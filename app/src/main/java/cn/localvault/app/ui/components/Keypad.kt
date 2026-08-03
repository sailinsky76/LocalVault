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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * PIN 键盘。
 *
 * ── 这个键盘是什么、不是什么 ──
 * 它是 **PIN 键盘**，不是主密码键盘。这个区分是 M1 定下的第 ② 条设计决策，
 * 直接决定了产品的安全边界：
 *
 *   · 6 位数字只有 10⁶ 种组合，如果它是主密码，保险库文件被拷走
 *     就等于明文 —— 离线爆破一台笔记本几秒钟的事；
 *   · 但作为 PIN 它是安全的，因为 [cn.localvault.app.core.keystore.QuickUnlock]
 *     把 PIN 包裹外面又套了一层 Keystore 设备绑定密钥。攻击者拿到文件也没用，
 *     他必须在这台机器上、在本应用进程里试，于是落进 AttemptLimiter 的退避。
 *
 * 所以这个键盘只出现在两个地方：日常快捷解锁、设置 PIN。
 * 建库和改主密码永远用全键盘。
 */
@Composable
fun PinDots(
    filled: Int,
    total: Int = 6,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    val on = if (error) VaultColors.Rust else VaultColors.Brass
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            Box(
                Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(if (i < filled) on else VaultColors.Slab2)
            )
        }
    }
}

/**
 * 3×4 数字键盘。左下角留空（不放「取消」，避免误触退出解锁流程），
 * 右下角是退格。
 *
 * 刻意**不做**满 6 位自动提交：解锁失败会计入退避，
 * 而误触的最后一位不该直接烧掉一次尝试机会。由调用方决定何时提交。
 */
@Composable
fun Keypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bottomLeft: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().widthIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in KEY_ROWS) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (key in row) {
                    when (key) {
                        ' ' -> Box(Modifier.weight(1f).aspectRatio(1.55f)) {
                            bottomLeft?.invoke()
                        }
                        '<' -> KeyCell(Modifier.weight(1f), enabled = enabled, onClick = onBackspace) {
                            VaultIcon(Glyph.Backspace, tint = VaultColors.Dim, size = 21.dp)
                        }
                        else -> KeyCell(Modifier.weight(1f), enabled = enabled, onClick = { onDigit(key) }) {
                            Text(key.toString(), style = VaultType.Keypad, color = VaultColors.Text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCell(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .aspectRatio(1.55f)
            .clip(VaultShape.Field)
            .background(if (enabled) VaultColors.Slab else VaultColors.Slab.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private val KEY_ROWS = listOf(
    charArrayOf('1', '2', '3'),
    charArrayOf('4', '5', '6'),
    charArrayOf('7', '8', '9'),
    charArrayOf(' ', '0', '<'),
)

/**
 * PIN 的输入缓冲。用 CharArray 而不是 String，理由同
 * [SecureTextState] ——「6 位数字」不代表它不值得擦。
 */
class PinBuffer(val capacity: Int = 6) {
    private val buf = CharArray(capacity)
    var size: Int = 0
        private set

    val isFull: Boolean get() = size == capacity

    fun push(c: Char): Boolean {
        if (isFull) return false
        buf[size++] = c
        return true
    }

    fun pop(): Boolean {
        if (size == 0) return false
        buf[--size] = '\u0000'
        return true
    }

    /** 交出一份副本，调用方用完负责 wipe */
    fun copyChars(): CharArray = buf.copyOf(size)

    fun wipe() {
        java.util.Arrays.fill(buf, '\u0000')
        size = 0
    }
}

/** 一小行说明配色，退避倒计时用 */
@Composable
fun KeypadNote(text: String, color: Color = VaultColors.Dim, modifier: Modifier = Modifier) {
    Text(text, style = VaultType.Sub, color = color, modifier = modifier)
}
