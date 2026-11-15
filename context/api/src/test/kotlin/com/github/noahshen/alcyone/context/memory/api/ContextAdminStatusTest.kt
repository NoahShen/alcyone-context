package com.github.noahshen.alcyone.context.memory.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ContextAdminStatusTest {
    @Test
    fun `third party context keeps false readiness and unknown admin facts`() =
        kotlinx.coroutines.runBlocking {
            val context =
                object : AlcyoneContext {
                    override suspend fun start() = Unit

                    override fun close() = Unit

                    override suspend fun closeAsync() = Unit

                    override suspend fun getStatus(): ContextStatus =
                        ContextStatus(ContextLifecycleState.STOPPING, emptyMap(), StorageStatus("secret/path", false))

                    override suspend fun ingestConversation(turn: ConversationTurn): IngestResult = error("not called")
                }
            assertFalse(context.checkReady())
            assertEquals(ContextLifecycleState.STOPPING, context.getAdminStatus().lifecycle)
            assertNull(context.getAdminStatus().workers)
        }

    @Test
    fun `llm list is copied at the public DTO boundary`() {
        val source = mutableListOf(AdminLlmTaskUsage("EXTRACTION", "profile", 1, 1, 0, 1, 1, 0, 3, 2, 5))
        val status = ContextAdminStatus(ContextLifecycleState.RUNNING, llm = source)
        source.clear()
        assertEquals(1, status.llm?.size)
        assertEquals("EXTRACTION", status.llm?.single()?.task)
    }

    @Test
    fun `empty and unconfigured administrative facts remain distinct`() {
        val empty = ContextAdminStatus(ContextLifecycleState.RUNNING, llm = emptyList())
        assertEquals(emptyList(), empty.llm)
        assertNull(ContextAdminStatus(ContextLifecycleState.RUNNING).llm)
    }
}
