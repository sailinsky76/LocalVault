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

import cn.localvault.app.ui.components.HoldProgress
import cn.localvault.app.ui.unlock.ResetVaultModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按住不放三秒」的计时账本。
 *
 * 这个类只有几十行，但它底下挂的是全 App 第二个不可逆的动作，
 * 而它的三条要紧性质在真机上恰恰是最难验的——
 * 「完成只报了一次」和「完成报了六十次」在屏幕上长得一模一样。
 *
 * 时间一律由用例给（这个类里没有任何时钟），所以三秒在这儿是一行加法。
 */
class HoldProgressTest {

    private fun hold() = HoldProgress(ResetVaultModel.HOLD_MILLIS)

    /* ═════════════ 没按着的时候 ═════════════ */

    @Test
    fun `没按着的时候进度是零、剩余是全程`() {
        val h = hold()
        assertFalse(h.holding)
        assertEquals(0f, h.progress(123_456L), 0f)
        assertEquals(ResetVaultModel.HOLD_MILLIS, h.remaining(123_456L))
    }

    @Test
    fun `没按着的时候一直 tick 也不会触发`() {
        val h = hold()
        repeat(200) { assertFalse(h.tick(it * 100L)) }
    }

    /* ═════════════ 按住到满 ═════════════ */

    @Test
    fun `按下那一刻进度是零`() {
        val h = hold()
        h.press(1000L)
        assertTrue(h.holding)
        assertEquals(0f, h.progress(1000L), 0f)
        assertEquals(ResetVaultModel.HOLD_MILLIS, h.remaining(1000L))
    }

    @Test
    fun `走到一半时进度在中间、剩余是一半`() {
        val h = hold()
        h.press(1000L)
        val half = 1000L + ResetVaultModel.HOLD_MILLIS / 2
        assertEquals(0.5f, h.progress(half), 0.001f)
        assertEquals(ResetVaultModel.HOLD_MILLIS / 2, h.remaining(half))
        assertFalse(h.tick(half))
    }

    @Test
    fun `差一毫秒还不算数`() {
        val h = hold()
        h.press(1000L)
        assertFalse(h.tick(1000L + ResetVaultModel.HOLD_MILLIS - 1))
    }

    @Test
    fun `正好到点就算数`() {
        val h = hold()
        h.press(1000L)
        assertTrue(h.tick(1000L + ResetVaultModel.HOLD_MILLIS))
    }

    /**
     * 这一条是这个类存在的第一个理由。
     *
     * 帧回调一秒来六十次，越过终点之后还会继续来。漏掉这条，
     * `onComplete` 会被连着调几十次，而它调的是 `ResetVaultController.submit()`。
     */
    @Test
    fun `完成只报一次，之后的每一帧都不再报`() {
        val h = hold()
        h.press(0L)
        var fired = 0
        // 五秒的帧，每 16ms 一帧，覆盖三秒那个点前后
        var t = 0L
        while (t <= 5000L) {
            if (h.tick(t)) fired++
            t += 16L
        }
        assertEquals(1, fired)
    }

    @Test
    fun `到点之后剩余停在零，不会减成负数`() {
        val h = hold()
        h.press(0L)
        h.tick(ResetVaultModel.HOLD_MILLIS)
        assertEquals(0L, h.remaining(60_000L))
        assertEquals(1f, h.progress(60_000L), 0f)
    }

    /* ═════════════ 松手是中止，不是暂停 ═════════════ */

    /**
     * 这一条是这个类存在的第二个理由，也是这道门的全部意义所在
     * （决策(128)：它拦的是「刚打完字的手指顺势再点一下」）。
     *
     * 做成暂停的话，「按三下每下一秒」就等于按住三秒。
     */
    @Test
    fun `松手再按是从头开始，不接着上次的进度`() {
        val h = hold()
        h.press(0L)
        // 按到还差 100ms
        val almost = ResetVaultModel.HOLD_MILLIS - 100L
        assertFalse(h.tick(almost))
        h.release()

        // 再按下，只按 200ms —— 加起来早就超过三秒了，但这一轮才刚开始
        h.press(almost)
        assertFalse(h.tick(almost + 200L))
        assertEquals(ResetVaultModel.HOLD_MILLIS - 200L, h.remaining(almost + 200L))
    }

    @Test
    fun `松手之后进度和剩余立刻复位`() {
        val h = hold()
        h.press(0L)
        h.tick(1000L)
        h.release()
        assertFalse(h.holding)
        assertEquals(0f, h.progress(1000L), 0f)
        assertEquals(ResetVaultModel.HOLD_MILLIS, h.remaining(1000L))
    }

    @Test
    fun `完成之后再按一次仍然要重新按满三秒`() {
        val h = hold()
        h.press(0L)
        assertTrue(h.tick(ResetVaultModel.HOLD_MILLIS))

        h.press(10_000L)
        assertFalse(h.tick(10_000L + ResetVaultModel.HOLD_MILLIS - 1))
        assertTrue(h.tick(10_000L + ResetVaultModel.HOLD_MILLIS))
    }

    /* ═════════════ 时钟不老实的时候 ═════════════ */

    /**
     * 这一条是第三个理由。现在传进来的是 `withFrameMillis` 的帧时间（单调递增），
     * 所以它是防将来的：万一某天换成了会回退的时间源，
     * 进度不能变成负数，更不能因为 `elapsed >= total` 在负数上意外成立而直接触发。
     */
    @Test
    fun `时钟倒退时进度不为负也不会触发`() {
        val h = hold()
        h.press(10_000L)
        assertEquals(0f, h.progress(9_000L), 0f)
        assertEquals(ResetVaultModel.HOLD_MILLIS, h.remaining(9_000L))
        assertFalse(h.tick(9_000L))
        assertFalse(h.tick(0L))
    }

    /* ═════════════ 和文案对得上 ═════════════ */

    /**
     * 按钮上那句「继续按住…N」的 N 由 `ResetVaultModel.holdLabel` 算，
     * 输入是这里给的 `remaining`。两边各自都测过，这一条钉的是**接缝**：
     * 整个按住过程中不能出现「继续按住…0」，也不能一上来就是 2。
     */
    @Test
    fun `按住全程的剩余秒数从三数到一，没有零`() {
        val h = hold()
        h.press(0L)
        val seen = LinkedHashSet<String>()
        var t = 0L
        while (t < ResetVaultModel.HOLD_MILLIS) {
            seen += ResetVaultModel.holdLabel(h.remaining(t))
            t += 16L
        }
        assertEquals(listOf("继续按住…3", "继续按住…2", "继续按住…1"), seen.toList())
    }

    /* ═════════════ 参数本身 ═════════════ */

    @Test
    fun `时长必须为正`() {
        val e = runCatching { HoldProgress(0L) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e is IllegalArgumentException)
    }
}
