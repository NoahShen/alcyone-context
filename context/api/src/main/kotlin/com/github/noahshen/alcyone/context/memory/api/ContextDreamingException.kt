package com.github.noahshen.alcyone.context.memory.api

/** Dreaming 调度错误码。 */
enum class ContextDreamingErrorCode {
    UNAVAILABLE,
    ALREADY_RUNNING,
}

/** Dreaming 调度入口错误；不挂 cause。 */
class ContextDreamingException(
    val errorCode: ContextDreamingErrorCode,
    message: String,
) : ContextException("[$errorCode] $message")

/** Dreaming run 失败阶段。 */
enum class DreamingRunStage {
    FIXED_RANGE,
    PAGE_READ,
    BUDGET,
    BATCH_PROCESSING,
}

/** 已开始 run 的具名失败；partialResult 只描述最后成功批，不伪成功。 */
class DreamingRunException(
    val stage: DreamingRunStage,
    val partialResult: DreamingResult,
    message: String,
    cause: Throwable? = null,
) : ContextException("[$stage] $message", cause) {
    init {
        require(!partialResult.completed) { "partialResult must have completed=false" }
    }
}
