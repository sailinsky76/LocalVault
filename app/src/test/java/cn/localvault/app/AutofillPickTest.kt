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
import cn.localvault.app.ui.autofill.AutofillOffer
import cn.localvault.app.ui.autofill.AutofillPick
import cn.localvault.app.ui.autofill.BrowserTrust
import cn.localvault.app.ui.autofill.DomainMatch
import cn.localvault.app.ui.autofill.FillContext
import cn.localvault.app.ui.autofill.FillPlan
import cn.localvault.app.ui.autofill.HostTrust
import cn.localvault.app.ui.autofill.RawField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用户自己挑那一条时，屏幕上摆什么、最后往哪几个框写」。
 *
 * 这一层和 `AutofillOffer` 是一对：那边守的是**自动**那一下，
 * 靠的是「同屏每一组框各判一次归属」；这一页上归属那道闸门被用户**主动越过了**，
 * 所以它必须换一道闸门而不是把闸门拆掉——**只往主表单那一组写**。
 * 那一条如果写成「照 `plan.forms` 全写一遍」，前面八个内核文件守住的东西
 * 会在这一行上全部漏光，而真机上看不见：页面照样弹出来，用户点一下，一切正常。
 * 「只写主表单那一组」那几条用例（第五节）就是钉这个的。
 */
class AutofillPickTest {

    private var seq = 0L

    private fun f(hint: String? = null, web: String? = null, focused: Boolean = false): RawField =
        RawField(
            handle = seq++,
            autofillHints = if (hint == null) emptyList() else listOf(hint),
            webDomain = web,
            focused = focused,
        )

    private fun user(web: String? = null, focused: Boolean = false) = f("username", web, focused)
    private fun pass(web: String? = null, focused: Boolean = false) = f("password", web, focused)
    private fun newPass(web: String? = null) = f("newPassword", web)
    private fun otp(web: String? = null) = f("smsOTPCode", web)

    private fun plan(app: String, vararg fields: RawField): FillPlan.Plan =
        FillPlan.forRequest(FillContext(activityPackage = app, fields = fields.toList()))

    /** 一屏最普通的网页登录框，浏览器承载。 */
    private fun webLogin(host: String = "example.com", app: String = CHROME) =
        plan(app, user(host), pass(host))

    /** 一屏原生登录框。 */
    private fun appLogin(app: String = "com.tencent.mm") = plan(app, user(), pass())

    private fun entry(
        id: String = "id-1",
        name: String = "示例站",
        username: String = "zhangsan",
        password: String = PASSWORD,
        domains: List<String> = listOf("example.com"),
        favorite: Boolean = false,
        updatedAt: Long = 0L,
        notes: String = "",
    ) = VaultEntry(
        id = id,
        name = name,
        username = username,
        password = password,
        domains = domains,
        favorite = favorite,
        updatedAt = updatedAt,
        notes = notes,
    )

    private val trust: HostTrust = object : HostTrust {
        override fun isTrustedBrowser(packageName: String) = packageName == CHROME
    }

    private fun row(e: VaultEntry, p: FillPlan.Plan = webLogin()) = AutofillPick.row(e, p, trust)

    private fun choose(
        e: VaultEntry,
        p: FillPlan.Plan = webLogin(),
        label: String? = "Chrome",
        level: BrowserTrust.Level? = null,
    ) = AutofillPick.choose(e, p, trust, label, level)

    /* ══════════════ 一、整页该不该出现 ══════════════ */

    @Test
    fun `一屏正常的登录框，这一页照常出现`() {
        assertNull(AutofillPick.refusal(webLogin(), SELF))
    }

    @Test
    fun `没有能填的框时整页不出现`() {
        assertEquals(
            AutofillPick.REFUSE_NO_FORM,
            AutofillPick.refusal(plan(CHROME, otp("example.com")), SELF),
        )
    }

    @Test
    fun `不往本应用自己的界面上填`() {
        assertEquals(AutofillPick.REFUSE_OWN_UI, AutofillPick.refusal(appLogin(SELF), SELF))
    }

    @Test
    fun `既是自己的界面又没有可填的框时，先报没有框`() {
        // 顺序和 AutofillOffer.respond 一致：这一问不需要知道任何别的事
        assertEquals(
            AutofillPick.REFUSE_NO_FORM,
            AutofillPick.refusal(plan(SELF, otp()), SELF),
        )
    }

    /* ══════════════ 二、一行 ══════════════ */

    @Test
    fun `第一行是条目名称`() {
        assertEquals("示例站", row(entry()).label)
    }

    @Test
    fun `没有名称的条目退回账号`() {
        assertEquals("zhangsan", row(entry(name = "")).label)
    }

    @Test
    fun `名称和账号都没有时也不显示空白`() {
        val r = row(entry(name = "", username = ""))
        assertEquals(AutofillOffer.NO_NAME, r.label)
        assertEquals(AutofillOffer.NO_USERNAME, r.sublabel)
    }

    @Test
    fun `名称里的换行被压成一行`() {
        assertEquals("上 下", row(entry(name = "上\n下")).label)
    }

    @Test
    fun `名称里的双向控制符被剔掉`() {
        val r = row(entry(name = "bank\u202Emoc.knab"))
        assertFalse(r.label.contains('\u202E'))
    }

    @Test
    fun `超长的名称被截断`() {
        val r = row(entry(name = "字".repeat(200)))
        assertTrue(r.label.length <= AutofillPick.MAX_LABEL + 1)
        assertTrue(r.label.endsWith("…"))
    }

    @Test
    fun `逐字对上的是精确档，而且带出命中的那一行原文`() {
        val r = row(entry(domains = listOf("https://example.com/login")))
        assertEquals(DomainMatch.Verdict.Exact, r.verdict)
        assertEquals("https://example.com/login", r.matchedDomain)
        assertTrue(r.auto)
        assertFalse(r.needsWarning)
    }

    @Test
    fun `同一个可注册域下的不同子域是兄弟档`() {
        val r = row(entry(domains = listOf("mail.example.com")), webLogin("login.example.com"))
        assertEquals(DomainMatch.Verdict.SameSite, r.verdict)
        assertTrue(r.auto)
    }

    @Test
    fun `网址对得上但承载它的不是浏览器`() {
        val r = row(entry(), webLogin(app = "com.evil.wallpaper"))
        assertEquals(DomainMatch.Verdict.UntrustedHost, r.verdict)
        assertFalse(r.auto)
        assertTrue(r.needsWarning)
    }

    @Test
    fun `原生框配网址条目是没有证据`() {
        val r = row(entry(), appLogin())
        assertEquals(DomainMatch.Verdict.NoEvidence, r.verdict)
        assertTrue(r.needsWarning)
    }

    @Test
    fun `网页框配包名条目是类型对不上`() {
        val r = row(entry(domains = listOf("com.tencent.mm")))
        assertEquals(DomainMatch.Verdict.WrongKind, r.verdict)
        assertTrue(r.needsWarning)
    }

    @Test
    fun `一行网址都没存的条目不算存在别处`() {
        val r = row(entry(domains = emptyList()))
        assertEquals(DomainMatch.Verdict.None, r.verdict)
        assertFalse(r.storedElsewhere)
        assertFalse(r.needsWarning)
        assertNull(r.matchedDomain)
    }

    @Test
    fun `存了别的站的条目要被标出来`() {
        val r = row(entry(domains = listOf("other.example.net")))
        assertEquals(DomainMatch.Verdict.None, r.verdict)
        assertTrue(r.storedElsewhere)
        assertTrue(r.needsWarning)
    }

    @Test
    fun `网址那一行只剩空白时，和一行都没存是同一件事`() {
        val r = row(entry(domains = listOf("   ", "")))
        assertFalse(r.storedElsewhere)
    }

    @Test
    fun `账号密码都空的条目在这一屏上填不出东西`() {
        assertFalse(row(entry(username = "", password = "")).fillable)
    }

    @Test
    fun `只有账号的条目在登录屏上填得出东西`() {
        assertTrue(row(entry(password = "")).fillable)
    }

    @Test
    fun `只有密码的条目在只有账号框的那一屏上填不出东西`() {
        val onlyUser = plan(CHROME, user("example.com"))
        assertFalse(row(entry(username = "", password = PASSWORD), onlyUser).fillable)
    }

    @Test
    fun `一行的 toString 只报形状`() {
        val s = row(entry(name = "示例站")).toString()
        assertFalse(s.contains(PASSWORD))
        assertFalse(s.contains("示例站"))
        assertFalse(s.contains("zhangsan"))
    }

    /* ══════════════ 三、默认清单 ══════════════ */

    @Test
    fun `默认清单第一段只收够格自动填的那两档`() {
        val list = AutofillPick.listing(
            webLogin(),
            listOf(
                entry(id = "a", domains = listOf("example.com")),
                entry(id = "b", domains = listOf("sub.example.com")),
                entry(id = "c", domains = listOf("other.net")),
                entry(id = "d", domains = listOf("com.tencent.mm")),
                entry(id = "e", domains = emptyList()),
            ),
            trust,
        )
        assertEquals(listOf("a", "b"), list.forThisSite.map { it.entryId })
        assertTrue(list.forThisSite.all { it.auto })
    }

    @Test
    fun `第一段不套用填充条那个 8 条上限`() {
        val many = (1..12).map { entry(id = "id-$it", name = "站 $it") }
        val list = AutofillPick.listing(webLogin(), many, trust)
        assertEquals(12, list.forThisSite.size)
    }

    @Test
    fun `第一段的顺序和填充条上一样`() {
        val list = AutofillPick.listing(
            webLogin(),
            listOf(
                entry(id = "sib", domains = listOf("mail.example.com")),
                entry(id = "plain", updatedAt = 1L),
                entry(id = "fav", favorite = true),
            ),
            trust,
        )
        // 精确档整体压过兄弟档；精确档内部收藏在前
        assertEquals(listOf("fav", "plain", "sib"), list.forThisSite.map { it.entryId })
    }

    @Test
    fun `最近改动那一段不重复摆已经在上面的条目`() {
        val list = AutofillPick.listing(
            webLogin(),
            listOf(entry(id = "a"), entry(id = "z", domains = emptyList())),
            trust,
        )
        assertEquals(listOf("a"), list.forThisSite.map { it.entryId })
        assertEquals(listOf("z"), list.recent.map { it.entryId })
    }

    @Test
    fun `最近改动那一段有上限，而且如实说自己没摆全`() {
        val many = (1..40).map { entry(id = "id-$it", name = "杂 $it", domains = emptyList()) }
        val list = AutofillPick.listing(webLogin(), many, trust)
        assertEquals(AutofillPick.RECENT_LIMIT, list.recent.size)
        assertTrue(list.partial)
    }

    @Test
    fun `摆全了就不说那句「没摆全」`() {
        val list = AutofillPick.listing(webLogin(), listOf(entry()), trust)
        assertFalse(list.partial)
    }

    @Test
    fun `库是空的时候说一句实话，两段都空`() {
        val list = AutofillPick.listing(webLogin(), emptyList(), trust)
        assertEquals(AutofillPick.EMPTY_VAULT, list.note)
        assertTrue(list.isEmpty)
    }

    @Test
    fun `这个站一条都没对上时说那一句，但最近改动照样摆`() {
        val list = AutofillPick.listing(
            webLogin(),
            listOf(entry(domains = listOf("other.net"))),
            trust,
        )
        assertEquals(AutofillPick.NO_MATCH, list.note)
        assertEquals(1, list.recent.size)
    }

    @Test
    fun `一切正常时一句废话都不说`() {
        assertNull(AutofillPick.listing(webLogin(), listOf(entry()), trust).note)
    }

    @Test
    fun `这一屏没有可填的框时，第一段是空的而不是抛异常`() {
        val list = AutofillPick.listing(plan(CHROME, otp("example.com")), listOf(entry()), trust)
        assertTrue(list.forThisSite.isEmpty())
        assertEquals(1, list.recent.size)
    }

    /* ══════════════ 四、搜索 ══════════════ */

    @Test
    fun `搜索的顺序按关键词，不拿归属重排`() {
        val hits = AutofillPick.search(
            webLogin(),
            listOf(
                entry(id = "auto", name = "我的银行卡", domains = listOf("example.com")),
                entry(id = "typed", name = "银行", domains = emptyList()),
            ),
            "银行",
            trust,
        )
        assertEquals(listOf("typed", "auto"), hits.map { it.entryId })
        assertTrue(hits[1].auto)
        assertFalse(hits[0].auto)
    }

    @Test
    fun `搜索一律不过滤，够不上档的照样搜得到`() {
        val hits = AutofillPick.search(
            webLogin(),
            listOf(entry(id = "wrong", name = "微信", domains = listOf("com.tencent.mm"))),
            "微信",
            trust,
        )
        assertEquals(1, hits.size)
        assertEquals(DomainMatch.Verdict.WrongKind, hits[0].verdict)
    }

    @Test
    fun `空关键词不摆搜索结果`() {
        assertTrue(AutofillPick.search(webLogin(), listOf(entry()), "   ", trust).isEmpty())
    }

    @Test
    fun `备注搜不到——白名单是从列表页那边继承的，不在这儿另开一份`() {
        val hits = AutofillPick.search(
            webLogin(),
            listOf(entry(notes = "密保答案是我妈的名字")),
            "密保答案",
            trust,
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `密码搜不到`() {
        assertTrue(AutofillPick.search(webLogin(), listOf(entry()), PASSWORD, trust).isEmpty())
    }

    @Test
    fun `搜索结果有上限`() {
        val many = (1..80).map { entry(id = "id-$it", name = "站点 $it") }
        assertEquals(
            AutofillPick.MAX_RESULTS,
            AutofillPick.search(webLogin(), many, "站点", trust).size,
        )
    }

    /* ══════════════ 五、只往主表单那一组写 ══════════════ */

    @Test
    fun `同屏另一组框，手动挑也一个字都不写`() {
        // 一组是 example.com 的网页框（光标在这儿），另一组是承载应用自己的原生框。
        // 用户看见的、点头的是前一组；后一组他没看见，也没同意。
        val p = plan(
            "com.evil.wallpaper",
            user("example.com", focused = true),
            pass("example.com"),
            user(),
            pass(),
        )
        val webHandles = setOf(0L, 1L)
        val writes = AutofillPick.writes(p, entry(domains = emptyList()))
        assertEquals(2, writes.size)
        assertTrue(writes.all { it.handle in webHandles })
    }

    @Test
    fun `归属对不上的条目照样写得出来——手动挑的全部意义就在这儿`() {
        val writes = AutofillPick.writes(webLogin(), entry(domains = listOf("com.tencent.mm")))
        assertEquals(2, writes.size)
    }

    @Test
    fun `新密码那一栏，手动挑也不填`() {
        val p = plan(CHROME, user("example.com"), newPass("example.com"))
        val writes = AutofillPick.writes(p, entry())
        assertEquals(listOf(FillPlan.Slot.Username), writes.map { it.slot })
    }

    @Test
    fun `分不出新旧的两个密码框，手动挑也一个都不填`() {
        val p = plan(CHROME, user("example.com"), pass("example.com"), pass("example.com"))
        assertEquals(
            listOf(FillPlan.Slot.Username),
            AutofillPick.writes(p, entry()).map { it.slot },
        )
    }

    @Test
    fun `值是空的那一格不写`() {
        val writes = AutofillPick.writes(webLogin(), entry(password = ""))
        assertEquals(listOf(FillPlan.Slot.Username), writes.map { it.slot })
    }

    @Test
    fun `没有主表单时一个字都不写`() {
        assertTrue(AutofillPick.writes(plan(CHROME, otp("example.com")), entry()).isEmpty())
    }

    /* ══════════════ 六、确认那一屏 ══════════════ */

    @Test
    fun `够格自动填的那两档，一句警告都不摆`() {
        assertTrue(choose(entry()).warnings.isEmpty())
        assertTrue(
            choose(entry(domains = listOf("mail.example.com")), webLogin("login.example.com"))
                .warnings.isEmpty(),
        )
    }

    @Test
    fun `兄弟域那一句要把两个域名都写出来`() {
        val c = choose(entry(domains = listOf("mail.example.com")), webLogin("login.example.com"))
        val note = c.notes.first { it.contains("mail.example.com") }
        assertTrue(note.contains("login.example.com"))
    }

    @Test
    fun `不是浏览器承载时摆一句，而且不摆两遍`() {
        val c = choose(
            entry(),
            webLogin(app = "com.evil.wallpaper"),
            level = BrowserTrust.Level.Unknown,
        )
        assertEquals(1, c.warnings.size)
        assertEquals(AutofillPick.warning(DomainMatch.Verdict.UntrustedHost), c.warnings[0])
    }

    @Test
    fun `原生框配网址条目摆没有证据那一句`() {
        val c = choose(entry(), appLogin())
        assertEquals(listOf(AutofillPick.warning(DomainMatch.Verdict.NoEvidence)), c.warnings)
    }

    @Test
    fun `网页框配包名条目摆类型对不上那一句`() {
        val c = choose(entry(domains = listOf("com.tencent.mm")))
        assertEquals(listOf(AutofillPick.warning(DomainMatch.Verdict.WrongKind)), c.warnings)
    }

    @Test
    fun `存了别的站的条目摆第四句`() {
        val c = choose(entry(domains = listOf("other.net")))
        assertEquals(listOf(AutofillPick.STORED_ELSEWHERE), c.warnings)
    }

    @Test
    fun `归属没话说而浏览器不认识时，浏览器那一句补上来`() {
        val c = choose(
            entry(domains = emptyList()),
            webLogin(app = "com.evil.wallpaper"),
            level = BrowserTrust.Level.Unknown,
        )
        assertEquals(listOf(BrowserTrust.note(BrowserTrust.Level.Unknown)), c.warnings)
    }

    @Test
    fun `只核对了包名 和 已核验 那两句是陈述句，不进警告`() {
        for (level in listOf(BrowserTrust.Level.PackageOnly, BrowserTrust.Level.Verified)) {
            val c = choose(entry(), level = level)
            assertTrue(c.warnings.isEmpty())
            assertTrue(c.notes.contains(BrowserTrust.note(level)))
        }
    }

    @Test
    fun `要设新密码的那一屏先说一句`() {
        val p = plan(CHROME, user("example.com"), newPass("example.com"))
        val c = choose(entry(), p)
        assertTrue(c.notes.contains(FillPlan.kindNote(FillPlan.Kind.NewCredential)))
    }

    @Test
    fun `一切照常的那一屏，陈述句也一句不摆`() {
        assertTrue(choose(entry()).notes.isEmpty())
    }

    @Test
    fun `会写哪几格如实报出来`() {
        assertEquals(
            listOf(FillPlan.Slot.Username, FillPlan.Slot.Password),
            choose(entry()).slots,
        )
        assertEquals(listOf(FillPlan.Slot.Username), choose(entry(password = "")).slots)
    }

    @Test
    fun `什么都填不出来的那一条不给按`() {
        val c = choose(entry(username = "", password = ""))
        assertFalse(c.canFill)
        assertEquals(AutofillPick.BLOCKED_NOTHING_TO_FILL, c.blocked)
        assertTrue(c.slots.isEmpty())
    }

    @Test
    fun `没有主表单时不给按`() {
        val c = choose(entry(), plan(CHROME, otp("example.com")))
        assertFalse(c.canFill)
        assertEquals(AutofillPick.REFUSE_NO_FORM, c.blocked)
    }

    @Test
    fun `会交给谁那一行永远在`() {
        assertTrue(choose(entry()).handOver.isNotEmpty())
        assertTrue(choose(entry(), appLogin()).handOver.isNotEmpty())
    }

    @Test
    fun `确认屏上没有一处出现密码`() {
        val c = choose(entry(), level = BrowserTrust.Level.PackageOnly)
        val everything = buildString {
            append(c.handOver).append(c.blocked).append(c.toString())
            append(c.row.label).append(c.row.sublabel).append(c.row.matchedDomain)
            c.warnings.forEach { append(it) }
            c.notes.forEach { append(it) }
        }
        assertFalse(everything.contains(PASSWORD))
    }

    /* ══════════════ 七、会交给谁 ══════════════ */

    @Test
    fun `网页那一行同时写应用名、包名和页面主机名`() {
        val line = AutofillPick.handOver(
            org(webLogin(app = "com.evil.wallpaper")),
            "Chrome 浏览器",
        )
        assertTrue(line.contains("Chrome 浏览器"))
        assertTrue(line.contains("com.evil.wallpaper"))
        assertTrue(line.contains("example.com"))
    }

    @Test
    fun `原生那一行写应用名和包名，不提页面`() {
        val line = AutofillPick.handOver(org(appLogin("com.tencent.mm")), "微信")
        assertTrue(line.contains("微信"))
        assertTrue(line.contains("com.tencent.mm"))
        assertFalse(line.contains("页面"))
    }

    @Test
    fun `读不到应用名时只写包名，不说「未知应用」`() {
        val line = AutofillPick.handOver(org(appLogin("com.tencent.mm")), null)
        assertTrue(line.contains("com.tencent.mm"))
        assertFalse(line.contains("未知"))
    }

    @Test
    fun `应用名是那个应用自己声明的，双向控制符要剔掉`() {
        val line = AutofillPick.handOver(org(appLogin("com.evil.app")), "Chrome\u202E")
        assertFalse(line.contains('\u202E'))
        // 包名照样在，名字骗得了人，包名骗不了
        assertTrue(line.contains("com.evil.app"))
    }

    @Test
    fun `应用名里的换行被压成一行`() {
        val line = AutofillPick.handOver(org(appLogin("com.evil.app")), "上\n下")
        assertTrue(line.contains("上 下"))
    }

    @Test
    fun `超长的应用名被截断`() {
        val line = AutofillPick.handOver(org(appLogin("com.evil.app")), "长".repeat(300))
        assertTrue(line.length < 300)
    }

    @Test
    fun `全是空白的应用名当作没有`() {
        val line = AutofillPick.handOver(org(appLogin("com.evil.app")), "  \n ")
        assertFalse(line.contains("（"))
    }

    @Test
    fun `应用名恰好等于包名时不写两遍`() {
        val line = AutofillPick.handOver(org(appLogin("com.evil.app")), "com.evil.app")
        assertEquals(1, line.split("com.evil.app").size - 1)
    }

    @Test
    fun `没有主表单时那一行也不是空的`() {
        assertEquals(AutofillPick.REFUSE_NO_FORM, AutofillPick.handOver(null, "Chrome"))
    }

    /* ══════════════ 八、话怎么说 ══════════════ */

    @Test
    fun `四句警告互不重样`() {
        val all = listOf(
            AutofillPick.warning(DomainMatch.Verdict.UntrustedHost),
            AutofillPick.warning(DomainMatch.Verdict.NoEvidence),
            AutofillPick.warning(DomainMatch.Verdict.WrongKind),
            AutofillPick.STORED_ELSEWHERE,
        )
        assertEquals(4, all.toSet().size)
        all.forEach { assertNotNull(it) }
    }

    @Test
    fun `够格自动的那两档没有话说`() {
        assertNull(AutofillPick.warning(DomainMatch.Verdict.Exact))
        assertNull(AutofillPick.warning(DomainMatch.Verdict.SameSite))
        assertNull(AutofillPick.warning(DomainMatch.Verdict.None))
    }

    @Test
    fun `没有一句把这件事说成故障`() {
        val words = listOf("失败", "出错", "错误", "稍后重试", "异常")
        val all = listOf(
            AutofillPick.warning(DomainMatch.Verdict.UntrustedHost)!!,
            AutofillPick.warning(DomainMatch.Verdict.NoEvidence)!!,
            AutofillPick.warning(DomainMatch.Verdict.WrongKind)!!,
            AutofillPick.STORED_ELSEWHERE,
            AutofillPick.NO_MATCH,
            AutofillPick.EMPTY_VAULT,
            AutofillPick.PARTIAL_NOTE,
            AutofillPick.BLOCKED_NOTHING_TO_FILL,
            AutofillPick.REFUSE_NO_FORM,
            AutofillPick.REFUSE_OWN_UI,
        )
        for (s in all) {
            assertTrue(s.isNotBlank())
            for (w in words) assertFalse("「$s」里有「$w」", s.contains(w))
        }
    }

    /** 从一份计划里取主表单的归属，只为几条 handOver 用例。 */
    private fun org(p: FillPlan.Plan) = p.primary!!.origin

    private companion object {
        const val CHROME = "com.android.chrome"
        const val SELF = "cn.localvault.app"
        const val PASSWORD = "s3cr3t-p@ss"
    }
}
