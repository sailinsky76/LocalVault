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
import cn.localvault.app.core.crypto.wipe
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 删库时要一起清掉的、长在 Android 上的那几样东西。
 *
 * 抽成接口的理由和 `UnlockGuard` 一模一样：Keystore 和 SharedPreferences
 * 在纯 JVM 上跑不起来，而「按什么顺序清」恰恰是这一步最要紧、
 * 又最不该靠上机点一遍来验证的部分。线上实现见 [QuickUnlockRemnants]。
 *
 * 注意它和 `UnlockGuard.disableQuickUnlock()` **不是同一件事**：
 * 那一个刻意保留退避计数（关掉 PIN 不能顺带把等待时间也清零，否则是在奖励爆破）；
 * 这一个要求清得一点不剩——库都没了，为一个不存在的库留着失败计数没有意义，
 * 而且下一次建库时那份计数会凭空作用在一个全新的库上。
 */
interface VaultRemnants {

    /**
     * 快捷解锁的全部痕迹：两份包裹、盐和 KDF 参数、退避计数、
     * 以及 Keystore 里那两把钥匙。
     */
    fun purgeQuickUnlock()

    /**
     * 剪贴板里可能还躺着刚才复制出去的一个密码。
     *
     * 这一条容易漏：倒计时是挂在 Application scope 上的（决策⑬），
     * 库删掉不会让它停下来，于是「保险库已经不存在了，
     * 而它里面的一个密码还躺在系统剪贴板里等着被粘贴」这种状态是真会出现的。
     */
    fun clearClipboard()
}

/**
 * 删除保险库这件事的执行者。
 *
 * ── 三步的顺序是这个文件里最要紧的东西 ──
 *
 *   1. 验主密码
 *   2. 清快捷解锁的残留（Keystore 钥匙 + prefs）、清剪贴板
 *   3. 删库文件
 *
 * 反过来（先删文件再清残留）看着更顺手，因为「主要动作」先做完了。
 * 但那个顺序的中途失败是**不可收拾**的：文件已经没了，
 * 而 prefs 里还躺着一份包着某个已不存在的库的主密钥的包裹，
 * Keystore 里还留着两把钥匙。它们不会导致崩溃，只会在用户下一次建库时
 * 变成一堆解释不清的脏数据（`isAnyEnrolled` 为 true，解出来的钥匙却开不了新库）。
 *
 * 现在这个顺序的中途失败则是**可收拾**的：库还在、数据一条没少，
 * 代价只是快捷解锁被关了——而用户刚刚才输过主密码，一定进得去，
 * 重新开一次就完事。见 `DeleteVaultModel.Failure.FilesRemain`。
 *
 * ── 为什么以「库还在不在」为准，不以删除的返回值为准 ──
 *
 * `VaultStorage.deleteAll()` 会在**任何一个**文件（主文件、临时文件、上一版备份）
 * 删不掉时返回 false。但真正决定成败的只有一件事：这台设备上还能不能打开这个库。
 * 一个残留的 `.tmp`（上次写盘崩在第一步留下的垃圾，本来就不是完整的库）
 * 不该让整件事被报成失败，让用户对着一个其实已经删干净的库再点一次。
 * 所以第 3 步之后再问一次 [VaultRepository.exists]，以它为准。
 */
class DeleteVaultController(
    private val repo: VaultRepository,
    private val session: VaultSession,
    private val remnants: VaultRemnants,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {

    @Immutable
    sealed interface Step {
        data object Idle : Step

        /** 正在派生密钥核对主密码。低配机上这一步要一两秒 */
        data object Verifying : Step

        /** 口令对了，正在清快捷解锁和剪贴板 */
        data object Purging : Step

        /** 正在删文件 */
        data object Deleting : Step

        /**
         * 删完了。
         *
         * 这是个**几乎看不见**的终态：`session.onVaultDeleted()` 一调，
         * 相位就翻回 `NoVault`，整棵已解锁子树连同这一页一起被换成欢迎页（决策⑪）。
         * 留着它只为一件事——万一将来相位切换被改成带动画的，
         * 这一帧上按钮不该还是可点的。**不要在这个状态上加成功提示**：
         * 欢迎页本身就是回执，再弹一句「已删除」是拿一块屏幕说废话。
         */
        data object Done : Step

        data class Failed(val reason: DeleteVaultModel.Failure) : Step
    }

    var step by mutableStateOf<Step>(Step.Idle)
        private set

    val busy: Boolean
        get() = step is Step.Verifying || step is Step.Purging || step is Step.Deleting

    val done: Boolean get() = step is Step.Done

    private var job: Job? = null

    /**
     * 删。[password] 是调用方在主线程取的**副本**，本方法负责清零，
     * 交出来之后就不要再碰它了——包括 busy 时被拒绝的那一次。
     */
    fun submit(password: CharArray) {
        if (busy || done) { password.wipe(); return }

        job = scope.launch {
            /*
             * 残留清过没有。**这个标记只为了让失败文案不撒谎。**
             *
             * `Failure.FilesRemain` 那段话里写着「快捷解锁已经在这一步之前被关掉了」，
             * 而验口令那一步就抛出来的异常（比如读盘失败）发生在清残留**之前**，
             * 那时候指纹和 PIN 一个都没动。同一句话在两种情况下一真一假，
             * 所以归类必须知道走到哪儿了。
             */
            var purged = false
            try {
                step = Step.Verifying
                val ok = withContext(worker) { repo.verifyMasterPassword(password) }
                if (!ok) {
                    step = Step.Failed(DeleteVaultModel.Failure.WrongPassword)
                    return@launch
                }

                /*
                 * 从这里开始清残留。这两步都用 runCatching 兜住：
                 * 它们失败不该让整件事停下来——用户要的是「把库删掉」，
                 * 为一个 Keystore 抽风而保住一个他已经决定不要的库，
                 * 只会让他再点一次、再抽风一次。清不掉的那部分是脏数据，
                 * 不是安全问题（那两把钥匙包的是一个马上就不存在的库）。
                 */
                step = Step.Purging
                withContext(worker) {
                    runCatching { remnants.purgeQuickUnlock() }
                    runCatching { remnants.clearClipboard() }
                }
                purged = true

                step = Step.Deleting
                val gone = withContext(worker) {
                    repo.deleteEverything()
                    // 以「还在不在」为准，不以返回值为准。见类注释。
                    !repo.exists()
                }
                if (!gone) {
                    step = Step.Failed(DeleteVaultModel.Failure.FilesRemain)
                    return@launch
                }

                /*
                 * 会话相位翻回 NoVault。
                 *
                 * 必须是最后一步：它一执行，整棵已解锁子树（包括这一页和这个控制器）
                 * 就会被换掉。放在前面的话，后面那些代码是在一棵正在被销毁的树上跑的。
                 */
                session.onVaultDeleted()
                step = Step.Done
            } catch (c: CancellationException) {
                // 取消不是失败。同建库 / 改密码控制器。
                throw c
            } catch (t: Throwable) {
                step = Step.Failed(classify(t, purged))
            } finally {
                password.wipe()
            }
        }
    }

    fun dismissError() { if (step is Step.Failed) step = Step.Idle }

    fun cancel() { job?.cancel(); job = null }

    /**
     * 异常归类。
     *
     * 这里**没有** `WrongPasswordException` 那一支：口令不对的正常出口是
     * `verifyMasterPassword` 返回 false。真从别处抛出来（比如库文件在这几百毫秒里
     * 坏掉了），那不是「你打错了」。
     *
     * [purged] 决定用哪一条文案，理由见上面那个变量的注释：
     * `FilesRemain` 那段话里有一句「快捷解锁已经被关掉了」，
     * 清残留之前抛出来的异常配上这句话就是假的，那种情况一律走 Unknown——
     * 它说的「库还在，数据一条没少」在两种情况下都成立。
     */
    private fun classify(t: Throwable, purged: Boolean): DeleteVaultModel.Failure = when {
        !purged -> DeleteVaultModel.Failure.Unknown
        t is java.io.IOException -> DeleteVaultModel.Failure.FilesRemain
        else -> DeleteVaultModel.Failure.Unknown
    }
}
