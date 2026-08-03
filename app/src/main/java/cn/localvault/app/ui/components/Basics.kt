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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/* ─────────────────────────── 页面骨架 ─────────────────────────── */

/**
 * 所有页面的统一外壳：黑底 + 系统栏内边距 + 可选顶栏 + 可选封条。
 *
 * 不用 Material3 的 Scaffold，因为它会带进一套我们全部要覆盖的
 * 默认配色和 TopAppBar 高度规则，最后代码里全是「关掉默认值」的样板。
 */
@Composable
fun VaultScreen(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    actions: RowScopeActions = {},
    seal: @Composable () -> Unit = {},
    /**
     * 左上角那个图标。默认是返回箭头。
     *
     * 开这个口子是为了多选模式：那一刻左上角的动作是**退出选择**，不是「回上一页」，
     * 而列表页本来就是首页——一个指着上一页的箭头会让用户以为按下去会离开保险库。
     * 叉号说的是「关掉这层状态」，那正是它做的事。
     */
    navGlyph: Glyph = Glyph.Back,
    navDescription: String = "返回",
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VaultColors.Void)
            .systemBarsPadding(),
    ) {
        seal()
        if (title != null || onBack != null) {
            VaultTopBar(
                title = title,
                onBack = onBack,
                actions = actions,
                navGlyph = navGlyph,
                navDescription = navDescription,
            )
        }
        content()
    }
}

/** 顶栏的 actions 槽位。取个别名是为了让 VaultScreen 的签名不至于太吓人。 */
typealias RowScopeActions = @Composable androidx.compose.foundation.layout.RowScope.() -> Unit

@Composable
fun VaultTopBar(
    title: String?,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    navGlyph: Glyph = Glyph.Back,
    navDescription: String = "返回",
) {
    // 高度 52 → 60dp：里面装的是 48dp 热区和 26sp 标题，52 已经装不下了
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconSlot(navGlyph, contentDescription = navDescription, onClick = onBack)
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title.orEmpty(),
            style = VaultType.H1,
            color = VaultColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        actions()
    }
}

/**
 * 48dp 的方形点击热区。低于这个尺寸单手操作会频繁点空。
 *
 * ── 修订（v2）──
 *
 * 三处一起改，因为它们是同一个问题的三个面：
 *
 *   - 热区 44 → 48dp：44 是 iOS 的建议值，Android 的 Material 建议值是 48，
 *     而这个 App 的顶栏图标（搜索、设置）恰好是最常在通勤路上单手点的两个；
 *   - 图标 20 → 24dp：20dp 的手绘线条图标在 xxhdpi 上只有 60px，
 *     搜索的放大镜环和设置的三条滑轨在这个尺寸下细节互相黏连；
 *   - 默认色 [VaultColors.Dim] → [VaultColors.Text]：这是最关键的一条。
 *     顶栏图标是**功能入口**，不是辅助信息，初版用次要文字色画它们，
 *     等于主动把「唯一的两个操作入口」画成了背景。
 *
 * 禁用态也从 Dimmer 提到 Dim —— 禁用要传达「现在点不了」，
 * 不该顺带传达「你也别想看清它是什么」。
 */
@Composable
fun IconSlot(
    glyph: Glyph,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = VaultColors.Text,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(VaultShape.TileSm)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VaultIcon(
            glyph,
            tint = if (enabled) tint else VaultColors.Dim,
            size = 24.dp,
            strokeWidth = 1.85f,
        )
    }
}

/* ─────────────────────────── 按钮 ─────────────────────────── */

/**
 * 主操作按钮。黄铜色实心，一屏只该出现一个。
 *
 * 黄铜在这套设计里只用于三种场合：可信状态、需要注意的操作、机器生成的凭据。
 * 如果一个页面上出现了两个黄铜按钮，通常说明这一步该拆成两屏。
 */
@Composable
fun BrassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val bg = if (enabled && !busy) VaultColors.Brass else VaultColors.BrassDim
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(VaultShape.Field)
            .background(bg)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = VaultColors.Void,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text, style = VaultType.H2, color = VaultColors.Void)
        }
    }
}

/** 次要操作：描边，无填充。 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = VaultColors.Text,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(VaultShape.Field)
            .border(1.dp, VaultColors.Line, VaultShape.Field)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = VaultType.H2, color = if (enabled) tint else VaultColors.Dimmer)
    }
}

/** 危险操作：铁锈色描边。删除、解绑、重置走这个。 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(VaultShape.Field)
            .background(VaultColors.RustWash)
            .border(1.dp, VaultColors.Rust.copy(alpha = 0.5f), VaultShape.Field)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = VaultType.H2, color = VaultColors.Rust)
    }
}

/** 纯文字按钮，用于「跳过」「稍后再说」这类退路。 */
@Composable
fun TextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = VaultColors.Dim,
) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        style = VaultType.Body,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(VaultShape.TileSm)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/* ─────────────────────────── 容器与分区 ─────────────────────────── */

/**
 * 分区小标题：等宽、全大写风格、大字距。
 *
 * 默认色从 Dimmer 提到 Dim：分区标题（「常用」「A」「其他」）是列表的
 * 导航骨架，用户靠它定位，不是可以忽略的脚注。
 */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = VaultColors.Dim) {
    Text(text.uppercase(), style = VaultType.Eyebrow, color = color, modifier = modifier)
}

@Composable
fun VaultCard(
    modifier: Modifier = Modifier,
    background: Color = VaultColors.Slab,
    borderColor: Color = VaultColors.LineSoft,
    shape: RoundedCornerShape = VaultShape.Card,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape),
    ) { content() }
}

@Composable
fun HairLine(modifier: Modifier = Modifier, color: Color = VaultColors.LineSoft) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * 提示条。三种语气：中性说明、需要注意、出事了。
 * 不做「成功」绿条 —— 操作成功不需要一条横幅来告诉用户，
 * 界面本身的变化就是最好的回执。
 */
enum class BannerTone { Info, Warn, Danger }

@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Info,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (bg, fg, glyph) = when (tone) {
        // Info 的文字色从 Dim 提到 Text：提示条是要被读完的整句话，
        // 不是角落里的水印。语气的差别交给底色和图标去表达。
        BannerTone.Info -> Triple(VaultColors.Slab2, VaultColors.Text, Glyph.Shield)
        BannerTone.Warn -> Triple(VaultColors.BrassTint, VaultColors.Brass, Glyph.Warn)
        BannerTone.Danger -> Triple(VaultColors.RustWash, VaultColors.Rust, Glyph.Warn)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(VaultShape.Row)
            .background(bg)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VaultIcon(glyph, tint = fg, size = 20.dp, strokeWidth = 1.85f)
        Text(text, style = VaultType.Sub, color = fg, modifier = Modifier.weight(1f))
        if (actionText != null && onAction != null) {
            Text(
                actionText,
                style = VaultType.Sub.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = fg,
                modifier = Modifier
                    .clip(VaultShape.TileSm)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** 空状态。文案要说明「现在什么都没有」和「下一步做什么」，不放插画。 */
@Composable
fun EmptyState(
    glyph: Glyph,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VaultIcon(glyph, tint = VaultColors.Dim, size = 44.dp, strokeWidth = 1.6f)
        Spacer(Modifier.height(2.dp))
        // 空状态的标题用主文字色：这一屏没有别的内容和它抢注意力，
        // 却偏偏是新用户第一次看到的界面，没有理由弱化它。
        Text(title, style = VaultType.H1, color = VaultColors.Text, textAlign = TextAlign.Center)
        Text(subtitle, style = VaultType.Body, color = VaultColors.Dim, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(6.dp)); action() }
    }
}

/**
 * 设置页那种「一行一项」的行。右侧可以放值、开关或箭头。
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueMono: Boolean = false,
    showChevron: Boolean = false,
    tint: Color = VaultColors.Text,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = VaultType.RowName, color = tint)
            if (subtitle != null) {
                Text(subtitle, style = VaultType.Sub, color = VaultColors.Dim)
            }
        }
        if (value != null) {
            Text(
                value,
                style = if (valueMono) VaultType.MonoSmall else VaultType.Sub,
                // 右侧的「值」是这一行真正的答案（开着/关着、5 分钟、AES-256），
                // 比左边的标题更该看清
                color = VaultColors.Text,
            )
        }
        trailing?.invoke()
        if (showChevron) VaultIcon(Glyph.Chevron, tint = VaultColors.Dim, size = 19.dp)
    }
}
