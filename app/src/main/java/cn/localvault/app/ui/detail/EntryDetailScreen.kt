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

package cn.localvault.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.LocalClipboard
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DangerButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.EntryTile
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.IconSlot
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultDialog
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType
import cn.localvault.app.ui.util.Fmt

/**
 * 条目详情。
 *
 * ── 遮蔽只防「路过一眼」，不防「拿到手机的人」 ──
 *
 * 所以这一页**不做「显示 30 秒后自动变回圆点」的倒计时**。
 * 显示明文的唯一用途就是照着抄，而倒计时恰好会在抄到一半时把内容抽走，
 * 逼用户再点一次、再从头找一遍位置。
 *
 * 也**不做「切后台回来自动重新遮蔽」**。听起来更安全，其实是障眼法：
 * 库这会儿还开着，能看到屏幕的人自己点一下那只眼睛就行了，
 * 遮蔽拦不住他。真正拦得住的是自动锁定，而它已经在了（默认 60 秒）。
 * 加一个只能骗自己的开关，比不加更糟——它会让人以为多了一层保护。
 *
 * 页面销毁时状态自然消失，所以「进详情 → 点编辑 → 返回」之后密码是重新遮住的，
 * 这条不需要额外写代码，是 `remember` 的生命周期本来就给的。
 */
@Composable
fun EntryDetailScreen(
    entryId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val session = LocalSession.current
    val clip = LocalClipboard.current
    val state by session.state.collectAsState()

    val data = (state as? VaultSession.State.Unlocked)?.data ?: return
    val entry = data.entries.firstOrNull { it.id == entryId }

    var removed by remember { mutableStateOf<EntryDetail.Removed?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf(emptySet<EntryDetail.Row>()) }

    // 删掉之后停在墓碑页，不自动退回列表 —— 理由见 [Tombstone]。
    val snapshot = removed
    if (snapshot != null) {
        Tombstone(
            entry = snapshot.entry,
            onUndo = {
                val r = session.mutate { d ->
                    d.copy(entries = EntryDetail.restore(d.entries, snapshot))
                }
                if (r.isSuccess) removed = null else failure = "撤销没能写回保险库，条目仍是删除状态。"
            },
            failure = failure,
            onDone = onBack,
        )
        return
    }

    // 条目不在了（从别处删掉、或者返回栈上残留的一帧），安静退出。
    // 不弹「条目不存在」——用户没做错什么，那句话只会让他以为出了故障。
    if (entry == null) {
        LaunchedEffect(entryId) { onBack() }
        return
    }

    val rows = remember(entry) { EntryDetail.rows(entry) }
    val clipSeconds = data.meta.clipboardClearSeconds

    VaultScreen(
        title = entry.name,
        onBack = onBack,
        seal = { DefaultSeal() },
        actions = {
            IconSlot(
                if (entry.favorite) Glyph.StarFilled else Glyph.Star,
                contentDescription = if (entry.favorite) "取消收藏" else "收藏",
                tint = if (entry.favorite) VaultColors.Brass else VaultColors.Dim,
                onClick = {
                    val r = session.updateEntry(entry.copy(favorite = !entry.favorite))
                    if (r.isFailure) failure = "改动没能写进保险库，请稍后再试。"
                },
            )
            IconSlot(Glyph.Pencil, contentDescription = "编辑", onClick = { onEdit(entry.id) })
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (failure != null) {
                Banner(text = failure!!, tone = BannerTone.Danger)
            }

            Header(entry)

            VaultCard(Modifier.fillMaxWidth()) {
                Column {
                    rows.forEachIndexed { i, row ->
                        if (i > 0) HairLine()
                        FieldRow(
                            row = row,
                            entry = entry,
                            revealed = row in revealed,
                            onToggleReveal = {
                                revealed = if (row in revealed) revealed - row else revealed + row
                            },
                            onCopy = { value ->
                                clip.copySensitive(
                                    EntryDetail.clipboardLabel(row),
                                    value,
                                    clipSeconds,
                                )
                            },
                        )
                    }
                    if (rows.isEmpty()) {
                        Text(
                            "只有名称。点右上角的铅笔补上账号和密码。",
                            style = VaultType.Sub,
                            color = VaultColors.Dimmer,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }

            MetaBlock(entry)

            Spacer(Modifier.height(2.dp))

            /**
             * 删除放在**滚到底部的全宽按钮**上，不放顶栏图标里。
             *
             * 顶栏那个 44dp 的方块和收藏、编辑挤在一起，误触的代价却完全不同：
             * 点错收藏无非多一次改动，点错删除是把一条密码删了。
             * 危险动作要让人走到它跟前，并且看见「删除这个条目」五个字，
             * 而不是看见一个需要辨认的小图标。
             */
            DangerButton("删除这个条目", onClick = { confirmDelete = true })

            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmDelete) {
        VaultDialog(
            title = "删除这个条目？",
            message = "删掉之后还能撤销一次。离开这一页就撤不回来了——除非你有备份。",
            // 弹窗里只放名称和打过码的账号。密码和备注一个字都不进来，
            // 理由见 EntryDetail.deleteConfirmDetail 和决策⑭。
            detail = EntryDetail.deleteConfirmDetail(entry),
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = false
                val (next, snap) = EntryDetail.remove(data.entries, entry.id)
                if (snap != null) {
                    val r = session.mutate { d -> d.copy(entries = next) }
                    if (r.isSuccess) {
                        removed = snap
                        failure = null
                    } else {
                        failure = "删除没能写进保险库，条目还在。"
                    }
                }
            },
            secondaryText = "取消",
            onSecondary = { confirmDelete = false },
            onDismissRequest = { confirmDelete = false },
        )
    }
}

/* ─────────────────────────── 头部 ─────────────────────────── */

@Composable
private fun Header(entry: VaultEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EntryTile(entry, size = 58.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                entry.name,
                style = VaultType.H1,
                color = VaultColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "更新于 ${Fmt.relativeTime(entry.updatedAt)}",
                style = VaultType.MonoSmall,
                color = VaultColors.Dimmer,
            )
        }
    }
}

/* ─────────────────────────── 字段行 ─────────────────────────── */

/**
 * 一行字段：标签 + 值 + （可选）显示切换 + （可选）复制。
 *
 * ── 复制之后不弹任何提示 ──
 *
 * 顶部封条上那条剪贴板倒计时（`ClipboardBar`）就是回执，
 * 而且它比一句「已复制」有用得多：它同时告诉用户还有几秒会被清掉。
 * 再叠一个 toast 只是把同一件事说两遍，还会挡住刚复制的那一行。
 * 这和「不做成功绿条」（见 `Banner` 的注释）是同一条规矩。
 */
@Composable
private fun FieldRow(
    row: EntryDetail.Row,
    entry: VaultEntry,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val hideable = EntryDetail.hiddenByDefault(row)
    val shown = !hideable || revealed

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Eyebrow(EntryDetail.label(row))

            when (row) {
                EntryDetail.Row.Password -> Text(
                    if (shown) entry.password else dots(entry.password.length),
                    style = VaultType.MonoPassword,
                    color = if (shown) VaultColors.Text else VaultColors.Dim,
                )

                EntryDetail.Row.Notes -> Text(
                    if (shown) entry.notes else "点右边的眼睛显示备注",
                    style = if (shown) VaultType.Body else VaultType.Sub,
                    color = if (shown) VaultColors.Text else VaultColors.Dimmer,
                )

                EntryDetail.Row.Domain -> Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    entry.domains.filter { it.isNotBlank() }.forEach { d ->
                        Text(d, style = VaultType.MonoBody, color = VaultColors.Text)
                    }
                }

                EntryDetail.Row.Category -> CategoryPill(entry.category)

                EntryDetail.Row.Username -> Text(
                    entry.username,
                    style = VaultType.MonoBody,
                    color = VaultColors.Text,
                )
            }
        }

        if (hideable) {
            IconSlot(
                if (shown) Glyph.EyeOff else Glyph.Eye,
                contentDescription = if (shown) "隐藏" else "显示",
                onClick = onToggleReveal,
            )
        }

        if (EntryDetail.copyable(row)) {
            IconSlot(
                Glyph.Copy,
                contentDescription = "复制${EntryDetail.label(row)}",
                onClick = { onCopy(valueOf(row, entry)) },
            )
        }
    }
}

/**
 * 圆点用固定 12 个，不按真实长度画。
 *
 * 按长度画等于把密码位数印在屏幕上，而位数是爆破时最值钱的一条边信息——
 * 知道是 8 位还是 20 位，工作量差着十几个数量级。
 * 这一条和列表页不显示任何库内信息（决策㉖）是同一个方向：
 * 遮起来的东西不该顺便交代自己有多长。
 */
private fun dots(@Suppress("UNUSED_PARAMETER") realLength: Int): String = "•".repeat(12)

private fun valueOf(row: EntryDetail.Row, entry: VaultEntry): String = when (row) {
    EntryDetail.Row.Username -> entry.username
    EntryDetail.Row.Password -> entry.password
    EntryDetail.Row.Domain -> entry.domains.firstOrNull { it.isNotBlank() }.orEmpty()
    EntryDetail.Row.Category -> entry.category
    EntryDetail.Row.Notes -> entry.notes
}

@Composable
private fun CategoryPill(text: String) {
    Text(
        text = text,
        style = VaultType.Sub,
        color = VaultColors.Dim,
        modifier = Modifier
            .clip(VaultShape.Field)
            .background(VaultColors.Slab2)
            .border(1.dp, VaultColors.LineSoft, VaultShape.Field)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

/* ─────────────────────────── 元信息 ─────────────────────────── */

/**
 * 三个时间。
 *
 * 「密码上次更改」单独列一行，是为了给二期的密码健康体检留一个入口，
 * 也因为它和「条目上次更改」经常差很远——只改了个备注不该让密码显得很新。
 * 这条区分在 `VaultSession.updateEntry` 里已经写好了，这里只是把它显示出来。
 */
@Composable
private fun MetaBlock(entry: VaultEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetaLine("创建", Fmt.relativeTime(entry.createdAt))
        MetaLine("上次修改", Fmt.relativeTime(entry.updatedAt))
        MetaLine("密码上次更改", Fmt.relativeTime(entry.passwordUpdatedAt))
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = VaultType.Sub, color = VaultColors.Dimmer)
        Text(value, style = VaultType.MonoSmall, color = VaultColors.Dim)
    }
}

/* ─────────────────────────── 墓碑页 ─────────────────────────── */

/**
 * 删掉之后停在这一页，而不是立刻退回列表。
 *
 * 常见做法是删完就跳回列表、底部飘一条「已删除 · 撤销」的 Snackbar。
 * 那条 Snackbar 基本没人点得到：跳回去的同一瞬间列表在重排，
 * 用户的注意力全在「我那条到哪去了」，5 秒就过去了。
 *
 * 停在原地则给了「撤销」一个明确的落点，也顺便让用户确认删掉的确实是这一条。
 * 还有一个附带好处：撤销状态不需要跨页面传递，
 * 也就没有「撤销状态跨越一次自动锁定活下来」这种隐患——
 * 锁定时整棵子树被换掉（决策⑪），这一页连同快照一起消失。
 */
@Composable
private fun Tombstone(
    entry: VaultEntry,
    onUndo: () -> Unit,
    failure: String?,
    onDone: () -> Unit,
) {
    VaultScreen(title = "已删除", seal = { DefaultSeal() }) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))

            if (failure != null) Banner(text = failure, tone = BannerTone.Danger)

            Box(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                VaultIcon(Glyph.Trash, tint = VaultColors.Dimmer, size = 32.dp)
            }

            Text(
                "「${entry.name}」已从保险库删除",
                style = VaultType.H2,
                color = VaultColors.Text,
            )
            Text(
                "已经写进保险库。现在还能撤销一次，离开这一页就只能从备份里找回来。",
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
            )

            Spacer(Modifier.height(6.dp))
            GhostButton("撤销删除", onClick = onUndo, tint = VaultColors.Brass)
            BrassButton("完成", onClick = onDone)
        }
    }
}
