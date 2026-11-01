package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CM1 全面复查 F3：三个注册入口共用同一校验，重复/空白/错配一律具名拒绝，不静默覆盖、不泄露 Key。
 */
class ServerClientValidationTest {
    private val keyMarker = "f3-unique-api-key-marker"

    @Test
    fun `collection entry rejects duplicate runtime ids instead of silently overwriting`() {
        val first = IngestionClientConfig("runtime-1", "pi", "key-one")
        val duplicate = IngestionClientConfig("runtime-1", "hermes", "key-two")
        val failure = assertFailsWith<ContextConfigException> { IngestionClientRegistry.of(listOf(first, duplicate)) }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
        assertTrue(failure.message.orEmpty().contains("runtime-1"), "消息应指明 runtimeId: ${failure.message}")
        assertTrue(
            failure.message.orEmpty().contains("more than once"),
            "重复 runtimeId 必须明确拒绝: ${failure.message}",
        )
        assertFalse(failure.message.orEmpty().contains("key-one"))
        assertFalse(failure.message.orEmpty().contains("key-two"))
    }

    @Test
    fun `same api key bound to different runtimes is rejected on every entry`() {
        val clients =
            listOf(
                IngestionClientConfig("runtime-1", "pi", keyMarker),
                IngestionClientConfig("runtime-2", "hermes", keyMarker),
            )
        val viaCollection = assertFailsWith<ContextConfigException> { IngestionClientRegistry.of(clients) }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, viaCollection.errorCode)
        assertTrue(viaCollection.message.orEmpty().contains("apiKey"), "消息应指明字段: ${viaCollection.message}")

        val viaMap =
            assertFailsWith<ContextConfigException> {
                ServerConfig(clients = clients.associateBy { it.runtimeId })
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, viaMap.errorCode)

        // 错误消息不得回显 Key 值。
        listOf(viaCollection, viaMap).forEach { failure ->
            assertFalse(failure.message.orEmpty().contains(keyMarker), "错误消息不得回显 apiKey: ${failure.message}")
        }
    }

    @Test
    fun `map key must equal the configured runtime id on direct construction`() {
        val failure =
            assertFailsWith<ContextConfigException> {
                ServerConfig(clients = mapOf("map-key" to IngestionClientConfig("actual-runtime", "pi", "key-one")))
            }
        assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
        assertTrue(failure.message.orEmpty().contains("map-key"), "消息应指明字段路径: ${failure.message}")
    }

    @Test
    fun `blank runtime id runtime type and api key are rejected`() {
        assertFailsWith<ContextConfigException> {
            IngestionClientRegistry.of(listOf(IngestionClientConfig(" ", "pi", "key-one")))
        }
        assertFailsWith<ContextConfigException> {
            IngestionClientRegistry.of(listOf(IngestionClientConfig("runtime-1", "  ", "key-one")))
        }
        assertFailsWith<ContextConfigException> {
            IngestionClientRegistry.of(listOf(IngestionClientConfig("runtime-1", "pi", "  ")))
        }
        // 直接构造 Map 入口同样拒绝。
        assertFailsWith<ContextConfigException> {
            ServerConfig(clients = mapOf("runtime-1" to IngestionClientConfig("runtime-1", "pi", "")))
        }
    }

    @Test
    fun `registry and config keep their own snapshot when the caller mutates the original map`() {
        val mutable = mutableMapOf("runtime-1" to IngestionClientConfig("runtime-1", "pi", "key-one"))
        val config = ServerConfig(clients = mutable)
        val registry = IngestionClientRegistry.from(config)
        assertNotNull(registry.authenticate("Bearer key-one"))

        mutable["runtime-2"] = IngestionClientConfig("runtime-2", "hermes", "key-two")
        mutable["runtime-1"] = IngestionClientConfig("runtime-1", "pi", "key-replaced")

        assertNotNull(registry.authenticate("Bearer key-one"), "快照必须仍接受原 Key")
        assertNull(registry.authenticate("Bearer key-two"), "后加入的注册不得生效")
        assertNull(registry.authenticate("Bearer key-replaced"), "被替换的 Key 不得生效")
        assertEquals(setOf("runtime-1"), config.clients().keys)
    }

    @Test
    fun `collection entry accepts a valid multi runtime snapshot`() {
        val registry =
            IngestionClientRegistry.of(
                listOf(
                    IngestionClientConfig("runtime-1", "pi", "key-one"),
                    IngestionClientConfig("runtime-2", "hermes", "key-two"),
                ),
            )
        assertEquals("runtime-1", registry.authenticate("Bearer key-one")?.runtimeId)
        assertEquals("runtime-2", registry.authenticate("Bearer key-two")?.runtimeId)
        assertNull(registry.authenticate("Bearer unknown"))
    }
}
