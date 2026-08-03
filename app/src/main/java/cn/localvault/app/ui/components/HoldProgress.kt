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

package cn.localvault.app.ui.components

/**
 * 「按住不放 N 毫秒」这件事的计时账本。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 和 `ResetVaultModel` / `AttemptLimiter` 同一个套路：
 * 手势由界面负责认（按下 / 抬起 / 被父容器抢走），
 * 「到没到时候、还剩几秒、这一次算不算数」由这里说了算，于是能纯 JVM 测。
 *
 * ── 为什么值得单独抽出来 ──
 *
 * 它看着只是一道减法，但有三条性质是肉眼在真机上很难验的，
 * 而这个按钮底下挂的是全 App 第二个不可逆的动作（决策(126)~(128)）：
 *
 *   1. **完成只报一次。** 帧回调是一秒六十次，越过终点那一帧之后
 *      还会继续来帧；漏掉这条，`onComplete` 会被连着调几十次，
 *      而它调的是 `ResetVaultController.submit()`。
 *      （控制器自己也挡着 `if (busy || done) return`，但那是第二道门，
 *      不是不设第一道门的理由。）
 *   2. **松手是中止，不是暂停。** 再按下必须从零开始重新计。
 *      做成暂停的话，「按三下每下一秒」就等于按住三秒，
 *      这道门拦的那个「刚打完字的手指顺势再点一下」就白设了。
 *   3. **时钟倒着走也不能凑出进度。** 传进来的 now 由调用方给，
 *      万一某天换成了会回退的时间源，进度不能变成负数、更不能因为
 *      `elapsed >= total` 在负数上意外成立而直接触发。
 *
 * ── 时间从外面传进来 ──
 *
 * 这个类里没有任何时钟。同 `AttemptLimiter` / `UnlockController` 的退避：
 * 谁调用谁给 now，于是测试里可以把三秒走完而不用真等三秒。
 * 界面那边给的是 `withFrameMillis` 的帧时间（单调递增，且天然按帧对齐）。
 */
class HoldProgress(val totalMillis: Long) {

    init {
        require(totalMillis > 0L) { "按住时长必须为正" }
    }

    /** 这一轮是什么时候按下去的。null = 现在没按着。 */
    private var startedAt: Long? = null

    /** 这一轮有没有已经报过完成。见类注释第 1 条。 */
    private var fired: Boolean = false

    val holding: Boolean get() = startedAt != null

    /**
     * 按下。**每次按下都是新的一轮**——把上一轮的账全部抹掉，
     * 而不是接着上次的进度往下走。见类注释第 2 条。
     */
    fun press(now: Long) {
        startedAt = now
        fired = false
    }

    /**
     * 松手（或者被父容器的滚动抢走、或者手势被取消）。
     *
     * 三种情况一律按同一件事处理：这一轮不算数。
     * 「滑走算不算松手」这个问题在这一页上没有讨论余地——
     * 用户手指移开了原地，就不能再当成他一直按着。
     */
    fun release() {
        startedAt = null
        fired = false
    }

    /** 已经按了多久。没按着、或者时钟倒退时都是 0，不会是负数。 */
    fun elapsed(now: Long): Long {
        val s = startedAt ?: return 0L
        return (now - s).coerceAtLeast(0L)
    }

    /** 还差多久。到点之后是 0，不会继续往下减成负数。 */
    fun remaining(now: Long): Long = (totalMillis - elapsed(now)).coerceAtLeast(0L)

    /** 0f..1f。给按钮上那条填充用。 */
    fun progress(now: Long): Float =
        (elapsed(now).toFloat() / totalMillis.toFloat()).coerceIn(0f, 1f)

    /**
     * 走一帧。
     *
     * @return 只有**恰好越过终点的那一次**返回 true，此后一直返回 false，
     *         直到下一次 [press]。调用方拿它当「现在可以动手了」的信号。
     */
    fun tick(now: Long): Boolean {
        if (startedAt == null || fired) return false
        if (elapsed(now) < totalMillis) return false
        fired = true
        return true
    }
}
