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
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.biometric.BiometricManager
import cn.localvault.app.core.crypto.AeadRegistry
import cn.localvault.app.core.crypto.KdfParams
import cn.localvault.app.core.crypto.KdfRegistry
import cn.localvault.app.core.crypto.Rng
import cn.localvault.app.core.crypto.SecureBytes
import cn.localvault.app.core.crypto.toUtf8Secure
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

/**
 * 快捷解锁：把库主密钥另外包一份，让用户日常不必输入长主密码。
 *
 * **主密码始终是唯一的真凭据。** 这里存的任何东西被删掉，
 * 用户都还能用主密码打开保险库；反过来，这里的任何一份包裹被攻破，
 * 也只影响这台设备上的这一份。
 *
 * 两条路：
 *
 * ① 生物识别 —— 库主密钥交给 Keystore 里「每次使用都要认证」的钥匙包裹。
 *    限速由安全硬件负责，指纹库一变这把钥匙立即作废。这是首选。
 *
 * ② 应用内 PIN —— 6 位数字。单看它只有 10⁶ 种组合，撑不住离线爆破，
 *    所以做了两层绑定：
 *      PIN --Argon2id--> pinKey --包裹--> 库主密钥  ⇒ blob
 *      blob --Keystore 设备绑定密钥--> 存进 prefs
 *    攻击者把数据目录整个拷走也没用：外层那道解不开，
 *    他必须在这台设备上、在本应用进程里试，于是落进 AttemptLimiter 的退避。
 *
 * 所以原型上那个数字键盘是成立的——但它是 PIN 键盘，不是主密码键盘。
 */
class QuickUnlock(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val biometricManager = BiometricManager.from(context.applicationContext)

    init {
        // 问一句「这台机器有没有 StrongBox」，让 KeystoreKeys 的降级阶梯
        // 不必靠抛异常来试探第一档。QuickUnlock 是在 VaultApp.onCreate 里建的，
        // 所以两把钥匙真正被用到时这个答案一定已经在手上了。
        KeystoreKeys.noteDeviceCapabilities(context)

        // v1 钥匙带着 UNLOCKED_DEVICE_REQUIRED，留着只会继续制造
        // 「设置时好的、锁一次就失效」。清掉，并让对应的包裹跟着一起走。
        if (KeystoreKeys.purgeLegacyKeys()) {
            Log.i(TAG, "检测到 v1 钥匙，已清理；对应的快捷解锁绑定需要重新开启一次")
        }
        healStaleBindings()
    }

    /**
     * 自检：prefs 里有包裹，但 Keystore 里对应的钥匙已经不在了 —— 把包裹也清掉。
     *
     * ── 为什么必须有这一步 ──
     *
     * 这两样东西存在两个地方，而**它们的生命周期并不同步**：包裹在
     * SharedPreferences 里（跟着应用数据），钥匙在 Keystore 里（跟着安全硬件，
     * 会因为指纹库变更、锁屏凭据变更、系统升级、厂商 ROM 的各种情况而消失）。
     * 一旦只剩下包裹，界面就会长期展示一个**点了必定失败**的入口：
     * 解锁页上摆着 PIN 键盘和指纹图标，而它们背后已经什么都没有了。
     *
     * 上一版没有这一步，于是那种状态只能在用户下一次开门时才暴露出来——
     * 而且暴露的方式是「PIN 不正确」（外层解不开 → GCM 校验失败），
     * 用户输的是对的，还要为此吃一次退避。
     *
     * 清掉包裹**不损失任何东西**：主密码始终是唯一的真凭据，
     * 这两份包裹都只是这台设备上的捷径。用户看到的是「快捷解锁没开」，
     * 那是一句真话，而且是他自己能修的（去设置里重新开一次）。
     */
    private fun healStaleBindings() {
        // 注意判的是 `== false` 而不是 `!`：containsKey 返回 null 表示
        // 这一刻问不出来（见那边的注释），那种情况下**什么都不做**——
        // 误删一份好的绑定比晚一步发现一份坏的绑定糟得多。
        if (isBiometricEnrolled &&
            KeystoreKeys.containsKey(KeystoreKeys.ALIAS_AUTH_REQUIRED) == false
        ) {
            Log.i(TAG, "指纹包裹还在但钥匙已不存在，清掉残留")
            prefs.edit().remove(KEY_BIO_BLOB).remove(KEY_BIO_IV).apply()
        }
        if (isPinEnrolled &&
            KeystoreKeys.containsKey(KeystoreKeys.ALIAS_DEVICE_BOUND) == false
        ) {
            Log.i(TAG, "PIN 包裹还在但钥匙已不存在，清掉残留")
            disablePin()
        }
    }

    // ───────────────────── 状态 ─────────────────────

    val isBiometricEnrolled: Boolean get() = prefs.contains(KEY_BIO_BLOB)
    val isPinEnrolled: Boolean get() = prefs.contains(KEY_PIN_BLOB)
    val isAnyEnrolled: Boolean get() = isBiometricEnrolled || isPinEnrolled

    /** 这台设备当前能不能用强生物识别 */
    fun biometricAvailability(): Int =
        biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

    val isBiometricUsable: Boolean
        get() = biometricAvailability() == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * **快捷解锁**（PIN / 生物）连续失败的次数，和下面 [attemptState] 里的总次数分开记。
     *
     * 多这一个计数器，是为了让「连错 10 次就关掉快捷解锁」这条规则
     * 只被快捷解锁自己的失败触发。主密码输错十次说明用户记不清主密码了，
     * 这时候再把他唯一还记得的 PIN 关掉，就是亲手把他锁在门外。
     * 判断逻辑在 [cn.localvault.app.ui.unlock.UnlockController.recordFailure]。
     */
    var quickFailCount: Int
        get() = prefs.getInt(KEY_QUICK_FAIL, 0)
        set(value) = prefs.edit().putInt(KEY_QUICK_FAIL, value).apply()

    var attemptState: AttemptLimiter.State
        get() = AttemptLimiter.State(
            failCount = prefs.getInt(KEY_FAIL_COUNT, 0),
            lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L),
        )
        set(value) = prefs.edit()
            .putInt(KEY_FAIL_COUNT, value.failCount)
            .putLong(KEY_LOCKED_UNTIL, value.lockedUntil)
            .apply()

    // ───────────────────── 生物识别 ─────────────────────

    /**
     * 第一步：拿一个待认证的 Cipher。把它塞进 BiometricPrompt.CryptoObject，
     * 用户按完指纹后再调 [finishBiometricEnrollment]。
     */
    fun beginBiometricEnrollment(): Cipher = KeystoreKeys.authCipherForEncrypt()

    /** 第二步：认证已通过，用解锁后的 Cipher 包裹库主密钥。 */
    fun finishBiometricEnrollment(authenticatedCipher: Cipher, vaultKey: ByteArray) {
        val ct = authenticatedCipher.doFinal(vaultKey)
        val iv = authenticatedCipher.iv

        /*
         * 同 `enrollPin` 里那段自检，但这一把没法真的往返一次——
         * 解密要现场过一次指纹，不能在这里再弹一个框。
         *
         * 能验的是**下一次解锁的第一步**：拿存好的 IV 去 init 一个解密 Cipher。
         * 那一步不需要认证（认证发生在 doFinal），却要求这把钥匙确实在 Keystore 里、
         * 确实可用、参数确实对得上。它挡不住所有问题，但挡得住最常见的那一类：
         * 钥匙其实没落地，而我们已经把包裹写了进去。
         */
        try {
            KeystoreKeys.authCipherForDecrypt(iv)
        } catch (t: Throwable) {
            Log.w(TAG, "指纹钥匙刚建好就取不到解密 Cipher，放弃这次绑定", t)
            KeystoreKeys.deleteKey(KeystoreKeys.ALIAS_AUTH_REQUIRED)
            throw KeystoreUnavailableException(
                KeystoreFailure.classify(t), KeystoreKeys.ALIAS_AUTH_REQUIRED, "绑定自检", t
            )
        }

        prefs.edit()
            .putString(KEY_BIO_BLOB, ct.b64())
            .putString(KEY_BIO_IV, iv.b64())
            // 重新绑定就是重新开始：上一次是哪把指纹错了 10 次，与这一次无关
            .putInt(KEY_QUICK_FAIL, 0)
            .apply()
    }

    /**
     * 解锁第一步：取一个用存好的 IV 初始化的解密 Cipher。
     * @throws BiometricKeyInvalidatedException 指纹库变过了，这份包裹已经不可用
     */
    fun beginBiometricUnlock(): Cipher {
        val iv = prefs.getString(KEY_BIO_IV, null)?.unb64()
            ?: throw IllegalStateException("尚未开启指纹解锁")
        return try {
            KeystoreKeys.authCipherForDecrypt(iv)
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            disableBiometric()
            throw BiometricKeyInvalidatedException(e)
        } catch (e: KeyStoreException) {
            disableBiometric()
            throw BiometricKeyInvalidatedException(e)
        } catch (e: KeystoreUnavailableException) {
            // 钥匙不在了 / 读不出来 = 这份绑定已经失效，和上面两条一个处置：
            // 清掉残留，让用户用主密码进门后重新绑定一次。
            //
            // 但**其他**几种失败（安全硬件拒收规格、设备被认为锁着）不清残留：
            // 那些是有可能过一会儿就好的，把包裹删掉等于逼用户重新绑定一次
            // 来解决一个本来会自己消失的问题。
            if (e.failure == KeystoreFailure.KeyInvalidated) {
                disableBiometric()
                throw BiometricKeyInvalidatedException(e)
            }
            throw e
        }
    }

    /** 解锁第二步：认证已通过，解出库主密钥。 */
    fun finishBiometricUnlock(authenticatedCipher: Cipher): SecureBytes {
        val blob = prefs.getString(KEY_BIO_BLOB, null)?.unb64()
            ?: throw IllegalStateException("尚未开启指纹解锁")
        return SecureBytes.wrap(authenticatedCipher.doFinal(blob))
    }

    fun disableBiometric() {
        prefs.edit().remove(KEY_BIO_BLOB).remove(KEY_BIO_IV).apply()
        KeystoreKeys.deleteKey(KeystoreKeys.ALIAS_AUTH_REQUIRED)
    }

    // ───────────────────── PIN ─────────────────────

    /**
     * 绑定 PIN。[pin] 用完由调用方清零。
     *
     * PIN 走的 Argon2 参数比主密码低（32 MiB / t=2）——不是偷工减料：
     * 外层已经有 Keystore 设备绑定挡着离线爆破，
     * 这里的成本只需要挡住「在本机上手动试」，而那条路已经被退避限制住了。
     * 把它调到跟主密码一样只会让每次解锁多等半秒，安全性没有实际提升。
     */
    fun enrollPin(pin: CharArray, vaultKey: ByteArray) {
        val salt = Rng.bytes(16)
        val params = pinKdfParams()
        val aead = AeadRegistry.default

        val pinKey = pin.toUtf8Secure().use { pw ->
            KdfRegistry.get(params.id).derive(pw, salt, params, 32)
        }
        val inner = try {
            val nonce = Rng.bytes(aead.nonceLength)
            nonce + aead.seal(pinKey.bytes(), nonce, vaultKey, salt)
        } finally { pinKey.wipe() }

        // 再用 Keystore 设备绑定密钥包一层 —— 这层让文件被拷走也无法离线爆破
        val outer = KeystoreKeys.encryptWithDeviceBoundKey(inner)

        /*
         * ══════ 写 prefs 之前，先当场读一次回来 ══════
         *
         * 加密成功**不代表**以后解得开：这一层的钥匙在安全硬件里，
         * 而「能不能拿它解密」取决于生成时那份规格里的种种属性，
         * 有些属性的效果要等到下一次使用（甚至下一次设备状态变化）才显形。
         * 这正是上一版那个 bug 的形状——设置的时候一切顺利，
         * 切出去一趟回来，PIN 就用不了了。
         *
         * 当场往返一次，把那种失败从「下次开门时才发现」提前到「现在就告诉你」。
         * 代价是一次 AES-GCM，几十微秒；收益是我们**再也不会**把一份
         * 打不开的包裹写进 prefs、然后让用户以为自己设置成功了。
         *
         * 注意先后顺序：验证不通过就**一个字都不写**。宁可让用户看到
         * 「这台设备上设不了 PIN」，也不能让他看到一个开着但打不开的开关。
         */
        val readBack = try {
            KeystoreKeys.decryptWithDeviceBoundKey(outer)
        } catch (t: Throwable) {
            Log.w(TAG, "设备绑定密钥刚写就读不回来，放弃这次 PIN 绑定", t)
            throw KeystoreUnavailableException(
                KeystoreFailure.classify(t), KeystoreKeys.ALIAS_DEVICE_BOUND, "绑定自检", t
            )
        }
        val ok = readBack.contentEquals(inner)
        java.util.Arrays.fill(readBack, 0)
        if (!ok) {
            throw KeystoreUnavailableException(
                KeystoreFailure.Unknown, KeystoreKeys.ALIAS_DEVICE_BOUND, "绑定自检：往返内容不一致"
            )
        }

        prefs.edit()
            .putString(KEY_PIN_BLOB, outer.b64())
            .putString(KEY_PIN_SALT, salt.b64())
            .putInt(KEY_PIN_KDF_ID, params.id)
            .putInt(KEY_PIN_KDF_MEM, params.memoryKiB)
            .putInt(KEY_PIN_KDF_ITER, params.iterations)
            .putInt(KEY_QUICK_FAIL, 0)
            .apply()
    }

    /**
     * 用 PIN 解出库主密钥。
     * @throws WrongPinException PIN 不对
     */
    fun unlockWithPin(pin: CharArray): SecureBytes {
        val outer = prefs.getString(KEY_PIN_BLOB, null)?.unb64()
            ?: throw IllegalStateException("尚未设置 PIN")
        val salt = prefs.getString(KEY_PIN_SALT, null)!!.unb64()
        val params = KdfParams(
            id = prefs.getInt(KEY_PIN_KDF_ID, KdfParams.ID_ARGON2ID),
            memoryKiB = prefs.getInt(KEY_PIN_KDF_MEM, 32_768),
            iterations = prefs.getInt(KEY_PIN_KDF_ITER, 2),
            parallelism = 1,
        )

        val inner = KeystoreKeys.decryptWithDeviceBoundKey(outer)
        val aead = AeadRegistry.default
        val nonce = inner.copyOfRange(0, aead.nonceLength)
        val wrapped = inner.copyOfRange(aead.nonceLength, inner.size)

        val pinKey = pin.toUtf8Secure().use { pw ->
            KdfRegistry.get(params.id).derive(pw, salt, params, 32)
        }
        return try {
            SecureBytes.wrap(aead.open(pinKey.bytes(), nonce, wrapped, salt))
        } catch (e: AEADBadTagException) {
            throw WrongPinException()
        } finally {
            pinKey.wipe()
        }
    }

    fun disablePin() {
        prefs.edit()
            .remove(KEY_PIN_BLOB).remove(KEY_PIN_SALT)
            .remove(KEY_PIN_KDF_ID).remove(KEY_PIN_KDF_MEM).remove(KEY_PIN_KDF_ITER)
            .apply()
    }

    private fun pinKdfParams(): KdfParams =
        if (KdfRegistry.isAvailable(KdfParams.ID_ARGON2ID))
            KdfParams(KdfParams.ID_ARGON2ID, memoryKiB = 32_768, iterations = 2, parallelism = 1)
        else
            KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 300_000, 1)

    // ───────────────────── 清理 ─────────────────────

    /** 关掉全部快捷解锁，退回只能用主密码。数据本身不受影响。 */
    fun disableAll() {
        disableBiometric()
        disablePin()
        prefs.edit()
            .remove(KEY_FAIL_COUNT).remove(KEY_LOCKED_UNTIL).remove(KEY_QUICK_FAIL)
            .apply()
        KeystoreKeys.deleteAll()
    }

    private fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.unb64() = Base64.decode(this, Base64.NO_WRAP)

    companion object {
        private const val TAG = "QuickUnlock"
        private const val PREFS = "lv_quick_unlock"
        private const val KEY_BIO_BLOB = "bio_blob"
        private const val KEY_BIO_IV = "bio_iv"
        private const val KEY_PIN_BLOB = "pin_blob"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_KDF_ID = "pin_kdf_id"
        private const val KEY_PIN_KDF_MEM = "pin_kdf_mem"
        private const val KEY_PIN_KDF_ITER = "pin_kdf_iter"
        private const val KEY_QUICK_FAIL = "quick_fail_count"
        private const val KEY_FAIL_COUNT = "fail_count"
        private const val KEY_LOCKED_UNTIL = "locked_until"
    }
}

class WrongPinException : Exception("PIN 不正确")
