package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionBuildResult
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionCommitException
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionCommitter
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionPrompt
import com.github.noahshen.alcyone.context.memory.core.extraction.PreExtractionContextBuilder
import com.github.noahshen.alcyone.context.memory.core.id.MemoryIdGenerator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CT11-B2：Runtime `DefaultExtractionRunner` 与 CT10 Builder / B1 Validator·Committer 的串联。
 *
 * 覆盖：空/非空成功、Builder 窗口与发送请求一致、非法响应 0 写、LLM 错误传播、单 Turn 循环与行数校验、
 * 固定开销配置错误、防御迭代上限、唤醒失败不回滚、取消传播、无 Pending 无副作用。
 */
class DefaultExtractionRunnerTest {
    private val session = SessionKey("rt", "agent", "s1")
    private val base: Instant = Instant.parse("2026-10-31T02:00:00Z")
    private val committedAt: Instant = base.plusSeconds(9_000)

    @Test
    fun `an empty memories response completes targets without raw or wakeup`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            insertTurn(database, session, "t2", base.plusSeconds(1), TurnStatus.PENDING)
            val wakeups = AtomicInteger()
            val llm = FakeLlmClient { response("""{"memories":[]}""") }
            val harness = harness(database, llm, wakeup = { wakeups.incrementAndGet() })

            harness.runner.execute(session)

            assertEquals(1, llm.calls)
            assertTrue(raws(database).isEmpty())
            assertFalse(hasOrganizationPending(database))
            assertEquals(
                listOf(ExtractionStatus.COMPLETE, ExtractionStatus.COMPLETE),
                statuses(database),
            )
            assertEquals(0, wakeups.get())
        }

    @Test
    fun `a non empty response commits raw pending and targets and wakes organization once`() =
        withTriggerDatabase { database ->
            // History 早于锚点：锚点是 t2（最新 pending），窗口为 [t1(complete), t2(pending)]。
            insertTurn(database, session, "t1", base, TurnStatus.COMPLETE)
            insertTurn(database, session, "t2", base.plusSeconds(1), TurnStatus.PENDING)
            val wakeups = AtomicInteger()
            val llm =
                FakeLlmClient {
                    response("""{"memories":[{"content":"memory-one","source_turn_ids":[1,2]}]}""")
                }
            val harness = harness(database, llm, wakeup = { wakeups.incrementAndGet() })

            harness.runner.execute(session)

            val rows = raws(database)
            assertEquals(1, rows.size)
            assertEquals("memory-one", rows.single().content)
            assertTrue(hasOrganizationPending(database))
            assertEquals(
                listOf(
                    MemorySourceRef.ConversationTurnRef("rt", "agent", "s1", "t1"),
                    MemorySourceRef.ConversationTurnRef("rt", "agent", "s1", "t2"),
                ),
                rows.single().sources,
            )
            assertEquals(
                listOf(ExtractionStatus.COMPLETE, ExtractionStatus.COMPLETE),
                statuses(database),
            )
            assertEquals(1, wakeups.get(), "rawCount > 0 必须唤醒一次")
        }

    @Test
    fun `the request sent by the runner matches the builder window and requestFor`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            insertTurn(database, session, "t2", base.plusSeconds(1), TurnStatus.COMPLETE)
            insertTurn(database, session, "t3", base.plusSeconds(2), TurnStatus.PENDING)
            val llm = FakeLlmClient { response("""{"memories":[]}""") }
            val harness = harness(database, llm)

            val expectedContext = (harness.builder.build(session) as ExtractionBuildResult.Built).context
            val expectedRequest = harness.builder.requestFor(expectedContext)

            harness.runner.execute(session)

            val sent = llm.requests.single()
            assertEquals(expectedRequest, sent, "Runner 发送的请求必须与 Builder.requestFor 完全一致")
            assertEquals(LlmTask.EXTRACTION, sent.task)
            assertEquals(LlmResponseFormat.JSON_SCHEMA, sent.responseFormat)
            assertEquals(ExtractionPrompt.schema(), sent.schema)
            assertEquals(null, sent.maxOutputTokens)
            assertEquals(2, sent.messages.size)
            assertEquals("system", sent.messages[0].role)
            assertEquals(ExtractionPrompt.systemPrompt(), sent.messages[0].content)
            assertEquals(
                ExtractionPrompt.messages(expectedContext.turns)[1].content,
                sent.messages[1].content,
                "user 消息必须与 Builder 选中的窗口一致",
            )
            val user = sent.messages[1].content
            assertEquals(3, Regex("<turn id=").findAll(user).count())
            assertEquals(2, Regex("target=\"true\"").findAll(user).count())
            assertTrue(user.indexOf("id=\"1\"") < user.indexOf("id=\"2\""), "顺序必须与真实会话一致")
        }

    @Test
    fun `invalid model output is rejected with invalid response and writes nothing`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            val wakeups = AtomicInteger()
            val invalidPayloads =
                listOf(
                    "not json",
                    """{"memories":[],"extra":1}""",
                    """{"memories":[{"content":"x","source_turn_ids":[99]}]}""",
                )
            invalidPayloads.forEach { payload ->
                val llm = FakeLlmClient { response(payload) }
                val harness = harness(database, llm, wakeup = { wakeups.incrementAndGet() })
                val failure = assertFailsWith<LlmException>(payload) { harness.runner.execute(session) }
                assertEquals(LlmErrorCode.INVALID_RESPONSE, failure.errorCode, payload)
                assertTrue(raws(database).isEmpty(), payload)
                assertFalse(hasOrganizationPending(database), payload)
                assertEquals(listOf(ExtractionStatus.PENDING), statuses(database), payload)
            }
            assertEquals(0, wakeups.get())
        }

    @Test
    fun `an llm failure propagates unchanged and writes nothing`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            val failure = LlmException(LlmErrorCode.TIMEOUT, "provider timed out")
            val llm = FakeLlmClient { throw failure }
            val harness = harness(database, llm)

            val thrown = assertFailsWith<LlmException> { harness.runner.execute(session) }
            assertSame(failure, thrown)
            assertTrue(raws(database).isEmpty())
            assertEquals(listOf(ExtractionStatus.PENDING), statuses(database))
        }

    @Test
    fun `a single over budget anchor is marked failed and the next window succeeds`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            insertTurn(database, session, "t2", base.plusSeconds(1), TurnStatus.PENDING)
            val before = contextOf(database)
            // 标记失败之后的窗口视图：anchor（id=1）已 failed，剩余 Target 只有 id=2。
            val afterFailure =
                ExtractionContext(
                    session,
                    before.turns.map { turn ->
                        if (turn.id == 1L) turn.copy(extractionStatus = ExtractionStatus.FAILED) else turn
                    },
                )
            val builder =
                ScriptedBuilder { index ->
                    if (index == 0) {
                        ExtractionBuildResult.SingleTurnOverBudget(anchorTurnId = 1L, requiredTotalTokens = 9_999)
                    } else {
                        ExtractionBuildResult.Built(afterFailure)
                    }
                }
            val wakeups = AtomicInteger()
            val llm = FakeLlmClient { response("""{"memories":[{"content":"memory","source_turn_ids":[2]}]}""") }
            val harness = harness(database, llm, builder = builder, wakeup = { wakeups.incrementAndGet() })

            harness.runner.execute(session)

            assertEquals(1, raws(database).size)
            assertEquals(listOf(ExtractionStatus.FAILED, ExtractionStatus.COMPLETE), statuses(database))
            assertEquals(1, wakeups.get())
        }

    @Test
    fun `a single over budget marker with zero updated rows is a state conflict`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.COMPLETE)
            val builder =
                ScriptedBuilder { ExtractionBuildResult.SingleTurnOverBudget(anchorTurnId = 1L, requiredTotalTokens = 1) }
            val llm = FakeLlmClient { response("""{"memories":[]}""") }
            val harness = harness(database, llm, builder = builder)

            val failure = assertFailsWith<ExtractionCommitException> { harness.runner.execute(session) }
            assertTrue(failure.message.orEmpty().contains("state changed"), failure.message.orEmpty())
            assertTrue(raws(database).isEmpty())
            assertEquals(listOf(ExtractionStatus.COMPLETE), statuses(database))
        }

    @Test
    fun `fixed overhead over budget is a configuration error with no writes`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            val builder =
                ScriptedBuilder { ExtractionBuildResult.FixedOverheadOverBudget(requiredTotalTokens = 70_000) }
            val llm = FakeLlmClient { response("""{"memories":[]}""") }
            val harness = harness(database, llm, builder = builder)

            val failure = assertFailsWith<ContextConfigException> { harness.runner.execute(session) }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertTrue(raws(database).isEmpty())
            assertEquals(listOf(ExtractionStatus.PENDING), statuses(database))
        }

    @Test
    fun `the window iteration limit prevents an endless single turn loop`() =
        withTriggerDatabase { database ->
            repeat(5) { index -> insertTurn(database, session, "t${index + 1}", base.plusSeconds(index.toLong())) }
            val builder =
                ScriptedBuilder { index ->
                    ExtractionBuildResult.SingleTurnOverBudget(anchorTurnId = index + 1L, requiredTotalTokens = 1)
                }
            val llm = FakeLlmClient { response("""{"memories":[]}""") }
            val harness = harness(database, llm, builder = builder, maxIterations = 3)

            val failure = assertFailsWith<ExtractionExecutionException> { harness.runner.execute(session) }
            assertTrue(failure.message.orEmpty().contains("iterations"), failure.message.orEmpty())
            assertEquals(3, builder.calls, "达到上限即停止")
            assertTrue(raws(database).isEmpty())
            assertEquals(3, statuses(database).count { it == ExtractionStatus.FAILED })
        }

    @Test
    fun `a wakeup failure does not roll back the committed batch`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            val wakeups = AtomicInteger()
            val llm = FakeLlmClient { response("""{"memories":[{"content":"memory","source_turn_ids":[1]}]}""") }
            val harness =
                harness(
                    database,
                    llm,
                    wakeup = {
                        wakeups.incrementAndGet()
                        throw IllegalStateException("wakeup boom ct11-b2-marker")
                    },
                )

            harness.runner.execute(session)

            assertEquals(1, wakeups.get(), "唤醒失败不得重试")
            assertEquals(1, raws(database).size, "活跃提交必须已落库")
            assertTrue(hasOrganizationPending(database))
            assertEquals(listOf(ExtractionStatus.COMPLETE), statuses(database))
        }

    @Test
    fun `cancellation from the llm propagates unchanged`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base, TurnStatus.PENDING)
            val llm = FakeLlmClient { throw CancellationException("ct11-b2-cancel") }
            val harness = harness(database, llm)

            assertFailsWith<CancellationException> { harness.runner.execute(session) }
            assertTrue(raws(database).isEmpty())
            assertEquals(listOf(ExtractionStatus.PENDING), statuses(database))
        }

    @Test
    fun `no pending context returns without llm write or wakeup`() =
        withTriggerDatabase { database ->
            val wakeups = AtomicInteger()
            val llm = FakeLlmClient { response("""{"memories":[]}""") }
            val harness =
                harness(
                    database,
                    llm,
                    builder = ScriptedBuilder { ExtractionBuildResult.NoPending },
                    wakeup = { wakeups.incrementAndGet() },
                )

            harness.runner.execute(session)

            assertEquals(0, llm.calls)
            assertTrue(raws(database).isEmpty())
            assertEquals(0, wakeups.get())
        }

    // ------------------------------------------------------------------ 工具

    private class Harness(
        val builder: DefaultPreExtractionContextBuilder,
        val runner: DefaultExtractionRunner,
    )

    private fun harness(
        database: ContextDatabase,
        llmClient: LlmClient,
        builder: PreExtractionContextBuilder? = null,
        wakeup: OrganizationWakeup = OrganizationWakeup { },
        maxIterations: Int = 8,
    ): Harness {
        val realBuilder =
            DefaultPreExtractionContextBuilder(
                database = database,
                llmConfig = llmConfig(),
                extractionConfig = ExtractionConfig(initialWindowTurns = 32),
            )
        val committer =
            ExtractionCommitter(
                database = database,
                idGenerator = SequentialIdGenerator(),
                clock = Clock.fixed(committedAt, ZoneOffset.UTC),
            )
        val runner =
            DefaultExtractionRunner(
                builder = builder ?: realBuilder,
                requestFor = realBuilder::requestFor,
                llmClient = llmClient,
                validator = JsonExtractionResultValidator(),
                committer = committer,
                database = database,
                maxWindowIterations = maxIterations,
                wakeup = wakeup,
            )
        return Harness(realBuilder, runner)
    }

    private fun llmConfig(): LlmConfig =
        LlmConfig(
            profiles =
                mapOf(
                    "extraction" to
                        LlmProfileConfig(
                            name = "extraction",
                            baseUrl = "https://llm.example.com/v1",
                            apiKey = "ct11-b2-llm-key-marker",
                            model = "fast-model",
                            contextWindowTokens = 64_000,
                            jsonMode = LlmResponseFormat.JSON_SCHEMA,
                        ),
                ),
            tasks =
                mapOf(
                    LlmTask.EXTRACTION to
                        LlmTaskConfig(profile = "extraction", maxInputTokens = 12_000, maxOutputTokens = 3_000),
                ),
        )

    private fun response(content: String): LlmResponse = LlmResponse(content = content, usage = null, finishReason = "stop")

    private suspend fun contextOf(database: ContextDatabase): ExtractionContext =
        ExtractionContext(
            session,
            database.read { read ->
                read.conversations.sessionWindow(sessionKey = session, beforeTimestamp = null, beforeId = null, limit = 100)
            },
        )

    private suspend fun raws(database: ContextDatabase) = database.read { read -> read.rawMemories.scanPage(0, 100) }

    private suspend fun hasOrganizationPending(database: ContextDatabase): Boolean = database.read { read -> read.pending.hasAnyPending() }

    private suspend fun statuses(database: ContextDatabase): List<ExtractionStatus> =
        database.read { read ->
            read.conversations
                .sessionWindow(sessionKey = session, beforeTimestamp = null, beforeId = null, limit = 100)
                .map { turn -> turn.extractionStatus }
        }

    /** 记录调用次数并按脚本返回结果的 Builder 替身。 */
    private class ScriptedBuilder(
        private val next: (Int) -> ExtractionBuildResult,
    ) : PreExtractionContextBuilder {
        var calls: Int = 0
            private set

        override suspend fun build(sessionKey: SessionKey): ExtractionBuildResult {
            val result = next(calls)
            calls++
            return result
        }
    }

    private class FakeLlmClient(
        private val respond: suspend (LlmRequest) -> LlmResponse,
    ) : LlmClient {
        val requests: MutableList<LlmRequest> = CopyOnWriteArrayList()
        var calls: Int = 0
            private set

        override suspend fun complete(request: LlmRequest): LlmResponse {
            calls++
            requests += request
            return respond(request)
        }
    }

    private class SequentialIdGenerator : MemoryIdGenerator {
        private val counter = AtomicInteger(0)

        override fun nextRawMemoryId(): String = "raw-${counter.incrementAndGet()}"

        override fun nextOrganizedMemoryId(): String = "org-${counter.incrementAndGet()}"
    }
}
