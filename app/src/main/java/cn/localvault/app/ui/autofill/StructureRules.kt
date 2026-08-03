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

/**
 * 「那棵树怎么走成一份 [FillContext]」——**规则这一半**。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 另一半（`AssistShell`）负责把 `AssistStructure.ViewNode`
 * 上那十几个 getter 读成 [NodeFacts]，除此之外一个判断都不做。
 *
 * ── 为什么连「走树」都要挪到这一侧来 ──
 *
 * 按原计划这一步是「一层用 `android.*` 的薄壳，不写单测」（同 `SafExportSink` /
 * `SafImportSource`）。那两个薄壳当得起「薄」字：一个 `openInputStream` 读到底，
 * 一个 `write` 写下去，读错写错当场就看得见。
 *
 * 走树这件事不是。它里面藏着三条**会悄悄改变填充结果**的规则：
 *
 *   1. **`webDomain` 沿着树往下继承。** 浏览器只在 WebView 那一层（有时是页面根节点）
 *      写上自称的网站，底下每个 `<input>` 上是空的。不继承，网页框全都变成
 *      「说不出自己属于哪个网站」的原生框（`FieldGroups.originKey` 返回 null），
 *      于是一律拿承载它的浏览器包名去匹配——用户的所有网站密码从此一条都填不出来。
 *   2. **继承只能往下，不能往旁边。** 这一条正是决策(158)/(171) 在树上的形态：
 *      恶意应用套一个 WebView，那棵子树上的框如实带着 `webDomain = 你的网银`，
 *      而同一屏上它**自己的原生框是 WebView 的兄弟，不是它的后代**。
 *      只要继承严格顺着父子边走，那些原生框就永远拿不到那个 `webDomain`。
 *      写成「记住上一个见过的 `webDomain`」（一个循环里的可变变量，比传参顺手得多）
 *      就漏了——那正是 AutoSpill 走的门。
 *   3. **看不见 / 明说别填，是整棵子树的事。** 一个 `visibility != VISIBLE` 的容器，
 *      它底下的框在屏幕上一个都看不见，可各自的 `visibility` 全是 `VISIBLE`。
 *      隐藏密码框那个老套路（决策见 `FieldRoles`）正是这么摆的。
 *
 * 三条都是「错了也不报错，只是从此填错人或者不填」的规则，
 * 而它们**没有一条需要 `android.*`**。所以这一步把走树本身也搬到了纯 Kotlin 这一侧：
 * [Tree] 抽掉「节点长什么样」，[Walker] 负责走，薄壳只剩一个 [Tree] 的实现。
 * 于是上面三条能被 `StructureRulesTest` 用假节点钉住，
 * 而不是靠在真机上装几十个 App 碰运气。
 *
 * ── 这一层允许出现的判断，只有一种 ──
 *
 * **下游必然会做出同样结论的那些**（见 [NodeFacts.worthPicking]）。
 * 它们改不了任何一次填充的结果，只是不让 [Limits] 那几个上限被垃圾节点吃掉。
 * 除此之外，「这个框是账号还是密码」「这几个框算不算一个表单」一个字都不在这儿判——
 * 那是 [FieldRoles] 和 [FieldGroups] 的事，它们已经有 58 个用例钉着了。
 */
object StructureRules {

    /* ══════════════════════════ 节点长什么样 ══════════════════════════ */

    /**
     * 一个节点上，我们**需要**知道的全部事实。
     *
     * 它比 [RawField] 多两样（[htmlTag] / [webScheme]），少一样（句柄）：
     * 前两样是走树时要用、进不了模型的中间事实，句柄则要等这个节点被收进清单
     * 才发得下来（[Walker]）。
     *
     * **这里同样没有「这个框现在写着什么」**（决策(165)）。
     * `ViewNode.getText()` / `getAutofillValue()` 在薄壳里连读都不会读一次——
     * 不是「记得别读」，是这个类里没有那个字段，读了也无处可放。
     */
    class NodeFacts(
        /** 这个节点有没有 `AutofillId`。没有的填不了，只能当过路的容器。 */
        val autofillable: Boolean = false,
        val autofillHints: List<String> = emptyList(),
        val inputType: Int = 0,
        val autofillType: Int = AndroidInput.AUTOFILL_TYPE_NONE,
        val importantForAutofill: Int = AndroidInput.IMPORTANT_FOR_AUTOFILL_AUTO,
        val idEntry: String? = null,
        val hintText: String? = null,
        val contentDescription: String? = null,
        /** `<input>` / `<div>` / `<form>`……原生节点这里是 null。 */
        val htmlTag: String? = null,
        /** HTML 属性表，原样抄过来（名字大小写不保证）。 */
        val htmlAttributes: List<Pair<String, String>> = emptyList(),
        /** `http` / `https` / 别的什么。API 28 以下读不到，那时是 null。 */
        val webScheme: String? = null,
        /** 这个节点自称属于哪个网站。**一句自称**（决策(158)）。 */
        val webDomain: String? = null,
        /** 这个节点自己可见（不管祖先）。 */
        val visible: Boolean = true,
        val focused: Boolean = false,
    ) {
        /**
         * 值不值得收进清单。
         *
         * 两道，**每一道下游都会做出同样的结论**，所以它们改不了任何结果：
         *   · 没有 `AutofillId` 的填不进去，[Walker] 连句柄都发不出来；
         *   · 不是文本框的（`AUTOFILL_TYPE_TEXT` 之外的开关 / 列表 / 日期），
         *     `FieldRoles` 的第三道硬性排除会原样再判一次。
         *
         * 还有一道半的：HTML 节点里只收 `<input>` 和 `<textarea>`。
         * 严格说这一道下游判不出来（`FieldRoles` 不看标签名），但它拦掉的是
         * `<div>` `<span>` `<a>` 这些**本来就没有 `AutofillId`** 的节点，
         * 所以实际效果和第一道重合。留着它是因为一个中等复杂的网页有几千个节点，
         * 而 [Limits.MAX_FIELDS] 只有 100——不先把 DOM 的壳剥掉，
         * 上限会被一堆填不进去的东西吃光。
         */
        val worthPicking: Boolean
            get() = autofillable &&
                autofillType == AndroidInput.AUTOFILL_TYPE_TEXT &&
                (htmlTag == null || isInputTag(htmlTag))

        /**
         * 不是 `data class`，`toString` 手写只报形状——同决策(144)。
         * 这里面有 [webDomain]（用户上过哪些站）、有 [hintText] 和
         * [htmlAttributes]（页面文案，够拼出他在哪一页）。
         */
        override fun toString(): String = "NodeFacts(${if (autofillable) "id" else "no-id"})"
    }

    /**
     * 「这棵树怎么走」。节点是什么类型由使用方决定：
     * 线上是 `AssistStructure.ViewNode`，用例里是几个假节点。
     *
     * 做成接口而不是直接收 `ViewNode`，理由和 `HostTrust` / `UnlockGuard` /
     * `VaultRemnants` 一样：要用 `android.*` 的那一小块留在外面，判断留在里面。
     */
    interface Tree<N> {
        fun childCount(node: N): Int

        /** 第 i 个孩子。读不到就返回 null（某些实现会在这儿抛，见 [Walker.feed]）。 */
        fun childAt(node: N, index: Int): N?

        fun facts(node: N): NodeFacts
    }

    /** 被收进清单的一个框：给下游的 [RawField] + 它是从哪个节点来的。 */
    class Picked<N> internal constructor(val field: RawField, val node: N) {
        override fun toString(): String = "Picked(#${field.handle})"
    }

    /* ══════════════════════════ 上限 ══════════════════════════ */

    /**
     * 三个上限。它们防的不是恶意，是**一屏正常网页**。
     *
     * 系统给自动填充服务的时间是有限的（`onFillRequest` 拖久了，
     * 用户看到的是「填充条没出来」，而不是任何错误）。一个电商首页几千个节点，
     * 层层套着 `div`；再赶上一个自己写的无限嵌套布局，递归走法直接 `StackOverflowError`——
     * 那个 Error 会顺着 `catch (t: Throwable)` 变成一次静悄悄的「这次不填了」，
     * 而下一次同样的页面还是一样。
     *
     * 超限的处理是**停下来，保留已经收到的**，不是抛异常：
     * 走到第 100 个框还没找到账号密码的页面，多半也不会在第 101 个上有；
     * 而把已经收到的 99 个一起扔掉，代价是整屏都不填。
     */
    object Limits {
        /** 最多收多少个框。 */
        const val MAX_FIELDS = 100

        /** 最多走多少个节点。 */
        const val MAX_NODES = 6000

        /** 最深走多少层。超过这一层的子树整个不看。 */
        const val MAX_DEPTH = 96
    }

    /* ══════════════════════════ 沿树往下带的东西 ══════════════════════════ */

    /**
     * 从祖先那儿继承下来的三件事。
     *
     * **它是不可变的，而且只作为参数往下传**——不是一个循环外面的可变变量。
     * 这句话就是这个类存在的全部理由，见文件头第 2 条：
     * 可变变量会让 WebView 的 `webDomain` 漏给它的**兄弟**，
     * 而那些兄弟正是恶意应用自己的原生输入框。
     */
    class Inherited(
        /** 最近一个声明了网站的祖先说的那个网站；一路上都没有就是 null。 */
        val webDomain: String?,
        /** 一路上每个祖先都可见。 */
        val visible: Boolean,
        /** 某个祖先说了「我和我的后代都别填」。 */
        val excluded: Boolean,
    ) {
        override fun toString(): String =
            "Inherited(${if (webDomain == null) "app" else "web"}, vis=$visible, ex=$excluded)"

        companion object {
            /** 树根上的初值：没有网站、可见、没被排除。 */
            val ROOT = Inherited(webDomain = null, visible = true, excluded = false)
        }
    }

    /**
     * 走到这个节点之后，传给它的孩子们的是什么。
     *
     * 三条各自的方向都是**只收紧、不放松**：
     *   · 网站：自己声明了就覆盖（iframe 里嵌着另一个站是正常的，
     *     那时候底下的框属于里面那个站，不属于外面那个）；没声明就沿用祖先的。
     *     **注意这里不做归一**，原样往下传——归一是 `FieldGroups.originKey` 的事，
     *     全工程只有 `VaultIndex.normalizeDomain` 一份（决策㉝）。
     *   · 可见：祖先不可见，后代一律算不可见。反过来不行——
     *     一个可见的容器里当然可以摆一个隐藏的框，那正是要挡的东西。
     *   · 排除：祖先说了「连后代一起别填」，后代一律算被排除。
     *     后代自己写 `importantForAutofill=yes` **翻不回来**：
     *     那个属性说的是「这个框自己重不重要」，而祖先那句
     *     `NO_EXCLUDE_DESCENDANTS` 说的是「这整块别碰」，
     *     谁更外面谁说了算。
     */
    fun descend(parent: Inherited, facts: NodeFacts): Inherited {
        val claim = webDomainClaim(facts.webScheme, facts.webDomain)
        return Inherited(
            webDomain = claim ?: parent.webDomain,
            visible = parent.visible && facts.visible,
            excluded = parent.excluded ||
                facts.importantForAutofill == AndroidInput.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
        )
    }

    /**
     * 一个节点的「我属于这个网站」这句自称，采信还是不采信。
     *
     * 两条：
     *   · 空的、只有空白的，当作没说（同 `FieldGroups.originKey`：
     *     一个说不出自己属于哪个网站的框，没有任何自称可供采信）；
     *   · **协议只认 `http` / `https`。** `file://` 和 `content://` 下的页面
     *     也会带着 `webDomain`，而那是本地文件——一个应用把一份 HTML 写进自己的目录、
     *     里面填上 `<base href="https://银行">`，就能让那棵子树自称是银行。
     *     Android 28 以下读不到协议（`getWebScheme` 是 API 28 加的），
     *     那时候 [NodeFacts.webScheme] 是 null，**按采信处理**：
     *     一律不采信的话，26 / 27 两个版本上所有网页填充全部失效，
     *     那不是保守，那是把功能关掉。这两个版本上兜底的仍是
     *     `DomainMatch` 那一层的浏览器判定（`UntrustedHost`）。
     */
    fun webDomainClaim(scheme: String?, domain: String?): String? {
        val d = domain?.trim().orEmpty()
        if (d.isEmpty()) return null
        if (scheme != null && !scheme.equals("http", true) && !scheme.equals("https", true)) {
            return null
        }
        return d
    }

    /* ══════════════════════════ HTML 那几个属性 ══════════════════════════ */

    /** 网页上真正能输入的标签。`<select>` 不收：那是列表，不是文本框。 */
    fun isInputTag(tag: String?): Boolean {
        val t = tag?.trim()?.lowercase() ?: return false
        return t == "input" || t == "textarea"
    }

    /**
     * 从属性表里取一个属性，**大小写不敏感，取第一个**。
     *
     * 大小写：HTML 属性名本来就不区分大小写，而各家浏览器交上来的样子并不统一
     * （见过 `TYPE` 和 `autoComplete`）。用 `Map` 直接查是最顺手的写法，
     * 也是最容易在某一家浏览器上悄悄失灵的写法——表现是那一家浏览器上
     * 「密码框认不出来」，而在你手边这台机器上一切正常。
     *
     * 取第一个：同名属性重复出现是非法 HTML，浏览器自己也取第一个。
     */
    fun htmlAttr(attributes: List<Pair<String, String>>, name: String): String? =
        attributes.firstOrNull { it.first.trim().equals(name, ignoreCase = true) }?.second

    /* ══════════════════════════ 组装 ══════════════════════════ */

    /**
     * 把一个节点的事实 + 继承下来的东西，摊成一个 [RawField]。
     *
     * 三处「继承压过自己」的落笔都在这儿：
     *   · `webDomain` 取继承来的那个（自己声明的在 [descend] 里已经并进去了）；
     *   · `visible` 要祖先和自己都可见，**而且 `<input type=hidden>` 直接算不可见**——
     *     那是网页上藏一个密码框最常见的写法，`visibility` 一栏是看不出来的；
     *   · 被祖先排除时，`importantForAutofill` 一律改写成 `NO`。
     *     不在这儿直接丢掉它，是为了让「应用明说别填」这个结论
     *     仍然由 `FieldRoles` 那一处做出来——同一条规矩有两个地方能做出结论时，
     *     迟早有一天两处会不一样，而那种不一样查起来要命。
     */
    fun toRawField(handle: Long, facts: NodeFacts, inherited: Inherited): RawField {
        val htmlType = htmlAttr(facts.htmlAttributes, "type")
        return RawField(
            handle = handle,
            autofillHints = facts.autofillHints,
            inputType = facts.inputType,
            autofillType = facts.autofillType,
            importantForAutofill = if (inherited.excluded) {
                AndroidInput.IMPORTANT_FOR_AUTOFILL_NO
            } else {
                facts.importantForAutofill
            },
            idEntry = facts.idEntry,
            hintText = facts.hintText,
            contentDescription = facts.contentDescription,
            htmlType = htmlType,
            htmlName = htmlAttr(facts.htmlAttributes, "name"),
            htmlAutocomplete = htmlAttr(facts.htmlAttributes, "autocomplete"),
            webDomain = inherited.webDomain,
            visible = inherited.visible &&
                facts.visible &&
                !htmlType.equals("hidden", ignoreCase = true),
            focused = facts.focused,
        )
    }

    /* ══════════════════════════ 走 ══════════════════════════ */

    /**
     * 走一棵（或几棵）树，收出一份按出现顺序排好的框清单。
     *
     * **先序**：一个节点先于它的孩子，孩子按原顺序。原生布局和 DOM 的先序
     * 大致就是视觉上的从上到下——`FillPlan` 里「第一个账号框」「第一个密码框」
     * 这两个说法要靠这个顺序才有意义（决策(172) 那一刀也是）。
     *
     * **不递归**：显式一个栈。理由见 [Limits]，深度上限是拦住病态布局的，
     * 而 `StackOverflowError` 是拦不住的——它在你想接住之前就已经发生了。
     *
     * 一次请求里可能有好几个窗口（`AssistStructure` 那几个 `WindowNode`），
     * 所以 [feed] 可以调多次，句柄在整个 [Walker] 里连续发，三个上限也是整体算的。
     */
    class Walker<N>(private val tree: Tree<N>) {

        private val collected = ArrayList<Picked<N>>()
        private var handles = 0L
        private var nodes = 0

        /** 收到的框，按出现顺序。 */
        val picked: List<Picked<N>> get() = collected

        /** 上限撞到过没有。[AssistShell] 用它记一行日志——只记撞没撞到，不记是哪一屏。 */
        var truncated: Boolean = false
            private set

        fun feed(root: N) {
            val stack = ArrayList<Frame<N>>()
            stack += Frame(root, Inherited.ROOT, 0)
            while (stack.isNotEmpty()) {
                if (collected.size >= Limits.MAX_FIELDS || nodes >= Limits.MAX_NODES) {
                    truncated = true
                    return
                }
                val frame = stack.removeAt(stack.size - 1)
                nodes++

                // 读事实这一步是薄壳给的，最外面那圈 App 什么都干得出来（自定义 View
                // 的 getter 里抛异常是真见过的）。一个节点读不出来就跳过它这一支，
                // 不该让整屏都不填。
                val facts = runCatching { tree.facts(frame.node) }.getOrNull() ?: continue
                val inherited = descend(frame.inherited, facts)

                if (facts.worthPicking) {
                    collected += Picked(toRawField(handles++, facts, inherited), frame.node)
                }

                if (frame.depth >= Limits.MAX_DEPTH) {
                    truncated = true
                    continue
                }
                val count = runCatching { tree.childCount(frame.node) }.getOrNull() ?: 0
                // 倒着压栈，弹出来才是原顺序。
                for (i in count - 1 downTo 0) {
                    val child = runCatching { tree.childAt(frame.node, i) }.getOrNull() ?: continue
                    stack += Frame(child, inherited, frame.depth + 1)
                }
            }
        }

        private class Frame<N>(val node: N, val inherited: Inherited, val depth: Int)
    }

    /**
     * 走完之后交给下游的那份 [FillContext]。
     *
     * [activityPackage] 只能由调用方从系统那儿拿（`AssistStructure.getActivityComponent()`），
     * **不许从节点上的 `idPackage` 取**——那一栏是应用自己填的，
     * 而整条链上就靠这个包名当硬事实（决策(158)）。
     */
    fun <N> contextOf(
        activityPackage: String,
        picked: List<Picked<N>>,
        respectOptOut: Boolean = FieldRoles.DEFAULT_RESPECT_OPT_OUT,
    ): FillContext =
        FillContext(activityPackage, picked.map { it.field }, respectOptOut)
}
