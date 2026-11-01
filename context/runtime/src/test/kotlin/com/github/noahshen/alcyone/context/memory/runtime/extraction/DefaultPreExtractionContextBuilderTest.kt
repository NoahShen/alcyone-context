package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.core.ContextDatabase
import com.github.noahshen.alcyone.context.memory.core.ExtractionStatus
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionBuildResult
import com.github.noahshen.alcyone.context.memory.core.extraction.ExtractionContext
import com.github.noahshen.alcyone.context.memory.core.llm.LlmMessage
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CT10-B2：`DefaultPreExtractionContextBuilder` 的真实 SQLite 快照/折半验收。
 *
 * 覆盖：无 Pending、锚点（timestamp DESC/id DESC/同 timestamp/跨 Session/较新 complete）、
 * 连续窗口与 History、按实际 K 折半、单 Turn 超预算、固定开销超预算、同快照（并发补录不混入）、
 * 预算等号、JSON 模式与 schema 处理、构造期 fail-fast。
 */
class DefaultPreExtractionContextBuilderTest {
    private val session = SessionKey("rt", "agent", "s1")
    private val otherSession = SessionKey("rt", "agent", "s2")
    private val base: Instant = Instant.parse("2026-10-30T02:00:00Z")

    @Test
    fun `no pending returns NoPending without estimating`() =
        withTriggerDatabase { database ->
            val estimator = RecordingEstimator { turns -> turns * 100 }
            val builder = builder(database, estimator = estimator)

            assertEquals(ExtractionBuildResult.NoPending, builder.build(session))
            assertEquals(0, estimator.promptCalls, "无 pending 不得渲染/估算")

            insertTurn(database, session, "c1", base, TurnStatus.COMPLETE)
            insertTurn(database, session, "f1", base.plusSeconds(1), TurnStatus.FAILED)
            assertEquals(ExtractionBuildResult.NoPending, builder.build(session))
            assertEquals(0, estimator.promptCalls)
        }

    @Test
    fun `the anchor is the latest pending by timestamp then id within the session`() =
        withTriggerDatabase { database ->
            val builder = builder(database, estimator = RecordingEstimator { turns -> turns * 100 })
            insertTurn(database, session, "t1", base)
            insertTurn(database, session, "t2", base.plusSeconds(5))
            insertTurn(database, otherSession, "o1", base.plusSeconds(10))

            var built = builder.build(session) as ExtractionBuildResult.Built
            assertEquals(listOf(1L, 2L), ids(built.context), "只含本 Session；锚点为最新 pending")

            // 同 timestamp、更大 id → 锚点后移。
            insertTurn(database, session, "t3", base.plusSeconds(5))
            built = builder.build(session) as ExtractionBuildResult.Built
            assertEquals(listOf(1L, 2L, 4L), ids(built.context))

            // 较新的 complete 不抢锚点，也不进入锚点之后的窗口。
            insertTurn(database, session, "t4", base.plusSeconds(30), TurnStatus.COMPLETE)
            built = builder.build(session) as ExtractionBuildResult.Built
            assertEquals(listOf(1L, 2L, 4L), ids(built.context))
        }

    @Test
    fun `the window is continuous and keeps complete and failed history`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base)
            insertTurn(database, session, "t2", base.plusSeconds(1), TurnStatus.COMPLETE)
            insertTurn(database, session, "t3", base.plusSeconds(2))
            insertTurn(database, session, "t4", base.plusSeconds(3), TurnStatus.FAILED)
            insertTurn(database, session, "t5", base.plusSeconds(4))

            val builder = builder(database, estimator = RecordingEstimator { turns -> turns * 100 }, window = 5)
            val built = builder.build(session) as ExtractionBuildResult.Built
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(built.context), "按真实会话顺序，含中间 complete/failed")
            assertEquals(
                listOf(
                    ExtractionStatus.PENDING,
                    ExtractionStatus.COMPLETE,
                    ExtractionStatus.PENDING,
                    ExtractionStatus.FAILED,
                    ExtractionStatus.PENDING,
                ),
                built.context.turns.map { it.extractionStatus },
            )

            // 窗口外旧 Pending 不处理。
            val narrowed = builder(database, estimator = RecordingEstimator { turns -> turns * 100 }, window = 3)
            val narrowedBuilt = narrowed.build(session) as ExtractionBuildResult.Built
            assertEquals(listOf(3L, 4L, 5L), ids(narrowedBuilt.context))
        }

    @Test
    fun `halving uses the actual read size and stops at the first fit`() =
        withTriggerDatabase { database ->
            repeat(8) { index -> insertTurn(database, session, "t$index", base.plusSeconds(index.toLong())) }

            val estimator = RecordingEstimator { turns -> turns * 100 }
            val builder = builder(database, estimator = estimator, task = task(maxInput = 500), window = 8)
            val built = builder.build(session) as ExtractionBuildResult.Built

            // 首次 8 条超限 → 折半到 4 条首次满足即停止（0 为固定开销那次）。
            assertEquals(listOf(0, 8, 4), estimator.observedTurnCounts)
            assertEquals(listOf(5L, 6L, 7L, 8L), ids(built.context), "最近的 4 条，窗口外 Pending 未处理")
        }

    @Test
    fun `a single over budget turn is reported without writing status`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base)
            val estimator = RecordingEstimator { turns -> turns * 1_000 }
            val builder = builder(database, estimator = estimator, task = task(maxInput = 500))

            val result = builder.build(session) as ExtractionBuildResult.SingleTurnOverBudget
            assertEquals(1L, result.anchorTurnId)
            assertTrue(result.requiredTotalTokens > 0)

            val status = database.read { read -> read.conversations.latestPending(session)?.extractionStatus }
            assertEquals(ExtractionStatus.PENDING, status, "Builder 不得写 failed/complete")
        }

    @Test
    fun `fixed overhead over budget is reported before reading the window`() =
        withTriggerDatabase { database ->
            repeat(3) { index -> insertTurn(database, session, "t$index", base.plusSeconds(index.toLong())) }

            val estimator = RecordingEstimator(overheadTokens = 1_000) { turns -> turns * 10 }
            val builder = builder(database, estimator = estimator, task = task(maxInput = 500))

            val result = builder.build(session) as ExtractionBuildResult.FixedOverheadOverBudget
            assertTrue(result.requiredTotalTokens > 0)
            assertEquals(listOf(0), estimator.observedTurnCounts, "只算基础请求，不读窗口")
        }

    @Test
    fun `a concurrent insert is not visible inside the read snapshot`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base)
            insertTurn(database, session, "t2", base.plusSeconds(2))

            val estimator = RecordingEstimator { turns -> turns * 100 }
            val builder = builder(database, estimator = estimator, window = 32)

            val blocked = CountDownLatch(1)
            val release = CountDownLatch(1)
            builder.betweenReadsForTesting = {
                blocked.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS), "快照屏障必须有界放行")
            }

            val writer =
                thread {
                    assertTrue(blocked.await(10, TimeUnit.SECONDS), "读取必须已进入快照回调")
                    // 另一连接在快照建立后补录一条**中间** pending（若可见会插到 t1 与 t2 之间）。
                    runBlocking { insertTurn(database, session, "t-new", base.plusSeconds(1)) }
                    release.countDown()
                }
            val built = builder.build(session) as ExtractionBuildResult.Built
            writer.join(10_000)

            assertEquals(listOf(1L, 2L), ids(built.context), "同一快照不得混入并发补录的 Turn")
            val pendingAfter = database.read { read -> read.conversations.countPending(session) }
            assertEquals(3, pendingAfter, "补录确实已提交（只是不在快照内）")
        }

    @Test
    fun `budget equality counts as fits`() =
        withTriggerDatabase { database ->
            repeat(3) { index -> insertTurn(database, session, "t$index", base.plusSeconds(index.toLong())) }

            // input = 300 == maxInputTokens；required = 300 + 100 + 0 = 400 == contextWindowTokens。
            val estimator = RecordingEstimator { turns -> turns * 100 }
            val builder =
                builder(
                    database,
                    estimator = estimator,
                    task = task(maxInput = 300, maxOutput = 100),
                    profile = profile(contextWindowTokens = 400),
                    safetyMarginPercent = 0,
                    window = 3,
                )

            val built = builder.build(session) as ExtractionBuildResult.Built
            assertEquals(3, built.context.turns.size)
        }

    @Test
    fun `json schema mode sends the v1 schema while json object mode does not`() =
        withTriggerDatabase { database ->
            insertTurn(database, session, "t1", base)

            val schemaEstimator = RecordingEstimator { turns -> turns * 10 }
            val schemaBuilder =
                builder(
                    database,
                    estimator = schemaEstimator,
                    profile = profile(jsonMode = LlmResponseFormat.JSON_SCHEMA),
                )
            assertTrue(schemaBuilder.build(session) is ExtractionBuildResult.Built)
            assertTrue(schemaEstimator.schemaSeen.all { it != null }, "JSON_SCHEMA 必须携带 schema")
            assertTrue(schemaEstimator.schemaSeen.any { it != null })

            val objectEstimator = RecordingEstimator { turns -> turns * 10 }
            val objectBuilder =
                builder(
                    database,
                    estimator = objectEstimator,
                    profile = profile(jsonMode = LlmResponseFormat.JSON_OBJECT),
                )
            assertTrue(objectBuilder.build(session) is ExtractionBuildResult.Built)
            assertTrue(objectEstimator.schemaSeen.all { it == null }, "JSON_OBJECT 不得携带 schema")
        }

    @Test
    fun `construction fails fast with config paths and never echoes secrets`() {
        // 1) 缺 EXTRACTION Task：LlmConfig 允许空 tasks，Builder 构造期拒绝。
        val missingTask =
            assertFailsWith<ContextConfigException> { builderWith(llmConfig(task = null)) }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, missingTask.errorCode)
        assertTrue(
            missingTask.message.orEmpty().contains("context.llm.tasks.EXTRACTION"),
            missingTask.message.orEmpty(),
        )

        // 2) Task 引用缺失 Profile：LlmConfig 自身在构造期即拒绝（同一路径口径）；
        //    Builder 另有防御性同路径检查（经已校验的 LlmConfig 不可达）。
        val danglingProfile =
            assertFailsWith<ContextConfigException> {
                llmConfig(task = LlmTaskConfig(profile = "missing", maxInputTokens = 12_000, maxOutputTokens = 3_000))
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, danglingProfile.errorCode)
        assertTrue(
            danglingProfile.message.orEmpty().contains("context.llm.tasks.EXTRACTION.profile"),
            danglingProfile.message.orEmpty(),
        )

        // 3) PROMPT_ONLY：LlmConfig 允许该 jsonMode，Builder 构造期拒绝。
        val promptOnly =
            assertFailsWith<ContextConfigException> {
                builderWith(llmConfig(profiles = mapOf("extraction" to profile(jsonMode = LlmResponseFormat.PROMPT_ONLY))))
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, promptOnly.errorCode)
        assertTrue(
            promptOnly.message.orEmpty().contains("context.llm.profiles.extraction.jsonMode"),
            promptOnly.message.orEmpty(),
        )

        // 三条消息都不含 Key/URL。
        listOf(missingTask, danglingProfile, promptOnly).forEach { failure ->
            assertFalse(failure.message.orEmpty().contains(KEY_MARKER), failure.message.orEmpty())
            assertFalse(failure.message.orEmpty().contains("https://"), failure.message.orEmpty())
        }
    }

    private fun builderWith(config: LlmConfig): DefaultPreExtractionContextBuilder =
        DefaultPreExtractionContextBuilder(
            database = UnusedDatabase,
            llmConfig = config,
            extractionConfig = ExtractionConfig(),
        )

    // ------------------------------------------------------------------ 工具

    private fun builder(
        database: ContextDatabase,
        estimator: TokenEstimator,
        task: LlmTaskConfig = task(),
        profile: LlmProfileConfig = profile(),
        safetyMarginPercent: Int = 20,
        window: Int = 32,
    ): DefaultPreExtractionContextBuilder =
        DefaultPreExtractionContextBuilder(
            database = database,
            llmConfig =
                LlmConfig(
                    profiles = mapOf(profile.name to profile),
                    tasks = mapOf(LlmTask.EXTRACTION to task),
                    safetyMarginPercent = safetyMarginPercent,
                ),
            extractionConfig = ExtractionConfig(initialWindowTurns = window),
            estimator = estimator,
        )

    private fun llmConfig(
        task: LlmTaskConfig? = task(),
        profiles: Map<String, LlmProfileConfig> = mapOf("extraction" to profile()),
    ): LlmConfig =
        LlmConfig(
            profiles = profiles,
            tasks = task?.let { mapOf(LlmTask.EXTRACTION to it) } ?: emptyMap(),
        )

    private fun task(
        maxInput: Int = 12_000,
        maxOutput: Int = 3_000,
    ): LlmTaskConfig = LlmTaskConfig(profile = "extraction", maxInputTokens = maxInput, maxOutputTokens = maxOutput)

    private fun profile(
        jsonMode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
        contextWindowTokens: Int = 64_000,
    ): LlmProfileConfig =
        LlmProfileConfig(
            name = "extraction",
            baseUrl = "https://llm.example.com/v1",
            apiKey = KEY_MARKER,
            model = "fast-model",
            contextWindowTokens = contextWindowTokens,
            jsonMode = jsonMode,
        )

    private fun ids(context: ExtractionContext): List<Long> = context.turns.map { it.id }

    /** 记录每次估算观察到的轮次数（从渲染后的 user 消息里数 `<turn id=`），并按配置返回 token。 */
    private class RecordingEstimator(
        private val overheadTokens: Int = 0,
        private val tokensForTurns: (Int) -> Int,
    ) : TokenEstimator {
        val observedTurnCounts: MutableList<Int> = CopyOnWriteArrayList()
        val schemaSeen: MutableList<String?> = CopyOnWriteArrayList()
        val promptCalls: Int get() = observedTurnCounts.size

        override fun estimateText(text: String): Int = overheadTokens

        override fun estimatePrompt(messages: List<LlmMessage>): Int {
            val userContent = messages.lastOrNull()?.content.orEmpty()
            val turns = Regex("<turn id=").findAll(userContent).count()
            observedTurnCounts += turns
            return overheadTokens + tokensForTurns(turns)
        }

        override fun estimateSchema(schema: String?): Int {
            schemaSeen += schema
            return 0
        }
    }

    /** fail-fast 用例用的占位数据库：构造期不得访问它。 */
    private object UnusedDatabase : ContextDatabase {
        override suspend fun <T> read(block: (com.github.noahshen.alcyone.context.memory.core.repository.ReadContext) -> T): T =
            error("constructor must not touch the database")

        override suspend fun <T> write(block: (com.github.noahshen.alcyone.context.memory.core.repository.TransactionContext) -> T): T =
            error("constructor must not touch the database")
    }

    private companion object {
        const val KEY_MARKER = "ct10-b2-unique-key-marker-5d31"
    }
}
