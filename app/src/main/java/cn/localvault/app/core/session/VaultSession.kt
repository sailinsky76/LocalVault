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

package cn.localvault.app.core.session

import android.util.Log
import cn.localvault.app.core.crypto.SecureBytes
import cn.localvault.app.core.vault.VaultData
import cn.localvault.app.core.vault.VaultEntry
import cn.localvault.app.core.vault.VaultFile
import cn.localvault.app.core.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 解锁后的会话。这是整个 App 里唯一持有明文数据和库主密钥的地方。
 *
 * 三条不能破的规矩：
 *   1. 锁定时必须把库主密钥清零，并丢掉明文数据的引用；
 *   2. 任何一次数据改动都立刻落盘 —— 不做「稍后保存」，
 *      因为进程随时可能被系统杀掉，而用户以为自己已经存好了；
 *   3. 切到后台就开始计时，超时自动锁。
 */
class VaultSession(
    private val repo: VaultRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface State {
        /** 还没建库，应该走首次引导 */
        data object NoVault : State
        /** 有库，锁着 */
        data object Locked : State
        /** 已解锁，明文在内存里 */
        data class Unlocked(val data: VaultData) : State
    }

    /**
     * 上一次锁定是怎么发生的。
     *
     * ── 为什么不能只靠 [Event.AutoLocked] ──
     *
     * 那是个 `SharedFlow` 的一次性事件，而解锁页是**锁定之后才被创建的**：
     * `lock()` 先把状态翻成 Locked，导航相位随即换掉整棵子树，
     * 新建的解锁页再去订阅 `events` 时，那条事件早就发完了。
     * 于是「因长时间未操作已自动锁定」这句话永远不会出现——
     * 而这句话恰恰是用户回到手机、发现自己被挡在外面时最需要看到的一句，
     * 少了它，自动锁定看起来就像应用自己崩了一次。
     *
     * 所以额外留一个**状态**（而不是事件），由解锁页在创建时读一次。
     * 它不含任何库内信息，只说明「上一次是谁把门关上的」。
     */
    enum class LockReason { None, Manual, AutoTimeout }

    var lastLockReason: LockReason = LockReason.None
        private set

    /** 一次性事件，用于让 UI 提示「已自动锁定」这类信息 */
    sealed interface Event {
        data object AutoLocked : Event
        data class SaveFailed(val error: Throwable) : Event
        data class RestoredFromBackup(val reason: String) : Event
    }

    private val _state = MutableStateFlow<State>(if (repo.exists()) State.Locked else State.NoVault)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** 库主密钥。只在解锁期间存在。 */
    private var vaultKey: SecureBytes? = null

    /**
     * 当前打开的这个库的文件头里记的 KDF 档位。未解锁时为 null。
     *
     * 留着它只为一件事：让顶部封条显示**这个库实际用的**档位，
     * 而不是「本机新建库会用哪一档」。两者在换机场景下会不一样——
     * 用户从旧手机拷过来的库可能是低配档位建的，
     * 封条如果显示新机器的档位就是在撒谎。
     *
     * ── 为什么是 `StateFlow` 而不是一个普通 getter ──
     *
     * 因为它**会在解锁期间变**：改主密码会顺带按这台设备重新校准一次档位
     * （见 `ChangeMasterController`）。普通 getter 的话，封条要到下一次
     * 锁定—解锁才会更新，中间那段时间它显示的是这个库已经不再使用的档位，
     * 而封条的第 1 条规矩就是不许显示假话（见 Seal.kt）。
     *
     * 只存参数、不存整个文件头：盐和参数里不含密钥材料，但也没有别人要用，
     * 留着一个用不上的对象只会让「锁定时到底该清掉哪些东西」多一条要记的规矩。
     * 跟着 lock() 一起清空——锁定后界面不该还能读出上一次解锁的任何东西。
     */
    private val _headerKdfParams =
        MutableStateFlow<cn.localvault.app.core.crypto.KdfParams?>(null)

    val headerKdfParamsFlow: StateFlow<cn.localvault.app.core.crypto.KdfParams?> =
        _headerKdfParams.asStateFlow()

    /** 同上，取当前值。给不在 Compose 里的调用方用（含单测）。 */
    val headerKdfParams: cn.localvault.app.core.crypto.KdfParams?
        get() = _headerKdfParams.value

    private var autoLockJob: Job? = null
    private var backgroundedAt: Long = 0L

    val isUnlocked: Boolean get() = _state.value is State.Unlocked
    val data: VaultData? get() = (_state.value as? State.Unlocked)?.data

    // ───────────────────── 解锁 / 锁定 ─────────────────────

    fun adopt(opened: VaultFile.Opened) {
        vaultKey?.wipe()
        vaultKey = opened.vaultKey
        _headerKdfParams.value = opened.header.kdfParams
        _state.value = State.Unlocked(opened.data)
        lastLockReason = LockReason.None
        cancelAutoLock()
    }

    /** 主密码解锁 */
    fun unlock(masterPassword: CharArray) = adopt(repo.unlock(masterPassword))

    /** 快捷解锁（指纹 / PIN 已解出库主密钥）。传入后本会话接管它的生命周期。 */
    fun unlockWithKey(key: SecureBytes) = key.use { adopt(repo.unlockWithKey(it)) }

    /**
     * 锁定。清掉密钥和明文。
     *
     * 注意 [VaultData] 是不可变对象，我们只能丢引用等 GC，
     * 没法像字节数组那样擦。这是用 Kotlin 数据类换来的便利所付的代价，
     * 可接受的原因是：真正致命的是库主密钥，而它是能擦干净的。
     */
    fun lock() = lockInternal(LockReason.Manual)

    private fun lockInternal(reason: LockReason) {
        val wasUnlocked = isUnlocked
        vaultKey?.wipe()
        vaultKey = null
        _headerKdfParams.value = null
        cancelAutoLock()
        if (_state.value !is State.NoVault) _state.value = State.Locked
        // 本来就是锁着的时候再调一次 lock()（比如页面重建时的保险调用）
        // 不该把上一次的原因抹掉，否则自动锁定的提示会被一次无关的调用吃掉。
        if (wasUnlocked) lastLockReason = reason
    }

    fun onVaultCreated(opened: VaultFile.Opened) = adopt(opened)

    /**
     * 主密码改完之后调，把文件头换成新的那一份。
     *
     * ── 为什么不是重新解锁一次 ──
     *
     * 改主密码**没有换库主密钥**（决策①：只重新包裹它），
     * 所以会话里那把钥匙、内存里那份明文数据，改完之后依然是对的。
     * 唯一过期的东西是文件头——新的盐、可能还有新的 KDF 档位。
     * 为这一件事把整个库重新解密一遍，只会白白多一次几百毫秒的派生，
     * 还要在中间那一瞬间让状态短暂地离开 Unlocked（导航相位会跟着抖一下）。
     *
     * 不接受未解锁时的调用：改密码这件事本来就只能在解锁状态下发生，
     * 真在锁定之后才走到这里，说明有一条我们没想到的路径，
     * 那时候悄悄把一个文件头存下来，比什么都不做更难查。
     */
    fun onMasterPasswordChanged(newHeader: VaultFile.Header) {
        if (!isUnlocked) return
        _headerKdfParams.value = newHeader.kdfParams
    }

    /**
     * 库文件已经被删掉了，把相位翻回 [State.NoVault]。
     *
     * ── 为什么不能用 [lock] ──
     *
     * `lock()` 的终点是 [State.Locked]，那是「有库，锁着」。删完之后翻到那儿，
     * 用户看到的是一张解锁页——一个要他为一个已经不存在的库输入主密码的页面。
     * 他会以为删除失败了，然后去输密码，然后得到一个说不清的错误。
     *
     * ── 为什么由调用方来调，而不是让会话自己去问 `repo.exists()` ──
     *
     * 会话没有任何时机知道文件被删了：它不监听文件系统，也不该监听。
     * 删除是一个有明确发起点的动作（`DeleteVaultController` 的第 3 步），
     * 由发起方在**确认文件真的没了之后**通知一声，比让会话去猜要可靠得多。
     *
     * 和 [lockInternal] 做同样的清理（擦密钥、丢明文、停自动锁定、清中断标记），
     * 只是终点不同。[lastLockReason] 归零：下一次锁定发生时，
     * 解锁页不该看到一条来自上一个库的「上次是被自动锁定的」。
     */
    fun onVaultDeleted() {
        vaultKey?.wipe()
        vaultKey = null
        _headerKdfParams.value = null
        cancelAutoLock()
        lastLockReason = LockReason.None
        _state.value = State.NoVault
    }

    // ───────────────────── 可信中断 ─────────────────────

    /**
     * 「可信中断」的截止时刻。
     *
     * ── 这个东西是来解决什么的 ──
     *
     * 我们自己拉起系统界面时（文件选择器、指纹弹窗、系统分享面板），
     * Activity 会走 `onStop`，从会话的角度看和「用户按 Home 键走了」一模一样，
     * 于是自动锁定开始倒计时。默认 60 秒——用户在文件选择器里翻两层文件夹
     * 就超了，回来一看库已经锁了，导出直接失败。
     * 更糟的是把自动锁定设成「立即」的用户，他**永远无法完成一次导出**。
     *
     * 所以要区分「用户离开了」和「我们把用户送出去了」。后者给一段宽限。
     *
     * ── 为什么是宽限而不是直接关掉自动锁定 ──
     *
     * 因为「我们送出去的」不等于「用户一定会回来」：他可能在文件选择器里
     * 按了 Home 键把手机往桌上一放。所以宽限是有限的（[INTERLUDE_GRACE_SECONDS]），
     * 超时照锁不误；回到前台后由发起方调 [endSystemInterlude] 立刻恢复常规超时。
     */
    private var interludeUntil: Long = 0L

    private val inInterlude: Boolean get() = clock() < interludeUntil

    /** 拉起系统界面**之前**调。 */
    fun beginSystemInterlude() {
        if (isUnlocked) interludeUntil = clock() + INTERLUDE_GRACE_SECONDS * 1000L
    }

    /** 系统界面的结果回来之后调（包括用户取消）。 */
    fun endSystemInterlude() { interludeUntil = 0L }

    // ───────────────────── 自动锁定 ─────────────────────

    /**
     * 这一刻该用多长的超时。
     *
     * 中断期内一律用宽限值，**包括用户把自动锁定设成「立即」的情况**——
     * 那个设置的本意是「我离开屏幕就锁」，不是「禁止我导出备份」。
     */
    private fun effectiveTimeoutSeconds(): Int =
        if (inInterlude) INTERLUDE_GRACE_SECONDS
        else data?.meta?.autoLockSeconds ?: DEFAULT_AUTO_LOCK

    /** Activity onStop 时调用 */
    fun onEnterBackground() {
        if (!isUnlocked) return
        backgroundedAt = clock()
        val timeout = effectiveTimeoutSeconds()

        if (timeout <= 0) { autoLock(); return }

        autoLockJob?.cancel()
        autoLockJob = scope.launch {
            delay(timeout * 1000L)
            autoLock()
        }
    }

    /**
     * Activity onStart 时调用。
     *
     * 不能只靠上面那个 delay：进程被冻结时协程也不跑，
     * 所以回前台必须用挂钟时间再核对一次。
     */
    fun onEnterForeground() {
        autoLockJob?.cancel()
        autoLockJob = null
        if (!isUnlocked || backgroundedAt == 0L) return

        val timeout = effectiveTimeoutSeconds()
        val elapsed = (clock() - backgroundedAt) / 1000
        if (elapsed >= timeout) autoLock() else backgroundedAt = 0L
    }

    private fun autoLock() {
        if (!isUnlocked) return
        Log.i(TAG, "自动锁定")
        lockInternal(LockReason.AutoTimeout)
        _events.tryEmit(Event.AutoLocked)
    }

    private fun cancelAutoLock() {
        autoLockJob?.cancel()
        autoLockJob = null
        backgroundedAt = 0L
        // 锁定 / 解锁都算流程重置：一个悬着的中断标记不该跨越锁定活下来
        interludeUntil = 0L
    }

    // ───────────────────── 修改数据 ─────────────────────

    /**
     * 所有数据改动的唯一入口。改完立刻落盘。
     *
     * 失败时**内存里的状态回滚**——不能让界面显示「已保存」而磁盘上没有。
     */
    fun mutate(transform: (VaultData) -> VaultData): Result<VaultData> {
        val current = (_state.value as? State.Unlocked)?.data
            ?: return Result.failure(IllegalStateException("保险库未解锁"))
        val key = vaultKey ?: return Result.failure(IllegalStateException("库主密钥不存在"))

        val next = transform(current)
        return try {
            repo.save(next, key)
            _state.value = State.Unlocked(next)
            Result.success(next)
        } catch (e: Throwable) {
            Log.e(TAG, "保存失败，已回滚", e)
            _events.tryEmit(Event.SaveFailed(e))
            Result.failure(e)
        }
    }

    // ── 条目操作（都走 mutate，保证「改了就一定存了」）──

    fun addEntry(entry: VaultEntry): Result<VaultData> {
        val now = clock()
        val e = entry.copy(
            id = entry.id.ifEmpty { UUID.randomUUID().toString() },
            createdAt = if (entry.createdAt == 0L) now else entry.createdAt,
            updatedAt = now,
            passwordUpdatedAt = if (entry.password.isNotEmpty()) now else 0L,
        )
        return mutate { it.copy(entries = it.entries + e) }
    }

    fun updateEntry(entry: VaultEntry): Result<VaultData> {
        val now = clock()
        return mutate { d ->
            d.copy(entries = d.entries.map { old ->
                if (old.id != entry.id) old
                else entry.copy(
                    updatedAt = now,
                    // 密码没变就不刷新「上次改密码」时间，否则「该换密码了」的提醒永远不会触发
                    passwordUpdatedAt = if (old.password != entry.password) now else old.passwordUpdatedAt,
                )
            })
        }
    }

    fun deleteEntry(id: String): Result<VaultData> =
        mutate { d -> d.copy(entries = d.entries.filterNot { it.id == id }) }

    /**
     * 批量删除：**一次 [mutate]，一次序列化、一次加密、一次写盘。**
     *
     * ── 为什么不能循环调 [deleteEntry] ──
     *
     * 和 [importEntries] 顶上那段是同一件事，只是方向相反。
     * [mutate] 的规矩是「改了就一定存了」，代价是每调一次就把**整个库**
     * 序列化、加密、原子写盘、再读回来验一遍（决策⑱）。
     * 用户在列表上勾了 20 条按删除，循环调用就是 20 次全库重写：
     * 慢，而且**不是原子的**——删到第 13 条磁盘满了，他得到的是一个
     * 删掉 12 条的库，外加一句「删除失败」。这时候屏幕上的条数、
     * 「有 N 条改动还没进备份」和他脑子里的记录三者全对不上，
     * 而他没有任何办法知道到底删掉了哪 12 条。
     *
     * 走一次 [mutate] 的话，要么 20 条全没了，要么一条都没动、内存也回滚了
     * （见 [mutate] 的 catch 分支）。失败之后重来一次是干净的。
     *
     * ── 找不到的 id 一律忽略，不报错 ──
     *
     * 列表页把选中集合摆在屏幕上的这段时间里，那几条完全可能已经在别处没了
     * （详情页删掉了、导入覆盖了）。用户对这一批的意思自始至终是「让它们不在库里」，
     * 而那几条已经不在了——这不是失败，是目标已经达成。
     * 判成失败会让整批删除停下来，而他看着屏幕想不出哪里出了错。
     * 同 [importEntries] 里「replace 那条找不到就当新增」的下半段。
     *
     * 空集合直接返回成功且**不写盘**：一次没有任何改动的全库重写，
     * 成功了没有意义，失败了却会让用户以为删除出了问题。
     */
    fun deleteEntries(ids: Set<String>): Result<VaultData> {
        val current = (_state.value as? State.Unlocked)?.data
            ?: return Result.failure(IllegalStateException("保险库未解锁"))
        if (ids.isEmpty()) return Result.success(current)
        return mutate { d -> d.copy(entries = d.entries.filterNot { it.id in ids }) }
    }

    /**
     * 批量导入的唯一落盘入口：**一次 [mutate]，一次序列化，一次加密，一次写盘。**
     *
     * ── 为什么不能一条一条 [addEntry] ──
     *
     * [mutate] 的规矩是「改了就一定存了」，代价是每调一次就把**整个库**
     * 序列化、加密、原子写盘、再读回来验一遍（决策⑱）。平时一次改一条，
     * 这个代价看不见；导入一份 500 条的 CSV 就是 500 次全库重写——
     * 一个几百 KB 的库要写掉上百 MB，几十秒起步，中途还要一直握着明文表。
     * 更糟的是它**不是原子的**：第 317 条上磁盘满了，用户得到的是一个
     * 导进去 316 条的库，而屏幕上写着「导入失败」。他重来一次，
     * 那 316 条又会撞上判重——一份本来干净的导入，变成了要手工收拾的烂摊子。
     *
     * 所以批量走一次 [mutate]：要么 500 条全在，要么一条都没进、内存也回滚了
     * （见 [mutate] 的 catch 分支）。失败之后重来一次是干净的。
     *
     * ── 三条规则和单条入口保持一致 ──
     *
     *  1. [add] 里的条目 `id` 为空时补一个新的，`createdAt` 为 0 时取现在
     *     （同 [addEntry]。源文件里的创建时间这一版不认，所以实际上都是现在）；
     *  2. [replace] 靠 `id` 找库里那一条，`passwordUpdatedAt` **只在密码真的变了时**
     *     才刷新（同 [updateEntry]）——否则「该换密码了」的提醒会被一次
     *     什么都没改的重复导入整体清零；`createdAt` 一律留旧的，那是同一条条目的身份；
     *  3. 顺序：库里原有的条目留在原位（覆盖是就地替换，不是删了再加），
     *     新增的按给进来的顺序追加在末尾。列表页自己会排序，
     *     但「导进去的顺序和文件里的顺序一致」在用户核对时是有用的。
     *
     * ── [replace] 里那条在库里已经找不到了怎么办 ──
     *
     * 当成新增，**保留它原来的 id**。这是 `CsvImport.apply` 里同一个判断的下半段：
     * 预览摆在屏幕上的这段时间里，用户可能在别处把那条删了。
     * 他对这一行的意思自始至终是「把它导进去」，那条没了正好直接加。
     * 判成失败或者静默丢弃都比这个差——前者让整份导入停在这里，后者他发现不了。
     *
     * 空清单直接返回成功且**不写盘**：一次没有任何改动的全库重写，
     * 成功了没有意义，失败了却会让用户以为导入出了问题。
     */
    fun importEntries(add: List<VaultEntry>, replace: List<VaultEntry>): Result<VaultData> {
        val current = (_state.value as? State.Unlocked)?.data
            ?: return Result.failure(IllegalStateException("保险库未解锁"))
        if (add.isEmpty() && replace.isEmpty()) return Result.success(current)

        val now = clock()
        val incoming = replace.associateBy { it.id }
        val hit = HashSet<String>(incoming.size)

        return mutate { d ->
            val kept = d.entries.map { old ->
                val new = incoming[old.id] ?: return@map old
                hit += old.id
                new.copy(
                    createdAt = old.createdAt,
                    updatedAt = now,
                    passwordUpdatedAt =
                        if (old.password != new.password) now else old.passwordUpdatedAt,
                )
            }
            // 预览期间被别处删掉的那几条，跟新增走同一条路
            val strays = replace.filter { it.id !in hit }
            val fresh = (strays + add).map { e ->
                e.copy(
                    id = e.id.ifEmpty { UUID.randomUUID().toString() },
                    createdAt = if (e.createdAt == 0L) now else e.createdAt,
                    updatedAt = now,
                    passwordUpdatedAt = if (e.password.isNotEmpty()) now else 0L,
                )
            }
            d.copy(entries = kept + fresh)
        }
    }

    fun updateMeta(transform: (cn.localvault.app.core.vault.VaultMeta) -> cn.localvault.app.core.vault.VaultMeta) =
        mutate { it.copy(meta = transform(it.meta)) }

    /** 导出成功后记一笔，首页的「从未备份」提醒据此消失 */
    fun markBackedUp() = updateMeta { it.copy(lastBackupAt = clock()) }

    // ───────────────────── 给快捷解锁绑定用 ─────────────────────

    /**
     * 借用库主密钥去做绑定（指纹 / PIN）。
     *
     * 刻意做成回调而不是 getter：调用方拿不到长期引用，
     * 也就没机会把它存到某个变量里忘了清。
     */
    fun <R> withVaultKey(block: (ByteArray) -> R): R {
        val key = vaultKey ?: throw IllegalStateException("保险库未解锁")
        return block(key.bytes())
    }

    companion object {
        private const val TAG = "VaultSession"
        const val DEFAULT_AUTO_LOCK = 60

        /**
         * 可信中断的宽限时长。3 分钟够翻几层文件夹，又短到手机被顺走时不至于门户大开。
         */
        const val INTERLUDE_GRACE_SECONDS = 180
    }
}
