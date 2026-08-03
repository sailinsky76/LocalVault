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

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.autofill.AutofillPolicy
import cn.localvault.app.ui.autofill.SaveNotice
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.ExplainBlock
import cn.localvault.app.ui.components.ExplainNote
import cn.localvault.app.ui.components.ExplainRow
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.ToggleRow
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.components.explain
import cn.localvault.app.ui.settings.AutofillSettingsModel.Action
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType

/**
 * 自动填充设置页（`Route.SETTINGS_AUTOFILL`）。
 *
 * M4 从 4-1a 一路写到 4-3b，整条路早就通了，但**开启的入口一直不存在**——
 * 只能靠用户自己在系统设置里翻到「自动填充服务」那一项。这一页是那个缺口。
 * 和 M3-6b-1 那一页（快捷解锁的绑定页）在工程里是同一种角色。
 *
 * ── 这一页有一半是在解释，不是在设置 ──
 *
 * 这个功能设置一次，然后在接下来几个月里，用户会遇到十几次「怎么没弹出来」。
 * 那十几次里有一大半是我们**故意**不填的，
 * 而故意不填和坏掉了在屏幕上长得一模一样。所以解释必须有，而且不能少。
 *
 * ── 修订（v3）：解释仍然全在，但不再和那个开关抢地方 ──
 *
 * 初版把这些解释一段段平铺下来，结果是：这一页真正能操作的东西只有两个开关，
 * 它们淹在四五段灰字中间，一屏扫过去最显眼的是文字块而不是控件。
 * 「这一页一半是在解释」本来是个正确的判断，被排版实现成了喧宾夺主。
 *
 * 现在的分工（`components/Explain.kt` 定的那条规矩）：
 *
 *   - 和控件平铺在一起的字，**最多两三行**，只说用户此刻要做的判断
 *     （[AutofillSettingsModel.Row.noteShort]）；
 *   - 完整那几段一个字不删，收进「详细说明」弹窗；
 *   - [AutofillSettingsModel.LIMITS] 和 [AutofillSettingsModel.WHY_NOT_SHOWING]
 *     这两块整个收成一行可点的——它们不看也能把这一页走完，但撞上问题的人非看不可，
 *     这正好是「书签」而不是「正文」该有的形态。
 *
 * 页面从十来段字变成两个开关 + 两行入口，一屏装得下，而信息一条没少。
 *
 * ── 为什么它只挂在已解锁那张图上 ──
 *
 * 技术上它完全不需要解锁（问系统状态、跳系统设置，一次都不碰库）。
 * 但它是从设置主页进来的，而设置主页本来就只在解锁相位可达；
 * 为它单开一条锁着也能进的路，等于多一个入口要维护，
 * 换来的是「锁着的时候也能改一项设置」——而那件事没有任何人需要。
 */
@Composable
fun AutofillSettingsScreen(onBack: () -> Unit) {
    val session = LocalSession.current
    val state by session.state.collectAsState()
    val context = LocalContext.current

    // 锁定那一瞬间导航相位会换掉整棵子树，但这一帧可能还会被画一次。
    // 同列表页 / 设置页 / 快捷解锁页的处理。
    if (state !is VaultSession.State.Unlocked) return

    /**
     * 从系统设置回来时重新问一遍。
     *
     * 这条路**一定**会发生，而且是这一页最主要的一条：用户点开关 → 跳到系统那张
     * 确认屏 → 点「确定」→ 按返回键回来。不重新问的话，他会看到那个开关
     * **还是关着的**，然后合理地认为刚才那一下没成。
     * 和 `SecuritySettingsScreen` 里那个观察者是同一套写法、同一个理由。
     */
    val activity = remember(context) { context.findComponentActivity() }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) revision++
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    val availability = remember(revision) { context.autofillAvailability() }
    val row = remember(availability) { AutofillSettingsModel.row(availability) }

    /*
     * 「请勿填充」那一项。值住在 SharedPreferences 里（[AutofillPolicy] 文件头写了
     * 为什么不能住在库文件里），这一页是它唯一的入口。
     *
     * 拨动之后立刻落盘，不做「保存」按钮：这一项没有中间态，也没有任何东西要校验。
     * 服务那一侧每次请求现读，所以下一次弹填充条时就是新值——不需要通知谁。
     */
    val policy = remember(context) { AutofillPolicy(context) }
    var respectOptOut by remember { mutableStateOf(policy.respectOptOut) }
    val optOut = AutofillSettingsModel.optOutRow(respectOptOut)

    /**
     * 跳出去之前先开可信中断（决策⑳）。
     *
     * 用户在系统那张列表里可能要翻一会儿（尤其是要先找到现在那个服务再换掉），
     * 不开宽限的话回来时库已经锁了——而他刚做完的那件事恰恰需要
     * 回到这一页来确认成没成。同「去系统设置录入指纹」那个按钮。
     */
    val jump: () -> Unit = {
        when (row.action) {
            Action.RequestSetService -> {
                session.beginSystemInterlude()
                context.openAutofillServicePicker()
            }
            Action.OpenSystemSettings -> {
                session.beginSystemInterlude()
                context.openSystemSettings()
            }
            Action.None -> Unit
        }
    }

    VaultScreen(title = "自动填充", onBack = onBack, seal = { DefaultSeal() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            /* ───────────────── 一句话，外加一处可以读完的地方 ───────────────── */

            ExplainNote(
                AutofillSettingsModel.INTRO_SHORT,
                style = VaultType.Sub,
                color = VaultColors.Dim,
                detailTitle = AutofillSettingsModel.INTRO_DETAIL_TITLE,
                // 这里**不**顺手把 LIMITS 也塞进来。那三条有自己的一行入口（下面），
                // 同一份字出现在两个弹窗里，早晚会变成只改一处的那两处之一。
                detail = explain(AutofillSettingsModel.INTRO),
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            /* ───────────────── 那一行开关 ───────────────── */

            VaultCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    ToggleRow(
                        title = "用本地保险库填充",
                        subtitle = row.subtitle,
                        checked = row.checked,
                        enabled = row.enabled,
                        /*
                         * 往哪个方向拨都是同一个动作：跳出去。
                         *
                         * 这不是偷懒——应用**没有**把自己设为或撤下填充服务的能力，
                         * 两个方向都只能把用户送到系统那边（见 AutofillSettingsModel.Row）。
                         * 所以这里不看 `want` 的值，看的是当前档位决定的 `action`。
                         *
                         * 开关此刻**不动**：真正拨动它的是用户从系统回来时
                         * 上面那个 ON_START 观察者重新问到的结果。
                         * 先拨过去再跳出去的话，用户在系统那屏按了取消，
                         * 回来会看到一个开着但其实没设成的开关（同 PIN 那一行的处理）。
                         */
                        onCheckedChange = { jump() },
                    )

                    /*
                     * 说明单独画，不塞给 ToggleRow 的 `note`：那个参数只在开关被
                     * **禁用**时才顶替副标题（见 Toggle.kt），而这一页上最要紧的两句
                     * （「系统同时只认一个」「应用没办法把自己撤下来」）
                     * 恰恰都出现在开关能动的档位上，走那条路会被悄悄吞掉。
                     *
                     * 平铺的是**短版**，完整那段挂在链接后面（v3）：
                     * 这一格里能动的只有那个开关，说明的高度不该是它的三倍。
                     */
                    val noteShort = row.noteShort
                    val noteFull = row.note
                    if (noteShort != null) {
                        ExplainNote(
                            noteShort,
                            style = VaultType.Sub,
                            // 「已经是默认」那一档用黄铜色：它是这一页唯一一句
                            // 「你以为点一下就能关掉，其实不能」的话，得看得见。
                            color = if (row.checked) VaultColors.Brass else VaultColors.Dimmer,
                            detailTitle = "关于这个开关",
                            // 短版已经把要紧的意思带出来了，长短两版一样时不必画链接。
                            detail = if (noteFull != null && noteFull != noteShort) {
                                explain(noteFull)
                            } else {
                                emptyList()
                            },
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }

                    if (row.buttonText != null) {
                        Spacer(Modifier.height(2.dp))
                        GhostButton(row.buttonText, onClick = jump, tint = VaultColors.Dim)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            /* 三条底线。紧跟着开关，因为它说的正是「开了之后我们会做什么、不做什么」。 */
            ExplainRow(
                title = AutofillSettingsModel.LIMITS_TITLE,
                subtitle = AutofillSettingsModel.LIMITS_SUMMARY,
                detail = listOf(ExplainBlock.Bullets(AutofillSettingsModel.LIMITS)),
            )

            /* ───────────────── 保存确认那条通知 ───────────────── */

            /*
             * 这一格**只在缺权限时出现**，给了就整块消失。
             *
             * 它不是一项设置，是一次修复：没有通知权限时，「保存」这条链在
             * 这台设备上是断的——系统的保存框照弹、按下「更新」之后什么都不会发生
             * （原因见 SaveNotice 文件头，一句话是「确认页没有别的入口能起来」）。
             * 所以它摆在开关下面、三条底线后面：读到这儿的人刚看完
             * 「开了之后会发生什么」，紧接着就是「还差一步才真的会发生」。
             *
             * 常驻一个已经满足的条件是这一页最不需要的东西：这一页的毛病本来就是
             * 控件淹在字里（文件头 v3 那段）。
             *
             * 决策(228) 之后它还多了一层含义：请求是进页面就自动发的（下面那段），
             * 所以这张卡片出现，说明**自动那一次没成**——用户划掉了系统框，
             * 或者更早之前就拒过一次、那张框已经不会再弹了。它是兜底，不是主路。
             */
            val needsNotice = remember(revision) {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !SaveNotice.canPost(context)
            }
            var refused by remember { mutableStateOf(false) }
            val askNotice = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                // 系统那张框只会弹一次；第二次调 launch 会当场返回 false 而不弹任何东西。
                // 所以被拒之后要换一条路（下面那个按钮），否则用户会按着一个没反应的按钮
                if (!granted) refused = true
                revision++
            }

            /*
             * 进这一页就**自动发起一次**（决策(228)）。
             *
             * `POST_NOTIFICATIONS` 是运行时权限，只有用户能授予——应用没有任何办法
             * 替他按下那个「允许」。能省掉的是**我们自己那一次点击**：原来的路是
             * 「看到卡片 → 点『允许通知』→ 系统弹框 → 点『允许』」两下，
             * 现在是进页面直接弹系统框，一下。
             *
             * 只发一次（`asked`），理由是这个 launcher 的回调会 `revision++`，
             * 而 `needsNotice` 是跟着 `revision` 重算的——不设这个闸就是一个自旋。
             *
             * 用 `remember` 而不是 `rememberSaveable`：转屏后会再问一次，
             * 而「再问一次」在这里不是代价（系统框只弹一次，之后 launch 立刻返回），
             * 反倒是 `rememberSaveable` 要往 `savedInstanceState` 里写东西——
             * 同 `EntryFormFields` / `VaultListScreen` 那两处的取舍。
             *
             * 权限已经给了、或者系统版本用不着（< 13）时 `needsNotice` 是 false，
             * 这段一次都不跑，用户永远看不到这张框。
             */
            var asked by remember { mutableStateOf(false) }
            LaunchedEffect(needsNotice) {
                if (needsNotice && !asked) {
                    asked = true
                    askNotice.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            if (needsNotice) {
                VaultCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        ExplainNote(
                            "还差一步：按下系统的保存框之后，确认页要靠一条通知才能打开。" +
                                "没给通知权限的话，那一下会看起来什么都没发生。" +
                                "通知里不出现用户名、密码，也不出现是哪个应用。" +
                                (
                                    if (refused) {
                                        "\n\n系统那张请求框不会再弹了（一台设备只弹一次），" +
                                            "只能去系统设置里打开。"
                                    } else {
                                        ""
                                    }
                                    ),
                            style = VaultType.Sub,
                            color = VaultColors.Brass,
                            maxLines = 10,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        GhostButton(
                            if (refused) "去系统设置打开通知" else "再问我一次",
                            onClick = {
                                if (refused) {
                                    // 跳出去之前先开可信中断，同上面那个 jump（决策⑳）
                                    session.beginSystemInterlude()
                                    context.openAppNotificationSettings()
                                } else {
                                    askNotice.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            tint = VaultColors.Dim,
                        )
                    }
                }
            }

            /* ───────────────── 「请勿填充」声明 ───────────────── */

            Eyebrow("填充范围", modifier = Modifier.padding(start = 4.dp, top = 8.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    ToggleRow(
                        title = AutofillSettingsModel.OPT_OUT_TITLE,
                        subtitle = optOut.subtitle,
                        checked = respectOptOut,
                        onCheckedChange = { want ->
                            respectOptOut = want
                            policy.respectOptOut = want
                        },
                    )
                    /*
                     * 同上面那一行：说明单独画，不塞给 ToggleRow 的 `note`——
                     * 那个参数只在开关被禁用时才顶替副标题（Toggle.kt），
                     * 而这一项两档都能动，走那条路两段话都会被吞掉。
                     */
                    ExplainNote(
                        optOut.noteShort,
                        style = VaultType.Sub,
                        // 开着那一档用黄铜色：它是这一页第二句「你以为没坏，其实是你自己关的」，
                        // 而这一档的症状（某些 App 彻底填不了）最容易被当成 bug。
                        color = if (respectOptOut) VaultColors.Brass else VaultColors.Dimmer,
                        detailTitle = AutofillSettingsModel.OPT_OUT_TITLE,
                        linkText = "这一项两档各有什么代价",
                        // 两档的完整说明都给，而且各自挂一个小标题：拨动它之前，
                        // 两个方向的代价该一次看全，而不是拨过去之后才读到另一半。
                        // 不加标题的话，两段讲相反状态的话连排在一起，读者分不清哪段说的是哪档。
                        detail = listOf(
                            ExplainBlock.Section(
                                "打开之后",
                                AutofillSettingsModel.optOutRow(respected = true).note,
                            ),
                            ExplainBlock.Section(
                                "关着的时候",
                                AutofillSettingsModel.optOutRow(respected = false).note,
                            ),
                        ),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            /* ───────────────── 收起来的那两块 ───────────────── */

            Eyebrow("遇到问题", modifier = Modifier.padding(start = 4.dp, top = 8.dp))

            /*
             * 这一项**没有**往 WHY_NOT_SHOWING 里加一条。
             * 那份清单是给「默认状态下遇到症状」的人扫的，而「请勿填充」默认是关的——
             * 加一条只会让绝大多数读者去排查一个他没打开过的设置。
             * 打开它的人，症状的解释就写在上面那一格的链接里。
             */
            ExplainRow(
                title = AutofillSettingsModel.WHY_EYEBROW,
                subtitle = AutofillSettingsModel.WHY_SUMMARY,
                detail = AutofillSettingsModel.WHY_NOT_SHOWING.map {
                    // 症状用行名的字号、原因用小字，两段之间不并成一行。
                    // 用户是带着症状来的（他记得的是「在某某 App 里没弹出来」），
                    // 这一列症状是给他扫的，扫到了才会读下面那段。
                    ExplainBlock.Section("「${it.symptom}」", it.why)
                } + ExplainBlock.Para(AutofillSettingsModel.WHY_TAIL),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
