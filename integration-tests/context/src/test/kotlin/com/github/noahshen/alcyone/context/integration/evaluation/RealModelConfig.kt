package com.github.noahshen.alcyone.context.integration.evaluation

import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader
import com.github.noahshen.alcyone.context.memory.runtime.llm.KtorLlmClient
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Path

internal class RealModelConfig private constructor(
    val model: String,
    val responseFormat: LlmResponseFormat,
    val llmConfig: LlmConfig,
    private val client: KtorLlmClient,
) : AutoCloseable {
    val profileName get() = llmConfig.profiles.keys.single()
    val url get() = llmConfig.profiles.getValue(profileName).baseUrl
    val key get() = llmConfig.profiles.getValue(profileName).apiKey
    val timeoutSeconds get() = llmConfig.tasks.values.minOf { it.totalTimeoutSeconds.toLong() }
    val llmClient: LlmClient get() = client
    val temperature: Double? = null

    override fun close() = runBlocking { client.closeAsync() }

    companion object {
        fun fromEnvironment(
            yamlPath: Path,
            env: Map<String, String> = System.getenv(),
        ): RealModelConfig {
            if (yamlPath.toFile().isFile) {
                val original = ContextConfigLoader.loadEvaluationLlm(yamlPath, env::get)
                require(original.profiles.size == 1) { "Evaluation YAML must contain exactly one LLM profile" }
                val profile =
                    original.profiles.values
                        .single()
                        .let { it.copy(maxAttempts = 1) }
                validateProfile(profile.baseUrl, profile.apiKey, profile.model, profile.contextWindowTokens)
                require(original.tasks.isEmpty() || original.tasks.size == LlmTask.entries.size) {
                    "Evaluation tasks must be empty or explicitly bind every LlmTask"
                }
                val tasks =
                    original.tasks.ifEmpty {
                        LlmTask.entries.associateWith {
                            val margin = ((profile.contextWindowTokens.toLong() * original.safetyMarginPercent + 99) / 100).toInt()
                            val output =
                                if (it ==
                                    LlmTask.DREAMING_REFLECTION
                                ) {
                                    2048
                                } else {
                                    minOf(4096, (profile.contextWindowTokens - margin) / 4)
                                }
                            val input = profile.contextWindowTokens - margin - output
                            require(input > 0 && output > 0) { "Evaluation profile has insufficient usable context window" }
                            LlmTaskConfig(profile.name, input, output, totalTimeoutSeconds = 120)
                        }
                    }
                val llmConfig = LlmConfig(mapOf(profile.name to profile), tasks, original.safetyMarginPercent)
                return RealModelConfig(profile.model, profile.jsonMode, llmConfig, KtorLlmClient(llmConfig))
            }
            val endpoint = env.required("ALCYONE_EVAL_BASE_URL")
            val key = env.required("ALCYONE_EVAL_API_KEY")
            val model = env.required("ALCYONE_EVAL_MODEL")
            val contextWindow = env["ALCYONE_EVAL_CONTEXT_WINDOW_TOKENS"]?.toIntOrNull() ?: 128_000
            validateProfile(endpoint, key, model, contextWindow)
            val mode =
                when (env["ALCYONE_EVAL_RESPONSE_FORMAT"]?.uppercase() ?: "JSON_SCHEMA") {
                    "JSON_SCHEMA" -> LlmResponseFormat.JSON_SCHEMA
                    "JSON_OBJECT" -> LlmResponseFormat.JSON_OBJECT
                    else -> error("Evaluation response format must be JSON_SCHEMA or JSON_OBJECT")
                }
            val profile = LlmProfileConfig("ct31-eval", endpoint, key, model, contextWindow, mode, maxAttempts = 1)
            val tasks = LlmTask.entries.associateWith { LlmTaskConfig(profile.name, 32_000, 4096, totalTimeoutSeconds = 120) }
            val llmConfig = LlmConfig(mapOf(profile.name to profile), tasks)
            return RealModelConfig(model, mode, llmConfig, KtorLlmClient(llmConfig))
        }

        private fun validateProfile(
            endpoint: String,
            key: String,
            model: String,
            contextWindow: Int,
        ) {
            val uri = URI(endpoint)
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                "Evaluation endpoint must be an absolute HTTP(S) URL without user info"
            }
            require(key.isNotBlank()) { "Evaluation API key must be provided through a secret environment variable" }
            require(model.isNotBlank()) { "Evaluation model must be configured" }
            require(contextWindow in 4096..10_000_000) { "Invalid evaluation context window" }
        }

        private fun Map<String, String>.required(name: String): String =
            get(name)?.takeIf { it.isNotBlank() } ?: error("Missing required evaluation environment variable $name")
    }
}
