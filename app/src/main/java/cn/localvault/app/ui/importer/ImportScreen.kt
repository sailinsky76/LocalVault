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

package cn.localvault.app.ui.importer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.ExplainNote
import cn.localvault.app.ui.components.explain
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.restore.SafImportSource
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType

/**
 * 通配 MIME。理由和恢复页那一处一字不差：填一个具体的类型，
 * 那些被 ROM 存成 `text/plain`、被网盘客户端改了类型、被用户重命名过的导出文件
 * 在选择器里就会变灰点不动，而用户得到的结论是「我的导出文件不见了」。
 *
 * 这一页还多一层理由：CSV 的 MIME 本身就没有统一的写法
 * （`text/csv`、`text/comma-separated-values`、`application/vnd.ms-excel`
 * 都在野外见得到，Chrome 导出的那份在不少机器上被登记成最后这一个）。
 * 认文件靠的是内容——严格 UTF-8 / GBK 解码 + RFC 4180 状态机，从头到尾不看扩展名。
 */
private val OPEN_ANY = arrayOf("*/*")

/**
 * 从 CSV 导入（`Route.SETTINGS_IMPORT`，只挂在已解锁那张图上）。
 *
 * ── 一页四段，顺序是有理由的 ──
 *
 *   1. **先说这是明文**（[CsvText.PLAINTEXT_NOTE]），再让人选文件；
 *   2. 选文件 → 逐列核对映射；
 *   3. 预览：新增多少、覆盖多少、跳过多少，撞上的按行号列出，处置三选一；
 *   4. 导入 → 结果 + **再说一次删源文件**。
 *
 * 第 1 条摆在最前面而不是最后，是这一页唯一一个和常规做法反着来的地方。
 * 常规做法是导完之后提示「记得删掉源文件」——那时候他已经把文件从旧手机
 * 传到新手机、发过一次微信文件传输、可能还在电脑的下载目录里留了一份。
 * 提前说，他至少有机会选择「先把它挪到一个不会被同步走的地方」。
 * 导完之后**再说一遍**（结果段），因为那才是他真正会去删的时刻。
 *
 * ── 这一页显示不了任何一格内容 ──
 *
 * 没有表格预览，没有「前五行长这样」。理由写在 `ImportPieces.kt` 顶上。
 * 用户失去的是「一眼看出这份文件对不对」的便利，换来的是
 * 这一屏在被人凑过来看一眼时不会摊开几百个明文口令。
 * 补偿是列名、行号、条数和四类跳过理由——它们足够回答
 * 「哪一列是什么」「哪些行没进来、为什么」这两个真正要紧的问题。
 *
 * ── 为什么这一页不做「撤销」 ──
 *
 * 因为它做不到「撤销」这两个字承诺的事。新增的那部分删得干净，
 * 而覆盖那部分改掉的是用户原有的条目，那些旧值在落盘的那一刻就没了
 * （决策⑧：这个应用没有回收站，删掉就是删掉）。
 * 一个只能撤销一半的按钮比没有按钮更危险。
 * 所以力气全花在**按下去之前**：默认处置是最不会毁数据的「跳过」，
 * 覆盖的合并规则在按钮上方逐条写明，撞上的行按行号列出来。
 */
@Composable
fun ImportScreen(
    controller: ImportController,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val session = LocalSession.current

    /** 正在改哪一列的角色。null = 没开弹窗。 */
    var editingColumn by remember { mutableStateOf<Int?>(null) }

    /**
     * 离开这一页就把那份明文表丢掉。
     *
     * 控制器挂在**图**那一层（落盘是跨几秒的异步动作，不能挂在页面上），
     * 于是它会活过一次 `popBackStack`。而它手里那张表是一份明文密码表，
     * 没有任何理由在用户已经退回设置页之后继续留着。
     */
    DisposableEffect(controller) { onDispose { controller.discard() } }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 不管选中了还是按了返回，中断都在这一刻结束（同备份页）。
        session.endSystemInterlude()
        if (uri != null) controller.pick(SafImportSource(ctx.contentResolver, uri))
    }

    fun pick() {
        controller.discard()
        // 必须在拉起系统界面**之前**打招呼：Activity 一 onStop 会话就开始倒计时了。
        // 这一页和恢复页不同——这里是已解锁相位，自动锁定是真的会发生的
        // （而且导入的人正在两台手机之间来回看，翻文件夹翻得比谁都久）。
        session.beginSystemInterlude()
        picker.launch(OPEN_ANY)
    }

    val step = controller.step
    val done = step as? ImportController.Step.Done
    val failed = step as? ImportController.Step.Failed
    val preview = step is ImportController.Step.Preview

    // 落盘中不许退出：这一步跨几秒，中途走掉的人不知道导进去了没有。
    BackHandler(enabled = controller.busy) { }

    VaultScreen(
        title = "从 CSV 导入",
        onBack = if (controller.busy) null else onBack,
        seal = { DefaultSeal() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            if (done == null) {
                // 平铺的只留一句判断句，「不联网 / 只读 / 没有存储权限」这些性质
                // 收进详细说明（v3，同恢复页与备份页）。
                ExplainNote(
                    "从别的密码管理器或浏览器导出的 CSV，可以整份搬进来。",
                    style = VaultType.Body,
                    color = VaultColors.Dim,
                    detailTitle = "从 CSV 导入是怎么回事",
                    detail = explain(
                        "从别的密码管理器或浏览器导出的 CSV 文件，可以整份搬进来。" +
                            "整个过程不联网，那份文件只被读，一个字节都不会被改。",
                        "选文件走的是系统的文件选择器。本应用没有存储权限，" +
                            "只拿得到你亲手挑中的那一个文件，别的什么都看不见。",
                    ),
                )

                // 第一屏就说，不等导完（见类注释）。
                Banner(text = CsvText.PLAINTEXT_NOTE, tone = BannerTone.Warn)
            }

            /* ══════════ 一、选文件 ══════════ */

            if (done == null) {
                Eyebrow("CSV 文件")

                when {
                    step is ImportController.Step.Reading -> Progress("正在读取并解析…")

                    controller.fileName != null && preview -> FileCard(
                        name = controller.fileName!!,
                        rows = controller.rowCount,
                        columns = controller.header.size,
                    )

                    // 「没有存储权限」那半句挪进了页顶的详细说明：它是这一页的性质，
                    // 不是「还没选文件」这个状态的说明，摆在这儿只会把下面那个按钮往下推。
                    else -> Text(
                        "还没有选择文件。",
                        style = VaultType.Sub,
                        color = VaultColors.Dimmer,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }

                if (!controller.busy) {
                    GhostButton(
                        text = if (preview) "换一个文件" else "选择 CSV 文件",
                        onClick = { pick() },
                    )
                }
            }

            /* ══════════ 二、列映射 ══════════ */

            if (preview) {
                val plan = controller.plan
                if (plan != null) {
                    Eyebrow("每一列是什么")

                    Text(
                        CsvMapping.summary(plan),
                        style = VaultType.Sub,
                        color = VaultColors.Dim,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )

                    VaultCard(Modifier.fillMaxWidth()) {
                        Column {
                            plan.header.forEachIndexed { i, name ->
                                if (i > 0) HairLine()
                                ColumnMapRow(
                                    name = name,
                                    role = plan.roleOf(i),
                                    onClick = { editingColumn = i },
                                )
                            }
                        }
                    }

                    // 「恢复自动识别」只在用户真的改过之后才出现。一进来就摆着的话，
                    // 它会被读成「这份识别结果是可疑的，你该点一下这里」。
                    // 自动那一份按表头缓存：表头不变它就不变，不必每帧重认一次。
                    val auto = remember(plan.header) { CsvMapping.plan(plan.header).assign }
                    if (plan.assign != auto) {
                        GhostButton(text = "恢复自动识别", onClick = { controller.resetRoles() })
                    }

                    controller.notes.forEach { NoteLine(it) }

                    plan.blockers().forEach { Banner(text = it, tone = BannerTone.Danger) }
                }
            }

            /* ══════════ 三、预览 ══════════ */

            if (preview && controller.blockers.isEmpty()) {
                val outcome = controller.outcome
                val hits = controller.candidates.filter { it.willImport && it.hit != null }

                Eyebrow("会发生什么")

                if (controller.recomputing) {
                    Progress("正在重新核对…")
                } else {
                    Text(
                        controller.summary,
                        style = VaultType.Body,
                        color = VaultColors.Text,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )

                    VaultCard(Modifier.fillMaxWidth()) {
                        Column {
                            CountRow("新增条目", outcome.add.size, highlight = true)
                            HairLine()
                            CountRow("覆盖已有条目", outcome.replace.size, highlight = true)
                            HairLine()
                            CountRow(
                                "跳过",
                                outcome.skippedByRow + outcome.skippedByPolicy,
                            )
                        }
                    }

                    // 跳过的按理由归并，一类一行——一行一条的话，
                    // 一份带着两百行分组行的 LastPass 导出会在这儿铺满整屏。
                    controller.skipCounts.forEach { (reason, n) ->
                        NoteLine("$n 行：${reason.note}", Glyph.Minus)
                    }

                    /* ── 撞车 ── */

                    if (hits.isNotEmpty()) {
                        Eyebrow("和库里已有的条目撞上了")
                        Text(
                            "下面这些行看起来和库里已有的条目是同一条。" +
                                "**这只是「像」，不是「是」**——所以怎么处置由你决定，" +
                                "默认是最不会毁数据的那一个。",
                            style = VaultType.Sub,
                            color = VaultColors.Dim,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                        HitList(hits)
                        PolicyChoice(current = controller.policy, onPick = { controller.setPolicy(it) })
                    }

                    outcome.noteTexts().forEach { NoteLine(it) }
                }
            }

            /* ══════════ 四、失败 ══════════ */

            failed?.let { f ->
                Banner(
                    text = f.text,
                    tone = BannerTone.Danger,
                    actionText = f.kind.action,
                    onAction = {
                        controller.dismissError()
                        // 「换一个文件」这个按钮得真的把选择器拉起来，
                        // 否则用户点完只看到横幅消失，然后在一张空页面上愣住。
                        if (f.kind == ImportController.Fail.PickAnother) pick()
                    },
                )
            }

            /* ══════════ 五、导入 / 结果 ══════════ */

            if (done != null) {
                ResultSection(done.report, onFinish = onBack)
            } else if (preview) {
                BrassButton(
                    text = "导入",
                    onClick = { controller.commit() },
                    enabled = controller.canCommit,
                    busy = controller.busy,
                )
                if (!controller.canCommit && !controller.busy && !controller.recomputing) {
                    // 灰按钮必须配一句解释（决策(61)）。
                    Text(
                        blockReason(controller),
                        style = VaultType.Sub,
                        color = VaultColors.Dimmer,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                if (controller.busy) Progress("正在写入保险库…")
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    /* ── 角色选择弹窗 ── */

    val col = editingColumn
    val plan = controller.plan
    if (col != null && plan != null) {
        RolePickerDialog(
            columnName = plan.header.getOrNull(col).orEmpty(),
            current = plan.roleOf(col),
            onPick = { role ->
                controller.setRole(col, role)
                editingColumn = null
            },
            onDismiss = { editingColumn = null },
        )
    }
}

/* ─────────────────────── 分段 ─────────────────────── */

/**
 * 选中的文件：名字 + 几行几列。
 *
 * 名字用等宽字体单独一行，理由同恢复页——这条路上最常见的失误是在选择器里点错，
 * 而把名字摆出来，用户常常自己就看出来点的是哪个了。
 * 行数列数是这一页唯一能提前给出的「这份文件有多大」的量，
 * 一个只有 3 行的文件多半不是他要导的那份。
 */
@Composable
private fun FileCard(name: String, rows: Int, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            name,
            style = VaultType.MonoSmall,
            color = VaultColors.Text,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            "$rows 行数据，$columns 列。表头那一行不算在内，也不会被导入。",
            style = VaultType.Sub,
            color = VaultColors.Dimmer,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/**
 * 导完了。
 *
 * ── 这一屏为什么不叫「导入成功」 ──
 *
 * 因为成功与否用户自己会判断，而他判断的依据是那三个数字对不对得上他的预期。
 * 一句「导入成功」加一个对钩，会让人直接跳过下面那句删源文件的话——
 * 而那句话是这一整页最要紧的一句：他手上那份 CSV 现在还躺在下载目录里，
 * 任何一个装了文件管理器的应用都读得到里面的全部密码，
 * 它比这个保险库好打开得多。
 *
 * 所以这一屏的顺序是：数字 → 记账 → **删源文件**（红色横幅）→ 完成。
 * 那条横幅是 Danger 而不是 Warn，是这一页唯一一次用红色：
 * 它说的是一件此刻正在发生的、真实的暴露。
 */
@Composable
private fun ResultSection(report: ImportController.Report, onFinish: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Eyebrow("导入结果")

        VaultCard(Modifier.fillMaxWidth()) {
            Column {
                CountRow("新增了", report.added, highlight = true)
                HairLine()
                CountRow("覆盖了", report.replaced, highlight = true)
                HairLine()
                CountRow("跳过了", report.skipped)
            }
        }

        if (report.total == 0) {
            Text(
                "这一次没有任何条目被写进保险库——上面那些行要么被跳过了，" +
                    "要么在你核对期间已经处理过了。库还是原来的样子。",
                style = VaultType.Sub,
                color = VaultColors.Dim,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }

        report.notes.forEach { NoteLine(it) }

        Banner(text = report.sourceFileReminder, tone = BannerTone.Danger)

        Text(
            "导进来的条目和手工新增的没有任何区别：可以逐条修改、删除，" +
                "也会跟着下一次备份一起被导出。建议现在重新导出一次加密备份——" +
                "上一份备份里还没有这些条目。",
            style = VaultType.Sub,
            color = VaultColors.Dim,
            modifier = Modifier.padding(horizontal = 2.dp),
        )

        BrassButton(text = "完成", onClick = onFinish)
    }
}

/**
 * 导入按钮为什么是灰的。
 *
 * 三种情况分别说，因为下一步完全不同：还在算（等一下）、映射不全（上面去改）、
 * 算下来无事可做（改处置，或者这份文件本来就没有新东西）。
 */
private fun blockReason(c: ImportController): String = when {
    c.plan == null -> "先选一个 CSV 文件。"
    c.blockers.isNotEmpty() -> "上面还有没指定的列，指定完才能导。"
    c.outcome.total == 0 && c.candidates.any { it.hit != null } ->
        "按现在的处置（${c.policy.label}），这份文件里没有任何一行会被写进来——" +
            "它们都和库里已有的条目撞上了。想更新它们的话，把处置改成「覆盖」。"
    else -> "这份文件里没有可以导入的行。上面的跳过理由说明了每一行是为什么。"
}

@Composable
private fun Progress(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = VaultColors.Brass,
            strokeWidth = 1.6.dp,
        )
        Text(text, style = VaultType.Sub, color = VaultColors.Dim)
    }
}
