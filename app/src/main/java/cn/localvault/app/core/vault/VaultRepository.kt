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
import cn.localvault.app.core.crypto.KdfParams
import cn.localvault.app.core.crypto.KdfRegistry
import cn.localvault.app.core.crypto.SecureBytes
import cn.localvault.app.core.crypto.toUtf8Secure
import java.io.File

/**
 * 保险库的仓库层：把「加解密」和「落盘」拼在一起，并且负责一件
 * 两边单独都做不到的事——**保证写进磁盘的东西一定能被读回来**。
 *
 * 每次保存前会先把刚生成的密文当场解一遍。多花几毫秒，
 * 换来的是「绝不会因为一个我们没想到的 bug 把用户的库写成不可逆的乱码」。
 */
class VaultRepository(private val storage: VaultStorage) {

    constructor(dir: File) : this(VaultStorage(dir))

    fun exists(): Boolean = storage.exists()

    /**
     * 库文件当前占多少字节。关于页用它兑现「整个库就是一个文件」这句承诺——
     * 一个能当场告诉你「你的全部密码一共 4.3 KB」的应用，
     * 比一句「数据安全存储」更能让人相信它没在别处留副本。
     *
     * 刻意只给大小，不给绝对路径：那个路径在应用私有目录里，
     * 没有 root 的用户按图索骥也打不开。
     */
    fun fileSizeBytes(): Long = storage.mainFile.length()

    // ───────────────────── 建库 ─────────────────────

    /**
     * 新建保险库。[masterPassword] 由调用方清零。
     *
     * @param kdfParams 建议传入 `Argon2idKdf.calibrate()` 的结果，
     *                  让参数匹配这台设备的性能，而不是一刀切。
     */
    fun create(
        masterPassword: CharArray,
        kdfParams: KdfParams = KdfRegistry.preferredParams(),
    ): VaultFile.Opened {
        check(!storage.exists()) { "保险库已存在，不能重复创建" }
        val now = System.currentTimeMillis()
        val data = VaultData(meta = VaultMeta(createdAt = now))

        val (bytes, opened) = masterPassword.toUtf8Secure().use { pw ->
            VaultFile.createAndOpen(pw, data, kdfParams)
        }
        try {
            storage.save(bytes)
        } catch (t: Throwable) {
            opened.close()     // 写盘失败就别把密钥留在内存里
            throw t
        }
        return opened
    }

    // ───────────────────── 解锁 ─────────────────────

    /**
     * 主密码解锁。
     * @throws WrongPasswordException / VaultCorruptedException / VaultFormatException
     */
    fun unlock(masterPassword: CharArray): VaultFile.Opened {
        val bytes = storage.load() ?: throw IllegalStateException("保险库不存在")
        return try {
            masterPassword.toUtf8Secure().use { pw -> VaultFile.open(bytes, pw) }
        } catch (e: VaultCorruptedException) {
            // 主文件解不开，试试上一版备份。这是原子写入留的第二条命。
            Log.w(TAG, "主文件损坏，尝试回退到备份")
            val bak = storage.loadBackup() ?: throw e
            masterPassword.toUtf8Secure().use { pw -> VaultFile.open(bak, pw) }
                .also { Log.w(TAG, "已从备份恢复，将丢失最后一次修改") }
        }
    }

    /** 快捷解锁：库主密钥已由 Keystore 解出，跳过口令派生。 */
    fun unlockWithKey(vaultKey: ByteArray): VaultFile.Opened {
        val bytes = storage.load() ?: throw IllegalStateException("保险库不存在")
        return VaultFile.openWithKey(bytes, vaultKey)
    }

    // ───────────────────── 保存 ─────────────────────

    /**
     * 保存修改。用内存里的库主密钥重新封装，不需要主密码。
     *
     * 落盘前会验证一次：解不回来就抛异常，绝不写进去。
     */
    fun save(data: VaultData, vaultKey: SecureBytes) {
        val current = storage.load() ?: throw IllegalStateException("保险库不存在")
        val sealed = VaultFile.reseal(current, vaultKey.bytes(), data)

        verifyOrThrow(sealed, vaultKey.bytes(), expectedEntries = data.entries.size)
        storage.save(sealed)
    }

    /**
     * 修改主密码。数据不丢，快捷解锁的包裹不受影响
     * （它们包的是库主密钥，而库主密钥没变）。
     *
     * 返回新文件头，调用方要拿它去更新会话里那一份——顶部封条显示的是
     * **当前这个库文件头里**的 KDF 档位（见 `VaultSession.header`），
     * 改完密码不更新的话，封条会继续显示旧的盐和旧的档位，那是假话。
     */
    fun changeMasterPassword(
        newPassword: CharArray,
        vaultKey: SecureBytes,
        kdfParams: KdfParams = KdfRegistry.preferredParams(),
    ): VaultFile.Header = changeMasterPassword(newPassword, vaultKey.bytes(), kdfParams)

    /**
     * 同上，但直接收裸密钥。
     *
     * 给 `VaultSession.withVaultKey { }` 用：那个方法刻意做成回调，
     * 调用方拿到的就是一个 `ByteArray`，而且拿不到长期引用。
     * 为了迁就这里的签名去 `SecureBytes.of(key)` 复制一份，
     * 等于在改密码这段时间里让库主密钥在内存中多存在一副本，
     * 还要多一处「谁负责 wipe」的约定——正是那个回调设计要消灭的东西。
     *
     * **这个重载不接管 [vaultKey] 的生命周期**，它只读不擦：
     * 那把钥匙是会话的，改完密码之后会话还要继续用它保存数据。
     */
    fun changeMasterPassword(
        newPassword: CharArray,
        vaultKey: ByteArray,
        kdfParams: KdfParams,
    ): VaultFile.Header {
        val current = storage.load() ?: throw IllegalStateException("保险库不存在")
        val rewrapped = newPassword.toUtf8Secure().use { pw ->
            VaultFile.rewrap(current, vaultKey, pw, kdfParams)
        }
        // 双重验证：新口令能开、旧主密钥也还能开
        newPassword.toUtf8Secure().use { pw -> VaultFile.open(rewrapped, pw).close() }
        verifyOrThrow(rewrapped, vaultKey, expectedEntries = null)
        storage.save(rewrapped)
        return VaultFile.parseHeader(rewrapped)
    }

    /**
     * 只验证主密码对不对，不改变任何状态。
     *
     * 改主密码之前要先确认「现在坐在这儿的人知道旧主密码」（决策(108)）。
     * 做成仓库层的一个方法而不是让上层自己调 `unlock`，是为了把
     * **那个刚被解出来的库主密钥必须当场擦掉**这件事收在一个地方——
     * `unlock()` 返回的 `Opened` 里躺着一把和会话里那把一模一样的钥匙，
     * 上层一旦忘了 `close()`，内存里就多一副本，而且没有任何东西会报错。
     *
     * 代价是这里会把整个库解密一遍（`VaultFile.open` 不提供「只解钥匙」的入口），
     * 于是明文数据在这一瞬间多存在一份。可接受：调用这个方法的前提本来就是
     * **库已经解锁**，同样的明文此刻正躺在会话里。
     *
     * @return true 表示口令正确；口令错误返回 false。
     *         文件损坏之类的问题照常抛出——那不是「口令不对」，不能混为一谈。
     */
    fun verifyMasterPassword(password: CharArray): Boolean =
        try {
            unlock(password).close()
            true
        } catch (e: WrongPasswordException) {
            false
        }

    /**
     * 把刚封好的字节当场解开验证。
     * 这里捕获的是「代码 bug」，不是「攻击」——但后果一样严重。
     */
    private fun verifyOrThrow(sealed: ByteArray, vaultKey: ByteArray, expectedEntries: Int?) {
        val check = try {
            VaultFile.openWithKey(sealed, vaultKey)
        } catch (e: Exception) {
            throw VaultWriteVerificationException(e)
        }
        check.use {
            if (expectedEntries != null && it.data.entries.size != expectedEntries) {
                throw VaultWriteVerificationException(
                    IllegalStateException("条目数不符：期望 $expectedEntries，实际 ${it.data.entries.size}")
                )
            }
        }
    }

    // ───────────────────── 导入 / 重置 ─────────────────────

    /**
     * 从一份外部备份文件恢复，并直接返回解锁态。
     *
     * ── 为什么返回 `Opened` 而不是 `Unit` ──
     *
     * 同 `VaultFile.createAndOpen` 的理由：恢复完成之后紧接着就是「已解锁」，
     * 让上层再调一次 `unlock()` 等于把 Argon2id 连跑两遍——低端机上就是三秒白等，
     * 而库主密钥这时候本来就在手上。
     * （M5 之前这里是 `restoreFrom(bytes, password)`：它验完口令就把 `Opened` 关掉，
     * 于是调用方除了再派生一次没有别的出路。那个签名没有任何调用点，直接换掉。）
     *
     * ── 三条硬性顺序 ──
     *
     *  1. **先确认这台设备上没有库**。恢复是「装上一份新的」，不是「盖掉现有的」：
     *     盖掉是不可逆的数据破坏，而想换库的用户手边有删除页和清空页，那两页各有门槛。
     *     这里的 `check` 是最后一道，页面上还有一道（见 `RestoreModel.blockReason`）。
     *  2. **先验口令能打开，再落盘**。绝不允许把一个打不开的文件装成这台设备的库——
     *     那会让用户从「有一份打不开的备份」变成「有一个打不开的库」，处境更差。
     *  3. **落盘之后再逐字节读回来比一遍**。这是导出那三道检查（决策⑱）在导入侧的镜像：
     *     写出去的和读回来的必须完全一致，否则这台设备上的库和用户手上那份文件已经分叉，
     *     而分叉的表现要到下一次解锁才会暴露。
     *
     * **写进磁盘的就是这份文件本身，一个字节都不改**（不重新封装、不换 nonce、
     * 不刷新任何时间戳）。于是恢复完成的那一刻，「这台设备上的库」和
     * 「用户手上那份备份」是逐字节相同的两份东西——这句话能写在屏幕上，
     * 也能被 `contentEquals` 钉死。
     *
     * @param onVerified 口令验过、还没落盘时回调一次，**只用来翻一个界面状态**
     *        （从「正在核对主密码」翻到「正在装到这台设备上」）。
     *        它抛出的任何异常都会被吞掉：一次界面上的小意外没有资格中止一次
     *        已经验证通过的恢复。
     */
    fun restoreAndOpen(
        fileBytes: ByteArray,
        password: CharArray,
        onVerified: () -> Unit = {},
    ): VaultFile.Opened {
        check(!storage.exists()) { "这台设备上已经有一个保险库，恢复不能覆盖它" }

        val opened = password.toUtf8Secure().use { pw -> VaultFile.open(fileBytes, pw) }
        runCatching { onVerified() }

        try {
            storage.save(fileBytes)
            val onDisk = storage.load()
            if (onDisk == null || !onDisk.contentEquals(fileBytes)) {
                throw VaultWriteVerificationException(
                    IllegalStateException("装到本机的内容和备份文件不一致")
                )
            }
        } catch (t: Throwable) {
            opened.close()          // 装不上就别把密钥留在内存里
            throw t
        }
        return opened
    }

    /** 导出当前库的原始字节（就是备份文件本身，已加密）。 */
    fun exportBytes(): ByteArray = storage.load() ?: throw IllegalStateException("保险库不存在")

    fun deleteEverything(): Boolean = storage.deleteAll()

    companion object { private const val TAG = "VaultRepository" }
}

/**
 * 写盘前的自检失败。这意味着我们生成了一个自己都解不开的文件——
 * 属于必须上报的严重 bug，但**用户的数据是安全的**，因为它没被写下去。
 */
class VaultWriteVerificationException(cause: Throwable) :
    Exception("保存前的完整性校验未通过，本次修改已取消（原数据未受影响）", cause)
