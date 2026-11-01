package com.github.noahshen.alcyone.context.common

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SequenceIdGeneratorTest {
    private class TestClock(
        var millis: Long,
    ) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.ofHours(8)

        override fun withZone(zone: java.time.ZoneId): Clock = TestClock(millis)

        override fun instant(): Instant = Instant.ofEpochMilli(millis)

        override fun millis(): Long = millis
    }

    @Test
    fun `nextId 前缀由入参决定且格式固定`() {
        val gen = SequenceIdGenerator(TestClock(Instant.parse("2026-10-23T10:30:25Z").toEpochMilli()))
        val id = gen.nextId("ABC")
        assertTrue(id.startsWith("ABC-"), "实际: $id")
        val parts = id.split("-")
        assertEquals(4, parts.size)
        assertEquals(8, parts[2].length, "当天毫秒应为 8 位补零")
        assertEquals(2, parts[3].length, "序列应为 2 位补零")
        assertTrue(parts[1].matches("\\d{8}".toRegex()), "日期部分应为 8 位数字: ${parts[1]}")
    }

    @Test
    fun `空 prefix 被拒绝`() {
        val gen = SequenceIdGenerator(TestClock(Instant.parse("2026-10-23T10:30:25Z").toEpochMilli()))
        assertFailsWith<IllegalArgumentException> { gen.nextId("") }
    }

    @Test
    fun `固定时钟下同毫秒生成的 ID 序号递增且唯一`() {
        val ms = Instant.parse("2026-10-23T10:30:25.123Z").toEpochMilli()
        val gen = SequenceIdGenerator(TestClock(ms))
        val ids = List(50) { gen.nextId("T") }
        assertEquals(50, ids.toSet().size)
        // 序号段：00, 01, ..., 49
        assertEquals(ids.last().substringAfterLast('-'), "49")
    }

    @Test
    fun `同毫秒 101 个 ID 全部唯一，序号超过 99 则逻辑毫秒推进并归零`() {
        val ms = Instant.parse("2026-10-23T10:30:25.999Z").toEpochMilli()
        val gen = SequenceIdGenerator(TestClock(ms))
        val ids = List(101) { gen.nextId("T") }
        assertEquals(101, ids.toSet().size, "同毫秒 101 个 ID 应全部唯一")
        // 第 100 个（索引 99）序号 99，第 101 个（索引 100）溢出后序号归零为 00
        assertEquals("99", ids[99].substringAfterLast('-'))
        assertEquals("00", ids[100].substringAfterLast('-'))
        assertNotEquals(ids[99], ids[100])
    }

    @Test
    fun `跨日 ID 的日期部分随上海日历日切换`() {
        // 2026-10-23 23:59:59.999 +08
        val beforeMs = Instant.parse("2026-10-23T15:59:59.999Z").toEpochMilli()
        // 2026-10-24 00:00:00.000 +08
        val afterMs = Instant.parse("2026-10-23T16:00:00.000Z").toEpochMilli()
        val clock = TestClock(beforeMs)
        val gen = SequenceIdGenerator(clock)
        val beforeId = gen.nextId("T")
        assertEquals("20261023", beforeId.split("-")[2 - 1])
        clock.millis = afterMs
        val afterId = gen.nextId("T")
        assertEquals("20261024", afterId.split("-")[2 - 1])
        assertNotEquals(beforeId, afterId)
    }

    @Test
    fun `并发 101 线程生成无重复`() {
        val ms = Instant.parse("2026-10-23T10:30:25Z").toEpochMilli()
        val gen = SequenceIdGenerator(TestClock(ms))
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(101)
        val executor: ExecutorService = Executors.newFixedThreadPool(101)
        val results = CopyOnWriteArrayList<String>()
        val exceptions = CopyOnWriteArrayList<Throwable>()
        try {
            repeat(101) {
                executor.submit {
                    startLatch.await()
                    try {
                        results.add(gen.nextId("T"))
                    } catch (t: Throwable) {
                        exceptions.add(t)
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "101 个并发任务在 30s 内未全部完成")
            assertTrue("子任务异常: ${exceptions.map { it.message ?: it } }") { exceptions.isEmpty() }
            assertEquals(101, results.size)
            assertEquals(101, results.toSet().size, "并发生成的 101 个 ID 应全部唯一")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `时钟回拨不导致进程内 ID 重复`() {
        val t = Instant.parse("2026-10-23T10:30:25.500Z").toEpochMilli()
        val clock = TestClock(t)
        val gen = SequenceIdGenerator(clock)
        val first = gen.nextId("T") // t, seq 00

        // 时钟回拨到 t - 1000，逻辑毫秒不应退缩，继续在 t 推进序号
        clock.millis = t - 1000
        val second = gen.nextId("T") // t, seq 01
        assertNotEquals(first, second)
        assertEquals("01", second.substringAfterLast('-'))

        // 时钟再回拨更早
        clock.millis = t - 5000
        val third = gen.nextId("T") // t, seq 02
        assertNotEquals(first, third)
        assertNotEquals(second, third)
        assertEquals("02", third.substringAfterLast('-'))

        // 时钟向前推进超过逻辑毫秒，重新采用当前毫秒
        clock.millis = t + 10000
        val fourth = gen.nextId("T") // t+10000, seq 00
        assertNotEquals(fourth, third)
        assertEquals("00", fourth.substringAfterLast('-'))
    }

    @Test
    fun `切换阿拉伯 Locale 后 ID 补零仍使用 ASCII 数字`() {
        val original = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale("ar", "EG"))
            val ms = Instant.parse("2026-10-23T10:30:25.123Z").toEpochMilli()
            val gen = SequenceIdGenerator(TestClock(ms))
            val id = gen.nextId("T")
            val suffix = id.substringAfterLast('-')
            assertTrue(suffix.all { it in '0'..'9' }, "实际 suffix: $suffix")
            assertEquals("00", suffix)
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original)
        }
    }
}
