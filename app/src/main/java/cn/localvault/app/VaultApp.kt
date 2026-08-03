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

import android.app.Application
import android.util.Log
import cn.localvault.app.core.crypto.Argon2idKdf
import cn.localvault.app.core.keystore.QuickUnlock
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.crypto.KdfParams
import cn.localvault.app.core.crypto.KdfRegistry
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.ui.util.SecureClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * 依赖在这里手工拼装。
 *
 * 没有引入 Hilt / Koin：这个 App 的对象图小到一眼能看完，
 * 而依赖注入框架会带来反射、代码生成和额外的攻击面。
 * 对一个把「权限清单干净」当卖点的产品来说，少一个依赖就是少一次解释成本。
 */
class VaultApp : Application() {

    /** Argon2 原生库是否可用。不可用时全局降级到 PBKDF2，设置页要如实告知用户。 */
    var argon2Available = false
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: VaultRepository
        private set
    lateinit var session: VaultSession
        private set
    lateinit var quickUnlock: QuickUnlock
        private set

    /**
     * 剪贴板的自动清除计时器挂在 Application 的 scope 上，不是 Activity 的。
     * 「复制密码 → 切到浏览器粘贴」是最常见的路径，
     * 计时器绝不能因为界面进了后台就停摆。
     */
    lateinit var clipboard: SecureClipboard
        private set

    override fun onCreate() {
        super.onCreate()

        argon2Available = Argon2idKdf.registerIfAvailable()
        Log.i(TAG, "KDF: ${if (argon2Available) "Argon2id" else "PBKDF2-HMAC-SHA512（降级）"}")

        // 保险库放在 filesDir 下的私有目录。
        // 不用 getExternalFilesDir：那是所有应用可读的。
        repository = VaultRepository(File(filesDir, "vault"))
        session = VaultSession(repository, appScope)
        quickUnlock = QuickUnlock(this)
        clipboard = SecureClipboard(this, appScope)
    }

    /**
     * **新建库**会用哪一档 KDF。
     *
     * 注意它和「当前这个库实际用的是哪一档」是两回事：后者在
     * [cn.localvault.app.core.session.VaultSession.headerKdfParams] 里，
     * 从文件头读，换机拷过来的库可能是别的档位。
     * MainActivity 里的取值顺序是「解锁了就用文件头的，没解锁才用这个兜底」。
     *
     * 另外这里返回的是**默认档**，建库时真正落进文件头的是
     * [cn.localvault.app.ui.onboarding.CreateVaultController] 校准出来的那一档，
     * 低配机上可能比这里低。所以建库那一屏的提示文案是「将使用」而不是「正在使用」。
     */
    fun currentKdfParams(): KdfParams = KdfRegistry.preferredParams()

    companion object {
        private const val TAG = "VaultApp"
    }
}
