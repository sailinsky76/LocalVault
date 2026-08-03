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

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.autofill.FillRequest
import android.service.autofill.InlinePresentation
import android.util.Log
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import cn.localvault.app.MainActivity

/**
 * [InlinePlan.Slot] → `InlinePresentation`。**这一侧只有平台活，一个判断都没有。**
 *
 * 它和 [InlinePlan] 之间那条缝，同 [AutofillViews] / [AutofillRow]：
 * 「摆几格、摆哪几条、用第几份规格」在纯 Kotlin 那一侧（[InlinePlan]，34 条用例钉着），
 * 「怎么变成平台对象」在这一侧——塞错了在真机上当场看得见，而那正是这种代码
 * 该待的地方。
 *
 * ── 这里传出去的每一个字同样会离开这个进程 ──
 *
 * `Slice` 和 `RemoteViews` 一样要被 parcel 出去交给系统，再交给**输入法进程**
 * 去画（[AutofillViews] 文件头那一段一字不差地适用于这里）。所以这个文件
 * 也只接洗过的 [AutofillRow.Chip]，不接 [AutofillOffer.Item]，
 * 更不接 `FillPlan.Write`——「内联那一格上不会出现密码」于是同样是
 * 一件**编译期**的事（决策(218)）。
 *
 * ── 为什么整个类都挂着 `@RequiresApi(R)` ──
 *
 * `InlineSuggestionsRequest` / `InlinePresentation` 是 Android 11 才有的类型。
 * minSdk 是 26，所以这个文件里凡是碰到它们的地方都必须在 SDK 检查后面；
 * [from] 是唯一的入口，那一句检查也就写在那儿。低版本上 [Support] 这个类
 * **一次都不会被加载**（返回的是 null，而 null 不触发类加载），
 * 于是它字段上那些 11 才有的类型永远不会被解析。
 */
internal object InlineViews {

    private const val TAG = "AutofillSvc"

    /**
     * 一次请求里内联那一侧的全部家当：输入法要几格（已摊成 [InlinePlan.Ask]）、
     * 以及那几份规格本身（装 `InlinePresentation` 时要原样交回去）。
     *
     * 拿一个对象把两样绑在一起，是为了让接线那一侧**不可能**拿 A 的格数去配 B 的规格。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    class Support private constructor(
        val ask: InlinePlan.Ask,
        private val specs: List<InlinePresentationSpec>,
    ) {

        /**
         * 一格。[slot] 为 null（这一条没进内联条）时返回 null，
         * 调用方照原样把 null 交给 `Dataset.Builder` 就行——
         * **不摆内联的那一条仍然在浮层里**，它没有消失。
         *
         * 任何一步失败都返回 null 而不是抛：这段代码跑在别人的应用触发的
         * 一次系统回调里（同 `VaultAutofillService` 文件头），
         * 一次未捕获的异常等于这一屏什么都不出。
         */
        fun presentation(context: Context, slot: InlinePlan.Slot?): InlinePresentation? {
            if (slot == null) return null
            val spec = specs.getOrNull(slot.specIndex) ?: return null
            return runCatching { build(context, spec, slot.chip) }
                .onFailure {
                    // 只打异常类名（决策(144)）
                    Log.w(TAG, "内联那一格装不出来：${it.javaClass.simpleName}")
                }
                .getOrNull()
        }

        /**
         * `androidx.autofill` 里那三行。
         *
         * `newContentBuilder` 收一个**归属 `PendingIntent`**：用户在建议条上
         * 长按那一格时由输入法拉起它。指向 [MainActivity]（也就是保险库自己），
         * 而且是 `FLAG_IMMUTABLE` 的——它和解锁跳板那个不一样，
         * 系统不会往里塞任何东西，也就没有理由让别人改得动它。
         *
         * `pinned = false`（决策(219)）：钉住的那一格会**长期占着**别人键盘上的位置，
         * 连他打字的时候也在。这条建议条是输入法的地盘，我们只在他点到
         * 一个账号框或密码框的时候出现。
         */
        @SuppressLint("RestrictedApi")
        private fun build(
            context: Context,
            spec: InlinePresentationSpec,
            chip: AutofillRow.Chip,
        ): InlinePresentation {
            val content = InlineSuggestionUi.newContentBuilder(attribution(context))
                .setTitle(chip.title)
                .setSubtitle(chip.subtitle)
                // 读屏软件念的是标题那一句。副标题里是账号，念出来等于在公共场合
                // 报一遍用户名——那一句留给屏幕，不进无障碍描述
                .setContentDescription(chip.title)
                .build()
            return InlinePresentation(content.slice, spec, false)
        }

        private fun attribution(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQ_ATTRIBUTION,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        companion object {

            /**
             * 读那份请求。**这里一个判断都不做**：读到什么就是什么，
             * 「够不够摆」由 [InlinePlan.forOffer] / [InlinePlan.forUnlock] 说。
             *
             * `getVersions` 那一句放在 `runCatching` 里，是因为规格里那个
             * `style` Bundle 是**输入法给的**：它可以是空的、可以是别的版本、
             * 也可以是一份我们读不动的东西。读不出来就当作「这一份不认得」，
             * 于是 [InlinePlan] 会给出 `NoStyle` 并整份退回浮层（决策(214)）。
             */
            @SuppressLint("RestrictedApi")
            fun of(request: InlineSuggestionsRequest?): Support? {
                if (request == null) return null
                val specs = request.inlinePresentationSpecs
                val v1 = specs.map { spec ->
                    runCatching {
                        UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)
                    }.getOrDefault(false)
                }
                return Support(InlinePlan.ask(request.maxSuggestionCount, v1), specs)
            }
        }
    }

    /**
     * 唯一的入口。Android 10 及以下、或者输入法不支持内联时返回 null，
     * 后面整条链拿到的就是 `ask = null` → [InlinePlan.Why.NoRequest] → 走浮层。
     *
     * **这一句 SDK 检查是全工程唯一一处判断内联可用性的地方。**
     */
    fun from(request: FillRequest): Support? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            null
        } else {
            Support.of(request.inlineSuggestionsRequest)
        }

    /** 归属那个 `PendingIntent` 的请求码。必须和 `AutofillResponses` 里那两个都不同。 */
    private const val REQ_ATTRIBUTION = 0x10CC
}
