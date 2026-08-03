/*
 * Copyright (C) 2026 sailinsky76
 *
 * This file is part of LocalVault (本地保险库).
 *
 * LocalVault is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LocalVault is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LocalVault.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package cn.localvault.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.localvault.app.core.crypto.KdfParams
import cn.localvault.app.core.crypto.wipe
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.core.vault.VaultWriteVerificationException
import cn.localvault.app.core.vault.WrongPasswordException
import cn.localvault.app.ui.onboarding.defaultKdfCalibration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 修改主密码这件事的执行者。页面只管收集三个输入框，真正的动作全在这里。
 *
 * 结构和 `CreateVaultController` 一样，理由也一样：这一步跨三个线程语境
 * （主线程读 `Editable`、后台派生密钥、回主线程更新会话），
 * 混在 Composable 里必然写错。口令的传递路径同样是
 * 「主线程 `copyChars()` 取副本 → 交给后台 → 后台用完立刻 `wipe()`」。
 *
 * ── 四个阶段，每一个都要在屏幕上说出来 ──
 *
 * 这一页的等待时间是建库页的两倍多：先用**旧**参数派生一次（验证旧口令），
 * 再用**新**参数派生一次（重新包裹）。低配机上加起来能到三四秒。
 * 中间不吭声的话，用户会以为卡死了，而这一刻他最不想看到的就是
 * 一个正在改他主密码的程序失去响应。
 *
 * ── 为什么要验证旧主密码 ──
 *
 * 决策(98) 说过：改安全设置之前**不**再要求验证一次身份，因为能走到设置页的人
 * 早就能看到库里每一条密码了，再验一遍谁也挡不住。
 * 这一页是那条规矩的例外，理由不是「更安全」，是**这个动作会伤到真正的用户**：
 * 改完之后，他手上所有旧备份都只认旧口令，而旧口令刚刚被换掉。
 * 一个把手机放在桌上转身接水的人，回来发现主密码被人改了——
 * 那不只是「别人看到了我的密码」，那是他自己再也进不去了。
 * 所以这里验的不是「你有没有权限」，是「你是不是知道旧口令的那个人」，
 * 顺带也拦住了纯粹的误触（这一页上任何一步做完都撤不回来）。
 *
 * ── 为什么不接退避 ──
 *
 * 旧口令在这儿输错不进 `AttemptLimiter`。那套退避守的是**门**，
 * 而走到这一页的时候门已经开着了；在这儿罚一次，罚到的是一个已经在库里的人——
 * 表现是他被自己的保险库锁在门外 15 分钟，而他并没有做错什么。
 * 真正的限速是 KDF 本身：每错一次都要实打实跑一遍 Argon2id，
 * 这台设备上一秒钟试不了两次，而攻击者要是能一直坐在一台解锁的手机前，
 * 他早就把里面的密码抄完了，犯不着来猜主密码。
 */
class ChangeMasterController(
    private val repo: VaultRepository,
    private val session: VaultSession,
    private val scope: CoroutineScope,
    private val argon2Available: Boolean,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val calibrator: (Boolean) -> KdfParams = ::defaultKdfCalibration,
) {

    @Immutable
    sealed interface Step {
        data object Idle : Step

        /** 正在用旧参数派生，核对当前主密码 */
        data object Verifying : Step

        /** 旧口令对了，正在试这台机器扛得住哪一档新参数 */
        data object Calibrating : Step

        /** 参数定了，正在重新包裹并写盘 */
        data object Sealing : Step

        /**
         * 改完了。**这是个终态**，页面在这儿换成另一屏（成功卡片 + 去备份），
         * 不再退回表单——那三个输入框此刻装的东西已经没有意义了。
         */
        data object Done : Step

        data class Failed(val reason: ChangeMasterModel.Failure) : Step
    }

    var step by mutableStateOf<Step>(Step.Idle)
        private set

    /** 新库头最后落在哪一档 KDF 上。改完之后顶部封条显示的就是它。 */
    var chosenParams: KdfParams? = null
        private set

    val busy: Boolean
        get() = step is Step.Verifying || step is Step.Calibrating || step is Step.Sealing

    val done: Boolean get() = step is Step.Done

    private var job: Job? = null

    /**
     * 改密码。[oldPassword] 和 [newPassword] 都是调用方在主线程取的**副本**，
     * 本方法负责清零，交出来之后就不要再碰它们了——包括 busy 时被拒绝的那一次。
     */
    fun submit(oldPassword: CharArray, newPassword: CharArray) {
        if (busy || done) {
            oldPassword.wipe(); newPassword.wipe(); return
        }

        job = scope.launch {
            try {
                step = Step.Verifying
                val ok = withContext(worker) { repo.verifyMasterPassword(oldPassword) }
                if (!ok) {
                    step = Step.Failed(ChangeMasterModel.Failure.WrongOld)
                    return@launch
                }

                /*
                 * 顺便按这台设备重新测算一次 KDF 档位。
                 *
                 * 这是整个 App 里**唯一**能把一个老库的加密档位提上来的时机：
                 * 参数写在文件头里，只有重新包裹主密钥时才会被换掉（决策①）。
                 * 换了新手机、或者从一台老机器的备份恢复过来的库，
                 * 会一直带着当年那台机器定下的低档参数跑下去，
                 * 而用户完全看不出来——封条上倒是如实写着，但没人会去对。
                 *
                 * 反过来，在一台更慢的设备上改密码会把档位**调低**。那也是对的：
                 * 校准的目标从来不是「越高越好」，是「这台机器能承受的最高档」。
                 * 高到每次解锁要等三秒，用户的应对方式是把快捷解锁一开了事，
                 * 那才是真的降低了安全性。
                 */
                step = Step.Calibrating
                val params = withContext(worker) { calibrator(argon2Available) }
                chosenParams = params

                step = Step.Sealing
                val header = withContext(worker) {
                    session.withVaultKey { key ->
                        repo.changeMasterPassword(newPassword, key, params)
                    }
                }

                // 从这一行往下，磁盘上的主密码**已经换掉了**。
                // 后面任何一步失败都不能再报「修改失败」——那会是一句谎话，
                // 而且是最坏的那种：用户会继续用旧口令，然后发现开不了门。
                session.onMasterPasswordChanged(header)

                /*
                 * 记一笔修改时间。**失败了也不算改密码失败。**
                 *
                 * 它只是个时间戳，用来回答「手上那份备份还认不认现在的主密码」
                 * （见 `VaultMeta.masterChangedAt`）。写不进去的后果是设置页那一行
                 * 少一句提醒；而把整件事判为失败的后果是用户以为密码没改成。
                 * 两者不在一个量级。
                 *
                 * 顺带一提，这一步走的是 `updateMeta` → `mutate` → 落盘，
                 * 它读的是刚刚写下去的那个文件，用的是没变过的库主密钥（决策①），
                 * 所以它和上面那次写盘不会打架。
                 */
                runCatching { session.updateMeta { it.copy(masterChangedAt = System.currentTimeMillis()) } }

                step = Step.Done
            } catch (c: CancellationException) {
                // 取消不是失败。同建库控制器：吞掉它会让协程的取消语义断在这里。
                throw c
            } catch (t: Throwable) {
                step = Step.Failed(classify(t))
            } finally {
                // 成功、失败、被取消，三条路都从这里出去，两份副本一定被抹掉
                oldPassword.wipe()
                newPassword.wipe()
            }
        }
    }

    fun dismissError() { if (step is Step.Failed) step = Step.Idle }

    fun cancel() { job?.cancel(); job = null }

    /**
     * 异常归类。
     *
     * [WrongPasswordException] 在这里是**兜底**而不是主路径：
     * 旧口令不对的正常出口是 `verifyMasterPassword` 返回 false。
     * 真从这儿抛出来，说明是在重新包裹那一步、用新口令回读时没解开——
     * 那属于自检没过，不该跟「你打错了」共用一句话。
     */
    private fun classify(t: Throwable): ChangeMasterModel.Failure = when (t) {
        is VaultWriteVerificationException -> ChangeMasterModel.Failure.WriteVerify
        is WrongPasswordException -> ChangeMasterModel.Failure.WriteVerify
        is java.io.IOException -> ChangeMasterModel.Failure.Io
        else -> ChangeMasterModel.Failure.Unknown
    }
}
