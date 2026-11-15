package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.persistence.sqldelight.ContextOfflineBackup
import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object StandaloneCommand {
    private val termination = CompletableDeferred<Unit>()
    private val signalInitiated = AtomicBoolean(false)
    private var terminationHook: Thread? = null
    private var hookInstalled = false

    /** B1 deterministic lifecycle seam; no shutdown hook is installed for injected termination. */
    internal fun requestTerminationForTesting() {
        termination.complete(Unit)
    }

    @Synchronized
    private fun installTerminationHook(
        server: ManagedAlcyoneHttpServer,
        stderr: java.io.PrintStream,
    ) {
        if (!hookInstalled) {
            terminationHook =
                Thread {
                    signalInitiated.set(true)
                    termination.complete(Unit)
                    val code =
                        try {
                            runBlocking { server.closeAsync() }
                            0
                        } catch (_: Throwable) {
                            stderr.println("RUN_FAILED")
                            4
                        }
                    Runtime.getRuntime().halt(code)
                }
            Runtime.getRuntime().addShutdownHook(terminationHook)
            hookInstalled = true
        }
    }

    internal fun runForTesting(
        args: Array<String>,
        stdout: java.io.PrintStream,
        stderr: java.io.PrintStream,
        termination: Deferred<Unit> = this.termination,
        installShutdownHook: Boolean = false,
        managedFactory: (ContextConfig, ServerConfig) -> ManagedAlcyoneHttpServer =
            { context, server -> ManagedAlcyoneHttpServer.createManaged(context, server) },
    ): Int = execute(args, stdout, stderr, termination, managedFactory, installShutdownHook)

    fun run(
        args: Array<String>,
        stdout: java.io.PrintStream,
        stderr: java.io.PrintStream,
    ): Int =
        execute(args, stdout, stderr, termination, installShutdownHook = true, managedFactory = { context, server ->
            ManagedAlcyoneHttpServer.createManaged(context, server)
        })

    private fun execute(
        args: Array<String>,
        stdout: java.io.PrintStream,
        stderr: java.io.PrintStream,
        termination: Deferred<Unit>,
        managedFactory: (ContextConfig, ServerConfig) -> ManagedAlcyoneHttpServer,
        installShutdownHook: Boolean,
    ): Int {
        val parsed = parse(args)
        if (parsed == null) return error(stderr, 2, "USAGE_ERROR")
        return try {
            val context =
                com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader
                    .load(parsed.yaml)
            ServerConfigLoader.load(parsed.yaml)
            when (parsed.command) {
                Command.INIT -> {
                    runBlocking { AlcyoneContextFactory.initializeDatabase(context) }
                    stdout.println("INITIALIZED")
                    0
                }
                Command.UPGRADE -> {
                    val directory = parsed.backupDir!!
                    if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw IllegalStateException("BACKUP_DIR")
                    val target = directory.resolve("context-v1-${UUID.randomUUID()}.sqlite")
                    val result = ContextOfflineBackup.snapshot(context.databasePath, target)
                    stdout.println("NO_OP schema_version=${result.schemaVersion} backup_id=${target.fileName}")
                    0
                }
                Command.RUN -> {
                    val server = managedFactory(context, ServerConfigLoader.load(parsed.yaml))
                    runBlocking {
                        try {
                            if (installShutdownHook) installTerminationHook(server, stderr)
                            server.start()
                            termination.await()
                            server.closeAsync()
                        } catch (failure: Throwable) {
                            runCatching { server.closeAsync() }
                            throw failure
                        }
                    }
                    0
                }
            }
        } catch (failure: Throwable) {
            when (failure) {
                is CancellationException, is VirtualMachineError, is ThreadDeath, is LinkageError -> throw failure
                is ContextConfigException -> error(stderr, 2, "CONFIG_INVALID")
                else ->
                    if (parsed.command == Command.RUN && signalInitiated.get()) {
                        4
                    } else {
                        if (parsed.command == Command.RUN) error(stderr, 4, "RUN_FAILED") else error(stderr, 3, "${parsed.command}_FAILED")
                    }
            }
        }
    }

    private fun error(
        stderr: java.io.PrintStream,
        code: Int,
        message: String,
    ): Int {
        stderr.println(message)
        return code
    }

    private enum class Command { INIT, UPGRADE, RUN }

    private data class Parsed(
        val command: Command,
        val yaml: Path,
        val backupDir: Path?,
    )

    private fun parse(args: Array<String>): Parsed? {
        if (args.isEmpty()) return null
        val command =
            when (args[0]) {
                "init" -> Command.INIT
                "upgrade" -> Command.UPGRADE
                "run" -> Command.RUN
                else -> return null
            }
        val options = args.drop(1)
        if (options.size % 2 != 0) return null
        val values = mutableMapOf<String, Path>()
        for (index in options.indices step 2) {
            val key = options[index]
            val value = options[index + 1]
            if ((key != "--config" && key != "--backup-dir") ||
                value.startsWith("--") ||
                values.put(key, Path.of(value)) != null
            ) {
                return null
            }
        }
        val yaml = values["--config"] ?: return null
        val backup = values["--backup-dir"]
        if (values.keys != (if (command == Command.UPGRADE) setOf("--config", "--backup-dir") else setOf("--config"))) return null
        return Parsed(command, yaml, backup)
    }
}
