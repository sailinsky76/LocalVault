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
import cn.localvault.app.ui.detail.EntryDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目详情的内核。
 *
 * 这里盯着两件在界面上验不动的事：
 *  - **撤销能不能把条目原封不动放回原位**（要验证就得真删一条，撤错了数据就没了）；
 *  - **确认弹窗里到底会印出什么**——决策⑭的整个立论就在这一句上，
 *    而它是最容易在某次「让用户看清楚点」的改动里被悄悄破掉的。
 */
class EntryDetailTest {

    private var seq = 0

    private fun e(
        name: String,
        username: String = "",
        password: String = "",
        domains: List<String> = emptyList(),
        category: String = "",
        notes: String = "",
    ) = VaultEntry(
        id = "id-${seq++}",
        name = name,
        username = username,
        password = password,
        domains = domains,
        category = category,
        notes = notes,
    )

    /* ─────────────────── 删除与撤销 ─────────────────── */

    @Test
    fun `删除会摘掉那一条，并留下它原来的位置`() {
        val list = listOf(e("A"), e("B"), e("C"))
        val (next, snap) = EntryDetail.remove(list, list[1].id)
        assertEquals(2, next.size)
        assertNotNull(snap)
        assertEquals(1, snap!!.index)
        assertEquals(list[1].id, snap.entry.id)
    }

    @Test
    fun `撤销把条目放回原来的位置，不是往末尾一扔`() {
        val list = listOf(e("A"), e("B"), e("C"))
        val (next, snap) = EntryDetail.remove(list, list[1].id)
        val back = EntryDetail.restore(next, snap!!)
        assertEquals(list.map { it.id }, back.map { it.id })
    }

    @Test
    fun `撤销回来的是同一个对象，字段一个都没变`() {
        val original = e("招商银行", username = "13800138000", password = "s3cret", notes = "密保答案")
        val (next, snap) = EntryDetail.remove(listOf(original), original.id)
        val back = EntryDetail.restore(next, snap!!)
        assertEquals(1, back.size)
        assertSame(original, back[0])
    }

    @Test
    fun `删一个不存在的 id：列表不动，也不产生快照`() {
        val list = listOf(e("A"), e("B"))
        val (next, snap) = EntryDetail.remove(list, "根本没有这个 id")
        assertEquals(list, next)
        assertNull(snap)
    }

    @Test
    fun `撤销按钮被连点两下，条目不会出现两遍`() {
        val list = listOf(e("A"), e("B"))
        val (next, snap) = EntryDetail.remove(list, list[0].id)
        val once = EntryDetail.restore(next, snap!!)
        val twice = EntryDetail.restore(once, snap)
        assertEquals(2, twice.size)
        assertEquals(once.map { it.id }, twice.map { it.id })
    }

    @Test
    fun `原位置已经越界时也放得回去，不抛异常`() {
        val list = listOf(e("A"), e("B"), e("C"), e("D"))
        val (_, snap) = EntryDetail.remove(list, list[3].id)
        // 模拟撤销之前列表又被别处改短了
        val shrunk = listOf(list[0])
        val back = EntryDetail.restore(shrunk, snap!!)
        assertEquals(2, back.size)
        assertTrue(back.any { it.id == snap.entry.id })
    }

    /* ─────────────────── 打码 ─────────────────── */

    @Test
    fun `手机号保留后四位——中国人认自己的号靠的就是这四位`() {
        assertEquals("138****8000", EntryDetail.maskIdentity("13800138000"))
    }

    @Test
    fun `邮箱只遮前缀，域名整段保留`() {
        assertEquals("zh****@example.com", EntryDetail.maskIdentity("zhangsan@example.com"))
    }

    @Test
    fun `普通用户名只留头不留尾`() {
        val masked = EntryDetail.maskIdentity("zhangsan2019")
        assertEquals("zh****", masked)
        assertFalse("露出尾巴等于给撞库送料", masked.endsWith("2019"))
    }

    @Test
    fun `短到藏不住的整段藏掉`() {
        assertEquals("**", EntryDetail.maskIdentity("ab"))
        assertEquals("*", EntryDetail.maskIdentity("a"))
    }

    @Test
    fun `空账号打出来还是空，不是一串星号`() {
        assertEquals("", EntryDetail.maskIdentity(""))
        assertEquals("", EntryDetail.maskIdentity("   "))
    }

    @Test
    fun `邮箱里的 at 号在末尾时不当邮箱处理`() {
        // "abc@" 不是邮箱（@ 后面没东西），走普通规则，不能崩也不能露出整段
        assertEquals("a****", EntryDetail.maskIdentity("abc@"))
    }

    /* ─────────────────── 确认弹窗（决策⑭） ─────────────────── */

    @Test
    fun `确认弹窗里绝不出现密码`() {
        val entry = e("招商银行", username = "13800138000", password = "Tr0ub4dor&3", notes = "密保：母亲姓氏")
        val detail = EntryDetail.deleteConfirmDetail(entry)
        assertFalse(detail.contains("Tr0ub4dor&3"))
        assertFalse(detail.contains("密保"))
        assertFalse(detail.contains("母亲"))
    }

    @Test
    fun `确认弹窗里的账号是打过码的`() {
        val entry = e("招商银行", username = "13800138000")
        assertEquals("招商银行 · 138****8000", EntryDetail.deleteConfirmDetail(entry))
    }

    @Test
    fun `没有账号时退而用网址，都没有就只有名称`() {
        assertEquals(
            "某站 · example.com",
            EntryDetail.deleteConfirmDetail(e("某站", domains = listOf("example.com"))),
        )
        assertEquals("光杆条目", EntryDetail.deleteConfirmDetail(e("光杆条目")))
    }

    /* ─────────────────── 页面上有哪些行 ─────────────────── */

    @Test
    fun `空字段不占位——空的密码行会让人以为密码丢了`() {
        val rows = EntryDetail.rows(e("只有名字"))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `行的顺序是固定的`() {
        val entry = e(
            "全字段",
            username = "u", password = "p",
            domains = listOf("example.com"), category = "银行", notes = "n",
        )
        assertEquals(
            listOf(
                EntryDetail.Row.Username,
                EntryDetail.Row.Password,
                EntryDetail.Row.Domain,
                EntryDetail.Row.Category,
                EntryDetail.Row.Notes,
            ),
            EntryDetail.rows(entry),
        )
    }

    @Test
    fun `全是空白的网址不算有网址`() {
        val rows = EntryDetail.rows(e("x", domains = listOf("", "   ")))
        assertFalse(rows.contains(EntryDetail.Row.Domain))
    }

    @Test
    fun `密码和备注默认都藏起来`() {
        assertTrue(EntryDetail.hiddenByDefault(EntryDetail.Row.Password))
        assertTrue(EntryDetail.hiddenByDefault(EntryDetail.Row.Notes))
        assertFalse(EntryDetail.hiddenByDefault(EntryDetail.Row.Username))
        assertFalse(EntryDetail.hiddenByDefault(EntryDetail.Row.Domain))
    }

    @Test
    fun `分类没有复制按钮`() {
        assertFalse(EntryDetail.copyable(EntryDetail.Row.Category))
        assertTrue(EntryDetail.copyable(EntryDetail.Row.Password))
        assertTrue(EntryDetail.copyable(EntryDetail.Row.Username))
    }

    @Test
    fun `剪贴板标签只是字段名，不带条目名`() {
        EntryDetail.Row.entries.forEach { row ->
            val label = EntryDetail.clipboardLabel(row)
            assertTrue(label.isNotBlank())
            assertFalse("标签里不能出现条目名", label.contains("招商"))
        }
    }
}
