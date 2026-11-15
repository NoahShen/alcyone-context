package com.github.noahshen.alcyone.context.vfs.core

import com.github.noahshen.alcyone.context.vfs.PublishOptions
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.event.AsyncEventNotifier
import com.github.noahshen.alcyone.context.vfs.core.event.EventFactory
import com.github.noahshen.alcyone.context.vfs.core.event.EventPipeline
import com.github.noahshen.alcyone.context.vfs.core.event.TrackedNotifiers
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryDiagnosticCode
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryJournal
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryOperation
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryOperationKind
import com.github.noahshen.alcyone.context.vfs.core.recovery.RecoveryPhase
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import com.github.noahshen.alcyone.context.vfs.core.transaction.FakeStateUnitOfWork
import com.github.noahshen.alcyone.context.vfs.core.transaction.RecoveryCloseRepository
import com.github.noahshen.alcyone.context.vfs.core.transaction.TransactionScope
import com.github.noahshen.alcyone.context.vfs.core.transaction.UnitOfWork
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlin.test.assertFailsWith

/** CT30 B1：四文件操作成功时，journal 与 Node/Event 提交必须形成可观察的完整进度。 */
class DefaultVfsRecoveryProgressTest {
    private val notifiers = TrackedNotifiers()

    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }

    @Test
    fun `safeReplace commits requested observed and closed progress`() =
        runBlocking {
            val disk = StorageFakeImpl(StorageCapabilities(atomicReplace = true))
            val harness =
                VfsHarness(
                    mounts = listOf(VfsHarness.Mounted("/resources", disk)),
                    notifier = notifiers.create(),
                )
            val journal = RecordingRecoveryJournal()
            val vfs = journaledVfs(harness, journal, notifiers.create())

            val info = vfs.safeReplace(uri("/notes/a.txt"), "replacement".toByteArray())

            assertEquals("replacement", disk.readText("notes/a.txt"))
            assertEquals(
                listOf(RecoveryPhase.STORAGE_REPLACE_REQUESTED, RecoveryPhase.STORAGE_REPLACE_OBSERVED, RecoveryPhase.CLOSED),
                journal.observedPhases,
            )
            val operation = journal.singleOperation()
            assertEquals(RecoveryOperationKind.SAFE_REPLACE, operation.kind)
            assertEquals(RecoveryPhase.CLOSED, operation.phase)
            assertEquals(2L, operation.generation)
            assertEquals(RecoveryDiagnosticCode.NONE, operation.diagnosticCode)
            assertEquals(null, operation.nodeId)
            assertEquals(setOf(operation.operationId), harness.uow.closedRecoveryOperations())
            assertEquals(listOf(VfsEventType.FILE_CREATED), harness.committedEvents().map { it.type })
            assertTrue(journal.listPending().isEmpty())
        }

    @Test
    fun `publishNew commits requested observed and closed progress`() =
        runBlocking {
            val disk = StorageFakeImpl(StorageCapabilities(atomicPublishNew = true))
            val harness =
                VfsHarness(
                    mounts = listOf(VfsHarness.Mounted("/resources", disk)),
                    notifier = notifiers.create(),
                )
            val journal = RecordingRecoveryJournal()
            val vfs = journaledVfs(harness, journal, notifiers.create())

            val info =
                vfs.publishNew(
                    uri("/notes/published.txt"),
                    ByteArrayInputStream("published".toByteArray()),
                    PublishOptions(maxBytes = 9, expectedSizeBytes = 9),
                )

            assertEquals("published", disk.readText("notes/published.txt"))
            assertEquals(
                listOf(RecoveryPhase.PUBLISH_REQUESTED, RecoveryPhase.PUBLISH_OBSERVED, RecoveryPhase.CLOSED),
                journal.observedPhases,
            )
            val operation = journal.singleOperation()
            assertEquals(RecoveryOperationKind.PUBLISH_NEW, operation.kind)
            assertEquals(RecoveryPhase.CLOSED, operation.phase)
            assertEquals(2L, operation.generation)
            assertEquals(RecoveryDiagnosticCode.NONE, operation.diagnosticCode)
            assertEquals(info.id, operation.nodeId)
            assertEquals(setOf(operation.operationId), harness.uow.closedRecoveryOperations())
            assertEquals(listOf(VfsEventType.FILE_CREATED), harness.committedEvents().map { it.type })
            assertTrue(journal.listPending().isEmpty())
        }

    @Test
    fun `native move commits requested observed and closed progress`() =
        runBlocking {
            val disk = StorageFakeImpl().also { it.withFile("a.txt", "hello") }
            val harness =
                VfsHarness(
                    mounts = listOf(VfsHarness.Mounted("/resources", disk)),
                    notifier = notifiers.create(),
                    shareNodeState = true,
                )
            val original = harness.seedNode("/resources/a.txt")
            val journal = RecordingRecoveryJournal()
            val vfs = journaledVfs(harness, journal, notifiers.create())

            val info = vfs.move(uri("/a.txt"), uri("/archive/b.txt"))

            assertEquals(original.id, info.id)
            assertEquals("hello", disk.readText("archive/b.txt"))
            assertEquals(
                listOf(
                    RecoveryPhase.NATIVE_MOVE_REQUESTED,
                    RecoveryPhase.NATIVE_MOVE_OBSERVED,
                    RecoveryPhase.CLOSED,
                ),
                journal.observedPhases,
            )
            val operation = journal.singleOperation()
            assertEquals(RecoveryOperationKind.NATIVE_FILE_MOVE, operation.kind)
            assertEquals(RecoveryPhase.CLOSED, operation.phase)
            assertEquals(2L, operation.generation)
            assertEquals(RecoveryDiagnosticCode.NONE, operation.diagnosticCode)
            assertEquals(original.id, operation.nodeId)
            assertEquals(setOf(operation.operationId), harness.uow.closedRecoveryOperations())
            assertEquals(listOf(VfsEventType.FILE_MOVED), harness.committedEvents().map { it.type })
            assertTrue(journal.listPending().isEmpty())
        }

    @Test
    fun `copy move commits every requested and observed phase before close`() =
        runBlocking {
            val source = StorageFakeImpl().also { it.withFile("a.txt", "hello") }
            val target = StorageFakeImpl()
            val harness =
                VfsHarness(
                    mounts =
                        listOf(
                            VfsHarness.Mounted("/resources", source, key = "left"),
                            VfsHarness.Mounted("/resources/archive", target, key = "right"),
                        ),
                    notifier = notifiers.create(),
                    shareNodeState = true,
                )
            val original = harness.seedNode("/resources/a.txt")
            val journal = RecordingRecoveryJournal()
            val vfs = journaledVfs(harness, journal, notifiers.create())

            val info = vfs.move(uri("/a.txt"), uri("/archive/b.txt"))

            assertEquals(original.id, info.id)
            assertEquals(null, source.typeOfOrNull("a.txt"))
            assertEquals("hello", target.readText("b.txt"))
            assertEquals(
                listOf(
                    RecoveryPhase.TARGET_WRITE_REQUESTED,
                    RecoveryPhase.TARGET_WRITE_OBSERVED,
                    RecoveryPhase.SOURCE_DELETE_REQUESTED,
                    RecoveryPhase.SOURCE_DELETE_OBSERVED,
                    RecoveryPhase.CLOSED,
                ),
                journal.observedPhases,
            )
            val operation = journal.singleOperation()
            assertEquals(RecoveryOperationKind.COPY_FILE_MOVE, operation.kind)
            assertEquals(RecoveryPhase.CLOSED, operation.phase)
            assertEquals(4L, operation.generation)
            assertEquals(RecoveryDiagnosticCode.NONE, operation.diagnosticCode)
            assertEquals(original.id, operation.nodeId)
            assertEquals(setOf(operation.operationId), harness.uow.closedRecoveryOperations())
            assertEquals(listOf(VfsEventType.FILE_MOVED), harness.committedEvents().map { it.type })
            assertTrue(journal.listPending().isEmpty())
        }

    @Test
    fun `copy move does not delete source when SOURCE_DELETE_REQUESTED progress cannot persist`() =
        runBlocking {
            val source = StorageFakeImpl().also { it.withFile("a.txt", "hello") }
            val target = StorageFakeImpl()
            val harness =
                VfsHarness(
                    mounts =
                        listOf(
                            VfsHarness.Mounted("/resources", source, key = "left"),
                            VfsHarness.Mounted("/resources/archive", target, key = "right"),
                        ),
                    notifier = notifiers.create(),
                    shareNodeState = true,
                )
            harness.seedNode("/resources/a.txt")
            val progressFailure = IllegalStateException("journal could not record source delete request")
            val journal =
                RecordingRecoveryJournal(
                    advanceFailurePhase = RecoveryPhase.SOURCE_DELETE_REQUESTED,
                    advanceFailure = progressFailure,
                )
            val vfs = journaledVfs(harness, journal, notifiers.create())
            source.calls.clear()
            target.calls.clear()

            val failure = assertFailsWith<VfsException> { vfs.move(uri("/a.txt"), uri("/archive/b.txt")) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect)
            assertSame(progressFailure, failure.cause)
            assertEquals("hello", source.readText("a.txt"))
            assertEquals("hello", target.readText("b.txt"))
            assertTrue(source.calls.none { it.startsWith("delete:") }, "进度失败后绝不能删源：${source.calls}")
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() })
            assertTrue(harness.committedEvents().isEmpty())
            assertEquals(
                listOf(RecoveryPhase.TARGET_WRITE_REQUESTED, RecoveryPhase.TARGET_WRITE_OBSERVED),
                journal.observedPhases,
            )
            val operation = journal.singleOperation()
            assertEquals(RecoveryPhase.TARGET_WRITE_OBSERVED, operation.phase)
            assertEquals(1L, operation.generation)
            assertEquals(RecoveryDiagnosticCode.NONE, operation.diagnosticCode)
            assertEquals(listOf(operation), journal.listPending())
        }

    @Test
    fun `storage failure diagnosis is best effort and never masks the primary failure`() =
        runBlocking {
            val source = StorageFakeImpl().also { it.withFile("a.txt", "hello") }
            val target = StorageFakeImpl()
            val backendFailure = VfsException(VfsErrorCode.STORAGE_ERROR, "source delete failed")
            source.failures.onDelete = backendFailure
            val diagnosisFailure = IllegalStateException("journal diagnosis unavailable")
            val harness =
                VfsHarness(
                    mounts =
                        listOf(
                            VfsHarness.Mounted("/resources", source, key = "left"),
                            VfsHarness.Mounted("/resources/archive", target, key = "right"),
                        ),
                    notifier = notifiers.create(),
                    shareNodeState = true,
                )
            harness.seedNode("/resources/a.txt")
            val journal = RecordingRecoveryJournal(diagnoseFailure = diagnosisFailure)
            val vfs = journaledVfs(harness, journal, notifiers.create())

            val failure = assertFailsWith<VfsException> { vfs.move(uri("/a.txt"), uri("/archive/b.txt")) }

            assertEquals(VfsErrorCode.STORAGE_ERROR, failure.code)
            assertEquals(VfsEffect.PARTIAL, failure.effect)
            assertSame(backendFailure, failure.cause)
            assertEquals(1, failure.suppressed.size)
            assertSame(diagnosisFailure, failure.suppressed.single())
            assertEquals("hello", source.readText("a.txt"))
            assertEquals("hello", target.readText("b.txt"))
            assertEquals(listOf("/resources/a.txt"), harness.committedNodes().map { it.path.toString() })
            assertTrue(harness.committedEvents().isEmpty())
            val operation = journal.singleOperation()
            assertEquals(RecoveryPhase.SOURCE_DELETE_REQUESTED, operation.phase)
            assertEquals(2L, operation.generation)
            assertEquals(RecoveryDiagnosticCode.NONE, operation.diagnosticCode)
            assertEquals(listOf(operation), journal.listPending())
        }

    @Test
    fun `all four operations keep their behavior when no recovery journal is configured`() =
        runBlocking {
            val source =
                StorageFakeImpl(
                    StorageCapabilities(atomicReplace = true, atomicPublishNew = true),
                ).also { it.withFile("a.txt", "hello") }
            val target = StorageFakeImpl()
            val harness =
                VfsHarness(
                    mounts =
                        listOf(
                            VfsHarness.Mounted("/resources", source, key = "left"),
                            VfsHarness.Mounted("/resources/archive", target, key = "right"),
                        ),
                    notifier = notifiers.create(),
                    shareNodeState = true,
                )
            harness.seedNode("/resources/a.txt")

            harness.vfs.safeReplace(uri("/safe.txt"), "safe".toByteArray())
            harness.vfs.publishNew(
                uri("/published.txt"),
                ByteArrayInputStream("published".toByteArray()),
                PublishOptions(maxBytes = 9, expectedSizeBytes = 9),
            )
            harness.vfs.move(uri("/a.txt"), uri("/renamed.txt"))
            harness.vfs.move(uri("/renamed.txt"), uri("/archive/copied.txt"))

            assertEquals("safe", source.readText("safe.txt"))
            assertEquals("published", source.readText("published.txt"))
            assertNull(source.typeOfOrNull("a.txt"))
            assertNull(source.typeOfOrNull("renamed.txt"))
            assertEquals("hello", target.readText("copied.txt"))
            assertEquals(
                listOf(VfsEventType.FILE_CREATED, VfsEventType.FILE_CREATED, VfsEventType.FILE_MOVED, VfsEventType.FILE_MOVED),
                harness.committedEvents().map { it.type },
            )
            assertTrue(harness.uow.closedRecoveryOperations().isEmpty())
        }

    @Test
    fun `safeReplace begin failure leaves an existing target untouched`() =
        runBlocking {
            val disk = StorageFakeImpl(StorageCapabilities(atomicReplace = true)).also { it.withFile("a.txt", "old") }
            val harness =
                VfsHarness(
                    mounts = listOf(VfsHarness.Mounted("/resources", disk)),
                    notifier = notifiers.create(),
                )
            val journal = RecordingRecoveryJournal(beginFailure = VfsException(VfsErrorCode.STATE_ERROR, "journal unavailable"))
            val vfs = journaledVfs(harness, journal, notifiers.create())
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { vfs.safeReplace(uri("/a.txt"), "new".toByteArray()) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.NONE, failure.effect)
            assertEquals(listOf("stat:a.txt"), disk.calls)
            assertEquals("old", disk.readText("a.txt"))
            assertEquals(listOf(RecoveryOperationKind.SAFE_REPLACE), journal.beginKinds)
            assertTrue(journal.operations().isEmpty())
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test
    fun `publishNew begin failure does not publish the input`() =
        runBlocking {
            val disk = StorageFakeImpl(StorageCapabilities(atomicPublishNew = true))
            val harness =
                VfsHarness(
                    mounts = listOf(VfsHarness.Mounted("/resources", disk)),
                    notifier = notifiers.create(),
                )
            val journal = RecordingRecoveryJournal(beginFailure = VfsException(VfsErrorCode.STATE_ERROR, "journal unavailable"))
            val vfs = journaledVfs(harness, journal, notifiers.create())
            disk.calls.clear()

            val failure =
                assertFailsWith<VfsException> {
                    vfs.publishNew(
                        uri("/published.txt"),
                        ByteArrayInputStream("published".toByteArray()),
                        PublishOptions(maxBytes = 9, expectedSizeBytes = 9),
                    )
                }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.NONE, failure.effect)
            assertEquals(listOf("stat:published.txt"), disk.calls)
            assertNull(disk.typeOfOrNull("published.txt"))
            assertEquals(listOf(RecoveryOperationKind.PUBLISH_NEW), journal.beginKinds)
            assertTrue(journal.operations().isEmpty())
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test
    fun `native move begin failure leaves the source and target untouched`() =
        runBlocking {
            val disk = StorageFakeImpl().also { it.withFile("a.txt", "hello") }
            val harness =
                VfsHarness(
                    mounts = listOf(VfsHarness.Mounted("/resources", disk)),
                    notifier = notifiers.create(),
                    shareNodeState = true,
                )
            harness.seedNode("/resources/a.txt")
            val journal = RecordingRecoveryJournal(beginFailure = VfsException(VfsErrorCode.STATE_ERROR, "journal unavailable"))
            val vfs = journaledVfs(harness, journal, notifiers.create())
            disk.calls.clear()

            val failure = assertFailsWith<VfsException> { vfs.move(uri("/a.txt"), uri("/b.txt")) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.NONE, failure.effect)
            assertEquals("hello", disk.readText("a.txt"))
            assertNull(disk.typeOfOrNull("b.txt"))
            assertTrue(disk.calls.none { it.startsWith("move:") }, "journal begin 失败后不得移动文件：${disk.calls}")
            assertEquals(listOf(RecoveryOperationKind.NATIVE_FILE_MOVE), journal.beginKinds)
            assertTrue(journal.operations().isEmpty())
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test
    fun `copy move begin failure does not write target or delete source after the journal request`() =
        runBlocking {
            val source = StorageFakeImpl().also { it.withFile("a.txt", "hello") }
            val target = StorageFakeImpl()
            val harness =
                VfsHarness(
                    mounts =
                        listOf(
                            VfsHarness.Mounted("/resources", source, key = "left"),
                            VfsHarness.Mounted("/resources/archive", target, key = "right"),
                        ),
                    notifier = notifiers.create(),
                    shareNodeState = true,
                )
            harness.seedNode("/resources/a.txt")
            val journal = RecordingRecoveryJournal(beginFailure = VfsException(VfsErrorCode.STATE_ERROR, "journal unavailable"))
            val vfs = journaledVfs(harness, journal, notifiers.create())
            source.calls.clear()
            target.calls.clear()

            val failure = assertFailsWith<VfsException> { vfs.move(uri("/a.txt"), uri("/archive/b.txt")) }

            assertEquals(VfsErrorCode.STATE_ERROR, failure.code)
            assertEquals(VfsEffect.NONE, failure.effect)
            assertEquals(1, source.calls.count { it.startsWith("read:") }, "begin 前允许完成一次源读取：${source.calls}")
            assertTrue(source.calls.none { it.startsWith("delete:") }, "源端不得删除：${source.calls}")
            assertTrue(target.calls.none { it.startsWith("write:") || it.startsWith("delete:") }, "目标端不得写入：${target.calls}")
            assertEquals("hello", source.readText("a.txt"))
            assertNull(target.typeOfOrNull("b.txt"))
            assertEquals(listOf(RecoveryOperationKind.COPY_FILE_MOVE), journal.beginKinds)
            assertTrue(journal.operations().isEmpty())
            assertTrue(harness.committedEvents().isEmpty())
        }

    @Test
    fun `preflight rejections do not request a recovery operation`() =
        runBlocking {
            val safeDisk = StorageFakeImpl(StorageCapabilities(atomicReplace = true))
            val safeHarness =
                VfsHarness(
                    mounts = listOf(VfsHarness.Mounted("/resources", safeDisk)),
                    limits = VfsLimits(defaultWriteMaxBytes = 3),
                    notifier = notifiers.create(),
                )
            val safeJournal = RecordingRecoveryJournal()
            val safeVfs =
                journaledVfs(
                    safeHarness,
                    safeJournal,
                    notifiers.create(),
                    limits = VfsLimits(defaultWriteMaxBytes = 3),
                )

            val safeFailure = assertFailsWith<VfsException> { safeVfs.safeReplace(uri("/a.txt"), "four".toByteArray()) }

            assertEquals(VfsErrorCode.LIMIT_EXCEEDED, safeFailure.code)
            assertTrue(safeJournal.beginKinds.isEmpty())
            assertTrue(safeDisk.calls.isEmpty())

            val publishDisk = StorageFakeImpl(StorageCapabilities(atomicPublishNew = true))
            val publishHarness =
                VfsHarness(
                    mounts = listOf(VfsHarness.Mounted("/resources", publishDisk)),
                    notifier = notifiers.create(),
                )
            val publishJournal = RecordingRecoveryJournal()
            val publishVfs = journaledVfs(publishHarness, publishJournal, notifiers.create())

            val publishFailure =
                assertFailsWith<VfsException> {
                    publishVfs.publishNew(
                        uri("/published.txt"),
                        ByteArrayInputStream("published".toByteArray()),
                        PublishOptions(maxBytes = 8, expectedSizeBytes = 9),
                    )
                }

            assertEquals(VfsErrorCode.INVALID_ARGUMENT, publishFailure.code)
            assertTrue(publishJournal.beginKinds.isEmpty())
            assertTrue(publishDisk.calls.isEmpty())

            val nativeDisk = StorageFakeImpl()
            val nativeHarness =
                VfsHarness(
                    mounts = listOf(VfsHarness.Mounted("/resources", nativeDisk)),
                    notifier = notifiers.create(),
                )
            val nativeJournal = RecordingRecoveryJournal()
            val nativeVfs = journaledVfs(nativeHarness, nativeJournal, notifiers.create())

            val nativeFailure = assertFailsWith<VfsException> { nativeVfs.move(uri("/a.txt"), uri("/a.txt")) }

            assertEquals(VfsErrorCode.INVALID_ARGUMENT, nativeFailure.code)
            assertTrue(nativeJournal.beginKinds.isEmpty())
            assertTrue(nativeDisk.calls.isEmpty())

            val copySource = StorageFakeImpl().also { it.withFile("a.txt", "hello") }
            val copyTarget = StorageFakeImpl().also { it.withFile("b.txt", "occupied") }
            val copyHarness =
                VfsHarness(
                    mounts =
                        listOf(
                            VfsHarness.Mounted("/resources", copySource, key = "left"),
                            VfsHarness.Mounted("/resources/archive", copyTarget, key = "right"),
                        ),
                    notifier = notifiers.create(),
                    shareNodeState = true,
                )
            copyHarness.seedNode("/resources/a.txt")
            val copyJournal = RecordingRecoveryJournal()
            val copyVfs = journaledVfs(copyHarness, copyJournal, notifiers.create())
            copySource.calls.clear()
            copyTarget.calls.clear()

            val copyFailure = assertFailsWith<VfsException> { copyVfs.move(uri("/a.txt"), uri("/archive/b.txt")) }

            assertEquals(VfsErrorCode.ALREADY_EXISTS, copyFailure.code)
            assertTrue(copyJournal.beginKinds.isEmpty())
            assertTrue(copySource.calls.none { it.startsWith("read:") || it.startsWith("delete:") })
            assertTrue(copyTarget.calls.none { it.startsWith("write:") || it.startsWith("delete:") })
            assertEquals("hello", copySource.readText("a.txt"))
            assertEquals("occupied", copyTarget.readText("b.txt"))
        }

    private fun journaledVfs(
        harness: VfsHarness,
        journal: RecordingRecoveryJournal,
        notifier: AsyncEventNotifier,
        limits: VfsLimits = VfsLimits(),
    ): DefaultVfs {
        val unitOfWork = ClosingRecoveryUnitOfWork(harness.uow, journal)
        return DefaultVfs(
            router = harness.router,
            capabilities = harness.capabilities,
            registry = harness.registry,
            nodes = harness.nodes,
            metadata = harness.metadata,
            storages = { key -> harness.mounts.firstOrNull { it.key == key }?.storage },
            boundary = harness.boundary,
            pipeline = EventPipeline(harness.boundary, unitOfWork, notifier),
            events = EventFactory(harness.clock),
            limits = limits,
            clock = harness.clock,
            recoveryJournal = journal,
        )
    }

    private fun uri(path: String): VfsUri = VfsUri.parse("alcyone://resources$path")

    /** 让测试替身的同事务 close 真正反映到 journal，而不是只记录一个 UoW 调用字符串。 */
    private class ClosingRecoveryUnitOfWork(
        private val delegate: FakeStateUnitOfWork,
        private val journal: RecordingRecoveryJournal,
    ) : UnitOfWork {
        override suspend fun <T> inTransaction(block: suspend (TransactionScope) -> T): T {
            val closes = mutableListOf<Close>()
            val result =
                delegate.inTransaction { scope ->
                    block(
                        object : TransactionScope {
                            override val nodes get() = scope.nodes
                            override val metadata get() = scope.metadata
                            override val events get() = scope.events
                            override val recovery: RecoveryCloseRepository =
                                object : RecoveryCloseRepository {
                                    override suspend fun close(
                                        operationId: String,
                                        expectedGeneration: Long,
                                        at: Instant,
                                    ) {
                                        scope.recovery.close(operationId, expectedGeneration, at)
                                        closes += Close(operationId, expectedGeneration, at)
                                    }
                                }
                        },
                    )
                }
            closes.forEach { journal.close(it.operationId, it.expectedGeneration, it.at) }
            return result
        }

        private data class Close(
            val operationId: String,
            val expectedGeneration: Long,
            val at: Instant,
        )
    }

    /** 在内存里执行与持久 journal 相同的 phase/generation 状态机，供成功进度测试断言。 */
    private class RecordingRecoveryJournal(
        private val beginFailure: Exception? = null,
        private val advanceFailurePhase: RecoveryPhase? = null,
        private val advanceFailure: Exception? = null,
        private val diagnoseFailure: Exception? = null,
    ) : RecoveryJournal {
        private val operationsById = linkedMapOf<String, RecoveryOperation>()
        val observedPhases: MutableList<RecoveryPhase> = mutableListOf()
        val beginKinds: MutableList<RecoveryOperationKind> = mutableListOf()

        fun singleOperation(): RecoveryOperation = operationsById.values.single()

        fun operations(): List<RecoveryOperation> = operationsById.values.toList()

        fun close(
            operationId: String,
            expectedGeneration: Long,
            at: Instant,
        ) {
            val current = operationsById.getValue(operationId)
            check(current.generation == expectedGeneration)
            val closed = current.transitionTo(RecoveryPhase.CLOSED, RecoveryDiagnosticCode.NONE, at)
            operationsById[operationId] = closed
            observedPhases += closed.phase
        }

        override suspend fun begin(operation: RecoveryOperation): RecoveryOperation {
            beginKinds += operation.kind
            beginFailure?.let { throw it }
            check(operationsById.put(operation.operationId, operation) == null)
            observedPhases += operation.phase
            return operation
        }

        override suspend fun advance(
            operationId: String,
            expectedGeneration: Long,
            nextPhase: RecoveryPhase,
            diagnosticCode: RecoveryDiagnosticCode,
            at: Instant,
        ): RecoveryOperation {
            val current = operationsById.getValue(operationId)
            check(current.generation == expectedGeneration)
            if (nextPhase == advanceFailurePhase) advanceFailure?.let { throw it }
            val next = current.transitionTo(nextPhase, diagnosticCode, at)
            operationsById[operationId] = next
            observedPhases += next.phase
            return next
        }

        override suspend fun diagnose(
            operationId: String,
            expectedGeneration: Long,
            diagnosticCode: RecoveryDiagnosticCode,
            at: Instant,
        ): RecoveryOperation {
            val current = operationsById.getValue(operationId)
            check(current.generation == expectedGeneration)
            diagnoseFailure?.let { throw it }
            val next = current.withDiagnostic(diagnosticCode, at)
            operationsById[operationId] = next
            observedPhases += next.phase
            return next
        }

        override suspend fun find(operationId: String): RecoveryOperation? = operationsById[operationId]

        override suspend fun listPending(): List<RecoveryOperation> = operationsById.values.filter { it.phase != RecoveryPhase.CLOSED }
    }
}
