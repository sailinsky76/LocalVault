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

import android.util.Log
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode

/**
 * Argon2id —— 主力 KDF。JNI 实现，只能在 Android 运行时使用。
 *
 * 这个类被单独隔离出来的原因：它是整个工程里唯一依赖原生库的地方。
 * 如果 argon2kt 依赖解析不了、或者上游 API 有变动，只需要改这一个文件，
 * 其余代码通过 Kdf 接口完全不受影响。
 */
class Argon2idKdf : Kdf {
    override val id = KdfParams.ID_ARGON2ID

    private val argon2 by lazy { Argon2Kt() }

    override fun derive(password: ByteArray, salt: ByteArray, params: KdfParams, outLen: Int): SecureBytes {
        require(params.id == id) { "KDF 类型不匹配" }
        val result = argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password,
            salt = salt,
            tCostInIterations = params.iterations,
            mCostInKibibyte = params.memoryKiB,
            parallelism = params.parallelism,
            hashLengthInBytes = outLen,
        )
        return SecureBytes.wrap(result.rawHashAsByteArray())
    }

    companion object {
        private const val TAG = "Argon2idKdf"

        /**
         * 在 Application 启动时调用。原生库加载失败不应该让 App 崩溃——
         * 退回 PBKDF2 依然是安全的，只是抗爆破成本低一些。
         */
        fun registerIfAvailable(): Boolean = try {
            val kdf = Argon2idKdf()
            // 触发一次真实派生，确认 .so 确实加载成功
            kdf.derive(
                password = ByteArray(8),
                salt = ByteArray(16),
                params = KdfParams(KdfParams.ID_ARGON2ID, memoryKiB = 8192, iterations = 1, parallelism = 1),
                outLen = 32
            ).wipe()
            KdfRegistry.register(kdf)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Argon2 原生库不可用，降级到 PBKDF2-HMAC-SHA512", t)
            false
        }

        /**
         * 设备校准：新建保险库时跑一次，挑一档能在目标耗时内完成的参数。
         * 目标 [targetMillis] 默认 600ms —— 低于这个值说明还能加码，
         * 高于说明这台机器扛不住默认档，降到 LOW。
         */
        fun calibrate(targetMillis: Long = 600): KdfParams {
            val kdf = KdfRegistry.get(KdfParams.ID_ARGON2ID)
            val candidates = listOf(KdfParams.ARGON2ID_DEFAULT, KdfParams.ARGON2ID_LOW)
            for (p in candidates) {
                val t0 = System.nanoTime()
                runCatching {
                    kdf.derive(ByteArray(16), ByteArray(16), p).wipe()
                }.onFailure { return KdfParams.ARGON2ID_LOW }
                val ms = (System.nanoTime() - t0) / 1_000_000
                Log.d(TAG, "校准 mem=${p.memoryKiB}KiB t=${p.iterations} → ${ms}ms")
                if (ms <= targetMillis * 2) return p
            }
            return KdfParams.ARGON2ID_LOW
        }
    }
}
