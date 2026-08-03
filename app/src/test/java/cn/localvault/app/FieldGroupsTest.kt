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

import cn.localvault.app.ui.autofill.AndroidInput
import cn.localvault.app.ui.autofill.DomainMatch
import cn.localvault.app.ui.autofill.FieldGroups
import cn.localvault.app.ui.autofill.FieldRoles
import cn.localvault.app.ui.autofill.FillContext
import cn.localvault.app.ui.autofill.HostTrust
import cn.localvault.app.ui.autofill.Origin
import cn.localvault.app.ui.autofill.RawField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一屏框怎么切成几个表单，每个表单属于谁」。
 *
 * 这一层在真机上**几乎验证不了**：切错了的表现只是「自动填充没出现」或者
 * 「只填了账号」，而那两种表现和十几种别的原因长得一模一样；
 * 至于归属算错（决策(158)）那一条，要复现得先写一个恶意应用出来，
 * 写得出来也不该留在仓库里。所以全部钉在这儿。
 */
class FieldGroupsTest {

    private var seq = 0L

    /** 造一个框。默认是个认不出角色的普通文本框。 */
    private fun f(
        hint: String? = null,
        web: String? = null,
        id: String? = null,
        inputType: Int = 0,
        visible: Boolean = true,
        focused: Boolean = false,
        important: Int = AndroidInput.IMPORTANT_FOR_AUTOFILL_AUTO,
    ): RawField = RawField(
        handle = seq++,
        autofillHints = if (hint == null) emptyList() else listOf(hint),
        inputType = inputType,
        importantForAutofill = important,
        idEntry = id,
        webDomain = web,
        visible = visible,
        focused = focused,
    )

    private fun user(web: String? = null, focused: Boolean = false) =
        f(hint = "username", web = web, focused = focused)

    private fun pass(web: String? = null, focused: Boolean = false, visible: Boolean = true) =
        f(hint = "password", web = web, focused = focused, visible = visible)

    private fun newPass(web: String? = null) = f(hint = "newPassword", web = web)

    private fun otp(web: String? = null, focused: Boolean = false) =
        f(hint = "smsOTPCode", web = web, focused = focused)

    private fun ctx(app: String, vararg fields: RawField) =
        FillContext(activityPackage = app, fields = fields.toList())

    /**
     * 打开「听应用的 `importantForAutofill=no`」那个设置的上下文。
     *
     * 缺省是**不听**（[FieldRoles.DEFAULT_RESPECT_OPT_OUT] = false，理由见那里的注释：
     * 真机上这个旗子主要被通用登录组件滥用）。所以要考「明说别填的框不进组」，
     * 就得把设置打开——用缺省值考它，考的其实是「默认不听」这条相反的规矩。
     */
    private fun ctxRespectingOptOut(app: String, vararg fields: RawField) =
        FillContext(activityPackage = app, fields = fields.toList(), respectOptOut = true)

    private val nobody: HostTrust = object : HostTrust {
        override fun isTrustedBrowser(packageName: String) = false
    }

    private val chromeOnly: HostTrust = object : HostTrust {
        override fun isTrustedBrowser(packageName: String) = packageName == "com.android.chrome"
    }

    /* ══════════════════════════ 什么都不进组 ══════════════════════════ */

    @Test
    fun `空请求切不出任何一组`() {
        assertTrue(FieldGroups.split(ctx("com.a.b")).isEmpty())
    }

    @Test
    fun `一屏全是认不出角色的框时不产生空组`() {
        val groups = FieldGroups.split(
            ctx("com.a.b", f(id = "search_box"), f(id = "amount"), f(id = "remark"))
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `看不见的密码框不进任何一组`() {
        // 隐藏密码框是个老套路：放一个不可见的框骗管理器填进去，再用脚本读走。
        val groups = FieldGroups.split(ctx("com.a.b", user(), pass(visible = false)))
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].fields.size)
        assertEquals(FieldRoles.Role.Username, groups[0].fields[0].role)
    }

    @Test
    fun `设置打开时，应用明说别填的框不进任何一组`() {
        val groups = FieldGroups.split(
            ctxRespectingOptOut(
                "com.a.b",
                user(),
                pass(),
                f(hint = "password", important = AndroidInput.IMPORTANT_FOR_AUTOFILL_NO),
            )
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].fields.size)
    }

    @Test
    fun `设置默认是关的，明说别填的框照样进组`() {
        // 这才是出厂行为，值得单独钉一条：默认不听那个旗子（决策见
        // FieldRoles.DEFAULT_RESPECT_OPT_OUT）。哪天有人把默认值翻过来，
        // 这条会当场红，而不是让一屏淘宝登录页悄悄地填不出来。
        val groups = FieldGroups.split(
            ctx(
                "com.a.b",
                user(),
                pass(),
                f(hint = "password", important = AndroidInput.IMPORTANT_FOR_AUTOFILL_NO),
            )
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].fields.size)
    }

    @Test
    fun `只剩验证码框的组要保留下来`() {
        // 它是一句要对用户说的话（「这一屏没有能填的东西」），
        // 比一声不响地消失有用。
        val groups = FieldGroups.split(ctx("com.a.b", otp()))
        assertEquals(1, groups.size)
        assertEquals(FieldRoles.Role.Otp, groups[0].fields[0].role)
    }

    /* ══════════════════════════ 归属：决策(158) ══════════════════════════ */

    @Test
    fun `原生框那一组的归属是承载它的应用`() {
        val groups = FieldGroups.split(ctx("com.sina.weibo", user(), pass()))
        assertEquals(1, groups.size)
        assertEquals(Origin.App("com.sina.weibo"), groups[0].origin)
        assertFalse(groups[0].isWeb)
    }

    @Test
    fun `网页框那一组同时带着自称的网站和承载它的应用`() {
        val groups = FieldGroups.split(
            ctx("com.android.chrome", user(web = "example.com"), pass(web = "example.com"))
        )
        assertEquals(1, groups.size)
        assertEquals(Origin.Web("example.com", "com.android.chrome"), groups[0].origin)
        assertTrue(groups[0].isWeb)
    }

    @Test
    fun `hostApp 永远取请求里那个包名，绝不取 webDomain`() {
        // 这是决策(158) 唯一能被写错的地方：一个恶意应用套 WebView 假冒登录页，
        // 那些框如实带着 webDomain = 你的网银。
        val groups = FieldGroups.split(
            ctx("com.evil.wallpapers", user(web = "bank.example.com"), pass(web = "bank.example.com"))
        )
        val origin = groups.single().origin as Origin.Web
        assertEquals("bank.example.com", origin.host)
        assertEquals("com.evil.wallpapers", origin.hostApp)
    }

    @Test
    fun `套了 WebView 的恶意应用一路走到判定这一步会被拦成 UntrustedHost`() {
        // 端到端走一遍：切组 → 归属 → 判定。
        val groups = FieldGroups.split(
            ctx("com.evil.wallpapers", user(web = "bank.example.com"), pass(web = "bank.example.com"))
        )
        val hit = DomainMatch.best(groups.single().origin, listOf("bank.example.com"), nobody)
        assertEquals(DomainMatch.Verdict.UntrustedHost, hit.verdict)
        assertFalse(hit.verdict.canAutoFill)
    }

    @Test
    fun `同一次请求里原生框和网页框各算各的归属`() {
        // 最要紧的一条：原生那一组**绝不继承** webDomain。
        val groups = FieldGroups.split(
            ctx(
                "com.evil.wallpapers",
                user(web = "bank.example.com"),
                pass(web = "bank.example.com"),
                user(),
                pass(),
            )
        )
        assertEquals(2, groups.size)
        assertEquals(Origin.Web("bank.example.com", "com.evil.wallpapers"), groups[0].origin)
        assertEquals(Origin.App("com.evil.wallpapers"), groups[1].origin)
    }

    @Test
    fun `原生那一组配上网址条目只能是没有证据，不会因为同屏有个 WebView 就变成精确`() {
        val groups = FieldGroups.split(
            ctx("com.evil.wallpapers", user(web = "bank.example.com"), pass(web = "bank.example.com"), user(), pass())
        )
        val native = groups.first { !it.isWeb }
        assertEquals(
            DomainMatch.Verdict.NoEvidence,
            DomainMatch.best(native.origin, listOf("bank.example.com"), chromeOnly).verdict,
        )
    }

    @Test
    fun `两个不同的 webDomain 切成两组，不拿第一个代表整屏`() {
        // iframe：主站的登录框 + 第三方支付的框。
        val groups = FieldGroups.split(
            ctx(
                "com.android.chrome",
                user(web = "shop.example.com"),
                pass(web = "shop.example.com"),
                pass(web = "pay.other.com"),
            )
        )
        assertEquals(2, groups.size)
        assertEquals("shop.example.com", (groups[0].origin as Origin.Web).host)
        assertEquals("pay.other.com", (groups[1].origin as Origin.Web).host)
    }

    @Test
    fun `同一个网站的框即使被别的网站的框隔开也不并组`() {
        val groups = FieldGroups.split(
            ctx(
                "com.android.chrome",
                user(web = "a.com"),
                pass(web = "b.com"),
                pass(web = "a.com"),
            )
        )
        assertEquals(2, groups.size)
        assertEquals("a.com", (groups[0].origin as Origin.Web).host)
        assertEquals(2, groups[0].fields.size)
        assertEquals("b.com", (groups[1].origin as Origin.Web).host)
    }

    /* ══════════════════════════ webDomain 归一 ══════════════════════════ */

    @Test
    fun `webDomain 是一整条 URL 时收敛到主机名`() {
        val groups = FieldGroups.split(
            ctx("com.android.chrome", pass(web = "https://mail.example.com/inbox?a=1#x"))
        )
        assertEquals("mail.example.com", (groups.single().origin as Origin.Web).host)
    }

    @Test
    fun `webDomain 带端口和大写时照样归一`() {
        val groups = FieldGroups.split(ctx("com.android.chrome", pass(web = "Example.COM:8443")))
        assertEquals("example.com", (groups.single().origin as Origin.Web).host)
    }

    @Test
    fun `webDomain 是空串的框按原生算`() {
        // 一个说不出自己属于哪个网站的框没有任何「自称」可采信。
        val groups = FieldGroups.split(ctx("com.a.b", user(web = ""), pass(web = "   ")))
        assertEquals(1, groups.size)
        assertEquals(Origin.App("com.a.b"), groups[0].origin)
    }

    @Test
    fun `归一之后同一个主机的两种写法算一组`() {
        val groups = FieldGroups.split(
            ctx("com.android.chrome", user(web = "example.com"), pass(web = "https://example.com/login"))
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].fields.size)
    }

    /* ══════════════════════════ 角色序列切组 ══════════════════════════ */

    @Test
    fun `登录表单和注册表单同屏时切成两组`() {
        val groups = FieldGroups.split(ctx("com.a.b", user(), pass(), user(), pass()))
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].fields.size)
        assertEquals(2, groups[1].fields.size)
    }

    @Test
    fun `又来一个密码框不切组`() {
        // 账号 密码 密码 几乎总是「密码 + 确认密码」，硬切开会得到一个
        // 只有密码框的第二组，看起来像分屏登录的第二屏。
        val groups = FieldGroups.split(ctx("com.a.b", user(), pass(), pass()))
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].fields.size)
    }

    @Test
    fun `连着两个账号框不切组`() {
        val groups = FieldGroups.split(ctx("com.a.b", user(), user(), pass()))
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].fields.size)
    }

    @Test
    fun `新密码框也算密码，之后再来账号框要切组`() {
        val groups = FieldGroups.split(ctx("com.a.b", user(), newPass(), user(), pass()))
        assertEquals(2, groups.size)
        assertEquals(FieldRoles.Role.NewPassword, groups[0].fields[1].role)
    }

    @Test
    fun `验证码框不触发切组`() {
        val groups = FieldGroups.split(ctx("com.a.b", user(), otp(), pass()))
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].fields.size)
    }

    /* ══════════════════════════ 顺序与焦点 ══════════════════════════ */

    @Test
    fun `组内保持原来的顺序`() {
        val u = user()
        val p = pass()
        val groups = FieldGroups.split(ctx("com.a.b", u, p))
        assertEquals(listOf(u.handle, p.handle), groups[0].fields.map { it.handle })
    }

    @Test
    fun `组的先后按每组第一个框出现的顺序`() {
        val groups = FieldGroups.split(
            ctx("com.android.chrome", pass(web = "b.com"), user(), pass(web = "a.com"))
        )
        assertEquals(3, groups.size)
        assertEquals("b.com", (groups[0].origin as Origin.Web).host)
        assertEquals(Origin.App("com.android.chrome"), groups[1].origin)
        assertEquals("a.com", (groups[2].origin as Origin.Web).host)
    }

    @Test
    fun `focused 落在光标真正所在的那一组`() {
        val groups = FieldGroups.split(
            ctx("com.android.chrome", user(web = "a.com"), pass(web = "b.com", focused = true))
        )
        assertEquals(2, groups.size)
        assertFalse(groups[0].focused)
        assertTrue(groups[1].focused)
    }

    @Test
    fun `一个框都没聚焦时所有组的 focused 都是 false`() {
        val groups = FieldGroups.split(ctx("com.a.b", user(), pass()))
        assertFalse(groups[0].focused)
    }

    /* ══════════════════════════ withRole ══════════════════════════ */

    @Test
    fun `withRole 只取那一档且保持顺序`() {
        val g = FieldGroups.split(ctx("com.a.b", user(), otp(), pass(), pass())).single()
        assertEquals(1, g.withRole(FieldRoles.Role.Username).size)
        assertEquals(2, g.withRole(FieldRoles.Role.Password).size)
        assertEquals(1, g.withRole(FieldRoles.Role.Otp).size)
        assertTrue(g.withRole(FieldRoles.Role.NewPassword).isEmpty())
    }

    /* ══════════════════════════ toString 不吐内容 ══════════════════════════ */

    @Test
    fun `Group 的 toString 不吐主机名也不吐包名`() {
        val g = FieldGroups.split(
            ctx("com.evil.wallpapers", user(web = "bank.example.com"), pass(web = "bank.example.com"))
        ).single()
        val s = g.toString()
        assertFalse(s.contains("bank"))
        assertFalse(s.contains("evil"))
        assertTrue(s.contains("2"))
    }

    @Test
    fun `Field 的 toString 只有句柄和角色`() {
        val g = FieldGroups.split(ctx("com.a.b", f(hint = "username", id = "et_login_account"))).single()
        val s = g.fields[0].toString()
        assertFalse(s.contains("et_login_account"))
        assertTrue(s.contains("Username"))
    }

    @Test
    fun `Origin 的 toString 不吐主机名`() {
        assertFalse(Origin.Web("bank.example.com", "com.evil.wallpapers").toString().contains("bank"))
        assertFalse(Origin.App("com.evil.wallpapers").toString().contains("evil"))
        // equals 照旧，判定逻辑一点没变。
        assertEquals(Origin.App("com.a.b"), Origin.App("com.a.b"))
        assertNotNull(Origin.Web("a.com", "com.a.b").host)
    }
}
