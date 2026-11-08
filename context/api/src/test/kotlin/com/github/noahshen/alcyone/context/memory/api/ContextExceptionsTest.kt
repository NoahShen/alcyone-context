package com.github.noahshen.alcyone.context.memory.api

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContextExceptionsTest {
    @Test
    fun `异常类均继承自 ContextException 与 RuntimeException`() {
        val configEx = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, "bad config")
        val lifecycleEx = ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, "already closed")
        val persistenceEx = ContextPersistenceException(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, "missing db")
        val ingestionEx = ContextIngestionException(ContextIngestionErrorCode.TURN_CONFLICT, "content conflict")
        val resourceEx = ContextResourceException(ContextResourceErrorCode.NOT_FOUND, "resource missing")

        assertTrue(configEx is ContextException)
        assertTrue(configEx is RuntimeException)
        assertTrue(lifecycleEx is ContextException)
        assertTrue(persistenceEx is ContextException)
        assertTrue(ingestionEx is ContextException)
        assertTrue(resourceEx is ContextException)
        assertTrue(resourceEx is RuntimeException)
    }

    @Test
    fun `ContextConfigException 错误码与消息正确传递`() {
        val cause = IllegalArgumentException("cause")
        val ex = ContextConfigException(ContextConfigErrorCode.MISSING_ENV, "env missing", cause)
        assertSame(ContextConfigErrorCode.MISSING_ENV, ex.errorCode)
        assertTrue(ex.message!!.contains("MISSING_ENV"))
        assertTrue(ex.message!!.contains("env missing"))
        assertSame(cause, ex.cause)

        // 全部枚举值可实例化
        ContextConfigErrorCode.entries.forEach { code ->
            val e = ContextConfigException(code, "test")
            assertSame(code, e.errorCode)
        }
    }

    @Test
    fun `ContextLifecycleException 错误码与消息正确传递`() {
        val ex = ContextLifecycleException(ContextLifecycleErrorCode.NOT_STARTED, "not started")
        assertSame(ContextLifecycleErrorCode.NOT_STARTED, ex.errorCode)
        assertTrue(ex.message!!.contains("NOT_STARTED"))
        assertNull(ex.cause)

        ContextLifecycleErrorCode.entries.forEach { code ->
            val e = ContextLifecycleException(code, "test")
            assertSame(code, e.errorCode)
        }
    }

    @Test
    fun `ContextPersistenceException 错误码与消息正确传递`() {
        val ex = ContextPersistenceException(ContextPersistenceErrorCode.SCHEMA_VERSION_MISMATCH, "v1 != v2")
        assertSame(ContextPersistenceErrorCode.SCHEMA_VERSION_MISMATCH, ex.errorCode)
        assertTrue(ex.message!!.contains("SCHEMA_VERSION_MISMATCH"))

        ContextPersistenceErrorCode.entries.forEach { code ->
            val e = ContextPersistenceException(code, "test")
            assertSame(code, e.errorCode)
        }
    }

    @Test
    fun `ContextIngestionException 错误码与消息正确传递`() {
        val ex = ContextIngestionException(ContextIngestionErrorCode.TURN_CONFLICT, "conflict")
        assertSame(ContextIngestionErrorCode.TURN_CONFLICT, ex.errorCode)
        assertTrue(ex.message!!.contains("TURN_CONFLICT"))

        ContextIngestionErrorCode.entries.forEach { code ->
            val e = ContextIngestionException(code, "test")
            assertSame(code, e.errorCode)
        }
    }

    @Test
    fun `ContextResourceException 只暴露安全错误码且没有 cause`() {
        ContextResourceErrorCode.entries.forEach { code ->
            val exception = ContextResourceException(code, "safe resource failure")
            assertSame(code, exception.errorCode)
            assertTrue(exception.message!!.contains(code.name))
            assertTrue(exception.message!!.contains("safe resource failure"))
            assertNull(exception.cause)
        }
    }
}
