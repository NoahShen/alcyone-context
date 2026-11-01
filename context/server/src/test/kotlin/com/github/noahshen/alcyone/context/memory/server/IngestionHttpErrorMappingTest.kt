package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.ContextStatus
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.StorageStatus
import com.github.noahshen.alcyone.context.memory.runtime.IngestionLimitAware
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * CT07-B2 R4：错误映射必须用真实 HTTP 请求断言，而不是只读代码分支。
 *
 * 假 Context 只触发目标异常；断言固定脱敏响应体，不含异常 message/类名、Key、正文或连接串。
 */
class IngestionHttpErrorMappingTest {
    private val secretMarker = "SENSITIVE-ERROR-MARKER"
    private val bodyMarker = "REQUEST-BODY-MARKER"

    @Test
    fun `persistence unavailable maps to 503 with a fixed redacted body`() =
        testApplication {
            val failure =
                ContextPersistenceException(
                    ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE,
                    "$secretMarker jdbc:sqlite:/private/db $bodyMarker",
                )
            application { ingestion(FailingContext(failure), HttpTurns.registry()) }
            val response = postTurn(HttpTurns.KEY_RUNTIME_1)
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertRedacted(response.bodyAsText(), """{"error":"SERVICE_UNAVAILABLE"}""")
        }

    @Test
    fun `other persistence errors map to 500 with a fixed redacted body`() =
        testApplication {
            val failure =
                ContextPersistenceException(
                    ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED,
                    "$secretMarker jdbc:sqlite:/private/db",
                )
            application { ingestion(FailingContext(failure), HttpTurns.registry()) }
            val response = postTurn(HttpTurns.KEY_RUNTIME_1)
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertRedacted(response.bodyAsText(), """{"error":"INTERNAL_ERROR"}""")
        }

    @Test
    fun `unexpected runtime exceptions map to 500 with a fixed redacted body`() =
        testApplication {
            val failure = IllegalStateException("$secretMarker unexpected failure $bodyMarker")
            application { ingestion(FailingContext(failure), HttpTurns.registry()) }
            val response = postTurn(HttpTurns.KEY_RUNTIME_1)
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertRedacted(response.bodyAsText(), """{"error":"INTERNAL_ERROR"}""")
        }

    private fun assertRedacted(
        body: String,
        expected: String,
    ) {
        assertEquals(expected, body)
        assertFalse(body.contains(secretMarker))
        assertFalse(body.contains(bodyMarker))
        assertFalse(body.contains("ContextPersistenceException"))
        assertFalse(body.contains("IllegalStateException"))
        assertFalse(body.contains(HttpTurns.KEY_RUNTIME_1))
        assertFalse(body.contains("jdbc:sqlite"))
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postTurn(key: String) =
        client.post("/v1/conversations/turns") {
            header(HttpHeaders.Authorization, "Bearer $key")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(HttpTurns.json())
        }

    /** 只触发目标异常的假 Context；timestamp/限额都合法通过，避免掩盖映射分支。 */
    private class FailingContext(
        private val failure: Throwable,
    ) : AlcyoneContext,
        IngestionLimitAware {
        override val ingestionMaxTurnBytes: Int = 2 * 1024 * 1024

        override suspend fun ingestConversation(turn: ConversationTurn): IngestResult = throw failure

        override suspend fun start() = Unit

        override fun close() = Unit

        override suspend fun closeAsync() = Unit

        override suspend fun getStatus(): ContextStatus = ContextStatus(ContextLifecycleState.RUNNING, emptyMap(), StorageStatus("db"))
    }
}
