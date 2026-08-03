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

package cn.localvault.app.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 页面之间传递「草稿初值」的一次性交接槽。目前只有一个用途：
 * 搜索没找到时，把用户刚打的那几个字带进新增流的名称栏。
 *
 * ── 为什么不用路由参数 ──
 *
 * `nav.navigate("add?name=招商")` 是最省事的写法，而且看起来无害。
 * 但 [Route] 顶上那条界限（**只允许条目 id 进路由，其余一律从内存里取**）
 * 在这里第一次被真正考验，而这次考验它必须守住：
 *
 * 路由参数会随 back stack 一起被系统写进 `savedInstanceState`，
 * 那是一条明文落盘的路径。而搜索关键词**本身就是库内容的投影**——
 * 用户打下「招商」两个字，这两个字就等于「这个库里有招商银行」。
 * 整个产品的前提是「除了那一个加密文件，别处不留任何库内容」，
 * 一个字符串参数就能把这个前提破掉，而且破得悄无声息。
 *
 * 条目 id 是随机 UUID，它进 Bundle 什么也说明不了，所以那条界限画在那个位置。
 *
 * ── 生命周期 ──
 *
 * 实例挂在**已解锁那张图**上（见 `UnlockedGraph`）。锁定时整棵子树连同
 * back stack 一起被换掉（决策⑪），这个对象自然跟着一起没了——
 * 不需要谁记得在锁定时清空它，忘了清空这件事根本没有发生的机会。
 *
 * [takeName] 取一次就清空：新增页转屏重建时不该再被填一次名字，
 * 那会把用户已经改过的内容盖掉。
 */
class DraftHandoff {

    private var pendingName: String? = null

    /** 放一个名称初值进去。空白字符串等于什么都没放。 */
    fun offerName(name: String) {
        pendingName = name.trim().takeIf { it.isNotEmpty() }
    }

    /** 取走并清空。没有就返回 null。 */
    fun takeName(): String? {
        val v = pendingName
        pendingName = null
        return v
    }

    /** 用户中途退出新增流时调用，免得下次进来莫名其妙带着上次的字。 */
    fun clear() {
        pendingName = null
    }
}

val LocalDraftHandoff = staticCompositionLocalOf<DraftHandoff> {
    error("DraftHandoff 未提供：只有已解锁那张图里才能读它")
}
