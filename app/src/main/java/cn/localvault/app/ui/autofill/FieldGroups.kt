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

import cn.localvault.app.ui.list.VaultIndex

/**
 * 把一屏输入框切成几组「表单」，**并且每组各算各的归属**。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * `AssistStructure` 那棵树怎么走成 [FillContext] 是 M4-1b-3 那层薄壳的事。
 *
 * ── 这一步是决策(158) 唯一能被写错的地方 ──
 *
 * 决策(158) 说的是「归属算在字段上，不算在请求上」。那条决策落到代码里，
 * 就是这个文件里 [originKey] / [originOf] 那六行——一屏上可能同时有
 * 原生输入框和 WebView 输入框，也可能有两个来自不同网站的 iframe，
 * 而系统把它们**装在同一个请求里**交过来。
 *
 * 写错的形态只有一种，而且写起来非常顺手：先扫一遍整棵树，
 * 找到第一个非空的 `webDomain` 当作「这次请求是给哪个网站的」，
 * 然后拿它去匹配所有字段。那正是 AutoSpill 走的门：一个恶意应用套一个 WebView，
 * 里面那几个框如实带着 `webDomain = 你的网银`，而同一屏上它自己的原生输入框
 * 会跟着一起被算成「属于你的网银」——用户点一下填充条，
 * 密码就写进了那个应用自己读得到的框里。
 *
 * 所以这里的规矩是死的：**分组的第一把钥匙就是归一后的 `webDomain`**，
 * 空的（原生框）和非空的（网页框）永远不可能落进同一组，
 * 两个不同的 `webDomain` 也永远不可能落进同一组。
 * `hostApp` 一律取 [FillContext.activityPackage]——那是系统给的，应用改不了。
 *
 * ── 为什么不按控件层级切 ──
 *
 * 「同一个 `<form>` 里的框算一组」听起来最正确，但那棵树给不出这个信息：
 * WebView 交上来的节点极少暴露 `<form>` 边界，原生表单更是想怎么套 `LinearLayout`
 * 就怎么套。真按父节点切，最常见的结果是**每个框各成一组**
 * （每个 `<input>` 各自裹着一层 div），于是账号和密码永远配不到一起，
 * 表现是「自动填充只填账号不填密码」。所以这里只用两条拿得准的依据：
 * 归一后的 `webDomain`，和角色出现的顺序（见 [Bucket.accepts]）。
 */
object FieldGroups {

    /**
     * 一个进了组的框：原始事实 + [FieldRoles] 给出的角色。
     *
     * 不是 `data class`，`toString` 手写只报形状——同决策(144)。
     * 它抱着一个 [RawField]，而那里面有 `webDomain`（用户上过哪些站，
     * 见 `RawField.toString` 那段注释）和页面文案。
     */
    class Field internal constructor(
        val raw: RawField,
        val role: FieldRoles.Role,
    ) {
        val handle: Long get() = raw.handle

        override fun toString(): String = "Field(#$handle, ${role.name})"
    }

    /**
     * 一组框，以及**这一组自己的**归属。
     *
     * [fields] 保持它们在请求里出现的顺序（薄壳按树的先序走，那大致就是视觉顺序），
     * 因为「第一个账号框」「第一个密码框」这两个说法要靠顺序才有意义。
     */
    class Group internal constructor(
        val origin: Origin,
        val fields: List<Field>,
    ) {
        /** 光标此刻在不在这一组里。系统给的，是「用户正在看哪一屏」最硬的信号。 */
        val focused: Boolean get() = fields.any { it.raw.focused }

        val isWeb: Boolean get() = origin is Origin.Web

        fun withRole(role: FieldRoles.Role): List<Field> = fields.filter { it.role == role }

        /**
         * 不是 `data class`，而且**刻意不把 [origin] 打进来**：
         * 那里面有主机名和承载应用的包名，顺手写进一句日志就等于把
         * 「这台手机的主人在什么应用里登录了哪个站」抄进 logcat。
         */
        override fun toString(): String =
            "Group(${if (isWeb) "web" else "app"}, ${fields.size} fields)"
    }

    /* ══════════════════════════ 切组 ══════════════════════════ */

    /**
     * 把一次请求切成若干组。
     *
     * 三件事按顺序做：
     *   1. 逐个框问 [FieldRoles] 它是干什么的，[FieldRoles.Role.Other] 一律丢掉
     *      （看不见的、应用明说别填的、不是文本框的，在那一层就已经变成 `Other` 了）；
     *   2. 按归一后的 `webDomain` 分家——**这一条不留任何例外**，见文件头；
     *   3. 同一家之内，按角色出现的顺序再切（见 [Bucket.accepts]）。
     *
     * 输出的顺序是「每组第一个框出现的顺序」，稳定可预期：
     * M4-2a 要拿 [FillPlan.pick] 从里面挑一组当主表单，
     * 而一个顺序会抖的列表会让「今天填了明天不填」这种最难查的问题冒出来。
     *
     * 丢掉 `Other` 之后一个框都不剩的组不会出现在结果里（不产生空组），
     * 但**只剩验证码框的组会保留**：它是一句要对用户说的话
     * （「这一屏没有可以填的东西」），比一声不响地消失有用。
     */
    fun split(context: FillContext): List<Group> {
        val buckets = ArrayList<Bucket>()
        for (raw in context.fields) {
            val role = FieldRoles.classify(raw, context.respectOptOut).role
            if (role == FieldRoles.Role.Other) continue

            val key = originKey(raw)
            val open = buckets.lastOrNull { it.key == key }
            val bucket = if (open != null && open.accepts(role)) {
                open
            } else {
                Bucket(key).also { buckets += it }
            }
            bucket.fields += Field(raw, role)
        }
        return buckets.map { Group(originOf(context, it.key), it.fields.toList()) }
    }

    /**
     * 攒组用的可变桶。`key == null` 表示原生框那一家。
     */
    private class Bucket(val key: String?) {
        val fields = ArrayList<Field>()

        /**
         * 这个角色还能不能进这一桶。
         *
         * 只有一条规则：**已经收过密码框的桶，不再收账号框。**
         * 它切的是「登录表单和注册表单同屏」这种最常见的布局
         * （账号 密码 ─ 账号 密码，两套并排或上下摆着）。少了这一刀，
         * 两套表单会被当成一个「有两个账号框、两个密码框」的怪表单，
         * 于是 [FillPlan] 只会往第一套里填，第二套永远填不上；
         * 更糟的是那两个密码框会被判成「分不出新旧」，结果一个都不填。
         *
         * 反过来**不因为「又来了一个密码框」而切**：那种形状
         * （账号 密码 密码）几乎总是同一个表单里的「密码 + 确认密码」，
         * 硬切开会得到一个只有密码框的第二组，看起来像分屏登录的第二屏。
         * 一个表单里出现两个分不出新旧的密码框该怎么办，
         * 是 [FillPlan] 的事，不是切组的事——那一层的答案是「一个都不填」。
         */
        fun accepts(role: FieldRoles.Role): Boolean =
            !(role == FieldRoles.Role.Username && fields.any { isPasswordish(it.role) })

        private fun isPasswordish(role: FieldRoles.Role): Boolean =
            role == FieldRoles.Role.Password || role == FieldRoles.Role.NewPassword
    }

    /* ══════════════════════════ 归属 ══════════════════════════ */

    /**
     * 这个框自称属于哪个网站，归一之后的样子；原生框返回 null。
     *
     * 归一走 `VaultIndex.normalizeDomain`，**不另写一份**——决策㉝ 那句
     * 「不许各写各的」的字面兑现（条目那一侧在 `DomainMatch.judge` 里用的是同一个函数）。
     * 系统交上来的 `webDomain` 通常已经是个干净的主机名，但不保证：
     * 有的浏览器交的是一整条 URL，也有交上来带端口的。不归一的后果是
     * `example.com:443` 和条目里的 `example.com` 对不上，而用户看不出为什么。
     *
     * 空串和只有空白的按原生算：一个说不出自己属于哪个网站的 WebView 框，
     * 没有任何「自称」可供采信，那就只剩承载它的应用这一条硬事实。
     */
    private fun originKey(f: RawField): String? {
        val raw = f.webDomain ?: return null
        val host = VaultIndex.normalizeDomain(raw)
        return host.ifEmpty { null }
    }

    /**
     * `hostApp` 永远取 [FillContext.activityPackage]，**永远不取 `webDomain`**。
     * 这两条线的分工写在 [Origin] 的文件头里，这里只是最后一道落笔。
     */
    private fun originOf(context: FillContext, key: String?): Origin =
        if (key == null) {
            Origin.App(context.activityPackage)
        } else {
            Origin.Web(key, context.activityPackage)
        }
}
