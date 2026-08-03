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

package cn.localvault.app

import cn.localvault.app.ui.autofill.BrowserTrust
import cn.localvault.app.ui.autofill.KnownBrowsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「顶着这个包名的，是不是真的那个浏览器」。
 *
 * 这一层挡的是**包名占位**：用户手机上没装 Chrome 的话，一个侧载应用
 * 完全可以把自己叫做 `com.android.chrome`，然后堂堂正正通过 `KnownBrowsers` 那张表，
 * 于是 `DomainMatch` 把 `UntrustedHost` 降格成 `Exact`，密码自动出现在它的填充条上。
 * 这种事在真机上根本没法验证——要复现得先做一个仿冒包装上去。所以钉在这儿。
 */
class BrowserTrustTest {

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    /** 一张只为用例存在的小表。真表现在是空的，见 `BrowserTrust.FINGERPRINTS`。 */
    private val table = mapOf(CHROME to setOf(a, b))

    private fun decide(pkg: String, actual: Set<String>?) =
        BrowserTrust.decide(pkg, actual, table)

    /* ══════════════ 一、摘要归一 ══════════════ */

    @Test
    fun `大写带冒号的摘要归一成小写连续十六进制`() {
        // apksigner 给连续小写，keytool 给 AB:CD:… 大写带冒号，
        // 不归一的表现是「签名校验一直失败」——最难查的那种一致失败。
        val colon = "A".repeat(64).chunked(2).joinToString(":")
        assertEquals("a".repeat(64), BrowserTrust.normalizeDigest(colon))
    }

    @Test
    fun `空白和短横都不算数`() {
        assertEquals(a, BrowserTrust.normalizeDigest("  ${"a".repeat(32)}\n${"a".repeat(32)} "))
        assertEquals(a, BrowserTrust.normalizeDigest("a".repeat(64).chunked(4).joinToString("-")))
    }

    @Test
    fun `长度不对的认不出来`() {
        assertEquals("", BrowserTrust.normalizeDigest("a".repeat(63)))
        assertEquals("", BrowserTrust.normalizeDigest("a".repeat(65)))
        assertEquals("", BrowserTrust.normalizeDigest(""))
    }

    @Test
    fun `不是十六进制的认不出来`() {
        assertEquals("", BrowserTrust.normalizeDigest("z".repeat(64)))
        assertEquals("", BrowserTrust.normalizeDigest("g" + "a".repeat(63)))
    }

    @Test
    fun `已经归一过的原样返回`() {
        assertEquals(a, BrowserTrust.normalizeDigest(a))
    }

    /* ══════════════ 二、判档 ══════════════ */

    @Test
    fun `不在浏览器表里的一律不认`() {
        assertEquals(BrowserTrust.Level.Unknown, decide("com.evil.app", setOf(a)))
    }

    @Test
    fun `表里有这一家的摘要且对得上就是已核验`() {
        assertEquals(BrowserTrust.Level.Verified, decide(CHROME, setOf(a)))
    }

    @Test
    fun `命中任意一个摘要就算过`() {
        // 签名轮换、渠道多签都会让实际摘要多于一个；
        // 要求「全在表里」，会在轮换那天把一家正版浏览器判成冒充。
        // 而这不放松安全：私钥不在手上就签不出那个签名。
        assertEquals(BrowserTrust.Level.Verified, decide(CHROME, setOf(c, b)))
    }

    @Test
    fun `表里有但对不上，判的是不认识而不是退回只认包名`() {
        // 退回去等于这张表白建：包名占位那条路会原样通到底。
        assertEquals(BrowserTrust.Level.Unknown, decide(CHROME, setOf(c)))
    }

    @Test
    fun `表里有这一家但签名读不出来，判不认识`() {
        assertEquals(BrowserTrust.Level.Unknown, decide(CHROME, null))
    }

    @Test
    fun `表里没有这一家的摘要就只核对包名`() {
        // 内置表注定不全（没有网络权限，拉不到在线名单）。
        // 判成不可信的话，那个浏览器上从此再也不出填充条，而用户查不出原因。
        assertEquals(BrowserTrust.Level.PackageOnly, decide(FIREFOX, setOf(a)))
        assertEquals(BrowserTrust.Level.PackageOnly, decide(FIREFOX, null))
    }

    @Test
    fun `包名前后的空白和大小写不影响`() {
        assertEquals(BrowserTrust.Level.Verified, decide("  ${CHROME.uppercase()} ", setOf(a)))
    }

    @Test
    fun `表里的摘要写成大写带冒号也照样能对上`() {
        val messy = mapOf(CHROME to setOf("A".repeat(64).chunked(2).joinToString(":")))
        assertEquals(
            BrowserTrust.Level.Verified,
            BrowserTrust.decide(CHROME, setOf(a), messy),
        )
    }

    @Test
    fun `表里写坏的那一条不算数`() {
        val broken = mapOf(CHROME to setOf("这不是摘要"))
        // 唯一一条摘要归一不出来 → 等于这一家没有摘要 → 退回只认包名，
        // 而不是把所有实际摘要都判成对不上。
        assertEquals(
            BrowserTrust.Level.PackageOnly,
            BrowserTrust.decide(CHROME, setOf(a), broken),
        )
    }

    /* ══════════════ 三、够不够格自动建议 ══════════════ */

    @Test
    fun `已核验和只认包名都够格，不认识的不够格`() {
        assertTrue(BrowserTrust.Level.Verified.trusted)
        assertTrue(BrowserTrust.Level.PackageOnly.trusted)
        assertFalse(BrowserTrust.Level.Unknown.trusted)
    }

    /* ══════════════ 四、内置表的守卫 ══════════════ */

    @Test
    fun `内置表里的每一个包名都在浏览器表里`() {
        // 将来往表里加条目时，这一条会挡住「摘要加了、包名却拼错了」——
        // 那种错的表现是那一家永远停在只认包名，而没有任何一处会说话。
        for (pkg in BrowserTrust.FINGERPRINTS.keys) {
            assertTrue("$pkg 不在 KnownBrowsers 里", KnownBrowsers.PACKAGES.contains(pkg))
            assertEquals("包名要小写", pkg.lowercase(), pkg)
        }
    }

    @Test
    fun `内置表里的每一条摘要都是合法的 SHA-256`() {
        for ((pkg, digests) in BrowserTrust.FINGERPRINTS) {
            assertTrue("$pkg 的摘要不能是空集", digests.isNotEmpty())
            for (d in digests) {
                assertEquals("$pkg 的摘要写坏了：$d", 64, BrowserTrust.normalizeDigest(d).length)
            }
        }
    }

    /* ══════════════ 五、话怎么说 ══════════════ */

    @Test
    fun `三档三句话，互不重样`() {
        val notes = BrowserTrust.Level.entries.map { BrowserTrust.note(it) }
        assertEquals(notes.size, notes.toSet().size)
        for (n in notes) assertTrue(n.isNotBlank())
    }

    @Test
    fun `已核验那一句不说安全，也不当作对页面的背书`() {
        // 我们核对的是「这个包是它自称的那个包」，不是「这个页面不是钓鱼网站」。
        // 一句听起来像背书的话，会让用户在真该停下来看一眼的时候放心地点下去。
        val n = BrowserTrust.note(BrowserTrust.Level.Verified)
        assertFalse(n.contains("安全"))
        assertTrue(n.contains("不保证"))
    }

    @Test
    fun `没有一句把它说成故障`() {
        for (level in BrowserTrust.Level.entries) {
            val n = BrowserTrust.note(level)
            assertFalse(n.contains("失败"))
            assertFalse(n.contains("出错"))
        }
    }

    @Test
    fun `不认识那一句要说清用户还能做什么`() {
        // 一句只说「不行」的话，用户唯一的结论是这功能坏了。
        assertTrue(BrowserTrust.note(BrowserTrust.Level.Unknown).contains("自己挑"))
    }

    private companion object {
        const val CHROME = "com.android.chrome"
        const val FIREFOX = "org.mozilla.firefox"
    }
}
