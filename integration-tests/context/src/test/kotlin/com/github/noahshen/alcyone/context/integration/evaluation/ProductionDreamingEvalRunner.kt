package com.github.noahshen.alcyone.context.integration.evaluation

import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.OfflineDreamingEvaluationResult
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.ProductionDreamingEvaluationRunner
import java.nio.file.Path

/** Offline production-component assembly: real stages, committer and SQLite; not a scheduler end-to-end run. */
internal class ProductionDreamingEvalRunner(
    private val client: com.github.noahshen.alcyone.context.memory.core.llm.LlmClient? = null,
    private val llmConfig: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig? = null,
    private val model: String? = null,
) {
    suspend fun run(
        sample: EvalSample,
        config: RealModelConfig,
        directory: Path,
    ): DreamingObservation = run(sample, config.llmClient, config.llmConfig, config.model, directory)

    suspend fun run(
        sample: EvalSample,
        client: com.github.noahshen.alcyone.context.memory.core.llm.LlmClient = requireNotNull(this.client),
        llmConfig: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig = requireNotNull(this.llmConfig),
        model: String = requireNotNull(this.model),
        directory: Path,
    ): DreamingObservation {
        val dbPath = directory.resolve("context.db").toAbsolutePath()
        ContextDatabaseInitializer.initialize(dbPath)
        val executor = ContextDatabaseFactory.open(dbPath)
        try {
            val database = ContextDatabaseImpl(executor)
            val turnIds = sample.input.longs("turnIds")
            val turns = if (turnIds.isEmpty()) emptyList() else sample.input.toDreamingWindow()
            val allTurns = sample.input.toTurns()
            val raw = sample.input.toRawMemories()
            val organized = sample.input.toOrganizedMemories()
            database.write { tx ->
                allTurns.forEach { tx.conversations.insertTurn(evalSession, it.sessionTurnId, it.timestamp, it.createdAt, it.messages) }
                raw.forEach { check(tx.rawMemories.insertBatch(listOf(it)) == 1) }
                raw.forEach { check(tx.pending.enqueue(it.id) == 1) }
                organized.forEach { check(tx.organizedMemories.insertNew(it) == 1) }
                organized.forEach { memory ->
                    memory.rawMemoryIds.forEach { rawId -> check(tx.organizedMemories.insertSourceLink(memory.id, rawId) == 1) }
                }
            }
            if (turns.isEmpty()) return readEmptyDreaming(database, raw.size)
            val recorder =
                com.github.noahshen.alcyone.context.memory.runtime.evaluation
                    .ProductionRecordingClient(client)

            // One terminal snapshot function for success, ordinary failure and cancellation: the raw artifacts
            // and the result must always describe the same logical calls.
            fun terminalCalls(): List<CallRecord> {
                val logical = recorder.logicalCalls()
                val refs = writeLogicalEvidenceSafely(directory, logical).refs
                return logical.map { it.toCallRecord(model, null, refs[it.callId]) }
            }

            val result =
                try {
                    ProductionDreamingEvaluationRunner(recorder, llmConfig).run(database, turns)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw EvaluationStageCancelled(terminalCalls(), cancellation)
                } catch (failure: Exception) {
                    throw EvaluationStageFailed(terminalCalls(), failure)
                }
            val logical = recorder.logicalCalls()
            val refs = writeLogicalEvidenceSafely(directory, logical).refs
            val expectedRaw = sample.input.toRawMemories()
            val evidenceLinks =
                sample.input
                    .toOrganizedMemories()
                    .flatMap { memory ->
                        memory.rawMemoryIds.map { rawId -> rawId to memory.id }
                    }.toSet()
            return readDreaming(
                database,
                result,
                turns.size,
                model,
                expectedRaw.size,
                evidenceLinks,
                turns.last().id,
                logical.map { refs[it.callId] },
            )
        } finally {
            executor.close()
        }
    }

    private suspend fun readDreaming(
        database: ContextDatabase,
        result: OfflineDreamingEvaluationResult,
        count: Int,
        model: String,
        inputPending: Int,
        inputEvidenceLinks: Set<Pair<String, String>>,
        windowUpper: Long,
        evidenceRefs: List<RawEvidenceRef?>,
    ): DreamingObservation =
        database.read { read ->
            val allRaw = mutableListOf<RawMemory>()
            var rawOffset = 0
            while (true) {
                val page = read.rawMemories.scanPage(rawOffset, 256)
                if (page.isEmpty()) break
                allRaw += page
                rawOffset += page.size
            }
            val raw = allRaw.filter { it.generatedBy == MemoryOrigin.DREAMING }
            val links =
                raw
                    .flatMap { memory -> read.organizedMemories.findOrganizedByRaw(memory.id).map { memory.id to it } }
                    .distinct()
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, ids) -> ids.toSet() }
            val inputLinks =
                inputEvidenceLinks
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, ids) -> ids.toSet() }
            val persistedMax = read.conversations.maxTurnId() ?: 0
            val checkpoint = read.dreaming.readCheckpoint()
            DreamingObservation(
                raw,
                result.commit.lastTurnId == windowUpper,
                checkpoint,
                read.pending.count().toInt(),
                inputPending,
                count,
                result.topics.map { TopicObservation(it.title, it.sourceTurnIds) },
                result.insights.map { InsightObservation(it.content, it.sources.map { source -> source.sessionTurnId.toLong() }) },
                links,
                inputLinks,
                result.calls.mapIndexed { index, call -> call.toRecord(model).copy(rawEvidence = evidenceRefs.getOrNull(index)) },
                persistedMax,
            )
        }

    private suspend fun readEmptyDreaming(
        database: ContextDatabase,
        inputPending: Int,
    ): DreamingObservation =
        database.read { read ->
            val evidenceLinks = sampleEvidenceLinks(read)
            DreamingObservation(
                rawMemories = emptyList(),
                commitCompleted = true,
                persistedCheckpoint = read.dreaming.readCheckpoint(),
                pendingCount = read.pending.count().toInt(),
                inputPendingCount = inputPending,
                conversationTurnCount = 0,
                topics = emptyList(),
                insights = emptyList(),
                evidenceLinks = evidenceLinks,
                inputEvidenceLinks = evidenceLinks,
                calls = emptyList(),
                maxPersistedTurnId = read.conversations.maxTurnId() ?: 0,
            )
        }
}

private fun sampleEvidenceLinks(read: com.github.noahshen.alcyone.context.memory.core.repository.ReadContext): Map<String, Set<String>> =
    read.organizedMemories
        .scanPage(0, 256)
        .flatMap { memory ->
            read.organizedMemories.findSourcesByOrganized(memory.id).map { rawId -> rawId to memory.id }
        }.groupBy({ it.first }, { it.second })
        .mapValues { (_, ids) -> ids.toSet() }
