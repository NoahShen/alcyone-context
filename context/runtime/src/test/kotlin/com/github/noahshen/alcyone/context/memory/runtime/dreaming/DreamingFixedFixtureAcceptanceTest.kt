package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.DreamingRunException
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingCommitter
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingConversationNeedValidator
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingEvidencePrompt
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingNoveltyValidator
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingReflectionPrompt
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingTopicDiscoveryPrompt
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGenerator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.core.retrieval.MemorySearchIdValidator
import com.github.noahshen.alcyone.context.memory.runtime.extraction.OrganizationWakeup
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.retrieval.JsonMemorySearchResponseParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** B2：脱敏 JSONL 经真实 v1 Prompt、四阶段、证据校验与 SQLite 提交；不把 Fake 通过记为模型通过。 */
class DreamingFixedFixtureAcceptanceTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `sanitized jsonl drives routing evidence commit failure and recovery`() =
        runBlocking {
            val manifest = json.parseToJsonElement(resource("manifest.json")).asObject()
            val realResults = json.parseToJsonElement(resource(manifest.string("realModelResultsFile"))).asObject()
            assertEquals("not-run", realResults.string("status"))
            assertEquals(DreamingTopicDiscoveryPrompt.VERSION, manifest.string("topicPromptVersion"))
            assertEquals(DreamingTopicDiscoveryPrompt.SCHEMA_VERSION, manifest.string("topicSchemaVersion"))
            assertEquals(DreamingEvidencePrompt.VERSION, manifest.string("evidencePromptVersion"))
            assertEquals(DreamingEvidencePrompt.SCHEMA_VERSION, manifest.string("evidenceSchemaVersion"))
            assertEquals(DreamingReflectionPrompt.VERSION, manifest.string("reflectionPromptVersion"))
            assertEquals(DreamingReflectionPrompt.SCHEMA_VERSION, manifest.string("reflectionSchemaVersion"))

            val turns = resourceLines(manifest.string("turnsFile")).map(::fixtureTurn)
            val memories = resourceLines(manifest.string("memoriesFile"))
            assertEquals(listOf("raw", "organized"), memories.map { it.asObject().string("kind") })
            assertEquals("fixture-raw-001", memories[0].asObject().string("id"))
            assertEquals(listOf("fixture-raw-001"), memories[1].asObject().strings("rawMemoryIds"))
            val scenarios = resourceLines(manifest.string("scenariosFile")).map { it.asObject() }
            assertEquals(listOf(1L, 2L, 3L), turns.map { it.id })
            assertTrue(manifest.string("sanitization").contains("synthetic"))
            val results =
                scenarios.map { scenario ->
                    try {
                        executeScenario(manifest, turns, memories.map { it.asObject() }, scenario)
                    } catch (failure: Throwable) {
                        throw AssertionError("Fixture scenario ${scenario.string("id")}", failure)
                    }
                }
            val report = results.joinToString("\n") { result -> "CT26_FIXTURE $result" }
            println(
                "CT26_FIXTURE scenarios=${results.size} promptSha256=${sha256(DreamingReflectionPrompt.reflectionSystemPrompt())} " +
                    "schemaSha256=${sha256(DreamingReflectionPrompt.reflectionSchema())} realModel=not-run\n$report",
            )
        }

    private suspend fun executeScenario(
        manifest: JsonObject,
        fixtures: List<StoredConversationTurn>,
        memories: List<JsonObject>,
        scenario: JsonObject,
    ): String {
        val id = scenario.string("id")
        val requestedTurnIds = scenario.longs("turnIds")
        val fixtureTurns = fixtures.filter { it.id <= requestedTurnIds.max() }
        var state = FixtureDatabase()
        seed(state.database, fixtureTurns, memories)
        setDreamingCheckpoint(state.database, scenario.long("initialCheckpoint"))
        var llm = FakeEvidenceLlmClient { request, _ -> fixtureResponse(scenario.objects("stageOutputs"), request) }
        var coordinator = coordinator(state.database, llm)
        val checkpointBeforeFailure = scenario.long("initialCheckpoint")
        val expected = scenario.objectValue("expected")
        try {
            if (id == "reflection-failure") {
                val failure = assertFailsWith<DreamingRunException> { coordinator.runOnce() }
                assertEquals(LlmErrorCode.PROVIDER_UNAVAILABLE, (failure.cause as LlmException).errorCode)
            } else {
                coordinator.runOnce()
            }
        } finally {
            coordinator.closeAsync()
        }
        assertState(state.database, expected, id)
        assertEquals(expected.int("reflectionCount"), llm.requests.count { it.task == LlmTask.DREAMING_REFLECTION }, id)
        assertRequestContracts(manifest, scenario.objects("stageOutputs"), llm.requests, id)
        val reflection = llm.requests.lastOrNull { it.task == LlmTask.DREAMING_REFLECTION }
        expected.strings("promptContains").forEach { fragment ->
            assertTrue(
                reflection
                    ?.messages
                    ?.last()
                    ?.content
                    ?.contains(fragment) == true,
                "$id missing $fragment",
            )
        }
        assertEquals(
            scenario.objects("stageOutputs").map { it.string("stage") },
            llm.requests.map { fixtureStage(it) },
            id,
        )
        val raws = state.database.read { it.rawMemories.scanPage(0, 10) }
        assertEquals(expected.longs("sourceTurnIds"), raws.flatMap(::sourceTurnIds), id)
        if (id != "reflection-failure") {
            val empty = coordinator(state.database, llm)
            val result = empty.runOnce()
            empty.closeAsync()
            assertEquals(0, result.scannedTurns, "$id repeated run")
            assertEquals(0, result.generatedMemories, "$id repeated run")
        }

        if (id == "reflection-failure") {
            val checkpointAfterFailure = state.database.read { it.dreaming.readCheckpoint() }
            assertEquals(checkpointBeforeFailure, checkpointAfterFailure)
            // 重新打开同一个 SQLite 文件；失败批的 turn 2/3 重扫，已提交历史 Raw 不重复。
            state.close()
            state = FixtureDatabase(state.path)
            llm = FakeEvidenceLlmClient { request, _ -> fixtureResponse(expected.objects("recoveryStageOutputs"), request) }
            coordinator = coordinator(state.database, llm)
            val resumed = coordinator.runOnce()
            coordinator.closeAsync()
            assertEquals(checkpointBeforeFailure, resumed.startCheckpoint)
            assertEquals(expected.long("scannedUpperTurnId"), resumed.endCheckpoint)
            assertEquals(expected.int("scannedTurns"), resumed.scannedTurns)
            assertEquals(expected.int("generatedMemories"), resumed.generatedMemories)
            assertState(state.database, expected, "$id-recovery", recovery = true)
            assertEquals(
                expected.longs("recoverySourceTurnIds"),
                state.database.read { it.rawMemories.scanPage(0, 10) }.flatMap(::sourceTurnIds),
            )
        }
        val checkpoint = state.database.read { it.dreaming.readCheckpoint() }
        state.close()
        return "id=$id checkpoint=$checkpoint outputs=${scenario.objects("stageOutputs").size}"
    }

    private fun coordinator(
        database: ContextDatabase,
        llm: FakeEvidenceLlmClient,
    ): DreamingRunCoordinator {
        val profiles =
            listOf("topic", "memory-search", "evidence", "reflection").associate { suffix ->
                "fixture-$suffix" to
                    LlmProfileConfig(
                        "fixture-$suffix",
                        "https://fixture.invalid/v1",
                        "not-a-secret-$suffix",
                        "fixture-$suffix-model",
                        1_000_000,
                        LlmResponseFormat.JSON_SCHEMA,
                    )
            }
        val tasks =
            listOf(LlmTask.DREAMING_TOPIC, LlmTask.MEMORY_SEARCH, LlmTask.DREAMING_EVIDENCE, LlmTask.DREAMING_REFLECTION)
                .associateWith { task ->
                    val profile = "fixture-${manifestProfileSuffix(task)}"
                    LlmTaskConfig(profile, 100_000, 4_096)
                }
        val config = LlmConfig(profiles, tasks)
        val estimator = FixtureTokenEstimator()
        val discovery = LlmDreamingTopicDiscoveryStage(llm, config, estimator)
        val evidence = LlmDreamingEvidenceStage(database, llm, config, discovery, estimator)
        val reflection = LlmDreamingReflectionStage(llm, config, estimator)
        val ids = AtomicInteger()
        val generator =
            object : MemoryIdGenerator {
                override fun nextRawMemoryId(): String = "fixture-generated-${ids.incrementAndGet()}"

                override fun nextOrganizedMemoryId(): String = error("Dreaming cannot create Organized")
            }
        val processor =
            LlmDreamingBatchProcessor(
                database,
                evidence,
                reflection,
                DreamingCommitter(database, generator, fixedClock()),
                OrganizationWakeup {},
            )
        return DreamingRunCoordinator(database, processor, LlmDreamingTopicBatchBudget(discovery), pageSize = 100)
    }

    private suspend fun seed(
        database: ContextDatabase,
        turns: List<StoredConversationTurn>,
        memoryFixtures: List<JsonObject>,
    ) {
        database.write { tx ->
            turns.forEach { turn ->
                val result =
                    tx.conversations.insertTurn(
                        turn.runtimeId,
                        turn.agentId,
                        turn.sessionId,
                        turn.sessionTurnId,
                        turn.timestamp,
                        turn.createdAt,
                        turn.messages,
                    )
                check(result.status == IngestStatus.CREATED && result.id == turn.id)
                if (turn.extractionStatus == ExtractionStatus.COMPLETE) check(tx.conversations.markComplete(turn.id) == 1)
            }
            val rawFixture = memoryFixtures.first { it.string("kind") == "raw" }
            val organizedFixture = memoryFixtures.first { it.string("kind") == "organized" }
            val turnById = turns.associateBy { it.id }
            val sources =
                rawFixture.longs("sourceTurnIds").map { id ->
                    val turn = turnById.getValue(id)
                    MemorySourceRef.ConversationTurnRef(turn.runtimeId, turn.agentId, turn.sessionId, turn.sessionTurnId)
                }
            check(
                tx.rawMemories.insertBatch(
                    listOf(
                        RawMemory(
                            rawFixture.string("id"),
                            rawFixture.string("content"),
                            MemoryOrigin.valueOf(rawFixture.string("generatedBy")),
                            sources,
                            Instant.parse(rawFixture.string("createdAt")),
                        ),
                    ),
                ) ==
                    1,
            )
            val organized =
                OrganizedMemory(
                    organizedFixture.string("id"),
                    organizedFixture.string("content"),
                    organizedFixture.strings("domains"),
                    organizedFixture.strings("rawMemoryIds"),
                    Instant.parse(organizedFixture.string("createdAt")),
                    Instant.parse(organizedFixture.string("updatedAt")),
                )
            check(tx.organizedMemories.insertNew(organized) == 1)
            check(tx.organizedMemories.insertSourceLink(organized.id, "fixture-raw-001") == 1)
        }
    }

    private suspend fun assertState(
        database: ContextDatabase,
        expected: JsonObject,
        id: String,
        recovery: Boolean = false,
    ) {
        val checkpointKey = if (recovery) "recoveryCheckpoint" else "checkpoint"
        assertEquals(expected.long(checkpointKey), database.read { it.dreaming.readCheckpoint() }, id)
        val rawCountKey = if (recovery) "recoveryRawCount" else "rawCount"
        val pendingCountKey = if (recovery) "recoveryPendingCount" else "pendingCount"
        assertEquals(expected.int(rawCountKey), database.read { it.rawMemories.scanPage(0, 10).size }, id)
        assertEquals(expected.int(pendingCountKey), database.read { it.pending.listAllByRawCreatedAt().size }, id)
        database.read { read ->
            assertEquals(listOf("fixture-raw-001"), read.organizedMemories.findSourcesByOrganized("fixture-organized-001"), id)
            assertEquals(listOf("fixture-organized-001"), read.organizedMemories.findOrganizedByRaw("fixture-raw-001"), id)
        }
    }

    private fun assertRequestContracts(
        manifest: JsonObject,
        outputs: List<JsonObject>,
        requests: List<LlmRequest>,
        id: String,
    ) {
        assertEquals(outputs.size, requests.size, id)
        val profiles = manifest.objectValue("profiles")
        requests.zip(outputs).forEach { (request, output) ->
            assertEquals(LlmTask.valueOf(output.string("task")), request.task, id)
            assertEquals(output.string("profile"), "fixture-${manifestProfileSuffix(request.task)}", id)
            assertEquals(LlmResponseFormat.JSON_SCHEMA, request.responseFormat, id)
            assertEquals(DreamingReflectionPrompt.VERSION, manifest.string("reflectionPromptVersion"))
            val profile = profiles.objectValue(output.string("profile"))
            assertTrue(request.schema?.isNotBlank() == true, id)
            assertEquals("fixture-${manifestProfileSuffix(request.task)}-model", profile.string("model"), id)
            assertEquals("JSON_SCHEMA", profile.string("jsonMode"))
        }
    }

    private fun manifestProfileSuffix(task: LlmTask): String =
        when (task) {
            LlmTask.DREAMING_TOPIC -> "topic"
            LlmTask.MEMORY_SEARCH -> "memory-search"
            LlmTask.DREAMING_EVIDENCE -> "evidence"
            LlmTask.DREAMING_REFLECTION -> "reflection"
            else -> error("Unsupported fixed Dreaming task: $task")
        }

    private fun fixtureResponse(
        outputs: List<JsonObject>,
        request: LlmRequest,
    ): LlmResponse {
        val output =
            outputs.firstOrNull { it.string("task") == request.task.name && it.string("stage") == fixtureStage(request) }
                ?: error(
                    "No fixed output for ${request.task} ${fixtureStage(
                        request,
                    )}; schema=${request.schema?.take(150)}; prompt=${request.messages.last().content.take(200)}",
                )
        if (output["failureCode"] == null) {
            try {
                when (output.string("stage")) {
                    "TOPIC_DISCOVERY" -> JsonDreamingTopicResponseParser().parse(output.string("content"))
                    "CANDIDATE_SEARCH" ->
                        MemorySearchIdValidator.validateScannerBatch(
                            setOf("fixture-organized-001"),
                            JsonMemorySearchResponseParser().parse(output.string("content")),
                        )
                    "EVIDENCE_NOVELTY" ->
                        DreamingNoveltyValidator.validate(
                            JsonDreamingNoveltyResponseParser().parse(output.string("content")),
                            Regex(
                                "<organized_memory id=\"([^\"]+)\"",
                            ).findAll(request.messages.last().content).map { it.groupValues[1] }.toSet(),
                        )
                    "EVIDENCE_RAW" ->
                        MemorySearchIdValidator.validateScannerBatch(
                            setOf("fixture-raw-001"),
                            JsonDreamingEvidenceCandidateParser().parse(output.string("content")),
                        )
                    "EVIDENCE_CONVERSATION" ->
                        DreamingConversationNeedValidator.validate(
                            JsonDreamingConversationNeedParser().parse(output.string("content")),
                            setOf("fixture-raw-001"),
                        )
                    "REFLECTION" -> JsonDreamingReflectionParser().parse(output.string("content"))
                }
            } catch (failure: Throwable) {
                throw AssertionError("Invalid fixture ${output.string("stage")}: ${output.string("content")}", failure)
            }
        }
        output["failureCode"]?.let {
            throw LlmException(
                LlmErrorCode.valueOf((it as JsonPrimitive).content),
                "controlled fixture failure",
                request.task,
                output.string("profile"),
                output.string("model"),
            )
        }
        return LlmResponse(content = output.string("content"), usage = null, finishReason = "stop")
    }

    private fun fixtureStage(request: LlmRequest): String =
        when (request.task) {
            LlmTask.DREAMING_TOPIC -> "TOPIC_DISCOVERY"
            LlmTask.MEMORY_SEARCH ->
                if (request.schema?.contains("DreamingEvidenceRawOutputV1") == true) {
                    "EVIDENCE_RAW"
                } else {
                    "CANDIDATE_SEARCH"
                }
            LlmTask.DREAMING_REFLECTION -> "REFLECTION"
            LlmTask.DREAMING_EVIDENCE ->
                when {
                    request.messages
                        .last()
                        .content
                        .contains("<organized count=") -> "EVIDENCE_NOVELTY"
                    request.messages
                        .last()
                        .content
                        .contains("<raw_memories count=") -> "EVIDENCE_RAW"
                    else -> "EVIDENCE_CONVERSATION"
                }
            else -> error("Unexpected fixed fixture task ${request.task}")
        }

    private fun fixtureTurn(element: kotlinx.serialization.json.JsonElement): StoredConversationTurn {
        val turn = element.asObject()
        return StoredConversationTurn(
            turn.long("id"),
            turn.string("runtimeId"),
            turn.string("agentId"),
            turn.string("sessionId"),
            turn.string("sessionTurnId"),
            Instant.parse(turn.string("timestamp")),
            Instant.parse(turn.string("timestamp")),
            turn
                .array(
                    "messages",
                ).map { ChatMessage(ChatRole.valueOf(it.asObject().string("role").uppercase()), it.asObject().string("content")) },
            ExtractionStatus.valueOf(turn.string("extractionStatus")),
        )
    }

    private fun sourceTurnIds(raw: RawMemory): List<Long> =
        raw.sources.map {
            (it as MemorySourceRef.ConversationTurnRef).sessionTurnId.removePrefix("turn-00").toLong()
        }

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"), ZoneOffset.UTC)

    private fun resourceLines(name: String) =
        resource(name)
            .lineSequence()
            .filter { it.isNotBlank() }
            .map(json::parseToJsonElement)
            .toList()

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/dreaming-fixtures-v1/$name")).bufferedReader().use {
            it.readText()
        }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private class FixtureDatabase(
        path: java.nio.file.Path? = null,
    ) {
        val path: java.nio.file.Path =
            path ?: java.nio.file.Files
                .createTempDirectory("ct26-fixture-")
                .resolve("context.db")
        private val executor: com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
        val database: com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl

        init {
            if (path ==
                null
            ) {
                com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
                    .initialize(this.path)
            }
            executor =
                com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
                    .open(this.path)
            database =
                com.github.noahshen.alcyone.context.memory.persistence.sqldelight
                    .ContextDatabaseImpl(executor)
        }

        fun close() = executor.close()
    }

    private class FixtureTokenEstimator : TokenEstimator {
        override fun estimateText(text: String): Int = text.length

        override fun estimatePrompt(messages: List<com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage>): Int =
            messages.sumOf { estimateText(it.content) }

        override fun estimateSchema(schema: String?): Int = schema?.let(::estimateText) ?: 0
    }
}

private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject = this as JsonObject

private fun JsonObject.string(name: String): String = (getValue(name) as JsonPrimitive).content

private fun JsonObject.long(name: String): Long = (getValue(name) as JsonPrimitive).long

private fun JsonObject.int(name: String): Int = (getValue(name) as JsonPrimitive).int

private fun JsonObject.array(name: String): JsonArray = getValue(name) as JsonArray

private fun JsonObject.objectValue(name: String): JsonObject = getValue(name).asObject()

private fun JsonObject.objects(name: String): List<JsonObject> = array(name).map { it.asObject() }

private fun JsonObject.longs(name: String): List<Long> = array(name).map { (it as JsonPrimitive).long }

private fun JsonObject.strings(name: String): List<String> = array(name).map { (it as JsonPrimitive).content }
