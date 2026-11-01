package com.github.noahshen.alcyone.context.memory.api

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RawMemoryTest {
    private val turnRef = MemorySourceRef.ConversationTurnRef("rt", "ag", "ss", "tt")
    private val rawRef = MemorySourceRef.RawMemoryRef("raw-1")
    private val rawRef2 = MemorySourceRef.RawMemoryRef("raw-2")
    private val createdAt = Instant.parse("2026-10-23T18:30:25.123Z")

    @Test
    fun `五字段不可变且解构可读`() {
        val mem =
            RawMemory.create(
                id = "R-MEM-20261023-00000001-00",
                content = "原子记忆内容",
                generatedBy = MemoryOrigin.EXTRACTION,
                sources = listOf(turnRef, rawRef),
                createdAt = createdAt,
            )
        val (id, content, origin, sources, time) = mem
        assertEquals("R-MEM-20261023-00000001-00", id)
        assertEquals("原子记忆内容", content)
        assertSame(MemoryOrigin.EXTRACTION, origin)
        assertEquals(listOf(turnRef, rawRef), sources)
        assertSame(createdAt, time)
    }

    @Test
    fun `sources 构造与 copy 路径均保持不可修改快照且多元素修改被隔离`() {
        // 1. 测试传入 mutable list（至少 2 个元素），调用方在外部 clear/add，原对象集合不变
        val mutableOriginal: MutableList<MemorySourceRef> = mutableListOf(turnRef, rawRef)
        val mem =
            RawMemory(
                id = "R-MEM-test",
                content = "evidence",
                generatedBy = MemoryOrigin.EXTRACTION,
                sources = mutableOriginal,
                createdAt = Instant.EPOCH,
            )
        mutableOriginal.clear()
        assertEquals(2, mem.sources.size)
        assertEquals(turnRef, mem.sources[0])
        assertEquals(rawRef, mem.sources[1])

        mutableOriginal.add(rawRef2)
        assertEquals(2, mem.sources.size)

        // 2. 测试调用 copy(..., sources = mutable) 后，调用方在外部 clear/add，新对象的集合不变
        val mutableForCopy = java.util.ArrayList<MemorySourceRef>(mem.sources)
        val copied = mem.copy(sources = mutableForCopy)
        mutableForCopy.clear()
        assertEquals(2, copied.sources.size)
        assertEquals(turnRef, copied.sources[0])
        assertEquals(rawRef, copied.sources[1])

        // 3. 尝试修改返回的集合，必抛 UnsupportedOperationException
        @Suppress("UNCHECKED_CAST")
        val mutableView = mem.sources as java.util.List<MemorySourceRef>
        assertFailsWith<UnsupportedOperationException> { mutableView.add(rawRef2) }
        assertFailsWith<UnsupportedOperationException> { mutableView.clear() }

        // 4. 断言 Architect ModelCopyProbe 核心逻辑能成功通过
        val probeOriginal =
            RawMemory.create(
                "R-MEM-test",
                "evidence",
                MemoryOrigin.EXTRACTION,
                listOf(MemorySourceRef.RawMemoryRef("source1"), MemorySourceRef.RawMemoryRef("source2")),
                Instant.EPOCH,
            )
        val probeMutable = java.util.ArrayList(probeOriginal.sources)
        val probeCopied = probeOriginal.copy(sources = probeMutable)
        probeMutable.clear()
        assertTrue(!probeCopied.sources.isEmpty(), "Public copy bypasses immutable snapshot")
        assertEquals(2, probeCopied.sources.size)
    }

    @Test
    fun `equals 与 hashCode 与 toString 行为正确`() {
        val mem1 = RawMemory("id-1", "c", MemoryOrigin.EXTRACTION, listOf(turnRef, rawRef), createdAt)
        val mem2 = mem1.copy()
        assertEquals(mem1, mem2)
        assertEquals(mem1.hashCode(), mem2.hashCode())
        val mem3 = mem1.copy(content = "c2")
        assertNotEquals(mem1, mem3)
        assertTrue(mem1.toString().contains("id-1"))
    }

    @Test
    fun `id 或 content 空白时拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            RawMemory.create("", "c", MemoryOrigin.EXTRACTION, emptyList(), createdAt)
        }
        assertFailsWith<IllegalArgumentException> {
            RawMemory.create("id-1", "   ", MemoryOrigin.EXTRACTION, emptyList(), createdAt)
        }
    }

    @Test
    fun `MemoryOrigin 仅包含 EXTRACTION 和 DREAMING`() {
        assertEquals(
            setOf("EXTRACTION", "DREAMING"),
            MemoryOrigin.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `MemorySourceRef 支持 ConversationTurnRef 与 RawMemoryRef 两种形态`() {
        assertEquals("rt", turnRef.runtimeId)
        assertEquals("tt", turnRef.sessionTurnId)
        assertEquals("raw-1", rawRef.memoryId)
    }
}
