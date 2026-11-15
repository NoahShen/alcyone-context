package com.github.noahshen.alcyone.context.memory.server

import java.util.Collections

/** 单个 Runtime 的静态接入注册（`server.ingestion.clients.<runtimeId>`）。 */
class IngestionClientConfig(
    val runtimeId: String,
    val runtimeType: String,
    val apiKey: String,
) {
    /** 任何打印都不得泄露 Key。 */
    override fun toString(): String = "IngestionClientConfig(runtimeId=$runtimeId, runtimeType=$runtimeType, apiKey=***)"
}

/** Server 自有请求限制；不覆盖 Context 实例上的 Ingestion 限额。 */
class ServerLimits(
    val maxSearchBodyBytes: Int = DEFAULT_MAX_SEARCH_BODY_BYTES,
    val maxResourceBytes: Long? = null,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val readyTimeoutMillis: Long = DEFAULT_READY_TIMEOUT_MILLIS,
) {
    init {
        require(maxSearchBodyBytes > 0) { "server.limits.maxSearchBodyBytes must be positive" }
        require(maxResourceBytes == null || maxResourceBytes > 0) { "server.limits.maxResourceBytes must be positive" }
        require(requestTimeoutMillis > 0) { "server.limits.requestTimeoutMillis must be positive" }
        require(readyTimeoutMillis > 0) { "server.limits.readyTimeoutMillis must be positive" }
    }

    override fun toString(): String =
        "ServerLimits(maxSearchBodyBytes=$maxSearchBodyBytes, maxResourceBytes=$maxResourceBytes, " +
            "requestTimeoutMillis=$requestTimeoutMillis, readyTimeoutMillis=$readyTimeoutMillis)"

    companion object {
        const val DEFAULT_MAX_SEARCH_BODY_BYTES: Int = 65_536
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS: Long = 60_000
        const val DEFAULT_READY_TIMEOUT_MILLIS: Long = 2_000
    }
}

/**
 * Server 启动配置（`server` 分区）。
 *
 * 注册及凭据在构造时校验并复制；`toString()` 永不输出 Key。未配置 Read/Admin Key
 * 表示对应端点在静态配置层禁用。
 */
class ServerConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    clients: Map<String, IngestionClientConfig> = emptyMap(),
    val readKey: String? = null,
    val adminKey: String? = null,
    val limits: ServerLimits = ServerLimits(),
) {
    private val ownedClients: Map<String, IngestionClientConfig>

    init {
        require(host.isNotBlank()) { "server.host must not be blank" }
        require(port in 1..65535) { "server.port must be in 1..65535" }
        IngestionClientValidation.validateMap(clients)
        ServerAuthValidation.validateKeys(clients.values, readKey, adminKey)
        ownedClients = LinkedHashMap(clients)
    }

    fun client(runtimeId: String): IngestionClientConfig? = ownedClients[runtimeId]

    /** 只读视图；底层注册集合归本实例所有。 */
    fun clients(): Map<String, IngestionClientConfig> = Collections.unmodifiableMap(ownedClients)

    override fun toString(): String =
        "ServerConfig(host=$host, port=$port, clients=${ownedClients.keys}, " +
            "readKey=${if (readKey == null) "disabled" else "***"}, " +
            "adminKey=${if (adminKey == null) "disabled" else "***"}, limits=$limits)"

    companion object {
        const val DEFAULT_HOST: String = "127.0.0.1"

        const val DEFAULT_PORT: Int = 8080
    }
}

/**
 * 兼容既有 Ingestion 测试/宿主 API 的注册包装。
 *
 * 真实认证委托给同一 [ServerAuthRegistry]；本类不保存原始 Key→对象 Map。
 */
class IngestionClientRegistry private constructor(
    internal val auth: ServerAuthRegistry,
) {
    /** 只返回已识别且类别为 Ingestion 的客户端；未知或错类均为 null。 */
    fun authenticate(authorizationHeader: String?): IngestionClientConfig? =
        (auth.authenticate(authorizationHeader) as? ServerPrincipal.Ingestion)?.client

    companion object {
        fun from(config: ServerConfig): IngestionClientRegistry = IngestionClientRegistry(ServerAuthRegistry.from(config))

        /** Collection 入口先按 runtimeId 拒绝重复，再进入统一配置校验。 */
        fun of(clients: Collection<IngestionClientConfig>): IngestionClientRegistry {
            val snapshot = clients.toList()
            IngestionClientValidation.validateCollection(snapshot)
            val byRuntimeId = LinkedHashMap<String, IngestionClientConfig>()
            snapshot.forEach { client -> byRuntimeId[client.runtimeId] = client }
            return from(ServerConfig(clients = byRuntimeId))
        }
    }
}
