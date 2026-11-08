package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryMapConfigTest {
    @Test
    fun `default config enables periodic refresh with the frozen cron`() {
        val config = MemoryMapConfig()
        assertTrue(config.periodic.enabled)
        assertEquals("*/10 * * * *", config.periodic.cron)
        assertEquals("Asia/Shanghai", MemoryMapDefaults.TIME_ZONE_ID)
    }

    @Test
    fun `periodic can be disabled without disabling explicit memory map configuration`() {
        val config = MemoryMapConfig(MemoryMapPeriodicConfig(enabled = false, cron = "0 3 * * *"))
        assertFalse(config.periodic.enabled)
        assertEquals("0 3 * * *", config.periodic.cron)
    }

    @Test
    fun `invalid or blank cron is rejected without echoing the expression`() {
        val marker = "invalid-cron-secret"
        val failure =
            assertFailsWith<ContextConfigException> {
                MemoryMapPeriodicConfig(cron = marker)
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
        assertFalse(failure.message.orEmpty().contains(marker))
        assertNull(failure.cause)
    }

    @Test
    fun `binding helper permits disabled map and validates task profile and structured json mode`() {
        validateMemoryMapBinding(null, null)

        val missing = assertFailsWith<ContextConfigException> { validateMemoryMapBinding(MemoryMapConfig(), null) }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, missing.errorCode)

        validateMemoryMapBinding(MemoryMapConfig(), llm(LlmResponseFormat.JSON_SCHEMA))
        validateMemoryMapBinding(MemoryMapConfig(), llm(LlmResponseFormat.JSON_OBJECT))

        val promptOnly =
            assertFailsWith<ContextConfigException> {
                validateMemoryMapBinding(MemoryMapConfig(), llm(LlmResponseFormat.PROMPT_ONLY))
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, promptOnly.errorCode)
    }

    private fun llm(mode: LlmResponseFormat): LlmConfig =
        LlmConfig(
            profiles =
                mapOf(
                    "map" to
                        LlmProfileConfig(
                            name = "map",
                            baseUrl = "http://127.0.0.1:1",
                            apiKey = "fake-key",
                            model = "fake-map",
                            contextWindowTokens = 16_384,
                            jsonMode = mode,
                            maxAttempts = 1,
                        ),
                ),
            tasks =
                mapOf(
                    LlmTask.MEMORY_MAP to
                        LlmTaskConfig(
                            profile = "map",
                            maxInputTokens = 8_000,
                            maxOutputTokens = 1_000,
                        ),
                ),
        )
}
