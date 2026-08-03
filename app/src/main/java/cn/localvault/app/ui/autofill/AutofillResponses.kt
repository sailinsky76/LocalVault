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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveInfo
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue

/**
 * [AutofillOffer.Response] → `FillResponse`。**这一层不做任何判断。**
 *
 * 判断全在 [AutofillOffer] 里（30 条用例），洗字全在 [AutofillRow] 里（26 条），
 * 保存那一头挂不挂、看哪几个框全在 [SavePlan] 里（28 条），
 * 这个文件只做三件平台活：句柄换 `AutofillId`、值包成 `AutofillValue`、
 * 两行字塞进 `RemoteViews`。哪一天有人想在这儿加一个 `if`，
 * 那个 `if` 十有八九该加在 [AutofillOffer] 里——那边测得到，这边测不到。
 *
 * ── 唯一一处「像判断」的东西：空的时候返回 null ──
 *
 * 一个不含任何 `Dataset`、也没有 `authentication` 的 `FillResponse`
 * 会让系统弹出一条**空的填充条**。那正是决策(174) / 决策(181) 反复说的那件事：
 * 用户对着一条什么都没有的浮层，唯一的结论是「这功能坏了」。
 * 所以这里宁可返回 null（`onSuccess(null)` = 这次不出手），
 * 而不是交一个空壳上去。
 *
 * 这和「[AutofillOffer.Offer] 里一条候选都没有时仍然是 `Offer` 而不是 `Silent`」
 * 并不矛盾：那个空的 `Offer` 会退化成末尾那条「在保险库里搜索」（[searchDataset]），
 * 于是 `count` 至少是 1，响应仍然出得来。**M4-2b-2 补上的就是这一行。**
 * 只有连那一行都装不出来（这一屏一个可填的框都没有、或者
 * `PendingIntent` 建不出来）时，才真的返回 null。
 */
internal object AutofillResponses {

    private const val TAG = "AutofillSvc"

    /**
     * 已解锁：把每一条候选装成一个 `Dataset`。
     *
     * 一个 `Dataset` 可以一次填好同屏好几组框（决策(175)），
     * 而**哪几组能填是 [AutofillOffer.writesFor] 一组一组判过的**——
     * 这里照着 `item.writes` 写就行，不许自己再展开 `plan.forms`。
     */
    fun datasets(
        context: Context,
        parsed: AssistShell.Parsed,
        plan: FillPlan.Plan,
        offer: AutofillOffer.Offer,
        save: SaveInfo?,
        inline: InlineViews.Support?,
    ): FillResponse? {
        val builder = FillResponse.Builder()
        // 内联条上摆哪几格全由 [InlinePlan] 说（34 条用例），这里照着装。
        // slots 和 offer.items **等长且一一对应**，所以下面按下标取，不用自己对号
        val layout = InlinePlan.forOffer(inline?.ask, offer.items, offer.hidden)
        var count = 0
        for ((i, item) in offer.items.withIndex()) {
            val dataset = dataset(context, parsed, item, inline?.presentation(context, layout.slots[i]))
                ?: continue
            builder.addDataset(dataset)
            count++
        }
        // 末尾那条「在保险库里搜索」。**它排在最后是有意的**：
        // 排在最前的话，一个每次都出现、每次都长得一样的入口会先被眼睛抓住，
        // 而它下面那几条才是这一次真正算出来的答案。
        //
        // 内联条上反过来：那一格是**唯一**能看见没进内联的那几条的入口，
        // 所以格数不够时先保它（决策(215)）。两处的先后不一样，说的是同一件事——
        // 让用户看得见全部。
        val search = searchDataset(
            context,
            parsed,
            plan,
            offer.hidden,
            inline?.presentation(context, layout.search),
        )
        if (search != null) {
            builder.addDataset(search)
            count++
        }
        // 这一行只有数字（决策(144)）：包名、主机名、条目名一个都不打
        Log.d(TAG, "候选 ${offer.items.size} 条 → 装出 $count 个 Dataset（含搜索行 ${search != null}）")
        Log.d(TAG, "内联：$layout")

        // 一条都装不出来时**不是直接 null**：这一屏可能仍然值得看着
        // （见 [saveOnly] 那段——新注册那一屏恰恰是「填不出任何东西」和
        // 「最值得存」同时成立的一屏）
        if (count == 0) return if (save != null) saveOnly(save) else null

        if (save != null) builder.setSaveInfo(save)
        return builder.build()
    }

    private fun dataset(
        context: Context,
        parsed: AssistShell.Parsed,
        item: AutofillOffer.Item,
        chip: InlinePresentation?,
    ): Dataset? {
        val presentation = AutofillViews.row(context.packageName, AutofillRow.forItem(item))
        @Suppress("DEPRECATION") // Dataset.Builder(RemoteViews) 在 33 上被 Presentations 取代，
        // 但那条新路要 minSdk 33；这一条在 26..36 上都能用，行为一样
        val builder = Dataset.Builder(presentation)
        // **浮层那一份永远都在，内联只是多挂一份**（决策(214) 的另一半）：
        // 没挂内联的那几条照样出现在浮层里，输入法不认内联时整份也照样出得来。
        // 两条路谁都不能把谁顶掉，这就是「不能两条都不出」的落点
        setChip(builder, chip)
        var wrote = 0
        for (w in item.writes) {
            // 句柄只在这一次请求里有意义（AssistShell 文件头）。换不出来说明
            // 这个框在解析之后消失了（页面自己改了 DOM），跳过它而不是整条作废
            val id: AutofillId = parsed.autofillId(w.handle) ?: continue
            builder.setValue(id, AutofillValue.forText(w.value))
            wrote++
        }
        // 一个字都没写成的 Dataset 点下去什么都不会发生，不如不出现（决策(174)）
        return if (wrote == 0) null else builder.build()
    }

    /* ══════════════════════════ 末尾那条搜索行 ══════════════════════════ */

    /**
     * 「在保险库里搜索」——决策(160) 的落点，决策(181) 欠的那一行。
     *
     * ── 它是**数据集级**认证，不是响应级 ──
     *
     * [unlock] 那一条用的是 `FillResponse.setAuthentication`：整份响应都还没算出来，
     * 因为库锁着，我们连有几条都数不出来。这一条反过来——上面那几条候选已经
     * 实实在在装好了，用户点的只是**其中一行**。用响应级认证会把那几条一起吞掉：
     * 用户点了搜索、进去又改主意退出来，回到填充条上时那几条候选得重新算一次，
     * 而中途库可能已经自动锁定了，于是他看到的是「先解锁」。
     * 数据集级认证只替换它自己那一行，别的原样留着。
     *
     * ── `setValue(id, null)` 那几行不是占位垃圾 ──
     *
     * 一个带认证的 `Dataset` 必须先声明「我覆盖哪几个框」，值给 null 表示
     * 「等认证回来再说」。一个都不声明的话，系统认为这一行填不了任何东西，
     * 它根本不会画出来——表现是「一条候选都没有的时候填充条整个不出现」，
     * 而那正是这一行要治的病。
     *
     * 覆盖的是**这一屏上所有认得出来的框**（同 [unlock]），不只是光标那一个：
     * 用户可能先点了密码框，挑完希望账号也一起填上。
     */
    private fun searchDataset(
        context: Context,
        parsed: AssistShell.Parsed,
        plan: FillPlan.Plan,
        hidden: Int,
        chip: InlinePresentation?,
    ): Dataset? {
        val ids = fillableIds(parsed, plan)
        if (ids.isEmpty()) return null
        val sender = pickSender(context) ?: return null
        val presentation = AutofillViews.row(context.packageName, AutofillRow.forSearch(hidden))
        @Suppress("DEPRECATION") // 同 dataset()：Presentations 那条新路要 minSdk 33
        val builder = Dataset.Builder(presentation)
        setChip(builder, chip)
        for (id in ids) builder.setValue(id, null as AutofillValue?)
        builder.setAuthentication(sender)
        return builder.build()
    }

    /**
     * 挂内联那一格。**分出来是为了那句 SDK 检查只写一遍。**
     *
     * [chip] 不为 null 就已经意味着这台设备是 11+（[InlineViews.from] 是唯一的入口，
     * 低版本上它返回 null），那句检查因此是给编译器和 lint 看的。
     * 写死一句 `if (chip != null)` 而不带版本判断也能跑，但那会在某天有人
     * 从别处造出一个 `InlinePresentation` 时变成一条 10 上的 `NoClassDefFoundError`。
     */
    private fun setChip(builder: Dataset.Builder, chip: InlinePresentation?) {
        if (chip != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setInlinePresentation(chip)
        }
    }

    /* ══════════════════════════ 用户挑完之后交回去的那一个 ══════════════════════════ */

    /**
     * 挑选页上按下「确认填入」之后，装成 `Dataset` 交回 `EXTRA_AUTHENTICATION_RESULT`。
     *
     * **写哪几个框由 [AutofillPick.writes] 说了算，这里一行判断都不做**——
     * 那一个函数是「手动挑只往主表单那一组写」（决策(187)）的唯一落点，
     * 也是最后一道能把前面八个内核文件全部小心作废的地方。
     * 在这儿展开 `plan.forms` 补几个框，就是 AutoSpill 那条路。
     *
     * `presentation` 这一份 `RemoteViews` 用户其实看不到（认证结果一回来
     * 系统就直接把值填进去了），但 `Dataset.Builder` 要求它非空。
     * 照样走一遍 [AutofillRow.forPick] 那道洗：这个文件的规矩是
     * 「凡是交出去的字都先洗过」，为一个看不见的对象破例，
     * 等于给下一个人留了一个「这里可以不洗」的先例。
     */
    fun picked(
        context: Context,
        parsed: AssistShell.Parsed,
        writes: List<FillPlan.Write>,
        row: AutofillPick.Row,
    ): Dataset? {
        val presentation = AutofillViews.row(context.packageName, AutofillRow.forPick(row))
        @Suppress("DEPRECATION")
        val builder = Dataset.Builder(presentation)
        var wrote = 0
        for (w in writes) {
            val id: AutofillId = parsed.autofillId(w.handle) ?: continue
            builder.setValue(id, AutofillValue.forText(w.value))
            wrote++
        }
        Log.d(TAG, "手动挑 → 写 $wrote 格")
        return if (wrote == 0) null else builder.build()
    }

    /**
     * 拉起挑选页的 `IntentSender`。
     *
     * `FLAG_MUTABLE` 的理由和 [unlockSender] 一模一样，而且这一处更要紧：
     * 挑选页拿不到 `EXTRA_ASSIST_STRUCTURE` 的话，它连「这一屏有哪几个框」
     * 都不知道，[AutofillPick.refusal] 会一路走到「没有可填的地方」，
     * 用户点开看到的是一句拒绝——而屏幕上明明有两个空着的输入框。
     *
     * [REQ_PICK] 必须和 [REQ_UNLOCK] 不同：`PendingIntent` 按
     * （requestCode, Intent）配对复用，两个共用一个 code 会互相顶掉，
     * 表现是「点解锁进了挑选页」或者反过来。
     */
    private fun pickSender(context: Context): IntentSender? = runCatching {
        val intent = Intent(context, AutofillPickActivity::class.java)
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        PendingIntent.getActivity(context, REQ_PICK, intent, flags).intentSender
    }.onFailure {
        Log.w(TAG, "建不出挑选页入口：${it.javaClass.simpleName}")
    }.getOrNull()

    /**
     * 锁着：不出任何 `Dataset`，只出一条「先解锁」。
     *
     * `setAuthentication` 收的是**整份响应**的认证：用户点那一行，系统拉起
     * [AutofillUnlockActivity]，那边解完锁把一份新的 `FillResponse`
     * 放回 `EXTRA_AUTHENTICATION_RESULT`，系统当场换上。
     *
     * 第一个参数那串 `AutofillId` 是「这份认证覆盖哪几个框」。给的是
     * **这一屏上所有认得出来的框**，不只是光标所在那个：用户可能先点了密码框，
     * 解锁完希望账号也一起填上。
     */
    fun unlock(
        context: Context,
        parsed: AssistShell.Parsed,
        plan: FillPlan.Plan,
        save: SaveInfo?,
        inline: InlineViews.Support?,
    ): FillResponse? {
        val ids = fillableIds(parsed, plan)
        // 一个可填的框都没有，但这一屏仍然可能值得看着（见 [saveOnly]）
        if (ids.isEmpty()) return if (save != null) saveOnly(save) else null
        val sender = unlockSender(context) ?: return if (save != null) saveOnly(save) else null
        val presentation = AutofillViews.row(context.packageName, AutofillRow.forUnlock())
        val solo = InlinePlan.forUnlock(inline?.ask)
        val chip = inline?.presentation(context, solo.slot)
        Log.d(TAG, "先解锁那一条的内联：$solo")
        val builder = FillResponse.Builder()
        // **这一条是内联最要紧的一条**：自动锁定过之后，用户在别人的应用里
        // 看到的就只有它。挂不上内联的话，他的键盘上一格都不出现——
        // 而那正是「装了个填充服务却什么都没发生」的那种体验。
        //
        // 两个分支交出去的东西完全一样（同一批 ids、同一个 sender、同一份浮层），
        // 差别只有多不多带一格内联。11 以下走下面那条，同 M4-2a-2② 那天写下来的样子
        if (chip != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") // 四参那个在 33 上被 Presentations 取代，同 dataset()
            builder.setAuthentication(ids, sender, presentation, chip)
        } else {
            @Suppress("DEPRECATION")
            builder.setAuthentication(ids, sender, presentation)
        }
        // **锁着也照样挂。** 保存这一路一次库都不用打开（`SavedFields` /
        // `SaveCapture` 通篇没碰过 `VaultSession`），要解锁是等用户按下系统那个
        // 保存框、站到我们自己的确认页上之后的事（`AutofillSaveFlow.Unlocking`）。
        // 反过来写——「锁着就不挂」——的代价是：自动锁定过了的手机上，
        // 用户登录成功后一次也不会被问要不要存，而那恰恰是他最需要被问的时候
        // （他刚打完一个新密码，屏幕上还看得见）。
        if (save != null) builder.setSaveInfo(save)
        return builder.build()
    }

    /* ══════════════════════════ 保存那一头 ══════════════════════════ */

    /**
     * 一条 `Dataset` 都没有、只挂着 `SaveInfo` 的响应。
     *
     * ── 它守的是这条链上最值钱的一屏 ──
     *
     * **新注册。** 用户在一个库里一条都没存过的站上注册账号：填充这一侧对那一屏的
     * 答案本来就是「没什么可填」（[AutofillOffer.Offer] 里 `items` 为空，
     * 而 M4-2b-2 之后那还会退化成末尾那条搜索行；连那一行都装不出来时 `count == 0`）。
     * 如果这时候直接 `onSuccess(null)`，系统这一次会话里就没有任何 `SaveInfo`——
     * **于是他注册完，保存框一次都不出现**。
     *
     * 那是最值钱的一次：一个刚生成、刚被网站接受、且**只存在于他短期记忆里**的密码
     * （同 [SavePlan.of] 里 required 只放一个的理由）。错过它的代价是他从此
     * 进不去那个账号，而这件事发生的当天没有任何症状——
     * 屏幕上什么都没弹，他也不知道本该弹。
     *
     * 所以「填不出东西」和「不值得看着」在这一层必须是两件事。
     * 判「值不值得看着」的是 [SavePlan.decide]（28 条用例），不是这儿。
     */
    fun saveOnly(save: SaveInfo): FillResponse =
        FillResponse.Builder().setSaveInfo(save).build()

    /**
     * [SavePlan.Decision] → `SaveInfo`。**这一层不做任何判断**，同这个文件的其余部分。
     *
     * 挂不挂、看哪几个框、哪一个必填、旗子加不加，全在 [SavePlan] 里
     * （28 条用例）；这里只做三件平台活：句柄换 `AutofillId`、
     * 算那个类型位、`setOptionalIds` / `setFlags`。
     *
     * 返回 null 的三种情形都不是故障：
     *   · [SavePlan] 说这一屏不挂（[SavePlan.Decision.Skipped]，四档各有一句实话）；
     *   · 必填那个句柄换不出 `AutofillId`——那个框在解析之后没了。
     *     `SaveInfo.Builder` 收到空的必填数组会**抛** `IllegalArgumentException`，
     *     而这里是系统回调里，抛出去就是别人的应用旁边弹一条崩溃提示；
     *   · 可选那几格全都换不出来。这一档仍然挂得住（必填还在），
     *     所以它不返回 null，只是 `setOptionalIds` 那一行跳过——
     *     空数组在那个方法上也是要抛的。
     *
     * ── 那个类型位 ──
     *
     * `SAVE_DATA_TYPE_PASSWORD` 永远有：[SavePlan] 已经把「一个密码框都没有」的一屏
     * 挡在外面了（[SavePlan.Skip.NoPasswordField]），所以走到这儿一定有密码。
     * 有账号框时再或上 `SAVE_DATA_TYPE_USERNAME`——那一位只影响系统那个保存框上
     * 印的是「保存密码？」还是「保存密码和用户名？」，
     * 而**说错了的代价不小**：用户按下去之后落到我们自己的确认页上，
     * 那一页会逐条摆出改的是哪几样（[AutofillSaveFlow.CHANGES_HEADING]），
     * 两处对不上的话他第一反应是我们在偷偷多存东西。
     */
    fun saveInfo(parsed: AssistShell.Parsed, decision: SavePlan.Decision): SaveInfo? {
        if (decision is SavePlan.Decision.Skipped) {
            // 只打档名，不打包名主机名（决策(144)）。M4-4 的关于页会把
            // SavePlan.note 那四句摆给用户，这一行只是给日志
            Log.d(TAG, "这一屏不看着：${decision.why.name}")
            return null
        }
        val info = (decision as SavePlan.Decision.Hang).info

        val required = info.required.mapNotNull { parsed.autofillId(it) }.toTypedArray()
        if (required.isEmpty()) {
            Log.d(TAG, "必填那个框换不出 AutofillId，这一屏不看着")
            return null
        }
        val optional = info.optional
            .mapNotNull { parsed.autofillId(it) }
            .filter { !required.contains(it) }
            .toTypedArray()

        var type = SaveInfo.SAVE_DATA_TYPE_PASSWORD
        if (info.wantsUsername) type = type or SaveInfo.SAVE_DATA_TYPE_USERNAME

        val builder = SaveInfo.Builder(type, required)
        if (optional.isNotEmpty()) builder.setOptionalIds(optional)
        if (info.saveOnAllViewsInvisible) {
            builder.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
        }
        Log.d(
            TAG,
            "看着 ${required.size} 必填 + ${optional.size} 可选" +
                "（${info.kind.name}，旗子=${info.saveOnAllViewsInvisible}）",
        )
        return builder.build()
    }

    /** 这一屏上「有可能被填」的那些框。顺序稳定（`FillPlan` 那边已经保证）。 */
    fun fillableIds(parsed: AssistShell.Parsed, plan: FillPlan.Plan): Array<AutofillId> {
        val out = ArrayList<AutofillId>(4)
        for (form in plan.forms) {
            for (t in form.targets) {
                parsed.autofillId(t.handle)?.let { if (!out.contains(it)) out += it }
            }
        }
        return out.toTypedArray()
    }

    /**
     * 拉起解锁跳板页的 `IntentSender`。
     *
     * **`FLAG_MUTABLE` 是必须的**（31+）：系统会往这个 `Intent` 里塞
     * `EXTRA_ASSIST_STRUCTURE` 和 `EXTRA_CLIENT_STATE` 再发出去。
     * 写成 `FLAG_IMMUTABLE` 的话，跳板页拿到的是一个空 Intent——
     * 它解完锁却不知道该为哪一屏组装响应，于是永远回一个 `RESULT_CANCELED`，
     * 表现是「点了先解锁，指纹也过了，可什么都没填上」，而且不报任何错。
     *
     * 反过来说，可变 `PendingIntent` 的那条老风险（别人拿去改成别的 Intent）
     * 在这里不成立：它交出去的对象只到系统的 autofill 服务手里，
     * 而 `setPackage` 已经把去处钉死在本应用上。
     */
    private fun unlockSender(context: Context): IntentSender? = runCatching {
        val intent = Intent(context, AutofillUnlockActivity::class.java)
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        PendingIntent.getActivity(context, REQ_UNLOCK, intent, flags).intentSender
    }.onFailure {
        Log.w(TAG, "建不出解锁跳板：${it.javaClass.simpleName}")
    }.getOrNull()

    private const val REQ_UNLOCK = 0x10CA

    /** 见 [pickSender]：**必须**和 [REQ_UNLOCK] 不同。 */
    private const val REQ_PICK = 0x10CB
}
