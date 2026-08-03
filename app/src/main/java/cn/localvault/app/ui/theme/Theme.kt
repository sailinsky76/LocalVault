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

package cn.localvault.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 这个 App 只有暗色主题。
 *
 * 不是偷懒：密码管理器大量在暗光、单手、匆忙的场景下被打开
 * （床上、地铁、收银台前）。一个亮色主题会让「深夜查个密码」这件事变得刺眼。
 * 亮色主题排到 2.0 再评估。
 */
private val VaultColorScheme = darkColorScheme(
    primary            = VaultColors.Brass,
    onPrimary          = VaultColors.Void,
    primaryContainer   = VaultColors.BrassWash,
    onPrimaryContainer = VaultColors.Brass,
    secondary          = VaultColors.Jade,
    onSecondary        = VaultColors.Void,
    error              = VaultColors.Rust,
    onError            = VaultColors.Text,
    background         = VaultColors.Void,
    onBackground       = VaultColors.Text,
    surface            = VaultColors.Slab,
    onSurface          = VaultColors.Text,
    surfaceVariant     = VaultColors.Slab2,
    onSurfaceVariant   = VaultColors.Dim,
    outline            = VaultColors.Line,
    outlineVariant     = VaultColors.LineSoft,
)

@Composable
fun LocalVaultTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VaultColorScheme,
        typography = VaultType.Material,
        content = content
    )
}
