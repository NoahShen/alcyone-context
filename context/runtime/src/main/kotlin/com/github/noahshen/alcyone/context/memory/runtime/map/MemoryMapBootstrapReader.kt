package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryMapView
import com.github.noahshen.alcyone.context.memory.core.map.MemoryMapRenderer
import com.github.noahshen.alcyone.context.vfs.Vfs
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** 一次 Map 读取是否取得了可靠的“存在/缺失”结果；false 表示读取或解析失败。 */
internal data class MemoryMapReadResult(
    val state: MemoryMapState,
    val reliable: Boolean,
)

/** 固定 Map URI 的只读加载器：不查数据库、不调用 LLM。 */
internal class MemoryMapBootstrapReader(
    private val vfs: Vfs,
    private val tracker: MemoryMapStatusTracker,
    private val logger: Logger = LoggerFactory.getLogger(MemoryMapBootstrapReader::class.java),
) {
    suspend fun refresh(): MemoryMapReadResult {
        val bytes =
            try {
                vfs.read(MemoryMapPublisher.MAP_URI)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: VfsException) {
                if (failure.code == VfsErrorCode.NOT_FOUND) {
                    tracker.markMissing()
                    return MemoryMapReadResult(tracker.snapshot(), reliable = true)
                }
                tracker.markUnknown()
                logger.warn("memoryMap.bootstrap.read.failed")
                return MemoryMapReadResult(tracker.snapshot(), reliable = false)
            } catch (_: Throwable) {
                tracker.markUnknown()
                logger.warn("memoryMap.bootstrap.read.failed")
                return MemoryMapReadResult(tracker.snapshot(), reliable = false)
            }

        val document =
            try {
                MemoryMapRenderer.parse(bytes)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                tracker.markUnknown()
                logger.warn("memoryMap.bootstrap.parse.failed")
                return MemoryMapReadResult(tracker.snapshot(), reliable = false)
            }
        val view =
            MemoryMapView(
                content = bytes.toString(Charsets.UTF_8),
                generatedAt = document.generatedAt,
                sourceUpdatedAt = document.sourceUpdatedAt,
            )
        tracker.update(view, MapStatus.UNKNOWN)
        return MemoryMapReadResult(tracker.snapshot(), reliable = true)
    }

    fun snapshot(): MemoryMapState = tracker.snapshot()
}
