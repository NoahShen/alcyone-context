package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.event.AsyncEventNotifier
import com.github.noahshen.alcyone.context.vfs.core.event.EventFactory
import com.github.noahshen.alcyone.context.vfs.core.event.EventPipeline
import com.github.noahshen.alcyone.context.vfs.core.operation.CapabilitySnapshot
import com.github.noahshen.alcyone.context.vfs.core.registry.NodeRegistry
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.InMemoryNodeRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.transaction.FakeStateUnitOfWork
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * Core 单测用的组装：内存状态库 + 内存盘 + 路由 + 预检能力表 + Registry + EventPipeline + [DefaultVfs]。
 *
 * 盘用 [StorageFakeImpl]——它记下每次调用并支持故障注入，所以「有没有问过后端」「有没有动手」都能断言。
 * 状态提交走 [FakeStateUnitOfWork]：写时复制，能证明回滚，也能注入状态 / 事件写失败。
 *
 * **两个状态仓库是有意分开的**：[nodes] 是自动提交那份（Registry 查询用），
 * 写操作提交进去的是 [uow]。所以断言写入结果要读 [committedNodes] / [committedEvents]，
 * 想让两条路看到同一批预置记录就把它们放进 [initialNodes]。
 */
internal class VfsHarness(
    mounts: List<Mounted> = listOf(Mounted("/resources")),
    namespaces: Set<String> = setOf("resources"),
    initialNodes: List<NodeRecord> = emptyList(),
    capabilityOverrides: Map<String, StorageCapabilities> = emptyMap(),
    limits: VfsLimits = VfsLimits(),
    storages: (String) -> Storage? = { key -> mounts.firstOrNull { it.key == key }?.storage },
    /** 通知器由测试统一登记并关闭（断言失败也不留分发协程）。 */
    notifier: AsyncEventNotifier,
    val clock: () -> Instant = { FIXED_CLOCK },
) {
    /** 一个挂载点：逻辑路径 + 背后的内存盘。 */
    class Mounted(
        val mountPath: String,
        val storage: StorageFakeImpl = StorageFakeImpl(),
        val key: String = mountPath,
    )

    /** 预置一条 Node 记录，两个仓库都写进去：写路径（事务里读）和查询路径（自动提交读）都能看到它。 */
    fun seedNode(
        path: String,
        type: NodeType = NodeType.FILE,
        id: String = "018f0a5c-1b2c-7def-8abc-0000000000e1",
        registeredAt: Instant = FIXED_CLOCK,
    ): NodeRecord {
        val record = NodeRecord(NodeId.parse(id), VfsPath.parse(path), type, true, registeredAt, registeredAt)
        runBlocking {
            nodes.register(record)
            // 写路径在事务里查状态，那份是 [uow] 自己已提交的状态，所以也得先放一条进去。
            uow.inTransaction { scope -> scope.nodes.register(record) }
        }
        return record
    }

    val nodes: NodeRepository =
        InMemoryNodeRepository().also { repo ->
            runBlocking { initialNodes.forEach { repo.register(it) } }
        }

    val router: MountRouter =
        MountRouter.of(namespaces, mounts.map { MountRecord(VfsPath.parse(it.mountPath), it.key) })

    val boundary = StateBoundary()

    val uow = FakeStateUnitOfWork(initialNodes = initialNodes)

    val capabilities =
        CapabilitySnapshot.of(
            mounts.associate { mounted ->
                mounted.key to (capabilityOverrides[mounted.key] ?: mounted.storage.capabilities())
            },
        )

    val pipeline = EventPipeline(boundary, uow, notifier)

    val registry = NodeRegistry(router, nodes, storages, boundary, clock)

    val vfs =
        DefaultVfs(
            router = router,
            capabilities = capabilities,
            registry = registry,
            nodes = nodes,
            storages = storages,
            boundary = boundary,
            pipeline = pipeline,
            events = EventFactory(clock),
            limits = limits,
            clock = clock,
        )

    /** 写入事务提交后的 Node 记录。 */
    fun committedNodes(): List<NodeRecord> = uow.snapshot().first

    /** 写入事务提交后的事件记录。 */
    fun committedEvents(): List<EventRecord> = uow.snapshot().third

    companion object {
        /** 默认的固定「现在」，断言时间戳时好比较。 */
        val FIXED_CLOCK: Instant = Instant.parse("2026-10-03T00:00:00Z")
    }
}

/** 读替身盘上的文件内容：断言磁盘真实状态时用（不走 VFS）。 */
internal fun StorageFakeImpl.readText(relativePath: String): String =
    runBlocking { read(StoragePath.parse(relativePath), Long.MAX_VALUE).bytes.toString(Charsets.UTF_8) }

/** 替身盘上这个路径在不在，是什么类型；不在返回 null。 */
internal fun StorageFakeImpl.typeOf(relativePath: String): NodeType? =
    runBlocking {
        stat(StoragePath.parse(relativePath)).type
    }
