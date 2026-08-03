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

import cn.localvault.app.core.keystore.KeystoreFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `KeystoreFailure.classify` 的用例。
 *
 * 这一份存在的理由，就是这次两个 bug 的病根：
 * `KeystoreKeys` 的降级判断只认一种异常（`StrongBoxUnavailableException`），
 * 而真机上安全硬件拒收规格时抛的是另外五六种东西——
 * 那些情况没有任何一条能在 JVM 上造出来，于是也就一条都没被测过。
 *
 * 现在判断本身按**类名和消息文本**做，于是可以直接喂字符串：
 * 下面每一条 raw 都是真机日志里出现过的形状（或者 AOSP 源码里的原话）。
 * 造不出异常对象不再是「测不了」的理由。
 */
class KeystoreDiagnosisTest {

    private fun check(expected: KeystoreFailure, raw: String) =
        assertEquals(raw, expected, KeystoreFailure.classifyText(raw))

    /* ══════════════ 缺凭据：不该降级，该让用户去录指纹 ══════════════ */

    @Test
    fun `没录指纹时要求认证的钥匙建不出来`() {
        check(
            KeystoreFailure.NoSecureCredential,
            "java.security.InvalidAlgorithmParameterException|" +
                "java.lang.IllegalStateException: At least one biometric must be enrolled " +
                "to create keys requiring user authentication for every use|"
        )
    }

    @Test
    fun `没设锁屏密码`() {
        check(
            KeystoreFailure.NoSecureCredential,
            "java.security.InvalidAlgorithmParameterException|" +
                "Secure lock screen must be enabled to create keys requiring user authentication|"
        )
    }

    /**
     * 这一条是整份用例里最要紧的一条。
     *
     * 「缺凭据」的异常链上同时带着 `InvalidAlgorithmParameterException`
     * 这个 `SpecRejected` 的特征词——如果判断顺序写反了，它就会被当成
     * 「规格被拒」，于是一路降级到最低档、仍然失败，最后给用户一句
     * 「这台设备的安全硬件用不了」——而他真正需要听到的是「去录一枚指纹」。
     */
    @Test
    fun `缺凭据的判断要压过规格被拒`() {
        check(
            KeystoreFailure.NoSecureCredential,
            "java.security.ProviderException|" +
                "android.security.KeyStoreException: no enrolled biometrics|" +
                "java.security.InvalidAlgorithmParameterException|"
        )
    }

    /* ══════════════ 钥匙作废 ══════════════ */

    @Test
    fun `指纹库变过之后钥匙作废`() {
        check(
            KeystoreFailure.KeyInvalidated,
            "android.security.keystore.KeyPermanentlyInvalidatedException|Key permanently invalidated|"
        )
    }

    @Test
    fun `钥匙读不出来也算作废`() {
        check(
            KeystoreFailure.KeyInvalidated,
            "java.security.UnrecoverableKeyException|Failed to obtain information about key|"
        )
    }

    /* ══════════════ 设备被认为锁着 ══════════════ */

    @Test
    fun `设备锁定时带unlockedDeviceRequired的钥匙不能用`() {
        check(
            KeystoreFailure.DeviceLocked,
            "android.security.KeyStoreException: Device locked (internal Keystore code: -26)|"
        )
    }

    /* ══════════════ 规格被拒：该降级 ══════════════ */

    @Test
    fun `没有StrongBox`() {
        check(
            KeystoreFailure.SpecRejected,
            "android.security.keystore.StrongBoxUnavailableException|" +
                "Failed to generate key pair, StrongBox is not available|"
        )
    }

    /**
     * StrongBox **在**，但不认识我们要求的某个属性。
     *
     * 抛出来的是一个泛泛的 `ProviderException`，**不是**
     * `StrongBoxUnavailableException`——上一版的降级判断在这里失灵，
     * 于是这台机器上指纹绑定和 PIN 设置双双报废。
     */
    @Test
    fun `StrongBox拒收规格时抛的是泛泛的ProviderException`() {
        check(
            KeystoreFailure.SpecRejected,
            "java.security.ProviderException|" +
                "android.security.KeyStoreException: Keystore operation failed " +
                "(internal Keystore code: -68 message: HARDWARE_TYPE_UNAVAILABLE)|"
        )
    }

    @Test
    fun `安全芯片没实现这个属性`() {
        check(
            KeystoreFailure.SpecRejected,
            "java.security.ProviderException|" +
                "android.security.KeyStoreException: Unimplemented (internal Keystore code: -38)|"
        )
    }

    @Test
    fun `init阶段的参数校验失败也算规格被拒`() {
        check(
            KeystoreFailure.SpecRejected,
            "java.security.InvalidAlgorithmParameterException|" +
                "Unsupported combination of key parameters|"
        )
    }

    /* ══════════════ 兜底 ══════════════ */

    @Test
    fun `认不出来的落到Unknown而不是随便猜一个`() {
        check(KeystoreFailure.Unknown, "java.io.IOException|磁盘满了|")
        check(KeystoreFailure.Unknown, "")
    }

    /**
     * `classify` 走 cause 链，而不是只看最外层——Keystore 抛出来的东西
     * 一层套一层，真正有信息的那一句往往在第二三层。
     */
    @Test
    fun `走整条cause链`() {
        val root = IllegalStateException(
            "At least one biometric must be enrolled to create keys requiring user authentication"
        )
        val wrapped = RuntimeException("wrapper", java.security.ProviderException("outer", root))
        assertEquals(KeystoreFailure.NoSecureCredential, KeystoreFailure.classify(wrapped))
    }

    /** 自引用的 cause 链不能把 classify 卡死。 */
    @Test
    fun `循环cause链不死循环`() {
        val a = RuntimeException("a")
        assertEquals(KeystoreFailure.Unknown, KeystoreFailure.classify(a))
        assertEquals(KeystoreFailure.Unknown, KeystoreFailure.classify(null))
    }
}
