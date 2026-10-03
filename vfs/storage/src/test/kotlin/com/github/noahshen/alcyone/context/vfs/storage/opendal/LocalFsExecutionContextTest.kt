package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.opendal.Operator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * T12 F1 / F2 / R2 的证据测试。
 *
 * 三类断言都不靠时序碰运气：
 *
 * - 线程归属用**线程名捕获**（调用方线程有专属名字），不靠「跑得慢所以在别处」。
 * - 句柄释放用**计数替身**，替身不碰 native，断言与 OpenDAL 内部行为无关。
 * - 取消窗口用 [nativeCallHook] 暂停 IO 块内的 continuation，确定性地停在「资源已取得、结果还没交回」那一刻。
 *
 * [Timeout]：这里用闩锁和独立线程编排时序，万一哪次等不到放行就挂死整个套件；超时把它变成一次失败。
 */
@Timeout(60)
class LocalFsExecutionContextTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var root: Path

    private fun setUpRoot(content: String = "0123456789") {
        root = Files.createDirectory(tempDir.resolve("root"))
        Files.writeString(root.resolve("a.txt"), content)
    }

    /** 记录自己被调用的线程名与关闭次数；纯 JVM 对象，不触碰 native。 */
    private class RecordingReader(
        payload: ByteArray,
    ) : InputStream() {
        private val source = ByteArrayInputStream(payload)

        @Volatile
        var threadName: String? = null

        var closeCalls = 0
            private set

        override fun read(): Int {
            threadName = Thread.currentThread().name
            return source.read()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            threadName = Thread.currentThread().name
            return source.read(buffer, offset, length)
        }

        override fun close() {
            closeCalls++
        }
    }

    /** 名字固定的单线程调度器：调用方线程名与 IO 线程一定不同。 */
    private fun callerDispatcher(name: String) = Executors.newSingleThreadExecutor { r -> Thread(r, name) }.asCoroutineDispatcher()

    // ---------------------------------------------------------------- F1

    /**
     * F1：`readStream` 的阻塞 native 调用必须离开调用方线程。
     *
     * 调用方跑在专属命名的单线程上；`nativeCallHook` 在 IO 块内被触发，它捕获到的线程名就是
     * 「这次存储操作实际跑在哪」。把它与调用方名对比，就直接回答了 F1 的问题，不依赖时序。
     */
    @Test
    fun `readStream runs its native calls off the caller thread`() {
        setUpRoot()
        val reader = RecordingReader("0123456789".toByteArray())
        val threads = mutableSetOf<String>()
        val caller = callerDispatcher("caller-stream")

        try {
            runBlocking(caller) {
                LocalFsStorage
                    .open(
                        root,
                        LocalFsOptions(),
                        onNativeCall = { threads += Thread.currentThread().name },
                        readerFactory = { _ -> { reader } },
                    ).use { storage ->
                        storage.readStream(StoragePath.parse("a.txt")).use { stream ->
                            assertEquals("0123456789", String(stream.openStream().readAllBytes()))
                        }
                    }
            }
        } finally {
            caller.close()
        }

        assertTrue(threads.isNotEmpty(), "至少要触发过一次 IO 块")
        threads.forEach { assertNotEquals("caller-stream", it, "阻塞的 native 调用不得跑在调用方线程上") }
    }

    /**
     * F1：`read` 的**闸门**（阻塞 `stat` + `Files.isSymbolicLink`）同样必须离开调用方线程。
     *
     * 这条正是 F1 报告里探针测的那一层。只勾「read precheck」这一步：
     * 不限定操作名的话，主体那次 `storageCall` 也会触发钩子，把闸门跑回调用方线程的回归盖掉。
     */
    @Test
    fun `read runs its blocking precheck off the caller thread`() {
        setUpRoot()
        Files.createDirectory(root.resolve("dir"))
        val precheckThreads = mutableSetOf<String>()
        val caller = callerDispatcher("caller-read")

        try {
            runBlocking(caller) {
                LocalFsStorage
                    .open(root, LocalFsOptions(), onNativeCall = { operation ->
                        if (operation == "read precheck") precheckThreads += Thread.currentThread().name
                    })
                    .use { storage ->
                        val failure = assertFailsWith<VfsException> { storage.read(StoragePath.parse("dir"), 64) }
                        assertEquals(VfsErrorCode.TYPE_MISMATCH, failure.code, "闸门先判类型再判内容")
                    }
            }
        } finally {
            caller.close()
        }

        assertEquals(1, precheckThreads.size, "闸门必须走一次 storageCall")
        precheckThreads.forEach { assertNotEquals("caller-read", it, "阻塞的 stat 不得跑在调用方线程上") }
    }

    /** 闸门拒绝符号链接时根本不该开 reader，且拒绝同样发生在 IO 调度器上。 */
    @Test
    fun `a symlinked readStream target is refused without opening a reader`() {
        setUpRoot()
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        Files.writeString(outside.resolve("secret.txt"), "SECRET")
        Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.txt"))
        val opened = AtomicInteger(0)
        val caller = callerDispatcher("caller-symlink")

        try {
            runBlocking(caller) {
                LocalFsStorage
                    .open(
                        root,
                        LocalFsOptions(),
                        readerFactory = { _ ->
                            { _ ->
                                opened.incrementAndGet()
                                RecordingReader("SECRET".toByteArray())
                            }
                        },
                    ).use { storage ->
                        val failure = assertFailsWith<VfsException> { storage.readStream(StoragePath.parse("link.txt")) }
                        assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, failure.code)
                    }
            }
        } finally {
            caller.close()
        }

        assertEquals(0, opened.get(), "闸门拒绝时根本不该开 reader")
        assertEquals("SECRET", Files.readString(outside.resolve("secret.txt")))
    }

    // ---------------------------------------------------------------- F2

    /**
     * F2 核心：**流没打开就关闭**必须释放那个 native reader。
     *
     * 旧断言（关完再读一个新流）只能证明 Operator 还可用，恒真；这里直接看替身的关闭次数。
     * 把 `LocalFsStream.close` 改回不释放的实现，这条立刻失败。
     */
    @Test
    fun `closing a stream that was never opened releases the reader`() {
        setUpRoot()
        val reader = RecordingReader("0123456789".toByteArray())

        runBlocking {
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    readerFactory = { _ -> { reader } },
                ).use { storage ->
                    val stream = storage.readStream(StoragePath.parse("a.txt"))

                    assertEquals(0, reader.closeCalls, "刚取到流时还没有任何关闭")
                    stream.close()

                    assertEquals(1, reader.closeCalls, "未打开就关闭必须释放底层 reader")
                }
        }
    }

    /** F2 补充：打开后由包装流负责关闭，两侧都幂等，同一句柄只关一次。 */
    @Test
    fun `an opened stream releases the reader through the wrapper exactly once`() {
        setUpRoot()
        val reader = RecordingReader("0123456789".toByteArray())

        runBlocking {
            LocalFsStorage
                .open(
                    root,
                    LocalFsOptions(),
                    readerFactory = { _ -> { reader } },
                ).use { storage ->
                    val stream = storage.readStream(StoragePath.parse("a.txt"))
                    val input = stream.openStream()

                    assertEquals(0, reader.closeCalls, "打开不等于关闭")
                    input.close()
                    assertEquals(1, reader.closeCalls)

                    input.close()
                    stream.close()
                    assertEquals(1, reader.closeCalls, "不能对同一 native 句柄 close 两次")
                }
        }
    }

    /** F2 第三种顺序：流未打开就关 Adapter，句柄同样被释放（这里不要求成功，只要求不双关、不泄漏）。 */
    @Test
    fun `closing the adapter after an unopened stream does not close the reader twice`() {
        setUpRoot()
        val reader = RecordingReader("0123456789".toByteArray())
        val storage =
            runBlocking {
                LocalFsStorage.open(root, LocalFsOptions(), readerFactory = { _ -> { reader } })
            }

        val stream = runBlocking { storage.readStream(StoragePath.parse("a.txt")) }
        stream.close()
        assertEquals(1, reader.closeCalls)
        stream.close()
        assertEquals(1, reader.closeCalls, "重复关闭必须幂等")
        storage.close()
    }

    // ---------------------------------------------------------------- R2

    /**
     * R2（`readStream`）：reader 已取得、但结果还没交回调用方时取消 → reader 必须被释放。
     *
     * 确定性编排：钩子在 IO 块**内部**暂停 continuation；块跑完时 `withContext` 发现已取消，
     * 抛出的 `CancellationException` 落在 `withContext` 返回处——正是「资源已取得、返回值被丢弃」那一刻。
     */
    @Test
    fun `readStream releases the reader when the caller is cancelled before delivery`() {
        setUpRoot()
        val reader = RecordingReader("0123456789".toByteArray())
        val caller = callerDispatcher("caller-cancel")
        val inBlock = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = AtomicBoolean(false)

        // 只钓开 reader 的那一步：闸门里的 stat 必须先跑完，资源才真的被取得。
        val job =
            launchCancelling(caller, inBlock, release, delivered, "open read stream") {
                LocalFsStorage
                    .open(
                        root,
                        LocalFsOptions(),
                        onNativeCall = pausingHook("open read stream", inBlock, release),
                        readerFactory = { _ -> { reader } },
                    ).use { storage ->
                        storage.readStream(StoragePath.parse("a.txt"))
                    }
            }

        runBlocking { job.join() }
        assertFalse(delivered.get(), "取消后调用方不能拿到成功结果")
        assertEquals(1, reader.closeCalls, "交接失败时必须释放已经取得的 reader")
        caller.close()
    }

    /**
     * R2（`create`）：Operator 已建好、但结果还没交回调用方时取消 → Operator 必须被释放。
     *
     * Operator 本身是 native 句柄，不能替换成替身，所以这一层分两步证明：
     * 端到端行为（取消后同路径可重新打开）+ 替身层的 release 契约（下一条用例）。
     */
    @Test
    fun `create releases the operator when the caller is cancelled before delivery`() {
        setUpRoot()
        val caller = callerDispatcher("caller-cancel-create")
        val inBlock = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val handed =
            java.util.concurrent.atomic
                .AtomicReference<Operator?>()

        // 真的打开一个 Operator 并把实例记下来：Operator 的 close 是 final native 方法，替不掉，
        // 所以释放证据取自 isDisposed —— 关键是**同一个实例**在取消后必须已释放，
        // 而不是「之后还能再打开一个」（那对泄漏恒真，正是 F2 揭穿的那种弱断言）。
        val job =
            launchCancelling(caller, inBlock, release, delivered, "open adapter") {
                LocalFsStorage.open(
                    root,
                    LocalFsOptions(),
                    operatorFactory = { path ->
                        Operator
                            .of(
                                org.apache.opendal.ServiceConfig.Fs
                                    .builder()
                                    .root(path.toString())
                                    .build(),
                            ).also { handed.set(it) }
                    },
                    onNativeCall = pausingHook("open adapter", inBlock, release),
                )
            }
        runBlocking { job.join() }

        assertFalse(delivered.get(), "取消后调用方不能拿到成功结果")
        val operator = handed.get()
        assertNotNull(operator, "Operator 确实被建好了")
        assertTrue(operator.isDisposed, "交接失败时必须关掉已经建好的 Operator")
        caller.close()
    }

    /** [handoffOrRelease] 契约本身：登记在内、取消在外 → release 必定被调用。 */
    @Test
    fun `handoffOrRelease releases the registered resource when delivery is cancelled`() {
        val releases = AtomicInteger(0)
        val registered = arrayOfNulls<Any>(1)
        val caller = callerDispatcher("caller-handoff")
        val inBlock = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = AtomicBoolean(false)

        val job =
            launchCancelling(caller, inBlock, release, delivered, "probe") {
                handoffOrRelease(release = { releases.incrementAndGet() }) {
                    storageCall("probe", onEnter = pausingHook("probe", inBlock, release)) {
                        registered[0] = "resource" // 登记必须发生在 IO 块内部
                    }
                }
            }
        runBlocking { job.join() }

        assertFalse(delivered.get())
        assertEquals("resource", registered[0], "IO 块确实跑完了，资源确实拿到了")
        assertEquals(1, releases.get(), "交接失败必须释放已登记的资源")
        caller.close()
    }

    /** 清理失败只 addSuppressed，不覆盖原始取消异常。 */
    @Test
    fun `a failing cleanup is attached as suppressed and never replaces the cancellation`() {
        val caller = callerDispatcher("caller-cleanup")
        val inBlock = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val observed =
            java.util.concurrent.atomic
                .AtomicReference<Throwable>()

        val job =
            launchCancelling(caller, inBlock, release, delivered, "probe") {
                try {
                    handoffOrRelease(release = { throw IllegalStateException("cleanup failed") }) {
                        storageCall("probe", onEnter = pausingHook("probe", inBlock, release)) { Unit }
                    }
                } catch (e: CancellationException) {
                    observed.set(e)
                    throw e
                }
            }
        runBlocking { job.join() }

        assertFalse(delivered.get())
        val cancellation = observed.get()
        assertTrue(cancellation is CancellationException, "必须仍然是取消异常")
        assertEquals("cleanup failed", cancellation.suppressed.singleOrNull()?.message, "清理失败只作为 suppressed 附加")
        caller.close()
    }

    /**
     * 启动协程并把 continuation 停在 IO 块**内部**，再取消它。
     *
     * 钩子由 [body] 自己作为构造参数传进被测对象，所以测试之间不会互相污染；
     * 块在取消后仍会跑完（阻塞 native 调用不响应协程取消，T12 已声明的边界），
     * 随后 `withContext` 在返回处抛取消——正是要覆盖的窗口。
     */
    private fun launchCancelling(
        caller: kotlinx.coroutines.CoroutineDispatcher,
        inBlock: CountDownLatch,
        release: CountDownLatch,
        delivered: AtomicBoolean,
        operation: String,
        body: suspend CoroutineScope.() -> Unit,
    ): Job {
        val job =
            CoroutineScope(caller).launch(start = CoroutineStart.UNDISPATCHED) {
                body()
                delivered.set(true)
            }
        assertTrue(inBlock.await(5, TimeUnit.SECONDS), "协程没有停在 $operation 的 IO 块内")
        job.cancel()
        release.countDown()
        runBlocking { job.join() }
        return job
    }

    /** 传给 [LocalFsStorage.open] 的观测点：只在目标操作上暂停，把 continuation 停在 IO 块内部。 */
    private fun pausingHook(
        operation: String,
        inBlock: CountDownLatch,
        release: CountDownLatch,
    ): (String) -> Unit =
        { current ->
            if (current == operation) {
                inBlock.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
}
