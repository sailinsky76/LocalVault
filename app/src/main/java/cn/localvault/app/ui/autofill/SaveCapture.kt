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
 * 「[SavePlan.Info] 看着的那几个框，此刻各自写着什么」——收成一份 [SaveContext]。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * `getAutofillValue()` 怎么调、`AutofillId` 怎么找回来，是 [SaveShell] 的事
 * （同 [AssistShell] 之于 [StructureRules]、[AutofillResponses] 之于 [AutofillOffer]）。
 * 这一层只回答一个问题：**手上这几格值，拼成交给 [AutofillSave] 的那个入参该是什么样。**
 *
 * ── 为什么这三十几行值得单独一个文件 ──
 *
 * 因为它是整条保存链上**唯一一处**「明文从平台流进我们自己模型」的地方，
 * 而这一处的每一种错法都有同一个形状：**当天没有任何症状，代价在下一次登录时才出现。**
 *
 *   · **一格读不出来就整份作废** —— 分屏登录第二屏根本没有账号框，
 *     `SavePlan` 也照样把它挂上了（那一屏的密码正是最该存的东西）。
 *     一格取不到值就返回 null，表现是「分屏登录的站从此再也存不进东西」，
 *     而分屏登录恰恰是大站的主流形态。所以这里的规矩是**一格一格收，收得下几格算几格**，
 *     「够不够存」由 [AutofillSave.refuse] 那一层判（[AutofillSave.Reason.NothingCaptured]）。
 *   · **顺手去重** —— 两个密码框读到两个不一样的值，是
 *     [SaveContext.conflictingPasswords] 唯一的判据，而那一条守的是
 *     「分不清该存哪个就一个都不存」。在这儿写一句 `distinctBy { it.what }`
 *     来「清理一下」，那条判据当场失效：库里会存下两个密码里随便一个，
 *     用户下次登录时发现登不进去，而他绝不会想到是保存那一步动的手。
 *     **所以这一层一格都不合并、一格都不排序**，[SavePlan.of] 给的顺序原样保持。
 *   · **一格抛异常带走整个回调** —— 见 [capture] 里那个 `catch (Throwable)`。
 *
 * ── 这一层不洗字，一个字符都不洗 ──
 *
 * 取舍全交给 [SavedFields.capture]（那边 trim 账号、密码一个字符不动，理由写在那儿），
 * 这个文件只负责**别把它绕过去**：不许在这里先 `toString().trim()` 一遍再传进去，
 * 那一改会让「以空格结尾的密码原样存下来」这条保证悄悄失效。
 *
 * ── [Tally] 是给日志的，不是给屏幕的 ──
 *
 * 它只有数字，一个字符的值都没有（决策(144)）。存在的理由是
 * 「保存框弹出来了，可什么都没读到」这件事在真机上除了日志没有第二个观察点——
 * 而 [SavedFields.Rejected.TooLong] / [SavedFields.Rejected.Control] /
 * [SavedFields.Rejected.Masked] 那三档意味着这一格里的东西不是我们要的
 * （一整段文本、一串带方向控制符的字、或者安全键盘摆出来的一串圆点），
 * 那是需要在 logcat 里看得见的信号。**它不进屏幕**：
 * 用户要看的是 [AutofillSave.note] 那一句实话，不是我们的记账。
 */
object SaveCapture {

    /**
     * 「第 n 号句柄那个框，此刻写着什么」。
     *
     * 做成一个函数式接口而不是收一个 `Map`，理由和 [HostTrust] / `ImportSource` 一样：
     * 真正的实现要碰 `AssistStructure`（[SaveShell]），而这一层的每一条规则
     * 都得在没有设备的地方跑得起来。
     *
     * 返回 null 表示「这个框没有值，或者找不回来了」——**两种情形不区分**：
     * 从系统弹出保存框到 `onSaveRequest` 到达，页面完全可能又变过一次
     * （网页登录成功后 DOM 换掉一批节点是常态），那时候某几个句柄换不出
     * `AutofillId` 是正常现象，不是故障。
     */
    fun interface Values {
        fun read(handle: Long): CharSequence?
    }

    /**
     * 这一次收了几格、丢了几格、为什么丢。**只有数字。**
     *
     * [unreadable] 和 [blank] 分开记是有意的：前者说明平台那一侧出了岔子
     * （读值抛了异常），后者是每天都在发生的正常现象（那个框本来就是空的）。
     * 合成一格的话，「某个安卓版本上读值方法整个不通」这件事
     * 会伪装成「用户什么都没打」，而那会安静地让保存在那些机型上全线失效。
     */
    class Tally internal constructor(
        /** [SavePlan.Info.watches] 一共几格。 */
        val watched: Int,
        /** 收下了几格。 */
        val kept: Int,
        /** 空的或只有空白。 */
        val blank: Int,
        /** 超过 [SavedFields.MAX_VALUE_CHARS]，整格拒收。 */
        val tooLong: Int,
        /** 里面有控制字符或双向控制符，整格拒收。 */
        val control: Int,
        /** 读到的是一串掩码符（安全键盘），整格拒收。见 [SavedFields.Rejected.Masked]。 */
        val masked: Int,
        /** 读的时候抛了东西。见 [capture] 里那段。 */
        val unreadable: Int,
    ) {
        /** 读到了东西但没要。**这两档都意味着某个框被判错了**，见文件头末段。 */
        val rejected: Int get() = tooLong + control + masked

        override fun toString(): String =
            "Tally(watched=$watched, kept=$kept, blank=$blank, " +
                "tooLong=$tooLong, control=$control, masked=$masked, unreadable=$unreadable)"
    }

    /**
     * 收完之后的东西：一份能直接交给 [AutofillSave.outcome] 的 [SaveContext]，
     * 外加一份 [Tally]。
     *
     * **[context] 永远不是 null，即使一格都没收下。**
     * 空手也要往下走一步，因为「什么都没读到」在这条链上是一句**要说出口**的话
     * （[AutofillSave.Reason.NothingCaptured]，而 [AutofillSave.refuse] 是它唯一的产地）。
     * 在这一层提前返回 null，用户按下系统那个保存框之后看到的就是一次
     * 什么都没发生的提交——而这一档恰恰不是「他没打字」：
     * `SavePlan` 的必填语义保证了系统弹框时那个框**有值**，
     * 所以走到这一档几乎总意味着那个值被 [SavedFields] 整格拒收了，
     * 也就是我们把某个框判错了。那件事必须说一句。
     */
    class Capture internal constructor(
        val context: SaveContext,
        val tally: Tally,
    ) {
        /** 同 [SaveContext.toString]：只报形状（决策(144)）。 */
        override fun toString(): String = "Capture($tally)"
    }

    /* ══════════════════════════ 入口 ══════════════════════════ */

    /**
     * 收一遍。**M4-3b-2② 只需要调这一个。**
     *
     * [info] 来自 [SavePlan.decide]——注意那一次 `decide` 必须是拿
     * **`onSaveRequest` 这一刻的结构**重新算的，不能复用填充那一刻算好的那一份：
     * 句柄是先序遍历的序号，只在一次 [AssistShell.parse] 里有意义
     * （`AssistShell.Parsed.autofillId` 文件头那段），
     * 而且页面在这中间本来就可能变过。拿旧句柄去读新结构，
     * 读到的不是「没有值」就是**另一个框里的值**——后者会把某个框里的东西
     * 当成密码存进库，而它可能是一个手机号，也可能是别人的名字。
     *
     * [appLabel] 是承载应用**自称**的名字，读不出来就传 null，
     * **绝不在这里编一个「未知应用」兜底**（决策(188)；
     * [AutofillSave.storedUnder] 那边会只写包名）。
     *
     * ── 那个 `catch (Throwable)` ──
     *
     * 收得下的按 [SavedFields] 的规矩收，读不出来的记一笔 [Tally.unreadable] 就跳过。
     * 抓 `Throwable` 而不是 `Exception` 是照着 [AssistShell] 文件头那一段：
     * 平台 getter 在低版本上缺失时抛的是 `NoSuchMethodError`，
     * 那是个 `Error`。一格上的 `Error` 不该带走整个 `onSaveRequest`——
     * 一次未捕获的异常从系统回调里冒出去，用户看到的是别人的应用旁边
     * 弹了一条「保险库已停止运行」。
     */
    fun capture(info: SavePlan.Info, values: Values, appLabel: String?): Capture {
        val kept = ArrayList<SavedFields.Value>(info.watches.size)
        var blank = 0
        var tooLong = 0
        var control = 0
        var masked = 0
        var unreadable = 0

        // 顺序原样保持，一格不合并——见文件头第二条
        for (w in info.watches) {
            val raw = try {
                values.read(w.handle)
            } catch (t: Throwable) {
                unreadable += 1
                continue
            }

            val value = SavedFields.capture(w.what, raw)
            if (value != null) {
                kept += value
                continue
            }
            when (SavedFields.rejection(w.what, raw)) {
                SavedFields.Rejected.Blank -> blank += 1
                SavedFields.Rejected.TooLong -> tooLong += 1
                SavedFields.Rejected.Control -> control += 1
                SavedFields.Rejected.Masked -> masked += 1
                // 收不下却问不出原因是说不通的（[SavedFields.capture] 和
                // [SavedFields.rejection] 是同一套判据）。真走到这儿按空的算，
                // 但**不合并进 blank**——那会让上面那句「说不通」变得看不见。
                null -> unreadable += 1
            }
        }

        return Capture(
            context = SaveContext(
                origin = info.origin,
                kind = info.kind,
                values = kept,
                appLabel = appLabel,
                // 见 SaveContext.maskedPassword：拒收已经保住了库，
                // 这一位只是让下游说得出「为什么没有密码」
                maskedPassword = masked > 0,
            ),
            tally = Tally(
                watched = info.watches.size,
                kept = kept.size,
                blank = blank,
                tooLong = tooLong,
                control = control,
                masked = masked,
                unreadable = unreadable,
            ),
        )
    }
}
