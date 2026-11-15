package com.github.noahshen.alcyone.context.memory.integration.evaluation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineEvaluationReportTest {
    private val root = DefaultEvaluationRoot.find()

    @Test
    fun `search metrics calculate recall critical recall MRR and final recall`() {
        val observation =
            SearchObservation(
                queryId = "ret-test",
                k = 2,
                candidateIds = listOf("r1", "r2", "r3"),
                judgments =
                    listOf(
                        RelevanceJudgment("r1", 3, critical = true),
                        RelevanceJudgment("r2", 2),
                        RelevanceJudgment("r3", 1),
                    ),
                scannerRanking = listOf("r1", "r3", "r2"),
                rerankInputIds = listOf("r1", "r3", "r2"),
                rerankRanking = listOf("r2", "r3", "r1"),
                expectedOrder = listOf("r2", "r3", "r1"),
                judgedIds = listOf("r1", "r2", "r3"),
            )

        val metrics = OfflineEvaluationHarness.evaluateSearch(observation)

        assertEquals(2.0 / 3.0, metrics.recallAtK.value)
        assertEquals(1.0, metrics.criticalRecallAtK.value)
        assertEquals(1.0, metrics.mrrAtK.value)
        assertEquals(2.0 / 3.0, metrics.finalRecallAtK.value)
        assertEquals(SearchStageStatus.PASS, metrics.scannerStatus)
        assertEquals(SearchStageStatus.PASS, metrics.rerankStatus)
        assertEquals(SearchStageStatus.PASS, metrics.orderStatus)
        assertEquals(0, metrics.unjudgedIdCount)
        assertEquals(0, metrics.protocolFailureCount)
    }

    @Test
    fun `legal irrelevant first result produces MRR one half rather than protocol failure`() {
        val observation =
            SearchObservation(
                queryId = "irrelevant-first",
                k = 2,
                candidateIds = listOf("n1", "r1"),
                judgments = listOf(RelevanceJudgment("r1", 1)),
                scannerRanking = listOf("n1", "r1"),
                rerankInputIds = listOf("n1", "r1"),
                rerankRanking = listOf("n1", "r1"),
                judgedIds = listOf("n1", "r1"),
            )

        val metrics = OfflineEvaluationHarness.evaluateSearch(observation)

        assertEquals(1.0, metrics.recallAtK.value)
        assertEquals(0.5, metrics.mrrAtK.value)
        assertEquals(1.0, metrics.finalRecallAtK.value)
        assertEquals(0, metrics.outOfSetIdCount)
        assertEquals(0, metrics.unjudgedIdCount)
        assertEquals(0, metrics.protocolFailureCount)
    }

    @Test
    fun `missing human judgment is a protocol failure even for an in-set ID`() {
        val observation =
            SearchObservation(
                queryId = "unjudged",
                k = 2,
                candidateIds = listOf("r1", "n1"),
                judgments = listOf(RelevanceJudgment("r1", 1)),
                scannerRanking = listOf("r1", "n1"),
                rerankInputIds = listOf("r1", "n1"),
                rerankRanking = listOf("r1", "n1"),
            )

        val metrics = OfflineEvaluationHarness.evaluateSearch(observation)

        assertEquals(MetricStatus.PROTOCOL_FAILURE, metrics.recallAtK.status)
        assertEquals(1, metrics.unjudgedIdCount)
        assertEquals(0, metrics.outOfSetIdCount)
        assertEquals(2, metrics.protocolFailureCount)
    }

    @Test
    fun `top K boundary is inclusive and K plus one is excluded`() {
        val judgments = listOf(RelevanceJudgment("r1", 1), RelevanceJudgment("r2", 1))
        val atBoundary =
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation(
                    "boundary-k",
                    1,
                    listOf("r1", "r2"),
                    judgments,
                    listOf("r1", "r2"),
                    listOf("r1", "r2"),
                    listOf("r1", "r2"),
                    judgedIds = listOf("r1", "r2"),
                ),
            )
        val sortedTail =
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation(
                    "boundary-tail",
                    1,
                    listOf("n1", "r1"),
                    listOf(RelevanceJudgment("r1", 1)),
                    listOf("n1", "r1"),
                    listOf("n1", "r1"),
                    listOf("n1", "r1"),
                    expectedOrder = listOf("n1", "r1"),
                    judgedIds = listOf("n1", "r1"),
                ),
            )

        assertEquals(0.5, atBoundary.recallAtK.value)
        assertEquals(0.0, sortedTail.recallAtK.value)
        assertEquals(0.0, sortedTail.mrrAtK.value)
    }

    @Test
    fun `rerank cannot add an ID excluded by scanner`() {
        val observation =
            SearchObservation(
                queryId = "stage-boundary",
                k = 2,
                candidateIds = listOf("r1", "r2"),
                judgments = listOf(RelevanceJudgment("r1", 1), RelevanceJudgment("r2", 1)),
                scannerRanking = listOf("r1"),
                rerankInputIds = listOf("r2"),
                rerankRanking = listOf("r2"),
                judgedIds = listOf("r1", "r2"),
            )

        val metrics = OfflineEvaluationHarness.evaluateSearch(observation)

        assertEquals(MetricStatus.PROTOCOL_FAILURE, metrics.finalRecallAtK.status)
        assertEquals(SearchStageStatus.PASS, metrics.scannerStatus)
        assertEquals(SearchStageStatus.PROTOCOL_FAILURE, metrics.rerankStatus)
        assertEquals(SearchStageStatus.NOT_APPLICABLE, metrics.orderStatus)
        assertEquals(1, metrics.protocolFailureCount)
    }

    @Test
    fun `rerank output cannot escape its recorded input`() {
        val observation =
            SearchObservation(
                queryId = "rerank-output-boundary",
                k = 2,
                candidateIds = listOf("r1", "r2"),
                judgments = listOf(RelevanceJudgment("r1", 1), RelevanceJudgment("r2", 1)),
                scannerRanking = listOf("r1"),
                rerankInputIds = listOf("r1"),
                rerankRanking = listOf("r2", "r1"),
                judgedIds = listOf("r1", "r2"),
            )

        val metrics = OfflineEvaluationHarness.evaluateSearch(observation)

        assertEquals(SearchStageStatus.PASS, metrics.scannerStatus)
        assertEquals(SearchStageStatus.PROTOCOL_FAILURE, metrics.rerankStatus)
        assertEquals(1, metrics.outOfSetIdCount)
    }

    @Test
    fun `critical first and expected order are explicit semantic dimensions`() {
        val grounded =
            SearchObservation(
                queryId = "grounded",
                k = 2,
                candidateIds = listOf("n1", "c1"),
                judgments = listOf(RelevanceJudgment("c1", 3, critical = true)),
                scannerRanking = listOf("n1", "c1"),
                rerankInputIds = listOf("n1", "c1"),
                rerankRanking = listOf("n1", "c1"),
                expectedOrder = listOf("n1", "c1"),
                judgedIds = listOf("n1", "c1"),
            )
        val ungrounded = grounded.copy(queryId = "ungrounded", expectedOrder = listOf("c1", "n1"))

        assertEquals(SearchStageStatus.PASS, OfflineEvaluationHarness.evaluateSearch(grounded).orderStatus)
        val failed = OfflineEvaluationHarness.evaluateSearch(ungrounded)
        assertEquals(0.5, failed.mrrAtK.value)
        assertEquals(SearchStageStatus.FAIL, failed.orderStatus)
        assertEquals(0, failed.protocolFailureCount)
    }

    @Test
    fun `captured empty output retains zero scores when expected order is unmet`() {
        val metrics =
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation(
                    queryId = "empty-order",
                    k = 2,
                    candidateIds = listOf("r1"),
                    judgments = listOf(RelevanceJudgment("r1", 1, critical = true)),
                    scannerRanking = emptyList(),
                    rerankInputIds = emptyList(),
                    rerankRanking = emptyList(),
                    expectedOrder = listOf("r1"),
                ),
            )

        listOf(metrics.recallAtK, metrics.criticalRecallAtK, metrics.mrrAtK, metrics.finalRecallAtK).forEach {
            assertEquals(MetricStatus.CALCULATED, it.status)
            assertEquals(0.0, it.value)
        }
        assertEquals(1, metrics.recallAtK.denominator)
        assertEquals(SearchStageStatus.FAIL, metrics.orderStatus)
        assertEquals(0, metrics.protocolFailureCount)
    }

    @Test
    fun `order mismatch does not erase legal irrelevant first MRR`() {
        val ranked = listOf("n1", "r1")
        val metrics =
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation(
                    queryId = "semantic-order",
                    k = 2,
                    candidateIds = ranked,
                    judgments = listOf(RelevanceJudgment("r1", 1, critical = true)),
                    scannerRanking = ranked,
                    rerankInputIds = ranked,
                    rerankRanking = ranked,
                    expectedOrder = listOf("r1", "n1"),
                    judgedIds = ranked,
                ),
            )

        assertEquals(1.0, metrics.recallAtK.value)
        assertEquals(1.0, metrics.criticalRecallAtK.value)
        assertEquals(0.5, metrics.mrrAtK.value)
        assertEquals(1.0, metrics.finalRecallAtK.value)
        assertEquals(SearchStageStatus.FAIL, metrics.orderStatus)
        assertEquals(0, metrics.protocolFailureCount)
    }

    @Test
    fun `only relevant result at K plus one is not recalled`() {
        val ranked = listOf("n1", "r1")

        val metrics =
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation(
                    "k-boundary-negative",
                    k = 1,
                    candidateIds = listOf("n1", "r1"),
                    judgments = listOf(RelevanceJudgment("r1", 1)),
                    scannerRanking = ranked,
                    rerankInputIds = ranked,
                    rerankRanking = ranked,
                    judgedIds = listOf("n1", "r1"),
                ),
            )

        assertEquals(0.0, metrics.recallAtK.value)
        assertEquals(0.0, metrics.mrrAtK.value)
    }

    @Test
    fun `nonempty qrels with captured empty rankings score zero rather than not applicable`() {
        val metrics =
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation(
                    queryId = "captured-empty",
                    k = 2,
                    candidateIds = listOf("r1"),
                    judgments = listOf(RelevanceJudgment("r1", 1, critical = true)),
                    scannerRanking = emptyList(),
                    rerankInputIds = emptyList(),
                    rerankRanking = emptyList(),
                ),
            )

        listOf(metrics.recallAtK, metrics.criticalRecallAtK, metrics.mrrAtK, metrics.finalRecallAtK).forEach {
            assertEquals(MetricStatus.CALCULATED, it.status)
            assertEquals(0.0, it.value)
        }
        assertEquals(1, metrics.recallAtK.denominator)
        assertEquals(0, metrics.protocolFailureCount)
    }

    @Test
    fun `zero denominators are reported as not applicable rather than zero`() {
        val observation =
            SearchObservation("empty", 3, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val metrics = OfflineEvaluationHarness.evaluateSearch(observation)

        listOf(metrics.recallAtK, metrics.criticalRecallAtK, metrics.mrrAtK, metrics.finalRecallAtK).forEach {
            assertEquals(MetricStatus.NOT_APPLICABLE, it.status)
            assertEquals(null, it.value)
            assertEquals(0, it.denominator)
        }
    }

    @Test
    fun `duplicates and fabricated IDs are protocol failures not filtered scores`() {
        val judgments = listOf(RelevanceJudgment("r1", 1))
        val duplicate =
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation(
                    "duplicate",
                    2,
                    listOf("r1"),
                    judgments,
                    listOf("r1", "r1"),
                    listOf("r1"),
                    listOf("r1"),
                ),
            )
        val outOfSet =
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation(
                    "out-of-set",
                    2,
                    listOf("r1"),
                    judgments,
                    listOf("r1"),
                    listOf("r1"),
                    listOf("r1", "fabricated"),
                ),
            )

        assertEquals(MetricStatus.PROTOCOL_FAILURE, duplicate.recallAtK.status)
        assertEquals(1, duplicate.protocolFailureCount)
        assertEquals(MetricStatus.PROTOCOL_FAILURE, outOfSet.recallAtK.status)
        assertEquals(1, outOfSet.outOfSetIdCount)
    }

    @Test
    fun `empty qrels permit only empty output and do not create ranking denominators`() {
        val empty =
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation(
                    "empty-qrels",
                    2,
                    listOf("n1"),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    judgedIds = listOf("n1"),
                ),
            )
        val fabricated =
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation(
                    "empty-qrels-output",
                    2,
                    listOf("n1"),
                    emptyList(),
                    listOf("n1"),
                    listOf("n1"),
                    listOf("n1"),
                    judgedIds = listOf("n1"),
                ),
            )

        assertEquals(MetricStatus.NOT_APPLICABLE, empty.recallAtK.status)
        assertEquals(MetricStatus.NOT_APPLICABLE, empty.mrrAtK.status)
        assertEquals(0, empty.protocolFailureCount)
        assertEquals(MetricStatus.PROTOCOL_FAILURE, fabricated.recallAtK.status)
        assertEquals(2, fabricated.protocolFailureCount)
    }

    @Test
    fun `invalid K and duplicate qrels fail closed`() {
        assertFailsWith<EvaluationDatasetException> {
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation("bad-k", 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            )
        }
        assertFailsWith<EvaluationDatasetException> {
            OfflineEvaluationHarness.evaluateSearch(
                SearchObservation(
                    "bad-qrels",
                    1,
                    listOf("r1"),
                    listOf(RelevanceJudgment("r1", 1), RelevanceJudgment("r1", 2)),
                    listOf("r1"),
                    listOf("r1"),
                    listOf("r1"),
                    judgedIds = listOf("r1"),
                ),
            )
        }
    }

    @Test
    fun `macro average excludes not-applicable samples and propagates protocol failure`() {
        val calculated = MetricValue(MetricStatus.CALCULATED, 1.0, 1, 1)
        val notApplicable = MetricValue(MetricStatus.NOT_APPLICABLE)

        assertEquals(1.0, OfflineEvaluationHarness.macro(listOf(calculated, notApplicable)).value)
        assertEquals(
            MetricStatus.PROTOCOL_FAILURE,
            OfflineEvaluationHarness.macro(listOf(calculated, MetricValue(MetricStatus.PROTOCOL_FAILURE))).status,
        )
    }

    @Test
    fun `offline report deterministically recomputes and is not semantic evidence`() {
        val packageData = EvalDatasetLoader.load(root)
        val first = OfflineEvaluationHarness.buildOfflineReport(packageData)
        val second = OfflineEvaluationHarness.buildOfflineReport(packageData)
        val checkedIn = Json.parseToJsonElement(Files.readString(root.resolve("evals/results/offline-v1.json"))).jsonObject

        assertEquals(first, second)
        assertEquals(first, checkedIn)
        assertFalse(
            first
                .getValue("semanticQualityEvaluated")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals(
            "NOT_RUN",
            first
                .getValue("modelRun")
                .jsonObject
                .getValue("status")
                .jsonPrimitive.content,
        )
        val replay = first.getValue("fakeReplay").jsonObject
        assertEquals("COMPLETED_FIXTURE_REPLAY", replay.getValue("status").jsonPrimitive.content)
        assertEquals(
            4,
            replay
                .getValue("scenarioCount")
                .jsonPrimitive.content
                .toInt(),
        )
        assertFalse(
            replay
                .getValue("semanticEvidence")
                .jsonPrimitive.content
                .toBoolean(),
        )
        val retrievalCoverage =
            first
                .getValue("inputCoverage")
                .jsonObject
                .getValue("retrieval")
                .jsonObject
        assertEquals(
            12,
            retrievalCoverage
                .getValue("sampleCount")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            9,
            retrievalCoverage
                .getValue("samplesWithRelevantJudgments")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            3,
            retrievalCoverage
                .getValue("samplesWithoutRelevantJudgments")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            21,
            retrievalCoverage
                .getValue("totalCandidateCount")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals("NOT_APPLICABLE_NO_CAPTURED_OUTPUT", retrievalCoverage.getValue("semanticMetricsStatus").jsonPrimitive.content)
        assertEquals(12, retrievalCoverage.getValue("samples").jsonArray.size)
        assertTrue(first.toString().contains("\"criticalErrors\":[]"))
        assertTrue(!Regex("20[0-9]{2}-[0-9]{2}-[0-9]{2}T").containsMatchIn(first.toString()))
    }

    @Test
    fun `fake replay is NOT_RUN when the fixture manifest is missing`() {
        withCopiedEvaluationRoot { temporary ->
            val packageData = EvalDatasetLoader.load(temporary)
            Files.delete(temporary.resolve("$FIXTURE_ROOT/manifest.json"))

            assertReplayNotRun(OfflineEvaluationHarness.buildOfflineReport(packageData))
        }
    }

    @Test
    fun `fake replay is NOT_RUN when canonical scenario source SHA drifts`() {
        withCopiedEvaluationRoot { temporary ->
            val packageData = EvalDatasetLoader.load(temporary)
            val scenarios = temporary.resolve("$FIXTURE_ROOT/scenarios.jsonl")
            Files.writeString(scenarios, Files.readString(scenarios) + "\n")

            assertReplayNotRun(OfflineEvaluationHarness.buildOfflineReport(packageData))
        }
    }

    @Test
    fun `fake replay is NOT_RUN when a successful stage output is not a JSON object`() {
        withCopiedEvaluationRoot { temporary ->
            val packageData = EvalDatasetLoader.load(temporary)
            val scenariosPath = temporary.resolve("$FIXTURE_ROOT/scenarios.jsonl")
            val scenarios = Files.readAllLines(scenariosPath).toMutableList()
            val first = Json.parseToJsonElement(scenarios.first()).jsonObject
            val stageOutputs = first.getValue("stageOutputs").jsonArray
            val firstStage = stageOutputs.first().jsonObject
            val malformedStage = JsonObject(firstStage + ("content" to JsonPrimitive("{not-json")))
            val malformedScenario = JsonObject(first + ("stageOutputs" to JsonArray(listOf(malformedStage) + stageOutputs.drop(1))))
            scenarios[0] = malformedScenario.toString()
            Files.writeString(scenariosPath, scenarios.joinToString("\n", postfix = "\n"))

            val changedPackage = withDreamingSourceSha(packageData, sha256(scenariosPath))
            assertReplayNotRun(OfflineEvaluationHarness.buildOfflineReport(changedPackage))
        }
    }

    private fun assertReplayNotRun(report: JsonObject) {
        val replay = report.getValue("fakeReplay").jsonObject
        assertEquals("NOT_RUN", replay.getValue("status").jsonPrimitive.content)
        assertEquals(
            0,
            replay
                .getValue("scenarioCount")
                .jsonPrimitive.content
                .toInt(),
        )
        assertFalse(
            replay
                .getValue("semanticEvidence")
                .jsonPrimitive.content
                .toBoolean(),
        )
    }

    private fun withDreamingSourceSha(
        packageData: EvaluationPackage,
        sourceSha256: String,
    ): EvaluationPackage =
        packageData.copy(
            datasets =
                packageData.datasets.map { dataset ->
                    if (dataset.task != "DREAMING") {
                        dataset
                    } else {
                        dataset.copy(
                            samples =
                                dataset.samples.map { sample ->
                                    if (sample.provenanceKind != "IMPORTED") {
                                        sample
                                    } else {
                                        val provenance = sample.source.getValue("provenance").jsonObject
                                        val changedProvenance = JsonObject(provenance + ("sourceSha256" to JsonPrimitive(sourceSha256)))
                                        sample.copy(source = JsonObject(sample.source + ("provenance" to changedProvenance)))
                                    }
                                },
                        )
                    }
                },
        )

    private fun sha256(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    private fun withCopiedEvaluationRoot(block: (Path) -> Unit) {
        val temporary = Files.createTempDirectory("ct31-report-replay")
        try {
            copyTree(root.resolve("evals"), temporary.resolve("evals"))
            copyTree(root.resolve("context/core/src/main/resources/prompts"), temporary.resolve("context/core/src/main/resources/prompts"))
            copyTree(root.resolve(FIXTURE_ROOT), temporary.resolve(FIXTURE_ROOT))
            copyTree(root.resolve("docs/context/tasks/cm2-ct12/evals"), temporary.resolve("docs/context/tasks/cm2-ct12/evals"))
            copyTree(root.resolve("docs/context/tasks/cm4-ct21/evaluation"), temporary.resolve("docs/context/tasks/cm4-ct21/evaluation"))
            block(temporary)
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    private fun copyTree(
        source: Path,
        target: Path,
    ) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private companion object {
        const val FIXTURE_ROOT = "context/runtime/src/test/resources/dreaming-fixtures-v1"

        @JvmStatic
        fun main(args: Array<String>) = regenerateOfflineEvaluationReport(args)
    }
}
