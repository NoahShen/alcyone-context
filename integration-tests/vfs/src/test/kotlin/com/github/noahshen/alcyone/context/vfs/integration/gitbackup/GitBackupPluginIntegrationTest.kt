package com.github.noahshen.alcyone.context.vfs.integration.gitbackup

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.gitbackup.GitBackupConfig
import com.github.noahshen.alcyone.context.vfs.gitbackup.GitBackupPlugin
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.TimeUnit

class GitBackupPluginIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private fun gitCommand(
        workDir: Path?,
        args: List<String>,
    ): Pair<Int, String> {
        val outFile = Files.createTempFile(tempDir, "git-out-", ".txt")
        var process: Process? = null
        try {
            val pb =
                if (workDir ==
                    null
                ) {
                    ProcessBuilder(listOf("git") + args)
                } else {
                    ProcessBuilder(listOf("git") + args).directory(workDir.toFile())
                }
            pb.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            pb.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            pb.redirectErrorStream(true)
            pb.redirectOutput(outFile.toFile())
            process = pb.start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
                throw IllegalStateException("git ${args.joinToString(" ")} timed out")
            }
            val code = process.exitValue()
            val out = Files.readString(outFile)
            return code to out
        } finally {
            var cleanupError: Throwable? = null
            try {
                process?.destroyForcibly()
                process?.waitFor(5, TimeUnit.SECONDS)
            } catch (e: Throwable) {
                cleanupError = e
            }
            try {
                Files.deleteIfExists(outFile)
            } catch (e: Throwable) {
                if (cleanupError != null) cleanupError.addSuppressed(e) else cleanupError = e
            }
            cleanupError?.let { throw it }
        }
    }

    private fun runGit(
        workDir: Path,
        vararg args: String,
    ): String {
        val (code, out) = gitCommand(workDir, args.toList())
        check(code == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    private fun bareShow(
        bare: Path,
        spec: String,
    ): Pair<Int, String> = gitCommand(null, listOf("--git-dir", bare.toString(), "show", spec))

    private fun bareHasRef(bare: Path): Boolean {
        val (code, _) = gitCommand(null, listOf("--git-dir", bare.toString(), "rev-parse", "--verify", "refs/heads/main"))
        return code == 0
    }

    private fun bareSnapshot(bare: Path): Pair<String, List<String>> {
        val (refCode, refOut) = gitCommand(null, listOf("--git-dir", bare.toString(), "rev-parse", "--verify", "refs/heads/main"))
        check(refCode == 0) { "invalid repository or unborn ref: $refOut" }
        val sha = refOut.trim()
        val (lsCode, lsOut) = gitCommand(null, listOf("--git-dir", bare.toString(), "ls-tree", "-r", "--name-only", sha))
        check(lsCode == 0) { "git ls-tree failed: $lsOut" }
        val paths = lsOut.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return sha to paths
    }

    private fun barePathContent(
        bare: Path,
        path: String,
        snapshot: Pair<String, List<String>>,
    ): String? {
        if (!snapshot.second.contains(path)) return null
        val (code, out) = gitCommand(null, listOf("--git-dir", bare.toString(), "show", "${snapshot.first}:$path"))
        check(code == 0) { "git show failed: $out" }
        return out
    }

    private fun bareFileContent(
        bare: Path,
        path: String,
    ): String? {
        val snapshot = bareSnapshot(bare)
        return barePathContent(bare, path, snapshot)
    }

    private fun awaitBare(
        bare: Path,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        check(predicate()) { "bare remote condition not met in time" }
    }

    @Test
    @Timeout(120)
    fun `plugin subscribes to vfs events and pushes to local bare remote`() =
        runBlocking {
            val workspace = Files.createDirectory(tempDir.resolve("workspace"))
            val stateDb = tempDir.resolve("state.db")
            val bare = Files.createDirectory(tempDir.resolve("remote.git"))
            val (initCode, initOut) = gitCommand(null, listOf("init", "--bare", "-b", "main", bare.toString()))
            check(initCode == 0) { "git init bare failed: $initOut" }

            runGit(workspace, "init", "-b", "main")
            runGit(workspace, "config", "user.name", "T36 Test")
            runGit(workspace, "config", "user.email", "t36@example.com")
            runGit(workspace, "remote", "add", "origin", bare.toString())
            runGit(workspace, "commit", "--allow-empty", "-m", "baseline")
            runGit(workspace, "push", "-u", "origin", "main")

            val config =
                VfsRuntimeConfig(
                    stateDatabase = stateDb,
                    namespaces = setOf("resources"),
                    mounts =
                        listOf(
                            MountConfig(
                                com.github.noahshen.alcyone.context.vfs.VfsPath
                                    .parse("/resources"),
                                "resources",
                                workspace,
                            ),
                        ),
                )
            var vfs: AlcyoneVfs? = null
            var backup: GitBackupPlugin? = null
            var backup2: GitBackupPlugin? = null
            try {
                vfs = AlcyoneVfs.create(config)
                val backupConfig =
                    GitBackupConfig(
                        repositoryRoot = workspace,
                        eventRoots = setOf(VfsUri.parse("alcyone://resources")),
                        branch = "main",
                        remoteName = "origin",
                        quietPeriod = Duration.ofMillis(200),
                    )
                backup = GitBackupPlugin(backupConfig)
                vfs!!.subscribe(backup!!.eventConsumer)
                backup!!.start()

                vfs!!.write(VfsUri.parse("alcyone://resources/a.txt"), "hello".toByteArray())
                awaitBare(bare) { bareFileContent(bare, "a.txt") == "hello" }

                vfs!!.write(VfsUri.parse("alcyone://resources/a.txt"), "hello2".toByteArray())
                awaitBare(bare) { bareFileContent(bare, "a.txt") == "hello2" }

                vfs!!.delete(VfsUri.parse("alcyone://resources/a.txt"))
                awaitBare(bare) { bareFileContent(bare, "a.txt") == null }

                vfs!!.write(VfsUri.parse("alcyone://resources/b.txt"), "world".toByteArray())
                vfs!!.move(VfsUri.parse("alcyone://resources/b.txt"), VfsUri.parse("alcyone://resources/c.txt"))
                awaitBare(bare) {
                    val snapshot = bareSnapshot(bare)
                    barePathContent(bare, "c.txt", snapshot) == "world" && barePathContent(bare, "b.txt", snapshot) == null
                }

                // read/metadata should not trigger git; verify no extra commit by HEAD stability
                val headBefore = gitCommand(null, listOf("--git-dir", bare.toString(), "rev-parse", "main")).second.trim()
                vfs!!.read(VfsUri.parse("alcyone://resources/c.txt"))
                val cStat = vfs!!.stat(VfsUri.parse("alcyone://resources/c.txt"))
                vfs!!.setMetadata(cStat.id, NodeMetadata(setOf("t"), "desc"))
                vfs!!.getMetadata(cStat.id)
                Thread.sleep(300)
                val headAfter = gitCommand(null, listOf("--git-dir", bare.toString(), "rev-parse", "main")).second.trim()
                assertEquals(headBefore, headAfter)

                Files.writeString(workspace.resolve("external.txt"), "external")
                backup!!.requestSync()
                awaitBare(bare) { bareFileContent(bare, "external.txt") == "external" }

                val headBeforeClose = gitCommand(null, listOf("--git-dir", bare.toString(), "rev-parse", "main")).second.trim()
                backup!!.close()
                vfs!!.write(VfsUri.parse("alcyone://resources/d.txt"), "afterclose".toByteArray())
                Thread.sleep(300)
                val headAfterClose = gitCommand(null, listOf("--git-dir", bare.toString(), "rev-parse", "main")).second.trim()
                assertEquals(headBeforeClose, headAfterClose, "residual callback must not create commit")
                assertEquals("afterclose", vfs!!.read(VfsUri.parse("alcyone://resources/d.txt")).toString(Charsets.UTF_8))

                backup2 = GitBackupPlugin(backupConfig)
                vfs!!.subscribe(backup2!!.eventConsumer)
                backup2!!.start()
                awaitBare(bare) { bareFileContent(bare, "d.txt") == "afterclose" }
                backup2!!.close()
            } finally {
                val failures = mutableListOf<Throwable>()
                try {
                    backup2?.close()
                } catch (e: Throwable) {
                    failures.add(e)
                }
                try {
                    backup?.close()
                } catch (e: Throwable) {
                    failures.add(e)
                }
                try {
                    vfs?.close()
                } catch (e: Throwable) {
                    failures.add(e)
                }
                try {
                    Files.walk(tempDir).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                }
                if (failures.isNotEmpty()) throw failures.first()
            }
        }

    @Test
    fun `invalid repository query throws`() {
        val invalid = tempDir.resolve("not-a-repo")
        Files.createDirectories(invalid)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            bareFileContent(invalid, "a.txt")
        }
    }

    @Test
    @Timeout(120)
    fun `vfs write completes while git push is blocked`() =
        runBlocking {
            val workspace = Files.createDirectory(tempDir.resolve("workspace2"))
            val stateDb = tempDir.resolve("state2.db")
            val bare = Files.createDirectory(tempDir.resolve("remote2.git"))
            val (ic, io) = gitCommand(null, listOf("init", "--bare", "-b", "main", bare.toString()))
            check(ic == 0) { io }
            runGit(workspace, "init", "-b", "main")
            runGit(workspace, "config", "user.name", "T36 Test")
            runGit(workspace, "config", "user.email", "t36@example.com")
            runGit(workspace, "remote", "add", "origin", bare.toString())
            runGit(workspace, "commit", "--allow-empty", "-m", "baseline")
            runGit(workspace, "push", "-u", "origin", "main")

            val hook = workspace.resolve(".git/hooks/pre-push")
            Files.writeString(
                hook,
                "#!/bin/sh\ntouch \"${tempDir.resolve(
                    "entered",
                )}\"\nwhile [ ! -f \"${tempDir.resolve("release.lock")}\" ]; do sleep 0.1; done\n",
            )
            hook.toFile().setExecutable(true)

            val config =
                VfsRuntimeConfig(
                    stateDatabase = stateDb,
                    namespaces = setOf("resources"),
                    mounts =
                        listOf(
                            MountConfig(
                                com.github.noahshen.alcyone.context.vfs.VfsPath
                                    .parse("/resources"),
                                "resources",
                                workspace,
                            ),
                        ),
                )
            var backup: GitBackupPlugin? = null
            val vfs = AlcyoneVfs.create(config)
            try {
                val backupConfig =
                    GitBackupConfig(
                        repositoryRoot = workspace,
                        eventRoots = setOf(VfsUri.parse("alcyone://resources")),
                        branch = "main",
                        remoteName = "origin",
                        quietPeriod = Duration.ofMillis(100),
                    )
                backup = GitBackupPlugin(backupConfig)
                vfs.subscribe(backup!!.eventConsumer)
                backup!!.start()
                val enteredDeadline = System.currentTimeMillis() + 10000
                while (!Files.exists(tempDir.resolve("entered")) && System.currentTimeMillis() < enteredDeadline) {
                    Thread.sleep(50)
                }
                check(Files.exists(tempDir.resolve("entered"))) { "git hook did not start" }
                val start = System.currentTimeMillis()
                vfs.write(VfsUri.parse("alcyone://resources/blocked.txt"), "x".toByteArray())
                val elapsed = System.currentTimeMillis() - start
                assertTrue(elapsed < 1000, "VFS write should not wait for Git")

                Thread.sleep(500)
                val beforeRelease = bareFileContent(bare, "blocked.txt")
                assertTrue(beforeRelease == null, "blocked push must not update bare remote")

                Files.writeString(tempDir.resolve("release.lock"), "go")
                awaitBare(bare) { bareFileContent(bare, "blocked.txt") == "x" }
            } finally {
                try {
                    Files.writeString(tempDir.resolve("release.lock"), "go")
                } catch (e: Exception) {
                }
                try {
                    backup?.close()
                } catch (e: Exception) {
                }
                try {
                    vfs.close()
                } catch (e: Exception) {
                }
                hook.toFile().delete()
                try {
                    Files.deleteIfExists(tempDir.resolve("entered"))
                } catch (e: Exception) {
                }
                try {
                    Files.deleteIfExists(tempDir.resolve("release.lock"))
                } catch (e: Exception) {
                }
            }
        }
}
