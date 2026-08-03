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

package cn.localvault.app.ui.add

import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.edit.EntryForm
import cn.localvault.app.ui.list.VaultIndex

/**
 * 新增条目那三步背后的全部规则。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose**，
 * 和 [cn.localvault.app.ui.edit.EntryForm]、[cn.localvault.app.ui.list.VaultIndex]、
 * [cn.localvault.app.ui.detail.EntryDetail]、[cn.localvault.app.ui.unlock.UnlockGuard]
 * 是同一个套路：能在 JVM 上跑的东西一律不放进 Composable。
 *
 * ── 为什么新增要分三步，而编辑是一整页 ──
 *
 * 两者的用户处境完全不同。编辑是**冲着某一个字段来的**：他知道要改哪儿，
 * 一整页摊开让他一眼找到那一行最省事（决策(62) 说的就是这件事）。
 * 新增则是从一张白纸开始，六个空框一次摆出来会让人先花几秒钟决定「从哪儿填起」，
 * 而其中四个其实都可以留空——那四个空框每一个都在无声地暗示「这里也得填」。
 *
 * 分三步不是为了少填，是为了**把「必须填的」和「可以不填的」在时间上分开**：
 * 第一步只有名称是硬要求，第二步和第三步全都能空着按下一步。
 *
 * ── 但字段还是那一套字段 ──
 *
 * 每一步画哪几个框由 [fields] 说了算，画出来的东西是
 * [cn.localvault.app.ui.edit.EntryFormFields]——和编辑页同一个 Composable，
 * 修剪、切行、去重的规则是同一个 [EntryForm]。决策(55) 早就写明了理由：
 * 两边各写一份的话，「网址怎么切行」「密码能不能 trim」这几条规则马上分叉，
 * 而分叉的表现是**同一个库里两种数据**，将来 M4 自动填充按哪一份匹配都对不齐。
 */
object AddFlow {

    /**
     * 三步。名字取的是「这一步在问什么」，不是「第几屏」——
     * 将来真要插一步（比如从剪贴板识别），改的是这个枚举，不是一堆散在页面里的整数。
     */
    enum class Step { Basics, Password, Filing }

    val steps: List<Step> = listOf(Step.Basics, Step.Password, Step.Filing)

    fun index(step: Step): Int = steps.indexOf(step)

    /** 「2 / 3」。用等宽字体显示，别用「第二步」——数字比汉字好一眼扫过。 */
    fun ordinal(step: Step): String = "${index(step) + 1} / ${steps.size}"

    fun next(step: Step): Step? = steps.getOrNull(index(step) + 1)

    fun prev(step: Step): Step? = steps.getOrNull(index(step) - 1)

    fun isLast(step: Step): Boolean = next(step) == null

    fun title(step: Step): String = when (step) {
        Step.Basics -> "名称与账号"
        Step.Password -> "密码"
        Step.Filing -> "归类与网址"
    }

    /**
     * 每一步顶上那句话。它要回答的是「这一步能不能跳过」——
     * 用户站在一个空框前最想知道的就是这个，而没有人会去翻帮助文档。
     */
    fun hint(step: Step): String = when (step) {
        Step.Basics -> "名称是唯一必填的，列表和搜索都靠它认人。"
        Step.Password -> "可以现在生成，也可以先空着，之后随时能补。"
        Step.Filing -> "三样都能留空。填了网址，将来自动填充才认得站点。"
    }

    /* ══════════════════════════ 每一步画哪几个框 ══════════════════════════ */

    /**
     * 三步的字段**不重不漏**：并起来正好是六个，两两之间没有交集。
     * 这条由单测钉死——漏一个字段的表现是「有个东西怎么都填不进去」，
     * 重一个字段的表现是「同一个框在两屏上各改各的，后一屏把前一屏盖掉」。
     */
    fun fields(step: Step): Set<EntryForm.Field> = when (step) {
        Step.Basics -> setOf(EntryForm.Field.Name, EntryForm.Field.Username)
        Step.Password -> setOf(EntryForm.Field.Password)
        Step.Filing -> setOf(
            EntryForm.Field.Domains,
            EntryForm.Field.Category,
            EntryForm.Field.Notes,
        )
    }

    /**
     * 进入这一步时把光标放在哪儿。null = **不自动聚焦，也不弹键盘**。
     *
     * ── 第一步弹键盘，编辑页不弹（对比决策(62)）──
     *
     * 编辑页不自动聚焦，是因为用户点铅笔多半冲着某一个字段来，
     * 替他选一个只会挡住半张表单。新增流的第一步不存在这个问题：
     * 这一步只有两个框，而且他此刻要做的第一个动作**确实就是打名称**。
     * 少按一下那个框，是这条流程上最便宜的一次省事。
     *
     * ── 名称已经从搜索页带进来时，聚焦落在账号上 ──
     *
     * 用户在搜索页打了「招商」、没搜到、点了「新增「招商」」，
     * 名称栏里已经是他要的字了。这时候再把光标顶在名称上，
     * 他得先按一下账号框——而那一下本来可以不用按。
     *
     * ── 参数是 [seededName]，**不是草稿** ──
     *
     * 这条规则问的是「**进这条流程的时候**名称栏里是不是已经有字了」，
     * 是一个进页面时就定死、之后再也不会变的事实
     * （[cn.localvault.app.ui.nav.DraftHandoff.takeName] 给没给东西）。
     *
     * 早先这里收的是整个 `Draft`、判的是 `draft.name.isBlank()`，读起来等价，
     * 实际上把一个**一次性的决定**变成了一个**跟着输入实时重算的值**：
     * 用户在名称栏敲下第一个字的瞬间，`isBlank()` 从 true 翻成 false，
     * 这个函数的返回值就从「名称」变成「账号」，页面照着它把光标搬走了——
     * 名字才打了一个字，人就被踢到下一个框里。
     *
     * 换成 Boolean 不只是修掉那一次，是让这类错误**没法再写出来**：
     * 调用方手上没有任何随输入变化的东西可以喂给它。
     * 同一个道理，这个值也不该由页面顺手用 `draft.name.isNotBlank()` 算出来
     * （那等于把 bug 原样搬到调用点），只能来自交接槽——见 `AddEntryScreen` 里那段。
     *
     * ── 第二步刻意不聚焦 ──
     *
     * 这一步有两条路：生成一个，或者自己打一个已有的。自动弹键盘等于替他选了后者，
     * 而且键盘会把下面那个「生成一个强密码」顶出屏幕。
     */
    fun autoFocus(step: Step, seededName: Boolean): EntryForm.Field? = when (step) {
        Step.Basics -> if (seededName) EntryForm.Field.Username else EntryForm.Field.Name
        Step.Password -> null
        Step.Filing -> null
    }

    /* ══════════════════════════ 能不能往下走 ══════════════════════════ */

    /**
     * 只有第一步有硬要求，而那个要求就是 [EntryForm.nameOk]——
     * **不在这里另写一遍**。名称必填这条规矩由 [EntryForm] 一家说了算，
     * 新增流和编辑页问的是同一个函数。
     */
    fun canAdvance(step: Step, draft: EntryForm.Draft): Boolean = when (step) {
        Step.Basics -> EntryForm.nameOk(draft)
        Step.Password -> true
        Step.Filing -> EntryForm.canSave(draft)
    }

    /**
     * 按钮为什么是灰的，永远要给一句话（决策(61)）。
     * 一个没有解释的灰按钮，用户第一反应是「这个 App 卡了」，
     * 而在一条只走过一次的新增流程上，他多半就此退出去了。
     */
    fun blockReason(step: Step, draft: EntryForm.Draft): String? =
        if (canAdvance(step, draft)) null
        else "名称是唯一必填项——列表和搜索都靠它认人。"

    /** 主按钮上的字。最后一步是「保存」，不是「完成」——用户要知道这一下会写盘。 */
    fun advanceText(step: Step): String = if (isLast(step)) "保存" else "下一步"

    /**
     * 能不能直接跳到第 n 步（点进度条上的某一段）。
     *
     * 往回跳永远允许：回去改个错字不该有任何门槛。
     * 往前跳则要求**沿途每一步都已经满足**——否则点一下进度条就能绕过
     * 「名称必填」，而那条规矩是列表和搜索的地基。
     */
    fun canJumpTo(target: Step, current: Step, draft: EntryForm.Draft): Boolean {
        val from = index(current)
        val to = index(target)
        if (to <= from) return true
        for (i in from until to) {
            if (!canAdvance(steps[i], draft)) return false
        }
        return true
    }

    /* ══════════════════════════ 中途退出 ══════════════════════════ */

    /**
     * 一个字都没填。此时退出**不该弹任何拦截**。
     *
     * 用户点了加号、看了一眼、按返回——这是最常见的一次误触，
     * 而拦截框的全部作用就是让他多点一下。拦截必须只在真的会丢东西时出现，
     * 否则用户学会的是闭着眼睛点「放弃」，等到真有东西要丢的那次也照点不误。
     * 这条和 [EntryForm.isDirty] 那段注释是同一个道理。
     *
     * 比的是[EntryForm.cleaned]之后的草稿：网址框里多按的两个回车不算填了东西。
     * 但**密码里的一个空格算**——密码不 trim（决策(57)），
     * 那个空格完全可能是他有意打的。
     */
    fun isEmpty(draft: EntryForm.Draft): Boolean = EntryForm.cleaned(draft) == EntryForm.Draft()

    /**
     * 「放弃新增？」弹窗里那行小字：**填了哪几个字段，不说填的是什么**。
     *
     * 和 [EntryForm.changedSummary]、[cn.localvault.app.ui.detail.EntryDetail.deleteConfirmDetail]
     * 是同一条规矩，理由也是同一个：弹窗是一个独立的 window（决策⑭），
     * Activity 上的 `FLAG_SECURE` 不会传下去，那一屏可截图可录屏。
     * 「确定放弃吗？刚生成的 `Kx7#mQ...` 就没了」写起来非常自然，
     * 而它会把一个密码明文摆到一个不受保护的窗口上。
     * 返回值里永远不会出现任何字段的内容——由单测盯着。
     */
    fun filledSummary(draft: EntryForm.Draft): String {
        val c = EntryForm.cleaned(draft)
        return buildList {
            if (c.name.isNotEmpty()) add(EntryForm.Field.Name)
            if (c.username.isNotEmpty()) add(EntryForm.Field.Username)
            if (c.password.isNotEmpty()) add(EntryForm.Field.Password)
            if (c.domainsText.isNotEmpty()) add(EntryForm.Field.Domains)
            if (c.category.isNotEmpty()) add(EntryForm.Field.Category)
            if (c.notes.isNotEmpty()) add(EntryForm.Field.Notes)
        }.joinToString(" · ") { EntryForm.label(it) }
    }

    /* ══════════════════════════ 最后一步的回顾 ══════════════════════════ */

    data class ReviewLine(val label: String, val value: String, val dim: Boolean)

    /**
     * 第三步顶上那张回顾卡：把前两步填的东西摆出来核对一遍。
     *
     * ── 密码显示成固定 12 个圆点 ──
     *
     * 和详情页那条（决策㊽）一模一样，连**不按真实长度画**这一点都一样：
     * 位数是离线爆破时最值钱的一条边信息，8 位还是 20 位差十几个数量级。
     * 这里更不该按长度画——用户刚生成完一串 20 位的，
     * 回顾卡上画 20 个点等于把长度直接印在屏幕上。
     *
     * 这一步**没有那只眼睛**：想核对密码就点回第二步，那儿本来就是明文。
     * 在回顾卡上再开一个显示开关，等于把同一件事做两遍，
     * 而多出来的那一遍恰好在一张「快按保存了」的屏幕上。
     */
    fun review(draft: EntryForm.Draft): List<ReviewLine> {
        val c = EntryForm.cleaned(draft)
        return listOf(
            ReviewLine(EntryForm.label(EntryForm.Field.Name), c.name, dim = false),
            ReviewLine(
                EntryForm.label(EntryForm.Field.Username),
                c.username.ifEmpty { "未填" },
                dim = c.username.isEmpty(),
            ),
            ReviewLine(
                EntryForm.label(EntryForm.Field.Password),
                if (c.password.isEmpty()) "留空" else PASSWORD_DOTS,
                dim = c.password.isEmpty(),
            ),
        )
    }

    /** 12 个。见 [review] 里那段——不按真实长度画。 */
    const val PASSWORD_DOTS: String = "••••••••••••"

    /* ══════════════════════════ 重复提醒 ══════════════════════════ */

    /**
     * 库里可能已经有这一条了。
     *
     * ── 为什么编辑页没有这个，新增流有 ──
     *
     * 「其实我已经存过了」是新增流独有的错误，而且很常见：
     * 用户在一个网站上重置了密码，回来想更新，却顺手点了加号。
     * 结果是库里出现两条「招商银行」，一条密码是对的、一条是过期的，
     * 而列表上它们长得一模一样——下次登录不上时他完全不知道该信哪一条。
     * 一个密码管理器给出两个互相矛盾的答案，比给不出答案更糟。
     */
    data class Duplicate(val id: String, val name: String, val reason: Reason)

    enum class Reason {
        /** 同名 + 同账号。最强的信号。 */
        NameAndUser,

        /** 同一个主机 + 同账号。名字写得不一样（「淘宝」和「taobao」）也拦得住。 */
        DomainAndUser,

        /** 只是重名。弱信号，只在**能确定不是两个账号**的时候才报。 */
        SameName,
    }

    /**
     * ── 什么时候**不该**报 ──
     *
     * 同一个站上有两个账号是完全正常的：私人邮箱和工作邮箱、
     * 自己的淘宝和给爸妈注册的淘宝。所以两边账号都填了、而且**不一样**时，
     * 一律不报——那正是用户此刻要做的事，跳一条提醒出来只会让他以为自己做错了。
     *
     * 只有在账号相同（强信号），或者一方压根没填账号（分不清是不是同一个，
     * 但重名本身就会让列表变成两行一模一样的字）时才报。
     *
     * 匹配一律忽略大小写和首尾空白：`Admin` 和 `admin ` 是同一个账号，
     * 而用户看不出这两行有什么区别。
     */
    fun findDuplicate(entries: List<VaultEntry>, draft: EntryForm.Draft): Duplicate? {
        val c = EntryForm.cleaned(draft)
        val name = c.name.lowercase()
        if (name.isEmpty()) return null
        val user = c.username.lowercase()
        val hosts = EntryForm.domainLines(c.domainsText)
            .map { VaultIndex.normalizeDomain(it) }
            .filter { it.isNotEmpty() }
            .toSet()

        var weak: Duplicate? = null
        for (e in entries) {
            val eName = e.name.trim().lowercase()
            val eUser = e.username.trim().lowercase()
            val sameName = eName == name
            val sameUser = user.isNotEmpty() && eUser == user

            if (sameName && sameUser) {
                return Duplicate(e.id, e.name, Reason.NameAndUser)
            }
            if (sameUser && hosts.isNotEmpty()) {
                val eHosts = e.domains.map { VaultIndex.normalizeDomain(it) }.filter { it.isNotEmpty() }
                if (eHosts.any { it in hosts }) {
                    return Duplicate(e.id, e.name, Reason.DomainAndUser)
                }
            }
            // 重名，且至少有一边没填账号——分不清是不是同一个，但列表上会变成两行一样的字
            if (sameName && (user.isEmpty() || eUser.isEmpty()) && weak == null) {
                weak = Duplicate(e.id, e.name, Reason.SameName)
            }
        }
        return weak
    }

    /**
     * 提醒里只出现**名称**，不出现账号，更不会出现密码。
     *
     * 账号打上码（`138****1234`）看着更专业，但那要么泄露一半，
     * 要么码完之后用户根本认不出是哪一条——两头不落好。
     * 名称本来就是列表上那一行的主位，说出来他立刻知道指的是谁。
     */
    fun duplicateMessage(dup: Duplicate): String = when (dup.reason) {
        Reason.NameAndUser -> "库里已经有一条「${dup.name}」，账号也是同一个。"
        Reason.DomainAndUser -> "库里已经有一条「${dup.name}」用着同一个网址和账号。"
        Reason.SameName -> "库里已经有一条叫「${dup.name}」的。"
    }

    /**
     * 提醒**不给「打开那一条」的按钮**。
     *
     * 点过去就是一次导航，而正在填的草稿此刻还没有任何落点——
     * 要么当场丢掉（用户会骂人），要么再造一套「把草稿存起来、回来时接着填」的机制，
     * 而那套机制存放草稿的地方只能是内存或者 Bundle，后者正是
     * [cn.localvault.app.ui.nav.DraftHandoff] 整篇注释要堵的洞。
     *
     * 这道提醒的用处是**让他停一下想想**，不是替他做决定。真想去看那一条，
     * 按返回退出去（草稿为空就直接退，非空会问一句）比无声无息丢掉草稿好得多。
     */
    fun duplicateIsBlocking(): Boolean = false

    /* ══════════════════════════ 存完之后去哪儿 ══════════════════════════ */

    /**
     * 找出刚存进去的那一条的 id。
     *
     * [cn.localvault.app.core.session.VaultSession.addEntry] 会自己生成 UUID
     * （决策：**id 的生成只能有一个地方**，否则新增流一个、导入流一个，迟早会撞），
     * 所以调用方拿不到 id，只能从「存之前」和「存之后」两份列表里找出多出来的那个。
     *
     * 不写成 `after.last()`：那依赖「新条目一定追加在末尾」这个当下成立、
     * 但没有任何地方承诺过的实现细节。哪天排序改成按名称插入，
     * 保存完就会跳到一条不相干的条目上，而这种 bug 在测试里几乎不会被发现。
     */
    fun newestId(before: List<VaultEntry>, after: List<VaultEntry>): String? {
        val old = before.mapTo(HashSet()) { it.id }
        return after.firstOrNull { it.id !in old }?.id
    }
}
