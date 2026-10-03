package com.github.noahshen.alcyone.context.vfs.core.event

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * T14 第二轮复核 R4：钉住「测试类上的 `@AfterEach` 真的会触发通知器清理」。
 *
 * 上一轮把 `@AfterEach` 标在 `TrackedNotifiers` 这个辅助对象上，JUnit 不会执行它——
 * 用例结束后通知器还活着，分发协程会带到下一个用例甚至测试进程退出。这里用**两条有顺序的用例**证明钩子真的跑了：
 * 第一条造一个没人手动关的通知器，第二条（跑在它之后）检查它已经被关掉。
 *
 * 不能只验证「手动调 closeAll() 有效」——那只证明 helper 能用，证明不了 JUnit 会调它。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NotifierTeardownTest {
    private val notifiers = TrackedNotifiers()

    @Test
    @Order(1)
    fun `the first case leaves a running notifier behind`() {
        val notifier = notifiers.create()
        notifier.publish(listOf(sentinelEvent()))
        TrackedNotifiers.lastCreated = notifier

        assertFalse(notifier.isClosed, "这条用例故意不手动关闭")
        assertTrue(notifier.job.isActive)
    }

    @Test
    @Order(2)
    fun `the after each hook of the first case closed it`() {
        val previous = TrackedNotifiers.lastCreated

        assertNotNull(previous, "第一条用例应该造出过一个通知器")
        assertTrue(previous!!.isClosed, "@AfterEach 没被触发：上一条用例的通知器还开着")
        assertTrue(previous.job.isCompleted, "分发协程也应该已经结束")
    }

    @AfterEach
    fun tearDown() {
        notifiers.closeAll()
    }
}
