package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.vfs.runtime.MountBackend
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 配置路径隔离校验（CT06-B1/B2 共用）。
 *
 * - `context.db` 与 `vfs.db` 不得是同一文件：存在用 `Files.isSameFile`；不存在时比较「计划真实路径」。
 * - 两个数据库都不得落在任何 LocalFs 挂载根的真实路径之内（按路径段边界）。
 *
 * 路径规范化分两步（缺一不可）：
 * 1. 逐段解析符号链接：`Files.isSymbolicLink` 对悬空链接同样为 true，`readSymbolicLink` 相对目标
 *    按链接父目录、绝对目标从根重新解析；连续链接链维护已访问集合 + 40 次深度上限；循环 /
 *    超深度 / 读取失败 → `INVALID_CONFIG`，**不回退词法路径**。
 * 2. 真实路径规范化：存在 → `toRealPath()`；不存在 → 最近存在祖先 `toRealPath()` + 不存在后缀。
 *    兜住大小写不敏感文件系统上的同一目录别名；解析失败 → `INVALID_CONFIG`，不回退词法。
 *
 * 不使用全路径 lowercase，避免误伤大小写敏感文件系统；普通不存在的多级父目录合法，且本对象
 * 只读文件系统、不创建任何目录或文件。
 */
internal object ContextPathIsolation {
    /** 单条配置路径允许解析的符号链接次数上限（与常见 OS 的 ELOOP 阈值一致）。 */
    private const val MAX_SYMLINK_REVISIONS: Int = 40

    fun requireDistinctDatabases(
        contextDatabase: Path,
        vfsDatabase: Path,
    ) {
        if (!sameFile(contextDatabase, "context.database.path", vfsDatabase, "vfs.database.path")) return
        throw invalid("context.database.path and vfs.database.path must not reference the same file")
    }

    fun requireOutsideLocalMounts(
        database: Path,
        mounts: List<MountConfig>,
        field: String,
    ) {
        val databaseReal = canonicalizeForComparison(database, field)
        mounts.forEachIndexed { index, mount ->
            val backend = mount.backend
            if (backend is MountBackend.LocalFs) {
                val rootField = "vfs.mounts[$index].backend.root"
                val rootReal = canonicalizeForComparison(backend.root, rootField)
                if (databaseReal.startsWith(rootReal)) {
                    throw invalid("$field must not be inside the local mount root configured at ${mount.vfsPath} ($rootField)")
                }
            }
        }
    }

    private fun sameFile(
        first: Path,
        firstField: String,
        second: Path,
        secondField: String,
    ): Boolean {
        if (Files.exists(first) && Files.exists(second)) {
            return try {
                Files.isSameFile(first, second)
            } catch (failure: IOException) {
                canonicalizeForComparison(first, firstField) == canonicalizeForComparison(second, secondField)
            }
        }
        return canonicalizeForComparison(first, firstField) == canonicalizeForComparison(second, secondField)
    }

    /** 先逐段解析符号链接，再做真实路径规范化。 */
    private fun canonicalizeForComparison(
        path: Path,
        field: String,
    ): Path {
        val symlinkResolved = resolveSymlinkSegments(path.toAbsolutePath(), field)
        return realPathForComparison(symlinkResolved, field)
    }

    private fun resolveSymlinkSegments(
        absolute: Path,
        field: String,
    ): Path {
        val root = absolute.root ?: throw invalid("$field must resolve to an absolute path")
        val remaining = ArrayDeque<String>()
        absolute.forEach { segment -> remaining.addLast(segment.toString()) }
        val resolved = ArrayDeque<String>()
        // 只跟踪「连续链接链」上的链接：消费普通路径段后清空，避免把 /var -> private/var 这类
        // 因绝对目标重新从根解析而再次遇到的合法链接误判为循环；真正的循环由这里与深度上限共同兜底。
        val activeLinks = mutableSetOf<Path>()
        var revisions = 0

        while (remaining.isNotEmpty()) {
            when (val segment = remaining.removeFirst()) {
                "", "." -> Unit
                ".." -> {
                    activeLinks.clear()
                    if (resolved.isNotEmpty()) resolved.removeLast()
                }
                else -> {
                    val candidate = resolved.fold(root) { prefix, part -> prefix.resolve(part) }.resolve(segment)
                    if (!Files.isSymbolicLink(candidate)) {
                        activeLinks.clear()
                        resolved.addLast(segment)
                    } else {
                        if (!activeLinks.add(candidate)) {
                            throw invalid("$field contains a symbolic link cycle")
                        }
                        revisions++
                        if (revisions > MAX_SYMLINK_REVISIONS) {
                            throw invalid("$field exceeds the symbolic link depth limit")
                        }
                        val target =
                            try {
                                Files.readSymbolicLink(candidate)
                            } catch (failure: Exception) {
                                throw invalid("$field contains a symbolic link that cannot be resolved")
                            }
                        val targetSegments = mutableListOf<String>()
                        target.forEach { part -> targetSegments += part.toString() }
                        if (target.isAbsolute) resolved.clear()
                        val rest = remaining.toList()
                        remaining.clear()
                        targetSegments.forEach { part -> remaining.addLast(part) }
                        rest.forEach { part -> remaining.addLast(part) }
                    }
                }
            }
        }
        return resolved.fold(root) { prefix, part -> prefix.resolve(part) }
    }

    private fun realPathForComparison(
        path: Path,
        field: String,
    ): Path {
        if (Files.exists(path)) {
            return toRealPath(path, field)
        }
        val absolute = path.toAbsolutePath()
        val missing = ArrayDeque<String>()
        var existing: Path? = absolute
        while (existing != null && !Files.exists(existing)) {
            existing.fileName?.let { missing.addFirst(it.toString()) }
            existing = existing.parent
        }
        val nearest = existing ?: throw invalid("$field cannot be resolved to a real path")
        var resolved = toRealPath(nearest, field)
        for (segment in missing) {
            resolved = resolved.resolve(segment)
        }
        return resolved
    }

    private fun toRealPath(
        path: Path,
        field: String,
    ): Path =
        try {
            path.toRealPath()
        } catch (failure: IOException) {
            throw invalid("$field cannot be resolved to a real path")
        } catch (failure: SecurityException) {
            throw invalid("$field cannot be resolved to a real path")
        }

    private fun invalid(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
}
