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

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.LocalQuickUnlock
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.ExplainNote
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.ToggleRow
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.components.explain
import cn.localvault.app.ui.settings.QuickUnlockModel.BiometricSupport
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType
import cn.localvault.app.ui.unlock.rememberBiometricEnroller

/**
 * 快捷解锁的绑定页（`Route.SETTINGS_SECURITY`）。
 *
 * M3-2c-2 交付时，解锁侧（指纹按一下就开门）已经全部接通，
 * 但**开启的入口一直不存在**——只能靠手动造一份绑定才能验证那条路。
 * 这一页就是那个缺口。
 *
 * ── 为什么不做一个「快捷解锁」总开关 ──
 *
 * 指纹和 PIN 是两条独立的路，安全性质也不一样（一个由安全硬件限速，
 * 一个由我们的退避限速），用户完全可能只想开其中一条。
 * 一个总开关要么把两条绑在一起，要么就变成「总开关 + 两个子开关」——
 * 后者多出一个中间态（总开关开着但两条都关着），
 * 而那个状态在屏幕上是解释不清楚的。
 *
 * ── 为什么开启之前不再要求输一次主密码 ──
 *
 * 常见的做法是「改安全设置前先重新验证身份」。这一页不这么做，理由是它在这儿
 * 挡不住任何人：走到这一页说明保险库**已经是解锁状态**，
 * 能走到这儿的人已经能看到里面每一条密码了，再验一次主密码保护的是什么呢。
 * 真正的门槛在别处——开启指纹必须当场通过一次**系统的**生物识别，
 * 而那一枚指纹不是拿着这台手机就能凭空录进去的。
 * （改主密码是另一回事，那个动作会让旧备份失效，所以 M3-6c 会要求验证。）
 */
@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    /**
     * 去 PIN 设置流。[change] 为 true 表示已经设过一个、这次是换一个。
     *
     * **刻意没有默认值**，理由同 M3-6b-1 给 `SettingsScreen` 加 `onSecurity` 时那一条：
     * 这一页上多出来的是一个能点的入口，如果它的跳转参数可以被省略，
     * 某天有人复制一份调用忘了传，那一行就会变成点了没反应的死行，而编译器一声不吭。
     * 让它编译不过更省事。
     */
    onSetupPin: (change: Boolean) -> Unit,
) {
    val session = LocalSession.current
    val quick = LocalQuickUnlock.current
    val state by session.state.collectAsState()
    val context = LocalContext.current

    // 锁定那一瞬间导航相位会换掉整棵子树，但这一帧可能还会被画一次。
    // 同列表页 / 设置页的处理：与其去读一个刚被清空的会话，不如什么都不画。
    if (state !is VaultSession.State.Unlocked) return

    /**
     * `QuickUnlock` 的状态存在 SharedPreferences 里，不是可观察的。
     * 这个计数器就是「重新去问一遍」的信号：绑定成功、解绑、或者从系统设置
     * 回到这一页时 +1，读取的地方以它为 key。
     *
     * 不做成 StateFlow 是因为这一页是唯一会改它的地方，
     * 为一个只有一个读者的值铺一套订阅机制，属于给自己找活干。
     */
    var revision by remember { mutableIntStateOf(0) }

    /**
     * 从系统设置回来时重新问一遍。
     *
     * 这条路是真会发生的：用户看到「这台设备还没有录入指纹」，
     * 点「去系统设置录入」，录完按返回键回来——如果不重新查一次，
     * 他会看到那个开关**还是灰的**，然后合理地认为这个应用坏了。
     */
    val activity = remember(context) { context.findComponentActivity() }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) revision++
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    val support = remember(revision) { quick.biometricAvailability().toSupport() }
    val bioEnrolled = remember(revision) { quick.isBiometricEnrolled }
    val row = remember(support, bioEnrolled) {
        QuickUnlockModel.biometricRow(support, bioEnrolled)
    }

    /*
     * PIN 那一行。它不需要 `support` 这一类东西——PIN 不依赖任何传感器，
     * 用的也不是「每次使用都要认证」的那把 Keystore 钥匙，
     * 所以它没有「设备支持度」这个维度，也就没有需要解释的异常状态。
     * 见 PinSetupModel.PinRow 上面那段。
     *
     * 从 PIN 设置流返回时这一行会自己刷新：导航切走时这一页整棵子树被 dispose，
     * 回来时 `remember` 重新算一遍，于是又去问了一次 prefs。
     * （从**系统设置**回来是另一条路，那条靠上面那个 ON_START 观察者。）
     */
    val pinEnrolled = remember(revision) { quick.isPinEnrolled }
    val pinRow = remember(pinEnrolled) { PinSetupModel.pinRow(pinEnrolled) }

    var failure by remember { mutableStateOf<String?>(null) }

    val enroll = rememberBiometricEnroller(
        quickUnlock = quick,
        session = session,
        onEnrolled = {
            failure = null
            revision++
        },
        onFailure = { f ->
            // 取消返回 null —— 用户改主意不是错误，不该弹红条（同解锁侧）。
            failure = QuickUnlockModel.enrollFailureMessage(f)
            // 不管成没成，开关都要跟着真实状态走一遍：
            // 失败时它必须弹回「关」，否则界面在说一件没发生的事。
            revision++
        },
    )

    VaultScreen(title = "快捷解锁", onBack = onBack, seal = { DefaultSeal() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            /*
             * 页顶那段原来是两个整段（安全芯片怎么包、主密码为什么仍是真凭据），
             * 加起来六七行，把第一个开关顶到了半屏以下——而这一页要做的事
             * 只有拨那两个开关。短版留三句判断，完整那两段进弹窗（v4）。
             */
            ExplainNote(
                QuickUnlockModel.INTRO_SHORT,
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                detailTitle = QuickUnlockModel.INTRO_DETAIL_TITLE,
                detail = explain(QuickUnlockModel.INTRO),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )

            if (failure != null) {
                Banner(failure!!, tone = BannerTone.Danger)
            }

            Eyebrow("指纹", modifier = Modifier.padding(start = 4.dp, top = 8.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    ToggleRow(
                        title = "用指纹解锁",
                        subtitle = row.subtitle,
                        checked = row.checked,
                        // 没有 Activity（理论上不该发生）时 enroll 为 null，
                        // 那就不该让开关看起来能开——点了没反应比灰着更糟（决策(61)）。
                        enabled = row.enabled && (row.checked || enroll != null),
                        onCheckedChange = { want ->
                            failure = null
                            if (want) {
                                enroll?.invoke()
                            } else {
                                /**
                                 * 关闭**不弹确认**。
                                 *
                                 * 确认弹窗是留给不可逆动作的（删条目、删库）。
                                 * 关掉指纹解锁完全可逆——重新打开就是再按一次指纹的事，
                                 * 而且主密码从头到尾都能开门，什么都不会丢。
                                 * 给一个可逆的小动作配弹窗，用户学到的是「这个应用什么都要问」，
                                 * 等到真正危险的那个弹窗出现时，他已经养成了直接点确认的习惯。
                                 */
                                quick.disableBiometric()
                                revision++
                            }
                        },
                    )

                    /*
                     * 说明单独画，不塞给 ToggleRow 的 `note`：
                     * 那个参数**只在开关被禁用时**才会替换掉副标题（见 Toggle.kt），
                     * 而这一页上「已绑定但指纹库变过了」这种情况开关是能动的，
                     * 走那条路的话最要紧的一句话会被悄悄吞掉。
                     */
                    val noteShort = row.noteShort
                    if (noteShort != null) {
                        // 平铺短版，完整那段挂在链接后面。长短一样时不画链接
                        // （这一行绝大多数档位本来就只有一两行，见 BiometricRow.noteShort）。
                        ExplainNote(
                            noteShort,
                            style = VaultType.Sub,
                            color = if (row.checked && row.enabled) VaultColors.Brass
                            else VaultColors.Dimmer,
                            detailTitle = "关于指纹解锁",
                            detail = row.note
                                ?.takeIf { it != noteShort }
                                ?.let { explain(it) }
                                ?: emptyList(),
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }

                    if (row.showEnrollHint) {
                        Spacer(Modifier.height(2.dp))
                        GhostButton(
                            "去系统设置录入指纹",
                            onClick = {
                                // 又一次可信中断：跳出去录指纹可能要花上一分钟，
                                // 不打开宽限的话回来时库已经锁了（决策⑳）。
                                session.beginSystemInterlude()
                                context.openBiometricEnrollSettings()
                            },
                            tint = VaultColors.Dim,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Eyebrow("PIN", modifier = Modifier.padding(start = 4.dp, top = 8.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    ToggleRow(
                        title = "用 6 位 PIN 解锁",
                        subtitle = pinRow.subtitle,
                        checked = pinRow.checked,
                        // 这一行永远能点：PIN 没有「这台设备支不支持」的问题。
                        onCheckedChange = { want ->
                            failure = null
                            if (want) {
                                // 打开 = 去设置流。开关此刻**不动**——
                                // 真正打开它的是设置流走完之后 prefs 里多出来的那份包裹。
                                // 先把开关拨到「开」再去设置，用户中途退出就会看到
                                // 一个开着但其实没设成的开关（决策(100) 的另一面）。
                                onSetupPin(false)
                            } else {
                                /**
                                 * 关闭同样**不弹确认**（决策(103)）。
                                 * 关掉 PIN 完全可逆——重新设一个就是按十二下的事，
                                 * 而主密码从头到尾都能开门，什么都不会丢。
                                 */
                                quick.disablePin()
                                revision++
                            }
                        },
                    )

                    if (pinRow.changeText != null) {
                        Spacer(Modifier.height(2.dp))
                        GhostButton(
                            pinRow.changeText,
                            /*
                             * 「修改 PIN」**不要求先输一遍旧 PIN**，理由见
                             * PinSetupModel.Mode 上面那段：这一页是解锁态才到得了的，
                             * 旧 PIN 在这儿挡不住任何人，却正好挡住了最需要改 PIN 的那个人
                             * ——快记不住现在这个的人。
                             */
                            onClick = { onSetupPin(true) },
                            tint = VaultColors.Dim,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            if (!row.checked && !pinRow.checked) {
                Text(
                    QuickUnlockModel.NONE_ENABLED_NOTE,
                    style = VaultType.Sub,
                    color = VaultColors.Dimmer,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ─────────────────────── 碰 Android 的那一侧 ─────────────────────── */

/**
 * 平台返回码 → 语义。**只有这一个 `when`**，判断全在 [QuickUnlockModel] 里，
 * 那边是纯 Kotlin，能在 JVM 上钉死。和解锁侧 `classifyBiometricError` 的分工一致。
 */
private fun Int.toSupport(): BiometricSupport = when (this) {
    BiometricManager.BIOMETRIC_SUCCESS -> BiometricSupport.Ready
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricSupport.NoneEnrolled
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricSupport.NoHardware
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricSupport.TemporarilyUnavailable
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricSupport.NeedsSecurityUpdate
    // BIOMETRIC_STATUS_UNKNOWN 和将来可能新增的码都落到这里。
    // 归到 Unknown 而不是「不支持」是有意的，见 QuickUnlockModel.biometricRow 的注释。
    else -> BiometricSupport.Unknown
}

/**
 * 跳到系统的指纹录入界面。
 *
 * `ACTION_BIOMETRIC_ENROLL` 是 API 30 才有的，低版本退回安全设置总页——
 * 那儿也找得到指纹，只是要多点两下。两个都拉不起来时**什么都不做**：
 * 那句「请在系统设置里录一枚指纹」已经写在按钮上面了，
 * 用户照着做完全走得通，没必要为此弹一条「打不开系统设置」的错误来添乱。
 *
 * 这个 Intent **不需要任何权限**，关于页那份权限清单（只有 `USE_BIOMETRIC` 一条）
 * 不会因为它变长——这是这一页能加这个按钮的前提。
 */
private fun android.content.Context.openBiometricEnrollSettings() {
    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG,
                )
            )
        }
        add(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }
    for (intent in intents) {
        if (runCatching { startActivity(intent) }.isSuccess) return
    }
}

/**
 * 取 Activity 的 lifecycle，用来在「从系统设置回来」这一刻重新查一次指纹状态。
 *
 * 刻意不用 `LocalLifecycleOwner`：它在 compose-ui 和 lifecycle-runtime-compose
 * 两个包里各有一份，前者已被弃用、后者要多加一个依赖。
 * 而这一页真正需要的只是「这个 Activity 的 lifecycle」，
 * 从 Context 里剥出来就有，不必为此动依赖表。
 *
 * M4-4a 起它**不再是 private**：`AutofillSettingsScreen` 要用同一套
 * 「从系统设置回来时重新问一遍」，而那一页和这一页在同一个包里。
 * 复制一份到那边是另一个选择，但两份 tailrec 的剥壳逻辑迟早会走样，
 * 而走样的表现是「有的页面从系统回来会刷新，有的不会」——最难查的那一类。
 */
internal tailrec fun android.content.Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
