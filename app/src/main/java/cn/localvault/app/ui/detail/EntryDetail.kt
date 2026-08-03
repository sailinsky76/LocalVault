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

package cn.localvault.app.ui.detail

import cn.localvault.app.core.vault.VaultEntry

/**
 * 条目详情页背后那些会被反复争论、又最容易悄悄写错的判断。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose**，
 * 和 [cn.localvault.app.ui.list.VaultIndex]、[cn.localvault.app.ui.unlock.UnlockGuard]
 * 是同一个套路。
 *
 * 这一页有两件事在界面上特别难验证：
 *  - 删除之后还能不能原样撤回来（要真删一条再撤，撤错了数据就没了）；
 *  - 确认弹窗里到底会印出什么（决策⑭那一条的整个立论就在这儿）。
 * 切成纯函数之后两件事都能在单测里钉死。
 */
object EntryDetail {

    /* ══════════════════════════ 删除与撤销 ══════════════════════════ */

    /**
     * 被删掉的那一条的快照，连同它原来在列表里的位置。
     *
     * ── 为什么撤销靠内存快照，而不是「延迟 5 秒再真删」 ──
     *
     * 「先在界面上删掉、5 秒内没人点撤销才真正落盘」是很常见的写法，
     * 放在这个 App 里却是错的，因为两种做法的**失败方向相反**：
     *
     *  - 延迟删除失败时（进程被系统杀掉、自动锁定、崩溃，任何一种都会让
     *    那个还没执行的删除凭空消失），结果是**用户以为删了，其实还在**。
     *  - 立刻落盘 + 内存撤销失败时，结果是**想撤的时候撤不回来**。
     *
     * 密码管理器必须选后者。「以为删了其实还在」意味着他把手机卖掉、
     * 送修、或者交给别人时，以为已经清干净了。那是不可接受的失败方向；
     * 「撤销撤不回来」只是可惜。
     *
     * 所以 [remove] 立刻返回删好的新列表（由会话立刻落盘），
     * 快照只活在内存里，页面一销毁就没了。
     */
    data class Removed(val entry: VaultEntry, val index: Int)

    /**
     * 从列表里摘掉一条，同时留下撤销所需的快照。
     *
     * 找不到时返回原列表和 null —— 重复调用（比如用户连点两下删除）
     * 不会把上一次的快照冲掉，也不会误删别的条目。
     */
    fun remove(entries: List<VaultEntry>, id: String): Pair<List<VaultEntry>, Removed?> {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return entries to null
        val snapshot = Removed(entries[index], index)
        return entries.filterIndexed { i, _ -> i != index } to snapshot
    }

    /**
     * 把快照插回原来的位置。
     *
     * 位置要恢复，不是往末尾一扔：列表虽然是按名称排序展示的
     * （[cn.localvault.app.ui.list.VaultIndex.sections]），但底层顺序会进文件、
     * 会进备份、也会被将来的导出用到。撤销的语义是「什么都没发生过」，
     * 那就该连顺序一起没发生过。
     *
     * id 已经存在时原样返回：撤销按钮被连点两下不该让同一条出现两遍。
     */
    fun restore(entries: List<VaultEntry>, removed: Removed): List<VaultEntry> {
        if (entries.any { it.id == removed.entry.id }) return entries
        val at = removed.index.coerceIn(0, entries.size)
        return entries.toMutableList().apply { add(at, removed.entry) }
    }

    /* ══════════════════════════ 打码 ══════════════════════════ */

    /**
     * 把账号打码成「认得出是哪一条，但抄不走」的样子。
     *
     * 只用在**确认弹窗**里。详情页正文里的账号是明文——人都已经点进这一条了，
     * 再打码只是碍事。弹窗不一样：它是一个独立的 window（决策⑭），
     * 虽然我们给它显式加了 `FLAG_SECURE`，能少放一点明文就少放一点。
     *
     * ── 规则：只留头，不留尾；手机号是唯一的例外 ──
     *
     * 尾部对「认出是哪一条」帮助不大，对撞库却很有用：
     * 很多人的邮箱前缀就是他到处在用的用户名，露出后半段等于白送。
     * 手机号例外，是因为中国人认自己的手机号靠的就是后四位——
     * 这里不照顾这个习惯，弹窗上那行字就失去了它存在的意义。
     */
    fun maskIdentity(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""

        // 邮箱：域名整段保留，它本来就是公开信息，而且是认出条目的主要线索
        val at = s.lastIndexOf('@')
        if (at > 0 && at < s.length - 1 && s.indexOf('.', at) > at) {
            val local = s.substring(0, at)
            val domain = s.substring(at)
            return maskHeadOnly(local) + domain
        }

        // 手机号 / 纯数字账号
        if (s.length in 7..15 && s.all { it.isDigit() }) {
            val front = if (s.length >= 11) 3 else 2
            val back = if (s.length >= 11) 4 else 2
            if (front + back < s.length) {
                return s.take(front) + "****" + s.takeLast(back)
            }
        }

        return maskHeadOnly(s)
    }

    /** 短到藏不住的就整段藏掉——露出「一共三个字，头两个是 zh」也是在帮忙猜。 */
    private fun maskHeadOnly(s: String): String = when {
        s.length <= 2 -> "*".repeat(s.length)
        s.length <= 5 -> s.take(1) + "****"
        else -> s.take(2) + "****"
    }

    /**
     * 删除确认弹窗里那行小字：「这一条是哪一条」。
     *
     * **它永远不含密码，也不含备注。** 这不是一句提醒，是这个函数存在的理由：
     * 一旦有人为了「让用户看清楚删的是什么」把更多字段拼进来，
     * 那一屏就成了一个把密码摆在独立 window 上的地方（决策⑭）。
     * 所以拼装逻辑放在这里，由单测盯着。
     */
    fun deleteConfirmDetail(entry: VaultEntry): String {
        val who = when {
            entry.username.isNotBlank() -> maskIdentity(entry.username)
            entry.domains.isNotEmpty() -> entry.domains.first()
            else -> ""
        }
        return if (who.isEmpty()) entry.name else "${entry.name} · $who"
    }

    /* ══════════════════════════ 页面上有哪些行 ══════════════════════════ */

    /**
     * 详情页从上到下的那几行。
     *
     * **空字段不占位。** 一个只记了账号没记密码的条目（有人拿它当通讯录用），
     * 不该显示一行空的密码——空行会让用户以为密码丢了。
     *
     * 这里**没有动态验证码**（`VaultEntry.totpSecret`）。字段在数据模型里留着
     * 是为了将来不用改文件格式，但界面上一行都不画：
     * 显示一个点了没反应的「验证码」行，比不显示更糟——
     * 用户会把它当成能用的功能，然后在需要的时候发现它是个摆设。
     * 那是二期的事。
     */
    enum class Row { Username, Password, Domain, Category, Notes }

    fun rows(entry: VaultEntry): List<Row> = buildList {
        if (entry.username.isNotBlank()) add(Row.Username)
        if (entry.password.isNotEmpty()) add(Row.Password)
        if (entry.domains.any { it.isNotBlank() }) add(Row.Domain)
        if (entry.category.isNotBlank()) add(Row.Category)
        if (entry.notes.isNotBlank()) add(Row.Notes)
    }

    fun label(row: Row): String = when (row) {
        Row.Username -> "账号"
        Row.Password -> "密码"
        Row.Domain -> "网址 / 应用"
        Row.Category -> "分类"
        Row.Notes -> "备注"
    }

    /**
     * 进页面时默认藏起来的行。
     *
     * 密码要藏是显然的。**备注也要藏**，理由和「备注不参与搜索」（决策㉜）
     * 是同一条：备注恰恰是用户拿来放密保问题答案、身份证号、
     * 银行预留手机号的地方。详情页一打开就把它摊平，
     * 等于在地铁上点开一条记录就把身份证号亮给旁边的人——
     * 而用户按下那一条的时候，想找的多半只是账号。
     */
    fun hiddenByDefault(row: Row): Boolean = row == Row.Password || row == Row.Notes

    /**
     * 这一行给不给复制按钮。
     *
     * 分类不给：它是用来分组的一个词，复制它没有任何使用场景，
     * 而每多一个按钮就多一次误触把东西送进剪贴板的机会。
     */
    fun copyable(row: Row): Boolean = row != Row.Category

    /**
     * 复制时写进剪贴板的**标签**（不是内容）。
     *
     * 标签会出现在封条那条倒计时上（「密码已复制 · 12 秒后清除」），
     * 也会作为 ClipData 的 label 交给系统。所以它只能是字段名，
     * 绝不能带上条目名——「招商银行的密码」印在系统剪贴板面板上，
     * 等于把「这台手机的主人有招商银行账户」告诉了每一个能读剪贴板描述的应用。
     */
    fun clipboardLabel(row: Row): String = label(row)
}
