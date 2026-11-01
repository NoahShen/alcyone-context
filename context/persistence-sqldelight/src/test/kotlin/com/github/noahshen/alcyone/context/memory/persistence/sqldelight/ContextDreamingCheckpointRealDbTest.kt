package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * CT05-B2 Dreaming Checkpoint：初始水位、CAS 匹配/不匹配/后退、事务回滚保持原值。
 */
class ContextDreamingCheckpointRealDbTest {
    @Test
    fun `checkpoint starts at zero`() =
        ConversationFixtures.withDatabase { executor ->
            assertEquals(0L, DreamingCheckpointRepositoryImpl(executor).readCheckpoint())
        }

    @Test
    fun `cas updates only when expected matches and next moves forward`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = DreamingCheckpointRepositoryImpl(executor)

            assertEquals(1, B2Fixtures.casCheckpoint(executor, expected = 0, next = 5))
            assertEquals(5L, repository.readCheckpoint())

            // expected 不匹配：0 行且值不变。
            assertEquals(0, B2Fixtures.casCheckpoint(executor, expected = 0, next = 9))
            assertEquals(5L, repository.readCheckpoint())

            // next 未严格向前：0 行且值不变。
            assertEquals(0, B2Fixtures.casCheckpoint(executor, expected = 5, next = 5))
            assertEquals(0, B2Fixtures.casCheckpoint(executor, expected = 5, next = 4))
            assertEquals(5L, repository.readCheckpoint())

            assertEquals(1, B2Fixtures.casCheckpoint(executor, expected = 5, next = 6))
            assertEquals(6L, repository.readCheckpoint())
        }

    @Test
    fun `rollback keeps the checkpoint value`() =
        ConversationFixtures.withDatabase { executor ->
            val repository = DreamingCheckpointRepositoryImpl(executor)
            assertFailsWith<IllegalStateException> {
                executor.write { tx ->
                    assertEquals(1, DreamingCheckpointTxOpsImpl(tx).casUpdateCheckpoint(0, 3))
                    throw IllegalStateException("boom")
                }
            }
            assertEquals(0L, repository.readCheckpoint())

            assertEquals(1, B2Fixtures.casCheckpoint(executor, expected = 0, next = 3))
            assertEquals(3L, repository.readCheckpoint())
        }
}
