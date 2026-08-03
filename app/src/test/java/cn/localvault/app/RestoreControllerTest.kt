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
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultData
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.core.vault.VaultFile
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.core.vault.VaultStorage
import cn.localvault.app.ui.restore.ImportSource
import cn.localvault.app.ui.restore.RestoreController
import cn.localvault.app.ui.restore.RestoreModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * 从备份恢复的全流程单测。**跑的是真的库文件**（临时目录 + 真的加解密），
 * 只把 KDF 换成廉价参数。
 *
 * 这一步要钉的东西：
 *
 *  - **装进去的就是那份文件本身**，一个字节不改（不重新封装、不换档位）；
 *  - 失败的每一条路上，这台设备上**都没有留下半个库**；
 *  - 已经有库的时候恢复必须拒绝，而且**现有的库一个字节都不能被碰**；
 *  - 恢复完会话直接是解锁的（不让人再输一遍刚输过的主密码），
 *    且顺手记一笔 `lastBackupAt` —— 否则一个刚装完机的人会立刻被
 *    首次备份那道关卡挡住，要求他再导一份他刚用过的文件；
 *  - 口令副本在成功和失败两条路上都被清零。
 *
 * 能在纯 JVM 上跑，靠的是 `worker = Dispatchers.Unconfined`（协程同步执行）
 * 和 `ImportSource` 是个接口（SAF 在 JVM 上跑不起来）。
 * 控制器用到 `mutableStateOf`，所以这个文件需要 compose-runtime 在测试类路径上，
 * 同 `DeleteVaultControllerTest`。
 */
class RestoreControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val fast = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 1000, 1)
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private val PW = "correct horse battery staple"

    /** 一份「别的设备上导出来的」备份文件：两条数据，廉价 KDF。 */
    private fun backupBytes(password: String = PW): ByteArray {
        val data = VaultData(
            entries = listOf(
                VaultEntry(id = "a", name = "招商银行", username = "13800000000", password = "p1"),
                VaultEntry(id = "b", name = "微信", username = "wx_id", password = "p2"),
            )
        )
        return VaultFile.create(password.toByteArray(), data, fast)
    }

    private class FakeSource(
        override val displayName: String,
        private val bytes: ByteArray?,
    ) : ImportSource {
        override fun read(): ByteArray = bytes ?: throw IOException("U 盘拔了")
    }

    private class Rig(
        val controller: RestoreController,
        val session: VaultSession,
        val repo: VaultRepository,
        val storage: VaultStorage,
    )

    private fun rig(): Rig {
        val storage = VaultStorage(tmp.newFolder())
        val repo = VaultRepository(storage)
        val session = VaultSession(repo, scope)
        return Rig(
            RestoreController(repo, session, scope, worker = Dispatchers.Unconfined),
            session, repo, storage,
        )
    }

    private fun pw() = PW.toCharArray()

    // ───────────────────── 选文件 ─────────────────────

    @Test fun `选中一份真备份能认出来`() {
        val r = rig()
        r.controller.pick(FakeSource("localvault-20260729.lvault", backupBytes()))
        val p = r.controller.probe
        assertTrue(p is RestoreModel.Probe.Recognized)
        assertEquals("localvault-20260729.lvault", (p as RestoreModel.Probe.Recognized).fileName)
        assertEquals(RestoreController.Step.Idle, r.controller.step)
    }

    @Test fun `文件读不下来时报的是读取失败而且没有留下半个文件名`() {
        val r = rig()
        r.controller.pick(FakeSource("u盘上的备份.lvault", null))
        assertEquals(RestoreController.Step.Failed(RestoreModel.Failure.Io), r.controller.step)
        assertNull(r.controller.probe)
    }

    @Test fun `换文件会把上一份从内存里丢掉`() {
        val r = rig()
        r.controller.pick(FakeSource("a.lvault", backupBytes()))
        r.controller.clearFile()
        assertNull(r.controller.probe)
        // 没有文件时提交是空转：不报错、也不装任何东西
        r.controller.submit(pw())
        assertFalse(r.repo.exists())
    }

    // ───────────────────── 装上 ─────────────────────

    @Test fun `恢复之后数据一条不少而且会话直接是解锁的`() {
        val r = rig()
        r.controller.pick(FakeSource("a.lvault", backupBytes()))
        r.controller.submit(pw())

        val state = r.session.state.value
        assertTrue(state is VaultSession.State.Unlocked)
        val data = (state as VaultSession.State.Unlocked).data
        assertEquals(2, data.entries.size)
        assertEquals("招商银行", data.entries[0].name)
        assertEquals("p2", data.entries[1].password)
    }

    @Test fun `装进磁盘的就是那份文件本身一个字节不改`() {
        // 直接测仓库层：控制器成功之后会紧接着记一笔 lastBackupAt（那会重写文件），
        // 所以「逐字节相同」要在装上的那一刻验，而不是在流程走完之后验。
        val storage = VaultStorage(tmp.newFolder())
        val repo = VaultRepository(storage)
        val bytes = backupBytes()
        repo.restoreAndOpen(bytes, pw()).close()
        assertTrue(bytes.contentEquals(storage.load()!!))
    }

    @Test fun `不会拿本机的默认档位重新封装`() {
        val r = rig()
        r.controller.pick(FakeSource("a.lvault", backupBytes()))
        r.controller.submit(pw())
        // 封条上显示的必须是这份文件当年那台设备定下的档位
        assertEquals(fast.id, r.session.headerKdfParams!!.id)
        assertEquals(fast.iterations, r.session.headerKdfParams!!.iterations)
    }

    @Test fun `恢复完能用同一个主密码重新解锁`() {
        val r = rig()
        r.controller.pick(FakeSource("a.lvault", backupBytes()))
        r.controller.submit(pw())
        r.session.lock()
        r.session.unlock(pw())
        assertTrue(r.session.state.value is VaultSession.State.Unlocked)
    }

    @Test fun `恢复完记了一笔备份不会立刻又被要求导出一份`() {
        val r = rig()
        r.controller.pick(FakeSource("a.lvault", backupBytes()))
        r.controller.submit(pw())
        val meta = (r.session.state.value as VaultSession.State.Unlocked).data.meta
        assertTrue("lastBackupAt 还是 0，首次备份那道关卡会挡在前面", meta.lastBackupAt > 0L)
    }

    @Test fun `成功之后那份文件从内存里清掉了`() {
        val r = rig()
        r.controller.pick(FakeSource("a.lvault", backupBytes()))
        r.controller.submit(pw())
        assertNull(r.controller.probe)
    }

    @Test fun `口令验过之后的那个回调抛异常不影响恢复`() {
        val storage = VaultStorage(tmp.newFolder())
        val repo = VaultRepository(storage)
        val bytes = backupBytes()
        repo.restoreAndOpen(bytes, pw()) { throw IllegalStateException("界面炸了") }.close()
        assertTrue(storage.exists())
    }

    // ───────────────────── 装不上 ─────────────────────

    @Test fun `口令不对时这台设备上什么都没留下`() {
        val r = rig()
        r.controller.pick(FakeSource("a.lvault", backupBytes()))
        r.controller.submit("wrong password".toCharArray())

        assertEquals(
            RestoreController.Step.Failed(RestoreModel.Failure.WrongPassword),
            r.controller.step,
        )
        assertFalse(r.repo.exists())
        assertTrue(r.session.state.value is VaultSession.State.NoVault)
    }

    @Test fun `口令错了之后可以直接再来一次`() {
        val r = rig()
        r.controller.pick(FakeSource("a.lvault", backupBytes()))
        r.controller.submit("nope".toCharArray())
        r.controller.dismissError()
        r.controller.submit(pw())     // 那份文件还在内存里，不用重新选
        assertTrue(r.session.state.value is VaultSession.State.Unlocked)
    }

    @Test fun `选错文件时报的是选错了`() {
        val r = rig()
        r.controller.pick(FakeSource("IMG_0421.JPG", ByteArray(5000) { 0x41 }))
        r.controller.submit(pw())
        assertEquals(
            RestoreController.Step.Failed(RestoreModel.Failure.NotVaultFile),
            r.controller.step,
        )
        assertFalse(r.repo.exists())
    }

    @Test fun `更新版本的备份报的是版本太新`() {
        val r = rig()
        val bytes = backupBytes().also { it[6] = 0; it[7] = 9 }
        r.controller.pick(FakeSource("future.lvault", bytes))
        r.controller.submit(pw())
        assertEquals(RestoreController.Step.Failed(RestoreModel.Failure.TooNew), r.controller.step)
    }

    @Test fun `密文被改坏的备份报的是坏了不是口令不对`() {
        val r = rig()
        val bytes = backupBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()   // 动最后一个字节
        r.controller.pick(FakeSource("a.lvault", bytes))
        r.controller.submit(pw())
        val step = r.controller.step
        assertTrue(step is RestoreController.Step.Failed)
        assertEquals(RestoreModel.Failure.Corrupted, (step as RestoreController.Step.Failed).kind)
        assertFalse(r.repo.exists())
    }

    // ───────────────────── 已经有库 ─────────────────────

    @Test fun `已经有库时拒绝恢复而且现有的库一个字节没动`() {
        val storage = VaultStorage(tmp.newFolder())
        val repo = VaultRepository(storage)
        repo.create("原来的口令".toCharArray(), fast).close()
        val before = storage.load()!!

        val session = VaultSession(repo, scope)
        val controller = RestoreController(repo, session, scope, worker = Dispatchers.Unconfined)
        controller.pick(FakeSource("a.lvault", backupBytes()))
        controller.submit(pw())

        assertEquals(
            RestoreController.Step.Failed(RestoreModel.Failure.VaultExists),
            controller.step,
        )
        assertTrue(before.contentEquals(storage.load()!!))
        assertTrue(session.state.value is VaultSession.State.Locked)
    }

    // ───────────────────── 口令副本 ─────────────────────

    @Test fun `成功路上口令副本被清零`() {
        val r = rig()
        r.controller.pick(FakeSource("a.lvault", backupBytes()))
        val copy = pw()
        r.controller.submit(copy)
        assertTrue(copy.all { it == '\u0000' })
    }

    @Test fun `失败路上口令副本也被清零`() {
        val r = rig()
        r.controller.pick(FakeSource("a.lvault", backupBytes()))
        val copy = "wrong".toCharArray()
        r.controller.submit(copy)
        assertTrue(copy.all { it == '\u0000' })
    }

    @Test fun `没选文件时被拒绝的那一次也把口令清零`() {
        val r = rig()
        val copy = pw()
        r.controller.submit(copy)
        assertTrue(copy.all { it == '\u0000' })
    }
}
