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

package cn.localvault.app.ui.onboarding

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.LabeledField
import cn.localvault.app.ui.components.MatchHint
import cn.localvault.app.ui.components.SecurePasswordField
import cn.localvault.app.ui.components.StrengthMeter
import cn.localvault.app.ui.components.VaultDialog
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.components.rememberSecureTextState
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType
import cn.localvault.app.ui.util.PasswordStrength

/**
 * 硬下限。低于这个长度直接不给建库，弹窗也绕不过去。
 *
 * 值本身在 [PasswordStrength.MASTER_MIN_LENGTH]——改主密码那一页用的是同一个数，
 * 两处必须一致（理由写在那个常量上面）。
 */
private const val MIN_MASTER_LEN = PasswordStrength.MASTER_MIN_LENGTH

/**
 * 设置主密码。
 *
 * ── 为什么「输入」和「确认」在同一屏，而不是分两页 ──
 *
 * 分两页意味着第一页的密码必须**活过一次页面切换**：第一页被销毁时
 * [cn.localvault.app.ui.components.SecureTextState] 会把缓冲区擦掉，
 * 所以要么阻止它擦，要么先复制一份存到某个跨页面的持有者里。
 * 两条路都是在给主密码多开一个副本、多加一段谁也说不清什么时候结束的生命周期。
 *
 * 同屏两个框就没有这个问题：两份缓冲区同生共死，用户按返回键离开时一起清零，
 * 比对走 `contentEquals`（逐字符异或，不早退、不产生 String）。
 * M3-1 把 `contentEquals` 的参数设计成另一个 `SecureTextState` 而不是 CharArray，
 * 本来就是奔着同屏比对去的。
 *
 * 代价是这一屏信息密度偏高。用小标题分区来化解，不拆页。
 */
@Composable
fun CreateMasterScreen(
    controller: CreateVaultController,
    onBack: () -> Unit,
) {
    val pw = rememberSecureTextState()
    val confirm = rememberSecureTextState()
    var askWeakConfirm by remember { mutableStateOf(false) }

    val strength = remember(pw.revision) { pw.read { PasswordStrength.evaluate(it) } }
    val matched = remember(pw.revision, confirm.revision) { pw.contentEquals(confirm) }

    val tooShort = pw.length in 1 until MIN_MASTER_LEN
    val canSubmit = pw.length >= MIN_MASTER_LEN && matched && !controller.busy

    fun submit() {
        if (!canSubmit) return
        if (strength.bits < PasswordStrength.MASTER_MIN_BITS) {
            askWeakConfirm = true
        } else {
            controller.create(pw.copyChars())
        }
    }

    // 建库中途不允许返回：写盘已经开始，退出去只会留下一个半成品状态。
    // 校准阶段其实退得，但为了不让用户在两个阶段之间猜哪个能退，统一封掉。
    BackHandler(enabled = controller.busy) { }

    VaultScreen(
        title = "设置主密码",
        onBack = if (controller.busy) null else onBack,
        seal = { DefaultSeal() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 输入法弹出时把表单顶上去。少了这一行，「再输一次」那个框
                // 在多数机型上正好被键盘盖住，用户只能盲输确认密码。
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            Text(
                "这一个密码保护其余全部密码。建议用一句只有你想得起来的话，" +
                    "比如一句歌词加两个数字——长度比复杂度更管用。",
                style = VaultType.Body,
                color = VaultColors.Dim,
            )

            LabeledField("主密码") {
                SecurePasswordField(
                    state = pw,
                    placeholder = "至少 $MIN_MASTER_LEN 位",
                    autoFocus = true,
                    imeAction = ImeAction.Next,
                    isError = tooShort,
                )
            }

            if (pw.length > 0) {
                StrengthMeter(strength)
            }
            if (tooShort) {
                Text(
                    "还差 ${MIN_MASTER_LEN - pw.length} 位才到下限",
                    style = VaultType.Sub,
                    color = VaultColors.Rust,
                )
            }

            LabeledField("再输一次") {
                SecurePasswordField(
                    state = confirm,
                    placeholder = "确认主密码",
                    imeAction = ImeAction.Done,
                    onImeAction = { submit() },
                    isError = confirm.length > 0 && !matched,
                )
            }

            if (confirm.length > 0) {
                MatchHint(matched)
            }

            // 这条横幅不做成弹窗，因为弹窗会被下意识点掉。
            // 它必须在用户按下「创建」的那一刻还在屏幕上。
            Banner(
                text = "主密码没有找回通道。忘了它，这个库里的数据就永久没了——" +
                    "这是「不上传任何数据」的代价，不是可以补上的功能。",
                tone = BannerTone.Warn,
            )

            when (val s = controller.step) {
                is CreateVaultController.Step.Failed -> Banner(
                    text = s.message,
                    tone = BannerTone.Danger,
                    actionText = "知道了",
                    onAction = { controller.dismissError() },
                )
                else -> Unit
            }

            BrassButton(
                text = "创建保险库",
                onClick = { submit() },
                enabled = canSubmit,
                busy = controller.busy,
            )

            ProgressNote(controller.step)

            Spacer(Modifier.height(24.dp))
        }
    }

    if (askWeakConfirm) {
        VaultDialog(
            title = "这个主密码偏弱",
            message = "保险库文件一旦被拷走，挡住离线爆破的就只剩这个密码本身。" +
                "现在换一个更长的，比出事之后再补要省事得多。",
            detail = strength.hint,
            // 主按钮（黄铜、显眼）是「回去改」，继续用弱口令放在次按钮。
            // 用户一路点最显眼那个按钮的结果应该是更安全，不是更省事。
            confirmText = "改一个更强的",
            onConfirm = { askWeakConfirm = false },
            secondaryText = "我知道风险，就用它",
            onSecondary = {
                askWeakConfirm = false
                controller.create(pw.copyChars())
            },
            // 按返回键或点弹窗外面 = 什么都不做，绝不能等于「就用它」
            onDismissRequest = { askWeakConfirm = false },
        )
    }
}

/**
 * 建库过程的阶段文案。
 *
 * 校准那一步可能要一两秒，而且是在用户点完按钮之后才开始跑的。
 * 如果只转个圈不说话，用户会以为卡死了。这里如实说明正在干什么——
 * 顺带也让「这个 App 在认真对待你的主密码」这件事被看见。
 */
@Composable
private fun ProgressNote(step: CreateVaultController.Step) {
    val text = when (step) {
        CreateVaultController.Step.Calibrating -> "正在测算本机能承受的加密强度…"
        CreateVaultController.Step.Sealing -> "正在派生主密钥并封装保险库…"
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
