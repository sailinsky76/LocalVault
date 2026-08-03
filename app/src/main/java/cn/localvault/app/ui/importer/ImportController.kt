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

package cn.localvault.app.ui.importer

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.localvault.app.core.session.VaultSession
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.ui.restore.ImportSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CSV 导入的执行者。把前四层（[CsvText] → [CsvParser] → [CsvMapping] → [CsvImport]）
 * 串成一条用户能走的路，并且是**唯一**碰 `VaultSession` 的那一层。
 *
 * ── 这一层里没有 Android，也没有 Compose 之外的界面 ──
 *
 * 文件从哪来是 [ImportSource] 的事（和恢复页共用同一个接口，不写第二份：
 * 那个接口只有 `read()` 没有 `write()`，于是「你那份 CSV 我们一个字都没改」
 * 这句话在这一页也是靠类型系统成立的，不是靠谁记得别写）。
 * 于是整条链路能在纯 JVM 上跑完，包括真的加密落盘。
 *
 * ── 一页流程里的四个时刻 ──
 *
 *   1. [pick]：读字节 → 解码 → 解析 → 自动认列 → 算一遍候选。**不需要任何确认**，
 *      因为到这一步为止什么都没写；
 *   2. [setRole] / [clearRoles]：用户改列映射。每改一下重算候选（贵，见下）；
 *   3. [setPolicy]：用户改撞车处置。只重算 [CsvImport.apply]（便宜）；
 *   4. [commit]：**以当下的库**重算一遍，然后一次性落盘。
 *
 * ── 为什么第 4 步要重算，而不是直接用屏幕上那份 ──
 *
 * 预览摆在屏幕上的这段时间是不确定的：用户会去翻源文件核对，会切出去，
 * 会在别的页面上删掉一条正好撞上的条目。拿一份旧快照去落盘，
 * 「覆盖 3 条」里那 3 条指的可能已经不是他看到的那 3 条了。
 * 重算一遍的代价是几十毫秒，换的是「屏幕上那句话和磁盘上的结果说的是同一件事」。
 * （[CsvImport.apply] 里那条「覆盖对象不见了就当新增」处理的正是这中间的缝。）
 *
 * ── 明文表活多久 ──
 *
 * [table] 是一份**明文密码表**，比库本身还敏感（决策(143)：它擦不掉，
 * 只能让它活得短、不外泄、说实话）。所以：它不进路由、不打日志、
 * 不进任何 `toString`；[commit] 成功后立刻丢引用；页面离开时调 [discard]。
 * 导入完成那一屏必须把 [CsvText.PLAINTEXT_NOTE] 摆出来——用户那份 CSV
 * 还躺在「下载」目录里，那才是真正的危险，比我们内存里这几百毫秒大得多。
 */
class ImportController(
    private val session: VaultSession,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {

    /* ══════════════════════════ 状态 ══════════════════════════ */

    @Immutable
    sealed interface Step {
        /** 还没选文件 */
        data object Idle : Step
        data object Reading : Step
        /** 已经解析出来了，正在核对列映射 / 看预览 */
        data object Preview : Step
        data object Committing : Step
        data class Done(val report: Report) : Step
        data class Failed(val kind: Fail, val text: String) : Step
    }

    /**
     * 失败之后**下一步该做什么**。分三种是因为按钮不一样，
     * 而按错按钮的代价是用户拿同一份文件反复重试同一个必然失败。
     */
    enum class Fail(val action: String) {
        /** 文件本身的问题（不是文本、空的、太大、没有数据行……）。重试一百次都一样。 */
        PickAnother("换一个文件"),
        /** 落盘失败。源文件还在内存里，可以直接再来一次。 */
        Retry("再试一次"),
        /** 库在中途锁了。这一页得退出去，先解锁。 */
        Relock("回去解锁"),
    }

    /** 导入完成之后的那一屏。**只有数字，没有任何条目内容。** */
    @Immutable
    class Report(
        val added: Int,
        val replaced: Int,
        val skippedByPolicy: Int,
        val skippedByRow: Int,
        /** [CsvImport.Flag] 那几条「照样导了，但你该知道」。 */
        val notes: List<String>,
    ) {
        val total: Int get() = added + replaced
        val skipped: Int get() = skippedByPolicy + skippedByRow

        /** 导入完成那一屏**必须**显示的一句。见类注释末段。 */
        val sourceFileReminder: String get() = CsvText.PLAINTEXT_NOTE

        override fun toString(): String = "ImportReport(+$added, ~$replaced, skip $skipped)"
    }

    var step by mutableStateOf<Step>(Step.Idle)
        private set

    /** 选中文件的显示名。选错文件是这条路上最常见的失误，所以它要一直挂在屏幕上。 */
    var fileName by mutableStateOf<String?>(null)
        private set

    /** 当前的列映射方案。没选文件时为 null。 */
    var plan by mutableStateOf<CsvMapping.Plan?>(null)
        private set

    private var policyState by mutableStateOf(CsvImport.Policy.Skip)
    val policy: CsvImport.Policy get() = policyState

    /** 当前映射下的候选清单（已含库内判重）。 */
    var candidates by mutableStateOf<List<CsvImport.Candidate>>(emptyList())
        private set

    /** 候选正在重算（改了列映射之后）。界面据此把预览那一块置灰，而不是让它闪烁。 */
    var recomputing by mutableStateOf(false)
        private set

    /**
     * 解析出来的明文表。**私有，不对外暴露**——界面要什么就在这里加一个只读的派生值，
     * 别把整张表交出去。见类注释末段。
     */
    private var table: CsvParser.Table? = null

    private var job: Job? = null
    private var recomputeJob: Job? = null

    val busy: Boolean
        get() = step is Step.Reading || step is Step.Committing

    /* ══════════════════════════ 派生 ══════════════════════════ */

    /** 表头。列映射那一屏逐列显示它。 */
    val header: List<String> get() = plan?.header ?: emptyList()

    /** 数据行数（不含表头）。 */
    val rowCount: Int get() = table?.rows?.size ?: 0

    /** 解析阶段的记账（参差行、空行……）+ 映射阶段的记账。 */
    val notes: List<String>
        get() = (table?.notes() ?: emptyList()) + (plan?.noteTexts() ?: emptyList())

    /** 拦着不让导的事。空表示可以导。 */
    val blockers: List<String> get() = plan?.blockers() ?: emptyList()

    /** 这一刻的处置结果。[plan] / [policy] / 库内容任一变化都会重算。 */
    val outcome: CsvImport.Outcome
        get() = CsvImport.apply(candidates, existing(), policy)

    val summary: String get() = CsvImport.summary(outcome)

    /** 跳过的行按理由归并计数，界面上一类一行。 */
    val skipCounts: Map<CsvImport.Skip, Int> get() = CsvImport.skipCounts(candidates)

    /**
     * 能不能按「导入」。
     *
     * 三个条件缺一不可：不在忙、映射本身过得去（[CsvMapping.Plan.blockers]）、
     * **这一刻算出来确实有东西要写**。第三条不是多余的：
     * 用户完全可能把处置设成「跳过」，而这份文件里每一行都撞上了——
     * 那时候按下去会是一次什么都不做的成功，比按钮灰着更让人困惑。
     */
    val canCommit: Boolean
        get() = !busy && !recomputing && step is Step.Preview &&
            blockers.isEmpty() && outcome.total > 0

    private fun existing(): List<VaultEntry> = session.data?.entries ?: emptyList()

    /* ══════════════════════════ 选文件 ══════════════════════════ */

    /**
     * 用户在系统文件选择器里挑了一个文件。读进来、解码、解析、自动认列。
     *
     * 这一整段**什么都不写**，所以不需要确认，也不需要主密码——
     * 库已经是解锁的了，这一页才走得到。
     */
    fun pick(source: ImportSource) {
        if (busy) return
        job = scope.launch {
            try {
                step = Step.Reading
                val name = source.displayName
                fileName = name

                val bytes = try {
                    withContext(worker) { source.read() }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    fail(Fail.PickAnother, IO_FAILED)
                    return@launch
                }

                val prepared = withContext(worker) { prepare(bytes) }
                when (prepared) {
                    is Prepared.Bad -> fail(Fail.PickAnother, prepared.text)
                    is Prepared.Ok -> {
                        table = prepared.table
                        plan = prepared.plan
                        policyState = CsvImport.Policy.Skip
                        candidates = prepared.candidates
                        step = Step.Preview
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(Fail.PickAnother, IO_FAILED)
            }
        }
    }

    private sealed interface Prepared {
        class Ok(
            val table: CsvParser.Table,
            val plan: CsvMapping.Plan,
            val candidates: List<CsvImport.Candidate>,
        ) : Prepared

        class Bad(val text: String) : Prepared
    }

    /**
     * 字节 → 可以摆上屏幕的东西。跑在 [worker] 上，一句 Compose 状态都不碰。
     *
     * 失败时交出来的是**前面那一层自己的那句话**（[CsvText.message] /
     * [CsvParser.message]）。不在这里另写一套的理由和别处一样：
     * 同一件事有两套说法时，用户看到的那一套迟早会和真正发生的事对不上。
     */
    private fun prepare(bytes: ByteArray): Prepared {
        val decoded = CsvText.decode(bytes)
        if (decoded !is CsvText.Decoded.Ok) return Prepared.Bad(CsvText.message(decoded))

        val parsed = CsvParser.parse(decoded.text)
        if (parsed !is CsvParser.Parsed.Ok) return Prepared.Bad(CsvParser.message(parsed))

        val table = parsed.table
        val plan = CsvMapping.plan(table.header)
        return Prepared.Ok(table, plan, CsvImport.prepare(table, plan, existing()))
    }

    /* ══════════════════════════ 改映射 / 改处置 ══════════════════════════ */

    /**
     * 把某一列改成某个角色（`null` = 这一列不导入）。
     *
     * 「一个角色只能占一列」的连带清空在 [CsvMapping.Plan.withRole] 里，
     * 这里不重复——那条规则写第二份的表现是「密码列点到别处了，
     * 可原来那列还标着密码」，两列都往密码里写，用户看到的是先写的那一列。
     */
    fun setRole(column: Int, role: CsvMapping.Role?) {
        val p = plan ?: return
        if (busy) return
        val next = p.withRole(column, role)
        if (next === p) return
        plan = next
        recompute()
    }

    /** 全部清空，从头手点。 */
    fun clearRoles() {
        val p = plan ?: return
        if (busy) return
        plan = p.cleared()
        recompute()
    }

    /** 回到自动识别的那一份。用户点乱了之后的退路。 */
    fun resetRoles() {
        val t = table ?: return
        if (busy) return
        plan = CsvMapping.plan(t.header)
        recompute()
    }

    /**
     * 改撞车处置。**不重算候选**：处置只影响 [CsvImport.apply]，
     * 那一步是 O(n) 的纯内存计算，`outcome` 是个 getter，下一帧就是新的了。
     * 判重（[CsvImport.against]，O(行数 × 库条目数)）和处置无关，重算它是白花钱。
     */
    fun setPolicy(p: CsvImport.Policy) {
        if (busy) return
        policyState = p
    }

    /**
     * 重算候选。改列映射之后必须走一遍：行 → 条目和判重都依赖映射。
     *
     * 扔到 [worker] 上，并且**后一次取消前一次**：用户逐列点过去时，
     * 中间那几份结果没有人要，而一份两万行的表算一遍要几百毫秒——
     * 排队算完只会让最后一次结果姗姗来迟。
     */
    private fun recompute() {
        val t = table ?: return
        val p = plan ?: return
        recomputeJob?.cancel()
        recomputing = true
        recomputeJob = scope.launch {
            try {
                val next = withContext(worker) { CsvImport.prepare(t, p, existing()) }
                candidates = next
                recomputing = false
            } catch (c: CancellationException) {
                // 被下一次改动取消了。**不要在这里把 recomputing 翻回 false**：
                // 接手的那一次刚把它设成 true，翻回去会让界面在整段连点期间闪一下「算完了」。
                throw c
            } catch (t2: Throwable) {
                recomputing = false
            }
        }
    }

    /* ══════════════════════════ 落盘 ══════════════════════════ */

    /**
     * 导入。以**当下的库**重算一遍，然后一次性落盘（[VaultSession.importEntries]）。
     *
     * 成功之后立刻丢掉明文表。失败分两种：库锁了（这一页没救了，得先解锁）
     * 和写盘失败（源文件还在内存里，可以直接再来一次）——
     * 后者磁盘上什么都没变，[VaultSession.mutate] 失败时连内存都回滚了。
     */
    fun commit() {
        if (!canCommit) return
        val t = table ?: return
        val p = plan ?: return
        val chosen = policy

        job = scope.launch {
            try {
                step = Step.Committing

                if (!session.isUnlocked) { fail(Fail.Relock, LOCKED); return@launch }

                // 屏幕上那份预览可能已经旧了：这段时间里用户在别处改过库。
                // 以当下的库重算，落盘的和马上要显示的报告才是同一件事。
                val now = existing()
                val fresh = withContext(worker) { CsvImport.prepare(t, p, now) }
                val out = CsvImport.apply(fresh, now, chosen)

                if (out.total == 0) {
                    // 重算之后无事可做（比如那几条撞上的条目在别处被改成了会跳过的样子）。
                    // 报成功、数字是 0，比报失败诚实：磁盘上确实没有需要写的东西。
                    table = null
                    step = Step.Done(report(out))
                    return@launch
                }

                val result = withContext(worker) { session.importEntries(out.add, out.replace) }
                result.fold(
                    onSuccess = {
                        table = null      // 明文表的寿命到此为止
                        step = Step.Done(report(out))
                    },
                    onFailure = { _ ->
                        if (!session.isUnlocked) fail(Fail.Relock, LOCKED)
                        else fail(Fail.Retry, SAVE_FAILED)
                    },
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t2: Throwable) {
                fail(Fail.Retry, SAVE_FAILED)
            }
        }
    }

    private fun report(out: CsvImport.Outcome) = Report(
        added = out.add.size,
        replaced = out.replace.size,
        skippedByPolicy = out.skippedByPolicy,
        skippedByRow = out.skippedByRow,
        notes = out.noteTexts(),
    )

    /* ══════════════════════════ 收尾 ══════════════════════════ */

    /**
     * 丢掉这一份文件（换文件、退出这一页、导完之后）。
     *
     * 把明文表连同它派生出来的一切一起丢掉。[fileName] 也清掉——
     * 留着一个文件名而没有内容，界面上会是一张说不清自己在等什么的空页。
     */
    fun discard() {
        recomputeJob?.cancel()
        recomputeJob = null
        recomputing = false
        table = null
        plan = null
        candidates = emptyList()
        fileName = null
        policyState = CsvImport.Policy.Skip
        step = Step.Idle
    }

    /**
     * 关掉错误提示。
     *
     * 分两条路：文件本身不行的，退回「还没选文件」（顺手把那个文件名也清了，
     * 让「换一个文件」是这一屏上唯一说得通的动作）；写盘失败的，
     * 退回预览——那份表还在，用户按一下就能再试。
     */
    fun dismissError() {
        val f = step as? Step.Failed ?: return
        step = when {
            f.kind == Fail.Retry && table != null -> Step.Preview
            f.kind == Fail.PickAnother -> { fileName = null; Step.Idle }
            else -> Step.Idle
        }
    }

    fun cancel() {
        job?.cancel(); job = null
        recomputeJob?.cancel(); recomputeJob = null
    }

    private fun fail(kind: Fail, text: String) {
        step = Step.Failed(kind, text)
    }

    private companion object {
        const val IO_FAILED =
            "这个文件读不下来。可能是它已经被删掉或者移走了，也可能是提供它的那个应用" +
                "（网盘、文件管理器）此刻不让读。回文件选择器里重新选一次。"

        const val SAVE_FAILED =
            "写进保险库时失败了，**一条都没有导进去**——这一步要么全进要么全不进，" +
                "所以库还是原来的样子，不会剩下导了一半的东西。" +
                "最常见的原因是存储空间不够。腾点地方再试一次。"

        const val LOCKED =
            "保险库在导入过程中锁上了（多半是切出去太久，自动锁定生效了），" +
                "什么都没有导进去。先解锁，再重新选一次文件。"
    }
}
