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

package cn.localvault.app.ui.autofill

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.EmptyState
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.IconSlot
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.list.VaultIndex
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 挑选页。用户在别人的应用里点了填充条末尾那条「在保险库里搜索」之后，落到的就是这一页。
 *
 * **这个文件里没有一条规则。** 摆哪几条、每一条旁边标什么、点下去之前必须先说哪几句、
 * 以及最后往哪几个框写，全在 [AutofillPick] 里（71 条用例）；此刻该摆哪一屏在
 * [AutofillPickFlow] 里。这一页只把那些东西画出来。
 * 想在这儿加一个 `if` 之前先停一下——它十有八九该加在那两个文件之一里，那边测得到。
 *
 * ── 两屏，不是一屏加一个弹窗 ──
 *
 * 挑中一条之后换的是**整屏**（[ConfirmBody]），不是从底下推一个半高的 sheet 上来。
 * 理由是决策(160)：手动挑这一下之所以被允许，靠的是「自动的那一下用户可能没看清，
 * 手动的那一下他一定看清了」。而 [AutofillPick.warningsFor] 那几句每一句都是三四行的
 * 完整句子——塞进半屏 sheet 里，它们会变成一个需要滚动的小窗口，
 * 或者被人顺手折成一句「查看详情」。那两种做法都会让上面那条前提不再成立。
 *
 * 同理，[AutofillPick.Choice.warnings] 里的每一句在这一页上**逐句原样摆出来**，
 * 一句都不许折叠、不许省略号、不许「展开更多」。
 *
 * ── 这一页上没有一处出现密码 ──
 *
 * [AutofillPick.Row] 里根本没有能放它的字段，[AutofillPick.Choice.slots] 里
 * 也只有格位没有值。所以「挑选页上不显示密码」在这一层是**类型保证**，
 * 不是一条要靠人记得的纪律（同 `AutofillRow` / `AutofillViews` 那两处）。
 *
 * ── 关键词不落盘 ──
 *
 * [query] 用 `remember` 而不是 `rememberSaveable`，理由和 `SearchScreen` 一模一样：
 * 关键词本身就是库内容的投影，而 `savedInstanceState` 是一条明文落盘的路径。
 * 这一页比搜索页更该守这一条——它的宿主 Activity 浮在别人的应用上面，
 * 被系统回收重建的概率高得多。
 */
@Composable
fun AutofillPickScreen(
    plan: FillPlan.Plan,
    entries: List<VaultEntry>,
    trust: HostTrust,
    appLabel: String?,
    browserLevel: BrowserTrust.Level?,
    onConfirm: (VaultEntry) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var chosenId by remember { mutableStateOf<String?>(null) }

    val origin = plan.primary?.origin
    val handOver = remember(origin, appLabel) { AutofillPick.handOver(origin, appLabel) }

    val listing = remember(plan, entries, trust) { AutofillPick.listing(plan, entries, trust) }
    val results = remember(plan, entries, trust, query) {
        if (VaultIndex.normalizeQuery(query).isEmpty()) {
            null
        } else {
            AutofillPick.search(plan, entries, query, trust)
        }
    }

    val chosen = chosenId?.let { id -> entries.firstOrNull { it.id == id } }

    // 挑中一条之后按返回，退的是「这一条」而不是整页。
    // 直接退出整页的话，用户想换一条就得从头再点一次填充条。
    BackHandler(enabled = chosen != null) { chosenId = null }

    if (chosen != null) {
        val choice = remember(chosen, plan, trust, appLabel, browserLevel) {
            AutofillPick.choose(chosen, plan, trust, appLabel, browserLevel)
        }
        ConfirmBody(
            choice = choice,
            onBack = { chosenId = null },
            onConfirm = { onConfirm(chosen) },
        )
        return
    }

    ListBody(
        handOver = handOver,
        listing = listing,
        results = results,
        query = query,
        siteTitle = AutofillPickFlow.siteSectionTitle(origin),
        onQueryChange = { query = it },
        onPick = { chosenId = it.entryId },
        onCancel = onCancel,
    )
}

/* ─────────────────────────── 清单那一屏 ─────────────────────────── */

@Composable
private fun ListBody(
    handOver: String,
    listing: AutofillPick.Listing,
    results: List<AutofillPick.Row>?,
    query: String,
    siteTitle: String,
    onQueryChange: (String) -> Unit,
    onPick: (AutofillPick.Row) -> Unit,
    onCancel: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    // 一开始滚就收键盘（同 SearchScreen）：结果已经实时出来了，此刻屏幕比输入框值钱。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }

    VaultScreen {
        Column(Modifier.fillMaxSize().imePadding()) {
            PickBar(
                query = query,
                onQueryChange = onQueryChange,
                onClose = onCancel,
                onSubmit = { focusManager.clearFocus() },
            )

            // 「会交给谁」钉在顶上，不随列表滚走。
            // 这是这一页上唯一一句用户做决定时非看不可的话（决策(188)），
            // 而列表往下滚两屏之后它就再也不在视野里了——除非它不滚。
            HandOverStrip(handOver)

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (results != null) {
                    if (results.isEmpty()) {
                        item {
                            EmptyState(
                                glyph = Glyph.Search,
                                title = "没有对得上的",
                                subtitle = AutofillPickFlow.NO_RESULTS,
                            )
                        }
                    } else {
                        item { Eyebrow(AutofillPickFlow.SECTION_RESULTS) }
                        items(results, key = { it.entryId }) { row ->
                            PickRow(row, onClick = { onPick(row) })
                        }
                    }
                    return@LazyColumn
                }

                // 决策(95)：一切正常时一句废话都不说，note 为 null 就什么都不摆
                listing.note?.let { note ->
                    item { Banner(note, tone = BannerTone.Info) }
                }

                if (listing.forThisSite.isNotEmpty()) {
                    item { Eyebrow(siteTitle) }
                    items(listing.forThisSite, key = { "site:${it.entryId}" }) { row ->
                        PickRow(row, onClick = { onPick(row) })
                    }
                }

                if (listing.recent.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Eyebrow(AutofillPickFlow.SECTION_RECENT)
                    }
                    items(listing.recent, key = { "recent:${it.entryId}" }) { row ->
                        PickRow(row, onClick = { onPick(row) })
                    }
                }

                // 「这里只摆了几条」——不说这一句的后果是用户以为条目丢了，
                // 而他不会想到去搜一下（决策(189)）
                if (listing.partial) {
                    item {
                        Text(
                            AutofillPick.PARTIAL_NOTE,
                            style = VaultType.Sub,
                            color = VaultColors.Dimmer,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                if (listing.isEmpty && listing.note == null) {
                    item {
                        EmptyState(
                            glyph = Glyph.Lock,
                            title = "保险库里还没有条目",
                            subtitle = AutofillPick.EMPTY_VAULT,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶栏就是搜索框本身，右边一个关闭。
 *
 * **不进来就弹键盘**——这一点和 `SearchScreen` 反着来，是有意的。
 * 搜索页是用户专门点搜索图标进去的，他进去就是要打字；
 * 这一页他点进来是为了**看看有哪几条**，默认清单第一段往往就是他要的那条。
 * 一进来就顶上一块键盘，等于把那一段挤出屏幕。
 */
@Composable
private fun PickBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .clip(VaultShape.Field)
                .background(VaultColors.Slab)
                .border(1.dp, VaultColors.Line, VaultShape.Field)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            VaultIcon(Glyph.Search, tint = VaultColors.Dim, size = 21.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        AutofillPickFlow.SEARCH_HINT,
                        style = VaultType.Body.copy(color = VaultColors.Dim),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = VaultType.Body.copy(color = VaultColors.Text),
                    singleLine = true,
                    cursorBrush = SolidColor(VaultColors.Brass),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconSlot(Glyph.Close, contentDescription = "清空", onClick = { onQueryChange("") })
            }
        }
        IconSlot(Glyph.Close, contentDescription = "不填了", onClick = onClose)
    }
}

/** 钉在顶上的那一行「会交给谁」。 */
@Composable
private fun HandOverStrip(text: String) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VaultColors.BrassWash)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VaultIcon(Glyph.Share, tint = VaultColors.Brass, size = 19.dp, strokeWidth = 1.85f)
            Text(text, style = VaultType.Sub, color = VaultColors.Brass, modifier = Modifier.weight(1f))
        }
        HairLine()
    }
}

/* ─────────────────────────── 一行 ─────────────────────────── */

/**
 * 清单上的一行。
 *
 * 三样标记，各自只在该出现的时候出现：
 *   · [AutofillPick.Row.auto] —— 够格自动填的那两档，一个不打眼的黄铜点。
 *     **不写「推荐」两个字**：那是在替用户排序，而他来这一页正是因为
 *     我们排的那个序里没有他要的那条。
 *   · [AutofillPick.Row.matchedDomain] —— 对上的那一行网址**原文**。
 *     兄弟域那种情形全靠它才看得出来（决策(159) 的第二道兜底）。
 *   · [AutofillPick.Row.needsWarning] —— 点下去会先看到几句话。
 *     在这里先露一点，是为了让用户点之前就知道「这一条有话要说」，
 *     而不是点完被一屏字挡住、以为出了错。
 *
 * 填不出东西的那一条**画成禁用而不是藏起来**（决策(174) 的同一条思路）：
 * 藏起来的后果是用户在这一页上找不到他明明记得存过的那一条，
 * 然后开始怀疑库里的数据没了。
 */
@Composable
private fun PickRow(row: AutofillPick.Row, onClick: () -> Unit) {
    val enabled = row.fillable
    VaultCard(
        modifier = Modifier.fillMaxWidth(),
        background = if (enabled) VaultColors.Slab else VaultColors.Void,
        borderColor = VaultColors.LineSoft,
        shape = VaultShape.Row,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (row.auto) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(VaultShape.TileSm)
                                .background(VaultColors.Brass),
                        )
                    }
                    Text(
                        row.label,
                        style = VaultType.RowName,
                        color = if (enabled) VaultColors.Text else VaultColors.Dimmer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    row.sublabel,
                    style = VaultType.Sub,
                    color = VaultColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.matchedDomain?.let { d ->
                    Text(
                        d,
                        style = VaultType.MonoSmall,
                        color = if (row.verdict == DomainMatch.Verdict.SameSite) {
                            VaultColors.Brass
                        } else {
                            VaultColors.Dimmer
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!enabled) {
                    Text(
                        "这一屏上填不出东西",
                        style = VaultType.Sub,
                        color = VaultColors.Dimmer,
                    )
                }
            }
            if (row.needsWarning && enabled) {
                VaultIcon(Glyph.Warn, tint = VaultColors.Brass, size = 19.dp, strokeWidth = 1.85f)
            }
        }
    }
}

/* ─────────────────────────── 确认那一屏 ─────────────────────────── */

/**
 * 挑中之后、按下确认之前的那一整屏。
 *
 * 顺序是有讲究的，从上到下依次是：
 *   1. **会交给谁**（[AutofillPick.Choice.handOver]）——决定的依据，摆最上面；
 *   2. 挑的是哪一条——让他确认自己没点错行；
 *   3. **必须先看的几句**（[AutofillPick.Choice.warnings]）——逐句摆开；
 *   4. 陈述句（兄弟域、这一屏的性质、浏览器核验到哪一步）；
 *   5. 会填哪几格——**只有格位，没有值**；
 *   6. 两个按钮，主按钮是「确认填入」。
 *
 * 警告排在陈述句前面，而不是按「重要性」混在一起：混排的结果是用户
 * 学会整块跳过（决策(95)）。这一段里只放要他小心的话，别的都在下面。
 */
@Composable
private fun ConfirmBody(
    choice: AutofillPick.Choice,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scroll = rememberScrollState()

    VaultScreen(title = AutofillPickFlow.TITLE, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 会交给谁
            Banner(choice.handOver, tone = BannerTone.Warn)

            // 2. 挑的是哪一条
            VaultCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(choice.row.label, style = VaultType.RowName, color = VaultColors.Text)
                    Text(choice.row.sublabel, style = VaultType.Sub, color = VaultColors.Dim)
                    choice.row.matchedDomain?.let {
                        Text(it, style = VaultType.MonoSmall, color = VaultColors.Dimmer)
                    }
                }
            }

            // 3. 必须先看的几句。一句都不折叠（见文件头）
            if (choice.warnings.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Eyebrow(AutofillPickFlow.WARN_HEADING, color = VaultColors.Brass)
                    choice.warnings.forEach { w ->
                        Banner(w, tone = BannerTone.Warn)
                    }
                }
            }

            // 4. 陈述句
            if (choice.notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choice.notes.forEach { n ->
                        Text(n, style = VaultType.Sub, color = VaultColors.Dim)
                    }
                }
            }

            // 5. 会填哪几格
            AutofillPickFlow.slotsLine(choice.slots)?.let { line ->
                Text(line, style = VaultType.MonoSmall, color = VaultColors.Dimmer)
            }

            // 不给按的那一条，理由原样摆出来
            choice.blocked?.let { why ->
                Banner(why, tone = BannerTone.Danger)
            }

            Spacer(Modifier.height(2.dp))

            BrassButton(
                text = if (choice.canFill) AutofillPickFlow.CONFIRM else AutofillPickFlow.CANNOT_FILL,
                onClick = onConfirm,
                enabled = choice.canFill,
            )
            GhostButton(AutofillPickFlow.BACK, onClick = onBack, tint = VaultColors.Dim)
        }
    }
}

/* ─────────────────────────── 拒绝那一屏 ─────────────────────────── */

/**
 * [AutofillPick.refusal] 说不该出现时的那一屏。
 *
 * 话原样摆出来，一个字都不改写，也**不加「出错了」之类的帽子**——
 * 那两种情形（这一屏没有可填的框 / 这是保险库自己的界面）都不是故障。
 */
@Composable
fun AutofillRefusalScreen(reason: String, onClose: () -> Unit) {
    VaultScreen(title = AutofillPickFlow.TITLE, onBack = onClose) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            EmptyState(
                glyph = Glyph.Shield,
                title = "这一次不填",
                subtitle = reason,
            )
            GhostButton("知道了", onClick = onClose, tint = VaultColors.Dim)
        }
    }
}
