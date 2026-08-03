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

import cn.localvault.app.core.vault.VaultStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 落盘层测试。重点全在「崩在中间会怎样」——
 * 这些场景在真机上极难复现，但在用户那里一年总会撞上几次。
 */
class VaultStorageTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun storage() = VaultStorage(tmp.root)
    private fun blob(marker: Byte, size: Int = 200) = ByteArray(size) { marker }

    private fun f(name: String) = File(tmp.root, name)
    private val main get() = f(VaultStorage.NAME)
    private val bak get() = f("${VaultStorage.NAME}.bak")
    private val temp get() = f("${VaultStorage.NAME}.tmp")

    @Test
    fun `空目录时报告不存在，读出 null`() {
        val s = storage()
        assertFalse(s.exists())
        assertNull(s.load())
    }

    @Test
    fun `保存后能原样读回`() {
        val s = storage()
        s.save(blob(1))
        assertTrue(s.exists())
        assertArrayEquals(blob(1), s.load())
    }

    @Test
    fun `第二次保存后，上一版进入备份`() {
        val s = storage()
        s.save(blob(1))
        s.save(blob(2))
        assertArrayEquals(blob(2), s.load())
        assertArrayEquals(blob(1), s.loadBackup())
    }

    @Test
    fun `保存过程中不留临时文件`() {
        val s = storage()
        s.save(blob(1))
        assertFalse("tmp 文件应在提交后消失", temp.exists())
    }

    @Test
    fun `崩在写临时文件途中：主文件完好，垃圾 tmp 被清掉`() {
        val s = storage()
        s.save(blob(1))
        temp.writeBytes(byteArrayOf(9, 9, 9))     // 模拟半个 tmp

        assertArrayEquals(blob(1), s.load())
        assertFalse("残留的 tmp 必须被清理", temp.exists())
    }

    @Test
    fun `崩在改名之间：tmp 是完整新版本，应被扶正`() {
        val s = storage()
        s.save(blob(1))
        // 手工制造「main 已改名成 bak，tmp 还没转正」的残局
        main.renameTo(bak)
        temp.writeBytes(blob(2))

        assertArrayEquals("应扶正 tmp 而不是回退 bak", blob(2), s.load())
        assertTrue(main.exists())
    }

    @Test
    fun `主文件丢失且无 tmp：从备份恢复`() {
        val s = storage()
        s.save(blob(1))
        s.save(blob(2))
        main.delete()

        assertArrayEquals("应回退到上一版", blob(1), s.load())
    }

    @Test
    fun `拒绝写入明显不完整的内容`() {
        val s = storage()
        try {
            s.save(byteArrayOf(1, 2, 3))
            org.junit.Assert.fail("过短的内容必须被拒绝")
        } catch (e: IllegalArgumentException) { /* 预期 */ }
    }

    @Test
    fun `连续多次保存，最新版始终可读`() {
        val s = storage()
        repeat(20) { i -> s.save(blob((i + 1).toByte())) }
        assertArrayEquals(blob(20), s.load())
        assertArrayEquals(blob(19), s.loadBackup())
    }

    @Test
    fun `删除后彻底不存在`() {
        val s = storage()
        s.save(blob(1)); s.save(blob(2))
        assertTrue(s.deleteAll())
        assertFalse(s.exists())
        assertNull(s.load())
        assertEquals(0, tmp.root.listFiles()!!.count { it.name.startsWith(VaultStorage.NAME) })
    }
}
