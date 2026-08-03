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

package cn.localvault.app.ui.apps

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.IconSlot
import cn.localvault.app.ui.components.PlainField
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 应用选择器覆盖层。
 *
 * ── 和 [cn.localvault.app.ui.generate.GeneratorSheet] 逐条同构 ──
 *
 * **不是路由**：选中的包名要交回正在填的那张表单，而页面之间回传值的通道是
 * `savedStateHandle`，那是一个会被写进 `savedInstanceState` 的 Bundle
 * （[cn.localvault.app.ui.nav.DraftHandoff] 整篇注释要堵的洞）。
 * 做成同一棵 composition 里的覆盖层，回传就是一个普通的 Kotlin 回调。
 *
 * **不是 Dialog**：Compose 的 `Dialog` 是独立 Window，Activity 上的 `FLAG_SECURE`
 * 不会传下去（决策⑭）。这一屏上摆着的是「这台手机装了哪些应用」，
 * 那是一份不该能被别的应用录屏录走的清单。画在当前 window 里自动继承防截屏。
 *
 * ── 点一下就生效，没有「确定」──
 *
 * 底下那个按钮写的是「完成」，它只负责关掉这一层——**勾选在点下去的那一刻就已经
 * 写进草稿了**。做成「先勾选、再确定」的话，用户按返回手势退出时那几下勾选会
 * 无声无息地作废，而屏幕上没有任何地方说过要按确定。
 * 而勾错了的代价极低：再点一下就撤销（[cn.localvault.app.ui.edit.DomainTargets.toggle]），
 * 而且草稿本来就还没保存。
 *
 * @param added 清单里已有的包名，**归一后（小写）的形式**。
 * @param onToggle 用户点了某一行。参数是包名原文。
 */
@Composable
fun AppPickerSheet(
    added: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalog = rememberAppCatalog()
    var query by remember { mutableStateOf("") }

    /**
     * 名单在 IO 线程上读。`null` = 还在读，空表 = 读完了但一个都没有。
     *
     * 两者必须分得开：一个「还在读」的空屏上写「读不到应用列表」，
     * 用户会在名单出现前的那半秒里以为功能坏了，然后关掉再也不点第二次。
     */
    val loaded: List<AppPicker.App>? by produceState<List<AppPicker.App>?>(null, catalog) {
        value = withContext(Dispatchers.IO) { catalog.load() }
    }

    val sections = remember(loaded, query) {
        loaded?.let { AppPicker.sections(AppPicker.filter(it, query)) }
    }

    // 系统返回键关掉这一层，而不是退出正在填的表单。
    // 关掉不丢任何东西——勾选早就写进草稿了。
    BackHandler { onDismiss() }

    Box(modifier.fillMaxSize()) {

        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(VaultShape.Sheet)
                .background(VaultColors.Slab)
                .border(1.dp, VaultColors.Line, VaultShape.Sheet)
                .navigationBarsPadding()
                // 搜索框要给键盘让位，否则打字时它正好被顶在键盘底下。
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "选择应用",
                    style = VaultType.H2,
                    color = VaultColors.Text,
                    modifier = Modifier.weight(1f),
                )
                IconSlot(Glyph.Close, contentDescription = "关闭", onClick = onDismiss)
            }

            HairLine()

            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                PlainField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜应用名或包名",
                    imeAction = ImeAction.Search,
                    trailing = {
                        if (query.isEmpty()) {
                            Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                                VaultIcon(Glyph.Search, tint = VaultColors.Dimmer, size = 20.dp)
                            }
                        } else {
                            IconSlot(
                                Glyph.Close,
                                contentDescription = "清空",
                                tint = VaultColors.Dim,
                                onClick = { query = "" },
                            )
                        }
                    },
                )
            }

            HairLine()

            /**
             * 名单区高度写死上限，不用 `fillMaxHeight`。
             *
             * 覆盖层要露出下面那张表单的一角——用户得看得见自己是在给哪一条挑应用。
             * 一个铺满全屏的选择器和一个新页面没有区别，而它偏偏又不在返回栈里。
             */
            Box(Modifier.heightIn(min = 180.dp, max = 400.dp)) {
                when {
                    sections == null -> CenterNote("正在读取应用列表…")

                    loaded!!.isEmpty() -> CenterNote(AppPicker.UNAVAILABLE)

                    sections.isEmpty() -> CenterNote(AppPicker.emptyResult(query))

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 10.dp,
                        ),
                    ) {
                        sections.forEach { section ->
                            if (section.title != null) {
                                item(key = "h-${section.title}") {
                                    Column {
                                        Spacer(Modifier.height(10.dp))
                                        Eyebrow(
                                            section.title,
                                            modifier = Modifier.padding(
                                                start = 2.dp,
                                                top = 4.dp,
                                                bottom = 8.dp,
                                            ),
                                            color = VaultColors.Dimmer,
                                        )
                                    }
                                }
                            }
                            items(section.apps, key = { it.packageName }) { app ->
                                AppRow(
                                    app = app,
                                    catalog = catalog,
                                    checked = app.packageName.lowercase(Locale.ROOT) in added,
                                    onClick = { onToggle(app.packageName) },
                                )
                            }
                        }
                    }
                }
            }

            HairLine()

            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                BrassButton("完成", onClick = onDismiss)
                Text(
                    AppPicker.PRIVACY_NOTE,
                    style = VaultType.MonoSmall,
                    color = VaultColors.Dimmer,
                )
            }
        }
    }
}

/**
 * 名单上的一行：图标 + 应用名 + 包名 + 勾。
 *
 * ── 包名一直显示，不只在勾中时显示 ──
 *
 * 它是**真正被存进库的那一串**，而用户此刻正在替一条数据做决定。
 * 藏起来的话，他事后在详情页看到 `com.tencent.mm` 会想不起来这是从哪儿来的。
 * 用等宽字排在应用名下面，用最弱的那档灰——认得的人看得见，不认得的人不会被它挡路。
 */
@Composable
private fun AppRow(
    app: AppPicker.App,
    catalog: InstalledAppCatalog,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VaultShape.Row)
            .background(if (checked) VaultColors.BrassTint else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app.packageName, app.label, catalog)
        Column(Modifier.weight(1f)) {
            Text(app.label, style = VaultType.RowName, color = VaultColors.Text, maxLines = 1)
            Text(
                app.packageName,
                style = VaultType.MonoSmall,
                color = VaultColors.Dimmer,
                maxLines = 1,
            )
        }
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (checked) VaultIcon(Glyph.Check, tint = VaultColors.Brass, size = 20.dp)
        }
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = VaultType.Sub, color = VaultColors.Dim)
    }
}
