package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapArtifact
import com.github.noahshen.alcyone.context.vfs.NodeInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun interface MemoryMapGeneratorPort {
    suspend fun generate(): MemoryMapArtifact
}

internal fun interface MemoryMapPublisherPort {
    suspend fun publish(renderedBytes: ByteArray): NodeInfo
}

/** 生成成功后才安全发布；供 CT19 后续复用的直接编排入口。 */
internal class MemoryMapService(
    private val generator: MemoryMapGeneratorPort,
    private val publisher: MemoryMapPublisherPort,
) {
    suspend fun generateAndPublish(): MemoryMapArtifact {
        val artifact = generator.generate()
        currentCoroutineContext().ensureActive()
        publisher.publish(artifact.renderedBytes)
        return artifact
    }
}
