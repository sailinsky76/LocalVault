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

package cn.localvault.app.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.localvault.app.ui.theme.VaultColors
import cn.localvault.app.ui.theme.VaultShape
import cn.localvault.app.ui.theme.VaultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一个页面/覆盖层活着的期间共用一份目录。
 *
 * 用 `remember` 而不是挂在 `LocalProviders` 上：这份名单没有跨页面共享的价值
 * （选择器一次就用完了），而挂上去意味着它会**跟着已解锁那张图一直活着**——
 * 一份「这台手机装了什么」的清单在内存里多留几分钟没有任何好处。
 * 页面一走它就跟着被回收，同 [cn.localvault.app.ui.generate.GeneratorHolder]
 * 拒绝落盘那段的思路。
 */
@Composable
fun rememberAppCatalog(): InstalledAppCatalog {
    val context = LocalContext.current
    return remember(context) {
        InstalledAppCatalog(context.packageManager, context.packageName)
    }
}

/**
 * 应用图标。取不到（没装 / 解析失败）时画一个**首字方块**兜底。
 *
 * 兜底不画问号也不画一个通用的安卓小人：那两样都在说「出错了」，
 * 而这里最常见的情形其实是**这条是从别的手机上导入的**，一点错都没有。
 * 首字方块和这套设计里条目列表的头像是同一个语言，用户不会觉得哪里坏了。
 *
 * 栅格化放在 IO 线程上（[produceState] + `withContext`）：
 * 自适应图标要合成前后两层，一屏十来个在组合线程上做会掉帧。
 * 出图之前先画兜底方块，不留空洞——空洞在滚动时看起来像加载失败。
 */
@Composable
fun AppIcon(
    packageName: String,
    label: String?,
    catalog: InstalledAppCatalog,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, packageName, px) {
        value = withContext(Dispatchers.IO) { catalog.iconOf(packageName, px) }
    }

    Box(
        modifier = modifier.size(size).clip(VaultShape.TileSm).background(VaultColors.Slab2),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(size))
        } else {
            Text(
                text = initialOf(label ?: packageName),
                style = VaultType.MonoSmall,
                color = VaultColors.Dim,
            )
        }
    }
}

/**
 * 首字。
 *
 * 中文取第一个字，英文取第一个字母的大写；包名兜底时**跳过 `com.` 这类前缀**，
 * 否则手机上一半的方块都会是「C」，等于没画。
 */
private fun initialOf(text: String): String {
    val s = text.trim()
    if (s.isEmpty()) return "?"
    if (s.count { it == '.' } >= 1 && s.all { it.code < 128 }) {
        val meaningful = s.split('.').lastOrNull { it.isNotEmpty() } ?: s
        return meaningful.first().uppercaseChar().toString()
    }
    return s.first().uppercaseChar().toString()
}
