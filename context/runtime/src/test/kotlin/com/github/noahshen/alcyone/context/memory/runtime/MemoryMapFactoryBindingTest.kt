package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmHttpStub
import com.github.noahshen.alcyone.context.memory.runtime.llm.chatCompletionBody
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** CT19 B2 A01：Memory Map 纯绑定门、create 零副作用与 start 单 pipeline。 */
class MemoryMapFactoryBindingTest {
    @Test
    fun `memory map null requires no llm and creates no pipeline`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val impl = context as AlcyoneContextImpl
            try {
                assertNull(impl.memoryMapPipelineForTesting())
                runBlocking { context.start() }
                assertNull(impl.memoryMapPipelineForTesting())
                assertFalse(context.status().workers.containsKey("memoryMap"))
            } finally {
                context.close()
            }
        }

    @Test
    fun `explicit memory map binding failures reject create before any side effect`() =
        ContextFixture().use { fixture ->
            val before = fixture.snapshot()
            val stub = LlmHttpStub(200, chatCompletionBody("unused"))
            try {
                val cases =
                    listOf(
                        null,
                        memoryMapLlm(stub, includeTask = false),
                        memoryMapLlm(stub, mode = LlmResponseFormat.PROMPT_ONLY),
                    )
                cases.forEach { llm ->
                    val failure =
                        kotlin.test.assertFailsWith<ContextConfigException> {
                            AlcyoneContextFactory.create(
                                memoryMapContextConfig(fixture, llm),
                                memoryMapVfsConfig(fixture),
                            )
                        }
                    assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
                    assertFalse(failure.message.orEmpty().contains("ct19-b2-map-key-marker"))
                    assertEquals(before, fixture.snapshot(), "绑定失败不得创建文件、目录或锁")
                }
                assertFalse(Files.exists(fixture.contextPath))
                assertFalse(Files.exists(fixture.vfsPath))
            } finally {
                stub.close()
            }
        }

    @Test
    fun `JSON schema and JSON object are accepted while create remains side effect free`() =
        ContextFixture().use { fixture ->
            val stub = LlmHttpStub(200, chatCompletionBody("unused"))
            try {
                listOf(LlmResponseFormat.JSON_SCHEMA, LlmResponseFormat.JSON_OBJECT).forEach { mode ->
                    val before = fixture.snapshot()
                    val context =
                        AlcyoneContextFactory.create(
                            memoryMapContextConfig(fixture, memoryMapLlm(stub, mode)),
                            memoryMapVfsConfig(fixture),
                        )
                    assertEquals(before, fixture.snapshot())
                    assertEquals(ContextLifecycleState.CREATED, context.status().lifecycle)
                    assertEquals(
                        "IDLE",
                        context
                            .status()
                            .workers
                            .getValue("memoryMap")
                            .state.name,
                    )
                }
            } finally {
                stub.close()
            }
        }

    @Test
    fun `legal binding starts exactly one memory map pipeline`() =
        runBlocking {
            ContextFixture().use { fixture ->
                fixture.initialize()
                val stub = LlmHttpStub(200, chatCompletionBody("unused"))
                val generator = RecordingMemoryMapGenerator { mapArtifact(sourceUpdatedAt = null) }
                val context =
                    AlcyoneContextFactory.create(
                        contextConfig = memoryMapContextConfig(fixture, memoryMapLlm(stub)),
                        vfsConfig = memoryMapVfsConfig(fixture),
                        probe = AlcyoneContextFactory.StartProbe { },
                        memoryMapGenerator = generator,
                    )
                val impl = context as AlcyoneContextImpl
                try {
                    assertNull(impl.memoryMapPipelineForTesting())
                    context.start()
                    assertNotNull(impl.memoryMapPipelineForTesting())
                    awaitMemoryMapWiringUntil { generator.calls.get() == 1 }
                } finally {
                    context.close()
                    stub.close()
                }
            }
        }
}
