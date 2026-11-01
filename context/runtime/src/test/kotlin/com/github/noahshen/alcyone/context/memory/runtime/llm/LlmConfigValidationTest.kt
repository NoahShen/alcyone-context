package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT08-B1（A02）：`context.llm` 首次消费后的严格校验。
 *
 * 表格覆盖方案 §a「校验规则表」11 类（未知键 / 缺 profiles / 空白 Profile 名 / 必填缺失或空白 /
 * baseUrl 形状 / apiKey 的 ENV / jsonMode / 数值范围 / 未知 Task / Task→未知 Profile / 静态预算），
 * 以及「直接 Kotlin 构造走同一校验」与「异常/toString 不泄露 Key」。
 */
class LlmConfigValidationTest {
    private companion object {
        const val KEY_MARKER = "ct08-b1-unique-key-marker-51c7"
        const val OMIT = "<omit>"
        const val ENV_NAME = "CT08_B1_LLM_KEY"
    }

    // ------------------------------------------------------------------ A01 的配置侧

    @Test
    fun `llm absent keeps config null and reads no environment variable`() =
        withDirectory { dir ->
            val requested = mutableListOf<String>()
            val config =
                load(
                    dir,
                    """
                    context:
                      database:
                        path: ./data/context.db
                    vfs:
                      database:
                        path: ./data/vfs.db
                    """,
                ) { name ->
                    requested += name
                    null
                }

            assertNull(config.llm, "未配置 context.llm 时必须是 null")
            assertTrue(requested.isEmpty(), "未配置 llm 不得读取任何 ENV，实际: $requested")
        }

    @Test
    fun `valid llm config is parsed with documented defaults`() =
        withDirectory { dir ->
            val config =
                load(
                    dir,
                    yaml(
                        margin = "20",
                        profiles = profileBlock(overrides = mapOf("apiKey" to "\${$ENV_NAME}")),
                        tasks = taskBlock(),
                    ),
                ) { name -> if (name == ENV_NAME) "sk-from-env-value" else null }

            val llm = assertNotNull(config.llm)
            assertEquals(20, llm.safetyMarginPercent)
            val profile = assertNotNull(llm.profiles["extraction"])
            assertEquals("extraction", profile.name)
            assertEquals("https://llm.example.com/v1", profile.baseUrl)
            assertEquals("sk-from-env-value", profile.apiKey)
            assertEquals("fast-model", profile.model)
            assertEquals(64_000, profile.contextWindowTokens)
            assertEquals(LlmResponseFormat.JSON_SCHEMA, profile.jsonMode)
            assertEquals(10, profile.connectTimeoutSeconds)
            assertEquals(180, profile.requestTimeoutSeconds)
            assertEquals(120, profile.socketTimeoutSeconds)
            assertEquals(2, profile.maxAttempts)

            val task = assertNotNull(llm.tasks[LlmTask.EXTRACTION])
            assertEquals("extraction", task.profile)
            assertEquals(12_000, task.maxInputTokens)
            assertEquals(3_000, task.maxOutputTokens)
            assertEquals(360, task.totalTimeoutSeconds)
        }

    @Test
    fun `safety margin and timeout overrides are honoured`() =
        withDirectory { dir ->
            val config =
                load(
                    dir,
                    yaml(
                        margin = "0",
                        profiles =
                            profileBlock(
                                overrides =
                                    mapOf(
                                        "connectTimeoutSeconds" to "3",
                                        "requestTimeoutSeconds" to "30",
                                        "socketTimeoutSeconds" to "45",
                                        "maxAttempts" to "5",
                                    ),
                            ),
                        tasks = taskBlock(overrides = mapOf("totalTimeoutSeconds" to "60")),
                    ),
                )
            val llm = assertNotNull(config.llm)
            assertEquals(0, llm.safetyMarginPercent)
            val profile = assertNotNull(llm.profiles["extraction"])
            assertEquals(3, profile.connectTimeoutSeconds)
            assertEquals(30, profile.requestTimeoutSeconds)
            assertEquals(45, profile.socketTimeoutSeconds)
            assertEquals(5, profile.maxAttempts)
            assertEquals(60, assertNotNull(llm.tasks[LlmTask.EXTRACTION]).totalTimeoutSeconds)
        }

    // ------------------------------------------------------------------ 规则 1：未知键

    @Test
    fun `unknown llm fields are rejected with the field path`() =
        withDirectory { dir ->
            val atRoot =
                expectFailure(
                    dir,
                    yaml(profiles = profileBlock(), tasks = taskBlock(), extraLlm = "unexpected: 1"),
                    ContextConfigErrorCode.UNKNOWN_FIELD,
                )
            assertTrue(atRoot.message.orEmpty().contains("context.llm.unexpected"), atRoot.message.orEmpty())

            val inProfile =
                expectFailure(
                    dir,
                    yaml(profiles = profileBlock(extra = mapOf("apiKeyEnv" to "X")), tasks = taskBlock()),
                    ContextConfigErrorCode.UNKNOWN_FIELD,
                )
            assertTrue(
                inProfile.message.orEmpty().contains("context.llm.profiles.extraction.apiKeyEnv"),
                inProfile.message.orEmpty(),
            )

            val inTask =
                expectFailure(
                    dir,
                    yaml(profiles = profileBlock(), tasks = taskBlock(extra = mapOf("temperature" to "0.2"))),
                    ContextConfigErrorCode.UNKNOWN_FIELD,
                )
            assertTrue(
                inTask.message.orEmpty().contains("context.llm.tasks.EXTRACTION.temperature"),
                inTask.message.orEmpty(),
            )
        }

    // ------------------------------------------------------------------ 规则 2/3：profiles 结构

    @Test
    fun `llm requires a non empty profiles mapping`() =
        withDirectory { dir ->
            val missing = expectFailure(dir, "context:\n  llm:\n    profiles: {}\n", ContextConfigErrorCode.INVALID_CONFIG)
            assertTrue(missing.message.orEmpty().contains("context.llm.profiles is required"), missing.message.orEmpty())

            val absent =
                expectFailure(
                    dir,
                    "context:\n  llm:\n    safetyMarginPercent: 10\n",
                    ContextConfigErrorCode.INVALID_CONFIG,
                )
            assertTrue(absent.message.orEmpty().contains("context.llm.profiles is required"), absent.message.orEmpty())
        }

    @Test
    fun `llm rejects a blank profile name`() =
        withDirectory { dir ->
            val failure = expectFailure(dir, yaml(profiles = profileBlock(name = "\" \""), tasks = ""))
            assertTrue(failure.message.orEmpty().contains("context.llm.profiles has a blank name"), failure.message.orEmpty())
        }

    // ------------------------------------------------------------------ 规则 4：必填缺失/空白

    @Test
    fun `llm required fields are rejected when missing or blank`() =
        withDirectory { dir ->
            listOf("baseUrl", "apiKey", "model").forEach { field ->
                val missing =
                    expectFailure(
                        dir,
                        yaml(profiles = profileBlock(overrides = mapOf(field to OMIT))),
                    )
                assertTrue(
                    missing.message.orEmpty().contains("context.llm.profiles.extraction.$field is required"),
                    "$field: ${missing.message.orEmpty()}",
                )
                val blank =
                    expectFailure(
                        dir,
                        yaml(profiles = profileBlock(overrides = mapOf(field to "\"   \""))),
                    )
                assertTrue(
                    blank.message.orEmpty().contains("context.llm.profiles.extraction.$field is required"),
                    "$field: ${blank.message.orEmpty()}",
                )
            }

            val missingWindow =
                expectFailure(dir, yaml(profiles = profileBlock(overrides = mapOf("contextWindowTokens" to OMIT))))
            assertTrue(
                missingWindow.message.orEmpty().contains("context.llm.profiles.extraction.contextWindowTokens is required"),
                missingWindow.message.orEmpty(),
            )
            val missingJsonMode =
                expectFailure(dir, yaml(profiles = profileBlock(overrides = mapOf("jsonMode" to OMIT))))
            assertTrue(
                missingJsonMode.message.orEmpty().contains("context.llm.profiles.extraction.jsonMode is required"),
                missingJsonMode.message.orEmpty(),
            )
            val missingTaskProfile =
                expectFailure(dir, yaml(tasks = taskBlock(overrides = mapOf("profile" to OMIT))))
            assertTrue(
                missingTaskProfile.message.orEmpty().contains("context.llm.tasks.EXTRACTION.profile is required"),
                missingTaskProfile.message.orEmpty(),
            )
        }

    // ------------------------------------------------------------------ 规则 5：baseUrl

    @Test
    fun `llm base url must be absolute http or https without credentials`() =
        withDirectory { dir ->
            listOf(
                "ftp://llm.example.com/v1",
                "llm.example.com/v1",
                "https://user:secret@llm.example.com/v1",
                "https://llm.example.com/v1?token=secret",
                "https://llm.example.com/v1#fragment",
                "https://",
            ).forEach { baseUrl ->
                val failure =
                    expectFailure(
                        dir,
                        yaml(profiles = profileBlock(overrides = mapOf("baseUrl" to "\"$baseUrl\""))),
                    )
                assertTrue(
                    failure
                        .message
                        .orEmpty()
                        .contains(
                            "context.llm.profiles.extraction.baseUrl must be an http(s) URL " +
                                "without userinfo, query or fragment",
                        ),
                    "$baseUrl -> ${failure.message.orEmpty()}",
                )
                assertFalse(failure.message.orEmpty().contains("secret"), "$baseUrl 的消息不得回显 URL 凭据")
                assertFalse(failure.message.orEmpty().contains(baseUrl), "$baseUrl 的消息不得回显原始 URL")
            }
        }

    // ------------------------------------------------------------------ 规则 6：apiKey 的 ENV

    @Test
    fun `llm api key environment variable must be set and is never echoed`() =
        withDirectory { dir ->
            val unset =
                expectFailure(
                    dir,
                    yaml(profiles = profileBlock(overrides = mapOf("apiKey" to "\${$ENV_NAME}"))),
                    ContextConfigErrorCode.MISSING_ENV,
                )
            val message = unset.message.orEmpty()
            assertTrue(message.contains(ENV_NAME), "MISSING_ENV 消息必须含 ENV 名: $message")
            assertTrue(message.contains("context.llm.profiles.extraction.apiKey"), message)
            assertFalse(message.contains(KEY_MARKER), "MISSING_ENV 消息不得含 Key 值: $message")

            val blankEnv =
                expectFailure(
                    dir,
                    yaml(profiles = profileBlock(overrides = mapOf("apiKey" to "\${$ENV_NAME}"))),
                    ContextConfigErrorCode.MISSING_ENV,
                ) { name -> if (name == ENV_NAME) "   " else null }
            assertTrue(blankEnv.message.orEmpty().contains(ENV_NAME), blankEnv.message.orEmpty())
        }

    // ------------------------------------------------------------------ 规则 7：jsonMode

    @Test
    fun `llm json mode must be one of the three documented values`() =
        withDirectory { dir ->
            val failure =
                expectFailure(
                    dir,
                    yaml(profiles = profileBlock(overrides = mapOf("jsonMode" to "yaml_schema"))),
                )
            val message = failure.message.orEmpty()
            assertTrue(message.contains("context.llm.profiles.extraction.jsonMode must be one of"), message)
            assertTrue(message.contains("json_schema"), message)
            assertTrue(message.contains("json_object"), message)
            assertTrue(message.contains("prompt_only"), message)
            assertFalse(message.contains("yaml_schema"), "非法 jsonMode 取值不得回显: $message")

            // 大小写不敏感。
            val config =
                load(
                    dir,
                    yaml(profiles = profileBlock(overrides = mapOf("jsonMode" to "JSON_OBJECT"))),
                )
            assertEquals(
                LlmResponseFormat.JSON_OBJECT,
                assertNotNull(assertNotNull(config.llm).profiles["extraction"]).jsonMode,
            )
        }

    // ------------------------------------------------------------------ 规则 8：数值范围

    @Test
    fun `llm numeric ranges are enforced without echoing the value`() =
        withDirectory { dir ->
            data class RangeCase(
                val path: String,
                val bounds: String,
                val yaml: String,
            )

            val cases =
                listOf(
                    RangeCase(
                        "context.llm.safetyMarginPercent",
                        "0..100",
                        yaml(margin = "101", profiles = profileBlock(), tasks = taskBlock()),
                    ),
                    RangeCase(
                        "context.llm.profiles.extraction.contextWindowTokens",
                        "1..10000000",
                        yaml(profiles = profileBlock(overrides = mapOf("contextWindowTokens" to "0"))),
                    ),
                    RangeCase(
                        "context.llm.profiles.extraction.contextWindowTokens",
                        "1..10000000",
                        yaml(profiles = profileBlock(overrides = mapOf("contextWindowTokens" to "10000001"))),
                    ),
                    RangeCase(
                        "context.llm.profiles.extraction.connectTimeoutSeconds",
                        "1..300",
                        yaml(profiles = profileBlock(overrides = mapOf("connectTimeoutSeconds" to "0"))),
                    ),
                    RangeCase(
                        "context.llm.profiles.extraction.requestTimeoutSeconds",
                        "1..3600",
                        yaml(profiles = profileBlock(overrides = mapOf("requestTimeoutSeconds" to "3601"))),
                    ),
                    RangeCase(
                        "context.llm.profiles.extraction.socketTimeoutSeconds",
                        "1..3600",
                        yaml(profiles = profileBlock(overrides = mapOf("socketTimeoutSeconds" to "0"))),
                    ),
                    RangeCase(
                        "context.llm.profiles.extraction.maxAttempts",
                        "1..5",
                        yaml(profiles = profileBlock(overrides = mapOf("maxAttempts" to "6"))),
                    ),
                    RangeCase(
                        "context.llm.tasks.EXTRACTION.maxInputTokens",
                        "1..10000000",
                        yaml(profiles = profileBlock(), tasks = taskBlock(overrides = mapOf("maxInputTokens" to "0"))),
                    ),
                    RangeCase(
                        "context.llm.tasks.EXTRACTION.maxOutputTokens",
                        "1..10000000",
                        yaml(profiles = profileBlock(), tasks = taskBlock(overrides = mapOf("maxOutputTokens" to "-1"))),
                    ),
                    RangeCase(
                        "context.llm.tasks.EXTRACTION.totalTimeoutSeconds",
                        "1..3600",
                        yaml(profiles = profileBlock(), tasks = taskBlock(overrides = mapOf("totalTimeoutSeconds" to "0"))),
                    ),
                )

            cases.forEach { case ->
                val failure = expectFailure(dir, case.yaml)
                val message = failure.message.orEmpty()
                assertTrue(message.contains("${case.path} must be in ${case.bounds}"), "${case.path} -> $message")
            }
        }

    // ------------------------------------------------------------------ 规则 9/10：Task 键与引用

    @Test
    fun `llm task keys are matched case insensitively and duplicates are rejected`() =
        withDirectory { dir ->
            val unknown =
                expectFailure(
                    dir,
                    yaml(
                        profiles = profileBlock(),
                        tasks = taskBlock(name = "NOT_A_TASK"),
                    ),
                )
            assertTrue(
                unknown.message.orEmpty().contains("context.llm.tasks.NOT_A_TASK is not a known LLM task"),
                unknown.message.orEmpty(),
            )

            val duplicate =
                expectFailure(
                    dir,
                    yaml(
                        profiles = profileBlock(),
                        tasks = taskBlock(name = "extraction") + taskBlock(name = "EXTRACTION"),
                    ),
                )
            assertTrue(
                duplicate.message.orEmpty().contains("context.llm.tasks.EXTRACTION is defined more than once"),
                duplicate.message.orEmpty(),
            )

            // 小写键等价于枚举名，归一化后可被路由到。
            val lowercase =
                load(
                    dir,
                    yaml(profiles = profileBlock(), tasks = taskBlock(name = "extraction")),
                )
            assertEquals(
                "extraction",
                assertNotNull(assertNotNull(lowercase.llm).tasks[LlmTask.EXTRACTION]).profile,
            )
        }

    @Test
    fun `llm task profile must exist`() =
        withDirectory { dir ->
            val failure =
                expectFailure(
                    dir,
                    yaml(
                        profiles = profileBlock(),
                        tasks = taskBlock(overrides = mapOf("profile" to "quality")),
                    ),
                )
            assertTrue(
                failure
                    .message
                    .orEmpty()
                    .contains("context.llm.tasks.EXTRACTION.profile refers to an unknown profile: quality"),
                failure.message.orEmpty(),
            )
        }

    // ------------------------------------------------------------------ 规则 11：静态预算自洽

    @Test
    fun `llm static budget must fit the profile context window`() =
        withDirectory { dir ->
            val failure =
                expectFailure(
                    dir,
                    yaml(
                        profiles = profileBlock(overrides = mapOf("contextWindowTokens" to "10000")),
                        tasks = taskBlock(overrides = mapOf("maxInputTokens" to "8000", "maxOutputTokens" to "3000")),
                    ),
                )
            assertTrue(
                failure.message.orEmpty().contains("context.llm.tasks.EXTRACTION exceeds the profile context window: extraction"),
                failure.message.orEmpty(),
            )

            // 恰好等于容量是允许的（边界不是越界）。
            val boundary =
                load(
                    dir,
                    yaml(
                        profiles = profileBlock(overrides = mapOf("contextWindowTokens" to "11000")),
                        tasks = taskBlock(overrides = mapOf("maxInputTokens" to "8000", "maxOutputTokens" to "3000")),
                    ),
                )
            assertNotNull(boundary.llm)
        }

    // ------------------------------------------------------------------ 直接 Kotlin 构造

    @Test
    fun `direct kotlin llm config uses the same validation and owns its snapshot`() {
        val failure =
            assertFailsWith<ContextConfigException> {
                LlmConfig(
                    profiles = mapOf("extraction" to profile(maxAttempts = 9)),
                    tasks = mapOf(LlmTask.EXTRACTION to task()),
                )
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
        assertTrue(
            failure.message.orEmpty().contains("context.llm.profiles.extraction.maxAttempts must be in 1..5"),
            failure.message.orEmpty(),
        )

        val unknownProfile =
            assertFailsWith<ContextConfigException> {
                LlmConfig(
                    profiles = mapOf("extraction" to profile()),
                    tasks = mapOf(LlmTask.GOVERNANCE to task(profile = "quality")),
                )
            }
        assertTrue(
            unknownProfile.message.orEmpty().contains("context.llm.tasks.GOVERNANCE.profile refers to an unknown profile: quality"),
            unknownProfile.message.orEmpty(),
        )

        assertFailsWith<ContextConfigException> {
            LlmConfig(profiles = emptyMap(), tasks = emptyMap())
        }

        // 自有快照：构造后修改入参集合不影响实例，且副本再校验后仍是同一份内容。
        val profiles = linkedMapOf("extraction" to profile())
        val tasks = linkedMapOf<LlmTask, LlmTaskConfig>(LlmTask.EXTRACTION to task())
        val config = LlmConfig(profiles = profiles, tasks = tasks, safetyMarginPercent = 20)
        profiles.clear()
        tasks.clear()
        assertEquals(setOf("extraction"), config.profiles.keys)
        assertEquals(setOf(LlmTask.EXTRACTION), config.tasks.keys)
        val copy = config.ownedCopy()
        assertEquals(config.profiles.keys, copy.profiles.keys)
        assertEquals(config.tasks.keys, copy.tasks.keys)
        assertEquals(20, copy.safetyMarginPercent)
    }

    // ------------------------------------------------------------------ 脱敏

    @Test
    fun `llm config never echoes the api key in messages or toString`() =
        withDirectory { dir ->
            val valid =
                load(
                    dir,
                    yaml(profiles = profileBlock(overrides = mapOf("apiKey" to KEY_MARKER))),
                )
            val llm = assertNotNull(valid.llm)
            val profile = assertNotNull(llm.profiles["extraction"])

            assertTrue(profile.toString().contains("apiKey=<redacted>"), profile.toString())
            assertFalse(profile.toString().contains(KEY_MARKER), profile.toString())
            assertFalse(llm.toString().contains(KEY_MARKER), llm.toString())
            assertFalse(valid.toString().contains(KEY_MARKER), valid.toString())
            // baseUrl 只显示 scheme://host[:port]/…，不显示 path。
            assertTrue(profile.toString().contains("baseUrl=https://llm.example.com/…"), profile.toString())

            // 失败路径同样不泄露：键存在但另一个字段未知。
            val failure =
                expectFailure(
                    dir,
                    yaml(
                        profiles =
                            profileBlock(
                                overrides = mapOf("apiKey" to KEY_MARKER),
                                extra = mapOf("unknownField" to "1"),
                            ),
                    ),
                    ContextConfigErrorCode.UNKNOWN_FIELD,
                )
            assertFalse(describeChain(failure).contains(KEY_MARKER), describeChain(failure))
        }

    // ------------------------------------------------------------------ 工具

    private fun expectFailure(
        directory: Path,
        yaml: String,
        code: ContextConfigErrorCode = ContextConfigErrorCode.INVALID_CONFIG,
        environment: (String) -> String? = { null },
    ): ContextConfigException {
        val failure = assertFailsWith<ContextConfigException> { load(directory, yaml, environment) }
        assertEquals(code, failure.errorCode, failure.message.orEmpty())
        return failure
    }

    private fun profile(
        maxAttempts: Int = 2,
        apiKey: String = KEY_MARKER,
    ): LlmProfileConfig =
        LlmProfileConfig(
            name = "extraction",
            baseUrl = "https://llm.example.com/v1",
            apiKey = apiKey,
            model = "fast-model",
            contextWindowTokens = 64_000,
            jsonMode = LlmResponseFormat.JSON_SCHEMA,
            maxAttempts = maxAttempts,
        )

    private fun task(profile: String = "extraction"): LlmTaskConfig =
        LlmTaskConfig(profile = profile, maxInputTokens = 12_000, maxOutputTokens = 3_000)

    private fun yaml(
        margin: String? = null,
        profiles: String = profileBlock(),
        tasks: String = taskBlock(),
        extraLlm: String? = null,
    ): String =
        buildString {
            appendLine("context:")
            appendLine("  llm:")
            if (margin != null) appendLine("    safetyMarginPercent: $margin")
            appendLine("    profiles:")
            append(profiles)
            if (tasks.isNotEmpty()) {
                appendLine("    tasks:")
                append(tasks)
            }
            if (extraLlm != null) appendLine("    $extraLlm")
            appendLine("vfs:")
            appendLine("  database:")
            appendLine("    path: ./data/vfs.db")
        }

    private fun profileBlock(
        name: String = "extraction",
        overrides: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
    ): String {
        val fields =
            linkedMapOf(
                "baseUrl" to "https://llm.example.com/v1",
                "apiKey" to KEY_MARKER,
                "model" to "fast-model",
                "contextWindowTokens" to "64000",
                "jsonMode" to "json_schema",
            )
        applyOverrides(fields, overrides)
        return buildString {
            appendLine("      $name:")
            fields.forEach { (field, value) -> appendLine("        $field: $value") }
            extra.forEach { (field, value) -> appendLine("        $field: $value") }
        }
    }

    private fun taskBlock(
        name: String = "EXTRACTION",
        overrides: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
    ): String {
        val fields =
            linkedMapOf(
                "profile" to "extraction",
                "maxInputTokens" to "12000",
                "maxOutputTokens" to "3000",
            )
        applyOverrides(fields, overrides)
        return buildString {
            appendLine("      $name:")
            fields.forEach { (field, value) -> appendLine("        $field: $value") }
            extra.forEach { (field, value) -> appendLine("        $field: $value") }
        }
    }

    private fun applyOverrides(
        fields: MutableMap<String, String>,
        overrides: Map<String, String>,
    ) {
        overrides.forEach { (field, value) ->
            if (value == OMIT) fields.remove(field) else fields[field] = value
        }
    }

    private fun load(
        directory: Path,
        yaml: String,
        environment: (String) -> String? = { null },
    ): ContextConfig {
        val file = directory.resolve("config.yaml")
        Files.writeString(file, yaml)
        return ContextConfigLoader.load(file, environment)
    }

    private fun describeChain(failure: Throwable): String =
        buildString {
            var current: Throwable? = failure
            while (current != null) {
                appendLine(current.message.orEmpty())
                appendLine(current.stackTraceToString())
                current = current.cause
            }
        }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("ct08-b1-")
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
