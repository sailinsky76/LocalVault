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
 * 「这一屏要不要挂 `SaveInfo`；挂的话，看着哪几个框」。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * `SaveInfo.Builder` 怎么装、句柄怎么换回 `AutofillId`，是 M4-3b-2 那层薄壳的事
 * （同 [AutofillResponses] 之于 [AutofillOffer]）。这一层只回答两个问题：
 * **这一屏值不值得看着，以及看哪几个框。**
 *
 * ── 为什么这几行判断值得单独一个文件 ──
 *
 * 因为它们决定的是「保存框弹不弹」，而这两种错法在真机上都**不报错**：
 *
 *   · 少挂一个框：用户在改密码页把新密码打完、提交成功，保存框一次都没出现。
 *     他不会来报告这件事——他只会觉得"这个自动填充好像不太行"，
 *     然后回到手工复制粘贴。**这是这条链上最容易发生、也最不会被发现的失败。**
 *   · 多挂一屏：用户在一屏根本不是登录表单的地方（或者在保险库自己的界面里）
 *     被弹了一次保存框。他按下保存，然后什么都没发生——
 *     因为 [AutofillSave.refuse] 会在那一步拒绝。**向用户要一次确认，
 *     然后告诉他这次做不成**，比一开始就不问糟得多。
 *
 * 把这几行写成纯函数，是为了让上面两条在没有设备的地方就能钉住。
 *
 * ── 这一层和 [FillPlan] 方向相反，两处刻意不复用 ──
 *
 * [FillPlan] 问的是「哪一组**填得出**东西」，这一层问的是
 * 「哪一组用户**刚往里打了**东西」。两个问题在大多数一屏上答案相同，
 * 但有一屏它们正好相反，而那一屏恰恰是保存最要紧的一屏：
 *
 *   **一个只有新密码框的改密码页。** 填充那一侧对它的答案是「一个框都不填」
 *   （[FillPlan] 底线一：绝不往新密码栏里填已有密码），于是那一组的
 *   `Form.targets` 是空的，[FillPlan.pick] 会直接跳过它。
 *   保存这一侧对同一组的答案是「这正是要看的那一组」。
 *
 * 所以 [pickForSave] 是另写的，**不许改成调 [FillPlan.pick] 来省事**：
 * 那一改当天不会有任何症状，代价是改密码页从此再也不弹保存框。
 */
object SavePlan {

    /**
     * 看着一个框。
     *
     * 不是 `data class`，`toString` 手写只报形状——同决策(144)。
     * 这个对象本身不抱明文（值要等 `onSaveRequest` 那一刻才读），
     * 但它总是和 [Origin] 一起被传来传去，而那里面有主机名。
     */
    class Watch internal constructor(
        val handle: Long,
        val what: SavedFields.Captured,
    ) {
        override fun toString(): String = "Watch(#$handle, ${what.name})"
    }

    /** 为什么这一屏一个 `SaveInfo` 都不挂。 */
    enum class Skip {
        /** 这是保险库自己的界面。同决策(180)，不往自己身上填，也不为自己存。 */
        OwnUi,

        /** 一屏上一组框都没有（或者只剩验证码框那种）。 */
        NoForm,

        /**
         * 主表单里一个密码框都没有。
         *
         * 分屏登录的第一屏就是这样：只有账号框。那一屏**不该挂**——
         * 用户还没打密码，保存框却在他点"下一步"的时候弹出来，
         * 一是打断登录，二是存下去的会是一条只有账号、没有密码的条目，
         * 而那条东西在库里唯一的作用是以后每次都被列出来、每次都填不出密码。
         */
        NoPasswordField,

        /**
         * 一屏上两个以上分不出新旧的密码框（[FillPlan.Kind.AmbiguousPasswords]）。
         *
         * 这一档是**不看任何值就能提前知道存不成**的：
         * [AutofillSave.refuse] 到时候一定会给 [AutofillSave.Reason.CannotTellPassword]。
         * 既然如此，就不该先弹一次保存框、等用户按下去再告诉他这次不算。
         *
         * 注意这只是提前量，[AutofillSave.refuse] 那三条一条都不删：
         * 从挂 `SaveInfo` 到 `onSaveRequest` 之间页面完全可能又变了一次，
         * 而那一层才是落笔前最后一道。护栏要长在落笔处（同 [AutofillSave.proposeUpdate]）。
         */
        AmbiguousPasswords,
    }

    /**
     * 挂什么。
     *
     * [required] / [optional] 分开是因为系统那边的语义不一样：
     * **required 里的框全都有值，保存框才会弹**。
     */
    class Info internal constructor(
        val origin: Origin,
        val kind: FillPlan.Kind,
        val watches: List<Watch>,

        /**
         * 必须有值的那几个句柄。**永远只有一个**，见 [of] 里那段。
         */
        val required: List<Long>,

        /** 有值就一起交上来的那些。 */
        val optional: List<Long>,

        /**
         * 要不要加 `FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE`。
         *
         * **网页加，原生不加。** 这不是保守起见，是两种一屏的提交形态不一样：
         *   · 网页登录成功后 Activity 通常一动不动，只是 DOM 换了一批节点。
         *     不加这个旗子，系统等不到它认得的"提交"信号，保存框永远不出现——
         *     而网页恰恰是自动填充最常用的场合。
         *   · 原生应用登录成功一般会换 Activity 或 Fragment，系统自己就能触发。
         *     在那儿加上它的代价是：用户只是关掉了一个浮层（框跟着不可见了），
         *     保存框就弹出来，而他其实什么都还没提交。
         */
        val saveOnAllViewsInvisible: Boolean,
    ) {
        val wantsUsername: Boolean
            get() = watches.any { it.what == SavedFields.Captured.Username }

        val wantsPassword: Boolean
            get() = watches.any { it.what != SavedFields.Captured.Username }

        /** 一共看着几个框。薄壳装 `SaveInfo` 时要用到它判空。 */
        val size: Int get() = watches.size

        /** 同 [FillPlan.Form.toString]：不把 [origin] 打进来（那是一条访问记录）。 */
        override fun toString(): String = "Info(${kind.name}, ${watches.size} watches)"
    }

    /** 挂，还是不挂。 */
    sealed class Decision {
        class Skipped internal constructor(val why: Skip) : Decision() {
            override fun toString(): String = "Skipped(${why.name})"
        }

        class Hang internal constructor(val info: Info) : Decision() {
            override fun toString(): String = "Hang(${info.kind.name})"
        }
    }

    /* ══════════════════════════ 入口 ══════════════════════════ */

    /**
     * M4-3b-2 只需要调这一个。
     *
     * [ownPackage] 是本应用自己的包名，**不在这一层写死**——同
     * [AutofillOffer.respond] / [AutofillPick.refusal] / [AutofillSave.refuse] 那三处：
     * debug 构建的包名带着 `.debug` 后缀，写死的话"不为自己存"这一条在 debug 包上是失效的。
     */
    fun decide(context: FillContext, ownPackage: String): Decision {
        if (context.activityPackage == ownPackage) return Decision.Skipped(Skip.OwnUi)

        val groups = FieldGroups.split(context)
        val index = pickForSave(groups)
        if (index >= 0) return of(groups[index])

        // 一组带密码框的都没有。这里**再挑一次**，挑的是第一组带账号框的——
        // 不是为了挂（那一组落到 of() 里照样会被 Skip.NoPasswordField 挡下），
        // 而是为了让这一次跳过说得出**自己那一句实话**。
        // 少了这三行，分屏登录的第一屏（只有账号框，最常见的一屏之一）
        // 会被报成 Skip.NoForm，而 M4-4 的关于页于是会对用户说
        // 「这一屏上没有认得出来的登录表单」——一句和事实不符的话，
        // 说给一个正盯着屏幕上那个账号框的人听。
        val withUsername = groups.indexOfFirst {
            it.withRole(FieldRoles.Role.Username).isNotEmpty()
        }
        if (withUsername >= 0) return of(groups[withUsername])

        return Decision.Skipped(Skip.NoForm)
    }

    /**
     * 保存这一侧的主表单挑哪一组。返回 -1 表示一组都不值得看。
     *
     * 顺序和 [FillPlan.pick] 形状相同，但**每一条的"值得"判据都换成了"有没有密码框"**，
     * 理由见文件头那段：填得出东西和刚被打进东西，不是同一个问题。
     *
     *   1. **光标所在那一组**，只要它有密码框。系统告诉我们光标在哪个框里，
     *      那是"用户此刻正在看哪一屏"最硬的信号；
     *   2. 否则第一个有密码框的；
     *   3. 一个都没有 → -1。
     *
     * 这里**不为"只有账号框"的那一组兜底**（[FillPlan.pick] 第 3 条会兜）：
     * 那一组落到 [of] 里也只会得到 [Skip.NoPasswordField]，
     * 而在这儿就跳过去，可以让"一屏上账号在一组、密码在另一组"这种
     * 分栏页面选中真正带密码的那一组，而不是选中排在前面的账号组。
     */
    fun pickForSave(groups: List<FieldGroups.Group>): Int {
        val focused = groups.indexOfFirst { it.focused && hasPassword(it) }
        if (focused >= 0) return focused
        return groups.indexOfFirst { hasPassword(it) }
    }

    private fun hasPassword(group: FieldGroups.Group): Boolean =
        group.fields.any {
            it.role == FieldRoles.Role.Password || it.role == FieldRoles.Role.NewPassword
        }

    /**
     * 单组的答案。
     *
     * ── 看哪几个框 ──
     *
     * **所有密码框都看，包括新密码框。** 这一条正好和 [FillPlan] 底线一相反
     * （那边一律不往新密码框里写），而两处方向相反是有意的：
     * 注册页和改密码页上，用户刚打进去的那个值**只在新密码框里**。
     * 不看它，这两种一屏就永远存不下东西——而它们恰恰是最值得存的两种，
     * 刚注册完那一次是最值钱、也最不可能再打一遍的一次（同 [AutofillSave.outcome] 那段）。
     *
     * 哪一个值最后被存下去不在这一层决定，那是 [SaveContext.effectivePassword]
     * 的事（新密码压过已有密码，75 条用例钉着）。这一层只管**别漏读**。
     *
     * **账号只看第一个**，同 [FillPlan.of] 那一行的理由：两个账号框最常见的成因是
     * "邮箱 + 确认邮箱"，但也有别的成因，而这个应用在拿不准时一律少动。
     *
     * ── required 为什么只放一个 ──
     *
     * 系统对 required 的语义是"这几个框**全都**有值，保存框才弹"。
     * 把两个密码框都放进去，在"密码 + 确认密码"那种一屏上碰巧是对的，
     * 但只要用户跳过了其中一个可选的确认框，保存框就再也不出现——
     * 而他刚注册完，那个密码此刻还只存在于他的短期记忆里。
     *
     * 所以 required 只放**一个**：新密码框优先（那才是他刚打的），
     * 没有新密码框时放第一个已有密码框。其余全部进 optional，
     * 有值就一起交上来，由 [SavedFields] 和 [SaveContext] 去取舍——
     * 那一层已经被 75 条用例钉住了，这一层不该再判一次。
     *
     * 账号也进 optional：分屏登录的第二屏根本没有账号框，
     * 把它放进 required 等于让那一屏永远不弹保存框。
     */
    fun of(group: FieldGroups.Group): Decision {
        val kind = FillPlan.of(group).kind
        if (kind == FillPlan.Kind.AmbiguousPasswords) {
            return Decision.Skipped(Skip.AmbiguousPasswords)
        }

        val usernames = group.withRole(FieldRoles.Role.Username)
        val passwords = group.withRole(FieldRoles.Role.Password)
        val newPasswords = group.withRole(FieldRoles.Role.NewPassword)

        if (passwords.isEmpty() && newPasswords.isEmpty()) {
            return Decision.Skipped(Skip.NoPasswordField)
        }

        val watches = ArrayList<Watch>(4)
        usernames.firstOrNull()?.let {
            watches += Watch(it.handle, SavedFields.Captured.Username)
        }
        for (p in passwords) watches += Watch(p.handle, SavedFields.Captured.Password)
        for (p in newPasswords) watches += Watch(p.handle, SavedFields.Captured.NewPassword)

        // 见上面那段：只有一个 required，而且优先是新密码框
        val anchor = (newPasswords.firstOrNull() ?: passwords.first()).handle
        val optional = watches.map { it.handle }.filter { it != anchor }

        return Decision.Hang(
            Info(
                origin = group.origin,
                kind = kind,
                watches = watches,
                required = listOf(anchor),
                optional = optional,
                saveOnAllViewsInvisible = group.origin is Origin.Web,
            )
        )
    }

    /* ══════════════════════════ 话怎么说 ══════════════════════════ */

    /**
     * 每一档不挂的理由对应的一句实话。**M4-4 的关于页要把它们和
     * [AutofillSave.note] 那六条摆在一起**——用户问的是同一个问题
     * （"为什么这次没问我要不要存"），而答案分散在两个枚举里只是我们的实现细节。
     *
     * 这几句里没有"失败""出错"：它们描述的都不是故障，
     * 是几种我们**故意**不出手的处境（同 [AutofillSave.note] / [FillPlan.kindNote]）。
     */
    fun note(why: Skip): String = when (why) {
        Skip.OwnUi ->
            "这是保险库自己的界面，不会把自己这一屏上的输入存成条目。"

        Skip.NoForm ->
            "这一屏上没有认得出来的登录表单，所以没有问你要不要存。"

        Skip.NoPasswordField ->
            "这一屏上只有账号框，没有密码框。等下一屏出现密码框、你把密码打进去之后，" +
                "才会问你要不要存——只存一个账号的条目，以后每次都会填不出密码来。"

        Skip.AmbiguousPasswords ->
            "这一屏上有两个分不出新旧的密码框，没法判断该存哪一个，所以这次没有问。" +
                "你可以自己在保险库里改那一条。"
    }
}
