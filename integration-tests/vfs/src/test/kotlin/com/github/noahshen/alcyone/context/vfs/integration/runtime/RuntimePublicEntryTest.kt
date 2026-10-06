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
import kotlinx.coroutines.delay
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
import kotlin.test.assertFailsWith

/**
 * T18 A01：宿主**只提交配置**，通过 `AlcyoneVfs` 这一层完成
 * `write → read → stat → list → Metadata → delete`，并核对磁盘、状态库与事件三边一致。
 *
 * 全部是真的：真的本机目录、真的 SQLite 文件、真的 OpenDAL Adapter、真的进程内事件通知。
 */
class RuntimePublicEntryTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var disk: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        disk = Files.createDirectory(tempDir.resolve("docs"))
    }

    private fun config(mounts: List<MountConfig> = listOf(MountConfig(VfsPath.parse("/resources/docs"), "docs", disk))) =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts = mounts,
        )

    private fun uri(path: String) = VfsUri.parse("alcyone://resources/docs$path")

    @Test
    @Timeout(60)
    fun `a host that only submits configuration gets the whole file lifecycle`() =
        runBlocking {
            prepare()
            val received = CopyOnWriteArrayList<VfsEvent>()

            withVfs(config()) { vfs ->
                vfs.subscribe { event -> received.add(event) }

                val written = vfs.write(uri("/a.txt"), "hello".toByteArray())
                assertEquals("hello", Files.readString(disk.resolve("a.txt")), "内容真的写到本机磁盘")
                assertEquals(listOf(written.id), activeNodeIds(), "状态库里是同一个 Node ID")
                assertEquals(written.id, eventNodeIds().single(), "事件带着同一个 Node ID")

                assertEquals("hello", vfs.read(uri("/a.txt")).toString(Charsets.UTF_8), "读得到刚写的内容")

                val stat = vfs.stat(uri("/a.txt"))
                assertEquals(written.id, stat.id, "同一个文件始终同一个身份")
                assertEquals(written.id, vfs.getNode(written.id).id)

                val listed = vfs.list(VfsUri.parse("alcyone://resources/docs"))
                assertEquals(listOf("/resources/docs/a.txt"), listed.map { it.uri.path.toString() })
                assertEquals(listOf(written.id), listed.map { it.nodeId })

                vfs.setMetadata(written.id, NodeMetadata(setOf("ct"), "胸部 CT"))
                assertEquals(setOf("ct"), vfs.getMetadata(written.id).tags)

                vfs.delete(uri("/a.txt"))
                assertTrue(Files.notExists(disk.resolve("a.txt")), "文件真的从磁盘消失")
                val gone = assertFailsWith<VfsException> { vfs.getNode(written.id) }
                assertEquals(VfsErrorCode.NOT_FOUND, gone.code, "删掉的 Node 同一个旧 ID 查不到")
                val goneMetadata = assertFailsWith<VfsException> { vfs.getMetadata(written.id) }
                assertEquals(VfsErrorCode.NOT_FOUND, goneMetadata.code, "删掉的 Node 不会退化成空 Metadata")
                assertEquals(emptyList<NodeId>(), activeNodeIds(), "没有别的有效 Node")

                // 通知是异步的：再写一个标记文件并等它送达，FIFO 保证前面的事件都已经送到。
                vfs.write(uri("/tail.txt"), "tail".toByteArray())
                withTimeout(30_000) { while (received.size < 4) delay(10) }
                assertEquals(
                    listOf(VfsEventType.FILE_CREATED, VfsEventType.METADATA_UPDATED, VfsEventType.FILE_DELETED, VfsEventType.FILE_CREATED),
                    received.map { it.type },
                    "公开订阅入口按顺序收到四类事件",
                )
            }

            assertEquals(
                listOf("FILE_CREATED", "METADATA_UPDATED", "FILE_DELETED", "FILE_CREATED"),
                eventTypes(),
                "事件按写入顺序留在事件日志里",
            )
        }

    /** 非法配置不开放服务：连状态库都不会被打开（文件根本不该出现）。 */
    @Test
    @Timeout(60)
    fun `an invalid configuration is refused before any service is opened`() {
        prepare()
        val missing = tempDir.resolve("not-there")

        val failure =
            runBlocking {
                assertFailsWith<VfsException> {
                    AlcyoneVfs.create(
                        config(listOf(MountConfig(VfsPath.parse("/resources/docs"), "docs", missing))),
                    )
                }
            }

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
        assertTrue(Files.notExists(database), "非法配置不打开状态库，也就不会写下任何挂载映射")
        assertTrue(Files.notExists(database.resolveSibling("state.db.lock")), "非法配置不占独占锁")
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

    /** 直查状态库，只读统计与事件 / 挂载行；绕开 VFS 打开逻辑看文件本身。 */
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

    private fun activeNodeIds(): List<NodeId> =
        query("SELECT id FROM node WHERE deleted_at IS NULL ORDER BY vfs_path").map {
            NodeId.parse(it[0]!!)
        }

    private fun eventTypes(): List<String> = query("SELECT event_type FROM event ORDER BY rowid").map { it[0]!! }

    private fun eventNodeIds(): List<NodeId?> = query("SELECT node_id FROM event ORDER BY rowid").map { it[0]?.let(NodeId::parse) }
}
