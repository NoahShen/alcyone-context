package com.github.noahshen.alcyone.context.memory.server

import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(120)
class StandaloneProcessTest {
    private val executable: Path by lazy {
        Path.of("build/install/alcyone-context/bin/alcyone-context").toAbsolutePath().also {
            assertTrue(Files.isExecutable(it), "run :context:server:installDist first")
        }
    }

    private val processClasspath: String by lazy {
        Files.readString(Path.of("build/standalone-process-test.classpath"))
    }

    @Test
    fun `real installDist strict init upgrade and run failures never expose secrets or create databases`() {
        val directory = createTempDirectory("ct29 process with spaces ")
        val yaml = writeYaml(directory.resolve("config with spaces.yaml"))
        assertEquals(2, command(directory).exitCode())
        assertEquals(0, command(directory, "init", "--config", yaml.toString()).exitCode())
        val backups = Files.createDirectory(directory.resolve("backup dir with spaces"))
        val upgraded = command(directory, "upgrade", "--config", yaml.toString(), "--backup-dir", backups.toString())
        assertEquals(0, upgraded.exitCode())
        assertTrue(upgraded.stdout.startsWith("NO_OP schema_version=1 backup_id=context-v1-"))
        assertEquals(1, Files.list(backups).use { it.count() })
        assertFalse(Files.exists(directory.resolve("data/vfs.sqlite")))

        val missing =
            command(directory.resolve("missing run"), "run", "--config", writeYaml(directory.resolve("missing run/c.yaml")).toString())
        assertEquals(4, missing.exitCode())
        assertFalse(Files.exists(directory.resolve("missing run/data/context.sqlite")))
        assertFalse(Files.exists(directory.resolve("missing run/data/vfs.sqlite")))

        val secret = "process-secret-never-print"
        val bad = directory.resolve("bad config.yaml")
        Files.writeString(bad, "server:\n  auth:\n    readKey: $secret\n    unknown: value\n")
        val invalid = command(directory, "run", "--config", bad.toString())
        assertEquals(2, invalid.exitCode())
        assertEquals("CONFIG_INVALID\n", invalid.stderr)
        assertFalse(secret in invalid.stdout + invalid.stderr)
        assertFalse("Exception" in invalid.stdout + invalid.stderr)
    }

    @Test
    fun `real occupied port exits four and both databases reopen`() {
        val directory = createTempDirectory("ct29 occupied port ")
        val port = freePort()
        val yaml = writeYaml(directory.resolve("config.yaml"), port)
        assertEquals(0, command(directory, "init", "--config", yaml.toString()).exitCode())
        ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1")).use {
            val failed = command(directory, "run", "--config", yaml.toString())
            assertEquals(4, failed.exitCode())
            assertEquals("RUN_FAILED\n", failed.stderr)
        }
        assertDatabaseReopens(directory)
        assertTrue(Files.exists(directory.resolve("data/vfs.sqlite")))
    }

    @Test
    fun `real SIGTERM drains resource response and exits zero with ready read admin and permissions`() {
        productionSignalCase("TERM")
    }

    @Test
    fun `real SIGINT drains resource response and exits zero`() {
        productionSignalCase("INT")
    }

    @Test
    fun `real close failure drains the response and TERM exits four`() {
        closeFailureSignalCase("TERM")
    }

    @Test
    fun `real close failure drains the response and INT exits four`() {
        closeFailureSignalCase("INT")
    }

    private fun closeFailureSignalCase(signal: String) {
        val directory = createTempDirectory("ct29 controlled close $signal ")
        val port = freePort()
        val yaml = writeYaml(directory.resolve("config.yaml"), port)
        assertEquals(0, command(directory, "init", "--config", yaml.toString()).exitCode())
        prepareResource(yaml)
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dslf4j.internal.verbosity=ERROR",
                "-cp",
                processClasspath,
                "com.github.noahshen.alcyone.context.memory.server.StandaloneCloseFailureProbe",
                yaml.toString(),
            ).directory(directory.toFile())
        val running = RunningProcess(process, directory.resolve("controlled-close-$signal.log"))
        try {
            awaitLive(port, running)
            runBlocking {
                io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO).use { client ->
                    client
                        .prepareGet("http://127.0.0.1:$port/v1/resources/content?uri=alcyone://resources/slow.bin") {
                            headers.append("Authorization", "Bearer $READ")
                        }.execute { response ->
                            assertEquals(200, response.status.value)
                            running.signal(signal)
                            val bytes = ByteArray(4 * 1024 * 1024)
                            val expected = ByteArray(bytes.size) { (it % 251).toByte() }
                            val channel = response.bodyAsChannel()
                            var offset = 0
                            while (offset < bytes.size) {
                                val read = channel.readAvailable(bytes, offset, minOf(1024, bytes.size - offset))
                                if (read < 0) break
                                offset += read
                                delay(1)
                            }
                            assertEquals(bytes.size, offset)
                            assertEquals(expected.toList(), bytes.toList())
                        }
                }
            }
            assertEquals(4, running.await())
            assertEquals("RUN_FAILED\n", running.stderr())
            assertFalse("controlled-close-failure" in running.stderr())
        } finally {
            running.destroy()
        }
    }

    private fun productionSignalCase(signal: String) {
        val directory = createTempDirectory("ct29 production $signal ")
        val port = freePort()
        val yaml = writeYaml(directory.resolve("config with spaces.yaml"), port)
        assertEquals(0, command(directory, "init", "--config", yaml.toString()).exitCode())
        prepareResource(yaml)
        val process = commandBuilder(directory, "run", "--config", yaml.toString())
        val running = RunningProcess(process, directory.resolve("production-$signal.log"))
        try {
            awaitReady(port, running)
            assertEquals(401, request(port, "GET", "/v1/context/bootstrap", null).statusCode())
            assertEquals(401, request(port, "GET", "/v1/context/bootstrap", "wrong").statusCode())
            assertEquals(200, request(port, "GET", "/v1/context/bootstrap", READ).statusCode())
            assertEquals(401, request(port, "GET", "/v1/admin/status", null).statusCode())
            assertEquals(403, request(port, "GET", "/v1/admin/status", READ).statusCode())
            assertEquals(200, request(port, "GET", "/v1/admin/status", ADMIN).statusCode())

            runBlocking {
                io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO).use { client ->
                    client
                        .prepareGet("http://127.0.0.1:$port/v1/resources/content?uri=alcyone://resources/slow.bin") {
                            headers.append("Authorization", "Bearer $READ")
                        }.execute { response ->
                            assertEquals(200, response.status.value)
                            val channel = response.bodyAsChannel()
                            val bytes = ByteArray(4 * 1024 * 1024)
                            val expected = ByteArray(bytes.size) { (it % 251).toByte() }
                            var offset = 0
                            while (offset < 1024) {
                                val read = channel.readAvailable(bytes, offset, 1024 - offset)
                                assertTrue(read > 0, "response closed before the first chunk")
                                offset += read
                            }
                            running.signal(signal)
                            while (offset < bytes.size) {
                                val read = channel.readAvailable(bytes, offset, minOf(1024, bytes.size - offset))
                                if (read < 0) break
                                offset += read
                                delay(1)
                            }
                            assertEquals(bytes.size, offset)
                            assertEquals(expected.toList(), bytes.copyOf(offset).toList())
                        }
                }
            }
            assertEquals(0, running.await(), "signal=$signal")
            assertDatabaseReopens(directory)
        } finally {
            running.destroy()
        }
    }

    private fun prepareResource(yaml: Path) {
        val fixture =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                processClasspath,
                "com.github.noahshen.alcyone.context.memory.server.StandaloneResourceFixture",
                yaml.toString(),
            ).directory(yaml.parent.toFile()).redirectErrorStream(true).start()
        assertTrue(fixture.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, fixture.exitValue(), fixture.inputStream.bufferedReader().readText())
    }

    private fun awaitLive(
        port: Int,
        running: RunningProcess,
    ) {
        repeat(200) {
            if (!running.isAlive()) error("Standalone exited before live: ${running.stderr()}")
            if (runCatching { request(port, "GET", "/health/live", null).statusCode() == 200 }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        error("Standalone did not become live on port $port: ${running.stderr()}")
    }

    private fun awaitReady(
        port: Int,
        running: RunningProcess,
    ) {
        repeat(200) {
            if (!running.isAlive()) error("Standalone exited before ready: ${running.stderr()}")
            if (runCatching { request(port, "GET", "/health/ready", null).statusCode() == 200 }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        error("Standalone did not become ready on port $port: ${running.stderr()}")
    }

    private fun assertStableError(
        stderr: String,
        expected: String,
    ) {
        assertEquals(expected, stderr.lineSequence().last { it.isNotBlank() })
        assertFalse("Exception" in stderr)
    }

    private fun request(
        port: Int,
        method: String,
        path: String,
        key: String?,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).method(method, HttpRequest.BodyPublishers.noBody())
        if (key != null) builder.header("Authorization", "Bearer $key")
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun assertDatabaseReopens(
        directory: Path,
        contextOnly: Boolean = false,
    ) {
        for (name in if (contextOnly) listOf("context.sqlite") else listOf("context.sqlite", "vfs.sqlite")) {
            DriverManager.getConnection("jdbc:sqlite:${directory.resolve("data/$name").toUri()}?mode=ro").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA quick_check").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("ok", rows.getString(1))
                        assertFalse(rows.next())
                    }
                }
            }
        }
    }

    private fun writeYaml(
        path: Path,
        port: Int = freePort(),
    ): Path {
        Files.createDirectories(path.parent)
        Files.createDirectories(path.parent.resolve("storage"))
        Files.writeString(
            path,
            "context:\n  database:\n    path: ${path.parent.resolve("data/context.sqlite")}\n" +
                "vfs:\n  database:\n    path: ${path.parent.resolve("data/vfs.sqlite")}\n  namespaces: [resources]\n" +
                "  mounts:\n    - vfsPath: /resources\n      storageKey: process-local\n" +
                "      backend: { type: local, root: ${path.parent.resolve("storage")} }\n" +
                "server:\n  port: $port\n  auth:\n    readKey: $READ\n    adminKey: $ADMIN\n",
        )
        return path
    }

    private fun freePortFrom(yaml: Path): Int =
        Files
            .readString(yaml)
            .lineSequence()
            .first {
                it.trim().startsWith("port:")
            }.substringAfter(':')
            .trim()
            .toInt()

    private fun command(
        directory: Path,
        vararg args: String,
    ): Completed {
        Files.createDirectories(directory)
        val running =
            RunningProcess(commandBuilder(directory, *args), directory.resolve("command-${directory.fileName}-${System.nanoTime()}.log"))
        val completed = running.await(60)
        return Completed(completed, running.stdout(), running.stderr())
    }

    private fun commandBuilder(
        directory: Path,
        vararg args: String,
    ): ProcessBuilder = ProcessBuilder(listOf(executable.toString()) + args).directory(directory.toFile())

    private data class Completed(
        val code: Int,
        val stdout: String,
        val stderr: String,
    ) {
        fun exitCode(): Int {
            assertFalse("at io.ktor" in stdout + stderr)
            assertFalse("BindException" in stdout + stderr)
            assertFalse("ContextConfigException" in stdout + stderr)
            return code
        }
    }

    private class RunningProcess(
        processBuilder: ProcessBuilder,
        log: Path,
    ) {
        private val logPath = log
        private val stdoutPath = log.resolveSibling(log.fileName.toString() + ".stdout")
        private val stderrPath = log.resolveSibling(log.fileName.toString() + ".stderr")
        private val process =
            processBuilder
                .redirectOutput(stdoutPath.toFile())
                .redirectError(stderrPath.toFile())
                .start()

        fun signal(signal: String) {
            val sender = ProcessBuilder("kill", "-$signal", process.pid().toString()).start()
            assertEquals(0, sender.waitFor())
            Files.writeString(
                logPath.resolveSibling(logPath.fileName.toString() + ".signals"),
                "pid=${process.pid()} signal=$signal\n",
                Charsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        }

        fun await(): Int = await(90)

        fun await(seconds: Long): Int {
            assertTrue(process.waitFor(seconds, TimeUnit.SECONDS), "process did not exit after signal")
            return process.exitValue()
        }

        fun isAlive(): Boolean = process.isAlive

        fun stdout(): String = Files.readString(stdoutPath)

        fun stderr(): String = Files.readString(stderrPath)

        fun destroy() {
            process.descendants().forEach { runCatching { it.destroyForcibly() } }
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private companion object {
        const val READ = "process-read-key"
        const val ADMIN = "process-admin-key"

        fun freePort(): Int = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }
}
