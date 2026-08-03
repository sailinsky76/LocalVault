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

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体反差就是这个 App 的性格：
 *   人写的内容（应用名、备注、说明文案）→ 系统黑体
 *   机器生成的内容（密码、账号、密钥指纹、倒计时、加密参数）→ 等宽
 *
 * 凡是「不该被人手抄」的东西，一律等宽。用户会无意识地学到这条规则。
 *
 * ── 字号修订（v2）──
 *
 * 初版整体偏小一到两档：正文 13sp、辅助说明 12.5sp、等宽小字 11sp。
 * 这套尺度是照着设计稿在电脑上定的，放到实机上有两个叠加的问题：
 *
 *   1. **中文没有 x-height 优势**。13sp 的拉丁文还算舒服，
 *      同样 13sp 的汉字笔画会挤在一起，「据」「置」这类字直接糊掉。
 *      Android 中文正文的实际下限是 14sp，舒适区在 15–16sp；
 *   2. **等宽字体的视觉字号比黑体小**。同为 11sp，Monospace 的字面
 *      明显小于 Default，而 11sp 恰好用在账号、密钥指纹这些
 *      「必须逐字符核对」的地方 —— 最需要看清的内容用了最小的字。
 *
 * 修订后所有正文类样式 ≥ 14sp，等宽类再额外加一档补偿字面差。
 * 层级关系（H1 > H2 > RowName > Body > Sub）保持不变，
 * 只是整条尺度上移，所以不会有哪一屏的信息优先级被改写。
 */
object VaultType {

    val Sans: FontFamily = FontFamily.Default
    val Mono: FontFamily = FontFamily.Monospace

    /** 页面主标题。22 → 26sp：顶栏是每一屏的锚点，值得占这个体量 */
    val H1 = TextStyle(fontFamily = Sans, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    /** 区块标题 / 按钮文字。15 → 17sp */
    val H2 = TextStyle(fontFamily = Sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    /** 条目名。14.5 → 17sp，并升到 SemiBold —— 列表扫读时它是唯一的落点 */
    val RowName = TextStyle(fontFamily = Sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    /** 正文说明。13 → 15sp */
    val Body = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 23.sp)
    /** 弱化说明。12.5 → 14sp。全项目用得最多的样式（82 处），加这一档收益最大 */
    val Sub = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 21.sp)

    /**
     * 小标签，全大写 + 大字距。用于分区标题。
     *
     * 10 → 12sp，同时字距从 1.6 收到 1.3：字号变大之后，
     * 原来那个字距会把「常用」这种两字标题拉散成两个孤立的字。
     */
    val Eyebrow = TextStyle(fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.3.sp)
    /** 机器内容：账号、密钥、参数。11 → 13.5sp */
    val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 13.5.sp)
    val MonoBody  = TextStyle(fontFamily = Mono, fontSize = 15.sp, letterSpacing = 0.5.sp)
    /** 密码明文展示：字距拉开，便于人工核对。16 → 19sp */
    val MonoPassword = TextStyle(fontFamily = Mono, fontSize = 19.sp, letterSpacing = 1.4.sp)
    /** 封条内的参数行。10.5 → 12sp */
    val Seal = TextStyle(fontFamily = Mono, fontSize = 12.sp, letterSpacing = 0.4.sp)
    /** 数字键盘。19 → 23sp */
    val Keypad = TextStyle(fontFamily = Mono, fontSize = 23.sp)

    val Material = Typography(
        headlineSmall = H1,
        titleMedium   = H2,
        bodyMedium    = Body,
        bodySmall     = Sub,
        labelSmall    = Eyebrow,
    )
}
