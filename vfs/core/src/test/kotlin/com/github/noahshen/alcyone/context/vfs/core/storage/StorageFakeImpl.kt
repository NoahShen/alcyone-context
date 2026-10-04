package com.github.noahshen.alcyone.context.vfs.core.storage

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant

/**
 * 内存级 Storage 测试替身。
 *
 * 支持故障注入（[failures]）与调用记录（[calls]），用于演示「Storage 失败时不产生任何状态变更与事件」。
 * 这是替身，不是真实文件系统 I/O。
 */
class StorageFakeImpl(
    private var capabilities: StorageCapabilities = StorageCapabilities(),
) : Storage {
    /**
     * 故障注入开关。字段非空时，对应操作在执行任何副作用前直接抛出该异常。
     * [readStream] 优先用 [onReadStream]，为 null 时回退到 [onRead]。
     */
    class Failures {
        var onRead: VfsException? = null
        var onReadStream: VfsException? = null
        var onStat: VfsException? = null
        var onList: VfsException? = null
        var onWrite: VfsException? = null
        var onCreateDirectory: VfsException? = null
        var onMove: VfsException? = null
        var onDelete: VfsException? = null

        /**
         * 只在这个相对路径上让 [Storage.createDirectory] 失败；其他目录照常建。
         * 用来制造「建到一半失败」——首层成功、次层失败。
         */
        var onCreateDirectoryAt: Pair<String, VfsException>? = null
    }

    /** 故障注入开关。 */
    val failures = Failures()

    /** 调用记录，形如 `read:notes/a.txt`，供测试断言 Storage 先于事务被调用。 */
    val calls: MutableList<String> = mutableListOf()

    /** 一键让所有操作失败，便于演示「Storage 失败时事务根本没有开启」。 */
    fun failEverything(reason: String = "injected storage failure") {
        val failure = VfsException(VfsErrorCode.STORAGE_ERROR, reason)
        failures.onRead = failure
        failures.onReadStream = failure
        failures.onStat = failure
        failures.onList = failure
        failures.onWrite = failure
        failures.onCreateDirectory = failure
        failures.onMove = failure
        failures.onDelete = failure
    }

    private sealed interface Node {
        data class File(
            var bytes: ByteArray,
            var modifiedAt: Instant,
        ) : Node

        data class Directory(
            var modifiedAt: Instant,
        ) : Node
    }

    private val nodes = mutableMapOf<String, Node>()

    init {
        // 挂载根默认作为目录存在
        nodes[StoragePath.root.toRelativeString()] = Node.Directory(Instant.now())
    }

    fun setCapabilities(caps: StorageCapabilities) {
        this.capabilities = caps
    }

    override fun capabilities(): StorageCapabilities = capabilities

    override suspend fun stat(path: StoragePath): StorageAttributes {
        calls += "stat:${path.toRelativeString()}"
        failures.onStat?.let { throw it }
        val key = path.toRelativeString()
        val node = nodes[key] ?: throw VfsException(VfsErrorCode.NOT_FOUND, "Path not found: $key")
        return when (node) {
            is Node.File -> StorageAttributes(NodeType.FILE, node.bytes.size.toLong(), node.modifiedAt)
            is Node.Directory -> StorageAttributes(NodeType.DIRECTORY, null, node.modifiedAt)
        }
    }

    override suspend fun read(
        path: StoragePath,
        maxBytes: Long,
    ): StorageContent {
        calls += "read:${path.toRelativeString()}"
        failures.onRead?.let { throw it }
        val key = path.toRelativeString()
        val node = nodes[key] ?: throw VfsException(VfsErrorCode.NOT_FOUND, "Path not found: $key")
        if (node is Node.Directory) {
            throw VfsException(VfsErrorCode.TYPE_MISMATCH, "Target is a directory: $key")
        }
        val file = node as Node.File
        if (file.bytes.size.toLong() > maxBytes) {
            throw VfsException(VfsErrorCode.LIMIT_EXCEEDED, "File size ${file.bytes.size} exceeds limit $maxBytes")
        }
        return StorageContent(
            bytes = file.bytes.copyOf(),
            attributes = StorageAttributes(NodeType.FILE, file.bytes.size.toLong(), file.modifiedAt),
        )
    }

    override suspend fun readStream(
        path: StoragePath,
        maxBytes: Long?,
    ): StorageStream {
        calls += "readStream:${path.toRelativeString()}"
        (failures.onReadStream ?: failures.onRead)?.let { throw it }
        val key = path.toRelativeString()
        val node = nodes[key] ?: throw VfsException(VfsErrorCode.NOT_FOUND, "Path not found: $key")
        if (node is Node.Directory) {
            throw VfsException(VfsErrorCode.TYPE_MISMATCH, "Target is a directory: $key")
        }
        val file = node as Node.File
        if (maxBytes != null && file.bytes.size.toLong() > maxBytes) {
            throw VfsException(VfsErrorCode.LIMIT_EXCEEDED, "File size ${file.bytes.size} exceeds limit $maxBytes")
        }

        val attrs = StorageAttributes(NodeType.FILE, file.bytes.size.toLong(), file.modifiedAt)
        return object : StorageStream {
            private var closed = false

            override val attributes: StorageAttributes get() = attrs

            override fun openStream(): InputStream {
                if (closed) throw IllegalStateException("Stream is closed")
                val stream = ByteArrayInputStream(file.bytes.copyOf())
                return if (maxBytes != null) {
                    object : InputStream() {
                        private var count = 0L

                        override fun read(): Int {
                            val b = stream.read()
                            if (b != -1) {
                                count++
                                if (count > maxBytes) {
                                    throw VfsException(VfsErrorCode.LIMIT_EXCEEDED, "Stream exceeded limit $maxBytes")
                                }
                            }
                            return b
                        }

                        override fun close() {
                            stream.close()
                        }
                    }
                } else {
                    stream
                }
            }

            override fun close() {
                closed = true
            }
        }
    }

    override suspend fun list(path: StoragePath): List<StorageEntry> {
        calls += "list:${path.toRelativeString()}"
        failures.onList?.let { throw it }
        val key = path.toRelativeString()
        val node = nodes[key] ?: throw VfsException(VfsErrorCode.NOT_FOUND, "Directory not found: $key")
        if (node !is Node.Directory) {
            throw VfsException(VfsErrorCode.TYPE_MISMATCH, "Target is not a directory: $key")
        }

        val prefixSegments = path.segments
        val entries = mutableListOf<StorageEntry>()

        for ((k, n) in nodes) {
            if (k == key) continue
            val entryPath = StoragePath.parse(k)
            if (entryPath.segments.size == prefixSegments.size + 1 &&
                entryPath.segments.subList(0, prefixSegments.size) == prefixSegments
            ) {
                val name = entryPath.name
                val attrs =
                    when (n) {
                        is Node.File -> StorageAttributes(NodeType.FILE, n.bytes.size.toLong(), n.modifiedAt)
                        is Node.Directory -> StorageAttributes(NodeType.DIRECTORY, null, n.modifiedAt)
                    }
                entries.add(StorageEntry(name, attrs.type, attrs))
            }
        }
        return entries
    }

    override suspend fun write(
        path: StoragePath,
        content: ByteArray,
        mode: StorageWriteMode,
    ): StorageAttributes {
        calls += "write:${path.toRelativeString()}"
        failures.onWrite?.let { throw it }
        if (path.isRoot) {
            throw VfsException(VfsErrorCode.TYPE_MISMATCH, "Cannot write to root directory")
        }
        val key = path.toRelativeString()
        // 验证父路径：若父路径是本替身能确认的文件，则属于可确认的文件/目录类型冲突
        val parent = path.parent
        if (!parent.isRoot) {
            val parentNode = nodes[parent.toRelativeString()]
            if (parentNode is Node.File) {
                throw VfsException(
                    VfsErrorCode.TYPE_MISMATCH,
                    "Parent path is an existing file: ${parent.toRelativeString()}",
                )
            }
        }
        val existing = nodes[key]
        if (existing is Node.Directory) {
            throw VfsException(VfsErrorCode.TYPE_MISMATCH, "Target already exists as a directory: $key")
        }

        when (mode) {
            StorageWriteMode.CREATE_NEW -> {
                if (existing != null) {
                    throw VfsException(VfsErrorCode.ALREADY_EXISTS, "File already exists: $key")
                }
            }

            StorageWriteMode.REPLACE_EXISTING -> {
                if (existing == null) {
                    throw VfsException(VfsErrorCode.NOT_FOUND, "File does not exist: $key")
                }
            }

            StorageWriteMode.UPSERT -> {}
        }

        val now = Instant.now()
        nodes[key] = Node.File(content.copyOf(), now)
        return StorageAttributes(NodeType.FILE, content.size.toLong(), now)
    }

    override suspend fun createDirectory(path: StoragePath) {
        calls += "createDirectory:${path.toRelativeString()}"
        failures.onCreateDirectory?.let { throw it }
        failures.onCreateDirectoryAt
            ?.takeIf { (at, _) -> at == path.toRelativeString() }
            ?.let { (_, failure) -> throw failure }
        if (path.isRoot) return
        val key = path.toRelativeString()
        val existing = nodes[key]
        if (existing is Node.File) {
            throw VfsException(VfsErrorCode.TYPE_MISMATCH, "Path is already a file: $key")
        }
        if (existing is Node.Directory) return

        // 检查父路径：若某级祖先是本替身能确认的文件，属于可确认的文件/目录类型冲突
        var current = path.parent
        while (!current.isRoot) {
            val parentNode = nodes[current.toRelativeString()]
            if (parentNode is Node.File) {
                throw VfsException(VfsErrorCode.TYPE_MISMATCH, "Parent is a file: ${current.toRelativeString()}")
            }
            current = current.parent
        }

        // 自动逐级创建目录
        val segments = path.segments
        for (i in 1..segments.size) {
            val subPath = StoragePath.of(segments.subList(0, i)).toRelativeString()
            if (!nodes.containsKey(subPath)) {
                nodes[subPath] = Node.Directory(Instant.now())
            }
        }
    }

    override suspend fun move(
        source: StoragePath,
        target: StoragePath,
    ): StorageAttributes {
        calls += "move:${source.toRelativeString()}->${target.toRelativeString()}"
        failures.onMove?.let { throw it }
        if (source.isRoot || target.isRoot) {
            throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "Cannot move root")
        }
        val srcKey = source.toRelativeString()
        val targetKey = target.toRelativeString()

        val srcNode = nodes[srcKey] ?: throw VfsException(VfsErrorCode.NOT_FOUND, "Source not found: $srcKey")
        if (nodes.containsKey(targetKey)) {
            throw VfsException(VfsErrorCode.ALREADY_EXISTS, "Target already exists: $targetKey")
        }

        when (srcNode) {
            is Node.File -> {
                if (!capabilities.nativeFileMove) {
                    throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "Native file move not supported")
                }
                nodes.remove(srcKey)
                nodes[targetKey] = srcNode
                return StorageAttributes(NodeType.FILE, srcNode.bytes.size.toLong(), srcNode.modifiedAt)
            }

            is Node.Directory -> {
                if (!capabilities.nativeDirectoryMove) {
                    throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "Native directory move not supported")
                }
                // 移动目录及其子项
                val toMove = nodes.filterKeys { it == srcKey || it.startsWith("$srcKey/") }
                toMove.forEach { (k, v) ->
                    nodes.remove(k)
                    val newK = if (k == srcKey) targetKey else targetKey + k.removePrefix(srcKey)
                    nodes[newK] = v
                }
                return StorageAttributes(NodeType.DIRECTORY, null, srcNode.modifiedAt)
            }
        }
    }

    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ) {
        calls += "delete:${path.toRelativeString()}"
        failures.onDelete?.let { throw it }
        if (path.isRoot) {
            throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "Cannot delete root")
        }
        val key = path.toRelativeString()
        val node = nodes[key] ?: throw VfsException(VfsErrorCode.NOT_FOUND, "Path not found: $key")

        when (node) {
            is Node.File -> {
                nodes.remove(key)
            }

            is Node.Directory -> {
                val children = nodes.keys.filter { it != key && it.startsWith("$key/") }
                if (children.isNotEmpty() && !recursive) {
                    throw VfsException(VfsErrorCode.DIRECTORY_NOT_EMPTY, "Directory is not empty: $key")
                }
                nodes.remove(key)
                if (recursive) {
                    children.forEach { nodes.remove(it) }
                }
            }
        }
    }
}
