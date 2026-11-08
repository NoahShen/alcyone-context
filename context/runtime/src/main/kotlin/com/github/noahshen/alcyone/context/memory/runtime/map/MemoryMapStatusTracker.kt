package com.github.noahshen.alcyone.context.memory.runtime.map

import com.github.noahshen.alcyone.context.memory.api.MapStatus
import com.github.noahshen.alcyone.context.memory.api.MemoryMapView

/** 一次原子读取的 Memory Map View/Status 快照。 */
internal data class MemoryMapState(
    val view: MemoryMapView?,
    val status: MapStatus,
)

/** 线程安全的进程内 Memory Map 状态缓存；不持久化、不执行 I/O。 */
internal class MemoryMapStatusTracker {
    private val lock = Any()
    private var current = MemoryMapState(view = null, status = MapStatus.UNKNOWN)

    fun snapshot(): MemoryMapState = synchronized(lock) { current }

    fun update(
        view: MemoryMapView?,
        status: MapStatus,
    ) {
        require(status != MapStatus.CURRENT && status != MapStatus.STALE || view != null) {
            "$status requires a valid memory map view"
        }
        synchronized(lock) {
            current = MemoryMapState(view, status)
        }
    }

    fun markMissing() {
        update(view = null, status = MapStatus.MISSING)
    }

    /** 标记状态不可判定，同时保留上一份有效 View（如有）。 */
    fun markUnknown() {
        synchronized(lock) {
            current = current.copy(status = MapStatus.UNKNOWN)
        }
    }

    fun restore(state: MemoryMapState) {
        synchronized(lock) {
            current = state
        }
    }
}
