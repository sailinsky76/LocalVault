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

import cn.localvault.app.ui.autofill.AutofillOffer
import cn.localvault.app.ui.autofill.InlinePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内联建议（输入法建议条上那几格）摆几格、摆哪几条。
 *
 * 这一层的每一条规则**错了都不会报错**：内联条是输入法画的，
 * 我们这边交出去之后既看不到结果，也收不到任何反馈。
 * 少摆一条、把兄弟域那条当成精确匹配摆上去、拿第 3 份规格去配第 1 格——
 * 三样在自己的手机上都可能一辈子碰不到一次，
 * 所以它们只能在这儿钉住。
 */
class InlinePlanTest {

    /* ═════════════ 四道门：什么时候整份退回浮层 ═════════════ */

    @Test
    fun `输入法没问 —— 走浮层那条老路`() {
        val layout = InlinePlan.forOffer(null, listOf(item(), item()), hidden = 0)
        assertFalse(layout.on)
        assertEquals(InlinePlan.Why.NoRequest, layout.why)
        assertNull("整份不摆时搜索那一格也不该出现", layout.search)
        assertTrue("一格都不许摆", layout.slots.all { it == null })
    }

    @Test
    fun `整份不摆时 slots 仍然和候选等长 —— 接线那一侧按下标取`() {
        val items = listOf(item(), item(), item())
        assertEquals(items.size, InlinePlan.forOffer(null, items, hidden = 0).slots.size)
    }

    @Test
    fun `输入法要 0 格`() {
        val layout = InlinePlan.forOffer(ask(max = 0), listOf(item()), hidden = 0)
        assertEquals(InlinePlan.Why.NoRoom, layout.why)
    }

    @Test
    fun `一份规格都没给`() {
        val layout = InlinePlan.forOffer(ask(specs = emptyList()), listOf(item()), hidden = 0)
        assertEquals(InlinePlan.Why.NoSpec, layout.why)
    }

    @Test
    fun `规格里没有我们认得的版本 —— 整份退回浮层而不是画一格没人说得准的东西`() {
        val layout = InlinePlan.forOffer(ask(specs = listOf(false)), listOf(item()), hidden = 0)
        assertEquals(InlinePlan.Why.NoStyle, layout.why)
    }

    @Test
    fun `会用到的那几份里有一份不认得 —— 整份退回`() {
        // 4 格里第 2 份不认得
        val layout = InlinePlan.forOffer(
            ask(max = 4, specs = listOf(true, false, true, true)),
            listOf(item(), item(), item()),
            hidden = 0,
        )
        assertEquals(InlinePlan.Why.NoStyle, layout.why)
    }

    @Test
    fun `用不到的那几份不认得 —— 照摆，不拿别人的富余惩罚用户`() {
        // 只要 2 格（1 条候选 + 搜索），第 3 份是什么版本与我们无关
        val layout = InlinePlan.forOffer(
            ask(max = 2, specs = listOf(true, true, false)),
            listOf(item()),
            hidden = 0,
        )
        assertTrue(layout.on)
        assertNotNull(layout.slots[0])
    }

    /* ═════════════ 规格怎么配 ═════════════ */

    @Test
    fun `规格只有一份时后面几格复用最后一份`() {
        val layout = InlinePlan.forOffer(
            ask(max = 4, specs = listOf(true)),
            listOf(item(), item(), item()),
            hidden = 0,
        )
        assertEquals(0, layout.slots[0]!!.specIndex)
        assertEquals(0, layout.slots[1]!!.specIndex)
        assertEquals(0, layout.search!!.specIndex)
    }

    @Test
    fun `规格够多时逐格往下取`() {
        val layout = InlinePlan.forOffer(
            ask(max = 4, specs = listOf(true, true, true, true)),
            listOf(item(), item(), item()),
            hidden = 0,
        )
        assertEquals(0, layout.slots[0]!!.specIndex)
        assertEquals(1, layout.slots[1]!!.specIndex)
        assertEquals(2, layout.slots[2]!!.specIndex)
        assertEquals("搜索那一格排在候选后面", 3, layout.search!!.specIndex)
    }

    /* ═════════════ 摆几条 ═════════════ */

    @Test
    fun `格数够时候选一条不少`() {
        val layout = InlinePlan.forOffer(ask(max = 4), listOf(item(), item(), item()), hidden = 0)
        assertEquals(3, layout.slots.count { it != null })
        assertEquals(0, layout.withheld)
    }

    @Test
    fun `搜索那一格永远在`() {
        listOf(1, 2, 3, 4, 99).forEach { max ->
            val layout = InlinePlan.forOffer(ask(max = max), listOf(item(), item()), hidden = 0)
            assertNotNull("max=$max 时搜索那一格没了", layout.search)
        }
    }

    @Test
    fun `只给一格时那一格是搜索，不是排第一的候选`() {
        val layout = InlinePlan.forOffer(ask(max = 1), listOf(item(), item()), hidden = 0)
        assertTrue(layout.on)
        assertTrue("一条候选都不该摆", layout.slots.all { it == null })
        assertNotNull(layout.search)
        assertEquals("两条都没进内联，那一格上要说得出来", 2, layout.withheld)
    }

    @Test
    fun `给两格时是一条候选加一格搜索`() {
        val layout = InlinePlan.forOffer(ask(max = 2), listOf(item(), item(), item()), hidden = 0)
        assertEquals(1, layout.slots.count { it != null })
        assertNotNull(layout.slots[0])
        assertEquals(2, layout.withheld)
    }

    @Test
    fun `输入法要很多格也不超过我们自己那道上限`() {
        val many = List(10) { item() }
        val layout = InlinePlan.forOffer(ask(max = 99), many, hidden = 0)
        assertEquals(InlinePlan.MAX_CHIPS - 1, layout.slots.count { it != null })
        assertEquals(10 - (InlinePlan.MAX_CHIPS - 1), layout.withheld)
    }

    /* ═════════════ 兄弟域那几条 ═════════════ */

    @Test
    fun `兄弟域那一条不进内联 —— 两行摆不下那句「你存的是」`() {
        val items = listOf(item(badge = "你存的是 mail.example.com"), item())
        val layout = InlinePlan.forOffer(ask(max = 4), items, hidden = 0)
        assertNull("兄弟域那一条不许出现在内联条上", layout.slots[0])
        assertNotNull("精确匹配那一条照常", layout.slots[1])
        assertEquals(1, layout.withheld)
    }

    @Test
    fun `全是兄弟域时一条 chip 都没有，但搜索那一格还在`() {
        val items = List(3) { item(badge = "你存的是 mail.example.com") }
        val layout = InlinePlan.forOffer(ask(max = 4), items, hidden = 0)
        assertTrue(layout.on)
        assertTrue(layout.slots.all { it == null })
        assertNotNull(layout.search)
        assertEquals(3, layout.withheld)
    }

    @Test
    fun `兄弟域被挡下之后，那一格让给后面那条精确的`() {
        val items = listOf(
            item(badge = "你存的是 mail.example.com"),
            item(name = "第二条"),
            item(name = "第三条"),
        )
        val layout = InlinePlan.forOffer(ask(max = 4), items, hidden = 0)
        assertNull(layout.slots[0])
        assertEquals("第二条", layout.slots[1]!!.chip.title)
        assertEquals("第三条", layout.slots[2]!!.chip.title)
        assertEquals(1, layout.withheld)
    }

    /* ═════════════ 「还有 N 条」那个数 ═════════════ */

    @Test
    fun `被截掉的那几条也要算进去`() {
        // 5 条够格，浮层上只摆得下 2 条（hidden=3）；内联条上摆 3 条
        val layout = InlinePlan.forOffer(ask(max = 4), listOf(item(), item()), hidden = 3)
        assertEquals("2 条全进了内联，加上浮层截掉的 3 条", 3, layout.withheld)
    }

    @Test
    fun `没进内联的那几条要写在搜索那一格上`() {
        val layout = InlinePlan.forOffer(ask(max = 2), listOf(item(), item(), item()), hidden = 0)
        assertTrue(
            "搜索那一格必须说出还有几条：${layout.search!!.chip.title}",
            layout.search!!.chip.title.contains("2"),
        )
    }

    @Test
    fun `一条都没少时不提「还有」`() {
        val layout = InlinePlan.forOffer(ask(max = 4), listOf(item()), hidden = 0)
        assertEquals(AutofillOffer.searchLabel(0), layout.search!!.chip.title)
    }

    @Test
    fun `内联那一格的数字和浮层那一行可以不一样 —— 各说各屏上的真话`() {
        // 浮层：3 条都摆得下，hidden=0，那一行写的是「在保险库里搜索…」
        // 内联：只给 2 格，于是有 2 条没进去
        val items = listOf(item(), item(), item())
        val layout = InlinePlan.forOffer(ask(max = 2), items, hidden = 0)
        assertEquals(AutofillOffer.searchLabel(2), layout.search!!.chip.title)
        assertFalse(
            "两处数字本来就不该一样",
            AutofillOffer.searchLabel(0) == layout.search!!.chip.title,
        )
    }

    /* ═════════════ 空的一份 ═════════════ */

    @Test
    fun `一条候选都没有时仍然摆那一格搜索`() {
        val layout = InlinePlan.forOffer(ask(max = 4), emptyList(), hidden = 0)
        assertTrue(layout.on)
        assertTrue(layout.slots.isEmpty())
        assertNotNull(layout.search)
    }

    /* ═════════════ 对号 ═════════════ */

    @Test
    fun `每一格的字来自它自己那一条，不许串行`() {
        val items = listOf(item(name = "甲", user = "a@x"), item(name = "乙", user = "b@x"))
        val layout = InlinePlan.forOffer(ask(max = 4), items, hidden = 0)
        assertEquals("甲", layout.slots[0]!!.chip.title)
        assertEquals("a@x", layout.slots[0]!!.chip.subtitle)
        assertEquals("乙", layout.slots[1]!!.chip.title)
        assertEquals("b@x", layout.slots[1]!!.chip.subtitle)
    }

    /* ═════════════ 「先解锁」那一格 ═════════════ */

    @Test
    fun `锁着那一条：输入法没问`() {
        val solo = InlinePlan.forUnlock(null)
        assertFalse(solo.on)
        assertEquals(InlinePlan.Why.NoRequest, solo.why)
        assertNull(solo.slot)
    }

    @Test
    fun `锁着那一条：要 0 格`() {
        assertEquals(InlinePlan.Why.NoRoom, InlinePlan.forUnlock(ask(max = 0)).why)
    }

    @Test
    fun `锁着那一条：规格不认得`() {
        assertEquals(InlinePlan.Why.NoStyle, InlinePlan.forUnlock(ask(specs = listOf(false))).why)
    }

    @Test
    fun `锁着那一条摆出来是「先解锁」，用第一份规格`() {
        val solo = InlinePlan.forUnlock(ask(max = 3, specs = listOf(true, true)))
        assertTrue(solo.on)
        assertEquals(AutofillOffer.UNLOCK_LABEL, solo.slot!!.chip.title)
        assertEquals(AutofillOffer.UNLOCK_NOTE, solo.slot!!.chip.subtitle)
        assertEquals(0, solo.slot!!.specIndex)
    }

    @Test
    fun `只给一格时「先解锁」照样出得来 —— 它不是候选`() {
        // 同样是 max=1：候选那一路一条都不摆（那一格留给搜索），
        // 而这一路必须摆——这一屏上没有别的东西可点
        assertTrue(InlinePlan.forUnlock(ask(max = 1)).on)
        assertTrue(InlinePlan.forOffer(ask(max = 1), listOf(item()), 0).slots.all { it == null })
    }

    /* ═════════════ 日志里不许出现内容 ═════════════ */

    @Test
    fun `Ask 的 toString 只有数字`() {
        assertEquals("Ask(max=3, specs=2, v1=1)", InlinePlan.ask(3, listOf(true, false)).toString())
    }

    @Test
    fun `Slot 与 Layout 的 toString 不吐一个字的内容`() {
        val items = listOf(item(name = "招商银行", user = "zhangsan"))
        val layout = InlinePlan.forOffer(ask(max = 4), items, hidden = 2)
        val text = layout.toString() + layout.slots[0]!!.toString() + layout.search!!.toString()
        assertFalse(text.contains("招商银行"))
        assertFalse(text.contains("zhangsan"))
        assertTrue(layout.toString().contains("withheld=2"))
    }

    @Test
    fun `整份不摆时 toString 只报档名`() {
        assertEquals(
            "Layout(off=NoRequest)",
            InlinePlan.forOffer(null, listOf(item()), 0).toString(),
        )
    }

    /* ═════════════ 造数据 ═════════════ */

    private fun item(
        name: String = "示例站",
        user: String = "zhangsan",
        badge: String? = null,
    ): AutofillOffer.Item = AutofillOffer.Item(
        entryId = "id-$name-$user",
        label = name,
        sublabel = user,
        badge = badge,
        writes = emptyList(),
    )

    /** 默认是一份认得的规格、要得下 [InlinePlan.MAX_CHIPS] 格。 */
    private fun ask(
        max: Int = 5,
        specs: List<Boolean> = listOf(true),
    ): InlinePlan.Ask = InlinePlan.ask(max, specs)
}
