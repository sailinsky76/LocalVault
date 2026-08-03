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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import cn.localvault.app.BuildConfig
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.LocalQuickUnlock
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.PresetChip
import cn.localvault.app.ui.components.SettingRow
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.list.VaultIndex
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType

/**
 * 设置页。
 *
 * ── 这一页刻意不做的事 ──
 *
 * **不做「安全评分」。** 那种「你的安全指数 82 分」看着专业，实际有两个问题：
 * 一是构成完全是我们自己编的（凭什么开指纹加 15 分、剪贴板 30 秒扣 5 分），
 * 二是它会让用户去**刷分**而不是理解风险——为了那 100 分把自动锁定调成「立即」，
 * 用两天就烦了，索性把整个 App 卸了。这一页只做两件事：
 * 如实显示当前是什么状态，以及在设置真有代价时把代价写出来。
 *
 * ── 改动立刻落盘 ──
 *
 * 和条目一样（`VaultSession` 的第 2 条规矩），点一下就写文件，没有「保存」按钮。
 * 设置项本来就是一次点击表达一个完整意图，不存在「改到一半」的中间态，
 * 也就不需要编辑页那种脏检查和放弃拦截（决策(59)/(60) 管的是表单，不是开关）。
 *
 * 但要注意一个反直觉之处：**改设置会重写库文件，却不计入「未备份改动」。**
 * 那个计数按条目的 `updatedAt` 算（决策㉞），meta 的改动不碰任何条目。
 * 这是故意的——备份要保住的是用户攒下来的账号密码，
 * 一个自动锁定时长丢了没人会心疼，为它响一次备份提醒，
 * 等于拿真正要紧的那次提醒去换一件无关紧要的事。
 * 对比决策(52)：收藏算改动，因为收藏是**条目的内容**。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onBackup: () -> Unit,
    onAbout: () -> Unit,
    onSecurity: () -> Unit,
    onChangeMaster: () -> Unit,
    onImport: () -> Unit,
    /**
     * 去自动填充那一页。
     *
     * **刻意没有默认值**，理由同 `onSecurity`（M3-6b-1）：这一页上又长出一个
     * 能点的入口，参数可省略的话，某天有人复制一份调用忘了传，
     * 那一行就变成点了没反应的死行，而编译器一声不吭。让它编译不过更省事。
     */
    onAutofill: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val session = LocalSession.current
    val quick = LocalQuickUnlock.current
    val state by session.state.collectAsState()

    // 锁定那一瞬间导航相位会换掉整棵子树，但这一帧可能还会被画一次。
    // 与其让它去读一个刚被清空的 data，不如什么都不画。和列表页同一处理。
    val data = (state as? VaultSession.State.Unlocked)?.data ?: return
    val meta = data.meta

    var failure by remember { mutableStateOf<String?>(null) }

    /**
     * 自动填充那一行的副标题要现问系统，而这个状态在别处会被改掉。
     * 两条回来的路，各有各的刷新机制：
     *
     * · 从**自动填充那一页**回来 —— 导航切走时这一页整棵子树被 dispose，
     *   回来时 `remember` 自己就重算了（同 PIN 设置流回到快捷解锁页）。
     * · 从**系统设置**直接回来（用户在别处把服务换掉了）—— 那条路不经过导航，
     *   靠的是下面这个 ON_START 观察者。和 `SecuritySettingsScreen` 里
     *   那个 revision 是同一套写法、同一个理由。
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
    val autofill = remember(revision) { context.autofillAvailability() }

    val backup = remember(data.entries, meta.lastBackupAt) {
        SettingsModel.backupSummary(
            lastBackupAt = meta.lastBackupAt,
            changedSince = if (meta.lastBackupAt <= 0L) 0
            else VaultIndex.changedSince(data.entries, meta.lastBackupAt),
        )
    }

    VaultScreen(title = "设置", onBack = onBack, seal = { DefaultSeal() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (failure != null) {
                Banner(failure!!, tone = BannerTone.Danger)
            }

            /* ───────────────── 安全 ───────────────── */

            Eyebrow("安全", modifier = Modifier.padding(start = 4.dp, top = 6.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                Column {
                    ChoiceBlock(
                        title = "自动锁定",
                        currentLabel = SettingsModel.autoLockLabel(meta.autoLockSeconds),
                        options = SettingsModel.autoLockOptions(meta.autoLockSeconds),
                        current = meta.autoLockSeconds,
                        labelOf = SettingsModel::autoLockLabel,
                        note = SettingsModel.autoLockNote(meta.autoLockSeconds),
                        onPick = { seconds ->
                            val r = session.updateMeta { it.copy(autoLockSeconds = seconds) }
                            failure = if (r.isSuccess) null
                            else "设置没能写进保险库，刚才那一下没有生效。"
                        },
                    )
                    HairLine()
                    /**
                     * 快捷解锁。**只是一个入口，开关在里面那一页。**
                     *
                     * 不在这里直接摆两个开关：开启指纹要当场弹一次系统认证、
                     * 开启 PIN 要走一段设置流，两者都不是「点一下就完事」的动作，
                     * 而这一排上面那两行（自动锁定、剪贴板）恰恰都是。
                     * 混在一起会让用户以为这一行也是点一下就切换。
                     *
                     * 副标题如实写当前开着什么，**不做任何评判**——
                     * 没开启时只写「每次都要输主密码」这个事实，不写「建议开启」：
                     * 一来那不是真的（主密码本来就是最强的那道），
                     * 二来这一页早就定过规矩，如实显示状态，不打分不劝导（决策(95)）。
                     */
                    SettingRow(
                        title = "快捷解锁",
                        subtitle = QuickUnlockModel.summary(
                            pinEnrolled = quick.isPinEnrolled,
                            biometricEnrolled = quick.isBiometricEnrolled,
                        ),
                        showChevron = true,
                        onClick = onSecurity,
                    )
                    HairLine()
                    ChoiceBlock(
                        title = "剪贴板自动清除",
                        currentLabel = SettingsModel.clipboardLabel(meta.clipboardClearSeconds),
                        options = SettingsModel.clipboardOptions(meta.clipboardClearSeconds),
                        current = meta.clipboardClearSeconds,
                        labelOf = SettingsModel::clipboardLabel,
                        note = SettingsModel.clipboardNote(meta.clipboardClearSeconds),
                        onPick = { seconds ->
                            val r = session.updateMeta { it.copy(clipboardClearSeconds = seconds) }
                            failure = if (r.isSuccess) null
                            else "设置没能写进保险库，刚才那一下没有生效。"
                        },
                    )
                    HairLine()
                    /**
                     * 修改主密码。**放在这一分区的末尾，不放在两个开关中间。**
                     *
                     * 上面那三行是日常会调的东西（自动锁定、快捷解锁、剪贴板），
                     * 而这一行一个用户可能一辈子只点一次。把它夹在中间，
                     * 唯一的效果是增加误触——而这一行是本分区里**唯一一个
                     * 会让用户手上那份备份的口令过期**的动作（决策(114)）。
                     *
                     * 副标题只报事实，不写「建议定期更换」：这个库不联网，
                     * 定期更换一个从没离开过设备的口令，防的是什么呢。
                     * 但备份比这次修改还早的时候要说出来——那件事用户不会自己想到。
                     */
                    val master = ChangeMasterModel.rowSummary(
                        masterChangedAt = meta.masterChangedAt,
                        lastBackupAt = meta.lastBackupAt,
                    )
                    SettingRow(
                        title = "修改主密码",
                        subtitle = master.text,
                        tint = if (master.urgent) VaultColors.Brass else VaultColors.Text,
                        showChevron = true,
                        onClick = onChangeMaster,
                    )
                }
            }

            /* ───────────────── 自动填充 ───────────────── */

            /*
             * 单独一格，**不并进上面的「安全」分区**。
             *
             * 安全分区里那四行管的都是「谁能打开这个库」；自动填充管的是
             * 「打开之后，里面的东西怎么出去」——方向正好相反。
             * 更实际的一条：那四行改的值都在库里、点一下就落盘，而这一行
             * 改的东西**根本不在库里**（在系统那边），它甚至改不了，只能把人送过去。
             * 混在一起，用户会以为它和上面几行一样点一下就切换。
             */
            Eyebrow("自动填充", modifier = Modifier.padding(start = 4.dp, top = 10.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                SettingRow(
                    title = "自动填充",
                    subtitle = AutofillSettingsModel.settingsRowSummary(autofill),
                    // 永远不变色。没开自动填充不是一件待办，见 settingsRowUrgent 上那段。
                    tint = if (AutofillSettingsModel.settingsRowUrgent(autofill)) VaultColors.Brass
                    else VaultColors.Text,
                    showChevron = true,
                    onClick = onAutofill,
                )
            }

            /* ───────────────── 备份 ───────────────── */

            Eyebrow("备份", modifier = Modifier.padding(start = 4.dp, top = 10.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                Column {
                    SettingRow(
                        title = "导出加密备份",
                        subtitle = backup.text,
                        // 有事要办时把这一行标成黄铜色。不另加横幅——
                        // 用户是自己走进设置页的，不需要再被拦一次（列表页那条才是拦路的）。
                        tint = if (backup.urgent) VaultColors.Brass else VaultColors.Text,
                        showChevron = true,
                        onClick = onBackup,
                    )
                    HairLine()
                    /*
                     * 从 CSV 导入。放在「备份」这一格里，和导出做邻居。
                     *
                     * 不为它单开一个「导入 / 导出」分区：这两件事在用户心里是同一件——
                     * 「我的东西怎么进来、怎么出去」。而副标题里那句「明文」是刻意的，
                     * 它是这一行唯一比「导出加密备份」更需要被提前知道的性质：
                     * 那一行产出的是加密文件，这一行吃进去的是一张任何应用都读得到的明文表。
                     *
                     * 这一行**永远不变色**（对比上面那行会转黄铜色）：
                     * 没有导入过不是一件待办，绝大多数用户一辈子不会点它。
                     */
                    SettingRow(
                        title = "从 CSV 导入",
                        subtitle = "把别处导出的 CSV 搬进来。那种文件是明文的。",
                        showChevron = true,
                        onClick = onImport,
                    )
                }
            }

            /* ───────────────── 关于 ───────────────── */

            Eyebrow("关于", modifier = Modifier.padding(start = 4.dp, top = 10.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                SettingRow(
                    title = "关于本应用",
                    subtitle = "权限、依赖、加密参数",
                    value = BuildConfig.VERSION_NAME,
                    valueMono = true,
                    showChevron = true,
                    onClick = onAbout,
                )
            }

            Spacer(Modifier.height(14.dp))

            /**
             * 立即锁定。
             *
             * 它解决的是一个自动锁定解决不了的场景：**用户要当面把手机递给别人**
             * （给同事看一张照片、把手机放在桌上去接杯水）。这时候他需要的是
             * 「现在就锁」，而不是「再等 60 秒」——而 60 秒恰恰是他把手机递出去
             * 之后那段最没法控制的时间。
             *
             * 放在设置页底部而不是列表页顶栏：顶栏那几个 44dp 方块已经挤着
             * 搜索和设置了，再塞一个锁的图标，误触的代价是当场把自己关在门外。
             * 而真要用它的时候，多点一下进设置完全来得及。
             */
            GhostButton("立即锁定", onClick = { session.lock() })

            Text(
                "平时不用手动锁，自动锁定已经在管这件事。",
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )

            /* ───────────────── 危险区 ───────────────── */

            /**
             * 「删除保险库」**单独占一个分区，而且在整页最下面。**
             *
             * 它本来更"像"是安全分区的一员（改主密码就在那儿），但那正是不能放的理由：
             * 安全分区里其余四行都是日常会点的东西，把一个不可逆的动作混进去，
             * 唯一的效果是增加误触。单开一个只有一行的分区看着浪费，
             * 但那一行周围的空白本身就是一道门槛——手指滑到这儿会停一下。
             *
             * 分区标题写「危险区」，副标题只陈述后果（`ROW_SUBTITLE`），
             * 不写「谨慎操作」「不可撤销！」这类感叹。真心想删的人会被吓唬话激怒，
             * 误点进来的人在下一页第一屏就会退出去——那一页摆的是条目数和备份状况，
             * 比这儿多写三个感叹号有用得多。
             *
             * 这一行也**永远不变色**（对比备份和改主密码那两行会转黄铜色）：
             * 我们没有任何立场提醒任何人去删自己的数据。见 `DeleteVaultModel.ROW_SUBTITLE`。
             */
            Eyebrow("危险区", modifier = Modifier.padding(start = 4.dp, top = 16.dp))

            VaultCard(Modifier.fillMaxWidth()) {
                SettingRow(
                    title = "删除保险库",
                    subtitle = DeleteVaultModel.ROW_SUBTITLE,
                    tint = VaultColors.Rust,
                    showChevron = true,
                    onClick = onDelete,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ─────────────────────────── 一个档位块 ─────────────────────────── */

/**
 * 「标题 + 当前值 + 一排可点的档位 + 可选的一句说明」。
 *
 * 用预设片而不是下拉菜单或滑块，理由和长度步进器那边（决策(75)）一样：
 * 点一下**就是把值填进去**，不另开一套「你正处于某个选项中」的状态，
 * 也不需要先展开一个菜单才能看到有哪些选择——
 * 全部档位一眼摆在那里，用户能立刻知道这个设置的上下限在哪。
 *
 * 当前值同时出现在两个地方（右上角的文字 + 高亮的那一片），是有意的重复：
 * 一排片子里哪个被选中，在小屏幕上要眯着眼看颜色差别，
 * 而右上角那行字任何时候都读得出来。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceBlock(
    title: String,
    currentLabel: String,
    options: List<Int>,
    current: Int,
    labelOf: (Int) -> String,
    note: String?,
    onPick: (Int) -> Unit,
) {
    Column {
        SettingRow(title = title, value = currentLabel, valueMono = true)
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            options.forEach { seconds ->
                PresetChip(
                    text = labelOf(seconds),
                    // 用归一后的值比，免得库里躺着 -1 时「立即」那一片高亮不起来
                    selected = seconds.coerceAtLeast(0) == current.coerceAtLeast(0),
                    onClick = { if (seconds != current) onPick(seconds) },
                )
            }
        }
        if (note != null) {
            Text(
                note,
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 13.dp),
            )
        }
    }
}
