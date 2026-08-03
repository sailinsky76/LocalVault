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
import cn.localvault.app.ui.add.AddFlow
import cn.localvault.app.ui.edit.EntryForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新增 3 步流的内核。
 *
 * 这里盯着四件在界面上验不动、又几乎必然会被后来的改动悄悄破掉的事：
 *
 *  - **三步的字段不重不漏**。漏一个的表现是「有个东西怎么都填不进去」，
 *    重一个的表现是「同一个框在两屏上各改各的，后一屏把前一屏盖掉」——
 *    两种都要走完整条流程才看得出来。
 *  - **「放弃新增」弹窗里绝不出现字段值**。此刻草稿里很可能正躺着一串刚生成的密码，
 *    而弹窗是一个不继承 `FLAG_SECURE` 的独立 window（决策⑭）。
 *  - **回顾卡上的密码永远是固定 12 个圆点**（决策㊽）。按真实长度画看着更贴心，
 *    而位数是离线爆破时最值钱的一条边信息。
 *  - **判重不许把「同一个站的两个账号」当成重复**。私人邮箱和工作邮箱是最常见的
 *    一对，把它报成重复，用户会以为自己做错了事。
 */
class AddFlowTest {

    private fun entry(
        id: String = "id-1",
        name: String = "招商银行",
        username: String = "",
        domains: List<String> = emptyList(),
    ) = VaultEntry(id = id, name = name, username = username, domains = domains)

    private fun draft(
        name: String = "",
        username: String = "",
        password: String = "",
        domainsText: String = "",
        category: String = "",
        notes: String = "",
    ) = EntryForm.Draft(name, username, password, domainsText, category, notes)

    /* ══════════════════════ 步骤本身 ══════════════════════ */

    @Test
    fun `三步的字段并起来正好是六个`() {
        val union = AddFlow.steps.flatMap { AddFlow.fields(it) }
        assertEquals(EntryForm.Field.values().size, union.size)
        assertEquals(EntryForm.Field.values().toSet(), union.toSet())
    }

    @Test
    fun `三步的字段两两不相交`() {
        assertEquals(AddFlow.steps.flatMap { AddFlow.fields(it) }.size,
            AddFlow.steps.flatMap { AddFlow.fields(it) }.distinct().size)
    }

    @Test
    fun `前后步与首末判断`() {
        assertEquals(AddFlow.Step.Password, AddFlow.next(AddFlow.Step.Basics))
        assertEquals(AddFlow.Step.Password, AddFlow.prev(AddFlow.Step.Filing))
        assertNull(AddFlow.prev(AddFlow.Step.Basics))
        assertNull(AddFlow.next(AddFlow.Step.Filing))
        assertTrue(AddFlow.isLast(AddFlow.Step.Filing))
        assertFalse(AddFlow.isLast(AddFlow.Step.Basics))
        assertEquals("1 / 3", AddFlow.ordinal(AddFlow.Step.Basics))
        assertEquals("3 / 3", AddFlow.ordinal(AddFlow.Step.Filing))
    }

    @Test
    fun `最后一步的按钮写的是保存，前面写下一步`() {
        assertEquals("保存", AddFlow.advanceText(AddFlow.Step.Filing))
        assertEquals("下一步", AddFlow.advanceText(AddFlow.Step.Basics))
        assertEquals("下一步", AddFlow.advanceText(AddFlow.Step.Password))
    }

    /* ══════════════════════ 能不能往下走 ══════════════════════ */

    @Test
    fun `第一步卡名称，后两步在名称已填的前提下一路放行`() {
        val empty = draft()
        assertFalse(AddFlow.canAdvance(AddFlow.Step.Basics, empty))
        assertTrue(AddFlow.canAdvance(AddFlow.Step.Password, empty.copy(name = "x")))
        assertTrue(AddFlow.canAdvance(AddFlow.Step.Filing, empty.copy(name = "x")))
        // 兜底：万一将来有人让用户绕过第一步，最后那下保存也不许放行
        assertFalse(AddFlow.canAdvance(AddFlow.Step.Filing, empty))
    }

    @Test
    fun `全是空白的名称不算填了`() {
        assertFalse(AddFlow.canAdvance(AddFlow.Step.Basics, draft(name = "   \n ")))
        assertTrue(AddFlow.canAdvance(AddFlow.Step.Basics, draft(name = " 微信 ")))
    }

    @Test
    fun `灰按钮永远配一句解释，能点的时候没有多余的话`() {
        assertNotNull(AddFlow.blockReason(AddFlow.Step.Basics, draft()))
        assertNull(AddFlow.blockReason(AddFlow.Step.Basics, draft(name = "微信")))
        assertNull(AddFlow.blockReason(AddFlow.Step.Password, draft(name = "微信")))
    }

    /* ══════════════════════ 点进度条跳步 ══════════════════════ */

    @Test
    fun `往回跳永远允许，哪怕当前这步还没填完`() {
        val bad = draft()
        assertTrue(AddFlow.canJumpTo(AddFlow.Step.Basics, AddFlow.Step.Filing, bad))
        assertTrue(AddFlow.canJumpTo(AddFlow.Step.Password, AddFlow.Step.Filing, bad))
        assertTrue(AddFlow.canJumpTo(AddFlow.Step.Basics, AddFlow.Step.Basics, bad))
    }

    @Test
    fun `名称没填时不能靠点进度条绕到后面去`() {
        val bad = draft()
        assertFalse(AddFlow.canJumpTo(AddFlow.Step.Password, AddFlow.Step.Basics, bad))
        assertFalse(AddFlow.canJumpTo(AddFlow.Step.Filing, AddFlow.Step.Basics, bad))
    }

    @Test
    fun `名称填了就能一路往前跳`() {
        val ok = draft(name = "微信")
        assertTrue(AddFlow.canJumpTo(AddFlow.Step.Password, AddFlow.Step.Basics, ok))
        assertTrue(AddFlow.canJumpTo(AddFlow.Step.Filing, AddFlow.Step.Basics, ok))
    }

    /* ══════════════════════ 自动聚焦 ══════════════════════ */

    @Test
    fun `第一步聚焦名称，但名称已经带进来时聚焦账号`() {
        assertEquals(EntryForm.Field.Name, AddFlow.autoFocus(AddFlow.Step.Basics, seededName = false))
        assertEquals(
            EntryForm.Field.Username,
            AddFlow.autoFocus(AddFlow.Step.Basics, seededName = true),
        )
    }

    /**
     * 这一条钉的是一个真出过的 bug：聚焦规则早先收的是整个草稿、判的是
     * `draft.name.isBlank()`，于是用户在名称栏敲下第一个字的瞬间，
     * 该聚焦谁的答案就翻了面，光标被搬到账号框里——名字还没打完人就被踢走。
     *
     * 现在它只认「进这条流程时名称是不是已经带进来了」，
     * 那是一个进页面就定死的事实。所以**同一次流程里，第一步的答案不许变**：
     * 参数里已经没有任何随输入变化的东西，这条也就成了一句废话——
     * 而它正是要保证这句话一直是废话。
     */
    @Test
    fun `名称栏打字不会把光标挪走`() {
        // 没带名称进来：从空到打了半个词到打完，答案自始至终是「名称」
        val first = AddFlow.autoFocus(AddFlow.Step.Basics, seededName = false)
        repeat(3) {
            assertEquals(first, AddFlow.autoFocus(AddFlow.Step.Basics, seededName = false))
        }
        assertEquals(EntryForm.Field.Name, first)
    }

    @Test
    fun `第二步和第三步不自动聚焦`() {
        assertNull(AddFlow.autoFocus(AddFlow.Step.Password, seededName = false))
        assertNull(AddFlow.autoFocus(AddFlow.Step.Filing, seededName = false))
        assertNull(AddFlow.autoFocus(AddFlow.Step.Password, seededName = true))
        assertNull(AddFlow.autoFocus(AddFlow.Step.Filing, seededName = true))
    }

    /* ══════════════════════ 中途退出 ══════════════════════ */

    @Test
    fun `什么都没填时退出不该被拦`() {
        assertTrue(AddFlow.isEmpty(draft()))
        // 网址框里多按的两个回车、账号末尾误敲的空格，都不算填了东西
        assertTrue(AddFlow.isEmpty(draft(domainsText = "\n\n  \n", username = "   ")))
    }

    @Test
    fun `密码里的一个空格算填了东西`() {
        // 密码不 trim（决策(57)），那个空格完全可能是他有意打的
        assertFalse(AddFlow.isEmpty(draft(password = " ")))
    }

    @Test
    fun `放弃弹窗的摘要里只有字段名，一个值都没有`() {
        val d = draft(
            name = "招商银行",
            username = "13800000000",
            password = "Kx7#mQ2ap",
            notes = "身份证 110101",
        )
        val s = AddFlow.filledSummary(d)
        assertTrue(s.contains("名称"))
        assertTrue(s.contains("密码"))
        assertTrue(s.contains("备注"))
        assertFalse(s.contains("招商银行"))
        assertFalse(s.contains("13800000000"))
        assertFalse(s.contains("Kx7#mQ2ap"))
        assertFalse(s.contains("110101"))
        // 没填的字段不出现
        assertFalse(s.contains("分类"))
    }

    /* ══════════════════════ 回顾卡 ══════════════════════ */

    @Test
    fun `回顾卡上的密码永远是固定 12 个圆点`() {
        val short = AddFlow.review(draft(name = "a", password = "ab"))
        val long = AddFlow.review(draft(name = "a", password = "0123456789012345678901234567890"))
        assertEquals(AddFlow.PASSWORD_DOTS, short.last().value)
        assertEquals(AddFlow.PASSWORD_DOTS, long.last().value)
        assertEquals(12, AddFlow.PASSWORD_DOTS.length)
    }

    @Test
    fun `回顾卡里不会出现密码原文`() {
        val pw = "Kx7#mQ2ap"
        val lines = AddFlow.review(draft(name = "微信", username = "u", password = pw))
        assertFalse(lines.any { it.value.contains(pw) })
    }

    @Test
    fun `没填的账号和空密码显示成灰的说明，不是空白`() {
        val lines = AddFlow.review(draft(name = "微信"))
        assertEquals("未填", lines[1].value)
        assertTrue(lines[1].dim)
        assertEquals("留空", lines[2].value)
        assertTrue(lines[2].dim)
        assertFalse(lines[0].dim)
    }

    @Test
    fun `回顾卡显示的是修剪之后的值`() {
        val lines = AddFlow.review(draft(name = "  微信  ", username = " abc "))
        assertEquals("微信", lines[0].value)
        assertEquals("abc", lines[1].value)
    }

    /* ══════════════════════ 判重 ══════════════════════ */

    @Test
    fun `同名同账号是最强的信号`() {
        val list = listOf(entry(id = "a", name = "招商银行", username = "13800000000"))
        val d = AddFlow.findDuplicate(list, draft(name = "招商银行", username = "13800000000"))
        assertNotNull(d)
        assertEquals("a", d!!.id)
        assertEquals(AddFlow.Reason.NameAndUser, d.reason)
    }

    @Test
    fun `同一个站的两个不同账号不算重复`() {
        // 私人邮箱和工作邮箱：这正是用户此刻要做的事，报重复会让他以为自己错了
        val list = listOf(entry(id = "a", name = "Gmail", username = "me@gmail.com"))
        assertNull(AddFlow.findDuplicate(list, draft(name = "Gmail", username = "work@gmail.com")))
    }

    @Test
    fun `重名而其中一边没填账号时，报一条弱提醒`() {
        val list = listOf(entry(id = "a", name = "Gmail", username = "me@gmail.com"))
        val d = AddFlow.findDuplicate(list, draft(name = "gmail"))
        assertNotNull(d)
        assertEquals(AddFlow.Reason.SameName, d!!.reason)

        val list2 = listOf(entry(id = "b", name = "Gmail", username = ""))
        val d2 = AddFlow.findDuplicate(list2, draft(name = "Gmail", username = "me@gmail.com"))
        assertEquals(AddFlow.Reason.SameName, d2!!.reason)
    }

    @Test
    fun `名字写得不一样但同主机同账号也拦得住`() {
        val list = listOf(
            entry(id = "a", name = "淘宝", username = "abc", domains = listOf("taobao.com")),
        )
        val d = AddFlow.findDuplicate(
            list,
            draft(name = "Taobao", username = "abc", domainsText = "https://taobao.com/login"),
        )
        assertNotNull(d)
        assertEquals(AddFlow.Reason.DomainAndUser, d!!.reason)
    }

    @Test
    fun `账号的大小写和首尾空白不影响判重`() {
        val list = listOf(entry(id = "a", name = "GitHub", username = "Admin"))
        val d = AddFlow.findDuplicate(list, draft(name = " github ", username = "admin "))
        assertEquals(AddFlow.Reason.NameAndUser, d!!.reason)
    }

    @Test
    fun `子域名不同不算同一个站`() {
        // 决策㉝：一个子域名都不剥，所以 mail 和 www 是两个主机
        val list = listOf(
            entry(id = "a", name = "A", username = "abc", domains = listOf("mail.example.com")),
        )
        assertNull(
            AddFlow.findDuplicate(
                list,
                draft(name = "B", username = "abc", domainsText = "www.example.com"),
            )
        )
    }

    @Test
    fun `名称还没打时不判重`() {
        val list = listOf(entry(id = "a", name = "招商银行", username = "abc"))
        assertNull(AddFlow.findDuplicate(list, draft(username = "abc")))
    }

    @Test
    fun `空库判不出重复`() {
        assertNull(AddFlow.findDuplicate(emptyList(), draft(name = "微信", username = "abc")))
    }

    @Test
    fun `强信号优先于弱信号，哪怕弱的排在前面`() {
        val list = listOf(
            entry(id = "weak", name = "Gmail", username = ""),
            entry(id = "strong", name = "Gmail", username = "me@gmail.com"),
        )
        val d = AddFlow.findDuplicate(list, draft(name = "Gmail", username = "me@gmail.com"))
        assertEquals("strong", d!!.id)
        assertEquals(AddFlow.Reason.NameAndUser, d.reason)
    }

    @Test
    fun `提醒文案里有名称，但没有账号也没有密码`() {
        val list = listOf(entry(id = "a", name = "招商银行", username = "13800000000"))
        val d = AddFlow.findDuplicate(
            list,
            draft(name = "招商银行", username = "13800000000", password = "Kx7#mQ2ap"),
        )!!
        val msg = AddFlow.duplicateMessage(d)
        assertTrue(msg.contains("招商银行"))
        assertFalse(msg.contains("13800000000"))
        assertFalse(msg.contains("Kx7#mQ2ap"))
    }

    @Test
    fun `重复只是提醒，不阻拦保存`() {
        assertFalse(AddFlow.duplicateIsBlocking())
    }

    /* ══════════════════════ 存完之后去哪儿 ══════════════════════ */

    @Test
    fun `不管新条目落在哪个位置都找得到它的 id`() {
        val before = listOf(entry(id = "a"), entry(id = "b"))
        assertEquals("new", AddFlow.newestId(before, before + entry(id = "new")))
        assertEquals("new", AddFlow.newestId(before, listOf(entry(id = "new")) + before))
        assertEquals(
            "new",
            AddFlow.newestId(before, listOf(entry(id = "a"), entry(id = "new"), entry(id = "b"))),
        )
    }

    @Test
    fun `一条都没多出来时返回 null，而不是随便给一个`() {
        val before = listOf(entry(id = "a"), entry(id = "b"))
        assertNull(AddFlow.newestId(before, before))
        assertNull(AddFlow.newestId(before, listOf(entry(id = "a"))))
    }

    /* ══════════════════════ 文案 ══════════════════════ */

    @Test
    fun `每一步都有标题和一句能回答「这步能不能跳过」的话`() {
        AddFlow.steps.forEach { s ->
            assertTrue(AddFlow.title(s).isNotBlank())
            assertTrue(AddFlow.hint(s).isNotBlank())
        }
    }
}
