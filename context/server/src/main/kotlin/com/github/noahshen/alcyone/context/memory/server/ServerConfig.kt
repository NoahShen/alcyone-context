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

/**
 * Server 启动配置（`server` 分区）。
 *
 * 只保存静态注册的 Runtime 客户端；注册集合在构造时复制，调用者之后修改原 Map 不影响本实例。
 */
class ServerConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    clients: Map<String, IngestionClientConfig> = emptyMap(),
) {
    private val ownedClients: Map<String, IngestionClientConfig>

    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535" }
        // 直接构造入口同样走统一校验，不能绕过；校验后再复制为自有快照。
        IngestionClientValidation.validateMap(clients)
        ownedClients = LinkedHashMap(clients)
    }

    fun client(runtimeId: String): IngestionClientConfig? = ownedClients[runtimeId]

    /** 只读视图；底层注册集合归本实例所有。 */
    fun clients(): Map<String, IngestionClientConfig> = Collections.unmodifiableMap(ownedClients)

    override fun toString(): String = "ServerConfig(host=$host, port=$port, clients=${ownedClients.keys})"

    companion object {
        const val DEFAULT_HOST: String = "127.0.0.1"

        const val DEFAULT_PORT: Int = 8080
    }
}

/**
 * Bearer Key → 静态注册的认证表。
 *
 * 由 [ServerConfig.clients] 的**快照**构建；调用者之后修改原注册 Map 不能绕过认证。
 */
class IngestionClientRegistry private constructor(
    private val byKey: Map<String, IngestionClientConfig>,
) {
    /** 解析 `Authorization: Bearer <key>`；缺失/格式错误/未知 Key 返回 null（由调用方映射 401）。 */
    fun authenticate(authorizationHeader: String?): IngestionClientConfig? {
        val key = bearerToken(authorizationHeader) ?: return null
        return byKey[key]
    }

    companion object {
        fun from(config: ServerConfig): IngestionClientRegistry {
            // ServerConfig 构造已校验；这里再校验一次快照，防止未来出现绕过路径。
            IngestionClientValidation.validateCollection(config.clients().values)
            val byKey = LinkedHashMap<String, IngestionClientConfig>()
            config.clients().values.forEach { client -> byKey[client.apiKey] = client }
            return IngestionClientRegistry(byKey)
        }

        /**
         * Collection 入口（测试/宿主直连）：先按 runtimeId 检测重复（**不静默覆盖**），
         * 再交给 [ServerConfig] 的统一校验与快照。
         */
        fun of(clients: Collection<IngestionClientConfig>): IngestionClientRegistry {
            val snapshot = clients.toList()
            IngestionClientValidation.validateCollection(snapshot)
            val byRuntimeId = LinkedHashMap<String, IngestionClientConfig>()
            snapshot.forEach { client -> byRuntimeId[client.runtimeId] = client }
            return from(ServerConfig(clients = byRuntimeId))
        }
    }

    private fun bearerToken(header: String?): String? {
        val text = header?.trim() ?: return null
        if (text.isEmpty()) return null
        val separator = text.indexOf(' ')
        if (separator <= 0) return null
        val scheme = text.substring(0, separator)
        val token = text.substring(separator + 1).trim()
        if (!scheme.equals("Bearer", ignoreCase = true) || token.isEmpty()) return null
        return token
    }
}
