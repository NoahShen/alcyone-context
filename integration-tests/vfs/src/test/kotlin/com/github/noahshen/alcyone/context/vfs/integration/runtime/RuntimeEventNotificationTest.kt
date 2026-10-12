package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * T26 补公开 Runtime 的两处接线缺口（其余在真实 SQLite 机制层与组件层已覆盖）。
 *
 * - **A04 Consumer 隔离**：公开入口上两个 Consumer，一个抛普通异常，另一个仍收到**同一个事件**，
 *   且其后继操作与事件照常；已提交状态与事件日志不受抛错 Consumer 影响。
 * - **A06 正常重开不自动重放**：保存旧事件字段快照，正常关库重开同一库，核对旧事件原样保留；
 *   新实例的新订阅**不自动收到旧日志**，并用重开后**新生成的标记事件**确认通知通路确实工作——
 *   避免把「监听器根本没接上」误当作不重放的证明。
 *
 * 全部是真的：真本机目录、真 SQLite、真 Runtime 组装、真进程内通知。不靠 sleep 断言「没收到」。
 */
class RuntimeEventNotificationTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var disk: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        disk = Files.createDirectory(tempDir.resolve("docs"))
    }

    private fun config() =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts = listOf(MountConfig(VfsPath.parse("/resources/docs"), "docs", disk)),
        )

    private fun uri(path: String) = VfsUri.parse("alcyone://resources/docs$path")

    /**
     * A04：一个 Consumer 抛普通异常，另一个仍收到**同一个事件**；后续操作与事件照常；
     * 抛错 Consumer 既不撤销已提交状态，也不改事件日志。
     *
     * 等待按事件 ID 进行（同一条 FIFO 链），不 sleep；抛错 Consumer 与正常 Consumer 都订阅在操作之前。
     */
    @Test
    @Timeout(60)
    fun `one failing consumer does not stop the other one nor later committed events`() =
        runBlocking {
            prepare()
            val healthy = IdRecorder()
            val throwing = CopyOnWriteArrayList<VfsEvent>()

            withVfs(config()) { vfs ->
                // 先订阅再触发：抛错的 Consumer 排在前面，健康 Consumer 排在后面。
                vfs.subscribe { event ->
                    throwing.add(event)
                    throw IllegalStateException("injected push failure for ${event.id}")
                }
                vfs.subscribe { event -> healthy.record(event) }

                val written = vfs.write(uri("/a.txt"), "hello".toByteArray())
                val created = healthy.await(written.id, VfsEventType.FILE_CREATED)
                assertEquals(listOf(written.id), created.map { it.nodeId }, "健康 Consumer 收到了同一个 FILE_CREATED")
                // 后续操作与事件仍正常：替换 Metadata 也会通知，抛错 Consumer 挡不住它。
                vfs.setMetadata(written.id, NodeMetadata(setOf("ct"), "胸部 CT"))
                healthy.await(written.id, VfsEventType.METADATA_UPDATED)

                // 后续再写一个文件：事件照常送达，抛错 Consumer 也没把分发链带走。
                val second = vfs.write(uri("/b.txt"), "again".toByteArray())
                val secondCreated = healthy.await(second.id, VfsEventType.FILE_CREATED)
                assertEquals(second.id, secondCreated.single().nodeId, "后续操作的事件照常到达")

                // 抛错 Consumer 确实看到了事件（说明它真的被调过），只是它挡不住别人。
                assertTrue(throwing.isNotEmpty(), "抛错 Consumer 应该被调用过")

                // 已提交状态照旧：文件还在，逻辑记录还在。
                assertEquals("hello", vfs.read(uri("/a.txt")).toString(Charsets.UTF_8))
                assertEquals("again", vfs.read(uri("/b.txt")).toString(Charsets.UTF_8))
                assertEquals(setOf("ct"), vfs.getMetadata(written.id).tags)
            }

            // 持久事件与业务操作一一对应，抛错 Consumer 不改事件日志。
            assertEquals(listOf("FILE_CREATED", "METADATA_UPDATED", "FILE_CREATED"), eventTypes())
            assertEquals(
                listOf("alcyone://resources/docs/a.txt", "alcyone://resources/docs/a.txt", "alcyone://resources/docs/b.txt"),
                query("SELECT uri FROM event ORDER BY rowid").map { it[0] },
            )
        }

    /**
     * A06：正常关闭重开同一库，旧事件字段原样保留；新实例的新订阅**不自动重放**旧日志——
     * 用重开后新生成的标记事件确认通知通路确实工作，而不是「等一会儿没动静」。
     *
     * 只验证当前串行进程内分发与**正常重开**：不证明未来任意异步重放器不会补发，也不验证崩溃恢复。
     */
    @Test
    @Timeout(60)
    fun `a normal reopen keeps the log but a new subscription is not replayed the old events`() =
        runBlocking {
            prepare()

            // 第一段：提交一批事件，然后正常关闭（关库放锁）。
            val oldIds =
                withVfs(config()) { vfs ->
                    listOf(vfs.write(uri("/old1.txt"), "one".toByteArray()).id, vfs.write(uri("/old2.txt"), "two".toByteArray()).id)
                }
            assertEquals(listOf("FILE_CREATED", "FILE_CREATED"), eventTypes(), "关闭前两条旧事件都在日志里")
            val beforeReopen = eventSnapshots()
            assertEquals(2, beforeReopen.size)

            // 第二段：重开同一库，新订阅先接好，再生成一条**重开后的新事件**。
            withVfs(config()) { vfs ->
                val recorder = IdRecorder()
                vfs.subscribe { event -> recorder.record(event) }

                val fresh = vfs.write(uri("/new.txt"), "fresh".toByteArray())
                val delivered = recorder.await(fresh.id, VfsEventType.FILE_CREATED)

                // 收到的新事件就是这一条。
                assertEquals(
                    listOf(fresh.id),
                    delivered.map { it.nodeId },
                    "新订阅收到了重开后的新事件：${delivered.map { it.nodeId }}",
                )
                assertEquals(
                    listOf(VfsEventType.FILE_CREATED),
                    delivered.map { it.type },
                    "重开后收到的是新写入事件",
                )
                assertEquals("alcyone://resources/docs/new.txt", delivered.single().uri.toString())

                // 关键否定断言：新订阅**收到过的所有事件**里都没有旧日志的 Node。
                // 这条新事件已经送达，说明监听器确实接上了——不是「根本没接上」冒充不重放。
                val everything = recorder.received()
                assertTrue(
                    everything.none { it.nodeId in oldIds },
                    "旧日志里的两条事件没有出现在新订阅的收件箱里：${everything.map { it.nodeId }}",
                )
                assertEquals(1, everything.size, "重开后新订阅只收到了新事件这一条：${everything.map { it.uri }}")
            }

            // 旧事件字段原样保留，新事件追加在后面。
            assertEquals(beforeReopen, eventSnapshots().take(2), "重开后旧事件的 ID / 类型 / Node / URI 原样不变")
            assertEquals(listOf("FILE_CREATED", "FILE_CREATED", "FILE_CREATED"), eventTypes(), "新事件追加在旧记录之后")
        }

    /** create → 用 → close 三步；`close()` 是挂起函数，所以用 `try/finally`。 */
    private suspend fun <T> withVfs(
        config: VfsRuntimeConfig,
        block: suspend (AlcyoneVfs) -> T,
    ): T {
        val vfs = AlcyoneVfs.create(config)
        return try {
            block(vfs)
        } finally {
            vfs.close()
        }
    }

    /** 只读查库，确认持久化事实；不替 SDK 写数据。 */
    private fun query(sql: String): List<Array<String?>> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList {
                        val columns = rows.metaData.columnCount
                        while (rows.next()) add(Array(columns) { rows.getString(it + 1) })
                    }
                }
            }
        }

    private fun eventTypes(): List<String> = query("SELECT event_type FROM event ORDER BY rowid").map { it[0]!! }

    /** 一条事件取 `event_id / event_type / node_id / uri` 四项合成一个可比较的字符串。 */
    private data class EventSnapshot(
        val eventId: String,
        val eventType: String,
        val nodeId: String?,
        val uri: String,
    )

    private fun eventSnapshots(): List<EventSnapshot> =
        query("SELECT event_id, event_type, node_id, uri FROM event ORDER BY rowid").map {
            EventSnapshot(eventId = it[0]!!, eventType = it[1]!!, nodeId = it[2], uri = it[3]!!)
        }

    /**
     * 按「目标 Node ID + 事件类型」等待的事件记录器。
     *
     * 每个键各等各的信号，不复用一次性标记：同一条 FIFO 链上先等的那次会把信号消费掉，
     * 第二次 await 会立刻返回，断言就变成和后台消费赛跑。等到就是真等到了。
     */
    private class IdRecorder {
        private val seen = CopyOnWriteArrayList<VfsEvent>()
        private val arrived = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun record(event: VfsEvent) {
            seen.add(event)
            arrived.computeIfAbsent(key(event.nodeId?.value, event.type)) { CompletableDeferred() }.complete(Unit)
        }

        /** 收到过的全部事件（不按等待的 key 过滤），用于「没有收到旧日志」这类否定断言。 */
        fun received(): List<VfsEvent> = seen.toList()

        /** 等到该键的事件真的进了列表，返回**该键匹配的全部事件**。 */
        suspend fun await(
            nodeId: NodeId,
            type: VfsEventType,
        ): List<VfsEvent> {
            val key = key(nodeId.value, type)
            withTimeout(30_000) { arrived.computeIfAbsent(key) { CompletableDeferred() }.await() }
            return seen.filter { it.nodeId == nodeId && it.type == type }
        }

        private fun key(
            nodeId: String?,
            type: VfsEventType,
        ) = "$nodeId:$type"
    }
}
