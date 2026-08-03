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

package cn.localvault.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import cn.localvault.app.core.keystore.QuickUnlock
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.ui.util.SecureClipboard

/**
 * 依赖通过 CompositionLocal 往下传，而不是一层层塞进每个 Composable 的参数。
 *
 * 和 [cn.localvault.app.VaultApp] 里手工拼装依赖是同一个思路：
 * 对象图小到一眼能看完，不值得为它引入 Hilt 的注解处理器和反射。
 * 用 `staticCompositionLocalOf` 是因为这几个对象在整个 App 生命周期里不会变，
 * 换成 `compositionLocalOf` 只会白白多一层订阅开销。
 */

val LocalSession = staticCompositionLocalOf<VaultSession> {
    error("VaultSession 未提供：请在 VaultRoot 里用 ProvideVaultDeps 包起来")
}

val LocalRepository = staticCompositionLocalOf<VaultRepository> {
    error("VaultRepository 未提供")
}

val LocalQuickUnlock = staticCompositionLocalOf<QuickUnlock> {
    error("QuickUnlock 未提供")
}

val LocalClipboard = staticCompositionLocalOf<SecureClipboard> {
    error("SecureClipboard 未提供")
}

val LocalCryptoInfo = staticCompositionLocalOf<CryptoInfo> {
    error("CryptoInfo 未提供")
}

/**
 * 顶部封条上要显示的东西。
 *
 * [argon2Available] 为 false 时封条必须如实显示 PBKDF2 —— 见 Seal.kt 的第 1 条规矩。
 * 用户有权知道自己这台设备上跑的到底是哪一档。
 */
@Immutable
data class CryptoInfo(
    val argon2Available: Boolean,
    val kdfLabel: String,
    val cipherLabel: String = "AES-256-GCM",
)

@Composable
fun ProvideVaultDeps(
    session: VaultSession,
    repository: VaultRepository,
    quickUnlock: QuickUnlock,
    clipboard: SecureClipboard,
    cryptoInfo: CryptoInfo,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSession provides session,
        LocalRepository provides repository,
        LocalQuickUnlock provides quickUnlock,
        LocalClipboard provides clipboard,
        LocalCryptoInfo provides cryptoInfo,
        content = content,
    )
}
