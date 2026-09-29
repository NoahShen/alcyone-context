package alcyone.vfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T01 第 4.1 节：NodeId 是 UUIDv7 文本形式，移动后不变，不是路径的哈希。 */
class NodeIdTest {
    private val uuidV7 = "018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8091"

    @Test
    fun `valid uuid v7 is accepted and normalized to lower case`() {
        assertEquals(uuidV7, NodeId.parse(uuidV7).value)
        assertEquals(uuidV7, NodeId.parse(uuidV7.uppercase()).value)
        assertEquals(uuidV7.uppercase(), NodeId.parse(uuidV7.uppercase()).toString().uppercase())
    }

    @Test
    fun `value semantics`() {
        assertEquals(NodeId.parse(uuidV7), NodeId.parse(uuidV7))
        assertEquals(NodeId.parse(uuidV7).hashCode(), NodeId.parse(uuidV7).hashCode())
        assertTrue(NodeId.parse(uuidV7) != VfsEventId.parse(uuidV7))
        assertNotEquals(NodeId.parse(uuidV7), NodeId.parse("018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8092"))
    }

    @Test
    fun `non uuid v7 input is rejected locally`() {
        val invalid =
            listOf(
                "",
                "not-a-uuid",
                "018f6a3c9c1e7b2d8f3a4c5d6e7f8091",
                "{018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8091}",
                "urn:uuid:018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8091",
                "018f6a3c-9c1e-4b2d-8f3a-4c5d6e7f8091", // version 4
                "018f6a3c-9c1e-7b2d-cf3a-4c5d6e7f8091", // variant not RFC 4122
                "018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f809z", // non hex
            )
        for (text in invalid) {
            val error = assertFailsWith<VfsException>("expected INVALID_ARGUMENT for: $text") { NodeId.parse(text) }
            assertEquals(VfsErrorCode.INVALID_ARGUMENT, error.code)
        }
    }

    @Test
    fun `event id uses the same uuid v7 rule`() {
        assertEquals(uuidV7, VfsEventId.parse(uuidV7.uppercase()).value)
        val error = assertFailsWith<VfsException> { VfsEventId.parse("018f6a3c-9c1e-4b2d-8f3a-4c5d6e7f8091") }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, error.code)
    }

    @Test
    fun `both vfs id types accept every variant nibble in both cases`() {
        val forms =
            listOf(
                "018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8091",
                "018f6a3c-9c1e-7b2d-9f3a-4c5d6e7f8091",
                "018f6a3c-9c1e-7b2d-af3a-4c5d6e7f8091",
                "018f6a3c-9c1e-7b2d-bf3a-4c5d6e7f8091",
            )
        for (form in forms) {
            for (written in listOf(form, form.uppercase())) {
                assertEquals(form, NodeId.parse(written).value, "NodeId must accept $written")
                assertEquals(form, VfsEventId.parse(written).value, "VfsEventId must accept $written")
            }
        }
    }

    @Test
    fun `both vfs id types reject non ascii characters as invalid argument`() {
        val invalid =
            listOf(
                "\u0660" + "18f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8091",
                "018F6A3C-9C1E-7B2D-C3A-4C5D6E7F8091", // variant 小写 c，不是 8/9/a/b
                "018f6a3c-9c1e-7b2d-\uFF2D3a-4c5d6e7f8091",
            )
        for (text in invalid) {
            val nodeError = assertFailsWith<VfsException>("NodeId must reject $text") { NodeId.parse(text) }
            assertEquals(VfsErrorCode.INVALID_ARGUMENT, nodeError.code)
            assertEquals(VfsEffect.NONE, nodeError.effect)
            val eventError = assertFailsWith<VfsException>("VfsEventId must reject $text") { VfsEventId.parse(text) }
            assertEquals(VfsErrorCode.INVALID_ARGUMENT, eventError.code)
        }
    }
}
