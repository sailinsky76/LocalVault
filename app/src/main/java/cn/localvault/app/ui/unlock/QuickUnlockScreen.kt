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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.localvault.app.core.keystore.QuickUnlock
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.Keypad
import cn.localvault.app.ui.components.PinBuffer
import cn.localvault.app.ui.components.PinDots
import cn.localvault.app.ui.components.SealTone
import cn.localvault.app.ui.components.TextLink
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType
import cn.localvault.app.ui.util.Fmt
import kotlinx.coroutines.delay

/**
 * 快捷解锁：PIN 键盘 + 指纹。
 *
 * ── 满 6 位不自动提交 ──
 *
 * 这是 M3-1 定下 [Keypad] 时就写明的规矩，这里必须兑现。
 * 自动提交在别处很常见，但我们这里一次失败的代价比别处大：
 * 第 5 次错就开始退避，第 9 次错要等 15 分钟。
 * 而 PIN 恰恰是**不看屏幕、凭肌肉记忆按**的东西，最后一位按错的概率不低——
 * 自动提交意味着他连改正的机会都没有，眼睁睁看着一次机会被烧掉。
 * 所以按满 6 位之后按钮才点亮，由用户自己按下去。
 *
 * ── 指纹会在进页面时自动弹一次 ──
 *
 * 这一条和上一条不矛盾：指纹认不出来**不消耗任何猜测机会**（见 [BiometricPolicy]），
 * 自动弹出的最坏结果只是用户按一下「用主密码」把它关掉。
 * 而它省下的是最高频路径上的一次点击。
 */
@Composable
fun QuickUnlockScreen(
    controller: UnlockController,
    quickUnlock: QuickUnlock,
    autoLocked: Boolean,
    onUseMaster: () -> Unit,
) {
    val buffer = remember { PinBuffer() }
    // PinBuffer 是普通对象不是 Compose 状态，长度单独拿一个 state 跟着它走。
    // 不把 PinBuffer 改成 @Stable 类，是不想让一个装着 PIN 的 char[]
    // 沾上任何「可能被框架持有、被快照系统复制」的关系。
    var filled by remember { mutableIntStateOf(0) }
    DisposableEffect(buffer) { onDispose { buffer.wipe() } }

    val pinEnrolled = remember { quickUnlock.isPinEnrolled }
    var biometricHidden by remember { mutableStateOf(false) }

    /*
     * ══════════════ 指纹自动弹框的三个状态 ══════════════
     *
     * 症状：自动锁定后切回应用，有时提示「指纹传感器不可用，请用主密码解锁」，
     * 切出去再切回来就好了。
     *
     * 原因：`ERROR_HW_UNAVAILABLE`。指纹框是在这一屏刚组合出来时拉起的，
     * 而那一刻 Activity 可能还在 resume 的路上，传感器也可能还被上一个应用
     * （或系统锁屏）占着。系统于是回一句「腾不出手」——上一版把这句话
     * 当成了「这台设备的传感器用不了」，弹红字 + 撤按钮，
     * 把用户逼去输长主密码，为的是一个半秒后自己就消失的问题。
     *
     * 三件事一起改：
     *   1. `resumed` —— 等 Activity 真的 RESUMED 了再弹（不是「组合出来了」）；
     *   2. `busyRetries` —— 「传感器正忙」时**静默重试**，不弹错误、不撤按钮；
     *   3. `promptActive` —— 弹框正在显示时不许再弹一次（第二次 authenticate
     *      会把第一次取消掉，那才是真的会让用户看见一条莫名的错误）。
     *
     * 顺带一提：用户发现的那个土办法（切出去再切回来）之所以有效，
     * 就是因为它给了传感器交接的时间。第 1 条和第 2 条等于把它自动化了。
     */
    var busyRetries by remember { mutableIntStateOf(0) }
    var promptActive by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivityForUnlock() }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    // 回到前台就把重试预算还回来：这一趟的传感器状态和上一趟无关。
                    busyRetries = 0
                }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    val launchBiometric = rememberBiometricUnlocker(
        quickUnlock = quickUnlock,
        onKey = { key ->
            promptActive = false
            controller.unlockWithKey(key)
        },
        onFailure = { failure ->
            promptActive = false
            val silentRetry =
                failure == BiometricFailure.HardwareBusy && busyRetries < MAX_BUSY_RETRIES
            if (silentRetry) {
                // 不显示错误、不撤按钮。用户不该知道这件事发生过——
                // 他要的是开门，不是一份传感器交接的实况转播。
                busyRetries++
            } else {
                if (BiometricPolicy.shouldShowMessage(failure)) {
                    controller.reportBiometricFailure(BiometricPolicy.message(failure))
                }
                // 这条路已经走不通了就把按钮撤掉。用户对着一个注定失败的按钮反复按，
                // 比一开始就没有这个按钮更消磨信任。
                //
                // 但**不只凭错误码下这个结论**：再问系统一次。错误码答的是
                // 「刚才那一次为什么没成」，这里要问的是「以后还成不成」，
                // 后者只有系统知道。上一版把前者当后者用，于是一次偶发的
                // 传感器占用，代价是这一整屏再也不给指纹入口。
                if (!BiometricPolicy.biometricStillUsable(failure)) {
                    biometricHidden =
                        !(quickUnlock.isBiometricEnrolled && quickUnlock.isBiometricUsable)
                }
            }
        },
        // 回到前台时重新问一遍 canAuthenticate()，理由见那边的参数注释。
        probe = resumed,
    )
    val biometricAvailable = launchBiometric != null && !biometricHidden

    // 用 rememberUpdatedState 取最新的那个闭包，
    // 免得 LaunchedEffect 因为每次重组都拿到新 lambda 而反复触发。
    val currentLauncher by rememberUpdatedState(launchBiometric)

    /** 唯一的弹框入口——自动和手动都走它，这样 [promptActive] 才守得住。 */
    fun launchBiometricOnce() {
        if (promptActive) return
        val launcher = currentLauncher ?: return
        promptActive = true
        launcher.invoke()
    }

    /*
     * 自动弹一次。`resumed` 和 `busyRetries` 都在 key 里：
     * 前者保证只在真正回到前台之后弹，后者让「传感器正忙」时的静默重试
     * 自然地表现为这个效应重跑一次。
     *
     * 两段延迟都很短，但缺一不可：首次那一下是等系统把传感器交接完
     * （RESUMED 只说明界面可交互了，不等于传感器已经归还），
     * 重试那一下是给它第二次机会。
     */
    LaunchedEffect(resumed, busyRetries) {
        if (!resumed || busyRetries > MAX_BUSY_RETRIES) return@LaunchedEffect
        delay(if (busyRetries == 0) SENSOR_SETTLE_MS else BUSY_RETRY_MS)
        if (controller.canAttempt) launchBiometricOnce()
    }

    // 退避倒计时的心跳，和主密码页同一套：控制器里没有计时器，一律按挂钟重算。
    LaunchedEffect(controller) {
        while (true) {
            controller.refreshLock()
            delay(500)
        }
    }

    /**
     * 连错太多次之后控制器会关掉快捷解锁。这时这一屏已经没有任何可用入口了，
     * 必须把用户送到主密码页——那边会显示「为什么 PIN 没了」的交代。
     */
    LaunchedEffect(controller.quickUnlockJustDisabled) {
        if (controller.quickUnlockJustDisabled) onUseMaster()
    }

    fun clearPin() {
        buffer.wipe()
        filled = 0
    }

    fun submit() {
        if (!controller.canAttempt || !buffer.isFull) return
        // copyChars() 交出去之后由控制器负责清零，本地这份立刻抹掉，
        // 于是任何时刻内存里最多只有一份 PIN。
        val pin = buffer.copyChars()
        clearPin()
        controller.unlockWithPin(pin)
    }

    VaultScreen(
        title = null,
        seal = { DefaultSeal(tone = if (controller.isLockedOut) SealTone.Alert else null) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))

            VaultIcon(Glyph.Lock, tint = VaultColors.Brass, size = 28.dp)
            Text(
                if (pinEnrolled) "输入 PIN" else "指纹解锁",
                style = VaultType.H1,
                color = VaultColors.Text,
            )

            if (autoLocked) {
                Text(
                    "上次因长时间未操作已自动锁定",
                    style = VaultType.Sub,
                    color = VaultColors.Dimmer,
                    textAlign = TextAlign.Center,
                )
            }

            if (pinEnrolled) {
                Spacer(Modifier.height(2.dp))
                PinDots(
                    filled = filled,
                    total = buffer.capacity,
                    error = controller.step is UnlockController.Step.Failed,
                )
            }

            when (val s = controller.step) {
                is UnlockController.Step.Failed -> Banner(
                    text = s.message,
                    tone = BannerTone.Danger,
                    actionText = "关闭",
                    onAction = { controller.dismissError() },
                )
                else -> Unit
            }

            if (controller.isLockedOut) {
                Banner(
                    text = "${Fmt.countdown(controller.lockRemainingMillis)} 后可以再试。" +
                        "等待只会变长，不会删除数据。",
                    tone = BannerTone.Danger,
                )
            }

            Spacer(Modifier.height(2.dp))

            if (pinEnrolled) {
                Keypad(
                    onDigit = { c ->
                        controller.dismissError()
                        if (buffer.push(c)) filled = buffer.size
                    },
                    onBackspace = {
                        controller.dismissError()
                        if (buffer.pop()) filled = buffer.size
                    },
                    enabled = controller.canAttempt,
                    bottomLeft = {
                        if (biometricAvailable) {
                            FingerprintKey(
                                enabled = controller.canAttempt,
                                onClick = { launchBiometricOnce() },
                            )
                        }
                    },
                )

                Spacer(Modifier.height(2.dp))

                BrassButton(
                    text = if (controller.isLockedOut)
                        "等待 ${Fmt.countdown(controller.lockRemainingMillis)}"
                    else "解锁",
                    onClick = { submit() },
                    enabled = controller.canAttempt && buffer.isFull,
                    busy = controller.busy,
                )
            } else {
                // 只绑了指纹没绑 PIN。不画一个按不出结果的键盘，
                // 直接把唯一那条路做成主按钮。
                Text(
                    "把手指放在传感器上。",
                    style = VaultType.Sub,
                    color = VaultColors.Dimmer,
                    textAlign = TextAlign.Center,
                )
                BrassButton(
                    text = "用指纹解锁",
                    onClick = { launchBiometricOnce() },
                    enabled = biometricAvailable && controller.canAttempt,
                    busy = controller.busy,
                )
            }

            TextLink("用主密码解锁", onClick = { clearPin(); onUseMaster() })

            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 键盘左下角那颗指纹键。
 *
 * 放在左下角是因为 [Keypad] 把那个位置留成了空槽——当初留空的理由是
 * 「不放取消键，避免误触退出解锁流程」，而指纹键即使误触也只是弹个系统框，
 * 按一下就关掉，没有任何代价。
 */
@Composable
private fun FingerprintKey(enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(VaultColors.Slab2)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            VaultIcon(
                Glyph.Fingerprint,
                tint = if (enabled) VaultColors.Brass else VaultColors.Dimmer,
                size = 24.dp,
            )
        }
    }
}

/* ─────────────────────── 指纹自动弹框的两个时长 ─────────────────────── */

/**
 * RESUMED 之后再等这么久才拉起指纹框。
 *
 * 为什么不是 0：`ON_RESUME` 说明界面可以交互了，**不说明指纹传感器已经归还**。
 * 上一个应用（或系统锁屏）刚才可能正用着它，交接要几百毫秒。
 * 这段时间里 `authenticate()` 会拿到 `ERROR_HW_UNAVAILABLE`。
 *
 * 为什么不更长：这一下延迟是加在最高频路径上的——每次开门都要走一遍。
 * 250ms 短到用户察觉不到「等了一下」，又足以躲过绝大多数交接窗口。
 */
private const val SENSOR_SETTLE_MS = 250L

/** 静默重试之前再等这么久。比首次长一些：既然第一次撞上了，就多给点时间。 */
private const val BUSY_RETRY_MS = 600L

/**
 * 「传感器正忙」最多静默重试几次。
 *
 * 2 次是权衡出来的：交接窗口通常一次就躲过去了，两次几乎必中。
 * 再多就变成另一种问题了——用户按了「用指纹」，屏幕上什么都不发生，
 * 而我们在后台悄悄试第五次。到了这个地步，如实说一句
 * 「传感器正忙，可以再按一下」比继续偷偷试更尊重人。
 */
private const val MAX_BUSY_RETRIES = 2

/**
 * 取 Activity 的 lifecycle，用来判断「真的回到前台了没有」。
 *
 * 刻意不用 `LocalLifecycleOwner`，理由同 `SecuritySettingsScreen` 里那一段：
 * 它在 compose-ui 和 lifecycle-runtime-compose 两个包里各有一份，
 * 前者已弃用、后者要多加一个依赖。从 Context 里剥出来就有。
 */
private tailrec fun android.content.Context.findComponentActivityForUnlock():
    androidx.activity.ComponentActivity? = when (this) {
    is androidx.activity.ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findComponentActivityForUnlock()
    else -> null
}
