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
 * [MountRecord.storageKey] → [StorageCapabilities] 快照（T10 §2.1）。构建后不可变，调用方之后修改入参不影响已构建视图（同 T09 快照模式）。
 *
 * 快照只表达打开后端时已知的能力，不探测运行中新出现的限制。
 */
class CapabilitySnapshot private constructor(
    private val byStorageKey: Map<String, StorageCapabilities>,
) {
    /** 该存储实例的能力；快照未包含时返回 `null`，由调用方按自己的契约处理。 */
    fun capabilitiesOf(storageKey: String): StorageCapabilities? = byStorageKey[storageKey]

    companion object {
        fun of(capabilities: Map<String, StorageCapabilities>): CapabilitySnapshot = CapabilitySnapshot(HashMap(capabilities))
    }
}

/**
 * 预检通过的结论（T10 §2.3、§2.5）：执行策略 + 源与目标各自命中的挂载与相对 StoragePath。
 *
 * 策略只描述"该怎么做"，**不执行任何 I/O**；T15 / T16 / T20 / T21 据此编排实际操作、状态更新与失败 effect 报告。
 */
data class PreconditionResult(
    val strategy: ExecutionStrategy,
    val source: RouteMatch?,
    val target: RouteMatch?,
)

/** 编排层应采用的移动 / 变更方式（T10 §2.5）。 */
enum class ExecutionStrategy {
    /** write 直接执行：存储没有额外的组合能力要求。 */
    DIRECT_WRITE,

    /** delete 直接执行。 */
    DIRECT_DELETE,

    /** 同挂载点且后端支持原生 move。 */
    NATIVE_MOVE,

    /** 同挂载点但缺少原生 move 能力，按 read → write → 确认 → delete 回退。 */
    COPY_FALLBACK_MOVE,

    /** 跨挂载点，一律按 read → write → 确认 → delete 的复制语义执行。 */
    CROSS_MOUNT_COPY_MOVE,
}

/**
 * 变更操作预检入口（T10 §2.1）。纯逻辑：只读 [MountRouter] 与 [CapabilitySnapshot]，
 * 不调用 Storage / Repository，不产生事件、不读写 Node，拒绝时抛既有错误码的 [VfsException]（effect 为 `NONE`，确认无副作用）。
 *
 * 检查顺序是契约的一部分（T02 §8.2"先拒绝纯参数错误和已知配置结构冲突，再检查路由 / 存储状态"）：
 * **纯参数与路径冲突（INVALID_ARGUMENT）→ 结构保护（UNSUPPORTED_OPERATION）→ 路由判定（MOUNT_NOT_FOUND）
 * → 只读（READ_ONLY）→ 能力组合（UNSUPPORTED_OPERATION）**。
 * 例：同一路径既无挂载又是受保护目录时，报结构冲突而不是 `MOUNT_NOT_FOUND`。
 *
 * 预检只拒绝"已知"问题：通过不代表操作会成功，存在性、类型冲突与真实 I/O 错误由执行期判定（T02 §9.1）。
 */
object OperationGuard {
    /**
     * 预检一次变更操作：拒绝时抛异常，通过时返回执行策略与源 / 目标的路由结果。
     *
     * @param router 挂载与配置目录结构（T09）；结构判断一律复用其查询，不重复推导。
     * @param capabilities 能力快照，必须覆盖本次操作涉及的全部 `storageKey`：查不到即抛 `INVALID_ARGUMENT`
     *   （快照缺失使只读与能力判定不可信，静默假设齐全会让不受支持的变更漏到执行期）。
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
        // 结构保护对源与目标各自执行，且整体早于任何路由判定。
        sourcePath?.let { rejectConfiguredDirectory(it, type, "source", router) }
        targetPath?.let { rejectConfiguredDirectory(it, type, "target", router) }
        val source = sourcePath?.let { side(it, "source", type, router, capabilities) }
        val target = targetPath?.let { side(it, "target", type, router, capabilities) }

        // 只读先于能力组合：既无必要继续推导策略，也无法在只读存储上执行任何回退。
        listOfNotNull(source, target).firstOrNull { it.capabilities.readOnly }?.let { throw readOnly(it) }

        val strategy =
            when (type) {
                OperationType.WRITE -> ExecutionStrategy.DIRECT_WRITE
                OperationType.DELETE -> ExecutionStrategy.DIRECT_DELETE
                OperationType.MOVE -> moveStrategy(intent, source!!, target!!)
            }
        return PreconditionResult(strategy, source?.route, target?.route)
    }

    /** 同路径 move 与"目标在源子树内"的 move 是纯参数冲突，早于结构与路由判定（T02 §7.1、§8.2）。 */
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
     * 拒绝对配置结构的变更（T02 §8.2）：逻辑根、命名空间根、Mount 根、配置推导目录以及包含后代挂载的目录，
     * 无论作为 move 的源、目标还是 write / delete 的对象。
     *
     * 三类结构在 T09 中都是"配置推导目录"：挂载点自身和它的所有祖先都在 `configuredDirectories` 里，
     * 因此一次 [MountRouter.isConfiguredDirectory] 查询即覆盖全部，不重复推导也不产生双重拒绝。
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

    /**
     * 一侧预检的结论：逻辑位置、路由命中和该侧存储的能力。
     *
     * 能力从快照按挂载的 `storageKey` 读取；快照未覆盖该 key 时抛 `INVALID_ARGUMENT`，因为只读与能力组合
     * 都无法判定，静默当成“能力齐全”会把不受支持的变更放行到执行期。
     */
    private data class Side(
        val path: VfsPath,
        val role: String,
        val route: RouteMatch,
        val capabilities: StorageCapabilities,
    )

    /** 路由判定 + 能力快照读取；两者都在结构保护之后执行。 */
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
     * move 的策略选择（T10 §2.4）：
     *
     * - 同挂载点：优先原生 move，缺少对应原生能力时回退到复制语义；目录回退需要目标存储能创建目录，
     *   否则明确 `UNSUPPORTED_OPERATION`（T02 §6.2 要求保留空子目录）。
     * - 跨挂载点：一律按 read → write → 确认 → delete 的复制语义检查，**即使两侧 storageKey 相同也不改用原生 move**。
     *   这是首版的取舍：真实物理映射与重叠关系由 T12 / T18 掌握后再重估。
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

    /** 复制语义只在条目是目录时要求目标存储能创建目录；文件回退没有额外能力要求。 */
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

    /** 只读存储：操作涉及的任一侧只读即拒绝，信息只含逻辑路径与角色。 */
    private fun readOnly(side: Side): VfsException =
        VfsException(
            VfsErrorCode.READ_ONLY,
            "Cannot operate on ${side.role} '${side.path}': the backing storage is read-only",
        )

    /** 错误信息只带逻辑路径与操作名，不含物理路径、挂载位置或存储标识。 */
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
