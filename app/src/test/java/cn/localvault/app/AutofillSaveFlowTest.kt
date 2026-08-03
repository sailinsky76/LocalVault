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
import cn.localvault.app.ui.autofill.AutofillOffer
import cn.localvault.app.ui.autofill.AutofillPickFlow
import cn.localvault.app.ui.autofill.AutofillSave
import cn.localvault.app.ui.autofill.AutofillSaveFlow
import cn.localvault.app.ui.autofill.FillPlan
import cn.localvault.app.ui.autofill.HostTrust
import cn.localvault.app.ui.autofill.Origin
import cn.localvault.app.ui.autofill.SaveContext
import cn.localvault.app.ui.autofill.SavedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存确认页**此刻该摆哪一屏**。
 *
 * 这一层守的三件事在真机上都**看不出来**——页面还在，字还在，没有一处会报错：
 *
 *   1. 摆着确认单被自动锁定。那一屏上有用户刚打的账号、有他库里那条条目的名称，
 *      浮在别人的应用上面；**而且这一页手上还揣着一份明文密码**。
 *      第二节那几条钉的是它：把 `phase` 里 `Locked -> Unlocking` 那一行删掉，
 *      它们立刻红，而真机上要复现得等满一次锁定周期并且盯着一屏没变化的界面看。
 *   2. 页面被回收后重建，`SaveHandoff.take` 第二次拿不到。
 *      不钉的话，用户看到的是一个按下去什么都不会发生的「存进保险库」按钮。
 *   3. **解锁之后才算出来的那两档拒绝必须说一句，不能安静关掉**——
 *      用户刚为它输了一次主密码。第三节钉这个，它是这一页和挑选页唯一不一样的地方。
 */
class AutofillSaveFlowTest {

    private fun trustOnly(vararg pkgs: String): HostTrust = object : HostTrust {
        override fun isTrustedBrowser(packageName: String): Boolean = packageName in pkgs
    }

    private val trust: HostTrust = trustOnly(CHROME)

    private val unlocked: VaultSession.State
        get() = VaultSession.State.Unlocked(VaultData(entries = emptyList()))

    private fun ctx(
        kind: FillPlan.Kind = FillPlan.Kind.Login,
        user: String? = "zhangsan",
        pwd: String? = "s3cr3t",
        host: String = "example.com",
        app: String = CHROME,
    ): SaveContext {
        val values = listOfNotNull(
            user?.let { SavedFields.capture(SavedFields.Captured.Username, it) },
            pwd?.let { SavedFields.capture(SavedFields.Captured.Password, it) },
        )
        return SaveContext(Origin.Web(host, app), kind, values, appLabel = "Chrome")
    }

    private fun entry(
        id: String = "id-1",
        name: String = "示例站",
        username: String = "zhangsan",
        password: String = "old",
    ) = VaultEntry(
        id = id,
        name = name,
        username = username,
        password = password,
        domains = listOf("example.com"),
    )

    private fun offer(): AutofillSave.Outcome.Offer =
        AutofillSave.Outcome.Offer(AutofillSave.proposeCreate(ctx(), trust))

    private fun updateOffer(target: VaultEntry = entry()): AutofillSave.Outcome.Offer =
        AutofillSave.Outcome.Offer(AutofillSave.proposeUpdate(ctx(), target, trust))

    /**
     * 那两档 `Silent` 直接构造，不绕 `AutofillSave.outcome` 去凑一个能产出它的库——
     * 这个文件管的是「算出这个答案之后界面摆什么」，算得对不对由
     * `AutofillSaveTest` 那 75 条管。绕一圈等于在这儿又赌一次那一层的行为。
     */
    private fun silent(reason: AutofillSave.Reason) = AutofillSave.Outcome.Silent(reason)

    private fun phase(
        state: VaultSession.State = unlocked,
        hasContext: Boolean = true,
        refusal: AutofillSave.Reason? = null,
        outcome: AutofillSave.Outcome? = offer(),
        committed: Boolean = false,
    ) = AutofillSaveFlow.phase(state, hasContext, refusal, outcome, committed)

    /* ══════════════════════════ 一、正路 ══════════════════════════ */

    @Test
    fun `库开着且提案算好了，摆确认单`() {
        val o = offer()
        val p = phase(outcome = o)
        assertTrue(p is AutofillSaveFlow.Confirming)
        assertSame(o.proposal, (p as AutofillSaveFlow.Confirming).proposal)
    }

    @Test
    fun `刚解锁完提案还没算出来时摆等待，不摆空的确认单`() {
        // 少了这一档，「解锁完成」到「提案算好」之间会有一帧空清单，
        // 而那一帧上的按钮是按得下去的。
        assertEquals(AutofillSaveFlow.Working, phase(outcome = null))
    }

    /* ══════════════════════════ 二、跟着相位走 ══════════════════════════ */

    @Test
    fun `库锁着时摆解锁`() {
        assertEquals(AutofillSaveFlow.Unlocking, phase(state = VaultSession.State.Locked))
    }

    @Test
    fun `摆着确认单被自动锁定，当场收回去`() {
        // 这一条是这个文件存在的理由。提案已经算好了（outcome 非 null），
        // 界面上正摆着账号和条目名称，此刻相位翻成 Locked——
        // 必须换屏，不能因为「手上有提案」就接着摆。
        val p = phase(state = VaultSession.State.Locked, outcome = offer())
        assertEquals(AutofillSaveFlow.Unlocking, p)
    }

    @Test
    fun `库在这中间被删掉了就安静走人`() {
        assertEquals(AutofillSaveFlow.Leaving, phase(state = VaultSession.State.NoVault))
        assertEquals(
            AutofillSaveFlow.Leaving,
            phase(state = VaultSession.State.NoVault, outcome = null),
        )
    }

    @Test
    fun `交接单取不到就安静走人`() {
        // 页面被回收后重建，SaveHandoff 已经被取过一次。
        assertEquals(AutofillSaveFlow.Leaving, phase(hasContext = false))
    }

    @Test
    fun `交接单没了盖过库开着、盖过手上那份提案`() {
        assertEquals(
            AutofillSaveFlow.Leaving,
            phase(state = unlocked, hasContext = false, outcome = offer()),
        )
    }

    @Test
    fun `存完就走，别的一概不问`() {
        assertEquals(AutofillSaveFlow.Leaving, phase(committed = true))
        assertEquals(
            AutofillSaveFlow.Leaving,
            phase(state = VaultSession.State.Locked, committed = true),
        )
        assertEquals(
            AutofillSaveFlow.Leaving,
            phase(refusal = AutofillSave.Reason.OwnUi, committed = true),
        )
    }

    /* ══════════════════════════ 三、话要说出口 ══════════════════════════ */

    @Test
    fun `不需要碰库的那四条拒绝排在库状态之前`() {
        // 反过来写等于为一件注定做不成的事，向用户要了一次主密码。
        for (r in listOf(
            AutofillSave.Reason.OwnUi,
            AutofillSave.Reason.NothingCaptured,
            AutofillSave.Reason.CannotTellPassword,
            AutofillSave.Reason.MaskedPassword,
        )) {
            val p = phase(state = VaultSession.State.Locked, refusal = r, outcome = null)
            assertTrue("$r 本该直接摆一句话", p is AutofillSaveFlow.Refused)
            assertEquals(AutofillSave.note(r), (p as AutofillSaveFlow.Refused).reason)
        }
    }

    @Test
    fun `解锁之后才算出来的那两档要说一句，不许安静关掉`() {
        // 这是这一页和挑选页唯一不一样的地方：用户刚为它解了一次锁，
        // 屏幕闪一下就没了，和坏掉没有区别。
        for (r in listOf(
            AutofillSave.Reason.AlreadyStored,
            AutofillSave.Reason.CannotTellEntry,
        )) {
            val p = phase(outcome = silent(r))
            assertTrue("$r 本该摆一句话而不是走人", p is AutofillSaveFlow.Refused)
            assertEquals(AutofillSave.note(r), (p as AutofillSaveFlow.Refused).reason)
        }
    }

    @Test
    fun `拒绝那一句一个字都不改写`() {
        val p = phase(refusal = AutofillSave.Reason.CannotTellPassword) as AutofillSaveFlow.Refused
        assertEquals(AutofillSave.note(AutofillSave.Reason.CannotTellPassword), p.reason)
    }

    @Test
    fun `每一档理由都摆得出一句话来`() {
        for (r in AutofillSave.Reason.entries) {
            val p = phase(refusal = r, outcome = null)
            assertTrue(p is AutofillSaveFlow.Refused)
            assertTrue((p as AutofillSaveFlow.Refused).reason.length > 8)
        }
    }

    /* ══════════════════════════ 四、顶栏和按钮 ══════════════════════════ */

    @Test
    fun `新增和改动在顶栏上说成两句不一样的话`() {
        val create = AutofillSave.proposeCreate(ctx(), trust)
        val update = AutofillSave.proposeUpdate(ctx(), entry(), trust)
        assertNotEquals(AutofillSaveFlow.headline(create), AutofillSaveFlow.headline(update))
    }

    @Test
    fun `改动那一行要把被改的那一条叫出来`() {
        val update = AutofillSave.proposeUpdate(ctx(), entry(name = "招商银行"), trust)
        assertTrue(AutofillSaveFlow.headline(update).contains("招商银行"))
    }

    @Test
    fun `按钮上的字也分两档`() {
        assertNotEquals(
            AutofillSaveFlow.commitLabel(AutofillSave.Mode.Create),
            AutofillSaveFlow.commitLabel(AutofillSave.Mode.Update),
        )
    }

    /* ══════════════════════════ 五、条目怎么念 ══════════════════════════ */

    @Test
    fun `名称空了退回账号，两样都空是那一句`() {
        assertEquals("示例站", AutofillSaveFlow.entryLabel(entry(name = "示例站")))
        assertEquals("zhangsan", AutofillSaveFlow.entryLabel(entry(name = "", username = "zhangsan")))
        assertEquals(
            AutofillOffer.NO_NAME,
            AutofillSaveFlow.entryLabel(entry(name = "", username = "")),
        )
    }

    @Test
    fun `没有条目时也是那一句，不是空白`() {
        assertEquals(AutofillOffer.NO_NAME, AutofillSaveFlow.entryLabel(null))
    }

    @Test
    fun `条目名称里的双向控制符被洗掉`() {
        // 这一行会被摆在一块浮在别人应用之上的窗口上。
        val label = AutofillSaveFlow.entryLabel(entry(name = "银行\u202Egnp.exe"))
        assertFalse(label.contains('\u202E'))
    }

    @Test
    fun `没有账号的条目在第二行写那一句，不写密码`() {
        assertEquals(
            AutofillOffer.NO_USERNAME,
            AutofillSaveFlow.entrySublabel(entry(username = "", password = "s3cr3t")),
        )
        assertFalse(AutofillSaveFlow.entrySublabel(entry(password = "s3cr3t")).contains("s3cr3t"))
    }

    /* ══════════════════════════ 六、名称那一栏 ══════════════════════════ */

    @Test
    fun `用户自己打的名字一个字都不改，只剔首尾空白`() {
        // 和 suggestedName 相反：那一串是被保存对象提供的，必须洗；
        // 这一串是用户在我们自己的界面上一个键一个键打的（同 EntryForm）。
        assertEquals("我的 银行", AutofillSaveFlow.finalName("  我的 银行 ", "example.com"))
        assertEquals("a  b", AutofillSaveFlow.finalName("a  b", "example.com"))
    }

    @Test
    fun `名称留空时退回建议名，不拦着不让走`() {
        assertEquals("example.com", AutofillSaveFlow.finalName("", "example.com"))
        assertEquals("example.com", AutofillSaveFlow.finalName("   ", "example.com"))
        assertEquals("example.com", AutofillSaveFlow.finalName(null, "example.com"))
    }

    @Test
    fun `建议名也空时退回未命名`() {
        assertEquals(AutofillSave.UNNAMED, AutofillSaveFlow.finalName("", ""))
        assertEquals(AutofillSave.UNNAMED, AutofillSaveFlow.finalName(null, "   "))
    }

    /* ══════════════════════════ 七、警告一句不折叠 ══════════════════════════ */

    @Test
    fun `提案里的警告原样在前，一句不少`() {
        // 不可信承载 + 新建，AutofillSave 会给一句 CREATED_FROM_UNTRUSTED
        val p = AutofillSave.proposeCreate(
            ctx(app = "com.evil.app"),
            trustOnly(),
        )
        val notes = AutofillSaveFlow.allNotes(p, FillPlan.Kind.Login)
        assertEquals(p.warnings, notes)
        assertTrue(notes.contains(AutofillSave.CREATED_FROM_UNTRUSTED))
    }

    @Test
    fun `设新密码那一屏末尾要补上看不见网站结果的那一句`() {
        val p = AutofillSave.proposeCreate(ctx(kind = FillPlan.Kind.NewCredential), trust)
        val notes = AutofillSaveFlow.allNotes(p, FillPlan.Kind.NewCredential)
        assertEquals(AutofillSave.UNVERIFIED_NOTE, notes.last())
    }

    @Test
    fun `新注册也要补那一句，尽管库里没有旧的可丢`() {
        // CHANGED_PASSWORD_NOTE 只在 Update 时出现，而「网站那边成没成功我们看不见」
        // 在一次新注册上一样成立——两句话说的不是同一件事。
        val p = AutofillSave.proposeCreate(ctx(kind = FillPlan.Kind.NewCredential), trust)
        assertFalse(p.warnings.contains(AutofillSave.CHANGED_PASSWORD_NOTE))
        assertTrue(
            AutofillSaveFlow.allNotes(p, FillPlan.Kind.NewCredential)
                .contains(AutofillSave.UNVERIFIED_NOTE),
        )
    }

    @Test
    fun `一切照常时一句废话都不说`() {
        val p = AutofillSave.proposeUpdate(ctx(), entry(), trust)
        assertTrue(AutofillSaveFlow.allNotes(p, FillPlan.Kind.Login).isEmpty())
    }

    /* ══════════════════════════ 八、两页不许说成两件事 ══════════════════════════ */

    @Test
    fun `警告那一段的小标题和挑选页共用一个`() {
        assertEquals(AutofillPickFlow.WARN_HEADING, AutofillSaveFlow.WARN_HEADING)
    }

    @Test
    fun `按钮上的字互不重样`() {
        val words = listOf(
            AutofillSaveFlow.COMMIT_CREATE,
            AutofillSaveFlow.COMMIT_UPDATE,
            AutofillSaveFlow.DISMISS,
            AutofillSaveFlow.ACKNOWLEDGE,
            AutofillSaveFlow.CHANGE_TARGET,
            AutofillSaveFlow.NOTHING_TO_CHANGE,
        )
        assertEquals(words.size, words.distinct().size)
        for (w in words) assertTrue(w.isNotBlank())
    }

    /* ══════════════════════════ 九、不吐内容 ══════════════════════════ */

    @Test
    fun `相位的toString一个内容都不吐`() {
        val c = phase(outcome = updateOffer(entry(name = "招商银行", username = "zhangsan")))
        val texts = listOf(
            c.toString(),
            AutofillSaveFlow.Refused("这是保险库自己的界面").toString(),
            AutofillSaveFlow.Unlocking.toString(),
            AutofillSaveFlow.Working.toString(),
            AutofillSaveFlow.Leaving.toString(),
        )
        for (t in texts) {
            for (secret in listOf("招商银行", "zhangsan", "s3cr3t", "example.com")) {
                assertFalse("$secret 漏进了 $t", t.contains(secret))
            }
        }
    }

    @Test
    fun `同样输入算两遍结果一样`() {
        val o = offer()
        assertEquals(
            phase(outcome = o)::class,
            phase(outcome = o)::class,
        )
        assertEquals(
            phase(state = VaultSession.State.Locked),
            phase(state = VaultSession.State.Locked),
        )
    }

    private companion object {
        const val CHROME = "com.android.chrome"
    }
}
