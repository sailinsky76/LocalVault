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
import cn.localvault.app.core.vault.WrongPasswordException
import cn.localvault.app.ui.settings.ChangeMasterController
import cn.localvault.app.ui.settings.ChangeMasterModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 修改主密码的全流程单测。**跑的是真的库文件**（临时目录 + 真的加解密），
 * 只把 KDF 换成廉价参数——这一步要钉的东西恰恰全在文件层面：
 *
 *  - 旧口令**真的**开不了了、新口令**真的**能开；
 *  - **库主密钥一个字节都没变**。这条是「指纹和 PIN 不用重新设置」那句话的
 *    全部依据（决策①）。哪天有人图省事在改密码时顺手换掉库主密钥，
 *    界面上一切正常，用户要到下一次按指纹时才发现门开不了了——
 *    而那时他多半已经把旧主密码忘了；
 *  - 失败的时候文件**一个字节都没动**。这是所有失败文案里那句
 *    「原来的主密码依然有效」的依据。
 *
 * 能在纯 JVM 上跑，靠的是控制器留的两个注入点（同 `CreateVaultControllerTest`）：
 * `worker = Dispatchers.Unconfined` 让协程同步执行，`calibrator = { fast }`
 * 绕开 Argon2（JNI）和 PBKDF2 的几十万次迭代。
 */
class ChangeMasterControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 廉价参数。这里测的是流程和文件，不是 KDF 强度——后者在 VaultFileTest 里。 */
    private val fast = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 1000, 1)

    /** 校准后要落到的那一档，故意和 [fast] 不同，好验证「改密码顺带重新校准」。 */
    private val recalibrated = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 2000, 1)

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private val OLD = "old master passphrase"
    private val NEW = "brand new passphrase 42"

    private class Rig(
        val controller: ChangeMasterController,
        val session: VaultSession,
        val repo: VaultRepository,
    )

    /** 建一个装了两条数据的库，解锁，然后挂一个控制器上去。 */
    private fun rig(): Rig {
        val repo = VaultRepository(VaultStorage(tmp.root))
        val session = VaultSession(repo, scope)
        // 注意不能 `use {}`：`adopt` 会接管这把库主密钥的生命周期，
        // 在这儿关掉它等于把会话的钥匙当场擦成全零。
        session.onVaultCreated(repo.create(OLD.toCharArray(), fast))
        session.addEntry(VaultEntry(id = "", name = "微信", password = "Aa1!aaaa"))
        session.addEntry(VaultEntry(id = "", name = "招商银行", password = "Bb2@bbbb"))

        return Rig(
            ChangeMasterController(
                repo = repo,
                session = session,
                scope = scope,
                argon2Available = false,
                worker = Dispatchers.Unconfined,
                calibrator = { recalibrated },
            ),
            session,
            repo,
        )
    }

    /* ═════════════ 主路径 ═════════════ */

    @Test
    fun `改完之后新口令能开、旧口令开不了`() {
        val r = rig()
        r.controller.submit(OLD.toCharArray(), NEW.toCharArray())
        assertEquals(ChangeMasterController.Step.Done, r.controller.step)

        r.session.lock()
        r.repo.unlock(NEW.toCharArray()).use { assertEquals(2, it.data.entries.size) }

        try {
            r.repo.unlock(OLD.toCharArray()).close()
            throw AssertionError("旧口令居然还能开")
        } catch (e: WrongPasswordException) {
            // 正是要的
        }
    }

    @Test
    fun `库主密钥一个字节都没变 —— 指纹和 PIN 的包裹因此不受影响`() {
        val r = rig()
        // 改之前借出来的那把钥匙，就是快捷解锁包在 Keystore 里的那一把。
        val keyBefore = r.session.withVaultKey { it.copyOf() }

        r.controller.submit(OLD.toCharArray(), NEW.toCharArray())

        val keyAfter = r.session.withVaultKey { it.copyOf() }
        assertTrue("会话里的库主密钥被换掉了", keyBefore.contentEquals(keyAfter))

        // 更要紧的是：拿改密码**之前**那把钥匙，还能直接打开改完之后的文件。
        // 这就是「按指纹解锁」那条路走的动作（VaultFile.openWithKey）。
        r.session.lock()
        r.repo.unlockWithKey(keyBefore).use { assertEquals(2, it.data.entries.size) }
    }

    @Test
    fun `数据一条不丢，内容原样`() {
        val r = rig()
        r.controller.submit(OLD.toCharArray(), NEW.toCharArray())
        r.session.lock()

        r.repo.unlock(NEW.toCharArray()).use { opened ->
            assertEquals(2, opened.data.entries.size)
            assertEquals("微信", opened.data.entries[0].name)
            assertEquals("Bb2@bbbb", opened.data.entries[1].password)
        }
    }

    @Test
    fun `改完之后会话还是解锁的，不需要用户再登一次`() {
        val r = rig()
        r.controller.submit(OLD.toCharArray(), NEW.toCharArray())

        // 改主密码没有换库主密钥，所以内存里那份明文依然是对的。
        // 为这件事把用户踢回解锁页，是白白让他刚设好的新口令立刻再打一遍。
        assertTrue(r.session.isUnlocked)
        assertEquals(2, r.session.data?.entries?.size)
    }

    @Test
    fun `顶部封条跟着换成新档位 —— 会话里的文件头被更新了`() {
        val r = rig()
        assertEquals(fast, r.session.headerKdfParams)

        r.controller.submit(OLD.toCharArray(), NEW.toCharArray())

        // 不更新的话，封条会继续显示旧的档位，那是假话。
        assertEquals(recalibrated, r.session.headerKdfParams)
        assertEquals(recalibrated, r.controller.chosenParams)
        assertNotEquals(fast, r.session.headerKdfParams)
    }

    @Test
    fun `改完之后记下修改时间，于是设置页那一行知道备份口令已经过期`() {
        val r = rig()
        val backupAt = System.currentTimeMillis() - 86_400_000L
        r.session.updateMeta { it.copy(lastBackupAt = backupAt) }

        r.controller.submit(OLD.toCharArray(), NEW.toCharArray())

        val meta = r.session.data!!.meta
        assertTrue(meta.masterChangedAt > 0L)
        assertTrue(meta.masterChangedAt > meta.lastBackupAt)
        assertTrue(ChangeMasterModel.rowSummary(meta.masterChangedAt, meta.lastBackupAt).urgent)
    }

    /* ═════════════ 旧口令输错 ═════════════ */

    @Test
    fun `旧口令不对时不动文件，旧口令照样开得了门`() {
        val r = rig()
        r.controller.submit("wrong one".toCharArray(), NEW.toCharArray())

        assertEquals(
            ChangeMasterController.Step.Failed(ChangeMasterModel.Failure.WrongOld),
            r.controller.step,
        )

        r.session.lock()
        // 失败文案里那句「原来的主密码依然有效」必须是真的。
        r.repo.unlock(OLD.toCharArray()).use { assertEquals(2, it.data.entries.size) }
        // 而新口令绝不能因为「试过一次」就生效了半个。
        try {
            r.repo.unlock(NEW.toCharArray()).close()
            throw AssertionError("没改成，新口令却能开")
        } catch (e: WrongPasswordException) {
            // 正是要的
        }
    }

    @Test
    fun `报错之后点「知道了」回到可以重来的状态`() {
        val r = rig()
        r.controller.submit("wrong one".toCharArray(), NEW.toCharArray())
        r.controller.dismissError()
        assertEquals(ChangeMasterController.Step.Idle, r.controller.step)
        assertFalse(r.controller.busy)

        // 重来一次，这次输对
        r.controller.submit(OLD.toCharArray(), NEW.toCharArray())
        assertEquals(ChangeMasterController.Step.Done, r.controller.step)
    }

    /* ═════════════ 口令副本的生死 ═════════════ */

    @Test
    fun `成功之后两份副本都被清零`() {
        val r = rig()
        val old = OLD.toCharArray()
        val fresh = NEW.toCharArray()
        r.controller.submit(old, fresh)

        assertTrue("旧口令副本没擦", old.all { it == '\u0000' })
        assertTrue("新口令副本没擦", fresh.all { it == '\u0000' })
    }

    @Test
    fun `失败之后两份副本一样被清零`() {
        val r = rig()
        val old = "wrong one".toCharArray()
        val fresh = NEW.toCharArray()
        r.controller.submit(old, fresh)

        // 失败路径最容易漏擦：提前 return 一次，finally 之外的清零就被跳过了。
        assertTrue(old.all { it == '\u0000' })
        assertTrue(fresh.all { it == '\u0000' })
    }

    @Test
    fun `已经改完之后再提交一次会被拒绝，而且照样把副本擦掉`() {
        val r = rig()
        r.controller.submit(OLD.toCharArray(), NEW.toCharArray())

        val again = "third passphrase".toCharArray()
        val old = NEW.toCharArray()
        r.controller.submit(old, again)

        // 终态是 Done，不会被这一次调用带回中间态
        assertEquals(ChangeMasterController.Step.Done, r.controller.step)
        assertTrue(again.all { it == '\u0000' })
        assertTrue(old.all { it == '\u0000' })
    }

    /* ═════════════ 锁着的时候 ═════════════ */

    @Test
    fun `会话已经锁了的话，什么都不会被改动`() {
        val r = rig()
        r.session.lock()

        r.controller.submit(OLD.toCharArray(), NEW.toCharArray())

        // 借不到库主密钥 → 失败，而且必须是「文件没动」的那一类失败
        assertTrue(r.controller.step is ChangeMasterController.Step.Failed)
        r.repo.unlock(OLD.toCharArray()).use { assertEquals(2, it.data.entries.size) }
    }
}
