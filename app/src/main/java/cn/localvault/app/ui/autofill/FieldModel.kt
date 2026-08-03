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
 * 一次填充请求在**纯 Kotlin 这一侧**长什么样。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * `AssistStructure` 那棵树怎么走、`AutofillId` 怎么拿，全是 M4-2a 那层薄壳的事
 * （同 `SafImportSource` 之于 `RestoreModel`）。这一层只拿到走完树之后剩下的事实。
 *
 * ── 这个模型里刻意**没有**「这个框现在写着什么」 ──
 *
 * `AssistStructure.ViewNode` 是给得出当前文本的（`getText()` / `getAutofillValue()`），
 * 而那正是最不能进这一层的东西：屏幕上那个密码框里，可能已经躺着用户上一次输入的口令，
 * 或者他刚打了一半的密码。它一旦进了模型，就会跟着 `RawField` 一路传到
 * 分类器、分组器、日志、异常消息里去。
 *
 * 填充这条路**用不到它**——我们是要往框里写，不是要读框里有什么。
 * 所以这里不是「记得别读」，是**模型里根本没有这个字段**
 * （同 `EntryForm` 那条「摘要函数收不到字段值」、`ImportSource` 只有 `read()` 的做法）。
 *
 * 将来 M4-3 的保存流程确实要读用户刚打进去的东西，那是另一条路、另一个模型，
 * 到那时单独建，**不许往 [RawField] 上加一个 `text` 字段来省事**。
 */
class RawField(
    /**
     * 不透明句柄。M4-2a 拿它换回真正的 `AutofillId`。
     *
     * 这一层不认识 `AutofillId`，也不需要认识——它只要能说出「第几个框填什么」，
     * 由薄壳负责把号码翻回系统对象。
     */
    val handle: Long,

    /** 应用/网页自己声明的填充提示。最硬的信号，见 [FieldRoles]。 */
    val autofillHints: List<String> = emptyList(),

    /** `android:inputType` 的位。常量抄在 [AndroidInput] 里。 */
    val inputType: Int = 0,

    /** 系统给的填充类型（文本 / 开关 / 列表 / 日期）。见 [AndroidInput]。 */
    val autofillType: Int = AndroidInput.AUTOFILL_TYPE_TEXT,

    /** `importantForAutofill`。应用明说「别填我」的要听。见 [AndroidInput]。 */
    val importantForAutofill: Int = AndroidInput.IMPORTANT_FOR_AUTOFILL_AUTO,

    /** 资源 id 的名字，如 `et_username`。安卓原生表单主要靠它认。 */
    val idEntry: String? = null,

    /** `android:hint`，屏幕上那句灰字。 */
    val hintText: String? = null,

    /** 无障碍描述。有些表单只在这里写清楚这个框是干什么的。 */
    val contentDescription: String? = null,

    /** 网页 `<input>` 的 `type` 属性。 */
    val htmlType: String? = null,

    /** 网页 `<input>` 的 `name` 属性。 */
    val htmlName: String? = null,

    /** 网页 `<input>` 的 `autocomplete` 属性。W3C 那套词表，和 hints 一样硬。 */
    val htmlAutocomplete: String? = null,

    /**
     * 这个框自称属于哪个网站。**这是一句自称**（决策(158)），
     * 判断的时候永远要和 [FillContext.activityPackage] 一起看。
     *
     * 原生框这里是 null。
     */
    val webDomain: String? = null,

    /** 这个框在屏幕上看得见。看不见的一律不填，见 [FieldRoles.classify]。 */
    val visible: Boolean = true,

    /** 光标在不在这个框里。分组的时候用得着（M4-1b-2）。 */
    val focused: Boolean = false,
) {
    /**
     * 不是 `data class`，`toString` 手写只报形状——同决策(144)。
     *
     * 这里面没有密码，但有 [webDomain]：把它打进日志，等于把用户上过哪些站
     * 抄进 logcat，而那本身就是一份不该外泄的清单（同 `DomainMatch.Hit` 的理由）。
     */
    override fun toString(): String = "RawField(#$handle)"
}

/**
 * 一次填充请求。
 *
 * [activityPackage] 由系统给出（`FillRequest` 里那个 activity 组件的包名），
 * **应用改不了自己的包名**，这是整条链上最硬的一个事实。
 * 它和每个字段各自的 [RawField.webDomain] 一起，构成决策(158) 里那两条线。
 */
class FillContext(
    val activityPackage: String,
    val fields: List<RawField>,
    /**
     * 这一次请求听不听「应用明说别填」（`importantForAutofill=no`）。
     *
     * **它跟着请求走，不是全局状态。** 值来自用户在「设置 → 自动填充」里的那一项，
     * 由薄壳（[AutofillPolicy]）在每次 `onFillRequest` 开头读一次、传进来；
     * 语义和默认值写在 [FieldRoles.DEFAULT_RESPECT_OPT_OUT] 上。
     *
     * 放在这儿而不是放个可写的全局变量，是为了让 [FieldRoles] / [FieldGroups] /
     * [FillPlan] 这一整层继续是纯函数——同一份 [FillContext] 进去，
     * 永远是同一个结论，单测里两种取值各钉一遍就够，不必担心用例之间互相串。
     */
    val respectOptOut: Boolean = FieldRoles.DEFAULT_RESPECT_OPT_OUT,
) {
    override fun toString(): String = "FillContext(${fields.size} fields)"
}

/**
 * 从安卓平台抄过来的几个位值和常量。
 *
 * **为什么抄而不是 `import android.text.InputType`：** 抄一份进来，
 * [FieldRoles] 那一整层就能在纯 JVM 上跑单测——而它恰恰是最需要单测的一层
 * （几十条关键词规则，靠肉眼在真机上一个个试根本试不完）。
 *
 * 抄这几个值是安全的：它们是公开 API 的一部分，值改了会破坏所有已发布应用的
 * 二进制兼容，平台不会动。真要出错也会当场被发现——某一类框从此再也认不出来，
 * 而不是悄悄认错。
 */
object AndroidInput {

    // android.text.InputType
    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_MASK_VARIATION = 0x00000ff0

    const val TYPE_CLASS_TEXT = 0x00000001
    const val TYPE_CLASS_NUMBER = 0x00000002
    const val TYPE_CLASS_PHONE = 0x00000003

    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    // android.view.View
    const val AUTOFILL_TYPE_NONE = 0
    const val AUTOFILL_TYPE_TEXT = 1
    const val AUTOFILL_TYPE_TOGGLE = 2
    const val AUTOFILL_TYPE_LIST = 3
    const val AUTOFILL_TYPE_DATE = 4

    const val IMPORTANT_FOR_AUTOFILL_AUTO = 0
    const val IMPORTANT_FOR_AUTOFILL_YES = 1
    const val IMPORTANT_FOR_AUTOFILL_NO = 2
    const val IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS = 4
    const val IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS = 8

    /** 这个 `inputType` 是不是一个密码框（四种变体都算）。 */
    fun isPassword(inputType: Int): Boolean {
        val cls = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return when (cls) {
            TYPE_CLASS_TEXT -> variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
            TYPE_CLASS_NUMBER -> variation == TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** 这个 `inputType` 是不是一个邮箱框。 */
    fun isEmail(inputType: Int): Boolean {
        if (inputType and TYPE_MASK_CLASS != TYPE_CLASS_TEXT) return false
        val variation = inputType and TYPE_MASK_VARIATION
        return variation == TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    /** 这个 `inputType` 是不是一个电话号码框。 */
    fun isPhone(inputType: Int): Boolean =
        inputType and TYPE_MASK_CLASS == TYPE_CLASS_PHONE

    /** 应用明说了「别填我」。 */
    fun optedOut(importantForAutofill: Int): Boolean =
        importantForAutofill == IMPORTANT_FOR_AUTOFILL_NO ||
            importantForAutofill == IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
}
