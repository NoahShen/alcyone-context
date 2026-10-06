package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.DeleteOptions
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeInfo
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.ReadOptions
import com.github.noahshen.alcyone.context.vfs.StatOptions
import com.github.noahshen.alcyone.context.vfs.Vfs
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
import com.github.noahshen.alcyone.context.vfs.VfsStreamResult
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.WriteOptions
import com.github.noahshen.alcyone.context.vfs.core.DefaultVfs
import com.github.noahshen.alcyone.context.vfs.core.event.AsyncEventNotifier
import com.github.noahshen.alcyone.context.vfs.core.event.EventFactory
import com.github.noahshen.alcyone.context.vfs.core.event.EventPipeline
import com.github.noahshen.alcyone.context.vfs.core.event.VfsEventConsumer
import com.github.noahshen.alcyone.context.vfs.core.operation.CapabilitySnapshot
import com.github.noahshen.alcyone.context.vfs.core.registry.NodeRegistry
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteMetadataRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteMountRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteNodeRepository
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.SqliteUnitOfWork
import com.github.noahshen.alcyone.context.vfs.persistence.sqldelight.VfsStateDatabase
import com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * 宿主拿到的 VFS 实例（T18）：`create(config)` 之后直接用 [Vfs] 的方法，最后 `close()`。
 *
 * 例：
 *
 * ```
 * val vfs = AlcyoneVfs.create(
 *     VfsRuntimeConfig(
 *         stateDatabase = Path.of("data/state.db"),
 *         namespaces = setOf("resources"),
 *         mounts = listOf(MountConfig(VfsPath.parse("/resources/docs"), "docs", Path.of("/data/docs"))),
 *     ),
 * )
 * try {
 *     vfs.write(VfsUri.parse("alcyone://resources/docs/a.txt"), "hi".toByteArray())
 * } finally {
 *     vfs.close()
 * }
 * ```
 *
 * **宿主只提交配置**：Operator、JDBC 连接、Repository、各 Manager 都在这里创建并持有，宿主不用管也不用关。
 *
 * **关闭要做的事**（T18 §2.5）：第一次 [close] 把状态转成 closing，新操作直接报 `CLOSED`；
 * 已经在跑的操作在 [VfsRuntimeConfig.closeGracePeriod] 里可以自己跑完，到点还没完的会被**请求取消**；
 * 然后按「流 → 通知器 → 各块盘 → 状态库 → 独占锁」逐个释放。重复 [close] 共享同一次结果，不会释放两遍。
 *
 * **等待期不是强杀期限**：卡在 native 调用里不响应取消的操作不会被强行弄死，它可能拿到 `STATE_ERROR`。
 * 这也是为什么本轮只取消、不加硬超时回收。
 */
class AlcyoneVfs private constructor(
    /** 组装好的 Core 实现；本类只在生命周期那一层包一层，其余方法直接转给它。 */
    private val delegate: DefaultVfs,
    private val notifier: AsyncEventNotifier,
    /** 释放顺序：通知器 → 各块盘 → 状态库连接 → 独占锁。锁放最后，放完才算没人占着这个库。 */
    private val resources: List<AutoCloseable>,
    /** 关闭时给在途操作的自助时间；到点就请求取消。 */
    private val closeGracePeriod: Duration,
    /** `openStream` 的总量上限；`null` 表示不设总量上限。 */
    private val streamTotalLimit: Long?,
) : Vfs {
    private enum class Lifecycle {
        OPEN,
        CLOSING,
        CLOSED,
    }

    @Volatile
    private var lifecycle = Lifecycle.OPEN

    /** 关闭的「并发闸门」：登记在途操作和开始关闭都走它，两边不会撞出半截状态。 */
    private val gate = Mutex()

    /** 第一次 close 放进去的完成信号；后来的 close 只是等它。 */
    private var closing: CompletableDeferred<Unit>? = null

    /** 正在跑的公共操作。每条记录把「取消谁」和「等谁」分成两件事，见 [InFlightOp]。 */
    private val inFlight = ConcurrentHashMap.newKeySet<InFlightOp>()

    /**
     * 一条在途记录。为什么要分成两个字段，而不是只放一个 Job：
     *
     * - [done] 是「这次调用彻底结束」的信号，**只能**由调用自己的 finally 完成。
     *   close() 只 `await()` 它，绝不去 cancel 它——用 Job 的话，`cancel()` 会让它立刻变成已完成，
     *   等待就成了空等，数据库在清理还没跑完时就被关掉了。
     * - [work] 是「这次调用真正在干活的那个子协程」，close() 的取消请求发给它。
     *   它晚一点才有值（要在 coroutineScope 里拿到自己的 Job），所以用 `CompletableDeferred` 兜一下：
     *   关闭来得比赋值早时，[InFlightOp.cancelWork] 会记住这笔账，等 Job 一到位就补上取消。
     */
    private class InFlightOp {
        /** 这次调用自己的子协程；还在建时是 null，建好后立刻填上。 */
        private var work: Job? = null

        /** 关闭已经请求过取消、但 work 还没到位时记下来，等 work 填上时补一次。 */
        private var cancelPending: CancellationException? = null

        /** 彻底结束的信号（含失败清理）。只有调用自己的 finally 能完成它，外部 cancel 不了。 */
        val done = CompletableDeferred<Unit>()

        /** 把这次调用的子协程挂上来；如果之前已经请求过取消，当场补发。 */
        fun attachWork(job: Job) {
            work = job
            cancelPending?.let { job.cancel(it) }
        }

        /** 请求取消这次工作。[job] 是这次调用自己的子协程，不是宿主的父 Job。 */
        fun cancelWork(reason: CancellationException) {
            val target = work
            if (target == null) {
                cancelPending = reason
                return
            }
            target.cancel(reason)
        }
    }

/** 已经交到调用者手上的流。调用者忘了关的，close() 替它收。 */
    private val openStreams = ConcurrentHashMap.newKeySet<VfsStreamResult>()

    /** 同模块测试用的挂钩：公共操作真正开跑之前调一次（参数是操作名）。生产代码不设置。 */
    internal var beforeOperation: (suspend (String) -> Unit)? = null

    /** 同模块测试用的挂钩：`openStream` 已经拿到流、还没交给调用方时调一次，用来制造取消窗口。 */
    internal var streamHandover: (suspend (VfsStreamResult) -> Unit)? = null

    /** 同模块测试用的诊断：Runtime 现在还记着几条没关的流。调用方自己关了的流不计入。 */
    internal val trackedStreamCount: Int get() = openStreams.size

    /** 同模块测试用的挂钩：关闭走到「发完取消、开始等在途收尾」这一刻调一次。生产代码不设置。 */
    internal var beforeWaitingForInFlight: (suspend () -> Unit)? = null

    /**
     * 订阅已提交的事件（T14 的有界进程内通知）。
     *
     * 例：宿主想记审计日志，`vfs.subscribe { event -> log(event.type) }`。
     * Consumer 抛错只被记录，不影响已提交的文件操作。
     *
     * 首版不保证回放与可靠投递：队列满就丢通知，事件日志里那条还在。
     */
    fun subscribe(consumer: (VfsEvent) -> Unit) {
        notifier.subscribe(VfsEventConsumer(consumer))
    }

    override suspend fun read(
        uri: VfsUri,
        options: ReadOptions,
    ): ByteArray = operation("read") { delegate.read(uri, options) }

    /**
     * 流式读取：和 [read] 一样登记在途，**多一样**是把流登记到 [openStreams]。
     *
     * 流交给调用方之后就不算在途操作了（它可能在调用方手里读很久，不该占着关闭的等待期），
     * 但 Runtime 记着它：`close()` 的时候还没关的流会被收回来。
     *
     * 调用方自己把流关了的话，Core 在关流那一刻回调 `onClose`，我们就把这条摘掉——
     * 不然长跑的实例会越攒越多没人要的流。
     *
     * 例：取消恰好落在「流已经建好、还没交给调用方」这一刻，流当场关掉，不会变成没人管的句柄。
     */
    override suspend fun openStream(
        uri: VfsUri,
        options: VfsStreamOptions,
    ): VfsStreamResult {
        var opened: VfsStreamResult? = null
        return operation(
            name = "openStream",
            // 只有 operation 真的正常返回才算交接成功；任何失败都走到这里，把没交出去的流当场关掉。
            // 清理失败挂 suppressed，原异常照抛；这段清理跑在 done.complete 之前，close() 会等它。
            onFailure = { failure ->
                runCatching { opened?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            },
        ) {
            delegate
                // 关流时再读 opened：那时它已经指向真正加进集合的那个对象（建流那一刻它还是 null）。
                .openStream(uri, options) { opened?.let { openStreams.remove(it) } }
                .also {
                    opened = it
                    openStreams.add(it)
                    streamHandover?.invoke(it)
                }
        }
    }

    override suspend fun write(
        uri: VfsUri,
        content: ByteArray,
        options: WriteOptions,
    ): NodeInfo = operation("write") { delegate.write(uri, content, options) }

    override suspend fun stat(
        uri: VfsUri,
        options: StatOptions,
    ): NodeInfo = operation("stat") { delegate.stat(uri, options) }

    override suspend fun getNode(id: NodeId): NodeInfo = operation("getNode") { delegate.getNode(id) }

    override suspend fun list(uri: VfsUri) = operation("list") { delegate.list(uri) }

    override suspend fun move(
        source: VfsUri,
        target: VfsUri,
    ): NodeInfo = operation("move") { delegate.move(source, target) }

    override suspend fun delete(
        uri: VfsUri,
        options: DeleteOptions,
    ) = operation("delete") { delegate.delete(uri, options) }

    override suspend fun getMetadata(id: NodeId): NodeMetadata = operation("getMetadata") { delegate.getMetadata(id) }

    override suspend fun setMetadata(
        id: NodeId,
        metadata: NodeMetadata,
    ) = operation("setMetadata") { delegate.setMetadata(id, metadata) }

    /**
     * 所有公共操作的同一个入口：先登记在途，再跑真正的逻辑。
     *
     * 跑在 [coroutineScope] 里，也就是调用方协程的**子**协程：close 要取消的是「这一次调用」，
     * 顺着取消会连带把宿主自己的父 Job 弄挂，所以不碰它。
     */
    private suspend fun <T> operation(
        name: String,
        onFailure: (suspend (Throwable) -> Unit)? = null,
        block: suspend () -> T,
    ): T {
        val op = admit(name)
        try {
            return coroutineScope {
                op.attachWork(currentCoroutineContext().job)
                beforeOperation?.invoke(name)
                block()
            }
        } catch (failure: Throwable) {
            // 走到这里就是这次调用没成功。清理放在 done.complete 之前：期间 close() 还得等这条记录。
            if (onFailure != null) {
                withContext(NonCancellable) { onFailure(failure) }
            }
            throw failure
        } finally {
            // 交接完成或失败清理都做完了，才算这次调用真的结束；close() 的等以这个为准。
            op.done.complete(Unit)
            inFlight.remove(op)
        }
    }

    /** 登记在途；已经开始关闭就直接拒。和 [beginClose] 共用一把锁，所以不会有「登记与关闭交错」的窗口。 */
    private suspend fun admit(name: String): InFlightOp =
        gate.withLock {
            if (lifecycle != Lifecycle.OPEN) {
                throw VfsException(VfsErrorCode.CLOSED, "The VFS instance is closing or closed; '$name' was refused")
            }
            InFlightOp().also { inFlight.add(it) }
        }

    /**
     * 关闭这个实例：先转 closing，再等在途操作，然后逐个释放资源。幂等，并发调用共享同一次结果。
     *
     * 顺序：closing → 等 [VfsRuntimeConfig.closeGracePeriod] → 给还没完的操作发取消 →
     * 流 → 通知器 → 各块盘 → 状态库 → 独占锁。某一步失败不阻止后面继续尝试，
     * 第一个失败抛给调用方，其余挂在 `suppressed` 上。
     *
     * 清理跑在 [NonCancellable] 里：调用方正好在取消时，清理也不会被跟着取消掉。
     * 关锁放最后，放完才算没人占着这个状态库；已经提交过的 Node 与事件留在库里，重开还在。
     *
     * 挂起函数：等待期里要挂起，不能承诺 `AutoCloseable` 那种阻塞语义，示例用 `try/finally`。
     */
    suspend fun close() {
        // 先记下这是不是从 Consumer 回调里发起的。必须放在第一个挂起点之前：
        // 分发协程正卡在回调上等着这个 close 返回，再去等它就是自己等自己。
        val fromConsumer = notifier.isInsideConsumer()
        val (mine, completion) = beginClose()
        if (!mine) {
            // 回调里的这次 close 只是「提交了关闭请求」：已经在关的那一次会把资源收干净，
            // 所以这里直接返回，不去等包含自己的那次收尾。
            if (!fromConsumer) completion.await() // 普通的并发关闭：共享同一次结果，包括失败
            return
        }
        try {
            withContext(NonCancellable) { releaseEverything() }
            completion.complete(Unit)
        } catch (failure: Throwable) {
            completion.completeExceptionally(failure)
            throw failure
        }
    }

    /** 第一个进来的开始关，并把完成信号放进 [closing]；后来的只拿信号。 */
    private suspend fun beginClose(): Pair<Boolean, CompletableDeferred<Unit>> =
        gate.withLock {
            closing?.let { return@withLock false to it }
            lifecycle = Lifecycle.CLOSING
            true to CompletableDeferred<Unit>().also { closing = it }
        }

    /** 等待期 → 请求取消 → 逐个释放。必须在 [NonCancellable] 里调。 */
    private suspend fun releaseEverything() {
        awaitGracePeriod()
        cancelInFlight()
        // 到这里取消已经发出去了，接下来是等收尾。测试用这个钩子确认「close 确实走到等待阶段」，
        // 而不是「close 还没被调度」——不然 isCompleted=false 这种断言会假阳性。
        beforeWaitingForInFlight?.invoke()
        // 取消只是「请求停止」：等它们真的收完（含 NonCancellable 里的回滚），再去关数据库和放锁。
        // 等的是 done，不是被取消的那个 Job——done 只有调用自己清理完才会完成，没法被提前弄完。
        inFlight.toList().forEach { it.done.await() }

        val failures = mutableListOf<Throwable>()
        // 流先关：它们还指着块盘上的文件句柄，块盘关了之后就没人能再收它们了。
        openStreams.toList().forEach { stream ->
            runCatching { stream.close() }.exceptionOrNull()?.let { failures += it }
        }
        openStreams.clear()
        resources.forEach { resource ->
            runCatching { resource.close() }.exceptionOrNull()?.let { failures += it }
        }
        lifecycle = Lifecycle.CLOSED

        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw VfsException(VfsErrorCode.STATE_ERROR, "VFS instance could not be closed cleanly").apply { initCause(first) }
        }
    }

    /** 给在途操作 [closeGracePeriod] 的自助时间。已经有操作没跑完才会真的等。 */
    private suspend fun awaitGracePeriod() {
        val waiting = inFlight.toList()
        if (waiting.isEmpty()) return
        withTimeoutOrNull(closeGracePeriod) { waiting.forEach { it.done.await() } }
    }

    /**
     * 等待期到了还在跑的，发**协作取消**：不打断宿主自己的父 Job，也不试图强杀卡在 native 里的线程。
     * 卡着不动的那个可能拿到 `STATE_ERROR`，这是本轮明确的边界。
     */
    private fun cancelInFlight() {
        val reason = CancellationException("the VFS instance is closing after its grace period")
        inFlight.forEach { op -> op.cancelWork(reason) }
    }

    /** 组装用的内部回调：只在同模块测试里用来卡住 / 失败某个时刻，不对外开挂载开关。 */
    internal fun interface AssemblyProbe {
        /**
         * 所有 Adapter 与状态库都已经建好、Core 接线完成、但实例还没交给调用方时调一次。
         *
         * 例：在这里抛错，用例就能断言「已取得的资源都被释放了、原始异常还在、之后还能再 create 一次」。
         */
        suspend fun beforeReturn(assembly: Assembly)
    }

    /** 已经拿到手但还没交出去的资源；只给诊断与测试断言用。 */
    internal class Assembly(
        val state: VfsStateDatabase,
        val notifier: AsyncEventNotifier,
        val storages: List<LocalFsStorage>,
    )

    companion object {
        /**
         * 按配置创建可用实例。**宿主的唯一入口**：只提交配置，不管内部怎么组装。
         *
         * 顺序是有讲究的（每一步都尽量在拿到资源之前把能拒的拒掉）：
         *
         * 1. 校验配置：命名空间、挂载逻辑结构、物理根规范化与重叠、状态库路径（非法 → 什么都不创建）；
         * 2. **拿独占锁**，失败报 `CONFLICT`——排在开库之前，所以第二个实例连库都碰不到；
         * 3. 打开 / 升级状态库（含 T18 的 mount 表迁移）；
         * 4. **核对挂载映射**：身份变了、挂载被删、命名空间被删、旧行没有物理身份 → `CONFLICT`，
         *    这时候存储 Adapter 还没建；
         * 5. 一块一块打开存储 Adapter，组装 Registry / DefaultVfs / EventPipeline；
         * 6. 把实例交给调用方。
         *
         * 中途失败或被取消：**已经取得的资源按逆序释放**，原始异常原样抛出，清理失败挂在 `suppressed` 上。
         * 不会为了「回滚初始化」删掉用户已有的文件或状态库——已提交的 Node 和事件就是事实。
         *
         * 例：第二个挂载的目录在启动前被删了，`create` 报 `INVALID_ARGUMENT`，第一个 Adapter 与状态库都被释放，
         * 把目录补回来之后同一个配置还能再建一次。
         */
        suspend fun create(config: VfsRuntimeConfig): AlcyoneVfs = create(config, AssemblyProbe { })

        /** 同模块测试用的重载：多一个 [probe] 在组装完成、交出实例之前卡一下。 */
        internal suspend fun create(
            config: VfsRuntimeConfig,
            probe: AssemblyProbe,
            /** 同模块测试用的注入点：额外塞进释放列表的资源（用例会塞一个 close 就报错的）。 */
            extraResources: List<AutoCloseable> = emptyList(),
        ): AlcyoneVfs {
            val resolved = config.resolve()
            // 已取得的资源按顺序追加，失败时逆序释放；锁在最前面，所以它一定最后被放掉。
            val acquired = mutableListOf<AutoCloseable>()
            try {
                val lock = StateInstanceLock.acquire(resolved.lockPath).also { acquired += it }
                val state = VfsStateDatabase.file(resolved.databasePath).also { acquired += it }
                val nodes = SqliteNodeRepository(state)
                val mounts = SqliteMountRepository(state)
                // 共享串行边界：Registry、DefaultVfs、EventPipeline 用同一个实例，锁不可重入，不要再套。
                val boundary = StateBoundary()
                // 冲突检查在开 Adapter 之前：配置对不上一块盘都不会白开。
                reconcileMounts(resolved, boundary, nodes, mounts)

                val notifier = AsyncEventNotifier(resolved.eventBufferCapacity).also { acquired += it }
                val storages = openStorages(resolved, acquired)
                probe.beforeReturn(Assembly(state, notifier, storages))

                val byKey = storages.associateBy { storage -> storage.root.toString() }
                val lookups = resolved.roots.mapValues { (_, root) -> byKey.getValue(root.toString()) }
                val storagesByKey: (String) -> Storage? = { key -> lookups[key] }
                return AlcyoneVfs(
                    delegate =
                        DefaultVfs(
                            router = resolved.router,
                            capabilities = CapabilitySnapshot.of(lookups.mapValues { (_, storage) -> storage.capabilities() }),
                            registry = NodeRegistry(resolved.router, nodes, storagesByKey, boundary),
                            nodes = nodes,
                            metadata = SqliteMetadataRepository(state),
                            storages = storagesByKey,
                            boundary = boundary,
                            pipeline = EventPipeline(boundary, SqliteUnitOfWork(state), notifier),
                            limits = resolved.limits,
                            streamTotalLimit = resolved.streamTotalLimit,
                            events = EventFactory(),
                        ),
                    notifier = notifier,
                    // 释放顺序：注入的 → 通知器 → 各块盘 → 状态库 → 独占锁（锁最后放）。
                    resources = extraResources + notifier + storages + listOf(state, lock),
                    closeGracePeriod = resolved.closeGracePeriod,
                    streamTotalLimit = resolved.streamTotalLimit,
                )
            } catch (failure: Throwable) {
                releaseOnFailure(acquired, failure)
                throw failure
            }
        }

        /** 一块一块打开 Adapter；每成功一块就登记一块，失败时已开的都被释放。 */
        private suspend fun openStorages(
            resolved: ResolvedConfig,
            acquired: MutableList<AutoCloseable>,
        ): List<LocalFsStorage> =
            resolved.roots.map { (_, root) ->
                LocalFsStorage.create(root).also { acquired += it }
            }

        /**
         * 初始化失败 / 取消时的清理：逆序释放已取得的资源，**保留原始异常**。
         *
         * 清理在 [NonCancellable] 里跑：调用方正好在取消时，清理不能跟着被取消掉，
         * 否则资源就没人收了。清理自己的失败进 `suppressed`，不顶掉原始异常。
         */
        private suspend fun releaseOnFailure(
            acquired: List<AutoCloseable>,
            failure: Throwable,
        ) {
            withContext(NonCancellable) {
                acquired.asReversed().forEach { resource ->
                    runCatching { resource.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                }
            }
        }
    }
}
