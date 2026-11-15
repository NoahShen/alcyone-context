package com.github.noahshen.alcyone.context.vfs.core.recovery

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.repository.InMemoryNodeRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.state.StateBoundary
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OperationReconcilerTest {
    @Test
    fun `list pending and both reconcile entries share one state boundary`() =
        runBlocking {
            val boundary = StateBoundary()
            val storage = StorageFakeImpl().also { it.withFile("target.txt", "content") }
            storage.calls.clear()
            val nodes = TrackingNodeRepository(InMemoryNodeRepository())
            val journal = TrackingJournal(operation("target.txt"))
            val reconciler =
                OperationReconciler(
                    journal = journal,
                    nodes = nodes,
                    router = ROUTER,
                    storages = { key -> storage.takeIf { key == STORAGE_KEY } },
                    boundary = boundary,
                )

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder =
                async(start = CoroutineStart.UNDISPATCHED) {
                    boundary.withLock {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()

            val pending = async { reconciler.listPending() }
            val byId = async { reconciler.reconcile("operation-1") }
            val byValue = async { reconciler.reconcile(operation("target.txt")) }
            repeat(20) { yield() }

            assertFalse(pending.isCompleted)
            assertFalse(byId.isCompleted)
            assertFalse(byValue.isCompleted)
            assertEquals(0, journal.listPendingCalls)
            assertEquals(0, journal.findCalls)
            assertTrue(storage.calls.isEmpty(), "storage reads must not escape the shared boundary")
            assertEquals(0, nodes.findByPathCalls)

            release.complete(Unit)
            holder.await()

            assertEquals(listOf("operation-1"), pending.await().map { it.operationId })
            assertEquals(RecoveryPresence.PRESENT, byId.await()?.target?.presence)
            assertEquals(RecoveryPresence.PRESENT, byValue.await().target.presence)
            assertEquals(1, journal.findCalls)
            assertEquals(2, nodes.findByPathCalls)
            assertEquals(listOf("stat:target.txt", "stat:target.txt"), storage.calls)
        }

    @Test
    fun `unexpected node read failure is reported as state error inside the report`() =
        runBlocking {
            val nodes = TrackingNodeRepository(InMemoryNodeRepository(), failReads = true)
            val journal = TrackingJournal(operation("target.txt"))
            val reconciler =
                OperationReconciler(
                    journal = journal,
                    nodes = nodes,
                    router = ROUTER,
                    storages = { StorageFakeImpl() },
                    boundary = StateBoundary(),
                )

            val report = reconciler.reconcile(journal.operation)

            assertEquals(RecoveryPresence.ABSENT, report.target.presence)
            assertNull(report.targetNode.record)
            assertEquals(com.github.noahshen.alcyone.context.vfs.VfsErrorCode.STATE_ERROR, report.targetNode.errorCode)
        }

    @Test
    fun `unknown operation id returns null without reading storage or nodes`() =
        runBlocking {
            val storage = StorageFakeImpl()
            val nodes = TrackingNodeRepository(InMemoryNodeRepository())
            val journal = TrackingJournal(operation("target.txt"))
            val reconciler =
                OperationReconciler(
                    journal = journal,
                    nodes = nodes,
                    router = ROUTER,
                    storages = { key -> storage.takeIf { key == STORAGE_KEY } },
                    boundary = StateBoundary(),
                )

            assertNull(reconciler.reconcile("missing-operation"))
            assertEquals(1, journal.findCalls)
            assertTrue(storage.calls.isEmpty())
            assertEquals(0, nodes.findByPathCalls)
        }

    @Test
    fun `closed operations are excluded from pending and reconcile`() =
        runBlocking {
            val closed =
                operation("target.txt")
                    .transitionTo(RecoveryPhase.PUBLISH_OBSERVED, at = Instant.parse("2026-10-03T00:00:01Z"))
                    .transitionTo(RecoveryPhase.CLOSED, at = Instant.parse("2026-10-03T00:00:02Z"))
            val storage = StorageFakeImpl()
            val nodes = TrackingNodeRepository(InMemoryNodeRepository())
            val journal = TrackingJournal(closed)
            val reconciler =
                OperationReconciler(
                    journal = journal,
                    nodes = nodes,
                    router = ROUTER,
                    storages = { key -> storage.takeIf { key == STORAGE_KEY } },
                    boundary = StateBoundary(),
                )

            assertTrue(reconciler.listPending().isEmpty())
            assertNull(reconciler.reconcile(closed.operationId))
            assertEquals(1, journal.listPendingCalls)
            assertEquals(1, journal.findCalls)
            assertTrue(storage.calls.isEmpty())
            assertEquals(0, nodes.findByPathCalls)
        }

    @Test
    fun `closed operation value is rejected before reading journal storage or nodes`() =
        runBlocking {
            val closed =
                operation("target.txt")
                    .transitionTo(RecoveryPhase.PUBLISH_OBSERVED, at = Instant.parse("2026-10-03T00:00:01Z"))
                    .transitionTo(RecoveryPhase.CLOSED, at = Instant.parse("2026-10-03T00:00:02Z"))
            val storage = StorageFakeImpl()
            val nodes = TrackingNodeRepository(InMemoryNodeRepository())
            val journal = TrackingJournal(closed)
            val reconciler =
                OperationReconciler(
                    journal = journal,
                    nodes = nodes,
                    router = ROUTER,
                    storages = { key -> storage.takeIf { key == STORAGE_KEY } },
                    boundary = StateBoundary(),
                )

            assertFailsWith<IllegalArgumentException> { reconciler.reconcile(closed) }
            assertEquals(0, journal.findCalls)
            assertTrue(storage.calls.isEmpty())
            assertEquals(0, nodes.findByPathCalls)
        }

    @Test
    fun `storage UNKNOWN stays unknown for source and target and requires recovery`() =
        runBlocking {
            val operation = moveOperation(sourcePath = "source.txt", targetPath = "target.txt")
            val storage = StorageFakeImpl()
            storage.failures.onStat =
                VfsException(
                    code = VfsErrorCode.STORAGE_ERROR,
                    message = "backend outcome is unknown",
                    effect = VfsEffect.UNKNOWN,
                )
            val journal = TrackingJournal(operation)
            val reconciler =
                OperationReconciler(
                    journal = journal,
                    nodes = TrackingNodeRepository(InMemoryNodeRepository()),
                    router = ROUTER,
                    storages = { key -> storage.takeIf { key == STORAGE_KEY } },
                    boundary = StateBoundary(),
                )

            val report = reconciler.reconcile(operation)
            val source = requireNotNull(report.source)

            assertEquals(operation.sourceUri, source.uri)
            assertEquals(RecoveryPresence.UNKNOWN, source.presence)
            assertEquals(VfsErrorCode.STORAGE_ERROR, source.errorCode)
            assertEquals(operation.targetUri, report.target.uri)
            assertEquals(RecoveryPresence.UNKNOWN, report.target.presence)
            assertEquals(VfsErrorCode.STORAGE_ERROR, report.target.errorCode)
            assertEquals(VfsErrorCode.RECOVERY_REQUIRED, report.verdict)
        }

    @Test
    fun `missing node records stay null while storage presence facts are retained`() =
        runBlocking {
            val operation = moveOperation(sourcePath = "source.txt", targetPath = "target.txt")
            val storage = StorageFakeImpl().also { it.withFile("source.txt", "source content") }
            val nodes = TrackingNodeRepository(InMemoryNodeRepository())
            val journal = TrackingJournal(operation)
            val reconciler =
                OperationReconciler(
                    journal = journal,
                    nodes = nodes,
                    router = ROUTER,
                    storages = { key -> storage.takeIf { key == STORAGE_KEY } },
                    boundary = StateBoundary(),
                )

            val report = reconciler.reconcile(operation)
            val sourceNode = requireNotNull(report.sourceNode)

            assertEquals(RecoveryPresence.PRESENT, requireNotNull(report.source).presence)
            assertEquals(RecoveryPresence.ABSENT, report.target.presence)
            assertNull(sourceNode.record)
            assertNull(report.targetNode.record)
            assertNull(sourceNode.errorCode)
            assertNull(report.targetNode.errorCode)
            assertEquals(2, nodes.findByPathCalls)
        }

    @Test
    fun `reconcile remains read only across journal node storage and report calls`() =
        runBlocking {
            val operation = moveOperation(sourcePath = "source.txt", targetPath = "target.txt")
            val storage =
                StorageFakeImpl().also {
                    it.withFile("source.txt", "source content")
                    it.withFile("target.txt", "target content")
                }
            val nodes = TrackingNodeRepository(InMemoryNodeRepository())
            val journal = TrackingJournal(operation)
            val reconciler =
                OperationReconciler(
                    journal = journal,
                    nodes = nodes,
                    router = ROUTER,
                    storages = { key -> storage.takeIf { key == STORAGE_KEY } },
                    boundary = StateBoundary(),
                )

            val storageCallsBefore = storage.calls.toList()
            val nodeReadsBefore = nodes.findByPathCalls
            val byId = requireNotNull(reconciler.reconcile(operation.operationId))
            val byOperation = reconciler.reconcile(operation)

            assertEquals(operation.operationId, byId.operation.operationId)
            assertEquals(operation.operationId, byOperation.operation.operationId)
            assertEquals(0, journal.beginCalls)
            assertEquals(0, journal.advanceCalls)
            assertEquals(0, journal.diagnoseCalls)
            assertEquals(0, nodes.registerCalls)
            assertEquals(0, nodes.updatePathCalls)
            assertEquals(0, nodes.touchCalls)
            assertEquals(0, nodes.markDeletedCalls)
            assertEquals(nodeReadsBefore + 4, nodes.findByPathCalls)
            val reconcileStorageCalls = storage.calls.drop(storageCallsBefore.size)
            assertEquals(4, reconcileStorageCalls.size)
            assertTrue(reconcileStorageCalls.all { it.startsWith("stat:") })
        }

    private fun operation(path: String): RecoveryOperation =
        RecoveryOperation.begin(
            operationId = "operation-1",
            kind = RecoveryOperationKind.PUBLISH_NEW,
            sourceUri = null,
            targetUri = VfsUri.parse("alcyone://resources/$path"),
            sourceStorageKey = null,
            targetStorageKey = STORAGE_KEY,
            nodeId = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000e1"),
            at = Instant.parse("2026-10-03T00:00:00Z"),
        )

    private fun moveOperation(
        sourcePath: String,
        targetPath: String,
    ): RecoveryOperation =
        RecoveryOperation.begin(
            operationId = "operation-1",
            kind = RecoveryOperationKind.NATIVE_FILE_MOVE,
            sourceUri = VfsUri.parse("alcyone://resources/$sourcePath"),
            targetUri = VfsUri.parse("alcyone://resources/$targetPath"),
            sourceStorageKey = STORAGE_KEY,
            targetStorageKey = STORAGE_KEY,
            nodeId = NodeId.parse("018f0a5c-1b2c-7def-8abc-0000000000e1"),
            at = Instant.parse("2026-10-03T00:00:00Z"),
        )

    private class TrackingJournal(
        val operation: RecoveryOperation,
    ) : RecoveryJournal {
        var beginCalls = 0
        var advanceCalls = 0
        var diagnoseCalls = 0
        var findCalls = 0
        var listPendingCalls = 0

        override suspend fun begin(operation: RecoveryOperation): RecoveryOperation {
            beginCalls++
            error("not used")
        }

        override suspend fun advance(
            operationId: String,
            expectedGeneration: Long,
            nextPhase: RecoveryPhase,
            diagnosticCode: RecoveryDiagnosticCode,
            at: Instant,
        ): RecoveryOperation {
            advanceCalls++
            error("not used")
        }

        override suspend fun diagnose(
            operationId: String,
            expectedGeneration: Long,
            diagnosticCode: RecoveryDiagnosticCode,
            at: Instant,
        ): RecoveryOperation {
            diagnoseCalls++
            error("not used")
        }

        override suspend fun find(operationId: String): RecoveryOperation? {
            findCalls++
            return operation.takeIf { it.operationId == operationId }
        }

        override suspend fun listPending(): List<RecoveryOperation> {
            listPendingCalls++
            return listOf(operation)
        }
    }

    private class TrackingNodeRepository(
        private val delegate: NodeRepository,
        private val failReads: Boolean = false,
    ) : NodeRepository by delegate {
        var findByPathCalls = 0
        var registerCalls = 0
        var updatePathCalls = 0
        var touchCalls = 0
        var markDeletedCalls = 0

        override suspend fun findByPath(path: VfsPath): NodeRecord? {
            findByPathCalls++
            if (failReads) throw IllegalStateException("node store unavailable")
            return delegate.findByPath(path)
        }

        override suspend fun register(record: NodeRecord): NodeRecord {
            registerCalls++
            return delegate.register(record)
        }

        override suspend fun updatePath(
            id: NodeId,
            newPath: VfsPath,
            updatedAt: Instant,
        ) {
            updatePathCalls++
            delegate.updatePath(id, newPath, updatedAt)
        }

        override suspend fun touch(
            id: NodeId,
            updatedAt: Instant,
        ) {
            touchCalls++
            delegate.touch(id, updatedAt)
        }

        override suspend fun markDeleted(
            ids: Collection<NodeId>,
            deletedAt: Instant,
        ) {
            markDeletedCalls++
            delegate.markDeleted(ids, deletedAt)
        }
    }

    private companion object {
        const val STORAGE_KEY = "resources"
        val ROUTER: MountRouter =
            MountRouter.of(
                setOf("resources"),
                listOf(MountRecord(VfsPath.parse("/resources"), STORAGE_KEY)),
            )
    }
}
