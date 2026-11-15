package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import java.time.Instant

/** 追加事件：全字段写入，可空列对应可空字段；主键重复由数据库拒绝并映射为 STATE_ERROR。 */
internal fun EventQueries.append(event: EventRecord) {
    insertEvent(
        event.id.value,
        event.type.name,
        event.nodeId?.value,
        event.occurredAt.toEpochMilli(),
        event.uri.toString(),
        event.operationId,
        event.sourceUri?.toString(),
        event.targetUri?.toString(),
    )
}

internal fun Event.toEventRecord(): EventRecord =
    EventRecord(
        id = VfsEventId.parse(event_id),
        type = VfsEventType.valueOf(event_type),
        nodeId = node_id?.let(NodeId::parse),
        occurredAt = Instant.ofEpochMilli(occurred_at),
        uri = VfsUri.parse(uri),
        operationId = operation_id,
        sourceUri = source_uri?.let(VfsUri::parse),
        targetUri = target_uri?.let(VfsUri::parse),
    )
