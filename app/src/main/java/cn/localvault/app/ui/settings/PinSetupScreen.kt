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

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.localvault.app.core.keystore.KeystoreUnavailableException
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.LocalQuickUnlock
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.Keypad
import cn.localvault.app.ui.components.PinBuffer
import cn.localvault.app.ui.components.PinDots
import cn.localvault.app.ui.components.VaultDialog
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.settings.PinSetupModel.ConfirmResult
import cn.localvault.app.ui.settings.PinSetupModel.Mode
import cn.localvault.app.ui.settings.PinSetupModel.Step
import cn.localvault.app.ui.settings.PinSetupModel.Weakness
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays

/**
 * PIN 的设置 / 修改流（`Route.SETTINGS_PIN`）。
 *
 * 两步：输一遍 → 再输一遍。判断规则全在 [PinSetupModel] 里，这一页只负责
 * 画键盘、管两个缓冲区的生死、以及把最终那一串交给
 * [cn.localvault.app.core.keystore.QuickUnlock.enrollPin]。
 *
 * ── 屏幕上从头到尾没有「显示 PIN」的开关 ──
 *
 * 密码框都有一个眼睛，这一页刻意没有。理由是两边的场景不一样：
 * 长口令必须能核对（打错一个字符谁都看不出来），而 PIN 只有六位、
 * 而且**多半是站着、当着人设的**——那颗眼睛在这儿的作用主要是把六位数字
 * 亮给旁边的人看。真打错了还有第二步兜着，代价是重来一次。
 *
 * ── 中途退出不会留下半个 PIN ──
 *
 * `enrollPin` 是这一页唯一一次写 prefs，而它在最后一步、一次写完。
 * 在此之前无论用户怎么退，磁盘上什么都没变；修改 PIN 时也是**直接覆盖**
 * 而不是先删后写，所以写失败的话，旧 PIN 原样还在，仍然开得了门。
 */
@Composable
fun PinSetupScreen(
    mode: Mode,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val session = LocalSession.current
    val quick = LocalQuickUnlock.current
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 锁定那一瞬间导航相位会换掉整棵子树，但这一帧可能还会被画一次。
    // 同设置页 / 绑定页的处理：与其去读一个刚被清空的会话，不如什么都不画。
    if (state !is VaultSession.State.Unlocked) return

    /**
     * 两个缓冲区：[typing] 是正在按的这一串，[stash] 是第一步存下来的那一串。
     *
     * 都用 [PinBuffer]（CharArray），都**不做成 Compose 状态**——
     * 理由同 `QuickUnlockScreen`：不想让一个装着 PIN 的 char[]
     * 沾上任何「可能被框架持有、被快照系统复制」的关系。
     * 界面要用的只有「按了几位」，那是个 Int，让它单独走 state。
     */
    val typing = remember { PinBuffer(PinSetupModel.LENGTH) }
    val stash = remember { PinBuffer(PinSetupModel.LENGTH) }
    var filled by remember { mutableIntStateOf(0) }

    // 离开这一页时两份都抹掉。用户按返回、被自动锁定、进程被切走，都会走到这里。
    DisposableEffect(Unit) {
        onDispose {
            typing.wipe()
            stash.wipe()
        }
    }

    var step by remember { mutableStateOf(Step.Enter) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 弹窗里只带一个枚举，不带那六位数字（见 PinSetupModel.weaknessMessage）。
    var weak by remember { mutableStateOf<Weakness?>(null) }

    fun clearTyping() {
        typing.wipe()
        filled = 0
    }

    fun restart(message: String?) {
        clearTyping()
        stash.wipe()
        step = Step.Enter
        error = message
    }

    /** 把刚输完的这一串挪进 [stash]，然后进第二步。 */
    fun goConfirm() {
        stash.wipe()
        val copy = typing.copyChars()
        try {
            for (c in copy) stash.push(c)
        } finally {
            Arrays.fill(copy, '\u0000')
        }
        clearTyping()
        error = null
        step = Step.Confirm
    }

    fun enroll() {
        if (busy) return
        busy = true
        scope.launch {
            // 交给 enrollPin 的那一份由我们负责清零（那边的注释写明了）。
            val pin = stash.copyChars()
            val result = try {
                withContext(Dispatchers.Default) {
                    runCatching { session.withVaultKey { key -> quick.enrollPin(pin, key) } }
                }
            } finally {
                Arrays.fill(pin, '\u0000')
            }
            busy = false
            if (result.isSuccess) {
                clearTyping()
                stash.wipe()
                onDone()
            } else {
                /*
                 * ⚠ 上一版这里只有一句 `restart(PinSetupModel.ENROLL_FAILED)`：
                 *   `runCatching` 把异常整个吃掉了，屏幕上永远是同一句
                 *   「这次没能设置 PIN，可以再试一次」，日志里一个字都没有。
                 *
                 *   而这条路上最常见的失败恰恰是**再试也没用**的那一种
                 *   （这台设备的安全芯片不接受 `KeystoreKeys` 要求的规格），
                 *   于是用户被那句「可以再试一次」留在原地反复按十二下。
                 *
                 *   现在：原始异常一定进日志，屏幕上的话按真实原因分。
                 */
                val cause = result.exceptionOrNull()
                Log.w("PinSetup", "设置 PIN 失败", cause)
                /*
                 * 写了一半的包裹要清掉，理由同指纹绑定（决策(105)）：
                 * 留着它的表现是「开关显示已开启，但每次解锁都失败」，
                 * 而用户要到下次开门才会发现。
                 *
                 * 修改 PIN 的场合这一步会顺手把旧 PIN 也清掉——那是对的：
                 * `enrollPin` 已经动过 prefs 的话，旧包裹的那几个字段
                 * 就已经不成套了，留着它比清掉更危险。
                 */
                runCatching { quick.disablePin() }
                restart(
                    when {
                        cause is KeystoreUnavailableException -> PinSetupModel.ENROLL_FAILED_KEYSTORE
                        // withVaultKey 在库锁着时抛这个，SecureBytes 被清零时也一样。
                        // 只认这两种，不是所有 IllegalStateException——
                        // `UnsupportedKdfException` 也是它的子类，那个和锁定无关。
                        cause is IllegalStateException &&
                            (cause.message?.contains("未解锁") == true ||
                                cause.message?.contains("已被清零") == true)
                        -> PinSetupModel.ENROLL_FAILED_LOCKED
                        else -> PinSetupModel.ENROLL_FAILED
                    }
                )
            }
        }
    }

    fun submit() {
        if (busy || !PinSetupModel.canSubmit(filled)) return
        when (step) {
            Step.Enter -> {
                val copy = typing.copyChars()
                val w = try {
                    PinSetupModel.weakness(copy)
                } finally {
                    Arrays.fill(copy, '\u0000')
                }
                // 弱 PIN 只提醒，不拦截。用户点了次按钮就照常往下走。
                if (w != null) weak = w else goConfirm()
            }

            Step.Confirm -> {
                val a = stash.copyChars()
                val b = typing.copyChars()
                val r = try {
                    PinSetupModel.confirm(a, b)
                } finally {
                    Arrays.fill(a, '\u0000')
                    Arrays.fill(b, '\u0000')
                }
                if (r == ConfirmResult.Match) {
                    enroll()
                } else {
                    // 两份一起清，退回第一步 —— 见 PinSetupModel.MISMATCH_MESSAGE 上面那段：
                    // 只清第二份等于假定「打错的是第二次」，而那个假定没有根据。
                    restart(PinSetupModel.MISMATCH_MESSAGE)
                }
            }
        }
    }

    /**
     * 第二步按返回键 = 回第一步，不是退出。
     *
     * 和新增 3 步流那条一样（M3-5b），也是同一个理由：用户此刻脑子里
     * 「上一步」指的是第一次输入，不是这一整页。忙着写盘时不接管返回——
     * 那半秒里退出去只会让人不确定到底存没存上。
     */
    BackHandler(enabled = step == Step.Confirm && !busy) { restart(null) }

    VaultScreen(
        title = PinSetupModel.title(mode),
        onBack = { if (!busy) onBack() },
        seal = { DefaultSeal() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))

            Text(
                PinSetupModel.heading(mode, step),
                style = VaultType.H1,
                color = VaultColors.Text,
            )
            Text(
                PinSetupModel.caption(mode, step),
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(2.dp))

            PinDots(
                filled = filled,
                total = PinSetupModel.LENGTH,
                error = error != null,
            )

            if (error != null) {
                Banner(
                    text = error!!,
                    tone = BannerTone.Danger,
                    actionText = "关闭",
                    onAction = { error = null },
                )
            }

            Spacer(Modifier.height(2.dp))

            Keypad(
                onDigit = { c ->
                    error = null
                    if (typing.push(c)) filled = typing.size
                },
                onBackspace = {
                    error = null
                    if (typing.pop()) filled = typing.size
                },
                enabled = !busy,
                // 左下角那个空槽这一页不放东西：指纹键在这儿没有意义，
                // 而放「取消」正是 Keypad 当初留空要躲开的那件事。
            )

            Spacer(Modifier.height(2.dp))

            BrassButton(
                text = PinSetupModel.submitText(step),
                onClick = { submit() },
                enabled = PinSetupModel.canSubmit(filled) && !busy,
                busy = busy,
            )

            // 这段说明放在最底下、键盘之后：进这一页的人第一件事是按数字，
            // 不是读安全说明。把它摆在顶上只会把键盘挤下屏幕。
            Text(
                PinSetupModel.INTRO,
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                modifier = Modifier.padding(top = 10.dp),
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    val w = weak
    if (w != null) {
        VaultDialog(
            title = PinSetupModel.weaknessTitle(w),
            message = PinSetupModel.weaknessMessage(w),
            // 主按钮（黄铜、显眼）是「换一个」：一路点最显眼那个按钮的结果
            // 应该是更安全，不是更省事（同建库页那个弱口令弹窗）。
            confirmText = PinSetupModel.WEAK_CONFIRM_TEXT,
            onConfirm = {
                weak = null
                clearTyping()
            },
            secondaryText = PinSetupModel.WEAK_SECONDARY_TEXT,
            onSecondary = {
                weak = null
                goConfirm()
            },
            // 按返回键或点弹窗外面 = 什么都不做，停在第一步、那六位还在。
            // 绝不能等于「就用它」（见 Dialogs.kt 里三个回调为什么分开）。
            onDismissRequest = { weak = null },
        )
    }
}
