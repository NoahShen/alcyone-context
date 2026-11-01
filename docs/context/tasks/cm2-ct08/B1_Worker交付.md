# CT08 B1（配置、错误类型、估算器、预算检查）Worker 交付

**日期**：2026-10-29
**批次**：CM2 CT08 **B1**（Architect §m 已批准；B2～B5 未开工）
**依据**：[CT08 方案](../../tasks/cm2-ct08/CT08_方案.md) §a/§b/§c/§j B1 行/§m · [CT08 任务书](../../milestones/cm2/CT08_任务书.md) v3 · 技术实现设计 v0.1 §3.2～§3.5
**状态**：**自测通过，待 Lead 初验与 Architect 独立复核**（未自标 DONE；未做任何 git add/commit）

## 0. 本次修改（2026-10-29，B1 首次交付）

- 按 §j B1 边界实现：`context.llm` 由「接受但不消费」改为**首次解析 + 严格校验**；Core 新增 `LlmErrorCode`/`LlmException` 与 `ConservativeTokenEstimator`；runtime 新增 `LlmConfig`（自有快照 + 同一 `validateLlm` + 脱敏 `toString`）与 `LlmBudget`（发送前双条件、余量只计一次、Long 防溢出）；`ContextConfig.llm` + Factory 自有快照 + Impl 仅保存 `val`。
- **未做**（B2～B5）：任何 HTTP Adapter / Ktor client、`HttpClient`、Runtime LLM 生命周期接线（`start`/`close` 未改）、`LlmRequest.schema`、`server`/`persistence`/`vfs` 改动、内部统计与日志接线。
- 定点更新一个既有用例（§m 唯一例外）：`ContextConfigLoaderTest.unimplemented blueprint sections are accepted without validation or env expansion` 中删除 `llm` 子树（它断言 llm 被接受且不展开 ENV，与首次消费矛盾），保留 `organization`/`memoryMap`/`backup` 的「接受但不校验」断言，并补 `assertNull(config.llm)`。其余既有用例未改。

## 1. 改动文件与 SHA-256

| 文件 | 类型 | SHA-256 |
| --- | --- | --- |
| `context/core/.../core/llm/LlmErrors.kt` | 新增 | `59d411d284a9e3a48598693d32e7401a14e95345493ba9c1dda441ea41d3e835` |
| `context/core/.../core/llm/ConservativeTokenEstimator.kt` | 新增 | `7dcaabc9289d8d54e1f9e75a0a2f2d11baf15c336028ccb08b0d0bd52b61bfef` |
| `context/core/src/test/.../core/llm/ConservativeTokenEstimatorTest.kt` | 新增 | `c4c49bcc4104a041fc714f9b317b3046b34373673e41a13b3714f94b5a7752fd` |
| `context/runtime/.../runtime/llm/LlmConfig.kt` | 新增 | `5f3674e53c29c2b33f61bbbaff954f33c50d8908260a4fe5308958636cc57dff` |
| `context/runtime/.../runtime/llm/LlmBudget.kt` | 新增 | `c6f033b2b41eec7024f8a6832e2a8fad062cad9f0a5d165535ac0838e51e0c73` |
| `context/runtime/.../runtime/ContextConfig.kt` | 修改（新增 `llm: LlmConfig? = null`） | `6d2eea66b918ff0ffe27da7b01378beaf47278f824f9385db304c9125e1113c8` |
| `context/runtime/.../runtime/ContextConfigLoader.kt` | 修改（`parseLlm` + 键集合 + KDoc） | `40d8eec4e738c2a16abcf7d58caf549b6ef16bb62344f1692c274a8aa876ea96` |
| `context/runtime/.../runtime/AlcyoneContextFactory.kt` | 修改（`ownedCopy()` + 传入 Impl） | `d0f3a17dba6a3a4ae362424558f6dea11ac88f757e17041b145b7e725c81c9cc` |
| `context/runtime/.../runtime/AlcyoneContextImpl.kt` | 修改（仅 `internal val llmConfig` + import） | `85b6b6a3e7fa9393322be8cac360dea6f01571e1cc6d9513c1b35654f896ccd1` |
| `context/runtime/src/test/.../runtime/llm/LlmConfigValidationTest.kt` | 新增 | `e258cb5ca10d963b1027f127652045e18ca04429a5be21f8ef958ff6e2e63366` |
| `context/runtime/src/test/.../runtime/llm/LlmBudgetTest.kt` | 新增 | `38f91d90676fd08204d8e0f8d2039d9e568672ae25879e42b250dab2169696ea` |
| `context/runtime/src/test/.../runtime/ContextConfigLoaderTest.kt` | 定点修改 1 例 | `4fe3333cd28e60e0862bc0edd741ee515e88cf497f09d7b2067499d2f8c41cf0` |

`context/core/build.gradle.kts` 未改（无 `ktor` 字样，无新依赖）；`context/persistence-sqldelight/**`、`context/server/**`、`vfs/**`、`common/**` 零改动。

## 2. 实现要点（与方案逐条对应）

### 2.1 `LlmErrors.kt`（§3.5 分类）

- `LlmErrorCode` = 冻结 12 类 `CONFIGURATION / AUTHENTICATION / QUOTA_EXCEEDED / RATE_LIMITED / NETWORK / TIMEOUT / PROVIDER_UNAVAILABLE / INVALID_REQUEST / CONTEXT_LIMIT_EXCEEDED / INVALID_RESPONSE / OUTPUT_TRUNCATED / MODEL_REFUSAL` + `LOCAL_BUDGET_EXCEEDED`。
- `open class LlmException : api.ContextException`，元数据仅 `task / profile / model / statusCode / attempts`；`toString` 只拼接这些安全字段，**不**保存 Provider 的 `code`/`requestId`/`finishReason` 原值。

### 2.2 `ConservativeTokenEstimator`（§b）

- ASCII 码点 `ceil(ascii/3)`；非 ASCII **按 Unicode 码点** 3 tokens（代理对只算一个码点，测试固定 `😀` = 3）；
- `estimatePrompt = Σ(estimateText(role) + estimateText(content)) + 8 × messages.size + 16`，role 用实际字符串（测试用 `"u"` vs `"supervisor-role"` 断言差值 = 4，不是固定角色开销）；
- `estimateSchema(null) = 0`；空串 = 0；累加/乘法全部 Long + 饱和，最终 `saturate` 到 `Int.MAX_VALUE`（不回绕成负数）。

### 2.3 `LlmConfig`（§a/§m）

- 三层类型 `LlmConfig` / `LlmProfileConfig`（`jsonMode` 复用既有 `LlmResponseFormat`）/ `LlmTaskConfig`；默认值集中在 `LlmDefaults`（10/180/120/360/2、余量 20、token 上限 1e7）。
- 构造即 `Collections.unmodifiableMap(LinkedHashMap(...))` 自有副本 + `init { validateLlm(this) }`；`ownedCopy()` 再复制再校验。**同一 `validateLlm` 服务 YAML 与直接 Kotlin 构造**。
- `LlmProfileConfig.toString`：`apiKey=<redacted>` 恒定，`baseUrl` 只输出 `scheme://host[:port]/…`；解析失败整体 `<redacted>`。`LlmConfig.toString` 只列 Profile/Task 名与目标 Profile。
- 校验顺序（避免「空白必填」被「URL 形状」抢先）：`safetyMarginPercent → profiles 非空 → 名字非空白/与键一致 → baseUrl 非空白 → baseUrl 形状 → apiKey/model → 数值范围 → Task 引用/范围 → 静态预算（Long 相加）`。

### 2.4 `LlmBudget`（§b/§l 2/3）

- `effectiveMaxOutputTokens = min(request.maxOutputTokens ?: task.maxOutputTokens, task.maxOutputTokens)`；`request.maxOutputTokens < 1` → `INVALID_REQUEST`（发送前）。
- `estimate(...)` 纯计算（`LlmBudgetDecision`：input / output / margin / required），`requireWithinBudget(...)` 双条件：输入超 `task.maxInputTokens` 或 `input + output + margin > profile.contextWindowTokens` → `LOCAL_BUDGET_EXCEEDED`。
- 余量 = `ceil(input × percent / 100)`，**只按输入估算加一次**（不含输出保留）；百分比越界 → `CONFIGURATION`。
- 所有加法/乘法 Long + 饱和：两个 `Int.MAX_VALUE` 估算值相加不会回绕为负数（回归用例见下）。

### 2.5 Loader（§a 校验规则表）

`parseLlm` 只做「结构 + 类型 + 已知键 + 枚举 + 必填存在性」，随后交给 `LlmConfig.init` 做字段范围/URL/引用/静态预算：

- 未知键：`context.llm` / `profiles.<name>` / `tasks.<TASK>` 三处 `UNKNOWN_FIELD` 带完整路径；
- `profiles` 缺失/`{}`→`context.llm.profiles is required`；名为空白 → `context.llm.profiles has a blank name`；
- `apiKey`/`baseUrl` 支持既有 `${ENV}` 展开；ENV 未定义或展开后空白 → `MISSING_ENV`（沿用既有 `expandEnv` 消息：含 ENV 名 + 字段路径，不含值）；
- `jsonMode` 大小写不敏感，非法值消息列出三个合法值，不回显原值；
- `tasks` 键按 `LlmTask.name` 大小写不敏感匹配，归一化后重复 → `is defined more than once`；未知 → `is not a known LLM task`；
- 未配置 `context.llm` → `ContextConfig.llm == null`，不读任何 ENV。

### 2.6 Factory / Impl（不越界）

- `build(...)`：在既有 `ownedVfsConfig` 之后新增 `val ownedLlmConfig = contextConfig.llm?.ownedCopy()`（复制 + 校验），传给 Impl；
- `AlcyoneContextImpl` 仅新增 `internal val llmConfig: LlmConfig? = null` 参数与 import：**不创建 client、不发请求、不参与 `start`/`close` 顺序**（B4 接线）。

## 3. A02 规则 → 用例映射

| # | 规则（§a 校验规则表） | 用例（`LlmConfigValidationTest`） |
| --- | --- | --- |
| 1 | `context.llm` / Profile / Task 内未知键 → `UNKNOWN_FIELD` | `unknown llm fields are rejected with the field path`（三处路径逐一断言） |
| 2 | 无 `profiles` 或为空 → `INVALID_CONFIG` | `llm requires a non empty profiles mapping`（缺失 + `{}`） |
| 3 | Profile 名空白 | `llm rejects a blank profile name` |
| 4 | 必填缺失 / 空白 | `llm required fields are rejected when missing or blank`（baseUrl/apiKey/model 各查缺失+空白；另加 `contextWindowTokens`/`jsonMode`/task `profile`） |
| 5 | `baseUrl` 非 http(s) 或含 userinfo/query/fragment | `llm base url must be absolute http or https without credentials`（6 个输入，另断言消息不回显 URL 与凭据） |
| 6 | `apiKey` 的 ENV 未定义 / 展开空白 → `MISSING_ENV` | `llm api key environment variable must be set and is never echoed`（含 ENV 名与路径，不含 Key 值） |
| 7 | `jsonMode` 非枚举 | `llm json mode must be one of the three documented values`（含大小写不敏感正例） |
| 8 | 数值越界 | `llm numeric ranges are enforced without echoing the value`（10 个字段/边界） |
| 9 | `tasks` 键非 `LlmTask` | `llm task keys are matched case insensitively and duplicates are rejected`（未知 + 归一化重复 + 小写正例） |
| 10 | `tasks` 指向不存在 Profile | `llm task profile must exist` |
| 11 | 预算静态不自洽 | `llm static budget must fit the profile context window`（越界 + 等于容量边界通过） |
| 附 | Kotlin 直接构造同一校验 + 自有快照 | `direct kotlin llm config uses the same validation and owns its snapshot`（maxAttempts=9、未知 Profile、空 profiles、构造后清空入参集合） |
| 附 | Key 不进 message/toString/stackTrace | `llm config never echoes the api key in messages or toString`（唯一标记 `ct08-b1-unique-key-marker-51c7`） |
| A01 配置侧 | 未配置 → `llm == null` 且不读 ENV | `llm absent keeps config null and reads no environment variable` + 既有 `unimplemented blueprint sections...` 中的 `assertNull(config.llm)` |

A07 的 Estimator / 预算部分：

| 断言 | 用例 |
| --- | --- |
| ASCII / CJK / emoji / 混合；role 与协议开销；`schema(null)=0`；大输入不溢出 | `ConservativeTokenEstimatorTest`（8 例） |
| 余量只计一次；schema 计入输入；`min` 封顶与 `<1` 拒绝；超 Task 输入预算；容量含输出+余量；Int 溢出不能绕过；百分比越界；余量向上取整 | `LlmBudgetTest`（9 例） |

## 4. 执行命令与结果

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | 0 | BUILD SUCCESSFUL（`spotless-apply.log`） |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | 0 | BUILD SUCCESSFUL（`targeted-test.log`） |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | BUILD SUCCESSFUL（`neighbour-and-spotless.log`） |

### 计数（源码 `@Test([^A-Za-z]|$)` 行首精确统计 vs XML `tests`）

| 模块 | 源码 `@Test` | XML `tests` | 差额 | 失败/错误/跳过 | 本批增量 |
| --- | --- | --- | --- | --- | --- |
| `context:core` | 19 | 19 | **0** | 0/0/0 | +8（`ConservativeTokenEstimatorTest`） |
| `context:runtime` | 114 | 114 | **0** | 0/0/0 | +25（`LlmConfigValidationTest` 16 + `LlmBudgetTest` 9） |
| `context:persistence-sqldelight` | 176 | 176 | **0** | 0/0/0 | 0（未改动） |
| `context:server` | 30 | 30 | **0** | 0/0/0 | 0（未改动） |

## 5. 证据

`.local/validation/cm2/worker/ct08-b1/`：`final-source-hashes.txt`、`verification.json`、`spotless-apply.log`、`targeted-test.log`、`neighbour-and-spotless.log`。

## 6. 未做 / 交后续批次

- **B2**：`LlmRequest.schema`、`KtorLlmClient`（路由、请求体、`responseFormat` 一致性、schema 校验、响应解析与 Usage）、`LlmBudget` 实际接入发送前检查（实际发送值精确断言、0 请求断言）。
- **B3**：`LlmRetryPolicy`（可重试判定、`Retry-After`、退避 + jitter）、总期限与每次尝试前重算 remaining。
- **B4**：`KtorLlmClient` 关闭协议、Runtime `start`/`close` 接线（`llm → vfs → executor`）、Context 同步入口桥接。
- **B5**：最小内部统计、脱敏日志捕获、重定向证据、`vfs` 基线 / `./scripts/check` 全量回归。
- 本轮**未**声明 B1 以外的任何验收（A03/A04/A05/A06/A08 未启动）。
