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

import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.edit.EntryForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目表单的内核。
 *
 * 这里盯着三件在界面上验不动、又几乎必然会被后来的改动悄悄破掉的事：
 *
 *  - **密码不许被 trim**。界面上只看得到一串圆点，
 *    首尾少一个空格在屏幕上没有任何表现，用户是在下次登录不上的时候才发现的。
 *  - **保存不许弄丢表单管不着的字段**（收藏、totpSecret、createdAt）。
 *    一次「改个备注」把收藏弄丢，用户多半只会觉得是自己记错了。
 *  - **「放弃修改」弹窗里绝不出现字段值**。决策⑭那条的立论就在这一句上，
 *    而「让用户看清楚放弃的是什么」是一个非常自然的改动方向。
 */
class EntryFormTest {

    private fun entry(
        id: String = "id-1",
        name: String = "招商银行",
        username: String = "",
        password: String = "",
        domains: List<String> = emptyList(),
        category: String = "",
        notes: String = "",
        favorite: Boolean = false,
        totpSecret: String? = null,
        createdAt: Long = 1_000L,
        updatedAt: Long = 2_000L,
        passwordUpdatedAt: Long = 1_500L,
    ) = VaultEntry(
        id = id,
        name = name,
        username = username,
        password = password,
        domains = domains,
        category = category,
        notes = notes,
        favorite = favorite,
        totpSecret = totpSecret,
        createdAt = createdAt,
        updatedAt = updatedAt,
        passwordUpdatedAt = passwordUpdatedAt,
    )

    /* ─────────────────── 草稿与条目的往返 ─────────────────── */

    @Test
    fun `草稿装载再原样存回，条目一个字段都不变`() {
        val e = entry(
            username = "zhangsan@example.com",
            password = "p@ss word",
            domains = listOf("cmbchina.com", "com.cmbchina.ccd.pluto.cmbActivity"),
            category = "银行",
            notes = "开户行：深圳分行",
            favorite = true,
            totpSecret = "JBSWY3DPEHPK3PXP",
        )
        val back = EntryForm.applyTo(e, EntryForm.draftOf(e))
        assertEquals(e, back)
    }

    @Test
    fun `保存不会碰表单管不着的字段`() {
        val e = entry(favorite = true, totpSecret = "SECRET", createdAt = 42L)
        val d = EntryForm.draftOf(e).copy(name = "改了个名")
        val back = EntryForm.applyTo(e, d)

        assertEquals("改了个名", back.name)
        assertTrue(back.favorite)
        assertEquals("SECRET", back.totpSecret)
        assertEquals(42L, back.createdAt)
        // 时间戳由 VaultSession.updateEntry 统一刷新，表单不许自作主张
        assertEquals(e.updatedAt, back.updatedAt)
        assertEquals(e.passwordUpdatedAt, back.passwordUpdatedAt)
    }

    /* ─────────────────── trim 的边界 ─────────────────── */

    @Test
    fun `密码首尾的空格必须原样保留`() {
        val d = EntryForm.Draft(name = "X", password = "  两头都有空格  ")
        assertEquals("  两头都有空格  ", EntryForm.cleaned(d).password)
        assertEquals("  两头都有空格  ", EntryForm.applyTo(entry(), d).password)
    }

    @Test
    fun `名称账号分类的首尾空白会被去掉`() {
        val d = EntryForm.Draft(
            name = "  招商银行 \n",
            username = "\tzhangsan  ",
            category = " 银行 ",
        )
        val c = EntryForm.cleaned(d)
        assertEquals("招商银行", c.name)
        assertEquals("zhangsan", c.username)
        assertEquals("银行", c.category)
    }

    @Test
    fun `备注去掉首尾空白但保留中间的换行`() {
        val d = EntryForm.Draft(name = "X", notes = "\n第一行\n第二行\n\n")
        assertEquals("第一行\n第二行", EntryForm.cleaned(d).notes)
    }

    /* ─────────────────── 网址的切行与去重 ─────────────────── */

    @Test
    fun `网址按换行逗号分号和空白切开`() {
        val got = EntryForm.domainLines("a.com\nb.com, c.com;d.com e.com")
        assertEquals(listOf("a.com", "b.com", "c.com", "d.com", "e.com"), got)
    }

    @Test
    fun `空行和纯空白行会被丢掉`() {
        val got = EntryForm.domainLines("\n\n  a.com  \n\n   \nb.com\n")
        assertEquals(listOf("a.com", "b.com"), got)
    }

    @Test
    fun `留下来的那些一个字符都不改写`() {
        // 归一是 M4 匹配环节的事（决策㉝）。存储环节悄悄改写用户输入，
        // 会让他保存完看到的东西和刚才打的不一样，而屏幕上没人解释是谁改的。
        val got = EntryForm.domainLines("https://mail.example.com/inbox?x=1")
        assertEquals(listOf("https://mail.example.com/inbox?x=1"), got)
    }

    @Test
    fun `指向同一个主机的重复写法只留第一个`() {
        val got = EntryForm.domainLines("example.com\nhttps://example.com/login\nEXAMPLE.COM:443")
        assertEquals(listOf("example.com"), got)
    }

    @Test
    fun `不同子域名不算重复，一个都不合并`() {
        val got = EntryForm.domainLines("example.com\nwww.example.com\nmail.example.com")
        assertEquals(listOf("example.com", "www.example.com", "mail.example.com"), got)
    }

    @Test
    fun `安卓包名原样保留，也不会和网址搞混`() {
        val got = EntryForm.domainLines("com.tencent.mm\nweixin.qq.com")
        assertEquals(listOf("com.tencent.mm", "weixin.qq.com"), got)
    }

    @Test
    fun `切不出主机名的碎片会被丢掉`() {
        assertEquals(emptyList<String>(), EntryForm.domainLines("https://"))
        assertEquals(listOf("a.com"), EntryForm.domainLines("https://\na.com"))
    }

    @Test
    fun `清理是幂等的，存两次结果一样`() {
        val d = EntryForm.Draft(name = "X", domainsText = " a.com , a.com\n\nb.com ")
        val once = EntryForm.cleaned(d)
        assertEquals(once, EntryForm.cleaned(once))
        assertEquals("a.com\nb.com", once.domainsText)
    }

    /* ─────────────────── 能不能存 ─────────────────── */

    @Test
    fun `名称是唯一必填项`() {
        assertTrue(EntryForm.canSave(EntryForm.Draft(name = "只有名称")))
        assertFalse(EntryForm.canSave(EntryForm.Draft(name = "")))
        assertFalse(EntryForm.canSave(EntryForm.Draft(name = "   ")))
    }

    @Test
    fun `密码为空照样能存`() {
        // 确实有人拿它当通讯录用，只记账号不记密码
        val d = EntryForm.Draft(name = "小区门禁", username = "8栋2单元", password = "")
        assertTrue(EntryForm.canSave(d))
        assertEquals("", EntryForm.applyTo(entry(), d).password)
    }

    /* ─────────────────── 改了没有 ─────────────────── */

    @Test
    fun `只多敲了一个尾随空格不算改动`() {
        val a = EntryForm.Draft(name = "招商银行", username = "zhangsan")
        val b = a.copy(username = "zhangsan ")
        assertFalse(EntryForm.isDirty(a, b))
    }

    @Test
    fun `在网址框里多按一个回车不算改动`() {
        val a = EntryForm.Draft(name = "X", domainsText = "a.com")
        val b = a.copy(domainsText = "a.com\n\n")
        assertFalse(EntryForm.isDirty(a, b))
    }

    @Test
    fun `密码末尾多一个空格算改动`() {
        // 和上面几条相反：密码不 trim，所以那个空格是**真的会存进去**的内容，
        // 返回时必须拦一道。
        val a = EntryForm.Draft(name = "X", password = "abc")
        val b = a.copy(password = "abc ")
        assertTrue(EntryForm.isDirty(a, b))
    }

    @Test
    fun `改了内容就算改动`() {
        val a = EntryForm.Draft(name = "X")
        assertTrue(EntryForm.isDirty(a, a.copy(name = "Y")))
        assertTrue(EntryForm.isDirty(a, a.copy(notes = "记一笔")))
        assertTrue(EntryForm.isDirty(a, a.copy(category = "银行")))
    }

    /* ─────────────────── 弹窗里只说字段名 ─────────────────── */

    @Test
    fun `改动摘要按字段列出，顺序固定`() {
        val a = EntryForm.Draft(name = "X", username = "u1", password = "p1")
        val b = a.copy(username = "u2", password = "p2")
        assertEquals(listOf(EntryForm.Field.Username, EntryForm.Field.Password),
            EntryForm.changedFields(a, b))
        assertEquals("账号 · 密码", EntryForm.changedSummary(a, b))
    }

    @Test
    fun `改动摘要里绝不出现任何字段的内容`() {
        val a = EntryForm.Draft(
            name = "招商银行",
            username = "zhangsan@example.com",
            password = "hunter2",
            domainsText = "cmbchina.com",
            category = "银行",
            notes = "身份证号 110101",
        )
        val b = EntryForm.Draft(
            name = "招行",
            username = "lisi@example.com",
            password = "correct-horse",
            domainsText = "cmbchina.com.cn",
            category = "金融",
            notes = "换了一句",
        )
        val summary = EntryForm.changedSummary(a, b)

        val forbidden = listOf(
            "招商银行", "招行", "zhangsan", "lisi", "example.com",
            "hunter2", "correct-horse", "cmbchina", "110101", "换了一句",
        )
        forbidden.forEach { v ->
            assertFalse("摘要里泄露了字段值：$v（$summary）", summary.contains(v))
        }
        // 该说的还是要说清楚：六个字段全改了
        assertEquals("名称 · 账号 · 密码 · 网址 / 应用 · 分类 · 备注", summary)
    }

    @Test
    fun `没有改动时摘要是空字符串`() {
        val a = EntryForm.Draft(name = "X")
        assertEquals("", EntryForm.changedSummary(a, a))
    }

    /* ─────────────────── 新增流复用同一套规则 ─────────────────── */

    @Test
    fun `新建条目走的是同一套清理规则，id 和时间戳留给会话去补`() {
        val d = EntryForm.Draft(
            name = "  新条目 ",
            password = " 带空格的密码 ",
            domainsText = "a.com\na.com\n",
        )
        val e = EntryForm.newEntry(d)
        assertEquals("新条目", e.name)
        assertEquals(" 带空格的密码 ", e.password)
        assertEquals(listOf("a.com"), e.domains)
        assertEquals("", e.id)
        assertEquals(0L, e.createdAt)
        assertEquals(0L, e.updatedAt)
    }

    @Test
    fun `空白名称初值等于没有初值`() {
        assertEquals("", EntryForm.blank("   ").name)
        assertEquals("招商", EntryForm.blank(" 招商 ").name)
    }
}
