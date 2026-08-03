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

import cn.localvault.app.core.crypto.KdfParams
import cn.localvault.app.core.vault.VaultData
import cn.localvault.app.core.vault.VaultFile
import cn.localvault.app.ui.restore.RestoreModel
import cn.localvault.app.ui.restore.RestoreModel.Failure
import cn.localvault.app.ui.restore.RestoreModel.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从备份恢复」内核的单测。**不依赖 Compose，也不依赖设备**，任何环境都能跑。
 *
 * 这一步的全部风险在两处，正好都是纯逻辑能钉死的：
 *   · **认文件**——选错文件 / 文件坏了 / 版本太新，三种情况的下一步完全不同；
 *   · **话怎么说**——这一页的用户多半刚丢了手机或刚清空过库，
 *     每一条失败文案都必须保住那句「你手上那份文件没有被改动」。
 */
class RestoreModelTest {

    /** 廉价参数：这里测的是分类和文案，不是 KDF 强度（那个在 VaultFileTest 里）。 */
    private val fast = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 1000, 1)

    private fun realVaultBytes(): ByteArray =
        VaultFile.create("correct horse".toByteArray(), VaultData(), fast)

    // ───────────────────── 认文件 ─────────────────────

    @Test fun `真的库文件认得出来`() {
        val p = RestoreModel.probe("localvault-20260729.lvault", realVaultBytes())
        assertTrue(p is Probe.Recognized)
        p as Probe.Recognized
        assertEquals("localvault-20260729.lvault", p.fileName)
        assertEquals(VaultFile.FORMAT_VERSION, p.formatVersion)
        assertEquals(KdfParams.ID_PBKDF2_SHA512, p.kdfParams.id)
    }

    @Test fun `大小报的是文件的真实字节数`() {
        val bytes = realVaultBytes()
        val p = RestoreModel.probe("a.lvault", bytes) as Probe.Recognized
        assertEquals(bytes.size.toLong(), p.sizeBytes)
    }

    @Test fun `随便一个别的文件报的是选错了而不是坏了`() {
        val jpeg = ByteArray(4096) { 0x7F }
        assertTrue(RestoreModel.probe("IMG_0421.JPG", jpeg) is Probe.NotVaultFile)
    }

    @Test fun `空文件也是选错了`() {
        assertTrue(RestoreModel.probe("empty.lvault", ByteArray(0)) is Probe.NotVaultFile)
    }

    @Test fun `短到装不下文件头的算选错了不算坏了`() {
        // 一个只有魔数的文件：还没到「这是我们的文件但坏了」的程度
        assertTrue(RestoreModel.probe("t", "LVAULT".toByteArray()) is Probe.NotVaultFile)
    }

    @Test fun `扩展名不参与判断`() {
        // 决策㉒：只认文件头。用户重命名过、或被 ROM 改过扩展名，照样认得出来
        val p = RestoreModel.probe("我的密码.bin", realVaultBytes())
        assertTrue(p is Probe.Recognized)
        assertTrue(RestoreModel.probe("骗你的.lvault", ByteArray(300)) is Probe.NotVaultFile)
    }

    @Test fun `更新版本写的文件单独一类并带着版本号`() {
        val bytes = realVaultBytes()
        bytes[6] = 0; bytes[7] = 9      // 格式版本改成 9
        val p = RestoreModel.probe("future.lvault", bytes)
        assertTrue(p is Probe.TooNew)
        assertEquals(9, (p as Probe.TooNew).formatVersion)
    }

    @Test fun `文件头坏掉的报的是坏了不是选错了`() {
        val bytes = realVaultBytes()
        bytes[19] = 0                   // salt 长度非法
        assertTrue(RestoreModel.probe("x.lvault", bytes) is Probe.Damaged)
    }

    @Test fun `迭代次数被改成零的也算坏了`() {
        val bytes = realVaultBytes()
        for (i in 13..16) bytes[i] = 0  // iterations = 0
        assertTrue(RestoreModel.probe("x.lvault", bytes) is Probe.Damaged)
    }

    @Test fun `认文件不改动传进来的字节`() {
        val bytes = realVaultBytes()
        val copy = bytes.copyOf()
        RestoreModel.probe("a", bytes)
        assertTrue(bytes.contentEquals(copy))
    }

    // ───────────────────── 事实 ─────────────────────

    @Test fun `事实只有文件头里读得出来的四行`() {
        val p = RestoreModel.probe("a.lvault", realVaultBytes()) as Probe.Recognized
        val facts = RestoreModel.facts(p)
        assertEquals(4, facts.size)
        assertEquals(listOf("文件", "大小", "格式", "加密参数"), facts.map { it.label })
    }

    @Test fun `事实里绝不出现条目数`() {
        val p = RestoreModel.probe("a.lvault", realVaultBytes()) as Probe.Recognized
        val all = RestoreModel.facts(p).joinToString(" ") { it.label + it.value }
        assertFalse(all.contains("条"))
        assertFalse(all.contains("个"))
    }

    @Test fun `加密参数写的是这份文件里的真实档位`() {
        val p = RestoreModel.probe("a.lvault", realVaultBytes()) as Probe.Recognized
        val v = RestoreModel.facts(p).first { it.label == "加密参数" }.value
        assertTrue(v.contains("PBKDF2"))
    }

    @Test fun `主动交代为什么数不出条目数`() {
        assertTrue(RestoreModel.WHY_NO_COUNT.contains("密文"))
        assertTrue(RestoreModel.WHY_NO_COUNT.contains("主密码"))
        // 不能把这件事说成缺陷
        assertFalse(RestoreModel.WHY_NO_COUNT.contains("暂时"))
        assertFalse(RestoreModel.WHY_NO_COUNT.contains("还没做"))
    }

    // ───────────────────── 实话 ─────────────────────

    @Test fun `会怎样那张清单必须说清指纹和PIN不跟着过来`() {
        val all = RestoreModel.WHAT_HAPPENS.joinToString(" ")
        assertTrue(all.contains("指纹"))
        assertTrue(all.contains("PIN"))
        assertTrue(all.contains("重新开") || all.contains("再开"))
    }

    @Test fun `会怎样那张清单要写明源文件不受影响`() {
        val all = RestoreModel.WHAT_HAPPENS.joinToString(" ")
        assertTrue(all.contains("不会被改动"))
        assertTrue(all.contains("不会被移动") || all.contains("不会被删除"))
    }

    @Test fun `主密码那句话点明认的是导出那一刻的口令`() {
        assertTrue(RestoreModel.PASSWORD_NOTE.contains("导出"))
        assertTrue(RestoreModel.PASSWORD_NOTE.contains("改过主密码"))
    }

    @Test fun `写明这一页不进退避`() {
        assertTrue(RestoreModel.RETRY_NOTE.contains("不会被锁"))
        // 不许在这儿吓唬人说会锁多久
        assertFalse(RestoreModel.RETRY_NOTE.contains("分钟"))
    }

    @Test fun `整页任何一句话都不出现找回破解客服`() {
        val page = (
            RestoreModel.WHAT_HAPPENS + listOf(
                RestoreModel.WHY_NO_COUNT, RestoreModel.PASSWORD_NOTE, RestoreModel.RETRY_NOTE,
            ) + Failure.values().map { RestoreModel.failureMessage(it) }
            ).joinToString(" ")
        listOf("找回", "破解", "客服", "稍后重试", "军工级", "绝对安全").forEach {
            assertFalse("不该出现「$it」", page.contains(it))
        }
    }

    // ───────────────────── 拦提交 ─────────────────────

    private fun ok() = Probe.Recognized("a.lvault", 4096, 1, KdfParams.PBKDF2_DEFAULT)

    @Test fun `文件选好口令填好才点得动`() {
        assertTrue(RestoreModel.canSubmit(ok(), hasPassword = true, busy = false, vaultExists = false))
        assertNull(RestoreModel.blockReason(ok(), true, false, false))
    }

    @Test fun `没选文件时说的是去选文件`() {
        val r = RestoreModel.blockReason(null, hasPassword = true, busy = false, vaultExists = false)
        assertNotNull(r)
        assertTrue(r!!.contains("选择"))
    }

    @Test fun `文件不能用时说的是换一个`() {
        val r = RestoreModel.blockReason(Probe.NotVaultFile("x"), true, false, false)
        assertTrue(r!!.contains("换一个"))
    }

    @Test fun `口令没填时说的是填口令`() {
        val r = RestoreModel.blockReason(ok(), hasPassword = false, busy = false, vaultExists = false)
        assertTrue(r!!.contains("主密码"))
    }

    @Test fun `已经有库这条排在最前面`() {
        // 即使文件和口令都齐了，也先报这条 —— 它是这一页唯一一条「此路不通」
        val r = RestoreModel.blockReason(ok(), hasPassword = true, busy = false, vaultExists = true)
        assertTrue(r!!.contains("已经有一个保险库"))
        assertTrue(r.contains("删除"))
        assertFalse(RestoreModel.canSubmit(ok(), true, false, true))
    }

    @Test fun `已经有库时的说法必须写明不会覆盖`() {
        val r = RestoreModel.blockReason(ok(), true, false, true)!!
        assertTrue(r.contains("不会覆盖"))
    }

    @Test fun `忙的时候不额外说一句废话`() {
        assertNull(RestoreModel.blockReason(ok(), true, busy = true, vaultExists = false))
        assertFalse(RestoreModel.canSubmit(ok(), true, busy = true, vaultExists = false))
    }

    @Test fun `拦截理由一次只报一条`() {
        // 三样都不满足时，报的是最靠前的那一条，不是拼三句
        val r = RestoreModel.blockReason(null, hasPassword = false, busy = false, vaultExists = true)!!
        assertTrue(r.contains("已经有一个保险库"))
        assertFalse(r.contains("请输入"))
        assertFalse(r.contains("请先选择"))
    }

    // ───────────────────── 进度 ─────────────────────

    @Test fun `三句进度互不相同`() {
        val steps = listOf(
            RestoreModel.STEP_READING, RestoreModel.STEP_OPENING, RestoreModel.STEP_INSTALLING,
        )
        assertEquals(steps.size, steps.toSet().size)
    }

    @Test fun `进度里不出现恢复成功这种话`() {
        val steps = RestoreModel.STEP_READING + RestoreModel.STEP_OPENING + RestoreModel.STEP_INSTALLING
        assertFalse(steps.contains("成功"))
        assertFalse(steps.contains("完成"))
    }

    // ───────────────────── 失败文案 ─────────────────────

    @Test fun `八条失败文案互不重样`() {
        val all = Failure.values().map { RestoreModel.failureMessage(it) }
        assertEquals(8, all.size)
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun `每一条失败文案都写出源文件没有被改动`() {
        Failure.values().forEach {
            assertTrue(
                "$it 少了那句话",
                RestoreModel.failureMessage(it).contains(RestoreModel.UNTOUCHED_CLAUSE),
            )
        }
    }

    @Test fun `八条失败文案各自给出不同的下一步`() {
        assertTrue(RestoreModel.failureMessage(Failure.WrongPassword).contains("再试一次"))
        assertTrue(RestoreModel.failureMessage(Failure.Corrupted).contains("换一份备份"))
        assertTrue(RestoreModel.failureMessage(Failure.NotVaultFile).contains(".lvault"))
        assertTrue(RestoreModel.failureMessage(Failure.TooNew).contains("升级"))
        assertTrue(RestoreModel.failureMessage(Failure.UnsupportedKdf).contains("换一台设备"))
        assertTrue(RestoreModel.failureMessage(Failure.VaultExists).contains("删除"))
        assertTrue(RestoreModel.failureMessage(Failure.Io).contains("拷到本机"))
        assertTrue(RestoreModel.failureMessage(Failure.Unknown).contains("再试"))
    }

    @Test fun `口令错的那条不许说文件坏了`() {
        val m = RestoreModel.failureMessage(Failure.WrongPassword)
        assertFalse(m.contains("损坏"))
        assertFalse(m.contains("坏"))
        // 也不该在这儿劝人换一份备份 —— 文件多半是好的，错的是口令
        assertFalse(m.contains("换一份"))
    }

    @Test fun `版本太新那条绝不劝人拿更早的备份将就`() {
        val m = RestoreModel.failureMessage(Failure.TooNew)
        assertTrue(m.contains("别拿更早的备份将就") || m.contains("不要拿更早"))
    }

    @Test fun `算法不支持那条要撇清文件和口令`() {
        val m = RestoreModel.failureMessage(Failure.UnsupportedKdf)
        assertTrue(m.contains("不是文件的问题"))
        assertTrue(m.contains("不是主密码的问题"))
    }

    @Test fun `没装成的那几条要说清这台设备上什么都没留下`() {
        assertTrue(RestoreModel.failureMessage(Failure.Unknown).contains("没有留下"))
    }

    @Test fun `选错文件那条要说明改过文件名也没关系`() {
        assertTrue(RestoreModel.failureMessage(Failure.NotVaultFile).contains("改过文件名"))
    }
}
