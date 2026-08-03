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

package cn.localvault.app.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.LocalCryptoInfo
import cn.localvault.app.ui.LocalQuickUnlock
import cn.localvault.app.ui.LocalRepository
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.add.AddEntryScreen
import cn.localvault.app.ui.detail.EntryDetailScreen
import cn.localvault.app.ui.edit.EditEntryScreen
import cn.localvault.app.ui.generate.GeneratorHolder
import cn.localvault.app.ui.generate.LocalGenerator
import cn.localvault.app.ui.importer.ImportController
import cn.localvault.app.ui.importer.ImportScreen
import cn.localvault.app.ui.list.SearchScreen
import cn.localvault.app.ui.list.VaultListScreen
import cn.localvault.app.ui.settings.AboutScreen
import cn.localvault.app.ui.settings.AutofillSettingsScreen
import cn.localvault.app.ui.settings.ChangeMasterScreen
import cn.localvault.app.ui.settings.DeleteVaultScreen
import cn.localvault.app.ui.settings.PinSetupModel
import cn.localvault.app.ui.settings.PinSetupScreen
import cn.localvault.app.ui.settings.SecuritySettingsScreen
import cn.localvault.app.ui.settings.SettingsScreen
import cn.localvault.app.ui.backup.BackupScreen
import cn.localvault.app.ui.onboarding.CreateMasterScreen
import cn.localvault.app.ui.onboarding.CreateVaultController
import cn.localvault.app.ui.onboarding.WelcomeScreen
import cn.localvault.app.ui.restore.RestoreController
import cn.localvault.app.ui.restore.RestoreScreen
import cn.localvault.app.ui.unlock.QuickUnlockGuard
import cn.localvault.app.ui.unlock.QuickUnlockScreen
import cn.localvault.app.ui.unlock.ResetVaultScreen
import cn.localvault.app.ui.unlock.UnlockController
import cn.localvault.app.ui.unlock.UnlockMasterScreen

/**
 * ── 为什么是三张互不相通的图，而不是一张大图 ──
 *
 * 「未建库 / 已锁定 / 已解锁」不是三个页面，是三种**权限状态**。
 * 如果把它们放进同一个 NavHost，back stack 就会跨状态残留：
 * 自动锁定发生在详情页时，back stack 里还压着那条条目的路由，
 * 用户重新解锁后按返回，就可能回到一个本该被清掉的界面。
 * 更糟的是，锁定时 Composable 不会立刻销毁，一屏明文密码
 * 有可能还挂在 back stack 的某个 saved state 里。
 *
 * 所以状态一变，整棵子树连同它的 back stack 一起换掉。
 * 代价是切换时没有页面级过渡动画（用一层淡入淡出兜底），
 * 换来的是「锁定 = 界面上一切痕迹清零」这条能一眼看懂的保证。
 */
@Composable
fun VaultRoot() {
    val session = LocalSession.current
    val state by session.state.collectAsState()

    // 只按状态的**种类**切换，条目增减不该让整棵树重建
    val phase = when (state) {
        is VaultSession.State.NoVault -> Phase.Onboarding
        is VaultSession.State.Locked -> Phase.Locked
        is VaultSession.State.Unlocked -> Phase.Unlocked
    }

    AnimatedContent(
        targetState = phase,
        transitionSpec = { fadeIn(tween()) togetherWith fadeOut(tween()) },
        label = "phase",
    ) { p ->
        when (p) {
            Phase.Onboarding -> OnboardingGraph()
            Phase.Locked -> LockedGraph()
            Phase.Unlocked -> UnlockedGraph()
        }
    }
}

private fun tween() = androidx.compose.animation.core.tween<Float>(durationMillis = 180)

private enum class Phase { Onboarding, Locked, Unlocked }

/* ─────────────────────── 图一：首次引导 ─────────────────────── */

@Composable
private fun OnboardingGraph(nav: NavHostController = rememberNavController()) {
    val repo = LocalRepository.current
    val session = LocalSession.current
    val info = LocalCryptoInfo.current
    val scope = rememberCoroutineScope()

    /**
     * 建库控制器挂在**图**这一层而不是页面里。
     *
     * 页面级的 `remember` 会在导航时被丢掉，而建库是个跨几秒的异步动作；
     * 挂在这里，它的生命周期跟整个引导阶段一致——引导结束（会话接管、
     * 相位切到已解锁）时整棵子树一起销毁，控制器自然跟着走。
     */
    val creator = remember(repo, session) {
        CreateVaultController(
            repo = repo,
            session = session,
            scope = scope,
            argon2Available = info.argon2Available,
        )
    }
    DisposableEffect(creator) { onDispose { creator.cancel() } }

    /**
     * 恢复控制器同样挂在**图**这一层，理由和建库控制器一样，而且更重：
     * 它手里攥着用户那份备份文件的全部字节（`pending`），
     * 而读文件、跑 KDF、写盘这三件事加起来能跨好几秒。
     *
     * 挂在页面上的话，用户在读文件的过程中转一下屏幕，那次读取就成了孤儿协程；
     * 挂在这里，它的生命周期跟整个引导阶段一致——恢复成功（会话接管、
     * 相位切到已解锁）时整棵子树一起销毁，它和那份字节一起走。
     *
     * 页面自己在 `onDispose` 里额外清一次 `pending`：控制器活得比页面长，
     * 而用户退回欢迎页之后没有任何理由让一整个加密库继续留在内存里。
     */
    val restorer = remember(repo, session) {
        RestoreController(repo = repo, session = session, scope = scope)
    }
    DisposableEffect(restorer) { onDispose { restorer.cancel() } }

    NavHost(navController = nav, startDestination = Route.WELCOME) {
        composable(Route.WELCOME) {
            WelcomeScreen(
                onCreate = { nav.navigate(Route.CREATE_PASSWORD) },
                onRestore = { nav.navigate(Route.RESTORE) },
            )
        }
        composable(Route.CREATE_PASSWORD) {
            CreateMasterScreen(controller = creator, onBack = { nav.popBackStack() })
        }
        /*
         * 从备份恢复。**只挂在这张图上**——它的前提是「这台设备上还没有库」
         * （决策(135)：恢复绝不覆盖已有的库）。已解锁那张图上没有这个入口，
         * 想装另一份备份的人得先在设置里删掉现有的库，走的是那条更强的门。
         *
         * 它同样不需要成功回调：恢复成功的最后一步是 `session.adopt`，
         * 相位从 `NoVault` 翻到 `Unlocked`，整棵引导子树连同这个 NavHost
         * 一起被换成保险库列表（决策⑪）。在这儿写一句 `onDone = { ... }`
         * 的话，那行代码永远不会被执行到——同删除页和清空页。
         */
        composable(Route.RESTORE) {
            RestoreScreen(controller = restorer, onBack = { nav.popBackStack() })
        }
    }
}

/* ─────────────────────── 图二：解锁 ─────────────────────── */

@Composable
private fun LockedGraph(nav: NavHostController = rememberNavController()) {
    val repo = LocalRepository.current
    val session = LocalSession.current
    val quick = LocalQuickUnlock.current
    val scope = rememberCoroutineScope()

    /**
     * 解锁控制器同样挂在**图**这一层，理由和建库控制器一样：
     * 派生密钥是个跨几百毫秒的异步动作，页面级的 remember 会在导航时被丢掉。
     * 挂在这里还有第二个好处——主密码页和 PIN 页共用同一个控制器，
     * 于是「在 PIN 页错了三次，切到主密码页」时退避状态是连续的，
     * 换个入口重来这条路自然就堵死了。
     */
    val guard = remember(quick) { QuickUnlockGuard(quick) }
    val controller = remember(repo, session, guard) {
        UnlockController(repo = repo, session = session, guard = guard, scope = scope)
    }
    DisposableEffect(controller) { onDispose { controller.cancel() } }

    /**
     * 「上一次是被自动锁定的」只在进入这张图的那一刻读一次。
     * 不做成可观察状态：它描述的是一件已经发生完的事，
     * 而解锁成功后整棵子树会被换掉，没有人需要看到它变回 None。
     */
    val autoLocked = remember {
        session.lastLockReason == VaultSession.LockReason.AutoTimeout
    }

    /**
     * 绑过快捷解锁就先落到 PIN / 指纹页，否则直接进主密码页。
     *
     * `remember` 不带 key，是要它**在进入这张图的那一刻只算一次**：
     * 连错十次会把快捷解锁关掉，那一刻 `isAnyEnrolled` 会翻成 false，
     * 起始点若跟着变，整张图会在用户眼前重建一次。
     * 那种情况下的去向由页面自己 `navigate`，不靠起始点。
     */
    val start = remember {
        if (quick.isAnyEnrolled) Route.UNLOCK else Route.UNLOCK_MASTER
    }

    NavHost(navController = nav, startDestination = start) {
        composable(Route.UNLOCK) {
            QuickUnlockScreen(
                controller = controller,
                quickUnlock = quick,
                autoLocked = autoLocked,
                onUseMaster = {
                    // 不留返回栈：从主密码页按返回应该是退出应用，
                    // 而不是弹回一个可能已经被关掉的 PIN 页。
                    nav.navigate(Route.UNLOCK_MASTER) {
                        popUpTo(Route.UNLOCK) { inclusive = true }
                    }
                },
            )
        }
        composable(Route.UNLOCK_MASTER) {
            UnlockMasterScreen(
                controller = controller,
                autoLocked = autoLocked,
                // 「忘记主密码了？」弹窗上的次按钮。**普通 navigate，留返回栈**：
                // 这一页是可以反悔的（它自己什么都还没做），
                // 按返回就该原样回到解锁页继续试密码。
                onReset = { nav.navigate(Route.RESET) },
                // 从主密码页切回快捷解锁：只有它还绑着、且没被这次会话关掉才给这个入口。
                onUseQuickUnlock = if (quick.isAnyEnrolled && start == Route.UNLOCK) {
                    {
                        nav.navigate(Route.UNLOCK) {
                            popUpTo(Route.UNLOCK_MASTER) { inclusive = true }
                        }
                    }
                } else null,
            )
        }
        /*
         * 清空重来。**只挂在这张图上**（对比 `Route.SETTINGS_DELETE`
         * 只挂在已解锁那张图上，两者正好互补，见 Routes.kt）。
         *
         * 它不需要任何成功回调，理由同删除页：清完之后
         * `session.onVaultDeleted()` 把相位从 `Locked` 翻回 `NoVault`，
         * 整棵解锁子树连同这个 NavHost 一起被换成欢迎页（决策⑪、决策(122)）。
         * 想在这儿写一句 `onDone = { ... }` 的话，那行代码永远不会被执行到。
         *
         * 只从 `Route.UNLOCK_MASTER` 进得来。PIN / 指纹页上没有这个入口——
         * 那一页的用户手边有一条更该先走的路（「改用主密码」），
         * 而这一页是主密码那条路走到头之后才该出现的东西。
         */
        composable(Route.RESET) {
            ResetVaultScreen(onBack = { nav.popBackStack() })
        }
    }
}

/* ─────────────────────── 图三：已解锁 ─────────────────────── */

@Composable
private fun UnlockedGraph(nav: NavHostController = rememberNavController()) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    /**
     * 从没导出过备份，就先落到备份页，不落到列表页。
     *
     * `remember` 不带 key，是要它**在进入这张图的那一刻只算一次**。
     * 用户导出成功后 `lastBackupAt` 会变，但起始目的地不该跟着变——
     * 那会在他正看着成功提示的时候把整张图重建掉。
     * 导出完成后的去向由页面自己 `navigate`，不靠起始点。
     */
    val start = remember {
        if (session.data?.meta?.lastBackupAt == 0L) Route.FIRST_BACKUP else Route.LIST
    }

    /**
     * 页面间的草稿交接槽，挂在这张图上。
     *
     * 挂在这里而不是做成全局单例，是为了让它的生命周期和「已解锁」这个相位
     * 严格一致：锁定时整棵子树连同 back stack 一起被换掉（决策⑪），
     * 这个对象自然跟着没了。没有人需要记得在锁定时清空它——
     * 「忘了清空」这件事根本没有发生的机会。
     */
    val draft = remember { DraftHandoff() }

    /**
     * 生成器的选项同样挂在这张图上，理由和上面那个交接槽一模一样：
     * 它要活过页面之间的跳转（用户会来回开好几次生成器），
     * 但绝不该活过一次锁定，也绝不该落盘。见 GeneratorSheet.kt。
     */
    val generator = remember { GeneratorHolder() }

    /**
     * 导入控制器挂在**图**这一层，理由和引导图上那两个一样，而且多一条。
     *
     * 一样的那条：落盘是跨几秒的异步动作（一份 500 条的 CSV 要跑一次全库加密写盘），
     * 页面级的 `remember` 会在导航和转屏时被丢掉，那次落盘就成了孤儿协程——
     * 而它已经把数据写进磁盘了，只是没有人再去把结果显示出来。
     *
     * 多的那条：它手里那张表是**明文密码表**，比备份文件还敏感（备份是加密的）。
     * 挂在这里，它的生命周期严格等于「已解锁」这个相位——锁定时整棵子树连同
     * back stack 一起被换掉（决策⑪），这张表跟着一起没。
     * 没有人需要记得「锁定时要清掉导入缓存」，那件事没有发生的机会。
     * 页面自己在 `onDispose` 里还会再清一次：控制器活得比页面长，
     * 而用户退回设置页之后没有理由让那张表继续留着。
     */
    val importer = remember(session) {
        ImportController(session = session, scope = scope)
    }
    DisposableEffect(importer) { onDispose { importer.cancel() } }

    CompositionLocalProvider(
        LocalDraftHandoff provides draft,
        LocalGenerator provides generator,
    ) {
        NavHost(navController = nav, startDestination = start) {
            composable(Route.FIRST_BACKUP) {
                BackupScreen(
                    firstRun = true,
                    onDone = {
                        // 把备份页从栈里弹掉：从列表按返回应该是退出应用，
                        // 而不是退回那道已经走完（或已经跳过）的关卡。
                        nav.navigate(Route.LIST) {
                            popUpTo(Route.FIRST_BACKUP) { inclusive = true }
                        }
                    },
                )
            }
            composable(Route.LIST) {
                VaultListScreen(
                    onOpenEntry = { id -> nav.navigate(Route.detail(id)) },
                    onAdd = { nav.navigate(Route.ADD) },
                    onSearch = { nav.navigate(Route.SEARCH) },
                    onSettings = { nav.navigate(Route.SETTINGS) },
                    // 走设置里那张备份页（`firstRun = false`），不是首次备份那道关卡：
                    // 用户是自己点进来的，不该再被「暂时跳过」这种挡路话术招待一遍。
                    onBackup = { nav.navigate(Route.SETTINGS_BACKUP) },
                )
            }
            composable(Route.SEARCH) {
                SearchScreen(
                    onBack = { nav.popBackStack() },
                    onOpenEntry = { id -> nav.navigate(Route.detail(id)) },
                    onCreateNamed = { name ->
                        // 关键词通过内存交接槽带走，**不进路由参数**——
                        // 路由参数会随 back stack 进 savedInstanceState，
                        // 而搜索词就是库内容的投影。见 DraftHandoff.kt。
                        draft.offerName(name)
                        nav.navigate(Route.ADD)
                    },
                )
            }
            composable(Route.DETAIL) { backStackEntry ->
                // 路由里只带 id，其余一律从内存里的会话取 —— 见 Routes.kt 顶上那条界限。
                EntryDetailScreen(
                    entryId = backStackEntry.arguments?.getString(Route.ARG_ID).orEmpty(),
                    onBack = { nav.popBackStack() },
                    onEdit = { id -> nav.navigate(Route.edit(id)) },
                )
            }
            composable(Route.EDIT) { backStackEntry ->
                // 同样只带 id。编辑页要写的那些字段一个都不进路由——
                // 路由参数会随 back stack 进 savedInstanceState（见 DraftHandoff.kt）。
                EditEntryScreen(
                    entryId = backStackEntry.arguments?.getString(Route.ARG_ID).orEmpty(),
                    // 保存成功和放弃修改都回到详情页：用户是从那儿点铅笔进来的，
                    // 让他回到刚才看的那一条，能立刻核对改动是不是他想要的。
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Route.ADD) {
                AddEntryScreen(
                    onExit = { nav.popBackStack() },
                    /**
                     * 存完直接进详情页，并把新增流**连同它前面那张搜索页**一起弹掉。
                     *
                     * 去详情页而不是回列表：用户刚填了三屏，此刻最想确认的是
                     * 「我填的东西是不是都进去了」，而列表上只看得到一个名字。
                     *
                     * `popUpTo(LIST)` 顺手把搜索页也弹掉了，这是有意的：
                     * 他是从「搜『招商』没搜到」进来的，现在那一条已经存进去了，
                     * 退回一张显示「没找到」的旧结果页只会让他以为没存上。
                     *
                     * id 拿不到时（[cn.localvault.app.ui.add.AddFlow.newestId] 返回 null，
                     * 理论上不该发生）退回列表——那一条**确实已经存进去了**，
                     * 只是没法直接跳过去，绝不能因此报错让用户以为没存上。
                     */
                    onSaved = { id ->
                        if (id == null) {
                            nav.popBackStack(Route.LIST, inclusive = false)
                        } else {
                            nav.navigate(Route.detail(id)) {
                                popUpTo(Route.LIST) { inclusive = false }
                            }
                        }
                    },
                )
            }
            composable(Route.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    // 走设置里那张备份页（`firstRun = false`），和列表页那条提醒条同一个去处。
                    onBackup = { nav.navigate(Route.SETTINGS_BACKUP) },
                    onAbout = { nav.navigate(Route.SETTINGS_ABOUT) },
                    onSecurity = { nav.navigate(Route.SETTINGS_SECURITY) },
                    onChangeMaster = { nav.navigate(Route.SETTINGS_MASTER) },
                    onImport = { nav.navigate(Route.SETTINGS_IMPORT) },
                    onAutofill = { nav.navigate(Route.SETTINGS_AUTOFILL) },
                    onDelete = { nav.navigate(Route.SETTINGS_DELETE) },
                )
            }
            /*
             * 快捷解锁的绑定页。**只挂在这张图（已解锁）上**，不挂在解锁图上：
             * 绑定要借库主密钥（`VaultSession.withVaultKey`），
             * 而那把钥匙只在解锁期间存在。锁着的时候这一页无事可做。
             */
            composable(Route.SETTINGS_SECURITY) {
                SecuritySettingsScreen(
                    onBack = { nav.popBackStack() },
                    onSetupPin = { change -> nav.navigate(Route.settingsPin(change)) },
                )
            }
            /*
             * PIN 设置流。同样只挂在这张图上——`enrollPin` 要借库主密钥。
             *
             * 走完之后 `popBackStack` 回绑定页，那一页的 `remember` 会重新
             * 问一次 prefs，于是 PIN 那一行自己就变成「已开启」了；
             * 不必回传任何结果（结果本来就在 prefs 里，回传等于把同一件事说两遍，
             * 而且那条回传通道是 savedStateHandle —— 见 Routes.kt 顶上那条界限）。
             */
            composable(Route.SETTINGS_PIN) { backStackEntry ->
                val change = backStackEntry.arguments?.getString(Route.ARG_CHANGE).toBoolean()
                PinSetupScreen(
                    mode = if (change) PinSetupModel.Mode.Change else PinSetupModel.Mode.Set,
                    onDone = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }
            /*
             * 修改主密码。同样只挂在这张图上——重新包裹要借库主密钥。
             *
             * 改完之后那一屏上的「现在重新导出备份」直接 navigate 到备份页，
             * **不 popBackStack**：用户按返回时应该回到刚才那张「改完了」的卡片，
             * 而不是被弹回设置页——他刚做完的那件事和备份是连着的，
             * 中途反悔了也该落回原地。
             */
            composable(Route.SETTINGS_MASTER) {
                ChangeMasterScreen(
                    onBack = { nav.popBackStack() },
                    onBackup = { nav.navigate(Route.SETTINGS_BACKUP) },
                )
            }
            /*
             * 删除保险库。**只挂在这张图上**，理由和上面几页不同——
             * 不是「锁着的时候做不了」，是刻意不给锁着的时候做（见 Routes.kt）。
             *
             * 它不需要任何成功回调：删完之后会话相位翻回 NoVault，
             * 整棵子树连同这个 NavHost 一起被换成欢迎页（决策⑪）。
             * 想在这儿写一句 `onDone = { nav.navigate(...) }` 的话，
             * 那行代码永远不会被执行到——这张图那时候已经不存在了。
             */
            composable(Route.SETTINGS_DELETE) {
                DeleteVaultScreen(onBack = { nav.popBackStack() })
            }
            composable(Route.SETTINGS_BACKUP) {
                BackupScreen(
                    firstRun = false,
                    onDone = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }
            /*
             * 从 CSV 导入。**只挂在这张图上**，和恢复页正好反过来（见 Routes.kt）。
             *
             * 结果页上那个「完成」按钮走的是 `onBack`，也就是 `popBackStack` 回设置页，
             * 而不是跳到列表页。用户是从设置里进来的，退回原地是他按返回时预期的位置；
             * 而且他多半接着要去点上面那一行「导出加密备份」——
             * 结果页最后一句正是这么建议的，那一行就在退回去之后的同一张卡片上。
             */
            composable(Route.SETTINGS_IMPORT) {
                ImportScreen(controller = importer, onBack = { nav.popBackStack() })
            }
            /*
             * 自动填充的开关与交代。**只挂在这张图（已解锁）上**，
             * 理由和别的设置子页不一样——它技术上根本不需要解锁，
             * 是刻意跟着设置主页走的。见 Routes.SETTINGS_AUTOFILL 上那段。
             */
            composable(Route.SETTINGS_AUTOFILL) {
                AutofillSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Route.SETTINGS_ABOUT) {
                AboutScreen(
                    onBack = { nav.popBackStack() },
                    /*
                     * 关于页那句指路牌的落点。用 navigate 而不是 popBackStack + navigate：
                     * 用户是从「关于」拐进来的，看完那七条按返回，
                     * 该回到关于页而不是设置主页——他刚才正在读的是这一页。
                     */
                    onAutofill = { nav.navigate(Route.SETTINGS_AUTOFILL) },
                )
            }
        }
    }
}
