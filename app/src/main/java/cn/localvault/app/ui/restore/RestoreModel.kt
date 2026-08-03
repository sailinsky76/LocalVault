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

package cn.localvault.app.ui.restore

import cn.localvault.app.core.crypto.KdfParams
import cn.localvault.app.core.vault.VaultFile
import cn.localvault.app.core.vault.VaultFormatException
import cn.localvault.app.core.vault.VaultNotRecognizedException
import cn.localvault.app.core.vault.VaultTooNewException
import cn.localvault.app.ui.util.Fmt

/**
 * 「从备份恢复」这一页的内核：认文件、说事实、拦提交、写失败文案。
 *
 * **没有一行 `android.*`，也没有一行 Compose。** 页面在 M5-1b。
 *
 * ───────────── 这一页在整个 App 里的位置 ─────────────
 *
 * 它是清空重来（M3-6c-3）那一页指着的那条出路。那一页对用户说的是
 * 「你导出到别处的备份不受影响，清空之后就是拿它们回来的时候」，
 * 而在这一步之前，「拿回来」是一张占位屏（决策(132) 因此立了一条发版顺序的硬约束）。
 * 也就是说：**这一页兑现的是整个产品对「换机 / 忘记主密码 / 手机丢了」这三件事的全部答复。**
 * 导出侧写得再仔细（三道校验，决策⑱），导入侧接不上，那三道校验就一件事都没保住。
 *
 * ───────────── 这一页只能知道这么多 ─────────────
 *
 * `.lvault` 的文件头是明文的（魔数、格式版本、KDF 档位、盐），条目全在密文里。
 * 所以在用户输主密码之前，这一页能如实说出「多大、什么格式、什么加密参数」，
 * **但一条也数不出来**。这不是功能没做完，这正是它该有的样子——
 * 一个不输口令就能告诉你「这份备份里有 37 条」的密码管理器，
 * 等于把库内容的一个投影摆在了任何拿到文件的人面前（同决策㉖对解锁页的要求）。
 * 所以 [WHY_NO_COUNT] 要主动把这件事说出来：用户在删除页上见过一整屏事实清单
 * （`DeleteVaultModel.facts` 有条目数），这里突然只剩四行，不解释会像是没做完。
 */
object RestoreModel {

    // ───────────────────── 认文件 ─────────────────────

    /**
     * 对用户选中的那个文件的判断。**只看文件内容的前几十个字节，不看扩展名**——
     * 决策㉒：系统文件选择器会按 MIME 类型改写扩展名，某些 ROM 会把
     * `.lvault` 变成 `.lvault.bin`；认扩展名的话，用户重命名一次就再也恢复不了了。
     */
    sealed interface Probe {
        /** 是本应用的保险库文件，文件头读得出来。能不能打开还得看主密码。 */
        data class Recognized(
            val fileName: String,
            val sizeBytes: Long,
            val formatVersion: Int,
            val kdfParams: KdfParams,
        ) : Probe

        /** 压根不是保险库文件——最常见的一次失误：在文件选择器里点错了。 */
        data class NotVaultFile(val fileName: String) : Probe

        /** 是我们的文件，但比这个版本新。 */
        data class TooNew(val fileName: String, val formatVersion: Int) : Probe

        /** 是我们的文件，但文件头已经坏了。 */
        data class Damaged(val fileName: String) : Probe
    }

    /**
     * 认一份候选文件。**不需要主密码，也绝不碰密文**。
     *
     * 三种失败分开报，是因为三句话的下一步完全不同：
     * 选错文件 → 换个文件；文件坏了 → 换一份备份；版本太新 → 升级应用。
     * 混成一句「文件无法识别」，用户唯一能做的就是把三件事挨个试一遍。
     */
    fun probe(fileName: String, bytes: ByteArray): Probe =
        try {
            val h = VaultFile.parseHeader(bytes)
            Probe.Recognized(fileName, bytes.size.toLong(), h.formatVersion, h.kdfParams)
        } catch (e: VaultTooNewException) {
            Probe.TooNew(fileName, e.fileFormatVersion)
        } catch (e: VaultNotRecognizedException) {
            Probe.NotVaultFile(fileName)
        } catch (e: VaultFormatException) {
            Probe.Damaged(fileName)
        }

    // ───────────────────── 事实 ─────────────────────

    data class Fact(val label: String, val value: String)

    /**
     * 文件头里读得出来的东西，一条不多一条不少。
     *
     * 加密参数这一行值得单独说一句：它是用户手上这份文件**当年那台设备**定下的档位
     * （决策(115)）。恢复完之后顶部封条显示的就是这一行——如果它比本机新建库的档位低，
     * 那不是 bug，是这份文件本来的样子；想提上来的办法是改一次主密码。
     */
    fun facts(p: Probe.Recognized): List<Fact> = listOf(
        Fact("文件", p.fileName),
        Fact("大小", Fmt.bytes(p.sizeBytes)),
        Fact("格式", "v${p.formatVersion}"),
        Fact("加密参数", Fmt.kdfLabel(p.kdfParams)),
    )

    const val WHY_NO_COUNT: String =
        "这里说不出这份备份里有多少条——文件头是明文的（上面那几行就是从它读出来的），" +
            "条目本身是密文，不输主密码谁也数不出来。这正是它该有的样子。"

    /* ══════════ 短版（v3：把输入框和按钮还给这一页） ══════════ */

    /*
     * 初版这一页从上到下是：一段引言、一段「还没选文件」、四行事实、
     * 一段「为什么数不出条目数」、四条「恢复之后会怎样」、密码框、
     * 两段密码说明、按钮。在常见机型上，**密码框和「恢复到这台设备」都在第一屏之外**。
     *
     * 这一页的用户画像偏偏是最不该遇到这个的那种：刚换了机、或者刚清空重来，
     * 手上这份文件是他最后一根绳子。他进来第一眼该看到的是「选文件」和「填密码」，
     * 而不是一屏需要读完才敢往下滚的字。
     *
     * 所以：平铺的一律换成下面这些短版（两三行封顶），完整那几段一字不删地
     * 收进「详细说明」弹窗（`components/Explain.kt`）。信息一条没少，
     * 第一屏从「一段引言」变成「引言 + 选文件 + 密码框 + 按钮」。
     */

    /** 页顶那句的短版。三件事：选什么、用什么打开、不联网。 */
    const val INTRO_SHORT: String =
        "选一个导出时生成的 .lvault 文件，用它当年的主密码打开。全程不联网。"

    /** 页顶完整那两段。原来平铺在页顶的那一段，加上原来那句「本应用没有存储权限」。 */
    val INTRO_DETAIL: List<String> = listOf(
        "选一个导出时生成的 .lvault 文件，用它当年的主密码打开，" +
            "这台设备上就会出现一个和它一模一样的保险库。整个过程不联网、不经过任何服务器。",
        "选文件走的是系统的文件选择器。本应用没有存储权限，" +
            "只拿得到你亲手挑中的那一个文件，别的什么都看不见。",
    )

    const val INTRO_DETAIL_TITLE: String = "从备份恢复是怎么回事"

    /** 还没选文件时那一句。原来那段里「没有存储权限」的部分挪进了 [INTRO_DETAIL]。 */
    const val NO_FILE_SHORT: String = "还没有选择文件。"

    /** 事实卡下面那句的短版。完整那段（[WHY_NO_COUNT]）在链接后面。 */
    const val WHY_NO_COUNT_SHORT: String = "条目数这里数不出来——不输主密码谁也数不出来。"

    // ───────────────────── 实话 ─────────────────────

    /**
     * 恢复之后会怎样。第三条是这一页**唯一一件用户不知道、又一定会撞上**的事：
     * 指纹和 PIN 不会跟着文件过来。
     *
     * 它们包的确实是同一把库主密钥，但那份包裹外面还有一层 Keystore 的设备绑定密钥
     * （决策⑥），而那把钥匙生在原来那台手机的安全芯片里，拷不出来也拿不走。
     * 不提前说清楚的话，用户会在恢复完之后发现指纹解锁不见了，
     * 然后合理地怀疑「是不是没恢复全」——而其实数据一条不少。
     */
    val WHAT_HAPPENS: List<String> = listOf(
        "这台设备上的保险库将和你手上那份文件一模一样，一个字节都不差。",
        "那份文件不会被改动，也不会被移动或删除——恢复完它照样是一份可用的备份。",
        "指纹和 PIN 不会跟着过来：它们绑在原来那台设备的安全芯片上，谁都拷不走。恢复完在设置里重新开一次就行。",
        "自动锁定、剪贴板这些设置会跟着过来——它们本来就存在库文件里。",
    )

    /**
     * 主密码框旁边那句话。**主密码是导出那一刻的那个**，不是用户现在最常用的那个——
     * 中间改过主密码的话，这份文件认的还是旧的（决策(114) 里那条「最安静的数据丢失路径」，
     * 在这一页是它唯一一次被用户当面撞上）。
     */
    const val PASSWORD_NOTE: String =
        "这份备份认的是导出那一刻的主密码。如果你后来改过主密码，它认的仍然是改之前那个。"

    /**
     * 输错不进退避，要写出来。
     *
     * 退避（`AttemptLimiter`）守的是**这台设备上那个库的门**；这一页上还没有库，
     * 也就没有门可守——挡在这儿只会挡住一个正拿着自己的备份、正在回忆旧口令的人。
     * 真正的限速是 KDF 本身：每错一次都要实打实跑一遍派生。
     */
    const val RETRY_NOTE: String =
        "这一页输错主密码不会被锁上，错了直接再来一次。"

    /* ══════════ 短版 ══════════ */

    /**
     * 密码框下面那一行。
     *
     * [PASSWORD_NOTE] 和 [RETRY_NOTE] 两段平铺时正好把「恢复」按钮顶下去，
     * 而它们的分量差得很远：前者会让人恍然大悟（「哦，是改之前那个」），
     * 后者只是让人放心（错了不会被锁）。留在外面的是前者的第一句，
     * 两段完整的在链接后面。
     */
    const val PASSWORD_HINT_SHORT: String = "认的是导出那一刻的主密码，不是你现在最常用的那个。"

    /**
     * 「恢复之后会怎样」收成一行时的标题与副标题。
     *
     * 副标题挑的是 [WHAT_HAPPENS] 第三条——那是这四条里**唯一一件
     * 用户不知道、又一定会撞上**的事。一行入口只放得下一句，就放那句。
     */
    const val AFTER_TITLE: String = "恢复之后会怎样"
    const val AFTER_SUMMARY: String = "指纹和 PIN 要重新开一次，其余原样搬过来"

    // ───────────────────── 拦提交 ─────────────────────

    /**
     * 按钮能不能按。四道拦截**按严重程度排，只报最靠前的那一条**——
     * 一次列出三行红字，用户不知道先解决哪个（同 `ChangeMasterModel` 的做法）。
     *
     * **`busy` 要单独判一次，不能只看 [blockReason] 是不是 null。**
     * 那个函数在忙的时候刻意返回 null（忙着的时候按钮本来就不画成可点，
     * 再配一句话是多余的），于是「没话说」和「可以按」在这里不是一回事：
     * 只写 `blockReason(...) == null` 的话，正在恢复的过程中按钮反而是可点的，
     * 连点两下就会同时跑两趟恢复。同 `DeleteVaultModel.canSubmit` 的写法。
     */
    fun canSubmit(
        probe: Probe?,
        hasPassword: Boolean,
        busy: Boolean,
        vaultExists: Boolean,
    ): Boolean = !busy && blockReason(probe, hasPassword, busy, vaultExists) == null

    /**
     * 灰按钮必须配一句解释（决策(61)：没有解释的灰控件，用户第一反应是应用卡了）。
     * 返回 null 表示可以按。
     */
    fun blockReason(
        probe: Probe?,
        hasPassword: Boolean,
        busy: Boolean,
        vaultExists: Boolean,
    ): String? = when {
        vaultExists ->
            "这台设备上已经有一个保险库了。恢复不会覆盖它——要装这一份，请先解锁现有的库，在设置里删除它。"
        busy -> null   // 忙的时候按钮本来就不画成可点，再配一句话是多余的
        probe == null ->
            "请先选择一个 .lvault 备份文件。"
        probe !is Probe.Recognized ->
            "这个文件不能用来恢复，请换一个。"
        !hasPassword ->
            "请输入这份备份的主密码。"
        else -> null
    }

    // ───────────────────── 过程与失败 ─────────────────────

    /** 两句进度。两次等待的原因不同（一次是派生，一次是写盘），所以不合成一句「正在恢复…」。 */
    const val STEP_READING: String = "正在读取文件…"
    const val STEP_OPENING: String = "正在核对主密码…"
    const val STEP_INSTALLING: String = "正在装到这台设备上…"

    enum class Failure {
        /** 口令不对 */
        WrongPassword,
        /** 是我们的文件，但密文解不开了 */
        Corrupted,
        /** 压根不是保险库文件 */
        NotVaultFile,
        /** 更新版本的应用写的 */
        TooNew,
        /** 文件用的 KDF 在这台设备上没有实现（多半是 Argon2 原生库没加载） */
        UnsupportedKdf,
        /** 这台设备上已经有库了 */
        VaultExists,
        /** 读文件 / 写盘出问题 */
        Io,
        /** 剩下的都归这儿 */
        Unknown,
    }

    /**
     * 失败文案。八条各写一遍，不共用模板。
     *
     * 每一条都必须落一句**「你手上那份文件没有被改动」**：
     * 这一页的用户多半刚经历过换机、丢手机或者忘记主密码，此刻他最怕的不是恢复失败，
     * 是「我最后这份备份是不是也被弄坏了」。这句话在八条路上全都成立
     * （我们只读那个文件，一次都不写它），所以八条都写得起。
     *
     * 每一条还必须给出**下一步**，而且八个下一步互不相同——
     * 分类分出来的全部价值就在这儿（见 [probe] 的说明）。
     */
    fun failureMessage(f: Failure): String = when (f) {
        Failure.WrongPassword ->
            "主密码不对，打不开这份备份。它认的是导出那一刻的那个主密码。" +
                "你手上那份文件没有被改动，再试一次就行。"
        Failure.Corrupted ->
            "这确实是一份保险库文件，但里面的内容已经解不开了，多半是在拷贝或同步途中被改坏的。" +
                "你手上那份文件没有被改动。请换一份备份试试——如果还留着更早的一份，那一份多半是好的。"
        Failure.NotVaultFile ->
            "这不是保险库文件（开头的标识对不上）。你手上那份文件没有被改动。" +
                "请回去挑一个导出时生成的 .lvault 文件；改过文件名也没关系，我们只看内容。"
        Failure.TooNew ->
            "这份备份是更新版本的应用导出的，当前版本读不懂它。你手上那份文件没有被改动。" +
                "请先把应用升级到新版本再来，别拿更早的备份将就——那会让你丢掉中间的所有改动。"
        Failure.UnsupportedKdf ->
            "这份备份用的密钥派生算法在这台设备上跑不起来（多半是 Argon2 组件没能加载）。" +
                "这既不是文件的问题，也不是主密码的问题，你手上那份文件没有被改动。" +
                "换一个完整版本的安装包，或换一台设备再试。"
        Failure.VaultExists ->
            "这台设备上已经有一个保险库，恢复不会覆盖它。你手上那份文件没有被改动。" +
                "要装这一份，请先解锁现有的库，在设置里删除它。"
        Failure.Io ->
            "文件读不下来，可能是它所在的位置断开了（U 盘拔了、网盘还没同步完）。" +
                "你手上那份文件没有被改动。把它先拷到本机存储里，再试一次。"
        Failure.Unknown ->
            "恢复没有完成，这台设备上也没有留下半个库。你手上那份文件没有被改动，可以直接再试一次。"
    }

    /** 八条失败文案共用的那句话。测试拿它逐条比对。 */
    const val UNTOUCHED_CLAUSE: String = "你手上那份文件没有被改动"
}
