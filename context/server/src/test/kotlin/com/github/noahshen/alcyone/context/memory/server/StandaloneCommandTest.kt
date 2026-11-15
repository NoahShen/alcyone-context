package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseExecutor
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseFactory
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextDatabaseInitializer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneCommandTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `strict argument matrix returns usage without constructing runtime`() {
        val yaml = writeYaml(directory.resolve("config.yaml"))
        val valid = "--config,$yaml"
        val cases =
            listOf(
                "",
                "unknown --config $yaml",
                "init",
                "init $valid extra",
                "init $valid --config $yaml",
                "init --backup-dir $directory $valid",
                "init --config --backup-dir",
                "upgrade $valid",
                "upgrade $valid --backup-dir",
                "upgrade $valid --backup-dir $directory --backup-dir $directory",
                "run $valid --backup-dir $directory",
                "run $valid --unknown x",
                "run --config $valid",
            )
        for (args in cases) {
            val result = invoke(if (args.isEmpty()) emptyArray() else args.split(" ").toTypedArray())
            assertEquals(2, result.code, args)
            assertEquals("USAGE_ERROR\n", result.error)
            assertEquals("", result.output)
            assertFalse(Files.exists(directory.resolve("data/context.sqlite")))
            assertFalse(Files.exists(directory.resolve("data/vfs.sqlite")))
        }
    }

    @Test
    fun `init creates only Context v1 and repeat is safe fixed failure`() {
        val yaml = writeYaml(directory.resolve("nested/config.yaml"))
        val first = invoke(arrayOf("init", "--config", yaml.toString()))
        assertEquals(0, first.code)
        assertEquals("INITIALIZED\n", first.output)
        assertEquals("", first.error)
        assertTrue(Files.size(directory.resolve("nested/data/context.sqlite")) > 0)
        assertFalse(Files.exists(directory.resolve("nested/data/vfs.sqlite")))
        val repeated = invoke(arrayOf("init", "--config", yaml.toString()))
        assertEquals(3, repeated.code)
        assertEquals("INIT_FAILED\n", repeated.error)
        assertEquals("", repeated.output)
    }

    @Test
    fun `upgrade backs up before no_op with safe filename only`() {
        val yaml = writeYaml(directory.resolve("config.yaml"))
        assertEquals(0, invoke(arrayOf("init", "--config", yaml.toString())).code)
        val backups = Files.createDirectory(directory.resolve("backup dir"))
        val result = invoke(arrayOf("upgrade", "--config", yaml.toString(), "--backup-dir", backups.toString()))
        assertEquals(0, result.code)
        assertEquals("", result.error)
        val backup = Files.list(backups).use { it.findFirst().orElseThrow() }
        assertEquals("NO_OP schema_version=1 backup_id=${backup.fileName}\n", result.output)
        assertFalse(Files.exists(directory.resolve("data/vfs.sqlite")))
        DriverManager.getConnection("jdbc:sqlite:${backup.toUri()}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA quick_check").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("ok", rows.getString(1))
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun `invalid config missing backup dir and wrong schema use fixed codes`() {
        val yaml = writeYaml(directory.resolve("config.yaml"))
        assertEquals(2, invoke(arrayOf("init", "--config", directory.resolve("missing.yaml").toString())).code)
        Files.writeString(yaml, "server:\n  unknown: 1\n")
        val invalid = invoke(arrayOf("init", "--config", yaml.toString()))
        assertEquals(2, invalid.code)
        assertEquals("CONFIG_INVALID\n", invalid.error)
        assertFalse("unknown" in invalid.error)
        val good = writeYaml(directory.resolve("good.yaml"))
        assertEquals(3, invoke(arrayOf("upgrade", "--config", good.toString(), "--backup-dir", directory.toString())).code)
        assertEquals(0, invoke(arrayOf("init", "--config", good.toString())).code)
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("data/context.sqlite")}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version=2") }
        }
        val wrong = invoke(arrayOf("upgrade", "--config", good.toString(), "--backup-dir", directory.toString()))
        assertEquals(3, wrong.code)
        assertEquals("UPGRADE_FAILED\n", wrong.error)
        assertEquals("", wrong.output)
        assertFalse("NO_OP" in wrong.output)
        assertFalse("backup_id" in wrong.output)
    }

    @Test
    fun `concurrent init has one winner and losers do not overwrite`() {
        val yaml = writeYaml(directory.resolve("config.yaml"))
        val pool = Executors.newFixedThreadPool(6)
        val gate = CountDownLatch(1)
        val results =
            (1..6).map {
                pool.submit<Int> {
                    gate.await()
                    invoke(arrayOf("init", "--config", yaml.toString())).code
                }
            }
        gate.countDown()
        assertEquals(listOf(0, 3), results.map { it.get(20, TimeUnit.SECONDS) }.distinct().sorted())
        pool.shutdownNow()
        assertFalse(Files.exists(directory.resolve("data/vfs.sqlite")))
    }

    @Test
    fun `zero corrupt low and high schema never overwrite and leave no backup`() {
        for (kind in listOf("zero", "corrupt", "low", "high")) {
            val yaml = writeYaml(directory.resolve("$kind/config.yaml"))
            val source = directory.resolve("$kind/data/context.sqlite")
            Files.createDirectories(source.parent)
            when (kind) {
                "zero" -> Files.createFile(source)
                "corrupt" -> Files.writeString(source, "not sqlite")
                else -> {
                    assertEquals(0, invoke(arrayOf("init", "--config", yaml.toString())).code)
                    DriverManager.getConnection("jdbc:sqlite:$source").use { connection ->
                        connection.createStatement().use { it.execute("PRAGMA user_version=${if (kind == "low") 0 else 2}") }
                    }
                }
            }
            val before = Files.readAllBytes(source).toList()
            val backups = Files.createDirectory(directory.resolve("$kind/backups"))
            val result = invoke(arrayOf("upgrade", "--config", yaml.toString(), "--backup-dir", backups.toString()))
            assertEquals(3, result.code, kind)
            assertEquals("UPGRADE_FAILED\n", result.error)
            assertEquals(before, Files.readAllBytes(source).toList())
            assertEquals(0, Files.list(backups).use { it.count() })
        }
    }

    @Test
    fun `concurrent upgrades claim unique backup names and every copy is readable`() {
        val yaml = writeYaml(directory.resolve("concurrent-upgrade/config.yaml"))
        assertEquals(0, invoke(arrayOf("init", "--config", yaml.toString())).code)
        val backups = Files.createDirectory(directory.resolve("concurrent-upgrade/backups"))
        val pool = Executors.newFixedThreadPool(4)
        val results =
            (1..4).map {
                pool.submit<Int> {
                    invoke(arrayOf("upgrade", "--config", yaml.toString(), "--backup-dir", backups.toString())).code
                }
            }
        assertEquals(listOf(0), results.map { it.get(20, TimeUnit.SECONDS) }.distinct())
        pool.shutdownNow()
        assertEquals(
            4,
            Files.list(backups).use { stream ->
                stream
                    .filter {
                        !it.fileName.toString().endsWith("-journal") &&
                            !it.fileName.toString().endsWith("-shm") &&
                            !it.fileName.toString().endsWith("-wal")
                    }.count()
            },
        )
        val leftovers =
            Files.list(backups).use { stream ->
                stream
                    .filter {
                        it.fileName.toString().endsWith("-journal") ||
                            it.fileName.toString().endsWith("-shm") ||
                            it.fileName.toString().endsWith("-wal")
                    }.toList()
            }
        assertEquals(emptyList(), leftovers)
    }

    @Test
    fun `init never overwrites zero corrupt low high or valid existing database`() {
        for (kind in listOf("zero", "corrupt", "low", "high", "valid")) {
            val yaml = writeYaml(directory.resolve("init-$kind/config.yaml"))
            val source = directory.resolve("init-$kind/data/context.sqlite")
            Files.createDirectories(source.parent)
            when (kind) {
                "zero" -> Files.createFile(source)
                "corrupt" -> Files.writeString(source, "not sqlite")
                else -> {
                    ContextDatabaseInitializer.initialize(source)
                    if (kind != "valid") {
                        DriverManager.getConnection("jdbc:sqlite:$source").use { connection ->
                            connection.createStatement().use { it.execute("PRAGMA user_version=${if (kind == "low") 0 else 2}") }
                        }
                    }
                }
            }
            val before = Files.readAllBytes(source).toList()
            val result = invoke(arrayOf("init", "--config", yaml.toString()))
            assertEquals(3, result.code, kind)
            assertEquals("INIT_FAILED\n", result.error)
            assertEquals("", result.output)
            assertEquals(before, Files.readAllBytes(source).toList())
            assertFalse(Files.exists(directory.resolve("init-$kind/data/vfs.sqlite")))
        }
    }

    @Test
    fun `upgrade copies committed raw payload and missing run database exits four without creation`() {
        val yaml = writeYaml(directory.resolve("raw/config.yaml"))
        val source = directory.resolve("raw/data/context.sqlite")
        ContextDatabaseInitializer.initialize(source)
        ContextDatabaseFactory.open(source).use { database ->
            insertCommittedTurnAndRaw(database)
        }
        val backups = Files.createDirectory(directory.resolve("raw/backups"))
        val result = invoke(arrayOf("upgrade", "--config", yaml.toString(), "--backup-dir", backups.toString()))
        assertEquals(0, result.code)
        val backup = Files.list(backups).use { it.findFirst().orElseThrow() }
        DriverManager.getConnection("jdbc:sqlite:${backup.toUri()}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT content FROM raw_memory WHERE id='raw-1'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("committed raw payload", rows.getString(1))
                }
            }
        }
        val missingYaml = writeYaml(directory.resolve("run-missing/config.yaml"))
        val missing = invoke(arrayOf("run", "--config", missingYaml.toString()))
        assertEquals(4, missing.code)
        assertEquals("RUN_FAILED\n", missing.error)
        assertEquals("", missing.output)
        assertFalse(Files.exists(directory.resolve("run-missing/data/context.sqlite")))
        assertFalse(Files.exists(directory.resolve("run-missing/data/vfs.sqlite")))
    }

    @Test
    fun `run remains alive until termination and then closes managed runtime`() {
        val yaml = writeYaml(directory.resolve("run-blocking/config.yaml"))
        assertEquals(0, invoke(arrayOf("init", "--config", yaml.toString())).code)
        val pool = Executors.newSingleThreadExecutor()
        val running = pool.submit<Result> { invoke(arrayOf("run", "--config", yaml.toString())) }
        Thread.sleep(1500)
        assertFalse(running.isDone)
        StandaloneCommand.requestTerminationForTesting()
        val result = running.get(20, TimeUnit.SECONDS)
        assertEquals(0, result.code)
        assertEquals("", result.error)
        pool.shutdownNow()
    }

    private fun insertCommittedTurnAndRaw(database: ContextDatabaseExecutor) {
        runBlocking {
            database.updateRaw(
                "INSERT INTO conversation_turn(runtime_id,agent_id,session_id,session_turn_id,timestamp,created_at,messages_json) " +
                    "VALUES ('r','a','s','t','1970-01-01T00:00:00Z','1970-01-01T00:00:00Z','[]')",
            )
            database.updateRaw(
                "INSERT INTO raw_memory(id,content,generated_by,sources_json,created_at) " +
                    "VALUES ('raw-1','committed raw payload','EXTRACTION','[]','1970-01-01T00:00:00Z')",
            )
        }
    }

    private fun writeYaml(path: Path): Path {
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            "context:\n  database:\n    path: ./data/context.sqlite\n" +
                "vfs:\n  database:\n    path: ./data/vfs.sqlite\n  namespaces: [memory]\n" +
                "server:\n  auth:\n    readKey: read-key\n    adminKey: admin-key\n",
        )
        return path
    }

    private data class Result(
        val code: Int,
        val output: String,
        val error: String,
    )

    private fun invoke(args: Array<String>): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code =
            StandaloneCommand.runForTesting(
                args,
                PrintStream(out),
                PrintStream(err),
                installShutdownHook = false,
            ) { context, server -> ManagedAlcyoneHttpServer.createManaged(context, server) }
        return Result(code, out.toString(), err.toString())
    }
}
