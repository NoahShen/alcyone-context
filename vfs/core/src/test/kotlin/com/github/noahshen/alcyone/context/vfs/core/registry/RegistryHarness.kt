package com.github.noahshen.alcyone.context.vfs.core.registry

import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.repository.InMemoryNodeRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * Core 单测用的脚手架：内存状态库 + 内存盘，拼出一个 [NodeRegistry]。
 *
 * 盘用 [StorageFakeImpl]——它会记下每次调用，所以替身能证明「这次到底有没有问过后端」；
 * 状态库默认 [InMemoryNodeRepository]，需要模拟竞争或故障时用参数换掉。
 */
internal class RegistryHarness(
    mounts: List<MountedStorage>,
    namespaces: Set<String> = setOf("resources"),
    val nodes: NodeRepository = InMemoryNodeRepository(),
    /** 怎么按 storageKey 找盘。默认按 [MountedStorage.key] 找；要模拟「装配漏盘」就传 `{ null }`。 */
    storages: (String) -> Storage? = { key -> mounts.firstOrNull { it.key == key }?.storage },
    clock: () -> Instant = Instant::now,
) {
    /** 一个挂载点：逻辑路径、背后的内存盘，以及这块盘的标识（默认与路径同名，测试里不用编 key）。 */
    class MountedStorage(
        val mountPath: String,
        val storage: StorageFakeImpl,
        val key: String = mountPath,
    )

    val router: MountRouter =
        MountRouter.of(
            namespaces,
            mounts.map { MountRecord(VfsPath.parse(it.mountPath), it.key) },
        )

    val registry: NodeRegistry = NodeRegistry(router, nodes, storages, StateBoundary(), clock)

    /** 这块盘上被 stat 过的相对路径，形如 `a.txt`。 */
    fun statCalls(storage: StorageFakeImpl): List<String> = storage.calls.filter { it.startsWith("stat:") }.map { it.removePrefix("stat:") }
}

/** 在替身盘上放一个文件：测试摆数据用，不在挂起上下文里。 */
internal fun StorageFakeImpl.withFile(
    relativePath: String,
    content: String = "hello",
) {
    runBlocking { write(StoragePath.parse(relativePath), content.toByteArray(), StorageWriteMode.UPSERT) }
}

/** 在替身盘上建一个目录。 */
internal fun StorageFakeImpl.makeDirectory(relativePath: String) {
    runBlocking { createDirectory(StoragePath.parse(relativePath)) }
}

/** 从替身盘上删掉一个文件或目录，模拟有人绕过 VFS 直接动磁盘。 */
internal fun StorageFakeImpl.remove(relativePath: String) {
    runBlocking { delete(StoragePath.parse(relativePath), false) }
}
