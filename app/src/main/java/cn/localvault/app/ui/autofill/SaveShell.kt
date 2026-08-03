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
import android.util.Log
import android.view.autofill.AutofillId

/**
 * **全工程唯一一处调 `getAutofillValue()` 的地方。**
 *
 * 它是 [AssistShell] 的镜像：那一个把结构摊成「有哪几个框、各是什么角色」，
 * 一次都没读过框里写着什么（[AssistShell] 文件头，决策(165)）；
 * 这一个只读值，一条角色判断都不做。两个文件加起来才是那棵树的全貌，
 * 而**分成两个**是这条边界的物理形式：填充那条路上的代码，
 * 编译期就拿不到用户打进去的字。
 *
 * 走树、分组、挂哪几个框全在别处（[StructureRules] 34 条、[FieldGroups] 65 条、
 * [SavePlan] 28 条），收值的规矩在 [SavedFields]（75 条里的一部分），
 * 拼成 [SaveContext] 在 [SaveCapture]。这里只剩两件平台活：
 * 走一遍树把 `AutofillId → 值` 记下来，然后按句柄查回去。
 *
 * ── 为什么只读 `getAutofillValue()`，不读 `getText()` ──
 *
 * `AssistStructure.ViewNode` 上有两个都能给出「这个框里的字」的方法，
 * 而它们不是一回事：
 *
 *   · `getAutofillValue()` 是**为自动填充准备的那一份**，
 *     系统只在这一次是保存请求（我们自己挂过 `SaveInfo`）时才把它填上；
 *   · `getText()` 是**给屏幕和无障碍看的那一份**。密码框上它可能是
 *     一串圆点（`•••••••`）——把那个存进库，用户下次登录时填出去的就是一串圆点，
 *     而屏幕上当时会明明白白写着「已保存」。
 *
 * 所以这里只认前者，一格拿不到就当没有值（[SaveCapture.Values] 允许这样）。
 * **不许加一句 `?: node.text` 来"兜底"**：那一改在登录页上多半看不出区别
 * （非密码框两者一样），代价只在密码那一格上，而那正是唯一要紧的一格。
 *
 * 同理，`v.isText` 不成立时（开关、日期、下拉）一律不要：
 * 那种框根本不该被 [FieldRoles] 判成账号或密码，真读到了说明上游判错了，
 * 而 `AutofillValue.getTextValue()` 在非文本值上会直接抛。
 *
 * ── 这张表活多久 ──
 *
 * [values] 返回的那个 [SaveCapture.Values] 闭包里**抱着一屏的明文**，
 * 包括用户刚打的密码。它的寿命由调用方负责：`onSaveRequest` 里算完
 * [SaveCapture.capture] 就该丢掉引用，不许存成 Service 的字段。
 * 这条和 [SaveHandoff] 那三条纪律是同一件事的两半——
 * 那边管「交出去的那一份」，这边管「读进来的那一份」。
 */
internal object SaveShell {

    private const val TAG = "AutofillSave"

    /**
     * 走一遍结构，给出「句柄 → 此刻的值」。
     *
     * [parsed] 必须是**拿同一个 [structure] 现算的那一份**（`AssistShell.parse`）。
     * 拿填充那一刻的旧 `Parsed` 来查，句柄对上的是另一个框——理由写在
     * [SaveCapture.capture] 的注释里，那是这一步最容易犯、也最不会被发现的错。
     */
    fun values(structure: AssistStructure, parsed: AssistShell.Parsed): SaveCapture.Values {
        val texts = collect(structure)
        // 这一行只有数字：包名、主机名、读到的值一个都不打（决策(144)）
        Log.d(TAG, "读值：${texts.size} 格有值")
        return SaveCapture.Values { handle -> parsed.autofillId(handle)?.let { texts[it] } }
    }

    /**
     * 把整棵（几棵）树上有值的那些框记下来。
     *
     * 骨架照抄 [StructureRules.Walker]，连三个上限都用它那一份
     * （[StructureRules.Limits]）——两次走的是同一棵树，用两套上限的话，
     * 会出现「填充那一侧收了这个框，保存这一侧走不到它」这种只在超大页面上
     * 才发作的错，而那种页面没人会拿来测。
     *
     * **不递归**，理由同那边：`StackOverflowError` 在你想接住之前就已经发生了。
     *
     * 撞到上限时**保留已经收到的**，不抛也不清空：走到第 6000 个节点还没走完的
     * 页面，剩下的多半也没有我们要的那两格；而把已经读到的一起扔掉，
     * 代价是这一屏从此存不进任何东西。
     */
    private fun collect(structure: AssistStructure): Map<AutofillId, CharSequence> {
        val out = HashMap<AutofillId, CharSequence>(8)
        var nodes = 0

        val windows = runCatching { structure.windowNodeCount }.getOrDefault(0)
        for (w in 0 until windows) {
            val root = runCatching { structure.getWindowNodeAt(w)?.rootViewNode }.getOrNull()
                ?: continue

            val stack = ArrayList<Frame>()
            stack += Frame(root, 0)
            while (stack.isNotEmpty()) {
                if (nodes >= StructureRules.Limits.MAX_NODES) break
                val frame = stack.removeAt(stack.size - 1)
                nodes++

                // 最外面那圈是别人的 App，自定义 View 的 getter 里抛异常是真见过的
                // （同 Walker.feed 里那几个 runCatching）。一个节点读不出来就跳过它，
                // 不该让整屏都存不进去。
                readInto(frame.node, out)

                if (frame.depth >= StructureRules.Limits.MAX_DEPTH) continue
                val count = runCatching { frame.node.childCount }.getOrNull() ?: 0
                for (i in count - 1 downTo 0) {
                    val child = runCatching { frame.node.getChildAt(i) }.getOrNull() ?: continue
                    stack += Frame(child, frame.depth + 1)
                }
            }
        }
        return out
    }

    /**
     * 一个节点上那一格。**这个函数是那道边界本身**，见文件头。
     *
     * 三道都不成立就什么都不记：没有 `AutofillId`（那它压根进不了句柄表）、
     * 没有 `AutofillValue`（这一次不是保存请求，或者那个框系统没给值）、
     * 值不是文本。
     */
    private fun readInto(
        node: AssistStructure.ViewNode,
        out: MutableMap<AutofillId, CharSequence>,
    ) {
        runCatching {
            val id = node.autofillId ?: return@runCatching
            val v = node.autofillValue ?: return@runCatching
            // 非文本值上 getTextValue() 会抛。见文件头末段：真读到说明上游判错了
            if (!v.isText) return@runCatching
            val text = v.textValue ?: return@runCatching
            // 原样放进去，**一个字符都不洗**：取舍是 SavedFields 的事
            // （那边 trim 账号、密码一个字符不动）。在这儿先 trim 一遍，
            // 「以空格结尾的密码原样存下来」这条保证就悄悄失效了
            out[id] = text
        }.onFailure {
            // 只打异常类名（决策(144)）。低版本上缺 getter 抛的是 Error，
            // 顺着这里变成「这一格没有值」，由 SaveCapture 记一笔 unreadable
            Log.d(TAG, "这一格读不出来：${it.javaClass.simpleName}")
        }
    }

    private class Frame(val node: AssistStructure.ViewNode, val depth: Int)
}
