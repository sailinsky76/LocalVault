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

/**
 * 内联建议（Android 11+ 输入法建议条上的那几格）**摆几格、摆哪几条、每一格用哪一份规格**。
 *
 * **整个文件没有一行 `android.*`。** `InlineSuggestionsRequest` 怎么问、
 * `InlinePresentation` 怎么装是 [InlineViews] 的事（那一侧塞错了当场看得见），
 * 两行字怎么洗是 [AutofillRow] 的事；这里只回答一个问题——
 * **这一次请求，内联条上到底出现哪几格。**
 *
 * 这一层和 [AutofillOffer] 的关系，同 [AutofillRow] 和 [AutofillViews] 的关系：
 * 候选是谁、够不够格自动填，[AutofillOffer] 早就判完了（30 条用例）；
 * 这里一条都不重判，只做「这块屏幕上摆得下多少」这一件事。
 * 想在这儿加一个「这条要不要给他」的判断之前先停一下——那个判断该加在
 * [AutofillOffer] 里，那边测得到，而且那边一次就管住浮层和内联两条路。
 *
 * ── 三条底线 ──
 *
 * **一、全有或全无（决策(214)）。** 规格里没有我们认得的版本、一格都摆不下、
 * 输入法压根没问——三种都退回浮层那条老路，绝不出「半条内联」。
 * 内联和浮层在同一次请求里是**两种画法，不是两份内容**：
 * 摆出一条画不完整的内联条，用户看到的是一个缺了两行的填充条，
 * 而他没有任何办法知道自己少看见了什么。
 *
 * **二、搜索那一格永远留着（决策(215)）。** 哪怕输入法只给一格，那一格也是
 * 「在保险库里搜索」，而不是排第一的那条候选。理由是**没进内联条的那几条
 * 不许悄悄消失**：那一格上写着「还有 N 条」，N 里数着被截掉的、
 * 兄弟域被挡下的、以及排在格数之外的全部。少了它，用户看到的是
 * 一条候选都没有或者只有一条，而他会以为这就是全部。
 *
 * **三、兄弟域那几条不进内联（决策(216)）。** 内联一格只有两行，
 * 摆不下「你存的是 mail.example.com」那句话，而决策(159) 说那一句
 * 不许省、也不许和精确档混在一起显示。摆不下就不摆，计进那个 N 里。
 */
object InlinePlan {

    /* ══════════════════════════ 输入法问过来的东西 ══════════════════════════ */

    /**
     * 输入法那一侧问过来的两件事，摊成纯数据。
     *
     * @param max 输入法要几格。平台那边可能是 `SUGGESTION_COUNT_UNLIMITED`
     *   （一个很大的数），所以这里**不许直接拿它当格数**——见 [MAX_CHIPS]。
     * @param v1 每一份规格「认不认得我们会画的那个版本」。**是个布尔数组而不是数量**：
     *   规格是输入法给的，它完全可以给一份我们没见过的版本，
     *   而那种时候画出去的东西长什么样没有人说得准。
     */
    class Ask internal constructor(
        val max: Int,
        val v1: List<Boolean>,
    ) {
        /** 只报数字，不报别的（决策(144)）。 */
        override fun toString(): String = "Ask(max=$max, specs=${v1.size}, v1=${v1.count { it }})"
    }

    /** 薄壳那一侧唯一的入口：把平台对象读成 [Ask]。 */
    fun ask(max: Int, specs: List<Boolean>): Ask = Ask(max, specs)

    /** 这一次不摆内联的原因。四种都不是故障，日志里只打档名。 */
    enum class Why {
        /** 输入法没问——不支持内联，或者这一次它不想要。Android 10 及以下永远是这一档。 */
        NoRequest,

        /** 输入法要 0 格。 */
        NoRoom,

        /** 一份规格都没给。 */
        NoSpec,

        /** 规格里没有我们认得的那个版本（决策(214)）。 */
        NoStyle,
    }

    /**
     * 内联条上的一格：画什么、用第几份规格。
     *
     * [specIndex] 已经**夹过**了：输入法给的规格可能比格数少，
     * 那时最后一份管住剩下所有格（平台文档明写的规则）。把这条规则放在这儿，
     * 是因为它错了不会报错——只会在某些输入法上画出一格尺寸不对的东西，
     * 而那种事在自己的手机上一辈子也碰不到一次。
     */
    class Slot internal constructor(
        val chip: AutofillRow.Chip,
        val specIndex: Int,
    ) {
        override fun toString(): String = "Slot(spec=$specIndex)"
    }

    /**
     * 一次「已解锁、出候选」的请求，内联条长什么样。
     *
     * [slots] 和 `offer.items` **一一对应、等长**：第 i 条候选没进内联条时
     * 第 i 格是 null。这样接线那一侧不用自己去对号，也就不会对错
     * （对错的表现是「这一行的字画到了另一行的数据上」，那是最坏的一种错）。
     */
    class Layout internal constructor(
        val slots: List<Slot?>,
        val search: Slot?,
        /** 没进内联条的候选条数，**含**被 `AutofillMatch.MAX_SUGGESTIONS` 截掉的那些。 */
        val withheld: Int,
        /** 非 null 时这一份整个不摆内联，退回浮层（底线一）。 */
        val why: Why?,
    ) {
        val on: Boolean get() = why == null

        override fun toString(): String = if (why != null) {
            "Layout(off=${why.name})"
        } else {
            "Layout(${slots.count { it != null }}/${slots.size}+search=${search != null}, withheld=$withheld)"
        }
    }

    /** 只有一格的那两种情形（眼下只有「先解锁」）。 */
    class Solo internal constructor(val slot: Slot?, val why: Why?) {
        val on: Boolean get() = why == null
        override fun toString(): String = if (why != null) "Solo(off=${why.name})" else "Solo(on)"
    }

    /**
     * 我们自己那道上限。**不是输入法说要 20 格就摆 20 格。**
     *
     * 平台文档建议不超过 5 格（超过之后每一格都要跨进程画一次），
     * 而这一条建议条是**别人的键盘**，不是我们的界面：占满它不是我们的位置。
     * 4 = 3 条候选 + 那一格搜索（底线二）。
     */
    const val MAX_CHIPS = 4

    /* ══════════════════════════ 判 ══════════════════════════ */

    /**
     * 已解锁那一路：候选摆哪几格。
     *
     * [hidden] 是 [AutofillOffer.Offer.hidden]（够格但被截掉的条数）。
     * 出去的 [Layout.withheld] 会比它大——还要加上兄弟域那几条和排在格数之外的那几条。
     */
    fun forOffer(ask: Ask?, items: List<AutofillOffer.Item>, hidden: Int): Layout {
        val total = if (ask == null) 0 else minOf(ask.max, MAX_CHIPS)
        val why = refusal(ask, total)
        if (why != null) return Layout(items.map { null }, null, 0, why)

        // 搜索那一格永远留着（底线二）。total == 1 时 forItems == 0：
        // 一条候选都不摆，只摆那一格搜索——它写着「还有 N 条」，
        // 于是「少了几条」这件事仍然在屏幕上
        val forItems = total - 1
        val slots = ArrayList<Slot?>(items.size)
        var used = 0
        for (item in items) {
            // 兄弟域那一条摆不下第三行，于是不摆（底线三）
            if (item.badge != null || used >= forItems) {
                slots += null
                continue
            }
            slots += Slot(AutofillRow.chipForItem(item), specIndex(ask!!, used))
            used++
        }
        val withheld = items.size - used + hidden
        val search = Slot(AutofillRow.chipForSearch(withheld), specIndex(ask!!, used))
        return Layout(slots, search, withheld, null)
    }

    /**
     * 锁着那一路：只有「先解锁」一格。
     *
     * 这一格**不受 [MAX_CHIPS] 管**，只要输入法肯给一格就摆——
     * 它不是候选，是这一屏上唯一能点的东西。
     */
    fun forUnlock(ask: Ask?): Solo {
        val why = refusal(ask, if (ask == null) 0 else minOf(ask.max, 1))
        if (why != null) return Solo(null, why)
        return Solo(Slot(AutofillRow.chipForUnlock(), specIndex(ask!!, 0)), null)
    }

    /**
     * 四道门，顺序有意：**先问「问了没有」，再问「给了几格」，最后才看规格。**
     *
     * [want] 是这一次最多会用到几格。规格只检查会用到的那几份：
     * 输入法给了 5 份而我们只摆 2 格时，第 3 份是什么版本与我们无关，
     * 为它整份退回浮层是拿别人的富余惩罚用户。
     */
    private fun refusal(ask: Ask?, want: Int): Why? = when {
        ask == null -> Why.NoRequest
        want < 1 -> Why.NoRoom
        ask.v1.isEmpty() -> Why.NoSpec
        ask.v1.take(want).any { !it } -> Why.NoStyle
        else -> null
    }

    /** 规格不够时最后一份管住剩下所有格（平台规则，见 [Slot.specIndex]）。 */
    private fun specIndex(ask: Ask, slot: Int): Int = minOf(slot, ask.v1.size - 1)
}
