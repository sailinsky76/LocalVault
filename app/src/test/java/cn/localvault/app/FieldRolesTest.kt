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

package cn.localvault.app

import cn.localvault.app.ui.autofill.AndroidInput
import cn.localvault.app.ui.autofill.FieldRoles
import cn.localvault.app.ui.autofill.FieldRoles.Role
import cn.localvault.app.ui.autofill.RawField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 字段角色识别。
 *
 * 这一层在真机上验证不完：要覆盖这几十条规则得装几十个 App，
 * 装完还不知道漏了哪些。而认错一次的后果是密码被填进验证码框、
 * 或者旧密码被填进「新密码」栏然后用户直接点了提交。
 */
class FieldRolesTest {

    private var seq = 0L

    private fun f(
        hints: List<String> = emptyList(),
        inputType: Int = 0,
        autofillType: Int = AndroidInput.AUTOFILL_TYPE_TEXT,
        importantForAutofill: Int = AndroidInput.IMPORTANT_FOR_AUTOFILL_AUTO,
        idEntry: String? = null,
        hintText: String? = null,
        contentDescription: String? = null,
        htmlType: String? = null,
        htmlName: String? = null,
        htmlAutocomplete: String? = null,
        visible: Boolean = true,
    ) = RawField(
        handle = seq++,
        autofillHints = hints,
        inputType = inputType,
        autofillType = autofillType,
        importantForAutofill = importantForAutofill,
        idEntry = idEntry,
        hintText = hintText,
        contentDescription = contentDescription,
        htmlType = htmlType,
        htmlName = htmlName,
        htmlAutocomplete = htmlAutocomplete,
        visible = visible,
    )

    private fun role(field: RawField): Role = FieldRoles.classify(field).role

    private val PWD = AndroidInput.TYPE_CLASS_TEXT or AndroidInput.TYPE_TEXT_VARIATION_PASSWORD
    private val VISIBLE_PWD =
        AndroidInput.TYPE_CLASS_TEXT or AndroidInput.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    private val WEB_PWD =
        AndroidInput.TYPE_CLASS_TEXT or AndroidInput.TYPE_TEXT_VARIATION_WEB_PASSWORD
    private val NUM_PWD =
        AndroidInput.TYPE_CLASS_NUMBER or AndroidInput.TYPE_NUMBER_VARIATION_PASSWORD
    private val EMAIL = AndroidInput.TYPE_CLASS_TEXT or AndroidInput.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

    /* ══════════════════════════ 第一档：声明 ══════════════════════════ */

    @Test
    fun `autofillHints 说什么就是什么`() {
        assertEquals(Role.Username, role(f(hints = listOf("username"))))
        assertEquals(Role.Password, role(f(hints = listOf("password"))))
        assertEquals(Role.NewPassword, role(f(hints = listOf("newPassword"))))
        assertEquals(Role.Otp, role(f(hints = listOf("smsOTPCode"))))
        assertEquals(Role.Other, role(f(hints = listOf("creditCardNumber"))))
        assertEquals(FieldRoles.Source.Hints, FieldRoles.classify(f(hints = listOf("username"))).source)
    }

    @Test
    fun `一个框带好几条声明时按最要紧的认`() {
        // 同时写 username 和 newPassword 的表单确实存在。
        // 认成账号框的话，它旁边那个真的密码框就会被填进已有密码。
        assertEquals(
            Role.NewPassword,
            role(f(hints = listOf("username", "newPassword"))),
        )
    }

    /* ══════════════════════════ 第二档：HTML autocomplete ══════════════════════════ */

    @Test
    fun `autocomplete 的前后缀不影响识别`() {
        assertEquals(Role.Username, role(f(htmlAutocomplete = "username webauthn")))
        assertEquals(Role.NewPassword, role(f(htmlAutocomplete = "new-password")))
        assertEquals(Role.Otp, role(f(htmlAutocomplete = "one-time-code")))
    }

    @Test
    fun `作者明说 current-password 时变量名翻不了它`() {
        // id 叫 password_confirm、但 autocomplete 写着 current-password——
        // 作者专门为填充写的那句话，比变量名可靠。
        assertEquals(
            Role.Password,
            role(f(htmlAutocomplete = "current-password", idEntry = "password_confirm")),
        )
    }

    @Test
    fun `结账页的收货电话不是登录用的手机号`() {
        assertEquals(Role.Other, role(f(htmlAutocomplete = "section-blue shipping tel")))
        assertEquals(Role.Other, role(f(htmlAutocomplete = "billing tel")))
    }

    @Test
    fun `不认 autocomplete=off`() {
        // 那是网站在告诉浏览器「别记住」，不是在告诉密码管理器「别填」。
        // 认它的话，最先失效的是银行——银行登录框几乎清一色写着 off。
        assertEquals(Role.Password, role(f(htmlAutocomplete = "off", inputType = PWD)))
        assertEquals(Role.Username, role(f(htmlAutocomplete = "off", htmlName = "loginId")))
    }

    /* ══════════════════════════ 第三档：输入种类 ══════════════════════════ */

    @Test
    fun `HTML input type`() {
        assertEquals(Role.Password, role(f(htmlType = "password")))
        assertEquals(Role.Username, role(f(htmlType = "email")))
        assertEquals(Role.Username, role(f(htmlType = "tel")))
        assertEquals(Role.Other, role(f(htmlType = "search")))
        assertEquals(Role.Other, role(f(htmlType = "hidden")))
        assertEquals(Role.Other, role(f(htmlType = "checkbox")))
    }

    @Test
    fun `四种密码 inputType 都认得出`() {
        assertEquals(Role.Password, role(f(inputType = PWD)))
        assertEquals(Role.Password, role(f(inputType = VISIBLE_PWD)))
        assertEquals(Role.Password, role(f(inputType = WEB_PWD)))
        assertEquals(Role.Password, role(f(inputType = NUM_PWD)))
    }

    @Test
    fun `邮箱与电话 inputType 都算账号框`() {
        // 邮箱、手机、用户名最后都填 entry.username 那一个字段，分开只会多出
        // 三条永远走同一个分支的代码。
        assertEquals(Role.Username, role(f(inputType = EMAIL)))
        assertEquals(Role.Username, role(f(inputType = AndroidInput.TYPE_CLASS_PHONE)))
    }

    @Test
    fun `电话框碰上收货人就出局`() {
        assertEquals(
            Role.Other,
            role(f(inputType = AndroidInput.TYPE_CLASS_PHONE, hintText = "收货人手机")),
        )
    }

    /* ══════════════════════════ 新密码 ══════════════════════════ */

    @Test
    fun `密码框上的确认与新字样会翻成新密码`() {
        assertEquals(Role.NewPassword, role(f(inputType = PWD, idEntry = "et_confirm_pwd")))
        assertEquals(Role.NewPassword, role(f(inputType = PWD, hintText = "请输入新密码")))
        assertEquals(Role.NewPassword, role(f(inputType = PWD, hintText = "Confirm Password")))
        assertEquals(Role.NewPassword, role(f(inputType = PWD, idEntry = "et_reset_password")))
        assertEquals(Role.NewPassword, role(f(hints = listOf("password"), idEntry = "new_password")))
    }

    @Test
    fun `绝不把新密码框当成已有密码框`() {
        // 用户在改密码页看到填充条把旧密码塞进「新密码」栏，多半直接点提交——
        // 于是新密码和旧密码一样，而他以为自己改过了。
        val newPwd = f(inputType = PWD, hintText = "请设置新密码")
        assertNotEquals(Role.Password, role(newPwd))
        assertEquals(Role.NewPassword, role(newPwd))
    }

    @Test
    fun `确认这类词不参与第一轮判定`() {
        // 「确认」单独看不指向密码。一个「确认收货」旁边的框不该被卷进来。
        assertEquals(Role.Other, role(f(hintText = "确认收货")))
        assertEquals(Role.Other, role(f(idEntry = "btn_confirm")))
    }

    /* ══════════════════════════ 第四档：关键词 ══════════════════════════ */

    @Test
    fun `中文账号框`() {
        assertEquals(Role.Username, role(f(idEntry = "et_account", hintText = "请输入手机号")))
        assertEquals(Role.Username, role(f(hintText = "用户名")))
        assertEquals(Role.Username, role(f(hintText = "邮箱地址")))
        assertEquals(Role.Username, role(f(hintText = "登录名")))
    }

    @Test
    fun `英文账号框与分隔符变体`() {
        assertEquals(Role.Username, role(f(htmlName = "j_username")))
        assertEquals(Role.Username, role(f(hintText = "Email Address")))
        assertEquals(Role.Username, role(f(idEntry = "user_name")))
        assertEquals(Role.Username, role(f(idEntry = "login-id")))
    }

    @Test
    fun `密码关键词走在账号前面`() {
        // userPassword 这种名字两头都沾，顺序反了会被认成账号框。
        assertEquals(Role.Password, role(f(idEntry = "userPassword")))
        assertEquals(Role.Password, role(f(idEntry = "et_pwd", hintText = "请输入密码")))
    }

    @Test
    fun `验证码框`() {
        assertEquals(
            Role.Otp,
            role(f(idEntry = "et_code", hintText = "请输入验证码", inputType = AndroidInput.TYPE_CLASS_NUMBER)),
        )
        assertEquals(Role.Otp, role(f(hintText = "图形码")))
        assertEquals(Role.Otp, role(f(htmlName = "captcha")))
    }

    /* ══════════════════════════ 两张负面表 ══════════════════════════ */

    @Test
    fun `提到密码不等于是密码框`() {
        assertEquals(Role.Other, role(f(hintText = "密码提示问题")))
        assertEquals(Role.Other, role(f(idEntry = "tv_forgot_password")))
        assertEquals(Role.Other, role(f(hintText = "密保问题答案")))
        assertEquals(Role.Other, role(f(hintText = "密码强度")))
    }

    @Test
    fun `一看就不是凭据的框`() {
        assertEquals(Role.Other, role(f(idEntry = "search_src_text", hintText = "搜索")))
        assertEquals(Role.Other, role(f(hintText = "请输入银行卡号")))
        assertEquals(Role.Other, role(f(hintText = "收货地址")))
        assertEquals(Role.Other, role(f(hintText = "昵称")))
        assertEquals(Role.Other, role(f(hintText = "备注")))
        assertEquals(Role.Other, role(f(hintText = "身份证号")))
    }

    @Test
    fun `负面表里没有地址两个字`() {
        // 「邮箱地址」和「Email Address」是中英文里最常见的账号框写法。
        // 把「地址」放进负面表，等于关掉一大半网站的自动填充。
        // 真正的收货地址栏本来也匹配不上任何正向词，落到最后自然是 Other。
        assertEquals(Role.Username, role(f(hintText = "邮箱地址")))
        assertEquals(Role.Username, role(f(hintText = "Email address")))
        assertEquals(Role.Other, role(f(hintText = "详细地址")))
    }

    @Test
    fun `remember 里的 member 不会被当成会员名`() {
        assertEquals(Role.Other, role(f(idEntry = "cb_remember_account")))
    }

    /* ══════════════════════════ 三道硬性排除 ══════════════════════════ */

    @Test
    fun `看不见的框一律不填`() {
        // 隐藏的密码框是个老套路：放一个不可见的框骗管理器填进去，再用脚本读走。
        // 用户看不见的东西他就没法拒绝，所以这一条不给任何例外。
        assertEquals(Role.Other, role(f(inputType = PWD, visible = false)))
        assertEquals(Role.Other, role(f(hints = listOf("password"), visible = false)))
    }

    @Test
    fun `默认不听应用的别填声明`() {
        // DEFAULT_RESPECT_OPT_OUT = false。真机上这一档正是淘宝登录页那两个框
        // （ifa=2，但 id 里明写着 login_account / login_password）能被填的原因。
        assertEquals(false, FieldRoles.DEFAULT_RESPECT_OPT_OUT)

        for (ifa in listOf(
            AndroidInput.IMPORTANT_FOR_AUTOFILL_NO,
            AndroidInput.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
        )) {
            val g = FieldRoles.classify(f(inputType = PWD, importantForAutofill = ifa))
            // 放行之后，这个框该认成什么就认成什么——四档证据一档都不少
            assertEquals(Role.Password, g.role)
            assertEquals(FieldRoles.Source.InputType, g.source)
        }
    }

    @Test
    fun `设置打开之后就听那句声明`() {
        for (ifa in listOf(
            AndroidInput.IMPORTANT_FOR_AUTOFILL_NO,
            AndroidInput.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
        )) {
            val g = FieldRoles.classify(
                f(inputType = PWD, importantForAutofill = ifa),
                respectOptOut = true,
            )
            assertEquals(Role.Other, g.role)
            assertEquals(FieldRoles.Source.None, g.source)
        }
    }

    @Test
    fun `记号两档都留下`() {
        // `optedOut` 和 `role` 是两件事：前者说「应用希望第三方别碰」，
        // 后者说「这个框是干什么的」。记号无论设置摆哪边都必须留下——
        // 少了它，「被这一道拦掉」和「四个文本槽全空、压根没得猜」
        // 在排查日志里长得一模一样，而两者的修法完全不同。
        val field = f(inputType = PWD, importantForAutofill = AndroidInput.IMPORTANT_FOR_AUTOFILL_NO)
        assertEquals(true, FieldRoles.classify(field, respectOptOut = false).optedOut)
        assertEquals(true, FieldRoles.classify(field, respectOptOut = true).optedOut)
    }

    @Test
    fun `批量判定也认这个设置`() {
        // classifyAll 是排查转储走的那条路。它要是漏了这个参数，
        // 日志里写的判定和这一次真正走的判定会是两个结论。
        val fields = listOf(
            f(inputType = PWD, importantForAutofill = AndroidInput.IMPORTANT_FOR_AUTOFILL_NO),
        )
        assertEquals(listOf(Role.Password), FieldRoles.classifyAll(fields).map { it.role })
        assertEquals(
            listOf(Role.Other),
            FieldRoles.classifyAll(fields, respectOptOut = true).map { it.role },
        )
    }

    @Test
    fun `没有声明别填的框不留记号`() {
        assertEquals(false, FieldRoles.classify(f(inputType = PWD)).optedOut)
        assertEquals(
            false,
            FieldRoles.classify(
                f(inputType = PWD, importantForAutofill = AndroidInput.IMPORTANT_FOR_AUTOFILL_YES),
            ).optedOut,
        )
    }

    @Test
    fun `看不见的框不受那个设置影响`() {
        // 这个设置管的只有「应用明说别填」那一道。看不见的框是另一回事：
        // 用户没有拒绝的机会（AutoSpill 那条路），任何设置都不许碰它。
        val hidden = f(
            inputType = PWD,
            visible = false,
            importantForAutofill = AndroidInput.IMPORTANT_FOR_AUTOFILL_NO,
        )
        for (respect in listOf(false, true)) {
            val g = FieldRoles.classify(hidden, respectOptOut = respect)
            assertEquals(Role.Other, g.role)
            assertEquals(FieldRoles.Source.None, g.source)
        }
    }

    @Test
    fun `passwordAuto 这个非官方 hint 算硬声明`() {
        // 真机上 com.taobao.taobao 和 com.zxunity.android.yzyx 都用了它。
        // 关键不是「多认一个应用」，是档位：没有这一条时它只能靠 inputType 认出来，
        // 而用户点一下「小眼睛」inputType 就变了，这一档证据当场消失。
        val g = FieldRoles.classify(f(hints = listOf("passwordAuto")))
        assertEquals(Role.Password, g.role)
        assertEquals(FieldRoles.Source.Hints, g.source)

        // 没有 inputType 兜底也照样认得出来——这正是补它的意义
        assertEquals(Role.Password, role(f(hints = listOf("passwordAuto"), inputType = 0)))
    }

    @Test
    fun `不是文本框就不填`() {
        assertEquals(
            Role.Other,
            role(f(autofillType = AndroidInput.AUTOFILL_TYPE_TOGGLE, hintText = "密码")),
        )
        assertEquals(
            Role.Other,
            role(f(autofillType = AndroidInput.AUTOFILL_TYPE_LIST, hints = listOf("username"))),
        )
    }

    @Test
    fun `什么线索都没有时认不出来`() {
        assertEquals(Role.Other, role(f()))
        assertEquals(FieldRoles.Source.None, FieldRoles.classify(f()).source)
    }

    /* ══════════════════════════ 位运算 ══════════════════════════ */

    @Test
    fun `抄过来的那几个位值算得对`() {
        // 这几个常量是从安卓平台抄的（为了这一层能纯 JVM 跑）。抄错的话
        // 表现是「某一类框从此再也认不出来」，在真机上很难归因，所以在这儿钉一遍。
        assertEquals(true, AndroidInput.isPassword(PWD))
        assertEquals(true, AndroidInput.isPassword(NUM_PWD))
        assertEquals(false, AndroidInput.isPassword(EMAIL))
        assertEquals(false, AndroidInput.isPassword(AndroidInput.TYPE_CLASS_TEXT))
        assertEquals(true, AndroidInput.isEmail(EMAIL))
        assertEquals(false, AndroidInput.isEmail(PWD))
        assertEquals(true, AndroidInput.isPhone(AndroidInput.TYPE_CLASS_PHONE))
        assertEquals(false, AndroidInput.isPhone(AndroidInput.TYPE_CLASS_TEXT))
        assertEquals(true, AndroidInput.optedOut(AndroidInput.IMPORTANT_FOR_AUTOFILL_NO))
        assertEquals(false, AndroidInput.optedOut(AndroidInput.IMPORTANT_FOR_AUTOFILL_YES))
        assertEquals(false, AndroidInput.optedOut(AndroidInput.IMPORTANT_FOR_AUTOFILL_AUTO))
    }

    /* ══════════════════════════ 批量 ══════════════════════════ */

    @Test
    fun `一次判一屏`() {
        val fields = listOf(
            f(idEntry = "et_account", hintText = "手机号"),
            f(inputType = PWD, idEntry = "et_pwd"),
            f(idEntry = "et_code", hintText = "验证码"),
            f(idEntry = "btn_login"),
        )
        assertEquals(
            listOf(Role.Username, Role.Password, Role.Otp, Role.Other),
            FieldRoles.classifyAll(fields).map { it.role },
        )
    }

    /* ══════════════════════════ 不吐内容 ══════════════════════════ */

    @Test
    fun `RawField 的 toString 不带网址和文案`() {
        // 这里面没有密码，但有 webDomain：打进日志等于把用户上过哪些站抄进 logcat。
        val field = RawField(
            handle = 7L,
            hintText = "请输入密码",
            webDomain = "bank.example.com",
        )
        val s = field.toString()
        assertEquals(false, s.contains("bank.example.com"))
        assertEquals(false, s.contains("请输入密码"))
        assertEquals(true, s.contains("7"))
    }
}
