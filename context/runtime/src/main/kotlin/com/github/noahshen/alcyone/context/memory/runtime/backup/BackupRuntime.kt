package com.github.noahshen.alcyone.context.memory.runtime.backup

import com.github.noahshen.alcyone.context.memory.api.*
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextBackupSnapshotter
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.DreamingPeriodicCron
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.DreamingPeriodicTickSource
import com.github.noahshen.alcyone.context.vfs.PublishOptions
import com.github.noahshen.alcyone.context.vfs.Vfs
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.time.Clock

internal fun interface BackupTickSource {
    suspend fun awaitTick(): Boolean
}

/** Single-flight coordinator; the published object is never rolled back after publication. */
internal class BackupRuntime(
    private val config: BackupConfig,
    executor: ContextDatabaseExecutor?,
    private val vfs: Vfs,
    private val clock: Clock,
    tickSource: BackupTickSource? = null,
    private val snapshotter: ContextBackupSnapshotter = executor ?: error("backup requires a SQLite snapshotter"),
    private val tempRoot: Path = config.effectiveTempDirectory,
    private val cleanup: suspend (Path, Path?) -> Unit = ::deleteBackupArtifacts,
    private val snapshotProbe: suspend () -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(BackupRuntime::class.java)

    private enum class State { NEW, RUNNING, CLOSING, CLOSED }

    private val lock = Any()
    private var state = State.NEW
    private var active: Run? = null
    private var activeCompletion: CompletableDeferred<Unit>? = null
    private var closeCompletion: CompletableDeferred<Throwable?>? = null
    private var status = ContextBackupStatus(WorkerState.STOPPED)
    private val cron =
        config.cron?.let {
            DreamingPeriodicCron(
                it,
                config.timezone,
                ::requestBackground,
                clock = clock.withZone(java.time.ZoneId.of(config.timezone)),
                tickSource = tickSource?.let { source -> DreamingPeriodicTickSource { source.awaitTick() } },
            )
        }

    fun start() {
        synchronized(lock) {
            when (state) {
                State.NEW -> {
                    state = State.RUNNING
                    status = status.copy(state = WorkerState.IDLE)
                }
                State.RUNNING -> return
                State.CLOSING, State.CLOSED -> throw closed()
            }
        }
        cron?.start()
    }

    suspend fun runNow(): ContextBackupResult {
        val run = begin() ?: throw ContextBackupException(ContextBackupErrorCode.ALREADY_RUNNING, "backup is already running")
        try {
            val result = withContext(Dispatchers.IO) { perform() }
            finish(run, result, null)
            return result
        } catch (failure: Throwable) {
            finish(run, null, failure)
            throw failure
        }
    }

    fun status(): ContextBackupStatus =
        synchronized(lock) {
            if (state == State.CLOSING || state == State.CLOSED) status.copy(state = WorkerState.STOPPED) else status
        }

    fun beginClosing() = synchronized(lock) { if (state == State.NEW || state == State.RUNNING) state = State.CLOSING }

    suspend fun closeAsync() {
        beginClosing()
        var owner = false
        val completion =
            synchronized(lock) {
                closeCompletion ?: CompletableDeferred<Throwable?>().also {
                    closeCompletion = it
                    owner = true
                }
            }
        if (!owner) {
            completion.await()?.let { throw it }
            return
        }
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                cron?.closeAsync()
            } catch (throwable: Throwable) {
                failure = throwable
            }
            try {
                // Admission is already closed. Drain the accepted run instead of cancelling it
                // halfway through snapshot or publication.
                synchronized(lock) { activeCompletion }?.await()
            } catch (throwable: Throwable) {
                failure?.addSuppressed(throwable) ?: run { failure = throwable }
            }
            synchronized(lock) { state = State.CLOSED }
            completion.complete(failure)
        }
        failure?.let { throw it }
    }

    private suspend fun begin(): Run? {
        val job = currentCoroutineContext()[Job] ?: error("backup requires a coroutine Job")
        return synchronized(lock) {
            if (state != State.RUNNING) throw closed()
            if (active != null) return@synchronized null
            status = status.copy(state = WorkerState.RUNNING, lastFailureStage = null, lastError = null)
            Run(job, CompletableDeferred()).also {
                active = it
                activeCompletion = it.completion
            }
        }
    }

    private fun requestBackground(): Boolean {
        val run =
            synchronized(lock) {
                if (state != State.RUNNING || active != null) return false
                status = status.copy(state = WorkerState.RUNNING, lastFailureStage = null, lastError = null)
                Run(SupervisorJob(), CompletableDeferred()).also {
                    active = it
                    activeCompletion = it.completion
                }
            }
        CoroutineScope(run.job + Dispatchers.IO).launch {
            try {
                finish(run, perform(), null)
            } catch (failure: Throwable) {
                finish(run, null, failure)
            }
        }
        return true
    }

    private fun finish(
        run: Run,
        result: ContextBackupResult?,
        failure: Throwable?,
    ) {
        synchronized(lock) {
            if (active !== run) return
            status =
                when {
                    result != null -> ContextBackupStatus(WorkerState.IDLE, result.uri, result.completedAt)
                    failure?.cancellationOrNull() != null ->
                        status.copy(
                            state = WorkerState.IDLE,
                            lastFailureStage = null,
                            lastError = null,
                        )
                    else ->
                        status.copy(
                            state = WorkerState.IDLE,
                            lastFailureStage = stageOf(failure),
                            lastError = failure?.javaClass?.simpleName ?: "unknown",
                        )
                }
            active = null
            activeCompletion = null
            run.completion.complete(Unit)
        }
        when {
            result != null ->
                logger.info(
                    "backup.completed uri={} bytes={}",
                    result.uri,
                    result.sizeBytes,
                )
            failure != null && failure.cancellationOrNull() == null ->
                logger.warn("backup.failed stage={} error={}", stageOf(failure), failure.javaClass.simpleName)
        }
    }

    private suspend fun perform(): ContextBackupResult {
        var workspace: Path? = null
        var returned: Path? = null
        try {
            Files.createDirectories(tempRoot)
            if (!Files.isDirectory(tempRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw ContextBackupException(
                    ContextBackupErrorCode.UNAVAILABLE,
                    "backup temporary root is not a directory",
                )
            }
            val realRoot =
                try {
                    tempRoot.toRealPath()
                } catch (_: Throwable) {
                    throw ContextBackupException(
                        ContextBackupErrorCode.UNAVAILABLE,
                        "backup temporary root cannot be resolved",
                    )
                }
            workspace = Files.createTempDirectory(realRoot, "backup-")
            val local = workspace.resolve("snapshot.sqlite")
            returned = workspace.resolve("published.sqlite")
            try {
                try {
                    snapshotter.backupSnapshot(local)
                } catch (failure: Throwable) {
                    failure.cancellationOrNull()?.let { throw it }
                    throw BackupStageFailure(ContextBackupFailureStage.SNAPSHOT, failure)
                }
                snapshotProbe()
                currentCoroutineContext().ensureActive()
                val localFingerprint =
                    try {
                        verifySqlite(local)
                    } catch (failure: Throwable) {
                        failure.cancellationOrNull()?.let { throw it }
                        throw BackupStageFailure(ContextBackupFailureStage.PRE_CHECK, failure)
                    }
                val publishedBytes =
                    try {
                        Files.size(local)
                    } catch (failure: Throwable) {
                        failure.cancellationOrNull()?.let { throw it }
                        throw BackupStageFailure(ContextBackupFailureStage.PRE_CHECK, failure)
                    }
                if (publishedBytes > config.maxBytes) {
                    throw BackupStageFailure(
                        ContextBackupFailureStage.PRE_CHECK,
                        ContextBackupException(
                            ContextBackupErrorCode.UNAVAILABLE,
                            "backup exceeds configured size limit",
                        ),
                    )
                }
                val uri = config.nextUri(clock)
                logger.info("context backup publishing uri={} bytes={}", uri, publishedBytes)
                try {
                    vfs.publishNew(uri, Files.newInputStream(local), PublishOptions(config.maxBytes, publishedBytes))
                } catch (failure: Throwable) {
                    failure.cancellationOrNull()?.let { throw it }
                    throw ContextBackupException(
                        ContextBackupErrorCode.PUBLISH_FAILED,
                        "backup publication failed: ${failure.javaClass.simpleName}",
                    )
                }
                currentCoroutineContext().ensureActive()
                try {
                    vfs.openStream(uri, VfsStreamOptions(config.maxBytes)).use { remote ->
                        Files.newOutputStream(returned, StandardOpenOption.CREATE_NEW).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = remote.stream.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                total += read
                                if (total > config.maxBytes) error("published backup exceeds size limit")
                                output.write(buffer, 0, read)
                            }
                            if (total != publishedBytes) error("published backup size mismatch")
                        }
                    }
                    if (Files.size(returned) != publishedBytes) error("published backup size mismatch")
                    val returnedFingerprint = verifySqlite(returned)
                    if (returnedFingerprint != localFingerprint) {
                        error("published SQLite page header or page count changed")
                    }
                } catch (failure: Throwable) {
                    failure.cancellationOrNull()?.let { throw it }
                    val code =
                        if (failure is ContextBackupException &&
                            failure.code == ContextBackupErrorCode.QUICK_CHECK_FAILED
                        ) {
                            ContextBackupErrorCode.QUICK_CHECK_FAILED
                        } else {
                            ContextBackupErrorCode.UNAVAILABLE
                        }
                    throw BackupStageFailure(
                        ContextBackupFailureStage.POST_CHECK,
                        ContextBackupException(
                            code,
                            "backup post-publish verification failed: ${failure.javaClass.simpleName}",
                        ),
                    )
                }
                return ContextBackupResult(
                    uri.toString(),
                    publishedBytes,
                    clock.instant(),
                    listOf(
                        QuickCheckResult(QuickCheckPhase.PRE_PUBLISH, true),
                        QuickCheckResult(QuickCheckPhase.POST_PUBLISH, true),
                    ),
                )
            } catch (failure: BackupStageFailure) {
                failure.cancellationOrNull()?.let { throw it }
                throw failure
            } catch (failure: ContextBackupException) {
                failure.cancellationOrNull()?.let { throw it }
                if (failure.code == ContextBackupErrorCode.PUBLISH_FAILED) throw failure
                throw BackupStageFailure(ContextBackupFailureStage.PRE_CHECK, failure)
            } catch (failure: Throwable) {
                failure.cancellationOrNull()?.let { throw it }
                throw BackupStageFailure(ContextBackupFailureStage.PRE_CHECK, failure)
            }
        } finally {
            workspace?.let { cleanup(it, returned) }
        }
    }

    private fun verifySqlite(file: Path): SqliteFingerprint {
        ContextDatabaseFactory.verifyBackupSchema(file)
        val header = Files.newInputStream(file).use { it.readNBytes(SQLITE_HEADER_BYTES) }
        if (header.size != SQLITE_HEADER_BYTES || !header.copyOfRange(0, SQLITE_MAGIC_BYTES).contentEquals(SQLITE_MAGIC)) {
            throw ContextBackupException(ContextBackupErrorCode.QUICK_CHECK_FAILED, "SQLite page header is invalid")
        }
        var pageCount = 0L
        DriverManager.getConnection("jdbc:sqlite:${file.toUri()}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA quick_check").use { rows ->
                    if (!rows.next() || rows.getString(1) != "ok" || rows.next()) {
                        throw ContextBackupException(ContextBackupErrorCode.QUICK_CHECK_FAILED, "SQLite quick_check failed")
                    }
                }
                statement.executeQuery("PRAGMA page_count").use { rows ->
                    if (!rows.next()) {
                        throw ContextBackupException(ContextBackupErrorCode.QUICK_CHECK_FAILED, "SQLite page count is unavailable")
                    }
                    pageCount = rows.getLong(1)
                }
            }
        }
        if (pageCount <= 0) {
            throw ContextBackupException(ContextBackupErrorCode.QUICK_CHECK_FAILED, "SQLite page count is invalid")
        }
        return SqliteFingerprint(header.toList(), pageCount)
    }

    private fun closed() = ContextBackupException(ContextBackupErrorCode.CLOSED, "context backup is closed")

    private fun stageOf(failure: Throwable?) = failureStage(failure)

    private class Run(
        val job: Job,
        val completion: CompletableDeferred<Unit>,
    )

    private data class SqliteFingerprint(
        val pageHeader: List<Byte>,
        val pageCount: Long,
    )

    private companion object {
        const val SQLITE_MAGIC_BYTES = 16
        const val SQLITE_HEADER_BYTES = 100
        val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}

internal class BackupStageFailure(
    val stage: ContextBackupFailureStage,
    cause: Throwable,
) : Exception("backup ${stage.name} failed: ${cause.javaClass.simpleName}", cause)

/** Fixed stage classification only; never exposes an exception message or backend details. */
fun failureStage(failure: Throwable?): ContextBackupFailureStage? =
    when (failure) {
        is CancellationException -> null
        is BackupStageFailure -> failure.stage
        is ContextBackupException ->
            when (failure.code) {
                ContextBackupErrorCode.PUBLISH_FAILED -> ContextBackupFailureStage.PUBLISH
                ContextBackupErrorCode.QUICK_CHECK_FAILED -> ContextBackupFailureStage.POST_CHECK
                else -> ContextBackupFailureStage.PRE_CHECK
            }
        null -> null
        else -> ContextBackupFailureStage.PRE_CHECK
    }

private fun Throwable.cancellationOrNull(): CancellationException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is CancellationException) return current
        current = current.cause
    }
    return null
}

internal suspend fun deleteBackupArtifacts(
    directory: Path,
    publishedCopy: Path?,
) {
    withContext(NonCancellable + Dispatchers.IO) {
        var primary: Throwable? = null
        val snapshots = listOf(directory.resolve("snapshot.sqlite"), publishedCopy).filterNotNull()
        val files =
            snapshots +
                snapshots.flatMap { snapshot ->
                    listOf("-wal", "-shm", "-journal").map { suffix -> snapshot.resolveSibling(snapshot.fileName.toString() + suffix) }
                }
        for (file in files) {
            try {
                Files.deleteIfExists(file)
            } catch (failure: Throwable) {
                primary?.addSuppressed(failure) ?: run { primary = failure }
            }
        }
        if (Files.exists(directory) && Files.list(directory).use { it.findAny().isPresent }) {
            val failure = java.io.IOException("backup workspace is not empty")
            primary?.addSuppressed(failure) ?: run { primary = failure }
        }
        try {
            Files.deleteIfExists(directory)
        } catch (failure: Throwable) {
            primary?.addSuppressed(failure) ?: run { primary = failure }
        }
        primary?.let { throw it }
    }
}
