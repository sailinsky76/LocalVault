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

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 圆角尺度：原型定的 16–20 区间。越大的容器圆角越大。 */
object VaultShape {
    val Tile   = RoundedCornerShape(12.dp)   // 40dp 图标块
    val TileSm = RoundedCornerShape(10.dp)
    val TileLg = RoundedCornerShape(17.dp)
    val Field  = RoundedCornerShape(14.dp)   // 输入框 / 按钮
    val Row    = RoundedCornerShape(14.dp)
    val Card   = RoundedCornerShape(18.dp)
    val Sheet  = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
}

/** 间距尺度 */
object VaultSpace {
    val PagePadding = 16
    val SectionGap  = 22
    val ItemGap     = 10
}
