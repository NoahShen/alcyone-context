package com.github.noahshen.alcyone.context.memory.core.map

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryMapValidatorTest {
    private val base: Instant = Instant.parse("2026-11-08T01:00:00Z")
    private val snapshot =
        MemoryMapSnapshot(
            listOf(
                organized("O-MEM-0001", listOf("work", "preferences")),
                organized("O-MEM-0002", listOf("finance")),
            ),
            base,
        )

    @Test
    fun `valid draft is accepted and domains are stably deduplicated`() {
        val validated =
            MemoryMapValidator.validate(
                MemoryMapDraft(" overview ", " navigation ", listOf("work", "work", "finance")),
                snapshot,
            )

        assertEquals(" overview ", validated.overview)
        assertEquals(" navigation ", validated.navigation)
        assertEquals(listOf("work", "finance"), validated.domains)
    }

    @Test
    fun `blank overview or navigation is rejected`() {
        listOf(
            MemoryMapDraft("", "navigation", emptyList()),
            MemoryMapDraft("   ", "navigation", emptyList()),
            MemoryMapDraft("overview", "", emptyList()),
            MemoryMapDraft("overview", "\n", emptyList()),
        ).forEach { draft ->
            assertFailsWith<MemoryMapValidationException> { MemoryMapValidator.validate(draft, snapshot) }
        }
    }

    @Test
    fun `blank duplicate and unknown domains follow the frozen subset rules`() {
        val emptyDomains = MemoryMapDraft("overview", "navigation", emptyList())
        assertEquals(emptyDomains, MemoryMapValidator.validate(emptyDomains, snapshot))

        listOf(" ", "\n").forEach { blank ->
            assertFailsWith<MemoryMapValidationException> {
                MemoryMapValidator.validate(MemoryMapDraft("overview", "navigation", listOf(blank)), snapshot)
            }
        }
        assertFailsWith<MemoryMapValidationException> {
            MemoryMapValidator.validate(MemoryMapDraft("overview", "navigation", listOf("unknown")), snapshot)
        }
    }

    @Test
    fun `reserved renderer boundaries and non LF text are rejected`() {
        listOf(
            MemoryMapDraft("overview${MemoryMapValidator.NAVIGATION_BOUNDARY}tail", "navigation", emptyList()),
            MemoryMapDraft("overview", "navigation${MemoryMapValidator.DOMAINS_BOUNDARY}tail", emptyList()),
            MemoryMapDraft("overview\r\ntext", "navigation", emptyList()),
        ).forEach { draft ->
            assertFailsWith<MemoryMapValidationException> { MemoryMapValidator.validate(draft, snapshot) }
        }
    }

    @Test
    fun `large text is preserved because D9 does not add a second silent truncation limit`() {
        val overview = "o".repeat(250_000)
        val navigation = "n".repeat(250_000)

        val validated = MemoryMapValidator.validate(MemoryMapDraft(overview, navigation, emptyList()), snapshot)

        assertEquals(overview.length, validated.overview.length)
        assertEquals(navigation.length, validated.navigation.length)
    }

    @Test
    fun `document domains must already be unique and sorted`() {
        val valid = document(listOf("finance", "work"))
        assertEquals(valid, MemoryMapValidator.validateDocument(valid))

        assertFailsWith<MemoryMapValidationException> {
            MemoryMapValidator.validateDocument(document(listOf("work", "finance")))
        }
        assertFailsWith<MemoryMapValidationException> {
            MemoryMapValidator.validateDocument(document(listOf("work", "work")))
        }
    }

    @Test
    fun `snapshot rejects T1 mismatch and non id order`() {
        assertFailsWith<MemoryMapSnapshotException> {
            MemoryMapSnapshot(listOf(organized("O-MEM-0001", listOf("d"))), null)
        }
        assertFailsWith<MemoryMapSnapshotException> {
            MemoryMapSnapshot(
                listOf(
                    organized("O-MEM-0002", listOf("d")),
                    organized("O-MEM-0001", listOf("d")),
                ),
                base,
            )
        }
    }

    private fun organized(
        id: String,
        domains: List<String>,
    ): OrganizedMemory =
        OrganizedMemory(
            id = id,
            content = "content-$id",
            domains = domains,
            rawMemoryIds = emptyList(),
            createdAt = base,
            updatedAt = base,
        )

    private fun document(domains: List<String>): MemoryMapDocument =
        MemoryMapDocument(
            overview = "overview",
            navigation = "navigation",
            domains = domains,
            generatedAt = base,
            sourceUpdatedAt = base,
        )
}
