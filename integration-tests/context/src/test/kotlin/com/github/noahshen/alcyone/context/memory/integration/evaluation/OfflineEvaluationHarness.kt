package com.github.noahshen.alcyone.context.memory.integration.evaluation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.system.exitProcess

internal enum class MetricStatus {
    CALCULATED,
    NOT_APPLICABLE,
    PROTOCOL_FAILURE,
}

internal data class MetricValue(
    val status: MetricStatus,
    val value: Double? = null,
    val numerator: Int = 0,
    val denominator: Int = 0,
)

internal data class RelevanceJudgment(
    val id: String,
    val gain: Int,
    val critical: Boolean = false,
)

internal data class SearchObservation(
    val queryId: String,
    val k: Int,
    val candidateIds: List<String>,
    val judgments: List<RelevanceJudgment>,
    val scannerRanking: List<String>,
    val rerankInputIds: List<String>,
    val rerankRanking: List<String>,
    val expectedOrder: List<String> = emptyList(),
    /**
     * Every candidate with a human judgment, including zero-gain (known irrelevant) candidates.
     * Defaulting to the positive qrels keeps callers honest: unrelated candidates must be added
     * explicitly rather than being mistaken for unjudged output.
     */
    val judgedIds: List<String> = judgments.map { it.id },
)

internal enum class SearchStageStatus {
    PASS,
    FAIL,
    PROTOCOL_FAILURE,
    NOT_APPLICABLE,
}

internal data class SearchMetrics(
    val recallAtK: MetricValue,
    val criticalRecallAtK: MetricValue,
    val mrrAtK: MetricValue,
    val finalRecallAtK: MetricValue,
    val scannerStatus: SearchStageStatus,
    val rerankStatus: SearchStageStatus,
    val orderStatus: SearchStageStatus,
    val outOfSetIdCount: Int,
    val unjudgedIdCount: Int,
    val protocolFailureCount: Int,
)

internal enum class FakeReplayStatus {
    COMPLETED_FIXTURE_REPLAY,
    NOT_RUN,
}

internal data class FakeReplayResult(
    val scenarioCount: Int,
    val status: FakeReplayStatus,
)

/** Offline-only formulas. This object has no network, model, provider, or secret dependency. */
internal object OfflineEvaluationHarness {
    private val fixtureJson = Json { ignoreUnknownKeys = false }

    fun evaluateSearch(observation: SearchObservation): SearchMetrics {
        if (observation.k <= 0) throw EvaluationDatasetException("${observation.queryId}: K must be positive")
        if (observation.candidateIds.toSet().size != observation.candidateIds.size) {
            throw EvaluationDatasetException("${observation.queryId}: candidateIds contain duplicates")
        }
        val judgmentIds = observation.judgments.map { it.id }
        if (judgmentIds.toSet().size != judgmentIds.size) {
            throw EvaluationDatasetException("${observation.queryId}: judgments contain duplicate IDs")
        }
        if (observation.judgments.any { it.gain <= 0 }) {
            throw EvaluationDatasetException("${observation.queryId}: qrel gains must be positive")
        }
        if (observation.judgments.any { it.gain > 3 }) {
            throw EvaluationDatasetException("${observation.queryId}: qrel gains must be in 1..3")
        }
        val judgedIdDuplicates = firstDuplicate(observation.judgedIds)
        if (judgedIdDuplicates != null) {
            throw EvaluationDatasetException("${observation.queryId}: judgedIds contain duplicates")
        }
        val expectedOrderDuplicates = firstDuplicate(observation.expectedOrder)
        if (expectedOrderDuplicates != null) {
            throw EvaluationDatasetException("${observation.queryId}: expectedOrder contains duplicates")
        }

        val candidates = observation.candidateIds.toSet()
        val relevant = judgmentIds.toSet()
        val judged = observation.judgedIds.toSet()
        val unknownJudgments = relevant - candidates
        if (unknownJudgments.isNotEmpty()) {
            throw EvaluationDatasetException(
                "${observation.queryId}: judgments reference unknown candidate IDs: ${unknownJudgments.sorted().joinToString()}",
            )
        }
        val unknownJudged = judged - candidates
        if (unknownJudged.isNotEmpty()) {
            throw EvaluationDatasetException(
                "${observation.queryId}: judgedIds reference unknown candidate IDs: ${unknownJudged.sorted().joinToString()}",
            )
        }
        val unjudgedQrels = relevant - judged
        if (unjudgedQrels.isNotEmpty()) {
            throw EvaluationDatasetException(
                "${observation.queryId}: positive qrels are missing from judgedIds: ${unjudgedQrels.sorted().joinToString()}",
            )
        }
        val unknownExpectedOrder = observation.expectedOrder.toSet() - candidates
        if (unknownExpectedOrder.isNotEmpty()) {
            throw EvaluationDatasetException(
                "${observation.queryId}: expectedOrder references unknown candidate IDs: ${unknownExpectedOrder.sorted().joinToString()}",
            )
        }

        val scannerDuplicates = firstDuplicate(observation.scannerRanking)
        val rerankInputDuplicates = firstDuplicate(observation.rerankInputIds)
        val rerankDuplicates = firstDuplicate(observation.rerankRanking)
        val scannerOutOfSet = observation.scannerRanking.count { it !in candidates }
        val rerankInputOutOfSet = observation.rerankInputIds.count { it !in candidates }
        val rerankInputSet = observation.rerankInputIds.toSet()
        val rerankOutputOutOfInput = observation.rerankRanking.count { it !in rerankInputSet }
        val rerankInputMismatch =
            observation.rerankInputIds != observation.scannerRanking.take(observation.rerankInputIds.size)
        val scannerUnjudged = observation.scannerRanking.count { it !in judged }
        val rerankUnjudged = observation.rerankRanking.count { it !in judged }
        val unjudgedIdCount = (observation.scannerRanking + observation.rerankRanking).filter { it !in judged }.toSet().size
        val outOfSet = scannerOutOfSet + rerankInputOutOfSet + rerankOutputOutOfInput
        val emptyQrels = relevant.isEmpty()
        val scannerFailed =
            scannerDuplicates != null ||
                scannerOutOfSet > 0 ||
                scannerUnjudged > 0 ||
                (emptyQrels && observation.scannerRanking.isNotEmpty())
        val rerankFailed =
            rerankInputDuplicates != null ||
                rerankDuplicates != null ||
                rerankInputOutOfSet > 0 ||
                rerankInputMismatch ||
                rerankOutputOutOfInput > 0 ||
                rerankUnjudged > 0 ||
                (emptyQrels && (observation.rerankInputIds.isNotEmpty() || observation.rerankRanking.isNotEmpty()))
        val orderFailed =
            observation.expectedOrder.isNotEmpty() &&
                observation.rerankRanking.take(observation.expectedOrder.size) != observation.expectedOrder
        if (scannerFailed || rerankFailed) {
            return protocolFailure(
                scannerStatus = if (scannerFailed) SearchStageStatus.PROTOCOL_FAILURE else SearchStageStatus.PASS,
                rerankStatus = if (rerankFailed) SearchStageStatus.PROTOCOL_FAILURE else SearchStageStatus.PASS,
                orderStatus = SearchStageStatus.NOT_APPLICABLE,
                outOfSet = outOfSet,
                unjudged = unjudgedIdCount,
            )
        }

        val critical =
            observation.judgments
                .filter { it.critical }
                .map { it.id }
                .toSet()
        val scannerTopK = observation.scannerRanking.take(observation.k)
        val rerankTopK = observation.rerankRanking.take(observation.k)
        val scannerApplicable = relevant.isNotEmpty()
        val rerankApplicable = relevant.isNotEmpty()
        return SearchMetrics(
            recallAtK =
                if (!scannerApplicable) {
                    MetricValue(MetricStatus.NOT_APPLICABLE)
                } else {
                    ratio(scannerTopK.count { it in relevant }, relevant.size)
                },
            criticalRecallAtK =
                if (!scannerApplicable || critical.isEmpty()) {
                    MetricValue(MetricStatus.NOT_APPLICABLE)
                } else {
                    ratio(scannerTopK.count { it in critical }, critical.size)
                },
            mrrAtK =
                if (!rerankApplicable) {
                    MetricValue(MetricStatus.NOT_APPLICABLE)
                } else {
                    reciprocalRank(rerankTopK, relevant)
                },
            finalRecallAtK =
                if (!rerankApplicable) {
                    MetricValue(MetricStatus.NOT_APPLICABLE)
                } else {
                    ratio(rerankTopK.count { it in relevant }, relevant.size)
                },
            scannerStatus = SearchStageStatus.PASS,
            rerankStatus = SearchStageStatus.PASS,
            orderStatus = if (orderFailed) SearchStageStatus.FAIL else SearchStageStatus.PASS,
            outOfSetIdCount = 0,
            unjudgedIdCount = 0,
            protocolFailureCount = 0,
        )
    }

    fun macro(values: List<MetricValue>): MetricValue {
        val applicable = values.filter { it.status == MetricStatus.CALCULATED }
        if (values.any { it.status == MetricStatus.PROTOCOL_FAILURE }) {
            return MetricValue(MetricStatus.PROTOCOL_FAILURE)
        }
        if (applicable.isEmpty()) return MetricValue(MetricStatus.NOT_APPLICABLE)
        return MetricValue(
            status = MetricStatus.CALCULATED,
            value = applicable.sumOf { it.value ?: 0.0 } / applicable.size,
            numerator = applicable.sumOf { it.numerator },
            denominator = applicable.sumOf { it.denominator },
        )
    }

    /** Rebuilds evals/results/offline-v1.json solely from canonical manifest/data. */
    fun buildOfflineReport(packageData: EvaluationPackage): JsonObject {
        val manifestPath = packageData.root.resolve("evals/manifest-v1.json")
        val manifestSha =
            MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(manifestPath))
                .joinToString("") { "%02x".format(it) }
        return buildJsonObject {
            put("schemaVersion", 1)
            put("datasetVersion", packageData.manifest.datasetVersion)
            put("sourceManifestSha256", manifestSha)
            put("semanticQualityEvaluated", false)
            putJsonObject("integrity") {
                put("status", "PASS")
                put("datasetCount", packageData.datasets.size)
                put("sampleCount", packageData.samples.size)
                put("importedCount", packageData.datasets.sumOf { it.importedCount })
                put("newCount", packageData.datasets.sumOf { it.newCount })
                put(
                    "duplicateIdCount",
                    if (packageData.samples
                            .map { it.id }
                            .toSet()
                            .size == packageData.samples.size
                    ) {
                        0
                    } else {
                        1
                    },
                )
                put("danglingReferenceCount", 0)
                put("privacyViolationCount", 0)
            }
            putJsonArray("datasets") {
                packageData.datasets.forEach { dataset ->
                    addJsonObject {
                        put("id", dataset.id)
                        put("task", dataset.task)
                        put("sampleCount", dataset.samples.size)
                        put("importedCount", dataset.importedCount)
                        put("newCount", dataset.newCount)
                        put("sha256", dataset.sha256)
                        putJsonArray("riskTags") {
                            dataset.samples
                                .flatMap { it.oracle.getValue("riskTags").jsonArray }
                                .map { it.jsonPrimitive.content }
                                .toSortedSet()
                                .forEach { add(JsonPrimitive(it)) }
                        }
                        put(
                            "criticalCount",
                            dataset.samples.count {
                                it.oracle
                                    .getValue("criticality")
                                    .jsonPrimitive.content == "CRITICAL"
                            },
                        )
                        put(
                            "expectedEmptyCount",
                            dataset.samples.count {
                                it.oracle
                                    .getValue("expectedEmpty")
                                    .jsonPrimitive.content ==
                                    "true"
                            },
                        )
                        putJsonArray("expectedIds") {
                            dataset.samples
                                .map { it.id }
                                .sorted()
                                .forEach { add(JsonPrimitive(it)) }
                        }
                        put("protocolStatus", "NOT_RUN")
                        put("semanticStatus", "NOT_EVALUATED")
                    }
                }
            }
            putJsonObject("inputCoverage") {
                putJsonObject("retrieval") {
                    val retrieval = packageData.datasets.single { it.task == "MEMORY_SEARCH" }
                    val observations =
                        retrieval.samples.map { sample ->
                            RetrievalInputCoverage(
                                sampleId = sample.id,
                                k =
                                    sample.oracle
                                        .getValue("k")
                                        .jsonPrimitive.int,
                                candidateCount =
                                    sample.input
                                        .getValue("candidates")
                                        .jsonArray.size,
                                relevantJudgmentCount =
                                    sample.oracle
                                        .getValue("qrels")
                                        .jsonArray.size,
                                expectedEmpty =
                                    sample.oracle
                                        .getValue("expectedEmpty")
                                        .jsonPrimitive.boolean,
                            )
                        }
                    put("sampleCount", retrieval.samples.size)
                    put("samplesWithRelevantJudgments", observations.count { it.relevantJudgmentCount > 0 })
                    put("samplesWithoutRelevantJudgments", observations.count { it.relevantJudgmentCount == 0 })
                    put("totalCandidateCount", observations.sumOf { it.candidateCount })
                    put("semanticMetricsStatus", "NOT_APPLICABLE_NO_CAPTURED_OUTPUT")
                    put("semanticMetricsNote", "Canonical inputs and qrels are summarized; no retrieval outputs were captured.")
                    putJsonArray("samples") {
                        observations.forEach { observation ->
                            addJsonObject {
                                put("sampleId", observation.sampleId)
                                put("k", observation.k)
                                put("candidateCount", observation.candidateCount)
                                put("relevantJudgmentCount", observation.relevantJudgmentCount)
                                put("expectedEmpty", observation.expectedEmpty)
                            }
                        }
                    }
                }
            }
            val replay = verifyDreamingFixtureReplay(packageData)
            putJsonObject("fakeReplay") {
                put("scenarioCount", replay.scenarioCount)
                put("status", replay.status.name)
                put("semanticEvidence", false)
            }
            putJsonObject("aggregates") {
                putJsonObject("search") {
                    put("k", 3)
                    put("qrelsVersion", packageData.manifest.datasetVersion)
                    listOf("recallAtK", "criticalRecallAtK", "mrrAtK", "finalRecallAtK").forEach { name -> put(name, notApplicable()) }
                }
                putJsonObject("extraction") {
                    put("mustFactRecall", notApplicable())
                    put("emptyExactRate", notApplicable())
                }
            }
            put("criticalErrors", JsonArray(emptyList()))
            putJsonObject("modelRun") {
                put("status", "NOT_RUN")
                put("usage", JsonNull)
                put("cost", JsonNull)
                put("reason", "B2 is offline-only; real-model execution remains locked.")
            }
        }
    }

    fun writeOfflineReport(root: Path): Path {
        val report = buildOfflineReport(EvalDatasetLoader.load(root))
        val destination = root.resolve("evals/results/offline-v1.json")
        val text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n"
        if (!Files.isRegularFile(destination) || Files.readString(destination) != text) {
            Files.writeString(destination, text)
        }
        return destination
    }

    /**
     * Independently replays the checked-in Dreaming fixture manifest. A completed result proves only
     * that canonical imported provenance, fixture structure and replay JSON agree; it is not model
     * or semantic evidence.
     */
    fun verifyDreamingFixtureReplay(packageData: EvaluationPackage): FakeReplayResult =
        try {
            val dreaming =
                packageData.datasets.singleOrNull { it.task == "DREAMING" }
                    ?: fixtureFail("package must contain exactly one DREAMING dataset")
            val imported = dreaming.samples.filter { it.provenanceKind == "IMPORTED" }
            if (imported.isEmpty() || imported.size != dreaming.importedCount) {
                fixtureFail("Dreaming imported samples do not match the manifest count")
            }
            val provenance =
                imported.map { sample ->
                    sample.source.getValue("provenance").jsonObject.also { value ->
                        if (value.getValue("kind").jsonPrimitive.content != "IMPORTED") {
                            fixtureFail("${sample.id}: replay source must be imported")
                        }
                    }
                }
            val sourcePaths = provenance.map { it.fixtureString("sourcePath", "Dreaming provenance") }.toSet()
            val sourceHashes = provenance.map { it.fixtureString("sourceSha256", "Dreaming provenance") }.toSet()
            if (sourcePaths.size != 1 || sourceHashes.size != 1) {
                fixtureFail("Dreaming imported samples must share one canonical fixture source")
            }

            val sourcePath = Path.of(sourcePaths.single()).normalize()
            if (sourcePath.isAbsolute || sourcePath.startsWith("..")) {
                fixtureFail("Dreaming fixture source must be an in-repository relative path")
            }
            val sourceFile = packageData.root.resolve(sourcePath).normalize()
            if (!sourceFile.startsWith(packageData.root) || !Files.isRegularFile(sourceFile)) {
                fixtureFail("Dreaming fixture source is missing: $sourcePath")
            }
            if (sha256(sourceFile) != sourceHashes.single()) {
                fixtureFail("Dreaming fixture source SHA-256 drift")
            }

            val fixtureManifest = sourceFile.parent.resolve("manifest.json")
            if (!Files.isRegularFile(fixtureManifest)) {
                fixtureFail("Dreaming fixture manifest is missing")
            }
            val fixtureManifestJson = parseFixtureObject(Files.readString(fixtureManifest), "Dreaming fixture manifest")
            if (fixtureManifestJson.fixtureInt("schemaVersion", "Dreaming fixture manifest") != 1) {
                fixtureFail("Dreaming fixture manifest schemaVersion must be 1")
            }
            val scenariosFile = fixtureManifestJson.fixtureString("scenariosFile", "Dreaming fixture manifest")
            val scenariosPath = fixtureManifest.parent.resolve(scenariosFile).normalize()
            if (scenariosPath != sourceFile) {
                fixtureFail("Dreaming fixture manifest scenariosFile does not match canonical provenance")
            }

            val scenarios =
                Files.readAllLines(sourceFile).mapIndexedNotNull { index, line ->
                    if (line.isBlank()) null else parseFixtureObject(line, "$sourcePath:${index + 1}")
                }
            if (scenarios.size != imported.size) {
                fixtureFail("Dreaming fixture scenario count ${scenarios.size} does not match imported sample count ${imported.size}")
            }
            val scenarioIds =
                scenarios.mapIndexed { index, scenario ->
                    scenario.fixtureString("id", "Dreaming fixture scenario[$index]")
                }
            if (scenarioIds.toSet().size != scenarioIds.size) fixtureFail("Dreaming fixture scenario IDs must be unique")
            scenarios.forEachIndexed { index, scenario ->
                scenario["expected"]?.jsonObject ?: fixtureFail("Dreaming fixture scenario[$index] is missing expected")
                val stageOutputs =
                    scenario["stageOutputs"]?.jsonArray ?: fixtureFail("Dreaming fixture scenario[$index] is missing stageOutputs")
                if (stageOutputs.isEmpty()) fixtureFail("Dreaming fixture scenario[$index] must contain stageOutputs")
                stageOutputs.forEachIndexed { stageIndex, element ->
                    val stage =
                        element as? JsonObject
                            ?: fixtureFail("Dreaming fixture scenario[$index].stageOutputs[$stageIndex] must be an object")
                    stage.fixtureString("stage", "Dreaming fixture scenario[$index].stageOutputs[$stageIndex]")
                    val content = stage.fixtureString("content", "Dreaming fixture scenario[$index].stageOutputs[$stageIndex]")
                    val failureCode = stage["failureCode"]?.jsonPrimitive?.content
                    if (failureCode.isNullOrBlank()) {
                        parseFixtureObject(content, "Dreaming fixture scenario[$index].stageOutputs[$stageIndex].content")
                    }
                }
            }
            FakeReplayResult(status = FakeReplayStatus.COMPLETED_FIXTURE_REPLAY, scenarioCount = scenarios.size)
        } catch (_: Exception) {
            FakeReplayResult(status = FakeReplayStatus.NOT_RUN, scenarioCount = 0)
        }

    private fun ratio(
        numerator: Int,
        denominator: Int,
    ): MetricValue =
        if (denominator == 0) {
            MetricValue(MetricStatus.NOT_APPLICABLE)
        } else {
            MetricValue(MetricStatus.CALCULATED, numerator.toDouble() / denominator, numerator, denominator)
        }

    private fun reciprocalRank(
        ranking: List<String>,
        relevant: Set<String>,
    ): MetricValue {
        if (relevant.isEmpty()) return MetricValue(MetricStatus.NOT_APPLICABLE)
        val rank = ranking.indexOfFirst { it in relevant }
        if (rank < 0) return MetricValue(MetricStatus.CALCULATED, 0.0, 0, 1)
        return MetricValue(MetricStatus.CALCULATED, 1.0 / (rank + 1), 1, rank + 1)
    }

    private fun protocolFailure(
        scannerStatus: SearchStageStatus,
        rerankStatus: SearchStageStatus,
        orderStatus: SearchStageStatus,
        outOfSet: Int,
        unjudged: Int,
    ): SearchMetrics {
        val failed = MetricValue(MetricStatus.PROTOCOL_FAILURE)
        val failureCount =
            listOf(scannerStatus, rerankStatus, orderStatus).count { it == SearchStageStatus.PROTOCOL_FAILURE }
        return SearchMetrics(
            failed,
            failed,
            failed,
            failed,
            scannerStatus,
            rerankStatus,
            orderStatus,
            outOfSet,
            unjudged,
            failureCount,
        )
    }

    private fun firstDuplicate(values: List<String>): String? =
        values
            .groupingBy { it }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key

    private data class RetrievalInputCoverage(
        val sampleId: String,
        val k: Int,
        val candidateCount: Int,
        val relevantJudgmentCount: Int,
        val expectedEmpty: Boolean,
    )

    private fun sha256(path: java.nio.file.Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    private fun parseFixtureObject(
        text: String,
        path: String,
    ): JsonObject =
        try {
            fixtureJson.parseToJsonElement(text).jsonObject
        } catch (failure: Exception) {
            fixtureFail("$path: invalid JSON object: ${failure.message}")
        }

    private fun JsonObject.fixtureString(
        name: String,
        path: String,
    ): String =
        try {
            getValue(name).jsonPrimitive.content.takeIf { it.isNotBlank() }
                ?: fixtureFail("$path.$name must be a non-empty string")
        } catch (failure: EvaluationDatasetException) {
            throw failure
        } catch (_: Exception) {
            fixtureFail("$path.$name must be a string")
        }

    private fun JsonObject.fixtureInt(
        name: String,
        path: String,
    ): Int =
        try {
            getValue(name).jsonPrimitive.int
        } catch (failure: EvaluationDatasetException) {
            throw failure
        } catch (_: Exception) {
            fixtureFail("$path.$name must be an integer")
        }

    private fun fixtureFail(message: String): Nothing = throw EvaluationDatasetException(message)

    private fun notApplicable(): JsonObject =
        buildJsonObject {
            put("status", "NOT_APPLICABLE")
            put("value", JsonNull)
            put("numerator", 0)
            put("denominator", 0)
        }
}

internal fun regenerateOfflineEvaluationReport(args: Array<String>) {
    if (args.singleOrNull() != "--write-offline-report") {
        System.err.println("Usage: RegenerateOfflineEvaluationReport --write-offline-report")
        exitProcess(2)
    }
    val root = DefaultEvaluationRoot.find()
    println(OfflineEvaluationHarness.writeOfflineReport(root))
}
