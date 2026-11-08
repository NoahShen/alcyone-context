package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** CT17 B1：safeReplace 必须进入 AlcyoneVfs 的在途登记、关闭等待与取消协议。 */
class RuntimeSafeReplaceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Path
    private lateinit var disk: Path

    private fun prepare() {
        database = tempDir.resolve("state.db")
        disk = Files.createDirectory(tempDir.resolve("disk"))
    }

    private fun config(closeGracePeriod: Duration = 30.seconds) =
        VfsRuntimeConfig(
            stateDatabase = database,
            namespaces = setOf("resources"),
            mounts = listOf(MountConfig(VfsPath.parse("/resources"), "local", disk)),
            closeGracePeriod = closeGracePeriod,
        )

    private fun uri(name: String) = VfsUri.parse("alcyone://resources/$name")

    private suspend fun awaitClosing(vfs: AlcyoneVfs) {
        withTimeout(30_000) {
            while (true) {
                val failure = assertFailsWith<VfsException> { vfs.read(uri("probe.txt")) }
                if (failure.code == VfsErrorCode.CLOSED) return@withTimeout
                delay(10)
            }
        }
    }

    @Test
    @Timeout(60)
    fun `safeReplace enters the operation lifecycle and completes within the grace period`() =
        runBlocking {
            prepare()
            val vfs = AlcyoneVfs.create(config())
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val operations = mutableListOf<String>()
            try {
                val original = vfs.write(uri("a.txt"), "old".toByteArray())
                vfs.beforeOperation = { name ->
                    operations += name
                    if (name == "safeReplace") {
                        entered.complete(Unit)
                        gate.await()
                    }
                }
                val replacement = async(start = CoroutineStart.UNDISPATCHED) { vfs.safeReplace(uri("a.txt"), "new".toByteArray()) }
                withTimeout(30_000) { entered.await() }
                assertEquals("old", Files.readString(disk.resolve("a.txt")), "hook runs before delegate work")

                val closing = async { vfs.close() }
                awaitClosing(vfs)
                gate.complete(Unit)

                val info = withTimeout(30_000) { replacement.await() }
                withTimeout(30_000) { closing.await() }
                assertEquals(original.id, info.id)
                assertEquals("new", Files.readString(disk.resolve("a.txt")))
                assertTrue("safeReplace" in operations)
            } finally {
                gate.complete(Unit)
                if (vfs.beforeOperation != null) vfs.beforeOperation = null
                runCatching { vfs.close() }
            }
        }

    @Test
    @Timeout(60)
    fun `CLOSING and CLOSED refuse new safeReplace calls`() =
        runBlocking {
            prepare()
            val vfs = AlcyoneVfs.create(config())
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            try {
                vfs.write(uri("a.txt"), "old".toByteArray())
                vfs.beforeOperation = { name ->
                    if (name == "safeReplace") {
                        entered.complete(Unit)
                        gate.await()
                    }
                }
                val inFlight = async(start = CoroutineStart.UNDISPATCHED) { vfs.safeReplace(uri("a.txt"), "first".toByteArray()) }
                withTimeout(30_000) { entered.await() }
                val closing = async { vfs.close() }
                awaitClosing(vfs)

                val duringClose = assertFailsWith<VfsException> { vfs.safeReplace(uri("a.txt"), "second".toByteArray()) }
                assertEquals(VfsErrorCode.CLOSED, duringClose.code)

                gate.complete(Unit)
                withTimeout(30_000) { inFlight.await() }
                withTimeout(30_000) { closing.await() }

                val afterClose = assertFailsWith<VfsException> { vfs.safeReplace(uri("a.txt"), "third".toByteArray()) }
                assertEquals(VfsErrorCode.CLOSED, afterClose.code)
                assertEquals("first", Files.readString(disk.resolve("a.txt")))
            } finally {
                gate.complete(Unit)
                runCatching { vfs.close() }
            }
        }

    @Test
    @Timeout(60)
    fun `a safeReplace still running after the grace period is cancelled before target change`() =
        runBlocking {
            prepare()
            val vfs = AlcyoneVfs.create(config(closeGracePeriod = Duration.ZERO))
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            try {
                vfs.write(uri("a.txt"), "old".toByteArray())
                vfs.beforeOperation = { name ->
                    if (name == "safeReplace") {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (failure: CancellationException) {
                            cancelled.complete(Unit)
                            throw failure
                        }
                    }
                }
                val operation =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { vfs.safeReplace(uri("a.txt"), "new".toByteArray()) }
                    }
                withTimeout(30_000) { entered.await() }
                vfs.close()
                withTimeout(30_000) { cancelled.await() }
                val failure = withTimeout(30_000) { operation.await() }.exceptionOrNull()
                assertTrue(failure is CancellationException, "close cancellation reaches safeReplace: $failure")
                assertEquals("old", Files.readString(disk.resolve("a.txt")))
            } finally {
                runCatching { vfs.close() }
            }
        }
}
