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

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/**
 * 用系统文件选择器（SAF）落盘的导出目标。
 *
 * ── 为什么是 SAF，而不是自己申请存储权限 ──
 *
 * 这直接关系到产品的核心卖点。`WRITE_EXTERNAL_STORAGE` /
 * `MANAGE_EXTERNAL_STORAGE` 会让权限清单不再干净，用户在应用信息里
 * 看到「存储」权限，「这个 App 什么权限都没有」这句话当场作废。
 * SAF 一个权限都不用声明：用户自己在系统界面里挑位置，
 * 我们只拿到那一个文件的写入授权。
 *
 * 顺带得到的好处是位置随便挑 —— 本机文件夹、U 盘、SD 卡、
 * 甚至用户自己装的网盘客户端，都由系统那边接管，我们一行代码都不用改。
 */
class SafExportSink(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ExportSink {

    override val displayName: String
        get() = queryDisplayName() ?: uri.lastPathSegment ?: "所选位置"

    override fun write(bytes: ByteArray) {
        // "wt" 里的 t 是 truncate，不能省。
        // 只写 "w" 时部分 provider 不截断旧内容：用一份小的备份覆盖一份大的，
        // 尾巴上会留着旧文件的残骸，文件长度对不上、解析必挂。
        // 这种坏文件恰恰是「平时看不出来、要用的时候才发现」的那一类。
        val out = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("无法写入所选位置")
        out.use {
            it.write(bytes)
            it.flush()
        }
    }

    override fun readBack(): ByteArray? =
        resolver.openInputStream(uri)?.use { it.readBytes() }

    private fun queryDisplayName(): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
}
