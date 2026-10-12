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
import org.junit.jupiter.api.Assertions.assertNull
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
 * - **A04 Consumer 隔离 + 公开查询**：公开入口上两个 Consumer，一个抛普通异常，另一个仍收到**同一个事件**，
 *   且其后继操作与事件照常；两个 Consumer 收到的事件按**事件 ID** 与真实 `event` 表逐字段对照；
 *   Consumer **在回调返回之前**用公开 `getNode` 查一次刚提交的状态，把查询结果交给测试协程直接断言。
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
     * 等待按「目标 Node ID + 类型」进行（同一条 FIFO 链，本装置每个组合唯一），不 sleep；
     * 抛错 Consumer 与正常 Consumer 都订阅在操作之前。
     *
     * 本用例另外补齐 A01 / A03 的公开接线：
     * - 把两个 Consumer 收到的事件按**事件 ID** 与真实 `event` 表逐条对照（类型 / Node ID / URI / 毫秒时间，
     *   非移动事件的 source / target 为空）；直接比较两个 Consumer 的事件身份，证明它们收到的是同一批。
     * - Consumer **在回调返回之前**用公开 `getNode` 查询已提交状态，把结果 / 异常交给测试协程断言：
     *   公开回调不是挂起函数，用**回调内部有界的 `runBlocking { withTimeout(...) }`** 桥接，
     *   所以查询在回调里同步跑完、回调返回时查询已经结束。若实现自锁或看到未提交状态，测试会直接失败，
     *   而不是被通知器当普通异常吞掉；等待上限在查询内部，反例自锁也不会把 `notifier.close` 永久卡住。
     */
    @Test
    @Timeout(60)
    fun `one failing consumer does not stop the other one nor later committed events`() =
        runBlocking {
            prepare()
            val healthy = IdRecorder()
            val throwing = CopyOnWriteArrayList<VfsEvent>()

            // 回调里查已提交状态的结果 / 异常，通过这个通道交给测试协程直接断言。
            // 不用 AssertionError 直接抛：那会被分发器当「Consumer 抛错」处理（Error 不吞，但会把分发协程打挂），
            // 断言就到不了测试；把结果攒起来由测试断言，才真能传出来。
            val committedQuery = CompletableDeferred<Result<NodeId?>>()
            // 查询等待上限放在查询内部（下面的 runBlocking + withTimeout），不只放在主测试 await 上：
            // 反例实现若自锁，回调会在这里超时返回，不会把 notifier.close 永久卡住。
            val queryTimeoutMs = 10_000L

            withVfs(config()) { vfs ->
                // 先订阅再触发：抛错的 Consumer 排在前面，健康 Consumer 排在后面。
                vfs.subscribe { event ->
                    throwing.add(event)
                    throw IllegalStateException("injected push failure for ${event.id}")
                }
                vfs.subscribe { event -> healthy.record(event) }
                // 第三个 Consumer 在回调返回之前查一次已提交状态：公开回调不是挂起函数，
                // 用回调内部有界的 runBlocking + withTimeout 桥接，查询在回调里同步跑完。
                // 只查不写，查完前不做任何会改状态的操作。
                vfs.subscribe { event ->
                    if (event.type == VfsEventType.FILE_CREATED && !committedQuery.isCompleted) {
                        val outcome =
                            runCatching {
                                runBlocking { withTimeout(queryTimeoutMs) { vfs.getNode(event.nodeId!!).id } }
                            }
                        committedQuery.complete(outcome)
                    }
                }

                val written = vfs.write(uri("/a.txt"), "hello".toByteArray())
                val created = healthy.await(written.id, VfsEventType.FILE_CREATED)
                assertEquals(listOf(written.id), created.map { it.nodeId }, "健康 Consumer 收到了同一个 FILE_CREATED")

                // 回调里查到的已提交身份：查询已在回调内完成，测试才继续；期间没有做任何会改状态的操作。
                val queried = withTimeout(30_000) { committedQuery.await() }
                assertEquals(written.id, queried.getOrElse { throw it }, "回调内公开 getNode 查到已提交的同一 Node")

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

            // —— 通知与持久事件按事件 ID 对照 ——
            // 健康 Consumer 已确认处理完成（await 返回时事件已进列表，此时分发链前面的都已跑完），
            // 拿它收到的事件逐个去真实 event 表按 event_id 找同名行，逐字段核对。
            val rows = eventRowsById()
            val healthyEvents = healthy.received()
            assertEquals(3, healthyEvents.size, "健康 Consumer 收到三条事件：${healthyEvents.map { it.type }}")
            healthyEvents.forEach { event ->
                val row = rows[event.id.value] ?: error("事件 ${event.id} 在真实 event 表里找不到对应行")
                assertEquals(event.type.name, row.eventType, "事件 ${event.id} 的类型与持久记录一致")
                assertEquals(event.nodeId?.value, row.nodeId, "事件 ${event.id} 的 Node ID 与持久记录一致")
                assertEquals(event.uri.toString(), row.uri, "事件 ${event.id} 的 URI 与持久记录一致")
                assertEquals(event.occurredAt.toEpochMilli(), row.occurredAt, "事件 ${event.id} 的毫秒时间与持久记录一致")
                // 这三条都不是移动事件：source / target 都为空。
                assertNull(row.sourceUri, "非移动事件 ${event.id} 的持久 source_uri 为空")
                assertNull(row.targetUri, "非移动事件 ${event.id} 的持久 target_uri 为空")
                assertNull(event.sourceUri, "非移动事件 ${event.id} 的通知 sourceUri 为空")
                assertNull(event.targetUri, "非移动事件 ${event.id} 的通知 targetUri 为空")
            }

            // 两个 Consumer 收到的是同一批事件：逐个比较事件身份（ID / 类型 / Node / URI / 时间）。
            val throwingEvents = throwing.toList()
            assertEquals(healthyEvents.size, throwingEvents.size, "抛错 Consumer 与健康 Consumer 收到的事件条数相同")
            assertEquals(
                healthyEvents.map { it.id },
                throwingEvents.map { it.id },
                "两个 Consumer 收到的事件 ID 顺序完全相同，证明拿到的是同一批",
            )
            throwingEvents.zip(healthyEvents).forEach { (thrown, good) ->
                assertEquals(good.type, thrown.type, "同一事件 ${good.id} 的类型一致")
                assertEquals(good.nodeId, thrown.nodeId, "同一事件 ${good.id} 的 Node ID 一致")
                assertEquals(good.uri, thrown.uri, "同一事件 ${good.id} 的 URI 一致")
                assertEquals(good.occurredAt, thrown.occurredAt, "同一事件 ${good.id} 的毫秒时间一致")
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
     * 重开后的等待按「目标 Node ID + 类型」（本装置每个组合唯一）。
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

    /** create → 用 → close 三步；`close()` 是挂起函数，所以用 `try/finally`。
     *
     * 当前未覆盖「关闭同时失败」：没有注入关闭失败，所以本用例里 close 不会把主异常盖住——
     * 这只说明当前没覆盖该组合，不等于实现上保证 close 永不会失败。将来若要叠加
     * 「清理本身也失败」的场景，这里应改成保留主异常、把 close 失败挂到 suppressed 上，
     * 而不是用 close 的异常顶掉主异常。
     */
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

    /** 一条持久事件行，按事件 ID 索引，供通知与真库逐字段对照。 */
    private data class EventRow(
        val eventType: String,
        val nodeId: String?,
        val occurredAt: Long,
        val uri: String,
        val sourceUri: String?,
        val targetUri: String?,
    )

    /** 真实 `event` 表按 `event_id` 索引的行；通知与持久事件按事件 ID 对照时用。 */
    private fun eventRowsById(): Map<String, EventRow> =
        query("SELECT event_id, event_type, node_id, occurred_at, uri, source_uri, target_uri FROM event").associate {
            it[0]!! to
                EventRow(
                    eventType = it[1]!!,
                    nodeId = it[2],
                    occurredAt = it[3]!!.toLong(),
                    uri = it[4]!!,
                    sourceUri = it[5],
                    targetUri = it[6],
                )
        }

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
     * 按「目标 Node ID + 事件类型」聚合的事件记录器。
     *
     * **只适用于本例**：每组合（Node ID + 类型）在本装置里唯一，所以按它聚合不会错配。
     * 它不是通用的重复事件等待器：同组合会发多次的场合要改按事件 ID 等，否则可能拿错那一次。
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
