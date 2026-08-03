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

import androidx.compose.ui.graphics.Color

/**
 * 「保险库仪表」配色 —— 与交互原型 1:1 对应。
 *
 * 设计主张：钢青色机身 + 黄铜色强调。黄铜是锁芯和钥匙的颜色，
 * 它只用在三种地方：可信状态、需要注意的操作、机器生成的凭据。
 * 不做彩色仪表盘，不做渐变噱头。
 *
 * ── 对比度修订（v2）──
 *
 * 初版的问题不是「配色难看」，而是**三级文字里的第三级根本不该存在于正文**。
 * [Dimmer] 在 [Void] 上只有 3.4:1，而它是全项目用得最多的颜色（81 处），
 * 又几乎总是配 12.5sp 的 [VaultType.Sub] 或 11sp 的 [VaultType.MonoSmall]。
 * 结果是「小字 + 低对比 + 暗背景」三个不利因素叠在同一段文本上，
 * 在户外或亮度调低时直接消失。
 *
 * 修订原则：
 *   1. 所有承载文字的颜色，在它可能落到的**最亮**容器（[Slab2]）上
 *      也要 ≥ 4.5:1（WCAG AA 正文标准）。不是「大多数情况够用」，
 *      而是最坏情况够用 —— 因为最坏情况恰好是卡片里的辅助说明，
 *      那是用户最需要读清楚的地方；
 *   2. 机身四层与描边整体提亮，让「卡片」真的看起来是一块卡片。
 *      初版 Slab 对 Void 只有 1.10:1，等于没有边界，
 *      这是「整体灰暗、糊成一片」的直接来源；
 *   3. 色相一律不动。提亮是为了看得清，不是为了换风格。
 *
 * 每个值后面标注的是它在 [Void] 上的对比度。
 */
object VaultColors {
    // 机身（由深到浅的四层）。整体提亮一档，层与层之间的差值也拉开，
    // 这样卡片叠在背景上、按下态叠在卡片上，都能靠明度差看出来。
    val Void      = Color(0xFF0A1113)   // 最底层背景
    val Slab      = Color(0xFF18252A)   // 卡片 / 输入框      1.21:1 vs Void
    val Slab2     = Color(0xFF223135)   // 悬浮 / 次级卡片    1.41:1 vs Void
    val Slab3     = Color(0xFF2C3E43)   // 按下态

    // 描边。初版 1.5:1 的描边在实机上是看不见的，
    // 于是「描边卡片」和「无边卡片」长得一样，层次感全靠猜。
    val Line      = Color(0xFF3B4F54)   // 2.21:1 —— 肉眼可辨的结构线
    val LineSoft  = Color(0xFF2C3D41)   // 1.66:1 —— 分隔线，仅暗示分组

    // 文字（三级）。三级都必须能当正文读，
    // 区别是「注意力权重」，不是「能不能看见」。
    val Text      = Color(0xFFF2F6F5)   // 17.5:1  主文字
    val Dim       = Color(0xFFA6B8BB)   //  9.3:1  次要文字（Slab2 上仍有 6.5:1）
    val Dimmer    = Color(0xFF8A9DA0)   //  6.7:1  最弱文字（Slab2 上 4.75:1，刚好过 AA）

    // 黄铜（强调色）
    val Brass     = Color(0xFFE3B96C)   // 10.4:1
    val BrassDim  = Color(0xFF9A7C41)   // 禁用态黄铜；上面压 Void 黑字仍有 4.8:1
    val BrassWash = Color(0xFF2E2618)   // 顶部封条底色

    // 语义色
    val Jade      = Color(0xFF5CBFA6)   //  8.6:1  安全 / 通过
    val Rust      = Color(0xFFDE7C60)   //  6.5:1  弱密码 / 危险

    // 语义色的低透明度背景。alpha 从 0x29 提到 0x33：
    // 初版的色块在暗背景上几乎和 Slab 无法区分，
    // 「这是一条警告」的信息全靠图标和文字颜色单独扛。
    val JadeWash  = Color(0x335CBFA6)
    val RustWash  = Color(0x33DE7C60)
    val BrassTint = Color(0x30E3B96C)
}
