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

import cn.localvault.app.ui.autofill.PublicSuffix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公共后缀表与包名判定。
 *
 * 这一整个文件钉的都是同一句话：**哪两个主机名算同一个站。**
 * 判宽了会把 A 的密码建议给 B，而这件事在真机上几乎不可能试出来——
 * 谁也不会为了验证一条规则，真去注册两个 `co.uk` 的域名。
 */
class PublicSuffixTest {

    /* ══════════════════════════ 后缀与可注册域 ══════════════════════════ */

    @Test
    fun `普通两段域名`() {
        assertEquals("com", PublicSuffix.publicSuffixOf("example.com"))
        assertEquals("example.com", PublicSuffix.registrableDomain("example.com"))
        assertEquals("qq.com", PublicSuffix.registrableDomain("mail.qq.com"))
        assertEquals("qq.com", PublicSuffix.registrableDomain("a.b.c.qq.com"))
    }

    @Test
    fun `没写进表里的顶级域照样算得对`() {
        // 默认规则：任何未列出的单段后缀，它自己就是公共后缀。
        // 这一条覆盖掉整个 gTLD 世界，包括明年才会出现的那些。
        assertEquals("zip", PublicSuffix.publicSuffixOf("example.zip"))
        assertEquals("example.zip", PublicSuffix.registrableDomain("login.example.zip"))
    }

    @Test
    fun `多段后缀`() {
        assertEquals("co.uk", PublicSuffix.publicSuffixOf("www.bbc.co.uk"))
        assertEquals("bbc.co.uk", PublicSuffix.registrableDomain("www.bbc.co.uk"))
        assertEquals("example.com.cn", PublicSuffix.registrableDomain("shop.example.com.cn"))
        assertEquals("example.co.jp", PublicSuffix.registrableDomain("a.b.example.co.jp"))
    }

    @Test
    fun `中国大陆的省级二级域也是公共后缀`() {
        // 漏掉这一批的表现是 a.bj.cn 和 b.bj.cn 被算成同一家。
        assertEquals("bj.cn", PublicSuffix.publicSuffixOf("x.bj.cn"))
        assertEquals("x.bj.cn", PublicSuffix.registrableDomain("y.x.bj.cn"))
        assertFalse(PublicSuffix.sameSite("a.bj.cn", "b.bj.cn"))
    }

    @Test
    fun `托管平台的子域是陌生人`() {
        // user1 和 user2 是两个互不相识的人，不是同一个站的两个页面。
        assertEquals("github.io", PublicSuffix.publicSuffixOf("user.github.io"))
        assertEquals("user.github.io", PublicSuffix.registrableDomain("user.github.io"))
        assertFalse(PublicSuffix.sameSite("u1.github.io", "u2.github.io"))
        assertFalse(PublicSuffix.sameSite("a.vercel.app", "b.vercel.app"))
    }

    @Test
    fun `后缀本身没有可注册域`() {
        // 「co.uk 的密码」不是一个有意义的东西，它下面挂的是别人。
        assertNull(PublicSuffix.registrableDomain("co.uk"))
        assertNull(PublicSuffix.registrableDomain("com"))
        assertNull(PublicSuffix.registrableDomain("github.io"))
        assertTrue(PublicSuffix.isPublicSuffix("co.uk"))
        assertTrue(PublicSuffix.isPublicSuffix("com"))
        assertFalse(PublicSuffix.isPublicSuffix("example.com"))
    }

    @Test
    fun `单段主机名没有兄弟`() {
        assertNull(PublicSuffix.registrableDomain("localhost"))
        assertFalse(PublicSuffix.sameSite("localhost", "localhost"))
    }

    @Test
    fun `IP 字面量永远没有兄弟`() {
        // 192.168.1.7 和 192.168.1.8 是两台机器，数字上再像也不能沾边。
        assertNull(PublicSuffix.registrableDomain("192.168.1.7"))
        assertNull(PublicSuffix.publicSuffixOf("192.168.1.7"))
        assertFalse(PublicSuffix.sameSite("192.168.1.7", "192.168.1.8"))
        assertFalse(PublicSuffix.sameSite("192.168.1.7", "192.168.1.7"))
        assertTrue(PublicSuffix.isIpLiteral("10.0.0.1"))
        assertTrue(PublicSuffix.isIpLiteral("[::1]"))
        assertFalse(PublicSuffix.isIpLiteral("1.2.3.999"))
        assertFalse(PublicSuffix.isIpLiteral("example.com"))
    }

    @Test
    fun `畸形输入一律算不出来`() {
        assertNull(PublicSuffix.registrableDomain(""))
        assertNull(PublicSuffix.registrableDomain("   "))
        assertNull(PublicSuffix.registrableDomain("a..b"))
        assertFalse(PublicSuffix.sameSite("", ""))
    }

    @Test
    fun `首尾的点和大小写不影响结果`() {
        assertEquals("example.com", PublicSuffix.registrableDomain("WWW.Example.COM."))
        assertTrue(PublicSuffix.sameSite("Mail.Example.com", "example.com."))
    }

    /* ══════════════════════════ 通配与例外 ══════════════════════════ */

    @Test
    fun `通配后缀只吃一段`() {
        // *.ck ：foo.ck 本身是公共后缀，bar.foo.ck 才是可注册域。
        assertEquals("foo.ck", PublicSuffix.publicSuffixOf("foo.ck"))
        assertNull(PublicSuffix.registrableDomain("foo.ck"))
        assertEquals("bar.foo.ck", PublicSuffix.registrableDomain("bar.foo.ck"))
    }

    @Test
    fun `例外规则压过通配规则`() {
        // !www.ck ：从通配里挖回来的那一段。
        assertEquals("ck", PublicSuffix.publicSuffixOf("www.ck"))
        assertEquals("www.ck", PublicSuffix.registrableDomain("www.ck"))
        assertEquals("www.ck", PublicSuffix.registrableDomain("a.www.ck"))
    }

    /* ══════════════════════════ 表不全时的兜底 ══════════════════════════ */

    @Test
    fun `未知国家码顶级域下的注册局二级域也当公共后缀`() {
        // 表里没有 co.zz，靠 REGISTRY_LIKE 兜底。偏向是「切得更碎」：
        // 切碎只会少给一条建议，切粗才会把密码递给别人。
        assertEquals("co.zz", PublicSuffix.publicSuffixOf("a.co.zz"))
        assertEquals("a.co.zz", PublicSuffix.registrableDomain("b.a.co.zz"))
        assertFalse(PublicSuffix.sameSite("a.co.zz", "b.co.zz"))
    }

    @Test
    fun `兜底不误伤三字母以上的顶级域`() {
        // 两字母才是国家码。go.com 里的 com 是 gTLD，不该触发兜底，
        // 否则 disney.go.com 这种正常子域会被切碎。
        assertEquals("com", PublicSuffix.publicSuffixOf("a.go.com"))
        assertEquals("go.com", PublicSuffix.registrableDomain("a.go.com"))
        assertTrue(PublicSuffix.sameSite("a.go.com", "b.go.com"))
    }

    @Test
    fun `兜底不误伤普通的二级域`() {
        // corp 不在注册局惯用名单里，所以 login.corp.io 和 corp.io 仍是同一家。
        assertTrue(PublicSuffix.sameSite("login.corp.io", "corp.io"))
    }

    /* ══════════════════════════ sameSite ══════════════════════════ */

    @Test
    fun `同一个可注册域下的子域互为兄弟`() {
        assertTrue(PublicSuffix.sameSite("mail.example.com", "login.example.com"))
        assertTrue(PublicSuffix.sameSite("example.com", "www.example.com"))
        assertTrue(PublicSuffix.sameSite("a.b.c.example.co.uk", "example.co.uk"))
    }

    @Test
    fun `注册局底下的两个域名不是兄弟`() {
        // 这一条是整张表存在的全部理由。
        assertFalse(PublicSuffix.sameSite("a.co.uk", "b.co.uk"))
        assertFalse(PublicSuffix.sameSite("alpha.com.cn", "beta.com.cn"))
        assertFalse(PublicSuffix.sameSite("example.com", "example.com.cn"))
        assertFalse(PublicSuffix.sameSite("example.com", "example.net"))
    }

    /* ══════════════════════════ 国际化域名 ══════════════════════════ */

    @Test
    fun `中文域名和它的 punycode 形式是同一个站`() {
        val punycode = PublicSuffix.canonicalHost("例子.中国")
        assertTrue(punycode.startsWith("xn--"))
        assertEquals(punycode, PublicSuffix.canonicalHost(punycode))
        assertTrue(PublicSuffix.sameSite("例子.中国", punycode))
    }

    @Test
    fun `转不动的输入原样退回而不是抛出去`() {
        // IDN.toASCII 对某些串会抛 IllegalArgumentException。
        // 那种串本来也匹配不上什么，让它按原样走完流程就好，不能把异常捅到填充服务里。
        // 单个标签超过 63 字节，IDN 规范不接受。这里只要求「不抛出去、给得出东西」，
        // 不去断言具体转成了什么——那是 JDK 的事，钉死它只会让测试跟着 JDK 版本坏掉。
        val tooLong = "例".repeat(200)
        assertTrue(PublicSuffix.canonicalHost(tooLong).isNotEmpty())
        assertNull(PublicSuffix.registrableDomain(tooLong))
    }

    /* ══════════════════════════ 包名还是主机名 ══════════════════════════ */

    @Test
    fun `认得出安卓包名`() {
        assertTrue(PublicSuffix.looksLikePackage("com.tencent.mm"))
        assertTrue(PublicSuffix.looksLikePackage("com.whatsapp"))
        assertTrue(PublicSuffix.looksLikePackage("org.mozilla.firefox"))
        assertTrue(PublicSuffix.looksLikePackage("com.android.chrome"))
        // 首段是两字母国家码的那一大批
        assertTrue(PublicSuffix.looksLikePackage("tv.danmaku.bili"))
        assertTrue(PublicSuffix.looksLikePackage("de.blinkt.openvpn"))
        assertTrue(PublicSuffix.looksLikePackage("io.flutter.demo"))
        assertTrue(PublicSuffix.looksLikePackage("me.zhanghai.android.files"))
    }

    @Test
    fun `两头都像顶级域时按段数断`() {
        // com.tencent.mm 的 mm 是缅甸国家码，光看末段分不出来。
        assertTrue(PublicSuffix.looksLikePackage("com.tencent.mm"))
        // 两段的一律当主机名——真实世界里 com.cn / cn.com / co.uk 都是域名。
        assertFalse(PublicSuffix.looksLikePackage("com.cn"))
        assertFalse(PublicSuffix.looksLikePackage("cn.com"))
        assertFalse(PublicSuffix.looksLikePackage("co.uk"))
    }

    @Test
    fun `认得出主机名`() {
        assertFalse(PublicSuffix.looksLikePackage("mail.google.com"))
        assertFalse(PublicSuffix.looksLikePackage("example.com"))
        assertFalse(PublicSuffix.looksLikePackage("www.gov.uk"))
        assertFalse(PublicSuffix.looksLikePackage("weibo.cn"))
    }

    @Test
    fun `数字开头的段不可能是包名`() {
        // 163.com 是这条规则的主要服务对象：安卓包名的每一段都必须以字母开头。
        assertFalse(PublicSuffix.looksLikePackage("163.com"))
        assertFalse(PublicSuffix.looksLikePackage("360.cn"))
    }

    @Test
    fun `拿不准一律判主机名`() {
        // 这是保守的那一边：判错成主机名最多是填不进去，
        // 判错成包名就可能在原生应用里填出不该填的东西。
        assertFalse(PublicSuffix.looksLikePackage("a.b"))
        assertFalse(PublicSuffix.looksLikePackage("localhost"))
        assertFalse(PublicSuffix.looksLikePackage(""))
        assertFalse(PublicSuffix.looksLikePackage("192.168.1.7"))
        assertFalse(PublicSuffix.looksLikePackage("xn--fsqu00a.xn--fiqs8s"))
        // 带连字符的段不合包名规范
        assertFalse(PublicSuffix.looksLikePackage("com.example-app"))
        assertFalse(PublicSuffix.looksLikePackage("my-site.example.foo"))
    }
}
