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
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.core.vault.VaultStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultSessionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val fast = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 1000, 1)
    private var now = 1_700_000_000_000L
    private val scope = CoroutineScope(Job())

    @After fun tearDown() = scope.cancel()

    private fun session(): Pair<VaultSession, VaultRepository> {
        val repo = VaultRepository(VaultStorage(tmp.root))
        return VaultSession(repo, scope, clock = { now }) to repo
    }

    private fun unlockedSession(): Pair<VaultSession, VaultRepository> {
        val (s, r) = session()
        s.onVaultCreated(r.create("pw".toCharArray(), fast))
        return s to r
    }

    @Test
    fun `没有库时状态是 NoVault`() {
        val (s, _) = session()
        assertEquals(VaultSession.State.NoVault, s.state.value)
    }

    @Test
    fun `建库后进入已解锁状态`() {
        val (s, _) = unlockedSession()
        assertTrue(s.isUnlocked)
        assertNotNull(s.data)
    }

    @Test
    fun `锁定后状态变 Locked，且拿不到数据`() {
        val (s, _) = unlockedSession()
        s.lock()
        assertEquals(VaultSession.State.Locked, s.state.value)
        assertFalse(s.isUnlocked)
        assertEquals(null, s.data)
    }

    @Test
    fun `新增条目会立刻落盘，重新解锁后还在`() {
        val (s, r) = unlockedSession()
        s.addEntry(VaultEntry(id = "", name = "招商银行", password = "Cm8@vN4!"))
        s.lock()

        r.unlock("pw".toCharArray()).use {
            assertEquals(1, it.data.entries.size)
            assertEquals("招商银行", it.data.entries[0].name)
            assertTrue("应自动补上 id", it.data.entries[0].id.isNotEmpty())
        }
    }

    @Test
    fun `改密码时才刷新 passwordUpdatedAt，只改备注不刷新`() {
        val (s, _) = unlockedSession()
        s.addEntry(VaultEntry(id = "a", name = "淘宝", password = "old-pw"))
        val created = s.data!!.entries[0]

        now += 86_400_000L
        s.updateEntry(created.copy(notes = "加个备注"))
        assertEquals("密码没变，时间不该刷新",
            created.passwordUpdatedAt, s.data!!.entries[0].passwordUpdatedAt)

        now += 86_400_000L
        s.updateEntry(s.data!!.entries[0].copy(password = "new-pw"))
        assertEquals("密码变了，时间应刷新到现在", now, s.data!!.entries[0].passwordUpdatedAt)
    }

    @Test
    fun `删除条目同样立刻落盘`() {
        val (s, r) = unlockedSession()
        s.addEntry(VaultEntry(id = "a", name = "京东"))
        s.addEntry(VaultEntry(id = "b", name = "Steam"))
        s.deleteEntry("a")
        s.lock()

        r.unlock("pw".toCharArray()).use {
            assertEquals(1, it.data.entries.size)
            assertEquals("Steam", it.data.entries[0].name)
        }
    }

    @Test
    fun `切后台超过超时时间，回前台立即锁定`() {
        val (s, _) = unlockedSession()
        s.updateMeta { it.copy(autoLockSeconds = 60) }

        s.onEnterBackground()
        now += 61_000L               // 进程被冻结，协程没跑，靠挂钟时间兜底
        s.onEnterForeground()

        assertFalse("超时后必须锁上", s.isUnlocked)
    }

    @Test
    fun `切后台但很快回来，保持解锁`() {
        val (s, _) = unlockedSession()
        s.updateMeta { it.copy(autoLockSeconds = 60) }

        s.onEnterBackground()
        now += 5_000L
        s.onEnterForeground()

        assertTrue("5 秒内回来不该被锁", s.isUnlocked)
    }

    @Test
    fun `超时设为 0 表示切后台立即锁`() {
        val (s, _) = unlockedSession()
        s.updateMeta { it.copy(autoLockSeconds = 0) }
        s.onEnterBackground()
        assertFalse(s.isUnlocked)
    }

    @Test
    fun `锁定后不能再改数据`() {
        val (s, _) = unlockedSession()
        s.lock()
        assertTrue(s.addEntry(VaultEntry(id = "a", name = "不该成功")).isFailure)
    }

    @Test
    fun `markBackedUp 会记下时间，首页据此判断要不要提醒备份`() {
        val (s, _) = unlockedSession()
        assertEquals("新库应视为从未备份", 0L, s.data!!.meta.lastBackupAt)
        s.markBackedUp()
        assertEquals(now, s.data!!.meta.lastBackupAt)
    }
}
