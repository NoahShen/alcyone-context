package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase

/**
 * Organization Worker 的 Source 预检/幂等清理门。
 *
 * 先以短读事务判断 Raw 是否已有 Source。已关联时再用短写事务二次核验，只有 Source 仍存在才清理
 * 残留 Pending；因此读后若状态反转，不会误删 Pending。不调用 LLM，也不修改 CT15 API。
 */
internal class OrganizationSourceGuard(
    private val database: ContextDatabase,
) {
    suspend fun isAlreadyAssociated(rawId: String): Boolean {
        val initiallyAssociated =
            database.read { read -> read.organizedMemories.findOrganizedByRaw(rawId).isNotEmpty() }
        if (!initiallyAssociated) return false

        return database.write { tx ->
            if (tx.organizedMemories.findOrganizedByRaw(rawId).isEmpty()) {
                false
            } else {
                if (tx.pending.exists(rawId)) {
                    val removed = tx.pending.dequeue(rawId)
                    if (removed != 1) {
                        throw ContextPersistenceException(
                            ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE,
                            "organization source cleanup did not remove exactly one pending row",
                        )
                    }
                }
                true
            }
        }
    }
}
