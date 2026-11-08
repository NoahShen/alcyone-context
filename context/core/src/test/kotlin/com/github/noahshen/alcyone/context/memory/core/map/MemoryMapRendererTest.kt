package com.github.noahshen.alcyone.context.memory.core.map

import com.github.noahshen.alcyone.context.common.TimeCodec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryMapRendererTest {
    private val generatedAt: Instant = Instant.parse("2026-11-08T01:02:03.456Z")
    private val sourceUpdatedAt: Instant = Instant.parse("2026-11-08T00:00:00Z")

    @Test
    fun `renderer writes the exact front matter headings and deterministic real domains`() {
        val document = document(listOf("finance", "work"), sourceUpdatedAt)
        val text = MemoryMapRenderer.render(document).toString(Charsets.UTF_8)

        assertEquals(
            "---\n" +
                "generatedAt: ${TimeCodec.format(generatedAt)}\n" +
                "sourceUpdatedAt: ${TimeCodec.format(sourceUpdatedAt)}\n" +
                "---\n\n" +
                "# Memory Overview\n\noverview\n\n" +
                "# Memory Navigation\n\nnavigation\n\n" +
                "## Domains\n\n- finance\n- work\n",
            text,
        )
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `renderer parse round trips non empty and empty source watermarks`() {
        val nonEmpty = document(listOf("finance", "work"), sourceUpdatedAt)
        val empty = document(emptyList(), null)

        assertEquals(nonEmpty, MemoryMapRenderer.parse(MemoryMapRenderer.render(nonEmpty)))
        val parsedEmpty = MemoryMapRenderer.parse(MemoryMapRenderer.render(empty))
        assertEquals(empty, parsedEmpty)
        assertNull(parsedEmpty.sourceUpdatedAt)
        assertTrue(MemoryMapRenderer.render(empty).toString(Charsets.UTF_8).contains("sourceUpdatedAt: null\n"))
        assertTrue(MemoryMapRenderer.render(empty).toString(Charsets.UTF_8).contains("_None._\n"))
    }

    @Test
    fun `strict parser rejects missing duplicate and unknown front matter keys`() {
        listOf(
            validText().replace("generatedAt: ${TimeCodec.format(generatedAt)}\n", ""),
            validText().replace(
                "generatedAt: ${TimeCodec.format(generatedAt)}\n",
                "generatedAt: ${TimeCodec.format(generatedAt)}\ngeneratedAt: ${TimeCodec.format(generatedAt)}\n",
            ),
            validText().replace("sourceUpdatedAt:", "unknown:"),
        ).forEach { text ->
            assertFailsWith<MemoryMapValidationException> { MemoryMapRenderer.parse(text) }
        }
    }

    @Test
    fun `strict parser rejects non canonical times and missing headings`() {
        listOf(
            validText().replace(TimeCodec.format(generatedAt), "2026-11-08T01:02:03Z"),
            validText().replace(TimeCodec.format(sourceUpdatedAt), "not-a-time"),
            validText().replace("# Memory Overview", "# Overview"),
            validText().replace("# Memory Navigation", "# Navigation"),
            validText().replace("## Domains", "## Domain"),
        ).forEach { text ->
            assertFailsWith<MemoryMapValidationException> { MemoryMapRenderer.parse(text) }
        }
    }

    @Test
    fun `strict parser rejects unsorted domains invalid UTF8 and CRLF`() {
        assertFailsWith<MemoryMapValidationException> {
            MemoryMapRenderer.parse(validText().replace("- finance\n- work\n", "- work\n- finance\n"))
        }
        assertFailsWith<MemoryMapValidationException> {
            MemoryMapRenderer.parse(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertFailsWith<MemoryMapValidationException> {
            MemoryMapRenderer.parse(validText().replace("\n", "\r\n"))
        }
    }

    @Test
    fun `renderer propagates its structural validation failure without producing bytes`() {
        val invalid =
            document(listOf("finance"), sourceUpdatedAt).copy(
                navigation = "navigation${MemoryMapValidator.DOMAINS_BOUNDARY}tail",
            )

        assertFailsWith<MemoryMapValidationException> { MemoryMapRenderer.render(invalid) }
    }

    private fun validText(): String =
        MemoryMapRenderer.render(document(listOf("finance", "work"), sourceUpdatedAt)).toString(Charsets.UTF_8)

    private fun document(
        domains: List<String>,
        source: Instant?,
    ): MemoryMapDocument =
        MemoryMapDocument(
            overview = "overview",
            navigation = "navigation",
            domains = domains,
            generatedAt = generatedAt,
            sourceUpdatedAt = source,
        )
}
