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

/**
 * 把 VFS 的文件操作落到远端 WebDAV 服务器上，底层用 Apache OpenDAL 的 `webdav` 服务（T24）。
 *
 * 例：endpoint `http://127.0.0.1:8080`、远端根 `/dav/team`、路径 `报告/a.txt`，
 * 实际动的就是 `http://127.0.0.1:8080/dav/team/报告/a.txt`。它只管和服务器说话，
 * 不生成 Node ID、不写事件、不碰状态库。
 *
 * **这一轮只做最小闭环**：`stat` / `list` / 建目录 / 有界读 / 写 / 删除。两件已知边界：
 *
 * - **不做移动**：`move` 直接 `UNSUPPORTED_OPERATION`，能力表里 `nativeFileMove` / `nativeDirectoryMove`
 *   也报 `false`，于是 Core 走复制回退而不是调这里的 `move`（rename 的完整验收留 T25）。
 * - **不做流式读取**：`readStream` 直接 `UNSUPPORTED_OPERATION`；`read` 走有界读，行为与本地盘一致。
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
     */
    private val lifetime = NativeLifetime(label = "webdav storage") { operator.close() }

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
         * `nativeFileMove` / `nativeDirectoryMove` 恒为 `false`：rename 的完整行为这一轮没有验收，
         * 与其报了再让 Core 调一个没验过的 `move`，不如报 `false`，Core 自然走复制回退（T25 验收）。
         */
        private fun capabilitiesOf(operator: Operator): StorageCapabilities {
            val capability = operator.info.capability
            return StorageCapabilities(
                nativeFileMove = false,
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
     * 流式读取这一轮**明确阶段拒绝**：流的所有权、关流顺序和网络半截失败都还没验收（T25）。
     *
     * 拒绝发生在碰到服务器之前，所以它不会留下任何远端副作用。
     */
    override suspend fun readStream(
        path: StoragePath,
        maxBytes: Long?,
    ): StorageStream =
        throw VfsException(
            VfsErrorCode.UNSUPPORTED_OPERATION,
            "streaming reads are not supported by the webdav storage in this release; use the bounded read instead",
        )

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
     * 改名 / 移动：这一轮**明确阶段拒绝**，能力表也不声称支持，Core 不会走到这里。
     *
     * 与 [readStream] 同一条理由：rename 的完整行为（目录、并发、错误码）留到 T25 验收，
     * 不用空实现或「假成功」蒙混过去。
     */
    override suspend fun move(
        source: StoragePath,
        target: StoragePath,
    ): StorageAttributes =
        throw VfsException(
            VfsErrorCode.UNSUPPORTED_OPERATION,
            "native move is not supported by the webdav storage in this release; moves go through the copy fallback",
        )

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

    /** 打开文件流：`read` 里自己关，所以不用像本地盘那样登记到待关闭列表（`readStream` 已被拒绝）。 */
    private fun newReader(backendPath: String): ReaderHandle = ReaderHandle(operator.createInputStream(backendPath))
}
