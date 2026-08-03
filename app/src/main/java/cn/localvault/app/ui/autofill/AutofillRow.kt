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
 * 填充条上**一行**长什么样。
 *
 * **这个文件里没有一行 `android.*`。** `RemoteViews` 怎么装是 [AutofillViews] 的事，
 * 这里只回答两个问题：**哪三段文字**、以及**那三段文字在交给系统之前要先过哪几道手**。
 *
 * ── 为什么要有这一层，而不是把 [AutofillOffer.Item] 的字段直接 setText ──
 *
 * 因为填充条上那几行字**不是我们写的**：条目名称、账号，全是用户（或者一份从别处
 * 导进来的 CSV）打进去的。而这一行字要被画进**系统进程**的一个浮层里，
 * 浮在别人的应用上面。用户内容进公共浮层，中间必须有一道洗。
 *
 * 三件事必须在这儿做完：
 *
 * 1. **压成一行。** 名称里带换行是完全合法的（导入来的行、粘贴来的名字），
 *    而 `RemoteViews` 里一个带换行的 `TextView` 会把填充条撑成半屏高，
 *    把下面那几条候选顶出屏幕——第一条候选于是变成了唯一那条候选，
 *    而用户不会知道自己少看见了两条。
 *
 * 2. **剔掉控制字符，尤其是双向控制符。** `U+202E`（RTL override）之后的字符会
 *    倒着画出来：`bank.com` 存成 `\u202Emoc.knab` 在屏幕上一模一样。
 *    这个应用里可以塞进这种字符串的地方只有一个——用户自己那份 CSV
 *    （决策(156) 明说导入预览一格内容都不显示，于是它从没被人看过一眼）。
 *    填充条是它第一次被画出来的地方，也就必须是它被洗掉的地方。
 *
 * 3. **截断。** 一个 200 字的条目名称会把账号那一行挤没。截断不是为了好看：
 *    这三行的**全部用途**是让用户认出「哦，是我那一条」，认不出就等于没有。
 *
 * ── 这里不可能出现密码 ──
 *
 * [forItem] 收的是 [AutofillOffer.Item]，而那个类里**根本没有能放密码的字段**
 * （AutofillOffer 的底线一）。要写下去的值封在 `Item.writes` 里，
 * 这个文件一次都没有碰过它。所以「填充条上不显示密码」在这一层是**类型保证**，
 * 不是一条要靠人记得的纪律。
 */
object AutofillRow {

    /* ══════════════════════════ 一行 ══════════════════════════ */

    /**
     * 填充条上的一行，洗干净、可以直接 setText 的样子。
     *
     * [badge] 为 null 时那个 `TextView` 要 `GONE` 而不是设成空串——
     * 空的 `TextView` 照样占着行距，于是精确匹配那几条会比兄弟域那几条矮一点点，
     * 看着像是没对齐的 bug。
     */
    class Row internal constructor(
        val title: String,
        val subtitle: String,
        val badge: String?,
    ) {
        /** 同 `FillPlan.Write.toString`：只报形状，一个字的内容都不吐（决策(144)）。 */
        override fun toString(): String = "Row(badge=${badge != null})"
    }

    /** 一条候选。 */
    fun forItem(item: AutofillOffer.Item): Row = Row(
        title = clean(item.label, MAX_TITLE).ifEmpty { AutofillOffer.NO_NAME },
        subtitle = clean(item.sublabel, MAX_SUBTITLE).ifEmpty { AutofillOffer.NO_USERNAME },
        badge = item.badge?.let { clean(it, MAX_BADGE) }?.ifEmpty { null },
    )

    /**
     * 「先解锁」那一条。
     *
     * 两行都是我们自己写死的字符串，本来不需要洗；照样走一遍 [clean]，
     * 是为了让这个文件只有一条出口——将来有人把某句文案改成拼接的，
     * 也不会因为「这几句是我们自己写的」而漏掉。
     */
    fun forUnlock(): Row = Row(
        title = clean(AutofillOffer.UNLOCK_LABEL, MAX_TITLE),
        subtitle = clean(AutofillOffer.UNLOCK_NOTE, MAX_SUBTITLE),
        badge = null,
    )

    /**
     * 末尾那条「在保险库里搜索」。
     *
     * 文字早在 M4-2a-2② 就备好了（`AutofillOffer.searchLabel` 把
     * 「截掉了几条 / 一条没截」两种说法钉在用例里），**M4-2b-2 才真的摆上去**——
     * 它要跳到挑选页，而那一页到这一步才存在。落点见
     * [AutofillResponses.searchDataset]。
     */
    fun forSearch(hidden: Int): Row = Row(
        title = clean(AutofillOffer.searchLabel(hidden), MAX_TITLE),
        subtitle = clean(SEARCH_NOTE, MAX_SUBTITLE),
        badge = null,
    )

    /**
     * 用户在挑选页上确认的那一条，装回 `Dataset` 时要的那份 `RemoteViews`。
     *
     * 这一份**用户其实看不到**：认证结果一回来，系统就直接把值填进框里了。
     * 照样走一遍 [clean]，理由同 [forUnlock]——这个文件只有一条出口。
     *
     * [AutofillPick.Row] 里的两行字其实已经洗过一道（[AutofillPick] 自己就调
     * [clean]），再洗一遍是**幂等**的，不会有第二种结果；
     * 而少写这一句的代价是有一天有人换掉挑选页那一侧的洗法，
     * 这一条路就悄悄变成了没洗过的。
     *
     * `badge` 恒为 null：兄弟域那个标记的用途是让用户**在挑之前**看清，
     * 而这一份是他挑完之后的回执。
     */
    fun forPick(row: AutofillPick.Row): Row = Row(
        title = clean(row.label, MAX_TITLE).ifEmpty { AutofillOffer.NO_NAME },
        subtitle = clean(row.sublabel, MAX_SUBTITLE).ifEmpty { AutofillOffer.NO_USERNAME },
        badge = null,
    )

    /* ══════════════════════════ 内联那一格 ══════════════════════════ */

    /**
     * 输入法建议条上的一格（M4-4b）。**两行，没有第三行。**
     *
     * 它和 [Row] 是同一批字、同一道洗、同一个出口，差别只有两处，而两处都是
     * 「那块屏幕不是同一块」带来的：
     *
     * 1. **没有 badge。** 内联那一格只放得下标题和副标题，兄弟域那句
     *    「你存的是 mail.example.com」没有地方摆。而决策(159) 说那一句不许省、
     *    也不许和精确档混在一起显示——于是兄弟域那几条干脆不进内联条
     *    （决策(216)），这个类里因此**不需要**一个永远为 null 的 badge 字段。
     *
     * 2. **上限更短**（[MAX_CHIP_TITLE] / [MAX_CHIP_SUBTITLE]）。填充条那一行的宽度
     *    是输入框的宽度，内联那一格的宽度是**一个键盘上的一小格**，而且旁边还并排
     *    站着别的候选。按浮层那个 40 去截，等于把旁边两格挤出屏幕。
     *
     * 密码在这一层同样是**类型保证**而不是纪律：[chipForItem] 收的是
     * [AutofillOffer.Item]，那个类里根本没有能放密码的字段（决策(218)）。
     */
    class Chip internal constructor(
        val title: String,
        val subtitle: String,
    ) {
        /** 同 [Row.toString]：只报形状，一个字的内容都不吐（决策(144)）。 */
        override fun toString(): String = "Chip()"
    }

    /** 一条候选的内联版。文字和 [forItem] 出自同一处，只是截得更短。 */
    fun chipForItem(item: AutofillOffer.Item): Chip = Chip(
        title = clean(item.label, MAX_CHIP_TITLE).ifEmpty { AutofillOffer.NO_NAME },
        subtitle = clean(item.sublabel, MAX_CHIP_SUBTITLE).ifEmpty { AutofillOffer.NO_USERNAME },
    )

    /** 「先解锁」的内联版。 */
    fun chipForUnlock(): Chip = Chip(
        title = clean(AutofillOffer.UNLOCK_LABEL, MAX_CHIP_TITLE),
        subtitle = clean(AutofillOffer.UNLOCK_NOTE, MAX_CHIP_SUBTITLE),
    )

    /**
     * 「在保险库里搜索」的内联版。
     *
     * [hidden] 在这一格上的含义比浮层那一行更宽：浮层那一行数的是被
     * [AutofillMatch.MAX_SUGGESTIONS] 截掉的条数，这一格还要把
     * **没能进内联条的那几条**一起数进去（兄弟域的、排在格数之外的）。
     * 两处数字因此可以不一样——各自说的都是**自己那块屏幕上**的真话（决策(215)）。
     */
    fun chipForSearch(hidden: Int): Chip = Chip(
        title = clean(AutofillOffer.searchLabel(hidden), MAX_CHIP_TITLE),
        subtitle = clean(SEARCH_NOTE, MAX_CHIP_SUBTITLE),
    )

    /* ══════════════════════════ 洗 ══════════════════════════ */

    /**
     * 压成一行、剔掉控制字符、折叠空白、超长截断。
     *
     * 顺序是有意的：**先剔控制字符，再折空白**。反过来的话，
     * `"a\u202Eb"` 里那个 override 会先被当成一个普通字符留着，
     * 折完空白它还在那儿。
     *
     * 截断按**码点**算，不按 `Char`：名称里带一个 emoji 是很平常的事，
     * 按 `Char` 切会把一对代理对切成半个，屏幕上是一个「豆腐块」，
     * 而那个位置本来是用户用来认出这一条的图标。
     */
    internal fun clean(raw: String, max: Int): String {
        val sb = StringBuilder(minOf(raw.length, max * 2 + 8))
        var lastWasSpace = true // 开头的空白直接吞掉，省一次 trim
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isSpace(cp) -> {
                    if (!lastWasSpace) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                }
                isDropped(cp) -> Unit // 控制字符 / 双向控制符：直接消失，不留空格
                else -> {
                    sb.appendCodePoint(cp)
                    lastWasSpace = false
                }
            }
        }
        // 结尾可能剩一个折出来的空格
        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        return truncate(sb.toString(), max)
    }

    /**
     * 算的是**码点数**不是 `Char` 数，所以「20 个汉字」和「20 个字母」一样长
     * ——这不准（汉字宽一倍），但填充条那一行的实际宽度本来就由系统和字号决定，
     * 我们这一刀只保证「不会长到荒唐」，真正的省略由 `TextView` 的
     * `ellipsize` 再兜一次。两道都要有：只靠 `ellipsize` 的话，
     * 那个 200 字的字符串仍然会**整串**被交给系统进程去测量和布局。
     */
    private fun truncate(s: String, max: Int): String {
        require(max >= 2) { "max 太小，省略号自己都放不下" }
        val count = s.codePointCount(0, s.length)
        if (count <= max) return s
        val end = s.offsetByCodePoints(0, max - 1)
        return s.substring(0, end).trimEnd() + ELLIPSIS
    }

    /** 一切被当作「空白」的东西，包括制表、换行、不间断空格、各种排版空格。 */
    private fun isSpace(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
            cp == 0x0B || cp == 0x0C || cp == 0x85 || cp == 0xA0 ||
            (cp in 0x2000..0x200A) || cp == 0x2028 || cp == 0x2029 ||
            cp == 0x202F || cp == 0x205F || cp == 0x3000

    /**
     * 直接消失的那些码点。
     *
     * · C0 / C1 控制字符（`\u0000`–`\u001F`、`\u007F`–`\u009F`）——
     *   换行制表已经在 [isSpace] 里折成空格了，剩下的没有一个该出现在人名里；
     * · **双向控制符**（`U+200E/200F`、`U+202A`–`U+202E`、`U+2066`–`U+2069`）——
     *   见文件头第 2 条，这是三道里唯一一道防的是**恶意**而不是失手；
     * · 零宽字符（`U+200B`–`U+200D`、`U+FEFF`）——它们能让两条看起来一模一样的候选
     *   其实是两条，用户挑中的永远是上面那条。
     */
    private fun isDropped(cp: Int): Boolean =
        cp <= 0x1F || (cp in 0x7F..0x9F) ||
            cp == 0x200E || cp == 0x200F || (cp in 0x202A..0x202E) ||
            (cp in 0x2066..0x2069) || (cp in 0x200B..0x200D) || cp == 0xFEFF

    /* ══════════════════════════ 尺寸与文案 ══════════════════════════ */

    /**
     * 三行各自的上限（码点）。
     *
     * 数字不是拍的：填充条的宽度是**输入框的宽度**，窄的那种（登录页上半屏的
     * 用户名框）在一台 6 寸手机上大约放得下 20 个汉字。给到 40 是让宽的那种
     * （全宽的搜索框、平板）也不至于早早就截，剩下的交给 `ellipsize`。
     */
    const val MAX_TITLE = 40
    const val MAX_SUBTITLE = 48
    const val MAX_BADGE = 40

    /**
     * 内联那一格的上限（码点）。**比上面三个短一半是有意的。**
     *
     * 浮层那一行独占输入框的宽度，内联这一格是键盘建议条上的**一小格**，
     * 旁边还并排站着别的候选（可能是输入法自己的联想词）。按 40 去截，
     * 第一格就会把后面两格推出屏幕——而被推出去的那两格里，
     * 有一格是「在保险库里搜索」，也就是用户唯一能看见剩下几条的入口（决策(215)）。
     *
     * 真正的省略仍然由输入法那边做（它拿到的是一个 `Slice`，怎么画由它说了算）；
     * 这一刀只保证「不会长到荒唐」，同 [MAX_TITLE] 那一段。
     */
    const val MAX_CHIP_TITLE = 24
    const val MAX_CHIP_SUBTITLE = 28

    const val ELLIPSIS = "…"

    /** 搜索那一行的小字。它要说清楚「点下去不会当场填好」。 */
    const val SEARCH_NOTE = "打开保险库，自己挑一条"
}
