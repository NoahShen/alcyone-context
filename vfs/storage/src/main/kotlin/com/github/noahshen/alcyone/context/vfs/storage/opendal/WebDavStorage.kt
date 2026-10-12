package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
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
import org.apache.opendal.WriteOptions
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 VFS 的文件操作落到远端 WebDAV 服务器上，底层用 Apache OpenDAL 的 `webdav` 服务（T24）。
 *
 * 例：endpoint `http://127.0.0.1:8080`、远端根 `/dav/team`、路径 `报告/a.txt`，
 * 实际动的就是 `http://127.0.0.1:8080/dav/team/报告/a.txt`。它只管和服务器说话，
 * 不生成 Node ID、不写事件、不碰状态库。
 *
 * **这一轮做完整闭环**（T25）：`stat` / `list` / 建目录 / 有界读 / 流式读 / 写 / 删除 / 原生文件移动。
 * 关键边界：
 *
 * - **原生文件移动**（T25 A03）：实测 OpenDAL 0.50.6 webdav `capability.rename == true`，文件 rename 可用；
 *   Adapter 在 rename 之前补 Port 要求的「目标全新」「父目录存在」检查（后端 rename 会覆盖目标、会顺手建父目录）。
 *   目录 rename 实测返回 `IsADirectory`，能力表 `nativeDirectoryMove` 仍报 `false`，目录移动交给 Core 整树复制回退。
 * - **流式读取**（T25 A02）：复用 [StorageInputStream] / [ReaderHandle] / [NativeLifetime] 机制，按**实际读出字节**计限。
 *
 * **不预检连接**：打开 Adapter 不发网络请求，第一次真正操作才连服务器。
 * 这样配置校验阶段（还没碰后端）和后端故障阶段分得开，也避免为了「开一下」就去动远端目录。
 *
 * 本地盘那套符号链接 / JDK 目录枚举的检查不搬过来——客户端无法一概识别服务端链接，
 * 边界依赖服务端配置（WsgiDAV 默认不暴露符号链接语义，但其他服务器可能不同）；
 * 这里的每条规则都按**观察到的 OpenDAL webdav 行为**写，测试服务是 WsgiDAV（T24 §2.4）。
 *
 * **账号边界**（任务 §2.2）：如果账号决定服务器可见的根（不同用户看到不同的根），
 * VFS 无法识别两个不同账号的 URL 是否指向同一份数据——身份串只由 endpoint + root 决定，
 * 不含用户名。换用户名不一定是同一份数据；换密码也不是换盘。密码轮换可接受，
 * 但不能声称换用户名永远是同一份数据。
 *
 * 后端行为里有四条和本地盘不一样，所以这里各补了一次显式检查：
 *
 * - 删除不存在的目标**返回成功**（本地盘也是），所以先 `stat` 再删，缺目标报 `NOT_FOUND`。
 * - 删除非空目录**返回成功**（服务按规范递归删），所以非递归删先列一层，非空报 `DIRECTORY_NOT_EMPTY`。
 * - 列举不存在的目录**返回空**，所以 `list` 先 `stat`。
 * - 认证失败是 HTTP 401，OpenDAL 报 `Unexpected`；错误转换按响应行 `status: 401` 认成
 *   `STORAGE_ACCESS_DENIED`（见 [mapStorageErrors]）。
 */
class WebDavStorage private constructor(
    private val operator: Operator,
    /** 整理后的远端根，例如 `/dav/team`。**不含 endpoint 与凭据**，只用于诊断。 */
    val root: String,
    private val capabilities: StorageCapabilities,
) : StorageAdapter {
    /**
     * 防止「一边操作一边关闭」，和 [LocalFsStorage] 共用同一把锁。
     *
     * OpenDAL 的 [Operator] 关掉之后再用会让 JVM 崩溃，所以每个操作都经 [NativeLifetime] 加锁，
     * 关闭时等进行中的操作收尾。关闭幂等。
     *
     * 释放分三步，**顺序不能变**：先把还开着的文件流尽量关掉，再关 Operator，最后统一报错。
     * 这样一次 `openStream` 之后被 Runtime 遗忘的流，也会在关存储时清理掉。
     */
    private val lifetime =
        NativeLifetime(label = "webdav storage") {
            val failures = mutableListOf<Throwable>()
            drainReaders(failures)
            try {
                operator.close()
            } catch (e: Throwable) {
                failures += e
            }
            // 第一个失败当主异常，其余挂在 suppressed 上，一个都不吞。
            failures.firstOrNull()?.let { first ->
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }

    /** 已经打开、但还没关闭的读文件流。关闭存储时要把它们都关掉。 */
    private val liveReaders = ConcurrentHashMap.newKeySet<ReaderHandle>()

    companion object {
        /**
         * 打开一个 WebDAV 存储。**不发网络请求**，所以端点不可达时这里不会失败，
         * 第一次真正操作才报错。
         *
         * [endpoint] / [root] 必须合法（[WebDavRoots.normalize]），凭据只进这里、不进身份串。
         */
        suspend fun create(
            endpoint: String,
            root: String,
            username: String = "",
            password: String = "",
        ): WebDavStorage {
            val (normalizedEndpoint, normalizedRoot) = WebDavRoots.normalize(endpoint, root)
            var opened: Operator? = null
            return handoffOrRelease(release = { opened?.close() }) {
                storageCall("open adapter", backend = "webdav storage") {
                    val operator =
                        Operator.of(
                            "webdav",
                            buildMap {
                                put("endpoint", normalizedEndpoint)
                                put("root", normalizedRoot)
                                // 匿名服务不给凭据：给了反而会被服务器当成一次失败的登录。
                                if (username.isNotEmpty()) {
                                    put("username", username)
                                    put("password", password)
                                }
                            },
                        )
                    opened = operator
                    WebDavStorage(operator, normalizedRoot, capabilitiesOf(operator))
                }
            }
        }

        /**
         * 声明这块盘支持哪些操作。宁可报少也不要多报。
         *
         * `nativeFileMove` 跟随后端 `rename` 能力（T25 实测验过文件 rename 可用，与真实服务的能力表一致）；
         * `nativeDirectoryMove` 恒为 `false`：实测 webdav rename 目录返回 `IsADirectory`，目录移动走 Core 复制回退。
         */
        private fun capabilitiesOf(operator: Operator): StorageCapabilities {
            val capability = operator.info.capability
            return StorageCapabilities(
                // 与本地盘同一条口径：没有 stat 就做不了「目标全新」的预检，不能自称原生移动。
                nativeFileMove = capability.rename && capability.stat,
                nativeDirectoryMove = false,
                createDirectory = capability.createDir,
                boundedRead = capability.read,
                readOnly = false,
            )
        }
    }

    override fun close() = lifetime.close()

    fun isClosed(): Boolean = lifetime.isClosed()

    /**
     * 能力值是打开时定好的常量，不碰后端，所以这里故意不加锁，
     * 关闭之后也能查——否则一次慢操作就能把能力查询一起堵住。
     */
    override fun capabilities(): StorageCapabilities = capabilities

    override suspend fun stat(path: StoragePath): StorageAttributes =
        call("stat") {
            lifetime.call { statOrFail(plain(path), "stat") }
        }

    override suspend fun read(
        path: StoragePath,
        maxBytes: Long,
    ): StorageContent {
        if (path.isRoot) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "mount root is a directory")
        if (maxBytes < 0) throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "maxBytes must not be negative")
        val attributes = call("read precheck") { lifetime.call { statOrFail(plain(path), "read") } }
        if (attributes.type == NodeType.DIRECTORY) {
            throw VfsException(VfsErrorCode.TYPE_MISMATCH, "read target is a directory")
        }
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
     * 流式读取（T25 A02）：复用 [LocalFsStorage] 那套 reader 所有权与 native 生命周期机制。
     *
     * 关流顺序没有要求，两种都行：
     * - 先关流再关存储：一切正常。
     * - 先关存储：存储关闭时会把还开着的文件流都关掉，之后再关流是空操作，不报错。
     *
     * 限额按**实际读出字节**计（[StorageInputStream]），不看 stat 长度；`maxBytes = null` 表示无界。
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
                        WebDavStream(attributes, handle, maxBytes)
                    } catch (e: Throwable) {
                        handle.release()
                        throw e
                    }.also { opened = it }
                }
            }
        }
    }

    /**
     * 读文件前的检查：拒绝「拿文件当目录」的情况，然后查出文件信息。
     * 查服务器是阻塞的，因此切到后台线程执行。
     */
    private suspend fun precheckForRead(path: StoragePath): StorageAttributes =
        call("read precheck") {
            lifetime.call { statOrFail(plain(path), "read") }
        }

    /** 单层完整列举：不含自身与孙级，不承诺排序。 */
    override suspend fun list(path: StoragePath): List<StorageEntry> =
        call("list") {
            lifetime.call {
                val attributes = statOrFail(plain(path), "list")
                if (attributes.type == NodeType.FILE) {
                    throw VfsException(VfsErrorCode.TYPE_MISMATCH, "list target is not a directory")
                }
                listChildren(path)
            }
        }

    /**
     * 写文件。三个阶段的效果和本地盘一致：先检查（`NONE`）→ 动手写（`UNKNOWN`）→ 复查（`PARTIAL`）。
     *
     * 三个写入模式都靠**提前查**文件信息判断，这一步不是原子的：查完到真正 PUT 之前，
     * 别的客户端可能改了目标。服务端不保证「不存在才写」时（见 [conditionalCreateAvailable]），
     * `CREATE_NEW` 只有这一层检查。
     */
    override suspend fun write(
        path: StoragePath,
        content: ByteArray,
        mode: StorageWriteMode,
    ): StorageAttributes {
        if (path.isRoot) throw VfsException(VfsErrorCode.TYPE_MISMATCH, "cannot write to the mount root")
        // 第一步：先检查，一个字节都还没写。这时失败就是「什么都没做」。
        call("write", VfsEffect.NONE) {
            lifetime.call {
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
            afterChange { lifetime.call { statOrFail(plain(path), "write", VfsEffect.PARTIAL) } }
        }
    }

    /**
     * 建目录。已经存在就算成功。
     *
     * 检查阶段失败是「什么都没建」；进了后端再失败就是「结果不明」——
     * 服务端是否顺带补上级目录说不清，所以这里不替它猜。
     */
    override suspend fun createDirectory(path: StoragePath) {
        // 挂载根本身不用建，但「已关闭」还是要照报。
        if (path.isRoot) {
            lifetime.call { }
            return
        }
        val existing =
            call("create directory", VfsEffect.NONE) {
                lifetime.call { metadataOrNull(plain(path)) }
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

    /**
     * 原生文件移动（T25 A03）。遵守 Port 语义：目标全新、源存在、目标父目录存在。
     *
     * 实测 OpenDAL 0.50.6 webdav rename 可用，但行为与 Port 要求不一致，所以在这里补检查：
     * - 目标已存在时会覆盖而不是报错 → 必须提前检查目标不存在（[ALREADY_EXISTS]）；
     * - 目标父目录不存在时会顺手建出来 → 必须提前检查父目录存在（[NOT_FOUND]）；
     * - 源不存在报 [NOT_FOUND]；目录移动报 [UNSUPPORTED_OPERATION]（实测 rename 目录返回 `IsADirectory`）。
     *
     * 三个阶段：先检查（`NONE`）→ reshape（`UNKNOWN`）→ 复查目标（`PARTIAL`）。
     * 探测到的已存在 / 缺失是**预检**，不对并发提供原子保障；真正 rename 失败时从失败点证明不了结果，
     * 保留后端 code / effect（含 UNKNOWN）不降级。
     */
    override suspend fun move(
        source: StoragePath,
        target: StoragePath,
    ): StorageAttributes {
        if (source.isRoot || target.isRoot) throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "cannot move the mount root")
        // 第一步：检查源类型、目标是否全新、目标父目录是否存在。
        call("move", VfsEffect.NONE) {
            lifetime.call {
                val sourceAttributes = statOrFail(plain(source), "move")
                if (sourceAttributes.type == NodeType.DIRECTORY) {
                    throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "native directory move is not supported by the webdav backend")
                }
                if (metadataOrNull(plain(target)) != null) {
                    throw VfsException(VfsErrorCode.ALREADY_EXISTS, "move target already exists")
                }
                requireExistingParentDirectory(target)
            }
        }
        // 第二步：真正 rename。失败时分不清改没改，所以只能说「结果不明」。
        call("move content", VfsEffect.UNKNOWN) {
            lifetime.call { operator.rename(plain(source), plain(target)) }
        }
        // 第三步：再查一次目标确认。改名已经成功了，查不到就说「已经改了东西」。
        return call("move attributes", VfsEffect.PARTIAL) {
            afterChange { lifetime.call { statOrFail(plain(target), "move", VfsEffect.PARTIAL) } }
        }
    }

    /**
     * 删除文件或目录。不存在报 `NOT_FOUND`；目录不递归删时里面还有东西报 `DIRECTORY_NOT_EMPTY`。
     *
     * 实际 DELETE 请求边界使用保守 effect（R2 修复）：文件、非递归空目录、递归删的第一项都可能在
     * 「服务端已删除但回包丢失」时失败——从失败点证明不了「什么都没删」，所以报 `UNKNOWN`。
     * 递归删中途失败时已经删掉过几个就报 `PARTIAL`。
     *
     * 递归删先排好顺序（这一步只看不删），再从最深的往上删。
     */
    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    ) {
        if (path.isRoot) throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "cannot delete the mount root")
        call("delete") {
            lifetime.call {
                val attributes = statOrFail(plain(path), "delete")
                if (attributes.type == NodeType.FILE) {
                    deleteConservatively(plain(path))
                } else if (!recursive) {
                    // 后端删非空目录也会报成功，所以空不空必须自己看。
                    if (listChildren(path).isNotEmpty()) {
                        throw VfsException(VfsErrorCode.DIRECTORY_NOT_EMPTY, "directory is not empty")
                    }
                    deleteConservatively(dirPath(path))
                } else {
                    val plan = mutableListOf<Pair<StoragePath, NodeType>>()
                    collectForDeletion(path, plan)
                    plan += path to NodeType.DIRECTORY
                    var removed = 0
                    plan.forEach { (target, type) ->
                        // 目录必须带结尾的 `/`，否则后端会当成文件去删，空目录也会失败。
                        val targetPath = if (type == NodeType.DIRECTORY) dirPath(target) else plain(target)
                        deleteConservatively(targetPath, if (removed == 0) VfsEffect.UNKNOWN else VfsEffect.PARTIAL)
                        removed++
                    }
                }
            }
        }
    }

    /**
     * 实际发 DELETE 请求。
     *
     * 默认 effect 是 [VfsEffect.UNKNOWN]：服务端可能已经删掉了目标只是回包丢了，
     * 从失败点证明不了「什么都没删」，所以保守报「结果不明」。调用方递归删时传 `PARTIAL`。
     */
    private fun deleteConservatively(
        backendPath: String,
        effect: VfsEffect = VfsEffect.UNKNOWN,
    ) {
        mapStorageErrors("delete", effect, backend = "webdav storage") {
            operator.delete(backendPath)
        }
    }

    /** 后端支不支持「不存在才写」。不支持的话，CREATE_NEW 只能靠提前查。 */
    private val conditionalCreateAvailable: Boolean = operator.info.capability.writeWithIfNotExists

    /** 每个存储操作都从这里走：换到后台线程、把异常翻译成 VFS 错误码。 */
    private suspend fun <T> call(
        operation: String,
        effect: VfsEffect = VfsEffect.NONE,
        block: () -> T,
    ): T = storageCall(operation, effect, backend = "webdav storage", block = block)

    /**
     * 交给后端的路径。**只编码一次**：这里给出的是已解码的逻辑相对路径，
     * 百分号编码由 OpenDAL 在拼 URL 时做，这一层不再自己转义。
     *
     * 挂载根交给后端的是空串——`toRelativeString()` 对根返回 `.`，那是本地盘的写法，
     * 当远端路径用会被服务器当成一个叫 `.` 的条目。
     */
    private fun plain(path: StoragePath): String = if (path.isRoot) "" else path.toRelativeString()

    /** 交给后端的目录路径：必须带结尾的 `/`，否则后端只认得出它自己；根用空串。 */
    private fun dirPath(path: StoragePath): String = if (path.isRoot) "" else "${path.toRelativeString()}/"

    private fun metadataOrNull(backendPath: String): Metadata? =
        try {
            operator.stat(backendPath)
        } catch (e: OpenDALException) {
            if (e.code == OpenDALException.Code.NotFound) null else throw e
        }

    /**
     * 已经改动成功之后的收尾步骤（比如写完之后再查一次文件信息）。
     *
     * 这一步不管怎么失败，都已经改过东西了，所以 [VfsEffect] 至少是 [VfsEffect.PARTIAL]；
     * 还带着 `NONE` 的重新标一次（[mapStorageErrors] 对已是 [VfsException] 的异常原样抛出，不会补）。
     */
    private fun <T> afterChange(block: () -> T): T =
        try {
            block()
        } catch (e: VfsException) {
            if (e.effect != VfsEffect.NONE) throw e
            throw VfsException(
                e.code,
                e.message ?: e.code.name,
                e.uri,
                e.operationId,
                VfsEffect.PARTIAL,
            ).apply { e.cause?.let { initCause(it) } }
        }

    /**
     * 查文件信息，查不到就报 `NOT_FOUND`。
     *
     * [effect]：这一步失败时到底有没有改动，取决于调用方。检查阶段传 `NONE`；
     * 改动已经成功之后传 `PARTIAL`，不然「已经写进去了」这个事实就丢了。
     */
    private fun statOrFail(
        backendPath: String,
        operation: String,
        effect: VfsEffect = VfsEffect.NONE,
    ): StorageAttributes =
        metadataOrNull(backendPath)?.let { attributesOf(it, effect) }
            ?: throw VfsException(
                VfsErrorCode.NOT_FOUND,
                "webdav storage $operation target does not exist",
                effect = effect,
            )

    /** 查文件类型。后端说不上来是什么就报错，不猜。 */
    private fun typeOf(
        metadata: Metadata,
        effect: VfsEffect = VfsEffect.NONE,
    ): NodeType =
        when (metadata.mode) {
            Metadata.EntryMode.FILE -> NodeType.FILE
            Metadata.EntryMode.DIR -> NodeType.DIRECTORY
            Metadata.EntryMode.UNKNOWN -> throw VfsException(
                VfsErrorCode.STORAGE_ERROR,
                "webdav storage returned an unknown entry type",
                effect = effect,
            )
        }

    private fun attributesOf(
        metadata: Metadata,
        effect: VfsEffect = VfsEffect.NONE,
    ): StorageAttributes =
        StorageAttributes(
            type = typeOf(metadata, effect),
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
     * 列出一个目录里的东西。只列一层，不含目录自己和更深层，顺序不保证。
     *
     * OpenDAL 把条目按**操作员相对路径**回给调用方，另外两条实测出来的写法差异也在这里消化掉：
     *
     * - 查挂载根（空前缀）时自己报 `/`，子项不带前缀；查子目录时自己报 `sub/`，子项带 `sub/`；
     * - **目录子项带结尾 `/`**（`sub/报告/`），文件子项不带。
     *
     * 所以先按前缀切掉、再去掉结尾的 `/`，剩下的必须是恰好一个段；切不干净就是后端越界了，直接报错不猜。
     */
    private fun listChildren(path: StoragePath): List<StorageEntry> {
        val prefix = dirPath(path)
        val children = mutableListOf<StorageEntry>()
        for (entry in operator.list(prefix)) {
            val raw = entry.path
            if (raw == prefix || raw == "/") continue
            val relative = if (prefix.isEmpty()) raw else raw.removePrefix(prefix)
            val name = relative.removeSuffix("/")
            if (name.isEmpty() || '/' in name) {
                throw VfsException(
                    VfsErrorCode.STORAGE_ERROR,
                    "webdav storage listed an entry outside the requested directory (prefix='$prefix', entry='$raw')",
                )
            }
            val attributes = attributesOf(entry.metadata)
            children += StorageEntry(name, attributes.type, attributes)
        }
        return children
    }

    /** 把要删的条目按「最深的先删」排好。这一步只看不删，所以任何失败都还没有副作用。 */
    private fun collectForDeletion(
        path: StoragePath,
        plan: MutableList<Pair<StoragePath, NodeType>>,
    ) {
        for (entry in listChildren(path)) {
            val child = path.resolve(entry.name)
            // 先把里面的删完再删自己，保证每一步目标都是空的。
            if (entry.type == NodeType.DIRECTORY) {
                collectForDeletion(child, plan)
            }
            plan += child to entry.type
        }
    }

    /** 打开文件流并记进 [liveReaders]，保证关闭存储时不会漏掉被 Runtime 遗忘的流。 */
    private fun newReader(backendPath: String): ReaderHandle {
        val handle =
            ReaderHandle(
                operator.createInputStream(backendPath),
                onReleased = { liveReaders.remove(it) },
                // 后端名传下去：读流在释放后再读时，消息要说清是哪块盘（R2 修复）。
                label = "webdav storage",
            )
        liveReaders += handle
        return handle
    }

    /**
     * 尽量把所有还开着的文件流都关掉，关不掉的记进 [failures]，**不中断其余**。
     *
     * 场景：三个流里有一个关的时候后端报错。正确的做法是三个都试一次，而不是关掉第一个就冒出去——
     * 那样另外两个文件句柄会一直被占着。由 [lifetime] 在关闭存储时调用。
     */
    private fun drainReaders(failures: MutableList<Throwable>) {
        // 先取快照：关流时会把自身从 liveReaders 摘掉，边遍历边改容易漏。
        for (handle in liveReaders.toList()) {
            try {
                handle.release()
            } catch (e: Throwable) {
                failures += storageCleanupFailure(e)
            }
        }
        liveReaders.clear()
    }

    /**
     * 把清理失败翻译成 VFS 错误码（原始异常挂在 `cause` 上）；翻译不了就保留原异常。
     *
     * 这一步自身也不能抛：它正在关停途中，弄不好会让后面的资源跟着漏掉。
     */
    private fun storageCleanupFailure(cause: Throwable): Throwable =
        try {
            mapStorageErrors("close read stream", backend = "webdav storage") { throw cause }
        } catch (mapped: Throwable) {
            mapped
        }

    /**
     * 一个只能打开一次的读文件流（复用 [LocalFsStorage] 的所有权机制）。
     *
     * 文件流实际由 [ReaderHandle] 保管，本类和 [StorageInputStream] 都只是能触发关闭的入口，
     * 所以谁先关都行，而且只会关一次。三种顺序的结果：
     *
     * 1. 先关流：正常关掉。
     * 2. 先关存储：存储关闭时会把还开着的文件流都关掉，之后再关这个流是空操作，不报错。
     * 3. 还没打开就关存储：一样由存储关掉，之后再关也是空操作。
     */
    private inner class WebDavStream(
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
            mapStorageErrors("close read stream", backend = "webdav storage") { handle.release() }
        }
    }
}
