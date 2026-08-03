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
import cn.localvault.app.ui.autofill.FieldGroups
import cn.localvault.app.ui.autofill.FillContext
import cn.localvault.app.ui.autofill.FillPlan
import cn.localvault.app.ui.autofill.Origin
import cn.localvault.app.ui.autofill.RawField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这一组框里到底往哪几个框写什么」。
 *
 * 这一层的两条底线（新密码栏绝不填已有密码、分不出新旧的密码框一个都不填）
 * 在真机上验证不了：填错了不报错，用户点一下提交，页面照样说「修改成功」，
 * 代价要到很久以后才显出来。所以全部钉在这儿。
 */
class FillPlanTest {

    private var seq = 0L

    private fun f(
        hint: String? = null,
        web: String? = null,
        id: String? = null,
        focused: Boolean = false,
    ): RawField = RawField(
        handle = seq++,
        autofillHints = if (hint == null) emptyList() else listOf(hint),
        idEntry = id,
        webDomain = web,
        focused = focused,
    )

    private fun user(web: String? = null, focused: Boolean = false) =
        f(hint = "username", web = web, focused = focused)

    private fun pass(web: String? = null, focused: Boolean = false) =
        f(hint = "password", web = web, focused = focused)

    private fun newPass(web: String? = null) = f(hint = "newPassword", web = web)

    private fun otp(web: String? = null, focused: Boolean = false) =
        f(hint = "smsOTPCode", web = web, focused = focused)

    private fun ctx(app: String, vararg fields: RawField) =
        FillContext(activityPackage = app, fields = fields.toList())

    private fun formOf(vararg fields: RawField): FillPlan.Form =
        FillPlan.of(FieldGroups.split(ctx("com.a.b", *fields)).single())

    private fun entry(username: String = "zhangsan", password: String = "s3cr3t") =
        VaultEntry(id = "id-1", name = "招商银行", username = username, password = password)

    /* ══════════════════════════ 五种表单 ══════════════════════════ */

    @Test
    fun `登录表单填账号和密码，账号在前`() {
        val u = user()
        val p = pass()
        val form = formOf(u, p)
        assertEquals(FillPlan.Kind.Login, form.kind)
        assertEquals(listOf(u.handle, p.handle), form.targets.map { it.handle })
        assertEquals(
            listOf(FillPlan.Slot.Username, FillPlan.Slot.Password),
            form.targets.map { it.slot },
        )
    }

    @Test
    fun `只有账号框是分屏登录的第一屏`() {
        val u = user()
        val form = formOf(u)
        assertEquals(FillPlan.Kind.UsernameStep, form.kind)
        assertEquals(listOf(u.handle), form.targets.map { it.handle })
        assertTrue(form.wantsUsername)
        assertFalse(form.wantsPassword)
    }

    @Test
    fun `只有密码框是分屏登录的第二屏`() {
        val p = pass()
        val form = formOf(p)
        assertEquals(FillPlan.Kind.PasswordStep, form.kind)
        assertEquals(listOf(p.handle), form.targets.map { it.handle })
        assertTrue(form.wantsPassword)
        assertFalse(form.wantsUsername)
    }

    @Test
    fun `注册表单只填账号，两个新密码框都留空`() {
        val u = user()
        val form = formOf(u, newPass(), newPass())
        assertEquals(FillPlan.Kind.NewCredential, form.kind)
        assertEquals(listOf(u.handle), form.targets.map { it.handle })
        assertFalse(form.wantsPassword)
        assertEquals(2, form.skipped[FillPlan.Skipped.NewPasswordField])
    }

    @Test
    fun `改密码表单填账号和当前密码，新密码那栏留空`() {
        // 这是 NewCredential 里唯一会填密码的形状：作者明确标出了「当前密码」。
        val u = user()
        val current = pass()
        val form = formOf(u, current, newPass())
        assertEquals(FillPlan.Kind.NewCredential, form.kind)
        assertEquals(listOf(u.handle, current.handle), form.targets.map { it.handle })
        assertEquals(1, form.skipped[FillPlan.Skipped.NewPasswordField])
    }

    @Test
    fun `两个分不出新旧的密码框一个都不填，只填账号`() {
        val u = user()
        val form = formOf(u, pass(), pass())
        assertEquals(FillPlan.Kind.AmbiguousPasswords, form.kind)
        assertEquals(listOf(u.handle), form.targets.map { it.handle })
        assertFalse(form.wantsPassword)
        assertEquals(2, form.skipped[FillPlan.Skipped.AmbiguousPasswordField])
    }

    @Test
    fun `三个都没标的密码框同样一个都不填`() {
        val form = formOf(user(), pass(), pass(), pass())
        assertEquals(FillPlan.Kind.AmbiguousPasswords, form.kind)
        assertEquals(3, form.skipped[FillPlan.Skipped.AmbiguousPasswordField])
    }

    @Test
    fun `分不出新旧压过有新密码框那一档`() {
        // 两个已有密码 + 一个新密码：连「哪个是现在的」都没定论，
        // 这时候往任何一个里填都是拿底线赌运气。
        val form = formOf(user(), pass(), pass(), newPass())
        assertEquals(FillPlan.Kind.AmbiguousPasswords, form.kind)
        assertFalse(form.wantsPassword)
    }

    @Test
    fun `连账号都没有的密码歧义表单一个 target 都没有`() {
        val form = formOf(pass(), pass())
        assertEquals(FillPlan.Kind.AmbiguousPasswords, form.kind)
        assertTrue(form.isEmpty)
    }

    @Test
    fun `整屏只有验证码框时什么都不填`() {
        val form = formOf(otp())
        assertEquals(FillPlan.Kind.Nothing, form.kind)
        assertTrue(form.isEmpty)
        assertEquals(1, form.skipped[FillPlan.Skipped.OtpField])
    }

    @Test
    fun `验证码框在登录表单里也永远不填，但要记一笔`() {
        val u = user()
        val p = pass()
        val form = formOf(u, p, otp())
        assertEquals(FillPlan.Kind.Login, form.kind)
        assertEquals(listOf(u.handle, p.handle), form.targets.map { it.handle })
        assertEquals(1, form.skipped[FillPlan.Skipped.OtpField])
    }

    @Test
    fun `多个账号框只填第一个，多出来的记一笔`() {
        val first = user()
        val second = user()
        val form = formOf(first, second, pass())
        assertEquals(listOf(first.handle), form.targets.filter { it.slot == FillPlan.Slot.Username }.map { it.handle })
        assertFalse(form.targets.any { it.handle == second.handle })
        assertEquals(1, form.skipped[FillPlan.Skipped.ExtraUsernameField])
    }

    @Test
    fun `没有被跳过的东西时记账表是空的`() {
        // 计数为 0 的键一个都不许出现——否则 M4-4 那一页会摆出四条
        // 「跳过了 0 个」的废话。
        assertTrue(formOf(user(), pass()).skipped.isEmpty())
    }

    /* ══════════════════════════ 真正写下去的值 ══════════════════════════ */

    @Test
    fun `写下去的值和格位对得上`() {
        val u = user()
        val p = pass()
        val writes = FillPlan.writes(formOf(u, p), entry())
        assertEquals(2, writes.size)
        assertEquals(u.handle, writes[0].handle)
        assertEquals("zhangsan", writes[0].value)
        assertEquals(p.handle, writes[1].handle)
        assertEquals("s3cr3t", writes[1].value)
    }

    @Test
    fun `条目没有密码时只写账号，不用空串把框擦掉`() {
        val writes = FillPlan.writes(formOf(user(), pass()), entry(password = ""))
        assertEquals(1, writes.size)
        assertEquals(FillPlan.Slot.Username, writes[0].slot)
    }

    @Test
    fun `条目没有账号时只写密码`() {
        val writes = FillPlan.writes(formOf(user(), pass()), entry(username = ""))
        assertEquals(1, writes.size)
        assertEquals(FillPlan.Slot.Password, writes[0].slot)
    }

    @Test
    fun `账号密码都空的条目一个字都不写`() {
        assertTrue(FillPlan.writes(formOf(user(), pass()), entry("", "")).isEmpty())
    }

    @Test
    fun `密码首尾的空格原样写下去`() {
        // 决策(57)：密码不 trim。到了这一步更不许动它。
        val writes = FillPlan.writes(formOf(pass()), entry(password = " a b "))
        assertEquals(" a b ", writes.single().value)
    }

    @Test
    fun `Write 的 toString 不吐值`() {
        val w = FillPlan.writes(formOf(user(), pass()), entry()).last()
        val s = w.toString()
        assertFalse(s.contains("s3cr3t"))
        assertTrue(s.contains("Password"))
    }

    @Test
    fun `Target 和 Form 的 toString 不吐主机名`() {
        val group = FieldGroups.split(
            ctx("com.evil.wallpapers", user(web = "bank.example.com"), pass(web = "bank.example.com"))
        ).single()
        val form = FillPlan.of(group)
        assertFalse(form.toString().contains("bank"))
        assertFalse(form.toString().contains("evil"))
        assertFalse(form.targets.first().toString().contains("bank"))
    }

    /* ══════════════════════════ 主表单挑哪一个 ══════════════════════════ */

    @Test
    fun `光标所在那一组优先当主表单`() {
        val plan = FillPlan.forRequest(
            ctx(
                "com.android.chrome",
                user(web = "a.com", focused = true),
                pass(web = "b.com"),
                user(web = "b.com"),
            )
        )
        assertEquals(0, plan.primaryIndex)
        assertEquals(FillPlan.Kind.UsernameStep, plan.primary?.kind)
    }

    @Test
    fun `光标那一组什么都填不了时让位给账号密码齐全的那一组`() {
        // 光标在验证码框里，而同屏还摆着一套空着的账号密码。
        val plan = FillPlan.forRequest(
            ctx(
                "com.android.chrome",
                otp(focused = true),
                user(web = "a.com"),
                pass(web = "a.com"),
            )
        )
        assertEquals(FillPlan.Kind.Login, plan.primary?.kind)
        assertEquals(1, plan.primaryIndex)
    }

    @Test
    fun `没有登录表单时挑第一个有东西可填的`() {
        val plan = FillPlan.forRequest(ctx("com.a.b", otp(), user(web = "a.com")))
        assertEquals(1, plan.primaryIndex)
        assertEquals(FillPlan.Kind.UsernameStep, plan.primary?.kind)
    }

    @Test
    fun `一个组都填不了东西时主表单是 null`() {
        val plan = FillPlan.forRequest(ctx("com.a.b", otp()))
        assertEquals(1, plan.forms.size)
        assertEquals(-1, plan.primaryIndex)
        assertNull(plan.primary)
    }

    @Test
    fun `一个框都没有时既没有表单也没有主表单`() {
        val plan = FillPlan.forRequest(ctx("com.a.b"))
        assertTrue(plan.forms.isEmpty())
        assertNull(plan.primary)
    }

    @Test
    fun `一屏两个表单都留在清单里，顺序稳定`() {
        // 系统的 Dataset 是按 AutofillId 装的，一次可以把同屏两套框都写好。
        val plan = FillPlan.forRequest(ctx("com.a.b", user(), pass(), user(), pass()))
        assertEquals(2, plan.forms.size)
        assertTrue(plan.forms.all { it.kind == FillPlan.Kind.Login })
        assertEquals(0, plan.primaryIndex)
    }

    @Test
    fun `主表单带着自己那一组的归属`() {
        val plan = FillPlan.forRequest(
            ctx("com.evil.wallpapers", user(web = "bank.example.com"), pass(web = "bank.example.com"))
        )
        val origin = plan.primary?.origin as Origin.Web
        assertEquals("bank.example.com", origin.host)
        assertEquals("com.evil.wallpapers", origin.hostApp)
    }

    @Test
    fun `原生表单的主表单归属是应用本身`() {
        val plan = FillPlan.forRequest(ctx("com.sina.weibo", user(), pass()))
        assertEquals(Origin.App("com.sina.weibo"), plan.primary?.origin)
    }

    /* ══════════════════════════ 话怎么说 ══════════════════════════ */

    @Test
    fun `一切照常的两档一句废话都不说`() {
        assertNull(FillPlan.kindNote(FillPlan.Kind.Login))
        assertNull(FillPlan.kindNote(FillPlan.Kind.PasswordStep))
    }

    @Test
    fun `其余几档都有话说，而且互不重样`() {
        val notes = FillPlan.Kind.values().mapNotNull { FillPlan.kindNote(it) }
        assertEquals(4, notes.size)
        assertEquals(notes.size, notes.toSet().size)
        assertTrue(notes.all { it.isNotBlank() })
    }

    @Test
    fun `这几句话里没有一句把它说成故障`() {
        val bad = listOf("失败", "出错", "错误", "稍后重试", "联系客服", "崩溃", "不支持")
        for (note in FillPlan.Kind.values().mapNotNull { FillPlan.kindNote(it) }) {
            for (w in bad) assertFalse("$note 里出现了「$w」", note.contains(w))
        }
    }

    @Test
    fun `新密码那一句要写出后果，而不是只说「留空了」`() {
        val note = FillPlan.kindNote(FillPlan.Kind.NewCredential)!!
        assertTrue(note.contains("新密码"))
        assertTrue(note.contains("一模一样") || note.contains("等于没换"))
    }

    @Test
    fun `密码歧义那一句要写明只填了账号，并交代下一步`() {
        val note = FillPlan.kindNote(FillPlan.Kind.AmbiguousPasswords)!!
        assertTrue(note.contains("账号"))
        assertTrue(note.contains("粘"))
    }

    @Test
    fun `四条跳过说明互不重样，也都不是故障口吻`() {
        val notes = FillPlan.Skipped.values().map { FillPlan.skipNote(it) }
        assertEquals(4, notes.size)
        assertEquals(notes.size, notes.toSet().size)
        for (note in notes) {
            assertTrue(note.isNotBlank())
            assertFalse(note.contains("失败"))
            assertFalse(note.contains("稍后重试"))
        }
    }

    @Test
    fun `验证码那一句要说清那东西压根不在保险库里`() {
        val note = FillPlan.skipNote(FillPlan.Skipped.OtpField)
        assertTrue(note.contains("验证码"))
        assertTrue(note.contains("不在保险库里"))
    }
}
