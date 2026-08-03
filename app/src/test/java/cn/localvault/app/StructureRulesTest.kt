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

package cn.localvault.app

import cn.localvault.app.ui.autofill.AndroidInput
import cn.localvault.app.ui.autofill.FieldGroups
import cn.localvault.app.ui.autofill.Origin
import cn.localvault.app.ui.autofill.StructureRules
import cn.localvault.app.ui.autofill.StructureRules.Inherited
import cn.localvault.app.ui.autofill.StructureRules.NodeFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「那棵树怎么走成一屏框」。
 *
 * 这一层原本计划是「用 `android.*` 的薄壳，不写单测」。改主意的理由写在
 * `StructureRules` 的文件头上：走树里藏着三条**错了也不报错**的规则——
 * `webDomain` 的继承、继承只能往下不能往旁边、看不见是整棵子树的事。
 * 第二条就是 AutoSpill（决策(158)/(171)）在树上的形态，
 * 而它在真机上要复现得先写一个恶意应用出来。所以全部钉在这儿。
 *
 * 下面用一组假节点搭树，走的是和线上**同一个** `Walker`——
 * 线上和这里的唯一区别是 `Tree` 的实现（`ViewNode` 的一串 getter）。
 */
class StructureRulesTest {

    /* ── 假树 ────────────────────────────────────────────── */

    private class Node(
        val facts: NodeFacts = NodeFacts(),
        val children: List<Node> = emptyList(),
        val tag: String = "",
    )

    private object FakeTree : StructureRules.Tree<Node> {
        override fun childCount(node: Node) = node.children.size
        override fun childAt(node: Node, index: Int) = node.children.getOrNull(index)
        override fun facts(node: Node) = node.facts
    }

    private fun walk(vararg roots: Node): StructureRules.Walker<Node> =
        StructureRules.Walker(FakeTree).apply { roots.forEach { feed(it) } }

    /** 一个能填的文本框。 */
    private fun input(
        hint: String? = null,
        idEntry: String? = null,
        web: String? = null,
        scheme: String? = "https",
        htmlTag: String? = null,
        attrs: List<Pair<String, String>> = emptyList(),
        visible: Boolean = true,
        focused: Boolean = false,
        important: Int = AndroidInput.IMPORTANT_FOR_AUTOFILL_AUTO,
        autofillType: Int = AndroidInput.AUTOFILL_TYPE_TEXT,
        tag: String = "",
    ) = Node(
        facts = NodeFacts(
            autofillable = true,
            autofillHints = if (hint == null) emptyList() else listOf(hint),
            autofillType = autofillType,
            importantForAutofill = important,
            idEntry = idEntry,
            htmlTag = htmlTag,
            htmlAttributes = attrs,
            webScheme = if (web == null) null else scheme,
            webDomain = web,
            visible = visible,
            focused = focused,
        ),
        tag = tag,
    )

    /** 一个装东西的容器（没有 AutofillId）。 */
    private fun box(
        vararg children: Node,
        web: String? = null,
        scheme: String? = "https",
        visible: Boolean = true,
        important: Int = AndroidInput.IMPORTANT_FOR_AUTOFILL_AUTO,
    ) = Node(
        facts = NodeFacts(
            autofillable = false,
            importantForAutofill = important,
            webScheme = if (web == null) null else scheme,
            webDomain = web,
            visible = visible,
        ),
        children = children.toList(),
    )

    /* ══════════════ 一、那句「我属于这个网站」采信不采信 ══════════════ */

    @Test
    fun `没有自称就是没有`() {
        assertNull(StructureRules.webDomainClaim("https", null))
        assertNull(StructureRules.webDomainClaim("https", ""))
        assertNull(StructureRules.webDomainClaim("https", "   "))
    }

    @Test
    fun `http 和 https 都采信，大小写不论`() {
        assertEquals("example.com", StructureRules.webDomainClaim("https", "example.com"))
        assertEquals("example.com", StructureRules.webDomainClaim("http", "example.com"))
        assertEquals("example.com", StructureRules.webDomainClaim("HTTPS", "example.com"))
    }

    @Test
    fun `本地文件和别的协议下的自称一律不采信`() {
        // 应用把一份 HTML 写进自己的目录，里面写上 base href = 银行，
        // 那棵子树就会带着银行的 webDomain 交上来。
        assertNull(StructureRules.webDomainClaim("file", "bank.example.com"))
        assertNull(StructureRules.webDomainClaim("content", "bank.example.com"))
        assertNull(StructureRules.webDomainClaim("javascript", "bank.example.com"))
    }

    @Test
    fun `读不到协议时按采信处理`() {
        // API 28 以下没有 getWebScheme。一律不采信的话，
        // 26 / 27 两个版本上所有网页填充全部失效——那不是保守，是把功能关掉。
        assertEquals("example.com", StructureRules.webDomainClaim(null, "example.com"))
    }

    @Test
    fun `这一层不做归一，原样往下传`() {
        // 归一只有一份，在 FieldGroups 那儿走 VaultIndex.normalizeDomain（决策㉝）。
        assertEquals(
            "https://Example.COM:443/login",
            StructureRules.webDomainClaim("https", " https://Example.COM:443/login "),
        )
    }

    /* ══════════════ 二、HTML 那几个属性 ══════════════ */

    @Test
    fun `只有 input 和 textarea 算输入框`() {
        assertTrue(StructureRules.isInputTag("input"))
        assertTrue(StructureRules.isInputTag("INPUT"))
        assertTrue(StructureRules.isInputTag("textarea"))
        assertFalse(StructureRules.isInputTag("div"))
        assertFalse(StructureRules.isInputTag("select"))
        assertFalse(StructureRules.isInputTag(null))
    }

    @Test
    fun `属性名大小写不敏感`() {
        // 见过 TYPE，也见过 autoComplete。用 Map 直接查是最容易在
        // 某一家浏览器上悄悄失灵的写法。
        val attrs = listOf("TYPE" to "password", "autoComplete" to "current-password")
        assertEquals("password", StructureRules.htmlAttr(attrs, "type"))
        assertEquals("current-password", StructureRules.htmlAttr(attrs, "autocomplete"))
        assertNull(StructureRules.htmlAttr(attrs, "name"))
    }

    @Test
    fun `同名属性取第一个`() {
        val attrs = listOf("name" to "user", "name" to "pass")
        assertEquals("user", StructureRules.htmlAttr(attrs, "name"))
    }

    /* ══════════════ 三、继承（这一节是决策(158) 在树上的形态） ══════════════ */

    @Test
    fun `网站往后代传`() {
        val web = StructureRules.descend(Inherited.ROOT, box(web = "example.com").facts)
        val child = StructureRules.descend(web, input().facts)
        assertEquals("example.com", child.webDomain)
    }

    @Test
    fun `后代自己声明的网站压过祖先的`() {
        // iframe 里嵌着另一个站是正常的，那时底下的框属于里面那个站。
        val outer = StructureRules.descend(Inherited.ROOT, box(web = "outer.com").facts)
        val inner = StructureRules.descend(outer, box(web = "inner.com").facts)
        assertEquals("inner.com", inner.webDomain)
    }

    @Test
    fun `不采信的自称不进继承`() {
        val local = StructureRules.descend(Inherited.ROOT, box(web = "bank.com", scheme = "file").facts)
        assertNull(local.webDomain)
    }

    @Test
    fun `祖先看不见，后代一律看不见`() {
        val hidden = StructureRules.descend(Inherited.ROOT, box(visible = false).facts)
        val child = StructureRules.descend(hidden, input(visible = true).facts)
        assertFalse(child.visible)
    }

    @Test
    fun `祖先说了整块别填，后代翻不回来`() {
        val excluded = StructureRules.descend(
            Inherited.ROOT,
            box(important = AndroidInput.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS).facts,
        )
        val child = StructureRules.descend(
            excluded,
            input(important = AndroidInput.IMPORTANT_FOR_AUTOFILL_YES).facts,
        )
        assertTrue(child.excluded)
    }

    @Test
    fun `只说自己别填的，不牵连后代`() {
        val self = StructureRules.descend(
            Inherited.ROOT,
            box(important = AndroidInput.IMPORTANT_FOR_AUTOFILL_NO).facts,
        )
        assertFalse(self.excluded)
    }

    /* ══════════════ 四、摊成 RawField ══════════════ */

    @Test
    fun `html 三个属性各就各位`() {
        val f = StructureRules.toRawField(
            7L,
            input(
                htmlTag = "input",
                attrs = listOf("type" to "password", "name" to "pwd", "autocomplete" to "current-password"),
            ).facts,
            Inherited.ROOT,
        )
        assertEquals(7L, f.handle)
        assertEquals("password", f.htmlType)
        assertEquals("pwd", f.htmlName)
        assertEquals("current-password", f.htmlAutocomplete)
    }

    @Test
    fun `input type hidden 直接算看不见`() {
        // 藏一个密码框最常见的写法，visibility 那一栏是看不出来的。
        val f = StructureRules.toRawField(
            0L,
            input(htmlTag = "input", attrs = listOf("type" to "HIDDEN"), visible = true).facts,
            Inherited.ROOT,
        )
        assertFalse(f.visible)
    }

    @Test
    fun `被祖先排除的框，写成应用明说别填`() {
        // 不在这儿直接丢掉它：这个结论仍然由 FieldRoles 那一处做出来。
        val f = StructureRules.toRawField(
            0L,
            input().facts,
            Inherited(webDomain = null, visible = true, excluded = true),
        )
        assertEquals(AndroidInput.IMPORTANT_FOR_AUTOFILL_NO, f.importantForAutofill)
    }

    @Test
    fun `webDomain 取继承来的那个`() {
        val f = StructureRules.toRawField(
            0L,
            input().facts,
            Inherited(webDomain = "example.com", visible = true, excluded = false),
        )
        assertEquals("example.com", f.webDomain)
    }

    /* ══════════════ 五、走树 ══════════════ */

    @Test
    fun `先序，句柄从零连续发`() {
        val w = walk(box(input(tag = "a"), box(input(tag = "b"), input(tag = "c")), input(tag = "d")))
        assertEquals(listOf(0L, 1L, 2L, 3L), w.picked.map { it.field.handle })
        assertEquals(listOf("a", "b", "c", "d"), w.picked.map { it.node.tag })
    }

    @Test
    fun `没有 AutofillId 的容器不占句柄`() {
        val w = walk(box(box(box(input(tag = "only")))))
        assertEquals(1, w.picked.size)
        assertEquals(0L, w.picked[0].field.handle)
    }

    @Test
    fun `不是文本框的不收`() {
        val w = walk(
            box(
                input(autofillType = AndroidInput.AUTOFILL_TYPE_TOGGLE),
                input(autofillType = AndroidInput.AUTOFILL_TYPE_DATE),
                input(tag = "text"),
            ),
        )
        assertEquals(listOf("text"), w.picked.map { it.node.tag })
    }

    @Test
    fun `DOM 的壳不收，input 收`() {
        val w = walk(
            box(
                input(htmlTag = "div"),
                input(htmlTag = "a"),
                input(htmlTag = "input", tag = "in"),
                input(htmlTag = "textarea", tag = "ta"),
            ),
        )
        assertEquals(listOf("in", "ta"), w.picked.map { it.node.tag })
    }

    @Test
    fun `多次 feed 句柄接着往下发`() {
        val w = walk(box(input()), box(input(), input()))
        assertEquals(listOf(0L, 1L, 2L), w.picked.map { it.field.handle })
    }

    @Test
    fun `读事实抛异常的节点跳过，其余照收`() {
        val bomb = Node(tag = "bomb")
        val tree = object : StructureRules.Tree<Node> {
            override fun childCount(node: Node) = node.children.size
            override fun childAt(node: Node, index: Int) = node.children.getOrNull(index)
            override fun facts(node: Node): NodeFacts {
                if (node.tag == "bomb") error("自定义 View 的 getter 里抛异常是真见过的")
                return node.facts
            }
        }
        val w = StructureRules.Walker(tree)
        w.feed(box(input(tag = "a"), bomb, input(tag = "b")))
        assertEquals(listOf("a", "b"), w.picked.map { it.node.tag })
    }

    @Test
    fun `孩子读不出来不炸`() {
        val tree = object : StructureRules.Tree<Node> {
            override fun childCount(node: Node) = node.children.size
            override fun childAt(node: Node, index: Int): Node? =
                if (index == 1) throw IllegalStateException("读不到") else node.children.getOrNull(index)
            override fun facts(node: Node) = node.facts
        }
        val w = StructureRules.Walker(tree)
        w.feed(box(input(tag = "a"), input(tag = "boom"), input(tag = "c")))
        assertEquals(listOf("a", "c"), w.picked.map { it.node.tag })
    }

    @Test
    fun `太深的子树整个不看，并且如实记一笔截断`() {
        var deep = input(tag = "bottom")
        repeat(StructureRules.Limits.MAX_DEPTH + 5) { deep = box(deep) }
        val w = walk(deep)
        assertTrue(w.picked.isEmpty())
        assertTrue(w.truncated)
    }

    @Test
    fun `框太多时保留已经收到的，不是整屏扔掉`() {
        val many = (0 until StructureRules.Limits.MAX_FIELDS + 30).map { input() }
        val w = walk(box(*many.toTypedArray()))
        assertEquals(StructureRules.Limits.MAX_FIELDS, w.picked.size)
        assertTrue(w.truncated)
    }

    @Test
    fun `一切正常时不报截断`() {
        val w = walk(box(input(), input()))
        assertFalse(w.truncated)
    }

    /* ══════════════ 六、端到端：走完树接上归属那一层 ══════════════ */

    @Test
    fun `WebView 子树里的框归到它自称的网站`() {
        val w = walk(
            box(
                box(
                    input(hint = "username", htmlTag = "input"),
                    input(hint = "password", htmlTag = "input"),
                    web = "example.com",
                ),
            ),
        )
        val groups = FieldGroups.split(
            StructureRules.contextOf("com.android.chrome", w.picked),
        )
        assertEquals(1, groups.size)
        assertEquals(Origin.Web("example.com", "com.android.chrome"), groups[0].origin)
    }

    @Test
    fun `同屏的原生框绝不继承兄弟 WebView 的网站`() {
        // AutoSpill：恶意应用套一个 WebView 显示银行登录页（那些框如实带着
        // webDomain = 银行），同一屏上它自己的原生框是那个 WebView 的**兄弟**。
        // 继承一旦从「父子」松成「上一个见过的」，这两个原生框就会被算成属于银行，
        // 用户点一下填充条，密码就写进了应用自己读得到的框。
        val w = walk(
            box(
                box(
                    input(hint = "username", htmlTag = "input"),
                    input(hint = "password", htmlTag = "input"),
                    web = "bank.example.com",
                ),
                input(hint = "username", idEntry = "et_user"),
                input(hint = "password", idEntry = "et_pass"),
            ),
        )
        val groups = FieldGroups.split(
            StructureRules.contextOf("com.evil.app", w.picked),
        )
        assertEquals(2, groups.size)
        assertEquals(Origin.Web("bank.example.com", "com.evil.app"), groups[0].origin)
        assertEquals(Origin.App("com.evil.app"), groups[1].origin)
    }

    @Test
    fun `藏在看不见的容器里的密码框，一路走到判定都不算数`() {
        val w = walk(
            box(
                box(input(hint = "password", tag = "trap"), visible = false),
                input(hint = "username", tag = "user"),
            ),
        )
        val groups = FieldGroups.split(StructureRules.contextOf("com.example.app", w.picked))
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].fields.size)
        assertEquals("user", w.picked.first { it.field.handle == groups[0].fields[0].handle }.node.tag)
    }

    @Test
    fun `祖先说了整块别填，底下的框一个都不进组`() {
        val w = walk(
            box(
                input(hint = "username"),
                input(hint = "password"),
                important = AndroidInput.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
            ),
        )
        assertEquals(2, w.picked.size) // 收是收进来了……
        // ……但结论是 FieldRoles 那一处做出来的，而那一处**默认不听**这个旗子
        // （FieldRoles.DEFAULT_RESPECT_OPT_OUT = false）。所以这条要考的
        // 「祖先说了别填 → 底下的框被写成 NO」这一步，得把设置打开才看得见；
        // 用缺省值考它，考的其实是相反的那条规矩。
        val respectful = FieldGroups.split(
            StructureRules.contextOf("com.example.app", w.picked, respectOptOut = true),
        )
        assertTrue(respectful.isEmpty())

        // 顺带钉住这一步真的落到了字段上：两个框的 importantForAutofill 都被改写成 NO，
        // 与「听不听」无关——听不听是下一层的事。
        assertTrue(w.picked.all { it.field.importantForAutofill == AndroidInput.IMPORTANT_FOR_AUTOFILL_NO })
    }

    @Test
    fun `包名一律用传进来的那个`() {
        val w = walk(box(input(hint = "username")))
        val ctx = StructureRules.contextOf("com.example.app", w.picked)
        assertEquals("com.example.app", ctx.activityPackage)
        assertEquals(w.picked.size, ctx.fields.size)
    }

    /* ══════════════ 七、别把内容打进日志 ══════════════ */

    @Test
    fun `新增的这几个 toString 不吐主机名也不吐页面文案`() {
        val facts = NodeFacts(
            autofillable = true,
            webDomain = "bank.example.com",
            hintText = "请输入你的登录密码",
            htmlAttributes = listOf("name" to "pwd"),
        )
        assertFalse(facts.toString().contains("bank"))
        assertFalse(facts.toString().contains("密码"))

        val inherited = Inherited(webDomain = "bank.example.com", visible = true, excluded = false)
        assertFalse(inherited.toString().contains("bank"))

        val w = walk(box(input(web = "bank.example.com", htmlTag = "input")))
        assertFalse(w.picked[0].toString().contains("bank"))
    }
}
