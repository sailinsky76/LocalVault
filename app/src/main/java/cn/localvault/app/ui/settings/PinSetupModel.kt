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

package cn.localvault.app.ui.settings

/**
 * PIN 设置流的内核：两步的推进、两次输入的比对、弱 PIN 的识别与说法、
 * 安全设置页上 PIN 那一行长什么样。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 和 [QuickUnlockModel]、`BiometricPolicy` 一个套路，但这一份搬过来的理由不太一样：
 * 那两份是因为分支在真机上凑不齐，这一份是因为**弱 PIN 的判断规则本身需要被钉住**。
 * 「什么算连号」「日期样式算到哪一步」这种规则一旦只活在界面代码里，
 * 改一次就没人说得清它现在到底认不认 `890123` 了。
 *
 * ── 这一页最要紧的一句话 ──
 *
 * **6 位 PIN 的安全性不来自它本身，来自设备绑定**（决策⑥）。
 * 它只有 10⁶ 种组合，单看必然被爆破；撑住它的是外面那层 Keystore 设备绑定密钥——
 * 攻击者把数据目录整个拷走也解不开，必须在这台设备上、在本应用进程里试，
 * 于是落进 `AttemptLimiter` 的退避。
 *
 * 这句话直接决定了下面所有文案的写法，见 [weaknessMessage] 上面那段。
 */
object PinSetupModel {

    /** PIN 的位数。和 `PinBuffer` 的默认容量、`PinDots` 的默认格数是同一个数。 */
    const val LENGTH = 6

    /**
     * 进这一页的两种来路。
     *
     * ── 为什么「修改 PIN」不要求先输一遍旧 PIN ──
     *
     * 理由同决策(98)：走到这一页说明保险库**已经是解锁状态**，
     * 能走到这儿的人早就能看见里面每一条密码了，再验一次旧 PIN 保护的是什么呢。
     * 而它的代价很实在——用户想改 PIN 的最常见原因就是**他快记不住现在这个了**，
     * 拿旧 PIN 挡在门口，等于告诉他「想换掉这个记不住的东西，请先把它背出来」。
     * 何况这道门根本挡不住：他只要把开关关掉再打开，一样能设一个新的，
     * 只是多绕两步、还多担一次「关掉会不会把数据弄没」的心。
     */
    enum class Mode {
        /** 从未设置过，第一次设。 */
        Set,

        /** 已经设过一个，换一个新的。底层就是覆盖写（`enrollPin` 直接覆盖旧包裹）。 */
        Change,
    }

    /** 两步：先输一遍，再输一遍。 */
    enum class Step { Enter, Confirm }

    /* ══════════════════════ 弱 PIN ══════════════════════ */

    /**
     * 弱 PIN 的种类。分种类而不是只给一个布尔值，是因为**每一种的说法不一样**：
     * 「连号」要说的是它在别人试的前几个里，「生日样式」要说的是认识你的人猜得到，
     * 这两句话给错对象都会显得莫名其妙。
     */
    enum class Weakness {
        /** 六位全一样：`000000` `111111`。 */
        AllSame,

        /** 一路加一或一路减一（首尾相接也算）：`123456` `654321` `890123`。 */
        Sequential,

        /** 两位或三位一个循环：`121212` `123123`。（六位全一样归 [AllSame]。） */
        Repeating,

        /** 常见到已经不算秘密的那几串，含数字键盘上的直线与对角线：`147258` `112233`。 */
        WellKnown,

        /** 看着像日期：`901231`（YYMMDD）、`311290`（DDMMYY）、`123190`（MMDDYY）、`199012`（YYYYMM）。 */
        DateLike,
    }

    /**
     * 认不认这串 PIN 是弱的。不弱返回 null。
     *
     * 位数不对或含非数字时同样返回 null——那不是「强」，是**这个函数不该在这儿下结论**：
     * 界面早就用「按满六位按钮才亮」拦住了，真走到这里说明是调用方出了岔子，
     * 这时候冒出一句「这个 PIN 偏弱」只会把人带偏。
     *
     * 判断顺序是 [Weakness] 的声明顺序，且**刻意从具体到宽泛**：
     * `121212` 既是循环也能算「常见」，报「循环」比报「常见」更有用；
     * `010101` 既是循环也像日期，同理。
     *
     * @param pin 调用方持有的缓冲，这里**只读不留**——不复制、不转成 String。
     *            6 位数字不代表它不值得当敏感数据对待（同 `PinBuffer` 的理由）。
     */
    fun weakness(pin: CharArray): Weakness? {
        val d = digitsOrNull(pin) ?: return null
        return when {
            isAllSame(d) -> Weakness.AllSame
            isSequential(d) -> Weakness.Sequential
            isRepeating(d) -> Weakness.Repeating
            isWellKnown(d) -> Weakness.WellKnown
            isDateLike(d) -> Weakness.DateLike
            else -> null
        }
    }

    fun weaknessTitle(w: Weakness): String = when (w) {
        Weakness.AllSame -> "这个 PIN 是六位一样的数字"
        Weakness.Sequential -> "这个 PIN 是一串连号"
        Weakness.Repeating -> "这个 PIN 在重复同一小段"
        Weakness.WellKnown -> "这个 PIN 很常见"
        Weakness.DateLike -> "这个 PIN 看起来像一个日期"
    }

    /**
     * 弱 PIN 的说法。
     *
     * ── 为什么不能照抄弱主密码那一段 ──
     *
     * 建库时那段话是：「保险库文件一旦被拷走，挡住离线爆破的就只剩这个密码本身。」
     * 这句话对主密码是真的，**对 PIN 是假的**——PIN 包裹外面还有一层 Keystore
     * 设备绑定密钥，文件被拷走的人根本解不开它，谈不上离线爆破（决策⑥）。
     * 把那段话搬过来，等于用一个不成立的理由吓唬用户；而他哪天真去查清楚了，
     * 会连带不再相信我们说的其它话。
     *
     * PIN 的真实风险模型是另一个：**能拿到这台手机的人，当着这台手机试**。
     * 那个人多半是身边的人，他知道你的生日；而退避挡得住上千次，
     * 挡不住前面那几次——「先试生日，再试 123456」正好都在前几次里。
     * 所以这几句话的落点统一是「猜得到 / 试得中」，不是「算得出」。
     *
     * 另外：这个函数的入参里**根本没有 PIN**，只有一个枚举。
     * 这样「弹窗里绝不出现用户刚输的那六位数」就不是靠自觉守住的，而是写不出来。
     * （同 `EntryForm` 那条「放弃修改的摘要里只有字段名」，测试里各钉了一条。）
     */
    fun weaknessMessage(w: Weakness): String = when (w) {
        Weakness.AllSame ->
            "拿到这台手机的人试的头几个里就有它。PIN 不怕被算出来，怕的是被人当着面试中——" +
                "换成一串没有规律的数字，这条路就基本堵死了。"

        Weakness.Sequential ->
            "连号和倒着的连号都在别人会试的头几个里。PIN 不怕被算出来，怕的是被人当着面试中——" +
                "换成一串没有规律的数字，这条路就基本堵死了。"

        Weakness.Repeating ->
            "重复的小段很容易被看一眼手势就记住，也在常被试的那批里。" +
                "换成一串没有规律的数字要稳得多。"

        Weakness.WellKnown ->
            "这一串（包括数字键盘上那几条直线和斜线）出现在几乎每一份常见 PIN 名单里。" +
                "换一串没规律的，别人试中的机会会小很多。"

        Weakness.DateLike ->
            "生日、纪念日这类数字，认识你的人不用试几次就能猜到——" +
                "而能拿到这台手机的人，往往正是认识你的人。换一串和你无关的数字更稳妥。"
    }

    /**
     * 弱 PIN 弹窗上两个按钮的字。
     *
     * 主按钮（黄铜、显眼）是「换一个」，继续用弱 PIN 放在次按钮——
     * 和建库那一页同一条规矩：**用户一路点最显眼那个按钮的结果应该是更安全，不是更省事。**
     * 而且它只是提醒，不是拦截：这是他自己的设备、自己的 PIN，
     * 真要用生日我们也拦不住，只能保证他是知情之后选的。
     */
    const val WEAK_CONFIRM_TEXT = "换一个"
    const val WEAK_SECONDARY_TEXT = "我知道，就用它"

    /* ══════════════════════ 两次输入的比对 ══════════════════════ */

    /** [confirm] 的结果。 */
    enum class ConfirmResult { Match, Mismatch }

    /**
     * 比对两次输入。
     *
     * @param first  第一步存下来的那份
     * @param second 第二步刚输完的这份
     */
    fun confirm(first: CharArray, second: CharArray): ConfirmResult {
        if (first.size != second.size || first.size != LENGTH) return ConfirmResult.Mismatch
        var same = true
        // 不提前 return：早退出会让「第几位开始不一样」变成可观测的东西。
        // 这里本来没有攻击面（比的是用户自己刚输的两份），但这种写法一旦在别处被抄，
        // 抄走的是习惯。统一按不早退的写法来。
        for (i in first.indices) if (first[i] != second[i]) same = false
        return if (same) ConfirmResult.Match else ConfirmResult.Mismatch
    }

    /**
     * 两次不一致时**退回第一步重输**，而不是只清掉第二次那份。
     *
     * 只清第二份是更常见的做法，但它建立在一个没根据的假设上：**打错的是第二次**。
     * 如果打错的其实是第一次，用户会对着一个他并不想要的 PIN 反复确认，
     * 直到某一次「对上了」——于是他设下了一个自己以为不是那样的 PIN，
     * 而这件事要到下次解锁才会暴露，那时候他连怀疑的方向都没有。
     *
     * 两份一起清掉，最坏是多按六下。
     */
    const val MISMATCH_MESSAGE = "两次输入不一致，请重新输入一遍。"

    /* ══════════════════════ 页面文案 ══════════════════════ */

    fun title(mode: Mode): String = when (mode) {
        Mode.Set -> "设置 PIN"
        Mode.Change -> "修改 PIN"
    }

    fun heading(mode: Mode, step: Step): String = when (step) {
        Step.Enter -> when (mode) {
            Mode.Set -> "设一个 6 位 PIN"
            Mode.Change -> "设一个新的 6 位 PIN"
        }
        Step.Confirm -> "再输一遍"
    }

    /**
     * 标题下面那一行。
     *
     * 第二步那句写的是「确认没有按错」而不是「请再次输入以确认」——
     * 后者是在描述这个界面的机制，前者才是在说这一步为什么存在。
     */
    fun caption(mode: Mode, step: Step): String = when (step) {
        Step.Enter -> "解锁时用它代替主密码。主密码不会变，也一直都能用。"
        Step.Confirm -> "确认刚才没有按错。"
    }

    fun submitText(step: Step): String = when (step) {
        Step.Enter -> "下一步"
        Step.Confirm -> "完成"
    }

    /** 按满六位按钮才亮（决策㉛的同一条规矩，这一页也照办）。 */
    fun canSubmit(filled: Int): Boolean = filled == LENGTH

    /**
     * 页面顶上那段说明。
     *
     * 三句话，一句都不能少，而且顺序是照用户的疑问顺序排的：
     *   1. 六位数字凭什么够用（因为它靠的是设备绑定，不是长度）；
     *   2. 输错了会怎么样（退避，不是删数据——这是密码管理器用户最怕的那件事）；
     *   3. 主密码还在不在（在，而且永远在）。
     */
    val INTRO: String =
        "PIN 只在这台设备上成立：它把库主密钥另外包了一份，外面再由这台手机的安全芯片锁住。" +
            "拷走保险库文件的人解不开它，只能拿着这台手机、在这个应用里一次次试。\n\n" +
            "所以六位就够了——但也正因为如此，输错会被越拖越久地拦住，" +
            "而不会删掉任何数据。主密码始终是唯一的真凭据，它一直都能打开保险库。"

    /**
     * 写不进去时说什么。
     *
     * 落点和 `QuickUnlockModel.enrollFailureMessage` 一致：**「这次没设上，
     * 库和数据都没受影响」**，而不是「请用主密码解锁」——用户此刻已经在库里了
     * （决策(102)）。
     */
    val ENROLL_FAILED: String =
        "这次没能设置 PIN。可以再试一次；保险库和里面的数据都没有受影响。"

    /**
     * 安全硬件那一侧没办成时说什么。
     *
     * 为什么不能都用 [ENROLL_FAILED]：那句话的落点是「可以再试一次」，
     * 而这一类失败**再试一百次也是同一个结果**——问题在这台设备的安全芯片
     * 不接受我们要求的规格，不在这一次操作。让用户对着一句「可以再试一次」
     * 反复按十二下，是我们在浪费他的时间。
     *
     * 所以这一句只说两件事：这台设备上这条路走不通，以及主密码照样开门。
     * 后半句是这一页所有失败文案的共同落点（决策(102)）：
     * 用户此刻已经在库里了，最需要确认的是「我的数据还在」。
     */
    val ENROLL_FAILED_KEYSTORE: String =
        "这台设备的安全硬件没能接受这份 PIN 绑定，PIN 在这台设备上暂时用不了。" +
            "这不影响保险库本身——主密码照样打开，里面的数据一条都没动。" +
            "如果这台设备支持指纹，可以改用指纹解锁。"

    /**
     * 库在半路锁掉了。
     *
     * 这不是故障，是自动锁定正常工作（用户设置 PIN 的中途切出去了一趟）。
     * 说清楚「重新解锁一次再来」，而不是让他以为 PIN 功能坏了。
     */
    val ENROLL_FAILED_LOCKED: String =
        "设置 PIN 需要保险库处于解锁状态，而它在中途锁上了。请重新解锁后再设一次。"

    /* ══════════════════════ 安全设置页上那一行 ══════════════════════ */

    /**
     * PIN 那一行要画成什么样。
     *
     * 和 [QuickUnlockModel.BiometricRow] 相比少了 `enabled` 和 `note` 两件事，
     * 这不是偷懒，是**PIN 真的没有那些情况**：它不依赖任何传感器，
     * 也不会因为系统里删了什么而失效——`enrollPin` 用的是设备绑定密钥，
     * 不是「每次使用都要认证」的那把（对比 `KeystoreKeys` 里两把钥匙的分工）。
     * 于是这一行永远能点，也永远没有需要解释的异常状态。
     *
     * 既然没有异常状态，这一行就**一句说明都不出**（决策(95)）。
     * 给一个从不出问题的开关配一句「一切正常」，读者学会的是跳过所有小字。
     *
     * @param changeText 「修改 PIN」入口的字。只在已设置时非 null——
     *                   没设过的时候摆一个「修改」，点进去却是设置流，那是在说胡话。
     */
    data class PinRow(
        val checked: Boolean,
        val subtitle: String,
        val changeText: String?,
    )

    fun pinRow(enrolled: Boolean): PinRow =
        if (enrolled) PinRow(
            checked = true,
            subtitle = "已开启 · 只在这台设备上",
            changeText = "修改 PIN",
        ) else PinRow(
            checked = false,
            subtitle = "未开启",
            changeText = null,
        )

    /* ══════════════════════ 判断规则 ══════════════════════ */

    /** CharArray → 每位的数值。位数不对或含非数字返回 null。 */
    private fun digitsOrNull(pin: CharArray): IntArray? {
        if (pin.size != LENGTH) return null
        val out = IntArray(LENGTH)
        for (i in 0 until LENGTH) {
            val c = pin[i]
            if (c < '0' || c > '9') return null
            out[i] = c - '0'
        }
        return out
    }

    private fun isAllSame(d: IntArray): Boolean = d.all { it == d[0] }

    /**
     * 一路 +1 或一路 −1，**首尾相接也算**。
     *
     * 算上环绕是有意的：`890123` 和 `901234` 在别人试的时候和 `123456` 是一档的东西，
     * 而「它跨过了 9」这件事只有写代码的人在意。
     */
    private fun isSequential(d: IntArray): Boolean {
        var up = true
        var down = true
        for (i in 1 until d.size) {
            if ((d[i - 1] + 1) % 10 != d[i]) up = false
            if ((d[i - 1] + 9) % 10 != d[i]) down = false
        }
        return up || down
    }

    /** 两位或三位一个循环。六位全一样已经被 [isAllSame] 先接走了。 */
    private fun isRepeating(d: IntArray): Boolean {
        for (p in intArrayOf(2, 3)) {
            var ok = true
            for (i in p until d.size) if (d[i] != d[i - p]) ok = false
            if (ok) return true
        }
        return false
    }

    /**
     * 常见名单。存成 IntArray 而不是字符串，是为了和 [weakness] 的入参对得上——
     * 比对全程只碰数字，不会为了「方便比一下」把用户的 PIN 拼成一个 String
     * （那个 String 会一直留在堆里等 GC，这正是 `PinBuffer` 当初要躲开的东西）。
     *
     * 名单只收**别的规则接不住**的那些：`123456`（连号）、`121212`（循环）
     * 这类不必重复列，列了也永远轮不到它们。
     */
    private val WELL_KNOWN: List<IntArray> = listOf(
        // 数字键盘上的竖线、斜线，正着倒着各一遍
        intArrayOf(1, 4, 7, 2, 5, 8), intArrayOf(2, 5, 8, 3, 6, 9),
        intArrayOf(3, 6, 9, 1, 4, 7), intArrayOf(1, 5, 9, 3, 5, 7),
        intArrayOf(3, 5, 7, 1, 5, 9), intArrayOf(9, 5, 1, 7, 5, 3),
        intArrayOf(7, 5, 3, 9, 5, 1), intArrayOf(9, 6, 3, 8, 5, 2),
        intArrayOf(8, 5, 2, 9, 6, 3), intArrayOf(1, 4, 7, 3, 6, 9),
        // 整行整行地按
        intArrayOf(7, 8, 9, 4, 5, 6), intArrayOf(4, 5, 6, 1, 2, 3),
        intArrayOf(1, 2, 3, 7, 8, 9),
        // 成对递增 / 递减
        intArrayOf(1, 1, 2, 2, 3, 3), intArrayOf(9, 9, 8, 8, 7, 7),
        intArrayOf(1, 2, 2, 3, 3, 4),
        // 对称
        intArrayOf(1, 2, 3, 3, 2, 1), intArrayOf(6, 5, 4, 4, 5, 6),
        intArrayOf(1, 2, 3, 6, 5, 4),
        // 整十
        intArrayOf(1, 0, 2, 0, 3, 0), intArrayOf(1, 0, 0, 2, 0, 0),
    )

    private fun isWellKnown(d: IntArray): Boolean = WELL_KNOWN.any { it.contentEquals(d) }

    /**
     * 看着像日期。
     *
     * 认四种排法：`YYMMDD` `DDMMYY` `MMDDYY` `YYYYMM`。
     *
     * ── 这一条明显偏宽，是有意的 ──
     *
     * 按这几条规则，全部一百万个六位数里约 8.8% 会被认成日期
     * （连同前面几条规则，一共约 8.9% 会被提醒），里面必然混着一些其实是随机敲出来的。
     * 但这一条**只是提醒，不是拦截**——认错了的代价是多看一次弹窗，按次按钮就过；
     * 认漏了的代价是一个用生日当 PIN 的人什么提示都没收到，
     * 而那正是这一整条规则唯一存在的理由。
     * 真实分布里生日样式的占比远高于 8.8%，两边并不对称。
     *
     * 不检查「这一天是否真的存在」（2 月 31 日照样算日期样式）：
     * 一个把 `900231` 设成 PIN 的人，多半也是照着生日的样子敲的，
     * 而认识他的人猜的时候同样会往那个方向猜。历法在这儿帮不上忙。
     */
    private fun isDateLike(d: IntArray): Boolean {
        val p1 = d[0] * 10 + d[1]
        val p2 = d[2] * 10 + d[3]
        val p3 = d[4] * 10 + d[5]
        val yyyy = p1 * 100 + p2
        return (isMonth(p2) && isDay(p3)) ||          // YYMMDD
            (isDay(p1) && isMonth(p2)) ||             // DDMMYY
            (isMonth(p1) && isDay(p2)) ||             // MMDDYY
            (yyyy in 1900..2099 && isMonth(p3))       // YYYYMM
    }

    private fun isMonth(v: Int) = v in 1..12
    private fun isDay(v: Int) = v in 1..31
}
