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
import cn.localvault.app.core.crypto.constantTimeEquals
import cn.localvault.app.core.crypto.toUtf8Secure
import cn.localvault.app.core.vault.VaultCorruptedException
import cn.localvault.app.core.vault.VaultData
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.core.vault.VaultFile
import cn.localvault.app.core.vault.VaultFormatException
import cn.localvault.app.core.vault.WrongPasswordException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 保险库文件格式的单元测试。跑在纯 JVM 上，不需要设备。
 *
 * 用 PBKDF2 的低迭代档，是为了让整个测试套件在一秒内跑完；
 * 生产参数由 KdfRegistry.preferredParams() 决定，与这里无关。
 */
class VaultFileTest {

    private val fastKdf = KdfParams(KdfParams.ID_PBKDF2_SHA512, memoryKiB = 0, iterations = 1000, parallelism = 1)

    private fun sampleData() = VaultData(
        entries = listOf(
            VaultEntry(id = "1", name = "微信", username = "138****6621", password = "hK7#mQ2vRt\$9", category = "社交"),
            VaultEntry(id = "2", name = "招商银行", username = "6225****4471", password = "Cm8@vN4!wT6z", category = "金融"),
        ),
    )

    @Test
    fun `创建后能用同一口令打开，内容完全一致`() {
        val pwd = "正确的马电池订书钉".toCharArray().toUtf8Secure()
        val file = pwd.use { VaultFile.create(it, sampleData(), fastKdf) }

        "正确的马电池订书钉".toCharArray().toUtf8Secure().use { p ->
            VaultFile.open(file, p).use { opened ->
                assertEquals(2, opened.data.entries.size)
                assertEquals("微信", opened.data.entries[0].name)
                assertEquals("Cm8@vN4!wT6z", opened.data.entries[1].password)
                assertEquals(32, opened.vaultKey.size)
            }
        }
    }

    @Test
    fun `口令错误抛 WrongPasswordException 而不是解出垃圾`() {
        val file = "correct horse".toCharArray().toUtf8Secure().use { VaultFile.create(it, sampleData(), fastKdf) }
        try {
            "correct hors".toCharArray().toUtf8Secure().use { VaultFile.open(file, it) }
            fail("应该抛 WrongPasswordException")
        } catch (e: WrongPasswordException) { /* 预期 */ }
    }

    @Test
    fun `同样的内容加密两次，密文必须不同（salt 与 nonce 随机）`() {
        val a = "pw".toCharArray().toUtf8Secure().use { VaultFile.create(it, sampleData(), fastKdf) }
        val b = "pw".toCharArray().toUtf8Secure().use { VaultFile.create(it, sampleData(), fastKdf) }
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `篡改文件头的 KDF 参数会导致解密失败（防降级攻击）`() {
        val file = "pw".toCharArray().toUtf8Secure().use { VaultFile.create(it, sampleData(), fastKdf) }
        // 偏移 13..16 是迭代次数，改成 1 试图让爆破成本归零
        file[13] = 0; file[14] = 0; file[15] = 0; file[16] = 1
        try {
            "pw".toCharArray().toUtf8Secure().use { VaultFile.open(file, it) }
            fail("篡改后不应该能打开")
        } catch (e: Exception) {
            assertTrue("应当被认证机制拒绝", e is WrongPasswordException || e is VaultFormatException)
        }
    }

    @Test
    fun `篡改密文任意一个字节都会被检测出来`() {
        val file = "pw".toCharArray().toUtf8Secure().use { VaultFile.create(it, sampleData(), fastKdf) }
        file[file.size - 20] = (file[file.size - 20] + 1).toByte()
        try {
            "pw".toCharArray().toUtf8Secure().use { VaultFile.open(file, it) }
            fail("篡改后不应该能打开")
        } catch (e: VaultCorruptedException) { /* 预期 */ }
    }

    @Test
    fun `不是保险库的文件给出可读的错误`() {
        try {
            VaultFile.open("这是一张照片".toByteArray(), ByteArray(4))
            fail("应该拒绝")
        } catch (e: VaultFormatException) {
            assertTrue(e.message!!.contains("保险库"))
        }
    }

    @Test
    fun `reseal 复用主密钥保存，不需要重新派生口令`() {
        val file = "pw".toCharArray().toUtf8Secure().use { VaultFile.create(it, sampleData(), fastKdf) }
        val updated = "pw".toCharArray().toUtf8Secure().use { p ->
            VaultFile.open(file, p).use { opened ->
                val newData = opened.data.copy(
                    entries = opened.data.entries + VaultEntry(id = "3", name = "GitHub", password = "Gh2^tP9!xJ5w")
                )
                VaultFile.reseal(file, opened.vaultKey.bytes(), newData)
            }
        }
        "pw".toCharArray().toUtf8Secure().use { p ->
            VaultFile.open(updated, p).use { assertEquals(3, it.data.entries.size) }
        }
    }

    @Test
    fun `reseal 每次都换新 nonce，不重用`() {
        val file = "pw".toCharArray().toUtf8Secure().use { VaultFile.create(it, sampleData(), fastKdf) }
        val (r1, r2) = "pw".toCharArray().toUtf8Secure().use { p ->
            VaultFile.open(file, p).use { o ->
                VaultFile.reseal(file, o.vaultKey.bytes(), o.data) to
                    VaultFile.reseal(file, o.vaultKey.bytes(), o.data)
            }
        }
        assertNotEquals(r1.toList(), r2.toList())
    }

    @Test
    fun `改主密码后旧口令失效、新口令可用、数据不丢`() {
        val file = "old-pass".toCharArray().toUtf8Secure().use { VaultFile.create(it, sampleData(), fastKdf) }
        val rewrapped = "old-pass".toCharArray().toUtf8Secure().use { p ->
            VaultFile.open(file, p).use { o ->
                "new-pass".toCharArray().toUtf8Secure().use { np ->
                    VaultFile.rewrap(file, o.vaultKey.bytes(), np, fastKdf)
                }
            }
        }
        try {
            "old-pass".toCharArray().toUtf8Secure().use { VaultFile.open(rewrapped, it) }
            fail("旧口令必须失效")
        } catch (e: WrongPasswordException) { /* 预期 */ }

        "new-pass".toCharArray().toUtf8Secure().use { p ->
            VaultFile.open(rewrapped, p).use { assertEquals(2, it.data.entries.size) }
        }
    }

    @Test
    fun `中文与 emoji 口令能正确往返`() {
        val pwd = "口令🔐中文Ａ"
        val file = pwd.toCharArray().toUtf8Secure().use { VaultFile.create(it, sampleData(), fastKdf) }
        pwd.toCharArray().toUtf8Secure().use { p ->
            VaultFile.open(file, p).use { assertEquals("微信", it.data.entries[0].name) }
        }
    }

    @Test
    fun `手写 UTF8 编码与标准库结果一致`() {
        for (s in listOf("abc", "中文测试", "🔐🗝️", "mixed 混合 123 !@#", "\u0000边界")) {
            s.toCharArray().toUtf8Secure().use { assertArrayEquals(s.toByteArray(Charsets.UTF_8), it) }
        }
    }

    @Test
    fun `SecureBytes 清零后不可再用`() {
        val sb = cn.localvault.app.core.crypto.SecureBytes.of(byteArrayOf(1, 2, 3))
        sb.wipe()
        try {
            sb.bytes(); fail("清零后应拒绝访问")
        } catch (e: IllegalStateException) { /* 预期 */ }
    }

    @Test
    fun `恒定时间比较结果正确`() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertTrue(!constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertTrue(!constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }
}
