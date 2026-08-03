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

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 口令派生。保险库文件被拷走之后，唯一挡住离线爆破的就是这一层。
 *
 * 参数一律写进文件头，未来提高强度不会让老库打不开。
 */
data class KdfParams(
    val id: Int,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    companion object {
        const val ID_ARGON2ID = 1
        const val ID_PBKDF2_SHA512 = 2

        /**
         * 默认档：64 MiB / t=3 / p=1。
         * OWASP 的最低线是 19 MiB / t=2 / p=1；手机上 64 MiB 解锁约在 400–700ms，
         * 这个延迟是刻意保留的——它同时也在告诉用户「主密码正在被认真对待」。
         */
        val ARGON2ID_DEFAULT = KdfParams(ID_ARGON2ID, memoryKiB = 65_536, iterations = 3, parallelism = 1)

        /** 低配机降级档（校准后自动选用） */
        val ARGON2ID_LOW = KdfParams(ID_ARGON2ID, memoryKiB = 32_768, iterations = 4, parallelism = 1)

        /**
         * 兜底档：仅在 Argon2 原生库不可用时使用。
         * PBKDF2 对 GPU/ASIC 的抵抗力远不如 Argon2id，所以迭代次数拉到 60 万。
         */
        val PBKDF2_DEFAULT = KdfParams(ID_PBKDF2_SHA512, memoryKiB = 0, iterations = 600_000, parallelism = 1)
    }
}

interface Kdf {
    val id: Int
    /** 派生 [outLen] 字节的密钥。password 由调用方负责清零。 */
    fun derive(password: ByteArray, salt: ByteArray, params: KdfParams, outLen: Int = 32): SecureBytes
}

/**
 * PBKDF2-HMAC-SHA512。纯 JCE，无任何外部依赖，
 * 作用是保证工程在拿不到 Argon2 原生库时依然能跑通全流程。
 */
class Pbkdf2Kdf : Kdf {
    override val id = KdfParams.ID_PBKDF2_SHA512

    override fun derive(password: ByteArray, salt: ByteArray, params: KdfParams, outLen: Int): SecureBytes {
        require(params.id == id) { "KDF 类型不匹配" }
        // PBEKeySpec 只接受 CharArray，这里做 latin1 一一映射，
        // 保证任意字节序列都能无损塞进去（不会因编码丢信息）。
        val chars = CharArray(password.size) { (password[it].toInt() and 0xFF).toChar() }
        return try {
            val spec = PBEKeySpec(chars, salt, params.iterations, outLen * 8)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val key = factory.generateSecret(spec)
            spec.clearPassword()
            SecureBytes.wrap(key.encoded)
        } finally {
            chars.wipe()
        }
    }
}

/**
 * 全局 KDF 入口。
 *
 * 之所以做成可替换的注册表，是因为：
 *   1) 单元测试里要用极低成本参数跑，不然每个用例都要几百毫秒；
 *   2) Argon2 是 JNI，在 JVM 单测环境里加载不了；
 *   3) 将来换更强的 KDF 时，老文件仍要能按文件头里的 id 找到对应实现。
 */
object KdfRegistry {
    private val impls = mutableMapOf<Int, Kdf>(
        KdfParams.ID_PBKDF2_SHA512 to Pbkdf2Kdf()
    )

    fun register(kdf: Kdf) { impls[kdf.id] = kdf }

    fun get(id: Int): Kdf =
        impls[id] ?: throw UnsupportedKdfException(id)

    fun isAvailable(id: Int) = impls.containsKey(id)

    /** 新建保险库时用哪一档：优先 Argon2id，拿不到就退回 PBKDF2。 */
    fun preferredParams(): KdfParams =
        if (isAvailable(KdfParams.ID_ARGON2ID)) KdfParams.ARGON2ID_DEFAULT
        else KdfParams.PBKDF2_DEFAULT
}

class UnsupportedKdfException(id: Int) :
    IllegalStateException("此保险库使用了当前版本不支持的密钥派生算法（id=$id），请升级应用后重试")
