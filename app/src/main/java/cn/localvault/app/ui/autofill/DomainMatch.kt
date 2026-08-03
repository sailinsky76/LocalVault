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

import cn.localvault.app.ui.list.VaultIndex
import java.util.Locale

/**
 * 归属判定：**这一条条目，能不能填给这一组输入框。**
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 *
 * 全工程里再没有第二个函数的错误代价有这么大：判宽了，用户点一下就把密码发给了别人，
 * 而且事后什么痕迹都没有；判窄了，无非是这次得自己去搜索里挑一条。
 * 两种错误差着几个数量级，所以这里的每一条分支都往窄了写，
 * 并且**每一种拒绝都有自己的名字**（[Verdict]），好让 M4-2b 能对着用户说出
 * 「为什么这一条没有自动出现」——而不是让他面对一个空荡荡的填充条。
 */
object DomainMatch {

    /**
     * 判定结果。
     *
     * 只有前两档会被自动建议（[canAutoFill]）。后面四档不是「失败」，
     * 是四种不同的、需要分别向用户交代的处境。
     */
    enum class Verdict(val rank: Int) {

        /** 逐字相同的主机名，或逐字相同的包名。 */
        Exact(5),

        /**
         * 同一个可注册域下的不同子域（`mail.example.com` ↔ `login.example.com`）。
         *
         * 这一档**在界面上必须和 [Exact] 分开显示**，写清「你存的是 A，现在在 B」。
         * 公共后缀表不可能永远全（见 [PublicSuffix] 文件头），
         * 表错一条的后果在这里能被用户一眼看见——这是第二道兜底，不是装饰。
         */
        SameSite(4),

        /**
         * 网站对得上，但**承载这个网页的应用不是已知浏览器**。
         *
         * 这正是 AutoSpill 那条路（见 [Origin] 文件头）。不自动建议；
         * 用户主动搜索着挑的话，M4-2b 必须在按下去之前写明这些内容会交给哪个应用。
         */
        UntrustedHost(3),

        /**
         * 原生应用的输入框，而条目里存的是网址——**没有任何证据说这两者是一家**。
         *
         * 直觉上「微博 App 就该填 weibo.com 的密码」，业界不少管理器也确实这么干。
         * 正规的做法是查 Digital Asset Links（`https://域名/.well-known/assetlinks.json`，
         * 由域名持有者声明哪些应用签名属于自己）——而那要联网，
         * 这个 App 从 M0 起连 `INTERNET` 权限都没有声明，做不到，也不打算为它破例。
         *
         * 于是这里给出的是一句实话：不自动填，但用户可以手动挑，
         * 挑的时候屏幕上会写清「这条存的是网址，你现在在一个应用里，我们没法证明它们是一家」。
         * 那句话是真的——业界那些默认填了的，其实也没证明。
         */
        NoEvidence(2),

        /**
         * 类型对不上：网页输入框 ↔ 条目里存的是安卓包名。
         *
         * 一个网页拿到「某某应用的密码」，正是把原生凭据骗出去的那条路，
         * 所以**绝不自动建议**。
         *
         * 但它不禁止用户手动挑——这条界限想清楚了再画的：
         * 禁止手动等于替用户决定他自己那条数据能去哪儿，而这个应用从头到尾
         * 不做这种事（弱口令给二次确认而不是拒绝、清空库给两道门槛而不是不给）。
         * 手动挑的时候 M4-2b 必须把 [WrongKind] 这句话原样写在按钮上方。
         * 自动建议和手动挑的分界线就在这儿：**自动的那一下用户可能没看清，
         * 手动的那一下他一定看清了。**
         */
        WrongKind(1),

        /** 不相干。绝大多数条目对绝大多数请求都是这一档。 */
        None(0);

        /** 能不能不问自取地出现在填充条上。只有前两档。 */
        val canAutoFill: Boolean get() = this == Exact || this == SameSite

        /** 用户手动挑的时候，按钮上方是不是必须先说一句。 */
        val needsWarning: Boolean
            get() = this == UntrustedHost || this == NoEvidence || this == WrongKind
    }

    /**
     * 单条网址 / 包名的判定。
     *
     * [entryDomain] 是条目 `domains` 里的**一行原文**——用户当初打进去什么就是什么
     * （决策(56)：网址只丢不改写）。归一在这里做，因为归一属于匹配环节。
     * 用的是 `VaultIndex.normalizeDomain`，**不另写一份**——决策㉝ 那句
     * 「不许各写各的」的字面兑现。写第二份的后果是「搜得出来但填不进去」，
     * 或者更糟的反过来。
     */
    fun judge(origin: Origin, entryDomain: String, trust: HostTrust): Verdict {
        val d = VaultIndex.normalizeDomain(entryDomain)
        if (d.isEmpty()) return Verdict.None

        val isPackage = PublicSuffix.looksLikePackage(d)

        return when (origin) {
            is Origin.App ->
                if (isPackage) {
                    // 包名只认逐字相等。**不做「同厂商前缀」**：
                    // `com.tencent.mm` 和 `com.tencent.mobileqq` 是微信和 QQ，
                    // 两个账号体系；认前缀等于把 `com.google.*` 底下所有应用
                    // 当成同一个站，那和「剥子域名」是同一个错误的两种写法。
                    if (d == normalizePackage(origin.hostApp)) Verdict.Exact else Verdict.None
                } else {
                    Verdict.NoEvidence
                }

            is Origin.Web ->
                if (isPackage) {
                    Verdict.WrongKind
                } else {
                    // 两边都过一遍 canonicalHost 再比：条目里可能是用户手打的中文域名，
                    // 而浏览器交上来的一定是 punycode。见 PublicSuffix.canonicalHost。
                    val entryHost = PublicSuffix.canonicalHost(d)
                    val host = PublicSuffix.canonicalHost(VaultIndex.normalizeDomain(origin.host))
                    val site = when {
                        host.isEmpty() -> return Verdict.None
                        entryHost == host -> Verdict.Exact
                        PublicSuffix.sameSite(entryHost, host) -> Verdict.SameSite
                        else -> return Verdict.None
                    }
                    // 站点对得上之后才轮到问「谁在承载它」。顺序反过来也能算出同样的结果，
                    // 但那样每一条不相干的条目都会先去查一次浏览器表，
                    // 而且会让「不是浏览器」这个事实盖住「压根不是同一个站」这个更普通的事实。
                    if (trust.isTrustedBrowser(origin.hostApp)) site else Verdict.UntrustedHost
                }
        }
    }

    /**
     * 一个条目往往存了好几行网址。取其中最好的那一档，并**把命中的那一行带出来**——
     * 界面要如实显示是哪一行对上了（`SameSite` 那一档尤其需要，见 [Verdict.SameSite]）。
     *
     * 一行都没有的条目返回 [Verdict.None]。
     */
    fun best(origin: Origin, entryDomains: List<String>, trust: HostTrust): Hit {
        var best = Hit(Verdict.None, null)
        for (line in entryDomains) {
            val v = judge(origin, line, trust)
            if (v.rank > best.verdict.rank) {
                best = Hit(v, line)
                if (v == Verdict.Exact) break // 没有比逐字相等更好的了
            }
        }
        return best
    }

    /**
     * [best] 的结果。[matched] 是**条目里的原文**，不是归一之后的形式——
     * 屏幕上要显示的是用户自己写下的那一行，让他认得出来。
     */
    class Hit(val verdict: Verdict, val matched: String?) {
        /**
         * 不是 `data class`，`toString` 手写。理由同决策(144)：
         * 这个对象的字段虽然只是网址，但它总是和条目一起被传来传去，
         * 顺手打进日志的那一下会把用户上过哪些站抄进 logcat——
         * 那本身就是一份不该外泄的清单。
         */
        override fun toString(): String = "Hit(${verdict.name})"
    }

    /**
     * 包名规范化：只做 trim + 小写。
     *
     * 安卓包名规范上区分大小写，实际上大写包名极其罕见，而条目那一行经过
     * `normalizeDomain` 已经被转成小写了——不在这里对齐的话，
     * 一个存着 `com.Example.App` 的条目会永远匹配不上，且用户找不到原因。
     */
    private fun normalizePackage(pkg: String): String =
        pkg.trim().lowercase(Locale.ROOT)
}
