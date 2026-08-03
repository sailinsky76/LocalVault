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

import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.edit.EntryForm
import cn.localvault.app.ui.list.VaultIndex

/**
 * CSV 导入的第四层：**一行 → 一条条目，以及它和库里已有条目的关系**。
 *
 * 上一层是 [CsvMapping]（哪一列是什么），下一层是页面（M5-2b）。
 * **这个文件没有一行 `android.*`，也没有一行 Compose。**
 *
 * ───────────── 这一层为什么单独存在 ─────────────
 *
 * 因为判重的两种错法都是**静默**的，而且方向相反：
 *
 * - 算宽了（把不同的条目当成同一条）→ 用户选「覆盖」，一条好好的旧密码被
 *   另一个站点的密码盖掉。旧密码没有备份，没有回收站，屏幕上什么都不会报。
 * - 算窄了（同一条没认出来）→ 用户的库里出现两条「微信」，
 *   哪条是新的看不出来，改密码时改了不常用的那条，下次登录失败。
 *
 * 所以判重不给「是 / 不是」，只给**三档强度**（[Match]），
 * 每一档在 M5-2b 上都会明说「凭什么算撞了」，处置由用户选。
 *
 * ───────────── 不写第二份规则 ─────────────
 *
 * 行怎么变成条目，走的是 [EntryForm] 那一套：网址靠 [EntryForm.domainLines]
 * 切行去重（它内部复用 [VaultIndex.normalizeDomain]），条目靠
 * [EntryForm.newEntry] 造。这不是为了少写几行，是因为**规则一旦分叉，
 * 同一个库里就会有两种数据**——导入进来的条目网址带着 `https://`、
 * 手工新增的不带，将来 M4 自动填充按哪一份匹配都对不齐。
 *
 * id 和三个时间戳一律留空，由 `VaultSession.addEntry` 补
 * （决策：id 的生成只能有一个地方）。
 *
 * ───────────── 敏感性 ─────────────
 *
 * [Candidate] 里装着明文密码。所有 `toString()` 只报形状不报内容，
 * 所有文案只带行号和条数，同 [CsvText.PLAINTEXT_NOTE] 与 [CsvParser]。
 */
object CsvImport {

    /* ══════════════════════════ 跳过 ══════════════════════════ */

    /**
     * **不导入**这一行的理由。每一条都是「导进去也是垃圾」，不是「看着不对劲」。
     *
     * 这张表刻意很短。导入这件事上，多导一条垃圾用户删得掉，
     * 少导一条真数据他**发现不了**——所以拿不准的一律导。
     */
    enum class Skip(val note: String) {
        Blank(
            "整行都是空的（映射过去的每一格都没有内容），跳过。"
        ),
        NotLogin(
            "「条目类型」那一列说这不是一条登录记录（是安全笔记、银行卡或者身份信息），跳过。" +
                "这个版本只导入登录条目——把一段安全笔记塞进「密码」栏，" +
                "它在列表里看起来就和一条真密码一样了。"
        ),
        Nameless(
            "这一行既没有名称也没有网址，跳过。导进来的条目在列表里会是一行空白，" +
                "点进去才知道是什么，也搜不到。"
        ),
        NothingToStore(
            "这一行只有一个名字，账号和密码都是空的，跳过。" +
                "这种行通常是源文件里的分组行或者文件夹行，不是一条记录。"
        ),
    }

    /* ══════════════════════════ 记账 ══════════════════════════ */

    /**
     * 照样导入、但值得摆到用户面前的事。写法同 [CsvParser.Anomaly]：
     * 发生了什么 + 我们怎么处理的。
     */
    enum class Flag(val note: String) {
        NoPassword(
            "有条目没有密码，只有账号。它们照样导入了——确实有人拿密码管理器当通讯录用，" +
                "而丢掉它们是发现不了的。导入后可以逐条补。"
        ),
        NoUsername(
            "有条目没有账号，只有密码。照样导入。"
        ),
        NameFromUrl(
            "有条目没有名称，用网址当了名字。导入后可以逐条改。"
        ),
        MultipleUrls(
            "有条目带了不止一个网址，全部保留了（同一个主机的不同写法会去重）。"
        ),
        TotpKept(
            "动态验证码密钥按**原样**存下来了，没有解析。这个版本还不会算验证码，" +
                "现在解析一旦解错没人会发现；等这个功能做出来时源数据还是完整的。"
        ),
        DuplicateInFile(
            "源文件自己有重复的行（同名同账号）。它们都导入了——源文件里是两条，" +
                "这里就是两条，替你合并等于替你做主。导入后核对一下。"
        ),
        NotesKept(
            "覆盖时源文件的备注没有写进去，因为那一条原来已经有备注了。" +
                "旧备注保留，新备注没有拼在后面——拼接会在反复导入之后攒出一堆重复的话。"
        ),
    }

    /* ══════════════════════════ 判重 ══════════════════════════ */

    /**
     * 撞上的强度。**三档都只是「像」，不是「是」**，处置一律由用户选。
     *
     * 排序即强度，[NameAndUser] 最强。同强度时取库里靠前的那一条。
     */
    enum class Match(val label: String, val why: String) {
        NameAndUser("同名同账号", "名称和账号都一模一样"),
        SiteAndUser("同网站同账号", "账号一样，而且有一个网址指向同一个主机"),
        NameOnly("同名", "名称一样，但账号对不上（有一边是空的）"),
    }

    /** 撞上了谁。只带 id，不带那条条目本身——避免把库里的密码一路带进界面状态。 */
    class Hit(val existingId: String, val match: Match) {
        override fun toString(): String = "CsvHit(${match.name})"
    }

    /* ══════════════════════════ 候选 ══════════════════════════ */

    /**
     * 一行转出来的东西。
     *
     * [entry] 的 `id` 是空串、三个时间戳是 0——它还不是一条条目，
     * 是一份**打算变成条目的草稿**。[line] 是它在源文件里的行号，
     * 用户拿它去源文件里定位，也是界面上唯一能显示的定位信息。
     */
    class Candidate(
        val line: Int,
        val entry: VaultEntry,
        val skip: Skip?,
        val flags: Set<Flag>,
        val hit: Hit?,
    ) {
        val willImport: Boolean get() = skip == null

        internal fun with(hit: Hit?, extra: Flag? = null) = Candidate(
            line, entry, skip,
            if (extra == null) flags else flags + extra,
            hit,
        )

        /** **不吐任何字段内容。** */
        override fun toString(): String =
            "CsvCandidate(line=$line, ${if (skip == null) "import" else "skip:" + skip.name}" +
                (if (hit == null) "" else ", hit:" + hit.match.name) +
                (if (flags.isEmpty()) "" else ", ${flags.size} flags") + ")"
    }

    /* ══════════════════════════ 转换 ══════════════════════════ */

    /** [Role.Kind] 那一列里，明确表示「这不是一条登录记录」的取值。 */
    private val NON_LOGIN = setOf(
        "note", "securenote", "secure", "card", "creditcard", "identity",
        "sshkey", "document", "server", "database",
        "笔记", "安全笔记", "银行卡", "卡片", "身份", "证件", "文档",
    )

    /** 「常用」那一列里算真的取值。其余一律算假，包括空。 */
    private val TRUTHY = setOf("1", "true", "yes", "y", "t", "是", "真", "★", "star")

    /**
     * 把整张表转成候选，并做**源文件内部**的判重。
     *
     * 库内判重是下一步（[against]）——分开是因为这两件事的输入不同：
     * 这一步只看文件，那一步要看用户的库，而后者在预览页上会随着
     * 用户切换处置反复重算。
     */
    fun convert(table: CsvParser.Table, plan: CsvMapping.Plan): List<Candidate> {
        val raw = table.rows.map { row -> convertRow(row.cells, row.line, plan) }

        // 源文件内部判重：同名同账号，两者都非空才算。
        // 「都为空算撞」是一定要避免的：那会让文件里所有无账号的行互相撞成一片。
        val seen = HashSet<String>()
        return raw.map { c ->
            if (!c.willImport) return@map c
            val n = nameKey(c.entry)
            val u = userKey(c.entry)
            if (n.isEmpty() || u.isEmpty()) return@map c
            if (seen.add("$n\u0000$u")) c else c.with(c.hit, Flag.DuplicateInFile)
        }
    }

    internal fun convertRow(cells: List<String>, line: Int, plan: CsvMapping.Plan): Candidate {
        fun cell(role: CsvMapping.Role): String =
            plan.columnOf(role)?.let { cells.getOrNull(it) } ?: ""

        // 密码不 trim，其余 trim——空格完全可以是密码的一部分（同 EntryForm.cleaned）。
        // 替用户把首尾空格去掉，结果是他下次登录不上，而且这里不会有任何提示。
        val name = cell(CsvMapping.Role.Name).trim()
        val username = cell(CsvMapping.Role.Username).trim()
        val password = cell(CsvMapping.Role.Password)
        val urlText = cell(CsvMapping.Role.Url).trim()
        val notes = cell(CsvMapping.Role.Notes).trim()
        val category = cell(CsvMapping.Role.Category).trim()
        val totp = cell(CsvMapping.Role.Totp).trim()
        val kind = cell(CsvMapping.Role.Kind).trim().lowercase()
        val favorite = cell(CsvMapping.Role.Favorite).trim().lowercase() in TRUTHY

        val flags = LinkedHashSet<Flag>()

        val everythingBlank = name.isEmpty() && username.isEmpty() && password.isEmpty() &&
            urlText.isEmpty() && notes.isEmpty()
        if (everythingBlank) return skipped(line, Skip.Blank)

        if (kind.isNotEmpty() && CsvMapping.normalizeName(kind) in NON_LOGIN) {
            return skipped(line, Skip.NotLogin)
        }

        val domains = EntryForm.domainLines(urlText)
        if (domains.size > 1) flags += Flag.MultipleUrls

        if (name.isEmpty() && domains.isEmpty()) return skipped(line, Skip.Nameless)

        val finalName = if (name.isNotEmpty()) {
            name
        } else {
            flags += Flag.NameFromUrl
            // 归一后的主机名比原串好读（`https://a.com/login` → `a.com`），
            // 归一不出东西就退回原串——名字宁可难看也不要空。
            VaultIndex.normalizeDomain(domains[0]).ifEmpty { domains[0] }
        }

        if (username.isEmpty() && password.isEmpty()) return skipped(line, Skip.NothingToStore)
        if (password.isEmpty()) flags += Flag.NoPassword
        if (username.isEmpty()) flags += Flag.NoUsername
        if (totp.isNotEmpty()) flags += Flag.TotpKept

        // 走 EntryForm 那一套造条目，不另写一份（见文件头）。
        val base = EntryForm.newEntry(
            EntryForm.Draft(
                name = finalName,
                username = username,
                password = password,
                domainsText = urlText,
                category = category,
                notes = notes,
            )
        )
        val entry = base.copy(
            favorite = favorite,
            totpSecret = totp.ifEmpty { null },
        )
        return Candidate(line, entry, null, flags, null)
    }

    private fun skipped(line: Int, why: Skip) =
        Candidate(line, VaultEntry(id = "", name = ""), why, emptySet(), null)

    /* ══════════════════════════ 和库里比 ══════════════════════════ */

    private fun nameKey(e: VaultEntry) = e.name.trim().lowercase()
    private fun userKey(e: VaultEntry) = e.username.trim().lowercase()
    private fun domainKeys(e: VaultEntry) =
        e.domains.map { VaultIndex.normalizeDomain(it) }.filter { it.isNotEmpty() }.toSet()

    /**
     * 给每个候选找出库里最像的那一条。
     *
     * 三档的判据写死在这里，**每一档都要求参与比较的字段非空**：
     * 「两边账号都是空的所以算同账号」会让一个库里所有无账号条目互相撞成一片，
     * 而用户此时多半刚点了「覆盖」。
     */
    fun against(candidates: List<Candidate>, existing: List<VaultEntry>): List<Candidate> {
        if (existing.isEmpty()) return candidates
        val index = existing.map { Triple(nameKey(it), userKey(it), domainKeys(it)) }
        return candidates.map { c ->
            if (!c.willImport) return@map c
            val n = nameKey(c.entry)
            val u = userKey(c.entry)
            val d = domainKeys(c.entry)

            var best: Hit? = null
            for (i in existing.indices) {
                val (en, eu, ed) = index[i]
                val m = when {
                    u.isNotEmpty() && u == eu && n.isNotEmpty() && n == en -> Match.NameAndUser
                    u.isNotEmpty() && u == eu && d.any { it in ed } -> Match.SiteAndUser
                    n.isNotEmpty() && n == en -> Match.NameOnly
                    else -> null
                } ?: continue
                val b = best
                if (b == null || m.ordinal < b.match.ordinal) best = Hit(existing[i].id, m)
                if (m == Match.NameAndUser) break
            }
            if (best == null) c else c.with(best)
        }
    }

    /** 一步到位：表 + 映射 + 现有条目 → 候选清单。M5-2b 用这个入口。 */
    fun prepare(
        table: CsvParser.Table,
        plan: CsvMapping.Plan,
        existing: List<VaultEntry>,
    ): List<Candidate> = against(convert(table, plan), existing)

    /* ══════════════════════════ 处置 ══════════════════════════ */

    /**
     * 撞上了怎么办。**默认是 [Skip]**：三档里最弱的那一档只凭一个同名，
     * 而默认值是那种用户不看就点下一步的东西，所以默认必须是最不会毁数据的那个。
     */
    enum class Policy(val label: String, val note: String) {
        Skip(
            "跳过",
            "撞上的行不导入，库里现有的条目一个字都不动。最稳妥，也可能漏掉源文件里更新的密码。"
        ),
        Overwrite(
            "覆盖",
            "用源文件的内容更新库里那一条。**源文件里空着的格子不会覆盖掉已有内容**——" +
                "CSV 里的空格子意思是「这一列没导出」，不是「请清空」。"
        ),
        KeepBoth(
            "都留着",
            "两条都保留，名字不改。列表里会出现两条同名条目，靠账号区分；" +
                "替你在名字后面加个「(1)」也是替你改数据，这里不做。"
        ),
    }

    /**
     * 落盘前的最终清单。
     *
     * [add] 里的条目 id 是空串（交给 `VaultSession.addEntry` 补），
     * [replace] 里的条目带着**库里那条原来的 id**（交给 `updateEntry`）。
     */
    class Outcome(
        val add: List<VaultEntry>,
        val replace: List<VaultEntry>,
        val skippedByPolicy: Int,
        val skippedByRow: Int,
        val flags: Set<Flag>,
    ) {
        val total: Int get() = add.size + replace.size

        fun noteTexts(): List<String> = Flag.entries.filter { it in flags }.map { it.note }

        override fun toString(): String =
            "CsvOutcome(+${add.size}, ~${replace.size}, skip ${skippedByPolicy + skippedByRow})"
    }

    fun apply(
        candidates: List<Candidate>,
        existing: List<VaultEntry>,
        policy: Policy,
    ): Outcome {
        val byId = existing.associateBy { it.id }
        val add = ArrayList<VaultEntry>()
        val replace = ArrayList<VaultEntry>()
        val flags = LinkedHashSet<Flag>()
        var byPolicy = 0
        var byRow = 0

        for (c in candidates) {
            if (!c.willImport) { byRow++; continue }
            val hit = c.hit
            if (hit == null) { flags += c.flags; add += c.entry; continue }
            when (policy) {
                // 被处置跳过的行不记它的账：它没有导入，说「有条目没有密码」是误导
                Policy.Skip -> byPolicy++
                Policy.KeepBoth -> { flags += c.flags; add += c.entry }
                Policy.Overwrite -> {
                    flags += c.flags
                    val old = byId[hit.existingId]
                    if (old == null) {
                        // 库在预览期间被改过（另一处删了那条）。当成新增，
                        // 不当成失败——用户要的是「把这行导进去」，那条没了正好直接加。
                        add += c.entry
                    } else {
                        val merged = merge(old, c.entry)
                        if (merged.notes == old.notes && c.entry.notes.isNotBlank() &&
                            old.notes.isNotBlank()
                        ) flags += Flag.NotesKept
                        replace += merged
                    }
                }
            }
        }
        return Outcome(add, replace, byPolicy, byRow, flags)
    }

    /**
     * 覆盖时的合并规则。**空的不覆盖。**
     *
     * 这条是这一层最重要的一句话。CSV 里一个空格子的含义是
     * 「源那边这一列没有导出」——1Password 不导出安卓包名，
     * Chrome 不导出分类，Firefox 连名称列都没有。
     * 如果覆盖等于整条替换，用户选一次「覆盖」就会把自己在这里补过的
     * 分类、备注、包名全部清空，而屏幕上不会有任何提示，也没有回收站。
     *
     * `id` 和 `createdAt` 一定留旧的（那是同一条条目的身份），
     * `favorite` 取或（收藏过就还是收藏），网址两边合并去重
     * （去重按 [VaultIndex.normalizeDomain] 归一后的形式，写法留旧的——同
     * [EntryForm.domainLines] 里那条「保留第一次出现的写法」）。
     * 时间戳交给 `VaultSession.updateEntry` 刷新，尤其 `passwordUpdatedAt`
     * 必须由「密码到底变没变」决定，那个判断只有会话层拿得到。
     */
    internal fun merge(old: VaultEntry, new: VaultEntry): VaultEntry {
        val seen = HashSet<String>()
        val domains = ArrayList<String>(old.domains.size + new.domains.size)
        for (d in old.domains + new.domains) {
            val k = VaultIndex.normalizeDomain(d)
            if (k.isEmpty() || !seen.add(k)) continue
            domains += d
        }
        return old.copy(
            name = new.name.ifBlank { old.name },
            username = new.username.ifBlank { old.username },
            password = new.password.ifEmpty { old.password },
            domains = domains,
            category = new.category.ifBlank { old.category },
            // 备注只在旧的为空时才写入。拼接会在反复导入之后攒出一堆重复的话，
            // 而备注恰恰是用户放密保问题答案的地方，越乱越危险。
            notes = old.notes.ifBlank { new.notes },
            totpSecret = new.totpSecret ?: old.totpSecret,
            favorite = old.favorite || new.favorite,
        )
    }

    /* ══════════════════════════ 文案 ══════════════════════════ */

    /** 预览页顶上那一句：**先说会新增多少、覆盖多少、跳过多少，再说别的**。 */
    fun summary(outcome: Outcome): String {
        val skip = outcome.skippedByPolicy + outcome.skippedByRow
        if (outcome.total == 0) {
            return "这份文件里没有可以导入的行" + (if (skip > 0) "（跳过 $skip 条）。" else "。")
        }
        val parts = ArrayList<String>(3)
        if (outcome.add.isNotEmpty()) parts += "新增 ${outcome.add.size} 条"
        if (outcome.replace.isNotEmpty()) parts += "覆盖 ${outcome.replace.size} 条"
        if (skip > 0) parts += "跳过 $skip 条"
        return parts.joinToString("，") + "。"
    }

    /** 撞上的那几条在预览里的一行说明。**只带行号和理由，不带内容。** */
    fun hitNote(c: Candidate): String {
        val h = c.hit ?: return ""
        return "第 ${c.line} 行和库里已有的一条${h.match.label}（${h.match.why}）"
    }

    /** 跳过的行按理由归并计数，界面上一类一行，不是一行一条。 */
    fun skipCounts(candidates: List<Candidate>): Map<Skip, Int> {
        val out = LinkedHashMap<Skip, Int>()
        for (s in Skip.entries) {
            val n = candidates.count { it.skip == s }
            if (n > 0) out[s] = n
        }
        return out
    }
}
