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

package cn.localvault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 按住不放才算数的按钮。目前只有一个调用方（清空重来页），
 * 但它放在 `ui/components/` 而不是那一页里面——这个部件的取舍
 * 和「清空保险库」这件事无关，和「怎么把一次点击换成一段持续的意图」有关。
 *
 * ── 为什么不能用 `combinedClickable` 的 `onLongClick` ──
 *
 * 那个东西只在满了之后通知你一声，中间的两秒九对界面来说是一片空白：
 *   · 画不出进度——用户不知道自己按的到底算不算数，
 *     也分不出「按住中」和「这按钮坏了」；
 *   · 说不出还剩几秒——`ResetVaultModel.holdLabel` 那句
 *     「继续按住…3 / 2 / 1」根本没有数据来源；
 *   · **中止不了**——它的时长是系统的长按阈值（几百毫秒），
 *     不是我们要的三秒，也没有「松手就作废」这个语义。
 * 所以这里直接接 [pointerInput]：按下开始，抬起 / 被抢走就作废。
 *
 * ── 手势为什么用 `waitForUpOrCancellation` ──
 *
 * 因为这个按钮长在一个 `verticalScroll` 里面。用户按着不动是「按住」，
 * 按着往上一滑是「他想翻页看上面那几段字」——后者会被父容器判成滚动，
 * 手势在这里被取消，[waitForUpOrCancellation] 返回 null。
 * 两种情况我们的处理一模一样：这一轮不算数（见 [HoldProgress.release]）。
 * 手指还压在屏幕上但已经滑走了，就不能再当成他一直按着。
 *
 * ── 这里刻意不加震动反馈 ──
 *
 * 一个到点时「嗒」一下的马达在手感上确实更好，
 * 而 `View.performHapticFeedback` 也确实不需要 `VIBRATE` 权限。
 * 但关于页那份权限清单是这个 App 的招牌之一（`SettingsModel` 里
 * 「权限清单只有一条」是有单测钉着的），把一个碰马达的调用塞进来，
 * 迟早会有人在别处顺手改成 `Vibrator`，那时清单就多一条了。
 * 进度条 + 一秒一跳的剩余秒数已经足够说明「它在数着」，不值当拿这个换。
 *
 * @param holdLabel 按住过程中按钮上的字。参数是**剩余毫秒**，
 *                  文案由调用方给（见 `ResetVaultModel.holdLabel`）——
 *                  这个组件不认识「清空保险库」这几个字。
 * @param onComplete 按满时长的那一刻调一次，**只调一次**（见 [HoldProgress]）。
 */
@Composable
fun HoldButton(
    idleText: String,
    holdLabel: (remainingMillis: Long) -> String,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdMillis: Long = 3000L,
) {
    val hold = remember(holdMillis) { HoldProgress(holdMillis) }

    // 手势那边只负责翻这个开关，剩下的全在下面那个 LaunchedEffect 里。
    // 分开是因为帧循环必须能被结构化取消，而 pointerInput 的作用域不适合跑长循环。
    var pressed by remember { mutableStateOf(false) }

    // 每帧刷新一次，用来重画进度和剩余秒数。
    // 存的是帧时间本身而不是算好的进度：算法在 HoldProgress 里，这儿只管传时间。
    var frameNow by remember { mutableStateOf(0L) }

    /*
     * 按钮中途变灰时立刻中止。
     *
     * 这在这一页上是真会发生的：`canArm` 同时看着抄写对不对和忙不忙，
     * 而输入法在按住期间仍然可能把那一行字改掉（联想候选、手写板回填）。
     * 不中止的话，用户会看着一个已经变灰的按钮把进度走满。
     */
    LaunchedEffect(enabled) {
        if (!enabled) {
            pressed = false
            hold.release()
        }
    }

    LaunchedEffect(pressed) {
        if (!pressed) {
            hold.release()
            return@LaunchedEffect
        }
        var started = false
        while (true) {
            val t = withFrameMillis { it }
            if (!started) {
                // 第一帧才按下：用帧时间当起点，和后面每一帧的时间源是同一个。
                hold.press(t)
                started = true
            }
            frameNow = t
            if (hold.tick(t)) {
                // 先收手势再动手。反过来的话，onComplete 之后这一页多半已经
                // 进入忙碌态，而 pressed 还挂着 true，抬手时又会翻一次。
                pressed = false
                onComplete()
                break
            }
        }
    }

    val fraction = if (pressed) hold.progress(frameNow) else 0f
    val label = if (pressed) holdLabel(hold.remaining(frameNow)) else idleText

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(VaultShape.Field)
            .background(VaultColors.RustWash)
            .border(
                1.dp,
                VaultColors.Rust.copy(alpha = if (enabled) 0.5f else 0.2f),
                VaultShape.Field,
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    try {
                        pressed = true
                        // 返回 null = 手势被取消（父容器把它当滚动抢走了）。
                        // 抬手和被抢走在这儿是同一件事，所以不看返回值。
                        waitForUpOrCancellation()
                    } finally {
                        // 写在 finally 里：这个协程会随 pointerInput 一起被取消
                        // （比如 enabled 翻成 false 的那一刻），那时也得把开关放下。
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (fraction > 0f) {
            // 进度是从左往右长出来的一整块，不是一条细线：
            // 用户的手指此刻正压在这个按钮上，一条 3dp 的进度线多半被指头盖住了。
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(VaultColors.Rust.copy(alpha = 0.30f)),
            )
        }
        Text(
            label,
            style = VaultType.H2,
            color = if (enabled) VaultColors.Rust else VaultColors.Dimmer,
        )
    }
}
