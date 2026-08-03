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
import cn.localvault.app.BuildConfig

/**
 * 排查用的转储。**只在 debug 包里说话。**
 *
 * ── 它为什么必须存在，又为什么必须是这个样子 ──
 *
 * 决策(144) 定下的那条规矩没有错，而且不打算改：正式日志里只有数字和枚举名，
 * 包名、主机名、页面文案一个都不打——「这台手机的主人在什么应用里登录了哪个站」
 * 是一份不该躺在 logcat 里的清单，而 logcat 是同一台设备上任何一个
 * 拿到 `READ_LOGS` 的东西都读得到的地方。
 *
 * 代价是排查的时候什么都看不见。`不出手：NoFillableField` 只说了
 * 「一个能认的框都没有」，说不出那几个框到底带着什么、卡在四道排除的哪一道上，
 * 于是只能靠猜——而关键词表这种东西，猜着补一轮就是错一轮。
 *
 * 所以这里划一条明确的线：**这份转储什么都打，但只在 debug 包里编译得进去。**
 *
 *   · [ON] 就是 `BuildConfig.DEBUG`，release 包里它是编译期常量 `false`，
 *     R8（`isMinifyEnabled = true`）会把每个 `if (!ON) return` 之后的整段
 *     连同里面的字符串一起删掉。也就是说 release 的 apk 里
 *     **不存在**这些格式串，不是「存在但不执行」；
 *   · 每一个入口第一行就是那个判断，没有第二条路进来；
 *   · 这个文件里没有一处会读到用户**打进框里的字**——`getText()` /
 *     `getAutofillValue()` 在这儿和在 `AssistShell` 里一样一次都没出现（决策(165)）。
 *     它打的是「这个框长什么样」（id、placeholder、type），不是「里面有什么」。
 *
 * ── 用它的人要知道的一件事 ──
 *
 * 打开 debug 包排查的那段时间里，logcat 里确实会出现你正在登录的站点和页面文案。
 * 这是**故意的**，也是它唯一的用处。抓完日志、贴给别人之前自己扫一眼，
 * 别把一份带着主机名的日志随手发出去。
 */
internal object AutofillDebug {

    const val TAG = "AutofillDbg"

    /**
     * 不用 `const`（`BuildConfig.DEBUG` 是 Java 那侧的 static final，Kotlin 拿它当不了
     * 编译期常量），但它仍然是一个静态终值，R8 照样折得动。
     */
    @JvmField
    val ON: Boolean = BuildConfig.DEBUG

    /* ══════════════════════ 一、这次请求是谁发来的、收到了什么框 ══════════════════════ */

    /**
     * 走完结构之后打一次。
     *
     * **第一行那个包名是这份转储里最要紧的东西。** 正式日志里没有它，
     * 于是「这一次请求到底来自浏览器还是来自本应用自己」这个问题在 release 上
     * 根本问不出来——而 `OwnUi` 和 `NoFillableField` 这两个结论，
     * 一个只可能因为包名不对，一个只可能因为框不对，分不清包名就分不清方向。
     *
     * 每个框打两行：一行是我们**读到**的事实，一行是网页节点上那张属性表的**全部内容**。
     * 后者是这份转储真正的产出：`RawField` 现在只取了 `type` / `name` / `autocomplete`
     * 三个属性，而页面上写着的往往是 `placeholder` 或 `aria-label`——
     * 那些字段进不了 `RawField`，也就永远不会出现在正式日志里，
     * 只能在这儿从活的 `ViewNode` 上直接读。
     */
    fun structure(
        activityPackage: String,
        picked: List<StructureRules.Picked<AssistStructure.ViewNode>>,
    ) {
        if (!ON) return
        Log.d(TAG, "── 请求来自：$activityPackage ── 收到 ${picked.size} 个框")
        picked.forEachIndexed { index, p ->
            runCatching { dumpOne(index, p) }
                .onFailure { Log.d(TAG, "  #$index 读不出来：${it.javaClass.simpleName}") }
        }
    }

    private fun dumpOne(index: Int, p: StructureRules.Picked<AssistStructure.ViewNode>) {
        val f = p.field
        val node = p.node

        val flags = buildList {
            if (!f.visible) add("看不见")
            if (AndroidInput.optedOut(f.importantForAutofill)) add("声明别填")
            if (f.focused) add("光标在这儿")
        }

        val flagText = if (flags.isEmpty()) "" else " ⟨${flags.joinToString("、")}⟩"

        Log.d(
            TAG,
            "  #$index h=${f.handle} tag=${tagOf(node)} " +
                "type=${clip(f.htmlType)} name=${clip(f.htmlName)} " +
                "ac=${clip(f.htmlAutocomplete)} hints=${f.autofillHints.joinToString(",")} " +
                "inputType=0x${Integer.toHexString(f.inputType)} " +
                "afType=${f.autofillType} ifa=${f.importantForAutofill} " +
                "id=${clip(f.idEntry)} hint=${clip(f.hintText)} desc=${clip(f.contentDescription)} " +
                "web=${clip(f.webDomain)}$flagText",
        )

        // 网页节点那张属性表的全部内容。原生框这里是空的，不打。
        val attrs = runCatching { node.htmlInfo?.attributes }.getOrNull().orEmpty()
        if (attrs.isNotEmpty()) {
            val text = attrs.mapNotNull { a ->
                val name = a?.first ?: return@mapNotNull null
                "$name=${clip(a.second)}"
            }.joinToString(" ")
            Log.d(TAG, "     html: $text")
        }
    }

    /** `RawField` 里没存标签名（判定用不着），排查时要，从节点上直接读。 */
    private fun tagOf(node: AssistStructure.ViewNode): String =
        runCatching { node.htmlInfo?.tag }.getOrNull() ?: "原生"

    /* ══════════════════════ 二、这些框各自被判成了什么 ══════════════════════ */

    /**
     * 判定结果。**这一步是四道排除和四档证据的唯一出口**——
     * 一个框判成 `Other`，可能是被声明挡掉的、被负面表挡掉的，
     * 也可能是四个文本槽全空、压根没得猜，而这三种的修法完全不同。
     * [FieldRoles.Source] 把它们分开：`None` 就是「没得猜」。
     *
     * 顺带把分组和 [FillPlan] 的结论也打出来，因为 `OwnUi` / `UntrustedHost`
     * 这类结论是在这一层之后、按 `Origin` 做出来的，而正式日志只说结论不说依据。
     */
    fun verdicts(context: FillContext) {
        if (!ON) return
        // 必须把 context 上那个策略带上：用默认值重算一遍的话，
        // 日志里写的判定和这一次真正走的判定可能不是同一个结论。
        val guesses = FieldRoles.classifyAll(context.fields, context.respectOptOut)
        context.fields.forEachIndexed { i, f ->
            val g = guesses[i]
            // optedOut 单独缀一句：被「应用明说别填」拦掉，和四个文本槽全空、
            // 压根没得猜，原来都长成 `Other（依据 None）`，而这两种的修法完全不同。
            val opt = if (!g.optedOut) {
                ""
            } else if (g.role == FieldRoles.Role.Other && g.source == FieldRoles.Source.None) {
                " ⟨应用声明别填，已拦下⟩"
            } else {
                " ⟨应用声明别填，已放行⟩"
            }
            Log.d(TAG, "  判定 h=${f.handle} → ${g.role.name}（依据 ${g.source.name}）$opt")
        }

        val groups = runCatching { FieldGroups.split(context) }.getOrNull().orEmpty()
        Log.d(TAG, "  分组：${groups.size} 组")
        groups.forEachIndexed { i, g ->
            Log.d(TAG, "    组$i origin=${originText(g.origin)} 框=${g.fields.size}")
        }

        val plan = runCatching { FillPlan.forRequest(context) }.getOrNull()
        val primary = plan?.primary
        Log.d(
            TAG,
            "  主表单：" + if (primary == null) {
                "没有"
            } else {
                "${primary.kind.name} origin=${originText(primary.origin)} " +
                    "可写 ${primary.targets.size} 格"
            },
        )
    }

    /**
     * `Origin` 的 `toString` 是刻意脱敏的（决策(158) 那两段），这儿要的正是它藏起来的东西。
     * 只在这个文件里展开，别处照旧。
     */
    private fun originText(origin: Origin): String = when (origin) {
        is Origin.App -> "App(${origin.hostApp})"
        is Origin.Web -> "Web(${origin.host} @ ${origin.hostApp})"
    }

    /* ══════════════════════ 小工具 ══════════════════════ */

    /**
     * 换行会把一行日志拆成好几条，`null` 和空串要分得开（一个是没这个属性，
     * 一个是有但为空——`name=""` 和没有 `name` 在排查时是两回事）。
     */
    private fun clip(s: CharSequence?): String {
        if (s == null) return "∅"
        val one = s.toString().replace('\n', ' ').replace('\r', ' ').trim()
        if (one.isEmpty()) return "«空»"
        return if (one.length <= MAX) one else one.take(MAX) + "…"
    }

    private const val MAX = 48

    /* ══════════════════════ 三、系统那侧的一句话 ══════════════════════ */

    /**
     * 一次请求从头到尾没有任何日志，说明系统根本没来问我们，那时候上面两个转储
     * 一行都不会打——而「没打」和「打了但没框」在 logcat 里长得一模一样。
     * 所以连接和断开各说一句，中间夹着的东西才有边界。
     *
     * 正式日志里已经有「已连接 / 已断开」了（`AutofillSvc`），这里补的是
     * **一次请求的开头**：同一个会话里系统可能问好几次（换了个框、页面变了），
     * 而那几次在 logcat 上是连着的一片，不划开的话很容易把上一次的框
     * 读成这一次的。
     */
    fun requestStart(which: Int) {
        if (!ON) return
        Log.d(TAG, "════════ 第 $which 次填充请求 ════════")
    }

    /**
     * 保存请求的抬头。**这一行补的是一个真实的读日志障碍**（决策(232)）：
     *
     * 填充请求有 [requestStart] 那条抬头，保存请求原来什么都没有——它在 logcat 上
     * 只剩一行光秃秃的 `── 请求来自：…`（那是 [structure] 打的，两条路共用）。
     * 于是一份抓下来的日志里，「这是又一次填充」和「这是用户刚按下了保存框」
     * 长得几乎一模一样，唯一的区别是**后面有没有跟着 `收值：Tally(...)`**——
     * 而那一行要往下读好几行才看得到，中间还夹着几行框转储。
     *
     * 这个差别不是美观问题：整条保存链的故障（框没弹、弹了没反应、存进去是空的）
     * 全靠这份日志分档，而分档的第一步就是先认出「这一次到底是哪一种请求」。
     *
     * 不编号，同 [requestStart] 那边的理由反过来：一次会话里填充可能问好几次，
     * 保存至多一次。
     */
    fun saveRequestStart() {
        if (!ON) return
        Log.d(TAG, "════════ 保存请求 ════════")
    }
}
