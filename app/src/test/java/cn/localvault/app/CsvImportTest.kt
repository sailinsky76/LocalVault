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
import cn.localvault.app.ui.importer.CsvImport
import cn.localvault.app.ui.importer.CsvImport.Flag
import cn.localvault.app.ui.importer.CsvImport.Match
import cn.localvault.app.ui.importer.CsvImport.Policy
import cn.localvault.app.ui.importer.CsvImport.Skip
import cn.localvault.app.ui.importer.CsvMapping
import cn.localvault.app.ui.importer.CsvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行 → 条目、判重与三种处置。
 *
 * 这一堆用例盯着两种**静默**的失败：判重算宽了，用户点「覆盖」会把一条
 * 好好的旧密码盖掉（没有备份、没有回收站、屏幕上什么都不报）；
 * 算窄了，库里出现两条「微信」，改密码时改了不常用的那条。
 * 还有第三种：覆盖时把空格子当成「请清空」，一次点击清掉用户自己补的分类和备注。
 */
class CsvImportTest {

    /* ───────────── 脚手架 ───────────── */

    /** 直接从一段 CSV 文本走完整条链路：文本 → 表 → 映射 → 候选。 */
    private fun candidates(csv: String, existing: List<VaultEntry> = emptyList()): List<CsvImport.Candidate> {
        val parsed = CsvParser.parse(csv)
        assertTrue("期望解析成功，实际是 $parsed", parsed is CsvParser.Parsed.Ok)
        val table = (parsed as CsvParser.Parsed.Ok).table
        val plan = CsvMapping.plan(table.header)
        return CsvImport.prepare(table, plan, existing)
    }

    private fun one(csv: String): CsvImport.Candidate = candidates(csv).single()

    private fun entry(
        id: String = "x",
        name: String = "",
        username: String = "",
        password: String = "",
        domains: List<String> = emptyList(),
        category: String = "",
        notes: String = "",
        favorite: Boolean = false,
        totp: String? = null,
    ) = VaultEntry(
        id = id, name = name, username = username, password = password,
        domains = domains, category = category, notes = notes,
        favorite = favorite, totpSecret = totp,
    )

    private val HEAD = "name,url,username,password,notes,folder\n"

    /* ───────────── 一行变成什么 ───────────── */

    @Test fun `一条正常的行`() {
        val c = one(HEAD + "微信,https://weixin.qq.com,ax12,p@ss,备注,社交\n")
        assertTrue(c.willImport)
        assertEquals("微信", c.entry.name)
        assertEquals("ax12", c.entry.username)
        assertEquals("p@ss", c.entry.password)
        assertEquals(listOf("https://weixin.qq.com"), c.entry.domains)
        assertEquals("备注", c.entry.notes)
        assertEquals("社交", c.entry.category)
        assertTrue(c.flags.isEmpty())
    }

    @Test fun `id 和时间戳一律留空，交给会话层补`() {
        val c = one(HEAD + "a,,u,p,,\n")
        assertEquals("", c.entry.id)
        assertEquals(0L, c.entry.createdAt)
        assertEquals(0L, c.entry.updatedAt)
        assertEquals(0L, c.entry.passwordUpdatedAt)
    }

    @Test fun `行号跟着走`() {
        val cs = candidates(HEAD + "a,,u,p,,\nb,,u2,p2,,\n")
        assertEquals(listOf(2, 3), cs.map { it.line })
    }

    @Test fun `密码不 trim，其余字段 trim`() {
        val c = one(HEAD + "\"  网易  \",,\"  ax  \",\"  pw  \",,\n")
        assertEquals("网易", c.entry.name)
        assertEquals("ax", c.entry.username)
        assertEquals("  pw  ", c.entry.password)
    }

    @Test fun `密码里的逗号一路活到条目上`() {
        val c = one(HEAD + "a,,u,\"p,w;q\",,\n")
        assertEquals("p,w;q", c.entry.password)
    }

    @Test fun `网址原样保留，不被归一改写`() {
        val c = one(HEAD + "a,https://mail.example.com/inbox,u,p,,\n")
        assertEquals(listOf("https://mail.example.com/inbox"), c.entry.domains)
    }

    @Test fun `多个网址全留下并去重`() {
        val c = one(HEAD + "a,\"example.com https://example.com/login b.com\",u,p,,\n")
        assertEquals(listOf("example.com", "b.com"), c.entry.domains)
        assertTrue(Flag.MultipleUrls in c.flags)
    }

    @Test fun `没有名称时用网址当名字`() {
        val c = one(HEAD + ",https://mail.example.com/inbox,u,p,,\n")
        assertEquals("mail.example.com", c.entry.name)
        assertTrue(Flag.NameFromUrl in c.flags)
    }

    @Test fun `有名称时不去动它`() {
        val c = one(HEAD + "网易邮箱,https://mail.163.com,u,p,,\n")
        assertEquals("网易邮箱", c.entry.name)
        assertFalse(Flag.NameFromUrl in c.flags)
    }

    /* ───────────── 没有密码的行怎么办 ───────────── */

    @Test fun `只有账号没有密码照样导入`() {
        val c = one(HEAD + "论坛,,ax12,,,\n")
        assertTrue("丢掉它用户发现不了", c.willImport)
        assertEquals("", c.entry.password)
        assertTrue(Flag.NoPassword in c.flags)
    }

    @Test fun `只有密码没有账号照样导入`() {
        val c = one(HEAD + "路由器,,,admin123,,\n")
        assertTrue(c.willImport)
        assertTrue(Flag.NoUsername in c.flags)
    }

    @Test fun `账号和密码都空就跳过`() {
        val c = one(HEAD + "社交类,,,,,\n")
        assertEquals(Skip.NothingToStore, c.skip)
    }

    @Test fun `整行空跳过`() {
        val c = one(HEAD + ",,,,,\n")
        assertEquals(Skip.Blank, c.skip)
    }

    @Test fun `名称和网址都空就跳过`() {
        val c = one(HEAD + ",,ax12,p,备注,\n")
        assertEquals(Skip.Nameless, c.skip)
    }

    @Test fun `跳过的行不带任何字段内容`() {
        val c = one(HEAD + ",,ax12,secret-pw,备注,\n")
        assertEquals("", c.entry.password)
        assertEquals("", c.entry.username)
    }

    /* ───────────── 安全笔记那种行 ───────────── */

    @Test fun `Bitwarden 的安全笔记行跳过`() {
        val csv = "folder,favorite,type,name,notes,login_username,login_password\n" +
            ",,note,一段笔记,内容,,\n" +
            ",,login,微信,,ax,pw\n"
        val cs = candidates(csv)
        assertEquals(Skip.NotLogin, cs[0].skip)
        assertTrue(cs[1].willImport)
    }

    @Test fun `银行卡和身份信息也跳过`() {
        val csv = "type,name,username,password\ncard,某行卡,,\nidentity,某人,,\n"
        assertTrue(candidates(csv).all { it.skip == Skip.NotLogin })
    }

    @Test fun `类型列是不认识的值时不跳过`() {
        val csv = "type,name,username,password\nsomethingelse,微信,ax,pw\n"
        assertTrue("拿不准的一律导", candidates(csv).single().willImport)
    }

    /* ───────────── 常用与动态验证码 ───────────── */

    @Test fun `常用列认得出几种真值`() {
        val csv = "name,username,password,favorite\na,u,p,1\nb,u2,p2,true\nc,u3,p3,是\nd,u4,p4,0\ne,u5,p5,\n"
        val cs = candidates(csv)
        assertEquals(listOf(true, true, true, false, false), cs.map { it.entry.favorite })
    }

    @Test fun `动态验证码密钥原样存，不解析`() {
        val csv = "title,username,password,otpauth\na,u,p,otpauth://totp/x?secret=ABC123\n"
        val c = candidates(csv).single()
        assertEquals("otpauth://totp/x?secret=ABC123", c.entry.totpSecret)
        assertTrue(Flag.TotpKept in c.flags)
    }

    @Test fun `没有验证码列时 totpSecret 是 null 而不是空串`() {
        assertNull(one(HEAD + "a,,u,p,,\n").entry.totpSecret)
    }

    /* ───────────── 源文件自己的重复 ───────────── */

    @Test fun `文件里同名同账号的第二条被记账但照样导入`() {
        val cs = candidates(HEAD + "微信,,ax,p1,,\n微信,,ax,p2,,\n")
        assertTrue(cs.all { it.willImport })
        assertFalse(Flag.DuplicateInFile in cs[0].flags)
        assertTrue(Flag.DuplicateInFile in cs[1].flags)
    }

    @Test fun `文件里两条都没账号不算重复`() {
        val cs = candidates(HEAD + "路由器,,,p1,,\n路由器,,,p2,,\n")
        assertTrue("都为空算撞会让无账号的行互相撞成一片", cs.none { Flag.DuplicateInFile in it.flags })
    }

    @Test fun `大小写不同的同名同账号算重复`() {
        val cs = candidates(HEAD + "Gmail,,AX@x.com,p1,,\ngmail,,ax@x.com,p2,,\n")
        assertTrue(Flag.DuplicateInFile in cs[1].flags)
    }

    /* ───────────── 和库里比 ───────────── */

    @Test fun `空库不会撞`() {
        assertNull(one(HEAD + "微信,,ax,p,,\n").hit)
    }

    @Test fun `同名同账号是最强的一档`() {
        val old = entry(id = "old1", name = "微信", username = "ax")
        val c = candidates(HEAD + "微信,,ax,p,,\n", listOf(old)).single()
        assertEquals(Match.NameAndUser, c.hit?.match)
        assertEquals("old1", c.hit?.existingId)
    }

    @Test fun `同网站同账号是第二档`() {
        val old = entry(id = "old1", name = "企鹅", username = "ax", domains = listOf("https://qq.com/login"))
        val c = candidates(HEAD + "QQ,qq.com,ax,p,,\n", listOf(old)).single()
        assertEquals(Match.SiteAndUser, c.hit?.match)
    }

    @Test fun `只有同名是最弱的一档`() {
        val old = entry(id = "old1", name = "微信")
        val c = candidates(HEAD + "微信,,ax,p,,\n", listOf(old)).single()
        assertEquals(Match.NameOnly, c.hit?.match)
    }

    @Test fun `账号都为空不算同账号`() {
        val old = entry(id = "old1", name = "甲", domains = listOf("a.com"))
        val c = candidates(HEAD + "乙,a.com,,p,,\n", listOf(old)).single()
        assertNull("否则库里所有无账号条目会互相撞成一片", c.hit)
    }

    @Test fun `名字不同账号不同网站不同就是不撞`() {
        val old = entry(id = "old1", name = "甲", username = "u1", domains = listOf("a.com"))
        assertNull(candidates(HEAD + "乙,b.com,u2,p,,\n", listOf(old)).single().hit)
    }

    @Test fun `撞上时取最强的那一档，与库里的先后无关`() {
        val weak = entry(id = "weak", name = "微信")
        val strong = entry(id = "strong", name = "微信", username = "ax")
        val c = candidates(HEAD + "微信,,ax,p,,\n", listOf(weak, strong)).single()
        assertEquals(Match.NameAndUser, c.hit?.match)
        assertEquals("strong", c.hit?.existingId)
    }

    @Test fun `同强度时取库里靠前的那一条`() {
        val a = entry(id = "a", name = "微信", username = "ax")
        val b = entry(id = "b", name = "微信", username = "ax")
        assertEquals("a", candidates(HEAD + "微信,,ax,p,,\n", listOf(a, b)).single().hit?.existingId)
    }

    @Test fun `网址的写法不同但主机相同也算同网站`() {
        val old = entry(id = "old1", name = "x", username = "ax", domains = listOf("example.com"))
        val c = candidates(HEAD + "y,https://example.com/login?a=1,ax,p,,\n", listOf(old)).single()
        assertEquals(Match.SiteAndUser, c.hit?.match)
    }

    @Test fun `被跳过的行不参与判重`() {
        val old = entry(id = "old1", name = "笔记")
        val csv = "type,name,username,password\nnote,笔记,,\n"
        assertNull(candidates(csv, listOf(old)).single().hit)
    }

    /* ───────────── 三种处置 ───────────── */

    private fun outcome(csv: String, existing: List<VaultEntry>, p: Policy) =
        CsvImport.apply(candidates(csv, existing), existing, p)

    private val OLD = entry(
        id = "old1", name = "微信", username = "ax", password = "oldpw",
        domains = listOf("weixin.qq.com"), category = "社交", notes = "旧备注", favorite = true,
    )

    @Test fun `跳过时库里一个字都不动`() {
        val o = outcome(HEAD + "微信,,ax,newpw,,\n", listOf(OLD), Policy.Skip)
        assertTrue(o.add.isEmpty())
        assertTrue(o.replace.isEmpty())
        assertEquals(1, o.skippedByPolicy)
    }

    @Test fun `都留着时两条并存且名字不改`() {
        val o = outcome(HEAD + "微信,,ax,newpw,,\n", listOf(OLD), Policy.KeepBoth)
        assertEquals(1, o.add.size)
        assertEquals("微信", o.add[0].name)
        assertTrue(o.replace.isEmpty())
    }

    @Test fun `覆盖时带着旧 id`() {
        val o = outcome(HEAD + "微信,,ax,newpw,,\n", listOf(OLD), Policy.Overwrite)
        assertEquals(1, o.replace.size)
        assertEquals("old1", o.replace[0].id)
        assertEquals("newpw", o.replace[0].password)
    }

    @Test fun `没撞上的行三种处置都照样新增`() {
        for (p in Policy.entries) {
            val o = outcome(HEAD + "新站,,zz,p,,\n", listOf(OLD), p)
            assertEquals("处置 $p", 1, o.add.size)
        }
    }

    @Test fun `处置的默认值是跳过`() {
        assertEquals(Policy.Skip, Policy.entries.first())
    }

    /* ───────────── 覆盖时空的不覆盖（这一节是这个文件的核心） ───────────── */

    @Test fun `源文件里的空格子不会清掉已有的分类`() {
        val o = outcome(HEAD + "微信,,ax,newpw,,\n", listOf(OLD), Policy.Overwrite)
        assertEquals("社交", o.replace[0].category)
    }

    @Test fun `源文件里的空格子不会清掉已有的备注`() {
        val o = outcome(HEAD + "微信,,ax,newpw,,\n", listOf(OLD), Policy.Overwrite)
        assertEquals("旧备注", o.replace[0].notes)
    }

    @Test fun `源文件里的空密码不会清掉已有的密码`() {
        val o = outcome(HEAD + "微信,,ax,,,\n", listOf(OLD), Policy.Overwrite)
        assertEquals("oldpw", o.replace[0].password)
    }

    @Test fun `源文件里没有网址时已有的网址留着`() {
        val o = outcome(HEAD + "微信,,ax,newpw,,\n", listOf(OLD), Policy.Overwrite)
        assertEquals(listOf("weixin.qq.com"), o.replace[0].domains)
    }

    @Test fun `覆盖时网址两边合并去重，写法留旧的`() {
        val o = outcome(HEAD + "微信,\"https://weixin.qq.com/x m.qq.com\",ax,newpw,,\n", listOf(OLD), Policy.Overwrite)
        assertEquals(listOf("weixin.qq.com", "m.qq.com"), o.replace[0].domains)
    }

    @Test fun `覆盖不会弄丢收藏`() {
        val o = outcome(HEAD + "微信,,ax,newpw,,\n", listOf(OLD), Policy.Overwrite)
        assertTrue(o.replace[0].favorite)
    }

    @Test fun `覆盖保留 createdAt`() {
        val old = OLD.copy(createdAt = 12345L)
        val o = outcome(HEAD + "微信,,ax,newpw,,\n", listOf(old), Policy.Overwrite)
        assertEquals(12345L, o.replace[0].createdAt)
    }

    @Test fun `旧备注非空时新备注不写进去，而且要记账`() {
        val o = outcome(HEAD + "微信,,ax,newpw,新备注,\n", listOf(OLD), Policy.Overwrite)
        assertEquals("旧备注", o.replace[0].notes)
        assertTrue(Flag.NotesKept in o.flags)
    }

    @Test fun `旧备注为空时新备注写得进去`() {
        val old = OLD.copy(notes = "")
        val o = outcome(HEAD + "微信,,ax,newpw,新备注,\n", listOf(old), Policy.Overwrite)
        assertEquals("新备注", o.replace[0].notes)
        assertFalse(Flag.NotesKept in o.flags)
    }

    @Test fun `覆盖的对象在预览期间被别处删掉时当成新增`() {
        val cs = candidates(HEAD + "微信,,ax,newpw,,\n", listOf(OLD))
        val o = CsvImport.apply(cs, emptyList(), Policy.Overwrite)
        assertEquals(1, o.add.size)
        assertTrue(o.replace.isEmpty())
    }

    /* ───────────── 记账口径 ───────────── */

    @Test fun `被处置跳过的行不记它的账`() {
        // 那一行没有密码，但它没有导入——说「有条目没有密码」是误导
        val o = outcome(HEAD + "微信,,ax,,,\n", listOf(OLD), Policy.Skip)
        assertFalse(Flag.NoPassword in o.flags)
    }

    @Test fun `导入了的行才记账`() {
        val o = outcome(HEAD + "论坛,,ax9,,,\n", listOf(OLD), Policy.Skip)
        assertTrue(Flag.NoPassword in o.flags)
    }

    @Test fun `跳过分两种，分别计数`() {
        val csv = HEAD + "微信,,ax,newpw,,\n" + ",,,,,\n"
        val o = outcome(csv, listOf(OLD), Policy.Skip)
        assertEquals(1, o.skippedByPolicy)
        assertEquals(1, o.skippedByRow)
    }

    @Test fun `跳过的行按理由归并计数`() {
        val cs = candidates(HEAD + ",,,,,\n" + "分组,,,,,\n" + "微信,,ax,p,,\n")
        val counts = CsvImport.skipCounts(cs)
        assertEquals(1, counts[Skip.Blank])
        assertEquals(1, counts[Skip.NothingToStore])
        assertNull(counts[Skip.Nameless])
    }

    /* ───────────── 文案与敏感性 ───────────── */

    @Test fun `摘要先说数量`() {
        val o = outcome(HEAD + "新站,,zz,p,,\n微信,,ax,q,,\n", listOf(OLD), Policy.Overwrite)
        assertEquals("新增 1 条，覆盖 1 条。", CsvImport.summary(o))
    }

    @Test fun `什么都导不了时说得明白`() {
        val o = outcome(HEAD + ",,,,,\n", emptyList(), Policy.Skip)
        assertTrue(CsvImport.summary(o).contains("没有可以导入"))
    }

    @Test fun `摘要不出现稍后重试和联系客服`() {
        val o = outcome(HEAD + "微信,,ax,q,,\n", listOf(OLD), Policy.Skip)
        val s = CsvImport.summary(o)
        assertFalse(s.contains("稍后"))
        assertFalse(s.contains("客服"))
    }

    @Test fun `撞上的说明只带行号和理由，不带内容`() {
        val c = candidates(HEAD + "微信,,ax,supersecret,,\n", listOf(OLD)).single()
        val s = CsvImport.hitNote(c)
        assertTrue(s.contains("第 2 行"))
        assertFalse(s.contains("supersecret"))
        assertFalse(s.contains("ax"))
        assertFalse(s.contains("old1"))
    }

    @Test fun `候选的 toString 不吐内容`() {
        val c = candidates(HEAD + "微信,,ax,supersecret,备注,\n", listOf(OLD)).single()
        val s = c.toString()
        assertFalse(s.contains("supersecret"))
        assertFalse(s.contains("微信"))
        assertFalse(s.contains("备注"))
        assertTrue(s.contains("line=2"))
    }

    @Test fun `结果的 toString 不吐内容`() {
        val o = outcome(HEAD + "微信,,ax,supersecret,,\n", listOf(OLD), Policy.Overwrite)
        assertFalse(o.toString().contains("supersecret"))
    }

    @Test fun `Hit 的 toString 不带 id`() {
        val c = candidates(HEAD + "微信,,ax,p,,\n", listOf(OLD)).single()
        assertFalse(c.hit.toString().contains("old1"))
    }

    @Test fun `文案互不重样`() {
        assertEquals(Skip.entries.size, Skip.entries.map { it.note }.toSet().size)
        assertEquals(Flag.entries.size, Flag.entries.map { it.note }.toSet().size)
        assertEquals(Policy.entries.size, Policy.entries.map { it.note }.toSet().size)
        assertEquals(Match.entries.size, Match.entries.map { it.why }.toSet().size)
    }

    @Test fun `记账文案按声明顺序输出`() {
        val o = outcome(HEAD + "论坛,,ax9,,,\n" + ",a.com,zz,p,,\n", emptyList(), Policy.Skip)
        assertEquals(Flag.entries.filter { it in o.flags }.map { it.note }, o.noteTexts())
    }

    @Test fun `三种处置的说明互不重样，而且都说清了代价`() {
        assertNotEquals(Policy.Skip.note, Policy.Overwrite.note)
        assertTrue(Policy.Overwrite.note.contains("空"))
        assertTrue(Policy.KeepBoth.note.contains("同名"))
    }

    /* ───────────── 边角 ───────────── */

    @Test fun `映射里没有密码列时全表都只有账号`() {
        val csv = "name,username\n微信,ax\n"
        val cs = candidates(csv)
        assertTrue(cs.single().willImport)
        assertTrue(Flag.NoPassword in cs.single().flags)
    }

    @Test fun `参差不齐的短行不会越界`() {
        val cs = candidates("name,url,username,password,notes,folder\n微信,,ax\n")
        assertTrue(cs.single().willImport)
        assertEquals("", cs.single().entry.password)
    }

    @Test fun `空表得到空清单`() {
        val o = CsvImport.apply(emptyList(), emptyList(), Policy.Skip)
        assertEquals(0, o.total)
        assertEquals(0, o.skippedByRow)
    }
}
