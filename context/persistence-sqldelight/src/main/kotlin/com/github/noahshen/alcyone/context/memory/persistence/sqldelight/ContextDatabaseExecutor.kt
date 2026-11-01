package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Context 状态库连接执行器：1 个写 Slot + N 个只读 Slot（默认 2，可配置）。
 *
 * 只做持久化基础（技术实现设计 §2.6 / CT01 §h7），**不含任何业务 Repository**：
 * - 每个 Slot 自持独占物理连接与固定执行线程，一次操作期间连接不被其它协程触碰；
 * - 写事务固定 `autoCommit=false` → 回调 → `commit()`，异常或取消 `rollback()` 后恢复；
 * - 读上下文用显式 `BEGIN` / `COMMIT` 包住回调，多次查询得到同一 WAL 快照；
 * - 禁止嵌套、禁止 Savepoint、禁止上下文逃逸，跨线程调用抛具名异常；
 * - `close` 幂等：请求准入、停止准入与唯一关闭结果共用同一同步边界；先拒绝新请求，
 *   再等所有在途阻塞 JDBC 退出，最后逆序关 ReadSlots、WriteSlot；同步 `close()` 与
 *   异步 `closeAsync()` 等待同一次真实关闭完成，成功/失败对所有等待者一致可见；
 *   协程取消不会抢先关掉正在使用的连接，`CancellationException` 原样向外传播。
 *
 * 连接验证（R4）：每个 Slot 的实际连接在应用持久化 PRAGMA 前，由
 * [ContextSlot.validateSchemaVersion] 再次读取 `user_version`，并由
 * [ContextSlot.validateMissingObjects] 检查六表五索引；任何不符都关闭已打开的 Slot
 * 并抛异常。不信任前一次检查作为永久保证。
 *
 * 底层入口 [queryRaw] / [updateRaw] / [read] / [write] 供 CT04 自测与 CT05 接线使用。
 */
class ContextDatabaseExecutor
    internal constructor(
        private val path: Path,
        readSlotCount: Int,
        /** 读 Slot 的打开方式；测试用它注入「第 N 个 Slot 打开失败」以验证逆序清理。 */
        readSlotOpener: ((Int) -> ContextSlot)? = null,
    ) : AutoCloseable {
        // 参数校验先于资源创建，避免非法参数导致部分资源已分配。
        init {
            require(readSlotCount >= 1) { "readSlotCount must be at least 1" }
        }

        private val slots =
            startSlots(path, readSlotCount) { index ->
                readSlotOpener?.invoke(index) ?: ContextSlot(SlotKind.READ, index, path, READ_PRAGMAS)
            }

        private val writeSlot: ContextSlot get() = slots.write

        private val readSlots: List<ContextSlot> get() = slots.reads

        private val writeSemaphore = Semaphore(1)

        private val readSemaphores: List<Semaphore> = readSlots.map { Semaphore(1) }

        private val roundRobin = AtomicLong(0)

        /** 回调内嵌套检测：只在 Slot 固定线程上置位，跨协程不会误报。 */
        private val insideCallback = ThreadLocal.withInitial { false }

        /** 回调正文锁定：只在回调正文执行期间置位，供「回调内 close」具名拒绝。 */
        private val insideScopeBody = ThreadLocal.withInitial { false }

        /**
         * 单一同步边界（R2）：请求准入（closed 复查 + inFlight++）、停止准入、发布唯一关闭
         * 结果全部在这把锁内原子完成；同步 `close()` 与协程 `closeAsync()` 共用它。
         */
        private val protocolLock = Object()

        /** 关闭态；只在 [protocolLock] 内读写，保留 `AtomicBoolean` 供独立探针读取，`@Volatile` 语义由原子类提供。 */
        private val closed = AtomicBoolean(false)

        /** 在途阻塞 JDBC 计数；只在 [protocolLock] 内读写，close 等它真正归零。 */
        private var inFlight = 0

        /** 在途归零的挂起等待者；只在 [protocolLock] 内读写。 */
        private val drainWaiters = CopyOnWriteArrayList<CompletableDeferred<Unit>>()

        /** 唯一关闭协议；首个发起者创建，其余调用者复用同一份。 */
        private var closeProtocol: CloseProtocol? = null

        /** 全部 Slot 是否已成功释放（P1：只有全部成功才置位）。 */
        private var released = false

        /** 状态库文件路径（诊断用，不暴露连接）。 */
        fun databasePath(): Path = path

        /**
         * 写事务：唯一写 Slot 上 `autoCommit=false` 执行，成功提交，异常或取消回滚。
         *
         * R3 rev4 统一终结（见 [runBoundedTransaction]）：
         * - 成功 → commit 一次 → 恢复 autoCommit → IDLE；
         * - 业务异常 / 取消（含回调直接抛 `CancellationException`）→ rollback 一次，
         *   原异常或原取消对象为主，收尾失败只 `addSuppressed`；
         * - commit / rollback / 恢复失败 → 该 Slot 中毒且不再复用（健康 Slot 不受影响），
         *   物理连接在作用域失效的收尾边界尽力释放；
         * - commit 已尝试后绝不回滚，不把已落库内容伪装成回滚。
         *
         * 回调同步不可挂起；提交前检测到调用方已取消时走取消终结路径。
         */
        suspend fun <T> write(block: (RawTransactionScope) -> T): T =
            enter {
                writeSemaphore.withPermit {
                    val slot = writeSlot
                    // §8.3：取得 Slot 独占权之后再校验 poison，排队/后退请求不能沿用旧准入结论。
                    slot.checkPoison()
                    withScope<RawTransactionScope, T>(slot, { owner ->
                        val scopeSession = ScopeSession(slot, owner)
                        slot.currentScope = scopeSession
                        RawTransactionScope(slot, owner).apply { this.session = scopeSession }
                    }) { scope ->
                        runBoundedTransaction(slot, "write") { block(scope) }
                    }
                }
            }

        /** 只读上下文：绑定一个空闲读 Slot，显式事务包住回调，得到稳定 WAL 快照。 */
        suspend fun <T> read(block: (RawReadScope) -> T): T =
            enter {
                acquireReadSlot { slot ->
                    slot.checkPoison()
                    withScope<RawReadScope, T>(slot, { owner ->
                        val scopeSession = ScopeSession(slot, owner)
                        slot.currentScope = scopeSession
                        RawReadScope(slot, owner).apply { this.session = scopeSession }
                    }) { scope ->
                        runBoundedTransaction(slot, "read") { block(scope) }
                    }
                }
            }

        /**
         * 单条原始查询（无显式快照事务）：任选一个健康读 Slot，执行完立即归还。
         *
         * `mapper` 是公开用户回调，与 [write] / [read] 的回调一致地设置嵌套/关闭守卫，
         * `finally` 恢复，使同步/异步 close 在 mapper 内均具名拒绝而不自死锁。
         *
         * R2/R3 rev4：`sql` 统一过 [SqlBoundaryGuard]，独占权后再校验 poison，执行走受控连接。
         */
        suspend fun <T> queryRaw(
            sql: String,
            mapper: (java.sql.ResultSet) -> T,
        ): T =
            enter {
                acquireReadSlot { slot ->
                    withContext(slot.dispatcher) {
                        withUserCallback {
                            slot.checkPoison()
                            SqlBoundaryGuard.check(sql)
                            val scopeSession = ScopeSession(slot, Thread.currentThread())
                            slot.currentScope = scopeSession
                            try {
                                ContextSqliteErrors.map("raw query") {
                                    slot.scopedConnection(scopeSession).createStatement().use { statement ->
                                        statement.executeQuery(sql).use(mapper)
                                    }
                                }
                            } finally {
                                scopeSession.invalidate()
                                slot.clearCurrentScope()
                            }
                        }
                    }
                }
            }

        /** 单条原始更新（写 Slot）：CT04 自测与 CT05 起步用；业务写入必须走 [write]。 */
        suspend fun updateRaw(sql: String): Int =
            enter {
                writeSemaphore.withPermit {
                    val slot = writeSlot
                    withContext(slot.dispatcher) {
                        slot.checkPoison()
                        SqlBoundaryGuard.check(sql)
                        val scopeSession = ScopeSession(slot, Thread.currentThread())
                        slot.currentScope = scopeSession
                        try {
                            ContextSqliteErrors.map("raw update") {
                                slot.scopedConnection(scopeSession).createStatement().use { statement ->
                                    statement.executeUpdate(sql)
                                }
                            }
                        } finally {
                            scopeSession.invalidate()
                            slot.clearCurrentScope()
                        }
                    }
                }
            }

        /**
         * 事务作用域的统一执行与终结（R3 rev4）。
         *
         * - 成功：commit 一次，随后恢复 autoCommit 并转 IDLE；
         * - 业务异常 / 取消（含回调直接抛 `CancellationException`）：rollback 一次，
         *   原异常或原取消对象为主，安全收尾失败仅 `addSuppressed`，不改 `cause`；
         * - commit / rollback / 恢复失败：由 [ContextSlot] 内部 poison，此处不再重复终结；
         * - commit 已尝试后绝不回滚（避免把已落库内容伪装成回滚）。
         *
         * 回调同步不可挂起；提交前若检测到调用方已被取消，则按取消路径终结。
         */
        private suspend fun <T> runBoundedTransaction(
            slot: ContextSlot,
            what: String,
            block: () -> T,
        ): T {
            slot.beginTransaction()
            var result: Any? = null
            var primary: Throwable? = null
            var commitAttempted = false
            try {
                result = block()
                if (!currentCoroutineContext().isActive) {
                    primary = CancellationException("context $what cancelled before commit")
                } else {
                    commitAttempted = true
                    slot.commitTransaction()
                }
            } catch (failure: Throwable) {
                primary = failure
            }
            val failure = primary
            if (failure != null) {
                if (!commitAttempted && !slot.isPoisoned()) {
                    runCatching { slot.rollbackTransaction() }.exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }

        /**
         * 幂等异步关闭：并发调用共享同一次关闭结果。
         *
         * 顺序：置关闭态（新请求立即拒绝）→ 等在途阻塞 JDBC 归零 → 逆序关 ReadSlots → 关 WriteSlot。
         * 清理跑在 [NonCancellable] 内，协程取消不会抢先关闭被使用的连接。
         *
         * 若关闭已由前面的调用（同步或异步）发起，则本调用**等待**同一份完成结果，
         * 不提前返回；失败对所有等待者一致可见。
         */
        suspend fun closeAsync() {
            val (mine, protocol) = beginClose()
            if (!mine) {
                // 等待者不隔离取消：被取消时 `CancellationException` 原样传播，真实清理由发起者继续。
                protocol.await()
                return
            }
            try {
                withContext(NonCancellable) {
                    awaitDrained()
                    releaseAll()
                }
                // 成功结果必须在不可取消上下文中发布：本发起者被取消时，其它等待者仍拿到结果。
                withContext(NonCancellable) { protocol.complete(Unit) }
            } catch (failure: Throwable) {
                // 失败也必须发布到共享结果：无论等待方是同步还是异步，清理失败必须对全部等待者可见。
                withContext(NonCancellable) { protocol.completeExceptionally(failure) }
                throw failure
            }
            // 共享结果已发布后，若本发起方已被取消，取消原样向外传播（不影响其它等待者）。
            currentCoroutineContext().ensureActive()
        }

        /**
         * 同步关闭入口（`AutoCloseable`）：非协程宿主使用。
         *
         * 同样先等在途归零再关连接。若关闭已在进行中（由 `closeAsync` 发起或另一次同步
         * 调用发起），则**阻塞等待**同一完成结果，绝不提前返回；失败在该次调用中重抛。
         */
        override fun close() {
            val (mine, protocol) = beginClose()
            if (!mine) {
                protocol.awaitBlocking()
                return
            }
            // 清理责任不可被调用线程中断取消：等待在途归零期间若被 interrupt，
            // 仅记录中断标志并继续等待，随后照常 releaseAll()，完成后恢复中断标志。
            var interrupted = false
            try {
                interrupted = awaitDrainedBlocking()
                releaseAll()
            } catch (failure: Throwable) {
                // 清理失败：同样发布共享结果，后续同步/异步调用者必须观察到失败而不是永远阻塞。
                protocol.completeExceptionally(failure)
                if (interrupted) Thread.currentThread().interrupt()
                throw failure
            }
            protocol.complete(Unit)
            // 中断标志在所有真实资源释放完成后恢复，结果与真实资源终态一致。
            if (interrupted) Thread.currentThread().interrupt()
        }

        private suspend fun <S : RawScopeHandle, T> withScope(
            slot: ContextSlot,
            factory: (Thread) -> S,
            block: suspend (S) -> T,
        ): T =
            withContext(slot.dispatcher) {
                withUserCallback {
                    val scope = factory(Thread.currentThread())
                    try {
                        block(scope)
                    } finally {
                        scope.invalidate()
                        slot.clearCurrentScope()
                        // §8.3：中毒 Slot 在本次同步 JDBC 已退出、作用域已失效的收尾边界
                        // 尽力释放物理连接；不在 Slot 线程里 shutdown 并 await 自身。
                        slot.releasePoisonedConnection()
                    }
                }
            }

        /**
         * 统一用户回调守卫：任何由执行器交给调用方执行的回调（`write` / `read` 正文、
         * `queryRaw` 的 `mapper`）都必须在同一线程上置位嵌套/关闭守卫，`finally` 恢复。
         * 于是回调内发起 close 会被 [beginClose] 具名拒绝，而不是等待自身退出而自死锁。
         */
        private suspend fun <T> withUserCallback(block: suspend () -> T): T {
            val previous = insideCallback.get()
            insideCallback.set(true)
            try {
                val previousBody = insideScopeBody.get()
                insideScopeBody.set(true)
                try {
                    return block()
                } finally {
                    insideScopeBody.set(previousBody)
                }
            } finally {
                insideCallback.set(previous)
            }
        }

        /** 所有对外入口的统一闸门：准入与停止准入共用 [protocolLock]，关闭后拒绝。 */
        private suspend fun <T> enter(block: suspend () -> T): T {
            acquireRequest()?.let { throw it }
            try {
                return block()
            } finally {
                releaseRequest()
            }
        }

        /**
         * 请求准入：检查关闭态并增加在途计数，二者在同一次 [protocolLock] 内完成。
         *
         * 于是「关闭看到在途为 0 并释放」与「已准入请求再增计数」不可能交错：关闭一旦把
         * [closed] 置真并发布结果，本方法在同一锁下看到的必是新的 closed 值。
         *
         * @return 需要抛出的具名异常（关闭态或回调内嵌套），准入成功返回 null。
         */
        private fun acquireRequest(): Throwable? =
            synchronized(protocolLock) {
                when {
                    closed.get() -> closedException()
                    insideCallback.get() -> nestedOperation()
                    else -> {
                        inFlight++
                        null
                    }
                }
            }

        private fun releaseRequest() {
            synchronized(protocolLock) {
                if (--inFlight == 0) {
                    protocolLock.notifyAll()
                    drainWaiters.forEach { it.complete(Unit) }
                    drainWaiters.clear()
                }
            }
        }

        /**
         * 取得一个健康读 Slot 的独占权。
         *
         * R3 rev4：中毒读 Slot 只影响它自己（不自动关闭健康 Slot），分配时直接跳过；
         * 全部读 Slot 都中毒时由 [ContextSlot.checkPoison] 具名拒绝。
         */
        private suspend fun <T> acquireReadSlot(block: suspend (ContextSlot) -> T): T {
            if (readSlots.size == 1) {
                return readSemaphores[0].withPermit {
                    readSlots[0].checkPoison()
                    block(readSlots[0])
                }
            }
            val start = (roundRobin.getAndIncrement() % readSlots.size).toInt()
            for (offset in readSlots.indices) {
                val index = (start + offset) % readSlots.size
                if (readSemaphores[index].tryAcquire()) {
                    if (readSlots[index].isPoisoned()) {
                        readSemaphores[index].release()
                        continue
                    }
                    try {
                        return block(readSlots[index])
                    } finally {
                        readSemaphores[index].release()
                    }
                }
            }
            // 全部忙碌：在起点排队等待，避免自旋；若该 Slot 已中毒则具名拒绝。
            return readSemaphores[start].withPermit {
                readSlots[start].checkPoison()
                block(readSlots[start])
            }
        }

        /**
         * 发起或加入唯一一次关闭。
         *
         * 在 [protocolLock] 内完成「封闭新请求 + 记录唯一协议」，两种关闭入口（同步/异步）
         * 完全共用这条路径，不再有 Mutex 与 synchronized 两条互不排斥的通道。
         *
         * @return (本调用是否为发起者, 唯一关闭协议)
         */
        private fun beginClose(): Pair<Boolean, CloseProtocol> {
            // 回调内部发起关闭会等待自身退出而死锁：具名拒绝（免死锁路径）。
            if (insideScopeBody.get()) throw closeInsideCallback()
            synchronized(protocolLock) {
                closeProtocol?.let { return false to it }
                closed.set(true)
                val protocol = CloseProtocol()
                closeProtocol = protocol
                return true to protocol
            }
        }

        /**
         * 逆序释放全部 Slot，带有限内部重试（P1，设计 §4/§9）。
         *
         * - 同一轮次最多 [MAX_RELEASE_ATTEMPTS] 次尝试（**次数上限，不是耗时上限**），只重试失败的 Slot；
         * - 每次尝试都经 `slot.close()`（停止接单入口），不绕过 `disposed`；
         * - 首次失败保留在 [IdentityHashMap]；后续失败 `addSuppressed` 且跳过同一实例（无自我 suppressed）；
         * - `released` 只在**全部成功**后置位；轮次结束仍有失败 → 共享失败（主失败为首次原因）。
         */
        private fun releaseAll() {
            synchronized(protocolLock) {
                if (released) return
            }

            val slots = readSlots.asReversed() + listOf(writeSlot)
            val pending = slots.toMutableList()
            val firstFailures = IdentityHashMap<ContextSlot, Throwable>()

            for (attempt in 1..MAX_RELEASE_ATTEMPTS) {
                val stillFailing = mutableListOf<ContextSlot>()
                pending.forEach { slot ->
                    val failure = runCatching { slot.close() }.exceptionOrNull()
                    if (failure == null) {
                        firstFailures.remove(slot)
                    } else {
                        val first = firstFailures[slot]
                        if (first == null) {
                            firstFailures[slot] = failure
                        } else if (first !== failure && first.suppressed.none { it === failure }) {
                            first.addSuppressed(failure)
                        }
                        stillFailing += slot
                    }
                }
                pending.clear()
                pending += stillFailing
                if (pending.isEmpty()) break
            }

            synchronized(protocolLock) {
                if (pending.isEmpty()) released = true
            }

            if (pending.isNotEmpty()) {
                val primary = firstFailures.values.first()
                firstFailures.values.drop(1).forEach { other ->
                    if (other !== primary && primary.suppressed.none { it === other }) primary.addSuppressed(other)
                }
                throw ContextPersistenceException(
                    ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE,
                    "releasing context database resources failed for ${pending.size} slot(s)",
                    primary,
                )
            }
        }

        /** 挂起等待在途归零；不占用任何线程。 */
        private suspend fun awaitDrained() {
            while (true) {
                val waiter = CompletableDeferred<Unit>()
                synchronized(protocolLock) {
                    if (inFlight == 0) return
                    drainWaiters += waiter
                }
                waiter.await()
            }
        }

        /**
         * 阻塞等待在途归零。清理责任不可被中断取消：等待中若被 [Thread.interrupt]，
         * 记录中断标志后继续等待真实归零，绝不在仍有在途 JDBC 时抢关连接；
         * 中断标志由调用者在清理完成后恢复。
         *
         * @return 本次等待期间是否被中断
         */
        private fun awaitDrainedBlocking(): Boolean {
            var interrupted = false
            synchronized(protocolLock) {
                while (inFlight > 0) {
                    try {
                        protocolLock.wait()
                    } catch (failure: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            return interrupted
        }

        /** 测试/诊断辅助：某 Slot 的 PRAGMA 实值。 */
        internal fun pragma(
            kind: SlotKind,
            index: Int,
            name: String,
        ): String = slotFor(kind, index).pragma(name)

        /** 测试/诊断辅助：某 Slot 的物理连接身份。 */
        internal fun connectionIdentity(
            kind: SlotKind,
            index: Int,
        ): Int = slotFor(kind, index).connectionIdentity()

        /** 同模块测试用：按类型/索引取 Slot（用于所有权与线程清理的最小注入）。 */
        internal fun slotForTesting(
            kind: SlotKind,
            index: Int = 0,
        ): ContextSlot = slotFor(kind, index)

        internal fun readSlotCount(): Int = readSlots.size

        internal fun writeSlotAutoCommit(): Boolean = writeSlot.autoCommit()

        private fun slotFor(
            kind: SlotKind,
            index: Int,
        ): ContextSlot =
            when (kind) {
                SlotKind.WRITE -> writeSlot
                SlotKind.READ -> readSlots.getOrNull(index) ?: throw persistenceInvalid("read slot $index does not exist")
            }

        companion object {
            /** 同一清理轮次内的最大尝试次数（次数上限，非耗时上限）。 */
            const val MAX_RELEASE_ATTEMPTS: Int = 2

            /** 关闭后请求的具名异常。 */
            fun closedException(): ContextLifecycleException =
                ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, "context database is closed")

            /** 回调内部发起关闭：拒绝以避免等待自身退出造成死锁。 */
            fun closeInsideCallback(): ContextLifecycleException =
                ContextLifecycleException(
                    ContextLifecycleErrorCode.CLOSED,
                    "close() cannot be called from inside a read/write callback; let the callback return first",
                )

            /** 嵌套事务/回调内重复进入。 */
            fun nestedOperation(): ContextPersistenceException = persistenceInvalid("nested read/write inside a callback is not supported")

            /** 上下文逃逸出回调后被使用。 */
            fun escapedScope(
                kind: String,
                what: String,
            ): ContextPersistenceException = persistenceInvalid("$kind scope used after its callback returned ($what)")

            /** 跨线程使用上下文。 */
            fun crossThread(
                kind: String,
                what: String,
            ): ContextPersistenceException = persistenceInvalid("$kind scope used from a thread that does not own the slot ($what)")

            fun persistenceInvalid(message: String): ContextPersistenceException =
                ContextPersistenceException(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, message)
        }
    }

/**
 * 唯一关闭协议（R2）：同步与异步关闭共享同一份完成结果。
 *
 * [complete] / [completeExceptionally] 由发起者在清理结束后调用；其余调用者等待。
 * 同步等待用 [awaitBlocking]（阻塞，供 [ContextDatabaseExecutor.close]），协程等待用
 * [await]（挂起，供 [ContextDatabaseExecutor.closeAsync]）。失败对所有等待者可见。
 */
internal class CloseProtocol {
    private val completion = CompletableDeferred<Unit>()
    private val monitor = Object()

    @Volatile
    private var failure: Throwable? = null

    private var finished = false

    suspend fun await() {
        completion.await()
    }

    fun awaitBlocking() {
        // 等待者同样不得因中断而错过真实完成结果：记录中断后继续等待，
        // 完成后先恢复中断标志，再如实抛出共享失败。
        var interrupted = false
        var error: Throwable? = null
        synchronized(monitor) {
            while (!finished) {
                try {
                    monitor.wait()
                } catch (failure: InterruptedException) {
                    interrupted = true
                }
            }
            error = failure
        }
        if (interrupted) Thread.currentThread().interrupt()
        error?.let { throw it }
    }

    fun complete(outcome: Unit) {
        synchronized(monitor) {
            finished = true
            monitor.notifyAll()
        }
        runCatching { completion.complete(outcome) }
    }

    fun completeExceptionally(failure: Throwable) {
        this.failure = failure
        synchronized(monitor) {
            finished = true
            monitor.notifyAll()
        }
        completion.completeExceptionally(failure)
    }
}

/** 作用域失效的统一入口：回调返回后作用域立即不可用。 */
internal interface RawScopeHandle {
    fun invalidate()
}

/** 启动资源流程的结果：1 个写 Slot + N 个已打开的读 Slot。 */
internal class StartupSlots(
    val write: ContextSlot,
    val reads: List<ContextSlot>,
)

/**
 * 启动资源登记流程（R1）：逐个打开并登记，任一环节失败逆序释放全部已取得资源。
 *
 * 顺序固定：参数校验由调用方先行完成；先开写 Slot 并立即登记，再逐个开读 Slot 并登记；
 * 最后对每个实际连接依次做版本/结构校验与 PRAGMA 应用。任一步失败：按登记逆序逐个
 * 尽力 close，清理异常以 [Throwable.addSuppressed] 附到主失败，绝不中途跳过后续资源。
 *
 * [openRead] 是唯一可替换点：生产路径固定打开真实连接，测试用它让第 N 个 Slot 失败，
 * 从而验证「部分初始化失败 → 全部已取得资源逆序释放」这条路径真的被执行。
 */
internal fun startSlots(
    path: Path,
    readSlotCount: Int,
    openRead: (Int) -> ContextSlot,
): StartupSlots {
    val acquired = mutableListOf<ContextSlot>()

    fun releaseAll(primary: Throwable) {
        acquired.asReversed().forEach { slot ->
            runCatching { slot.close() }.exceptionOrNull()?.let(primary::addSuppressed)
        }
    }
    try {
        acquired += ContextSlot(SlotKind.WRITE, 0, path, WRITE_PRAGMAS)
        repeat(readSlotCount) { index ->
            acquired += openRead(index)
        }
        // 先统一验证每个实际连接，最后才应用持久化 PRAGMA；不信任前一次检查作为永久保证。
        acquired.forEach { it.validateSchemaVersion(ContextDatabaseInitializer.SCHEMA_VERSION) }
        acquired.forEach { it.validateMissingObjects() }
        acquired.forEach { it.applyPragmas() }
        return StartupSlots(acquired.first(), acquired.drop(1))
    } catch (failure: Throwable) {
        releaseAll(failure)
        throw failure
    }
}
