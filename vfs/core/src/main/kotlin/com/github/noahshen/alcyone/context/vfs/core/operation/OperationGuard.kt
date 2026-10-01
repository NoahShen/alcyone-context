package com.github.noahshen.alcyone.context.vfs.core.operation

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.router.RouteMatch
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageCapabilities

/**
 * 每块存储会做什么的只读快照：[MountRecord.storageKey] → [StorageCapabilities]（T10 §2.1）。
 *
 * 例：`CapabilitySnapshot.of(mapOf("local-disk" to StorageCapabilities(readOnly = true)))`
 *
 * 建好之后不再变：调用方之后改自己那份 Map 也不影响它（同 T09 的 MountRouter）。
 * 只记打开后端时已知的能力；运行中才出现的限制按执行期错误处理。
 */
class CapabilitySnapshot private constructor(
    private val byStorageKey: Map<String, StorageCapabilities>,
) {
    /** 这块存储的能力；快照里没有就返回 null。 */
    fun capabilitiesOf(storageKey: String): StorageCapabilities? = byStorageKey[storageKey]

    companion object {
        /** 用一张能力表建快照，入参会被复制一份。 */
        fun of(capabilities: Map<String, StorageCapabilities>): CapabilitySnapshot = CapabilitySnapshot(HashMap(capabilities))
    }
}

/**
 * 预检通过的结论：用什么办法做，源和目标各自落在哪个挂载的哪个相对位置（T10 §2.3、§2.5）。
 *
 * 例：写 `/memory/notes/a.txt` 通过后，`strategy` 是 `DIRECT_WRITE`，`target` 是挂载 `/memory` 上的 `notes/a.txt`。
 * 执行层照着做就行；这里只描述做法，不做任何 I/O，也不产生事件。
 */
data class PreconditionResult(
    val strategy: ExecutionStrategy,
    val source: RouteMatch?,
    val target: RouteMatch?,
)

/** 这次变更该怎么做（T10 §2.5）。只是描述，不执行。 */
enum class ExecutionStrategy {
    /** 写文件，直接写。除只读外没有别的能力要求。 */
    DIRECT_WRITE,

    /** 删文件或目录，直接删。 */
    DIRECT_DELETE,

    /** 同一个挂载点内移动，后端支持原生 move，直接用它。 */
    NATIVE_MOVE,

    /** 还是同一个挂载点，但后端不支持原生 move，改用读 → 写 → 确认 → 删源。 */
    COPY_FALLBACK_MOVE,

    /** 跨挂载点，一律用读 → 写 → 确认 → 删源。 */
    CROSS_MOUNT_COPY_MOVE,
}

/**
 * 变更操作预检：写 / 移动 / 删除之前先问一句「这事能不能做」（T10 §2.1）。
 *
 * 只看配置，不碰存储：不调用 Storage / Repository，不产生事件，不读写 Node。
 * 通过只说明「已知的问题都没了」，不代表操作一定成功——存在性、类型冲突、真实 I/O 错误由执行期判定（T02 §9.1）。
 *
 * 检查顺序是契约（T02 §8.2：先拒绝参数错误和已知配置结构冲突，再看路由 / 存储状态）：
 * 1. 参数冲突（`INVALID_ARGUMENT`）→ 2. 结构保护（`UNSUPPORTED_OPERATION`）→ 3. 路由判定（`MOUNT_NOT_FOUND`）
 * → 4. 只读（`READ_ONLY`）→ 5. 能力组合（`UNSUPPORTED_OPERATION`）。前一步拒绝就不会走后面。
 *
 * 例：`/notes/team` 既没有自己的挂载、又是 `/notes/team/docs` 的祖先目录，写它先报结构错误，不会报「找不到挂载」。
 */
object OperationGuard {
    /**
     * 跑一遍预检：能通过就返回策略和路由结果，不能通过就抛 [VfsException]（effect 为 `NONE`，没产生任何变更）。
     *
     * 例：`check(OperationIntent.write(VfsPath.parse("/memory/notes/a.txt")), router, capabilities)`
     * 返回 `strategy = DIRECT_WRITE`，`target` 是挂载 `/memory` 上的 `notes/a.txt`。
     *
     * @param router 挂载和配置目录（T09）。这个路径能不能改，看它怎么说。
     * @param capabilities 能力快照，必须包含这次用到的每个 `storageKey`；少一个就报 `INVALID_ARGUMENT`：
     *   能力判不出来就不能放行，否则做不了的变更会一路拖到执行期才失败。
     */
    fun check(
        intent: OperationIntent,
        router: MountRouter,
        capabilities: CapabilitySnapshot,
    ): PreconditionResult {
        rejectConflictingMoveArguments(intent)

        val type = intent.type
        val sourcePath = intent.source
        val targetPath = intent.target
        // 结构保护排在路由前面：源和目标都先查一遍，是配置目录就当场拒掉。
        sourcePath?.let { rejectConfiguredDirectory(it, type, "source", router) }
        targetPath?.let { rejectConfiguredDirectory(it, type, "target", router) }
        val source = sourcePath?.let { side(it, "source", type, router, capabilities) }
        val target = targetPath?.let { side(it, "target", type, router, capabilities) }

        // 盘是只读的：什么办法都执行不了，先拒掉，不用再看能力。
        listOfNotNull(source, target).firstOrNull { it.capabilities.readOnly }?.let { throw readOnly(it) }

        val strategy =
            when (type) {
                OperationType.WRITE -> ExecutionStrategy.DIRECT_WRITE
                OperationType.DELETE -> ExecutionStrategy.DIRECT_DELETE
                OperationType.MOVE -> moveStrategy(intent, source!!, target!!)
            }
        return PreconditionResult(strategy, source?.route, target?.route)
    }

    /** 移动的两种参数冲突：源和目标相同、目标落进源自己的子树（T02 §7.1、§8.2）。放第一步，早于结构和路由。 */
    private fun rejectConflictingMoveArguments(intent: OperationIntent) {
        if (intent.type != OperationType.MOVE) return
        val source = intent.source!!
        val target = intent.target!!
        if (source == target) {
            throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "Move source and target are the same path '$target'")
        }
        val prefix = source.segments
        val segments = target.segments
        if (segments.size > prefix.size && segments.subList(0, prefix.size) == prefix) {
            throw VfsException(VfsErrorCode.INVALID_ARGUMENT, "Move target '$target' is inside its own source subtree '$source'")
        }
    }

    /**
     * 配置里推出来的目录不能改：逻辑根、命名空间根、挂载点、缺层的祖先目录（T02 §8.2）。
     *
     * 例：挂了 `/notes/team/docs` 之后 `/notes/team` 就不能删——它下面挂着一块盘。
     *
     * 这几类路径在 T09 里都算配置目录：挂载点自己，加上它的每一层祖先。所以查一次
     * [MountRouter.isConfiguredDirectory] 就够，不用再单独查挂载点和后代挂载。
     */
    private fun rejectConfiguredDirectory(
        path: VfsPath,
        type: OperationType,
        role: String,
        router: MountRouter,
    ) {
        if (router.isConfiguredDirectory(path)) {
            throw unsupported(type, role, path, "it is a configured directory (logical root, namespace root, mount root or mount ancestor)")
        }
    }

    /** 预检通过的一侧：逻辑路径、路由结果、这块盘的能力。 */
    private data class Side(
        val path: VfsPath,
        val role: String,
        val route: RouteMatch,
        val capabilities: StorageCapabilities,
    )

    /**
     * 路由判定 + 查这块盘的能力；两步都在结构保护之后。
     *
     * 快照里没有这块盘就报 `INVALID_ARGUMENT`：只读和能不能回退都判不出来，
     * 当成「能力齐全」放行，只会让做不了的变更拖到执行期才失败。
     */
    private fun side(
        path: VfsPath,
        role: String,
        type: OperationType,
        router: MountRouter,
        capabilities: CapabilitySnapshot,
    ): Side {
        val route =
            router.route(path) ?: throw VfsException(
                VfsErrorCode.MOUNT_NOT_FOUND,
                "No mount covers ${type.name.lowercase()} $role '$path'",
            )
        val caps =
            capabilities.capabilitiesOf(route.mount.storageKey) ?: throw VfsException(
                VfsErrorCode.INVALID_ARGUMENT,
                "Capability snapshot is incomplete: no entry for the storage of the ${type.name.lowercase()} $role '$path'",
            )
        return Side(path, role, route, caps)
    }

    /**
     * 移动用什么办法做（T10 §2.4）。
     *
     * - 同一个挂载点：先用后端的原生 move。例：把 `a.txt` 改名成 `b.txt`，后端支持就是 `NATIVE_MOVE`，不支持就复制。
     * - 跨挂载点：一律复制（读 → 写 → 确认 → 删源），**两块盘是同一个 `storageKey` 也不改用原生 move**。
     *   首版保守：真实物理位置要到 T12 / T18 才清楚，之后再重估。
     */
    private fun moveStrategy(
        intent: OperationIntent,
        source: Side,
        target: Side,
    ): ExecutionStrategy {
        if (source.route.mount == target.route.mount) {
            val native =
                if (intent.entryType == NodeType.DIRECTORY) {
                    source.capabilities.nativeDirectoryMove
                } else {
                    source.capabilities.nativeFileMove
                }
            if (native) return ExecutionStrategy.NATIVE_MOVE
            requireCopyTarget(intent, target)
            return ExecutionStrategy.COPY_FALLBACK_MOVE
        }
        requireCopyTarget(intent, target)
        return ExecutionStrategy.CROSS_MOUNT_COPY_MOVE
    }

    /** 复制目录要在目标建目录，不然空子目录会丢（T02 §6.2）。移动文件没有这项要求。 */
    private fun requireCopyTarget(
        intent: OperationIntent,
        target: Side,
    ) {
        if (intent.entryType != NodeType.DIRECTORY || target.capabilities.createDirectory) return
        throw unsupported(
            intent.type,
            target.role,
            target.path,
            "the backing storage cannot create directories and a directory copy must preserve empty sub-directories",
        )
    }

    /** 盘是只读：任一侧只读就拒。消息只写逻辑路径和角色，不写 storageKey。 */
    private fun readOnly(side: Side): VfsException =
        VfsException(
            VfsErrorCode.READ_ONLY,
            "Cannot operate on ${side.role} '${side.path}': the backing storage is read-only",
        )

    /** 拼一条 UNSUPPORTED_OPERATION 消息：只带逻辑路径和操作名，不带物理路径和 storageKey。 */
    private fun unsupported(
        type: OperationType,
        role: String,
        path: VfsPath,
        reason: String,
    ): VfsException =
        VfsException(
            VfsErrorCode.UNSUPPORTED_OPERATION,
            "Cannot ${type.name.lowercase()} $role '$path': $reason",
        )
}
