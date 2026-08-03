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

package cn.localvault.app.ui.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DangerButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.EmptyState
import cn.localvault.app.ui.components.EntryRow
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.GroupHeader
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.IconSlot
import cn.localvault.app.ui.components.SelectionCheck
import cn.localvault.app.ui.components.VaultDialog
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 保险库列表 —— 解锁之后的家。
 *
 * 这一页要同时是三样东西：能扫读的清单、还没备份时的提醒、以及新增的入口。
 * 顺序上刻意让提醒排在清单前面：一个没备份过的库，最重要的信息不是
 * 「你有 37 条密码」，而是「这 37 条只存在于这一台设备上」。
 *
 * ── 和解锁页的一个显眼区别 ──
 *
 * 决策㉖说解锁页不显示任何库内信息（条目数、最近修改时间都不行），
 * 那条只约束**锁着的时候**。已经解锁了还藏着条目数是在装样子——
 * 人都进来了，界面本身就是全部信息。所以这里顶栏大方地显示总数。
 *
 * ── 多选模式 ──
 *
 * 这一页有两个形态，共用同一份列表：普通模式（点一行进详情）和
 * **选择模式**（点一行是勾选，底下换成一条删除栏）。
 * 规则全在 [ListSelection] 里（可单测），这里只负责画和导航。
 *
 * **入口只有一个：长按任意一条。** 顶栏那个对勾按钮撤掉了（决策(220)）——
 * 多选是个不常用的功能，不值得在每一屏的顶栏上长期占一格，
 * 而顶栏那几个 44dp 方块挤在一起本来就是误触的温床（同决策(97)）。
 * 代价是长按变成一个没有提示的手势，由列表末尾那行小字接住（见 [SelectHint]）。
 *
 * 两个形态的分界要画得足够狠，因为同一个点击手势在两边的后果完全不同：
 * 顶栏整条换掉（标题变成条数、左上角变成叉、右边变成全选）、
 * 加号收起来、底部长出一条危险色的操作栏。任何一处含糊，
 * 都会变成「他以为在选，其实点进了详情」或者反过来。
 */
@Composable
fun VaultListScreen(
    onOpenEntry: (String) -> Unit,
    onAdd: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onBackup: () -> Unit,
) {
    val session = LocalSession.current
    val state by session.state.collectAsState()

    // 锁定的那一瞬间导航相位会换掉整棵子树，但这一帧可能还会被画一次。
    // 与其让它去读一个刚被清空的 data，不如直接什么都不画。
    val data = (state as? VaultSession.State.Unlocked)?.data ?: return

    val entries = data.entries
    val sections = remember(entries) { VaultIndex.sections(entries) }
    val neverBackedUp = data.meta.lastBackupAt == 0L
    val pending = remember(entries, data.meta.lastBackupAt) {
        if (neverBackedUp) 0 else VaultIndex.changedSince(entries, data.meta.lastBackupAt)
    }

    /**
     * 选择模式的几个状态。
     *
     * 用 `remember` 而不是 `rememberSaveable`：转屏会退回普通模式。
     * 认下这个代价，和搜索页转屏丢关键词（决策㊲）是同一笔账——
     * `rememberSaveable` 意味着把一串条目 id 写进 `savedInstanceState`，
     * 而那是一个会落盘、不受 `FLAG_SECURE` 保护的 Bundle
     * （[cn.localvault.app.ui.nav.DraftHandoff] 那篇注释要堵的洞）。
     * 一组 id 说明不了库里有什么，但它能说明这个库有多大、
     * 用户刚才对哪几条动了心思，而这两件事没有理由写到磁盘上。
     */
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    /**
     * 库在别处变了就把选中集合收敛一遍（[ListSelection.prune]）。
     *
     * 变的路径不止一条：用户点进详情页删了一条再回来、自动填充在后台存了一条、
     * 导入覆盖了一批。不收敛的话「已选 5 条」会和实际删掉的条数对不上，
     * 而删除接口忽略找不到的 id，所以它不会报错，只会安静地骗人。
     */
    LaunchedEffect(entries) {
        selected = ListSelection.prune(selected, entries)
        // 库空了就没什么可选的了。停在一个空列表的选择模式里，
        // 用户只能看着一条永远点不亮的删除栏。
        if (entries.isEmpty()) selecting = false
    }

    val exitSelection: () -> Unit = {
        selecting = false
        selected = emptySet()
        failure = null
    }

    // 选择模式下接管返回：先退出选择，而不是退出保险库。
    // 用户按返回的意思在这一刻是「算了不选了」，不是「锁库走人」。
    BackHandler(enabled = selecting) { exitSelection() }

    VaultScreen(
        title = if (selecting) ListSelection.title(selected.size) else "保险库",
        onBack = if (selecting) exitSelection else null,
        navGlyph = Glyph.Close,
        navDescription = "退出选择",
        seal = { DefaultSeal() },
        actions = {
            if (selecting) {
                /**
                 * 全选 / 取消全选。用文字不用图标：这两个动作互为反面，
                 * 而任何一个图标都表达不了「现在按下去是哪一边」——
                 * 用户得先点一下才知道，而那一下可能正好把 37 条全选上。
                 */
                Text(
                    text = ListSelection.toggleAllText(selected, entries),
                    style = VaultType.Body,
                    color = VaultColors.Brass,
                    modifier = Modifier
                        .clip(VaultShape.TileSm)
                        .clickable { selected = ListSelection.toggleAll(selected, entries) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                if (entries.isNotEmpty()) {
                    Text(
                        "${entries.size} 条",
                        style = VaultType.MonoSmall,
                        color = VaultColors.Dim,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    /**
                     * 这里**没有**进选择模式的按钮，是有意的（决策(220)）。
                     *
                     * 顶栏是每一屏都在的地方，摆什么进去就等于宣称
                     * 「这件事经常要做」。多选不是——它一个月用不到一次，
                     * 而它旁边那两个（搜索、设置）是每天都点的。
                     * 长按那条路由列表末尾那行小字负责教（见 [SelectHint]）：
                     * 那行字只在滚到底时才占位置，而顶栏那一格是永久的。
                     */
                    IconSlot(Glyph.Search, contentDescription = "搜索", onClick = onSearch)
                }
                IconSlot(Glyph.Settings, contentDescription = "设置", onClick = onSettings)
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // 底部留出 FAB 的位置。不留的话最后一条永远被那个圆钮压着一半，
                // 而它恰恰是用户新加的那一条。
                // 左右 6 → 8dp：色块放大到 48dp 之后，原来的留白会让它顶到屏幕边缘。
                // 选择模式下底部那条栏比 FAB 高，留白要跟着长——否则最后一条
                // 永远被删除按钮压着，而那一条完全可能正是他要删的。
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 4.dp,
                    bottom = if (selecting) 168.dp else 104.dp,
                ),
            ) {
                if (failure != null) {
                    item(key = "failure") {
                        Banner(
                            text = failure!!,
                            tone = BannerTone.Danger,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                        )
                    }
                }

                /**
                 * 备份提醒在选择模式下**收起来**。
                 *
                 * 它是一条要人去点的横幅（「去备份」），而这一刻屏幕上唯一该被点的
                 * 是条目和底下那个删除。更要紧的是：正要删东西的人看到
                 * 「还没有备份过」，会以为这是在警告他删除有风险——
                 * 那句话说的其实是另一件事。
                 */
                if (entries.isNotEmpty() && !selecting) {
                    item(key = "backup-notice") {
                        BackupNotice(
                            neverBackedUp = neverBackedUp,
                            pendingCount = pending,
                            onBackup = onBackup,
                        )
                    }
                }

                sections.forEach { section ->
                    item(key = "h:${section.title}") {
                        if (selecting) {
                            val gs = ListSelection.groupState(selected, section)
                            GroupHeader(
                                section.title,
                                onClick = {
                                    selected = ListSelection.toggleGroup(selected, section)
                                },
                                trailing = {
                                    SelectionCheck(
                                        checked = gs == ListSelection.GroupState.All,
                                        partial = gs == ListSelection.GroupState.Some,
                                        size = 20.dp,
                                    )
                                },
                            )
                        } else {
                            GroupHeader(section.title, count = section.entries.size)
                        }
                    }
                    items(section.entries, key = { it.id }) { entry ->
                        val isSelected = entry.id in selected
                        EntryRow(
                            entry = entry,
                            selected = selecting && isSelected,
                            onClick = {
                                if (selecting) {
                                    selected = ListSelection.toggle(selected, entry.id)
                                } else {
                                    onOpenEntry(entry.id)
                                }
                            },
                            /**
                             * 长按进选择模式，**并且顺手把长按的那一条选上**。
                             *
                             * 不选上的话，用户长按完看到的是一个空的选择模式，
                             * 还得再点一下他手指本来就压着的那一行。
                             * 已经在选择模式里时长按等同于点击——不给它第二种含义，
                             * 两种手势在同一个状态下做同一件事，比多一个隐藏功能好。
                             */
                            onLongClick = {
                                selecting = true
                                selected = ListSelection.toggle(selected, entry.id)
                            },
                            trailing = if (selecting) {
                                { SelectionCheck(checked = isSelected) }
                            } else {
                                null
                            },
                        )
                    }
                }

                /**
                 * 长按提示。**摆在整份清单的末尾，不摆在顶上。**
                 *
                 * 顶上那个位置每一次打开保险库都会被看见，而这句话
                 * 只需要被看见一次——常驻在扫读起点上，它会先变成噪音，
                 * 再连累旁边真正要紧的那条备份提醒一起被略过（同决策㉞/(95)）。
                 * 末尾这个位置的好处正相反：滚到底的人恰恰是
                 * 「在库里翻来翻去、发现堆了一批废条目」的那个人，
                 * 也就是唯一需要多选的那个人。
                 *
                 * 它不可点。做成能点的话，等于把撤掉的那个按钮换个地方摆回来，
                 * 而这一行的用途是**教会那个手势**，不是替代它——
                 * 学会之后，用户在任何一屏（包括搜索结果之外的滚动位置）都能用。
                 */
                if (ListSelection.showHint(entries.size, selecting)) {
                    item(key = "hint") { SelectHint() }
                }

                if (entries.isEmpty()) {
                    item(key = "empty") {
                        Spacer(Modifier.height(24.dp))
                        EmptyState(
                            glyph = Glyph.Key,
                            title = "保险库是空的",
                            subtitle = "从最常用的那个账号开始。",
                            action = { BrassButton("添加第一条", onClick = onAdd) },
                        )
                    }
                }
            }

            /**
             * 底部：普通模式是加号，选择模式是删除栏。**两者永不同时出现。**
             *
             * 一个加号和一排多选框同时摆在屏幕上，用户不知道那一下会加到哪儿去；
             * 而选择模式下他要的动作只有一个，那个动作值得一整条栏。
             */
            if (selecting) {
                DeleteBar(
                    count = selected.size,
                    onDelete = { confirmDelete = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                AddButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
    }

    /**
     * 删除确认。
     *
     * ── 和详情页单条删除的语气是**反的**，而且都是实话 ──
     *
     * 那边写的是「删掉之后还能撤销一次」，因为 `EntryDetail.remove` 留了一份快照。
     * 这里没有那份快照：一次删 20 条要在内存里囤 20 条明文，而它们随时会被
     * 自动锁定（决策⑪）连同整棵子树一起换掉——一个**有时候能撤销、
     * 有时候不能**的撤销按钮，比没有更坏。所以这里如实说「不能撤销」，
     * 并且把这句话摆在用户按下去之前唯一会读的那个位置。
     *
     * `danger = true` 会同时做两件事：确认按钮染成铁锈色，
     * 以及**禁掉点外面关闭**（决策⑮：危险操作必须明确选一个按钮）。
     */
    if (confirmDelete) {
        val count = selected.size
        VaultDialog(
            title = ListSelection.confirmTitle(count),
            message = ListSelection.confirmMessage(count),
            // 只有名称，一个账号都不带。理由见 ListSelection.confirmDetail。
            detail = ListSelection.confirmDetail(entries, selected),
            confirmText = ListSelection.deleteText(count),
            danger = true,
            onConfirm = {
                confirmDelete = false
                // 一次 mutate，要么全删要么一条不动（VaultSession.deleteEntries）。
                val r = session.deleteEntries(selected)
                if (r.isSuccess) {
                    exitSelection()
                } else {
                    // 留在选择模式里，选中集合原样不动——他可以直接再试一次。
                    failure = ListSelection.FAILURE
                }
            },
            secondaryText = "取消",
            onSecondary = { confirmDelete = false },
            onDismissRequest = { confirmDelete = false },
        )
    }
}

/**
 * 备份提醒。两种语气，触发条件完全不同：
 *
 *  - **从未备份**：说明这个库现在只有一份，手机丢了就没了。这是硬提醒（Warn），
 *    而且按决策㉑它不会消失——用户跳过多少次，下次解锁照样挡在前面。
 *  - **有改动没进备份**：说明手上那份备份是旧的。按条数提醒而不是按天数，
 *    理由见 [VaultIndex.changedSince]。
 *
 * 两条都不满足时**什么都不显示**。「你的备份是最新的」这种绿条属于
 * 拿一整行屏幕说一句废话，而且看多了会让真正要紧的那条也被略过。
 */
@Composable
private fun BackupNotice(neverBackedUp: Boolean, pendingCount: Int, onBackup: () -> Unit) {
    when {
        neverBackedUp -> Banner(
            text = "还没有备份过，这个库只存在于这台设备上。",
            tone = BannerTone.Warn,
            actionText = "去备份",
            onAction = onBackup,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        )
        pendingCount > 0 -> Banner(
            text = "有 $pendingCount 条改动还没进备份。",
            tone = BannerTone.Info,
            actionText = "再备份一次",
            onAction = onBackup,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

/**
 * 列表末尾那行小字：告诉用户长按可以多选。
 *
 * ── 为什么是这个样子 ──
 *
 * 用 `Sub` + `Dimmer` 而不是 Banner：Banner 是**要人去点**的东西
 * （备份提醒那两条都带着一个动作），而这一行没有动作，只是一句话。
 * 给它一个边框和底色，它就会在扫读时和上面那条真正的提醒抢注意力，
 * 而两者的要紧程度差着一个量级。
 *
 * 居中是把它读成「清单到此为止」的那一下——同 [DeleteBar] 底下那句。
 * 靠左的话它会像是最后一组的一部分。
 */
@Composable
private fun SelectHint() {
    Text(
        text = ListSelection.LONG_PRESS_HINT,
        style = VaultType.Sub,
        color = VaultColors.Dimmer,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            // 四个边分开写：padding 没有「horizontal + top/bottom」那个重载。
            // 上下不对称是有意的——上面 22dp 把它和最后一条隔开，
            // 下面只留 6dp，让它贴着清单的末尾，而不是浮在一片空白中间。
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 6.dp),
    )
}

/**
 * 选择模式下的底部操作栏。
 *
 * ── 它是不透明的，而且带一条上边线 ──
 *
 * 底下压着的是一份还能滚动的列表。半透明的操作栏会让条目名从危险色按钮
 * 底下透出来，而这一刻用户正要确认「我要删的是不是这些」。
 *
 * ── 一条都没选时按钮是灰的，并且给一句话 ──
 *
 * 决策(61)。而且这一屏尤其需要：用户多半是刚用一个陌生手势（长按）进来的，
 * 一个没有解释的灰按钮会让他以为刚才那下按坏了什么。
 */
@Composable
private fun DeleteBar(
    count: Int,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VaultColors.Slab)
            .navigationBarsPadding(),
    ) {
        HairLine(color = VaultColors.Line)
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            DangerButton(
                text = ListSelection.deleteText(count),
                enabled = count > 0,
                onClick = onDelete,
            )
            Text(
                text = if (count == 0) {
                    ListSelection.emptyHint()
                } else {
                    ListSelection.confirmMessage(count)
                },
                style = VaultType.MonoSmall,
                color = if (count == 0) VaultColors.Dimmer else VaultColors.Rust,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 右下角的新增按钮。
 *
 * 手搓而不是用 Material3 的 `FloatingActionButton`：后者自带一套海拔阴影和
 * 涟漪配色，要全部覆盖成这套黑底黄铜的样子，代码里就会积一堆「关掉默认值」的样板——
 * 和 [VaultScreen] 不用 `Scaffold` 是同一个理由。
 */
@Composable
private fun AddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(18.dp)
            .size(60.dp)
            .clip(CircleShape)
            .background(VaultColors.Brass)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VaultIcon(Glyph.Plus, tint = VaultColors.Void, size = 28.dp, strokeWidth = 2.3f)
    }
}
