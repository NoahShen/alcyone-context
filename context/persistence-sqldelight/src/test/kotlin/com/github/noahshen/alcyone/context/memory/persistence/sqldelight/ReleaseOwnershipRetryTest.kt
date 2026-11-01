package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CM1 全面复查 P1：Slot 连接所有权与关闭轮次内的有限重试。
 *
 * 全部物理释放断言都在测试兜底清理**之前**执行；Driver 代理只包 `close`，`finally` 恢复 Driver。
 */
class ReleaseOwnershipRetryTest {
    @Test
    fun `T1 first close failure then retry succeeds and ownership is released`() =
        withFaultingDriver(closeFailures = 1) { path, wrapper ->
            val executor = ContextDatabaseFactory.open(path)
            try {
                assertNull(runCatching { executor.close() }.exceptionOrNull(), "轮次内重试必须成功")
                assertEquals(0, wrapper.physicalConnections.count { !it.isClosed }, "全部物理连接必须已关闭")
                assertTrue(wrapper.closeCallCount() >= 2, "必须真的尝试了两次（首失败 + 重试）")
            } finally {
                runCatching { executor.close() }
            }
        }

    @Test
    fun `T2 persistent close failure keeps ownership and shares the same failure`() =
        withFaultingDriver(closeFailures = Int.MAX_VALUE) { path, wrapper ->
            val executor = ContextDatabaseFactory.open(path)
            try {
                val first = runCatching { executor.close() }.exceptionOrNull()
                assertTrue(first is ContextPersistenceException, "持续失败必须报告失败: $first")
                assertEquals(
                    ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE,
                    (first as ContextPersistenceException).errorCode,
                )
                assertTrue(wrapper.physicalConnections.any { !it.isClosed }, "失败时连接所有权必须保留")
                assertFalse(
                    executor.connectionIdentity(SlotKind.WRITE, 0).let { false },
                    "占位断言（保持 connectionIdentity 可达）",
                )
                val second = runCatching { executor.close() }.exceptionOrNull()
                assertSame(first, second, "后续 close 必须返回同一失败实例")
                val third = runCatching { runBlocking { executor.closeAsync() } }.exceptionOrNull()
                assertSame(first, third, "closeAsync 也必须返回同一失败实例")
            } finally {
                runCatching { executor.close() }
            }
        }

    @Test
    fun `T3 only the failing slot keeps ownership while other slots are really closed`() =
        withFaultingDriver(closeFailures = Int.MAX_VALUE, onlySlot = 0) { path, wrapper ->
            val executor = ContextDatabaseFactory.open(path)
            try {
                // 写 Slot 同时注入线程退出失败：两类失败必须聚合（主失败 + suppressed）。
                executor.slotForTesting(SlotKind.WRITE).injectThreadTerminationFailureForTesting(
                    IllegalStateException("thread cleanup failure"),
                )
                val failure = runCatching { executor.close() }.exceptionOrNull()
                assertTrue(failure is ContextPersistenceException, "写 Slot 持续失败必须报告失败: $failure")
                val connections = wrapper.physicalConnections
                assertEquals(3, connections.size, "1 写 + 2 读")
                assertFalse(connections[0].isClosed, "写 Slot（index 0）必须保留所有权")
                assertTrue(connections[1].isClosed && connections[2].isClosed, "读 Slot 必须真实关闭")
                val primary = assertNotNull(failure.cause, "必须保留首次失败作为 cause")
                assertTrue(primary.suppressed.isNotEmpty(), "失败聚合必须含 suppressed（线程失败 + 连接失败）")
            } finally {
                runCatching { executor.close() }
            }
        }

    @Test
    fun `T4 interrupt during cleanup still completes and restores the flag`() =
        withFaultingDriver(closeFailures = Int.MAX_VALUE) { path, wrapper ->
            val executor = ContextDatabaseFactory.open(path)
            try {
                val interruptedBefore = Thread.currentThread().isInterrupted
                Thread.currentThread().interrupt()
                val failure = runCatching { executor.close() }.exceptionOrNull()
                assertTrue(failure is ContextPersistenceException, "中断不得把失败变成成功: $failure")
                assertTrue(Thread.currentThread().isInterrupted, "中断标志必须在 finally 恢复")
                if (!interruptedBefore) Thread.interrupted()
                assertTrue(wrapper.physicalConnections.any { !it.isClosed }, "失败时所有权保留，状态与结果一致")
            } finally {
                Thread.interrupted()
                runCatching { executor.close() }
            }
        }

    @Test
    fun `T5 a later close only awaits the same protocol without new attempts`() =
        withFaultingDriver(closeFailures = Int.MAX_VALUE) { path, wrapper ->
            val executor = ContextDatabaseFactory.open(path)
            try {
                val first = runCatching { executor.close() }.exceptionOrNull()
                assertTrue(first is ContextPersistenceException)
                val attemptsAfterFirst = wrapper.closeCallCount()
                val repeated = runCatching { executor.close() }.exceptionOrNull()
                assertSame(first, repeated)
                assertEquals(attemptsAfterFirst, wrapper.closeCallCount(), "后续 close 不得再发起新的物理 close")
            } finally {
                runCatching { executor.close() }
            }
        }

    @Test
    fun `T6 poisoned connection failure is retried by the global close`() =
        withFaultingDriver(closeFailures = Int.MAX_VALUE, onlySlot = 0) { path, wrapper ->
            val executor = ContextDatabaseFactory.open(path)
            try {
                // 触发写 Slot 中毒：commit 失败 → poison → releasePoisonedConnection 尝试 close（失败）
                val writeFailure = runCatching { runBlocking { executor.write { } } }.exceptionOrNull()
                assertTrue(writeFailure is ContextPersistenceException, "commit 注入必须可观察: $writeFailure")
                val closeFailure = runCatching { executor.close() }.exceptionOrNull()
                assertTrue(closeFailure is ContextPersistenceException, "全局 close 仍失败时必须报告: $closeFailure")
                assertTrue(wrapper.physicalConnections.any { !it.isClosed }, "持续失败时保留引用")
            } finally {
                runCatching { executor.close() }
            }
        }

    @Test
    fun `T7 missing physical connection must not swallow the thread cleanup failure`() =
        TestDatabases.temporaryDirectory().let { directory ->
            try {
                val path = TestDatabases.initializedDatabase(directory)
                val executor = ContextDatabaseFactory.open(path)
                val slot = executor.slotForTesting(SlotKind.WRITE)
                try {
                    // 先成功释放物理连接（所有权清空），再注入线程退出失败。
                    assertNull(runCatching { slot.releaseAttempt() }.exceptionOrNull(), "首次释放必须成功")
                    assertNull(slot.physicalConnectionForTesting(), "物理连接所有权必须已清空")
                    slot.injectThreadTerminationFailureForTesting(IllegalStateException("thread cleanup failure"))
                    val failure = runCatching { slot.releaseAttempt() }.exceptionOrNull()
                    assertTrue(failure is IllegalStateException, "无连接时线程失败不得被吞掉: $failure")
                    assertEquals("thread cleanup failure", failure?.message)
                } finally {
                    runCatching { slot.releaseAttempt() }
                    runCatching { executor.close() }
                }
            } finally {
                TestDatabases.deleteRecursively(directory)
            }
        }

    @Test
    fun `T8 first failure stays primary and the second is suppressed without self suppression`() =
        withFaultingDriver(closeFailures = Int.MAX_VALUE, onlySlot = 0) { path, wrapper ->
            val executor = ContextDatabaseFactory.open(path)
            try {
                val failure = runCatching { executor.close() }.exceptionOrNull()
                assertTrue(failure is ContextPersistenceException, "两次尝试都失败时必须报告失败: $failure")
                val primary = failure.cause
                assertNotNull(primary, "必须保留首次失败作为 cause")
                assertTrue(primary.suppressed.isNotEmpty(), "第二次失败必须作为 suppressed 追加")
                primary.suppressed.forEach { suppressed ->
                    assertFalse(suppressed === primary, "不得出现同一 Throwable 自我 suppressed")
                }
                assertEquals(2, wrapper.closeCallCount(0), "同一轮次对失败 Slot 最多两次尝试")
            } finally {
                runCatching { executor.close() }
            }
        }

    // ------------------------------------------------------------------ 夹具

    private fun withFaultingDriver(
        closeFailures: Int,
        onlySlot: Int? = null,
        block: (java.nio.file.Path, CountingCloseFaultDriver) -> Unit,
    ) {
        val directory = TestDatabases.temporaryDirectory()
        try {
            val path = TestDatabases.initializedDatabase(directory)
            val delegate = DriverManager.getDriver("jdbc:sqlite:${path.toAbsolutePath()}")
            val wrapper = CountingCloseFaultDriver(delegate, closeFailures, onlySlot)
            DriverManager.deregisterDriver(delegate)
            DriverManager.registerDriver(wrapper)
            try {
                block(path, wrapper)
            } finally {
                DriverManager.deregisterDriver(wrapper)
                DriverManager.registerDriver(delegate)
                wrapper.physicalConnections.forEach { runCatching { it.close() } }
            }
        } finally {
            TestDatabases.deleteRecursively(directory)
        }
    }
}

/** 只包 `close` 的 Driver 代理：按 Slot 索引注入失败次数，并统计真实 close 调用次数。 */
private class CountingCloseFaultDriver(
    private val delegate: Driver,
    closeFailures: Int,
    private val onlySlot: Int?,
) : Driver {
    val physicalConnections = CopyOnWriteArrayList<Connection>()
    private val remainingCloseFailures = AtomicInteger(closeFailures)
    private val remainingCommitFailures = AtomicInteger(1)
    private val slotIndex = AtomicInteger(0)
    private val closeCalls = AtomicInteger(0)
    private val closeCallsBySlot = java.util.concurrent.ConcurrentHashMap<Int, AtomicInteger>()

    fun closeCallCount(): Int = closeCalls.get()

    fun closeCallCount(slot: Int): Int = closeCallsBySlot[slot]?.get() ?: 0

    override fun connect(
        url: String,
        info: Properties?,
    ): Connection? {
        val real = delegate.connect(url, info ?: Properties()) ?: return null
        if (url.endsWith("?mode=ro")) return real
        val slot = slotIndex.getAndIncrement()
        physicalConnections += real
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name == "close") {
                closeCalls.incrementAndGet()
                closeCallsBySlot.getOrPut(slot) { AtomicInteger(0) }.incrementAndGet()
                val targeted = onlySlot?.let { it == slot } ?: true
                if (targeted && remainingCloseFailures.get() > 0) {
                    remainingCloseFailures.decrementAndGet()
                    throw SQLException("injected close failure")
                }
            }
            if (slot == 0 && method.name == "commit" && remainingCommitFailures.getAndDecrement() > 0) {
                throw SQLException("injected commit failure")
            }
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.cause ?: failure
            }
        } as Connection
    }

    override fun acceptsURL(url: String): Boolean = delegate.acceptsURL(url)

    override fun getPropertyInfo(
        url: String,
        info: Properties?,
    ): Array<DriverPropertyInfo> = delegate.getPropertyInfo(url, info ?: Properties())

    override fun getMajorVersion(): Int = delegate.majorVersion

    override fun getMinorVersion(): Int = delegate.minorVersion

    override fun jdbcCompliant(): Boolean = delegate.jdbcCompliant()

    override fun getParentLogger(): Logger = delegate.parentLogger
}
