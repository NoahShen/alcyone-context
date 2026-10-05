package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.event.TrackedNotifiers
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * T17 S1/S2：Metadata 查询与整体替换的有效性、纯逻辑范围、逻辑时间和事件条数。
 *
 * 这些用例证明的是「规则」：替身盘记下每次调用，所以「两种操作一次 Storage 都没碰」是可断言的事实；
 * 替身状态库是写时复制的事务，所以回滚、时间推进和事件条数也看得见。真实 SQLite 的持久化、
 * 事件主键冲突回滚和并发 / 取消放 `DefaultVfsRealStackTest`。
 */
class DefaultVfsMetadataTest {
    private val notifiers = TrackedNotifiers()

    /** 钩子写在测试类上：断言先失败也会执行，不留分发协程。 */
    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    private fun harness(
        disk: StorageFakeImpl = StorageFakeImpl(),
        readOnly: Boolean = false,
        clock: () -> Instant = { VfsHarness.FIXED_CLOCK },
    ) = VfsHarness(
        listOf(VfsHarness.Mounted("/resources", disk)),
        capabilityOverrides = if (readOnly) mapOf("/resources" to StorageCapabilities(readOnly = true)) else emptyMap(),
        notifier = notifiers.create(),
        clock = clock,
    )

    private fun unknownId() = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000ff")

    @Test
    fun `A01 a valid node without metadata reads empty while unknown and retired ids are NOT_FOUND`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(disk)
            val record = harness.seedNode("/resources/a.txt")
            disk.calls.clear()

            assertEquals(NodeMetadata(), harness.vfs.getMetadata(record.id), "有效但没设置过 → 空对象")

            val getUnknown = assertFailsWith<VfsException> { harness.vfs.getMetadata(unknownId()) }
            assertEquals(VfsErrorCode.NOT_FOUND, getUnknown.code, "没登记过的 ID 不能读出空 Metadata")
            val setUnknown = assertFailsWith<VfsException> { harness.vfs.setMetadata(unknownId(), NodeMetadata(description = "x")) }
            assertEquals(VfsErrorCode.NOT_FOUND, setUnknown.code, "同一条规则也挡住更新")
            assertEquals(VfsEffect.NONE, setUnknown.effect, "无效 ID 一个字节都没改")

            harness.retireNode(record.id)
            val getRetired = assertFailsWith<VfsException> { harness.vfs.getMetadata(record.id) }
            assertEquals(VfsErrorCode.NOT_FOUND, getRetired.code, "已删除的 ID 不退化成空 Metadata")
            val setRetired = assertFailsWith<VfsException> { harness.vfs.setMetadata(record.id, NodeMetadata(description = "x")) }
            assertEquals(VfsErrorCode.NOT_FOUND, setRetired.code, "不给已删除的 Node 写回 Metadata")

            assertTrue(harness.committedEvents().isEmpty(), "被拒的操作不发事件")
            assertTrue(disk.calls.isEmpty(), "整条链一次 Storage 都没碰：${disk.calls}")
        }

    @Test
    fun `A02 a set replaces the whole object and an empty object clears it`() =
        runBlocking {
            val harness = harness()
            val record = harness.seedNode("/resources/a.txt")
            val full =
                NodeMetadata(
                    tags = setOf("ct", "影像"),
                    description = "胸部 CT 报告",
                    extensions = buildJsonObject { put("dicom", "1.2.840") },
                )

            harness.vfs.setMetadata(record.id, full)
            assertEquals(full, harness.vfs.getMetadata(record.id), "三个字段完整往返")

            val replacement = NodeMetadata(description = "报告")
            harness.vfs.setMetadata(record.id, replacement)
            assertEquals(replacement, harness.vfs.getMetadata(record.id), "整体替换：不和旧 tags 合并")

            harness.vfs.setMetadata(record.id, NodeMetadata())
            assertEquals(NodeMetadata(), harness.vfs.getMetadata(record.id), "空对象清空")
            assertTrue(
                harness.uow
                    .snapshot()
                    .second
                    .isEmpty(),
                "清空后状态库里不留空对象",
            )
        }

    @Test
    fun `A03 both operations stay in the state store on a read-only backend`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(disk, readOnly = true)
            // Node 登记过了，磁盘上那个文件从来没存在过；只读后端也只是「写不了」，不是「读不了状态」。
            val record = harness.seedNode("/resources/gone.txt")
            disk.calls.clear()

            harness.vfs.setMetadata(record.id, NodeMetadata(setOf("ct"), "物理文件已不在"))
            assertEquals(NodeMetadata(setOf("ct"), "物理文件已不在"), harness.vfs.getMetadata(record.id))

            assertTrue(disk.calls.isEmpty(), "Metadata 两种操作一次 Storage 都不碰：${disk.calls}")
            assertEquals(listOf(VfsEventType.METADATA_UPDATED), harness.committedEvents().map { it.type })
        }

    @Test
    fun `A03 a disk that fails on every call still serves both metadata operations`() =
        runBlocking {
            val disk = StorageFakeImpl()
            val harness = harness(disk)
            val record = harness.seedNode("/resources/a.txt")
            disk.failEverything()
            disk.calls.clear()

            harness.vfs.setMetadata(record.id, NodeMetadata(description = "still logical"))

            assertEquals(NodeMetadata(description = "still logical"), harness.vfs.getMetadata(record.id))
            assertTrue(disk.calls.isEmpty(), "一碰就失败的盘也没被碰：${disk.calls}")
        }

    @Test
    fun `A04 each set advances updatedAt keeps the identity and emits exactly one event`() =
        runBlocking {
            // 每次 set 读两次时钟：一次 touch 推进逻辑时间，一次给事件打时间戳（下共 5 次 set = 10 次读）。
            val moments = ArrayDeque(List(10) { Instant.parse("2026-10-04T10:00:%02dZ".format(it)) })
            val harness = harness(clock = { moments.removeFirst() })
            val record = harness.seedNode("/resources/a.txt")

            harness.vfs.setMetadata(record.id, NodeMetadata(description = "first"))
            val afterFirst = harness.committedNodes().single()
            assertEquals(record.id, afterFirst.id, "身份不变")
            assertEquals(record.path, afterFirst.path, "路径不变")
            assertEquals(record.type, afterFirst.type, "类型不变")
            assertEquals(record.registeredAt, afterFirst.registeredAt, "登记时间不变")
            assertEquals(Instant.parse("2026-10-04T10:00:00Z"), afterFirst.updatedAt, "逻辑时间推进到这次 set 的时钟")

            harness.vfs.setMetadata(record.id, NodeMetadata(description = "second"))
            assertEquals(
                Instant.parse("2026-10-04T10:00:02Z"),
                harness.committedNodes().single().updatedAt,
                "第二次 set 再推进一次",
            )

            // 传完全相同的值、再传一次空对象：两次都是有效调用，各发一条事件（本轮不做相等比较）。
            harness.vfs.setMetadata(record.id, NodeMetadata(description = "second"))
            harness.vfs.setMetadata(record.id, NodeMetadata())
            harness.vfs.setMetadata(record.id, NodeMetadata())

            val events = harness.committedEvents()
            assertEquals(
                listOf(
                    VfsEventType.METADATA_UPDATED,
                    VfsEventType.METADATA_UPDATED,
                    VfsEventType.METADATA_UPDATED,
                    VfsEventType.METADATA_UPDATED,
                    VfsEventType.METADATA_UPDATED,
                ),
                events.map { it.type },
                "同值和重复清空各一条事件",
            )
            assertTrue(events.all { it.nodeId == record.id }, "事件带目标 ID")
            assertTrue(events.all { it.uri.path.toString() == "/resources/a.txt" }, "事件 URI 是这次受保护读取里看到的路径")

            val before = harness.committedEvents().size
            harness.vfs.getMetadata(record.id)
            assertEquals(before, harness.committedEvents().size, "查询零事件")
        }

    @Test
    fun `A05 a plain state failure is reported as STATE_ERROR with NONE and changes nothing`() =
        runBlocking {
            val harness = harness()
            val record = harness.seedNode("/resources/a.txt")
            harness.seedMetadata(record.id, NodeMetadata(setOf("ct"), "旧说明"))
            val before = harness.committedNodes().single()
            // 真实 SQLite 自己会把 JDBC 失败映射成 STATE_ERROR（见 mapStateErrors）；
            // 这里注入一个不是 VfsException 的失败，验证编排也不会把它原样漏出去。
            harness.uow.failOnEventAppendRaw = IllegalStateException("the state driver gave up")

            val failure =
                assertFailsWith<VfsException> {
                    harness.vfs.setMetadata(record.id, NodeMetadata(setOf("mr"), "新说明"))
                }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.NONE, failure.effect, "这一批全在状态库里，没有 Storage 副作用")
            assertTrue(failure.cause is IllegalStateException, "原始原因保留在 cause 上：${failure.cause}")
            assertEquals(NodeMetadata(setOf("ct"), "旧说明"), harness.uow.snapshot().second[record.id], "同批回滚，旧值还在")
            assertEquals(before.updatedAt, harness.committedNodes().single().updatedAt, "Node 逻辑时间一起回滚")
            assertTrue(harness.committedEvents().isEmpty(), "没有成功事件")
        }

    @Test
    fun `A05 a state failure that already carries an effect is passed through unchanged`() =
        runBlocking {
            val harness = harness()
            val record = harness.seedNode("/resources/a.txt")
            // 状态库自己报出的异常（带 UNKNOWN）原样抛出：不改写 code，也不把 effect 降成 NONE。
            // 真实 SQLite 只会经 mapStateErrors 产出 STATE_ERROR + NONE，所以这一路只在替身上验证。
            val unknown =
                VfsException(
                    VfsErrorCode.STATE_ERROR,
                    "state store could not tell whether it committed",
                    effect = VfsEffect.UNKNOWN,
                )
            harness.uow.failOnEventAppend = unknown

            val failure =
                assertFailsWith<VfsException> {
                    harness.vfs.setMetadata(record.id, NodeMetadata(description = "x"))
                }

            assertSame(unknown, failure, "状态库自己报出的异常原样抛出，不重新包装")
            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.UNKNOWN, failure.effect, "UNKNOWN 不降级成 NONE")
            assertNull(harness.uow.snapshot().second[record.id], "这一批仍然整体回滚，什么都没写进去")
        }

    // __METADATA_APPEND__
}
