package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertFailsWith

/**
 * T19 A03 / A04：公开入口上的**连续多段正常重开**。一次 `create → 用 → close` 只算一格，
 * 这里在**同一个状态库**上连着开三次，证明：
 *
 * - 内容、有效 Node、Metadata、Node ID 在正常重开后原样保留（A03）；
 * - 删除并重开后旧 ID 与旧 Metadata 都 `NOT_FOUND`，同路径重建得到**新 ID**（A03）；
 * - 变更事件留在日志里，重开后旧事件的 ID / 类型 / 目标 Node / URI 四项都不变，新事件追加在后面（A04）。
 *
 * 全程真 SQLite + 真本机目录，状态库放在挂载物理根**之外**；只读 SQL 只用来确认持久化事实，
 * 不替被测操作写数据，也不删库 / 清 Mount 行来让测试通过。
 */
class RuntimeReopenIdentityTest {
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
     * A03：写 + Metadata → close → 校验 → 删除 → close → 校验 → 同路径重建。
     *
     * 判据全部落在公开结果上：读到的字节、同一个 Node ID、Metadata 标签、删除后旧 ID 的 `NOT_FOUND`、
     * 重建后 ID 变了。磁盘内容与状态库行只做旁证。
     */
    @Test
    @Timeout(60)
    fun `a file keeps its identity and metadata across two normal reopens and a rebuild gets a new id`() =
        runBlocking {
            prepare()
            val tag = NodeMetadata(setOf("ct", "影像"), "胸部 CT 报告")

            // 第一段：写入与 Metadata，然后正常关闭。
            val first =
                withVfs(config()) { vfs ->
                    val info = vfs.write(uri("/note.txt"), "first".toByteArray())
                    vfs.setMetadata(info.id, tag)
                    info.id
                }

            // 第二段：重开校验内容 / 身份 / Metadata 都还在，然后删除再关。
            withVfs(config()) { vfs ->
                assertEquals("first", vfs.read(uri("/note.txt")).toString(Charsets.UTF_8), "重开后内容还在")
                assertEquals(first, vfs.getNode(first).id, "重开保留同一个 Node ID")
                assertEquals(first, vfs.stat(uri("/note.txt")).id, "stat 也拿到同一个身份")
                assertEquals(tag, vfs.getMetadata(first), "重开保留 Metadata")
                assertEquals(listOf("/resources/docs/note.txt"), activePaths(), "状态库里是同一个有效记录")

                vfs.delete(uri("/note.txt"))
                assertFalse(Files.exists(disk.resolve("note.txt")), "删除真的从磁盘消失")
            }

            // 删除后的旧 ID / Metadata 一律查不到（不靠删库规避）。
            val rebuiltId =
                withVfs(config()) { vfs ->
                    val gone = assertFailsWith<VfsException> { vfs.getNode(first) }
                    assertEquals(VfsErrorCode.NOT_FOUND, gone.code, "删除重开后旧 ID 无效")
                    val goneMetadata = assertFailsWith<VfsException> { vfs.getMetadata(first) }
                    assertEquals(VfsErrorCode.NOT_FOUND, goneMetadata.code, "旧 Metadata 也跟着失效")
                    assertTrue(activePaths().isEmpty(), "没有别的有效记录")

                    // 同路径重建：得到新的身份，内容和 Metadata 都从零开始。
                    val rebuilt = vfs.write(uri("/note.txt"), "second".toByteArray())
                    assertNotEquals(first, rebuilt.id, "同路径重建得到新 Node ID")
                    assertEquals("second", vfs.read(uri("/note.txt")).toString(Charsets.UTF_8))
                    assertEquals(NodeMetadata(), vfs.getMetadata(rebuilt.id), "新记录没有旧 Metadata")
                    rebuilt.id
                }

            // 再关再开一次：重建出的新身份同样跨重开稳定（与 rebuiltId 逐位相等，也与最初不同）。
            withVfs(config()) { vfs ->
                val stat = vfs.stat(uri("/note.txt"))
                assertEquals("second", vfs.read(uri("/note.txt")).toString(Charsets.UTF_8))
                assertEquals(rebuiltId, stat.id, "重建出的新 ID 跨重开保持稳定")
                assertNotEquals(first, stat.id, "重建出的新 ID 与第一次不同")
            }
        }

    /**
     * A04：事件随正常重开保留，且**重开前后已有事件的 ID / 类型 / 目标 Node / URI 不变**；
     * 实时订阅先于操作，按目标 Node ID 与类型等待。
     *
     * 同一库写文件（FILE_CREATED）→ 替换 Metadata（METADATA_UPDATED）→ 删除（FILE_DELETED）。
     * 重启后再追加一次写入，事件日志按 rowid 追加，旧三条的 event_id / event_type / node_id / uri 四项逐字未变。
     */
    @Test
    @Timeout(60)
    fun `committed events survive a reopen and their ids and targets do not change`() =
        runBlocking {
            prepare()
            val recorder = TargetingRecorder()

            val written =
                withVfs(config()) { vfs ->
                    vfs.subscribe { event -> recorder.record(event) }
                    val info = vfs.write(uri("/a.txt"), "hello".toByteArray())
                    // 订阅先于操作：按目标 Node ID 等到 FILE_CREATED 才算收到。
                    recorder.await(info.id, VfsEventType.FILE_CREATED)
                    vfs.setMetadata(info.id, NodeMetadata(setOf("ct"), "胸部 CT"))
                    recorder.await(info.id, VfsEventType.METADATA_UPDATED)
                    vfs.delete(uri("/a.txt"))
                    recorder.await(info.id, VfsEventType.FILE_DELETED)
                    info.id
                }

            assertEquals(
                listOf("FILE_CREATED", "METADATA_UPDATED", "FILE_DELETED"),
                eventTypes(),
                "三条变更都留在事件日志里",
            )
            // 重开前保存旧事件的小快照：event_id / event_type / node_id / uri 四项一起比，
            // 只读 SQL 确认持久化事实，不替 SDK 写数据。
            val beforeReopen = eventSnapshots()
            assertEquals(3, beforeReopen.size, "重开前库里有三条事件")
            assertTrue(
                beforeReopen.all { it.nodeId == written.value },
                "三条事件的 node_id 都指向被写入的那个 ID：$beforeReopen",
            )

            // 重开后：旧事件四项逐字未变，新事件追加在后面。
            withVfs(config()) { vfs ->
                val again = vfs.write(uri("/b.txt"), "again".toByteArray())
                assertEquals(listOf("FILE_CREATED"), listOf(eventTypes().last()), "重开后的写入追加一条新事件")
                assertEquals(beforeReopen, eventSnapshots().take(3), "重开后旧事件的 ID / 类型 / 目标 Node 与 URI 原样不变")
                assertEquals(
                    listOf("FILE_CREATED", "METADATA_UPDATED", "FILE_DELETED", "FILE_CREATED"),
                    eventTypes(),
                    "旧事件类型原样保留，新事件追在后面",
                )
                val appended = eventSnapshots().last()
                assertEquals(again.id.value, appended.nodeId, "新事件指向新写文件的 Node ID")
                assertEquals("alcyone://resources/docs/b.txt", appended.uri, "新事件指向新写文件的逻辑 URI")
            }

            assertEquals(beforeReopen, eventSnapshots().take(3), "两次重开之间旧事件四项仍逐字稳定")
        }

    /** create → 用 → close 的三步。`close()` 是挂起函数，所以用 `try/finally` 而不是 `use`。 */
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

    private fun activePaths(): List<String> = query("SELECT vfs_path FROM node WHERE deleted_at IS NULL ORDER BY vfs_path").map { it[0]!! }

    private fun eventTypes(): List<String> = query("SELECT event_type FROM event ORDER BY rowid").map { it[0]!! }

    /**
     * 事件的小快照：一条事件取 `event_id / event_type / node_id / uri` 四项合成一个可比较的字符串。
     * 重开前后对同一批记录整条比较，就能覆盖「ID / 类型 / 目标 Node / URI 不变」的完整口径。
     */
    private data class EventSnapshot(
        val eventId: String,
        val eventType: String,
        val nodeId: String?,
        val uri: String,
    ) {
        override fun toString(): String = "EventSnapshot(eventId=$eventId, eventType=$eventType, nodeId=$nodeId, uri=$uri)"
    }

    private fun eventSnapshots(): List<EventSnapshot> =
        query("SELECT event_id, event_type, node_id, uri FROM event ORDER BY rowid").map {
            EventSnapshot(eventId = it[0]!!, eventType = it[1]!!, nodeId = it[2], uri = it[3]!!)
        }

    /**
     * 按「目标 Node ID + 事件类型」等待的事件记录器。
     *
     * 每次 await 各等各的信号，不复用一次性标记：同一条 FIFO 链上先等的那次会把信号消费掉，
     * 第二次 await 会立刻返回，断言就变成和后台消费赛跑。等到就是真等到了。
     */
    private class TargetingRecorder {
        private val seen = java.util.concurrent.CopyOnWriteArrayList<VfsEvent>()
        private val arrived = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun record(event: VfsEvent) {
            seen.add(event)
            arrived.computeIfAbsent(key(event.nodeId, event.type)) { CompletableDeferred() }.complete(Unit)
        }

        suspend fun await(
            nodeId: NodeId,
            type: VfsEventType,
        ) {
            withTimeout(30_000) { arrived.computeIfAbsent(key(nodeId, type)) { CompletableDeferred() }.await() }
        }

        private fun key(
            nodeId: NodeId?,
            type: VfsEventType,
        ) = "${nodeId?.value}:$type"
    }
}
