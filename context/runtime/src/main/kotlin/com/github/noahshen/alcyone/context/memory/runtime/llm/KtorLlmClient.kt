package com.github.noahshen.alcyone.context.memory.runtime.llm

import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.core.llm.ConservativeTokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.LlmClient
import com.github.noahshen.alcyone.context.memory.core.llm.LlmErrorCode
import com.github.noahshen.alcyone.context.memory.core.llm.LlmException
import com.github.noahshen.alcyone.context.memory.core.llm.LlmRequest
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponse
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.core.llm.TokenEstimator
import com.github.noahshen.alcyone.context.memory.core.llm.TokenUsage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * 基于 Ktor 的 `core.llm.LlmClient` 实现（CT08-B3：总期限 + 有限重试 + `Retry-After` + 取消传播）。
 *
 * 发送前顺序（任一失败 → **0 次 HTTP**）：
 * 1. `task → tasks[task]`（缺失 → `CONFIGURATION`，消息含 task 名）→ `profiles[profile]`（运行期再查兜底）；
 * 2. `request.responseFormat` 必须等于 `profile.jsonMode`，否则 `INVALID_REQUEST`（不静默覆盖）；
 * 3. `JSON_SCHEMA` 模式下 `request.schema` 必须非空白且是合法 JSON（否则 `INVALID_REQUEST`）；
 * 4. [LlmBudget.requireWithinBudget]（失败 → `LOCAL_BUDGET_EXCEEDED`）。
 *
 * 请求：`POST <baseUrl 去尾部 '/'>`（已以 `/chat/completions` 结尾则不重复追加）；
 * Header **逐请求**携带 `Authorization: Bearer <该 Profile 的 Key>`，HttpClient 不设默认
 * Authorization、`followRedirects = false`（凭据不跨重定向）、`expectSuccess = false`、
 * 不安装 `Logging` 插件（B5 才引入脱敏日志）。
 *
 * 期限与重试（§e / 方案 §e）：
 * - `deadline = monotonicNow + task.totalTimeoutSeconds`，从**首次请求之前**起算，覆盖全部尝试与退避；
 *   每次尝试前、每次退避前都**重算** `remaining`；`remaining <= 0` → `TIMEOUT`（不再发请求）。
 * - 单次请求超时 = `min(profile.requestTimeout, remaining)`；owned HttpClient 安装 `HttpTimeout`，
 *   逐请求设置 connect/socket/request 超时。**注入的 client 由调用方负责超时配置**：未安装
 *   `HttpTimeout` 时跳过逐请求超时（仍受 `remaining` 检查约束），B4 统一接线。
 * - 只重试可判定的暂时错误（[LlmRetryPolicy.isRetryable]）；`attempts` = 实际执行次数。
 * - 有效 `Retry-After` 优先于退避：`<= remaining` 精确等待（无 jitter、不截短），`> remaining`
 *   立即以该错误码失败；额度耗尽优先于 `Retry-After`。
 * - 调用方取消（请求中或退避中）原样传播 `CancellationException`，不包装、不重试；
 *   总期限到期是 `LlmException(TIMEOUT)`，不使用 `withTimeout` 混淆两者。
 *
 * 响应：2xx 必须是 JSON 对象且有非空白 `choices[0].message.content`；`JSON_SCHEMA`/`JSON_OBJECT`
 * 模式**额外要求 content 本身是合法 JSON**，代码围栏（` ``` ` 开头）一律 `INVALID_RESPONSE`，
 * 不剥离、不修复、不猜测。拒答（非空 `refusal` 或 `finish_reason == content_filter`）优先于截断。
 * `usage` 缺失为 `null`（未知，不是 0），逐字段缺失按字段 `null`。
 *
 * 异常消息只含 task/profile/model/状态码等安全元数据，**不含** Key、URL、请求或响应正文；
 * JSON 解析失败**不挂**原始 `SerializationException`（其消息带 `JSON input: <原文>`），
 * 传输失败只挂不链接原异常、仅含类型名的固定 `IOException`。
 *
 * 诊断（CT08-B5）：每个终态（成功/失败）各写一条日志（`llm.call.completed` INFO /
 * `llm.call.failed` WARN）并更新按 `(task, profile)` 的进程内最小统计；日志与诊断只含 §g 白名单字段，
 * **不传 Throwable、不含 Key/正文/URL**。取消与关闭后 `CLOSED` 拒绝不是调用终态：不计统计、不写日志；
 * 路由前的 `CONFIGURATION` 失败只写失败日志、不计统计。
 *
 * 生命周期（CT08-B4）：`RUNNING / CLOSING / CLOSED` + 在途集合 + 共享关闭结果，三者受**同一把普通锁**
 * 保护（持锁不做挂起调用）。[complete] 先 `ensureActive()`，再用结构化子任务（`async(start = LAZY)`）
 * 登记在途；准入与关闭封闭同锁，移除只在 `invokeOnCompletion`。关闭后/关闭期间的新调用
 * → `ContextLifecycleException(CLOSED)` 且 **0 次 HTTP**；调用方取消与关闭取消在途一律
 * `CancellationException` 原样传播，**不转 CLOSED**。
 *
 * **[closeAsync] 是唯一清理入口（没有同步 `close()`）**：owner 全程 `NonCancellable`，取消在途并等待
 * 真实收尾后才关闭**自建** HttpClient；清理失败会作为共享结果发布，`CLOSED` 后重复关闭仍重抛
 * **同一异常实例**；等待者（非 owner）被取消只放弃本次等待。注入的 HttpClient 归调用方所有，本类不关闭。
 * 日志与统计属 B5。
 */
class KtorLlmClient(
    private val config: LlmConfig,
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
    httpClient: HttpClient? = null,
    /** 单调时钟（毫秒），只用于总期限；默认 `System.nanoTime()`，便于测试注入。 */
    private val monotonicNowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /** 墙钟（毫秒），只用于 `Retry-After` 的 HTTP-date 基准；默认 `System.currentTimeMillis()`。 */
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
    /** jitter 随机源，便于测试固定退避区间。 */
    private val random: Random = Random.Default,
) : LlmClient {
    private val json = Json
    private val ownsHttpClient = httpClient == null
    private val httpClient: HttpClient =
        httpClient ?: HttpClient(CIO) {
            expectSuccess = false
            followRedirects = false
            // 超时全部逐请求设置（按 Profile + remaining），这里只安装插件。
            install(HttpTimeout)
        }
    private val timeoutsSupported: Boolean = this.httpClient.pluginOrNull(HttpTimeout) != null

    /** 生命周期状态、在途集合与共享关闭结果同受这把普通锁保护；持锁不做挂起调用。 */
    private val lock = Any()

    private var state: LifecycleState = LifecycleState.RUNNING

    private val inFlight: MutableSet<Job> = mutableSetOf()

    private var closeOutcome: CompletableDeferred<Throwable?>? = null

    private val logger = LoggerFactory.getLogger(KtorLlmClient::class.java)

    /** 按 `(task, profile)` 的进程内最小统计（重启清零、不落库）。 */
    private val statistics = LlmCallStatistics()

    /** 测试/诊断用：读取某 `(task, profile)` 的统计；无记录返回 `null`。 */
    internal fun statsForTesting(
        task: LlmTask,
        profile: String,
    ): LlmCallStats? = statistics.stats(task, profile)

    /** 测试接缝：owner 清理的最后一步用它替代 `httpClient.close()`（生产为 null）。 */
    internal var closeActionForTesting: (() -> Unit)? = null

    /** 测试/诊断用：当前生命周期状态名（`RUNNING` / `CLOSING` / `CLOSED`）。 */
    internal fun stateForTesting(): String = synchronized(lock) { state.name }

    /** 测试/诊断用：当前在途调用数（移除只在 `invokeOnCompletion`）。 */
    internal fun inFlightCountForTesting(): Int = synchronized(lock) { inFlight.size }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        currentCoroutineContext().ensureActive()
        return coroutineScope {
            // 结构化子任务：父协程会等它真正结束；取消自然传播，不使用游离 Job(parent = ...)。
            val call = async(start = CoroutineStart.LAZY) { execute(request) }
            val admitted =
                synchronized(lock) {
                    if (state != LifecycleState.RUNNING) {
                        false
                    } else {
                        inFlight += call
                        // 在途移除只发生在真实完成时：await() 的 finally 不代表任务已收尾。
                        call.invokeOnCompletion { synchronized(lock) { inFlight -= call } }
                        true
                    }
                }
            if (!admitted) {
                // 关闭已封闭准入：先于任何请求构造/校验/HTTP 拒绝。
                call.cancel()
                throw ContextLifecycleException(ContextLifecycleErrorCode.CLOSED, "llm client is closed")
            }
            call.start()
            call.await()
        }
    }

    /** 真正的发送逻辑（B2/B3 行为不变）；只由 [complete] 的受跟踪子任务调用。 */
    private suspend fun execute(request: LlmRequest): LlmResponse {
        val startedAt = monotonicNowMillis()
        var resolvedProfile: LlmProfileConfig? = null
        var estimatedInputTokens: Long? = null
        var retryable = false
        try {
            return executeAttempts(
                request = request,
                startedAt = startedAt,
                onProfileResolved = { resolvedProfile = it },
                onEstimatedInputTokens = { estimatedInputTokens = it },
                onRetryable = { retryable = it },
            )
        } catch (cancellation: CancellationException) {
            // 取消不是调用终态：不计统计、不写日志。
            throw cancellation
        } catch (failure: LlmException) {
            val profile = resolvedProfile
            val attempts = failure.attempts ?: 0
            if (profile != null) {
                statistics.recordFailure(request.task, profile.name, attempts)
            }
            // 路由前（profile 未解析）失败只写日志、不计统计。
            val diagnostics =
                LlmCallDiagnostics(
                    task = request.task.name,
                    profile = profile?.name ?: UNRESOLVED_PROFILE,
                    model = profile?.model,
                    attempts = attempts,
                    statusCode = failure.statusCode,
                    elapsedMillis = monotonicNowMillis() - startedAt,
                    estimatedInputTokens = estimatedInputTokens,
                    providerUsagePresent = false,
                    retryable = retryable,
                )
            logger.warn("llm.call.failed {}", diagnostics.fields())
            throw failure
        }
    }

    /**
     * 路由、发送前校验、预算与重试循环（原 B2/B3 逻辑）。
     *
     * 通过回调把「已解析 Profile / 输入估算 / 最终可重试性」带回 [execute]，供失败诊断使用。
     */
    private suspend fun executeAttempts(
        request: LlmRequest,
        startedAt: Long,
        onProfileResolved: (LlmProfileConfig) -> Unit,
        onEstimatedInputTokens: (Long?) -> Unit,
        onRetryable: (Boolean) -> Unit,
    ): LlmResponse {
        val taskConfig =
            config.tasks[request.task]
                ?: throw LlmException(
                    errorCode = LlmErrorCode.CONFIGURATION,
                    message = "llm task is not configured: ${request.task.name}",
                    task = request.task,
                )
        val profile =
            config.profiles[taskConfig.profile]
                ?: throw LlmException(
                    errorCode = LlmErrorCode.CONFIGURATION,
                    message =
                        "llm profile is not configured for task ${request.task.name}: ${taskConfig.profile}",
                    task = request.task,
                )
        onProfileResolved(profile)
        if (request.responseFormat != profile.jsonMode) {
            throw LlmException(
                errorCode = LlmErrorCode.INVALID_REQUEST,
                message =
                    "request.responseFormat must match the profile jsonMode " +
                        "(request=${request.responseFormat}, profile=${profile.jsonMode})",
                task = request.task,
                profile = profile.name,
                model = profile.model,
            )
        }
        val schemaElement = schemaElementFor(request, profile)
        // 预算只按实际发送的 schema 估算：非 JSON_SCHEMA 模式不发送 schema，也不计入预算。
        val schemaToSend = if (profile.jsonMode == LlmResponseFormat.JSON_SCHEMA) request.schema else null
        val decision =
            LlmBudget.requireWithinBudget(
                request = request,
                schema = schemaToSend,
                task = taskConfig,
                profile = profile,
                safetyMarginPercent = config.safetyMarginPercent,
                estimator = estimator,
            )
        onEstimatedInputTokens(decision.estimatedInputTokens)

        val body = requestBody(request, profile, decision.effectiveMaxOutputTokens, schemaElement)
        // 总期限从首次请求之前起算，覆盖全部尝试与退避。
        val deadline = monotonicNowMillis() + taskConfig.totalTimeoutSeconds * 1_000L
        var attempt = 0
        while (true) {
            // 取消原样传播：不包装、不重试。
            currentCoroutineContext().ensureActive()
            val remaining = deadline - monotonicNowMillis()
            if (remaining <= 0L) {
                onRetryable(true)
                throw timeoutException(request, profile, attempt, "llm total timeout elapsed before the request was sent")
            }
            attempt++
            when (val outcome = callOnce(request, profile, body, remaining)) {
                is CallOutcome.Success -> {
                    recordSuccess(request, profile, attempt, outcome, decision.estimatedInputTokens, startedAt)
                    return outcome.response
                }
                is CallOutcome.Failure -> {
                    val failure = outcome.failure
                    if (!failure.retryable || attempt >= profile.maxAttempts) {
                        onRetryable(failure.retryable)
                        throw failure.toException(request, profile, attempt)
                    }
                    // 每次退避前重算 remaining，不复用上一次的值。
                    val remainingNow = deadline - monotonicNowMillis()
                    val retryAfter = failure.retryAfterMillis
                    if (retryAfter != null) {
                        if (retryAfter > remainingNow) {
                            // 不截短、不提前重试：立即以该次错误码失败。
                            onRetryable(failure.retryable)
                            throw failure.toException(request, profile, attempt)
                        }
                        // 精确等待（无 jitter、不截短），可被取消立即中止。
                        delay(retryAfter)
                    } else {
                        if (remainingNow <= 0L) {
                            onRetryable(true)
                            throw timeoutException(request, profile, attempt, "llm total timeout elapsed before the retry")
                        }
                        delay(minOf(LlmRetryPolicy.backoffMillis(attempt, random), remainingNow))
                    }
                }
            }
        }
    }

    /**
     * 唯一清理入口（**没有同步 `close()`**）：幂等，且共享同一关闭结果。
     *
     * owner：`NonCancellable` 下取消在途 → 循环 `join` 直到在途集合为空（收敛：CLOSING 已封闭准入）
     * → 关闭**自建**的 HttpClient（注入的 client 由调用方负责关闭）→ 发布结果（成功 `null` / 失败同一异常）。
     * 清理失败也发布终态，绝不滞留 `CLOSING`；`CLOSED` 后重复调用仍等待并**重抛同一异常实例**。
     *
     * 等待者（非 owner）：只等待共享结果；自身被取消只放弃本次等待，owner 仍完成清理。
     */
    suspend fun closeAsync() {
        var isOwner = false
        var snapshot: List<Job> = emptyList()
        val outcome =
            synchronized(lock) {
                val existing = closeOutcome
                if (existing != null) {
                    existing
                } else {
                    val created = CompletableDeferred<Throwable?>()
                    closeOutcome = created
                    state = LifecycleState.CLOSING
                    // 显式遍历复制：不用 toList()（其对 size == 1 有 next() 快路径）。
                    snapshot = ArrayList(inFlight)
                    isOwner = true
                    created
                }
            }
        if (!isOwner) {
            outcome.await()?.let { throw it }
            return
        }
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                snapshot.forEach { call -> call.cancel(CancellationException("llm client is closing")) }
                while (true) {
                    val pending = synchronized(lock) { ArrayList(inFlight) }
                    if (pending.isEmpty()) break
                    pending.forEach { call -> call.join() }
                }
                val action = closeActionForTesting
                if (action != null) {
                    action()
                } else if (ownsHttpClient) {
                    httpClient.close()
                }
            } catch (throwable: Throwable) {
                // 任何清理失败都必须发布终态，不得滞留 CLOSING。
                failure = throwable
            } finally {
                synchronized(lock) { state = LifecycleState.CLOSED }
                outcome.complete(failure)
            }
        }
        failure?.let { throw it }
    }

    // ------------------------------------------------------------------ 单次尝试

    /** 执行一次 HTTP 尝试；成功返回解析结果，失败返回**结构化**失败（含可重试性与 `Retry-After`）。 */
    private suspend fun callOnce(
        request: LlmRequest,
        profile: LlmProfileConfig,
        body: String,
        remainingMillis: Long,
    ): CallOutcome {
        val response =
            try {
                httpClient.post(endpoint(profile.baseUrl)) {
                    header(HttpHeaders.Authorization, "Bearer ${profile.apiKey}")
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    if (timeoutsSupported) {
                        timeout {
                            requestTimeoutMillis = minOf(profile.requestTimeoutSeconds * 1_000L, remainingMillis)
                            connectTimeoutMillis = profile.connectTimeoutSeconds * 1_000L
                            socketTimeoutMillis = profile.socketTimeoutSeconds * 1_000L
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                // 调用方取消不是超时也不是网络错误：原样传播，不包装成 LlmException。
                throw cancellation
            } catch (transport: Exception) {
                return transportFailure(transport, request, profile)
            }

        val status = response.status.value
        val text =
            try {
                response.bodyAsText()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (transport: Exception) {
                return transportFailure(transport, request, profile, status)
            }

        if (status !in 200..299) {
            return httpFailure(status, response.headers, text, request, profile)
        }
        return parseSuccessResponse(text, request, profile, status)
    }

    // ------------------------------------------------------------------ 发送前校验

    /** `JSON_SCHEMA` 模式必须给出可解析的 schema；其他模式忽略该字段（返回 null，不发送）。 */
    private fun schemaElementFor(
        request: LlmRequest,
        profile: LlmProfileConfig,
    ): JsonElement? {
        if (profile.jsonMode != LlmResponseFormat.JSON_SCHEMA) return null
        val schema = request.schema
        if (schema == null || schema.isBlank()) {
            throw invalidRequest(
                request = request,
                profile = profile,
                message = "request.schema is required and must not be blank for JSON_SCHEMA profiles",
            )
        }
        val element =
            try {
                json.parseToJsonElement(schema)
            } catch (_: SerializationException) {
                // 原始 SerializationException 的消息会带 "JSON input: <原文>"：既不挂 cause，
                // 也不把 schema 文本放进消息，避免 message/toString/stackTrace 回显请求正文。
                throw invalidRequest(
                    request = request,
                    profile = profile,
                    message = "request.schema must be valid JSON for JSON_SCHEMA profiles",
                )
            }
        return element
    }

    private fun invalidRequest(
        request: LlmRequest,
        profile: LlmProfileConfig,
        message: String,
    ): LlmException =
        LlmException(
            errorCode = LlmErrorCode.INVALID_REQUEST,
            message = message,
            task = request.task,
            profile = profile.name,
            model = profile.model,
        )

    // ------------------------------------------------------------------ 请求

    private fun endpoint(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return if (trimmed.endsWith(CHAT_COMPLETIONS_SUFFIX)) trimmed else "$trimmed$CHAT_COMPLETIONS_SUFFIX"
    }

    private fun requestBody(
        request: LlmRequest,
        profile: LlmProfileConfig,
        maxOutputTokens: Int,
        schemaElement: JsonElement?,
    ): String =
        buildJsonObject {
            put("model", profile.model)
            putJsonArray("messages") {
                request.messages.forEach { message ->
                    addJsonObject {
                        put("role", message.role)
                        put("content", message.content)
                    }
                }
            }
            put("max_tokens", maxOutputTokens)
            when (profile.jsonMode) {
                LlmResponseFormat.JSON_SCHEMA ->
                    putJsonObject("response_format") {
                        put("type", "json_schema")
                        putJsonObject("json_schema") {
                            put("name", request.task.name.lowercase())
                            put("strict", true)
                            put("schema", requireNotNull(schemaElement))
                        }
                    }

                LlmResponseFormat.JSON_OBJECT ->
                    putJsonObject("response_format") {
                        put("type", "json_object")
                    }

                LlmResponseFormat.PROMPT_ONLY -> Unit
            }
        }.toString()

    // ------------------------------------------------------------------ 2xx 响应

    private fun parseSuccessResponse(
        text: String,
        request: LlmRequest,
        profile: LlmProfileConfig,
        statusCode: Int,
    ): CallOutcome {
        val body =
            parseObjectOrNull(text)
                ?: return responseFailure(
                    LlmErrorCode.INVALID_RESPONSE,
                    "llm response body must be a JSON object",
                    request,
                    profile,
                )
        val firstChoice = (body["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = firstChoice?.get("message") as? JsonObject
        val rawContent = (message?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (firstChoice == null || message == null || rawContent == null || rawContent.isBlank()) {
            // Provider 在 2xx 信封里塞 context-length 错误时仍按远端上下文超限分类。
            return if (body.indicatesContextLength()) {
                responseFailure(
                    LlmErrorCode.CONTEXT_LIMIT_EXCEEDED,
                    "llm response reports a context length failure",
                    request,
                    profile,
                )
            } else {
                responseFailure(
                    LlmErrorCode.INVALID_RESPONSE,
                    "llm response choices[0].message.content must be a non-blank string",
                    request,
                    profile,
                )
            }
        }
        val content = rawContent
        if (profile.jsonMode != LlmResponseFormat.PROMPT_ONLY) {
            // 只验 HTTP 信封不算业务 JSON 有效：content 本身必须是合法 JSON，围栏一律拒绝。
            if (content.trim().startsWith(CODE_FENCE_PREFIX)) {
                return responseFailure(
                    LlmErrorCode.INVALID_RESPONSE,
                    "llm response content must not be a fenced code block",
                    request,
                    profile,
                )
            }
            try {
                json.parseToJsonElement(content)
            } catch (_: SerializationException) {
                // 原始 SerializationException 会带 "JSON input: <content>"：不挂 cause，避免响应正文外泄。
                return responseFailure(
                    LlmErrorCode.INVALID_RESPONSE,
                    "llm response content must be valid JSON",
                    request,
                    profile,
                )
            }
        }
        val finishReason = (firstChoice.get("finish_reason") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val refusal = (message.get("refusal") as? JsonPrimitive)?.takeIf { it.isString }?.content
        // 拒答优先于截断：明确拒答不能被当成可重试的截断。
        if (!refusal.isNullOrBlank() || finishReason == FINISH_REASON_CONTENT_FILTER) {
            return responseFailure(
                LlmErrorCode.MODEL_REFUSAL,
                "llm response reports a model refusal",
                request,
                profile,
            )
        }
        if (finishReason == FINISH_REASON_LENGTH) {
            return responseFailure(
                LlmErrorCode.OUTPUT_TRUNCATED,
                "llm response output was truncated (finish_reason=length)",
                request,
                profile,
            )
        }
        return CallOutcome.Success(
            response =
                LlmResponse(
                    content = content,
                    usage = (body["usage"] as? JsonObject)?.toTokenUsage(),
                    finishReason = finishReason,
                ),
            statusCode = statusCode,
        )
    }

    /** 成功终态：更新统计并写一条 INFO 日志（字段白名单，无 Throwable）。 */
    private fun recordSuccess(
        request: LlmRequest,
        profile: LlmProfileConfig,
        attempts: Int,
        outcome: CallOutcome.Success,
        estimatedInputTokens: Long?,
        startedAt: Long,
    ) {
        val usage = outcome.response.usage
        statistics.recordSuccess(request.task, profile.name, attempts, usage)
        val diagnostics =
            LlmCallDiagnostics(
                task = request.task.name,
                profile = profile.name,
                model = profile.model,
                attempts = attempts,
                statusCode = outcome.statusCode,
                elapsedMillis = monotonicNowMillis() - startedAt,
                estimatedInputTokens = estimatedInputTokens,
                providerUsagePresent = usage != null,
            )
        logger.info("llm.call.completed {}", diagnostics.fields())
    }

    // ------------------------------------------------------------------ 非 2xx 与分类

    private fun httpFailure(
        status: Int,
        headers: Headers,
        text: String,
        request: LlmRequest,
        profile: LlmProfileConfig,
    ): CallOutcome.Failure {
        val body = parseObjectOrNull(text)
        val markers = body?.errorMarkers().orEmpty()
        val contextLength = body?.indicatesContextLength() == true
        val retryAfter = LlmRetryPolicy.parseRetryAfter(headers[HttpHeaders.RetryAfter], headers[HttpHeaders.Date], wallClockMillis())
        val rateLimitHint = markers.any(::indicatesRateLimit)
        val code =
            when {
                status == 401 || status == 403 -> LlmErrorCode.AUTHENTICATION
                // 额度耗尽优先于 Retry-After：即使带有效 Retry-After 也不重试。
                status == 429 && markers.any(::indicatesQuota) -> LlmErrorCode.QUOTA_EXCEEDED
                status == 429 -> LlmErrorCode.RATE_LIMITED
                contextLength -> LlmErrorCode.CONTEXT_LIMIT_EXCEEDED
                status == 408 -> LlmErrorCode.TIMEOUT
                status >= 500 -> LlmErrorCode.PROVIDER_UNAVAILABLE
                status >= 400 -> LlmErrorCode.INVALID_REQUEST
                else -> LlmErrorCode.PROVIDER_UNAVAILABLE
            }
        val retryable =
            LlmRetryPolicy.isRetryable(
                errorCode = code,
                statusCode = status,
                hasValidRetryAfter = retryAfter != null,
                rateLimitHint = rateLimitHint,
            )
        return CallOutcome.Failure(
            LlmFailure(
                errorCode = code,
                message = "llm provider returned HTTP $status",
                statusCode = status,
                retryable = retryable,
                // 只对 429（RATE_LIMITED）采用 Retry-After；其他状态按退避。
                retryAfterMillis = retryAfter.takeIf { code == LlmErrorCode.RATE_LIMITED },
            ),
        )
    }

    private fun transportFailure(
        failure: Throwable,
        request: LlmRequest,
        profile: LlmProfileConfig,
        statusCode: Int? = null,
    ): CallOutcome.Failure {
        val timedOut = isTimeoutFailure(failure)
        return CallOutcome.Failure(
            LlmFailure(
                errorCode = if (timedOut) LlmErrorCode.TIMEOUT else LlmErrorCode.NETWORK,
                message =
                    "llm request " +
                        (if (timedOut) "timed out" else "failed at the transport layer") +
                        ": ${failure.javaClass.simpleName}",
                statusCode = statusCode,
                // 单次请求/连接/socket 超时与传输中断都是可重试的暂时错误。
                retryable = true,
                retryAfterMillis = null,
                // 只保留异常类型名：原始传输异常不链接进异常链（其文本/上下文不可控）。
                cause = IOException("transport failure: ${failure.javaClass.simpleName}"),
            ),
        )
    }

    /** 超时类异常（含被包装的情形）：`HttpRequestTimeoutException` / `ConnectTimeoutException` / `SocketTimeoutException`。 */
    private fun isTimeoutFailure(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }
            .take(8)
            .any { it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is SocketTimeoutException }

    private fun timeoutException(
        request: LlmRequest,
        profile: LlmProfileConfig,
        attempts: Int,
        message: String,
    ): LlmException =
        LlmException(
            errorCode = LlmErrorCode.TIMEOUT,
            message = message,
            task = request.task,
            profile = profile.name,
            model = profile.model,
            attempts = attempts,
        )

    private fun responseFailure(
        code: LlmErrorCode,
        message: String,
        request: LlmRequest,
        profile: LlmProfileConfig,
    ): CallOutcome.Failure =
        CallOutcome.Failure(
            LlmFailure(
                errorCode = code,
                message = message,
                statusCode = null,
                // 2xx 的解析/截断/拒答/context-length 一律不重试。
                retryable = false,
                retryAfterMillis = null,
            ),
        )

    // ------------------------------------------------------------------ JSON 小工具

    private fun parseObjectOrNull(text: String): JsonObject? =
        try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (failure: IllegalArgumentException) {
            null
        }

    /** `error.code` / `error.type` 的**小写**取值；缺 `error` 对象时为空。 */
    private fun JsonObject.errorMarkers(): List<String> {
        val error = this["error"] as? JsonObject ?: return emptyList()
        return listOf("code", "type")
            .mapNotNull { key -> (error[key] as? JsonPrimitive)?.content?.lowercase() }
    }

    private fun JsonObject.indicatesContextLength(): Boolean = errorMarkers().any(::indicatesContextLength)

    private fun indicatesContextLength(marker: String): Boolean =
        marker.contains("context_length") ||
            marker.contains("context length") ||
            marker.contains("context_window") ||
            marker.contains("maximum context") ||
            marker.contains("too many tokens")

    private fun indicatesQuota(marker: String): Boolean =
        marker.contains("insufficient_quota") ||
            marker.contains("quota") ||
            marker.contains("billing")

    /** 限流类标记：只有这些才允许把 429 当作「可安全判定的暂时错误」重试。 */
    private fun indicatesRateLimit(marker: String): Boolean =
        marker.contains("rate_limit") ||
            marker.contains("rate limit") ||
            marker.contains("too_many_requests") ||
            marker.contains("too many requests")

    private fun JsonObject.toTokenUsage(): TokenUsage =
        TokenUsage(
            inputTokens = longField("prompt_tokens"),
            outputTokens = longField("completion_tokens"),
            totalTokens = longField("total_tokens"),
        )

    /** 缺失或非整数 → `null`（**不填 0**）；字符串形态的数字不算整数。 */
    private fun JsonObject.longField(name: String): Long? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toLongOrNull()
    }

    // ------------------------------------------------------------------ 内部结果类型

    private sealed interface CallOutcome {
        data class Success(
            val response: LlmResponse,
            val statusCode: Int,
        ) : CallOutcome

        data class Failure(
            val failure: LlmFailure,
        ) : CallOutcome
    }

    /** 一次失败的结构化描述；`attempts` 在真正抛出时按**实际执行次数**填充。 */
    private class LlmFailure(
        val errorCode: LlmErrorCode,
        val message: String,
        val statusCode: Int?,
        val retryable: Boolean,
        val retryAfterMillis: Long?,
        val cause: Throwable? = null,
    ) {
        fun toException(
            request: LlmRequest,
            profile: LlmProfileConfig,
            attempts: Int,
        ): LlmException =
            LlmException(
                errorCode = errorCode,
                message = message,
                task = request.task,
                profile = profile.name,
                model = profile.model,
                statusCode = statusCode,
                attempts = attempts,
                cause = cause,
            )
    }

    private enum class LifecycleState {
        RUNNING,
        CLOSING,
        CLOSED,
    }

    private companion object {
        const val UNRESOLVED_PROFILE = "<unresolved>"
        const val CHAT_COMPLETIONS_SUFFIX = "/chat/completions"
        const val CODE_FENCE_PREFIX = "```"
        const val FINISH_REASON_LENGTH = "length"
        const val FINISH_REASON_CONTENT_FILTER = "content_filter"
    }
}
