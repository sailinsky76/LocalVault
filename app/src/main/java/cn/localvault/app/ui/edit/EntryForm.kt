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

package cn.localvault.app.ui.edit

import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.list.VaultIndex

/**
 * 条目表单背后那些「说不清就会分叉」的规则。
 *
 * **整个文件没有一行 `android.*`，也没有一行 Compose**，
 * 和 [cn.localvault.app.ui.list.VaultIndex]、[cn.localvault.app.ui.detail.EntryDetail]、
 * [cn.localvault.app.ui.unlock.UnlockGuard] 是同一个套路。
 *
 * ── 为什么表单要切成三层 ──
 *
 * 这套字段 M3-5 新增流的最后一步要原样复用。如果编辑页和新增页各写一份，
 * 「网址怎么切行、怎么去重」「名称要不要 trim」「密码能不能 trim」
 * 这几条规则马上就会分叉，而分叉的表现是**同一个库里两种数据**——
 * 从新增流进来的条目网址带着 `https://`，从编辑页改过的不带，
 * 将来 M4 自动填充按哪一份匹配都对不齐。
 *
 * 所以规则全部落在这个对象里（可单测），字段块是一个可复用的 Composable
 * （[EntryFormFields]），页面只负责导航、保存和「改了没存」那道拦截
 * （[EditEntryScreen]）。
 */
object EntryForm {

    /**
     * 正在编辑的草稿。
     *
     * 它和 [VaultEntry] 不是一回事，也不该是：
     * 草稿里的网址是**一整块多行文本**（用户正在打字，中间完全可能有空行），
     * 而 [VaultEntry.domains] 是一个已经清理好的列表。
     * 中间这层转换正是最容易两边写得不一样的地方，所以它有名字、有测试。
     *
     * 草稿里**没有** `favorite`、`totpSecret` 和三个时间戳：
     * 那些字段不归表单管，保存时由 [applyTo] 从原条目上原样带过去。
     * 少写一个字段在这里就等于「保存一次把用户的收藏弄丢了」。
     */
    data class Draft(
        val name: String = "",
        val username: String = "",
        val password: String = "",
        /** 一行一个网址 / 安卓包名。保存时才切开、清理、去重。 */
        val domainsText: String = "",
        val category: String = "",
        val notes: String = "",
    )

    fun draftOf(entry: VaultEntry): Draft = Draft(
        name = entry.name,
        username = entry.username,
        password = entry.password,
        domainsText = entry.domains.joinToString("\n"),
        category = entry.category,
        notes = entry.notes,
    )

    /** 新增流用：可以带一个名称初值（搜索没搜到时从 `DraftHandoff` 取来的那几个字）。 */
    fun blank(name: String = ""): Draft = Draft(name = name.trim())

    /* ══════════════════════════ 网址的切行与去重 ══════════════════════════ */

    /**
     * 分隔符：换行、逗号、分号、任何空白。
     *
     * 敢按空白切，是因为**合法的网址和安卓包名里不可能出现空白字符**，
     * 所以一行里出现空格只有一种解释——用户从别处粘了一串过来。
     * 逗号和分号同理：那是从表格或者浏览器书签导出里粘出来的形状。
     */
    private val SEPARATORS = Regex("[\\s,;，；]+")

    /**
     * 把多行文本切成干净的网址列表。
     *
     * ── 只丢，不改写 ──
     *
     * 空白和空行会被丢掉，重复的会被丢掉，**但留下来的那些一个字符都不动**：
     * 用户打的是 `https://mail.example.com/inbox`，存进去的就是这一串。
     *
     * 很容易顺手在这里调一次 [VaultIndex.normalizeDomain] 把它收敛成
     * `mail.example.com`，看起来更整齐。但那是**悄悄改写用户输入**：
     * 他保存完回到详情页，看到的东西和他刚才打的不一样，
     * 而屏幕上没有任何地方解释是谁改的。归一属于匹配环节（M4 自动填充），
     * 不属于存储环节——决策㉝画的就是这条界限。
     *
     * ── 但去重按归一后的形式做 ──
     *
     * 去重是唯一用得着 [VaultIndex.normalizeDomain] 的地方：
     * `example.com` 和 `https://example.com/login` 指的是同一个主机，
     * 留两份只会让详情页多一行废话。这里**复用**那个函数而不是另写一个，
     * 是决策㉝那句「不许各写各的」的字面兑现。
     * 同一组里保留**第一次出现的那个写法**，因为那多半是用户自己打的，
     * 后面的往往是粘贴带进来的长串。
     */
    fun domainLines(text: String): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (piece in text.split(SEPARATORS)) {
            val s = piece.trim()
            if (s.isEmpty()) continue
            // 归一后是空的（比如用户只打了一个 "https://"）说明这一段根本不成其为地址
            val key = VaultIndex.normalizeDomain(s)
            if (key.isEmpty()) continue
            if (!seen.add(key)) continue
            out.add(s)
        }
        return out
    }

    /* ══════════════════════════ 修剪 ══════════════════════════ */

    /**
     * 保存前的修剪。
     *
     * ── 密码**不 trim**，其它字段 trim ──
     *
     * 空格完全可以是密码的一部分。替用户把首尾空格去掉，结果是他下次登录不上，
     * 而他永远猜不到是谁改的——他能看到的只有一串圆点，
     * 圆点和圆点之间没有任何地方写着「这里少了一个空格」。
     * 这是这个文件里最不该图省事的一行。
     *
     * 其它字段则必须 trim：账号、网址、分类几乎都是粘贴进来的，
     * 而粘贴几乎必然带走一个尾随空格或换行。一个开头带空格的名称
     * 还会在列表里排到所有条目的最前面（[VaultIndex] 用 Collator 排序），
     * 用户完全看不出为什么。
     *
     * 备注 trim 掉首尾空白但**保留中间的换行**——它本来就是多行文本。
     */
    fun cleaned(draft: Draft): Draft = Draft(
        name = draft.name.trim(),
        username = draft.username.trim(),
        password = draft.password,
        domainsText = domainLines(draft.domainsText).joinToString("\n"),
        category = draft.category.trim(),
        notes = draft.notes.trim(),
    )

    /* ══════════════════════════ 能不能存 ══════════════════════════ */

    /**
     * **名称是唯一的必填项。**
     *
     * 密码可以为空：确实有人拿它当通讯录用，只记账号不记密码
     * （详情页的 [cn.localvault.app.ui.detail.EntryDetail.rows] 早就支持空密码不占位了）。
     * 网址可以为空，分类可以为空，备注当然可以为空。
     *
     * 名称不行，因为列表和搜索都靠它认人：名称是列表行的主位，
     * 也是搜索白名单（决策㉜）里唯一命中了不用标字段名的那个。
     * 一条没有名称的条目在列表上就是一行空白，用户点进去才知道是什么。
     */
    fun nameOk(draft: Draft): Boolean = draft.name.isNotBlank()

    fun canSave(draft: Draft): Boolean = nameOk(draft)

    /* ══════════════════════════ 改了没有 ══════════════════════════ */

    /**
     * 比的是**修剪之后**的两份草稿。
     *
     * 直接比原始文本的话，用户在账号末尾误敲一个空格再删掉，
     * 中间那一下就会让「有未保存的改动」亮起来；更糟的是在网址框里
     * 多按一个回车——那一个空行会被 [cleaned] 丢掉，
     * 存进去和不存进去一模一样，却足以让返回时弹出一道拦截。
     *
     * 拦截必须只在**真的会丢东西**的时候出现，否则用户学会的是闭着眼睛点「放弃」。
     */
    fun isDirty(original: Draft, current: Draft): Boolean = cleaned(original) != cleaned(current)

    enum class Field { Name, Username, Password, Domains, Category, Notes }

    fun label(field: Field): String = when (field) {
        Field.Name -> "名称"
        Field.Username -> "账号"
        Field.Password -> "密码"
        Field.Domains -> "网址 / 应用"
        Field.Category -> "分类"
        Field.Notes -> "备注"
    }

    fun changedFields(original: Draft, current: Draft): List<Field> {
        val a = cleaned(original)
        val b = cleaned(current)
        return buildList {
            if (a.name != b.name) add(Field.Name)
            if (a.username != b.username) add(Field.Username)
            if (a.password != b.password) add(Field.Password)
            if (a.domainsText != b.domainsText) add(Field.Domains)
            if (a.category != b.category) add(Field.Category)
            if (a.notes != b.notes) add(Field.Notes)
        }
    }

    /**
     * 「放弃修改？」那个弹窗里的一行小字：**改了哪几个字段，但不说改成了什么**。
     *
     * 这个函数存在的理由和 [cn.localvault.app.ui.detail.EntryDetail.deleteConfirmDetail]
     * 完全一样：弹窗是一个独立的 window（决策⑭）。
     * 「确定放弃吗？密码将从 abc123 改回 xyz789」这种话写起来非常自然，
     * 而它会把**两个**密码同时摆到一个独立 window 上。
     * 所以这里只返回字段名，返回值里永远不会出现任何一个字段的内容——由单测盯着。
     */
    fun changedSummary(original: Draft, current: Draft): String =
        changedFields(original, current).joinToString(" · ") { label(it) }

    /* ══════════════════════════ 写回条目 ══════════════════════════ */

    /**
     * 把草稿盖回原条目。
     *
     * 用 `entry.copy(...)` 而不是重新 new 一个 [VaultEntry]：
     * `favorite`、`totpSecret`、`createdAt` 这些不归表单管的字段原样留着。
     * 三个时间戳由 [cn.localvault.app.core.session.VaultSession.updateEntry] 统一刷新——
     * 尤其是 `passwordUpdatedAt`，它必须由「密码到底变没变」决定，
     * 而那个判断在会话层拿得到旧值，在这里拿不到。
     */
    fun applyTo(entry: VaultEntry, draft: Draft): VaultEntry {
        val c = cleaned(draft)
        return entry.copy(
            name = c.name,
            username = c.username,
            password = c.password,
            domains = domainLines(c.domainsText),
            category = c.category,
            notes = c.notes,
        )
    }

    /**
     * 新增流用：从草稿造一条全新的条目。
     *
     * id 留空、时间戳留 0，交给 [cn.localvault.app.core.session.VaultSession.addEntry]
     * 去补 UUID 和时间——id 的生成必须只有一个地方，
     * 否则「新增流生成一个、导入流生成一个」迟早会撞。
     */
    fun newEntry(draft: Draft): VaultEntry = applyTo(VaultEntry(id = "", name = ""), draft)
}
