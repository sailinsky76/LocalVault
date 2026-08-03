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
import cn.localvault.app.ui.list.VaultIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表分组与搜索打分。
 *
 * 这些判断在界面上很难验证——「为什么这一条排在前面」看不出来，
 * 「备注有没有被搜到」更是要造一个含敏感字样的备注再去试。
 * 切成纯函数之后全部能在这里走一遍。
 */
class VaultIndexTest {

    private var seq = 0

    private fun e(
        name: String,
        username: String = "",
        domains: List<String> = emptyList(),
        category: String = "",
        notes: String = "",
        password: String = "",
        favorite: Boolean = false,
        updatedAt: Long = 0L,
    ) = VaultEntry(
        id = "id-${seq++}",
        name = name,
        username = username,
        domains = domains,
        category = category,
        notes = notes,
        password = password,
        favorite = favorite,
        updatedAt = updatedAt,
    )

    /* ─────────────────── 分组 ─────────────────── */

    @Test
    fun `空库没有任何分组`() {
        assertTrue(VaultIndex.sections(emptyList()).isEmpty())
    }

    @Test
    fun `全库都没分类时，唯一那组叫「全部」而不是「未分类」`() {
        val s = VaultIndex.sections(listOf(e("微信"), e("支付宝")))
        assertEquals(1, s.size)
        assertEquals(VaultIndex.ALL_TITLE, s[0].title)
    }

    @Test
    fun `一旦有了分类，剩下的那组才叫「未分类」`() {
        val s = VaultIndex.sections(listOf(e("网易邮箱", category = "邮箱"), e("某网站")))
        assertEquals(2, s.size)
        assertEquals("邮箱", s[0].title)
        assertEquals(VaultIndex.UNCATEGORIZED_TITLE, s[1].title)
    }

    @Test
    fun `常用永远第一组，未分类永远最后一组`() {
        val s = VaultIndex.sections(
            listOf(
                e("零散条目"),
                e("网银", category = "银行"),
                e("主力邮箱", favorite = true),
            )
        )
        assertEquals(listOf(VaultIndex.FAVORITES_TITLE, "银行", VaultIndex.UNCATEGORIZED_TITLE), s.map { it.title })
    }

    @Test
    fun `收藏的条目只出现在常用组，不在自己的分类组里重复`() {
        val fav = e("网银", category = "银行", favorite = true)
        val other = e("信用卡", category = "银行")
        val s = VaultIndex.sections(listOf(fav, other))

        val all = s.flatMap { it.entries }
        assertEquals("总行数必须等于条目数，否则顶栏的计数会和肉眼数出来的对不上", 2, all.size)
        assertEquals(listOf(fav.id), s.first { it.kind == VaultIndex.Kind.Favorites }.entries.map { it.id })
        assertEquals(listOf(other.id), s.first { it.kind == VaultIndex.Kind.Category }.entries.map { it.id })
    }

    @Test
    fun `中文按拼音排，不按码点排`() {
        // 按 String.compareTo 的话排的是 Unicode 码点：
        // 北(U+5317) < 安(U+5B89) < 微(U+5FAE) < 支(U+652F)，
        // 于是「北京银行」会排到「安居客」前面——用户完全看不出这是按什么排的。
        // Collator 在 zh 下按拼音：an < bei < wei < zhi。
        val s = VaultIndex.sections(listOf(e("支付宝"), e("北京银行"), e("微信"), e("安居客")))
        assertEquals(
            listOf("安居客", "北京银行", "微信", "支付宝"),
            s[0].entries.map { it.name },
        )
    }

    @Test
    fun `名称相同时排序仍然稳定`() {
        val a = e("同名")
        val b = e("同名")
        val once = VaultIndex.sections(listOf(a, b))[0].entries.map { it.id }
        val twice = VaultIndex.sections(listOf(b, a))[0].entries.map { it.id }
        assertEquals("输入顺序不同也要给出同一个结果，否则列表会在重组时跳动", once, twice)
    }

    @Test
    fun `分类列表去重且排序`() {
        val list = listOf(e("a", category = "银行"), e("b", category = "邮箱"), e("c", category = "银行"), e("d"))
        assertEquals(2, VaultIndex.categories(list).size)
    }

    /* ─────────────────── 备份提醒 ─────────────────── */

    @Test
    fun `从未备份时，全部条目都算「没进备份」`() {
        val list = listOf(e("a", updatedAt = 10), e("b", updatedAt = 20))
        assertEquals(2, VaultIndex.changedSince(list, 0L))
    }

    @Test
    fun `只统计上次备份之后改过的条目`() {
        val list = listOf(e("旧", updatedAt = 100), e("新", updatedAt = 300), e("更新", updatedAt = 400))
        assertEquals(2, VaultIndex.changedSince(list, 200L))
        assertEquals(0, VaultIndex.changedSince(list, 500L))
    }

    /* ─────────────────── 搜索：白名单 ─────────────────── */

    @Test
    fun `备注和密码绝不参与搜索`() {
        val list = listOf(
            e("某银行", notes = "密保问题答案是我妈妈的名字", password = "hunter2ABC")
        )
        assertTrue("备注被搜到了——那会把密保答案摊在屏幕上", VaultIndex.search(list, "密保").isEmpty())
        assertTrue("备注被搜到了", VaultIndex.search(list, "妈妈").isEmpty())
        assertTrue("密码被搜到了——等于给肩窥加了一道门", VaultIndex.search(list, "hunter2").isEmpty())
        // 白名单里的字段照常能搜到，说明上面三条不是因为整体没工作
        assertEquals(1, VaultIndex.search(list, "银行").size)
    }

    @Test
    fun `空关键词返回空，不返回全库`() {
        val list = listOf(e("微信"), e("支付宝"))
        assertTrue(VaultIndex.search(list, "").isEmpty())
        assertTrue(VaultIndex.search(list, "   ").isEmpty())
    }

    /* ─────────────────── 搜索：打分 ─────────────────── */

    @Test
    fun `匹配越完整排越前`() {
        val exact = e("abc")
        val prefix = e("abcdef")
        val wordPrefix = e("xy abcz")
        val contains = e("xabcz")
        val hits = VaultIndex.search(listOf(contains, wordPrefix, prefix, exact), "abc")

        assertEquals(
            listOf(exact.id, prefix.id, wordPrefix.id, contains.id),
            hits.map { it.entry.id },
        )
        assertEquals(VaultIndex.MatchKind.Exact, hits[0].match)
        assertEquals(VaultIndex.MatchKind.WordPrefix, hits[2].match)
        assertEquals(VaultIndex.MatchKind.Contains, hits[3].match)
    }

    @Test
    fun `同等匹配下名称优先于账号优先于网址`() {
        val byName = e("abc")
        val byUser = e("某站", username = "abc")
        val byDomain = e("另一站", domains = listOf("https://abc"))
        val hits = VaultIndex.search(listOf(byDomain, byUser, byName), "abc")
        assertEquals(listOf(byName.id, byUser.id, byDomain.id), hits.map { it.entry.id })
    }

    @Test
    fun `账号完全命中，胜过名称里碰巧含有关键词`() {
        val coincidence = e("我的abc备用号")          // 名称 Contains = 44
        val real = e("某站", username = "abc")        // 账号 Exact    = 403
        val hits = VaultIndex.search(listOf(coincidence, real), "abc")
        assertEquals(real.id, hits[0].entry.id)
    }

    @Test
    fun `同分时收藏的排前面`() {
        val plain = e("abc")
        val fav = e("abc", favorite = true)
        val hits = VaultIndex.search(listOf(plain, fav), "abc")
        assertEquals(fav.id, hits[0].entry.id)
    }

    @Test
    fun `一个条目最多出一行，多字段命中只留最好的那次`() {
        val one = e("github", username = "github", domains = listOf("https://github.com/login"))
        val hits = VaultIndex.search(listOf(one), "github")
        assertEquals(1, hits.size)
        assertEquals(VaultIndex.Field.Name, hits[0].field)
    }

    @Test
    fun `大小写不敏感，中文子串能命中`() {
        val list = listOf(e("GitHub"), e("网易邮箱"))
        assertEquals(1, VaultIndex.search(list, "GITHUB").size)
        assertEquals(1, VaultIndex.search(list, "邮箱").size)
    }

    @Test
    fun `高亮区间落在原始文本上`() {
        val hits = VaultIndex.search(listOf(e("我的GitHub账号")), "github")
        val h = hits.single()
        assertEquals("github", h.text.substring(h.range).lowercase())
    }

    @Test
    fun `limit 生效`() {
        val list = (1..50).map { e("站点$it") }
        assertEquals(10, VaultIndex.search(list, "站点", limit = 10).size)
    }

    /* ─────────────────── 域名归一 ─────────────────── */

    @Test
    fun `网址收敛成主机名`() {
        assertEquals("example.com", VaultIndex.normalizeDomain("HTTPS://Example.com/login?next=%2F"))
        assertEquals("example.com", VaultIndex.normalizeDomain("http://example.com:8443/"))
        assertEquals("example.com", VaultIndex.normalizeDomain("  Example.com.  "))
        assertEquals("example.com", VaultIndex.normalizeDomain("https://user:pw@example.com/x"))
    }

    @Test
    fun `路径里的 @ 不会把主机名切没`() {
        assertEquals("example.com", VaultIndex.normalizeDomain("https://example.com/mail@inbox"))
    }

    @Test
    fun `一个子域名都不剥，www 也不剥`() {
        // 「哪些子域名算同一个站」必须靠公共后缀表认真做，那是 M4 的域名归属校验。
        // 在搜索里图省事先剥一层，将来两边规则对不上，
        // 就会出现「搜出来是这一条，填进去是另一条」。
        assertEquals("mail.google.com", VaultIndex.normalizeDomain("https://mail.google.com"))
        assertEquals("www.example.com", VaultIndex.normalizeDomain("https://www.example.com/"))
    }

    @Test
    fun `安卓包名原样保留`() {
        assertEquals("com.tencent.mm", VaultIndex.normalizeDomain("com.tencent.mm"))
    }

    @Test
    fun `搜网址时用的是归一之后的形式`() {
        val one = e("某站", domains = listOf("HTTPS://Example.com/login?a=1"))
        val hits = VaultIndex.search(listOf(one), "example.com")
        assertNotNull(hits.firstOrNull())
        assertEquals(VaultIndex.MatchKind.Exact, hits[0].match)
        assertEquals("example.com", hits[0].text)
    }

    @Test
    fun `不剥 www 也不影响手感，它紧跟在分隔符后面算词首命中`() {
        val one = e("某站", domains = listOf("https://www.example.com"))
        assertNull(VaultIndex.search(listOf(one), "zzz").firstOrNull())
        val hits = VaultIndex.search(listOf(one), "example")
        assertEquals(1, hits.size)
        assertEquals(VaultIndex.MatchKind.WordPrefix, hits[0].match)
    }
}
