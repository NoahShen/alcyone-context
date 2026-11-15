package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A01/A03：三类凭据注册、冲突拒绝、Loader 限额与 Key 脱敏。 */
class ServerAuthTest {
    @Test
    fun `auth registry distinguishes all classes and rejects unknown or malformed tokens`() {
        val config = config()
        val registry = ServerAuthRegistry.from(config)

        assertIs<ServerPrincipal.Ingestion>(registry.authenticate("Bearer ingestion-secret"))
        assertEquals(ServerPrincipal.Read, registry.authenticate("Bearer read-secret"))
        assertEquals(ServerPrincipal.Admin, registry.authenticate("Bearer admin-secret"))
        assertNull(registry.authenticate(null))
        assertNull(registry.authenticate("Bearer unknown"))
        assertEquals(ServerPrincipal.Read, registry.authenticate("bearer read-secret"))
        assertNull(registry.authenticate("Basic read-secret"))
        assertNull(registry.authenticate("Bearer read secret"))
        listOf(" Bearer read-secret", "Bearer read-secret ", "Bearer  read-secret").forEach {
            assertEquals(ServerPrincipal.Read, registry.authenticate(it))
        }
        listOf("bearer ingestion-secret", " Bearer ingestion-secret ", "Bearer  ingestion-secret").forEach {
            assertIs<ServerPrincipal.Ingestion>(registry.authenticate(it))
        }
    }

    @Test
    fun `all credential classes collide without exposing a key`() {
        val marker = "collision-secret-marker"
        val cases: List<() -> ServerConfig> =
            listOf(
                {
                    ServerConfig(
                        clients = mapOf("r1" to IngestionClientConfig("r1", "pi", marker)),
                        readKey = marker,
                    )
                },
                {
                    ServerConfig(
                        clients = mapOf("r1" to IngestionClientConfig("r1", "pi", marker)),
                        adminKey = marker,
                    )
                },
                { ServerConfig(readKey = marker, adminKey = marker) },
            )
        cases.forEach { create ->
            val failure = assertFailsWith<ContextConfigException> { create() }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertFalse(failure.stackTraceToString().contains(marker))
            assertFalse(failure.toString().contains(marker))
        }
    }

    @Test
    fun `loader reads auth limits expands env and keeps secrets out of toString`() {
        val directory = Files.createTempDirectory("ct28-auth-")
        try {
            val path = directory.resolve("config.yaml")
            Files.writeString(
                path,
                """
                server:
                  auth:
                    readKey: ${'$'}{READ_KEY}
                    adminKey: ${'$'}{ADMIN_KEY}
                  limits:
                    maxSearchBodyBytes: 1234
                    maxResourceBytes: 5678
                    requestTimeoutMillis: 90
                """.trimIndent(),
            )
            val config =
                ServerConfigLoader.load(path) { name ->
                    when (name) {
                        "READ_KEY" -> "read-secret"
                        "ADMIN_KEY" -> "admin-secret"
                        else -> null
                    }
                }
            assertEquals("read-secret", config.readKey)
            assertEquals("admin-secret", config.adminKey)
            assertEquals(1234, config.limits.maxSearchBodyBytes)
            assertEquals(5678, config.limits.maxResourceBytes)
            assertEquals(90, config.limits.requestTimeoutMillis)
            assertFalse(config.toString().contains("read-secret"))
            assertFalse(config.toString().contains("admin-secret"))
        } finally {
            Files.deleteIfExists(directory.resolve("config.yaml"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `loader rejects unknown auth and limits fields and non-positive limits`() {
        val directory = Files.createTempDirectory("ct28-auth-errors-")
        try {
            val path = directory.resolve("config.yaml")
            val invalid =
                listOf(
                    "server:\n  auth:\n    unknown: value\n",
                    "server:\n  limits:\n    maxSearchBodyBytes: 0\n",
                    "server:\n  limits:\n    maxResourceBytes: -1\n",
                    "server:\n  limits:\n    requestTimeoutMillis: 0\n",
                )
            invalid.forEach { yaml ->
                Files.writeString(path, yaml)
                assertFailsWith<ContextConfigException> { ServerConfigLoader.load(path) }
            }
        } finally {
            Files.deleteIfExists(directory.resolve("config.yaml"))
            Files.deleteIfExists(directory)
        }
    }

    private fun config(): ServerConfig =
        ServerConfig(
            clients = mapOf("runtime-1" to IngestionClientConfig("runtime-1", "pi", "ingestion-secret")),
            readKey = "read-secret",
            adminKey = "admin-secret",
            limits = ServerLimits(maxSearchBodyBytes = 128, maxResourceBytes = 256, requestTimeoutMillis = 1_000),
        )
}
