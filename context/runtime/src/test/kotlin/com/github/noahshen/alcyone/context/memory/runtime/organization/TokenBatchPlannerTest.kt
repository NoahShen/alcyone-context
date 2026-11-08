package com.github.noahshen.alcyone.context.memory.runtime.organization

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CT13-B1 A02/A06（通用预算分批）：单批/多批、换批后单条重检、固定开销与单条超限、
 * 等于边界允许、不跳过/不返回部分，以及检查循环中的取消传播。
 */
class TokenBatchPlannerTest {
    /** 每个条目 100 tokens；[maxInput] 为“每批允许的条目数 × 100”。 */
    private fun budget(maxInput: Int): CandidateBatchBudget<String> =
        CandidateBatchBudget { batch ->
            val required = batch.size * 100L
            if (required <= maxInput) null else required
        }

    @Test
    fun `an empty item list produces no batches`() =
        runBlocking {
            assertEquals(emptyList(), TokenBatchPlanner.plan(emptyList<String>(), budget(100)))
        }

    @Test
    fun `all items fit into one batch`() =
        runBlocking {
            assertEquals(
                listOf(listOf("a", "b", "c")),
                TokenBatchPlanner.plan(listOf("a", "b", "c"), budget(300)),
            )
        }

    @Test
    fun `items are split into multiple batches preserving order`() =
        runBlocking {
            assertEquals(
                listOf(listOf("a", "b"), listOf("c", "d"), listOf("e")),
                TokenBatchPlanner.plan(listOf("a", "b", "c", "d", "e"), budget(200)),
            )
        }

    @Test
    fun `the equality boundary counts as fitting`() =
        runBlocking {
            assertEquals(
                listOf(listOf("a", "b")),
                TokenBatchPlanner.plan(listOf("a", "b"), budget(200)),
            )
        }

    @Test
    fun `fixed overhead over budget fails before any batch`() =
        runBlocking {
            val fixedOver = CandidateBatchBudget<String> { batch -> if (batch.isEmpty()) 500L else null }
            val failure =
                assertFailsWith<CandidateBudgetExceededException> {
                    TokenBatchPlanner.plan(listOf("a"), fixedOver)
                }
            assertEquals(CandidateBudgetExceededException.Reason.FIXED_OVERHEAD, failure.reason)
            assertEquals(500L, failure.requiredTotalTokens)
        }

    @Test
    fun `a first item that cannot fit fails explicitly`() =
        runBlocking {
            val failure =
                assertFailsWith<CandidateBudgetExceededException> {
                    TokenBatchPlanner.plan(listOf("a", "b"), budget(50))
                }
            assertEquals(CandidateBudgetExceededException.Reason.SINGLE_ITEM, failure.reason)
            assertEquals(100L, failure.requiredTotalTokens)
        }

    @Test
    fun `an oversized item after a closed batch is re-checked and fails`() =
        runBlocking {
            // "b" 单独 100 能放进 100 的预算；"c" 与 "d" 都超，但先证明换批后会对当前条目重检：
            // 这里用按条目长度定价的预算，让 "cc" 单独就超限。
            val variable =
                CandidateBatchBudget<String> { batch ->
                    val required = batch.sumOf { item -> item.length.toLong() * 100 }
                    if (required <= 250) null else required
                }
            // "aa"(200) 与 "bb"(200) 相加 400 > 250 → 封批；"bb" 单独 200 ≤ 250 → 新批；
            // "cccc"(400) 单独就 > 250 → 换批后重检失败（不会因为上一批非空而默认容纳）。
            val failure =
                assertFailsWith<CandidateBudgetExceededException> {
                    TokenBatchPlanner.plan(listOf("aa", "bb", "cccc"), variable)
                }
            assertEquals(CandidateBudgetExceededException.Reason.SINGLE_ITEM, failure.reason)
            assertEquals(400L, failure.requiredTotalTokens)
        }

    @Test
    fun `every item is assigned exactly once across batches`() =
        runBlocking {
            val items = (1..7).map { index -> "item-$index" }
            val batches = TokenBatchPlanner.plan(items, budget(200))
            assertEquals(items, batches.flatten(), "不得跳过、不得重复、保持顺序")
            assertTrue(batches.all { batch -> batch.size <= 2 })
        }

    @Test
    fun `cancellation during the budget loop propagates`() =
        runBlocking {
            // 确定性受控取消（不依赖 withTimeout/调度时序）：
            // 预算回调在第 3 次检查处发出信号并挂起在受控屏障上，测试线程随后让该屏障以
            // CancellationException 结束；planner 必须把 CE 原样抛出，不得吞掉取消返回部分批次，
            // 也不得再执行任何后续预算检查。
            val checks =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val suspended = CompletableDeferred<Unit>()
            val barrier = CompletableDeferred<Unit>()
            val scope = CoroutineScope(Dispatchers.Default)
            val planner =
                scope.async {
                    TokenBatchPlanner.plan((1..50).map { it.toString() }) { batch ->
                        val check = checks.incrementAndGet()
                        if (check == 3) {
                            suspended.complete(Unit)
                            barrier.await()
                        }
                        if (batch.size * 100L <= 100L) null else batch.size * 100L
                    }
                }
            try {
                suspended.await()

                barrier.completeExceptionally(CancellationException("controlled cancellation"))
                // 若 planner 吞掉 CE 返回部分批次，await() 会正常返回 → 本断言失败。
                assertFailsWith<CancellationException> { planner.await() }
                // 取消发生在第 3 次检查的挂起点：不得再有任何后续预算检查。
                assertEquals(3, checks.get(), "取消后不得继续预算检查")
            } finally {
                planner.cancelAndJoin()
                scope.cancel()
            }
            // 保持 JUnit 5 可见：表达式体不能以非 Unit 值结尾。
            Unit
        }
}
