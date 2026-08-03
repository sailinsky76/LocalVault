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

import cn.localvault.app.core.keystore.AttemptLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptLimiterTest {

    private var now = 1_000_000L
    private val limiter = AttemptLimiter(now = { now })

    @Test
    fun `前四次失败不罚等待，给手滑留余地`() {
        var s = AttemptLimiter.State()
        repeat(4) {
            s = limiter.onFailure(s)
            assertFalse("第 ${s.failCount} 次不应锁定", limiter.isLocked(s))
        }
        assertEquals(4, s.failCount)
    }

    @Test
    fun `第五次起开始退避，且时长递增`() {
        var s = AttemptLimiter.State()
        repeat(5) { s = limiter.onFailure(s) }
        val first = limiter.remainingLockMillis(s)
        assertTrue("第五次应开始等待", first > 0)

        now += first
        s = limiter.onFailure(s)
        assertTrue("等待时间应递增", limiter.remainingLockMillis(s) > first)
    }

    @Test
    fun `等待期满后自动解除`() {
        var s = AttemptLimiter.State()
        repeat(5) { s = limiter.onFailure(s) }
        assertTrue(limiter.isLocked(s))
        now += limiter.remainingLockMillis(s) + 1
        assertFalse(limiter.isLocked(s))
    }

    @Test
    fun `成功一次即完全清零`() {
        var s = AttemptLimiter.State()
        repeat(7) { s = limiter.onFailure(s) }
        s = limiter.onSuccess()
        assertEquals(0, s.failCount)
        assertFalse(limiter.isLocked(s))
    }

    @Test
    fun `连错十次才关掉快捷解锁，而且只是关掉，不碰数据`() {
        var s = AttemptLimiter.State()
        repeat(9) { s = limiter.onFailure(s) }
        assertFalse(limiter.shouldDisableQuickUnlock(s))
        s = limiter.onFailure(s)
        assertTrue(limiter.shouldDisableQuickUnlock(s))
    }

    @Test
    fun `退避时长有上限，不会溢出成永久锁死`() {
        var s = AttemptLimiter.State()
        repeat(50) { s = limiter.onFailure(s) }
        val wait = limiter.remainingLockMillis(s)
        assertTrue("上限应为 15 分钟", wait in 1..(15 * 60 * 1000L))
    }
}
