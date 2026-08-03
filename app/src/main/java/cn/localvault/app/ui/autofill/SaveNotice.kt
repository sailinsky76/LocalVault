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

package cn.localvault.app.ui.autofill

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.localvault.app.R

/**
 * 「有一条待存的密码」那条通知。**保存确认页的第二条入口，也是唯一一条不看别人脸色的入口。**
 *
 * ── 为什么需要它 ──
 *
 * 保存这条链上，确认页原本只有一条入口：`SaveCallback.onSuccess(IntentSender)`，
 * 由系统去启动。M4-3c 一路查下来，那条入口在真机上**根本不到达**：
 *
 *   · 服务自己 `startActivity` —— 后台启动限制，静默拦下（M4-3b 的写法）；
 *   · 交给 `onSuccess(IntentSender)` —— 框架不是自己启动，而是回头交给
 *     **被填的那个应用**去发（`AutofillManagerClient.startIntentSender` 里那一句
 *     `afm.mContext.startIntentSender(...)`，`afm` 是一个弱引用）。而保存框是在
 *     用户登录成功、那个 Activity 已经销毁之后才弹出来的——真机日志上
 *     从「已断开」到用户按下「更新」隔了 14 秒。那个上下文早就没了，
 *     于是这一句什么都不做，**不抛异常、不回调、系统日志上也不留一行**
 *     （`ActivityTaskManager` 全程干净，所以它甚至不是被拦下的）。
 *
 * 两条路的共同点是：**它们都要求别人替我们把一个页面拉起来**。这一条不要求。
 * 通知是用户自己点的，由系统代发，是一条明确的后台启动豁免——它一定通。
 *
 * ── 通知里一个字的凭据都不出现 ──
 *
 * 不写用户名、不写密码、**也不写是哪个应用**。那三样任何一样都会画在锁屏上、
 * 进通知历史、被别的通知监听服务读到，而这个 App 的整个前提是
 * 「除了那一个加密文件，别处不留任何库内容」（`SaveHandoff` 文件头那段）。
 * 「有一条待存的密码」这句话本身不构成泄露：屏幕上刚刚才弹过系统那个保存框，
 * 用户知道说的是哪一条；旁人从这句话里什么也读不到。
 *
 * 同理 [NotificationCompat.VISIBILITY_SECRET]：锁屏上连这一行都不出现。
 *
 * ── 它不延长明文的寿命 ──
 *
 * 通知只是一张票的入口，明文仍然躺在 [SaveHandoff] 那个进程内的槽里，
 * 仍然受 [SaveHandoff.TTL_MILLIS] 管。所以这里 [NotificationCompat.Builder.setTimeoutAfter]
 * 用的就是那个 TTL：过了这个点票已经取不出来了，通知再挂着只会让用户点进一个
 * 「要存的东西不见了」的空页面。
 */
object SaveNotice {

    private const val TAG = "AutofillSave"

    /** 通道 id。整个 App 只有这一条通知，所以只有这一个通道。 */
    private const val CHANNEL_ID = "cn.localvault.app.save.pending"

    /**
     * 通知 id。同时只挂一条——[SaveHandoff.offer] 那条「同时只留一份」的纪律
     * 在通知这一侧的对应物：槽里只有一份，屏幕上就只该有一条入口。
     * 第二次 `notify` 用同一个 id，系统会替换掉上一条，不会并排两条指向同一个页面。
     */
    private const val NOTICE_ID = 0x10CD

    /**
     * 这条通知那个 `PendingIntent` 的 requestCode。
     * 必须和 `REQ_UNLOCK`(0x10CA) / `REQ_PICK`(0x10CB) / `REQ_SAVE`(0x10CC) 都不同，
     * 理由同那三处：共用一个 code 的两个 `PendingIntent` 会互相顶掉。
     */
    private const val REQ_NOTICE = 0x10CE

    /**
     * 这台设备上能不能发出去。33+ 要运行时权限，用户没给就是发不出去，
     * 那时候 `notify` 不会抛异常、也不会有任何东西出现——和这次要修的 bug
     * 同一种失败方式，所以必须在发之前问一次，问不到就走别的路。
     */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 发出去。返回「发成了没有」——发不成时调用方要自己把槽清掉
     * （那一份明文已经没有任何入口能取到了）。
     */
    fun post(context: Context, ticket: SaveHandoff.Ticket): Boolean {
        if (!canPost(context)) {
            Log.w(TAG, "发不出通知：没有通知权限")
            return false
        }
        return runCatching {
            ensureChannel(context)
            NotificationManagerCompat.from(context).notify(NOTICE_ID, build(context, ticket))
            true
        }.onFailure {
            // 只打异常类名（决策(144)）
            Log.w(TAG, "发不出通知：${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    /**
     * 收回去。**票一到手就调**（`AutofillSaveActivity.redeem`）：
     * 槽里已经没东西了，留着一条点进去只会看到空页面的通知是纯粹的噪音。
     * `setAutoCancel` 只管「用户点了」这一路，从别的入口（比如 28+ 那条路
     * 后来居然通了）进的页面还是要靠这一句。
     */
    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTICE_ID) }
    }

    private fun build(context: Context, ticket: SaveHandoff.Ticket) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("有一条待存的密码")
            .setContentText("点这里确认要存进保险库的内容")
            // 锁屏上连这一行都不出现
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // 票过期之后这条通知就是个死链接，让系统自己收走
            .setTimeoutAfter(SaveHandoff.TTL_MILLIS)
            .setContentIntent(entry(context, ticket))
            .build()

    /**
     * 点进去的那个入口。
     *
     * `FLAG_ACTIVITY_NEW_TASK` 在**这一条**路上是要的：发起方是系统，不是任何一个
     * 前台 Activity，所以这一页只能自成一个任务（清单里 `taskAffinity=""` 正是为它备的）。
     * 这和 28+ 那条 `onSuccess(IntentSender)` 上必须**去掉**这个旗子不矛盾——
     * 那一条的发起方是被填的那个 Activity，落点该压在它的任务上（决策(222)）。
     *
     * `FLAG_ONE_SHOT`：这张票本来就只该被用一次（[SaveHandoff.take] 取一次就清）。
     * `FLAG_CANCEL_CURRENT`：`PendingIntent` 按 (requestCode, Intent) 配对复用，
     * **extras 不参与配对**——不加它，第二条通知会复用第一条那个 `PendingIntent`，
     * 点进去拿到的是一张早就被取过的旧票（同 `VaultAutofillService.saveSender`）。
     */
    private fun entry(context: Context, ticket: SaveHandoff.Ticket): PendingIntent {
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(
            context,
            REQ_NOTICE,
            AutofillSaveActivity.intent(context, ticket)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags,
        )
    }

    /**
     * 通道。重复建是允许的（同 id 只更新可改的那几项），所以每次发之前建一次，
     * 省掉一个「有没有建过」的字段——那种字段在进程被杀之后就不准了。
     *
     * 重要度给 `HIGH`：用户刚刚按下「更新」，此刻他正等着看到点什么。
     * 给 `DEFAULT` 的话这条通知只会静静躺进抽屉，而症状——「按下更新什么都没发生」——
     * 和这个 bug 本身一模一样。
     */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "待存的密码",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "系统的保存框拉不起确认页时，从这里进去确认"
            setShowBadge(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
