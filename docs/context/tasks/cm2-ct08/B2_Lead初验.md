# CT08 B2 · Lead 独立初验（第 1 轮：未通过，R1 阻断）

**日期**：2026-10-29
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：第 1 轮发现 R1（阻断）并退回 Worker；**第 2 轮（R1 修复后）初验通过，提交 Architect 独立复核**；B3 在复核通过前不启动。
**依据**：[B2_委派单](B2_委派单.md) §6、[CT08 方案](CT08_方案.md) §c/§d/§e/§g/§h/§j、[架构与技术复核](架构与技术复核.md) §7（B1 DONE）

## 0. 本次修改（2026-10-29，B2 两轮初验记录）

Worker 完成 B2 自测并交付；Lead 独立复算 SHA、跑三组命令、逐条核对 §3 契约，并用自建探针做泄漏/不重试/重定向反例。

- **第 1 轮**：功能主路径全部符合，但发现阻断缺陷 R1——schema/content 解析失败的原始 `SerializationException` 被挂为 `cause`，其消息内含输入原文，经 `stackTraceToString()` 泄漏请求 schema 与响应 content。退回 Worker 修复（§5）。
- **第 2 轮**：Worker 修复 R1（不挂原始解析异常；传输异常改为仅类型名的合成 cause），新增两例正式泄漏回归；Lead 重跑全部命令、复算计数（runtime 147）并复跑 6 项独立探针全部通过（§8）。**初验通过，提交 Architect。**

## 1. 交付面与边界核对

`find -newermt '-90 minutes'` 命中 B1 已 APPROVED 的 2 个文件（21:39–21:40）与本批 7 个文件（22:27–22:32），无其他源码/构建漂移：

| 类型 | 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- | --- |
| 改 | `context/core/.../core/llm/LlmClient.kt` | `77b1f7aa…5303319a` |
| 新 | `context/runtime/.../runtime/llm/KtorLlmClient.kt` | `b75e6b9a…73d0d149` |
| 新 | `.../runtime/llm/LlmHttpStub.kt` | `36929807…97b96ec9` |
| 新 | `.../runtime/llm/KtorLlmClientRoutingTest.kt` | `c02fb71d…1b96b36b` |
| 新 | `.../runtime/llm/KtorLlmClientRequestTest.kt` | `afa6beeb…1f61e09ae` |
| 新 | `.../runtime/llm/KtorLlmClientResponseTest.kt` | `c3e70418…05ef5cdcd` |
| 新 | `.../runtime/llm/KtorBoundaryTest.kt` | `678c650e…7dc8cd8fad` |

- `context/runtime/build.gradle.kts` `b2aeea2c…`、`context/core/build.gradle.kts` `c5099f6c…` 与派发基线一致（无依赖/构建改动）。
- 既有 core+runtime 测试文件均未进入变化窗口（无修改）；`ContextConfigLoaderTest`、`LlmConfig`/`LlmBudget`/Loader/Factory/Impl 未改。
- `LlmClient.kt` 仅在 `LlmRequest` 末尾新增 `schema: String? = null`；`spotlessApply` 后 7 个文件 SHA 全部不变。

## 2. Lead 独立实跑与计数

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL，26 tasks executed |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL，28 tasks executed |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 19 | 19 | 0 | 0/0/0 |
| runtime | 145 | 145 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

增量一致：runtime 119 → 145（+26：Routing 2 / Request 8 / Response 15 / Boundary 1）。core 无 Ktor：build 文件文本无 `ktor`、core main 无 `import io.ktor`，`CoreSkeletonTest` 通过。

## 3. 逐条契约核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| Task→Profile 路由、逐请求 Key/model/路径、交叉不串 | RoutingTest 真实 stub | ✔ |
| `responseFormat` 与 `jsonMode` 不一致 → `INVALID_REQUEST`，0 请求 | RequestTest | ✔ |
| `JSON_SCHEMA` schema 缺失/空白/非法 → `INVALID_REQUEST`，0 请求；`JSON_OBJECT`/`PROMPT_ONLY` 忽略且不发送 schema | RequestTest + Lead 探针（非法 schema 在 JSON_OBJECT 下被忽略、未发送） | ✔ |
| 预算失败 → `LOCAL_BUDGET_EXCEEDED`，0 请求；预算使用实际发送 schema | ResponseTest 两例 + B1 逻辑 | ✔ |
| URL 归一（尾 `/`、已含 `/chat/completions` 不重复） | RequestTest | ✔ |
| Body 三模式、`max_tokens` 实际值、messages 原顺序 | RequestTest | ✔ |
| 2xx 非法 body/缺 content/空 content → `INVALID_RESPONSE`（1 请求，不重试） | ResponseTest 6 种信封 | ✔ |
| JSON 模式 content 必须合法 JSON；围栏 ` ``` ` 拒绝且不剥离；PROMPT_ONLY 允许非 JSON | ResponseTest | ✔ |
| 拒答优先于截断；`length` → `OUTPUT_TRUNCATED` | ResponseTest | ✔ |
| `usage` 缺失为 `null`、部分/非整数逐字段 null（不填 0） | ResponseTest | ✔ |
| 401/429+quota/429/500/400 分类、`attempts=1`、消息不回显 Provider 正文 | ResponseTest | ✔ |
| Provider context-length → `CONTEXT_LIMIT_EXCEEDED` | ResponseTest | ✔ |
| 传输失败 → `NETWORK`（B2 不重试） | ResponseTest | ✔ |
| `close()` 幂等、只关自建 client、注入 client 仍可用 | ResponseTest | ✔ |
| 不抢做 B3/B4/B5：无重试/期限/状态机/日志/统计 | 源码审查（无 RetryPolicy、无 CLOSING/CLOSED、无 Logging/统计） | ✔ |
| 3xx 不追随 | Lead 探针：302 + `Location` → 目标 server 0 请求 | ✔ |

## 4. Lead 反例探针（独立于 Worker 用例）

探针：`.local/validation/cm2/lead/ct08-b2/probe/LeadB2ProbeTest.kt`（结果 `lead-probe-results.xml`，`tests=5 failures=2`）：

| 探针 | 结果 |
| --- | --- |
| 非法 schema 的原文不得出现在 message/toString/stackTraceToString | **FAIL（R1）** |
| 非法 content 的原文不得出现在 message/toString/stackTraceToString | **FAIL（R1）** |
| `JSON_OBJECT` 忽略非法 schema 且不发送 | PASS |
| 429 + `Retry-After: 5` 在 B2 不等待、仅 1 次请求（耗时 < 2s） | PASS |
| 302 不追随，目标 server 0 请求 | PASS |

## 5. R1（阻断，必须修复）

**现象**：`KtorLlmClient.kt` 在 schema 解析失败（181–188 行）与 content 解析失败（301–308 行）时，把原始 `kotlinx.serialization.SerializationException` 作为 `cause` 挂到 `LlmException`。`JsonDecodingException` 的消息包含 **`JSON input: <原文>`**，因此 `failure.stackTraceToString()`（以及任何打印异常链的日志/诊断）会输出完整 schema 或响应 content。

**实测证据**（探针 XML 原文）：

```text
Caused by: kotlinx.serialization.json.internal.JsonDecodingException: Unexpected JSON token at offset 29: Expected end of the object or comma at path: $
JSON input: {"x": LEAK_SCHEMA_MARKER_9f31
...
Caused by: kotlinx.serialization.json.internal.JsonDecodingException: Unexpected JSON token at offset 26: Expected EOF after parsing, but had i instead at path: $
JSON input: LEAK_CONTENT_MARKER_9f32 is not json
```

**违反**：
- `core/.../llm/LlmErrors.kt` 的 `LlmException` 契约：「严禁把 API Key、Authorization、请求/响应正文、带凭据的 URL 放进 `message`、`cause` 或本对象的任何字段」；
- 方案 §g「异常消息不含 Key/Authorization/请求或响应正文/baseUrl 凭据」；
- 本批委派单 §3.1/§3.3 与 §2 的「不回显」要求。

**修复要求**：
1. 不得把原始 `SerializationException`（或任何含原文的异常）挂为 `cause`；改为不挂 `cause`，或挂一个不含输入文本、不含原始 cause 的固定消息异常（例如 `IllegalArgumentException("invalid JSON")`，且不链接原异常）。
2. 保证 `message`、`toString()`、`stackTraceToString()` 三者均不含 schema/content 原文；其他分类的 `cause`（如传输异常）也不得携带请求/响应正文。
3. 在 `KtorLlmClientRequestTest`（schema）与 `KtorLlmClientResponseTest`（content）各新增正式回归：带独特标记的非法输入 → 对应错误码，且 `message + toString() + stackTraceToString()` 不含标记；schema 用例仍断言 0 请求、content 用例仍断言 1 请求。
4. 允许改动：`KtorLlmClient.kt`、`KtorLlmClientRequestTest.kt`、`KtorLlmClientResponseTest.kt`（仅新增用例，不改既有断言）；其他文件零改动。
5. 重跑三条命令并给出新 SHA、计数（runtime 应为 145 + 新增用例数）、证据目录 `.local/validation/cm2/worker/ct08-b2-r1/`；无 git 操作、不自标 DONE。

## 6. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | `networkFailure` 挂 `IOException` 为 cause；消息可能含 URL/host（`baseUrl` 已禁止 userinfo/query/fragment），不含请求/响应正文 | 可接受；B5 日志验证时一并复核 |
| L2 | 3xx（不追随）落到 `PROVIDER_UNAVAILABLE`；方案未定义 3xx 分类 | 非阻断；B5 重定向用例只要求「不追随 + 目标 0 请求」，已实测通过 |
| L3 | `LlmResponse.finishReason` 返回 Provider 原值（仅返回对象，不进异常/日志） | 按委派单 §3.3.5 锁定，符合 §g「日志不落原文」的范围 |
| L4 | `close()` 后仍可调用（未定义 CLOSED 行为） | 按委派单 §3.5 留 B4，非缺陷 |
| L5 | `usage` 中字符串数字按「非整数」→ null | 符合委派单 §3.3.6，已测 |

## 7. 结论与下一步

- 功能主路径（路由、三模式请求、预算前置、响应解析、分类、Usage、最小释放）符合 B2 契约；26 个新增用例与四模块计数全部对得上。
- **R1 属安全边界缺陷（请求/响应正文经异常链泄漏），B2 不能通过**；已退回 Worker 修复。
- 修复后 Lead 重新执行完整初验（含重跑正式 runtime 测试恢复 145/145、复跑泄漏探针），通过后才提交 Architect 复核；**B3 在此之前不启动**。

### 证据路径

- `.local/validation/cm2/lead/ct08-b2/spotlessApply.log`、`core-runtime-test.log`、`persistence-server-test.log`
- `.local/validation/cm2/lead/ct08-b2/lead-probe.log`、`lead-probe-results.xml`、`probe/`

## 8. 第 2 轮初验（R1 修复后，2026-10-29）

### 8.1 修复交付面

仅 3 个文件变化（22:35–22:36），其余 B2 文件与 B1/构建文件 SHA 与第 1 轮完全一致：

| 文件 | 新 SHA-256（Lead 复算 = Worker） |
| --- | --- |
| `context/runtime/.../runtime/llm/KtorLlmClient.kt` | `f222ca8eb0de46e6597881311106420b8523f1b6a7d4aa69e06bb6b4d9cf623b` |
| `.../runtime/llm/KtorLlmClientRequestTest.kt` | `10e1a62d782dd3fb501f4e15ea50d5d18b80bb125db08c9fe7da138c7a334303` |
| `.../runtime/llm/KtorLlmClientResponseTest.kt` | `97e91a40c4829bf2dd0ba9b71422b0baa2e5d7052d914b0c3e30771d89faed69` |

`spotlessApply` 后 3 个 SHA 不变；`LlmClient.kt`、`LlmHttpStub.kt`、Routing/Boundary 测试与两个 build 文件均未变。

### 8.2 R1 修复核对

- schema 解析失败（`schemaElementFor`）与 content JSON 校验失败（`parseSuccessResponse`）：`catch (_: SerializationException)` 后抛固定消息 `LlmException`，**不挂 cause**；`invalidRequest` / `responseFailure` 已删除 `cause` 参数（防止回挂）。
- 传输失败（`networkFailure`）：不再链接原始异常，改为合成 `IOException("transport failure: <SimpleClassName>")`，不含 URL/正文/Key。
- `parseObjectOrNull` 仍只吞异常返回 `null`，不产生异常链。
- 新增正式回归：
  - `KtorLlmClientRequestTest.invalid schema text never leaks into the error or stack trace`（标记 `LEAK_SCHEMA_MARKER_9f31`，`INVALID_REQUEST`，0 请求，message/toString/stackTraceToString 三处均无标记）；
  - `KtorLlmClientResponseTest.invalid content text never leaks into the error or stack trace`（标记 `LEAK_CONTENT_MARKER_9f32`，`INVALID_RESPONSE`，1 请求，三处均无标记）。

### 8.3 Lead 独立实跑与计数（第 2 轮）

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，3 个 SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL，26 tasks executed |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL，28 tasks executed |
| 探针后恢复正式 runtime XML：`:context:core:test :context:runtime:test --rerun-tasks` | BUILD SUCCESSFUL |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 19 | 19 | 0 | 0/0/0 |
| runtime | 147 | 147 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

新增用例分布：Routing 2、Request 9（8+1）、Response 16（15+1）、Boundary 1；runtime 119 → 147（+28）。

### 8.4 Lead 独立探针（第 2 轮，6/6 PASS）

`.local/validation/cm2/lead/ct08-b2/lead-probe-results-r2.xml`（`tests=6 failures=0 errors=0`）：

| 探针 | 第 1 轮 | 第 2 轮 |
| --- | --- | --- |
| 非法 schema 原文不出现在 message/toString/stackTraceToString | FAIL | **PASS** |
| 非法 content 原文不出现在 message/toString/stackTraceToString | FAIL | **PASS** |
| 传输失败异常链不泄漏请求正文/Key（合成 cause 后新增） | — | **PASS** |
| `JSON_OBJECT` 忽略非法 schema 且不发送 | PASS | PASS |
| 429 + `Retry-After: 5` 不等待、仅 1 次请求（< 2s） | PASS | PASS |
| 302 不追随、目标 server 0 请求 | PASS | PASS |

说明：传输探针首版用 `"hi"` 作为正文标记，因 `"hi"` 是栈帧文本（如 `this`/`which`）的常见子串而误报；改为唯一标记 `LEAD_REQUEST_MARKER_9f33` 后通过，属探针自身修正，不涉及生产代码。

### 8.5 结论

- R1 已关闭：解析失败与传输失败均不再把原始异常链挂入 `LlmException`，message/toString/stackTraceToString 三类文本均无 schema/content/Key/请求正文。
- 第 1 轮已核对的全部契约（路由、三模式、预算前置、响应/Usage、分类、最小释放、B3/B4/B5 边界）在修复后回归全绿，计数差额为 0。
- **B2 Lead 初验通过，提交 Architect 独立复核**；复核通过前 B3 不启动。
- **Architect 裁定（2026-10-29）**：**B2 APPROVED / DONE**，可启动 B3；复核记录见 [架构与技术复核](架构与技术复核.md) §8（独立实跑 19/147/176/30 全零、spotless 通过、6 项探针 6/6、7 文件 SHA 一致、core 类路径无 Ktor）。
