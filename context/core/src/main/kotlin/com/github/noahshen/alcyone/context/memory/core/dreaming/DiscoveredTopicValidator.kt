package com.github.noahshen.alcyone.context.memory.core.dreaming

/** DiscoveredTopic 纯逻辑校验失败；消息只含安全规则名，不含 Topic/Turn 正文或 ID。 */
class DiscoveredTopicValidationException(
    message: String,
) : IllegalArgumentException(message)

/** DiscoveredTopic 的结构、来源权限和 Consolidation 覆盖规则。 */
object DiscoveredTopicValidator {
    /** 校验 Discovery 结果，并返回列表元素稳定去重后的副本。 */
    fun validateDiscovery(
        topics: List<DiscoveredTopic>,
        batchTurnIds: Set<Long>,
    ): List<DiscoveredTopic> {
        val normalized = topics.map(::normalize)
        normalized.forEach { topic ->
            topic.sourceTurnIds.forEach { sourceId ->
                if (sourceId !in batchTurnIds) {
                    throw DiscoveredTopicValidationException("topic source id is not in the current batch")
                }
            }
        }
        return normalized
    }

    /** 校验一次批内 Consolidation 的数量和来源并集，不修改 Topic 语义顺序。 */
    fun validateConsolidation(
        topics: List<DiscoveredTopic>,
        inputTopics: List<DiscoveredTopic>,
    ): List<DiscoveredTopic> {
        if (inputTopics.size < 2) {
            throw DiscoveredTopicValidationException("consolidation requires at least two input topics")
        }
        val normalizedInput = inputTopics.map(::normalize)
        val allowedSources = normalizedInput.flatMap { topic -> topic.sourceTurnIds }.toSet()
        val normalized = topics.map(::normalize)
        if (normalized.isEmpty() || normalized.size > normalizedInput.size) {
            throw DiscoveredTopicValidationException("consolidated topic count must be between one and input count")
        }
        normalized.forEach { topic ->
            if (topic.sourceTurnIds.any { sourceId -> sourceId !in allowedSources }) {
                throw DiscoveredTopicValidationException("consolidated topic source id is not in the input topics")
            }
        }
        val outputSources = normalized.flatMap { topic -> topic.sourceTurnIds }.toSet()
        if (outputSources != allowedSources) {
            throw DiscoveredTopicValidationException("consolidated topic source ids must preserve the input union")
        }
        return normalized
    }

    private fun normalize(topic: DiscoveredTopic): DiscoveredTopic {
        if (topic.title.isBlank()) throw DiscoveredTopicValidationException("topic title must not be blank")
        if (topic.description.isBlank()) throw DiscoveredTopicValidationException("topic description must not be blank")
        if (topic.keywords.any { keyword -> keyword.isBlank() }) {
            throw DiscoveredTopicValidationException("topic keyword must not be blank")
        }
        if (topic.sourceTurnIds.isEmpty()) {
            throw DiscoveredTopicValidationException("topic source turn ids must not be empty")
        }
        if (topic.sourceTurnIds.any { sourceId -> sourceId < 0L }) {
            throw DiscoveredTopicValidationException("topic source turn id must not be negative")
        }
        return DiscoveredTopic(
            title = topic.title,
            description = topic.description,
            keywords = topic.keywords.distinct(),
            sourceTurnIds = topic.sourceTurnIds.distinct(),
        )
    }
}
