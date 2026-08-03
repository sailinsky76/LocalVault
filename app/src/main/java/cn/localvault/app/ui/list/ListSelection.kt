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

package cn.localvault.app.ui.list

import cn.localvault.app.core.vault.VaultEntry

/**
 * 列表页多选的全部规则。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose**，
 * 和 [VaultIndex]、[cn.localvault.app.ui.edit.EntryForm]、
 * [cn.localvault.app.ui.add.AddFlow] 是同一个套路。
 *
 * ── 为什么这么点东西也要单开一层 ──
 *
 * 多选看起来只是一个 `Set<String>`，但它有四件事在界面上验不动、
 * 又几乎必然被后来的改动悄悄破掉：
 *
 *  1. **选中集合会腐烂**。用户勾了 5 条，切到详情页删掉其中 1 条再回来，
 *     那个 id 还留在集合里。屏幕上写着「已选 5 条」，按下删除实际只删掉 4 条——
 *     而 [cn.localvault.app.core.session.VaultSession.deleteEntries] 忽略找不到的 id，
 *     所以它不会报错，只会安静地和用户对不上。[prune] 是这条的解药。
 *  2. **分组全选不是「逐条翻转」**。一组 8 条里已经选了 3 条时，
 *     用户点组标头的意思是「这一组我都要」，不是「反选成 5 条」。
 *  3. **确认框里只出现名称**。此刻要摆上屏幕的是一串条目，
 *     顺手把账号也带上会让一次删除变成一屏泄漏面（决策⑭ 那条的加强版：
 *     单条删除敢带一个打码账号，是因为只有一条要认人）。
 *  4. **条数是唯一要说准的数**。「删除 12 条」这句话按下去以后无法撤销，
 *     它和实际删掉的条数必须是同一个数。
 */
object ListSelection {

    /* ══════════════════════════ 集合的增减 ══════════════════════════ */

    fun toggle(selected: Set<String>, id: String): Set<String> =
        if (id in selected) selected - id else selected + id

    /**
     * 把已经不在库里的 id 剔掉。
     *
     * 每次库变了都要过一遍——库在别处被改的路径不止一条：详情页删除、
     * 导入覆盖、自动填充新存了一条。不剔的话，屏幕上那个「已选 N 条」
     * 会慢慢变成一个越来越假的数字，而它正是删除按钮上写的那个数。
     */
    fun prune(selected: Set<String>, entries: List<VaultEntry>): Set<String> {
        if (selected.isEmpty()) return selected
        val live = entries.mapTo(HashSet(entries.size)) { it.id }
        if (selected.all { it in live }) return selected
        return selected.filterTo(LinkedHashSet()) { it in live }
    }

    fun allIds(entries: List<VaultEntry>): Set<String> =
        entries.mapTo(LinkedHashSet(entries.size)) { it.id }

    /** 空库不算「全选了」——否则空库上那个按钮会写着「取消全选」。 */
    fun isAllSelected(selected: Set<String>, entries: List<VaultEntry>): Boolean =
        entries.isNotEmpty() && selected.size >= entries.size &&
            entries.all { it.id in selected }

    /**
     * 顶栏右边那个按钮：全选 ↔ 取消全选。
     *
     * 已经全选时点它是**清空**，不是退出选择模式：
     * 退出是左上角那个叉的事，两个动作混在一个按钮上，
     * 用户想清空重选时会莫名其妙被弹回普通模式。
     */
    fun toggleAll(selected: Set<String>, entries: List<VaultEntry>): Set<String> =
        if (isAllSelected(selected, entries)) emptySet() else allIds(entries)

    fun toggleAllText(selected: Set<String>, entries: List<VaultEntry>): String =
        if (isAllSelected(selected, entries)) "取消全选" else "全选"

    /* ══════════════════════════ 分组 ══════════════════════════ */

    enum class GroupState { None, Some, All }

    fun groupState(selected: Set<String>, section: VaultIndex.Section): GroupState {
        if (section.entries.isEmpty()) return GroupState.None
        val n = section.entries.count { it.id in selected }
        return when (n) {
            0 -> GroupState.None
            section.entries.size -> GroupState.All
            else -> GroupState.Some
        }
    }

    /**
     * 点组标头。**整组已经全选时清空这一组，否则把这一组全选上。**
     *
     * 刻意不做逐条翻转：一组 8 条里已经选了 3 条时，用户点标头的意思
     * 是「这一组我都要」。翻转会给他 5 条，而那 5 条是哪 5 条他还得挨个数——
     * 一个让用户去数的批量操作，不如没有。
     */
    fun toggleGroup(selected: Set<String>, section: VaultIndex.Section): Set<String> {
        val ids = section.entries.map { it.id }
        return if (groupState(selected, section) == GroupState.All) {
            selected - ids.toSet()
        } else {
            selected + ids
        }
    }

    /* ══════════════════════════ 屏幕上的字 ══════════════════════════ */

    /* ── 长按提示（普通模式下，列表末尾那一行）── */

    /**
     * 撤掉顶栏那个对勾按钮之后（决策(220)），长按成了进选择模式的**唯一**入口，
     * 而长按在列表上是一个没有任何提示的手势——不知道它存在的人永远不会去试。
     * 这一行就是那个提示。
     *
     * 它同时说清两件事：**怎么进**（长按任意一条）和**进去能干什么**（一次删几条）。
     * 只写前半句的话，用户不知道为什么要长按；只写后半句是句废话。
     *
     * 不写「点右上角也行」——右上角已经没有那个按钮了。
     * 这句话和界面必须逐字对得上，否则用户会照着它去点一个不存在的东西，
     * 然后合理地认为这个功能坏了。
     */
    const val LONG_PRESS_HINT: String = "长按任意一条可以多选，一次删掉几条。"

    /**
     * 少于这么多条就不摆那行提示。
     *
     * 只有一条的库上，「一次删掉几条」是一句用不上的话，而它会挂在
     * 一个刚建好库、屏幕上只有一行东西的新用户眼前，成为那一屏最显眼的噪音。
     * 真需要多选的人，库里一定不止一条。
     */
    const val HINT_MIN_ENTRIES: Int = 2

    /**
     * 那行提示现在该不该出现。
     *
     * **选择模式下一律不出现**：那时候用户已经在里面了，再教他怎么进去
     * 是句废话；而且那一屏底下已经有一句话了（[emptyHint] / [confirmMessage]），
     * 两句灰字上下摆着，要紧的那句会被稀释掉。
     */
    fun showHint(entryCount: Int, selecting: Boolean): Boolean =
        !selecting && entryCount >= HINT_MIN_ENTRIES

    /* ── 选择模式下的字 ── */

    /** 选择模式下的顶栏标题。0 条也照常显示，见 [emptyHint]。 */
    fun title(count: Int): String = if (count == 0) "选择条目" else "已选 $count 条"

    fun deleteText(count: Int): String = if (count == 0) "删除" else "删除这 $count 条"

    /**
     * 一条都没选时，那个灰按钮下面的一句话。
     *
     * 决策(61)：按钮为什么是灰的永远要给一句话。一个没有解释的灰按钮，
     * 用户第一反应是「这个 App 卡了」，而这一屏上他刚做完一个陌生的手势
     * （长按），最容易得出的结论就是「刚才那下按坏了」。
     */
    fun emptyHint(): String = "点条目选中它们。长按或点右上角也能全选。"

    /* ══════════════════════════ 确认框 ══════════════════════════ */

    fun confirmTitle(count: Int): String = "删除这 $count 条？"

    /**
     * 确认框正文。
     *
     * ── 这句话必须说「不能撤销」──
     *
     * 详情页删单条是**可以撤销一次**的（`EntryDetail.remove` 会留一份快照，
     * 弹窗里也照实写了「删掉之后还能撤销一次」）。批量这一条路没有那份快照：
     * 一次删 20 条要在内存里囤 20 条明文条目，而它们随时可能被自动锁定
     * （决策⑪ 会把整棵子树连同快照一起换掉）连带丢掉——
     * 一个**有时候能撤销、有时候不能**的撤销按钮，比没有更坏。
     *
     * 所以两条路的语气必须是反的，而且都得是实话。用户在这一屏读到的
     * 「不能撤销」，是他决定要不要按下去的全部依据。
     */
    fun confirmMessage(count: Int): String =
        "这 $count 条会立刻从保险库里消失，不能撤销——除非你有备份。"

    /** 明细里最多列几条。再多，弹窗会长到按钮被顶出屏幕。 */
    const val DETAIL_MAX: Int = 6

    /**
     * 确认框里那块明细：**只有名称，一个账号都不带。**
     *
     * 单条删除那儿（`EntryDetail.deleteConfirmDetail`）敢带一个打过码的账号，
     * 是因为只有一条，用户需要它来确认「是不是这一条」。
     * 这里一次摆的是一串——名称本来就是列表行的主位，他扫一眼就认得出，
     * 而把六个账号一起摆上去，换来的确认力几乎为零，泄漏面却翻了六倍。
     *
     * 超出 [DETAIL_MAX] 的部分只报条数：那个数和标题里的数能对上，
     * 用户自己会算，不需要把整份清单摊开。
     *
     * 顺序跟着 [entries] 走（也就是库里的顺序），不重排——
     * 用户刚在屏幕上看过的就是这个顺序。
     */
    fun confirmDetail(entries: List<VaultEntry>, selected: Set<String>): String {
        val picked = entries.filter { it.id in selected }
        if (picked.isEmpty()) return ""
        val head = picked.take(DETAIL_MAX).joinToString("\n") { it.name.ifBlank { "（无名称）" } }
        val rest = picked.size - DETAIL_MAX
        return if (rest > 0) "$head\n…还有 $rest 条" else head
    }

    /* ══════════════════════════ 出错 ══════════════════════════ */

    /**
     * 写盘失败时那句话。
     *
     * 「条目还在」这四个字是关键：[cn.localvault.app.core.session.VaultSession.mutate]
     * 失败时内存和磁盘一起回滚，所以一条都没少。不说这句的话，
     * 用户面对一个「删除失败」只会以为删了一半，然后开始挨个核对。
     */
    const val FAILURE: String = "删除没能写进保险库，选中的条目都还在。"
}
