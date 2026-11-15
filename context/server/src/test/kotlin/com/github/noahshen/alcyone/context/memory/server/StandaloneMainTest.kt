package com.github.noahshen.alcyone.context.memory.server

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StandaloneMainTest {
    @Test
    fun `standalone handler delegates fatal errors and suppresses ordinary background failures`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val delegated = AtomicReference<Throwable>()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, failure -> delegated.set(failure) }
            installStandaloneUncaughtExceptionHandler()
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            val fatal = OutOfMemoryError("controlled fatal")
            handler.uncaughtException(Thread.currentThread(), fatal)
            assertSame(fatal, delegated.get())
            delegated.set(null)
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("controlled ordinary"))
            assertEquals(null, delegated.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `library test entry does not install process globals or shutdown hook`() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousLogging = System.getProperty("slf4j.internal.verbosity")
        val result = StandaloneCommand.runForTesting(arrayOf("status"), System.out, System.err)
        assertEquals(2, result)
        assertSame(previousHandler, Thread.getDefaultUncaughtExceptionHandler())
        assertEquals(previousLogging, System.getProperty("slf4j.internal.verbosity"))
    }

    @Test
    fun `generated distribution has a slf4j policy and executable Java entry`() {
        val script =
            java.nio.file.Path
                .of("build/install/alcyone-context/bin/alcyone-context")
        val text =
            java.nio.file.Files
                .readString(script)
        assertTrue("slf4j.internal.verbosity=ERROR" in text)
        assertTrue(text.lineSequence().any { it.startsWith("exec \"\$JAVACMD\"") })
        assertFalse("org.gradle.wrapper" in text)
    }
}
