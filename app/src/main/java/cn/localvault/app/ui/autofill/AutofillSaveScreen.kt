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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import cn.localvault.app.ui.components.PlainField
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 保存确认页。用户在别人的应用里提交了登录表单、按下了系统那个保存框之后，落到的就是这一页。
 *
 * **这个文件里没有一条规则。** 提案里有什么在 [AutofillSave] 里（75 条用例），
 * 此刻该摆哪一屏、每一句话怎么说在 [AutofillSaveFlow] 里（58 条用例中的一半），
 * 看哪几个框、收哪几格在 [SavePlan] / [SaveCapture] 里。这一页只把那些东西画出来。
 * 想在这儿加一个 `if` 之前先停一下——它十有八九该加在那几个文件之一里，那边测得到。
 *
 * ── 这一页上没有一处出现密码 ──
 *
 * [AutofillSave.Change.shown] 在 [AutofillSave.Field.Password] 那一条上**永远是 null**
 * （构造时就没被赋值），所以「确认页上不显示密码」在这一层是**类型保证**，
 * 不是一条要靠人记得的纪律（同 `AutofillPickScreen` / `AutofillRow` / `AutofillViews`）。
 * 屏幕上摆的是「密码会被换掉」这句话本身，不是两个密码——用户此刻要确认的是
 * **改的是哪一条、动的是哪几样**，不是核对一串字符。
 *
 * `proposal.result.password` 里确实躺着那一份明文（要交给 `VaultSession` 的就是它），
 * 但这一页从不读它。**别在这儿加一个「点一下看看存的是什么」的眼睛图标**：
 * 这一页浮在别人的应用上面，那个眼睛是这一整条链上唯一会把明文画到那种窗口里的东西。
 *
 * ── 一句都不折叠 ──
 *
 * [AutofillSaveFlow.allNotes] 给的每一句在这一页上逐句原样摆出来，
 * 一句都不许折叠、不许省略号、不许「展开更多」，理由同 `AutofillPickScreen` 文件头：
 * 那几句每一句都是三四行的完整句子，而它们正是这一按之所以被允许的全部依据。
 *
 * ── 名称栏只在新增那一档出现 ──
 *
 * 更新那一档**一个字都不碰名称**（决策(201)）：用户当初给那一条起的名字，
 * 是他在列表里认出它的唯一依据。这一页上摆一个能改名的输入框，
 * 等于给了一条「顺手把它改掉」的路，而他改完之后在自己的库里就找不到那条了。
 * 所以那一栏在 [AutofillSave.Mode.Update] 上根本不画出来，不是画成禁用。
 *
 * ── 关键词、草稿一个字都不落盘 ──
 *
 * [typedName] 用 `remember` 而不是 `rememberSaveable`，理由同 `AutofillPickScreen.query`：
 * `savedInstanceState` 是一条明文落盘的路径，而这一页的宿主浮在别人的应用上面，
 * 被系统回收重建的概率高得多。何况真被回收了这一页也回不来
 * （`SaveHandoff.take` 第二次拿不到，[AutofillSaveFlow.Leaving]），留着草稿没有意义。
 */
@Composable
fun AutofillSaveScreen(
    proposal: AutofillSave.Proposal,
    storedUnder: String,
    notes: List<String>,
    failure: String?,
    onSwitchTarget: (VaultEntry) -> Unit,
    onCommit: (finalName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typedName by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    // 摊开「换一条」之后按返回，退的是那一屏而不是整页——直接关掉的话，
    // 用户想回到确认单就得让对面那个应用再触发一次保存框，而那一次多半不会再来。
    BackHandler(enabled = picking) { picking = false }

    if (picking) {
        SwitchTargetBody(
            alternatives = proposal.alternatives,
            onPick = {
                onSwitchTarget(it)
                picking = false
            },
            onBack = { picking = false },
        )
        return
    }

    ConfirmBody(
        proposal = proposal,
        storedUnder = storedUnder,
        notes = notes,
        failure = failure,
        typedName = typedName,
        onNameChange = { typedName = it },
        onOpenSwitch = { picking = true },
        onCommit = {
            // 名称怎么定死在 AutofillSaveFlow.finalName 里（用户自己打的字一个都不洗，
            // 空了退回建议名）。这儿不补一句「名称不能为空」——他此刻站在一个
            // 正等着他登录的表单前面。
            onCommit(AutofillSaveFlow.finalName(typedName, proposal.result.name))
        },
        onDismiss = onDismiss,
    )
}

/* ─────────────────────────── 确认那一屏 ─────────────────────────── */

/**
 * 从上到下的顺序是有讲究的：
 *
 *   1. **这一条会记在谁名下**（[AutofillSave.storedUnder]）——决定的依据，摆最上面；
 *   2. 这一按到底是新长一条还是动一条已有的（[AutofillSaveFlow.headline]）——
 *      系统那个保存框只说「保存密码？」，这一行是用户在按下去之前，
 *      唯一一眼能分辨这两者的地方；
 *   3. **会改成这样**——逐条摆开，一条都不省；
 *   4. 必须先看的几句——逐句摆开；
 *   5. 名称（只有新增那一档）；
 *   6. 「换一条来改」；
 *   7. 不给按的理由 / 没存成的理由；
 *   8. 两个按钮。
 *
 * 3 排在 4 前面，和挑选页那一屏（警告在前）**反着来**，是有意的：
 * 那一页用户已经知道自己要哪一条（他刚点的），要提醒的是「交出去之后会怎样」；
 * 这一页他还不知道我们打算动哪几样——先把动作摆清楚，再说要小心的地方。
 */
@Composable
private fun ConfirmBody(
    proposal: AutofillSave.Proposal,
    storedUnder: String,
    notes: List<String>,
    failure: String?,
    typedName: String,
    onNameChange: (String) -> Unit,
    onOpenSwitch: () -> Unit,
    onCommit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()

    VaultScreen(title = AutofillSaveFlow.TITLE, onBack = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 会记在谁名下
            Banner(storedUnder, tone = BannerTone.Warn)

            // 2. 新长一条，还是动已有的那一条
            Text(
                AutofillSaveFlow.headline(proposal),
                style = VaultType.RowName,
                color = VaultColors.Text,
            )

            // 3. 会改成这样。**一条都不省**：这一段就是用户按下按钮时同意的全部内容
            if (proposal.changes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow(AutofillSaveFlow.CHANGES_HEADING)
                    VaultCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            proposal.changes.forEach { ChangeLine(it) }
                        }
                    }
                }
            }

            // 4. 必须先看的几句。一句都不折叠（见文件头）
            if (notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Eyebrow(AutofillSaveFlow.WARN_HEADING, color = VaultColors.Brass)
                    notes.forEach { Banner(it, tone = BannerTone.Warn) }
                }
            }

            // 5. 名称。**只有新增那一档有这一栏**，见文件头
            if (proposal.mode == AutofillSave.Mode.Create) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Eyebrow(AutofillSaveFlow.NAME_LABEL)
                    PlainField(
                        // 空着的时候把建议名摆在灰字上，而不是替他填进去：
                        // 填进去的话他一按就改成了「我们建议的那个名字」，
                        // 而那一串是从主机名或者应用名推出来的（AutofillSave.suggestedName）
                        value = typedName,
                        onValueChange = onNameChange,
                        placeholder = proposal.result.name,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        AutofillSaveFlow.NAME_HINT,
                        style = VaultType.Sub,
                        color = VaultColors.Dimmer,
                    )
                }
            }

            // 6. 换一条来改。**没有备选就不画这个入口**（决策(95)：一切正常时一句废话不说）
            if (proposal.alternatives.isNotEmpty()) {
                SwitchTargetRow(
                    count = proposal.alternatives.size,
                    onClick = onOpenSwitch,
                )
            }

            // 一按什么都不会变时，把话说出来（正常路径到不了这儿，
            // 用户自己换过一条之后可以到——见 AutofillSaveFlow.NOTHING_TO_CHANGE_NOTE）
            if (proposal.isNoop && proposal.blocked == null) {
                Banner(AutofillSaveFlow.NOTHING_TO_CHANGE_NOTE, tone = BannerTone.Info)
            }

            // 7. 不给按的那一条，理由原样摆出来（画成禁用而不是藏起来，决策(174)）
            proposal.blocked?.let { Banner(it, tone = BannerTone.Danger) }

            // 落盘失败。停在原地，提案一个字不动——同 AddEntryScreen 那一处
            failure?.let { Banner(it, tone = BannerTone.Danger) }

            Spacer(Modifier.height(2.dp))

            BrassButton(
                // 被 blocked 挡住时按钮上仍然写着那句「新增这一条 / 按上面改」，
                // 只是画成禁用：改写成「不能存」会让用户以为是这一条条目坏了，
                // 而真正的原因就摆在上面那条红条上（决策(174)）。
                text = if (proposal.isNoop && proposal.blocked == null) {
                    AutofillSaveFlow.NOTHING_TO_CHANGE
                } else {
                    AutofillSaveFlow.commitLabel(proposal.mode)
                },
                onClick = onCommit,
                enabled = proposal.canCommit,
            )
            GhostButton(AutofillSaveFlow.DISMISS, onClick = onDismiss, tint = VaultColors.Dim)
        }
    }
}

/**
 * 一条改动。
 *
 * 话由 [AutofillSave.changeNote] 给（那一句里已经说清是「补上」还是「换掉」），
 * 后面跟着 [AutofillSave.Change.shown]——**密码那一条它是 null**，于是那一行
 * 只有话没有值，这正是要的样子。
 */
@Composable
private fun ChangeLine(change: AutofillSave.Change) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        VaultIcon(
            if (change.how == AutofillSave.How.Replace) Glyph.Refresh else Glyph.Plus,
            tint = VaultColors.Brass,
            size = 17.dp,
            strokeWidth = 2f,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                AutofillSave.changeNote(change),
                style = VaultType.Sub,
                color = VaultColors.Text,
            )
            change.shown?.let {
                Text(
                    it,
                    style = VaultType.MonoSmall,
                    color = VaultColors.Dim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 「换一条来改」那一行入口。 */
@Composable
private fun SwitchTargetRow(count: Int, onClick: () -> Unit) {
    VaultCard(
        modifier = Modifier.fillMaxWidth(),
        background = VaultColors.Slab,
        shape = VaultShape.Row,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                AutofillSaveFlow.CHANGE_TARGET,
                style = VaultType.Body,
                color = VaultColors.Text,
                modifier = Modifier.weight(1f),
            )
            Text("$count", style = VaultType.Sub, color = VaultColors.Dim)
            VaultIcon(Glyph.Chevron, tint = VaultColors.Dim, size = 18.dp)
        }
    }
}

/* ─────────────────────────── 换一条那一屏 ─────────────────────────── */

/**
 * 换一条来改。
 *
 * 摆的是 [AutofillSave.Proposal.alternatives]——**里面只有够格自动填的那些**
 * （[AutofillSave.updatable] 已经筛过），所以这一屏上不会出现一条
 * 「点了之后必定被 [AutofillSave.Proposal.blocked] 挡住」的行。
 * 但换过去之后仍然可能被挡（账号对不上那一档），那两道护栏长在
 * [AutofillSave.proposeUpdate] 里，不在这一页上。
 *
 * 这一屏上同样一处都不出现密码：两行字都由 [AutofillSaveFlow] 给
 * （名称退回账号那一套和填充条上是同一份，见 [AutofillSaveFlow.entryLabel]）。
 */
@Composable
private fun SwitchTargetBody(
    alternatives: List<VaultEntry>,
    onPick: (VaultEntry) -> Unit,
    onBack: () -> Unit,
) {
    val scroll = rememberScrollState()

    VaultScreen(title = AutofillSaveFlow.CHANGE_TARGET, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (alternatives.isEmpty()) {
                // 擦肩而过的那种时序：他点开的那一瞬间，另一个窗口刚把最后一条删掉了
                EmptyState(
                    glyph = Glyph.Key,
                    title = "没有别的可换",
                    subtitle = AutofillSaveFlow.NO_ALTERNATIVES,
                )
                GhostButton(AutofillSaveFlow.ACKNOWLEDGE, onClick = onBack, tint = VaultColors.Dim)
                return@Column
            }

            Eyebrow(AutofillSaveFlow.CHANGE_TARGET_HEADING)
            alternatives.forEach { entry ->
                VaultCard(
                    modifier = Modifier.fillMaxWidth(),
                    background = VaultColors.Slab,
                    borderColor = VaultColors.LineSoft,
                    shape = VaultShape.Row,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(entry) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                AutofillSaveFlow.entryLabel(entry),
                                style = VaultType.RowName,
                                color = VaultColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                AutofillSaveFlow.entrySublabel(entry),
                                style = VaultType.Sub,
                                color = VaultColors.Dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        VaultIcon(Glyph.Chevron, tint = VaultColors.Dim, size = 18.dp)
                    }
                }
            }
        }
    }
}

/* ─────────────────────────── 说一句就走的那一屏 ─────────────────────────── */

/**
 * [AutofillSaveFlow.Refused] 那一屏。
 *
 * **不复用挑选页那个 [AutofillRefusalScreen]**，虽然两屏长得几乎一样。
 * 那一个的标题写死成 [AutofillPickFlow.TITLE]（「要填哪一条？」），
 * 摆在这一页上会变成一句和当前处境无关的话——用户刚按下的是「保存」。
 * 复用一个只差一个字符串的组件，代价是那个组件很快会长出第二个参数、
 * 然后是第三个，而两页真正共用的东西只有一个 [EmptyState]。
 *
 * 话原样摆出来，一个字都不改写，也不加「出错了」之类的帽子——
 * 这几档（已经存过了、认不出该改哪一条、这是保险库自己的界面）一个都不是故障。
 */
@Composable
fun AutofillSaveRefusalScreen(reason: String, onClose: () -> Unit) {
    VaultScreen(title = AutofillSaveFlow.TITLE, onBack = onClose) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            EmptyState(
                glyph = Glyph.Shield,
                title = "这一次不存",
                subtitle = reason,
            )
            GhostButton(AutofillSaveFlow.ACKNOWLEDGE, onClick = onClose, tint = VaultColors.Dim)
        }
    }
}

/* ─────────────────────────── 算提案的那一帧 ─────────────────────────── */

/**
 * [AutofillSaveFlow.Working] 那一档：库刚开，提案还没算出来。
 *
 * 这一屏看起来多余（[AutofillSave.outcome] 跑得很快），但它必须存在——
 * 没有它的话，「解锁完成」到「提案算好」之间会有一帧摆着空清单的确认页，
 * 而那一帧上的按钮是可以按下去的（[AutofillSaveFlow.Working] 那段注释）。
 *
 * 摆一屏空的、什么都点不动的东西，而不是一个转圈：那一帧短到看不见，
 * 而一个来得及被看见的转圈会让人以为这一步很慢。
 */
@Composable
fun AutofillSaveWorkingScreen() {
    VaultScreen(title = AutofillSaveFlow.TITLE) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            VaultIcon(Glyph.Lock, tint = VaultColors.Dimmer, size = 22.dp)
        }
    }
}
