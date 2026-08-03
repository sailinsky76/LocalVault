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
import cn.localvault.app.ui.importer.CsvImport
import cn.localvault.app.ui.importer.CsvMapping
import cn.localvault.app.ui.importer.CsvText
import cn.localvault.app.ui.importer.ImportController
import cn.localvault.app.ui.restore.ImportSource
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
 * M5-2b-1：批量落盘入口（`VaultSession.importEntries`）+ 导入控制器内核。
 *
 * **跑的是真的库文件**：临时目录、真的加解密、真的落盘，只把 KDF 换成廉价参数。
 * 前四层（文本 / 解析 / 映射 / 行转条目）各自已经有自己的用例，
 * 这个文件只钉两件事：
 *
 *  - 一份 CSV 从字节走到磁盘上的库，中间每一步接得上；
 *  - **要么全进要么全不进**，以及所有「预览之后库变了」的缝。
 *
 * 能在纯 JVM 上跑，靠的是 `Dispatchers.Unconfined`（协程同步执行）和
 * [ImportSource] 是个接口。控制器用到 `mutableStateOf`，
 * 所以这个文件需要 compose-runtime 在测试类路径上，同 `RestoreControllerTest`。
 */
class ImportControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val fast = KdfParams(KdfParams.ID_PBKDF2_SHA512, 0, 1000, 1)
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private var now = 1_700_000_000_000L

    @After fun tearDown() = scope.cancel()

    private fun unlocked(clock: () -> Long = { now }): Pair<VaultSession, VaultRepository> {
        val repo = VaultRepository(VaultStorage(tmp.root))
        val s = VaultSession(repo, scope, clock = clock)
        s.onVaultCreated(repo.create("pw".toCharArray(), fast))
        return s to repo
    }

    private fun controller(s: VaultSession) =
        ImportController(s, scope, worker = Dispatchers.Unconfined)

    /** 一个假的文件来源。[bytes] 为 null 表示读的时候抛。 */
    private class Source(
        override val displayName: String,
        private val bytes: ByteArray?,
    ) : ImportSource {
        constructor(name: String, text: String) : this(name, text.toByteArray(Charsets.UTF_8))

        override fun read(): ByteArray = bytes ?: throw IOException("读不了")
    }

    /** Chrome 导出的样子。三行数据。 */
    private val CHROME = """
        name,url,username,password
        微信,https://weixin.qq.com,user1,pw-one
        淘宝,https://taobao.com,buyer,"pw,two"
        京东,https://jd.com,jd-user,pw-three
    """.trimIndent()

    private fun entry(
        id: String, name: String, user: String = "", pw: String = "",
        category: String = "", domains: List<String> = emptyList(),
    ) = VaultEntry(
        id = id, name = name, username = user, password = pw,
        category = category, domains = domains,
        createdAt = 1_600_000_000_000L, updatedAt = 1_600_000_000_000L,
        passwordUpdatedAt = 1_600_000_000_000L,
    )

    /* ══════════════════ A. 会话批量入口 ══════════════════ */

    @Test
    fun `空清单不落盘也不算失败`() {
        val (s, _) = unlocked()
        val before = s.data!!
        val r = s.importEntries(emptyList(), emptyList())
        assertTrue(r.isSuccess)
        assertTrue("同一个对象，说明连 mutate 都没走", before === r.getOrNull())
        assertEquals(0, s.data!!.entries.size)
    }

    @Test
    fun `未解锁时批量导入失败`() {
        val (s, _) = unlocked()
        s.lock()
        assertTrue(s.importEntries(listOf(entry("", "微信")), emptyList()).isFailure)
    }

    @Test
    fun `新增会补 id 和三个时间戳`() {
        val (s, _) = unlocked()
        s.importEntries(listOf(VaultEntry(id = "", name = "微信", password = "pw")), emptyList())
        val e = s.data!!.entries.single()
        assertTrue("id 要补上", e.id.isNotEmpty())
        assertEquals(now, e.createdAt)
        assertEquals(now, e.updatedAt)
        assertEquals(now, e.passwordUpdatedAt)
    }

    @Test
    fun `没有密码的条目 passwordUpdatedAt 是 0`() {
        val (s, _) = unlocked()
        s.importEntries(listOf(VaultEntry(id = "", name = "通讯录里的人")), emptyList())
        assertEquals(0L, s.data!!.entries.single().passwordUpdatedAt)
    }

    @Test
    fun `一次导入的所有条目共用同一个时间戳`() {
        // 时钟每读一次就往前走，于是「读了几次时钟」是可观测的。
        val (s, _) = unlocked(clock = { now += 1_000; now })
        s.importEntries(
            listOf(
                VaultEntry(id = "", name = "a", password = "x"),
                VaultEntry(id = "", name = "b", password = "y"),
                VaultEntry(id = "", name = "c", password = "z"),
            ),
            emptyList(),
        )
        val stamps = s.data!!.entries.map { it.updatedAt }.toSet()
        assertEquals("三条共用一个时间戳 = 只走了一次 mutate", 1, stamps.size)
    }

    @Test
    fun `一条一条加则时间戳各不相同（对照组）`() {
        val (s, _) = unlocked(clock = { now += 1_000; now })
        s.addEntry(VaultEntry(id = "", name = "a", password = "x"))
        s.addEntry(VaultEntry(id = "", name = "b", password = "y"))
        assertEquals(2, s.data!!.entries.map { it.updatedAt }.toSet().size)
    }

    @Test
    fun `批量导入真的落盘了`() {
        val (s, r) = unlocked()
        s.importEntries(listOf(VaultEntry(id = "", name = "微信", password = "pw")), emptyList())
        s.lock()
        r.unlock("pw".toCharArray()).use {
            assertEquals(1, it.data.entries.size)
            assertEquals("微信", it.data.entries[0].name)
        }
    }

    @Test
    fun `覆盖是就地替换，位置不动`() {
        val (s, _) = unlocked()
        s.importEntries(
            listOf(entry("a", "甲"), entry("b", "乙"), entry("c", "丙")), emptyList(),
        )
        s.importEntries(emptyList(), listOf(entry("b", "乙改")))
        assertEquals(listOf("甲", "乙改", "丙"), s.data!!.entries.map { it.name })
    }

    @Test
    fun `覆盖保留旧的 createdAt`() {
        val (s, _) = unlocked()
        s.importEntries(listOf(entry("a", "甲")), emptyList())
        val created = s.data!!.entries[0].createdAt
        now += 86_400_000L
        s.importEntries(emptyList(), listOf(entry("a", "甲", pw = "新密码")))
        assertEquals("创建时间是这条条目的身份，不能被导入改掉",
            created, s.data!!.entries[0].createdAt)
    }

    @Test
    fun `覆盖时密码变了才刷新 passwordUpdatedAt`() {
        val (s, _) = unlocked()
        s.importEntries(listOf(entry("a", "甲", pw = "old")), emptyList())
        val stamp = s.data!!.entries[0].passwordUpdatedAt

        now += 86_400_000L
        s.importEntries(emptyList(), listOf(entry("a", "甲", pw = "old", category = "购物")))
        assertEquals("密码没变就不刷新，否则「该换密码了」会被一次重复导入整体清零",
            stamp, s.data!!.entries[0].passwordUpdatedAt)

        now += 86_400_000L
        s.importEntries(emptyList(), listOf(entry("a", "甲", pw = "new")))
        assertEquals(now, s.data!!.entries[0].passwordUpdatedAt)
    }

    @Test
    fun `覆盖对象在这中间被删了，就当成新增且保留原 id`() {
        val (s, _) = unlocked()
        s.importEntries(listOf(entry("a", "甲")), emptyList())
        s.deleteEntry("a")
        s.importEntries(emptyList(), listOf(entry("a", "甲")))
        val e = s.data!!.entries.single()
        assertEquals("甲", e.name)
        assertEquals("a", e.id)
    }

    @Test
    fun `新增追加在末尾且保持给进来的顺序`() {
        val (s, _) = unlocked()
        s.importEntries(listOf(entry("a", "甲")), emptyList())
        s.importEntries(
            listOf(VaultEntry(id = "", name = "乙"), VaultEntry(id = "", name = "丙")),
            listOf(entry("a", "甲改")),
        )
        assertEquals(listOf("甲改", "乙", "丙"), s.data!!.entries.map { it.name })
    }

    @Test
    fun `五百条一次导入`() {
        val (s, r) = unlocked()
        val many = (1..500).map { VaultEntry(id = "", name = "站点$it", password = "pw$it") }
        assertTrue(s.importEntries(many, emptyList()).isSuccess)
        s.lock()
        r.unlock("pw".toCharArray()).use {
            assertEquals(500, it.data.entries.size)
            assertEquals(500, it.data.entries.map { e -> e.id }.toSet().size)
        }
    }

    /* ══════════════════ B. 控制器：选文件 ══════════════════ */

    @Test
    fun `一开始什么都没有`() {
        val (s, _) = unlocked()
        val c = controller(s)
        assertEquals(ImportController.Step.Idle, c.step)
        assertNull(c.fileName)
        assertFalse(c.canCommit)
    }

    @Test
    fun `选中一份 Chrome 导出，进入预览`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("passwords.csv", CHROME))

        assertEquals(ImportController.Step.Preview, c.step)
        assertEquals("passwords.csv", c.fileName)
        assertEquals(4, c.header.size)
        assertEquals(3, c.rowCount)
        assertTrue(c.blockers.isEmpty())
        assertTrue(c.canCommit)
        assertEquals(3, c.outcome.add.size)
    }

    @Test
    fun `二进制文件被挡住，说的是解码层那句话`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("photo.jpg", byteArrayOf(0, 1, 2, 3, 0, 7, 9)))

        val f = c.step as ImportController.Step.Failed
        assertEquals(ImportController.Fail.PickAnother, f.kind)
        assertEquals(CsvText.message(CsvText.Decoded.NotText), f.text)
    }

    @Test
    fun `空文件被挡住`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("empty.csv", ByteArray(0)))
        assertEquals(ImportController.Fail.PickAnother,
            (c.step as ImportController.Step.Failed).kind)
    }

    @Test
    fun `只有表头没有数据行，也被挡住`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("head.csv", "name,url,username,password"))
        assertEquals(ImportController.Fail.PickAnother,
            (c.step as ImportController.Step.Failed).kind)
    }

    @Test
    fun `读文件抛异常时说的是读不下来，而不是格式不对`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("gone.csv", null))

        val f = c.step as ImportController.Step.Failed
        assertEquals(ImportController.Fail.PickAnother, f.kind)
        assertTrue(f.text.contains("读不下来"))
    }

    /* ══════════════════ B. 控制器：改映射 ══════════════════ */

    @Test
    fun `没有密码列时拦着不让导`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", "name,url\n微信,https://weixin.qq.com"))
        assertTrue(c.blockers.isNotEmpty())
        assertFalse(c.canCommit)
    }

    @Test
    fun `把账号那一列改成密码，候选跟着变`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))
        assertEquals("pw-one", c.outcome.add[0].password)

        c.setRole(2, CsvMapping.Role.Password)   // 第 2 列 username → 密码
        assertFalse(c.recomputing)
        assertEquals("user1", c.outcome.add[0].password)
        assertEquals("一个角色只占一列，原来的密码列要被自动清空",
            "", c.outcome.add[0].username)
    }

    @Test
    fun `全部清空之后导不了，恢复自动识别又能导`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))

        c.clearRoles()
        assertFalse(c.canCommit)
        assertEquals(0, c.outcome.total)

        c.resetRoles()
        assertTrue(c.canCommit)
        assertEquals(3, c.outcome.add.size)
    }

    /* ══════════════════ B. 控制器：处置 ══════════════════ */

    private fun withHit(): Pair<VaultSession, ImportController> {
        val (s, _) = unlocked()
        s.importEntries(
            listOf(entry("old", "微信", user = "user1", pw = "旧密码", category = "社交")),
            emptyList(),
        )
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))
        return s to c
    }

    @Test
    fun `撞上一条时，三种处置的结果各不相同`() {
        val (_, c) = withHit()
        assertEquals("默认是跳过", CsvImport.Policy.Skip, c.policy)
        assertEquals(2, c.outcome.add.size)
        assertEquals(0, c.outcome.replace.size)
        assertEquals(1, c.outcome.skippedByPolicy)

        c.setPolicy(CsvImport.Policy.Overwrite)
        assertEquals(2, c.outcome.add.size)
        assertEquals(1, c.outcome.replace.size)

        c.setPolicy(CsvImport.Policy.KeepBoth)
        assertEquals(3, c.outcome.add.size)
        assertEquals(0, c.outcome.replace.size)
    }

    @Test
    fun `全都撞上而处置是跳过时，导入按钮是灰的`() {
        val (s, _) = unlocked()
        s.importEntries(
            listOf(
                entry("1", "微信", user = "user1"),
                entry("2", "淘宝", user = "buyer"),
                entry("3", "京东", user = "jd-user"),
            ),
            emptyList(),
        )
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))
        assertEquals(0, c.outcome.total)
        assertFalse("一次什么都不做的成功比灰按钮更让人困惑", c.canCommit)
    }

    /* ══════════════════ B. 控制器：落盘 ══════════════════ */

    @Test
    fun `导入成功，报告数字和库里的东西对得上`() {
        val (s, r) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))
        c.commit()

        val done = c.step as ImportController.Step.Done
        assertEquals(3, done.report.added)
        assertEquals(0, done.report.replaced)
        assertEquals(3, done.report.total)

        s.lock()
        r.unlock("pw".toCharArray()).use {
            assertEquals(3, it.data.entries.size)
            assertEquals(setOf("微信", "淘宝", "京东"), it.data.entries.map { e -> e.name }.toSet())
        }
    }

    @Test
    fun `密码里的逗号一路活到库里`() {
        val (s, r) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))
        c.commit()

        s.lock()
        r.unlock("pw".toCharArray()).use { opened ->
            assertEquals("pw,two", opened.data.entries.first { it.name == "淘宝" }.password)
        }
    }

    @Test
    fun `覆盖时空的不覆盖，端到端也成立`() {
        val (s, c) = withHit()
        c.setPolicy(CsvImport.Policy.Overwrite)
        c.commit()

        val e = s.data!!.entries.first { it.id == "old" }
        assertEquals("密码该被换掉", "pw-one", e.password)
        assertEquals("CSV 里没有分类那一列，不等于请清空分类", "社交", e.category)
    }

    @Test
    fun `导入完成那一屏一定带着删源文件那句话`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))
        c.commit()
        val done = c.step as ImportController.Step.Done
        assertEquals(CsvText.PLAINTEXT_NOTE, done.report.sourceFileReminder)
    }

    @Test
    fun `导入完成后明文表就丢掉了，再点一次导入不会重复写`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))
        c.commit()
        c.commit()
        assertEquals(3, s.data!!.entries.size)
    }

    @Test
    fun `落盘之前会以当下的库重算一遍`() {
        val (s, c) = withHit()
        c.setPolicy(CsvImport.Policy.Overwrite)
        assertEquals(1, c.outcome.replace.size)

        // 预览还摆在屏幕上，用户在别处把那条删了
        s.deleteEntry("old")
        c.commit()

        val done = c.step as ImportController.Step.Done
        assertEquals("那条没了，就该当成新增", 3, done.report.added)
        assertEquals(0, done.report.replaced)
        assertEquals(3, s.data!!.entries.size)
    }

    @Test
    fun `库在中途锁上时，一条都没进去`() {
        val (s, r) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))
        s.lock()
        c.commit()

        // canCommit 已经算不出来了（库锁了 outcome 是空的），所以直接验库
        r.unlock("pw".toCharArray()).use { assertEquals(0, it.data.entries.size) }
    }

    /* ══════════════════ B. 控制器：收尾 ══════════════════ */

    @Test
    fun `discard 把这一份文件的一切都清掉`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))
        c.discard()

        assertEquals(ImportController.Step.Idle, c.step)
        assertNull(c.fileName)
        assertNull(c.plan)
        assertTrue(c.candidates.isEmpty())
        assertEquals(0, c.rowCount)
        assertFalse(c.canCommit)
    }

    @Test
    fun `文件本身不行时，关掉提示等于回到还没选文件`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("photo.jpg", byteArrayOf(0, 1, 2, 3, 0, 7, 9)))
        c.dismissError()
        assertEquals(ImportController.Step.Idle, c.step)
        assertNull("留着一个文件名而没有内容，界面上是一张说不清的空页", c.fileName)
    }

    /* ══════════════════ 不吐内容 ══════════════════ */

    @Test
    fun `报告的 toString 不带任何条目内容`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))
        c.commit()
        val text = (c.step as ImportController.Step.Done).report.toString()
        assertFalse(text.contains("微信"))
        assertFalse(text.contains("pw-one"))
        assertFalse(text.contains("user1"))
    }

    @Test
    fun `映射方案的 toString 也不带表头`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", CHROME))
        assertFalse(c.plan!!.toString().contains("password"))
    }

    @Test
    fun `记账文案里没有任何一格的内容`() {
        val (s, _) = unlocked()
        val c = controller(s)
        c.pick(Source("x.csv", "name,url,username,password,extra\n微信,https://weixin.qq.com,u,p,多出来的一格,再多一格"))
        val all = c.notes.joinToString("\n")
        assertFalse(all.contains("多出来的一格"))
        assertTrue("参差行应该被记一笔", c.notes.isNotEmpty())
    }
}
