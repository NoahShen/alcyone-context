package com.github.noahshen.alcyone.context.memory.api

/** 会话轮次入库结果：内部自增主键 id 与幂等状态。 */
data class IngestResult(
    val id: Long,
    val status: IngestStatus,
)
