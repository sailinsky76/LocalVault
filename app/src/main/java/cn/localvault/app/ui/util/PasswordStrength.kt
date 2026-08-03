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

package cn.localvault.app.ui.util

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 密码强度评估。
 *
 * 三个刻意的取舍：
 *
 * **① 不引入 zxcvbn。** 它带一个几百 KB 的词表，而对中文用户最有价值的
 * 弱口令（`woaini1314`、生日、手机号、`5201314`）恰恰不在它的英文词表里。
 * 这里用「字符集熵 − 规律性惩罚 + 本地弱口令名单」的组合，体积几乎为零，
 * 对真实用户的误判反而更少。
 *
 * **② 全程 CharArray，不产生 String。** 强度条要随用户每敲一个键实时更新，
 * 如果每次都 `String(chars)`，一个 12 位密码敲完就在堆里留下 12 个
 * 擦不掉的 String 副本 —— 这是密码管理器里最容易被忽略的泄露面。
 *
 * **③ 给出的是数量级，不是精确值。** 熵的估算永远只是估算，
 * 所以界面上只显示四档和一句人话建议，不显示「73.4 bit」这种
 * 会让用户误以为很精确的数字。
 */
object PasswordStrength {

    enum class Level { Weak, Fair, Good, Strong }

    data class Result(
        val bits: Int,
        val level: Level,
        /** 一句给用户看的话。已经够强时是肯定句，不够强时是可执行的建议。 */
        val hint: String,
    )

    /** 主密码的建议下限。低于它建库时会拦一道（可以强行继续，但要明确点确认）。 */
    const val MASTER_MIN_BITS = 60

    /**
     * 主密码的**硬**下限（字符数）。低于它连提交都不给，弹窗也绕不过去。
     *
     * 放在这儿而不是各页面自己写一个私有常量：设主密码的地方有两处
     * （建库、改密码），两处用的门槛必须是同一个数。分开写的话，
     * 哪天有人只调其中一处，结果就是「建库要 10 位，改密码 8 位就过」——
     * 用户可以通过改密码把主密码降到建库时不允许的强度，
     * 而屏幕上没有任何地方会提到这件事。
     */
    const val MASTER_MIN_LENGTH = 8

    fun evaluate(pw: CharArray): Result {
        val n = pw.size
        if (n == 0) return Result(0, Level.Weak, "还没有输入")

        // ── 字符集规模 ──
        var lower = false; var upper = false; var digit = false; var symbol = false; var wide = false
        for (c in pw) {
            when {
                c in 'a'..'z' -> lower = true
                c in 'A'..'Z' -> upper = true
                c in '0'..'9' -> digit = true
                c.code < 128 -> symbol = true
                else -> wide = true          // 中文等非 ASCII，按保守的 40 计
            }
        }
        var pool = 0
        if (lower) pool += 26
        if (upper) pool += 26
        if (digit) pool += 10
        if (symbol) pool += 33
        if (wide) pool += 40
        if (pool == 0) pool = 26

        var bits = n * log2(pool.toDouble())

        // ── 规律性惩罚 ──
        // 重复字符：aaaa / 121212 这种，实际熵远低于长度暗示的水平
        val distinct = distinctCount(pw)
        if (distinct < n) {
            bits *= (0.45 + 0.55 * distinct.toDouble() / n)
        }
        // 连续递增/递减：abcdef、123456、qwerty 的字母表相邻段
        val runLen = longestSequentialRun(pw)
        if (runLen >= 3) bits -= (runLen - 2) * log2(pool.toDouble()) * 0.8

        // 纯数字：口令空间只有 10^n，无论多长都要如实反映
        if (digit && !lower && !upper && !symbol && !wide) {
            bits = min(bits, n * log2(10.0))
            if (n <= 8) bits -= 6      // 生日 / 短数字串是被优先爆破的
        }

        // ── 弱口令名单 ──
        if (matchesCommon(pw)) bits = min(bits, 14.0)

        val b = max(0.0, bits).toInt()
        val level = when {
            b < 40 -> Level.Weak
            b < 60 -> Level.Fair
            b < 80 -> Level.Good
            else -> Level.Strong
        }
        return Result(b, level, hintFor(level, n, lower, upper, digit, symbol, wide))
    }

    private fun hintFor(
        level: Level, n: Int,
        lower: Boolean, upper: Boolean, digit: Boolean, symbol: Boolean, wide: Boolean,
    ): String = when (level) {
        Level.Strong -> "足够作为主密码"
        Level.Good -> "可以用，再长几位会更稳"
        Level.Fair -> when {
            n < 12 -> "太短了，主密码建议 16 位以上"
            !symbol && !wide -> "加几个符号，或者改用四五个不相干的词拼起来"
            else -> "再长一些"
        }
        Level.Weak -> when {
            n < 8 -> "太短，文件被拷走就能离线爆破"
            digit && !lower && !upper -> "纯数字撑不住，换成词组"
            else -> "试试四五个不相干的词拼起来，比乱码好记也更长"
        }
    }

    // ───────────────────── 辅助 ─────────────────────

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun distinctCount(pw: CharArray): Int {
        // 密码长度有限，O(n²) 完全够用，好处是不需要建 Set（免掉装箱和额外副本）
        var count = 0
        outer@ for (i in pw.indices) {
            for (j in 0 until i) if (pw[j] == pw[i]) continue@outer
            count++
        }
        return count
    }

    /** 最长的「码位每步 ±1」连续段长度，abcd / 4321 都算 */
    private fun longestSequentialRun(pw: CharArray): Int {
        if (pw.size < 2) return pw.size
        var best = 1; var cur = 1; var dir = 0
        for (i in 1 until pw.size) {
            val d = pw[i].code - pw[i - 1].code
            if ((d == 1 || d == -1) && (cur == 1 || d == dir)) {
                cur++; dir = d
            } else {
                cur = 1; dir = 0
            }
            if (cur > best) best = cur
        }
        return best
    }

    /**
     * 弱口令名单。刻意小而准：只放真正高频的，
     * 名单太大反而会把「passion」这种合理词根误伤。
     * 中文用户的高频组合单独列，这是英文词表覆盖不到的部分。
     */
    private val COMMON = arrayOf(
        "123456", "1234567", "12345678", "123456789", "1234567890", "12345",
        "111111", "000000", "888888", "666666", "123123", "112233", "121212",
        "password", "passw0rd", "qwerty", "qwertyuiop", "abc123", "a123456",
        "iloveyou", "admin", "admin123", "root", "letmein", "monkey", "dragon",
        "woaini", "woaini1314", "5201314", "1314520", "aa123456", "zxcvbnm",
        "asdasd", "qazwsx", "wang123", "123qwe", "1qaz2wsx", "abcd1234",
    )

    /** 不区分大小写地比对，且不把用户输入变成 String */
    private fun matchesCommon(pw: CharArray): Boolean {
        for (candidate in COMMON) {
            if (candidate.length != pw.size) continue
            var same = true
            for (i in pw.indices) {
                if (lower(pw[i]) != candidate[i]) { same = false; break }
            }
            if (same) return true
        }
        return false
    }

    private fun lower(c: Char) = if (c in 'A'..'Z') c + 32 else c
}
