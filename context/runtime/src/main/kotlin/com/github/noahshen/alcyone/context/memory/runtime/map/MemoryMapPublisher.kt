package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.vfs.NodeInfo
import com.github.noahshen.alcyone.context.vfs.Vfs
import com.github.noahshen.alcyone.context.vfs.VfsUri

/** Memory Map 唯一发布器：固定 URI 且只调用 VFS safeReplace。 */
internal class MemoryMapPublisher(
    private val vfs: Vfs,
) : MemoryMapPublisherPort {
    override suspend fun publish(renderedBytes: ByteArray): NodeInfo = vfs.safeReplace(MAP_URI, renderedBytes)

    internal companion object {
        const val MAP_URI_STRING: String = "alcyone://memory/map.md"
        val MAP_URI: VfsUri = VfsUri.parse(MAP_URI_STRING)
    }
}
