package com.github.noahshen.alcyone.context.vfs.integration.runtime

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsStreamResult
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.WriteMode
import com.github.noahshen.alcyone.context.vfs.WriteOptions
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertFailsWith

/**
 * T27 A01 / A02 / A03 / A07：公开 Runtime（[AlcyoneVfs]）、真 SQLite、真本机目录上的并发与生命周期代表。
 *
 * 只补已有证据链里缺的四条公开入口代表，不复制 T13 / T15 / T18 的机制矩阵：
 *
 * - A01 并发 stat：同一个未登记路径被多个调用同时 stat，最终只留一个有效身份，且懒注册不发变更事件；
 *   read / list 不变成登记操作。
 * - A02 同路径 CREATE_NEW 竞争：两个调用经**同一个启动门**同时发起，收集两次返回；一个成功一个
 *   `ALREADY_EXISTS`，磁盘内容、有效记录与成功事件都对应获胜调用。启动门只保证「一起发起」，
 *   不冒充「同时进入临界区」——串行边界由 T13 / T15 的受控用例单独证明。
 * - A03 Metadata 与移动交错：受控次序下移动先完成，再按旧 Node ID 更新 Metadata，看它落在新路径、
 *   事件 URI 也应是新路径。第二调用自己的返回值 / 异常直接保存后断言，不是事后再查。
 * - A07 反复创建 / 关闭：有限轮次（这里 3 轮）真实资源，交替「显式关流」与「交给 Runtime 回收」，
 *   每轮确认旧流不可读、同库能再打开。结论限定为所测轮次与可观测资源，不作堆内存 / 无泄漏推断。
 *
 * 时序用 `CompletableDeferred` 启停门控制，不用 `Thread.sleep`；每个用例都有 [Timeout] 兜底。
 * 并发子任务在 `withVfs` **内部**用 `coroutineScope` 托管：一旦等待抛错，作用域先取消并等待这批子任务，
 * 然后才让 `withVfs` 关 Runtime，不把未收尾的子任务挂到外层 `runBlocking` 上（R2）。
 */
class RuntimeConcurrencyLifecycleTest {
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
     * A01：同一个物理预置文件被 8 个调用同时 `stat`，全部返回同一个身份；状态库里只有一条有效记录，
     * 懒注册不追加任何变更事件。`read` / `list` 之后它仍然没有身份。
     */
    @Test
    @Timeout(60)
    fun `concurrent stats of one path hand out a single identity and register no event`() =
        runBlocking {
            prepare()
            // 绕过 VFS 直接预置：它一开始没有身份，内容却真的在。
            Files.writeString(disk.resolve("external.txt"), "outside")

            withVfs(config()) { vfs ->
                // 先写一个种子文件：直接断言它落了一条事件，说明持久事件写入可用（这里没有订阅，不是进程内通知通路的验证）。
                vfs.write(uri("/seed.txt"), "seed".toByteArray())
                assertEquals(1, eventCount(), "种子写的 FILE_CREATED 已持久进事件表")
                val eventsAfterSeed = eventCount()

                // 读取不是登记操作：read 之后仍未登记，list 也不登记，都不追加事件。
                assertEquals("outside", vfs.read(uri("/external.txt")).toString(Charsets.UTF_8), "没登记也读得到")
                assertNull(findByPath("/resources/docs/external.txt"), "read 不登记")
                vfs.list(VfsUri.parse("alcyone://resources/docs"))
                assertNull(findByPath("/resources/docs/external.txt"), "list 也不登记")
                assertEquals(eventsAfterSeed, eventCount(), "read / list 不产生变更事件")

                // 同一启动门一起发起：先把所有协程建好停在门上，再一次性放行。
                // 用 coroutineScope 托管这批子任务：只要有一个 awaitAll 抛错，作用域会先取消其余子任务并等它们收尾，
                // 然后才返回到 withVfs 去关 vfs.close()，不会把没跑完的子任务留在外层 runBlocking 上。
                val gate = CompletableDeferred<Unit>()
                val results =
                    coroutineScope {
                        (1..8)
                            .map {
                                async(Dispatchers.Default) {
                                    gate.await()
                                    vfs.stat(uri("/external.txt")).id
                                }
                            }.also { gate.complete(Unit) }
                            .awaitAll()
                    }

                assertEquals(1, results.distinct().size, "并发 stat 同一路径最终只有一个有效身份：$results")
                assertEquals(1, countActive("/resources/docs/external.txt"), "状态库里只有一条有效记录")
                assertEquals(0, countActiveDuplicates(), "同一路径没有留下第二条有效记录")
                assertEquals(results.first(), findByPath("/resources/docs/external.txt"), "有效记录就是返回的那个身份")
                assertEquals(eventsAfterSeed, eventCount(), "stat 懒注册不追加变更事件")
            }
        }

    /**
     * A02：两个调用经同一个启动门对同一路径 `CREATE_NEW`，一个成功一个 `ALREADY_EXISTS`；
     * 实际内容、有效记录与唯一成功事件都对应获胜调用。
     *
     * 门只保证「一起发起」，不保证「同时进临界区」——串行机制由 T13 / T15 的受控用例单独证明，
     * 这里核对的是「无论谁先，结果一定是合法集合里的一个」。
     */
    @Test
    @Timeout(60)
    fun `a same path CREATE_NEW race leaves one winner and the loser sees ALREADY_EXISTS`() =
        runBlocking {
            prepare()

            withVfs(config()) { vfs ->
                val gate = CompletableDeferred<Unit>()
                // 甲写 A、乙写 B：不预设谁赢，两边都收集「结果或异常」。
                // 用 coroutineScope 托管这两个子任务：awaitAll 抛错时先取消 / 等它们收尾，再让 withVfs 关 vfs.close()。
                val attempts =
                    coroutineScope {
                        listOf("A", "B")
                            .map { payload ->
                                async(Dispatchers.Default) {
                                    gate.await()
                                    runCatching {
                                        vfs.write(uri("/race.txt"), payload.toByteArray(), WriteOptions(WriteMode.CREATE_NEW))
                                    } to payload
                                }
                            }.also { gate.complete(Unit) }
                            .awaitAll()
                    }

                val succeeded = attempts.filter { it.first.isSuccess }
                val failed = attempts.filter { it.first.isFailure }

                assertEquals(1, succeeded.size, "只有一个调用能创建成功：$attempts")
                assertEquals(1, failed.size, "另一个必须被拒：$attempts")

                val winner = succeeded.single()
                val loserFailure = failed.single().first.exceptionOrNull()
                val winnerPayload = winner.second
                val winnerInfo = winner.first.getOrThrow()

                // 失败方的**实际结果**必须直接断言，不能只事后查库。
                assertTrue(loserFailure is VfsException, "败者拿到的是业务异常：$loserFailure")
                assertEquals(VfsErrorCode.ALREADY_EXISTS, (loserFailure as VfsException).code, "败者拿到 ALREADY_EXISTS")

                // 物理内容对应获胜调用；败者的字节一个都没落地。
                assertEquals(winnerPayload, Files.readString(disk.resolve("race.txt")), "磁盘内容对应获胜调用")
                assertEquals(winnerPayload, vfs.read(uri("/race.txt")).toString(Charsets.UTF_8), "读回也是获胜方的内容")
                assertEquals(winnerInfo.id, findByPath("/resources/docs/race.txt"), "有效记录对应获胜调用")
                assertEquals(1, countActive("/resources/docs/race.txt"), "同路径只有一条有效记录")

                // 成功事件只有一条，且 nodeId 是获胜调用的身份；失败那次没留下任何成功事件。
                assertEquals(listOf("FILE_CREATED"), eventTypes(), "只有获胜调用发一条 FILE_CREATED")
                assertEquals(winnerInfo.id.value, eventNodeIds().single(), "成功事件对应获胜调用")
            }
        }

    /**
     * A03：移动先完成，再按**旧 Node ID** 更新 Metadata。
     *
     * 这是**顺序接线代表**（不是受控交错）：移动整条链先跑完、正常返回（拿到目标路径），随后才发起
     * `setMetadata`。它核对的是「移动之后旧 ID 仍指向同一身份、更新落到新路径」，用它验证跨操作的顺序接线；
     * 真正的受控交错（两操作在一把边界上排队）在 `DefaultVfsRealStackTest` 的包装用例里。第二调用自己的返回值 /
     * 异常由我们直接保存后断言。
     */
    @Test
    @Timeout(60)
    fun `a metadata update by the old id after a move lands on the new path with the new uri`() =
        runBlocking {
            prepare()

            withVfs(config()) { vfs ->
                val created = vfs.write(uri("/report.txt"), "hello".toByteArray())
                val oldId = created.id

                // 移动先完成：返回的 NodeInfo 已是目标路径。
                val moved = vfs.move(uri("/report.txt"), uri("/archive/report.txt"))
                assertEquals(oldId, moved.id, "移动复用原身份")
                assertEquals(uri("/archive/report.txt"), moved.uri, "移动返回目标路径")

                // 再用**旧 ID** 更新 Metadata：第二调用的返回值 / 异常直接保存，事后再断言。
                val metadata = NodeMetadata(setOf("ct"), "胸部 CT")
                val secondResult = runCatching { vfs.setMetadata(oldId, metadata) }

                assertTrue(secondResult.isSuccess, "按旧 ID 更新应当成功，实际：${secondResult.exceptionOrNull()}")
                // 身份通过旧 ID 仍然有效，且通过公开查询拿到的是新路径。
                assertEquals(oldId, vfs.getNode(oldId).id, "旧 ID 仍是同一身份")
                assertEquals(
                    "/resources/docs/archive/report.txt",
                    vfs
                        .getNode(oldId)
                        .uri.path
                        .toString(),
                    "身份已经指向新路径",
                )
                assertEquals(metadata, vfs.getMetadata(oldId), "Metadata 落在同一个（已移动）身份上")

                // 事件：写一条 FILE_CREATED + 一条 FILE_MOVED + 一条 METADATA_UPDATED；
                // Metadata 事件的 URI 应是**新路径**。
                assertEquals(listOf("FILE_CREATED", "FILE_MOVED", "METADATA_UPDATED"), eventTypes())
                val metadataEvent = eventRows().last()
                assertEquals(oldId.value, metadataEvent.nodeId, "Metadata 事件的 nodeId 是原身份")
                assertEquals("alcyone://resources/docs/archive/report.txt", metadataEvent.uri, "Metadata 事件的 URI 是新路径")
                assertEquals(1, countActive("/resources/docs/archive/report.txt"), "新路径下有效记录就是这条身份")
                assertEquals(0, countActive("/resources/docs/report.txt"), "旧路径不再有有效记录")
            }
        }

    /**
     * A07：3 轮真实创建 / 关闭，交替「显式关流」与「交给 Runtime 回收」。
     *
     * 每轮都用**真资源**：新 Runtime + 真本机目录 + 真 SQLite；在轮内打开流、确认流可用后按模式处理；
     * `close()` 之后确认旧流不可读、同库能再打开、已提交身份保留。
     * 结论限定为这 3 轮与可观测资源（读失败 + 重开成功），不作堆内存 / 无泄漏推断。
     */
    @Test
    @Timeout(120)
    fun `three rounds of create and close alternate explicit and reclaimed streams and always reopen`() =
        runBlocking {
            prepare()
            val rounds = 3
            // 本轮可观测资源：每轮打开过的流，关闭 Runtime 后都必须不可读。
            val streamsOpened = mutableListOf<VfsStreamResult>()
            lateinit var committedId: NodeId

            repeat(rounds) { round ->
                val explicit = round % 2 == 0 // 交替：偶数轮调用方主动关，奇数轮留给 Runtime 回收
                val vfs = AlcyoneVfs.create(config())
                try {
                    // 每轮写一个属于本轮的真实文件，确认身份跨轮保留。
                    val info = vfs.write(uri("/keep.txt"), "round-$round".toByteArray())
                    if (round == 0) committedId = info.id
                    assertEquals(committedId, info.id, "同一路径跨轮复用同一身份（第 $round 轮）")

                    // 用真流式读：绕开 read 的小文件也走 openStream，证明流所有权在真资源上。
                    val stream = vfs.openStream(uri("/keep.txt"))
                    streamsOpened += stream
                    val bytes = stream.stream.readBytes()
                    assertEquals("round-$round", bytes.toString(Charsets.UTF_8), "流读到本轮内容（第 $round 轮）")

                    if (explicit) {
                        stream.close()
                        assertTrue(stream.isClosed, "显式关闭后流已释放（第 $round 轮）")
                    } else {
                        assertFalse(stream.isClosed, "本轮的流故意不关，留给 Runtime 回收（第 $round 轮）")
                    }
                } finally {
                    // close() 幂等：断言失败也要把 Runtime 关掉，交给下一轮重开。
                    vfs.close()
                }

                // 关闭之后：本轮所有打开过的流都必须不可读。
                streamsOpened.forEach { opened ->
                    assertTrue(opened.isClosed, "Runtime close 之后没有仍然开着的流（第 $round 轮）")
                    val thrown = assertFailsWith<Exception> { opened.stream.read() }
                    assertTrue(
                        thrown is IOException || thrown is VfsException,
                        "关闭后的流读取必须失败（第 $round 轮），实际是 $thrown",
                    )
                }

                // 同库重开成功：证明独占锁已放、连接已关。
                withVfs(config()) { reopened ->
                    assertEquals(committedId, reopened.getNode(committedId).id, "重开后已提交身份保留（第 $round 轮）")
                    assertEquals("round-$round", reopened.read(uri("/keep.txt")).toString(Charsets.UTF_8), "重开后内容还在")
                }
            }

            assertEquals(rounds, streamsOpened.size, "这一轮确实开过 $rounds 条流")
            assertEquals(1, countActive("/resources/docs/keep.txt"), "反复创建 / 关闭后仍只有一条有效记录")
            assertEquals(rounds, eventTypes().count { it == "FILE_WRITTEN" || it == "FILE_CREATED" }, "每轮一次写事件")
        }

    // ---- 公共清理与只读查询 ----

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

    private fun query(sql: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    /** 有效记录条数（`deleted_at IS NULL`）。 */
    private fun countActive(path: String): Int =
        query("SELECT COUNT(*) FROM node WHERE deleted_at IS NULL AND vfs_path = '$path'").single().toInt()

    /** 同一路径是否存在多个有效身份（应为 0）。 */
    private fun countActiveDuplicates(): Int =
        query(
            "SELECT COUNT(*) FROM (SELECT vfs_path FROM node WHERE deleted_at IS NULL GROUP BY vfs_path HAVING COUNT(*) > 1)",
        ).single().toInt()

    private fun findByPath(path: String): NodeId? =
        query("SELECT id FROM node WHERE deleted_at IS NULL AND vfs_path = '$path'").firstOrNull()?.let { NodeId.parse(it) }

    private fun eventCount(): Int = query("SELECT COUNT(*) FROM event").single().toInt()

    private fun eventTypes(): List<String> = query("SELECT event_type FROM event ORDER BY rowid")

    /** event 表按行序取出 ( event_type, node_id, uri )——用来核对事件身份与 URI。 */
    private data class EventRow(
        val type: String,
        val nodeId: String,
        val uri: String,
    )

    private fun eventRows(): List<EventRow> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT event_type, COALESCE(node_id, ''), uri FROM event ORDER BY rowid").use { rows ->
                    buildList { while (rows.next()) add(EventRow(rows.getString(1), rows.getString(2), rows.getString(3))) }
                }
            }
        }

    private fun eventNodeIds(): List<String> = query("SELECT COALESCE(node_id, '') FROM event ORDER BY rowid")
}
