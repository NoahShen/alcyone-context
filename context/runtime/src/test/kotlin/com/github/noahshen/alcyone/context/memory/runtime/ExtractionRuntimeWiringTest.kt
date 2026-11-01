package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.extraction.ExtractionConfig
import com.github.noahshen.alcyone.context.memory.runtime.extraction.OrganizationWakeup
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmHttpStub
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.Slf4jCapture
import com.github.noahshen.alcyone.context.memory.runtime.llm.chatCompletionBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT11-B3：公共 Factory/Impl 的 Extraction 生产接线。
 *
 * 覆盖：公共入口无 LLM 时零 pipeline；有合法 `context.llm` 时构造真实 Runner（Count → LLM → 校验 → 原子提交）、
 * 非法响应由 Scheduler 消费（Target 保持 pending、Context 不崩）、注入 wakeup 的 raw>0/空 memories 语义、
 * `maxWindowIterations = initialWindowTurns` 接线，以及无 LLM 时 stub 0 请求。
 */
class ExtractionRuntimeWiringTest {
    private val session = SessionKey("runtime-1", "agent-1", "session-1")
    private val base: Instant = Instant.parse("2026-10-31T02:00:00Z")
    private val responseMarker = "ct11-b3-memory-marker-5d21"

    @Test
    fun `the public factory without llm keeps no pipeline and starts and closes`() =
        wiringTest { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                assertNull(impl.pipelineForTesting(), "无 context.llm 不得构造 pipeline")
            } finally {
                context.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `an llm config without a legal extraction binding keeps no pipeline`() {
        // 1b-1：EXTRACTION Task 存在但 Profile 为 PROMPT_ONLY → 不建 pipeline，start/close 正常。
        wiringTest { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, chatCompletionBody("""{"memories":[]}"""))
            val context =
                AlcyoneContextFactory.create(
                    fixture.config().copy(
                        llm = llmConfig(stub, jsonMode = LlmResponseFormat.PROMPT_ONLY),
                        extraction = ExtractionConfig(idleEnabled = false),
                    ),
                    fixture.vfsConfig(),
                )
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                assertNull(impl.pipelineForTesting(), "PROMPT_ONLY 不构成合法 extraction 绑定")
                repeat(5) { index -> runBlocking { context.ingestConversation(turn(index)) } }
                delay(300)
                assertEquals(0, stub.requests().size, "无 pipeline 时不得发出提取请求")
                assertEquals(List(5) { ExtractionStatus.PENDING }, statuses(impl))
            } finally {
                context.close()
                stub.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

        // 1b-2：没有任何 EXTRACTION Task → 不建 pipeline，start/close 正常。
        wiringTest { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, chatCompletionBody("""{"memories":[]}"""))
            val context =
                AlcyoneContextFactory.create(
                    fixture.config().copy(
                        llm = llmConfig(stub, includeExtractionTask = false),
                        extraction = ExtractionConfig(idleEnabled = false),
                    ),
                    fixture.vfsConfig(),
                )
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                assertNull(impl.pipelineForTesting(), "缺 EXTRACTION Task 不构成合法 extraction 绑定")
            } finally {
                context.close()
                stub.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }
    }

    @Test
    fun `the public factory with llm wires the real runner and commits extracted memories`() =
        Slf4jCapture().use { capture ->
            wiringTest { fixture ->
                fixture.initialize()
                // 新库的 5 条 Turn 依次获得 id 1..5；锚点/窗口含全部 5 条，来源引用 id=5。
                val stub =
                    LlmHttpStub(
                        200,
                        chatCompletionBody("""{"memories":[{"content":"$responseMarker","source_turn_ids":[5]}]}"""),
                    )
                val context = publicContextWithLlm(fixture, stub)
                val impl = context as AlcyoneContextImpl
                try {
                    runBlocking { context.start() }
                    assertNotNull(impl.pipelineForTesting(), "配置了 context.llm 后必须构造 pipeline")

                    repeat(5) { index ->
                        assertEquals(IngestStatus.CREATED, runBlocking { context.ingestConversation(turn(index)) }.status)
                    }
                    awaitUntil { rawRows(impl).isNotEmpty() }

                    val raws = rawRows(impl)
                    assertEquals(1, raws.size, "5 条 pending 达到阈值后应产生一条 Raw")
                    assertEquals(responseMarker, raws.single().content)
                    assertEquals(MemoryOrigin.EXTRACTION, raws.single().generatedBy)
                    assertEquals(
                        listOf(MemorySourceRef.ConversationTurnRef(session.runtimeId, session.agentId, session.sessionId, "turn-4")),
                        raws.single().sources,
                        "来源必须是稳定业务键",
                    )
                    assertTrue(
                        runBlocking { impl.databaseForTesting()!!.read { read -> read.pending.exists(raws.single().id) } },
                        "Raw 必须进入 Organization Pending",
                    )
                    assertEquals(
                        List(5) { ExtractionStatus.COMPLETE },
                        statuses(impl),
                        "全部 Target 必须完成",
                    )
                    assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle)
                    assertEquals(1, stub.requests().size, "一次成功提取只发一次请求")

                    // 日志脱敏：不得出现 API Key 或记忆正文。
                    assertTrue(
                        capture.logs.none { it.formattedMessage.contains(KEY_MARKER) },
                        capture.logs.map { it.formattedMessage }.toString(),
                    )
                    assertTrue(
                        capture.logs.none { it.formattedMessage.contains(responseMarker) },
                        capture.logs.map { it.formattedMessage }.toString(),
                    )
                } finally {
                    context.close()
                    stub.close()
                }
            }
        }

    @Test
    fun `an invalid model response keeps targets pending and the context running`() =
        wiringTest { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, chatCompletionBody("""{"memories":[],"extra":1}"""))
            val context = publicContextWithLlm(fixture, stub)
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                repeat(5) { index ->
                    runBlocking { context.ingestConversation(turn(index)) }
                }
                awaitUntil { stub.requests().isNotEmpty() }
                delay(500)

                assertTrue(rawRows(impl).isEmpty(), "非法响应不得写 Raw")
                assertTrue(
                    !runBlocking { impl.databaseForTesting()!!.read { read -> read.pending.hasAnyPending() } },
                    "非法响应不得写 Organization Pending",
                )
                assertEquals(List(5) { ExtractionStatus.PENDING }, statuses(impl), "Target 必须保持 pending")
                assertEquals(ContextLifecycleState.RUNNING, context.status().lifecycle, "普通失败不得使 Context 崩溃")
            } finally {
                context.close()
                stub.close()
            }
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `the injected wakeup fires once for raw and never for an empty batch`() {
        // 场景 1：raw > 0 → 唤醒 1 次。
        wiringTest { fixture ->
            fixture.initialize()
            val wakeups = AtomicInteger()
            val stub =
                LlmHttpStub(
                    200,
                    chatCompletionBody("""{"memories":[{"content":"$responseMarker","source_turn_ids":[5]}]}"""),
                )
            val context = internalContextWithLlm(fixture, stub) { wakeups.incrementAndGet() }
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                repeat(5) { index -> runBlocking { context.ingestConversation(turn(index)) } }
                awaitUntil { rawRows(impl).isNotEmpty() }
                awaitUntil { wakeups.get() == 1 }
                assertEquals(1, wakeups.get(), "rawCount>0 必须唤醒一次")
            } finally {
                context.close()
                stub.close()
            }
        }

        // 场景 2：空 memories → 不唤醒。
        wiringTest { fixture ->
            fixture.initialize()
            val wakeups = AtomicInteger()
            val stub = LlmHttpStub(200, chatCompletionBody("""{"memories":[]}"""))
            val context = internalContextWithLlm(fixture, stub) { wakeups.incrementAndGet() }
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                repeat(5) { index -> runBlocking { context.ingestConversation(turn(index)) } }
                awaitUntil { statuses(impl).all { status -> status == ExtractionStatus.COMPLETE } }
                delay(300)
                assertTrue(rawRows(impl).isEmpty())
                assertEquals(0, wakeups.get(), "空 memories 不得唤醒 Organization")
            } finally {
                context.close()
                stub.close()
            }
        }
    }

    @Test
    fun `max window iterations is wired from the initial window turns`() {
        val source =
            File(
                repositoryRoot(),
                "context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextImpl.kt",
            )
        assertTrue(source.isFile, "缺少 ${source.path}")
        val text = source.readText()
        assertTrue(
            text.contains("maxWindowIterations = extractionConfig.initialWindowTurns"),
            "Runner 的防御上限必须来自 initialWindowTurns",
        )
        assertTrue(text.contains("wakeup = organizationWakeup"), "唤醒必须来自注入的 OrganizationWakeup")
        assertTrue(
            text.contains("extractionRunner ?: defaultExtractionRunner("),
            "注入的 Runner 必须优先于默认 Runner",
        )
    }

    @Test
    fun `without llm the count trigger never sends a request to the stub`() =
        wiringTest { fixture ->
            fixture.initialize()
            val stub = LlmHttpStub(200, chatCompletionBody("""{"memories":[]}"""))
            // 不配置 context.llm：pipeline 不构造，Count/Idle 不启用（stub 仅作为兜底观察）。
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val impl = context as AlcyoneContextImpl
            try {
                runBlocking { context.start() }
                repeat(5) { index -> runBlocking { context.ingestConversation(turn(index)) } }
                delay(500)
                assertNull(impl.pipelineForTesting())
                assertEquals(0, stub.requests().size, "无 LLM 时不得发出任何模型请求")
                assertEquals(
                    List(5) { ExtractionStatus.PENDING },
                    statuses(impl),
                    "无 LLM 时不得推进提取状态",
                )
            } finally {
                context.close()
                stub.close()
            }
        }

    // ------------------------------------------------------------------ 工具

    /** 每个用例：临时目录 + 真实 SQLite 夹具 + `runBlocking`（便于直接调用挂起辅助）。 */
    private fun wiringTest(block: suspend (ContextFixture) -> Unit) {
        ContextFixture().use { fixture -> runBlocking { block(fixture) } }
    }

    private fun publicContextWithLlm(
        fixture: ContextFixture,
        stub: LlmHttpStub,
    ): AlcyoneContext =
        AlcyoneContextFactory.create(
            fixture.config().copy(llm = llmConfig(stub), extraction = ExtractionConfig(idleEnabled = false)),
            fixture.vfsConfig(),
        )

    private fun internalContextWithLlm(
        fixture: ContextFixture,
        stub: LlmHttpStub,
        wakeup: () -> Unit,
    ): AlcyoneContext =
        AlcyoneContextFactory.create(
            contextConfig = fixture.config().copy(llm = llmConfig(stub), extraction = ExtractionConfig(idleEnabled = false)),
            vfsConfig = fixture.vfsConfig(),
            probe = AlcyoneContextFactory.StartProbe { },
            organizationWakeup = OrganizationWakeup { wakeup() },
        )

    private fun llmConfig(
        stub: LlmHttpStub,
        jsonMode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
        includeExtractionTask: Boolean = true,
    ): LlmConfig =
        LlmConfig(
            profiles =
                mapOf(
                    "extraction" to
                        LlmProfileConfig(
                            name = "extraction",
                            baseUrl = stub.baseUrl,
                            apiKey = KEY_MARKER,
                            model = "fast-model",
                            contextWindowTokens = 64_000,
                            jsonMode = jsonMode,
                            maxAttempts = 1,
                        ),
                ),
            tasks =
                if (includeExtractionTask) {
                    mapOf(
                        LlmTask.EXTRACTION to
                            LlmTaskConfig(profile = "extraction", maxInputTokens = 12_000, maxOutputTokens = 3_000),
                    )
                } else {
                    emptyMap()
                },
        )

    private fun turn(index: Int): ConversationTurn =
        ConversationTurn(
            runtimeId = session.runtimeId,
            agentId = session.agentId,
            sessionId = session.sessionId,
            sessionTurnId = "turn-$index",
            timestamp = base.plusSeconds(index.toLong()),
            messages = listOf(ChatMessage(ChatRole.USER, "hello-$index")),
        )

    private suspend fun rawRows(impl: AlcyoneContextImpl): List<com.github.noahshen.alcyone.context.memory.api.RawMemory> =
        impl.databaseForTesting()!!.read { read -> read.rawMemories.scanPage(0, 20) }

    private suspend fun statuses(impl: AlcyoneContextImpl): List<ExtractionStatus> =
        impl
            .databaseForTesting()!!
            .read { read ->
                read.conversations.sessionWindow(
                    sessionKey = session,
                    beforeTimestamp = null,
                    beforeId = null,
                    limit = 100,
                )
            }.map { turn -> turn.extractionStatus }

    private suspend fun awaitUntil(condition: suspend () -> Boolean) {
        withTimeout(15_000L) {
            while (!condition()) delay(20)
        }
    }

    private fun repositoryRoot(): File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("无法定位仓库根（settings.gradle.kts）")

    private companion object {
        const val KEY_MARKER = "ct11-b3-unique-key-marker-7c21"
    }
}
