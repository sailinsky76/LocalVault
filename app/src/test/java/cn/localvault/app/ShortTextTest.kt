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

import cn.localvault.app.ui.restore.RestoreModel
import cn.localvault.app.ui.settings.AutofillSettingsModel
import cn.localvault.app.ui.settings.AutofillSettingsModel.Availability
import cn.localvault.app.ui.settings.ChangeMasterModel
import cn.localvault.app.ui.settings.DeleteVaultModel
import cn.localvault.app.ui.settings.QuickUnlockModel
import cn.localvault.app.ui.settings.QuickUnlockModel.BiometricSupport
import cn.localvault.app.ui.unlock.ResetVaultModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长短两版文案的关系。
 *
 * ── 这一组用例守的是什么 ──
 *
 * v3 那次排版修订（见 `components/Explain.kt` 文件头）把页面上的解释性文字
 * 拆成了两份：一份短的跟着控件走，一份完整的收进「详细说明」弹窗。
 * 这个做法有一个**很安静的失败方式**——
 *
 * 拆的时候两份都对，然后某一次改文案时只改了其中一份。
 * 屏幕上看不出任何异常：短的那句还在，弹窗还是能打开，
 * 只是两处说的已经不是同一件事了。而这个工程里那些文案，
 * 说错一句的后果是用户把一次**克制**当成了一个 bug
 * （`AutofillSettingsModel` 文件头那整段讲的就是这件事）。
 *
 * 所以这里盯三样：
 *
 *  1. **短的确实短。** 「最多两三行」是那条规矩的全部内容，
 *     写进用例才不会在下一次「就多加半句」里被磨掉；
 *  2. **短的没把要紧的意思弄丢。** 每一句短版都对应一条
 *     「这个关键词删掉，用户就会做错事」的断言——
 *     删掉的不是文采，是他做判断要用的那个事实；
 *  3. **一行入口的副标题写的是里面有什么**，不是「点击查看详情」。
 *     后者是废话：箭头已经在那儿了。
 *
 * ── 为什么长度上限是 45 ──
 *
 * 正文类样式是 14–15sp（`VaultType.Sub` / `Body`），常见机型上一行约 20 字，
 * 45 字落在两行多一点。再往上就要挤到第三行，而第三行正是
 * 「按钮开始往下走」的那一行——恢复页当初被顶出屏幕就是这么一段段攒出来的。
 */
class ShortTextTest {

    /** 和控件平铺在一起那种说明的字数上限。理由见类注释。 */
    private val INLINE_MAX = 45

    /** 一行入口（`ExplainRow`）副标题的字数上限。它必须在一行里显示得完。 */
    private val SUBTITLE_MAX = 30

    /**
     * 短版里不许出现的字。
     *
     * 这些词的共同点是**指着界面自己说话**：用户看得见那个箭头、那个链接，
     * 再写一遍等于用宝贵的两行里的一行说了句废话。
     */
    private val UI_FILLER = listOf("点击查看", "点此", "查看详情", "了解更多", "更多信息")

    /**
     * 短版里不许出现的**承诺**。
     *
     * v4 把原来那条光秃秃的「找回」收紧成了三个具体说法。理由：
     * 清空重来页最要紧的一句就是「主密码**没有找回通道**」——
     * 一条禁掉「找回」二字的规矩，会正好禁掉这个产品最该说出口的那句实话。
     * 要防的从来不是这两个字，是那种「联系我们帮你找回」的暗示
     * （同 `ResetVaultModelTest` 里那条，它禁的也是「找回主密码」而不是「找回」）。
     */
    private val RESCUE_PROMISE =
        listOf("找回主密码", "可以找回", "帮你找回", "破解", "客服", "军工级", "绝对安全")

    /* ══════════════════════ 自动填充设置页 ══════════════════════ */

    @Test
    fun `开关旁边那句短版，四档该有的有、该没有的没有`() {
        // 有话说的三档必须两份都在：短的挂在开关下面，长的进弹窗。
        listOf(Availability.Unsupported, Availability.OtherService, Availability.Ours).forEach {
            val row = AutofillSettingsModel.row(it)
            assertNotNull(it.name, row.note)
            assertNotNull("有 note 就必须有短版，否则弹窗里那段没有入口：${it.name}", row.noteShort)
        }
        // 「还没设为默认」那一档本来就没话说（点一下，系统问一句，就完了），
        // 短版也必须是空的 —— 为了对称硬凑一句，是在这一格里凭空加一行字。
        assertNull(AutofillSettingsModel.row(Availability.NoService).note)
        assertNull(AutofillSettingsModel.row(Availability.NoService).noteShort)
    }

    @Test
    fun `短版必须比长版短，而且短得下得了两三行`() {
        Availability.values().forEach { a ->
            val row = AutofillSettingsModel.row(a)
            val short = row.noteShort ?: return@forEach
            assertTrue(
                "${a.name} 的短版有 ${short.length} 字，超过 $INLINE_MAX 就要挤到第三行去",
                short.length <= INLINE_MAX,
            )
            val full = row.note
            if (full != null) {
                assertTrue("${a.name} 的短版不比长版短，那这两份就没有理由存在", short.length < full.length)
            }
        }
    }

    @Test
    fun `换掉别的填充服务那一档，短版不许把后果和误会弄丢`() {
        // 这是全页最要紧的一句。用户点这个开关时想的是「多开一个」，
        // 不是「把我正在用的那个顶掉」—— 短版删掉「只认一个」，他就真的换了，
        // 然后哪天发现存在那一个里的密码不出来了，完全想不起来是这一下造成的。
        val short = AutofillSettingsModel.row(Availability.OtherService).noteShort!!
        assertTrue("要写清系统同时只认一个", short.contains("只认一个"))
        // 「换掉服务」和「删掉数据」在用户脑子里离得很近。不写这句会拦住一批本该敢试的人。
        assertTrue("要写清不会动那个应用里的数据", short.contains("不会动"))
    }

    @Test
    fun `已经是默认那一档，短版必须说清这一下关不掉什么`() {
        // 开关能点，但点下去是跳到系统设置，不是当场关掉（见 Row 上那段）。
        // 一个拨过去自己会弹回来的开关，比一个灰着的开关更让人生气。
        val short = AutofillSettingsModel.row(Availability.Ours).noteShort!!
        assertTrue(short.contains("撤下来") || short.contains("关不掉"))
        assertTrue("得指明这一下的去处是系统那张列表", short.contains("系统"))
    }

    @Test
    fun `页顶那句的短版仍然只讲用户看得到的现象`() {
        val short = AutofillSettingsModel.INTRO_SHORT
        assertTrue(short.length <= INLINE_MAX)
        assertTrue("现象的落点是那个框，删了就没有可对上的东西了", short.contains("密码框"))
        // 同长版那条规矩：别把我们这边的事写给用户看
        listOf("AssistStructure", "FillResponse", "解析", "Dataset").forEach {
            assertFalse("短版也不许出现：$it", short.contains(it))
        }
    }

    @Test
    fun `请勿填充那一项，两档的短版各说各的、各自保住自己那条代价`() {
        val on = AutofillSettingsModel.optOutRow(respected = true)
        val off = AutofillSettingsModel.optOutRow(respected = false)

        assertNotEquals(on.noteShort, off.noteShort)
        listOf(on, off).forEach {
            assertTrue(it.noteShort.isNotBlank())
            assertTrue("短版 ${it.noteShort.length} 字，太长", it.noteShort.length <= INLINE_MAX)
            assertTrue("短版不比长版短就没必要拆", it.noteShort.length < it.note.length)
        }
        // 开着 —— 一批应用会彻底填不了，而那个现象和「这个功能坏了」长得一模一样。
        // 短版丢了这个词，用户过两周撞上症状时就没有任何线索指回这个开关。
        assertTrue(on.noteShort.contains("填不了"))
        // 关着 —— 我们在做应用作者明确表示不希望的事，用户有权在这一行上就知道。
        assertTrue(off.noteShort.contains("不听"))
    }

    @Test
    fun `两块收起来的解释，副标题写的是里面有什么`() {
        val subtitles = listOf(
            AutofillSettingsModel.LIMITS_SUMMARY,
            AutofillSettingsModel.WHY_SUMMARY,
            RestoreModel.AFTER_SUMMARY,
        )
        subtitles.forEach { s ->
            assertTrue(s.isNotBlank())
            assertTrue("副标题 ${s.length} 字，一行放不下", s.length <= SUBTITLE_MAX)
            UI_FILLER.forEach { filler ->
                assertFalse("箭头已经在那儿了，别再写一遍「$filler」：$s", s.contains(filler))
            }
        }
    }

    @Test
    fun `症状清单那一行的条数跟着清单走`() {
        // 手写死一个数字，加一条症状时没人会记得回来改，
        // 于是那一行会开始撒一个很小但很难发现的谎。
        val n = AutofillSettingsModel.WHY_NOT_SHOWING.size
        assertTrue(AutofillSettingsModel.WHY_SUMMARY.contains(n.toString()))
    }

    @Test
    fun `收起来不等于删掉 —— 那两块的完整内容一条都还在`() {
        // 这一组用例真正在守的东西：排版改了，信息量不许变。
        assertTrue(AutofillSettingsModel.LIMITS.size >= 3)
        assertTrue(AutofillSettingsModel.WHY_NOT_SHOWING.size >= 7)
        assertTrue(AutofillSettingsModel.WHY_TAIL.isNotBlank())
        assertEquals(4, RestoreModel.WHAT_HAPPENS.size)
    }

    /* ══════════════════════ 从备份恢复页 ══════════════════════ */

    @Test
    fun `恢复页页顶那句的短版，三件事一件不少`() {
        val short = RestoreModel.INTRO_SHORT
        assertTrue("短版 ${short.length} 字", short.length <= INLINE_MAX)
        // 选什么
        assertTrue(short.contains("lvault"))
        // 用什么打开 —— 这一页最容易被卡住的地方，一开始就要点明
        assertTrue(short.contains("主密码"))
        // 不联网。这是这个产品的立身之本，页顶那一句里不该省掉。
        assertTrue(short.contains("不联网"))
    }

    @Test
    fun `页顶的详细说明收下了原来平铺的那两段`() {
        val detail = RestoreModel.INTRO_DETAIL
        assertTrue(detail.size >= 2)
        val all = detail.joinToString(" ")
        // 原来那段的完整意思
        assertTrue(all.contains("一模一样"))
        assertTrue(all.contains("服务器"))
        // 从「还没选文件」那一句里挪过来的半句 —— 它是这一页的性质，
        // 不是那个状态的说明，但一个字都不能丢。
        assertTrue(all.contains("存储权限"))
    }

    @Test
    fun `密码框下面那句短版点明了认的是哪一个口令`() {
        val short = RestoreModel.PASSWORD_HINT_SHORT
        assertTrue(short.length <= INLINE_MAX)
        // 中间改过主密码的话，这份文件认的还是旧的 —— 决策(114) 那条
        // 「最安静的数据丢失路径」，在这一页是它唯一一次被用户当面撞上。
        assertTrue(short.contains("导出"))
        assertTrue(short.contains("主密码"))
    }

    @Test
    fun `恢复之后那一行的副标题挑的是唯一一件用户不知道的事`() {
        // WHAT_HAPPENS 四条里三条是好消息，一行入口只放得下一句，
        // 就放那条会让人事后怀疑「是不是没恢复全」的。
        val s = RestoreModel.AFTER_SUMMARY
        assertTrue(s.contains("指纹"))
        assertTrue(s.contains("PIN"))
        assertTrue(s.contains("重新开") || s.contains("再开"))
    }

    @Test
    fun `数不出条目数那句的短版，仍然不许把这件事说成缺陷`() {
        val short = RestoreModel.WHY_NO_COUNT_SHORT
        assertTrue(short.length <= INLINE_MAX)
        assertTrue(short.contains("主密码"))
        // 同长版那条规矩：这是它该有的样子，不是还没做完
        assertFalse(short.contains("暂时"))
        assertFalse(short.contains("还没做"))
    }

    @Test
    fun `还没选文件那句短到只剩状态本身`() {
        // 它旁边紧跟着的就是「选择备份文件」那个按钮。
        // 这一句每多一行，那个按钮就往下走一行。
        assertTrue(RestoreModel.NO_FILE_SHORT.length <= 15)
    }

    /* ══════════════════════ 快捷解锁绑定页 ══════════════════════ */

    @Test
    fun `快捷解锁页顶那句的短版，三句判断一句不少`() {
        val short = QuickUnlockModel.INTRO_SHORT
        assertTrue("短版 ${short.length} 字", short.length <= INLINE_MAX)
        // 1. 只在这台设备上成立 —— 换机、拷走文件都带不走
        assertTrue(short.contains("这台设备"))
        // 2. 主密码永远还能用，它才是真凭据（决策①）
        assertTrue(short.contains("主密码"))
        // 3. 关掉不会动数据 —— 不写这句，一个失效的绑定会被一直留在那儿
        assertTrue(short.contains("不会动") || short.contains("不动"))
    }

    @Test
    fun `页顶短版不许把机制写给用户看`() {
        // 长版里那些（安全芯片、包裹）是解释，不是他此刻要做的判断。
        listOf("安全芯片", "包裹", "主密钥").forEach {
            assertFalse("短版不该出现「$it」", QuickUnlockModel.INTRO_SHORT.contains(it))
        }
    }

    @Test
    fun `指纹那一行，只有真超长的那一档才另写短版`() {
        var shortened = 0
        BiometricSupport.values().forEach { support ->
            listOf(true, false).forEach { enrolled ->
                val row = QuickUnlockModel.biometricRow(support, enrolled)
                val short = row.noteShort ?: return@forEach
                assertTrue(
                    "${support.name}/$enrolled 的短版有 ${short.length} 字，超过 $INLINE_MAX",
                    short.length <= INLINE_MAX,
                )
                // note 为空时 noteShort 也必须为空 —— 默认值就是它，
                // 有短版没长版意味着有人把默认值改坏了。
                assertNotNull("有短版就必须有长版：${support.name}", row.note)
                if (short != row.note) shortened++
            }
        }
        // 为了对称给每一档都凑一个短版，是把同一句话维护两遍。
        assertEquals("目前只该有「绑定已失效」那一档需要拆", 1, shortened)
    }

    @Test
    fun `绑定失效那一档的短版，先说不是故障、数据没动`() {
        // 用户看到「已失效」第一反应是「我的密码是不是没了」。
        // 怎么修是读完之后的事，而且那条路（去系统设置录入指纹）
        // 已经是屏幕上一个实实在在的按钮了。
        val row = QuickUnlockModel.biometricRow(BiometricSupport.NoneEnrolled, enrolled = true)
        val short = row.noteShort!!
        assertTrue(short.contains("不是故障"))
        assertTrue(short.contains("没动"))
        assertTrue("短版不比长版短就没必要拆", short.length < row.note!!.length)
    }

    /* ══════════════════════ 删除保险库页 ══════════════════════ */

    @Test
    fun `删除页三句短版都下得了两三行，而且都比长版短`() {
        listOf(
            DeleteVaultModel.EXPORTS_NOTE_SHORT to DeleteVaultModel.EXPORTS_NOTE,
            DeleteVaultModel.ERASURE_NOTE_SHORT to DeleteVaultModel.ERASURE_NOTE,
            DeleteVaultModel.PASSWORD_HINT_SHORT to DeleteVaultModel.PASSWORD_HINT,
        ).forEach { (short, full) ->
            assertTrue("「$short」有 ${short.length} 字", short.length <= INLINE_MAX)
            assertTrue("「$short」不比长版短", short.length < full.length)
        }
    }

    @Test
    fun `不做覆写擦除这件事，短版里就说了`() {
        // 决策⑧ 要的是这件事被**主动**说出来。收进一个多数人不会点开的弹窗，
        // 和不说没有区别 —— 那正是同类应用写「安全擦除」时干的事。
        val short = DeleteVaultModel.ERASURE_NOTE_SHORT
        assertTrue(short.contains("覆写"))
        assertTrue(short.contains("不做"))
        // 不许在短版里偷偷把话说反
        listOf("粉碎", "彻底擦除", "安全擦除", "军工").forEach {
            assertFalse("不该出现「$it」", short.contains(it))
        }
    }

    @Test
    fun `删除页那两句短版各自保住自己那半件事`() {
        // 好消息那半句：真删错了，拿备份还能回来。
        assertTrue(DeleteVaultModel.EXPORTS_NOTE_SHORT.contains("不受影响"))
        // 用户十有八九是靠指纹进来的，他此刻正要伸手去按传感器。
        val hint = DeleteVaultModel.PASSWORD_HINT_SHORT
        assertTrue(hint.contains("主密码"))
        assertTrue(hint.contains("指纹"))
        assertTrue(hint.contains("PIN"))
    }

    /* ══════════════════════ 改主密码页 ══════════════════════ */

    @Test
    fun `改主密码那条横幅收短之后，两个后果一个没丢`() {
        val short = ChangeMasterModel.BEFORE_WARNING_SHORT
        assertTrue("横幅是一行高的东西，${short.length} 字太长", short.length <= INLINE_MAX)
        // 一：旧备份不跟着变（决策(114) 那条最安静的数据丢失路径）
        assertTrue(short.contains("旧主密码"))
        assertTrue(short.contains("备份"))
        // 二：新的一样没有找回通道
        assertTrue(short.contains("找回"))
        assertTrue("短版不比长版短就没必要拆", short.length < ChangeMasterModel.BEFORE_WARNING.length)
    }

    /* ══════════════════════ 清空重来页 ══════════════════════ */

    @Test
    fun `清空重来页六句短版都下得了两三行，而且都比长版短`() {
        listOf(
            ResetVaultModel.LEAD_SHORT to ResetVaultModel.LEAD,
            ResetVaultModel.NO_RECOVERY_SHORT to ResetVaultModel.NO_RECOVERY,
            ResetVaultModel.NO_INVENTORY_SHORT to ResetVaultModel.NO_INVENTORY_NOTE,
            ResetVaultModel.EXPORTS_NOTE_SHORT to ResetVaultModel.EXPORTS_NOTE,
            ResetVaultModel.PHRASE_HINT_SHORT to ResetVaultModel.PHRASE_HINT,
            ResetVaultModel.SYSTEM_PATH_SHORT to ResetVaultModel.SYSTEM_PATH_NOTE,
        ).forEach { (short, full) ->
            assertTrue("「$short」有 ${short.length} 字", short.length <= INLINE_MAX)
            assertTrue("「$short」不比长版短", short.length < full.length)
        }
    }

    @Test
    fun `第一屏那两句短版仍然把结论摆在前面`() {
        // 走到这一页的人多半抱着侥幸。让他读到第三段才发现这不是找回入口，
        // 他会觉得刚才那一屏字是在绕圈子 —— 那是这一页排版顺序的全部理由。
        assertTrue(ResetVaultModel.LEAD_SHORT.contains("删掉"))
        assertTrue(
            ResetVaultModel.LEAD_SHORT.contains("帮不了") ||
                ResetVaultModel.LEAD_SHORT.contains("拿回来"),
        )
        val no = ResetVaultModel.NO_RECOVERY_SHORT
        // 没有找回通道
        assertTrue(no.contains("没有找回通道"))
        // 唯一那条生路：备份文件 + 它当时对应的主密码，两样缺一不可
        assertTrue(no.contains(".lvault"))
        assertTrue(no.contains("主密码"))
    }

    @Test
    fun `数不出条目那句的短版，两个方向都堵上`() {
        val short = ResetVaultModel.NO_INVENTORY_SHORT
        // 「读不到」挡住「这应用没做完」的读法
        assertTrue(short.contains("读不到"))
        // 「不该读」才是决策(129) 真正要说的那一半
        assertTrue(short.contains("不该读"))
        assertTrue(short.contains("锁着"))
        assertFalse(short.contains("暂时"))
    }

    @Test
    fun `唯一那条好消息，收短之后两头都还在`() {
        val short = ResetVaultModel.EXPORTS_NOTE_SHORT
        assertTrue(short.contains("不受影响"))
        // 这一页给他的唯一一条前路，比「不受影响」更要紧
        assertTrue(short.contains("拿它们回来") || short.contains("拿回来"))
    }

    @Test
    fun `抄写那句短版留的是停止线，不是修辞`() {
        val short = ResetVaultModel.PHRASE_HINT_SHORT
        assertTrue(short.contains("不是口令"))
        assertTrue(short.contains("停下"))
    }

    @Test
    fun `覆写擦除的短版两页共用同一个字符串`() {
        // 同决策(131)：同一件事不许有两份字。v4 之后要守的是长短两份。
        assertSame(DeleteVaultModel.ERASURE_NOTE_SHORT, ResetVaultModel.ERASURE_NOTE_SHORT)
    }

    /* ══════════════════════ 六页共同的规矩 ══════════════════════ */

    @Test
    fun `所有短版都不指着界面自己说话`() {
        allShortText().forEach { s ->
            UI_FILLER.forEach { filler ->
                assertFalse("短版里不该有「$filler」：$s", s.contains(filler))
            }
        }
    }

    @Test
    fun `所有短版都不暗示还有救援在路上`() {
        // 同 RestoreModelTest 那条。拆文案是最容易在改写时顺手写出一句
        // 「如需帮助请联系客服」的时刻 —— 而这个产品没有客服，也打不开你的库。
        val page = allShortText().joinToString(" ")
        RESCUE_PROMISE.forEach {
            assertFalse("不该出现「$it」", page.contains(it))
        }
    }

    @Test
    fun `所有短版都不许把删掉说成可以撤销`() {
        // 三页新收短的文案里有两页是不可逆动作（删除、清空重来）。
        // 收短是最容易顺手写出一句安慰的时刻。
        val page = allShortText().joinToString(" ")
        listOf("可恢复", "撤销", "回收站", "30 天").forEach {
            assertFalse("不该出现「$it」", page.contains(it))
        }
    }

    /** 六页上所有平铺在控件旁边的短文案。加了新的短版就往这里挂一条。 */
    private fun allShortText(): List<String> =
        Availability.values().mapNotNull { AutofillSettingsModel.row(it).noteShort } +
            biometricShorts() +
            listOf(
                AutofillSettingsModel.INTRO_SHORT,
                AutofillSettingsModel.optOutRow(respected = true).noteShort,
                AutofillSettingsModel.optOutRow(respected = false).noteShort,
                AutofillSettingsModel.LIMITS_SUMMARY,
                AutofillSettingsModel.WHY_SUMMARY,
                RestoreModel.INTRO_SHORT,
                RestoreModel.NO_FILE_SHORT,
                RestoreModel.WHY_NO_COUNT_SHORT,
                RestoreModel.PASSWORD_HINT_SHORT,
                RestoreModel.AFTER_SUMMARY,
                QuickUnlockModel.INTRO_SHORT,
                QuickUnlockModel.NONE_ENABLED_NOTE,
                DeleteVaultModel.EXPORTS_NOTE_SHORT,
                DeleteVaultModel.ERASURE_NOTE_SHORT,
                DeleteVaultModel.PASSWORD_HINT_SHORT,
                ChangeMasterModel.BEFORE_WARNING_SHORT,
                ResetVaultModel.LEAD_SHORT,
                ResetVaultModel.NO_RECOVERY_SHORT,
                ResetVaultModel.NO_INVENTORY_SHORT,
                ResetVaultModel.EXPORTS_NOTE_SHORT,
                ResetVaultModel.PHRASE_HINT_SHORT,
                ResetVaultModel.SYSTEM_PATH_SHORT,
            )

    /** 指纹那一行八种档位下的短版。 */
    private fun biometricShorts(): List<String> =
        BiometricSupport.values().flatMap { support ->
            listOf(true, false).mapNotNull { enrolled ->
                QuickUnlockModel.biometricRow(support, enrolled).noteShort
            }
        }
}
