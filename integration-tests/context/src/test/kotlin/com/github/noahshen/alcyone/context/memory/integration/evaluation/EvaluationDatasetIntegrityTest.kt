package com.github.noahshen.alcyone.context.memory.integration.evaluation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvaluationDatasetIntegrityTest {
    private val root: Path = DefaultEvaluationRoot.find()

    @Test
    fun `canonical package is complete and provenance counts are frozen`() {
        val packageData = EvalDatasetLoader.load(root)

        assertEquals(6, packageData.datasets.size)
        assertEquals(72, packageData.samples.size)
        assertEquals(28, packageData.datasets.sumOf { it.importedCount })
        assertEquals(44, packageData.datasets.sumOf { it.newCount })
        assertEquals(
            12,
            packageData.datasets
                .map { it.samples.size }
                .distinct()
                .single(),
        )
        assertEquals(
            packageData.samples.size,
            packageData.samples
                .map { it.id }
                .toSet()
                .size,
        )
        assertEquals(
            mapOf("ext" to 12, "sc" to 12, "gov" to 12, "ret" to 12, "map" to 12, "dream" to 12),
            packageData.samples.groupingBy { it.id.substringBefore('-') }.eachCount(),
        )
    }

    @Test
    fun `actual stable dataset prefixes are accepted without renaming`() {
        val packageData = EvalDatasetLoader.load(root)
        assertEquals(
            listOf("ext-001", "sc-001", "gov-001", "ret-001", "map-001", "dream-001"),
            packageData.datasets.map { it.samples.first().id },
        )
    }

    @Test
    fun `every dataset covers critical risk empty and human constraints`() {
        val packageData = EvalDatasetLoader.load(root)
        packageData.datasets.forEach { dataset ->
            assertTrue(
                dataset.samples.all {
                    it.source
                        .getValue("review")
                        .toString()
                        .contains("APPROVED_FOR_OFFLINE")
                },
            )
            assertTrue(dataset.samples.all { it.provenanceKind in setOf("IMPORTED", "SYNTHESIZED") })
            assertTrue(
                dataset.samples.any {
                    it.oracle
                        .getValue("criticality")
                        .jsonPrimitive.content == "CRITICAL"
                },
            )
            if (dataset.task != "GOVERNANCE") {
                assertTrue(dataset.samples.any { it.oracle.getValue("expectedEmpty").toString() == "true" })
            }
        }
        val risks = packageData.samples.flatMap { it.oracle.riskTags() }.toSet()
        assertTrue(setOf("H1_FABRICATION", "H2_CRITICAL_CONFLICT_MISS", "H3_CORRECTION_REGRESSION").all { it in risks })
    }

    @Test
    fun `manifest rejects dataset hash drift`() {
        val manifest = Files.readString(root.resolve("evals/manifest-v1.json"))
        val changed =
            manifest.replace(
                Files.readString(root.resolve("evals/manifest-v1.json")).let {
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(
                            it,
                        ).jsonObject
                        .getValue("datasets")
                        .jsonArray
                        .first()
                        .jsonObject
                        .getValue("sha256")
                        .jsonPrimitive.content
                },
                "0".repeat(64),
            )
        assertTrue(changed != manifest)
        val failure =
            assertFailsWith<EvaluationDatasetException> {
                val temporary = Files.createTempDirectory("ct31-manifest-drift")
                try {
                    copyTree(root.resolve("evals"), temporary.resolve("evals"))
                    copyTree(
                        root.resolve("context/core/src/main/resources/prompts"),
                        temporary.resolve("context/core/src/main/resources/prompts"),
                    )
                    copyTree(
                        root.resolve("context/runtime/src/test/resources/dreaming-fixtures-v1"),
                        temporary.resolve("context/runtime/src/test/resources/dreaming-fixtures-v1"),
                    )
                    copyTree(
                        root.resolve("docs/context/tasks/cm4-ct21/evaluation"),
                        temporary.resolve("docs/context/tasks/cm4-ct21/evaluation"),
                    )
                    Files.writeString(temporary.resolve("evals/manifest-v1.json"), changed)
                    EvalDatasetLoader.load(temporary)
                } finally {
                    temporary.toFile().deleteRecursively()
                }
            }
        val failureMessage = failure.message.orEmpty().lowercase()
        assertTrue(failureMessage.contains("sha256") || failureMessage.contains("sha-256"), failure.message)
    }

    @Test
    fun `dataset and report JSON schemas are executable`() {
        val schema = root.resolve("evals/schema/dataset-v1.schema.json")
        Files.list(root.resolve("evals/datasets")).use { paths ->
            paths.filter { it.toString().endsWith(".jsonl") }.forEach { dataset ->
                Files.readAllLines(dataset).filter { it.isNotBlank() }.forEach { line ->
                    EvaluationJsonSchemaSubsetValidator.validateFile(schema, Json.parseToJsonElement(line))
                }
            }
        }
        val first = Json.parseToJsonElement(Files.readAllLines(root.resolve("evals/datasets/extraction.jsonl")).first()).jsonObject
        assertFailsWith<EvaluationDatasetException> {
            EvaluationJsonSchemaSubsetValidator.validateFile(schema, JsonObject(first + ("unexpected" to JsonPrimitive(true))))
        }
        assertFailsWith<EvaluationDatasetException> {
            EvaluationJsonSchemaSubsetValidator.validateFile(schema, JsonObject(first - "id"))
        }
        assertFailsWith<EvaluationDatasetException> {
            val input = JsonObject(first.getValue("input").jsonObject + ("turns" to JsonArray(emptyList())))
            EvaluationJsonSchemaSubsetValidator.validateFile(schema, JsonObject(first + ("input" to input)))
        }
        val imported = Json.parseToJsonElement(Files.readAllLines(root.resolve("evals/datasets/retrieval.jsonl")).first()).jsonObject
        assertFailsWith<EvaluationDatasetException> {
            val provenance = JsonObject(imported.getValue("provenance").jsonObject + ("sourceSampleId" to JsonNull))
            EvaluationJsonSchemaSubsetValidator.validateFile(schema, JsonObject(imported + ("provenance" to provenance)))
        }
        val report = Json.parseToJsonElement(Files.readString(root.resolve("evals/results/offline-v1.json")))
        EvaluationJsonSchemaSubsetValidator.validateFile(root.resolve("evals/schema/report-v1.schema.json"), report)
    }

    @Test
    fun `governance rejects NOOP and empty output at schema and loader layers`() {
        val packageData = EvalDatasetLoader.load(root)
        val sample = packageData.samples.single { it.id == "gov-007" }
        val schema = root.resolve("evals/schema/dataset-v1.schema.json")
        val noopOracle = JsonObject(sample.oracle + ("expectedAction" to JsonPrimitive("NOOP")))
        assertFailsWith<EvaluationDatasetException> {
            EvaluationJsonSchemaSubsetValidator.validateFile(schema, JsonObject(sample.source + ("oracle" to noopOracle)))
        }

        val dataset = packageData.datasets.single { it.id == "governance" }
        listOf(
            noopOracle to "expectedAction is invalid",
            JsonObject(sample.oracle + ("expectedEmpty" to JsonPrimitive(true))) to "decisions cannot be empty",
        ).forEach { (oracle, expectedMessage) ->
            val failure =
                assertFailsWith<EvaluationDatasetException> {
                    EvalDatasetLoader.parseSampleLines(
                        root,
                        packageData.manifest,
                        EvaluationDatasetDescriptor(
                            dataset.id,
                            dataset.task,
                            dataset.path,
                            dataset.samples.size,
                            dataset.importedCount,
                            dataset.newCount,
                            dataset.sha256,
                            dataset.route,
                        ),
                        dataset.samples.map {
                            JsonObject(it.source + ("oracle" to if (it.id == sample.id) oracle else it.oracle)).toString()
                        },
                    )
                }
            assertTrue(failure.message.orEmpty().contains(expectedMessage), failure.message)
        }
    }

    @Test
    fun `imported extraction and retrieval samples are faithful source projections`() {
        val packageData = EvalDatasetLoader.load(root)
        val imported = packageData.datasets.flatMap { it.samples }.filter { it.provenanceKind == "IMPORTED" }
        assertEquals(28, imported.size)
        assertEquals(
            28,
            imported
                .mapNotNull {
                    it.source["provenance"]!!
                        .jsonObject["sourceSampleId"]
                        ?.jsonPrimitive
                        ?.content
                }.toSet()
                .size,
        )

        val extractionSources = sourceLines("docs/context/tasks/cm2-ct12/evals/extraction.jsonl")
        imported.filter { it.task == "EXTRACTION" }.forEach { sample ->
            val source = extractionSources.single { it.getValue("id") == sample.source["provenance"]!!.jsonObject["sourceSampleId"] }
            val sourceTurns =
                source
                    .getValue("input")
                    .jsonObject
                    .getValue("turns")
                    .jsonArray
                    .map { it.jsonObject }
            val actualTurns =
                sample.input
                    .getValue("turns")
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(sourceTurns.size, actualTurns.size, sample.id)
            sourceTurns.zip(actualTurns).forEach { (expected, actual) ->
                listOf(
                    "id",
                    "timestamp",
                    "role",
                    "content",
                ).forEach { assertEquals(expected.getValue(it), actual.getValue(it), "${sample.id}.$it") }
            }
        }

        val retrievalSources = sourceLines("docs/context/tasks/cm4-ct21/evaluation/retrieval.jsonl")
        val retrieval = imported.filter { it.task == "MEMORY_SEARCH" }
        assertEquals(
            (1..10).map {
                "rs-${it.toString().padStart(3, '0')}"
            },
            retrieval.map {
                it.source["provenance"]!!
                    .jsonObject
                    .getValue("sourceSampleId")
                    .jsonPrimitive.content
            },
        )
        retrieval.forEach { sample ->
            val source = retrievalSources.single { it.getValue("id") == sample.source["provenance"]!!.jsonObject["sourceSampleId"] }
            assertEquals(source.getValue("input").jsonObject.getValue("query"), sample.input.getValue("query"), sample.id)
            assertEquals(source.getValue("input").jsonObject.getValue("candidates"), sample.input.getValue("candidates"), sample.id)
            assertEquals(source.getValue("semantic_constraints"), sample.oracle.getValue("sourceSemanticConstraints"), sample.id)
            assertEquals(source.getValue("expected_behavior"), sample.oracle.getValue("sourceExpectedBehavior"), sample.id)
            assertEquals(source.getValue("forbidden_errors"), sample.oracle.getValue("sourceForbiddenErrors"), sample.id)
            assertEquals(JsonPrimitive(3), sample.oracle.getValue("k"), sample.id)
        }
        assertEquals(
            "ALLOW_RESULTS_OR_EMPTY",
            retrieval[8]
                .oracle
                .getValue("resultMode")
                .jsonPrimitive.content,
        )
        assertEquals(
            "RETURN_EMPTY_WITHOUT_MODEL_CALL",
            retrieval[9]
                .oracle
                .getValue("emptyCandidatesBehavior")
                .jsonPrimitive.content,
        )

        val mapSources = sourceLines("docs/context/tasks/cm4-ct21/evaluation/memory-map.jsonl")
        val maps = imported.filter { it.task == "MEMORY_MAP" }
        assertEquals(
            (1..10).map {
                "mm-${it.toString().padStart(3, '0')}"
            },
            maps.map {
                it.source["provenance"]!!
                    .jsonObject
                    .getValue("sourceSampleId")
                    .jsonPrimitive.content
            },
        )
        maps.forEach { sample ->
            val source = mapSources.single { it.getValue("id") == sample.source["provenance"]!!.jsonObject["sourceSampleId"] }
            assertEquals(
                source.getValue("input").jsonObject.getValue("organized_memories"),
                sample.input.getValue("organizedMemories"),
                sample.id,
            )
            assertEquals(source.getValue("semantic_constraints"), sample.oracle.getValue("sourceSemanticConstraints"), sample.id)
            assertEquals(source.getValue("expected_behavior"), sample.oracle.getValue("sourceExpectedBehavior"), sample.id)
            if (sample.id == "map-003") {
                assertEquals(JsonArray(listOf(JsonPrimitive("把旧偏好当作当前状态"))), sample.oracle.getValue("sourceForbiddenErrors"))
                assertTrue(sample.source.toString().contains("documented correction"))
            } else {
                assertEquals(source.getValue("forbidden_errors"), sample.oracle.getValue("sourceForbiddenErrors"), sample.id)
            }
        }
        assertEquals(
            "RETURN_DETERMINISTIC_EMPTY_WITHOUT_MODEL_CALL",
            maps[6]
                .oracle
                .getValue("emptyMapBehavior")
                .jsonPrimitive.content,
        )

        val dreamSources = sourceLines("context/runtime/src/test/resources/dreaming-fixtures-v1/scenarios.jsonl")
        imported.filter { it.task == "DREAMING" }.forEach { sample ->
            val source = dreamSources.single { it.getValue("id") == sample.source["provenance"]!!.jsonObject["sourceSampleId"] }
            assertEquals(source.getValue("expected"), sample.oracle.getValue("sourceScenarioExpected"), sample.id)
            assertEquals(source.getValue("stageOutputs"), sample.oracle.getValue("sourceScenarioStageOutputs"), sample.id)
        }
    }

    private fun sourceLines(path: String) =
        Files.readAllLines(root.resolve(path)).filter { it.isNotBlank() }.map {
            kotlinx.serialization.json.Json
                .parseToJsonElement(it)
                .jsonObject
        }
}

private fun kotlinx.serialization.json.JsonObject.riskTags(): List<String> =
    (getValue("riskTags") as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }

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
