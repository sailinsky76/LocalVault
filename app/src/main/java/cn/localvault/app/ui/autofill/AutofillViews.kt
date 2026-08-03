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

import android.view.View
import android.widget.RemoteViews
import cn.localvault.app.R

/**
 * [AutofillRow.Row] → `RemoteViews`。**整个文件就是三次 setText 加一次 setViewVisibility。**
 *
 * 它和 [AutofillRow] 之间那条缝，同 `AssistShell` / `StructureRules`：
 * 「摆什么、洗成什么样」在纯 Kotlin 那一侧（59 条用例钉着），
 * 「怎么塞进平台对象」在这一侧（塞错了当场看得见）。
 *
 * ── 这里传出去的每一个字都会离开这个进程 ──
 *
 * `RemoteViews` 的名字是字面意思：它被打包成 `Parcel` 交给**系统进程**，
 * 由系统去 inflate、去测量、去画。所以凡是走进这个文件的字符串，
 * 都等于已经公开了——输入法看得见，无障碍服务看得见，截屏和录屏也录得到。
 *
 * 于是这个文件只接 [AutofillRow.Row]，不接 [AutofillOffer.Item]，
 * 更不接 `FillPlan.Write`。这不是洁癖：`Item` 里虽然也没有密码，
 * 但它手里攥着 `writes`，而 `Write` 里是明文。让这个文件根本拿不到那个对象，
 * 「填充条上不会出现密码」就是一件**编译期**的事，不是一条要靠人记得的纪律。
 */
internal object AutofillViews {

    /**
     * [packageName] 必须是**本应用**的包名：系统要拿它去我们的 APK 里找
     * `R.layout.autofill_row`。传别人的包名会在系统进程里 inflate 失败，
     * 表现是填充条整个不出现，而 logcat 里的那条异常在我们的进程里看不到。
     */
    fun row(packageName: String, row: AutofillRow.Row): RemoteViews =
        RemoteViews(packageName, R.layout.autofill_row).apply {
            setTextViewText(R.id.autofill_row_title, row.title)
            setTextViewText(R.id.autofill_row_subtitle, row.subtitle)
            if (row.badge != null) {
                setTextViewText(R.id.autofill_row_badge, row.badge)
                setViewVisibility(R.id.autofill_row_badge, View.VISIBLE)
            } else {
                // 布局里默认就是 gone，这一句是防 RemoteViews 被复用
                setViewVisibility(R.id.autofill_row_badge, View.GONE)
            }
        }
}
