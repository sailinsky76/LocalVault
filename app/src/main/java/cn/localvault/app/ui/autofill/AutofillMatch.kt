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

import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.list.VaultIndex

/**
 * 从整个库里挑出「这一组输入框上该出现哪几条」。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * [DomainMatch] 回答的是「这一条能不能填」，这里回答的是「哪几条、按什么顺序、显示几条」。
 *
 * ── 这里只管自动建议那一半 ──
 *
 * 用户手动搜索着挑的那一半**不在这个文件里**：那走的是 M3-3b 那套
 * `VaultIndex.search`，一个字都不重写。理由是决策㉜ 已经把「哪些字段可以被搜」
 * 钉死成一张白名单了，在这儿另起一套搜索，等于把那张白名单复制一份——
 * 而复制出来的那一份迟早会把备注也搜进去。
 */
object AutofillMatch {

    /**
     * 填充条上最多放几条。
     *
     * 不是拍脑袋：系统的填充条本身就只露出两三条，其余要展开；
     * 而一个「这个站我存了 30 条」的用户，需要的不是把 30 条摊开，
     * 是直接去搜索里找。超出的部分由 [Suggestions.hidden] 报出条数，
     * M4-2b 在末尾放一条「在保险库里搜索（还有 12 条）」。
     */
    const val MAX_SUGGESTIONS = 8

    /**
     * 一条候选。
     *
     * 不是 `data class`，`toString` 手写只报形状——决策(144)。
     * 这个对象直接抱着一个 [VaultEntry]，而 `VaultEntry` 是 `data class`，
     * 它的自动 `toString` 会把**明文密码**原样打出来。
     * 哪天有人顺手 `Log.d(TAG, "candidates=$list")`，那一行就是一份明文凭据。
     * 让它做不到，而不是让人记得别做。
     */
    class Candidate(
        val entry: VaultEntry,
        val verdict: DomainMatch.Verdict,
        /** 条目里命中的那一行**原文**。界面要显示它，好让用户认出「哦，是这一条」。 */
        val matchedDomain: String?,
    ) {
        override fun toString(): String = "Candidate(${verdict.name})"
    }

    /**
     * 挑选结果。同样不是 `data class`，理由同上。
     */
    class Suggestions(
        val shown: List<Candidate>,
        /** 够格自动出现的总条数（含被 [MAX_SUGGESTIONS] 截掉的）。 */
        val total: Int,
    ) {
        val hidden: Int get() = (total - shown.size).coerceAtLeast(0)
        val isEmpty: Boolean get() = shown.isEmpty()

        override fun toString(): String = "Suggestions(shown=${shown.size}, total=$total)"

        companion object {
            val EMPTY = Suggestions(emptyList(), 0)
        }
    }

    /**
     * 自动建议：只收 [DomainMatch.Verdict.canAutoFill] 那两档。
     *
     * 排序：**精确档整体压过兄弟档** → 收藏 → 最近改动的在前 → 名称
     * （名称用 [VaultIndex.NAME_ORDER]，和列表页同一个顺序）。
     *
     * 「最近改动的在前」是这里唯一一条带猜测成分的规则，值得说清楚：
     * 同一个站存了两条的人，多半是刚改过密码又存了一条新的，或者一个主号一个小号。
     * 前一种情况下新的那条是对的；后一种情况下两条都会出现在填充条上，排序无所谓。
     * 用「上次填充用过哪条」来排会更准，但那要**记一笔谁在什么时候登录了什么**——
     * 这个应用不记这种账（同决策㊲ 不做搜索历史），所以宁可用一条差一点、
     * 但不产生新数据的规则。
     *
     * [limit] 带默认值，既有调用一个字没改。放开它的只有 M4-2b 的挑选页
     * （[AutofillPick.listing]）：[MAX_SUGGESTIONS] 那个 8 是**填充条的**上限，
     * 而那一页是全屏，用户点进来的那一行写的正是「还有 12 条」。
     * 那一页另写一套排序的后果是「填充条上排第一的和挑选页上排第一的不是同一条」，
     * 而没有任何一处能解释为什么。
     */
    fun suggest(
        origin: Origin,
        entries: List<VaultEntry>,
        trust: HostTrust,
        limit: Int = MAX_SUGGESTIONS,
    ): Suggestions {
        val hits = ArrayList<Candidate>()
        for (e in entries) {
            if (!hasSomethingToFill(e)) continue
            if (e.domains.isEmpty()) continue
            val hit = DomainMatch.best(origin, e.domains, trust)
            if (hit.verdict.canAutoFill) {
                hits += Candidate(e, hit.verdict, hit.matched)
            }
        }
        if (hits.isEmpty()) return Suggestions.EMPTY
        hits.sortWith(ORDER)
        return Suggestions(hits.take(limit.coerceAtLeast(0)), hits.size)
    }

    /**
     * 手动挑那一侧要的东西：给定一条用户自己搜出来的条目，它落在哪一档。
     *
     * 界面据此决定按钮上方要不要先说一句（[DomainMatch.Verdict.needsWarning]）。
     * **注意这里不做任何过滤**——用户搜得到的就该挑得到，
     * 判定只影响「说什么」，不影响「让不让」。分界线写在
     * [DomainMatch.Verdict.WrongKind] 那一段里。
     */
    fun inspect(origin: Origin, entry: VaultEntry, trust: HostTrust): Candidate {
        val hit = DomainMatch.best(origin, entry.domains, trust)
        return Candidate(entry, hit.verdict, hit.matched)
    }

    /**
     * 这一条身上有没有能填的东西。
     *
     * 账号密码都空的条目照样可能存在——决策(149) 明说没有密码的行也照样导入，
     * 而「名称 + 备注」型的条目（比如只记了一句话）在库里完全合法。
     * 让它出现在填充条上，用户点下去会发现什么都没发生，
     * 然后合理地怀疑是这个功能坏了。
     *
     * 只有账号的条目**要留着**：不少登录页是账号和密码分两屏的，
     * 第一屏只要账号。
     */
    private fun hasSomethingToFill(e: VaultEntry): Boolean =
        e.username.isNotEmpty() || e.password.isNotEmpty()

    private val ORDER: Comparator<Candidate> =
        compareByDescending<Candidate> { it.verdict.rank }
            .thenByDescending { it.entry.favorite }
            .thenByDescending { it.entry.updatedAt }
            .thenBy(VaultIndex.NAME_ORDER) { it.entry }
}
