package com.github.noahshen.alcyone.context.memory.core.id

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 业务前缀（`R-MEM` / `O-MEM`）与 [SequenceIdGenerator] 委托接线的验证。
 * 算法层面的线程安全、跨日、并发、时钟回拨等测试统一收敛在
 * `common/src/test/.../SequenceIdGeneratorTest.kt`，本类不再重复覆盖。
 */
class MemoryIdGeneratorImplTest {
    private class TestClock(
        var millis: Long,
    ) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.ofHours(8)

        override fun withZone(zone: java.time.ZoneId): Clock = TestClock(millis)

        override fun instant(): Instant = Instant.ofEpochMilli(millis)

        override fun millis(): Long = millis
    }

    @Test
    fun `nextRawMemoryId 前缀 R-MEM，nextOrganizedMemoryId 前缀 O-MEM`() {
        val gen = MemoryIdGeneratorImpl(TestClock(Instant.parse("2026-10-23T10:30:25Z").toEpochMilli()))
        val rawId = gen.nextRawMemoryId()
        val organizedId = gen.nextOrganizedMemoryId()
        assertTrue(rawId.startsWith("R-MEM-"), "实际: $rawId")
        assertTrue(organizedId.startsWith("O-MEM-"), "实际: $organizedId")
        // 委托同一底层生成器后，两业务前缀在同一时钟下格式应保持一致
        assertEquals(
            rawId.length,
            organizedId.length,
            "R-MEM 与 O-MEM 前缀长度不同但整体长度应一致",
        )
    }

    @Test
    fun `两次同毫秒调用序号推进且无重复`() {
        val ms = Instant.parse("2026-10-23T10:30:25.123Z").toEpochMilli()
        val gen = MemoryIdGeneratorImpl(TestClock(ms))
        val a = gen.nextRawMemoryId()
        val b = gen.nextRawMemoryId()
        assertTrue(a != b, "同一毫秒的两次生成不应重复")
        assertEquals("00", a.substringAfterLast('-'))
        assertEquals("01", b.substringAfterLast('-'))
    }

    @Test
    fun `时钟回拨后序号仍严格递增不重复`() {
        val t = Instant.parse("2026-10-23T10:30:25.500Z").toEpochMilli()
        val clock = TestClock(t)
        val gen = MemoryIdGeneratorImpl(clock)
        val first = gen.nextOrganizedMemoryId()
        clock.millis = t - 1000
        val second = gen.nextOrganizedMemoryId()
        assertTrue(first != second, "时钟回拨不应导致重复")
        assertEquals("01", second.substringAfterLast('-'))
    }
}
