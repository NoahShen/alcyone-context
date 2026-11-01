package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import java.time.Instant

/**
 * CT05-B2 测试夹具：真实文件 SQLite + 真实执行器，复用 [ConversationFixtures.withDatabase]。
 *
 * 时间固定为可预测的 UTC 时刻，便于断言库内 `+08:00` 文本。
 */
internal object B2Fixtures {
    /** 2026-10-28T00:00:00.000Z → 库内 `2026-10-28T08:00:00.000+08:00`。 */
    val T0: Instant = Instant.parse("2026-10-28T00:00:00.000Z")

    fun raw(
        id: String,
        content: String = "content-$id",
        generatedBy: MemoryOrigin = MemoryOrigin.EXTRACTION,
        sources: List<MemorySourceRef> = emptyList(),
        createdAt: Instant = T0,
    ): RawMemory = RawMemory(id, content, generatedBy, sources, createdAt)

    fun organized(
        id: String,
        content: String = "content-$id",
        domains: List<String> = listOf("domain-a"),
        createdAt: Instant = T0,
        updatedAt: Instant = T0,
    ): OrganizedMemory = OrganizedMemory(id, content, domains, emptyList(), createdAt, updatedAt)

    suspend fun insertRaw(
        executor: ContextDatabaseExecutor,
        vararg memories: RawMemory,
    ): Int = executor.write { tx -> RawMemoryTxOpsImpl(tx).insertBatch(memories.toList()) }

    suspend fun insertOrganized(
        executor: ContextDatabaseExecutor,
        memory: OrganizedMemory,
    ): Int = executor.write { tx -> OrganizedMemoryTxOpsImpl(tx).insertNew(memory) }

    suspend fun updateOrganized(
        executor: ContextDatabaseExecutor,
        memory: OrganizedMemory,
    ): Int = executor.write { tx -> OrganizedMemoryTxOpsImpl(tx).update(memory) }

    suspend fun link(
        executor: ContextDatabaseExecutor,
        organizedId: String,
        rawId: String,
    ): Int = executor.write { tx -> OrganizedMemoryTxOpsImpl(tx).insertSourceLink(organizedId, rawId) }

    suspend fun advance(
        executor: ContextDatabaseExecutor,
        organizedId: String,
        next: Instant,
    ): Int = executor.write { tx -> OrganizedMemoryTxOpsImpl(tx).advanceUpdatedAt(organizedId, next) }

    suspend fun enqueue(
        executor: ContextDatabaseExecutor,
        rawId: String,
    ): Int = executor.write { tx -> OrganizationPendingTxOpsImpl(tx).enqueue(rawId) }

    suspend fun dequeue(
        executor: ContextDatabaseExecutor,
        rawId: String,
    ): Int = executor.write { tx -> OrganizationPendingTxOpsImpl(tx).dequeue(rawId) }

    suspend fun casCheckpoint(
        executor: ContextDatabaseExecutor,
        expected: Long,
        next: Long,
    ): Int = executor.write { tx -> DreamingCheckpointTxOpsImpl(tx).casUpdateCheckpoint(expected, next) }
}
