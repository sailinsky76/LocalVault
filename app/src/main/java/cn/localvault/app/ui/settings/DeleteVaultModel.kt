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

package cn.localvault.app.ui.settings

import cn.localvault.app.ui.util.Fmt

/**
 * 删除保险库的内核：这一屏要摆出哪些事实、备份状态该用什么语气说、
 * 删完之后设备上还剩什么、失败了说什么、设置页那一行写什么。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 和 `SettingsModel` / `QuickUnlockModel` / `PinSetupModel` / `ChangeMasterModel` 同一个套路。
 *
 * ── 这是全 App 唯一一个真正不可逆的动作 ──
 *
 * 别处的「危险」都留了后路：删条目有墓碑页可以撤销（M3-4a）、连错 N 次只退避不清库
 * （决策⑦）、改主密码失败时文件一个字节都没动（决策(110)）。
 * 只有这一个，按下去之后**没有任何东西能把它拿回来**——
 * 不是「30 天内可恢复」，不是「回收站」，是那个文件不在了，而它是这台设备上
 * 唯一一份数据（决策⑤：整个库就是一个文件）。
 *
 * 所以这一页的写法和别的页反过来：别处是「把代价写出来」，这一页是
 * **把用户手上还剩什么写出来**。他真正需要在按下按钮之前想清楚的不是
 * 「删除会怎样」（他知道），是「我还有没有那份备份文件、还记不记得它的口令」。
 * 这两件事我们一件都验证不了，所以只能问，不能替他判断——见 [BackupStand.Fresh]
 * 那一条为什么也不说「可以放心删」。
 *
 * ── 这一页不许出现的词 ──
 *
 * 「粉碎」「彻底销毁」「军工级擦除」「不可恢复地覆写」。
 * 我们没有做覆写擦除，也不打算做（决策⑧），说这些话就是撒谎。
 * 该说的实话在 [ERASURE_NOTE] 里。
 */
object DeleteVaultModel {

    /* ══════════════════════ 这次会删掉什么 ══════════════════════ */

    /**
     * 摆在页面顶上的事实清单。**只有数量、大小、时间，没有任何一条条目的内容。**
     *
     * 为什么连一条名称都不列：这一屏很可能不是本人在看
     * （能走到这儿说明库是开着的，而库开着的手机可能正躺在桌上）。
     * 列出「微信 / 招商银行 / 公司邮箱」等于把一份目录白送给站在旁边的人，
     * 而这份目录对**真正的用户**一点用都没有——他知道自己存了什么。
     * 同决策㉖ 在解锁页上的那条规矩，只是方向反过来了。
     */
    data class Inventory(
        val entries: Int,
        val fileBytes: Long,
        val createdAt: Long,
    )

    data class Fact(val label: String, val value: String)

    fun facts(inv: Inventory, now: Long = System.currentTimeMillis()): List<Fact> = listOf(
        Fact("条目", "${inv.entries} 条"),
        Fact("库文件", Fmt.bytes(inv.fileBytes)),
        // 建库时间为 0（老库、或者刚建完还没落时间）时写「未知」，不写 1970 年。
        // 同关于页那一条：一个显示「1970-01-01」的界面会让人怀疑别处的数字也不可信。
        Fact("建于", if (inv.createdAt > 0L) Fmt.relativeTime(inv.createdAt, now) else "未知"),
    )

    /* ══════════════════════ 备份现在是什么状况 ══════════════════════ */

    /**
     * 三种处境。判定和列表页那条备份提醒条用的是同一套口径（决策㉞：
     * 按「改了多少条」算，不按「多少天没备份」算）。
     */
    enum class BackupStand {
        /** 从来没导出过。删下去就是真的什么都不剩了 */
        Never,

        /** 导出过，但之后又改了 N 条。那 N 条不在任何备份里 */
        Stale,

        /** 导出过，之后没有条目变动 */
        Fresh,
    }

    fun backupStand(lastBackupAt: Long, changedSince: Int): BackupStand = when {
        lastBackupAt <= 0L -> BackupStand.Never
        changedSince > 0 -> BackupStand.Stale
        else -> BackupStand.Fresh
    }

    /**
     * 备份状况的说法。[severe] 为 true 时页面用红色（`BannerTone.Danger`），
     * 否则用中性色。
     *
     * ── [BackupStand.Fresh] 为什么也不说「可以放心删」 ──
     *
     * 因为「备份是最新的」这个判断我们只能做到一半：我们知道**导出过**，
     * 而且知道那次导出是**校验过的**（决策⑱：写后回读比对才记 `lastBackupAt`）。
     * 但那之后的事我们一概不知道——文件还在不在那个网盘里、
     * U 盘有没有被格式化过、以及最要命的一条：**那份文件对应的主密码，
     * 用户还记不记得**（如果中间改过主密码，它认的还是旧的，见决策(114)）。
     * 说一句「可以放心删」，等于替用户对三件我们看不见的事下了保证。
     * 所以这一档只陈述我们确实知道的那一件，剩下的用问句还给他。
     */
    data class Notice(val text: String, val severe: Boolean)

    fun backupNotice(
        stand: BackupStand,
        changedSince: Int,
        lastBackupAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Notice = when (stand) {
        BackupStand.Never -> Notice(
            "这个保险库从来没有导出过备份。删掉之后，里面的东西不会在任何地方还有一份。",
            severe = true,
        )
        BackupStand.Stale -> Notice(
            "上次导出备份是${Fmt.relativeTime(lastBackupAt, now)}，之后有 $changedSince 条改动没有进过备份。" +
                "删掉之后，那 $changedSince 条只存在于这台设备上的部分就没有了。",
            severe = true,
        )
        BackupStand.Fresh -> Notice(
            "上次导出备份是${Fmt.relativeTime(lastBackupAt, now)}，之后条目没有变动。" +
                "那份文件现在还在你手上吗？它的主密码你还记得吗？",
            severe = false,
        )
    }

    /* ══════════════════════ 删完之后 ══════════════════════ */

    /**
     * 会跟着一起没掉的东西。**没绑快捷解锁就不提指纹和 PIN。**
     * 同 `ChangeMasterModel.quickUnlockNote`：解释一个用户没有的功能，
     * 只会让他多读两行、多一分不确定。
     */
    fun collateral(pinEnrolled: Boolean, biometricEnrolled: Boolean): List<String> {
        val out = mutableListOf(
            "保险库文件本身，连同它的上一版备份副本",
            "全部条目、分类、以及这个库里的所有设置",
        )
        val which = when {
            pinEnrolled && biometricEnrolled -> "指纹解锁和 PIN"
            biometricEnrolled -> "指纹解锁"
            pinEnrolled -> "PIN 解锁"
            else -> null
        }
        if (which != null) {
            out += "${which}的绑定，以及安全芯片里对应的那把钥匙"
        }
        return out
    }

    /**
     * **不**会跟着没的东西。这一条比上面那张清单更容易被忽略，也更容易被写成谎话。
     *
     * 你导出到别处的 `.lvault` 文件是独立的一份，删库不会去追杀它们——
     * 这既是好消息（真删错了，拿备份还能回来），也是必须说清的一件事
     * （以为「删掉全部数据」包含那份放在网盘里的备份的人，会以为自己已经清干净了）。
     */
    const val EXPORTS_NOTE: String =
        "你导出到别处的备份文件不受影响。它们是独立的副本，" +
            "这里删不掉，也管不着——要清干净得自己去那些地方删。"

    /**
     * [EXPORTS_NOTE] 的短版（v4）。
     *
     * 留的是那半句好消息（「不受影响」），因为这一页上它是唯一一件
     * 能让人松一口气的事；「要清干净得自己去删」是**读完之后**才用得上的行动项，
     * 进弹窗。两半都在，只是不再同时占着按钮上面那两行。
     */
    const val EXPORTS_NOTE_SHORT: String =
        "你导出到别处的备份文件不受影响，这里删不掉也管不着。"

    /**
     * 覆写擦除这件事的如实交代（决策⑧）。
     *
     * 很多同类应用在这一步会写「安全擦除」「多次覆写」。那是假的：
     * SSD / eMMC 有磨损均衡和 FTL 映射，往同一个路径写随机数根本落不到原来的物理块上，
     * 只是自欺欺人，还额外磨损闪存。
     *
     * 真正的保障是 Android 的全盘加密——文件删掉之后那些块的密钥不可达，
     * 就等同于销毁。这句话说出来不好听（用户想听的是「粉碎」），
     * 但一个在这种地方肯说实话的应用，说别处的话才有人信。
     */
    const val ERASURE_NOTE: String =
        "删除就是把文件删掉，不做「多次覆写」那一套——" +
            "闪存有磨损均衡，往同一个路径写随机数根本盖不到原来的物理块，" +
            "只是自欺欺人还磨硬件。真正管用的是 Android 的全盘加密：" +
            "文件删掉之后，那些数据块的密钥不可达，等同销毁。"

    /**
     * [ERASURE_NOTE] 的短版（v4）。
     *
     * **先说我们做了什么、不做什么**——那是这句话的全部承诺，
     * 也是唯一一句用户不看就会误会的（他默认我们在「粉碎」）。
     * 闪存磨损均衡、FTL 映射、全盘加密那三层原理是**支撑**，不是承诺，
     * 收进弹窗一字不删。决策⑧ 要的是这件事被说出来，不是被说满四行。
     */
    const val ERASURE_NOTE_SHORT: String =
        "删除就是把文件删掉，不做「多次覆写」那一套。"

    /** [ERASURE_NOTE] 那个弹窗的标题。删除页和清空重来页共用（同决策(131)）。 */
    const val ERASURE_DETAIL_TITLE: String = "为什么不做「多次覆写」"

    /* ══════════════════════ 能不能按下去 ══════════════════════ */

    /**
     * 拦住提交的原因。**只有一条**，所以不做成枚举列表。
     *
     * ── 为什么门槛是主密码，而不是「请抄写『删除保险库』」 ──
     *
     * 抄写短语是这一类操作的通行做法，它防的是**惯性点击**：
     * 用户闭着眼一路点主按钮，抄写那一步会把他截停。这个作用是真的。
     *
     * 但主密码框同样能截停他（没有人能凭肌肉记忆无意识地打完一个 20 位口令），
     * 而且它多做一件抄写永远做不到的事：**证明坐在这儿的是本人。**
     * 这一页真正的威胁场景不是「用户手滑」，是决策(112) 里那个场景的加强版——
     * 一个把解锁着的手机放在桌上转身接水的人，回来发现库没了。
     * 改主密码尚且可以拿备份救回来，这一个救不回来。
     *
     * 两道门都上是没必要的：抄写在主密码之外不新增任何保护，
     * 只新增一次仪式，而仪式做多了，用户学会的是「照着抄就行」。
     *
     * ── 代价，以及为什么接受 ──
     *
     * 靠指纹解锁进来、确实想不起主密码的人，在这一页删不掉。
     * 那正是这道门该拦住的形状：一个进得来、却说不出主密码的人，
     * 更可能是别人而不是他自己。他还有系统层面的「清除应用数据」可用，
     * 那条路我们本来也拦不住——见 [BLOCKED_HINT]，出口给了，但不放在标题上。
     */
    fun canSubmit(passwordLength: Int, busy: Boolean): Boolean =
        passwordLength > 0 && !busy

    /**
     * 主密码框下面那句话。**只在框是空的时候出现。**
     * 一旦用户开始输入就让位——那时候他已经知道要干什么了。
     */
    const val PASSWORD_HINT: String =
        "删除要用主密码确认。指纹和 PIN 在这一步不算数：它们能开门，" +
            "但证明不了现在拿着手机的是你。"

    /**
     * [PASSWORD_HINT] 的短版（v4）。
     *
     * 「指纹和 PIN 在这一步不算数」必须留在外面：这一页的用户十有八九
     * 是靠指纹进来的，他此刻正要伸手去按传感器。**为什么**不算数
     * （它们证明不了拿着手机的是你）是一句解释，进弹窗。
     */
    const val PASSWORD_HINT_SHORT: String =
        "删除要用主密码确认，指纹和 PIN 在这一步不算数。"

    /** 想不起主密码、又确实想清空这个应用的人，唯一还剩的那条路。 */
    const val BLOCKED_HINT: String =
        "实在想不起主密码：去系统的「应用信息 → 存储 → 清除数据」，" +
            "效果一样，我们拦不住也不打算拦。"

    /* ══════════════════════ 最后那个弹窗 ══════════════════════ */

    const val CONFIRM_TITLE: String = "删除之后拿不回来"
    const val CONFIRM_YES: String = "永久删除"
    const val CONFIRM_NO: String = "取消"

    /**
     * 最后那个弹窗的正文。
     *
     * **里面只有条数，没有任何一条条目的名称、账号或密码。**
     * 决策⑭ 已经把弹窗的 `FLAG_SECURE` 焊死了，但那管的是截屏；
     * 这一屏正对着的是站在旁边的那双眼睛，而弹窗恰恰是全屏最显眼的一块。
     * 对比 M3-4a 删单条那个弹窗——那里写了名称和打过码的账号，
     * 因为用户必须确认「删的是哪一条」；这里没有「哪一条」可选，
     * 写出来就纯粹是白送信息。
     *
     * [BackupStand.Never] 时多一句：这是唯一一种「删完真的什么都不剩」的处境，
     * 而它同时也是最常见的一种（跳过过首次备份的人）。
     */
    fun confirmMessage(entries: Int, stand: BackupStand): String {
        val head = if (entries > 0) {
            "$entries 条记录连同这个保险库文件会被删除。这个动作没有撤销，也没有回收站。"
        } else {
            "这个保险库文件会被删除。库里现在没有条目，但删除本身一样没有撤销，也没有回收站。"
        }
        return if (stand == BackupStand.Never) {
            "$head\n\n你没有导出过备份，删完之后这些内容不会在任何地方还有一份。"
        } else {
            head
        }
    }

    /* ══════════════════════ 失败了说什么 ══════════════════════ */

    /**
     * 三种失败。**它们的共同点是：保险库还在。**
     *
     * 这是 `DeleteVaultController` 的执行顺序保证的（先验口令、再清快捷解锁残留、
     * 最后才删文件），所以下面每一条都可以把这句话写出来。
     * 删库失败时用户最怕的和改密码失败时是同一件事——「是不是删了一半」。
     */
    enum class Failure {
        /** 主密码输错了。文件一个字节都没动，快捷解锁也还在 */
        WrongPassword,

        /**
         * 快捷解锁的残留清掉了，但库文件没删成（权限、正被占用之类）。
         *
         * 这一条要单独说，因为它是唯一一种**留下了副作用**的失败：
         * 库还在，指纹和 PIN 却已经关了。用户下次解锁会发现要输主密码，
         * 不说清楚的话，那看起来像是「删了一半」的证据。
         */
        FilesRemain,

        Unknown,
    }

    fun failureMessage(f: Failure): String = when (f) {
        Failure.WrongPassword ->
            "主密码不对。保险库没有被删除，也没有任何东西被改动。"
        Failure.FilesRemain ->
            "库文件没能删掉，保险库还在，数据一条没少。" +
                "但快捷解锁已经在这一步之前被关掉了——" +
                "不想删了的话，去「设置 → 快捷解锁」重新开一次就行。"
        Failure.Unknown ->
            "删除没有完成。保险库还在，数据一条没少。"
    }

    /* ══════════════════════ 设置页那一行 ══════════════════════ */

    /**
     * 设置页「删除保险库」那一行的副标题。
     *
     * **它永远不带 urgent 标记，永远不用黄铜色。**
     * 「导出加密备份」和「修改主密码」那两行会在有事要办时变色（决策(118)），
     * 因为那是在提醒用户去做一件对他有好处的事。这一行不是：
     * 我们没有任何立场提醒任何人去删自己的数据，把它标成显眼的颜色，
     * 只会增加它被点开的次数——而这一行是全 App 唯一一个点进去可能后悔的入口。
     *
     * 它也不写「危险」「谨慎操作」这类修饰。分区标题已经写着「危险区」了，
     * 副标题的位置留给一句真正有信息量的话。
     */
    const val ROW_SUBTITLE: String = "连同快捷解锁一起清空，无法恢复"
}
