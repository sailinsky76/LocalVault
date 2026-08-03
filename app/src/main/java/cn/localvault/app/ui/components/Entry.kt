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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 条目图标：名称首字 + 名称哈希取的底色。
 *
 * 这里承载的是 M1 的第 ⑨ 条决策：**永远不联网抓 favicon**。
 * 一旦为了「图标好看」去请求一次网络，`INTERNET` 权限就得加回来，
 * 而那是这个产品最大的差异化。底色由
 * [cn.localvault.app.core.vault.VaultEntry.tileColor] 本地算出，
 * 同一个名字永远同一个颜色，用户照样能靠颜色扫读列表。
 */
@Composable
fun EntryTile(
    entry: VaultEntry,
    modifier: Modifier = Modifier,
    // 40 → 48dp。色块是列表里唯一的彩色元素，也是「靠颜色扫读」这条
    // 设计主张的全部载体（决策⑨：永不联网抓 favicon）。40dp 时它撑不起
    // 这个角色，首字还得缩到 16.8sp 才塞得进去。
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(if (size >= 56.dp) VaultShape.TileLg else VaultShape.Tile)
            .background(Color(entry.tileColor)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            entry.initial,
            style = VaultType.RowName.copy(
                fontSize = (size.value * 0.44f).sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            ),
            color = Color.White,
        )
    }
}

/**
 * 列表里的一行。
 *
 * 右侧刻意**不放**「一键复制密码」按钮：列表是扫读用的，
 * 在这里放复制会让用户在没确认是哪一条的情况下就把密码送进剪贴板。
 * 复制统一收进详情页 —— 多一次点击，换一次「我确认是这条」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(
    entry: VaultEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * 长按。为 null 时行为和以前一模一样（普通 `clickable`）。
     *
     * 用 `combinedClickable` 而不是往行里塞一个 `pointerInput`：
     * 前者会把长按的**触感反馈**和无障碍的「长按」动作一起带上，
     * 手写一个手势检测器只能得到回调，用户按住 500 毫秒不会有那一下震动，
     * 于是他不知道自己按够了没有，往往会松手重来。
     */
    onLongClick: (() -> Unit)? = null,
    /**
     * 选中态。只画一层底色——**勾在 [trailing] 里**，由调用方给。
     *
     * 底色用 [VaultColors.BrassTint]（半透明黄铜）而不是换整行背景：
     * 这一行左边那块色块是靠颜色扫读的全部载体（决策⑨），
     * 一层不透明的选中背景会把它和相邻行的差别抹平。
     */
    selected: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(VaultShape.Row)
            .background(if (selected) VaultColors.BrassTint else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // 竖向 9 → 11dp：条目名升到 17sp 之后，原来的内边距会让
            // 相邻两行的文字几乎贴在一起，扫读时容易串行
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        EntryTile(entry)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    entry.name,
                    style = VaultType.RowName,
                    color = VaultColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.favorite) VaultIcon(Glyph.StarFilled, tint = VaultColors.Brass, size = 15.dp)
            }
            Text(
                entry.username.ifEmpty { entry.domains.firstOrNull() ?: "—" },
                style = VaultType.MonoSmall,
                color = VaultColors.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
        if (trailing == null) VaultIcon(Glyph.Chevron, tint = VaultColors.Dim, size = 18.dp)
    }
}

/** 列表分组的字母/分类标头 */
@Composable
fun GroupHeader(
    text: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
    /**
     * 点标头。多选模式下用来整组选中/取消（[cn.localvault.app.ui.list.ListSelection.toggleGroup]）。
     * 为 null 时标头就是一行不可点的字，和以前一样。
     */
    onClick: (() -> Unit)? = null,
    /** 右边那个数字换成别的东西——多选模式下换成整组的勾。 */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        // 四边都写全：`padding` 没有「horizontal + top/bottom」这个重载，
        // 只有 (start, top, end, bottom) 和 (horizontal, vertical) 两组，不能混着用。
        modifier = modifier
            .fillMaxWidth()
            .clip(VaultShape.Row)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(text)
        when {
            trailing != null -> trailing()
            count != null -> Eyebrow(count.toString())
        }
    }
}

/**
 * 多选用的勾选圈。
 *
 * ── 为什么不用 Material3 的 `Checkbox` ──
 *
 * 和整个工程不用 `Scaffold`、不用 `AlertDialog` 是同一个理由：
 * 它自带一套 M3 的取色和涟漪，要覆盖成这身黑底黄铜，代码里就会积一堆
 * 「关掉默认值」的样板。这里要画的东西一共就是一个圆和一个勾。
 *
 * ── 未选中时画的是空心圈，不是空白 ──
 *
 * 空白会让用户以为这一行不能选。空心圈是**这一行也可以被选**的唯一提示，
 * 而多选模式是他刚用一个陌生手势进来的，屏幕上每一处都得自己解释自己。
 *
 * [partial] 给分组标头用：这一组选了一部分。画一条横杠而不是半个勾——
 * 半个勾在 20dp 上和一个完整的勾分不出来。
 */
@Composable
fun SelectionCheck(
    checked: Boolean,
    modifier: Modifier = Modifier,
    partial: Boolean = false,
    size: Dp = 22.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (checked || partial) VaultColors.Brass else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (checked || partial) VaultColors.Brass else VaultColors.Line,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            VaultIcon(Glyph.Check, tint = VaultColors.Void, size = size * 0.68f, strokeWidth = 2.4f)
        } else if (partial) {
            VaultIcon(Glyph.Minus, tint = VaultColors.Void, size = size * 0.68f, strokeWidth = 2.4f)
        }
    }
}
