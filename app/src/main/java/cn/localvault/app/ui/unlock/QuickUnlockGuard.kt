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

package cn.localvault.app.ui.unlock

import cn.localvault.app.core.crypto.SecureBytes
import cn.localvault.app.core.keystore.AttemptLimiter
import cn.localvault.app.core.keystore.QuickUnlock

/**
 * [UnlockGuard] 的线上实现。薄到没有任何自己的判断——
 * 所有「该不该」都在 [UnlockController] 里，这里只负责「怎么存」。
 *
 * 单独一个文件是有意的：这样 `UnlockGuard.kt` 和 `UnlockController.kt`
 * 两个文件里一行 `android.*` 的 import 都没有，
 * 「解锁逻辑可以纯 JVM 测」这句话就不是靠自觉维持的，是靠 import 表维持的。
 */
class QuickUnlockGuard(private val quickUnlock: QuickUnlock) : UnlockGuard {

    override var attemptState: AttemptLimiter.State
        get() = quickUnlock.attemptState
        set(value) { quickUnlock.attemptState = value }

    override var quickFailCount: Int
        get() = quickUnlock.quickFailCount
        set(value) { quickUnlock.quickFailCount = value }

    override val isPinEnrolled: Boolean get() = quickUnlock.isPinEnrolled
    override val isBiometricEnrolled: Boolean get() = quickUnlock.isBiometricEnrolled

    override fun unlockWithPin(pin: CharArray): SecureBytes = quickUnlock.unlockWithPin(pin)

    /**
     * 注意调的是 [QuickUnlock.disableAll] 而不是它内部那个连退避计数一起清掉的路径：
     * 关掉快捷解锁之后退避**必须继续生效**。
     * 否则连错 10 次的收益就是「PIN 没了，但等待时间也归零了」，
     * 攻击者反而少等一会儿——那是在奖励爆破。
     */
    override fun disableQuickUnlock() {
        val keep = quickUnlock.attemptState
        quickUnlock.disableAll()
        quickUnlock.attemptState = keep
    }
}
