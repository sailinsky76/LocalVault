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
import cn.localvault.app.ui.autofill.AutofillMatch
import cn.localvault.app.ui.autofill.DomainMatch
import cn.localvault.app.ui.autofill.HostTrust
import cn.localvault.app.ui.autofill.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「填充条上出现哪几条、按什么顺序」。
 *
 * 排序在真机上看得见但说不清——「为什么这一条排前面」没法在屏幕上验证，
 * 而一个总把小号排在主号前面的填充条，用户每次都要多点一下，
 * 久了就不用了。切成纯函数之后全部能在这里走一遍。
 */
class AutofillMatchTest {

    private var seq = 0

    private fun e(
        name: String,
        domains: List<String> = emptyList(),
        username: String = "u",
        password: String = "p",
        favorite: Boolean = false,
        updatedAt: Long = 0L,
    ) = VaultEntry(
        id = "id-${seq++}",
        name = name,
        username = username,
        password = password,
        domains = domains,
        favorite = favorite,
        updatedAt = updatedAt,
    )

    private val trustChrome: HostTrust = object : HostTrust {
        override fun isTrustedBrowser(packageName: String) = packageName == "com.android.chrome"
    }

    private fun web(host: String, app: String = "com.android.chrome") = Origin.Web(host, app)

    /* ══════════════════════════ 挑得对 ══════════════════════════ */

    @Test
    fun `只有精确档和兄弟档会被自动建议`() {
        val entries = listOf(
            e("对得上", listOf("example.com")),
            e("兄弟", listOf("mail.example.com")),
            e("不相干", listOf("other.com")),
            e("是个应用", listOf("com.tencent.mm")),
            e("没网址"),
        )
        val s = AutofillMatch.suggest(web("example.com"), entries, trustChrome)
        assertEquals(2, s.total)
        assertEquals(listOf("对得上", "兄弟"), s.shown.map { it.entry.name })
    }

    @Test
    fun `宿主不是浏览器时一条都不自动建议`() {
        // AutoSpill 那条路的最终表现：填充条上什么都不出现。
        val entries = listOf(e("对得上", listOf("example.com")))
        val s = AutofillMatch.suggest(
            Origin.Web("example.com", "com.evil.wallpapers"),
            entries,
            trustChrome,
        )
        assertTrue(s.isEmpty)
        assertEquals(0, s.total)
    }

    @Test
    fun `原生应用里只认包名条目`() {
        val entries = listOf(
            e("微博应用", listOf("com.sina.weibo")),
            e("微博网站", listOf("weibo.com")),
        )
        val s = AutofillMatch.suggest(Origin.App("com.sina.weibo"), entries, trustChrome)
        assertEquals(1, s.total)
        assertEquals("微博应用", s.shown[0].entry.name)
    }

    @Test
    fun `什么都填不出来的条目不上填充条`() {
        // 账号密码都空的条目在库里合法（决策(149)：没有密码的行照样导入）。
        // 让它出现，用户点下去会发现什么都没发生，然后以为功能坏了。
        val entries = listOf(
            e("空壳", listOf("example.com"), username = "", password = ""),
            e("只有账号", listOf("example.com"), username = "someone", password = ""),
        )
        val s = AutofillMatch.suggest(web("example.com"), entries, trustChrome)
        // 只有账号的要留着——不少登录页把账号和密码分成两屏。
        assertEquals(1, s.total)
        assertEquals("只有账号", s.shown[0].entry.name)
    }

    @Test
    fun `命中的那一行原文要带出来`() {
        val entries = listOf(e("多网址", listOf("other.com", "https://mail.example.com/inbox")))
        val s = AutofillMatch.suggest(web("login.example.com"), entries, trustChrome)
        assertEquals(1, s.total)
        assertEquals(DomainMatch.Verdict.SameSite, s.shown[0].verdict)
        assertEquals("https://mail.example.com/inbox", s.shown[0].matchedDomain)
    }

    /* ══════════════════════════ 排得对 ══════════════════════════ */

    @Test
    fun `精确档整体压过兄弟档`() {
        val entries = listOf(
            e("兄弟但收藏", listOf("mail.example.com"), favorite = true, updatedAt = 9_000L),
            e("精确但没收藏", listOf("example.com"), updatedAt = 1L),
        )
        val s = AutofillMatch.suggest(web("example.com"), entries, trustChrome)
        assertEquals(listOf("精确但没收藏", "兄弟但收藏"), s.shown.map { it.entry.name })
    }

    @Test
    fun `同档里收藏的在前`() {
        val entries = listOf(
            e("普通", listOf("example.com"), updatedAt = 9_000L),
            e("收藏", listOf("example.com"), favorite = true, updatedAt = 1L),
        )
        val s = AutofillMatch.suggest(web("example.com"), entries, trustChrome)
        assertEquals(listOf("收藏", "普通"), s.shown.map { it.entry.name })
    }

    @Test
    fun `同档同收藏时最近改动的在前`() {
        // 同一个站存了两条的人，多半是刚改过密码又存了一条新的。
        val entries = listOf(
            e("旧的", listOf("example.com"), updatedAt = 100L),
            e("新的", listOf("example.com"), updatedAt = 200L),
        )
        val s = AutofillMatch.suggest(web("example.com"), entries, trustChrome)
        assertEquals(listOf("新的", "旧的"), s.shown.map { it.entry.name })
    }

    @Test
    fun `再同分就按名称排且和列表页同一个顺序`() {
        // 用的是 VaultIndex.NAME_ORDER，中文按拼音。
        // 两处各排各的话，用户会看到「填充条上第一条」和「列表里第一条」不是同一条。
        val entries = listOf(
            e("北京银行", listOf("example.com")),
            e("安居客", listOf("example.com")),
        )
        val s = AutofillMatch.suggest(web("example.com"), entries, trustChrome)
        assertEquals(listOf("安居客", "北京银行"), s.shown.map { it.entry.name })
    }

    /* ══════════════════════════ 截断 ══════════════════════════ */

    @Test
    fun `超过上限的部分报条数而不是硬塞`() {
        val entries = (1..12).map { e("第 $it 条", listOf("example.com"), updatedAt = it.toLong()) }
        val s = AutofillMatch.suggest(web("example.com"), entries, trustChrome)
        assertEquals(AutofillMatch.MAX_SUGGESTIONS, s.shown.size)
        assertEquals(12, s.total)
        assertEquals(12 - AutofillMatch.MAX_SUGGESTIONS, s.hidden)
        // 截掉的是排在后面的，不是随便截的
        assertEquals("第 12 条", s.shown[0].entry.name)
    }

    @Test
    fun `没有候选时 hidden 是零不是负数`() {
        val s = AutofillMatch.suggest(web("example.com"), emptyList(), trustChrome)
        assertTrue(s.isEmpty)
        assertEquals(0, s.hidden)
    }

    /* ══════════════════════════ 手动挑那一侧 ══════════════════════════ */

    @Test
    fun `inspect 不做过滤只给判定`() {
        // 用户搜得到的就该挑得到，判定只影响「说什么」，不影响「让不让」。
        val entry = e("微信", listOf("com.tencent.mm"))
        val c = AutofillMatch.inspect(web("example.com"), entry, trustChrome)
        assertEquals(DomainMatch.Verdict.WrongKind, c.verdict)
        assertTrue(c.verdict.needsWarning)

        val unrelated = AutofillMatch.inspect(web("example.com"), e("随便一条"), trustChrome)
        assertEquals(DomainMatch.Verdict.None, unrelated.verdict)
        assertFalse(unrelated.verdict.needsWarning)
    }

    /* ══════════════════════════ 不吐内容 ══════════════════════════ */

    @Test
    fun `候选对象的 toString 不带密码`() {
        // 决策(144)。Candidate 直接抱着一个 VaultEntry，而 VaultEntry 是 data class，
        // 它自动生成的 toString 会把明文密码原样打出来。
        // 哪天有人顺手 Log.d(TAG, "candidates=$list")，那一行就是一份明文凭据。
        val entries = listOf(e("网银", listOf("example.com"), username = "138xxxx", password = "hunter2"))
        val s = AutofillMatch.suggest(web("example.com"), entries, trustChrome)
        val one = s.shown[0].toString()
        assertFalse(one.contains("hunter2"))
        assertFalse(one.contains("138xxxx"))
        assertFalse(one.contains("网银"))

        val whole = s.toString()
        assertFalse(whole.contains("hunter2"))
        assertTrue(whole.contains("1"))
    }
}
