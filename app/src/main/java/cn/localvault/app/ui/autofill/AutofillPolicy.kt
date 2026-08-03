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

package cn.localvault.app.ui.autofill

import android.content.Context
import android.content.SharedPreferences

/**
 * 自动填充那几项「用户说了算」的设置，落在哪儿、怎么读。
 *
 * **这个文件是薄壳：只有存取，没有一条规则。** 规则那一侧在
 * [FieldRoles.DEFAULT_RESPECT_OPT_OUT]，那儿写着这个值是什么意思、
 * 为什么默认是现在这个样子；这儿只回答「上次用户选的是什么」。
 *
 * ── 为什么存在 `SharedPreferences` 里，而不是库文件里 ──
 *
 * 自动锁定那几项设置存在 `VaultModel` 里（跟着库走、换机跟着搬），
 * 这一项**不能**跟着学，理由是**读它的时候库可能是锁着的**：
 *
 * ```
 *   compose() → AssistShell.parse → FillPlan.forRequest   ← 判定在这儿，读得到策略
 *             → AutofillOffer.respond(state = ...)        ← 到这一步才看库锁没锁
 * ```
 *
 * 判定跑在看锁之前。策略要是躺在密文里，锁着的时候读不出来，只能退回默认值——
 * 于是同一屏同一个框，库开着和锁着会判出两个结论。用户看到的是
 * 「解锁前后填充条不一样」，而这件事没有任何办法解释得通。
 *
 * 存明文也不泄露什么：它是一个布尔，说的是这台设备的主人怎么设置这个应用，
 * 不是他去过哪个网站、存了哪些条目（那些一个字节都不在这儿，见决策(144)）。
 *
 * ── 换机之后要重设一次 ──
 *
 * 代价是它不跟着库走：备份恢复到新机器上，这一项回到默认。可以接受——
 * 自动填充服务本来就要在新机器上重新在系统设置里选一次，这一项就在同一页上。
 */
class AutofillPolicy(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 听不听应用的「请勿自动填充」声明。
     *
     * **每次请求现读，不缓存。** 服务实例活得比一次请求长（系统在会话之间会
     * 解绑重连，见 `VaultAutofillService.onDisconnected`），而用户完全可能
     * 在两次填充之间去设置页改这一项——它和主界面在同一个进程里，
     * 改完那一下不会有任何人来通知服务。缓存的后果是改完之后要等到
     * 进程被杀才生效，而用户会以为这个开关坏了。
     *
     * 一次 `getBoolean` 是内存里的一次哈希查找（`SharedPreferences` 首次加载后
     * 常驻内存），放在 `onFillRequest` 开头不构成任何负担。
     */
    var respectOptOut: Boolean
        get() = prefs.getBoolean(KEY_RESPECT_OPT_OUT, FieldRoles.DEFAULT_RESPECT_OPT_OUT)
        set(value) {
            prefs.edit().putBoolean(KEY_RESPECT_OPT_OUT, value).apply()
        }

    private companion object {
        /** 和 `lv_quick_unlock` 分开：那一份里躺着包裹和失败计数，这一份只有偏好。 */
        const val PREFS = "lv_autofill"
        const val KEY_RESPECT_OPT_OUT = "respect_opt_out"
    }
}
