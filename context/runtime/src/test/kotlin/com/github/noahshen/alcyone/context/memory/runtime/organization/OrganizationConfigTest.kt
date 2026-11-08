package com.github.noahshen.alcyone.context.memory.runtime.organization

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

/** CT16 B1：Organization 配置构造与双 LLM binding 纯校验。 */
class OrganizationConfigTest {
    @Test
    fun `empty organization config uses both triggers and the frozen cron`() {
        val config = OrganizationConfig()

        assertEquals(true, config.immediateEnabled)
        assertEquals(true, config.periodicEnabled)
        assertEquals("*/10 * * * *", config.cron)
    }

    @Test
    fun `both triggers disabled and invalid cron are rejected at construction`() {
        val disabled =
            assertFailsWith<ContextConfigException> {
                OrganizationConfig(immediateEnabled = false, periodicEnabled = false)
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, disabled.errorCode)
        assertNull(disabled.cause)

        val invalidCron =
            assertFailsWith<ContextConfigException> {
                OrganizationConfig(periodicEnabled = true, cron = "not a cron")
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, invalidCron.errorCode)
        assertFalse(invalidCron.message.orEmpty().contains("not a cron"))
        assertNull(invalidCron.cause)
    }

    @Test
    fun `disabled periodic trigger does not exempt cron validation`() {
        val failure =
            assertFailsWith<ContextConfigException> {
                OrganizationConfig(immediateEnabled = true, periodicEnabled = false, cron = "* *")
            }

        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
        assertFalse(failure.message.orEmpty().contains("* *"))
    }

    @Test
    fun `binding accepts both required structured tasks and profiles`() {
        validateOrganizationBinding(OrganizationConfig(), validLlm())
    }

    @Test
    fun `null organization remains disabled without requiring llm`() {
        validateOrganizationBinding(null, null)
    }

    @Test
    fun `binding requires llm and both tasks`() {
        assertInvalidBinding(OrganizationConfig(), null, "context.llm")
        assertInvalidBinding(
            OrganizationConfig(),
            validLlm(tasks = mapOf(LlmTask.GOVERNANCE to task("governance-profile"))),
            "CANDIDATE_SCANNER",
        )
        assertInvalidBinding(
            OrganizationConfig(),
            validLlm(tasks = mapOf(LlmTask.CANDIDATE_SCANNER to task("scanner-profile"))),
            "GOVERNANCE",
        )
    }

    @Test
    fun `binding rejects prompt only profiles without exposing keys or urls`() {
        val llm =
            validLlm(
                profiles =
                    mapOf(
                        "scanner-profile" to profile("scanner-profile", LlmResponseFormat.JSON_SCHEMA),
                        "governance-profile" to profile("governance-profile", LlmResponseFormat.PROMPT_ONLY),
                    ),
            )

        val failure =
            assertFailsWith<ContextConfigException> {
                validateOrganizationBinding(OrganizationConfig(), llm)
            }

        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
        assertFalse(failure.message.orEmpty().contains("secret-api-key"))
        assertFalse(failure.message.orEmpty().contains("example.test"))
    }

    @Test
    fun `binding rejects a prompt only scanner profile`() {
        val llm =
            validLlm(
                profiles =
                    mapOf(
                        "scanner-profile" to profile("scanner-profile", LlmResponseFormat.PROMPT_ONLY),
                        "governance-profile" to profile("governance-profile", LlmResponseFormat.JSON_SCHEMA),
                    ),
            )

        val failure = assertFailsWith<ContextConfigException> { validateOrganizationBinding(OrganizationConfig(), llm) }

        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
        assertFalse(failure.message.orEmpty().contains("secret-api-key"))
    }

    @Test
    fun `a task with a missing profile is rejected by llm validation`() {
        val failure =
            assertFailsWith<ContextConfigException> {
                LlmConfig(
                    profiles = mapOf("scanner-profile" to profile("scanner-profile", LlmResponseFormat.JSON_SCHEMA)),
                    tasks =
                        mapOf(
                            LlmTask.CANDIDATE_SCANNER to task("scanner-profile"),
                            LlmTask.GOVERNANCE to task("missing-profile"),
                        ),
                )
            }

        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
        assertFalse(failure.message.orEmpty().contains("secret-api-key"))
    }

    private fun assertInvalidBinding(
        organization: OrganizationConfig,
        llm: LlmConfig?,
        expectedPath: String,
    ) {
        val failure = assertFailsWith<ContextConfigException> { validateOrganizationBinding(organization, llm) }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
        assertEquals(true, failure.message.orEmpty().contains(expectedPath), failure.message.orEmpty())
        assertFalse(failure.message.orEmpty().contains("secret-api-key"))
    }

    private fun validLlm(
        profiles: Map<String, LlmProfileConfig> =
            mapOf(
                "scanner-profile" to profile("scanner-profile", LlmResponseFormat.JSON_SCHEMA),
                "governance-profile" to profile("governance-profile", LlmResponseFormat.JSON_OBJECT),
            ),
        tasks: Map<LlmTask, LlmTaskConfig> =
            mapOf(
                LlmTask.CANDIDATE_SCANNER to task("scanner-profile"),
                LlmTask.GOVERNANCE to task("governance-profile"),
            ),
    ): LlmConfig = LlmConfig(profiles = profiles, tasks = tasks)

    private fun profile(
        name: String,
        format: LlmResponseFormat,
    ): LlmProfileConfig =
        LlmProfileConfig(
            name = name,
            baseUrl = "https://example.test/v1",
            apiKey = "secret-api-key",
            model = "test-model",
            contextWindowTokens = 32_000,
            jsonMode = format,
        )

    private fun task(profile: String): LlmTaskConfig = LlmTaskConfig(profile = profile, maxInputTokens = 1_000, maxOutputTokens = 1_000)
}
