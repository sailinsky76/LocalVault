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

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultRepository
import cn.localvault.app.ui.settings.VaultRemnants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「忘了主密码，清空重来」这件事的执行者。
 *
 * ── 它和 `DeleteVaultController` 差在哪儿 ──
 *
 * 差的不是一个参数，是**有没有身份证明**这件事本身：
 *
 *   删除页：验主密码 → 清残留 → 删文件 → 相位翻回 NoVault
 *   这一页：            清残留 → 删文件 → 相位翻回 NoVault
 *
 * 少掉的那一步不是可选项，是这一页存在的全部理由——面对的人说不出主密码。
 * 所以两个控制器没有合并成一个带 `password: CharArray?` 的东西：
 * 那等于让「这次动作有没有人证明过身份」变成一个可空参数，
 * 而这恰恰是最不该用 null 来表达的区别。写错一次（传了 null 却走到删除页）
 * 就是把决策(119) 那道门无声地拆掉。两个类各自把话说死，编译器帮着看住。
 *
 * 顺带的好处是失败文案也分得开：删除页每条都写「保险库还在，数据一条没少」
 * 当安慰，这一页的用户要的就是删掉，同一句话在这儿是坏消息。
 *
 * ── 顺序照抄删除页，一个字不改（决策(120)）──
 *
 * 先清快捷解锁的残留，后删库文件。反过来的中途失败是**不可收拾**的：
 * 文件已经没了，而 prefs 里还躺着一份包着某个不存在的库的主密钥的包裹，
 * Keystore 里还留着两把钥匙——它们会在用户下一次建库时变成
 * 一堆解释不清的脏数据（`isAnyEnrolled` 为 true，解出来的钥匙却开不了新库）。
 * 现在这个顺序的中途失败是**可收拾**的：库还在，代价只是快捷解锁被关了，
 * 而这一页的用户本来就进不去，那点代价对他等于零。
 *
 * ── 全程一次都没有打开过保险库 ──
 *
 * 这一步不需要库主密钥，也拿不到（相位是 Locked）。它只是删文件。
 * 这条性质顺带解决了一个真实的处境：**库文件坏了、任何口令都打不开**的人
 * 走的也是这一页，而一个需要先解开库才能清空的实现，恰恰在那种时候用不了。
 *
 * ── 以「库还在不在」为准（决策(121)）──
 *
 * `VaultStorage.deleteAll()` 在任何一个文件（主文件、临时文件、上一版备份）
 * 删不掉时都返回 false，但决定成败的只有一件事：这台设备上还有没有这个库。
 * 一个残留的 `.tmp` 不该让整件事被报成失败，让用户对着一个其实已经空了的
 * 保险库再按一次三秒。所以删完之后再问一次 [VaultRepository.exists]，以它为准。
 */
class ResetVaultController(
    private val repo: VaultRepository,
    private val session: VaultSession,
    private val remnants: VaultRemnants,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {

    @Immutable
    sealed interface Step {
        data object Idle : Step

        /** 正在清快捷解锁的绑定和剪贴板 */
        data object Purging : Step

        /** 正在删文件 */
        data object Deleting : Step

        /**
         * 删完了。
         *
         * 和删除页那个 `Done` 一样，这是个**几乎看不见**的终态：
         * `session.onVaultDeleted()` 一调，相位就从 `Locked` 翻到 `NoVault`，
         * 整棵解锁子树连同这一页一起被换成欢迎页（决策⑪）。
         * 留着它只为一件事——万一将来相位切换被改成带动画的，
         * 那一帧上按钮不该还是可按的。
         *
         * **不要在这个状态上加成功提示。** 欢迎页本身就是回执（决策(122)）。
         * 对这一页来说还多一层：跟一个刚丢掉全部密码的人说「清空成功」，
         * 那个「成功」两个字是在庆祝他的损失。
         */
        data object Done : Step

        data class Failed(val reason: ResetVaultModel.Failure) : Step
    }

    var step by mutableStateOf<Step>(Step.Idle)
        private set

    val busy: Boolean
        get() = step is Step.Purging || step is Step.Deleting

    val done: Boolean get() = step is Step.Done

    private var job: Job? = null

    /**
     * 清空。**没有参数**——这一页没有任何东西要核对，也没有任何东西要清零。
     *
     * 调用方（页面）负责把两道门（抄写 + 按住三秒）走完才调到这里；
     * 门在界面上，不在这里，理由和别的控制器一样：
     * 判定要能纯 JVM 测（见 `ResetVaultModel.canArm`），执行要能不带界面地跑。
     */
    fun submit() {
        if (busy || done) return

        job = scope.launch {
            /*
             * 残留清过没有。**这个标记只为了让失败文案不撒谎。**
             * `Failure.FilesRemain` 那段话里写着「指纹和 PIN 已经在这一步之前清掉了」，
             * 而清残留本身就抛异常的那一次并没有清干净，同一句话在两种情况下一真一假。
             */
            var purged = false
            try {
                /*
                 * 两个 runCatching：它们失败不该让整件事停下来。
                 * 用户要的是「把这个库从这台手机上弄掉」，
                 * 为一次 Keystore 抽风而保住一个他已经打不开的库，
                 * 只会让他再按一次、再抽风一次。清不掉的那部分是脏数据，
                 * 不是安全问题——那两把钥匙包的是一个马上就不存在的库。
                 */
                step = Step.Purging
                withContext(worker) {
                    runCatching { remnants.purgeQuickUnlock() }
                    runCatching { remnants.clearClipboard() }
                }
                purged = true

                step = Step.Deleting
                val gone = withContext(worker) {
                    repo.deleteEverything()
                    // 以「还在不在」为准，不以返回值为准。见类注释。
                    !repo.exists()
                }
                if (!gone) {
                    step = Step.Failed(ResetVaultModel.Failure.FilesRemain)
                    return@launch
                }

                /*
                 * 相位翻回 NoVault。必须是最后一步：它一执行，
                 * 整棵解锁子树（包括这一页和这个控制器）就会被换掉，
                 * 放在前面的话，后面那些代码是在一棵正在被销毁的树上跑的。
                 *
                 * 这里调的是 `onVaultDeleted()` 而不是 `lock()`：后者的终点是
                 * `Locked`，那会把用户送回一张要他为一个已经不存在的库
                 * 输入主密码的解锁页——正是他刚刚花三秒钟摆脱的那一页。
                 */
                session.onVaultDeleted()
                step = Step.Done
            } catch (c: CancellationException) {
                // 取消不是失败。同建库 / 改密码 / 删库控制器。
                throw c
            } catch (t: Throwable) {
                step = Step.Failed(classify(t, purged))
            }
        }
    }

    fun dismissError() { if (step is Step.Failed) step = Step.Idle }

    fun cancel() { job?.cancel(); job = null }

    /**
     * 异常归类。
     *
     * [purged] 决定用哪一条文案：`FilesRemain` 那段话里有一句
     * 「指纹和 PIN 已经在这一步之前清掉了」，清残留之前（或之中）抛出来的异常
     * 配上这句话就是假的，那种情况一律走 `Unknown`——
     * 它说的「保险库还在这台设备上」在两种情况下都成立。
     */
    private fun classify(t: Throwable, purged: Boolean): ResetVaultModel.Failure = when {
        !purged -> ResetVaultModel.Failure.Unknown
        t is java.io.IOException -> ResetVaultModel.Failure.FilesRemain
        else -> ResetVaultModel.Failure.Unknown
    }
}
