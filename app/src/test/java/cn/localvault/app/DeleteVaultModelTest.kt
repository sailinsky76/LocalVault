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

import cn.localvault.app.ui.settings.DeleteVaultModel
import cn.localvault.app.ui.settings.DeleteVaultModel.BackupStand
import cn.localvault.app.ui.settings.DeleteVaultModel.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删除保险库的内核。
 *
 * 这一页是全 App 唯一一个真正不可逆的动作，而它的风险不在代码里——
 * 删文件本身是三行 `File.delete()`，几乎不可能写错。
 * 会出事的是**话**：
 *
 *  - 说了「彻底销毁 / 粉碎」，而我们其实只是删文件（决策⑧）；
 *  - 说了「你的备份是最新的，可以放心删」，而备份文件在不在、
 *    它的口令还记不记得，我们一件都不知道；
 *  - 失败的时候没说清「库还在」，用户以为删了一半；
 *  - 弹窗里顺手写出条目名称，而这一屏很可能正被站在旁边的人看着。
 *
 * 所以这些判断全部搬进纯 Kotlin 对象，由下面这些用例钉住。
 */
class DeleteVaultModelTest {

    private val NOW = 1_700_000_000_000L
    private val DAY = 24 * 3600 * 1000L

    /* ═════════════ 事实清单 ═════════════ */

    @Test
    fun `事实清单只有数量大小时间，一条条目内容都没有`() {
        val facts = DeleteVaultModel.facts(
            DeleteVaultModel.Inventory(entries = 37, fileBytes = 4321, createdAt = NOW - 30 * DAY),
            now = NOW,
        )
        assertEquals(3, facts.size)
        assertEquals(listOf("条目", "库文件", "建于"), facts.map { it.label })
        assertTrue(facts[0].value.contains("37"))
    }

    @Test
    fun `建库时间为 0 时写未知，不写 1970 年`() {
        // 关于页那一条同样的规矩：一个显示「1970-01-01」的界面
        // 会让人怀疑旁边那些数字也不可信。
        val facts = DeleteVaultModel.facts(
            DeleteVaultModel.Inventory(entries = 1, fileBytes = 100, createdAt = 0L),
            now = NOW,
        )
        assertEquals("未知", facts[2].value)
        assertFalse(facts[2].value.contains("1970"))
    }

    @Test
    fun `空库照实写 0 条，不藏起来`() {
        val facts = DeleteVaultModel.facts(
            DeleteVaultModel.Inventory(entries = 0, fileBytes = 96, createdAt = NOW),
            now = NOW,
        )
        assertTrue(facts[0].value.contains("0"))
    }

    /* ═════════════ 备份处境的三分 ═════════════ */

    @Test
    fun `从来没备份过是 Never`() {
        assertEquals(BackupStand.Never, DeleteVaultModel.backupStand(0L, changedSince = 0))
        // 没备份过时 VaultIndex.changedSince 会把全部条目算进去，这不该改变判定
        assertEquals(BackupStand.Never, DeleteVaultModel.backupStand(0L, changedSince = 37))
    }

    @Test
    fun `备份过、之后又改过条目是 Stale`() {
        assertEquals(BackupStand.Stale, DeleteVaultModel.backupStand(NOW - DAY, changedSince = 3))
    }

    @Test
    fun `备份过、之后没动过是 Fresh`() {
        assertEquals(BackupStand.Fresh, DeleteVaultModel.backupStand(NOW - DAY, changedSince = 0))
    }

    @Test
    fun `判定和列表页那条提醒条是同一套口径`() {
        // 决策㉞：按「改了多少条」算，不按「多少天没备份」算。
        // 一年前备份、之后一条没改，依然是 Fresh——那份备份确实还是完好的。
        assertEquals(BackupStand.Fresh, DeleteVaultModel.backupStand(NOW - 365 * DAY, 0))
    }

    /* ═════════════ 备份状况的说法 ═════════════ */

    private fun notice(stand: BackupStand, changed: Int = 0, last: Long = NOW - DAY) =
        DeleteVaultModel.backupNotice(stand, changed, last, NOW)

    @Test
    fun `没备份过和备份过期都算严重，备份新鲜不算`() {
        assertTrue(notice(BackupStand.Never, last = 0L).severe)
        assertTrue(notice(BackupStand.Stale, changed = 3).severe)
        assertFalse(notice(BackupStand.Fresh).severe)
    }

    @Test
    fun `没备份过时必须把话说死`() {
        val t = notice(BackupStand.Never, last = 0L).text
        assertTrue(t.contains("从来没有导出过") || t.contains("从来没有"))
        assertTrue(t.contains("不会在任何地方还有一份"))
    }

    @Test
    fun `备份过期时要写出到底有几条没进备份`() {
        val t = notice(BackupStand.Stale, changed = 7).text
        assertTrue(t.contains("7"))
    }

    @Test
    fun `备份新鲜时也不说可以放心删`() {
        // 我们只知道「导出过、而且那次校验通过了」。
        // 文件还在不在、它的口令还记不记得（改过主密码的话认的是旧的），
        // 这三件事一件都看不见——说「放心」等于替用户下三个保证。
        val t = notice(BackupStand.Fresh).text
        assertFalse(t.contains("放心"))
        assertFalse(t.contains("可以安全"))
        // 取而代之的是把那两件我们看不见的事用问句还给他
        assertTrue(t.contains("还在你手上吗") || t.contains("还记得吗"))
    }

    @Test
    fun `三种说法互不重样`() {
        val a = notice(BackupStand.Never, last = 0L).text
        val b = notice(BackupStand.Stale, changed = 2).text
        val c = notice(BackupStand.Fresh).text
        assertNotEquals(a, b); assertNotEquals(b, c); assertNotEquals(a, c)
    }

    /* ═════════════ 跟着一起没的东西 ═════════════ */

    @Test
    fun `没绑快捷解锁时一个字都不提指纹和 PIN`() {
        val l = DeleteVaultModel.collateral(pinEnrolled = false, biometricEnrolled = false)
        assertEquals(2, l.size)
        assertTrue(l.none { it.contains("指纹") || it.contains("PIN") })
    }

    @Test
    fun `绑了哪一项就只说哪一项`() {
        val onlyPin = DeleteVaultModel.collateral(pinEnrolled = true, biometricEnrolled = false)
        assertTrue(onlyPin.any { it.contains("PIN") })
        assertTrue(onlyPin.none { it.contains("指纹") })

        val onlyBio = DeleteVaultModel.collateral(pinEnrolled = false, biometricEnrolled = true)
        assertTrue(onlyBio.any { it.contains("指纹") })
        assertTrue(onlyBio.none { it.contains("PIN") })
    }

    @Test
    fun `两项都绑了合成一行说，不占两行`() {
        val both = DeleteVaultModel.collateral(pinEnrolled = true, biometricEnrolled = true)
        assertEquals(3, both.size)
        assertTrue(both[2].contains("指纹") && both[2].contains("PIN"))
    }

    @Test
    fun `清单里要写出安全芯片那把钥匙也会一起删`() {
        // 不写的话，用户会以为「关掉开关」和「删库」清掉的是同一批东西。
        val both = DeleteVaultModel.collateral(pinEnrolled = true, biometricEnrolled = true)
        assertTrue(both.any { it.contains("钥匙") })
    }

    @Test
    fun `清单第一条必须提到上一版备份副本`() {
        // VaultStorage 会留一个 .bak（原子写入的第二条命）。
        // 只说「删除保险库文件」而不提它，那句话就是不完整的。
        val l = DeleteVaultModel.collateral(false, false)
        assertTrue(l[0].contains("上一版") || l[0].contains("副本"))
    }

    /* ═════════════ 导出到别处的备份 ═════════════ */

    @Test
    fun `必须说清导出到别处的备份不会跟着没`() {
        val t = DeleteVaultModel.EXPORTS_NOTE
        assertTrue(t.contains("不受影响") || t.contains("独立"))
        // 而且要说清后果的另一半：想清干净得自己去删
        assertTrue(t.contains("自己去"))
    }

    /* ═════════════ 覆写擦除的实话（决策⑧）═════════════ */

    @Test
    fun `擦除说明里不许出现那几个撑场面的词`() {
        val t = DeleteVaultModel.ERASURE_NOTE
        listOf("粉碎", "彻底销毁", "军工级", "不可恢复地覆写", "安全擦除").forEach {
            assertFalse("擦除说明不该出现「$it」", t.contains(it))
        }
    }

    @Test
    fun `擦除说明必须给出真正管用的那个理由`() {
        val t = DeleteVaultModel.ERASURE_NOTE
        assertTrue(t.contains("全盘加密"))
        assertTrue(t.contains("磨损均衡") || t.contains("物理块"))
    }

    /* ═════════════ 能不能按下去 ═════════════ */

    @Test
    fun `主密码没填时按钮是灰的`() {
        assertFalse(DeleteVaultModel.canSubmit(passwordLength = 0, busy = false))
    }

    @Test
    fun `填了就能按，不要求任何长度`() {
        // 这里不校验长度：长度不对的后果是核对失败，那条路已经有文案了。
        // 在这儿再拦一次，只会让一个记得自己主密码很短的用户
        // 面对一个永远点不亮的按钮，而屏幕上没有一句话解释为什么。
        assertTrue(DeleteVaultModel.canSubmit(passwordLength = 1, busy = false))
    }

    @Test
    fun `正在删的时候按钮也是灰的`() {
        assertFalse(DeleteVaultModel.canSubmit(passwordLength = 12, busy = true))
    }

    @Test
    fun `主密码那句提示要说清指纹和 PIN 在这一步不算数`() {
        val t = DeleteVaultModel.PASSWORD_HINT
        assertTrue(t.contains("指纹") && t.contains("PIN"))
        assertTrue(t.contains("证明"))
    }

    @Test
    fun `想不起主密码的人要有一条出路，而且写的是实话`() {
        // 系统层面的「清除数据」我们本来就拦不住，藏着它不增加任何安全性，
        // 只会让一个合理的诉求变成一次卸载重装。
        val t = DeleteVaultModel.BLOCKED_HINT
        assertTrue(t.contains("清除数据"))
        assertTrue(t.contains("拦不住"))
    }

    /* ═════════════ 最后那个弹窗 ═════════════ */

    @Test
    fun `弹窗正文里只有条数，没有任何条目内容`() {
        val t = DeleteVaultModel.confirmMessage(37, BackupStand.Fresh)
        assertTrue(t.contains("37"))
        // 函数签名里根本收不到条目 —— 于是它连想写都写不出来。
        // 这一条和 PinSetupModel 那条「文案函数的入参里根本没有 PIN」是同一个手法。
    }

    @Test
    fun `弹窗必须写明没有撤销也没有回收站`() {
        val t = DeleteVaultModel.confirmMessage(5, BackupStand.Fresh)
        assertTrue(t.contains("没有撤销"))
        assertTrue(t.contains("回收站"))
    }

    @Test
    fun `没备份过的人在弹窗里再被告知一次`() {
        // 这是唯一一种「删完真的什么都不剩」的处境，也是最常见的一种
        // （跳过过首次备份的人）。页面上说过一次，按下按钮前再说一次。
        val never = DeleteVaultModel.confirmMessage(5, BackupStand.Never)
        val fresh = DeleteVaultModel.confirmMessage(5, BackupStand.Fresh)
        assertTrue(never.contains("没有导出过备份"))
        assertFalse(fresh.contains("没有导出过备份"))
        assertTrue(never.length > fresh.length)
    }

    @Test
    fun `空库时不写 0 条记录那种别扭话，但也不说删了没损失`() {
        val t = DeleteVaultModel.confirmMessage(0, BackupStand.Fresh)
        assertFalse(t.contains("0 条"))
        assertTrue(t.contains("没有撤销"))
    }

    @Test
    fun `主按钮写的是永久删除，次按钮是取消`() {
        // 主按钮上必须是那个动作本身，不能是「确定」——
        // 「确定」在弹窗里是最容易被无意识点掉的两个字。
        assertEquals("永久删除", DeleteVaultModel.CONFIRM_YES)
        assertEquals("取消", DeleteVaultModel.CONFIRM_NO)
        assertFalse(DeleteVaultModel.CONFIRM_YES.contains("确定"))
    }

    /* ═════════════ 失败了说什么 ═════════════ */

    @Test
    fun `三条失败文案每一条都必须说出保险库还在`() {
        // 删库失败时用户最怕的和改密码失败时是同一件事：「是不是删了一半」。
        Failure.entries.forEach { f ->
            val t = DeleteVaultModel.failureMessage(f)
            assertTrue("「$f」没说清库还在", t.contains("保险库还在") || t.contains("保险库没有被删除"))
        }
    }

    @Test
    fun `三条失败文案互不重样`() {
        val all = Failure.entries.map { DeleteVaultModel.failureMessage(it) }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `口令错时要说清连快捷解锁都没动`() {
        // 这一条是唯一一种「什么副作用都没有」的失败，说出来能省掉
        // 一次「我是不是得重新绑指纹」的疑心。
        val t = DeleteVaultModel.failureMessage(Failure.WrongPassword)
        assertTrue(t.contains("没有任何东西被改动"))
    }

    @Test
    fun `文件没删掉时要交代快捷解锁已经被关了，并给出怎么办`() {
        // 这是唯一一种留下副作用的失败。不说清楚的话，
        // 「下次解锁要输主密码了」看起来就像「删了一半」的证据。
        val t = DeleteVaultModel.failureMessage(Failure.FilesRemain)
        assertTrue(t.contains("快捷解锁"))
        assertTrue(t.contains("重新开"))
        assertTrue(t.contains("数据一条没少"))
    }

    @Test
    fun `失败文案里不出现损坏丢失这类吓人又不准的词`() {
        Failure.entries.forEach { f ->
            val t = DeleteVaultModel.failureMessage(f)
            listOf("损坏", "丢失", "崩溃").forEach {
                assertFalse("「$f」不该出现「$it」", t.contains(it))
            }
        }
    }

    /* ═════════════ 设置页那一行 ═════════════ */

    @Test
    fun `设置页那一行只陈述后果，不吓唬也不劝导`() {
        val t = DeleteVaultModel.ROW_SUBTITLE
        assertTrue(t.contains("无法恢复"))
        listOf("谨慎", "危险！", "警告", "三思").forEach {
            assertFalse("那一行不该出现「$it」", t.contains(it))
        }
    }

    @Test
    fun `设置页那一行要提到快捷解锁也会被清掉`() {
        // 这是用户在点进去之前唯一能知道「删的不止那个文件」的地方。
        assertTrue(DeleteVaultModel.ROW_SUBTITLE.contains("快捷解锁"))
    }

    /* ═════════════ 整页的用词底线 ═════════════ */

    @Test
    fun `整页任何一句话都不许暗示删除是可以撤销的`() {
        val all = buildList {
            add(DeleteVaultModel.ERASURE_NOTE)
            add(DeleteVaultModel.EXPORTS_NOTE)
            add(DeleteVaultModel.PASSWORD_HINT)
            add(DeleteVaultModel.BLOCKED_HINT)
            add(DeleteVaultModel.ROW_SUBTITLE)
            add(DeleteVaultModel.CONFIRM_TITLE)
            addAll(Failure.entries.map { DeleteVaultModel.failureMessage(it) })
            addAll(
                listOf(BackupStand.Never, BackupStand.Stale, BackupStand.Fresh)
                    .map { notice(it, changed = 1).text }
            )
        }
        all.forEach { t ->
            listOf("可恢复", "30 天", "撤销删除", "找回").forEach {
                assertFalse("「$t」里不该出现「$it」", t.contains(it))
            }
        }
    }
}
