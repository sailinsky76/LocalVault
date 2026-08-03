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

package cn.localvault.app.ui.settings

import cn.localvault.app.core.keystore.QuickUnlock
import cn.localvault.app.ui.util.SecureClipboard

/**
 * [VaultRemnants] 的线上实现。薄到没有任何自己的判断——
 * 所有「该不该、按什么顺序」都在 [DeleteVaultController] 里，这里只负责「怎么清」。
 *
 * 单独一个文件是有意的，同 `QuickUnlockGuard` 之于 `UnlockGuard`：
 * 这样 `DeleteVaultController.kt` 和 `DeleteVaultModel.kt` 里
 * 一行 `android.*`、一行 Keystore、一行 SharedPreferences 都不会出现，
 * 「删库的顺序可以纯 JVM 测」这句话就不是靠自觉维持的，是靠 import 表维持的。
 */
class QuickUnlockRemnants(
    private val quickUnlock: QuickUnlock,
    private val clipboard: SecureClipboard,
) : VaultRemnants {

    /**
     * 调的是 [QuickUnlock.disableAll]，**不是** `UnlockGuard.disableQuickUnlock()`。
     *
     * 后者会刻意把退避状态原样存回去（关掉 PIN 不能顺带把等待时间清零，
     * 否则连错十次的收益是「少等一会儿」，等于奖励爆破）。
     * 这里不需要那份小心：库马上就不存在了，一份针对不存在的库的失败计数
     * 留下来只有坏处——它会原封不动地作用在用户下一次新建的那个库上，
     * 表现是「刚建好的库，第一次解锁就被告知还要等 15 分钟」。
     *
     * `disableAll()` 内部依次做三件事：清两份包裹和 PIN 的盐 / KDF 参数、
     * 清三个计数器、删掉 Keystore 里那两把钥匙。
     */
    override fun purgeQuickUnlock() {
        quickUnlock.disableAll()
    }

    /**
     * 剪贴板里可能还躺着刚从这个库里复制出去的一个密码。
     *
     * [SecureClipboard.clearNow] 只会清掉**我们自己放进去的那一份**
     * （靠 ClipDescription 里那个一次性 token 认），
     * 用户从别处复制的东西不会被顺手抹掉——那不是我们的东西。
     */
    override fun clearClipboard() {
        clipboard.clearNow()
    }
}
