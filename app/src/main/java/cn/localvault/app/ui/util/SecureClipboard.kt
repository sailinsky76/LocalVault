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

package cn.localvault.app.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 会自己清空的剪贴板。
 *
 * 密码管理器把密码放进剪贴板的那一刻，就把它交给了整个系统：
 * 任何前台应用、任何输入法都能读走。所以这里做三件事：
 *
 *   ① 打上 `EXTRA_IS_SENSITIVE` 标记 —— Android 13 起系统不再弹出
 *      带明文预览的复制气泡，输入法剪贴板历史也会跳过它；
 *   ② 倒计时到点自动清空，默认 15 秒（可在设置里改，见 VaultMeta）；
 *   ③ 清空前先确认剪贴板里躺着的**还是我们放进去的那份**，
 *      否则就什么也不做 —— 绝不能因为超时而抹掉用户自己复制的东西。
 *
 * 第 ③ 点靠往 ClipDescription 的 extras 里塞一个一次性 token 实现。
 *
 * 生命周期挂在 Application 的 scope 上而不是 Activity：
 * 用户复制完密码马上切到浏览器去粘贴，这是最常见的路径，
 * 计时器绝不能因为界面进了后台就停摆。
 */
class SecureClipboard(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val cm = context.applicationContext
        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** 正在倒计时的那一份。UI 据此显示「密码已复制 · 12 秒后清除」。 */
    data class Pending(val label: String, val remainingSeconds: Int, val totalSeconds: Int)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private var job: Job? = null
    private var token: String? = null

    /**
     * 复制一段敏感内容并启动倒计时。
     *
     * @param label 给用户看的名字（「密码」「账号」「恢复码」），不是内容本身
     * @param seconds 0 或负数表示不自动清除（设置页允许关掉，但会有明确警告）
     */
    fun copySensitive(label: String, value: String, seconds: Int = DEFAULT_SECONDS) {
        val t = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(label, value).apply {
            description.extras = PersistableBundle().apply {
                // 常量在 API 33 才有，这里直接写字面量，低版本放着也无害
                putBoolean("android.content.extra.IS_SENSITIVE", true)
                putString(EXTRA_TOKEN, t)
            }
        }
        runCatching { cm.setPrimaryClip(clip) }
            .onFailure { Log.w(TAG, "写入剪贴板失败", it); return }

        token = t
        job?.cancel()
        job = null

        if (seconds <= 0) {
            /*
             * 用户在设置里关掉了自动清除（M3-6a 起这个开关才真的点得到）。
             *
             * 这时候**不能什么都不显示**。决策(51) 定的是「复制之后不弹任何提示，
             * 顶部那条倒计时就是回执」——一旦倒计时不存在，那条回执也跟着没了，
             * 于是点复制变成一个毫无反馈的动作：用户不知道成没成功，
             * 会再点一次，甚至怀疑这个按钮坏了。
             *
             * 所以照样发一条 Pending，只是它不倒数、也不会自己消失，
             * 一直挂到用户点「立即清除」或者复制别的东西为止。
             * `totalSeconds = 0` 就是「这一份不会自动清除」的标记，
             * 封条那一层据此换掉文案（见 Seal.kt 的 ClipboardBar）。
             *
             * 它一直挂着不是副作用，是有意的：那条常驻的横幅本身就在提醒
             * 「你现在有一份密码躺在剪贴板里」，而这正是关掉自动清除的代价。
             */
            _pending.value = Pending(label, remainingSeconds = 0, totalSeconds = 0)
            return
        }

        job = scope.launch {
            for (left in seconds downTo 1) {
                _pending.value = Pending(label, left, seconds)
                delay(1000)
            }
            clearIfOurs()
            _pending.value = null
        }
    }

    /** 用户手动点「立即清除」，或离开详情页时调用。 */
    fun clearNow() {
        job?.cancel()
        job = null
        clearIfOurs()
        _pending.value = null
    }

    /**
     * 只清掉我们自己放进去的那一份。
     *
     * Android 10 起后台应用读不到剪贴板，`primaryClipDescription` 可能返回 null。
     * 这种情况下选择**照清不误**：我们能确定的是 15 秒内自己放过一份密码进去，
     * 而误清一段用户刚复制的普通文本，代价远小于把密码留在剪贴板里。
     */
    private fun clearIfOurs() {
        val expected = token ?: return
        val desc = runCatching { cm.primaryClipDescription }.getOrNull()
        val actual = desc?.extras?.getString(EXTRA_TOKEN)
        if (actual != null && actual != expected) {
            Log.d(TAG, "剪贴板内容已被替换，跳过清除")
            token = null
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }.onFailure { Log.w(TAG, "清除剪贴板失败", it) }
        token = null
    }

    companion object {
        private const val TAG = "SecureClipboard"
        private const val EXTRA_TOKEN = "cn.localvault.clip_token"
        const val DEFAULT_SECONDS = 15
    }
}
