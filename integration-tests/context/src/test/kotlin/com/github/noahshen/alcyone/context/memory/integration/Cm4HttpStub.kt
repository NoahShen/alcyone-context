package com.github.noahshen.alcyone.context.memory.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** CM4 全景测试按 System Prompt 路由的 JDK HTTP stub；动态 ID 仅来自 SQLite 只读查询。 */
internal class Cm4HttpStub(
    private val contextDatabase: Path,
    private val blockSecondMap: Boolean = false,
) : AutoCloseable {
    enum class RequestType {
        EXTRACTION,
        ORGANIZATION_CANDIDATE,
        GOVERNANCE,
        MEMORY_MAP,
        SEARCH_SCANNER,
        SEARCH_RERANK,
    }

    data class RecordedRequest(
        val type: RequestType,
        val authorizationPresent: Boolean,
        val body: String,
    )

    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "cm4-http-stub").apply { isDaemon = true }
        }
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getByName(HOST), 0), 0).also { server ->
            server.executor = executor
            server.createContext("/") { exchange -> respond(exchange) }
            server.start()
        }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val extractionCalls = AtomicInteger(0)
    private val governanceCalls = AtomicInteger(0)
    private val mapCalls = AtomicInteger(0)
    private val secondMapEntered = CountDownLatch(1)
    private val releaseSecondMap = CountDownLatch(1)

    val baseUrl: String get() = "http://$HOST:${server.address.port}"

    fun requestTypes(): List<RequestType> = requests.map { request -> request.type }

    fun recordedRequests(): List<RecordedRequest> = requests.toList()

    fun awaitSecondMapEntered(): Boolean = secondMapEntered.await(20, TimeUnit.SECONDS)

    fun releaseSecondMapGate() {
        releaseSecondMap.countDown()
    }

    override fun close() {
        releaseSecondMap.countDown()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun respond(exchange: HttpExchange) {
        val body = exchange.requestBody.use { stream -> String(stream.readBytes(), StandardCharsets.UTF_8) }
        val type = route(body)
        requests +=
            RecordedRequest(
                type = type,
                authorizationPresent = !exchange.requestHeaders.getFirst("Authorization").isNullOrBlank(),
                body = body,
            )
        val content =
            when (type) {
                RequestType.EXTRACTION -> extractionResponse()
                RequestType.ORGANIZATION_CANDIDATE -> """{"candidate_ids":[]}"""
                RequestType.GOVERNANCE -> governanceResponse()
                RequestType.MEMORY_MAP -> mapResponse()
                RequestType.SEARCH_SCANNER -> scannerResponse()
                RequestType.SEARCH_RERANK -> rerankResponse()
            }
        val bytes = ExtractionHttpStub.chatCompletionEnvelope(content).toByteArray(StandardCharsets.UTF_8)
        try {
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { stream -> stream.write(bytes) }
        } catch (_: IOException) {
            // Client cancellation is an expected E2E boundary.
        } finally {
            exchange.close()
        }
    }

    private fun route(body: String): RequestType =
        when {
            "# Extraction Prompt v1" in body -> RequestType.EXTRACTION
            "# Organization Candidate Prompt v1" in body -> RequestType.ORGANIZATION_CANDIDATE
            "# Organization Governance Prompt v1" in body -> RequestType.GOVERNANCE
            "# Memory Map Prompt v1" in body -> RequestType.MEMORY_MAP
            "# Memory Search Scanner Prompt v1" in body -> RequestType.SEARCH_SCANNER
            "# Memory Search Rerank Prompt v1" in body -> RequestType.SEARCH_RERANK
            else -> error("unrecognized CM4 LLM request")
        }

    private fun extractionResponse(): String {
        val call = extractionCalls.incrementAndGet()
        val targetTurnId = pendingTargetTurnId(contextDatabase)
        val content = if (call == 1) FIRST_MEMORY else SECOND_MEMORY
        return "{\"memories\":[{\"content\":\"${escape(content)}\",\"source_turn_ids\":[$targetTurnId]}]}"
    }

    private fun governanceResponse(): String {
        val call = governanceCalls.incrementAndGet()
        return if (call == 1) {
            "{\"action\":\"NEW\",\"targetMemoryId\":null,\"content\":\"${escape(FIRST_MEMORY)}\",\"domains\":[\"domain-a\"]}"
        } else {
            "{\"action\":\"NEW\",\"targetMemoryId\":null,\"content\":\"${escape(SECOND_MEMORY)}\",\"domains\":[\"domain-b\"]}"
        }
    }

    private fun mapResponse(): String {
        val call = mapCalls.incrementAndGet()
        if (blockSecondMap && call == 2) {
            secondMapEntered.countDown()
            check(releaseSecondMap.await(30, TimeUnit.SECONDS)) { "second map gate timed out" }
        }
        val overview = if (call == 1) "CM4 map version one" else "CM4 map version two"
        return "{\"overview\":\"$overview\",\"navigation\":\"Use domain-a for CM4 memories.\",\"domains\":[\"domain-a\"]}"
    }

    private fun scannerResponse(): String {
        val id = firstOrganizedId(contextDatabase)
        return "{\"candidate_ids\":[\"${escape(id)}\"]}"
    }

    private fun rerankResponse(): String {
        val id = firstOrganizedId(contextDatabase)
        return "{\"memory_ids\":[\"${escape(id)}\"]}"
    }

    private fun pendingTargetTurnId(database: Path): Long =
        queryReadOnly(
            database,
            "SELECT COALESCE((SELECT id FROM conversation_turn WHERE extraction_status = 'pending' ORDER BY id DESC LIMIT 1), (SELECT MAX(id) FROM conversation_turn))",
        ) { result ->
            check(result.next()) { "pending target turn id is unavailable" }
            result.getLong(1)
        }

    private companion object {
        const val HOST = "127.0.0.1"
        const val FIRST_MEMORY =
            "CM4 journey memory: the user keeps project notes in alcyone://resources/journey/data.bin and prefers concise navigation."
        const val SECOND_MEMORY =
            "CM4 second version: the user added a finance follow-up and still uses alcyone://resources/journey/data.bin."

        fun escape(text: String): String =
            buildString(text.length + 8) {
                text.forEach { char ->
                    when (char) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(char)
                    }
                }
            }
    }
}

internal fun firstOrganizedId(database: Path): String =
    queryReadOnly(database, "SELECT id FROM organized_memory ORDER BY created_at, id LIMIT 1") { result ->
        check(result.next()) { "organized memory id is unavailable" }
        result.getString(1)
    }

internal fun firstRawId(database: Path): String =
    queryReadOnly(database, "SELECT id FROM raw_memory ORDER BY created_at, id LIMIT 1") { result ->
        check(result.next()) { "raw memory id is unavailable" }
        result.getString(1)
    }

internal fun sqliteLong(
    database: Path,
    sql: String,
): Long =
    queryReadOnly(database, sql) { result ->
        check(result.next()) { "scalar query returned no row: $sql" }
        result.getLong(1)
    }

internal fun sqliteString(
    database: Path,
    sql: String,
): String =
    queryReadOnly(database, sql) { result ->
        check(result.next()) { "scalar query returned no row: $sql" }
        result.getString(1)
    }

internal fun <T> queryReadOnly(
    database: Path,
    sql: String,
    mapper: (ResultSet) -> T,
): T =
    DriverManager.getConnection("jdbc:sqlite:${database.toUri()}?mode=ro", Properties()).use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(sql).use(mapper) }
    }
