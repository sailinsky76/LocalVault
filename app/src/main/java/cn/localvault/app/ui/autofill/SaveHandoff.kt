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

/**
 * 把一份 [SaveContext] 从 `AutofillService` 交到保存确认页手上的一次性槽。
 *
 * **整个文件没有一行 `android.*`。** 它只是一个带票号的槽，之所以要单独存在，
 * 是因为它替掉的那条路径写起来顺手得多，而且当天不会有任何症状。
 *
 * ── 为什么明文不能走 Intent（决策(198)）──
 *
 * `onSaveRequest` 里最自然的写法是：
 *
 * ```
 * intent.putExtra("username", user)
 * intent.putExtra("password", pwd)   // ← 就是这一行
 * startActivity(intent)
 * ```
 *
 * 那两行看起来只是「传给我自己的另一个页面」，实际上不是。`startActivity` 的
 * `Intent` 要经过 `system_server`：extras 会被 parcel 出去、在系统进程里被解析、
 * 排进 `ActivityManager` 的记录，还会被 `dumpsys activity` 打出来。
 * 密码于是离开了我们这个进程，落进了一个我们既管不着生命周期、
 * 也不知道谁在 `dumpsys` 的地方——而这个 App 的整个前提是
 * 「除了那一个加密文件，别处不留任何库内容」。
 *
 * `DraftHandoff` 早就为**搜索关键词**画过同一条界限（那边是不许进
 * `savedInstanceState`），理由一字不差；这里只是同一条界限上更硬的一段：
 * 那边漏出去的是「这个库里有招商银行」，这里漏出去的是密码本身。
 *
 * 所以进 `Intent` 的只有 [Ticket] 那个数字。**一个数字什么也说明不了**——
 * 同 `Route` 那条界限只放行随机 UUID 的条目 id。
 *
 * ── 生命周期 ──
 *
 * 这个槽是**进程内**的，而 `AutofillService` 和保存确认页在同一个进程里
 * （同一个 APK、没有 `android:process`），所以「放进去、拉起页面、取出来」
 * 全程不跨进程。
 *
 * 三条纪律，M4-3b 必须逐条兑现：
 *   1. [take] **取一次就清**。页面转屏重建时不该再拿到一份明文，
 *      那会让同一个密码在内存里多出一份没人负责清理的副本（同 `DraftHandoff.takeName`）；
 *   2. 页面结束时（无论确认、取消还是被系统干掉）调一次 [clear]；
 *   3. **自动锁定时调一次 [clear]**。库都锁上了，进程里却还揣着一份刚读到的明文密码，
 *      那正是自动锁定要消灭的东西。
 *
 * 除此之外还有一道兜底：[take] 会看时间，超过 [TTL_MILLIS] 的一律不给
 * （见 [take]）。它防的是「用户按下取消键之后什么回调都没来」这一类——
 * 那种情况下上面三条一条都不会触发，而一份明文会在进程里一直躺到进程死掉。
 */
object SaveHandoff {

    /**
     * 交接单最多存活多久。
     *
     * 五分钟：从 `onSaveRequest` 到用户在确认页上按下按钮，中间可能隔着一次主密码解锁、
     * 一次指纹、一次切出去看验证码。给短了会出现「解锁完回来发现要存的东西没了」，
     * 而那时他刚打的密码已经不在屏幕上了——这个 App 最不能制造的就是这种丢失。
     *
     * 给长了则相反：一份明文在进程里躺着，躺多久就危险多久。
     * 五分钟和默认的自动锁定时长（60 秒）不挂钩是有意的——
     * 锁定那条路已经由上面第 3 条纪律直接 [clear] 掉了，
     * 这个 TTL 兜的是**回调一次都没来**的那种情况。
     */
    const val TTL_MILLIS: Long = 5 * 60 * 1000L

    /** 进 `Intent` 的东西只有它。一个数字，不透明，什么也说明不了。 */
    @JvmInline
    value class Ticket(val id: Long) {
        override fun toString(): String = "Ticket($id)"
    }

    private var ticket: Long = 0L
    private var pending: SaveContext? = null
    private var expiresAt: Long = 0L
    private var counter: Long = 0L

    /**
     * 放一份进去，拿回一张票。
     *
     * **同时只留一份。** 前一份会被当场丢掉（连同它的票）：
     * 两份并存意味着进程里躺着两份明文，而后一份一定更新、更该被用。
     * 这也让「忘了清理」这件事至少不会累积。
     */
    @Synchronized
    fun offer(context: SaveContext, now: Long): Ticket {
        pending = context
        counter += 1
        ticket = counter
        expiresAt = now + TTL_MILLIS
        return Ticket(ticket)
    }

    /**
     * 取一次就清。票对不上、或者已经过期，都返回 null。
     *
     * 过期那一路**照样清**：留着一份连票都对不上的明文毫无用处，
     * 它只会等着某一次 `dumpsys` 或者堆转储。
     */
    @Synchronized
    fun take(t: Ticket, now: Long): SaveContext? {
        // 票对得上：无论过没过期都清掉，然后按过期与否决定给不给。
        if (ticket != 0L && t.id == ticket) {
            val value = if (now < expiresAt) pending else null
            clearInternal()
            return value
        }
        // 票对不上：**不许动手里这一份**。一张过期页面留下的旧票不该把
        // 刚放进来的那一份清掉——那会表现成「用户提交完登录，保存屏空着弹出来」，
        // 而他刚打的密码已经不在屏幕上了。
        if (pending != null && now >= expiresAt) clearInternal()
        return null
    }

    /** 有没有东西等着被取。**不返回内容**，只回答有没有——给日志和测试用。 */
    @Synchronized
    fun hasPending(now: Long): Boolean = pending != null && now < expiresAt

    /**
     * **这张票**还在槽里、而且还没过期吗。不返回内容，只回答有没有。
     * 给守望那一段用（`VaultAutofillService.watchLanding`）：确认页取票是在
     * `onCreate`/`onNewIntent` 的第一步，所以「过了几秒票还在」只有一个解释——
     * 那一页没起来。
     */
    @Synchronized
    fun isPending(t: Ticket, now: Long): Boolean =
        ticket != 0L && t.id == ticket && pending != null && now < expiresAt

    /**
     * **这张票**还在槽里的话，就地丢掉，并回答「刚才是不是我丢的」。
     *
     * 给守望那一段用（`VaultAutofillService.watchLanding`）：确认页要么当场起来、
     * 要么永远不起来，所以「过几秒还没人来取」等于「这一份明文没人会来取了」。
     * 那时候把它留满 [TTL_MILLIS] 只是白留——纪律第 2、3 条都长在确认页上，
     * 那一页没起来，一条都不会触发。
     *
     * 必须按票判、而且判和清要在同一把锁里：中间可能已经来过一次新的保存请求，
     * 槽里换成了另一份。认票之后再清，才不会把刚放进来的那一份误伤掉
     * （同 [take] 里「票对不上不许动手里这一份」那一条）。
     */
    @Synchronized
    fun dropIfPending(t: Ticket, now: Long): Boolean {
        if (ticket == 0L || t.id != ticket) return false
        val wasLive = now < expiresAt
        clearInternal()
        return wasLive
    }

    /** 手动清空。三条纪律里的第 2、3 条走这里。 */
    @Synchronized
    fun clear() = clearInternal()

    private fun clearInternal() {
        pending = null
        ticket = 0L
        expiresAt = 0L
    }
}
