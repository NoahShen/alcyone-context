package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageContent
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageEntry
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageStream
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import org.apache.opendal.Metadata
import org.apache.opendal.OpenDALException
import org.apache.opendal.Operator
import org.apache.opendal.ServiceConfig
import org.apache.opendal.WriteOptions
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core Storage Port 的首个真实实现：OpenDAL Local FS（T12）。
 *
 * 用法：挂载根 `data/reports` + 相对路径 `a.txt` → 物理文件 `<root>/a.txt`。
 * Adapter 只做「物理读写 + 物理边界」，不生成 Node ID、不写 Event、不碰 SQLite。
 *
 * 后端差异（T04 §5 + 本轮在 macOS arm64 实测确认）：
 *
 * | 后端行为 | 本 Adapter 的适配 |
 * | --- | --- |
 * | `write` 自动补父目录 | 写前先 `stat` 父级，缺失即 NOT_FOUND，Storage 不隐式建目录 |
 * | `rename` 覆盖已有目标 | 写前先 `stat` 目标，存在即 ALREADY_EXISTS |
 * | 删除不存在的路径返回成功 | 删前先 `stat`，缺失即 NOT_FOUND |
 * | 删除非空目录返回 `Unexpected`（os error 66） | 删前列举子项，非空即 DIRECTORY_NOT_EMPTY |
 * | 目录 `rename` 返回 `IsADirectory` | 直接判为 UNSUPPORTED_OPERATION，且 `nativeDirectoryMove = false` |
 * | `list` 把目录自身也放进结果 | 过滤自身条目，只保留相对单段名字 |
 * | `list` 不带尾斜杠时只返回自身 | 目录路径统一补尾斜杠 |
 * | `fs` 不支持 range read | 有界读取改为「包装流 + 按实际读取计数」，不依赖 `stat` 大小 |
 * | Operator 关闭后继续使用会让 JVM 崩溃 | 每次触碰 native 前检查关闭状态，转成 `CLOSED` |
 *
 * 已知限制见 `docs/tasks/m2-t12/T12_使用说明.md`：不抵御「检查后外部进程替换路径」的竞争，
 * 不防护硬链接与不可识别的别名，阻塞 native 调用不能被协程取消即时中断。
 */
class LocalFsStorage private constructor(
    private val operator: Operator,
    /** 规范化后的物理根（已消解符号链接）。 */
    val root: Path,
    private val options: LocalFsOptions,
    private val capabilities: StorageCapabilities,
) : Storage,
    AutoCloseable {
    private val closed = AtomicBoolean(false)

    /**
     * 打开一个 Local FS Storage。物理根必须**已存在**且是目录，否则 `INVALID_ARGUMENT`。
     *
     * 例：`LocalFsStorage.create(Path.of("/data/reports"))`。
     * 构造中途失败（例如 native 加载失败）会释放已经建好的 Operator。
     */
    companion object {
        suspend fun create(
            root: Path,
            options: LocalFsOptions = LocalFsOptions(),
        ): LocalFsStorage {
            val realRoot = LocalFsRoots.normalize(root)
            return storageCall("open") {
                val operator =
                    Operator.of(
                        ServiceConfig.Fs
                            .builder()
                            .root(realRoot.toString())
                            .build(),
                    )
                try {
                    LocalFsStorage(operator, realRoot, options, capabilitiesOf(operator))
                } catch (e: Throwable) {
                    operator.close()
                    throw e
                }
            }
        }

        /**
         * 能力值按**实际适配效果**给，不按后端声明照抄。
         *
         * 目录移动恒为 `false`：fs 的目录 rename 返回 `IsADirectory`（T04 实测 + 本轮复测），
         * 复制删除回退留给 T20～T22。`readOnly` 也恒为 `false`：一次权限检查不能承诺以后总能写入，
         * 可写目录挂只读盘的情况由真实写失败暴露（映射为 STORAGE_ACCESS_DENIED）。
         */
        private fun capabilitiesOf(operator: Operator): StorageCapabilities {
            val capability = operator.info.capability
            return StorageCapabilities(
                nativeFileMove = capability.rename && capability.stat,
                nativeDirectoryMove = false,
                createDirectory = capability.createDir,
                boundedRead = capability.read,
                readOnly = false,
            )
        }
    }

    /** 幂等；关闭后任何新调用抛 `CLOSED`。 */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            operator.close()
        }
    }

    fun isClosed(): Boolean = closed.get()

    /** 内部诊断：native 句柄是否已释放。用来区分「文件可删」与「Operator 确实关闭」。 */
    internal fun nativeHandleDisposed(): Boolean = operator.isDisposed

    override fun capabilities(): StorageCapabilities = capabilities

    override suspend fun stat(path: StoragePath): StorageAttributes =
        storageCall("stat") {
            open()
            LocalPath.resolve(root, path.segments)
            statOrFail(plain(path), "stat")
        }

    override suspend fun read(
        path: StoragePath,
        maxBytes: Long,
    ): StorageContent {
        if (path.isRoot) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "mount root is a directory")
        if (maxBytes < 0) throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "maxBytes must not be negative")
        return storageCall("read") {
            open()
            LocalPath.resolve(root, path.segments)
            val attributes = statOrFail(plain(path), "read")
            if (attributes.type == NodeType.DIRECTORY) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "read target is a directory")
            // 后端不支持 range read，只能边读边计数；BoundedInputStream 保证内存不超过 maxBytes + 1。
            // 只关包装流：底层 native 流由它关闭，避免对同一 native 对象 close 两次。
            BoundedInputStream(operator.createInputStream(plain(path)), maxBytes) { open() }.use { bounded ->
                StorageContent(bounded.readAllBytes(), attributes)
            }
        }
    }

    /**
     * 返回的流**只能打开一次**，由调用方关闭；每个流独立计数。
     *
     * 关闭顺序：先关流、再关 Adapter。Adapter 已关闭而流还没关时，流的下一次读取抛 `CLOSED` 而不是碰 native
     * （实测 Operator 关闭后再使用会 SIGSEGV）。
     */
    override suspend fun readStream(
        path: StoragePath,
        maxBytes: Long?,
    ): StorageStream {
        if (path.isRoot) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "mount root is a directory")
        if (maxBytes != null && maxBytes < 0) throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "maxBytes must not be negative")
        return storageCall("open read stream") {
            open()
            LocalPath.resolve(root, path.segments)
            val attributes = statOrFail(plain(path), "read")
            if (attributes.type == NodeType.DIRECTORY) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "read target is a directory")
            val native = operator.createInputStream(plain(path))
            try {
                LocalFsStream(attributes, native, maxBytes)
            } catch (e: Throwable) {
                native.close()
                throw e
            }
        }
    }

    /**
     * 限额在任何存储副作用之前判断；不截断，也不「先写再报错」。
     *
     * 模式预检用 `stat` 实现，**不是原子操作**；CREATE_NEW 另外使用后端 `if_not_exists` 条件写兜底。
     */
    override suspend fun write(
        path: StoragePath,
        content: ByteArray,
        mode: StorageWriteMode,
    ): StorageAttributes {
        if (path.isRoot) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "cannot write to the mount root")
        if (content.size.toLong() > options.defaultWriteLimitBytes) {
            throw VfsException(
                VfsErrorCode.LIMIT_EXCEEDED,
                "write of ${content.size} bytes exceeds the configured limit of ${options.defaultWriteLimitBytes} bytes",
            )
        }
        // 写入失败时无法断定文件是否已被创建或截断，故不谎报 NONE。
        return storageCall("write", VfsEffect.UNKNOWN) {
            open()
            LocalPath.resolve(root, path.segments)
            requireExistingParentDirectory(path)
            when (val existing = metadataOrNull(plain(path))) {
                null ->
                    if (mode == StorageWriteMode.REPLACE_EXISTING) {
                        throw VfsException(VfsErrorCode.NOT_FOUND, "write target does not exist")
                    }

                else -> {
                    if (typeOf(existing) == NodeType.DIRECTORY) {
                        throw VfsException(VfsErrorCode.TYPE_MISMATCH, "write target already exists as a directory")
                    }
                    if (mode == StorageWriteMode.CREATE_NEW) {
                        throw VfsException(VfsErrorCode.ALREADY_EXISTS, "write target already exists")
                    }
                }
            }
            if (mode == StorageWriteMode.CREATE_NEW && conditionalCreateAvailable) {
                operator.write(plain(path), content, WriteOptions.builder().ifNotExists(true).build())
            } else {
                operator.write(plain(path), content)
            }
            statOrFail(plain(path), "write")
        }
    }

    /** 目录已存在视为成功；父目录由后端逐级创建（与 Core 的 StorageFakeImpl 一致）。 */
    override suspend fun createDirectory(path: StoragePath) {
        if (path.isRoot) return
        storageCall("create directory") {
            open()
            LocalPath.resolve(root, path.segments)
            val existing = metadataOrNull(plain(path))
            if (existing == null) {
                operator.createDir(dirPath(path))
            } else if (typeOf(existing) == NodeType.FILE) {
                throw VfsException(VfsErrorCode.TYPE_MISMATCH, "create directory target already exists as a file")
            }
        }
    }

    /** 目录移动不覆盖，且不由本 Adapter 兜底：T04 实测目录 rename 返回 `IsADirectory`。 */
    override suspend fun move(
        source: StoragePath,
        target: StoragePath,
    ): StorageAttributes {
        if (source.isRoot || target.isRoot) throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "cannot move the mount root")
        return storageCall("move", VfsEffect.UNKNOWN) {
            open()
            LocalPath.resolve(root, source.segments)
            LocalPath.resolve(root, target.segments)
            val sourceAttributes = statOrFail(plain(source), "move")
            if (sourceAttributes.type == NodeType.DIRECTORY) {
                throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "native directory move is not supported by the local backend")
            }
            if (metadataOrNull(plain(target)) != null) {
                throw VfsException(VfsErrorCode.ALREADY_EXISTS, "move target already exists")
            }
            requireExistingParentDirectory(target)
            operator.rename(plain(source), plain(target))
            statOrFail(plain(target), "move")
        }
    }

    /**
     * 非递归删除非空目录 → `DIRECTORY_NOT_EMPTY`；缺失 → `NOT_FOUND`。
     *
     * 递归删除先规划整棵子树（此时拒绝符号链接，一个字节都没删），再自底向上删除；
     * 中途失败时前面已经删掉的条目算已知副作用，effect 报 `PARTIAL`。
     */
    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ) {
        if (path.isRoot) throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "cannot delete the mount root")
        storageCall("delete") {
            open()
            LocalPath.resolve(root, path.segments)
            val attributes = statOrFail(plain(path), "delete")
            if (attributes.type == NodeType.FILE) {
                operator.delete(plain(path))
                return@storageCall
            }
            if (!recursive) {
                if (listChildren(path, refuseSymbolicLinks = false).isNotEmpty()) {
                    throw VfsException(VfsErrorCode.DIRECTORY_NOT_EMPTY, "directory is not empty")
                }
                operator.delete(dirPath(path))
                return@storageCall
            }
            // 规划阶段先走完整棵子树（此时拒绝符号链接，一个字节都没删），再按最深在前执行。
            val plan = mutableListOf<Pair<StoragePath, NodeType>>()
            collectForDeletion(path, plan)
            plan += path to NodeType.DIRECTORY
            plan.forEachIndexed { index, (target, type) ->
                // 第一条就失败说明还没删任何东西；之后失败则已存在已知部分变更。
                mapStorageErrors("delete", if (index == 0) VfsEffect.NONE else VfsEffect.PARTIAL) {
                    // 目录要带尾斜杠：不带时后端按文件删除，空目录也会失败（实测 Unexpected）。
                    operator.delete(if (type == NodeType.DIRECTORY) dirPath(target) else plain(target))
                }
            }
        }
    }

    /** 单层完整列举：不含自身与孙级，不承诺排序。 */
    override suspend fun list(path: StoragePath): List<StorageEntry> =
        storageCall("list") {
            open()
            LocalPath.resolve(root, path.segments)
            val attributes = statOrFail(plain(path), "list")
            if (attributes.type == NodeType.FILE) {
                throw VfsException(VfsErrorCode.TYPE_MISMATCH, "list target is not a directory")
            }
            listChildren(path)
        }

    /** 后端是否支持条件创建（`if_not_exists`）；不支持时 CREATE_NEW 只靠预检。 */
    private val conditionalCreateAvailable: Boolean = operator.info.capability.writeWithIfNotExists

    private fun open() {
        if (closed.get()) throw VfsException(VfsErrorCode.CLOSED, "local storage is closed")
    }

    /** 文件 / 通用路径：根是空串。 */
    private fun plain(path: StoragePath): String = path.toRelativeString()

    /** 目录路径必须带尾斜杠：不带时后端只把目录自身当作列举结果。 */
    private fun dirPath(path: StoragePath): String = if (path.isRoot) "/" else "${path.toRelativeString()}/"

    private fun metadataOrNull(backendPath: String): Metadata? =
        try {
            operator.stat(backendPath)
        } catch (e: OpenDALException) {
            if (e.code == OpenDALException.Code.NotFound) null else throw e
        }

    private fun statOrFail(
        backendPath: String,
        operation: String,
    ): StorageAttributes =
        metadataOrNull(backendPath)?.let { attributesOf(it) }
            ?: throw VfsException(VfsErrorCode.NOT_FOUND, "local storage $operation target does not exist")

    /** 后端写了但读不出元数据属于后端异常，不再猜类型。 */
    private fun typeOf(metadata: Metadata): NodeType =
        when (metadata.mode) {
            Metadata.EntryMode.FILE -> NodeType.FILE
            Metadata.EntryMode.DIR -> NodeType.DIRECTORY
            Metadata.EntryMode.UNKNOWN -> throw VfsException(VfsErrorCode.STORAGE_ERROR, "local storage returned an unknown entry type")
        }

    private fun attributesOf(metadata: Metadata): StorageAttributes =
        StorageAttributes(
            type = typeOf(metadata),
            sizeBytes = if (metadata.mode == Metadata.EntryMode.FILE) metadata.contentLength else null,
            modifiedAt = metadata.lastModified,
        )

    /** 父目录必须已存在：Storage 不隐式为 write / move 补父目录（补齐属 Core 编排，T15）。 */
    private fun requireExistingParentDirectory(path: StoragePath) {
        val parent = metadataOrNull(plain(path.parent)) ?: throw VfsException(VfsErrorCode.NOT_FOUND, "parent directory does not exist")
        if (typeOf(parent) == NodeType.FILE) {
            throw VfsException(VfsErrorCode.TYPE_MISMATCH, "parent of the target is an existing file")
        }
    }

    /**
     * 单层列举：过滤后端放进结果的目录自身，只保留相对单段名字，并对每个条目做符号链接检查。
     *
     * @param refuseSymbolicLinks 非递归删除只需要知道「是否为空」，不进入子项，因此不触发链接拒绝。
     * @throws VfsException 条目里出现符号链接（`STORAGE_ACCESS_DENIED`）或后端返回了非单层条目（`STORAGE_ERROR`）。
     */
    private fun listChildren(
        path: StoragePath,
        refuseSymbolicLinks: Boolean = true,
    ): List<StorageEntry> {
        val prefix = dirPath(path)
        val entries = operator.list(prefix)
        val children = mutableListOf<StorageEntry>()
        for (entry in entries) {
            if (entry.path == prefix) continue
            val name = entry.path.removePrefix(prefix).removeSuffix("/")
            if (name.isEmpty() || '/' in name) {
                throw VfsException(VfsErrorCode.STORAGE_ERROR, "local storage returned an entry outside the requested directory")
            }
            if (refuseSymbolicLinks) {
                // 列举条目里的符号链接同样不跟随（T12 §2.2）。
                LocalPath.resolve(root, path.resolve(name).segments)
            }
            val attributes = attributesOf(entry.metadata)
            children += StorageEntry(name, attributes.type, attributes)
        }
        return children
    }

    /** 递归删除的规划阶段：只读取和校验，不删除，因此遇到符号链接时副作用为零。结果是后序（最深在前）。 */
    private fun collectForDeletion(
        path: StoragePath,
        plan: MutableList<Pair<StoragePath, NodeType>>,
    ) {
        for (entry in listChildren(path)) {
            val child = path.resolve(entry.name)
            // listChildren 已检查过链接，这里再解析一次以覆盖中间组件类型检查。
            LocalPath.resolve(root, child.segments)
            // 后序遍历：先删子树再删自己，保证每一步目标都是空的。
            if (entry.type == NodeType.DIRECTORY) {
                collectForDeletion(child, plan)
            }
            plan += child to entry.type
        }
    }

    /** 单次打开的流：重复打开报状态错误，关闭后打开报 CLOSED。 */
    private inner class LocalFsStream(
        override val attributes: StorageAttributes,
        private var native: InputStream?,
        private val limitBytes: Long?,
    ) : StorageStream {
        private var opened = false
        private var finished = false

        override fun openStream(): InputStream {
            open()
            if (finished) throw VfsException(VfsErrorCode.CLOSED, "storage stream is closed")
            if (opened) throw VfsException(VfsErrorCode.STATE_ERROR, "storage stream is single-shot; call readStream again to reopen")
            opened = true
            val stream = native ?: throw VfsException(VfsErrorCode.STATE_ERROR, "storage stream is already consumed")
            native = null
            return if (limitBytes == null) {
                GuardedInputStream(stream) { open() }
            } else {
                BoundedInputStream(stream, limitBytes) { open() }
            }
        }

        override fun close() {
            if (finished) return
            finished = true
            val stream = native
            native = null
            // Adapter 已关闭时不再触碰 native 句柄（关闭后的 native 操作会崩 JVM）。
            if (stream != null && !closed.get()) {
                mapStorageErrors("close read stream") { stream.close() }
            }
        }
    }
}

/** 只做「Adapter 是否还开着」的守卫，不计限额（`maxBytes = null` 的流）。 */
private class GuardedInputStream(
    private val delegate: InputStream,
    private val guard: () -> Unit,
) : InputStream() {
    override fun read(): Int {
        guard()
        return delegate.read()
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        guard()
        return delegate.read(buffer, offset, length)
    }

    override fun skip(count: Long): Long {
        guard()
        return delegate.skip(count)
    }

    override fun available(): Int {
        guard()
        return delegate.available()
    }

    override fun close() = delegate.close()
}
