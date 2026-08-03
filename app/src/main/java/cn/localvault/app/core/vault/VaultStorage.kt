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

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 保险库文件的落盘层。这一层只关心「字节安全地写进去、完整地读出来」，
 * 完全不碰加解密。
 *
 * ───────────── 为什么值得单独写一层 ─────────────
 *
 * 本地优先产品最大的真实风险不是被破解，是**数据没了**。
 * 而数据没了最常见的原因不是黑客，是：写到一半进程被杀 / 掉电 / 存储写满。
 * 直接 `file.writeBytes(newContent)` 会先把原文件截断成 0 字节再写——
 * 这个瞬间如果断了，用户的全部密码就是一个空文件。
 *
 * 所以写入流程是：
 *   1. 写 main.lvault.tmp，flush + fsync（必须 fsync，否则只是进了页缓存）
 *   2. 把当前的 main.lvault 改名成 main.lvault.bak（上一版留着）
 *   3. 把 tmp 改名成 main.lvault（同目录 rename，原子操作）
 *
 * 任何一步中断，磁盘上至少还有一个完整的旧版本。
 * 打开时 [load] 会自动做崩溃恢复。
 */
class VaultStorage(private val dir: File) {

    val mainFile = File(dir, NAME)
    private val tmpFile = File(dir, "$NAME.tmp")
    private val bakFile = File(dir, "$NAME.bak")

    init {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建保险库目录：${dir.absolutePath}")
        }
    }

    fun exists(): Boolean = mainFile.exists() || bakFile.exists()

    /**
     * 读取当前保险库字节。会自动处理上一次写入被打断的残局。
     *
     * @return 文件内容；库不存在时返回 null
     */
    fun load(): ByteArray? {
        recoverIfNeeded()
        return if (mainFile.exists()) mainFile.readBytes() else null
    }

    /** 上一个成功保存的版本。主文件损坏时给用户的最后一根稻草。 */
    fun loadBackup(): ByteArray? = if (bakFile.exists()) bakFile.readBytes() else null

    /**
     * 原子保存。
     *
     * 调用方必须保证 [bytes] 是一个**完整且能被解开**的保险库文件——
     * 这一层不做校验，因为它没有密钥。校验在 [VaultRepository] 里做。
     */
    @Synchronized
    fun save(bytes: ByteArray) {
        require(bytes.size > 20) { "拒绝写入明显不完整的保险库文件" }

        // 1. 写临时文件并落到物理介质
        FileOutputStream(tmpFile).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()          // 少了这一行，掉电时前面的功夫全白费
        }

        // 2. 现役版本退成备份
        if (mainFile.exists()) {
            if (bakFile.exists() && !bakFile.delete()) {
                Log.w(TAG, "旧备份删除失败，继续尝试覆盖")
            }
            if (!mainFile.renameTo(bakFile)) {
                // 改名失败（极少见）。退回复制方式，慢但不会丢。
                mainFile.copyTo(bakFile, overwrite = true)
                mainFile.delete()
            }
        }

        // 3. 临时文件转正
        if (!tmpFile.renameTo(mainFile)) {
            // 转正失败：把备份还原回去，宁可丢这次修改，也不能让库消失
            if (bakFile.exists()) bakFile.copyTo(mainFile, overwrite = true)
            throw IOException("保存失败：无法提交新版本的保险库文件")
        }

        hardenPermissions()
    }

    /**
     * 崩溃恢复。三种残局：
     *   - 有 main：正常，不动
     *   - 无 main、有 tmp：崩在第 2 或第 3 步之间 → tmp 是完整的新版本，扶正
     *   - 无 main、无 tmp、有 bak：崩在第 2 步之后 → 用 bak 顶上（丢最后一次修改）
     */
    private fun recoverIfNeeded() {
        if (mainFile.exists()) {
            if (tmpFile.exists()) tmpFile.delete()   // 上次崩在第 1 步，tmp 是垃圾
            return
        }
        when {
            tmpFile.exists() -> {
                Log.w(TAG, "检测到未完成的写入，正在扶正临时文件")
                tmpFile.renameTo(mainFile)
            }
            bakFile.exists() -> {
                Log.w(TAG, "主文件缺失，从上一版备份恢复")
                bakFile.copyTo(mainFile, overwrite = true)
            }
        }
    }

    /**
     * 收紧文件权限：只有本应用 UID 可读写。
     * Android 的 filesDir 本来就是私有的，这里是纵深防御——
     * 万一将来有人手滑把库放到了别处。
     */
    private fun hardenPermissions() {
        runCatching {
            mainFile.setReadable(false, false); mainFile.setReadable(true, true)
            mainFile.setWritable(false, false); mainFile.setWritable(true, true)
            mainFile.setExecutable(false, false)
        }
    }

    /**
     * 彻底删除保险库（用户主动「重置」时）。
     *
     * 注意：这里**不做覆写擦除**。SSD / eMMC 有磨损均衡和 FTL 映射，
     * 往同一个路径写随机数根本覆盖不到原来的物理块，只是自欺欺人还额外磨损闪存。
     * 真正的保障来自 Android 的全盘加密——文件删掉后密钥不可达即等同销毁。
     */
    @Synchronized
    fun deleteAll(): Boolean {
        var ok = true
        listOf(mainFile, tmpFile, bakFile).forEach { f ->
            if (f.exists() && !f.delete()) ok = false
        }
        return ok
    }

    companion object {
        private const val TAG = "VaultStorage"
        const val NAME = "main.lvault"
    }
}
