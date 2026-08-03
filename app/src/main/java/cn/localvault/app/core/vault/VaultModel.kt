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

package cn.localvault.app.core.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 保险库的明文数据模型。
 *
 * 这些对象只在解锁后存在于内存中，落盘前整体加密。
 * 字段名用短名（@SerialName）是为了压缩体积——一个 500 条的库
 * 用短名能小三成，加密后写盘更快，也少一点内存驻留。
 */
@Serializable
data class VaultData(
    @SerialName("v") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("e") val entries: List<VaultEntry> = emptyList(),
    @SerialName("m") val meta: VaultMeta = VaultMeta(),
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

@Serializable
data class VaultMeta(
    /** 库创建时间（毫秒） */
    @SerialName("c") val createdAt: Long = 0L,
    /** 最后一次成功导出备份的时间。0 表示从未备份——首页要为此常驻提醒。 */
    @SerialName("b") val lastBackupAt: Long = 0L,
    /**
     * 最后一次修改主密码的时间。0 表示从建库起就没改过。
     *
     * 它存在只为回答一个问题：**用户手上那份备份文件，还认不认他现在记得的这个主密码？**
     * 备份文件是改主密码之前导出的话，答案是不认——那份文件只认旧主密码，
     * 而旧主密码正是用户刚刚决定不再用、多半也不会再记住的那一个。
     * 这是这个 App 里最安静的一条数据丢失路径：什么都没报错，
     * 半年后想恢复时才发现打不开。有了这个时间戳，
     * `lastBackupAt < masterChangedAt` 就是一个能当场算出来、也能写在屏幕上的事实。
     */
    @SerialName("mc") val masterChangedAt: Long = 0L,
    /** 自动锁定超时（秒），0 表示切后台立即锁 */
    @SerialName("t") val autoLockSeconds: Int = 60,
    /** 剪贴板自动清除倒计时（秒） */
    @SerialName("cc") val clipboardClearSeconds: Int = 15,
)

@Serializable
data class VaultEntry(
    @SerialName("i") val id: String,
    /** 名称，如「微信」「招商银行」 */
    @SerialName("n") val name: String,
    /** 账号 / 用户名 / 手机号 */
    @SerialName("u") val username: String = "",
    /** 密码明文（整个库已加密，条目层不再二次加密） */
    @SerialName("p") val password: String = "",
    /** 关联网址或安卓包名，自动填充靠它匹配 */
    @SerialName("d") val domains: List<String> = emptyList(),
    @SerialName("g") val category: String = "",
    @SerialName("o") val notes: String = "",
    /** 动态验证码密钥（Base32）。2.0 功能，先留字段避免将来改格式 */
    @SerialName("s") val totpSecret: String? = null,
    @SerialName("f") val favorite: Boolean = false,
    @SerialName("ca") val createdAt: Long = 0L,
    @SerialName("ua") val updatedAt: Long = 0L,
    /** 密码本身最后一次变更的时间，用于「该换密码了」提醒 */
    @SerialName("pa") val passwordUpdatedAt: Long = 0L,
) {
    /**
     * 图标底色。刻意用名称哈希本地生成，不联网抓 favicon——
     * 一旦为了好看去请求网络，「无网络权限」这个卖点就没了。
     */
    val tileColor: Int get() = TILE_PALETTE[Math.floorMod(name.hashCode(), TILE_PALETTE.size)]

    val initial: String get() = name.trim().take(1).ifEmpty { "?" }

    companion object {
        /**
         * 低饱和度色板，和钢青机身不打架。
         *
         * ── 修订（v2）──
         *
         * 初版有两格（#2D333B、#1B2838）明度低到对 [Void] 背景只有
         * 1.5:1 和 1.28:1 —— 名字哈希到这两格的条目，色块**根本不显形**，
         * 看起来只是一个白色首字浮在黑底上。十二格里有两格失效，
         * 意味着大约每六条就有一条不参与「靠颜色扫读」这件事，
         * 而那正是不联网抓 favicon 时唯一的替代方案。
         *
         * 重排的两条约束：
         *   1. **色相等间隔**：12 格均分色环（每 30°）。初版是手挑的，
         *      结果四格挤在蓝—青区间、两格是几乎无色的深灰蓝，
         *      能真正互相区分的其实不到八格；
         *   2. **明度对齐**：全部落在 relative luminance ≈ 0.132，
         *      于是每格都是「白字 ≥5.7:1、离背景 ≥3.3:1」。
         *      明度统一还有个副作用是列表滚动时不会有几行突然跳出来 ——
         *      色块该传达的是**身份**，不是**优先级**。
         *
         * 饱和度压在 52%，保留原来「不和钢青机身打架」的意图。
         *
         * 注意：色板改了，已有条目的色块颜色会跟着变（映射仍由
         * 名称哈希决定，同一个名字依旧稳定对应同一格）。这纯属观感变化，
         * 不涉及任何库内数据。
         */
        val TILE_PALETTE = intArrayOf(
            0xFF247441.toInt(), 0xFF247167.toInt(), 0xFF2D6C90.toInt(), 0xFF4B5CC6.toInt(),
            0xFF784BC6.toInt(), 0xFF9F37AF.toInt(), 0xFFAD3782.toInt(), 0xFFB5394A.toInt(),
            0xFF96552F.toInt(), 0xFF706623.toInt(), 0xFF536E23.toInt(), 0xFF2F7424.toInt(),
        )
    }
}
