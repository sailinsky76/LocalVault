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

import cn.localvault.app.ui.importer.CsvMapping
import cn.localvault.app.ui.importer.CsvMapping.Dialect
import cn.localvault.app.ui.importer.CsvMapping.Note
import cn.localvault.app.ui.importer.CsvMapping.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列名映射内核。
 *
 * 这一堆用例存在的理由只有一句话：**猜错哪一列是密码，不会报错。**
 * 把 `Password Hint`（密码提示）当成密码导进去之后，
 * 用户的保险库里会有一条长得完全正常、实际上打不开任何东西的记录，
 * 而真密码已经跟着源文件一起被删了。所以每一家的真实表头都要钉一遍，
 * 排除表更要一条一条钉。
 */
class CsvMappingTest {

    private fun plan(vararg header: String) = CsvMapping.plan(header.toList())

    private fun roles(vararg header: String): List<Role?> = plan(*header).assign

    /* ───────────── 各家的真实表头 ───────────── */

    @Test fun `Chrome 导出`() {
        val p = plan("name", "url", "username", "password", "note")
        assertEquals(Dialect.Chrome, p.dialect)
        assertEquals(
            listOf(Role.Name, Role.Url, Role.Username, Role.Password, Role.Notes),
            p.assign,
        )
        assertTrue(p.ready())
    }

    @Test fun `Bitwarden 导出`() {
        val p = plan(
            "folder", "favorite", "type", "name", "notes", "fields",
            "reprompt", "login_uri", "login_username", "login_password", "login_totp",
        )
        assertEquals(Dialect.Bitwarden, p.dialect)
        assertEquals(Role.Category, p.roleOf(0))
        assertEquals(Role.Favorite, p.roleOf(1))
        assertEquals(Role.Kind, p.roleOf(2))
        assertEquals(Role.Name, p.roleOf(3))
        assertEquals(Role.Notes, p.roleOf(4))
        assertEquals(Role.Url, p.roleOf(7))
        assertEquals(Role.Username, p.roleOf(8))
        assertEquals(Role.Password, p.roleOf(9))
        assertEquals(Role.Totp, p.roleOf(10))
    }

    @Test fun `Bitwarden 的 fields 和 reprompt 不参与映射`() {
        val p = plan("name", "fields", "reprompt", "login_password")
        assertNull(p.roleOf(1))
        assertNull(p.roleOf(2))
    }

    @Test fun `1Password 导出`() {
        val p = plan("Title", "Url", "Username", "Password", "OTPAuth", "Favorite", "Archived", "Tags", "Notes")
        assertEquals(Dialect.OnePassword, p.dialect)
        assertEquals(Role.Name, p.roleOf(0))
        assertEquals(Role.Totp, p.roleOf(4))
        assertEquals(Role.Category, p.roleOf(7))
        assertNull("Archived 不是任何角色", p.roleOf(6))
    }

    @Test fun `LastPass 导出`() {
        val p = plan("url", "username", "password", "totp", "extra", "name", "grouping", "fav")
        assertEquals(Dialect.LastPass, p.dialect)
        assertEquals(Role.Notes, p.roleOf(4))
        assertEquals(Role.Name, p.roleOf(5))
        assertEquals(Role.Category, p.roleOf(6))
        assertEquals(Role.Favorite, p.roleOf(7))
    }

    @Test fun `KeePass 导出`() {
        val p = plan("Group", "Title", "Username", "Password", "URL", "Notes")
        assertEquals(Dialect.KeePass, p.dialect)
        assertEquals(Role.Category, p.roleOf(0))
        assertTrue(p.ready())
    }

    @Test fun `Firefox 导出没有名称列，用网址兜底`() {
        val p = plan(
            "url", "username", "password", "httpRealm", "formActionOrigin",
            "guid", "timeCreated", "timeLastUsed", "timePasswordChanged",
        )
        assertEquals(Dialect.Firefox, p.dialect)
        assertNull(p.columnOf(Role.Name))
        assertEquals(0, p.columnOf(Role.Url))
        assertTrue("有网址就不该拦着", p.ready())
        assertTrue(Note.NameFromUrl in p.notes)
    }

    @Test fun `中文列名`() {
        val p = plan("名称", "网址", "账号", "密码", "备注", "分类")
        assertEquals(Dialect.Chinese, p.dialect)
        assertEquals(
            listOf(Role.Name, Role.Url, Role.Username, Role.Password, Role.Notes, Role.Category),
            p.assign,
        )
    }

    @Test fun `帐号那个异体字也要认`() {
        assertEquals(Role.Username, roles("标题", "帐号", "密码")[1])
    }

    /* ───────────── 归一 ───────────── */

    @Test fun `三种写法归到同一个键`() {
        assertEquals("loginuri", CsvMapping.normalizeName("Login URI"))
        assertEquals("loginuri", CsvMapping.normalizeName("login_uri"))
        assertEquals("loginuri", CsvMapping.normalizeName("login-uri"))
    }

    @Test fun `三种写法映射结果一样`() {
        val a = roles("Login URI", "login_password")
        val b = roles("login-uri", "Login Password")
        assertEquals(a, b)
        assertEquals(Role.Url, a[0])
    }

    @Test fun `列名两边的空白和大小写都不影响`() {
        assertEquals(Role.Password, roles("  PASSWORD  ", "x")[0])
    }

    @Test fun `带括号的中文列名靠宽松匹配兜住`() {
        assertEquals(Role.Username, roles("用户名（登录）", "密码")[0])
    }

    /* ───────────── 排除表（这一节是这个文件的核心） ───────────── */

    @Test fun `密码提示绝不能当成密码`() {
        val p = plan("Title", "Password Hint", "Username")
        assertNull(p.columnOf(Role.Password))
        assertNull(p.roleOf(1))
        assertFalse("没有密码列就该拦着", p.ready())
    }

    @Test fun `中文的密码提示同样挡住`() {
        assertNull(plan("名称", "密码提示").columnOf(Role.Password))
    }

    @Test fun `密码修改时间不是密码`() {
        assertNull(plan("url", "timePasswordChanged").columnOf(Role.Password))
    }

    @Test fun `确认密码不是密码`() {
        assertNull(plan("名称", "确认密码").columnOf(Role.Password))
    }

    @Test fun `密码强度不是密码`() {
        assertNull(plan("name", "Password Strength").columnOf(Role.Password))
    }

    @Test fun `密码历史不是密码`() {
        assertNull(plan("name", "password history").columnOf(Role.Password))
    }

    @Test fun `真正的密码列在旁边有提示列时照样认得出来`() {
        val p = plan("Title", "Password", "Password Hint")
        assertEquals(1, p.columnOf(Role.Password))
        assertNull(p.roleOf(2))
    }

    @Test fun `排除表只作用于宽松匹配，精确的列名不受影响`() {
        // 「密码」是精确命中，不走宽松匹配，所以哪怕排除表里有「码」字样也无所谓
        assertEquals(Role.Password, roles("密码")[0])
    }

    /* ───────────── 重复与冲突 ───────────── */

    @Test fun `两列都叫密码时只用靠前那一列`() {
        val p = plan("name", "password", "pwd")
        assertEquals(1, p.columnOf(Role.Password))
        assertNull(p.roleOf(2))
        assertTrue(Note.DuplicateRole in p.notes)
    }

    @Test fun `没有重复时不报重复`() {
        assertFalse(Note.DuplicateRole in plan("name", "url", "username", "password").notes)
    }

    @Test fun `一个角色最多占一列`() {
        val p = plan("username", "user", "login", "password")
        assertEquals(1, p.assign.count { it == Role.Username })
    }

    /* ───────────── 手工改（M5-2b 那一页每点一下走的就是这里） ───────────── */

    @Test fun `改一列的角色`() {
        val p = plan("a", "b", "password").withRole(0, Role.Name)
        assertEquals(Role.Name, p.roleOf(0))
        assertTrue(p.ready())
    }

    @Test fun `把角色挪到别的列时，原来那列自动让位`() {
        val p0 = plan("name", "url", "username", "password")
        val p1 = p0.withRole(1, Role.Password)
        assertEquals(Role.Password, p1.roleOf(1))
        assertNull("原来的密码列必须被清掉", p1.roleOf(3))
        assertEquals(1, p1.assign.count { it == Role.Password })
    }

    @Test fun `改成不导入`() {
        val p = plan("name", "url", "username", "password").withRole(3, null)
        assertNull(p.roleOf(3))
        assertFalse(p.ready())
    }

    @Test fun `原方案不会被改动`() {
        val p0 = plan("name", "url", "username", "password")
        p0.withRole(3, null)
        assertEquals(Role.Password, p0.roleOf(3))
    }

    @Test fun `改成同一个角色时返回自己`() {
        val p0 = plan("name", "password")
        assertTrue(p0 === p0.withRole(1, Role.Password))
    }

    @Test fun `越界的列号不抛异常`() {
        val p0 = plan("name", "password")
        assertTrue(p0 === p0.withRole(9, Role.Url))
        assertTrue(p0 === p0.withRole(-1, Role.Url))
    }

    @Test fun `改动之后记账要重算`() {
        val p0 = plan("name", "password", "pwd")
        assertTrue(Note.DuplicateRole in p0.notes)
        val p1 = p0.withRole(2, Role.Notes)
        assertFalse("重复已经被用户解决了，就别再说了", Note.DuplicateRole in p1.notes)
    }

    @Test fun `指定了名称列之后不再说用网址当名字`() {
        val p0 = plan("url", "username", "password")
        assertTrue(Note.NameFromUrl in p0.notes)
        val p1 = p0.withRole(1, Role.Name)
        assertFalse(Note.NameFromUrl in p1.notes)
    }

    @Test fun `全部清空之后什么都不剩`() {
        val p = plan("name", "url", "username", "password").cleared()
        assertTrue(p.assign.all { it == null })
        assertFalse(p.ready())
        assertEquals(listOf(0, 1, 2, 3), p.unmapped())
    }

    /* ───────────── 拦截 ───────────── */

    @Test fun `没有密码列就拦着`() {
        val b = plan("name", "url", "username").blockers()
        assertEquals(1, b.size)
        assertTrue(b[0].contains("密码"))
    }

    @Test fun `名称和网址都没有就拦着`() {
        val b = plan("username", "password").blockers()
        assertEquals(1, b.size)
        assertTrue(b[0].contains("名称"))
    }

    @Test fun `两样都缺时两条都说`() {
        assertEquals(2, plan("aaa", "bbb").blockers().size)
    }

    @Test fun `拦截文案不出现稍后重试和联系客服`() {
        val all = plan("aaa", "bbb").blockers().joinToString()
        assertFalse(all.contains("稍后"))
        assertFalse(all.contains("客服"))
        assertFalse(all.contains("重试"))
    }

    /* ───────────── 第一行是不是表头 ───────────── */

    @Test fun `没有表头的文件认得出来`() {
        val p = plan("https://mail.example.com", "zhang@example.com", "s3cr3t-pass")
        assertTrue(p.headerIsData)
        assertEquals(Dialect.Unknown, p.dialect)
        assertTrue("认不出来的时候一列都不许猜", p.assign.all { it == null })
        assertTrue(Note.HeaderLooksLikeData in p.notes)
    }

    @Test fun `真表头不会被当成数据`() {
        assertFalse(plan("name", "url", "username", "password").headerIsData)
        assertFalse(plan("名称", "网址", "账号", "密码").headerIsData)
    }

    @Test fun `认出了列名就不再怀疑第一行是数据`() {
        // 有些导出的表头里带着 URL 样子的列名，只要有一列认得出来就不该翻脸
        assertFalse(plan("name", "password", "https://x.com").headerIsData)
    }

    @Test fun `手工指定之后 headerIsData 这个事实不变`() {
        val p = plan("https://a.com", "u@b.com", "pw12345678").withRole(2, Role.Password)
        assertTrue(p.headerIsData)
        assertTrue(Note.HeaderLooksLikeData in p.notes)
    }

    @Test fun `很长的一格算数据`() {
        assertTrue(CsvMapping.looksLikeData(listOf("a".repeat(41))))
    }

    @Test fun `一串数字算数据`() {
        assertTrue(CsvMapping.looksLikeData(listOf("13800138000")))
    }

    @Test fun `短数字不算`() {
        assertFalse(CsvMapping.looksLikeData(listOf("id", "v2")))
    }

    @Test fun `空格不算`() {
        assertFalse(CsvMapping.looksLikeData(listOf("", "  ")))
    }

    /* ───────────── 文案与敏感性 ───────────── */

    @Test fun `记账文案按声明顺序输出，与识别顺序无关`() {
        val p = plan("url", "username", "password", "otpauth", "junk")
        val texts = p.noteTexts()
        assertEquals(Note.entries.filter { it in p.notes }.map { it.text }, texts)
    }

    @Test fun `五条记账文案互不重样`() {
        assertEquals(Note.entries.size, Note.entries.map { it.text }.toSet().size)
    }

    @Test fun `角色说明互不重样`() {
        assertEquals(Role.entries.size, Role.entries.map { it.label }.toSet().size)
        assertEquals(Role.entries.size, Role.entries.map { it.hint }.toSet().size)
    }

    @Test fun `toString 不吐表头内容`() {
        // 没有表头的文件里，那一行是真实数据——密码可能就在里面
        val p = plan("https://a.com", "u@b.com", "s3cr3t-pass")
        val s = p.toString()
        assertFalse(s.contains("s3cr3t"))
        assertFalse(s.contains("u@b.com"))
        assertFalse(s.contains("a.com"))
        assertTrue(s.contains("3"))
    }

    @Test fun `summary 不吐表头内容`() {
        val s = CsvMapping.summary(plan("https://a.com", "u@b.com", "s3cr3t-pass"))
        assertFalse(s.contains("s3cr3t"))
        assertFalse(s.contains("u@b.com"))
    }

    @Test fun `认出格式时的那句话和认不出时不一样`() {
        val known = CsvMapping.summary(plan("name", "url", "username", "password"))
        val unknown = CsvMapping.summary(plan("aaa", "bbb", "password", "name"))
        assertNotEquals(known, unknown)
        assertTrue(known.contains("Chrome"))
    }

    /* ───────────── 边角 ───────────── */

    @Test fun `空表头不崩`() {
        val p = CsvMapping.plan(emptyList())
        assertEquals(0, p.width)
        assertFalse(p.ready())
    }

    @Test fun `全是空列名`() {
        val p = plan("", "", "")
        assertTrue(p.assign.all { it == null })
        assertFalse(p.headerIsData)
    }

    @Test fun `unmapped 报的是列号不是内容`() {
        assertEquals(listOf(1), plan("password", "zzz", "name").unmapped())
    }

    @Test fun `columnOf 对没有出现的角色返回 null`() {
        assertNull(plan("name", "password").columnOf(Role.Totp))
    }
}
