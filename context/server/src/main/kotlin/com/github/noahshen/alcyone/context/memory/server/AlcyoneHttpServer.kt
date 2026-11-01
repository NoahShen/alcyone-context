package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.api.AlcyoneContext
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/**
 * 最小 CIO HTTP Server 封装（CT07-B2，不发布/不部署）。
 *
 * 只组装 [AlcyoneContext] 与静态注册的 Bearer 认证表；路由复用 [ingestion] 插件，
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
        /** 用配置里的 host/port 与静态客户端注册启动最小 Server。 */
        fun start(
            config: ServerConfig,
            context: AlcyoneContext,
        ): AlcyoneHttpServer {
            val registry = IngestionClientRegistry.from(config)
            val server =
                embeddedServer(CIO, host = config.host, port = config.port) {
                    // 限额由 Context 实例（IngestionLimitAware）提供，Server 不读 YAML/常量。
                    ingestion(context, registry)
                }
            return AlcyoneHttpServer(server).also { it.start() }
        }
    }
}
