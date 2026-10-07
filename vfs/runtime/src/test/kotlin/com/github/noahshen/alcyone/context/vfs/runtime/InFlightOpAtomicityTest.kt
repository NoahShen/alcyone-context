package com.github.noahshen.alcyone.context.vfs.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 验证 [InFlightOp] 的取消与登记原子性，以及 done 不受 cancelWork 影响。
 *
 * 只在同模块测试里跑，生产代码里 InFlightOp 是 internal，这里通过 internal 暴露出来测。
 */
class InFlightOpAtomicityTest {
    @Test
    @Timeout(60)
    fun `cancelWork before attachWork registration still receives cancellation`() =
        runBlocking {
            val op = AlcyoneVfs.InFlightOp()
            val reason = CancellationException("early cancel")

            // 先取消，再登记：取消请求不应丢失
            op.cancelWork(reason)
            val deferred = CompletableDeferred<CancellationException>()
            val job = kotlinx.coroutines.Job()
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) deferred.complete(cause)
            }
            op.attachWork(job)

            val received = withTimeout(2_000) { deferred.await() }
            assertEquals(reason, received, "先取消后登记，取消请求不应丢失")
        }

    @Test
    @Timeout(60)
    fun `attachWork then cancelWork immediate cancellation`() =
        runBlocking {
            val op = AlcyoneVfs.InFlightOp()
            val deferred = CompletableDeferred<CancellationException>()
            val job = kotlinx.coroutines.Job()
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) deferred.complete(cause)
            }
            op.attachWork(job)

            val reason = CancellationException("after attach")
            op.cancelWork(reason)

            val received = withTimeout(2_000) { deferred.await() }
            assertEquals(reason, received, "先登记后取消，取消应立即生效")
        }

    @Test
    @Timeout(60)
    fun `cancelWork does not complete done`() =
        runBlocking {
            val op = AlcyoneVfs.InFlightOp()
            val job = kotlinx.coroutines.Job()
            op.attachWork(job)
            op.cancelWork(CancellationException("test"))

            // done 不应被提前完成
            assertFalse(op.done.isCompleted, "cancelWork 不应完成 done")
        }
}
