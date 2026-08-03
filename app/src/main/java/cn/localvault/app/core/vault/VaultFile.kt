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

import cn.localvault.app.core.crypto.Aead
import cn.localvault.app.core.crypto.AeadRegistry
import cn.localvault.app.core.crypto.KdfParams
import cn.localvault.app.core.crypto.KdfRegistry
import cn.localvault.app.core.crypto.Rng
import cn.localvault.app.core.crypto.SecureBytes
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException

/**
 * ┌───────────────────────────────────────────────────────────────────┐
 * │  .lvault 文件格式 v1                                                │
 * ├───────────────────────────────────────────────────────────────────┤
 * │  偏移  长度  字段                                                    │
 * │   0     6   魔数 "LVAULT"                                          │
 * │   6     2   格式版本 (u16 BE)                                       │
 * │   8     1   KDF 编号                                               │
 * │   9     4   KDF 内存开销 KiB (u32 BE)                               │
 * │  13     4   KDF 迭代次数 (u32 BE)                                   │
 * │  17     1   KDF 并行度                                              │
 * │  18     1   加密算法编号                                             │
 * │  19     1   salt 长度 S                                            │
 * │  20     S   salt                                                  │
 * │  ── 以上 (20+S) 字节为文件头，整体作为 AAD ──                          │
 * │  H     12   包裹 nonce                                             │
 * │  H+12  48   被 KEK 包裹的库主密钥 (32B 密钥 + 16B 标签)                │
 * │  H+60  12   数据 nonce                                             │
 * │  H+72   4   密文长度 (u32 BE)                                       │
 * │  H+76   P   密文 + 标签                                             │
 * └───────────────────────────────────────────────────────────────────┘
 *
 * 为什么要两层密钥（口令→KEK→库主密钥→数据），而不是口令直接加密数据：
 *
 *   1. 改主密码只需要重新包一次 32 字节的主密钥，不必把整个库重新加密；
 *   2. 生物解锁可以用 Keystore 里的硬件密钥把同一个库主密钥再包一份，
 *      两条解锁路径共存，而主密码始终是唯一的真凭据；
 *   3. 将来做「紧急恢复码」也是同一个套路，再包一份就行。
 *
 * 文件头整体作为 AAD 参与认证，所以攻击者无法把 KDF 参数改弱后
 * 让 App 用低成本参数去派生——改一个字节，解密就会失败。
 */
object VaultFile {

    private val MAGIC = "LVAULT".toByteArray(Charsets.US_ASCII)
    const val FORMAT_VERSION = 1
    private const val SALT_LEN = 16
    private const val VAULT_KEY_LEN = 32

    private val json = Json {
        ignoreUnknownKeys = true      // 向前兼容：老版本读到新字段不炸
        encodeDefaults = false        // 默认值不写，省体积
    }

    data class Header(
        val formatVersion: Int,
        val kdfParams: KdfParams,
        val cipherId: Int,
        val salt: ByteArray,
    ) {
        val byteLength: Int get() = 20 + salt.size
    }

    /** 解锁成功后的产物：明文数据 + 库主密钥（后续保存和生物绑定都要用） */
    class Opened(val data: VaultData, val vaultKey: SecureBytes, val header: Header) : java.io.Closeable {
        override fun close() = vaultKey.wipe()
    }

    // ───────────────────────── 创建 ─────────────────────────

    /**
     * 用主密码新建一个保险库文件。
     * @param password 主密码的 UTF-8 字节，由调用方负责清零
     */
    fun create(
        password: ByteArray,
        data: VaultData,
        kdfParams: KdfParams = KdfRegistry.preferredParams(),
        cipherId: Int = AeadRegistry.default.id,
    ): ByteArray {
        val (bytes, opened) = createAndOpen(password, data, kdfParams, cipherId)
        opened.close()
        return bytes
    }

    /**
     * 建库并直接返回解锁态。
     *
     * 建库之后紧接着就是「已解锁」状态，如果走 create 再 open，
     * 等于连跑两次 Argon2id —— 低端机上就是三秒白等。
     * 库主密钥本来就在手上，没有理由再派生一次。
     */
    fun createAndOpen(
        password: ByteArray,
        data: VaultData,
        kdfParams: KdfParams = KdfRegistry.preferredParams(),
        cipherId: Int = AeadRegistry.default.id,
    ): Pair<ByteArray, Opened> {
        val salt = Rng.bytes(SALT_LEN)
        val header = Header(FORMAT_VERSION, kdfParams, cipherId, salt)
        val vaultKey = SecureBytes.wrap(Rng.bytes(VAULT_KEY_LEN))
        val bytes = try {
            serialize(header, vaultKey.bytes(), password, data)
        } catch (t: Throwable) {
            vaultKey.wipe()
            throw t
        }
        return bytes to Opened(data, vaultKey, header)
    }

    /**
     * 用已经在内存里的库主密钥重新落盘（日常保存走这条，不需要再跑一次 Argon2）。
     * 注意：这里必须复用原来的 header（含 salt），否则 KEK 对不上。
     * 但每次都要换新的 nonce —— GCM 下同密钥重用 nonce 是灾难性的。
     */
    fun reseal(existing: ByteArray, vaultKey: ByteArray, data: VaultData): ByteArray {
        val header = parseHeader(existing)
        val plaintext = json.encodeToString(VaultData.serializer(), data).toByteArray(Charsets.UTF_8)
        val aead = AeadRegistry.get(header.cipherId)
        val headerBytes = existing.copyOfRange(0, header.byteLength)
        // 包裹块原样保留：主密钥没变，KEK 也没变
        val wrapBlockStart = header.byteLength
        val wrapBlock = existing.copyOfRange(wrapBlockStart, wrapBlockStart + aead.nonceLength + VAULT_KEY_LEN + aead.tagLength)

        val dataNonce = Rng.bytes(aead.nonceLength)
        val ciphertext = aead.seal(vaultKey, dataNonce, plaintext, headerBytes)
        java.util.Arrays.fill(plaintext, 0)

        return ByteBuffer.allocate(headerBytes.size + wrapBlock.size + dataNonce.size + 4 + ciphertext.size).apply {
            put(headerBytes); put(wrapBlock); put(dataNonce); putInt(ciphertext.size); put(ciphertext)
        }.array()
    }

    // ───────────────────────── 解锁 ─────────────────────────

    /**
     * 用主密码打开保险库。
     * @throws WrongPasswordException 口令错误或文件被篡改
     * @throws VaultFormatException   不是合法的库文件
     */
    fun open(bytes: ByteArray, password: ByteArray): Opened {
        val header = parseHeader(bytes)
        val aead = AeadRegistry.get(header.cipherId)
        val kdf = KdfRegistry.get(header.kdfParams.id)

        val headerBytes = bytes.copyOfRange(0, header.byteLength)
        var p = header.byteLength

        val wrapNonce = bytes.readOrFail(p, aead.nonceLength); p += aead.nonceLength
        val wrappedKey = bytes.readOrFail(p, VAULT_KEY_LEN + aead.tagLength); p += wrappedKey.size
        val dataNonce = bytes.readOrFail(p, aead.nonceLength); p += aead.nonceLength
        val payloadLen = ByteBuffer.wrap(bytes.readOrFail(p, 4)).int; p += 4
        if (payloadLen < 0 || p + payloadLen > bytes.size) throw VaultFormatException("密文长度声明与实际文件不符")
        val payload = bytes.copyOfRange(p, p + payloadLen)

        val kek = kdf.derive(password, header.salt, header.kdfParams, VAULT_KEY_LEN)
        val vaultKey: SecureBytes = try {
            SecureBytes.wrap(aead.open(kek.bytes(), wrapNonce, wrappedKey, headerBytes))
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        } finally {
            kek.wipe()
        }

        val plaintext = try {
            aead.open(vaultKey.bytes(), dataNonce, payload, headerBytes)
        } catch (e: AEADBadTagException) {
            vaultKey.wipe()
            // 主密钥解出来了但数据解不开 → 文件损坏，不是密码错
            throw VaultCorruptedException()
        }

        val data = try {
            json.decodeFromString(VaultData.serializer(), String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            vaultKey.wipe()
            throw VaultCorruptedException()
        } finally {
            java.util.Arrays.fill(plaintext, 0)
        }

        return Opened(data, vaultKey, header)
    }

    /**
     * 用已经拿到的库主密钥直接打开，跳过口令派生。
     *
     * 快捷解锁（指纹 / PIN）走这条：库主密钥是从 Keystore 里解出来的，
     * 不需要也没有主密码，所以不能走 [open]。
     * 少跑一次 Argon2id，指纹解锁才能做到「按下即开」。
     */
    fun openWithKey(bytes: ByteArray, vaultKey: ByteArray): Opened {
        val header = parseHeader(bytes)
        val aead = AeadRegistry.get(header.cipherId)
        val headerBytes = bytes.copyOfRange(0, header.byteLength)

        // 跳过包裹块：wrapNonce + wrappedKey
        var p = header.byteLength + aead.nonceLength + VAULT_KEY_LEN + aead.tagLength
        val dataNonce = bytes.readOrFail(p, aead.nonceLength); p += aead.nonceLength
        val payloadLen = ByteBuffer.wrap(bytes.readOrFail(p, 4)).int; p += 4
        if (payloadLen < 0 || p + payloadLen > bytes.size) throw VaultFormatException("密文长度声明与实际文件不符")
        val payload = bytes.copyOfRange(p, p + payloadLen)

        val plaintext = try {
            aead.open(vaultKey, dataNonce, payload, headerBytes)
        } catch (e: AEADBadTagException) {
            // 密钥对不上，或文件已损坏。对快捷解锁来说两者都该退回主密码。
            throw VaultCorruptedException()
        }

        val data = try {
            json.decodeFromString(VaultData.serializer(), String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw VaultCorruptedException()
        } finally {
            java.util.Arrays.fill(plaintext, 0)
        }

        return Opened(data, SecureBytes.of(vaultKey), header)
    }

    /**
     * 修改主密码：只重新包裹主密钥，数据密文原样不动。
     * 换新 salt 和新 nonce。
     */
    fun rewrap(
        existing: ByteArray,
        vaultKey: ByteArray,
        newPassword: ByteArray,
        kdfParams: KdfParams = KdfRegistry.preferredParams(),
    ): ByteArray {
        val oldHeader = parseHeader(existing)
        val oldAead = AeadRegistry.get(oldHeader.cipherId)
        // 先把旧密文取出来（还是用同一个 vaultKey 加密的，不需要重新加密）
        var p = oldHeader.byteLength + oldAead.nonceLength + VAULT_KEY_LEN + oldAead.tagLength
        val dataNonce = existing.readOrFail(p, oldAead.nonceLength); p += oldAead.nonceLength
        val payloadLen = ByteBuffer.wrap(existing.readOrFail(p, 4)).int; p += 4
        val payload = existing.copyOfRange(p, p + payloadLen)

        // 新头
        val newHeader = Header(FORMAT_VERSION, kdfParams, oldHeader.cipherId, Rng.bytes(SALT_LEN))
        val newHeaderBytes = writeHeader(newHeader)
        val aead = AeadRegistry.get(newHeader.cipherId)
        val kdf = KdfRegistry.get(kdfParams.id)

        // ⚠ AAD 变了（新 header），所以数据密文必须跟着重新加密一次。
        //   这是唯一一处「改密码要动全量数据」的地方，代价可接受，
        //   换来的是 KDF 参数无法被降级攻击。
        val plaintext = aead.open(vaultKey, dataNonce, payload, existing.copyOfRange(0, oldHeader.byteLength))
        val newDataNonce = Rng.bytes(aead.nonceLength)
        val newPayload = aead.seal(vaultKey, newDataNonce, plaintext, newHeaderBytes)
        java.util.Arrays.fill(plaintext, 0)

        val kek = kdf.derive(newPassword, newHeader.salt, kdfParams, VAULT_KEY_LEN)
        val wrapNonce = Rng.bytes(aead.nonceLength)
        val wrapped = try {
            aead.seal(kek.bytes(), wrapNonce, vaultKey, newHeaderBytes)
        } finally { kek.wipe() }

        return ByteBuffer.allocate(newHeaderBytes.size + wrapNonce.size + wrapped.size + newDataNonce.size + 4 + newPayload.size).apply {
            put(newHeaderBytes); put(wrapNonce); put(wrapped); put(newDataNonce); putInt(newPayload.size); put(newPayload)
        }.array()
    }

    // ───────────────────────── 内部 ─────────────────────────

    private fun serialize(header: Header, vaultKey: ByteArray, password: ByteArray, data: VaultData): ByteArray {
        val headerBytes = writeHeader(header)
        val aead = AeadRegistry.get(header.cipherId)
        val kdf = KdfRegistry.get(header.kdfParams.id)

        val kek = kdf.derive(password, header.salt, header.kdfParams, VAULT_KEY_LEN)
        val wrapNonce = Rng.bytes(aead.nonceLength)
        val wrapped = try {
            aead.seal(kek.bytes(), wrapNonce, vaultKey, headerBytes)
        } finally { kek.wipe() }

        val plaintext = json.encodeToString(VaultData.serializer(), data).toByteArray(Charsets.UTF_8)
        val dataNonce = Rng.bytes(aead.nonceLength)
        val ciphertext = aead.seal(vaultKey, dataNonce, plaintext, headerBytes)
        java.util.Arrays.fill(plaintext, 0)

        return ByteBuffer.allocate(headerBytes.size + wrapNonce.size + wrapped.size + dataNonce.size + 4 + ciphertext.size).apply {
            put(headerBytes); put(wrapNonce); put(wrapped); put(dataNonce); putInt(ciphertext.size); put(ciphertext)
        }.array()
    }

    private fun writeHeader(h: Header): ByteArray =
        ByteBuffer.allocate(h.byteLength).apply {
            put(MAGIC)
            putShort(h.formatVersion.toShort())
            put(h.kdfParams.id.toByte())
            putInt(h.kdfParams.memoryKiB)
            putInt(h.kdfParams.iterations)
            put(h.kdfParams.parallelism.toByte())
            put(h.cipherId.toByte())
            put(h.salt.size.toByte())
            put(h.salt)
        }.array()

    fun parseHeader(bytes: ByteArray): Header {
        if (bytes.size < 20) throw VaultNotRecognizedException("文件过短，不是保险库文件")
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) throw VaultNotRecognizedException("文件标识不匹配，这不是本应用的保险库文件")
        }
        val bb = ByteBuffer.wrap(bytes)
        bb.position(6)
        val version = bb.short.toInt() and 0xFFFF
        if (version > FORMAT_VERSION) {
            throw VaultTooNewException(version)
        }
        val kdfId = bb.get().toInt() and 0xFF
        val mem = bb.int
        val iters = bb.int
        val par = bb.get().toInt() and 0xFF
        val cipherId = bb.get().toInt() and 0xFF
        val saltLen = bb.get().toInt() and 0xFF
        if (saltLen !in 8..64 || bytes.size < 20 + saltLen) throw VaultFormatException("文件头损坏")
        val salt = bytes.copyOfRange(20, 20 + saltLen)

        // 参数合理性检查：防止构造一个「迭代 0 次」的文件诱导 App 做无成本派生
        if (iters <= 0 || par <= 0 || mem < 0) throw VaultFormatException("文件头参数非法")

        return Header(version, KdfParams(kdfId, mem, iters, par), cipherId, salt)
    }

    private fun ByteArray.readOrFail(offset: Int, len: Int): ByteArray {
        if (offset + len > size) throw VaultFormatException("文件被截断")
        return copyOfRange(offset, offset + len)
    }
}

class WrongPasswordException : Exception("主密码不正确")
class VaultCorruptedException : Exception("保险库文件已损坏，无法解密。请尝试用备份文件恢复")
open class VaultFormatException(msg: String) : Exception(msg)

/**
 * 这压根不是本应用的保险库文件（魔数对不上，或短得连文件头都装不下）。
 *
 * ── 为什么要从 [VaultFormatException] 里分出来 ──
 *
 * 因为恢复页要说的话完全不同。「这不是保险库文件」的下一步是**换一个文件**
 * （用户在文件选择器里点错了，这是恢复流程上最常见的一次失误）；
 * 而「文件头损坏」的下一步是**换一份备份**（这个文件确实是我们的，只是坏了），
 * 那是一句坏得多的消息，不该在用户只是选错文件的时候扔给他。
 *
 * 分不出来的话，唯一的办法是在恢复页上比对异常里的那句中文——
 * 而那句话是给用户看的，会改；一改，判断就悄无声息地失效了。
 *
 * 注意：识别只认这六个字节的文件头，**不认扩展名**（决策㉒）。
 * 系统文件选择器会按 MIME 改写扩展名，认扩展名的话，
 * 用户重命名一次文件就再也恢复不了了。
 */
class VaultNotRecognizedException(msg: String) : VaultFormatException(msg)

/**
 * 文件是我们的，但它是更新版本的应用写的，这个版本读不懂。
 *
 * 单独分出来同样是为了那句话的落点：这既不是「文件坏了」也不是「选错了」，
 * 用户什么都没做错，能解决问题的只有**升级应用**。
 * 而且这时候绝不能提示他「换一份旧的备份试试」——
 * 那会让他拿一份更老的数据把新的盖掉。
 */
class VaultTooNewException(val fileFormatVersion: Int) :
    VaultFormatException("该保险库由更新版本的应用创建（格式 v$fileFormatVersion），请先升级应用")
