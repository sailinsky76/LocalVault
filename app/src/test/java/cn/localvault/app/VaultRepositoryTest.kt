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
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.core.vault.VaultStorage
import cn.localvault.app.core.vault.WrongPasswordException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val fast = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 1000, 1)
    private fun repo() = VaultRepository(VaultStorage(tmp.root))

    @Test
    fun `建库 → 解锁 → 加条目 → 重新解锁，数据都在`() {
        val r = repo()
        assertFalse(r.exists())

        r.create("master-pass".toCharArray(), fast).use { opened ->
            assertTrue(r.exists())
            assertEquals(0, opened.data.entries.size)
            val next = opened.data.copy(
                entries = listOf(VaultEntry(id = "a", name = "微信", password = "hK7#mQ2v"))
            )
            r.save(next, opened.vaultKey)
        }

        r.unlock("master-pass".toCharArray()).use {
            assertEquals(1, it.data.entries.size)
            assertEquals("微信", it.data.entries[0].name)
        }
    }

    @Test
    fun `错误主密码不能解锁`() {
        val r = repo()
        r.create("right".toCharArray(), fast).close()
        try {
            r.unlock("wrong".toCharArray()); fail("不该解开")
        } catch (e: WrongPasswordException) { /* 预期 */ }
    }

    @Test
    fun `快捷解锁路径：拿库主密钥直接开，不需要主密码`() {
        val r = repo()
        val key = r.create("master".toCharArray(), fast).use { opened ->
            r.save(opened.data.copy(entries = listOf(VaultEntry(id = "a", name = "支付宝"))), opened.vaultKey)
            opened.vaultKey.copy()      // 模拟从 Keystore 解出来的库主密钥
        }
        r.unlockWithKey(key).use {
            assertEquals("支付宝", it.data.entries[0].name)
        }
    }

    @Test
    fun `改主密码后，旧口令失效、新口令可用、快捷解锁的库主密钥依然有效`() {
        val r = repo()
        val key = r.create("old".toCharArray(), fast).use { opened ->
            r.save(opened.data.copy(entries = listOf(VaultEntry(id = "a", name = "GitHub"))), opened.vaultKey)
            r.changeMasterPassword("new".toCharArray(), opened.vaultKey, fast)
            opened.vaultKey.copy()
        }

        try { r.unlock("old".toCharArray()); fail("旧口令必须失效") }
        catch (e: WrongPasswordException) { /* 预期 */ }

        r.unlock("new".toCharArray()).use { assertEquals(1, it.data.entries.size) }
        // 库主密钥没变 → 指纹/PIN 绑定不需要重做，这是两层密钥设计的直接收益
        r.unlockWithKey(key).use { assertEquals("GitHub", it.data.entries[0].name) }
    }

    @Test
    fun `主文件损坏时自动回退到上一版备份`() {
        val r = repo()
        r.create("pw".toCharArray(), fast).use { opened ->
            r.save(opened.data.copy(entries = listOf(VaultEntry(id = "1", name = "第一版"))), opened.vaultKey)
            r.save(opened.data.copy(entries = listOf(VaultEntry(id = "2", name = "第二版"))), opened.vaultKey)
        }
        // 把主文件的密文改坏（保留文件头，模拟坏块而不是格式错误）
        val main = File(tmp.root, VaultStorage.NAME)
        val b = main.readBytes()
        b[b.size - 10] = (b[b.size - 10] + 1).toByte()
        main.writeBytes(b)

        r.unlock("pw".toCharArray()).use {
            assertEquals("应回退到上一版，丢失最后一次修改但不丢库", "第一版", it.data.entries[0].name)
        }
    }

    @Test
    fun `从备份文件恢复：这台设备上已经有库时，连口令都不看就拒绝`() {
        val r = repo()
        val foreign = VaultRepository(VaultStorage(tmp.newFolder("other"))).let { other ->
            other.create("other-pass".toCharArray(), fast).close()
            other.exportBytes()
        }
        r.create("mine".toCharArray(), fast).use { opened ->
            r.save(opened.data.copy(entries = listOf(VaultEntry(id = "x", name = "我的数据"))), opened.vaultKey)
        }

        // 两个口令各试一次，**一个错的一个对的**。
        // 钉的不是「结果是拒绝」，是「存在性检查排在验口令前面」（决策(135)）：
        // 只测错口令的话，一个把两道检查颠倒过来的实现照样能通过——
        // 而那种实现下，一次带着正确口令的恢复会把现有库整个盖掉。
        for (pw in listOf("错的口令", "other-pass")) {
            try {
                r.restoreAndOpen(foreign, pw.toCharArray())
                fail("这台设备上已经有库了，不该恢复成功")
            } catch (e: IllegalStateException) { /* 预期 */ }
        }

        r.unlock("mine".toCharArray()).use {
            assertEquals("现有库必须毫发无损", "我的数据", it.data.entries[0].name)
        }
    }

    @Test
    fun `不能在已有库的目录上重复建库`() {
        val r = repo()
        r.create("pw".toCharArray(), fast).close()
        try { r.create("pw2".toCharArray(), fast); fail("不该允许") }
        catch (e: IllegalStateException) { /* 预期 */ }
    }
}
