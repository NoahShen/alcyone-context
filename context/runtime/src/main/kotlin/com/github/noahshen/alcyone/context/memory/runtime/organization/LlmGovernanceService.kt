package com.github.noahshen.alcyone.context.memory.runtime.organization

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory
import com.github.noahshen.alcyone.context.memory.core.llm.ConservativeTokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceDecision
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceDecisionValidator
import com.github.noahshen.alcyone.context.memory.core.organization.GovernancePrompt
import com.github.noahshen.alcyone.context.memory.core.organization.GovernanceValidationException
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmBudget
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * CT14 Governance LLM 服务（Runtime internal；**不接** Factory/Impl/Config/Loader，不写数据库）。
 *
 * 流程：
 * 1. 构造期只读配置与 Prompt 资源：缺 `GOVERNANCE` Task / Profile 缺失 / 非 `JSON_SCHEMA`·`JSON_OBJECT`
 *    → `ContextConfigException(INVALID_CONFIG)`（消息只含配置路径，不含 Key/URL/正文）；**不访问 DB/LLM**。
 * 2. [decide] 构造**单个**请求：固定 Prompt + Raw + **全部**候选（不分批、不截断，裁定 D7）。
 * 3. 发送前 [LlmBudget.requireWithinBudget]：Schema 仅 `JSON_SCHEMA` 计入，`maxOutputTokens = task.maxOutputTokens`，
 *    输出保留与安全余量都计入；超限 → `LlmException(LOCAL_BUDGET_EXCEEDED)` 且**不发起 LLM 调用**。
 * 4. 调用 [LlmClient]；`finish_reason == "length"` → `OUTPUT_TRUNCATED`（不接受部分 JSON）。
 * 5. [JsonGovernanceResponseParser] 严格解析 → [GovernanceDecisionValidator] 校验候选权限与动作契约；
 *    任一非法统一映射为 `LlmException(INVALID_RESPONSE)`（裁定 D3），不降级 `NEW`、不返回部分决策。
 *
 * 取消原样传播（`CancellationException` 不被包装）；错误消息不含 Raw/候选正文、Key 或 URL。
 */
internal class LlmGovernanceService(
    private val llmClient: LlmClient,
    llmConfig: LlmConfig,
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
) {
    private val task: LlmTaskConfig
    private val profile: LlmProfileConfig
    private val safetyMarginPercent: Int = llmConfig.safetyMarginPercent
    private val schema: String?
    private val parser = JsonGovernanceResponseParser()

    init {
        val taskConfig =
            llmConfig.tasks[LlmTask.GOVERNANCE]
                ?: throw invalidConfig("context.llm.tasks.GOVERNANCE is required for governance")
        val profileConfig =
            llmConfig.profiles[taskConfig.profile]
                ?: throw invalidConfig(
                    "context.llm.tasks.GOVERNANCE.profile refers to an unknown profile: ${taskConfig.profile}",
                )
        if (profileConfig.jsonMode != LlmResponseFormat.JSON_SCHEMA &&
            profileConfig.jsonMode != LlmResponseFormat.JSON_OBJECT
        ) {
            throw invalidConfig(
                "context.llm.profiles.${profileConfig.name}.jsonMode must be JSON_SCHEMA or JSON_OBJECT for governance",
            )
        }
        task = taskConfig
        profile = profileConfig
        schema = if (profileConfig.jsonMode == LlmResponseFormat.JSON_SCHEMA) GovernancePrompt.schema() else null
    }

    /**
     * 判定一条 Raw 相对**本次全部候选**的动作。
     *
     * @throws LlmException `LOCAL_BUDGET_EXCEEDED`（预算超限，未发起请求）、`OUTPUT_TRUNCATED`、
     *   `INVALID_RESPONSE`（解析或校验失败）、`INVALID_REQUEST`（估算器非法返回值）。
     * @throws kotlinx.coroutines.CancellationException 原样传播。
     */
    suspend fun decide(
        rawMemory: RawMemory,
        candidates: List<OrganizedMemory>,
    ): GovernanceDecision {
        currentCoroutineContext().ensureActive()
        val request = requestFor(rawMemory, candidates)
        // 预算超限在此抛出 LOCAL_BUDGET_EXCEEDED：整项失败，不截断候选、不调用 LLM（D7）。
        LlmBudget.requireWithinBudget(
            request = request,
            schema = schema,
            task = task,
            profile = profile,
            safetyMarginPercent = safetyMarginPercent,
            estimator = estimator,
        )
        val response = llmClient.complete(request)
        if (response.finishReason == FINISH_REASON_LENGTH) {
            throw LlmException(
                errorCode = LlmErrorCode.OUTPUT_TRUNCATED,
                message = "governance output was truncated (finish_reason=length)",
                task = LlmTask.GOVERNANCE,
                profile = profile.name,
                model = profile.model,
            )
        }
        val decision = parser.parse(response.content)
        return try {
            GovernanceDecisionValidator.validate(decision, candidates)
        } catch (invalid: GovernanceValidationException) {
            throw LlmException(
                errorCode = LlmErrorCode.INVALID_RESPONSE,
                message = "invalid governance decision",
                task = LlmTask.GOVERNANCE,
                profile = profile.name,
                model = profile.model,
            )
        }
    }

    /** 估算与发送共用同一请求构造：预算判定与真实发送不可能出现内容差异。 */
    internal fun requestFor(
        rawMemory: RawMemory,
        candidates: List<OrganizedMemory>,
    ): LlmRequest =
        LlmRequest(
            task = LlmTask.GOVERNANCE,
            messages = GovernancePrompt.messages(rawMemory, candidates),
            responseFormat = profile.jsonMode,
            maxOutputTokens = task.maxOutputTokens,
            schema = schema,
        )

    /** 供测试断言“Schema 仅在 JSON_SCHEMA 计入预算”等只读信息；不暴露配置原文。 */
    internal fun effectiveSchema(): String? = schema

    private fun invalidConfig(detail: String): ContextConfigException =
        ContextConfigException(ContextConfigErrorCode.INVALID_CONFIG, detail)

    private companion object {
        const val FINISH_REASON_LENGTH = "length"
    }
}
