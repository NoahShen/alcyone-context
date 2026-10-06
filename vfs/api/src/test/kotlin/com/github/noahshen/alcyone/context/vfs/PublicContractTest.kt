package com.github.noahshen.alcyone.context.vfs

import java.lang.reflect.Modifier
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** T01 第 4、5、6.2、7、8 节的公共边界：错误码表、异常诊断字段、事件模型、选项默认值与签名纯净性。 */
class PublicContractTest {
    private val uuidV7 = "018f6a3c-9c1e-7b2d-8f3a-4c5d6e7f8091"

    // A05 错误契约
    @Test
    fun `error codes cover the T01 table`() {
        val expected =
            setOf(
                "INVALID_URI",
                "INVALID_ARGUMENT",
                "READ_ONLY",
                "STORAGE_ACCESS_DENIED",
                "NOT_FOUND",
                "ALREADY_EXISTS",
                "TYPE_MISMATCH",
                "DIRECTORY_NOT_EMPTY",
                "MOUNT_NOT_FOUND",
                "UNSUPPORTED_OPERATION",
                "LIMIT_EXCEEDED",
                "STORAGE_ERROR",
                "STATE_ERROR",
                "CONFLICT",
                "RECOVERY_REQUIRED",
                "CLOSED",
            )
        assertEquals(expected, VfsErrorCode.entries.map { it.name }.toSet())
    }

    @Test
    fun `exception carries logical uri operation id and effect`() {
        val uri = VfsUri.parse("alcyone://resources/a.txt")
        val error =
            VfsException(
                code = VfsErrorCode.STORAGE_ERROR,
                message = "backend connection lost",
                uri = uri,
                operationId = "op-1",
                effect = VfsEffect.UNKNOWN,
            )
        assertEquals(VfsErrorCode.STORAGE_ERROR, error.code)
        assertEquals(uri, error.uri)
        assertEquals("op-1", error.operationId)
        assertEquals(VfsEffect.UNKNOWN, error.effect)
        assertEquals("backend connection lost", error.message)
        assertEquals(setOf("NONE", "PARTIAL", "UNKNOWN"), VfsEffect.entries.map { it.name }.toSet())
    }

    @Test
    fun `local validation fails before any io with a domain exception`() {
        val error = assertFailsWith<VfsException> { VfsUri.parse("alcyone://resources/../a") }
        assertEquals(VfsErrorCode.INVALID_URI, error.code)
        assertEquals(VfsEffect.NONE, error.effect)
        assertTrue(RuntimeException::class.java.isInstance(error), "VfsException must stay unchecked for Java callers")
    }

    // A06 事件模型
    @Test
    fun `event types match T01 and do not expose NODE_REGISTERED`() {
        assertEquals(
            setOf(
                "FILE_CREATED",
                "FILE_WRITTEN",
                "FILE_MOVED",
                "DIRECTORY_MOVED",
                "FILE_DELETED",
                "DIRECTORY_DELETED",
                "METADATA_UPDATED",
            ),
            VfsEventType.entries.map { it.name }.toSet(),
        )
        assertTrue(VfsEventType.entries.none { it.name == "NODE_REGISTERED" })
    }

    @Test
    fun `move events carry source and target uri`() {
        val now = Instant.parse("2026-09-28T00:00:00Z")
        val event =
            VfsEvent(
                id = VfsEventId.parse(uuidV7),
                type = VfsEventType.FILE_MOVED,
                nodeId = NodeId.parse(uuidV7),
                occurredAt = now,
                uri = VfsUri.parse("alcyone://resources/b.txt"),
                operationId = "op-2",
                sourceUri = VfsUri.parse("alcyone://resources/a.txt"),
                targetUri = VfsUri.parse("alcyone://resources/b.txt"),
            )
        assertEquals(VfsEventType.FILE_MOVED, event.type)
        assertEquals("alcyone://resources/a.txt", event.sourceUri.toString())
        assertEquals(event.uri, event.targetUri)
        assertEquals(event, event.copy(id = VfsEventId.parse(uuidV7.uppercase())))
    }

    // T01 第 4.2 节结果模型与第 5 节选项默认值
    @Test
    fun `entry node id is optional and node info id is present`() {
        val uri = VfsUri.parse("alcyone://resources/report.md")
        val entry = VfsEntry(uri, NodeType.FILE, null, null)
        assertEquals(null, entry.nodeId)
        assertEquals(null, entry.storage)

        val now = Instant.parse("2026-09-28T00:00:00Z")
        val info = NodeInfo(NodeId.parse(uuidV7), uri, NodeType.DIRECTORY, now, now, StorageStat(null, null))
        assertEquals(NodeType.DIRECTORY, info.type)
        assertEquals(uuidV7, info.id.value)
        assertEquals(2, NodeType.entries.size)
    }

    @Test
    fun `options carry the approved defaults`() {
        assertEquals(null, ReadOptions().maxBytes)
        assertEquals(null, ReadOptions(null).maxBytes)
        assertEquals(WriteMode.UPSERT, WriteOptions().mode)
        assertEquals(setOf("CREATE_NEW", "REPLACE_EXISTING", "UPSERT"), WriteMode.entries.map { it.name }.toSet())
        assertTrue(StatOptions().includeStorage)
        assertTrue(!StatOptions(includeStorage = false).includeStorage)
        assertTrue(!DeleteOptions().recursive)
        assertTrue(DeleteOptions(recursive = true).recursive)
    }

    // R4：本地可判定的选项范围在 API 边界检查
    @Test
    fun `read options reject a negative limit on construction and on copy`() {
        val constructed = assertFailsWith<VfsException> { ReadOptions(-1) }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, constructed.code)
        assertEquals(VfsEffect.NONE, constructed.effect)

        val copied = assertFailsWith<VfsException> { ReadOptions(1024).copy(maxBytes = -1) }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, copied.code)
        assertEquals(VfsEffect.NONE, copied.effect)
        assertFailsWith<VfsException> { ReadOptions(Long.MIN_VALUE) }
    }

    @Test
    fun `read options accept null zero and positive limits`() {
        assertEquals(null, ReadOptions().maxBytes) // 使用 Runtime 限额，不表示无限
        assertEquals(0L, ReadOptions(0).maxBytes) // 只接受空内容
        assertEquals(1024L, ReadOptions(1024).maxBytes)
        assertEquals(0L, ReadOptions(1024).copy(maxBytes = 0).maxBytes)
        assertEquals(null, ReadOptions(1024).copy(maxBytes = null).maxBytes)
        assertEquals(1L, ReadOptions().copy(maxBytes = 1).maxBytes)
    }

    // T18 流式读取：与 ReadOptions 分开，默认不设总量上限，负数同样在构造时拒
    @Test
    fun `stream options default to no total limit and reject a negative one`() {
        assertEquals(null, VfsStreamOptions().maxTotalBytes, "默认不设总量上限，不是 0 也不是无限标志")
        assertEquals(0L, VfsStreamOptions(0).maxTotalBytes)
        assertEquals(1024L, VfsStreamOptions(maxTotalBytes = 1024).maxTotalBytes)

        val constructed = assertFailsWith<VfsException> { VfsStreamOptions(-1) }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, constructed.code)
        assertEquals(VfsEffect.NONE, constructed.effect)
        val copied = assertFailsWith<VfsException> { VfsStreamOptions(8).copy(maxTotalBytes = -8) }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, copied.code)
    }

    @Test
    fun `metadata defaults are empty`() {
        val metadata = NodeMetadata()
        assertEquals(emptySet(), metadata.tags)
        assertEquals(null, metadata.description)
        assertEquals(0, metadata.extensions.size)
    }

    // A04 公共边界
    @Test
    fun `vfs is an interface with only abstract declarations`() {
        assertTrue(Vfs::class.java.isInterface)
        val methods = Vfs::class.java.declaredMethods.filterNot { it.isSynthetic } // 过滤默认参数桥接方法
        assertEquals(
            setOf("read", "openStream", "write", "stat", "getNode", "list", "move", "delete", "getMetadata", "setMetadata"),
            methods.map { it.name }.toSet(),
        )
        for (method in methods) {
            assertTrue(Modifier.isAbstract(method.modifiers), "${method.name} must have no body in vfs/api")
        }
        assertEquals(0, methods.filter { Modifier.isStatic(it.modifiers) }.size)
    }

    @Test
    fun `public signatures expose no infrastructure or physical path types`() {
        val forbidden = listOf("org.apache.opendal", "app.cash.sqldelight", "org.sqlite", "java.sql", "java.nio.file")
        val apiTypes =
            listOf(
                Vfs::class.java,
                VfsUri::class.java,
                VfsPath::class.java,
                NodeId::class.java,
                VfsEventId::class.java,
                VfsEvent::class.java,
                VfsEventType::class.java,
                VfsException::class.java,
                VfsErrorCode::class.java,
                VfsEffect::class.java,
                VfsEntry::class.java,
                NodeInfo::class.java,
                NodeType::class.java,
                StorageStat::class.java,
                NodeMetadata::class.java,
                ReadOptions::class.java,
                VfsStreamOptions::class.java,
                VfsStreamResult::class.java,
                WriteOptions::class.java,
                WriteMode::class.java,
                StatOptions::class.java,
                DeleteOptions::class.java,
            )
        val referenced: List<String> =
            apiTypes.flatMap { type ->
                val fromMethods =
                    type.declaredMethods.flatMap { method ->
                        method.parameterTypes.map { it.name } + method.returnType.name +
                            method.genericParameterTypes.mapNotNull { (it as? Class<*>)?.name } +
                            (method.genericReturnType as? Class<*>)?.name.orEmpty()
                    }
                val fromFields =
                    type.declaredFields.flatMap { field ->
                        listOf(field.type.name, (field.genericType as? Class<*>)?.name.orEmpty())
                    }
                fromMethods + fromFields
            }
        for (name in referenced) {
            assertTrue(forbidden.none { name.startsWith(it) }, "public signature must not expose $name")
        }
        assertFailsWith<ClassNotFoundException> { Class.forName("com.github.noahshen.alcyone.context.vfs.StoragePath") }
        val classpath = System.getProperty("java.class.path")
        assertTrue(classpath.contains("kotlinx-serialization-json"), "expected serialization json on the api classpath")
        assertTrue(forbidden.none { classpath.contains(it) }, "vfs/api must not put infrastructure on its classpath")
    }
}
