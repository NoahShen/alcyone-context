package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DreamingCommitCrashRecoveryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `SIGKILL after real commit resumes in a second JVM without duplicates`() {
        val database = directory.resolve("data/context.db")
        val crash = launchProbe(database, crash = true)
        val marker = Files.readString(crash.first).trim()
        assertEquals("CT26_COMMIT_RETURNED_BEFORE_NOTIFICATION", marker)
        assertTrue(crash.second.isAlive)
        crash.second.destroyForcibly()
        assertTrue(crash.second.waitFor(15, TimeUnit.SECONDS))
        assertEquals(1L, checkpoint(database))
        assertEquals(listOf("ct26-crash-1"), rawIds(database))
        assertEquals(listOf("ct26-crash-1"), pendingIds(database))

        val recovery = launchProbe(database, crash = false)
        assertEquals("RECOVERY_RESULT=1:2:1:1", Files.readString(recovery.first).trim())
        assertEquals(2L, checkpoint(database))
        assertEquals(listOf("ct26-crash-1", "ct26-crash-2"), rawIds(database))
        assertEquals(listOf("ct26-crash-1", "ct26-crash-2"), pendingIds(database))
        assertEquals(
            2L,
            query(database, "SELECT COUNT(DISTINCT raw_memory_id) FROM organization_pending") { result ->
                check(result.next())
                result.getLong(1)
            },
        )
    }

    private fun launchProbe(
        database: Path,
        crash: Boolean,
    ): Pair<Path, Process> {
        val stdout = directory.resolve(if (crash) "crash.stdout" else "recovery.stdout")
        val stderr = directory.resolve(if (crash) "crash.stderr" else "recovery.stderr")
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                DreamingCommitCrashRecoveryProbe::class.qualifiedName,
                database.toString(),
                crash.toString(),
            ).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            waitForProbe(stdout, process)
            if (!crash) {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Recovery probe did not exit")
                assertEquals(0, process.exitValue(), Files.readString(stderr))
            }
            println("CT26 controlled probe pid=${process.pid()} crash=$crash output=$stdout")
            return stdout to process
        } catch (failure: Throwable) {
            process.destroyForcibly()
            process.waitFor(15, TimeUnit.SECONDS)
            throw AssertionError("Controlled probe failed; stderr=${Files.readString(stderr)}", failure)
        }
    }

    private fun waitForProbe(
        stdout: Path,
        process: Process,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline && process.isAlive) {
            if (Files.exists(stdout) && Files.readString(stdout).isNotBlank()) return
            Thread.sleep(25)
        }
        if (!process.isAlive) return
        throw AssertionError("Timed out waiting for controlled commit boundary")
    }

    private fun checkpoint(database: Path): Long =
        query(database, "SELECT last_processed_turn_id FROM dreaming_checkpoint WHERE id = 1") { result ->
            check(result.next())
            result.getLong(1)
        }

    private fun rawIds(database: Path): List<String> =
        query(database, "SELECT id FROM raw_memory ORDER BY id") { result ->
            buildList { while (result.next()) add(result.getString(1)) }
        }

    private fun pendingIds(database: Path): List<String> =
        query(database, "SELECT raw_memory_id FROM organization_pending ORDER BY raw_memory_id") { result ->
            buildList { while (result.next()) add(result.getString(1)) }
        }

    private fun <T> query(
        database: Path,
        sql: String,
        block: (java.sql.ResultSet) -> T,
    ): T =
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath().normalize()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 5000")
                statement.executeQuery(sql).use(block)
            }
        }
}
