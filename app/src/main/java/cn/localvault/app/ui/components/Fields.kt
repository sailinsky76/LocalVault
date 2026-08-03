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

package cn.localvault.app.ui.components

import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType
import cn.localvault.app.ui.util.PasswordStrength
import java.util.Arrays

/* ══════════════════════════ 主密码输入 ══════════════════════════ */

/**
 * 主密码 / PIN 的输入状态。
 *
 * ── 为什么这里要绕开 Compose 的 TextField ──
 *
 * `BasicTextField` 的值类型是 `String`。Java 的 String 不可变，
 * 一旦创建，在 GC 回收之前没有任何办法把它从堆里擦掉。而输入框每敲一个键
 * 就会产生一个新 String —— 输一个 20 位的主密码，堆里就躺着 20 个
 * 长度递增的密码前缀，全都擦不掉，全都可能出现在堆转储里。
 * 这跟 M1 里 [cn.localvault.app.core.crypto.SecureBytes] 花大力气
 * 手写 UTF-8 编码所要避免的，是同一件事。
 *
 * 所以主密码这一个输入框走 View 互操作：`EditText` 的内容是 `Editable`，
 * 底层是一个**可写的 char[]**，我们能在用完之后原地覆盖成全零。
 *
 * 顺带关掉的三件事：
 *   · `isSaveEnabled = false` —— 否则转屏时密码会被写进 savedInstanceState 的 Bundle；
 *   · `IMPORTANT_FOR_AUTOFILL_NO` —— 别让别的密码管理器来填我们的主密码框；
 *   · 长按选择菜单 —— 主密码不该能被复制走。
 *
 * 注意：条目里的密码字段**不用**这套，因为 [cn.localvault.app.core.vault.VaultEntry]
 * 本来就把密码存成 String（整库加密，条目层不再二次加密），
 * 在那里绕开 String 只是自我安慰。真正值得保护的是主密码和 PIN。
 */
@Stable
class SecureTextState {

    internal var editable: Editable? = null

    /** 当前长度。给强度条和按钮可用态用，不暴露内容。 */
    var length by mutableIntStateOf(0)
        internal set

    /**
     * 内容变更计数。给「输入变了要重算」的地方当 remember 的 key 用。
     *
     * 为什么不能直接拿 [length] 当 key：长度没变但内容变了的情况是存在的——
     * 用户选中一段再输入等长的替换，长度纹丝不动，而强度可能从「强」掉到「弱」。
     * 拿 length 做 key 的强度条在那一刻会继续显示旧结论，
     * 这类「界面说安全、实际不安全」的偏差是最不能留的。
     */
    var revision by mutableIntStateOf(0)
        internal set

    var revealed by mutableStateOf(false)

    val isEmpty: Boolean get() = length == 0

    /**
     * 借出内容的一份副本，块执行完立刻清零。
     * 做成回调是为了让调用方拿不到长期引用 —— 和 `VaultSession.withVaultKey` 同一个套路。
     */
    fun <R> read(block: (CharArray) -> R): R {
        val e = editable
        val arr = CharArray(e?.length ?: 0)
        if (e != null) for (i in arr.indices) arr[i] = e[i]
        return try { block(arr) } finally { Arrays.fill(arr, '\u0000') }
    }

    /** 取一份副本交给上层（例如传给 `VaultRepository.create`）。调用方负责 wipe。 */
    fun copyChars(): CharArray {
        val e = editable ?: return CharArray(0)
        return CharArray(e.length) { e[it] }
    }

    /** 内容是否和另一个输入框一致（确认密码用）。不产生 String，也不早退，恒定时间。 */
    fun contentEquals(other: SecureTextState): Boolean {
        val a = editable; val b = other.editable
        val la = a?.length ?: 0; val lb = b?.length ?: 0
        if (la != lb || la == 0) return false
        var diff = 0
        for (i in 0 until la) diff = diff or (a!![i].code xor b!![i].code)
        return diff == 0
    }

    /**
     * 原地覆盖成全零再清空。
     *
     * 先用等长的 NUL 串替换，是为了让 SpannableStringBuilder 在**同一块 char[]**
     * 上写入而不是重新分配；之后再删空。这是尽力而为 —— 如果中途扩容过，
     * 旧缓冲区仍可能残留。但比起 String 完全无法擦除，这已经是能做到的上限。
     */
    fun wipe() {
        val e = editable ?: return
        val n = e.length
        if (n > 0) {
            e.replace(0, n, String(CharArray(n)))
            e.replace(0, n, "")
        }
        length = 0
        revision++
    }
}

@Composable
fun rememberSecureTextState(): SecureTextState {
    val state = remember { SecureTextState() }
    // 离开这个界面就擦。用户按返回键退出解锁页，密码不该还留在内存里。
    DisposableEffect(state) { onDispose { state.wipe(); state.editable = null } }
    return state
}

/**
 * 主密码输入框。外观由 Compose 画，输入内核是 EditText。
 */
@Composable
fun SecurePasswordField(
    state: SecureTextState,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    isError: Boolean = false,
) {
    val borderColor = if (isError) VaultColors.Rust.copy(alpha = 0.6f) else VaultColors.Line
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(VaultShape.Field)
            .background(VaultColors.Slab)
            .border(1.dp, borderColor, VaultShape.Field)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (state.isEmpty) {
                Text(placeholder, style = VaultType.MonoBody, color = VaultColors.Dimmer)
            }
            SecureEditText(
                state = state,
                imeAction = imeAction,
                onImeAction = onImeAction,
                autoFocus = autoFocus,
            )
        }
        IconSlot(
            glyph = if (state.revealed) Glyph.EyeOff else Glyph.Eye,
            contentDescription = if (state.revealed) "隐藏密码" else "显示密码",
            onClick = { state.revealed = !state.revealed },
        )
    }
}

@Composable
private fun SecureEditText(
    state: SecureTextState,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    autoFocus: Boolean,
) {
    val textColor = VaultColors.Text.toArgb()
    val cursorColor = VaultColors.Brass.toArgb()

    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            EditText(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
                setTextColor(textColor)
                setHintTextColor(android.graphics.Color.TRANSPARENT)
                textSize = 15f
                letterSpacing = 0.06f
                typeface = Typeface.MONOSPACE
                isSingleLine = true

                // ① 转屏 / 进程重建时不要把密码写进 Bundle
                isSaveEnabled = false
                // ② 别让别的密码管理器接管我们的主密码框
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                // ③ 主密码不允许被复制走
                customSelectionActionModeCallback = BlockActionMode
                customInsertionActionModeCallback = BlockActionMode
                isLongClickable = false

                inputType = INPUT_HIDDEN
                imeOptions = when (imeAction) {
                    ImeAction.Next -> EditorInfo.IME_ACTION_NEXT
                    ImeAction.Go -> EditorInfo.IME_ACTION_GO
                    else -> EditorInfo.IME_ACTION_DONE
                } or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        state.editable = s
                        state.length = s?.length ?: 0
                        state.revision++
                    }
                })
                state.editable = text

                setOnEditorActionListener { _, _, _ -> onImeAction(); true }
                if (autoFocus) {
                    requestFocus()
                }
            }
        },
        update = { view ->
            val want = if (state.revealed) INPUT_SHOWN else INPUT_HIDDEN
            if (view.inputType != want) {
                view.inputType = want
                // 改 inputType 会重置字体并把光标弹回开头，这两行必须跟着补
                view.typeface = Typeface.MONOSPACE
                view.setSelection(view.text.length)
            }
            view.setTextColor(textColor)
            view.highlightColor = cursorColor
        },
        onRelease = { view ->
            // View 被回收时把缓冲区抹掉，别等 GC
            (view.text as? Editable)?.let { e ->
                val n = e.length
                if (n > 0) { e.replace(0, n, String(CharArray(n))); e.replace(0, n, "") }
            }
        },
    )
}

private const val INPUT_HIDDEN =
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
private const val INPUT_SHOWN =
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

/** 吃掉全部长按菜单（复制 / 粘贴 / 全选） */
private object BlockActionMode : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
    override fun onDestroyActionMode(mode: ActionMode?) = Unit
}

/* ══════════════════════════ 普通输入 ══════════════════════════ */

/**
 * 条目名称 / 账号 / 备注这类字段。这些内容本来就以 String 存在
 * [cn.localvault.app.core.vault.VaultEntry] 里（整库加密，条目层不再二次加密），
 * 所以用标准的 Compose 输入框，没必要为它绕 View 互操作。
 */
@Composable
fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Int = 52,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    masked: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * 作用在**输入框本体**上的 modifier，不是作用在外面那个框上的。
     *
     * 目前唯一的用途是挂 `FocusRequester`：新增流第一步要把光标直接放在名称上
     * （见 [cn.localvault.app.ui.add.AddFlow.autoFocus]）。
     * 单独开一个参数而不是让调用方用 `modifier`，是因为那个 modifier 已经
     * 承担了背景、描边、圆角和最小高度——把 `focusRequester` 挂在那上面，
     * 请求到的是外框的焦点，键盘不会弹出来，而且看不出哪儿错了。
     */
    fieldModifier: Modifier = Modifier,
) {
    val style = (if (mono) VaultType.MonoBody else VaultType.Body).copy(color = VaultColors.Text)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .clip(VaultShape.Field)
            .background(VaultColors.Slab)
            .border(1.dp, VaultColors.Line, VaultShape.Field)
            .padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, style = style.copy(color = VaultColors.Dimmer))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = style,
                singleLine = singleLine,
                cursorBrush = SolidColor(VaultColors.Brass),
                visualTransformation =
                    if (masked) PasswordVisualTransformation('•') else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onDone = { onImeAction() },
                    onNext = { onImeAction() },
                    onGo = { onImeAction() },
                ),
                modifier = Modifier.fillMaxWidth().then(fieldModifier),
            )
        }
        trailing?.invoke()
    }
}

/** 带标题的字段块 */
@Composable
fun LabeledField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Eyebrow(label)
        content()
    }
}

/* ══════════════════════════ 强度条 ══════════════════════════ */

/**
 * 四格强度条 + 一句人话。
 *
 * 刻意不显示「73 bit」这种数字：熵的估算本来就只是估算，
 * 一个精确到个位的数字会让用户以为它很准，进而在 59 和 61 之间纠结。
 * 用户真正需要知道的只有两件事：够不够，以及不够的话下一步做什么。
 */
@Composable
fun StrengthMeter(
    result: PasswordStrength.Result,
    modifier: Modifier = Modifier,
) {
    val (filled, color, word) = when (result.level) {
        PasswordStrength.Level.Weak -> Triple(1, VaultColors.Rust, "弱")
        PasswordStrength.Level.Fair -> Triple(2, VaultColors.Brass, "一般")
        PasswordStrength.Level.Good -> Triple(3, VaultColors.Jade, "较强")
        PasswordStrength.Level.Strong -> Triple(4, VaultColors.Jade, "强")
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(VaultShape.TileSm)
                        .background(if (i < filled) color else VaultColors.Slab2)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(result.hint, style = VaultType.Sub, color = VaultColors.Dim)
            Text(word, style = VaultType.MonoSmall, color = color)
        }
    }
}

/**
 * 「两次输入一致 / 不一致」那一行。
 *
 * 摆在这儿而不是各页面自己写一个私有版本，是因为要它的地方有两处
 * （建库、改主密码），而这两处的语义必须**一模一样**：
 * 一个把「不一致」画成灰色小字、另一个画成红色叉号，
 * 用户在第二处会以为那只是提示而不是拦截。
 *
 * 一致也说话（不只在出错时才出现）：确认框这种东西，用户是盯着它等一个
 * 「对上了」的信号的，什么都不显示会让他反复删掉重打。
 */
@Composable
fun MatchHint(matched: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        VaultIcon(
            if (matched) Glyph.Check else Glyph.Close,
            tint = if (matched) VaultColors.Jade else VaultColors.Rust,
            size = 14.dp,
        )
        Text(
            if (matched) "两次输入一致" else "两次输入不一致",
            style = VaultType.Sub,
            color = if (matched) VaultColors.Jade else VaultColors.Rust,
        )
    }
}

/**
 * 遮蔽显示的密码。点一下切换明文/圆点。
 * 明文用 [VaultType.MonoPassword]：字距拉开是为了让用户能逐字核对，
 * 尤其是 l / 1 / I 和 0 / O 这几组。
 */
@Composable
fun MaskedValue(
    value: String,
    revealed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = VaultColors.Text,
) {
    Text(
        text = if (revealed) value else "•".repeat(value.length.coerceAtMost(24)),
        style = VaultType.MonoPassword,
        color = color,
        modifier = modifier
            .clip(VaultShape.TileSm)
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
    )
}
