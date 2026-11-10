package com.github.noahshen.alcyone.context.memory.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class DreamingApiContractTest {
    @Test
    fun `DreamingResult preserves six fields and enforces nonnegative ordering`() {
        val result = DreamingResult(1, 3, 2, 4, 5, completed = true)
        assertEquals(
            listOf(1L, 3L, 2, 4, 5, true),
            listOf(
                result.startCheckpoint,
                result.endCheckpoint,
                result.scannedTurns,
                result.discoveredTopics,
                result.generatedMemories,
                result.completed,
            ),
        )
        listOf(
            { DreamingResult(-1, 0, 0, 0, 0, true) },
            { DreamingResult(2, 1, 0, 0, 0, true) },
            { DreamingResult(0, 0, -1, 0, 0, true) },
            { DreamingResult(0, 0, 0, -1, 0, true) },
            { DreamingResult(0, 0, 0, 0, -1, true) },
        ).forEach { block -> assertFailsWith<IllegalArgumentException> { block() } }
    }

    @Test
    fun `already running error has code prefix no cause and no sensitive content`() {
        val failure = ContextDreamingException(ContextDreamingErrorCode.ALREADY_RUNNING, "dreaming is active")
        assertEquals("[ALREADY_RUNNING] dreaming is active", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun `default third party entry reports unavailable not a run conflict`() =
        kotlinx.coroutines.runBlocking {
            val context =
                object : AlcyoneContext {
                    override suspend fun getStatus() = error("not required")

                    override suspend fun ingestConversation(turn: ConversationTurn) = error("not required")

                    override suspend fun start() = Unit

                    override fun close() = Unit

                    override suspend fun closeAsync() = Unit
                }
            val failure = assertFailsWith<ContextDreamingException> { context.runDreaming() }
            assertEquals(ContextDreamingErrorCode.UNAVAILABLE, failure.errorCode)
            assertNull(failure.cause)
        }

    @Test
    fun `run failure exposes stage partial result and original cause`() {
        val cause = IllegalStateException("underlying-db-failure")
        val partial = DreamingResult(0, 1, 1, 2, 3, completed = false)
        val failure = DreamingRunException(DreamingRunStage.BATCH_PROCESSING, partial, "batch failed", cause)

        assertEquals(DreamingRunStage.BATCH_PROCESSING, failure.stage)
        assertSame(partial, failure.partialResult)
        assertSame(cause, failure.cause)
        assertFailsWith<IllegalArgumentException> {
            DreamingRunException(DreamingRunStage.PAGE_READ, partial.copy(completed = true), "invalid")
        }
    }
}
