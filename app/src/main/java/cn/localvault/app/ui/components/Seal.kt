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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType

/**
 * ┌─ 顶部封条（收起） ────────────────────────────────────┐
 * │ ● 本地加密 · 无网络权限                             ⌄ │
 * └──────────────────────────────────────────────────────┘
 * ┌─ 展开 ───────────────────────────────────────────────┐
 * │ 密钥派生            Argon2id 64MiB t=3 p=1           │
 * │ 条目加密            AES-256-GCM                      │
 * └──────────────────────────────────────────────────────┘
 *
 * 这条窄带是整个产品的「合规说明书」，也是最反常规的一处设计。
 *
 * 大多数密码管理器把加密参数藏在设置的第四层，理由是「用户看不懂」。
 * 但用户看不懂的是参数，看得懂的是**这个 App 敢不敢把参数摆出来**。
 * 一个愿意在首屏一步之内交出算法参数的应用，和一个绝口不提的应用，
 * 传达的可信度完全不同 —— 而这恰恰是本地方案唯一能拿来对抗云端大厂的东西。
 *
 * ── 修订（v3）：为什么改成可折叠 ──
 *
 * 初版把 `本地加密 · 无网络权限` 和 `Argon2id 64MiB/t3 · AES-256-GCM`
 * 一左一右塞进同一行 30dp 的窄带里，两侧都 `maxLines = 1`。
 * 结果在 360dp 宽的机器上右半边必然被省略号吃掉尾巴，
 * 而被吃掉的恰好是 `AES-256-GCM` —— 一行「摆出参数」的设计，
 * 实际效果是每一页顶上都挂着一句没说完的话。宁可少说，不要说半句。
 *
 * 所以参数下沉到折叠区，收起态只留一句短话，永远不会被截断。
 *
 * 这一改动动了初版的规矩三（「高度固定、不可点击展开」）。那条规矩要防的是
 * **把背景信息做成功能入口**，于是展开区里只有事实、没有任何按钮和跳转：
 * 它仍然不是入口，只是同一句话的完整版。规矩一（只显示真实参数、
 * 降级必须如实说）比它重要，而且没有被放弃 —— 见下面的 `degradedNote`。
 *
 * 剩下两条规矩不变：
 *   1. 只显示**真实**参数，降级到 PBKDF2 时如实写 PBKDF2；
 *   2. 参数一律等宽字体 —— 机器生成的内容一律等宽，用户会无意识学到这条规则。
 */

enum class SealTone { Trusted, Degraded, Alert }

/** 折叠区里的一行事实：左边是人话，右边是机器参数。 */
data class SealFact(val label: String, val value: String)

@Composable
fun SealBar(
    left: String,
    modifier: Modifier = Modifier,
    tone: SealTone = SealTone.Trusted,
    facts: List<SealFact> = emptyList(),
    /**
     * 降级时那一句短话，直接拼在收起态的第一行里（例如「· 已降级」）。
     *
     * 降级是**坏消息**，不能藏在需要点一下才看得到的地方：用户没点开，
     * 就等于这个 App 没告诉过他。展开区里再给出降到了什么算法。
     */
    degradedNote: String? = null,
) {
    val fg = when (tone) {
        SealTone.Trusted -> VaultColors.Brass
        SealTone.Degraded -> VaultColors.Dim
        SealTone.Alert -> VaultColors.Rust
    }
    val bg = when (tone) {
        SealTone.Trusted -> VaultColors.BrassWash
        SealTone.Degraded -> VaultColors.Slab
        SealTone.Alert -> VaultColors.RustWash
    }

    // 状态留在组件里，不往上抬：换页之后重新收起是对的。
    // 参数是「想起来的时候去确认一次」的东西，不是需要跨页记住的偏好。
    var open by remember { mutableStateOf(false) }
    val expandable = facts.isNotEmpty()
    val chevronAngle by animateFloatAsState(
        targetValue = if (open) 270f else 90f,
        label = "seal-chevron",
    )

    Column(modifier = modifier.fillMaxWidth().background(bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { open = !open } else Modifier)
                .padding(horizontal = 14.dp)
                .height(30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
            Text(
                text = if (degradedNote != null) "$left · $degradedNote" else left,
                style = VaultType.Seal,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (expandable) {
                VaultIcon(
                    Glyph.Chevron,
                    tint = fg.copy(alpha = 0.7f),
                    size = 14.dp,
                    strokeWidth = 1.7f,
                    modifier = Modifier.rotate(chevronAngle),
                )
            }
        }

        AnimatedVisibility(
            visible = open && expandable,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(start = 26.dp, end = 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                facts.forEach { fact ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(fact.label, style = VaultType.Seal, color = fg.copy(alpha = 0.65f))
                        Text(
                            fact.value,
                            style = VaultType.Seal,
                            color = fg,
                            textAlign = TextAlign.End,
                            // 这里允许折行：参数说全比排整齐重要，
                            // 而这一段只在用户主动点开时才占位置。
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 剪贴板倒计时条。挂在封条下面，只在有内容待清除时出现。
 *
 * 做成常驻可见而不是一闪而过的 Toast，是因为用户需要知道
 * 「我现在有 12 秒时间去粘贴」，而不是事后才发现剪贴板空了。
 *
 * 标签（条目名）和状态分成两个 Text：条目名可以任意长，
 * 挤掉的必须是它，不能是「$n 秒后自动清除」那一句 —— 倒计时才是这一条的正文。
 */
@Composable
fun ClipboardBar(
    label: String,
    remainingSeconds: Int,
    onClearNow: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    /**
     * 用户在设置里把「剪贴板自动清除」关掉时传 false。
     *
     * 这条横幅于是变成常驻的：不倒数，也不会自己消失。它有两个作用——
     * 一是给复制这个动作一个回执（决策(51) 说回执就是这条，
     * 倒计时没了回执不能跟着没）；二是它一直挂在屏幕顶上这件事本身
     * 就在提醒「你现在有一份密码躺在剪贴板里」，而那正是关掉自动清除的代价。
     * 语气也换了：不再用玉色（那是「一切按计划进行」的颜色），改用黄铜色。
     */
    autoClear: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        val fg = if (autoClear) VaultColors.Jade else VaultColors.Brass
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VaultColors.Slab2)
                .padding(horizontal = 14.dp)
                .height(30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label,
                style = VaultType.MonoSmall,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                if (autoClear) "已复制 · ${remainingSeconds}s 后清除" else "已复制 · 不自动清除",
                style = VaultType.MonoSmall,
                color = fg,
                maxLines = 1,
            )
            Box(Modifier.weight(1f))
            Text(
                "立即清除",
                style = VaultType.MonoSmall,
                color = VaultColors.Dim,
                maxLines = 1,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .clickable(onClick = onClearNow)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * 封条 + 剪贴板条的组合槽位。页面只要调这一个就行。
 */
@Composable
fun SealSlot(
    sealLeft: String,
    tone: SealTone = SealTone.Trusted,
    facts: List<SealFact> = emptyList(),
    degradedNote: String? = null,
    clipboardLabel: String? = null,
    clipboardRemaining: Int = 0,
    clipboardAutoClear: Boolean = true,
    onClearClipboard: () -> Unit = {},
) {
    Column {
        SealBar(left = sealLeft, tone = tone, facts = facts, degradedNote = degradedNote)
        if (clipboardLabel != null) {
            ClipboardBar(
                label = clipboardLabel,
                remainingSeconds = clipboardRemaining,
                onClearNow = onClearClipboard,
                autoClear = clipboardAutoClear,
            )
        }
    }
}

/** 一小块状态徽标，用在设置页和详情页 */
@Composable
fun Pip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(4.dp).clip(CircleShape).background(color))
        Text(text, style = VaultType.MonoSmall, color = color)
    }
}
