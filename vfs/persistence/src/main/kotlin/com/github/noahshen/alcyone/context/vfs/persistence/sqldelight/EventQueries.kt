package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord

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
