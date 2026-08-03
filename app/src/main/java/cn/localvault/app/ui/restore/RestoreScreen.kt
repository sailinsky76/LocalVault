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

package cn.localvault.app.ui.restore

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.LocalRepository
import cn.localvault.app.ui.components.Banner
import cn.localvault.app.ui.components.BannerTone
import cn.localvault.app.ui.components.BrassButton
import cn.localvault.app.ui.components.DefaultSeal
import cn.localvault.app.ui.components.ExplainBlock
import cn.localvault.app.ui.components.ExplainNote
import cn.localvault.app.ui.components.ExplainRow
import cn.localvault.app.ui.components.Eyebrow
import cn.localvault.app.ui.components.GhostButton
import cn.localvault.app.ui.components.HairLine
import cn.localvault.app.ui.components.LabeledField
import cn.localvault.app.ui.components.SecurePasswordField
import cn.localvault.app.ui.components.SettingRow
import cn.localvault.app.ui.components.VaultCard
import cn.localvault.app.ui.components.VaultScreen
import cn.localvault.app.ui.components.explain
import cn.localvault.app.ui.components.rememberSecureTextState
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultType

/**
 * 通配 MIME —— **刻意不按类型过滤**（决策㉒的界面这一侧）。
 *
 * （下面那个字面量写成通配的「任意类型」形态，它不能出现在这段注释里：
 * 那三个字符里带着一个块注释的结束标记，写进来这段说明就在半路上断了。）
 *
 * 导出时用的是 `application/octet-stream`，某些 ROM 会据此把文件存成
 * `.lvault.bin`；用户自己重命名过的、从网盘同步回来被改了类型的，更是常事。
 * 只要在这里填一个具体的 MIME，那些文件在选择器里就会**变灰、点不动**——
 * 一个把自己备份改了个名字的人会得到「我的备份不见了」这个结论，
 * 而文件其实好端端地躺在那儿。
 *
 * 代价是选择器里什么都点得到，于是「点错了」成为最常见的一次失误——
 * 那正是 `RestoreModel.Probe.NotVaultFile` 单独分一类、单独给一句话的原因。
 * 认文件靠的是文件头里的 `LVAULT` 标识，从头到尾不看扩展名。
 */
private val OPEN_ANY = arrayOf("*/*")

/**
 * 从备份恢复（`Route.RESTORE`，只注册在引导图上）。
 *
 * ── 这一页兑现的是什么 ──
 *
 * 欢迎页上「整个库就是一个文件，换机时拷过去即可」那句承诺，和清空重来那一页
 * 指着的那条出路（「你导出到别处的备份还在」），最后都落在这一页上。
 * 在它接通之前，那两句话都是真的、但没有地方去（决策(132) 因此拦住了内测包）。
 *
 * ── 排版顺序（v3 修订） ──
 *
 * 一句话 → 选文件 → 文件头里的事实 → 主密码 → 恢复 → 之后会怎样。
 *
 * 先选文件后输密码，这一条没变，而且不是随手排的：**主密码只对某一份具体的文件有意义**
 * （`RestoreModel.PASSWORD_NOTE`：认的是导出那一刻那个）。
 * 反过来先让人输密码，等于请他先凭空回忆一个口令，再去找那个决定该回忆哪一个的文件。
 *
 * 变的是解释性文字的位置。初版在密码框之前还夹着两段说明和四条「恢复之后会怎样」，
 * 于是在常见机型上，**密码框和「恢复到这台设备」按钮都落在第一屏之外**——
 * 一个刚换机、手上这份文件是最后一根绳子的人，进来第一眼看到的是一屏要读的字。
 * 现在那几段按 `components/Explain.kt` 那条规矩处理：
 * 平铺的只留两三行，完整的一字不删收进「详细说明」弹窗，
 * 「恢复之后会怎样」整块收成一行可点的、摆在按钮下面。
 *
 * v4 又拿掉了一格：文件认出来之后，那个「换一个文件」按钮不再画。
 * 它夹在事实卡和密码框中间，而在那一档它是这一页**唯一一个不通向目的地**的按钮——
 * 一个刚核对完文件名、正要输密码的人不会用它，它却实实在在地把
 * 「恢复到这台设备」又往下推了一格。想换一份的人走返回键，
 * 从上一页再进来一次即可（`DisposableEffect` 保证那是全新的一轮）。
 * 没认出来的那一档照旧留着——理由写在按钮那儿。
 *
 * 「之后会怎样」挪到按钮**下面**是有讲究的：它四条里三条是好消息，
 * 唯一一件用户不知道的（指纹和 PIN 不跟过来）已经写在那一行的副标题上，
 * 一眼就能扫到；而它整体不是一个需要在按下去之前读完的东西——
 * 恢复不覆盖、不改动源文件，这一步没有不可逆的代价。
 *
 * ── 这一页没有的东西 ──
 *
 * 没有退避倒计时。退避守的是这台设备上那个库的门，而这一页上还没有库
 * （`RestoreModel.RETRY_NOTE` 把这件事明说了）——挡在这儿只会挡住一个
 * 正拿着自己的备份、正在回忆旧口令的人。真正的限速是 KDF 本身。
 *
 * 也没有「恢复成功」页。成功那一刻 `session.adopt` 把相位从 `NoVault` 翻到
 * `Unlocked`，整棵引导子树连同这一页一起被换成保险库列表（决策⑪）。
 * 那一屏就是回执，而且是比任何一句「恢复成功」都硬的回执——他的条目都在上面。
 */
@Composable
fun RestoreScreen(
    controller: RestoreController,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = LocalRepository.current
    val password = rememberSecureTextState()

    /**
     * 这台设备上是不是已经有库了。
     *
     * `remember` 不带 key：这一页只在 `NoVault` 相位可达，答案在进来的那一刻
     * 就是 false，而它一旦变成 true（恢复成功那一瞬间），整棵子树已经在被换掉的路上了。
     * 做成每帧都去 stat 一次文件，只会在成功的最后一帧上闪出一句
     * 「这台设备上已经有一个保险库了」。
     *
     * 那为什么还留着这一道？因为它和控制器里 `repo.exists()` 那一道是**两道**：
     * 这一道是给用户看的解释，那一道守的是真正的写盘时刻（决策(135)）。
     */
    val vaultExists = remember { repo.exists() }

    /**
     * 离开这一页就把那份文件从内存里丢掉。
     *
     * 控制器挂在**图**那一层（跨几秒的异步动作不能挂在页面上，同建库控制器），
     * 于是它会活过一次 `popBackStack`。而 `pending` 里装的是一整个加密库，
     * 没有任何理由在用户已经退回欢迎页之后继续留着。
     *
     * 成功那条路上控制器自己已经清过一次了，这里再清一次是无害的
     * （`submit` 早在协程开头就把字节数组取到了局部变量里）。
     */
    DisposableEffect(controller) { onDispose { controller.clearFile() } }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        /*
         * 这里**不需要** `session.beginSystemInterlude()`（对比导出页）。
         * 可信中断防的是「拉起系统界面时 Activity onStop，自动锁定倒计时开始跑」，
         * 而这一页处在 `NoVault` 相位：还没有库，也就没有自动锁定这回事
         * （`beginSystemInterlude` 里那句 `if (isUnlocked)` 在这儿本来就不成立）。
         * 照着导出页抄一行过来的话，它是一行永远不生效的代码。
         */
        if (uri != null) controller.pick(SafImportSource(ctx.contentResolver, uri))
    }

    val probe = controller.probe
    val recognized = probe as? RestoreModel.Probe.Recognized

    val canSubmit = RestoreModel.canSubmit(
        probe = probe,
        hasPassword = password.length > 0,
        busy = controller.busy,
        vaultExists = vaultExists,
    )
    val blockReason = RestoreModel.blockReason(
        probe = probe,
        hasPassword = password.length > 0,
        busy = controller.busy,
        vaultExists = vaultExists,
    )

    fun submit() {
        if (canSubmit) controller.submit(password.copyChars())
    }

    // 恢复进行中不许退出。中途走掉的人既不知道装成了没有，也没有任何页面会告诉他——
    // 而磁盘上那一刻可能已经躺着一个完好的库了（落盘先于会话接管，见控制器）。
    BackHandler(enabled = controller.busy) { }

    VaultScreen(
        title = "从备份恢复",
        onBack = if (controller.busy) null else onBack,
        seal = { DefaultSeal() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            ExplainNote(
                RestoreModel.INTRO_SHORT,
                style = VaultType.Body,
                color = VaultColors.Dim,
                detailTitle = RestoreModel.INTRO_DETAIL_TITLE,
                detail = explain(*RestoreModel.INTRO_DETAIL.toTypedArray()),
            )

            /* ── 一、选文件 ── */

            Eyebrow("备份文件")

            when {
                controller.step is RestoreController.Step.Reading ->
                    Progress(RestoreModel.STEP_READING)

                recognized != null -> RecognizedCard(recognized)

                probe != null -> RejectedCard(probe)

                // 「没有存储权限、只拿得到你挑中的那一个」挪进了页顶那个详细说明里：
                // 它是这一页的性质，不是「还没选文件」这个状态的说明，
                // 摆在这儿的唯一后果是把「选择备份文件」那个按钮往下推三行。
                else -> Text(
                    RestoreModel.NO_FILE_SHORT,
                    style = VaultType.Sub,
                    color = VaultColors.Dimmer,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }

            /*
             * 选文件按钮**只在还没有一份能用的文件时出现**（v4）。
             *
             * 认出来之后它就没了：那一刻这一页的动作变成「输密码 → 恢复」，
             * 而它顶在密码框上面占的那一格，正好是「恢复到这台设备」
             * 掉出第一屏的最后一根稻草。想换一份的人按左上角返回、
             * 从上一页再进来一次就是全新的一轮（离开这一页时那个
             * `DisposableEffect` 已经把上一份从内存里丢掉了）——
             * 多两下，换的是主按钮一眼可见。
             *
             * **没认出来时它必须在。** 那是这条路上最常见的失误
             * （选择器不按类型过滤，见 OPEN_ANY），而那个状态下屏幕上
             * 除了一条红字什么都没有，「再选一次」是唯一的下一步；
             * 这时候把人赶回上一页，是把一次点错变成一次绕路。
             * 那一档也没有密码框和它抢空间，本来就挤不着谁。
             */
            if (!controller.busy && recognized == null) {
                GhostButton(
                    text = if (probe == null) "选择备份文件" else "换一个文件",
                    onClick = {
                        // 换文件时先把上一份从内存里丢掉，再拉选择器。
                        // 用户在选择器里按返回的话，这一页就回到「还没选文件」，
                        // 而不是留着一份他刚刚明确表示要换掉的文件。
                        controller.clearFile()
                        picker.launch(OPEN_ANY)
                    },
                )
            }

            /* ── 二、主密码 ── */

            if (recognized != null) {
                LabeledField("这份备份的主密码") {
                    SecurePasswordField(
                        state = password,
                        placeholder = "导出那一刻的主密码",
                        // 不自动聚焦、不自动弹键盘：上面那张事实卡是这一页最需要被
                        // 核对一眼的东西（尤其是文件名——点错文件是这条路上最常见的失误），
                        // 一屏键盘会把它顶出去。同删除页（决策(64)）。
                        autoFocus = false,
                        imeAction = ImeAction.Done,
                        onImeAction = { submit() },
                        isError = controller.step.let {
                            it is RestoreController.Step.Failed &&
                                it.kind == RestoreModel.Failure.WrongPassword
                        },
                    )
                }
                ExplainNote(
                    RestoreModel.PASSWORD_HINT_SHORT,
                    color = VaultColors.Dim,
                    detailTitle = "关于这份备份的主密码",
                    detail = explain(RestoreModel.PASSWORD_NOTE, RestoreModel.RETRY_NOTE),
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }

            /* ── 三、失败 ── */

            (controller.step as? RestoreController.Step.Failed)?.let { failed ->
                Banner(
                    text = RestoreModel.failureMessage(failed.kind),
                    tone = BannerTone.Danger,
                    actionText = "知道了",
                    onAction = { controller.dismissError() },
                )
            }

            /* ── 四、恢复 ── */

            BrassButton(
                text = "恢复到这台设备",
                onClick = { submit() },
                enabled = canSubmit,
                busy = controller.busy,
            )

            // 灰按钮必须配一句解释（决策(61)）。忙的时候不配——那一刻按钮上转着圈，
            // 下面还有一句正在做什么，再加一句「不能按」是废话。
            if (blockReason != null && !controller.busy) {
                Text(
                    blockReason,
                    style = VaultType.Sub,
                    color = VaultColors.Dimmer,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }

            ProgressNote(controller.step)

            /* ── 五、恢复之后会怎样 ── */

            /*
             * 收成一行，摆在按钮下面。理由见文件头那段：
             * 四条里唯一一件用户不知道的（指纹和 PIN 不跟过来）已经写在副标题上，
             * 剩下三条是好消息，都不需要在按下去之前读完——
             * 这一步不覆盖任何东西，也不改动那份文件，没有不可逆的代价。
             *
             * 选文件之前不画：那时候他还没有一份「之后」可言。
             */
            if (recognized != null && !controller.busy) {
                ExplainRow(
                    title = RestoreModel.AFTER_TITLE,
                    subtitle = RestoreModel.AFTER_SUMMARY,
                    detail = listOf(ExplainBlock.Bullets(RestoreModel.WHAT_HAPPENS)),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ─────────────────────────── 小零件 ─────────────────────────── */

/**
 * 认出来了：把文件头里读得出来的四行摆出来，然后主动交代为什么没有第五行。
 *
 * 那句交代（[RestoreModel.WHY_NO_COUNT]）不是客套。用户在删除页上见过一整屏
 * 带条目数的事实清单，这里突然只剩四行，不解释会像是没做完；
 * 而它真正的意思是「不输主密码谁也数不出来」——这恰恰是这个产品最该被看见的性质。
 *
 * v3：平铺的那一句收成一行（[RestoreModel.WHY_NO_COUNT_SHORT]），
 * 完整那段在链接后面。这一段原来占三行，而它下面紧接着的就是密码框。
 */
@Composable
private fun RecognizedCard(p: RestoreModel.Probe.Recognized) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VaultCard(Modifier.fillMaxWidth()) {
            Column {
                RestoreModel.facts(p).forEachIndexed { i, f ->
                    if (i > 0) HairLine()
                    SettingRow(title = f.label, value = f.value, valueMono = true)
                }
            }
        }
        ExplainNote(
            RestoreModel.WHY_NO_COUNT_SHORT,
            detailTitle = "为什么这里数不出条目数",
            detail = explain(RestoreModel.WHY_NO_COUNT),
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/**
 * 没认出来。三种坏法各给一句话、各给一个不同的下一步——
 * 这正是 `RestoreModel.probe` 把它们分成三类的全部理由。
 *
 * 文案直接借用那八条里对应的三条，**不另写一套**：同一件事在这一页会出现两次
 * （选完文件时、以及万一绕过前一道拦截在提交时），两次说的必须是同一句话。
 * 借用也意味着它们都带着那句「你手上那份文件没有被改动」——
 * 一个刚被告知「这文件不对」的人，最先怕的就是这个。
 */
@Composable
private fun RejectedCard(p: RestoreModel.Probe) {
    val name = when (p) {
        is RestoreModel.Probe.NotVaultFile -> p.fileName
        is RestoreModel.Probe.TooNew -> p.fileName
        is RestoreModel.Probe.Damaged -> p.fileName
        is RestoreModel.Probe.Recognized -> p.fileName
    }
    val message = when (p) {
        is RestoreModel.Probe.NotVaultFile ->
            RestoreModel.failureMessage(RestoreModel.Failure.NotVaultFile)
        is RestoreModel.Probe.TooNew ->
            RestoreModel.failureMessage(RestoreModel.Failure.TooNew)
        // 文件头坏了，对用户来说和「密文解不开」是同一件事、同一个下一步
        // （换一份备份），所以共用 Corrupted 那一条。控制器的 classify 也是这么归的。
        is RestoreModel.Probe.Damaged ->
            RestoreModel.failureMessage(RestoreModel.Failure.Corrupted)
        is RestoreModel.Probe.Recognized -> return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 文件名单独一行：这条路上最常见的失误就是在选择器里点错了，
        // 而把名字摆出来，用户经常自己就看出来点的是哪个了。
        Text(
            name,
            style = VaultType.MonoSmall,
            color = VaultColors.Dim,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Banner(text = message, tone = BannerTone.Danger)
    }
}

/**
 * 三句进度。
 *
 * 分三句而不是一句「正在恢复…」，因为三次等待的原因完全不同，
 * 而中间那一次（核对主密码）要跑一遍 Argon2id，低配机上一两秒——
 * 不吭声的话那一两秒会被读成卡死。这一页尤其不能让人以为卡死：
 * 用户此刻多半刚换了机或刚清空过，手上这份文件是他最后一根绳子。
 */
@Composable
private fun ProgressNote(step: RestoreController.Step) {
    val text = when (step) {
        RestoreController.Step.Opening -> RestoreModel.STEP_OPENING
        RestoreController.Step.Installing -> RestoreModel.STEP_INSTALLING
        // Reading 那一句画在选文件那一块里（它说的是那一块的事），这里不重复。
        else -> null
    } ?: return
    Progress(text)
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
