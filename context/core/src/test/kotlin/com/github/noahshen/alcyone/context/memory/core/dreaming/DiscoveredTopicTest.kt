package com.github.noahshen.alcyone.context.memory.core.dreaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveredTopicTest {
    @Test
    fun `four fields are preserved and lists are deeply copied`() {
        val keywords = mutableListOf("one", "two")
        val sources = mutableListOf(1L, 2L)
        val topic = DiscoveredTopic("title", "description", keywords, sources)
        keywords += "three"
        sources += 3L

        assertEquals(listOf("one", "two"), topic.keywords)
        assertEquals(listOf(1L, 2L), topic.sourceTurnIds)
        assertEquals("title", topic.title)
        assertEquals("description", topic.description)
        assertEquals(listOf("one", "two"), topic.component3())
        assertEquals(listOf(1L, 2L), topic.component4())
    }

    @Test
    fun `list properties reject external mutation`() {
        val topic = DiscoveredTopic("title", "description", listOf("keyword"), listOf(1L))

        assertFailsWith<UnsupportedOperationException> { (topic.keywords as MutableList).add("other") }
        assertFailsWith<UnsupportedOperationException> { (topic.sourceTurnIds as MutableList).add(2L) }
    }

    @Test
    fun `copy creates defensive copies without changing values`() {
        val topic = DiscoveredTopic("title", "description", listOf("keyword"), listOf(1L))
        val copy = topic.copy(keywords = listOf("keyword", "next"))

        assertEquals(topic.title, copy.title)
        assertEquals(listOf("keyword", "next"), copy.keywords)
        assertEquals(listOf(1L), copy.sourceTurnIds)
    }

    @Test
    fun `toString exposes only safe counts and lengths`() {
        val topic = DiscoveredTopic("SECRET-TITLE", "SECRET-DESCRIPTION", listOf("SECRET-KEYWORD"), listOf(42L))
        val text = topic.toString()

        assertFalse(text.contains("SECRET"), text)
        assertTrue(text.contains("titleLength=12"), text)
        assertTrue(text.contains("keywordCount=1"), text)
        assertTrue(text.contains("sourceTurnIdCount=1"), text)
    }

    @Test
    fun `equality and hash include semantic fields but not backing identity`() {
        val left = DiscoveredTopic("title", "description", listOf("keyword"), listOf(1L))
        val right = DiscoveredTopic("title", "description", listOf("keyword"), listOf(1L))

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }
}
