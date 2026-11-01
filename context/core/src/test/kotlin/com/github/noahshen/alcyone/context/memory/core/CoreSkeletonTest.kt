package com.github.noahshen.alcyone.context.memory.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoreSkeletonTest {
    @Test
    fun `package name and core coroutines dependencies resolve correctly`() {
        assertEquals("com.github.noahshen.alcyone.context.memory.core", CoreSkeletonTest::class.java.packageName)

        val value = runBlocking(Dispatchers.Default) { 42 }
        assertEquals(42, value)
    }

    @Test
    fun `core module strictly isolates infrastructure dependencies`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("org.sqlite.JDBC") }
        assertFailsWith<ClassNotFoundException> { Class.forName("app.cash.sqldelight.db.SqlDriver") }
        assertFailsWith<ClassNotFoundException> { Class.forName("org.yaml.snakeyaml.Yaml") }
        assertFailsWith<ClassNotFoundException> { Class.forName("io.ktor.server.engine.ApplicationEngine") }
        assertFailsWith<ClassNotFoundException> { Class.forName("io.ktor.client.HttpClient") }
    }
}
