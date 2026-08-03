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

package cn.localvault.app.ui.apps

import java.text.Collator
import java.util.Locale

/**
 * 应用选择器背后的规则：**排序、搜索、分组。**
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 「怎么从 `PackageManager` 把名单取出来」在 [InstalledAppCatalog] 里，
 * 那一层薄到几乎没有判断——同 `AndroidHostTrust` / `BrowserTrust` 那条缝。
 *
 * ── 这份名单从哪儿来，又到哪儿去 ──
 *
 * 来自清单里那条 `<queries>`（只看得见**有启动图标的应用**，见 AndroidManifest 的注释）。
 * 它**只活在选择器打开的那一段时间里**：不落盘、不进保险库、不写日志。
 * 进程连 `INTERNET` 权限都没有声明，这份名单在技术上也出不去。
 * 唯一会被记下来的是用户亲手勾中的那几个包名，它们和他手打的网址一样，
 * 是他自己那条数据的一部分。
 */
object AppPicker {

    /**
     * 名单上的一项。
     *
     * [label] 是**现查出来的**应用名（用户选了「不存进库」那一版）。
     * 它不进保险库：库里只有 [packageName]，因为匹配只认包名
     * （[cn.localvault.app.ui.autofill.DomainMatch.judge] 里那段「包名只认逐字相等」）。
     * 换一台手机、或者应用被卸载之后，界面上退回显示包名——那是实话，
     * 比显示一个可能早就改过名的旧标题诚实。
     *
     * `toString` 手写：这个对象携带的是「这台手机上装了什么」，
     * 顺手打进日志的那一下就是一份设备指纹。同 [cn.localvault.app.ui.edit.DomainTargets.Target]。
     */
    data class App(
        val packageName: String,
        val label: String,
        /** 系统自带（`FLAG_SYSTEM`）。只影响排序，不影响能不能选。 */
        val system: Boolean,
    ) {
        override fun toString(): String = "App(system=$system)"
    }

    /* ══════════════════════════ 排序 ══════════════════════════ */

    /**
     * 中文按拼音排，不按码点排——理由和 [cn.localvault.app.ui.list.VaultIndex] 里
     * 那段一模一样：`String.compareTo` 排的是 Unicode 码点，
     * 于是「百度」会排在「爱奇艺」前面，用户完全说不出这份名单按什么排的。
     *
     * `Collator` 不是线程安全的，所以放 ThreadLocal——同上。
     */
    private val COLLATOR: ThreadLocal<Collator> =
        ThreadLocal.withInitial { Collator.getInstance(Locale.CHINA) }

    /**
     * **用户自己装的在前，系统自带的在后**，各自按名称排。
     *
     * 这一刀值得切：用户来这儿是为了给某个账号挑应用，而需要存密码的几乎全是
     * 他自己装的那些。系统自带里带启动图标的是相机、时钟、文件管理——
     * 一年也轮不到一次，却会均匀地散在拼音序里，把真正要找的那些顶下去半屏。
     *
     * 但**不隐藏**系统应用：手机厂商的账号、运营商营业厅这类确实要存密码的东西
     * 就躺在系统分区里，藏掉它们会让一部分用户彻底找不到自己要的那一个，
     * 而且找不到的时候屏幕上不会有任何地方解释为什么。
     */
    fun order(apps: List<App>): List<App> {
        val c = COLLATOR.get()!!
        return apps.sortedWith(
            compareBy<App> { it.system }
                .thenComparator { a, b -> c.compare(a.label, b.label) }
                .thenBy { it.packageName },
        )
    }

    /* ══════════════════════════ 搜索 ══════════════════════════ */

    /**
     * 应用名或包名里包含这几个字。
     *
     * ── 刻意不做拼音首字母 ──
     *
     * 打「wx」搜出微信当然好用，但那要一张几千字的拼音表，
     * 而这个 App 的卖点之一就是依赖少、体积小（同 [cn.localvault.app.ui.list.VaultIndex]
     * 拒绝 A–Z 索引条那段）。中文用户直接打「微」就够了——
     * 这份名单撑死一两百项，两个字足以筛到个位数。
     *
     * ── 包名也参与搜索 ──
     *
     * 知道包名的用户是少数，但他们打的一定是包名。让他们打得进去，
     * 比逼他们去翻拼音强。
     */
    fun matches(app: App, query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        return app.label.lowercase(Locale.ROOT).contains(q) ||
            app.packageName.lowercase(Locale.ROOT).contains(q)
    }

    fun filter(apps: List<App>, query: String): List<App> =
        if (query.isBlank()) apps else apps.filter { matches(it, query) }

    /* ══════════════════════════ 分段 ══════════════════════════ */

    data class Section(val title: String?, val apps: List<App>)

    const val SYSTEM_TITLE: String = "系统自带"

    /**
     * 切成「（无标题的那一段）+ 系统自带」。
     *
     * 用户自己装的那一段**故意不给标题**：它是默认的、绝大多数情况下唯一要看的那一段，
     * 给它安一个「已安装的应用」的抬头，等于在名单顶上先占掉一行说废话。
     * 有标题的那一段是例外的那一段，标题在这里的作用是**解释为什么这些排在后面**。
     *
     * 搜索之后仍然分段：搜「相机」既可能命中系统相机也可能命中第三方相机，
     * 而这两者对用户完全不是一回事。
     */
    fun sections(apps: List<App>): List<Section> {
        val ordered = order(apps)
        val mine = ordered.filter { !it.system }
        val sys = ordered.filter { it.system }
        return buildList {
            if (mine.isNotEmpty()) add(Section(null, mine))
            if (sys.isNotEmpty()) add(Section(SYSTEM_TITLE, sys))
        }
    }

    /* ══════════════════════════ 界面上的几句话 ══════════════════════════ */

    /** 名单读不出来时（`<queries>` 被改掉、或者厂商 ROM 拦了）那句话。 */
    const val UNAVAILABLE: String = "读不到应用列表。可以先用「添加网址」，或到手动编辑里自己填包名。"

    /** 搜了但一个都没命中。 */
    fun emptyResult(query: String): String = "没有名称或包名含「${query.trim()}」的应用。"

    /**
     * 底下那句话。说的是**这份名单去哪儿了**。
     *
     * 一个密码管理器打开你的应用列表，是个值得解释一句的动作。
     * 不解释的话，稍微警觉一点的用户下一步就是去应用商店的评论区问。
     */
    const val PRIVACY_NOTE: String = "列表只在这一屏读取，不会存进保险库，也没有网络权限把它传出去。"
}
