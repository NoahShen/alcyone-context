package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException

/**
 * 静态 Runtime 注册的统一校验（F3）。
 *
 * 三个入口——`ServerConfig` 直接构造（Map）、YAML Loader、[IngestionClientRegistry.of]
 * （Collection）——共用本函数，避免任何一处绕过校验或静默覆盖注册：
 * - `runtimeId` 非空白；作为 Map key 时必须与配置对象的 `runtimeId` 一致；
 * - `runtimeType` / `apiKey` 非空白；
 * - 重复 `runtimeId` 具名拒绝（**不得静默覆盖**）；
 * - 同一 `apiKey` 绑定不同 Runtime 具名拒绝。
 *
 * 错误消息只包含字段路径与 runtimeId，**不回显 apiKey 值**。
 */
internal object IngestionClientValidation {
    private const val BASE_PATH = "server.ingestion.clients"

    fun validateMap(
        clients: Map<String, IngestionClientConfig>,
        path: String = BASE_PATH,
    ) {
        clients.forEach { (mapKey, client) ->
            if (client.runtimeId.isBlank()) {
                throw invalid("$path has a registration with a blank runtimeId")
            }
            if (mapKey != client.runtimeId) {
                throw invalid("$path.$mapKey.runtimeId must equal the map key")
            }
        }
        validateCollection(clients.values, path)
    }

    fun validateCollection(
        clients: Collection<IngestionClientConfig>,
        path: String = BASE_PATH,
    ) {
        val seenRuntimeIds = HashSet<String>()
        val apiKeyOwner = HashMap<String, String>()
        clients.forEach { client ->
            val runtimeId = client.runtimeId
            if (runtimeId.isBlank()) throw invalid("$path has a registration with a blank runtimeId")
            if (!seenRuntimeIds.add(runtimeId)) {
                throw invalid("$path.$runtimeId is registered more than once")
            }
            if (client.runtimeType.isBlank()) throw invalid("$path.$runtimeId.runtimeType must not be blank")
            if (client.apiKey.isBlank()) throw invalid("$path.$runtimeId.apiKey must not be blank")
            val owner = apiKeyOwner.putIfAbsent(client.apiKey, runtimeId)
            if (owner != null) {
                throw invalid("$path.$runtimeId.apiKey conflicts with another registered runtime")
            }
        }
    }

    private fun invalid(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
}
