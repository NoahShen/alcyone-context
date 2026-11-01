package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextIngestionException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionTimestampText
import com.github.noahshen.alcyone.context.memory.runtime.IngestionLimitAware
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/**
 * 最小 HTTP 入库路由（CT07-B2）。
 *
 * - `POST /v1/conversations/turns`，`Authorization: Bearer <key>` + 六字段 snake_case JSON；
 * - 认证只做静态 Key → runtimeId 绑定；请求体 `runtime_id` 与绑定不一致 → 403；
 * - 限额先于完整反序列化：`Content-Length` 与流式读取双重上限；超限 413 且不进入 Core；
 * - 原始 `timestamp` 用 Core [IngestionTimestampText.parseRequiringOffset] 解析（无 offset → 400）；
 * - 校验/幂等/事务全部复用 [AlcyoneContext.ingestConversation]，不建第二套逻辑；
 * - 响应只含 `{"id":<id>}` 或固定错误码，不含 Key、正文或连接信息。
 */
fun Application.ingestion(
    context: AlcyoneContext,
    registry: IngestionClientRegistry,
) {
    // 限额必须来自当前 Context 实例的真实配置，不能用常量或另行读取配置冒充。
    val limitAware =
        context as? IngestionLimitAware
            ?: throw IllegalStateException("context does not expose ingestion limits (IngestionLimitAware)")
    val maxTurnBytes = limitAware.ingestionMaxTurnBytes
    require(maxTurnBytes > 0) { "context ingestion maxTurnBytes must be positive" }
    val json =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
    routing {
        post("/v1/conversations/turns") {
            val client =
                registry.authenticate(call.request.headers[HttpHeaders.Authorization])
                    ?: return@post call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")

            val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > maxTurnBytes.toLong()) {
                return@post call.respondError(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE")
            }

            val body =
                readBodyUpTo(call.receiveChannel(), maxTurnBytes)
                    ?: return@post call.respondError(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE")
            // 限额（上面两道）之后、JSON 反序列化之前：严格 UTF-8 解码，非法字节一律 400。
            val text =
                decodeStrictUtf8(body)
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "INVALID_TURN")

            val payload =
                try {
                    json.decodeFromString<TurnPayload>(text)
                } catch (failure: Exception) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "INVALID_TURN")
                }

            if (payload.runtimeId != client.runtimeId) {
                return@post call.respondError(HttpStatusCode.Forbidden, "FORBIDDEN")
            }

            val turn =
                try {
                    ConversationTurn.create(
                        runtimeId = payload.runtimeId,
                        agentId = payload.agentId,
                        sessionId = payload.sessionId,
                        sessionTurnId = payload.sessionTurnId,
                        timestamp = IngestionTimestampText.parseRequiringOffset(payload.timestamp),
                        messages = payload.messages.map { ChatMessage(roleOf(it.role), it.content) },
                    )
                } catch (failure: ContextIngestionException) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "INVALID_TURN")
                } catch (failure: IllegalArgumentException) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "INVALID_TURN")
                }

            val result =
                try {
                    context.ingestConversation(turn)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: ContextIngestionException) {
                    val (status, code) = mapIngestion(failure.errorCode)
                    return@post call.respondError(status, code)
                } catch (failure: ContextLifecycleException) {
                    return@post call.respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE")
                } catch (failure: ContextPersistenceException) {
                    if (failure.errorCode == ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE) {
                        return@post call.respondError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE")
                    }
                    return@post call.respondError(HttpStatusCode.InternalServerError, "INTERNAL_ERROR")
                } catch (failure: Exception) {
                    return@post call.respondError(HttpStatusCode.InternalServerError, "INTERNAL_ERROR")
                }

            val status = if (result.status == IngestStatus.CREATED) HttpStatusCode.Created else HttpStatusCode.OK
            call.respondText("""{"id":${result.id}}""", ContentType.Application.Json, status)
        }
    }
}

/**
 * 严格 UTF-8 解码（F2）。
 *
 * 不用 `String(bytes, Charsets.UTF_8)`：它会把非法字节静默替换成 U+FFFD，等于篡改正文。
 * 这里用 `CharsetDecoder` + `CodingErrorAction.REPORT`，非法字节 / 截断序列 / 孤立代理项
 * 一律返回 null，由调用方映射 400；合法 U+FFFD（EF BF BD）是正常字符，不受影响。
 */
private fun decodeStrictUtf8(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: java.nio.charset.CharacterCodingException) {
        null
    }

/** 流式读取请求体；超过 [maxTurnBytes] 返回 null（调用方映射 413），不读取完整请求。 */
private suspend fun readBodyUpTo(
    channel: ByteReadChannel,
    maxTurnBytes: Int,
): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        total += read
        if (total > maxTurnBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun mapIngestion(code: ContextIngestionErrorCode): Pair<HttpStatusCode, String> =
    when (code) {
        ContextIngestionErrorCode.INVALID_TURN -> HttpStatusCode.BadRequest to "INVALID_TURN"
        ContextIngestionErrorCode.PAYLOAD_TOO_LARGE -> HttpStatusCode.PayloadTooLarge to "PAYLOAD_TOO_LARGE"
        ContextIngestionErrorCode.TURN_CONFLICT -> HttpStatusCode.Conflict to "TURN_CONFLICT"
        ContextIngestionErrorCode.UNAUTHORIZED -> HttpStatusCode.Unauthorized to "UNAUTHORIZED"
        ContextIngestionErrorCode.FORBIDDEN -> HttpStatusCode.Forbidden to "FORBIDDEN"
    }

private fun roleOf(value: String): ChatRole =
    when (value) {
        "user" -> ChatRole.USER
        "assistant" -> ChatRole.ASSISTANT
        "tool" -> ChatRole.TOOL
        else -> throw IllegalArgumentException("unsupported message role")
    }

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
) {
    respondText("""{"error":"$code"}""", ContentType.Application.Json, status)
}

@Serializable
private data class TurnPayload(
    @SerialName("runtime_id") val runtimeId: String,
    @SerialName("agent_id") val agentId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_turn_id") val sessionTurnId: String,
    val timestamp: String,
    val messages: List<MessagePayload>,
)

@Serializable
private data class MessagePayload(
    val role: String,
    val content: String,
)

private const val BUFFER_SIZE = 8192
