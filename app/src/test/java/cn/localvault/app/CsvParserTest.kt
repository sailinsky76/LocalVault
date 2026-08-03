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

import cn.localvault.app.ui.importer.CsvParser
import cn.localvault.app.ui.importer.CsvParser.Anomaly
import cn.localvault.app.ui.importer.CsvParser.Delimiter
import cn.localvault.app.ui.importer.CsvParser.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 解析内核。
 *
 * 这一堆用例存在的理由只有一句话：**密码里有逗号。**
 * 一台按逗号 split 的解析器会把 `"a,b"` 切成两半，而且不报错——
 * 导进来的密码是错的，用户几个月后登录失败时才发现，那时源文件早删了。
 * 所以引号、引号里的引号、引号里的换行、三种行尾，全都得钉住。
 */
class CsvParserTest {

    private fun table(text: String): CsvParser.Table {
        val p = CsvParser.parse(text)
        assertTrue("期望解析成功，实际是 $p", p is Parsed.Ok)
        return (p as Parsed.Ok).table
    }

    private fun cells(text: String): List<List<String>> =
        table(text).let { t -> listOf(t.header) + t.rows.map { it.cells } }

    /* ───────────── 基本形状 ───────────── */

    @Test fun `最简单的一张表`() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), cells("a,b\n1,2\n"))
    }

    @Test fun `末尾没有换行也认`() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), cells("a,b\n1,2"))
    }

    @Test fun `CRLF 行尾`() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), cells("a,b\r\n1,2\r\n"))
    }

    @Test fun `只有 CR 的老式行尾`() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), cells("a,b\r1,2\r"))
    }

    @Test fun `空单元格保留位置`() {
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("1", "", "3")),
            cells("a,b,c\n1,,3\n"),
        )
    }

    @Test fun `行首和行尾的空单元格都不丢`() {
        assertEquals(listOf(listOf("a", "b"), listOf("", "2")), cells("a,b\n,2\n"))
        assertEquals(listOf(listOf("a", "b"), listOf("1", "")), cells("a,b\n1,\n"))
    }

    @Test fun `文件在分隔符后面就结束了，最后那一格是空的`() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "")), cells("a,b\n1,"))
    }

    /* ───────────── 引号：这一节是整个文件的理由 ───────────── */

    @Test fun `引号里的逗号不是分隔符`() {
        assertEquals(
            listOf(listOf("name", "password"), listOf("wx", "a,b,c")),
            cells("name,password\nwx,\"a,b,c\"\n"),
        )
    }

    @Test fun `引号里的分号不是分隔符`() {
        assertEquals(
            listOf(listOf("name", "password"), listOf("wx", "a;b;c")),
            cells("name,password\nwx,\"a;b;c\"\n"),
        )
    }

    @Test fun `两个连写的引号是一个引号`() {
        assertEquals(
            listOf(listOf("a", "b"), listOf("he said \"hi\"", "2")),
            cells("a,b\n\"he said \"\"hi\"\"\",2\n"),
        )
    }

    @Test fun `整格就是一个引号字符`() {
        assertEquals(listOf(listOf("a", "b"), listOf("\"", "2")), cells("a,b\n\"\"\"\",2\n"))
    }

    @Test fun `空的引号对是一格空字符串，不是空行`() {
        assertEquals(listOf(listOf("a", "b"), listOf("", "2")), cells("a,b\n\"\",2\n"))
    }

    @Test fun `引号里的换行留在格子里`() {
        assertEquals(
            listOf(listOf("a", "b"), listOf("第一行\n第二行", "2")),
            cells("a,b\n\"第一行\n第二行\",2\n"),
        )
    }

    @Test fun `引号里的 CRLF 归一成 LF`() {
        // 带着 \r 存进库里，以后在任何界面上都会多出一个看不见的字符。
        assertEquals(
            listOf(listOf("a", "b"), listOf("l1\nl2", "2")),
            cells("a,b\n\"l1\r\nl2\",2\n"),
        )
    }

    @Test fun `引号在文件末尾正常闭合`() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), cells("a,b\n1,\"2\""))
    }

    /* ───────────── 分隔符猜测 ───────────── */

    @Test fun `分号分隔（中文区 Excel 的默认导出）`() {
        val t = table("名称;账号\n微信;abc\n")
        assertEquals(Delimiter.Semicolon, t.delimiter)
        assertEquals(listOf("名称", "账号"), t.header)
    }

    @Test fun `制表符分隔`() {
        val t = table("a\tb\n1\t2\n")
        assertEquals(Delimiter.Tab, t.delimiter)
    }

    @Test fun `按第一行的多数派选，数据行里的逗号带不偏`() {
        val t = table("a,b,c\n1,x;y,3\n")
        assertEquals(Delimiter.Comma, t.delimiter)
        assertEquals(listOf("1", "x;y", "3"), t.rows[0].cells)
    }

    @Test fun `第一行引号里的分隔符不参与统计`() {
        val t = table("\"a,a\";b\n1;2\n")
        assertEquals(Delimiter.Semicolon, t.delimiter)
        assertEquals(listOf("a,a", "b"), t.header)
    }

    @Test fun `一个分隔符都没有时退回逗号并明说`() {
        val p = CsvParser.parse("onlyonecolumn\nx\n")
        assertTrue(p is Parsed.SingleColumn)
        assertEquals(Delimiter.Comma, (p as Parsed.SingleColumn).tried)
    }

    /* ───────────── 参差不齐的行 ───────────── */

    @Test fun `短行补空并记账`() {
        val t = table("a,b,c\n1,2\n")
        assertEquals(listOf("1", "2", ""), t.rows[0].cells)
        assertTrue(Anomaly.RaggedShort in t.anomalies)
    }

    @Test fun `长行不丢内容，多出来的进 overflow 并记账`() {
        val t = table("a,b\n1,2,3\n")
        assertEquals(listOf("1", "2"), t.rows[0].cells)
        assertEquals(listOf("3"), t.rows[0].overflow)
        assertTrue(Anomaly.RaggedLong in t.anomalies)
    }

    @Test fun `每一行的格数都等于表头列数`() {
        val t = table("a,b,c\n1\n1,2,3,4\n1,2,3\n")
        t.rows.forEach { assertEquals(t.width, it.cells.size) }
    }

    /* ───────────── 空行 ───────────── */

    @Test fun `空行被跳过且不占行`() {
        val t = table("a,b\n\n1,2\n\n3,4\n")
        assertEquals(2, t.rows.size)
        assertEquals(2, t.blankLines)
    }

    @Test fun `表格中间的空行会记账`() {
        assertTrue(Anomaly.BlankLineInside in table("a,b\n\n1,2\n").anomalies)
    }

    @Test fun `文件末尾多敲的回车不记账`() {
        // 这条太常见了，把它报出来只会让那一页显得吹毛求疵。
        val t = table("a,b\n1,2\n\n")
        assertEquals(1, t.blankLines)
        assertFalse(Anomaly.BlankLineInside in t.anomalies)
    }

    /* ───────────── 不合规但能读的 ───────────── */

    @Test fun `引号开了没关：读到末尾，记账，不丢内容`() {
        val t = table("a,b\n\"unterminated,2\n")
        assertTrue(Anomaly.UnterminatedQuote in t.anomalies)
        assertTrue(t.rows[0].cells[0].contains("unterminated"))
    }

    @Test fun `结束引号后面还有字：两截拼起来，记账`() {
        val t = table("a,b\n\"x\"junk,2\n")
        assertEquals("xjunk", t.rows[0].cells[0])
        assertTrue(Anomaly.TextAfterQuote in t.anomalies)
    }

    @Test fun `引号前有空格：不当引号，原样保留，记账`() {
        // 刻意不去「智能地」把引号剥掉——万一密码本身就叫 "abc"，剥掉就是改坏数据，
        // 而改坏一个密码是不会报错的。
        val t = table("a,b\n \"x\",2\n")
        assertEquals(" \"x\"", t.rows[0].cells[0])
        assertTrue(Anomaly.QuoteAfterSpace in t.anomalies)
    }

    /* ───────────── 行号 ───────────── */

    @Test fun `行号从 1 起算`() {
        val t = table("a,b\n1,2\n3,4\n")
        assertEquals(2, t.rows[0].line)
        assertEquals(3, t.rows[1].line)
    }

    @Test fun `空行和引号里的换行都照算行号`() {
        // 用户拿这个行号去源文件里定位，算少了就指到别处去了。
        val t = table("a,b\n\n\"x\ny\",2\nz,9\n")
        assertEquals(3, t.rows[0].line)
        assertEquals(5, t.rows[1].line)
    }

    /* ───────────── 该拒绝的 ───────────── */

    @Test fun `全是空行`() {
        assertEquals(Parsed.NoRows, CsvParser.parse("\n\n\n"))
    }

    @Test fun `只有表头`() {
        val p = CsvParser.parse("name,username,password\n")
        assertTrue(p is Parsed.HeaderOnly)
        assertEquals(3, (p as Parsed.HeaderOnly).columns)
    }

    @Test fun `只有一列`() {
        assertTrue(CsvParser.parse("password\nabc\n") is Parsed.SingleColumn)
    }

    @Test fun `列太多`() {
        val header = (0..CsvParser.MAX_COLUMNS).joinToString(",") { "c$it" }
        val p = CsvParser.parse("$header\n")
        assertTrue(p is Parsed.TooManyColumns)
        assertEquals(CsvParser.MAX_COLUMNS + 1, (p as Parsed.TooManyColumns).found)
    }

    @Test fun `行太多`() {
        val sb = StringBuilder("a,b\n")
        repeat(CsvParser.MAX_ROWS + 1) { sb.append("1,2\n") }
        val p = CsvParser.parse(sb.toString())
        assertTrue(p is Parsed.TooManyRows)
        assertEquals(CsvParser.MAX_ROWS + 1, (p as Parsed.TooManyRows).found)
    }

    @Test fun `刚好到行数上限还能导`() {
        val sb = StringBuilder("a,b\n")
        repeat(CsvParser.MAX_ROWS) { sb.append("1,2\n") }
        assertEquals(CsvParser.MAX_ROWS, table(sb.toString()).rows.size)
    }

    @Test fun `单格长得离谱是硬失败，整份都不导`() {
        // 成因是引号没配对，那意味着整张表从那里开始全部错位；
        // 一条被截断的密码导进去之后和一条好密码长得一模一样，事后分辨不出来。
        val big = "x".repeat(CsvParser.MAX_CELL_CHARS + 1)
        val p = CsvParser.parse("a,b\n$big,2\n")
        assertTrue(p is Parsed.CellTooLong)
        assertEquals(2, (p as Parsed.CellTooLong).line)
    }

    @Test fun `刚好到单格上限还能导`() {
        val big = "x".repeat(CsvParser.MAX_CELL_CHARS)
        val t = table("a,b\n$big,2\n")
        assertEquals(CsvParser.MAX_CELL_CHARS, t.rows[0].cells[0].length)
    }

    /* ───────────── 记账的呈现 ───────────── */

    @Test fun `notes 的顺序只看枚举声明顺序，与遇见顺序无关`() {
        val a = table("a,b,c\n1,2\n1,2,3,4\n")   // 先短后长
        val b = table("a,b,c\n1,2,3,4\n1,2\n")   // 先长后短
        assertEquals(a.notes(), b.notes())
        assertEquals(2, a.notes().size)
    }

    @Test fun `每条记账都写清了「怎么处理的」`() {
        Anomaly.entries.forEach {
            assertTrue("这条没说处理方式：${it.note}", it.note.length > 15)
            assertFalse(it.note.contains("可能有问题"))
        }
    }

    @Test fun `记账文案互不重样`() {
        val notes = Anomaly.entries.map { it.note }
        assertEquals(notes.size, notes.toSet().size)
    }

    /* ───────────── 不泄漏 ───────────── */

    @Test fun `Row 和 Table 的 toString 都不吐内容`() {
        val t = table("name,password\nzhangsan,hunter2\n")
        assertFalse(t.toString().contains("hunter2"))
        assertFalse(t.rows[0].toString().contains("hunter2"))
        assertFalse(t.rows[0].toString().contains("zhangsan"))
        assertTrue(t.rows[0].toString().contains("line=2"))
    }

    @Test fun `失败对象和失败文案里都不带单元格内容`() {
        val big = "s3cr3t".repeat(CsvParser.MAX_CELL_CHARS)
        val p = CsvParser.parse("a,b\n$big,2\n")
        assertFalse(p.toString().contains("s3cr3t"))
        assertFalse(CsvParser.message(p).contains("s3cr3t"))
    }

    /* ───────────── 文案 ───────────── */

    @Test fun `六类失败的文案互不重样`() {
        val msgs = listOf(
            CsvParser.message(Parsed.NoRows),
            CsvParser.message(Parsed.HeaderOnly(3)),
            CsvParser.message(Parsed.SingleColumn(Delimiter.Comma)),
            CsvParser.message(Parsed.TooManyRows(99999)),
            CsvParser.message(Parsed.TooManyColumns(99)),
            CsvParser.message(Parsed.CellTooLong(7)),
        )
        msgs.forEach { assertTrue(it.length > 10) }
        assertEquals(msgs.size, msgs.toSet().size)
    }

    @Test fun `失败文案里不出现空话，也不出现「已导入一部分」`() {
        val banned = listOf("稍后重试", "请重试", "联系客服", "未知错误", "系统繁忙", "已导入部分", "找回", "破解")
        listOf(
            CsvParser.message(Parsed.NoRows),
            CsvParser.message(Parsed.HeaderOnly(3)),
            CsvParser.message(Parsed.SingleColumn(Delimiter.Semicolon)),
            CsvParser.message(Parsed.TooManyRows(1)),
            CsvParser.message(Parsed.TooManyColumns(1)),
            CsvParser.message(Parsed.CellTooLong(1)),
        ).forEach { m -> banned.forEach { assertFalse("「$it」不该出现在：$m", m.contains(it)) } }
    }

    @Test fun `单格超长那条必须说清整份都没导`() {
        val m = CsvParser.message(Parsed.CellTooLong(42))
        assertTrue(m.contains("42"))
        assertTrue(m.contains("没有导入"))
    }

    @Test fun `分隔符猜错那条要把猜的是什么说出来`() {
        val m = CsvParser.message(Parsed.SingleColumn(Delimiter.Semicolon))
        assertTrue(m.contains(Delimiter.Semicolon.label))
        assertNotEquals(m, CsvParser.message(Parsed.SingleColumn(Delimiter.Comma)))
    }

    @Test fun `成功时没有文案`() {
        assertEquals("", CsvParser.message(CsvParser.parse("a,b\n1,2\n")))
    }

    /* ───────────── 真实导出的样子 ───────────── */

    @Test fun `Chrome 那种表头能整张读下来`() {
        val src = "name,url,username,password,note\n" +
            "example,https://example.com/,me@x.com,\"p,a\"\"ss\",\"多行\n备注\"\n"
        val t = table(src)
        assertEquals(listOf("name", "url", "username", "password", "note"), t.header)
        assertEquals(1, t.rows.size)
        assertEquals("p,a\"ss", t.rows[0].cells[3])
        assertEquals("多行\n备注", t.rows[0].cells[4])
        assertTrue(t.anomalies.isEmpty())
    }

    @Test fun `本工程生成器造出来的密码原样过得去`() {
        // M3-5a 的默认符号集里就有逗号、分号、引号和反斜杠。
        val pw = "a,b;c\"d\\e"
        val escaped = pw.replace("\"", "\"\"")
        val t = table("name,password\nx,\"$escaped\"\n")
        assertEquals(pw, t.rows[0].cells[1])
    }
}
