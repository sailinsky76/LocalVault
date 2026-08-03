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

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * [AppPicker] 的线上实现：**只有「怎么从 `PackageManager` 把东西取出来」，没有判断。**
 *
 * 判断（排序、搜索、分段）全在 [AppPicker] 里，可以在纯 JVM 上单测——
 * 同 `AndroidHostTrust` / `BrowserTrust`、`AssistShell` / `StructureRules` 那条缝。
 *
 * ── 为什么是 MAIN/LAUNCHER 查询，不是 `getInstalledApplications` ──
 *
 * 两个原因，一个是能不能，一个是该不该。
 *
 * **能不能**：Android 11（API 30）起包可见性默认收紧，`getInstalledApplications`
 * 只能看到自己和少数几个包，要拿全名单得申请 `QUERY_ALL_PACKAGES`——
 * 那是一项敏感权限，会出现在「应用信息 → 权限」里，上架还要单独申报理由。
 * 这个 App 的权限清单只有一条 `USE_BIOMETRIC`，而那份清单本身就是产品卖点
 * （见 AndroidManifest 顶上那个框）。为了一个输入辅助去动它，不划算。
 * 清单里那条 `<queries>` **不是权限**，不会出现在权限页上。
 *
 * **该不该**：`getInstalledApplications` 连没有启动图标的东西都给你——
 * 输入法、厂商的推送常驻、各种服务壳子。用户在那份名单里既认不出自己要的，
 * 也不会想给它们存密码。有启动图标的那一批，恰好就是他心里「我装的应用」。
 *
 * ── 缓存 ──
 *
 * 名单和图标都按需缓存在这个对象里，**活到它被丢掉为止**（选择器关掉、页面离开）。
 * 不做持久化：一份躺在磁盘上的「这台手机装了哪些应用」既是新的用户数据，
 * 也是新的攻击面，而它省下的只是几十毫秒。同 `AndroidHostTrust` 那段。
 */
class InstalledAppCatalog(
    private val packages: PackageManager,
    /** 自己的包名。名单里要把自己去掉——见 [load]。 */
    private val selfPackage: String,
) {

    private var cached: List<AppPicker.App>? = null
    private val labels = HashMap<String, String?>()
    private val icons = HashMap<String, ImageBitmap?>()

    /**
     * 读一遍名单。**这一步可能要几百毫秒**（一次跨进程查询 + 每项一次标题解析），
     * 调用方必须放在后台线程上，别挂在组合里。
     *
     * ── 三件顺手做掉的事 ──
     *
     * · **按包名去重**：一个应用可以注册多个启动 Activity（不少厂商的自带应用如此），
     *   不去重的话名单上会出现两行一模一样的字，而用户看不出区别在哪儿。
     * · **去掉自己**：给密码管理器自己存一条密码没有意义——主密码从来不在库里。
     *   留着它只会让用户点进去然后困惑。
     * · **标题为空时退回包名**：极少数应用的 label 解析出来是空串，
     *   一行空白比一行包名难认得多。
     *
     * 读不到（`<queries>` 被改掉、厂商 ROM 有别的限制）返回空表，
     * 由界面去说 [AppPicker.UNAVAILABLE] 那句话。**不抛异常**：
     * 一个输入辅助失灵不该把新增流带崩，用户还有「添加网址」和手动编辑两条路。
     */
    @Synchronized
    fun load(): List<AppPicker.App> {
        cached?.let { return it }

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packages.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                packages.queryIntentActivities(intent, 0)
            }
        }.getOrElse { emptyList() }

        val seen = HashSet<String>()
        val out = ArrayList<AppPicker.App>(resolved.size)
        for (ri in resolved) {
            val info = ri.activityInfo?.applicationInfo ?: continue
            val pkg = info.packageName ?: continue
            if (pkg == selfPackage) continue
            if (!seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(packages).toString().trim() }
                .getOrNull()
                .orEmpty()
                .ifEmpty { pkg }
            labels[pkg] = label
            out.add(
                AppPicker.App(
                    packageName = pkg,
                    label = label,
                    system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                ),
            )
        }
        cached = out
        return out
    }

    /**
     * 单个包的应用名。**没装返回 null**，由界面去说「未安装」。
     *
     * 条目详情/表单里那份清单要靠它把 `com.tencent.mm` 画成「微信」。
     * 走的是 `getApplicationInfo` 而不是在 [load] 的结果里找——
     * 用户存的包名完全可能来自另一台手机、或者一个没有启动图标的应用，
     * 那些在名单里查不到，但 `getApplicationInfo` 查得到。
     */
    @Synchronized
    fun labelOf(packageName: String): String? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return null
        if (labels.containsKey(pkg)) return labels[pkg]
        val label = runCatching {
            packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString().trim()
        }.getOrNull()?.ifEmpty { null }
        labels[pkg] = label
        return label
    }

    /**
     * 应用图标，栅格化成给定边长的位图。没装或者取不到返回 null。
     *
     * 按尺寸栅格化一次就缓存：自适应图标每次绘制都要合成前后两层，
     * 在一个能滚动的名单里逐帧做这件事会掉帧。
     * 尺寸参数固定由调用方给同一个值，所以缓存不按尺寸分键。
     */
    @Synchronized
    fun iconOf(packageName: String, sizePx: Int): ImageBitmap? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return null
        if (icons.containsKey(pkg)) return icons[pkg]
        val bmp = runCatching {
            packages.getApplicationIcon(pkg).toBitmap(sizePx, sizePx).asImageBitmap()
        }.getOrNull()
        icons[pkg] = bmp
        return bmp
    }
}
