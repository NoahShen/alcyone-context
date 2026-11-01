package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PersistenceSkeletonTest {
    @Test
    fun `package name and sqldelight sqlite driver resolve and execute`() {
        assertEquals(
            "com.github.noahshen.alcyone.context.memory.persistence.sqldelight",
            PersistenceSkeletonTest::class.java.packageName,
        )

        val jdbcClass = Class.forName("org.sqlite.JDBC")
        assertNotNull(jdbcClass)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use { d ->
            d.execute(null, "CREATE TABLE test_table (id INTEGER PRIMARY KEY, name TEXT NOT NULL);", 0)
            d.execute(null, "INSERT INTO test_table (id, name) VALUES (1, 'skeleton');", 0)
        }
    }

    @Test
    fun `persistence module strictly isolates ktor server and yaml dependencies`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("org.yaml.snakeyaml.Yaml") }
        assertFailsWith<ClassNotFoundException> { Class.forName("io.ktor.server.engine.ApplicationEngine") }
    }
}
