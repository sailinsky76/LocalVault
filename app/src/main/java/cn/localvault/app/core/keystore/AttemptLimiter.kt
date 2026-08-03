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

/**
 * 解锁失败退避。纯逻辑，不依赖 Android，方便单测。
 *
 * ───────────── 一个刻意不做的设计 ─────────────
 * 很多密码 App 会「连续错 N 次就清空数据库」。我们不做。
 * 因为它把一个**拒绝服务漏洞**送到了任何能碰你手机的人手里：
 * 小孩乱按十次，你的全部密码没了。
 * 真正的威胁模型里，攻击者拿到的是文件而不是界面，
 * 清库既挡不住他，又天天在坑真实用户。
 *
 * 我们的做法是：延迟 + 禁用快捷解锁（退回主密码），数据永远不动。
 */
class AttemptLimiter(
    private val backoffSeconds: IntArray = DEFAULT_BACKOFF,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class State(val failCount: Int = 0, val lockedUntil: Long = 0L)

    /** 还需等待多少毫秒才能再试。0 表示可以立刻试。 */
    fun remainingLockMillis(state: State): Long =
        (state.lockedUntil - now()).coerceAtLeast(0L)

    fun isLocked(state: State) = remainingLockMillis(state) > 0

    fun onFailure(state: State): State {
        val count = state.failCount + 1
        val idx = (count - 1).coerceAtMost(backoffSeconds.size - 1)
        val wait = backoffSeconds[idx]
        return State(count, if (wait > 0) now() + wait * 1000L else 0L)
    }

    fun onSuccess(): State = State()

    /**
     * 是否应该关掉快捷解锁（PIN / 生物），强制回到主密码。
     * 连错 10 次基本可以确定不是本人在按。
     */
    fun shouldDisableQuickUnlock(state: State) = state.failCount >= DISABLE_QUICK_UNLOCK_AT

    companion object {
        /** 第 1~4 次不等，第 5 次起逐步拉长：5s / 15s / 60s / 5min / 15min */
        val DEFAULT_BACKOFF = intArrayOf(0, 0, 0, 0, 5, 15, 60, 300, 900)
        const val DISABLE_QUICK_UNLOCK_AT = 10
    }
}
