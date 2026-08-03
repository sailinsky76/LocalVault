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

import cn.localvault.app.ui.autofill.FieldGroups
import cn.localvault.app.ui.autofill.FillContext
import cn.localvault.app.ui.autofill.FillPlan
import cn.localvault.app.ui.autofill.Origin
import cn.localvault.app.ui.autofill.RawField
import cn.localvault.app.ui.autofill.SavePlan
import cn.localvault.app.ui.autofill.SavedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这一屏要不要挂 `SaveInfo`；挂的话，看着哪几个框」。
 *
 * 这一层的两种错法在真机上都**不报错**，所以全部钉在这儿：
 *
 *   · **少挂一个框** —— 用户在改密码页把新密码打完、提交成功，保存框一次都没出现。
 *     他不会来报告这件事，只会觉得这个功能不太行，然后回去手工复制粘贴。
 *     第二节那几条钉的就是它，其中「只有一个新密码框的改密码页」那一条最要紧：
 *     那一屏在 `FillPlan` 那一侧是**空的**（底线一，一个框都不填），
 *     把 `pickForSave` 改成调 `FillPlan.pick` 来省事，它立刻红。
 *   · **多挂一屏** —— 用户被弹了一次保存框，按下去却什么都没发生
 *     （`AutofillSave.refuse` 在那一步拒绝）。第一节钉的是这个。
 */
class SavePlanTest {

    private var seq = 0L

    private fun f(
        hint: String? = null,
        web: String? = null,
        focused: Boolean = false,
    ): RawField = RawField(
        handle = seq++,
        autofillHints = if (hint == null) emptyList() else listOf(hint),
        webDomain = web,
        focused = focused,
    )

    private fun user(web: String? = null, focused: Boolean = false) =
        f(hint = "username", web = web, focused = focused)

    private fun pass(web: String? = null, focused: Boolean = false) =
        f(hint = "password", web = web, focused = focused)

    private fun newPass(web: String? = null, focused: Boolean = false) =
        f(hint = "newPassword", web = web, focused = focused)

    private fun otp(web: String? = null, focused: Boolean = false) =
        f(hint = "smsOTPCode", web = web, focused = focused)

    private fun ctx(app: String = HOST, vararg fields: RawField) =
        FillContext(activityPackage = app, fields = fields.toList())

    private fun decide(vararg fields: RawField): SavePlan.Decision =
        SavePlan.decide(ctx(HOST, *fields), OWN)

    private fun hang(vararg fields: RawField): SavePlan.Info {
        val d = decide(*fields)
        assertTrue("本该挂，实际是 $d", d is SavePlan.Decision.Hang)
        return (d as SavePlan.Decision.Hang).info
    }

    private fun skip(vararg fields: RawField): SavePlan.Skip {
        val d = decide(*fields)
        assertTrue("本该跳过，实际是 $d", d is SavePlan.Decision.Skipped)
        return (d as SavePlan.Decision.Skipped).why
    }

    private fun whatOf(info: SavePlan.Info, handle: Long): SavedFields.Captured? =
        info.watches.firstOrNull { it.handle == handle }?.what

    /* ══════════════════════════ 一、不挂的那几屏 ══════════════════════════ */

    @Test
    fun `保险库自己的界面一个框都不看`() {
        val c = FillContext(activityPackage = OWN, fields = listOf(user(), pass()))
        val d = SavePlan.decide(c, OWN)
        assertTrue(d is SavePlan.Decision.Skipped)
        assertEquals(SavePlan.Skip.OwnUi, (d as SavePlan.Decision.Skipped).why)
    }

    @Test
    fun `包名不写死，换一个自己的包名答案跟着变`() {
        // debug 构建的包名带着 `.debug` 后缀。写死的话「不为自己存」在 debug 包上是失效的。
        val c = FillContext(activityPackage = "$OWN.debug", fields = listOf(user(), pass()))
        assertTrue(SavePlan.decide(c, OWN) is SavePlan.Decision.Hang)
        assertTrue(SavePlan.decide(c, "$OWN.debug") is SavePlan.Decision.Skipped)
    }

    @Test
    fun `一个框都没有时是没有表单`() {
        assertEquals(SavePlan.Skip.NoForm, skip())
    }

    @Test
    fun `整屏只有验证码框时是没有表单`() {
        assertEquals(SavePlan.Skip.NoForm, skip(otp()))
    }

    @Test
    fun `只有账号框的那一屏不挂，而且说得出自己那一句`() {
        // 分屏登录的第一屏。这里必须是 NoPasswordField 而不是 NoForm——
        // 屏幕上明明有一个账号框，对着它说「没有认得出来的登录表单」是一句假话。
        assertEquals(SavePlan.Skip.NoPasswordField, skip(user()))
    }

    @Test
    fun `两个分不出新旧的密码框提前跳过，不等到按下保存才拒绝`() {
        assertEquals(SavePlan.Skip.AmbiguousPasswords, skip(user(), pass(), pass()))
    }

    @Test
    fun `四档跳过各有各的话，一句都不重样，也没有一句说成故障`() {
        val notes = SavePlan.Skip.entries.map { SavePlan.note(it) }
        assertEquals(notes.size, notes.distinct().size)
        for (n in notes) {
            assertTrue("空话：$n", n.length > 8)
            for (bad in listOf("失败", "出错", "错误", "异常", "稍后重试")) {
                assertFalse("「$bad」不该出现在：$n", n.contains(bad))
            }
        }
    }

    /* ══════════════════════════ 二、看哪几个框 ══════════════════════════ */

    @Test
    fun `登录表单看账号和密码两个框`() {
        val u = user()
        val p = pass()
        val info = hang(u, p)
        assertEquals(FillPlan.Kind.Login, info.kind)
        assertEquals(listOf(u.handle, p.handle), info.watches.map { it.handle })
        assertEquals(SavedFields.Captured.Username, whatOf(info, u.handle))
        assertEquals(SavedFields.Captured.Password, whatOf(info, p.handle))
        assertTrue(info.wantsUsername)
        assertTrue(info.wantsPassword)
    }

    @Test
    fun `分屏登录第二屏只有密码框，照样挂`() {
        val p = pass()
        val info = hang(p)
        assertEquals(FillPlan.Kind.PasswordStep, info.kind)
        assertEquals(listOf(p.handle), info.watches.map { it.handle })
        assertFalse(info.wantsUsername)
    }

    @Test
    fun `注册页上那两个新密码框都要看`() {
        // 填充那一侧对新密码框的答案是「一个都不填」（底线一）。
        // 这一侧刚好相反：用户刚打进去的值**只在**新密码框里。
        val u = user()
        val n1 = newPass()
        val n2 = newPass()
        val info = hang(u, n1, n2)
        assertEquals(FillPlan.Kind.NewCredential, info.kind)
        assertEquals(listOf(u.handle, n1.handle, n2.handle), info.watches.map { it.handle })
        assertEquals(SavedFields.Captured.NewPassword, whatOf(info, n1.handle))
        assertEquals(SavedFields.Captured.NewPassword, whatOf(info, n2.handle))
    }

    @Test
    fun `只有一个新密码框的改密码页照样挂`() {
        // 这一屏在 FillPlan 那一侧的 targets 是**空的**，FillPlan.pick 会跳过它。
        // 把 pickForSave 改成调 FillPlan.pick 来省事，这一条立刻红——
        // 而真机上的表现只是「改密码页从此再也不弹保存框」，没有任何一处会报错。
        val n = newPass()
        val form = FillPlan.of(FieldGroups.split(ctx(HOST, n)).single())
        assertTrue("前提变了：这一屏在填充那一侧本该是空的", form.isEmpty)

        val info = hang(n)
        assertEquals(listOf(n.handle), info.watches.map { it.handle })
        assertEquals(SavedFields.Captured.NewPassword, whatOf(info, n.handle))
    }

    @Test
    fun `改密码页上当前密码和新密码分别记成两档`() {
        val u = user()
        val cur = pass()
        val nw = newPass()
        val info = hang(u, cur, nw)
        assertEquals(SavedFields.Captured.Password, whatOf(info, cur.handle))
        assertEquals(SavedFields.Captured.NewPassword, whatOf(info, nw.handle))
    }

    @Test
    fun `账号只看第一个`() {
        val u1 = user()
        val u2 = user()
        val info = hang(u1, u2, pass())
        assertEquals(1, info.watches.count { it.what == SavedFields.Captured.Username })
        assertEquals(u1.handle, info.watches.first { it.what == SavedFields.Captured.Username }.handle)
    }

    @Test
    fun `验证码框一个都不看`() {
        val u = user()
        val p = pass()
        val info = hang(u, p, otp())
        assertEquals(listOf(u.handle, p.handle), info.watches.map { it.handle })
    }

    /* ══════════════════════════ 三、必填只有一个 ══════════════════════════ */

    @Test
    fun `必填永远只有一个`() {
        for (info in listOf(
            hang(user(), pass()),
            hang(user(), newPass(), newPass()),
            hang(user(), pass(), newPass()),
            hang(pass()),
            hang(newPass()),
        )) {
            assertEquals("必填多于一个：$info", 1, info.required.size)
        }
    }

    @Test
    fun `必填是新密码框而不是当前密码框`() {
        // 改密码页上用户刚打的是新密码。把「当前密码」放进必填，
        // 一个没填当前密码就提交的页面（很多站不要求）会让保存框永远不出现。
        val cur = pass()
        val nw = newPass()
        val info = hang(user(), cur, nw)
        assertEquals(listOf(nw.handle), info.required)
        assertTrue(info.optional.contains(cur.handle))
    }

    @Test
    fun `注册页上第二个新密码框进可选，不进必填`() {
        // 「密码 + 确认密码」里那个确认框常常可以不填。放进必填就再也弹不出保存框，
        // 而用户此刻刚注册完，那个密码只存在于他的短期记忆里。
        val n1 = newPass()
        val n2 = newPass()
        val info = hang(user(), n1, n2)
        assertEquals(listOf(n1.handle), info.required)
        assertTrue(info.optional.contains(n2.handle))
    }

    @Test
    fun `账号永远进可选`() {
        // 分屏登录第二屏根本没有账号框；放进必填等于那一屏永远不弹保存框。
        val u = user()
        val info = hang(u, pass())
        assertFalse(info.required.contains(u.handle))
        assertTrue(info.optional.contains(u.handle))
    }

    @Test
    fun `必填和可选加起来正好是看着的那几个框，没有重的也没有漏的`() {
        val info = hang(user(), pass(), newPass())
        val all = info.required + info.optional
        assertEquals(info.watches.map { it.handle }.toSet(), all.toSet())
        assertEquals(all.size, all.distinct().size)
        assertEquals(info.size, all.size)
    }

    /* ══════════════════════════ 四、挑哪一组 ══════════════════════════ */

    @Test
    fun `光标所在那一组优先`() {
        val a = SavePlan.decide(
            ctx(HOST, user("a.example.com"), pass("a.example.com"), user("b.example.com"), pass("b.example.com", focused = true)),
            OWN,
        )
        val info = (a as SavePlan.Decision.Hang).info
        assertEquals(Origin.Web("b.example.com", HOST), info.origin)
    }

    @Test
    fun `光标落在没有密码框的那一组时，挑真正带密码的那一组`() {
        // 一屏分成两栏：左边一个订阅邮箱框（光标在那儿），右边才是登录表单。
        // 填充那一侧会挑光标那一组（它填得出账号），保存这一侧不能照搬。
        val info = (
            SavePlan.decide(
                ctx(
                    HOST,
                    user("news.example.com", focused = true),
                    user("login.example.com"),
                    pass("login.example.com"),
                ),
                OWN,
            ) as SavePlan.Decision.Hang
            ).info
        assertEquals(Origin.Web("login.example.com", HOST), info.origin)
    }

    @Test
    fun `原生框和网页框永远不会被凑成一组`() {
        // 决策(158)：AutoSpill 那条门。这一层只是照着 FieldGroups 走，
        // 但保存这一侧凑错组的代价更重——错的关联会长期躺在库里。
        val info = (
            SavePlan.decide(ctx(HOST, user(), pass("bank.example.com")), OWN) as? SavePlan.Decision.Hang
            )?.info
        if (info != null) {
            val handles = info.watches.map { it.handle }
            assertEquals("一组里混进了两种来源", 1, handles.size)
        }
    }

    @Test
    fun `一组都没有时pickForSave返回负一`() {
        assertEquals(-1, SavePlan.pickForSave(emptyList()))
        assertEquals(-1, SavePlan.pickForSave(FieldGroups.split(ctx(HOST, otp()))))
    }

    /* ══════════════════════════ 五、那个旗子 ══════════════════════════ */

    @Test
    fun `网页要加全部不可见就保存的旗子`() {
        assertTrue(hang(user("example.com"), pass("example.com")).saveOnAllViewsInvisible)
    }

    @Test
    fun `原生应用不加那个旗子`() {
        assertFalse(hang(user(), pass()).saveOnAllViewsInvisible)
    }

    /* ══════════════════════════ 六、不吐内容 ══════════════════════════ */

    @Test
    fun `三个toString一个内容都不吐`() {
        val info = hang(user("bank.example.com"), pass("bank.example.com"))
        val texts = listOf(
            info.toString(),
            info.watches.first().toString(),
            SavePlan.Decision.Hang(info).toString(),
            skip(user()).let { SavePlan.Decision.Skipped(it).toString() },
        )
        for (t in texts) {
            assertFalse("主机名漏了：$t", t.contains("bank.example.com"))
            assertFalse("包名漏了：$t", t.contains(HOST))
        }
    }

    @Test
    fun `同样输入算两遍结果一样`() {
        val fields = listOf(user("example.com"), pass("example.com"))
        val a = (SavePlan.decide(FillContext(HOST, fields), OWN) as SavePlan.Decision.Hang).info
        val b = (SavePlan.decide(FillContext(HOST, fields), OWN) as SavePlan.Decision.Hang).info
        assertEquals(a.watches.map { it.handle }, b.watches.map { it.handle })
        assertEquals(a.required, b.required)
        assertEquals(a.kind, b.kind)
    }

    @Test
    fun `跳过和挂是两件不同的事，不会同时成立`() {
        assertNotEquals(
            SavePlan.decide(ctx(HOST, user(), pass()), OWN).javaClass,
            SavePlan.decide(ctx(HOST, user()), OWN).javaClass,
        )
    }

    private companion object {
        const val OWN = "cn.localvault.app"
        const val HOST = "com.android.chrome"
    }
}
