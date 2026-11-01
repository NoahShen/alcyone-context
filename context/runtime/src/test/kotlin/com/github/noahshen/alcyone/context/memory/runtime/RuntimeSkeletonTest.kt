package com.github.noahshen.alcyone.context.memory.runtime

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuntimeSkeletonTest {
    @Test
    fun `package name and key runtime dependencies resolve properly`() {
        assertEquals("com.github.noahshen.alcyone.context.memory.runtime", RuntimeSkeletonTest::class.java.packageName)

        // Ktor Client CIO
        val client = HttpClient(CIO)
        assertNotNull(client)
        client.close()

        // SnakeYAML
        val yaml = Yaml()
        val parsedYaml = yaml.load<Map<String, Any>>("key: value")
        assertEquals("value", parsedYaml["key"])

        // kotlinx.serialization
        val jsonElement = Json.parseToJsonElement("""{"framework":"alcyone"}""")
        assertTrue(jsonElement is JsonObject)

        // cron-utils
        val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))
        val cron = parser.parse("0 0 * * * ?")
        cron.validate()

        // SLF4J
        val logger = LoggerFactory.getLogger(RuntimeSkeletonTest::class.java)
        assertNotNull(logger)

        // VFS Runtime dependency
        val vfsConfigClass = VfsRuntimeConfig::class.java
        assertNotNull(vfsConfigClass)
    }

    @Test
    fun `runtime module strictly isolates ktor server dependencies`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("io.ktor.server.engine.ApplicationEngine") }
    }
}
