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

package cn.localvault.app.ui.list

import cn.localvault.app.core.vault.VaultEntry
import java.text.Collator
import java.util.Locale

/**
 * 列表的分组排序 + 搜索的匹配打分。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 和 `UnlockGuard` / `BiometricPolicy` 是同一个套路：把「什么该排前面」
 * 「哪些字段可以被搜」这类会被反复争论、又最容易悄悄写错的判断切出来，
 * 放进纯 JVM 能跑的地方钉死。界面层只负责把结果画出来。
 *
 * 这么切还有一个后续收益：[normalizeDomain] 是 M4 自动填充做**域名归属校验**
 * 时要用的同一套归一逻辑。搜索和自动填充如果各写一份，早晚会出现
 * 「搜得到但填不进去」或者更糟的「填进了不该填的站点」。
 */
object VaultIndex {

    /* ══════════════════════════ 分组 ══════════════════════════ */

    enum class Kind { Favorites, Category, Uncategorized }

    data class Section(
        val title: String,
        val entries: List<VaultEntry>,
        val kind: Kind,
    )

    const val FAVORITES_TITLE = "常用"
    const val UNCATEGORIZED_TITLE = "未分类"
    const val ALL_TITLE = "全部"

    /**
     * 把整库切成列表上看到的那几段。
     *
     * 顺序固定：常用 → 各分类（按名称）→ 未分类。
     *
     * ── 为什么不做 A–Z 首字母索引条 ──
     *
     * 右侧那条字母索引在通讯录里好用，在这里不好用：「微信」的首字母 W
     * 要靠一张拼音表才取得到，而把一张几千字的拼音表塞进一个以
     * 「依赖少、体积小」为卖点的 App 里不划算。只对英文名做索引条更糟——
     * 中文用户的库会变成一根几乎全部落在「#」上的字母条。
     * 用用户自己写的分类分组，信息量比机器猜的首字母大得多。
     *
     * ── 为什么收藏的条目不在分类组里重复出现 ──
     *
     * 重复会让顶栏那个「37 条」和用户拿手指头数出来的行数对不上。
     * 一个说不清自己到底有多少条数据的密码管理器，很难让人相信它没弄丢东西。
     */
    fun sections(entries: List<VaultEntry>): List<Section> {
        if (entries.isEmpty()) return emptyList()

        val favorites = entries.filter { it.favorite }.sortedWith(BY_NAME)
        val rest = entries.filterNot { it.favorite }
        val grouped = rest.filter { it.category.isNotBlank() }.groupBy { it.category.trim() }
        val uncategorized = rest.filter { it.category.isBlank() }.sortedWith(BY_NAME)

        val out = ArrayList<Section>(grouped.size + 2)
        if (favorites.isNotEmpty()) {
            out += Section(FAVORITES_TITLE, favorites, Kind.Favorites)
        }
        grouped.keys.sortedWith(Comparator { a, b -> compareName(a, b) }).forEach { c ->
            out += Section(c, grouped.getValue(c).sortedWith(BY_NAME), Kind.Category)
        }
        if (uncategorized.isNotEmpty()) {
            // 一个分组都没有、全库都没分类时，标题写「未分类」是句废话
            // （相对于什么而言未分类？），此时叫「全部」。
            val onlyOne = out.isEmpty()
            out += Section(
                if (onlyOne) ALL_TITLE else UNCATEGORIZED_TITLE,
                uncategorized,
                Kind.Uncategorized,
            )
        }
        return out
    }

    /** 库里出现过的分类，按名称排序。留给 M3-3b 的筛选和 M3-5 的分类选择器用。 */
    fun categories(entries: List<VaultEntry>): List<String> =
        entries.map { it.category.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(Comparator { a, b -> compareName(a, b) })

    /**
     * 上次备份之后被改过的条目数。
     *
     * ── 为什么提醒按「差异」而不是按「天数」 ──
     *
     * 「距离上次备份已 90 天」这种提醒会在用户三个月什么都没改的时候
     * 照样每天弹一次，而那三个月里他手上那份备份**一直是完好的**。
     * 骚扰换不来备份，只换来用户学会无视横幅——等真有 20 条改动没进备份时，
     * 他也不会看了。备份的价值取决于改了多少，提醒就该按改了多少来发。
     */
    fun changedSince(entries: List<VaultEntry>, since: Long): Int {
        if (since <= 0L) return entries.size
        return entries.count { it.updatedAt > since }
    }

    /* ══════════════════════════ 搜索 ══════════════════════════ */

    /**
     * 可以被搜的字段。**这张表是白名单，不是「目前实现了这几个」。**
     *
     * ── 为什么备注不参与搜索 ──
     *
     * 搜索结果必须能向用户解释「这一条为什么会出现在这里」，
     * 而唯一诚实的解释方式是把命中的那一小段原文展示出来。
     * 备注恰恰是用户拿来放密保问题答案、身份证号、银行预留手机号的地方——
     * 一旦它参与搜索，一次随手的搜索就会把这些东西摊在屏幕上，
     * 而用户按下那几个字的时候完全没预期到会看见它们。
     *
     * 密码就更不用说了：让密码可被搜索，等于给「肩窥 + 猜前几位」开了一道门。
     */
    enum class Field { Name, Username, Domain, Category }

    /** 匹配的完整程度。权重差距刻意拉得很开，见 [scoreOf]。 */
    enum class MatchKind { Exact, Prefix, WordPrefix, Contains }

    /**
     * 一条命中。
     *
     * [text] 是**实际被匹配到的那个字符串**（域名是归一之后的），
     * [range] 是它里面被命中的区间。两者一起交给界面层做高亮，
     * 免得搜索页再算一遍匹配——算两遍就会有两套规则，
     * 迟早出现「高亮的位置和排在前面的理由对不上」。
     */
    data class Hit(
        val entry: VaultEntry,
        val field: Field,
        val match: MatchKind,
        val text: String,
        val range: IntRange,
    ) {
        val score: Int get() = scoreOf(this.field, match)
    }

    /**
     * 打分。**匹配得越完整越靠前，同等完整度下名称 > 账号 > 网址 > 分类。**
     *
     * 字段权重只有个位数，纯粹是同档内的决胜局：
     * 「账号完全等于关键词」应该排在「名称里碰巧含有关键词」前面，
     * 因为前者几乎不可能是巧合，后者经常是。
     */
    private fun scoreOf(field: Field, match: MatchKind): Int {
        val m = when (match) {
            MatchKind.Exact -> 400
            MatchKind.Prefix -> 200
            MatchKind.WordPrefix -> 120
            MatchKind.Contains -> 40
        }
        val f = when (field) {
            Field.Name -> 4
            Field.Username -> 3
            Field.Domain -> 2
            Field.Category -> 1
        }
        return m + f
    }

    /**
     * 搜索。一个条目最多出一行——命中多个字段时只保留最好的那次。
     *
     * 关键词整体当一个串处理，不按空格切词：条目名里本来就带空格
     * （「Google 广告」「公司 VPN」），切词之后要么得决定是 AND 还是 OR，
     * 要么得在高亮时处理多段区间，收益远不及复杂度。真有人需要再说。
     */
    fun search(entries: List<VaultEntry>, rawQuery: String, limit: Int = 200): List<Hit> {
        val q = normalizeQuery(rawQuery)
        if (q.isEmpty()) return emptyList()

        val hits = ArrayList<Hit>(minOf(entries.size, limit))
        for (e in entries) {
            bestHit(e, q)?.let { hits += it }
        }
        hits.sortWith(Comparator { a, b -> compareHits(a, b) })
        return if (hits.size > limit) hits.subList(0, limit).toList() else hits
    }

    /** 关键词归一：去首尾空白 + 转小写。中文不受影响。 */
    fun normalizeQuery(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    private fun bestHit(entry: VaultEntry, q: String): Hit? {
        var best: Hit? = null

        fun consider(field: Field, text: String) {
            if (text.isEmpty()) return
            val m = matchOf(text, q) ?: return
            val hit = Hit(entry, field, m.first, text, m.second)
            if (best == null || hit.score > best!!.score) best = hit
        }

        consider(Field.Name, entry.name)
        consider(Field.Username, entry.username)
        for (d in entry.domains) consider(Field.Domain, normalizeDomain(d))
        consider(Field.Category, entry.category)

        return best
    }

    /**
     * 单个字段的匹配。返回「匹配档位 + 命中区间」，不匹配返回 null。
     *
     * 区间是**在原始 text 上的下标**：我们只做 lowercase，不做长度会变的归一
     * （比如 NFKC），所以下标对得上。这条一旦破了，高亮就会错位。
     */
    private fun matchOf(text: String, q: String): Pair<MatchKind, IntRange>? {
        val t = text.lowercase(Locale.ROOT)
        if (t.length != text.length) {
            // 极少数字符转小写后长度会变（如 İ）。宁可退化成「不高亮」，
            // 也不要给出一个错位的区间。
            val i = t.indexOf(q)
            return if (i < 0) null else MatchKind.Contains to IntRange.EMPTY
        }
        if (t == q) return MatchKind.Exact to 0..(q.length - 1)
        val i = t.indexOf(q)
        if (i < 0) return null
        val range = i..(i + q.length - 1)
        return when {
            i == 0 -> MatchKind.Prefix to range
            isWordStart(t, i) -> MatchKind.WordPrefix to range
            else -> MatchKind.Contains to range
        }
    }

    /**
     * 词首判定只认分隔符，不认「中文每个字都是一个词」。
     *
     * 如果把每个汉字都当词首，中文的 Contains 就永远升格成 WordPrefix，
     * 于是同一句「关键词出现在中间」的事实，中文条目排 120 分、英文条目排 40 分。
     * 一个库里中英文混着放的用户会看到毫无道理的顺序。
     */
    private fun isWordStart(t: String, i: Int): Boolean = i > 0 && t[i - 1] in DELIMITERS

    private val DELIMITERS: Set<Char> =
        " \t\n\r-_./\\@:|,;()[]{}+#&?=*'\"！，。、；：（）【】《》·—".toSet()

    private fun compareHits(a: Hit, b: Hit): Int {
        if (a.score != b.score) return b.score - a.score
        // 同分时收藏的排前面：用户亲手标过「常用」，那就是他给的排序信号
        if (a.entry.favorite != b.entry.favorite) return if (a.entry.favorite) -1 else 1
        return BY_NAME.compare(a.entry, b.entry)
    }

    /* ══════════════════════════ 域名归一 ══════════════════════════ */

    /**
     * 把用户随手粘进来的网址收敛成一个可比对的主机名。
     *
     * `HTTPS://user:pw@Example.com:8443/login?next=%2F` → `example.com`
     *
     * 安卓包名（`com.tencent.mm`）原样保留：它没有 scheme、没有斜杠、没有冒号，
     * 走完这几步不会被改坏，而 M4 自动填充要靠它匹配原生 App。
     *
     * ── 刻意**只做语法上的剥离**，一个子域名都不动 ──
     *
     * `www.` 不剥，`mail.google.com` 也不会被收敛成 `google.com`。
     * 剥 `www.` 看起来无害、业界也都这么干，但它是「哪些子域名算同一个站」
     * 这个滑坡的第一步，而那件事必须靠公共后缀表认真做——它属于 M4 的
     * 域名归属校验（AutoSpill 那一类攻击就是从这儿进来的）。
     * 在搜索里图省事先剥一层，将来两边规则对不上，
     * 就会出现「搜出来是这一条，填进去是另一条」。
     *
     * 不剥也不影响搜索手感：`www.example.com` 里的 `example` 紧跟在分隔符
     * `.` 后面，本来就会被判成词首命中（[MatchKind.WordPrefix]）。
     */
    fun normalizeDomain(raw: String): String {
        var s = raw.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return ""

        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)

        // 先切掉路径 / 查询 / 锚点。必须在处理 userinfo 之前做——
        // 路径里完全可能带 '@'（`example.com/mail@x`），
        // 顺序反了就会把整个主机名切没。
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')

        // user:pass@host 形式
        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)

        // 端口。IPv6 字面量带一堆冒号，不动它。
        if (!s.startsWith("[")) s = s.substringBefore(':')

        return s.trim('.')
    }

    /* ══════════════════════════ 排序基础设施 ══════════════════════════ */

    /**
     * 中文按拼音排，不按码点排。
     *
     * 用 `String.compareTo` 排的是 Unicode 码点，于是「北京银行」会排在
     * 「安居客」前面（北 U+5317 < 安 U+5B89）——用户看着这份列表，
     * 完全说不出它到底按什么排的，也就没法预测想找的那条在哪儿。
     * [Collator] 在 zh 区域下按拼音：an < bei < wei < zhi。
     *
     * 中英混排时拉丁字母整段排在汉字前面（CLDR 的 zh 排序规则如此），
     * 这一点不去动它：真去插手就得自己维护一张拼音表，
     * 而那正是 [sections] 里说的、不划算的那件事。
     *
     * `Collator` 实例不是线程安全的，所以放 ThreadLocal——
     * 每次调用现 new 一个的开销比这大得多。
     */
    private val COLLATOR: ThreadLocal<Collator> =
        ThreadLocal.withInitial { Collator.getInstance(Locale.CHINA) }

    private fun compareName(a: String, b: String): Int = COLLATOR.get().compare(a, b)

    /**
     * 名称排序器，对外那一份。
     *
     * M4 的自动填充候选在同分时也要按名称排，而**必须和列表页是同一个顺序**：
     * 两处各排各的，用户会看到「填充条上第一条」和「列表里第一条」不是同一条，
     * 而这两个地方讲的是同一批数据。这就是决策㉝ 那句「不许各写各的」——
     * 它管的不只是域名归一，凡是「同一批数据在两个地方呈现」的规则都适用。
     */
    val NAME_ORDER: Comparator<VaultEntry> get() = BY_NAME

    /** 名称相同时用 id 兜底，保证排序结果稳定——否则列表会在重组时莫名跳动。 */
    private val BY_NAME: Comparator<VaultEntry> = Comparator { a, b ->
        val c = compareName(a.name, b.name)
        if (c != 0) c else a.id.compareTo(b.id)
    }
}
