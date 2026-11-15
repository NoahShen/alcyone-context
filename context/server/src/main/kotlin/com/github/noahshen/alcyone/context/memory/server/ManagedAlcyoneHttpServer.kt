package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Owns exactly one Context and CIO engine, including deterministic reverse-order cleanup. */
class ManagedAlcyoneHttpServer internal constructor(
    private val contextConfig: ContextConfig,
    private val serverConfig: ServerConfig,
    private val contextFactory: (ContextConfig) -> AlcyoneContext,
    private val engineFactory: (ServerConfig, AlcyoneContext, ManagedRequestRegistry) -> EmbeddedServer<*, *>,
) : AutoCloseable {
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Mutex()
    private var startJob: Deferred<Unit>? = null
    private var closeJob: Deferred<Unit>? = null
    private var context: AlcyoneContext? = null
    private var engine: EmbeddedServer<*, *>? = null
    private var admission: ManagedRequestRegistry? = null

    /** Concurrent/repeated calls await the same creation, Context start and listen outcome. */
    suspend fun start() {
        val job =
            stateLock.withLock {
                check(closeJob == null) { "managed server is closing" }
                startJob ?: owner.async { startOnce() }.also { startJob = it }
            }
        job.await()
    }

    /** Closes admission first, drains CIO and all registered requests, then closes Runtime. */
    suspend fun closeAsync() {
        val job =
            stateLock.withLock {
                closeJob ?: owner.async { closeOnce() }.also { job ->
                    closeJob = job
                    job.invokeOnCompletion { owner.cancel() }
                }
            }
        job.await()
    }

    override fun close() {
        runBlocking { closeAsync() }
    }

    private suspend fun startOnce() {
        val created = contextFactory(contextConfig)
        context = created
        val registry = ManagedRequestRegistry()
        admission = registry
        var listening: EmbeddedServer<*, *>? = null
        try {
            created.start()
            val createdEngine = engineFactory(serverConfig, created, registry)
            engine = createdEngine
            createdEngine.startSuspend(wait = false)
            listening = createdEngine
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                val primary = failure
                runCatching { (listening ?: engine)?.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 0) }
                    .exceptionOrNull()
                    ?.takeUnless { it is CancellationException }
                    ?.let(primary::addSuppressed)
                runCatching { created.closeAsync() }.exceptionOrNull()?.takeUnless { it is CancellationException }?.let(
                    primary::addSuppressed,
                )
            }
            throw failure
        }
    }

    private suspend fun closeOnce() {
        withContext(NonCancellable) {
            val start = startJob
            val startFailure = start?.let { runCatching { it.await() }.exceptionOrNull() }
            if (startFailure != null) throw startFailure
            // startOnce has completed successfully; cleanup owns each acquired resource exactly once.
            val registry = admission
            val runningEngine = engine
            val ownedContext = context
            var primary: Throwable? = null
            registry?.closeAdmission()
            try {
                registry?.awaitDrained()
            } catch (failure: Throwable) {
                primary = failure
            }
            if (primary == null && runningEngine != null) {
                try {
                    // Admission and logical request completion are already closed/drained. A short CIO
                    // grace flushes committed bytes and closes idle keep-alive connections; it is not
                    // used as the request-drain completion signal or a success timeout.
                    runningEngine.stopSuspend(gracePeriodMillis = 1_000, timeoutMillis = Long.MAX_VALUE)
                } catch (failure: Throwable) {
                    primary = failure
                }
            }
            if (primary == null && ownedContext != null) {
                try {
                    ownedContext.closeAsync()
                } catch (failure: Throwable) {
                    primary = failure
                }
            } else if (ownedContext != null && primary != null) {
                // CIO/drain failure must not release a live Runtime. Repeated close replays this same
                // failure instead of claiming a successful drain or releasing resources twice.
            }
            primary?.let { throw it }
        }
    }

    companion object {
        fun createManaged(
            contextConfig: ContextConfig,
            serverConfig: ServerConfig,
        ): ManagedAlcyoneHttpServer =
            ManagedAlcyoneHttpServer(
                contextConfig = contextConfig,
                serverConfig = serverConfig,
                contextFactory = { AlcyoneContextFactory.create(it, it.vfsConfig) },
                engineFactory = ::managedEngine,
            )

        fun fromYaml(yamlPath: Path): ManagedAlcyoneHttpServer {
            val contextConfig = ContextConfigLoader.load(yamlPath)
            val serverConfig = ServerConfigLoader.load(yamlPath)
            return createManaged(contextConfig, serverConfig)
        }

        internal fun createForTesting(
            contextConfig: ContextConfig,
            serverConfig: ServerConfig,
            contextFactory: (ContextConfig) -> AlcyoneContext,
            engineFactory: (ServerConfig, AlcyoneContext, ManagedRequestRegistry) -> EmbeddedServer<*, *> = ::managedEngine,
        ): ManagedAlcyoneHttpServer = ManagedAlcyoneHttpServer(contextConfig, serverConfig, contextFactory, engineFactory)
    }
}

private fun managedEngine(
    config: ServerConfig,
    context: AlcyoneContext,
    registry: ManagedRequestRegistry,
): EmbeddedServer<*, *> =
    embeddedServer(CIO, host = config.host, port = config.port) {
        this.intercept(ApplicationCallPipeline.Call) {
            if (!registry.tryEnter()) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE")
                return@intercept
            }
            try {
                proceed()
            } finally {
                registry.leave()
            }
        }
        with(AlcyoneHttpServer) { installContextRoutes(context, config) }
    }

/** Lock-protected monotone admission and a completion signal; no timing-based drain guesses. */
internal class ManagedRequestRegistry {
    private val lock = Any()
    private var accepting = true
    private var inFlight = 0
    private var drained = CompletableDeferred<Unit>()

    fun tryEnter(): Boolean =
        synchronized(lock) {
            if (!accepting) return@synchronized false
            inFlight++
            true
        }

    fun leave() {
        synchronized(lock) {
            check(inFlight > 0) { "unbalanced managed request registration" }
            inFlight--
            if (!accepting && inFlight == 0 && !drained.isCompleted) drained.complete(Unit)
        }
    }

    fun closeAdmission() {
        synchronized(lock) {
            accepting = false
            if (inFlight == 0 && !drained.isCompleted) drained.complete(Unit)
        }
    }

    suspend fun awaitDrained() {
        drained.await()
    }

    internal fun inFlightForTesting(): Int = synchronized(lock) { inFlight }
}
