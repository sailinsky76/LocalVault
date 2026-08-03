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

package cn.localvault.app.ui.autofill

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * [HostTrust] 的线上实现：查包名，**再核一次签名**。
 *
 * 这个文件里只有「怎么从 `PackageManager` 把摘要取出来」，
 * 判档全在 [BrowserTrust] 里（纯 JVM 可测）——同 `AssistShell` / `StructureRules` 那条缝。
 *
 * ── 为什么不用 `KnownBrowsers` 直接当线上实现 ──
 *
 * 那张表只按包名认，而包名是可以被占位的（见 [BrowserTrust] 文件头）。
 * `KnownBrowsers` 的文档里写死了「它给的是必要条件，不是充分条件」，
 * 就是为了防止有人看见「已经查过浏览器表了」把这一步省掉。这个类是那句话的下半截。
 *
 * ── 缓存 ──
 *
 * 每一次填充请求都会问一遍，而一屏上可能有好几组框。查一次包信息要跨进程，
 * 放在 `onFillRequest` 的关键路径上问十次是没必要的。所以按包名缓存一份，
 * **缓存活在这个对象的生命周期里**（服务实例被销毁就没了）。
 * 不做持久化：一个装在磁盘上的「这些应用是可信浏览器」清单，
 * 既是一份新的用户数据，也是一个新的攻击面，而它省下的只是几毫秒。
 */
class AndroidHostTrust(
    private val packages: PackageManager,
) : HostTrust {

    private val cache = HashMap<String, BrowserTrust.Level>()

    override fun isTrustedBrowser(packageName: String): Boolean = level(packageName).trusted

    /**
     * 这个包落在哪一档。M4-2b 要拿它去取那句话（[BrowserTrust.note]）。
     */
    @Synchronized
    fun level(packageName: String): BrowserTrust.Level {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return BrowserTrust.Level.Unknown
        cache[pkg]?.let { return it }

        // 只有包名在表里时才去查签名：不在表里的一律 Unknown，
        // 而那是绝大多数请求（每一个普通应用都会走到这儿），
        // 没有理由为它们各做一次跨进程查询。
        val level = if (!KnownBrowsers.isTrustedBrowser(pkg)) {
            BrowserTrust.Level.Unknown
        } else {
            BrowserTrust.decide(pkg, digestsOf(pkg))
        }
        cache[pkg] = level
        return level
    }

    /**
     * 取这个包的签名证书摘要（SHA-256，小写十六进制）。读不到返回 null。
     *
     * **null 和空集合是两件事**（见 [BrowserTrust.decide]）：null 是「问不出来」，
     * 空集合是「问出来了，一个签名都没有」。后者在真机上不该发生，
     * 但把它当成「问不出来」会让一个真出了怪事的包蒙混过去。
     *
     * 两条路：API 28 起用 `GET_SIGNING_CERTIFICATES` + `apkContentsSigners`
     * （轮换过签名的包，历史证书在 `signingCertificateHistory` 里，这里一并取）；
     * 26 / 27 上只有已弃用的 `GET_SIGNATURES`。那个常量在这两个版本上是安全可用的：
     * 它出名的那个问题（Janus）针对的是 v1 签名，而 API 26 起安装器要求 v2 签名方案，
     * 摘要算的是整个 APK。
     */
    private fun digestsOf(packageName: String): Set<String>? = runCatching {
        val out = HashSet<String>(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packages.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val signing = info.signingInfo ?: return@runCatching null
            val current: Array<Signature>? = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            current?.forEach { out += sha256(it.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val info = packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.forEach { it?.let { s -> out += sha256(s.toByteArray()) } }
        }
        out
    }.onFailure {
        // 只记包名和异常类型：这一行不涉及库内容，但也没有理由打得更细。
        Log.w(TAG, "读不到签名：$packageName / ${it.javaClass.simpleName}")
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "HostTrust"
        val HEX = "0123456789abcdef".toCharArray()
    }
}
