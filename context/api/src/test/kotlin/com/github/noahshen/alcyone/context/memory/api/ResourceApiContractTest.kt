package com.github.noahshen.alcyone.context.memory.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ResourceApiContractTest {
    @Test
    fun `ResourceMetadata exposes only the frozen nullable fields`() {
        val metadata = ResourceMetadata("alcyone://resources/a.bin", sizeBytes = null, modifiedAt = null)

        assertEquals("alcyone://resources/a.bin", metadata.uri)
        assertNull(metadata.sizeBytes)
        assertNull(metadata.modifiedAt)

        val fieldTypes = ResourceMetadata::class.java.declaredFields.associate { it.name to it.type }
        assertEquals(String::class.java, fieldTypes.getValue("uri"))
        assertEquals(java.lang.Long::class.java, fieldTypes.getValue("sizeBytes"))
        assertEquals(Instant::class.java, fieldTypes.getValue("modifiedAt"))
        assertEquals(setOf("uri", "sizeBytes", "modifiedAt"), fieldTypes.keys)
    }

    @Test
    fun `AlcyoneContext exposes the three resource methods with frozen return types`() {
        val loadMeta = AlcyoneContext::class.java.methods.single { it.name == "loadMeta" }
        val read = AlcyoneContext::class.java.methods.single { it.name == "read" }
        val readStream = AlcyoneContext::class.java.methods.single { it.name == "readStream" }

        assertEquals(Object::class.java, loadMeta.returnType)
        assertEquals(String::class.java, loadMeta.parameterTypes.first())
        assertEquals(Continuation::class.java, loadMeta.parameterTypes.last())
        assertEquals(Object::class.java, read.returnType)
        assertEquals(String::class.java, read.parameterTypes.first())
        assertEquals(Continuation::class.java, read.parameterTypes.last())
        assertEquals(Flow::class.java, readStream.returnType)
        assertEquals(listOf(String::class.java), readStream.parameterTypes.toList())
    }

    @Test
    fun `Resource API module keeps infrastructure classes off its classpath`() {
        listOf(
            "org.sqlite.JDBC",
            "app.cash.sqldelight.db.SqlDriver",
            "org.yaml.snakeyaml.Yaml",
            "io.ktor.client.HttpClient",
            "org.apache.opendal.Operator",
        ).forEach { className ->
            assertFailsWith<ClassNotFoundException>(className) { Class.forName(className) }
        }
    }

    @Test
    fun `third party implementations retain safe cold defaults`() =
        runBlocking {
            val context =
                object : AlcyoneContext {
                    override suspend fun ingestConversation(turn: ConversationTurn): IngestResult = error("not used")

                    override suspend fun start() = Unit

                    override fun close() = Unit

                    override suspend fun closeAsync() = Unit

                    override suspend fun getStatus(): ContextStatus = error("not used")
                }
            val cold = context.readStream("alcyone://resources/a")

            listOf<suspend () -> Unit>(
                { context.loadMeta("alcyone://resources/a") },
                { context.read("alcyone://resources/a") },
                { cold.toList() },
            ).forEach { operation ->
                val failure = assertFailsWith<ContextResourceException> { operation() }
                assertEquals(ContextResourceErrorCode.UNAVAILABLE, failure.errorCode)
                assertNull(failure.cause)
            }
        }
}
