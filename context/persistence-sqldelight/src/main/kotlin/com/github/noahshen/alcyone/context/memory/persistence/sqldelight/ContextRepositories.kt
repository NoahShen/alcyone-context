package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.repository.DreamingCheckpointRepository
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizationPendingRepository
import com.github.noahshen.alcyone.context.memory.core.repository.OrganizedMemoryRepository
import com.github.noahshen.alcyone.context.memory.core.repository.RawMemoryRepository
import java.time.Instant

/**
 * B2 普通 suspend Repository 实现（Raw / Organized / Pending / Checkpoint）。
 *
 * 与 [ConversationRepositoryImpl] 同一约定：每个方法都是**一次** `executor.read { }`
 * 短作用域，作用域随回调结束失效；不缓存 scope / 连接 / Ops，因此跨调用不保证同一快照。
 * 作用域内部一律走对应 ReadOps，从而复用 CT04 的线程归属、逃逸与关闭保护。
 *
 * 本文件从 Raw Memory 读取（不可更新、不可删除）开始。
 */
class RawMemoryRepositoryImpl(
    private val executor: ContextDatabaseExecutor,
) : RawMemoryRepository {
    override suspend fun findById(id: String): RawMemory? = executor.read { scope -> RawMemoryReadOpsImpl(scope).findById(id) }

    override suspend fun findByIds(ids: Collection<String>): List<RawMemory> =
        executor.read { scope -> RawMemoryReadOpsImpl(scope).findByIds(ids) }

    override suspend fun scanPage(
        offset: Int,
        limit: Int,
    ): List<RawMemory> = executor.read { scope -> RawMemoryReadOpsImpl(scope).scanPage(offset, limit) }
}

/** Organized Memory 读取（含来源正反查与全局水位）。 */
class OrganizedMemoryRepositoryImpl(
    private val executor: ContextDatabaseExecutor,
) : OrganizedMemoryRepository {
    override suspend fun findById(id: String): OrganizedMemory? = executor.read { scope -> OrganizedMemoryReadOpsImpl(scope).findById(id) }

    override suspend fun findByIds(ids: Collection<String>): List<OrganizedMemory> =
        executor.read { scope -> OrganizedMemoryReadOpsImpl(scope).findByIds(ids) }

    override suspend fun findSourcesByOrganized(organizedId: String): List<String> =
        executor.read { scope -> OrganizedMemoryReadOpsImpl(scope).findSourcesByOrganized(organizedId) }

    override suspend fun findOrganizedByRaw(rawId: String): List<String> =
        executor.read { scope -> OrganizedMemoryReadOpsImpl(scope).findOrganizedByRaw(rawId) }

    override suspend fun maxUpdatedAt(): Instant? = executor.read { scope -> OrganizedMemoryReadOpsImpl(scope).maxUpdatedAt() }

    override suspend fun scanPage(
        offset: Int,
        limit: Int,
    ): List<OrganizedMemory> = executor.read { scope -> OrganizedMemoryReadOpsImpl(scope).scanPage(offset, limit) }
}

/** Organization Pending 读取。 */
class OrganizationPendingRepositoryImpl(
    private val executor: ContextDatabaseExecutor,
) : OrganizationPendingRepository {
    override suspend fun exists(rawId: String): Boolean = executor.read { scope -> OrganizationPendingReadOpsImpl(scope).exists(rawId) }

    override suspend fun hasAnyPending(): Boolean = executor.read { scope -> OrganizationPendingReadOpsImpl(scope).hasAnyPending() }

    override suspend fun nextByRawCreatedAt(): String? =
        executor.read { scope -> OrganizationPendingReadOpsImpl(scope).nextByRawCreatedAt() }
}

/** Dreaming Checkpoint 读取。 */
class DreamingCheckpointRepositoryImpl(
    private val executor: ContextDatabaseExecutor,
) : DreamingCheckpointRepository {
    override suspend fun readCheckpoint(): Long = executor.read { scope -> DreamingCheckpointReadOpsImpl(scope).readCheckpoint() }
}
