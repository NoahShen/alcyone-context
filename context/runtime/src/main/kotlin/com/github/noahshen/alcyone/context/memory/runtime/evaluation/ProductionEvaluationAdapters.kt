package com.github.noahshen.alcyone.context.memory.runtime.evaluation

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionCommitter
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionPrompt
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGeneratorImpl
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapArtifact
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCommitter
import com.github.noahshen.alcyone.context.memory.runtime.extraction.DefaultExtractionRunner
import com.github.noahshen.alcyone.context.memory.runtime.extraction.DefaultPreExtractionContextBuilder
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.JsonExtractionResultValidator
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.RawExchangeCallId
import com.github.noahshen.alcyone.context.memory.runtime.map.MemoryMapGenerator
import com.github.noahshen.alcyone.context.memory.runtime.organization.DefaultOrganizationWorker
import com.github.noahshen.alcyone.context.memory.runtime.organization.LlmCandidateRetriever
import com.github.noahshen.alcyone.context.memory.runtime.organization.LlmGovernanceService
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationCommitOperation
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationItemFailureObserver
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationSourceGuard
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.MemorySearchService
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Opt-in adapters for CT31. They observe internal production components; default Runtime is unchanged. */
class ProductionRecordingClient(
    private val delegate: LlmClient,
    private val clock: Clock = Clock.systemUTC(),
) : LlmClient {
    private val lock = Any()
    private val records = mutableListOf<OfflineEvaluationObservedCall>()
    private val logical = mutableListOf<RecordedLlmCall>()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val callId = UUID.randomUUID().toString()
        // One sink per logical call, keyed by callId, so concurrent calls never share a buffer.
        val capture = EvaluationRawExchangeCapture(delegate, callId)
        val startedAt: Instant = clock.instant()
        val start = System.nanoTime()
        capture.start()
        try {
            val response = kotlinx.coroutines.withContext(RawExchangeCallId(callId)) { delegate.complete(request) }
            val attempts = capture.drain()
            val elapsed = (System.nanoTime() - start) / 1_000_000
            synchronized(lock) {
                records +=
                    OfflineEvaluationObservedCall(
                        request.task.name,
                        request.messages,
                        request.responseFormat.name,
                        request.maxOutputTokens,
                        request.schema,
                        response.content,
                        response.usage,
                        response.finishReason,
                        elapsed,
                    )
                logical +=
                    RecordedLlmCall(
                        callId,
                        request.task.name,
                        request.messages,
                        request.responseFormat.name,
                        request.maxOutputTokens,
                        request.schema,
                        EvaluationCallState.SUCCEEDED,
                        startedAt,
                        elapsed,
                        attempts,
                        response.content,
                        response.usage,
                        response.finishReason,
                        null,
                        null,
                    )
            }
            return response
        } catch (cancellation: CancellationException) {
            recordTerminal(callId, request, startedAt, start, EvaluationCallState.CANCELLED, cancellation, capture)
            throw cancellation
        } catch (failure: Exception) {
            recordTerminal(callId, request, startedAt, start, EvaluationCallState.FAILED, failure, capture)
            throw failure
        } finally {
            capture.stop()
        }
    }

    private fun recordTerminal(
        callId: String,
        request: LlmRequest,
        startedAt: Instant,
        start: Long,
        state: EvaluationCallState,
        failure: Throwable,
        capture: EvaluationRawExchangeCapture,
    ) {
        val attempts = capture.drain()
        synchronized(lock) {
            logical +=
                RecordedLlmCall(
                    callId,
                    request.task.name,
                    request.messages,
                    request.responseFormat.name,
                    request.maxOutputTokens,
                    request.schema,
                    state,
                    startedAt,
                    (System.nanoTime() - start) / 1_000_000,
                    attempts,
                    attempts.lastOrNull { it.rawResponse != null }?.rawResponse,
                    null,
                    null,
                    failure::class.qualifiedName ?: failure::class.simpleName,
                    (failure as? LlmException)?.errorCode?.name,
                )
        }
    }

    fun calls(): List<OfflineEvaluationObservedCall> = synchronized(lock) { records.toList() }

    fun logicalCalls(): List<RecordedLlmCall> = synchronized(lock) { logical.toList() }
}

data class OrganizationItemFailure(
    val rawId: String,
    val errorType: String,
    val errorCode: String?,
)

class ProductionExtractionExecutor(
    database: ContextDatabase,
    client: LlmClient,
    config: LlmConfig,
    clock: Clock,
) {
    private val builder = DefaultPreExtractionContextBuilder(database, config, ExtractionConfig())
    private val runner =
        DefaultExtractionRunner(
            builder,
            builder::requestFor,
            client,
            JsonExtractionResultValidator(),
            ExtractionCommitter(database, MemoryIdGeneratorImpl(clock), clock),
            database,
            10,
        )

    suspend fun run(session: com.github.noahshen.alcyone.context.memory.api.SessionKey) = runner.execute(session)

    fun request(context: ExtractionContext): LlmRequest = builder.requestFor(context)

    fun promptVersion(): String = ExtractionPrompt.VERSION
}

class ProductionOrganizationExecutor(
    database: ContextDatabase,
    client: LlmClient,
    config: LlmConfig,
    clock: Clock,
) {
    private val candidates = LlmCandidateRetriever(database, client, config)
    private val lock = Any()
    private val failures = mutableListOf<OrganizationItemFailure>()
    private val worker =
        DefaultOrganizationWorker(
            database,
            OrganizationSourceGuard(database),
            candidates,
            LlmGovernanceService(client, config)::decide,
            OrganizationCommitOperation {
                raw,
                selected,
                decision,
                ->
                OrganizationCommitter(database, MemoryIdGeneratorImpl(clock), clock).commit(raw, selected, decision)
            },
            itemFailureObserver =
                OrganizationItemFailureObserver { rawId, errorType, errorCode ->
                    synchronized(lock) { failures += OrganizationItemFailure(rawId, errorType, errorCode) }
                },
        )

    suspend fun scan(raw: RawMemory) = candidates.search(raw)

    suspend fun run() {
        synchronized(lock) { failures.clear() }
        worker.runRound()
    }

    /** Failures swallowed by this round; empty in the default Runtime path. */
    fun itemFailures(): List<OrganizationItemFailure> = synchronized(lock) { failures.toList() }
}

data class ProductionSearchObservation(
    val scannerIds: List<String>,
    val ranked: List<OrganizedMemory>,
)

class ProductionSearchExecutor(
    database: ContextDatabase,
    client: LlmClient,
    config: LlmConfig,
) {
    private val service = MemorySearchService(database, client, config)
    var scannerIds: List<String> = emptyList()
        private set

    init {
        service.selectedIdsObserver = { scannerIds = it }
    }

    suspend fun search(
        query: String,
        limit: Int,
    ): ProductionSearchObservation {
        val ranked = service.search(query, limit = limit)
        return ProductionSearchObservation(scannerIds.toList(), ranked)
    }
}

class ProductionMapExecutor(
    database: ContextDatabase,
    client: LlmClient,
    config: LlmConfig,
    clock: Clock,
) {
    private val generator = MemoryMapGenerator(database, client, config, clock)

    suspend fun generate(): MemoryMapArtifact = generator.generate()
}
