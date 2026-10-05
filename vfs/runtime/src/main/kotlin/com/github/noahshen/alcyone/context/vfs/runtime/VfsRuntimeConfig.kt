package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.VfsLimits
import com.github.noahshen.alcyone.context.vfs.core.event.AsyncEventNotifier
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsRoots
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 一块挂载要描述什么：一个逻辑位置、一个实例标识、一个本机目录。
 *
 * 例：`resources` 命名空间下把 `/resources/docs` 挂到 `/Users/me/docs`。
 */
data class MountConfig(
    /** 挂载点的逻辑路径，必须在已配置的命名空间下，且不能是逻辑根 `/`。 */
    val vfsPath: VfsPath,
    /** 实例标识，只作内部唯一键用，不当凭据看；重开时它变了会按冲突拒绝。 */
    val storageKey: String,
    /** 本机目录，必须已经存在。规范化（消解符号链接）在 [VfsRuntimeConfig.resolve] 里做。 */
    val root: Path,
)

/**
 * Runtime 的全部配置。宿主**只提交这个对象**给 [AlcyoneVfs.create]，
 * 自己不创建 Operator、Driver、Repository 或各 Manager。
 *
 * 例：状态库放 `data/state.db`，命名空间 `resources` / `memory`，各挂一块本机目录，读写限额都是 16 MiB。
 *
 * 本轮（S1 + S2）只组装，不含流式读取与关闭等待期：[closeGracePeriod] 只是先留在配置里，
 * 真正实现它的是 T18 第二片。
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
    /** 进程内事件队列容量，沿用 T14 的有界通知。 */
    val eventBufferCapacity: Int = AsyncEventNotifier.DEFAULT_CAPACITY,
    /** 关闭时等待在途操作的期限。**本轮尚未实现**，只是先把配置位置留出来。 */
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
    /** 每个 storageKey 对应的规范化物理根；同一 key 只开一个存储实例。 */
    val roots: Map<String, Path>,
    val limits: VfsLimits,
    val eventBufferCapacity: Int,
    val closeGracePeriod: Duration,
) {
    companion object {
        /** Local FS 在挂载身份里的后端类型；换后端时这一项也参与冲突判断。 */
        const val LOCAL_FS_BACKEND_TYPE: String = "local-fs"
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
    val keys = mounts.map { it.storageKey }
    keys.forEach { key ->
        if (key.isBlank() || key.any { it.isWhitespace() || it.isISOControl() }) {
            throw invalidArgument("storageKey must be a non-blank name without whitespace or control characters")
        }
    }
    // 同一个 key 只留一份物理根：同一个目录挂两次是「一块盘挂两个逻辑位置」，不是一个重叠根。
    val rootsByKey = LinkedHashMap<String, Path>()
    mounts.forEach { mount -> rootsByKey.putIfAbsent(mount.storageKey, mount.root) }
    // 规范化 + 互不重叠检查：根目录必须是已经存在的目录，符号链接换成它指向的真实路径（T12）。
    // 身份里存的必须是**规范化之后**的路径，否则换个写法（别名、相对路径）就会被当成换了根。
    val normalizedByKey = rootsByKey.keys.zip(LocalFsRoots.requireNonOverlapping(rootsByKey.values)).toMap()

    val records =
        mounts.map { mount ->
            MountRecord(
                path = mount.vfsPath,
                storageKey = mount.storageKey,
                backendType = ResolvedConfig.LOCAL_FS_BACKEND_TYPE,
                physicalRoot = normalizedByKey.getValue(mount.storageKey).toString(),
            )
        }
    // 逻辑侧校验：挂载不能是逻辑根、不能在未配置命名空间下、不能重复（T02 §3.1）。
    val router = MountRouter.of(namespaces, records)

    val databasePath = normalizeStateDatabasePath(stateDatabase)
    return ResolvedConfig(
        databasePath = databasePath,
        lockPath = databasePath.resolveSibling(databasePath.fileName.toString() + LOCK_SUFFIX),
        namespaces = namespaces,
        mounts = records,
        router = router,
        roots = normalizedByKey,
        limits = limits,
        eventBufferCapacity = eventBufferCapacity,
        closeGracePeriod = closeGracePeriod,
    )
}

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
        // 文件本身已经是符号链接就整条解析；还不存在时只解析父目录。
        val resolvedParent = parent.toRealPath()
        return if (Files.exists(absolute)) absolute.toRealPath() else resolvedParent.resolve(absolute.fileName.toString())
    } catch (failure: IOException) {
        throw VfsException(
            VfsErrorCode.STATE_ERROR,
            "state database path cannot be prepared (${failure::class.simpleName})",
        ).apply { initCause(failure) }
    }
}

/** 独占锁文件后缀。锁文件留在盘上是正常的，**存在不等于还被占用**。 */
internal const val LOCK_SUFFIX: String = ".lock"

private fun invalidArgument(reason: String): VfsException =
    VfsException(VfsErrorCode.INVALID_ARGUMENT, "Invalid VFS configuration: $reason")
