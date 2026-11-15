@file:Suppress("ktlint:standard:max-line-length")

package com.github.noahshen.alcyone.context.integration.evaluation

import com.github.noahshen.alcyone.context.memory.runtime.evaluation.EvaluationCallState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

fun main() {
    val root = Path.of(System.getProperty("alcyone.eval.root") ?: error("Missing evaluation root"))
    val live = System.getProperty("alcyone.eval.live").toBoolean()
    val production = System.getProperty("alcyone.eval.production").toBoolean()
    val externalApproved = System.getProperty("alcyone.eval.externalApproved").toBoolean()
    val output = System.getProperty("alcyone.eval.output") ?: error("Missing -PcontextEvalOutput")
    val task = System.getProperty("alcyone.eval.task")
    val sampleId = System.getProperty("alcyone.eval.sample")
    val limit = System.getProperty("alcyone.eval.limit")?.toIntOrNull()?.also { require(it > 0) }
    val selected = loadEvalSamples(root).filter { (task == null || it.task == task) && (sampleId == null || it.id == sampleId) }
    val samples = if (limit == null) selected else selected.take(limit)
    require(samples.isNotEmpty()) { "No CT31 samples selected" }
    val directory = root.resolve(output).normalize()
    require(directory.startsWith(root) && directory != root && !directory.startsWith(root.resolve("evals"))) {
        "Evaluation output must be separate from canonical evals"
    }
    if (!live) {
        println("DRY RUN: ${samples.size} samples. No network or model results used. Production=$production Output=$directory")
        samples.forEach { println("${it.id} ${it.task} inputSha256=${sha256(it.input.toString())}") }
        return
    }
    requireEmptyOutputDirectory(directory)
    Files.createDirectories(directory)
    require(externalApproved) { "Refusing external LLM calls without -PcontextEvalExternalApproved=true" }
    val configPath = root.resolve(System.getProperty("alcyone.eval.config") ?: ".local/ct31/model.yaml").normalize()
    require(configPath.startsWith(root)) { "Evaluation configuration must stay inside the repository" }
    val config = RealModelConfig.fromEnvironment(configPath)
    require(
        production ||
            !samples.any {
                it.task == "DREAMING"
            },
    ) { "DREAMING samples require production mode; protocol-only execution cannot assert persistence" }
    val completed = mutableListOf<SampleResult>()
    val judgeRecords = mutableListOf<JudgeCallRecord>()
    val offlineSamples = mutableSetOf<String>()
    val runStartedAt = Instant.now()
    var terminalSnapshot: EvaluationTerminalSnapshot? = null
    try {
        config.use {
            runBlocking {
                val runner = SemanticEvaluationRunner(EvalExecutor(config))
                samples.forEach { sample ->
                    println("EVAL_START ${sample.id} ${sample.task}")
                    val sampleDirectory = directory.resolve(sample.id)
                    Files.createDirectory(sampleDirectory)
                    val judge = SemanticJudge(config.llmClient, sample)
                    var reviewed = SampleResult(sample)
                    val identity =
                        EvaluationIdentity(
                            config.model,
                            config.profileName,
                            config.responseFormat.name,
                            config.temperature,
                        )
                    try {
                        val base =
                            if (production && sample.task == "DREAMING") {
                                evaluateProductionDreaming(sample, config, sampleDirectory)
                            } else if (production) {
                                ProductionEvalRunner(config).run(sample, sampleDirectory)
                            } else {
                                offlineSamples += sample.id
                                runner.run(sample)
                            }
                        // Production has completed, so this result must survive any later Judge cancellation.
                        reviewed = base
                        reviewed =
                            reviewCompletedSample(sample, base, judge) {
                                sampleDirectory.resolve("dreaming.json").takeIf { Files.exists(it) }?.let {
                                    kotlinx.serialization.json.Json
                                        .parseToJsonElement(Files.readString(it))
                                        .jsonObject
                                }
                            }
                    } catch (failure: CancellationException) {
                        // A cancelled sample keeps the calls captured before cancellation. `reviewed` is only the
                        // last completed result, so prefer the calls carried by the cancellation itself.
                        val cancelled = cancelledSampleResult(sample, failure, reviewed)
                        // The outer finally still runs after this branch. Pin the complete terminal snapshot so
                        // that its later writes cannot fall back to the stale pre-cancellation lists.
                        terminalSnapshot =
                            EvaluationTerminalSnapshot(
                                results = completed + cancelled,
                                judgeCalls = judgeRecords + judge.calls,
                            )
                        withContext(kotlinx.coroutines.NonCancellable) {
                            writeCancelledArtifacts(
                                sampleDirectory,
                                directory,
                                root,
                                identity,
                                cancelled,
                                judge.calls,
                                completed,
                                judgeRecords,
                                samples.size,
                                production,
                                offlineSamples,
                                task,
                                sampleId,
                                limit,
                                runStartedAt,
                                selectedSampleIds = samples.map { it.id },
                            )
                        }
                        throw failure
                    } catch (failure: EvaluationStageFailed) {
                        reviewed = SampleResult(sample, failure.calls, reviewed.checks, safeError(failure.cause ?: failure))
                    } catch (failure: Exception) {
                        reviewed = SampleResult(sample, reviewed.calls, reviewed.checks, reviewed.error ?: safeError(failure))
                    }
                    completed += reviewed
                    judgeRecords += judge.calls
                    writeSampleArtifacts(sampleDirectory, identity, reviewed, judge.calls)
                    println("EVAL_DONE ${sample.id} ${reviewed.status} calls=${reviewed.calls.size} judgeCalls=${judge.calls.size}")
                    writeReport(
                        directory,
                        root,
                        identity,
                        completed,
                        judgeRecords,
                        samples.size,
                        production,
                        offlineSamples,
                        task,
                        sampleId,
                        limit,
                        finished = false,
                        selectedSampleIds = samples.map { it.id },
                    )
                    writeRunManifest(
                        directory,
                        root,
                        identity,
                        completed,
                        judgeRecords,
                        selected = samples.size,
                        task = task,
                        sampleId = sampleId,
                        limit = limit,
                        startedAt = runStartedAt,
                        finished = false,
                        selectedSampleIds = samples.map { it.id },
                    )
                }
            }
        }
    } finally {
        val identity =
            EvaluationIdentity(
                config.model,
                config.profileName,
                config.responseFormat.name,
                config.temperature,
            )
        writeTerminalArtifacts(
            directory,
            root,
            identity,
            completed = completed,
            judgeRecords = judgeRecords,
            terminalSnapshot = terminalSnapshot,
            selected = samples.size,
            production = production,
            offlineSamples = offlineSamples,
            task = task,
            sampleId = sampleId,
            limit = limit,
            startedAt = runStartedAt,
            finished = evaluationRunFinished(terminalSnapshot, completed.size, samples.size),
            selectedSampleIds = samples.map { it.id },
        )
    }
    println(
        "Evaluation written to $directory. Semantic quality includes model judgments and remaining human review; this is not a real-user evaluation.",
    )
}

/** One immutable terminal view shared by the cancellation branch and the subsequently executed finally block. */
internal data class EvaluationTerminalSnapshot(
    val results: List<SampleResult>,
    val judgeCalls: List<JudgeCallRecord>,
)

/** A pinned terminal snapshot always represents cancellation, even when it already contains every selected sample. */
internal fun evaluationRunFinished(
    terminalSnapshot: EvaluationTerminalSnapshot?,
    completedSamples: Int,
    selectedSamples: Int,
): Boolean = terminalSnapshot == null && completedSamples == selectedSamples

/**
 * The final report and run manifest always consume the same terminal snapshot. A cancellation pins its current
 * sample before throwing, so the finally write cannot silently revert to the pre-cancellation lists.
 */
internal fun writeTerminalArtifacts(
    directory: Path,
    root: Path,
    identity: EvaluationIdentity,
    completed: List<SampleResult>,
    judgeRecords: List<JudgeCallRecord>,
    terminalSnapshot: EvaluationTerminalSnapshot?,
    selected: Int,
    production: Boolean,
    offlineSamples: Set<String>,
    task: String?,
    sampleId: String?,
    limit: Int?,
    startedAt: Instant,
    finished: Boolean,
    selectedSampleIds: List<String>,
) {
    val terminal = terminalSnapshot ?: EvaluationTerminalSnapshot(completed.toList(), judgeRecords.toList())
    writeReport(
        directory,
        root,
        identity,
        terminal.results,
        terminal.judgeCalls,
        selected,
        production,
        offlineSamples,
        task,
        sampleId,
        limit,
        finished,
        selectedSampleIds,
    )
    writeRunManifest(
        directory,
        root,
        identity,
        terminal.results,
        terminal.judgeCalls,
        selected,
        task,
        sampleId,
        limit,
        startedAt,
        finished,
        selectedSampleIds,
    )
}

private suspend fun evaluateProductionDreaming(
    sample: EvalSample,
    config: RealModelConfig,
    directory: Path,
): SampleResult = evaluateProductionDreaming(sample, config.llmClient, config.llmConfig, config.model, directory)

/**
 * Production Dreaming sample evaluation. A stage failure or cancellation carries the calls already captured at
 * the LlmClient boundary, together with their raw evidence references, so `result.json` never disagrees with
 * the raw artifacts written to the same directory.
 */
internal suspend fun evaluateProductionDreaming(
    sample: EvalSample,
    client: com.github.noahshen.alcyone.context.memory.core.llm.LlmClient,
    llmConfig: com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig,
    model: String,
    directory: Path,
): SampleResult =
    try {
        val observed = ProductionDreamingEvalRunner().run(sample, client, llmConfig, model, directory)
        val artifact = dreamingArtifact(observed)
        writeJson(directory.resolve("dreaming.json"), artifact)
        evaluateDreaming(sample, observed.copy(calls = observed.calls.map { it.copy(output = artifact) }), model)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: EvaluationStageCancelled) {
        throw failure
    } catch (failure: EvaluationStageFailed) {
        SampleResult(sample, failure.calls, error = safeError(failure.cause ?: failure))
    } catch (failure: Exception) {
        SampleResult(sample, error = safeError(failure))
    }

internal fun safeError(failure: Throwable): String =
    when (failure) {
        is com.github.noahshen.alcyone.context.memory.core.llm.LlmException -> "LlmException:${failure.errorCode}"
        is com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException -> "ContextLifecycleException:${failure.errorCode}"
        else -> failure::class.qualifiedName ?: "Evaluation failure"
    }

/**
 * Semantic review of a completed production sample, with every non-cancellation failure human-reviewable.
 * Cancellation carries the already completed production calls to the CLI terminal writer.
 */
internal suspend fun reviewCompletedSample(
    sample: EvalSample,
    completed: SampleResult,
    judge: SemanticJudge,
    artifact: () -> JsonObject? = { null },
): SampleResult =
    try {
        val checks = semanticReview(sample, completed, judge, artifact())
        SampleResult(sample, completed.calls, completed.checks + checks, completed.error)
    } catch (failure: CancellationException) {
        // Preserve completed production calls in the terminal cancellation snapshot as well as in the CLI's
        // mutable `reviewed` result.
        throw EvaluationStageCancelled(completed.calls, failure)
    } catch (failure: Exception) {
        SampleResult(
            sample,
            completed.calls,
            completed.checks +
                Check(
                    "semantic_judge_failed",
                    Verdict.REVIEW_REQUIRED,
                    safeError(failure),
                ),
            completed.error,
        )
    }

/**
 * Build the terminal `CANCELLED` sample. Calls captured before cancellation come from the stage itself (which
 * wraps them into [EvaluationStageCancelled]); when the cancellation carried none, the last completed result is
 * used. The result must never report zero calls while its own raw directory holds CANCELLED artifacts.
 */
internal fun cancelledSampleResult(
    sample: EvalSample,
    failure: Throwable,
    lastCompleted: SampleResult,
): SampleResult {
    val carried = (failure as? EvaluationStageCancelled)?.calls.orEmpty()
    val calls = if (carried.isNotEmpty()) carried else lastCompleted.calls
    return SampleResult(
        sample,
        calls,
        lastCompleted.checks +
            Check("evaluation_cancelled", Verdict.REVIEW_REQUIRED, "cancelled during sample"),
        "CANCELLED",
    )
}

internal fun writeCancelledArtifacts(
    sampleDirectory: Path,
    outputDirectory: Path,
    root: Path,
    identity: EvaluationIdentity,
    result: SampleResult,
    calls: List<JudgeCallRecord>,
    completed: List<SampleResult>,
    priorJudgeCalls: List<JudgeCallRecord>,
    selected: Int,
    production: Boolean,
    offlineSamples: Set<String>,
    task: String?,
    sampleId: String?,
    limit: Int?,
    runStartedAt: Instant,
    selectedSampleIds: List<String>? = null,
) {
    writeSampleArtifacts(sampleDirectory, identity, result, calls)
    writeReport(
        outputDirectory,
        root,
        identity,
        completed + result,
        priorJudgeCalls + calls,
        selected,
        production,
        offlineSamples,
        task,
        sampleId,
        limit,
        finished = false,
        selectedSampleIds = selectedSampleIds,
    )
    writeRunManifest(
        outputDirectory,
        root,
        identity,
        completed + result,
        priorJudgeCalls + calls,
        selected,
        task,
        sampleId,
        limit,
        runStartedAt,
        finished = false,
        selectedSampleIds = selectedSampleIds,
    )
}

internal fun writeSampleArtifacts(
    directory: Path,
    identity: EvaluationIdentity,
    result: SampleResult,
    calls: List<JudgeCallRecord>,
) {
    writeJson(directory.resolve("result.json"), result.json())
    val records = writeJudgeArtifacts(directory, calls)
    writeJson(
        directory.resolve("judge.json"),
        buildJsonObject {
            put("version", SemanticJudge.VERSION)
            put("model", identity.model)
            put("independentBoundary", false)
            put("reviewBoundary", "ADDITIONAL_CONTROLLED_REVIEW_NOT_INDEPENDENT_MODEL")
            put("rawPromptsAndResponsesRestricted", true)
            put("logicalCalls", calls.size)
            put("succeeded", calls.count { it.state == EvaluationCallState.SUCCEEDED })
            put("failed", calls.count { it.state == EvaluationCallState.FAILED })
            put("cancelled", calls.count { it.state == EvaluationCallState.CANCELLED })
            put(
                "missingRawEvidence",
                calls.count {
                    it.attempts.none { attempt ->
                        attempt.rawResponse != null || attempt.httpStatus != null
                    }
                },
            )
            putJsonArray("calls") { records.forEach { add(it) } }
        },
    )
}

/** Write one raw exchange artifact per judge call; judge.json only references path/SHA. */
internal fun writeJudgeArtifacts(
    directory: Path,
    calls: List<JudgeCallRecord>,
): List<JsonObject> {
    if (calls.isEmpty()) return emptyList()
    val rawDirectory = directory.resolve("raw")
    Files.createDirectories(rawDirectory)
    return calls.map { call ->
        val name = "judge-call-${call.callId}.json"
        val path = rawDirectory.resolve(name)
        writeJson(
            path,
            buildJsonObject {
                put("callId", call.callId)
                put("kind", "JUDGE")
                put("sampleId", call.sampleId)
                put("task", call.task)
                put("judgment", call.judgment)
                put("state", call.state.name)
                put("startedAt", call.startedAt.toString())
                put("elapsedMs", call.elapsedMs)
                put("input", call.input)
                put("rawResponse", call.rawResponse)
                put("parsedResponse", call.parsedResponse)
                putJsonArray("exchanges") {
                    call.attempts.forEach { attempt ->
                        add(
                            buildJsonObject {
                                put("attempt", attempt.attempt)
                                put("httpStatus", attempt.httpStatus ?: -1)
                                put("rawResponse", attempt.rawResponse ?: "")
                                put("rawResponsePresent", attempt.rawResponse != null)
                                put("failureKind", attempt.failureKind ?: "")
                                put("errorType", attempt.errorType ?: "")
                            },
                        )
                    }
                }
                put("safeError", call.error ?: "")
            },
        )
        buildJsonObject {
            put("callId", call.callId)
            put("judgment", call.judgment)
            put("state", call.state.name)
            put("elapsedMs", call.elapsedMs)
            call.error?.let { put("safeError", it) }
            putJsonObject("rawEvidence") {
                put("path", "raw/$name")
                put("sha256", sha256File(path))
                put("attempts", call.attempts.size)
                put("missing", call.attempts.none { it.rawResponse != null || it.httpStatus != null })
            }
        }
    }
}

/**
 * Single completeness decision shared by `report.json` and `run.json`.
 *
 * A run is complete only when the caller says it finished, every selected sample is present exactly once and
 * each sample's `result.json` really exists with a readable digest. Both writers consume this one snapshot, so
 * a partial run can never produce one artifact that claims `complete=true` and another that does not.
 */
internal data class RunCompleteness(
    val complete: Boolean,
    val blocked: List<String>,
    val sampleArtifactSha256: Map<String, String>,
) {
    fun addTo(json: kotlinx.serialization.json.JsonObjectBuilder) {
        json.put("complete", complete)
        json.putJsonArray("completeBlockedBy") { blocked.forEach { add(JsonPrimitive(it)) } }
    }
}

/** Blockers derived from `judge.json` and the judge raw artifacts it references. */
private data class JudgeEvidence(
    val blockers: List<String>,
)

internal fun runCompleteness(
    directory: Path,
    results: List<SampleResult>,
    selected: Int,
    finished: Boolean,
    resolvedSampleIds: List<String>? = null,
    judgeCalls: List<JudgeCallRecord> = emptyList(),
): RunCompleteness {
    val duplicated =
        results
            .groupingBy { it.sample.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    val digests = mutableMapOf<String, String>()
    val missing = mutableListOf<String>()
    val rawMismatch = mutableListOf<String>()
    val rawMissing = mutableListOf<String>()
    val rawReferenceMissing = mutableListOf<String>()
    val rawFlaggedMissing = mutableListOf<String>()
    results.forEach { result ->
        val sampleDirectory = directory.resolve(result.sample.id)
        val artifact = sampleDirectory.resolve("result.json")
        val digest = if (Files.isRegularFile(artifact)) sha256File(artifact) else null
        if (digest == null) missing += result.sample.id else digests[result.sample.id] = digest
        result.calls.forEachIndexed { index, call ->
            val evidence = call.rawEvidence
            if (evidence == null) {
                rawReferenceMissing += "${result.sample.id}:$index"
                return@forEachIndexed
            }
            if (evidence.missing) {
                rawFlaggedMissing += "${result.sample.id}:${evidence.path}"
                return@forEachIndexed
            }
            val rawFile = sampleDirectory.resolve(evidence.path)
            if (!Files.isRegularFile(rawFile)) {
                rawMissing += "${result.sample.id}:${evidence.path}"
            } else if (sha256File(rawFile) != evidence.sha256) {
                rawMismatch += "${result.sample.id}:${evidence.path}"
            }
        }
    }
    // Judge logical calls are evidence too: every call recorded in `judge.json` must still point at a raw
    // artifact whose bytes match the recorded SHA, and the recorded call set must equal the run's judge calls.
    val judgeArtifactMissing = mutableListOf<String>()
    val judgeCallCountMismatch = mutableListOf<String>()
    val judgeCallIdMismatch = mutableListOf<String>()
    val judgeRawMissing = mutableListOf<String>()
    val judgeRawMismatch = mutableListOf<String>()
    val judgeFlaggedMissing = mutableListOf<String>()
    val expectedJudgeCalls = judgeCalls.groupBy { it.sampleId }
    (results.map { it.sample.id }.toSet() + expectedJudgeCalls.keys).sorted().forEach { sampleId ->
        val expected = expectedJudgeCalls[sampleId].orEmpty()
        val judgeFile = directory.resolve(sampleId).resolve("judge.json")
        if (expected.isEmpty() && !Files.isRegularFile(judgeFile)) return@forEach
        if (!Files.isRegularFile(judgeFile)) {
            judgeArtifactMissing += sampleId
            return@forEach
        }
        val calls =
            try {
                Json
                    .parseToJsonElement(Files.readString(judgeFile))
                    .jsonObject
                    .getValue("calls")
                    .jsonArray
            } catch (failure: Exception) {
                judgeArtifactMissing += sampleId
                return@forEach
            }
        if (calls.size != expected.size) judgeCallCountMismatch += "$sampleId:${calls.size}/${expected.size}"
        val recordedIds =
            calls
                .map {
                    it.jsonObject
                        .getValue("callId")
                        .jsonPrimitive.content
                }.sorted()
        val expectedIds = expected.map { it.callId }.sorted()
        if (recordedIds != expectedIds) judgeCallIdMismatch += "$sampleId:${recordedIds - expectedIds}:${expectedIds - recordedIds}"
        calls.forEach { call ->
            val evidence = call.jsonObject["rawEvidence"]?.jsonObject
            if (evidence
                    ?.get("missing")
                    ?.jsonPrimitive
                    ?.content
                    ?.toBoolean() == true
            ) {
                judgeFlaggedMissing += "$sampleId:${call.jsonObject.getValue("callId").jsonPrimitive.content}"
            }
            val path = evidence?.get("path")?.jsonPrimitive?.content
            val sha = evidence?.get("sha256")?.jsonPrimitive?.content
            if (path == null || sha == null) {
                judgeRawMissing += "$sampleId:NO_REFERENCE"
                return@forEach
            }
            val rawFile = directory.resolve(sampleId).resolve(path)
            if (!Files.isRegularFile(rawFile)) {
                judgeRawMissing += "$sampleId:$path"
            } else if (sha256File(rawFile) != sha) {
                judgeRawMismatch += "$sampleId:$path"
            }
        }
    }
    val judgeEvidence =
        JudgeEvidence(
            listOfNotNull(
                judgeArtifactMissing.takeIf { it.isNotEmpty() }?.let { "MISSING_JUDGE_ARTIFACT:${it.sorted()}" },
                judgeCallCountMismatch.takeIf { it.isNotEmpty() }?.let { "JUDGE_CALL_COUNT_MISMATCH:${it.sorted()}" },
                judgeCallIdMismatch.takeIf { it.isNotEmpty() }?.let { "JUDGE_CALL_ID_MISMATCH:${it.sorted()}" },
                judgeRawMissing.takeIf { it.isNotEmpty() }?.let { "MISSING_JUDGE_RAW_ARTIFACT:${it.sorted()}" },
                judgeRawMismatch.takeIf { it.isNotEmpty() }?.let { "JUDGE_RAW_ARTIFACT_SHA_MISMATCH:${it.sorted()}" },
                judgeFlaggedMissing.takeIf { it.isNotEmpty() }?.let { "JUDGE_RAW_EVIDENCE_MARKED_MISSING:${it.sorted()}" },
            ),
        )
    val blocked =
        buildList {
            if (results.size != selected) add("SAMPLE_COUNT_MISMATCH:${results.size}/$selected")
            if (duplicated.isNotEmpty()) add("DUPLICATE_SAMPLE_IDS:${duplicated.sorted()}")
            if (missing.isNotEmpty()) add("MISSING_RESULT_ARTIFACT:${missing.sorted()}")
            if (rawReferenceMissing.isNotEmpty()) add("MISSING_RAW_EVIDENCE_REFERENCE:${rawReferenceMissing.sorted()}")
            if (rawFlaggedMissing.isNotEmpty()) add("RAW_EVIDENCE_MARKED_MISSING:${rawFlaggedMissing.sorted()}")
            if (rawMissing.isNotEmpty()) add("MISSING_RAW_ARTIFACT:${rawMissing.sorted()}")
            if (rawMismatch.isNotEmpty()) add("RAW_ARTIFACT_SHA_MISMATCH:${rawMismatch.sorted()}")
            if (resolvedSampleIds != null) {
                // Selection and results must describe the same samples, in both directions.
                val expected = resolvedSampleIds.toSet()
                val recorded = results.map { it.sample.id }.toSet()
                val unexpected = (recorded - expected).sorted()
                val notRun = (expected - recorded).sorted()
                if (unexpected.isNotEmpty()) add("UNEXPECTED_SAMPLE_IDS:$unexpected")
                if (notRun.isNotEmpty()) add("UNRUN_SAMPLE_IDS:$notRun")
            }
            if (!finished) add("RUN_NOT_FINISHED")
            if (resolvedSampleIds == null) add("MISSING_SELECTION_IDS")
            resolvedSampleIds?.let { ids ->
                if (ids.size != selected) add("SELECTION_SIZE_MISMATCH:${ids.size}/$selected")
                val repeatedSelection =
                    ids
                        .groupingBy { it }
                        .eachCount()
                        .filterValues { it > 1 }
                        .keys
                if (repeatedSelection.isNotEmpty()) add("DUPLICATE_SELECTION_IDS:${repeatedSelection.sorted()}")
            }
            judgeEvidence.blockers.forEach { add(it) }
        }
    return RunCompleteness(finished && blocked.isEmpty(), blocked, digests)
}

internal fun writeReport(
    directory: Path,
    root: Path,
    model: String,
    profile: String,
    responseFormat: String,
    temperature: Double?,
    results: List<SampleResult>,
    judgeCalls: List<JudgeCallRecord>,
    selected: Int,
    production: Boolean,
    offlineSamples: Set<String>,
    task: String?,
    sampleId: String?,
    limit: Int?,
    finished: Boolean,
    selectedSampleIds: List<String>? = null,
) {
    val completeness = runCompleteness(directory, results, selected, finished, selectedSampleIds, judgeCalls)
    val report =
        buildJsonObject {
            put("schemaVersion", 3)
            put("kind", "CT31_PRODUCTION_SEMANTIC_EVALUATION")
            put("executionMode", if (production) "PRODUCTION_COMPONENTS" else "OFFLINE_PROTOCOL_BASELINE")
            put("realDatabaseAssertions", production)
            putJsonArray("offlineSampleIds") { offlineSamples.sorted().forEach { add(JsonPrimitive(it)) } }
            put("datasetVersion", "ct31-v1")
            put("generatedAt", Instant.now().toString())
            // Complete is never derived here: it comes from the shared run snapshot used by `run.json`.
            completeness.addTo(this)
            putJsonObject("sampleResultSha256") {
                completeness.sampleArtifactSha256.toSortedMap().forEach { (id, digest) -> put(id, digest) }
            }
            put("model", model)
            put("profile", profile)
            put("responseFormat", responseFormat)
            put("temperature", temperature)
            put("maxAttempts", 1)
            putJsonObject("selection") {
                put("task", task)
                put("sample", sampleId)
                put("limit", limit)
            }
            put("selectedSamples", selected)
            putJsonArray("selectedSampleIds") { (selectedSampleIds ?: emptyList()).sorted().forEach { add(JsonPrimitive(it)) } }
            put("sampleCount", results.size)
            put("semanticJudge", SemanticJudge.VERSION)
            put("judgeIndependence", "ADDITIONAL_CONTROLLED_REVIEW_NOT_INDEPENDENT_MODEL")
            // PASS/FAIL counts come from a model judge, never from a person: H1/H2/H3 remain outstanding until
            // they are actually executed and recorded. All-PASS must not be readable as "review done".
            put("humanReviewStillRequired", true)
            putJsonObject("humanReview") {
                put("state", "NOT_EXECUTED")
                // Explicit, never derived from result status: no report can claim human review is done until
                // H1/H2/H3 are actually executed and recorded.
                put("complete", false)
                putJsonArray("blocked") { listOf("H1", "H2", "H3").forEach { add(JsonPrimitive(it)) } }
                put("required", listOf("H1", "H2", "H3").joinToString(","))
                put("note", "Judge verdicts are not human calibration; H1/H2/H3 must be executed separately")
            }
            put("realUserStudy", false)
            put("sourceManifestSha256", sha256File(root.resolve("evals/manifest-v1.json")))
            putJsonObject("evaluationImplementation") {
                put("version", EVALUATION_IMPLEMENTATION_VERSION)
                putJsonObject("criticalProductionSha256") {
                    evaluationImplementationSha(root, EVALUATION_CRITICAL_PRODUCTION_SOURCES).forEach { (relative, digest) ->
                        put(relative, digest)
                    }
                }
                putJsonObject("sourceSha256") {
                    // Hash the complete evaluation source-set view, not a filename-curated subset. This includes
                    // executable helpers and the tests that exercise them, so adding or editing an evaluation source
                    // can never leave `sourceSha256` describing a different implementation.
                    evaluationImplementationSha(root).forEach { (relative, digest) ->
                        put(relative, digest)
                    }
                }
            }
            putJsonObject("rawEvidence") {
                put("productionLogicalCalls", results.sumOf { it.calls.size })
                put("productionSucceeded", results.sumOf { r -> r.calls.count { it.rawEvidence?.state == "SUCCEEDED" } })
                put("productionFailed", results.sumOf { r -> r.calls.count { it.rawEvidence?.state == "FAILED" } })
                put("productionCancelled", results.sumOf { r -> r.calls.count { it.rawEvidence?.state == "CANCELLED" } })
                put(
                    "productionMissingRawEvidence",
                    results.sumOf { r ->
                        r.calls.count { it.rawEvidence == null || it.rawEvidence.missing }
                    },
                )
                put("judgeLogicalCalls", judgeCalls.size)
                put(
                    "judgeMissingRawEvidence",
                    judgeCalls.count { call ->
                        call.attempts.none {
                            it.rawResponse != null ||
                                it.httpStatus != null
                        }
                    },
                )
            }
            put("semanticQualityEvaluated", results.any { it.status == "PASS" || it.status == "FAIL" })
            putJsonObject("summary") {
                listOf("PASS", "FAIL", "REVIEW_REQUIRED", "PROTOCOL_FAILURE", "NOT_RUN").forEach { status ->
                    put(
                        status,
                        results.count {
                            it.status ==
                                status
                        },
                    )
                }
                put("modelCalls", results.sumOf { it.calls.size })
                put("inputTokens", results.sumOf { r -> r.calls.sumOf { it.usage?.inputTokens ?: 0L } })
                put("outputTokens", results.sumOf { r -> r.calls.sumOf { it.usage?.outputTokens ?: 0L } })
                put(
                    "missingUsageCount",
                    results.sumOf { r ->
                        r.calls.count { it.usage?.inputTokens == null || it.usage?.outputTokens == null }
                    },
                )
                put("latencyMs", results.sumOf { r -> r.calls.sumOf { it.latencyMs } })
                put("truncatedResponses", results.sumOf { r -> r.calls.count { it.truncated } })
                put(
                    "tasks",
                    JsonArray(
                        results
                            .map { it.sample.task }
                            .distinct()
                            .sorted()
                            .map(::JsonPrimitive),
                    ),
                )
                put("judgeCalls", judgeCalls.size)
                put("judgeSucceeded", judgeCalls.count { it.state == EvaluationCallState.SUCCEEDED })
                put("judgeFailed", judgeCalls.count { it.state == EvaluationCallState.FAILED })
                put("judgeCancelled", judgeCalls.count { it.state == EvaluationCallState.CANCELLED })
                put(
                    "judgeMissingRawEvidence",
                    judgeCalls.count { call -> call.attempts.none { it.rawResponse != null || it.httpStatus != null } },
                )
                put("judgeTokenUsageRecorded", false)
                putJsonObject("taskCounts") {
                    results.groupingBy { it.sample.task }.eachCount().toSortedMap().forEach { (name, count) ->
                        put(name, count)
                    }
                }
            }
            putJsonArray("results") { results.forEach { add(it.json()) } }
        }
    writeJson(directory.resolve("report.json"), report)
    val summaryText =
        buildString {
            append(
                "# CT31 production semantic evaluation\n\n- Model: `$model`\n- Dataset: `ct31-v1`\n- Completed: ${results.size}/$selected (${if (completeness.complete) "COMPLETE" else "PARTIAL"})\n",
            )
            append(
                "- Execution: `${if (production) "real production components and SQLite assertions" else "offline protocol baseline; not an end-to-end production run"}`\n",
            )
            if (offlineSamples.isNotEmpty()) append("- Offline-only samples: `${offlineSamples.sorted()}`\n")
            if (!completeness.complete) append("- Complete blocked by: `${completeness.blocked}`\n")
            append(
                "- Production raw evidence missing: ${results.sumOf { r ->
                    r.calls.count { it.rawEvidence == null || it.rawEvidence.missing }
                }} of ${results.sumOf { it.calls.size }} logical calls\n" +
                    "- Judge: `${SemanticJudge.VERSION}` (additional controlled review, not an independent model; raw evidence missing: ${judgeCalls.count { call ->
                        call.attempts.none { attempt ->
                            attempt.rawResponse != null || attempt.httpStatus != null
                        }
                    }})\n- Human review: required for REVIEW_REQUIRED and a calibration audit; NOT_EXECUTED (blocked: H1,H2,H3)\n- This is not a real-user study.\n\n",
            )
            listOf("PASS", "FAIL", "REVIEW_REQUIRED", "PROTOCOL_FAILURE", "NOT_RUN").forEach { status ->
                append("- $status: ${results.count { it.status == status }}\n")
            }
            append("\n| Sample | Task | Result | Production calls |\n|---|---|---|---|\n")
            results.forEach { append("| ${it.sample.id} | ${it.sample.task} | ${it.status} | ${it.calls.size} |\n") }
        }
    val temp = Files.createTempFile(directory, ".eval-summary-", ".tmp")
    Files.writeString(temp, summaryText)
    Files.move(temp, directory.resolve("report.md"), StandardCopyOption.ATOMIC_MOVE)
}

private fun writeReport(
    directory: Path,
    root: Path,
    identity: EvaluationIdentity,
    results: List<SampleResult>,
    judgeCalls: List<JudgeCallRecord>,
    selected: Int,
    production: Boolean,
    offlineSamples: Set<String>,
    task: String?,
    sampleId: String?,
    limit: Int?,
    finished: Boolean,
    selectedSampleIds: List<String>? = null,
) = writeReport(
    directory,
    root,
    identity.model,
    identity.profileName,
    identity.responseFormat,
    identity.temperature,
    results,
    judgeCalls,
    selected,
    production,
    offlineSamples,
    task,
    sampleId,
    limit,
    finished,
    selectedSampleIds,
)

internal const val EVALUATION_IMPLEMENTATION_VERSION = "ct31-eval-r2-v2"

/**
 * Production sources whose behaviour the measured numbers depend on. A missing file is an error, never a
 * silently reported `MISSING`: an absent digest would make two different implementations look identical.
 */
internal val EVALUATION_CRITICAL_PRODUCTION_SOURCES =
    setOf(
        "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation/EvaluationRawExchangeCapture.kt",
        // Offline-baseline parsers/encoders decide the verdict of the protocol baseline, so they are part of the
        // measured implementation rather than incidental test scaffolding.
        "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation/OfflineEvaluationTaskAdapters.kt",
        "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation/EvalOutput.kt",
        "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation/ProductionEvaluationAdapters.kt",
        "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation/ProductionDreamingEvaluationRunner.kt",
        "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/llm/KtorLlmClient.kt",
        "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/llm/RawExchangeObserver.kt",
        "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/organization/DefaultOrganizationWorker.kt",
    )

/** Evaluation-only sources: runner assembly, judge, scoring and CLI artifact writing. */
internal val EVALUATION_EXECUTION_SOURCES =
    setOf(
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/ProductionEvalRunner.kt",
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/ProductionDreamingEvalRunner.kt",
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/SemanticJudge.kt",
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/SemanticReview.kt",
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/DreamingEvaluation.kt",
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/RawEvidence.kt",
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/EvalResult.kt",
        // Evaluation-only main source shipped for the offline baseline; the on-disk completeness test below
        // requires every file here to stay listed.
        "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation/OfflineEvaluationTestFixtures.kt",
        // Identity/terminal types and fixtures decide what a recorded call and its artifacts contain, so a
        // report without them cannot be traced back to a complete implementation.
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/EvaluationTerminal.kt",
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/EvalFixtures.kt",
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/SemanticEvaluationRunner.kt",
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/RealModelConfig.kt",
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/RealModelEvalCli.kt",
    )

/**
 * Evaluation directories whose source-set contents are part of the report implementation. The two evaluation
 * directories contain both execution helpers and their tests; keeping all of them avoids a filename allow/deny list
 * that could silently omit a new runner. Critical production seams outside these directories are supplied above.
 */
internal val EVALUATION_SOURCE_DIRECTORIES =
    listOf(
        "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation",
        "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation",
    )

/** Discover every evaluation source file from disk plus the curated production seams outside those directories. */
internal fun evaluationImplementationFiles(root: Path): List<String> {
    val discovered =
        EVALUATION_SOURCE_DIRECTORIES.flatMap { relativeDirectory ->
            val directory = root.resolve(relativeDirectory)
            require(Files.isDirectory(directory)) { "Evaluation source directory missing: $relativeDirectory" }
            Files.walk(directory).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".kt") || it.fileName.toString().endsWith(".java") }
                    .map { root.relativize(it).toString().replace('\\', '/') }
                    .toList()
            }
        }
    return (EVALUATION_CRITICAL_PRODUCTION_SOURCES + discovered).distinct().sorted()
}

/** SHA-256 over file bytes for every implementation source; throws when a listed or discovered file is absent. */
internal fun evaluationImplementationSha(
    root: Path,
    files: Collection<String>? = null,
): Map<String, String> {
    val selected = files ?: evaluationImplementationFiles(root)
    return selected.distinct().sorted().associateWith { relative ->
        val path = root.resolve(relative)
        require(Files.isRegularFile(path)) { "Evaluation implementation source missing: $relative" }
        sha256File(path)
    }
}

internal val CT31_DATASET_FILES =
    listOf(
        "evals/datasets/extraction.jsonl",
        "evals/datasets/governance.jsonl",
        "evals/datasets/scanner.jsonl",
        "evals/datasets/retrieval.jsonl",
        "evals/datasets/memory-map.jsonl",
        "evals/datasets/dreaming.jsonl",
    )

/**
 * Immutable run manifest. Every artifact SHA is computed from file bytes, so a re-serialized or tampered
 * report cannot keep the old digest.
 */
internal fun writeRunManifest(
    directory: Path,
    root: Path,
    identity: EvaluationIdentity,
    results: List<SampleResult>,
    judgeCalls: List<JudgeCallRecord>,
    selected: Int,
    task: String?,
    sampleId: String?,
    limit: Int?,
    startedAt: Instant,
    finished: Boolean,
    selectedSampleIds: List<String>? = null,
) = writeRunManifest(
    directory,
    root,
    identity.model,
    identity.profileName,
    identity.responseFormat,
    results,
    judgeCalls,
    selected,
    task,
    sampleId,
    limit,
    startedAt,
    finished,
    selectedSampleIds,
)

internal fun writeRunManifest(
    directory: Path,
    root: Path,
    model: String,
    profile: String,
    responseFormat: String,
    results: List<SampleResult>,
    judgeCalls: List<JudgeCallRecord>,
    selected: Int,
    task: String?,
    sampleId: String?,
    limit: Int?,
    startedAt: Instant,
    finished: Boolean,
    selectedSampleIds: List<String>? = null,
) {
    val completeness = runCompleteness(directory, results, selected, finished, selectedSampleIds, judgeCalls)
    val samples =
        results.map { result ->
            val sampleDirectory = directory.resolve(result.sample.id)
            val files =
                if (Files.isDirectory(sampleDirectory)) {
                    Files.walk(sampleDirectory).use { stream ->
                        stream
                            .filter { Files.isRegularFile(it) }
                            .map { sampleDirectory.relativize(it).toString().replace('\\', '/') }
                            .sorted()
                            .toList()
                    }
                } else {
                    emptyList()
                }
            buildJsonObject {
                put("id", result.sample.id)
                put("status", result.status)
                putJsonArray("artifacts") {
                    files.forEach { relative ->
                        add(
                            buildJsonObject {
                                put("path", relative)
                                put("sha256", sha256File(sampleDirectory.resolve(relative)))
                            },
                        )
                    }
                }
            }
        }
    writeJson(
        directory.resolve("run.json"),
        buildJsonObject {
            put("kind", "CT31_RUN_MANIFEST")
            put("evaluationTask", "CT31_PRODUCTION_SEMANTIC_EVALUATION")
            put("evaluationImplementation", EVALUATION_IMPLEMENTATION_VERSION)
            put("complete", completeness.complete)
            putJsonArray("completeBlockedBy") { completeness.blocked.forEach { add(JsonPrimitive(it)) } }
            put("startedAt", startedAt.toString())
            put("updatedAt", Instant.now().toString())
            put("model", model)
            put("profile", profile)
            put("responseFormat", responseFormat)
            putJsonObject("selection") {
                put("task", task)
                put("sample", sampleId)
                put("limit", limit)
            }
            put("selectedSamples", selected)
            putJsonArray("selectedSampleIds") { (selectedSampleIds ?: emptyList()).sorted().forEach { add(JsonPrimitive(it)) } }
            put("sampleCount", results.size)
            put("judgeLogicalCalls", judgeCalls.size)
            put("manifestSha256", sha256File(root.resolve("evals/manifest-v1.json")))
            putJsonObject("datasetSha256") {
                CT31_DATASET_FILES.forEach { relative ->
                    val path = root.resolve(relative)
                    put(relative, if (Files.isRegularFile(path)) sha256File(path) else "MISSING")
                }
            }
            val report = directory.resolve("report.json")
            val reportMd = directory.resolve("report.md")
            put("reportSha256", if (Files.isRegularFile(report)) sha256File(report) else "MISSING")
            put("reportMarkdownSha256", if (Files.isRegularFile(reportMd)) sha256File(reportMd) else "MISSING")
            putJsonArray("samples") { samples.forEach { add(it) } }
        },
    )
}

/**
 * A live run must start in a fresh directory so a previous partial or complete run can never be overwritten.
 * Extracted from `main` so the refusal is testable without network access.
 */
internal fun requireEmptyOutputDirectory(directory: Path) {
    require(
        Files.notExists(directory) ||
            Files.newDirectoryStream(directory).use {
                !it.iterator().hasNext()
            },
    ) { "Evaluation output directory must be empty: $directory" }
}

internal fun writeJson(
    path: Path,
    value: kotlinx.serialization.json.JsonObject,
) {
    val temp = Files.createTempFile(path.parent, ".eval-", ".tmp")
    Files.writeString(
        temp,
        evalJson.encodeToString(
            kotlinx.serialization.json.JsonObject
                .serializer(),
            value,
        ) + "\n",
    )
    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE)
}
