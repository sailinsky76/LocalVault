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
import cn.localvault.app.ui.settings.SettingsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页内核。
 *
 * 这里盯着的都是「文案会不会变成谎话」这一类事，而它们在界面上几乎验不出来：
 *
 *  - **档位表不许悄悄改写用户的值**。库可能是从别的设备拷来的、也可能是
 *    将来某个版本写下的。四舍五入到最近一档的表现是「我什么都没点，
 *    自动锁定自己从 45 秒变成了 30 秒」，而屏幕上没有任何地方交代是谁改的。
 *  - **同一个 `0` 在两张表里意思正好相反**（自动锁定 = 立即，剪贴板 = 永不清）。
 *    共用一套「0 = 关闭」的文案，结果是把最安全的一档和最危险的一档写成同一个词。
 *  - **只在有代价的时候说话**。每一档都配一句说明的设置页，读起来像免责声明，
 *    用户学会的是跳过所有小字。
 *  - **权限清单只能有一条，而且不许出现网络**。「应用信息里的权限列表是空的」
 *    是这个产品全部的底气；哪天有人往 Manifest 里加一条却忘了改关于页，
 *    那一页就当场变成谎话——而且是最难被发现的那种，因为界面看起来完全正常。
 */
class SettingsModelTest {

    /* ═════════════ 档位表与默认值 ═════════════ */

    @Test
    fun `默认值必须在两张档位表里，且和 VaultMeta 的默认值一致`() {
        val meta = VaultMeta()
        assertEquals(SettingsModel.DEFAULT_AUTO_LOCK, meta.autoLockSeconds)
        assertEquals(SettingsModel.DEFAULT_CLIPBOARD, meta.clipboardClearSeconds)
        assertTrue(meta.autoLockSeconds in SettingsModel.AUTO_LOCK_STEPS)
        assertTrue(meta.clipboardClearSeconds in SettingsModel.CLIPBOARD_STEPS)
    }

    @Test
    fun `自动锁定档位升序，最安全的「立即」排在最前`() {
        val steps = SettingsModel.AUTO_LOCK_STEPS
        assertEquals(0, steps.first())
        assertEquals(steps.sorted(), steps)
    }

    @Test
    fun `剪贴板档位里 0 排在最后，其余升序 —— 两张表都按「从最安全到最不安全」排`() {
        val steps = SettingsModel.CLIPBOARD_STEPS
        assertEquals(0, steps.last())
        val timed = steps.dropLast(1)
        assertEquals(timed.sorted(), timed)
        assertFalse("0 只能出现一次", timed.contains(0))
    }

    @Test
    fun `没有「永不自动锁定」这一档，最长到 5 分钟为止`() {
        // 一个永不自动锁定的密码管理器，在手机被顺走的那一刻等于没有密码管理器。
        // 用户想要「永不」的真实动机几乎总是「老是要重新解锁太烦了」，
        // 那件事的正解是快捷解锁，不是把门一直敞着。
        assertEquals(SettingsModel.LONG_AUTO_LOCK, SettingsModel.AUTO_LOCK_STEPS.last())
        assertTrue(SettingsModel.AUTO_LOCK_STEPS.all { it <= 300 })
    }

    /* ═════════════ 不在表里的值：插进去，不改写 ═════════════ */

    @Test
    fun `自动锁定：表里没有的值被插进来而不是四舍五入`() {
        val opts = SettingsModel.autoLockOptions(45)
        assertTrue(opts.contains(45))
        assertEquals(SettingsModel.AUTO_LOCK_STEPS.size + 1, opts.size)
        assertEquals("插进来之后仍要升序", opts.sorted(), opts)
    }

    @Test
    fun `剪贴板：表里没有的值插在计时档中间，0 仍然留在最后`() {
        val opts = SettingsModel.clipboardOptions(45)
        assertTrue(opts.contains(45))
        assertEquals(SettingsModel.CLIPBOARD_STEPS.size + 1, opts.size)
        assertEquals(0, opts.last())
        val timed = opts.dropLast(1)
        assertEquals(timed.sorted(), timed)
    }

    @Test
    fun `表里已有的值不会被插第二遍`() {
        assertEquals(SettingsModel.AUTO_LOCK_STEPS, SettingsModel.autoLockOptions(60))
        assertEquals(SettingsModel.CLIPBOARD_STEPS, SettingsModel.clipboardOptions(15))
    }

    @Test
    fun `负数归一成 0，不会多出第二个「立即」`() {
        // 库文件是用户拿得到的（决策⑤），里面躺着一个 -1 并非不可能
        assertEquals(SettingsModel.AUTO_LOCK_STEPS, SettingsModel.autoLockOptions(-1))
        assertEquals(SettingsModel.CLIPBOARD_STEPS, SettingsModel.clipboardOptions(-1))
        assertEquals("立即", SettingsModel.autoLockLabel(-1))
        assertEquals("不自动清除", SettingsModel.clipboardLabel(-5))
    }

    @Test
    fun `远超上限的值照实显示，不被截断到最长档`() {
        // 「设置页是用来显示用户的库的，不是用来悄悄改写它的」
        val opts = SettingsModel.autoLockOptions(86_400)
        assertTrue(opts.contains(86_400))
        assertEquals(86_400, opts.last())
    }

    @Test
    fun `取档位不会改动档位表本身`() {
        val before = SettingsModel.AUTO_LOCK_STEPS.toList()
        SettingsModel.autoLockOptions(45)
        SettingsModel.autoLockOptions(7)
        assertEquals(before, SettingsModel.AUTO_LOCK_STEPS)
    }

    /* ═════════════ 文案 ═════════════ */

    @Test
    fun `自动锁定的文案`() {
        assertEquals("立即", SettingsModel.autoLockLabel(0))
        assertEquals("30 秒", SettingsModel.autoLockLabel(30))
        assertEquals("1 分钟", SettingsModel.autoLockLabel(60))
        assertEquals("5 分钟", SettingsModel.autoLockLabel(300))
        assertEquals("1 分 30 秒", SettingsModel.autoLockLabel(90))
    }

    @Test
    fun `剪贴板的文案`() {
        assertEquals("15 秒", SettingsModel.clipboardLabel(15))
        assertEquals("2 分钟", SettingsModel.clipboardLabel(120))
        assertEquals("不自动清除", SettingsModel.clipboardLabel(0))
    }

    @Test
    fun `同一个 0 在两处意思相反，文案绝不能一样`() {
        val a = SettingsModel.autoLockLabel(0)
        val c = SettingsModel.clipboardLabel(0)
        assertFalse("最安全的一档和最危险的一档不能叫同一个名字", a == c)
        assertFalse("剪贴板的 0 不是「立即」", c.contains("立即"))
        // 也不叫「关闭」「永不」：那两个词描述的是开关，
        // 而用户要判断的是「东西会不会自己消失」
        assertFalse(c.contains("关闭"))
        assertFalse(c.contains("永不"))
    }

    @Test
    fun `关掉剪贴板自动清除必须给出说明，而且要指出手动清除在哪儿`() {
        val note = SettingsModel.clipboardNote(0)
        assertNotNull(note)
        assertTrue("只警告不给出路等于把责任推给用户就走开", note!!.contains("立即清除"))
    }

    @Test
    fun `剪贴板的正常档位不出说明`() {
        assertNull(SettingsModel.clipboardNote(15))
        assertNull(SettingsModel.clipboardNote(60))
        assertNull(SettingsModel.clipboardNote(120))
    }

    @Test
    fun `「立即」自动锁定必须交代系统界面另有宽限`() {
        // 不交代的话没人敢选这一档 —— 而它以前确实是走不通的（决策⑳）
        val note = SettingsModel.autoLockNote(0)
        assertNotNull(note)
        assertTrue(note!!.contains("宽限"))
    }

    @Test
    fun `最长那一档必须如实说明代价`() {
        assertNotNull(SettingsModel.autoLockNote(SettingsModel.LONG_AUTO_LOCK))
    }

    @Test
    fun `中间几档一律不出说明`() {
        assertNull(SettingsModel.autoLockNote(15))
        assertNull(SettingsModel.autoLockNote(30))
        assertNull(SettingsModel.autoLockNote(60))
        assertNull(SettingsModel.autoLockNote(120))
    }

    /* ═════════════ 备份行副标题 ═════════════ */

    @Test
    fun `从未备份过时是要紧的`() {
        val s = SettingsModel.backupSummary(lastBackupAt = 0L, changedSince = 0)
        assertTrue(s.urgent)
        assertTrue(s.text.contains("从未"))
    }

    @Test
    fun `有改动没进备份时报出条数，并且是要紧的`() {
        val s = SettingsModel.backupSummary(lastBackupAt = 1_000L, changedSince = 7)
        assertTrue(s.urgent)
        assertTrue(s.text.contains("7"))
    }

    @Test
    fun `都没问题时只报事实，不写「已是最新」这类夸奖`() {
        // now 必须是个真实量级的时间戳：早先这里写的是 10_000_000L，
        // 减掉三天之后是负数，于是走进了「从未备份过」那一支，
        // 这条用例其实一直在考别的东西。
        val now = 1_700_000_000_000L
        val s = SettingsModel.backupSummary(lastBackupAt = now - 3 * 86_400_000L, changedSince = 0, now = now)
        assertFalse(s.urgent)
        assertTrue(s.text.contains("上次备份")) // 钉住走的是哪一支，免得又拿别的分支来充数
        listOf("最新", "安全", "很好", "无需").forEach {
            assertFalse("决策㉞：拿一整行屏幕说废话，看多了会让要紧的那条也被略过", s.text.contains(it))
        }
    }

    @Test
    fun `从未备份优先于改动条数 —— 两条都成立时说的是更严重的那句`() {
        val s = SettingsModel.backupSummary(lastBackupAt = 0L, changedSince = 30)
        assertTrue(s.text.contains("从未"))
    }

    /* ═════════════ 关于页 ═════════════ */

    @Test
    fun `权限清单只有一条`() {
        assertEquals(1, SettingsModel.PERMISSIONS.size)
    }

    @Test
    fun `权限清单里不许出现网络或存储`() {
        // 这条拦不住有人同时改 Manifest 和这里，但它能保证那次修改是**故意的**
        val all = SettingsModel.PERMISSIONS.joinToString(" ")
        listOf("INTERNET", "网络", "存储", "STORAGE", "位置", "通讯录").forEach {
            assertFalse("权限清单里冒出了 $it", all.contains(it))
        }
        assertTrue(all.contains("USE_BIOMETRIC"))
    }

    @Test
    fun `自动填充那一段不许混进权限清单`() {
        // BIND_AUTOFILL_SERVICE 是写在 <service> 上的一道锁（只有 system_server
        // 持有它），不是这个应用申请的能力。写进权限清单，用户去系统里核对会对不上，
        // 而这一页的全部价值就在于每一条都能被自己核实
        val perms = SettingsModel.PERMISSIONS.joinToString(" ")
        assertFalse(perms.contains("BIND_AUTOFILL"))
        assertFalse(perms.contains("自动填充"))
        assertEquals(1, SettingsModel.PERMISSIONS.size)
    }

    @Test
    fun `自动填充那一段要说清它读得到什么读不到什么`() {
        val all = SettingsModel.AUTOFILL_NOTE.joinToString(" ")
        assertTrue("要说清它不设为默认就不出现", all.contains("默认"))
        assertTrue("要说清它读不到用户打的字", all.contains("读不到"))
        assertTrue("要说清填充条上没有密码", all.contains("不显示密码"))
    }

    @Test
    fun `自动填充那一段不许把系统那屏警告说成没事`() {
        // 「它将能够看到你屏幕上的内容」是系统对所有填充服务说的同一句话。
        // 接下半句可以，把它抹掉不行——一句听起来像背书的话，
        // 会让用户在真该停下来看一眼的时候放心地点下去（同 BrowserTrust 那一条）
        val all = SettingsModel.AUTOFILL_NOTE.joinToString(" ")
        listOf("不用担心", "完全安全", "绝对安全", "忽略").forEach {
            assertFalse("这一段不许安抚：「$it」", all.contains(it))
        }
    }

    @Test
    fun `「没有的东西」清单里的每一条都能被用户自己核实`() {
        // 不写「军工级」「绝对安全」这类不能核实的词：它们没有信息量，
        // 还会连累旁边那几条真话
        val all = SettingsModel.ABSENCES.joinToString(" ")
        listOf("军工", "绝对安全", "银行级", "世界领先", "最安全").forEach {
            assertFalse("关于页写的是事实，不是故事：不该出现「$it」", all.contains(it))
        }
        assertTrue(SettingsModel.ABSENCES.isNotEmpty())
    }

    @Test
    fun `降级到 PBKDF2 时关于页必须写出来`() {
        val degraded = SettingsModel.aboutFacts(
            versionName = "0.1.0",
            kdfLabel = "PBKDF2-SHA512 600k",
            cipherLabel = "AES-256-GCM",
            argon2Available = false,
            entryCount = 3,
            vaultBytes = 4321,
            createdAt = 1_700_000_000_000L,
        )
        val kdf = degraded.first { it.label == "密钥派生" }
        assertTrue(kdf.value.contains("PBKDF2"))
        assertTrue("必须说明这是降级，不能只是换个参数悄悄过去", kdf.value.contains("降级"))
    }

    @Test
    fun `Argon2 可用时不多加任何括号`() {
        val ok = SettingsModel.aboutFacts(
            versionName = "0.1.0",
            kdfLabel = "Argon2id 64MiB/t3",
            cipherLabel = "AES-256-GCM",
            argon2Available = true,
            entryCount = 3,
            vaultBytes = 4321,
            createdAt = 1_700_000_000_000L,
        )
        assertEquals("Argon2id 64MiB/t3", ok.first { it.label == "密钥派生" }.value)
    }

    @Test
    fun `关于页每一行的名字都不重复`() {
        val facts = SettingsModel.aboutFacts(
            versionName = "0.1.0",
            kdfLabel = "Argon2id 64MiB/t3",
            cipherLabel = "AES-256-GCM",
            argon2Available = true,
            entryCount = 12,
            vaultBytes = 8192,
            createdAt = 1_700_000_000_000L,
        )
        assertEquals(facts.size, facts.map { it.label }.toSet().size)
    }

    @Test
    fun `建库时间为 0 时写「未知」而不是 1970 年`() {
        val facts = SettingsModel.aboutFacts(
            versionName = "0.1.0",
            kdfLabel = "Argon2id 64MiB/t3",
            cipherLabel = "AES-256-GCM",
            argon2Available = true,
            entryCount = 0,
            vaultBytes = 0,
            createdAt = 0L,
        )
        assertEquals("未知", facts.first { it.label == "建库时间" }.value)
    }
}
