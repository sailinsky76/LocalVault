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
 * 保存确认页**此刻该摆哪一屏**，以及那一屏上几句由状态拼出来的话。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 提案里有什么是 [AutofillSave] 的事（75 条用例钉着），页面长什么样是
 * `AutofillSaveScreen` 的事（M4-3b-2）；这里只回答一个问题：
 * **这一屏现在该是解锁、是确认、是一句说明，还是安静地走人。**
 *
 * ── 它和 [AutofillPickFlow] 是同一类文件，但守的东西更重 ──
 *
 * 那一页摆的是"往外交什么"，这一页摆的是"往库里写什么"。多摆一帧的代价也就不一样：
 *
 *   · 用户点开这一页，看着一份提案，然后接了个电话，回来时**自动锁定已经过了**——
 *     如果这一页不跟着相位走，那一屏上还摆着他刚打的账号、他库里那条条目的名称，
 *     浮在别人的应用上面。库在会话里是锁着的，界面上却还留着一份摊开的确认单。
 *     **而且这一页手上还揣着一份明文密码**（[SaveHandoff] 交过来的那一份），
 *     所以这一相位的动作不只是换屏，还要 [SaveHandoff.clear] 一次——
 *     那正是 `SaveHandoff` 文件头第 3 条纪律。
 *   · 页面被系统回收之后重建（转屏、内存紧张），`SaveHandoff.take` 已经取过一次，
 *     第二次拿不到。这一档必须是 [Leaving] 而不是一屏空白的确认单——
 *     否则用户看到的是一个按下去什么都不会发生的"存进保险库"按钮。
 *
 * 这两条在真机上都**看不出来**：页面还在，字还在，没有任何一处会报错。
 * 写成一个 `when` 再用 [phase] 罩住，是为了让它们在没有设备的地方就能钉住。
 *
 * ── 一处和 [AutofillPickFlow] 刻意不一样的地方 ──
 *
 * 挑选页上"什么都不用做了"是 [AutofillPickFlow.Leaving]——安静关掉，不弹任何提示。
 * 这一页不行：**解锁之后才算出来的那两档拒绝，必须说一句**。
 * [AutofillSave.Reason.AlreadyStored] 和 [AutofillSave.Reason.CannotTellEntry]
 * 都只能在解锁之后才知道（决策(197)），而用户刚刚为它输了一次主密码或按了一次指纹。
 * 屏幕直接关掉的话，他对这次交互的全部印象是"我解了锁，然后它闪了一下就没了"——
 * 那和坏掉没有区别。所以那两档走 [Refused]，把 [AutofillSave.note] 那句话原样摆出来。
 */
object AutofillSaveFlow {

    /* ══════════════════════════ 相位 ══════════════════════════ */

    /** 这一页此刻该摆的五种样子。界面照着 `when` 一遍，没有第六种。 */
    sealed interface Phase

    /**
     * 整页只摆 [reason] 这一句话，一条改动都不列，按钮只剩"知道了"。
     *
     * 话是 [AutofillSave.note] / [SavePlan.note] 给的，这里一个字都不改写。
     */
    class Refused(val reason: String) : Phase {
        override fun toString(): String = "Refused"
    }

    /**
     * 库锁着（或者摆着摆着被自动锁定了）。摆解锁那两屏。
     *
     * 同 [AutofillPickFlow.Unlocking]，两种情形合成同一个相位：
     * "进来时就锁着"和"看了一半被锁上了"在用户眼里行为一致，
     * 而代码里少一个能写错的分支。
     *
     * **这一相位落到界面上时要 [SaveHandoff.clear] 一次**，见文件头第一条。
     * 清掉之后重新解锁回来会走到 [Leaving]（交接单没了），
     * 这是有意的：一份在进程里躺了超过一次锁定周期的明文密码，
     * 宁可丢掉也不留着——用户在登录框里还能再打一遍，
     * 而一份没人负责清理的明文副本是这个应用的全部前提所反对的东西。
     */
    data object Unlocking : Phase

    /**
     * 库开着，提案还没算出来。
     *
     * 这一档看起来多余（[AutofillSave.outcome] 跑得很快），但它必须存在：
     * 没有它的话，"解锁完成"到"提案算好"之间会有一帧摆着空清单的确认页，
     * 而那一帧上的按钮是可以按下去的。
     */
    data object Working : Phase

    /** 库开着，提案算好了，摆确认单。这一页真正的样子。 */
    class Confirming(val proposal: AutofillSave.Proposal) : Phase {
        override fun toString(): String = "Confirming(${proposal.mode.name})"
    }

    /**
     * 安静走人：已经存过了、交接单没了，或者这台设备上已经没有库了。
     *
     * **不弹任何提示。** 这一页浮在别人的应用上面，一条来路不明的 Toast
     * 只会让人以为是那个应用出了问题（同 [AutofillPickFlow.Leaving]）。
     */
    data object Leaving : Phase

    /**
     * 此刻该摆哪一屏。
     *
     * @param state 会话相位。**每一帧都要重新问一次**，不能只在进来时问一次——
     *   文件头第一条说的就是这个。
     * @param hasContext [SaveHandoff.take] 取到东西了没有。
     * @param refusal [AutofillSave.refuse] 的答案（不需要知道库的任何事的那三条），
     *   为 null 表示这一屏值得往下走。
     * @param outcome 解锁之后算出来的结果。还没算时传 null。
     * @param committed 已经落盘了没有。
     */
    fun phase(
        state: VaultSession.State,
        hasContext: Boolean,
        refusal: AutofillSave.Reason?,
        outcome: AutofillSave.Outcome?,
        committed: Boolean,
    ): Phase = when {
        // 存过就走，别的一概不问：此刻界面上摆什么都不再有意义，
        // 而多摆一帧确认单就是多摆一帧能被再按一次的按钮
        committed -> Leaving

        // 交接单没了（被取过、过期了、或者自动锁定时清掉了）。
        // 手上一个值都没有，摆什么都是假的
        !hasContext -> Leaving

        // 不需要知道库的任何事的那三问，排在库状态之前（同 AutofillSave.refuse 的顺序，
        // 也同 AutofillPickFlow / AutofillPick.refusal / AutofillOffer.respond）。
        // 反过来写等于为一件注定做不成的事，向用户要了一次主密码
        refusal != null -> Refused(AutofillSave.note(refusal))

        // 库在这中间被删掉了（另一个入口做的）。没有地方可存，也没有什么可说的
        state is VaultSession.State.NoVault -> Leaving

        state is VaultSession.State.Locked -> Unlocking

        outcome == null -> Working

        // 解锁之后才算得出来的那两档，必须说一句而不是安静关掉——见文件头末段
        outcome is AutofillSave.Outcome.Silent -> Refused(AutofillSave.note(outcome.reason))

        outcome is AutofillSave.Outcome.Offer -> Confirming(outcome.proposal)

        else -> Leaving
    }

    /* ══════════════════════════ 由状态拼出来的那几句 ══════════════════════════ */

    /**
     * 顶栏那一行：这一按到底是新长一条，还是动一条已有的。
     *
     * 两句话分得很开是有意的。系统自带的那个保存框只说"保存密码？"，
     * 按下去之后发生了什么用户是看不见的——而"新增一条"和
     * "把某一条的密码换掉"是两件代价差着一个数量级的事（[AutofillSave] 文件头）。
     * 这一行是用户在按下按钮之前，唯一一眼就能分辨这两者的地方。
     */
    fun headline(proposal: AutofillSave.Proposal): String = when (proposal.mode) {
        AutofillSave.Mode.Create -> HEADLINE_CREATE
        AutofillSave.Mode.Update -> "$HEADLINE_UPDATE_PREFIX${entryLabel(proposal.target)}"
    }

    /** 按钮上的字。同样按档分开，理由同 [headline]。 */
    fun commitLabel(mode: AutofillSave.Mode): String = when (mode) {
        AutofillSave.Mode.Create -> COMMIT_CREATE
        AutofillSave.Mode.Update -> COMMIT_UPDATE
    }

    /**
     * "换一条"那个清单里，一条条目怎么念。
     *
     * 名称退回账号，再空就是 [AutofillOffer.NO_NAME]——**直接调
     * [AutofillOffer.labelOf]，不抄一份过来**。那个函数当初从 `private` 放宽到
     * `internal` 就是为了挑选页复用它，理由写在它头上：抄一份的后果是
     * 某天两处不一样了，同一条条目在填充条上写着账号、在这一页上写着
     * 「（这一条没有名称）」，而用户会以为那是两条。
     *
     * 洗那一道照做（同 [AutofillResponses.picked] 那段：凡是交出去的字都先洗过）——
     * 这一行会被摆在一块浮在别人应用之上的窗口上，而条目名称是用户自己打的，
     * 也可能是从别处导进来的。
     */
    fun entryLabel(entry: VaultEntry?): String {
        if (entry == null) return AutofillOffer.NO_NAME
        return AutofillRow.clean(AutofillOffer.labelOf(entry), AutofillPick.MAX_LABEL)
            .ifEmpty { AutofillOffer.NO_NAME }
    }

    /** 那个清单里第二行的账号。没有账号时是 [AutofillOffer.NO_USERNAME]。 */
    fun entrySublabel(entry: VaultEntry): String =
        AutofillRow.clean(entry.username, AutofillPick.MAX_SUBLABEL)
            .ifEmpty { AutofillOffer.NO_USERNAME }

    /**
     * 名称栏最后落进库里的那一串。
     *
     * **用户自己打的名字一个字都不洗。** 这和 [AutofillSave.suggestedName] 相反，
     * 而两处相反是有意的：那一串是**被保存对象提供的**（应用名、主机名），
     * 里面可能塞着 `U+202E` 让它倒着画出来（决策(184)/(188)），所以必须洗；
     * 这一串是用户坐在我们自己的界面上一个键一个键打的，
     * 和他在 `EntryForm` 里给条目起名字是同一件事——
     * 那一页也不洗，替他改掉他打的字才是这一栏上唯一会让他吃惊的行为（同决策(56)）。
     *
     * 只 [String.trim] 首尾空白：那一头的空白几乎总是键盘带进来的，
     * 而名称不是凭据，多一个前导空格的唯一后果是列表里排序看着怪
     * （对比 [SavedFields.capture] 里密码那一头一个字符都不动的理由）。
     *
     * 空的时候退回建议名，建议名也空时退回 [AutofillSave.UNNAMED]——
     * **不给"名称不能为空"这种拦截**：用户此刻站在一个正等着他登录的表单前面，
     * 为了一个他随时能回保险库改的标签把他挡在这儿，是把人送进一条死路
     * （同 [AutofillPickFlow.NO_RESULTS] 那段不给"新增一条"出口的理由）。
     */
    fun finalName(typed: String?, suggested: String): String {
        val t = typed?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        return suggested.ifBlank { AutofillSave.UNNAMED }
    }

    /**
     * 那三档非自动的警告 + 保存这一侧独有的两句，界面上**一句都不许折叠**。
     *
     * 这个函数只是把 [AutofillSave.Proposal.warnings] 原样传出来，外加改密码那一屏
     * 末尾要补的 [AutofillSave.UNVERIFIED_NOTE]。之所以还要有它，
     * 是因为那一句的出现条件（"这一屏是在设新密码"）和 `warnings` 里
     * [AutofillSave.CHANGED_PASSWORD_NOTE] 的出现条件（还要求是 `Update`）不一样：
     * 一次**新注册**也是在设新密码，那时候库里旧的没什么可丢，
     * 但"网站那边到底成没成功，我们看不见"这句话照样成立。
     */
    fun allNotes(proposal: AutofillSave.Proposal, kind: FillPlan.Kind): List<String> {
        val out = ArrayList<String>(proposal.warnings.size + 1)
        out += proposal.warnings
        if (kind == FillPlan.Kind.NewCredential) out += AutofillSave.UNVERIFIED_NOTE
        return out
    }

    /* ══════════════════════════ 成句的那几条 ══════════════════════════ */

    /** 顶栏那一行提示。它要说清楚"这一页不是保险库本身"。 */
    const val TITLE = "存进保险库？"

    const val HEADLINE_CREATE = "会在保险库里新长出一条"
    const val HEADLINE_UPDATE_PREFIX = "会改动已有的这一条："

    const val COMMIT_CREATE = "新增这一条"
    const val COMMIT_UPDATE = "按上面改"

    /** 不存。**不写"取消"**：取消听起来像撤销一次误操作，而这是一个正当的选择。 */
    const val DISMISS = "这次不存"

    /** [Refused] 那一屏上唯一的按钮。 */
    const val ACKNOWLEDGE = "知道了"

    /** 逐条改动那一段的小标题。 */
    const val CHANGES_HEADING = "会改成这样"

    /**
     * 必须先看的话那一段的小标题。
     *
     * **和挑选页共用一个常量**（[AutofillPickFlow.WARN_HEADING]）：
     * 同一件事在两页上说成两个样子，用户会以为那是两件事。
     */
    const val WARN_HEADING = AutofillPickFlow.WARN_HEADING

    /** 名称那一栏的标签和灰字。 */
    const val NAME_LABEL = "名称"
    const val NAME_HINT = "在保险库列表里，这一条叫什么"

    /** "换一条"那个入口。 */
    const val CHANGE_TARGET = "换一条来改"
    const val CHANGE_TARGET_HEADING = "这个站你还存着这几条"

    /**
     * 一条备选都没有时那一句。
     *
     * 它只在用户主动点开"换一条"之后才出现——[AutofillSave.Proposal.alternatives]
     * 是空的时候那个入口本来就不该画出来。留这一句是为了那种擦肩而过的时序：
     * 他点开的那一瞬间，另一个窗口刚把最后一条删掉了。
     */
    const val NO_ALTERNATIVES = "这个站在保险库里只有正在改的这一条。"

    /**
     * 提案里一条改动都没有时，按钮上的字。
     *
     * 正常路径到不了这儿（[AutofillSave.outcome] 会把它变成
     * [AutofillSave.Reason.AlreadyStored] 然后安静走人），
     * 但用户在确认页上**自己换过一条**之后可以到：换过去那一条恰好一模一样。
     * 那时候按钮要画成禁用而不是藏起来（同决策(174)），并说明为什么。
     */
    const val NOTHING_TO_CHANGE = "这一条不用改"

    const val NOTHING_TO_CHANGE_NOTE =
        "你换过去的这一条，账号和密码跟你刚才用的一模一样，按下去什么都不会变。"
}
