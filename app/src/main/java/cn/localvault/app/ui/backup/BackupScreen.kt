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

package cn.localvault.app.ui.backup

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.LocalRepository
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.ExplainBlock
import cn.localvault.app.ui.components.ExplainRow
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.TextLink
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType
import cn.localvault.app.ui.util.Fmt

/**
 * `application/octet-stream` —— 系统据此给文件选择器一个默认扩展名。
 * 某些 ROM 会因此把 `.lvault` 改成 `.lvault.bin`，无所谓：
 * 导入侧认的是文件头里的 `LVAULT` 标识，不是扩展名。见 [Fmt.backupFileName]。
 */
private const val BACKUP_MIME = "application/octet-stream"

/**
 * 导出加密备份。
 *
 * 首次引导（[firstRun] = true）时它是主图的起始目的地，挡在保险库列表前面；
 * 之后在「设置 → 备份与迁移」里复用同一张页面，只是文案和退路不同。
 *
 * ── 为什么「跳过」是允许的 ──
 *
 * 「强制备份」听起来更负责，实际是把用户锁死在一个他此刻可能完不成的动作上：
 * 存储空间满了、公司手机禁用了文件选择器、就是想先随便记两条试试——
 * 这些人会得到一个进不去的 App，然后卸载。
 *
 * 真正管用的做法是**不让它消失**：跳过不写 `lastBackupAt`，
 * 于是下次解锁这一页照样挡在前面，列表页也常驻提醒。
 * 一次不痛不痒的打断，重复到用户做完为止，比一堵墙有效。
 */
@Composable
fun BackupScreen(
    firstRun: Boolean,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val session = LocalSession.current
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()

    val controller = remember(repo, session) { ExportController(repo, session, scope) }
    DisposableEffect(controller) { onDispose { controller.cancel() } }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME)
    ) { uri ->
        // 不管用户是选了位置还是按了返回，中断都在这一刻结束，
        // 自动锁定立即恢复常规超时——宽限只覆盖「人还在流程里」的那段。
        session.endSystemInterlude()
        if (uri != null) controller.export(SafExportSink(ctx.contentResolver, uri))
    }

    fun pick() {
        controller.reset()
        // 必须在拉起系统界面**之前**打招呼：Activity 一 onStop，
        // 会话那边就开始倒计时了，晚一步就来不及。
        session.beginSystemInterlude()
        picker.launch(Fmt.backupFileName())
    }

    VaultScreen(
        title = if (firstRun) "先备份一次" else "导出加密备份",
        onBack = onBack,
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

            Text(
                if (firstRun)
                    "现在这个库只存在于这台手机上。手机丢了、摔了、被恢复出厂，数据就没有第二份。"
                else
                    "导出的文件就是完整的保险库，换机时拷过去、用同一个主密码打开。",
                style = VaultType.Body,
                color = VaultColors.Dim,
            )

            /*
             * v3：这一格原来是三条各带两行正文的说明，加上一条三行的警告，
             * 一共九行字压在「选择位置并导出」上面——首次引导时那个按钮
             * 在常见机型上要往下滚才看得见，而这一屏的全部目的就是让人按到它。
             *
             * 现在每条只留一句判断句，完整那三段和网盘那段收进下面那一行
             * （`components/Explain.kt` 定的规矩）。一个字都没删。
             */
            VaultCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Eyebrow("这份文件是什么")
                    Fact(Glyph.Lock, "已经加密好了", "导出的就是保险库文件本身，里面没有主密码。")
                    Fact(Glyph.Key, "只有主密码能打开它", "所以别把主密码和这个文件放在同一个地方。")
                    Fact(Glyph.Share, "存哪由你决定", "手机、U 盘、电脑都行，走系统的文件选择器。")
                }
            }

            // 网盘那条留在外面，但收成一句。它是这一页唯一一句「这么做会有代价」，
            // 收进弹窗等于让最该看见它的那批人（顺手传网盘的）看不见。
            Banner(
                text = "存到网盘等于把它放上别人的服务器——那时主密码就是唯一的一道屏障了。",
                tone = BannerTone.Warn,
            )

            ExplainRow(
                title = "这份备份的细节",
                subtitle = "加密方式、主密码放哪、存到网盘的代价",
                detail = listOf(
                    ExplainBlock.Section(
                        "已经加密好了",
                        "导出的就是保险库文件本身，和手机里存的那一份格式相同。里面没有主密码。",
                    ),
                    ExplainBlock.Section(
                        "只有主密码能打开它",
                        "所以主密码不能和这个文件存在同一个地方——那等于把钥匙贴在锁上。",
                    ),
                    ExplainBlock.Section(
                        "存哪由你决定",
                        "手机文件夹、U 盘、电脑都行。整个过程走系统的文件选择器，本应用没有存储权限。",
                    ),
                    ExplainBlock.Section(
                        "关于网盘",
                        "存到网盘意味着把这份加密文件放上了别人的服务器。它依然是加密的，" +
                            "但那时主密码就是唯一的一道屏障了——弱口令在这种场景下会真的出事。",
                    ),
                ),
            )

            StatusArea(controller.step)

            when (controller.step) {
                is ExportController.Step.Done -> {
                    BrassButton(
                        if (firstRun) "完成，进入保险库" else "完成",
                        onClick = onDone,
                    )
                    GhostButton("再导出一份到别处", onClick = { pick() })
                }
                else -> {
                    BrassButton(
                        "选择位置并导出",
                        onClick = { pick() },
                        enabled = !controller.busy,
                        busy = controller.busy,
                    )
                    if (firstRun && !controller.busy) {
                        TextLink(
                            "暂时跳过（下次打开还会提醒）",
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Fact(glyph: Glyph, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        VaultIcon(glyph, tint = VaultColors.Brass, size = 19.dp, modifier = Modifier.padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = VaultType.RowName, color = VaultColors.Text)
            Text(body, style = VaultType.Sub, color = VaultColors.Dimmer)
        }
    }
}

/**
 * 状态区。
 *
 * 三个进行中的阶段分别说明在干什么，是因为「校验」这一步会让用户多等一会儿，
 * 而他有权知道那一会儿不是卡住了，是在确认这份备份真的能用。
 */
@Composable
private fun StatusArea(step: ExportController.Step) {
    when (step) {
        ExportController.Step.Idle -> Unit

        ExportController.Step.Sealing -> Progress("正在读取保险库并自检…")
        ExportController.Step.Writing -> Progress("正在写入所选位置…")
        ExportController.Step.Verifying -> Progress("正在把文件读回来核对，确认这份备份能用…")

        is ExportController.Step.Done -> VaultCard(
            Modifier.fillMaxWidth(),
            background = VaultColors.Slab2,
            borderColor = VaultColors.Jade.copy(alpha = 0.35f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VaultIcon(Glyph.Check, tint = VaultColors.Jade, size = 19.dp)
                    Text("备份已写出并校验通过", style = VaultType.H2, color = VaultColors.Jade)
                }
                Text(step.where, style = VaultType.MonoSmall, color = VaultColors.Text)
                Text(
                    "${Fmt.bytes(step.bytes)} · 已读回比对，内容一致",
                    style = VaultType.MonoSmall,
                    color = VaultColors.Dimmer,
                )
            }
        }

        is ExportController.Step.Failed -> Banner(text = step.message, tone = BannerTone.Danger)
    }
}

@Composable
private fun Progress(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            color = VaultColors.Brass,
            strokeWidth = 1.5.dp,
        )
        Text(text, style = VaultType.MonoSmall, color = VaultColors.Dim)
    }
}
