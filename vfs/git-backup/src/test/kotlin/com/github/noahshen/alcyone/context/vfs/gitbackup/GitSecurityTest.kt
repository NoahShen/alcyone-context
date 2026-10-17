package com.github.noahshen.alcyone.context.vfs.gitbackup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GitSecurityTest {
    @Test
    fun `sanitizeUrl masks password and never echoes it`() {
        val password = "T34_SYNTHETIC_PASSWORD"
        val input = "https://fixture:$password@host/repo.git"
        val output = GitSecurity.sanitizeUrl(input)

        assertEquals("https://fixture:***@host/repo.git", output)
        assertFalse(output.contains(password), "Sanitized URL must not contain the password")
    }

    @Test
    fun `sanitizeUrl masks password embedded in a larger output`() {
        val password = "T34_SYNTHETIC_PASSWORD"
        val input =
            "fatal: unable to access 'https://fixture:$password@host/repo.git/': " +
                "Could not resolve host"
        val output = GitSecurity.sanitizeUrl(input)

        assertFalse(output.contains(password), "Sanitized output must not contain the password")
        assertEquals(
            "fatal: unable to access 'https://fixture:***@host/repo.git/': Could not resolve host",
            output,
        )
    }

    @Test
    fun `sanitizeUrl leaves credential-free output untouched`() {
        val input = "fatal: repository 'file:///tmp/remote.git' not found"
        assertEquals(input, GitSecurity.sanitizeUrl(input))
    }
}
