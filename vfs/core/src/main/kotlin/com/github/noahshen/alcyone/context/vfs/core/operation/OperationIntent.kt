package com.github.noahshen.alcyone.context.vfs.core.operation

import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.WriteMode

/** 预检要判定的三种变更操作。read / stat / list 是查询，不在这里判定（T02 §9.1）。 */
enum class OperationType { WRITE, MOVE, DELETE }

/**
 * 一次变更操作的意图：把「要做什么、动哪个路径、条目是文件还是目录」告诉预检（T10 §2.1）。
 *
 * 只装参数，不装事实。文件存不存在、目标是文件还是目录，都是执行期才知道的事。
 *
 * 字段形状由工厂方法固定：write 只有 `target`；move 有 `source` 和 `target`；delete 只有 `source`。
 * 例：`OperationIntent.write(VfsPath.parse("/memory/a.txt"))` 的 `source` 一定是 null。
 * 路径本身写法不对（空段、含字面 `%` 等）在构造 `VfsPath` 时就报 `INVALID_URI`，这里不再检查。
 *
 * @property entryType 条目是文件还是目录。搬目录比搬文件多要一样能力（建目录），预检按它选执行办法。
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
        /** 写一个文件。目标在不在、是文件还是目录，留到执行期再判。 */
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

        /** 把 `source` 移到 `target`。源和目标相同、目标落进源子树这类冲突由预检拒绝。 */
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

        /** 删掉 `source`。`recursive` 只影响执行：目录里挂着别的盘时，`recursive = true` 也删不掉（T02 §8.2）。 */
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
