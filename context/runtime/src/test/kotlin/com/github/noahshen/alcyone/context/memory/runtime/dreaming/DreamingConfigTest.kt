package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class DreamingConfigTest {
    @Test
    fun `default is manual only with Asia Shanghai and old ContextConfig construction remains compatible`() {
        val config = DreamingConfig()
        assertNull(config.cron)
        assertEquals("Asia/Shanghai", config.timezone)
        val context =
            ContextConfig(
                databasePath = Path.of("/tmp/ct22/context.db"),
                lifecycleShutdownTimeout = 30.seconds,
                vfsConfig = VfsRuntimeConfig(Path.of("/tmp/ct22/vfs.db"), emptySet(), emptyList()),
            )
        assertEquals(config, context.dreaming)
    }

    @Test
    fun `valid cron and IANA timezone are accepted`() {
        val config = DreamingConfig(cron = "0 3 * * *", timezone = "Europe/London")
        assertEquals("0 3 * * *", config.cron)
        assertEquals("Europe/London", config.timezone)
    }

    @Test
    fun `orphan timezone is rejected without echoing the raw value`() {
        val failure =
            assertFailsWith<ContextConfigException> {
                DreamingConfig(cron = null, timezone = "Europe/London")
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
        assertNull(failure.cause)
        assertEquals(
            "[INVALID_CONFIG] context.dreaming.timezone is not allowed without context.dreaming.cron",
            failure.message,
        )
        assertFalse(failure.message.orEmpty().contains("Europe/London"))
    }

    @Test
    fun `invalid blank cron and invalid timezone are rejected safely`() {
        listOf("", "not-cron", "0 3 * *").forEach { cron ->
            val failure = assertFailsWith<ContextConfigException> { DreamingConfig(cron = cron) }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertNull(failure.cause)
        }
        listOf("", "Not/AZone", "+08:00").forEach { timezone ->
            val failure = assertFailsWith<ContextConfigException> { DreamingConfig(cron = "0 3 * * *", timezone = timezone) }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertNull(failure.cause)
        }
    }
}
