package com.github.noahshen.alcyone.context.memory.api

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OrganizedMemoryTest {
    private val createdAt = Instant.parse("2026-10-23T18:30:25.123Z")
    private val updatedAt = Instant.parse("2026-10-23T18:31:00.000Z")

    @Test
    fun `六字段不可变且解构可读`() {
        val mem =
            OrganizedMemory.create(
                id = "O-MEM-20261023-00000001-00",
                content = "当前认知内容",
                domains = listOf("medical", "finance"),
                rawMemoryIds = listOf("raw-1", "raw-2"),
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        val (id, content, domains, rawMemoryIds, cAt, uAt) = mem
        assertEquals("O-MEM-20261023-00000001-00", id)
        assertEquals("当前认知内容", content)
        assertEquals(listOf("medical", "finance"), domains)
        assertEquals(listOf("raw-1", "raw-2"), rawMemoryIds)
        assertSame(createdAt, cAt)
        assertSame(updatedAt, uAt)
    }

    @Test
    fun `domains 与 rawMemoryIds 构造与 copy 路径均保持不可修改快照`() {
        val mutableDomains = mutableListOf("medical", "finance")
        val mutableRawIds = mutableListOf("raw-1", "raw-2")
        val mem =
            OrganizedMemory(
                id = "id-1",
                content = "c",
                domains = mutableDomains,
                rawMemoryIds = mutableRawIds,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )

        // 1. 修改原集合，原对象不受影响
        mutableDomains.clear()
        mutableRawIds.clear()
        assertEquals(2, mem.domains.size)
        assertEquals(2, mem.rawMemoryIds.size)

        // 2. 尝试直接修改暴露的集合视图，必抛 UnsupportedOperationException
        @Suppress("UNCHECKED_CAST")
        val domView = mem.domains as java.util.List<String>
        assertFailsWith<UnsupportedOperationException> { domView.add("tech") }
        assertFailsWith<UnsupportedOperationException> { domView.clear() }

        @Suppress("UNCHECKED_CAST")
        val rawView = mem.rawMemoryIds as java.util.List<String>
        assertFailsWith<UnsupportedOperationException> { rawView.add("raw-3") }
        assertFailsWith<UnsupportedOperationException> { rawView.clear() }

        // 3. copy 传入 mutable list，随后外部清空，副本不受影响
        val copyDomains = mutableListOf("d1", "d2")
        val copyRawIds = mutableListOf("r1", "r2")
        val copied = mem.copy(domains = copyDomains, rawMemoryIds = copyRawIds)
        copyDomains.clear()
        copyRawIds.clear()
        assertEquals(2, copied.domains.size)
        assertEquals(2, copied.rawMemoryIds.size)
    }

    @Test
    fun `equals 与 hashCode 与 toString 行为正确`() {
        val mem1 = OrganizedMemory("id-1", "c", listOf("d1", "d2"), listOf("r1", "r2"), createdAt, updatedAt)
        val mem2 = mem1.copy()
        assertEquals(mem1, mem2)
        assertEquals(mem1.hashCode(), mem2.hashCode())
        val mem3 = mem1.copy(content = "different")
        assertNotEquals(mem1, mem3)
        assertTrue(mem1.toString().contains("id-1"))
    }

    @Test
    fun `id 或 content 空白时拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            OrganizedMemory.create("", "c", emptyList(), emptyList(), createdAt, updatedAt)
        }
        assertFailsWith<IllegalArgumentException> {
            OrganizedMemory.create("id-1", "   ", emptyList(), emptyList(), createdAt, updatedAt)
        }
    }
}
