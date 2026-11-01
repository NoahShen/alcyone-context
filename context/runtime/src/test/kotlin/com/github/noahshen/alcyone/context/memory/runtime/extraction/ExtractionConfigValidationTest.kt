package com.github.noahshen.alcyone.context.memory.runtime.extraction

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * CT09-B1 A01：`context.extraction` 配置默认值、YAML/直接构造同一校验、范围与未知键、
 * `idle.enabled=false` 不豁免校验、错误消息不回显原值且 cron 不挂 cause。
 */
class ExtractionConfigValidationTest {
    @Test
    fun `defaults are frozen and used when yaml omits extraction`() =
        withDirectory { directory ->
            val defaults = ExtractionConfig()
            assertEquals(5, defaults.countThreshold)
            assertTrue(defaults.idleEnabled)
            assertEquals(10.minutes, defaults.idleTimeout)
            assertEquals("*/5 * * * *", defaults.cron)
            assertEquals(32, defaults.initialWindowTurns)
            assertEquals(32, ExtractionDefaults.INITIAL_WINDOW_TURNS)
            assertEquals("Asia/Shanghai", ExtractionDefaults.TIME_ZONE_ID)

            // ContextConfig 的默认值同样生效。
            val direct =
                ContextConfig(
                    databasePath = directory.resolve("context.db"),
                    lifecycleShutdownTimeout = 30.seconds,
                    vfsConfig = vfsConfig(directory),
                )
            assertEquals(defaults, direct.extraction)

            // YAML 未配置 extraction → 冻结默认值。
            assertEquals(defaults, load(directory, yaml()).extraction)
        }

    @Test
    fun `yaml partial and full overrides are parsed`() =
        withDirectory { directory ->
            val partial =
                load(
                    directory,
                    yaml(
                        """
                        extraction:
                          triggers:
                            count:
                              threshold: 9
                        """.trimIndent(),
                    ),
                )
            assertEquals(9, partial.extraction.countThreshold)
            assertTrue(partial.extraction.idleEnabled)
            assertEquals(10.minutes, partial.extraction.idleTimeout)
            assertEquals("*/5 * * * *", partial.extraction.cron)

            val full =
                load(
                    directory,
                    yaml(
                        """
                        extraction:
                          triggers:
                            count:
                              threshold: 2
                            idle:
                              enabled: false
                              idleTimeout: 90s
                              cron: "0 3 * * *"
                        """.trimIndent(),
                    ),
                )
            assertEquals(2, full.extraction.countThreshold)
            assertFalse(full.extraction.idleEnabled)
            assertEquals(90.seconds, full.extraction.idleTimeout)
            assertEquals("0 3 * * *", full.extraction.cron)
        }

    @Test
    fun `direct construction uses the same validation`() {
        val valid = ExtractionConfig(countThreshold = 1, idleEnabled = false, idleTimeout = 1.seconds, cron = "0 0 * * *")
        assertEquals(1, valid.countThreshold)

        assertInvalidConfig { ExtractionConfig(countThreshold = 0) }
        assertInvalidConfig { ExtractionConfig(countThreshold = -1) }
        assertInvalidConfig { ExtractionConfig(idleTimeout = Duration.ZERO) }
        assertInvalidConfig { ExtractionConfig(idleTimeout = Duration.INFINITE) }
        assertInvalidConfig { ExtractionConfig(cron = "not a cron") }
        assertInvalidConfig { ExtractionConfig(cron = "*/5 * * *") }
    }

    @Test
    fun `initial window turns is parsed and validated in both paths`() =
        withDirectory { directory ->
            // YAML 覆盖
            val loaded =
                load(
                    directory,
                    yaml(
                        """
                        extraction:
                          initialWindowTurns: 64
                        """.trimIndent(),
                    ),
                )
            assertEquals(64, loaded.extraction.initialWindowTurns)
            // 直接构造覆盖（同一校验）
            assertEquals(64, ExtractionConfig(initialWindowTurns = 64).initialWindowTurns)
            // 边界合法值
            assertEquals(1, ExtractionConfig(initialWindowTurns = 1).initialWindowTurns)
            assertEquals(1024, ExtractionConfig(initialWindowTurns = 1024).initialWindowTurns)

            // 越界：0 / 1025 / 负数，消息含路径且不回显原值。
            listOf(0, 1025, -3).forEach { value ->
                assertFailsWith<ContextConfigException> { ExtractionConfig(initialWindowTurns = value) }
                val failure =
                    assertFailsWith<ContextConfigException> {
                        load(directory, yaml("extraction:\n  initialWindowTurns: $value"))
                    }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode, "value=$value")
                assertTrue(
                    failure.message.orEmpty().contains("context.extraction.initialWindowTurns"),
                    failure.message.orEmpty(),
                )
                // 消息只给范围，不回显非法值（范围文本本身含数字，故不按数字子串断言）。
                assertNull(failure.cause, "initialWindowTurns 校验不得挂 cause: ${failure.cause}")
            }

            // 非整数：类型错误仍是 INVALID_CONFIG 且含路径。
            val nonInteger =
                assertFailsWith<ContextConfigException> {
                    load(directory, yaml("extraction:\n  initialWindowTurns: many"))
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, nonInteger.errorCode)
            assertTrue(
                nonInteger.message.orEmpty().contains("context.extraction.initialWindowTurns"),
                nonInteger.message.orEmpty(),
            )
            assertFalse(nonInteger.message.orEmpty().contains("many"), nonInteger.message.orEmpty())

            // 未知键（拼写错误）仍被拒绝。
            val unknown =
                assertFailsWith<ContextConfigException> {
                    load(directory, yaml("extraction:\n  initialWindowTurn: 8"))
                }
            assertEquals(ContextConfigErrorCode.UNKNOWN_FIELD, unknown.errorCode)
        }

    @Test
    fun `threshold must be a positive integer`() =
        withDirectory { directory ->
            listOf("0", "-1").forEach { value ->
                val failure =
                    assertFailsWith<ContextConfigException> {
                        load(directory, yaml(countYaml(value)))
                    }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
                assertTrue(
                    failure.message.orEmpty().contains("context.extraction.triggers.count.threshold"),
                    failure.message.orEmpty(),
                )
            }
            // 非整数同样拒绝，且消息不回显原值。
            listOf("1.5", "five").forEach { value ->
                val failure =
                    assertFailsWith<ContextConfigException> {
                        load(directory, yaml(countYaml(value)))
                    }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
                assertTrue(
                    failure.message.orEmpty().contains("context.extraction.triggers.count.threshold"),
                    failure.message.orEmpty(),
                )
                assertFalse(failure.message.orEmpty().contains(value), failure.message.orEmpty())
            }
        }

    @Test
    fun `idle timeout must be a finite positive duration`() =
        withDirectory { directory ->
            listOf("0s", "-5s").forEach { value ->
                val failure =
                    assertFailsWith<ContextConfigException> {
                        load(directory, yaml(idleYaml("idleTimeout: $value")))
                    }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
                assertTrue(
                    failure.message.orEmpty().contains("context.extraction.triggers.idle.idleTimeout"),
                    failure.message.orEmpty(),
                )
                assertFalse(failure.message.orEmpty().contains(value), failure.message.orEmpty())
            }
            // 无限时长：直接构造必须拒绝。
            val infinite = assertFailsWith<ContextConfigException> { ExtractionConfig(idleTimeout = Duration.INFINITE) }
            assertTrue(
                infinite.message.orEmpty().contains("context.extraction.triggers.idle.idleTimeout"),
                infinite.message.orEmpty(),
            )
        }

    @Test
    fun `cron must be a five field unix expression`() =
        withDirectory { directory ->
            listOf("\"\"", "\"*/5 * * *\"", "\"*/5 * * * * *\"", "\"not a cron\"", "\"61 * * * *\"").forEach { value ->
                val failure =
                    assertFailsWith<ContextConfigException> {
                        load(directory, yaml(idleYaml("cron: $value")))
                    }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode, "cron=$value")
                assertTrue(
                    failure.message.orEmpty().contains("context.extraction.triggers.idle.cron"),
                    failure.message.orEmpty(),
                )
                // 不附带可泄漏原值的解析 cause。
                assertNull(failure.cause, "cron 解析失败不得挂 cause: ${failure.cause}")
                assertFalse(failure.message.orEmpty().contains("not a cron"), failure.message.orEmpty())
            }
        }

    @Test
    fun `timezone is an unknown field`() =
        withDirectory { directory ->
            val failure =
                assertFailsWith<ContextConfigException> {
                    load(directory, yaml(idleYaml("timezone: Asia/Shanghai")))
                }
            assertEquals(ContextConfigErrorCode.UNKNOWN_FIELD, failure.errorCode)
            assertTrue(
                failure.message.orEmpty().contains("context.extraction.triggers.idle.timezone"),
                failure.message.orEmpty(),
            )
        }

    @Test
    fun `every extraction level rejects unknown keys`() =
        withDirectory { directory ->
            listOf(
                """
                extraction:
                  extra: 1
                """.trimIndent(),
                """
                extraction:
                  triggers:
                    extra: 1
                """.trimIndent(),
                """
                extraction:
                  triggers:
                    count:
                      extra: 1
                """.trimIndent(),
                """
                extraction:
                  triggers:
                    idle:
                      extra: 1
                """.trimIndent(),
            ).forEach { block ->
                val failure =
                    assertFailsWith<ContextConfigException> {
                        load(directory, yaml(block))
                    }
                assertEquals(ContextConfigErrorCode.UNKNOWN_FIELD, failure.errorCode, block)
                assertTrue(
                    failure.message.orEmpty().contains("context.extraction"),
                    failure.message.orEmpty(),
                )
            }
        }

    @Test
    fun `disabling idle does not exempt the other fields`() =
        withDirectory { directory ->
            val badTimeout =
                assertFailsWith<ContextConfigException> {
                    load(directory, yaml(idleYaml("enabled: false\nidleTimeout: 0s")))
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, badTimeout.errorCode)

            val badCron =
                assertFailsWith<ContextConfigException> {
                    load(directory, yaml(idleYaml("enabled: false\ncron: \"bad cron\"")))
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, badCron.errorCode)
        }

    // ------------------------------------------------------------------ 工具

    private fun assertInvalidConfig(block: () -> Unit) {
        val failure = assertFailsWith<ContextConfigException> { block() }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
    }

    /** 完整 YAML；[extraction] 为 `context:` 下的片段（本方法统一加 2 空格缩进）。 */
    private fun yaml(extraction: String? = null): String =
        buildString {
            appendLine("context:")
            appendLine("  database:")
            appendLine("    path: ./data/context.db")
            extraction?.trimEnd()?.lines()?.forEach { line -> appendLine("  $line") }
            appendLine("vfs:")
            appendLine("  database:")
            appendLine("    path: ./data/vfs.db")
        }

    private fun countYaml(threshold: String): String =
        """
        extraction:
          triggers:
            count:
              threshold: $threshold
        """.trimIndent()

    private fun idleYaml(body: String): String =
        buildString {
            appendLine("extraction:")
            appendLine("  triggers:")
            appendLine("    idle:")
            body.trimEnd().lines().forEach { line -> appendLine("      $line") }
        }.trimEnd()

    private fun vfsConfig(directory: Path): VfsRuntimeConfig =
        VfsRuntimeConfig(
            stateDatabase = directory.resolve("vfs.db"),
            namespaces = emptySet(),
            mounts = emptyList(),
        )

    private fun load(
        directory: Path,
        yaml: String,
    ): ContextConfig {
        val file = directory.resolve("config.yaml")
        Files.writeString(file, yaml)
        return ContextConfigLoader.load(file, { null })
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("ct09-b1-config-")
        try {
            block(directory)
        } finally {
            deleteRecursively(directory)
        }
    }

    private fun deleteRecursively(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
