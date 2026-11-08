package com.github.noahshen.alcyone.context.memory.api

import java.time.Instant

/** `/resources` 下文件的轻量只读元数据；统计字段为 `null` 表示后端未提供。 */
data class ResourceMetadata(
    val uri: String,
    val sizeBytes: Long?,
    val modifiedAt: Instant?,
)
