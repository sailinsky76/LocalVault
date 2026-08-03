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

package cn.localvault.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import cn.localvault.app.ui.CryptoInfo
import cn.localvault.app.ui.ProvideVaultDeps
import cn.localvault.app.ui.nav.VaultRoot
import cn.localvault.app.ui.theme.LocalVaultTheme
import cn.localvault.app.ui.util.Fmt

/**
 * ── 为什么是 FragmentActivity 而不是 ComponentActivity ──
 *
 * `androidx.biometric.BiometricPrompt` 只接受 `FragmentActivity`：
 * 它内部要挂一个不可见的 Fragment 来接住配置变更和进程重建
 * （指纹弹窗弹着的时候转屏，认证结果得有地方回）。
 * 这不是可以绕开的实现细节——自己拿 `android.hardware.biometrics.BiometricPrompt`
 * 意味着要为 API 28 以下另写一套，还要自己处理厂商定制弹窗的一堆差异。
 *
 * `FragmentActivity` 本身就是 `ComponentActivity` 的子类，
 * `enableEdgeToEdge()` / `setContent` / `onStart` / `onStop` 全部照旧，
 * 代价只是多打包一个 androidx.fragment（biometric 本来就把它带进来了）。
 */
class MainActivity : FragmentActivity() {

    private val app: VaultApp get() = application as VaultApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 禁止截屏、禁止录屏，并让「最近任务」里的缩略图变成空白。
        // 必须在 setContent 之前，且永远不要为了录演示视频而临时注释掉。
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        enableEdgeToEdge()
        setContent {
            LocalVaultTheme {
                // 封条要显示的档位。
                //   · 已解锁 → 用**这个库文件头里**记的参数（换机拷过来的库可能是别的档位）；
                //   · 未解锁 → 用「本机新建库会用哪一档」兜底。
                //
                // 直接订阅会话里那个 StateFlow，不再拿相位当 remember 的 key：
                // 这个值会在**解锁期间**变（改主密码会重新校准档位），
                // 按相位缓存的话，封条要到下一次锁定—解锁才跟上，
                // 中间那段时间它写着一个这个库已经不用的档位。
                val headerParams by app.session.headerKdfParamsFlow.collectAsState()
                val kdfParams = headerParams ?: app.currentKdfParams()

                ProvideVaultDeps(
                    session = app.session,
                    repository = app.repository,
                    quickUnlock = app.quickUnlock,
                    clipboard = app.clipboard,
                    cryptoInfo = CryptoInfo(
                        argon2Available = app.argon2Available,
                        // 封条上显示的是**真实**参数：降级到 PBKDF2 时如实写 PBKDF2
                        kdfLabel = Fmt.kdfLabel(kdfParams),
                    ),
                ) {
                    VaultRoot()
                }
            }
        }
    }

    /**
     * 自动锁定的两个触发点。放在 Activity 而不是 ProcessLifecycleOwner，
     * 是为了少一个依赖；单 Activity 架构下两者等价。
     */
    override fun onStart() {
        super.onStart()
        app.session.onEnterForeground()
    }

    override fun onStop() {
        super.onStop()
        app.session.onEnterBackground()
        // 界面进后台就把倒计时中的剪贴板内容清掉？——不。
        // 「复制密码 → 切到浏览器粘贴」是最常见的路径，在这里清等于让功能失效。
        // 清除交给 SecureClipboard 自己的计时器，它挂在 Application 的 scope 上。
    }
}
