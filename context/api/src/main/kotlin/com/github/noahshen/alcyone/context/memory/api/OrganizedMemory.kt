package com.github.noahshen.alcyone.context.memory.api

import java.time.Instant
import java.util.Collections

/**
 * Organized Memory 当前认知模型：六字段不可变模型。
 *
 * `domains` 与 `rawMemoryIds` 在所有构造器与 [copy] 路径均执行防御性浅拷贝
 * 并包装为不可修改 List，杜绝外部篡改已存在 OrganizedMemory 的领域与来源证据集合。
 */
class OrganizedMemory(
    val id: String,
    val content: String,
    domains: List<String>,
    rawMemoryIds: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val domains: List<String> = Collections.unmodifiableList(domains.toList())
    val rawMemoryIds: List<String> = Collections.unmodifiableList(rawMemoryIds.toList())

    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(content.isNotBlank()) { "content must not be blank" }
    }

    fun copy(
        id: String = this.id,
        content: String = this.content,
        domains: List<String> = this.domains,
        rawMemoryIds: List<String> = this.rawMemoryIds,
        createdAt: Instant = this.createdAt,
        updatedAt: Instant = this.updatedAt,
    ): OrganizedMemory =
        OrganizedMemory(
            id = id,
            content = content,
            domains = domains,
            rawMemoryIds = rawMemoryIds,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    operator fun component1(): String = id

    operator fun component2(): String = content

    operator fun component3(): List<String> = domains

    operator fun component4(): List<String> = rawMemoryIds

    operator fun component5(): Instant = createdAt

    operator fun component6(): Instant = updatedAt

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OrganizedMemory) return false
        return id == other.id &&
            content == other.content &&
            domains == other.domains &&
            rawMemoryIds == other.rawMemoryIds &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + domains.hashCode()
        result = 31 * result + rawMemoryIds.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }

    override fun toString(): String =
        "OrganizedMemory(id=$id, content=$content, domains=$domains, rawMemoryIds=$rawMemoryIds, createdAt=$createdAt, updatedAt=$updatedAt)"

    companion object {
        fun create(
            id: String,
            content: String,
            domains: List<String>,
            rawMemoryIds: List<String>,
            createdAt: Instant,
            updatedAt: Instant,
        ): OrganizedMemory =
            OrganizedMemory(
                id = id,
                content = content,
                domains = domains,
                rawMemoryIds = rawMemoryIds,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
    }
}
