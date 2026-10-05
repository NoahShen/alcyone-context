package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.Vfs
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsException
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
 * **本轮（T18 第一片）已经做到的**：配置校验与组装、挂载身份持久化与冲突拒绝、状态库独占、
 * 初始化失败 / 取消时的资源清理。事件照 T14 在提交后进程内通知。
 *
 * **本轮没有做到的**（第二片）：公共流式读取、`closeGracePeriod` 等待期与 closing 状态、遗漏流的回收。
 * 当前 [close] 只是「把已建的资源按顺序释放掉，幂等」，**不等在途操作**：
 * 正在跑的调用可能拿到 `STATE_ERROR` 或 `CLOSED`，也不保证流被收回。详见使用说明。
 */
class AlcyoneVfs private constructor(
    /** 组装好的 Core 实现，直接就是公共 [Vfs] 接口。 */
    private val delegate: Vfs,
    private val notifier: AsyncEventNotifier,
    /** 按创建的逆序释放：独占锁、通知器、各块盘、状态库连接 —— 顺序就是创建顺序的逆序。 */
    private val resources: List<AutoCloseable>,
) : Vfs by delegate,
    AutoCloseable {
    private var closed = false

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

    /**
     * 释放已建的资源，幂等：重复调用是空操作。
     *
     * 顺序：通知器 → 各块盘 → 状态库连接 → 独占锁。锁**最后**放，放完才算没人占着这个状态库。
     * 某一步失败不阻止后面继续尝试，最后把第一个失败抛出来，其余挂在 `suppressed` 上。
     *
     * **本轮不等待在途操作**（第二片才实现等待期）：关闭调用返回之后，别的协程里正在跑的
     * `write` / `delete` 可能失败，报 `STATE_ERROR` 或 `CLOSED`；已提交的 Node 与事件留在库里，重开还在。
     */
    override fun close() {
        if (closed) return
        closed = true
        val failures = mutableListOf<Throwable>()
        resources.forEach { resource -> runCatching { resource.close() }.exceptionOrNull()?.let { failures += it } }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw VfsException(VfsErrorCode.STATE_ERROR, "VFS instance could not be closed cleanly").apply { initCause(first) }
        }
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
        ): AlcyoneVfs {
            val resolved = config.resolve()
            // 已取得的资源按顺序追加，失败时逆序释放；锁在最前面，所以它一定最后被放掉。
            val acquired = mutableListOf<AutoCloseable>()
            try {
                acquired += StateInstanceLock.acquire(resolved.lockPath)
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
                            events = EventFactory(),
                        ),
                    notifier = notifier,
                    resources = acquired.reversed().toList(),
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
