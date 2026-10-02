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
 * | Operator 关闭后继续使用会让 JVM 崩溃 | 每次存储操作与每次流读取都持 [NativeLifetime] 读锁，与释放互斥 |
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
    /**
     * 为一个相对路径打开 native reader。生产固定走 `operator.createInputStream`。
     *
     * 存在的唯一理由是让测试能在**不碰 native** 的前提下拿到 [LocalFsStream]，直接断言它到底
     * 释放没释放那个流句柄：OpenDAL 的 reader 泄漏不会让后续读取失败，“还能读”恒真，证明不了任何事。
     */
    private val readerFactory: (String) -> InputStream = { path -> operator.createInputStream(path) },
    /**
     * 为一个相对路径取元数据。生产固定走 `operator.stat`。
     *
     * 与 [readerFactory] 同款的测试缝隙：effect 分阶段的断言必须能精确地让**某一次** `stat` 失败，
     * 而不能用 [onNativeCall]——它在 `mapStorageErrors` 之前抛，异常不会被映射，测不到阶段差别。
     */
    private val statFactory: (String) -> Metadata = { path -> operator.stat(path) },
    /**
     * 测试专用可观测点，收到 [storageCall] 的操作名。生产恒为 `null`。
     *
     * 阻塞调用是否离开调用方线程、以及「资源已取得但交回前被取消」这两个窗口都发生在
     * [storageCall] 的 `withContext` 边界上，别处没有能挂上去的点。构造参数注入而不是全局变量：
     * 测试之间不会互相污染，并发跑也不会互相踩。
     */
    private val onNativeCall: ((String) -> Unit)?,
) : Storage,
    AutoCloseable {
    /**
     * 「Operator 还可用」与「释放 Operator」互斥：所有存储操作取读锁，[close] 取写锁。
     * 已进入的操作做完才释放，释放后不再有操作进入——这是唯一能挡住 SIGSEGV 的办法。
     */
    private val lifetime =
        NativeLifetime {
            // 排空必须发生在**写锁内、Operator 释放之前**：这样 reader 一定在 Operator 还活着时被关闭，
            // 不会去操作一个已 dispose 的 Operator。写锁同时保证没有读取正在使用这些 reader。
            drainReaders()
            operator.close()
        }

    /**
     * 尚未释放的 reader 登记表。
     *
     * 实测 Operator 释放**不会**连带释放 reader，所以每个建出来的 reader 都必须登记；
     * 首次释放时由 [ReaderHandle] 的回调摘除。Adapter 关闭时在写锁内排空，调用方之后再关流是幂等空操作。
     */
    private val liveReaders = ConcurrentHashMap.newKeySet<ReaderHandle>()

    companion object {
        /**
         * 打开一个 Local FS Storage。物理根必须**已存在**且是目录，否则 `INVALID_ARGUMENT`。
         *
         * 例：`LocalFsStorage.create(Path.of("/data/reports"))`。
         *
         * [handoffOrRelease] 保证「Operator 已建好、但协程在交回结果前被取消」时它仍被释放。
         */
        suspend fun create(
            root: Path,
            options: LocalFsOptions = LocalFsOptions(),
        ): LocalFsStorage = open(root, options)

        /**
         * 内部入口：可替换 Operator 工厂与 reader 工厂。
         *
         * 两个工厂都只为测试存在：OpenDAL 的 Operator 与 reader 都不是可注入的接口，
         * 而「交接失败时到底释没释放」这种断言必须看**实际对象**的关闭次数——
         * 「之后还能打开」这种间接证据对 Operator 泄漏恒真（已实测）。
         *
         * [readerFactory] 放在最后，调用方可以用尾随 lambda 写。
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
         * 创建 Operator 本身就是 native 调用（[Operator.of] 会加载并打开后端），
         * 所以它必须在 [storageCall] 的 IO 执行环境里，而不是由 `create` 在调用方线程上直接开。
         *
         * 不用 [storageCall] 包自己，而是交给调用方套：只有这样 `opened = …` 的登记才在同一个 IO 块内完成。
         */
        private fun openOperatorInBlock(root: Path): Operator =
            Operator.of(
                ServiceConfig.Fs
                    .builder()
                    .root(root.toString())
                    .build(),
            )

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

    /** 幂等；等在进行中的操作和流读取收尾后才释放 native 句柄；关闭后任何新调用抛 `CLOSED`。 */
    override fun close() = lifetime.close()

    fun isClosed(): Boolean = lifetime.isClosed()

    /** 内部诊断：native 句柄是否已释放。用来区分「文件可删」与「Operator 确实关闭」。 */
    internal fun nativeHandleDisposed(): Boolean = operator.isDisposed

    /**
     * 有意的例外：**不**取 [NativeLifetime] 读锁。
     *
     * 能力值是打开后端时算好的不可变快照，不触碰任何 native 句柄，所以「Adapter 已关闭」对它没有意义。
     * 取读锁反而会让「`close()` 正在等某个慢操作」时，纯读的能力查询也被一起挡住。
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
                // 后端不支持 range read，只能边读边计数；StorageInputStream 保证内存不超过 maxBytes + 1 字节。
                StorageInputStream(newReader(plain(path)), maxBytes, lifetime).use { bounded ->
                    StorageContent(bounded.readAllBytes(), attributes)
                }
            }
        }
    }

    /**
     * 返回的流**只能打开一次**，由调用方关闭；每个流独立计数。
     *
     * 底层 native 句柄的**唯一所有者**是返回的 [StorageStream]：[LocalFsStream.openStream] 之后
     * [LocalFsStream.close] 不再经手，[StorageInputStream.close] 负责关掉它。因此「流没打开就关 Adapter」
     * 不会泄漏——那一份 native 句柄仍由 [LocalFsStream.close] 在读锁内释放。
     *
     * 关闭顺序：先关流、再关 Adapter 最自然，但反过来也安全。已打开的流与 `close` 争用写锁时，
     * 一次读取要么做完、要么抛 `CLOSED`，不会释放在读中的句柄。
     */
    override suspend fun readStream(
        path: StoragePath,
        maxBytes: Long?,
    ): StorageStream {
        if (path.isRoot) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "mount root is a directory")
        if (maxBytes != null && maxBytes < 0) throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "maxBytes must not be negative")
        val attributes = precheckForRead(path)
        if (attributes.type == NodeType.DIRECTORY) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "read target is a directory")
        // 与 create 同一模式：native reader 在 IO 块内取得，交给调用方之前被取消则在这里释放。
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

    /** 建一个 reader 并登记：它随后要么被流释放，要么被 [drainReaders] 排空，不会漏。 */
    private fun newReader(backendPath: String): ReaderHandle {
        val handle = ReaderHandle(readerFactory(backendPath)) { liveReaders.remove(it) }
        liveReaders += handle
        return handle
    }

    /** 在写锁内释放所有未释放的 reader（由 [lifetime] 的 release 回调调用）。 */
    private fun drainReaders() {
        liveReaders.forEach { handle ->
            mapStorageErrors("close read stream") { handle.release() }
        }
        liveReaders.clear()
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
        // effect 按**阶段**给，不按方法给。
        // 阶段一（预检）：路径闸门、父目录、模式判定，一个字节都没写 → 失败一律 NONE。
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
        // 阶段二（写入）：调用后端后无法断定文件是否已创建或截断 → UNKNOWN。
        call("write content", VfsEffect.UNKNOWN) {
            lifetime.call {
                if (mode == StorageWriteMode.CREATE_NEW && conditionalCreateAvailable) {
                    operator.write(plain(path), content, WriteOptions.builder().ifNotExists(true).build())
                } else {
                    operator.write(plain(path), content)
                }
            }
        }
        // 阶段三（回读属性）：写入已成功，读不回来不再是「什么都没做」→ PARTIAL。
        return call("write attributes", VfsEffect.PARTIAL) {
            lifetime.call { statOrFail(plain(path), "write") }
        }
    }

    /**
     * 目录已存在视为成功；父目录由后端逐级创建（与 Core 的 StorageFakeImpl 一致）。
     *
     * effect：预检失败 → `NONE`（什么都没建）；进入 `operator.createDir` 后失败 → `UNKNOWN`
     * （后端会逐级补目录，失败时无法断定建到了哪一层）。
     */
    override suspend fun createDirectory(path: StoragePath) {
        // 挂载根本身没有可新建的目录，但「已关闭」仍然必须先报：不能因为是根就绕开生命周期检查。
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

    /** 目录移动不覆盖，且不由本 Adapter 兜底：T04 实测目录 rename 返回 `IsADirectory`。 */
    override suspend fun move(
        source: StoragePath,
        target: StoragePath,
    ): StorageAttributes {
        if (source.isRoot || target.isRoot) throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "cannot move the mount root")
        // 阶段一（预检）：两端路径闸门、源类型、目标是否已存在、目标父目录 → 失败 NONE。
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
        // 阶段二（改名）：调用后无法断定是否已改名 → UNKNOWN。
        call("move content", VfsEffect.UNKNOWN) {
            lifetime.call { operator.rename(plain(source), plain(target)) }
        }
        // 阶段三（回读属性）：改名已成功 → PARTIAL。
        return call("move attributes", VfsEffect.PARTIAL) {
            lifetime.call { statOrFail(plain(target), "move") }
        }
    }

    /**
     * 非递归删除非空目录 → `DIRECTORY_NOT_EMPTY`；缺失 → `NOT_FOUND`。
     *
     * 递归删除先规划整棵子树（此时拒绝符号链接，一个字节都没删），再自底向上删除；
     * 中途失败时 effect 由**已成功删除的条目数**推导：有就 `PARTIAL`，没有就 `NONE`。
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
                    // 规划阶段先走完整棵子树（此时拒绝符号链接，一个字节都没删），再按最深在前执行。
                    val plan = mutableListOf<Pair<StoragePath, NodeType>>()
                    collectForDeletion(path, plan)
                    plan += path to NodeType.DIRECTORY
                    var removed = 0
                    plan.forEach { (target, type) ->
                        mapStorageErrors("delete", if (removed == 0) VfsEffect.NONE else VfsEffect.PARTIAL) {
                            // 目录要带尾斜杠：不带时后端按文件删除，空目录也会失败（实测 Unexpected）。
                            operator.delete(if (type == NodeType.DIRECTORY) dirPath(target) else plain(target))
                        }
                        // 证据：已成功删除的条目数。effect 由这个事实推导，而不是「大概是第一条吧」。
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

    /** 后端是否支持条件创建（`if_not_exists`）；不支持时 CREATE_NEW 只靠预检。 */
    private val conditionalCreateAvailable: Boolean = operator.info.capability.writeWithIfNotExists

    /** 所有存储调用的唯一入口：上 IO 调度器、带错误映射，并带上本实例的 [onNativeCall] 观测点。 */
    private suspend fun <T> call(
        operation: String,
        effect: VfsEffect = VfsEffect.NONE,
        block: () -> T,
    ): T = storageCall(operation, effect, onNativeCall, block)

    /** 文件 / 通用路径：根是空串。 */
    private fun plain(path: StoragePath): String = path.toRelativeString()

    /** 目录路径必须带尾斜杠：不带时后端只把目录自身当作列举结果。 */
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
     * 读取前的闸门：拒绝符号链接与中间组件类型冲突，再取目标元数据。
     *
     * 两层都必要：
     *
     * - [LocalPath.resolve] 走 JDK 本地路径 API，不碰 native，但 [Files] 也会阻塞，所以整体在
     *   [storageCall] 的 IO 执行环境里跑——否则阻塞的 `stat` / `isSymbolicLink` 会占用调用方线程。
     * - 只有 `stat` 需要 [lifetime] 读锁，而且闸门刻意放在长读的读锁**之外**：
     *   IO 往返不占用读锁、不挡住 `close`，同时保证 `read` / `readStream` 两条路径的边界规则完全一致。
     */
    private suspend fun precheckForRead(path: StoragePath): StorageAttributes =
        call("read precheck") {
            LocalPath.resolve(root, path.segments)
            lifetime.call { statOrFail(plain(path), "read") }
        }

    /**
     * 单层列举：过滤后端放进结果的目录自身，只保留相对单段名字，并对每个条目做符号链接检查。
     *
     * 物理目录会额外核对一遍，因为 `fs` 后端对每个条目做 `stat`，**悬空符号链接 `stat` 失败就被静默跳过**。
     * 只遍历后端条目的话，“先规划后删除”的拒绝保证会失效：普通文件已被删完才报 `STORAGE_ERROR`。
     * 核对规则（物理目录里有、但后端没返回的条目）：
     *
     * - 是符号链接 → `STORAGE_ACCESS_DENIED`，与可见链接同一处理；
     * - 不是符号链接 → `STORAGE_ERROR`，两边不一致但不猜原因。
     *
     * 内容访问仍然全部走 OpenDAL；这里只用 JDK 读目录项，不重写后端。
     *
     * @param refuseSymbolicLinks 非递归删除只需要知道「是否为空」，因此它把链接当普通内容算“非空”；
     *   但后端隐藏的条目仍参与“是否为空”的判断，否则含悬空链接的目录会被误判为空。
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
                // 列举条目里的符号链接同样不跟随（T12 §2.2）。
                LocalPath.resolve(root, path.resolve(name).segments)
            }
            val attributes = attributesOf(entry.metadata)
            children += StorageEntry(name, attributes.type, attributes)
        }
        refuseHiddenEntries(LocalPath.resolve(root, path.segments), reported, refuseSymbolicLinks)
        return children
    }

    /**
     * 物理目录里存在、后端没返回的条目（悬空符号链接等）→ 拒绝。
     *
     * 必须在任何破坏性操作之前做完：`delete` 先走完规划阶段再动手，所以这里的抛出发生在“一个字节都没删”时。
     * @param refuseSymbolicLinks `false` 时（非递归删除只关心是否为空）不报链接拒绝，而是算作「非空」，
     *   保持 [VfsErrorCode.DIRECTORY_NOT_EMPTY] 语义。
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
                    // 非递归删除只需要知道「是否为空」，所以不可见条目一律算非空。
                    throw VfsException(VfsErrorCode.DIRECTORY_NOT_EMPTY, "directory is not empty")
                }
                if (Files.isSymbolicLink(child)) {
                    throw VfsException(VfsErrorCode.STORAGE_ACCESS_DENIED, "symbolic links are not followed")
                }
                // 不是链接却只有本地看得到：后端与本地对不上，不猜原因。
                throw VfsException(VfsErrorCode.STORAGE_ERROR, "local storage listing is inconsistent with the physical directory")
            }
        }
    }

    /** 递归删除的规划阶段：只读取和校验，不删除，因此遇到符号链接时副作用为零。结果是后序（最深在前）。 */
    private fun collectForDeletion(
        path: StoragePath,
        plan: MutableList<Pair<StoragePath, NodeType>>,
    ) {
        for (entry in listChildren(path)) {
            // listChildren 已对每个子项做过完整闸门（含路径解析与隐藏条目核对），不再重复。
            val child = path.resolve(entry.name)
            // 后序遍历：先删子树再删自己，保证每一步目标都是空的。
            if (entry.type == NodeType.DIRECTORY) {
                collectForDeletion(child, plan)
            }
            plan += child to entry.type
        }
    }

    /**
     * 单次打开的流：重复打开报状态错误，关闭后打开报 CLOSED。
     *
     * **关闭语义**：reader 由 [ReaderHandle] 持有，本类与 [StorageInputStream] 都不是所有者，
     * 只是两个都可以触发释放的入口，而 [ReaderHandle] 的闸门保证**恰好释放一次**。
     *
     * 三种关闭顺序的确定结果：
     *
     * 1. 先关流、再关 Adapter：`openStream()` 的返回值或本类的 `close()` 释放 reader；
     *    Adapter 关闭时登记表已空，排空是空操作。
     * 2. Adapter 先关：写锁内先排空释放全部 reader，再释放 Operator——reader 一定在 Operator 还活着时关闭。
     *    之后调用方再 `close()` 是**幂等空操作，不报错也不泄漏**。
     * 3. 流没打开就关 Adapter：reader 同样由排空释放；本类的 `close()` 之后是空操作。
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
            // 不经过 lifetime：Adapter 已关闭时这仍必须能释放或空转，否则 reader 就漏了。
            mapStorageErrors("close read stream") { handle.release() }
        }
    }
}
