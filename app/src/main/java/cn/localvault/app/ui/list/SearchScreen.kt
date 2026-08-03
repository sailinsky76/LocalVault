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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.EmptyState
import cn.localvault.app.ui.components.EntryTile
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.IconSlot
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 全屏搜索。
 *
 * 匹配和打分早在 M3-3a 就写完了（[VaultIndex.search]），切片和高亮在
 * [SearchHighlight] 里，所以这一页只做三件事：收关键词、把结果画出来、
 * 在搜不到的时候给一个去处。
 *
 * ── 关键词绝不落盘：既不做搜索历史，也不用 rememberSaveable ──
 *
 * 关键词**本身就是库内容的投影**。用户打下「招商」两个字，这两个字
 * 就等于「这个库里有招商银行」。做一份「最近搜索」意味着在那个加密文件
 * 之外又开了一份未加密的目录索引，而这个产品的整个前提是
 * 「除了那一个文件，别处不留库内容」。搜索历史带来的方便，
 * 换不来砸掉这个前提的代价。
 *
 * 同样的理由，[query] 用 `remember` 而不是 `rememberSaveable`：后者会把
 * 字符串写进 `savedInstanceState`，那是一条明文落盘的路径。
 * 代价是转屏会丢掉正在输入的关键词——这个代价我们认。
 *
 * ── 不做防抖 ──
 *
 * 全库已经在内存里，几百条的线性扫描是微秒级的事。加一层 debounce 只会
 * 在每次按键和出结果之间插进一段用户能感觉到的延迟，还额外引入
 * 「刚打完就按返回、最后一次搜索还排在队里」这种竞态。
 * 真到条目上万那天（决策⑤已经写明那时要改增量方案），再一起处理。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onCreateNamed: (String) -> Unit,
) {
    val session = LocalSession.current
    val state by session.state.collectAsState()

    // 和列表页一样：锁定那一瞬间这一帧可能还会被画一次，
    // 与其去读一个刚被清空的 data，不如什么都不画。
    val data = (state as? VaultSession.State.Unlocked)?.data ?: return
    val entries = data.entries

    var query by remember { mutableStateOf("") }

    val hits = remember(entries, query) { VaultIndex.search(entries, query) }
    val categories = remember(entries) { VaultIndex.categories(entries) }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    // 进来就把键盘弹出来。用户是专门点搜索图标进来的，
    // 再让他点一下输入框是白白多一次操作。
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 一开始滚结果就收键盘：结果已经实时出来了，此刻屏幕比输入框值钱。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }

    VaultScreen(seal = { DefaultSeal() }) {
        Column(Modifier.fillMaxSize().imePadding()) {
            SearchBar(
                query = query,
                onQueryChange = { query = it },
                onBack = onBack,
                onSubmit = { focusManager.clearFocus() },
                focusRequester = focusRequester,
            )

            when {
                VaultIndex.normalizeQuery(query).isEmpty() ->
                    IdleBody(
                        categories = categories,
                        onPickCategory = { query = it },
                    )

                hits.isEmpty() ->
                    NoResults(
                        query = query.trim(),
                        onCreate = { onCreateNamed(query.trim()) },
                    )

                else ->
                    Results(
                        hits = hits,
                        listState = listState,
                        onOpenEntry = onOpenEntry,
                    )
            }
        }
    }
}

/* ─────────────────────────── 搜索栏 ─────────────────────────── */

/**
 * 顶栏就是输入框本身，不再另起一行标题。
 *
 * 这一屏只有一个目的，标题栏上写「搜索」两个字纯属复述，
 * 而那 52dp 在结果列表上更有用。
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconSlot(Glyph.Back, contentDescription = "返回", onClick = onBack)

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
                        "名称 · 账号 · 网址 · 分类",
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
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            if (query.isNotEmpty()) {
                IconSlot(
                    Glyph.Close,
                    contentDescription = "清空",
                    onClick = { onQueryChange("") },
                )
            }
        }
    }
}

/* ─────────────────────────── 空关键词 ─────────────────────────── */

/**
 * 还没输入时的样子。
 *
 * 这里**不列全库**。列表页已经是那份清单了，搜索页再列一遍会让人以为
 * 这是两份数据；更实际的问题是，用户点进搜索是为了缩小范围，
 * 一进来先看到全部只会让他多滑两下。
 *
 * 取而代之的是把决策㉜那张白名单**明明白白写在屏幕上**。
 * 备注和密码不参与搜索是个刻意的决定，但用户不知道——他会拿备注里的
 * 身份证号去搜，搜不到，然后合理地认为这个搜索坏了。
 * 与其让他猜，不如一开始就说清楚：能搜哪四个字段，为什么另外两个不能搜。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleBody(categories: List<String>, onPickCategory: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        VaultCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    VaultIcon(Glyph.Shield, tint = VaultColors.Dim, size = 19.dp)
                    Text("可以搜到的", style = VaultType.H2, color = VaultColors.Text)
                }
                Text(
                    "名称、账号、网址、分类。",
                    style = VaultType.Sub,
                    color = VaultColors.Dim,
                )
                Text(
                    "备注和密码不参与搜索——备注里常放密保答案这类东西。",
                    style = VaultType.Sub,
                    color = VaultColors.Dimmer,
                )
            }
        }

        if (categories.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Eyebrow("分类")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { c ->
                        CategoryChip(c, onClick = { onPickCategory(c) })
                    }
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        Text(
            "搜索内容不会被记录。",
            style = VaultType.MonoSmall,
            color = VaultColors.Dimmer,
        )
    }
}

/**
 * 分类快捷键：点一下就是**把分类名填进关键词**，不是另开一套筛选状态。
 *
 * 真做成独立的筛选态，马上要回答「筛了『银行』又搜『招商』，是 AND 还是 OR」，
 * 还得在界面上表达「你现在处于筛选中」这件事。而分类本来就是可搜字段
 * （[VaultIndex.Field.Category]），填进去得到的结果几乎一样，
 * 还顺带把名字里带「银行」却没归类的那几条也捞出来——那通常正是用户想要的。
 */
@Composable
private fun CategoryChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = VaultType.Sub,
        color = VaultColors.Dim,
        modifier = Modifier
            .clip(VaultShape.Field)
            .background(VaultColors.Slab2)
            .border(1.dp, VaultColors.LineSoft, VaultShape.Field)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    )
}

/* ─────────────────────────── 无结果 ─────────────────────────── */

/**
 * 搜不到时给的是**「新增一条」，不是「换个词试试」**。
 *
 * 搜不到最常见的原因不是拼错，是这条压根还没存进来：用户刚在某个网站
 * 注册完，回到 App 里想找找看，发现没有。这时候让他退出搜索、点右下角
 * 加号、再把刚打过的字重打一遍，是三次没必要的操作。
 *
 * 关键词通过 [cn.localvault.app.ui.nav.DraftHandoff] 交接，不走路由参数——
 * 理由见那个文件。
 */
@Composable
private fun NoResults(query: String, onCreate: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(20.dp))
        EmptyState(
            glyph = Glyph.Search,
            title = "没有匹配的条目",
            subtitle = "「$query」在名称、账号、网址和分类里都没有出现。",
            action = {
                BrassButton("新增「${query.take(12)}${if (query.length > 12) "…" else ""}」", onClick = onCreate)
            },
        )
    }
}

/* ─────────────────────────── 结果列表 ─────────────────────────── */

@Composable
private fun Results(
    hits: List<VaultIndex.Hit>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenEntry: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 24.dp),
    ) {
        item(key = "count") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Eyebrow("结果")
                Eyebrow(hits.size.toString())
            }
        }

        items(hits, key = { it.entry.id }) { hit ->
            HitRow(hit = hit, onClick = { onOpenEntry(hit.entry.id) })
        }

        // 上限触顶时说一句。不说的话用户会以为「就这么多了」，
        // 然后再也搜不到那条排在第 201 位的。
        if (hits.size >= SEARCH_LIMIT) {
            item(key = "truncated") {
                Text(
                    "只显示前 $SEARCH_LIMIT 条，多打几个字缩小范围。",
                    style = VaultType.MonoSmall,
                    color = VaultColors.Dimmer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** 和 [VaultIndex.search] 的默认值保持一致。 */
private const val SEARCH_LIMIT = 200

/**
 * 结果里的一行。它和列表页的 `EntryRow` 长得像，但多一件事必须做到：
 * **把「这一条为什么会出现」摆在脸上**。
 *
 * 所以：
 *  - 名称命中 → 高亮画在主行上，右侧不标字段（名称本来就占着主位）；
 *  - 其余命中 → 副行换成命中片段，右侧标出「账号 / 网址 / 分类」。
 *    用户搜一串数字时，一条高亮在账号、一条高亮在网址，不标字段的话
 *    这两行看起来一模一样。
 *
 * 和列表页一样，右侧**不放**「一键复制密码」——搜索结果尤其不该放，
 * 这里的每一行都是刚刚才浮现出来的，用户还没确认是不是他要的那条。
 */
@Composable
private fun HitRow(hit: VaultIndex.Hit, onClick: () -> Unit) {
    val entry = hit.entry
    val nameHit = hit.field == VaultIndex.Field.Name
    val label = SearchHighlight.fieldLabel(hit.field)

    val nameSnippet = remember(hit) {
        if (nameHit) SearchHighlight.nameSnippet(hit.text, hit.range) else null
    }
    val subSnippet = remember(hit) {
        if (nameHit) null else SearchHighlight.snippet(hit.text, hit.range)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VaultShape.Row)
            .clickable(onClick = onClick)
            .semantics { contentDescription = SearchHighlight.describe(hit) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntryTile(entry)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (nameSnippet != null) {
                    HighlightText(
                        snippet = nameSnippet,
                        style = VaultType.RowName,
                        baseColor = VaultColors.Text,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Text(
                        entry.name,
                        style = VaultType.RowName,
                        color = VaultColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (entry.favorite) {
                    VaultIcon(Glyph.StarFilled, tint = VaultColors.Brass, size = 15.dp)
                }
            }

            if (subSnippet != null) {
                HighlightText(
                    snippet = subSnippet,
                    style = VaultType.MonoSmall,
                    baseColor = VaultColors.Dim,
                )
            } else {
                Text(
                    entry.username.ifEmpty { entry.domains.firstOrNull() ?: "—" },
                    style = VaultType.MonoSmall,
                    color = VaultColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (label != null) {
            Text(
                label,
                style = VaultType.Eyebrow,
                color = VaultColors.Dimmer,
                modifier = Modifier
                    .clip(VaultShape.TileSm)
                    .background(VaultColors.Slab2)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        } else {
            VaultIcon(Glyph.Chevron, tint = VaultColors.Dim, size = 18.dp)
        }
    }
}

/**
 * 把 [SearchHighlight.Snippet] 画出来。
 *
 * 省略号用比正文更暗的颜色：它是我们加的，不是用户的数据，
 * 和数据同色会让人以为条目名里真的带着一个「…」。
 */
@Composable
private fun HighlightText(
    snippet: SearchHighlight.Snippet,
    style: TextStyle,
    baseColor: Color,
    modifier: Modifier = Modifier,
    highlightColor: Color = VaultColors.Brass,
) {
    val text: AnnotatedString = remember(snippet, baseColor, highlightColor) {
        buildAnnotatedString {
            val ell = SpanStyle(color = VaultColors.Dimmer)
            if (snippet.leadingEllipsis) withStyle(ell) { append('…') }
            snippet.segments.forEach { seg ->
                if (seg.highlighted) {
                    withStyle(
                        SpanStyle(color = highlightColor, fontWeight = FontWeight.SemiBold),
                    ) { append(seg.text) }
                } else {
                    withStyle(SpanStyle(color = baseColor)) { append(seg.text) }
                }
            }
            if (snippet.trailingEllipsis) withStyle(ell) { append('…') }
        }
    }
    Text(
        text = text,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
