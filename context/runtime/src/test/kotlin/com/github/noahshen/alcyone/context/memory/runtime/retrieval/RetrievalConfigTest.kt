package com.github.noahshen.alcyone.context.memory.runtime.retrieval

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RetrievalConfigTest {
    @Test
    fun `retrieval defaults and inclusive max limit boundaries are frozen`() {
        assertEquals(50, RetrievalConfig().search.maxLimit)
        assertEquals(1, SearchConfig(1).maxLimit)
        assertEquals(1_000, SearchConfig(1_000).maxLimit)
    }

    @Test
    fun `zero negative and above bound max limits are rejected safely`() {
        listOf(0, -1, 1_001).forEach { maxLimit ->
            val failure = assertFailsWith<ContextConfigException> { SearchConfig(maxLimit) }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertNull(failure.cause)
            assertTrue(failure.message.orEmpty().contains("context.retrieval.search.maxLimit"))
        }
    }

    @Test
    fun `existing ContextConfig construction defaults retrieval without filesystem side effects`() {
        val config =
            ContextConfig(
                databasePath = Path.of("/tmp/ct20-retrieval/context.db"),
                lifecycleShutdownTimeout = 30.seconds,
                vfsConfig =
                    VfsRuntimeConfig(
                        stateDatabase = Path.of("/tmp/ct20-retrieval/vfs.db"),
                        namespaces = emptySet(),
                        mounts = emptyList(),
                    ),
            )

        assertEquals(RetrievalConfig(), config.retrieval)
        assertEquals(50, config.retrieval.search.maxLimit)
    }
}
