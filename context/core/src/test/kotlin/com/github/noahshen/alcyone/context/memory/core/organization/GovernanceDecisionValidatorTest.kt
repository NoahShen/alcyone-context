package com.github.noahshen.alcyone.context.memory.core.organization

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CT14-B1 A01～A04（Core 纯逻辑）：NEW/MERGE/UPDATE 字段契约、候选权限、domains 规则（D4/D5）、
 * 空候选（D6）、非法决策**不降级 NEW / 不部分保存**。
 */
class GovernanceDecisionValidatorTest {
    private val base: Instant = Instant.parse("2026-11-01T03:00:00Z")

    private val candidates =
        listOf(
            organized("O-MEM-0001", "user prefers dark mode"),
            organized("O-MEM-0002", "user lives in Shanghai"),
        )

    // ---------------------------------------------------------------- NEW（A01）

    @Test
    fun `a new decision with content and domains is accepted`() {
        val decision =
            GovernanceDecisionValidator.validate(
                GovernanceDecision(
                    action = GovernanceAction.NEW,
                    content = "user prefers dark mode since 2026",
                    domains = listOf("preferences"),
                ),
                candidates,
            )

        assertEquals(GovernanceAction.NEW, decision.action)
        assertNull(decision.targetMemoryId)
        assertEquals("user prefers dark mode since 2026", decision.content)
        assertEquals(listOf("preferences"), decision.domains)
    }

    @Test
    fun `a new decision may not carry a target`() {
        val failure =
            assertFailsWith<GovernanceValidationException> {
                GovernanceDecisionValidator.validate(
                    GovernanceDecision(
                        action = GovernanceAction.NEW,
                        targetMemoryId = "O-MEM-0001",
                        content = "content",
                        domains = listOf("preferences"),
                    ),
                    candidates,
                )
            }

        assertTrue(failure.message.orEmpty().contains("targetMemoryId"), failure.message.orEmpty())
    }

    @Test
    fun `a new decision requires content`() {
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(action = GovernanceAction.NEW, domains = listOf("preferences")),
                candidates,
            )
        }
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(action = GovernanceAction.NEW, content = "   ", domains = listOf("preferences")),
                candidates,
            )
        }
    }

    @Test
    fun `a new decision requires non empty domains`() {
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(action = GovernanceAction.NEW, content = "content"),
                candidates,
            )
        }
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(action = GovernanceAction.NEW, content = "content", domains = emptyList()),
                candidates,
            )
        }
    }

    @Test
    fun `a new decision rejects blank domain entries`() {
        listOf("", "   ").forEach { blank ->
            assertFailsWith<GovernanceValidationException>(blank) {
                GovernanceDecisionValidator.validate(
                    GovernanceDecision(action = GovernanceAction.NEW, content = "content", domains = listOf("ok", blank)),
                    candidates,
                )
            }
        }
    }

    @Test
    fun `new domains are deduplicated by first occurrence without trimming`() {
        val decision =
            GovernanceDecisionValidator.validate(
                GovernanceDecision(
                    action = GovernanceAction.NEW,
                    content = " content with outer spaces ",
                    domains = listOf("finance", "preferences", "finance", " spaced "),
                ),
                candidates,
            )

        // 稳定去重（保留首次顺序），且不 trim 改写原文（D5）。
        assertEquals(listOf("finance", "preferences", " spaced "), decision.domains)
        assertEquals(" content with outer spaces ", decision.content)
    }

    @Test
    fun `a new decision is allowed with no candidates`() {
        val decision =
            GovernanceDecisionValidator.validate(
                GovernanceDecision(action = GovernanceAction.NEW, content = "standalone fact", domains = listOf("facts")),
                emptyList(),
            )

        assertEquals(GovernanceAction.NEW, decision.action)
        assertEquals("standalone fact", decision.content)
    }

    // ---------------------------------------------------------------- MERGE（A02）

    @Test
    fun `a merge decision with a candidate target is accepted`() {
        val decision =
            GovernanceDecisionValidator.validate(
                GovernanceDecision(
                    action = GovernanceAction.MERGE,
                    targetMemoryId = "O-MEM-0001",
                    content = "confirmed again in 2026-10",
                ),
                candidates,
            )

        assertEquals(GovernanceAction.MERGE, decision.action)
        assertEquals("O-MEM-0001", decision.targetMemoryId)
        assertEquals("confirmed again in 2026-10", decision.content)
        assertNull(decision.domains)
    }

    @Test
    fun `a merge decision may omit content`() {
        val decision =
            GovernanceDecisionValidator.validate(
                GovernanceDecision(action = GovernanceAction.MERGE, targetMemoryId = "O-MEM-0002"),
                candidates,
            )

        assertNull(decision.content)
        assertEquals("O-MEM-0002", decision.targetMemoryId)
    }

    @Test
    fun `a merge decision requires a target`() {
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(action = GovernanceAction.MERGE, content = "content"),
                candidates,
            )
        }
    }

    @Test
    fun `a merge decision rejects blank content when provided`() {
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(action = GovernanceAction.MERGE, targetMemoryId = "O-MEM-0001", content = "  "),
                candidates,
            )
        }
    }

    @Test
    fun `a merge decision rejects any domains including an empty array`() {
        // D4：提供 domains 即非法，空数组同样拒绝。
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(
                    action = GovernanceAction.MERGE,
                    targetMemoryId = "O-MEM-0001",
                    domains = emptyList(),
                ),
                candidates,
            )
        }
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(
                    action = GovernanceAction.MERGE,
                    targetMemoryId = "O-MEM-0001",
                    domains = listOf("preferences"),
                ),
                candidates,
            )
        }
    }

    // ---------------------------------------------------------------- UPDATE（A03）

    @Test
    fun `an update decision with target and content is accepted`() {
        val decision =
            GovernanceDecisionValidator.validate(
                GovernanceDecision(
                    action = GovernanceAction.UPDATE,
                    targetMemoryId = "O-MEM-0001",
                    content = "user switched back to light mode",
                ),
                candidates,
            )

        assertEquals(GovernanceAction.UPDATE, decision.action)
        assertEquals("O-MEM-0001", decision.targetMemoryId)
        assertEquals("user switched back to light mode", decision.content)
        assertNull(decision.domains)
    }

    @Test
    fun `an update decision requires content and target`() {
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(action = GovernanceAction.UPDATE, targetMemoryId = "O-MEM-0001"),
                candidates,
            )
        }
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(action = GovernanceAction.UPDATE, content = "content"),
                candidates,
            )
        }
    }

    @Test
    fun `an update decision accepts and deduplicates optional domains`() {
        val decision =
            GovernanceDecisionValidator.validate(
                GovernanceDecision(
                    action = GovernanceAction.UPDATE,
                    targetMemoryId = "O-MEM-0001",
                    content = "content",
                    domains = listOf("finance", "finance", "work"),
                ),
                candidates,
            )

        assertEquals(listOf("finance", "work"), decision.domains)
    }

    @Test
    fun `an update decision rejects invalid domains when provided`() {
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(
                    action = GovernanceAction.UPDATE,
                    targetMemoryId = "O-MEM-0001",
                    content = "content",
                    domains = emptyList(),
                ),
                candidates,
            )
        }
        assertFailsWith<GovernanceValidationException> {
            GovernanceDecisionValidator.validate(
                GovernanceDecision(
                    action = GovernanceAction.UPDATE,
                    targetMemoryId = "O-MEM-0001",
                    content = "content",
                    domains = listOf("ok", " "),
                ),
                candidates,
            )
        }
    }

    // ------------------------------------------------- 候选权限 / 不降级（A04）

    @Test
    fun `a target outside the candidates is rejected for merge and update`() {
        listOf(GovernanceAction.MERGE, GovernanceAction.UPDATE).forEach { action ->
            listOf("O-MEM-9999", "", "  ", "o-mem-0001").forEach { target ->
                assertFailsWith<GovernanceValidationException>("$action/$target") {
                    GovernanceDecisionValidator.validate(
                        GovernanceDecision(
                            action = action,
                            targetMemoryId = target,
                            content = "content",
                        ),
                        candidates,
                    )
                }
            }
        }
    }

    @Test
    fun `an invalid merge never degrades to new`() {
        val failure =
            assertFailsWith<GovernanceValidationException> {
                GovernanceDecisionValidator.validate(
                    GovernanceDecision(
                        action = GovernanceAction.MERGE,
                        targetMemoryId = "O-MEM-9999",
                        content = "content",
                        domains = listOf("preferences"),
                    ),
                    candidates,
                )
            }

        // 抛错而不是返回任何决策（尤其不是 NEW）。
        assertTrue(failure is GovernanceValidationException)
    }

    @Test
    fun `validation returns a new instance and never mutates the input decision`() {
        val input =
            GovernanceDecision(
                action = GovernanceAction.NEW,
                content = "content",
                domains = listOf("a", "a", "b"),
            )

        val validated = GovernanceDecisionValidator.validate(input, candidates)

        assertEquals(listOf("a", "a", "b"), input.domains, "输入决策不得被改写")
        assertEquals(listOf("a", "b"), validated.domains)
    }

    // ------------------------------------------------------------------ 工具

    private fun organized(
        id: String,
        content: String,
        domains: List<String> = listOf("preferences"),
    ): OrganizedMemory =
        OrganizedMemory(
            id = id,
            content = content,
            domains = domains,
            rawMemoryIds = listOf("R-MEM-0001"),
            createdAt = base,
            updatedAt = base,
        )
}
