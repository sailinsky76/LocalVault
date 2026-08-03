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

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import cn.localvault.app.BuildConfig
import cn.localvault.app.VaultApp
import cn.localvault.app.core.session.VaultSession

/**
 * 自动填充服务本体。**这个文件通篇只有管道，没有一条规则。**
 *
 * 一次 `onFillRequest` 走的是这么一条直线，每一站都在别处测过：
 *
 * ```
 *   AssistStructure
 *     → AssistShell.parse        句柄 ↔ AutofillId，走树（StructureRules，34 条）
 *     → FillPlan.forRequest      切组、各算各的归属、填哪几个框（65 条）
 *     → AutofillOffer.respond    出什么、每组各判一次归属（30 条）
 *     → AutofillResponses        装成 FillResponse（没有判断）
 * ```
 *
 * 挂 `SaveInfo` 那一支并排在旁边，**和上面那条链各走各的**
 * （`SavePlan.decide` → `AutofillResponses.saveInfo`）：一屏「填得出什么」和
 * 「值不值得看着」是两个问题，有一屏上两边的答案正好相反（`SavePlan` 文件头）。
 * 保存请求那一条见 [onSaveRequest]。
 *
 * 想在这儿加一个 `if` 之前先停一下：它十有八九该加在 `AutofillOffer` 里。
 * 这个文件跑在**别人的应用**触发的一次系统回调里，既没有界面也没有用例，
 * 加在这儿的判断是这条链上唯一一段没人看得见的代码。
 *
 * ── 它和主进程是同一个进程 ──
 *
 * 服务和 `MainActivity` 共用一个 [VaultApp]，也就共用同一个
 * [cn.localvault.app.core.session.VaultSession]。所以「库有没有解锁」
 * 不需要跨进程问任何人，读一下 `state.value` 就是。
 *
 * 这也意味着**自动锁定那套相位对填充是有效的**：用户切到浏览器，
 * `MainActivity.onStop` 已经把倒计时点着了；超时之后这里读到的就是 `Locked`，
 * 于是填充条上只剩一条「先解锁」。这正是要的行为——
 * 一个「界面锁着、填充照出」的管理器，等于把自动锁定这个设置变成了装饰。
 *
 * ── 失败一律 `onSuccess(null)` ──
 *
 * `FillCallback` 有一个 `onFailure(CharSequence)`，那句话会**画在填充条上**，
 * 出现在别人的应用里。它能说的没有一句是用户此刻用得上的
 * （「结构解析失败」对一个正在登录的人意味着什么？），
 * 却给了旁边那个应用一个探针：反复变换页面结构，看我们什么时候开口。
 * 所以这里从不 `onFailure`：出不了手就安静地不出手，理由留在 logcat 里。
 */
class VaultAutofillService : AutofillService() {

    private val app: VaultApp get() = application as VaultApp

    /**
     * 浏览器身份核验。**按服务实例缓存**（[AndroidHostTrust] 内部按包名记一份）：
     * 一屏上可能有好几组框，每组都要问一次，而查一次签名要跨进程。
     */
    private val hostTrust: AndroidHostTrust by lazy { AndroidHostTrust(packageManager) }

    /**
     * 用户那几项自动填充设置。**服务实例缓存的是这个读取器，不是读到的值**——
     * 值每次请求现读（见 [AutofillPolicy.respectOptOut] 上那段）。
     */
    private val policy: AutofillPolicy by lazy { AutofillPolicy(this) }

    /**
     * 第几次填充请求。**只给 [AutofillDebug] 划边界用**：同一个会话里系统会问好几次
     * （换了个框、页面自己变了），那几次在 logcat 上是连着的一片，
     * 不划开就很容易把上一次的框读成这一次的。release 包里没人读它。
     */
    private var requestSeq = 0

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        // 系统在用户换了框、或者页面自己变了的时候会撤回这一次请求。
        // 撤回之后再 onSuccess 会挨一条警告（严重时是 IllegalStateException），
        // 所以答复之前对一下这个旗子。
        var cancelled = false
        cancellationSignal.setOnCancelListener { cancelled = true }

        val response = runCatching { compose(request) }
            .onFailure { Log.w(TAG, "这次不填：${it.javaClass.simpleName}") }
            .getOrNull()

        if (cancelled) {
            Log.d(TAG, "请求已被撤回，不答复")
            return
        }
        callback.onSuccess(response)
    }

    private fun compose(request: FillRequest): FillResponse? {
        AutofillDebug.requestStart(++requestSeq)

        // 一次请求可能带着好几个 FillContext（同一个会话里的历次结构快照）。
        // 要的是**最后那一个**——它才是屏幕现在的样子。
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            Log.w(TAG, "请求里没有结构")
            return null
        }

        // 拿不到 activityComponent 就一个框都不收（决策(177)）
        // 策略在这一句现读。挑选页和解锁页那两次重新解析读的是同一份
        // SharedPreferences，所以三条路上的判定一定一致（AssistShell.parse 上那段）。
        val parsed = AssistShell.parse(structure, policy.respectOptOut) ?: return null
        // 四道排除、四档证据、分组、主表单——正式日志只说结论，这儿说依据（debug 包限定）
        AutofillDebug.verdicts(parsed.context)
        val plan = FillPlan.forRequest(parsed.context)
        val state = app.session.state.value

        // 输入法要不要内联那几格（Android 11+）。**读出来就完了，一个判断都不做**：
        // 摆几格由 `InlinePlan` 说，装成平台对象由 `InlineViews` 做。
        // 这里拿到 null 的三种情形——系统是 10 及以下、输入法不支持内联、
        // 这一次它不想要——在下游是同一档（`InlinePlan.Why.NoRequest`），
        // 表现都是走浮层那条老路，一条候选都不会因此少
        val inline = InlineViews.from(request)

        // 「这一屏要不要看着」和「这一屏填得出什么」是**两个独立的问题**，
        // 各问一次（[SavePlan] 文件头那段：一个只有新密码框的改密码页，
        // 两边的答案正好相反）。挂不挂全由 SavePlan 说，这儿只补一条它答不了的：
        // **这台设备上还没有库的时候不挂。** SavePlan 不知道库的事（它没碰过
        // VaultSession），而没有库时用户按下系统那个保存框，落到确认页上会走
        // AutofillSaveFlow.Leaving——安静关掉。向他要一次确认再什么都不做，
        // 比一开始就不问糟得多（同 SavePlan 文件头「多挂一屏」那一条）。
        val save = if (state is VaultSession.State.NoVault) {
            null
        } else {
            AutofillResponses.saveInfo(
                parsed,
                SavePlan.decide(parsed.context, BuildConfig.APPLICATION_ID),
            )
        }

        return when (
            val answer = AutofillOffer.respond(
                state = state,
                plan = plan,
                trust = hostTrust,
                // 不写死字符串：debug 构建的包名带着 `.debug` 后缀，
                // 写死的话「不往自己身上填」这条在 debug 包上是失效的
                selfPackage = BuildConfig.APPLICATION_ID,
            )
        ) {
            is AutofillOffer.Silent -> {
                // 只打原因，不打包名和主机名（决策(144)）
                Log.d(TAG, "不出手：${answer.why.name}")
                // **但「不出填充条」不等于「不看着」**：新注册那一屏正是
                // 填不出任何东西、同时最值得存的一屏（见 AutofillResponses.saveOnly）
                save?.let { AutofillResponses.saveOnly(it) }
            }
            is AutofillOffer.Unlock -> AutofillResponses.unlock(this, parsed, plan, save, inline)
            is AutofillOffer.Offer ->
                AutofillResponses.datasets(this, parsed, plan, answer, save, inline)
        }
    }

    /**
     * 保存请求：用户按下了系统那个保存框。
     *
     * **这个回调一个字都不往库里写。** 待办上那句话在这一层是硬的：
     * 落盘前要让用户看见改的是哪一条、哪几个字段。整条读值链
     * （[SaveShell] / [SaveCapture] / [SavedFields]）通篇没有 `VaultSession`，
     * 落盘只在用户自己按下确认页那个按钮时发生，全工程只有 `AutofillSaveActivity.commit` 一处。
     * 一个能在用户不知情时往保险库里写东西的回调，比不能保存糟得多。
     *
     * 唯一碰 `VaultSession` 的地方是 [notWorthAsking]，而它**只读**、算完就丢：
     * 它回答的是「这一次要不要打扰用户」，不是「要写什么」（决策(226)）。
     *
     * 一次 `onSaveRequest` 走的是这么一条直线，每一站都在别处测过：
     *
     * ```
     *   SaveRequest
     *     → AssistShell.parse       **重新解析**，句柄只在一次 parse 里有意义
     *     → SavePlan.decide         看哪几个框（28 条）
     *     → SaveShell.values        唯一一处 getAutofillValue()
     *     → SaveCapture.capture     收成 SaveContext（这一步的用例）
     *     → AutofillSave.outcome    库解锁着的话，先问一句「值得打扰吗」（只读，决策(226)）
     *     → SaveHandoff.offer       换成一张票（明文不进 Intent）
     *     → onSuccess(IntentSender) **由系统**拉起确认页（28+；26/27 走 handOff）
     * ```
     *
     * ── 为什么要**重新** parse 一遍 ──
     *
     * 手上明明有填充那一刻算好的 `Parsed` 和 `SavePlan.Info`，存成字段复用它们
     * 是这一层最自然的写法，而它是错的：句柄就是先序遍历的序号
     * （[AssistShell.Parsed.autofillId] 文件头），而从挂 `SaveInfo` 到用户提交，
     * 中间隔着一次登录成功——网页那一侧 DOM 已经换过一批节点了。
     * 拿旧句柄读新结构，读到的不是「没有值」就是**另一个框里的值**，
     * 而后者会把一个手机号、一段地址当成密码存进库。
     *
     * 何况服务实例根本不保证还是那一个：`AutofillService` 会被系统解绑
     * （[onDisconnected]），而 `SaveRequest` 里带着的结构快照是自足的。
     *
     * ── 永远 `onSuccess`，从不 `onFailure` ──
     *
     * 理由和 [onFillRequest] 那一段一字不差：`SaveCallback.onFailure(CharSequence)`
     * 那句话会画在**别人的应用**上面，而它能说的没有一句是此刻用户用得上的。
     * `onSuccess` 在这里的语义只是「这次请求我收下了」，不是「已经存好了」——
     * 存没存成由确认页说，而那一页是用户自己按下按钮的。
     *
     * ── 确认页必须由**系统**去拉，不能自己 `startActivity`（决策(221)）──
     *
     * 这一条是 M4-3b 那天写错、而且**错了整整一个版本都没有任何症状**的地方。
     * 当时的写法是在 [capture] 末尾自己 `startActivity`，理由看着很硬：
     * 服务和确认页同进程同 APK，加一个 `FLAG_ACTIVITY_NEW_TASK` 就完了。
     *
     * 它在 Android 10（API 29）及以上是不通的。这个回调跑在一个**没有任何可见窗口**
     * 的进程里（用户正站在别人的登录页上），也就是后台启动限制的正靶心：
     * `AutofillService` 由 `system_server` 绑定，而 system_server 不是「可见应用」，
     * 所以那条豁免不成立。真机上的表现是——
     *
     *   · 系统的保存框正常弹出（`SaveInfo` 那一支是好的）；
     *   · 用户按下「更新」，框消失；
     *   · **确认页一次都不出现，库里什么都没变**；
     *   · 而且 `startActivity` **不抛任何异常**，只在 `ActivityTaskManager` 上留一行
     *     `Background activity launch blocked`。于是 [handOff] 里那个
     *     `runCatching { }.onFailure { SaveHandoff.clear() }` 一次也不触发，
     *     槽里那份刚读到的明文密码原地躺满 [SaveHandoff.TTL_MILLIS]——
     *     `SaveHandoff` 三条纪律的第 2、3 条都长在确认页上，那一页没起来，一条都不会走。
     *
     * 正确的路是平台早就备好的那个重载：`SaveCallback.onSuccess(IntentSender)`（API 28）。
     * 交出去的那个 `IntentSender` 由系统**从正在被填的那个 Activity 的上下文**启动，
     * 因此它压根不是一次后台启动。这也是为什么这里必须是 `PendingIntent` 而不是
     * `startActivity`：和填充那一侧的解锁跳板、挑选页（[AutofillResponses.unlockSender] /
     * `pickSender`）走的是同一条路——**这条链上三个页面，从此没有一个是我们自己拉起来的。**
     *
     * 26/27 上没有那个重载，但那两版也没有后台启动限制，所以旧路原样留着兜底
     * （[handOff]），只在 `Build.VERSION.SDK_INT < P` 时才走。
     *
     * ── 交给系统还不够：那个 `Intent` 上不许有 `FLAG_ACTIVITY_NEW_TASK`（决策(222)）──
     *
     * 改成 `onSuccess(IntentSender)` 之后真机上**症状一模一样**：日志走到
     * 「确认页：交给系统拉」为止，之后既没有 `Displayed …AutofillSaveActivity`，
     * 也没有确认页自己那一行，库里什么都没变。
     *
     * 差别在那个 `Intent` 上。`onSuccess(IntentSender)` 的设计是：这一次启动
     * **由被填的那个 Activity 的上下文发起，因而成为那个 Activity 任务栈的一部分**。
     * 而 M4-3c 第一版里 `AutofillSaveActivity.intent` 还带着
     * `FLAG_ACTIVITY_NEW_TASK`——那是从上一条 `startActivity` 老路上带过来的残留
     * （从 `Service` 启动 Activity 时它是硬性要求）。带着它，再叠上清单里的
     * `taskAffinity=""`，系统要做的就不是「往前台那个任务上压一页」而是
     * **新建一个任务**，于是又踩回同一堵墙上：后台新建任务是 BAL 拦得最死的那一种，
     * 而且照样不抛异常、照样没有回调。
     *
     * 对照组一直摆在隔壁：解锁跳板和挑选页走的是同一套「系统代发我们的 `PendingIntent`」，
     * 那两个 `Intent`（[AutofillResponses] 里的 `unlockSender` / `pickSender`）
     * **一个旗子都没加**。三页里唯一起不来的那一页，也是唯一多带一个旗子的那一页。
     *
     * 26/27 那条兜底路仍然需要它，所以它挪进了 [handOff]——那是唯一一条真的从
     * `Service` 上下文启动的路。
     *
     * ── 这条路必须有一个观察点 ──
     *
     * 上面那两版之所以各藏了一整轮，是因为 `onSuccess(sender)` 之后**没有任何回音**：
     * 成功和石沉大海在 logcat 上长得一模一样。所以现在交出去之后守一下
     * （[watchLanding]）：几秒后票还在槽里，就是这一页没起来，当场记一行、
     * 顺手把那份没人来取的明文清掉。
     *
     * ── 两种成功答复各调一次，而且必须**立刻**调 ──
     *
     * 平台的要求是 `onSuccess()` 或 `onSuccess(IntentSender)` 二者之一、当场调、只调一次。
     * 改成这样之后「先启动页面、再答复系统」那个顺序问题自然没了：答复本身就是启动。
     */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // 收值可能抛（最外面那圈是别人的应用），抛了就当这次收不出东西。
        // 注意 capture 现在**只放票、不拉页面**：拉页面是下面这几行的事
        val ticket = runCatching { capture(request) }
            .onFailure { Log.w(TAG, "这次不存：${it.javaClass.simpleName}") }
            .getOrNull()

        if (ticket == null) {
            // 收不出东西：安静收下这次请求。槽里也没东西——capture 只在
            // 真收出一份之后才 offer（见那边最后两行）
            callback.onSuccess()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sender = saveSender(ticket)
            if (sender == null) {
                // 建不出入口 = 确认页永远不会起来 = 没人来取。
                // 这一句就是 handOff 里那次 clear 的等价物，理由一字不差
                SaveHandoff.clear()
                callback.onSuccess()
            } else {
                // **这一行是这条路唯一的观察点**，不是调试残留：
                // 「交给系统」和「自己拉」在日志上原来长得一模一样（两边成功时都不打字，
                // 而自己拉那条被 BAL 拦下时也不打字），于是「装的是哪个包」在 logcat 上
                // 分不出来。分不出来，这个 bug 就还能再藏一次。
                // 它只有一个档名，没有票号、没有包名（决策(144)）
                Log.d(TAG, "确认页：交给系统拉（onSuccess(IntentSender)）")
                callback.onSuccess(sender)
                // 交出去之后这条链就没有回音了（见 watchLanding）。守一下。
                watchLanding(ticket)
            }
            return
        }

        // 26/27：没有 onSuccess(IntentSender)，也没有后台启动限制
        Log.d(TAG, "确认页：自己拉（SDK<28）")
        handOff(ticket)
        callback.onSuccess()
    }

    /**
     * 读值那一段。**收出来的那一份放进 [SaveHandoff]，返回那张票**；收不出来时返回 null。
     *
     * **这个方法里一行判断都没有**，每一站的规矩都在它自己的文件里：
     * 看哪几个框是 [SavePlan]，读值是 [SaveShell]，一格一格收是 [SaveCapture]，
     * 存不存得成是 [AutofillSave]，摆哪一屏是 [AutofillSaveFlow]。在这儿加一个 `if`，
     * 就是在这条链上唯一一段没人看得见、也没有用例的代码里加。
     *
     * ── 为什么它只放票、不拉页面 ──
     *
     * 拉页面的方式在 26/27 和 28+ 上是两条不同的路（[onSaveRequest] 那段长注释），
     * 而**放票这件事两条路上一模一样**。分开之后这个方法回到了它该有的样子：
     * 读一遍值，交出一张票，一句平台调用都不做。
     *
     * `SaveHandoff.offer` 仍然排在拉页面之前，理由和原来一字不差（见 [handOff]）：
     * 页面起来之后才放进槽，会出现页面已经 `take` 过一次（拿到 null 走人）、
     * 我们随后才把东西放进去的时序。
     *
     * **注意这个方法一个字都没往库里写**：整条读值链
     * （[SaveShell] / [SaveCapture] / [SavedFields]）通篇没有 `VaultSession`。
     * 落盘只在用户自己按下确认页那个按钮时发生，那一处在
     * `AutofillSaveActivity.commit` 里，全工程只有一处。
     */
    private fun capture(request: SaveRequest): SaveHandoff.Ticket? {
        AutofillDebug.saveRequestStart()

        // 同 compose()：要的是**最后那一个**结构快照，它才是用户提交那一刻的样子
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            Log.w(TAG, "保存请求里没有结构")
            return null
        }
        // 同 compose()：策略现读。挂 SaveInfo 那一刻用的是哪一档，
        // 到用户提交时可能已经改了，而这里要的是**现在**这一档。
        val parsed = AssistShell.parse(structure, policy.respectOptOut) ?: return null

        val decision = SavePlan.decide(parsed.context, BuildConfig.APPLICATION_ID)
        if (decision is SavePlan.Decision.Skipped) {
            // 走到这儿说明页面在中间变过（挂 SaveInfo 那一刻它还是值得看着的）。
            // 只打档名（决策(144)）
            Log.d(TAG, "这一屏现在不值得看着了：${decision.why.name}")
            return null
        }
        val info = (decision as SavePlan.Decision.Hang).info

        // 这个 Values 闭包里抱着一屏明文，包括用户刚打的密码。
        // 它的寿命就是下面这一句（SaveShell 文件头末段）：不许存成字段
        val captured = SaveCapture.capture(
            info = info,
            values = SaveShell.values(structure, parsed),
            appLabel = appLabel(info.origin.hostApp),
        )

        // 记账这一行只有数字。tooLong / control 不为零意味着我们把某个框判错了
        // （一整段文本或者一串圆点被当成了密码），那是需要在 logcat 里看得见的信号
        Log.d(TAG, "收值：${captured.tally}")

        // 库此刻解锁着的话，这一次值不值得打扰在这儿就能知道（决策(226)）
        if (notWorthAsking(captured.context)) return null

        // 放票这一步在两条路上都一样，所以留在这儿；拉页面的那一步不在这儿
        return SaveHandoff.offer(captured.context, System.currentTimeMillis())
    }

    /**
     * **这一次根本不用问用户。** 只在库当下解锁着时才答得出来，锁着一律返回 false。
     *
     * ── 它修的是什么 ──
     *
     * 用自动填充登录成功之后，系统那个保存框**照样会弹**：手机号那个输入框在收到
     * 我们填进去的 `18623456789` 之后自己排版成了 `186 2345 6789`，
     * 于是框架比对「填进去的值」和「提交时的值」发现不一样，判定用户改过，就问一次。
     * 这一层管不着别人的输入框，但管得着后面那一串——原来的表现是：
     * 系统框 → 通知 → 用户点开 → 一页写着「这一份库里已经有了，不用存」。
     * **为一件确定不用做的事打扰了三次。**
     *
     * `AutofillSave.outcome` 本来就能算出这一档（[AutofillSave.Reason.AlreadyStored]），
     * 只是原来算得太晚——在确认页上。把它提到这儿，那三次打扰一次都不发生。
     *
     * ── 这一句没有破坏「这个回调一个字都不往库里写」 ──
     *
     * 它**只读** `state.value`，算出来的那份提案当场丢掉。真正的提案由确认页
     * 拿**那一刻**的库内容重算（决策(152)，`AutofillSave.outcome` 文件头那段）：
     * 从这儿到用户按下确认中间隔着一次通知、可能还隔着一次解锁，库完全可能已经变了。
     * 所以这儿算出来的东西只配回答一个问题——「要不要往下走」。
     *
     * ── 锁着时为什么不问 ──
     *
     * 锁着就是数不出库里有什么，而**宁可让用户白走一趟，也不能因为库锁着就把他刚打的
     * 密码丢掉**（决策(197) 那一段，一字不改）。刚注册完那一次正是最值钱、
     * 也最不可能再打一遍的一次。
     */
    private fun notWorthAsking(context: SaveContext): Boolean {
        val state = app.session.state.value as? VaultSession.State.Unlocked ?: return false
        val outcome = runCatching {
            AutofillSave.outcome(
                context = context,
                entries = state.data.entries,
                trust = hostTrust,
                ownPackage = BuildConfig.APPLICATION_ID,
            )
        }.onFailure {
            // 算不出来就当值得问：漏问一次的代价是丢一个密码，多问一次只是多一次点击
            Log.w(TAG, "算不出这一次要不要问：${it.javaClass.simpleName}")
        }.getOrNull() ?: return false

        if (outcome !is AutofillSave.Outcome.Silent) return false

        // 静默之前再问一句：这一档静默了，用户会不会以为自己刚存上了（决策(234)）。
        // 判据是纯的，在 AutofillSave 里，**这儿一个数都不用数**——
        // 决策(232)/(233) 那两版在这里数过库，两版都错在同一个地方（见那边的注释）
        if (!AutofillSave.safeToStaySilent(outcome.reason)) {
            // 这一行是这条岔路唯一的观察点：静默和「问了但用户按了取消」
            // 在日志上原来长得一样。同上，只打档名（决策(144)）
            Log.d(TAG, "这一档不许静默，照样问：${outcome.reason.name}")
            return false
        }

        // 只打档名，不打包名、不打条目名（决策(144)）
        Log.d(TAG, "这一次不打扰：${outcome.reason.name}")
        return true
    }

    /**
     * 拉起确认页用的那个 `IntentSender`。交给 `SaveCallback.onSuccess(IntentSender)`，
     * 由**系统**去启动——这一条是这次修复的核心，理由在 [onSaveRequest] 那段注释里。
     *
     * ── `FLAG_CANCEL_CURRENT` 不是可选的 ──
     *
     * `PendingIntent` 按 (requestCode, Intent) 配对复用，而**配对时 extras 不参与比较**。
     * 不加它，第二次保存请求会拿回第一次那个 `PendingIntent`，里面躺着的是
     * **第一张票**——确认页 `take` 一张早就被取过的旧票，拿到 null，
     * 于是 [AutofillSaveFlow.Leaving]，安静走人。
     * 症状和这次修的那个 bug 一模一样（「按下更新什么都没发生」），
     * 只是它只在第二次之后才发作，所以更不容易被发现。
     *
     * `FLAG_ONE_SHOT` 是同一件事的另一半：这张票本来就只该被用一次
     * （[SaveHandoff.take] 取一次就清）。
     *
     * ── 这一处和另外两个 sender 相反，用 `FLAG_IMMUTABLE` ──
     *
     * [AutofillResponses.unlockSender] / `pickSender` 必须可变，因为**系统要往那两个
     * `Intent` 里塞** `EXTRA_ASSIST_STRUCTURE`（那边注释写了写成 IMMUTABLE 的后果）。
     * 这一处反过来：确认页要的东西已经在 extras 里了（一个数字），系统不需要补任何东西，
     * 那就没有任何理由留一个可变的出去。
     *
     * [REQ_SAVE] 必须和 `AutofillResponses` 里那两个 requestCode 都不同，
     * 理由同那边：共用一个 code 的两个 `PendingIntent` 会互相顶掉。
     */
    private fun saveSender(ticket: SaveHandoff.Ticket): IntentSender? = runCatching {
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        PendingIntent.getActivity(
            this,
            REQ_SAVE,
            AutofillSaveActivity.intent(this, ticket),
            flags,
            creatorBalOptions(),
        ).intentSender
    }.onFailure {
        // 只打异常类名（决策(144)）
        Log.w(TAG, "建不出确认页入口：${it.javaClass.simpleName}")
    }.getOrNull()

    /**
     * 交出 `IntentSender` 之后守一下：**这一页到底起没起来。**
     *
     * ── 为什么非要自己守 ──
     *
     * `callback.onSuccess(sender)` 之后这条链**一个回音都没有**。系统拿到
     * `IntentSender` 不是自己启动，而是转交给被填的那个应用去发；发不出去时
     * （后台启动被拦、宿主 Activity 已经销毁、定制系统另有一道开关）
     * 我们这边既没有异常也没有返回值，`onDisconnected` 照常打出来，
     * logcat 上「成功」和「石沉大海」长得一模一样。
     * 这个 bug 前后藏了两版，靠的就是这一段没有观察点。
     *
     * 判据用的是槽本身：确认页 `onCreate`/`onNewIntent` 第一件事就是取票
     * （`AutofillSaveActivity.redeem`），所以「过了 [WATCH_MILLIS] 票还在」
     * 只有一个解释——那一页没起来。中途的解锁屏不影响这个判据：
     * 取票在解锁**之前**（票一到手就没在槽里了）。
     *
     * ── 兜底不是「清掉」，是换一条入口 ──
     *
     * 判定成立时，那一份明文是**没人会来取**的：`SaveHandoff` 三条纪律的第 2、3 条
     * 都长在确认页上，那一页没起来，一条都不会触发。M4-3c 第一版到这里是直接
     * [SaveHandoff.dropIfPending] 掉——安全上没问题，但用户那一侧仍然是
     * 「按下更新什么都没发生」。
     *
     * 现在改成发一条通知（[SaveNotice]）：通知是用户自己点的、由系统代发，
     * 是一条明确的后台启动豁免，**不需要任何人替我们拉页面**。
     * 只有连通知都发不出去（没给权限）时才退回清掉那一路。
     *
     * 明文的寿命不因此变长：它仍然躺在同一个槽里、仍然受 [SaveHandoff.TTL_MILLIS] 管，
     * 通知的 `setTimeoutAfter` 用的就是那个 TTL。
     *
     * 时长取 [WATCH_MILLIS]：够系统把一次 Activity 启动走完（正常是几十毫秒），
     * 又短到用户还没来得及在确认页上做任何事。守望跑在主线程的 `Handler` 上而不是
     * 协程里——服务这一侧没有任何作用域，而 `onSaveRequest` 之后系统当场解绑
     * （日志上那一行「已断开」），跟着服务实例走的东西都会没。进程本身还在
     * （和主界面同进程），几秒的延时消息稳稳跑得完。
     */
    private fun watchLanding(ticket: SaveHandoff.Ticket) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!SaveHandoff.isPending(ticket, System.currentTimeMillis())) return@postDelayed
            // 这一行是这条路上唯一能证明「没起来」的证据。
            // 它只有一个时长，没有票号、没有包名、没有条目名（决策(144)）
            Log.w(TAG, "确认页没起来：${WATCH_MILLIS}ms 后票还在槽里，改走通知")
            if (SaveNotice.post(this, ticket)) return@postDelayed
            // 通知也发不出去（用户没给权限 / 关了通知）：这一份明文再没有任何入口能取到，
            // 留着只会躺满 TTL。按票丢掉——认票才不会误伤中途来的新一份
            SaveHandoff.dropIfPending(ticket, System.currentTimeMillis())
            Log.w(TAG, "这一份没有入口了，已就地清掉")
        }, WATCH_MILLIS)
    }

    /**
     * 创建者侧那半张后台启动许可（34+）。返回 null 表示这台设备上没有这个概念。
     *
     * ── 它是一条「特权链」上我们唯一够得着的那一环 ──
     *
     * 系统拿到 [saveSender] 交出去的 `IntentSender` 之后**不是自己启动**，而是转交给
     * 被填的那个应用：`AutofillManager.AutofillManagerClient.startIntentSender` 里那一句
     * `mContext.startIntentSender(intentSender, null, 0, 0, 0)`——**最后三个 0 的位置本该是
     * `ActivityOptions`**。也就是说发送者那一侧一个字都没授权，而它是别人的代码，我们改不了。
     *
     * 于是整条链只剩创建者这一半，而这一半默认也是关的：targetSdk 35 之后，
     * 创建 `PendingIntent` 的一方不再自动把自己的后台启动特权授出去。
     * 两半都没有 = 拦掉，且**发送方拿不到异常、我们拿不到回调**，
     * 系统只在 logcat 上留一行 `Background activity launch blocked!`
     * （里面 `callingPackage` 是我们、`realCallingPackage` 是那个被填的应用）。
     *
     * ── 但它单独不一定够 ──
     *
     * 授权只是「愿不愿意把特权借出去」，能不能借还要看**我们此刻有没有那份特权**：
     * 判定的另一半是「创建者当下满足某条 BAL 豁免吗」。`onSaveRequest` 跑在一个没有任何
     * 可见窗口的后台服务里，逐条对下来一条都不占（不是 IME、没有 SYSTEM_ALERT_WINDOW、
     * 没有 START_ACTIVITIES_FROM_BACKGROUND）。所以这一句是**必要不充分**：
     * 不加一定不行，加了也未必行。真机上是哪一档，看上面那行系统日志。
     *
     * 写成一个函数而不是塞进 [saveSender]，是因为那句版本判断和 `ActivityOptions`
     * 一起写在参数位上会让那个调用看不出主线是什么。
     */
    private fun creatorBalOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return runCatching {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
                .toBundle()
        }.onFailure {
            // 定制系统上这个方法被改过是有先例的。拿不到就当没有，
            // 交出去的仍然是一个能用的 PendingIntent，只是少了那半张许可
            Log.w(TAG, "拿不到创建者侧许可：${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /**
     * **26/27 专用的兜底**：自己 `startActivity` 拉确认页。
     *
     * 28 及以上一律走 [saveSender] + `onSuccess(IntentSender)`，**不许走这里**——
     * 这条路在 Android 10 及以上会被后台启动限制静默拦下，
     * 而且不抛任何异常（[onSaveRequest] 那段长注释就是这件事的现场记录）。
     * 留着它只是因为 26/27 上那个重载不存在，而那两版也没有那个限制。
     *
     * **进 `Intent` 的只有那张票**（一个数字）。明文为什么不能走 `Intent`，
     * `SaveHandoff` 文件头写了整整一段：`startActivity` 要经过 `system_server`，
     * extras 会被 parcel 出去、排进 `ActivityManager` 的记录、被 `dumpsys` 打出来。
     *
     * ── 拉不起来时必须把槽清掉 ──
     *
     * 那时候槽里躺着一份**没人会来取**的明文密码，一直躺到
     * [SaveHandoff.TTL_MILLIS] 过期，或者进程死掉——而 `SaveHandoff` 三条纪律
     * 的第 2、3 条都长在确认页上，那一页根本没起来，一条都不会触发。
     * 所以这里自己补一次 [SaveHandoff.clear]。
     *
     * 28+ 那条路上这一句的等价物在 [onSaveRequest] 里（`sender == null` 那一支）。
     * 两条路都要有它，是因为「页面起不来」和「没人来取」在这条链上是同一件事。
     */
    private fun handOff(ticket: SaveHandoff.Ticket) {
        runCatching {
            // NEW_TASK 现在加在**这一条路上**，不在 AutofillSaveActivity.intent 里：
            // 从 Service 上下文 startActivity 没有它会当场抛 AndroidRuntimeException，
            // 而 28+ 那条路上有它就等于让系统去新建一个后台任务（决策(222)）。
            startActivity(
                AutofillSaveActivity.intent(this, ticket)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            // 只打异常类名（决策(144)）
            Log.w(TAG, "拉不起确认页：${it.javaClass.simpleName}")
            SaveHandoff.clear()
        }
    }

    /**
     * 承载这一屏的应用**自称**的名字。
     *
     * 读不出来就返回 null，**绝不编一个「未知应用」兜底**（决策(188)）——
     * [AutofillSave.storedUnder] 那边会只写包名。写法和
     * `AutofillPickActivity.appLabel` 一样，那边是给挑选页用的，
     * 这边是给建议名用的（[AutofillSave.suggestedName]）。
     */
    private fun appLabel(packageName: String): String? = runCatching {
        packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
    }.onFailure {
        // 只打异常类名，不打包名（决策(144)）
        Log.d(TAG, "读不到应用名：${it.javaClass.simpleName}")
    }.getOrNull()

    override fun onConnected() {
        Log.d(TAG, "已连接")
    }

    override fun onDisconnected() {
        Log.d(TAG, "已断开")
    }

    private companion object {
        const val TAG = "AutofillSvc"

        /**
         * 确认页那个 `PendingIntent` 的 requestCode。
         *
         * **必须和 `AutofillResponses` 里的 `REQ_UNLOCK`（0x10CA）/ `REQ_PICK`（0x10CB）
         * 都不同**，理由同那边：两个 `PendingIntent` 共用一个 code 会互相顶掉，
         * 表现是「按下保存框进了挑选页」或者反过来。
         */
        const val REQ_SAVE = 0x10CC

        /**
         * 守望那一段等多久（见 [watchLanding]）。
         *
         * 三秒是两头夹出来的：一次 Activity 启动正常在几十毫秒内走完（`Displayed`
         * 那一行就是），给到三秒是把冷启动、低端机、系统正忙都算进去；
         * 再长就会盖到用户已经站在确认页上、正在挑要存哪一条的那段时间——
         * 而那时票早就不在槽里了，判据本来也不会误伤，只是日志会变得不好读。
         */
        const val WATCH_MILLIS = 3_000L
    }
}
