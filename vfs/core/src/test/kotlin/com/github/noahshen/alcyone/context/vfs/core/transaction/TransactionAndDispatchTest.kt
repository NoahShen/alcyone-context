package com.github.noahshen.alcyone.context.vfs.core.transaction

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.EventDispatcher
import com.github.noahshen.alcyone.context.vfs.core.event.VfsEventConsumer
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class TransactionAndDispatchTest {
    private fun node(path: String): NodeRecord =
        NodeRecord(
            id =
                com.github.noahshen.alcyone.context.vfs.NodeId.parse(
                    "018f0a5c-1b2c-7def-8abc-000000000001",
                ),
            path = VfsPath.parse(path),
            type = NodeType.FILE,
            physical = true,
            registeredAt = Instant.parse("2026-09-29T00:00:00Z"),
            updatedAt = Instant.parse("2026-09-29T00:00:00Z"),
        )

    private fun event(id: String): EventRecord =
        EventRecord(
            id = VfsEventId.parse(id),
            type = VfsEventType.FILE_CREATED,
            nodeId = node("/notes/a.txt").id,
            occurredAt = Instant.parse("2026-09-29T00:00:00Z"),
            uri = VfsUri.parse("alcyone://resources/notes/a.txt"),
        )

    // 1. 成功路径：同一事务内操作 nodes 与 events，提交后全部可见
    @Test
    fun `successful transaction commits nodes metadata and events together`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            val target = node("/notes/a.txt")

            val returned =
                uow.inTransaction { tx ->
                    val registered = tx.nodes.register(target)
                    tx.metadata.put(
                        registered.id,
                        com.github.noahshen.alcyone.context.vfs
                            .NodeMetadata(tags = setOf("t")),
                    )
                    tx.events.append(event("018f0a5c-1b2c-7def-8abc-000000000002"))
                    registered.id
                }
            assertEquals(target.id, returned)

            val (nodes, metadata, events) = uow.snapshot()
            assertEquals(1, nodes.size)
            assertEquals(target.path, nodes[0].path)
            assertEquals(1, metadata.size)
            assertEquals(1, events.size)

            // 提交后再次读取事务外查询仍可见（已提交状态）
            val afterCommit = uow.inTransaction { tx -> tx.nodes.findByPath(target.path) }
            assertNotNull(afterCommit)
        }

    // 2. 调用顺序断言：替身记录的次序可被断言
    @Test
    fun `recorded call order shows state write precedes event append precedes commit`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            val target = node("/notes/a.txt")

            uow.inTransaction { tx ->
                tx.nodes.register(target)
                tx.events.append(event("018f0a5c-1b2c-7def-8abc-000000000003"))
            }

            assertEquals("nodes.register:$target", uow.calls[0])
            assertEquals("events.append:${event("018f0a5c-1b2c-7def-8abc-000000000003")}", uow.calls[1])
            assertEquals("commit", uow.calls[2])
            assertFalse(uow.calls.any { it.endsWith("-failed") }, "successful path must not record failures")
        }

    // 3. 事件追加失败 → 同一事务内此前的 Node 变更全部不生效
    @Test
    fun `event append failure rolls back node changes in the same transaction`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            uow.failOnEventAppend = FakeStateUnitOfWork.storageFailure("event log write failed")
            val target = node("/notes/a.txt")

            assertThrows(RuntimeException::class.java) {
                runBlocking {
                    uow.inTransaction { tx ->
                        tx.nodes.register(target)
                        tx.events.append(event("018f0a5c-1b2c-7def-8abc-000000000004"))
                    }
                }
            }

            val (nodes, _, events) = uow.snapshot()
            assertTrue(nodes.isEmpty(), "node registered before event failure must be rolled back")
            assertTrue(events.isEmpty())
            assertTrue(uow.calls.contains("events.append-failed"))
            assertFalse(uow.calls.contains("commit"), "failed transaction must not commit")
        }

    // 4. 状态修改失败 → 事件也不提交
    @Test
    fun `state write failure keeps events uncommitted`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            uow.failOnStateWrite = FakeStateUnitOfWork.storageFailure("node registry write failed")

            assertThrows(RuntimeException::class.java) {
                runBlocking {
                    uow.inTransaction { tx -> tx.nodes.register(node("/notes/b.txt")) }
                }
            }

            val (nodes, _, events) = uow.snapshot()
            assertTrue(nodes.isEmpty())
            assertTrue(events.isEmpty())
            assertFalse(uow.calls.contains("commit"))
        }

    // 5. 提交失败 → 变更不生效，且不允许分发
    @Test
    fun `commit failure discards changes and blocks dispatch`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            uow.failOnCommit = FakeStateUnitOfWork.storageFailure("commit failed")

            assertThrows(RuntimeException::class.java) {
                runBlocking {
                    uow.inTransaction { tx ->
                        tx.nodes.register(node("/notes/c.txt"))
                        tx.events.append(event("018f0a5c-1b2c-7def-8abc-000000000005"))
                    }
                }
            }

            val (nodes, _, events) = uow.snapshot()
            assertTrue(nodes.isEmpty(), "commit failure must not leave committed state")
            assertTrue(events.isEmpty())
            assertTrue(uow.calls.contains("commit-failed"))

            // 编排层契约：只有提交成功才允许分发，提交失败时 events 为空
            val (committedNodes, _, committedEvents) = uow.snapshot()
            val dispatchable = if (committedEvents.isNotEmpty()) committedEvents else emptyList()
            assertTrue(dispatchable.isEmpty(), "no event may be dispatched when transaction did not commit")
        }

    // 6. 通知失败 → 已提交状态保持不变，异常不冒泡
    @Test
    fun `consumer failure does not roll back committed state and does not propagate`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            val dispatcher = EventDispatcher()
            val delivered = mutableListOf<VfsEvent>()
            dispatcher.subscribe { event -> throw IllegalStateException("consumer exploded") }
            dispatcher.subscribe { event -> delivered.add(event) }

            val committed =
                uow.inTransaction { tx ->
                    tx.nodes.register(node("/notes/d.txt"))
                    val record = event("018f0a5c-1b2c-7def-8abc-000000000006")
                    tx.events.append(record)
                    record
                }

            val (_, _, events) = uow.snapshot()
            assertEquals(1, events.size)

            val publicEvent =
                VfsEvent(
                    id = committed.id,
                    type = committed.type,
                    nodeId = committed.nodeId,
                    occurredAt = committed.occurredAt,
                    uri = committed.uri,
                    operationId = committed.operationId,
                )
            // 首个消费者抛异常，分发器吞掉并继续投递给下一个消费者
            dispatcher.dispatch(listOf(publicEvent))
            assertEquals(1, delivered.size, "healthy consumer must still receive the event")
            assertEquals(1, uow.snapshot().first.size, "committed state must be unchanged by consumer failure")
        }

    // 7. 取消异常原样传播，事务不提交
    @Test
    fun `cancellation exception propagates unchanged and does not commit`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            val thrown = CancellationException("cancelled by caller")

            val caught =
                assertThrows(CancellationException::class.java) {
                    runBlocking {
                        uow.inTransaction { tx ->
                            tx.nodes.register(node("/notes/e.txt"))
                            throw thrown
                        }
                    }
                }
            assertEquals(thrown, caught)
            assertTrue(uow.snapshot().first.isEmpty())
            assertFalse(uow.calls.contains("commit"))
        }

    // 8. 事务对象逃逸：回调外使用抛 IllegalStateException
    @Test
    fun `transaction scope is invalid after block returns`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            var escaped: TransactionScope? = null

            uow.inTransaction { tx ->
                escaped = tx
                tx.nodes.register(node("/notes/f.txt"))
            }

            val scope = escaped
            assertNotNull(scope)
            val ex =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { scope!!.nodes.findByPath(VfsPath.parse("/notes/f.txt")) }
                }
            assertTrue(ex.message!!.contains("outside of inTransaction"))
        }

    // 9. 事务对象在提交失败后同样失效
    @Test
    fun `transaction scope is invalid after failed commit`() =
        runBlocking {
            val uow = FakeStateUnitOfWork()
            uow.failOnCommit = FakeStateUnitOfWork.storageFailure("commit failed")
            var escaped: TransactionScope? = null

            assertThrows(RuntimeException::class.java) {
                runBlocking {
                    uow.inTransaction { tx ->
                        escaped = tx
                        tx.nodes.register(node("/notes/g.txt"))
                    }
                }
            }

            val scope = escaped
            assertNotNull(scope)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { scope!!.events.append(event("018f0a5c-1b2c-7def-8abc-000000000007")) }
            }
            assertNull(uow.snapshot().first.firstOrNull())
        }

    // 12. R1 回归：消费者抛普通 Exception / RuntimeException 均被吞掉，后续消费者仍收到
    @Test
    fun `consumer checked exception is swallowed and later consumers still receive the event`() =
        runBlocking {
            val dispatcher = EventDispatcher()
            val delivered = mutableListOf<VfsEvent>()
            dispatcher.subscribe { throw java.io.IOException("io failure in consumer") }
            dispatcher.subscribe { delivered.add(it) }

            val record = event("018f0a5c-1b2c-7def-8abc-000000000009")
            dispatcher.dispatch(
                listOf(
                    VfsEvent(record.id, record.type, record.nodeId, record.occurredAt, record.uri, record.operationId),
                ),
            )
            assertEquals(1, delivered.size, "IOException must not escape dispatch or block later consumers")
        }

    @Test
    fun `consumer runtime exception is swallowed and later consumers still receive the event`() =
        runBlocking {
            val dispatcher = EventDispatcher()
            val delivered = mutableListOf<VfsEvent>()
            dispatcher.subscribe { throw IllegalStateException("runtime failure in consumer") }
            dispatcher.subscribe { delivered.add(it) }

            val record = event("018f0a5c-1b2c-7def-8abc-00000000000a")
            dispatcher.dispatch(
                listOf(
                    VfsEvent(record.id, record.type, record.nodeId, record.occurredAt, record.uri, record.operationId),
                ),
            )
            assertEquals(1, delivered.size, "RuntimeException must not escape dispatch or block later consumers")
        }

    // 13. R1 回归：CancellationException 原样传播，不被吞
    @Test
    fun `consumer cancellation propagates unchanged and is not swallowed`() =
        runBlocking {
            val dispatcher = EventDispatcher()
            val delivered = mutableListOf<VfsEvent>()
            val thrown = CancellationException("consumer cancelled")
            dispatcher.subscribe { throw thrown }
            dispatcher.subscribe { delivered.add(it) }

            val record = event("018f0a5c-1b2c-7def-8abc-00000000000b")
            val caught =
                assertThrows(CancellationException::class.java) {
                    runBlocking {
                        dispatcher.dispatch(
                            listOf(
                                VfsEvent(
                                    record.id,
                                    record.type,
                                    record.nodeId,
                                    record.occurredAt,
                                    record.uri,
                                    record.operationId,
                                ),
                            ),
                        )
                    }
                }
            assertEquals(thrown, caught)
            assertTrue(delivered.isEmpty(), "cancellation stops dispatch immediately")
        }

    // 10. 防泄漏反射测试
    @Test
    fun `transaction and event types do not leak infrastructure types`() {
        val types =
            listOf(
                UnitOfWork::class.java,
                TransactionScope::class.java,
                EventDispatcher::class.java,
                VfsEventConsumer::class.java,
            )
        val forbidden = listOf("opendal", "sqldelight", "sqlite", "jdbc", "java.sql", "java.nio.file")

        for (cls in types) {
            val allTypes = mutableListOf<Class<*>>()
            allTypes += cls.declaredMethods.flatMap { m -> listOf(m.returnType) + m.parameterTypes }
            allTypes += cls.methods.flatMap { m -> listOf(m.returnType) + m.parameterTypes }
            cls.declaredFields.forEach { allTypes.add(it.type) }
            for (type in allTypes) {
                val name = type.name.lowercase()
                for (keyword in forbidden) {
                    assertFalse(
                        name.contains(keyword),
                        "${cls.simpleName} leaks forbidden type: $name",
                    )
                }
            }
        }
    }

    // 11. 无消费者时 dispatch 是空操作；重复注册消费者按注册顺序投递
    @Test
    fun `dispatch is no-op without consumers and preserves subscription order`() =
        runBlocking {
            val dispatcher = EventDispatcher()
            val order = mutableListOf<String>()
            dispatcher.subscribe { order += "first" }
            dispatcher.subscribe { order += "second" }

            val record = event("018f0a5c-1b2c-7def-8abc-000000000008")
            dispatcher.dispatch(
                listOf(
                    VfsEvent(record.id, record.type, record.nodeId, record.occurredAt, record.uri, record.operationId),
                ),
            )
            assertEquals(listOf("first", "second"), order)

            val empty = EventDispatcher()
            empty.dispatch(emptyList())
        }
}
