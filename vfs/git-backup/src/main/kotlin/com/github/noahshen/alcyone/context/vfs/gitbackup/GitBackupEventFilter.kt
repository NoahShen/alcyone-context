package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import java.util.Collections

/**
 * 内部 VFS 变更事件筛选器。
 *
 * 规则：
 * - 忽略 METADATA_UPDATED 事件（返回 false）；
 * - 移动事件（FILE_MOVED, DIRECTORY_MOVED）：sourceUri 或 targetUri 任一命中 eventRoots 即返回 true；
 * - 普通变更（FILE_CREATED, FILE_WRITTEN, FILE_DELETED, DIRECTORY_DELETED）：检查 event.uri 是否命中 eventRoots；
 * - 命中判定基于完整路径段前缀匹配，严格段边界（例如 /a/b 是 /a/b/c 的前缀，但不是 /a/bc 的前缀）。
 */
internal class GitBackupEventFilter(
    eventRoots: Set<VfsUri>,
) {
    private val eventRoots: Set<VfsUri> = Collections.unmodifiableSet(eventRoots.toHashSet())

    fun matches(event: VfsEvent): Boolean {
        if (event.type == VfsEventType.METADATA_UPDATED) {
            return false
        }

        return when (event.type) {
            VfsEventType.FILE_MOVED, VfsEventType.DIRECTORY_MOVED -> {
                val sourceHit = event.sourceUri?.let { isUnderAnyRoot(it) } ?: false
                val targetHit = event.targetUri?.let { isUnderAnyRoot(it) } ?: isUnderAnyRoot(event.uri)
                sourceHit || targetHit
            }
            else -> isUnderAnyRoot(event.uri)
        }
    }

    private fun isUnderAnyRoot(uri: VfsUri): Boolean = eventRoots.any { root -> isPrefixOf(root, uri) }

    private fun isPrefixOf(
        root: VfsUri,
        target: VfsUri,
    ): Boolean {
        val rootSegments = root.path.segments
        val targetSegments = target.path.segments

        if (rootSegments.size > targetSegments.size) {
            return false
        }

        for (i in rootSegments.indices) {
            if (rootSegments[i] != targetSegments[i]) {
                return false
            }
        }
        return true
    }
}
