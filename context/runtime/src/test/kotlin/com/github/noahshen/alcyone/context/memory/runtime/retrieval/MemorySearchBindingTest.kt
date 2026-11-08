package com.github.noahshen.alcyone.context.memory.runtime.retrieval

import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextRetrievalException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MemorySearchBindingTest {
    @Test
    fun `null llm missing task and prompt only fail with configuration at call time`() {
        listOf(
            null,
            config(includeTask = false, mode = LlmResponseFormat.JSON_OBJECT),
            config(includeTask = true, mode = LlmResponseFormat.PROMPT_ONLY),
        ).forEach { llm ->
            val failure = assertFailsWith<ContextRetrievalException> { resolveMemorySearchBinding(llm) }
            assertEquals(ContextRetrievalErrorCode.CONFIGURATION, failure.errorCode)
            assertNull(failure.cause)
        }
    }

    @Test
    fun `JSON schema and JSON object bindings resolve task profile and safety margin`() {
        listOf(LlmResponseFormat.JSON_SCHEMA, LlmResponseFormat.JSON_OBJECT).forEach { mode ->
            val binding = resolveMemorySearchBinding(config(includeTask = true, mode = mode))
            assertEquals("search", binding.task.profile)
            assertEquals("search", binding.profile.name)
            assertEquals(mode, binding.profile.jsonMode)
            assertEquals(0, binding.safetyMarginPercent)
        }
    }

    @Test
    fun `max limit defaults and inclusive boundaries are frozen`() {
        assertEquals(50, MemorySearchDefaults.MAX_LIMIT)
        assertEquals(1_000, MemorySearchDefaults.MAX_LIMIT_BOUND)
        assertEquals(1, requireValidMaxLimit(1))
        assertEquals(1_000, requireValidMaxLimit(1_000))
        listOf(0, -1, 1_001).forEach { value ->
            val failure = assertFailsWith<ContextRetrievalException> { requireValidMaxLimit(value) }
            assertEquals(ContextRetrievalErrorCode.CONFIGURATION, failure.errorCode)
        }
    }

    @Test
    fun `search limit is valid only in one through configured max`() {
        requireValidSearchLimit(1, 50)
        requireValidSearchLimit(50, 50)
        listOf(0, -1, 51).forEach { value ->
            val failure = assertFailsWith<ContextRetrievalException> { requireValidSearchLimit(value, 50) }
            assertEquals(ContextRetrievalErrorCode.INVALID_REQUEST, failure.errorCode)
        }
    }

    private fun config(
        includeTask: Boolean,
        mode: LlmResponseFormat,
    ): LlmConfig {
        val profile =
            LlmProfileConfig(
                name = "search",
                baseUrl = "http://127.0.0.1:1",
                apiKey = "safe-key",
                model = "fake-search",
                contextWindowTokens = 64_000,
                jsonMode = mode,
                maxAttempts = 1,
            )
        return LlmConfig(
            profiles = mapOf(profile.name to profile),
            tasks =
                if (includeTask) {
                    mapOf(LlmTask.MEMORY_SEARCH to LlmTaskConfig("search", 32_000, 4_000))
                } else {
                    emptyMap()
                },
            safetyMarginPercent = 0,
        )
    }
}
