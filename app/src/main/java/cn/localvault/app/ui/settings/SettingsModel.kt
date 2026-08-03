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

package cn.localvault.app.ui.settings

import cn.localvault.app.ui.util.Fmt

/**
 * 设置页的内核：两张档位表、什么时候该出说明、备份行那句副标题、关于页的事实清单。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose。**
 * 和 `VaultIndex` / `EntryForm` / `PasswordGen` / `AddFlow` 是同一个套路：
 * 凡是「文案会不会撒谎」「顺序对不对」这类能被断言的东西，都从页面里搬出来。
 * 设置页尤其需要这一层——它上面的每一行都在向用户**声明这个 App 的行为**，
 * 而声明一旦和实现对不上，就不是界面 bug 了，是这个产品在骗人。
 */
object SettingsModel {

    /* ══════════════════════ 自动锁定 ══════════════════════ */

    /**
     * 自动锁定的档位。**升序，`0` 排在最前面，它的意思是「立即」。**
     *
     * ── 为什么没有「永不」 ──
     *
     * 一个永不自动锁定的密码管理器，在手机被顺走的那一刻等于没有密码管理器：
     * 捡到的人划开最近任务就是一屏明文。而用户想要「永不」的真实动机
     * 几乎总是同一句——「老是要重新解锁太烦了」——那件事的正解是
     * 快捷解锁（指纹按一下就开），不是把门一直敞着。
     * 所以这里给到 5 分钟为止：够覆盖「切出去查条短信再回来」，
     * 又短到手机离手时不至于门户大开。
     *
     * ── 为什么最短是「立即」而不是「5 秒」 ──
     *
     * 「立即」这一档以前是走不通的：拉起文件选择器时 Activity 也会走 onStop，
     * 用户永远做不完一次导出（决策⑳）。可信中断把这条路修好了之后，
     * 这一档才第一次真正可用，所以它值得摆出来——而且要在说明里
     * 把「系统界面另有宽限」这件事讲清楚，否则没人敢选。
     */
    val AUTO_LOCK_STEPS: List<Int> = listOf(0, 15, 30, 60, 120, 300)

    /** 库里没写过时用这个。和 `VaultMeta.autoLockSeconds` 的默认值必须一致。 */
    const val DEFAULT_AUTO_LOCK = 60

    /**
     * 要画哪几个档位。
     *
     * [current] 不在表里时**把它插进去**，而不是四舍五入到最近的一档。
     * 这条看着是小事，其实是一条硬规矩：设置页是用来**显示**用户的库的，
     * 不是用来悄悄改写它的。库可能是从别的设备拷来的、可能是将来某个版本
     * 写下的（那时档位表也许不一样）、也可能是用户手改过的备份文件。
     * 四舍五入的后果是：他打开设置页看了一眼、什么都没点，
     * 回头发现自动锁定从 45 秒变成了 30 秒——而屏幕上没有任何地方交代是谁改的。
     * 这和「网址只丢不改写」（决策(56)）、「密码不做 trim」（决策(57)）是同一条。
     */
    fun autoLockOptions(current: Int): List<Int> {
        val n = normalize(current)
        return if (n in AUTO_LOCK_STEPS) AUTO_LOCK_STEPS else (AUTO_LOCK_STEPS + n).sorted()
    }

    /** 「立即 / 30 秒 / 1 分钟 / 1 分 30 秒」。复用 [Fmt]，不另写一份。 */
    fun autoLockLabel(seconds: Int): String = Fmt.autoLockLabel(normalize(seconds))

    /**
     * 当前这一档要不要配一句说明。**中间那几档一律返回 null。**
     *
     * 每一档都配一句话的设置页，读起来像一份免责声明，
     * 结果是用户学会跳过所有小字——等到真有一句要紧的（比如「立即」那档
     * 其实不影响导出），他也不会看了。这和「备份是最新的就什么都不显示」
     * （决策㉞）是同一条规矩：**只在有话要说的时候说话。**
     */
    fun autoLockNote(seconds: Int): String? = when {
        normalize(seconds) == 0 ->
            "切到后台立刻锁。拉起文件选择器、指纹弹窗这类由本应用发起的系统界面时" +
                "另有 3 分钟宽限，所以导出备份不会被打断。"
        normalize(seconds) >= LONG_AUTO_LOCK ->
            "这段时间内切回来不用重新解锁——反过来说，手机离手的这几分钟里，" +
                "任何拿到它的人按一下最近任务就能看到你的保险库。"
        else -> null
    }

    /** 超过这个数就算「有点长了」，要如实说明代价。 */
    const val LONG_AUTO_LOCK = 300

    /* ══════════════════════ 剪贴板 ══════════════════════ */

    /**
     * 剪贴板自动清除的档位。**`0` 排在最后，它的意思是「不清除」。**
     *
     * ── 同一个 0，在两张表里意思正好相反 ──
     *
     * 自动锁定的 0 是「立即锁」，是最安全的一头；
     * 剪贴板的 0 是「永不清」，是最不安全的一头。
     * 所以这两张表不能共用一套「0 = 关闭」的默认文案，档位的排序方向也相反——
     * 两张表都按「从最安全到最不安全」排，用户不用去想哪边是大哪边是小。
     * 这也是这两个值虽然长得一样（都是 Int 秒数）却**不共用一个组件**的原因。
     *
     * ── 为什么剪贴板允许关掉，自动锁定却不允许「永不」 ──
     *
     * 因为它们管的东西不是一个量级：剪贴板里那一份是用户**自己主动放进去的
     * 一条**，而且下一次复制就会被覆盖；自动锁定管的是整个库。
     * 而且确实有关掉的正当理由——有些应用的粘贴框反应慢、有些流程要粘到好几处，
     * 15 秒真的不够。硬不给这个开关，用户的替代方案是**把密码打在备忘录里**，
     * 那比留在剪贴板里危险得多。给开关，但把后果写清楚。
     */
    val CLIPBOARD_STEPS: List<Int> = listOf(15, 30, 60, 120, 0)

    /** 和 `VaultMeta.clipboardClearSeconds` 的默认值必须一致。 */
    const val DEFAULT_CLIPBOARD = 15

    /** 理由同 [autoLockOptions]：不在表里的值照实插进去，0 永远留在末尾。 */
    fun clipboardOptions(current: Int): List<Int> {
        val n = normalize(current)
        if (n == 0 || n in CLIPBOARD_STEPS) return CLIPBOARD_STEPS
        val timed = CLIPBOARD_STEPS.filter { it != 0 } + n
        return timed.sorted() + 0
    }

    /**
     * 「15 秒 / 2 分钟 / 不自动清除」。
     *
     * 0 这一档刻意不叫「关闭」或「永不」：这两个词都在描述**开关**，
     * 而用户要判断的是**东西会不会自己消失**。「不自动清除」是一句陈述句，
     * 它顺带还暗示了「你可以手动清除」——那个入口就在顶部封条底下那条上。
     */
    fun clipboardLabel(seconds: Int): String = when (val n = normalize(seconds)) {
        0 -> "不自动清除"
        else -> Fmt.autoLockLabel(n)
    }

    /**
     * 关掉自动清除时必须给出的说明。
     *
     * 这句话里最要紧的不是警告，是**最后那半句**：告诉用户手动清除在哪儿。
     * 只警告不给出路的提示，等于把责任推给用户之后就走开了。
     */
    fun clipboardNote(seconds: Int): String? =
        if (normalize(seconds) == 0) {
            "复制出去的内容会一直留在剪贴板里，直到你复制别的东西。" +
                "复制之后顶部会一直显示一条提醒，点上面的「立即清除」可以随时清掉。"
        } else null

    /* ══════════════════════ 备份行的副标题 ══════════════════════ */

    /**
     * 设置页「导出加密备份」那一行右下角的小字。
     *
     * [urgent] 为 true 时页面把它画成黄铜色。判定和列表页顶部那条提醒
     * （决策㉞）用的是同一套条件，但**表现方式不同**：列表页是一整行横幅，
     * 会打断浏览；这里只是一行副标题，用户已经自己走进设置页了，
     * 不需要再被拦一次。
     */
    data class BackupSummary(val text: String, val urgent: Boolean)

    fun backupSummary(
        lastBackupAt: Long,
        changedSince: Int,
        now: Long = System.currentTimeMillis(),
    ): BackupSummary = when {
        lastBackupAt <= 0L -> BackupSummary("从未备份过", urgent = true)
        changedSince > 0 -> BackupSummary("有 $changedSince 条改动还没进备份", urgent = true)
        // 都没问题时只报事实（「3 天前」），不写「已是最新」「很安全」这类夸奖。
        // 决策㉞ 的原话：拿一整行屏幕说废话，看多了会让要紧的那条也被略过。
        else -> BackupSummary("上次备份：${Fmt.relativeTime(lastBackupAt, now)}", urgent = false)
    }

    /* ══════════════════════ 关于页 ══════════════════════ */

    /**
     * 关于页上那种「左边名字、右边值」的一行。
     *
     * [mono] 默认为 true：这一页上的值几乎全是机器生成的东西
     * （版本号、算法参数、字节数），按全工程的规矩它们该是等宽的。
     */
    data class Fact(val label: String, val value: String, val mono: Boolean = true)

    /**
     * 权限清单。**这是整个关于页最要紧的一条，也是唯一一条有测试盯着的。**
     *
     * 「应用信息里的权限列表是空的」是欢迎页三条承诺里的第一条，
     * 也是这个产品对抗云端大厂的全部底气。哪天有人为了某个功能
     * 往 Manifest 里加一条权限，而忘了改这里，关于页就会当场变成一句谎话——
     * 而且是**最难被发现的那种**，因为界面看起来完全正常。
     *
     * 所以 `SettingsModelTest` 里有一条用例钉着：这个列表只能有一项，
     * 而且任何一项里都不许出现「网络」「INTERNET」「存储」这类字眼。
     * 它拦不住有人同时改两处，但它能保证那次修改是**故意的**。
     */
    val PERMISSIONS: List<String> = listOf(
        "USE_BIOMETRIC —— 只用于弹出指纹框。它不给应用读取指纹数据的能力。",
    )

    /**
     * 自动填充服务那一条的交代（M4-2a-2②）。
     *
     * ── 为什么它没有被加进 [PERMISSIONS] ──
     *
     * 原计划里写着「这是 M0 之后第一次给权限清单添东西」。写下那句话的时候
     * 搞错了一件事：`BIND_AUTOFILL_SERVICE` 是写在 `<service>` 标签的
     * `android:permission` 上的，它的意思是**「谁想绑定这个服务，必须持有它」**——
     * 而持有它的只有 `system_server` 一个。它是一道**锁**，不是一项**能力**：
     * 应用没有因此多要到任何东西，「设置 → 应用 → 权限」里也不会多出一行。
     *
     * 所以把它写进 [PERMISSIONS] 才是那句谎话：用户照着那份清单去系统里核对，
     * 会发现对不上，而这一页的全部价值就在于**每一条都能被自己核实**。
     *
     * 但也不能就此不提。用户在系统设置里把这个应用设为默认填充服务时，
     * 会看到一屏相当吓人的话（「它将能够看到你屏幕上的内容」）——
     * 那是系统对所有填充服务说的同一句话，而它对这一个的适用范围值得单独讲清楚。
     * 所以有了这一段：**不进权限清单，但要出现在权限清单旁边。**
     */
    val AUTOFILL_NOTE: List<String> = listOf(
        "自动填充要在系统设置里手动设为默认服务，不设就一直不出现。",
        "设为默认之后，只有你点了输入框、系统主动来问的那一刻，" +
            "这个应用才看得到那一屏上有哪些输入框；它读的是框的类型和网站，" +
            "读不到你在框里打的字。",
        "填充条上永远不显示密码，只显示条目名称和账号——" +
            "那块浮层是系统画的，输入法和录屏都看得见。",
        "库锁着的时候只会出现一条「先解锁」，连这个网站存了几条都数不出来。",
    )

    /**
     * 依赖清单。写出来是因为「依赖少」是这个 App 的卖点之一，
     * 而卖点必须可核实——用户拿这份清单去对 APK 里的 classes.dex 是能对上的。
     */
    val DEPENDENCIES: List<String> = listOf(
        "AndroidX Compose / Navigation —— 界面",
        "AndroidX Biometric —— 指纹框",
        "kotlinx.serialization —— 库内容的序列化",
        "argon2kt —— Argon2id 的原生实现（唯一的原生依赖，拉不到时自动降级到 PBKDF2）",
    )

    /**
     * 「没有的东西」清单。
     *
     * 关于页通常写的是「我们有什么」，这一页反过来写「我们没有什么」——
     * 因为对这个产品来说，**没有的那些东西才是它的功能**。
     * 而且这几条全都能被用户自己核实：权限列表看一眼、断网用一天、
     * 卸载前后翻一遍文件管理器。写不能核实的话（「军工级加密」「绝对安全」）
     * 一句都不写，那种话只会拉低前面几条的可信度。
     */
    val ABSENCES: List<String> = listOf(
        "没有网络权限，也就没有云同步、没有账号、没有「找回密码」",
        "没有广告、没有统计埋点、没有崩溃上报",
        "没有内购、没有会员、没有条目数量上限",
        "没有任何一份数据离开过这台设备",
    )

    /**
     * 关于页上半部分那几行事实。
     *
     * 全部由调用方传进来，这个函数自己不去读任何东西——
     * 于是「降级到 PBKDF2 时这一页会不会照实写」变成一条能在纯 JVM 上断言的性质，
     * 而不是「得找一台拉不到原生库的设备才能验」。
     *
     * 这里**不显示库文件的绝对路径**。那个路径在应用私有目录里，
     * 没有 root 的用户按图索骥也打不开，印出来只会让人去找一个找不到的东西。
     * 用户真正拿得到、也真正需要知道位置的那一份是**导出的备份文件**，
     * 而它在哪儿是用户自己在系统文件选择器里挑的。
     */
    fun aboutFacts(
        versionName: String,
        kdfLabel: String,
        cipherLabel: String,
        argon2Available: Boolean,
        entryCount: Int,
        vaultBytes: Long,
        createdAt: Long,
    ): List<Fact> = listOf(
        Fact("版本", versionName),
        Fact(
            "密钥派生",
            // 降级时必须写出来，且写在**值**里而不是某个角落的小字里。
            // 封条的第 1 条规矩（Seal.kt）在这一页的兑现。
            if (argon2Available) kdfLabel else "$kdfLabel（原生库不可用，已降级）",
        ),
        Fact("内容加密", cipherLabel),
        Fact("保险库", "$entryCount 条 · ${Fmt.bytes(vaultBytes)}"),
        Fact("建库时间", if (createdAt > 0L) Fmt.date(createdAt) else "未知"),
    )

    /* ══════════════════════ 私有 ══════════════════════ */

    /**
     * 负数一律当 0。
     *
     * 库文件是用户能拿到手的（决策⑤：整个库就是一个文件），
     * 所以里面躺着一个 -1 并非不可能。两处的语义都是「0 和负数没有区别」，
     * 归一之后才不会出现「-1 秒」这种画在屏幕上的东西。
     * 注意归一只作用于**显示和比较**，不回写库——见 [autoLockOptions] 的注释。
     */
    private fun normalize(seconds: Int): Int = seconds.coerceAtLeast(0)
}
