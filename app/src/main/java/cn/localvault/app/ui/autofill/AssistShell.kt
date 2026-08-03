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

import android.app.assist.AssistStructure
import android.os.Build
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId

/**
 * `AssistStructure` ↔ 纯 Kotlin 那一侧的**那层皮**。
 *
 * 这是整个 M4 里唯一一个 `import android.*` 的文件（M4-2a 的 `AutofillService`
 * 会是第二个）。它只干两件事：
 *
 *   1. 把 `ViewNode` 上那十几个 getter 读成 [StructureRules.NodeFacts]；
 *   2. 记住「第 n 号句柄是哪个 `AutofillId`」。
 *
 * 走树、继承、上限、组装 [RawField] 全在 [StructureRules] 里，那一侧纯 JVM 可测。
 * 这么切的理由写在 `StructureRules` 的文件头上：走树里藏着三条
 * **错了也不报错、只是从此填错人或者不填**的规则，而它们没有一条需要 `android.*`。
 * 同 `SafImportSource` 之于 `RestoreController`，只是这一次那条缝要划得更靠外。
 *
 * ── 这个文件里读不到用户打的字 ──
 *
 * `ViewNode` 是给得出 `getText()` / `getAutofillValue()` 的，这两个方法在这个文件里
 * **一次都没有出现**（决策(165)）。屏幕上那个密码框里可能正躺着用户刚打了一半的口令，
 * 而 [StructureRules.NodeFacts] 里根本没有能放它的字段——读了也无处可放。
 * M4-3 的保存流程确实要读那个东西，那是另一条路、另一个模型，到时候单独建。
 */
object AssistShell {

    private const val TAG = "AssistShell"

    /**
     * 走完一次请求的结果：交给内核的那份 [FillContext]，
     * 外加一张只有这一次请求里有意义的句柄对照表。
     *
     * 拿着它，M4-2a 装 `Dataset` 的写法就只剩一行：
     * `FillPlan.writes(form, entry).forEach { setValue(parsed.autofillId(it.handle), ...) }`。
     */
    class Parsed internal constructor(
        val context: FillContext,
        private val ids: Map<Long, AutofillId>,
        /** 撞上了 [StructureRules.Limits] 里某个上限，清单是截断的。 */
        val truncated: Boolean,
    ) {
        /**
         * 句柄换 `AutofillId`。
         *
         * **句柄只在这一次请求里有意义**：它就是先序遍历的序号，
         * 下一次请求同一个框可能是别的号。所以不要把它存下来，
         * 更不要拿它当「用户上次在这个框里用了哪一条」的键——那种账本身就不该记（决策(163)）。
         */
        fun autofillId(handle: Long): AutofillId? = ids[handle]

        override fun toString(): String = "Parsed(${context.fields.size} fields)"
    }

    /**
     * 把一次填充请求的结构走成 [Parsed]。
     *
     * **包名拿不到就返回 null，一个框都不收。**
     * `activityComponent` 是系统填的，正常情况下不会是 null；真拿不到时，
     * 唯一还剩的包名来源是节点上的 `idPackage`，而那一栏是**应用自己填的**。
     * 拿它当归属，等于把决策(158) 里那条「最硬的事实」换成一句自称——
     * 一个恶意应用只要在自己的框上写 `idPackage = com.某银行`，
     * 就能把银行密码要走。宁可这一次不出填充条：用户看到的是「这儿没弹出来」，
     * 他去别处复制粘贴一次；而另一条路的代价他一辈子都不会知道。
     */
    fun parse(
        structure: AssistStructure,
        respectOptOut: Boolean = FieldRoles.DEFAULT_RESPECT_OPT_OUT,
    ): Parsed? {
        val activityPackage = structure.activityComponent?.packageName
        if (activityPackage.isNullOrBlank()) {
            Log.w(TAG, "请求里没有 activityComponent，这次不填")
            return null
        }
        return parse(structure, activityPackage, respectOptOut)
    }

    /**
     * 包名已经从别处拿到时走这一个（M4-2a 有 `FillRequest` 在手，两条路都可能）。
     *
     * [respectOptOut] 一路传到 [FillContext] 上，语义见
     * [FieldRoles.DEFAULT_RESPECT_OPT_OUT]。**四个调用点必须传同一个值**：
     * 填充那次和挑选页/解锁页那两次重新解析，只要有一处不一致，
     * 表现就是「填充条上有的条目，点进搜索页就没了」。
     */
    fun parse(
        structure: AssistStructure,
        activityPackage: String,
        respectOptOut: Boolean = FieldRoles.DEFAULT_RESPECT_OPT_OUT,
    ): Parsed {
        val walker = StructureRules.Walker(ViewNodeTree)

        // 一次请求可能带好几个窗口（对话框浮在 Activity 上、输入法自己那一层）。
        // 句柄在整个 Walker 里连续发，三个上限也是整体算的。
        val windows = runCatching { structure.windowNodeCount }.getOrDefault(0)
        for (i in 0 until windows) {
            val root = runCatching { structure.getWindowNodeAt(i)?.rootViewNode }.getOrNull()
            if (root != null) walker.feed(root)
        }

        val picked = walker.picked
        val ids = HashMap<Long, AutofillId>(picked.size)
        for (p in picked) {
            val id = p.node.autofillId
            if (id != null) ids[p.field.handle] = id
        }

        // 这一行日志刻意只有数字：包名、主机名、页面文案一个都不打。
        // 「这台手机的主人在什么应用里登录了哪个站」是一份不该外泄的清单（决策(144)）。
        Log.d(TAG, "结构：$windows 窗口 → ${picked.size} 个框，截断=${walker.truncated}")
        // 排查用的详细转储。release 包里这一句连同它里面的字符串一起会被 R8 删掉，
        // 见 AutofillDebug 文件头——决策(144) 那条规矩没有放松，
        // 只是在 debug 包上开了一扇门，而门的边界是编译期的，不是运行期的。
        AutofillDebug.structure(activityPackage, picked)

        return Parsed(
            context = StructureRules.contextOf(activityPackage, picked, respectOptOut),
            ids = ids,
            truncated = walker.truncated,
        )
    }

    /**
     * `ViewNode` 那一侧的 [StructureRules.Tree] 实现。**整个 object 就是一串 getter。**
     *
     * 几个 getter 是后来才加进平台的，而这个工程 minSdk = 26：
     *   · `getImportantForAutofill()` —— API 28。低版本上退回 `AUTO`
     *     （那两个版本上应用没法通过这一栏说「别填我」，只能靠别的信号）；
     *   · `getWebScheme()` —— API 28。低版本上是 null，
     *     [StructureRules.webDomainClaim] 对 null 的处理写在那儿了。
     *
     * 低版本上直接调这两个方法**不会编译失败也不会立刻崩**，它会在真机上抛
     * `NoSuchMethodError`——一个 Error，顺着 `runCatching` 变成一次静悄悄的「这次不填」，
     * 而 26/27 两个版本上从此再也没有填充条。所以这里老老实实按版本分叉。
     */
    private object ViewNodeTree : StructureRules.Tree<AssistStructure.ViewNode> {

        override fun childCount(node: AssistStructure.ViewNode): Int = node.childCount

        override fun childAt(node: AssistStructure.ViewNode, index: Int): AssistStructure.ViewNode? =
            node.getChildAt(index)

        override fun facts(node: AssistStructure.ViewNode): StructureRules.NodeFacts {
            val html = node.htmlInfo
            return StructureRules.NodeFacts(
                autofillable = node.autofillId != null,
                autofillHints = node.autofillHints?.filterNotNull().orEmpty(),
                inputType = node.inputType,
                autofillType = node.autofillType,
                importantForAutofill = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    node.importantForAutofill
                } else {
                    AndroidInput.IMPORTANT_FOR_AUTOFILL_AUTO
                },
                idEntry = node.idEntry,
                hintText = node.hint,
                contentDescription = node.contentDescription?.toString(),
                htmlTag = html?.tag,
                // `HtmlInfo.getAttributes()` 给的是 `android.util.Pair`，
                // 而 NodeFacts 那一侧是纯 Kotlin、只认 `kotlin.Pair`。
                // 这层皮存在的意义就是把这种平台类型挡在门外，所以在这儿转掉。
                // 名字为 null 的属性直接丢弃（拿它当键没有意义），值为 null 时按空串算。
                htmlAttributes = html?.attributes?.mapNotNull { attr ->
                    if (attr == null) return@mapNotNull null
                    val name = attr.first ?: return@mapNotNull null
                    name to (attr.second ?: "")
                }.orEmpty(),
                webScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    node.webScheme
                } else {
                    null
                },
                webDomain = node.webDomain,
                // `visibility` 说的只是这个节点自己；祖先那一层由 StructureRules 兜着。
                visible = node.visibility == View.VISIBLE,
                focused = node.isFocused,
            )
        }
    }
}
