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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import cn.localvault.app.ui.settings.AutofillSettingsModel.Availability

/**
 * 自动填充设置页碰 `android.*` 的那一整侧。**只有这一个文件。**
 *
 * 分工同 `SecuritySettingsScreen` 底下那个 `Int.toSupport()`：
 * 平台返回什么 → 语义，一个 `when` 就完；
 * 「这四档各该说什么话」全在 [AutofillSettingsModel] 里，那边纯 JVM 能测。
 *
 * 这个文件**不能**被单测（`AutofillManager` 是系统服务，
 * `Settings.ACTION_*` 要真的有个系统设置应用在），
 * 所以它里面刻意一个判断都不做——四档怎么分是下面那三行 if 的事，
 * 而那三行是照着平台文档逐字写的，没有可发挥的余地。
 */

/**
 * 问一次这台设备此刻的状态。
 *
 * ── 三个方法，问的是三件不同的事 ──
 *
 * · `isAutofillSupported()` —— 这台设备的系统里有没有自动填充这套东西。
 *   个别精简 ROM 是真的没有。
 * · `hasEnabledAutofillServices()` —— **调用方自己**是不是当前的填充服务。
 *   注意它问的是「我」，不是「有没有人」。
 * · `isEnabled()` —— 当前用户有没有设过任意一个填充服务。
 *
 * 顺序不能换：`hasEnabledAutofillServices()` 必须问在 `isEnabled()` 前面，
 * 因为我们自己当服务的时候两个都是 true，先问后者会把 [Availability.Ours]
 * 误判成 [Availability.OtherService]——屏幕上的表现是
 * 「明明已经设好了，这一页却让我去把别人换掉」。
 *
 * 拿不到服务、或者任何一步抛异常，一律当 [Availability.Unsupported]。
 * 这一档的文案是「这台设备上没有自动填充」，而那句话在
 * 「有但我们问不出来」的情况下也不算撒谎——用户照着它做（什么都不做）
 * 是对的，我们既没让他去点一个不存在的开关，也没答应他一件办不到的事。
 */
fun Context.autofillAvailability(): Availability {
    val manager = runCatching { getSystemService(AutofillManager::class.java) }.getOrNull()
        ?: return Availability.Unsupported
    return runCatching {
        when {
            !manager.isAutofillSupported -> Availability.Unsupported
            manager.hasEnabledAutofillServices() -> Availability.Ours
            manager.isEnabled -> Availability.OtherService
            else -> Availability.NoService
        }
    }.getOrDefault(Availability.Unsupported)
}

/**
 * 跳到系统那张「选择自动填充服务」的确认屏，本应用已经选中。
 *
 * `ACTION_REQUEST_SET_AUTOFILL_SERVICE` **必须带一个 `package:` 的 data**，
 * 不带的话平台直接抛 `IllegalArgumentException`——它就是靠这个知道
 * 要把哪个应用摆在那张确认屏上的。
 *
 * 这个 Intent **不需要任何权限**（同 `ACTION_BIOMETRIC_ENROLL`）。
 * 关于页那份权限清单仍然只有 `USE_BIOMETRIC` 一条，
 * 这是这一页能有这个按钮的前提。
 *
 * 拉不起来时退回系统设置总页；再拉不起来就**什么都不做**——
 * 「去系统设置里找『自动填充服务』」这句话已经写在按钮上方了，
 * 用户照着做完全走得通，没必要为此再弹一条错误来添乱（同指纹那边）。
 */
fun Context.openAutofillServicePicker() {
    val request = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
        .setData(Uri.parse("package:$packageName"))
    tryStart(request, Intent(Settings.ACTION_SETTINGS))
}

/**
 * 跳到系统设置，让用户自己去换掉或关掉。
 *
 * ── 为什么这里不用上面那个 `ACTION_REQUEST_SET_AUTOFILL_SERVICE` ──
 *
 * 因为那张屏问的是「要不要把**本应用**设为填充服务」，
 * 而走到这条路上的用户想做的正好相反。把他送到一张
 * 「确定要启用本地保险库吗」的确认屏上，他会以为自己点错了地方。
 *
 * Android 没有公开一个「打开自动填充服务列表」的 action
 * （只有上面那个带包名的请求屏），所以这里只能到设置总页为止。
 * 页面上那句话因此必须写成「去系统设置里换成别的、或者选『无』」
 * 而不是「点这里关闭」——按钮送到哪儿，话就说到哪儿。
 */
fun Context.openSystemSettings() {
    tryStart(Intent(Settings.ACTION_SETTINGS))
}

/**
 * 跳到本应用那张通知设置页。
 *
 * 只在用户已经拒过一次 `POST_NOTIFICATIONS` 之后才走这条路：系统那张请求框
 * **一辈子只弹一次**，第二次调 `launch` 会当场返回「未授予」而不弹任何东西，
 * 表现是「按钮按下去没反应」——正是这一版在修的那种症状，不能自己再造一个。
 *
 * `ACTION_APP_NOTIFICATION_SETTINGS` 是 26+ 的公开 action，不需要任何权限。
 * 拉不起来时退回本应用的「应用信息」页，再退回系统设置总页。
 */
fun Context.openAppNotificationSettings() {
    val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$packageName"))
    tryStart(direct, details, Intent(Settings.ACTION_SETTINGS))
}

private fun Context.tryStart(vararg intents: Intent) {
    for (intent in intents) {
        if (runCatching { startActivity(intent) }.isSuccess) return
    }
}
