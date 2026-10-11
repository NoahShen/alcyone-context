package com.github.noahshen.alcyone.context.vfs.integration.webdav

import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountBackend
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager

/**
 * T25：真实 WebDAV 用例共用的夹具信息与小组件。
 *
 * 服务由 `./scripts/webdav-check` 起（WsgiDAV + Cheroot，只听 127.0.0.1、测试凭据、一次性临时根，
 * 外加一块**真实只读**共享 `/readonly`）。**没有服务就直接失败**——这批用例不提供「跳过也算通过」的口子。
 *
 * 这里只放跨用例复用的东西：环境变量读取、物理根对照、同库重开、分块摘要、只读 SQL 查询。
 * 每类用例各自的装置（故障 HTTP 服务）留在自己文件里。
 */
object WebDavFixture {
    val endpoint: String get() = requireVariable("T24_WEBDAV_ENDPOINT")
    val user: String get() = requireVariable("T24_WEBDAV_USER")
    val password: String get() = requireVariable("T24_WEBDAV_PASSWORD")

    /** 服务数据根：脚本通过 `T24_WEBDAV_DATA_ROOT` 传进来，用于核对物理文件。 */
    fun dataRoot(): Path = Path.of(requireVariable("T24_WEBDAV_DATA_ROOT"))

    /** T25 A05：只读共享的物理根，脚本通过 `T25_WEBDAV_READONLY_ROOT` 传进来。 */
    fun readOnlyRoot(): Path = Path.of(requireVariable("T25_WEBDAV_READONLY_ROOT"))

    private fun requireVariable(name: String): String =
        System.getenv(name)
            ?: error("$name is not set: run these tests through ./scripts/webdav-check. A missing service is a failure here, never a skip.")

    /** 一条挂到 `/resources/remote` 的 WebDAV 挂载；root / 凭据 / endpoint 可按用例换。 */
    fun remoteMount(
        storageKey: String = "remote",
        endpoint: String = this.endpoint,
        root: String = "/",
        password: String = this.password,
        path: String = "/resources/remote",
    ) = MountConfig(VfsPath.parse(path), storageKey, MountBackend.WebDav(endpoint, root, user, password))

    /** 只挂一块 WebDAV 盘的 Runtime 配置。 */
    fun remoteOnlyConfig(
        stateDatabase: Path,
        endpoint: String = this.endpoint,
        root: String = "/",
        password: String = this.password,
    ) = VfsRuntimeConfig(
        stateDatabase = stateDatabase,
        namespaces = setOf("resources"),
        mounts = listOf(remoteMount(endpoint = endpoint, root = root, password = password)),
    )

    /**
     * 一块本机盘 + 一块 WebDAV 盘的 Runtime 配置（T25 A03 / A04 跨后端用）。
     *
     * `/resources/local` 落到 [localRoot] 这个已存在的本机目录，`/resources/remote` 落到 WebDAV 服务根；
     * 两块盘路径不重叠，跨后端移动走 Core 复制删除。
     */
    fun crossBackendConfig(
        stateDatabase: Path,
        localRoot: Path,
        endpoint: String = this.endpoint,
        password: String = this.password,
    ) = VfsRuntimeConfig(
        stateDatabase = stateDatabase,
        namespaces = setOf("resources"),
        mounts =
            listOf(
                MountConfig(VfsPath.parse("/resources/local"), "local", localRoot),
                remoteMount(endpoint = endpoint, password = password),
            ),
    )

    /** 把服务端共享根下的一个子路径单独挂一块 WebDAV 盘，用来验证只读共享这类子挂载（T25 A05）。 */
    fun readOnlyMount(
        mountKey: String = "readonly",
        remotePath: String = "/readonly",
    ) = MountConfig(VfsPath.parse("/resources/$mountKey"), mountKey, MountBackend.WebDav(endpoint, remotePath, user, password))

    /** create → 用 → close 三步；`AlcyoneVfs.close` 是挂起函数，所以用 try/finally。 */
    suspend fun <T> withVfs(
        config: VfsRuntimeConfig,
        block: suspend (AlcyoneVfs) -> T,
    ): T {
        val vfs = AlcyoneVfs.create(config)
        return try {
            block(vfs)
        } finally {
            vfs.close()
        }
    }

    /** 分块算一个文件的 SHA-256，整份不进内存；返回字节数与摘要。 */
    fun fileDigest(path: Path): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        java.nio.file.Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                total += read
            }
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 分块算一个流的 SHA-256，整份不进内存；返回字节数与摘要。 */
    fun streamDigest(input: java.io.InputStream): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            total += read
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 只读查库，返回每行第一列；不替被测操作写数据。 */
    fun query(
        database: Path,
        sql: String,
    ): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1) ?: "") }
                }
            }
        }

    /** 一条事件取 `event_type / node_id / uri / source_uri / target_uri` 五项。 */
    fun eventRows(database: Path): List<List<String>> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT event_type, node_id, uri, source_uri, target_uri FROM event ORDER BY rowid")
                    .use { rows ->
                        buildList {
                            while (rows.next()) {
                                add((1..5).map { rows.getString(it) ?: "" })
                            }
                        }
                    }
            }
        }
}
