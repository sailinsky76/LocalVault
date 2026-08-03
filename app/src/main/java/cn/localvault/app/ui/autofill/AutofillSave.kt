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
 * 「用户刚才在这一屏上打的东西，要不要存进保险库；存的话，是新增一条还是改一条已有的」。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * `onSaveRequest` 怎么接、确认页怎么画，是 M4-3b 的事。这一层只产出一份**提案**。
 *
 * ── 这一层和前面九个内核的区别：方向反过来了 ──
 *
 * M4-1 到 M4-2 那一整条链，回答的都是「往外交什么」。错了的代价是**把密码交给不该交的人**，
 * 所以那条链上每一处拿不准都往窄了写：认不出就少填，不要猜着填。
 *
 * 这一层反过来：它**往库里写**。错了的代价换了一种形状，而且换得比想象中重：
 *
 *   · 存错一条，库里就长期躺着一条错误的关联，**以后每一次自动填充都会用它**。
 *     一次填错只是一次，存错一次是从此以后每一次。
 *   · 更糟的是「改错一条」：把 A 账号的密码覆盖到 B 账号那一条上，**旧值当场没了**。
 *     这个 App 没有条目级的历史版本，也没有撤销（同决策(157)），
 *     用户下次登录 B 时会发现密码不对，而他手上再没有第二份。
 *
 * 所以这一层的两条底线是：
 *
 * **一、不静默改库。** 这一层只产出 [Proposal]，**一行都不落盘**。
 * 落盘要经过一屏用户看得见的确认，而那一屏上要逐条写清「改的是哪一条、哪几个字段」
 * （决策(196)）。系统给的那个保存框只有「保存 / 不用」两个按钮，
 * 按下去之后发生了什么用户是看不见的——那正是我们不能照抄的地方。
 *
 * **二、只增不改。** 已经有值的字段一个都不覆盖，除了密码那一格
 * （而密码那一格正是用户按下这个按钮的全部目的，且它会被逐条列在屏幕上）。
 * 名称、分类、备注一个字不碰，网址只追加不删除（决策(201)/(202)）。
 *
 * ── 顺序：拒绝排在库状态之前 ──
 *
 * 同 [AutofillPick.refusal] / [AutofillOffer.respond]：[refuse] 不需要知道库的任何事，
 * 于是也不会因为回答它而泄露任何事（决策(180)）。反过来写——先弹一次解锁框、
 * 解开之后才发现这一屏根本没读到任何值——**等于为一件注定做不成的事，向用户要了一次主密码。**
 */
object AutofillSave {

    /* ══════════════════════════ 不弹的那几种 ══════════════════════════ */

    /**
     * 为什么这一次不摆保存那一屏。
     *
     * 每一档都有自己的名字，理由同 [DomainMatch.Verdict]：M4-4 的关于页要能对着用户
     * 说出「为什么保存有时候不出现」，而不是让他面对一次什么都没发生的提交。
     *
     * 这几档里没有「失败」「出错」——它们描述的都不是故障，
     * 是几种我们**故意**不出手的处境。
     */
    enum class Reason {
        /** 这是保险库自己的界面。同决策(180)，不往自己身上填，也不为自己存。 */
        OwnUi,

        /** 一个值都没读到（框是空的，或者读到的东西被 [SavedFields] 整格拒收了）。 */
        NothingCaptured,

        /**
         * 分不出该存哪一个密码。
         *
         * 一屏上两个都判成「已有密码」的框，值还不一样（[SaveContext.conflictingPasswords]）。
         * `FillPlan` 在填充那一侧对这种一屏的答案是「一个都不填」，这里对称：**一个都不存**。
         */
        CannotTellPassword,

        /**
         * 分不出该改哪一条。
         *
         * 这一屏只读到密码没读到账号（分屏登录的第二屏），而这个站在库里有不止一条
         * 够格自动填的条目。改错一条的代价见文件头，**而这一档是唯一能提前避开它的地方**。
         */
        CannotTellEntry,

        /**
         * 密码框里读到的是一串掩码符，不是密码（决策(229)）。
         *
         * 安全键盘那一类应用：真值在 SDK 自己的缓冲里，摆在输入框里的就是一串 `•`，
         * 于是 [SavedFields] 把那一格整格拒收了。手上没有密码。
         *
         * **这一档存在的理由不是「拦住覆盖」**——拒收那一步已经拦住了。
         * 它拦的是另一件事：拿着一个读得到的账号去**新建一条密码为空的条目**。
         * 那条空壳永远不会被补上（下一次登录密码照样读不到），
         * 而用户按下「更新」时以为自己刚存了一份密码。
         * 与其留一条骗人的记录，不如说一句实话。
         */
        MaskedPassword,

        /** 库里已经有一模一样的了。什么都不用改，也就没有必要打扰一次。 */
        AlreadyStored,
    }

    /** 每一档对应的一句实话。M4-4 的关于页要把它们摆出来。 */
    fun note(reason: Reason): String = when (reason) {
        Reason.OwnUi ->
            "这是保险库自己的界面，不会把自己这一屏上的输入存成条目。"

        Reason.NothingCaptured ->
            "这一屏上没读到可以存的账号或密码。"

        Reason.CannotTellPassword ->
            "这一屏上有两个分不出新旧的密码框，里面的值还不一样——" +
                "没法判断你要用的是哪一个，所以这次一个都没存。你可以自己在保险库里改那一条。"

        Reason.MaskedPassword ->
            "这个应用的密码框用的是它自己的安全键盘，框里摆着的只是一串圆点，" +
                "真正的密码我们读不到，所以这一次什么都没存。" +
                "这一类应用的密码要自己在保险库里记一条。"

        Reason.CannotTellEntry ->
            "这一屏上只读到了密码，没读到账号，而这个站你在保险库里存了不止一条。" +
                "改错一条的话，另一个账号的密码就没了，所以这次没有动任何一条。" +
                "你可以自己打开那一条改密码。"

        Reason.AlreadyStored ->
            "你刚才用的账号和密码，保险库里存的就是这一份，没有需要改的地方。"
    }

    /**
     * 不需要知道库的任何事就能给出的拒绝。返回 null 表示这一屏值得往下走。
     *
     * [ownPackage] 是本应用自己的包名。M4-3b 传 `context.packageName`——
     * 同 [AutofillOffer.respond] / [AutofillPick.refusal] 那两处，不在这一层写死。
     */
    fun refuse(context: SaveContext, ownPackage: String): Reason? = when {
        // 顺序：三问都不需要库，而这一问最便宜也最硬。
        context.origin.hostApp == ownPackage -> Reason.OwnUi
        context.conflictingPasswords -> Reason.CannotTellPassword
        // 排在 NothingCaptured 之前：这一屏多半还读到了账号（`hasAnything` 成立），
        // 走不到那一档，而「读到的是掩码」是一句比「什么都没读到」准确得多的话。
        // 判 `effectivePassword == null` 而不是只判标记：同屏还有一个读得出的
        // 新密码框时（改密码页），那一格才是该存的东西，不该被这一档挡掉。
        context.maskedPassword && context.effectivePassword == null -> Reason.MaskedPassword
        !context.hasAnything -> Reason.NothingCaptured
        else -> null
    }

    /**
     * **这一档不打扰用户，安不安全**（决策(232) → 决策(233) → 决策(234) 三版之后的定稿）。
     *
     * 静默有一个隐含前提：用户按下系统那个保存框、屏幕上什么都没发生之后，
     * 他会认为「存好了」。所以只有当我们**确知他要的那份东西已经在库里**时，
     * 静默才是诚实的。
     *
     * [Reason.AlreadyStored] 天然满足：那一档的成立方式就是把读到的值和库里那条
     * 逐字比过一遍。[Reason.CannotTellEntry] 也满足：它成立的前提是这个站库里
     * 有不止一条，用户手上有东西可查。
     *
     * [Reason.MaskedPassword] **永远不满足，和库里有什么无关**——这是这一档的定义：
     * 我们没读到密码，也就**没有任何办法知道库里那条是不是他刚打的那个**。
     *
     * ── 这一条走了两版弯路，两版都错在同一个地方 ──
     *
     *   · 决策(232)：「库里有没有能被这次保存改动的条目」。空壳条目（密码为空）
     *     满足它，可它保护不了任何东西。
     *   · 决策(233)：改成「库里有没有一份**非空**的密码」。看着严了，其实还是错的——
     *     库里那份密码非空，不等于它**是对的**。用户改过密码、库里那条早已过期，
     *     这一次他正是想把它更新过来才按下保存的，而我们安静收场，
     *     他于是带着一条错的记录走了。
     *
     * 两版的共同错误是：**拿库里有什么，去替代一件我们根本读不到的事实。**
     * 只要密码是掩码，「库里那条对不对」就是不可知的，而在不可知的时候保持沉默，
     * 等于替用户做了「不用管」这个判断。
     *
     * ── 决策(231) 当初担心的骚扰不成立 ──
     *
     * 那时候设想的是「每登录一次就是一条通知」。实际的链路不是这样：
     * 这一页只在用户**自己按下系统那个保存框**之后才出现——它是对一次明确请求的
     * 回答，不是主动打扰。答不上来的时候说一句「这次没存、原因是这个」，
     * 是这条链上最起码的诚实；什么都不做才是那个需要辩解的选项。
     */
    fun safeToStaySilent(reason: Reason): Boolean = reason != Reason.MaskedPassword

    /* ══════════════════════════ 提案 ══════════════════════════ */

    enum class Mode {
        /** 库里新长出一条。 */
        Create,

        /** 改一条已有的。**只有这一档能让已有的值消失**，所以它的门槛处处比 [Create] 高。 */
        Update,
    }

    /** 会被动到的字段。名称、分类、备注不在这个枚举里——它们一个字都不会被碰（决策(201)）。 */
    enum class Field { Name, Username, Password, Domain }

    /** 怎么动。 */
    enum class How {
        /** 原来是空的，现在填上。 */
        Add,

        /** 原来有值，会被换掉。**整份提案里只有密码可能是这一档**，见 [changesFor]。 */
        Replace,
    }

    /**
     * 一条改动。
     *
     * [shown] 是可以摆在屏幕上的那一段；**[Field.Password] 那一条永远是 null**。
     * 这不是「记得别显示」，是这个字段在构造时就没被赋值（[changesFor] 里那一行 `null`）——
     * 确认页上摆的是「密码会被换掉」这句话本身，不是两个密码。
     *
     * 用户需要在那一屏上确认的是**改的是哪一条、动的是哪几样**，不是核对密码字符串：
     * 他刚打完那个密码，屏幕上（多半）还看得见；把库里那个旧密码也一起摆出来，
     * 才是这一屏上唯一真正新增的泄露面——而它对这个决定没有任何帮助。
     */
    class Change internal constructor(
        val field: Field,
        val how: How,
        val shown: String?,
    ) {
        override fun toString(): String = "Change(${field.name}, ${how.name})"
    }

    /**
     * 一份提案：**如果用户按下确认，库里会变成什么样。**
     *
     * 不是 `data class`，`toString` 手写只报形状：[result] 是一个 `VaultEntry`，
     * 而 `VaultEntry` 是 `data class`，它的自动 `toString` 会把明文密码原样打出来
     * （同 `AutofillMatch.Candidate` 那一段）。
     */
    class Proposal internal constructor(
        val mode: Mode,

        /** [Mode.Update] 时是被改的那一条；[Mode.Create] 时是 null。 */
        val target: VaultEntry?,

        /** 按下确认之后要交给 `VaultSession` 的那一条。[Mode.Create] 时 `id` 是空串。 */
        val result: VaultEntry,

        /** 逐条改动。空清单表示这一按什么都不会变（见 [isNoop]）。 */
        val changes: List<Change>,

        /** 按钮上方必须先说的那几句。空清单 = 一句废话都不说（同决策(95)）。 */
        val warnings: List<String>,

        /** 这一条落在哪一档归属。确认页要据此决定说哪一句。 */
        val verdict: DomainMatch.Verdict,

        /**
         * 不给按时的那一句原因。null 表示可以按。
         *
         * **画成禁用而不是藏起来**（同决策(174)）：藏起来的后果是用户找不到
         * 他明明选中的那一条，然后开始怀疑是这个功能坏了。
         */
        val blocked: String?,

        /**
         * 用户还能改哪几条。确认页上「换一条」那个入口用它。
         *
         * 只装够格自动填的那些（[DomainMatch.Verdict.canAutoFill]）——理由同 [proposeUpdate]。
         */
        val alternatives: List<VaultEntry>,
    ) {
        /** 按下去什么都不会变。确认页要把按钮禁掉并说明。 */
        val isNoop: Boolean get() = changes.isEmpty()

        val canCommit: Boolean get() = blocked == null && !isNoop

        override fun toString(): String =
            "Proposal(${mode.name}, ${changes.size} changes, blocked=${blocked != null})"
    }

    /** 摆保存那一屏，还是安静走人。 */
    sealed class Outcome {
        class Silent internal constructor(val reason: Reason) : Outcome() {
            override fun toString(): String = "Silent(${reason.name})"
        }

        class Offer internal constructor(val proposal: Proposal) : Outcome() {
            override fun toString(): String = "Offer(${proposal.mode.name})"
        }
    }

    /* ══════════════════════════ 入口 ══════════════════════════ */

    /**
     * 解锁之后算一次：这一屏到底该摆什么。
     *
     * [entries] 是**当下**的库内容。这一点和 `ImportController` 落盘前重算是同一条
     * （决策(152)）：从 `onSaveRequest` 到用户按下确认，中间隔着一次解锁、
     * 可能还隔着他切出去看了一眼验证码，库在那期间完全可能已经变了。
     *
     * 拿旧快照算出来的提案会摆出一句和事实不符的话（「这一条现在还没存密码」，
     * 而他刚在另一个窗口里存过了），然后**照着那句话去覆盖**。
     */
    fun outcome(
        context: SaveContext,
        entries: List<VaultEntry>,
        trust: HostTrust,
        ownPackage: String,
    ): Outcome {
        refuse(context, ownPackage)?.let { return Outcome.Silent(it) }

        val matches = updatable(context.origin, entries, trust)
        val target = chooseTarget(context, matches) ?: run {
            // 只读到密码、这个站却有好几条——见 Reason.CannotTellEntry。
            if (context.username == null && matches.size >= 2) {
                return Outcome.Silent(Reason.CannotTellEntry)
            }
            null
        }

        val proposal =
            if (target != null) proposeUpdate(context, target, trust, matches)
            else proposeCreate(context, trust, matches)

        // 一模一样时不打扰。**这一句只能在解锁之后说**（决策(197)）：
        // 库锁着的时候我们数不出库里有什么，也就没法提前知道这一次是白跑。
        // 宁可让用户白解锁一次，也不能因为库锁着就把他刚打的密码丢掉——
        // 刚注册完那一次正是最值钱、也最不可能再打一遍的一次。
        if (proposal.isNoop && proposal.blocked == null) {
            return Outcome.Silent(Reason.AlreadyStored)
        }
        return Outcome.Offer(proposal)
    }

    /**
     * 库里哪几条**有资格被这一次保存改动**。
     *
     * 只收 [DomainMatch.Verdict.canAutoFill] 那两档，这是决策(199) 的落笔处，
     * 值得单独说清楚：
     *
     * 后面四档（`UntrustedHost` / `NoEvidence` / `WrongKind` / `None`）在**填充**那一侧
     * 是「不自动出手，但用户可以手动挑」——挑错了顶多是这次填错，退出去重来即可。
     * 在**保存**这一侧不能照搬：一个套着 WebView 假冒登录页的应用
     * （`UntrustedHost`，见 [Origin] 文件头那段 AutoSpill），
     * 拿到的不只是这一次输入——它还能借这个保存框**改掉用户库里那条真的**。
     * 用户以后每次登录真网站，填出去的都是被改过的那个值。
     *
     * 所以这一层的界限画在这儿：**不够格自动填的来源，永远只能新增，不能更新。**
     * 新增一条的代价是库里多一条用户能看见、能删掉的东西；
     * 更新一条的代价是一条他看不见、也找不回来的丢失。
     */
    fun updatable(origin: Origin, entries: List<VaultEntry>, trust: HostTrust): List<VaultEntry> =
        entries.filter { DomainMatch.best(origin, it.domains, trust).verdict.canAutoFill }

    /**
     * 该改哪一条，还是该新建。返回 null 表示新建。
     *
     *   1. **账号逐字相同**的那一条。账号是这条链上唯一一件用户和我们都看得见的
     *      对照物；「相同账号 + 相同站点」在实际生活里就是同一份凭据。
     *
     *      **逐字，一个字都不放宽**（决策(227) 撤回决策(225)）。曾经有过一层
     *      `AccountName`，它认得出登录框自己给手机号插的分节空格，把
     *      `186 2345 6789` 和 `18623456789` 判成同一个账号。撤掉的理由不是它算错了，
     *      而是这一层**判错的代价不对称**：判成「不同」只是库里多一条用户看得见、
     *      也删得掉的记录；判成「相同」是一次不可撤销的覆盖，而这个 App 没有条目级
     *      历史、也没有撤销。「几个没意义的空格」这个前提要成立，得先假定那个字符串
     *      是个电话号码、且分隔符不携带信息——这是两层猜测，猜错一次就是一条密码没了。
     *
     *      **也不用名称、不用包名当判据。** 包名其实已经在用了，而且它是**前一道**
     *      筛子不是这一道：[updatable] 先按归属把「这个站的条目」筛出来，剩下的问题
     *      恰恰是「这个站的好几条里改哪一条」。用包名当最终判据等于「同一个应用 →
     *      覆盖手上这一条」——一个人在同一个应用上有主号和小号时，小号那次登录会把
     *      主号那条的密码盖掉，而他要到下次登录主号才发现。名称比包名更糟：它是用户
     *      能随手改的自由文本，而新建时的建议名又是从应用名推出来的，两个小号默认
     *      都叫同一个名字。
     *   2. 好几条账号都相同（罕见，但导入来的库里会有重复行）→ 取最近改过的那一条，
     *      其余的进 [Proposal.alternatives]，让用户在屏幕上换。
     *      **不在这里静静地挑一条了事**——多出来的那几条正是他最可能要挑的。
     *   3. 一条账号都对不上，但这个站在库里**恰好一条、而且那一条的账号是空的**
     *      → 认它（决策(224)）。这一档补的是一个真实的缺口：用户先在库里手记了一条
     *      「小红书 + 密码」（列表页新建时账号本来就可以留空），后来在应用里登录，
     *      按下保存框——账号逐字相同这一条永远不成立，于是每登录一次就新增一条，
     *      而他要的是把账号补上、密码改掉。
     *
     *      **这一档不会让任何东西凭空消失**：账号那一格原来是空的，落的是
     *      [How.Add]（[changesFor] 那一段），[proposeUpdate] 的护栏二本来就只挡
     *      「账号非空且对不上」。真正会被换掉的只有密码，而它逐条写在确认页上。
     *
     *      `singleOrNull` 是有意收紧的：这个站有两条时不认。一条空账号 + 一条别人的账号，
     *      猜哪一条都可能猜错，而猜错的代价是另一个账号的密码没了（文件头那段）。
     *
     *   4. 上面都不成立 → 新建。他在这个站上用了一个新账号，这就是一条新条目。
     *   5. **这一屏没读到账号**（分屏登录的第二屏）→ 只有当这个站在库里**恰好一条**时
     *      才认那一条。不止一条时不猜（[Reason.CannotTellEntry]）。
     */
    fun chooseTarget(context: SaveContext, matches: List<VaultEntry>): VaultEntry? {
        val user = context.username
        if (user == null) return matches.singleOrNull()
        // 逐字比较（决策(227)）。登录框会自己给手机号插分节空格，于是
        // `186 2345 6789` 和库里的 `18623456789` 会被判成两个账号、落成一次新增——
        // 这是**有意接受**的代价，理由写在上面那段文档里：多一条能删的记录，
        // 好过一次删不掉的覆盖。
        val sameUser = matches.filter { it.username == user }
        if (sameUser.isNotEmpty()) return sameUser.maxWithOrNull(RECENCY)
        // 见上面第 3 条：这个站只有一条、而且那一条还没有账号
        return matches.singleOrNull()?.takeIf { it.username.isEmpty() }
    }

    /* ══════════════════════════ 两种提案 ══════════════════════════ */

    /**
     * 新建一条。
     *
     * [trust] 是必需的，尽管新建这一路不查库里任何一条：警告那一句要靠它
     * （[CREATED_FROM_UNTRUSTED]）。给它一个默认值会让那句话某天静静地不再出现，
     * 而编译器一声不吭——同 `SettingsScreen.onSecurity` 那条理由。
     */
    fun proposeCreate(
        context: SaveContext,
        trust: HostTrust,
        alternatives: List<VaultEntry> = emptyList(),
    ): Proposal {
        val name = suggestedName(context.origin, context.appLabel)
        // 屏幕上读到什么就存什么，**一个字都不改**（决策(227)）。
        // 曾经在这儿抹掉过手机号的分节空格，撤掉了：改写用户刚打进去的账号是一次
        // 静悄悄的猜测，而它换来的好处（下次填进一个不接受空格的框里）在这条链上
        // 反而会反过来咬人——存下的那一份和下次读回来的那一份不一样，
        // 逐字比较就又对不上了。
        val user = context.username.orEmpty()
        val pwd = context.effectivePassword.orEmpty()
        val line = domainLine(context.origin)

        val changes = ArrayList<Change>(4)
        changes += Change(Field.Name, How.Add, name)
        if (user.isNotEmpty()) changes += Change(Field.Username, How.Add, user)
        if (pwd.isNotEmpty()) changes += Change(Field.Password, How.Add, null)
        if (line.isNotEmpty()) changes += Change(Field.Domain, How.Add, line)

        val result = VaultEntry(
            id = "",
            name = name,
            username = user,
            password = pwd,
            domains = if (line.isEmpty()) emptyList() else listOf(line),
        )

        return Proposal(
            mode = Mode.Create,
            target = null,
            result = result,
            changes = changes,
            warnings = warningsFor(context, DomainMatch.Verdict.None, Mode.Create, trust),
            verdict = DomainMatch.Verdict.None,
            blocked = null,
            alternatives = alternatives,
        )
    }

    /**
     * 改一条已有的。
     *
     * [target] 可以是用户在确认页上**自己换过去的**那一条，所以这里不假设它一定来自
     * [chooseTarget]——两条护栏（不够格自动填的不许改、账号对不上的不许改）
     * 都在这个函数里，而不是在挑选那一步里。挑选那一步只是默认值，护栏必须长在落笔处。
     */
    fun proposeUpdate(
        context: SaveContext,
        target: VaultEntry,
        trust: HostTrust,
        alternatives: List<VaultEntry> = emptyList(),
    ): Proposal {
        val verdict = DomainMatch.best(context.origin, target.domains, trust).verdict
        val changes = changesFor(context, target)
        val result = applyTo(context, target)

        // 取出来放进局部变量：`context.username` 是自定义 getter，
        // 在 `when` 里判过非空也不会被智能转换
        val user = context.username

        val blocked = when {
            // 护栏一：见 updatable 那一段。
            !verdict.canAutoFill -> BLOCKED_UNTRUSTED_UPDATE

            // 护栏二：账号对不上。**绝不改写一个非空的账号**（决策(201)）——
            // 一条存着别人账号的条目被换上这次的密码，等于两个账号的凭据被搅在一起，
            // 而屏幕上会显示「已保存」。这一档在自动挑选里到不了，
            // 只有用户在确认页上自己换了一条时才可能出现，所以它必须画成禁用
            // 而不是悄悄改成新建——他明明选中了那一条。
            user != null &&
                target.username.isNotEmpty() &&
                target.username != user -> BLOCKED_OTHER_ACCOUNT

            else -> null
        }

        return Proposal(
            mode = Mode.Update,
            target = target,
            result = result,
            changes = changes,
            warnings = warningsFor(context, verdict, Mode.Update, trust),
            verdict = verdict,
            blocked = blocked,
            alternatives = alternatives.filter { it.id != target.id },
        )
    }

    /**
     * 更新时到底动了哪几样。
     *
     * **只有三样可能被动**，而且只有密码那一样可能是 [How.Replace]：
     *   · 账号：只在原来是空的时候补上（[How.Add]）。原来有值一律不碰，
     *     那种情况会被 [proposeUpdate] 挡在 [Proposal.blocked] 上；
     *   · 密码：空的补上，不一样的换掉，一样的不列；
     *   · 网址：这个站在这一条里一行都没有时追加一行。**永远不删已有的行**
     *     （决策(202)，同决策(56) 网址只丢不改写）。
     *
     * 名称、分类、备注、收藏一个字都不动（决策(201)）。用户当初给这一条起的名字，
     * 是他在列表里认出它的唯一依据；用一个从主机名推出来的名字盖掉它，
     * 会让他在自己的库里找不到东西——而他甚至不会知道是哪一步改的。
     */
    fun changesFor(context: SaveContext, target: VaultEntry): List<Change> {
        val out = ArrayList<Change>(3)

        val user = context.username
        if (user != null && target.username.isEmpty()) {
            out += Change(Field.Username, How.Add, user)
        }

        val pwd = context.effectivePassword
        if (pwd != null && pwd != target.password) {
            out += Change(
                Field.Password,
                if (target.password.isEmpty()) How.Add else How.Replace,
                null, // 见 Change.shown 那一段
            )
        }

        val line = domainLine(context.origin)
        if (line.isNotEmpty() && !hasDomain(target, line)) {
            out += Change(Field.Domain, How.Add, line)
        }
        return out
    }

    /** 把 [changesFor] 那几样真的落在条目上，产出要交给 `VaultSession.updateEntry` 的那一条。 */
    private fun applyTo(context: SaveContext, target: VaultEntry): VaultEntry {
        var e = target
        val user = context.username
        if (user != null && e.username.isEmpty()) e = e.copy(username = user)

        val pwd = context.effectivePassword
        if (pwd != null && pwd != e.password) e = e.copy(password = pwd)

        val line = domainLine(context.origin)
        if (line.isNotEmpty() && !hasDomain(e, line)) e = e.copy(domains = e.domains + line)
        return e
    }

    /**
     * 这一条里有没有已经写着这个站。
     *
     * 比的是**归一之后**的形式，用 `VaultIndex.normalizeDomain`，**不另写一份**——
     * 决策㉝ 那句「不许各写各的」的又一次兑现。不归一的后果是：
     * 条目里存着 `https://example.com/`，这次算出来的是 `example.com`，
     * 于是每登录一次就往这一条上追加一行看起来一模一样的网址，
     * 半年后那一条下面挂着二十行同一个站。
     */
    private fun hasDomain(entry: VaultEntry, line: String): Boolean {
        val key = VaultIndex.normalizeDomain(line)
        if (key.isEmpty()) return false
        return entry.domains.any { VaultIndex.normalizeDomain(it) == key }
    }

    /* ══════════════════════════ 存成什么样 ══════════════════════════ */

    /**
     * 要写进 `domains` 的那一行。
     *
     * · 原生应用 → 包名。它由系统给出、应用改不了，是这条链上最硬的事实（决策(158)）；
     * · 网页 → **归一后的主机名原样**，不上卷到可注册域。
     *
     * 第二条值得说清楚：把 `login.example.com` 存成 `example.com` 会让以后的匹配
     * 从 `SameSite` 变成 `Exact`，看起来更好用。但那是**我们替用户扩大了这条凭据的适用面**，
     * 而扩大匹配面是这条链上唯一一个代价大的方向（[DomainMatch] 文件头那句
     * 「判宽了，用户点一下就把密码发给了别人」）。
     * 存窄了的代价只是以后在兄弟域上多看一句提示（[AutofillPick.sameSiteNote]），
     * 而那一句本来就该看见。
     */
    fun domainLine(origin: Origin): String = when (origin) {
        is Origin.App -> VaultIndex.normalizeDomain(origin.hostApp)
        is Origin.Web -> VaultIndex.normalizeDomain(origin.host)
    }

    /**
     * 新建那一条叫什么名字。**这只是个初值**——确认页上那一栏是可以改的，
     * 而且用户多半会改（他知道这个站在他心里叫什么）。
     *
     * · 网页 → 可注册域（`mail.example.com` → `example.com`）。
     *   这里**故意和 [domainLine] 反着来**：名字是给人看的，`example.com`
     *   比 `login.example.com` 更像一个站的名字；而匹配用的那一行要窄。
     *   一个是标签，一个是凭据的适用范围，两者本来就不该是同一个东西。
     * · 原生应用 → 洗过的应用名；读不到名字时用包名。
     *   **不写「未知应用」**（同 [AutofillPick.handOver] 那一段）：那四个字听起来像出了故障，
     *   而包名已经把该说的都说了。
     */
    fun suggestedName(origin: Origin, appLabel: String?): String = when (origin) {
        is Origin.App -> {
            val pkg = AutofillRow.clean(origin.hostApp, AutofillPick.MAX_PACKAGE)
            val label = appLabel?.let { AutofillRow.clean(it, AutofillPick.MAX_APP_LABEL) }.orEmpty()
            if (label.isEmpty()) pkg else label
        }

        is Origin.Web -> {
            val host = VaultIndex.normalizeDomain(origin.host)
            val name = PublicSuffix.registrableDomain(host) ?: host
            AutofillRow.clean(name, AutofillPick.MAX_DOMAIN).ifEmpty { UNNAMED }
        }
    }

    /**
     * 「这一条会记在谁名下」——确认页顶上那一行。
     *
     * 和 [AutofillPick.handOver] 是同一条规矩的两个方向：那一行说的是内容**要交给谁**，
     * 这一行说的是这次输入**要记在谁名下**。两处都必须**永远把包名写出来**：
     * 应用名是被保存对象自己提供的字符串，可以叫「Chrome 浏览器」，
     * 也可以在里面塞一个 `U+202E` 让它倒着画出来（决策(184)/(188)）。
     * 洗和显示都复用 [AutofillPick.identify]，**不写第二份**。
     */
    fun storedUnder(origin: Origin, appLabel: String?): String {
        val who = AutofillPick.identify(origin.hostApp, appLabel)
        return when (origin) {
            is Origin.App ->
                "这一条会记在 $who 名下。以后你在这个应用里登录时，它会被填出来。"

            is Origin.Web ->
                "这一条会记在 ${AutofillRow.clean(origin.host, AutofillPick.MAX_DOMAIN)} 名下" +
                    "（这一页由 $who 在显示）。以后你在这个网站上登录时，它会被填出来。"
        }
    }

    /* ══════════════════════════ 话怎么说 ══════════════════════════ */

    /**
     * 按下去之前必须先说的那几句。
     *
     * 三档非自动那三句**原样复用 [AutofillPick.warning]，一个字都不改写**——
     * 同一件事在两页上说成两个样子，用户会以为那是两件事。
     * 这里只补保存这一侧独有的那两句。
     */
    fun warningsFor(
        context: SaveContext,
        verdict: DomainMatch.Verdict,
        mode: Mode,
        trust: HostTrust,
    ): List<String> {
        val out = ArrayList<String>(2)

        if (mode == Mode.Update) {
            AutofillPick.warning(verdict)?.let { out += it }
        } else if (untrustedSource(context.origin, trust)) {
            // 新建那一侧只在这一档说一句：一个假冒登录页骗到的输入被记进库，
            // 以后会在**真网站**上被填出来——那才是这一条真正的代价，
            // 而它和填充那一侧的措辞不一样，所以单独写。
            out += CREATED_FROM_UNTRUSTED
        }

        if (context.kind == FillPlan.Kind.NewCredential && mode == Mode.Update) {
            out += CHANGED_PASSWORD_NOTE
        }
        return out
    }

    /**
     * 这一屏是**网页**，而承载它的应用不是我们认得的浏览器。
     *
     * 新建那一侧不能靠 [DomainMatch.Verdict] 来问这件事：新建的前提正是
     * 「库里一条对得上的都没有」，于是判定永远是 [DomainMatch.Verdict.None]，
     * 而 `None` 说的是「不相干」，不是「承载它的不可信」。
     * 两者在这一屏上完全可能同时成立——一个假冒登录页，用户在上面注册了一个新账号，
     * 那正是最该说一句的时候，也正是 verdict 说不出话的时候。
     *
     * 原生应用那一侧不问这个：那里根本没有「网页自称属于谁」这一层，
     * 归属就是包名本身，而包名是系统给的（决策(158)）。
     */
    private fun untrustedSource(origin: Origin, trust: HostTrust): Boolean =
        origin is Origin.Web && !trust.isTrustedBrowser(origin.hostApp)

    /** 这一档改动在屏幕上怎么念。 */
    fun changeNote(change: Change): String = when (change.field) {
        Field.Name -> "名称：${change.shown}"
        Field.Username ->
            if (change.how == How.Add) "账号：${change.shown}" else "账号：换成 ${change.shown}"

        Field.Password ->
            if (change.how == How.Add) "密码：存进去（这一条原来没有密码）"
            else "密码：**换掉原来那个**。旧的会当场消失，这个应用不留历史版本。"

        Field.Domain ->
            if (change.how == How.Add) "网址：追加一行 ${change.shown}"
            else "网址：${change.shown}"
    }

    const val UNNAMED = "未命名"

    const val BLOCKED_UNTRUSTED_UPDATE =
        "这一条不给改。承载这一屏的不是我们认得的浏览器，或者这一条存的东西和这一屏对不上——" +
            "一个仿冒的登录页如果能改掉你库里那条真的，你以后在真网站上填出去的就是被改过的值。" +
            "要存的话可以新建一条，那一条你自己看得见、也删得掉。"

    const val BLOCKED_OTHER_ACCOUNT =
        "这一条存的账号和你刚才用的不是同一个。改它等于把两个账号的密码搅在一起，" +
            "而旧的那个当场就没了。换一条，或者新建一条。"

    const val CREATED_FROM_UNTRUSTED =
        "承载这一屏的不是我们认得的浏览器。存下来之后，这一条以后会在**真的**那个网站上被填出来——" +
            "所以先确认你刚才登录的确实是那个网站本身。"

    const val CHANGED_PASSWORD_NOTE =
        "你刚才是在设一个新密码。存下来之后，库里那条的旧密码会被换掉，" +
            "而这个应用不留历史版本——如果那个网站最后没有改成功，你手上就没有旧的那个了。"

    /**
     * 改密码那一屏上，按钮下面还要补的一句。
     *
     * 它和 [CHANGED_PASSWORD_NOTE] 说的不是同一件事：那一句说「旧的会没」，
     * 这一句说「网站那边到底改成功了没有，我们不知道」。
     * 这个 App 看不见网站的返回结果，只看得见用户往框里打了什么——
     * 提交失败（密码太弱、原密码打错、验证码过期）时屏幕上一样会弹这个保存框。
     */
    const val UNVERIFIED_NOTE =
        "这个保险库看不见网站那边有没有改成功，只看得见你往框里打了什么。" +
            "如果那一步没有成功，先别存——回去重来一次，等成功了再存。"

    /**
     * 「最近改过的排前面」。同 [AutofillMatch] 那个排序的最后两项，理由也相同：
     * 不用「上次在这儿用过哪一条」，那要记一笔谁在什么时候登录了什么（决策(163)）。
     */
    private val RECENCY: Comparator<VaultEntry> =
        compareBy<VaultEntry> { it.updatedAt }.thenBy(VaultIndex.NAME_ORDER) { it }
}
