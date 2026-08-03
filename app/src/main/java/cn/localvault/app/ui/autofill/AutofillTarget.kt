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

import java.util.Locale

/**
 * 「这一组输入框到底属于谁」—— 自动填充里唯一真正要紧的问题。
 *
 * **整个文件没有一行 `android.*`。** `AssistStructure` 怎么拆成这个东西是 M4-1b 的活，
 * 这里只定义拆出来之后长什么样，以及据此能做什么判断。
 *
 * ── 为什么归属要算在「字段」上，不算在「请求」上 ──
 *
 * AutoSpill（2023）那一类攻击的全部内容就在这一句话里，值得把它写在最显眼的地方。
 *
 * 一个恶意应用可以自己套一个 WebView，在里面显示一张长得和某网站一模一样的登录页。
 * 系统交上来的填充请求里，WebView 那些输入框**确实带着** `webDomain = 那个网站`，
 * 因为它们真的是那个网页里的框。管理器按「这个请求是给 example.com 的」下判断，
 * 于是把 example.com 的密码填了进去——填进的是**恶意应用进程里的 WebView**，
 * 应用自己读得到。用户看到的是自己熟悉的登录页和熟悉的自动填充条，
 * 点一下，密码就交出去了，屏幕上不会有任何异样。
 *
 * 所以这里的模型刻意做成两条：
 *   · [Origin.hostApp] —— **承载这些框的应用包名**。它由系统给出，应用改不了自己的包名，
 *     这是整条链上最硬的一个事实；
 *   · [Origin.Web.host] —— 框自称属于哪个网站。它由页面内容决定，**是一句自称**。
 *
 * 判断永远同时看这两条：网站对得上、而承载它的应用**不是已知浏览器**时，
 * 结论不是「填」也不是「不填」，是 [DomainMatch.Verdict.UntrustedHost]——
 * 不自动建议，用户主动挑的话要在屏幕上写清「这些内容会交给某某应用」。
 *
 * 还有一条同源的规矩，写在 [DomainMatch] 里：**同一次请求里的原生输入框和
 * WebView 输入框必须各算各的归属。** 拿整个请求算一个归属，正是 AutoSpill 走的门。
 */
sealed class Origin {

    /** 承载这些输入框的应用包名。系统给的，不可伪造（同一台设备上不会有两个应用同名）。 */
    abstract val hostApp: String

    /**
     * 原生输入框：归属就是这个应用本身。
     *
     * 能填进来的只有 `domains` 里存着**同一个包名**的条目。存着网址的条目一律不填，
     * 理由见 [DomainMatch.Verdict.NoEvidence]。
     */
    data class App(override val hostApp: String) : Origin() {
        /**
         * `data class` 自动生成的 `toString` 会把 [hostApp] 原样打出来。
         * 单看一个包名不算秘密，但这个对象总是和条目、和 `webDomain` 一起被传来传去，
         * 顺手打进一句日志就等于把「这台手机的主人在什么应用里登录了哪个站」
         * 抄进 logcat——那是一份不该外泄的清单（同决策(144)，
         * 也同 `RawField.toString` 那段注释）。
         *
         * `equals` / `hashCode` 照旧由 `data class` 生成，判定逻辑一点没变。
         */
        override fun toString(): String = "Origin.App"
    }

    /**
     * 网页输入框：框自称属于 [host]，而它跑在 [hostApp] 里。
     *
     * [host] 是主机名，不含协议、端口和路径——从系统那儿拿到的 `webDomain` 通常已经是
     * 这个样子，但 M4-1b 仍然会过一遍 `VaultIndex.normalizeDomain`，
     * 免得哪天某个浏览器交上来一整条 URL。
     */
    data class Web(val host: String, override val hostApp: String) : Origin() {
        /** 理由同 [App.toString]，这一个更要紧：[host] 就是一条访问记录。 */
        override fun toString(): String = "Origin.Web"
    }
}

/**
 * 「承载这些输入框的应用，是不是一个真的浏览器」。
 *
 * 做成接口而不是直接查一张表，理由和 `UnlockGuard` / `VaultRemnants` 一样：
 * 这一层将来要长出 `android.*` 的东西（见下面 [KnownBrowsers] 的告示），
 * 而判断逻辑本身要留在纯 JVM 能跑的地方。
 */
interface HostTrust {
    fun isTrustedBrowser(packageName: String): Boolean
}

/**
 * 按包名认浏览器的默认实现。
 *
 * ── 这张表能挡住什么，挡不住什么 ──
 *
 * 挡得住的：一个随手写的恶意应用套 WebView 假冒登录页。它的包名不在表里，
 * 于是 example.com 的密码不会被自动建议出来。这是 AutoSpill 那条路的主要形态。
 *
 * **挡不住的：包名占位。** 安卓只保证同一台设备上包名唯一，不保证某个包名归谁。
 * 用户手机上没装 Chrome 的话，一个侧载应用完全可以把自己的包名写成
 * `com.android.chrome`，然后堂堂正正地通过这张表。
 *
 * 所以 M4-2 的线上实现**必须再校验签名**（`PackageManager` 取签名证书摘要，
 * 和一张内置的摘要表比对），那一层用得着 `android.*`，正是这个接口存在的原因。
 * 这个默认实现留给单元测试和「签名表里没有这一家」的兜底，
 * 它给出的是**必要条件而不是充分条件**——这句话必须写在这儿，
 * 免得将来有人看见「已经查过浏览器表了」就把签名校验那一步省掉。
 */
object KnownBrowsers : HostTrust {

    /**
     * 常见浏览器包名。国内那几家占了一半——它们是国内用户实际会在上面登录的地方，
     * 少一个的表现是「在这个浏览器里自动填充从来不出现」，用户只会觉得功能坏了。
     */
    val PACKAGES: Set<String> = """
        com.android.browser
        com.android.chrome
        com.chrome.beta com.chrome.dev com.chrome.canary org.chromium.chrome
        org.mozilla.firefox org.mozilla.firefox_beta org.mozilla.fenix org.mozilla.focus
        com.microsoft.emmx
        com.opera.browser com.opera.browser.beta com.opera.mini.native com.opera.gx
        com.brave.browser com.brave.browser_beta
        com.duckduckgo.mobile.android
        com.sec.android.app.sbrowser com.sec.android.app.sbrowser.beta
        com.vivaldi.browser com.kiwibrowser.browser com.yandex.browser
        com.ecosia.android com.qwant.liberty
        com.tencent.mtt
        com.qihoo.browser com.qihoo.contents
        com.baidu.browser.apps com.baidu.searchbox
        sogou.mobile.explorer
        com.UCMobile com.UCMobile.intl com.uc.browser.en
        com.huawei.browser com.hihonor.browser
        com.heytap.browser com.coloros.browser com.oppo.browser
        com.vivo.browser
        com.miui.browser com.mi.globalbrowser
        com.meizu.media.reader
        com.quark.browser
        com.jianguo.browser
        acr.browser.lightning
        """.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toHashSet()

    override fun isTrustedBrowser(packageName: String): Boolean =
        packageName.trim().lowercase(Locale.ROOT) in PACKAGES

    /** 一句给界面用的实话。M4-2b 要在「这些内容会交给谁」那一行旁边说清楚。 */
    const val TRUST_NOTE: String =
        "这一步只按应用包名认浏览器。包名由系统分配、应用自己改不了，" +
            "但如果你的手机上原本就没装某个浏览器，一个仿冒应用是有可能顶着它的包名装进来的。"
}
