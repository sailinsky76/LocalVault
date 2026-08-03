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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import cn.localvault.app.BuildConfig
import cn.localvault.app.MainActivity
import cn.localvault.app.VaultApp
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.CryptoInfo
import cn.localvault.app.ui.ProvideVaultDeps
import cn.localvault.app.ui.theme.LocalVaultTheme
import cn.localvault.app.ui.util.Fmt

/**
 * 用户在别人的应用里提交了登录表单、按下了系统那个保存框之后，落到的就是这一页。
 *
 * 它是 [AutofillUnlockActivity] / [AutofillPickActivity] 的第三个同胞，共用同一副骨架
 * （`FLAG_SECURE`、自己接自动锁定那两个回调、浮在别人的应用上面、按返回就整个消失），
 * 但和那两个有**三处结构性的不同**：
 *
 *   · 那两页是系统用 `PendingIntent` 拉起来的，手上握着一份 `AssistStructure`；
 *     这一页也是系统用 `PendingIntent` 拉起来的（`SaveCallback.onSuccess(IntentSender)`），
 *     但手上只有一个数字（[SaveHandoff.Ticket]）。明文不进 `Intent`——
 *     理由整整齐齐写在 `SaveHandoff` 文件头上。
 *
 *     **这一条曾经不是这样。** M4-3b 那天这一页是服务自己 `startActivity` 拉起来的，
 *     而那条路在 Android 10 及以上会被后台启动限制静默拦下：保存框弹得出来、
 *     按下去这一页一次都不出现、`startActivity` 还不抛异常。
 *     现场记录在 `VaultAutofillService.onSaveRequest` 的注释里。
 *     26/27 上仍然走 `startActivity`（那两版没有那个限制，也没有那个重载）。
 *   · 那两页交的答卷是 `EXTRA_AUTHENTICATION_RESULT`（交给系统，由系统去填框）；
 *     这一页交的答卷是**往自己的库里写一条**。方向反过来了，代价也就不一样：
 *     那两页做错了是把密码交给不该交的人，这一页做错了是**盖掉用户库里已有的东西**。
 *   · 那两页什么都不做地关掉是完全正当的（[AutofillPickFlow.Leaving]）；
 *     这一页有两档必须先说一句再关（[AutofillSaveFlow.Refused]），
 *     因为那两档只有在用户为它输过一次主密码之后才算得出来（决策(197)）。
 *
 * ── 这个文件里没有一条规则 ──
 *
 * 提案里有什么在 [AutofillSave]（75 条用例），此刻摆哪一屏、每句话怎么说在
 * [AutofillSaveFlow]（58 条中的一半），画成什么样在 [AutofillSaveScreen]。
 * 这里只剩四件平台活：接票取货、按相位摆屏、把用户按下的那一下交给 `VaultSession`、
 * 以及**在该清的三个时机把手上那份明文清掉**。
 *
 * ── 手上那份明文，三个时机必须清 ──
 *
 * `SaveHandoff` 文件头列了三条纪律，这一页是其中第 2、3 条**唯一**的兑现处：
 *
 *   1. 取一次就清 —— [SaveHandoff.take] 自己做了；
 *   2. 页面结束时清 —— [onDestroy]；
 *   3. **自动锁定时清** —— [drop]，由下面那个 `sawUnlocked` 守着。
 *
 * 第 3 条有一处容易写错，而且写错了当天没有任何症状：**不能一看见 `Locked` 就清**。
 * 库锁着正是这条路最常见的入口（决策(204)：锁着也照样挂 `SaveInfo`，
 * 因为「刚注册完那一次」正是最值钱、也最不可能再打一遍的一次）。
 * 进来时就锁着的话，一看见 `Locked` 就清，等于用户还没来得及解锁，
 * 要存的东西已经没了——他解完锁看到的是一屏什么都没有然后自己关掉，
 * 而他刚打的那个密码此刻只存在于他的短期记忆里。
 *
 * 所以 [drop] 只在**曾经解锁过之后又锁上**时才走：`sawUnlocked` 记的就是这件事。
 * 清掉之后下一帧 `hasContext` 变 false，相位自然走到 [AutofillSaveFlow.Leaving]，
 * 这是有意的（[AutofillSaveFlow.Unlocking] 那段：一份在进程里躺了超过一次锁定周期的
 * 明文，宁可丢掉也不留着）。
 *
 * ── [context] 这个字段的寿命 ──
 *
 * 它抱着一份明文密码，是这个进程里唯一一处长命一点的副本。做成
 * `mutableStateOf` 而不是普通字段，是因为相位每一帧都要重新问一次它在不在
 * （同 [AutofillPickActivity.delivered] 那一处的理由）：普通字段清空了不会触发重组，
 * 那一屏确认单会在明文已经被清掉之后继续摆着，按钮照样按得下去。
 *
 * **绝不写进 `savedInstanceState`。** 那是一条明文落盘的路径，
 * 而这一页的宿主浮在别人的应用上面，被系统回收重建的概率比主界面高得多。
 * 真被回收了这一页也回不来——重建后 [SaveHandoff.take] 拿不到第二次，
 * 相位是 [AutofillSaveFlow.Leaving]，安静走人。那正是要的行为。
 */
class AutofillSaveActivity : FragmentActivity() {

    private val app: VaultApp get() = application as VaultApp

    private val hostTrust: AndroidHostTrust by lazy { AndroidHostTrust(packageManager) }

    /**
     * 交接过来的那一份。清掉之后这一页就该走人了，见文件头。
     *
     * 名字刻意不叫 `context`：这是一个 `Activity`，它本身就是一个 `Context`，
     * 两个名字撞在一起之后，某天有人在这儿写 `startActivity(Intent(context, ...))`
     * 会得到一条完全说不通的编译错误，或者更糟——写成了 `this` 而以为是那一份提案。
     */
    private var pending: SaveContext? by mutableStateOf(null)

    /** 已经落盘了没有。落过就走，别的一概不问（[AutofillSaveFlow.phase] 第一条）。 */
    private var committed by mutableStateOf(false)

    /** 用户在确认页上自己换过去的那一条。null = 用默认挑中的那一条。 */
    private var switchedTo: VaultEntry? by mutableStateOf(null)

    /** 落盘失败时那一句。停在原地，提案一个字不动（同 `AddEntryScreen`）。 */
    private var failure: String? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 这一页会摆出用户刚打的账号、他库里那条条目的名称，而且浮在别人的应用之上。
        // 必须在 setContent 之前。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        if (!redeem(intent)) {
            finishQuietly()
            return
        }

        enableEdgeToEdge()
        setContent {
            LocalVaultTheme {
                val state by app.session.state.collectAsState()
                val ctx = pending

                // 不需要知道库的任何事的那三问。**每一帧重算一次没有意义**（入参不变），
                // 但也不值得为它开一个字段——remember 一次就够
                val refusal = remember(ctx) {
                    ctx?.let { AutofillSave.refuse(it, BuildConfig.APPLICATION_ID) }
                }

                val entries = (state as? VaultSession.State.Unlocked)?.data?.entries

                /**
                 * 解锁之后才算得出来的那一份。
                 *
                 * `remember(entries, ctx)` 里那个 `entries` 是**当下**的库内容，
                 * 不是进来时的快照——从 `onSaveRequest` 到用户按下确认，
                 * 中间隔着一次解锁，库完全可能已经在另一个窗口里被改过了
                 * （[AutofillSave.outcome] 文件头那段）。
                 */
                val outcome = remember(entries, ctx) {
                    if (entries == null || ctx == null) {
                        null
                    } else {
                        AutofillSave.outcome(
                            context = ctx,
                            entries = entries,
                            trust = hostTrust,
                            ownPackage = BuildConfig.APPLICATION_ID,
                        ).also {
                            // 「为什么是新增而不是更新」原来在日志上完全看不出来：
                            // Create 有三种成因（这个站库里一条都没有 / 有但不够格被改 /
                            // 够格但账号对不上），三种的修法完全不同。
                            // 这一行只有两个数字和一个布尔，没有账号、没有条目名、
                            // 没有包名（决策(144)）
                            val n = AutofillSave.updatable(ctx.origin, entries, hostTrust).size
                            Log.d(TAG, "提案：$it｜同站可改 $n 条｜这一屏读到账号=${ctx.username != null}")
                        }
                    }
                }

                val phase = AutofillSaveFlow.phase(
                    state = state,
                    hasContext = ctx != null,
                    refusal = refusal,
                    outcome = outcome,
                    committed = committed,
                )

                // 「曾经解锁过」这件事只在这儿记一次，然后由它守住 drop()——见文件头。
                // key 用的是**相位的类型**而不是相位本身：Refused / Confirming 是
                // 普通 class（没有 equals），每一次重组都会是一个新对象，
                // 拿它当 key 等于每帧重跑一次这个块。这里要的是「换了一档才跑」。
                // `ctx` 也在 key 里：`onNewIntent` 换了一份提案之后，相位的**类型**
                // 可能一个字都没变（上一次也停在 Confirming），而 `sawUnlocked` 刚被
                // 归零了。只拿 phase.javaClass 当 key 的话这个块不会重跑，
                // 于是新的这一次从头到尾 `sawUnlocked` 都是 false——库中途被锁上时
                // drop() 不触发，一份明文留在进程里过夜（SaveHandoff 纪律第 3 条失效）
                LaunchedEffect(phase.javaClass, ctx) {
                    // **相位是这一页唯一值得记的东西**，而它原来一行日志都没有：
                    // 「页面起来了但当场 Leaving」和「页面根本没起来」在 logcat 上
                    // 是同一个样子（都什么都不打）。每一档的 toString 都只有档名
                    // ——Confirming 那一档多一个 Create/Update，没有任何内容（决策(144)）
                    Log.d(TAG, "相位：$phase")
                    when (phase) {
                        is AutofillSaveFlow.Working, is AutofillSaveFlow.Confirming ->
                            sawUnlocked = true

                        is AutofillSaveFlow.Unlocking ->
                            if (sawUnlocked) {
                                Log.d(TAG, "摆着确认单被锁上了，手上那份不留")
                                drop()
                            }

                        else -> Unit
                    }
                }

                val headerParams by app.session.headerKdfParamsFlow.collectAsState()
                ProvideVaultDeps(
                    session = app.session,
                    repository = app.repository,
                    quickUnlock = app.quickUnlock,
                    clipboard = app.clipboard,
                    cryptoInfo = CryptoInfo(
                        argon2Available = app.argon2Available,
                        kdfLabel = Fmt.kdfLabel(headerParams ?: app.currentKdfParams()),
                    ),
                ) {
                    when (phase) {
                        is AutofillSaveFlow.Leaving ->
                            LaunchedEffect(Unit) { finishQuietly() }

                        is AutofillSaveFlow.Refused ->
                            AutofillSaveRefusalScreen(
                                reason = phase.reason,
                                onClose = ::finishQuietly,
                            )

                        // 复用跳板页那两屏，一个字都不重写（见 UnlockHost 的注释）
                        is AutofillSaveFlow.Unlocking ->
                            UnlockHost(
                                quick = app.quickUnlock,
                                session = app.session,
                                onOpenApp = ::openAppAndLeave,
                            )

                        is AutofillSaveFlow.Working -> AutofillSaveWorkingScreen()

                        is AutofillSaveFlow.Confirming -> {
                            if (ctx != null) {
                                val shown = remember(phase.proposal, switchedTo, ctx) {
                                    proposalFor(phase.proposal, ctx)
                                }
                                AutofillSaveScreen(
                                    proposal = shown,
                                    storedUnder = remember(ctx) {
                                        AutofillSave.storedUnder(ctx.origin, ctx.appLabel)
                                    },
                                    notes = remember(shown, ctx) {
                                        AutofillSaveFlow.allNotes(shown, ctx.kind)
                                    },
                                    failure = failure,
                                    onSwitchTarget = { switchedTo = it; failure = null },
                                    onCommit = { name -> commit(shown, name) },
                                    onDismiss = ::finishQuietly,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /* ═════════════════════ 接票取货 ═════════════════════ */

    /**
     * 拿 [Intent] 里那张票去 [SaveHandoff] 换一份提案。换到了返回 true。
     *
     * 抽出来是为了 [onNewIntent] 能和 [onCreate] 走**同一段**代码：
     * 这一段里每一档的取舍（没票、票过期、票被取过）都不是平台细节，
     * 两处各写一份的话，某天只有一处被改对。
     */
    private fun redeem(from: Intent?): Boolean {
        // 无论这次取不取得到票，那条通知都该收走：它是这一页的第二条入口，
        // 而这一页已经在屏幕上了。取不到票时留着它更糟——点进来只会是空页面
        SaveNotice.cancel(this)
        val id = from?.getLongExtra(EXTRA_TICKET, 0L) ?: 0L
        if (id == 0L) {
            // 没票。这一页只有一个来路（我们自己的服务），走到这儿说明有人手工拉起了它
            Log.w(TAG, "没有交接票，这次不存")
            return false
        }
        pending = SaveHandoff.take(SaveHandoff.Ticket(id), System.currentTimeMillis())
        if (pending == null) {
            // 页面被回收后重建、或者交接单过期了。两种都安静走人——
            // 摆一屏按下去什么都不会发生的确认单比这个糟得多
            Log.d(TAG, "交接单取不到，这次不存")
            return false
        }
        // **确认页真的起来了**这件事在日志上原来没有任何一行代表它，
        // 于是「页面没起来」和「页面起来了但相位不对」在 logcat 上分不出来。
        // 这一行只说「起来了、票兑上了」，不说票号也不说包名（决策(144)）
        Log.d(TAG, "确认页起来了，交接单已兑")
        return true
    }

    /**
     * **上一次的实例还活着时，新的票走的是这儿，不是 [onCreate]。**
     *
     * 这一条和后台启动那一条是同一类错误——写错了当天没有任何症状，
     * 而症状出现时长得和「这个功能坏了」一模一样：
     *
     * 清单里这一页是 `launchMode="singleTop"` 且 `taskAffinity=""`（自成一个任务，
     * 且 `excludeFromRecents`）。用户上一次被问「要存吗」的时候没有按那两个按钮里的
     * 任何一个，而是按了 Home——这一页于是**没有 finish**，就那么活在一个
     * 他在最近任务里看不见、也就再也回不去的任务里。等他下一次登录、
     * 再按一次系统那个保存框，`singleTop` 会把新 `Intent` 送到这个老实例上。
     * 不接这个回调的话，[onCreate] 不会再跑一遍，**这一次的票没有任何人去取**：
     * 用户看到的就是「按下更新，什么都没发生」。
     *
     * 手上那份旧的明文当然要丢，但**这里绝对不能调 [drop]**，尽管它读起来正合适：
     * `drop()` 除了清手上那一份，还会 [SaveHandoff.clear] 一次——
     * 而槽里此刻装着的正是**这一次**的那一份（服务先 `offer` 才拉页面，
     * 顺序写在 `VaultAutofillService.capture` 上）。调下去就是自己把刚送来的东西
     * 扔了，然后 `take` 拿到 null、安静走人——又一次「按下更新什么都没发生」，
     * 而且只在「上一页还活着」这个前提下才复现。
     * 所以这里只动 [pending] 这一个字段，槽交给 [SaveHandoff.take] 去清
     * （它取一次就清，是纪律第 1 条）。
     *
     * 四个记着「上一次做到哪」的字段一起归零，否则新的这一次会继承上一次的
     * [committed]（当场走 [AutofillSaveFlow.Leaving]，又是一次「什么都没发生」）、
     * 上一次换过去的那条目标、或者上一次那句失败提示。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 走到这儿说明上一次的实例还活着（用户上一回按了 Home）。
        // 这一行是判断「票是不是走了 onCreate 之外的路」的唯一依据
        Log.d(TAG, "确认页复用了上一次的实例（singleTop）")

        // 只丢手上那一份，**不碰槽**——理由见上面那段，这一行不许换成 drop()
        pending = null
        committed = false
        switchedTo = null
        failure = null
        // 「曾经解锁过」是上一次的事。留着它会让新的这一次一进来就把
        // 刚取到的明文丢掉（库锁着 → Unlocking → sawUnlocked 已经是 true → drop）
        sawUnlocked = false

        if (!redeem(intent)) finishQuietly()
    }

    /**
     * 用户在确认页上自己换过一条之后，要摆的是**重新算过的那一份**。
     *
     * 走的是 [AutofillSave.proposeUpdate] 而不是在这儿改几个字段：那个函数里长着
     * 两道护栏（不够格自动填的不许改、账号对不上的不许改），而护栏必须长在落笔处
     * ——它文件头写着这句话。备选池要把**原来那一条也放回去**，
     * 否则用户换过去之后就再也换不回来了；`proposeUpdate` 自己会把当前这一条滤掉。
     */
    private fun proposalFor(
        base: AutofillSave.Proposal,
        ctx: SaveContext,
    ): AutofillSave.Proposal {
        val target = switchedTo ?: return base
        val pool = base.alternatives + listOfNotNull(base.target)
        return AutofillSave.proposeUpdate(
            context = ctx,
            target = target,
            trust = hostTrust,
            alternatives = pool,
        )
    }

    /* ═════════════════════ 落盘 ═════════════════════ */

    /**
     * 用户按下了那个按钮。**这是整条自动填充链上唯一一处往库里写东西的地方。**
     *
     * 两档分开走，一档都不能省：
     *   · [AutofillSave.Mode.Create] —— `addEntry`。`result.id` 是空串，
     *     补 id、补时间戳全由 `VaultSession.addEntry` 做（和「新增条目」那一页同一条路，
     *     不在这儿另写一份）。名称用 [AutofillSaveFlow.finalName] 算好的那一串。
     *   · [AutofillSave.Mode.Update] —— `updateEntry`。**名称不传**：
     *     `result` 是 `AutofillSave.applyTo` 从原条目 copy 出来的，
     *     名称原样带着，那正是决策(201) 要的（一个字都不动）。
     *
     * 失败就**停在原地**，提案一个字不动（同 `AddEntryScreen` 最后一步那段）：
     * 把他退回到别人的应用去，等于让他重新登录一次才能再被问一遍，
     * 而失败的原因（空间满了、闪存出错）多半重试一次就好了。
     *
     * `VaultSession.mutate` 失败时内存状态自己会回滚，所以这里不需要补救什么——
     * 需要做的只有一件：**别把 [committed] 置上**。置上了就是屏幕说存好了而磁盘上没有，
     * 那是这个应用最不能出现的一句假话。
     */
    private fun commit(proposal: AutofillSave.Proposal, finalName: String) {
        if (committed) return
        if (!proposal.canCommit) return

        val result = when (proposal.mode) {
            AutofillSave.Mode.Create ->
                app.session.addEntry(proposal.result.copy(name = finalName))

            AutofillSave.Mode.Update ->
                app.session.updateEntry(proposal.result)
        }

        if (result.isSuccess) {
            // 先清明文再置旗子：置旗子会让相位当场变成 Leaving 并触发 finish()，
            // 而 onDestroy 未必赶在下一帧之前跑
            drop()
            committed = true
            Log.d(TAG, "已存：${proposal.mode.name}")
        } else {
            Log.w(TAG, "没能存进去：${result.exceptionOrNull()?.javaClass?.simpleName}")
            failure = COMMIT_FAILED
        }
    }

    /* ═════════════════════ 明文的三个清点 ═════════════════════ */

    /** 曾经摆到过解锁之后的那几屏。守着 [drop]，见文件头。 */
    private var sawUnlocked = false

    /** 手上那份明文不留了。清完下一帧相位自然走到 [AutofillSaveFlow.Leaving]。 */
    private fun drop() {
        pending = null
        // 手上这一份已经是 take 出来的，槽里本来就是空的；照样清一次是因为
        // 「这一句多余」这个判断依赖的是别处的实现，而它值不了一份明文的价钱
        SaveHandoff.clear()
        // 通知是这一页的第二条入口，槽都空了就不该再留一个入口在抽屉里
        SaveNotice.cancel(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 纪律第 2 条：页面结束时清一次，无论是确认、取消，还是被系统干掉
        drop()
    }

    /* ═════════════════════ 自动锁定 ═════════════════════ */

    /** 理由同 `AutofillUnlockActivity.onStart` / `onStop`——见那两处的注释。 */
    override fun onStart() {
        super.onStart()
        app.session.onEnterForeground()
    }

    override fun onStop() {
        super.onStop()
        app.session.onEnterBackground()
    }

    /* ═════════════════════ 走人 ═════════════════════ */

    /**
     * 关掉，**不弹任何提示**。
     *
     * 理由同 `AutofillPickActivity.finishWithoutFilling`：这一页浮在别人的应用上面，
     * 一条来路不明的 Toast 只会让人以为是那个应用出了问题。
     * 该说的那两档已经由 [AutofillSaveFlow.Refused] 在页面里说过了。
     */
    private fun finishQuietly() {
        if (isFinishing) return
        finish()
    }

    /** 「忘记主密码了？」那个弹窗的次按钮。理由同挑选页那一处。 */
    private fun openAppAndLeave() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure { Log.w(TAG, "打不开主界面：${it.javaClass.simpleName}") }
        finishQuietly()
    }

    companion object {
        private const val TAG = "AutofillSave"

        /**
         * 进 `Intent` 的东西只有它，而它只是一个数字。
         *
         * `SaveHandoff` 文件头解释了为什么明文不能走这条路：`startActivity` 的
         * `Intent` 要经过 `system_server`，extras 会被 parcel 出去、
         * 排进 `ActivityManager` 的记录、还会被 `dumpsys activity` 打出来。
         */
        private const val EXTRA_TICKET = "cn.localvault.app.autofill.SAVE_TICKET"

        const val COMMIT_FAILED =
            "这一条没能写进保险库，刚才读到的内容还在，可以再按一次试试。"

        /**
         * 服务拉起这一页用的那个 `Intent`。**一个旗子都不加**（决策(222)）。
         *
         * 这一个 `Intent` 有两个用处：28+ 上包成 `PendingIntent` 交给
         * `SaveCallback.onSuccess(IntentSender)`（由系统启动），26/27 上仍由服务
         * 自己 `startActivity`（那一条自己补 `FLAG_ACTIVITY_NEW_TASK`，
         * 见 `VaultAutofillService.handOff`）。
         *
         * ── 为什么这里**不能**有 `FLAG_ACTIVITY_NEW_TASK` ──
         *
         * 上一版在这儿加了它，理由写的是「从 `Service` 启动的硬性要求，两条路留着才一致」。
         * 那句话对 26/27 那条路成立，对 28+ 这条路正好相反，而且它就是这一页起不来的原因。
         *
         * `SaveCallback.onSuccess(IntentSender)` 的整个设计是：这个 intent
         * **由被填的那个 Activity 的上下文启动，因而成为那个 Activity 任务栈的一部分**
         * （平台文档原话）。加上 `NEW_TASK`，再叠上清单里的 `taskAffinity=""`，
         * 等于当场把这句话推翻——系统不再是「往前台那个任务上压一页」，
         * 而是**新建一个任务**。而「后台新建任务」正是后台启动限制拦得最死的那一种。
         *
         * 对照组就在隔壁：[AutofillUnlockActivity] 和 [AutofillPickActivity] 走的是同一套
         * 「系统代发我们的 `PendingIntent`」，那两个 `Intent`（`AutofillResponses.unlockSender` /
         * `pickSender`）**一个旗子都没加**，它们起得来。三页里唯一起不来的那一页，
         * 也是唯一多带一个旗子的那一页——这个差别是上一版从 `startActivity` 那条老路上
         * 带过来的残留。
         *
         * 清单里 `taskAffinity=""` 保持原样：没有 `NEW_TASK` 的启动根本不看 affinity
         * （它只在新建任务/重新归属时才被读），所以那一行现在只对 26/27 那条兜底路生效。
         */
        fun intent(context: Context, ticket: SaveHandoff.Ticket): Intent =
            Intent(context, AutofillSaveActivity::class.java)
                .putExtra(EXTRA_TICKET, ticket.id)
    }
}
