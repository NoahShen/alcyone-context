package com.github.noahshen.alcyone.context.memory.core.ingestion

/**
 * 会话轮次入库限额（CT07）。
 *
 * 来源：`context.ingestion.{maxTurnBytes,maxMessages,maxIdLength}`；
 * 缺省 2 MiB / 256 / 256。不可变值对象，Loader 复制后归实例所有。
 */
data class IngestionLimits(
    /** 单个会话轮次规范化有效载荷（UTF-8 字节）上限。 */
    val maxTurnBytes: Int = DEFAULT_MAX_TURN_BYTES,
    /** 单个会话轮次的消息条数上限。 */
    val maxMessages: Int = DEFAULT_MAX_MESSAGES,
    /** 四个业务 ID（runtimeId / agentId / sessionId / sessionTurnId）的最大字符长度。 */
    val maxIdLength: Int = DEFAULT_MAX_ID_LENGTH,
) {
    init {
        require(maxTurnBytes > 0) { "maxTurnBytes must be a positive integer" }
        require(maxMessages > 0) { "maxMessages must be a positive integer" }
        require(maxIdLength > 0) { "maxIdLength must be a positive integer" }
    }

    companion object {
        /** 2 MiB。 */
        const val DEFAULT_MAX_TURN_BYTES: Int = 2 * 1024 * 1024

        const val DEFAULT_MAX_MESSAGES: Int = 256

        const val DEFAULT_MAX_ID_LENGTH: Int = 256

        /** CT01 蓝图缺省限额。 */
        val DEFAULTS: IngestionLimits = IngestionLimits()
    }
}
