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

package cn.localvault.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.LocalClipboard
import cn.localvault.app.ui.LocalQuickUnlock
import cn.localvault.app.ui.LocalRepository
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.DangerButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.ExplainNote
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.LabeledField
import cn.localvault.app.ui.components.SecurePasswordField
import cn.localvault.app.ui.components.SettingRow
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultDialog
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.components.explain
import cn.localvault.app.ui.components.rememberSecureTextState
import cn.localvault.app.ui.list.VaultIndex
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType

/**
 * 删除保险库（`Route.SETTINGS_DELETE`）。
 *
 * ── 这一页的排版顺序是有讲究的 ──
 *
 * 事实 → 备份状况 → 会没掉什么 → 覆写擦除的实话 → 主密码 → 按钮。
 *
 * 按钮在最下面，而上面那四块**都不是劝阻**，是四条他现在需要、
 * 而且只有我们能提供的信息（他自己数不出条目数，也不知道我们不做覆写擦除）。
 * 不写「你确定吗？三思！」那类话：真心想删的人会被这种话激怒，
 * 误点进来的人则早在第一屏事实那里就退出去了。
 *
 * **顺序 v4 一步没动，动的只有长度。** 这一页和恢复页不一样：那一页把说明
 * 收短是为了让主按钮浮上第一屏，而这一页的主按钮**本来就该在读完之后才够得着**。
 * 所以这里做的只是把三段解释（导出的备份怎么样、为什么不做覆写擦除、
 * 为什么指纹不算数）各收成一句 + 一个链接，让「事实 → 备份状况 → 会没掉什么」
 * 这三块真正要被读的东西挤进第一屏，而不是被三段实现细节垫在下面。
 * 一个字都没删，全在弹窗里。
 *
 * ── 没有「已删除」成功页 ──
 *
 * 删完之后 `session.onVaultDeleted()` 把相位翻回 `NoVault`，
 * 整棵已解锁子树连同这一页一起被换成欢迎页（决策⑪）。
 * 那一屏就是回执——用户看到的是一个空白的、要他新建保险库的应用，
 * 没有比这更清楚的「删掉了」。再插一句「删除成功」的提示，
 * 只会让他在看到欢迎页之前多点一次。
 */
@Composable
fun DeleteVaultScreen(onBack: () -> Unit) {
    val session = LocalSession.current
    val repo = LocalRepository.current
    val quick = LocalQuickUnlock.current
    val clipboard = LocalClipboard.current
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 锁定（或删除成功）那一瞬间导航相位会换掉整棵子树，但这一帧可能还会被画一次。
    // 与其让它去读一个刚被清空的会话，不如什么都不画。同设置页 / 改主密码页。
    val data = (state as? VaultSession.State.Unlocked)?.data ?: return
    val meta = data.meta

    val controller = remember(repo, session) {
        DeleteVaultController(
            repo = repo,
            session = session,
            remnants = QuickUnlockRemnants(quick, clipboard),
            scope = scope,
        )
    }
    DisposableEffect(controller) { onDispose { controller.cancel() } }

    val password = rememberSecureTextState()
    var askConfirm by remember { mutableStateOf(false) }

    val facts = remember(data.entries.size, meta.createdAt) {
        DeleteVaultModel.facts(
            DeleteVaultModel.Inventory(
                entries = data.entries.size,
                // 每次重组都去 stat 一次文件不值当，但这个 key 已经覆盖了
                // 「条目变了」这个唯一会让文件大小明显变化的原因。
                fileBytes = repo.fileSizeBytes(),
                createdAt = meta.createdAt,
            )
        )
    }

    val stand = remember(data.entries, meta.lastBackupAt) {
        DeleteVaultModel.backupStand(
            lastBackupAt = meta.lastBackupAt,
            changedSince = VaultIndex.changedSince(data.entries, meta.lastBackupAt),
        )
    }
    val notice = remember(stand, data.entries, meta.lastBackupAt) {
        DeleteVaultModel.backupNotice(
            stand = stand,
            changedSince = VaultIndex.changedSince(data.entries, meta.lastBackupAt),
            lastBackupAt = meta.lastBackupAt,
        )
    }

    val canSubmit = DeleteVaultModel.canSubmit(password.length, controller.busy)

    // 删除进行中不允许返回。这中间退出去，用户既不知道删成了没有，
    // 也没有任何页面会告诉他——而这一页恰恰是最不该留下这种悬念的一页。
    BackHandler(enabled = controller.busy) { }

    VaultScreen(
        title = "删除保险库",
        onBack = if (controller.busy) null else onBack,
        seal = { DefaultSeal() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            /* ── 一、这次会删掉的东西，只报数字 ── */

            Eyebrow("要删掉的是这个")
            VaultCard(Modifier.fillMaxWidth()) {
                Column {
                    facts.forEachIndexed { i, f ->
                        if (i > 0) HairLine()
                        SettingRow(title = f.label, value = f.value, valueMono = true)
                    }
                }
            }

            /* ── 二、备份现在是什么状况 ── */

            Banner(
                text = notice.text,
                tone = if (notice.severe) BannerTone.Danger else BannerTone.Info,
            )

            /* ── 三、跟着一起没的，和不会跟着没的 ── */

            Eyebrow("删掉之后")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeleteVaultModel.collateral(
                    pinEnrolled = quick.isPinEnrolled,
                    biometricEnrolled = quick.isBiometricEnrolled,
                ).forEach { Bullet(it) }
            }

            ExplainNote(
                DeleteVaultModel.EXPORTS_NOTE_SHORT,
                style = VaultType.Sub,
                color = VaultColors.Dim,
                detailTitle = "导出的备份会怎么样",
                detail = explain(DeleteVaultModel.EXPORTS_NOTE),
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            /* ── 四、覆写擦除这件事的实话（决策⑧）── */

            /*
             * 这一段是这一页最长的一块，也是**最不需要在按下按钮之前读完**的一块：
             * 它解释的是我们的实现，不是他要做的判断。收成一句 + 一个链接（v4）。
             *
             * 但那一句必须留在外面。决策⑧ 要的是「不做多次覆写」这件事被主动说出来，
             * 而不是被收进一个多数人不会点开的弹窗——那和不说没有区别。
             */
            ExplainNote(
                DeleteVaultModel.ERASURE_NOTE_SHORT,
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                detailTitle = DeleteVaultModel.ERASURE_DETAIL_TITLE,
                detail = explain(DeleteVaultModel.ERASURE_NOTE),
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            /* ── 五、主密码 ── */

            LabeledField("主密码") {
                SecurePasswordField(
                    state = password,
                    placeholder = "输入主密码以确认",
                    // 不自动聚焦、不自动弹键盘：这一页进来第一件事是读上面那些字，
                    // 不是打字。同编辑页（决策(64)），只是这里的理由更重。
                    autoFocus = false,
                    imeAction = ImeAction.Done,
                    onImeAction = { if (canSubmit) askConfirm = true },
                    isError = false,
                )
            }

            if (password.length == 0) {
                ExplainNote(
                    DeleteVaultModel.PASSWORD_HINT_SHORT,
                    style = VaultType.Sub,
                    color = VaultColors.Dimmer,
                    detailTitle = "为什么指纹在这一步不算数",
                    detail = explain(DeleteVaultModel.PASSWORD_HINT),
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }

            (controller.step as? DeleteVaultController.Step.Failed)?.let { s ->
                Banner(
                    text = DeleteVaultModel.failureMessage(s.reason),
                    tone = BannerTone.Danger,
                    actionText = "知道了",
                    onAction = { controller.dismissError() },
                )
            }

            DangerButton(
                text = "删除这个保险库",
                onClick = { if (canSubmit) askConfirm = true },
                enabled = canSubmit,
            )

            ProgressNote(controller.step)

            Text(
                DeleteVaultModel.BLOCKED_HINT,
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (askConfirm) {
        /*
         * 最后那一道。`danger = true` 做两件事：主按钮画成红的，
         * 以及**关掉「点弹窗外面即取消」**（见 VaultDialog 的 dismissOnClickOutside）。
         * 后者在这一页反过来读才是重点——危险动作永远在主按钮上，
         * 取消手势永远只意味着「什么都别做」（决策⑮）。
         */
        VaultDialog(
            title = DeleteVaultModel.CONFIRM_TITLE,
            message = DeleteVaultModel.confirmMessage(data.entries.size, stand),
            danger = true,
            confirmText = DeleteVaultModel.CONFIRM_YES,
            onConfirm = {
                askConfirm = false
                controller.submit(password.copyChars())
            },
            secondaryText = DeleteVaultModel.CONFIRM_NO,
            onSecondary = { askConfirm = false },
            onDismissRequest = { askConfirm = false },
        )
    }
}

/* ─────────────────────────── 小零件 ─────────────────────────── */

@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        VaultIcon(
            Glyph.Close,
            tint = VaultColors.Rust,
            size = 13.dp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(text, style = VaultType.Sub, color = VaultColors.Dim)
    }
}

/**
 * 进度说明。
 *
 * 三句话对应三个阶段。第一句最要紧：核对主密码要跑一遍 Argon2id，
 * 低配机上一两秒，而这一刻用户盯着的是一个正在删他全部密码的程序——
 * 不吭声的话，那一两秒会被读成「卡死了」。
 *
 * `Done` 不给文案：那一帧之后整棵子树就换成欢迎页了，
 * 一句只出现十几毫秒的「已删除」除了闪一下没有别的作用。
 */
@Composable
private fun ProgressNote(step: DeleteVaultController.Step) {
    val text = when (step) {
        is DeleteVaultController.Step.Verifying -> "正在核对主密码…"
        is DeleteVaultController.Step.Purging -> "正在清除指纹 / PIN 的绑定与剪贴板…"
        is DeleteVaultController.Step.Deleting -> "正在删除保险库文件…"
        else -> null
    } ?: return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = VaultColors.Rust,
            strokeWidth = 1.6.dp,
        )
        Text(text, style = VaultType.Sub, color = VaultColors.Dim)
    }
}
