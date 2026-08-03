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

import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultData
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.autofill.AutofillMatch
import cn.localvault.app.ui.autofill.AutofillOffer
import cn.localvault.app.ui.autofill.FieldGroups
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
 * 「这一次填充请求，屏幕上到底该出现什么」。
 *
 * 这一层把前面四个内核串起来，所以它也是**最后一道能把前面全部小心作废的地方**：
 * 只要在这儿写一句「主表单判过了，那就照 `plan.forms` 全写一遍」，
 * 密码就会顺着同屏的第二组框流进不该去的地方（见 `每个表单各判一次归属` 那几条）。
 * 那种错在真机上看不见——填充条照样弹出来，用户点一下，一切正常。
 */
class AutofillOfferTest {

    private var seq = 0L

    private fun f(hint: String? = null, web: String? = null, focused: Boolean = false): RawField =
        RawField(
            handle = seq++,
            autofillHints = if (hint == null) emptyList() else listOf(hint),
            webDomain = web,
            focused = focused,
        )

    private fun user(web: String? = null, focused: Boolean = false) =
        f("username", web, focused)

    private fun pass(web: String? = null, focused: Boolean = false) =
        f("password", web, focused)

    private fun newPass(web: String? = null) = f("newPassword", web)

    private fun otp(web: String? = null, focused: Boolean = false) = f("smsOTPCode", web, focused)

    private fun plan(app: String, vararg fields: RawField): FillPlan.Plan =
        FillPlan.forRequest(FillContext(activityPackage = app, fields = fields.toList()))

    /** 一屏最普通的网页登录框（浏览器承载）。 */
    private fun webLogin(host: String = "example.com") =
        plan(CHROME, user(host), pass(host))

    private fun entry(
        id: String = "id-1",
        name: String = "示例站",
        username: String = "zhangsan",
        password: String = "s3cr3t-p@ss",
        domains: List<String> = listOf("example.com"),
        favorite: Boolean = false,
        updatedAt: Long = 0L,
    ) = VaultEntry(
        id = id,
        name = name,
        username = username,
        password = password,
        domains = domains,
        favorite = favorite,
        updatedAt = updatedAt,
    )

    private fun unlocked(vararg entries: VaultEntry): VaultSession.State =
        VaultSession.State.Unlocked(VaultData(entries = entries.toList()))

    private val trust: HostTrust = object : HostTrust {
        override fun isTrustedBrowser(packageName: String) = packageName == CHROME
    }

    private fun respond(
        state: VaultSession.State,
        plan: FillPlan.Plan,
    ): AutofillOffer.Response = AutofillOffer.respond(state, plan, trust, SELF)

    private fun offerOf(state: VaultSession.State, plan: FillPlan.Plan): AutofillOffer.Offer =
        respond(state, plan) as AutofillOffer.Offer

    /* ══════════════ 一、三条路 ══════════════ */

    @Test
    fun `这一屏没有能填的框就什么都不出`() {
        val r = respond(unlocked(entry()), plan(CHROME, otp("example.com")))
        assertEquals(AutofillOffer.Why.NoFillableField, (r as AutofillOffer.Silent).why)
    }

    @Test
    fun `没有能填的框这一问排在库状态前面`() {
        // 它不需要知道库的任何事，也就不会因为回答它而泄露任何事。
        val noFields = plan(CHROME, otp("example.com"))
        assertTrue(respond(VaultSession.State.NoVault, noFields) is AutofillOffer.Silent)
        assertTrue(respond(VaultSession.State.Locked, noFields) is AutofillOffer.Silent)
        assertEquals(
            AutofillOffer.Why.NoFillableField,
            (respond(VaultSession.State.Locked, noFields) as AutofillOffer.Silent).why,
        )
    }

    @Test
    fun `不往自己的界面上填`() {
        // 拿自己的密码填自己的解锁页：那一屏是 FLAG_SECURE 的，而填充条是系统画的；
        // 更要紧的是自动锁定那套相位会和「系统把我们推到后台」打架。
        val r = respond(unlocked(entry()), plan(SELF, user(), pass()))
        assertEquals(AutofillOffer.Why.OwnUi, (r as AutofillOffer.Silent).why)
    }

    @Test
    fun `还没有库时什么都不出`() {
        val r = respond(VaultSession.State.NoVault, webLogin())
        assertEquals(AutofillOffer.Why.NoVault, (r as AutofillOffer.Silent).why)
    }

    @Test
    fun `锁着的时候只出一条先解锁`() {
        assertTrue(respond(VaultSession.State.Locked, webLogin()) is AutofillOffer.Unlock)
    }

    @Test
    fun `解锁那一条不说库里有什么`() {
        // 不是不肯说，是数不出来——库文件是密文。
        assertFalse(AutofillOffer.UNLOCK_NOTE.contains("条目"))
        assertTrue(AutofillOffer.UNLOCK_NOTE.contains("数不出"))
    }

    /* ══════════════ 二、填充条上那几行 ══════════════ */

    @Test
    fun `命中的条目出一行，名称在上账号在下`() {
        val o = offerOf(unlocked(entry()), webLogin())
        assertEquals(1, o.items.size)
        assertEquals("示例站", o.items[0].label)
        assertEquals("zhangsan", o.items[0].sublabel)
    }

    @Test
    fun `填充条上没有密码`() {
        // 填充条是系统进程画的，输入法和无障碍服务看得见，也会进截屏。
        val o = offerOf(unlocked(entry(password = "s3cr3t-p@ss")), webLogin())
        val item = o.items[0]
        assertFalse(item.label.contains("s3cr3t"))
        assertFalse(item.sublabel.contains("s3cr3t"))
        assertFalse((item.badge ?: "").contains("s3cr3t"))
        assertFalse(item.toString().contains("s3cr3t"))
        assertFalse(o.toString().contains("s3cr3t"))
    }

    @Test
    fun `没有账号的条目显示一句话而不是空白`() {
        val o = offerOf(unlocked(entry(username = "")), webLogin())
        assertEquals(AutofillOffer.NO_USERNAME, o.items[0].sublabel)
        // 但密码照填。
        assertEquals(1, o.items[0].writes.size)
        assertEquals(FillPlan.Slot.Password, o.items[0].writes[0].slot)
    }

    @Test
    fun `没有名称的条目退回账号`() {
        val o = offerOf(unlocked(entry(name = "")), webLogin())
        assertEquals("zhangsan", o.items[0].label)
    }

    @Test
    fun `名称账号都没有时也不会显示空白`() {
        val o = offerOf(unlocked(entry(name = "", username = "")), webLogin())
        assertEquals(AutofillOffer.NO_NAME, o.items[0].label)
    }

    @Test
    fun `兄弟域要写出你存的是哪一个`() {
        // 决策(159) 的第二道兜底：公共后缀表不可能永远全，
        // 表错一条的后果要在这一行上被用户一眼看见。
        val o = offerOf(
            unlocked(entry(domains = listOf("mail.example.com"))),
            webLogin("login.example.com"),
        )
        assertNotNull(o.items[0].badge)
        assertTrue(o.items[0].badge!!.contains("mail.example.com"))
    }

    @Test
    fun `逐字对上的那一档不摆这一行`() {
        val o = offerOf(unlocked(entry()), webLogin())
        assertNull(o.items[0].badge)
    }

    /* ══════════════ 三、每个表单各判一次归属（底线二） ══════════════ */

    @Test
    fun `同屏另一组框归属对不上时一个字都不写`() {
        // 一组是 example.com 的 iframe，另一组是承载它的应用自己的原生框。
        // 「主表单判过了，照 forms 全写一遍」是这一层唯一能把前面全部小心作废的写法。
        val p = plan(
            CHROME,
            user("example.com", focused = true), pass("example.com"),
            user(), pass(), // 浏览器自己的原生框
        )
        assertEquals(2, p.forms.size)
        val o = offerOf(unlocked(entry(domains = listOf("example.com"))), p)
        val handles = o.items[0].writes.map { it.handle }.toSet()
        assertEquals(setOf(0L, 1L), handles)
    }

    @Test
    fun `两个不同网站的表单同屏时各写各的`() {
        val p = plan(
            CHROME,
            user("a.example.com", focused = true), pass("a.example.com"),
            user("other.test"), pass("other.test"),
        )
        val o = offerOf(unlocked(entry(domains = listOf("a.example.com"))), p)
        assertEquals(setOf(0L, 1L), o.items[0].writes.map { it.handle }.toSet())
    }

    @Test
    fun `同一个网站被隔开的两组都写`() {
        // 登录表单和注册表单同屏（FieldGroups 会切成两组），归属是同一个，
        // 两组都该写好——一次 Dataset 本来就能把同屏几组一起填（决策(175)）。
        val p = plan(
            CHROME,
            user("example.com", focused = true), pass("example.com"),
            user("example.com"), pass("example.com"),
        )
        assertEquals(2, p.forms.size)
        val o = offerOf(unlocked(entry()), p)
        assertEquals(setOf(0L, 1L, 2L, 3L), o.items[0].writes.map { it.handle }.toSet())
    }

    @Test
    fun `承载的应用不是浏览器时一条都不自动出`() {
        // AutoSpill：恶意应用套一个 WebView 显示银行登录页。
        val p = plan("com.evil.app", user("example.com"), pass("example.com"))
        val o = offerOf(unlocked(entry()), p)
        assertTrue(o.items.isEmpty())
    }

    @Test
    fun `原生框配网址条目也不自动出`() {
        val p = plan("com.example.app", user(), pass())
        val o = offerOf(unlocked(entry(domains = listOf("example.com"))), p)
        assertTrue(o.items.isEmpty())
    }

    /* ══════════════ 四、点下去什么都不会发生的不出现（底线三） ══════════════ */

    @Test
    fun `只有名称的条目不出现在填充条上`() {
        val o = offerOf(unlocked(entry(username = "", password = "")), webLogin())
        assertTrue(o.items.isEmpty())
    }

    @Test
    fun `新密码那一屏只填账号，没有账号的条目就整条不出`() {
        val p = plan(CHROME, user("example.com"), newPass("example.com"))
        val onlyPassword = entry(username = "")
        assertTrue(offerOf(unlocked(onlyPassword), p).items.isEmpty())
        assertEquals(1, offerOf(unlocked(entry()), p).items.size)
    }

    /* ══════════════ 五、空的 Offer 不是 Silent ══════════════ */

    @Test
    fun `一条都没匹配上时仍然是 Offer，好留住那条搜索入口`() {
        // 决策(160)：「绝不自动建议」和「不许手动挑」是两件事。
        // 何况一个空荡荡的填充条，用户唯一的结论是「这功能坏了」。
        val r = respond(unlocked(entry(domains = listOf("other.test"))), webLogin())
        assertTrue(r is AutofillOffer.Offer)
        assertTrue((r as AutofillOffer.Offer).items.isEmpty())
    }

    @Test
    fun `库是空的时候也是 Offer`() {
        val r = respond(unlocked(), webLogin())
        assertTrue(r is AutofillOffer.Offer)
    }

    /* ══════════════ 六、条数、顺序、说明 ══════════════ */

    @Test
    fun `超过上限的部分只报条数`() {
        val many = (1..AutofillMatch.MAX_SUGGESTIONS + 3).map {
            entry(id = "id-$it", name = "站 $it")
        }
        val o = offerOf(unlocked(*many.toTypedArray()), webLogin())
        assertEquals(AutofillMatch.MAX_SUGGESTIONS, o.items.size)
        assertEquals(3, o.hidden)
        assertTrue(AutofillOffer.searchLabel(o.hidden).contains("3"))
    }

    @Test
    fun `没有被截掉时搜索那一行不提条数`() {
        val o = offerOf(unlocked(entry()), webLogin())
        assertEquals(0, o.hidden)
        assertFalse(AutofillOffer.searchLabel(0).contains("还有"))
    }

    @Test
    fun `收藏的排在前面`() {
        val o = offerOf(
            unlocked(entry(id = "a", name = "甲"), entry(id = "b", name = "乙", favorite = true)),
            webLogin(),
        )
        assertEquals(listOf("b", "a"), o.items.map { it.entryId })
    }

    @Test
    fun `一切照常的那一屏一句废话都不说`() {
        assertNull(offerOf(unlocked(entry()), webLogin()).note)
    }

    @Test
    fun `要设新密码的那一屏先说一句`() {
        val o = offerOf(unlocked(entry()), plan(CHROME, user("example.com"), newPass("example.com")))
        assertNotNull(o.note)
        assertTrue(o.note!!.contains("留空"))
    }

    @Test
    fun `分不出新旧的那一屏也先说一句`() {
        val o = offerOf(
            unlocked(entry()),
            plan(CHROME, user("example.com"), pass("example.com"), pass("example.com")),
        )
        assertNotNull(o.note)
    }

    /* ══════════════ 七、四种「不出现」都要能对人解释 ══════════════ */

    @Test
    fun `每一种不出现都有一句话，而且没有一句说成故障`() {
        val notes = AutofillOffer.Why.entries.map { AutofillOffer.whyNote(it) }
        assertEquals(notes.size, notes.toSet().size)
        for (n in notes) {
            assertTrue(n.isNotBlank())
            assertFalse(n.contains("失败"))
            assertFalse(n.contains("出错"))
            assertFalse(n.contains("稍后重试"))
        }
    }

    @Test
    fun `Silent 的 toString 只报原因，不吐别的`() {
        val r = respond(unlocked(entry()), plan(SELF, user(), pass())) as AutofillOffer.Silent
        assertEquals("Silent(OwnUi)", r.toString())
    }

    private companion object {
        const val CHROME = "com.android.chrome"
        const val SELF = "cn.localvault.app"
    }
}
