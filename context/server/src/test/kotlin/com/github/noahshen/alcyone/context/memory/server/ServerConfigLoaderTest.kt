package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
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

/** CT07-B2 server 分区：静态注册、ENV、严格字段、Key 冲突与快照所有权。 */
class ServerConfigLoaderTest {
    @Test
    fun `parses clients with ENV expansion and redacts keys`() =
        withDirectory { dir ->
            val config =
                ServerConfigLoader.load(
                    write(
                        dir,
                        """
                        server:
                          host: 127.0.0.1
                          port: 9090
                          ingestion:
                            clients:
                              runtime-1:
                                runtimeType: pi
                                apiKey: ${'$'}{CT07_RUNTIME_1_KEY}
                              runtime-2:
                                runtimeType: hermes
                                apiKey: ${'$'}{CT07_RUNTIME_2_KEY}
                        """,
                    ),
                ) { name ->
                    when (name) {
                        "CT07_RUNTIME_1_KEY" -> "key-marker-one"
                        "CT07_RUNTIME_2_KEY" -> "key-marker-two"
                        else -> null
                    }
                }
            assertEquals("127.0.0.1", config.host)
            assertEquals(9090, config.port)
            assertEquals(setOf("runtime-1", "runtime-2"), config.clients().keys)
            assertEquals("pi", config.client("runtime-1")?.runtimeType)
            assertEquals("key-marker-one", config.client("runtime-1")?.apiKey)
            assertFalse(config.toString().contains("key-marker-one"), "配置打印不得泄露 Key")
            assertFalse(config.client("runtime-1").toString().contains("key-marker-one"), "client 打印不得泄露 Key")
        }

    @Test
    fun `missing blank and invalid env values are rejected without leaking`() =
        withDirectory { dir ->
            val missing =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        "server:\n  ingestion:\n    clients:\n      r1:\n        runtimeType: pi\n        apiKey: \${CT07_MISSING_KEY}\n",
                    )
                }
            assertEquals(ContextConfigErrorCode.MISSING_ENV, missing.errorCode)
            assertTrue(missing.message.orEmpty().contains("server.ingestion.clients.r1.apiKey"))
            assertTrue(missing.message.orEmpty().contains("CT07_MISSING_KEY"))
            assertNull(missing.cause)

            val blank =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        "server:\n  ingestion:\n    clients:\n      r1:\n        runtimeType: pi\n        apiKey: \${CT07_BLANK_KEY}\n",
                    ) { "   " }
                }
            assertEquals(ContextConfigErrorCode.MISSING_ENV, blank.errorCode)
        }

    @Test
    fun `unknown fields are rejected at every level`() =
        withDirectory { dir ->
            val cases =
                listOf(
                    "server:\n  unknown: 1\n" to "server.unknown",
                    "server:\n  ingestion:\n    unknown: 1\n" to "server.ingestion.unknown",
                    "server:\n  ingestion:\n    clients:\n      r1:\n        runtimeType: pi\n        apiKey: k\n        unknown: 1\n" to
                        "server.ingestion.clients.r1.unknown",
                )
            cases.forEach { (yaml, path) ->
                val failure = assertFailsWith<ContextConfigException> { load(dir, yaml) }
                assertEquals(ContextConfigErrorCode.UNKNOWN_FIELD, failure.errorCode, "case: $path")
                assertTrue(failure.message.orEmpty().contains(path), "期望 $path，实际: ${failure.message}")
            }
        }

    @Test
    fun `type range duplicate key and duplicate yaml key violations are rejected without leaking keys`() =
        withDirectory { dir ->
            val cases =
                listOf(
                    "server:\n  host: []\n" to "server.host",
                    "server:\n  port: not-a-number\n" to "server.port",
                    "server:\n  port: 70000\n" to "server.port",
                    "server:\n  ingestion:\n    clients: []\n" to "server.ingestion.clients",
                    "server:\n  ingestion:\n    clients:\n      r1:\n        runtimeType: pi\n        apiKey: 123\n" to
                        "server.ingestion.clients.r1.apiKey",
                    "server:\n  ingestion:\n    clients:\n      r1:\n        runtimeType: ''\n        apiKey: k\n" to
                        "server.ingestion.clients.r1.runtimeType",
                )
            cases.forEach { (yaml, path) ->
                val failure = assertFailsWith<ContextConfigException> { load(dir, yaml) }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode, "case: $path")
                assertTrue(failure.message.orEmpty().contains(path), "期望 $path，实际: ${failure.message}")
            }

            val duplicateKey =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        "server:\n  ingestion:\n    clients:\n      r1:\n        runtimeType: pi\n        apiKey: unique-key-marker\n" +
                            "      r2:\n        runtimeType: pi\n        apiKey: unique-key-marker\n",
                    )
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, duplicateKey.errorCode)
            assertFalse(duplicateKey.message.orEmpty().contains("unique-key-marker"), "冲突错误不得回显 Key")

            val duplicateYaml =
                assertFailsWith<ContextConfigException> { load(dir, "server:\n  port: 8080\n  port: 9090\n") }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, duplicateYaml.errorCode)
        }

    @Test
    fun `registry snapshots caller maps so later mutation cannot bypass authentication`() {
        val mutable = mutableMapOf("runtime-1" to IngestionClientConfig("runtime-1", "pi", "key-one"))
        val config = ServerConfig(clients = mutable)
        val registry = IngestionClientRegistry.from(config)
        assertNotNull(registry.authenticate("Bearer key-one"))

        mutable["runtime-2"] = IngestionClientConfig("runtime-2", "hermes", "key-two")
        mutable["runtime-1"] = IngestionClientConfig("runtime-1", "pi", "key-replaced")

        assertNotNull(registry.authenticate("Bearer key-one"), "快照必须仍接受原 Key")
        assertNull(registry.authenticate("Bearer key-two"), "后加入的注册不得生效")
        assertNull(registry.authenticate("Bearer key-replaced"), "被替换的 Key 不得生效")
        @Suppress("UNCHECKED_CAST")
        val view = config.clients() as MutableMap<String, IngestionClientConfig>
        assertFailsWith<UnsupportedOperationException> { view["runtime-2"] = mutable.getValue("runtime-2") }
    }

    @Test
    fun `missing server partition yields defaults with no clients`() =
        withDirectory { dir ->
            val config = load(dir, "context:\n  database:\n    path: ./data/context.db\n")
            assertEquals(ServerConfig.DEFAULT_HOST, config.host)
            assertEquals(ServerConfig.DEFAULT_PORT, config.port)
            assertEquals(emptySet(), config.clients().keys)
        }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("ct07-b2-config-")
        try {
            block(directory)
        } finally {
            deleteRecursively(directory)
        }
    }

    private fun load(
        directory: Path,
        yaml: String,
    ): ServerConfig = ServerConfigLoader.load(write(directory, yaml))

    private fun load(
        directory: Path,
        yaml: String,
        environment: (String) -> String?,
    ): ServerConfig = ServerConfigLoader.load(write(directory, yaml), environment)

    private fun write(
        directory: Path,
        yaml: String,
    ): Path = directory.resolve("config.yaml").also { Files.writeString(it, yaml) }

    private fun deleteRecursively(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
