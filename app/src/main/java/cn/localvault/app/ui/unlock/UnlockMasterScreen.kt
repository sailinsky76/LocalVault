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

package cn.localvault.app.ui.unlock

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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.SealTone
import cn.localvault.app.ui.components.SecurePasswordField
import cn.localvault.app.ui.components.TextLink
import cn.localvault.app.ui.components.VaultDialog
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.components.rememberSecureTextState
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType
import cn.localvault.app.ui.util.Fmt
import kotlinx.coroutines.delay

/**
 * 主密码解锁页。
 *
 * ── 这一屏刻意什么都不显示 ──
 *
 * 没有库里的条目数、没有上次修改时间、没有用户给保险库起的名字。
 * 锁定状态下这些都属于「不该泄露的元信息」：捡到手机的人不需要打开保险库，
 * 光看到「37 条 · 最近更新 2 分钟前」就已经知道这台设备值得带走。
 * 屏幕上唯一的信息是加密参数（封条），而那个反正写在文件头里，谁都能读。
 *
 * ── 退避期间输入框仍然可用 ──
 *
 * 只禁用提交按钮，不禁用输入。用户在等待的这十几秒里通常正在回忆密码，
 * 让他能一边想一边敲、等倒计时归零直接按下去，比锁死输入框友好得多，
 * 而且不损失任何安全性——退避拦的是**提交尝试**的速率，不是打字。
 */
@Composable
fun UnlockMasterScreen(
    controller: UnlockController,
    autoLocked: Boolean,
    /**
     * 「忘记主密码了？」那个弹窗上次按钮的去处（`Route.RESET`）。
     *
     * **刻意没有默认值**，同 `SettingsScreen.onSecurity` / `onDelete`：
     * 这是一个新长出来的、能点得动的出口，如果它的跳转参数可以被省略，
     * 某天有人复制一份调用忘了传，那个按钮就会变成点了没反应的死行，
     * 而编译器一声不吭。这一页尤其不能这样——按到它的人已经走投无路了。
     */
    onReset: () -> Unit,
    onUseQuickUnlock: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val pw = rememberSecureTextState()
    var askForgot by remember { mutableStateOf(false) }

    // 自动锁定的提示只在用户还没动手时占着位置：他一开始输入就说明已经看到了。
    // 用「碰过没有」而不是「当前长度」当条件——否则用户全部删掉重输时，
    // 这条横幅会突然又冒出来，把输入框往下顶一截。
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(pw.revision) { if (pw.revision > 0) touched = true }
    val showAutoLocked = autoLocked && !touched

    // 倒计时的心跳。控制器里没有任何计时器，剩余时间一律由这里按挂钟重算，
    // 于是进程被冻结、手机重启之后回来，显示的秒数依然是对的。
    LaunchedEffect(controller) {
        while (true) {
            controller.refreshLock()
            delay(500)
        }
    }

    fun submit() {
        if (!controller.canAttempt || pw.isEmpty) return
        controller.unlockWithMaster(pw.copyChars())
    }

    // 派生途中不许返回：这时候退出去只会留下一个转着圈的空壳。
    BackHandler(enabled = controller.busy) { }

    VaultScreen(
        title = null,
        onBack = if (controller.busy) null else onBack,
        // 退避期间封条转红。这是全工程唯一需要覆盖 tone 的场合，
        // 见 DefaultSeal 的注释——其余页面一律不许自己编封条的语气。
        seal = { DefaultSeal(tone = if (controller.isLockedOut) SealTone.Alert else null) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))

            VaultIcon(Glyph.Lock, tint = VaultColors.Brass, size = 34.dp)
            Text("保险库已锁定", style = VaultType.H1, color = VaultColors.Text)
            Text(
                "输入主密码打开。只在这台设备上验证。",
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(4.dp))

            if (showAutoLocked) {
                Banner(
                    text = "上次因长时间未操作已自动锁定。",
                    tone = BannerTone.Info,
                )
            }

            if (controller.quickUnlockJustDisabled) {
                // 这条不是「提示」，是「交代」：用户刚刚看着 PIN 入口消失。
                Banner(
                    text = "连续多次失败，PIN 与指纹已关闭，数据一条没动。解锁后可在设置里重新开启。",
                    tone = BannerTone.Warn,
                    actionText = "知道了",
                    onAction = { controller.acknowledgeQuickUnlockDisabled() },
                )
            }

            SecurePasswordField(
                state = pw,
                placeholder = "主密码",
                autoFocus = true,
                imeAction = ImeAction.Go,
                onImeAction = { submit() },
                isError = controller.step is UnlockController.Step.Failed,
            )

            when (val s = controller.step) {
                is UnlockController.Step.Failed ->
                    // 用横幅而不是弹窗：弹窗要多点一次才能回到输入框，
                    // 而「输错密码」是个高频、低严重度的事件，不值得打断。
                    Banner(
                        text = s.message,
                        tone = BannerTone.Danger,
                        actionText = "关闭",
                        onAction = { controller.dismissError() },
                    )
                else -> Unit
            }

            if (controller.isLockedOut) {
                CooldownNote(controller.lockRemainingMillis)
            }

            BrassButton(
                text = if (controller.isLockedOut)
                    "等待 ${Fmt.countdown(controller.lockRemainingMillis)}"
                else "解锁",
                onClick = { submit() },
                enabled = controller.canAttempt && !pw.isEmpty,
                busy = controller.busy,
            )

            if (onUseQuickUnlock != null) {
                GhostButton(
                    text = "改用 PIN 或指纹",
                    onClick = onUseQuickUnlock,
                    enabled = !controller.busy,
                    tint = VaultColors.Dim,
                )
            }

            TextLink("忘记主密码了？", onClick = { askForgot = true })

            Spacer(Modifier.height(24.dp))
        }
    }

    if (askForgot) {
        // ── 这个弹窗的作用是把坏消息说清楚，不是安慰 ──
        // 「主密码没有找回通道」是「不上传任何数据」的直接后果。
        // 如果这里含糊其辞（「请联系客服」「请稍后重试」），用户会一直等一个
        // 永远不会来的救援，而不是趁早去找那份他其实存过的备份文件。
        //
        // ── 主按钮是「我再想想」，清空重来在次按钮上 ──
        //
        // 这个弹窗的常客不是已经死心的人，是抱着侥幸点开看看的人，
        // 而对他来说最好的结果就是关掉弹窗再想一会儿。
        //
        // 把一个不可逆动作的入口放在次按钮上，只有在「次按钮和取消手势
        // 是两个回调」的前提下才是安全的——决策⑮ 早就把它们拆开了
        // （见 VaultDialog 的注释），所以点弹窗外面的空白、按返回键，
        // 走的都是 onDismissRequest，绝不会走到这儿来。
        //
        // 这里也**不**加 `danger = true`：那会把主按钮画成红的，
        // 而主按钮是「我再想想」。红色要跟着危险动作走，不是跟着弹窗走。
        VaultDialog(
            title = "主密码无法找回",
            message = "这个密码没有在任何地方留过副本——包括我们这边。" +
                "它是解开这个保险库的唯一钥匙，我们没有能力绕过它，也没有后门。\n\n" +
                "如果确实想不起来了，只有一条路：用你导出过的 .lvault 备份文件，" +
                "配上当时那份备份对应的主密码来恢复。\n\n" +
                // 这一段以前是没有的：那时这个弹窗说完「只有一条路」就没下文了，
                // 而对一个连备份也没有的人来说，那句话读完是个死胡同。
                ResetVaultModel.DIALOG_SECONDARY_NOTE,
            detail = "备份文件和主密码是两样东西。有文件没密码，同样打不开。",
            confirmText = "我再想想",
            onConfirm = { askForgot = false },
            secondaryText = ResetVaultModel.DIALOG_SECONDARY,
            onSecondary = {
                // 先收弹窗再跳。反过来的话，用户按返回回到这一页时，
                // 弹窗还原封不动地开着——他会以为自己刚才那一下没点上。
                askForgot = false
                onReset()
            },
            onDismissRequest = { askForgot = false },
        )
    }
}

/**
 * 冷却期说明。
 *
 * 特意写明「数据没有被删除」——很多密码类应用是「连错 N 次清库」的，
 * 用户见到红色倒计时时的第一反应往往是「我的东西是不是要没了」。
 * 我们的规则正相反（见 AttemptLimiter 的注释），那就得说出来。
 */
@Composable
private fun CooldownNote(remainingMillis: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VaultIcon(Glyph.Warn, tint = VaultColors.Rust, size = 19.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${Fmt.countdown(remainingMillis)} 后可以再试",
                style = VaultType.MonoBody,
                color = VaultColors.Rust,
            )
            Text(
                "等待只会变长，不会删除数据。",
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
            )
        }
    }
}
