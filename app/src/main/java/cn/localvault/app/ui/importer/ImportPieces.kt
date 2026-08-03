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

package cn.localvault.app.ui.importer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 导入页上那些只有这一页会用到的零件。
 *
 * 拆出来不是为了复用（它们哪儿都不会再用），是为了让 `ImportScreen` 那一屏的
 * **顺序**读得出来——那一页真正要说清的是「先看什么、后看什么」，
 * 而不是某一行是怎么画的。同 `EntryFormFields` 和编辑页的关系。
 *
 * ── 这个文件里有一条贯穿始终的规矩 ──
 *
 * **一格内容都不显示。** 列映射那一屏显示的是列名（表头）和它被认成了什么，
 * 预览那一屏显示的是行号和条数，判重那一屏显示的是「第几行撞上了、凭什么算撞」。
 * 全程没有任何一个地方把某一行的名称、账号、密码画到屏幕上。
 *
 * 理由不是洁癖：导入是**别人的密码第一次进入这个应用**的时刻，
 * 而这一屏很可能就是用户第一次在一个新装的应用里看到自己的密码。
 * 一份 CSV 的预览做成表格看起来很专业，但它等于在一屏上同时摊开几百个明文口令，
 * 而这一页恰恰是最容易被人凑过来看一眼的时候（用户正对着旧手机核对）。
 * 内核那一层已经把「不吐内容」钉进了每一个 `toString`（决策(144)），
 * 界面这一层要接住它——否则那一层的规矩就只是自我安慰。
 *
 * 唯一的例外是**表头**：列名不是数据，而不显示列名的话，
 * 「这一列是什么」这个问题就没法回答了。表头本身长得像数据的那种文件，
 * `CsvMapping.Note.HeaderLooksLikeData` 会明说，用户看得见。
 */

/* ─────────────────────── 列映射 ─────────────────────── */

/**
 * 列映射那一屏里的一行：列名 + 它被认成了什么 + 点开改。
 *
 * ── 为什么「不导入」也要占一行，而不是折叠起来 ──
 *
 * 因为没认出来的列恰恰是最需要被看见的。1Password 的 `Password Hint`
 * 被排除表挡住之后就落在这里（决策(147)），用户扫一眼会发现
 * 「哦，提示语那一列没导，对的」；折起来的话他看到的是一份「全都认好了」的假象，
 * 而真正该他决定的那几列被藏在一个「展开更多」后面。
 */
@Composable
fun ColumnMapRow(
    name: String,
    role: CsvMapping.Role?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            // 列名用等宽字体：它是从文件里原样搬来的，等宽能让
            // 「Login Name」和「Login  Name」（两个空格）这类东西看得出来。
            Text(
                name.ifBlank { "（这一列没有列名）" },
                style = VaultType.MonoSmall,
                color = if (name.isBlank()) VaultColors.Dimmer else VaultColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                role?.hint ?: "这一列不会被导入",
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            role?.label ?: "不导入",
            style = VaultType.Sub,
            // 认出来的标黄铜色，没认出来的压暗。**不用红色**：
            // 一列没被认出来不是错误，多数文件都有好几列用不上。
            color = if (role == null) VaultColors.Dimmer else VaultColors.Brass,
        )
        VaultIcon(Glyph.Chevron, tint = VaultColors.Dim, size = 18.dp)
    }
}

/**
 * 点某一列之后弹出来的角色选择。
 *
 * ── 为什么不用 `VaultDialog` ──
 *
 * 那个组件是「一句话 + 两个按钮」的形状，而这里要的是一张十选一的单子。
 * 硬塞进去的结果是十个角色变成一段挤在 message 里的文字。
 * 但**防截屏那一条必须自己声明**：弹窗是独立 window，不继承 Activity 的
 * `FLAG_SECURE`（决策⑭）。这一屏上没有密码，可有没有密码不是这条规矩的判据——
 * 判据是「它是不是独立 window」，漏一次的代价是后面每一个照着这里抄的人都漏。
 *
 * 「不导入」放在最上面而不是最下面：把某一列**关掉**是这张单子上最常用的动作
 * （用户来这儿多半是因为某一列认错了），让它离手指最近。
 */
@Composable
fun RolePickerDialog(
    columnName: String,
    current: CsvMapping.Role?,
    onPick: (CsvMapping.Role?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Column(
            modifier = Modifier
                .clip(VaultShape.Card)
                .background(VaultColors.Slab)
                .padding(vertical = 8.dp),
        ) {
            Text(
                columnName.ifBlank { "（这一列没有列名）" },
                style = VaultType.MonoSmall,
                color = VaultColors.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HairLine()
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                RoleOption("不导入这一列", null, current == null) { onPick(null) }
                CsvMapping.Role.entries.forEach { r ->
                    RoleOption(r.label, r.hint, current == r) { onPick(r) }
                }
            }
        }
    }
}

@Composable
private fun RoleOption(
    label: String,
    hint: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = VaultType.Body,
                color = if (selected) VaultColors.Brass else VaultColors.Text,
            )
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(hint, style = VaultType.Sub, color = VaultColors.Dimmer)
            }
        }
        if (selected) VaultIcon(Glyph.Check, tint = VaultColors.Brass, size = 19.dp)
        else Spacer(Modifier.width(16.dp))
    }
}

/* ─────────────────────── 处置 ─────────────────────── */

/**
 * 「撞上了怎么办」的三选一。
 *
 * 每一个选项都**带着它自己的那句说明**（`Policy.note`），而不是选中之后
 * 才在下面显示一句。三种处置里有一种（覆盖）是不可撤销地改动已有数据，
 * 而它和另外两种在按钮上只差两个字——用户必须在**按之前**读到
 * 「空的不覆盖」和「都留着不会自动改名」这两件事，事后才说就晚了。
 */
@Composable
fun PolicyChoice(
    current: CsvImport.Policy,
    onPick: (CsvImport.Policy) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CsvImport.Policy.entries.forEach { p ->
            val on = p == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(VaultShape.Row)
                    .background(if (on) VaultColors.BrassTint else VaultColors.Slab2)
                    .clickable { onPick(p) }
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VaultIcon(
                    if (on) Glyph.Check else Glyph.Chevron,
                    tint = if (on) VaultColors.Brass else VaultColors.Dimmer,
                    size = 15.dp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        p.label,
                        style = VaultType.Body,
                        color = if (on) VaultColors.Brass else VaultColors.Text,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(p.note, style = VaultType.Sub, color = VaultColors.Dim)
                }
            }
        }
    }
}

/* ─────────────────────── 判重 ─────────────────────── */

/**
 * 撞上的那几行，一行一句：**第几行、和库里的哪一档撞上了、凭什么算撞。**
 *
 * 只列前 [MAX_HITS] 条，多的报个数。列全的话，一份从旧管理器整体导过来的文件
 * 会在这里铺开几百行，而那几百行说的是同一件事；用户真要逐条核对，
 * 该看的是源文件那边，行号就是给他这个用的。
 *
 * 三档判重的措辞（`Match.label` / `Match.why`）直接取内核那一份，不另写：
 * 「凭什么算撞」这句话必须和真正的判据一个字不差，
 * 否则用户会按一句不准确的解释去选处置，而选错的那一种会盖掉他的旧密码。
 */
@Composable
fun HitList(hits: List<CsvImport.Candidate>) {
    if (hits.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        hits.take(MAX_HITS).forEach { c ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                VaultIcon(
                    Glyph.Warn,
                    tint = VaultColors.Brass,
                    size = 13.dp,
                    modifier = Modifier.padding(top = 3.dp),
                )
                // hitNote 只带行号和理由，不带内容——那个函数就是为这一行写的。
                Text(CsvImport.hitNote(c), style = VaultType.Sub, color = VaultColors.Dim)
            }
        }
        if (hits.size > MAX_HITS) {
            Text(
                "还有 ${hits.size - MAX_HITS} 行也撞上了，处置对它们一视同仁。" +
                    "要逐条核对的话，拿上面那些行号去源文件里找。",
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

private const val MAX_HITS = 12

/* ─────────────────────── 说明条 ─────────────────────── */

/** 记账 / 跳过理由那种「一条一句」的排版。图标是中性的，因为这些都不是错误。 */
@Composable
fun NoteLine(text: String, glyph: Glyph = Glyph.Shield) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        VaultIcon(
            glyph,
            tint = VaultColors.Dimmer,
            size = 13.dp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(text, style = VaultType.Sub, color = VaultColors.Dim)
    }
}

/** 一行数字。结果页和预览页上「新增 / 覆盖 / 跳过」那三行走这个。 */
@Composable
fun CountRow(label: String, count: Int, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = VaultType.Body, color = VaultColors.Text, modifier = Modifier.weight(1f))
        Text(
            count.toString(),
            style = VaultType.MonoSmall,
            color = if (highlight && count > 0) VaultColors.Brass else VaultColors.Dim,
        )
    }
}
