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

import cn.localvault.app.ui.autofill.FillContext
import cn.localvault.app.ui.autofill.FillPlan
import cn.localvault.app.ui.autofill.RawField
import cn.localvault.app.ui.autofill.SaveCapture
import cn.localvault.app.ui.autofill.SavePlan
import cn.localvault.app.ui.autofill.SavedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「看着的那几个框，此刻各自写着什么」——收成一份 `SaveContext`。
 *
 * 这一层是整条保存链上**唯一一处**明文从平台流进我们自己模型的地方，
 * 而它的每一种错法都是同一个形状：**当天没有任何症状，代价在下一次登录时才出现。**
 * 所以三条都钉在这儿：
 *
 *   · 一格读不出来**不作废整份**（分屏登录第二屏没有账号框，那一屏的密码最该存）；
 *   · **一格都不合并**——两个不一样的密码值是 `conflictingPasswords` 唯一的判据，
 *     在这一层顺手去重，「分不清就一个都不存」当场失效；
 *   · 一格抛异常**不带走整个回调**（`onSaveRequest` 里一次未捕获的异常
 *     = 别人的应用旁边弹一条崩溃提示）。
 *
 * 收值本身的规矩（账号 trim、密码一个字符不动、超长和控制字符整格拒收）
 * 在 `SavedFieldsTest` 那一侧，这里只钉「别把它绕过去」。
 */
class SaveCaptureTest {

    private var seq = 0L

    private fun f(hint: String?, web: String? = null, focused: Boolean = false) = RawField(
        handle = seq++,
        autofillHints = if (hint == null) emptyList() else listOf(hint),
        webDomain = web,
        focused = focused,
    )

    private fun user(web: String? = null) = f("username", web)
    private fun pass(web: String? = null) = f("password", web)
    private fun newPass(web: String? = null) = f("newPassword", web)

    private fun info(vararg fields: RawField): SavePlan.Info {
        val d = SavePlan.decide(FillContext(HOST, fields.toList()), OWN)
        assertTrue("本该挂，实际是 $d", d is SavePlan.Decision.Hang)
        return (d as SavePlan.Decision.Hang).info
    }

    /** 按「第几格」给值：null = 那一格没有值。 */
    private fun reader(vararg values: Pair<Long, String?>): SaveCapture.Values {
        val map = values.toMap()
        return SaveCapture.Values { handle -> map[handle] }
    }

    private fun capture(
        info: SavePlan.Info,
        values: SaveCapture.Values,
        appLabel: String? = null,
    ) = SaveCapture.capture(info, values, appLabel)

    /* ══════════════════ 一、寻常的那几屏 ══════════════════ */

    @Test
    fun `登录页两格都读到`() {
        val u = user()
        val p = pass()
        val i = info(u, p)
        val c = capture(i, reader(u.handle to "amy@example.com", p.handle to "hunter2"))

        assertEquals("amy@example.com", c.context.username)
        assertEquals("hunter2", c.context.password)
        assertEquals("hunter2", c.context.effectivePassword)
        assertEquals(2, c.tally.kept)
        assertEquals(0, c.tally.blank)
    }

    @Test
    fun `分屏第二屏只有密码框，照样收得出东西`() {
        val p = pass()
        val i = info(p)
        val c = capture(i, reader(p.handle to "hunter2"))

        assertNull(c.context.username)
        assertEquals("hunter2", c.context.effectivePassword)
        assertTrue(c.context.hasAnything)
    }

    @Test
    fun `账号那一格读不出来，密码那一格照样收下`() {
        val u = user()
        val p = pass()
        val i = info(u, p)
        // 页面在提交那一刻把账号框换掉了，句柄换不出 AutofillId → null
        val c = capture(i, reader(u.handle to null, p.handle to "hunter2"))

        assertNull(c.context.username)
        assertEquals("hunter2", c.context.effectivePassword)
        assertTrue("一格没读到不该作废整份", c.context.hasAnything)
        assertEquals(1, c.tally.kept)
        assertEquals(1, c.tally.blank)
    }

    @Test
    fun `注册页读的是新密码框那一格`() {
        val u = user()
        val n = newPass()
        val i = info(u, n)
        val c = capture(i, reader(u.handle to "amy", n.handle to "brand-new"))

        assertEquals("brand-new", c.context.newPassword)
        assertNull(c.context.password)
        assertEquals("brand-new", c.context.effectivePassword)
    }

    @Test
    fun `改密码页两个框都读，新的压过旧的`() {
        val p = pass()
        val n = newPass()
        val i = info(p, n)
        val c = capture(i, reader(p.handle to "old-one", n.handle to "new-one"))

        assertEquals("old-one", c.context.password)
        assertEquals("new-one", c.context.newPassword)
        assertEquals("新的压过旧的", "new-one", c.context.effectivePassword)
    }

    @Test
    fun `只有一个新密码框的改密码页，收得出东西`() {
        // 这一屏在 FillPlan 那一侧是空的（底线一：绝不往新密码栏里填），
        // 而它恰恰是保存最要紧的一屏。SavePlan 那边有同名的一条，这里钉收值那一半
        val n = newPass()
        val i = info(n)
        val c = capture(i, reader(n.handle to "new-one"))

        assertEquals("new-one", c.context.effectivePassword)
        assertEquals(1, c.tally.kept)
    }

    /* ══════════════════ 二、一格都不合并 ══════════════════ */

    @Test
    fun `两个密码框读到两个不一样的值，两格都留着`() {
        // 「顺手去重」在这一层最自然的写法是 distinctBy { it.what }，
        // 而它会让改密码页只剩一格——旧的那个当场消失，
        // 于是 effectivePassword 变成一场碰运气
        val p = pass()
        val n = newPass()
        val i = info(p, n)
        val c = capture(i, reader(p.handle to "one", n.handle to "two"))

        assertEquals(2, c.context.values.size)
        assertEquals("one", c.context.password)
        assertEquals("two", c.context.newPassword)
    }

    @Test
    fun `同一个 what 装两格时，分不清该存哪个这条判据还在`() {
        // 两个都判成「已有密码」的一屏会被 SavePlan 提前挡掉
        // （Skip.AmbiguousPasswords），所以这种 Info 造不出来。
        // 这一条钉的是下游那条判据本身：一旦哪天有人在 SaveCapture 里加一句去重，
        // conflictingPasswords 就再也不会成立，而它守的是「分不清就一个都不存」
        val i = info(pass())
        val two = cn.localvault.app.ui.autofill.SaveContext(
            origin = i.origin,
            kind = i.kind,
            values = listOf(
                SavedFields.capture(SavedFields.Captured.Password, "one")!!,
                SavedFields.capture(SavedFields.Captured.Password, "two")!!,
            ),
        )
        assertTrue("这一条是 conflictingPasswords 唯一的判据", two.conflictingPasswords)
    }

    @Test
    fun `收下来那几格的顺序，和看着的那几个框一模一样`() {
        val u = user()
        val p = pass()
        val n = newPass()
        val i = info(u, p, n)
        val c = capture(
            i,
            reader(u.handle to "amy", p.handle to "old", n.handle to "new"),
        )
        assertEquals(
            i.watches.map { it.what },
            c.context.values.map { it.what },
        )
    }

    @Test
    fun `密码那一格一个字符都不洗，首尾空白留着`() {
        val p = pass()
        val i = info(p)
        val c = capture(i, reader(p.handle to " pw with space "))
        assertEquals(" pw with space ", c.context.effectivePassword)
    }

    @Test
    fun `账号那一格剔掉首尾空白`() {
        val u = user()
        val p = pass()
        val i = info(u, p)
        val c = capture(i, reader(u.handle to "  amy  ", p.handle to "x"))
        assertEquals("amy", c.context.username)
    }

    /* ══════════════════ 三、收不下的那几格 ══════════════════ */

    @Test
    fun `空的那一格记成 blank，不记成拒收`() {
        val u = user()
        val p = pass()
        val i = info(u, p)
        val c = capture(i, reader(u.handle to "   ", p.handle to "x"))

        assertNull(c.context.username)
        assertEquals(1, c.tally.blank)
        assertEquals(0, c.tally.rejected)
    }

    @Test
    fun `超长那一格整格拒收，并且记在 tooLong 上`() {
        val u = user()
        val p = pass()
        val i = info(u, p)
        val long = "x".repeat(SavedFields.MAX_VALUE_CHARS + 1)
        val c = capture(i, reader(u.handle to "amy", p.handle to long))

        assertNull("超长不许截断存进去", c.context.effectivePassword)
        assertEquals(1, c.tally.tooLong)
        assertEquals("读到了东西但没要", 1, c.tally.rejected)
    }

    @Test
    fun `一串圆点那种控制字符值被拒收，并且记在 control 上`() {
        val p = pass()
        val i = info(p)
        val c = capture(i, reader(p.handle to "pass\u202eword"))

        assertNull(c.context.effectivePassword)
        assertEquals(1, c.tally.control)
        assertEquals(1, c.tally.rejected)
    }

    @Test
    fun `安全键盘那一串圆点被整格拒收，绝不覆盖库里的密码`() {
        // 决策(229)：com.sgcc.wsgw.cn 的密码框里摆的就是一串 •，真值在 SDK 缓冲里。
        // 收下它 = 把库里那条正确的密码换成一串圆点，而且找不回来。
        val p = pass()
        val i = info(p)
        val c = capture(i, reader(p.handle to "\u2022\u2022\u2022\u2022\u2022\u2022"))

        assertNull(c.context.effectivePassword)
        assertTrue(c.context.maskedPassword)
        assertEquals(1, c.tally.masked)
        assertEquals(1, c.tally.rejected)
    }

    @Test
    fun `星号和句点组成的那一格也算掩码`() {
        val p = pass()
        assertNull(capture(info(p), reader(p.handle to "******")).context.effectivePassword)
        assertNull(capture(info(p), reader(p.handle to "........")).context.effectivePassword)
    }

    @Test
    fun `密码里夹着圆点不算掩码，照样原样收下`() {
        // 判据是「整格每一个字符都是掩码符」。放宽成「含有」会让
        // a•b 这种真密码被拒收，而那一档没有任何东西需要保护。
        val p = pass()
        val c = capture(info(p), reader(p.handle to "a\u2022b.c*d"))
        assertEquals("a\u2022b.c*d", c.context.effectivePassword)
        assertEquals(0, c.tally.masked)
    }

    @Test
    fun `一串圆点的用户名照样收下——掩码这一档只管密码`() {
        val u = user()
        val p = pass()
        val c = capture(info(u, p), reader(u.handle to "\u2022\u2022\u2022", p.handle to "pw"))
        assertEquals("\u2022\u2022\u2022", c.context.username)
        assertEquals("pw", c.context.effectivePassword)
        assertEquals(0, c.tally.masked)
    }

    @Test
    fun `一格都没收下时也给出 SaveContext，不返回 null`() {
        // 这一档要能说出「这一屏上没读到可以存的账号或密码」那句话
        // （AutofillSave.Reason.NothingCaptured 是它唯一的产地）
        val u = user()
        val p = pass()
        val i = info(u, p)
        val c = capture(i, reader(u.handle to null, p.handle to null))

        assertNotNull(c.context)
        assertFalse(c.context.hasAnything)
        assertEquals(0, c.tally.kept)
        assertEquals(2, c.tally.watched)
    }

    /* ══════════════════ 四、读值抛东西 ══════════════════ */

    @Test
    fun `一格抛异常，别的格照样收下`() {
        val u = user()
        val p = pass()
        val i = info(u, p)
        val values = SaveCapture.Values { handle ->
            if (handle == u.handle) throw IllegalStateException("自定义 View 的 getter")
            "hunter2"
        }
        val c = capture(i, values)

        assertEquals("hunter2", c.context.effectivePassword)
        assertEquals(1, c.tally.unreadable)
        assertEquals(1, c.tally.kept)
    }

    @Test
    fun `一格抛 Error 也接得住`() {
        // 低版本上平台 getter 缺失时抛的是 NoSuchMethodError——一个 Error。
        // 抓 Exception 的话，26 / 27 两个版本上保存整个失效，而且是崩着失效的
        val p = pass()
        val i = info(p)
        val values = SaveCapture.Values { throw NoSuchMethodError("getAutofillValue") }
        val c = capture(i, values)

        assertEquals(0, c.tally.kept)
        assertEquals(1, c.tally.unreadable)
    }

    @Test
    fun `每一格抛异常也不会抛出这个函数`() {
        val u = user()
        val p = pass()
        val n = newPass()
        val i = info(u, p, n)
        val c = capture(i, SaveCapture.Values { throw RuntimeException("boom") })
        assertEquals(3, c.tally.unreadable)
        assertFalse(c.context.hasAnything)
    }

    /* ══════════════════ 五、记账加起来对得上 ══════════════════ */

    @Test
    fun `收下的加丢掉的正好等于看着的那几格`() {
        val u = user()
        val p = pass()
        val n = newPass()
        val i = info(u, p, n)
        val c = capture(
            i,
            reader(
                u.handle to "  amy  ",
                p.handle to "",
                n.handle to "x".repeat(SavedFields.MAX_VALUE_CHARS + 1),
            ),
        )
        assertEquals(
            c.tally.watched,
            c.tally.kept + c.tally.blank + c.tally.tooLong +
                c.tally.control + c.tally.masked + c.tally.unreadable,
        )
    }

    @Test
    fun `watched 就是看着的那几个框的个数`() {
        val u = user()
        val p = pass()
        val n = newPass()
        val i = info(u, p, n)
        val c = capture(i, reader())
        assertEquals(i.watches.size, c.tally.watched)
    }

    /* ══════════════════ 六、归属和这一屏在做什么，原样带过去 ══════════════════ */

    @Test
    fun `归属直接来自那一组，不在这一层重算`() {
        val u = user(web = "example.com")
        val p = pass(web = "example.com")
        val i = info(u, p)
        val c = capture(i, reader(u.handle to "amy", p.handle to "x"))
        assertEquals(i.origin, c.context.origin)
    }

    @Test
    fun `这一屏在做什么，原样带过去`() {
        val n = newPass()
        val i = info(n)
        val c = capture(i, reader(n.handle to "x"))
        assertEquals(FillPlan.Kind.NewCredential, c.context.kind)
        assertEquals(i.kind, c.context.kind)
    }

    @Test
    fun `应用名读不出来就是 null，不编一个兜底字符串`() {
        val p = pass()
        val i = info(p)
        val c = capture(i, reader(p.handle to "x"), appLabel = null)
        assertNull(c.context.appLabel)
    }

    @Test
    fun `应用名读到了就原样带过去`() {
        val p = pass()
        val i = info(p)
        val c = capture(i, reader(p.handle to "x"), appLabel = "某某浏览器")
        assertEquals("某某浏览器", c.context.appLabel)
    }

    /* ══════════════════ 七、toString 一个内容都不吐 ══════════════════ */

    @Test
    fun `记账和收获的 toString 里没有任何一格的值`() {
        val u = user(web = "example.com")
        val p = pass(web = "example.com")
        val i = info(u, p)
        val c = capture(i, reader(u.handle to "amy@example.com", p.handle to "hunter2"), "某某")

        for (s in listOf(c.toString(), c.tally.toString(), c.context.toString())) {
            assertFalse(s, s.contains("hunter2"))
            assertFalse(s, s.contains("amy"))
            assertFalse(s, s.contains("example.com"))
            assertFalse(s, s.contains("某某"))
        }
    }

    @Test
    fun `记账的 toString 里那几个数字都在`() {
        val p = pass()
        val i = info(p)
        val s = capture(i, reader(p.handle to "x")).tally.toString()
        assertTrue(s, s.contains("watched=1"))
        assertTrue(s, s.contains("kept=1"))
    }

    /* ══════════════════ 八、同一份输入算两遍，结果一样 ══════════════════ */

    @Test
    fun `算两遍一模一样`() {
        val u = user()
        val p = pass()
        val i = info(u, p)
        val r = reader(u.handle to "amy", p.handle to "hunter2")
        val a = capture(i, r)
        val b = capture(i, r)

        assertEquals(a.context.values.map { it.what }, b.context.values.map { it.what })
        assertEquals(a.context.username, b.context.username)
        assertEquals(a.tally.toString(), b.tally.toString())
    }

    private companion object {
        const val OWN = "cn.localvault.app"
        const val HOST = "com.android.chrome"
    }
}
