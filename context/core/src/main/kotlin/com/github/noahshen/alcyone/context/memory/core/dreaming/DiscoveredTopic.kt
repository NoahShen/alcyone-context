package com.github.noahshen.alcyone.context.memory.core.dreaming

import java.util.Collections

/**
 * Dreaming 批内临时 Topic；不分配系统 ID，也不进入公开 SDK 契约。
 *
 * 列表字段在构造和复制时防御性拷贝并包装为不可修改视图；[toString] 只输出安全计数，
 * 不包含 title/description/keywords/sourceTurnIds 内容。
 */
class DiscoveredTopic(
    val title: String,
    val description: String,
    keywords: List<String>,
    sourceTurnIds: List<Long>,
) {
    val keywords: List<String> = Collections.unmodifiableList(keywords.toList())
    val sourceTurnIds: List<Long> = Collections.unmodifiableList(sourceTurnIds.toList())

    fun copy(
        title: String = this.title,
        description: String = this.description,
        keywords: List<String> = this.keywords,
        sourceTurnIds: List<Long> = this.sourceTurnIds,
    ): DiscoveredTopic = DiscoveredTopic(title, description, keywords, sourceTurnIds)

    operator fun component1(): String = title

    operator fun component2(): String = description

    operator fun component3(): List<String> = keywords

    operator fun component4(): List<Long> = sourceTurnIds

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveredTopic) return false
        return title == other.title &&
            description == other.description &&
            keywords == other.keywords &&
            sourceTurnIds == other.sourceTurnIds
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + keywords.hashCode()
        result = 31 * result + sourceTurnIds.hashCode()
        return result
    }

    override fun toString(): String =
        "DiscoveredTopic(titleLength=${title.length}, descriptionLength=${description.length}, " +
            "keywordCount=${keywords.size}, sourceTurnIdCount=${sourceTurnIds.size})"
}
