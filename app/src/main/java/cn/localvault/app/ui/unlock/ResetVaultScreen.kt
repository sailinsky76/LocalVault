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

package cn.localvault.app.ui.unlock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.LocalClipboard
import cn.localvault.app.ui.LocalQuickUnlock
import cn.localvault.app.ui.LocalRepository
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.ExplainBlock
import cn.localvault.app.ui.components.ExplainDialog
import cn.localvault.app.ui.components.ExplainLink
import cn.localvault.app.ui.components.ExplainNote
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.HoldButton
import cn.localvault.app.ui.components.PlainField
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultIcon
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.components.explain
import cn.localvault.app.ui.settings.QuickUnlockRemnants
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 清空重来（`Route.RESET`）。**只挂在解锁那张图上。**
 *
 * ── 这一页和删除页长得像，但排版顺序是反过来的 ──
 *
 * 删除页：事实 → 会没掉什么 → 主密码 → 按钮。第一屏就把「你要删的是 37 条」
 * 摆在最前面，因为那一页的用户已经证明了身份，他缺的只是一个准确的账。
 *
 * 这一页第一屏是**坏消息**（没有找回通道、唯一的生路是备份文件），
 * 因为走到这儿的人多半是抱着一线希望点进来的（「说不定还有别的办法」）。
 * 让他在前三行就看清这不是找回入口，比让他读到最后一段才发现要好得多——
 * 后者会让他觉得刚才那一屏字是绕圈子。
 *
 * 会没掉什么排在后面，不是因为它不重要，是因为对一个**打不开这个库**的人来说，
 * 「库里的条目会没」这句话的分量本来就比删除页上轻：那些东西他现在也拿不到。
 *
 * ── v4：收的是长度，顺序一格没动 ──
 *
 * 六段解释各收成一句 + 一个链接（`components/Explain.kt` 那条规矩），
 * 原文一个字不删。**但这一页的两道门没有往上挪。**
 *
 * 别的页面做这件事是为了让主按钮浮上第一屏，这一页反过来：抄写和按住三秒
 * 本来就该够不着（决策(126)(127)(128)），一个能一眼看见并按下去的清空按钮
 * 是这一页最不该有的东西。收短的目的只有一个——让「先说清楚」那两句、
 * 那两个问句、和「清空之后会没什么」真正落在他会读的那一屏里，
 * 而不是被六段实现细节和修辞垫得越来越远。
 *
 * ── 这一页读不到任何库里的东西，一行都没有 ──
 *
 * 全文没有一次 `session.data`（决策(129)）。相位是 `Locked`，
 * 那个 `data` 本来就是 null——但更要紧的是**就算能读也不读**：
 * 这一页和解锁页是同一张图上的同一种可达性，捡到手机的人一样点得进来。
 * 一个对着没解开的保险库能报出条目数的应用，才是有问题的那一个。
 *
 * ── 没有最后那个确认弹窗 ──
 *
 * 对比删除页（那儿有一个 `danger = true` 的 `VaultDialog`）。
 * 这里刻意不加，见决策(128)：按住三秒本身就带着「随时松手即中止」的语义，
 * 它比弹窗上的一次点击更强。再叠一个弹窗只是把仪式凑够三样，
 * 而仪式凑够三样恰恰是决策(126) 那把尺子不允许的。
 */
@Composable
fun ResetVaultScreen(onBack: () -> Unit) {
    val repo = LocalRepository.current
    val session = LocalSession.current
    val quick = LocalQuickUnlock.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    /*
     * 控制器挂在页面里，不挂在图那一层。
     *
     * 和这张图上的 `UnlockController` 反着来，理由是那个要被两张页面共用
     * （主密码页和 PIN 页共享同一套退避），这个只有一个调用方。
     * 页面级 `remember` 的那条老风险——「导航走开就被丢掉」——在这儿不成立：
     * 执行期间返回被 `BackHandler` 挡着，而执行成功的下一刻整棵子树就没了。
     *
     * `QuickUnlockRemnants` 是从 `ui.settings` 借过来的（同
     * `ResetVaultController` 里那处跨包引用）：两页要清的残留是同一堆，
     * 为它再定义一个同形状的接口只会多出一个迟早会走样的副本。
     */
    val controller = remember(repo, session) {
        ResetVaultController(
            repo = repo,
            session = session,
            remnants = QuickUnlockRemnants(quick, clipboard),
            scope = scope,
        )
    }
    DisposableEffect(controller) { onDispose { controller.cancel() } }

    var typed by remember { mutableStateOf("") }
    val canArm = ResetVaultModel.canArm(typed, controller.busy)

    /*
     * 绑没绑过快捷解锁，只在进这一页时读一次。
     *
     * 这个值只影响「会跟着一起没」那张清单里有没有指纹 / PIN 那一条
     * （`ResetVaultModel.collateral`）。清空进行到一半时它会翻成 false，
     * 而那一刻让清单当着用户的面少掉一行，只会让他以为自己看错了。
     */
    val pinEnrolled = remember { quick.isPinEnrolled }
    val bioEnrolled = remember { quick.isBiometricEnrolled }
    val collateral = remember(pinEnrolled, bioEnrolled) {
        ResetVaultModel.collateral(pinEnrolled = pinEnrolled, biometricEnrolled = bioEnrolled)
    }

    // 清空进行中不许返回。同删除页：这中间退出去，用户既不知道清干净了没有，
    // 也没有任何页面会告诉他。
    BackHandler(enabled = controller.busy) { }

    VaultScreen(
        title = ResetVaultModel.TITLE,
        onBack = if (controller.busy) null else onBack,
        // 封条照常。这一页不覆盖 tone —— 退避转红是解锁页独有的，
        // 别的页面一律不许自己编封条的语气（见 DefaultSeal）。
        seal = { DefaultSeal() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            /* ── 一、这一页是干什么的，以及它帮不了什么 ── */

            ExplainNote(
                ResetVaultModel.LEAD_SHORT,
                style = VaultType.Body,
                color = VaultColors.Dim,
                detailTitle = ResetVaultModel.TITLE,
                detail = explain(ResetVaultModel.LEAD),
            )

            /* ── 二、先把坏消息说完 ── */

            /*
             * 这两段（没有找回通道、只有备份能拿回来）原来各占三行。
             * v4 收成一句两行的，两个结论一个不少，论据进弹窗——
             * 收的是长度，不是顺序：这一页第一屏仍然是坏消息，
             * 那正是它和删除页排版相反的全部理由（见文件头）。
             */
            Eyebrow("先说清楚")
            ExplainNote(
                ResetVaultModel.NO_RECOVERY_SHORT,
                style = VaultType.Sub,
                color = VaultColors.Dim,
                detailTitle = ResetVaultModel.NO_RECOVERY_TITLE,
                detail = explain(
                    ResetVaultModel.NO_RECOVERY,
                    ResetVaultModel.BACKUP_IS_THE_ONLY_WAY,
                ),
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            /* ── 三、我们答不上来、只能还给他的两个问题（决策(125)）── */

            VaultCard(Modifier.fillMaxWidth()) {
                Column {
                    ResetVaultModel.QUESTIONS.forEachIndexed { i, q ->
                        if (i > 0) HairLine()
                        Question(q)
                    }
                }
            }

            // 为什么这一页说不出条目数。主动交代，不是道歉（决策(129)）。
            ExplainNote(
                ResetVaultModel.NO_INVENTORY_SHORT,
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                detailTitle = "为什么这一页数不出条目",
                detail = explain(ResetVaultModel.NO_INVENTORY_NOTE),
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            /* ── 四、跟着一起没的 ── */

            Eyebrow("清空之后")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                collateral.forEach { Bullet(it) }
            }

            /* ── 五、不会跟着没的。这是全屏唯一的好消息，所以给它一个玉色的框 ── */

            GoodNews(
                ResetVaultModel.EXPORTS_NOTE_SHORT,
                detail = explain(ResetVaultModel.EXPORTS_NOTE),
            )

            // 覆写擦除这件事的实话（决策⑧）。和删除页读到的是同一个字符串（决策(131)），
            // 长短两版都是——那条规矩在 v4 之后要守两份字，不是一份。
            ExplainNote(
                ResetVaultModel.ERASURE_NOTE_SHORT,
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                detailTitle = cn.localvault.app.ui.settings.DeleteVaultModel.ERASURE_DETAIL_TITLE,
                detail = explain(ResetVaultModel.ERASURE_NOTE),
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            /* ── 六、门槛之一：抄写 ── */

            // 这一句用普通说明文字，不用 `Eyebrow`：分区小标题在这套设计里是
            // 大字距的全大写标签（「先说清楚」「清空之后」），
            // 而这一行是一句直接对着用户说的指令，语气不是标题。
            Text(
                ResetVaultModel.PHRASE_LABEL,
                style = VaultType.Body,
                color = VaultColors.Text,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            /*
             * 范文。**不要给它套 `SelectionContainer`**（决策(127)）——
             * Compose 的 `Text` 默认不可选中，这道门才成立；
             * 哪天为了「方便复制」在外面包一层，就等于把它改成
             * 「长按 → 复制 → 粘贴」，那连停顿都省了。
             */
            Text(
                ResetVaultModel.PHRASE,
                style = VaultType.MonoBody,
                color = VaultColors.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(VaultShape.TileSm)
                    .background(VaultColors.Void)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )

            PlainField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = "在这里抄一遍",
                mono = true,
                // Done 而不是 Go：这个键**不提交**。
                // 提交只有一条路——按住三秒（决策(128)）。
                //
                // 执行期间也不禁用这个框（`PlainField` 本来也没有 `enabled`，
                // 不为这一处去改它的签名）：那两步加起来只有几十毫秒，
                // 而且这期间 `canArm` 已经是 false，按钮是灰的，改字改不出任何后果。
                imeAction = ImeAction.Done,
            )

            /*
             * 抄错了不给红字。按钮是不是亮着就是全部回执（`ResetVaultModel.canArm`）——
             * 一句「抄错了」会把这道门变成一个可以反复试的谜题，而它本来就不是谜题。
             */
            ExplainNote(
                ResetVaultModel.PHRASE_HINT_SHORT,
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                detailTitle = "这一句是干什么的",
                detail = explain(ResetVaultModel.PHRASE_HINT),
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            /* ── 七、失败了说什么 ── */

            (controller.step as? ResetVaultController.Step.Failed)?.let { s ->
                Banner(
                    text = ResetVaultModel.failureMessage(s.reason),
                    tone = BannerTone.Danger,
                    actionText = "知道了",
                    onAction = { controller.dismissError() },
                )
            }

            /* ── 八、门槛之二：按住三秒 ── */

            HoldButton(
                idleText = ResetVaultModel.BUTTON_IDLE,
                holdLabel = ResetVaultModel::holdLabel,
                onComplete = { controller.submit() },
                enabled = canArm,
                holdMillis = ResetVaultModel.HOLD_MILLIS,
            )

            Text(
                ResetVaultModel.HOLD_HINT,
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            )

            ProgressNote(controller.step)

            /* ── 九、系统那条路。写出来，但放在最下面（同删除页的 BLOCKED_HINT）── */

            ExplainNote(
                ResetVaultModel.SYSTEM_PATH_SHORT,
                style = VaultType.Sub,
                color = VaultColors.Dimmer,
                detailTitle = ResetVaultModel.SYSTEM_PATH_TITLE,
                detail = explain(ResetVaultModel.SYSTEM_PATH_NOTE),
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ─────────────────────────── 小零件 ─────────────────────────── */

/**
 * 一个还给用户的问题。
 *
 * 用问号图标而不是项目符号：这两行和上面那几段的语气是不一样的——
 * 上面是我们知道的事，这两行是**只有他知道**的事（决策(125)）。
 */
@Composable
private fun Question(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "？",
            style = VaultType.MonoBody,
            color = VaultColors.Brass,
        )
        Text(text, style = VaultType.Sub, color = VaultColors.Text)
    }
}

/** 会跟着一起没的一条。红叉，同删除页那张清单。 */
@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        VaultIcon(
            Glyph.Close,
            tint = VaultColors.Rust,
            size = 13.dp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(text, style = VaultType.Sub, color = VaultColors.Dim)
    }
}

/**
 * 唯一的好消息。
 *
 * 用玉色而不是走 `Banner`：`Banner` 只有中性 / 注意 / 出事三种语气，
 * 而且注释里写明了「不做成功绿条」——那条规矩针对的是**操作回执**
 * （成功了不需要横幅，界面变化就是回执）。这一条不是回执，
 * 是一件在满屏坏消息里唯一站得住的事实，它需要被一眼看见。
 */
@Composable
private fun GoodNews(text: String, detail: List<ExplainBlock> = emptyList()) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VaultShape.Row)
            .background(VaultColors.JadeWash)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VaultIcon(
            Glyph.Check,
            tint = VaultColors.Jade,
            size = 16.dp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column {
            Text(text, style = VaultType.Sub, color = VaultColors.Jade)
            /*
             * 链接跟着框走用玉色，不用黄铜（v4）。
             *
             * 黄铜在这套设计里管的是「需要注意的地方」，而这个框整块的意思
             * 恰恰相反——它是这一页唯一一件好事。在一片玉色里插一处黄铜，
             * 读起来像是这条好消息附带一个警告。
             */
            if (detail.isNotEmpty()) {
                ExplainLink(
                    "哪些副本还在",
                    onClick = { open = true },
                    color = VaultColors.Jade,
                )
            }
        }
    }

    if (open) {
        ExplainDialog(
            title = "不会跟着没的东西",
            blocks = detail,
            onDismiss = { open = false },
        )
    }
}

/**
 * 进度说明。两句话对应两个阶段（`ResetVaultModel` 里那两句）。
 *
 * 这两句必须出现在屏幕上，哪怕只闪一下：用户此刻盯着的是一个正在删掉
 * 他全部密码的程序，一个没有任何字的转圈动画会让「它到底删到哪一步了」
 * 变成事后无法回答的问题。
 *
 * `Done` 不给文案。那一帧之后相位就翻回 `NoVault`、整棵子树换成欢迎页了，
 * 而且——跟一个刚丢掉全部密码的人说「清空成功」，那两个字是在庆祝他的损失。
 */
@Composable
private fun ProgressNote(step: ResetVaultController.Step) {
    val text = when (step) {
        is ResetVaultController.Step.Purging -> ResetVaultModel.STEP_PURGING
        is ResetVaultController.Step.Deleting -> ResetVaultModel.STEP_DELETING
        else -> null
    } ?: return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = VaultColors.Rust,
            strokeWidth = 1.6.dp,
        )
        Text(text, style = VaultType.Sub, color = VaultColors.Dim)
    }
}
