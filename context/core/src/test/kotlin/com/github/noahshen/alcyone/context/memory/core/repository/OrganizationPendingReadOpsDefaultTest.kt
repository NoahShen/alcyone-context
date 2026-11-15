package com.github.noahshen.alcyone.context.memory.core.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class OrganizationPendingReadOpsDefaultTest {
    @Test
    fun `third party count keeps the source compatible list default`() {
        val ops =
            object : OrganizationPendingReadOps {
                override fun exists(rawId: String) = false

                override fun hasAnyPending() = true

                override fun nextByRawCreatedAt(): String? = null

                override fun listAllByRawCreatedAt() = listOf("a", "b")
            }
        assertEquals(2, ops.count())
    }
}
