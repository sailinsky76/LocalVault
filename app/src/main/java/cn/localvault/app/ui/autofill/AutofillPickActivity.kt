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
 * 用户在别人的应用里点了填充条**末尾**那条「在保险库里搜索」之后，落到的就是这一页。
 *
 * 它和 [AutofillUnlockActivity] 是一对，共用同一副骨架（`FLAG_SECURE`、
 * 从 Intent 里接 `AssistStructure`、自己接自动锁定那两个回调、
 * 答卷从 `EXTRA_AUTHENTICATION_RESULT` 交回去），差别只有两处：
 *
 *   · 那一页是**响应级**认证，交回去的是一整份 `FillResponse`；
 *     这一页是**数据集级**认证，交回去的是**一个** `Dataset`
 *     （见 [AutofillResponses.searchDataset] 那段注释）。
 *   · 那一页解完锁就自动交卷；这一页解完锁之后才刚开始——
 *     用户要在清单上挑一条，还要看完几句话再按确认。
 *
 * ── 这个文件里没有一条规则 ──
 *
 * 摆什么在 [AutofillPick]（71 条用例），此刻摆哪一屏在 [AutofillPickFlow]，
 * 画成什么样在 [AutofillPickScreen]，往哪几个框写在 [AutofillPick.writes]。
 * 这里只剩四件平台活：接结构、按相位摆屏、读一下承载应用的名字、把 `Dataset` 交回去。
 *
 * ── 自动锁定对这一页尤其要紧 ──
 *
 * 决策(185) 对这一页同样成立，而且比对跳板页更要紧：跳板页上用户只是输一次密码，
 * 前后几秒；这一页他可能翻上一分钟，中途接个电话、切出去查个验证码。
 * [onStart] / [onStop] 那两行把倒计时接上，[AutofillPickFlow.phase] 每一帧
 * 重新问一次相位——于是锁定发生的那一刻，摊开的清单会当场收起来变回解锁屏。
 * 少了这两样中的任何一样，那一屏资产清单就会一直摆在别人的应用上面。
 */
class AutofillPickActivity : FragmentActivity() {

    private val app: VaultApp get() = application as VaultApp

    private var parsed: AssistShell.Parsed? = null
    private var plan: FillPlan.Plan? = null

    /**
     * 交过一次就不再交第二次（同 [AutofillUnlockActivity]）。
     *
     * 做成 Compose 状态而不是普通的 `Boolean`，是因为 [AutofillPickFlow.phase]
     * 要读它：普通字段改了不会触发重组，那一句「交过就走」在界面上就永远等不到，
     * 全靠 [deliver] 里那一行 `finish()` 兜住——**能跑，但那是巧合，不是设计**。
     */
    private var delivered by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 这一页会摊开一屏条目名称和账号，而且浮在别人的应用之上。
        // 必须在 setContent 之前。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val structure = assistStructure()
        if (structure == null) {
            Log.w(TAG, "Intent 里没有结构，这次不填")
            finishWithoutFilling()
            return
        }
        // 拿不到 activityComponent 就一个框都不收（决策(177)）。
        // 策略要和服务那一次用同一份，否则「填充条上有的条目，点进这一页就没了」。
        val p = AssistShell.parse(structure, AutofillPolicy(this).respectOptOut)
        if (p == null) {
            Log.w(TAG, "结构解析不出来，这次不填")
            finishWithoutFilling()
            return
        }
        parsed = p
        plan = FillPlan.forRequest(p.context)

        enableEdgeToEdge()
        setContent {
            LocalVaultTheme {
                val state by app.session.state.collectAsState()
                val currentPlan = plan!!

                val refusal = remember(currentPlan) {
                    AutofillPick.refusal(currentPlan, BuildConfig.APPLICATION_ID)
                }
                // 每一帧重新问一次，不是只在进来时问一次（见文件头末段）
                val phase = AutofillPickFlow.phase(state, refusal, delivered)

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
                        is AutofillPickFlow.Leaving ->
                            LaunchedEffect(Unit) { finishWithoutFilling() }

                        is AutofillPickFlow.Refused ->
                            AutofillRefusalScreen(
                                reason = phase.reason,
                                onClose = ::finishWithoutFilling,
                            )

                        // 复用跳板页那两屏，一个字都不重写（见 UnlockHost 的注释）
                        is AutofillPickFlow.Unlocking ->
                            UnlockHost(
                                quick = app.quickUnlock,
                                session = app.session,
                                onOpenApp = ::openAppAndLeave,
                            )

                        is AutofillPickFlow.Picking -> {
                            val data = (state as? VaultSession.State.Unlocked)?.data
                            if (data != null) {
                                AutofillPickScreen(
                                    plan = currentPlan,
                                    entries = data.entries,
                                    trust = hostTrust,
                                    appLabel = appLabel(currentPlan),
                                    browserLevel = browserLevel(currentPlan),
                                    onConfirm = ::deliver,
                                    onCancel = ::finishWithoutFilling,
                                )
                            }
                        }
                    }
                }
            }
        }
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

    /* ═════════════════════ 交答卷 ═════════════════════ */

    /**
     * 用户按了「确认填入」。
     *
     * **往哪几个框写由 [AutofillPick.writes] 一个函数说了算**（决策(187)），
     * 装配由 [AutofillResponses.picked] 做。这个方法里一行判断都没有，
     * 而这不是巧合——它是前面九个内核文件全部工作的收口，
     * 在这儿补一句「顺手把另一组也填了」，前面那些就白做了。
     */
    private fun deliver(entry: VaultEntry) {
        if (delivered) return
        val p = parsed ?: return finishWithoutFilling()
        val currentPlan = plan ?: return finishWithoutFilling()

        val dataset = runCatching {
            val writes = AutofillPick.writes(currentPlan, entry)
            if (writes.isEmpty()) return@runCatching null
            AutofillResponses.picked(
                context = this,
                parsed = p,
                writes = writes,
                row = AutofillPick.row(entry, currentPlan, hostTrust),
            )
        }.onFailure {
            Log.w(TAG, "装不出 Dataset：${it.javaClass.simpleName}")
        }.getOrNull()

        if (dataset == null) {
            // 走到这儿说明「按钮本该是灰的却按下去了」。不弹提示：
            // 用户看到的是这一页消失、输入框没变，而那正是真实发生的事。
            Log.w(TAG, "一个字都没写成，这次不填")
            finishWithoutFilling()
            return
        }

        delivered = true
        setResult(
            RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset),
        )
        finish()
    }

    /** 理由同 `AutofillUnlockActivity.finishWithoutFilling`：**不弹任何提示**。 */
    private fun finishWithoutFilling() {
        if (isFinishing) return
        setResult(RESULT_CANCELED)
        finish()
    }

    /** 「忘记主密码了？」那个弹窗的次按钮。理由同跳板页那一处。 */
    private fun openAppAndLeave() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure { Log.w(TAG, "打不开主界面：${it.javaClass.simpleName}") }
        finishWithoutFilling()
    }

    /* ═════════════════════ 承载它的是谁 ═════════════════════ */

    private val hostTrust: AndroidHostTrust by lazy { AndroidHostTrust(packageManager) }

    /**
     * 承载这一屏的应用**自称**的名字。
     *
     * 读不出来就传 null——[AutofillPick.handOver] 那边会只写包名，
     * **不写「未知应用」**（决策(188)）。这里绝不自己编一个兜底字符串。
     */
    private fun appLabel(plan: FillPlan.Plan): String? {
        val pkg = plan.primary?.origin?.hostApp ?: return null
        return runCatching {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        }.onFailure {
            // 只打异常类名，不打包名（决策(144)）
            Log.d(TAG, "读不到应用名：${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /**
     * 这个承载应用落在哪一档。**原生框那一屏传 null**——那一屏上没有浏览器可谈，
     * 硬给一个 `Unknown` 会让确认屏上多出一句「这不是我们认得的浏览器」，
     * 而用户根本没在浏览器里（[AutofillPick.warningsFor] 会照说不误）。
     */
    private fun browserLevel(plan: FillPlan.Plan): BrowserTrust.Level? {
        val origin = plan.primary?.origin
        if (origin !is Origin.Web) return null
        return runCatching { hostTrust.level(origin.hostApp) }.getOrNull()
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
        const val TAG = "AutofillPick"
    }
}
