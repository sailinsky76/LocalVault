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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cn.localvault.app.ui.LocalClipboard
import cn.localvault.app.ui.LocalCryptoInfo

/**
 * 每个页面顶部那条封条的标准接法。
 *
 * 单独抽出来的理由不是「少写几行」，而是**防止封条在某些页面上撒谎**。
 * 如果每个页面各自拼 `SealSlot`，早晚会有人在某一页把
 * `tone` 写死成 `Trusted`、或者把 `kdfLabel` 换成 "Argon2id" 常量，
 * 于是降级到 PBKDF2 的设备上，用户在 A 页看到实话、在 B 页看到假话。
 *
 * 所以：全工程的页面一律 `seal = { DefaultSeal() }`，
 * 只有确实需要报警的页面（比如解锁失败退避中）才传 [tone] 覆盖。
 *
 * ── 折叠区里放什么 ──
 *
 * 只放两行：派生算法和条目加密算法。**不要**把关于页那一整套
 * （版本号、库文件大小、权限清单、第三方组件）搬过来 —— 那会变成
 * 一个从每一页都能拉下来的第二个关于页，而关于页本身就在设置里躺着。
 *
 * 但这两行不能只留在关于页，因为关于页在解锁之后才打得开
 * （见 `AboutScreen` 开头那句 `?: return`）。锁着的时候，
 * 这条封条是用户唯一能看到「这台机器上到底用的什么算法」的地方，
 * 而那恰恰是最该看到它的时刻 —— 一台 Argon2 加载失败的机器，
 * 用户有权在输入主密码**之前**就知道。
 */
@Composable
fun DefaultSeal(
    tone: SealTone? = null,
    left: String = "本地加密 · 无网络权限",
) {
    val info = LocalCryptoInfo.current
    val clip = LocalClipboard.current
    val pending by clip.pending.collectAsState()

    SealSlot(
        sealLeft = left,
        tone = tone ?: if (info.argon2Available) SealTone.Trusted else SealTone.Degraded,
        facts = listOf(
            SealFact("密钥派生", info.kdfLabel),
            SealFact("条目加密", info.cipherLabel),
        ),
        // 降级这件事写在收起态那一行里，不等用户点开。降到了什么算法在折叠区。
        degradedNote = if (info.argon2Available) null else "已降级",
        clipboardLabel = pending?.label,
        clipboardRemaining = pending?.remainingSeconds ?: 0,
        // totalSeconds == 0 是「这一份不会自动清除」的标记，见 SecureClipboard。
        clipboardAutoClear = (pending?.totalSeconds ?: 1) > 0,
        onClearClipboard = { clip.clearNow() },
    )
}
