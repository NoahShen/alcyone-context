package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `metadata` 表的读取与写入。
 *
 * 载荷是 `NodeMetadata` 的 JSON 文本，固定三个键 `tags` / `description` / `extensions`，
 * 不给 API 类型加序列化注解（T11 §2.5）。空 `tags`、`null` `description`、空 `extensions`
 * 都能原样往返。
 */
internal fun MetadataQueries.readMetadata(id: NodeId): NodeMetadata? =
    selectPayloadByNodeId(id.value).executeAsOneOrNull()?.let(::decodeMetadata)

/**
 * 整体替换元数据。空元数据（无 tags、无 description、无 extensions）按清空处理：删行而不是留空行，
 * 与 Core 的内存实现一致，`get` 随后返回 null。
 */
internal fun MetadataQueries.writeMetadata(
    id: NodeId,
    metadata: NodeMetadata,
) {
    if (metadata.tags.isEmpty() && metadata.description == null && metadata.extensions.isEmpty()) {
        deleteMetadataByNodeId(id.value)
    } else {
        insertOrReplaceMetadata(id.value, encodeMetadata(metadata))
    }
}

internal fun MetadataQueries.removeMetadata(id: NodeId) {
    deleteMetadataByNodeId(id.value)
}

private fun encodeMetadata(metadata: NodeMetadata): String =
    buildJsonObject {
        put("tags", JsonArray(metadata.tags.map(::JsonPrimitive)))
        put("description", metadata.description?.let(::JsonPrimitive) ?: JsonNull)
        put("extensions", metadata.extensions)
    }.toString()

private fun decodeMetadata(payload: String): NodeMetadata {
    val json = Json.parseToJsonElement(payload).jsonObject
    val tags = (json["tags"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    // JsonNull 也是 JsonPrimitive，必须先排除，否则取 content 会抛异常。
    val description = (json["description"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    val extensions = json["extensions"] as? JsonObject ?: JsonObject(emptyMap())
    return NodeMetadata(tags.toSet(), description, extensions)
}
