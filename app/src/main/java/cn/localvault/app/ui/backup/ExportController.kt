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

package cn.localvault.app.ui.backup

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultFile
import cn.localvault.app.core.vault.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导出的落点。把 SAF 的 `Uri` 和 `ContentResolver` 关在实现里，
 * 控制器本身完全不认识 Android —— 于是这段逻辑能在纯 JVM 上测。
 *
 * [readBack] 不是可选项，是这套设计的一半：见 [ExportController] 的说明。
 */
interface ExportSink {
    /** 给用户看的名字，成功后要显示出来（「存到了哪」比「成功了」有用得多）。 */
    val displayName: String
    fun write(bytes: ByteArray)
    /** 把刚写下去的内容原样读回来。读不到返回 null。 */
    fun readBack(): ByteArray?
}

/**
 * 导出加密备份。
 *
 * ── 为什么写完还要读回来比一遍 ──
 *
 * 因为**一份打不开的备份比没有备份更糟**：没有备份的人知道自己没有备份，
 * 而拿着一份坏备份的人以为自己安全了，等到需要它的那天才发现不行，
 * 那时原设备通常已经不在了。
 *
 * 所以这里做三道检查，每一道拦的是不同的东西：
 *   1. **写之前**用内存里的库主密钥把即将写出去的字节解一遍
 *      —— 拦「我们生成了一个自己都打不开的文件」这类代码 bug；
 *      这和 `VaultRepository.save` 落盘前自检是同一个套路。
 *   2. **写之后**把文件读回来逐字节比对
 *      —— 拦写入被截断、目标位置空间不足、provider 没有截断旧内容
 *      （所以 SAF 那边必须用 `"wt"` 模式）这类 IO 问题。
 *   3. 两道都过了才记 `lastBackupAt`
 *      —— 「备份过了」这个标记必须由**验证过的事实**产生，
 *      而不是由「用户点过导出按钮」产生。
 *
 * 代价是多读一遍文件。个人库通常几十 KB，这点开销换的是备份可信。
 */
class ExportController(
    private val repo: VaultRepository,
    private val session: VaultSession,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {

    @Immutable
    sealed interface Step {
        data object Idle : Step
        data object Sealing : Step
        data object Writing : Step
        data object Verifying : Step
        data class Done(val where: String, val bytes: Int) : Step
        data class Failed(val message: String) : Step
    }

    var step by mutableStateOf<Step>(Step.Idle)
        private set

    val busy: Boolean
        get() = step is Step.Sealing || step is Step.Writing || step is Step.Verifying

    private var job: Job? = null

    fun export(sink: ExportSink) {
        if (busy) return
        job = scope.launch {
            try {
                step = Step.Sealing
                val bytes = withContext(worker) {
                    val raw = repo.exportBytes()
                    verifyOpenable(raw)
                    raw
                }

                step = Step.Writing
                withContext(worker) { sink.write(bytes) }

                step = Step.Verifying
                withContext(worker) {
                    val back = sink.readBack()
                        ?: throw ExportVerifyException("写完之后读不回来，无法确认备份是否完整")
                    if (!back.contentEquals(bytes)) {
                        throw ExportVerifyException(
                            "读回来的内容和写下去的不一致（写 ${bytes.size} 字节，读到 ${back.size} 字节）"
                        )
                    }
                    verifyOpenable(back)
                }

                // 只有到这里才算「备份过了」。这一步会重写库文件（更新 lastBackupAt），
                // 所以刚导出的那份备份会比库文件旧一个版本 —— 无所谓，
                // 备份文件本身是自洽完整的，少的只是一个时间戳。
                withContext(worker) { session.markBackedUp() }

                step = Step.Done(sink.displayName, bytes.size)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                step = Step.Failed(explain(t))
            }
        }
    }

    fun reset() { step = Step.Idle }

    fun cancel() { job?.cancel(); job = null }

    /**
     * 用内存里的库主密钥把这份字节解开一次。
     *
     * 走 `openWithKey` 而不是 `open`：这里不需要也不该碰主密码。
     * `Opened` 里的密钥是一份拷贝，`use` 结束就被擦掉，动不到会话那一份。
     */
    private fun verifyOpenable(bytes: ByteArray) {
        session.withVaultKey { key -> VaultFile.openWithKey(bytes, key).use { } }
    }

    private fun explain(t: Throwable): String = when (t) {
        is ExportVerifyException ->
            "备份写出去了，但校验没通过：${t.message}。请换一个位置重试，不要依赖这份文件。"
        is IllegalStateException ->
            // withVaultKey 在锁定后会抛这个。可信中断的宽限用完就会走到这里。
            "保险库已锁定，导出中止。请重新解锁后再试。"
        is SecurityException ->
            "没有写入所选位置的权限，请换一个文件夹"
        is java.io.IOException ->
            "写入失败：${t.message ?: "存储空间可能不足"}"
        else ->
            "导出失败：${t.javaClass.simpleName}${t.message?.let { "（$it）" } ?: ""}"
    }
}

class ExportVerifyException(message: String) : Exception(message)
