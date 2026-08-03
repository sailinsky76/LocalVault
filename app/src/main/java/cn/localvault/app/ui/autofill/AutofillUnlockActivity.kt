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

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import cn.localvault.app.BuildConfig
import cn.localvault.app.MainActivity
import cn.localvault.app.VaultApp
import cn.localvault.app.core.keystore.QuickUnlock
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.CryptoInfo
import cn.localvault.app.ui.LocalRepository
import cn.localvault.app.ui.ProvideVaultDeps
import cn.localvault.app.ui.theme.LocalVaultTheme
import cn.localvault.app.ui.unlock.QuickUnlockGuard
import cn.localvault.app.ui.unlock.QuickUnlockScreen
import cn.localvault.app.ui.unlock.UnlockController
import cn.localvault.app.ui.unlock.UnlockMasterScreen
import cn.localvault.app.ui.util.Fmt

/**
 * 用户在别人的应用里点了填充条上那条「先解锁」之后，落到的就是这一页。
 *
 * 它是一块**跳板**，不是一个新页面：解锁那两屏用的是主界面里同样那两个
 * Composable（`QuickUnlockScreen` / `UnlockMasterScreen`）和同一个
 * `UnlockController`，于是失败退避、连错十次关掉快捷解锁、
 * 「上次是被自动锁定的」那句话，在这儿和在应用里是同一套行为。
 * 这一页自己只做三件别处没有的事：
 *
 *   1. 从 Intent 里接住系统塞进来的 `AssistStructure`；
 *   2. 解开之后**当场把响应算出来**，从 `EXTRA_AUTHENTICATION_RESULT` 交回去；
 *   3. 走的时候把自动锁定的倒计时接上（见 [onStop]，这一条最容易漏）。
 *
 * ── 为什么不是「拉起主应用让他自己解锁」 ──
 *
 * 那条路能走，而且少写这一整个文件：解完锁，用户切回浏览器再点一下输入框，
 * 新的一次 `onFillRequest` 就能出候选了。差别在最后那半句——
 * **「再点一下」**。用户刚刚点的就是「先解锁」，他有理由认为解锁完就该填上。
 * 让他回到一个看起来毫无变化的页面、自己想到要再点一次，
 * 那一步会劝退绝大多数人，而这个功能只要被放弃一次，
 * 用户下次就回去用那个会同步到云端的管理器了。
 *
 * ── `EXTRA_ASSIST_STRUCTURE` 从哪儿来 ──
 *
 * 是系统塞的：`FillResponse.setAuthentication` 收下我们那个 `IntentSender`
 * 之后，会往里补 `EXTRA_ASSIST_STRUCTURE` 和 `EXTRA_CLIENT_STATE` 再发出来。
 * 所以那个 `PendingIntent` **必须是 `FLAG_MUTABLE`** 的
 * （见 `AutofillResponses.unlockSender`）。拿不到结构时这一页不硬撑：
 * 如实退回 `RESULT_CANCELED`，用户看到的是「解锁了但没填上」，
 * 而不是一份对着错误的屏幕算出来的响应。
 */
class AutofillUnlockActivity : FragmentActivity() {

    private val app: VaultApp get() = application as VaultApp

    private var structure: AssistStructure? = null

    /** 已经交过一次答卷就不再交第二次（`LaunchedEffect` 可能因重组再跑一遍）。 */
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 同 MainActivity：这一页上会出现 PIN 键盘和主密码输入框，
        // 而它是浮在别人的应用之上的。必须在 setContent 之前。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        structure = assistStructure()
        if (structure == null) {
            Log.w(TAG, "Intent 里没有结构，这次不填")
            finishWithoutFilling()
            return
        }

        // 进来的时候库可能已经是开着的（用户在别处刚解过锁，
        // 而这条填充条是解锁之前弹出来的）。那就不必再问一次密码。
        if (app.session.state.value is VaultSession.State.Unlocked) {
            deliver()
            return
        }

        enableEdgeToEdge()
        setContent {
            LocalVaultTheme {
                val state by app.session.state.collectAsState()

                LaunchedEffect(state) {
                    when (state) {
                        is VaultSession.State.Unlocked -> deliver()
                        // 库在这中间被删掉了（另一个入口做的）。没有可填的东西，
                        // 也没有什么可对用户说的——安静退出，让他回到刚才那一屏。
                        is VaultSession.State.NoVault -> finishWithoutFilling()
                        is VaultSession.State.Locked -> Unit
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
                    if (state is VaultSession.State.Locked) {
                        UnlockHost(
                            quick = app.quickUnlock,
                            session = app.session,
                            onOpenApp = ::openAppAndLeave,
                        )
                    }
                }
            }
        }
    }

    /* ═════════════════════ 自动锁定 ═════════════════════ */

    /**
     * 这两个回调**不是照抄 MainActivity，是这一页非有不可的东西**。
     *
     * 自动锁定的倒计时由「Activity 走 onStop」点着。如果这一页不接这两个回调：
     * 用户在浏览器里解了锁，填充完成，这一页 `finish` 掉——
     * 而**没有任何一个 Activity 会为此走 onStop**（主界面早就停在后台了，
     * 它那次 onStop 发生在更早以前，倒计时也已经烧完并锁过一次了）。
     * 结果是库从这一刻起**一直开着**，直到用户下一次亲手打开应用再退出去。
     *
     * 那是一个只在自动填充这条路上才会出现的漏洞，而且在应用里怎么点都试不出来。
     */
    override fun onStart() {
        super.onStart()
        app.session.onEnterForeground()
    }

    override fun onStop() {
        super.onStop()
        app.session.onEnterBackground()
    }

    /* ═════════════════════ 交答卷 ═════════════════════ */

    /**
     * 库开了，为**当初那一屏**算出响应交回去。
     *
     * 走的是和服务里一模一样的那条直线（解析 → 计划 → 判断 → 装配），
     * 一个字的判断都没有重写：这一页和 `VaultAutofillService`
     * 唯一的区别只是「此刻 `session.state` 是 Unlocked」。
     */
    private fun deliver() {
        if (delivered) return
        delivered = true

        val response = runCatching {
            // 同挑选页：策略读同一份，三条路上的判定必须一致。
            val parsed = AssistShell.parse(structure!!, AutofillPolicy(this).respectOptOut)
                ?: return@runCatching null
            val plan = FillPlan.forRequest(parsed.context)
            // **这一份也要挂 `SaveInfo`。** 系统换上认证结果的那一刻，
            // 原来那份带着 `SaveInfo` 的响应就整个被顶掉了（响应级认证的语义）——
            // 这里漏掉一行，表现是「凡是点过一次『先解锁』的那一次登录，
            // 提交之后保存框不出现」。而那正是最该出现的一次：
            // 库刚解开、用户刚登录成功，屏幕上那个密码多半还没存进去。
            // 这一行不报错、不崩、当天没有任何症状，所以写在这儿钉住。
            val save = AutofillResponses.saveInfo(
                parsed,
                SavePlan.decide(parsed.context, BuildConfig.APPLICATION_ID),
            )
            when (
                val answer = AutofillOffer.respond(
                    state = app.session.state.value,
                    plan = plan,
                    trust = AndroidHostTrust(packageManager),
                    selfPackage = BuildConfig.APPLICATION_ID,
                )
            ) {
                is AutofillOffer.Offer ->
                    // **这一份刻意不带内联**（决策(217)，`inline = null`）。
                    // 内联那几格的尺寸、字号、配色来自输入法在**那一次请求**里给的
                    // `InlinePresentationSpec`，而我们手上没有它——它跟着
                    // `FillRequest` 走，不跟着这个 `Intent` 走。
                    //
                    // 把它顺着 `PendingIntent` 塞过来是做得到的，但那份规格描述的是
                    // 「那一刻那个键盘的建议条」，而用户刚刚离开这一屏、
                    // 站在一整屏解锁页上过了一次指纹——回去的时候输入法可能
                    // 已经换过一轮会话，甚至换了一个输入法。拿一份过期的规格去画，
                    // 画出来什么样没有人说得准，而它出现在**别人的应用**上面。
                    //
                    // 所以这条路解开之后退回浮层：候选一条不少、当场就填上
                    // （这一页存在的全部理由），只是那几条不出现在键盘那一条上。
                    AutofillResponses.datasets(this, parsed, plan, answer, save, inline = null)
                // Silent / Unlock 两种（解锁之后再要一次解锁是说不通的）。
                //
                // 注意「解开之后这个站一条都没匹配上」**不落在这儿**：那种情形
                // 仍然是 `Offer`（只是 items 为空），而 M4-2b-2 之后它会退化成
                // 末尾那条「在保险库里搜索」——用户解了锁，至少得给他一个去处。
                //
                // 真落到这儿时（自己的界面、库在这中间被删了）**也不返回裸的 null**：
                // 一份只挂着 `SaveInfo` 的响应仍然是有用的（AutofillResponses.saveOnly）。
                // `SavePlan.decide` 已经把「自己的界面」挡在外面了，所以这一行不会
                // 让保险库为自己那一屏挂上东西。
                else -> save?.let { AutofillResponses.saveOnly(it) }
            }
        }.onFailure {
            Log.w(TAG, "算不出响应：${it.javaClass.simpleName}")
        }.getOrNull()

        if (response == null) {
            finishWithoutFilling()
            return
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response),
        )
        finish()
    }

    /**
     * 什么都不填地退出。
     *
     * `RESULT_CANCELED` 对系统的意思是「这次认证没成」，填充会话原样留着——
     * 用户回到刚才那个输入框，该打字打字。**不要**为此弹一个 Toast：
     * 这一页是浮在别人的应用上的，一条来路不明的提示只会让人以为是那个应用出了问题。
     */
    private fun finishWithoutFilling() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /**
     * 「忘记主密码了？」那个弹窗的次按钮。
     *
     * 重来（清空保险库）是一件不该在一块浮在浏览器上面的跳板上完成的事：
     * 那一页有两道门槛、四段实话，用户需要坐下来看完。所以这里把他送回主应用，
     * 这一次填充就此作罢。
     */
    private fun openAppAndLeave() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure { Log.w(TAG, "打不开主界面：${it.javaClass.simpleName}") }
        finishWithoutFilling()
    }

    /* ═════════════════════ 杂 ═════════════════════ */

    @Suppress("DEPRECATION")
    private fun assistStructure(): AssistStructure? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                AutofillManager.EXTRA_ASSIST_STRUCTURE,
                AssistStructure::class.java,
            )
        } else {
            intent.getParcelableExtra<AssistStructure>(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        }
    }.getOrNull()

    private companion object {
        const val TAG = "AutofillUnlock"
    }
}

/**
 * 解锁那两屏。
 *
 * 和 `VaultNavHost` 里的 `LockedGraph` 是同一套页面、同一个控制器，
 * 只是没有 `NavHost`——这一页总共两个去处，为它架一张导航图，
 * 换来的只有一个能被系统恢复的返回栈，而这一页**不该有**返回栈：
 * 它是别人应用上面的一层浮窗，按返回就该整个消失。
 *
 * ── 为什么是 `internal` 而不是 `private`（M4-2b-2 改的）──
 *
 * 挑选页那个 Activity 也会遇到「库锁着」——用户点开填充条时库是开着的，
 * 点进挑选页、翻了半天、然后**自动锁定过去了**（决策(185) 对那一页同样成立）。
 * 那一刻它要摆的是和这里一模一样的两屏。
 *
 * 抄一份过去的后果是：某天有人给这里的解锁流加一条「连错十次关掉快捷解锁」
 * 之类的规矩，而挑选页那一份没有跟着改——于是同一个 App 里，
 * 从填充条上解锁和从挑选页上解锁，行为不一样，而没有任何一处能解释为什么。
 */
@Composable
internal fun UnlockHost(
    quick: QuickUnlock,
    session: VaultSession,
    onOpenApp: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val repo = LocalRepository.current

    val guard = remember(quick) { QuickUnlockGuard(quick) }
    val controller = remember(repo, session, guard) {
        UnlockController(repo = repo, session = session, guard = guard, scope = scope)
    }
    DisposableEffect(controller) { onDispose { controller.cancel() } }

    // 同 LockedGraph：只在进来的那一刻算一次。连错十次会把快捷解锁关掉，
    // 那一刻 isAnyEnrolled 会翻成 false，起始点若跟着变，这一屏会在用户眼前重建
    val startedAtQuick = remember { quick.isAnyEnrolled }
    var useMaster by remember { mutableStateOf(!startedAtQuick) }

    val autoLocked = remember {
        session.lastLockReason == VaultSession.LockReason.AutoTimeout
    }

    if (useMaster) {
        UnlockMasterScreen(
            controller = controller,
            autoLocked = autoLocked,
            onReset = onOpenApp,
            onUseQuickUnlock = if (startedAtQuick && quick.isAnyEnrolled) {
                { useMaster = false }
            } else {
                null
            },
        )
    } else {
        QuickUnlockScreen(
            controller = controller,
            quickUnlock = quick,
            autoLocked = autoLocked,
            onUseMaster = { useMaster = true },
        )
    }
}
