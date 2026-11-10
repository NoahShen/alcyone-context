package com.github.noahshen.alcyone.context.memory.runtime.dreaming

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.core.StoredConversationTurn
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopic
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopicValidationException
import com.github.noahshen.alcyone.context.memory.core.dreaming.DiscoveredTopicValidator
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingTopicConsolidationPrompt
import com.github.noahshen.alcyone.context.memory.core.dreaming.DreamingTopicDiscoveryPrompt
import com.github.noahshen.alcyone.context.memory.core.llm.ConservativeTokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmBudget
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmBudgetDecision
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * CT23 Topic Discovery Stage：一次 Discovery，必要时一次 Consolidation。
 *
 * 所有预算检查与实际发送均复用 [discoveryRequestFor]/[consolidationRequestFor]；
 * 解析或来源校验失败统一转成无 cause 的 `LlmException(INVALID_RESPONSE)`。
 */
internal class LlmDreamingTopicDiscoveryStage(
    private val llmClient: LlmClient,
    llmConfig: LlmConfig,
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
    private val parser: JsonDreamingTopicResponseParser = JsonDreamingTopicResponseParser(),
) : DreamingTopicDiscoveryStage {
    private val task: LlmTaskConfig
    private val profile: LlmProfileConfig
    private val safetyMarginPercent: Int = llmConfig.safetyMarginPercent
    private val discoverySchema: String?
    private val consolidationSchema: String?

    init {
        val configuredTask =
            llmConfig.tasks[LlmTask.DREAMING_TOPIC]
                ?: throw invalidConfig("context.llm.tasks.DREAMING_TOPIC is required for dreaming topics")
        val configuredProfile =
            llmConfig.profiles[configuredTask.profile]
                ?: throw invalidConfig(
                    "context.llm.tasks.DREAMING_TOPIC.profile refers to an unknown profile: ${configuredTask.profile}",
                )
        if (configuredProfile.jsonMode != LlmResponseFormat.JSON_SCHEMA &&
            configuredProfile.jsonMode != LlmResponseFormat.JSON_OBJECT
        ) {
            throw invalidConfig(
                "context.llm.profiles.${configuredProfile.name}.jsonMode must be JSON_SCHEMA or JSON_OBJECT for dreaming topics",
            )
        }
        task = configuredTask
        profile = configuredProfile
        if (profile.jsonMode == LlmResponseFormat.JSON_SCHEMA) {
            discoverySchema = DreamingTopicDiscoveryPrompt.schema()
            consolidationSchema = DreamingTopicConsolidationPrompt.schema()
        } else {
            discoverySchema = null
            consolidationSchema = null
        }
    }

    override suspend fun discover(batch: List<StoredConversationTurn>): List<DiscoveredTopic> {
        if (batch.isEmpty()) {
            throw LlmException(
                errorCode = LlmErrorCode.INVALID_REQUEST,
                message = "dreaming topic discovery requires a non-empty batch",
                task = LlmTask.DREAMING_TOPIC,
            )
        }
        currentCoroutineContext().ensureActive()
        val discoveryRequest = discoveryRequestFor(batch)
        LlmBudget.requireWithinBudget(
            request = discoveryRequest,
            schema = discoverySchema,
            task = task,
            profile = profile,
            safetyMarginPercent = safetyMarginPercent,
            estimator = estimator,
        )
        val discoveryResponse = llmClient.complete(discoveryRequest)
        if (discoveryResponse.finishReason == FINISH_REASON_LENGTH) {
            throw truncated("dreaming topic discovery output was truncated")
        }
        val discovered = parseAndValidate(discoveryResponse.content, batch.map { turn -> turn.id }.toSet())
        if (discovered.size < 2) return discovered

        currentCoroutineContext().ensureActive()
        val consolidationRequest = consolidationRequestFor(discovered)
        LlmBudget.requireWithinBudget(
            request = consolidationRequest,
            schema = consolidationSchema,
            task = task,
            profile = profile,
            safetyMarginPercent = safetyMarginPercent,
            estimator = estimator,
        )
        val consolidationResponse = llmClient.complete(consolidationRequest)
        if (consolidationResponse.finishReason == FINISH_REASON_LENGTH) {
            throw truncated("dreaming topic consolidation output was truncated")
        }
        val consolidated = parseResponse(consolidationResponse.content)
        return try {
            DiscoveredTopicValidator.validateConsolidation(consolidated, discovered)
        } catch (_: DiscoveredTopicValidationException) {
            throw invalidResponse()
        }
    }

    /** CT22 Budget adapter 与真实 Discovery 发送共用的预算路径。空 Batch 用于固定开销。 */
    internal suspend fun discoveryBudgetFor(batch: List<StoredConversationTurn>): Long? {
        currentCoroutineContext().ensureActive()
        val request = discoveryRequestFor(batch)
        val decision =
            LlmBudget.estimate(
                request = request,
                schema = discoverySchema,
                task = task,
                profile = profile,
                safetyMarginPercent = safetyMarginPercent,
                estimator = estimator,
            )
        return decision.unfitsOrNull()
    }

    /** 预算、发送与测试共用同一 Discovery 请求构造。 */
    internal fun discoveryRequestFor(batch: List<StoredConversationTurn>): LlmRequest =
        LlmRequest(
            task = LlmTask.DREAMING_TOPIC,
            messages = DreamingTopicDiscoveryPrompt.messages(batch),
            responseFormat = profile.jsonMode,
            maxOutputTokens = task.maxOutputTokens,
            schema = discoverySchema,
        )

    internal fun consolidationRequestFor(topics: List<DiscoveredTopic>): LlmRequest =
        LlmRequest(
            task = LlmTask.DREAMING_TOPIC,
            messages = DreamingTopicConsolidationPrompt.messages(topics),
            responseFormat = profile.jsonMode,
            maxOutputTokens = task.maxOutputTokens,
            schema = consolidationSchema,
        )

    internal fun effectiveDiscoverySchema(): String? = discoverySchema

    internal fun effectiveConsolidationSchema(): String? = consolidationSchema

    private fun parseAndValidate(
        content: String,
        batchTurnIds: Set<Long>,
    ): List<DiscoveredTopic> {
        val parsed = parseResponse(content)
        return try {
            DiscoveredTopicValidator.validateDiscovery(parsed, batchTurnIds)
        } catch (_: DiscoveredTopicValidationException) {
            throw invalidResponse()
        }
    }

    private fun parseResponse(content: String): List<DiscoveredTopic> = parser.parse(content)

    private fun LlmBudgetDecision.unfitsOrNull(): Long? {
        val fitsInput = estimatedInputTokens <= task.maxInputTokens.toLong()
        val fitsContext = requiredTotalTokens <= profile.contextWindowTokens.toLong()
        return if (fitsInput && fitsContext) null else requiredTotalTokens
    }

    private fun truncated(message: String): LlmException =
        LlmException(
            errorCode = LlmErrorCode.OUTPUT_TRUNCATED,
            message = message,
            task = LlmTask.DREAMING_TOPIC,
            profile = profile.name,
            model = profile.model,
        )

    private fun invalidResponse(): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_RESPONSE,
            message = "invalid dreaming topic response",
            task = LlmTask.DREAMING_TOPIC,
        )

    private fun invalidConfig(detail: String): ContextConfigException =
        ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)

    private companion object {
        const val FINISH_REASON_LENGTH = "length"
    }
}
