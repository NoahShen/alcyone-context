package com.github.noahshen.alcyone.context.memory.api

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AlcyoneContextRetrievalTest {
    @Test
    fun `third party retrieval defaults fail unavailable instead of fake empty or null`() =
        runBlocking {
            val context = NoOpAlcyoneContext()
            val calls: List<suspend () -> Unit> =
                listOf(
                    { context.searchMemory("SECRET-QUERY-MARKER") },
                    { context.getMemory("SECRET-ID-MARKER") },
                    { context.getRawMemory("SECRET-RAW-ID-MARKER") },
                )

            calls.forEach { call ->
                val failure = kotlin.test.assertFailsWith<ContextRetrievalException> { call() }
                assertEquals(ContextRetrievalErrorCode.UNAVAILABLE, failure.errorCode)
                assertNull(failure.cause)
                assertFalse(failure.message.orEmpty().contains("SECRET"))
            }
        }

    private class NoOpAlcyoneContext : AlcyoneContext {
        override suspend fun ingestConversation(turn: ConversationTurn): IngestResult = throw UnsupportedOperationException()

        override suspend fun start() = Unit

        override fun close() = Unit

        override suspend fun closeAsync() = Unit

        override suspend fun getStatus(): ContextStatus = throw UnsupportedOperationException()
    }
}
