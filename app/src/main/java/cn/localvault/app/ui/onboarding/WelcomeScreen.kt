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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.LocalCryptoInfo
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.TextLink
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType

/**
 * 第一次打开时的欢迎页。
 *
 * ── 这一页刻意不做的事 ──
 * 不做三屏滑动的功能介绍。用户装一个密码管理器是来干活的，
 * 不是来看幻灯片的；而且真正需要他记住的只有一条——**主密码丢了没人能救**——
 * 这条不该淹没在「安全可靠」「简洁美观」之类的套话里。
 *
 * 所以这一页只说三句能被核实的话：
 *   · 没有联网权限（用户可以立刻去系统设置里自己看）
 *   · 主密码不上传也不留副本（所以忘了就没了）
 *   · 整个库就是一个文件（所以换机就是拷一个文件）
 *
 * 每一句都对应着后面某个模块必须兑现的承诺，不是文案。
 */
@Composable
fun WelcomeScreen(
    onCreate: () -> Unit,
    onRestore: () -> Unit,
) {
    val info = LocalCryptoInfo.current

    VaultScreen(seal = { DefaultSeal() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            VaultIcon(Glyph.Shield, tint = VaultColors.Brass, size = 44.dp)
            Spacer(Modifier.height(18.dp))

            Text("本地保险库", style = VaultType.H1, color = VaultColors.Text)
            Spacer(Modifier.height(8.dp))
            Text(
                "账号和密码只以加密形式存在这台手机上。",
                style = VaultType.Body,
                color = VaultColors.Dim,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(34.dp))

            Promise(
                Glyph.Lock,
                "没有联网权限",
                "应用信息里的权限列表是空的。没有 INTERNET 权限，进程建不了任何连接，数据在技术上离不开这台设备。",
            )
            Promise(
                Glyph.Key,
                "主密码只在你脑子里",
                "它不上传、不留副本、也不做「找回」。忘了主密码，这个库谁也打不开——包括我们。",
            )
            Promise(
                Glyph.Share,
                "整个库就是一个文件",
                "换手机时导出一个加密文件拷过去即可，不需要账号，也不经过任何服务器。",
            )

            Spacer(Modifier.height(28.dp))

            BrassButton("创建保险库", onClick = onCreate)
            Spacer(Modifier.height(6.dp))
            TextLink("我已有 .lvault 备份文件", onClick = onRestore, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(18.dp))
            Text(
                // 封条上写的是同一个值。这行的作用是让用户在建库**之前**
                // 就看见真实档位，而不是事后在设置页里才发现降级了。
                if (info.argon2Available) "本机将使用 ${info.kdfLabel} 派生主密钥"
                else "本机未能加载 Argon2 原生库，将使用 ${info.kdfLabel}",
                style = VaultType.MonoSmall,
                color = VaultColors.Dimmer,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Promise(glyph: Glyph, title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        VaultIcon(glyph, tint = VaultColors.Brass, size = 22.dp, modifier = Modifier.padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = VaultType.H2, color = VaultColors.Text)
            Text(body, style = VaultType.Sub, color = VaultColors.Dimmer)
        }
    }
}
