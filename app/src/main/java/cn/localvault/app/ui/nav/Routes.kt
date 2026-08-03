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

/**
 * 路由表。
 *
 * 用字符串常量而不是 type-safe navigation（`@Serializable` 路由对象）：
 * 后者要把参数序列化进 back stack，而 back stack 会被系统保存到
 * savedInstanceState 里。对一个密码管理器来说，**任何进入 Bundle 的东西
 * 都要当成会落盘的**。条目 id 是随机 UUID，本身不敏感，
 * 但把这条界限画在「只允许 id 进路由，其余一律从内存里的会话取」这个位置上，
 * 后面加页面时就不会有人不小心把密码塞进参数。
 */
object Route {

    // ── 首次引导（无库时的独立图）──
    const val WELCOME = "welcome"

    /**
     * 设置主密码。**输入和确认在同一屏**，所以没有独立的 CREATE_CONFIRM 路由。
     * 原因见 [cn.localvault.app.ui.onboarding.CreateMasterScreen] 的注释：
     * 分两页就必须让主密码活过一次页面切换，那等于多开一个副本。
     */
    const val CREATE_PASSWORD = "create_password"

    /**
     * 从 .lvault 备份文件恢复。**只注册到引导图上**（对比 [SETTINGS_DELETE]
     * 只在已解锁那张图上），因为它的前提就是「这台设备上还没有库」——
     * 恢复绝不覆盖已有的库（决策(135)）。
     *
     * 它不带任何参数。选中的那份备份是**一整个加密库**，
     * 它只活在 `RestoreController` 的内存里，一次都不进路由——
     * 见这个文件顶上那条界限，这次漏出去的话漏的是全部数据。
     * 主密码同理（活在 `SecureTextState` 的 `Editable` 里）。
     */
    const val RESTORE = "restore"

    /**
     * 首次备份。**它属于主图，不属于引导图。**
     *
     * 一开始把它放在引导图里，意味着「必须备份」这条规矩只在建库那一次会话里成立：
     * 用户建完库把 App 划掉，下次解锁进来就再也不会被要求备份了。
     * 改成由主图按 `meta.lastBackupAt == 0L` 判断，这条规矩才是持续的——
     * 没备份过就一直挡在前面，跟这次是不是刚建库无关。
     */
    const val FIRST_BACKUP = "first_backup"

    // ── 解锁（有库但锁着）──

    /**
     * 快捷解锁：PIN 键盘 + 指纹。只有绑定过才可达。
     *
     * 它和 [UNLOCK_MASTER] 是两张页面而不是一张带切换的页面，
     * 因为两者的输入部件完全不同（数字键盘 vs 全键盘 + EditText 互操作），
     * 挤在一屏里会让主密码那个框在 PIN 模式下依然活着、依然持有缓冲区。
     */
    const val UNLOCK = "unlock"

    /**
     * 主密码解锁。**它是这张图永远的兜底**：
     * 快捷解锁可能没绑、可能因连错被关掉、可能因为换了指纹而失效，
     * 而主密码在任何情况下都能开门。所以没绑定快捷解锁时它就是起始点。
     */
    const val UNLOCK_MASTER = "unlock_master"

    /**
     * 清空重来。**只注册到解锁那张图上**，和 [SETTINGS_DELETE] 正好互补。
     *
     * ── 为什么它不能出现在已解锁那张图上 ──
     *
     * 因为它没有身份证明这道门（它面对的就是说不出主密码的人，
     * 门槛是抄写一句话 + 按住三秒，见 `ResetVaultModel`）。
     * 一个已经解锁的用户手边有 [SETTINGS_DELETE]，那一页认主密码（决策(119)），
     * 是更强的一道门；把这一页也摆到设置里，等于在一个已解锁的界面上
     * 同时提供强弱两个入口，那道强的就白设了——谁都会走顺手的那个。
     *
     * ── 它也不带任何参数 ──
     *
     * 全页唯一的输入是抄写框里那句「我没有主密码了」，它不是凭据、
     * 不是库内容的投影，但也没有任何理由进 back stack。见文件顶上那条界限。
     */
    const val RESET = "reset"

    // ── 主图（已解锁）──
    const val LIST = "list"
    const val SEARCH = "search"
    /**
     * **密码生成器刻意没有路由。**
     *
     * 它必须把生成出来的密码交回调用它的那一页，而页面之间回传值的正规通道是
     * `savedStateHandle`——那是一个 Bundle，会被系统写进 `savedInstanceState`，
     * 等于把一个刚生成的密码明文落盘。这正是这个文件顶上那条界限
     * （只允许条目 id 进路由）要堵的洞，只不过这次漏出去的是密码本身。
     *
     * 所以生成器是 [cn.localvault.app.ui.generate.GeneratorSheet]：
     * 画在调用它那一页的同一棵 composition 里，结果通过普通的 Kotlin 回调交回去，
     * 全程不经过导航。顺带还解决了另一件事——它不是独立 window，
     * 于是自动继承 Activity 的 `FLAG_SECURE`（对比决策⑭）。
     */
    const val SETTINGS = "settings"

    /**
     * 快捷解锁的绑定页（开启 / 关闭指纹与 PIN）。
     *
     * M3-6b-1 把它接上了：设置主页「安全」分区里那一行「快捷解锁」跳到这儿，
     * 页面上是指纹的开关。M3-6b-2 又在它下面补上了 PIN 的开关与
     * 「修改 PIN」入口，跳向 [SETTINGS_PIN]。
     *
     * 它只在已解锁相位可达：绑定要借库主密钥（`VaultSession.withVaultKey`），
     * 锁着的时候根本没有东西可绑。
     */
    const val SETTINGS_SECURITY = "settings/security"

    /**
     * PIN 的设置 / 修改流。同样只在已解锁相位可达（要借库主密钥）。
     *
     * ── 这条路由为什么可以带一个参数 ──
     *
     * 这个文件顶上那条界限是「只允许 id 进路由，其余一律从内存里的会话取」，
     * 因为路由参数会随 back stack 落进 `savedInstanceState`。
     * `change` 只说明**用户是从哪个入口点进来的**（设置 / 修改），
     * 它不是库内容的投影，也推不出库里的任何东西，落盘无害。
     *
     * 真正不能进路由的是那六位数字——它从头到尾只活在
     * `PinBuffer` 的 CharArray 里，连一次 String 都不产生，
     * 更不会经过导航（同生成器不给路由的理由）。
     */
    private const val SETTINGS_PIN_BASE = "settings/security/pin"
    const val SETTINGS_PIN = "$SETTINGS_PIN_BASE/{change}"
    fun settingsPin(change: Boolean) = "$SETTINGS_PIN_BASE/$change"
    const val ARG_CHANGE = "change"
    /**
     * 修改主密码。**只在已解锁相位可达**，理由和上面两条一样：
     * 重新包裹要借库主密钥（`VaultSession.withVaultKey`），锁着的时候没有东西可包。
     *
     * 它不带任何参数——三个口令从头到尾只活在
     * [cn.localvault.app.ui.components.SecureTextState] 的 `Editable` 里，
     * 一次都不经过导航（同生成器不给路由的理由）。
     */
    const val SETTINGS_MASTER = "settings/master"

    /**
     * 删除保险库。**只在已解锁相位可达。**
     *
     * 这条限制的理由和上面几条不一样：那些是「锁着的时候没有库主密钥可借」，
     * 技术上做不了；这一条技术上完全做得了（删文件不需要任何密钥），
     * 是**刻意**不给的。
     *
     * 因为删除的确认门槛是主密码（见 `DeleteVaultModel.canSubmit` 的说明），
     * 而挂在解锁页上的那个入口面对的恰恰是「说不出主密码的人」——
     * 它会立刻退化成一个人人可按的「清空这台手机上的保险库」按钮，
     * 也就是决策⑦ 明令不做的那个拒绝服务漏洞，只不过这次是手动版的。
     *
     * 「我忘了主密码，想重来」那条路走的是 [RESET]，不是这一页。
     * 它需要另一套确认方式（抄写 + 按住三秒），和这一页共用不了。
     */
    const val SETTINGS_DELETE = "settings/delete"
    const val SETTINGS_BACKUP = "settings/backup"

    /**
     * 从 CSV 导入。**只挂在已解锁那张图上**，和 [RESTORE] 正好反过来。
     *
     * 恢复的前提是「这台设备上还没有库」（决策(135)）；导入的前提恰恰相反——
     * 它是把别人的条目**加进一个已经存在的库**，没有库就没有地方加，
     * 而且判重要拿库里现有的条目去比。
     *
     * 它不带任何参数。选中的那份 CSV 是一整张**明文密码表**，
     * 只活在 `ImportController` 的内存里，一次都不进路由——
     * 见这个文件顶上那条界限。它比备份文件还敏感：备份是加密的，这张表不是。
     */
    const val SETTINGS_IMPORT = "settings/import"

    /**
     * 自动填充的开关与交代（M4-4a）。**只挂在已解锁那张图上。**
     *
     * 这条限制和上面几条的理由都不一样：那些是「锁着的时候没有库主密钥可借」，
     * 技术上做不了；这一页技术上完全做得了——它从头到尾只问了两件事
     * （系统里现在设的是谁、要不要跳出去），一次都不碰库。
     *
     * 挂在这儿纯粹是因为它的入口在设置主页上，而设置主页只在解锁相位可达。
     * 为它单开一条锁着也能进的路，多出来的是一个要维护的入口和一张
     * 需要单独处理相位的页，换来的是「锁着的时候也能改这一项」——
     * 那件事没有任何人需要：一个还没解过锁的人，此刻要的是开门，不是配置填充。
     *
     * 它不带任何参数。这一页上没有任何东西来自库。
     */
    const val SETTINGS_AUTOFILL = "settings/autofill"
    const val SETTINGS_ABOUT = "settings/about"

    /** 条目详情。只带 id，其余从会话内存里取。 */
    private const val DETAIL_BASE = "entry"
    const val DETAIL = "$DETAIL_BASE/{id}"
    fun detail(id: String) = "$DETAIL_BASE/$id"
    const val ARG_ID = "id"

    /** 编辑已有条目。新增走独立的 3 步流 [ADD]，两者的交互差别足够大，不合并。 */
    private const val EDIT_BASE = "edit"
    const val EDIT = "$EDIT_BASE/{id}"
    fun edit(id: String) = "$EDIT_BASE/$id"

    /** 新增 3 步流 */
    const val ADD = "add"
}
