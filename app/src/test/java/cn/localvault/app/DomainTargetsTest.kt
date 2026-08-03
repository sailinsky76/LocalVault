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

import cn.localvault.app.ui.edit.DomainTargets
import cn.localvault.app.ui.edit.EntryForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「网址 / 应用」那份清单的增删与分类。
 *
 * 这里盯着四件在界面上验不动的事：
 *
 *  - **切行 / 去重仍然只有一份**。清单式界面的每一次增删最后都要落回
 *    [EntryForm.domainLines]，一旦这里另起炉灶写第二份，同一个库里就会出现两种数据
 *    （决策(55)）。
 *  - **原文一个字符都不改**（决策(56)）。用户手打的长网址不会因为后来在选择器里
 *    点了一下就被替换成光秃秃的主机名。
 *  - **分类用的是自动填充那一套判据**。界面上画着应用图标、实际按网址匹配，
 *    是比画错更糟的一种错。
 *  - **越界和空值一律「什么都不做」**。一次误触不该把整栏清空。
 */
class DomainTargetsTest {

    /* ── 分类 ── */

    @Test
    fun `包名判成应用，网址判成网址`() {
        assertEquals(DomainTargets.Kind.App, DomainTargets.kindOf("com.tencent.mm"))
        assertEquals(DomainTargets.Kind.App, DomainTargets.kindOf("tv.danmaku.bili"))
        assertEquals(DomainTargets.Kind.Web, DomainTargets.kindOf("mail.google.com"))
        assertEquals(DomainTargets.Kind.Web, DomainTargets.kindOf("163.com"))
    }

    @Test
    fun `完整网址先归一再分类，不会被路径带偏`() {
        assertEquals(
            DomainTargets.Kind.Web,
            DomainTargets.kindOf("https://mail.example.com/inbox?x=1"),
        )
    }

    /* ── 解析 ── */

    @Test
    fun `解析保留原文与顺序`() {
        val text = "https://mail.example.com/inbox\ncom.tencent.mm"
        val t = DomainTargets.parse(text)
        assertEquals(2, t.size)
        assertEquals("https://mail.example.com/inbox", t[0].raw)
        assertEquals(DomainTargets.Kind.Web, t[0].kind)
        assertEquals("com.tencent.mm", t[1].raw)
        assertEquals(DomainTargets.Kind.App, t[1].kind)
    }

    @Test
    fun `解析沿用 EntryForm 的切行与去重`() {
        val text = "example.com , example.com ;\n\nhttps://example.com/login\ncom.tencent.mm"
        val t = DomainTargets.parse(text)
        // example.com 的三种写法归一后是同一个主机，只留第一次出现的那个写法
        assertEquals(listOf("example.com", "com.tencent.mm"), t.map { it.raw })
    }

    @Test
    fun `只打了一个 scheme 的段落被丢掉`() {
        assertTrue(DomainTargets.parse("https://").isEmpty())
    }

    /* ── 增 ── */

    @Test
    fun `追加一行`() {
        val out = DomainTargets.add("example.com", "com.tencent.mm")
        assertEquals("example.com\ncom.tencent.mm", out)
    }

    @Test
    fun `已经有了就原样返回，且不动已有那一行的写法`() {
        val text = "https://mail.example.com/inbox"
        // 归一后同为 mail.example.com
        assertEquals(text, DomainTargets.add(text, "mail.example.com"))
    }

    @Test
    fun `空串和只有 scheme 的输入不产生新行`() {
        assertEquals("example.com", DomainTargets.add("example.com", "   "))
        assertEquals("example.com", DomainTargets.add("example.com", "https://"))
    }

    @Test
    fun `往空文本里加第一行`() {
        assertEquals("com.tencent.mm", DomainTargets.add("", "com.tencent.mm"))
    }

    /* ── 删 ── */

    @Test
    fun `按下标删的是清单上的第几张卡`() {
        val text = "a.com\ncom.tencent.mm\nb.com"
        assertEquals("a.com\nb.com", DomainTargets.removeAt(text, 1))
        assertEquals("com.tencent.mm\nb.com", DomainTargets.removeAt(text, 0))
    }

    @Test
    fun `下标越界什么都不做`() {
        val text = "a.com\nb.com"
        assertEquals(text, DomainTargets.removeAt(text, 9))
        assertEquals(text, DomainTargets.removeAt(text, -1))
    }

    @Test
    fun `按内容删忽略写法差异`() {
        val text = "https://mail.example.com/inbox\ncom.tencent.mm"
        assertEquals("com.tencent.mm", DomainTargets.remove(text, "MAIL.EXAMPLE.COM"))
    }

    /* ── 翻转 ── */

    @Test
    fun `toggle 一来一回回到原样`() {
        val text = "example.com"
        val once = DomainTargets.toggle(text, "com.tencent.mm")
        assertTrue(DomainTargets.contains(once, "com.tencent.mm"))
        val twice = DomainTargets.toggle(once, "com.tencent.mm")
        assertEquals(text, twice)
    }

    /* ── 给选择器打勾用的那一组键 ── */

    @Test
    fun `appKeys 只收包名，且是归一后的形式`() {
        val text = "example.com\nCom.Tencent.MM\nmail.google.com"
        assertEquals(setOf("com.tencent.mm"), DomainTargets.appKeys(text))
    }

    @Test
    fun `contains 对空输入返回 false，不会误判`() {
        assertFalse(DomainTargets.contains("example.com", ""))
        assertFalse(DomainTargets.contains("example.com", "https://"))
    }

    /* ── 和存储层的接缝 ── */

    @Test
    fun `增删之后的文本仍然是 EntryForm 认得的形状`() {
        var text = ""
        text = DomainTargets.add(text, "com.tencent.mm")
        text = DomainTargets.add(text, "https://example.com/login")
        // 再过一遍 domainLines 应该原地不动——说明这一层没有制造出需要二次清理的东西
        assertEquals(EntryForm.domainLines(text), text.split("\n"))
    }

    /* ── 日志泄漏 ── */

    @Test
    fun `toString 里不出现网址或包名`() {
        val t = DomainTargets.parse("com.tencent.mm").single()
        val s = t.toString()
        assertFalse(s.contains("tencent"))
        assertFalse(s.contains("mm"))
    }
}
