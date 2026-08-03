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

package cn.localvault.app.ui.generate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.IconSlot
import cn.localvault.app.ui.components.PresetChip
import cn.localvault.app.ui.components.Stepper
import cn.localvault.app.ui.components.StrengthMeter
import cn.localvault.app.ui.components.ToggleRow
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType
import cn.localvault.app.ui.util.PasswordStrength

/**
 * 生成器的面板内容。
 *
 * 它不持有密码，也不决定「生成完之后干什么」——那两件事在不同场合下答案不一样
 * （编辑页是「填进密码框」，设置页里的独立入口是「复制」，M3-5b 新增流是「填进第二步」）。
 * 这里只负责画对，并且在三个地方画得一模一样。这和 [cn.localvault.app.ui.edit.EntryFormFields]
 * 是同一个套路，理由也是同一条（决策(55)）：两边各写一份，规则马上就会分叉。
 *
 * ── 结果默认是明文 ──
 *
 * 详情页的密码默认遮蔽（决策㊾、㊼），这里刻意反过来。
 * 因为此刻这串字符**还不是任何账户的密码**——它还没被采用，
 * 泄露它的唯一后果是用户按「重新生成」。而遮住它就没法核对，
 * 而核对恰恰是这一屏唯一的用途。
 * 一旦按下「用这个密码」，它就归详情页那套遮蔽规则管了。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeneratorPanel(
    options: PasswordGen.Options,
    onOptionsChange: (PasswordGen.Options) -> Unit,
    password: String,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {

        GeneratedValue(password = password, onRegenerate = onRegenerate)

        StrengthMeter(meterResult(PasswordGen.entropyBits(options)))

        ModePicker(options = options, onOptionsChange = onOptionsChange)

        when (options.mode) {
            PasswordGen.Mode.Random -> RandomOptions(options, onOptionsChange)
            PasswordGen.Mode.Readable -> ReadableOptions(options, onOptionsChange)
        }
    }
}

/* ─────────────────────────── 结果 ─────────────────────────── */

/**
 * 结果**按字符类别分三色**：字母是正文色、数字是黄铜、符号是铁锈。
 *
 * 这不是装饰。这串东西的第一用途是被人照着抄进另一台设备
 * （电脑上的浏览器、公司的登录页、路由器后台），
 * 而在一串 20 位的等宽乱码里数出「第几个是符号」，靠颜色比靠眼睛快一个数量级。
 * 黄铜色在这套设计里本来就是「机器生成的凭据」的颜色（见 Color.kt），
 * 这里用在数字上是同一条线索的延伸。
 */
@Composable
private fun GeneratedValue(password: String, onRegenerate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VaultShape.Field)
            .background(VaultColors.Void)
            .border(1.dp, VaultColors.Line, VaultShape.Field)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = colorized(password),
            style = VaultType.MonoPassword,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
            IconSlot(
                Glyph.Refresh,
                contentDescription = "重新生成",
                tint = VaultColors.Brass,
                onClick = onRegenerate,
            )
        }
    }
}

private fun colorized(password: String): AnnotatedString = buildAnnotatedString {
    for (c in password) {
        val color = when (PasswordGen.kindOf(c)) {
            PasswordGen.Kind.Letter -> VaultColors.Text
            PasswordGen.Kind.Digit -> VaultColors.Brass
            PasswordGen.Kind.Symbol -> VaultColors.Rust
        }
        withStyle(SpanStyle(color = color)) { append(c) }
    }
}

/**
 * 强度条复用 [StrengthMeter] 的样子，但**喂给它的是算出来的熵，不是估出来的**。
 *
 * [PasswordStrength.evaluate] 是给用户自己打的密码用的：面对一个来路不明的字符串，
 * 只能从字符集和规律性去估。而这串是我们刚生成的，规则完全已知，
 * 熵在 [PasswordGen.entropyBits] 里能算准。
 *
 * 更要紧的是：拿 `evaluate()` 去评自己生成的密码会**报低**——
 * 一个真随机的 20 位密码里出现 `abc` 或者两个相同字符完全正常，
 * 而那套估算会为此扣分。于是用户看到「刚生成的密码只有『较强』」，
 * 然后合理地怀疑这个生成器有问题。
 *
 * 档位的门槛沿用 [PasswordStrength] 那一套，是为了让同一个界面上的
 * 「弱 / 一般 / 较强 / 强」始终指同一件事。
 */
private fun meterResult(bits: Int): PasswordStrength.Result {
    val level = when {
        bits < 40 -> PasswordStrength.Level.Weak
        bits < 60 -> PasswordStrength.Level.Fair
        bits < 80 -> PasswordStrength.Level.Good
        else -> PasswordStrength.Level.Strong
    }
    val hint = when (level) {
        PasswordStrength.Level.Strong -> "约 $bits bit —— 离线爆破在可预见的将来都算不完"
        PasswordStrength.Level.Good -> "约 $bits bit —— 够用，再长几位更稳"
        PasswordStrength.Level.Fair -> "约 $bits bit —— 加长几位，或者打开更多字符类"
        PasswordStrength.Level.Weak -> "约 $bits bit —— 太短了，这一档撑不住离线爆破"
    }
    return PasswordStrength.Result(bits = bits, level = level, hint = hint)
}

/* ─────────────────────────── 模式 ─────────────────────────── */

@Composable
private fun ModePicker(
    options: PasswordGen.Options,
    onOptionsChange: (PasswordGen.Options) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VaultShape.Field)
            .background(VaultColors.Slab)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ModeTab(
            text = "随机",
            selected = options.mode == PasswordGen.Mode.Random,
            modifier = Modifier.weight(1f),
        ) { onOptionsChange(options.copy(mode = PasswordGen.Mode.Random)) }
        ModeTab(
            text = "易读",
            selected = options.mode == PasswordGen.Mode.Readable,
            modifier = Modifier.weight(1f),
        ) { onOptionsChange(options.copy(mode = PasswordGen.Mode.Readable)) }
    }
}

@Composable
private fun ModeTab(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(VaultShape.TileSm)
            .background(if (selected) VaultColors.Slab3 else VaultColors.Slab)
            .heightIn(min = 38.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = VaultType.H2,
            color = if (selected) VaultColors.Text else VaultColors.Dim,
        )
    }
}

/* ─────────────────────────── 随机模式的选项 ─────────────────────────── */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RandomOptions(
    options: PasswordGen.Options,
    onOptionsChange: (PasswordGen.Options) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Eyebrow("长度")
            Stepper(
                value = options.length,
                onValueChange = { onOptionsChange(options.copy(length = it)) },
                min = PasswordGen.MIN_LENGTH,
                max = PasswordGen.MAX_LENGTH,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (preset in intArrayOf(12, 16, 20, 32, 64)) {
                PresetChip(
                    text = preset.toString(),
                    selected = options.length == preset,
                    onClick = { onOptionsChange(options.copy(length = preset)) },
                )
            }
        }

        /**
         * 「至少留一类」。
         *
         * 最后一个还开着的开关是**灰的**，而且旁边写着为什么灰
         * （决策(61)：没有解释的灰控件，用户第一反应是这个 App 卡了）。
         * 内核那边也兜了同一条（[PasswordGen.normalized] 会强制打开小写），
         * 两边都做不是重复——界面负责让用户明白，内核负责保证不管界面怎么变
         * 都不会生成出一个空密码或者直接崩掉。
         */
        val openCount = listOf(options.lower, options.upper, options.digits, options.symbols).count { it }
        val lastOne = openCount <= 1

        Column {
            ToggleRow(
                title = "小写字母",
                subtitle = "a–z",
                checked = options.lower,
                enabled = !(lastOne && options.lower),
                note = "至少要留一类字符",
                onCheckedChange = { onOptionsChange(options.copy(lower = it)) },
            )
            ToggleRow(
                title = "大写字母",
                subtitle = "A–Z",
                checked = options.upper,
                enabled = !(lastOne && options.upper),
                note = "至少要留一类字符",
                onCheckedChange = { onOptionsChange(options.copy(upper = it)) },
            )
            ToggleRow(
                title = "数字",
                subtitle = "0–9",
                checked = options.digits,
                enabled = !(lastOne && options.digits),
                note = "至少要留一类字符",
                onCheckedChange = { onOptionsChange(options.copy(digits = it)) },
            )
            ToggleRow(
                title = "符号",
                // 把符号集**原样列出来**：用户得先知道密码里可能出现什么，
                // 才谈得上判断「这个网站认不认」。
                subtitle = PasswordGen.symbolSet(),
                checked = options.symbols,
                enabled = !(lastOne && options.symbols),
                note = "至少要留一类字符",
                onCheckedChange = { onOptionsChange(options.copy(symbols = it)) },
            )
            ToggleRow(
                title = "避开易混字符",
                subtitle = "去掉 0 O 1 l I —— 要手抄或者念给别人听时打开",
                checked = options.avoidAmbiguous,
                onCheckedChange = { onOptionsChange(options.copy(avoidAmbiguous = it)) },
            )
        }

        Text(
            "字符池 ${PasswordGen.poolSize(options)} 个字符，每一类至少出现一次。",
            style = VaultType.MonoSmall,
            color = VaultColors.Dimmer,
        )
    }
}

/* ─────────────────────────── 易读模式的选项 ─────────────────────────── */

@Composable
private fun ReadableOptions(
    options: PasswordGen.Options,
    onOptionsChange: (PasswordGen.Options) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Eyebrow("音节数")
            Stepper(
                value = options.syllables,
                onValueChange = { onOptionsChange(options.copy(syllables = it)) },
                min = PasswordGen.MIN_SYLLABLES,
                max = PasswordGen.MAX_SYLLABLES,
            )
        }

        ToggleRow(
            title = "末尾加两位数字",
            subtitle = "路由器和一些老系统硬性要求密码里有数字",
            checked = options.trailingDigits,
            onCheckedChange = { onOptionsChange(options.copy(trailingDigits = it)) },
        )

        /**
         * 这一段话是这个模式存在的全部理由，所以它必须写在屏幕上，
         * 而不是只写在代码注释里：**易读模式更弱**。
         *
         * 不写清楚的话，「易读」听起来像是「一样安全但更好记」，
         * 于是用户会把它用在网银上。写清楚了，他才会把它用在对的地方——
         * 那些他自己也知道必须手打的地方。
         */
        Text(
            "给要手打、要念出来的地方用：WiFi、遥控器、电话里报给家人。它比随机模式弱。",
            style = VaultType.Sub,
            color = VaultColors.Dim,
        )
        Text(
            "音节是按拼音的声母韵母拼的，念得出来就记得住；不联网、不带词表。",
            style = VaultType.MonoSmall,
            color = VaultColors.Dimmer,
        )
    }
}
