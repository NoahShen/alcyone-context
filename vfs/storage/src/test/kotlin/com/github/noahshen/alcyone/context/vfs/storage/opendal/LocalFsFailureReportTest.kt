package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageWriteMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.opendal.OpenDALException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertFailsWith

/**
 * A06：能力不虚报、错误可诊断、effect 与事实一致、取消不被包装（T12 §2.5）。
 *
 * 权限相关用例依赖宿主身份：以 root 运行时 POSIX 权限不生效，这些用例会**显式跳过**而不是静默通过，
 * 跳过原因写在用例里。替代证据是同一条调用链上不依赖权限的失败路径（预检失败 → NONE）。
 */
class LocalFsFailureReportTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var root: Path

    private suspend fun openStorage() = LocalFsStorage.create(root)

    private fun lockPermissions(path: Path) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r-xr-xr-x"))
    }

    /** root 身份下权限不生效，用例必须跳过而不是假装通过。 */
    private fun assumePermissionsAreEnforced() {
        assumeTrue(System.getProperty("user.name") != "root", "以 root 运行时 POSIX 权限不生效，权限类用例无法验证")
    }

    @Test
    fun `capabilities do not overstate what the adapter actually does`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                val capabilities = storage.capabilities()

                assertTrue(capabilities.nativeFileMove, "文件 rename 实测可用")
                assertFalse(capabilities.nativeDirectoryMove, "T04 实测目录 rename 返回 IsADirectory，不能声明原生目录移动")
                assertTrue(capabilities.createDirectory)
                assertTrue(capabilities.boundedRead)
                assertFalse(capabilities.readOnly, "一次权限检查不构成只读承诺")
            }
        }

    @Test
    fun `a directory move is refused as unsupported`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            openStorage().use { storage ->
                storage.createDirectory(StoragePath.parse("dir"))

                val failure = assertFailsWith<VfsException> { storage.move(StoragePath.parse("dir"), StoragePath.parse("dir2")) }

                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, failure.code)
                assertEquals(VfsEffect.NONE, failure.effect)
            }
        }

    @Test
    fun `public messages never carry the physical path and keep the original cause`() {
        val failure =
            assertFailsWith<VfsException> {
                mapStorageErrors<Unit>("read") {
                    throw OpenDALException(OpenDALException.Code.PermissionDenied, "/var/secrets/a.txt")
                }
            }

        assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, failure.code)
        assertFalse(failure.message!!.contains("/var/secrets"), "公开消息不得拼接物理路径或后端原始消息")
        assertNotNull(failure.cause)
    }

    @Test
    fun `every recognizable backend error code maps to a VFS error code`() {
        val expected =
            mapOf(
                OpenDALException.Code.NotFound to VfsErrorCode.NOT_FOUND,
                OpenDALException.Code.AlreadyExists to VfsErrorCode.ALREADY_EXISTS,
                OpenDALException.Code.ConditionNotMatch to VfsErrorCode.ALREADY_EXISTS,
                OpenDALException.Code.IsADirectory to VfsErrorCode.TYPE_MISMATCH,
                OpenDALException.Code.NotADirectory to VfsErrorCode.TYPE_MISMATCH,
                OpenDALException.Code.PermissionDenied to VfsErrorCode.STORAGE_ACCESS_DENIED,
                OpenDALException.Code.Unsupported to VfsErrorCode.UNSUPPORTED_OPERATION,
                OpenDALException.Code.ConfigInvalid to VfsErrorCode.INVALID_ARGUMENT,
                OpenDALException.Code.Unexpected to VfsErrorCode.STORAGE_ERROR,
                OpenDALException.Code.RateLimited to VfsErrorCode.STORAGE_ERROR,
            )

        expected.forEach { (backend, vfs) ->
            val failure =
                assertFailsWith<VfsException> {
                    mapStorageErrors<Unit>("write") { throw OpenDALException(backend, "raw backend text") }
                }

            assertEquals(vfs, failure.code, "后端码 $backend 应映射为 $vfs")
        }
    }

    @Test
    fun `CancellationException is never wrapped`() {
        val cancellation = CancellationException("cancelled by caller")

        val thrown =
            assertFailsWith<CancellationException> {
                mapStorageErrors<Unit>("read") { throw cancellation }
            }

        assertSame(cancellation, thrown, "取消必须原样传播，不能被包装成 VfsException")
    }

    @Test
    fun `an already-mapped VfsException keeps its own code and effect`() {
        val original = VfsException(VfsErrorCode.LIMIT_EXCEEDED, "limit", effect = VfsEffect.PARTIAL)

        val thrown = assertFailsWith<VfsException> { mapStorageErrors<Unit>("read") { throw original } }

        assertSame(original, thrown)
        assertEquals(VfsEffect.PARTIAL, thrown.effect)
    }

    @Test
    fun `precondition failures report no side effect`() =
        runBlocking {
            root = Files.createDirectory(tempDir.resolve("root"))
            Files.writeString(root.resolve("a.txt"), "original")
            openStorage().use { storage ->
                val createNew =
                    assertFailsWith<VfsException> {
                        storage.write(StoragePath.parse("a.txt"), "new".toByteArray(), StorageWriteMode.CREATE_NEW)
                    }
                val replaceMissing =
                    assertFailsWith<VfsException> {
                        storage.write(StoragePath.parse("b.txt"), "new".toByteArray(), StorageWriteMode.REPLACE_EXISTING)
                    }

                assertEquals(VfsEffect.NONE, createNew.effect)
                assertEquals(VfsEffect.NONE, replaceMissing.effect)
                assertEquals("original", Files.readString(root.resolve("a.txt")))
                assertFalse(Files.exists(root.resolve("b.txt")))
            }
        }

    @Test
    fun `a write that fails after the backend was reached reports UNKNOWN`() =
        runBlocking {
            assumePermissionsAreEnforced()
            root = Files.createDirectory(tempDir.resolve("root"))
            val locked = Files.createDirectory(root.resolve("locked"))
            lockPermissions(locked)
            openStorage().use { storage ->
                val failure =
                    assertFailsWith<VfsException> {
                        storage.write(StoragePath.parse("locked/a.txt"), "x".toByteArray(), StorageWriteMode.UPSERT)
                    }

                assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, failure.code)
                // 无法从失败点区分「文件没被创建」和「已创建但内容不全」，所以不谎报 NONE。
                assertEquals(VfsEffect.UNKNOWN, failure.effect)
                assertNotNull(failure.cause, "原始后端异常保留在 cause 上")
            }
        }

    @Test
    fun `a recursive delete that fails after some entries are gone reports PARTIAL`() =
        runBlocking {
            assumePermissionsAreEnforced()
            root = Files.createDirectory(tempDir.resolve("root"))
            Files.createDirectories(root.resolve("dir/sub/deep"))
            Files.writeString(root.resolve("dir/sub/first.txt"), "1")
            Files.writeString(root.resolve("dir/sub/deep/second.txt"), "2")
            // locked 目录里的条目已删，但删除 locked 本身需要父目录的写权限，于是中途失败。
            lockPermissions(root.resolve("dir/sub"))
            openStorage().use { storage ->
                val failure = assertFailsWith<VfsException> { storage.delete(StoragePath.parse("dir"), recursive = true) }

                assertEquals(VfsErrorCode.STORAGE_ACCESS_DENIED, failure.code)
                assertEquals(VfsEffect.PARTIAL, failure.effect, "已经删掉的条目是已知副作用")
                assertFalse(Files.exists(root.resolve("dir/sub/deep/second.txt")), "前两个条目确实已被删除")
                assertTrue(Files.isDirectory(root.resolve("dir/sub")), "失败点之后的条目仍然存在")
            }
        }
}
