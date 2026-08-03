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

/**
 * 挑选页**此刻该摆哪一屏**，以及那一屏上几句由状态拼出来的话。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 摆什么内容是 [AutofillPick] 的事（71 条用例钉着），页面长什么样是
 * [AutofillPickScreen] 的事；这里只回答一个问题：**这一屏现在该是解锁、
 * 是清单、是一句拒绝，还是安静地走人。**
 *
 * ── 为什么这几行判断值得单独一个文件 ──
 *
 * 因为它们全都错得**不报错**。这一页是一块浮在别人应用之上的浮窗，
 * 它的入口是一个 `IntentSender`——那个东西一旦发出去，就不由我们决定
 * 什么时候被点、被谁点、带着哪一屏的结构被点。于是：
 *
 *   · 用户点开这一页，摊开一屏条目，然后接了个电话，回来时**自动锁定已经过了**——
 *     如果这一页不跟着相位走，那一屏清单就一直摆在别人的应用上面。
 *     库在会话里是锁着的，界面上却还留着一份摊开的资产目录。
 *     这一条在应用里怎么点都试不出来（应用里锁定会整个换掉导航图），
 *     它只在这一页上成立，而且**看不出来**——页面还在，字还在，一切正常。
 *   · 答卷交过一次之后再交第二次（重组、或者 `LaunchedEffect` 又跑了一遍），
 *     系统那边收到的是一次已经结束的会话的结果。
 *   · 库在这中间被另一个入口删掉了。
 *
 * 把它们写成一个 `when`，再用 [phase] 这一个纯函数罩住，是为了让上面那三条
 * **在没有设备的地方就能钉住**。Activity 那边于是只剩「照相位摆屏」。
 *
 * ── 判断的顺序是有讲究的（同 [AutofillPick.refusal] / [AutofillOffer.respond]）──
 *
 * [refusal] 排在库状态**之前**。理由和那两处一模一样：
 * 「这一屏有没有能填的框」「是不是我们自己的界面」这两问**不需要知道库的任何事**，
 * 也就不会因为回答它们而泄露任何事（决策(180)）。
 * 反过来写——先看库锁没锁、先弹一次解锁框、解开之后才发现这一屏根本没有可填的框——
 * 等于为一件注定做不成的事，向用户要了一次主密码。
 */
object AutofillPickFlow {

    /* ══════════════════════════ 相位 ══════════════════════════ */

    /** 这一页此刻该摆的四种样子。界面照着 `when` 一遍，没有第五种。 */
    sealed interface Phase

    /**
     * 整页不该出现，只摆 [reason] 这一句话，一条条目都不列。
     *
     * 话是 [AutofillPick.refusal] 给的，这里一个字都不改写。
     */
    class Refused(val reason: String) : Phase {
        override fun toString(): String = "Refused"
    }

    /**
     * 库锁着（或者摆着摆着被自动锁定了）。摆解锁那两屏。
     *
     * **这不是「进来时锁着」这一种情形。** 它同时是「进来时开着、
     * 摆了半屏清单、然后超时锁上了」那一种——见文件头第一条。
     * 两种情形在这里合成同一个相位是有意的：用户看到的行为一致
     * （清单收起来、要一次凭据、解开之后回到清单），而代码里少一个能写错的分支。
     */
    data object Unlocking : Phase

    /** 库开着，摆清单。这一页真正的样子。 */
    data object Picking : Phase

    /**
     * 安静走人：答卷已经交过了，或者这台设备上已经没有库了。
     *
     * **不弹任何提示。** 这一页浮在别人的应用上面，一条来路不明的 Toast
     * 只会让人以为是那个应用出了问题（同 `AutofillUnlockActivity.finishWithoutFilling`）。
     */
    data object Leaving : Phase

    /**
     * 此刻该摆哪一屏。
     *
     * @param state 会话相位。**每一帧都要重新问一次**，不能只在进来时问一次——
     *   文件头第一条说的就是这个。
     * @param refusal [AutofillPick.refusal] 的原话，为 null 表示这一屏可以填。
     * @param delivered 答卷交过了没有。
     */
    fun phase(
        state: VaultSession.State,
        refusal: String?,
        delivered: Boolean,
    ): Phase = when {
        // 交过就走，别的一概不问：此刻界面上摆什么都不再有意义，
        // 而多摆一帧清单就是多摆一帧不该摆的东西
        delivered -> Leaving

        // 不需要知道库的任何事的那两问，排在最前（见文件头末段）
        refusal != null -> Refused(refusal)

        // 库在这中间被删掉了（另一个入口做的）。没有可填的东西，
        // 也没有什么可对用户说的
        state is VaultSession.State.NoVault -> Leaving

        state is VaultSession.State.Locked -> Unlocking

        else -> Picking
    }

    /* ══════════════════════════ 由状态拼出来的那几句 ══════════════════════════ */

    /**
     * 「这一下会填：账号、密码」。
     *
     * **只有格位，没有值**（决策(144)）——[FillPlan.Slot] 这个枚举里
     * 根本没有能放值的地方，所以这一条在这一层是类型保证，不是纪律。
     *
     * 空清单返回 null 而不是「不填任何东西」：那种情形由
     * [AutofillPick.Choice.blocked] 说话（[AutofillPick.BLOCKED_NOTHING_TO_FILL]
     * 那一整句），在这儿再补一句短的只会和它撞车。
     */
    fun slotsLine(slots: List<FillPlan.Slot>): String? {
        if (slots.isEmpty()) return null
        val names = slots.distinct().map { slotName(it) }
        return "$SLOTS_PREFIX${names.joinToString("、")}"
    }

    /** 一个格位的中文名。这两个词在整个工程里只在这儿定义一次。 */
    fun slotName(slot: FillPlan.Slot): String = when (slot) {
        FillPlan.Slot.Username -> "账号"
        FillPlan.Slot.Password -> "密码"
    }

    /**
     * 默认清单第一段的小标题。
     *
     * 写「这个网站」还是「这个应用」，取决于承载的是网页还是原生框——
     * 在一个应用里看到「这个网站」是一句能让人愣一下的话，
     * 而这一页上每一句话都在替用户做判断，愣一下就是一次成本。
     */
    fun siteSectionTitle(origin: Origin?): String = when (origin) {
        is Origin.Web -> SECTION_THIS_SITE
        is Origin.App -> SECTION_THIS_APP
        null -> SECTION_THIS_SITE
    }

    /* ══════════════════════════ 成句的那几条 ══════════════════════════ */

    const val SLOTS_PREFIX = "这一下会填："

    const val SECTION_THIS_SITE = "这个网站"
    const val SECTION_THIS_APP = "这个应用"
    const val SECTION_RECENT = "最近改过的"
    const val SECTION_RESULTS = "搜索结果"

    /** 顶栏那一行提示。它要说清楚「这一页不是保险库本身」。 */
    const val TITLE = "挑一条填进去"

    const val SEARCH_HINT = "名称 · 账号 · 网址 · 分类"

    /**
     * 搜出来一条都没有时那一句。
     *
     * **这里不给「新增一条」那个出口**（搜索页上有，见 `SearchScreen.NoResults`）。
     * 那一页在应用里，用户坐下来在建条目；这一页浮在一个正等着他登录的表单上面。
     * 让他此刻去走一遍新增三步流，回来时这次填充会话早就没了，
     * 而他手上那个登录框还空着——那是把人送进一条死路。
     */
    const val NO_RESULTS = "没有对得上的条目。换个词试试——名称、账号、网址、分类都能搜。"

    /** 确认那一屏的两个按钮。 */
    const val CONFIRM = "确认填入"
    const val BACK = "返回，再看看"

    /** 确认屏上「必须先看的话」那一段的小标题。 */
    const val WARN_HEADING = "先看清这几句"

    /**
     * 挑中的那一条在这一屏上填不出东西来时，按钮上的字。
     *
     * 理由那一整句由 [AutofillPick.Choice.blocked] 给，这里只管按钮。
     */
    const val CANNOT_FILL = "这一条填不了"
}
