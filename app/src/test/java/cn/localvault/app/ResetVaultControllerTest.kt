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
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.core.vault.VaultStorage
import cn.localvault.app.ui.settings.VaultRemnants
import cn.localvault.app.ui.unlock.ResetVaultController
import cn.localvault.app.ui.unlock.ResetVaultModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「忘了主密码，清空重来」的全流程单测。**跑的是真的库文件**
 * （临时目录 + 真的加解密），只把 KDF 换成廉价参数。
 *
 * 要钉的东西和删除页那一组有重叠，也有它自己独有的三条：
 *
 *  - **全程一次都没有打开过保险库。** 这一页的用户说不出主密码，
 *    连库文件本身坏掉的情况也走这一页——一个「先解开再清空」的实现
 *    恰恰在最需要它的时候用不了。所以下面有一条用例直接把库文件写成垃圾字节。
 *  - **起点是 `Locked` 相位，终点是 `NoVault`。** 不能是 `Locked`，
 *    否则用户看到的是一张要他为一个已经不存在的库输入主密码的解锁页——
 *    正是他刚刚花三秒钟摆脱的那一页。
 *  - 顺序照删除页：**先清快捷解锁的残留，后删库文件**（决策(120)）。
 *
 * 能在纯 JVM 上跑，靠的是控制器留的两个注入点：`worker = Dispatchers.Unconfined`
 * 让协程同步执行，`remnants` 是个接口（Keystore 和 prefs 在 JVM 上跑不起来）。
 */
class ResetVaultControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 廉价参数。这里测的是流程和文件，不是 KDF 强度——后者在 VaultFileTest 里。 */
    private val fast = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 1000, 1)

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private val PW = "correct horse battery staple"

    /**
     * 假的残留清理器。除了记有没有被调过，还记**被调的那一刻库文件还在不在**——
     * 顺序这件事只能这么钉：光看调用次序说明不了问题，
     * 要看清残留发生的时候，那个文件是不是还好好躺在盘上。
     */
    private class FakeRemnants(
        private val mainFile: File,
        var throwOnPurge: Boolean = false,
        var throwOnClipboard: Boolean = false,
    ) : VaultRemnants {
        var purgeCalls = 0
        var clipboardCalls = 0
        var vaultStillThereWhenPurged: Boolean? = null

        override fun purgeQuickUnlock() {
            purgeCalls++
            vaultStillThereWhenPurged = mainFile.exists()
            if (throwOnPurge) throw IllegalStateException("Keystore 抽风")
        }

        override fun clearClipboard() {
            clipboardCalls++
            if (throwOnClipboard) throw IllegalStateException("剪贴板不给清")
        }
    }

    private class Rig(
        val controller: ResetVaultController,
        val session: VaultSession,
        val repo: VaultRepository,
        val remnants: FakeRemnants,
        val storage: VaultStorage,
    )

    /**
     * 建一个装了两条数据的库，然后**锁上**——这一页的用户就是站在锁着的门外的人。
     * 控制器挂在这个锁着的会话上。
     */
    private fun rig(
        throwOnPurge: Boolean = false,
        throwOnClipboard: Boolean = false,
        corruptTheFile: Boolean = false,
    ): Rig {
        val storage = VaultStorage(tmp.root)
        val repo = VaultRepository(storage)
        val session = VaultSession(repo, scope)
        // 不能 `use {}`：`onVaultCreated` 会接管这把库主密钥的生命周期。
        session.onVaultCreated(repo.create(PW.toCharArray(), fast))
        session.addEntry(VaultEntry(id = "", name = "微信", password = "Aa1!aaaa"))
        session.addEntry(VaultEntry(id = "", name = "招商银行", password = "Bb2@bbbb"))
        session.lock()

        if (corruptTheFile) {
            // 库文件坏掉、任何口令都打不开的人，走的也是这一页。
            storage.mainFile.writeBytes(ByteArray(64) { 0x5A })
        }

        val remnants = FakeRemnants(storage.mainFile, throwOnPurge, throwOnClipboard)
        return Rig(
            ResetVaultController(
                repo = repo,
                session = session,
                remnants = remnants,
                scope = scope,
                worker = Dispatchers.Unconfined,
            ),
            session, repo, remnants, storage,
        )
    }

    /* ═════════════ 正常路径 ═════════════ */

    @Test
    fun `清空之后库文件没了，上一版副本也没了`() {
        val r = rig()
        assertTrue(r.repo.exists())

        r.controller.submit()

        assertEquals(ResetVaultController.Step.Done, r.controller.step)
        assertFalse(r.repo.exists())
        assertFalse(r.storage.mainFile.exists())
        assertFalse(File(tmp.root, "${VaultStorage.NAME}.bak").exists())
    }

    @Test
    fun `相位从 Locked 翻到 NoVault，而不是停在 Locked`() {
        val r = rig()
        assertTrue(r.session.state.value is VaultSession.State.Locked)

        r.controller.submit()

        assertTrue(
            "停在 Locked 的话，用户会看到一张要他为一个不存在的库输密码的解锁页",
            r.session.state.value is VaultSession.State.NoVault,
        )
    }

    @Test
    fun `上一次锁定的原因归零`() {
        val r = rig()
        assertEquals(VaultSession.LockReason.Manual, r.session.lastLockReason)

        r.controller.submit()

        // 下一次锁定发生时，解锁页不该看到一条来自上一个库的「上次是被自动锁定的」。
        assertEquals(VaultSession.LockReason.None, r.session.lastLockReason)
    }

    @Test
    fun `全程没有解开过库：文件坏成一团垃圾也照样清得掉`() {
        // 这是这一页存在的另一半理由。一个「先解开再清空」的实现，
        // 恰恰在用户最需要它的时候（库打不开了）用不了。
        val r = rig(corruptTheFile = true)

        r.controller.submit()

        assertEquals(ResetVaultController.Step.Done, r.controller.step)
        assertFalse(r.repo.exists())
    }

    /* ═════════════ 顺序 ═════════════ */

    @Test
    fun `清残留发生在删文件之前`() {
        val r = rig()

        r.controller.submit()

        assertEquals(1, r.remnants.purgeCalls)
        assertEquals(
            "反过来的中途失败不可收拾：文件没了，Keystore 里还留着两把包着它的钥匙",
            true,
            r.remnants.vaultStillThereWhenPurged,
        )
    }

    @Test
    fun `剪贴板也清了一次`() {
        // 自动锁定不会让剪贴板计时器停下来（它挂在 Application scope 上，决策⑬），
        // 所以「库已经不存在了，而它里面的一个密码还躺在系统剪贴板里」是真会出现的。
        val r = rig()
        r.controller.submit()
        assertEquals(1, r.remnants.clipboardCalls)
    }

    @Test
    fun `清残留抛异常不影响库被清掉`() {
        // 为一次 Keystore 抽风保住一个用户已经打不开的库，
        // 只会让他再按一次三秒、再抽风一次。
        val r = rig(throwOnPurge = true)

        r.controller.submit()

        assertEquals(ResetVaultController.Step.Done, r.controller.step)
        assertFalse(r.repo.exists())
    }

    @Test
    fun `剪贴板清不掉同样不影响`() {
        val r = rig(throwOnClipboard = true)
        r.controller.submit()
        assertEquals(ResetVaultController.Step.Done, r.controller.step)
        assertFalse(r.repo.exists())
    }

    /* ═════════════ 边角 ═════════════ */

    @Test
    fun `库文件本来就不在了也算清干净，不报错`() {
        // 上一次删到一半、或者文件被外部弄没了。用户要的结果已经达成，
        // 让他对着一个其实已经空了的保险库再按一次三秒是没道理的。
        val r = rig()
        r.storage.deleteAll()

        r.controller.submit()

        assertEquals(ResetVaultController.Step.Done, r.controller.step)
        assertTrue(r.session.state.value is VaultSession.State.NoVault)
        // 残留照清：prefs 里那份包裹和 Keystore 里那两把钥匙才是真正的脏数据。
        assertEquals(1, r.remnants.purgeCalls)
    }

    @Test
    fun `已经清完之后再按一次会被拒绝`() {
        val r = rig()
        r.controller.submit()
        assertEquals(ResetVaultController.Step.Done, r.controller.step)

        r.controller.submit()

        assertEquals(ResetVaultController.Step.Done, r.controller.step)
        assertEquals(1, r.remnants.purgeCalls)
    }

    /* ═════════════ 删不掉的时候 ═════════════ */

    @Test
    fun `文件删不掉时报 FilesRemain，而且库还在`() {
        val r = rig()
        // 把目录设成只读，子文件就删不掉了。
        // root 用户不受这条限制，那种环境下跳过这个用例而不是给出假的通过。
        val readOnly = tmp.root.setWritable(false)
        Assume.assumeTrue("当前环境无法造出「删不掉」的情形", readOnly)
        // root 无视只读位。探一下：还写得进去就说明这个用例在这台机器上没有意义。
        val stillWritable = runCatching { File(tmp.root, "probe").createNewFile() }.getOrDefault(false)
        Assume.assumeFalse("当前用户能无视只读目录（多半是 root）", stillWritable)

        try {
            r.controller.submit()

            assertEquals(
                ResetVaultController.Step.Failed(ResetVaultModel.Failure.FilesRemain),
                r.controller.step,
            )
            assertTrue("报失败就得真的还在", r.repo.exists())
            // 相位没动：库还在，会话该继续是锁着的
            assertTrue(r.session.state.value is VaultSession.State.Locked)
        } finally {
            tmp.root.setWritable(true)
        }
    }

    @Test
    fun `失败之后可以再来一次`() {
        val r = rig()
        val readOnly = tmp.root.setWritable(false)
        Assume.assumeTrue("当前环境无法造出「删不掉」的情形", readOnly)
        val stillWritable = runCatching { File(tmp.root, "probe").createNewFile() }.getOrDefault(false)
        Assume.assumeFalse("当前用户能无视只读目录（多半是 root）", stillWritable)

        r.controller.submit()
        assertTrue(r.controller.step is ResetVaultController.Step.Failed)

        tmp.root.setWritable(true)
        r.controller.dismissError()
        assertEquals(ResetVaultController.Step.Idle, r.controller.step)

        r.controller.submit()
        assertEquals(ResetVaultController.Step.Done, r.controller.step)
        assertFalse(r.repo.exists())
    }

    @Test
    fun `Done 之后调 dismissError 不会把状态拨回 Idle`() {
        // 那会让按钮在整棵树被换掉之前的那一帧重新变成可按的。
        val r = rig()
        r.controller.submit()
        r.controller.dismissError()
        assertEquals(ResetVaultController.Step.Done, r.controller.step)
    }

    @Test
    fun `空跑一次 cancel 不影响后面的清空`() {
        // 页面离开时会调 cancel（同别的控制器），它在没有任务时必须是安全的。
        val r = rig()
        r.controller.cancel()
        r.controller.submit()
        assertEquals(ResetVaultController.Step.Done, r.controller.step)
    }
}
