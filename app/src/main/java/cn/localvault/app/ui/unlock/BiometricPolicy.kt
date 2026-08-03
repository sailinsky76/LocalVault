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

/**
 * 生物识别失败的**语义**分类。
 *
 * ── 为什么不直接在界面里 when 一遍错误码 ──
 *
 * `BiometricPrompt` 给的是一串平台错误码（`ERROR_LOCKOUT`、`ERROR_CANCELED`……），
 * 十几个值，含义差别很大：有的是用户自己按了取消，有的是安全硬件已经把你锁了 30 秒，
 * 有的是这把钥匙从此作废了。它们该走的路完全不同，而这套判断
 * 恰恰是最容易写错、又最不该靠「在真机上把指纹按错五次」来验证的部分。
 *
 * 所以拆成两段：**错误码 → 这个枚举**是一行 when，放在碰 Android 的那一侧；
 * **枚举 → 怎么处置**在这里，纯 Kotlin，能在 JVM 上钉死。
 */
enum class BiometricFailure {
    /** 用户自己按了取消 / 返回 / 「用主密码」。不是错误。 */
    UserCanceled,

    /** 连续认错太多次，安全硬件把生物识别锁了一小会儿（通常 30 秒）。 */
    TemporaryLockout,

    /** 锁死了，必须先用设备锁屏凭据解锁一次才会恢复。 */
    PermanentLockout,

    /**
     * 指纹库变过了（新增或删除了指纹），Keystore 里那把「认证才能用」的钥匙随之作废。
     * 这是**安全机制正常工作**的表现，不是故障。
     */
    KeyInvalidated,

    /**
     * 传感器**这一刻**用不上：被别的应用占着、系统还没交接完、刚从后台回来。
     *
     * ── 为什么要和 [HardwareUnavailable] 分开 ──
     *
     * 上一版把 `ERROR_HW_NOT_PRESENT`（这台机器没有传感器）和
     * `ERROR_HW_UNAVAILABLE`（传感器现在腾不出手）归成了同一种，
     * 而这两件事的处置正好相反：前者要把指纹入口撤掉，后者**等半秒再试就好**。
     *
     * 合并的代价是真实的：自动锁定后回到应用，指纹框在系统还没把传感器交接完时
     * 就被拉起，拿到 `ERROR_HW_UNAVAILABLE`，于是界面弹一条
     * 「指纹传感器不可用，请用主密码解锁」并把指纹按钮撤掉——
     * 而用户切出去再切回来就好了。他被逼着输长主密码，
     * 为的是一个半秒之后自己就消失的问题。
     */
    HardwareBusy,

    /** 这台设备没有可用的指纹传感器（`ERROR_HW_NOT_PRESENT`）。这一种是不会自己好的。 */
    HardwareUnavailable,

    /** 这台设备一个生物特征都没录。 */
    NoneEnrolled,

    Other,
}

/**
 * 每种失败该怎么处置。
 *
 * ── 贯穿这一整个对象的一条原则 ──
 *
 * **生物识别的失败一律不计入我们自己的退避。**
 * 指纹认不认得出来，是 BiometricPrompt 和安全硬件之间的事，
 * 那边已经有自己的限速（连错五次锁 30 秒，再错锁死）。
 * 我们再罚一次，等于同一件事收两遍钱：用户的手指湿了几次，
 * 换来的是连主密码也要等 15 分钟才能输——而攻击者根本不会走这条路。
 *
 * 判断「该不该计入退避」的标准始终是：**这次失败有没有消耗掉一次猜测机会。**
 * 指纹没有，PIN 和主密码有。
 */
object BiometricPolicy {

    /**
     * 要不要在界面上显示一条错误。
     *
     * 用户自己按取消时**不显示**。这看起来是小事，实际上很关键：
     * 「按取消 → 弹出一条红色错误」会让人以为自己做错了什么，
     * 而他只是想换用主密码而已。取消是一个正常出口，不是异常。
     */
    fun shouldShowMessage(failure: BiometricFailure): Boolean =
        failure != BiometricFailure.UserCanceled

    /**
     * 这次失败之后，指纹这条路还走得通吗。
     * 走不通就要把界面切到主密码，并且**不要**再把指纹按钮摆在最显眼的位置——
     * 让用户对着一个已经不可能成功的按钮反复按，是最消磨信任的一种设计。
     */
    fun biometricStillUsable(failure: BiometricFailure): Boolean = when (failure) {
        BiometricFailure.UserCanceled -> true
        BiometricFailure.TemporaryLockout -> true      // 等一会儿还能用
        BiometricFailure.PermanentLockout -> false
        BiometricFailure.KeyInvalidated -> false
        BiometricFailure.HardwareBusy -> true          // 等半秒就好，别撤按钮
        BiometricFailure.HardwareUnavailable -> false
        BiometricFailure.NoneEnrolled -> false
        BiometricFailure.Other -> true
    }

    /**
     * 要不要把绑好的那份生物识别包裹删掉。
     *
     * 只有 [BiometricFailure.KeyInvalidated] 需要：那把 Keystore 钥匙已经作废，
     * 留着一个永远解不开的密文，只会让每次解锁都多失败一次。
     * （`QuickUnlock.beginBiometricUnlock` 在抛出这个异常之前已经自己删过了，
     * 这里的返回值是给界面用的——它决定要不要把「指纹」按钮从这一屏上撤下去。）
     *
     * 反过来说，**锁死（PermanentLockout）不能删**：那只是暂时进不去，
     * 用户拿设备锁屏凭据解锁一次就恢复了。删掉等于替他做了一个他没同意的决定。
     */
    fun shouldForgetEnrollment(failure: BiometricFailure): Boolean =
        failure == BiometricFailure.KeyInvalidated

    /**
     * 给用户看的话。
     *
     * 三条写作要求：说清楚发生了什么、说清楚下一步做什么、
     * 以及——凡是可能让人担心数据的场合，明确说数据没事。
     */
    fun message(failure: BiometricFailure): String = when (failure) {
        BiometricFailure.UserCanceled ->
            ""   // 不显示，见 shouldShowMessage

        BiometricFailure.TemporaryLockout ->
            "指纹连续多次未通过，系统已暂时停用指纹识别。稍等片刻可再试，也可以直接用主密码。"

        BiometricFailure.PermanentLockout ->
            "指纹识别已被系统锁定。请先用手机自己的锁屏密码解锁一次来恢复它；" +
                "这个保险库现在可以用主密码打开。"

        BiometricFailure.KeyInvalidated ->
            "检测到本机指纹有变动，出于安全，原来绑定的指纹解锁已自动失效。" +
                "这不是故障，保险库里的数据一条没动——用主密码打开后可以重新绑定。"

        BiometricFailure.HardwareBusy ->
            // 自动重试都用光了才会看到这一句，所以它得给出一条用户自己能走的路：
            // 再按一下那个按钮。**不写**「请用主密码解锁」——指纹这条路还在，
            // 把人往主密码上赶等于替他放弃了一条还能走的路。
            "指纹传感器正忙，刚才没能弹出指纹框。可以再按一下指纹，也可以改用主密码。"

        BiometricFailure.HardwareUnavailable ->
            "这台设备的指纹传感器用不了。请用主密码解锁。"

        BiometricFailure.NoneEnrolled ->
            "这台设备还没有录入指纹。请在系统设置里录入后再回来绑定，现在请用主密码解锁。"

        BiometricFailure.Other ->
            "指纹解锁没有成功。请重试，或用主密码解锁。"
    }
}
