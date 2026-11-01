# CT08 B3 · Lead 独立初验（总期限 / 有限重试 / Retry-After / 取消传播）

**日期**：2026-10-29
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**B3 Lead 初验通过，提交 Architect 独立复核**；B4 在复核通过前不启动。
**依据**：[B3_委派单](B3_委派单.md)、[CT08 方案](CT08_方案.md) §e/§f/§g/§h A05/§j/§m、[架构与技术复核](架构与技术复核.md) §8（B2 DONE）

## 0. 本次修改（2026-10-29，B3 初验记录建立）

Worker 完成 B3 并交付；Lead 独立复算 SHA、跑三组命令、逐条核对 §3 锁定行为，并用自建脚本化 HTTP 服务做 8 项反例探针。**全部通过**；仅记录 5 项非阻断观察。

## 1. 交付面与边界核对

本批窗口（22:53–22:56）仅 6 个文件；B2 文件、`LlmHttpStub.kt`、`LlmClient.kt`、两个 build 文件 SHA 与 B3 派发基线完全一致：

| 类型 | 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- | --- |
| 新 | `context/runtime/.../llm/LlmRetryPolicy.kt` | `6aa53676…712f5773` |
| 改 | `context/runtime/.../llm/KtorLlmClient.kt` | `d5452f37…39048088` |
| 新 | `.../llm/LlmRetryPolicyTest.kt` | `92f136ea…71b94587b` |
| 新 | `.../llm/KtorLlmClientRetryTest.kt` | `1a2a318f…6e9cb053b0` |
| 新 | `.../llm/ScriptedHttpStub.kt` | `d3fc1590…a2ad6fc5b59e3` |
| 定点改 | `.../llm/KtorLlmClientResponseTest.kt` | `87a395fb…5f5c86594` |

- `spotlessApply` 后 6 个 SHA 不变。
- 唯一既有测试修改经逐行核对：`non 2xx statuses map to the frozen error codes` 与 `transport failure is classified as network` 两例仅新增 `maxAttempts = 1` 与说明注释（helper 增加默认参数），其余断言、用例与文件未动；符合委派单 §5。
- 未改 `LlmClient`/`LlmConfig`/`LlmBudget`/Loader/Factory/Impl/`ContextConfigLoaderTest`/Routing/Request/Boundary/`LlmHttpStub`/构建/依赖/vfs/common/persistence/server；core 仍无 Ktor。

## 2. Lead 独立实跑与计数

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL，26 tasks executed |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL，28 tasks executed |
| 探针后恢复正式 XML：`:context:core:test :context:runtime:test --rerun-tasks` | BUILD SUCCESSFUL |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 19 | 19 | 0 | 0/0/0 |
| runtime | 166 | 166 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

新增用例分布：`LlmRetryPolicyTest` 6、`KtorLlmClientRetryTest` 13；runtime 147 → 166（+19）。

## 3. 逐条核对 §3 锁定行为

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| deadline 覆盖全部尝试与退避；每次尝试/退避前重算 `remaining`，不复用旧值 | `complete()` 循环源码（每轮 `deadline - monotonicNowMillis()`；退避前 `remainingNow` 重新计算） | ✔ |
| `remaining <= 0` → `TIMEOUT`，不再发请求；`attempts` = 已执行次数 | 源码 + 总期限探针（1s 内返回，码为 TIMEOUT/PROVIDER_UNAVAILABLE，非取消） | ✔ |
| 单次请求超时 = `min(profile.requestTimeout, remaining)`；owned client 安装 `HttpTimeout` 并逐请求设置 connect/socket/request | 构造器 `install(HttpTimeout)`；`callOnce` 的 `timeout { }`；注入 client 用 `pluginOrNull` 跳过 | ✔ |
| 超时三类 → `TIMEOUT` 可重试；其他传输 → `NETWORK` 可重试 | `isTimeoutFailure` 遍历 cause 链（≤8 层）；`transportFailure` 分类；worker 用例覆盖 408 与单次超时 | ✔ |
| `CancellationException` 原样传播、不包装、不重试；未用 `withTimeout` 混同 | 两处显式 `catch (cancellation)`；`ensureActive()` + 可取消 `delay`；退避中/在途取消用例 + Lead 探针 | ✔ |
| 可重试表（NETWORK/TIMEOUT/429 三支/5xx 细分/4xx/2xx 解析类） | `LlmRetryPolicy.isRetryable` + 分类器；`LlmRetryPolicyTest` 表驱动 | ✔ |
| 429 额度耗尽优先于 `Retry-After` 且不重试 | `httpFailure` 先判 quota 再给 RATE_LIMITED；worker 用例 + Lead 探针（`Retry-After: 0` 仍 1 次） | ✔ |
| 429 无有效信号不重试 | 无 `Retry-After`、无限流标记 → `retryable=false`；worker 用例 | ✔ |
| `Retry-After` 五条规则（秒 / HTTP-date / 无效退避 / ≤remaining 精确等待 / >remaining 立即失败） | `parseRetryAfter` + `complete` 分支；策略单测 + 客户端用例 + Lead 探针 | ✔ |
| 退避 `min(1000·2^(n-1), 10s)` ±20%，jitter 后 ≤10s | `backoffMillis`（指数上限 4、`coerceIn(0, 10_000)`）；策略单测 200 次/档 | ✔ |
| 实际等待 `min(wait, remaining)`、可取消 | 源码 `delay(minOf(...))` / `delay(retryAfter)` | ✔ |
| 不重复消费/不换 Profile、`maxAttempts` 生效 | 循环仅按同一 Profile 重试；耗尽用例 attempts=2 | ✔ |
| B2 行为未回退 | B2 的 28 例 + B3 后全量 166 例全绿；异常链仍不挂原始解析异常 | ✔ |

## 4. Lead 独立探针（8/8 PASS）

探针：`.local/validation/cm2/lead/ct08-b3/probe/LeadB3ProbeTest.kt`（结果 `lead-probe-results.xml`，`tests=8 failures=0 errors=0`），使用 Lead 自建脚本化 `HttpServer`（不复用 Worker 的 `ScriptedHttpStub`）：

| # | 探针 | 结果 |
| --- | --- | --- |
| 1 | `500,500,200` + `maxAttempts=3` → 3 次请求后成功 | PASS |
| 2 | 持续 500 + `maxAttempts=2` → `PROVIDER_UNAVAILABLE`、`attempts=2`、2 次请求 | PASS |
| 3 | `429 + Retry-After: 100` + `totalTimeout=2s` → 1 次请求、`RATE_LIMITED`、耗时 < 2s | PASS |
| 4 | `429 + insufficient_quota + Retry-After: 0` → 1 次请求、`QUOTA_EXCEEDED` | PASS |
| 5 | `429 + Retry-After: 1` 后 200 → 2 次请求、间隔 ≥ 950ms | PASS |
| 6 | 退避等待中取消 → `CancellationException`（非 `LlmException`）、取消后不再发请求 | PASS |
| 7 | 持续 500 + `totalTimeout=1s` → 1s + slack 内返回、码为 TIMEOUT/PROVIDER_UNAVAILABLE、请求数 ≤ maxAttempts、非取消 | PASS |
| 8 | 注入未安装 `HttpTimeout` 的 HttpClient → 普通请求正常完成 | PASS |

## 5. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | `deadline` 在发送前校验与 body 构造之后起算（委派单 §3.1 的「入口（发送前校验之后）」写法） | 前置步骤均为纯本地、无 I/O，不产生时间预算泄漏；§e「覆盖连接/请求/响应/退避」满足。若 Architect 要求字面「函数第一行起算」，可一行前移后重验 |
| L2 | `callOnce` 捕获 `Exception` 后统一归 `NETWORK`（可重试），理论上会把非 IO 的编程错误也当传输失败 | 现有路径下仅 Ktor/IO 异常可达；B4 关闭态接入后再复核，非本批阻断 |
| L3 | `Retry-After: "+1"` 经 `toLongOrNull` 被接受为 1 秒 | 非负整数字面量，行为无风险；如需严格数字正则可在 B5 统一 |
| L4 | 3xx（不追随）归 `PROVIDER_UNAVAILABLE` 且不可重试 | 方案未定义 3xx；重定向证据在 B5，非本批阻断 |
| L5 | 若首次尝试前 `remaining <= 0`（实际不可能，totalTimeout ≥1s），`attempts=0` | 语义为「已执行尝试数」，与委派单一致 |

## 6. 结论与下一步

- §3 全部锁定行为落实；19 例新增测试 + 8 项 Lead 独立探针全绿；四模块 19/166/176/30 差额 0、失败/错误/跳过 0；B2 行为无回退。
- **B3 Lead 初验通过，提交 Architect 独立复核**；B4 在复核通过前不启动。
- **Architect 裁定（2026-10-29）**：**B3 APPROVED / DONE**，可启动 B4；复核记录见 [架构与技术复核](架构与技术复核.md) §9（独立实跑 19/166/176/30 全零、spotless 通过、8 项探针 8/8、6 文件 SHA 一致）。

### 证据路径

- `.local/validation/cm2/lead/ct08-b3/spotlessApply.log`
- `.local/validation/cm2/lead/ct08-b3/core-runtime-test.log`、`core-runtime-test-final.log`
- `.local/validation/cm2/lead/ct08-b3/persistence-server-test.log`
- `.local/validation/cm2/lead/ct08-b3/lead-probe.log`、`lead-probe-results.xml`、`probe/`
