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

import cn.localvault.app.core.vault.VaultMeta
import cn.localvault.app.ui.settings.ChangeMasterModel
import cn.localvault.app.ui.settings.ChangeMasterModel.Blocker
import cn.localvault.app.ui.settings.ChangeMasterModel.Failure
import cn.localvault.app.ui.util.PasswordStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 修改主密码的内核。
 *
 * 这一页上几乎每一句话都是在**声明一件已经发生或即将发生的事实**，
 * 而这类话说错了，用户当场是发现不了的：
 *
 *  - 「保险库没有被改动，原来的主密码依然有效」——失败时用户最怕的不是没改成，
 *    是「改到一半，两个都用不了了」。这句话必须每一条失败文案里都有；
 *  - 「你之前导出的备份文件仍然只认旧主密码」——这是整个 App 里最安静的一条
 *    数据丢失路径。什么都不报错，半年后才发现；
 *  - 「指纹和 PIN 不用重新设置」——它是决策① 的直接后果，
 *    哪天有人改成了「换密码顺便换库主密钥」，这句话就变成了假的，
 *    而用户要到下一次按指纹时才知道。
 *
 * 所以这些判断全部搬到这个纯 Kotlin 对象里，由下面这些用例钉住。
 */
class ChangeMasterModelTest {

    /* ═════════════ 硬下限 ═════════════ */

    @Test
    fun `新主密码的下限和建库那一页是同一个数`() {
        // 分开写的话，用户就能通过「改密码」把主密码降到建库时不允许的强度，
        // 而屏幕上没有任何地方会提到这件事。
        assertEquals(PasswordStrength.MASTER_MIN_LENGTH, ChangeMasterModel.MIN_LENGTH)
    }

    /* ═════════════ 什么时候能提交 ═════════════ */

    private fun blocker(
        old: Int = 12,
        new: Int = 12,
        matched: Boolean = true,
        sameAsOld: Boolean = false,
    ) = ChangeMasterModel.blocker(old, new, matched, sameAsOld)

    @Test
    fun `四条都过了才给提交`() {
        assertNull(blocker())
        assertTrue(ChangeMasterModel.canSubmit(12, 12, matched = true, sameAsOld = false))
    }

    @Test
    fun `当前主密码没填时先说这一条`() {
        // 后面三条全是白填，所以它排第一。
        assertEquals(Blocker.OldEmpty, blocker(old = 0, new = 0, matched = false))
    }

    @Test
    fun `新主密码差一位也不给过`() {
        assertEquals(Blocker.NewTooShort, blocker(new = ChangeMasterModel.MIN_LENGTH - 1))
        assertNull(blocker(new = ChangeMasterModel.MIN_LENGTH))
    }

    @Test
    fun `两遍不一致排在长度之后`() {
        // 长度是客观事实，一打完就知道；一致与否要等第二遍打完才有意义。
        assertEquals(Blocker.NotMatched, blocker(matched = false))
    }

    @Test
    fun `「和旧的是同一个」排在最后，等两遍都对上了再说`() {
        // 否则用户第二遍才打两个字，就被扣一顶「你没改」的帽子，而他根本没打完。
        assertEquals(Blocker.NotMatched, blocker(matched = false, sameAsOld = true))
        assertEquals(Blocker.SameAsOld, blocker(matched = true, sameAsOld = true))
    }

    @Test
    fun `原样再设一遍不算修改`() {
        assertFalse(ChangeMasterModel.canSubmit(12, 12, matched = true, sameAsOld = true))
    }

    /* ═════════════ 挡路时说什么 ═════════════ */

    @Test
    fun `只有「和旧的一样」有话说，其余三条一律不说`() {
        // 前三条在屏幕上各自已经有表达（空框子、「还差 N 位」、确认框上的叉）。
        // 在按钮上方把用户已经看见的事再说一遍，读多了他连要紧的那句也会跳过。
        assertNull(ChangeMasterModel.blockerMessage(Blocker.OldEmpty))
        assertNull(ChangeMasterModel.blockerMessage(Blocker.NewTooShort))
        assertNull(ChangeMasterModel.blockerMessage(Blocker.NotMatched))
        assertNotNull(ChangeMasterModel.blockerMessage(Blocker.SameAsOld))
    }

    @Test
    fun `「和旧的一样」那句话说的是后果，不是规则`() {
        val msg = ChangeMasterModel.blockerMessage(Blocker.SameAsOld)!!
        // 不写「不允许」「请输入不同的密码」这种在陈述一条规定的话——
        // 用户要的是知道「那我这一趟白跑了」，而不是知道我们有一条规矩。
        assertTrue(msg.contains("同一个"))
        assertFalse(msg.contains("不允许"))
    }

    /* ═════════════ 提交前的横幅 ═════════════ */

    @Test
    fun `提交前必须同时说清「旧备份不跟着变」和「没有找回通道」`() {
        val w = ChangeMasterModel.BEFORE_WARNING
        assertTrue("旧备份那件事是这一页独有的，用户想不到", w.contains("备份"))
        assertTrue(w.contains("旧主密码"))
        assertTrue("忘了就是永久打不开，这条老规矩在换口令的这一刻要重说一次", w.contains("找回"))
    }

    @Test
    fun `提交前那条横幅里不许出现「建议」「定期」这类劝导`() {
        // 决策(95)：如实说明代价，不打分、不劝导。
        val w = ChangeMasterModel.BEFORE_WARNING
        assertFalse(w.contains("建议"))
        assertFalse(w.contains("定期"))
    }

    /* ═════════════ 指纹 / PIN 会怎么样 ═════════════ */

    @Test
    fun `一项都没绑时一个字都不说`() {
        // 对一个没绑过的人来说，这只是一段解释了他没有的功能的小字。
        assertNull(ChangeMasterModel.quickUnlockNote(pinEnrolled = false, biometricEnrolled = false))
    }

    @Test
    fun `绑了哪一项就只说哪一项`() {
        val bio = ChangeMasterModel.quickUnlockNote(pinEnrolled = false, biometricEnrolled = true)!!
        assertTrue(bio.startsWith("指纹"))
        assertFalse("没绑 PIN 就别提 PIN", bio.contains("PIN"))

        val pin = ChangeMasterModel.quickUnlockNote(pinEnrolled = true, biometricEnrolled = false)!!
        assertTrue(pin.startsWith("PIN"))
        assertFalse("没绑指纹就别提指纹", pin.contains("指纹"))

        val both = ChangeMasterModel.quickUnlockNote(pinEnrolled = true, biometricEnrolled = true)!!
        assertTrue(both.contains("指纹"))
        assertTrue(both.contains("PIN"))
    }

    @Test
    fun `这句话必须说明理由，不能只丢一句「不受影响」`() {
        val note = ChangeMasterModel.quickUnlockNote(pinEnrolled = true, biometricEnrolled = true)!!
        // 「它们记住的是保险库本身，不是你的主密码」——这是决策① 的直接后果，
        // 也是用户能拿来自己推断其他情形（比如换手机）的那半句。
        assertTrue(note.contains("保险库本身"))
        assertTrue(note.contains("不是你的主密码"))
        assertTrue(note.contains("不用重新设置"))
    }

    /* ═════════════ 失败了说什么 ═════════════ */

    @Test
    fun `每一条失败文案都必须说清保险库没被改动、旧口令还有效`() {
        // 这一页失败时，用户脑子里的第一个念头是「我是不是两个都用不了了」。
        Failure.entries.forEach { f ->
            val msg = ChangeMasterModel.failureMessage(f)
            assertTrue("$f 少了「没有被改动」", msg.contains("没有被改动"))
            assertTrue("$f 少了「原来的主密码依然有效」", msg.contains("原来的主密码依然有效"))
        }
    }

    @Test
    fun `四条失败文案互不重样`() {
        val all = Failure.entries.map { ChangeMasterModel.failureMessage(it) }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `输错旧口令的那一条不许把责任推给应用，自检失败的那一条不许推给用户`() {
        val wrong = ChangeMasterModel.failureMessage(Failure.WrongOld)
        assertTrue(wrong.startsWith("当前主密码不对"))

        val verify = ChangeMasterModel.failureMessage(Failure.WriteVerify)
        // 自检没过意味着我们生成了一个自己都解不开的文件——那是 bug，
        // 说成「请重试」会让用户一遍遍试同一件注定失败的事。
        assertTrue(verify.contains("应用自身的问题"))
        assertFalse(verify.contains("请重试"))
    }

    @Test
    fun `失败文案里不出现「损坏」「丢失」这种吓人又不准确的词`() {
        // 和绑定失效那一段（决策(102) 一脉）同一条规矩：失败的时候数据一条没动，
        // 用词就不能让人以为数据出了事。
        Failure.entries.forEach { f ->
            val msg = ChangeMasterModel.failureMessage(f)
            assertFalse("$f 出现了「损坏」", msg.contains("损坏"))
            assertFalse("$f 出现了「丢失」", msg.contains("丢失"))
        }
    }

    /* ═════════════ 改完之后 ═════════════ */

    @Test
    fun `备份过的人，改完之后主动把「去重新备份」摆出来`() {
        val s = ChangeMasterModel.success(lastBackupAt = 1_700_000_000_000L)
        assertTrue(s.needsBackup)
        assertTrue(s.text.contains("旧主密码"))
    }

    @Test
    fun `从没备份过的人不在这里再喊一遍备份`() {
        // 他缺的是第一次备份，那件事早有列表页顶上那条常驻提醒在管（决策㉞）。
        // 在这儿再喊一遍，只会让两条提醒互相稀释。
        val s = ChangeMasterModel.success(lastBackupAt = 0L)
        assertFalse(s.needsBackup)
        assertFalse(s.text.contains("重新导出"))
    }

    @Test
    fun `成功文案不吹嘘，只说发生了什么`() {
        val s = ChangeMasterModel.success(lastBackupAt = 0L)
        assertTrue(s.text.contains("已经换成新的"))
        assertFalse(s.text.contains("安全"))
        assertFalse(s.text.contains("恭喜"))
    }

    /* ═════════════ 设置页那一行 ═════════════ */

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test
    fun `从没改过就写「从未修改过」，不写劝导`() {
        val r = ChangeMasterModel.rowSummary(masterChangedAt = 0L, lastBackupAt = now - day, now = now)
        assertEquals("从未修改过", r.text)
        assertFalse(r.urgent)
    }

    @Test
    fun `备份比这次修改还早时，把那件要紧的事说出来并标成要紧`() {
        val r = ChangeMasterModel.rowSummary(
            masterChangedAt = now - day,
            lastBackupAt = now - 10 * day,
            now = now,
        )
        assertTrue(r.urgent)
        assertTrue(r.text.contains("旧主密码"))
    }

    @Test
    fun `备份比修改新时只报事实，不标要紧`() {
        val r = ChangeMasterModel.rowSummary(
            masterChangedAt = now - 10 * day,
            lastBackupAt = now - day,
            now = now,
        )
        assertFalse(r.urgent)
        assertTrue(r.text.startsWith("上次修改："))
    }

    @Test
    fun `从没备份过时不算「备份口令过期」`() {
        // `lastBackupAt == 0` 不是「一份很旧的备份」，是「没有备份」。
        // 把它算成过期，这一行就会永远黄着，而用户按它去改主密码也解决不了问题。
        val r = ChangeMasterModel.rowSummary(
            masterChangedAt = now - day,
            lastBackupAt = 0L,
            now = now,
        )
        assertFalse(r.urgent)
        assertTrue(r.text.startsWith("上次修改："))
    }

    @Test
    fun `刚改完就备份的那一刻不算过期`() {
        // 边界：两个时间戳相等（改完立刻按了「现在重新导出备份」）。
        val r = ChangeMasterModel.rowSummary(masterChangedAt = now, lastBackupAt = now, now = now)
        assertFalse(r.urgent)
    }

    /* ═════════════ 和数据模型的默认值对齐 ═════════════ */

    @Test
    fun `新库的 masterChangedAt 是 0，于是新库的那一行写「从未修改过」`() {
        val meta = VaultMeta()
        assertEquals(0L, meta.masterChangedAt)
        assertEquals(
            "从未修改过",
            ChangeMasterModel.rowSummary(meta.masterChangedAt, meta.lastBackupAt, now).text,
        )
    }
}
