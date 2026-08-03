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

package cn.localvault.app.ui.autofill

import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.list.VaultIndex

/**
 * 用户自己挑那一条时，**挑选页上到底摆什么**。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 页面长什么样是 M4-2b-2 的事；这里只回答四个问题：
 * 摆哪几条、每一条旁边标什么、点下去之前必须先说哪几句话、以及**最后往哪几个框写**。
 *
 * ── 为什么会有这一页 ──
 *
 * 决策(160)：「绝不自动建议」和「不许手动挑」是两件事。`UntrustedHost` /
 * `NoEvidence` / `WrongKind` 那三档条目一条都不会自动出现在填充条上，
 * 但用户手上很可能正有那一条——禁止他手动挑，等于替他决定他自己那份数据能去哪儿，
 * 而这个应用从头到尾不做这种事（弱口令给二次确认而不是拒绝，清空库给两道门槛而不是不给）。
 * 决策(181) 又规定「一条都没匹配上时出的是空填充条 + 一条搜索入口」，
 * 那条入口的落点就是这一页。M4-2a-2 里 `AutofillResponses` 眼下在装不出
 * `Dataset` 时返回 null，正是因为这一页还不存在。
 *
 * ── 自动那一侧和手动这一侧，闸门不是同一道 ──
 *
 * 这是整个 M4-2b 里唯一真正要紧的一句话，值得写在文件头上。
 *
 * 自动那一侧（[AutofillOffer.writesFor]）的闸门是**归属判断**：同屏每一组框各判一次，
 * 判不过的一组一个字都不写。手动这一侧，那道闸门被用户**主动越过了**——
 * 他挑的这一条对这一屏可能压根不够格，不然它早就自动出现了，他也不必来这一页。
 * 于是有两条错路，两条都得躲开：
 *
 *   · 照 [AutofillOffer.writesFor] 那样一组一组判——判不过的组都不写，
 *     而用户挑的这一条对主表单本来就判不过，结果是**一个字都写不出去**：
 *     这一页点下去什么也没发生，而屏幕上不会有任何解释。
 *   · 反过来「既然用户已经同意了，那就照 `plan.forms` 全写一遍」——
 *     那正是 AutoSpill 那条路（见 [Origin] 文件头）。同一屏上完全可能一组是
 *     `example.com` 的 iframe、另一组是承载它的应用自己的原生框，
 *     用户看见的、点头的是前一组，密码却顺着后一组流进了别人的进程。
 *
 * 正解是**换一道闸门，而不是把闸门拆掉**：手动挑时**只往主表单那一组写**（[writes]）。
 * 主表单就是 [FillPlan.pick] 挑出来的那一组——优先是光标所在的那一组，
 * 也就是用户此刻正看着的那几个框。越过归属这件事只越过一次，
 * 而且只在他看得见的那一处越过（决策(187)）。
 *
 * ── 这一页上同样没有密码 ──
 *
 * [Row] 里根本没有能放它的字段，同 [AutofillOffer.Item]。这一页是我们自己的
 * `FLAG_SECURE` 界面，显示密码技术上做得到——但它没有用途：
 * 用户来这一页是为了**填**，不是为了看。而这一页浮在别人的应用上面，
 * 他此刻很可能正站在一个不方便掏出密码的地方。
 */
object AutofillPick {

    /* ══════════════════════════ 摆什么 ══════════════════════════ */

    /**
     * 挑选页上的一行。**没有密码字段**，也不抱着 [VaultEntry]。
     *
     * 不抱着条目对象是有意的：`VaultEntry` 是 `data class`，它的自动 `toString`
     * 会把明文密码原样打出来（同 `AutofillMatch.Candidate` 那段注释）。
     * 界面拿 [entryId] 回库里取那一条，交给 [writes] 的也是那一条。
     */
    class Row internal constructor(
        val entryId: String,
        /** 第一行：名称，空名称退回账号，两样都空时是 [AutofillOffer.NO_NAME]。 */
        val label: String,
        /** 第二行：账号，没有账号时是 [AutofillOffer.NO_USERNAME]。 */
        val sublabel: String,
        /** 命中的那一行网址**原文**（洗过）。一行都没对上时为 null。 */
        val matchedDomain: String?,
        val verdict: DomainMatch.Verdict,
        /**
         * 这一条**存了网址，但存的是别的站**。
         *
         * 见 [warningsFor]：`Verdict.None` 有两种成因，只有这一种要说话。
         */
        val storedElsewhere: Boolean,
        /** 这一条在这一屏上写得出东西。写不出的一律不许提交（决策(174)）。 */
        val fillable: Boolean,
    ) {
        /** 够格自动出现在填充条上的那两档。界面可以据此给个不打眼的标记。 */
        val auto: Boolean get() = verdict.canAutoFill

        /** 点下去之前必须先说一句。 */
        val needsWarning: Boolean get() = verdict.needsWarning || storedElsewhere

        /** 同决策(144)：只报形状，一个字的内容都不吐。 */
        override fun toString(): String = "Row(${verdict.name}, fillable=$fillable)"
    }

    /**
     * 刚进这一页、还没打任何关键词时摆的东西。
     *
     * ── 为什么不把整库摊开 ──
     *
     * 摊开最省事，而且「反正是我们自己的 `FLAG_SECURE` 页面」这个理由听着也成立。
     * 不摊开是三件事加起来的结果（决策(189)）：
     *   · 他来这一页是为了找**一条特定的**，搜索比滚动快；
     *   · 一屏浮在别人应用上面的完整资产清单，肩窥换来的只是省下两次打字；
     *   · 真正相关的那几条会被淹在几百行里，而它们本来是这一页的全部意义。
     *
     * **但过滤只发生在这份默认清单上，[search] 一律不过滤。**
     * 不摊开 ≠ 搜不到——后者就成了「替用户决定他自己那条数据能去哪儿」（决策(160)）。
     */
    class Listing internal constructor(
        /** 这个站够格自动填的那几条（[DomainMatch.Verdict.canAutoFill]），**不截断**。 */
        val forThisSite: List<Row>,
        /** 最近改动过的几条，已经排除上面那些。 */
        val recent: List<Row>,
        /** 库是空的 / 这个站一条都没对上。一切正常时是 null（决策(95)）。 */
        val note: String?,
        /** 这份清单没摆全。界面据此摆一句 [PARTIAL_NOTE]，免得用户以为条目丢了。 */
        val partial: Boolean,
    ) {
        val isEmpty: Boolean get() = forThisSite.isEmpty() && recent.isEmpty()

        override fun toString(): String =
            "Listing(site=${forThisSite.size}, recent=${recent.size}, partial=$partial)"
    }

    /**
     * 用户点中一条之后、按下确认之前的那一屏。
     *
     * [handOver] 那一行**不许省**，[warnings] 里的每一句也不许折叠成「查看详情」：
     * 手动挑这一下之所以被允许，靠的就是「自动的那一下用户可能没看清，
     * 手动的那一下他一定看清了」（决策(160)）。把话藏起来，这条前提就不成立了。
     */
    class Choice internal constructor(
        val row: Row,
        /** 「这些内容会交给 ⟨谁⟩」。见 [handOver]。 */
        val handOver: String,
        /** 必须先看的话。空清单说明这是够格自动填的那两档。 */
        val warnings: List<String>,
        /** 陈述句：兄弟域那一句、这一屏的 [FillPlan.kindNote]、浏览器那一档的说法。 */
        val notes: List<String>,
        /** 这一下会写哪几格——**只有格位，没有值**。 */
        val slots: List<FillPlan.Slot>,
        /** 不为 null 时不许提交，这句话就是理由。 */
        val blocked: String?,
    ) {
        val canFill: Boolean get() = blocked == null

        override fun toString(): String =
            "Choice(${row.verdict.name}, warnings=${warnings.size}, blocked=${blocked != null})"
    }

    /* ══════════════════════════ 整页该不该出现 ══════════════════════════ */

    /**
     * 这一页整个不该出现的两种情形。不为 null 时页面只摆这一句话，一条都不列。
     *
     * 两条和 [AutofillOffer.respond] 是同一对判断，**顺序也一样**：
     * 先问「这一屏有没有能填的框」，再问「是不是自己的界面」——
     * 前一问不需要知道库的任何事，也就不会因为回答它而泄露任何事（决策(180)）。
     *
     * 正常路径上这两条都到不了（能走到这一页，说明服务那边刚刚为这一屏出过响应）。
     * 留着是因为这一页的入口是一个 `IntentSender`，而那个东西一旦发出去就
     * **不由我们决定什么时候被点、被谁点、带着哪一屏的结构被点**。
     */
    fun refusal(plan: FillPlan.Plan, selfPackage: String): String? {
        val primary = plan.primary ?: return REFUSE_NO_FORM
        if (primary.origin.hostApp == selfPackage) return REFUSE_OWN_UI
        return null
    }

    /* ══════════════════════════ 一行 ══════════════════════════ */

    /** 把一条条目摆成这一页上的一行。 */
    fun row(entry: VaultEntry, plan: FillPlan.Plan, trust: HostTrust): Row {
        val origin = plan.primary?.origin
        val hit = if (origin == null) {
            DomainMatch.Hit(DomainMatch.Verdict.None, null)
        } else {
            DomainMatch.best(origin, entry.domains, trust)
        }
        return Row(
            entryId = entry.id,
            label = clean(AutofillOffer.labelOf(entry), MAX_LABEL)
                .ifEmpty { AutofillOffer.NO_NAME },
            sublabel = clean(entry.username, MAX_SUBLABEL)
                .ifEmpty { AutofillOffer.NO_USERNAME },
            matchedDomain = hit.matched?.let { clean(it, MAX_DOMAIN) }?.ifEmpty { null },
            verdict = hit.verdict,
            storedElsewhere = hit.verdict == DomainMatch.Verdict.None && hasAnyDomain(entry),
            fillable = writes(plan, entry).isNotEmpty(),
        )
    }

    /**
     * 「存了网址，但存的是别的站」——判的是**存过**，不是「存的那一行有没有意义」。
     *
     * 用 `normalizeDomain` 过一遍再问，是因为 `domains` 里可以躺着一行空白
     * （用户删干净了却留下了那一行）。那种条目和「一行都没存」是同一件事，
     * 不该为它摆一句「你存的是别的站」——那句话会让用户去翻一条根本没有网址的条目。
     */
    private fun hasAnyDomain(entry: VaultEntry): Boolean =
        entry.domains.any { VaultIndex.normalizeDomain(it).isNotEmpty() }

    /* ══════════════════════════ 默认清单 ══════════════════════════ */

    /**
     * 进来时摆的那两段。
     *
     * `forThisSite` 走的是 [AutofillMatch.suggest]，**同一个排序、同一套判断**
     * （精确档整体压过兄弟档 → 收藏 → 最近改动 → 名称），只是把
     * [AutofillMatch.MAX_SUGGESTIONS] 那个上限放开——那个 8 是**填充条的**上限
     * （系统只露出两三行），这一页是全屏，用户点进来的那一行写的正是「还有 12 条」。
     * 在这儿另写一套排序，就会出现「填充条上排第一的和这一页上排第一的不是同一条」，
     * 而没有任何一处能解释为什么。
     */
    fun listing(
        plan: FillPlan.Plan,
        entries: List<VaultEntry>,
        trust: HostTrust,
    ): Listing {
        val origin = plan.primary?.origin
        val site = if (origin == null) {
            emptyList()
        } else {
            AutofillMatch.suggest(origin, entries, trust, limit = Int.MAX_VALUE)
                .shown.map { row(it.entry, plan, trust) }
        }
        val taken = site.mapTo(HashSet(site.size)) { it.entryId }

        val rest = entries.filter { it.id !in taken }.sortedWith(RECENT_ORDER)
        val recent = rest.take(RECENT_LIMIT).map { row(it, plan, trust) }

        val note = when {
            entries.isEmpty() -> EMPTY_VAULT
            site.isEmpty() -> NO_MATCH
            else -> null
        }
        return Listing(site, recent, note, partial = rest.size > recent.size)
    }

    /**
     * 搜索。**复用 M3-3b 那套 [VaultIndex.search]，一个字都不重写。**
     *
     * 理由是决策㉜ 已经把「哪些字段可以被搜」钉死成一张白名单了
     * （名称 / 账号 / 网址 / 分类——**备注和密码不在里面**）。
     * 在这儿另起一套搜索等于把那张白名单复制一份，而复制出来的那一份
     * 迟早会把备注也搜进去——那正是用户拿来放密保答案和身份证号的地方。
     *
     * ── 不拿归属把结果重排 ──
     *
     * [VaultIndex] 给的顺序是「和你打的字最像的在前」。拿 [DomainMatch.Verdict]
     * 重排一遍，会把用户明确搜出来的那一条压到下面去——他打了那几个字，
     * 那几个字比我们的归属判断更能说明他要哪一条（决策(190)）。
     * 归属只决定**标注和警告**，不决定顺序。
     * 自动那一侧反过来（[AutofillMatch] 的排序以档位为首），因为那一侧没有关键词。
     */
    fun search(
        plan: FillPlan.Plan,
        entries: List<VaultEntry>,
        query: String,
        trust: HostTrust,
    ): List<Row> =
        VaultIndex.search(entries, query, limit = MAX_RESULTS).map { row(it.entry, plan, trust) }

    /* ══════════════════════════ 挑中之后 ══════════════════════════ */

    /**
     * 确认那一屏。
     *
     * @param appLabel 承载这一屏的应用**自称**的名字（`ApplicationInfo.loadLabel`），
     *   读不到时传 null。见 [handOver]，它在这儿会被洗一道。
     * @param browserLevel 这个承载应用落在哪一档（`AndroidHostTrust.level`）。
     *   原生框那一屏没有浏览器可谈，传 null。
     */
    fun choose(
        entry: VaultEntry,
        plan: FillPlan.Plan,
        trust: HostTrust,
        appLabel: String?,
        browserLevel: BrowserTrust.Level? = null,
    ): Choice {
        val form = plan.primary
        val r = row(entry, plan, trust)
        val slots = writes(plan, entry).map { it.slot }

        val notes = ArrayList<String>(3)
        if (form != null) {
            sameSiteNote(r, form.origin)?.let { notes += it }
            FillPlan.kindNote(form.kind)?.let { notes += it }
        }
        // 已核验 / 只认包名 那两句是陈述句，不是警告：它们说的是「我们核对到了哪一步」，
        // 而不是「你要小心」。Unknown 那一句归 warningsFor 处理。
        if (browserLevel != null && browserLevel != BrowserTrust.Level.Unknown) {
            notes += BrowserTrust.note(browserLevel)
        }

        return Choice(
            row = r,
            handOver = handOver(form?.origin, appLabel),
            warnings = warningsFor(r, browserLevel),
            notes = notes,
            slots = slots,
            blocked = when {
                form == null -> REFUSE_NO_FORM
                slots.isEmpty() -> BLOCKED_NOTHING_TO_FILL
                else -> null
            },
        )
    }

    /**
     * **手动挑那一下真正写出去的东西：只有主表单那一组。**
     *
     * 见文件头那一段。这里刻意**没有**调 [AutofillOffer.writesFor]，
     * 也刻意**没有**遍历 [FillPlan.Plan.forms]——两种写法各错在一头。
     *
     * 主表单里那几个 target 是 [FillPlan.of] 挑好的，所以这一行照样兑现了
     * 前面那些底线：新密码栏不在 target 里（决策(170)），
     * 分不出新旧的密码框一个都不在（决策(173)），值为空的那一格不写（决策(174)）。
     * **手动挑越过的只有归属那一道，不是全部。**
     */
    fun writes(plan: FillPlan.Plan, entry: VaultEntry): List<FillPlan.Write> {
        val form = plan.primary ?: return emptyList()
        return FillPlan.writes(form, entry)
    }

    /* ══════════════════════════ 话怎么说 ══════════════════════════ */

    /**
     * 「这些内容会交给 ⟨谁⟩」——这一页上最要紧的一行字。
     *
     * ── 应用名要洗，而且包名一定要一起写出来（决策(188)）──
     *
     * [appLabel] 是**那个应用自己声明的字符串**。一个仿冒应用完全可以把自己叫做
     * 「Chrome 浏览器」，也完全可以在名字里塞一个 `U+202E` 让它倒着画出来
     * （同决策(184)——而这一处比填充条更要紧：填充条上那三行是**用户自己的**数据，
     * 这一行是**被填对象提供的**数据，而它正是用户做决定时唯一看的那句话）。
     *
     * 所以两件事一起做：洗（复用 [AutofillRow.clean]，不写第二份）+
     * **永远把包名也写出来**。包名由系统分配、应用自己改不了，是这条链上最硬的事实
     * （决策(158)）。名字可以骗人，`com.example.free.wallpaper` 骗不了。
     *
     * 读不到名字时只写包名，**不写「未知应用」**——那四个字听起来像出了故障，
     * 而实际上包名已经把该说的都说了。
     */
    fun handOver(origin: Origin?, appLabel: String?): String {
        if (origin == null) return REFUSE_NO_FORM
        val who = identify(origin.hostApp, appLabel)
        return when (origin) {
            is Origin.App ->
                "这些内容会交给 $who，填进它自己的输入框里。"

            is Origin.Web ->
                "这些内容会交给 $who，填进它正在显示的 ${clean(origin.host, MAX_DOMAIN)} 页面里。"
        }
    }

    /**
     * 「⟨名字⟩（⟨包名⟩）」，或者名字读不出来时只有包名。
     *
     * **`internal` 而不是 `private`**：M4-3a 的 `AutofillSave.storedUnder` 要说同一句话
     * （「这一条会记在谁名下」和「这些内容会交给谁」是同一条规矩的两个方向）。
     * 抄一份过去的后果是——某天有人给这一句加一条新规矩而另一份没跟着改，
     * 于是同一个 App 里，填充那一页和保存那一页对同一个应用的称呼不一样，
     * 而没有任何一处能解释为什么（同 `UnlockHost` 从 `private` 放宽的理由）。
     */
    internal fun identify(packageName: String, appLabel: String?): String {
        val pkg = clean(packageName, MAX_PACKAGE)
        val label = appLabel?.let { clean(it, MAX_APP_LABEL) }.orEmpty()
        return if (label.isEmpty() || label == pkg) pkg else "$label（$pkg）"
    }

    /**
     * 点下去之前必须先说的那几句。空清单 = 这是够格自动填的那两档，一句废话都不说。
     *
     * ── 为什么有四句，而 [DomainMatch.Verdict.needsWarning] 只管三档 ──
     *
     * 因为 [DomainMatch.Verdict.None] 在自动那一侧永远不出现（自动只收前两档），
     * 而在手动这一侧它是**最常见的一档**——这一页的整个用途就是挑一条没自动出现的。
     * 而 `None` 有两种成因，代价差得很远（决策(191)）：
     *
     *   · 这一条一行网址都没存 → 很平常，谈不上「对不上」。这一页正是为它存在的，
     *     为它摆一句警告，用户下次就学会跳过所有小字了（同决策(95)）。
     *   · 这一条**存了网址，存的是别的站** → 极可能是他点错了行
     *     （两条名字相近的条目，或者一份导进来的 CSV 里挨着的两行）。
     *     这是唯一一处能拦住他的地方，而拦住的代价只是他多看一眼。
     *
     * [BrowserTrust.Level.Unknown] 那一句只在 [DomainMatch.Verdict.UntrustedHost]
     * **没有**出现时才补——两句说的是同一件事（承载这个网页的应用不是已知浏览器），
     * 摆两遍的后果是用户学会跳过这一整块（决策(95)）。
     */
    fun warningsFor(row: Row, browserLevel: BrowserTrust.Level? = null): List<String> {
        val out = ArrayList<String>(2)
        warning(row.verdict)?.let { out += it }
        if (row.storedElsewhere) out += STORED_ELSEWHERE
        if (
            browserLevel == BrowserTrust.Level.Unknown &&
            row.verdict != DomainMatch.Verdict.UntrustedHost
        ) {
            out += BrowserTrust.note(BrowserTrust.Level.Unknown)
        }
        return out
    }

    /**
     * 三档非自动各自那一句。决策(160)/(161)/(164) 欠的就是这三句话。
     *
     * 三句里没有「失败」「出错」「稍后重试」——它们描述的都不是故障，
     * 而是三种我们**故意**不自动出手的处境；每一句都要说清「我们知道什么、
     * 不知道什么」，然后把那一下留给用户自己按。
     */
    fun warning(verdict: DomainMatch.Verdict): String? = when (verdict) {
        DomainMatch.Verdict.UntrustedHost ->
            "网址对得上，但承载这个页面的不是我们认得的浏览器。" +
                "一个应用可以自己套一个 WebView，在里面显示一张一模一样的登录页——" +
                "那样填进去的密码，是它自己读得到的。"

        DomainMatch.Verdict.NoEvidence ->
            "这一条存的是网址，而你现在在一个应用里。" +
                "要证明这个应用和那个网址是一家，得去问那个域名（联网查一份声明文件），" +
                "而这个保险库没有网络权限，做不到——所以我们不知道它们是不是一家。"

        DomainMatch.Verdict.WrongKind ->
            "这一条存的是一个安卓应用的凭据，而你现在在一个网页里。" +
                "把应用的密码交给一张网页，正是把这类凭据骗出去的常用办法。"

        DomainMatch.Verdict.Exact, DomainMatch.Verdict.SameSite, DomainMatch.Verdict.None -> null
    }

    /**
     * 兄弟域那一句。**这一句不许省，也不许和精确档混在一起显示。**
     *
     * 决策(159) 的第二道兜底就是它：内置的公共后缀表不可能永远全，
     * 表里错一条的后果——「把两个不相干的站算成同一个站」——
     * 在这一行上能被用户一眼看见（他知道自己存的是哪个域名）。
     */
    fun sameSiteNote(row: Row, origin: Origin): String? {
        if (row.verdict != DomainMatch.Verdict.SameSite) return null
        val matched = row.matchedDomain ?: return null
        val host = (origin as? Origin.Web)?.host ?: return null
        return "你存的是 $matched，而这一屏是 ${clean(host, MAX_DOMAIN)}——" +
            "同一个网站底下的两个子域。"
    }

    /* ══════════════════════════ 洗与尺寸 ══════════════════════════ */

    /**
     * 复用填充条那一道洗（压成一行、剔控制字符与双向控制符、按码点截断），
     * **不写第二份**。
     *
     * 上限比填充条上宽——这一页是全屏、一行放得下更多字，而这一页的用途是
     * 让用户认出「是我那一条」并且看清「要交给谁」，截太早反而误事。
     * 但那三件事一件都不能少：这一页虽然是我们自己画的，
     * 里面的字仍然一个都不是我们写的（条目名称是用户的，应用名是**被填那个应用的**）。
     */
    private fun clean(raw: String, max: Int): String = AutofillRow.clean(raw, max)

    const val MAX_LABEL = 60
    const val MAX_SUBLABEL = 60
    const val MAX_DOMAIN = 60
    const val MAX_APP_LABEL = 40
    const val MAX_PACKAGE = 60

    /** 搜索结果的上限。比 [VaultIndex.search] 的默认 200 小：这一页是浮窗，不是列表页。 */
    const val MAX_RESULTS = 50

    /** 默认清单里「最近改动」那一段摆几条。 */
    const val RECENT_LIMIT = 12

    /* ══════════════════════════ 成句的那几条 ══════════════════════════ */

    const val EMPTY_VAULT = "保险库里还没有条目。"

    const val NO_MATCH =
        "这个网站在保险库里没有对得上的条目。你仍然可以自己挑一条——" +
            "挑之前请看清屏幕上那句「会交给谁」。"

    const val PARTIAL_NOTE = "这里只摆了对得上的和最近改过的几条。要找别的，直接搜。"

    const val STORED_ELSEWHERE =
        "这一条存的网址和这一屏不是同一个站。如果你本来想挑的是另一条，现在退出去还来得及。"

    const val BLOCKED_NOTHING_TO_FILL =
        "这一条在这一屏上填不出东西来——它的账号和密码都是空的，" +
            "或者这一屏要的那一格它正好没存。点下去不会有任何变化，所以这一下不给按。"

    const val REFUSE_NO_FORM =
        "这一屏上没有认得出来的账号框或密码框，没有可填的地方。"

    const val REFUSE_OWN_UI =
        "这是保险库自己的界面，不会往自己身上填。"

    /**
     * 最近改动那一段的顺序：收藏 → 最近改过的 → 名称。
     *
     * 和 [AutofillMatch] 那个排序的区别只有一处：这里没有档位那一项
     * （这一段里的条目全是 [DomainMatch.Verdict.None] 那一档，比不出高低）。
     */
    private val RECENT_ORDER: Comparator<VaultEntry> =
        compareByDescending<VaultEntry> { it.favorite }
            .thenByDescending { it.updatedAt }
            .thenBy(VaultIndex.NAME_ORDER) { it }
}
