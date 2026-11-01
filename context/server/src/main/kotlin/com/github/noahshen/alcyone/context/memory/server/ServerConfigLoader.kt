package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

/**
 * 解析 YAML 的 `server` 分区（CT07-B2）。
 *
 * - 只负责 `server`；`context` / `vfs` 由 Runtime 解析，其他顶层分区忽略；
 * - 严格未知字段：`server`、`server.ingestion`、每个 client 内出现未识别键 → `UNKNOWN_FIELD`（含路径）；
 * - 类型/范围错误、Key 冲突 → `INVALID_CONFIG`，消息只含字段路径、不回显 Key；
 * - 字符串中的 `${ENV_NAME}` 展开；未定义或 trim 后为空 → `MISSING_ENV`（只含路径与 ENV 名）；
 * - 解析结果复制到新的 [ServerConfig]：调用者修改原 YAML/Map 不影响认证。
 *
 * 不访问 Persistence/JDBC，也不启动 Server。
 */
object ServerConfigLoader {
    private val SERVER_KEYS = setOf("host", "port", "ingestion")
    private val INGESTION_KEYS = setOf("clients")
    private val CLIENT_KEYS = setOf("runtimeType", "apiKey")

    private val ENV_PATTERN = Regex("\\$\\{([^}]*)}")

    fun load(yamlPath: Path): ServerConfig = load(yamlPath, System::getenv)

    /** 测试注入 ENV 的入口。 */
    internal fun load(
        yamlPath: Path,
        environment: (String) -> String?,
    ): ServerConfig {
        val configFile = yamlPath.toAbsolutePath().normalize()
        val root = readYaml(configFile)
        val server = section(root, "server") ?: return ServerConfig()
        rejectUnknown(server, SERVER_KEYS, "server")

        val host = stringAt(server, "host", "server.host", environment) ?: ServerConfig.DEFAULT_HOST
        val port = intAt(server, "port", "server.port") ?: ServerConfig.DEFAULT_PORT
        if (port !in 1..65535) throw invalid("server.port must be in 1..65535")

        val ingestion = section(server, "ingestion")
        val clients = if (ingestion == null) emptyMap() else parseClients(ingestion, environment)
        return ServerConfig(host = host, port = port, clients = clients)
    }

    private fun parseClients(
        ingestion: Map<String, Any?>,
        environment: (String) -> String?,
    ): Map<String, IngestionClientConfig> {
        rejectUnknown(ingestion, INGESTION_KEYS, "server.ingestion")
        val clientsValue = ingestion["clients"] ?: return emptyMap()
        val clientsMap = asStringMap(clientsValue, "server.ingestion.clients")
        val clients = LinkedHashMap<String, IngestionClientConfig>()
        clientsMap.forEach { (runtimeId, value) ->
            val path = "server.ingestion.clients.$runtimeId"
            if (runtimeId.isBlank()) throw invalid("$path has a blank runtime id")
            val client = asStringMap(value, path)
            rejectUnknown(client, CLIENT_KEYS, path)
            val runtimeType =
                stringAt(client, "runtimeType", "$path.runtimeType", environment)
                    ?: throw invalid("$path.runtimeType is required")
            if (runtimeType.isBlank()) throw invalid("$path.runtimeType must not be blank")
            val apiKey =
                stringAt(client, "apiKey", "$path.apiKey", environment)
                    ?: throw invalid("$path.apiKey is required")
            if (apiKey.isBlank()) throw invalid("$path.apiKey must not be blank")
            clients[runtimeId] = IngestionClientConfig(runtimeId, runtimeType, apiKey)
        }
        // 与 ServerConfig 直接构造、Registry.of 共用同一套校验（重复 runtimeId/apiKey、空白字段）。
        IngestionClientValidation.validateMap(clients)
        return clients
    }

    // ------------------------------------------------------------------ YAML 基础

    private fun readYaml(configFile: Path): Map<String, Any?> {
        val text =
            try {
                Files.readString(configFile)
            } catch (failure: IOException) {
                throw invalid("cannot read config file: $configFile")
            }
        val loaded =
            try {
                Yaml(SafeConstructor(LoaderOptions().apply { setAllowDuplicateKeys(false) })).load<Any?>(text)
            } catch (failure: YAMLException) {
                throw invalid("config file is not valid YAML: $configFile")
            }
        if (loaded == null) return emptyMap()
        return asStringMap(loaded, "config root")
    }

    private fun section(
        parent: Map<String, Any?>,
        key: String,
    ): Map<String, Any?>? {
        if (!parent.containsKey(key)) return null
        return asStringMap(parent[key] ?: throw invalid("$key must be a mapping"), key)
    }

    private fun asStringMap(
        value: Any?,
        path: String,
    ): Map<String, Any?> {
        val map = value as? Map<*, *> ?: throw invalid("$path must be a mapping")
        val result = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, item) ->
            val name = key as? String ?: throw invalid("$path contains a non-string key")
            result[name] = item
        }
        return result
    }

    private fun rejectUnknown(
        map: Map<String, Any?>,
        allowed: Set<String>,
        path: String,
    ) {
        val unknown = map.keys.firstOrNull { it !in allowed } ?: return
        throw ContextConfigException(ContextConfigErrorCode.UNKNOWN_FIELD, "unknown field in server config: $path.$unknown")
    }

    private fun stringAt(
        map: Map<String, Any?>,
        key: String,
        path: String,
        environment: (String) -> String?,
    ): String? {
        if (!map.containsKey(key)) return null
        val raw = map[key] ?: throw invalid("$path must be a string")
        val text = raw as? String ?: throw invalid("$path must be a string")
        return ENV_PATTERN.replace(text) { match ->
            val name = match.groupValues[1]
            val value = environment(name)
            if (value == null || value.trim().isEmpty()) {
                throw ContextConfigException(
                    ContextConfigErrorCode.MISSING_ENV,
                    "environment variable '$name' referenced by $path is not set or blank",
                )
            }
            value
        }
    }

    private fun intAt(
        map: Map<String, Any?>,
        key: String,
        path: String,
    ): Int? {
        if (!map.containsKey(key)) return null
        val value = map[key]
        val number =
            when (value) {
                is Int -> value.toLong()
                is Long -> value
                is Short -> value.toLong()
                is Byte -> value.toLong()
                is BigInteger ->
                    try {
                        value.longValueExact()
                    } catch (failure: ArithmeticException) {
                        throw invalid("$path must fit in a 32-bit integer")
                    }
                else -> throw invalid("$path must be an integer")
            }
        if (number !in Int.MIN_VALUE..Int.MAX_VALUE) throw invalid("$path must fit in a 32-bit integer")
        return number.toInt()
    }

    private fun invalid(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
}
