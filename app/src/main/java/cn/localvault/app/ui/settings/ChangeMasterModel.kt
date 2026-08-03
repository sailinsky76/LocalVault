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
import cn.localvault.app.ui.util.PasswordStrength

/**
 * 修改主密码的内核：什么时候能提交、拦下来时说什么、失败了说什么、
 * 改完之后要交代什么、设置页那一行写什么。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 和 `SettingsModel` / `PinSetupModel` / `QuickUnlockModel` 是同一个套路。
 * 这一页尤其需要这一层，因为它上面几乎每一句话都是在**声明一件已经发生或即将发生
 * 的事实**（「你的备份还认旧主密码」「保险库没有被改动」「指纹不用重设」），
 * 而这类话一旦和实现对不上，用户是不会当场发现的——他会在半年后打不开备份时发现。
 *
 * ── 这一页最要紧的一件事 ──
 *
 * 不是「改密码」本身，是**改完之后那份旧备份怎么办**。
 * 主密码改掉之后，之前导出的 `.lvault` 文件一个字节都不会变，它仍然只认旧主密码——
 * 而旧主密码正是用户刚刚决定不再用、多半也不打算再记的那一个。
 * 这条路上没有任何东西会报错：改密码成功了，备份文件还好好躺在网盘里，
 * 一切看起来都对，直到某天真要用它。
 * 所以这个文件里有三处在说同一件事（提交前的横幅、成功之后的去处、设置页那一行），
 * 那不是啰嗦，是这条路上唯一的三个能说话的时机。
 */
object ChangeMasterModel {

    /** 新主密码的硬下限。和建库那一页共用同一个数，见 [PasswordStrength.MASTER_MIN_LENGTH]。 */
    const val MIN_LENGTH = PasswordStrength.MASTER_MIN_LENGTH

    /* ══════════════════════ 能不能提交 ══════════════════════ */

    /**
     * 挡住提交的原因。**只有一个能被报出来**（[blocker] 按顺序取第一个），
     * 因为屏幕上同时挂三条红字的结果是用户一条都不读。
     */
    enum class Blocker {
        /** 当前主密码还没填 */
        OldEmpty,

        /** 新主密码没到硬下限 */
        NewTooShort,

        /** 新的两遍不一致 */
        NotMatched,

        /**
         * 新主密码和当前主密码是同一个。
         *
         * 这一条不是洁癖。用户走到这一页，通常是因为他认为旧主密码**已经不安全了**
         * （被人看到过、和别处用了同一个、写在过纸上）。原样再设一遍会跑完整套流程、
         * 弹出成功、连备份都提示他重做一次——而他实际上什么都没改。
         * 那是这一页唯一一种「全部提示都是真的，合起来却在骗人」的结局。
         */
        SameAsOld,
    }

    /**
     * 按顺序取第一条挡路的原因；全都过了返回 null。
     *
     * 顺序是有讲究的：先看当前主密码（不填它后面全是白填），再看长度（客观事实），
     * 再看两遍一致（要等用户把第二遍打完才有意义），最后才是「和旧的一样」——
     * 那一条要等到两遍都对上了再说，否则用户第二遍才打两个字就被扣一顶
     * 「你没改」的帽子，而他根本还没打完。
     */
    fun blocker(
        oldLength: Int,
        newLength: Int,
        matched: Boolean,
        sameAsOld: Boolean,
    ): Blocker? = when {
        oldLength <= 0 -> Blocker.OldEmpty
        newLength < MIN_LENGTH -> Blocker.NewTooShort
        !matched -> Blocker.NotMatched
        sameAsOld -> Blocker.SameAsOld
        else -> null
    }

    fun canSubmit(oldLength: Int, newLength: Int, matched: Boolean, sameAsOld: Boolean): Boolean =
        blocker(oldLength, newLength, matched, sameAsOld) == null

    /**
     * 挡路原因的文案。
     *
     * **只有 [Blocker.SameAsOld] 有话说，其余三条一律返回 null。**
     * 前三条在屏幕上已经各有各的表达方式了：当前主密码是空的（框子空着摆在那儿）、
     * 长度不够（输入框下面那行「还差 N 位」）、两遍不一致（确认框下面那个叉）。
     * 再在按钮上方补一句「请填写当前主密码」，是把用户已经看见的事又说一遍，
     * 而这种话读多了，等到真有一句他没看见的（就是下面这条），他也不会读了。
     */
    fun blockerMessage(b: Blocker): String? = when (b) {
        Blocker.SameAsOld ->
            "新主密码和当前的是同一个。这样改完，一切照旧——" +
                "包括你想换掉它的那个理由。"
        else -> null
    }

    /* ══════════════════════ 提交前的交代 ══════════════════════ */

    /**
     * 提交前那条常驻横幅。**它必须在用户按下按钮的那一刻还在屏幕上**，
     * 所以不做成弹窗（弹窗会被下意识点掉）——同建库页那条。
     *
     * 两句话，各管一件事：
     * 第一句是这一页独有的、用户几乎不可能自己想到的（旧备份不跟着变）；
     * 第二句是老规矩（没有找回通道），它在建库时说过一次，
     * 但那可能是一年前的事了，而这一刻他正要把那个记熟了的口令换掉。
     */
    const val BEFORE_WARNING: String =
        "改完之后，之前导出的备份文件仍然只认旧主密码——它们不会跟着变。" +
            "新主密码同样没有找回通道，忘了就是永久打不开。"

    /**
     * [BEFORE_WARNING] 的短版（v4）。
     *
     * 这一条**不收进弹窗、只收短**：它是这一页唯一一句「这么做有代价」，
     * 而最该看见它的正是那批不会去点链接的人（同导出页那条网盘警告的处置）。
     * 所以横幅照留，两个后果一个不少，只是把「它们不会跟着变」这句
     * 同义重复的补白去掉——完整那句在「详情」里。
     */
    const val BEFORE_WARNING_SHORT: String =
        "改完之后，之前导出的备份只认旧主密码；新主密码一样没有找回通道。"

    /** 那条横幅「详情」弹窗的标题。 */
    const val BEFORE_WARNING_TITLE: String = "改主密码之后，旧备份会怎么样"

    /**
     * 「指纹 / PIN 会怎么样」。**没绑过就一个字都不说。**
     *
     * 绑过的人几乎一定会想到这件事（「改了主密码，指纹是不是要重录」），
     * 不说反而会让他为此犹豫；没绑过的人则完全不需要知道这条机制，
     * 对他来说这只是一段解释了一个他没有的功能的小字。
     * 这和设置页「只在有话要说的时候说话」是同一条规矩。
     *
     * 内容本身是决策① 的直接后果：快捷解锁包的是**库主密钥**，
     * 而改主密码只重新包裹库主密钥、没有换掉它，所以那两份包裹一动不动。
     */
    fun quickUnlockNote(pinEnrolled: Boolean, biometricEnrolled: Boolean): String? {
        val which = when {
            pinEnrolled && biometricEnrolled -> "指纹和 PIN"
            biometricEnrolled -> "指纹"
            pinEnrolled -> "PIN"
            else -> return null
        }
        return "${which}不用重新设置：它们记住的是这个保险库本身，不是你的主密码。"
    }

    /* ══════════════════════ 失败了说什么 ══════════════════════ */

    /**
     * 改密码可能的失败。**这四种全都发生在文件被改动之前。**
     *
     * 这不是巧合，是 `VaultRepository.changeMasterPassword` 的写法保证的：
     * 它先重新包裹、再用新口令解一遍、再用库主密钥解一遍，两道都过了才落盘，
     * 而落盘本身是原子的（临时文件 → fsync → 轮换 → rename）。
     * 于是「失败」和「保险库没被动过」是同一件事，
     * 下面每一条文案都可以理直气壮地把这句话写出来——见 [failureMessage]。
     *
     * 落盘**之后**还剩一件事（记一笔修改时间），那一步失败不算改密码失败，
     * 理由见 `ChangeMasterController`：那时候密码已经真的换了。
     */
    enum class Failure {
        /** 当前主密码输错了 */
        WrongOld,

        /** 自检没过：我们生成了一个自己都解不开的文件。是 bug，但数据是安全的 */
        WriteVerify,

        /** 写盘失败（空间不足之类） */
        Io,

        /** 剩下的 */
        Unknown,
    }

    /**
     * 失败文案。
     *
     * **四条里每一条都必须说清「保险库没有被改动、原来的主密码依然有效」。**
     * 这一页失败时用户最怕的不是没改成，是「改到一半」——
     * 密码管理器改密码改砸了，在人脑里的第一反应是「我是不是两个都用不了了」。
     * 那一刻他需要的不是错误代码，是一句「你现在这个密码还开得了门」。
     * `ChangeMasterModelTest` 有一条用例钉着这件事。
     */
    fun failureMessage(f: Failure): String = when (f) {
        Failure.WrongOld ->
            "当前主密码不对。保险库没有被改动，原来的主密码依然有效。"
        Failure.WriteVerify ->
            "新文件没能通过写入前的自检，本次修改已取消。" +
                "保险库没有被改动，原来的主密码依然有效——这是应用自身的问题，不是你的数据出了事。"
        Failure.Io ->
            "写入失败，请确认存储空间充足后重试。保险库没有被改动，原来的主密码依然有效。"
        Failure.Unknown ->
            "修改没有完成。保险库没有被改动，原来的主密码依然有效。"
    }

    /* ══════════════════════ 改完之后 ══════════════════════ */

    /**
     * 成功之后那一屏。
     *
     * [needsBackup] 为 true 时页面把「现在重新导出一份备份」摆成主按钮。
     * 判定就是那句大白话：**手上那份备份是在改密码之前导出的。**
     *
     * 从来没备份过的人（`lastBackupAt <= 0`）不走这条：他还没有「一份会过期的备份」，
     * 他缺的是第一次备份，而那件事早就有列表页顶上那条常驻提醒在管（决策㉞）。
     * 在这儿再喊一遍只会让两条提醒互相稀释。
     */
    data class Success(val text: String, val needsBackup: Boolean)

    fun success(lastBackupAt: Long): Success =
        if (lastBackupAt > 0L) {
            Success(
                "主密码已经换成新的。你之前导出的备份文件仍然只认旧主密码——" +
                    "现在重新导出一份，把那一份替换掉。",
                needsBackup = true,
            )
        } else {
            Success("主密码已经换成新的。下次解锁就要用它了。", needsBackup = false)
        }

    /* ══════════════════════ 设置页那一行 ══════════════════════ */

    /**
     * 设置页「修改主密码」那一行的副标题。
     *
     * [urgent] 为 true 时页面把这一行画成黄铜色——和「导出加密备份」那一行
     * （`SettingsModel.backupSummary`）用的是同一套表现，因为它们说的是同一件事的两半：
     * 备份太旧了 vs 备份的口令太旧了。后者更隐蔽，所以更需要被标出来。
     *
     * 三种情况各说各的，**都是陈述句，不打分也不劝导**（决策(95)）：
     *   · 从没改过 → 「从未修改过」。就是个事实，不写「建议定期更换」——
     *     定期换主密码这件事本身就没有证据支持，何况这个库不联网，
     *     换掉一个从没离开过设备的口令，防的是什么呢；
     *   · 改过、而且手上那份备份比这次修改还早 → 把那件要紧的事说出来；
     *   · 其余 → 「上次修改：3 个月前」。
     */
    data class RowSummary(val text: String, val urgent: Boolean)

    fun rowSummary(
        masterChangedAt: Long,
        lastBackupAt: Long,
        now: Long = System.currentTimeMillis(),
    ): RowSummary = when {
        masterChangedAt <= 0L -> RowSummary("从未修改过", urgent = false)
        lastBackupAt in 1 until masterChangedAt ->
            RowSummary("手上那份备份还认旧主密码", urgent = true)
        else -> RowSummary("上次修改：${Fmt.relativeTime(masterChangedAt, now)}", urgent = false)
    }
}
