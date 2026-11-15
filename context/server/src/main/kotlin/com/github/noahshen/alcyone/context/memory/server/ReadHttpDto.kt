package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.BootstrapContext
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.api.ResourceMetadata
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** HTTP 边界 DTO；与 API 领域模型解耦，避免序列化注解倒灌。 */
@Serializable
internal data class BootstrapResponse(
    @SerialName("memory_map") val memoryMap: MemoryMapResponse?,
    @SerialName("map_status") val mapStatus: String,
    val capabilities: String,
) {
    companion object {
        fun from(value: BootstrapContext): BootstrapResponse =
            BootstrapResponse(
                memoryMap =
                    value.memoryMap?.let {
                        MemoryMapResponse(it.content, it.generatedAt.toString(), it.sourceUpdatedAt?.toString())
                    },
                mapStatus = value.mapStatus.name,
                capabilities = value.capabilities,
            )
    }
}

@Serializable
internal data class MemoryMapResponse(
    val content: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("source_updated_at") val sourceUpdatedAt: String?,
)

@Serializable
internal data class OrganizedMemoryResponse(
    val id: String,
    val content: String,
    val domains: List<String>,
    @SerialName("raw_memory_ids") val rawMemoryIds: List<String>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    companion object {
        fun from(value: OrganizedMemory): OrganizedMemoryResponse =
            OrganizedMemoryResponse(
                id = value.id,
                content = value.content,
                domains = value.domains,
                rawMemoryIds = value.rawMemoryIds,
                createdAt = value.createdAt.toString(),
                updatedAt = value.updatedAt.toString(),
            )
    }
}

@Serializable
internal data class RawMemoryResponse(
    val id: String,
    val content: String,
    @SerialName("generated_by") val generatedBy: String,
    val sources: List<MemorySourceResponse>,
    @SerialName("created_at") val createdAt: String,
) {
    companion object {
        fun from(value: RawMemory): RawMemoryResponse =
            RawMemoryResponse(
                id = value.id,
                content = value.content,
                generatedBy = value.generatedBy.name,
                sources = value.sources.map(MemorySourceResponse::from),
                createdAt = value.createdAt.toString(),
            )
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
internal sealed interface MemorySourceResponse {
    companion object {
        fun from(value: MemorySourceRef): MemorySourceResponse =
            when (value) {
                is MemorySourceRef.ConversationTurnRef ->
                    ConversationTurnSourceResponse(
                        runtimeId = value.runtimeId,
                        agentId = value.agentId,
                        sessionId = value.sessionId,
                        sessionTurnId = value.sessionTurnId,
                    )
                is MemorySourceRef.RawMemoryRef -> RawMemorySourceResponse(value.memoryId)
            }
    }
}

@Serializable
@SerialName("conversation_turn")
internal data class ConversationTurnSourceResponse(
    @SerialName("runtime_id") val runtimeId: String,
    @SerialName("agent_id") val agentId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_turn_id") val sessionTurnId: String,
) : MemorySourceResponse

@Serializable
@SerialName("raw_memory")
internal data class RawMemorySourceResponse(
    @SerialName("memory_id") val memoryId: String,
) : MemorySourceResponse

@Serializable
internal data class ResourceMetadataResponse(
    val uri: String,
    @SerialName("size_bytes") val sizeBytes: Long?,
    @SerialName("modified_at") val modifiedAt: String?,
) {
    companion object {
        fun from(value: ResourceMetadata): ResourceMetadataResponse =
            ResourceMetadataResponse(value.uri, value.sizeBytes, value.modifiedAt?.toString())
    }
}
