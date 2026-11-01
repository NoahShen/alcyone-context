package com.github.noahshen.alcyone.context.memory.api

/** 会话轮次入库的幂等结果状态。 */
enum class IngestStatus {
    /** 首次入库成功。 */
    CREATED,

    /** 规范化内容完全一致的幂等重放，未写入新行。 */
    DUPLICATE,
}
