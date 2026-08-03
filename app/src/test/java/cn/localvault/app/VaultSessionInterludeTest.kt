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
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.core.vault.VaultStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「可信中断」的自动锁定行为。
 *
 * 被测的问题很具体：拉起系统文件选择器会让 Activity 走 `onStop`，
 * 从会话看和用户按 Home 键离开一模一样。默认 60 秒自动锁定的情况下，
 * 用户在选择器里翻两层文件夹回来，库已经锁了，导出白做。
 * 把自动锁定设成「立即」的用户则**永远无法完成一次导出**。
 *
 * 这组用例同时也在钉死另一半：宽限是**有限**的。
 * 「我们把用户送出去了」不等于「用户一定会回来」——
 * 他可能在选择器里按了 Home 键把手机往桌上一放。
 */
class VaultSessionInterludeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val fast = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 1000, 1)
    private var now = 1_700_000_000_000L
    private val scope = CoroutineScope(Job())

    @After fun tearDown() = scope.cancel()

    private fun unlocked(autoLockSeconds: Int? = null): VaultSession {
        val repo = VaultRepository(VaultStorage(tmp.root))
        val s = VaultSession(repo, scope, clock = { now })
        s.onVaultCreated(repo.create("correct horse battery".toCharArray(), fast))
        if (autoLockSeconds != null) s.updateMeta { it.copy(autoLockSeconds = autoLockSeconds) }
        return s
    }

    /** 对照组：没有中断标记时，超时照锁。 */
    @Test
    fun `常规情况下切后台超时会锁`() {
        val s = unlocked(autoLockSeconds = 60)
        s.onEnterBackground()
        now += 90_000
        s.onEnterForeground()
        assertFalse(s.isUnlocked)
    }

    @Test
    fun `中断期内切后台超过常规超时也不锁`() {
        val s = unlocked(autoLockSeconds = 60)
        s.beginSystemInterlude()
        s.onEnterBackground()

        now += 90_000            // 远超 60 秒，但还在 180 秒宽限内
        s.onEnterForeground()

        assertTrue(s.isUnlocked)
    }

    @Test
    fun `宽限用完照样锁`() {
        val s = unlocked(autoLockSeconds = 60)
        s.beginSystemInterlude()
        s.onEnterBackground()

        now += 200_000           // 超过 180 秒宽限：人多半不回来了
        s.onEnterForeground()

        assertFalse(s.isUnlocked)
    }

    /**
     * 把自动锁定设成「立即」的用户，以前是做不完一次导出的：
     * 文件选择器一弹出来库就锁了。
     */
    @Test
    fun `自动锁定设为立即时，中断期内也不会立刻锁`() {
        val s = unlocked(autoLockSeconds = 0)
        s.beginSystemInterlude()
        s.onEnterBackground()
        assertTrue(s.isUnlocked)

        now += 10_000
        s.onEnterForeground()
        assertTrue(s.isUnlocked)
    }

    @Test
    fun `没有中断标记时，自动锁定设为立即依然立刻锁`() {
        val s = unlocked(autoLockSeconds = 0)
        s.onEnterBackground()
        assertFalse(s.isUnlocked)
    }

    /**
     * 真机上的顺序是 `onStart` 先于 activity result 回调，
     * 所以回到前台时中断标记还在——这一步不能锁。
     * 标记由回调清掉，之后才恢复常规超时。
     */
    @Test
    fun `结束中断后，下一次切后台按常规超时算`() {
        val s = unlocked(autoLockSeconds = 60)
        s.beginSystemInterlude()
        s.onEnterBackground()
        now += 30_000
        s.onEnterForeground()          // 回到前台，还在中断里，不锁
        assertTrue(s.isUnlocked)

        s.endSystemInterlude()         // 文件选择器的结果回来了

        s.onEnterBackground()          // 这次是用户自己走的
        now += 90_000
        s.onEnterForeground()
        assertFalse(s.isUnlocked)
    }

    @Test
    fun `锁定会清掉悬着的中断标记`() {
        val s = unlocked(autoLockSeconds = 60)
        s.beginSystemInterlude()
        s.lock()
        assertFalse(s.isUnlocked)

        // 重新解锁后，上一次留下的标记不该还在起作用
        s.unlock("correct horse battery".toCharArray())
        assertTrue(s.isUnlocked)

        s.onEnterBackground()
        now += 90_000
        s.onEnterForeground()
        assertFalse(s.isUnlocked)
    }
}
