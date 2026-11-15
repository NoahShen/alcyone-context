package com.github.noahshen.alcyone.context.integration.evaluation

import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionPrompt
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapPrompt
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceAction
import com.github.noahshen.alcyone.context.memory.core.organization.GovernancePrompt
import com.github.noahshen.alcyone.context.memory.core.organization.OrganizationCandidatePrompt
import com.github.noahshen.alcyone.context.memory.core.retrieval.MemorySearchPrompt
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.OfflineEvaluationObservedCall
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.ProductionExtractionExecutor
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.ProductionMapExecutor
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.ProductionOrganizationExecutor
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.ProductionRecordingClient
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.ProductionSearchExecutor
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/** Runs production components against an isolated SQLite database; no caller controls execution identity. */
internal class ProductionEvalRunner(
    private val client: com.github.noahshen.alcyone.context.memory.core.llm.LlmClient,
    private val llmConfig: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig,
    private val model: String,
) {
    constructor(config: RealModelConfig) : this(config.llmClient, config.llmConfig, config.model)

    suspend fun run(
        sample: EvalSample,
        directory: Path,
    ): SampleResult {
        val recorder = ProductionRecordingClient(client)
        var resource: DatabaseResource? = null
        return try {
            resource = openDatabase(sample, directory)
            val database = resource.database
            val (checks, output) =
                when (sample.task) {
                    "EXTRACTION" -> extraction(sample, database, recorder)
                    "GOVERNANCE" -> organization(sample, database, recorder)
                    "CANDIDATE_SCANNER" -> scanner(sample, database, recorder)
                    "MEMORY_SEARCH" -> search(sample, database, recorder)
                    "MEMORY_MAP" -> map(sample, database, recorder)
                    else -> error("Unsupported production task: ${sample.task}")
                }
            val logical = recorder.logicalCalls()
            val write = writeLogicalEvidenceSafely(directory, logical)
            val evidence = write.refs
            val evidenceChecks =
                write.failure?.let {
                    checks +
                        Check(
                            "raw_evidence_write_failed",
                            Verdict.REVIEW_REQUIRED,
                            "error=$it logicalCalls=${logical.size} (production result preserved)",
                        )
                } ?: checks
            val calls =
                logical.map { call ->
                    call.toCallRecord(
                        model,
                        output.takeIf { call === logical.lastOrNull() && call.state.name == "SUCCEEDED" },
                        evidence[call.callId],
                    )
                }
            SampleResult(sample, calls, evidenceChecks)
        } catch (failure: CancellationException) {
            // Cancellation still carries the already captured calls: the raw artifacts exist, so the result
            // must never claim zero calls, and the CLI writes this snapshot into the cancelled artifacts.
            val logical = recorder.logicalCalls()
            val evidence = writeLogicalEvidenceSafely(directory, logical).refs
            throw EvaluationStageCancelled(
                logical.map { it.toCallRecord(model, null, evidence[it.callId]) },
                failure,
            )
        } catch (failure: Exception) {
            val logical = recorder.logicalCalls()
            val evidence = writeLogicalEvidenceSafely(directory, logical).refs
            SampleResult(
                sample,
                logical.map { it.toCallRecord(model, null, evidence[it.callId]) },
                error = safeError(failure),
            )
        } finally {
            resource?.executor?.close()
        }
    }

    private suspend fun extraction(
        sample: EvalSample,
        db: ContextDatabase,
        recorder: ProductionRecordingClient,
    ): Pair<List<Check>, JsonObject> {
        ProductionExtractionExecutor(db, recorder, llmConfig, clock()).run(evalSession)
        val upper = db.read { it.conversations.maxTurnId() ?: 0 }
        val statuses = db.read { it.conversations.idRange(0, upper, 256) }.associate { it.sessionTurnId to it.extractionStatus }
        val target = sample.oracle.longs("sourceTurnIds")
        val raw =
            readRaw(db).filter { memory ->
                memory.sources.filterIsInstance<MemorySourceRef.ConversationTurnRef>().any {
                    it.sessionId ==
                        evalSession.sessionId
                }
            }
        val content = raw.joinToString("\n") { it.content }
        val checks =
            listOf(
                check("production_runner", true, "DefaultExtractionRunner/ExtractionCommitter completed"),
                check("target_status", target.all { statuses[it.toString()] == ExtractionStatus.COMPLETE }, "statuses=$statuses"),
                check(
                    "sources_grounded",
                    raw.flatMap { it.sources }.filterIsInstance<MemorySourceRef.ConversationTurnRef>().all {
                        it.sessionTurnId.toLongOrNull() in
                            target
                    },
                    "raw=${raw.map { it.id }}",
                ),
                check(
                    "source_coverage",
                    raw
                        .flatMap {
                            it.sources
                        }.filterIsInstance<MemorySourceRef.ConversationTurnRef>()
                        .mapNotNull {
                            it.sessionTurnId.toLongOrNull()
                        }.containsAll(target),
                    "target=$target",
                ),
                check("expected_empty", sample.oracle.bool("expectedEmpty") == raw.isEmpty(), "raw=${raw.size}"),
                noTruncation(recorder),
                textCheck("required_facts", sample.oracle.strings("requiredFacts"), content),
            )
        return checks to rawOutput(raw)
    }

    private suspend fun scanner(
        sample: EvalSample,
        db: ContextDatabase,
        recorder: ProductionRecordingClient,
    ): Pair<List<Check>, JsonObject> {
        val ids = ProductionOrganizationExecutor(db, recorder, llmConfig, clock()).scan(sample.input.toRawMemory()).map { it.id }
        return listOf(
            check(
                "relevant_recall",
                ids.containsAll(sample.oracle.strings("relevantIds")),
                "expected=${sample.oracle.strings("relevantIds")} actual=$ids",
            ),
            check("forbidden_ids", ids.none { it in sample.oracle.strings("forbiddenIds") }, "actual=$ids"),
            check("critical_recall", ids.containsAll(sample.oracle.strings("criticalIds")), "actual=$ids"),
            check("expected_empty", sample.oracle.bool("expectedEmpty") == ids.isEmpty(), "actual=$ids"),
            noTruncation(recorder),
        ) to buildJsonObject { put("candidateIds", ids.toString()) }
    }

    private suspend fun organization(
        sample: EvalSample,
        db: ContextDatabase,
        recorder: ProductionRecordingClient,
    ): Pair<List<Check>, JsonObject> {
        val raw = sample.input.toRawMemory()
        val candidates = sample.input.toOrganizedMemories("candidates")
        val before = db.read { read -> candidates.map { read.organizedMemories.findById(it.id)!!.content } }
        val executor = ProductionOrganizationExecutor(db, recorder, llmConfig, clock())
        executor.run()
        val failures = executor.itemFailures()
        val targetFailure = failures.firstOrNull { it.rawId == raw.id }
        val associated = db.read { it.organizedMemories.findOrganizedByRaw(raw.id) }
        val pending = db.read { it.pending.exists(raw.id) }
        val actual = associated.singleOrNull()?.let { db.read { read -> read.organizedMemories.findById(it) } }
        val changed =
            candidates.mapIndexed { index, candidate ->
                candidate.id to
                    (db.read { it.organizedMemories.findById(candidate.id)!!.content } != before[index])
            }
        val expected = GovernanceAction.valueOf(sample.oracle.string("expectedAction"))
        val target = actual?.id
        val checks = mutableListOf<Check>()
        if (targetFailure != null) {
            checks +=
                Check(
                    "organization_item_failed",
                    Verdict.REVIEW_REQUIRED,
                    "raw=${raw.id} safeError=${targetFailure.errorType}:${targetFailure.errorCode ?: "none"} associated=$associated pending=$pending",
                )
        } else {
            checks +=
                listOf(
                    check("production_worker_commit", associated.size == 1 && !pending, "associated=$associated pending=$pending"),
                    check("source_link", actual?.rawMemoryIds?.contains(raw.id) == true, "actual=${actual?.id}"),
                    check(
                        "action_target",
                        if (expected ==
                            GovernanceAction.NEW
                        ) {
                            target !in candidates.map { it.id }
                        } else {
                            target in sample.oracle.strings("allowedTargets")
                        },
                        "expected=$expected actual=$target",
                    ),
                    check("forbidden_target", target == null || target !in sample.oracle.strings("forbiddenTargets"), "target=$target"),
                    check("forbidden_regression", changed.none { it.second && it.first != target }, "changed=$changed"),
                )
        }
        checks += noTruncation(recorder)
        actual?.content?.let { checks += textCheck("required_content", sample.oracle.strings("requiredContentPoints"), it) }
        return checks to
            buildJsonObject {
                put(
                    "observedAction",
                    if (actual == null) {
                        "NONE"
                    } else if (actual.id in candidates.map { it.id }) {
                        "MERGE_OR_UPDATE"
                    } else {
                        "NEW"
                    },
                )
                put("targetMemoryId", target ?: "")
                put("content", actual?.content ?: "")
                put("domains", actual?.domains.toString())
                put("targetItemFailure", targetFailure?.let { "${it.errorType}:${it.errorCode ?: "none"}" } ?: "")
                put(
                    "otherItemFailures",
                    failures.filter { it.rawId != raw.id }.map { "${it.rawId}:${it.errorType}" }.toString(),
                )
            }
    }

    private suspend fun search(
        sample: EvalSample,
        db: ContextDatabase,
        recorder: ProductionRecordingClient,
    ): Pair<List<Check>, JsonObject> {
        val k = sample.oracle.int("k")
        val executor = ProductionSearchExecutor(db, recorder, llmConfig)
        val searchObservation = executor.search(sample.input.toQuery(), k)
        val ranked = searchObservation.ranked.map { it.id }
        val expected = sample.oracle.obj("rerank").strings("expectedOrder")
        val scannerExpected = sample.oracle.obj("scanner").strings("expectedIds")
        return listOf(
            check("production_rerank", ranked == expected, "expected=$expected actual=$ranked"),
            check("critical_recall", ranked.containsAll(sample.oracle.strings("criticalIds")), "ranked=$ranked"),
            check("rerank_forbidden", ranked.none { it in sample.oracle.obj("rerank").strings("forbiddenIds") }, "ranked=$ranked"),
            check("expected_empty", sample.oracle.bool("expectedEmpty") == ranked.isEmpty(), "ranked=$ranked"),
            check(
                "scanner_recall",
                executor.scannerIds.containsAll(scannerExpected),
                "expected=$scannerExpected actual=${executor.scannerIds}",
            ),
            check(
                "scanner_forbidden",
                executor.scannerIds.none {
                    it in sample.oracle.obj("scanner").strings("forbiddenIds")
                },
                "scanner=${executor.scannerIds}",
            ),
            noTruncation(recorder),
        ) to
            buildJsonObject {
                put("scannerIds", executor.scannerIds.toString())
                put("rankedIds", ranked.toString())
            }
    }

    private suspend fun map(
        sample: EvalSample,
        db: ContextDatabase,
        recorder: ProductionRecordingClient,
    ): Pair<List<Check>, JsonObject> {
        val artifact = ProductionMapExecutor(db, recorder, llmConfig, clock()).generate()
        return (
            mapContractChecks(
                artifact.document.domains,
                artifact.document.overview,
                artifact.document.navigation,
                sample.oracle.strings("requiredDomains"),
            ) + noTruncation(recorder)
        ) to
            buildJsonObject {
                put("overview", artifact.document.overview)
                put("navigation", artifact.document.navigation)
                put("domains", artifact.document.domains.toString())
            }
    }

    private fun openDatabase(
        sample: EvalSample,
        directory: Path,
    ): DatabaseResource {
        val path = directory.resolve("production.db")
        runBlocking {
            AlcyoneContextFactory.initializeDatabase(
                ContextConfig(
                    path,
                    lifecycleShutdownTimeout = kotlin.time.Duration.parse("5s"),
                    vfsConfig = VfsRuntimeConfig(directory.resolve("unused-vfs.db"), emptySet(), emptyList()),
                ),
            )
        }
        val executor = ContextDatabaseFactory.open(path)
        val db = ContextDatabaseImpl(executor)
        runBlocking {
            db.write { tx ->
                if (sample.task ==
                    "EXTRACTION"
                ) {
                    sample.input.toTurns().forEach {
                        tx.conversations.insertTurn(
                            evalSession,
                            it.sessionTurnId,
                            it.timestamp,
                            it.createdAt,
                            it.messages,
                        )
                    }
                }
                val candidates =
                    when (sample.task) {
                        "CANDIDATE_SCANNER", "GOVERNANCE", "MEMORY_SEARCH" -> sample.input.toOrganizedMemories("candidates")
                        "MEMORY_MAP" -> sample.input.toOrganizedMemories()
                        else -> emptyList()
                    }
                candidates.forEach { check(tx.organizedMemories.insertNew(it) == 1) }
                if (sample.task == "CANDIDATE_SCANNER" || sample.task == "GOVERNANCE") {
                    val raw = sample.input.toRawMemory()
                    check(tx.rawMemories.insertBatch(listOf(raw)) == 1)
                    check(tx.pending.enqueue(raw.id) == 1)
                }
            }
        }
        return DatabaseResource(db, executor)
    }

    private fun clock(): Clock = Clock.offset(Clock.systemUTC(), Duration.ofDays(1))
}

private class DatabaseResource(
    val database: ContextDatabase,
    val executor: ContextDatabaseExecutor,
)

internal fun mapContractChecks(
    domains: List<String>,
    overview: String,
    navigation: String,
    requiredDomains: List<String>,
): List<Check> =
    listOf(
        check("domain_coverage", domains.containsAll(requiredDomains), "domains=$domains required=$requiredDomains"),
        check(
            "navigation_overview_nonempty",
            overview.isNotBlank() && navigation.isNotBlank(),
            "overviewLength=${overview.length} navigationLength=${navigation.length}",
        ),
    )

private fun noTruncation(recorder: ProductionRecordingClient) =
    check(
        "no_truncation",
        recorder.calls().none {
            it.finishReason == "length"
        },
        "calls=${recorder.calls().size}",
    )

private fun readRaw(db: ContextDatabase): List<RawMemory> =
    runBlocking {
        db.read { read ->
            val all = mutableListOf<RawMemory>()
            var offset = 0
            while (true) {
                val page = read.rawMemories.scanPage(offset, 256)
                if (page.isEmpty()) break
                all += page
                offset += page.size
            }
            all
        }
    }

private fun rawOutput(raw: List<RawMemory>): JsonObject =
    buildJsonObject {
        putJsonArray("persistedRawMemories") {
            raw.forEach {
                add(
                    buildJsonObject {
                        put("id", it.id)
                        put("content", it.content)
                        put(
                            "sources",
                            it.sources
                                .filterIsInstance<MemorySourceRef.ConversationTurnRef>()
                                .map { source -> source.sessionTurnId }
                                .toString(),
                        )
                    },
                )
            }
        }
    }

internal fun OfflineEvaluationObservedCall.toRecord(
    model: String,
    productionOutput: JsonObject? = null,
): CallRecord {
    val promptVersion =
        when (task) {
            LlmTask.EXTRACTION.name -> ExtractionPrompt.VERSION
            LlmTask.MEMORY_SEARCH.name -> MemorySearchPrompt.VERSION
            LlmTask.CANDIDATE_SCANNER.name -> OrganizationCandidatePrompt.VERSION
            LlmTask.GOVERNANCE.name -> GovernancePrompt.VERSION
            LlmTask.MEMORY_MAP.name -> MemoryMapPrompt.VERSION
            else -> "v1"
        }
    val parsed =
        runCatching {
            kotlinx.serialization.json.Json
                .parseToJsonElement(content)
                .obj()
        }.getOrElse { buildJsonObject { put("rawContent", content) } }
    val output =
        productionOutput?.let { result ->
            buildJsonObject {
                parsed.forEach { (k, v) -> put(k, v) }
                put("persistedProductionResult", result)
            }
        }
            ?: parsed
    return CallRecord(
        task,
        "production-$task",
        model,
        promptVersion,
        "v1",
        sha256(messages.joinToString("\n") { "${it.role}\n${it.content}" }),
        sha256(messages.joinToString("\n") { "${it.role}\n${it.content}" } + responseFormat + (schema ?: "")),
        messages.sumOf {
            (
                it.content.length +
                    3
            ) /
                4
        },
        usage,
        elapsedMs,
        1,
        finishReason,
        finishReason == "length",
        output,
    )
}

private fun check(
    id: String,
    passed: Boolean,
    evidence: String,
) = Check(id, if (passed) Verdict.PASS else Verdict.FAIL, evidence)

private fun textCheck(
    id: String,
    expected: List<String>,
    content: String,
): Check =
    if (expected.all {
            normalizeText(it) in
                normalizeText(content)
        }
    ) {
        Check(id, Verdict.PASS, "Oracle phrases matched persisted production content")
    } else {
        Check(id, Verdict.REVIEW_REQUIRED, "Paraphrase, negation and fact ownership require independent review")
    }

private fun normalizeText(value: String) = value.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
