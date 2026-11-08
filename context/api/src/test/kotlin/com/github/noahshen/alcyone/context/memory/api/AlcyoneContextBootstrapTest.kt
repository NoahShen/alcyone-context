package com.github.noahshen.alcyone.context.memory.api

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlcyoneContextBootstrapTest {
    @Test
    fun `third party default bootstrap returns empty unknown context with fixed capabilities`() =
        runBlocking {
            val context = NoOpAlcyoneContext()

            val first = context.bootstrap()
            val second = context.bootstrap()

            assertNull(first.memoryMap)
            assertEquals(MapStatus.UNKNOWN, first.mapStatus)
            assertEquals(BootstrapContext.DEFAULT_CAPABILITIES, first.capabilities)
            assertEquals(first, second, "默认 bootstrap 不得持久化或复制变化中的状态")
        }

    private class NoOpAlcyoneContext : AlcyoneContext {
        override suspend fun ingestConversation(turn: ConversationTurn): IngestResult = throw UnsupportedOperationException()

        override suspend fun start() = Unit

        override fun close() = Unit

        override suspend fun closeAsync() = Unit

        override suspend fun getStatus(): ContextStatus = throw UnsupportedOperationException()
    }
}
