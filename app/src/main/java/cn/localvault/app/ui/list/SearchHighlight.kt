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

/**
 * 把 [VaultIndex.Hit] 变成「可以直接画出来的几段文字」。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose**，和 [VaultIndex] 一样。
 *
 * ── 为什么这件事值得单独切出来 ──
 *
 * 搜索结果必须能向用户解释「这一条为什么会出现在这里」（决策㉜的后半句）。
 * 而这个解释是靠**把命中的那一小段原文亮出来**完成的——一旦亮出来的那段
 * 恰好把命中处截掉了，解释就失效了，用户看到的是一条毫无理由的结果。
 *
 * 「一行放不下就在末尾加省略号」是界面层的默认行为，而它恰恰会犯这个错：
 * 一条账号 `zhangsan_backup_2019@company-mail.example.com`，用户搜 `example`，
 * 尾部截断之后屏幕上剩下 `zhangsan_backup_2019@compan…`——命中的那几个字
 * 一个都没出现。所以窗口必须**以命中位置为中心**开，而不是从头开。
 *
 * 这类判断在界面上没法验证（要造一条长得刚刚好的数据再去肉眼看），
 * 切成纯函数之后全部能在单测里走一遍。
 */
object SearchHighlight {

    /** 副行的默认窗口宽度（字符数）。名称行更窄，见 [NAME_WINDOW]。 */
    const val DEFAULT_WINDOW = 34

    /** 名称行的窗口。它旁边还有图标和右侧字段标签，能放的字更少。 */
    const val NAME_WINDOW = 22

    /** 一段文字。[highlighted] 为 true 的那段就是命中处。 */
    data class Segment(val text: String, val highlighted: Boolean)

    /**
     * 一条可直接渲染的片段。
     *
     * 省略号做成布尔量而不是直接拼进 [segments]，是为了让界面层能用不同的
     * 颜色画它——省略号是我们加的，不是用户的数据，两者不该看起来一样。
     */
    data class Snippet(
        val segments: List<Segment>,
        val leadingEllipsis: Boolean = false,
        val trailingEllipsis: Boolean = false,
    ) {
        /** 拼回纯文本，给无障碍朗读和单测用。 */
        val plain: String
            get() = buildString {
                if (leadingEllipsis) append('…')
                segments.forEach { append(it.text) }
                if (trailingEllipsis) append('…')
            }

        val highlightedText: String
            get() = segments.firstOrNull { it.highlighted }?.text.orEmpty()
    }

    /**
     * 以 [range] 为中心，从 [text] 里开一个宽约 [window] 的窗口。
     *
     * 三条硬规矩：
     *
     *  1. **窗口必须装得下整个命中区间。** 关键词比窗口还长时（用户直接粘了
     *     一整个网址进来），宁可这一行比别的行长，也不能把高亮切掉一半——
     *     切掉之后这一行就变回了「没有理由的结果」。
     *  2. **不切开代理对。** 条目名里放 emoji 是很常见的事（「工作 💼」）。
     *     从一对代理中间切开会在屏幕上留下一个方框，看起来像数据坏了。
     *  3. **[range] 是原始下标，不重新匹配。** 区间由 [VaultIndex.search] 一次算出，
     *     这里只做切割。让搜索页自己再匹配一遍，就会有两套规则，
     *     迟早出现「高亮的位置和排在前面的理由对不上」。
     */
    fun snippet(text: String, range: IntRange, window: Int = DEFAULT_WINDOW): Snippet {
        if (text.isEmpty()) return Snippet(emptyList())

        val valid = !range.isEmpty() &&
            range.first >= 0 &&
            range.last < text.length &&
            range.first <= range.last

        if (!valid) {
            // 没有可用区间（[VaultIndex.matchOf] 在极少数大小写变长的字符上
            // 会退化成 IntRange.EMPTY）。退化成「不高亮 + 尾部截断」，
            // 而不是给出一个错位的高亮——错位的高亮比没有高亮更糟，
            // 它会让用户以为自己搜的是别的东西。
            if (text.length <= window) return Snippet(listOf(Segment(text, false)))
            val end = alignEnd(text, window)
            return Snippet(listOf(Segment(text.substring(0, end), false)), false, true)
        }

        val hitLen = range.last - range.first + 1
        val w = maxOf(window, hitLen)

        if (text.length <= w) return split(text, range, 0, text.length)

        // 命中区间居中：左右各分一半余量。左边少给一点——
        // 中文和网址都是越往左信息量越大（「招商银行」「accounts.」），
        // 让命中处稍微偏右一点，前缀就能多留几个字。
        val slack = w - hitLen
        var start = range.first - slack / 3
        if (start < 0) start = 0
        var end = start + w
        if (end > text.length) {
            end = text.length
            start = maxOf(0, end - w)
        }

        start = alignStart(text, start, range.first)
        end = alignEnd(text, end)

        return split(text, range, start, end)
    }

    /** 名称行的便捷入口：窗口更窄。 */
    fun nameSnippet(text: String, range: IntRange): Snippet =
        snippet(text, range, NAME_WINDOW)

    private fun split(text: String, range: IntRange, start: Int, end: Int): Snippet {
        val segments = ArrayList<Segment>(3)
        val hitStart = range.first.coerceIn(start, end)
        val hitEnd = (range.last + 1).coerceIn(hitStart, end)

        if (hitStart > start) segments += Segment(text.substring(start, hitStart), false)
        if (hitEnd > hitStart) segments += Segment(text.substring(hitStart, hitEnd), true)
        if (end > hitEnd) segments += Segment(text.substring(hitEnd, end), false)

        return Snippet(
            segments = segments,
            leadingEllipsis = start > 0,
            trailingEllipsis = end < text.length,
        )
    }

    /**
     * 左边界不能落在一对代理中间。落在低位代理上时往**左**退一格把高位带上，
     * 而不是往右进一格——往右会有越过 [hitFirst] 的风险，那等于吃掉一截高亮。
     */
    private fun alignStart(text: String, start: Int, hitFirst: Int): Int {
        if (start <= 0 || start >= text.length) return start.coerceIn(0, text.length)
        if (start > hitFirst) return hitFirst
        return if (text[start].isLowSurrogate()) start - 1 else start
    }

    /** 右边界落在高位代理上时往右进一格把低位带上。窗口多一个字符无伤大雅。 */
    private fun alignEnd(text: String, end: Int): Int {
        if (end <= 0) return 0
        if (end >= text.length) return text.length
        return if (text[end - 1].isHighSurrogate()) end + 1 else end
    }

    /* ══════════════════════ 命中字段的标签 ══════════════════════ */

    /**
     * 结果行右侧那个小标签：「这一条是靠什么被搜出来的」。
     *
     * 名称命中返回 null —— 名称本来就占着一行的主位，再标一次「名称」
     * 是拿一块屏幕说废话。真正需要交代的是另外三种：
     * 用户搜一串数字，一条高亮在账号上、一条高亮在网址上，
     * 不标字段的话这两行看起来一模一样。
     */
    fun fieldLabel(field: VaultIndex.Field): String? = when (field) {
        VaultIndex.Field.Name -> null
        VaultIndex.Field.Username -> "账号"
        VaultIndex.Field.Domain -> "网址"
        VaultIndex.Field.Category -> "分类"
    }

    /** 无障碍朗读用的整行描述。屏幕阅读器读不出颜色，只能靠这句话交代命中在哪。 */
    fun describe(hit: VaultIndex.Hit): String {
        val where = fieldLabel(hit.field) ?: "名称"
        return "${hit.entry.name}，在${where}中匹配"
    }
}
