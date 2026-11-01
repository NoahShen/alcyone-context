package com.github.noahshen.alcyone.context.memory.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ServerSkeletonTest {
    @Test
    fun `package name and ktor server cio engine resolve properly`() {
        assertEquals("com.github.noahshen.alcyone.context.memory.server", ServerSkeletonTest::class.java.packageName)

        val engineClass = CIO::class.java
        assertNotNull(engineClass)
    }

    @Test
    fun `ktor testApplication executes successfully`() {
        testApplication {
            application {
                routing {
                    get("/skeleton/ping") {
                        call.respondText("pong")
                    }
                }
            }

            val response = client.get("/skeleton/ping")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("pong", response.bodyAsText())
        }
    }
}
