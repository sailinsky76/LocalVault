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

import cn.localvault.app.ui.generate.PasswordGen
import cn.localvault.app.ui.generate.PasswordGen.Mode
import cn.localvault.app.ui.generate.PasswordGen.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * 密码生成器的内核。
 *
 * 这个文件存在的理由，比工程里其它任何一个测试文件都直白：
 * **生成器出错的时候，屏幕上什么都看不出来。**
 *
 * 一个漏掉的洗牌、一个多算的 bit、一个在「避开易混字符」时忘了过滤的类，
 * 生成出来的东西看上去和正确实现完全一样——都是一串没人认识的乱码。
 * 用户不可能发现，代码走查也很容易看漏（那几行都很短、很像对的）。
 * 所以这里盯着四件事：
 *
 *  - **每个位置的分布是一样的**（补位之后必须整体洗牌，不能事后替换固定位置）；
 *  - **开关说到做到**（关掉符号就一个符号都不许有，避开易混就一个易混字符都不许有）；
 *  - **熵是算出来的、而且不许报高**（报高的强度比不报强度更危险）；
 *  - **永远返回一个能用的密码**（四类全关也不许抛异常）。
 */
class PasswordGenTest {

    /** 固定序列的假随机源：总是返回序列里的下一个数，对上界取模。 */
    private class FakeRnd(vararg values: Int) : (Int) -> Int {
        private val seq: IntArray = values
        private var calls = 0
        val bounds = ArrayList<Int>()
        override fun invoke(bound: Int): Int {
            bounds.add(bound)
            val v = seq[calls % seq.size]
            calls++
            return ((v % bound) + bound) % bound
        }
    }

    /** 真随机源。用来跑「重复 N 次都必须成立」的性质。 */
    private val real: (Int) -> Int = { cn.localvault.app.core.crypto.Rng.int(it) }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    /* ───────────────────── 长度与字符类 ───────────────────── */

    @Test
    fun `长度就是要的那个长度`() {
        for (len in listOf(8, 12, 20, 33, 64)) {
            val pw = PasswordGen.generate(Options(length = len), real)
            assertEquals(len, pw.length)
        }
    }

    @Test
    fun `长度被夹进合法范围`() {
        assertEquals(PasswordGen.MIN_LENGTH, PasswordGen.generate(Options(length = 1), real).length)
        assertEquals(PasswordGen.MAX_LENGTH, PasswordGen.generate(Options(length = 9999), real).length)
    }

    @Test
    fun `打开的每一类都至少出现一次`() {
        repeat(200) {
            val pw = PasswordGen.generate(Options(length = 8), real)
            assertTrue("缺小写: $pw", pw.any { it in 'a'..'z' })
            assertTrue("缺大写: $pw", pw.any { it in 'A'..'Z' })
            assertTrue("缺数字: $pw", pw.any { it in '0'..'9' })
            assertTrue("缺符号: $pw", pw.any { PasswordGen.kindOf(it) == PasswordGen.Kind.Symbol })
        }
    }

    @Test
    fun `关掉的类一个字符都不许出现`() {
        repeat(100) {
            val pw = PasswordGen.generate(
                Options(length = 30, upper = false, symbols = false),
                real,
            )
            assertFalse("不该有大写: $pw", pw.any { it in 'A'..'Z' })
            assertFalse(
                "不该有符号: $pw",
                pw.any { PasswordGen.kindOf(it) == PasswordGen.Kind.Symbol },
            )
            assertTrue(pw.all { it in 'a'..'z' || it in '0'..'9' })
        }
    }

    @Test
    fun `只留数字时生成的是纯数字`() {
        val pw = PasswordGen.generate(
            Options(length = 12, lower = false, upper = false, symbols = false),
            real,
        )
        assertTrue(pw.all { it in '0'..'9' })
        assertEquals(12, pw.length)
    }

    /* ───────────────────── 避开易混字符 ───────────────────── */

    @Test
    fun `避开易混字符时那几个字符一个都不出现`() {
        repeat(300) {
            val pw = PasswordGen.generate(Options(length = 40, avoidAmbiguous = true), real)
            for (c in "0O1lI") {
                assertFalse("不该出现 $c: $pw", pw.contains(c))
            }
        }
    }

    @Test
    fun `不避开时那几个字符是能出现的`() {
        // 反向用例：如果有人把过滤写死成「永远过滤」，上一个用例照样通过，
        // 而用户会白白损失一截熵，并且屏幕上完全看不出来。
        val seen = HashSet<Char>()
        repeat(300) {
            PasswordGen.generate(Options(length = 40), real).forEach { seen.add(it) }
        }
        assertTrue("0O1lI 一个都没出现过，过滤大概是写死了", "0O1lI".any { it in seen })
    }

    @Test
    fun `避开易混字符会让字符池变小`() {
        val full = PasswordGen.poolSize(Options())
        val sifted = PasswordGen.poolSize(Options(avoidAmbiguous = true))
        assertEquals(26 + 26 + 10 + PasswordGen.symbolSet().length, full)
        assertEquals(full - 5, sifted)
    }

    /* ───────────────────── 洗牌 ───────────────────── */

    @Test
    fun `补位之后确实洗过牌`() {
        /*
         * 假随机源永远返回 0。于是：
         *   - 每类抽到的都是该类的第一个字符：a A 0 !
         *   - 补位抽到的都是整池的第一个字符：a
         *   - 洗牌时 j 恒为 0，Fisher-Yates 会把首字符一路换到末尾
         *
         * 关键在最后一条：**没洗牌的话，结果必然是 "aA0!aaaa"**（四类原封不动排在最前）。
         * 洗过牌之后，那四个占位字符不可能还整整齐齐待在开头。
         * 这是唯一一个能在纯逻辑上抓住「忘了洗牌」的办法——
         * 用真随机源的话，两种实现的输出都是一串乱码，肉眼和断言都分不出来。
         */
        val pw = PasswordGen.generate(Options(length = 8), FakeRnd(0))
        assertEquals(8, pw.length)
        assertNotEquals("aA0!aaaa", pw)
        // 洗牌只是换位置，不该改变字符的多重集合
        assertEquals("aA0!aaaa".toCharArray().sorted(), pw.toCharArray().sorted())
    }

    @Test
    fun `洗牌不会弄丢或凭空多出字符`() {
        repeat(50) {
            val pw = PasswordGen.generate(Options(length = 16), real)
            assertEquals(16, pw.length)
            assertEquals(16, pw.toCharArray().size)
        }
    }

    @Test
    fun `永远不向随机源要 0 边界`() {
        // rnd(0) 在 Rng.int 里会 require 失败。真正会触发它的是
        // 「某个字符类被过滤成空」这类将来才会写出来的 bug，
        // 那时候用户看到的是「点了生成没反应」。
        val fake = FakeRnd(3, 7, 1, 5)
        PasswordGen.generate(Options(length = 24, avoidAmbiguous = true), fake)
        assertTrue(fake.bounds.isNotEmpty())
        assertTrue("向随机源要了非正的上界", fake.bounds.all { it > 0 })
    }

    /* ───────────────────── 选项合法化 ───────────────────── */

    @Test
    fun `四类全关时强制打开小写而不是抛异常`() {
        val o = Options(lower = false, upper = false, digits = false, symbols = false)
        val n = PasswordGen.normalized(o)
        assertTrue(n.lower)
        val pw = PasswordGen.generate(o, real)
        assertEquals(PasswordGen.DEFAULT_LENGTH, pw.length)
        assertTrue(pw.all { it in 'a'..'z' })
    }

    @Test
    fun `合法化是幂等的`() {
        val weird = Options(length = 999, syllables = 1, lower = false, upper = false, digits = false, symbols = false)
        val once = PasswordGen.normalized(weird)
        val twice = PasswordGen.normalized(once)
        assertEquals(once, twice)
    }

    /* ───────────────────── 熵 ───────────────────── */

    @Test
    fun `熵随长度单调增`() {
        var prev = 0
        for (len in PasswordGen.MIN_LENGTH..PasswordGen.MAX_LENGTH) {
            val bits = PasswordGen.entropyBits(Options(length = len))
            assertTrue("长度 $len 的熵没有增加", bits > prev)
            prev = bits
        }
    }

    @Test
    fun `熵不许报得比天真上界还高`() {
        // 「每类至少出现一次」是一条约束，约束只会让可取的串变少。
        // 报高了不是「保守估计」——它会让一个刚卡在门槛上的密码显示成「强」。
        for (len in listOf(8, 10, 16, 20, 64)) {
            val o = Options(length = len)
            val naive = len * log2(PasswordGen.poolSize(o).toDouble())
            assertTrue(PasswordGen.entropyBits(o) <= naive.toInt())
        }
    }

    @Test
    fun `短密码上约束造成的损失看得见`() {
        // 8 位四类全开：天真算法 52 bit，容斥算出来要少一点。
        val o = Options(length = 8)
        val naive = 8 * log2(PasswordGen.poolSize(o).toDouble())
        assertTrue(PasswordGen.entropyBits(o) < naive.toInt())
    }

    @Test
    fun `长密码上约束几乎不再有影响`() {
        // 反过来：64 位时那条约束几乎排除不掉什么，两者应该只差 1 bit 以内。
        val o = Options(length = 64)
        val naive = 64 * log2(PasswordGen.poolSize(o).toDouble())
        assertTrue(naive - PasswordGen.entropyBits(o) < 1.5)
    }

    @Test
    fun `关掉字符类熵会变小`() {
        val full = PasswordGen.entropyBits(Options(length = 20))
        val noSymbols = PasswordGen.entropyBits(Options(length = 20, symbols = false))
        val onlyLower = PasswordGen.entropyBits(
            Options(length = 20, upper = false, digits = false, symbols = false),
        )
        assertTrue(noSymbols < full)
        assertTrue(onlyLower < noSymbols)
    }

    @Test
    fun `默认选项的熵够用`() {
        // 20 位四类全开应该稳稳在「强」那一档（PasswordStrength 的门槛是 80）。
        assertTrue(PasswordGen.entropyBits(Options()) >= 80)
    }

    /* ───────────────────── 易读模式 ───────────────────── */

    @Test
    fun `易读模式只含小写字母 连字符和末尾数字`() {
        repeat(100) {
            val pw = PasswordGen.generate(Options(mode = Mode.Readable), real)
            assertTrue("出现了意料之外的字符: $pw", pw.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
            assertFalse("易读模式里不该有大写: $pw", pw.any { it in 'A'..'Z' })
        }
    }

    @Test
    fun `易读模式的音节数和分组数对得上`() {
        val pw = PasswordGen.generate(
            Options(mode = Mode.Readable, syllables = 8, trailingDigits = false),
            real,
        )
        // 8 个音节、每 2 个断一次 = 4 组 = 3 个连字符
        assertEquals(3, pw.count { it == '-' })
        val groups = pw.split("-")
        assertEquals(4, groups.size)
        assertTrue(groups.all { it.isNotEmpty() })
    }

    @Test
    fun `末尾数字开着时正好两位数字并且单独一组`() {
        val pw = PasswordGen.generate(
            Options(mode = Mode.Readable, syllables = 6, trailingDigits = true),
            real,
        )
        val tail = pw.substringAfterLast('-')
        assertEquals(PasswordGen.TRAILING_DIGIT_COUNT, tail.length)
        assertTrue(tail.all { it in '0'..'9' })
        // 数字只出现在末尾那一组里
        assertEquals(PasswordGen.TRAILING_DIGIT_COUNT, pw.count { it in '0'..'9' })
    }

    @Test
    fun `易读模式比同样长度的随机模式弱 而且弱得诚实`() {
        val readable = Options(mode = Mode.Readable, syllables = 8)
        val bits = PasswordGen.entropyBits(readable)
        // 8 个音节 × log2(18×14) + 2 位数字 ≈ 63 + 6.6
        assertTrue(bits in 66..72)

        // 生成出来的串长度在 30 上下，而同样 30 位的随机密码熵接近 190。
        // 这个差距必须留在数据里，界面才有可能如实告诉用户。
        val pw = PasswordGen.generate(readable, real)
        assertTrue(PasswordGen.entropyBits(Options(length = pw.length)) > bits * 2)
    }

    @Test
    fun `易读模式的音节数被夹进合法范围`() {
        val few = PasswordGen.generate(Options(mode = Mode.Readable, syllables = 1, trailingDigits = false), real)
        assertEquals(PasswordGen.MIN_SYLLABLES / PasswordGen.GROUP_SYLLABLES - 1, few.count { it == '-' })
    }

    /* ───────────────────── 结果本身 ───────────────────── */

    @Test
    fun `生成的密码不含空白字符`() {
        // 密码里有空格的后果是：粘进别人的输入框时被静默 trim 掉，
        // 于是注册成功、登录失败，而两边看起来都没错。
        repeat(200) {
            val pw = PasswordGen.generate(Options(length = 24), real)
            assertFalse(pw.any { it.isWhitespace() })
        }
    }

    @Test
    fun `符号集里没有引号 反斜杠和 shell 元字符`() {
        val s = PasswordGen.symbolSet()
        for (c in "'\"\\ `<>|&") {
            assertFalse("符号集里不该有 $c", s.contains(c))
        }
        assertTrue(s.length >= 20)
    }

    @Test
    fun `连续两次生成不会相同`() {
        // 弱断言，但抓得住「结果被 remember 住了 / 随机源没前进」这类低级错误。
        val a = PasswordGen.generate(Options(), real)
        val b = PasswordGen.generate(Options(), real)
        assertNotEquals(a, b)
    }

    @Test
    fun `字符分类和显示用的三色对得上`() {
        assertEquals(PasswordGen.Kind.Letter, PasswordGen.kindOf('a'))
        assertEquals(PasswordGen.Kind.Letter, PasswordGen.kindOf('Z'))
        assertEquals(PasswordGen.Kind.Digit, PasswordGen.kindOf('7'))
        assertEquals(PasswordGen.Kind.Symbol, PasswordGen.kindOf('!'))
        // 易读模式的连字符归符号，于是分组一眼可见
        assertEquals(PasswordGen.Kind.Symbol, PasswordGen.kindOf('-'))
    }
}
