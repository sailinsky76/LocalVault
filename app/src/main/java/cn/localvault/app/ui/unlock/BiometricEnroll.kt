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
import android.security.keystore.KeyPermanentlyInvalidatedException
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
import cn.localvault.app.core.keystore.KeystoreFailure
import cn.localvault.app.core.keystore.KeystoreUnavailableException
import cn.localvault.app.core.keystore.QuickUnlock
import cn.localvault.app.core.session.VaultSession

/**
 * 指纹**绑定**侧的接线。解锁侧在 [rememberBiometricUnlocker]（`BiometricUnlock.kt`），
 * 两侧长得很像，但有三处关键差别，值得单开一个文件而不是加一个 `enroll: Boolean` 参数：
 *
 *   1. **方向相反**：解锁是 `beginBiometricUnlock()` 拿解密 Cipher，
 *      绑定是 `beginBiometricEnrollment()` 拿加密 Cipher；
 *   2. **绑定需要库主密钥**，所以只能在已解锁的时候做，
 *      而且要走 [VaultSession.withVaultKey]（拿不到长期引用，见那边的注释）；
 *   3. **文案落点不同**：解锁失败的落点是「请用主密码解锁」，
 *      绑定失败的落点是「这次没绑上，库和数据都没受影响」——
 *      用户此刻已经在库里了，跟他说「请用主密码打开」是句糊涂话。
 *      这一份在 `QuickUnlockModel.enrollFailureMessage`。
 *
 * ── 和解锁侧共享的那条最要紧的性质 ──
 *
 * 库主密钥是**被安全硬件加密的**，不是「我们判断认证通过了才去加密」。
 * 认证没真的过，`cipher.doFinal()` 根本算不出结果。
 * 所以这段代码的正确性不取决于我们有没有写对判断，取决于安全硬件。
 *
 * ── 同样刻意不允许「设备锁屏凭据」作为回退 ──
 *
 * `setAllowedAuthenticators` 只给 `BIOMETRIC_STRONG`。加上 `DEVICE_CREDENTIAL`
 * 会让整个保险库的强度掉到手机锁屏密码那一档——而锁屏密码常常是 4 位、
 * 经常当着人输、家人多半也知道。这条在绑定侧尤其要守住：
 * 解锁侧放宽了顶多是这一次进门容易了，绑定侧放宽了是**从此**都容易了。
 */
@Composable
fun rememberBiometricEnroller(
    quickUnlock: QuickUnlock,
    session: VaultSession,
    onEnrolled: () -> Unit,
    onFailure: (BiometricFailure) -> Unit,
): (() -> Unit)? {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivityForEnroll() }

    val currentOnEnrolled by rememberUpdatedState(onEnrolled)
    val currentOnFailure by rememberUpdatedState(onFailure)

    if (activity == null) return null

    val prompt = remember(activity, quickUnlock, session) {
        val executor = ContextCompat.getMainExecutor(activity)
        BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // 系统界面回来了，可信中断的宽限可以立刻收掉（同导出流程）。
                session.endSystemInterlude()

                val cipher = result.cryptoObject?.cipher
                if (cipher == null) {
                    // 没有 CryptoObject 意味着这次认证没有绑定任何密码学操作，
                    // 那就只是「界面上点了个头」。宁可绑定失败，
                    // 也不能存下一份没有真正经过安全硬件的包裹——
                    // 那种包裹在解锁时是打不开的，用户要到下次开门才会发现。
                    Log.w(TAG_ENROLL, "认证通过但没有 CryptoObject，拒绝写入包裹")
                    currentOnFailure(BiometricFailure.Other)
                    return
                }
                try {
                    session.withVaultKey { key ->
                        quickUnlock.finishBiometricEnrollment(cipher, key)
                    }
                } catch (t: Throwable) {
                    // 半途失败时把残留清掉：留下一份写了一半的包裹，
                    // 表现是「开关显示已开启，但每次解锁都失败」——
                    // 那是最难被用户理解的一种状态。
                    Log.w(TAG_ENROLL, "认证通过之后写包裹失败，清掉残留", t)
                    runCatching { quickUnlock.disableBiometric() }
                    currentOnFailure(classifyEnrollThrowable(t))
                    return
                }
                currentOnEnrolled()
            }

            override fun onAuthenticationError(code: Int, errString: CharSequence) {
                session.endSystemInterlude()
                currentOnFailure(classifyEnrollError(code))
            }

            // onAuthenticationFailed（这一次没认出来）同样不覆写：
            // 系统弹窗自己会说「请重试」，我们再弹一条只会互相打架。
        })
    }

    val info = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("开启指纹解锁")
            // 说清楚这一步在做什么。用户在设置页里按下开关之后弹出一个指纹框，
            // 如果只写「请验证指纹」，他不知道自己是在授权还是在被抽查。
            .setSubtitle("用这枚指纹为这台设备绑定保险库")
            .setNegativeButtonText("取消")
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
    }

    return {
        try {
            // 拉起系统界面之前打开可信中断，否则把自动锁定设成「立即」的用户
            // **永远绑不上指纹**：指纹框一弹，Activity 走 onStop，库当场锁掉，
            // 按完指纹回来时 withVaultKey 已经没有密钥可借了。
            // 这条和导出备份踩的是同一个坑（决策⑳）。
            session.beginSystemInterlude()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(freshEnrollCipher(quickUnlock)))
        } catch (t: Throwable) {
            session.endSystemInterlude()
            /*
             * ⚠ 这里上一版写的是 `currentOnFailure(BiometricFailure.HardwareUnavailable)`
             *   —— 一个无条件的猜测，而且是错的那一种猜测。
             *
             * 这个 try 罩着的是**拉起指纹框之前**的两件事：
             * 打开可信中断，和去 Keystore 取一个待认证的 Cipher。
             * 后者会失败的原因至少有四种（安全硬件拒收规格、系统里没录指纹、
             * 钥匙已作废、设备被认为锁着），其中只有一种和「传感器」有关。
             *
             * 于是真机上的表现就是：任何一种 Keystore 侧的问题，
             * 用户都会看到「指纹传感器暂时不可用，过一会儿再试」，
             * 然后过一会儿再试，再看到同一句话——而传感器一直是好的。
             *
             * 现在按真实原因分类，并且**一定**把原始异常写进日志。
             */
            Log.w(TAG_ENROLL, "指纹绑定在拉起指纹框之前就失败了", t)
            currentOnFailure(classifyEnrollThrowable(t))
        }
    }
}

private const val TAG_ENROLL = "BiometricEnroll"

/**
 * 拉起指纹框**之前**抛出来的异常 → 语义分类。
 *
 * 和 [classifyEnrollError]（系统回调里的错误码）分开是必要的：那一份认的是
 * `BiometricPrompt` 的错误码，这一份认的是 Keystore 抛出来的异常，
 * 两套东西没有交集，硬塞进一个函数只会让两边都读不懂。
 *
 * 具体的「这条异常链到底在说什么」交给 [KeystoreFailure.classify]——
 * 那是纯 Kotlin，能在 JVM 上钉死（`KeystoreDiagnosisTest`）。
 * 这里只负责把它的答案翻译成这一页认识的 [BiometricFailure]。
 */
private fun classifyEnrollThrowable(t: Throwable): BiometricFailure = when {
    t is KeyPermanentlyInvalidatedException -> BiometricFailure.KeyInvalidated

    t is KeystoreUnavailableException -> when (t.failure) {
        // 系统里一枚指纹都没录 → 让用户去录，别让他在这儿反复重试。
        KeystoreFailure.NoSecureCredential -> BiometricFailure.NoneEnrolled
        KeystoreFailure.KeyInvalidated -> BiometricFailure.KeyInvalidated
        // 安全硬件拒收了规格 / 设备被认为锁着。**都不是传感器的问题**，
        // 所以不能落到 HardwareUnavailable——那句话会把用户支到一条走不通的路上
        // （「过一会儿再试」），而这两种情况过一会儿也不会变。
        KeystoreFailure.SpecRejected,
        KeystoreFailure.DeviceLocked,
        KeystoreFailure.Unknown,
        -> BiometricFailure.Other
    }

    else -> BiometricFailure.Other
}

/**
 * 取一个干净的加密 Cipher。
 *
 * `KeystoreKeys.getOrCreateAuthRequiredKey()` 是「有就用、没有才建」，
 * 于是一把**已经作废**的旧钥匙（用户中途删过指纹）会被原样复用，
 * 而 `Cipher.init` 会当场抛 [KeyPermanentlyInvalidatedException]。
 * 这时候删掉它重建一把就行——我们本来就是要覆盖旧包裹，没有任何东西会丢。
 *
 * 只重试一次：第二次还失败就是真出问题了，再循环下去只会把错误藏起来。
 */
private fun freshEnrollCipher(quickUnlock: QuickUnlock) = try {
    quickUnlock.beginBiometricEnrollment()
} catch (e: KeyPermanentlyInvalidatedException) {
    Log.i(TAG_ENROLL, "旧钥匙已作废，删掉重建一把")
    quickUnlock.disableBiometric()
    quickUnlock.beginBiometricEnrollment()
}

/**
 * 平台错误码 → 语义分类。和解锁侧的 `classifyBiometricError` 分法一致，
 * 只有一处不同：**超时不归到「用户取消」**。
 *
 * 解锁页上超时说明人走开了，回来看到一条红字只会让他以为哪里按坏了。
 * 但绑定是用户刚刚亲手按下开关触发的：超时意味着他确实没按上指纹，
 * 而开关此刻已经弹回了「关」——不给一句解释的话，
 * 他看到的是「我明明打开了它，它自己又关上了」。
 */
private fun classifyEnrollError(code: Int): BiometricFailure = when (code) {
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_USER_CANCELED,
    -> BiometricFailure.UserCanceled

    BiometricPrompt.ERROR_TIMEOUT -> BiometricFailure.Other

    BiometricPrompt.ERROR_LOCKOUT -> BiometricFailure.TemporaryLockout
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricFailure.PermanentLockout

    BiometricPrompt.ERROR_NO_BIOMETRICS -> BiometricFailure.NoneEnrolled

    BiometricPrompt.ERROR_HW_NOT_PRESENT -> BiometricFailure.HardwareUnavailable
    BiometricPrompt.ERROR_HW_UNAVAILABLE -> BiometricFailure.HardwareBusy

    else -> BiometricFailure.Other
}

/** 同 `BiometricUnlock.kt` 里那个：`BiometricPrompt` 只认 `FragmentActivity`。 */
private tailrec fun Context.findFragmentActivityForEnroll(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivityForEnroll()
    else -> null
}
