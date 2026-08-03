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

import cn.localvault.app.ui.unlock.BiometricFailure

/**
 * 快捷解锁绑定页的内核：这台设备到底支不支持、开关能不能动、
 * 每种情况下该说哪一句话、绑定失败时该怎么说。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 和 `BiometricPolicy`（解锁侧）是同一个套路，也是同一个理由：
 * 这一页上的判断分支比它看起来多得多——「没硬件」「有硬件但没录指纹」
 * 「录了但传感器被占用」「绑过但指纹库变了」「系统安全模块要更新」——
 * 每一种的处置和文案都不一样，而这些恰恰是最难在真机上凑齐的场景
 * （要凑「安全模块需要更新」得找一台特定批次的机器）。
 * 搬到这里之后，它们全都变成能在纯 JVM 上断言的东西。
 *
 * ── 这一页最要紧的一句话 ──
 *
 * **主密码始终是唯一的真凭据，快捷解锁只是这台设备上的一条捷径。**
 * 这条在 M1 决策① 就定下了，但直到这一页才第一次直接摆在用户面前。
 * 下面所有文案都不许和它冲突：不许暗示「开了指纹更安全」
 * （它不改变库文件的强度），也不许暗示「关了指纹更安全」
 * （那会把用户推回「主密码设短一点省事」，那才是真的削弱）。
 */
object QuickUnlockModel {

    /* ══════════════════════ 这台设备支不支持 ══════════════════════ */

    /**
     * `BiometricManager.canAuthenticate()` 那一串返回码的**语义**分类。
     *
     * 平台码 → 这个枚举是一行 `when`，放在碰 Android 的那一侧
     * （`SecuritySettingsScreen` 里的 `toSupport()`）；
     * 枚举 → 怎么处置在这里。和 `BiometricPolicy` 的分法完全一致，
     * 这样两侧的判断风格不会各长各的。
     */
    enum class BiometricSupport {
        /** 能用，现在就能绑。 */
        Ready,

        /** 有传感器，但系统里一枚指纹都没录。**这是最常见的一种。** */
        NoneEnrolled,

        /** 这台设备根本没有强生物识别硬件。 */
        NoHardware,

        /** 硬件被占用 / 暂时不可用。可能过一会儿就好了。 */
        TemporarilyUnavailable,

        /** 系统的生物识别安全模块需要更新（厂商推送），在此之前不可用。 */
        NeedsSecurityUpdate,

        /**
         * 系统没给出明确答复。
         *
         * **不要把它当成「不支持」**——见 [biometricRow] 里的说明。
         */
        Unknown,
    }

    /* ══════════════════════ 指纹那一行 ══════════════════════ */

    /**
     * 指纹开关那一行要画成什么样。
     *
     * @param checked   开关的位置。它反映的是**有没有绑过**，不是「现在能不能用」——
     *                  绑过但指纹库变了的时候，开关仍然是开着的，
     *                  因为 prefs 里确实还躺着一份（已经没用了的）包裹，
     *                  用户需要看见它、并且能把它关掉。
     * @param enabled   开关能不能动。
     * @param subtitle  行副标题，一句话说清当前状态。
     * @param note      只在**有话要说**时非 null。中间那些「一切正常」的情况一律 null——
     *                  规矩同 `SettingsModel.autoLockNote`（决策(95)）：
     *                  每一行都配一句说明的页面，读起来像免责声明，
     *                  用户学会的是跳过所有小字。
     * @param noteShort [note] 摆在开关旁边时的样子（`components/Explain.kt` 那条规矩）。
     *                  **默认就是 [note] 本身**——这一行的说明大多本来就只有一两行，
     *                  为了对称去凑一个短版，等于把同一句话维护两遍。
     *                  只有真正超过两三行的那一档（绑定失效）才自己写一份，
     *                  完整的那段进弹窗，一个字不删。
     * @param showEnrollHint 要不要给一个「去系统设置录入指纹」的出口。
     *                  只在**录一枚指纹就能解决问题**时才给：没有硬件的机器上给这个按钮，
     *                  等于把用户支到一个他到了也没用的地方。
     */
    data class BiometricRow(
        val checked: Boolean,
        val enabled: Boolean,
        val subtitle: String,
        val note: String?,
        val showEnrollHint: Boolean,
        val noteShort: String? = note,
    )

    fun biometricRow(support: BiometricSupport, enrolled: Boolean): BiometricRow = when {

        /* ── 已绑定 ── */

        enrolled && support == BiometricSupport.Ready -> BiometricRow(
            checked = true,
            enabled = true,
            subtitle = "已开启 · 只在这台设备上",
            note = null,
            showEnrollHint = false,
        )

        enrolled && support == BiometricSupport.NoneEnrolled -> BiometricRow(
            checked = true,
            enabled = true,
            subtitle = "绑定已失效",
            // 这不是故障，是安全机制正常工作。所以先说「不是故障」，
            // 再说「数据没动」，最后才说下一步——顺序是照用户的担心顺序排的。
            note = "系统里的指纹被删光了，出于安全，原来那份绑定已经作废。" +
                "这不是故障，保险库里的数据一条没动。关掉它可以把残留清干净，" +
                "重新录入指纹之后再打开一次就行。",
            showEnrollHint = true,
            // 这一行的短版留的是那两句「他此刻最怕的事」——不是故障、数据没动。
            // 怎么修（关掉清残留、重录一枚再开）是**读完之后**的事，进弹窗；
            // 而且它下面紧接着就有一个「去系统设置录入指纹」的按钮，
            // 那条路已经在屏幕上了，不必先用三行字把它说一遍。
            noteShort = "系统里的指纹被删光了，这份绑定已作废。不是故障，数据一条没动。",
        )

        enrolled && support == BiometricSupport.NoHardware -> BiometricRow(
            checked = true,
            enabled = true,
            subtitle = "这台设备用不了指纹",
            note = "绑定记录还在，但这台设备上找不到可用的指纹传感器。关掉它可以把残留清干净。",
            showEnrollHint = false,
        )

        enrolled -> BiometricRow(
            checked = true,
            enabled = true,
            // 「未知」时不许写「暂时不可用」——我们并不知道它可不可用，
            // 写死一个结论会和下面那句「可以直接试一次」当场打架。
            subtitle = if (support == BiometricSupport.Unknown) "已开启"
            else "已开启 · 当前暂时不可用",
            note = supportNote(support),
            showEnrollHint = false,
        )

        /* ── 未绑定 ── */

        support == BiometricSupport.Ready -> BiometricRow(
            checked = false,
            enabled = true,
            subtitle = "未开启",
            note = null,
            showEnrollHint = false,
        )

        support == BiometricSupport.NoneEnrolled -> BiometricRow(
            checked = false,
            enabled = false,
            subtitle = "这台设备还没有录入指纹",
            // 灰掉的控件必须自己解释为什么灰（决策(61)），
            // 而且要给出路——只说「不可用」等于把人堵在这儿。
            note = "请先在系统设置里录一枚指纹，回来这个开关就能打开了。",
            showEnrollHint = true,
        )

        support == BiometricSupport.NoHardware -> BiometricRow(
            checked = false,
            enabled = false,
            subtitle = "这台设备没有指纹传感器",
            note = "指纹解锁在这台设备上用不了。这不影响保险库本身——用主密码照样打开。",
            showEnrollHint = false,
        )

        support == BiometricSupport.NeedsSecurityUpdate -> BiometricRow(
            checked = false,
            enabled = false,
            subtitle = "系统需要更新后才能用",
            note = supportNote(support),
            showEnrollHint = false,
        )

        /**
         * [BiometricSupport.TemporarilyUnavailable] 和 [BiometricSupport.Unknown]
         * 一律**留着开关可以按**，而不是灰掉。
         *
         * 「暂时不可用」按字面意思就是可能马上又能用了；「未知」则是系统自己
         * 没给出答复（老机型、定制 ROM 上真的会返回它）。这两种情况下把开关灰掉，
         * 等于我们替用户下了一个连系统都没敢下的结论——而代价是他在一台
         * 其实能用指纹的手机上，永远打不开这个开关。
         *
         * 让他按，按下去弹不出指纹框的话，会拿到一条真实的错误说明
         * （[enrollFailureMessage]），那比一个灰按钮诚实得多。
         */
        else -> BiometricRow(
            checked = false,
            enabled = true,
            subtitle = "未开启",
            note = supportNote(support),
            showEnrollHint = support == BiometricSupport.TemporarilyUnavailable,
        )
    }

    private fun supportNote(support: BiometricSupport): String? = when (support) {
        BiometricSupport.Ready -> null
        BiometricSupport.NoneEnrolled -> "请先在系统设置里录一枚指纹。"
        BiometricSupport.NoHardware -> "这台设备没有可用的指纹传感器。"
        BiometricSupport.TemporarilyUnavailable ->
            "指纹传感器现在被占用或暂时不可用，可以直接试一次，不行的话过一会儿再来。"
        BiometricSupport.NeedsSecurityUpdate ->
            "系统的生物识别模块需要一次安全更新才能使用。这要等厂商推送，我们这边没法代劳。"
        BiometricSupport.Unknown ->
            "这台设备没有给出明确答复。可以直接试一次——真不行的话会告诉你原因。"
    }

    /* ══════════════════════ 绑定失败的文案 ══════════════════════ */

    /**
     * 绑定过程中失败了要说什么。
     *
     * ── 为什么不复用 `BiometricPolicy.message` ──
     *
     * 那一份是**解锁页**用的，每一条的落点都是「请用主密码解锁」——
     * 而这里用户已经在库里了，他要做的事是「开启指纹」，不是「进门」。
     * 把解锁页的文案搬过来，用户会看到「这个保险库现在可以用主密码打开」，
     * 而他的库明明已经开着。那种话读起来像是应用把自己的状态搞糊涂了，
     * 用户对整个安全提示的信任会一起打折。
     *
     * 所以这里另写一份，落点统一改成「这次没绑上，库和数据都没受影响」。
     * `QuickUnlockModelTest` 里有一条用例钉着：这几句话里不许出现
     * 「解锁」「用主密码打开」这类只在门外才成立的说法。
     *
     * 取消返回 null —— 用户按「取消」是一个正常出口，不是异常
     * （同 `BiometricPolicy.shouldShowMessage`）。他只是改主意了。
     */
    fun enrollFailureMessage(failure: BiometricFailure): String? =
        when (failure) {
            BiometricFailure.UserCanceled -> null

            BiometricFailure.TemporaryLockout ->
                "指纹连续多次没通过，系统暂时停用了指纹识别。稍等片刻再开一次；" +
                    "保险库和里面的数据都没有受影响。"

            BiometricFailure.PermanentLockout ->
                "指纹识别已被系统锁定。先用手机自己的锁屏密码解锁一次来恢复它，再回来开启。"

            // 绑定的那一刻钥匙是刚生成的，理论上不会失效。真碰上了，
            // 说明就在这几秒里系统的指纹库变了——重来一次就好，不必解释得太深。
            BiometricFailure.KeyInvalidated ->
                "刚才这台设备的指纹信息发生了变动，这次绑定没有生效。请再试一次。"

            BiometricFailure.HardwareBusy ->
                "指纹传感器正忙，刚才没能弹出指纹框。可以再按一下这个开关；" +
                    "保险库和里面的数据都没有受影响。"

            BiometricFailure.HardwareUnavailable ->
                "这台设备的指纹传感器用不了，没法开启指纹解锁。" +
                    "这不影响保险库本身——主密码照样打开。"

            BiometricFailure.NoneEnrolled ->
                "这台设备还没有录入指纹。请先在系统设置里录一枚，再回来开启。"

            BiometricFailure.Other ->
                "这次没能开启指纹解锁。可以再试一次；保险库和里面的数据都没有受影响。"
        }

    /* ══════════════════════ 设置主页那一行 ══════════════════════ */

    /**
     * 设置主页「快捷解锁」那一行的副标题。
     *
     * **不做任何评判。** 没开启时只写「每次都要输主密码」这个事实，
     * 不写「不安全」「建议开启」——一来它不是真的，主密码本来就是最强的那道；
     * 二来这一页早就定过规矩：如实显示状态，不打分、不劝导（决策(95)）。
     */
    fun summary(pinEnrolled: Boolean, biometricEnrolled: Boolean): String = when {
        biometricEnrolled && pinEnrolled -> "已开启：指纹 · PIN"
        biometricEnrolled -> "已开启：指纹"
        pinEnrolled -> "已开启：PIN"
        else -> "未开启 · 每次都要输主密码"
    }

    /* ══════════════════════ 页面顶部那段话 ══════════════════════ */

    /**
     * 绑定页顶上那段说明。
     *
     * 三句话，一句都不能少：
     *   1. 快捷解锁只在这台设备上成立（换机、拷走文件都带不走）；
     *   2. 主密码永远还能用，它才是真凭据；
     *   3. 关掉的时候数据不会有任何变化。
     *
     * 第 3 句尤其重要：用户在一个密码管理器里关掉任何一个开关时，
     * 第一反应都是「会不会把我的密码弄没」。不把这句话提前写在这儿，
     * 他就会选择**不去动它**——于是一个失效的绑定会一直留在那里。
     */
    val INTRO: String =
        "快捷解锁是这台设备上的一条捷径：它把库主密钥另外包了一份，交给这台手机的安全芯片看管。" +
            "包裹跟着设备走，拷走保险库文件的人拿不到它。\n\n" +
            "主密码始终是唯一的真凭据——不管这里开着还是关着，它都能打开保险库。" +
            "关掉任何一项只会删掉那份包裹，保险库里的数据一条都不会动。"

    /**
     * 页顶那段的**短版**。上面那三句话一句不少，只是各压成一小节：
     * 「这台设备上的一条捷径」＝ 换机带不走；「主密码始终能开门」＝ 真凭据；
     * 「关掉数据一条不会动」＝ 那个最挡路的误会。
     *
     * 安全芯片、包裹这些**机制**留给弹窗。一个刚点进来要开指纹的人，
     * 此刻要判断的是「开了会怎样、关了会怎样」，不是我们把密钥交给了谁。
     */
    val INTRO_SHORT: String =
        "快捷解锁只是这台设备上的一条捷径；主密码始终能开门，关掉它数据一条不会动。"

    /** 页顶那个「详细说明」弹窗的标题。 */
    const val INTRO_DETAIL_TITLE: String = "快捷解锁到底是什么"

    /**
     * 一项都没开时，页面底部那句话。
     *
     * 它解释的是一件容易被误解的事：**这一页是空的不代表有问题。**
     * 从没绑过快捷解锁的用户走进来，看到两个关着的开关和一大段安全说明，
     * 很容易以为自己漏做了什么设置。
     */
    val NONE_ENABLED_NOTE: String =
        "一项都没开也完全正常——那意味着每次打开保险库都要输一遍主密码，" +
            "这是最朴素也最稳的用法。"
}
