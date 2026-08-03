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

package cn.localvault.app.ui.add

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultDialog
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.edit.EntryForm
import cn.localvault.app.ui.apps.AppPickerSheet
import cn.localvault.app.ui.edit.DomainTargets
import cn.localvault.app.ui.edit.EntryFormFields
import cn.localvault.app.ui.generate.GeneratorSheet
import cn.localvault.app.ui.list.VaultIndex
import cn.localvault.app.ui.nav.LocalDraftHandoff
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 新增一条：三步。规则全在 [AddFlow] 里，这一页只管画和导航。
 *
 * ── 草稿只有一个，步骤只是「现在画哪几个框」──
 *
 * 三步共用同一个 [EntryForm.Draft]，不是三个各自独立的状态。
 * 分开存的话，「第二步填的密码要不要在第一步返回时清掉」这种问题
 * 会在每一次前后跳转上重新问一遍，而任何一次答错的表现都是**用户填的东西没了**。
 * 一个草稿从头活到尾，前后跳转就只是换一组可见的框而已。
 *
 * ── 存之前一次盘都不落 ──
 *
 * 和编辑页那条（决策(59)）一样，在这里更硬：新增流中途落盘意味着库里会出现
 * 一条只有名称的半成品，而它会立刻出现在列表上、被搜到、并且让
 * 「有 N 条改动还没进备份」（决策㉞）涨一格。用户中途退出后，
 * 那条半成品还得他自己去删。
 */
@Composable
fun AddEntryScreen(
    onExit: () -> Unit,
    onSaved: (String?) -> Unit,
) {
    val session = LocalSession.current
    val handoff = LocalDraftHandoff.current
    val state by session.state.collectAsState()

    val data = (state as? VaultSession.State.Unlocked)?.data ?: return

    /**
     * 名称初值从内存交接槽里取，**取一次就没了**（[cn.localvault.app.ui.nav.DraftHandoff.takeName]）。
     *
     * 来源是搜索页那条「新增「招商」」（决策㊶）。用 `remember` 不带 key，
     * 是要它在这一页的整个生命周期里只跑一次：转屏重建时再取一次的话，
     * 会把用户已经改过的名称重新盖回搜索词。
     *
     * 取出来的字**单独留一份**（`seededName`），不是只塞进草稿就算完。
     * 它是第一步该把光标放在哪儿的唯一依据（[AddFlow.autoFocus]），
     * 而那个依据必须是「进这一页时的事实」、一进来就定死：
     * 若改成现算 `draft.name.isNotBlank()`，用户在名称栏敲下第一个字，
     * 这个值就翻面，光标当场被搬到账号框去——名字还没打完人就被踢走了。
     */
    val seededName = remember { handoff.takeName().orEmpty() }
    var draft by remember { mutableStateOf(EntryForm.blank(seededName)) }
    var step by remember { mutableStateOf(AddFlow.steps.first()) }

    /**
     * 新增流里的密码**默认摊开**，编辑页默认遮着。
     *
     * 这不是不一致，是两种处境：编辑页里那串东西**已经是某个账户的密码**，
     * 一进页面就摊在屏幕上，等于用户每次去改个网址都要把密码亮一遍。
     * 而这一步的密码要么是他此刻正在打的（打字时被遮住是错字的头号来源，
     * 而这个错字他要到下次登录不上时才会发现），要么是刚生成出来还没被任何账户
     * 用上的一串字符（决策(73) 说的就是它）。
     * 这一屏唯一的用途就是把这串东西弄对，遮住它只妨碍这件事。
     */
    var revealed by remember { mutableStateOf(true) }

    var showGenerator by remember { mutableStateOf(false) }

    /** 应用选择器。同上——覆盖层，不是路由（见 [cn.localvault.app.ui.apps.AppPickerSheet]）。 */
    var showAppPicker by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val categories = remember(data.entries) { VaultIndex.categories(data.entries) }

    /**
     * 重复提醒。跟着名称、账号、网址走——那三样是判重的全部依据，
     * 用户打备注的时候不该每敲一个字就重算一遍全库。
     */
    val duplicate = remember(data.entries, draft.name, draft.username, draft.domainsText) {
        AddFlow.findDuplicate(data.entries, draft)
    }

    val canAdvance = AddFlow.canAdvance(step, draft)
    val blockReason = AddFlow.blockReason(step, draft)

    /** 退出这条流程。草稿一个字都没有就直接走，不拿一道确认框招待误触。 */
    val leave: () -> Unit = {
        if (AddFlow.isEmpty(draft)) {
            handoff.clear()
            onExit()
        } else {
            confirmDiscard = true
        }
    }

    /**
     * 返回键 = **上一步**，只有第一步的返回才是退出。
     *
     * 这是三步流最容易做错的一处：把返回一律接成退出，用户在第三步想回去
     * 改一个错字，按一下返回，整条草稿没了。而返回键在安卓上就是「退一步」，
     * 这里的一步恰好就是一步。
     */
    BackHandler {
        val back = AddFlow.prev(step)
        if (back != null) step = back else leave()
    }

    Box(Modifier.fillMaxSize()) {
        VaultScreen(
            title = "新增",
            onBack = { AddFlow.prev(step)?.let { step = it } ?: leave() },
            seal = { DefaultSeal() },
        ) {
            Column(Modifier.fillMaxSize().imePadding()) {

                StepBar(
                    current = step,
                    draft = draft,
                    onJump = { target -> step = target },
                )

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

                    Text(
                        AddFlow.hint(step),
                        style = VaultType.Sub,
                        color = VaultColors.Dim,
                    )

                    /**
                     * 重复提醒只是提醒，**没有按钮**（见 [AddFlow.duplicateIsBlocking]）。
                     * 它出现在第一步和第三步（名称/账号变了、网址变了），
                     * 第二步不出现——那一屏在讲密码，跳一条不相干的黄条只会打断他。
                     */
                    if (duplicate != null && step != AddFlow.Step.Password) {
                        Banner(
                            text = AddFlow.duplicateMessage(duplicate),
                            tone = BannerTone.Warn,
                        )
                    }

                    if (step == AddFlow.Step.Filing) {
                        ReviewCard(draft = draft, onEdit = { step = AddFlow.Step.Basics })
                    }

                    EntryFormFields(
                        draft = draft,
                        onDraftChange = { draft = it },
                        categories = categories,
                        visible = AddFlow.fields(step),
                        autoFocus = AddFlow.autoFocus(step, seededName.isNotEmpty()),
                        passwordRevealed = revealed,
                        onTogglePasswordReveal = { revealed = !revealed },
                        onGenerate = { showGenerator = true },
                        onPickApp = { showAppPicker = true },
                    )

                    /**
                     * 密码为空时，把生成器的入口**摊开写成一句话**。
                     *
                     * 字段块右边那个循环箭头一直都在，但它是给「已经知道有这么个东西」
                     * 的人准备的。一个第一次走到这一步的用户面对的是一个空框，
                     * 而他此刻最该做的事恰恰是让机器给他生成一串——
                     * 这条路不该藏在一个 20dp 的图标里。
                     *
                     * 填了之后这个按钮就收起来：一屏上长期并存两个同样的入口，
                     * 会让人怀疑它们是不是不一样的东西。
                     */
                    if (step == AddFlow.Step.Password && draft.password.isEmpty()) {
                        GhostButton(
                            text = "生成一个强密码",
                            tint = VaultColors.Brass,
                            onClick = { showGenerator = true },
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                }

                HairLine()

                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    BrassButton(
                        text = AddFlow.advanceText(step),
                        enabled = canAdvance,
                        onClick = {
                            val nextStep = AddFlow.next(step)
                            if (nextStep != null) {
                                step = nextStep
                                return@BrassButton
                            }
                            // 最后一步：写盘。
                            val before = data.entries
                            val r = session.addEntry(EntryForm.newEntry(draft))
                            val after = r.getOrNull()
                            if (after != null) {
                                handoff.clear()
                                onSaved(AddFlow.newestId(before, after.entries))
                            } else {
                                /**
                                 * 失败就**停在原地**，草稿一个字不动。
                                 * 这条流程走到这儿用户已经填了三屏，
                                 * 把他退回列表等于让他从头再来一遍，
                                 * 而失败的原因（空间满了、闪存出错）多半重试一次就好了。
                                 */
                                failure = "这一条没能写进保险库，刚填的内容还在，可以再试一次。"
                            }
                        },
                    )

                    Text(
                        text = blockReason ?: when (step) {
                            AddFlow.Step.Filing -> "保存后立刻写进保险库。"
                            else -> "留着之后再补也可以。"
                        },
                        style = VaultType.MonoSmall,
                        color = if (blockReason != null) VaultColors.Rust else VaultColors.Dimmer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

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
     * 「放弃新增？」
     *
     * 决策⑮第三次被兑现（前两次是弱口令确认框和编辑页的放弃修改）：
     * 主按钮是安全的那一个（继续填写），危险动作在次按钮上，
     * 而**取消手势（点弹窗外面、按返回）只能意味着「什么都别做」**——
     * 也就是停在这一页，一个字不丢。
     *
     * detail 那行只列**填了哪几个字段**，一个值都不带（见 [AddFlow.filledSummary]）：
     * 弹窗是独立 window（决策⑭），而这一步的草稿里很可能正躺着一串刚生成的密码。
     */
    if (confirmDiscard) {
        VaultDialog(
            title = "放弃新增？",
            message = "这一条还没有存进保险库，刚填的内容不会保留。",
            detail = "已填写：${AddFlow.filledSummary(draft)}",
            confirmText = "继续填写",
            onConfirm = { confirmDiscard = false },
            secondaryText = "放弃",
            onSecondary = {
                confirmDiscard = false
                handoff.clear()
                onExit()
            },
            onDismissRequest = { confirmDiscard = false },
            danger = false,
        )
    }
}

/* ─────────────────────── 进度条 ─────────────────────── */

/**
 * 三段进度 + 当前这一步叫什么。
 *
 * ── 点得动，但只能往回点（以及往前点到已经满足的那一步）──
 *
 * 往回改个错字不该有任何门槛。往前则要求沿途每一步都已经满足
 * （[AddFlow.canJumpTo]），否则点一下进度条就绕过了「名称必填」，
 * 而那条规矩是列表和搜索的地基。
 *
 * 不显示「跳过」：第二步和第三步的主按钮本来就点得动（那两步没有必填项），
 * 再摆一个「跳过」等于给同一个动作两个名字，用户还得先分辨它们有什么不同。
 */
@Composable
private fun StepBar(
    current: AddFlow.Step,
    draft: EntryForm.Draft,
    onJump: (AddFlow.Step) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AddFlow.steps.forEach { s ->
                val reached = AddFlow.index(s) <= AddFlow.index(current)
                val jumpable = AddFlow.canJumpTo(s, current, draft)
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(VaultShape.TileSm)
                        .background(if (reached) VaultColors.Brass else VaultColors.Slab2)
                        .then(
                            if (jumpable && s != current) {
                                Modifier.clickable { onJump(s) }
                            } else Modifier
                        )
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(AddFlow.ordinal(current), style = VaultType.MonoSmall, color = VaultColors.Brass)
            Text(AddFlow.title(current), style = VaultType.H2, color = VaultColors.Text)
        }
    }
}

/* ─────────────────────── 最后一步的回顾卡 ─────────────────────── */

/**
 * 第三步顶上摆一张「前两步填了什么」。
 *
 * 保存是这条流程上唯一一个不可撤销的动作（存完只能进详情页再改一遍），
 * 而此刻前两步填的东西已经不在屏幕上了。让他在按之前看一眼，
 * 比按完之后进详情页发现名字打错了再退回来改一遍便宜。
 *
 * 密码那一行是固定 12 个圆点，**没有那只眼睛**——理由见
 * [AddFlow.review] 上面那段。想核对就点「改」回第一步、再往后一步，
 * 那儿的密码本来就是摊开的。
 */
@Composable
private fun ReviewCard(draft: EntryForm.Draft, onEdit: () -> Unit) {
    VaultCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Eyebrow("前两步", modifier = Modifier.weight(1f))
                Text(
                    "改",
                    style = VaultType.Sub,
                    color = VaultColors.Brass,
                    modifier = Modifier
                        .clip(VaultShape.TileSm)
                        .clickable(onClick = onEdit)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            AddFlow.review(draft).forEach { line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        line.label,
                        style = VaultType.MonoSmall,
                        color = VaultColors.Dimmer,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(
                        line.value,
                        style = VaultType.Sub,
                        color = if (line.dim) VaultColors.Dimmer else VaultColors.Text,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
