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

package cn.localvault.app.ui.autofill

import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultEntry

/**
 * 「这一次填充请求，屏幕上到底该出现什么」。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * `AssistStructure` 摊成 [FillContext] 是 [AssistShell] 的事，
 * 一屏框算成 [FillPlan.Plan] 是 [FillPlan] 的事，从整库里挑候选是 [AutofillMatch] 的事；
 * 这里只把它们串起来，回答最后那一个问题——**填充条上出现哪几行、每一行写什么。**
 *
 * M4-2a-2 那个 `AutofillService` 拿到 [Response] 之后要做的事只剩三件：
 * 把 [Item.writes] 里的句柄换成 `AutofillId`、把 [Item.label] / [Item.sublabel]
 * 塞进 `RemoteViews`、给 [Response.Unlock] 配一个 `IntentSender`。
 * **一行判断都不用再做**——这一层的每一条规则都在用例里钉着。
 *
 * ── 三条底线 ──
 *
 * **一、填充条上永远没有密码。** [Item] 里根本没有能放它的字段：
 * 要写下去的值封在 [Item.writes] 里（`FillPlan.Write.toString` 不吐值，决策(144)），
 * 而给人看的那两行只有名称和账号。系统的填充条会被**输入法和无障碍服务看见**，
 * 也会进系统的截屏与录屏——那是一块公共屏幕，不是保险库里面。
 *
 * **二、每一个表单各判一次归属，判不过的一个字都不写。** 见 [writesFor]。
 *
 * **三、一条点下去什么都不会发生的填充项，不如不出现。** [Item.writes] 为空的
 * 一律丢掉（决策(174) 在这一层的兑现）。
 */
object AutofillOffer {

    /* ══════════════════════════ 出什么 ══════════════════════════ */

    /** 这一次请求的答复。三种，界面（M4-2a-2 / M4-2b）照着摆就行。 */
    sealed interface Response

    /**
     * 什么都不出。**这不是失败**，是四种各自成立的处境（[Why]）。
     *
     * 界面上不需要为它做任何事——系统的填充条不出现，用户该干什么干什么。
     * 带着 [why] 是为了 M4-4 那句「自动填充为什么有时候不出现」
     * 能在关于页上说清楚，以及为了日志里那一行**只有原因、没有内容**。
     */
    class Silent(val why: Why) : Response {
        override fun toString(): String = "Silent(${why.name})"
    }

    enum class Why {
        /** 这一屏上没有认得出来的账号框或密码框。最常见的一种，多数应用的多数屏都是。 */
        NoFillableField,

        /** 这是本应用自己的界面。见 [respond]。 */
        OwnUi,

        /** 这台设备上还没有保险库。 */
        NoVault,
    }

    /**
     * 出一条「先解锁」。点下去拉起解锁页（`IntentSender`），解锁完系统会再问一次。
     *
     * **锁着的时候，填充条上一个字的库内容都没有**——连「这个网站存了 3 条」
     * 都不说。不是不肯说，是**答不上来**：条目在库文件里，库文件是密文，
     * 没有主密钥就数不出条数来。这一点值得写在文案里（[UNLOCK_NOTE]），
     * 因为它正是这个应用和那些「锁屏时也能预览」的管理器的区别。
     */
    object Unlock : Response {
        override fun toString(): String = "Unlock"
    }

    /**
     * 出 n 条候选，外加末尾那条「在保险库里搜索」。
     *
     * **[items] 是空的也照样是 [Offer]，不是 [Silent]。** 那条搜索入口是
     * 决策(160)（「绝不自动建议」和「不许手动挑」是两件事）的落点：
     * 这个站没有自动够格的条目，不等于用户手上没有能用的条目
     * （`NoEvidence` / `UntrustedHost` 那几档都在这儿）。
     * 何况一个空荡荡的填充条，用户唯一的结论是「这功能坏了」——
     * 而它恰恰是在保护他。
     */
    class Offer(
        val items: List<Item>,
        /** 够格但被 [AutofillMatch.MAX_SUGGESTIONS] 截掉的条数。写进搜索那一行。 */
        val hidden: Int,
        /** 这一屏要不要先说一句（新密码栏留空了之类）。来自 [FillPlan.kindNote]。 */
        val note: String?,
    ) : Response {
        override fun toString(): String = "Offer(${items.size} items, hidden=$hidden)"
    }

    /**
     * 填充条上的一行。
     *
     * **两行给人看的文字里没有密码，也没有网址。**
     * 名称和账号是用户自己写下的、用来认出「哦，是这一条」的东西；
     * 密码不在这儿（底线一），[badge] 那一行只在兄弟域时出现，说的是他自己存过的那个域名。
     */
    class Item internal constructor(
        /** 条目 id。M4-2a-2 只拿它做日志之外的关联，不显示。 */
        val entryId: String,
        /** 第一行：条目名称。空名称的条目退回账号，再空就是 [NO_NAME]。 */
        val label: String,
        /** 第二行：账号。没有账号的条目显示 [NO_USERNAME]，**不显示密码**。 */
        val sublabel: String,
        /**
         * 第三行，只在兄弟域（[DomainMatch.Verdict.SameSite]）时出现：
         * 「你存的是 mail.example.com」。
         *
         * 决策(159) 的第二道兜底就是它：公共后缀表不可能永远全，
         * 表错一条的后果在这一行上能被用户一眼看见。所以**这一行不许省**，
         * 也不许和精确档混在一起显示。
         */
        val badge: String?,
        /** 真正要写下去的东西。句柄要拿 [AssistShell.Parsed.autofillId] 换。 */
        val writes: List<FillPlan.Write>,
    ) {
        override fun toString(): String = "Item(${writes.size} writes)"
    }

    /* ══════════════════════════ 判 ══════════════════════════ */

    /**
     * 一次请求的全部判断。
     *
     * **顺序是有意的，而且前两道排在库状态前面：**
     *
     *   1. 这一屏有没有能填的框——这一问不需要知道库的任何事，
     *      也就不会因为回答它而泄露任何事；
     *   2. 这是不是本应用自己的界面——见下；
     *   3. 有没有库 → 锁没锁 → 挑候选。
     *
     * ── 为什么自己的界面上不出填充条 ──
     *
     * 拿自己的密码填自己的解锁页，先不说荒唐：那一屏是 `FLAG_SECURE` 的，
     * 而填充条是**系统进程**画的，不受这个标记管；更要紧的是自动锁定那套相位
     * （切后台就锁）和「系统为了画填充条把我们的界面推到后台」这件事会打起架来。
     * 这一条也顺手挡住了系统设置里挑默认填充服务时那一屏。
     *
     * [selfPackage] 由调用方传（`BuildConfig.APPLICATION_ID`），不在这儿写死——
     * 写死一个字符串常量，改包名那天没有人会记得回来改它。
     */
    fun respond(
        state: VaultSession.State,
        plan: FillPlan.Plan,
        trust: HostTrust,
        selfPackage: String,
    ): Response {
        val primary = plan.primary ?: return Silent(Why.NoFillableField)
        if (primary.origin.hostApp == selfPackage) return Silent(Why.OwnUi)

        return when (state) {
            is VaultSession.State.NoVault -> Silent(Why.NoVault)
            is VaultSession.State.Locked -> Unlock
            is VaultSession.State.Unlocked -> offer(primary, plan, state.data.entries, trust)
        }
    }

    private fun offer(
        primary: FillPlan.Form,
        plan: FillPlan.Plan,
        entries: List<VaultEntry>,
        trust: HostTrust,
    ): Offer {
        val suggestions = AutofillMatch.suggest(primary.origin, entries, trust)
        val items = ArrayList<Item>(suggestions.shown.size)
        for (c in suggestions.shown) {
            val writes = writesFor(plan, c.entry, trust)
            if (writes.isEmpty()) continue // 底线三
            items += Item(
                entryId = c.entry.id,
                label = labelOf(c.entry),
                sublabel = c.entry.username.ifEmpty { NO_USERNAME },
                badge = if (c.verdict == DomainMatch.Verdict.SameSite && c.matchedDomain != null) {
                    "你存的是 ${c.matchedDomain}"
                } else {
                    null
                },
                writes = writes,
            )
        }
        return Offer(items, suggestions.hidden, FillPlan.kindNote(primary.kind))
    }

    /**
     * 这一条条目，在这一屏上到底往哪几个框写。
     *
     * ── 底线二：每一个表单各判一次归属 ──
     *
     * 一次 `Dataset` 能把同屏好几组框一起写好（决策(175) 就是为此保留所有表单的），
     * 顺手的写法是「主表单判过了，那就照 [FillPlan.Plan.forms] 全写一遍」。
     * 那是错的，而且错法和 AutoSpill 同源：同一屏上完全可能一组是
     * `example.com` 的 iframe、另一组是承载它的应用自己的原生框
     * （[FieldGroups] 已经把它们切成两组、各算各的 [Origin] 了）。
     * 主表单判过的是**它自己那一组**的归属，不是另一组的。
     * 全写一遍，密码就顺着第二组流进了不该去的地方——
     * 前面三个文件小心翼翼守住的东西，在这最后一行上全漏光。
     *
     * 所以这里对**每一组**重新问一次 [DomainMatch.best]，只有够格自动填的那两档
     * （`Exact` / `SameSite`）才写。判不过的组一个字都不写，
     * 而且**不为此说什么**——用户没要求往那一组填，那一组安静地空着才是对的。
     */
    fun writesFor(
        plan: FillPlan.Plan,
        entry: VaultEntry,
        trust: HostTrust,
    ): List<FillPlan.Write> {
        val out = ArrayList<FillPlan.Write>(2)
        for (form in plan.forms) {
            if (form.isEmpty) continue
            if (!DomainMatch.best(form.origin, entry.domains, trust).verdict.canAutoFill) continue
            out += FillPlan.writes(form, entry)
        }
        return out
    }

    /**
     * 第一行显示什么。
     *
     * 名称是用户自己起的，绝大多数条目都有；空名称的条目照样合法
     * （导入进来的行可能只有账号，决策(149)），那时退回账号——
     * 退回**账号**而不是网址：网址那一行是我们匹配出来的，
     * 而这一行的用途是让用户认出「这是我那一条」。
     *
     * `internal` 而不是 `private`：M4-2b 的挑选页要用**同一条**规则
     * （[AutofillPick.row]）。抄一份过去的后果是某天两处不一样了——
     * 填充条上写着账号、挑选页上写着「（这一条没有名称）」，指的却是同一条。
     */
    internal fun labelOf(e: VaultEntry): String =
        e.name.ifBlank { e.username.ifBlank { NO_NAME } }

    /* ══════════════════════════ 话怎么说 ══════════════════════════ */

    const val NO_NAME = "（这一条没有名称）"
    const val NO_USERNAME = "（这一条没有存账号）"

    const val UNLOCK_LABEL = "解锁本地保险库"

    /**
     * 「先解锁」那一条底下的小字。
     *
     * 它说的是一件**技术事实**，不是一句安抚：锁着的时候库文件是密文，
     * 这个应用连「这个网站存了几条」都数不出来。写出来是因为
     * 用户对着一条什么都不显示的填充项，第二反应通常是「它是不是坏了」。
     */
    const val UNLOCK_NOTE = "锁着的时候，连这个网站存了几条都数不出来"

    /** 末尾那一行。[hidden] 是被截掉的条数，0 时不提。 */
    fun searchLabel(hidden: Int): String =
        if (hidden > 0) "在保险库里搜索（还有 $hidden 条）" else "在保险库里搜索…"

    /**
     * [Silent] 那四种处境各自的说法。M4-4 的关于页要把它们摆出来，
     * 回答那句「自动填充为什么有时候不出现」。
     *
     * 没有一句把它说成故障（不出现「失败 / 出错 / 稍后重试」）——
     * 三种都是这个应用**故意**不出手的时刻。
     */
    fun whyNote(why: Why): String = when (why) {
        Why.NoFillableField ->
            "这一屏上没有认得出来的账号框或密码框。认不出来的时候宁可不填——" +
                "把密码填进一个不知道是干什么的框里，比不填危险得多。"

        Why.OwnUi ->
            "这是保险库自己的界面，不会往自己身上填。"

        Why.NoVault ->
            "这台设备上还没有保险库。先建一个，或者从备份恢复一份。"
    }
}
