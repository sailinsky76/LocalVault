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

import cn.localvault.app.ui.components.PinBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PIN 缓冲区。
 *
 * 「不就是个长度 6 的 char[] 吗」——正因为它看起来太简单，才值得钉住：
 * 这是整个 App 里除主密码之外唯一一处**明文凭据在内存里驻留**的地方，
 * 而它的清零发生在几个容易漏掉的路径上（提交后、退出页面、被拒绝的提交）。
 * 少擦一次，一串 6 位数字就留在堆里等着被 dump。
 */
class PinBufferTest {

    @Test
    fun `按满就不再收，不会越界`() {
        val b = PinBuffer(capacity = 6)
        repeat(6) { assertTrue(b.push('1')) }
        assertTrue(b.isFull)
        assertFalse("满了之后再按应该被拒绝而不是抛异常", b.push('9'))
        assertEquals(6, b.size)
    }

    @Test
    fun `退格到空之后再按退格是安全的`() {
        val b = PinBuffer()
        b.push('1'); b.push('2')
        assertTrue(b.pop())
        assertTrue(b.pop())
        assertEquals(0, b.size)
        assertFalse("空的时候退格应该被拒绝", b.pop())
        assertEquals(0, b.size)
    }

    @Test
    fun `交出去的副本只含已输入的部分`() {
        val b = PinBuffer()
        "246".forEach { b.push(it) }
        assertArrayEquals(charArrayOf('2', '4', '6'), b.copyChars())
    }

    @Test
    fun `退格之后交出去的副本不含被删掉的那一位`() {
        val b = PinBuffer()
        "2468".forEach { b.push(it) }
        b.pop()
        assertArrayEquals(
            "退格必须真的抹掉，不能只是把长度减一",
            charArrayOf('2', '4', '6'),
            b.copyChars(),
        )
    }

    @Test
    fun `wipe 之后长度归零且交出去的是空数组`() {
        val b = PinBuffer()
        "246810".forEach { b.push(it) }
        b.wipe()
        assertEquals(0, b.size)
        assertFalse(b.isFull)
        assertEquals(0, b.copyChars().size)
    }

    @Test
    fun `wipe 之后重新输入不会带出上一次的残留`() {
        val b = PinBuffer()
        "999999".forEach { b.push(it) }
        b.wipe()
        "12".forEach { b.push(it) }
        assertArrayEquals(charArrayOf('1', '2'), b.copyChars())
    }
}
