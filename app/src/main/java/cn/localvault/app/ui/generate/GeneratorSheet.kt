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

package cn.localvault.app.ui.generate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.ui.LocalClipboard
import cn.localvault.app.ui.LocalSession
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.Glyph
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.IconSlot
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType
import cn.localvault.app.ui.util.SecureClipboard

/**
 * 生成器的选项，活在「已解锁」那张图上。
 *
 * 挂在这里而不是页面里，是因为用户会来回开好几次生成器（新增一条、
 * 改一条、再新增一条），而每次都把长度从 32 调回默认的 20 会让人恼火。
 *
 * **但它刻意不落盘**，哪怕落进加密的库文件里也不：
 * 那意味着每拨一下长度就整库重写一次（决策⑤：单文件整库加密，保存是整体重写），
 * 为了记住一个滑块位置付出一次全库加密和一次 fsync，不划算。
 * 锁定时整棵子树连同它一起消失（决策⑪），下次解锁回到默认值——
 * 这个代价和 [cn.localvault.app.ui.nav.DraftHandoff] 认下的是同一个。
 */
class GeneratorHolder {
    var options by mutableStateOf(PasswordGen.Options())
}

val LocalGenerator = staticCompositionLocalOf<GeneratorHolder> {
    error("GeneratorHolder 未提供：只有已解锁那张图里才能读它")
}

/**
 * 生成器覆盖层。
 *
 * ── 它不是一个路由，也不是一个 Dialog ──
 *
 * **为什么不是路由**：生成出来的密码要交回调用它的那一页。
 * 页面之间回传值的「正规」通道是 `savedStateHandle`，而那是一个 Bundle，
 * 会被系统写进 `savedInstanceState`——等于把一个刚生成的密码明文落盘。
 * 这正是 [cn.localvault.app.ui.nav.DraftHandoff] 那一整篇注释在讲的洞，
 * 只不过那次漏出去的是搜索词，这次是密码本身。
 * 做成同一棵 composition 里的覆盖层，结果就是一个普通的 Kotlin 回调，
 * 全程不经过导航，也没有任何东西可以被序列化。
 *
 * 顺带：`Route.GENERATOR` 因此从路由表里去掉了。
 *
 * **为什么不是 Dialog**：Compose 的 `Dialog` 是一个独立的 Window，
 * Activity 上的 `FLAG_SECURE` 不会传下去（决策⑭）。
 * [cn.localvault.app.ui.components.VaultDialog] 的办法是自己声明 `SecureOn`，
 * 那对确认弹窗够用；但这一屏上明晃晃摆着一个密码明文，
 * 与其依赖「记得设那个 flag」，不如**根本不新开 window**。
 * 画在当前 window 里，它自动继承 Activity 的防截屏。
 *
 * @param onUse 传 null 时不画「用这个密码」，只留复制——设置页里的独立入口走这条。
 * @param replacesExisting 调用方的密码框里已经有内容。会在按钮下面明说一句，
 *        因为「用这个密码」这几个字本身看不出它会盖掉什么。
 */
@Composable
fun GeneratorSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onUse: ((String) -> Unit)? = null,
    useText: String = "用这个密码",
    replacesExisting: Boolean = false,
) {
    val holder = LocalGenerator.current
    val clip = LocalClipboard.current
    val session = LocalSession.current
    val state by session.state.collectAsState()
    val clipSeconds = (state as? VaultSession.State.Unlocked)?.data?.meta?.clipboardClearSeconds
        ?: SecureClipboard.DEFAULT_SECONDS

    val options = holder.options

    /**
     * 密码用 `remember(options, nonce)` 算，不用 `LaunchedEffect` 往一个
     * `mutableStateOf` 里灌。
     *
     * 两个具体好处：一是选项一变**立刻**就有新密码，不会先闪一帧空框
     * （`LaunchedEffect` 要等下一次组合之后才跑）；
     * 二是「重新生成」只是把 nonce 加一，生成这件事只有一个入口，
     * 不会出现「有的路径生成了、有的路径忘了生成」。
     *
     * 刻意**不用 `rememberSaveable`**：那会把密码明文写进 `savedInstanceState`。
     * 代价是转屏会换一个新密码——和搜索页转屏丢关键词（决策㊲）认下的是同一笔账。
     */
    var nonce by remember { mutableStateOf(0) }
    val password = remember(options, nonce) { PasswordGen.generate(options) }

    // 系统返回键关掉覆盖层，而不是退出当前页面。
    // 关掉它不会丢任何东西——那串密码还没被采用，重新打开会有新的一串。
    BackHandler { onDismiss() }

    Box(modifier.fillMaxSize()) {

        // 遮罩。点它等于关掉——取消手势只意味着「什么都别做」（决策⑮），
        // 而这里「什么都别做」确实是无害的：没有任何改动会因此丢失。
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(VaultShape.Sheet)
                .background(VaultColors.Slab)
                .border(1.dp, VaultColors.Line, VaultShape.Sheet)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "生成密码",
                    style = VaultType.H2,
                    color = VaultColors.Text,
                    modifier = Modifier.weight(1f),
                )
                IconSlot(Glyph.Close, contentDescription = "关闭", onClick = onDismiss)
            }

            HairLine()

            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                GeneratorPanel(
                    options = options,
                    onOptionsChange = { holder.options = it },
                    password = password,
                    onRegenerate = { nonce++ },
                )
            }

            HairLine()

            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (onUse != null) {
                    BrassButton(useText, onClick = { onUse(password) })
                    GhostButton(
                        "只复制",
                        onClick = { clip.copySensitive(CLIP_LABEL, password, clipSeconds) },
                        tint = VaultColors.Dim,
                    )
                } else {
                    BrassButton(
                        "复制",
                        onClick = { clip.copySensitive(CLIP_LABEL, password, clipSeconds) },
                    )
                }

                /**
                 * 「用这个密码」这几个字看不出它会盖掉什么，所以要说一句。
                 *
                 * 不做二次确认弹窗：编辑页本来就不自动保存（决策(59)），
                 * 真正的撤销落点是那个还没按下的保存按钮，
                 * 而返回时那道「放弃修改」拦截（决策(60)）会兜住误操作。
                 * 在一个可以随时撤销的动作前面加确认框，只会教会用户闭眼点确定。
                 */
                Text(
                    text = if (replacesExisting) {
                        "会替换密码框里的内容，保存前原来那个还在。"
                    } else {
                        // M3-6a 起用户能把自动清除关掉了，这句话得跟着变——
                        // 一个说「15 秒后自动清除」而其实永远不清的提示，
                        // 比不写这句话糟得多。
                        if (clipSeconds > 0) "复制的内容 $clipSeconds 秒后自动清除。"
                        else "自动清除已关，复制的内容要自己清。"
                    },
                    style = VaultType.MonoSmall,
                    color = VaultColors.Dimmer,
                )
            }
        }
    }
}

/**
 * 剪贴板标签只写「生成的密码」。
 *
 * 决策(51) 那条在这里同样成立：系统的剪贴板面板会把这个标签显示给
 * 任何能读剪贴板描述的应用看。这里连条目名都还不存在，
 * 但也不能顺手写成「LocalVault · 密码」——那等于告诉每一个后台应用
 * 「这台设备上装着一个密码管理器，而且用户刚复制了一个密码出来」。
 */
private const val CLIP_LABEL = "生成的密码"
