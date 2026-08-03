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

import java.util.Arrays

/**
 * 一段用完必须抹掉的敏感字节（主密钥、KEK、口令的 UTF-8 编码）。
 *
 * 为什么不用 String 装密码：Java 的 String 不可变，一旦创建，
 * 在 GC 回收之前你没有任何办法把它从堆里擦掉，而堆可能被 dump。
 * 所以全流程用 CharArray / ByteArray，用完 wipe()。
 *
 * 用法：
 *   SecureBytes.of(key).use { k -> ... }   // 退出时自动清零
 */
class SecureBytes private constructor(private val buf: ByteArray) : java.io.Closeable {

    @Volatile
    private var wiped = false

    val size: Int get() = buf.size

    /** 取用底层数组。调用方不得保留引用，也不得修改长度。 */
    fun bytes(): ByteArray {
        check(!wiped) { "SecureBytes 已被清零，不能再使用" }
        return buf
    }

    fun copy(): ByteArray = bytes().copyOf()

    fun wipe() {
        if (!wiped) {
            Arrays.fill(buf, 0)
            wiped = true
        }
    }

    override fun close() = wipe()

    inline fun <R> use(block: (ByteArray) -> R): R = try {
        block(bytes())
    } finally {
        wipe()
    }

    companion object {
        /** 接管传入的数组（不复制）。传入后调用方不应再持有它。 */
        fun wrap(array: ByteArray) = SecureBytes(array)

        /** 复制一份再接管。 */
        fun of(array: ByteArray) = SecureBytes(array.copyOf())

        fun allocate(size: Int) = SecureBytes(ByteArray(size))
    }
}

/**
 * CharArray → UTF-8 ByteArray，全程不产生 String。
 *
 * 标准库的 String(chars).toByteArray() 会在堆里留下一个擦不掉的 String，
 * 对密码管理器来说这是个真实的泄露面，所以这里手写编码。
 */
fun CharArray.toUtf8Secure(): SecureBytes {
    // 最坏情况：每个 char 3 字节（BMP），代理对每两个 char 4 字节
    val out = ByteArray(size * 3)
    var o = 0
    var i = 0
    while (i < size) {
        val c = this[i].code
        when {
            c < 0x80 -> {
                out[o++] = c.toByte()
            }
            c < 0x800 -> {
                out[o++] = (0xC0 or (c shr 6)).toByte()
                out[o++] = (0x80 or (c and 0x3F)).toByte()
            }
            c in 0xD800..0xDBFF && i + 1 < size && this[i + 1].code in 0xDC00..0xDFFF -> {
                // 代理对，合成一个码位
                val cp = 0x10000 + ((c - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
                out[o++] = (0xF0 or (cp shr 18)).toByte()
                out[o++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                out[o++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                out[o++] = (0x80 or (cp and 0x3F)).toByte()
                i++
            }
            else -> {
                out[o++] = (0xE0 or (c shr 12)).toByte()
                out[o++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                out[o++] = (0x80 or (c and 0x3F)).toByte()
            }
        }
        i++
    }
    val exact = out.copyOf(o)
    Arrays.fill(out, 0)
    return SecureBytes.wrap(exact)
}

fun CharArray.wipe() = Arrays.fill(this, '\u0000')

/**
 * 恒定时间比较。用于校验标签、指纹等，避免计时侧信道。
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
