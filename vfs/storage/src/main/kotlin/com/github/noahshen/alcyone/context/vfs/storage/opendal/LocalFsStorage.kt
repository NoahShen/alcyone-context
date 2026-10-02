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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 VFS 的文件操作落到本地磁盘上，底层用 Apache OpenDAL。
 *
 * 例：挂载根是 `data/reports`、路径是 `a.txt`，那么实际动的就是 `data/reports/a.txt` 这个文件。
 * 它只管读写磁盘，不生成 Node ID、不写事件、不碰数据库。
 *
 * OpenDAL 的默认行为和 VFS 的要求对不上，所以每个操作前都自己先查一遍：
 *
 * - 写文件、改名时目标已存在 → 报 `ALREADY_EXISTS`（OpenDAL 本来会直接覆盖）。
 * - 上级目录不存在 → 报 `NOT_FOUND`（OpenDAL 本来会顺手建出来）。
 * - 要删的东西不存在 → 报 `NOT_FOUND`（OpenDAL 本来会当成功）。
 * - 删非空目录 → 报 `DIRECTORY_NOT_EMPTY`（OpenDAL 只会报一个看不懂的 `Unexpected`）。
 * - 改目录名 → 报 `UNSUPPORTED_OPERATION`（OpenDAL 只会报 `IsADirectory`）。
 * - 列举目录时会多返回它自己 → 过滤掉；目录路径统一带上结尾的 `/`，否则后端只会返回它自己。
 * - 后端不能只读文件的一段，所以限量读取靠 [StorageInputStream] 数着实际读了多少。
 * - **Operator 一旦关闭再使用会让 JVM 崩溃**，所以每个操作都经 [NativeLifetime] 加锁。
 *
 * 已知限制见 `docs/tasks/m2-t12/T12_使用说明.md`。
 */
class LocalFsStorage private constructor(
    private val operator: Operator,
    /** 挂载根，已把符号链接换成它指向的真实目录。 */
    val root: Path,
    private val options: LocalFsOptions,
    private val capabilities: StorageCapabilities,
    /** 打开文件流。生产固定用 OpenDAL；留成参数是为了测试能换成计数用的替身流。 */
    private val readerFactory: (String) -> InputStream = { path -> operator.createInputStream(path) },
    /** 查文件信息。生产固定用 OpenDAL；留成参数是为了测试能让其中一次查询失败。 */
    private val statFactory: (String) -> Metadata = { path -> operator.stat(path) },
    /** 测试用的观察点，每次进入 [storageCall] 会被叫一次。生产恒为 `null`。 */
    private val onNativeCall: ((String) -> Unit)?,
) : Storage,
    AutoCloseable {
    /** 防止「一边操作一边关闭」。 */
    private val lifetime =
        NativeLifetime {
            // 顺序很重要：先关掉所有还开着的文件流，再关 Operator。
            // 这样文件流一定是在 Operator 还有效的时候关掉的，不会去操作一个已经销毁的 Operator。
            drainReaders()
            operator.close()
        }

    /** 已经打开、但还没关闭的文件流。关闭存储时要把它们都关掉。 */
    private val liveReaders = ConcurrentHashMap.newKeySet<ReaderHandle>()

    companion object {
        /**
         * 打开一个本地磁盘存储。挂载根必须已经存在且是个目录，否则报 `INVALID_ARGUMENT`。
         *
         * 例：`LocalFsStorage.create(Path.of("/data/reports"))`。
         */
        suspend fun create(
            root: Path,
            options: LocalFsOptions = LocalFsOptions(),
        ): LocalFsStorage = open(root, options)

        /**
         * 测试用的入口，可以把打开 Operator、打开文件流、查文件信息这几步换成自己的实现。
         * 生产代码请用 [create]。
         */
        internal suspend fun open(
            root: Path,
            options: LocalFsOptions,
            operatorFactory: (Path) -> Operator = { openOperatorInBlock(it) },
            readerFactory: (Operator) -> (String) -> InputStream = { operator -> { path -> operator.createInputStream(path) } },
            statFactory: (Operator) -> (String) -> Metadata = { operator -> { path -> operator.stat(path) } },
            onNativeCall: ((String) -> Unit)? = null,
        ): LocalFsStorage {
            val realRoot = LocalFsRoots.normalize(root)
            var opened: Operator? = null
            return handoffOrRelease(release = { opened?.close() }) {
                storageCall("open adapter", onEnter = onNativeCall) {
                    val operator = operatorFactory(realRoot)
                    opened = operator
                    try {
                        LocalFsStorage(
                            operator,
                            realRoot,
                            options,
                            capabilitiesOf(operator),
                            readerFactory(operator),
                            statFactory(operator),
                            onNativeCall,
                        )
                    } catch (e: Throwable) {
                        opened = null
                        operator.close()
                        throw e
                    }
                }
            }
        }

        /**
         * 打开 OpenDAL 后端。它是阻塞调用，所以由调用方放进 [storageCall] 里执行。
         *
         * 之所以不自己套 [storageCall]：只有这样「记住已打开的 Operator」这一行
         * 才和打开动作在同一个后台任务里，取消时才能关掉它。
         */
        private fun openOperatorInBlock(root: Path): Operator =
            Operator.of(
                ServiceConfig.Fs
                    .builder()
                    .root(root.toString())
                    .build(),
            )

        /**
         * 声明这个存储支持哪些操作。宁可报少也不要多报：后端说支持不代表这里能用。
         *
         * `nativeDirectoryMove` 恒为 `false`：后端改目录名会返回 `IsADirectory`。
         * `readOnly` 恒为 `false`：权限随时可能变，写失败时自然会给调用方报错。
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

    /** 关闭存储。会等在进行的操作做完；关过之后再调用任何方法都报 `CLOSED`。 */
    override fun close() = lifetime.close()

    fun isClosed(): Boolean = lifetime.isClosed()

    /** 内部诊断用：Operator 是不是真的关掉了。不能拿「文件还能删」当证据。 */
    internal fun nativeHandleDisposed(): Boolean = operator.isDisposed

    /**
     * 返回能力值。存的是打开时就定好的常量，不碰后端，所以这里故意不加锁，
     * 关闭之后也能查——否则一次慢操作就能把能力查询一起堵住。
     */
    override fun capabilities(): StorageCapabilities = capabilities

    override suspend fun stat(path: StoragePath): StorageAttributes =
        call("stat") {
            lifetime.call {
                LocalPath.resolve(root, path.segments)
                statOrFail(plain(path), "stat")
            }
        }

    override suspend fun read(
        path: StoragePath,
        maxBytes: Long,
    ): StorageContent {
        if (path.isRoot) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "mount root is a directory")
        if (maxBytes < 0) throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "maxBytes must not be negative")
        val attributes = precheckForRead(path)
        if (attributes.type == NodeType.DIRECTORY) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "read target is a directory")
        return call("read") {
            lifetime.call {
                // 后端不能只读一段，所以边读边数；内存最多多占 1 个字节。
                StorageInputStream(newReader(plain(path)), maxBytes, lifetime).use { bounded ->
                    StorageContent(bounded.readAllBytes(), attributes)
                }
            }
        }
    }

    /**
     * 打开文件流，调用方用完自己关闭。同一个流只能打开一次，要重读就再调一次本方法。
     *
     * 关流的顺序没有要求，两种都行：
     * - 先关流再关存储：一切正常。
     * - 先关存储：存储关闭时会把还开着的文件流都关掉，之后再关流是空操作，不报错。
     */
    override suspend fun readStream(
        path: StoragePath,
        maxBytes: Long?,
    ): StorageStream {
        if (path.isRoot) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "mount root is a directory")
        if (maxBytes != null && maxBytes < 0) throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "maxBytes must not be negative")
        val attributes = precheckForRead(path)
        if (attributes.type == NodeType.DIRECTORY) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "read target is a directory")
        // 文件流在后台任务里打开。万一交到调用方手上之前协程被取消，就在这里关掉。
        var opened: StorageStream? = null
        return handoffOrRelease(release = { opened?.close() }) {
            call("open read stream") {
                lifetime.call {
                    val handle = newReader(plain(path))
                    try {
                        LocalFsStream(attributes, handle, maxBytes)
                    } catch (e: Throwable) {
                        handle.release()
                        throw e
                    }.also { opened = it }
                }
            }
        }
    }

    /** 打开文件流并记进 [liveReaders]，保证关闭存储时不会漏掉它。 */
    private fun newReader(backendPath: String): ReaderHandle {
        val handle = ReaderHandle(readerFactory(backendPath)) { liveReaders.remove(it) }
        liveReaders += handle
        return handle
    }

    /** 把所有还开着的文件流都关掉。由 [lifetime] 在关闭存储时调用。 */
    private fun drainReaders() {
        liveReaders.forEach { handle ->
            mapStorageErrors("close read stream") { handle.release() }
        }
        liveReaders.clear()
    }

    /**
     * 写文件。超限在动手写之前就拒绝，不会写一半再报错。
     *
     * 三个模式靠提前查文件信息来判断，这一步**不是原子的**：查完之后到真正写入之前，
     * 别的进程可能已经改了文件。所以 `CREATE_NEW` 还额外用了后端的「不存在才写」条件来兜底。
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
        // effect 按**阶段**给，不按方法给。
        // 第一步：先检查，一个字节都还没写。这时失败就是「什么都没做」。
        call("write", VfsEffect.NONE) {
            lifetime.call {
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
            }
        }
        // 第二步：动手写。写失败时分不清文件是没建还是建了一半，所以只能说「结果不明」。
        call("write content", VfsEffect.UNKNOWN) {
            lifetime.call {
                if (mode == StorageWriteMode.CREATE_NEW && conditionalCreateAvailable) {
                    operator.write(plain(path), content, WriteOptions.builder().ifNotExists(true).build())
                } else {
                    operator.write(plain(path), content)
                }
            }
        }
        // 第三步：再查一次文件信息确认。写入已经成功了，读不到就说「已经改了东西」。
        return call("write attributes", VfsEffect.PARTIAL) {
            lifetime.call { statOrFail(plain(path), "write") }
        }
    }

    /**
     * 建目录。已经存在就算成功；上级目录不存在时后端会一层层补出来。
     *
     * 检查阶段失败是「什么都没建」；进了后端再失败就是「结果不明」——
     * 后端会自己补目录，失败时说不清建到了哪一层。
     */
    override suspend fun createDirectory(path: StoragePath) {
        // 挂载根本身不用建，但「已关闭」还是要照报。
        if (path.isRoot) return lifetime.call { }
        val existing =
            call("create directory", VfsEffect.NONE) {
                lifetime.call {
                    LocalPath.resolve(root, path.segments)
                    metadataOrNull(plain(path))
                }
            }
        if (existing != null) {
            if (typeOf(existing) == NodeType.FILE) {
                throw VfsException(VfsErrorCode.TYPE_MISMATCH, "create directory target already exists as a file")
            }
            return
        }
        call("create directory content", VfsEffect.UNKNOWN) {
            lifetime.call { operator.createDir(dirPath(path)) }
        }
    }

    /** 改名 / 移动。目标已存在就报错，不覆盖；改目录名不支持（后端只会返回 `IsADirectory`）。 */
    override suspend fun move(
        source: StoragePath,
        target: StoragePath,
    ): StorageAttributes {
        if (source.isRoot || target.isRoot) throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "cannot move the mount root")
        // 第一步：检查两端路径、源是什么、目标在不在、上级目录在不在。
        call("move", VfsEffect.NONE) {
            lifetime.call {
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
            }
        }
        // 第二步：真正改名。失败时分不清改了没改，所以只能说「结果不明」。
        call("move content", VfsEffect.UNKNOWN) {
            lifetime.call { operator.rename(plain(source), plain(target)) }
        }
        // 第三步：再查一次目标确认。改名已经成功了，读不到就说「已经改了东西」。
        return call("move attributes", VfsEffect.PARTIAL) {
            lifetime.call { statOrFail(plain(target), "move") }
        }
    }

    /**
     * 删除文件或目录。不存在报 `NOT_FOUND`；目录不递归删时里面还有东西报 `DIRECTORY_NOT_EMPTY`。
     *
     * 递归删分两步：先把整棵子树的删除顺序排好（这一步遇到符号链接就停，一个字节都还没删），
     * 再从最深的往上删。中途失败时，已经删掉几个就报 `PARTIAL`，一个没删掉就报 `NONE`。
     */
    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ) {
        if (path.isRoot) throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "cannot delete the mount root")
        call("delete") {
            lifetime.call {
                LocalPath.resolve(root, path.segments)
                val attributes = statOrFail(plain(path), "delete")
                if (attributes.type == NodeType.FILE) {
                    operator.delete(plain(path))
                } else if (!recursive) {
                    if (listChildren(path, refuseSymbolicLinks = false).isNotEmpty()) {
                        throw VfsException(VfsErrorCode.DIRECTORY_NOT_EMPTY, "directory is not empty")
                    }
                    operator.delete(dirPath(path))
                } else {
                    // 先走完整棵子树排出删除顺序，此时还没删任何东西。
                    val plan = mutableListOf<Pair<StoragePath, NodeType>>()
                    collectForDeletion(path, plan)
                    plan += path to NodeType.DIRECTORY
                    var removed = 0
                    plan.forEach { (target, type) ->
                        mapStorageErrors("delete", if (removed == 0) VfsEffect.NONE else VfsEffect.PARTIAL) {
                            // 目录必须带结尾的 /，否则后端会当成文件去删，空目录也会失败。
                            operator.delete(if (type == NodeType.DIRECTORY) dirPath(target) else plain(target))
                        }
                        // 已经删掉几个是唯一的事实依据，效果就按它来定。
                        removed++
                    }
                }
            }
        }
    }

    /** 单层完整列举：不含自身与孙级，不承诺排序。 */
    override suspend fun list(path: StoragePath): List<StorageEntry> =
        call("list") {
            lifetime.call {
                LocalPath.resolve(root, path.segments)
                val attributes = statOrFail(plain(path), "list")
                if (attributes.type == NodeType.FILE) {
                    throw VfsException(VfsErrorCode.TYPE_MISMATCH, "list target is not a directory")
                }
                listChildren(path)
            }
        }

    /** 后端支不支持「不存在才写」。不支持的话，CREATE_NEW 只能靠提前查。 */
    private val conditionalCreateAvailable: Boolean = operator.info.capability.writeWithIfNotExists

    /** 每个存储操作都从这里走：换到后台线程、把异常翻译成 VFS 错误码。 */
    private suspend fun <T> call(
        operation: String,
        effect: VfsEffect = VfsEffect.NONE,
        block: () -> T,
    ): T = storageCall(operation, effect, onNativeCall, block)

    /** 交给后端的文件路径。 */
    private fun plain(path: StoragePath): String = path.toRelativeString()

    /** 交给后端的目录路径，必须带结尾的 `/`，否则后端只认得出它自己。 */
    private fun dirPath(path: StoragePath): String = if (path.isRoot) "/" else "${path.toRelativeString()}/"

    private fun metadataOrNull(backendPath: String): Metadata? =
        try {
            statFactory(backendPath)
        } catch (e: OpenDALException) {
            if (e.code == OpenDALException.Code.NotFound) null else throw e
        }

    private fun statOrFail(
        backendPath: String,
        operation: String,
    ): StorageAttributes =
        metadataOrNull(backendPath)?.let { attributesOf(it) }
            ?: throw VfsException(VfsErrorCode.NOT_FOUND, "local storage $operation target does not exist")

    /** 查文件类型。后端说不上来是什么就报错，不猜。 */
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

    /** 上级目录必须已经存在。这里不替调用方补目录，补目录是上层的事（T15）。 */
    private fun requireExistingParentDirectory(path: StoragePath) {
        val parent = metadataOrNull(plain(path.parent)) ?: throw VfsException(VfsErrorCode.NOT_FOUND, "parent directory does not exist")
        if (typeOf(parent) == NodeType.FILE) {
            throw VfsException(VfsErrorCode.TYPE_MISMATCH, "parent of the target is an existing file")
        }
    }

    /**
     * 读文件前的检查：拒绝符号链接和「拿文件当前目录」的情况，然后查出文件信息。
     *
     * 查磁盘这一步也是阻塞的，所以放在后台线程里，否则会把调用方的线程占住。
     * 它刻意放在长时间读文件的锁外面，免得一次慢查询挡住关闭操作。
     */
    private suspend fun precheckForRead(path: StoragePath): StorageAttributes =
        call("read precheck") {
            LocalPath.resolve(root, path.segments)
            lifetime.call { statOrFail(plain(path), "read") }
        }

    /**
     * 列出一个目录里的东西。只列一层，不含目录自己和更深层，顺序不保证。
     *
     * 还要用 JDK 直接读一遍磁盘做核对，因为后端列举时会跳过「指向已删除文件的符号链接」，
     * 只看后端的返回结果会以为目录是干净的。
     *
     * @param refuseSymbolicLinks `false` 时表示「只想知道目录是不是空的」，符号链接按普通内容算，
     *   遇到就报 `DIRECTORY_NOT_EMPTY` 而不是拒绝访问。
     */
    private fun listChildren(
        path: StoragePath,
        refuseSymbolicLinks: Boolean = true,
    ): List<StorageEntry> {
        val prefix = dirPath(path)
        val entries = operator.list(prefix)
        val children = mutableListOf<StorageEntry>()
        val reported = mutableSetOf<String>()
        for (entry in entries) {
            if (entry.path == prefix) continue
            val name = entry.path.removePrefix(prefix).removeSuffix("/")
            if (name.isEmpty() || '/' in name) {
                throw VfsException(VfsErrorCode.STORAGE_ERROR, "local storage returned an entry outside the requested directory")
            }
            reported += name
            if (refuseSymbolicLinks) {
                // 列出来的符号链接也不跟过去。
                LocalPath.resolve(root, path.resolve(name).segments)
            }
            val attributes = attributesOf(entry.metadata)
            children += StorageEntry(name, attributes.type, attributes)
        }
        refuseHiddenEntries(LocalPath.resolve(root, path.segments), reported, refuseSymbolicLinks)
        return children
    }

    /**
     * 磁盘上有、后端没返回的东西，直接拒绝。
     *
     * 一定发生在删东西之前：删除是先排好顺序再动手，所以报错时一个文件都还没删。
     */
    private fun refuseHiddenEntries(
        physical: Path,
        reported: Set<String>,
        refuseSymbolicLinks: Boolean,
    ) {
        if (!Files.isDirectory(physical)) return
        Files.newDirectoryStream(physical).use { stream ->
            for (child in stream) {
                val name = child.fileName.toString()
                if (name in reported) continue
                if (!refuseSymbolicLinks) {
                    // 非递归删除只需要知道「是否为空」，所以看不到的条目一律算非空。
                    throw VfsException(VfsErrorCode.DIRECTORY_NOT_EMPTY, "directory is not empty")
                }
                if (Files.isSymbolicLink(child)) {
                    throw VfsException(VfsErrorCode.STORAGE_ACCESS_DENIED, "symbolic links are not followed")
                }
                // 不是符号链接却只有本地看得到：两边对不上，不猜原因。
                throw VfsException(VfsErrorCode.STORAGE_ERROR, "local storage listing is inconsistent with the physical directory")
            }
        }
    }

    /** 把要删的条目按「最深的先删」排好。这一步只看不删，所以遇到符号链接时不会有任何改动。 */
    private fun collectForDeletion(
        path: StoragePath,
        plan: MutableList<Pair<StoragePath, NodeType>>,
    ) {
        for (entry in listChildren(path)) {
            // 上面已经对每个子项检查过了，这里不用重复。
            val child = path.resolve(entry.name)
            // 先把里面的删完再删自己，保证每一步目标都是空的。
            if (entry.type == NodeType.DIRECTORY) {
                collectForDeletion(child, plan)
            }
            plan += child to entry.type
        }
    }

    /**
     * 一个只能打开一次的读文件流。
     *
     * 文件流实际由 [ReaderHandle] 保管，本类和 [StorageInputStream] 都只是能触发关闭的入口，
     * 所以谁先关都行，而且只会关一次。三种顺序的结果：
     *
     * 1. 先关流：正常关掉。
     * 2. 先关存储：存储关闭时会把还开着的文件流都关掉，之后再关这个流是空操作，不报错。
     * 3. 还没打开就关存储：一样由存储关掉，之后再关也是空操作。
     */
    private inner class LocalFsStream(
        override val attributes: StorageAttributes,
        private val handle: ReaderHandle,
        private val limitBytes: Long?,
    ) : StorageStream {
        private var opened = false
        private var finished = false

        override fun openStream(): InputStream =
            lifetime.call {
                if (finished) throw VfsException(VfsErrorCode.CLOSED, "storage stream is closed")
                if (opened) throw VfsException(VfsErrorCode.STATE_ERROR, "storage stream is single-shot; call readStream again to reopen")
                opened = true
                StorageInputStream(handle, limitBytes, lifetime)
            }

        override fun close() {
            if (finished) return
            finished = true
            // 不经过 lifetime：存储已经关闭时，这里也要能正常关掉或者什么都不做。
            mapStorageErrors("close read stream") { handle.release() }
        }
    }
}
