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

import cn.localvault.app.ui.settings.QuickUnlockModel
import cn.localvault.app.ui.settings.QuickUnlockModel.BiometricSupport
import cn.localvault.app.ui.unlock.BiometricFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷解锁绑定页的内核。
 *
 * 这一页的分支比它看起来多得多，而且**大多数分支在真机上凑不齐**：
 * 「系统安全模块需要更新」要找特定批次的机器，「状态未知」只在某些定制 ROM 上出现，
 * 「绑过之后指纹库被清空」要来回折腾系统设置。所以这些判断全部搬进纯 Kotlin，
 * 在这里一次钉死。
 *
 * 盯着的是四类事：
 *
 *  - **开关的位置反映的是「绑没绑过」，不是「现在能不能用」**。
 *    绑过但指纹被删光时，开关必须还是开着的——因为 prefs 里确实还躺着
 *    一份（已经没用了的）包裹，用户需要看见它、并且能把它关掉。
 *  - **灰掉的开关必须自己解释为什么灰**（决策(61)）。一个没有解释的灰控件，
 *    用户的第一反应是应用卡了。
 *  - **「状态未知」不等于「不支持」**。灰掉它等于替用户下了一个连系统都没敢下的
 *    结论，代价是他在一台其实能用指纹的手机上永远打不开这个开关。
 *  - **绑定期的文案不能用解锁期那一份**。那一份每条的落点都是「请用主密码解锁」，
 *    而用户此刻已经在库里了。跟一个门已经开着的人说「请用主密码打开」，
 *    读起来像应用把自己的状态搞糊涂了——他对所有安全提示的信任会一起打折。
 */
class QuickUnlockModelTest {

    private val allSupports = BiometricSupport.values().toList()

    /* ═════════════ 开关的位置与可用性 ═════════════ */

    @Test
    fun `开关的位置永远等于「绑没绑过」，和当前能不能用无关`() {
        for (s in allSupports) {
            assertTrue("$s 已绑定时开关必须是开着的", QuickUnlockModel.biometricRow(s, true).checked)
            assertFalse("$s 未绑定时开关必须是关着的", QuickUnlockModel.biometricRow(s, false).checked)
        }
    }

    @Test
    fun `已绑定时开关永远能动 —— 用户必须有办法把失效的绑定关掉`() {
        for (s in allSupports) {
            assertTrue("$s 已绑定时开关不该是灰的", QuickUnlockModel.biometricRow(s, true).enabled)
        }
    }

    @Test
    fun `一切正常且未绑定时，开关能开、不多说一句废话`() {
        val row = QuickUnlockModel.biometricRow(BiometricSupport.Ready, enrolled = false)
        assertTrue(row.enabled)
        assertFalse(row.checked)
        assertNull("一切正常时不该出说明（决策(95)：只在有话要说时说话）", row.note)
        assertFalse(row.showEnrollHint)
    }

    @Test
    fun `一切正常且已绑定时，同样不出说明`() {
        val row = QuickUnlockModel.biometricRow(BiometricSupport.Ready, enrolled = true)
        assertTrue(row.checked)
        assertTrue(row.enabled)
        assertNull(row.note)
    }

    @Test
    fun `没录指纹时开关是灰的，并且给出去录指纹的出口`() {
        val row = QuickUnlockModel.biometricRow(BiometricSupport.NoneEnrolled, enrolled = false)
        assertFalse(row.enabled)
        assertNotNull(row.note)
        assertTrue("录一枚指纹就能解决的问题，必须给出口", row.showEnrollHint)
    }

    @Test
    fun `没有硬件时开关是灰的，但不给「去录指纹」——那儿去了也没用`() {
        val row = QuickUnlockModel.biometricRow(BiometricSupport.NoHardware, enrolled = false)
        assertFalse(row.enabled)
        assertNotNull(row.note)
        assertFalse("没有传感器的机器上给这个按钮，等于把人支到一个到了也没用的地方", row.showEnrollHint)
    }

    @Test
    fun `安全模块要更新时开关是灰的，也不给「去录指纹」`() {
        val row = QuickUnlockModel.biometricRow(BiometricSupport.NeedsSecurityUpdate, enrolled = false)
        assertFalse(row.enabled)
        assertNotNull(row.note)
        assertFalse(row.showEnrollHint)
    }

    @Test
    fun `「暂时不可用」和「状态未知」一律留着开关可以按`() {
        for (s in listOf(BiometricSupport.TemporarilyUnavailable, BiometricSupport.Unknown)) {
            val row = QuickUnlockModel.biometricRow(s, enrolled = false)
            assertTrue(
                "$s 灰掉开关等于替用户下了一个连系统都没敢下的结论",
                row.enabled,
            )
            assertNotNull("既然让他按，就得先说清楚可能按不成", row.note)
        }
    }

    @Test
    fun `凡是灰掉的开关，一定配着一句解释（决策(61)）`() {
        for (s in allSupports) {
            for (enrolled in listOf(true, false)) {
                val row = QuickUnlockModel.biometricRow(s, enrolled)
                if (!row.enabled) {
                    assertNotNull("$s / enrolled=$enrolled 灰了却不解释", row.note)
                    assertTrue(row.note!!.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `每一种情况都有一句非空的副标题`() {
        for (s in allSupports) {
            for (enrolled in listOf(true, false)) {
                val row = QuickUnlockModel.biometricRow(s, enrolled)
                assertTrue("$s / enrolled=$enrolled 副标题不该是空的", row.subtitle.isNotBlank())
            }
        }
    }

    @Test
    fun `note 要么是 null 要么有内容，不许是空白串`() {
        for (s in allSupports) {
            for (enrolled in listOf(true, false)) {
                val note = QuickUnlockModel.biometricRow(s, enrolled).note
                if (note != null) assertTrue("$s / enrolled=$enrolled", note.isNotBlank())
            }
        }
    }

    /* ═════════════ 绑定失效那一条 ═════════════ */

    @Test
    fun `绑过之后指纹被删光时，必须写明「不是故障」和「数据没动」`() {
        val row = QuickUnlockModel.biometricRow(BiometricSupport.NoneEnrolled, enrolled = true)
        assertTrue(row.checked)
        assertTrue("用户必须能把这份失效的绑定关掉", row.enabled)
        val note = row.note!!
        assertTrue("要先说这不是故障", note.contains("不是故障"))
        assertTrue("要说清数据一条没动", note.contains("数据"))
        assertTrue("要给下一步：重新录入", note.contains("重新"))
        assertTrue(row.showEnrollHint)
    }

    @Test
    fun `绑定失效的说明里不许出现「丢失」「损坏」这种吓人又不准确的词`() {
        val note = QuickUnlockModel.biometricRow(BiometricSupport.NoneEnrolled, true).note!!
        for (word in listOf("丢失", "损坏", "错误", "失败")) {
            assertFalse("「$word」会让用户以为库出事了", note.contains(word))
        }
    }

    /* ═════════════ 绑定失败的文案 ═════════════ */

    @Test
    fun `用户取消不算错误，不给任何文案`() {
        assertNull(QuickUnlockModel.enrollFailureMessage(BiometricFailure.UserCanceled))
    }

    @Test
    fun `除了取消，每一种失败都要给一句话`() {
        for (f in BiometricFailure.values()) {
            if (f == BiometricFailure.UserCanceled) continue
            val msg = QuickUnlockModel.enrollFailureMessage(f)
            assertNotNull("$f 没有文案", msg)
            assertTrue("$f 的文案是空的", msg!!.isNotBlank())
        }
    }

    @Test
    fun `绑定期的文案里绝不出现「解锁保险库」这类只在门外才成立的说法`() {
        for (f in BiometricFailure.values()) {
            val msg = QuickUnlockModel.enrollFailureMessage(f) ?: continue
            for (phrase in listOf("用主密码解锁", "用主密码打开", "请用主密码", "解锁保险库")) {
                assertFalse(
                    "$f 的文案用了解锁页的口径：「$phrase」——用户此刻已经在库里了",
                    msg.contains(phrase),
                )
            }
        }
    }

    @Test
    fun `绑定期的文案和解锁期的文案不许是同一句`() {
        for (f in BiometricFailure.values()) {
            val enroll = QuickUnlockModel.enrollFailureMessage(f) ?: continue
            val unlock = cn.localvault.app.ui.unlock.BiometricPolicy.message(f)
            assertFalse("$f 两边共用了同一句话", enroll == unlock)
        }
    }

    @Test
    fun `凡是可能让人担心数据的失败，都要明说库和数据没受影响`() {
        for (f in listOf(BiometricFailure.TemporaryLockout, BiometricFailure.Other)) {
            val msg = QuickUnlockModel.enrollFailureMessage(f)!!
            assertTrue("$f 要写明数据没事", msg.contains("没有受影响"))
        }
    }

    @Test
    fun `没录指纹导致的绑定失败，要说去哪儿录`() {
        val msg = QuickUnlockModel.enrollFailureMessage(BiometricFailure.NoneEnrolled)!!
        assertTrue(msg.contains("系统设置"))
    }

    /* ═════════════ 设置主页那一行 ═════════════ */

    @Test
    fun `四种组合各有各的说法，互不相同`() {
        val none = QuickUnlockModel.summary(pinEnrolled = false, biometricEnrolled = false)
        val bio = QuickUnlockModel.summary(pinEnrolled = false, biometricEnrolled = true)
        val pin = QuickUnlockModel.summary(pinEnrolled = true, biometricEnrolled = false)
        val both = QuickUnlockModel.summary(pinEnrolled = true, biometricEnrolled = true)
        assertEquals(4, setOf(none, bio, pin, both).size)
        assertTrue(bio.contains("指纹"))
        assertFalse("只开了指纹时不该提 PIN", bio.contains("PIN"))
        assertTrue(pin.contains("PIN"))
        assertFalse("只开了 PIN 时不该提指纹", pin.contains("指纹"))
        assertTrue(both.contains("指纹") && both.contains("PIN"))
    }

    @Test
    fun `一项都没开时只陈述事实，不评判`() {
        val none = QuickUnlockModel.summary(pinEnrolled = false, biometricEnrolled = false)
        assertTrue("要说清没开的后果是什么", none.contains("主密码"))
        for (word in listOf("不安全", "建议", "风险", "危险", "推荐", "警告")) {
            assertFalse(
                "设置页不打分、不劝导（决策(95)）：「$word」",
                none.contains(word),
            )
        }
    }

    /* ═════════════ 页面上那两段话 ═════════════ */

    @Test
    fun `开场白必须讲清三件事：只在这台设备、主密码永远能开、关掉不动数据`() {
        val intro = QuickUnlockModel.INTRO
        assertTrue("要说清它只是这台设备上的捷径", intro.contains("这台设备"))
        assertTrue("要说清主密码才是真凭据", intro.contains("主密码"))
        assertTrue("要提前打消「关掉会不会把密码弄没」的顾虑", intro.contains("不会动"))
    }

    @Test
    fun `开场白不许出现不能核实的话`() {
        val texts = listOf(QuickUnlockModel.INTRO, QuickUnlockModel.NONE_ENABLED_NOTE)
        for (t in texts) {
            for (word in listOf("军工级", "绝对安全", "百分百", "牢不可破", "永不")) {
                assertFalse("「$word」没有信息量，还会连累旁边那几条真话（决策(94)）", t.contains(word))
            }
        }
    }

    @Test
    fun `一项都没开时的那句话要说明「这样也正常」，不制造焦虑`() {
        val note = QuickUnlockModel.NONE_ENABLED_NOTE
        assertTrue(note.contains("正常"))
        assertTrue(note.contains("主密码"))
        for (word in listOf("不安全", "风险", "建议")) {
            assertFalse(note.contains(word))
        }
    }

    /* ═════════════ 跨函数的一致性 ═════════════ */

    @Test
    fun `「去录指纹」的出口只在录一枚指纹真能解决问题时才给`() {
        val canBeFixedByEnrolling = setOf(
            BiometricSupport.NoneEnrolled,
            BiometricSupport.TemporarilyUnavailable,
        )
        for (s in allSupports) {
            for (enrolled in listOf(true, false)) {
                val row = QuickUnlockModel.biometricRow(s, enrolled)
                if (row.showEnrollHint) {
                    assertTrue("$s / enrolled=$enrolled 不该给「去录指纹」", s in canBeFixedByEnrolling)
                }
            }
        }
    }

    @Test
    fun `已绑定的每一种异常状态，都要告诉用户「关掉可以清干净」`() {
        val abnormal = listOf(BiometricSupport.NoneEnrolled, BiometricSupport.NoHardware)
        for (s in abnormal) {
            val note = QuickUnlockModel.biometricRow(s, enrolled = true).note!!
            assertTrue("$s 要给出「关掉它」这个动作", note.contains("关掉"))
        }
    }

    @Test
    fun `枚举没有漏网的：每一个 BiometricSupport 都被 biometricRow 认领了`() {
        // 靠的是 when 的 else 分支，这条用例保证将来新增一个值时
        // 至少不会掉进一个 subtitle 为空、note 为 null 的裸状态。
        for (s in allSupports) {
            val row = QuickUnlockModel.biometricRow(s, enrolled = false)
            assertTrue(row.subtitle.isNotBlank())
            if (s != BiometricSupport.Ready) {
                assertNotNull("$s 未绑定时应当有一句交代", row.note)
            }
        }
    }
}
