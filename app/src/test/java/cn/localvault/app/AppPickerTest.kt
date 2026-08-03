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

package cn.localvault.app

import cn.localvault.app.ui.apps.AppPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用选择器的排序、搜索、分段。
 *
 * 盯着三件事：
 *
 *  - **用户自己装的排在系统自带前面**。切错方向的表现是：打开选择器，
 *    满屏都是相机、时钟、文件管理，真正要找的那个被顶到半屏以下。
 *  - **中文按拼音排**。按码点排的话「百度」会跑到「爱奇艺」前面，
 *    用户完全说不出这份名单按什么排的（同 `VaultIndexTest` 里那条）。
 *  - **包名也能搜到**。知道包名的是少数人，但他们打的一定是包名。
 */
class AppPickerTest {

    private fun app(pkg: String, label: String, system: Boolean = false) =
        AppPicker.App(packageName = pkg, label = label, system = system)

    /* ── 排序 ── */

    @Test
    fun `自己装的在前，系统自带在后`() {
        val out = AppPicker.order(
            listOf(
                app("com.android.settings", "设置", system = true),
                app("com.tencent.mm", "微信"),
            ),
        )
        assertEquals(listOf("com.tencent.mm", "com.android.settings"), out.map { it.packageName })
    }

    @Test
    fun `中文按拼音排，不按码点排`() {
        // 码点序：百 U+767E < 爱 U+7231 为假（百 0x767E，爱 0x7231），
        // 按码点「爱奇艺」会排在「百度」前面；按拼音 bai < ai 为假，ai < bai 为真。
        val out = AppPicker.order(listOf(app("a", "百度"), app("b", "爱奇艺")))
        assertEquals(listOf("爱奇艺", "百度"), out.map { it.label })
    }

    @Test
    fun `同名时按包名兜底，排序稳定`() {
        val out = AppPicker.order(listOf(app("z.app", "相机"), app("a.app", "相机")))
        assertEquals(listOf("a.app", "z.app"), out.map { it.packageName })
    }

    /* ── 搜索 ── */

    @Test
    fun `按应用名搜`() {
        assertTrue(AppPicker.matches(app("com.tencent.mm", "微信"), "微"))
        assertFalse(AppPicker.matches(app("com.tencent.mm", "微信"), "支付"))
    }

    @Test
    fun `按包名搜，忽略大小写`() {
        assertTrue(AppPicker.matches(app("com.tencent.mm", "微信"), "TENCENT"))
    }

    @Test
    fun `空查询全部命中`() {
        val apps = listOf(app("a", "甲"), app("b", "乙"))
        assertEquals(2, AppPicker.filter(apps, "   ").size)
    }

    /* ── 分段 ── */

    @Test
    fun `用户装的那一段没有标题，系统那一段有`() {
        val s = AppPicker.sections(
            listOf(app("com.tencent.mm", "微信"), app("com.android.settings", "设置", true)),
        )
        assertEquals(2, s.size)
        assertNull(s[0].title)
        assertEquals(AppPicker.SYSTEM_TITLE, s[1].title)
    }

    @Test
    fun `空的那一段不产生空标题`() {
        val s = AppPicker.sections(listOf(app("com.tencent.mm", "微信")))
        assertEquals(1, s.size)
        assertNull(s[0].title)
    }

    @Test
    fun `搜索之后仍然分段`() {
        val apps = listOf(app("com.third.cam", "相机大师"), app("com.android.camera", "相机", true))
        val s = AppPicker.sections(AppPicker.filter(apps, "相机"))
        assertEquals(2, s.size)
    }

    /* ── 日志泄漏 ── */

    @Test
    fun `toString 里不出现包名或应用名`() {
        val s = app("com.tencent.mm", "微信").toString()
        assertFalse(s.contains("tencent"))
        assertFalse(s.contains("微信"))
    }
}
