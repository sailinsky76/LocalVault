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

package cn.localvault.app.core.keystore

/**
 * Keystore 出错时「到底是哪一种错」的判断。
 *
 * **整个文件没有一行 `android.*`。** 和 `BiometricPolicy` / `QuickUnlockModel`
 * 是同一个套路，也是同一个理由：这几种失败恰恰是最难在真机上凑齐的
 * （要凑「StrongBox 拒收这份规格」得找一台特定批次的机器，
 * 要凑「设备被认为锁着」得挑一个有 bug 的 ROM），
 * 但它们的**处置和文案**必须是确定的。搬到这里之后能在纯 JVM 上钉死。
 *
 * ── 为什么按类名和消息文本认，而不是 `is XxxException` ──
 *
 * 一半的候选异常（`StrongBoxUnavailableException`、`KeyPermanentlyInvalidatedException`、
 * `android.security.KeyStoreException`）都在 `android.security.keystore` 包里，
 * 一 import 就把这个文件钉死在设备上，也就再也测不了了。
 * 而我们要的信息全在类名和消息里——Keystore 抛出来的东西层层包装
 * （`ProviderException` 裹 `KeyStoreException` 裹一个 int 错误码），
 * 类型匹配反而要写得更啰嗦。
 *
 * 所以这里走整条 cause 链，按名字认。带 Android 类型的那半边判断留在
 * 调用侧（`KeystoreKeys` / `BiometricEnroll`），它们本来就在碰 Android。
 */
enum class KeystoreFailure {

    /**
     * 缺的是**用户的凭据**，不是硬件的能力：这台设备没有设锁屏密码，
     * 或者一枚强生物特征都没录，于是「每次使用都要认证」的钥匙根本建不出来。
     *
     * **这一种再降级也没用**——降级能换掉的是安全等级，换不来一枚指纹。
     * 所以 `KeystoreKeys` 碰到它会立刻停止降级并抛出去，
     * 出口文案是「请先在系统设置里录一枚指纹」，而不是「传感器暂时不可用」。
     */
    NoSecureCredential,

    /**
     * 指纹库变过了（新增或删除），那把「认证才能用」的钥匙随之作废。
     * 这是**安全机制正常工作**的表现，不是故障。
     */
    KeyInvalidated,

    /**
     * 系统认为设备处于锁定状态，于是带 `setUnlockedDeviceRequired(true)` 的钥匙
     * 现在不能用。正常路径上出现在「刚开机还没解过锁」和
     * 「从锁屏上直接触发自动填充」这两处；也有若干 ROM 在解锁之后
     * 迟迟不更新这个状态，那属于系统 bug，我们只能如实告知。
     */
    DeviceLocked,

    /**
     * 安全硬件**拒收了这份规格**——不是「没有硬件」，是「这台机器的安全芯片
     * 不认识我们要求的某个属性」。StrongBox 尤其常见：它实现的属性集比 TEE 窄，
     * 拒收时抛的还经常不是 `StrongBoxUnavailableException` 而是一个泛泛的
     * `ProviderException`。
     *
     * 处置是**降级重试**，而不是报错——这也正是这次两个 bug 的病根所在。
     */
    SpecRejected,

    /** 认不出来。当成「可以重试一次」处理，但日志里必须留下原始异常。 */
    Unknown,
    ;

    companion object {

        /**
         * 走整条 cause 链，按类名和消息认。**顺序有讲究**：
         * 越具体的判断放在越前面，因为一条异常链上经常同时命中好几个特征
         * （`ProviderException: android.security.KeyStoreException: Key user not authenticated`
         * 既像 `SpecRejected` 又像 `NoSecureCredential`，而正确答案是后者）。
         */
        fun classify(t: Throwable?): KeystoreFailure {
            var cur = t
            val seen = HashSet<Throwable>()
            val text = StringBuilder()
            while (cur != null && seen.add(cur)) {
                text.append(cur.javaClass.name).append('|')
                cur.message?.let { text.append(it).append('|') }
                cur = cur.cause
            }
            return classifyText(text.toString())
        }

        /**
         * 拿一整条链拼出来的文本做判断。单独拆出来是为了测试能直接喂字符串——
         * 在 JVM 上造不出一个真的 `StrongBoxUnavailableException`。
         */
        fun classifyText(raw: String): KeystoreFailure {
            val s = raw.lowercase()
            return when {
                // ── 缺凭据 ──
                // 这几句是 KeyGenParameterSpec 在「要求认证但系统里没有可用凭据」时
                // 抛出的 InvalidAlgorithmParameterException 的原话（各版本措辞不同，
                // 所以认的是共同的关键词）。
                "at least one biometric must be enrolled" in s ||
                    "secure lock screen must be enabled" in s ||
                    "no enrolled" in s ||
                    "requires a lock screen" in s -> NoSecureCredential

                // ── 钥匙作废 ──
                "keypermanentlyinvalidated" in s ||
                    "key permanently invalidated" in s ||
                    "unrecoverablekey" in s -> KeyInvalidated

                // ── 设备被认为锁着 ──
                // KeyStoreException 的 -26 / DEVICE_LOCKED，以及各版本的措辞。
                "device_locked" in s ||
                    "device locked" in s ||
                    "device is locked" in s ||
                    "unlocked device required" in s -> DeviceLocked

                // ── 规格被拒 ──
                // StrongBoxUnavailableException 是最干净的一种；
                // 其余几种是真机上更常见的样子：泛泛的 ProviderException、
                // 带 -68（HARDWARE_TYPE_UNAVAILABLE）/ -38（UNIMPLEMENTED）的
                // KeyStoreException、以及 init 阶段的参数校验失败。
                "strongbox" in s ||
                    "hardware_type_unavailable" in s ||
                    "unimplemented" in s ||
                    "unsupported" in s ||
                    "incompatible" in s ||
                    "providerexception" in s ||
                    "invalidalgorithmparameter" in s ||
                    "keystoreexception" in s -> SpecRejected

                else -> Unknown
            }
        }
    }
}

/**
 * 「这台设备上的安全硬件没能把这件事办成」。
 *
 * 带上 [failure]、[alias] 和 [level]，是为了让上层能说一句**具体**的话，
 * 而不是又一条「出了点问题，请重试」。这次两个 bug 的用户可见症状
 * （「指纹传感器暂时不可用」「这次没能设置 PIN」）都是因为原始异常
 * 在半路被 `runCatching` / `catch (t: Throwable)` 吃掉了，
 * 到了屏幕上只剩一句和真实原因无关的猜测。
 *
 * @param level 最后尝试的那一档规格的说明，只进日志，不进界面。
 */
class KeystoreUnavailableException(
    val failure: KeystoreFailure,
    val alias: String,
    val level: String,
    cause: Throwable? = null,
) : Exception("Keystore 操作失败：$failure（alias=$alias, level=$level）", cause)
