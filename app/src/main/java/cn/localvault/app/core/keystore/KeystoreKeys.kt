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

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 里的两把钥匙。它们本身永远不会离开安全硬件，
 * 应用只能请求「用它加密/解密」，拿不到密钥字节。
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ DEVICE_BOUND —— 不要求用户认证，但绑定这台设备                        │
 * │   用途：把 PIN 包裹后的密钥块再包一层。                                │
 * │   收益：攻击者就算把整个 App 数据目录拷到电脑上，                       │
 * │        也没法离线爆破那个 6 位 PIN——他必须在这台机器上、               │
 * │        在应用进程里跑，于是就落进了我们的失败次数限制。                   │
 * ├──────────────────────────────────────────────────────────────────┤
 * │ AUTH_REQUIRED —— 每次使用都必须先通过生物识别                         │
 * │   用途：生物解锁时包裹库主密钥。                                      │
 * │   收益：限速由硬件做，不是由我们的代码做。                              │
 * │   并且指纹库一旦发生变更（有人偷偷录了自己的指纹），这把钥匙立即作废。      │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * ══════════════ 这一版为什么大改 ══════════════
 *
 * 上一版对「这台设备能不能满足我们要求的规格」抱了两个过于乐观的假设，
 * 每一个都单独足以让快捷解锁整体报废。
 *
 * ── 假设一：拒收 StrongBox 时一定抛 `StrongBoxUnavailableException` ──
 *
 * 不是这样的。安全芯片拒收一份规格时抛出来的东西五花八门：泛泛的
 * `java.security.ProviderException`、裹着 -68 / -38 错误码的
 * `android.security.KeyStoreException`、`init()` 阶段的
 * `InvalidAlgorithmParameterException`……只有「这台机器根本没有 StrongBox」
 * 这一种情况才保证抛 `StrongBoxUnavailableException`，而那恰好是唯一被接住的。
 * 于是在拒收规格的机器上一把钥匙都建不出来 →
 * 指纹绑定和 PIN 设置**双双失败**，而且每次都失败。
 *
 * 现在改成一条**降级阶梯**（StrongBox → TEE），每档失败都记日志、
 * 清掉可能留下的残缺条目、再试下一档；两档全败才抛
 * [KeystoreUnavailableException]，而它带着具体是哪一种失败。
 * 有一种例外：[KeystoreFailure.NoSecureCredential]（没设锁屏 / 没录指纹）
 * 会立刻中断降级——降级能换掉安全等级，换不来一枚指纹，
 * 继续往下试只是把一句说得清的话磨成一句说不清的话。
 *
 * ── 假设二：钥匙生成成功了，以后就一直能用 ──
 *
 * 也不是。上一版给两把钥匙都加了 `setUnlockedDeviceRequired(true)`，
 * 而那是整份规格里唯一一个**能让一把已经生成成功的钥匙在之后突然不可用**的属性。
 * 症状是「设置的时候一切正常，切出去一趟回来就说绑定失效了」——
 * 而且因为属性写进钥匙就改不掉，光改生成代码治不了已经绑过的设备。
 * 这一版把它整个去掉（理由写在 [doGenerate] 里，结论是它在两把钥匙上都是冗余的），
 * 别名同时升到 v2，并由 [purgeLegacyKeys] 做一次性清理。
 *
 * ── 另外：读取侧不许悄悄重建钥匙 ──
 *
 * 见 [getOrCreate] 的 `allowReset`。上一版在解锁路径上会悄悄建一把新钥匙，
 * 于是「钥匙没了」被一路翻译成「PIN 不正确」并吃一次退避。
 */
object KeystoreKeys {

    private const val TAG = "KeystoreKeys"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    const val ALIAS_DEVICE_BOUND = "lv_device_bound_v2"
    const val ALIAS_AUTH_REQUIRED = "lv_auth_required_v2"

    /**
     * v1 的两个别名。只在 [purgeLegacyKeys] 里出现，用来做一次性清理。
     *
     * 为什么要升版本号：v1 的钥匙是**带着 `UNLOCKED_DEVICE_REQUIRED` 生成的**，
     * 而那个属性一旦写进钥匙就改不了。留着它们的话，已经绑过快捷解锁的用户
     * 会一直用着有问题的那把钥匙，而症状（「设置时好的，锁一次就用不了」）
     * 不会因为我们改了生成代码而消失。升号 + 清理，让每台设备重新绑一次，
     * 代价是用户按一次指纹、输一次 PIN，收益是从此不再有两种钥匙并存。
     */
    private const val LEGACY_ALIAS_DEVICE_BOUND = "lv_device_bound_v1"
    private const val LEGACY_ALIAS_AUTH_REQUIRED = "lv_auth_required_v1"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(PROVIDER).apply { load(null) }
    }

    /* ─────────────────────── 设备能力 ─────────────────────── */

    /**
     * 这台机器有没有 StrongBox（独立安全芯片）。
     *
     * 上一版是「先试着建，抛异常再退回 TEE」——靠异常问一件系统愿意直接回答的事。
     * `PackageManager` 从 API 28 起就有这个 feature 查询，问一句就好，
     * 而且这样第一档不会在绝大多数没有 StrongBox 的机器上白白失败一次
     * （那一次失败会在 Keystore 里留下痕迹，也会在日志里制造噪音）。
     *
     * `null` 表示还没问过。[noteDeviceCapabilities] 在 `QuickUnlock` 构造时调用，
     * 而 `QuickUnlock` 是 `VaultApp.onCreate` 里建的，所以真正用到这两把钥匙时
     * 它一定已经有值了。真要是没有（测试、或者将来有人换了装配顺序），
     * 就退回上一版的行为：试一次 StrongBox，失败就降级——那条路现在也是安全的了。
     */
    private var strongBoxPresent: Boolean? = null

    fun noteDeviceCapabilities(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            strongBoxPresent = false
            return
        }
        strongBoxPresent = runCatching {
            context.applicationContext.packageManager
                .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        }.getOrDefault(false)
        Log.i(TAG, "设备能力：StrongBox=${strongBoxPresent}, SDK=${Build.VERSION.SDK_INT}")
    }

    /* ─────────────────────── 降级阶梯 ─────────────────────── */

    private data class Level(
        val strongBox: Boolean,
        val label: String,
    )

    private fun levels(): List<Level> = buildList {
        // strongBoxPresent 为 null（没问过）时也试一档，理由见上面那段注释。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBoxPresent != false) {
            add(Level(strongBox = true, label = "StrongBox"))
        }
        add(Level(strongBox = false, label = "TEE"))
    }

    /* ─────────────────── 一次性清理 v1 钥匙 ─────────────────── */

    /**
     * 删掉 v1 的两把钥匙。由 `QuickUnlock` 在构造时调用一次。
     *
     * @return 真的删掉了东西才返回 true —— `QuickUnlock` 拿它决定
     *         要不要顺手把 prefs 里那两份已经打不开的包裹也清掉。
     */
    fun purgeLegacyKeys(): Boolean {
        var found = false
        for (alias in listOf(LEGACY_ALIAS_DEVICE_BOUND, LEGACY_ALIAS_AUTH_REQUIRED)) {
            val present = runCatching { keyStore.containsAlias(alias) }.getOrDefault(false)
            if (present) {
                Log.i(TAG, "清理 v1 钥匙：$alias")
                deleteKey(alias)
                found = true
            }
        }
        return found
    }

    /**
     * 这把钥匙**现在**在不在 Keystore 里。给 `QuickUnlock` 的自检用。
     *
     * 返回 `null` 表示**问不出来**（Keystore 服务还没就绪、或者抛了别的异常），
     * 和「确定不在」严格区分开。这个区分是必须的：自检的动作是
     * 「钥匙不在就把包裹清掉」，而应用启动那一刻恰好是 Keystore 最可能
     * 答不上话的时候（直接启动模式、开机后第一次解锁之前）。
     * 把「问不出来」当成「不在」，代价是**误删一份好的绑定**——
     * 用户什么都没做，快捷解锁自己关了。宁可这一次不自检。
     */
    fun containsKey(alias: String): Boolean? =
        runCatching { keyStore.containsAlias(alias) }
            .onFailure { Log.w(TAG, "问不出 $alias 在不在，这次跳过自检", it) }
            .getOrNull()

    // ─────────────────────── 生成 ───────────────────────

    /** 取设备绑定密钥。[allowReset] 的含义见 [getOrCreate]，那是这一版的重点。 */
    fun getOrCreateDeviceBoundKey(allowReset: Boolean): SecretKey =
        getOrCreate(ALIAS_DEVICE_BOUND, requireAuth = false, allowReset = allowReset)

    /**
     * 取「每次使用都要认证」的钥匙。[allowReset] 的含义见 [getOrCreate]：
     * 绑定时传 `true`（正要覆盖旧包裹），解锁时传 `false`
     * （钥匙没了就是绑定失效了，不能装作没事重建一把）。
     */
    fun getOrCreateAuthRequiredKey(allowReset: Boolean): SecretKey =
        getOrCreate(ALIAS_AUTH_REQUIRED, requireAuth = true, allowReset = allowReset)

    /**
     * 取到这把钥匙，或者说清楚为什么取不到。
     *
     * @param allowReset 这条路径**能不能承受钥匙被删掉重建**。
     *
     * 这是这一版最要紧的一条区分，两把钥匙上都成立：
     *
     *   - **写入侧**（绑定指纹、设置 PIN）传 `true`。那一刻我们正要写一份全新的包裹，
     *     旧钥匙有没有用无所谓，重建不会弄丢任何东西。
     *   - **读取侧**（用指纹解锁、用 PIN 解锁）传 `false`。上一版这里会悄悄建一把新钥匙，
     *     于是后面必定解不开，而那个失败被一路翻译成「凭据不对」——
     *     用户输着**正确的** PIN，屏幕上写「PIN 不正确」，还要为此吃一次退避，
     *     连错十次之后快捷解锁被自动关掉。整条链上没有一句话是真的。
     *     现在它抛 [KeystoreUnavailableException]，上层才有机会说出
     *     「这份绑定已经失效，请用主密码进入后重设」。
     */
    private fun getOrCreate(alias: String, requireAuth: Boolean, allowReset: Boolean): SecretKey {
        val existing = try {
            if (keyStore.containsAlias(alias)) keyStore.getKey(alias, null) as? SecretKey else null
        } catch (t: Throwable) {
            // getKey 自己抛异常，多半是这把钥匙已经不可恢复（UnrecoverableKeyException）。
            // 上一版是 runCatching{}.getOrNull()，把「读不出来」和「不存在」吞成同一件事——
            // 而这两件事的正确处置正好相反。
            if (!allowReset) {
                Log.w(TAG, "$alias 已不可用，且当前路径不允许重建", t)
                throw KeystoreUnavailableException(
                    KeystoreFailure.KeyInvalidated, alias, "读取现有钥匙", t
                )
            }
            Log.w(TAG, "$alias 已不可用，删掉重建", t)
            deleteKey(alias)
            null
        }
        if (existing != null) return existing

        if (!allowReset) {
            // 钥匙不在了，而调用方是读取侧：包裹还在、钥匙没了。
            // 建一把新的只会让下一步失败得更难懂。
            throw KeystoreUnavailableException(
                KeystoreFailure.KeyInvalidated, alias, "钥匙已不存在", null
            )
        }
        return generate(alias, requireAuth)
    }

    private fun generate(alias: String, requireAuth: Boolean): SecretKey {
        var last: Throwable? = null
        for (level in levels()) {
            try {
                val key = doGenerate(alias, requireAuth, level)
                Log.i(TAG, "生成 $alias 成功（${level.label}）")
                return key
            } catch (t: Throwable) {
                val kind = KeystoreFailure.classify(t)

                // 缺的是用户的凭据，再降级也变不出一枚指纹来。立刻停，
                // 让上层能说出那句唯一有用的话：「请先在系统设置里录一枚指纹」。
                if (kind == KeystoreFailure.NoSecureCredential) {
                    Log.w(TAG, "生成 $alias 失败：系统里没有可用的凭据", t)
                    throw KeystoreUnavailableException(kind, alias, level.label, t)
                }

                Log.w(TAG, "生成 $alias 失败（${level.label}，判定 $kind），继续降级", t)
                last = t
                // 失败可能已经在 Keystore 里留下一个残缺条目。留着它的话
                // 下一次 getOrCreate 会认为「已经有了」，拿到一把不能用的钥匙，
                // 症状变成「绑定成功了，但每次解锁都失败」——最难被理解的那一种。
                deleteKey(alias)
            }
        }
        throw KeystoreUnavailableException(
            KeystoreFailure.classify(last), alias, "所有降级档位均失败", last
        )
    }

    private fun doGenerate(alias: String, requireAuth: Boolean, level: Level): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // 强制随机 IV：禁止调用方自己指定，杜绝 GCM 下重用 nonce
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && level.strongBox) {
            builder.setIsStrongBoxBacked(true)
        }

        /*
         * ══════ 这里**不再**调用 setUnlockedDeviceRequired(true) ══════
         *
         * 上一版两把钥匙都带着这个属性，而它是整份规格里唯一一个
         * **能让一把已经成功生成的钥匙在之后突然不可用**的东西——
         * 症状正是「设置的时候一切正常，切出去一趟回来就说绑定失效了」。
         * 属性一旦写进钥匙就改不掉，所以别名同时升到了 v2（见上面）。
         *
         * 去掉它损失了什么：严格地说，什么都没损失。它在两把钥匙上都是**冗余**的。
         *
         *   · AUTH_REQUIRED 那把：每次使用都要现场通过一次强生物识别
         *     （`setUserAuthenticationRequired(true)` + 0 秒时间窗）。
         *     这个条件严格强于「设备处于解锁状态」——能按过指纹框的人，
         *     设备当然是解锁的。再加一条更弱的前提，只是多一个失败点。
         *
         *   · DEVICE_BOUND 那把：它防的是「保险库文件被拷到别的机器上离线爆破 PIN」，
         *     靠的是「钥匙出不了这台设备的安全硬件」，和设备锁没锁无关。
         *     真要靠这个属性挡住的场景是「攻击者拿着一台锁屏的手机、
         *     让我们的进程跑起来去试 PIN」——而他既然能驱动 PIN 界面，
         *     手上那台机器本来就是解锁的。
         *     顺带说一句：M4 的自动填充要从锁屏上工作，这个属性到那时候
         *     是要专门去掉的，现在去掉正好省了一次返工。
         *
         * 库主密钥的强度一点没动：它仍然只以「被安全硬件里的钥匙包过一层」的
         * 形式落盘，主密码仍然是唯一的真凭据。
         */

        if (requireAuth) {
            builder.setUserAuthenticationRequired(true)
            // 指纹/面容库发生变更 → 这把钥匙立即永久失效。
            // 场景：有人趁你睡着把自己的指纹录进你手机。
            // 失效后用户必须重新输主密码，快捷解锁需要重新绑定。
            builder.setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 0 秒 = 每次使用都要单独认证一次（配合 CryptoObject），
                // 不是「认证一次管 30 秒」。密码库不接受时间窗。
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            }
            // API 30 以下不调用 setUserAuthenticationValidityDurationSeconds，
            // 默认行为就是「每次使用都需认证」。
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(builder.build())
        }.generateKey()
    }

    // ─────────────────────── 使用 ───────────────────────

    /**
     * 直接加密（仅限 DEVICE_BOUND 这类不要求认证的钥匙）。返回 iv‖密文。
     *
     * 这是**写入**侧，所以允许重建钥匙（见 [getOrCreateDeviceBoundKey]）。
     */
    fun encryptWithDeviceBoundKey(plaintext: ByteArray): ByteArray = wrapFailures(ALIAS_DEVICE_BOUND) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateDeviceBoundKey(allowReset = true))
        val ct = cipher.doFinal(plaintext)
        cipher.iv + ct
    }

    /** 解密侧：**不许**重建钥匙，理由见 [getOrCreateDeviceBoundKey]。 */
    fun decryptWithDeviceBoundKey(ivAndCiphertext: ByteArray): ByteArray = wrapFailures(ALIAS_DEVICE_BOUND) {
        require(ivAndCiphertext.size > IV_LEN) { "密文块过短" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateDeviceBoundKey(allowReset = false),
            GCMParameterSpec(TAG_BITS, ivAndCiphertext, 0, IV_LEN)
        )
        cipher.doFinal(ivAndCiphertext, IV_LEN, ivAndCiphertext.size - IV_LEN)
    }

    /**
     * 取一个待认证的 Cipher，交给 BiometricPrompt 的 CryptoObject。
     * 认证通过后系统才会解锁这个 Cipher，我们再拿它去做真正的加解密。
     *
     * [KeyPermanentlyInvalidatedException] **原样放过**：调用方
     * （`BiometricEnroll.freshEnrollCipher`）要靠它触发「删掉重建」那一步，
     * 包装成别的类型会把那条路切断。
     */
    fun authCipherForEncrypt(): Cipher = wrapFailures(ALIAS_AUTH_REQUIRED) {
        Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateAuthRequiredKey(allowReset = true))
        }
    }

    fun authCipherForDecrypt(iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateAuthRequiredKey(allowReset = false), GCMParameterSpec(TAG_BITS, iv))
        }

    /**
     * 把这一层里冒出来的**不认识的**异常统一翻译成 [KeystoreUnavailableException]。
     *
     * 为什么值得专门做这一步：上一版的两处调用点分别是
     * `runCatching { ... }` 和 `catch (t: Throwable) { HardwareUnavailable }`，
     * 它们会把任何异常都磨成同一句和真实原因无关的话。
     * 在这一层就翻译好，上层才有东西可分辨，日志里也才留得下原始异常。
     *
     * 三类东西原样放过：
     *   - [KeyPermanentlyInvalidatedException]：上层要靠它走重建那条路；
     *   - [KeystoreUnavailableException]：已经是翻译好的了，别包两层；
     *   - `AEADBadTagException` / `IllegalArgumentException`：那是**数据**不对，
     *     不是 Keystore 不行，翻译过去会把「PIN 输错了」说成「安全硬件故障」。
     */
    private inline fun <R> wrapFailures(alias: String, block: () -> R): R = try {
        block()
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw e
    } catch (e: KeystoreUnavailableException) {
        throw e
    } catch (e: javax.crypto.AEADBadTagException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (t: Throwable) {
        val kind = KeystoreFailure.classify(t)
        Log.w(TAG, "Keystore 操作失败（alias=$alias, 判定=$kind）", t)
        throw KeystoreUnavailableException(kind, alias, "使用阶段", t)
    }

    // ─────────────────────── 清理 ───────────────────────

    fun deleteKey(alias: String) = runCatching { keyStore.deleteEntry(alias) }.isSuccess

    fun deleteAll() {
        deleteKey(ALIAS_DEVICE_BOUND)
        deleteKey(ALIAS_AUTH_REQUIRED)
    }

    const val IV_LEN = 12
}

/**
 * 生物识别密钥已失效。
 *
 * 触发条件：用户新增/删除了指纹或面容，或者取消了锁屏密码。
 * 这是**安全机制正常工作**的表现，不是 bug。
 * UI 要做的是平静地告诉用户「请用主密码解锁并重新开启指纹解锁」，
 * 而不是弹一个吓人的错误。
 */
class BiometricKeyInvalidatedException(cause: Throwable? = null) :
    Exception("生物识别信息已变更，请用主密码解锁后重新开启指纹解锁", cause)
