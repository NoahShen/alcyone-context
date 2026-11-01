package com.github.noahshen.alcyone.context.memory.api

/** 对话中的一条消息，按真实顺序排列。 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
)
