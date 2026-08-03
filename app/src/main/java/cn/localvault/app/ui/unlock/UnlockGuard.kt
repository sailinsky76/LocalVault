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

/**
 * 解锁时要用到的、但全都长在 Android 上的那几样东西的抽象。
 *
 * 失败计数存在 SharedPreferences 里、PIN 包裹外面套着 Keystore 的设备绑定密钥——
 * 这两样在 JVM 上都跑不起来。而「连错几次该等多久」「什么时候该关掉快捷解锁」
 * 恰恰是解锁流程里最容易写错、又最不该靠上机点来验证的部分。
 * 所以把它们收进这个接口，[UnlockController] 只认接口，于是那部分逻辑能在纯 JVM 上测。
 *
 * 线上实现见 [QuickUnlockGuard]。
 */
interface UnlockGuard {

    /**
     * 退避状态。**三种解锁方式共用这一份。**
     *
     * 分开计数是个看起来更细致、实际上是漏洞的做法：攻击者会挑没被锁的那个入口继续试，
     * 于是「等 15 分钟」变成「换个门再来」。锁定期一到，所有入口一起关。
     */
    var attemptState: AttemptLimiter.State

    /**
     * **快捷解锁**（PIN / 生物）连续失败的次数。和 [attemptState] 里的总次数分开记。
     *
     * 为什么要多这一个计数器，见 [UnlockController.recordFailure] 的说明——
     * 一句话：主密码输错不该导致 PIN 被关掉。
     */
    var quickFailCount: Int

    val isPinEnrolled: Boolean
    val isBiometricEnrolled: Boolean
    val isAnyEnrolled: Boolean get() = isPinEnrolled || isBiometricEnrolled

    /**
     * PIN → 库主密钥。
     * @throws cn.localvault.app.core.keystore.WrongPinException PIN 不对
     */
    fun unlockWithPin(pin: CharArray): SecureBytes

    /**
     * 关掉全部快捷解锁，退回只能用主密码。
     *
     * **这不删任何数据。** 见 [AttemptLimiter] 的注释：连错 N 次就清库
     * 等于把一个拒绝服务漏洞送给任何能碰到这台手机的人。
     */
    fun disableQuickUnlock()
}
