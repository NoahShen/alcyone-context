package com.github.noahshen.alcyone.context.memory.runtime.resource

import com.github.noahshen.alcyone.context.memory.api.ContextResourceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextResourceException
import com.github.noahshen.alcyone.context.memory.api.ResourceMetadata
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.ReadOptions
import com.github.noahshen.alcyone.context.vfs.StatOptions
import com.github.noahshen.alcyone.context.vfs.Vfs
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Context 对 `/resources` 文件只读访问的唯一适配层；公共边界只暴露安全错误。 */
internal class ContextResourceAccess(
    private val vfs: Vfs,
) {
    suspend fun loadMeta(uri: String): ResourceMetadata {
        val target = resourceUri(uri, "load resource metadata")
        val node =
            try {
                vfs.stat(target, StatOptions())
            } catch (failure: Exception) {
                throw mappedFailure(failure, "load resource metadata", target)
            }
        if (node.type != NodeType.FILE) {
            throw resourceFailure(ContextResourceErrorCode.TYPE_MISMATCH, "load resource metadata", target)
        }
        return ResourceMetadata(
            uri = node.uri.toString(),
            sizeBytes = node.storage?.sizeBytes,
            modifiedAt = node.storage?.modifiedAt,
        )
    }

    suspend fun read(uri: String): ByteArray {
        val target = resourceUri(uri, "read resource")
        return try {
            vfs.read(target, ReadOptions())
        } catch (failure: Exception) {
            throw mappedFailure(failure, "read resource", target)
        }
    }

    fun readStream(uri: String): Flow<ByteArray> =
        flow {
            currentCoroutineContext().ensureActive()
            val target = resourceUri(uri, "open resource stream")
            val opened =
                try {
                    vfs.openStream(target, VfsStreamOptions())
                } catch (failure: Exception) {
                    throw mappedFailure(failure, "open resource stream", target)
                }

            var primary: Throwable? = null
            try {
                val buffer = ByteArray(STREAM_CHUNK_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count =
                        try {
                            opened.stream.read(buffer)
                        } catch (failure: Exception) {
                            throw mappedFailure(failure, "read resource stream", target)
                        }
                    if (count < 0) break
                    if (count == 0) continue
                    currentCoroutineContext().ensureActive()
                    emit(buffer.copyOf(count))
                }
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                try {
                    opened.close()
                } catch (closeFailure: Exception) {
                    val safeCloseFailure =
                        ContextResourceException(
                            ContextResourceErrorCode.UNAVAILABLE,
                            "read resource stream could not close $target",
                        )
                    if (primary == null) throw safeCloseFailure else primary.addSuppressed(safeCloseFailure)
                }
            }
        }

    private fun resourceUri(
        uri: String,
        operation: String,
    ): VfsUri {
        val target =
            try {
                VfsUri.parse(uri)
            } catch (failure: VfsException) {
                throw ContextResourceException(
                    ContextResourceErrorCode.INVALID_URI,
                    "$operation requires a valid alcyone://resources/... URI",
                )
            }
        val segments = target.path.segments
        if (segments.size < 2 || segments.first() != RESOURCES_NAMESPACE) {
            throw ContextResourceException(
                ContextResourceErrorCode.OUT_OF_SCOPE,
                "$operation is outside the resources scope: $target",
            )
        }
        return target
    }

    private fun mappedFailure(
        failure: Exception,
        operation: String,
        uri: VfsUri,
    ): ContextResourceException {
        if (failure is CancellationException) throw failure
        val code =
            when ((failure as? VfsException)?.code) {
                VfsErrorCode.NOT_FOUND -> ContextResourceErrorCode.NOT_FOUND
                VfsErrorCode.TYPE_MISMATCH -> ContextResourceErrorCode.TYPE_MISMATCH
                VfsErrorCode.LIMIT_EXCEEDED -> ContextResourceErrorCode.LIMIT_EXCEEDED
                else -> ContextResourceErrorCode.UNAVAILABLE
            }
        return resourceFailure(code, operation, uri)
    }

    private fun resourceFailure(
        code: ContextResourceErrorCode,
        operation: String,
        uri: VfsUri,
    ): ContextResourceException = ContextResourceException(code, "$operation failed for $uri")

    private companion object {
        const val RESOURCES_NAMESPACE = "resources"
        const val STREAM_CHUNK_BYTES = 64 * 1024
    }
}
