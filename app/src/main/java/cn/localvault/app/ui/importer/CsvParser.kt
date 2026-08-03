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

package cn.localvault.app.ui.importer

/**
 * CSV 导入的第二层：**一段文本 → 一张表**。
 *
 * **没有一行 `android.*`，也没有一行 Compose。** 上一层是 [CsvText]，
 * 下一层（列名映射 + 判重）是 M5-2a-2，页面是 M5-2b。
 *
 * ───────────── 为什么不用「按逗号 split」 ─────────────
 *
 * 因为密码里有逗号。
 *
 * 这不是抬杠：本工程自己的生成器（M3-5a）默认符号集里就有逗号和分号，
 * 1Password 和 Bitwarden 的导出里带逗号的密码更是遍地都是。
 * `line.split(",")` 遇到 `"a,b"` 会切成两半，于是**导进来的密码是错的，
 * 而且不报错**——用户要到几个月后登录失败时才发现，那时源文件早删了。
 *
 * 所以这里是一台老老实实的状态机：引号、引号里的引号（`""`）、
 * 引号里的换行、CRLF / LF / CR 三种行尾，一样都不能少。
 *
 * ───────────── 现实里的 CSV 不守 RFC 4180 ─────────────
 *
 * 严格按 RFC 写一台解析器不难，难的是决定**遇到不合规的输入时该怎么办**。
 * 这里的原则是：**能读的都读进来，读得不确定的全部记账**（[Anomaly]），
 * 由 M5-2b 那一页把账摆给用户看，让他在**导入之前**过一眼。
 * 一条都不静悄悄地丢，也一条都不静悄悄地改。
 *
 * 唯一的例外是[Parsed.CellTooLong]：那一条宁可整份不导。理由在它自己的注释里。
 *
 * ───────────── 敏感性 ─────────────
 *
 * 这张表里躺着用户的全部明文密码，同 [CsvText.PLAINTEXT_NOTE]。
 * 所以 [Row] 和 [Table] 都重写了 `toString()`，**只报形状不报内容**；
 * 这个文件里没有一行日志，任何失败也不把单元格内容带进消息里
 * （[Parsed.CellTooLong] 只带行号，不带那一格）。
 */
object CsvParser {

    /**
     * 数据行上限。两万条已经比任何真实的个人密码库大一个数量级
     * （国外几家管理器公布的中位数是几百条）。
     * 上限存在的理由不是省内存，是**预览页要把它们画出来**：
     * 一份被误选的日志文件可能有几百万行，那不是「导入很慢」，是界面直接卡死。
     */
    const val MAX_ROWS: Int = 20_000

    /** 列数上限。1Password 的完整导出大约 20 列，64 已经很宽裕。 */
    const val MAX_COLUMNS: Int = 64

    /**
     * 单格字符数上限。备注字段可以很长，32K 个字符（约一万个汉字）之后
     * 基本可以断定不是备注，而是把整个文件当成了一格——通常意味着
     * 某处的引号没配对，而那种表里**每一格的对应关系都已经错位了**。
     */
    const val MAX_CELL_CHARS: Int = 32_768

    /**
     * 支持的三种分隔符。
     *
     * 分号那一条不是为了标新立异：Excel 在中文 / 德语 / 法语区域设置下
     * 「另存为 CSV」写出来的就是分号分隔的（因为那些区域用逗号做小数点）。
     * 用户拿一份自己整理过的表来导入，撞上这条的概率相当高。
     */
    enum class Delimiter(val ch: Char, val label: String) {
        Comma(',', "逗号"),
        Semicolon(';', "分号"),
        Tab('\t', "制表符"),
    }

    /**
     * 解析过程中遇到的、**不影响继续但值得摆到用户面前**的事。
     *
     * 每一条的 [note] 都写成「发生了什么 + 我们怎么处理的」两截，
     * 不写「可能有问题」这种让人无从下手的话。
     */
    enum class Anomaly(val note: String) {
        UnterminatedQuote(
            "有一处引号没有配对，一直开到了文件末尾。从那里往后的内容被当成了同一格——" +
                "请重点核对预览里最后那几行。"
        ),
        TextAfterQuote(
            "有一格在结束引号之后还写了内容（形如 \"abc\"def）。两截被拼在了一起，一个字都没丢。"
        ),
        QuoteAfterSpace(
            "有一格在引号前面多了空格（形如 ␣\"abc\"）。按规矩这样的引号只是普通字符，" +
                "所以它被原样保留了——如果预览里看到密码带着引号，删掉引号再导。"
        ),
        RaggedShort(
            "有数据行的列数比表头少。缺的那几列按空白处理。"
        ),
        RaggedLong(
            "有数据行的列数比表头多。多出来的内容没有丢，但也没有归属——" +
                "最常见的原因是某一格里的逗号没有用引号括起来。"
        ),
        BlankLineInside(
            "表格中间有空行，已跳过。空行不会被当成一条数据，也不会占掉一个位置。"
        ),
    }

    /**
     * 一个数据行。[cells] 的长度**永远等于表头的列数**（短的补空、长的进 [overflow]），
     * 这样下一层做列映射时不必再处处判越界。
     *
     * [line] 是这一行在原文件里的**起始行号**（从 1 起算，引号里的换行照算），
     * 它是用户拿去在源文件里定位的唯一线索。
     */
    class Row(
        val cells: List<String>,
        val line: Int,
        val overflow: List<String> = emptyList(),
    ) {
        override fun toString(): String =
            "CsvRow(line=$line, ${cells.size} cells" +
                (if (overflow.isEmpty()) "" else ", +${overflow.size} overflow") + ")"
    }

    /** 解析出来的整张表。表头是第一条非空记录，**原样保留**，一个字都不改。 */
    class Table(
        val header: List<String>,
        val rows: List<Row>,
        val delimiter: Delimiter,
        val anomalies: Set<Anomaly>,
        val blankLines: Int,
    ) {
        val width: Int get() = header.size

        /** 按枚举声明顺序输出，与解析时的遇见顺序无关——这样界面上的措辞是稳定的。 */
        fun notes(): List<String> = Anomaly.entries.filter { it in anomalies }.map { it.note }

        override fun toString(): String =
            "CsvTable(${rows.size}x$width, ${delimiter.name}, ${anomalies.size} anomalies)"
    }

    sealed interface Parsed {
        class Ok(val table: Table) : Parsed {
            override fun toString(): String = "CsvParsed.Ok($table)"
        }

        /** 一条记录都没有（全是空行）。 */
        data object NoRows : Parsed

        /** 只有表头，没有任何数据行。 */
        data class HeaderOnly(val columns: Int) : Parsed

        /**
         * 只认出一列。这几乎一定是分隔符猜错了，或者根本不是 CSV——
         * 一列的表里不可能同时有账号和密码，继续往下走没有意义。
         */
        data class SingleColumn(val tried: Delimiter) : Parsed

        data class TooManyRows(val found: Int) : Parsed
        data class TooManyColumns(val found: Int) : Parsed

        /**
         * 某一格长得离谱。**这一条是硬失败，不是记账。**
         *
         * 因为它的成因（引号没配对）意味着这张表的列已经整体错位了，
         * 而错位之后「密码」那一列里装的可能是别人的备注，也可能是半截密码。
         * 一条被截断的密码导进保险库之后和一条好密码长得一模一样，
         * 没有任何办法事后分辨——那是这条路上唯一一种**静默的、不可逆的**损坏，
         * 所以这里选择整份拒绝。
         */
        data class CellTooLong(val line: Int) : Parsed
    }

    /**
     * 解析。[text] 应当来自 [CsvText.decode]（BOM 已剥、编码已定）。
     *
     * 分隔符不由调用方指定，也不问用户——第一次就问对了才叫好用。
     * 猜错的代价由 [Parsed.SingleColumn] 兜底，那条会明说「多半是分隔符不对」。
     */
    fun parse(text: String): Parsed {
        val delimiter = detectDelimiter(text)
        val scanned = scan(text, delimiter.ch)
        scanned.tooLongAtLine?.let { return Parsed.CellTooLong(it) }

        val records = scanned.records
        if (records.isEmpty()) return Parsed.NoRows

        val header = records[0].cells
        if (header.size > MAX_COLUMNS) return Parsed.TooManyColumns(header.size)
        if (header.size < 2) return Parsed.SingleColumn(delimiter)

        val dataCount = records.size - 1
        if (dataCount == 0) return Parsed.HeaderOnly(header.size)
        if (dataCount > MAX_ROWS) return Parsed.TooManyRows(dataCount)

        val anomalies = LinkedHashSet(scanned.anomalies)
        val rows = ArrayList<Row>(dataCount)
        for (i in 1 until records.size) {
            val rec = records[i]
            val cells = rec.cells
            when {
                cells.size == header.size -> rows += Row(cells, rec.line)
                cells.size < header.size -> {
                    anomalies += Anomaly.RaggedShort
                    rows += Row(cells + List(header.size - cells.size) { "" }, rec.line)
                }
                else -> {
                    anomalies += Anomaly.RaggedLong
                    rows += Row(
                        cells.subList(0, header.size).toList(),
                        rec.line,
                        cells.subList(header.size, cells.size).toList(),
                    )
                }
            }
        }
        return Parsed.Ok(Table(header, rows, delimiter, anomalies, scanned.blankLines))
    }

    /**
     * 猜分隔符：只看**第一条逻辑记录**（引号里的不算），谁出现得多算谁。
     *
     * 只看第一行，是因为表头是这份文件里最规整的一行——数据行里
     * 一个带逗号的密码就能把统计带偏。平手时按 [Delimiter] 的声明顺序取，
     * 一个都没有时取逗号（然后多半会走到 [Parsed.SingleColumn]，那条话说得清楚）。
     */
    fun detectDelimiter(text: String): Delimiter {
        val counts = IntArray(Delimiter.entries.size)
        var inQuotes = false
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') {
                        i++          // 引号里的 "" 是一个引号，不是结束
                    } else {
                        inQuotes = false
                    }
                }
            } else {
                when {
                    c == '"' -> inQuotes = true
                    c == '\n' || c == '\r' -> return pick(counts)
                    else -> {
                        val k = Delimiter.entries.indexOfFirst { it.ch == c }
                        if (k >= 0) counts[k]++
                    }
                }
            }
            i++
        }
        return pick(counts)
    }

    private fun pick(counts: IntArray): Delimiter {
        var best = -1
        var bestN = 0
        for (k in counts.indices) if (counts[k] > bestN) { bestN = counts[k]; best = k }
        return if (best < 0) Delimiter.Comma else Delimiter.entries[best]
    }

    /* ───────────────────── 状态机 ───────────────────── */

    private class Rec(val line: Int, val cells: List<String>)

    private class Scan {
        val records = ArrayList<Rec>()
        val anomalies = LinkedHashSet<Anomaly>()
        var blankLines = 0
        var tooLongAtLine: Int? = null
    }

    /**
     * 一遍扫完。
     *
     * 三条容错的写法值得留个记号，因为它们都不是 RFC 里的：
     *  - 引号开了没关：读到文件末尾为止，记 [Anomaly.UnterminatedQuote]；
     *  - 结束引号之后还有字：接着往后读到分隔符为止，两截拼起来，记 [Anomaly.TextAfterQuote]；
     *  - 引号前面有空格：**不当引号**（RFC 也是这么说的），整格原样留着，记 [Anomaly.QuoteAfterSpace]。
     *    这一条刻意不去「智能地」把引号剥掉：万一用户的密码本身就叫 `"abc"`，剥掉就改坏了数据，
     *    而改坏一个密码是不会报错的。留着，让他在预览里看见。
     *
     * 引号里的 CRLF 统一成 LF：那一格多半是备注，带着 `\r` 存进库里，
     * 以后在任何界面上都会多出一个看不见的字符。
     */
    private fun scan(text: String, delim: Char): Scan {
        val s = Scan()
        val n = text.length
        var i = 0
        var line = 1
        var pendingBlank = false

        while (i < n) {
            val recLine = line
            val cells = ArrayList<String>(8)
            var anyQuoted = false
            var endOfRecord = false

            while (!endOfRecord) {
                val sb = StringBuilder()
                var quoted = false
                var closed = false

                if (i < n && text[i] == '"') {
                    quoted = true
                    anyQuoted = true
                    i++
                    while (i < n) {
                        val c = text[i]
                        if (c == '"') {
                            if (i + 1 < n && text[i + 1] == '"') {
                                sb.append('"'); i += 2
                            } else {
                                i++; closed = true; break
                            }
                        } else if (c == '\r') {
                            if (i + 1 < n && text[i + 1] == '\n') i++
                            i++
                            line++
                            sb.append('\n')
                        } else {
                            if (c == '\n') line++
                            sb.append(c)
                            i++
                        }
                    }
                    if (!closed) s.anomalies += Anomaly.UnterminatedQuote
                }

                var trailing = false
                while (i < n) {
                    val c = text[i]
                    if (c == delim || c == '\n' || c == '\r') break
                    if (quoted) trailing = true
                    sb.append(c)
                    i++
                }
                if (trailing) s.anomalies += Anomaly.TextAfterQuote

                if (!quoted) {
                    val raw = sb.toString()
                    val trimmed = raw.trim()
                    if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"' &&
                        trimmed.length != raw.length
                    ) {
                        s.anomalies += Anomaly.QuoteAfterSpace
                    }
                }

                if (sb.length > MAX_CELL_CHARS) {
                    s.tooLongAtLine = recLine
                    return s
                }
                cells += sb.toString()

                if (i >= n) {
                    endOfRecord = true
                } else {
                    val c = text[i]
                    if (c == delim) {
                        i++
                    } else {
                        if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                        i++
                        line++
                        endOfRecord = true
                    }
                }
            }

            // 空行：一格、空内容、且没有出现过引号（`""` 是一格空字符串，不是空行）
            if (!anyQuoted && cells.size == 1 && cells[0].isEmpty()) {
                s.blankLines++
                pendingBlank = true
            } else {
                // 只有「空行后面还有记录」才值得说。文件末尾多敲的那个回车
                // 谁都不关心，把它报出来只会让那一页显得吹毛求疵。
                if (pendingBlank) {
                    s.anomalies += Anomaly.BlankLineInside
                    pendingBlank = false
                }
                s.records += Rec(recLine, cells)
            }
        }
        return s
    }

    /* ───────────────────── 文案 ───────────────────── */

    /**
     * 失败时说什么。同 [CsvText.message]：**每一条都指向一个不同的下一步**，
     * 都不写「稍后重试」，也都不把用户往「联系客服」上引。
     */
    fun message(p: Parsed): String = when (p) {
        is Parsed.Ok -> ""
        Parsed.NoRows ->
            "这份文件里一行内容都没有，只有空行。回原来那个应用重新导出一次。"
        is Parsed.HeaderOnly ->
            "这份文件只有表头（${p.columns} 列），没有任何一条数据。" +
                "多半是导出时选中的范围是空的——回原来那个应用再导一次。"
        is Parsed.SingleColumn ->
            "按${p.tried.label}切下来只有一列，这份文件多半不是 CSV，或者用的是这里不认识的分隔符" +
                "（认得逗号、分号和制表符）。用表格软件打开它，另存为逗号分隔的 CSV 再来一次。"
        is Parsed.TooManyRows ->
            "这份文件有 ${p.found} 行数据，超过了单次导入的上限（$MAX_ROWS 行）。" +
                "把它拆成几份分批导入。"
        is Parsed.TooManyColumns ->
            "这份文件有 ${p.found} 列，超过了上限（$MAX_COLUMNS 列）。" +
                "密码导出通常不会超过二十列——请确认选的是密码导出文件。"
        is Parsed.CellTooLong ->
            "第 ${p.line} 行有一格长得离谱，通常意味着那附近有一个引号没有配对，" +
                "而那会让整张表的列从那里开始全部错位。这种情况下导进来的密码可能是错的" +
                "（而且事后看不出来），所以这一份整个都没有导入。请修好那一行，或者重新导出一次。"
    }
}
