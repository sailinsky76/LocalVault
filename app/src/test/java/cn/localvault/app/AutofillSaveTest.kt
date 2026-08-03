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
import cn.localvault.app.ui.autofill.AutofillSave
import cn.localvault.app.ui.autofill.DomainMatch
import cn.localvault.app.ui.autofill.FillPlan
import cn.localvault.app.ui.autofill.HostTrust
import cn.localvault.app.ui.autofill.Origin
import cn.localvault.app.ui.autofill.SaveContext
import cn.localvault.app.ui.autofill.SaveHandoff
import cn.localvault.app.ui.autofill.SavedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 保存内核。
 *
 * 这一层和前面九个内核的区别是**方向反过来了**：它往库里写。
 * 所以这一份用例里，钉得最死的不是「填不填」，而是**「什么东西绝不会消失」**：
 *   · 已有的账号不会被换掉（第四节）；
 *   · 已有的网址行不会被删（第四节）；
 *   · 名称 / 分类 / 备注一个字不动（第四节）；
 *   · 不够格自动填的来源永远改不了库里那条真的（第三节，决策(199)）；
 *   · 分不出该改哪一条时**一条都不动**（第二节）。
 *
 * 这几条在真机上一条都试不出来——存错了屏幕上照样显示「已保存」，
 * 用户要到下一次登录时才发现，而那时他不会想到是保存那一步动的手。
 */
class AutofillSaveTest {

    private val us = "cn.localvault.app"
    private val chrome = "com.android.chrome"
    private val evil = "com.example.fake"

    private val trust: HostTrust = object : HostTrust {
        override fun isTrustedBrowser(packageName: String) = packageName == chrome
    }

    private fun web(host: String = "example.com", app: String = chrome) = Origin.Web(host, app)
    private fun app(pkg: String = "com.example.app") = Origin.App(pkg)

    private fun v(what: SavedFields.Captured, s: String) = SavedFields.capture(what, s)!!

    private fun ctx(
        origin: Origin = web(),
        user: String? = "ann",
        pwd: String? = "hunter2",
        newPwd: String? = null,
        kind: FillPlan.Kind = FillPlan.Kind.Login,
        label: String? = null,
        extraPwd: String? = null,
        maskedPwd: Boolean = false,
    ): SaveContext {
        val values = ArrayList<SavedFields.Value>()
        user?.let { values += v(SavedFields.Captured.Username, it) }
        pwd?.let { values += v(SavedFields.Captured.Password, it) }
        extraPwd?.let { values += v(SavedFields.Captured.Password, it) }
        newPwd?.let { values += v(SavedFields.Captured.NewPassword, it) }
        return SaveContext(origin, kind, values, label, maskedPassword = maskedPwd)
    }

    private fun entry(
        id: String = "e1",
        name: String = "示例",
        username: String = "ann",
        password: String = "old",
        domains: List<String> = listOf("example.com"),
        category: String = "工作",
        notes: String = "一句备注",
        updatedAt: Long = 1_000L,
    ) = VaultEntry(
        id = id, name = name, username = username, password = password,
        domains = domains, category = category, notes = notes, updatedAt = updatedAt,
    )

    private fun offer(o: AutofillSave.Outcome): AutofillSave.Proposal {
        assertTrue("期望摆出保存屏，实际是 $o", o is AutofillSave.Outcome.Offer)
        return (o as AutofillSave.Outcome.Offer).proposal
    }

    private fun silent(o: AutofillSave.Outcome): AutofillSave.Reason {
        assertTrue("期望安静走人，实际是 $o", o is AutofillSave.Outcome.Silent)
        return (o as AutofillSave.Outcome.Silent).reason
    }

    private fun has(p: AutofillSave.Proposal, f: AutofillSave.Field) =
        p.changes.any { it.field == f }

    private fun change(p: AutofillSave.Proposal, f: AutofillSave.Field) =
        p.changes.first { it.field == f }

    /* ═══════════════ 一、收值：只取舍，不改写（决策(195)）═══════════════ */

    @Test
    fun `密码一个字符都不动`() {
        val raw = "  pa ss  "
        assertEquals(raw, SavedFields.capture(SavedFields.Captured.Password, raw)!!.value)
    }

    @Test
    fun `账号剔首尾空白，中间不动`() {
        assertEquals("a n", SavedFields.capture(SavedFields.Captured.Username, "  a n  ")!!.value)
    }

    @Test
    fun `空的和全空白的都收不下`() {
        for (w in SavedFields.Captured.entries) {
            assertNull(SavedFields.capture(w, null))
            assertNull(SavedFields.capture(w, ""))
            assertNull(SavedFields.capture(w, "   "))
            assertEquals(SavedFields.Rejected.Blank, SavedFields.rejection(w, "  "))
        }
    }

    @Test
    fun `超长整格拒收，不截断`() {
        val long = "x".repeat(SavedFields.MAX_VALUE_CHARS + 1)
        assertNull(SavedFields.capture(SavedFields.Captured.Password, long))
        assertEquals(
            SavedFields.Rejected.TooLong,
            SavedFields.rejection(SavedFields.Captured.Password, long),
        )
        // 正好等于上限的收得下
        assertNotNull(
            SavedFields.capture(SavedFields.Captured.Password, "x".repeat(SavedFields.MAX_VALUE_CHARS)),
        )
    }

    @Test
    fun `控制字符与双向控制符整格拒收，而不是洗掉`() {
        for (bad in listOf("ab\u0000c", "a\nb", "a\tb", "a\u202Eb", "a\u200Bb", "a\uFEFFb")) {
            assertNull("应当拒收：$bad", SavedFields.capture(SavedFields.Captured.Password, bad))
            assertEquals(
                SavedFields.Rejected.Control,
                SavedFields.rejection(SavedFields.Captured.Password, bad),
            )
        }
    }

    @Test
    fun `Value 的 toString 不吐值`() {
        val s = v(SavedFields.Captured.Password, "hunter2").toString()
        assertFalse(s.contains("hunter2"))
    }

    @Test
    fun `SaveContext 的 toString 不吐值`() {
        val s = ctx().toString()
        assertFalse(s.contains("hunter2"))
        assertFalse(s.contains("ann"))
    }

    @Test
    fun `新密码压过已有密码`() {
        val c = ctx(pwd = "old", newPwd = "brandnew", kind = FillPlan.Kind.NewCredential)
        assertEquals("brandnew", c.effectivePassword)
    }

    @Test
    fun `两个已有密码框值一样时不算分不清`() {
        assertFalse(ctx(pwd = "same", extraPwd = "same").conflictingPasswords)
    }

    @Test
    fun `两个已有密码框值不一样时算分不清`() {
        assertTrue(ctx(pwd = "a", extraPwd = "b").conflictingPasswords)
    }

    /* ═══════════════ 二、拒绝排在库状态之前（决策(180)）═══════════════ */

    @Test
    fun `自己的界面不存`() {
        val c = ctx(origin = app(us))
        assertEquals(AutofillSave.Reason.OwnUi, AutofillSave.refuse(c, us))
    }

    @Test
    fun `一个值都没读到时不弹`() {
        val c = ctx(user = null, pwd = null)
        assertEquals(AutofillSave.Reason.NothingCaptured, AutofillSave.refuse(c, us))
    }

    @Test
    fun `只有账号也值得存`() {
        assertNull(AutofillSave.refuse(ctx(pwd = null), us))
    }

    @Test
    fun `密码框读到的是掩码时，不新建一条空壳条目`() {
        // 决策(229)：安全键盘那一屏。账号读得到、密码是一串圆点被拒收了，
        // 拿账号去新建等于留一条永远补不上密码的记录。
        val c = ctx(user = "ann", pwd = null, maskedPwd = true)
        assertEquals(AutofillSave.Reason.MaskedPassword, AutofillSave.refuse(c, us))
    }

    @Test
    fun `同屏还有一个读得出的新密码框时，掩码这一档不挡`() {
        // 改密码页：旧密码那格是安全键盘读不到，新密码那格读得到——
        // 后者才是该存的东西（effectivePassword），不该被挡掉。
        val c = ctx(user = "ann", pwd = null, newPwd = "fresh", maskedPwd = true)
        assertNull(AutofillSave.refuse(c, us))
    }

    @Test
    fun `掩码这一档一律不许静默`() {
        // 决策(234)：这一档的定义就是没读到密码，也就无从知道库里那条对不对。
        // 库里有什么都不改变这一点（决策(232)/(233) 那两版看库，两版都错）。
        assertFalse(AutofillSave.safeToStaySilent(AutofillSave.Reason.MaskedPassword))
    }

    @Test
    fun `其余几档照旧静默`() {
        // 护栏：以后往 Reason 里加档时，如果那一档也不该静默，
        // 得在 safeToStaySilent 里显式写出来，而不是靠默认值默默收场。
        val noisy = AutofillSave.Reason.values().filterNot { AutofillSave.safeToStaySilent(it) }
        assertEquals(listOf(AutofillSave.Reason.MaskedPassword), noisy)
    }

    @Test
    fun `两个分不出新旧的密码框，一个都不存`() {
        val c = ctx(pwd = "a", extraPwd = "b")
        assertEquals(AutofillSave.Reason.CannotTellPassword, AutofillSave.refuse(c, us))
    }

    @Test
    fun `四条拒绝一次都不用碰库`() {
        // 库是空的也照样给出同样的答案——它们不需要知道库的任何事。
        for (c in listOf(
            ctx(origin = app(us)),
            ctx(user = null, pwd = null),
            ctx(pwd = "a", extraPwd = "b"),
            ctx(user = "ann", pwd = null, maskedPwd = true),
        )) {
            val r = AutofillSave.refuse(c, us)
            assertNotNull(r)
            assertEquals(r, silent(AutofillSave.outcome(c, emptyList(), trust, us)))
        }
    }

    @Test
    fun `只读到密码而这个站有两条时，一条都不动`() {
        val c = ctx(user = null, pwd = "new", kind = FillPlan.Kind.PasswordStep)
        val db = listOf(entry("a", username = "ann"), entry("b", username = "bob"))
        assertEquals(AutofillSave.Reason.CannotTellEntry, silent(AutofillSave.outcome(c, db, trust, us)))
    }

    @Test
    fun `只读到密码而这个站恰好一条时，改那一条`() {
        val c = ctx(user = null, pwd = "new", kind = FillPlan.Kind.PasswordStep)
        val p = offer(AutofillSave.outcome(c, listOf(entry("a")), trust, us))
        assertEquals(AutofillSave.Mode.Update, p.mode)
        assertEquals("a", p.target?.id)
    }

    @Test
    fun `库里已经一模一样时安静走人`() {
        val c = ctx(user = "ann", pwd = "old")
        val db = listOf(entry(password = "old", domains = listOf("example.com")))
        assertEquals(AutofillSave.Reason.AlreadyStored, silent(AutofillSave.outcome(c, db, trust, us)))
    }

    @Test
    fun `每一档理由都有一句不说成故障的话`() {
        val banned = listOf("失败", "出错", "错误", "稍后重试", "异常")
        val seen = HashSet<String>()
        for (r in AutofillSave.Reason.entries) {
            val s = AutofillSave.note(r)
            assertTrue(s.length > 8)
            for (b in banned) assertFalse("「$b」出现在 $r 里", s.contains(b))
            assertTrue("$r 的话和别的重样了", seen.add(s))
        }
    }

    /* ═══════════════ 三、新增还是更新 ═══════════════ */

    @Test
    fun `账号逐字相同的那一条被改`() {
        val db = listOf(entry("a", username = "bob"), entry("b", username = "ann"))
        val p = offer(AutofillSave.outcome(ctx(), db, trust, us))
        assertEquals(AutofillSave.Mode.Update, p.mode)
        assertEquals("b", p.target?.id)
    }

    @Test
    fun `排版不同的手机号算两个账号——新增，不覆盖`() {
        // 决策(227) 撤回决策(225)：库里 18623456789，屏幕上读回 186 2345 6789，
        // 判成两个账号。库里多一条用户删得掉；覆盖错一条则不可撤销。
        val db = listOf(entry("a", username = "18623456789"))
        val p = offer(AutofillSave.outcome(ctx(user = "186 2345 6789"), db, trust, us))
        assertEquals(AutofillSave.Mode.Create, p.mode)
        assertNull(p.target)
        // 原来那一条一个字都没被动
        assertEquals("18623456789", db.single().username)
    }

    @Test
    fun `新建时账号原样存下，一个字都不改`() {
        // 屏幕上读到什么就存什么。抹掉分节空格会让存下的那一份和下次读回来的
        // 那一份对不上，逐字比较就又落成新增了。
        val p = offer(AutofillSave.outcome(ctx(user = "186 2345 6789"), emptyList(), trust, us))
        assertEquals(AutofillSave.Mode.Create, p.mode)
        assertEquals("186 2345 6789", p.result.username)
    }

    @Test
    fun `这个站只有一条、而且它还没有账号——补账号、改密码，不新增`() {
        val db = listOf(entry("a", username = ""))
        val p = offer(AutofillSave.outcome(ctx(), db, trust, us))
        assertEquals(AutofillSave.Mode.Update, p.mode)
        assertEquals("a", p.target?.id)
        assertEquals("ann", p.result.username)
        assertTrue(
            p.changes.any {
                it.field == AutofillSave.Field.Username && it.how == AutofillSave.How.Add
            },
        )
        assertNull(p.blocked)
    }

    @Test
    fun `空账号那一条只在这个站独苗时才认，旁边还有别人的账号就新建`() {
        val db = listOf(entry("a", username = ""), entry("b", username = "bob"))
        val p = offer(AutofillSave.outcome(ctx(), db, trust, us))
        assertEquals(AutofillSave.Mode.Create, p.mode)
        assertNull(p.target)
    }

    @Test
    fun `一条账号都对不上就新建`() {
        val db = listOf(entry("a", username = "bob"))
        val p = offer(AutofillSave.outcome(ctx(), db, trust, us))
        assertEquals(AutofillSave.Mode.Create, p.mode)
        assertNull(p.target)
    }

    @Test
    fun `库是空的就新建`() {
        val p = offer(AutofillSave.outcome(ctx(), emptyList(), trust, us))
        assertEquals(AutofillSave.Mode.Create, p.mode)
    }

    @Test
    fun `好几条同账号时取最近改过的，其余进 alternatives`() {
        val db = listOf(
            entry("a", username = "ann", updatedAt = 10L),
            entry("b", username = "ann", updatedAt = 99L),
            entry("c", username = "ann", updatedAt = 50L),
        )
        val p = offer(AutofillSave.outcome(ctx(), db, trust, us))
        assertEquals("b", p.target?.id)
        assertEquals(setOf("a", "c"), p.alternatives.map { it.id }.toSet())
    }

    @Test
    fun `不够格自动填的来源只能新建，绝不更新`() {
        // 同一份库、同一个账号，只把承载应用换成一个不认识的
        val db = listOf(entry("a", username = "ann"))
        val p = offer(AutofillSave.outcome(ctx(origin = web(app = evil)), db, trust, us))
        assertEquals(AutofillSave.Mode.Create, p.mode)
        assertTrue(p.alternatives.isEmpty())
    }

    @Test
    fun `原生应用屏上不去改那条存网址的条目`() {
        // NoEvidence 那一档：库里存的是 example.com，这一屏是一个应用
        val db = listOf(entry("a", domains = listOf("example.com")))
        val p = offer(AutofillSave.outcome(ctx(origin = app("com.example.app")), db, trust, us))
        assertEquals(AutofillSave.Mode.Create, p.mode)
        assertEquals(listOf("com.example.app"), p.result.domains)
    }

    @Test
    fun `updatable 只收够格自动填的那两档`() {
        val db = listOf(
            entry("exact", domains = listOf("example.com")),
            entry("sibling", domains = listOf("mail.example.com")),
            entry("other", domains = listOf("elsewhere.com")),
            entry("pkg", domains = listOf("com.example.app")),
            entry("none", domains = emptyList()),
        )
        val ok = AutofillSave.updatable(web(), db, trust).map { it.id }
        assertEquals(listOf("exact", "sibling"), ok)
    }

    @Test
    fun `硬换到一条不够格的条目上时不给按`() {
        val target = entry("a", domains = listOf("elsewhere.com"))
        val p = AutofillSave.proposeUpdate(ctx(), target, trust)
        assertEquals(AutofillSave.BLOCKED_UNTRUSTED_UPDATE, p.blocked)
        assertFalse(p.canCommit)
    }

    @Test
    fun `硬换到一条别人账号的条目上时不给按`() {
        val target = entry("a", username = "bob")
        val p = AutofillSave.proposeUpdate(ctx(user = "ann"), target, trust)
        assertEquals(AutofillSave.BLOCKED_OTHER_ACCOUNT, p.blocked)
        assertFalse(p.canCommit)
    }

    @Test
    fun `账号是空的那一条可以补上账号`() {
        val target = entry("a", username = "")
        val p = AutofillSave.proposeUpdate(ctx(user = "ann"), target, trust)
        assertNull(p.blocked)
        assertEquals(AutofillSave.How.Add, change(p, AutofillSave.Field.Username).how)
        assertEquals("ann", p.result.username)
    }

    @Test
    fun `不给按的那两句话不说成故障，而且都给了下一步`() {
        for (s in listOf(AutofillSave.BLOCKED_UNTRUSTED_UPDATE, AutofillSave.BLOCKED_OTHER_ACCOUNT)) {
            for (b in listOf("失败", "出错", "稍后重试")) assertFalse(s.contains(b))
            assertTrue(s.contains("新建") || s.contains("换一条"))
        }
    }

    /* ═══════════════ 四、只增不改：什么东西绝不会消失 ═══════════════ */

    @Test
    fun `更新时名称一个字都不动`() {
        val target = entry(name = "我的招商银行")
        val p = AutofillSave.proposeUpdate(ctx(pwd = "new"), target, trust)
        assertEquals("我的招商银行", p.result.name)
        assertFalse(has(p, AutofillSave.Field.Name))
    }

    @Test
    fun `更新时分类和备注一个字都不动`() {
        val target = entry(category = "工作", notes = "一句备注")
        val p = AutofillSave.proposeUpdate(ctx(pwd = "new"), target, trust)
        assertEquals("工作", p.result.category)
        assertEquals("一句备注", p.result.notes)
    }

    @Test
    fun `更新时已有的账号绝不被换掉`() {
        // 这一条只有走 blocked 才可能到，但结果对象也不许悄悄改
        val target = entry(username = "bob")
        val p = AutofillSave.proposeUpdate(ctx(user = "ann"), target, trust)
        assertEquals("bob", p.result.username)
    }

    @Test
    fun `更新时已有的网址行一行都不删，只追加`() {
        val target = entry(domains = listOf("mail.example.com", "old.example.com"))
        val p = AutofillSave.proposeUpdate(ctx(origin = web("login.example.com")), target, trust)
        assertTrue(p.result.domains.containsAll(listOf("mail.example.com", "old.example.com")))
        assertTrue(p.result.domains.contains("login.example.com"))
        assertEquals(3, p.result.domains.size)
    }

    @Test
    fun `同一个站不会被重复追加，哪怕写法不一样`() {
        val target = entry(domains = listOf("https://example.com/login"))
        val p = AutofillSave.proposeUpdate(ctx(origin = web("example.com")), target, trust)
        assertEquals(1, p.result.domains.size)
        assertFalse(has(p, AutofillSave.Field.Domain))
    }

    @Test
    fun `密码一样就不列这一条改动`() {
        val target = entry(password = "hunter2")
        val p = AutofillSave.proposeUpdate(ctx(pwd = "hunter2"), target, trust)
        assertFalse(has(p, AutofillSave.Field.Password))
    }

    @Test
    fun `密码原来是空的算 Add，不算 Replace`() {
        val target = entry(password = "")
        val p = AutofillSave.proposeUpdate(ctx(pwd = "hunter2"), target, trust)
        assertEquals(AutofillSave.How.Add, change(p, AutofillSave.Field.Password).how)
    }

    @Test
    fun `密码不一样算 Replace`() {
        val target = entry(password = "old")
        val p = AutofillSave.proposeUpdate(ctx(pwd = "new"), target, trust)
        assertEquals(AutofillSave.How.Replace, change(p, AutofillSave.Field.Password).how)
    }

    @Test
    fun `什么都没变的提案不给按`() {
        val target = entry(password = "hunter2", domains = listOf("example.com"))
        val p = AutofillSave.proposeUpdate(ctx(pwd = "hunter2"), target, trust)
        assertTrue(p.isNoop)
        assertFalse(p.canCommit)
    }

    @Test
    fun `整份提案里只有密码可能是 Replace`() {
        val target = entry(username = "", password = "old", domains = emptyList())
        val p = AutofillSave.proposeUpdate(ctx(), target, trust)
        for (c in p.changes) {
            if (c.field != AutofillSave.Field.Password) {
                assertEquals("${c.field} 不该是 Replace", AutofillSave.How.Add, c.how)
            }
        }
    }

    @Test
    fun `更新那一条的 id 不变`() {
        val target = entry("keep-me")
        val p = AutofillSave.proposeUpdate(ctx(pwd = "new"), target, trust)
        assertEquals("keep-me", p.result.id)
    }

    @Test
    fun `新建那一条的 id 是空的，交给会话去发`() {
        val p = AutofillSave.proposeCreate(ctx(), trust)
        assertEquals("", p.result.id)
    }

    /* ═══════════════ 五、屏幕上摆什么 ═══════════════ */

    @Test
    fun `密码那一条改动永远不带值`() {
        val target = entry(password = "old")
        val p = AutofillSave.proposeUpdate(ctx(pwd = "hunter2"), target, trust)
        val c = change(p, AutofillSave.Field.Password)
        assertNull(c.shown)
        assertFalse(AutofillSave.changeNote(c).contains("hunter2"))
        assertFalse(AutofillSave.changeNote(c).contains("old"))
    }

    @Test
    fun `新建那一份里也不带密码值`() {
        val p = AutofillSave.proposeCreate(ctx(), trust)
        for (c in p.changes) {
            assertFalse(c.shown.orEmpty().contains("hunter2"))
            assertFalse(AutofillSave.changeNote(c).contains("hunter2"))
        }
    }

    @Test
    fun `Proposal 的 toString 不吐任何内容`() {
        val s = offer(AutofillSave.outcome(ctx(), emptyList(), trust, us)).toString()
        assertFalse(s.contains("hunter2"))
        assertFalse(s.contains("ann"))
        assertFalse(s.contains("example.com"))
    }

    @Test
    fun `Change 的 toString 不吐任何内容`() {
        val p = AutofillSave.proposeCreate(ctx(), trust)
        for (c in p.changes) {
            assertFalse(c.toString().contains("ann"))
            assertFalse(c.toString().contains("example.com"))
        }
    }

    @Test
    fun `每一条改动都念得出一句话，而且互不重样`() {
        val target = entry(username = "", password = "old", domains = emptyList())
        val p = AutofillSave.proposeUpdate(ctx(origin = web("example.com")), target, trust)
        val said = p.changes.map { AutofillSave.changeNote(it) }
        assertEquals(said.size, said.distinct().size)
        for (s in said) assertTrue(s.length > 4)
    }

    @Test
    fun `换密码那一档的话要说清旧的会没`() {
        val target = entry(password = "old")
        val p = AutofillSave.proposeUpdate(ctx(pwd = "new"), target, trust)
        val s = AutofillSave.changeNote(change(p, AutofillSave.Field.Password))
        assertTrue(s.contains("换掉"))
        assertTrue(s.contains("历史版本") || s.contains("消失"))
    }

    @Test
    fun `记在谁名下那一行永远带包名`() {
        val s = AutofillSave.storedUnder(web("example.com", chrome), "Chrome 浏览器")
        assertTrue(s.contains(chrome))
        assertTrue(s.contains("example.com"))
    }

    @Test
    fun `应用名读不出来时只写包名，不写未知应用`() {
        val s = AutofillSave.storedUnder(app("com.example.app"), null)
        assertTrue(s.contains("com.example.app"))
        assertFalse(s.contains("未知应用"))
    }

    @Test
    fun `应用名里的双向控制符会被洗掉`() {
        val s = AutofillSave.storedUnder(app("com.example.app"), "moc.knab\u202E")
        assertFalse(s.contains("\u202E"))
    }

    @Test
    fun `网页那一屏说网站，原生那一屏说应用`() {
        assertTrue(AutofillSave.storedUnder(web(), null).contains("网站"))
        assertTrue(AutofillSave.storedUnder(app(), null).contains("应用"))
    }

    /* ═══════════════ 六、存成什么样 ═══════════════ */

    @Test
    fun `网页存归一后的主机名，不上卷到可注册域`() {
        assertEquals("login.example.com", AutofillSave.domainLine(web("login.example.com")))
    }

    @Test
    fun `原生存包名`() {
        assertEquals("com.example.app", AutofillSave.domainLine(app("com.example.app")))
    }

    @Test
    fun `名字用可注册域，和存下去那一行故意不一样`() {
        val o = web("login.example.com")
        assertEquals("example.com", AutofillSave.suggestedName(o, null))
        assertEquals("login.example.com", AutofillSave.domainLine(o))
    }

    @Test
    fun `原生的名字优先用应用名，读不到就用包名`() {
        assertEquals("某某银行", AutofillSave.suggestedName(app("com.bank"), "某某银行"))
        assertEquals("com.bank", AutofillSave.suggestedName(app("com.bank"), null))
        assertEquals("com.bank", AutofillSave.suggestedName(app("com.bank"), "   "))
    }

    @Test
    fun `名字也要洗一道`() {
        val n = AutofillSave.suggestedName(app("com.bank"), "某某\n银行\u202E")
        assertFalse(n.contains("\n"))
        assertFalse(n.contains("\u202E"))
    }

    @Test
    fun `新建那一份把四样都装齐了`() {
        val p = AutofillSave.proposeCreate(ctx(origin = web("login.example.com")), trust)
        assertEquals("example.com", p.result.name)
        assertEquals("ann", p.result.username)
        assertEquals("hunter2", p.result.password)
        assertEquals(listOf("login.example.com"), p.result.domains)
        assertEquals(4, p.changes.size)
    }

    @Test
    fun `只有账号没有密码时照样存得下`() {
        val p = AutofillSave.proposeCreate(ctx(pwd = null), trust)
        assertEquals("ann", p.result.username)
        assertEquals("", p.result.password)
        assertFalse(has(p, AutofillSave.Field.Password))
    }

    /* ═══════════════ 七、警告 ═══════════════ */

    @Test
    fun `一切照常时一句废话都不说`() {
        val target = entry(password = "old")
        val p = AutofillSave.proposeUpdate(ctx(pwd = "new"), target, trust)
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `改密码那一屏要先说一句旧的会没`() {
        val target = entry(password = "old")
        val c = ctx(pwd = "old", newPwd = "brandnew", kind = FillPlan.Kind.NewCredential)
        val p = AutofillSave.proposeUpdate(c, target, trust)
        assertTrue(p.warnings.contains(AutofillSave.CHANGED_PASSWORD_NOTE))
        assertEquals("brandnew", p.result.password)
    }

    @Test
    fun `从不认识的承载应用上新建时要先说一句`() {
        // 新建那一侧的判定永远是 None，这一句必须靠承载应用去问，不能靠 verdict
        val p = offer(AutofillSave.outcome(ctx(origin = web(app = evil)), emptyList(), trust, us))
        assertEquals(AutofillSave.Mode.Create, p.mode)
        assertEquals(DomainMatch.Verdict.None, p.verdict)
        assertTrue(p.warnings.contains(AutofillSave.CREATED_FROM_UNTRUSTED))
    }

    @Test
    fun `认得的浏览器上新建时不说那一句`() {
        val p = offer(AutofillSave.outcome(ctx(), emptyList(), trust, us))
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `原生应用那一侧不问浏览器可不可信`() {
        val p = offer(AutofillSave.outcome(ctx(origin = app("com.example.app")), emptyList(), trust, us))
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `那几句提醒里没有一句说成故障`() {
        val all = listOf(
            AutofillSave.CREATED_FROM_UNTRUSTED,
            AutofillSave.CHANGED_PASSWORD_NOTE,
            AutofillSave.UNVERIFIED_NOTE,
            AutofillSave.BLOCKED_UNTRUSTED_UPDATE,
            AutofillSave.BLOCKED_OTHER_ACCOUNT,
        )
        for (s in all) {
            for (b in listOf("失败了", "出错", "错误", "稍后重试", "异常")) {
                assertFalse("「$b」出现在：$s", s.contains(b))
            }
        }
        assertEquals(all.size, all.distinct().size)
    }

    @Test
    fun `更新时那三档非自动的话原样复用挑选页那一份`() {
        // 走 blocked 的那一档也照样把话带出来，页面才解释得清为什么不给按
        val target = entry(domains = listOf("example.com"))
        val p = AutofillSave.proposeUpdate(ctx(origin = web(app = evil)), target, trust)
        assertEquals(DomainMatch.Verdict.UntrustedHost, p.verdict)
        assertTrue(p.warnings.isNotEmpty())
    }

    /* ═══════════════ 八、幂等与稳定 ═══════════════ */

    @Test
    fun `同样的输入算两遍结果一样`() {
        val db = listOf(entry("a", username = "ann", password = "old"))
        val p1 = offer(AutofillSave.outcome(ctx(), db, trust, us))
        val p2 = offer(AutofillSave.outcome(ctx(), db, trust, us))
        assertEquals(p1.mode, p2.mode)
        assertEquals(p1.target?.id, p2.target?.id)
        assertEquals(p1.changes.map { it.field to it.how }, p2.changes.map { it.field to it.how })
        assertEquals(p1.result, p2.result)
    }

    @Test
    fun `存过一次之后再存同样的东西就没得改了`() {
        val db = listOf(entry("a", username = "ann", password = "old", domains = listOf("example.com")))
        val first = offer(AutofillSave.outcome(ctx(pwd = "new"), db, trust, us))
        val after = listOf(first.result)
        assertEquals(
            AutofillSave.Reason.AlreadyStored,
            silent(AutofillSave.outcome(ctx(pwd = "new"), after, trust, us)),
        )
    }

    @Test
    fun `换目标之后 alternatives 里不再有它自己`() {
        val db = listOf(
            entry("a", username = "ann", updatedAt = 10L),
            entry("b", username = "ann", updatedAt = 99L),
        )
        val p = offer(AutofillSave.outcome(ctx(pwd = "new"), db, trust, us))
        val other = p.alternatives.first()
        val q = AutofillSave.proposeUpdate(ctx(pwd = "new"), other, trust, db)
        assertFalse(q.alternatives.any { it.id == other.id })
    }

    /* ═══════════════ 九、交接槽：明文不进 Intent（决策(198)）═══════════════ */

    @Before
    fun resetHandoff() = SaveHandoff.clear()

    @Test
    fun `票号进得了 Intent，内容留在进程里`() {
        val c = ctx()
        val t = SaveHandoff.offer(c, 0L)
        assertFalse(t.toString().contains("hunter2"))
        assertSame(c, SaveHandoff.take(t, 1L))
    }

    @Test
    fun `取一次就清`() {
        val t = SaveHandoff.offer(ctx(), 0L)
        assertNotNull(SaveHandoff.take(t, 1L))
        assertNull(SaveHandoff.take(t, 2L))
        assertFalse(SaveHandoff.hasPending(2L))
    }

    @Test
    fun `票对不上一个字都拿不到`() {
        val t = SaveHandoff.offer(ctx(), 0L)
        assertNull(SaveHandoff.take(SaveHandoff.Ticket(t.id + 1), 1L))
    }

    @Test
    fun `过期的拿不到，而且照样被清掉`() {
        val t = SaveHandoff.offer(ctx(), 0L)
        assertNull(SaveHandoff.take(t, SaveHandoff.TTL_MILLIS + 1))
        assertFalse(SaveHandoff.hasPending(SaveHandoff.TTL_MILLIS + 1))
    }

    @Test
    fun `同时只留一份，后一份挤掉前一份`() {
        val first = SaveHandoff.offer(ctx(user = "a"), 0L)
        val second = SaveHandoff.offer(ctx(user = "b"), 0L)
        assertNull(SaveHandoff.take(first, 1L))
        // 挤掉之后前一张票作废，但后一张仍然有效
        val again = SaveHandoff.offer(ctx(user = "b"), 0L)
        assertNotNull(SaveHandoff.take(again, 1L))
        assertNull(SaveHandoff.take(second, 1L))
    }

    @Test
    fun `clear 之后什么都不剩`() {
        val t = SaveHandoff.offer(ctx(), 0L)
        SaveHandoff.clear()
        assertFalse(SaveHandoff.hasPending(1L))
        assertNull(SaveHandoff.take(t, 1L))
    }
}
