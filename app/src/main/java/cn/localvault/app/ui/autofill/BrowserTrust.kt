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

/**
 * 「顶着这个包名的，是不是真的那个浏览器」。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 从 `PackageManager` 把签名证书摘要取出来是 [AndroidHostTrust] 的事，
 * 这里只回答：**拿到这几个摘要之后，该给它哪一档信任。**
 *
 * ── 这是决策(164) 欠下的那一步 ──
 *
 * [KnownBrowsers] 那张包名表挡得住「一个随手写的恶意应用套 WebView 假冒登录页」，
 * 挡不住**包名占位**：安卓只保证同一台设备上包名唯一，不保证某个包名归谁。
 * 用户手机上没装 Chrome 的话，一个侧载应用完全可以把自己叫做 `com.android.chrome`，
 * 然后堂堂正正地通过那张表——而 `DomainMatch` 会因此把
 * [DomainMatch.Verdict.UntrustedHost] 降格成 `Exact`，密码就自动出现在它的填充条上。
 * 那张表的文档里写死了「它给的是必要条件，不是充分条件」，这个文件就是那句话的下半截。
 *
 * ── 三档，而不是两档 ──
 *
 * 直觉上这应该是个是非题。做成三档（[Level]）是因为**内置的摘要表注定是不全的**：
 * 世上的浏览器一直在增加，各家还会换签名，而这个应用没有网络权限，
 * 没法像别人那样在线拉一份名单下来。两档的话只有两种写法，两种都是坏的：
 *   · 表里没有就当不可信 → 表里少一家，那个浏览器上**从此再也不出填充条**，
 *     而用户只会觉得这功能坏了（而且他没有任何办法查出原因）；
 *   · 表里没有就当可信 → 那这一层等于没做。
 * 所以第三档 [Level.PackageOnly] 说的是实话：**这一家我们只核对了包名。**
 * 它照样自动建议（否则功能就废了），但界面上那句话不一样（见 [note]）。
 *
 * 真正被这一层挡下的是**第三种情况**：表里有这一家的摘要、而装在这台设备上的
 * 那个包**签名对不上**。那不是「我们不认识它」，那是「它不是它自称的那个」——
 * 一个正常用户的手机上永远不会出现这种情况。
 */
object BrowserTrust {

    /** 三档信任。 */
    enum class Level {
        /** 包名在表里，签名摘要也对上了内置表。 */
        Verified,

        /** 包名在表里，但内置表里没有这一家的摘要——**只核对了包名**。 */
        PackageOnly,

        /**
         * 不认识，或者签名对不上。
         *
         * 两件事合成一档是有意的：对用户来说它们是同一句话
         * （「承载这个网页的应用不是我们认得的浏览器」），
         * 而分开说的那一句——「这个应用冒充了某某浏览器」——
         * 我们其实没有证据说得那么重（用户自己编译了一版 Firefox 也会落到这儿）。
         */
        Unknown;

        /** 够不够格让 [DomainMatch] 把网页判定当作正经浏览器里的判定。 */
        val trusted: Boolean get() = this == Verified || this == PackageOnly
    }

    /**
     * 内置的签名证书摘要表：包名 → 这一家已知的证书 SHA-256（小写十六进制，无分隔符）。
     *
     * ── 它现在是空的，这是有意的，不是漏了 ──
     *
     * 摘要必须**从官方渠道的 APK 上亲手算出来**才能往里加。编一个假的进去比空着糟得多：
     * 填错一条的后果是那个浏览器从此判成 [Level.Unknown]，
     * 用户在最常用的浏览器里再也见不到填充条，而没有任何一处会告诉他为什么。
     * 空着的后果只是所有浏览器都停在 [Level.PackageOnly]——
     * 也就是 M4-2a-2 之前的水平，一步没退。
     *
     * **怎么算：**
     *   · 电脑上：`apksigner verify --print-certs app.apk`，取 `SHA-256 digest`；
     *     或 `keytool -printcert -jarfile app.apk`。
     *   · 设备上：`PackageManager` 取 `signingInfo.apkContentsSigners`，
     *     对每个 `Signature.toByteArray()` 求 SHA-256（[AndroidHostTrust] 就是这么做的）。
     *     注意**要在一台干净的设备上算**——在被仿冒过的设备上算出来的正好是仿冒者的摘要。
     *
     * **怎么加：** 同 [PublicSuffix] 那条「宁可缺、不可错」。一家可以有多个摘要
     * （签名轮换、渠道包），命中任意一个即可（见 [decide]）。
     * 加进来的每一条最好在提交信息里写清是从哪一个版本、哪个渠道的包上算的。
     */
    val FINGERPRINTS: Map<String, Set<String>> = emptyMap()

    /**
     * 把一个摘要归一成「小写、无分隔符的 64 位十六进制」。
     *
     * 各处工具吐出来的样子都不一样：`apksigner` 给的是连续小写十六进制，
     * `keytool` 给的是 `AB:CD:EF:…` 大写带冒号，有人还会顺手粘上空格和换行。
     * 归一不做的话，表里那条和算出来那条永远对不上，
     * 而表现是「签名校验一直失败」——最难查的那种一致失败。
     *
     * 认不出来的返回空串（**不是抛异常**，也不是原样返回）：
     * 空串既不会等于任何一个真摘要，也不会被 [decide] 当成有效期望值。
     */
    fun normalizeDigest(raw: String): String {
        val cleaned = buildString(raw.length) {
            for (c in raw) {
                when {
                    c.isWhitespace() || c == ':' || c == '-' -> Unit
                    else -> append(c.lowercaseChar())
                }
            }
        }
        if (cleaned.length != SHA256_HEX_LENGTH) return ""
        for (c in cleaned) {
            val ok = (c in '0'..'9') || (c in 'a'..'f')
            if (!ok) return ""
        }
        return cleaned
    }

    /**
     * 判档。
     *
     * @param packageName 承载这些输入框的应用包名（系统给的，见决策(158)）。
     * @param actual 这个包实际的签名证书摘要；**读不到时传 null**（不是空集合）。
     *
     * 几条值得写下来的：
     *
     * **读不到签名 → [Level.Unknown]。** 读不到的正常成因几乎没有
     * （查自己设备上一个已安装包的签名是不需要权限的），
     * 所以这一档宁可判严：这里两个方向的错误代价还是差着数量级
     * （多问一句 vs. 把密码交给一个冒名的应用）。
     *
     * **命中任意一个摘要就算过，不要求全等。** 一个包可能有签名轮换历史，
     * 也可能一个渠道包多签了一份；要求「实际的那几个必须全在表里」，
     * 会在轮换发生的那天把一家正版浏览器判成冒充。
     * 反过来「命中一个就够」并不放松安全：**私钥不在手上就签不出那个签名**，
     * 一个仿冒 APK 没法让自己带上别人的证书。
     *
     * **表里有、但对不上 → [Level.Unknown]，而不是退回 [Level.PackageOnly]。**
     * 退回去等于这张表白建：包名占位那条路会原样通到底。
     */
    fun decide(packageName: String, actual: Set<String>?): Level =
        decide(packageName, actual, FINGERPRINTS)

    /**
     * 同上，但摘要表由调用方给。
     *
     * 它是 `internal` 的，只为用例存在：[FINGERPRINTS] 现在是空的、将来会变，
     * 而上面那几条规则**不该跟着表一起变**。用例注入自己的一张小表，
     * 于是「对不上就拒」「命中一个就够」这些规则在表被填满的那天照样钉着。
     */
    internal fun decide(
        packageName: String,
        actual: Set<String>?,
        fingerprints: Map<String, Set<String>>,
    ): Level {
        val pkg = packageName.trim().lowercase()
        if (!KnownBrowsers.isTrustedBrowser(pkg)) return Level.Unknown

        val expected = fingerprints[pkg]
            ?.mapNotNull { normalizeDigest(it).ifEmpty { null } }
            ?.toSet()
            .orEmpty()
        if (expected.isEmpty()) return Level.PackageOnly

        if (actual == null) return Level.Unknown
        val got = actual.mapNotNull { normalizeDigest(it).ifEmpty { null } }
        return if (got.any { it in expected }) Level.Verified else Level.Unknown
    }

    /* ══════════════════════════ 话怎么说 ══════════════════════════ */

    /**
     * 每一档对应的那句话。M4-2b 要把它写在「这些内容会交给 ⟨应用名⟩」那一行旁边。
     *
     * [Level.Verified] 那一句**刻意不说「已验证」「安全」**：
     * 我们核对的只是「这个包是它自称的那个包」，而不是「这个页面不是钓鱼网站」。
     * 一句听起来像背书的话，会让用户在真该停下来看一眼的时候放心地点下去。
     */
    fun note(level: Level): String = when (level) {
        Level.Verified ->
            "这个浏览器的签名和内置的一份记录对得上——它确实是它自称的那个应用。" +
                "这一句不保证你现在打开的页面是真的。"

        Level.PackageOnly -> KnownBrowsers.TRUST_NOTE

        Level.Unknown ->
            "承载这个网页的应用不是已知的浏览器，所以这个网站的条目不会自动出现。" +
                "你仍然可以自己挑一条——挑之前请确认你认得这个应用。"
    }

    private const val SHA256_HEX_LENGTH = 64
}
