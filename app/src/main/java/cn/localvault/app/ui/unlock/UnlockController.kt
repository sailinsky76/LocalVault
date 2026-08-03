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

package cn.localvault.app.ui.unlock

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.localvault.app.core.crypto.SecureBytes
import cn.localvault.app.core.crypto.wipe
import cn.localvault.app.core.keystore.AttemptLimiter
import cn.localvault.app.core.keystore.KeystoreFailure
import cn.localvault.app.core.keystore.KeystoreUnavailableException
import cn.localvault.app.core.keystore.WrongPinException
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
 * 解锁这件事的执行者。三个入口（主密码 / PIN / 生物识别解出的密钥）汇到同一套
 * 失败计数与退避规则上。页面只管收集输入，判断全在这里。
 *
 * ── 线程语境和建库那边一样 ──
 *
 * 读口令必须在主线程（它躺在 `EditText` 的 `Editable` 里），
 * 派生密钥必须在后台（Argon2id 一次几百毫秒，主线程上就是 ANR），
 * 接管会话必须回主线程（它会翻转导航相位）。所以口令的路径依旧是：
 * 主线程 `copyChars()` 取副本 → 交给后台 → 后台用完立刻 `wipe()`。
 * **包括被拒绝的那次**：调用方一旦把副本交出来就不该再碰它，
 * 于是「谁负责擦」这个问题永远只有一个答案。
 *
 * ── 这个类最要紧的一段是失败分类 ──
 *
 * 见 [recordFailure]。「输错了」和「文件坏了」必须走不同的路，
 * 前者罚等待，后者只报告——把后者也算进退避，等于让一次磁盘故障
 * 把用户锁在自己的数据外面 15 分钟。
 */
class UnlockController(
    private val repo: VaultRepository,
    private val session: VaultSession,
    private val guard: UnlockGuard,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limiter: AttemptLimiter = AttemptLimiter(now = clock),
) {

    @Immutable
    sealed interface Step {
        data object Idle : Step
        /** 正在派生密钥 / 解包。主密码那条路会在这里停几百毫秒。 */
        data object Deriving : Step
        data class Failed(val message: String) : Step
    }

    var step by mutableStateOf<Step>(Step.Idle)
        private set

    /**
     * 退避还剩多少毫秒。
     *
     * 刻意**不在这里跑计时器**：倒计时是界面的事，控制器只提供一个
     * 「按当前挂钟时间重算」的方法 [refreshLock]，由页面每半秒调一次。
     * 这样这个类里就没有任何 `delay`，测试可以把时钟往前拨而不必等待。
     *
     * 用挂钟而不是倒计数还有一个实际理由：`lockedUntil` 是存在 prefs 里的时间戳，
     * 用户杀掉进程重进、甚至重启手机，剩余时间照样准确。
     */
    var lockRemainingMillis by mutableLongStateOf(0L)
        private set

    /**
     * 快捷解锁刚刚被关掉了。界面**必须**为此说一句话——
     * 用户连错十次 PIN 之后突然只剩主密码框，不解释的话他只会觉得 App 坏了，
     * 而这恰恰是他最需要相信这个 App 的时刻。
     */
    var quickUnlockJustDisabled by mutableStateOf(false)
        private set

    val busy: Boolean get() = step is Step.Deriving
    val isLockedOut: Boolean get() = lockRemainingMillis > 0

    /** 现在能不能提交一次尝试。界面据此禁用按钮，但控制器自己也会再拦一道。 */
    val canAttempt: Boolean get() = !busy && !isLockedOut

    private var job: Job? = null

    /** 按当前时间重算退避剩余。界面每半秒调一次即可。 */
    fun refreshLock() {
        lockRemainingMillis = limiter.remainingLockMillis(guard.attemptState)
    }

    // ───────────────────── 三个入口 ─────────────────────

    /**
     * 主密码解锁。[password] 是调用方在主线程取的副本，本方法负责清零。
     */
    fun unlockWithMaster(password: CharArray) {
        if (!accept(password)) return
        job = scope.launch {
            try {
                val opened = withContext(worker) { repo.unlock(password) }
                adopt(opened)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                recordFailure(t, quick = false)
            } finally {
                password.wipe()
            }
        }
    }

    /**
     * PIN 解锁。[pin] 同样由本方法清零。
     *
     * 两步都放在后台：解 PIN 包裹要过一次 Argon2id（参数比主密码低，但也有几十毫秒），
     * 再走一次 Keystore 的设备绑定密钥——后者是 IPC，卡主线程的概率不低。
     */
    fun unlockWithPin(pin: CharArray) {
        if (!accept(pin)) return
        job = scope.launch {
            try {
                val opened = withContext(worker) {
                    guard.unlockWithPin(pin).use { key -> repo.unlockWithKey(key) }
                }
                adopt(opened)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                recordFailure(t, quick = true)
            } finally {
                pin.wipe()
            }
        }
    }

    /**
     * 生物识别已经通过，库主密钥也解出来了，剩下的只是打开文件。
     *
     * 这条路上**没有「凭据错误」这种可能**：指纹对不对是 BiometricPrompt
     * 和安全硬件之间的事，轮到我们时密钥要么在手上、要么这个方法根本不会被调用。
     * 所以这里出的任何错都不算一次失败尝试，不进退避。
     */
    fun unlockWithKey(key: SecureBytes) {
        refreshLock()
        if (!canAttempt) {
            key.wipe()
            if (isLockedOut) step = Step.Failed(LOCKED_OUT_MESSAGE)
            return
        }
        step = Step.Deriving
        job = scope.launch {
            try {
                val opened = withContext(worker) { key.use { repo.unlockWithKey(it) } }
                adopt(opened)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                step = Step.Failed(explain(t, quick = true))
            } finally {
                key.wipe()
            }
        }
    }

    /**
     * 生物识别在**我们这一侧之外**失败了（用户取消、指纹库变更、硬件不可用）。
     * 同样不计入退避：安全硬件自己就有限速，我们再罚一次是重复计费。
     */
    fun reportBiometricFailure(message: String) {
        step = Step.Failed(message)
    }

    // ───────────────────── 内部 ─────────────────────

    /**
     * 收下这一次尝试，或者当场拒绝。
     *
     * 拒绝时也要把副本擦掉：调用方交出来之后就不该再碰它。
     */
    private fun accept(secret: CharArray): Boolean {
        refreshLock()
        if (!canAttempt) {
            secret.wipe()
            if (isLockedOut) step = Step.Failed(LOCKED_OUT_MESSAGE)
            return false
        }
        step = Step.Deriving
        return true
    }

    private fun adopt(opened: VaultFile.Opened) {
        // 成功了：退避清零，快捷解锁的失败次数也清零——本人回来了。
        guard.attemptState = limiter.onSuccess()
        guard.quickFailCount = 0
        lockRemainingMillis = 0L
        quickUnlockJustDisabled = false
        step = Step.Idle
        // 放在最后：相位一变整棵子树连同这个控制器一起被销毁，
        // 之后再赋值就是在往一个已经不存在的界面上写状态。
        session.adopt(opened)
    }

    /**
     * 一次失败该怎么记。这个方法里有两条不能合并的界线。
     *
     * ── 界线一：「输错了」≠「出故障了」 ──
     *
     * 只有凭据错误（主密码不对、PIN 不对）才计入退避。
     * 文件损坏、读盘失败、Keystore 抽风都不算——它们既不是攻击的迹象，
     * 也不是用户能通过「等一会儿」解决的问题。把故障也算进去，
     * 结果是一块坏掉的闪存把用户锁在自己的数据外面 15 分钟，
     * 而他真正需要的是赶紧看到「请用备份恢复」这句话。
     *
     * ── 界线二：主密码错，不关掉快捷解锁 ──
     *
     * [AttemptLimiter.shouldDisableQuickUnlock] 的用意是：有人在爆破 6 位 PIN，
     * 那就把这道 10⁶ 的门关掉，逼他去啃主密码。这是对的。
     *
     * 但如果**主密码**输错十次也触发同一个动作，方向就反了：
     * 那说明用户恰恰是「记不清主密码」的状态，而这时候把他唯一还记得的
     * PIN / 指纹也关掉，等于亲手把他锁在门外——攻击者反而毫发无伤。
     * 所以两个计数器分开：退避共用一份（锁定期一到所有入口一起关），
     * 「要不要关掉快捷解锁」只看快捷解锁自己错了多少次。
     */
    private fun recordFailure(t: Throwable, quick: Boolean) {
        val credentialWrong = t is WrongPasswordException || t is WrongPinException
        if (credentialWrong) {
            guard.attemptState = limiter.onFailure(guard.attemptState)

            if (quick) {
                val n = guard.quickFailCount + 1
                guard.quickFailCount = n
                if (limiter.shouldDisableQuickUnlock(AttemptLimiter.State(failCount = n))) {
                    guard.disableQuickUnlock()
                    guard.quickFailCount = 0
                    quickUnlockJustDisabled = true
                }
            }
            refreshLock()
        }
        step = Step.Failed(explain(t, quick))
    }

    fun dismissError() { if (step is Step.Failed) step = Step.Idle }

    fun acknowledgeQuickUnlockDisabled() { quickUnlockJustDisabled = false }

    fun cancel() { job?.cancel(); job = null }

    private fun explain(t: Throwable, quick: Boolean): String = when (t) {
        is WrongPasswordException ->
            if (isLockedOut) "主密码不正确。连续输错多次，需要等待后才能再试。"
            else "主密码不正确"

        is WrongPinException ->
            if (isLockedOut) "PIN 不正确。连续输错多次，需要等待后才能再试。"
            else "PIN 不正确"

        is KeystoreUnavailableException ->
            /*
             * 安全硬件那一侧出了事，不是用户记错了凭据。
             *
             * 三句话分开，因为**下一步该做什么完全不同**：
             * 「钥匙没了」要重新绑定，「硬件拒收/设备被认为锁着」重新绑定也没用。
             * 上一版这里只有一句「请重新开启一次」，对后两种情况是在支使用户
             * 去做一件必定白费的事——而且做完之后症状一模一样，
             * 他会合理地认为这个应用坏了。
             *
             * 三句的共同落点都是「主密码还能开门、数据一条没动」：
             * 那句话在这里是真的，而且是此刻唯一要紧的一件事。
             */
            when (t.failure) {
                KeystoreFailure.KeyInvalidated ->
                    "这台设备上的快捷解锁绑定已经失效了（安全硬件里的钥匙不在了，" +
                        "通常是指纹或锁屏凭据变动过）。请用主密码解锁，" +
                        "再到「设置 → 快捷解锁」里重新开启一次；保险库里的数据一条都没动。"

                KeystoreFailure.NoSecureCredential ->
                    "这台设备现在没有可用的锁屏凭据或指纹，快捷解锁暂时用不了。" +
                        "请用主密码解锁；保险库里的数据一条都没动。"

                KeystoreFailure.DeviceLocked ->
                    "系统现在认为这台设备处于锁定状态，快捷解锁的钥匙暂时不能用。" +
                        "请用主密码解锁；保险库里的数据一条都没动。"

                // 重新绑定治不了这一类——问题在这台设备的安全硬件不接受我们的规格。
                // 所以这一句**不提**「重新开启一次」。
                KeystoreFailure.SpecRejected, KeystoreFailure.Unknown ->
                    "这台设备的安全硬件没能完成快捷解锁需要的操作。" +
                        "请用主密码解锁；保险库里的数据一条都没动。"
            }

        is VaultCorruptedException ->
            // repo.unlock 内部已经自动回退试过上一版备份了，走到这里说明两份都打不开。
            "保险库文件已损坏，主文件和自动备份都无法解密。请用导出的备份文件恢复。"

        is VaultFormatException ->
            "保险库文件无法识别，可能不是本应用创建的文件，或已被改写。"

        is IllegalStateException ->
            if (quick) "快捷解锁的凭据已失效，请用主密码解锁。"
            else "找不到保险库文件。如果刚清理过应用数据，请用备份文件恢复。"

        is java.io.IOException ->
            "读取保险库失败：${t.message ?: "存储可能出了问题"}。请重试；若反复失败，请用备份文件恢复。"

        else ->
            "解锁失败：${t.javaClass.simpleName}${t.message?.let { "（$it）" } ?: ""}"
    }

    companion object {
        const val LOCKED_OUT_MESSAGE = "还在等待冷却，暂时不能再试"
    }
}
