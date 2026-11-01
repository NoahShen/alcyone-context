package com.github.noahshen.alcyone.context.memory.runtime.llm

import org.slf4j.ILoggerFactory
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArrayList

/** 捕获到的一条日志：级别、**已格式化**文本与 Throwable（用于断言「不把异常传给 logger」）。 */
internal data class CapturedLog(
    val level: Level,
    val formattedMessage: String,
    val throwable: Throwable?,
)

/**
 * CT08-B5 测试用 SLF4J 记录器：注入测试 Provider，捕获真实日志后恢复全局状态。
 *
 * 参考既有 `EventDispatcherLogCaptureTest` 的 Provider 注入与恢复模式（`INITIALIZATION_STATE` +
 * `PROVIDER` 反射字段）。**必须在创建被测 client 之前安装**（client 的 logger 在构造时解析）。
 */
internal class Slf4jCapture : AutoCloseable {
    val logs: MutableList<CapturedLog> = CopyOnWriteArrayList()

    private val stateField: Field =
        LoggerFactory::class.java.getDeclaredField("INITIALIZATION_STATE").apply { isAccessible = true }
    private val providerField: Field =
        LoggerFactory::class.java.getDeclaredField("PROVIDER").apply { isAccessible = true }
    private val originalState: Any? = stateField.get(null)
    private val originalProvider: Any? = providerField.get(null)
    private var installed: Boolean = false

    init {
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
                    logs += CapturedLog(level = level, formattedMessage = formatted, throwable = throwable)
                }

                override fun isTraceEnabled(): Boolean = true

                override fun isTraceEnabled(marker: org.slf4j.Marker?): Boolean = true

                override fun isDebugEnabled(): Boolean = true

                override fun isDebugEnabled(marker: org.slf4j.Marker?): Boolean = true

                override fun isInfoEnabled(): Boolean = true

                override fun isInfoEnabled(marker: org.slf4j.Marker?): Boolean = true

                override fun isWarnEnabled(): Boolean = true

                override fun isWarnEnabled(marker: org.slf4j.Marker?): Boolean = true

                override fun isErrorEnabled(): Boolean = true

                override fun isErrorEnabled(marker: org.slf4j.Marker?): Boolean = true
            }
        val provider =
            object : SLF4JServiceProvider {
                private val factory = ILoggerFactory { recordingLogger }

                override fun getLoggerFactory() = factory

                override fun getMarkerFactory() = BasicMarkerFactory()

                override fun getMDCAdapter() = NOPMDCAdapter()

                override fun getRequestedApiVersion() = "2.0.99"

                override fun initialize() = Unit
            }
        // 模拟 SLF4J 初始化成功并挂接测试 Provider。
        stateField.set(null, 3)
        providerField.set(null, provider)
        installed = true
    }

    /** 消息包含 [event] 的记录。 */
    fun recordsFor(event: String): List<CapturedLog> = logs.filter { it.formattedMessage.contains(event) }

    override fun close() {
        if (!installed) return
        try {
            providerField.set(null, originalProvider)
        } finally {
            stateField.set(null, originalState)
        }
    }
}
