package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import app.cash.sqldelight.db.QueryResult
import com.github.noahshen.alcyone.context.common.newUuidV7
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import java.time.Instant

/** 测试基线时间；表里按 epoch 毫秒存储，用它才能得到稳定的回读值。 */
internal val TEST_NOW: Instant = Instant.parse("2026-10-01T10:00:00Z")

internal fun testRecord(
    path: String,
    type: NodeType = NodeType.FILE,
    physical: Boolean = true,
    at: Instant = TEST_NOW,
    id: NodeId = NodeId.parse(newUuidV7().toString()),
): NodeRecord = NodeRecord(id, VfsPath.parse(path), type, physical, at, at)

internal fun testEvent(
    nodeId: NodeId?,
    uri: String,
    type: VfsEventType = VfsEventType.FILE_CREATED,
    occurredAt: Instant = TEST_NOW,
    operationId: String? = null,
    sourceUri: String? = null,
    targetUri: String? = null,
    id: VfsEventId = VfsEventId.parse(newUuidV7().toString()),
): EventRecord =
    EventRecord(
        id = id,
        type = type,
        nodeId = nodeId,
        occurredAt = occurredAt,
        uri = VfsUri.parse(uri),
        operationId = operationId,
        sourceUri = sourceUri?.let(VfsUri::parse),
        targetUri = targetUri?.let(VfsUri::parse),
    )

internal fun VfsStateDatabase.activeNodeCount(): Long = countOf("SELECT count(*) FROM node WHERE deleted_at IS NULL")

internal fun VfsStateDatabase.deletedNodeCount(): Long = countOf("SELECT count(*) FROM node WHERE deleted_at IS NOT NULL")

internal fun VfsStateDatabase.eventCount(): Long = countOf("SELECT count(*) FROM event")

internal fun VfsStateDatabase.metadataCount(): Long = countOf("SELECT count(*) FROM metadata")

private fun VfsStateDatabase.countOf(sql: String): Long =
    driver
        .executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
            0,
            null,
        ).value

/** 事件表原始行；列顺序与 `Event.sq` 的表定义一致。Core 的 EventRepository 没有读接口，这里直查驱动。 */
internal data class EventRow(
    val eventId: String,
    val eventType: String,
    val nodeId: String?,
    val occurredAt: Long,
    val uri: String,
    val operationId: String?,
    val sourceUri: String?,
    val targetUri: String?,
)

internal fun VfsStateDatabase.eventRows(): List<EventRow> =
    driver
        .executeQuery(
            null,
            "SELECT event_id, event_type, node_id, occurred_at, uri, operation_id, source_uri, target_uri FROM event",
            { cursor ->
                QueryResult.Value(
                    buildList {
                        while (cursor.next().value) {
                            add(
                                EventRow(
                                    eventId = cursor.getString(0)!!,
                                    eventType = cursor.getString(1)!!,
                                    nodeId = cursor.getString(2),
                                    occurredAt = cursor.getLong(3)!!,
                                    uri = cursor.getString(4)!!,
                                    operationId = cursor.getString(5),
                                    sourceUri = cursor.getString(6),
                                    targetUri = cursor.getString(7),
                                ),
                            )
                        }
                    },
                )
            },
            0,
            null,
        ).value

/** Mount 写入本轮不提供接口（T18 负责），测试直接写库。 */
internal fun VfsStateDatabase.insertMount(
    path: String,
    storageKey: String,
) {
    driver.execute(null, "INSERT INTO mount (vfs_path, storage_key) VALUES (?, ?)", 2) {
        bindString(0, path)
        bindString(1, storageKey)
    }
}
