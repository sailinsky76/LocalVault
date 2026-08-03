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

import cn.localvault.app.ui.settings.AutofillSettingsModel
import cn.localvault.app.ui.settings.AutofillSettingsModel.Action
import cn.localvault.app.ui.settings.AutofillSettingsModel.Availability
import cn.localvault.app.ui.settings.SettingsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动填充设置页的内核。
 *
 * 这一页上没有一个值是我们说了算的（是不是默认填充服务由系统决定，
 * 我们既开不了也关不掉），所以这里盯的全是**话说得对不对**：
 *
 *  - **四档不许并成两档。** 「没设过」和「设的是别人」在屏幕上都是「开关关着」，
 *    但后一种情况点下去会把用户正在用的那个密码管理器顶下去。
 *    并档的后果不是他被吓一跳，是他真换了，然后过几周发现某个密码不出来了，
 *    完全想不起来是这一下造成的。
 *  - **「已经是默认」那一档的开关必须还能点，而且必须自己解释。**
 *    Android 没给应用「把自己撤下来」的 API，往回拨只能跳到系统那张列表。
 *    一个拨过去自己会弹回来、还不说为什么的开关，比灰着更让人生气（决策(61) 的变体）。
 *  - **「为什么有时候不出现」那份清单不许缩水。** M4 一路的克制在屏幕上
 *    全都长成同一个样子：什么都没弹出来。这份清单是唯一把
 *    「坏了」和「在保护你」分开的地方。
 *  - **设置主页那一行永远不变色。** 没开自动填充不是一件待办（决策(95)）。
 */
class AutofillSettingsModelTest {

    /* ═════════════ 四档 ═════════════ */

    @Test
    fun `四档一个都不能少，而且各说各的话`() {
        val subtitles = Availability.values().map { AutofillSettingsModel.row(it).subtitle }
        assertEquals(4, Availability.values().size)
        assertEquals("四档的副标题不许有两档撞在一起", 4, subtitles.toSet().size)
    }

    @Test
    fun `只有「设的就是本应用」那一档 checked 为 true`() {
        Availability.values().forEach { a ->
            assertEquals(a.name, a == Availability.Ours, AutofillSettingsModel.row(a).checked)
        }
    }

    @Test
    fun `只有「这台设备没有自动填充」那一档是灰的，而且灰了就必须解释`() {
        Availability.values().forEach { a ->
            val row = AutofillSettingsModel.row(a)
            assertEquals(a.name, a != Availability.Unsupported, row.enabled)
        }
        // 决策(61)：不能点的控件必须自己解释为什么。
        assertNotNull(AutofillSettingsModel.row(Availability.Unsupported).note)
    }

    @Test
    fun `「已经是默认」时开关仍然能点 —— 但必须写明这一下关不掉什么`() {
        val row = AutofillSettingsModel.row(Availability.Ours)
        assertTrue(row.checked)
        assertTrue("往回拨这条路只能跳出去，不能画成灰的", row.enabled)
        val note = row.note
        assertNotNull("这是全页最容易骗到人的一格，必须有话", note)
        assertTrue(
            "得说清楚是去系统里换，不是这一下就关掉了",
            note!!.contains("系统设置") && note.contains("撤下来"),
        )
        assertEquals(Action.OpenSystemSettings, row.action)
    }

    @Test
    fun `「现在用的是别的填充服务」必须先说系统只认一个，也要说不会动那边的数据`() {
        val note = AutofillSettingsModel.row(Availability.OtherService).note
        assertNotNull(note)
        // 不说前半句，用户以为是「多开一个」；不说后半句，会拦住本该敢试的人。
        assertTrue(note!!.contains("只认一个"))
        assertTrue(note.contains("一个都不会动"))
    }

    @Test
    fun `「还没设为默认」那一档不多话 —— 只在有代价时才说话`() {
        // 同 SettingsModel.autoLockNote：每一档都配一句说明的页面读起来像免责声明，
        // 用户学会的是跳过所有小字，等到真有要紧的那句他也不会看了。
        assertNull(AutofillSettingsModel.row(Availability.NoService).note)
    }

    /* ═════════════ 动作 ═════════════ */

    @Test
    fun `没有可去之处的那一档不许留一个点了没反应的按钮`() {
        val row = AutofillSettingsModel.row(Availability.Unsupported)
        assertEquals(Action.None, row.action)
        assertNull("点了没反应比没有按钮更糟（决策(61)）", row.buttonText)
    }

    @Test
    fun `其余三档都给得出一个能点的去处，而且按钮上有字`() {
        listOf(Availability.NoService, Availability.OtherService, Availability.Ours).forEach { a ->
            val row = AutofillSettingsModel.row(a)
            assertTrue(a.name, row.action != Action.None)
            assertNotNull(a.name, row.buttonText)
        }
    }

    @Test
    fun `已经是默认时不许把人送去那张「要不要启用本应用」的确认屏`() {
        // 走到这条路上的用户想做的正好相反。送他去一张问「确定要启用吗」的屏，
        // 他会以为自己点错了地方。
        assertEquals(
            Action.OpenSystemSettings,
            AutofillSettingsModel.row(Availability.Ours).action,
        )
        assertEquals(
            Action.RequestSetService,
            AutofillSettingsModel.row(Availability.NoService).action,
        )
        assertEquals(
            Action.RequestSetService,
            AutofillSettingsModel.row(Availability.OtherService).action,
        )
    }

    /* ═════════════ 为什么有时候不出现 ═════════════ */

    @Test
    fun `这份清单不许缩水，每一条都有症状也有原因`() {
        val list = AutofillSettingsModel.WHY_NOT_SHOWING
        assertTrue("少于 6 条说明有情况没交代", list.size >= 6)
        list.forEach {
            assertTrue(it.symptom.isNotBlank())
            assertTrue(it.why.isNotBlank())
            // 症状是给人扫的，一行扫不完就没人扫了。
            assertTrue("症状那一行别写成一段话：${it.symptom}", it.symptom.length <= 24)
        }
        assertEquals("症状不许重复", list.size, list.map { it.symptom }.toSet().size)
    }

    @Test
    fun `最常见的两条排在最前 —— 按发生概率排，不按技术严重程度排`() {
        val first = AutofillSettingsModel.WHY_NOT_SHOWING.take(2)
        assertTrue(first[0].why.contains("默认填充服务"))
        assertTrue(first[1].why.contains("锁着"))
    }

    @Test
    fun `不能证明应用和网站是一家那一条，必须交代为什么我们查不了`() {
        val r = AutofillSettingsModel.WHY_NOT_SHOWING.first { it.why.contains("联网") }
        // 业界的正规查法要联网，而这个应用连 INTERNET 权限都没有（决策③）。
        // 只写「我们不填」而不写为什么，读起来就是这个应用功能不全。
        assertTrue(r.why.contains("网络权限"))
        // 每一条「不自动填」都必须给出路：手动挑那条路始终在。
        assertTrue(r.why.contains("搜索"))
    }

    @Test
    fun `浏览器认不出来那一条要说清是安全考虑，而且仍然给得出手动那条路`() {
        val r = AutofillSettingsModel.WHY_NOT_SHOWING.first { it.symptom.contains("浏览器") }
        assertTrue(r.why.contains("攻击"))
        assertTrue(r.why.contains("自己挑"))
    }

    @Test
    fun `收尾那句必须正着把底线说一遍`() {
        val tail = AutofillSettingsModel.WHY_TAIL
        // 前面七条里五条的结论是「我们故意不填」，读完容易觉得这东西毛病真多。
        assertTrue(tail.contains("不是出了故障"))
        assertTrue(tail.contains("收不回来"))
    }

    /* ═════════════ 和别处对得上 ═════════════ */

    @Test
    fun `关于页那句是指路牌，不是那份清单的副本`() {
        val pointer = AutofillSettingsModel.ABOUT_POINTER
        assertTrue(pointer.contains("设置"))
        // 一句话的长度。摆成第二份清单，就会变成只改一处的那两处之一。
        assertTrue(pointer.length <= 40)
        AutofillSettingsModel.WHY_NOT_SHOWING.forEach {
            assertFalse("清单的正文不许在关于页上再出现一遍", pointer.contains(it.symptom))
        }
    }

    @Test
    fun `这一页那三条底线和关于页那一段说的是同一件事，但不是同一份字`() {
        val here = AutofillSettingsModel.LIMITS
        val there = SettingsModel.AUTOFILL_NOTE
        assertTrue(here.isNotEmpty() && there.isNotEmpty())
        // 两份都短、各自完整、谁都不必先读另一份；但不许逐字相同——
        // 逐字相同就该合并成一个常量，摆两份的唯一理由是读者不同。
        assertTrue(here.toSet().intersect(there.toSet()).isEmpty())
        // 两份都必须守住「填充条上不显示密码」这一条（决策(203) 那一路的门面）。
        assertTrue(here.any { it.contains("不显示密码") })
        assertTrue(there.any { it.contains("不显示密码") })
    }

    @Test
    fun `设置主页那一行永远不变色 —— 没开自动填充不是一件待办`() {
        // 备份那一行会转黄铜色，因为不备份会丢东西；主密码那一行会，
        // 因为改完旧备份就打不开了。没开自动填充只是要多复制粘贴一次。
        // 为推销一个功能去染黄一行字，代价是这一页的颜色从此不可信（决策(95)）。
        Availability.values().forEach {
            assertFalse(it.name, AutofillSettingsModel.settingsRowUrgent(it))
        }
    }

    @Test
    fun `设置主页那一行的副标题四档各不相同，而且不写「已开启 未开启」`() {
        val all = Availability.values().map { AutofillSettingsModel.settingsRowSummary(it) }
        assertEquals(4, all.toSet().size)
        all.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `页首那段只讲用户看得到的现象`() {
        val intro = AutofillSettingsModel.INTRO
        assertTrue(intro.contains("密码框"))
        listOf("AssistStructure", "FillResponse", "解析", "Dataset").forEach {
            assertFalse("别把我们这边的事写给用户看：$it", intro.contains(it))
        }
    }

    /* ══════════════════════ 「请勿填充」那一项 ══════════════════════ */

    @Test
    fun `请勿填充那一项两档各说各的，而且都不留空`() {
        val off = AutofillSettingsModel.optOutRow(respected = false)
        val on = AutofillSettingsModel.optOutRow(respected = true)

        assertNotEquals(off.subtitle, on.subtitle)
        assertNotEquals(off.note, on.note)
        listOf(off.subtitle, off.note, on.subtitle, on.note).forEach {
            assertTrue(it.isNotBlank())
        }
    }

    @Test
    fun `两档的说明都写清了代价`() {
        // 两个方向都有真实代价，只写一档的话另一档变成暗雷：
        // 用户拨过去，过两周遇到症状，完全想不起来是这一下造成的。
        // 关着 —— 我们在做应用作者明确不希望的事，得说出来
        assertTrue(AutofillSettingsModel.optOutRow(respected = false).note.contains("不听"))
        // 开着 —— 一批应用会彻底填不了，而那个现象和「坏了」长得一样
        assertTrue(AutofillSettingsModel.optOutRow(respected = true).note.contains("填不了"))
    }

    @Test
    fun `行名写成正面说法，避免默认状态被读反`() {
        // 「忽略应用的请勿填充声明」这种写法，默认（关）读起来是「不忽略」＝「尊重」，
        // 而实际行为正好相反。行名必须描述打开之后会发生什么。
        val title = AutofillSettingsModel.OPT_OUT_TITLE
        assertTrue(title.contains("尊重"))
        assertFalse(title.contains("忽略"))
    }

    @Test
    fun `请勿填充那一项没有混进那份症状清单`() {
        // WHY_NOT_SHOWING 是给「默认状态下遇到症状」的人扫的，而这一项默认是关的。
        // 加一条只会让绝大多数读者去排查一个他没打开过的设置。
        AutofillSettingsModel.WHY_NOT_SHOWING.forEach {
            assertFalse(it.symptom + " / " + it.why, it.why.contains("请勿填充"))
        }
    }
}
