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

package cn.localvault.app.ui.util

import cn.localvault.app.core.crypto.KdfParams
import java.util.Calendar
import java.util.Locale

/**
 * 显示层的格式化。都是纯函数，方便单测。
 */
object Fmt {

    /** 「刚刚 / 12 分钟前 / 昨天 / 3 月 14 日 / 2024 年 3 月 14 日」 */
    fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0L) return "从未"
        val diff = now - millis
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000} 分钟前"
            diff < 86_400_000L && isSameDay(millis, now) -> "${diff / 3_600_000} 小时前"
            isYesterday(millis, now) -> "昨天"
            diff < 30L * 86_400_000L -> "${diff / 86_400_000} 天前"
            else -> date(millis, withYear = !isSameYear(millis, now))
        }
    }

    fun date(millis: Long, withYear: Boolean = true): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return if (withYear) "${c.get(Calendar.YEAR)} 年 $m 月 $d 日" else "$m 月 $d 日"
    }

    fun dateTime(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(
            Locale.US, "%d-%02d-%02d %02d:%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
        )
    }

    /** 用于导出文件名，不含空格和冒号 */
    fun fileStamp(millis: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(
            Locale.US, "%04d%02d%02d-%02d%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
        )
    }

    /**
     * 导出用的建议文件名。
     *
     * 扩展名只是给人看的：系统文件选择器可能按 MIME 类型改写它
     * （`application/octet-stream` 在某些 ROM 上会被追加 `.bin`）。
     * 所以**导入侧一律不认扩展名**，只认文件头开头那六个字节的 `LVAULT` 标识，
     * 见 `VaultFile.parseHeader`。这条一旦破了，用户把文件重命名一次就打不开了。
     */
    fun backupFileName(millis: Long = System.currentTimeMillis()): String =
        "localvault-${fileStamp(millis)}.lvault"

    /**
     * 封条上那一行加密参数。
     * 这行字是给用户「自己核对」用的，所以写真实参数，不写营销话术。
     */
    fun kdfLabel(p: KdfParams): String = when (p.id) {
        KdfParams.ID_ARGON2ID -> "Argon2id ${p.memoryKiB / 1024}MiB/t${p.iterations}"
        KdfParams.ID_PBKDF2_SHA512 -> "PBKDF2-SHA512 ${p.iterations / 1000}k"
        else -> "KDF#${p.id}"
    }

    fun bytes(n: Int): String = bytes(n.toLong())

    /**
     * `File.length()` 给的是 Long，导出页拿到的字节数是 Int。
     * 两个重载共用同一份实现，免得同一个数字在两页上写法不一样。
     */
    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024))
    }

    /**
     * 退避倒计时：「0:09」「15:00」。
     *
     * 不足一秒按一秒算（向上取整）：显示 0:00 却还按不动按钮，
     * 看起来就像卡死了。宁可多显示一秒，也不要出现「归零了但没解开」的一瞬。
     */
    fun countdown(millis: Long): String {
        val total = ((millis.coerceAtLeast(0L) + 999) / 1000).toInt()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    fun autoLockLabel(seconds: Int): String = when {
        seconds <= 0 -> "立即"
        seconds < 60 -> "$seconds 秒"
        seconds % 60 == 0 -> "${seconds / 60} 分钟"
        else -> "${seconds / 60} 分 ${seconds % 60} 秒"
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private fun isSameYear(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
    }

    private fun isYesterday(a: Long, now: Long): Boolean =
        isSameDay(a, now - 86_400_000L)
}
