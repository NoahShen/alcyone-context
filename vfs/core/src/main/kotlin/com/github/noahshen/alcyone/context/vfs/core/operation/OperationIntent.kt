package com.github.noahshen.alcyone.context.vfs.core.operation

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.WriteMode

/** 预检覆盖的变更操作；read / stat / list 等查询不在此判定（T02 §9.1）。 */
enum class OperationType { WRITE, MOVE, DELETE }

/**
 * 一次变更操作的意图（T10 §2.1）：编排层交给预检的**纯参数**，不含存在性、类型与覆盖等执行期事实。
 *
 * 字段形状按操作类型固定，由工厂方法保证不变量：write 只有 `target` 且条目恒为文件；
 * move 有 `source` 与 `target`；delete 只有 `source` 与 `recursive`。路径语法错误在构造 `VfsPath` 时已是 `INVALID_URI`，
 * 不在这里重复校验。
 *
 * @property entryType 被移动 / 删除的条目类型，供 B 阶段组合目录所需能力；A 阶段只保存不判定。
 */
class OperationIntent private constructor(
    val type: OperationType,
    val source: VfsPath?,
    val target: VfsPath?,
    val entryType: NodeType,
    val writeMode: WriteMode?,
    val recursive: Boolean,
) {
    companion object {
        /** 写入目标文件；目标存在性、类型与覆盖冲突属执行期判定。 */
        fun write(
            target: VfsPath,
            mode: WriteMode = WriteMode.UPSERT,
        ): OperationIntent =
            OperationIntent(
                type = OperationType.WRITE,
                source = null,
                target = target,
                entryType = NodeType.FILE,
                writeMode = mode,
                recursive = false,
            )

        /** 移动源到目标；同路径、目标入源子树等参数冲突由预检拒绝。 */
        fun move(
            source: VfsPath,
            target: VfsPath,
            entryType: NodeType,
        ): OperationIntent =
            OperationIntent(
                type = OperationType.MOVE,
                source = source,
                target = target,
                entryType = entryType,
                writeMode = null,
                recursive = false,
            )

        /** 删除源；`recursive` 不放宽结构保护（T02 §8.2）。 */
        fun delete(
            source: VfsPath,
            entryType: NodeType,
            recursive: Boolean = false,
        ): OperationIntent =
            OperationIntent(
                type = OperationType.DELETE,
                source = source,
                target = null,
                entryType = entryType,
                writeMode = null,
                recursive = recursive,
            )
    }
}
