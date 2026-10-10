package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.VfsLimits
import com.github.noahshen.alcyone.context.vfs.core.event.AsyncEventNotifier
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsRoots
import com.github.noahshen.alcyone.context.vfs.storage.opendal.WebDavRoots
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 挂载背后是什么盘：本机目录，还是一台 WebDAV 服务上的一个根。
 *
 * 两类后端各是一个子类型，配置写错了在 [VfsRuntimeConfig.resolve] 里就拒；
 * Core / API 看不到 endpoint、凭据，也看不到 OpenDAL 的类型（T24 §2.1）。
 */
sealed interface MountBackend {
    /**
     * 挂载身份串，写进 `mount` 表的 `physical_root`，也是重开时比对的依据。
     * Local FS 是规范化后的绝对路径，WebDAV 是整理后的 `endpoint + root`；**都不含凭据**。
     */
    fun identity(): String

    /** 本机目录，必须已经存在。规范化（消解符号链接）在 [VfsRuntimeConfig.resolve] 里做。 */
    data class LocalFs(
        val root: Path,
    ) : MountBackend {
        override fun identity(): String = root.toString()
    }

    /**
     * 远端 WebDAV 服务上的一个根（T24）。
     *
     * - [endpoint] 只接受 `http://` / `https://`，**不许**把账号密码写成 `user:pass@host`；
     * - [root] 是服务端的共享根，例如 `/dav/team`；
     * - [username] / [password] 只活在配置里：不进 `mount` 表、不进身份串、不进任何错误消息。
     *
     * [toString] 不回显 endpoint 中的 userinfo、不打印 username、把密码遮成 `***`——
     * 配置在 resolve 之前就能打印，错误配置也不能泄密（R4 修复）。
     */
    data class WebDav(
        val endpoint: String,
        val root: String,
        val username: String = "",
        val password: String = "",
    ) : MountBackend {
        override fun identity(): String = WebDavRoots.identity(endpoint, root)

        override fun toString(): String = "WebDav(endpoint=${safeEndpoint()}, root=$root, password=${redacted()})"

        /**
         * 安全显示 endpoint：有 userinfo 时只留 `scheme` + `://` + `***@` + `host:port`，不显示用户名与密码。
         *
         * 配置对象在 [VfsRuntimeConfig.resolve] 之前就能打印，非法的 userinfo 写法也不能把凭据带出去。
         * 主机非法（如 `bad host`）时 Java URI 只保留 registry authority、`host` 为 null，
         * 这时整串原文不回显，用固定占位——原文可能带着凭据。
         */
        private fun safeEndpoint(): String {
            val uri = runCatching { URI(endpoint) }.getOrNull()
            val scheme = uri?.scheme
            val host = uri?.host
            if (uri == null || scheme == null || host == null) return "(unprintable endpoint)"
            if (uri.userInfo == null) return endpoint
            val port = uri.port
            return buildString {
                append(scheme)
                    .append(":")
                    .append("//")
                    .append("***@")
                    .append(host)
                if (port != -1) append(':').append(port)
            }
        }

        private fun redacted(): String = if (password.isEmpty()) "(none)" else "***"
    }
}

/**
 * 一块挂载要描述什么：一个逻辑位置、一个实例标识、一个后端。
 *
 * 例：`resources` 命名空间下把 `/resources/docs` 挂到 `/Users/me/docs`；
 * 或者把 `/resources/remote` 挂到 `http://127.0.0.1:8080` 的 `/dav/team`。
 */
data class MountConfig(
    /** 挂载点的逻辑路径，必须在已配置的命名空间下，且不能是逻辑根 `/`。 */
    val vfsPath: VfsPath,
    /** 实例标识，只作内部唯一键用，不当凭据看；重开时它变了会按冲突拒绝。 */
    val storageKey: String,
    /** 这块盘是什么：本机目录还是 WebDAV 上的根。 */
    val backend: MountBackend,
) {
    /** 既有本地写法：`MountConfig(path, key, Path.of("/data/docs"))`，等价于 [MountBackend.LocalFs]。 */
    constructor(
        vfsPath: VfsPath,
        storageKey: String,
        root: Path,
    ) : this(vfsPath, storageKey, MountBackend.LocalFs(root))
}

/**
 * Runtime 的全部配置。宿主**只提交这个对象**给 [AlcyoneVfs.create]，
 * 自己不创建 Operator、Driver、Repository 或各 Manager。
 *
 * 例：状态库放 `data/state.db`，命名空间 `resources` / `memory`，各挂一块本机目录，读写限额都是 16 MiB。
 *
 * 一块挂载的后端是本机目录还是 WebDAV，由 [MountBackend] 子类型说清（T24）；
 * Core / API 只拿到 Storage Port，拿不到 endpoint、凭据或 OpenDAL 类型。
 * 公共流式读取 [AlcyoneVfs.openStream] 与关闭等待期都在实例里做。
 */
data class VfsRuntimeConfig(
    /** 状态库文件。相对路径按当前工作目录解析；符号链接别名会被规范化成同一个真实路径。 */
    val stateDatabase: Path,
    /** 顶级命名空间，例如 `setOf("resources", "memory")`。这是配置给的，不是 API 白名单。 */
    val namespaces: Set<String>,
    /** 挂载列表，至少一块（没有挂载时只有配置目录可导航）。 */
    val mounts: List<MountConfig>,
    /** 读写的默认限额。 */
    val limits: VfsLimits = VfsLimits(),
    /**
     * `openStream` 的总量上限（字节）。`null` = 不设总量上限，仍然分块读，不会把整个文件读进内存。
     *
     * 和 [limits] 分开：`limits` 管的是 ByteArray 读，16 MiB 默认不变；这里管的是一条流累计读到的字节数。
     * 调用方的 `VfsStreamOptions.maxTotalBytes` 只能进一步收紧它。
     */
    val streamTotalLimit: Long? = null,
    /** 进程内事件队列容量，沿用 T14 的有界通知。 */
    val eventBufferCapacity: Int = AsyncEventNotifier.DEFAULT_CAPACITY,
    /**
     * 关闭时等待在途操作的期限。进入 closing 之后，已接纳的操作在这段时间里可以自己跑完；
     * 到点还在跑的会被**请求取消**（协作取消，不是强杀）。
     */
    val closeGracePeriod: Duration = 30.seconds,
)

/**
 * 校验并整理后的配置：后面每一步都直接用它的结果，不再回头看原始入参。
 *
 * 例：原始配置写的是 `~/docs`（其实是相对路径 + 符号链接），这里拿到的是已经消解过的真实绝对路径。
 */
internal class ResolvedConfig(
    /** 规范化后的状态库绝对路径；相对路径与符号链接别名到这里已经收敛成同一个。 */
    val databasePath: Path,
    /** 独占锁文件，和状态库同目录。 */
    val lockPath: Path,
    val namespaces: Set<String>,
    /** 挂载记录，身份三件套（key + 后端类型 + 规范化物理根）已填好，路由直接可用。 */
    val mounts: List<MountRecord>,
    /** 路由视图：逻辑挂载与配置目录（T09 的校验一次做完）。 */
    val router: MountRouter,
    /** 每个 storageKey 对应的整理后后端（Local FS 已规范化路径，WebDAV 已整理 endpoint / root）；同一 key 只开一个存储实例。 */
    val backends: Map<String, MountBackend>,
    val limits: VfsLimits,
    val streamTotalLimit: Long?,
    val eventBufferCapacity: Int,
    val closeGracePeriod: Duration,
) {
    companion object {
        /** Local FS 在挂载身份里的后端类型；换后端时这一项也参与冲突判断。 */
        const val LOCAL_FS_BACKEND_TYPE: String = "local-fs"

        /** WebDAV 在挂载身份里的后端类型（T24）。 */
        const val WEBDAV_BACKEND_TYPE: String = "webdav"

        /** 挂载身份里的后端类型：按后端子类型分，不按配置写法分。 */
        fun backendTypeOf(backend: MountBackend): String =
            when (backend) {
                is MountBackend.LocalFs -> LOCAL_FS_BACKEND_TYPE
                is MountBackend.WebDav -> WEBDAV_BACKEND_TYPE
            }
    }
}

/**
 * 校验并整理配置。**在任何资源被创建之前跑完**：非法配置在这里就拒，不开库、不开 Adapter、不改系统环境。
 *
 * 做的事：命名空间与挂载的合法性交给 T09 的 [MountRouter.of]；物理根交给 T12 的
 * [LocalFsRoots.requireNonOverlapping] 规范化并查重叠；状态库路径收敛成真实绝对路径。
 *
 * 例：两个挂载共用同一个 storageKey 与同一个目录时按**一块盘**处理（只开一个实例），不算重叠；
 * 两个不同的目录互相包含才是重叠，配置在这里就被拒。
 */
internal fun VfsRuntimeConfig.resolve(): ResolvedConfig {
    // 能在这里判的数值先判掉：非法值不必等到真的要建通知器或者真的要关闭时才报。
    if (streamTotalLimit != null && streamTotalLimit < 0) {
        throw invalidArgument("streamTotalLimit must not be negative: $streamTotalLimit")
    }
    if (closeGracePeriod.isNegative()) {
        throw invalidArgument("closeGracePeriod must not be negative: $closeGracePeriod")
    }
    val keys = mounts.map { it.storageKey }
    keys.forEach { key ->
        if (key.isBlank() || key.any { it.isWhitespace() || it.isISOControl() }) {
            throw invalidArgument("storageKey must be a non-blank name without whitespace or control characters")
        }
    }
    // 一个 storageKey 只能对应一个盘：Local FS 先把目录消解成真实路径（T12），WebDAV 先整理 endpoint / root，
    // 再按 key 归并。同一个 key 写成两个目标时，后一条会被静默丢掉、读写却都落到第一条，所以直接拒。
    val backendsByKey = LinkedHashMap<String, MountBackend>()
    mounts.forEach { mount ->
        val normalized = normalizeBackend(mount.backend)
        val already = backendsByKey.putIfAbsent(mount.storageKey, normalized)
        if (already != null && already != normalized) {
            throw invalidArgument("storage key '${mount.storageKey}' is configured with more than one storage target")
        }
    }
    // 本地目录必须已经存在、且彼此不重叠；远端根按完整路径段查重叠。同一个目标挂两次在归并阶段已经变成一块盘。
    LocalFsRoots.requireNonOverlapping(localRoots(backendsByKey))
    WebDavRoots.requireNonOverlapping(webDavTargets(backendsByKey))

    val records =
        mounts.map { mount ->
            val backend = backendsByKey.getValue(mount.storageKey)
            MountRecord(
                path = mount.vfsPath,
                storageKey = mount.storageKey,
                backendType = ResolvedConfig.backendTypeOf(backend),
                physicalRoot = backend.identity(),
            )
        }
    // 逻辑侧校验：挂载不能是逻辑根、不能在未配置命名空间下、不能重复（T02 §3.1）。
    val router = MountRouter.of(namespaces, records)

    val databasePath = normalizeStateDatabasePath(stateDatabase)
    val lockPath = databasePath.resolveSibling(databasePath.fileName.toString() + LOCK_SUFFIX)
    // 状态库和锁文件不能放在挂载目录里：挂载里的文件能通过公开的 delete 删掉，独占锁就失效了。
    // 只查本地根——WebDAV 挂载没有本机路径，状态库不会「落在」远端盘里。
    requireOutsideMounts(databasePath, "state database", localRoots(backendsByKey), records)
    requireOutsideMounts(lockPath, "instance lock file", localRoots(backendsByKey), records)
    return ResolvedConfig(
        databasePath = databasePath,
        lockPath = lockPath,
        namespaces = namespaces,
        mounts = records,
        router = router,
        backends = backendsByKey,
        limits = limits,
        streamTotalLimit = streamTotalLimit,
        eventBufferCapacity = eventBufferCapacity,
        closeGracePeriod = closeGracePeriod,
    )
}

/** 把一条挂载的后端整理到可直接使用的形态：Local FS 消解符号链接，WebDAV 只做字符串整理，不联网。 */
private fun normalizeBackend(backend: MountBackend): MountBackend =
    when (backend) {
        is MountBackend.LocalFs -> MountBackend.LocalFs(LocalFsRoots.normalize(backend.root))
        is MountBackend.WebDav ->
            WebDavRoots.normalize(backend.endpoint, backend.root).let { (endpoint, root) ->
                MountBackend.WebDav(endpoint, root, backend.username, backend.password)
            }
    }

private fun localRoots(backends: Map<String, MountBackend>): List<Path> =
    backends.values.filterIsInstance<MountBackend.LocalFs>().map { it.root }

private fun webDavTargets(backends: Map<String, MountBackend>): List<Pair<String, String>> =
    backends.values.filterIsInstance<MountBackend.WebDav>().map { it.endpoint to it.root }

/**
 * 把状态库路径收敛成**真实绝对路径**：相对路径按当前工作目录解析，父目录链上的符号链接换成真实目录。
 *
 * 例：`data/state.db`、`./data/../data/state.db`、`/tmp/link-to-data/state.db` 三种写法
 * 归一之后是同一个文件，同一把独占锁也就不会被三种写法各拿一次。
 * 已经存在的文件本身是符号链接时（`state.db` 指向别处），连最后一段一起解析。
 *
 * 父目录不存在就建出来——状态库总要有个地方放，这条路径归一化顺带把目录准备好。
 * **不承诺**阻止外部工具绕过 VFS 直接写这个 SQLite 文件（T03 §7）。
 */
internal fun normalizeStateDatabasePath(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    val parent = absolute.parent ?: throw invalidArgument("state database path has no parent directory")
    try {
        Files.createDirectories(parent)
        val candidate = parent.toRealPath().resolve(absolute.fileName.toString())
        // 文件本身是符号链接：目标还在就跟着走，目标没了就拒。
        // 悬空链接必须拒：锁会加在链接名上，SQLite 却在链接指向的地方建库，两个写法就各拿了一把锁。
        if (Files.isSymbolicLink(candidate)) {
            if (!Files.exists(candidate)) {
                throw invalidArgument("state database is a symbolic link whose target does not exist")
            }
            return candidate.toRealPath()
        }
        return candidate
    } catch (failure: IOException) {
        throw VfsException(
            VfsErrorCode.STATE_ERROR,
            "state database path cannot be prepared (${failure::class.simpleName})",
        ).apply { initCause(failure) }
    }
}

/**
 * 状态库文件或锁文件落在某个挂载目录里（正好等于也算）就拒。
 *
 * 例：把 `data` 挂到 `/resources`，状态库配成 `data/state.db`，公开的 `delete` 就能把库和锁删掉。
 */
private fun requireOutsideMounts(
    statePath: Path,
    what: String,
    roots: Collection<Path>,
    records: List<MountRecord>,
) {
    roots.firstOrNull { statePath.startsWith(it) }?.let { root ->
        val at = records.firstOrNull { it.physicalRoot == root.toString() }?.path ?: VfsPath.root
        throw invalidArgument(
            "the $what must not be inside a mounted storage root (it is under the root mounted at '$at'); " +
                "files under a mount can be listed and deleted through the VFS, which would break the single-instance lock",
        )
    }
}

/** 独占锁文件后缀。锁文件留在盘上是正常的，**存在不等于还被占用**。 */
internal const val LOCK_SUFFIX: String = ".lock"

private fun invalidArgument(reason: String): VfsException =
    VfsException(VfsErrorCode.INVALID_ARGUMENT, "Invalid VFS configuration: $reason")
