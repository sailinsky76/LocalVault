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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 开关与步进器。
 *
 * 手写而不是用 Material3 的 `Switch`：和不用 Scaffold、不用 AlertDialog 同一个理由——
 * M3 的 Switch 带着一整套 `SwitchColors`（选中/未选中 × 轨道/滑块/描边/图标，
 * 十几个颜色槽），要让它长成这套钢青 + 黄铜的样子，得逐个覆盖，
 * 最后代码里全是「关掉默认值」的样板，还得跟着 material3 的版本升级维护一遍。
 * 这里两个 Box 就够了。
 *
 * 放在 `components/` 而不是生成器自己的目录里，是因为 M3-6 设置页要用同一批：
 * 自动锁定时长、剪贴板时长用步进器，开启 PIN / 指纹用开关。
 * 那时候如果各画各的，同一个 App 里会出现两种手感不同的开关。
 */

/* ─────────────────────────── 开关 ─────────────────────────── */

@Composable
fun VaultSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t by animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    val track = if (!enabled) {
        VaultColors.Slab2
    } else {
        lerp(VaultColors.Slab3, VaultColors.Brass, t)
    }
    val knob = when {
        !enabled -> VaultColors.Dimmer
        checked -> VaultColors.Void
        else -> VaultColors.Dim
    }
    Box(
        modifier = modifier
            .width(46.dp)
            .height(27.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .offset(x = 19.dp * t)
                .size(21.dp)
                .clip(CircleShape)
                .background(knob),
        )
    }
}

/**
 * 一行一个开关。
 *
 * [note] 是**被关掉时**才显示的那句话。这条规矩来自编辑页那个灰按钮
 * （决策(61)）：一个不能点、或者点了会有代价的控件，必须自己解释为什么。
 * 生成器里唯一会被禁用的是「最后一个还开着的字符类」——
 * 用户去点它，得到的不该是「点了没反应」，而是「至少得留一类」。
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    note: String? = null,
) {
    Row(
        modifier = modifier
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = VaultType.RowName,
                color = if (enabled) VaultColors.Text else VaultColors.Dimmer,
            )
            val sub = if (!enabled && note != null) note else subtitle
            if (sub != null) {
                Text(sub, style = VaultType.MonoSmall, color = VaultColors.Dimmer)
            }
        }
        VaultSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/* ─────────────────────────── 步进器 ─────────────────────────── */

/**
 * 「− 20 +」。
 *
 * ── 为什么不用滑块 ──
 *
 * 滑块看起来更适合「长度」这种连续量，但它在这里有两个具体问题：
 * 一是密码长度是**要被记住和复述的整数**（「我用的是 20 位」），
 * 滑块让人只能大概拖到那儿，想精确停在 16 得试几次；
 * 二是拖动会连续触发重新生成，用户从 12 拖到 32 的路上，
 * 屏幕上那串密码会闪几十次——那既晃眼，又意味着几十次随机数调用。
 *
 * 步进器每按一次是一个确定的数，配合下面的预设片，
 * 常用值一步到位，罕见值也能一位一位调过去。
 */
@Composable
fun Stepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
    step: Int = 1,
    valueText: String = value.toString(),
) {
    Row(
        modifier = modifier
            .clip(VaultShape.Field)
            .background(VaultColors.Slab)
            .border(1.dp, VaultColors.LineSoft, VaultShape.Field),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconSlot(
            Glyph.Minus,
            contentDescription = "减少",
            enabled = value > min,
            onClick = { onValueChange((value - step).coerceAtLeast(min)) },
        )
        Text(
            valueText,
            style = VaultType.MonoBody,
            color = VaultColors.Text,
            modifier = Modifier
                .width(52.dp)
                .padding(horizontal = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconSlot(
            Glyph.Plus,
            contentDescription = "增加",
            enabled = value < max,
            onClick = { onValueChange((value + step).coerceAtMost(max)) },
        )
    }
}

/**
 * 预设片。和分类快捷片（[cn.localvault.app.ui.edit.EntryFormFields]）、
 * 搜索页的分类快捷键（决策㊸）是同一个做法：点一下**就是把值填进去**，
 * 不另开一套「你正处于某个预设中」的状态。
 * 选中态只是把当前值标出来，不是一个独立的模式。
 */
@Composable
fun PresetChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        style = VaultType.MonoSmall,
        color = if (selected) VaultColors.Brass else VaultColors.Dim,
        modifier = modifier
            .clip(VaultShape.TileSm)
            .background(if (selected) VaultColors.BrassTint else VaultColors.Slab2)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}
