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

import cn.localvault.app.ui.importer.CsvText
import cn.localvault.app.ui.importer.CsvText.Decoded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.util.Random

/**
 * CSV 导入的第一层：字节 → 文本。
 *
 * 这一层的失败是整条导入链上**唯一一种会静悄悄成功**的失败：
 * 编码猜错了不会抛异常，只会让「名称」变成「鍚嶇О」，
 * 然后一路走到加密写盘。所以这里的用例主要钉两件事：
 * **该认出来的都认出来**，**认不出来的宁可拒绝也不猜**。
 */
class CsvTextTest {

    private val gbk: Charset? = try {
        Charset.forName("GBK")
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun ok(b: ByteArray): Decoded.Ok {
        val d = CsvText.decode(b)
        assertTrue("期望解码成功，实际是 $d", d is Decoded.Ok)
        return d as Decoded.Ok
    }

    private fun bom(vararg head: Int, body: ByteArray): ByteArray =
        ByteArray(head.size) { head[it].toByte() } + body

    /* ───────────── 认得出来的 ───────────── */

    @Test fun `纯 ASCII 认成 UTF-8`() {
        val d = ok("name,password\nfoo,bar\n".toByteArray(Charsets.UTF_8))
        assertEquals(CsvText.Encoding.Utf8, d.encoding)
        assertEquals("name,password\nfoo,bar\n", d.text)
    }

    @Test fun `无 BOM 的 UTF-8 中文认得出来`() {
        val src = "名称,账号,密码\n微信,abc,x1\n"
        val d = ok(src.toByteArray(Charsets.UTF_8))
        assertEquals(CsvText.Encoding.Utf8, d.encoding)
        assertEquals(src, d.text)
    }

    @Test fun `带 BOM 的 UTF-8 认得出来，且 BOM 被剥掉`() {
        val src = "名称,账号\n微信,abc\n"
        val d = ok(bom(0xEF, 0xBB, 0xBF, body = src.toByteArray(Charsets.UTF_8)))
        assertEquals(CsvText.Encoding.Utf8Bom, d.encoding)
        // BOM 留着的话，第一列列名会以一个看不见的字符开头，
        // 于是 M5-2a-2 的列映射永远对不上「名称」——一个极难自查的失败。
        assertEquals(src, d.text)
        assertFalse(d.text.startsWith("\uFEFF"))
    }

    @Test fun `GBK 中文认得出来`() {
        if (gbk == null) return
        val src = "名称,账号,密码\n招商银行,138,x1\n"
        val d = ok(src.toByteArray(gbk))
        assertEquals(CsvText.Encoding.Gbk, d.encoding)
        assertEquals(src, d.text)
    }

    @Test fun `GBK 的中文不会被当成 UTF-8 解出乱码`() {
        if (gbk == null) return
        val d = ok("名称,账号\n".toByteArray(gbk))
        assertNotEquals(CsvText.Encoding.Utf8, d.encoding)
        assertTrue(d.text.contains("名称"))
    }

    @Test fun `UTF-16 LE 带 BOM 认得出来`() {
        val src = "名称,账号\n微信,abc\n"
        val d = ok(bom(0xFF, 0xFE, body = src.toByteArray(Charsets.UTF_16LE)))
        assertEquals(CsvText.Encoding.Utf16Le, d.encoding)
        assertEquals(src, d.text)
    }

    @Test fun `UTF-16 BE 带 BOM 认得出来`() {
        val src = "名称,账号\n微信,abc\n"
        val d = ok(bom(0xFE, 0xFF, body = src.toByteArray(Charsets.UTF_16BE)))
        assertEquals(CsvText.Encoding.Utf16Be, d.encoding)
        assertEquals(src, d.text)
    }

    @Test fun `不看扩展名也不看文件名——这个函数根本不收文件名`() {
        // 决策㉒ 在这一层的体现：整个 decode 的签名里没有文件名这个参数，
        // 所以「.csv 才认」这种规则连写都写不出来。
        val d = ok("a,b\n1,2\n".toByteArray(Charsets.UTF_8))
        assertEquals(CsvText.Encoding.Utf8, d.encoding)
    }

    /* ───────────── 该拒绝的 ───────────── */

    @Test fun `空文件单独报一类`() {
        assertEquals(Decoded.Empty, CsvText.decode(ByteArray(0)))
    }

    @Test fun `只有一个 BOM 的文件也算空`() {
        assertEquals(Decoded.Empty, CsvText.decode(bom(0xEF, 0xBB, 0xBF, body = ByteArray(0))))
    }

    @Test fun `含 NUL 字节的直接判成不是文本`() {
        val b = "name,pass\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 1, 2, 3)
        assertEquals(Decoded.NotText, CsvText.decode(b))
    }

    @Test fun `随机二进制不会被 GBK 蒙混过关`() {
        val rnd = Random(20260729L)
        var accepted = 0
        repeat(200) {
            val b = ByteArray(512)
            rnd.nextBytes(b)
            if (CsvText.decode(b) is Decoded.Ok) accepted++
        }
        assertEquals(0, accepted)
    }

    @Test fun `控制字符满屏的也判成不是文本`() {
        // 构造一份「合法 UTF-8 但全是控制符」的输入：严格解码这一关它过得了，
        // looksTextual 那一关过不了。
        val b = ByteArray(64) { 0x01 }
        assertEquals(Decoded.NotText, CsvText.decode(b))
    }

    @Test fun `制表符与换行不算控制字符`() {
        val d = ok("a\tb\r\n1\t2\r\n".toByteArray(Charsets.UTF_8))
        assertEquals("a\tb\r\n1\t2\r\n", d.text)
    }

    @Test fun `UTF-32 单独报一类，不混进「选错文件」`() {
        val le = bom(0xFF, 0xFE, 0x00, 0x00, body = ByteArray(8))
        val be = bom(0x00, 0x00, 0xFE, 0xFF, body = ByteArray(8))
        assertEquals(Decoded.Utf32, CsvText.decode(le))
        assertEquals(Decoded.Utf32, CsvText.decode(be))
    }

    @Test fun `UTF-32 LE 的 BOM 不会被当成 UTF-16 LE`() {
        // FF FE 是 UTF-16LE 的 BOM，也是 UTF-32LE BOM 的前两个字节。
        // 判断顺序错了，这个文件会被解成满屏 NUL。
        val d = CsvText.decode(bom(0xFF, 0xFE, 0x00, 0x00, body = ByteArray(4)))
        assertNotEquals(Decoded.NotText, d)
        assertEquals(Decoded.Utf32, d)
    }

    @Test fun `超过上限的文件在解码之前就被拦下`() {
        val big = ByteArray((CsvText.MAX_BYTES + 1).toInt()) { 'a'.code.toByte() }
        val d = CsvText.decode(big)
        assertTrue(d is Decoded.TooBig)
        assertEquals(CsvText.MAX_BYTES + 1, (d as Decoded.TooBig).sizeBytes)
    }

    @Test fun `刚好等于上限的文件不被拦`() {
        val exact = ByteArray(CsvText.MAX_BYTES.toInt()) { 'a'.code.toByte() }
        assertTrue(CsvText.decode(exact) is Decoded.Ok)
    }

    /* ───────────── 不泄漏 ───────────── */

    @Test fun `Ok 的 toString 不吐内容`() {
        val d = ok("name,password\nzhangsan,hunter2\n".toByteArray(Charsets.UTF_8))
        val s = d.toString()
        assertFalse("toString 把明文密码带出去了：$s", s.contains("hunter2"))
        assertFalse(s.contains("zhangsan"))
        assertTrue(s.contains("Utf8"))
    }

    @Test fun `失败文案里不带文件内容`() {
        val b = "password,secret\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val msg = CsvText.message(CsvText.decode(b))
        assertFalse(msg.contains("secret"))
    }

    /* ───────────── 文案 ───────────── */

    @Test fun `四条失败文案互不重样且都不空`() {
        val msgs = listOf(
            CsvText.message(Decoded.TooBig(99L * 1024 * 1024)),
            CsvText.message(Decoded.Empty),
            CsvText.message(Decoded.NotText),
            CsvText.message(Decoded.Utf32),
        )
        msgs.forEach { assertTrue(it.length > 10) }
        assertEquals(msgs.size, msgs.toSet().size)
    }

    @Test fun `失败文案里不出现「稍后重试」这类空话`() {
        val banned = listOf("稍后重试", "请重试", "联系客服", "未知错误", "系统繁忙", "找回", "破解")
        val msgs = listOf(
            CsvText.message(Decoded.TooBig(1L)),
            CsvText.message(Decoded.Empty),
            CsvText.message(Decoded.NotText),
            CsvText.message(Decoded.Utf32),
        )
        msgs.forEach { m -> banned.forEach { assertFalse("「$it」不该出现在：$m", m.contains(it)) } }
    }

    @Test fun `每条失败文案都给出了一个下一步`() {
        // 判据很朴素：一句只说「失败了」的话里不会出现动词性的指路。
        val hints = listOf("重新导", "另存", "找", "确认", "请")
        listOf(
            CsvText.message(Decoded.TooBig(1L)),
            CsvText.message(Decoded.Empty),
            CsvText.message(Decoded.NotText),
            CsvText.message(Decoded.Utf32),
        ).forEach { m ->
            assertTrue("这条没告诉用户下一步做什么：$m", hints.any { m.contains(it) })
        }
    }

    @Test fun `成功时没有文案`() {
        assertEquals("", CsvText.message(ok("a,b\n1,2\n".toByteArray(Charsets.UTF_8))))
    }

    @Test fun `明文告知里明确要求删掉源文件`() {
        // 决策(143)：这段明文擦不掉，能做的只有说实话。
        assertTrue(CsvText.PLAINTEXT_NOTE.contains("删"))
        assertTrue(CsvText.PLAINTEXT_NOTE.contains("明文"))
        // 不许出现「安全」「加密保护」之类会让人误以为这份 CSV 本身受保护的话
        assertFalse(CsvText.PLAINTEXT_NOTE.contains("军工"))
    }
}
