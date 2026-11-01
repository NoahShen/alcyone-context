package com.github.noahshen.alcyone.context.memory.runtime.ingestion

import com.github.noahshen.alcyone.context.memory.api.IngestResult
import com.github.noahshen.alcyone.context.memory.api.IngestStatus
import com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionNotificationReporter
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextImpl
import com.github.noahshen.alcyone.context.memory.runtime.ContextFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT07-B1 R3：close 与「已接纳 ingest 的通知准入」竞态。
 *
 * 通过 gate 在 COMMIT 成功后、通知准入前挂起 ingest 线程；close 先获胜并关闭通知准入，再放行 gate。
 * 断言：通知 scope 不被重建、无残留 Job、reporter 收到固定脱敏取消、Turn 仍已提交。
 */
class IngestionNotificationRaceTest {
    @Test
    fun `close winning before notification admission reports cancellation and keeps the committed turn`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val reported = CopyOnWriteArrayList<Throwable>()
            val context =
                AlcyoneContextFactory.create(
                    fixture.config(),
                    fixture.vfsConfig(),
                    object : AlcyoneContextFactory.StartProbe {
                        override suspend fun afterResourcesAcquired() = Unit
                    },
                    ingestionReporter = IngestionNotificationReporter { cause -> reported += cause },
                ) as AlcyoneContextImpl
            runBlocking { context.start() }

            val gateEntered = CountDownLatch(1)
            val gateRelease = CountDownLatch(1)
            context.notificationLaunchGateForTesting = {
                gateEntered.countDown()
                assertTrue(gateRelease.await(10, TimeUnit.SECONDS), "通知准入闸门必须有界放行")
            }

            val result = AtomicReference<IngestResult?>(null)
            val ingestFailure = AtomicReference<Throwable?>(null)
            val ingestThread =
                Thread {
                    try {
                        result.set(runBlocking { context.ingestConversation(IngestionTurns.turn()) })
                    } catch (failure: Throwable) {
                        ingestFailure.set(failure)
                    }
                }
            ingestThread.start()
            assertTrue(gateEntered.await(10, TimeUnit.SECONDS), "COMMIT 必须已在 gate 之前成功")

            context.close()
            assertFalse(context.notificationScopeCreatedForTesting(), "close 后不得重建通知 scope")
            assertEquals(0, context.notificationJobCountForTesting(), "close 后不得残留通知 Job")

            gateRelease.countDown()
            ingestThread.join(10_000)
            assertFalse(ingestThread.isAlive, "ingest 线程必须有界退出")

            assertNull(ingestFailure.get(), "Turn 已提交，ingest 不得失败: ${ingestFailure.get()}")
            assertEquals(IngestStatus.CREATED, result.get()?.status, "已提交的 Turn 仍应返回 CREATED")
            assertTrue(reported.any { it is CancellationException }, "未准入的通知必须上报固定取消")
            val reportedText = reported.joinToString("|") { it.message.orEmpty() }
            assertFalse(reportedText.contains("runtime-1"), "上报内容不得含 SessionKey 值")
            assertFalse(reportedText.contains("hello"), "上报内容不得含正文")

            assertEquals(1L, countRows(fixture.contextPath), "Turn 必须已提交（独立只读连接可见）")
        }

    private fun countRows(path: Path): Long =
        DriverManager.getConnection("jdbc:sqlite:${path.toUri()}?mode=ro", Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM conversation_turn").use { rs ->
                    assertTrue(rs.next())
                    rs.getLong(1)
                }
            }
        }
}
