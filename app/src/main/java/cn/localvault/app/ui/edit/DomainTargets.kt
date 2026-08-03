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

package cn.localvault.app.ui.edit

import cn.localvault.app.ui.autofill.PublicSuffix
import cn.localvault.app.ui.list.VaultIndex

/**
 * 「网址 / 应用」那一栏背后的规则：**把一整块多行文本看成一份可增可删的目标清单。**
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose**，
 * 和 [EntryForm]、[cn.localvault.app.ui.add.AddFlow]、[VaultIndex] 是同一个套路。
 *
 * ── 为什么要有这么一层 ──
 *
 * 界面从「一个多行输入框」改成「一份清单 + 选择应用 / 添加网址两个按钮」之后，
 * 多出来的动作只有三个：**加一行、删一行、这一行是应用还是网址**。
 * 这三个动作如果写在 Composable 里，第二天新增流和编辑页就会各有一份，
 * 而分叉的表现是同一个库里两种数据——决策(55) 说的就是这件事。
 *
 * ── 数据模型一个字没动 ──
 *
 * [cn.localvault.app.core.vault.VaultEntry.domains] 还是 `List<String>`，
 * 还是网址和包名混在一起，保险库文件格式没有变。
 * 这一层的输入输出都是 [EntryForm.Draft.domainsText] 那块文本，
 * 切行、去重、「只丢不改写」全部**转手交给 [EntryForm.domainLines]**，
 * 不另写一份。选择器做的事从头到尾只有一件：往那块文本上追加一行准确的包名。
 *
 * ── 分类只用来画界面，不用来做判定 ──
 *
 * [kindOf] 借的是 [PublicSuffix.looksLikePackage]，也就是 M4 自动填充判归属
 * （[cn.localvault.app.ui.autofill.DomainMatch.judge]）用的同一个函数。
 * 复用而不是另写，理由和决策㉝ 一样：两份规则早晚对不上，
 * 而对不上的表现是「界面上画着一个应用图标，填充时却按网址匹配」——
 * 用户看得见的和实际生效的是两回事，这比看不见更糟。
 *
 * 但要说清楚：**这里判错了只是图标画错**。真正决定能不能填的是 `DomainMatch`，
 * 它有自己的一套档位和拒绝理由。所以这一层不需要、也不应该往严了写。
 */
object DomainTargets {

    /** 这一行是安卓包名还是网址。 */
    enum class Kind { App, Web }

    /**
     * 清单里的一行。
     *
     * [raw] 是**用户/选择器当初写下的原文**，一个字符都没改（决策(56)）；
     * [key] 是归一之后的可比对形式，只用于去重和「这个应用是不是已经在清单里了」。
     *
     * `toString` 手写，把 [raw] 挡掉。理由同
     * [cn.localvault.app.ui.autofill.DomainMatch.Hit]：这个对象总是跟着条目走，
     * 顺手打进日志的那一下会把用户上过哪些站、装了哪些应用抄进 logcat，
     * 而那本身就是一份不该外泄的清单。
     */
    data class Target(val raw: String, val kind: Kind, val key: String) {
        override fun toString(): String = "Target(${kind.name})"
    }

    /** 可比对形式。和搜索、自动填充用的是同一个函数。 */
    fun keyOf(raw: String): String = VaultIndex.normalizeDomain(raw)

    fun kindOf(raw: String): Kind =
        if (PublicSuffix.looksLikePackage(keyOf(raw))) Kind.App else Kind.Web

    /**
     * 把那块文本读成清单。顺序就是文本里的顺序——用户加进去的先后是有意义的，
     * 排个序会让他下次打开看到一份自己没排过的清单。
     */
    fun parse(text: String): List<Target> =
        EntryForm.domainLines(text).map { Target(it, kindOf(it), keyOf(it)) }

    /** 清单里已有的**包名**（归一后）。选择器靠它决定哪几行打勾。 */
    fun appKeys(text: String): Set<String> =
        parse(text).filter { it.kind == Kind.App }.mapTo(HashSet()) { it.key }

    fun contains(text: String, line: String): Boolean {
        val key = keyOf(line)
        if (key.isEmpty()) return false
        return parse(text).any { it.key == key }
    }

    /* ══════════════════════════ 增删 ══════════════════════════ */

    /**
     * 追加一行。已经有了（按归一形式比）就原样返回，**不动已有的那一行**。
     *
     * 和 [cn.localvault.app.ui.autofill.AutofillSave] 里那条「只追加不删除」
     * （决策(202)）是同一个姿势：用户手打的 `https://mail.example.com/inbox`
     * 不会因为后来选了一次而被替换成光秃秃的主机名。
     *
     * 归一后为空的（用户只打了一个 `https://`）直接丢——那一段根本不成其为地址，
     * 留下来只会在清单上占一行谁也看不懂的字。
     */
    fun add(text: String, line: String): String {
        val s = line.trim()
        if (s.isEmpty() || keyOf(s).isEmpty()) return text
        val lines = EntryForm.domainLines(text)
        if (lines.any { keyOf(it) == keyOf(s) }) return text
        return (lines + s).joinToString("\n")
    }

    /**
     * 按下标删一行。下标是 [parse] 出来那份清单上的下标，不是原始文本的行号——
     * 界面上用户点的是第几张卡片，这里删的就是第几张。
     *
     * 越界原样返回：删一个不存在的东西的正确处置是什么都不做，
     * 而不是让一次误触把整栏清空。
     */
    fun removeAt(text: String, index: Int): String {
        val lines = EntryForm.domainLines(text)
        if (index !in lines.indices) return text
        return lines.filterIndexed { i, _ -> i != index }.joinToString("\n")
    }

    /** 按内容删（归一后相等的那一行）。选择器里再点一下已勾选的应用走这条。 */
    fun remove(text: String, line: String): String {
        val key = keyOf(line)
        if (key.isEmpty()) return text
        return EntryForm.domainLines(text).filter { keyOf(it) != key }.joinToString("\n")
    }

    /**
     * 在与不在之间翻一下。选择器里一行的点击行为。
     *
     * 做成翻转而不是「只增不减」，是因为选择器里最常见的第二个动作就是**选错了要撤**。
     * 让他关掉选择器、回到清单上找到那一张卡再按叉，是三步；
     * 在原地再点一下是一步，而且他的手指还停在那一行上。
     */
    fun toggle(text: String, line: String): String =
        if (contains(text, line)) remove(text, line) else add(text, line)

    /* ══════════════════════════ 界面上的一句话 ══════════════════════════ */

    /**
     * 清单为空时那句话。
     *
     * 不写成「请添加至少一个」——网址栏本来就是可以留空的
     * （[cn.localvault.app.ui.add.AddFlow.hint] 里那句「三样都能留空」），
     * 一句祈使句会让用户以为这是必填项，而空着完全合法。
     * 这里说的是**填了能换来什么**，让他自己决定要不要花这几秒。
     */
    const val EMPTY_HINT: String = "还没有添加。填了以后，自动填充才认得出这个站点或应用。"

    /**
     * 两个按钮下面那句话。
     *
     * 「包名」两个字必须出现：用户如果不知道这个词，这句话正好告诉他有这么个东西
     * 而且不用他管；如果知道，这句话告诉他不必再去翻设置里那一长串。
     */
    const val PICK_HINT: String = "应用请用「选择应用」挑——包名手打几乎必错，而错了以后填充只会静静地不出现。"
}
