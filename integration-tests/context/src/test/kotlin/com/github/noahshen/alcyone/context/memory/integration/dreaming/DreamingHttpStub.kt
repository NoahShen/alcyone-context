package com.github.noahshen.alcyone.context.memory.integration.dreaming

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 真实 OpenAI-compatible HTTP endpoint；响应中的 turn id 取自公开 Prompt，不固定数据库 ID。 */
internal class DreamingHttpStub(
    private val contextDatabase: Path,
) : AutoCloseable {
    data class RecordedRequest(
        val stage: String,
        val model: String,
        val authorization: String?,
        val responseFormat: String,
        val body: String,
    ) {
        fun routingSummary(): String =
            "stage=$stage; format=$responseFormat; " +
                "rawSchema=${"DreamingEvidenceRawOutputV1" in body}; " +
                "noveltySchema=${"DreamingEvidenceNoveltyOutputV1" in body}; " +
                "conversationSchema=${"DreamingEvidenceConversationNeedOutputV1" in body}"
    }

    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "dreaming-http-stub").apply { isDaemon = true }
        }
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).also {
            it.executor = executor
            it.createContext("/") { exchange ->
                try {
                    respond(exchange)
                } catch (failure: Throwable) {
                    failures += failure.stackTraceToString().take(2000)
                    val payload =
                        failure
                            .stackTraceToString()
                            .take(4000)
                            .toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(400, payload.size.toLong())
                    exchange.responseBody.use { stream -> stream.write(payload) }
                }
            }
            it.start()
        }
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private val failures = CopyOnWriteArrayList<String>()
    private val reflectionFailures = AtomicInteger(-1)
    private val reflection503s = AtomicInteger(0)
    private val reflection429s = AtomicInteger(0)
    private val successfulReflectionSources = AtomicInteger(0)
    private val failedReflectionTurns = mutableSetOf<Long>()

    fun endpoint(): String = "http://127.0.0.1:${server.address.port}/v1/chat/completions"

    fun requests(): List<RecordedRequest> = calls.toList()

    fun failures(): List<String> = failures.toList()

    fun decodedPrompt(request: RecordedRequest): String = userPrompt(request.body)

    fun failReflectionOnce(afterSuccessfulCalls: Int = 0) {
        require(afterSuccessfulCalls >= 0)
        reflectionFailures.set(afterSuccessfulCalls)
        successfulReflectionSources.set(0)
    }

    fun remainingReflectionFailures(): Int = reflectionFailures.get()

    fun reflection503Once() {
        reflection503s.set(1)
    }

    fun reflection429Once() {
        reflection429s.set(2)
    }

    /** 按公开 Reflection 来源标记一个 turn；下一次遇到相同来源时成功，适配动态预算分批。 */
    fun failReflectionAtTurnOnce(turnId: Long) {
        require(turnId > 0)
        failedReflectionTurns += turnId
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Dreaming HTTP executor did not stop" }
    }

    private fun respond(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val taskName = jsonStringField(body, "name")
        val stage =
            when {
                "DreamingEvidenceRawOutputV1" in body ||
                    "# Dreaming Evidence Raw Prompt v1" in body -> "DREAMING_RAW"
                "DreamingEvidenceConversationNeedOutputV1" in body ||
                    "# Dreaming Evidence Conversation Need Prompt v1" in body -> "DREAMING_CONVERSATION"
                "DreamingEvidenceNoveltyOutputV1" in body ||
                    "# Dreaming Evidence Novelty Prompt v1" in body -> "DREAMING_EVIDENCE"
                else ->
                    when (taskName) {
                        "dreaming_topic" -> "DREAMING_TOPIC"
                        "memory_search" -> "MEMORY_SEARCH"
                        "dreaming_reflection" -> "DREAMING_REFLECTION"
                        "candidate_scanner" -> "ORGANIZATION_CANDIDATE"
                        "governance" -> "GOVERNANCE"
                        "memory_map" -> "MEMORY_MAP"
                        else -> error("unknown Dreaming request: task=$taskName; request=${body.take(500)}")
                    }
            }
        val model = checkNotNull(jsonStringField(body, "model")) { "missing model in Dreaming request" }
        val responseFormat =
            when {
                body.contains("\"json_schema\"") -> "json_schema"
                body.contains("\"json_object\"") -> "json_object"
                else -> "prompt_only"
            }
        calls +=
            RecordedRequest(
                stage = stage,
                model = model,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                responseFormat = responseFormat,
                body = body,
            )
        if (stage == "DREAMING_REFLECTION") {
            if (reflection503s.compareAndSet(1, 0)) {
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
                return
            }
            if (reflection429s.getAndUpdate { value -> if (value > 0) value - 1 else 0 } > 0) {
                exchange.responseHeaders.add("Retry-After", "0")
                val payload = """{"error":{"code":"rate_limit_exceeded"}}""".toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(429, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
                return
            }
            val sources =
                Regex("""<sourceTurnIds>([0-9,]+)</sourceTurnIds>""")
                    .find(userPrompt(body))
                    ?.groupValues
                    ?.get(1)
                    ?.split(',')
                    ?.count { it.isNotEmpty() } ?: 0
            val previous = successfulReflectionSources.getAndAdd(sources)
            if (reflectionFailures.get() >= 0 && previous >= reflectionFailures.get()) {
                reflectionFailures.set(-1)
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
                return
            }
            val turnId = turnId(userPrompt(body))
            if (failedReflectionTurns.remove(turnId)) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
                return
            }
        }
        val content = responseFor(stage, userPrompt(body))
        val response =
            """{"choices":[{"message":{"content":${content.asJsonString()}},"finish_reason":"stop"}]}"""
                .toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }

    private fun responseFor(
        stage: String,
        request: String,
    ): String =
        when (stage) {
            "DREAMING_TOPIC" -> {
                val turnIds = Regex("""<turn id="([0-9]+)"""").findAll(request).map { it.groupValues[1] }.joinToString(",")
                check(turnIds.isNotEmpty()) { "Topic request contains no turn IDs" }
                """{"topics":[{"title":"Kotlin retry","description":"Durable Kotlin retry policy","keywords":["kotlin","retry"],"sourceTurnIds":[$turnIds]}]}"""
            }
            "DREAMING_EVIDENCE" -> """{"decision":"NEEDS_EVIDENCE","coveredOrganizedIds":[]}"""
            "DREAMING_RAW" -> {
                val ids = Regex("""<raw_memory id="([^"]+)"""").findAll(request).map { it.groupValues[1] }.toList()
                check(ids.isNotEmpty()) { "Dreaming raw scanner received no candidates" }
                Json.encodeToString(JsonObject(mapOf("candidate_ids" to JsonArray(ids.map(::JsonPrimitive)))))
            }
            "MEMORY_SEARCH" -> {
                val ids = Regex("""<candidate id="([^"]+)"""").findAll(request).map { it.groupValues[1] }.toSet()
                check(ids.isNotEmpty()) { "Memory Search request contains no candidates: $request" }
                Json.encodeToString(JsonObject(mapOf("candidate_ids" to JsonArray(ids.map(::JsonPrimitive)))))
            }
            "DREAMING_CONVERSATION" -> {
                """{"memoryIds":[]}"""
            }
            "DREAMING_REFLECTION" -> {
                val turnIds =
                    Regex("""<sourceTurnIds>([0-9,]+)</sourceTurnIds>""").find(request)?.groupValues?.get(1)
                        ?: error("Reflection request contains no source turn IDs")
                turnIds.split(',').joinToString(prefix = """{"insights":[""", postfix = """]}""") { id ->
                    """{"content":"Retry durable Kotlin operations for turn $id with bounded backoff.","sourceTurnIds":[$id]}"""
                }
            }
            "ORGANIZATION_CANDIDATE" -> {
                val ids = Regex("""<candidate id="([^"]+)"""").findAll(request).map { it.groupValues[1] }.toSet()
                ids.joinToString(prefix = """{"candidate_ids":[""", postfix = """]}""") { "\"$it\"" }
            }
            "GOVERNANCE" -> {
                val target = Regex("""<candidate id="([^"]+)"""").find(request)?.groupValues?.get(1)
                if (target == null) {
                    """{"action":"NEW","content":"Retry durable Kotlin operations with bounded backoff.","domains":["kotlin"]}"""
                } else {
                    """{"action":"MERGE","targetMemoryId":"$target","content":"Retry durable Kotlin operations with bounded backoff."}"""
                }
            }
            "MEMORY_MAP" -> """{"updated":false,"version":1,"memoryMap":{"version":1,"content":"CT26","memoryCount":1}}"""
            else -> error("unhandled stage $stage")
        }

    private fun turnId(request: String): Long =
        Regex("""<sourceTurnIds>([0-9,]+)</sourceTurnIds>""")
            .find(request)
            ?.groupValues
            ?.get(1)
            ?.substringAfterLast(',')
            ?.toLong()
            ?: error("Reflection request contains no source turn IDs")

    private fun userPrompt(body: String): String =
        Json
            .parseToJsonElement(body)
            .jsonObject
            .getValue("messages")
            .jsonArray
            .last()
            .jsonObject
            .getValue("content")
            .jsonPrimitive.content

    private fun jsonStringField(
        body: String,
        field: String,
    ): String? = Regex("\"$field\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)

    private fun String.asJsonString(): String =
        buildString {
            append('"')
            this@asJsonString.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }
}
