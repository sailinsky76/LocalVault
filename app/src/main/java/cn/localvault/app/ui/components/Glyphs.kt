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

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.theme.VaultColors
import androidx.compose.foundation.layout.size

/**
 * 图标全部用 Canvas 手绘，不引入 material-icons 依赖。
 *
 * 理由和「不引入 Hilt」是同一条：这个 App 把「依赖少、权限干净」当卖点，
 * material-icons-extended 会往 APK 里塞进上千个矢量资源，
 * 而我们真正用到的不超过二十个。同时 Google 已经在把图标产物往
 * material3 里迁移，绑死一个正在搬家的坐标只会给未来的升级添堵。
 *
 * 所有图形都画在 24×24 的逻辑坐标系里，再按目标尺寸整体缩放，
 * 因此换尺寸不会让线宽比例走样。
 *
 * ── 修订（v2）──
 *
 * 默认尺寸 20 → 22dp，默认线宽 1.7 → 1.85。
 *
 * 手绘图标和字体走的是两套不同的清晰度逻辑：字体有 hinting，
 * 而 Canvas 描边只能靠抗锯齿。1.7 的线宽缩放到 20dp 后实际约 1.42px，
 * 落在半个像素上被抗锯齿摊成两行灰 —— 于是每根线看起来都比它该有的样子淡。
 * 这也是「整体灰暗」感受里容易被漏掉的一半：不只是颜色暗，是**线本身虚**。
 */
enum class Glyph {
    Back, Chevron, Close, Plus, Minus, Search,
    Eye, EyeOff, Copy, Check, Backspace,
    Lock, Unlock, Key, Shield, Warn, Fingerprint,
    Settings, Refresh, Trash, Share, Star, StarFilled, Pencil,
    Globe,
    /**
     * 「详细说明」那个链接前面的圆圈 i（见 `Explain.kt`）。
     *
     * 没有复用 [Shield]：盾牌在这套图标里已经被 Banner 的中性提示占着，
     * 它读起来是「这件事关乎安全」。而详细说明链接说的是
     * 「这儿还有下文」——两件事共用一个图标，用户会以为每一段说明都是安全警告。
     */
    Info,
}

@Composable
fun VaultIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = VaultColors.Text,
    strokeWidth: Float = 1.85f,
) {
    Canvas(modifier = modifier.size(size)) {
        val k = this.size.minDimension / 24f
        drawGlyph(glyph, tint, strokeWidth * k, k)
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, tint: Color, sw: Float, k: Float) {
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
    fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), tint, style = stroke)
    fun fillPath(build: Path.() -> Unit) = drawPath(Path().apply(build), tint)
    fun rect(l: Float, t: Float, r: Float, b: Float) = Rect(l * k, t * k, r * k, b * k)
    fun dot(x: Float, y: Float, r: Float) = drawCircle(tint, r * k, p(x, y))
    fun ring(x: Float, y: Float, r: Float) =
        drawCircle(tint, r * k, p(x, y), style = stroke)

    when (glyph) {
        Glyph.Back -> path { moveTo(15f * k, 4.5f * k); lineTo(7.5f * k, 12f * k); lineTo(15f * k, 19.5f * k) }
        Glyph.Chevron -> path { moveTo(9.5f * k, 5f * k); lineTo(16f * k, 12f * k); lineTo(9.5f * k, 19f * k) }
        Glyph.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        Glyph.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        // 步进器的减号。和 Plus 用同一条横线，两个按钮并排时线长才对得齐——
        // 差几个像素在别处无所谓，在一对紧挨着的 +/− 上一眼就看得出来。
        Glyph.Minus -> line(5f, 12f, 19f, 12f)

        // 环从 5.8 放大到 6.4、手柄相应外移：初版的镜环偏小，
        // 在顶栏那个尺寸下容易被认成一个句号
        Glyph.Search -> { ring(10.2f, 10.2f, 6.4f); line(14.9f, 14.9f, 20f, 20f) }

        Glyph.Eye -> {
            path {
                // 用三次贝塞尔而不是 quadraticBezierTo：后者在 Compose 1.7 起已标记废弃
                moveTo(2.5f * k, 12f * k)
                cubicTo(8.83f * k, 6.33f * k, 15.17f * k, 6.33f * k, 21.5f * k, 12f * k)
                cubicTo(15.17f * k, 17.67f * k, 8.83f * k, 17.67f * k, 2.5f * k, 12f * k)
                close()
            }
            ring(12f, 12f, 2.6f)
        }
        Glyph.EyeOff -> {
            path {
                // 用三次贝塞尔而不是 quadraticBezierTo：后者在 Compose 1.7 起已标记废弃
                moveTo(2.5f * k, 12f * k)
                cubicTo(8.83f * k, 6.33f * k, 15.17f * k, 6.33f * k, 21.5f * k, 12f * k)
                cubicTo(15.17f * k, 17.67f * k, 8.83f * k, 17.67f * k, 2.5f * k, 12f * k)
                close()
            }
            ring(12f, 12f, 2.6f)
            line(4f, 4f, 20f, 20f)
        }

        // 两张叠在一起的纸：后面那张露出右上角
        Glyph.Copy -> {
            path {
                moveTo(9f * k, 3.5f * k); lineTo(20.5f * k, 3.5f * k); lineTo(20.5f * k, 15f * k)
            }
            drawRoundRect(
                tint, topLeft = p(3.5f, 8.5f),
                size = Size(12.5f * k, 12f * k),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * k),
                style = stroke,
            )
        }

        Glyph.Check -> path { moveTo(4.5f * k, 12.5f * k); lineTo(9.5f * k, 17.5f * k); lineTo(19.5f * k, 6.5f * k) }

        Glyph.Backspace -> {
            path {
                moveTo(9f * k, 4.5f * k); lineTo(20.5f * k, 4.5f * k); lineTo(20.5f * k, 19.5f * k)
                lineTo(9f * k, 19.5f * k); lineTo(2.5f * k, 12f * k); close()
            }
            line(12f, 9f, 17f, 15f); line(17f, 9f, 12f, 15f)
        }

        Glyph.Lock -> {
            drawRoundRect(
                tint, topLeft = p(4.5f, 10.5f), size = Size(15f * k, 10f * k),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * k), style = stroke,
            )
            path {
                moveTo(8f * k, 10.5f * k); lineTo(8f * k, 7.5f * k)
                arcTo(rect(8f, 3.5f, 16f, 11.5f), 180f, 180f, false)
                lineTo(16f * k, 10.5f * k)
            }
        }
        Glyph.Unlock -> {
            drawRoundRect(
                tint, topLeft = p(4.5f, 10.5f), size = Size(15f * k, 10f * k),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * k), style = stroke,
            )
            // 开口朝右：这是「已解锁」的唯一视觉差别，刻意做得明显
            path {
                moveTo(8f * k, 10.5f * k); lineTo(8f * k, 7.5f * k)
                arcTo(rect(8f, 3.5f, 16f, 11.5f), 180f, 140f, false)
            }
        }

        Glyph.Key -> {
            ring(7.5f, 12f, 3.6f)
            line(11.1f, 12f, 20.5f, 12f)
            line(16f, 12f, 16f, 15.5f)
            line(19f, 12f, 19f, 14.5f)
        }

        Glyph.Shield -> path {
            moveTo(12f * k, 2.8f * k); lineTo(20f * k, 6f * k); lineTo(20f * k, 12f * k)
            cubicTo(20f * k, 17f * k, 16f * k, 19.8f * k, 12f * k, 21.2f * k)
            cubicTo(8f * k, 19.8f * k, 4f * k, 17f * k, 4f * k, 12f * k)
            lineTo(4f * k, 6f * k); close()
        }

        /**
         * 指纹：四条同心弧 + 两道向下的脊线。
         *
         * 屏幕坐标里 0° 朝右、90° 朝下，所以「上半圈」是 180°~360°。
         * 不画真实指纹那种十几条纹路——20dp 下它们会糊成一团灰。
         */
        Glyph.Fingerprint -> {
            val cy = 13.5f
            fun ridge(r: Float, start: Float, sweep: Float) = drawArc(
                color = tint, startAngle = start, sweepAngle = sweep, useCenter = false,
                topLeft = p(12f - r, cy - r), size = Size(2f * r * k, 2f * r * k),
                style = stroke,
            )
            ridge(9f, 195f, 150f)
            ridge(6.6f, 200f, 140f)
            ridge(4.2f, 195f, 150f)
            ridge(1.9f, 190f, 160f)
            // 最外圈两端顺势往下带一小截，避免图形看起来是「半个洋葱」
            line(3.3f, 11.2f, 3.3f, 16.2f)
            line(20.7f, 11.2f, 20.7f, 16.2f)
        }

        Glyph.Warn -> {
            path {
                moveTo(12f * k, 3.5f * k); lineTo(21.5f * k, 20f * k); lineTo(2.5f * k, 20f * k); close()
            }
            line(12f, 9.5f, 12f, 14.5f)
            dot(12f, 17.3f, 1.1f)
        }

        // 三条滑轨，比齿轮在小尺寸下更清楚
        Glyph.Settings -> {
            line(3.5f, 7f, 20.5f, 7f); line(3.5f, 12f, 20.5f, 12f); line(3.5f, 17f, 20.5f, 17f)
            drawCircle(VaultColors.Void, 2.9f * k, p(9f, 7f))
            drawCircle(VaultColors.Void, 2.9f * k, p(15f, 12f))
            drawCircle(VaultColors.Void, 2.9f * k, p(11f, 17f))
            ring(9f, 7f, 2.2f); ring(15f, 12f, 2.2f); ring(11f, 17f, 2.2f)
        }

        Glyph.Refresh -> {
            drawArc(
                color = tint, startAngle = 40f, sweepAngle = 285f, useCenter = false,
                topLeft = p(4.5f, 4.5f), size = Size(15f * k, 15f * k), style = stroke,
            )
            // 箭头落在弧的终点（40+285 = 325°）
            val cx = 12f; val cy = 12f; val r = 7.5f
            val a = Math.toRadians(325.0)
            val ex = cx + r * Math.cos(a).toFloat()
            val ey = cy + r * Math.sin(a).toFloat()
            fillPath {
                moveTo((ex + 2.6f) * k, (ey + 0.6f) * k)
                lineTo((ex - 2.2f) * k, (ey + 1.6f) * k)
                lineTo((ex - 0.2f) * k, (ey - 2.8f) * k)
                close()
            }
        }

        Glyph.Trash -> {
            line(3.5f, 6.5f, 20.5f, 6.5f)
            path { moveTo(9.2f * k, 6.5f * k); lineTo(9.2f * k, 4f * k); lineTo(14.8f * k, 4f * k); lineTo(14.8f * k, 6.5f * k) }
            path {
                moveTo(6f * k, 6.5f * k); lineTo(7.1f * k, 20.5f * k); lineTo(16.9f * k, 20.5f * k); lineTo(18f * k, 6.5f * k)
            }
            line(10.2f, 10f, 10.6f, 17.5f); line(13.8f, 10f, 13.4f, 17.5f)
        }

        Glyph.Share -> {
            dot(17.5f, 5f, 2.6f); dot(6.5f, 12f, 2.6f); dot(17.5f, 19f, 2.6f)
            line(8.7f, 10.6f, 15.3f, 6.4f)
            line(8.7f, 13.4f, 15.3f, 17.6f)
        }

        /**
         * 铅笔。笔尖在左下，笔身是一个 45° 的平行四边形，
         * 靠近笔头处横一道箍线——没有那道线，20dp 下它看起来像一支口红。
         */
        Glyph.Pencil -> {
            path {
                moveTo(3.5f * k, 20.5f * k)
                lineTo(5.2f * k, 15.2f * k)
                lineTo(16.2f * k, 4.2f * k)
                lineTo(19.8f * k, 7.9f * k)
                lineTo(8.8f * k, 18.9f * k)
                close()
            }
            line(13.6f, 6.8f, 17.2f, 10.4f)
        }
        // 网址那一行的图标。经线画两条对称的三次贝塞尔而不是一个正圆：
        // 一个正圆加一条横线在 22dp 上会被认成一个「⊖」，
        // 中间那两道弧是让它一眼看出是地球的唯一线索。
        Glyph.Globe -> {
            ring(12f, 12f, 8.5f)
            line(3.5f, 12f, 20.5f, 12f)
            path {
                moveTo(12f * k, 3.5f * k)
                cubicTo(16.2f * k, 7.6f * k, 16.2f * k, 16.4f * k, 12f * k, 20.5f * k)
            }
            path {
                moveTo(12f * k, 3.5f * k)
                cubicTo(7.8f * k, 7.6f * k, 7.8f * k, 16.4f * k, 12f * k, 20.5f * k)
            }
        }

        // 圆圈 i。点画在环内偏上，竖线从中线往下——
        // 常见的画法是点紧贴竖线，那在 15dp（链接里的尺寸）下两者会糊成一根。
        Glyph.Info -> {
            ring(12f, 12f, 8.6f)
            dot(12f, 7.8f, 1.05f)
            line(12f, 11f, 12f, 16.6f)
        }

        Glyph.Star, Glyph.StarFilled -> {
            val starPath = Path().apply { star(k) }
            if (glyph == Glyph.StarFilled) drawPath(starPath, tint)
            else drawPath(starPath, tint, style = stroke)
        }
    }
}

/** 五角星。顶点从 -90° 起算，内外半径 4.2 / 9.2。 */
private fun Path.star(k: Float) {
    val cx = 12f; val cy = 12.4f
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) 9.2f else 4.2f
        val a = Math.toRadians(-90.0 + i * 36.0)
        val x = (cx + r * Math.cos(a)).toFloat() * k
        val y = (cy + r * Math.sin(a)).toFloat() * k
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
