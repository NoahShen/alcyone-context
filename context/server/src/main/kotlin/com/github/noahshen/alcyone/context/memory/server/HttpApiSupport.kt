package com.github.noahshen.alcyone.context.memory.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Ingestion 与 Read 共用的严格 UTF-8 解码；非法字节返回 null。 */
internal fun decodeStrictUtf8(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        null
    }

/** 请求体实际字节计数；超过上限立即返回 null，不依赖或信任 Content-Length。 */
internal suspend fun readBodyUpTo(
    channel: ByteReadChannel,
    maxBytes: Int,
): ByteArray? {
    val output = ByteArrayOutputStream(minOf(maxBytes, HTTP_BUFFER_SIZE))
    val buffer = ByteArray(HTTP_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        if (read == 0) continue
        if (read > maxBytes - total) return null
        total += read
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

/**
 * Server 自有请求期限。

 * 不能直接用 [withTimeout] 分类：SDK 自己产生的 [kotlinx.coroutines.TimeoutCancellationException]
 * 也是 [CancellationException]，必须原样向上传播，不能误映射为 HTTP 504。这里只取消本次
 * 请求的工作 Job，并以专用取消原因区分 Server 期限；调用方协程的外部取消不会被改写。
 */
internal class ServerRequestDeadlineException : CancellationException("server request deadline exceeded")

internal suspend fun <T> withServerRequestDeadline(
    timeoutMillis: Long,
    block: suspend () -> T,
): T =
    supervisorScope {
        val result = CompletableDeferred<Result<T>>()
        val state = DeadlineState<T>()
        val job =
            launch {
                val outcome =
                    try {
                        Result.success(block())
                    } catch (failure: Throwable) {
                        Result.failure(failure)
                    }
                state.complete(result, outcome)
            }
        val timer =
            launch {
                delay(timeoutMillis)
                state.timeout(result)
            }
        try {
            result.await().getOrThrow()
        } finally {
            job.cancel()
            timer.cancel()
        }
    }

private class DeadlineState<T> {
    private val mutex = Mutex()
    private var finished = false

    suspend fun complete(
        result: CompletableDeferred<Result<T>>,
        outcome: Result<T>,
    ) {
        mutex.withLock {
            if (finished) return
            finished = true
            result.complete(outcome)
        }
    }

    suspend fun timeout(result: CompletableDeferred<Result<T>>) {
        mutex.withLock {
            if (finished) return
            finished = true
            result.complete(Result.failure(ServerRequestDeadlineException()))
        }
    }
}

/**
 * JSON 对象重复键检查。
 *
 * kotlinx.serialization 的普通 schema decode 不保证拒绝重复键；A03 要求显式拒绝。
 * 本扫描器只负责寻找对象成员重复（含嵌套对象），语法/类型仍由严格 Json decode 校验。
 * 键 token 经 Json 字符串解析，因此 `query` 与 `\u0071uery` 会被视为同一键。
 */
internal fun hasDuplicateJsonKeys(text: String): Boolean =
    try {
        DuplicateKeyScanner(text).scan()
    } catch (failure: JsonNestingLimitException) {
        // The request schema is shallow. Treat over-deep input as invalid before a
        // recursive serializer can turn a bounded request body into a stack overflow.
        true
    } catch (failure: IllegalArgumentException) {
        false
    } catch (failure: IndexOutOfBoundsException) {
        false
    }

private class DuplicateKeyScanner(
    private val text: String,
) {
    private var index = 0
    private var depth = 0

    fun scan(): Boolean {
        skipWhitespace()
        if (parseValue()) return true
        skipWhitespace()
        return false
    }

    private fun parseValue(): Boolean {
        skipWhitespace()
        if (index >= text.length) throw IllegalArgumentException()
        return when (text[index]) {
            '{' -> nested { parseObject() }
            '[' -> nested { parseArray() }
            '"' -> {
                parseStringToken()
                false
            }
            else -> {
                while (index < text.length && text[index] !in ",]} \t\r\n") index++
                false
            }
        }
    }

    private inline fun nested(parse: () -> Boolean): Boolean {
        if (depth >= MAX_JSON_DEPTH) throw JsonNestingLimitException()
        depth++
        return try {
            parse()
        } finally {
            depth--
        }
    }

    private fun parseObject(): Boolean {
        index++ // {
        val keys = HashSet<String>()
        skipWhitespace()
        if (consume('}')) return false
        while (true) {
            skipWhitespace()
            if (index >= text.length || text[index] != '"') throw IllegalArgumentException()
            val keyToken = parseStringToken()
            val key = Json.parseToJsonElement(keyToken).jsonPrimitive.content
            if (!keys.add(key)) return true
            skipWhitespace()
            if (!consume(':')) throw IllegalArgumentException()
            if (parseValue()) return true
            skipWhitespace()
            if (consume('}')) return false
            if (!consume(',')) throw IllegalArgumentException()
        }
    }

    private fun parseArray(): Boolean {
        index++ // [
        skipWhitespace()
        if (consume(']')) return false
        while (true) {
            if (parseValue()) return true
            skipWhitespace()
            if (consume(']')) return false
            if (!consume(',')) throw IllegalArgumentException()
        }
    }

    private fun parseStringToken(): String {
        val start = index
        index++ // opening quote
        var escaped = false
        while (index < text.length) {
            val char = text[index++]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                return text.substring(start, index)
            }
        }
        throw IllegalArgumentException()
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun consume(char: Char): Boolean {
        if (index < text.length && text[index] == char) {
            index++
            return true
        }
        return false
    }

    private companion object {
        const val MAX_JSON_DEPTH: Int = 64
    }
}

private class JsonNestingLimitException : RuntimeException()

/** 同一认证表与固定类别授权；未识别为 401，已识别错类为 403。 */
internal suspend fun requireOperation(
    call: ApplicationCall,
    auth: ServerAuthRegistry,
    kind: CredentialKind,
): Boolean {
    val principal = auth.authenticate(call.request.headers[HttpHeaders.Authorization])
    if (principal == null) {
        call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        return false
    }
    if (principal.kind != kind) {
        call.respondError(HttpStatusCode.Forbidden, "FORBIDDEN")
        return false
    }
    return true
}

/** 固定错误体；调用方必须已完成鉴权，且消息只允许安全错误码。 */
internal suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
) {
    respondText("""{"error":"$code"}""", ContentType.Application.Json, status)
}

internal const val HTTP_BUFFER_SIZE: Int = 8192
