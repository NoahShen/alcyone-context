package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.BootstrapContext
import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryMapView
import com.github.noahshen.alcyone.context.memory.api.MemoryOrigin
import com.github.noahshen.alcyone.context.memory.api.MemorySourceRef
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.api.ResourceMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A02/A08: HTTP DTO 与领域对象解耦，并保持公开字段稳定。 */
class ReadHttpSerializationTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `bootstrap serializes all four map states and map watermarks`() {
        val generatedAt = Instant.parse("2026-01-01T01:02:03Z")
        val sourceUpdatedAt = Instant.parse("2026-01-02T04:05:06Z")
        MapStatus.entries.forEach { status ->
            val value =
                BootstrapContext(
                    memoryMap = MemoryMapView("map-body", generatedAt, sourceUpdatedAt),
                    mapStatus = status,
                    capabilities = "capabilities",
                )

            val encoded = json.parseToJsonElement(json.encodeToString(BootstrapResponse.from(value))).jsonObject
            assertEquals(status.name, encoded.getValue("map_status").jsonPrimitive.content)
            assertEquals("capabilities", encoded.getValue("capabilities").jsonPrimitive.content)
            val map = encoded.getValue("memory_map").jsonObject
            assertEquals("map-body", map.getValue("content").jsonPrimitive.content)
            assertEquals(generatedAt.toString(), map.getValue("generated_at").jsonPrimitive.content)
            assertEquals(sourceUpdatedAt.toString(), map.getValue("source_updated_at").jsonPrimitive.content)
            assertFalse(encoded.containsKey("memoryMap"))
        }

        val empty = json.parseToJsonElement(json.encodeToString(BootstrapResponse.from(BootstrapContext(null, MapStatus.UNKNOWN, "c"))))
        assertTrue(empty.jsonObject.getValue("memory_map") is JsonNull)
    }

    @Test
    fun `memory DTOs preserve order fields and both source discriminators`() {
        val organized =
            json
                .parseToJsonElement(
                    json.encodeToString(
                        OrganizedMemoryResponse.from(
                            OrganizedMemory(
                                id = "organized-1",
                                content = "organized-content",
                                domains = listOf("z-domain", "a-domain", "z-domain"),
                                rawMemoryIds = listOf("raw-2", "raw-1"),
                                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                                updatedAt = Instant.parse("2026-01-02T00:00:00Z"),
                            ),
                        ),
                    ),
                ).jsonObject
        assertEquals(setOf("id", "content", "domains", "raw_memory_ids", "created_at", "updated_at"), organized.keys)
        assertEquals(listOf("z-domain", "a-domain", "z-domain"), organized.getValue("domains").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("raw-2", "raw-1"), organized.getValue("raw_memory_ids").jsonArray.map { it.jsonPrimitive.content })

        val raw =
            json
                .parseToJsonElement(
                    json.encodeToString(
                        RawMemoryResponse.from(
                            RawMemory(
                                id = "raw-1",
                                content = "raw-content",
                                generatedBy = MemoryOrigin.EXTRACTION,
                                sources =
                                    listOf(
                                        MemorySourceRef.ConversationTurnRef("runtime-1", "agent-1", "session-1", "turn-1"),
                                        MemorySourceRef.RawMemoryRef("raw-0"),
                                    ),
                                createdAt = Instant.parse("2026-01-03T00:00:00Z"),
                            ),
                        ),
                    ),
                ).jsonObject
        assertEquals(setOf("id", "content", "generated_by", "sources", "created_at"), raw.keys)
        val sources = raw.getValue("sources").jsonArray
        val turn = assertIs<JsonObject>(sources[0])
        val rawSource = assertIs<JsonObject>(sources[1])
        assertEquals("conversation_turn", turn.getValue("type").jsonPrimitive.content)
        assertEquals("turn-1", turn.getValue("session_turn_id").jsonPrimitive.content)
        assertEquals("raw_memory", rawSource.getValue("type").jsonPrimitive.content)
        assertEquals("raw-0", rawSource.getValue("memory_id").jsonPrimitive.content)
    }

    @Test
    fun `resource metadata keeps unavailable statistics as JSON null`() {
        val metadata =
            json
                .parseToJsonElement(
                    json.encodeToString(
                        ResourceMetadataResponse.from(ResourceMetadata("alcyone://resources/file", null, null)),
                    ),
                ).jsonObject
        assertEquals(setOf("uri", "size_bytes", "modified_at"), metadata.keys)
        assertEquals(JsonNull, metadata.getValue("size_bytes"))
        assertEquals(JsonNull, metadata.getValue("modified_at"))
    }
}
