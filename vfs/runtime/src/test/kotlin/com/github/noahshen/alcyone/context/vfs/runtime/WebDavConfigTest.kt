package com.github.noahshen.alcyone.context.vfs.runtime

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertFailsWith

/**
 * T24 A01 / A02 / A04 / A06：WebDAV 配置与 Runtime 组装，**不联网**。
 *
 * 打开 WebDAV Adapter 不发网络请求（见 `WebDavStorage.create`），所以这批用例只验配置整理、
 * 挂载身份、凭据不外泄、初始化失败释放与重开。真实读写在 `./scripts/webdav-check`。
 */
class WebDavConfigTest {
    @TempDir
    lateinit var tempDir: Path

    private val endpoint = "http://127.0.0.1:8080"

    private fun config(
        root: String = "/dav/team",
        password: String = "s3cr3t-pass",
        mounts: List<MountConfig>? = null,
    ) = VfsRuntimeConfig(
        stateDatabase = tempDir.resolve("state.db"),
        namespaces = setOf("resources"),
        mounts = mounts ?: listOf(remote("/resources/remote", "remote", root, password = password)),
    )

    private fun remote(
        path: String,
        key: String,
        root: String,
        username: String = "alice",
        password: String = "s3cr3t-pass",
    ) = MountConfig(VfsPath.parse(path), key, MountBackend.WebDav(endpoint, root, username, password))

    // ---- A01/A02：整理结果、后端类型与凭据不外泄 ----

    @Test
    fun `a webdav mount resolves to a backend type and an identity without credentials`() {
        val resolved = config(root = "/dav/team/").resolve()

        val backend = resolved.backends.getValue("remote") as MountBackend.WebDav
        assertEquals(endpoint, backend.endpoint, "endpoint 只去掉结尾斜杠，其余原样保留")
        assertEquals("/dav/team", backend.root, "远端根整理成以 / 开头、不带结尾 /")
        assertEquals("webdav", resolved.mounts.single().backendType, "挂载身份里能看出后端类型")
        assertEquals(
            "$endpoint/dav/team",
            resolved.mounts.single().physicalRoot,
            "身份 = endpoint + root，按完整路径段拼出来",
        )
        // 身份串与挂载行都不许出现凭据。
        val persisted = resolved.mounts.single().physicalRoot
        assertFalse(persisted.contains("alice"), "用户名不进身份串")
        assertFalse(persisted.contains("s3cr3t-pass"), "密码不进身份串")
    }

    @Test
    fun `printing a mount never shows the password`() {
        val mount = remote("/resources/remote", "remote", "/dav/team", password = "hunter2-super-secret")
        val printed = mount.toString() + " " + mount.backend.toString()

        assertFalse(printed.contains("hunter2-super-secret"), "配置打印会把密码带出去：$printed")
        assertTrue("***" in printed, "打印里要能看出密码被遮住了，而不是整个字段消失：$printed")
        assertEquals("$endpoint/dav/team", mount.backend.identity(), "遮住密码不影响身份")
    }

    @Test
    fun `printing a mount with credentials in the url still does not leak them`() {
        // R4 反例：配置在 resolve 之前就能打印，非法 userinfo 写法也不能把凭据带出去。
        val smuggled = MountBackend.WebDav("http://demo:synthetic-secret@localhost", "/", "", "")
        val printed = smuggled.toString()

        assertFalse(printed.contains("synthetic-secret"), "密码出现在了打印里：$printed")
        assertFalse(printed.contains("demo"), "用户名出现在了打印里：$printed")
        assertTrue("***" in printed, "userinfo 位置要能看出被遮住了：$printed")

        // 嵌套配置（MountConfig / VfsRuntimeConfig）的打印同样不泄密。
        val nested =
            MountConfig(
                VfsPath.parse("/resources/remote"),
                "remote",
                MountBackend.WebDav("http://demo:synthetic-secret@localhost", "/dav"),
            )
        val nestedPrinted = nested.toString()
        assertFalse(nestedPrinted.contains("synthetic-secret"), "嵌套打印里出现了密码：$nestedPrinted")
        assertFalse(nestedPrinted.contains("demo"), "嵌套打印里出现了用户名：$nestedPrinted")

        val config =
            VfsRuntimeConfig(
                stateDatabase = Path.of("data/state.db"),
                namespaces = setOf("resources"),
                mounts = listOf(nested),
            )
        val configPrinted = config.toString()
        assertFalse(configPrinted.contains("synthetic-secret"), "配置对象打印里出现了密码：$configPrinted")
        assertFalse(configPrinted.contains("demo"), "配置对象打印里出现了用户名：$configPrinted")
    }

    @Test
    fun `printing a mount whose endpoint has an illegal host does not echo the raw url`() {
        // R4 残留反例：主机非法（bad host）时 Java URI 只保留 registry authority、host 为 null，
        // 原文整串不能回显——用固定占位。
        val smuggled = MountBackend.WebDav("http://demo:synthetic-secret@bad_host", "/", "", "")
        val printed = smuggled.toString()

        assertFalse(printed.contains("synthetic-secret"), "密码出现在了打印里：$printed")
        assertFalse(printed.contains("bad_host"), "非法主机的原文也不该整串回显：$printed")

        // 嵌套配置（MountConfig / VfsRuntimeConfig）同样不泄密。
        val nested =
            MountConfig(
                VfsPath.parse("/resources/remote"),
                "remote",
                MountBackend.WebDav("http://demo:synthetic-secret@bad_host", "/dav"),
            )
        val nestedPrinted = nested.toString()
        assertFalse(nestedPrinted.contains("synthetic-secret"), "嵌套打印里出现了密码：$nestedPrinted")
        assertFalse(nestedPrinted.contains("bad_host"), "嵌套打印里出现了非法主机原文：$nestedPrinted")

        val config =
            VfsRuntimeConfig(
                stateDatabase = Path.of("data/state.db"),
                namespaces = setOf("resources"),
                mounts = listOf(nested),
            )
        val configPrinted = config.toString()
        assertFalse(configPrinted.contains("synthetic-secret"), "配置对象打印里出现了密码：$configPrinted")
        assertFalse(configPrinted.contains("bad_host"), "配置对象打印里出现了非法主机原文：$configPrinted")
    }

    @Test
    fun `an endpoint with credentials in the url is rejected before anything is created`() {
        val failure =
            assertFailsWith<VfsException> {
                config(
                    mounts =
                        listOf(
                            MountConfig(
                                VfsPath.parse("/resources/remote"),
                                "remote",
                                MountBackend.WebDav("http://alice:hunter2@127.0.0.1:8080", "/"),
                            ),
                        ),
                ).resolve()
            }

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
        assertFalse(failure.message!!.contains("hunter2"), "拒绝消息里不能回显凭据：${failure.message}")
    }

    @Test
    fun `the persisted mount rows never carry credentials`() {
        runBlocking {
            val vfs = AlcyoneVfs.create(config(password = "do-not-persist-me"))
            try {
                val rows = mountRows()
                assertEquals(1, rows.size)
                assertEquals("webdav", rows.single().first)
                assertEquals("$endpoint/dav/team", rows.single().second)
                assertFalse(rows.single().second.contains("do-not-persist-me"), "mount 表不存密码")
                assertFalse(rows.single().second.contains("alice"), "mount 表不存用户名")
            } finally {
                vfs.close()
            }
        }
    }

    // ---- A04：根边界与冲突 ----

    @Test
    fun `roots on the same service are compared by whole path segments`() {
        // `/a` 和 `/a-old` 平级：按字符串前缀会误判成重叠，按完整段不会。
        config(
            mounts =
                listOf(
                    remote("/resources/a", "ra", "/a"),
                    remote("/resources/old", "rb", "/a-old"),
                ),
        ).resolve()

        val nested =
            assertFailsWith<VfsException> {
                config(
                    mounts =
                        listOf(
                            remote("/resources/a", "ra", "/a"),
                            remote("/resources/ab", "rb", "/a/b"),
                        ),
                ).resolve()
            }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, nested.code, "同一个服务上互相包含的两个根要拒")

        val sameRoot =
            assertFailsWith<VfsException> {
                config(
                    mounts =
                        listOf(
                            remote("/resources/a", "ra", "/a"),
                            remote("/resources/b", "rb", "/a/"),
                        ),
                ).resolve()
            }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, sameRoot.code, "同一个根挂两个 key 也算一块盘")
    }

    @Test
    fun `overlap checks combine the endpoint path with the root`() {
        // R1 残留反例 A：endpoint `/dav` + root `/team` 与 endpoint 空路径 + root `/dav/team/sub`
        // 的完整路径是包含关系，必须拒。
        val failure =
            assertFailsWith<VfsException> {
                config(
                    mounts =
                        listOf(
                            MountConfig(VfsPath.parse("/resources/a"), "ra", MountBackend.WebDav("$endpoint/dav", "/team")),
                            MountConfig(VfsPath.parse("/resources/b"), "rb", MountBackend.WebDav(endpoint, "/dav/team/sub")),
                        ),
                ).resolve()
            }
        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)

        // R1 残留反例 B：endpoint `/one` + root `/a` 与 endpoint `/two` + root `/a` 不重叠，不该误拒。
        config(
            mounts =
                listOf(
                    MountConfig(VfsPath.parse("/resources/a"), "ra", MountBackend.WebDav("$endpoint/one", "/a")),
                    MountConfig(VfsPath.parse("/resources/b"), "rb", MountBackend.WebDav("$endpoint/two", "/a")),
                ),
        ).resolve()
    }

    @Test
    fun `one storage key cannot point at two webdav roots`() {
        val failure =
            assertFailsWith<VfsException> {
                config(
                    mounts =
                        listOf(
                            remote("/resources/a", "same", "/a"),
                            remote("/resources/b", "same", "/b"),
                        ),
                ).resolve()
            }

        assertEquals(VfsErrorCode.INVALID_ARGUMENT, failure.code)
        assertTrue(failure.message!!.contains("same"), "要说出是哪个 key 对不上：${failure.message}")
    }

    @Test
    @Timeout(60)
    fun `changing only the password keeps the stored mount identity`() =
        runBlocking {
            AlcyoneVfs.create(config(password = "old-password")).close()

            // 换了密码但 endpoint / root 没变：身份一致，同一个状态库直接重开成功。
            AlcyoneVfs.create(config(password = "new-password")).close()

            assertEquals("$endpoint/dav/team", mountRows().single().second, "mount 表里存的身份不含凭据，也没变过")
        }

    @Test
    fun `local and webdav mounts can live in the same configuration`() {
        val local = Files.createDirectory(tempDir.resolve("disk"))
        val resolved =
            config(
                mounts =
                    listOf(
                        MountConfig(VfsPath.parse("/resources/local"), "local", local),
                        remote("/resources/remote", "remote", "/dav/team"),
                    ),
            ).resolve()

        assertEquals(
            listOf("local-fs", "webdav"),
            resolved.mounts.map { it.backendType }.sorted(),
            "两类后端各记各的类型",
        )
        assertEquals(local.toRealPath(), (resolved.backends.getValue("local") as MountBackend.LocalFs).root)
        // 状态库只跟本地根比位置：远端盘没有本机路径，`requireOutsideMounts` 不会拿它去比，
        // 这条配置能解析通过本身就是证据。
        assertTrue(resolved.databasePath.isAbsolute, "状态库路径照旧收敛成绝对路径")
    }

    // ---- A06：初始化失败释放资源、正常关闭后同库可重开 ----

    @Test
    @Timeout(60)
    fun `a failure after the webdav adapter opened releases everything and the same library can be reused`() =
        runBlocking {
            val config = config()
            val injected = VfsException(VfsErrorCode.STORAGE_ERROR, "injected failure after assembly")

            var captured: AlcyoneVfs.Assembly? = null
            val thrown =
                assertFailsWith<VfsException> {
                    AlcyoneVfs.create(
                        config,
                        AlcyoneVfs.AssemblyProbe { assembly ->
                            captured = assembly
                            throw injected
                        },
                    )
                }

            assertSameInjected(injected, thrown)
            val assembly = requireNotNull(captured)
            assembly.storages.forEach { storage ->
                val closed = assertFailsWith<VfsException> { storage.stat(StoragePath.root) }
                assertEquals(VfsErrorCode.CLOSED, closed.code, "已经打开的 Adapter 必须被关掉")
            }
            assertTrue(assembly.notifier.isClosed, "通知器必须被关掉")
            assertTrue(assembly.state.isClosed, "状态库连接必须被关掉")

            // 同一个状态库再建一次（正常关闭），第二次关闭同样干净。
            val vfs = AlcyoneVfs.create(config)
            vfs.close()
            val reopened = AlcyoneVfs.create(config)
            reopened.close()
        }

    @Test
    @Timeout(60)
    fun `changing the remote root is a conflict instead of a silent re-point`() =
        runBlocking {
            AlcyoneVfs.create(config(root = "/dav/team")).close()

            val failure = assertFailsWith<VfsException> { AlcyoneVfs.create(config(root = "/dav/other")) }

            assertEquals(VfsErrorCode.CONFLICT, failure.code, "改 root 等于把旧 Node 指到另一份文件上")
            assertFalse(failure.message!!.contains("s3cr3t-pass"), "冲突消息里不能出现凭据：${failure.message}")
            // 拒绝发生在开 Adapter 之前：这次失败不留下任何新资源，原配置还能再建。
            AlcyoneVfs.create(config(root = "/dav/team")).close()
        }

    /** `mount` 表里每一行的 (backend_type, physical_root)，直接查库，不经过 VFS 公共接口。 */
    private fun mountRows(): List<Pair<String, String>> =
        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("state.db")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT backend_type, physical_root FROM mount ORDER BY vfs_path").use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString(1) to rows.getString(2))
                    }
                }
            }
        }

    private fun assertSameInjected(
        expected: VfsException,
        actual: VfsException,
    ) {
        assertTrue(actual === expected || actual.cause === expected, "原始异常原样抛出来，不被清理或包装顶掉：$actual")
    }
}
