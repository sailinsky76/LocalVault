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

package cn.localvault.app.ui.generate

import cn.localvault.app.core.crypto.Rng
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * 密码生成器的内核。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose**，
 * 和 [cn.localvault.app.ui.list.VaultIndex]、[cn.localvault.app.ui.edit.EntryForm]、
 * [cn.localvault.app.ui.unlock.UnlockGuard] 是同一个套路：
 * 凡是「说不清就会悄悄变错、而界面上又看不出来」的东西，都落到一个可单测的对象里。
 *
 * 生成器尤其需要这样，因为**它错了不会有任何表现**。
 * 一个有偏差的随机数、一个漏掉的字符类、一个忘了洗牌的补位循环，
 * 生成出来的东西看上去和正确实现一模一样——都是一串乱码。
 * 用户不可能发现，只有测试能发现。
 *
 * ── 随机源从哪来 ──
 *
 * [generate] 的随机源是一个参数（`rnd: (Int) -> Int`），默认是
 * [Rng.int]。这不是为了「解耦」这种空话，是为了让测试能钉死上面那几条：
 * 传一个固定序列进去，就能验证「补位之后确实洗过牌」；
 * 传一个记录调用的假实现进去，就能验证「我们从没向随机源要过 0 边界」。
 *
 * 线上那一份走的是 [Rng.int]——`SecureRandom` + 拒绝采样，无模偏差。
 * **绝不允许在这个文件里出现 `kotlin.random.Random`**，
 * 它是可预测的，而且默认种子和进程启动时间相关。
 */
object PasswordGen {

    /**
     * 两种模式，用途完全不同，不是「同一件事的两种风格」：
     *
     * - [Mode.Random] 是默认，给**从不手打的密码**用：存进库里、靠自动填充或复制粘贴使用。
     *   这种场合没有任何理由让它好记，所以一切让位给熵。
     * - [Mode.Readable] 给**必须靠人手抄或者口述的密码**用：WiFi 密码、
     *   要用电视遥控器一个一个选出来的密码、报给家人的密码。
     *   它更弱，这一点界面上如实写出来（见 [entropyBits]），但它比
     *   「用户被一串符号吓退之后自己改成 `wangwang123`」强得多。
     */
    enum class Mode { Random, Readable }

    data class Options(
        val mode: Mode = Mode.Random,

        // ── 随机模式 ──
        val length: Int = DEFAULT_LENGTH,
        val lower: Boolean = true,
        val upper: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        /** 避开在等宽字体里分不清的那几个字符。要手抄时才打开，它会削掉一点熵。 */
        val avoidAmbiguous: Boolean = false,

        // ── 易读模式 ──
        val syllables: Int = DEFAULT_SYLLABLES,
        /** 末尾加两位数字。很多路由器和老系统硬性要求密码里有数字。 */
        val trailingDigits: Boolean = true,
    )

    /**
     * 长度下限 8：低于这个数的密码在今天没有任何使用场景，
     * 提供一个 6 位选项只会让某些用户去选它。
     * 上限 64：再长就开始撞上各种网站「密码不能超过 N 位」的静默截断——
     * 那种截断最恶劣的地方在于**注册时截断了、登录时也截断，所以能登进去**，
     * 用户永远不知道自己实际用的是前 16 位。
     */
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 64
    const val DEFAULT_LENGTH = 20

    const val MIN_SYLLABLES = 4
    const val MAX_SYLLABLES = 14
    const val DEFAULT_SYLLABLES = 8

    const val TRAILING_DIGIT_COUNT = 2

    /** 易读模式每几个音节断一次。断开只是为了眼睛好认，不影响熵。 */
    const val GROUP_SYLLABLES = 2

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"

    /**
     * 符号集是**挑过的**，不是「键盘上所有符号」。
     *
     * 被排除掉的是这几类，每一类都有具体的翻车场景：
     *
     * - **引号（`'` `"`）和反斜杠**：这三个字符是所有转义 bug 的源头。
     *   网站后端处理错了，表现通常不是报错，而是**注册时存进去一个样子、
     *   登录时比对成另一个样子**，于是用户拿着正确的密码登不进去，
     *   而他手上这个密码管理器言之凿凿地说密码没错。
     * - **空格**：粘贴时首尾空格会被各种输入框静默 trim 掉
     *   （我们自己在 [cn.localvault.app.ui.edit.EntryForm] 里刻意不 trim 密码，
     *   但别人的网站会）。
     * - **反引号和 `<` `>` `|` `&`**：这串密码经常会被粘进 shell、配置文件、
     *   环境变量。生成一个能被 shell 展开的密码是在给用户挖坑。
     *
     * 剩下的这 22 个在任何地方都不会有歧义。少几个符号带来的熵损失
     * （每位大约 0.1 bit）远小于「有 1% 的概率生成一个用不了的密码」的代价。
     */
    private const val SYMBOLS = "!@#\$%^*()-_=+[]{};:,.?"

    /**
     * 易混字符。**只剔在等宽字体里真的分不清的那五个**。
     *
     * 很多生成器把 `5S`、`2Z`、`8B`、`6G`、`9q` 一并剔掉，
     * 那会把字符池砍掉近四分之一（每位少 0.4 bit 左右），换来的是
     * 一个在任何等宽字体里都本来就分得清的差别。
     * 这个开关的用途是「我要把它抄到纸上 / 念给别人听」，不是「让密码变好看」。
     */
    private const val AMBIGUOUS = "0O1lI"

    /**
     * 易读模式的音节表：拼音的声母 × 韵母。
     *
     * ── 为什么不内置一份词表 ──
     *
     * 业界的做法是 diceware：一份 2048 词的英文词表，每词 11 bit。
     * 这里不这么做，两个理由：
     *
     *  1. **体积**。一份够用的词表至少十几 KB，而这个 App 的卖点之一
     *     就是小到可以整个读一遍。为了一个次要模式背上一张词表不划算。
     *  2. **对中文用户没用**。`correct-horse-battery-staple` 好记，
     *     是因为读它的人认识这四个英文词。对一个中文用户来说，
     *     `vigilant-plumage-thicket` 和一串乱码的记忆成本几乎一样，
     *     而拼音音节他念一遍就能复述出来——`bamo-tenlai` 是可以在电话里
     *     报给家人的，`vigilant-plumage` 不是。
     *
     * 声母里去掉了 `j q x`：它们在拼音里只跟 `i / ü` 相拼，
     * 配上 `a / ou / ang` 会拼出念不出来的东西。剩下 18 × 14 = 252 种音节，
     * 每个音节接近 8 bit——和一个 diceware 词的 11 bit 不算差太远，
     * 而且一个音节只有 2–4 个字符。
     *
     * 不保证生成的每个音节都是**存在的**汉字读音（`fong`、`ceng` 之类会出现），
     * 但都读得出来，这已经够用了。
     */
    private val INITIALS = listOf(
        "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "zh", "ch", "sh", "r", "z", "c", "s",
    )
    private val FINALS = listOf(
        "a", "o", "e", "ai", "ei", "ao", "ou",
        "an", "en", "ang", "eng", "ong", "i", "u",
    )

    /* ══════════════════════════ 选项的合法化 ══════════════════════════ */

    /**
     * 把选项收进合法范围。**内核自己兜住，不指望界面兜住。**
     *
     * 最要紧的是那句 `if (anyClass) o.lower else true`：
     * 用户可以把四个字符类全部关掉（界面会拦，但内核不能依赖界面拦得住），
     * 那样字符池就是空的，`pool[rnd(0)]` 会直接抛异常——
     * 在生成密码这个动作上抛异常，用户看到的是「点了没反应」。
     * 与其如此，不如强制打开小写：**永远返回一个能用的密码**。
     *
     * 这个函数必须是幂等的（`normalized(normalized(x)) == normalized(x)`），
     * 由单测盯着——不幂等的话，界面上每重组一次状态，长度就可能又被夹一次。
     */
    fun normalized(o: Options): Options {
        val anyClass = o.lower || o.upper || o.digits || o.symbols
        return o.copy(
            length = o.length.coerceIn(MIN_LENGTH, MAX_LENGTH),
            lower = if (anyClass) o.lower else true,
            syllables = o.syllables.coerceIn(MIN_SYLLABLES, MAX_SYLLABLES),
        )
    }

    /** 打开的那几类字符，各自已经按「避开易混」过滤过。 */
    private fun classesOf(o: Options): List<String> {
        val out = ArrayList<String>(4)
        if (o.lower) out.add(sift(LOWER, o.avoidAmbiguous))
        if (o.upper) out.add(sift(UPPER, o.avoidAmbiguous))
        if (o.digits) out.add(sift(DIGITS, o.avoidAmbiguous))
        // 符号里本来就没有易混字符，但照样走一遍，免得将来往 SYMBOLS 里
        // 加一个 `|` 的时候，这条规矩在符号上悄悄失效。
        if (o.symbols) out.add(sift(SYMBOLS, o.avoidAmbiguous))
        return out
    }

    private fun sift(s: String, avoid: Boolean): String =
        if (!avoid) s else s.filter { it !in AMBIGUOUS }

    /** 字符池大小，界面上如实显示给用户看。 */
    fun poolSize(o: Options): Int = classesOf(normalized(o)).sumOf { it.length }

    /** 界面上把符号集原样列出来，用户才知道自己的密码里可能出现什么。 */
    fun symbolSet(): String = SYMBOLS

    /* ══════════════════════════ 生成 ══════════════════════════ */

    fun generate(o: Options, rnd: (Int) -> Int = Rng::int): String {
        val n = normalized(o)
        return when (n.mode) {
            Mode.Random -> generateRandom(n, rnd)
            Mode.Readable -> generateReadable(n, rnd)
        }
    }

    /**
     * ── 「每一类至少出现一次」必须靠洗牌，不能靠事后补 ──
     *
     * 很多网站硬性要求「必须包含大写字母和数字」，所以我们保证每个打开的类
     * 都至少出现一次。**但实现方式非常要命**：
     *
     * 天真的做法是「先纯随机生成，检查发现缺了数字，就把第 0 位换成一个数字」。
     * 那样生成出来的密码，**第 0 位是数字的概率被人为拉高了一大截**，
     * 而且这种偏差在屏幕上完全看不出来——每一个单独的密码看起来都很随机。
     * 攻击者知道这个软件的实现（它是开源的），于是可以按这个分布优先爆破。
     *
     * 正确做法是这里的三步：每类先抽一个占住位置 → 剩下的位置从整池抽 →
     * **整体 Fisher–Yates 洗一遍**。洗牌之后，每个位置的边缘分布都一样。
     *
     * `max(length, classes.size)` 那一句是兜底：长度下限 8 大于类别数 4，
     * 正常永远走不到，但如果将来有人把 [MIN_LENGTH] 调低到 3，
     * 这里不至于写出数组越界。
     */
    private fun generateRandom(o: Options, rnd: (Int) -> Int): String {
        val classes = classesOf(o)
        val pool = classes.joinToString("")
        val len = max(o.length, classes.size)

        val chars = CharArray(len)
        var i = 0
        for (c in classes) chars[i++] = c[rnd(c.length)]
        while (i < len) chars[i++] = pool[rnd(pool.length)]

        shuffle(chars, rnd)
        return String(chars)
    }

    /** Fisher–Yates。`rnd(i + 1)` 的上界含 i，少写这个 +1 就是最经典的洗牌 bug。 */
    private fun shuffle(a: CharArray, rnd: (Int) -> Int) {
        for (i in a.size - 1 downTo 1) {
            val j = rnd(i + 1)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
    }

    private fun generateReadable(o: Options, rnd: (Int) -> Int): String {
        val sb = StringBuilder()
        for (k in 0 until o.syllables) {
            if (k > 0 && k % GROUP_SYLLABLES == 0) sb.append('-')
            sb.append(INITIALS[rnd(INITIALS.size)])
            sb.append(FINALS[rnd(FINALS.size)])
        }
        if (o.trailingDigits) {
            sb.append('-')
            repeat(TRAILING_DIGIT_COUNT) { sb.append(DIGITS[rnd(DIGITS.length)]) }
        }
        return sb.toString()
    }

    /* ══════════════════════════ 熵 ══════════════════════════ */

    /**
     * 生成器显示的是**算出来的熵，不是猜出来的强度**。
     *
     * [cn.localvault.app.ui.util.PasswordStrength] 那一套是给用户自己打的密码用的：
     * 面对一个来路不明的字符串，只能从字符集和规律性去**估**。
     * 而这里的密码是我们刚生成的，生成规则完全已知，熵可以直接算准。
     *
     * 拿 `evaluate()` 去评自己生成的密码，除了多此一举还会**报低**：
     * 一个真随机的 20 位密码里出现 `abc` 或者两个相同字符是完全正常的，
     * 而那套估算会因此扣分。于是用户会看到「刚生成的密码强度是『较强』」，
     * 然后合理地怀疑这个生成器是不是有问题。
     *
     * ── 随机模式为什么要用容斥 ──
     *
     * 直觉答案是 `length × log2(池大小)`，但那个数**偏高**：
     * 「每类至少出现一次」这条约束把可取的字符串减少了
     * （所有不含数字的串都被排除在外）。容斥原理能把满足约束的串数算准：
     *
     *     N = Σ (-1)^|S| × (池 − S 中各类之和)^len
     *
     * 类别最多 4 个，16 项，一次乘方就够。这个差别在短密码上很明显：
     * 8 位 + 四类全开时，天真算法报 52 bit，实际是 51 bit 出头。
     * 报高了不算「保守估计」——它会让一个刚好卡在门槛上的密码显示成「强」。
     */
    fun entropyBits(o: Options): Int {
        val n = normalized(o)
        val bits = when (n.mode) {
            Mode.Random -> randomEntropy(n)
            Mode.Readable -> readableEntropy(n)
        }
        return bits.toInt()
    }

    private fun randomEntropy(o: Options): Double {
        val classes = classesOf(o)
        val len = max(o.length, classes.size)
        val pool = classes.sumOf { it.length }
        val k = classes.size

        var total = 0.0
        for (mask in 0 until (1 shl k)) {
            var excluded = 0
            var picked = 0
            for (i in 0 until k) {
                if ((mask shr i) and 1 == 1) {
                    excluded += classes[i].length
                    picked++
                }
            }
            val term = (pool - excluded).toDouble().pow(len)
            total += if (picked % 2 == 0) term else -term
        }
        if (total <= 1.0) return 0.0
        return log2(total)
    }

    private fun readableEntropy(o: Options): Double {
        val perSyllable = log2((INITIALS.size * FINALS.size).toDouble())
        val digits = if (o.trailingDigits) TRAILING_DIGIT_COUNT * log2(10.0) else 0.0
        // 连字符是固定位置的固定字符，一个 bit 都不贡献 —— 不许把它算进去。
        return o.syllables * perSyllable + digits
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    /* ══════════════════════════ 显示用的分类 ══════════════════════════ */

    /**
     * 生成结果**按字符类别分色显示**（字母、数字、符号三色），
     * 这不是装饰，是给「照着抄」用的：
     * 一串 20 位的等宽乱码里找出「第几个是符号」，靠颜色比靠眼睛快得多。
     * 易读模式里的连字符也归 [Kind.Symbol]，于是分组一眼可见。
     */
    enum class Kind { Letter, Digit, Symbol }

    fun kindOf(c: Char): Kind = when {
        c in '0'..'9' -> Kind.Digit
        c in 'a'..'z' || c in 'A'..'Z' -> Kind.Letter
        else -> Kind.Symbol
    }
}
