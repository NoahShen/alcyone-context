package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import java.security.MessageDigest

internal enum class CredentialKind {
    INGESTION,
    READ,
    ADMIN,
}

internal sealed interface ServerPrincipal {
    val kind: CredentialKind

    data class Ingestion(
        val client: IngestionClientConfig,
    ) : ServerPrincipal {
        override val kind: CredentialKind = CredentialKind.INGESTION
    }

    data object Read : ServerPrincipal {
        override val kind: CredentialKind = CredentialKind.READ
    }

    data object Admin : ServerPrincipal {
        override val kind: CredentialKind = CredentialKind.ADMIN
    }
}

/** 启动期凭据冲突校验；所有比较只使用固定长度摘要。 */
internal object ServerAuthValidation {
    fun validateKeys(
        clients: Collection<IngestionClientConfig>,
        readKey: String?,
        adminKey: String?,
    ) {
        if (readKey != null && readKey.isBlank()) throw invalid("server.auth.readKey must not be blank")
        if (adminKey != null && adminKey.isBlank()) throw invalid("server.auth.adminKey must not be blank")

        val entries = ArrayList<Pair<String, String>>(clients.size + 2)
        clients.forEach { entries += "server.ingestion.clients.${it.runtimeId}.apiKey" to it.apiKey }
        if (readKey != null) entries += "server.auth.readKey" to readKey
        if (adminKey != null) entries += "server.auth.adminKey" to adminKey
        entries.forEachIndexed { index, (path, key) ->
            for (other in entries.subList(index + 1, entries.size)) {
                if (SecretDigest.equals(key, other.second)) {
                    throw invalid("$path conflicts with ${other.first}")
                }
            }
        }
    }

    private fun invalid(detail: String): ContextConfigException = ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)
}

internal object SecretDigest {
    fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    fun equals(
        first: String,
        second: String,
    ): Boolean = MessageDigest.isEqual(digest(first), digest(second))
}

/** 三类 Key 的唯一认证表；不使用原始 Key 做 Map 查找。 */
internal class ServerAuthRegistry private constructor(
    private val entries: List<Entry>,
) {
    private class Entry(
        val digest: ByteArray,
        val principal: ServerPrincipal,
    )

    fun authenticate(authorizationHeader: String?): ServerPrincipal? {
        val token = bearerToken(authorizationHeader) ?: return null
        val candidate = SecretDigest.digest(token)
        var matched: ServerPrincipal? = null
        entries.forEach { entry ->
            if (MessageDigest.isEqual(candidate, entry.digest)) matched = entry.principal
        }
        return matched
    }

    fun supports(kind: CredentialKind): Boolean = entries.any { it.principal.kind == kind }

    companion object {
        fun from(config: ServerConfig): ServerAuthRegistry {
            val clients = config.clients().values.toList()
            IngestionClientValidation.validateCollection(clients)
            ServerAuthValidation.validateKeys(clients, config.readKey, config.adminKey)
            val entries = ArrayList<Entry>(clients.size + 2)
            clients.forEach { entries += Entry(SecretDigest.digest(it.apiKey), ServerPrincipal.Ingestion(it)) }
            config.readKey?.let { entries += Entry(SecretDigest.digest(it), ServerPrincipal.Read) }
            config.adminKey?.let { entries += Entry(SecretDigest.digest(it), ServerPrincipal.Admin) }
            return ServerAuthRegistry(entries)
        }

        private fun bearerToken(header: String?): String? {
            val text = header?.trim() ?: return null
            val separator = text.indexOf(' ')
            if (separator <= 0 || !text.substring(0, separator).equals("Bearer", ignoreCase = true)) return null
            val token = text.substring(separator + 1).trim()
            if (token.isEmpty()) return null
            return token
        }
    }
}
