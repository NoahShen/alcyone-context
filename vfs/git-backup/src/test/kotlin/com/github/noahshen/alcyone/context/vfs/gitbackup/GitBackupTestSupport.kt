@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.common.newUuidV7
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 内存 Logger：捕获格式化后的日志消息，用于验证日志契约。 */
internal class RecordingLogger : AbstractLogger() {
    data class Entry(
        val level: Level,
        val message: String,
    )

    private val entries = mutableListOf<Entry>()

    fun messages(): List<String> = synchronized(entries) { entries.map { it.message } }

    fun entriesSnapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: org.slf4j.Marker?,
        pattern: String?,
        arguments: Array<out Any>?,
        throwable: Throwable?,
    ) {
        val formatted =
            org.slf4j.helpers.MessageFormatter
                .arrayFormat(pattern, arguments, throwable)
                .message
        synchronized(entries) { entries.add(Entry(level, formatted)) }
    }

    override fun isTraceEnabled() = true

    override fun isTraceEnabled(marker: org.slf4j.Marker?) = true

    override fun isDebugEnabled() = true

    override fun isDebugEnabled(marker: org.slf4j.Marker?) = true

    override fun isInfoEnabled() = true

    override fun isInfoEnabled(marker: org.slf4j.Marker?) = true

    override fun isWarnEnabled() = true

    override fun isWarnEnabled(marker: org.slf4j.Marker?) = true

    override fun isErrorEnabled() = true

    override fun isErrorEnabled(marker: org.slf4j.Marker?) = true
}

/** 与受控调度器虚拟时间对齐的 Clock。 */
internal fun virtualClock(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): Clock =
    object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(scheduler.currentTime)
    }

/** 以给定基准时刻为起点、叠加受控调度器虚拟时间的 Clock。 */
internal fun offsetClock(
    base: java.time.ZonedDateTime,
    scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
): Clock =
    object : Clock() {
        override fun getZone(): ZoneId = base.zone

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = base.toInstant().plusMillis(scheduler.currentTime)
    }

/** 构造一个最小 VfsEvent，用于事件筛选与调度。 */
internal fun testEvent(
    uri: String,
    type: VfsEventType = VfsEventType.FILE_WRITTEN,
): VfsEvent =
    VfsEvent(
        id = VfsEventId.parse(newUuidV7().toString()),
        type = type,
        nodeId = null,
        occurredAt = Instant.now(),
        uri = VfsUri.parse(uri),
        operationId = null,
    )

/**
 * 从 [root] 出发沿字段引用（含 kotlin 函数对象）遍历，判断是否有对象满足 [predicate]。
 *
 * 用于证明订阅回调的引用结构：回调不应持有插件实例或关闭后的 Worker。
 */
internal fun referencesAny(
    root: Any,
    maxDepth: Int = 12,
    predicate: (Any) -> Boolean,
): Boolean {
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    fun traverse(
        value: Any?,
        depth: Int,
    ): Boolean {
        if (value == null || depth > maxDepth) return false
        if (predicate(value)) return true
        val cls = value.javaClass
        if (cls.isPrimitive || cls.isEnum || cls.isArray) return false
        val name = cls.name
        if (name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("kotlinx.") ||
            name.startsWith("org.slf4j.")
        ) {
            return false
        }
        if (name.startsWith("kotlin.") &&
            !name.startsWith("kotlin.jvm.internal") &&
            !name.startsWith("kotlin.jvm.functions")
        ) {
            return false
        }
        if (!visited.add(value)) return false
        var current: Class<*>? = cls
        while (current != null) {
            for (field in current.declaredFields) {
                if (java.lang.reflect.Modifier
                        .isStatic(field.modifiers)
                ) {
                    continue
                }
                try {
                    field.isAccessible = true
                    if (traverse(field.get(value), depth + 1)) return true
                } catch (e: Exception) {
                    // 忽略不可访问字段
                }
            }
            current = current.superclass
        }
        return false
    }

    return traverse(root, 0)
}

/** 回调引用结构是否可达给定实例。 */
internal fun referencesInstance(
    root: Any,
    target: Any,
): Boolean = referencesAny(root) { it === target }
