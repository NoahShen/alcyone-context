package com.github.noahshen.alcyone.context.memory.core.map

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import java.time.Instant

/** 一次固定 Memory Map 生成所使用的全量 Organized 快照与 T1。 */
data class MemoryMapSnapshot(
    val memories: List<OrganizedMemory>,
    val sourceUpdatedAt: Instant?,
) {
    val domains: List<String> =
        memories
            .flatMap { memory -> memory.domains }
            .distinct()
            .sorted()

    init {
        if (memories.zipWithNext().any { (left, right) -> left.id >= right.id }) {
            throw MemoryMapSnapshotException("organized memories must be strictly ordered by id ascending")
        }
        if (memories.maxOfOrNull { memory -> memory.updatedAt } != sourceUpdatedAt) {
            throw MemoryMapSnapshotException("sourceUpdatedAt does not match the snapshot max updatedAt")
        }
        if (domains.any { domain -> domain.isBlank() || domain.any { it == '\n' || it == '\r' } }) {
            throw MemoryMapSnapshotException("organized memory domains must be non-blank single-line values")
        }
    }
}

/** LLM 返回并待 Core 校验的最小 Map 草稿。 */
data class MemoryMapDraft(
    val overview: String,
    val navigation: String,
    val domains: List<String>,
)

/** Core 生成的权威 Map 文档；Front Matter 时间由 Renderer 固定格式化。 */
data class MemoryMapDocument(
    val overview: String,
    val navigation: String,
    val domains: List<String>,
    val generatedAt: Instant,
    val sourceUpdatedAt: Instant?,
)

/** 一次成功生成得到的文档与待发布字节。 */
data class MemoryMapArtifact(
    val document: MemoryMapDocument,
    val renderedBytes: ByteArray,
)

/** 快照内部一致性错误；不用于包装数据库原始失败。 */
class MemoryMapSnapshotException(
    message: String,
) : IllegalStateException(message)

/** Core Map 结构或 Domain 校验错误；Runtime 在 LLM 边界统一映射为 INVALID_RESPONSE。 */
class MemoryMapValidationException(
    message: String,
) : IllegalArgumentException(message)
