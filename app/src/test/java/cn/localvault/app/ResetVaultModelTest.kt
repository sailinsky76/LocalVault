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
import cn.localvault.app.ui.unlock.ResetVaultModel
import cn.localvault.app.ui.unlock.ResetVaultModel.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「忘了主密码，清空重来」的内核。
 *
 * 和删除页一样，这一步的风险不在代码里（删文件是三行 `File.delete()`），
 * 在**话**和**门槛**上：
 *
 *  - 门槛松了，就成了决策⑦ 明令不做的那个拒绝服务漏洞的手动版
 *    （任何拿到手机的人一按，全部密码没了）；
 *  - 门槛紧了，本人反而被挡在外面，而他绕一下就能走系统的「清除应用数据」，
 *    我们多设的那道门只折腾了他一个人；
 *  - 话说软了（「请联系客服」「稍后重试」），用户会一直等一个不会来的救援，
 *    而不是趁早去翻那份他其实存过的备份文件；
 *  - 话说满了（「彻底销毁」），那是撒谎（决策⑧）。
 *
 * 下面这些用例把这四条各自钉住。
 */
class ResetVaultModelTest {

    /* ═════════════ 门槛之一：抄写 ═════════════ */

    @Test
    fun `一字不差才算抄对`() {
        assertTrue(ResetVaultModel.matches("我没有主密码了"))
        assertEquals("我没有主密码了", ResetVaultModel.PHRASE)
    }

    @Test
    fun `错一个字就不算`() {
        assertFalse(ResetVaultModel.matches("我没有主密码啦"))
        assertFalse(ResetVaultModel.matches("我没有主秘密了"))
    }

    @Test
    fun `少字多字都不算`() {
        assertFalse(ResetVaultModel.matches("我没有主密码"))
        assertFalse(ResetVaultModel.matches("我没有主密码了了"))
        assertFalse(ResetVaultModel.matches(""))
        assertFalse(ResetVaultModel.matches("   "))
    }

    @Test
    fun `空白宽容：输入法带出来的空格不该卡住本人`() {
        // 中文输入法候选上屏时常带出空格，这不是抄错。
        assertTrue(ResetVaultModel.matches("  我没有主密码了  "))
        assertTrue(ResetVaultModel.matches("我没有 主密码了"))
        assertTrue(ResetVaultModel.matches("我 没 有 主 密 码 了"))
    }

    @Test
    fun `结尾顺手点的句号叹号不算错`() {
        assertTrue(ResetVaultModel.matches("我没有主密码了。"))
        assertTrue(ResetVaultModel.matches("我没有主密码了！"))
        assertTrue(ResetVaultModel.matches("我没有主密码了."))
    }

    @Test
    fun `中间的标点不宽容`() {
        // 结尾的标点是「抄完了顺手点一个」，中间多一个逗号说明他没在照着抄。
        assertFalse(ResetVaultModel.matches("我没有，主密码了"))
        assertFalse(ResetVaultModel.matches("我。没有主密码了"))
    }

    @Test
    fun `繁体不放行`() {
        // 繁体输入法打出来的是另一句话。放行等于承认「差不多就行」，
        // 而这道门拦的正是「差不多就点了」。
        assertFalse(ResetVaultModel.matches("我沒有主密碼了"))
    }

    @Test
    fun `抄的是一句关于自己的陈述，不是一个命令词`() {
        // 抄「删除」「DELETE」只是打字；抄这一句，记得主密码的人要打的字是假的。
        assertFalse(ResetVaultModel.PHRASE.contains("删除"))
        assertFalse(ResetVaultModel.PHRASE.contains("清空"))
        assertTrue(ResetVaultModel.PHRASE.startsWith("我"))
    }

    @Test
    fun `输入框下面那句话要把这道门在防什么直说出来`() {
        // 不说的话，用户学会的是「照着抄就行」，这道门就只剩仪式了。
        val h = ResetVaultModel.PHRASE_HINT
        assertTrue(h.contains("记得主密码"))
        assertTrue(h.contains("停下") || h.contains("假"))
    }

    /* ═════════════ 门槛之二：按住 ═════════════ */

    @Test
    fun `按住三秒，不多不少`() {
        // 再长就越过了那把尺子：比系统的「清除应用数据」还折腾，
        // 而系统那条路本人也走得通，多设的门只折腾本人。
        assertEquals(3000L, ResetVaultModel.HOLD_MILLIS)
    }

    @Test
    fun `按住的过程一直报还剩几秒`() {
        // 没有秒数的话，「正在按住」和「卡死了」在手感上分不出来。
        assertTrue(ResetVaultModel.holdLabel(3000L).contains("3"))
        assertTrue(ResetVaultModel.holdLabel(2400L).contains("3"))   // 向上取整
        assertTrue(ResetVaultModel.holdLabel(1200L).contains("2"))
        assertTrue(ResetVaultModel.holdLabel(200L).contains("1"))
    }

    @Test
    fun `剩余时间归零时不显示 0 秒`() {
        // 到 0 的那一刻动作就发生了，屏幕上出现「还剩 0 秒」只能是算错了。
        assertFalse(ResetVaultModel.holdLabel(0L).contains("0"))
        assertFalse(ResetVaultModel.holdLabel(-100L).contains("0"))
    }

    @Test
    fun `按钮上写的是按住不是点击`() {
        assertTrue(ResetVaultModel.BUTTON_IDLE.contains("按住"))
        assertFalse(ResetVaultModel.BUTTON_IDLE.contains("点击"))
        assertTrue(ResetVaultModel.HOLD_HINT.contains("松手"))
    }

    @Test
    fun `抄错时按钮是灰的，不是按了给一句抄错了`() {
        assertFalse(ResetVaultModel.canArm("我没有主密码", busy = false))
        assertTrue(ResetVaultModel.canArm("我没有主密码了", busy = false))
    }

    @Test
    fun `正在清空时按钮不再响应`() {
        assertFalse(ResetVaultModel.canArm("我没有主密码了", busy = true))
    }

    /* ═════════════ 第一屏说的话 ═════════════ */

    @Test
    fun `副标题第一句就说明它帮不了你找回`() {
        // 走到这儿的人多半抱着侥幸，让他读到第三段才发现，会觉得被绕了一圈。
        assertTrue(ResetVaultModel.LEAD.contains("拿回来") || ResetVaultModel.LEAD.contains("帮不了"))
        assertTrue(ResetVaultModel.LEAD.contains("删掉"))
    }

    @Test
    fun `说清楚没有后门，包括我们自己`() {
        assertTrue(ResetVaultModel.NO_RECOVERY.contains("我们"))
        assertTrue(ResetVaultModel.NO_RECOVERY.contains("副本") || ResetVaultModel.NO_RECOVERY.contains("绕过"))
    }

    @Test
    fun `备份文件和它当时的主密码是两样东西，缺一不可`() {
        // 最常见的误会：以为「我有备份文件」就等于安全，
        // 而那份文件认的是导出当时的主密码（改过的话就是旧的，决策(114)）。
        val t = ResetVaultModel.BACKUP_IS_THE_ONLY_WAY
        assertTrue(t.contains(".lvault"))
        assertTrue(t.contains("主密码"))
        assertTrue(t.contains("缺一") || t.contains("两样"))
    }

    @Test
    fun `我们答不上来的两件事用问句还给用户`() {
        assertEquals(2, ResetVaultModel.QUESTIONS.size)
        ResetVaultModel.QUESTIONS.forEach {
            assertTrue("「$it」该是个问句", it.contains("？"))
        }
        assertTrue(ResetVaultModel.QUESTIONS[0].contains(".lvault"))
        assertTrue(ResetVaultModel.QUESTIONS[1].contains("主密码"))
    }

    @Test
    fun `主动交代为什么这一页说不出库里有多少条`() {
        // 用户在删除页上见过那张事实清单，这一页突然一条都没有，
        // 不解释一下会显得像是没做完。
        val t = ResetVaultModel.NO_INVENTORY_NOTE
        assertTrue(t.contains("锁着"))
        assertTrue(t.contains("读不到") || t.contains("不该读"))
    }

    @Test
    fun `系统那条路写出来了，而且写明我们不比它更容易`() {
        val t = ResetVaultModel.SYSTEM_PATH_NOTE
        assertTrue(t.contains("清除数据"))
        assertTrue(t.contains("更容易"))
    }

    /* ═════════════ 会没什么、不会没什么 ═════════════ */

    @Test
    fun `没绑快捷解锁时一个字都不提指纹和 PIN`() {
        val list = ResetVaultModel.collateral(pinEnrolled = false, biometricEnrolled = false)
        list.forEach {
            assertFalse("「$it」不该提指纹", it.contains("指纹"))
            assertFalse("「$it」不该提 PIN", it.contains("PIN"))
        }
    }

    @Test
    fun `绑了就要写明安全芯片里那把钥匙也一起删`() {
        val list = ResetVaultModel.collateral(pinEnrolled = true, biometricEnrolled = true)
        val line = list.first { it.contains("指纹") }
        assertTrue(line.contains("PIN"))
        assertTrue(line.contains("安全芯片"))
    }

    @Test
    fun `只绑了一样就只说那一样`() {
        val onlyBio = ResetVaultModel.collateral(pinEnrolled = false, biometricEnrolled = true)
        assertTrue(onlyBio.any { it.contains("指纹") })
        assertFalse(onlyBio.any { it.contains("PIN") })

        val onlyPin = ResetVaultModel.collateral(pinEnrolled = true, biometricEnrolled = false)
        assertTrue(onlyPin.any { it.contains("PIN") })
        assertFalse(onlyPin.any { it.contains("指纹") })
    }

    @Test
    fun `退避的账也一起清，而且说出来`() {
        // 不清的话，下一个库会替上一个库背账——
        // 「刚建好的库，第一次解锁就被告知还要等 15 分钟」，那种界面没人看得懂。
        ResetVaultModel.collateral(false, false).let { list ->
            assertTrue(list.any { it.contains("失败计数") || it.contains("等待时间") })
        }
    }

    @Test
    fun `要写明全程没有解开过库`() {
        // 用户听到「删掉全部条目」时容易以为程序先读了一遍才删的。
        val list = ResetVaultModel.collateral(false, false)
        assertTrue(list.any { it.contains("密文") })
    }

    @Test
    fun `导出到别处的备份不受影响，这是全屏唯一的好消息`() {
        val t = ResetVaultModel.EXPORTS_NOTE
        assertTrue(t.contains("不受影响"))
        assertTrue(t.contains("拿它们回来") || t.contains("还在"))
    }

    /* ═════════════ 覆写擦除：两页说的必须是同一件事 ═════════════ */

    @Test
    fun `擦除说明是删除页那一份的引用，不是抄的第二份`() {
        // 闪存怎么回事只有一个事实。两页各写一份，迟早有一页被改而另一页没有，
        // 到那时用户会在两个界面上读到两种说法。
        assertSame(DeleteVaultModel.ERASURE_NOTE, ResetVaultModel.ERASURE_NOTE)
    }

    @Test
    fun `擦除说明既不吹牛也不含糊`() {
        val t = ResetVaultModel.ERASURE_NOTE
        listOf("粉碎", "彻底销毁", "军工级", "安全擦除").forEach {
            assertFalse("不该出现「$it」", t.contains(it))
        }
        assertTrue(t.contains("全盘加密"))
        assertTrue(t.contains("磨损均衡"))
    }

    /* ═════════════ 失败了说什么 ═════════════ */

    @Test
    fun `失败时不说库还在数据一条没少那种安慰`() {
        // 删除页那边这句话是安慰（用户怕删了一半）；
        // 这一页的用户要的就是删掉，「一条没少」在这儿是坏消息。
        Failure.entries.forEach { f ->
            val m = ResetVaultModel.failureMessage(f)
            assertFalse("「$m」不该拿「一条没少」当安慰", m.contains("一条没少"))
        }
    }

    @Test
    fun `每条失败都得跟上一句还能怎么办`() {
        Failure.entries.forEach { f ->
            val m = ResetVaultModel.failureMessage(f)
            assertTrue("「$m」要给出下一步", m.contains("清除数据"))
            assertTrue("「$m」要说清库还在哪儿", m.contains("这台设备"))
        }
    }

    @Test
    fun `FilesRemain 那条要交代指纹和 PIN 已经先没了`() {
        // 这是唯一一种留下了副作用的失败：库还在，快捷解锁却已经关了。
        val m = ResetVaultModel.failureMessage(Failure.FilesRemain)
        assertTrue(m.contains("指纹"))
        assertTrue(m.contains("之前"))
    }

    @Test
    fun `两条失败文案不重样`() {
        assertNotEquals(
            ResetVaultModel.failureMessage(Failure.FilesRemain),
            ResetVaultModel.failureMessage(Failure.Unknown),
        )
    }

    @Test
    fun `没有输错口令那一支`() {
        // 这一页压根没有口令要核对，那正是它存在的理由。
        assertEquals(2, Failure.entries.size)
        assertFalse(Failure.entries.any { it.name.contains("Password") })
    }

    /* ═════════════ 解锁页那个弹窗的落点 ═════════════ */

    @Test
    fun `弹窗上补的那句话要说明清空不等于找回`() {
        val t = ResetVaultModel.DIALOG_SECONDARY_NOTE
        assertTrue(t.contains("不会"))
        assertTrue(t.contains("从头开始") || t.contains("清空"))
    }

    @Test
    fun `次按钮的字里没有删除两个字的命令腔`() {
        // 这是弹窗上的次按钮，主按钮是「我再想想」。
        assertTrue(ResetVaultModel.DIALOG_SECONDARY.contains("从头开始"))
    }

    /* ═════════════ 整页的用词底线 ═════════════ */

    private fun everything(): List<String> = buildList {
        add(ResetVaultModel.TITLE)
        add(ResetVaultModel.LEAD)
        add(ResetVaultModel.NO_RECOVERY)
        add(ResetVaultModel.BACKUP_IS_THE_ONLY_WAY)
        addAll(ResetVaultModel.QUESTIONS)
        add(ResetVaultModel.NO_INVENTORY_NOTE)
        add(ResetVaultModel.SYSTEM_PATH_NOTE)
        addAll(ResetVaultModel.collateral(true, true))
        add(ResetVaultModel.EXPORTS_NOTE)
        add(ResetVaultModel.ERASURE_NOTE)
        add(ResetVaultModel.PHRASE_LABEL)
        add(ResetVaultModel.PHRASE_HINT)
        add(ResetVaultModel.BUTTON_IDLE)
        add(ResetVaultModel.HOLD_HINT)
        add(ResetVaultModel.STEP_PURGING)
        add(ResetVaultModel.STEP_DELETING)
        add(ResetVaultModel.DIALOG_SECONDARY)
        add(ResetVaultModel.DIALOG_SECONDARY_NOTE)
        addAll(Failure.entries.map { ResetVaultModel.failureMessage(it) })
    }

    @Test
    fun `整页不许暗示还有救援在路上`() {
        // 一句安慰会让用户一直等一个永远不会来的救援，
        // 而不是趁早去翻那份他其实存过的备份文件。
        everything().forEach { t ->
            listOf("联系客服", "稍后重试", "破解", "找回主密码", "技术支持").forEach {
                assertFalse("「$t」里不该出现「$it」", t.contains(it))
            }
        }
    }

    @Test
    fun `整页不许暗示这一步可以撤销`() {
        everything().forEach { t ->
            listOf("可恢复", "30 天", "撤销", "回收站").forEach {
                assertFalse("「$t」里不该出现「$it」", t.contains(it))
            }
        }
    }

    @Test
    fun `整页不许出现成功两个字`() {
        // 跟一个刚丢掉全部密码的人说「清空成功」，那两个字是在庆祝他的损失。
        everything().forEach { t ->
            assertFalse("「$t」里不该出现「成功」", t.contains("成功"))
        }
    }

    @Test
    fun `整页不许吓唬人`() {
        // 真心想清空的人会被吓唬话激怒；误点进来的人在第一屏就会退出去。
        everything().forEach { t ->
            listOf("谨慎操作", "警告！", "三思", "严重后果").forEach {
                assertFalse("「$t」里不该出现「$it」", t.contains(it))
            }
        }
    }
}
