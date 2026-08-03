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

import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultData
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.autofill.AutofillOffer
import cn.localvault.app.ui.autofill.AutofillRow
import cn.localvault.app.ui.autofill.FillContext
import cn.localvault.app.ui.autofill.FillPlan
import cn.localvault.app.ui.autofill.HostTrust
import cn.localvault.app.ui.autofill.RawField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 填充条那三行字，在交给系统进程之前要先过的那道洗。
 *
 * 这一层看起来只是「setText 之前 trim 一下」，但它是**用户内容第一次被画进
 * 别人的应用上面**的地方：那三行字是用户自己打的（或者从一份 CSV 里导进来的，
 * 而决策(156) 明说导入预览一格内容都不显示——于是它到这一刻为止从没被人看过一眼）。
 */
class AutofillRowTest {

    /* ═════════════ clean：压成一行 ═════════════ */

    @Test
    fun `普通文本原样通过`() {
        assertEquals("示例站", AutofillRow.clean("示例站", 40))
    }

    @Test
    fun `换行折成空格 —— 一行字被撑成半屏高会把下面的候选顶出屏幕`() {
        assertEquals("公司 内网", AutofillRow.clean("公司\n内网", 40))
    }

    @Test
    fun `回车与制表也折成空格`() {
        assertEquals("a b c", AutofillRow.clean("a\r\nb\tc", 40))
    }

    @Test
    fun `连续空白折成一个`() {
        assertEquals("a b", AutofillRow.clean("a   \n\t  b", 40))
    }

    @Test
    fun `首尾空白去掉`() {
        assertEquals("示例站", AutofillRow.clean("  \n示例站\t ", 40))
    }

    @Test
    fun `不间断空格与全角空格也算空白`() {
        assertEquals("a b", AutofillRow.clean("a\u00A0\u3000b", 40))
    }

    /* ═════════════ clean：剔控制字符 ═════════════ */

    @Test
    fun `控制字符直接消失而不是变成空格`() {
        // 变成空格的话，"ab" 会长出一个中间的空格来，看着像用户自己打的
        assertEquals("ab", AutofillRow.clean("a\u0000\u0007b", 40))
    }

    @Test
    fun `双向控制符被剔掉 —— 否则 bank 可以被伪装成别的域名`() {
        val spoof = "moc.knab\u202E"
        val cleaned = AutofillRow.clean(spoof, 40)
        assertFalse("RTL override 不许进填充条", cleaned.contains('\u202E'))
        assertEquals("moc.knab", cleaned)
    }

    @Test
    fun `孤立方向隔离符也剔掉`() {
        listOf('\u2066', '\u2067', '\u2068', '\u2069', '\u200E', '\u200F').forEach {
            assertFalse("$it 不许留下", AutofillRow.clean("a${it}b", 40).contains(it))
        }
    }

    @Test
    fun `零宽字符剔掉 —— 否则两条看起来一模一样的候选其实是两条`() {
        assertEquals("邮箱", AutofillRow.clean("邮\u200B箱\uFEFF", 40))
    }

    /* ═════════════ clean：截断 ═════════════ */

    @Test
    fun `超长截断并带省略号且总长不超过上限`() {
        val long = "字".repeat(200)
        val out = AutofillRow.clean(long, 40)
        assertEquals(40, out.length)
        assertTrue(out.endsWith(AutofillRow.ELLIPSIS))
    }

    @Test
    fun `刚好等于上限不截断`() {
        val exact = "字".repeat(40)
        assertEquals(exact, AutofillRow.clean(exact, 40))
    }

    @Test
    fun `超出一个字才开始截`() {
        val out = AutofillRow.clean("字".repeat(41), 40)
        assertTrue(out.endsWith(AutofillRow.ELLIPSIS))
        assertEquals(40, out.length)
    }

    @Test
    fun `代理对不会被截成半个`() {
        // 每个 emoji 两个 Char、一个码点。截到第 9 个码点时不许留下半个
        val emoji = "🔐".repeat(20)
        val out = AutofillRow.clean(emoji, 10)
        assertEquals(10, out.codePointCount(0, out.length))
        assertEquals("🔐".repeat(9) + AutofillRow.ELLIPSIS, out)
        // 切在代理对中间的话，最后一个 Char 会是一个孤立的高位代理
        assertFalse(out[out.length - 2].isHighSurrogate())
    }

    @Test
    fun `截断处末尾的空格一并去掉`() {
        // "abc " + "…" 是难看的，也让人以为省略的是一个空格
        val out = AutofillRow.clean("abc defghij", 5)
        assertEquals("abc" + AutofillRow.ELLIPSIS, out)
    }

    @Test
    fun `空串与纯空白洗完还是空串`() {
        assertEquals("", AutofillRow.clean("", 40))
        assertEquals("", AutofillRow.clean("  \n\t \u200B ", 40))
    }

    /* ═════════════ forItem ═════════════ */

    @Test
    fun `候选行的三段各自洗过`() {
        val row = AutofillRow.forItem(item(name = "示例\n站", username = "  zhang\u202Esan "))
        assertEquals("示例 站", row.title)
        assertEquals("zhangsan", row.subtitle)
    }

    @Test
    fun `没有 badge 时是 null 而不是空串 —— 空的那一行照样占行距`() {
        assertNull(AutofillRow.forItem(item()).badge)
    }

    @Test
    fun `兄弟域那一行照样出现并且洗过`() {
        // mail.example.com 存着，页面是 example.com → SameSite
        val row = AutofillRow.forItem(item(domains = listOf("mail.example.com")))
        assertTrue("兄弟域必须写出你存的是哪个域名", row.badge!!.contains("mail.example.com"))
    }

    @Test
    fun `名称洗完为空时退回占位而不是空白一行`() {
        // 零宽字符**不是**空白（`isBlank()` 认不出它），于是 AutofillOffer 那一层
        // 仍然认为这条有名称、不会退回账号；洗完却什么都不剩。这一层兜住
        val row = AutofillRow.forItem(item(name = "\u200B\u200B", username = "zhangsan"))
        assertEquals(AutofillOffer.NO_NAME, row.title)
    }

    @Test
    fun `没有账号的条目显示一句话而不是空白`() {
        val row = AutofillRow.forItem(item(username = ""))
        assertEquals(AutofillOffer.NO_USERNAME, row.subtitle)
    }

    @Test
    fun `密码不出现在任何一段里`() {
        val row = AutofillRow.forItem(item(password = "s3cr3t-p@ss"))
        listOf(row.title, row.subtitle, row.badge.orEmpty(), row.toString()).forEach {
            assertFalse("填充条上出现了密码：$it", it.contains("s3cr3t"))
        }
    }

    /* ═════════════ 另外两种行 ═════════════ */

    @Test
    fun `先解锁那一条两行都不为空且互不重样`() {
        val row = AutofillRow.forUnlock()
        assertTrue(row.title.isNotEmpty())
        assertTrue(row.subtitle.isNotEmpty())
        assertNotEquals(row.title, row.subtitle)
        assertNull(row.badge)
    }

    @Test
    fun `先解锁那一条不说库里有什么`() {
        val row = AutofillRow.forUnlock()
        // 「连几条都数不出来」这句话本身是允许的（它说的正是数不出来），
        // 不许出现的是任何一句**报数**或**报内容**的话
        val all = row.title + row.subtitle
        listOf("已保存", "匹配到", "找到").forEach {
            assertFalse("锁着的时候不许说库里有什么：$it", all.contains(it))
        }
        assertFalse("锁着的时候一个数字都报不出来", all.any { c -> c.isDigit() })
    }

    @Test
    fun `搜索那一行没截掉时不提条数`() {
        assertFalse(AutofillRow.forSearch(0).title.any { it.isDigit() })
    }

    @Test
    fun `搜索那一行截掉了就报条数`() {
        assertTrue(AutofillRow.forSearch(3).title.contains("3"))
    }

    @Test
    fun `搜索那一行的小字要说清点下去不会当场填好`() {
        assertTrue(AutofillRow.forSearch(0).subtitle.isNotEmpty())
    }

    /* ═════════════ toString ═════════════ */

    @Test
    fun `Row 的 toString 不吐内容`() {
        val row = AutofillRow.forItem(item(name = "招商银行", username = "zhangsan"))
        val s = row.toString()
        assertFalse(s.contains("招商银行"))
        assertFalse(s.contains("zhangsan"))
    }

    /* ═════════════ 造一条候选 ═════════════ */

    private val trust: HostTrust = object : HostTrust {
        override fun isTrustedBrowser(packageName: String) = packageName == CHROME
    }

    /**
     * 走真的那条路造出 [AutofillOffer.Item]：`Item` 的构造器是 `internal`，
     * 而更要紧的是——用例里手搓一个 `Item` 就绕开了 `AutofillOffer` 那一层的规则，
     * 于是「这一行到底会不会出现」这件事在这里就成了假设。
     */
    /* ═════════════ 内联那一格（M4-4b） ═════════════ */

    @Test
    fun `内联那一格走同一道洗`() {
        val chip = AutofillRow.chipForItem(item(name = "示例\n站", username = "  zhang\u202Esan "))
        assertEquals("示例 站", chip.title)
        assertEquals("zhangsan", chip.subtitle)
    }

    @Test
    fun `内联那一格截得比浮层那一行短 —— 旁边还并排站着别的候选`() {
        val long = "一".repeat(60)
        val chip = AutofillRow.chipForItem(item(name = long))
        val row = AutofillRow.forItem(item(name = long))
        assertTrue("内联那一格必须更短", chip.title.length < row.title.length)
        assertTrue(chip.title.endsWith(AutofillRow.ELLIPSIS))
    }

    @Test
    fun `内联那一格上洗完为空时同样退回占位`() {
        val chip = AutofillRow.chipForItem(item(name = "\u200B\u200B", username = ""))
        assertEquals(AutofillOffer.NO_NAME, chip.title)
        assertEquals(AutofillOffer.NO_USERNAME, chip.subtitle)
    }

    @Test
    fun `内联那一格上同样不会出现密码`() {
        val chip = AutofillRow.chipForItem(item(password = "s3cr3t-p@ss"))
        listOf(chip.title, chip.subtitle, chip.toString()).forEach {
            assertFalse("内联条上出现了密码：$it", it.contains("s3cr3t"))
        }
    }

    @Test
    fun `先解锁那一格两行和浮层那一条说的是同一句话`() {
        val chip = AutofillRow.chipForUnlock()
        val row = AutofillRow.forUnlock()
        assertEquals(row.title, chip.title)
        assertEquals(row.subtitle, chip.subtitle)
    }

    @Test
    fun `搜索那一格会说出还有几条`() {
        assertTrue(AutofillRow.chipForSearch(3).title.contains("3"))
        assertEquals(AutofillOffer.searchLabel(0), AutofillRow.chipForSearch(0).title)
    }

    /* ═════════════ 造数据 ═════════════ */

    private fun item(
        name: String = "示例站",
        username: String = "zhangsan",
        password: String = "s3cr3t-p@ss",
        domains: List<String> = listOf("example.com"),
    ): AutofillOffer.Item {
        val entry = VaultEntry(
            id = "id-1",
            name = name,
            username = username,
            password = password,
            domains = domains,
        )
        val plan = FillPlan.forRequest(
            FillContext(
                activityPackage = CHROME,
                fields = listOf(
                    RawField(handle = 0, autofillHints = listOf("username"), webDomain = "example.com"),
                    RawField(handle = 1, autofillHints = listOf("password"), webDomain = "example.com"),
                ),
            ),
        )
        val response = AutofillOffer.respond(
            state = VaultSession.State.Unlocked(VaultData(entries = listOf(entry))),
            plan = plan,
            trust = trust,
            selfPackage = "cn.localvault.app",
        )
        val offer = response as AutofillOffer.Offer
        return offer.items.single()
    }

    private companion object {
        const val CHROME = "com.android.chrome"
    }
}
