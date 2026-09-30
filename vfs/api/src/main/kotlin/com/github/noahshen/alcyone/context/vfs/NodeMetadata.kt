package com.github.noahshen.alcyone.context.vfs

import kotlinx.serialization.json.JsonObject

/**
 * 绑定 Node ID 的逻辑 Metadata。查询 / 替换只操作状态库，不访问 Storage；移动后保留。
 *
 * VFS 不解释 `extensions` 中的业务含义；没有固定 owner 字段，Agent 归属由 Context System 维护。
 */
data class NodeMetadata(
    val tags: Set<String> = emptySet(),
    val description: String? = null,
    val extensions: JsonObject = JsonObject(emptyMap()),
)
