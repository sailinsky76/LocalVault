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

import cn.localvault.app.ui.autofill.DomainMatch
import cn.localvault.app.ui.autofill.DomainMatch.Verdict
import cn.localvault.app.ui.autofill.HostTrust
import cn.localvault.app.ui.autofill.KnownBrowsers
import cn.localvault.app.ui.autofill.Origin
import cn.localvault.app.ui.autofill.PublicSuffix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归属判定。
 *
 * 这里钉的是「这一条能不能填给这一组输入框」，全工程错误代价最大的一个函数：
 * 判宽了，用户点一下就把密码发给了别人，事后一点痕迹都没有。
 *
 * 尤其是 AutoSpill 那一条（`一个假冒的登录页装在自己的 WebView 里`），
 * 在真机上要复现得先写一个恶意应用——写得出来也不该留在仓库里。
 * 所以它只能在这儿钉。
 */
class DomainMatchTest {

    private val chromeOnly: HostTrust = object : HostTrust {
        override fun isTrustedBrowser(packageName: String): Boolean =
            packageName == "com.android.chrome"
    }

    private val trustNobody: HostTrust = object : HostTrust {
        override fun isTrustedBrowser(packageName: String): Boolean = false
    }

    private fun web(host: String, app: String = "com.android.chrome") = Origin.Web(host, app)

    /* ══════════════════════════ 网页 ↔ 网址 ══════════════════════════ */

    @Test
    fun `同一个主机名是精确档`() {
        assertEquals(
            Verdict.Exact,
            DomainMatch.judge(web("login.example.com"), "login.example.com", chromeOnly),
        )
    }

    @Test
    fun `用户粘进来的整条网址照样对得上`() {
        // 决策(56)：网址只丢不改写，库里存的就是用户当初粘的那一长串。
        // 归一在匹配这一层做，用的是 VaultIndex.normalizeDomain 那一份，不另写。
        assertEquals(
            Verdict.Exact,
            DomainMatch.judge(
                web("example.com"),
                "HTTPS://user:pw@Example.com:8443/login?next=%2F",
                chromeOnly,
            ),
        )
    }

    @Test
    fun `同一个可注册域下的不同子域是兄弟档`() {
        assertEquals(
            Verdict.SameSite,
            DomainMatch.judge(web("login.example.com"), "mail.example.com", chromeOnly),
        )
        // 兄弟档也会被自动建议，但界面上必须和精确档分开显示。
        assertTrue(Verdict.SameSite.canAutoFill)
        assertTrue(Verdict.Exact.canAutoFill)
    }

    @Test
    fun `注册局底下的两个域名不相干`() {
        assertEquals(Verdict.None, DomainMatch.judge(web("a.co.uk"), "b.co.uk", chromeOnly))
        assertEquals(Verdict.None, DomainMatch.judge(web("u1.github.io"), "u2.github.io", chromeOnly))
        assertEquals(Verdict.None, DomainMatch.judge(web("example.com"), "example.net", chromeOnly))
    }

    @Test
    fun `中文域名和 punycode 是精确档而不是兄弟档`() {
        // 浏览器交上来的一定是 punycode，用户手打的多半是中文。
        // 这两者判成兄弟档不是「差不多」——界面会把同一个域名说成两个。
        val punycode = PublicSuffix.canonicalHost("例子.中国")
        assertEquals(Verdict.Exact, DomainMatch.judge(web(punycode), "例子.中国", chromeOnly))
    }

    /* ══════════════════════════ AutoSpill ══════════════════════════ */

    @Test
    fun `网站对得上但宿主不是浏览器时不自动建议`() {
        // 这就是 AutoSpill：恶意应用套一个 WebView 显示假的登录页，
        // 那些框确实带着真实的 webDomain，因为它们真的是那个网页里的框。
        val v = DomainMatch.judge(
            Origin.Web("login.example.com", "com.evil.wallpapers"),
            "login.example.com",
            chromeOnly,
        )
        assertEquals(Verdict.UntrustedHost, v)
        assertFalse(v.canAutoFill)
        assertTrue(v.needsWarning)
    }

    @Test
    fun `宿主不可信也不会把不相干的条目说成可疑`() {
        // 顺序要紧：先问「是不是同一个站」，再问「谁在承载它」。
        // 反过来的话，满库不相干的条目都会顶着 UntrustedHost 冒出来，
        // 而它们真正的原因只是「压根不是同一个站」。
        assertEquals(
            Verdict.None,
            DomainMatch.judge(Origin.Web("example.com", "com.evil.wallpapers"), "other.com", trustNobody),
        )
    }

    @Test
    fun `换一份可信名单结论就跟着变`() {
        // HostTrust 是接口不是常量，正是为了 M4-2 能换上「包名 + 签名」那一版。
        val v = DomainMatch.judge(
            Origin.Web("example.com", "com.evil.wallpapers"),
            "example.com",
            object : HostTrust {
                override fun isTrustedBrowser(packageName: String) = true
            },
        )
        assertEquals(Verdict.Exact, v)
    }

    @Test
    fun `内置浏览器表认得出常见浏览器`() {
        assertTrue(KnownBrowsers.isTrustedBrowser("com.android.chrome"))
        assertTrue(KnownBrowsers.isTrustedBrowser("com.tencent.mtt"))
        assertTrue(KnownBrowsers.isTrustedBrowser("org.mozilla.firefox"))
        assertTrue(KnownBrowsers.isTrustedBrowser("  com.android.chrome  "))
        assertFalse(KnownBrowsers.isTrustedBrowser("com.evil.wallpapers"))
        assertFalse(KnownBrowsers.isTrustedBrowser(""))
    }

    /* ══════════════════════════ 原生应用 ↔ 包名 ══════════════════════════ */

    @Test
    fun `包名逐字相等才算精确`() {
        assertEquals(
            Verdict.Exact,
            DomainMatch.judge(Origin.App("com.tencent.mm"), "com.tencent.mm", chromeOnly),
        )
    }

    @Test
    fun `同厂商前缀不算同一家`() {
        // com.tencent.mm 是微信，com.tencent.mobileqq 是 QQ，两个账号体系。
        // 认前缀等于把 com.google.* 底下所有应用当成同一个站，
        // 那和「剥子域名」是同一个错误的两种写法。
        assertEquals(
            Verdict.None,
            DomainMatch.judge(Origin.App("com.tencent.mm"), "com.tencent.mobileqq", chromeOnly),
        )
        assertEquals(
            Verdict.None,
            DomainMatch.judge(Origin.App("com.tencent.mm"), "com.tencent", chromeOnly),
        )
    }

    @Test
    fun `包名大小写不影响匹配`() {
        assertEquals(
            Verdict.Exact,
            DomainMatch.judge(Origin.App("com.Example.App"), "com.example.app", chromeOnly),
        )
    }

    /* ══════════════════════════ 两条交叉的路 ══════════════════════════ */

    @Test
    fun `原生应用配网址条目是没有证据而不是不相干`() {
        // 直觉上「微博 App 就该填 weibo.com 的密码」，但那要查 Digital Asset Links，
        // 而这个 App 从 M0 起连 INTERNET 权限都没声明。
        // 说「不相干」是撒谎，说「填」是没有证据，所以单独一档。
        val v = DomainMatch.judge(Origin.App("com.sina.weibo"), "weibo.com", chromeOnly)
        assertEquals(Verdict.NoEvidence, v)
        assertFalse(v.canAutoFill)
        assertTrue(v.needsWarning)
    }

    @Test
    fun `网页配包名条目绝不自动建议`() {
        // 一个网页拿到「某某应用的密码」，正是把原生凭据骗出去的那条路。
        val v = DomainMatch.judge(web("example.com"), "com.tencent.mm", chromeOnly)
        assertEquals(Verdict.WrongKind, v)
        assertFalse(v.canAutoFill)
        assertTrue(v.needsWarning)
    }

    @Test
    fun `不自动建议不等于不许手动挑`() {
        // 这条界限是想清楚了才画的：禁止手动等于替用户决定他自己那条数据能去哪儿。
        // 自动的那一下用户可能没看清，手动的那一下他一定看清了。
        // 四档非自动的里面，只有 None 不需要在按钮上方说话。
        assertTrue(Verdict.WrongKind.needsWarning)
        assertTrue(Verdict.NoEvidence.needsWarning)
        assertTrue(Verdict.UntrustedHost.needsWarning)
        assertFalse(Verdict.None.needsWarning)
        assertFalse(Verdict.Exact.needsWarning)
        assertFalse(Verdict.SameSite.needsWarning)
    }

    /* ══════════════════════════ 空与畸形 ══════════════════════════ */

    @Test
    fun `空网址与空主机名都判不相干`() {
        assertEquals(Verdict.None, DomainMatch.judge(web("example.com"), "", chromeOnly))
        assertEquals(Verdict.None, DomainMatch.judge(web("example.com"), "   ", chromeOnly))
        assertEquals(Verdict.None, DomainMatch.judge(web(""), "example.com", chromeOnly))
        assertEquals(Verdict.None, DomainMatch.judge(Origin.App("com.a.b"), "", chromeOnly))
    }

    /* ══════════════════════════ 一条多网址 ══════════════════════════ */

    @Test
    fun `多行网址取最好的那一档并带出命中的原文`() {
        val hit = DomainMatch.best(
            web("login.example.com"),
            listOf("other.com", "mail.example.com", "login.example.com"),
            chromeOnly,
        )
        assertEquals(Verdict.Exact, hit.verdict)
        // 带出来的是条目里的原文，不是归一之后的形式——界面要显示用户自己写的那一行。
        assertEquals("login.example.com", hit.matched)
    }

    @Test
    fun `一行都对不上时不带原文`() {
        val hit = DomainMatch.best(web("example.com"), listOf("a.com", "b.com"), chromeOnly)
        assertEquals(Verdict.None, hit.verdict)
        assertNull(hit.matched)
    }

    @Test
    fun `没有网址的条目判不相干`() {
        val hit = DomainMatch.best(web("example.com"), emptyList(), chromeOnly)
        assertEquals(Verdict.None, hit.verdict)
        assertNull(hit.matched)
    }

    @Test
    fun `既存网址又存包名的条目在网页上仍按网址算`() {
        // 这一条很常见：同一个服务的 App 和网站存在一起。
        val hit = DomainMatch.best(
            web("weibo.com"),
            listOf("com.sina.weibo", "weibo.com"),
            chromeOnly,
        )
        assertEquals(Verdict.Exact, hit.verdict)
        assertEquals("weibo.com", hit.matched)
    }

    @Test
    fun `同一条在原生应用里则按包名算`() {
        val hit = DomainMatch.best(
            Origin.App("com.sina.weibo"),
            listOf("weibo.com", "com.sina.weibo"),
            chromeOnly,
        )
        assertEquals(Verdict.Exact, hit.verdict)
        assertEquals("com.sina.weibo", hit.matched)
    }

    /* ══════════════════════════ 不吐内容 ══════════════════════════ */

    @Test
    fun `Hit 的 toString 只报形状`() {
        // 决策(144)。Hit 总是和条目一起被传来传去，顺手打进日志的那一下
        // 会把用户上过哪些站抄进 logcat——那本身就是一份不该外泄的清单。
        val hit = DomainMatch.best(web("example.com"), listOf("example.com"), chromeOnly)
        val s = hit.toString()
        assertFalse(s.contains("example.com"))
        assertTrue(s.contains("Exact"))
    }
}
