package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException

/**
 * 校验并整理 WebDAV 的远端位置（endpoint + root），供 Runtime 组装配置时调用（T24）。
 *
 * 和 [LocalFsRoots] 对称：装配阶段先把一整组位置校验完，那时还没有打开任何 Adapter。
 * 这里只管远端位置的边界；逻辑路径怎么路由到哪块盘是 Core 的事。
 *
 * **凭据不参与身份**：endpoint + root 才是挂载身份，换密码不算换盘（见身份串 [identity]）。
 *
 * 例：`http://127.0.0.1:8080` + `/dav/team` 的身份是 `http://127.0.0.1:8080/dav/team`。
 */
object WebDavRoots {
    /**
     * 整理 endpoint 与 root 这一对。
     *
     * 规则：
     * - endpoint 只接受 `http://` / `https://`，有主机、没有查询串 / 片段，也没有 `user:pass@`——
     *   凭据必须走 `MountBackend.WebDav` 的字段，不能塞进 URL（否则它会出现在身份串里）；
     * - root 以 `/` 开头、结尾不带 `/`（根自己除外），段里不许有空段 / `.` / `..` / 控制字符。
     *   **不套本地 Path 的规范化**：远端 URL 没有符号链接，也没有盘符。
     * - 这一步只做字符串整理，**不发网络请求**，也不替调用方确认远端目录存在。
     */
    fun normalize(
        endpoint: String,
        root: String,
    ): Pair<String, String> = normalizeEndpoint(endpoint) to normalizeRoot(root)

    /** 整理 endpoint：去掉结尾多余的 `/`，其余必须原样保留（大小写、端口都算身份）。 */
    fun normalizeEndpoint(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) throw invalid("webdav endpoint must not be empty")
        val scheme = SCHEME.find(trimmed) ?: throw invalid("webdav endpoint must start with http:// or https://")
        val rest = trimmed.substring(scheme.value.length)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) throw invalid("webdav endpoint must include a host")
        if ('@' in authority) {
            throw invalid("webdav endpoint must not carry credentials; use the mount's username and password fields")
        }
        if ('?' in rest || '#' in rest) throw invalid("webdav endpoint must not carry a query or fragment")
        val path = rest.substring(authority.length).trimEnd('/')
        return scheme.value + authority + path
    }

    /** 整理远端根：补开头的 `/`、去掉结尾的 `/`（根自己就是 `/`），按段拒绝空段 / `.` / `..`。 */
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
        }
        return "/" + segments.joinToString("/")
    }

    /** 挂载身份串：整理后的 `endpoint + root`，**不含用户名与密码**。 */
    fun identity(
        endpoint: String,
        root: String,
    ): String = normalizeEndpoint(endpoint) + normalizeRoot(root)

    /**
     * 一组远端根必须两两不同、且同一个 endpoint 下互不包含，按**完整路径段**比较。
     *
     * 例：同一服务上 `/a` 与 `/a-old` 平级，可以共存；`/a` 与 `/a/b` 互相包含，拒绝。
     * 识别边界：只看得见 endpoint 字符串，**看不出两台不同 endpoint 背后是不是同一台服务器**。
     */
    fun requireNonOverlapping(targets: Collection<Pair<String, String>>) {
        val normalized = targets.map { (endpoint, root) -> normalizeEndpoint(endpoint) to normalizeRoot(root) }
        for (i in normalized.indices) {
            for (j in i + 1 until normalized.size) {
                val (leftEndpoint, leftRoot) = normalized[i]
                val (rightEndpoint, rightRoot) = normalized[j]
                if (leftEndpoint != rightEndpoint) continue
                if (leftRoot == rightRoot || contains(leftRoot, rightRoot) || contains(rightRoot, leftRoot)) {
                    throw invalid("webdav roots must be distinct and must not contain each other (roots $i and $j overlap)")
                }
            }
        }
    }

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

    private val SCHEME = Regex("^[Hh][Tt][Tt][Pp][Ss]?://")

    private fun invalid(reason: String): VfsException = VfsException(VfsErrorCode.INVALID_ARGUMENT, "Invalid VFS configuration: $reason")
}
