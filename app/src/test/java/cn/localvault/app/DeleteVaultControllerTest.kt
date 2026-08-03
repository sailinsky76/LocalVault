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
import cn.localvault.app.ui.settings.DeleteVaultController
import cn.localvault.app.ui.settings.DeleteVaultModel
import cn.localvault.app.ui.settings.VaultRemnants
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
 * 删除保险库的全流程单测。**跑的是真的库文件**（临时目录 + 真的加解密），
 * 只把 KDF 换成廉价参数。
 *
 * 这一步要钉的东西全在**顺序**上：
 *
 *  - 先验口令，口令不对时文件一个字节都没动、快捷解锁也一点没碰；
 *  - **先清快捷解锁的残留，后删库文件**。反过来的中途失败是不可收拾的
 *    （文件没了，Keystore 里还留着两把包着它的钥匙）；这个顺序的中途失败
 *    是可收拾的（库还在，重开一次快捷解锁就完事）；
 *  - 删完之后会话相位翻回 `NoVault`，而且库主密钥被擦掉了。
 *
 * 能在纯 JVM 上跑，靠的是控制器留的两个注入点：`worker = Dispatchers.Unconfined`
 * 让协程同步执行，`remnants` 是个接口（Keystore 和 prefs 在 JVM 上跑不起来）。
 */
class DeleteVaultControllerTest {

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
        val controller: DeleteVaultController,
        val session: VaultSession,
        val repo: VaultRepository,
        val remnants: FakeRemnants,
        val storage: VaultStorage,
    )

    /** 建一个装了两条数据的库，解锁，然后挂一个控制器上去。 */
    private fun rig(
        throwOnPurge: Boolean = false,
        throwOnClipboard: Boolean = false,
    ): Rig {
        val storage = VaultStorage(tmp.root)
        val repo = VaultRepository(storage)
        val session = VaultSession(repo, scope)
        // 不能 `use {}`：`adopt` 会接管这把库主密钥的生命周期。
        session.onVaultCreated(repo.create(PW.toCharArray(), fast))
        session.addEntry(VaultEntry(id = "", name = "微信", password = "Aa1!aaaa"))
        session.addEntry(VaultEntry(id = "", name = "招商银行", password = "Bb2@bbbb"))

        val remnants = FakeRemnants(storage.mainFile, throwOnPurge, throwOnClipboard)
        return Rig(
            DeleteVaultController(
                repo = repo,
                session = session,
                remnants = remnants,
                scope = scope,
                worker = Dispatchers.Unconfined,
            ),
            session, repo, remnants, storage,
        )
    }

    /* ═════════════ 主路径 ═════════════ */

    @Test
    fun `删完之后库文件真的没了`() {
        val r = rig()
        assertTrue(r.repo.exists())
        r.controller.submit(PW.toCharArray())

        assertEquals(DeleteVaultController.Step.Done, r.controller.step)
        assertFalse(r.repo.exists())
        assertFalse(r.storage.mainFile.exists())
    }

    @Test
    fun `连上一版备份副本一起删掉`() {
        val r = rig()
        // 上面存了两条，所以 .bak 一定已经存在了（原子写入的第二条命）
        val bak = File(tmp.root, "${VaultStorage.NAME}.bak")
        assertTrue("前提没成立：这一步之前应该已经有 .bak 了", bak.exists())

        r.controller.submit(PW.toCharArray())
        assertFalse("上一版备份没删掉，等于库还留着一份能打开的副本", bak.exists())
    }

    @Test
    fun `删完之后相位翻回 NoVault，而不是 Locked`() {
        // 翻到 Locked 的话，用户看到的是一张解锁页——
        // 一个要他为一个已经不存在的库输入主密码的页面。
        val r = rig()
        r.controller.submit(PW.toCharArray())
        assertEquals(VaultSession.State.NoVault, r.session.state.value)
    }

    @Test
    fun `删完之后库主密钥已经被擦掉`() {
        val r = rig()
        r.controller.submit(PW.toCharArray())
        var borrowed = true
        try {
            r.session.withVaultKey { }
        } catch (e: IllegalStateException) {
            borrowed = false
        }
        assertFalse("删完之后还借得出库主密钥", borrowed)
    }

    @Test
    fun `删完之后自动锁定的原因归零`() {
        // 不归零的话，用户新建一个库、第一次自动锁定之后，
        // 解锁页顶上会挂着一条来自上一个库的提示。
        val r = rig()
        r.session.lock()
        r.session.unlock(PW.toCharArray())
        r.controller.submit(PW.toCharArray())
        assertEquals(VaultSession.LockReason.None, r.session.lastLockReason)
    }

    /* ═════════════ 顺序 ═════════════ */

    @Test
    fun `清残留发生在删文件之前`() {
        // 这一条是整个文件里最要紧的一句。反过来的顺序中途失败时，
        // prefs 里会留下一份包着已不存在的库的主密钥的包裹，
        // 而它会原封不动地作用在用户下一次新建的那个库上。
        val r = rig()
        r.controller.submit(PW.toCharArray())
        assertEquals(1, r.remnants.purgeCalls)
        assertEquals(true, r.remnants.vaultStillThereWhenPurged)
    }

    @Test
    fun `剪贴板也会被清一次`() {
        // 倒计时挂在 Application scope 上（决策⑬），库删掉不会让它停下来。
        // 不清的话，「保险库已经不存在了，而它里面的一个密码还躺在剪贴板里」
        // 这种状态是真会出现的。
        val r = rig()
        r.controller.submit(PW.toCharArray())
        assertEquals(1, r.remnants.clipboardCalls)
    }

    @Test
    fun `清残留抛异常不影响库被删掉`() {
        // 用户要的是「把库删掉」。为一次 Keystore 抽风而保住一个他已经决定不要的库，
        // 只会让他再点一次、再抽风一次。清不掉的那部分是脏数据，不是安全问题——
        // 那两把钥匙包的是一个马上就不存在的库。
        val r = rig(throwOnPurge = true)
        r.controller.submit(PW.toCharArray())
        assertEquals(DeleteVaultController.Step.Done, r.controller.step)
        assertFalse(r.repo.exists())
    }

    @Test
    fun `剪贴板清不掉同样不影响`() {
        val r = rig(throwOnClipboard = true)
        r.controller.submit(PW.toCharArray())
        assertEquals(DeleteVaultController.Step.Done, r.controller.step)
        assertFalse(r.repo.exists())
    }

    /* ═════════════ 口令不对 ═════════════ */

    @Test
    fun `口令不对时文件一个字节都没动`() {
        val r = rig()
        val before = r.storage.mainFile.readBytes()
        r.controller.submit("wrong password".toCharArray())

        assertEquals(
            DeleteVaultController.Step.Failed(DeleteVaultModel.Failure.WrongPassword),
            r.controller.step,
        )
        assertTrue(r.repo.exists())
        assertTrue(before.contentEquals(r.storage.mainFile.readBytes()))
    }

    @Test
    fun `口令不对时快捷解锁一点没碰`() {
        // 这就是 Failure.WrongPassword 那句「也没有任何东西被改动」的依据。
        val r = rig()
        r.controller.submit("wrong password".toCharArray())
        assertEquals(0, r.remnants.purgeCalls)
        assertEquals(0, r.remnants.clipboardCalls)
    }

    @Test
    fun `口令不对时会话还是解锁的`() {
        // 输错一次就被踢回解锁页，等于把一次误操作变成一次重新输长口令。
        val r = rig()
        r.controller.submit("wrong password".toCharArray())
        assertTrue(r.session.isUnlocked)
    }

    @Test
    fun `口令不对之后还能重来`() {
        val r = rig()
        r.controller.submit("wrong password".toCharArray())
        r.controller.dismissError()
        assertEquals(DeleteVaultController.Step.Idle, r.controller.step)

        r.controller.submit(PW.toCharArray())
        assertEquals(DeleteVaultController.Step.Done, r.controller.step)
    }

    /* ═════════════ 口令副本的清零 ═════════════ */

    @Test
    fun `成功路上口令副本被清零`() {
        val r = rig()
        val copy = PW.toCharArray()
        r.controller.submit(copy)
        assertTrue(copy.all { it == '\u0000' })
    }

    @Test
    fun `失败路上口令副本也被清零`() {
        val r = rig()
        val copy = "wrong password".toCharArray()
        r.controller.submit(copy)
        assertTrue(copy.all { it == '\u0000' })
    }

    @Test
    fun `已经删完之后再提交一次会被拒绝，而且照样清零`() {
        val r = rig()
        r.controller.submit(PW.toCharArray())
        assertEquals(DeleteVaultController.Step.Done, r.controller.step)

        val copy = PW.toCharArray()
        r.controller.submit(copy)
        // 状态没被改回去，残留也没被再清一遍
        assertEquals(DeleteVaultController.Step.Done, r.controller.step)
        assertEquals(1, r.remnants.purgeCalls)
        assertTrue(copy.all { it == '\u0000' })
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
            r.controller.submit(PW.toCharArray())
            assertEquals(
                DeleteVaultController.Step.Failed(DeleteVaultModel.Failure.FilesRemain),
                r.controller.step,
            )
            assertTrue("报了失败，库却真没了——那条文案就成了假话", r.repo.exists())
            // 而且残留确实已经清过了，这正是那条文案要交代的副作用
            assertEquals(1, r.remnants.purgeCalls)
        } finally {
            tmp.root.setWritable(true)
        }
    }
}
