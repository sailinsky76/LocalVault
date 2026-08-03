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

package cn.localvault.app.ui.unlock

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import cn.localvault.app.core.crypto.SecureBytes
import cn.localvault.app.core.keystore.BiometricKeyInvalidatedException
import cn.localvault.app.core.keystore.KeystoreFailure
import cn.localvault.app.core.keystore.KeystoreUnavailableException
import cn.localvault.app.core.keystore.QuickUnlock

/**
 * 把 M2 里悬了很久的那两个方法接上：
 * `QuickUnlock.beginBiometricUnlock()` 拿到一个待认证的 `Cipher`，
 * 塞进 `CryptoObject` 交给系统弹窗，用户按完指纹之后再用**同一个已解锁的 Cipher**
 * 去 `finishBiometricUnlock()` 解出库主密钥。
 *
 * ── 这里最要紧的一句：库主密钥是被安全硬件解出来的，不是被我们「验证通过后放行」的 ──
 *
 * 很多应用的指纹解锁是这样写的：弹个指纹框，回调说成功了，就把密钥从某处读出来。
 * 那种写法里指纹只是一道**界面上的关卡**——把 APK 改一行让回调直接返回成功，
 * 密钥照样到手。这里不是：那份包裹是用 Keystore 里「每次使用都要认证」的钥匙加密的，
 * 没有真的通过认证，`cipher.doFinal()` 根本算不出结果。
 * 所以这段代码的正确性不取决于我们有没有写对判断，取决于安全硬件。
 *
 * ── 刻意不允许「设备锁屏凭据」作为回退 ──
 *
 * `setAllowedAuthenticators` 只给 `BIOMETRIC_STRONG`，不加 `DEVICE_CREDENTIAL`。
 * 加上它确实更方便，但代价是把整个保险库的强度降到手机锁屏密码那一档——
 * 而锁屏密码常常是 4 位、经常在公共场合当着人输、家人多半也知道。
 * 我们的回退是**自己的主密码**，那是用户专门为这个库设的、只用于这一个地方的凭据。
 */

/**
 * 取一个「弹指纹框」的动作。
 *
 * 返回 null 表示这条路现在走不通（没绑过、没有 FragmentActivity、硬件不可用），
 * 调用方据此**根本不要画那个按钮**——让用户对着一个注定失败的按钮反复按，
 * 比没有这个按钮更伤。
 */
@Composable
fun rememberBiometricUnlocker(
    quickUnlock: QuickUnlock,
    onKey: (SecureBytes) -> Unit,
    onFailure: (BiometricFailure) -> Unit,
    /**
     * 「重新问一遍系统」的信号。值一变就重新查一次 `canAuthenticate()`。
     *
     * 为什么需要它：下面那个 `usable` 是缓存的（`canAuthenticate` 是跨进程调用，
     * 而退避倒计时期间这一屏每半秒重组一次）。但它缓存的答案**是会变的**——
     * 刚从后台回到前台的那一瞬间，系统可能还没把指纹传感器交接完，
     * 这时候问它会得到「不可用」。只问一次的后果是：那一屏从生到死都认为
     * 指纹用不了，连按钮都不画，而用户切出去再切回来就好了。
     *
     * 调用方传一个「回到前台」的标志进来即可（见 `QuickUnlockScreen`）。
     */
    probe: Any = Unit,
): (() -> Unit)? {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }

    // 用 rememberUpdatedState 而不是把回调塞进 remember 的 key：
    // 每次重组都换一个新的 BiometricPrompt，会让正在显示的系统弹窗被回收掉。
    val currentOnKey by rememberUpdatedState(onKey)
    val currentOnFailure by rememberUpdatedState(onFailure)

    // canAuthenticate() 是一次 binder 调用。退避倒计时期间这一屏每半秒重组一次，
    // 不缓存的话就是每秒两次跨进程查询——查的还是一个几乎不会变的答案。
    val usable = remember(activity, quickUnlock, probe) {
        activity != null && quickUnlock.isBiometricEnrolled && quickUnlock.isBiometricUsable
    }
    if (activity == null || !usable) return null

    val prompt = remember(activity, quickUnlock) {
        val executor = ContextCompat.getMainExecutor(activity)
        BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher
                if (cipher == null) {
                    // 没有 CryptoObject 就意味着这次认证没有绑定任何密码学操作，
                    // 那就只是「界面上点了个头」。宁可失败也不能把它当成解锁成功。
                    Log.w(TAG_UNLOCK, "认证通过但没有 CryptoObject，拒绝当成解锁成功")
                    currentOnFailure(BiometricFailure.Other)
                    return
                }
                val key = try {
                    quickUnlock.finishBiometricUnlock(cipher)
                } catch (t: Throwable) {
                    Log.w(TAG_UNLOCK, "认证通过之后解不出库主密钥", t)
                    currentOnFailure(BiometricFailure.Other)
                    return
                }
                currentOnKey(key)
            }

            override fun onAuthenticationError(code: Int, errString: CharSequence) {
                currentOnFailure(classifyBiometricError(code))
            }

            // onAuthenticationFailed（这一次没认出来）刻意不覆写：
            // 系统弹窗自己会提示「请重试」，我们再弹一条只会互相打架，
            // 而且它不消耗任何猜测机会——手指没放正不该产生任何后果。
        })
    }

    val info = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁保险库")
            .setSubtitle("用指纹打开，或改用主密码")
            .setNegativeButtonText("用主密码")
            // 认证成功后不要求再点一次确认：这一步没有任何危险动作，
            // 多一次点击只是让「快捷解锁」不快捷。
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
    }

    return {
        try {
            val cipher = quickUnlock.beginBiometricUnlock()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (e: BiometricKeyInvalidatedException) {
            // 指纹库变过了。QuickUnlock 在抛出之前已经把那份包裹删掉了，
            // 这里只负责把话说清楚：不是故障，数据也没丢。
            currentOnFailure(BiometricFailure.KeyInvalidated)
        } catch (t: Throwable) {
            // 同绑定侧（`BiometricEnroll.classifyEnrollThrowable`）：这个 catch 罩着的是
            // 「去 Keystore 取解密 Cipher」和「拉起指纹框」，失败原因不止一种，
            // 无条件说「硬件不可用」是在猜，而且猜错的那几种恰好是最需要说清的。
            Log.w(TAG_UNLOCK, "指纹解锁在拉起指纹框之前就失败了", t)
            currentOnFailure(
                when ((t as? KeystoreUnavailableException)?.failure) {
                    KeystoreFailure.NoSecureCredential -> BiometricFailure.NoneEnrolled
                    KeystoreFailure.KeyInvalidated -> BiometricFailure.KeyInvalidated
                    // 「设备被认为锁着」是有可能过一会儿就好的，归到可重试那一档。
                    KeystoreFailure.DeviceLocked -> BiometricFailure.HardwareBusy
                    else -> BiometricFailure.Other
                }
            )
        }
    }
}

private const val TAG_UNLOCK = "BiometricUnlock"

/**
 * 平台错误码 → 语义分类。刻意只有一个 when，判断全在 [BiometricPolicy] 里，
 * 那边是纯 Kotlin，能在 JVM 上测。
 */
private fun classifyBiometricError(code: Int): BiometricFailure = when (code) {
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_USER_CANCELED,
    // 超时归到「用户取消」：那说明人走开了，不是出了错。
    // 回来看到一条红色错误只会让他以为刚才哪里按坏了。
    BiometricPrompt.ERROR_TIMEOUT,
    -> BiometricFailure.UserCanceled

    BiometricPrompt.ERROR_LOCKOUT -> BiometricFailure.TemporaryLockout
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricFailure.PermanentLockout

    BiometricPrompt.ERROR_NO_BIOMETRICS -> BiometricFailure.NoneEnrolled

    // 这两个码**不能**归成一种：前者是「这台机器没有传感器」，
    // 后者是「传感器现在腾不出手」。见 BiometricFailure.HardwareBusy 上面那段。
    BiometricPrompt.ERROR_HW_NOT_PRESENT -> BiometricFailure.HardwareUnavailable
    BiometricPrompt.ERROR_HW_UNAVAILABLE -> BiometricFailure.HardwareBusy

    else -> BiometricFailure.Other
}

/**
 * `BiometricPrompt` 只认 `FragmentActivity`（它内部要挂一个 Fragment 来接生命周期），
 * 而 Compose 给的 `LocalContext` 在有些封装下是 `ContextWrapper`，得一层层剥。
 *
 * 这也是 `MainActivity` 从 `ComponentActivity` 改成 `FragmentActivity` 的原因，
 * 见那个文件里的注释。
 */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
