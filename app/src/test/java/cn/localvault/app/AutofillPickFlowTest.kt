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
import cn.localvault.app.ui.autofill.AutofillPick
import cn.localvault.app.ui.autofill.AutofillPickFlow
import cn.localvault.app.ui.autofill.AutofillRow
import cn.localvault.app.ui.autofill.FillContext
import cn.localvault.app.ui.autofill.FillPlan
import cn.localvault.app.ui.autofill.HostTrust
import cn.localvault.app.ui.autofill.Origin
import cn.localvault.app.ui.autofill.RawField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挑选页**此刻该摆哪一屏**，以及那一屏上几句由状态拼出来的话。
 *
 * 这一层守的东西只有一件，但它是这一整页上唯一一件在真机上**看不出来**的：
 *
 *   用户点开挑选页，摊开一屏条目，然后接了个电话；回来时自动锁定已经过了。
 *   如果这一页不跟着相位走，那一屏清单就一直摆在别人的应用上面——
 *   库在会话里是锁着的，界面上却还留着一份摊开的资产目录。
 *   页面还在，字还在，一切正常，没有任何一处会报错。
 *
 * 第二节那几条就是钉这个的：把 `phase` 里 `Locked -> Unlocking` 那一行删掉，
 * 它们立刻红掉，而真机上要复现得等上五分钟并且盯着一屏没有变化的界面看。
 */
class AutofillPickFlowTest {

    private var seq = 0L

    private fun f(hint: String?, web: String? = null): RawField =
        RawField(
            handle = seq++,
            autofillHints = if (hint == null) emptyList() else listOf(hint),
            webDomain = web,
        )

    private fun plan(app: String, vararg fields: RawField): FillPlan.Plan =
        FillPlan.forRequest(FillContext(activityPackage = app, fields = fields.toList()))

    private fun webLogin(host: String = "example.com", app: String = CHROME) =
        plan(app, f("username", host), f("password", host))

    /**
     * 一屏**没有可填的框**：只有一个验证码框。
     *
     * 用验证码框而不是「一个什么提示都没有的框」，是为了和 `AutofillPickTest`
     * 里那几条用同一个夹具——那边已经钉住了这一屏会得到 `REFUSE_NO_FORM`。
     * 换一个夹具就等于在这儿又赌一次「角色识别会怎么判」，而那不是这个文件该管的事。
     */
    private fun blankScreen() = plan(CHROME, f("smsOTPCode", "example.com"))

    private val unlocked: VaultSession.State =
        VaultSession.State.Unlocked(VaultData(entries = emptyList()))

    private val trust: HostTrust = object : HostTrust {
        override fun isTrustedBrowser(packageName: String) = packageName == CHROME
    }

    private fun refusalOf(p: FillPlan.Plan) = AutofillPick.refusal(p, SELF)

    /* ══════════════ 一、正常那条路 ══════════════ */

    @Test
    fun `库开着就摆清单`() {
        val phase = AutofillPickFlow.phase(unlocked, refusalOf(webLogin()), delivered = false)
        assertTrue(phase is AutofillPickFlow.Picking)
    }

    @Test
    fun `进来时库锁着，先解锁`() {
        val phase = AutofillPickFlow.phase(
            VaultSession.State.Locked,
            refusalOf(webLogin()),
            delivered = false,
        )
        assertTrue(phase is AutofillPickFlow.Unlocking)
    }

    /* ══════════════ 二、摆着摆着被自动锁定 ══════════════ */

    /**
     * **这一整个文件存在的理由。**
     *
     * 相位是每一帧重新算的，所以「进来时开着、后来锁上了」和「进来时就锁着」
     * 落在同一个分支上——清单当场收起来，换成解锁屏。
     */
    @Test
    fun `摆着清单时被自动锁定，当场收起来`() {
        val refusal = refusalOf(webLogin())

        val before = AutofillPickFlow.phase(unlocked, refusal, delivered = false)
        assertTrue("先得真的在摆清单", before is AutofillPickFlow.Picking)

        val after = AutofillPickFlow.phase(VaultSession.State.Locked, refusal, delivered = false)
        assertTrue("锁上之后不许还是 Picking", after is AutofillPickFlow.Unlocking)
    }

    @Test
    fun `解开之后回到清单，不是回到一片空白`() {
        val refusal = refusalOf(webLogin())
        val locked = AutofillPickFlow.phase(VaultSession.State.Locked, refusal, delivered = false)
        assertTrue(locked is AutofillPickFlow.Unlocking)

        val again = AutofillPickFlow.phase(unlocked, refusal, delivered = false)
        assertTrue(again is AutofillPickFlow.Picking)
    }

    /* ══════════════ 三、整页不该出现 ══════════════ */

    @Test
    fun `这一屏没有可填的框，只摆一句话`() {
        val refusal = refusalOf(blankScreen())
        assertNotNull("前提：refusal 得真的说话了", refusal)

        val phase = AutofillPickFlow.phase(unlocked, refusal, delivered = false)
        assertTrue(phase is AutofillPickFlow.Refused)
        assertEquals(AutofillPick.REFUSE_NO_FORM, (phase as AutofillPickFlow.Refused).reason)
    }

    @Test
    fun `是保险库自己的界面，也只摆一句话`() {
        val refusal = refusalOf(plan(SELF, f("username"), f("password")))
        val phase = AutofillPickFlow.phase(unlocked, refusal, delivered = false)
        assertEquals(
            AutofillPick.REFUSE_OWN_UI,
            (phase as AutofillPickFlow.Refused).reason,
        )
    }

    /**
     * **顺序那一条。** 拒绝排在库状态之前，理由同 `AutofillOffer.respond`：
     * 那一问不需要知道库的任何事。反过来写的后果是——为一件注定做不成的事，
     * 先向用户要了一次主密码。
     */
    @Test
    fun `库锁着但这一屏本来就没法填，不要主密码，直接说明白`() {
        val phase = AutofillPickFlow.phase(
            VaultSession.State.Locked,
            refusalOf(blankScreen()),
            delivered = false,
        )
        assertTrue("不许先弹解锁屏", phase is AutofillPickFlow.Refused)
    }

    @Test
    fun `拒绝的那句话原样传出来，一个字不改写`() {
        val refusal = refusalOf(blankScreen())!!
        val phase = AutofillPickFlow.phase(unlocked, refusal, delivered = false) as AutofillPickFlow.Refused
        assertEquals(refusal, phase.reason)
    }

    /* ══════════════ 四、走人的两种 ══════════════ */

    @Test
    fun `交过答卷就走，别的一概不问`() {
        assertTrue(
            AutofillPickFlow.phase(unlocked, null, delivered = true) is AutofillPickFlow.Leaving,
        )
    }

    /** 交过之后连「这一屏没法填」都不再说——此刻界面上摆什么都不再有意义。 */
    @Test
    fun `交过答卷之后，拒绝那一句也不摆了`() {
        val phase = AutofillPickFlow.phase(unlocked, refusalOf(blankScreen()), delivered = true)
        assertTrue(phase is AutofillPickFlow.Leaving)
    }

    @Test
    fun `库在这中间被删掉了，安静走人`() {
        val phase = AutofillPickFlow.phase(
            VaultSession.State.NoVault,
            refusalOf(webLogin()),
            delivered = false,
        )
        assertTrue(phase is AutofillPickFlow.Leaving)
    }

    /** 没有库、而且这一屏本来也没法填：先说那句不需要知道库的话。 */
    @Test
    fun `没有库时，拒绝那一句仍然排在前面`() {
        val phase = AutofillPickFlow.phase(
            VaultSession.State.NoVault,
            refusalOf(blankScreen()),
            delivered = false,
        )
        assertTrue(phase is AutofillPickFlow.Refused)
    }

    /* ══════════════ 五、四种相位互不重样 ══════════════ */

    @Test
    fun `四种相位各自到得了`() {
        val ok = refusalOf(webLogin())
        val no = refusalOf(blankScreen())
        val seen = setOf(
            AutofillPickFlow.phase(unlocked, ok, false)::class,
            AutofillPickFlow.phase(VaultSession.State.Locked, ok, false)::class,
            AutofillPickFlow.phase(unlocked, no, false)::class,
            AutofillPickFlow.phase(unlocked, ok, true)::class,
        )
        assertEquals(4, seen.size)
    }

    /* ══════════════ 六、会填哪几格 ══════════════ */

    @Test
    fun `两格都填时两个词都出现`() {
        val line = AutofillPickFlow.slotsLine(
            listOf(FillPlan.Slot.Username, FillPlan.Slot.Password),
        )!!
        assertTrue(line.startsWith(AutofillPickFlow.SLOTS_PREFIX))
        assertTrue(line.contains("账号"))
        assertTrue(line.contains("密码"))
    }

    @Test
    fun `只填一格就只说一个词`() {
        val line = AutofillPickFlow.slotsLine(listOf(FillPlan.Slot.Password))!!
        assertTrue(line.contains("密码"))
        assertFalse(line.contains("账号"))
    }

    /** 同一个格位出现两次（同屏两个密码框）不该说两遍「密码、密码」。 */
    @Test
    fun `重复的格位只说一遍`() {
        val line = AutofillPickFlow.slotsLine(
            listOf(FillPlan.Slot.Password, FillPlan.Slot.Password),
        )!!
        assertEquals(1, line.split("密码").size - 1)
    }

    /** 空清单由 `Choice.blocked` 那一整句说话，这儿不补一句短的和它撞车。 */
    @Test
    fun `一格都不填时这一行整个不出现`() {
        assertNull(AutofillPickFlow.slotsLine(emptyList()))
    }

    /**
     * 决策(144)：**只有格位，没有值**。
     *
     * 这一条其实是类型保证（`Slot` 是个枚举，里面没有能放值的地方），
     * 钉一条是为了有人哪天想把 `Write` 传进来时，先被这一条挡一下。
     */
    @Test
    fun `这一行里不会出现任何一个值`() {
        val line = AutofillPickFlow.slotsLine(
            listOf(FillPlan.Slot.Username, FillPlan.Slot.Password),
        )!!
        assertFalse(line.contains(PASSWORD))
        assertFalse(line.contains("zhangsan"))
    }

    /* ══════════════ 七、第一段的小标题 ══════════════ */

    @Test
    fun `网页那一屏说「这个网站」`() {
        assertEquals(
            AutofillPickFlow.SECTION_THIS_SITE,
            AutofillPickFlow.siteSectionTitle(Origin.Web("example.com", CHROME)),
        )
    }

    /** 在一个应用里看到「这个网站」是一句能让人愣一下的话。 */
    @Test
    fun `原生那一屏说「这个应用」`() {
        assertEquals(
            AutofillPickFlow.SECTION_THIS_APP,
            AutofillPickFlow.siteSectionTitle(Origin.App("com.tencent.mm")),
        )
    }

    @Test
    fun `没有主表单时不抛异常`() {
        assertNotNull(AutofillPickFlow.siteSectionTitle(null))
    }

    /* ══════════════ 八、几句文案自身 ══════════════ */

    /**
     * 这一页上的每一句都不是在描述故障——它们描述的是几种我们**故意**
     * 不自动出手的处境。说成故障的后果是用户去找「重试」，而没有重试可找。
     */
    @Test
    fun `没有一句把它说成故障`() {
        val bad = listOf("失败", "出错", "错误", "稍后重试", "异常")
        val all = listOf(
            AutofillPickFlow.TITLE,
            AutofillPickFlow.NO_RESULTS,
            AutofillPickFlow.CONFIRM,
            AutofillPickFlow.BACK,
            AutofillPickFlow.WARN_HEADING,
            AutofillPickFlow.CANNOT_FILL,
            AutofillPickFlow.SEARCH_HINT,
            AutofillPickFlow.SECTION_THIS_SITE,
            AutofillPickFlow.SECTION_THIS_APP,
            AutofillPickFlow.SECTION_RECENT,
            AutofillPickFlow.SECTION_RESULTS,
        )
        for (s in all) {
            for (b in bad) {
                assertFalse("「$s」里不该出现「$b」", s.contains(b))
            }
        }
    }

    /**
     * 搜不到时**不给「新增一条」那个出口**（搜索页上有）。
     *
     * 那一页在应用里，用户坐下来在建条目；这一页浮在一个正等着他登录的表单上面。
     * 让他此刻去走一遍新增三步流，回来时这次填充会话早就没了。
     */
    @Test
    fun `搜不到时不把人支去新增条目`() {
        assertFalse(AutofillPickFlow.NO_RESULTS.contains("新增"))
        assertFalse(AutofillPickFlow.NO_RESULTS.contains("创建"))
    }

    @Test
    fun `四句成句的文案互不重样`() {
        val all = listOf(
            AutofillPickFlow.SECTION_THIS_SITE,
            AutofillPickFlow.SECTION_THIS_APP,
            AutofillPickFlow.SECTION_RECENT,
            AutofillPickFlow.SECTION_RESULTS,
        )
        assertEquals(all.size, all.toSet().size)
    }

    /* ══════════════ 九、交回去那一份的两行字 ══════════════ */

    private fun pickRow(
        name: String = "示例站",
        username: String = "zhangsan",
    ): AutofillPick.Row = AutofillPick.row(
        VaultEntry(
            id = "id-1",
            name = name,
            username = username,
            password = PASSWORD,
            domains = listOf("example.com"),
        ),
        webLogin(),
        trust,
    )

    @Test
    fun `交回去那一份也是两行字，没有密码`() {
        val row = AutofillRow.forPick(pickRow())
        assertEquals("示例站", row.title)
        assertEquals("zhangsan", row.subtitle)
        assertFalse(row.title.contains(PASSWORD))
        assertFalse(row.subtitle.contains(PASSWORD))
    }

    /** 兄弟域那个标记的用途是让用户**挑之前**看清，这一份是他挑完之后的回执。 */
    @Test
    fun `交回去那一份不带标记`() {
        assertNull(AutofillRow.forPick(pickRow()).badge)
    }

    /**
     * 再洗一遍是**幂等**的。这一条钉的是「洗两遍不会洗出第二种结果」——
     * 有了它，`forPick` 里那一句 `clean` 就可以放心留着，
     * 而它留着的意义是：某天有人换掉挑选页那一侧的洗法，这条路不会悄悄变成没洗过的。
     */
    @Test
    fun `再洗一遍不会洗出第二种结果`() {
        val dirty = "招商\u202E银行\n\n储蓄"
        val once = AutofillPick.row(
            VaultEntry(
                id = "id-2",
                name = dirty,
                username = "u",
                password = PASSWORD,
                domains = listOf("example.com"),
            ),
            webLogin(),
            trust,
        )
        val twice = AutofillRow.forPick(once)
        assertEquals(once.label, twice.title)
        assertFalse(twice.title.contains("\u202E"))
        assertFalse(twice.title.contains("\n"))
    }

    @Test
    fun `名称和账号都空时，两行退回那两句现成的话`() {
        val row = AutofillRow.forPick(pickRow(name = "", username = ""))
        assertEquals(AutofillOffer.NO_NAME, row.title)
        assertEquals(AutofillOffer.NO_USERNAME, row.subtitle)
    }

    private companion object {
        const val CHROME = "com.android.chrome"

        /** 和 `BuildConfig.APPLICATION_ID` 对齐；纯 JVM 测试里不引 BuildConfig。 */
        const val SELF = "cn.localvault.app"

        const val PASSWORD = "hunter2-correct-horse"
    }
}
