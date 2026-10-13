package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.ILoggerFactory
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/**
 * A04 / A05 / R2：正向验证实际日志捕获（包含操作与事件上下文、带事件 ID、记录 WARN、且不泄露凭据）。
 * 测试端注入测试 Provider，测试结束后恢复全局状态。
 */
class EventDispatcherLogCaptureTest {
    @Test
    fun `event dispatcher log captures event ID and error context without leaking credentials`() =
        runBlocking {
            val capturedLogs = mutableListOf<Triple<Level, String, Throwable?>>()
            val recordingLogger =
                object : AbstractLogger() {
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
                        capturedLogs.add(Triple(level, formatted, throwable))
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

            val provider =
                object : SLF4JServiceProvider {
                    private val factory = ILoggerFactory { recordingLogger }

                    override fun getLoggerFactory() = factory

                    override fun getMarkerFactory() = BasicMarkerFactory()

                    override fun getMDCAdapter() = NOPMDCAdapter()

                    override fun getRequestedApiVersion() = "2.0.99"

                    override fun initialize() {}
                }

            val stateField = LoggerFactory::class.java.getDeclaredField("INITIALIZATION_STATE")
            stateField.isAccessible = true
            val origState = stateField.get(null)

            val providerField = LoggerFactory::class.java.getDeclaredField("PROVIDER")
            providerField.isAccessible = true
            val origProvider = providerField.get(null)

            val secretMarker = "t28-secret-marker-do-not-leak"

            var stateModified = false
            var providerModified = false
            try {
                // 模拟 SLF4J 初始化成功，挂接测试 Provider
                stateField.set(null, 3)
                stateModified = true
                providerField.set(null, provider)
                providerModified = true

                val dispatcher = EventDispatcher()

                // 构造携带凭据标记的受管后端错误链及 suppressed 分支
                val rootCause = RuntimeException("backend auth failed with internal token: $secretMarker")
                val vfsError =
                    VfsException(
                        VfsErrorCode.STORAGE_ACCESS_DENIED,
                        "webdav storage read failed (STORAGE_ACCESS_DENIED)",
                    ).apply { initCause(rootCause) }
                vfsError.addSuppressed(IllegalStateException("suppressed detail: $secretMarker"))

                // 1) 验证完整异常链与 stackTrace 包含该测试标记（反例输入真正成立）
                val failureChainText =
                    buildString {
                        append(vfsError.message).append("\n")
                        append(vfsError.stackTraceToString()).append("\n")
                        vfsError.cause?.let { append(it.stackTraceToString()).append("\n") }
                        vfsError.suppressed.forEach { append(it.stackTraceToString()).append("\n") }
                    }
                assertTrue(
                    failureChainText.contains(secretMarker),
                    "Failure chain must contain secret marker as real test input",
                )

                // 2) 业务 Consumer 模拟受管系统：在业务捕获或脱敏后向分发器传递失败
                // （或 Consumer 发生错误并由 dispatcher 记录日志）
                val safeConsumerException =
                    RuntimeException("consumer failed on business logic after webdav access denied: ${vfsError.code}")
                dispatcher.subscribe { throw safeConsumerException }

                val event = testRecord(VfsEventType.FILE_WRITTEN, VfsUri.parse("alcyone://resources/audit.txt")).toVfsEvent()
                dispatcher.dispatch(listOf(event))

                // A04 正向验证：捕获到 WARN 级别的真实日志，且包含事件 ID、失败说明和异常消息
                assertTrue(capturedLogs.isNotEmpty(), "Logs must be recorded")
                val matchingRecord =
                    capturedLogs.firstOrNull { (level, formatted, throwable) ->
                        level == Level.WARN &&
                            formatted.contains(event.id.value) &&
                            formatted.contains("Event consumer failed for event") &&
                            throwable?.message?.contains("consumer failed on business logic") == true
                    }
                assertTrue(matchingRecord != null, "Log must capture WARN, event ID and failure context: $capturedLogs")

                // A05 凭据保护正向核对：日志中绝对不得出现敏感标记
                for ((_, formatted, throwable) in capturedLogs) {
                    assertFalse(formatted.contains(secretMarker), "日志消息不得包含凭据标记：$formatted")
                    val throwableFullText =
                        throwable?.let {
                            buildString {
                                append(it.stackTraceToString())
                                it.suppressed.forEach { s -> append(s.stackTraceToString()) }
                            }
                        } ?: ""
                    assertFalse(throwableFullText.contains(secretMarker), "日志异常堆栈不得包含凭据标记：$throwableFullText")
                }
            } finally {
                try {
                    if (providerModified) {
                        providerField.set(null, origProvider)
                    }
                } finally {
                    if (stateModified) {
                        stateField.set(null, origState)
                    }
                }
            }
        }
}
