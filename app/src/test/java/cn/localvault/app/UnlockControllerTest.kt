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

package cn.localvault.app

import cn.localvault.app.core.crypto.KdfParams
import cn.localvault.app.core.crypto.SecureBytes
import cn.localvault.app.core.keystore.AttemptLimiter
import cn.localvault.app.core.keystore.WrongPinException
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.core.vault.VaultStorage
import cn.localvault.app.ui.unlock.UnlockController
import cn.localvault.app.ui.unlock.UnlockGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * 解锁流程的单测。
 *
 * [UnlockGuard] 这个接口存在的意义在这里体现：失败计数存在 SharedPreferences 里、
 * PIN 包裹外面套着 Keystore，两样都上不了 JVM。抽成接口之后，
 * 「错几次开始等」「什么时候关掉快捷解锁」「哪些错误不该罚用户」
 * 这三件最容易写错的事就能在这里钉死，不必靠上机反复输错密码来验证。
 *
 * 时钟是注入的，所以「等 15 分钟」在测试里是一行 `now += ...`。
 */
class UnlockControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 单测里用最便宜的 KDF：这里要验的是流程，不是密码学。 */
    private val fast = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 1000, 1)
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private var now = 1_700_000_000_000L

    private val correct = "correct horse battery staple"

    @After fun tearDown() = scope.cancel()

    // ── 假的守卫：把 prefs 和 Keystore 换成内存里的几个变量 ──

    private class FakeGuard(
        private val realKey: ByteArray,
        val rightPin: String = "246810",
    ) : UnlockGuard {
        override var attemptState = AttemptLimiter.State()
        override var quickFailCount = 0
        override var isPinEnrolled = true
        override var isBiometricEnrolled = true
        var disableCalls = 0
        /** 让 PIN 那条路模拟一次非凭据故障（Keystore 抽风） */
        var pinThrowsIo = false

        override fun unlockWithPin(pin: CharArray): SecureBytes {
            if (pinThrowsIo) throw IOException("Keystore 不可用")
            if (String(pin) != rightPin) throw WrongPinException()
            return SecureBytes.of(realKey)
        }

        override fun disableQuickUnlock() {
            disableCalls++
            isPinEnrolled = false
            isBiometricEnrolled = false
            // 注意退避状态**不清**，见 QuickUnlockGuard 的注释
        }
    }

    // ── 夹具 ──

    private class Rig(
        val controller: UnlockController,
        val session: VaultSession,
        val guard: FakeGuard,
    )

    private fun rig(): Rig {
        val repo = VaultRepository(VaultStorage(tmp.root))
        val opened = repo.create(correct.toCharArray(), fast)
        val keyCopy = opened.vaultKey.copy()
        opened.close()

        val session = VaultSession(repo, scope, clock = { now })
        val guard = FakeGuard(keyCopy)
        val controller = UnlockController(
            repo = repo,
            session = session,
            guard = guard,
            scope = scope,
            worker = Dispatchers.Unconfined,
            clock = { now },
        )
        return Rig(controller, session, guard)
    }

    private fun UnlockController.tryMaster(pw: String) = unlockWithMaster(pw.toCharArray())
    private fun UnlockController.tryPin(pin: String) = unlockWithPin(pin.toCharArray())

    // ─────────────────────── 基本路径 ───────────────────────

    @Test
    fun `主密码正确就解锁，并把失败计数清零`() {
        val r = rig()
        r.guard.attemptState = AttemptLimiter.State(failCount = 3)
        r.guard.quickFailCount = 2

        r.controller.tryMaster(correct)

        assertTrue("会话应已解锁", r.session.isUnlocked)
        assertEquals(UnlockController.Step.Idle, r.controller.step)
        assertEquals("成功后失败次数应归零", 0, r.guard.attemptState.failCount)
        assertEquals("成功后快捷失败次数也应归零", 0, r.guard.quickFailCount)
    }

    @Test
    fun `主密码错误不解锁，且计入失败`() {
        val r = rig()
        r.controller.tryMaster("wrong password here")

        assertFalse(r.session.isUnlocked)
        assertTrue(r.controller.step is UnlockController.Step.Failed)
        assertEquals(1, r.guard.attemptState.failCount)
    }

    @Test
    fun `PIN 正确也能解锁`() {
        val r = rig()
        r.controller.tryPin(r.guard.rightPin)
        assertTrue(r.session.isUnlocked)
    }

    @Test
    fun `交出去的口令副本一定被擦掉，包括被拒绝的那一次`() {
        val r = rig()

        val wrong = "not the password".toCharArray()
        r.controller.unlockWithMaster(wrong)
        assertTrue("失败路径要擦", wrong.all { it == '\u0000' })

        // 冷却期内的提交是**当场拒绝**的，连协程都不会起——
        // 这条路最容易忘记擦，因为它看起来「什么都没发生」。
        repeat(4) { r.controller.tryMaster("nope $it") }
        r.controller.refreshLock()
        assertTrue(r.controller.isLockedOut)

        val rejected = correct.toCharArray()
        r.controller.unlockWithMaster(rejected)
        assertTrue("被拒绝的那次也要擦", rejected.all { it == '\u0000' })
        assertFalse(r.session.isUnlocked)

        now += 3_600_000L
        val ok = correct.toCharArray()
        r.controller.unlockWithMaster(ok)
        assertTrue("成功路径要擦", ok.all { it == '\u0000' })
        assertTrue(r.session.isUnlocked)
    }

    // ─────────────────────── 退避 ───────────────────────

    @Test
    fun `连错五次进入冷却，冷却期内不再消耗尝试机会`() {
        val r = rig()
        repeat(5) { r.controller.tryMaster("nope $it") }

        r.controller.refreshLock()
        assertTrue("第五次之后应进入冷却", r.controller.isLockedOut)
        assertFalse(r.controller.canAttempt)

        val before = r.guard.attemptState.failCount
        r.controller.tryMaster("nope again")
        assertEquals("冷却期内的提交不该再加一次失败", before, r.guard.attemptState.failCount)
        assertEquals(
            UnlockController.Step.Failed(UnlockController.LOCKED_OUT_MESSAGE),
            r.controller.step,
        )
    }

    @Test
    fun `冷却期满后正确口令照样能开`() {
        val r = rig()
        repeat(5) { r.controller.tryMaster("nope $it") }
        r.controller.refreshLock()
        val wait = r.controller.lockRemainingMillis
        assertTrue(wait > 0)

        now += wait
        r.controller.refreshLock()
        assertFalse("时间到了就该解除", r.controller.isLockedOut)

        r.controller.tryMaster(correct)
        assertTrue(r.session.isUnlocked)
    }

    @Test
    fun `冷却是所有入口共用的，换成 PIN 也绕不过去`() {
        val r = rig()
        repeat(5) { r.controller.tryMaster("nope $it") }
        r.controller.refreshLock()

        val before = r.guard.quickFailCount
        r.controller.tryPin(r.guard.rightPin)

        assertFalse("冷却期内即使 PIN 正确也不该开", r.session.isUnlocked)
        assertEquals(before, r.guard.quickFailCount)
    }

    // ─────────────── 关掉快捷解锁：这条界线是本模块的重点 ───────────────

    @Test
    fun `PIN 连错十次会关掉快捷解锁，但数据一条不动`() {
        val r = rig()
        repeat(AttemptLimiter.DISABLE_QUICK_UNLOCK_AT) {
            // 每次都把时间往前拨，跳过冷却，专门验计数这条线
            now += 3_600_000L
            r.controller.tryPin("000000")
        }

        assertEquals("应当只关一次", 1, r.guard.disableCalls)
        assertFalse(r.guard.isPinEnrolled)
        assertFalse(r.guard.isBiometricEnrolled)
        assertTrue("界面必须能知道并解释这件事", r.controller.quickUnlockJustDisabled)

        // 关键：库还在，主密码照样能开
        now += 3_600_000L
        r.controller.tryMaster(correct)
        assertTrue("关掉快捷解锁绝不能影响主密码解锁", r.session.isUnlocked)
    }

    @Test
    fun `主密码连错十次不会关掉快捷解锁`() {
        val r = rig()
        repeat(AttemptLimiter.DISABLE_QUICK_UNLOCK_AT + 2) {
            now += 3_600_000L
            r.controller.tryMaster("wrong $it")
        }

        assertEquals("主密码的失败不该殃及 PIN", 0, r.guard.disableCalls)
        assertTrue(r.guard.isPinEnrolled)
        assertEquals(0, r.guard.quickFailCount)
        assertFalse(r.controller.quickUnlockJustDisabled)

        // 记不清主密码的人，还得靠 PIN 进得去——这正是不关它的理由
        now += 3_600_000L
        r.controller.tryPin(r.guard.rightPin)
        assertTrue(r.session.isUnlocked)
    }

    // ─────────────── 故障 ≠ 输错 ───────────────

    @Test
    fun `读盘失败之类的故障不计入退避`() {
        val r = rig()
        r.guard.pinThrowsIo = true

        repeat(6) { r.controller.tryPin(r.guard.rightPin) }

        assertEquals("故障不该罚用户等待", 0, r.guard.attemptState.failCount)
        assertEquals(0, r.guard.quickFailCount)
        r.controller.refreshLock()
        assertFalse(r.controller.isLockedOut)
        assertTrue(r.controller.step is UnlockController.Step.Failed)
    }

    @Test
    fun `冷却时间会跟着失败次数变长`() {
        val r = rig()
        repeat(5) { r.controller.tryMaster("nope $it") }
        r.controller.refreshLock()
        val first = r.controller.lockRemainingMillis

        now += first
        r.controller.tryMaster("nope more")
        r.controller.refreshLock()
        assertTrue("第六次的等待应比第五次长", r.controller.lockRemainingMillis > first)
    }

    // ─────────────── 自动锁定的原因要能被解锁页读到 ───────────────

    @Test
    fun `自动锁定的原因会一直留到下次解锁成功`() {
        val r = rig()
        r.controller.tryMaster(correct)
        assertEquals(VaultSession.LockReason.None, r.session.lastLockReason)

        // 切后台，自动锁定超时设为立即
        r.session.updateMeta { it.copy(autoLockSeconds = 0) }
        r.session.onEnterBackground()
        assertFalse(r.session.isUnlocked)
        assertEquals(VaultSession.LockReason.AutoTimeout, r.session.lastLockReason)

        // 锁着的时候再调一次 lock() 不该把原因抹成「手动」
        r.session.lock()
        assertEquals(VaultSession.LockReason.AutoTimeout, r.session.lastLockReason)

        r.controller.tryMaster(correct)
        assertEquals(VaultSession.LockReason.None, r.session.lastLockReason)
    }
}
