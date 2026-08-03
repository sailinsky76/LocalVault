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
import androidx.compose.runtime.LaunchedEffect
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
import cn.localvault.app.ui.LocalCryptoInfo
import cn.localvault.app.ui.LocalQuickUnlock
import cn.localvault.app.ui.LocalRepository
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.ExplainBanner
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.LabeledField
import cn.localvault.app.ui.components.MatchHint
import cn.localvault.app.ui.components.SecurePasswordField
import cn.localvault.app.ui.components.SecureTextState
import cn.localvault.app.ui.components.StrengthMeter
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultDialog
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.components.explain
import cn.localvault.app.ui.components.rememberSecureTextState
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType
import cn.localvault.app.ui.util.Fmt
import cn.localvault.app.ui.util.PasswordStrength

/**
 * 修改主密码（`Route.SETTINGS_MASTER`）。
 *
 * ── 三个框在同一屏 ──
 *
 * 决策⑯ 在这一页的第二次兑现，而且这次的理由更硬：
 * 分页的话要跨页面活着的不是一个口令，是**两个**（旧的等着做凭据、新的等着被确认）。
 * 同屏三个框则同生共死——用户按返回键离开，三份缓冲区一起清零。
 *
 * 代价是这一屏比建库那一屏还挤。处理办法一样：小标题分区、`imePadding` 顶起来，
 * 不拆页。
 *
 * ── 改完之后不退回表单 ──
 *
 * 成功之后整屏换成一张「改完了 + 现在去重新备份」的卡片，
 * 而不是弹个提示再回到设置页。因为这一页真正的收尾动作不是「改密码」，
 * 是**把手上那份只认旧口令的备份换掉**（见 `ChangeMasterModel` 顶上那段）。
 * 用户此刻正在想的问题恰好就是「那我的备份怎么办」，
 * 这是唯一一个把答案和入口一起递到他手边的时机——回到设置页，这件事就散了。
 */
@Composable
fun ChangeMasterScreen(
    onBack: () -> Unit,
    onBackup: () -> Unit,
) {
    val session = LocalSession.current
    val repo = LocalRepository.current
    val quick = LocalQuickUnlock.current
    val crypto = LocalCryptoInfo.current
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 锁定那一瞬间导航相位会换掉整棵子树，但这一帧可能还会被画一次。
    // 同设置页 / PIN 设置页的处理：与其去读一个刚被清空的会话，不如什么都不画。
    val data = (state as? VaultSession.State.Unlocked)?.data ?: return

    val controller = remember(repo, session) {
        ChangeMasterController(
            repo = repo,
            session = session,
            scope = scope,
            argon2Available = crypto.argon2Available,
        )
    }
    DisposableEffect(controller) { onDispose { controller.cancel() } }

    val old = rememberSecureTextState()
    val fresh = rememberSecureTextState()
    val confirm = rememberSecureTextState()
    var askWeakConfirm by remember { mutableStateOf(false) }

    val strength = remember(fresh.revision) { fresh.read { PasswordStrength.evaluate(it) } }
    val matched = remember(fresh.revision, confirm.revision) { fresh.contentEquals(confirm) }
    val sameAsOld = remember(fresh.revision, old.revision) { fresh.contentEquals(old) }

    val blocker = ChangeMasterModel.blocker(
        oldLength = old.length,
        newLength = fresh.length,
        matched = matched,
        sameAsOld = sameAsOld,
    )
    val tooShort = fresh.length in 1 until ChangeMasterModel.MIN_LENGTH
    val canSubmit = blocker == null && !controller.busy

    fun submit() {
        if (!canSubmit) return
        if (strength.bits < PasswordStrength.MASTER_MIN_BITS) {
            askWeakConfirm = true
        } else {
            controller.submit(old.copyChars(), fresh.copyChars())
        }
    }

    /*
     * 改完立刻把三个框抹掉。
     *
     * `rememberSecureTextState` 已经保证了「离开这一页时清零」，这里是提前一步：
     * 成功之后这一屏还会停在用户眼前（那张备份卡片），期间三份口令没有任何用处，
     * 却还躺在三个 EditText 的缓冲区里等着被 GC。
     */
    LaunchedEffect(controller.done) {
        if (controller.done) { old.wipe(); fresh.wipe(); confirm.wipe() }
    }

    // 派生和写盘期间不允许返回：这中间退出去，用户既不知道改成了没有，
    // 也没有任何页面会告诉他。理由同建库页。
    BackHandler(enabled = controller.busy) { }

    VaultScreen(
        title = "修改主密码",
        onBack = if (controller.busy) null else onBack,
        seal = { DefaultSeal() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            if (controller.done) {
                DoneBlock(
                    lastBackupAt = data.meta.lastBackupAt,
                    kdfLabel = controller.chosenParams?.let { Fmt.kdfLabel(it) },
                    onBackup = onBackup,
                    onFinish = onBack,
                )
            } else {
                FormBlock(
                    controller = controller,
                    old = old,
                    fresh = fresh,
                    confirm = confirm,
                    strength = strength,
                    matched = matched,
                    tooShort = tooShort,
                    blocker = blocker,
                    canSubmit = canSubmit,
                    quickNote = ChangeMasterModel.quickUnlockNote(
                        pinEnrolled = quick.isPinEnrolled,
                        biometricEnrolled = quick.isBiometricEnrolled,
                    ),
                    onSubmit = { submit() },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (askWeakConfirm) {
        VaultDialog(
            title = "这个新主密码偏弱",
            // 和建库页那个弹窗是同一套说法，因为这次说的确实是同一件事：
            // 保险库文件被拷走之后，挡住离线爆破的就只剩这个口令本身。
            // （对比决策(106)：PIN 不适用这套风险模型，所以那边另写一份。）
            message = "保险库文件一旦被拷走，挡住离线爆破的就只剩这个密码本身。" +
                "既然已经在换了，不如直接换一个更长的。",
            detail = strength.hint,
            confirmText = "改一个更强的",
            onConfirm = { askWeakConfirm = false },
            secondaryText = "我知道风险，就用它",
            onSecondary = {
                askWeakConfirm = false
                controller.submit(old.copyChars(), fresh.copyChars())
            },
            // 按返回键或点弹窗外面 = 什么都不做（决策⑮）
            onDismissRequest = { askWeakConfirm = false },
        )
    }
}

/* ─────────────────────────── 表单 ─────────────────────────── */

@Composable
private fun FormBlock(
    controller: ChangeMasterController,
    old: SecureTextState,
    fresh: SecureTextState,
    confirm: SecureTextState,
    strength: PasswordStrength.Result,
    matched: Boolean,
    tooShort: Boolean,
    blocker: ChangeMasterModel.Blocker?,
    canSubmit: Boolean,
    quickNote: String?,
    onSubmit: () -> Unit,
) {
    LabeledField("当前主密码") {
        SecurePasswordField(
            state = old,
            placeholder = "现在用的那一个",
            autoFocus = true,
            imeAction = ImeAction.Next,
            // 输错的红框留给失败横幅去说。这个框在提交之前没有任何办法
            // 知道里面填的对不对，先把它标红等于凭空指责用户。
            isError = false,
        )
    }

    LabeledField("新主密码") {
        SecurePasswordField(
            state = fresh,
            placeholder = "至少 ${ChangeMasterModel.MIN_LENGTH} 位",
            imeAction = ImeAction.Next,
            isError = tooShort,
        )
    }

    if (fresh.length > 0) {
        StrengthMeter(strength)
    }
    if (tooShort) {
        Text(
            "还差 ${ChangeMasterModel.MIN_LENGTH - fresh.length} 位才到下限",
            style = VaultType.Sub,
            color = VaultColors.Rust,
        )
    }

    LabeledField("再输一次新主密码") {
        SecurePasswordField(
            state = confirm,
            placeholder = "确认新主密码",
            imeAction = ImeAction.Done,
            onImeAction = onSubmit,
            isError = confirm.length > 0 && !matched,
        )
    }

    if (confirm.length > 0) {
        MatchHint(matched)
    }

    // 只有「和旧的是同一个」那一条有话说，其余三条挡路原因在屏幕上
    // 各自已经有表达了（见 ChangeMasterModel.blockerMessage）。
    blocker?.let { ChangeMasterModel.blockerMessage(it) }?.let {
        Banner(it, tone = BannerTone.Warn)
    }

    /*
     * 横幅照留，只收短（v4）。这一条不做成「一行入口」——收进去等于把
     * 这一页唯一一句「有代价」藏了，而它要拦的正是一路点主按钮的那个人。
     * 完整那句挂在横幅右边的「详情」上。
     */
    ExplainBanner(
        text = ChangeMasterModel.BEFORE_WARNING_SHORT,
        tone = BannerTone.Warn,
        detailTitle = ChangeMasterModel.BEFORE_WARNING_TITLE,
        detail = explain(ChangeMasterModel.BEFORE_WARNING),
    )

    if (quickNote != null) {
        Text(
            quickNote,
            style = VaultType.Sub,
            color = VaultColors.Dimmer,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }

    when (val s = controller.step) {
        is ChangeMasterController.Step.Failed -> Banner(
            text = ChangeMasterModel.failureMessage(s.reason),
            tone = BannerTone.Danger,
            actionText = "知道了",
            onAction = { controller.dismissError() },
        )
        else -> Unit
    }

    BrassButton(
        text = "修改主密码",
        onClick = onSubmit,
        enabled = canSubmit,
        busy = controller.busy,
    )

    ProgressNote(controller.step)
}

/* ─────────────────────────── 改完了 ─────────────────────────── */

/**
 * 成功之后那一屏。
 *
 * 主按钮是「现在重新导出备份」而不是「完成」——用户一路点最显眼那个按钮的结果
 * 应该是更安全，不是更省事（同建库页那个弱口令弹窗的按钮排布）。
 * 「以后再说」照样给，而且不加吓唬人的修饰：那是一个合理的选择，
 * 用户可能正在地铁上、手边没有能放备份的地方。
 */
@Composable
private fun DoneBlock(
    lastBackupAt: Long,
    kdfLabel: String?,
    onBackup: () -> Unit,
    onFinish: () -> Unit,
) {
    val result = ChangeMasterModel.success(lastBackupAt)

    VaultCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("主密码已更换", style = VaultType.H2, color = VaultColors.Text)
            Text(result.text, style = VaultType.Body, color = VaultColors.Dim)
            /*
             * 把这次校准落在哪一档如实写出来。
             *
             * 它是这一页一个不太显眼的副作用：改密码顺带按当前设备重新测算了
             * KDF 参数（见控制器里那段注释），而顶部封条马上就会跟着变。
             * 封条无声无息地变了个数字，比变之前更让人不安——所以在这儿交代一句。
             */
            if (kdfLabel != null) {
                Text(
                    "本次按这台设备重新测算了加密强度：$kdfLabel",
                    style = VaultType.MonoSmall,
                    color = VaultColors.Dimmer,
                )
            }
        }
    }

    if (result.needsBackup) {
        BrassButton("现在重新导出备份", onClick = onBackup)
        GhostButton("以后再说", onClick = onFinish)
    } else {
        BrassButton("完成", onClick = onFinish)
    }
}

/* ─────────────────────────── 阶段文案 ─────────────────────────── */

/**
 * 四个阶段各说各的。
 *
 * 「正在核对当前主密码」这一句尤其要有：它要跑一次完整的 KDF 派生，
 * 低配机上一两秒，而用户此刻刚按下按钮、还不知道自己有没有打错。
 * 转个圈不说话的话，那一两秒里他会以为程序卡住了。
 */
@Composable
private fun ProgressNote(step: ChangeMasterController.Step) {
    val text = when (step) {
        ChangeMasterController.Step.Verifying -> "正在核对当前主密码…"
        ChangeMasterController.Step.Calibrating -> "正在测算本机能承受的加密强度…"
        ChangeMasterController.Step.Sealing -> "正在用新主密码重新封装保险库…"
        else -> null
    } ?: return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            color = VaultColors.Brass,
            strokeWidth = 1.5.dp,
        )
        Text(text, style = VaultType.MonoSmall, color = VaultColors.Dim)
    }
}
