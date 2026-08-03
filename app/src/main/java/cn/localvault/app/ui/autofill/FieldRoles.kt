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

import java.util.Locale

/**
 * 「这个框是干什么的」。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 这是 M4 里规则最多、也最没法靠肉眼验证的一层：几十条关键词，
 * 在真机上一条条试要装几十个 App，试完还不知道漏了哪些。所以全部钉在单测里。
 *
 * ── 证据分四档硬度，硬的压过软的 ──
 *
 *   1. **`autofillHints`** —— 应用/网页作者自己声明的。最硬，因为它是**专门为填充写的**，
 *      写它的人就是希望被填对。
 *   2. **HTML `autocomplete`** —— W3C 那套词表，性质同上。
 *   3. **`inputType` / `<input type>`** —— 平台或页面声明的输入种类。
 *      硬在「密码框就是密码框」，软在它分不出新旧密码。
 *   4. **关键词**（资源 id、`name`、`hint`、无障碍描述）—— 最软，靠猜。
 *
 * 前面那档给出答案就不再往下走。这不只是效率问题：软信号翻硬信号的案子，
 * 表现是「明明声明了 `current-password` 却被当成新密码」，
 * 而作者能做的补救只剩下改变量名。
 *
 * ── 猜错的两个方向不对称 ──
 *
 * 认不出来（判成 [Role.Other]）→ 这个框不给填，用户自己复制粘贴，烦一次。
 * 认错了（把验证码框当密码框）→ 密码被填进一个会明文提交、甚至会被短信回显的地方。
 * 所以整套规则往「宁可不认」偏，两张负面表（[NOT_CREDENTIAL] / [NEGATE]）都宁可长一点。
 * 这套做法和 M5 列名映射的决策(147)（宽松匹配必须配排除表）是同一条经验。
 */
object FieldRoles {

    /**
     * 一个框能扮演的角色。
     *
     * 刻意**不区分邮箱 / 手机 / 用户名**——它们最后都填 `entry.username` 那一个字段，
     * 分开只会多出三条永远走同一个分支的代码。
     */
    enum class Role {
        /** 账号框：用户名 / 邮箱 / 手机号，都填 `entry.username`。 */
        Username,

        /** 已有密码框：填 `entry.password`。 */
        Password,

        /**
         * 新密码框（注册 / 改密码 / 确认密码）。
         *
         * **绝不往这里填已有密码。** 用户在改密码页看到填充条把旧密码塞进「新密码」栏，
         * 多半会直接点提交——于是他的新密码和旧密码一样，而他以为自己改过了。
         * 这一档留给 M4-4 接密码生成器。
         */
        NewPassword,

        /**
         * 一次性验证码框。短信验证码和图形验证码都归这一档。
         *
         * 两者填不了的理由不同（一个我们没有、一个我们看不见图），但**结论完全一样**，
         * 而要分开就得有一张分得清的表——没有那张表。
         * 认出它的全部价值在于**别把密码填进去**：不少页面的验证码框就在密码框正下方，
         * `inputType` 也常常是数字，认错一次，密码就跟着短信回显出去了。
         */
        Otp,

        /** 别的什么。不填。 */
        Other,
    }

    /** 结论是靠哪一档证据得出的。界面暂时用不着，排查问题时是唯一的线索。 */
    enum class Source { Hints, Html, InputType, Keyword, None }

    /**
     * 两个枚举加一个布尔，不含任何用户数据，可以是 `data class`。
     *
     * [optedOut] 是**这个框上写着 `importantForAutofill=no`** 的记号，
     * 和 [role] / [source] 是正交的两件事：它说的不是「这个框是干什么的」，
     * 而是「承载它的应用希望第三方别碰它」。为什么要把这两件事分开记，
     * 见 [DEFAULT_RESPECT_OPT_OUT] 和 [FillContext.respectOptOut]。
     */
    data class Guess(
        val role: Role,
        val source: Source,
        val optedOut: Boolean = false,
    )

    private val OTHER = Guess(Role.Other, Source.None)

    /**
     * `importantForAutofill=no` 默认听不听。**默认 false：不听。**
     *
     * 这不是一个常量开关，是一个**默认值**——真正生效的那个值随每一次请求走
     * （[FillContext.respectOptOut]），由用户在「设置 → 自动填充」里定，
     * 平台那一侧的读写在 [AutofillPolicy]。这里之所以还留一个常量，
     * 是因为纯 Kotlin 这一层不许碰 `android.*`，而 [classify] 需要一个
     * 单测和调用方都能省略的缺省。
     *
     * ── 为什么默认是「不听」 ──
     *
     * 这个旗子是平台留给应用作者的一句话：「这个框不要自动填」。它有正当用法
     * （验证码框、内部字段、一次性令牌栏），但真机上看到的主要是另一种用法。
     * com.taobao.taobao 的登录页：
     *
     * ```
     * #0 ifa=2 id=aliuser_recommend_login_account_et  hint=请输入手机号码
     * #1 ifa=2 id=aliuser_recommend_login_password_et hint=请输入密码  hints=passwordAuto
     * ```
     *
     * 一屏教科书级的登录表单，四档证据里三档都够判，被拦掉的唯一理由就是那个
     * `ifa=2`。而 `aliuser_` 这个前缀说明它来自一套通用登录组件——被拦掉的
     * 不是一个应用，是所有集成了它的应用。
     *
     * 决定不听它，理由是**这台设备的主人已经表过态了**：他在系统设置里把本应用
     * 选成了默认填充服务。应用作者在自己控件上写的偏好，压不过设备主人的选择。
     * 何况填充从来不是静默发生的——填充条要用户自己点，他看得见要填进哪个框。
     *
     * Android 文档对这一栏的定性也只是提示（hint），不是禁令；
     * 主流开源管理器同样不听它。
     *
     * ── 但它仍然是一个设置，而不是一行写死的代码 ──
     *
     * 「多数情况下被滥用」不等于「永远该忽略」。愿意让渡这部分便利、
     * 要求严格按应用声明行事的人是存在的，而这个判断不该由我们替他做。
     * 所以设置页给了那一项，默认关（＝不听），打开之后回到平台原本的语义。
     *
     * ── 有一条排除永远不在这个设置的管辖范围内 ──
     *
     * `!f.visible`。看不见的框和声明别填的框是两回事：后者用户看得见、点得到、
     * 能自己判断；前者是页面上摆一个不可见的输入框骗管理器填进去、再用脚本读走
     * （AutoSpill 那条路），用户没有拒绝的机会。那一道**永远是硬的**，
     * 任何设置都不许碰它——[classify] 里它写在所有分支前面，就是这个意思。
     */
    const val DEFAULT_RESPECT_OPT_OUT = false

    /* ══════════════════════════ 入口 ══════════════════════════ */

    fun classifyAll(
        fields: List<RawField>,
        respectOptOut: Boolean = DEFAULT_RESPECT_OPT_OUT,
    ): List<Guess> = fields.map { classify(it, respectOptOut) }

    /**
     * 单个框的判定：**两道硬性排除 + 一道听设置的**，然后才是四档证据。
     *
     * 硬的两道——看不见的、不是文本框的——走在所有猜测前面，没有任何例外。
     * 「看不见的不填」值得单独说一句：隐藏的密码框是个老套路，页面上放一个
     * 不可见的输入框骗管理器填进去，再用脚本读走。用户看不见的东西他就没法拒绝。
     *
     * 第三道「应用明说别填」由 [respectOptOut] 说了算，它随请求走
     * （[FillContext.respectOptOut]），默认见 [DEFAULT_RESPECT_OPT_OUT]。
     *
     * **拦不拦，[Guess.optedOut] 上的记号都照留。** 这一条不是顺手：
     * 排查时「被这一道拦掉」和「四个文本槽全空、压根没得猜」必须分得开，
     * 而它们原来都长成 `Other（依据 None）`，在日志里一模一样。
     */
    fun classify(f: RawField, respectOptOut: Boolean = DEFAULT_RESPECT_OPT_OUT): Guess {
        if (!f.visible) return OTHER
        if (f.autofillType != AndroidInput.AUTOFILL_TYPE_TEXT) return OTHER

        val optedOut = AndroidInput.optedOut(f.importantForAutofill)
        if (optedOut && respectOptOut) return OTHER.copy(optedOut = true)

        val g = evidence(f)
        return if (optedOut) g.copy(optedOut = true) else g
    }

    /** 四档证据本身。三道排除和那个记号都在 [classify] 里，这儿只管猜。 */
    private fun evidence(f: RawField): Guess {
        val bag = Bag(f)
        val declaredTokens = hardTokens(f)

        // 作者明说了「这是当前密码」。这一条单独提前，是为了让它**不被下面的
        // looksNew 翻掉**：一个 id 叫 password_confirm、但 autocomplete 写着
        // current-password 的框，作者的意思比变量名可靠。
        if (declaredTokens.contains(EXPLICIT_CURRENT_PASSWORD)) {
            return Guess(Role.Password, Source.Hints)
        }

        fromTokens(declaredTokens)?.let { role ->
            val src = if (f.autofillHints.isNotEmpty()) Source.Hints else Source.Html
            return Guess(refine(role, f, bag), src)
        }

        fromHtmlType(f.htmlType)?.let { return declared(it, f, bag, Source.Html) }
        fromInputType(f.inputType)?.let { return declared(it, f, bag, Source.InputType) }

        return fromKeywords(bag) ?: OTHER
    }

    /** 第三档给出的结论还要过两道手：密码要分新旧，账号要过一次负面表。 */
    private fun declared(role: Role, f: RawField, bag: Bag, source: Source): Guess = when {
        role == Role.Password ->
            if (looksNew(f, bag)) Guess(Role.NewPassword, Source.Keyword) else Guess(role, source)
        // 声明成邮箱 / 电话的还要挡一道：结账页上的「收货人手机」也是 inputType=phone，
        // 往里填账号是纯粹的添乱。
        role == Role.Username && bag.hits(NOT_CREDENTIAL) -> OTHER
        else -> Guess(role, source)
    }

    /** 硬信号说了是密码，再看一眼是不是「新密码」。 */
    private fun refine(role: Role, f: RawField, bag: Bag): Role =
        if (role == Role.Password && looksNew(f, bag)) Role.NewPassword else role

    /* ══════════════════════════ 第一、二档：声明 ══════════════════════════ */

    /**
     * `autofillHints` 和 HTML `autocomplete` 收在一起判，因为浏览器往往把网页里的
     * `autocomplete` 值原样塞进 `autofillHints`，两边用的是同一套词。
     *
     * `autocomplete` 的值可以带前缀（`section-blue shipping cc-number`）
     * 也可以带后缀（`username webauthn`），所以整串拆开、每个词都收进来。
     *
     * ── 不认 `autocomplete="off"` ──
     *
     * 那是网站在告诉浏览器「别记住这个」，不是在告诉用户的密码管理器「别填」。
     * 把它当拒绝，最先失效的是银行——银行的登录框几乎清一色写着 `off`，
     * 而那正是用户最需要一个长随机密码、也最不可能手打的地方。浏览器自己也早就不认了。
     */
    private fun hardTokens(f: RawField): Set<String> {
        val out = HashSet<String>()
        for (h in f.autofillHints) out += normalizeToken(h)
        val ac = f.htmlAutocomplete?.trim()?.lowercase(Locale.ROOT)
        if (!ac.isNullOrEmpty()) {
            for (t in ac.split(' ', '\t')) if (t.isNotEmpty()) out += normalizeToken(t)
        }
        out.remove("")
        return out
    }

    /**
     * 一个框可以带好几条声明。按**最要紧的先认**：新密码 > 密码 > 验证码 > 账号 > 明确的排除项。
     * 顺序反了的话，一个同时带 `username` 和 `newPassword` 的框（确实有这么写的）
     * 会被认成账号框，然后被填进已有密码旁边那一栏。
     */
    private fun fromTokens(tokens: Set<String>): Role? = when {
        tokens.isEmpty() -> null
        // `section-blue shipping tel` —— 结账页的收货电话，不是登录用的手机号。
        // 这两个前缀一出现，整串就和凭据无关了。
        tokens.any { it in ADDRESS_SECTION } -> Role.Other
        tokens.any { it in HINT_NEW_PASSWORD } -> Role.NewPassword
        tokens.any { it in HINT_PASSWORD } -> Role.Password
        tokens.any { it in HINT_OTP } -> Role.Otp
        tokens.any { it in HINT_USERNAME } -> Role.Username
        tokens.any { it in HINT_NOT_CREDENTIAL } -> Role.Other
        else -> null
    }

    /* ══════════════════════════ 第三档：声明的输入种类 ══════════════════════════ */

    private fun fromHtmlType(raw: String?): Role? {
        val t = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when (t) {
            "password" -> Role.Password
            "email", "tel" -> Role.Username
            "search", "hidden", "submit", "button", "reset", "image",
            "checkbox", "radio", "file", "range", "color",
            -> Role.Other
            else -> null // text / number 之类交给关键词
        }
    }

    private fun fromInputType(inputType: Int): Role? = when {
        AndroidInput.isPassword(inputType) -> Role.Password
        AndroidInput.isEmail(inputType) -> Role.Username
        AndroidInput.isPhone(inputType) -> Role.Username
        else -> null
    }

    /* ══════════════════════════ 第四档：关键词 ══════════════════════════ */

    /**
     * 顺序要紧：
     *
     *   1. [NOT_CREDENTIAL] —— 一看就不是凭据的（卡号、搜索框、收货地址）。直接出局。
     *   2. 密码 —— 走在账号前面，因为 `userPassword` 这种名字两头都沾。
     *      同时要过 [NEGATE]：「密码提示」「忘记密码」里也带着「密码」。
     *   3. 验证码。
     *   4. 账号 —— 同样要过 [NEGATE]（「密保问题」不是账号框）。
     *
     * 两张负面表分开是有原因的：[NOT_CREDENTIAL] 说的是「这个框是别的东西」，
     * [NEGATE] 说的是「这个词只是**提到**了密码/账号，不是那个框本身」。
     * 合成一张的话，「邮箱地址」里的「地址」会把一个再正常不过的账号框挡掉。
     */
    private fun fromKeywords(bag: Bag): Guess? {
        if (bag.isEmpty) return null
        if (bag.hits(NOT_CREDENTIAL)) return OTHER
        if (bag.hits(KW_PASSWORD)) {
            if (bag.hits(NEGATE)) return OTHER
            return Guess(if (bag.hits(KW_NEW)) Role.NewPassword else Role.Password, Source.Keyword)
        }
        if (bag.hits(KW_OTP)) return Guess(Role.Otp, Source.Keyword)
        if (bag.hits(KW_USERNAME)) {
            if (bag.hits(NEGATE)) return OTHER
            return Guess(Role.Username, Source.Keyword)
        }
        return null
    }

    /** 「新密码 / 确认密码 / 再输一遍」。**只在已经确定是密码框之后**问。 */
    private fun looksNew(f: RawField, bag: Bag): Boolean {
        if (hardTokens(f).any { it in HINT_NEW_PASSWORD }) return true
        return bag.hits(KW_NEW)
    }

    /* ══════════════════════════ 文本槽 ══════════════════════════ */

    /**
     * 四个文本槽拼成一袋，只拼一次。
     *
     * 同时留**原样**和**去掉分隔符**两种形式，于是 `user_name` / `user-name` /
     * `userName` 都能对上表里那个 `username`。
     *
     * 不是 `data class`，`toString` 手写——这袋子里装着页面文案和资源 id，
     * 打进日志等于把用户正在看的那一屏抄出去。
     */
    private class Bag(f: RawField) {
        private val raw: String = buildString {
            f.idEntry?.let { append(it).append(' ') }
            f.htmlName?.let { append(it).append(' ') }
            f.hintText?.let { append(it).append(' ') }
            f.contentDescription?.let { append(it) }
        }.lowercase(Locale.ROOT)

        private val squeezed: String = raw.filter { it.isLetterOrDigit() }

        val isEmpty: Boolean get() = squeezed.isEmpty()

        fun hits(table: List<String>): Boolean =
            table.any { raw.contains(it) || squeezed.contains(it) }

        override fun toString(): String = "Bag(${squeezed.length} chars)"
    }

    private fun normalizeToken(s: String): String =
        s.trim().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /* ══════════════════════════ 表 ══════════════════════════ */

    private fun words(text: String): Set<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toHashSet()

    private fun list(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** 作者明说「这是当前密码」的那一个词。见 [classify] 里为它开的小灶。 */
    private const val EXPLICIT_CURRENT_PASSWORD = "currentpassword"

    /** W3C `autocomplete` 的分区前缀。带上它们的那一串讲的是收货/账单，不是登录。 */
    private val ADDRESS_SECTION: Set<String> = words("shipping billing")

    private val HINT_USERNAME: Set<String> = words(
        """
        username newusername emailaddress email tel telnational telephone
        phone phonenumber phonenational mobile
        """
    )

    /**
     * `passwordauto` 是国内应用里实际出现的写法（真机日志里 com.taobao.taobao 和
     * com.zxunity.android.yzyx 两个不相干的应用都用了它），不是 `View` 上的官方常量——
     * 而 `autofillHints` 本来就允许塞任意字符串，官方那张表只是建议。
     *
     * 补它的理由不是「多认一个应用」，是**证据档位**：没有它的时候，
     * 有知有行那个密码框是靠 `inputType` 认出来的（日志里写着「依据 InputType」）。
     * 而 `inputType` 是会变的——用户点一下密码框旁边那个「小眼睛」，
     * `TYPE_TEXT_VARIATION_PASSWORD` 就变成可见文本，第三档证据当场消失，
     * 那个框会掉到关键词那一档甚至判成 `Other`。`autofillHints` 不会变。
     */
    private val HINT_PASSWORD: Set<String> = words("password currentpassword passwd passwordauto")

    private val HINT_NEW_PASSWORD: Set<String> = words("newpassword")

    private val HINT_OTP: Set<String> = words("smsotpcode smsotp onetimecode otpcode otp 2facode")

    /**
     * 明确不是登录凭据的声明。列出来是为了**尽早止损**——一个带着 `creditCardNumber`
     * 的框如果只是「认不出来」，关键词那一层还有机会把它猜成别的东西。
     */
    private val HINT_NOT_CREDENTIAL: Set<String> = words(
        """
        creditcardnumber creditcardsecuritycode creditcardexpirationdate
        creditcardexpirationday creditcardexpirationmonth creditcardexpirationyear
        ccnumber cccsc ccexp ccexpmonth ccexpyear cctype ccname cccsc
        postaladdress postaladdressextended postalcode addressline1 addressline2
        streetaddress addresscountry addressregion addresslocality country
        name givenname familyname middlename honorificprefix nickname
        birthdate birthdateday birthdatemonth birthdateyear bday gender
        organization organizationtitle url photo notapplicable
        """
    )

    /**
     * 「这个框是别的东西」。命中即出局，压过一切正向关键词。
     *
     * 注意这里**没有**「地址」和 `address` ——「邮箱地址」和「Email Address」
     * 是中英文里最常见的账号框写法，把它们挡掉就等于关掉了一大半网站的自动填充。
     * 真正的收货地址栏本来也匹配不上任何正向词，落到最后自然是 [Role.Other]，
     * 不需要为它专门排除一次。**负面表只用来挡「会误命中正向表」的那些词。**
     */
    private val NOT_CREDENTIAL: List<String> = list(
        """
        搜索 查找 关键词 search query keyword filter
        卡号 银行卡 信用卡 安全码 有效期 持卡人
        card cardnumber cardno cvv cvc cardholder expiry expdate
        收货 收件 联系人 邮编 邮寄 postal zipcode mailing
        昵称 nickname 真实姓名 realname 身份证 idcard idnumber 证件 护照 passport
        生日 出生 birthday 性别 gender
        金额 数量 备注 留言 评论 描述 amount quantity remark comment description
        公司 单位 职位 company organization jobtitle
        """
    )

    /**
     * 「这个词只是**提到**了密码/账号」。让正向匹配作废。
     *
     * 「重置」「找回」不在这张表里——那两个词出现在密码框上时，它就是一个**新密码框**，
     * 所以它们归 [KW_NEW]。放进这里会让「重置密码」那一页彻底认不出来，
     * 而那一页正是最该弹密码生成器的地方。
     */
    private val NEGATE: List<String> = list(
        """
        提示 忘记 强度 规则 说明 协议 同意 问题 答案 密保 记住
        hint forgot strength rule policy agree terms question answer remember
        """
    )

    private val KW_PASSWORD: List<String> = list("密码 口令 password passwd pwd passphrase")

    /**
     * 「新的还是旧的」。
     *
     * 「确认」「再次」这几个词单独看并不指向密码，所以这张表**只在已经确定是密码框之后**用，
     * 不参与第一轮判定——否则「确认收货」旁边那个框会被卷进来。
     *
     * 刻意不收单独的 `new`：`newsletter` 里就带着它。
     */
    private val KW_NEW: List<String> = list(
        """
        新密码 新口令 修改密码 设置密码 重置密码 找回密码 确认密码 重复密码
        确认 重复 再次 再输 重新输入 两次
        newpassword newpwd newpass setpassword resetpassword changepassword
        confirm confirmation retype reenter repeat again verifypassword
        password2 pwd2 pass2
        """
    )

    private val KW_OTP: List<String> = list(
        """
        验证码 短信码 动态码 动态口令 校验码 图形码 图片验证
        otp onetimecode smscode smsverify verifycode verificationcode
        authcode captcha securitycode dynamiccode
        """
    )

    private val KW_USERNAME: List<String> = list(
        """
        用户名 账号 帐号 账户 帐户 登录名 登陆名 会员名 手机号 手机 邮箱 邮件 工号 学号
        username userid usrname loginname logonname loginid logon signin
        account accountname email mail phone mobile telephone identifier
        """
    )
}
