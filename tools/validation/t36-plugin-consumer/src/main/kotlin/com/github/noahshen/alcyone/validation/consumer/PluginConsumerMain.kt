package com.github.noahshen.alcyone.validation.consumer

import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import com.github.noahshen.alcyone.context.vfs.gitbackup.GitBackupConfig
import com.github.noahshen.alcyone.context.vfs.gitbackup.GitBackupPlugin
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit

fun main() = runBlocking {
    val temp = Files.createTempDirectory("t36-plugin-consumer")
    var vfs: AlcyoneVfs? = null
    var backup: GitBackupPlugin? = null
    var backup2: GitBackupPlugin? = null
    try {
        val workspace = Files.createDirectory(temp.resolve("workspace"))
        val stateDb = temp.resolve("state.db")
        val bare = Files.createDirectory(temp.resolve("remote.git"))
        fun runGit(workDir: Path?, args: List<String>): Pair<Int, String> {
            val outFile = Files.createTempFile(temp, "git-out-", ".txt")
            var process: Process? = null
            try {
                val pb = if (workDir == null) ProcessBuilder(listOf("git") + args) else ProcessBuilder(listOf("git") + args).directory(workDir.toFile())
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
                process?.destroyForcibly()
                process?.waitFor(5, TimeUnit.SECONDS)
                Files.deleteIfExists(outFile)
            }
        }
        fun git(vararg args: String): String {
            val (code, out) = runGit(workspace, args.toList())
            check(code == 0) { "git ${args.joinToString(" ")} failed: $out" }
            return out
        }
        fun gitBare(vararg args: String): String {
            val (code, out) = runGit(null, listOf("--git-dir", bare.toString()) + args.toList())
            check(code == 0) { "git bare ${args.joinToString(" ")} failed: $out" }
            return out
        }
        val (ic, io) = runGit(null, listOf("init", "--bare", "-b", "main", bare.toString())); check(ic == 0) { io }
        git("init", "-b", "main")
        git("config", "user.name", "T36")
        git("config", "user.email", "t36@example.com")
        git("remote", "add", "origin", bare.toString())
        git("commit", "--allow-empty", "-m", "baseline")
        git("push", "-u", "origin", "main")

        vfs = AlcyoneVfs.create(
            VfsRuntimeConfig(
                stateDatabase = stateDb,
                namespaces = setOf("resources"),
                mounts = listOf(MountConfig(com.github.noahshen.alcyone.context.vfs.VfsPath.parse("/resources"), "resources", workspace)),
            ),
        )
        val backupConfig = GitBackupConfig(
            repositoryRoot = workspace,
            eventRoots = setOf(VfsUri.parse("alcyone://resources")),
            branch = "main",
            remoteName = "origin",
            quietPeriod = java.time.Duration.ofMillis(200),
        )
        backup = GitBackupPlugin(backupConfig)
        vfs!!.subscribe(backup!!.eventConsumer)
        backup!!.start()

        fun awaitBare(path: String, expected: String?) {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val (refCode, refOut) = runGit(null, listOf("--git-dir", bare.toString(), "rev-parse", "--verify", "refs/heads/main"))
                if (refCode == -1) throw IllegalStateException("git rev-parse timed out: $refOut")
                if (refCode != 0) { Thread.sleep(100); continue }
                val sha = refOut.trim()
                val (lsCode, lsOut) = runGit(null, listOf("--git-dir", bare.toString(), "ls-tree", "-r", "--name-only", sha))
                if (lsCode != 0) throw IllegalStateException("git ls-tree failed: $lsOut")
                val present = lsOut.lines().map { it.trim() }.filter { it.isNotEmpty() }.contains(path)
                if (!present) {
                    if (expected == null) return
                    Thread.sleep(100); continue
                }
                val (code, out) = runGit(null, listOf("--git-dir", bare.toString(), "show", "$sha:$path"))
                if (code == -1) throw IllegalStateException("git show timed out: $out")
                if (code != 0) throw IllegalStateException("git show failed: $out")
                if (out == expected) return
                Thread.sleep(100)
            }
            val (refCode, refOut) = runGit(null, listOf("--git-dir", bare.toString(), "rev-parse", "--verify", "refs/heads/main"))
            if (refCode != 0) throw IllegalStateException("git rev-parse failed: $refOut")
            val sha = refOut.trim()
            val (lsCode, lsOut) = runGit(null, listOf("--git-dir", bare.toString(), "ls-tree", "-r", "--name-only", sha))
            if (lsCode != 0) throw IllegalStateException("git ls-tree failed: $lsOut")
            val present = lsOut.lines().map { it.trim() }.filter { it.isNotEmpty() }.contains(path)
            val actual = if (present) {
                val (code, out) = runGit(null, listOf("--git-dir", bare.toString(), "show", "$sha:$path"))
                if (code != 0) throw IllegalStateException("git show failed: $out")
                out
            } else null
            check(actual == expected) { "expected $path=$expected but was $actual" }
        }

        vfs!!.write(VfsUri.parse("alcyone://resources/a.txt"), "hello".toByteArray())
        awaitBare("a.txt", "hello")

        vfs!!.write(VfsUri.parse("alcyone://resources/a.txt"), "hello2".toByteArray())
        awaitBare("a.txt", "hello2")

        vfs!!.delete(VfsUri.parse("alcyone://resources/a.txt"))
        awaitBare("a.txt", null)

        vfs!!.write(VfsUri.parse("alcyone://resources/b.txt"), "world".toByteArray())
        vfs!!.move(VfsUri.parse("alcyone://resources/b.txt"), VfsUri.parse("alcyone://resources/c.txt"))
        awaitBare("c.txt", "world")
        awaitBare("b.txt", null)

        // physical external change via manual trigger
        Files.writeString(workspace.resolve("external.txt"), "external")
        backup!!.requestSync()
        awaitBare("external.txt", "external")

        backup!!.close()
        vfs!!.write(VfsUri.parse("alcyone://resources/d.txt"), "afterclose".toByteArray())
        Thread.sleep(300)
        check(vfs.read(VfsUri.parse("alcyone://resources/d.txt")).toString(Charsets.UTF_8) == "afterclose")

        backup2 = GitBackupPlugin(backupConfig)
        vfs!!.subscribe(backup2!!.eventConsumer)
        backup2!!.start()
        awaitBare("d.txt", "afterclose")
        backup2!!.close()

        vfs.close()
        println("T36 plugin consumer: all assertions passed")
    } finally {
        val failures = mutableListOf<Throwable>()
        try { backup2?.close() } catch (e: Throwable) { failures.add(e) }
        try { backup?.close() } catch (e: Throwable) { failures.add(e) }
        try { vfs?.close() } catch (e: Throwable) { failures.add(e) }
        try {
            Files.walk(temp).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } catch (e: Throwable) { failures.add(e) }
        if (failures.isNotEmpty()) throw failures.first()
    }
}
