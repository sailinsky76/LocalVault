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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.apps.AppIcon
import cn.localvault.app.ui.apps.InstalledAppCatalog
import cn.localvault.app.ui.apps.rememberAppCatalog
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.IconSlot
import cn.localvault.app.ui.components.LabeledField
import cn.localvault.app.ui.components.PlainField
import cn.localvault.app.ui.components.StrengthMeter
import cn.localvault.app.ui.components.TextLink
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType
import cn.localvault.app.ui.util.PasswordStrength

/** 六个字段全画。编辑页用的就是这个。 */
val ALL_ENTRY_FIELDS: Set<EntryForm.Field> = EntryForm.Field.values().toSet()

/**
 * 条目表单的字段块。**编辑页和新增流的三步共用这一个。**
 *
 * 它自己不管保存、不管导航、不管「改了没存」那道拦截——
 * 那些在不同场景下的答案不一样（新增流的最后一步按「保存」，编辑页也按「保存」，
 * 但前者存完去详情页、后者存完回详情页；新增流中途退出要清 `DraftHandoff`，
 * 编辑页不用）。这里只负责把字段画对，并且**在每一个场合都画得一模一样**。
 *
 * ── [visible]：只画其中几个 ──
 *
 * 新增流分三步（[cn.localvault.app.ui.add.AddFlow.fields]），
 * 每一步只画自己那几个框。做成参数而不是让新增流另写一套字段，
 * 是决策(55) 的字面兑现：修剪、切行、去重那几条规则只能有一份，
 * 两边各写一份的话，同一个库里会出现两种数据。
 *
 * ── 密码字段用 [PlainField]，不用 `SecurePasswordField` ──
 *
 * 决策⑩里已经写清楚了：主密码走 EditText 互操作是因为它**从头到尾不该变成 String**，
 * 而 [cn.localvault.app.core.vault.VaultEntry.password] 本来就是 String，
 * 它躺在内存里的那个 `VaultData` 里，在这里绕开 Compose 的输入框只是自我安慰。
 * 真正保护它的是整库加密、`FLAG_SECURE` 和自动锁定。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryFormFields(
    draft: EntryForm.Draft,
    onDraftChange: (EntryForm.Draft) -> Unit,
    categories: List<String>,
    passwordRevealed: Boolean,
    onTogglePasswordReveal: () -> Unit,
    modifier: Modifier = Modifier,
    /** 画哪几个字段。默认全画——编辑页就是这么用的，签名对它一个字都没变。 */
    visible: Set<EntryForm.Field> = ALL_ENTRY_FIELDS,
    /**
     * 进来把光标放在哪个字段上，并弹出键盘。null = 不聚焦（编辑页的默认行为，决策(62)）。
     *
     * 不在这里判断「该不该聚焦」——那是场景的事，由
     * [cn.localvault.app.ui.add.AddFlow.autoFocus] 说了算，它可单测。
     *
     * **契约：这个值只能在「换了一屏」的时候变。**
     * 下面那个 `LaunchedEffect` 认的是值本身：它一变，光标就搬家。
     * 所以调用方传进来的必须是进这一屏时算好的一次性决定，
     * 绝不能是任何随 [draft] 实时变化的东西——否则用户打字打到一半，
     * 光标会自己跳到别的框里去，而且看不出是谁干的。
     */
    autoFocus: EntryForm.Field? = null,
    /**
     * 「生成一个」。**为 null 时界面上那个按钮根本不画。**
     *
     * M3-5a 起编辑页传的是真回调（打开
     * [cn.localvault.app.ui.generate.GeneratorSheet] 那个覆盖层），
     * M3-5b 的新增流第二步传的是同一个东西。
     * 槽位仍然可空，是因为将来可能有只读或者受限的场合复用这套字段；
     * 那种场合下不画按钮，比画一个点了没反应的按钮好——
     * 和「动态验证码字段留在数据模型里、界面上一行都不画」（决策(54)）是同一条。
     */
    onGenerate: (() -> Unit)? = null,
    /**
     * 「选择应用」。**为 null 时那个按钮根本不画**，用户仍然可以添加网址、
     * 或者进手动编辑自己填包名。
     *
     * 槽位可空的理由和 [onGenerate] 一模一样：选择器是一个覆盖层，
     * 必须由**页面**（同一棵 composition）来挂——做成路由的话，
     * 选中的包名只能靠 `savedStateHandle` 回传，而那是一个会被写进
     * `savedInstanceState` 的 Bundle（[cn.localvault.app.ui.nav.DraftHandoff]
     * 那篇注释要堵的洞）。字段块自己挂不了它，所以只能是一个回调。
     */
    onPickApp: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }

    /**
     * `runCatching`：`requestFocus` 在节点还没挂上去时会抛。
     * 聚焦失败的正确处置是「用户自己点一下那个框」，
     * 而不是让整个新增流在第一屏崩掉。
     */
    LaunchedEffect(autoFocus) {
        if (autoFocus != null) runCatching { focus.requestFocus() }
    }

    fun focusMod(field: EntryForm.Field): Modifier =
        if (autoFocus == field) Modifier.focusRequester(focus) else Modifier

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {

        if (EntryForm.Field.Name in visible) {
            LabeledField("名称") {
                PlainField(
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                    placeholder = "微信 / 招商银行 / GitHub",
                    imeAction = ImeAction.Next,
                    fieldModifier = focusMod(EntryForm.Field.Name),
                )
            }
        }

        if (EntryForm.Field.Username in visible) {
            LabeledField("账号") {
                PlainField(
                    value = draft.username,
                    onValueChange = { onDraftChange(draft.copy(username = it)) },
                    placeholder = "用户名 / 手机号 / 邮箱",
                    mono = true,
                    imeAction = ImeAction.Next,
                    fieldModifier = focusMod(EntryForm.Field.Username),
                )
            }
        }

        /**
         * 密码。右边一只眼睛切换，**没有倒计时**。
         *
         * 详情页那条规矩（决策㊼）在输入框里更硬：显示明文的唯一用途是照着核对，
         * 而在一个输入框里，内容被倒计时抽回成圆点的同时光标还留在原处——
         * 用户会以为自己刚才敲的东西没进去，然后重敲一遍。
         *
         * 默认遮不遮由调用方给（[passwordRevealed]）：编辑页默认遮着（那是一条
         * 已经在用的密码），新增流第二步默认摊开（见 `AddEntryScreen` 里那段）。
         */
        if (EntryForm.Field.Password in visible) {
            LabeledField("密码") {
                PlainField(
                    value = draft.password,
                    onValueChange = { onDraftChange(draft.copy(password = it)) },
                    placeholder = "留空也可以",
                    mono = true,
                    masked = !passwordRevealed,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                    fieldModifier = focusMod(EntryForm.Field.Password),
                    trailing = {
                        IconSlot(
                            if (passwordRevealed) Glyph.EyeOff else Glyph.Eye,
                            contentDescription = if (passwordRevealed) "隐藏密码" else "显示密码",
                            onClick = onTogglePasswordReveal,
                        )
                        if (onGenerate != null) {
                            IconSlot(
                                Glyph.Refresh,
                                contentDescription = "生成一个密码",
                                tint = VaultColors.Brass,
                                onClick = onGenerate,
                            )
                        }
                    },
                )
            }

            /**
             * 强度条只在**有内容的时候**出现。
             *
             * 空密码是合法的（[EntryForm.nameOk] 那段说明），
             * 而对着一个空框显示一条红色的「弱」，是在为一个用户没做的选择责备他。
             */
            if (draft.password.isNotEmpty()) {
                val strength = remember(draft.password) {
                    PasswordStrength.evaluate(draft.password.toCharArray())
                }
                StrengthMeter(strength)
            }
        }

        /**
         * 网址 / 应用：**一份可增可删的清单**，不再是一个让用户自己打字的多行框。
         *
         * ── 为什么把多行框换掉 ──
         *
         * 这一栏收两种东西：网址和安卓包名。网址用户打得出来，包名打不出来——
         * 「微信的包名是什么」这个问题，绝大多数人不知道从哪儿查，
         * 而**打错了是完全静默的**：[cn.localvault.app.ui.autofill.DomainMatch.judge]
         * 里那句「包名只认逐字相等」意味着少一个字母就是 `Verdict.None`，
         * 于是他在微信里唤起填充，什么都没出现，屏幕上也没有任何地方解释为什么。
         * 一个只有填对了才有反应、填错了毫无提示的输入框，是这一栏原来的样子。
         *
         * 所以包名改成**从这台手机上挑**（[cn.localvault.app.ui.apps.AppPickerSheet]），
         * 挑出来的一定是逐字准确的；网址仍然自己填，因为它本来就打得出来，
         * 而且最常见的动作是从地址栏整条粘过来。
         *
         * ── 但底下的规则一条都没改 ──
         *
         * 存进去的还是 [EntryForm.Draft.domainsText] 那一块多行文本，
         * 切行、去重、「只丢不改写」还是 [EntryForm.domainLines] 那一套，
         * 保险库文件格式一个字节都没动。清单的增删走
         * [DomainTargets]，它转手调的就是同一个函数——决策(55) 那句
         * 「规则只能有一份」在这里第三次被兑现。
         *
         * ── 手动编辑那条后路留着 ──
         *
         * 从别处粘一整块多行文本进来、或者要填一个**没装在这台手机上**的包名
         * （给旧手机上的应用留一条记录），清单式界面都做不到。
         * 那个入口退回原来那个多行框，一个字没变。
         */
        if (EntryForm.Field.Domains in visible) {
            DomainTargetsField(
                text = draft.domainsText,
                onTextChange = { onDraftChange(draft.copy(domainsText = it)) },
                onPickApp = onPickApp,
                fieldModifier = focusMod(EntryForm.Field.Domains),
            )
        }

        /**
         * 分类：一个输入框 + 已有分类的快捷片。
         *
         * 不做下拉选择器——那意味着还得再设计一套「管理分类」的界面
         * （改名、删掉一个还有条目在用的分类、空分类要不要留着）。
         * 快捷片直接复用 [cn.localvault.app.ui.list.VaultIndex.categories]，
         * 点一下就是**把分类名填进输入框**，同时不挡着他随手写一个新的。
         * 和搜索页的分类快捷键（决策㊸）是同一个做法：填进去，不另开一套状态。
         */
        if (EntryForm.Field.Category in visible) {
            LabeledField("分类") {
                PlainField(
                    value = draft.category,
                    onValueChange = { onDraftChange(draft.copy(category = it)) },
                    placeholder = "银行 / 工作 / 购物",
                    imeAction = ImeAction.Next,
                    fieldModifier = focusMod(EntryForm.Field.Category),
                )
                val others = remember(categories, draft.category) {
                    categories.filter { it != draft.category.trim() }
                }
                if (others.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        others.forEach { c ->
                            CategoryChip(c) { onDraftChange(draft.copy(category = c)) }
                        }
                    }
                }
            }
        }

        /**
         * 备注在这里是**摊开的**，不像详情页那样默认藏起来（决策㊾）。
         *
         * 详情页藏它，是因为用户点进一条多半只想看账号，
         * 不该顺手把身份证号亮在地铁上。而表单是他自己主动来填 / 来改的：
         * 一个默认藏起来的输入框会让「我到底改没改」变得看不出来，
         * 也会让他在看不见原文的情况下往里追加内容。
         */
        if (EntryForm.Field.Notes in visible) {
            LabeledField("备注") {
                PlainField(
                    value = draft.notes,
                    onValueChange = { onDraftChange(draft.copy(notes = it)) },
                    placeholder = "密保问题、开户行、客服电话……",
                    singleLine = false,
                    minHeight = 96,
                    imeAction = ImeAction.Default,
                    fieldModifier = focusMod(EntryForm.Field.Notes),
                )
                Text(
                    "备注不参与搜索。",
                    style = VaultType.MonoSmall,
                    color = VaultColors.Dimmer,
                    modifier = Modifier.padding(top = 7.dp, start = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = VaultType.Sub,
        color = VaultColors.Dim,
        modifier = Modifier
            .clip(VaultShape.Field)
            .background(VaultColors.Slab2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/* ══════════════════════════ 网址 / 应用 ══════════════════════════ */

/**
 * 目标清单本体。两种形态：**清单**（默认）和**手动编辑**（那条后路）。
 *
 * 形态用的是 `remember` 而不是 `rememberSaveable`：转屏会退回清单形态。
 * 认下这个代价，和搜索页转屏丢关键词（决策㊲）、生成器转屏换一串密码
 * 是同一笔账——`rememberSaveable` 意味着往 `savedInstanceState` 里写东西，
 * 而这一栏里躺着的是用户上过哪些站、装了哪些应用。
 * 正在编辑的**内容**不会丢，它在草稿里，草稿由页面持有。
 */
@Composable
private fun DomainTargetsField(
    text: String,
    onTextChange: (String) -> Unit,
    onPickApp: (() -> Unit)?,
    fieldModifier: Modifier,
) {
    val catalog = rememberAppCatalog()
    var manual by remember { mutableStateOf(false) }

    /** null = 现在没有在添加网址。空串 = 框已经出来了但还没打字。 */
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    val urlFocus = remember { FocusRequester() }

    LabeledField("网址 / 应用") {

        if (manual) {
            /**
             * 原来那个多行框，**一个参数都没改**。
             *
             * 它留着不是为了兼容旧习惯，是因为有两件事清单式界面做不到：
             * 从别处粘一整块进来，以及填一个没装在这台手机上的包名。
             */
            PlainField(
                value = text,
                onValueChange = onTextChange,
                placeholder = "example.com\ncom.tencent.mm",
                mono = true,
                singleLine = false,
                minHeight = 76,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Default,
                fieldModifier = fieldModifier,
            )
            Text(
                "一行一个，安卓包名也放这里。空行和重复的会在保存时丢掉。",
                style = VaultType.MonoSmall,
                color = VaultColors.Dimmer,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp),
            )
            TextLink("回到清单", onClick = { manual = false })
            return@LabeledField
        }

        val targets = remember(text) { DomainTargets.parse(text) }

        if (targets.isEmpty()) {
            Text(
                DomainTargets.EMPTY_HINT,
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 2.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEachIndexed { i, target ->
                    TargetRow(
                        target = target,
                        catalog = catalog,
                        // 按下标删，删的就是他点的那一张卡（[DomainTargets.removeAt]）。
                        onRemove = { onTextChange(DomainTargets.removeAt(text, i)) },
                    )
                }
            }
        }

        /**
         * 添加网址的那一行。**没有二次确认，回车即加入**——
         * 加错了清单上就是一张多出来的卡，按一下叉就没了。
         */
        val pending = pendingUrl
        if (pending != null) {
            val commit: () -> Unit = {
                if (pending.isNotBlank()) onTextChange(DomainTargets.add(text, pending))
                pendingUrl = null
            }
            // 框一出来就聚焦。用户刚按下「添加网址」，他此刻要做的下一个动作
            // 确实就是打字——同 [cn.localvault.app.ui.add.AddFlow.autoFocus] 那段。
            LaunchedEffect(Unit) { runCatching { urlFocus.requestFocus() } }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlainField(
                    value = pending,
                    onValueChange = { pendingUrl = it },
                    placeholder = "example.com",
                    modifier = Modifier.weight(1f),
                    mono = true,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    onImeAction = commit,
                    fieldModifier = Modifier.focusRequester(urlFocus),
                )
                IconSlot(
                    Glyph.Check,
                    contentDescription = "加入清单",
                    tint = VaultColors.Brass,
                    enabled = pending.isNotBlank(),
                    onClick = commit,
                )
            }
        }

        /**
         * 两个按钮并排，**都是次要按钮**。
         *
         * 不把「选择应用」做成黄铜色：一屏只该有一个黄铜按钮（见 `BrassButton` 的注释），
         * 而那一个在页面底部，是「保存」。这一栏本来就是可以整个跳过的。
         */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (onPickApp != null) {
                GhostButton("选择应用", onClick = onPickApp, modifier = Modifier.weight(1f))
            }
            GhostButton(
                "添加网址",
                onClick = { pendingUrl = "" },
                modifier = Modifier.weight(1f),
                enabled = pendingUrl == null,
            )
        }

        Text(
            DomainTargets.PICK_HINT,
            style = VaultType.MonoSmall,
            color = VaultColors.Dimmer,
            modifier = Modifier.padding(start = 2.dp),
        )

        TextLink("手动编辑", onClick = { manual = true }, color = VaultColors.Dimmer)
    }
}

/**
 * 清单上的一张卡。
 *
 * ── 应用行显示的是应用名，存进去的是包名 ──
 *
 * 主位那行字是**现查出来的**（[cn.localvault.app.ui.apps.InstalledAppCatalog.labelOf]），
 * 库里没有它。下面那行小字是真正被存下来的包名——两行都要有：
 * 只显示应用名，用户就不知道到底存了什么；只显示包名，就回到了改这一栏之前的样子。
 *
 * 查名字是一次同步的跨进程调用，放在组合里是因为一条条目的清单通常只有一两行，
 * 而且目录里有缓存。图标那边不同（要栅格化、名单能滚），所以走的是后台线程。
 *
 * ── 没装的包名照样显示，并且明说 ──
 *
 * 从别的手机导入的库、或者刚卸载掉的应用，都会落到这一档。
 * 不画成错误状态：这条数据没有任何问题，只是这台手机上碰巧没有那个应用。
 */
@Composable
private fun TargetRow(
    target: DomainTargets.Target,
    catalog: InstalledAppCatalog,
    onRemove: () -> Unit,
) {
    val isApp = target.kind == DomainTargets.Kind.App
    val pkg = target.raw.trim()
    val label = remember(pkg, isApp) { if (isApp) catalog.labelOf(pkg) else null }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VaultShape.Row)
            .background(VaultColors.Slab)
            .padding(start = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isApp) {
            AppIcon(pkg, label, catalog, size = 34.dp)
        } else {
            Box(
                Modifier.size(34.dp).clip(VaultShape.TileSm).background(VaultColors.Slab2),
                contentAlignment = Alignment.Center,
            ) {
                VaultIcon(Glyph.Globe, tint = VaultColors.Dim, size = 19.dp)
            }
        }

        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(
                text = if (isApp) (label ?: pkg) else target.raw,
                style = if (isApp && label != null) VaultType.Body else VaultType.MonoSmall,
                color = VaultColors.Text,
                maxLines = 2,
            )
            if (isApp) {
                Text(
                    text = if (label != null) pkg else "这台手机上没装这个应用",
                    style = VaultType.MonoSmall,
                    color = VaultColors.Dimmer,
                    maxLines = 1,
                )
            }
        }

        IconSlot(
            Glyph.Close,
            contentDescription = "从清单里移除",
            tint = VaultColors.Dim,
            onClick = onRemove,
        )
    }
}
