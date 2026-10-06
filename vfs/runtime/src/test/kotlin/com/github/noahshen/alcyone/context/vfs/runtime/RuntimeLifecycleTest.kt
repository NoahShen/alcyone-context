package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * T18 A06 / A07：关闭与在途调用。真的 SQLite + 真的本机目录。
 *
 * 「可控挂起」靠 `AlcyoneVfs.beforeOperation`：它让公共操作在真正动手之前停一下，
 * 这样 close 一定和一次**已经到达挂起点**的操作碰上，不是「刚启动、还没跑完」那种假相遇。
 */
class RuntimeLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var disk: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        disk = Files.createDirectory(tempDir.resolve("disk"))
    }

    private fun config(closeGracePeriod: kotlin.time.Duration = 30.seconds) =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts = listOf(MountConfig(VfsPath.parse("/resources"), "local", disk)),
            closeGracePeriod = closeGracePeriod,
        )

    private fun uri(name: String) = VfsUri.parse("alcyone://resources/$name")

    /**
     * 在途操作挂住时 close 已经开始的标志。
     *
     * 探测用的是 `read`（钩子只挂住 `list`），所以探测本身不会撞上挂起点：
     * 还没开始关时报 NOT_FOUND（探针文件不存在），进了 closing 才报 CLOSED。
     */
    private suspend fun awaitClosing(vfs: AlcyoneVfs) {
        withTimeout(30_000) {
            while (true) {
                val probe = assertFailsWith<VfsException> { vfs.read(uri("probe.txt")) }
                if (probe.code == VfsErrorCode.CLOSED) return@withTimeout
                delay(10)
            }
        }
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

    /** close 一开始就拒新调用：在途的那个还挂着的时候，另一个调用必须拿到 CLOSED。 */
    @Test
    @Timeout(60)
    fun `a new operation after close starts is refused with CLOSED`() =
        runBlocking {
            prepare()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()

            val vfs = AlcyoneVfs.create(config())
            // 只挂住 list：后面的 read 探针要能真的跑起来
            vfs.beforeOperation = { operation ->
                if (operation == "list") {
                    entered.complete(Unit)
                    gate.await()
                }
            }
            val stuck = async(start = CoroutineStart.UNDISPATCHED) { vfs.list(VfsUri.parse("alcyone://resources")) }
            withTimeout(30_000) { entered.await() }

            val closing = async { vfs.close() }
            awaitClosing(vfs)

            val refused = assertFailsWith<VfsException> { vfs.read(uri("a.txt")) }
            assertEquals(VfsErrorCode.CLOSED, refused.code, "closing 之后的新操作一律 CLOSED")
            val alsoRefused = assertFailsWith<VfsException> { vfs.stat(uri("a.txt")) }
            assertEquals(VfsErrorCode.CLOSED, alsoRefused.code)

            gate.complete(Unit)
            withTimeout(30_000) { stuck.await() }
            withTimeout(30_000) { closing.await() }
        }

    /** 等待期内放行：在途操作正常跑完，close 也正常返回。 */
    @Test
    @Timeout(60)
    fun `an in-flight operation can finish within the grace period`() =
        runBlocking {
            prepare()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val vfs = AlcyoneVfs.create(config(closeGracePeriod = 30.seconds))

            vfs.beforeOperation = {
                entered.complete(Unit)
                gate.await()
            }
            val stuck = async(start = CoroutineStart.UNDISPATCHED) { vfs.list(VfsUri.parse("alcyone://resources")) }
            withTimeout(30_000) { entered.await() }

            val closing = async { vfs.close() }
            gate.complete(Unit) // 等待期里放行，不该被取消

            val entries = withTimeout(30_000) { stuck.await() }
            withTimeout(30_000) { closing.await() }

            assertTrue(entries.isEmpty(), "挂起的那次 list 正常跑完，没有被取消")
        }

    /**
     * R6（复核 §10.3）：等待期到点后，**实际工作**必须收到取消，且不牵连宿主的父 Job。
     *
     * 时序确定：等 close 真到「发完取消、开始等收尾」再放行等待期，而不是靠「协程还没完成」当证据。
     */
    @Test
    @Timeout(10)
    fun `a close after the grace period cancels the real work without touching the parent job`() =
        runBlocking {
            prepare()
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val closeWaiting = CompletableDeferred<Unit>()
            val parent = CompletableDeferred<Unit>()

            val vfs = AlcyoneVfs.create(config(closeGracePeriod = Duration.ZERO))
            vfs.beforeOperation = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (cancellation: CancellationException) {
                    cancelled.complete(Unit)
                    throw cancellation
                }
            }
            vfs.beforeWaitingForInFlight = { closeWaiting.complete(Unit) }

            // 调用方协程挂在一个父 Job 下，用来验证取消不会顺着冒到父 Job。
            val parentJob = Job()
            val caller =
                CoroutineScope(coroutineContext + parentJob).async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { vfs.list(VfsUri.parse("alcyone://resources")) }
                }
            withTimeout(30_000) { entered.await() }

            val closing = async(kotlinx.coroutines.Dispatchers.Default) { vfs.close() }
            withTimeout(30_000) { closeWaiting.await() } // 确定 close 已发取消、进入等收尾

            try {
                // 给 2 秒等待实际工作收到取消；如果 cancelWork 没起作用，这里超时抛错并进入 catch
                withTimeout(2_000) { cancelled.await() }
            } catch (t: Throwable) {
                // 如果实际工作没被取消，主动取消它并释放，让测试失败并顺利退出，避免卡死
                caller.cancel()
                closing.cancel()
                throw AssertionError("实际工作未收到取消！变异被咬住: $t")
            }
            assertTrue(parentJob.isActive || parentJob.children.none { it.isActive }, "宿主的父 Job 不该被取消传播打挂：$parentJob")

            withTimeout(10_000) { closing.await() }
            withTimeout(10_000) { caller.join() }
            parent.complete(Unit)
            Unit
        }

    /** 等待期到了还在跑的：收到协作取消，close 照常走完并把资源放掉。 */
    @Test
    @Timeout(60)
    fun `an operation still running after the grace period is cancelled`() =
        runBlocking {
            prepare()
            val entered = CompletableDeferred<Unit>()
            val outcome = CompletableDeferred<String>()

            val vfs = AlcyoneVfs.create(config(closeGracePeriod = 200.milliseconds))
            vfs.beforeOperation = {
                entered.complete(Unit)
                // 一直挂着不配合：等待期结束后只能靠 close 发取消。
                CompletableDeferred<Unit>().await()
            }
            val stuck =
                async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        vfs.list(VfsUri.parse("alcyone://resources"))
                        outcome.complete("completed")
                    } catch (cancellation: CancellationException) {
                        outcome.complete("cancelled")
                    }
                }
            withTimeout(30_000) { entered.await() }

            val started = System.nanoTime()
            vfs.close()
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000

            assertEquals("cancelled", withTimeout(30_000) { outcome.await() }, "等待期到点后请求取消")
            assertTrue(elapsedMillis < 30_000, "等待期是 200 ms，不该等满默认的 30 s：${elapsedMillis}ms")
            assertTrue(stuck.isCompleted)
            withTimeout(30_000) { stuck.join() }

            // 锁已经放掉：同一个状态库还能再开一个实例。
            withVfs(config()) { reopened ->
                assertEquals(
                    "/resources",
                    reopened
                        .stat(VfsUri.parse("alcyone://resources"))
                        .uri.path
                        .toString(),
                )
            }
        }

    /** 两个 close 并发：都正常返回，注入的资源只被释放一次。 */
    @Test
    @Timeout(60)
    fun `concurrent close calls share one completion and release once`() =
        runBlocking {
            prepare()
            val releases = AtomicInteger()

            val vfs =
                AlcyoneVfs.create(
                    config(),
                    AlcyoneVfs.AssemblyProbe { },
                    listOf(AutoCloseable { releases.incrementAndGet() }),
                )
            val first = async { vfs.close() }
            val second = async { vfs.close() }

            withTimeout(30_000) { first.await() }
            withTimeout(30_000) { second.await() }

            assertEquals(1, releases.get(), "并发关闭共享同一次完成结果，不会释放两遍")
            val refused = assertFailsWith<VfsException> { vfs.stat(uri("a.txt")) }
            assertEquals(VfsErrorCode.CLOSED, refused.code)
        }

    /** 一个资源关失败，不许挡住其余资源：锁和状态库照样放掉，原始失败留在 cause 里。 */
    @Test
    @Timeout(60)
    fun `one resource failing to close does not stop the others`() =
        runBlocking {
            prepare()
            val injected = IllegalStateException("injected close failure")

            val vfs =
                AlcyoneVfs.create(
                    config(),
                    AlcyoneVfs.AssemblyProbe { },
                    listOf(
                        AutoCloseable { throw injected },
                    ),
                )

            val thrown = assertFailsWith<VfsException> { vfs.close() }
            assertEquals(VfsErrorCode.STATE_ERROR, thrown.code)
            assertTrue(thrown.cause === injected, "最初那一个失败原样留在 cause 里：${thrown.cause}")

            // 其余资源仍然被尝试过：状态库连接关了、锁放了，同一个库能再开。
            withVfs(config()) { reopened ->
                assertEquals(
                    "/resources",
                    reopened
                        .stat(VfsUri.parse("alcyone://resources"))
                        .uri.path
                        .toString(),
                )
            }
        }

    /** 关掉再开：已经提交的 Node 和 Metadata 都还在（关了不是「回滚初始化」）。 */
    @Test
    @Timeout(60)
    fun `a closed instance can be reopened and keeps committed state`() =
        runBlocking {
            prepare()
            val vfs = AlcyoneVfs.create(config())
            val written = vfs.write(uri("keep.txt"), "kept".toByteArray())
            vfs.setMetadata(
                written.id,
                com.github.noahshen.alcyone.context.vfs
                    .NodeMetadata(setOf("ct"), "胸部 CT"),
            )
            vfs.close()

            withVfs(config()) { reopened ->
                assertEquals("kept", reopened.read(uri("keep.txt")).toString(Charsets.UTF_8), "文件还在")
                assertEquals(written.id, reopened.getNode(written.id).id, "同一个 Node ID 重开还查得到")
                assertEquals(setOf("ct"), reopened.getMetadata(written.id).tags, "Metadata 也还在")
            }
        }

    /** Consumer 自己抛错：已经提交的写入不受影响，事件照样在事件日志里。 */
    @Test
    @Timeout(60)
    fun `a consumer failure does not undo a committed operation`() =
        runBlocking {
            prepare()
            val vfs = AlcyoneVfs.create(config())
            vfs.subscribe { throw IllegalStateException("injected consumer failure") }

            val written = vfs.write(uri("committed.txt"), "committed".toByteArray())

            assertEquals(written.id, vfs.getNode(written.id).id, "Consumer 抛错不撤销提交")
            assertEquals("committed", Files.readString(disk.resolve("committed.txt")))
            vfs.close()

            withVfs(config()) { reopened ->
                assertEquals(written.id, reopened.getNode(written.id).id, "重开之后状态照样在")
            }
        }

    /** 在事件回调里触发 close：不许自己等自己（等的是分发协程，它正等着这个回调返回）。 */
    @Test
    @Timeout(60)
    fun `closing from inside an event callback does not deadlock`() =
        runBlocking {
            prepare()
            val closed = CompletableDeferred<Unit>()

            val vfs = AlcyoneVfs.create(config())
            vfs.subscribe { runBlocking { vfs.close() }.let { closed.complete(Unit) } }

            val written = vfs.write(uri("from-callback.txt"), "ok".toByteArray())
            withTimeout(30_000) { closed.await() }

            assertEquals("ok", Files.readString(disk.resolve("from-callback.txt")), "回调里的 close 没拖死那次 write")
            assertEquals("/resources/from-callback.txt", written.uri.path.toString(), "write 正常返回了 NodeInfo")
            val refused = assertFailsWith<VfsException> { vfs.list(VfsUri.parse("alcyone://resources")) }
            assertEquals(VfsErrorCode.CLOSED, refused.code, "确实已经关掉了")
        }

    /**
     * R6：取消请求发出后，close 必须等在途操作真的收尾完（含 NonCancellable 回滚），才能关库和放锁。
     * 收尾还在跑的时候，close 不能返回、同一个库的锁不能被放掉。
     */
    @Test
    @Timeout(60)
    fun `close waits for an admitted operation to finish its cleanup before releasing the database`() =
        runBlocking {
            prepare()
            val entered = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val cleanupGate = CompletableDeferred<Unit>()
            val cleanupDone = CompletableDeferred<Unit>()

            val vfs = AlcyoneVfs.create(config(closeGracePeriod = Duration.ZERO))
            vfs.beforeOperation = {
                try {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                } catch (cancellation: CancellationException) {
                    // 模拟在途操作在 NonCancellable 里做清理 / 回滚
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        cleanupGate.await()
                        cleanupDone.complete(Unit)
                    }
                    throw cancellation
                }
            }
            val stuck = async(start = CoroutineStart.UNDISPATCHED) { vfs.list(VfsUri.parse("alcyone://resources")) }
            withTimeout(30_000) { entered.await() }

            val closing = async { vfs.close() }
            withTimeout(30_000) { cleanupStarted.await() }

            // 收尾没跑完时：close 不能先返回，锁也必须还占着
            assertFalse(closing.isCompleted, "收尾没结束，close 不能先返回")
            val lockConflict = assertFailsWith<VfsException> { AlcyoneVfs.create(config()) }
            assertEquals(VfsErrorCode.CONFLICT, lockConflict.code, "收尾期间独占锁不能提前放掉")

            cleanupGate.complete(Unit)
            withTimeout(30_000) { cleanupDone.await() }
            withTimeout(30_000) { closing.await() }

            // 收尾彻底完成之后，同一个库能正常重开
            withVfs(config()) { reopened ->
                assertEquals(
                    "/resources",
                    reopened
                        .stat(VfsUri.parse("alcyone://resources"))
                        .uri.path
                        .toString(),
                )
            }
            // 最后一句必须返回 Unit：Kotlin 会把返回 kotlin.Result 的方法名字混淆，JUnit 就会把它静默跳过
            stuck.join()
        }

    /**
     * R7：外部先开始 close，回调随后也发起 close，两边都不许死锁。
     * 回调里的 close 只是「提交了关闭请求」，不能在 completion.await 上等外部那次把分发协程关掉。
     */
    @Test
    @Timeout(60)
    fun `an external close and a callback close do not wait for each other`() =
        runBlocking {
            prepare()
            val callbackEntered = CompletableDeferred<Unit>()
            val allowCallbackClose = CompletableDeferred<Unit>()
            val callbackClosed = CompletableDeferred<Unit>()

            val vfs = AlcyoneVfs.create(config())
            vfs.subscribe {
                callbackEntered.complete(Unit)
                runBlocking {
                    allowCallbackClose.await()
                    vfs.close() // 这时外部 close 已经在关，这次调用不能等包含它自己的那次收尾
                    callbackClosed.complete(Unit)
                }
            }

            vfs.write(uri("event.txt"), "x".toByteArray())
            withTimeout(30_000) { callbackEntered.await() }

            val external = async { vfs.close() }
            awaitClosing(vfs)

            allowCallbackClose.complete(Unit)
            withTimeout(30_000) { callbackClosed.await() } // 死锁的话这里会超时
            withTimeout(30_000) { external.await() } // 外部 close 也要正常走完

            val refused = assertFailsWith<VfsException> { vfs.list(VfsUri.parse("alcyone://resources")) }
            assertEquals(VfsErrorCode.CLOSED, refused.code)
        }
}
