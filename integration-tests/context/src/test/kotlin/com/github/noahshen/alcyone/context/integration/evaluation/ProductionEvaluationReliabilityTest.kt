@file:Suppress("ktlint:standard:max-line-length")

package com.github.noahshen.alcyone.context.integration.evaluation

import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenUsage
import com.github.noahshen.alcyone.context.memory.integration.evaluation.EvaluationJsonSchemaSubsetValidator
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.EvaluationCallState
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.ProductionRecordingClient
import com.github.noahshen.alcyone.context.memory.runtime.evaluation.ProductionSearchExecutor
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionEvaluationReliabilityTest {
    private val root =
        com.github.noahshen.alcyone.context.memory.integration.evaluation.DefaultEvaluationRoot
            .find()

    @Test fun `search observation captures scanner after real service call`() =
        runBlocking {
            val sample = loadEvalSamples(root).first { it.id == "ret-001" }
            val candidates = sample.input.toOrganizedMemories("candidates")
            val client =
                ScriptedClient(
                    """{"candidate_ids":["${candidates[0].id}","${candidates[1].id}"]}""",
                    """{"memory_ids":["${candidates[1].id}"]}""",
                )
            val path = Files.createTempDirectory("ct31-search-").resolve("context.db")
            ContextDatabaseInitializer.initialize(path)
            val executor = ContextDatabaseFactory.open(path)
            try {
                val database = ContextDatabaseImpl(executor)
                database.write { tx -> candidates.forEach { check(tx.organizedMemories.insertNew(it) == 1) } }
                val result = ProductionSearchExecutor(database, client, testConfig()).search(sample.input.toQuery(), 1)
                assertEquals(listOf(candidates[0].id, candidates[1].id), result.scannerIds)
                assertEquals(listOf(candidates[1].id), result.ranked.map { it.id })
                assertEquals(listOf("MEMORY_SEARCH", "MEMORY_SEARCH"), client.tasks)
            } finally {
                executor.close()
            }
        }

    @Test fun `dreaming preserves canonical ids pending sources and evidence links`() =
        runBlocking {
            val sample = loadEvalSamples(root).first { it.id == "dream-001" }
            val raw = sample.input.toRawMemories().single()
            val organized =
                sample.input
                    .toOrganizedMemories()
                    .single()
                    .copy(rawMemoryIds = listOf(raw.id))
            val input = sample.json.obj("input").toMutableMap()
            input["organizedMemories"] =
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.buildJsonObject {
                            organized.id.let { put("id", kotlinx.serialization.json.JsonPrimitive(it)) }
                            put("content", kotlinx.serialization.json.JsonPrimitive(organized.content))
                            put(
                                "domains",
                                kotlinx.serialization.json.JsonArray(
                                    organized.domains.map { kotlinx.serialization.json.JsonPrimitive(it) },
                                ),
                            )
                            put(
                                "rawMemoryIds",
                                kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(raw.id))),
                            )
                        },
                    ),
                )
            val linkedSample =
                EvalSample(
                    sample.task,
                    kotlinx.serialization.json.JsonObject(
                        sample.json.toMutableMap().apply {
                            put("input", kotlinx.serialization.json.JsonObject(input))
                        },
                    ),
                )
            val calls =
                linkedSample.oracle
                    .array("sourceScenarioStageOutputs")
                    .map { it.obj().string("content") }
                    .toMutableList()
            while (calls.size < 7) calls += """{"candidate_ids":[]}"""
            val client = ScriptedClient(*calls.toTypedArray())
            val directory = Files.createTempDirectory("ct31-dream-")
            val observation =
                ProductionDreamingEvalRunner().run(linkedSample, client, testConfig(), "test-model", directory)
            assertEquals(raw.id, observation.inputEvidenceLinks.keys.single())
            assertEquals(setOf(organized.id), observation.inputEvidenceLinks[raw.id])
            assertTrue(observation.evidenceLinks.isEmpty())
            assertEquals(1, observation.inputPendingCount)
            assertEquals(1 + observation.rawMemories.size, observation.pendingCount)
            val executor = ContextDatabaseFactory.open(directory.resolve("context.db"))
            val db = ContextDatabaseImpl(executor)
            try {
                val stored = db.read { it.rawMemories.findById(raw.id) }
                assertEquals(raw.sources, stored?.sources)
            } finally {
                executor.close()
            }
        }

    @Test fun `malformed response preserves successful production call`() =
        runBlocking {
            val sample = loadEvalSamples(root).first { it.task == "MEMORY_SEARCH" }
            val client = ScriptedClient("not-json")
            val directory = Files.createTempDirectory("ct31-failure-")
            val result = ProductionEvalRunner(client, testConfig(), "test-model").run(sample, directory)
            assertEquals("PROTOCOL_FAILURE", result.status)
            assertEquals(1, result.calls.size)
            val evidence = requireNotNull(result.calls.single().rawEvidence)
            val raw = Json.parseToJsonElement(Files.readString(directory.resolve(evidence.path))).jsonObject
            // The transport call succeeded and the protocol failure happened afterwards in the adapter.
            assertEquals("SUCCEEDED", raw.getValue("state").jsonPrimitive.content)
            assertEquals("not-json", raw.getValue("content").jsonPrimitive.content)
            assertTrue(raw.getValue("exchanges").jsonArray.isEmpty())
            assertEquals("SUCCEEDED", evidence.state)
            assertEquals(
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(Files.readAllBytes(directory.resolve(evidence.path)))
                    .joinToString("") { "%02x".format(it) },
                evidence.sha256,
            )
            // The machine-readable observation keeps a reference only; the raw body stays in the hashed artifact.
            assertFalse(result.json().toString().contains("not-json"))
            assertTrue(result.json().toString().contains("outputRedacted"))
        }

    @Test fun `empty dreaming reports persisted input facts and empty selection`() =
        runBlocking {
            val original = loadEvalSamples(root).first { it.task == "DREAMING" }
            val sample =
                EvalSample(
                    original.task,
                    kotlinx.serialization.json.JsonObject(
                        original.json.toMutableMap().apply {
                            put(
                                "input",
                                kotlinx.serialization.json.JsonObject(
                                    original.input.toMutableMap().apply {
                                        put("turnIds", kotlinx.serialization.json.JsonArray(emptyList()))
                                        put("turns", kotlinx.serialization.json.JsonArray(emptyList()))
                                    },
                                ),
                            )
                        },
                    ),
                )
            val observation =
                ProductionDreamingEvalRunner().run(
                    sample,
                    ScriptedClient(),
                    testConfig(),
                    "test-model",
                    Files.createTempDirectory("ct31-empty-dream-"),
                )
            assertEquals(sample.input.toRawMemories().size, observation.inputPendingCount)
            assertEquals(sample.input.toRawMemories().size, observation.pendingCount)
            assertTrue(observation.rawMemories.isEmpty())
            assertTrue(observation.topics.isEmpty())
        }

    @Test fun `cancelled production call is recorded and propagates`() =
        runBlocking {
            val recorder = ProductionRecordingClient(CancellingClient())
            val request =
                LlmRequest(
                    task = LlmTask.MEMORY_SEARCH,
                    messages =
                        listOf(
                            com.github.noahshen.alcyone.context.memory.core.llm
                                .LlmMessage("user", "hi"),
                        ),
                    responseFormat = LlmResponseFormat.PROMPT_ONLY,
                )
            assertFailsWith<CancellationException> { recorder.complete(request) }
            val call = recorder.logicalCalls().single()
            assertEquals(EvaluationCallState.CANCELLED, call.state)
            // A scripted client has no Ktor transport, so the terminal record must flag missing raw evidence.
            assertTrue(call.rawEvidenceMissing)
        }

    @Test fun `judge failure keeps received body outside result metadata`() =
        runBlocking {
            val sample = loadEvalSamples(root).first { it.id == "ret-001" }
            val judge = SemanticJudge(ScriptedClient("not-json"), sample)
            assertFailsWith<Exception> { judge.judge("selection", "question", "actual", "expected") }
            val call = judge.calls.single()
            assertEquals(EvaluationCallState.FAILED, call.state)
            assertEquals("not-json", call.rawResponse)
            val directory = Files.createTempDirectory("ct31-judge-failure-")
            val reference = writeJudgeArtifacts(directory, judge.calls).single()
            val raw =
                Json
                    .parseToJsonElement(
                        Files.readString(
                            directory.resolve(
                                reference
                                    .getValue("rawEvidence")
                                    .jsonObject
                                    .getValue("path")
                                    .jsonPrimitive.content,
                            ),
                        ),
                    ).jsonObject
            assertEquals("not-json", raw.getValue("rawResponse").jsonPrimitive.content)
            assertEquals("FAILED", raw.getValue("state").jsonPrimitive.content)
            assertFalse(reference.toString().contains("not-json"))
            assertEquals(
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        Files.readAllBytes(
                            directory.resolve(
                                reference
                                    .getValue("rawEvidence")
                                    .jsonObject
                                    .getValue("path")
                                    .jsonPrimitive.content,
                            ),
                        ),
                    ).joinToString("") { "%02x".format(it) },
                reference
                    .getValue("rawEvidence")
                    .jsonObject
                    .getValue("sha256")
                    .jsonPrimitive.content,
            )
        }

    @Test fun `judge cancellation is recorded and propagates`() =
        runBlocking {
            val sample = loadEvalSamples(root).first { it.id == "ret-001" }
            val judge = SemanticJudge(CancellingClient(), sample)
            assertFailsWith<CancellationException> { judge.judge("selection", "question", "actual", "expected") }
            assertEquals(EvaluationCallState.CANCELLED, judge.calls.single().state)
        }

    @Test fun `governance item failure surfaces as review required end to end`() =
        runBlocking {
            val sample = loadEvalSamples(root).first { it.task == "GOVERNANCE" }
            val directory = Files.createTempDirectory("ct31-gov-failure-")
            val result =
                ProductionEvalRunner(FailingScannerClient(), testConfig(), "test-model")
                    .run(sample, directory)
            // The production worker swallows the item failure; the adapter must surface it instead of hiding it.
            assertEquals("REVIEW_REQUIRED", result.status)
            val review = result.checks.single { it.id == "organization_item_failed" }
            assertEquals(Verdict.REVIEW_REQUIRED, review.verdict)
            assertTrue(review.evidence.contains("associated=[]"), review.evidence)
            assertFalse(review.evidence.contains("ct31-governance-item-key"), "evidence must not leak secrets")
            // The failed logical call is still recorded with its classifiable error code.
            val failed = result.calls.single()
            val raw =
                Json
                    .parseToJsonElement(Files.readString(directory.resolve(requireNotNull(failed.rawEvidence).path)))
                    .jsonObject
            assertEquals("FAILED", raw.getValue("state").jsonPrimitive.content)
            assertTrue(failed.json().toString().contains("outputRedacted"))
        }

    @Test fun `raw evidence writer failure never replaces the production result`() =
        runBlocking {
            val sample = loadEvalSamples(root).first { it.id == "ret-001" }
            val candidates = sample.input.toOrganizedMemories("candidates")
            // A regular file where the raw directory belongs makes every evidence write fail for real.
            val directory = Files.createTempDirectory("ct31-raw-writer-")
            Files.writeString(directory.resolve("raw"), "not a directory")
            val result =
                ProductionEvalRunner(
                    ScriptedClient(
                        """{"candidate_ids":["${candidates[0].id}","${candidates[1].id}"]}""",
                        """{"memory_ids":["${candidates[1].id}"]}""",
                    ),
                    testConfig(),
                    "test-model",
                ).run(sample, directory)
            // The production calls succeeded and must stay reported as such, with an explicit evidence failure.
            assertEquals(2, result.calls.size)
            assertTrue(result.calls.all { it.rawEvidence == null })
            assertNull(result.error)
            val failure = result.checks.single { it.id == "raw_evidence_write_failed" }
            assertEquals(Verdict.REVIEW_REQUIRED, failure.verdict)
            assertTrue(failure.evidence.contains("production result preserved"), failure.evidence)
            assertEquals("not a directory", Files.readString(directory.resolve("raw")))
        }

    @Test fun `run manifest hashes bytes and changes after tamper`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-run-manifest-")
        Files.createDirectories(directory.resolve(sample.id))
        writeJson(directory.resolve(sample.id).resolve("result.json"), SampleResult(sample).json())
        writeReport(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            null,
            listOf(SampleResult(sample)),
            emptyList(),
            1,
            true,
            emptySet(),
            null,
            null,
            null,
            true,
        )
        writeRunManifest(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            listOf(SampleResult(sample)),
            emptyList(),
            1,
            null,
            null,
            null,
            Instant.now(),
            true,
        )
        val first = Json.parseToJsonElement(Files.readString(directory.resolve("run.json"))).jsonObject
        val firstReportSha = first.getValue("reportSha256").jsonPrimitive.content
        assertTrue(Files.exists(directory.resolve("report.md")))
        Files.writeString(directory.resolve("report.json"), Files.readString(directory.resolve("report.json")) + " ")
        writeRunManifest(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            listOf(SampleResult(sample)),
            emptyList(),
            1,
            null,
            null,
            null,
            Instant.now(),
            true,
        )
        val second = Json.parseToJsonElement(Files.readString(directory.resolve("run.json"))).jsonObject
        assertNotEquals(firstReportSha, second.getValue("reportSha256").jsonPrimitive.content)
    }

    @Test fun `cancelled run keeps partial manifest and partial report`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-partial-")
        val result = SampleResult(sample, error = "CANCELLED")
        writeReport(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            null,
            listOf(result),
            emptyList(),
            3,
            true,
            emptySet(),
            null,
            null,
            null,
            false,
        )
        writeRunManifest(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            listOf(result),
            emptyList(),
            3,
            null,
            null,
            null,
            Instant.now(),
            false,
        )
        val run = Json.parseToJsonElement(Files.readString(directory.resolve("run.json"))).jsonObject
        assertFalse(
            run
                .getValue("complete")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals(
            3,
            run
                .getValue("selectedSamples")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            1,
            run
                .getValue("sampleCount")
                .jsonPrimitive.content
                .toInt(),
        )
        assertTrue(Files.readString(directory.resolve("report.md")).contains("1/3 (PARTIAL)"))
        val report = Json.parseToJsonElement(Files.readString(directory.resolve("report.json"))).jsonObject
        assertFalse(
            report
                .getValue("complete")
                .jsonPrimitive.content
                .toBoolean(),
        )
    }

    @Test fun `live run refuses a non empty output directory`() {
        val directory = Files.createTempDirectory("ct31-nonempty-")
        requireEmptyOutputDirectory(directory)
        Files.writeString(directory.resolve("report.json"), "{}")
        assertFailsWith<IllegalArgumentException> { requireEmptyOutputDirectory(directory) }
        assertEquals("{}", Files.readString(directory.resolve("report.json")))
    }

    @Test fun `map navigation accepts either style and rejects empty output`() {
        assertTrue(
            mapContractChecks(listOf("testing"), "Overview", "see #mem-1 and @testing", listOf("testing")).all {
                it.verdict ==
                    Verdict.PASS
            },
        )
        assertTrue(mapContractChecks(listOf("testing"), "Overview", "see mem-1", listOf("testing")).all { it.verdict == Verdict.PASS })
        assertTrue(mapContractChecks(listOf("testing"), " ", " ", listOf("testing")).any { it.verdict == Verdict.FAIL })
    }

    @Test fun `dreaming window ignores an unselected later turn for checkpoint`() =
        runBlocking {
            val original = loadEvalSamples(root).first { it.id == "dream-001" }
            val extraTurn =
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(3))
                    put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                    put("content", kotlinx.serialization.json.JsonPrimitive("Unselected later turn."))
                }
            val input = original.input.toMutableMap()
            input["turns"] =
                kotlinx.serialization.json.JsonArray(
                    original.input.getValue("turns").jsonArray + extraTurn,
                )
            val sample =
                EvalSample(
                    original.task,
                    kotlinx.serialization.json.JsonObject(
                        original.json.toMutableMap().apply { put("input", kotlinx.serialization.json.JsonObject(input)) },
                    ),
                )
            val calls =
                sample.oracle
                    .array("sourceScenarioStageOutputs")
                    .map { it.obj().string("content") }
                    .toMutableList()
            while (calls.size < 7) calls += """{"candidate_ids":[]}"""
            val observation =
                ProductionDreamingEvalRunner().run(
                    sample,
                    ScriptedClient(*calls.toTypedArray()),
                    testConfig(),
                    "test-model",
                    Files.createTempDirectory("ct31-window-"),
                )
            assertEquals(2, observation.conversationTurnCount)
            assertEquals(2L, observation.persistedCheckpoint)
            assertTrue(observation.commitCompleted)
            assertEquals(3L, observation.maxPersistedTurnId)
        }

    @Test fun `zero insight dreaming is a pass when oracles expect empty`() {
        val sample = loadEvalSamples(root).first { it.id == "dream-001" }
        val emptySample =
            EvalSample(
                sample.task,
                kotlinx.serialization.json.JsonObject(
                    sample.json.toMutableMap().apply {
                        put(
                            "oracle",
                            kotlinx.serialization.json.JsonObject(
                                sample.oracle.toMutableMap().apply {
                                    put("expectedEmpty", kotlinx.serialization.json.JsonPrimitive(true))
                                },
                            ),
                        )
                    },
                ),
            )
        val observation =
            DreamingObservation(
                rawMemories = emptyList(),
                commitCompleted = true,
                persistedCheckpoint = 2,
                pendingCount = sample.input.toRawMemories().size,
                inputPendingCount = sample.input.toRawMemories().size,
                conversationTurnCount = 2,
                topics = emptyList(),
                insights = emptyList(),
                evidenceLinks = emptyMap(),
                inputEvidenceLinks = emptyMap(),
                calls = emptyList(),
                maxPersistedTurnId = 2,
            )
        val result = evaluateDreaming(emptySample, observation, "test-model")
        assertEquals(Verdict.PASS, result.checks.single { it.id == "insight_persisted" }.verdict)
        assertEquals(Verdict.PASS, result.checks.single { it.id == "insight_sources_persisted" }.verdict)
    }

    @Test fun `report has unique tasks independent judge count and real manifest hash`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-report-")
        writeReport(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            null,
            listOf(
                SampleResult(
                    sample,
                ),
                SampleResult(
                    sample.copy(
                        json =
                            kotlinx.serialization.json.JsonObject(
                                sample.json.toMutableMap().apply {
                                    put("id", kotlinx.serialization.json.JsonPrimitive("other"))
                                },
                            ),
                    ),
                ),
            ),
            listOf(
                JudgeCallRecord(
                    callId = "judge-1",
                    sampleId = sample.id,
                    task = sample.task,
                    judgment = "test",
                    input = "input",
                    rawResponse = "response",
                    parsedResponse = kotlinx.serialization.json.buildJsonObject {},
                    elapsedMs = 1,
                ),
            ),
            2,
            true,
            emptySet(),
            null,
            null,
            null,
            true,
        )
        val report = Json.parseToJsonElement(Files.readString(directory.resolve("report.json"))).jsonObject
        val summary = report.getValue("summary").jsonObject
        assertEquals(listOf(sample.task), summary.getValue("tasks").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            1,
            summary
                .getValue("judgeCalls")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            0,
            summary
                .getValue("modelCalls")
                .jsonPrimitive.content
                .toInt(),
        )
        assertFalse(
            summary
                .getValue("judgeTokenUsageRecorded")
                .jsonPrimitive.content
                .toBoolean(),
        )
        val expectedHash =
            java.security.MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(Files.readAllBytes(root.resolve("evals/manifest-v1.json")))
                .joinToString("") {
                    "%02x".format(it)
                }
        assertEquals(expectedHash, report.getValue("sourceManifestSha256").jsonPrimitive.content)
    }

    // ---------------------------------------------------------------- R2-1 dreaming failure evidence

    @Test fun `dreaming malformed response carries failed calls into the sample result`() =
        runBlocking {
            val sample = loadEvalSamples(root).first { it.id == "dream-001" }
            val directory = Files.createTempDirectory("ct31-dream-malformed-")
            val result = evaluateProductionDreaming(sample, ScriptedClient("not-json"), testConfig(), "test-model", directory)
            // The stage failure must not erase the logical call that produced the raw evidence.
            assertEquals(1, result.calls.size)
            val call = result.calls.single()
            assertEquals("PROTOCOL_FAILURE", result.status)
            // The LlmClient call itself succeeded; the malformed body failed later in the stage parser. The raw
            // artifact must therefore keep the received body and say SUCCEEDED, exactly as for Search/Scanner.
            assertEquals("SUCCEEDED", requireNotNull(call.rawEvidence).state)
            assertEquals(1, call.attempts)
            assertTrue(Files.isRegularFile(directory.resolve(requireNotNull(call.rawEvidence).path)))
            assertEquals(
                "not-json",
                Json
                    .parseToJsonElement(Files.readString(directory.resolve(call.rawEvidence!!.path)))
                    .jsonObject
                    .getValue("content")
                    .jsonPrimitive
                    .content,
            )
            // The raw artifact really exists on disk with a matching byte digest, not just a path string.
            assertEquals(
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(Files.readAllBytes(directory.resolve(call.rawEvidence!!.path)))
                    .joinToString("") { "%02x".format(it) },
                call.rawEvidence!!.sha256,
            )
        }

    @Test fun `dreaming transport failure carries failed calls and safe error`() =
        runBlocking {
            val sample = loadEvalSamples(root).first { it.id == "dream-001" }
            val directory = Files.createTempDirectory("ct31-dream-transport-")
            val result =
                evaluateProductionDreaming(
                    sample,
                    UnavailableClient("ct31-dream-transport-key"),
                    testConfig(),
                    "test-model",
                    directory,
                )
            assertEquals(1, result.calls.size)
            assertEquals("FAILED", requireNotNull(result.calls.single().rawEvidence).state)
            assertEquals("PROTOCOL_FAILURE", result.status)
            assertTrue(requireNotNull(result.error).contains("PROVIDER_UNAVAILABLE"), requireNotNull(result.error))
            assertFalse(result.json().toString().contains("ct31-dream-transport-key"))
        }

    // ---------------------------------------------------------------- R2-2 CLI cancellation chain

    @Test fun `cancelled sample keeps already captured calls in the partial report`() =
        runBlocking {
            val sample = loadEvalSamples(root).first { it.task == "MEMORY_SEARCH" }
            val candidates = sample.input.toOrganizedMemories("candidates")
            val directory = Files.createTempDirectory("ct31-cli-cancel-")
            val sampleDirectory = directory.resolve(sample.id)
            Files.createDirectories(sampleDirectory)
            // First production call succeeds, the second one cancels while in flight.
            val client =
                ScriptedThenCancellingClient(
                    """{"candidate_ids":["${candidates[0].id}","${candidates[1].id}"]}""",
                )
            val failure =
                assertFailsWith<EvaluationStageCancelled> {
                    ProductionEvalRunner(client, testConfig(), "test-model").run(sample, sampleDirectory)
                }
            assertEquals(2, failure.calls.size)
            assertEquals("SUCCEEDED", failure.calls[0].rawEvidence?.state)
            assertEquals("CANCELLED", failure.calls[1].rawEvidence?.state)
            // Drive the real cancellation branch of the CLI (stage failure -> terminal sample -> artifacts)
            // instead of hand-building a SampleResult, so the chain itself is what is under test.
            val result = cancelledSampleResult(sample, failure, SampleResult(sample))
            val judgeCall =
                JudgeCallRecord(
                    callId = "cancelled-judge-call",
                    sampleId = sample.id,
                    task = sample.task,
                    judgment = "cancelled",
                    input = "input",
                    rawResponse = "response",
                    parsedResponse = kotlinx.serialization.json.buildJsonObject {},
                    elapsedMs = 1,
                    state = EvaluationCallState.CANCELLED,
                )
            val identity = EvaluationIdentity("test-model", "profile-test", LlmResponseFormat.JSON_SCHEMA.name, null)
            val selectedSampleIds = listOf(sample.id, "not-executed-sample")
            writeCancelledArtifacts(
                sampleDirectory,
                directory,
                root,
                identity,
                result,
                listOf(judgeCall),
                emptyList(),
                emptyList(),
                2,
                true,
                emptySet(),
                null,
                null,
                null,
                Instant.now(),
                selectedSampleIds = selectedSampleIds,
            )
            // The outer finally follows the cancellation branch with the stale pre-cancellation lists. The pinned
            // terminal snapshot must keep both the cancelled sample and its judge call in the final artifacts.
            writeTerminalArtifacts(
                directory,
                root,
                identity,
                completed = emptyList(),
                judgeRecords = emptyList(),
                terminalSnapshot = EvaluationTerminalSnapshot(listOf(result), listOf(judgeCall)),
                selected = 2,
                production = true,
                offlineSamples = emptySet(),
                task = null,
                sampleId = null,
                limit = null,
                startedAt = Instant.now(),
                finished = false,
                selectedSampleIds = selectedSampleIds,
            )
            val written = Json.parseToJsonElement(Files.readString(sampleDirectory.resolve("result.json"))).jsonObject
            assertEquals(2, written.getValue("calls").jsonArray.size)
            val judge = Json.parseToJsonElement(Files.readString(sampleDirectory.resolve("judge.json"))).jsonObject
            assertEquals(1, judge.getValue("calls").jsonArray.size)
            val run = Json.parseToJsonElement(Files.readString(directory.resolve("run.json"))).jsonObject
            assertFalse(
                run
                    .getValue("complete")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertEquals(
                1,
                run
                    .getValue("sampleCount")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(
                1,
                run
                    .getValue("judgeLogicalCalls")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(1, run.getValue("samples").jsonArray.size)
            val report = Json.parseToJsonElement(Files.readString(directory.resolve("report.json"))).jsonObject
            assertEquals(1, report.getValue("results").jsonArray.size)
            assertEquals(
                2,
                report
                    .getValue("results")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("calls")
                    .jsonArray
                    .size,
            )
            assertEquals(
                1,
                report
                    .getValue("rawEvidence")
                    .jsonObject
                    .getValue("judgeLogicalCalls")
                    .jsonPrimitive
                    .content
                    .toInt(),
            )
            assertEquals(
                1,
                report
                    .getValue("rawEvidence")
                    .jsonObject
                    .getValue("productionCancelled")
                    .jsonPrimitive.content
                    .toInt(),
            )
            // The partial report must name exactly what is still missing instead of only saying "incomplete".
            assertFalse(
                report
                    .getValue("complete")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertTrue(
                report
                    .getValue("completeBlockedBy")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .any { it.contains("UNRUN_SAMPLE_IDS") && it.contains("not-executed-sample") },
                report.getValue("completeBlockedBy").toString(),
            )
            assertTrue(Files.readString(directory.resolve("report.md")).contains("PARTIAL"))

            // The outer finally receives stale empty lists. It must reuse the pinned cancellation snapshot rather
            // than overwriting the valid partial artifacts written above with an empty run.
            val terminalSnapshot = EvaluationTerminalSnapshot(results = listOf(result), judgeCalls = emptyList())
            val finished = evaluationRunFinished(terminalSnapshot, completedSamples = 0, selectedSamples = 2)
            assertFalse(finished)
            writeTerminalArtifacts(
                directory,
                root,
                identity,
                completed = emptyList(),
                judgeRecords = emptyList(),
                terminalSnapshot = terminalSnapshot,
                selected = 2,
                production = true,
                offlineSamples = emptySet(),
                task = null,
                sampleId = null,
                limit = null,
                startedAt = Instant.now(),
                finished = finished,
                selectedSampleIds = selectedSampleIds,
            )
            val finalWritten = Json.parseToJsonElement(Files.readString(sampleDirectory.resolve("result.json"))).jsonObject
            assertEquals(2, finalWritten.getValue("calls").jsonArray.size)
            val finalRun = Json.parseToJsonElement(Files.readString(directory.resolve("run.json"))).jsonObject
            assertFalse(
                finalRun
                    .getValue("complete")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertEquals(
                1,
                finalRun
                    .getValue("sampleCount")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertTrue(
                finalRun
                    .getValue("completeBlockedBy")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .any { it.contains("UNRUN_SAMPLE_IDS") && it.contains("not-executed-sample") },
                finalRun.getValue("completeBlockedBy").toString(),
            )
            val finalReport = Json.parseToJsonElement(Files.readString(directory.resolve("report.json"))).jsonObject
            assertFalse(
                finalReport
                    .getValue("complete")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertEquals(
                1,
                finalReport
                    .getValue("sampleCount")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(
                1,
                finalReport
                    .getValue("rawEvidence")
                    .jsonObject
                    .getValue("productionCancelled")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(
                2,
                finalReport
                    .getValue("results")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("calls")
                    .jsonArray.size,
            )
            assertTrue(
                finalReport
                    .getValue("completeBlockedBy")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .any { it.contains("UNRUN_SAMPLE_IDS") && it.contains("not-executed-sample") },
                finalReport.getValue("completeBlockedBy").toString(),
            )
        }

    @Test fun `cancellation on final selected sample never marks run finished`() {
        val sample = loadEvalSamples(root).first()
        val terminalSnapshot = EvaluationTerminalSnapshot(results = listOf(SampleResult(sample)), judgeCalls = emptyList())
        assertFalse(evaluationRunFinished(terminalSnapshot, completedSamples = 1, selectedSamples = 1))
        assertTrue(evaluationRunFinished(null, completedSamples = 1, selectedSamples = 1))
        assertFalse(evaluationRunFinished(null, completedSamples = 0, selectedSamples = 1))
    }

    // ---------------------------------------------------------------- R2-4 report invariants

    @Test fun `complete report refuses when selected samples are missing`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-incomplete-")
        Files.createDirectories(directory.resolve(sample.id))
        writeJson(directory.resolve(sample.id).resolve("result.json"), SampleResult(sample).json())
        writeReport(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            null,
            listOf(SampleResult(sample)),
            emptyList(),
            2,
            true,
            emptySet(),
            null,
            null,
            null,
            true,
        )
        val report = Json.parseToJsonElement(Files.readString(directory.resolve("report.json"))).jsonObject
        assertFalse(
            report
                .getValue("complete")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertTrue(report.getValue("completeBlockedBy").jsonArray.isNotEmpty())
    }

    @Test fun `complete report refuses duplicate sample ids`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-duplicate-")
        Files.createDirectories(directory.resolve(sample.id))
        writeJson(directory.resolve(sample.id).resolve("result.json"), SampleResult(sample).json())
        writeReport(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            null,
            listOf(SampleResult(sample), SampleResult(sample)),
            emptyList(),
            2,
            true,
            emptySet(),
            null,
            null,
            null,
            true,
        )
        val report = Json.parseToJsonElement(Files.readString(directory.resolve("report.json"))).jsonObject
        assertFalse(
            report
                .getValue("complete")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertTrue(report.getValue("completeBlockedBy").jsonArray.any { it.jsonPrimitive.content.contains("DUPLICATE") })
    }

    @Test fun `complete report refuses when a sample result artifact is missing`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-missing-artifact-")
        Files.createDirectories(directory.resolve(sample.id))
        // Selection and count agree, the run says finished, but the sample has no on-disk result artifact.
        writeReport(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            null,
            listOf(SampleResult(sample)),
            emptyList(),
            1,
            true,
            emptySet(),
            null,
            null,
            null,
            true,
        )
        writeRunManifest(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            listOf(SampleResult(sample)),
            emptyList(),
            1,
            null,
            null,
            null,
            Instant.now(),
            true,
        )
        val report = Json.parseToJsonElement(Files.readString(directory.resolve("report.json"))).jsonObject
        assertFalse(
            report
                .getValue("complete")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertTrue(
            report.getValue("completeBlockedBy").jsonArray.any {
                it.jsonPrimitive.content.startsWith("MISSING_RESULT_ARTIFACT")
            },
            report.getValue("completeBlockedBy").toString(),
        )
        // One shared decision: report and run manifest can never disagree about completeness.
        val run = Json.parseToJsonElement(Files.readString(directory.resolve("run.json"))).jsonObject
        assertEquals(
            report.getValue("complete").jsonPrimitive.content,
            run.getValue("complete").jsonPrimitive.content,
        )
        assertEquals(
            report.getValue("completeBlockedBy").jsonArray.map { it.jsonPrimitive.content },
            run.getValue("completeBlockedBy").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test fun `complete report refuses results outside the resolved selection`() {
        val sample = loadEvalSamples(root).first()
        val other = loadEvalSamples(root).first { it.id != sample.id }
        val directory = Files.createTempDirectory("ct31-unexpected-")
        listOf(sample, other).forEach { candidate ->
            Files.createDirectories(directory.resolve(candidate.id))
            writeJson(directory.resolve(candidate.id).resolve("result.json"), SampleResult(candidate).json())
        }
        writeReport(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            null,
            listOf(SampleResult(sample)),
            emptyList(),
            1,
            true,
            emptySet(),
            null,
            null,
            null,
            true,
            listOf(sample.id, other.id),
        )
        val report = Json.parseToJsonElement(Files.readString(directory.resolve("report.json"))).jsonObject
        assertFalse(
            report
                .getValue("complete")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertTrue(
            report.getValue("completeBlockedBy").jsonArray.any {
                it.jsonPrimitive.content.startsWith("UNRUN_SAMPLE_IDS")
            },
            report.getValue("completeBlockedBy").toString(),
        )
    }

    @Test fun `complete report refuses a raw artifact whose bytes no longer match its reference`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-raw-tamper-")
        val sampleDirectory = directory.resolve(sample.id)
        Files.createDirectories(sampleDirectory.resolve("raw"))
        val rawFile = sampleDirectory.resolve("raw/production-call-test-1.json")
        Files.writeString(rawFile, "{\"state\":\"SUCCEEDED\"}")
        val reference =
            RawEvidenceRef(
                path = "raw/production-call-test-1.json",
                sha256 = sha256File(rawFile),
                state = "SUCCEEDED",
                attempts = 1,
                missing = false,
            )
        val recorded =
            SampleResult(
                sample,
                calls =
                    listOf(
                        CallRecord(
                            task = sample.task,
                            profile = "production-${sample.task}",
                            model = "test-model",
                            promptVersion = "v1",
                            schemaVersion = "v1",
                            promptSha256 = sha256Bytes("prompt".toByteArray()),
                            estimatedInputTokens = 1,
                            usage = null,
                            latencyMs = 1,
                            attempts = 1,
                            finishReason = "stop",
                            truncated = false,
                            output = kotlinx.serialization.json.JsonObject(emptyMap()),
                            rawEvidence = reference,
                        ),
                    ),
            )
        writeJson(sampleDirectory.resolve("result.json"), recorded.json())
        // Consistent reference first: the run is complete.
        assertTrue(runCompleteness(directory, listOf(recorded), 1, true, listOf(sample.id)).complete)
        // One changed byte must break completeness instead of silently producing a "verified" report.
        Files.writeString(rawFile, "{\"state\":\"FAILED\"}")
        val tampered = runCompleteness(directory, listOf(recorded), 1, true, listOf(sample.id))
        assertFalse(tampered.complete)
        assertTrue(
            tampered.blocked.any { it.startsWith("RAW_ARTIFACT_SHA_MISMATCH") },
            tampered.blocked.toString(),
        )
        // Deleting the raw artifact is equally fatal.
        Files.delete(rawFile)
        val deleted = runCompleteness(directory, listOf(recorded), 1, true, listOf(sample.id))
        assertFalse(deleted.complete)
        assertTrue(deleted.blocked.any { it.startsWith("MISSING_RAW_ARTIFACT") }, deleted.blocked.toString())
    }

    @Test fun `run completeness refuses production calls without complete raw evidence`() {
        val sample = loadEvalSamples(root).first()

        fun recorded(evidence: RawEvidenceRef?) =
            SampleResult(
                sample,
                calls =
                    listOf(
                        CallRecord(
                            task = sample.task,
                            profile = "production-${sample.task}",
                            model = "test-model",
                            promptVersion = "v1",
                            schemaVersion = "v1",
                            promptSha256 = sha256Bytes("prompt".toByteArray()),
                            estimatedInputTokens = 1,
                            usage = null,
                            latencyMs = 1,
                            attempts = 1,
                            finishReason = "stop",
                            truncated = false,
                            output = kotlinx.serialization.json.JsonObject(emptyMap()),
                            rawEvidence = evidence,
                        ),
                    ),
            )

        fun assertBlocked(
            evidence: RawEvidenceRef?,
            blocker: String,
        ) {
            val directory = Files.createTempDirectory("ct31-raw-required-")
            val result = recorded(evidence)
            Files.createDirectories(directory.resolve(sample.id))
            writeJson(directory.resolve(sample.id).resolve("result.json"), result.json())
            val completeness = runCompleteness(directory, listOf(result), 1, true, listOf(sample.id))
            assertFalse(completeness.complete, completeness.blocked.toString())
            assertTrue(completeness.blocked.any { it.startsWith(blocker) }, completeness.blocked.toString())
        }

        assertBlocked(null, "MISSING_RAW_EVIDENCE_REFERENCE")
        assertBlocked(
            RawEvidenceRef(
                path = "raw/production-call-test-1.json",
                sha256 = "0".repeat(64),
                state = "FAILED",
                attempts = 1,
                missing = true,
            ),
            "RAW_EVIDENCE_MARKED_MISSING",
        )
    }

    // ---------------------------------------------------------------- R2-4 judge evidence and selection ids

    @Test fun `complete report refuses a judge raw artifact whose bytes no longer match`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-judge-raw-")
        val sampleDirectory = directory.resolve(sample.id)
        Files.createDirectories(sampleDirectory)
        val judgeCall =
            JudgeCallRecord(
                callId = "judge-raw-1",
                sampleId = sample.id,
                task = sample.task,
                judgment = "test",
                input = "input",
                rawResponse = "{\"pass\":true}",
                parsedResponse = kotlinx.serialization.json.buildJsonObject {},
                elapsedMs = 1,
                attempts =
                    listOf(
                        com.github.noahshen.alcyone.context.memory.runtime.evaluation.RecordedRawExchange(
                            attempt = 1,
                            httpStatus = 200,
                            rawResponse = "{\"pass\":true}",
                            failureKind = null,
                            errorType = null,
                        ),
                    ),
            )
        writeSampleArtifacts(
            sampleDirectory,
            EvaluationIdentity("model-test", "profile-test", LlmResponseFormat.JSON_SCHEMA.name, null),
            SampleResult(sample),
            listOf(judgeCall),
        )
        val sampleResult = SampleResult(sample)
        assertTrue(runCompleteness(directory, listOf(sampleResult), 1, true, listOf(sample.id), listOf(judgeCall)).complete)
        val rawFile = sampleDirectory.resolve("raw/judge-call-judge-raw-1.json")
        assertTrue(Files.isRegularFile(rawFile))
        Files.writeString(rawFile, "{\"pass\":false}")
        val tampered = runCompleteness(directory, listOf(sampleResult), 1, true, listOf(sample.id), listOf(judgeCall))
        assertFalse(tampered.complete)
        assertTrue(
            tampered.blocked.any { it.startsWith("JUDGE_RAW_ARTIFACT_SHA_MISMATCH") && it.contains(sample.id) },
            tampered.blocked.toString(),
        )
        // A judge call that was recorded but whose artifact vanished is equally fatal.
        Files.delete(rawFile)
        val deleted = runCompleteness(directory, listOf(sampleResult), 1, true, listOf(sample.id), listOf(judgeCall))
        assertFalse(deleted.complete)
        assertTrue(deleted.blocked.any { it.startsWith("MISSING_JUDGE_RAW_ARTIFACT") }, deleted.blocked.toString())
    }

    @Test fun `complete report refuses a judge call that recorded no request body`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-judge-nobody-")
        val sampleDirectory = directory.resolve(sample.id)
        Files.createDirectories(sampleDirectory)
        // Transport failure: the logical call happened but no HTTP exchange was captured.
        val judgeCall =
            JudgeCallRecord(
                callId = "judge-nobody-1",
                sampleId = sample.id,
                task = sample.task,
                judgment = "test",
                input = "input",
                rawResponse = "",
                parsedResponse = kotlinx.serialization.json.buildJsonObject {},
                elapsedMs = 1,
                error = "transport",
                state = EvaluationCallState.FAILED,
            )
        writeSampleArtifacts(
            sampleDirectory,
            EvaluationIdentity("model-test", "profile-test", LlmResponseFormat.JSON_SCHEMA.name, null),
            SampleResult(sample),
            listOf(judgeCall),
        )
        val completeness =
            runCompleteness(directory, listOf(SampleResult(sample)), 1, true, listOf(sample.id), listOf(judgeCall))
        assertFalse(completeness.complete)
        assertTrue(
            completeness.blocked.any { it.startsWith("JUDGE_RAW_EVIDENCE_MARKED_MISSING") },
            completeness.blocked.toString(),
        )
    }

    @Test fun `complete report refuses when the selection ids were never recorded`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-no-selection-")
        Files.createDirectories(directory.resolve(sample.id))
        writeJson(directory.resolve(sample.id).resolve("result.json"), SampleResult(sample).json())
        // Count and single result agree, the run finished, but nothing proves *which* samples were selected.
        val unrecorded = runCompleteness(directory, listOf(SampleResult(sample)), 1, true, null)
        assertFalse(unrecorded.complete)
        assertTrue(unrecorded.blocked.any { it.startsWith("MISSING_SELECTION_IDS") }, unrecorded.blocked.toString())
        // A selection list that cannot describe `selected` samples is equally untrustworthy.
        val mismatched = runCompleteness(directory, listOf(SampleResult(sample)), 2, true, listOf(sample.id))
        assertFalse(mismatched.complete)
        assertTrue(mismatched.blocked.any { it.startsWith("SELECTION_SIZE_MISMATCH") }, mismatched.blocked.toString())
        val duplicatedSelection = runCompleteness(directory, listOf(SampleResult(sample)), 2, true, listOf(sample.id, sample.id))
        assertFalse(duplicatedSelection.complete)
        assertTrue(
            duplicatedSelection.blocked.any { it.startsWith("DUPLICATE_SELECTION_IDS") },
            duplicatedSelection.blocked.toString(),
        )
    }

    @Test fun `human review is reported as not executed and blocked regardless of all PASS results`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-human-blocked-")
        Files.createDirectories(directory.resolve(sample.id))
        val passing = SampleResult(sample, checks = listOf(Check("ok", Verdict.PASS, "ok")))
        writeJson(directory.resolve(sample.id).resolve("result.json"), passing.json())
        writeReport(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            null,
            listOf(passing),
            emptyList(),
            1,
            true,
            emptySet(),
            null,
            null,
            null,
            true,
            listOf(sample.id),
        )
        val humanReview =
            Json
                .parseToJsonElement(Files.readString(directory.resolve("report.json")))
                .jsonObject
                .getValue("humanReview")
                .jsonObject
        assertEquals("NOT_EXECUTED", humanReview.getValue("state").jsonPrimitive.content)
        assertFalse(
            humanReview
                .getValue("complete")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals(
            listOf("H1", "H2", "H3"),
            humanReview.getValue("blocked").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test fun `written report and manifest satisfy the published report schema`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-schema-")
        Files.createDirectories(directory.resolve(sample.id))
        writeJson(directory.resolve(sample.id).resolve("result.json"), SampleResult(sample).json())
        writeReport(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            null,
            listOf(SampleResult(sample)),
            emptyList(),
            1,
            true,
            emptySet(),
            null,
            null,
            null,
            true,
            listOf(sample.id),
        )
        val schema = root.resolve("evals/schema/real-model-report-v1.schema.json")
        val report = Json.parseToJsonElement(Files.readString(directory.resolve("report.json")))
        EvaluationJsonSchemaSubsetValidator.validateFile(schema, report)
        // A schema-valid but incomplete artifact must still be readable as incomplete.
        assertTrue(
            report.jsonObject
                .getValue("complete")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertTrue(
            report
                .jsonObject
                .getValue("sampleResultSha256")
                .jsonObject
                .containsKey(sample.id),
        )
    }

    @Test fun `human review remains required until H1 H2 H3 actually run`() {
        val sample = loadEvalSamples(root).first()
        val directory = Files.createTempDirectory("ct31-human-review-")
        Files.createDirectories(directory.resolve(sample.id))
        writeJson(directory.resolve(sample.id).resolve("result.json"), SampleResult(sample).json())
        val passing = SampleResult(sample, checks = listOf(Check("ok", Verdict.PASS, "ok")))
        writeJson(directory.resolve(sample.id).resolve("result.json"), passing.json())
        writeReport(
            directory,
            root,
            "model-test",
            "profile-test",
            LlmResponseFormat.JSON_SCHEMA.name,
            null,
            listOf(passing),
            emptyList(),
            1,
            true,
            emptySet(),
            null,
            null,
            null,
            true,
            listOf(sample.id),
        )
        val report = Json.parseToJsonElement(Files.readString(directory.resolve("report.json"))).jsonObject
        // All-PASS must not be read as "human review done".
        assertTrue(
            report
                .getValue("humanReviewStillRequired")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals(
            "NOT_EXECUTED",
            report
                .getValue("humanReview")
                .jsonObject
                .getValue("state")
                .jsonPrimitive.content,
        )
        assertTrue(
            report
                .getValue("complete")
                .jsonPrimitive.content
                .toBoolean(),
        )
    }

    // ---------------------------------------------------------------- R2-5 implementation SHA manifest

    @Test fun `implementation manifest covers every execution source and fails on missing files`() {
        val critical =
            setOf(
                "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation/EvaluationRawExchangeCapture.kt",
                "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation/ProductionEvaluationAdapters.kt",
                "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation/ProductionDreamingEvaluationRunner.kt",
                "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/llm/KtorLlmClient.kt",
                "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/llm/RawExchangeObserver.kt",
            )
        val execution =
            setOf(
                "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/RawEvidence.kt",
                "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/ProductionEvalRunner.kt",
                "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/ProductionDreamingEvalRunner.kt",
                "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/SemanticReview.kt",
                "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/SemanticJudge.kt",
                "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/EvalResult.kt",
                "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/RealModelEvalCli.kt",
            )
        assertTrue(EVALUATION_CRITICAL_PRODUCTION_SOURCES.containsAll(critical), EVALUATION_CRITICAL_PRODUCTION_SOURCES.toString())
        assertTrue(EVALUATION_EXECUTION_SOURCES.containsAll(execution), EVALUATION_EXECUTION_SOURCES.toString())
        val implementationFiles = evaluationImplementationFiles(root)
        assertTrue(implementationFiles.containsAll(critical), implementationFiles.toString())
        assertTrue(implementationFiles.containsAll(execution), implementationFiles.toString())
        val shas = evaluationImplementationSha(root)
        assertEquals(implementationFiles, shas.keys.toList())
        assertTrue(shas.values.none { it == "MISSING" }, shas.toString())
        shas.forEach { (relative, digest) ->
            assertEquals(
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(Files.readAllBytes(root.resolve(relative)))
                    .joinToString("") { "%02x".format(it) },
                digest,
                relative,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            evaluationImplementationSha(root, listOf("does/not/exist.kt"))
        }
    }

    @Test fun `implementation manifest discovers every evaluation source file on disk`() {
        // A new file silently omitted from the manifest would make two different implementations look identical.
        // These directories intentionally contain both executable helpers and tests: omitting tests by filename
        // would let a future helper hide in one of those already-excluded files.
        val directories =
            listOf(
                "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation",
                "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation",
            )
        val onDisk =
            directories
                .flatMap { directory ->
                    Files.walk(root.resolve(directory)).use { stream ->
                        stream
                            .filter { Files.isRegularFile(it) }
                            .filter { it.fileName.toString().endsWith(".kt") || it.fileName.toString().endsWith(".java") }
                            .map { root.relativize(it).toString().replace('\\', '/') }
                            .toList()
                    }
                }.toSet()
        val discovered = evaluationImplementationFiles(root).toSet()
        assertEquals(EVALUATION_CRITICAL_PRODUCTION_SOURCES + onDisk, discovered)
        assertTrue(
            "integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/ProductionEvaluationReliabilityTest.kt" in
                discovered,
        )
    }
}

private class ScriptedClient(
    vararg responses: String,
) : LlmClient {
    private val queue = ArrayDeque(responses.toList())
    val tasks = mutableListOf<String>()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        tasks += request.task.name
        return LlmResponse(queue.removeFirst(), TokenUsage(10, 5, 15), "stop")
    }
}

private fun testConfig(): LlmConfig {
    val profile =
        LlmProfileConfig(
            "test",
            "https://example.invalid/v1",
            "not-a-secret",
            "test-model",
            32768,
            LlmResponseFormat.JSON_SCHEMA,
            maxAttempts = 1,
        )
    return LlmConfig(mapOf(profile.name to profile), LlmTask.entries.associateWith { LlmTaskConfig(profile.name, 20000, 4096, 10) })
}

private class CancellingClient : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse = throw CancellationException("cancelled by evaluation")
}

/** Returns one scripted body, then cancels the next logical call while it is in flight. */
private class ScriptedThenCancellingClient(
    private val first: String,
) : LlmClient {
    private var used = false

    override suspend fun complete(request: LlmRequest): LlmResponse {
        if (!used) {
            used = true
            return LlmResponse(first, TokenUsage(10, 5, 15), "stop")
        }
        throw CancellationException("ct31-cli-cancel")
    }
}

/** Provider outage with a marker in the message that must never reach artifacts. */
private class UnavailableClient(
    private val marker: String,
) : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse =
        throw com.github.noahshen.alcyone.context.memory.core.llm.LlmException(
            com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode.PROVIDER_UNAVAILABLE,
            marker,
        )
}

/** Fails the scanner call so the production worker swallows an item failure the adapter must report. */
private class FailingScannerClient : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse =
        throw com.github.noahshen.alcyone.context.memory.core.llm.LlmException(
            com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode.PROVIDER_UNAVAILABLE,
            "ct31-governance-item-key",
        )
}
