package com.github.noahshen.alcyone.context.memory.integration

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.api.SessionKey
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseImpl
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.Comparator
import java.util.Properties

/**
 * B3 集成测试夹具：真实文件 SQLite + 真实 [ContextDatabaseImpl]，不接 Runtime。
 *
 * 独立只读连接只用于核对落库内容（`mode=ro`），不参与写入。
 */
internal object B3Fixtures {
    val SESSION: SessionKey = SessionKey("runtime-1", "agent-1", "session-1")
    val T0: Instant = Instant.parse("2026-10-28T00:00:00.000Z")

    fun messages(vararg contents: String): List<ChatMessage> = contents.map { ChatMessage(ChatRole.USER, it) }

    fun raw(
        id: String,
        content: String = "content-$id",
        generatedBy: MemoryOrigin = MemoryOrigin.EXTRACTION,
        sources: List<MemorySourceRef> = emptyList(),
        createdAt: Instant = T0,
    ): RawMemory = RawMemory(id, content, generatedBy, sources, createdAt)

    fun organized(
        id: String,
        content: String = "content-$id",
        domains: List<String> = listOf("domain-a"),
        createdAt: Instant = T0,
        updatedAt: Instant = T0,
    ): OrganizedMemory = OrganizedMemory(id, content, domains, emptyList(), createdAt, updatedAt)

    fun conversationRef(turnId: String = "turn-1"): MemorySourceRef.ConversationTurnRef =
        MemorySourceRef.ConversationTurnRef(SESSION.runtimeId, SESSION.agentId, SESSION.sessionId, turnId)
}

/** 临时库：初始化一次，可多次打开 / 关闭，测试结束递归删除。 */
internal class TestDatabase : AutoCloseable {
    private val directory: Path = Files.createTempDirectory("context-b3-")
    val path: Path = directory.resolve("context.db")

    init {
        ContextDatabaseInitializer.initialize(path)
    }

    fun open(): ContextDatabaseExecutor = ContextDatabaseFactory.open(path)

    fun <T> readOnly(
        sql: String,
        mapper: (ResultSet) -> T,
    ): T =
        DriverManager.getConnection("jdbc:sqlite:${path.toUri()}?mode=ro", Properties()).use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery(sql).use(mapper) }
        }

    fun count(table: String): Long =
        readOnly("SELECT count(*) FROM $table") { rs ->
            rs.next()
            rs.getLong(1)
        }

    fun scalarLong(
        sql: String,
        column: Int = 1,
    ): Long? = readOnly(sql) { rs -> if (rs.next()) rs.getLong(column) else null }

    /**
     * 递归删除临时目录。目录不存在时直接返回；删除失败必须可见：
     * 逐个收集失败路径与异常，全部尝试后再抛出（其余失败以 suppressed 附加），
     * 不吞掉清理问题。`Files.walk` 的流必须关闭，避免句柄泄漏。
     */
    override fun close() {
        if (!Files.exists(directory)) return
        val failures = mutableListOf<Throwable>()
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (failure: Throwable) {
                    failures += IllegalStateException("failed to delete test artifact $path", failure)
                }
            }
        }
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }
}

/**
 * 打开装配并在回调结束后关闭执行器；需要重开或只读核对时直接使用 [TestDatabase]。
 *
 * 回调固定返回 Unit：避免测试方法因最后一个断言表达式返回非 Unit 而被 JUnit 5 静默跳过
 * （必须保持源码 `@Test` 数与 XML `tests` 数一致）。
 */
internal fun withDatabase(block: suspend (ContextDatabaseImpl, ContextDatabaseExecutor, TestDatabase) -> Unit) {
    TestDatabase().use { fixture ->
        fixture.open().use { executor ->
            kotlinx.coroutines.runBlocking { block(ContextDatabaseImpl(executor), executor, fixture) }
        }
    }
}
