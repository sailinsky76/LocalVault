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
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.core.vault.VaultStorage
import cn.localvault.app.ui.onboarding.CreateVaultController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 建库流程的单测。
 *
 * 能在纯 JVM 上跑，靠的是 [CreateVaultController] 留的两个注入点：
 *   · `worker = Dispatchers.Unconfined` + Unconfined 的 scope → 协程同步执行，
 *     `create()` 返回时全部动作已经做完，不需要 `runTest` 也不需要轮询等待；
 *   · `calibrator = { fast }` → 绕开 Argon2（JNI，JVM 里加载不了）
 *     和 PBKDF2 的 60 万次迭代（真跑一次要好几秒，测试会慢到没人愿意跑）。
 *
 * 这两个口子的存在本身就是被测的一部分：如果哪天有人把校准硬编码回去，
 * 这个文件会第一时间编译不过。
 */
class CreateVaultControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 廉价参数。这里测的是流程，不是 KDF 强度 —— 后者在 VaultFileTest 里。 */
    private val fast = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 1000, 1)
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun rig(): Triple<CreateVaultController, VaultSession, VaultRepository> {
        val repo = VaultRepository(VaultStorage(tmp.root))
        val session = VaultSession(repo, scope)
        val controller = CreateVaultController(
            repo = repo,
            session = session,
            scope = scope,
            argon2Available = false,
            worker = Dispatchers.Unconfined,
            calibrator = { fast },
        )
        return Triple(controller, session, repo)
    }

    /** 在同一个库上再开一个控制器，用来测「第二次建库」这类场景 */
    private fun controllerOn(session: VaultSession, repo: VaultRepository) =
        CreateVaultController(
            repo = repo, session = session, scope = scope, argon2Available = false,
            worker = Dispatchers.Unconfined, calibrator = { fast },
        )

    @Test
    fun `建库成功后会话进入已解锁`() {
        val (c, s, _) = rig()
        assertEquals(VaultSession.State.NoVault, s.state.value)

        c.create("correct horse battery".toCharArray())

        assertTrue(s.isUnlocked)
        assertNotNull(s.data)
        assertEquals(CreateVaultController.Step.Idle, c.step)
        assertFalse(c.busy)
    }

    @Test
    fun `建库用的就是传进来的那个口令`() {
        val (c, s, repo) = rig()
        c.create("correct horse battery".toCharArray())
        s.lock()

        // 同一个口令能开 —— 说明没有在传递过程中被截断或改写
        repo.unlock("correct horse battery".toCharArray()).use {
            assertEquals(0, it.data.entries.size)
        }
    }

    @Test
    fun `口令副本在建库结束后已被清零`() {
        val (c, _, _) = rig()
        val pw = "correct horse battery".toCharArray()
        c.create(pw)

        // 调用方交出去的那份数组必须原地变成全零，
        // 而不是「控制器自己复制了一份然后擦自己那份」。
        assertTrue(pw.all { it == '\u0000' })
    }

    @Test
    fun `建库失败时，口令副本一样被清零`() {
        val (c, s, repo) = rig()
        c.create("correct horse battery".toCharArray())
        s.lock()

        // 库已经在了，这一次注定失败
        val doomed = "another passphrase".toCharArray()
        controllerOn(s, repo).create(doomed)

        // 失败路径最容易漏擦：异常一抛，finally 之外的清零就被跳过了。
        assertTrue(doomed.all { it == '\u0000' })
    }

    @Test
    fun `已有库时再建库会失败，且不动已有数据`() {
        val (c, s, repo) = rig()
        c.create("correct horse battery".toCharArray())
        s.lock()

        val c2 = controllerOn(s, repo)
        c2.create("a totally different one".toCharArray())

        assertTrue(c2.step is CreateVaultController.Step.Failed)
        assertFalse(s.isUnlocked)
        // 原口令依然能开：失败的那次建库没有覆盖任何东西
        repo.unlock("correct horse battery".toCharArray()).use { assertNotNull(it.data) }
    }

    @Test
    fun `失败提示可以被用户关掉，回到可重试状态`() {
        val (c, s, repo) = rig()
        c.create("correct horse battery".toCharArray())
        s.lock()

        val c2 = controllerOn(s, repo)
        c2.create("x".repeat(12).toCharArray())
        assertTrue(c2.step is CreateVaultController.Step.Failed)

        c2.dismissError()
        assertEquals(CreateVaultController.Step.Idle, c2.step)
        assertFalse(c2.busy)
    }

    /**
     * 补的是那条夹缝：库已经写进磁盘了，但会话还停在「未建库」。
     * 真机上的成因是 `repo.create()` 之后、`session.onVaultCreated()` 之前进程被回收。
     * 这里直接构造出那个状态：绕开控制器先落一个库，再让控制器用同一个口令去建。
     */
    @Test
    fun `库已落盘但会话没接管时，同一个口令能直接接上`() {
        val repo = VaultRepository(VaultStorage(tmp.root))
        repo.create("correct horse battery".toCharArray(), fast).close()

        // 会话是在库已存在之后才构造的，所以它自己知道是 Locked；
        // 真实夹缝里它会是 NoVault，两种情况走的都是同一段恢复代码。
        val session = VaultSession(repo, scope)
        val c = controllerOn(session, repo)

        c.create("correct horse battery".toCharArray())

        assertEquals(CreateVaultController.Step.Idle, c.step)
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `校准结果会被记下来，供设置页显示实际档位`() {
        val (c, _, _) = rig()
        c.create("correct horse battery".toCharArray())
        assertEquals(fast, c.chosenParams)
    }
}
