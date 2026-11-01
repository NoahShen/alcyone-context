package com.github.noahshen.alcyone.context.memory.runtime.ingestion

import com.github.noahshen.alcyone.context.memory.api.ChatMessage
import com.github.noahshen.alcyone.context.memory.api.ChatRole
import com.github.noahshen.alcyone.context.memory.api.ConversationTurn
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 可控时钟：测试用它推进 createdAt，验证 DUPLICATE 不刷新。 */
internal class MutableClock(
    private var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advanceTo(instant: Instant) {
        current = instant
    }
}

internal object IngestionTurns {
    val T0: Instant = Instant.parse("2026-10-29T00:00:00.000Z")

    fun messages(vararg contents: String): List<ChatMessage> =
        contents.mapIndexed { index, content -> ChatMessage(if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT, content) }

    fun turn(
        runtimeId: String = "runtime-1",
        agentId: String = "agent-1",
        sessionId: String = "session-1",
        sessionTurnId: String = "turn-1",
        timestamp: Instant = T0,
        messages: List<ChatMessage> = messages("hello"),
    ): ConversationTurn =
        ConversationTurn.create(
            runtimeId = runtimeId,
            agentId = agentId,
            sessionId = sessionId,
            sessionTurnId = sessionTurnId,
            timestamp = timestamp,
            messages = messages,
        )
}
