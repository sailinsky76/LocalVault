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

import cn.localvault.app.ui.settings.PinSetupModel
import cn.localvault.app.ui.settings.PinSetupModel.ConfirmResult
import cn.localvault.app.ui.settings.PinSetupModel.Mode
import cn.localvault.app.ui.settings.PinSetupModel.Step
import cn.localvault.app.ui.settings.PinSetupModel.Weakness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PIN 设置流的内核。
 *
 * 盯着的是四类事：
 *
 *  - **弱 PIN 的规则本身**。这是这一份最主要的存在理由：规则只要还活在界面代码里，
 *    改一次就没人说得清它现在到底认不认 `890123` 了。这里把每一条都用具体的
 *    六位数字钉住，包括它们**互相冲突时谁赢**。
 *  - **弱 PIN 的说法不许照抄弱主密码那一份**。那一份讲的是「文件被拷走以后
 *    离线爆破」，而这件事对 PIN 根本不成立（外面还有一层设备绑定，决策⑥）。
 *    用一个不成立的理由吓唬用户，是在拿以后所有安全提示的可信度换这一次的点击。
 *  - **弹窗里绝不出现用户刚输的那六位数**。这条不是靠自觉守的：
 *    [PinSetupModel.weaknessMessage] 的入参里根本没有 PIN，写不出来。
 *    下面那条用例钉的是这个签名事实。
 *  - **两次不一致要退回第一步**，不是只清第二次那份。只清第二份等于假定
 *    「打错的是第二次」，而如果打错的其实是第一次，用户会对着一个自己
 *    并不想要的 PIN 反复确认，直到「对上」为止——那件事要到下次解锁才暴露。
 */
class PinSetupModelTest {

    private fun w(s: String): Weakness? = PinSetupModel.weakness(s.toCharArray())

    /* ═════════════ 弱 PIN：每一条规则 ═════════════ */

    @Test
    fun `六位一样的数字算弱`() {
        assertEquals(Weakness.AllSame, w("000000"))
        assertEquals(Weakness.AllSame, w("111111"))
        assertEquals(Weakness.AllSame, w("666666"))
        assertEquals(Weakness.AllSame, w("999999"))
    }

    @Test
    fun `正着倒着的连号都算弱`() {
        assertEquals(Weakness.Sequential, w("123456"))
        assertEquals(Weakness.Sequential, w("654321"))
        assertEquals(Weakness.Sequential, w("012345"))
        assertEquals(Weakness.Sequential, w("543210"))
    }

    @Test
    fun `跨过 9 的连号照样算弱 —— 别人试的时候不会因为它环绕了就跳过`() {
        assertEquals(Weakness.Sequential, w("890123"))
        assertEquals(Weakness.Sequential, w("210987"))
    }

    @Test
    fun `两位或三位一个循环算弱`() {
        assertEquals(Weakness.Repeating, w("121212"))
        assertEquals(Weakness.Repeating, w("010101"))
        assertEquals(Weakness.Repeating, w("123123"))
        assertEquals(Weakness.Repeating, w("520520"))
    }

    @Test
    fun `数字键盘上的直线和斜线算弱`() {
        assertEquals(Weakness.WellKnown, w("147258"))
        assertEquals(Weakness.WellKnown, w("159357"))
        assertEquals(Weakness.WellKnown, w("951753"))
        assertEquals(Weakness.WellKnown, w("789456"))
    }

    @Test
    fun `成对递增与对称的那几串算弱`() {
        assertEquals(Weakness.WellKnown, w("112233"))
        assertEquals(Weakness.WellKnown, w("998877"))
        assertEquals(Weakness.WellKnown, w("123321"))
        assertEquals(Weakness.WellKnown, w("102030"))
    }

    @Test
    fun `四种日期排法都认得出来`() {
        assertEquals("YYMMDD", Weakness.DateLike, w("901231"))
        assertEquals("DDMMYY", Weakness.DateLike, w("311290"))
        assertEquals("MMDDYY", Weakness.DateLike, w("123190"))
        assertEquals("YYYYMM", Weakness.DateLike, w("199012"))
    }

    @Test
    fun `历法上不存在的日子照样算日期样式 —— 照着生日的样子敲的人不查万年历`() {
        assertEquals(Weakness.DateLike, w("900231"))
    }

    /* ═════════════ 弱 PIN：不该误伤的 ═════════════ */

    @Test
    fun `没规律的六位数不该被提醒`() {
        for (pin in listOf("794613", "837295", "405938", "628394", "473916", "836471")) {
            assertNull("$pin 不该被认成弱 PIN", w(pin))
        }
    }

    @Test
    fun `位数不对或含非数字时不下结论 —— 那不是「强」，是不该在这儿说话`() {
        assertNull(w("12345"))
        assertNull(w("1234567"))
        assertNull(w(""))
        assertNull(w("12a456"))
        assertNull(w("１２３４５６"))
    }

    @Test
    fun `弱 PIN 只提醒不拦截 —— 次按钮的字必须是「继续用」而不是另一个「返回」`() {
        // 主按钮（显眼那个）是「换一个」：一路点最显眼的按钮，结果应该更安全。
        assertEquals("换一个", PinSetupModel.WEAK_CONFIRM_TEXT)
        assertTrue(
            "次按钮必须让用户能坚持用它，否则这就成了拦截",
            PinSetupModel.WEAK_SECONDARY_TEXT.contains("就用它"),
        )
    }

    /* ═════════════ 弱 PIN：规则打架时谁赢 ═════════════ */

    @Test
    fun `既是循环又在常见名单里的，报「循环」更有用`() {
        // 121212 两条都沾，但告诉用户「你在重复同一小段」比「这串很常见」具体。
        assertEquals(Weakness.Repeating, w("121212"))
    }

    @Test
    fun `既像日期又是循环的，报「循环」`() {
        // 010101 可以读成 01 年 01 月 01 日，但它首先是一个两位循环。
        assertEquals(Weakness.Repeating, w("010101"))
    }

    @Test
    fun `既在常见名单里又像日期的，报「常见」`() {
        // 102030 能被 DDMMYY 认成日期，但它在名单里的身份更贴切。
        assertEquals(Weakness.WellKnown, w("102030"))
    }

    @Test
    fun `连号优先于日期 —— 123456 首先是连号`() {
        assertEquals(Weakness.Sequential, w("123456"))
    }

    @Test
    fun `六位一样优先于循环 —— 111111 不报「两位循环」`() {
        assertEquals(Weakness.AllSame, w("111111"))
    }

    /* ═════════════ 文案 ═════════════ */

    @Test
    fun `弱 PIN 的说法里不许出现「离线爆破」那一套 —— 那对 PIN 不成立`() {
        for (weakness in Weakness.values()) {
            val msg = PinSetupModel.weaknessMessage(weakness)
            for (banned in listOf("离线", "爆破", "拷走", "破解")) {
                // 「PIN 不怕被算出来」这句是允许的——它正是在否定这条路，
                // 禁的是「离线」「爆破」这些把主密码的风险模型搬过来的说法。
                assertFalse("$weakness 的说法里不该出现「$banned」：$msg", msg.contains(banned))
            }
        }
    }

    @Test
    fun `弱 PIN 的说法落点统一是「被猜到 - 被试中」`() {
        for (weakness in Weakness.values()) {
            val msg = PinSetupModel.weaknessMessage(weakness)
            assertTrue(
                "$weakness 的说法要讲清风险是别人猜得到 / 试得中：$msg",
                msg.contains("试") || msg.contains("猜"),
            )
        }
    }

    @Test
    fun `每一种弱法的标题都不一样 —— 分种类就是为了说不同的话`() {
        val titles = Weakness.values().map { PinSetupModel.weaknessTitle(it) }
        assertEquals("有两种弱法用了同一句标题", titles.size, titles.toSet().size)
        val messages = Weakness.values().map { PinSetupModel.weaknessMessage(it) }
        assertEquals("有两种弱法用了同一段说明", messages.size, messages.toSet().size)
    }

    @Test
    fun `弱 PIN 的文案生成不接触 PIN 本身 —— 弹窗里写不出那六位数`() {
        // 这一条钉的是签名事实：weaknessTitle / weaknessMessage 的入参只有一个枚举，
        // 于是「弹窗里不出现用户刚输的数字」不是靠自觉守的，是写不出来。
        // 顺手核一遍：文案里一个连续的六位数字串都不该有。
        val sixDigits = Regex("\\d{6}")
        for (weakness in Weakness.values()) {
            assertFalse(sixDigits.containsMatchIn(PinSetupModel.weaknessTitle(weakness)))
            assertFalse(sixDigits.containsMatchIn(PinSetupModel.weaknessMessage(weakness)))
        }
    }

    @Test
    fun `页面说明必须写出「输错不会删数据」和「主密码一直都能用」`() {
        val intro = PinSetupModel.INTRO
        assertTrue("要写明输错的后果是被拦住，不是丢数据", intro.contains("不会删掉任何数据"))
        assertTrue("要写明主密码仍然是真凭据", intro.contains("主密码"))
        assertTrue("要写明它只在这台设备上成立", intro.contains("这台设备"))
    }

    @Test
    fun `页面说明不许出现不可核实的吹嘘`() {
        val texts = listOf(PinSetupModel.INTRO, PinSetupModel.ENROLL_FAILED) +
            Weakness.values().map { PinSetupModel.weaknessMessage(it) }
        for (t in texts) {
            for (banned in listOf("绝对", "军工", "永不", "百分之百", "无法破解")) {
                assertFalse("不该出现「$banned」：$t", t.contains(banned))
            }
        }
    }

    @Test
    fun `设置失败的说法落点是「没设上，数据没受影响」，不是「请用主密码解锁」`() {
        val msg = PinSetupModel.ENROLL_FAILED
        assertTrue(msg.contains("没有受到影响") || msg.contains("没有受影响"))
        // 用户此刻已经在库里了，跟他说「请用主密码解锁」是句糊涂话（决策(102)）。
        for (banned in listOf("用主密码解锁", "用主密码打开", "解锁保险库")) {
            assertFalse("绑定期文案里不该出现「$banned」：$msg", msg.contains(banned))
        }
    }

    @Test
    fun `两步的标题和副标题都不重样，用户能看出自己在第几步`() {
        for (mode in Mode.values()) {
            assertTrue(
                "$mode 两步的标题一样，用户分不出自己在哪一步",
                PinSetupModel.heading(mode, Step.Enter) != PinSetupModel.heading(mode, Step.Confirm),
            )
            assertTrue(
                "$mode 两步的副标题一样",
                PinSetupModel.caption(mode, Step.Enter) != PinSetupModel.caption(mode, Step.Confirm),
            )
        }
    }

    @Test
    fun `修改 PIN 的第一步要说清这是「新的」，别让人以为在验旧的`() {
        assertTrue(PinSetupModel.heading(Mode.Change, Step.Enter).contains("新"))
        assertFalse(PinSetupModel.heading(Mode.Set, Step.Enter).contains("新"))
        assertEquals("设置 PIN", PinSetupModel.title(Mode.Set))
        assertEquals("修改 PIN", PinSetupModel.title(Mode.Change))
    }

    @Test
    fun `第二步的按钮写「完成」，不写「下一步」`() {
        assertEquals("下一步", PinSetupModel.submitText(Step.Enter))
        assertEquals("完成", PinSetupModel.submitText(Step.Confirm))
    }

    /* ═════════════ 比对与提交 ═════════════ */

    @Test
    fun `两次一样才算一致`() {
        assertEquals(
            ConfirmResult.Match,
            PinSetupModel.confirm("481902".toCharArray(), "481902".toCharArray()),
        )
    }

    @Test
    fun `差一位就算不一致，包括最后一位`() {
        assertEquals(
            ConfirmResult.Mismatch,
            PinSetupModel.confirm("481902".toCharArray(), "481903".toCharArray()),
        )
        assertEquals(
            ConfirmResult.Mismatch,
            PinSetupModel.confirm("481902".toCharArray(), "581902".toCharArray()),
        )
    }

    @Test
    fun `位数不对一律算不一致 —— 绝不能让一个短的「前缀对上」就通过`() {
        assertEquals(
            ConfirmResult.Mismatch,
            PinSetupModel.confirm("481902".toCharArray(), "48190".toCharArray()),
        )
        assertEquals(
            ConfirmResult.Mismatch,
            PinSetupModel.confirm("48190".toCharArray(), "48190".toCharArray()),
        )
    }

    @Test
    fun `比对不改动传进来的两份缓冲 —— 清零是调用方的事，顺序不能被打乱`() {
        val a = "481902".toCharArray()
        val b = "481902".toCharArray()
        PinSetupModel.confirm(a, b)
        assertEquals("481902", a.concatToString())
        assertEquals("481902", b.concatToString())
    }

    @Test
    fun `不一致时的说法是「重新输入一遍」，摆明了两份都清掉`() {
        assertTrue(PinSetupModel.MISMATCH_MESSAGE.contains("重新"))
        // 不许写成「请再输一遍确认」——那暗示第一份还留着，而它其实已经被清了。
        assertFalse(PinSetupModel.MISMATCH_MESSAGE.contains("再输一遍确认"))
    }

    @Test
    fun `按满六位按钮才亮，多一位少一位都不行`() {
        assertFalse(PinSetupModel.canSubmit(0))
        assertFalse(PinSetupModel.canSubmit(5))
        assertTrue(PinSetupModel.canSubmit(6))
        assertFalse(PinSetupModel.canSubmit(7))
        assertEquals(6, PinSetupModel.LENGTH)
    }

    /* ═════════════ 安全设置页上那一行 ═════════════ */

    @Test
    fun `开关的位置等于「设没设过」`() {
        assertTrue(PinSetupModel.pinRow(enrolled = true).checked)
        assertFalse(PinSetupModel.pinRow(enrolled = false).checked)
    }

    @Test
    fun `「修改 PIN」只在已经设过的时候给 —— 没设过就摆一个「修改」是在说胡话`() {
        assertNotNull(PinSetupModel.pinRow(enrolled = true).changeText)
        assertNull(PinSetupModel.pinRow(enrolled = false).changeText)
    }

    @Test
    fun `PIN 那一行两种状态的副标题不一样，而且都不打分不劝导`() {
        val on = PinSetupModel.pinRow(true).subtitle
        val off = PinSetupModel.pinRow(false).subtitle
        assertTrue(on != off)
        for (t in listOf(on, off)) {
            for (banned in listOf("建议", "推荐", "不安全", "更安全", "为了您的")) {
                assertFalse("副标题里不该出现「$banned」：$t", t.contains(banned))
            }
        }
    }
}
