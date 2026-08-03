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

import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.list.SearchHighlight
import cn.localvault.app.ui.list.VaultIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索结果的切片与高亮。
 *
 * 这些性质在界面上几乎没法验证：要看出「命中被尾部截断吃掉了」，
 * 得先造一条长度刚刚好的账号，再拿一个位置刚刚好的关键词去搜，
 * 然后盯着一行 12 号字数字符。切成纯函数之后，全部能在这里走一遍。
 */
class SearchHighlightTest {

    private fun rangeOf(text: String, sub: String): IntRange {
        val i = text.indexOf(sub)
        require(i >= 0) { "测试数据本身有问题：$text 里没有 $sub" }
        return i..(i + sub.length - 1)
    }

    /** 有没有落单的代理字符——切开一对 emoji 的直接症状。 */
    private fun hasUnpairedSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                i += 2
            } else {
                if (c.isLowSurrogate()) return true
                i++
            }
        }
        return false
    }

    /* ─────────────────── 基本切片 ─────────────────── */

    @Test
    fun `短文本原样返回，两端都不加省略号`() {
        val t = "招商银行"
        val s = SearchHighlight.snippet(t, rangeOf(t, "招商"))
        assertFalse(s.leadingEllipsis)
        assertFalse(s.trailingEllipsis)
        assertEquals(t, s.segments.joinToString("") { it.text })
    }

    @Test
    fun `命中的那一段被标为高亮，且内容就是关键词`() {
        val t = "accounts.example.com"
        val s = SearchHighlight.snippet(t, rangeOf(t, "example"))
        assertEquals("example", s.highlightedText)
        assertEquals(1, s.segments.count { it.highlighted })
    }

    @Test
    fun `命中在开头时不加前省略号`() {
        val t = "example.com"
        val s = SearchHighlight.snippet(t, rangeOf(t, "exam"))
        assertFalse(s.leadingEllipsis)
        assertEquals(0, s.segments.indexOfFirst { it.highlighted })
    }

    @Test
    fun `命中在结尾时不加后省略号`() {
        val t = "com.tencent.mm"
        val s = SearchHighlight.snippet(t, rangeOf(t, "mm"))
        assertFalse(s.trailingEllipsis)
        assertTrue(s.segments.last().highlighted)
    }

    /* ─────────────────── 这个文件存在的理由 ─────────────────── */

    @Test
    fun `长文本里靠后的命中不会被吃掉——窗口以命中为中心，不是从头截`() {
        // 尾部截断的经典失败：屏幕上只剩前 34 个字符，命中的 example 一个字母都看不见。
        val t = "zhangsan_backup_2019@company-mail.example.com"
        assertTrue("测试数据得足够长才有意义", t.length > SearchHighlight.DEFAULT_WINDOW)

        val s = SearchHighlight.snippet(t, rangeOf(t, "example"))
        assertEquals("example", s.highlightedText)
        assertTrue("前面的内容被截掉了就该有省略号", s.leadingEllipsis)
        assertTrue(s.plain.contains("example"))
    }

    @Test
    fun `关键词比窗口还长时，宁可这行更长也不切掉高亮`() {
        val long = "a".repeat(60)
        val t = "x$long"
        val s = SearchHighlight.snippet(t, 1..60, window = 20)
        assertEquals(60, s.highlightedText.length)
    }

    @Test
    fun `窗口大致守住宽度，不会把整条超长文本都吐出来`() {
        val t = "u".repeat(40) + "target" + "v".repeat(40)
        val s = SearchHighlight.snippet(t, rangeOf(t, "target"), window = 30)
        val shown = s.segments.sumOf { it.text.length }
        assertTrue("实际长度 $shown", shown in 30..34)
        assertTrue(s.leadingEllipsis)
        assertTrue(s.trailingEllipsis)
    }

    @Test
    fun `名称行用更窄的窗口`() {
        val t = "n".repeat(30) + "命中" + "m".repeat(30)
        val wide = SearchHighlight.snippet(t, rangeOf(t, "命中"))
        val narrow = SearchHighlight.nameSnippet(t, rangeOf(t, "命中"))
        assertTrue(
            narrow.segments.sumOf { it.text.length } < wide.segments.sumOf { it.text.length },
        )
    }

    /* ─────────────────── 退化与边界 ─────────────────── */

    @Test
    fun `空文本不产生任何片段`() {
        val s = SearchHighlight.snippet("", 0..0)
        assertTrue(s.segments.isEmpty())
        assertEquals("", s.plain)
    }

    @Test
    fun `空区间退化成不高亮，而不是给出一个错位的高亮`() {
        val t = "i".repeat(80)
        val s = SearchHighlight.snippet(t, IntRange.EMPTY)
        assertTrue(s.segments.none { it.highlighted })
        assertTrue(s.trailingEllipsis)
        assertFalse(s.leadingEllipsis)
    }

    @Test
    fun `越界的区间同样退化，不抛异常`() {
        val t = "短"
        val s = SearchHighlight.snippet(t, 5..9)
        assertTrue(s.segments.none { it.highlighted })
        assertEquals("短", s.plain)
    }

    @Test
    fun `左边界不会切开一对 emoji`() {
        val t = "💼".repeat(20) + "target" + "z"
        val s = SearchHighlight.snippet(t, rangeOf(t, "target"))
        assertFalse(hasUnpairedSurrogate(s.segments.joinToString("") { it.text }))
        assertEquals("target", s.highlightedText)
    }

    @Test
    fun `右边界不会切开一对 emoji`() {
        val t = "targe" + "💼".repeat(20)
        val s = SearchHighlight.snippet(t, rangeOf(t, "targe"))
        assertFalse(hasUnpairedSurrogate(s.segments.joinToString("") { it.text }))
        assertEquals("targe", s.highlightedText)
    }

    @Test
    fun `plain 会把省略号一起拼回来，供无障碍朗读使用`() {
        val t = "w".repeat(50) + "命中"
        val s = SearchHighlight.snippet(t, rangeOf(t, "命中"))
        assertTrue(s.plain.startsWith("…"))
        assertTrue(s.plain.endsWith("命中"))
    }

    /* ─────────────────── 字段标签 ─────────────────── */

    @Test
    fun `名称命中不标字段——它本来就占着主位`() {
        assertNull(SearchHighlight.fieldLabel(VaultIndex.Field.Name))
    }

    @Test
    fun `另外三个字段都要标出来，否则两行看起来一模一样`() {
        assertEquals("账号", SearchHighlight.fieldLabel(VaultIndex.Field.Username))
        assertEquals("网址", SearchHighlight.fieldLabel(VaultIndex.Field.Domain))
        assertEquals("分类", SearchHighlight.fieldLabel(VaultIndex.Field.Category))
    }

    @Test
    fun `朗读描述里要说清命中在哪个字段`() {
        val e = VaultEntry(id = "1", name = "招商银行", username = "13800000000")
        val hit = VaultIndex.search(listOf(e), "138").first()
        assertEquals("招商银行，在账号中匹配", SearchHighlight.describe(hit))
    }

    /* ─────────────────── 和搜索内核对接 ─────────────────── */

    @Test
    fun `搜索给出的区间可以直接拿来切片，高亮出来就是用户打的那几个字`() {
        val entries = listOf(
            VaultEntry(id = "1", name = "Google 广告", domains = listOf("https://ads.google.com/aw/overview")),
            VaultEntry(id = "2", name = "网易邮箱", username = "someone@163.com"),
        )
        val hits = VaultIndex.search(entries, "google")
        assertTrue(hits.isNotEmpty())
        val hit = hits.first()
        val s = SearchHighlight.snippet(hit.text, hit.range)
        assertEquals("google", s.highlightedText.lowercase())
    }

    @Test
    fun `归一后的网址切片不会带上路径，因为搜索给的就是归一后的文本`() {
        val e = VaultEntry(
            id = "1",
            name = "某站",
            domains = listOf("HTTPS://user:pw@Accounts.Example.com:8443/login?next=%2F"),
        )
        val hit = VaultIndex.search(listOf(e), "example").first()
        assertEquals(VaultIndex.Field.Domain, hit.field)
        val s = SearchHighlight.snippet(hit.text, hit.range)
        assertFalse(s.plain.contains("login"))
        assertEquals("example", s.highlightedText)
    }

    @Test
    fun `备注命中不存在，所以也永远轮不到切片这一步`() {
        val e = VaultEntry(id = "1", name = "某站", notes = "身份证 110101")
        assertTrue(VaultIndex.search(listOf(e), "110101").isEmpty())
        assertNotNull(SearchHighlight.fieldLabel(VaultIndex.Field.Category)) // 白名单里只有这四个
    }
}
