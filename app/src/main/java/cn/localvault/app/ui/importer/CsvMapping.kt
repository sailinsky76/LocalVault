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
 * CSV 导入的第三层：**一张表 → 哪一列是什么**。
 *
 * 上一层是 [CsvParser]（文本 → 表），下一层是行 → `VaultEntry` 的转换与判重
 * （M5-2a-2②），页面是 M5-2b。**这个文件没有一行 `android.*`，也没有一行 Compose。**
 *
 * ───────────── 这一层为什么单独存在 ─────────────
 *
 * 因为「哪一列是密码」这个问题，**猜错了不会报错**。
 *
 * Bitwarden 导出的表里同时有 `login_password` 和 `login_totp`；
 * Chrome 的表里 `name` 装的是域名不是站点名；LastPass 把备注叫 `extra`、
 * 把分类叫 `grouping`；1Password 有一列 `Password Hint`（密码提示），
 * 一台按「列名里带 password 就是密码」写的映射器会把**密码提示**当成密码导进去，
 * 然后用户的真密码丢了、提示语成了他以为的密码。这一路上全程没有一个异常。
 *
 * 所以这里的做法是：**先按整表精确匹配，再做受限的宽松匹配，
 * 并且给宽松匹配配一张排除表**（[LOOSE_EXCLUDE]）——「提示 / hint / 强度 /
 * 修改时间」这类词一旦出现在列名里，这一列就永远不参与自动映射，
 * 宁可让用户在 M5-2b 那一页手点一下。
 *
 * ───────────── 自动映射不是终点 ─────────────
 *
 * [plan] 的结果一定会摆到用户面前（M5-2b 的列映射预览），他可以改：
 * [Plan.withRole] 是那一页每一次改动背后的唯一入口，改动的连带后果
 * （原来占着这个角色的那一列要自动让位）在这里算，不在界面上算。
 *
 * ───────────── 敏感性 ─────────────
 *
 * 注意一件不直观的事：**[Plan.header] 有可能装着密码。**
 * 有些导出（Firefox 的老版本、用户自己用脚本拼的表）没有表头行，
 * 那时 [CsvParser] 交上来的「表头」其实是第一条数据。这种情况由
 * [Note.HeaderLooksLikeData] 认出来并告诉用户，但在认出来之前，
 * 那一行已经在内存里了——所以 [Plan.toString] **不吐 header**，只报形状。
 * 同 [CsvText.PLAINTEXT_NOTE] 和 [CsvParser] 的做法。
 */
object CsvMapping {

    /* ══════════════════════════ 角色 ══════════════════════════ */

    /**
     * 一列可以扮演的角色。**没有映射到任何角色的列不会被导入**，
     * 这是刻意的：把不认识的列一股脑塞进备注，会把用户在别处的
     * 「密码修改时间」「文件夹 GUID」变成他保险库里的垃圾，而且删不干净。
     *
     * [Kind] 和 [Favorite] 不是条目字段，是**行的元信息**，
     * 下一步（行 → `VaultEntry`）要用：Bitwarden 的表里混着安全笔记，
     * 那些行没有密码，得靠 `type` 那一列认出来，不能当成「密码丢了」来报警。
     */
    enum class Role(val label: String) {
        Name("名称"),
        Username("账号"),
        Password("密码"),
        Url("网址"),
        Notes("备注"),
        Category("分类"),
        Totp("动态验证码密钥"),
        Favorite("常用标记"),
        Kind("条目类型"),
        ;

        /** 界面上那一行的副标题，说清这一列会变成条目上的什么。 */
        val hint: String
            get() = when (this) {
                Name -> "列表里显示的名字"
                Username -> "用户名 / 手机号 / 邮箱"
                Password -> "登录密码"
                Url -> "网址或安卓包名，将来自动填充靠它匹配"
                Notes -> "原样搬进条目的备注"
                Category -> "对应这里的分类"
                Totp -> "动态验证码密钥，本版本先存着不用"
                Favorite -> "值为真的行会被标成常用"
                Kind -> "区分登录条目和安全笔记，不写进条目"
            }
    }

    /** 认出来的导出格式。只用来在页面上说一句「看起来是 X 导出的」，不改变任何行为。 */
    enum class Dialect(val label: String) {
        OnePassword("1Password"),
        Bitwarden("Bitwarden"),
        Chrome("Chrome / Edge"),
        Firefox("Firefox"),
        LastPass("LastPass"),
        KeePass("KeePass"),
        Chinese("中文列名"),
        Unknown("未知格式"),
    }

    /* ══════════════════════════ 记账 ══════════════════════════ */

    /** 值得摆到用户面前、但不拦着他继续的事。写法同 [CsvParser.Anomaly]：发生了什么 + 怎么处理的。 */
    enum class Note(val text: String) {
        HeaderLooksLikeData(
            "这份文件的第一行看起来不像列名，像是一条数据（里面有网址或者账号的样子）。" +
                "多半是导出时没有带表头——请在下面手动指定每一列是什么，" +
                "并且注意第一行会被当成列名而**不会**被导入。"
        ),
        NameFromUrl(
            "没有认出「名称」列，导入时会用网址当名字。导完之后可以逐条改。"
        ),
        DuplicateRole(
            "有不止一列被认成了同一样东西，只用了靠前的那一列，靠后的先留空。" +
                "如果认反了，在下面把它改过来。"
        ),
        UnmappedColumns(
            "有几列没有认出来，它们不会被导入。这是有意的：把不认识的列塞进备注" +
                "只会在你的保险库里留下一堆清不掉的杂物。需要哪一列就在下面指过去。"
        ),
        TotpFound(
            "认出了动态验证码密钥那一列。本版本还不会算验证码，但密钥会原样存下来，" +
                "将来这个功能做出来时不用重新导一次。"
        ),
    }

    /* ══════════════════════════ 映射方案 ══════════════════════════ */

    /**
     * 一份列映射方案。[assign] 与表头**逐列对齐**，`null` 表示这一列不导入。
     *
     * 这是个不可变对象，[withRole] 返回新的一份——M5-2b 那一页每点一下就换一份，
     * 于是「撤销」和「恢复自动识别」都不用另写逻辑。
     */
    class Plan internal constructor(
        val header: List<String>,
        val assign: List<Role?>,
        val dialect: Dialect,
        val notes: Set<Note>,
        /** 第一行看着像数据而不像列名。真为 true 时 [assign] 一定是全空的。 */
        val headerIsData: Boolean,
    ) {
        val width: Int get() = header.size

        /** 某个角色落在哪一列，没有就是 null。同一个角色最多只会占一列。 */
        fun columnOf(role: Role): Int? = assign.indexOf(role).takeIf { it >= 0 }

        fun roleOf(column: Int): Role? = assign.getOrNull(column)

        /** 没有指派角色的列号。 */
        fun unmapped(): List<Int> = assign.indices.filter { assign[it] == null }

        /** 按枚举声明顺序输出，与识别时的先后无关——这样界面上的措辞是稳定的。 */
        fun noteTexts(): List<String> = Note.entries.filter { it in notes }.map { it.text }

        /**
         * **拦着不让导**的事。空表示可以导。
         *
         * 只有两条，都是「导进去也没有意义」而不是「不好看」：
         * 没有密码列，导进来的是一堆空壳；名称和网址都没有，
         * 导进来的条目在列表里连个能认的字都没有，用户找不回来也删不干净。
         */
        fun blockers(): List<String> {
            val out = ArrayList<String>(2)
            if (columnOf(Role.Password) == null) {
                out += "还没有指定哪一列是密码。没有密码列的话，导进来的会是一堆只有名字的空条目——" +
                    "如果这份文件里确实没有密码，那它多半不是密码导出文件。"
            }
            if (columnOf(Role.Name) == null && columnOf(Role.Url) == null) {
                out += "还没有指定哪一列是名称。名称和网址至少要有一个，" +
                    "否则导进来的条目在列表里没有任何能认出来的字。"
            }
            return out
        }

        fun ready(): Boolean = blockers().isEmpty()

        /**
         * 把某一列改成某个角色（`null` = 不导入），返回新的一份方案。
         *
         * **连带后果在这里算**：一个角色只能占一列，所以原来占着它的那一列
         * 会被自动置空。让界面自己去清会漏——那种漏法的表现是
         * 「密码列点到别处了，可原来那列还标着密码」，然后两列都往密码里写，
         * 后写的赢，而用户在预览上看到的是先写的那一列。
         *
         * 越界的列号原样返回，不抛异常：这个入口接的是界面事件，
         * 崩在这里没有任何好处。
         */
        fun withRole(column: Int, role: Role?): Plan {
            if (column !in assign.indices) return this
            if (assign[column] == role) return this
            val next = assign.toMutableList()
            if (role != null) {
                val taken = next.indexOf(role)
                if (taken >= 0) next[taken] = null
            }
            next[column] = role
            return Plan(header, next, dialect, recount(next), headerIsData)
        }

        /** 全部清空，从头手点。 */
        fun cleared(): Plan {
            val empty: List<Role?> = List(width) { null }
            return Plan(header, empty, dialect, recount(empty), headerIsData)
        }

        /**
         * 记账在改动之后要重算：用户把重复的那一列改掉之后，
         * 「有不止一列被认成同一样东西」这句话就该消失。
         * 只有 [Note.HeaderLooksLikeData] 是关于文件本身的事实，改映射不会让它变。
         */
        private fun recount(a: List<Role?>): Set<Note> {
            val out = LinkedHashSet<Note>()
            if (headerIsData) out += Note.HeaderLooksLikeData
            if (a.contains(Role.Name).not() && a.contains(Role.Url)) out += Note.NameFromUrl
            if (a.contains(Role.Totp)) out += Note.TotpFound
            if (a.any { it == null }) out += Note.UnmappedColumns
            return out
        }

        /** **不吐 header**：没有表头的文件里，那一行是真实数据。理由见文件头。 */
        override fun toString(): String =
            "CsvPlan($width cols, ${assign.count { it != null }} mapped, ${dialect.name}" +
                (if (headerIsData) ", headerIsData" else "") + ")"
    }

    /* ══════════════════════════ 自动识别 ══════════════════════════ */

    /**
     * 看一眼表头，给出一份方案。**这只是一份草稿**，一定会摆给用户看。
     *
     * 顺序是：归一列名 → 精确表 → 认格式 → 受限的宽松匹配 → 记账。
     */
    fun plan(header: List<String>): Plan {
        val keys = header.map { normalizeName(it) }
        val exact = keys.map { EXACT[it] }
        val dialect = detectDialect(keys, exact)

        if (exact.all { it == null } && looksLikeData(header)) {
            // 一个列名都没认出来，而这一行本身长得像数据——那它多半就是数据。
            // 此时不做任何猜测：猜错一次的代价是用户的第一条记录被当成列名扔掉。
            val empty = List(header.size) { null as Role? }
            return Plan(header, empty, Dialect.Unknown, setOf(Note.HeaderLooksLikeData, Note.UnmappedColumns), true)
        }

        val assign = arrayOfNulls<Role>(header.size)
        var duplicate = false

        fun place(i: Int, role: Role) {
            if (assign[i] != null) return
            if (assign.contains(role)) { duplicate = true; return }
            assign[i] = role
        }

        // 第一遍：精确匹配。整列名一字不差地对上才算。
        exact.forEachIndexed { i, r -> if (r != null) place(i, r) }

        // 第二遍：宽松匹配。只对还没定下来的列做，且列名里带排除词的一概不碰。
        keys.forEachIndexed { i, k ->
            if (assign[i] != null || k.isEmpty()) return@forEachIndexed
            if (LOOSE_EXCLUDE.any { k.contains(it) }) return@forEachIndexed
            val role = LOOSE.firstOrNull { (frag, _) -> k.contains(frag) }?.second ?: return@forEachIndexed
            place(i, role)
        }

        val list = assign.toList()
        val notes = LinkedHashSet<Note>()
        if (duplicate) notes += Note.DuplicateRole
        if (!list.contains(Role.Name) && list.contains(Role.Url)) notes += Note.NameFromUrl
        if (list.contains(Role.Totp)) notes += Note.TotpFound
        if (list.any { it == null }) notes += Note.UnmappedColumns

        return Plan(header, list, dialect, notes, false)
    }

    /**
     * 列名归一：小写、去掉一切不是字母 / 数字 / 汉字的东西。
     *
     * 于是 `Login URI`、`login_uri`、`login-uri` 三种写法（三家导出各写一种）
     * 归到同一个键上，不用在表里列三遍。
     */
    fun normalizeName(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    /**
     * 这一行看着像数据还是像列名。
     *
     * 判据都是**列名里几乎不可能出现**的东西：协议头、`@`、很长的一段、
     * 纯数字。任何一格中了就算。宁可漏判（那时用户会在预览里看见第一行不见了，
     * 那是看得见的错），也不要误判（把真表头当数据导进去，会多出一条名叫
     * 「用户名」的垃圾条目，用户还以为自己导对了）。
     */
    internal fun looksLikeData(cells: List<String>): Boolean = cells.any { raw ->
        val s = raw.trim()
        when {
            s.isEmpty() -> false
            s.contains("://") -> true
            s.contains('@') -> true
            s.length > 40 -> true
            s.length >= 4 && s.all { it.isDigit() } -> true
            else -> false
        }
    }

    private fun detectDialect(keys: List<String>, exact: List<Role?>): Dialect {
        val set = keys.toHashSet()
        return when {
            "loginuri" in set || ("loginusername" in set && "loginpassword" in set) -> Dialect.Bitwarden
            "formactionorigin" in set || "httprealm" in set -> Dialect.Firefox
            "grouping" in set && "extra" in set -> Dialect.LastPass
            "otpauth" in set && "title" in set -> Dialect.OnePassword
            "group" in set && "title" in set -> Dialect.KeePass
            keys.any { k -> k.any { it.code in 0x4E00..0x9FFF } } && exact.any { it != null } -> Dialect.Chinese
            "name" in set && "url" in set && "username" in set && "password" in set -> Dialect.Chrome
            else -> Dialect.Unknown
        }
    }

    /* ══════════════════════════ 词表 ══════════════════════════ */

    /**
     * 精确表。键是 [normalizeName] 之后的列名。
     *
     * 每一条都来自一份真实的导出，注释写明出处——将来有人想删掉某一条时，
     * 至少知道删的是谁家的用户。
     */
    private val EXACT: Map<String, Role> = buildMap {
        // ── 名称 ──
        put("title", Role.Name)          // 1Password / KeePass / Safari
        put("name", Role.Name)           // Bitwarden / Chrome / LastPass
        put("displayname", Role.Name)
        put("名称", Role.Name)
        put("标题", Role.Name)
        put("网站名称", Role.Name)
        put("应用名称", Role.Name)

        // ── 账号 ──
        put("username", Role.Username)   // 到处都是
        put("loginusername", Role.Username) // Bitwarden
        put("user", Role.Username)
        put("login", Role.Username)
        put("email", Role.Username)
        put("emailaddress", Role.Username)
        put("账号", Role.Username)
        put("帐号", Role.Username)        // 「帐」是另一个字，两个都得收
        put("账户", Role.Username)
        put("用户名", Role.Username)
        put("登录名", Role.Username)
        put("邮箱", Role.Username)
        put("手机号", Role.Username)

        // ── 密码 ──
        put("password", Role.Password)
        put("loginpassword", Role.Password) // Bitwarden
        put("pass", Role.Password)
        put("pwd", Role.Password)
        put("密码", Role.Password)
        put("口令", Role.Password)

        // ── 网址 ──
        put("url", Role.Url)
        put("urls", Role.Url)
        put("uri", Role.Url)
        put("loginuri", Role.Url)        // Bitwarden
        put("website", Role.Url)
        put("weburl", Role.Url)
        put("site", Role.Url)
        put("hostname", Role.Url)        // Firefox 新版
        put("网址", Role.Url)
        put("网站", Role.Url)
        put("链接", Role.Url)
        put("地址", Role.Url)
        put("域名", Role.Url)

        // ── 备注 ──
        put("notes", Role.Notes)
        put("note", Role.Notes)          // Chrome 新版
        put("extra", Role.Notes)         // LastPass
        put("comment", Role.Notes)
        put("comments", Role.Notes)
        put("备注", Role.Notes)
        put("说明", Role.Notes)
        put("注释", Role.Notes)

        // ── 分类 ──
        put("folder", Role.Category)     // Bitwarden
        put("group", Role.Category)      // KeePass
        put("grouping", Role.Category)   // LastPass
        put("category", Role.Category)
        put("tags", Role.Category)       // 1Password
        put("collection", Role.Category)
        put("分类", Role.Category)
        put("分组", Role.Category)
        put("文件夹", Role.Category)
        put("标签", Role.Category)
        put("类别", Role.Category)

        // ── 动态验证码 ──
        put("otpauth", Role.Totp)        // 1Password
        put("logintotp", Role.Totp)      // Bitwarden
        put("totp", Role.Totp)
        put("totpsecret", Role.Totp)
        put("onetimepassword", Role.Totp)
        put("动态密码", Role.Totp)
        put("验证码密钥", Role.Totp)

        // ── 常用 ──
        put("favorite", Role.Favorite)   // Bitwarden / 1Password
        put("favourite", Role.Favorite)
        put("fav", Role.Favorite)        // LastPass
        put("starred", Role.Favorite)
        put("收藏", Role.Favorite)
        put("常用", Role.Favorite)

        // ── 行类型 ──
        put("type", Role.Kind)           // Bitwarden：login / note / card / identity
        put("itemtype", Role.Kind)
        put("类型", Role.Kind)
    }

    /**
     * 宽松匹配的片段表，按先后顺序试。**顺序有讲究**：
     * `password` 必须排在 `pass` 之前，否则 `passwordhint` 那种列名
     * （虽然已经被 [LOOSE_EXCLUDE] 挡掉了）和 `passphrase` 会走岔。
     */
    private val LOOSE: List<Pair<String, Role>> = listOf(
        "password" to Role.Password,
        "密码" to Role.Password,
        "口令" to Role.Password,
        "username" to Role.Username,
        "用户名" to Role.Username,
        "账号" to Role.Username,
        "帐号" to Role.Username,
        "邮箱" to Role.Username,
        "email" to Role.Username,
        "totp" to Role.Totp,
        "otp" to Role.Totp,
        "url" to Role.Url,
        "uri" to Role.Url,
        "网址" to Role.Url,
        "网站" to Role.Url,
        "title" to Role.Name,
        "name" to Role.Name,
        "名称" to Role.Name,
        "标题" to Role.Name,
        "note" to Role.Notes,
        "备注" to Role.Notes,
        "folder" to Role.Category,
        "分类" to Role.Category,
        "分组" to Role.Category,
    )

    /**
     * 带上这些词的列名**永远不参与宽松匹配**。
     *
     * 这张表是这个文件里最重要的东西。少一条 `hint`，
     * 1Password 的 `Password Hint`（密码提示）就会被当成密码导进去——
     * 用户的真密码丢了，而那条「提示语」在保险库里长得和一条正常密码一模一样，
     * 事后没有任何办法分辨。少一条 `changed`，Firefox 的
     * `timePasswordChanged` 会被当成密码，导进去的是一串时间戳。
     *
     * 排除的代价只是用户在 M5-2b 那一页多点一下，而且点之前他看得见列名。
     */
    private val LOOSE_EXCLUDE: List<String> = listOf(
        "hint", "提示",
        "reprompt", "再次", "确认", "confirm",
        "strength", "强度", "score", "评分",
        "history", "历史",
        "changed", "modified", "created", "updated", "expire",
        "time", "date", "日期", "时间",
        "guid", "uuid",
        "count", "次数",
        "reused", "重复",
        "field", "fields",  // Bitwarden 的自定义字段列，结构完全不同，不能硬塞
    )

    /* ══════════════════════════ 文案 ══════════════════════════ */

    /** 页面顶上那一句。认出格式时说得具体一点，能省掉用户一次逐列核对。 */
    fun summary(plan: Plan): String {
        val mapped = plan.assign.count { it != null }
        return when {
            plan.headerIsData ->
                "这份文件的 ${plan.width} 列都没有认出来，第一行看着像数据。请逐列指定。"
            plan.dialect == Dialect.Unknown ->
                "认出了 ${plan.width} 列里的 $mapped 列。下面逐列核对一遍，不对就改。"
            else ->
                "看起来是${plan.dialect.label}导出的，${plan.width} 列里认出了 $mapped 列。" +
                    "下面逐列核对一遍，不对就改。"
        }
    }
}
