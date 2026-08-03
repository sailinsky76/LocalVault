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

import java.net.IDN
import java.util.Locale

/**
 * 公共后缀（eTLD）判定 —— 回答一个问题：**哪两个主机名算同一个站。**
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 和 `VaultIndex` / `UnlockGuard` / `BiometricPolicy` 一个套路。
 *
 * ── 这张表欠了三个模块了 ──
 *
 * 决策㉝ 定下「域名归一只做语法剥离，一个子域名都不剥，`www.` 也不剥」的时候，
 * 给出的理由是：剥子域名这件事**必须靠公共后缀表认真做**，属于 M4。
 * 现在到 M4 了，债在这里还。
 *
 * 不认真做的后果不是「不好用」，是**填错站**：
 * 谁都知道 `mail.google.com` 和 `www.google.com` 是同一家，
 * 于是很容易写出「去掉第一段再比」这种规则——然后 `a.co.uk` 和 `b.co.uk`
 * 就成了同一家，而那是两个毫不相干的英国公司；`user1.github.io` 和
 * `user2.github.io` 更是两个陌生人。一个把 A 的密码建议给 B 的自动填充，
 * 比没有自动填充坏得多：用户点一下就发出去了，而且不会知道发生过。
 *
 * ── 为什么表里只有「多段后缀」 ──
 *
 * 完整的 Public Suffix List 有近万条、两百多 KB，塞进一个以「依赖少、体积小」
 * 为卖点的 App 里不划算（同 [VaultIndex] 拒绝内置拼音表的理由），
 * 而且它每周都在变，内置一份等于内置一份会过期的东西。
 *
 * 但绝大部分条目**不需要内置**：PSL 本身就有一条默认规则——
 * 任何没被列出的单段后缀，它自己就是公共后缀。也就是说 `.com` `.dev` `.xyz`
 * 以及明年才会出现的那些新 gTLD，全都不用写，`example.<随便什么>` 自动得到
 * 正确答案。真正需要列的只有**多段的那些**（`co.uk` / `com.cn` / `github.io`），
 * 它们有几百条量级，而且是长尾——常用的那部分覆盖得到。
 *
 * ── 表不全的时候会怎样，以及为什么这是可以接受的 ──
 *
 * 表里少一条 `co.xy`，`a.co.xy` 和 `b.co.xy` 就会被算成同一个站——**这是会漏密码的方向**，
 * 所以不能光靠「表够全」。三道兜底：
 *
 *   1. **未知的两字母顶级域下，注册局惯用的那批二级域一律当公共后缀**
 *      （[REGISTRY_LIKE]）。所有两字母顶级域都是国家码顶级域（IANA 的规矩），
 *      而多段注册几乎只出现在国家码底下，`com.` `co.` `org.` `ac.` 这些名字
 *      在各国注册局里高度一致。这条规则的偏向是**切得更碎**——
 *      切碎只会少给一条建议，切粗才会把密码递给别人。
 *   2. **兄弟域（[DomainMatch.Verdict.SameSite]）在界面上和精确命中不是一个档**，
 *      M4-2b 会如实写出「这一条存的是 `mail.example.com`，你现在在
 *      `login.example.com`」。判断错了，用户看得见。
 *   3. 表本身按「宁可缺、不可错」维护：拿不准的不往里加。缺一条的代价是
 *      两个真兄弟不互相建议（用户去搜索里手动挑），错一条的代价是漏密码。
 */
object PublicSuffix {

    /* ══════════════════════════ 对外的三个问题 ══════════════════════════ */

    /**
     * 这个主机名的公共后缀是哪一段。`www.bbc.co.uk` → `co.uk`，`mail.qq.com` → `com`。
     *
     * 主机名本身就是公共后缀时返回它自己（`co.uk` → `co.uk`）。
     * IP 字面量、空串返回 null——它们没有后缀这回事。
     */
    fun publicSuffixOf(host: String): String? {
        val labels = labelsOf(host) ?: return null
        val n = suffixLabelCount(labels)
        if (n <= 0 || n > labels.size) return null
        return labels.takeLast(n).joinToString(".")
    }

    /** 主机名本身就是一个公共后缀（`com` / `co.uk` / `github.io`）——它底下没有「同一个站」可言。 */
    fun isPublicSuffix(host: String): Boolean {
        val labels = labelsOf(host) ?: return false
        return suffixLabelCount(labels) == labels.size
    }

    /**
     * 可注册域（eTLD+1）—— 「这个主机名属于谁」的答案。
     *
     * `login.example.co.uk` → `example.co.uk`；`mail.qq.com` → `qq.com`。
     *
     * 以下情况返回 **null**，含义统一是「这个主机名没有兄弟」：
     *   · 空串、含空标签（`a..b`）；
     *   · 单段主机名（`localhost`）；
     *   · IP 字面量——`192.168.1.7` 和 `192.168.1.8` 是两台机器，不是兄弟，
     *     数字上再像也不能沾边；
     *   · 主机名本身就是公共后缀（`co.uk`）——它下面挂的是别人，不是它自己的子域。
     */
    fun registrableDomain(host: String): String? {
        val labels = labelsOf(host) ?: return null
        val n = suffixLabelCount(labels)
        if (n <= 0 || labels.size <= n) return null
        return labels.takeLast(n + 1).joinToString(".")
    }

    /**
     * 两个主机名归同一个可注册域。这就是 [DomainMatch.Verdict.SameSite] 的定义。
     *
     * 注意**算不出可注册域的一律判 false**，不做「那就比字符串吧」的退让：
     * 退让一次，IP 和单段主机名就会从这里漏进兄弟档。逐字相等的情况由
     * [DomainMatch] 里的精确档接住，轮不到这里。
     */
    fun sameSite(a: String, b: String): Boolean {
        val ra = registrableDomain(a) ?: return false
        val rb = registrableDomain(b) ?: return false
        return ra == rb
    }

    /* ══════════════════════════ 主机名还是包名 ══════════════════════════ */

    /**
     * 这串东西是**安卓包名**（`com.tencent.mm`）还是**主机名**（`mail.qq.com`）。
     *
     * 条目的 `domains` 一栏两种都收（M1 的数据模型就是这么定的），
     * 而 M4 必须分得清：把一个包名当主机名，最多是填不进去；
     * 把一个主机名当包名，就可能在原生应用里填出不该填的东西。
     * 所以**拿不准一律判主机名**（返回 false），这是保守的那一边。
     *
     * 判据只有一条能用：包名习惯把顶级域倒着写在最前面，主机名把它写在最后面。
     * 于是：
     *   · 任何一段不合包名规范（不以小写字母开头、含 `a-z0-9_` 以外的字符）→ 主机名。
     *     `163.com` 在这里就出局了——首段以数字开头，安卓包名不允许；
     *   · 首段是顶级域、末段不是 → 包名（`com.whatsapp`、`tv.danmaku.bili`）；
     *   · 末段是顶级域、首段不是 → 主机名（`mail.google.com`）；
     *   · **两头都是顶级域**（`com.tencent.mm` 的 `mm` 是缅甸、`com.cn` 的 `com` 是 gTLD）
     *     → 按段数断：三段及以上算包名，两段算主机名。
     *     真实世界里两段的包名（`com.whatsapp`）末段基本不会撞上顶级域，
     *     而两段且两头都像顶级域的串（`com.cn` / `co.uk` / `cn.com`）几乎只可能是主机名；
     *   · 两头都不是顶级域 → 主机名（保守）。
     */
    fun looksLikePackage(raw: String): Boolean {
        val s = raw.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return false
        val labels = s.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty()) return false
            if (label[0] !in 'a'..'z') return false
            for (c in label) {
                if (c !in 'a'..'z' && c !in '0'..'9' && c != '_') return false
            }
        }
        val firstIsTld = isTopLevel(labels.first())
        val lastIsTld = isTopLevel(labels.last())
        return when {
            firstIsTld && !lastIsTld -> true
            !firstIsTld && lastIsTld -> false
            firstIsTld && lastIsTld -> labels.size >= 3
            else -> false
        }
    }

    /**
     * 这一段看起来是个顶级域。
     *
     * 两字母的一律算——IANA 把两字母顶级域整段留给了国家码，不会有别的东西进来，
     * 于是这一条规则一次覆盖两百多个后缀，也不会过期。
     * 三字母及以上只认 [KNOWN_GTLDS] 那张短表；认不出来算「不是」，
     * 于是走到 [looksLikePackage] 最后那条保守分支。
     *
     * **两字母还得都是字母。** 顶级域里从来没有数字（IDN 的 `xn--` 也长得多），
     * 而 `u1.github.io` / `s3.example.com` 这种首段是「字母+数字」的主机名满地都是。
     * 少了这一句，`u1` 会被当成国家码，于是 `u1.github.io` 首尾都像顶级域、
     * 又有三段，[looksLikePackage] 就判它是**包名**——一个主机名被当成包名，
     * 正是这个文件反复说的不该走的那一边：它会让条目在网页上永远填不进去
     * （判成 [DomainMatch.Verdict.WrongKind]），更坏的是一个包名叫 `u1.github.io`
     * 的应用会拿到 [DomainMatch.Verdict.Exact]。
     */
    private fun isTopLevel(label: String): Boolean =
        (label.length == 2 && label.all { it in 'a'..'z' }) || label in KNOWN_GTLDS

    /**
     * 可比对形式：小写、去首尾点、国际化域名转 punycode。
     *
     * **判相等之前必须两边都过一遍这个函数。** 用户手打的是 `例子.中国`，
     * 浏览器交上来的是 `xn--fsqu00a.xn--fiqs8s`，直接比字符串永远不等——
     * 而更坏的是它们**不是不匹配**：两者的可注册域算出来是一样的，
     * 于是会掉进兄弟档，界面上就会出现「你存的是 A，你现在在 B」这种
     * 把同一个域名说成两个的怪话。
     */
    fun canonicalHost(raw: String): String = ascii(raw)

    /** IP 字面量。IPv6 走 `[...]`（`VaultIndex.normalizeDomain` 会原样留下方括号）或裸冒号形式。 */
    fun isIpLiteral(host: String): Boolean {
        if (host.startsWith("[")) return true
        if (host.contains(':')) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } && p.toInt() <= 255
        }
    }

    /* ══════════════════════════ 内部 ══════════════════════════ */

    /**
     * 主机名 → 标签数组。不合格的返回 null。
     *
     * 这里做一件 `VaultIndex.normalizeDomain` **刻意没做**的事：把国际化域名转成
     * punycode（`例子.中国` → `xn--fsqu00a.xn--fiqs8s`）。这不违背决策㉝——
     * ㉝ 管的是**存储和搜索**那一侧（用户打进去什么就存什么、屏幕上显示什么就搜什么），
     * 而决策(56) 已经把「归一属于匹配环节」画清楚了，这里正是匹配环节。
     * 不转的话，浏览器交上来的 `xn--` 形式和用户手打的中文形式永远对不上，
     * 表现是「中文域名的条目从不出现在自动填充里」，而用户找不到原因。
     *
     * 转不动就原样退回：[IDN.toASCII] 对带下划线、超长标签之类的输入会抛
     * `IllegalArgumentException`，那种串本来也匹配不上什么，让它按原样走完流程即可。
     */
    private fun labelsOf(host: String): List<String>? {
        val h = ascii(host)
        if (h.isEmpty()) return null
        if (isIpLiteral(h)) return null
        val labels = h.split('.')
        if (labels.any { it.isEmpty() }) return null
        return labels
    }

    private fun ascii(host: String): String {
        val s = host.trim().trim('.').lowercase(Locale.ROOT)
        if (s.isEmpty() || s.all { it.code < 128 }) return s
        return try {
            IDN.toASCII(s, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        } catch (e: IllegalArgumentException) {
            s
        }
    }

    /**
     * 公共后缀占几段。返回 0 表示算不出（调用方一律当「没有兄弟」处理）。
     *
     * 规则的优先级抄 PSL 的规矩：**例外规则压过一切**，其次取最长匹配，
     * 最后落到默认规则（末段本身就是公共后缀）。
     */
    private fun suffixLabelCount(labels: List<String>): Int {
        val n = labels.size
        if (n == 0) return 0

        // 例外规则（PSL 里带 `!` 的那些）。它把一段还给用户：`!www.ck` 意味着
        // `www.ck` 是可注册域而不是公共后缀。例外必须先查，PSL 明文规定它压过通配。
        for (i in 0 until n) {
            if (labels.subList(i, n).joinToString(".") in EXCEPTIONS) return n - i - 1
        }

        // 默认规则：任何未列出的单段后缀，它自己就是公共后缀。
        // 这一条覆盖掉整个 gTLD 世界（含还没出生的那些），所以表里一条单段的都不用写。
        var best = 1

        for (i in 0 until n - 1) {
            val len = n - i
            if (len <= best) break // i 递增 = len 递减，后面不可能更长了
            val candidate = labels.subList(i, n).joinToString(".")
            if (candidate in MULTI_LABEL) {
                best = len
                break
            }
            // 通配规则 `*.X`：X 底下任意一段都是公共后缀，但**只吃一段**。
            if (labels.subList(i + 1, n).joinToString(".") in WILDCARD) {
                best = len
                break
            }
        }

        // 兜底：未知的两字母顶级域 + 注册局惯用二级域。见文件头「表不全的时候会怎样」。
        if (best < 2 && n >= 2 && labels[n - 1].length == 2 && labels[n - 2] in REGISTRY_LIKE) {
            best = 2
        }
        return best
    }

    /* ══════════════════════════ 表 ══════════════════════════ */

    /**
     * 表写成一大段空白分隔的文本再切开，不写成几百个 `"x", ` ——
     * 一是好读好改（一眼看得出哪个国家覆盖到了哪些），
     * 二是往里加一条不会因为漏个逗号而编译不过。
     * 切分只在类加载时做一次。
     */
    private fun table(text: String): Set<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toHashSet()

    /**
     * 多段公共后缀。**只列多段的**，单段的全交给默认规则。
     *
     * 排列顺序按国家/地区，方便对照着补。中国大陆那一段把省级二级域全列了：
     * `bj.cn` / `sh.cn` 这些至今在用，漏掉的话 `a.bj.cn` 和 `b.bj.cn` 会被算成兄弟。
     *
     * 最后一段是**私有后缀**——托管平台把子域分给互不相识的用户，
     * 性质和国家注册局完全一样。`user1.github.io` 和 `user2.github.io`
     * 是两个陌生人，这一段少一条就是实打实的漏密码。
     */
    private val MULTI_LABEL: Set<String> = table(
        """
        co.uk org.uk me.uk ltd.uk plc.uk net.uk sch.uk ac.uk gov.uk nhs.uk police.uk mod.uk

        com.cn net.cn org.cn gov.cn edu.cn ac.cn mil.cn
        ah.cn bj.cn cq.cn fj.cn gd.cn gs.cn gz.cn gx.cn ha.cn hb.cn he.cn hi.cn hk.cn hl.cn
        hn.cn jl.cn js.cn jx.cn ln.cn mo.cn nm.cn nx.cn qh.cn sc.cn sd.cn sh.cn sn.cn sx.cn
        tj.cn tw.cn xj.cn xz.cn yn.cn zj.cn

        com.hk org.hk net.hk edu.hk gov.hk idv.hk
        com.mo org.mo net.mo edu.mo gov.mo
        com.tw org.tw net.tw edu.tw gov.tw idv.tw game.tw ebiz.tw club.tw

        co.jp ne.jp or.jp ac.jp ad.jp ed.jp go.jp gr.jp lg.jp
        co.kr ne.kr or.kr re.kr pe.kr go.kr mil.kr ac.kr hs.kr ms.kr es.kr sc.kr kg.kr

        com.au net.au org.au edu.au gov.au asn.au id.au
        co.nz net.nz org.nz govt.nz ac.nz school.nz geek.nz kiwi.nz maori.nz health.nz

        com.sg net.sg org.sg edu.sg gov.sg per.sg
        com.my net.my org.my gov.my edu.my mil.my name.my
        co.id or.id ac.id go.id net.id web.id sch.id my.id biz.id desa.id
        co.th in.th ac.th go.th mi.th or.th net.th
        com.vn net.vn org.vn edu.vn gov.vn int.vn ac.vn biz.vn info.vn name.vn pro.vn health.vn
        com.ph net.ph org.ph gov.ph edu.ph ngo.ph
        com.pk net.pk edu.pk org.pk gov.pk
        co.in net.in org.in gen.in firm.in ind.in ac.in edu.in gov.in mil.in res.in
        co.il org.il net.il ac.il gov.il k12.il muni.il idf.il
        com.sa net.sa org.sa gov.sa edu.sa sch.sa med.sa pub.sa
        co.ae net.ae org.ae ac.ae gov.ae sch.ae mil.ae
        com.tr net.tr org.tr gen.tr biz.tr info.tr av.tr edu.tr gov.tr k12.tr

        com.br net.br org.br gov.br edu.br art.br blog.br
        com.ar net.ar org.ar gob.ar edu.ar int.ar mil.ar tur.ar
        com.mx org.mx net.mx edu.mx gob.mx
        com.co net.co org.co edu.co gov.co
        com.pe net.pe org.pe edu.pe gob.pe
        com.ve net.ve org.ve edu.ve gob.ve
        com.uy net.uy org.uy edu.uy gub.uy
        com.ec net.ec org.ec edu.ec gob.ec

        com.ru net.ru org.ru pp.ru msk.ru spb.ru
        com.ua net.ua org.ua in.ua kiev.ua
        com.pl net.pl org.pl edu.pl gov.pl info.pl waw.pl
        com.es org.es nom.es gob.es edu.es
        com.pt edu.pt gov.pt org.pt
        com.gr net.gr org.gr edu.gr gov.gr
        gov.it edu.it
        asso.fr com.fr gouv.fr nom.fr prd.fr tm.fr
        co.at or.at ac.at gv.at
        co.hu
        com.cy net.cy org.cy ac.cy gov.cy

        co.za org.za net.za web.za gov.za ac.za edu.za
        com.ng org.ng gov.ng edu.ng net.ng
        co.ke or.ke ne.ke go.ke ac.ke sc.ke me.ke info.ke
        com.eg edu.eg gov.eg net.eg org.eg sci.eg
        co.ma net.ma org.ma gov.ma ac.ma

        gc.ca ab.ca bc.ca mb.ca nb.ca nl.ca ns.ca nt.ca nu.ca on.ca pe.ca qc.ca sk.ca yk.ca

        github.io gitlab.io netlify.app vercel.app pages.dev workers.dev web.app
        firebaseapp.com appspot.com herokuapp.com azurewebsites.net cloudfront.net
        glitch.me repl.co blogspot.com myshopify.com github.dev codeberg.page
        """
    )

    /**
     * 通配后缀 `*.X`：X 底下的任意一段都是公共后缀（只吃一段）。
     * 这几个国家的注册局没有公开的二级域清单，PSL 里也是这么写的。
     */
    private val WILDCARD: Set<String> = table("bd ck er jm kh mm np pg")

    /** 例外 `!X`：从通配里挖回来的那几个。PSL 里目前只有 `!www.ck` 这一类。 */
    private val EXCEPTIONS: Set<String> = table("www.ck")

    /**
     * 注册局惯用的二级域名字。只在**未知的两字母顶级域**下当兜底用（见 [suffixLabelCount]）。
     * 这张表宁可长一点：多列一个只会把某个站切得更碎（少一条建议），
     * 少列一个才会把两个陌生人算成兄弟。
     */
    private val REGISTRY_LIKE: Set<String> = table(
        """
        com net org edu gov mil int ac co go ne or re pe in id ad ed gr lg
        sch nom gob gub gv gouv govt idv asn plc ltd biz info web pub res
        k12 hs ms es sc me tv pp priv name firm gen ind mi muni
        """
    )

    /**
     * 三字母及以上的常见顶级域。**只给 [looksLikePackage] 用**，不参与公共后缀计算
     * （那边有默认规则兜着，认不认得出都不影响结果）。
     *
     * 认不全没关系：认不出的顶级域会让 [looksLikePackage] 落到最后那条保守分支，
     * 结论是「主机名」——正是安全的那一边。
     */
    private val KNOWN_GTLDS: Set<String> = table(
        """
        com net org info biz name pro mobi asia tel travel jobs museum aero coop cat int
        edu gov mil arpa post xxx
        app dev page shop site online store cloud tech xyz top club vip live fun art blog
        wiki design email news media video game games music movie photo pics link click
        space website press today world life love work team group company center city
        global one now plus run studio agency digital network systems solutions services
        tools zone cool icu ltd inc llc gmbh
        """
    )
}
