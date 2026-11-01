package com.github.noahshen.alcyone.context.memory.core

/** 会话轮次在持久化层中的提取处理状态（内部实体，不进公开 API）。 */
enum class ExtractionStatus {
    PENDING,
    COMPLETE,
    FAILED,
}
