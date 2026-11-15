package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/**
 * 最小 CIO HTTP Server 封装（CT07-B2，不发布/不部署）。
 *
 * 只组装 [AlcyoneContext] 与静态注册的 Bearer 认证表；路由只做 HTTP 适配，
 * 不访问 Persistence/JDBC，也不启动任何 Worker/Cron。
 */
class AlcyoneHttpServer private constructor(
    private val server: EmbeddedServer<*, *>,
) : AutoCloseable {
    /** 启动监听；`wait = false` 时由测试自行持有并关闭。 */
    fun start(wait: Boolean = false) {
        server.start(wait)
    }

    override fun close() {
        server.stop(gracePeriodMillis = 500, timeoutMillis = 5_000)
    }

    companion object {
        internal fun createEngine(
            config: ServerConfig,
            context: AlcyoneContext,
        ): EmbeddedServer<*, *> =
            embeddedServer(CIO, host = config.host, port = config.port) {
                installContextRoutes(context, config)
            }

        /** Shared route installation for external and managed ownership modes. */
        internal fun Application.installContextRoutes(
            context: AlcyoneContext,
            config: ServerConfig,
        ) {
            val registry = IngestionClientRegistry.from(config)
            if (config.clients().isNotEmpty()) {
                // 有 Ingestion 注册时保留 B1 的真实限额契约；纯 Admin/Health 宿主无需该接口。
                ingestion(context, registry)
            }
            if (registry.auth.supports(CredentialKind.READ)) {
                readRoutes(context, registry.auth, config.limits)
            }
            adminAndHealthRoutes(context, registry.auth, config.limits)
        }

        /** 用配置里的 host/port 与静态客户端注册启动最小 Server。 */
        fun start(
            config: ServerConfig,
            context: AlcyoneContext,
        ): AlcyoneHttpServer {
            val server = createEngine(config, context)
            return AlcyoneHttpServer(server).also { it.start() }
        }
    }
}
