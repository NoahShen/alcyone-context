package com.github.noahshen.alcyone.context.memory.integration

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ContextIntegrationSkeletonTest {
    @Test
    fun `package name and cross module integration resolves successfully`() {
        assertEquals(
            "com.github.noahshen.alcyone.context.memory.integration",
            ContextIntegrationSkeletonTest::class.java.packageName,
        )

        // Runtime transitivity on test runtime classpath: SnakeYAML
        val yamlClass = Class.forName("org.yaml.snakeyaml.Yaml")
        assertNotNull(yamlClass)

        // Persistence transitivity on test runtime classpath: SQLDelight Driver & SQLite JDBC
        val driverClass = Class.forName("app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver")
        assertNotNull(driverClass)
        val jdbcClass = Class.forName("org.sqlite.JDBC")
        assertNotNull(jdbcClass)

        // Server dependency on test runtime classpath: CIO engine
        val cioClass = Class.forName("io.ktor.server.cio.CIO")
        assertNotNull(cioClass)
    }

    @Test
    fun `e2e testApplication integrates server and runtime dependencies`() {
        testApplication {
            application {
                routing {
                    get("/integration/ping") {
                        call.respondText("integration-pong")
                    }
                }
            }

            val response = client.get("/integration/ping")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("integration-pong", response.bodyAsText())
        }
    }
}
