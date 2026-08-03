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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 确认弹窗。全工程的弹窗都必须走这个组件，不要直接用 `Dialog`。
 *
 * ── 为什么不用 Material3 的 AlertDialog ──
 *
 * 表面理由和不用 Scaffold 一样：默认配色要一条条覆盖。
 * 但这里还有一条**安全**理由，比配色重要得多：
 *
 * **Compose 的 Dialog 是一个独立的 Window。**
 * MainActivity 上设的 `FLAG_SECURE` 只作用于 Activity 自己那个 window，
 * 弹窗那一层不会自动继承。哪天有人在弹窗里带出了账号或密码明文
 * （「确定删除『招商银行 / 138****』吗」这种很自然就会写出来），
 * 那一屏就是可截图、可录屏、会出现在最近任务缩略图里的。
 * 所以这里把 [SecureFlagPolicy.SecureOn] 显式写死，不留 `Inherit`。
 *
 * ── 三个回调为什么要分开 ──
 *
 * [onConfirm] 是主按钮，[onSecondary] 是次按钮，[onDismissRequest] 是
 * 按返回键或点弹窗外面。很多代码图省事把「次按钮」和「取消手势」合成一个，
 * 那在**次按钮才是危险动作**的场合会出人命：
 * 「主按钮=回去改密码 / 次按钮=就用这个弱口令」，一旦合并，
 * 用户点一下弹窗外面的空白，就无声无息地建了个弱口令保险库。
 * 取消手势永远只能意味着「什么都别做」。
 */
@Composable
fun VaultDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    detail: String? = null,
    danger: Boolean = false,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            // 见上：弹窗是独立 window，必须自己声明防截屏
            securePolicy = SecureFlagPolicy.SecureOn,
            // 危险操作不允许点外面糊弄过去，必须明确选一个按钮
            dismissOnClickOutside = !danger,
        ),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(VaultShape.Card)
                .background(VaultColors.Slab)
                .border(1.dp, VaultColors.Line, VaultShape.Card)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                VaultIcon(
                    if (danger) Glyph.Warn else Glyph.Shield,
                    tint = if (danger) VaultColors.Rust else VaultColors.Brass,
                    size = 18.dp,
                )
                Text(title, style = VaultType.H2, color = VaultColors.Text)
            }

            Text(message, style = VaultType.Body, color = VaultColors.Dim)

            if (detail != null) {
                Text(
                    detail,
                    style = VaultType.MonoSmall,
                    color = VaultColors.Dimmer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(VaultShape.TileSm)
                        .background(VaultColors.Void)
                        .padding(10.dp),
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (danger) {
                    DangerButton(confirmText, onClick = onConfirm)
                } else {
                    BrassButton(confirmText, onClick = onConfirm)
                }
                if (secondaryText != null && onSecondary != null) {
                    GhostButton(secondaryText, onClick = onSecondary, tint = VaultColors.Dim)
                }
            }
        }
    }
}
