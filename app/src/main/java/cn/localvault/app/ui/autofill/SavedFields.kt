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
 * 保存这一路的**独立模型**：用户刚刚往框里打进去的那几个值。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * `AssistStructure` 那棵树怎么走、`getAutofillValue()` 怎么读，是 M4-3b 那层薄壳的事。
 *
 * ── 为什么这是另一份模型，而不是给 [RawField] 加一个 `text` 字段 ──
 *
 * 决策(165) 在 M4-1b-1 就写下了这句话，而它欠的东西正是这个文件：
 *
 * > 填充这条路**用不到**「这个框现在写着什么」——我们是要往框里写，不是要读框里有什么。
 * > 所以那不是「记得别读」，是模型里根本没有这个字段。
 * > M4-3 的保存流程确实要读用户刚打进去的东西，那是另一条路、另一个模型，
 * > 到那时单独建，**不许往 `RawField` 上加一个 `text` 字段来省事**。
 *
 * 加一个字段确实省事，而且当天不会有任何症状。代价在别处：`RawField` 是
 * [FieldRoles] / [FieldGroups] / [FillPlan] 三层的输入，它一旦抱着明文，
 * 那三层的每一个 `toString`、每一条日志、每一个异常消息就都成了潜在的泄露点，
 * 而那三层里有几十个分支根本不需要知道值是什么。这个文件是那条边界的物理形式：
 * **填充那条路上的对象，编译期就装不下用户打的字。**
 *
 * ── 这一层只做取舍，不做改写（决策(195)）──
 *
 * 洗字符串是这个工程里的常规动作（[AutofillRow.clean] 那三件事），
 * 但那道洗是**给屏幕看的**——洗完的字只用来显示，原值另有去处。
 * 这里不一样：这里洗出来的东西**要被存进库、以后要被原样填回登录框**。
 * 一个被「压成一行、剔掉控制字符」之后才存下去的密码，登不进任何网站，
 * 而用户要到下一次登录时才发现，且不会想到是保存那一步动的手。
 *
 * 所以这一层的规矩是死的：**要么原样收下，要么整格拒收，绝不改写一个字符。**
 * 唯一的例外是账号的首尾空白（见 [capture]），那一处单独交代。
 */
object SavedFields {

    /**
     * 一格值最多多长。
     *
     * 512 不是随手定的：世上没有 512 个字符的密码，也没有 512 个字符的用户名。
     * 真读到这么长的东西，几乎一定是页面把一整段文本（协议条款、一份 JSON、
     * 一段被自动填进去的地址）塞进了一个我们判成账号或密码的框里。
     *
     * 存进去的后果比丢掉严重得多：用户会看到库里多出一条「密码」栏里躺着一整段文字的条目，
     * 而他既认不出这是什么，也不敢删。所以超长按**整格拒收**处理
     * （同决策(146)：CSV 里单格超长是唯一的硬失败）。
     */
    const val MAX_VALUE_CHARS = 512

    /** 这一格是从哪种框里读出来的。 */
    enum class Captured {
        /** 账号 / 用户名 / 邮箱 / 手机号。 */
        Username,

        /** 一个**已有**密码框（[FieldRoles.Role.Password]）。 */
        Password,

        /** 一个**要设新密码**的框（[FieldRoles.Role.NewPassword]）。 */
        NewPassword,
    }

    /**
     * 一格读到的值。
     *
     * 不是 `data class`，`toString` 手写只报形状——这是整条保存链上抱着明文的对象，
     * 和 `FillPlan.Write` 是同一类东西，`toString` 也就写得同样死（决策(144)）。
     */
    class Value internal constructor(val what: Captured, val value: String) {
        override fun toString(): String = "Value(${what.name})"
    }

    /** 一格没能收下的原因。只用来记账和写日志，**不进屏幕**。 */
    enum class Rejected {
        /** 空的，或者只有空白。 */
        Blank,

        /** 超过 [MAX_VALUE_CHARS]。 */
        TooLong,

        /**
         * 里面有控制字符或双向控制符。
         *
         * 填充条那一侧遇到这种字符是**洗掉**（[AutofillRow.clean]），这里是**拒收**——
         * 两处方向相反，理由在文件头：那边洗完只拿去显示，这边洗完要存进库。
         */
        Control,

        /**
         * **读到的是一串掩码符，不是密码。** 只对密码那两档判（见 [allMask]）。
         *
         * 现场（决策(229)）：`com.sgcc.wsgw.cn` 的密码框接的是一套安全键盘 SDK——
         * 真值全程在 SDK 自己的缓冲里（往往当场就加密），摆在 `EditText` 里的
         * **就是一串 `•`**。于是 `getAutofillValue()` 读回来的也是那串圆点，
         * 而它一路畅通地走完了整条链：账号对得上 → `How.Replace` → 落盘，
         * 把库里那条**正确的密码换成了一串圆点**。用户点开小眼睛看到的还是圆点，
         * 而这个 App 没有条目级历史、也没有撤销——那一份密码就是没了。
         *
         * 这一档不是「洗掉圆点」，是**整格拒收**（同文件头那条死规矩）。
         * 拒收之后这一格根本不进 [SaveContext]，[AutofillSave] 那边算出来的
         * `changes` 里就没有密码这一项，也就不会有任何覆盖。
         *
         * 判据故意收得很死：**整格每一个字符都来自 [MASK_CHARS] 才算**。
         * 一个真的把 `••••` 当密码用的人会被误伤（他得自己在库里改那一条），
         * 而这条误伤和它挡住的东西不在一个量级上：拒收的代价是这一次没存上，
         * 收下的代价是一条存在的密码被换掉、且找不回来。
         */
        Masked,
    }

    /**
     * 收一格。收不下时返回 null，原因由 [rejection] 单独问。
     *
     * **账号 trim 首尾空白，密码一个字符都不动。**
     * 这条不对称是有意的：
     *   · 账号那一头的空白几乎总是键盘或自动完成带进来的，
     *     而一个带前导空格的账号存进库之后，下次自动填充填出去登不进，
     *     用户会以为是密码错了——最难查的那一类症状；
     *   · 密码那一头相反。世上确实有以空格开头或结尾的密码
     *     （多半是从别处粘贴时带进来的，但用户当初就是拿它注册的）。
     *     替他 trim 一次，存下的就是一个**登不进去的密码**，
     *     而屏幕上会显示「已保存」。两种错误的代价差着一个数量级。
     */
    fun capture(what: Captured, raw: CharSequence?): Value? {
        val s = normalize(what, raw) ?: return null
        return Value(what, s)
    }

    /** [capture] 收不下时是为什么。收得下时返回 null。 */
    fun rejection(what: Captured, raw: CharSequence?): Rejected? {
        val text = raw?.toString() ?: return Rejected.Blank
        val s = if (what == Captured.Username) text.trim() else text
        if (s.isEmpty() || s.isBlank()) return Rejected.Blank
        if (s.length > MAX_VALUE_CHARS) return Rejected.TooLong
        if (s.any { isControl(it) }) return Rejected.Control
        // 掩码只对密码那两档判：账号框不会被掩码，而一个由圆点组成的**用户名**
        // 虽然古怪却是他自己打的，拒收它没有任何东西可保护。
        if (what != Captured.Username && allMask(s)) return Rejected.Masked
        return null
    }

    private fun normalize(what: Captured, raw: CharSequence?): String? {
        if (rejection(what, raw) != null) return null
        val text = raw!!.toString()
        return if (what == Captured.Username) text.trim() else text
    }

    /**
     * 掩码符：安全键盘 SDK 摆在输入框里冒充密码的那几种圆点 / 星号。
     *
     * 只收**形状上就是掩码**的那几个码位。ASCII 的 `*` 和 `.` 也在里面，
     * 尽管它们都是合法的密码字符——判据是「**整格全是**这些字符」（[allMask]），
     * 而一个通篇只有星号或只有句点的密码，比一次不可撤销的覆盖罕见得多。
     */
    private val MASK_CHARS: Set<Char> = setOf(
        '*',        // ASCII 星号
        '.',        // ASCII 句点
        '\u00B7',   // · 间隔号
        '\u2022',   // • 项目符号（最常见的那一个）
        '\u2024',   // ․ 单点前导符
        '\u2027',   // ‧ 连字点
        '\u2219',   // ∙ 点运算符
        '\u25CB',   // ○ 空心圆
        '\u25CF',   // ● 实心圆
        '\u25E6',   // ◦ 空心小圆
        '\u26AB',   // ⚫ 中等实心圆
        '\u2981',   // ⦁ Z 记号点
        '\u2B24',   // ⬤ 黑色大圆
        '\uFE61',   // ﹡ 小星号
        '\uFF0A',   // ＊ 全角星号
        '\uFF65',   // ･ 半角中点
    )

    /** 整格每一个字符都是掩码符。空串不算（那一档归 [Rejected.Blank]）。 */
    private fun allMask(s: String): Boolean = s.isNotEmpty() && s.all { it in MASK_CHARS }

    /**
     * 什么算控制字符。
     *
     * 和 [AutofillRow] 那一份**判据相同、动作相反**（那边剔掉，这边拒收），
     * 所以这里不复用它的私有函数，而是把判据原样写一遍并在两处互相指着——
     * 复用一个「返回洗过的字符串」的函数来做「要不要拒收」的判断，
     * 得先把值洗一遍再和原值比，那等于在这条路上凭空多造一份明文副本。
     *
     * 制表符和换行也算：一个密码框里读到换行，说明我们读到的不是一个密码框。
     */
    private fun isControl(c: Char): Boolean {
        val cp = c.code
        return when {
            cp < 0x20 -> true                      // C0
            cp == 0x7F -> true                     // DEL
            cp in 0x80..0x9F -> true               // C1
            cp in 0x200B..0x200F -> true           // 零宽 + LRM/RLM
            cp in 0x202A..0x202E -> true           // 双向覆盖
            cp in 0x2066..0x2069 -> true           // 双向隔离
            cp == 0xFEFF -> true                   // BOM
            else -> false
        }
    }
}

/**
 * 一次保存请求，在纯 Kotlin 这一侧的样子。
 *
 * [origin] 是 M4-1b-2 那一层算出来的**主表单**的归属——保存和填充在这一点上
 * 是同一条规矩：归属算在字段上，不算在请求上（决策(158)）。
 * 保存这一路上它更要紧一点：填错了顶多是这次没填上，
 * **存错了会在库里留下一条长期有效的错误关联**，以后每一次自动填充都用它。
 *
 * [kind] 来自 [FillPlan.Form.kind]。它回答的是「用户刚才在做什么」——
 * 登录、注册、还是改密码——而那决定了新密码该覆盖谁（见 [AutofillSave]）。
 *
 * [appLabel] 是**被保存对象提供的**字符串，进屏幕之前必须先洗一道
 * （同决策(184)/(188)，这里由 [AutofillSave.storedUnder] 统一做）。
 */
class SaveContext(
    val origin: Origin,
    val kind: FillPlan.Kind,
    val values: List<SavedFields.Value>,
    val appLabel: String? = null,
    /**
     * 这一屏上有密码框，但读回来的是一串掩码符，整格拒收了
     * （[SavedFields.Rejected.Masked]，决策(229)）。
     *
     * **拒收本身已经保住了库**——那一格根本没进 [values]，也就不可能覆盖任何东西。
     * 这个标记回答的是另一个问题：**「没有密码」这件事该怎么解释。**
     * 不带它的话，一屏安全键盘和一屏用户压根没打密码在下游长得一模一样，
     * 于是 [AutofillSave] 会拿着一个账号去新建一条**密码为空的条目**——
     * 一条永远不会被补上的空壳（下一次登录密码还是读不到，`changes` 依旧为空），
     * 而用户以为自己刚存了一份密码。见 [AutofillSave.Reason.MaskedPassword]。
     */
    val maskedPassword: Boolean = false,
) {
    private fun first(what: SavedFields.Captured): String? =
        values.firstOrNull { it.what == what }?.value

    val username: String? get() = first(SavedFields.Captured.Username)

    /** 已有密码框里读到的那一个。 */
    val password: String? get() = first(SavedFields.Captured.Password)

    /** 新密码框里读到的那一个。 */
    val newPassword: String? get() = first(SavedFields.Captured.NewPassword)

    /**
     * 一屏上出现两个以上**都判成已有密码**的框，而且值不一样。
     *
     * `FillPlan` 在填充那一侧对这种一屏的答案是「一个都不填」（底线二）。
     * 保存这一侧的答案对称：**一个都不存**。
     * 两个不同的值摆在面前，我们没有任何依据说哪一个是他要用的那个口令——
     * 猜错的后果是库里存下一个登不进去的密码，而他要到下次登录时才发现。
     *
     * 值**逐字相同**时不算分不清：那是「密码 + 确认密码」，最常见的一种形状，
     * 两个框里本来就该是同一个东西。
     */
    val conflictingPasswords: Boolean
        get() {
            val all = values.filter { it.what == SavedFields.Captured.Password }
            return all.size >= 2 && all.map { it.value }.distinct().size >= 2
        }

    /**
     * 最终该存下去的那个密码。**新密码压过已有密码。**
     *
     * 改密码页上两个框都有值：旧的那个是他现在还在用、库里多半已经存着的，
     * 新的那个是他从此要用的。存旧的等于什么都没做，而屏幕上会说「已保存」。
     */
    val effectivePassword: String? get() = newPassword ?: password

    /** 手上有没有任何一样值得存的东西。 */
    val hasAnything: Boolean get() = username != null || effectivePassword != null

    /** 同 `FillPlan.Write.toString`：只报形状，一个字符的值都不吐（决策(144)）。 */
    override fun toString(): String = "SaveContext(${values.size} values, ${kind.name})"
}
