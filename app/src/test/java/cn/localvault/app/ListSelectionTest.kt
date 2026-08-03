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
import cn.localvault.app.ui.list.ListSelection
import cn.localvault.app.ui.list.VaultIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表多选的内核。
 *
 * 这里盯着四件在界面上验不动、又几乎必然被后来的改动悄悄破掉的事：
 *
 *  - **选中集合会腐烂**。条目在别处被删掉之后，那个 id 还留在集合里，
 *    于是「已选 5 条」按下去只删掉 4 条，而删除接口忽略找不到的 id，
 *    所以它不报错，只是安静地和用户对不上。
 *  - **分组全选不是逐条翻转**。一组选了 3 条时点标头，用户要的是整组，
 *    不是反选成 5 条。
 *  - **确认框里绝不出现账号**。此刻要摆上屏幕的是一串条目，
 *    多带一个字段就是多一倍泄漏面（决策⑭）。
 *  - **条数必须说准**。「删除 12 条」按下去无法撤销，
 *    它和实际删掉的条数必须是同一个数。
 */
class ListSelectionTest {

    private fun entry(id: String, name: String = "条目$id", user: String = "") =
        VaultEntry(id = id, name = name, username = user)

    private val five = listOf(entry("a"), entry("b"), entry("c"), entry("d"), entry("e"))

    /* ── 增减 ── */

    @Test
    fun `toggle 一来一回回到原样`() {
        val once = ListSelection.toggle(emptySet(), "a")
        assertEquals(setOf("a"), once)
        assertEquals(emptySet<String>(), ListSelection.toggle(once, "a"))
    }

    /* ── 腐烂 ── */

    @Test
    fun `prune 剔掉已经不在库里的 id`() {
        val selected = setOf("a", "zzz", "c")
        assertEquals(setOf("a", "c"), ListSelection.prune(selected, five))
    }

    @Test
    fun `prune 没东西可剔时原样返回，不制造新对象`() {
        val selected = setOf("a", "c")
        assertSame(selected, ListSelection.prune(selected, five))
    }

    @Test
    fun `库空了以后选中集合清空`() {
        assertEquals(emptySet<String>(), ListSelection.prune(setOf("a", "b"), emptyList()))
    }

    /* ── 全选 ── */

    @Test
    fun `空库不算全选`() {
        assertFalse(ListSelection.isAllSelected(emptySet(), emptyList()))
    }

    @Test
    fun `全选与取消全选`() {
        val all = ListSelection.toggleAll(emptySet(), five)
        assertEquals(5, all.size)
        assertTrue(ListSelection.isAllSelected(all, five))
        assertEquals(emptySet<String>(), ListSelection.toggleAll(all, five))
    }

    @Test
    fun `选了一部分时按全选是补齐，不是清空`() {
        val some = setOf("a", "b")
        assertEquals(5, ListSelection.toggleAll(some, five).size)
    }

    @Test
    fun `按钮上的字跟着状态走`() {
        assertEquals("全选", ListSelection.toggleAllText(setOf("a"), five))
        assertEquals("取消全选", ListSelection.toggleAllText(ListSelection.allIds(five), five))
    }

    /* ── 分组 ── */

    private fun section(vararg ids: String) =
        VaultIndex.Section(
            title = "银行",
            entries = ids.map { entry(it) },
            kind = VaultIndex.Kind.Category,
        )

    @Test
    fun `分组三态`() {
        val s = section("a", "b", "c")
        assertEquals(ListSelection.GroupState.None, ListSelection.groupState(emptySet(), s))
        assertEquals(ListSelection.GroupState.Some, ListSelection.groupState(setOf("a"), s))
        assertEquals(
            ListSelection.GroupState.All,
            ListSelection.groupState(setOf("a", "b", "c"), s),
        )
    }

    @Test
    fun `选了一部分时点标头是整组选上，不是翻转`() {
        val s = section("a", "b", "c")
        val out = ListSelection.toggleGroup(setOf("a"), s)
        assertEquals(setOf("a", "b", "c"), out)
    }

    @Test
    fun `整组已选时点标头只清这一组，别的组不动`() {
        val s = section("a", "b")
        val out = ListSelection.toggleGroup(setOf("a", "b", "d"), s)
        assertEquals(setOf("d"), out)
    }

    /* ── 屏幕上的字 ── */

    @Test
    fun `标题与按钮文案`() {
        assertEquals("选择条目", ListSelection.title(0))
        assertEquals("已选 3 条", ListSelection.title(3))
        assertEquals("删除", ListSelection.deleteText(0))
        assertEquals("删除这 3 条", ListSelection.deleteText(3))
        assertEquals("删除这 3 条？", ListSelection.confirmTitle(3))
    }

    /* ── 长按提示 ── */

    /**
     * 撤掉顶栏那个对勾按钮之后，长按是**唯一**的入口，这句话是它唯一的提示。
     * 所以两件事都得在里面：怎么进（长按），以及进去能干什么（删几条）。
     */
    @Test
    fun `提示里同时说清怎么进和进去干什么`() {
        assertTrue(ListSelection.LONG_PRESS_HINT.contains("长按"))
        assertTrue(ListSelection.LONG_PRESS_HINT.contains("多选"))
        assertTrue(ListSelection.LONG_PRESS_HINT.contains("删"))
    }

    /**
     * 界面上已经没有那个按钮了，这句话里就不许再提它——
     * 用户会照着一句话去点一个不存在的东西，然后认为功能坏了。
     */
    @Test
    fun `提示里不提已经撤掉的那个按钮`() {
        assertFalse(ListSelection.LONG_PRESS_HINT.contains("右上角"))
        assertFalse(ListSelection.LONG_PRESS_HINT.contains("按钮"))
    }

    @Test
    fun `只有一条时不摆提示，两条起才摆`() {
        assertFalse(ListSelection.showHint(0, selecting = false))
        assertFalse(ListSelection.showHint(1, selecting = false))
        assertTrue(ListSelection.showHint(ListSelection.HINT_MIN_ENTRIES, selecting = false))
        assertTrue(ListSelection.showHint(40, selecting = false))
    }

    @Test
    fun `已经在选择模式里就不再教怎么进来`() {
        assertFalse(ListSelection.showHint(40, selecting = true))
    }

    @Test
    fun `确认框必须说不能撤销`() {
        assertTrue(ListSelection.confirmMessage(3).contains("不能撤销"))
    }

    /* ── 确认框明细 ── */

    @Test
    fun `明细只有名称，不出现账号`() {
        val entries = listOf(
            entry("a", name = "招商银行", user = "13800001111"),
            entry("b", name = "微信", user = "wx_test"),
        )
        val detail = ListSelection.confirmDetail(entries, setOf("a", "b"))
        assertEquals("招商银行\n微信", detail)
        assertFalse(detail.contains("13800001111"))
        assertFalse(detail.contains("wx_test"))
    }

    @Test
    fun `超出上限只报剩余条数`() {
        val entries = (1..10).map { entry("id$it", name = "条目$it") }
        val detail = ListSelection.confirmDetail(entries, ListSelection.allIds(entries))
        assertEquals(ListSelection.DETAIL_MAX + 1, detail.lines().size)
        assertTrue(detail.endsWith("…还有 ${10 - ListSelection.DETAIL_MAX} 条"))
    }

    @Test
    fun `刚好等于上限时不出现还有几条`() {
        val entries = (1..ListSelection.DETAIL_MAX).map { entry("id$it") }
        val detail = ListSelection.confirmDetail(entries, ListSelection.allIds(entries))
        assertFalse(detail.contains("还有"))
    }

    @Test
    fun `明细顺序跟着库里的顺序，不重排`() {
        val detail = ListSelection.confirmDetail(five, setOf("c", "a"))
        assertEquals("条目a\n条目c", detail)
    }

    @Test
    fun `没有名称的条目不画成空行`() {
        val detail = ListSelection.confirmDetail(listOf(entry("a", name = "  ")), setOf("a"))
        assertEquals("（无名称）", detail)
    }

    @Test
    fun `一条都没选时明细是空串`() {
        assertEquals("", ListSelection.confirmDetail(five, emptySet()))
    }
}
