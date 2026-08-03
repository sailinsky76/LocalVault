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
import cn.localvault.app.core.crypto.toUtf8Secure
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.core.vault.VaultFile
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.core.vault.VaultStorage
import cn.localvault.app.ui.backup.ExportController
import cn.localvault.app.ui.backup.ExportSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * 导出流程的单测。
 *
 * [ExportSink] 这个接口存在的意义在这里体现得最清楚：把 SAF 的
 * `Uri` / `ContentResolver` 关在实现里之后，「写出去的东西对不对」
 * 这件最要紧的事就能在纯 JVM 上验证，不必上机点一次文件选择器。
 *
 * 几个假 sink 分别模拟一类真实故障：写一半（存储满）、读不回来（provider 抽风）、
 * 写入直接抛错（没权限）。这三种在真机上都不好复现，但都会产出坏备份。
 */
class ExportControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val fast = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 1000, 1)
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private var now = 1_700_000_000_000L

    @After fun tearDown() = scope.cancel()

    // ── 假的导出目标 ──

    private class FakeSink(override val displayName: String = "下载/localvault.lvault") : ExportSink {
        var written: ByteArray? = null
        override fun write(bytes: ByteArray) { written = bytes.copyOf() }
        override fun readBack(): ByteArray? = written
    }

    /** 只写一半。模拟存储空间不足 / 写入被中断。 */
    private class HalfWriteSink : ExportSink {
        override val displayName = "半截文件"
        private var written: ByteArray? = null
        override fun write(bytes: ByteArray) { written = bytes.copyOf(bytes.size / 2) }
        override fun readBack(): ByteArray? = written
    }

    /** 写下去了但读不回来。模拟 provider 不支持回读。 */
    private class WriteOnlySink : ExportSink {
        override val displayName = "只写不读"
        override fun write(bytes: ByteArray) = Unit
        override fun readBack(): ByteArray? = null
    }

    private class FailingSink : ExportSink {
        override val displayName = "写不进去"
        override fun write(bytes: ByteArray) = throw IOException("空间不足")
        override fun readBack(): ByteArray? = null
    }

    // ── 夹具 ──

    private fun rig(): Triple<ExportController, VaultSession, VaultRepository> {
        val repo = VaultRepository(VaultStorage(tmp.root))
        val session = VaultSession(repo, scope, clock = { now })
        session.onVaultCreated(repo.create("correct horse battery".toCharArray(), fast))
        session.addEntry(VaultEntry(id = "", name = "某网站", username = "me", password = "s3cret"))
        val controller = ExportController(repo, session, scope, worker = Dispatchers.Unconfined)
        return Triple(controller, session, repo)
    }

    // ── 用例 ──

    @Test
    fun `导出成功后记下备份时间`() {
        val (c, s, _) = rig()
        assertEquals(0L, s.data!!.meta.lastBackupAt)

        c.export(FakeSink())

        assertTrue(c.step is ExportController.Step.Done)
        assertEquals(now, s.data!!.meta.lastBackupAt)
    }

    @Test
    fun `导出的字节就是一份能用主密码打开的完整保险库`() {
        val (c, _, _) = rig()
        val sink = FakeSink()
        c.export(sink)

        val bytes = sink.written!!
        "correct horse battery".toCharArray().toUtf8Secure().use { pw ->
            VaultFile.open(bytes, pw).use { opened ->
                // 备份里必须有刚加进去的那条，否则导出的是个旧版本
                assertEquals(1, opened.data.entries.size)
                assertEquals("某网站", opened.data.entries[0].name)
            }
        }
    }

    @Test
    fun `写了一半会被发现，并且不算备份过`() {
        val (c, s, _) = rig()
        c.export(HalfWriteSink())

        assertTrue(c.step is ExportController.Step.Failed)
        // 最要紧的一条：坏备份绝不能把「已备份」的标记点亮，
        // 否则用户从此看不到提醒，抱着一份废文件以为自己安全了。
        assertEquals(0L, s.data!!.meta.lastBackupAt)
    }

    @Test
    fun `写完读不回来也算失败`() {
        val (c, s, _) = rig()
        c.export(WriteOnlySink())

        assertTrue(c.step is ExportController.Step.Failed)
        assertEquals(0L, s.data!!.meta.lastBackupAt)
    }

    @Test
    fun `写入抛异常时给出可读的提示，而不是崩溃`() {
        val (c, s, _) = rig()
        c.export(FailingSink())

        val failed = c.step as ExportController.Step.Failed
        assertTrue(failed.message.isNotBlank())
        assertEquals(0L, s.data!!.meta.lastBackupAt)
    }

    @Test
    fun `成功状态里带着落点名字和真实字节数`() {
        val (c, _, _) = rig()
        val sink = FakeSink("U盘/vault-backup.lvault")
        c.export(sink)

        val done = c.step as ExportController.Step.Done
        assertEquals("U盘/vault-backup.lvault", done.where)
        assertEquals(sink.written!!.size, done.bytes)
    }

    @Test
    fun `失败之后可以重来，第二次成功照样记时间`() {
        val (c, s, _) = rig()
        c.export(FailingSink())
        assertTrue(c.step is ExportController.Step.Failed)

        c.reset()
        assertEquals(ExportController.Step.Idle, c.step)

        c.export(FakeSink())
        assertTrue(c.step is ExportController.Step.Done)
        assertNotEquals(0L, s.data!!.meta.lastBackupAt)
    }

    @Test
    fun `锁定状态下导出会被拦下，不会写出半份东西`() {
        val (c, s, _) = rig()
        s.lock()

        val sink = FakeSink()
        c.export(sink)

        assertTrue(c.step is ExportController.Step.Failed)
        // 自检发生在写盘之前，所以 sink 根本没被碰过
        assertEquals(null, sink.written)
    }
}
