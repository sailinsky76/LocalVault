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

package cn.localvault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType

/**
 * 「一句话 + 详细说明」。**这个工程里所有长篇解释都走这里。**
 *
 * ── 它解决的是什么问题 ──
 *
 * 这个 App 的文案量本来就大，而且大得有理由：M4 里那些「刻意不填」的决定，
 * 在屏幕上全都长成同一个样子（什么都没弹出来），不解释等于坏了。
 * 但把每一句解释都平铺在页面上，代价是**真正要操作的东西被挤下去了**：
 *
 *   - 自动填充设置页：一个开关，配着大半屏说明，开关反而不像重点；
 *   - 从备份恢复页：密码输入框和「恢复」按钮被四段说明顶到了屏幕外，
 *     一个刚换机、手里攥着最后一份备份的人，进来第一眼看不到该填什么。
 *
 * 所以规矩定成这样：
 *
 *   1. 和按钮、输入框平铺在一起的文字，**最多两三行**，只说用户此刻要做的判断；
 *   2. 剩下的话一个字都不删，搬进 [ExplainDialog]，用 [ExplainLink] 挂上去；
 *   3. 整块的长解释（症状清单、边界条件）连一句都不平铺，
 *      直接做成一行可点的 [ExplainRow]。
 *
 * 信息一条没少，屏幕上少了七八段。
 *
 * ── 为什么弹窗而不是折叠展开 ──
 *
 * 折叠（Accordion）展开之后仍然长在这一列里，会把下面的按钮**再一次**推走，
 * 而且推走的时机是用户刚点了一下、注意力正在别处的那一刻。
 * 弹窗是独立一层：读完关掉，底下那一屏原封不动。
 *
 * ── 安全 ──
 *
 * 同 [VaultDialog]：Compose 的 `Dialog` 是**独立的 Window**，
 * MainActivity 上那个 `FLAG_SECURE` 不会自动继承。这里的说明目前都是静态文案，
 * 但「详细说明里带出一条账号」是迟早会发生的事，所以 [SecureFlagPolicy.SecureOn]
 * 在这里也写死，不留 `Inherit`。
 */

/* ─────────────────────────── 内容模型 ─────────────────────────── */

/**
 * 详细说明里的一段。
 *
 * **纯 Kotlin，不带 Compose**——各个页面的 Model 层（`AutofillSettingsModel`、
 * `RestoreModel` 那一批）本来就有「一行 android.* 都不许有」的规矩，
 * 文案该继续住在那儿，由页面在这里包一层壳。所以这个类型故意做得很薄。
 */
sealed interface ExplainBlock {

    /** 一段话。 */
    data class Para(val text: String) : ExplainBlock

    /** 一组并列的短句，前面带黄铜色圆点。 */
    data class Bullets(val items: List<String>) : ExplainBlock

    /**
     * 小标题 + 正文。
     *
     * 给「症状 → 原因」这种成对的内容用（`AutofillSettingsModel.Reason`）：
     * 用户是带着症状来的，[heading] 是给他扫的，扫到了才会读 [body]。
     */
    data class Section(val heading: String, val body: String) : ExplainBlock
}

/** `explain("第一段", "第二段")` —— 最常见的那种，省得每次写一遍 `map { Para(it) }`。 */
fun explain(vararg paragraphs: String): List<ExplainBlock> =
    paragraphs.map { ExplainBlock.Para(it) }

/* ─────────────────────────── 链接 ─────────────────────────── */

/**
 * 「详细说明 ›」。看起来像链接，不像按钮——它不触发任何动作，只是打开一段字。
 *
 * 用黄铜色而不是次要灰：这一个字要是也灰着，它就和它旁边那句被截短的说明
 * 融成一片，用户根本不会发现还有下文可读，那这一整套精简就等于**删信息**。
 * 黄铜在这套设计里管的是「需要注意的地方」，一处能读到剩下半页话的入口正好是。
 */
@Composable
fun ExplainLink(
    text: String = "详细说明",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = VaultColors.Brass,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(VaultShape.TileSm)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            // 行内链接不做成 48dp 的方块（那会在两段文字之间豁开一道口子），
            // 但也不能只有一行字那么高——40dp 是还点得准的下限。
            // 左边不留内边距：这行字必须和它上面那段说明**左对齐**，
            // 差 6dp 在别处看不出来，在一段字正下方一眼就是歪的。
            .heightIn(min = 40.dp)
            .padding(end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        VaultIcon(Glyph.Info, tint = color, size = 15.dp, strokeWidth = 1.7f)
        Text(text, style = VaultType.Sub, color = color)
    }
}

/* ─────────────────────────── 弹窗 ─────────────────────────── */

/**
 * 详细说明弹窗。
 *
 * 只有一个「知道了」，**没有第二个按钮**：这里面一个字都不会改变任何状态，
 * 读完唯一能做的事就是关掉它。给一段说明配一对「确定 / 取消」，
 * 会让用户以为刚才那一下是个选择（同 [VaultDialog] 里那段「取消手势永远只能意味着什么都别做」）。
 *
 * 内容区自己滚。这一点是这个组件存在的全部意义——长文有了一个
 * **不会把页面撑开**的去处，页面那一列从此可以只留操作。
 */
@Composable
fun ExplainDialog(
    title: String,
    blocks: List<ExplainBlock>,
    onDismiss: () -> Unit,
    closeText: String = "知道了",
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 见文件头：弹窗是独立 window，必须自己声明防截屏
            securePolicy = SecureFlagPolicy.SecureOn,
            // 关掉平台默认宽度，改由下面的 widthIn + padding 控制：
            // 默认那个宽度容不下中文长段落，一页说明会被压成又窄又高的一条。
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                // 竖向留白既是呼吸感，也是**高度上限**：内容区拿到的是
                // 减掉这 44dp 之后的剩余空间，再长的说明也只会在里面滚，
                // 不会顶到状态栏或手势条。
                .padding(horizontal = 18.dp, vertical = 44.dp)
                .clip(VaultShape.Card)
                .background(VaultColors.Slab)
                .border(1.dp, VaultColors.Line, VaultShape.Card),
        ) {
            /* 标题栏：图标 + 标题 + 叉。叉和底部那个按钮是同一件事，
               留两个是因为长说明滚到中间时，回到底部按钮要拖一段。 */
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                VaultIcon(Glyph.Info, tint = VaultColors.Brass, size = 19.dp)
                Text(
                    title,
                    style = VaultType.H2,
                    color = VaultColors.Text,
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                )
                IconSlot(Glyph.Close, contentDescription = "关闭", onClick = onDismiss)
            }

            HairLine()

            Column(
                modifier = Modifier
                    // fill = false：说明短的时候弹窗就该矮，不要撑成满屏一大片空白。
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                blocks.forEach { ExplainBlockView(it) }
            }

            HairLine()

            Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                GhostButton(closeText, onClick = onDismiss, tint = VaultColors.Dim)
            }
        }
    }
}

@Composable
private fun ExplainBlockView(block: ExplainBlock) {
    when (block) {
        is ExplainBlock.Para -> Text(
            block.text,
            style = VaultType.Body,
            color = VaultColors.Dim,
        )

        is ExplainBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            block.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("·", style = VaultType.Body, color = VaultColors.Brass)
                    Text(item, style = VaultType.Sub, color = VaultColors.Text)
                }
            }
        }

        is ExplainBlock.Section -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(block.heading, style = VaultType.RowName, color = VaultColors.Text)
            Text(block.body, style = VaultType.Sub, color = VaultColors.Dim)
        }
    }
}

/* ─────────────────────────── 两个用法 ─────────────────────────── */

/**
 * **用法一：一句短说明 + 一个「详细说明」链接。**
 *
 * 页面上绝大多数解释性文字都该换成这个。[text] 写成两三行以内、
 * 只交代用户此刻要做的判断；原来那几段一个字不删地放进 [detail]。
 *
 * [detail] 为空时它退化成一段普通说明文字（连链接都不画），
 * 所以短文案不必为了用这个组件而硬凑出一段详情。
 */
@Composable
fun ExplainNote(
    text: String,
    modifier: Modifier = Modifier,
    detail: List<ExplainBlock> = emptyList(),
    detailTitle: String = "详细说明",
    linkText: String = "详细说明",
    style: TextStyle = VaultType.Sub,
    color: Color = VaultColors.Dimmer,
    maxLines: Int = 3,
) {
    var open by remember { mutableStateOf(false) }

    Column(modifier) {
        Text(
            text,
            style = style,
            color = color,
            // 没有详情可读的时候不许截断——那就是纯粹地把信息弄丢了。
            // 有详情时才收到 maxLines：被截掉的那部分在弹窗里一字不差。
            maxLines = if (detail.isEmpty()) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.isNotEmpty()) {
            ExplainLink(linkText, onClick = { open = true })
        }
    }

    if (open) {
        ExplainDialog(title = detailTitle, blocks = detail, onDismiss = { open = false })
    }
}

/**
 * **用法二：整块解释做成一行可点的卡片。**
 *
 * 用在那种「一句都不必平铺」的长内容上——自动填充那份症状清单、
 * 恢复页那四条「之后会怎样」。它们的共同点是：不看也能把这一页走完，
 * 但撞上问题的人非看不可。摆在页面上是七段字，收成一行就是一个书签。
 *
 * [subtitle] 写这份说明**里面有什么**（「七种常见情况」），
 * 不写「点击查看详情」——后者是废话，用户已经看见那个箭头了。
 */
@Composable
fun ExplainRow(
    title: String,
    detail: List<ExplainBlock>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    detailTitle: String = title,
) {
    var open by remember { mutableStateOf(false) }

    VaultCard(modifier.fillMaxWidth()) {
        SettingRow(
            title = title,
            subtitle = subtitle,
            showChevron = true,
            onClick = { open = true },
        )
    }

    if (open) {
        ExplainDialog(title = detailTitle, blocks = detail, onDismiss = { open = false })
    }
}

/**
 * **用法三：横幅版。**
 *
 * 给那种「这么做有代价」的话用——改主密码页那句「旧备份仍然只认旧主密码」、
 * 导出页那句网盘警告。它们和普通说明文字的区别是：**收进弹窗等于把它藏了**，
 * 而最该看见这句话的恰恰是那批不会去点链接的人（决策见 `BackupScreen` 里
 * 网盘那一条为什么留在外面）。
 *
 * 所以这里不做「短句 + 链接」那种形态，而是横幅照留、只把它收短，
 * 完整那几段挂在横幅右边那个动作位上。
 *
 * 复用 [Banner] 的 `actionText` 槽，不另画一套：那个槽在这个工程里
 * 一直是「这条横幅上唯一能点的东西」，语义正好。默认字是「详情」而不是
 * 「详细说明」——横幅是一行高的东西，右边那四个字会把正文挤成两行。
 */
@Composable
fun ExplainBanner(
    text: String,
    detail: List<ExplainBlock>,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Warn,
    detailTitle: String = "详细说明",
    linkText: String = "详情",
) {
    var open by remember { mutableStateOf(false) }

    Banner(
        text = text,
        modifier = modifier,
        tone = tone,
        // 没有详情可读时退化成一条普通横幅（同 ExplainNote），
        // 免得调用方为了用这个组件去硬凑一段详情。
        actionText = if (detail.isEmpty()) null else linkText,
        onAction = if (detail.isEmpty()) null else ({ open = true }),
    )

    if (open) {
        ExplainDialog(title = detailTitle, blocks = detail, onDismiss = { open = false })
    }
}
