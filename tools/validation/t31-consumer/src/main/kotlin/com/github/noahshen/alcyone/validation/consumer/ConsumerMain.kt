package com.github.noahshen.alcyone.validation.consumer

import com.github.noahshen.alcyone.context.vfs.NodeMetadata
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.WriteMode
import com.github.noahshen.alcyone.context.vfs.WriteOptions
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountBackend
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.security.MessageDigest

fun main() = runBlocking {
    println("[t31-consumer] Starting independent JVM consumer validation...")

    // 1. 独立临时目录准备
    val tempDir = Files.createTempDirectory("t31-consumer-test-")
    try {
        val storageRoot = Files.createDirectory(tempDir.resolve("storage"))
        val stateDbPath = tempDir.resolve("state.db")

        val config = VfsRuntimeConfig(
            stateDatabase = stateDbPath,
            namespaces = setOf("workspace"),
            mounts = listOf(
                MountConfig(
                    vfsPath = VfsPath.parse("/workspace/files"),
                    storageKey = "local-storage",
                    backend = MountBackend.LocalFs(storageRoot),
                ),
            ),
        )

        val persistentFileUri = VfsUri.parse("alcyone://workspace/files/reopen-target.txt")
        val ephemeralFileUri = VfsUri.parse("alcyone://workspace/files/ephemeral.txt")
        val movedFileUri = VfsUri.parse("alcyone://workspace/files/moved-target.txt")

        val persistentExpectedContent = "persistent data across reopen 2026".toByteArray(Charsets.UTF_8)
        val persistentMetadata = NodeMetadata(setOf("doc", "persistent"), "Reopen Retention Note")
        var persistentNodeId: com.github.noahshen.alcyone.context.vfs.NodeId? = null

        val ephemeralExpectedContent = "ephemeral initial content".toByteArray(Charsets.UTF_8)
        val expectedEphemeralHash = MessageDigest.getInstance("SHA-256").digest(ephemeralExpectedContent)

        // --- PHASE 1: 首次启动与生命周期完整覆盖 ---
        println("[t31-consumer] Phase 1: create Runtime and walk full lifecycle...")
        val vfs1 = AlcyoneVfs.create(config)
        try {
            // 2. subscribe 订阅事件
            val writeDeferred = CompletableDeferred<VfsEvent>()
            val moveDeferred = CompletableDeferred<VfsEvent>()
            vfs1.subscribe { event ->
                if (event.uri == ephemeralFileUri && event.type == VfsEventType.FILE_CREATED) {
                    writeDeferred.complete(event)
                }
                if (event.type == VfsEventType.FILE_MOVED) {
                    moveDeferred.complete(event)
                }
            }

            // 3. write (CREATE_NEW) 准备持久节点
            val persistentRecord = vfs1.write(
                uri = persistentFileUri,
                content = persistentExpectedContent,
                options = WriteOptions(mode = WriteMode.CREATE_NEW),
            )
            persistentNodeId = persistentRecord.id
            vfs1.setMetadata(persistentRecord.id, persistentMetadata)
            println("[t31-consumer] Created persistent node: id=${persistentRecord.id}")

            // write (CREATE_NEW) 临时节点
            val ephemeralRecord = vfs1.write(
                uri = ephemeralFileUri,
                content = ephemeralExpectedContent,
                options = WriteOptions(mode = WriteMode.CREATE_NEW),
            )
            val writtenEvent = withTimeout(10_000) { writeDeferred.await() }
            check(writtenEvent.nodeId == ephemeralRecord.id) {
                "Event nodeId mismatch: expected ${ephemeralRecord.id}, got ${writtenEvent.nodeId}"
            }

            // 4. read 全量读回
            val ephemeralRead = vfs1.read(ephemeralFileUri)
            check(ephemeralRead.toString(Charsets.UTF_8) == "ephemeral initial content") {
                "Read content mismatch for ephemeral file"
            }

            // 5. stat / list 状态与目录列表
            val ephemeralStat = vfs1.stat(ephemeralFileUri)
            check(ephemeralStat.id == ephemeralRecord.id) { "Stat nodeId mismatch" }

            val listResults = vfs1.list(VfsUri.parse("alcyone://workspace/files"))
            val listedPaths = listResults.map { it.uri.path.toString() }.toSet()
            check("/workspace/files/reopen-target.txt" in listedPaths) { "reopen-target.txt missing in list" }
            check("/workspace/files/ephemeral.txt" in listedPaths) { "ephemeral.txt missing in list" }

            // 6. setMetadata / getMetadata
            val ephemeralMeta = NodeMetadata(setOf("temp", "alpha"), "Ephemeral Description")
            vfs1.setMetadata(ephemeralRecord.id, ephemeralMeta)
            val fetchedMeta = vfs1.getMetadata(ephemeralRecord.id)
            check(fetchedMeta.tags == ephemeralMeta.tags && fetchedMeta.description == ephemeralMeta.description) {
                "Metadata mismatch after setMetadata"
            }

            // 7. openStream 分块读取并完整关闭
            val streamUri = ephemeralFileUri
            val digest = MessageDigest.getInstance("SHA-256")
            var totalStreamed = 0L
            vfs1.openStream(streamUri).use { streamResult ->
                val buffer = ByteArray(4)
                while (true) {
                    val readBytes = streamResult.stream.read(buffer)
                    if (readBytes < 0) break
                    digest.update(buffer, 0, readBytes)
                    totalStreamed += readBytes
                }
            }
            check(totalStreamed == ephemeralExpectedContent.size.toLong()) {
                "Streamed total bytes mismatch"
            }
            val actualStreamHash = digest.digest()
            check(actualStreamHash.contentEquals(expectedEphemeralHash)) {
                "Streamed SHA-256 mismatch against expected content hash"
            }

            // 8. move (验证 Node ID、Metadata 保持与事件目标)
            val moveResult = vfs1.move(source = ephemeralFileUri, target = movedFileUri)
            check(moveResult.id == ephemeralRecord.id) { "Move did not preserve Node ID" }
            val movedStat = vfs1.stat(movedFileUri)
            check(movedStat.id == ephemeralRecord.id) { "Moved stat ID mismatch" }
            val movedMeta = vfs1.getMetadata(ephemeralRecord.id)
            check(movedMeta.tags == ephemeralMeta.tags && movedMeta.description == ephemeralMeta.description) {
                "Metadata lost after move"
            }

            val movedEvent = withTimeout(10_000) { moveDeferred.await() }
            check(movedEvent.nodeId == ephemeralRecord.id) { "Move event Node ID mismatch" }
            check(movedEvent.uri == movedFileUri) {
                "Move event target URI mismatch: expected $movedFileUri, got ${movedEvent.uri}"
            }

            // 9. delete
            vfs1.delete(movedFileUri)
            var deletedNotFound = false
            try {
                vfs1.stat(movedFileUri)
            } catch (e: VfsException) {
                if (e.code == VfsErrorCode.NOT_FOUND) {
                    deletedNotFound = true
                }
            }
            check(deletedNotFound) { "File should throw NOT_FOUND after delete" }
            println("[t31-consumer] Phase 1 operations passed successfully.")
        } finally {
            // finally close
            vfs1.close()
            println("[t31-consumer] Phase 1: Runtime closed.")
        }

        // --- PHASE 2: 重新 open，验证保留节点的持久一致性 ---
        println("[t31-consumer] Phase 2: reopen Runtime with same config and verify retention...")
        val vfs2 = AlcyoneVfs.create(config)
        try {
            val reopenStat = vfs2.stat(persistentFileUri)
            check(reopenStat.id == persistentNodeId) {
                "Node ID changed after reopen! expected=$persistentNodeId, actual=${reopenStat.id}"
            }

            val reopenMeta = vfs2.getMetadata(persistentNodeId!!)
            check(reopenMeta.tags == persistentMetadata.tags && reopenMeta.description == persistentMetadata.description) {
                "Metadata changed after reopen! expected=$persistentMetadata, actual=$reopenMeta"
            }

            val reopenBytes = vfs2.read(persistentFileUri)
            check(reopenBytes.contentEquals(persistentExpectedContent)) {
                "Content mismatch after reopen!"
            }
            println("[t31-consumer] Phase 2: Reopen assertions verified (Node ID, Metadata, Content all identical).")
        } finally {
            vfs2.close()
            println("[t31-consumer] Phase 2: Runtime closed.")
        }

        println("[t31-consumer] ALL VERIFICATION ASSERTIONS PASSED SUCCESSFULLY!")
    } finally {
        val deleted = tempDir.toFile().deleteRecursively()
        if (!deleted) {
            println("[t31-consumer] WARNING: Temporary directory clean-up failed for: $tempDir")
        }
    }
}
