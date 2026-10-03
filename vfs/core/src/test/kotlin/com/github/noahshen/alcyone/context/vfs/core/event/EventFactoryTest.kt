package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * T14 A01：事件本身的字段规则。选哪种事件属 T15～T23，这里只钉住「造出来的和发出去的必须自洽」。
 */
class EventFactoryTest {
    private val nodeId = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000a1")
    private val uri = VfsUri.parse("alcyone://resources/notes/a.txt")
    private val fixedTime = Instant.parse("2026-10-03T01:02:03.456Z")
    private val events = EventFactory { fixedTime }

    @Test
    fun `A01 the model has exactly the seven event types and no registered-node event`() {
        assertEquals(
            listOf(
                "FILE_CREATED",
                "FILE_WRITTEN",
                "FILE_MOVED",
                "DIRECTORY_MOVED",
                "FILE_DELETED",
                "DIRECTORY_DELETED",
                "METADATA_UPDATED",
            ),
            VfsEventType.entries.map { it.name },
            "首版只有这七种；懒注册不发事件，所以没有 NODE_REGISTERED",
        )
    }

    @Test
    fun `A01 an event carries identity position and time only`() {
        assertEquals(
            setOf("id", "type", "nodeId", "occurredAt", "uri", "operationId", "sourceUri", "targetUri"),
            VfsEvent::class.java.declaredFields
                .map { it.name }
                .toSet(),
        )
        assertTrue(
            VfsEvent::class.java.declaredFields.none { it.type == ByteArray::class.java },
            "事件里不许出现文件内容",
        )
        assertTrue(
            VfsEvent::class.java.declaredFields.none { f ->
                listOf("token", "credential", "password", "agent", "owner").any {
                    it in
                        f.name.lowercase()
                }
            },
            "事件里不许出现凭据或 Agent 身份",
        )
    }

    @Test
    fun `A01 a generated record gets a uuid v7 id and a millisecond timestamp`() {
        val record = events.newRecord(VfsEventType.FILE_WRITTEN, nodeId, uri)

        assertEquals('7', record.id.toString()[14], "事件 ID 是 UUIDv7（版本位在第 15 个字符）")
        assertEquals(fixedTime, record.occurredAt)
        assertEquals(VfsEventType.FILE_WRITTEN, record.type)
        assertEquals(nodeId, record.nodeId)
        assertEquals(uri, record.uri)
        assertNull(record.operationId)
        assertNull(record.sourceUri)
        assertNull(record.targetUri)
    }

    @Test
    fun `A01 the timestamp is truncated to the millisecond the state store keeps`() {
        val noisy = EventFactory { Instant.parse("2026-10-03T01:02:03.456789123Z") }

        val record = noisy.newRecord(VfsEventType.FILE_CREATED, nodeId, uri)

        assertEquals(
            Instant.parse("2026-10-03T01:02:03.456Z"),
            record.occurredAt,
            "事件日志表存 epoch 毫秒，生成时就取整，通知和库里的时间才是同一个值",
        )
    }

    @Test
    fun `A01 the node id may be empty`() {
        val record = events.newRecord(VfsEventType.FILE_DELETED, nodeId = null, uri = uri)

        assertNull(record.nodeId, "只查得到逻辑记录、没有有效 Node 的删除事件就是空的")
        assertNull(record.toVfsEvent().nodeId)
    }

    @Test
    fun `A01 a move event points uri at the target and keeps both ends`() {
        val source = VfsUri.parse("alcyone://resources/notes/a.txt")
        val target = VfsUri.parse("alcyone://resources/notes/b.txt")

        for (type in listOf(VfsEventType.FILE_MOVED, VfsEventType.DIRECTORY_MOVED)) {
            val record = events.newMove(type, nodeId, source, target)

            assertEquals(target, record.uri, "$type 的 uri 是移动后的目标位置")
            assertEquals(source, record.sourceUri)
            assertEquals(target, record.targetUri)

            val published = record.toVfsEvent()
            assertEquals(target, published.uri)
            assertEquals(source, published.sourceUri)
            assertEquals(target, published.targetUri)
            assertEquals(nodeId, published.nodeId, "移动前后是同一个 Node ID")
        }
    }

    @Test
    fun `A01 a move event without both ends is rejected before anything is persisted`() {
        val source = VfsUri.parse("alcyone://resources/notes/a.txt")
        val target = VfsUri.parse("alcyone://resources/notes/b.txt")

        val missingSource =
            assertFailsWith<IllegalArgumentException> {
                events.newRecord(VfsEventType.FILE_MOVED, nodeId, uri = target, targetUri = target)
            }
        val sourceIsUri =
            assertFailsWith<IllegalArgumentException> {
                events.newRecord(VfsEventType.FILE_MOVED, nodeId, uri = source, sourceUri = source, targetUri = target)
            }
        val notAMove =
            assertFailsWith<IllegalArgumentException> {
                events.newMove(VfsEventType.FILE_WRITTEN, nodeId, source, target)
            }

        assertTrue(missingSource.message!!.contains("uri 必须是目标位置"))
        assertTrue(sourceIsUri.message!!.contains("uri 必须是目标位置"))
        assertTrue(notAMove.message!!.contains("只有移动事件能填"))
    }

    @Test
    fun `A01 a plain event rejects source and target`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                events.newRecord(
                    type = VfsEventType.FILE_WRITTEN,
                    nodeId = nodeId,
                    uri = uri,
                    sourceUri = uri,
                )
            }

        assertTrue(failure.message!!.contains("只有移动事件能填"))
    }

    @Test
    fun `A01 conversion keeps the same identity and aligns the time to the stored milliseconds`() {
        val record =
            events
                .newRecord(
                    VfsEventType.METADATA_UPDATED,
                    nodeId,
                    uri,
                    operationId = "op-7",
                ).copy(occurredAt = Instant.parse("2026-10-03T01:02:03.456999999Z"))

        val published = record.toVfsEvent()

        assertEquals(record.id, published.id, "通知侧不重新生成 ID")
        assertEquals(record.type, published.type)
        assertEquals(record.uri, published.uri)
        assertEquals(record.operationId, published.operationId)
        assertEquals(
            Instant.parse("2026-10-03T01:02:03.456Z"),
            published.occurredAt,
            "通知里的时间等于库里那一行的毫秒值",
        )
    }
}
