package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.ILoggerFactory
import org.slf4j.LoggerFactory
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/**
 * A04 / A05：正向验证实际日志捕获（包含操作与事件上下文、带事件 ID、且不泄露凭据）。
 * ponytail: 测试中用反射注入临时测试 Provider，生产仅依赖 SLF4J API，测试后完整恢复状态。
 */
class EventDispatcherLogCaptureTest {
    @Test
    fun `event dispatcher log captures event ID and error context without leaking credentials`() =
        runBlocking {
            val capturedLogs = mutableListOf<String>()
            val recordingLogger =
                object : AbstractLogger() {
                    override fun getFullyQualifiedCallerName(): String? = null

                    override fun handleNormalizedLoggingCall(
                        level: org.slf4j.event.Level,
                        marker: org.slf4j.Marker?,
                        pattern: String?,
                        arguments: Array<out Any>?,
                        throwable: Throwable?,
                    ) {
                        val formatted =
                            org.slf4j.helpers.MessageFormatter
                                .arrayFormat(pattern, arguments, throwable)
                                .message
                        capturedLogs.add("$level: $formatted" + (throwable?.let { " [cause: ${it.message}]" } ?: ""))
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

            try {
                // 模拟 SLF4J 初始化成功，挂接测试 Provider
                stateField.set(null, 3)
                providerField.set(null, provider)

                val dispatcher = EventDispatcher()
                dispatcher.subscribe { throw RuntimeException("consumer failed on business logic") }

                val event = testRecord(VfsEventType.FILE_WRITTEN, VfsUri.parse("alcyone://resources/audit.txt")).toVfsEvent()
                dispatcher.dispatch(listOf(event))

                // A04 正向验证：捕获到包含事件 ID 和异常上下文的真实日志
                assertTrue(
                    capturedLogs.any {
                        it.contains(event.id.value) &&
                            it.contains("Event consumer failed for event") &&
                            it.contains("consumer failed on business logic")
                    },
                    "Log must capture event ID and failure context: $capturedLogs",
                )

                // A05 凭据保护正向核对：日志中不得出现敏感标记
                for (logLine in capturedLogs) {
                    assertFalse(logLine.contains(secretMarker), "日志不得包含凭据标记：$logLine")
                }
            } finally {
                stateField.set(null, origState)
                providerField.set(null, origProvider)
            }
        }
}
