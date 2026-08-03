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

package cn.localvault.app.ui.restore

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.localvault.app.core.crypto.UnsupportedKdfException
import cn.localvault.app.core.crypto.wipe
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultCorruptedException
import cn.localvault.app.core.vault.VaultFormatException
import cn.localvault.app.core.vault.VaultNotRecognizedException
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.core.vault.VaultTooNewException
import cn.localvault.app.core.vault.VaultWriteVerificationException
import cn.localvault.app.core.vault.WrongPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份文件的来源。把 SAF 的 `Uri` 和 `ContentResolver` 关在实现里，
 * 控制器本身完全不认识 Android——于是这段逻辑能在纯 JVM 上测。
 *
 * 和导出侧的 `ExportSink` 是一对，形状也刻意对称。
 * **只有 [read]，没有 write**：这一页从头到尾不会写用户那个文件，
 * 而失败文案里那句「你手上那份文件没有被改动」（[RestoreModel.UNTOUCHED_CLAUSE]）
 * 之所以敢在八条路上都写，靠的就是这个接口里根本没有写入方法——
 * 不是靠谁记得别写（同 `EntryForm` 那条「摘要函数收不到字段值」的做法）。
 */
interface ImportSource {
    /** 给用户看的名字。选错文件是这条流程上最常见的失误，所以它要一直显示着。 */
    val displayName: String

    /** 把整个文件读进内存。读不到就抛。 */
    fun read(): ByteArray
}

/**
 * 从备份恢复的执行者。
 *
 * ── 为什么恢复要单独一个控制器，而不是复用建库那一个 ──
 *
 * 建库是「从无到有造一个新库」，恢复是「把一份已经存在的库装到这台设备上」。
 * 两者只有终点相同（会话接管、相位翻到已解锁），中间一步都不一样：
 * 建库要校准 KDF、要生成新的库主密钥；恢复一样都不做——
 * **写进磁盘的就是用户那份文件本身，一个字节不改**（见 `VaultRepository.restoreAndOpen`）。
 * 合成一个类，那个「要不要校准」的分支会一直挂在最要紧的一段代码上。
 *
 * ── 顺序 ──
 *
 *   1. 读文件 → 认文件头（不需要主密码，[probe] 就是这一步的产物）
 *   2. 用主密码打开一次（**只派生一次**，库主密钥留着给第 4 步）
 *   3. 原样落盘 + 读回来逐字节比对（仓库层做，决策⑱ 在导入侧的镜像）
 *   4. 会话接管 → 相位从 `NoVault` 翻到 `Unlocked`
 *   5. 记一笔 `lastBackupAt`（见下）
 *
 * **第 3 步先于第 4 步**，中途被系统回收也不会把人卡住：磁盘上已经有一个完好的库，
 * 下次启动就是一张解锁页，用户拿刚才那个主密码开门即可。
 * （反过来先接管再落盘的话，那一瞬间被回收就等于什么都没发生，
 * 而用户以为恢复完了——同建库那条「已落盘但没接管」的夹缝，方向相反。）
 *
 * ── 第 5 步为什么算「备份过了」 ──
 *
 * 决策⑱ 说「已备份」这个标记只能由**验证过的事实**产生。这次的事实比导出那次更硬：
 * 导出验的是我们刚写出去的文件，而这次验的是一份在别处存放过一段时间、
 * 经历过拷贝和同步、刚刚被真实主密码打开的文件。恢复成功的那一刻，
 * 「用户手上那份备份」和「这台设备上的库」逐字节相同——那正是 `lastBackupAt` 想回答的问题。
 * 不记的话，一个刚拿备份装完机的人会立刻被首次备份那道关卡挡住（决策⑰），
 * 被要求再导一份他刚刚才用过的东西。
 * 记这一笔失败**不算恢复失败**（同决策(116) 的分界线：磁盘上的库已经装好了，
 * 判成失败的后果是用户以为没恢复上，然后再来一遍——而那一遍会撞上「已有保险库」）。
 */
class RestoreController(
    private val repo: VaultRepository,
    private val session: VaultSession,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {

    @Immutable
    sealed interface Step {
        data object Idle : Step
        data object Reading : Step
        data object Opening : Step
        data object Installing : Step
        data class Failed(val kind: RestoreModel.Failure) : Step
    }

    var step by mutableStateOf<Step>(Step.Idle)
        private set

    /** 当前选中的那个文件的判断结果。没选文件时为 null。 */
    var probe by mutableStateOf<RestoreModel.Probe?>(null)
        private set

    val busy: Boolean
        get() = step is Step.Reading || step is Step.Opening || step is Step.Installing

    /**
     * 选中文件的原始字节。
     *
     * 它只活在内存里，一次都不落盘、一次都不进路由（同决策㊳/(67)：任何进 Bundle 的
     * 东西都要当成会落盘的，而这是一整个加密库）。恢复完成或用户换文件时立刻清掉。
     */
    private var pending: ByteArray? = null

    private var job: Job? = null

    /** 用户在系统文件选择器里挑了一个文件。读进来、认一下，不需要主密码。 */
    fun pick(source: ImportSource) {
        if (busy) return
        job = scope.launch {
            try {
                step = Step.Reading
                val name = source.displayName
                val bytes = withContext(worker) { source.read() }
                pending = bytes
                probe = RestoreModel.probe(name, bytes)
                step = Step.Idle
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                clearFile()
                step = Step.Failed(RestoreModel.Failure.Io)
            }
        }
    }

    /** 换一个文件 / 退出这一页。把那一整个库从内存里丢掉。 */
    fun clearFile() {
        pending = null
        probe = null
    }

    /**
     * 恢复。[password] 是调用方在主线程取的**副本**，本方法负责清零，
     * 交出来之后就不要再碰它了——包括在 busy 时被拒绝的那次（同 `CreateVaultController`）。
     */
    fun submit(password: CharArray) {
        if (busy) { password.wipe(); return }
        val bytes = pending
        if (bytes == null) { password.wipe(); return }

        job = scope.launch {
            try {
                // 页面上已经拦过一道（`RestoreModel.blockReason`），这里是最后一道。
                // 恢复覆盖不了现有的库，这条界限要在两处各守一遍：
                // 页面那道是给用户看的，这道是给「相位刚好在这一瞬间变了」准备的。
                if (repo.exists()) {
                    step = Step.Failed(RestoreModel.Failure.VaultExists)
                    return@launch
                }

                step = Step.Opening
                val opened = withContext(worker) {
                    repo.restoreAndOpen(bytes, password) {
                        // 口令验过了，接下来是写盘。两次等待的原因不同，所以要换一句话。
                        //
                        // 这个回调跑在 [worker] 上，而 `step` 是 Compose 状态——
                        // 在工作线程上直接写它是能写进去，但那属于「碰巧能用」：
                        // 快照系统对跨线程写没有做任何承诺，界面什么时候跟上是不确定的。
                        // 丢回 [scope]（页面那个 scope，跑在主线程）翻这一下，
                        // 代价是一次极短的调度，换的是一条不用担心的规矩。
                        scope.launch { if (busy) step = Step.Installing }
                    }
                }

                // 相位一翻，整棵引导子树连同这一页一起被换掉（决策⑪）。
                // 所以这之后不需要也不能再设 step —— 这一页那时已经不在了。
                session.adopt(opened)

                // 决策：刚用一份能打开的备份装上了这台设备，那份备份就是最新的。
                // 记失败不算恢复失败，见类注释第 5 步。
                runCatching { session.markBackedUp() }

                clearFile()
                step = Step.Idle
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                step = Step.Failed(classify(t))
            } finally {
                // 成功、失败、被取消，三条路都从这里出去，口令副本一定被抹掉
                password.wipe()
            }
        }
    }

    fun dismissError() { if (step is Step.Failed) step = Step.Idle }

    fun cancel() { job?.cancel(); job = null }

    /**
     * 异常 → 那八条话里的一条。
     *
     * 顺序要紧：[VaultNotRecognizedException] 和 [VaultTooNewException] 都是
     * [VaultFormatException] 的子类（M5 才分出来的，见 `VaultFile.kt`），
     * 父类那一支必须排在最后，否则两条更准确的话永远走不到。
     */
    private fun classify(t: Throwable): RestoreModel.Failure = when (t) {
        is WrongPasswordException -> RestoreModel.Failure.WrongPassword
        is VaultCorruptedException -> RestoreModel.Failure.Corrupted
        is VaultNotRecognizedException -> RestoreModel.Failure.NotVaultFile
        is VaultTooNewException -> RestoreModel.Failure.TooNew
        is UnsupportedKdfException -> RestoreModel.Failure.UnsupportedKdf
        // 文件头本身坏了。对用户来说和「密文解不开」是同一件事、同一个下一步。
        is VaultFormatException -> RestoreModel.Failure.Corrupted
        // 写出去的和读回来的对不上。库没装成，这台设备上什么都没留下。
        is VaultWriteVerificationException -> RestoreModel.Failure.Io
        is java.io.IOException -> RestoreModel.Failure.Io
        // `restoreAndOpen` 里那个 `check(!storage.exists())`
        is IllegalStateException -> RestoreModel.Failure.VaultExists
        else -> RestoreModel.Failure.Unknown
    }
}
