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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.localvault.app.BuildConfig
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.LocalCryptoInfo
import cn.localvault.app.ui.LocalRepository
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.TextLink
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType

/**
 * 关于页。
 *
 * ── 这一页写的是事实，不是故事 ──
 *
 * 大多数应用的关于页是一段品牌介绍加一个版本号。这一页反过来：
 * 上半部分是**能被核实的参数**（版本、算法、库文件多大），
 * 下半部分是**这个 App 没有的东西**——权限只有一条、没有埋点、没有账号。
 *
 * 之所以「没有什么」比「有什么」更值得占屏幕，是因为对一个本地密码管理器来说，
 * 那些「没有」恰恰就是它的功能。而且每一条都能被用户自己验证：
 * 权限列表去系统设置里看一眼、断网用一整天、卸载前后翻一遍文件管理器。
 *
 * **不写不能核实的话。**「军工级加密」「绝对安全」这类词一个都不用——
 * 它们不但没有信息量，还会连累旁边那几条真话（一份夹着广告词的说明书，
 * 读的人会自动把整页都打个折）。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    /**
     * 去自动填充那一页。
     *
     * **刻意没有默认值**，理由同 `SettingsScreen.onSecurity`（M3-6b-1）：
     * 这一页上长出了一个能点的出口，参数可省略的话，某天有人复制一份调用
     * 忘了传，那一行就变成点了没反应的死行，而编译器一声不吭。
     */
    onAutofill: () -> Unit,
) {
    val session = LocalSession.current
    val repo = LocalRepository.current
    val info = LocalCryptoInfo.current
    val state by session.state.collectAsState()

    val data = (state as? VaultSession.State.Unlocked)?.data ?: return

    // File.length() 是一次 stat，便宜；但没必要每帧都做。
    // 条目数一变就重算，正好对上「加了一条之后库变大了多少」这个用户会好奇的问题。
    val facts = remember(data.entries.size, info) {
        SettingsModel.aboutFacts(
            versionName = BuildConfig.VERSION_NAME,
            kdfLabel = info.kdfLabel,
            cipherLabel = info.cipherLabel,
            argon2Available = info.argon2Available,
            entryCount = data.entries.size,
            vaultBytes = repo.fileSizeBytes(),
            createdAt = data.meta.createdAt,
        )
    }

    VaultScreen(title = "关于", onBack = onBack, seal = { DefaultSeal() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VaultIcon(Glyph.Shield, tint = VaultColors.Brass, size = 30.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("本地保险库", style = VaultType.H1, color = VaultColors.Text)
                    Text(
                        "账号和密码只以加密形式存在这台手机上。",
                        style = VaultType.Sub,
                        color = VaultColors.Dim,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            /* ───────────────── 参数 ───────────────── */

            VaultCard(Modifier.fillMaxWidth()) {
                Column {
                    facts.forEachIndexed { i, fact ->
                        if (i > 0) HairLine()
                        FactRow(fact)
                    }
                }
            }

            /* ───────────────── 权限 ───────────────── */

            Eyebrow("权限", modifier = Modifier.padding(start = 4.dp, top = 10.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SettingsModel.PERMISSIONS.forEach { line ->
                        Text(line, style = VaultType.Sub, color = VaultColors.Text)
                    }
                    Text(
                        // 这句话是给用户一个**动作**，不是一句自夸。
                        // 可核实的承诺必须附带核实的办法，否则和「相信我们」没区别。
                        "去「系统设置 → 应用 → 本地保险库 → 权限」可以自己核对这一条。",
                        style = VaultType.Sub,
                        color = VaultColors.Dimmer,
                    )
                }
            }

            /* ───────────────── 自动填充 ───────────────── */

            // 单独一格，**不并进上面那份权限清单**：BIND_AUTOFILL_SERVICE
            // 不是这个应用申请的权限，写进去用户去系统里核对会对不上
            // （理由写在 SettingsModel.AUTOFILL_NOTE 上）。
            // 但系统那屏「它将能够看到你屏幕上的内容」够吓人的，得有人接着说下半句。
            Eyebrow("自动填充", modifier = Modifier.padding(start = 4.dp, top = 10.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SettingsModel.AUTOFILL_NOTE.forEach { line ->
                        Text(line, style = VaultType.Sub, color = VaultColors.Text)
                    }
                    /*
                     * M4-4a 补的那一段，**是一个指路牌，不是那份清单的副本**。
                     *
                     * 「它为什么有时候不出现」有七条，逐条摆在这儿会把这一页最要紧的
                     * 那件事（权限只有一条、没有的东西有哪些）挤到看不见的地方。
                     * 更要紧的是两边的读者要的东西不一样：来翻关于页的人在判断
                     * 这个应用可不可信，去自动填充页的人手上有一个具体的、
                     * 刚才没弹出来的输入框。
                     *
                     * 同一段话摆两处，早晚会只改一处（同决策(131) 那条引用常量的来意）。
                     */
                    TextLink(
                        AutofillSettingsModel.ABOUT_POINTER,
                        onClick = onAutofill,
                        // 这一格里其余几行都是不可点的说明。用黄铜色把这一行分出来，
                        // 否则它看起来只是第五句话——而它是这一格里唯一能点的东西。
                        color = VaultColors.Brass,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            /* ───────────────── 没有的东西 ───────────────── */

            Eyebrow("这个应用没有的东西", modifier = Modifier.padding(start = 4.dp, top = 10.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SettingsModel.ABSENCES.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("·", style = VaultType.Sub, color = VaultColors.Brass)
                            Text(line, style = VaultType.Sub, color = VaultColors.Text)
                        }
                    }
                }
            }

            /* ───────────────── 依赖 ───────────────── */

            Eyebrow("用到的第三方组件", modifier = Modifier.padding(start = 4.dp, top = 10.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    SettingsModel.DEPENDENCIES.forEach { line ->
                        Text(line, style = VaultType.MonoSmall, color = VaultColors.Dim)
                    }
                }
            }

            /* ───────────────── 重申一遍 ───────────────── */

            Spacer(Modifier.height(6.dp))
            VaultCard(Modifier.fillMaxWidth(), background = VaultColors.Slab2) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("主密码丢了就打不开了", style = VaultType.H2, color = VaultColors.Brass)
                    Text(
                        // 欢迎页说过一次，这里再说一次。重复是有意的：
                        // 建库那天用户只是想赶紧用起来，这句话多半没往心里去；
                        // 而他来翻关于页的时候，通常是已经用了一阵、库里东西不少了。
                        "它不上传、不留副本、也不做「找回」。我们没有任何办法帮你打开一个" +
                            "忘了主密码的保险库——不是不愿意，是这个库本来就是用它派生的密钥加密的。" +
                            "所以请把主密码另外记在一个安全的地方，并且定期导出备份。",
                        style = VaultType.Sub,
                        color = VaultColors.Dim,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun FactRow(fact: SettingsModel.Fact) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(fact.label, style = VaultType.RowName, color = VaultColors.Text, modifier = Modifier.weight(1f))
        Text(
            fact.value,
            // 机器生成的内容一律等宽 —— 全工程的规矩，见 Type.kt
            style = if (fact.mono) VaultType.MonoSmall else VaultType.Sub,
            color = VaultColors.Dim,
        )
    }
}
