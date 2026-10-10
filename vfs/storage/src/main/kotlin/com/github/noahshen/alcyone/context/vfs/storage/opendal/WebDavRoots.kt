package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import java.net.URI
import java.net.URISyntaxException

/**
 * 校验并整理 WebDAV 的远端位置（endpoint + root），供 Runtime 组装配置时调用（T24）。
 *
 * 和 [LocalFsRoots] 对称：装配阶段先把一整组位置校验完，那时还没有打开任何 Adapter。
 * 这里只管远端位置的边界；逻辑路径怎么路由到哪块盘是 Core 的事。
 *
 * **凭据不参与身份**：endpoint + root 才是挂载身份，换密码不算换盘（见身份串 [identity]）。
 *
 * 例：`http://127.0.0.1:8080` + `/dav/team` 的身份是 `http://127.0.0.1:8080/dav/team`。
 *
 * **位置规范化**（R1 修复）：endpoint 用 [URI] 解析，主机统一小写、默认端口（http 80 / https 443）省略、
 * 路径消解点段并拒绝 `%` 编码歧义；身份、相等与包含比较都使用同一套规范结果——
 * 完整路径 = endpoint 路径部分 + root，例如 endpoint `/dav` + root `/team` 的完整路径是 `/dav/team`。
 * 这样 `http://host/dav` + `/team` 与 `http://host` + `/dav/team` 得到相同身份串与完整路径，会被重叠检查拒绝。
 */
object WebDavRoots {
    /**
     * 整理 endpoint 与 root 这一对。
     *
     * 规则：
     * - endpoint 只接受 `http://` / `https://`，有主机、没有查询串 / 片段，也没有 `user:pass@`——
     *   凭据必须走 `MountBackend.WebDav` 的字段，不能塞进 URL（否则它会出现在身份串里）；
     * - 主机统一小写，默认端口（http 80 / https 443）省略，路径消解点段并拒绝 `%`；
     * - root 以 `/` 开头、结尾不带 `/`（根自己除外），段里不许有空段 / `.` / `..` / 控制字符 / `%`。
     *   **不套本地 Path 的规范化**：客户端无法一概识别服务端链接，边界依赖服务端配置；远端路径也没有盘符。
     * - 这一步只做字符串整理，**不发网络请求**，也不替调用方确认远端目录存在。
     */
    fun normalize(
        endpoint: String,
        root: String,
    ): Pair<String, String> = normalizeEndpoint(endpoint) to normalizeRoot(root)

    /**
     * 整理 endpoint：用 [URI] 解析，主机小写、默认端口省略、路径消解点段。
     *
     * 拒绝：非法 URI（如 `http://bad host`）、非 http/https 协议、无主机、userinfo、查询串、片段、
     * 无效端口（0 或超范围）、路径里的 `%` 编码歧义与越界 `..`。
     */
    fun normalizeEndpoint(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) throw invalid("webdav endpoint must not be empty")
        val uri = parseEndpoint(trimmed)
        val scheme = uri.scheme.lowercase()
        val host = uri.host?.lowercase() ?: throw invalid("webdav endpoint must include a host")
        val port = uri.port
        if (port != -1 && port !in 1..65535) throw invalid("webdav endpoint has an invalid port: $port")
        val omitPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val path = normalizePath(uri.rawPath)
        return buildString {
            append(scheme).append("://").append(host)
            if (!omitPort && port != -1) append(':').append(port)
            append(path)
        }
    }

    /** 解析并校验 endpoint 的 URI 结构：协议、主机、无 userinfo / 查询串 / 片段。 */
    private fun parseEndpoint(trimmed: String): URI {
        val uri =
            try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                throw invalid("webdav endpoint is not a valid absolute URI: ${e.reason}")
            }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw invalid("webdav endpoint must start with http:// or https://")
        }
        if (uri.host == null) throw invalid("webdav endpoint must include a host")
        if (uri.userInfo != null) {
            throw invalid("webdav endpoint must not carry credentials; use the mount's username and password fields")
        }
        if (uri.rawQuery != null) throw invalid("webdav endpoint must not carry a query")
        if (uri.rawFragment != null) throw invalid("webdav endpoint must not carry a fragment")
        return uri
    }

    /**
     * 整理 endpoint 的路径部分：去掉末尾 `/`、消解 `.` / `..` 段、拒绝 `%` 编码歧义。
     *
     * `%` 一律拒绝：后端会自己拼 URL 编码，用户写 `%2F` 之类的形式会和真实路径混淆。
     */
    private fun normalizePath(path: String): String {
        val segments = mutableListOf<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> {
                    if (segments.isEmpty()) throw invalid("endpoint path must not contain '..' segments that escape the root")
                    segments.removeLast()
                }
                else -> {
                    if ('%' in segment) throw invalid("endpoint path must not contain '%'; write decoded segments")
                    segments += segment
                }
            }
        }
        return if (segments.isEmpty()) "" else "/" + segments.joinToString("/")
    }

    /** 整理远端根：补开头的 `/`、去掉结尾的 `/`（根自己就是 `/`），按段拒绝空段 / `.` / `..` / `%`。 */
    fun normalizeRoot(root: String): String {
        val raw = root.trim()
        if (raw.isEmpty() || raw == "/") return "/"
        if (!raw.startsWith("/")) throw invalid("webdav root must start with '/'")
        val segments = raw.trimEnd('/').trimStart('/').split('/')
        segments.forEach { segment ->
            if (segment.isEmpty() || segment == "." || segment == "..") {
                throw invalid("webdav root must not contain empty, '.' or '..' segments")
            }
            if (segment.any { it.isISOControl() }) throw invalid("webdav root must not contain control characters")
            if ('%' in segment) throw invalid("webdav root must not contain '%'; let the backend encode paths")
        }
        return "/" + segments.joinToString("/")
    }

    /** 挂载身份串：整理后的 `endpoint + root`，**不含用户名与密码**。 */
    fun identity(
        endpoint: String,
        root: String,
    ): String = normalizeEndpoint(endpoint) + normalizeRoot(root)

    /**
     * 一组远端根必须两两不同、且同一个位置下互不包含，按**完整路径段**比较。
     *
     * 例：同一服务上 `/a` 与 `/a-old` 平级，可以共存；`/a` 与 `/a/b` 互相包含，拒绝。
     *
     * **身份查重**（R1 修复）：先按 `endpoint + root` 的完整身份串查重——`http://host/dav` + `/team`
     * 与 `http://host` + `/dav/team` 身份相同，直接拒绝；主机大小写、默认端口差异在规范化后收敛，
     * 同址写法也会得到相同身份串。
     *
     * **包含判断也用完整路径**（R1 残留修复）：endpoint 自己的路径（如 `/dav`）与 root（如 `/team`）
     * 先拼成完整路径 `/dav/team` 再比包含——只比原始 root 会漏掉 endpoint 里的路径
     * （`/dav` + `/team` 与 `/dav/team/sub` 会被当成不重叠），也会误判
     * （`/one` + `/a` 与 `/two` + `/a` 本来不重叠）。
     *
     * 识别边界：只看得见规范化后的 endpoint，**看不出两台不同 endpoint 背后是不是同一台服务器**。
     */
    fun requireNonOverlapping(targets: Collection<Pair<String, String>>) {
        val normalized = targets.map { (endpoint, root) -> normalizeEndpoint(endpoint) to normalizeRoot(root) }
        val identities = normalized.map { (endpoint, root) -> endpoint + root }
        if (identities.distinct().size != identities.size) {
            throw invalid("webdav roots must be distinct")
        }
        for (i in normalized.indices) {
            for (j in i + 1 until normalized.size) {
                val (leftEndpoint, leftRoot) = normalized[i]
                val (rightEndpoint, rightRoot) = normalized[j]
                if (!sameLocation(leftEndpoint, rightEndpoint)) continue
                val leftPath = fullPath(leftEndpoint, leftRoot)
                val rightPath = fullPath(rightEndpoint, rightRoot)
                if (leftPath == rightPath || contains(leftPath, rightPath) || contains(rightPath, leftPath)) {
                    throw invalid("webdav roots must be distinct and must not contain each other (roots $i and $j overlap)")
                }
            }
        }
    }

    /** 两个 endpoint 是否指向同一位置：协议、主机、端口相同（规范化后默认端口已省略）。 */
    private fun sameLocation(
        left: String,
        right: String,
    ): Boolean =
        URI(left).let { l ->
            URI(right).let { r -> l.scheme == r.scheme && l.host == r.host && l.port == r.port }
        }

    /**
     * 挂载在服务上的**完整路径**：endpoint 的路径部分 + root，例如 endpoint `/dav` + root `/team` → `/dav/team`。
     *
     * 身份、相等与包含比较都用它——endpoint 里的路径和 root 一样参与位置判断。
     */
    fun fullPath(
        endpoint: String,
        root: String,
    ): String = pathOf(normalizeEndpoint(endpoint)) + normalizeRoot(root)

    /** 规范 endpoint 的路径部分：`http://host:8080/dav` → `/dav`；没有路径就是空串。 */
    private fun pathOf(endpoint: String): String = normalizePath(URI(endpoint).rawPath ?: "")

    /** [outer] 按完整段包含 [inner]：`/a` 包含 `/a/b`，但不包含 `/a-old`。 */
    private fun contains(
        outer: String,
        inner: String,
    ): Boolean {
        if (outer == "/") return inner != "/"
        val outerSegments = outer.trim('/').split('/')
        val innerSegments = inner.trim('/').split('/')
        return innerSegments.size > outerSegments.size && innerSegments.subList(0, outerSegments.size) == outerSegments
    }

    private fun invalid(reason: String): VfsException = VfsException(VfsErrorCode.INVALID_ARGUMENT, "Invalid VFS configuration: $reason")
}
