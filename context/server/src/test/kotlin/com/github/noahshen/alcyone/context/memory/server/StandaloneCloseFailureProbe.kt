package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.runtime.AlcyoneContextFactory
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfig
import kotlin.system.exitProcess

/** Separate JVM only for the controlled close-failure case; production signals never use this entry. */
object StandaloneCloseFailureProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = StandaloneCloseFailureConfig.parse(args)
        val code =
            StandaloneCommand.runForTesting(
                arrayOf("run", "--config", config.yaml),
                System.out,
                System.err,
                installShutdownHook = true,
            ) { _, server ->
                ManagedAlcyoneHttpServer.createForTesting(
                    config.contextConfig,
                    server,
                    contextFactory = { FailingCloseContext(config) },
                )
            }
        exitProcess(code)
    }
}

private data class StandaloneCloseFailureConfig(
    val yaml: String,
    val contextConfig: ContextConfig,
) {
    companion object {
        fun parse(args: Array<String>): StandaloneCloseFailureConfig {
            val yaml = args.single()
            return StandaloneCloseFailureConfig(
                yaml,
                com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader
                    .load(
                        java.nio.file.Path
                            .of(yaml),
                    ),
            )
        }
    }
}

private class FailingCloseContext(
    config: StandaloneCloseFailureConfig,
) : com.github.noahshen.alcyone.context.memory.api.AlcyoneContext {
    private val delegate = AlcyoneContextFactory.create(config.contextConfig, config.contextConfig.vfsConfig)

    override suspend fun start() = delegate.start()

    override suspend fun closeAsync(): Unit = throw IllegalStateException("controlled-close-failure")

    override fun close(): Unit = throw IllegalStateException("controlled-close-failure")

    override suspend fun loadMeta(uri: String) = delegate.loadMeta(uri)

    override fun readStream(uri: String) = delegate.readStream(uri)

    override suspend fun ingestConversation(turn: com.github.noahshen.alcyone.context.memory.api.ConversationTurn) =
        delegate.ingestConversation(turn)

    override suspend fun getStatus() = delegate.getStatus()
}
