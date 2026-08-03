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

package cn.localvault.app.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 带关联数据的认证加密（AEAD）。
 *
 * 选型说明：主力用 AES-256-GCM，不用 XChaCha20-Poly1305。
 * 理由是 ARMv8 手机（minSdk 26 基本都是）有 AES 硬件指令，
 * 而 XChaCha20 在 Android 上必须引入 Tink 或 libsodium，
 * 为一个不会更安全的算法多背一个原生依赖不划算。
 *
 * cipherId 依然写进文件头，将来要换算法不会卡住老文件。
 */
interface Aead {
    val id: Int
    val nonceLength: Int
    val tagLength: Int

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
}

class AesGcmAead : Aead {
    override val id = ID
    override val nonceLength = 12      // GCM 的标准 nonce 长度，别改
    override val tagLength = 16

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "需要 256 位密钥" }
        require(nonce.size == nonceLength) { "nonce 长度必须是 $nonceLength" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagLength * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "需要 256 位密钥" }
        require(nonce.size == nonceLength) { "nonce 长度必须是 $nonceLength" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagLength * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)   // 标签不对会抛 AEADBadTagException
    }

    companion object {
        const val ID = 1
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

object AeadRegistry {
    private val impls = mapOf<Int, Aead>(AesGcmAead.ID to AesGcmAead())
    fun get(id: Int): Aead = impls[id]
        ?: throw IllegalStateException("此保险库使用了当前版本不支持的加密算法（id=$id），请升级应用后重试")
    val default: Aead get() = impls.getValue(AesGcmAead.ID)
}

/**
 * 随机源。所有 salt / nonce / 主密钥 / 生成的密码都必须走这里。
 *
 * 注意：绝不要在任何地方用 kotlin.random.Random 或 java.util.Random
 * 去生成跟密码有关的东西。项目里可以搜一遍确认。
 */
object Rng {
    private val secureRandom = SecureRandom()

    fun bytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    fun fill(target: ByteArray) = secureRandom.nextBytes(target)

    /**
     * 无模偏差的 [0, bound) 均匀整数。
     * 密码生成器必须用这个，简单取模会让字符集靠前的字符出现得更频繁。
     */
    fun int(bound: Int): Int {
        require(bound > 0)
        val limit = Int.MAX_VALUE - (Int.MAX_VALUE % bound)
        while (true) {
            val r = secureRandom.nextInt() and Int.MAX_VALUE
            if (r < limit) return r % bound
        }
    }
}
