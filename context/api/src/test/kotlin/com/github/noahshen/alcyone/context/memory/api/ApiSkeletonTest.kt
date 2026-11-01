package com.github.noahshen.alcyone.context.memory.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiSkeletonTest {
    @Test
    fun `package name and language dependencies resolve correctly`() {
        assertEquals("com.github.noahshen.alcyone.context.memory.api", ApiSkeletonTest::class.java.packageName)

        val flow: Flow<Int> = flowOf(1, 2, 3)
        val collected = runBlocking { flow.toList() }
        assertEquals(listOf(1, 2, 3), collected)

        val cancellation = CancellationException("skeleton cancellation test")
        assertEquals("skeleton cancellation test", cancellation.message)
    }

    @Test
    fun `api module has zero infrastructure dependencies on classpath`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("org.sqlite.JDBC") }
        assertFailsWith<ClassNotFoundException> { Class.forName("app.cash.sqldelight.db.SqlDriver") }
        assertFailsWith<ClassNotFoundException> { Class.forName("org.yaml.snakeyaml.Yaml") }
        assertFailsWith<ClassNotFoundException> { Class.forName("io.ktor.server.engine.ApplicationEngine") }
        assertFailsWith<ClassNotFoundException> { Class.forName("io.ktor.client.HttpClient") }
    }
}
