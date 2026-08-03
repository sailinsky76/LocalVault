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

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/**
 * 用系统文件选择器（SAF）读进来的备份文件。[SafExportSink] 的镜像。
 *
 * ── 为什么这一侧也不申请任何权限 ──
 *
 * 导出侧那段理由（见 `SafExportSink`）在这一侧一字不改地成立，而且更硬：
 * 用户要恢复的那份文件多半躺在下载目录、U 盘或者某个网盘客户端的目录里，
 * 而 `ACTION_OPEN_DOCUMENT` 让系统去解决「它在哪」这个问题，
 * 我们只拿到那一个文件的读取授权。关于页那份权限清单因此仍然只有 `USE_BIOMETRIC` 一条——
 * 整个「换机迁移」的闭环（导出 + 恢复）没有给它添过一行。
 *
 * ── 它只有 read，没有 write ──
 *
 * 这不是省事，是 [RestoreModel.UNTOUCHED_CLAUSE] 那句「你手上那份文件没有被改动」
 * 的实现依据：接口 [ImportSource] 里根本没有写入方法，这个类也就无从写起。
 * 注意这里连 `takePersistableUriPermission` 都没调——我们对这个 Uri 的兴趣
 * 只持续到 `read()` 返回为止，不需要它活过这次选择。
 */
class SafImportSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ImportSource {

    override val displayName: String
        get() = queryDisplayName() ?: uri.lastPathSegment ?: "所选文件"

    /**
     * 整个读进内存。
     *
     * ── 那个上限是干什么的 ──
     *
     * 文件选择器里什么都点得到，包括一部两个 G 的电影。不设上限的话，
     * 那一下不是「恢复失败」，是 `OutOfMemoryError`——一个 Error，
     * 它会顺着 `catch (t: Throwable)` 被归成「读不下来」，但在那之前
     * 进程已经在做一次不必要的大分配了，低端机上足以直接被系统杀掉。
     *
     * 上限取 64 MiB：一个装了上万条的库也只有几个 MB 量级
     * （条目是文本，密文按明文长度增长），离这个数还差两个数量级。
     * 先问一次 `SIZE` 列，问不到再靠流上的计数兜底——某些 provider
     * 不提供 SIZE，那一列返回 null 是常事，不能只靠它。
     *
     * **超限报的是「读不下来」那条话**（`Failure.Io`，见 `RestoreController.classify`）。
     * 它对一个错点了电影的人来说不够准，但这一步不该为它去改内核里那八条文案——
     * 真要加第九条，位置在 `RestoreModel.Failure` 里，见 PROGRESS 的说明。
     */
    override fun read(): ByteArray {
        querySize()?.let { size ->
            if (size > MAX_BYTES) throw IOException("文件太大，不像是保险库备份")
        }
        val input = resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
        return input.use { stream ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IOException("文件太大，不像是保险库备份")
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        }
    }

    private fun queryDisplayName(): String? = queryColumn(OpenableColumns.DISPLAY_NAME) { c ->
        c.getString(0)
    }

    private fun querySize(): Long? = queryColumn(OpenableColumns.SIZE) { c ->
        if (c.isNull(0)) null else c.getLong(0)
    }

    /**
     * 查一列。整段包在 `runCatching` 里：这两列都只影响**说得好不好听**
     * （名字显示成什么、能不能提前拦住一个大文件），
     * 而某些 provider 在 `query` 上直接抛异常。为了一行显示文字让整条恢复流程挂掉，
     * 那才是真正的 bug。查不到就退回流上的兜底。
     */
    private fun <R> queryColumn(column: String, read: (android.database.Cursor) -> R?): R? =
        runCatching {
            resolver.query(uri, arrayOf(column), null, null, null)
                ?.use { c -> if (c.moveToFirst()) read(c) else null }
        }.getOrNull()

    private companion object {
        const val MAX_BYTES: Long = 64L * 1024 * 1024
    }
}
