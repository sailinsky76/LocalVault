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

/**
 * 「这一组框里，到底往哪几个框写什么」。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * [FieldGroups] 回答「哪几个框算一个表单、这个表单属于谁」，
 * 这里回答「这个表单该填哪几个框、哪几个刻意留空、为什么」。
 * 输出的 [Plan] 就是交给 M4-2a 的全部东西：它拿 `handle` 换回 `AutofillId`、
 * 拿 [Write] 装 `Dataset`，一行判断都不用再做。
 *
 * ── 两条不许破的底线 ──
 *
 * **一、绝不往「新密码」栏里填已有密码**（决策(170)）。
 * 用户在改密码页看到填充条把旧密码塞了进去，多半会直接点提交——
 * 于是他的新密码和旧密码一样，而他以为自己改过了。
 * 这是一条静悄悄的失败：不报错，不留痕，等下次泄露事件时才显出代价。
 *
 * **二、分不出新旧的密码框，一个都不填。** 一屏上出现两个以上都判成
 * 「已有密码」的框，说明我们**没认出**这是注册页还是改密码页
 * （作者既没写 `autocomplete`，也没在 id 或提示语里留下「确认 / 新」这类词）。
 * 这时候往里填，等于把上面那条底线交给运气。账号照填——
 * 账号不是秘密，填错了用户当场看得见。
 *
 * 两条底线的方向是同一个：**认不出来就少填，不要猜着填。**
 * 少填的代价是用户自己复制粘贴一次；猜错的代价他要到很久以后才发现。
 */
object FillPlan {

    /**
     * 填得出去的东西只有两样。
     *
     * 没有「验证码」这一档：那东西我们手上没有（动态验证码是二期，决策(54)），
     * 图形验证码更是看不见图。认出验证码框的全部价值在于**别把密码填进去**，
     * 见 [FieldRoles.Role.Otp]。
     */
    enum class Slot { Username, Password }

    /**
     * 这一组框整体是个什么表单。
     *
     * 它决定的不只是填什么，还有 M4-2b 要不要在按钮上方先说一句（见 [kindNote]）。
     */
    enum class Kind {
        /** 账号 + 一个已有密码框。最常见的那一种，两样都填。 */
        Login,

        /** 只有账号框。分屏登录的第一屏，填账号。 */
        UsernameStep,

        /** 只有密码框。分屏登录的第二屏（账号已经在上一屏填过了），填密码。 */
        PasswordStep,

        /**
         * 这一屏要设一个新密码（注册 / 改密码 / 重置）。
         *
         * 新密码框一律留空（底线一）。账号照填；
         * 如果同屏还有一个**明确标着「当前密码」**的框，那一个也填——
         * 改密码页上那一栏正是我们手上这条数据该去的地方，
         * 而它恰恰是用户最不可能手打对的一栏。
         */
        NewCredential,

        /**
         * 两个以上分不出新旧的密码框。密码一个都不填，只填账号（底线二）。
         */
        AmbiguousPasswords,

        /** 没有账号框也没有密码框（比如整屏只有一个验证码框）。什么都不填。 */
        Nothing,
    }

    /** 某个框为什么被跳过了。计数用，M4-4 的「为什么有时候不出现」要拿它说话。 */
    enum class Skipped {
        /** 新密码框，见底线一。 */
        NewPasswordField,

        /** 验证码框。 */
        OtpField,

        /** 一组里多出来的账号框（只填第一个）。 */
        ExtraUsernameField,

        /** 分不出新旧的那几个密码框，见底线二。 */
        AmbiguousPasswordField,
    }

    /**
     * 「往这个句柄写这一格」。**不带值**——值要等用户在填充条上挑了哪一条才知道，
     * 那一步是 [writes]。
     */
    class Target internal constructor(val handle: Long, val slot: Slot) {
        override fun toString(): String = "Target(#$handle, ${slot.name})"
    }

    /**
     * 一组框的最终清单。
     *
     * 不是 `data class`，而且 `toString` 里**没有 [origin]**——理由同
     * `FieldGroups.Group.toString`（主机名 + 承载应用的包名合起来就是一条访问记录）。
     */
    class Form internal constructor(
        val origin: Origin,
        val kind: Kind,
        val targets: List<Target>,
        /** 只记条数，不记是哪几个框，也不记内容。计数为 0 的键不会出现。 */
        val skipped: Map<Skipped, Int>,
        /** 光标此刻是不是落在这一组里。见 [pick]。 */
        val focused: Boolean,
    ) {
        val wantsUsername: Boolean get() = targets.any { it.slot == Slot.Username }
        val wantsPassword: Boolean get() = targets.any { it.slot == Slot.Password }

        /** 一个框都不填。M4-2a 见到这个就不该为它建 `Dataset`。 */
        val isEmpty: Boolean get() = targets.isEmpty()

        override fun toString(): String = "Form(${kind.name}, ${targets.size} targets)"
    }

    /**
     * 一整屏的清单 + 主表单是哪一个。
     *
     * 保留**所有**表单而不是只留主表单：系统的 `Dataset` 是按 `AutofillId` 装的，
     * 一次可以把同屏几个表单的框一起写好，而填充条只在光标所在的那个框上露出来。
     * 主表单的用处是决定「这一屏按哪个归属去挑候选条目」——
     * 那个判断只能有一个答案。
     */
    class Plan internal constructor(val forms: List<Form>, val primaryIndex: Int) {
        val primary: Form? get() = forms.getOrNull(primaryIndex)

        override fun toString(): String = "Plan(${forms.size} forms, primary=$primaryIndex)"
    }

    /* ══════════════════════════ 入口 ══════════════════════════ */

    /** M4-2a 只需要调这一个。 */
    fun forRequest(context: FillContext): Plan {
        val forms = FieldGroups.split(context).map { of(it) }
        return Plan(forms, pick(forms))
    }

    /**
     * 单组的清单。
     *
     * 顺序是有意的：先把「跳过」记满，再定 [Kind]，最后才挑 target。
     * 反过来写（边挑边记）会让「某一档忘了记账」这种漏洞藏在一个 `when` 分支里，
     * 而记账是 M4-4 唯一的说话依据。
     */
    fun of(group: FieldGroups.Group): Form {
        val usernames = group.withRole(FieldRoles.Role.Username)
        val passwords = group.withRole(FieldRoles.Role.Password)
        val newPasswords = group.withRole(FieldRoles.Role.NewPassword)
        val otps = group.withRole(FieldRoles.Role.Otp)

        val skipped = LinkedHashMap<Skipped, Int>()
        fun note(what: Skipped, count: Int) {
            if (count > 0) skipped[what] = count
        }

        note(Skipped.OtpField, otps.size)
        note(Skipped.NewPasswordField, newPasswords.size)
        note(Skipped.ExtraUsernameField, (usernames.size - 1).coerceAtLeast(0))

        val kind = when {
            // 底线二排在最前面：它盖过「有新密码框」那一档，
            // 因为两个都判成已有密码的框，本身就说明这一屏我们没看懂。
            passwords.size >= 2 -> Kind.AmbiguousPasswords
            newPasswords.isNotEmpty() -> Kind.NewCredential
            passwords.size == 1 && usernames.isNotEmpty() -> Kind.Login
            passwords.size == 1 -> Kind.PasswordStep
            usernames.isNotEmpty() -> Kind.UsernameStep
            else -> Kind.Nothing
        }
        if (kind == Kind.AmbiguousPasswords) {
            note(Skipped.AmbiguousPasswordField, passwords.size)
        }

        val targets = ArrayList<Target>(2)
        // 账号只填第一个。两个账号框最常见的成因是「邮箱 + 确认邮箱」，
        // 往第二个里填同一个值其实无害，但也有别的成因（被认错的邀请码之类），
        // 而这个应用在拿不准的时候一律少填（同决策(147)/(149) 那套代价不对称的算法）。
        usernames.firstOrNull()?.let { targets += Target(it.handle, Slot.Username) }
        if (kind == Kind.Login || kind == Kind.PasswordStep || kind == Kind.NewCredential) {
            passwords.firstOrNull()?.let { targets += Target(it.handle, Slot.Password) }
        }

        return Form(group.origin, kind, targets, skipped, group.focused)
    }

    /**
     * 主表单挑哪一个。
     *
     *   1. **光标所在那一组**，只要它有东西可填。系统告诉我们光标在哪个框里，
     *      那是「用户此刻正在看哪一屏」最硬的信号，比任何启发式都准；
     *   2. 否则第一个 [Kind.Login]——账号密码齐全的那一组多半就是正主；
     *   3. 否则第一个有东西可填的；
     *   4. 一个都没有时返回 -1，[Plan.primary] 于是是 null，M4-2a 不出填充条。
     *
     * 第 1 条要求「有东西可填」，是为了这种一屏：光标在验证码框里，
     * 而同屏还有一套账号密码。那一组自己什么都填不了，
     * 拿它当主表单等于让整屏都填不了，而用户明明看得见那两个空框。
     */
    fun pick(forms: List<Form>): Int {
        val focused = forms.indexOfFirst { it.focused && !it.isEmpty }
        if (focused >= 0) return focused
        val login = forms.indexOfFirst { it.kind == Kind.Login }
        if (login >= 0) return login
        return forms.indexOfFirst { !it.isEmpty }
    }

    /* ══════════════════════════ 真正要写下去的东西 ══════════════════════════ */

    /**
     * 「往这个句柄写这一串」。
     *
     * 这是整条自动填充链上**唯一**抱着明文的对象，所以 `toString` 手写得格外死：
     * 只有句柄和格位，一个字符的值都不露。哪天有人顺手
     * `Log.d(TAG, "writes=$list")`，那一行就是一份明文凭据（同决策(144)）。
     */
    class Write internal constructor(
        val handle: Long,
        val slot: Slot,
        val value: String,
    ) {
        override fun toString(): String = "Write(#$handle, ${slot.name})"
    }

    /**
     * 用户挑了这一条条目，那就照 [Form.targets] 把值配上。
     *
     * **值是空的那一格不写。** 往框里写一个空串不是「什么都没做」，
     * 而是把用户可能已经手打进去的东西擦掉——他会以为是自动填充把他的输入吃了。
     * 只有账号没有密码的条目是正常存在的（决策(149) 明说没有密码的行照样导入），
     * 那种条目在登录页上就只填账号。
     *
     * 全空时返回空清单，M4-2a 据此不为这一条建 `Dataset`——
     * 一条点下去什么都不会发生的填充项，比不出现更让人怀疑功能坏了
     * （同 `AutofillMatch.hasSomethingToFill` 的理由，这里是它在字段一侧的兑现）。
     */
    fun writes(form: Form, entry: VaultEntry): List<Write> =
        form.targets.mapNotNull { t ->
            val value = when (t.slot) {
                Slot.Username -> entry.username
                Slot.Password -> entry.password
            }
            if (value.isEmpty()) null else Write(t.handle, t.slot, value)
        }

    /* ══════════════════════════ 话怎么说 ══════════════════════════ */

    /**
     * 这一档要不要先对用户说一句。
     *
     * [Kind.Login] 和 [Kind.PasswordStep] 返回 null：一切照常时**一句废话都不说**
     * （同决策(95)：每一档都配一句说明的界面，读者学会的是跳过所有小字，
     * 等到真有一句要紧的，他也不会看了）。
     *
     * 这几句话里没有「失败」「出错」「稍后重试」——它们描述的都不是故障，
     * 而是几种我们**故意**少填的处境，而且每一句都得让用户知道下一步该干什么。
     */
    fun kindNote(kind: Kind): String? = when (kind) {
        Kind.Login, Kind.PasswordStep -> null

        Kind.UsernameStep ->
            "这一屏上只有账号框，所以只填了账号。密码要等下一屏出现密码框时再填一次。"

        Kind.NewCredential ->
            "这一屏上有要设新密码的栏，那几栏留空了：把你已经存着的密码填进去，" +
                "提交之后新密码就和旧的一模一样，而你会以为自己换过了。"

        Kind.AmbiguousPasswords ->
            "这一屏上有两个分不出新旧的密码框，所以密码一个都没填，只填了账号。" +
                "请自己把密码粘到该去的那一栏。"

        Kind.Nothing ->
            "这一屏上没有能填的账号框或密码框。"
    }

    /** 某一类框被跳过时的说法。M4-4 的关于页要把这四条摆出来。 */
    fun skipNote(what: Skipped): String = when (what) {
        Skipped.NewPasswordField ->
            "要设新密码的那几栏留空了：往里填已经存着的那个密码，等于没换。"

        Skipped.OtpField ->
            "验证码框留空了：短信码和图形码都不在保险库里，那东西只能你自己看着填。"

        Skipped.ExtraUsernameField ->
            "这一屏上有不止一个账号框，只填了第一个——剩下的那些拿不准是什么，宁可不动。"

        Skipped.AmbiguousPasswordField ->
            "这一屏上的几个密码框分不出哪个是现在的、哪个是要新设的，所以一个都没填。"
    }
}
