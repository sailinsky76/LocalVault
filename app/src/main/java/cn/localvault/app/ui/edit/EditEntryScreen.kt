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

package cn.localvault.app.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.VaultDialog
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.apps.AppPickerSheet
import cn.localvault.app.ui.generate.GeneratorSheet
import cn.localvault.app.ui.list.VaultIndex
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType

/**
 * 编辑已有条目。
 *
 * ── 这一页不自动保存 ──
 *
 * 「改一个字就落一次盘」在笔记类应用里是对的，在这里是错的，有两个理由：
 *
 *  1. 条目一落盘 `updatedAt` 就会刷新，于是列表页那条「有 N 条改动还没进备份」
 *     （决策㉞）跟着涨。用户点进来看了一眼、改了个字又改回去，
 *     不该因此欠下一次备份。
 *  2. 自动保存意味着「改错了想撤回」没有落点。删除有墓碑页可以撤销（决策㊺），
 *     编辑没有——真正的撤销落点就是**还没按下的那个保存按钮**。
 *
 * 代价是要自己处理「改了没存就返回」，那就是下面那道拦截。
 *
 * ── 进来不自动弹键盘 ──
 *
 * 用户点铅笔多半是冲着某一个字段来的。自动聚焦到名称会把键盘顶起来遮掉半张表单，
 * 还容易让他以为光标所在的地方就是他要改的那一行。
 * （新增流不一样——那一步的第一个动作确实就是打名称，由 M3-5 自己决定。）
 */
@Composable
fun EditEntryScreen(
    entryId: String,
    onBack: () -> Unit,
) {
    val session = LocalSession.current
    val state by session.state.collectAsState()

    val data = (state as? VaultSession.State.Unlocked)?.data ?: return
    val entry = data.entries.firstOrNull { it.id == entryId }

    // 条目不在了（在别处被删掉、或者返回栈上残留的一帧），安静退出。
    // 和详情页一个处理：用户没做错什么，「条目不存在」那句话只会让他以为出了故障。
    if (entry == null) {
        LaunchedEffect(entryId) { onBack() }
        return
    }

    /**
     * 原始草稿用 `remember(entryId)` 而不是 `remember(entry)`。
     *
     * 拿 `entry` 当 key 的话，只要那条条目的对象换了新的（哪怕是别处刷新了一次时间戳），
     * 原始草稿就会被重算成「当前值」，于是 [EntryForm.isDirty] 立刻变 false，
     * 用户改了半天的东西不再受那道拦截保护，返回时一声不吭全丢。
     */
    val original = remember(entryId) { EntryForm.draftOf(entry) }
    var draft by remember(entryId) { mutableStateOf(original) }
    var revealed by remember(entryId) { mutableStateOf(false) }

    var confirmDiscard by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    /**
     * 生成器是一个**覆盖层**，不是一个页面。
     *
     * 见 [cn.localvault.app.ui.generate.GeneratorSheet] 顶上那段：
     * 做成路由的话，生成出来的密码只能靠 `savedStateHandle` 回传，
     * 而那是一个会被写进 `savedInstanceState` 的 Bundle。
     * 这里它就是这一页里的一个布尔状态，结果通过普通回调交回来。
     */
    var showGenerator by remember { mutableStateOf(false) }

    /**
     * 应用选择器。和上面那个生成器**逐条同构**：同一棵 composition 里的覆盖层，
     * 不是路由也不是 Dialog（理由见 [cn.localvault.app.ui.apps.AppPickerSheet] 顶上那段）。
     */
    var showAppPicker by remember { mutableStateOf(false) }

    val dirty = EntryForm.isDirty(original, draft)
    val nameOk = EntryForm.nameOk(draft)
    val categories = remember(data.entries) { VaultIndex.categories(data.entries) }

    val leave: () -> Unit = { if (dirty) confirmDiscard = true else onBack() }

    // 系统返回手势 / 返回键走同一道拦截。只在有改动时接管，
    // 没改动时让它按默认行为直接退出——多一道确认框只会教会用户闭眼点确认。
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Box(Modifier.fillMaxSize()) {
        VaultScreen(
            title = "编辑",
            onBack = leave,
            seal = { DefaultSeal() },
        ) {
            Column(Modifier.fillMaxSize().imePadding()) {

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (failure != null) {
                        Banner(text = failure!!, tone = BannerTone.Danger)
                    }

                    EntryFormFields(
                        draft = draft,
                        onDraftChange = { draft = it },
                        categories = categories,
                        passwordRevealed = revealed,
                        onTogglePasswordReveal = { revealed = !revealed },
                        onGenerate = { showGenerator = true },
                        onPickApp = { showAppPicker = true },
                    )

                    Spacer(Modifier.height(6.dp))
                }

                HairLine()

                /**
                 * 保存按钮固定在底部，不放顶栏。
                 *
                 * 表单滚起来之后顶栏上的「保存」会跟着滚出视野，用户改完最后一个字段
                 * 得先往回滚才能提交。底部固定还有一个好处：它和键盘一起上移
                 * （`imePadding`），打完字抬手就能按到。
                 */
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    BrassButton(
                        text = "保存",
                        enabled = nameOk && dirty,
                        onClick = {
                            val next = EntryForm.applyTo(entry, draft)
                            val r = session.updateEntry(next)
                            if (r.isSuccess) {
                                onBack()
                            } else {
                                failure = "改动没能写进保险库，条目还是原来的样子。"
                            }
                        },
                    )

                    /**
                     * 按钮为什么是灰的，永远要给一句话。
                     *
                     * **「没有改动」也要说**：一个灰着的保存按钮，用户第一反应是
                     * 「这个 App 卡了」。而没改动时不让点，是因为点一下照样会刷新
                     * `updatedAt`，让「有 N 条改动还没进备份」凭空 +1
                     * ——他其实什么都没改。
                     */
                    Text(
                        text = when {
                            !nameOk -> "名称是唯一必填项——列表和搜索都靠它认人。"
                            !dirty -> "还没有改动。"
                            else -> "保存后立刻写进保险库。"
                        },
                        style = VaultType.MonoSmall,
                        color = if (!nameOk) VaultColors.Rust else VaultColors.Dimmer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        /**
         * 采用之后**顺手把密码显示出来**。
         *
         * 不这么做的话，用户按完「用这个密码」，看到的是密码框里多了一串圆点——
         * 圆点和圆点之间没有任何地方写着刚才生成的是什么，
         * 他会立刻去点那只眼睛核对一遍。既然那一下是必然的，不如替他点。
         * 这也和「生成结果默认明文」是同一条：这串东西此刻的唯一用途就是被看清楚。
         */
        /**
         * 选中的包名当场写进草稿，**不等「完成」**。
         * 页面本来就不自动保存（决策(59)），真正的落点是底下那个还没按的保存按钮，
         * 而返回时那道「放弃修改」拦截会兜住误操作。
         */
        if (showAppPicker) {
            AppPickerSheet(
                added = remember(draft.domainsText) { DomainTargets.appKeys(draft.domainsText) },
                onToggle = { pkg ->
                    draft = draft.copy(domainsText = DomainTargets.toggle(draft.domainsText, pkg))
                },
                onDismiss = { showAppPicker = false },
            )
        }

        if (showGenerator) {
            GeneratorSheet(
                onDismiss = { showGenerator = false },
                replacesExisting = draft.password.isNotEmpty(),
                onUse = { pw ->
                    draft = draft.copy(password = pw)
                    revealed = true
                    showGenerator = false
                },
            )
        }
    }

    /**
     * 「放弃修改？」
     *
     * ── 主按钮是「继续编辑」，次按钮才是「放弃修改」──
     *
     * 决策⑮那条在这里第二次被兑现（第一次是弱口令确认框）：
     * 危险的那个动作放在次按钮上，而且**取消手势（点弹窗外面、按返回）
     * 只能意味着「什么都别做」**——也就是停在编辑页，改动一个字不丢。
     * 反过来写的话，用户点一下弹窗外面的空白，刚改的东西就无声无息没了。
     *
     * `danger = false`：这里的确认按钮是安全的那一个，不该染成红的，
     * 也不该禁掉「点外面关掉」——点外面正是最安全的那条路。
     *
     * detail 那一行只列**改了哪几个字段**，一个字段值都不带（见
     * [EntryForm.changedSummary]）：弹窗是独立 window（决策⑭），
     * 而「密码将从 …… 改回 ……」这种话会把两个密码同时摆上去。
     */
    if (confirmDiscard) {
        VaultDialog(
            title = "放弃修改？",
            message = "这一条会保持原来的样子，刚才改的内容不会保留。",
            detail = "未保存：${EntryForm.changedSummary(original, draft)}",
            confirmText = "继续编辑",
            onConfirm = { confirmDiscard = false },
            secondaryText = "放弃修改",
            onSecondary = {
                confirmDiscard = false
                onBack()
            },
            onDismissRequest = { confirmDiscard = false },
            danger = false,
        )
    }
}
