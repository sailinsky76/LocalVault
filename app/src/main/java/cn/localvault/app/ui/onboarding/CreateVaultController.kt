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

package cn.localvault.app.ui.onboarding

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.localvault.app.core.crypto.Argon2idKdf
import cn.localvault.app.core.crypto.KdfParams
import cn.localvault.app.core.crypto.KdfRegistry
import cn.localvault.app.core.crypto.wipe
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultCorruptedException
import cn.localvault.app.core.vault.VaultFile
import cn.localvault.app.core.vault.VaultFormatException
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.core.vault.WrongPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 建库这件事的执行者。页面只管收集表单，真正的动作全在这里。
 *
 * ── 为什么要单独拆一个类 ──
 *
 * 这一步同时跨了三个线程语境，混在 Composable 里必然写错：
 *
 *   · 读主密码必须在**主线程** —— 它躺在 `EditText` 的 `Editable` 里，
 *     那是 View 的内部状态，后台线程去读是未定义行为；
 *   · 派生密钥必须在**后台线程** —— Argon2id 一次 64 MiB 的派生要跑几百毫秒，
 *     放主线程就是一个稳定复现的 ANR；
 *   · 接管会话必须回到**主线程** —— 它会翻转导航相位。
 *
 * 所以口令的传递路径是：主线程 `copyChars()` 取一份副本 → 交给后台
 * → 后台用完立刻 `wipe()`。副本的生命周期短到能一眼看完，
 * 这是 [cn.localvault.app.ui.components.SecureTextState] 那套设计能兑现的前提。
 *
 * [worker] 和 [calibrator] 是留给单测的注入点：默认值就是线上行为，
 * 测试里换成 Unconfined + 廉价参数，这段逻辑就不必只靠上机点来验证。
 */
class CreateVaultController(
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
        /** 正在试这台机器扛得住哪一档参数 */
        data object Calibrating : Step
        /** 参数定了，正在派生密钥并写盘 */
        data object Sealing : Step
        data class Failed(val message: String) : Step
    }

    var step by mutableStateOf<Step>(Step.Idle)
        private set

    /** 校准结果。建成之后设置页要能显示「这台机器最后选了哪一档」。 */
    var chosenParams: KdfParams? = null
        private set

    val busy: Boolean get() = step is Step.Calibrating || step is Step.Sealing

    private var job: Job? = null

    /**
     * 建库。[password] 是调用方在主线程取的**副本**，本方法负责清零，
     * 调用方交出来之后就不要再碰它了 —— 包括在 busy 时被拒绝的那次。
     */
    fun create(password: CharArray) {
        if (busy) { password.wipe(); return }

        job = scope.launch {
            try {
                step = Step.Calibrating
                val params = withContext(worker) { calibrator(argon2Available) }
                chosenParams = params

                step = Step.Sealing
                val opened = withContext(worker) { sealOrAdopt(password, params) }

                // 会话接管：相位一变，整棵引导子树连同 back stack 一起被换掉
                session.onVaultCreated(opened)
                step = Step.Idle
            } catch (c: CancellationException) {
                // 取消不是失败。吞掉它会让协程的取消语义断在这里，
                // 而且会在界面上留下一条其实什么也没出错的红色横幅。
                throw c
            } catch (t: Throwable) {
                step = Step.Failed(explain(t))
            } finally {
                // 成功、失败、被取消，三条路都从这里出去，副本一定被抹掉
                password.wipe()
            }
        }
    }

    /**
     * 建库；如果磁盘上已经有库了，就用同一个口令把它打开。
     *
     * 这不是「顺手兼容一下」，是在补一个真实存在的夹缝：
     * `repo.create()` 写盘成功之后、`session.onVaultCreated()` 执行之前，
     * 进程可能被系统回收（低内存、字体/密度变化导致 Activity 重建都算）。
     * 那一瞬间磁盘上已经有一个完好的库，而内存里的会话还停在「未建库」。
     *
     * 不管这件事的话，用户看到的是：主密码明明设好了，再点「创建保险库」
     * 却弹出「保险库已存在」，而引导流程里根本没有解锁入口 —— 死在原地。
     * 用同一个口令打开它，是唯一正确的出路。
     *
     * 口令不对时 `unlock` 会抛 [cn.localvault.app.core.vault.WrongPasswordException]，
     * 照常报错 —— 这条路只是接上断掉的流程，不会掩盖真正的冲突。
     */
    private fun sealOrAdopt(password: CharArray, params: KdfParams): VaultFile.Opened =
        try {
            repo.create(password, params)
        } catch (e: IllegalStateException) {
            if (repo.exists()) repo.unlock(password) else throw e
        }

    fun dismissError() { if (step is Step.Failed) step = Step.Idle }

    fun cancel() { job?.cancel(); job = null }

    private fun explain(t: Throwable): String = when (t) {
        is WrongPasswordException ->
            // 走到这里说明磁盘上已经有一个库，而这个口令打不开它。
            // 多半是重装后残留的旧库，所以给的是「去恢复入口」而不是「再试一次」。
            "这台设备上已经有一个保险库，但刚才输入的主密码打不开它。" +
                "如果那是你的旧库，请用旧主密码；否则请先在系统设置里清除本应用数据。"
        is VaultCorruptedException, is VaultFormatException ->
            "已有的保险库文件无法识别，可能已损坏。请用备份文件恢复。"
        is java.io.IOException ->
            "写入失败，请确认存储空间充足后重试"
        else ->
            "创建失败：${t.javaClass.simpleName}${t.message?.let { "（$it）" } ?: ""}"
    }
}

/**
 * 挑一档这台设备扛得住的 KDF 参数。
 *
 * 不用固定参数的原因是手机性能差着一个数量级：旗舰上 64 MiB / t=3 只要 300ms，
 * 三年前的千元机可能要 2 秒。一刀切要么让低端机每次解锁都卡两秒，
 * 要么让旗舰白白浪费掉本可以拿到的抗爆破余量。
 *
 * 参数会写进文件头，所以**校准只影响新建，不影响兼容**：
 * 这台机器上定下的档位，换到别的机器打开同一个文件依然按原样派生。
 */
internal fun defaultKdfCalibration(argon2Available: Boolean): KdfParams =
    if (argon2Available && KdfRegistry.isAvailable(KdfParams.ID_ARGON2ID)) {
        runCatching { Argon2idKdf.calibrate() }.getOrDefault(KdfParams.ARGON2ID_LOW)
    } else {
        // Argon2 原生库没拉到。这不是崩溃理由，PBKDF2 依然安全，
        // 只是抗 GPU 爆破的成本低一些 —— 封条上会如实写 PBKDF2。
        KdfRegistry.preferredParams()
    }
