package com.github.noahshen.alcyone.context.memory.api

import java.time.Instant
import java.util.Collections

/**
 * Raw Memory 最小不可变逻辑模型：不可变的原始记忆记录／证据层。
 *
 * `sources` 在所有构造器与 [copy] 路径均执行防御性浅拷贝（`toList()`）
 * 并包装为不可修改 List（`Collections.unmodifiableList`），杜绝任何外部集合引用、
 * JVM 强制转型或公开 `copy(...)` 调用破坏已存在 RawMemory 的来源证据。
 */
class RawMemory(
    val id: String,
    val content: String,
    val generatedBy: MemoryOrigin,
    sources: List<MemorySourceRef>,
    val createdAt: Instant,
) {
    val sources: List<MemorySourceRef> = Collections.unmodifiableList(sources.toList())

    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(content.isNotBlank()) { "content must not be blank" }
    }

    fun copy(
        id: String = this.id,
        content: String = this.content,
        generatedBy: MemoryOrigin = this.generatedBy,
        sources: List<MemorySourceRef> = this.sources,
        createdAt: Instant = this.createdAt,
    ): RawMemory =
        RawMemory(
            id = id,
            content = content,
            generatedBy = generatedBy,
            sources = sources,
            createdAt = createdAt,
        )

    operator fun component1(): String = id

    operator fun component2(): String = content

    operator fun component3(): MemoryOrigin = generatedBy

    operator fun component4(): List<MemorySourceRef> = sources

    operator fun component5(): Instant = createdAt

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawMemory) return false
        return id == other.id &&
            content == other.content &&
            generatedBy == other.generatedBy &&
            sources == other.sources &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + generatedBy.hashCode()
        result = 31 * result + sources.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "RawMemory(id=$id, content=$content, generatedBy=$generatedBy, sources=$sources, createdAt=$createdAt)"

    companion object {
        fun create(
            id: String,
            content: String,
            generatedBy: MemoryOrigin,
            sources: List<MemorySourceRef>,
            createdAt: Instant,
        ): RawMemory =
            RawMemory(
                id = id,
                content = content,
                generatedBy = generatedBy,
                sources = sources,
                createdAt = createdAt,
            )
    }
}
