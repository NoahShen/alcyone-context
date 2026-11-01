# CT08 B5 · Lead 独立初验（最终证据与回归）

**日期**：2026-10-29
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**B5 Lead 初验通过，提交 Architect 独立复核**；CT08 是否 DONE 由 Architect 裁定。
**依据**：[B5_委派单](B5_委派单.md)、[CT08 方案](CT08_方案.md) §g/§h A08/§j B5/§m 5、[架构与技术复核](架构与技术复核.md) §10（B4 DONE）

## 0. 本次修改（2026-10-29，B5 初验记录建立）

Worker 完成 B5 并交付；Lead 独立复算 SHA、跑定向测试与 `./scripts/check`、逐模块核对 `@Test`=XML、校验 81 基线 / `vfs` 零改动 / Core 无 Ktor 双证据，并用自建探针复核统计边界、并发计数与日志字段。**全部通过**；记录 6 项非阻断观察（含计数口径说明）。

## 1. 交付面与边界核对

本批窗口（23:39–23:46）仅 5 个文件；`AlcyoneContextImpl`、`LlmRetryPolicy`、B4 两个新测试、`KtorLlmClientCloseTest`、两个 stub、`build.gradle.kts` 均与派发基线一致：

| 类型 | 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- | --- |
| 新 | `context/runtime/.../llm/LlmCallDiagnostics.kt` | `4c838195…18b4e84f` |
| 改 | `context/runtime/.../llm/KtorLlmClient.kt` | `f5615337…956aebf5` |
| 新 | `.../llm/KtorLlmClientDiagnosticsTest.kt` | `ece777b8…0e886cac` |
| 新 | `.../llm/KtorLlmClientRedirectTest.kt` | `4243852a…ce58c5e1` |
| 新 | `.../llm/Slf4jCapture.kt`（测试辅助） | `ad2b549e…97c035a6` |

- `spotlessApply` 后 5 个 SHA 不变；未改 `LlmClient`/`LlmConfig`/`LlmBudget`/`LlmRetryPolicy`/Loader/Factory/`AlcyoneContextImpl`/既有 Ktor 测试/两个 stub/构建/依赖/vfs/common/persistence/server；无新依赖；新增类型与访问器全部 `internal`，无公开 API/`ContextStatus`/`LlmResponse` 扩展。
- `LlmCallDiagnostics` / `LlmCallStats` / `LlmCallStatistics` / `statsForTesting` 均 `internal`；统计仅进程内、无落库。

## 2. Lead 独立实跑

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/check` | **exit 0**，`BUILD SUCCESSFUL in 1m 48s`，**130 actionable tasks: 130 executed** |

`./scripts/check` 后全模块计数（行首 `@Test` vs XML）：

| 模块 | 源码 | XML | 差额 | fail/err/skip |
| --- | ---: | ---: | ---: | --- |
| common | 23 | 23 | 0 | 0/0/0 |
| context/api | 26 | 26 | 0 | 0/0/0 |
| context/core | 19 | 19 | 0 | 0/0/0 |
| context/persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| context/runtime | 189 | 189 | 0 | 0/0/0 |
| context/server | 30 | 30 | 0 | 0/0/0 |
| integration-tests/context | 15 | 15 | 0 | 0/0/0 |
| integration-tests/vfs | 107 | 107 | 0 | 0/0/0 |
| vfs/api | 66 | 66 | 0 | 0/0/0 |
| vfs/core | 305 | 305 | 0 | 0/0/0 |
| vfs/git-backup | 73 | 73 | 0 | 0/0/0 |
| vfs/persistence | 52 | 52 | 0 | 0/0/0 |
| vfs/runtime | 61 | 61 | 0 | 0/0/0 |
| vfs/storage | 154 | 154 | 0 | 0/0/0 |
| **合计** | **1296** | **1296** | **0** | **0/0/0** |

增量一致：runtime 176 → 189（+13）。

## 3. 统计契约核对（§3）

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 按 `(task, profile)` 分组；`calls = successes + failures` | `LlmCallStatistics`（ConcurrentHashMap + 每项 synchronized） | ✔ |
| `attempts` 为实际执行次数之和 | 成功分支取本次 attempt；失败取异常 `attempts`；500,500+maxAttempts=2 → attempts=2 | ✔ |
| `usagePresent/usageMissing` | 成功+usage / 缺 usage 用例 | ✔ |
| token 仅对 usage 求和；无样本为 `null`（不填 0） | 缺 usage 用例 + Lead 探针「部分 usage 字段缺失保持 null」 | ✔ |
| 取消/`CLOSED` 不计统计、不写日志 | 在途取消、退避中取消两用例 | ✔ |
| 路由前 `CONFIGURATION` 只写失败日志、不计统计 | 专用用例 | ✔ |
| 发送前失败 `attempts=0` 且 0 请求 | 超预算用例 + Lead 探针 | ✔ |
| 并发计数不丢失 | Lead 探针：20 并发成功 → calls/successes/attempts/usagePresent 均 20，token 求和 220/140/360 | ✔ |
| 无记录返回 `null` | 专用用例 | ✔ |

## 4. 日志/诊断契约核对（§4）

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 成功 INFO `llm.call.completed`、失败 WARN `llm.call.failed`，每终态一条 | 真实 SLF4J 捕获用例 | ✔ |
| 字段仅白名单 `task/profile/model/attempts/statusCode/elapsedMillis/estimatedInputTokens/providerUsagePresent/retryable` | 成功日志字段集合 ⊆ 白名单；Lead 探针失败日志字段 ⊆ 白名单 | ✔ |
| 未知值输出 `null`（不冒充 0） | 发送前失败：`attempts=0`、`statusCode=null`、`estimatedInputTokens=null`、`retryable=false`（Lead 探针） | ✔ |
| 不泄漏 Key/Prompt/schema/响应 body/baseUrl path | 五类独特标记在全部日志与 `LlmCallDiagnostics.toString()` 中均不出现 | ✔ |
| 不把 Throwable 传给 logger | 捕获项 `throwable == null`（用例 + Lead 探针） | ✔ |
| 诊断对象无 Key 访问器、toString 同规则 | 源码 + 用例 | ✔ |
| 不逐次尝试写日志 | 源码仅在 `recordSuccess` / `catch(LlmException)` 写一次 | ✔ |

## 5. 重定向证据（§5）

- 真实两个 `HttpServer`：第一个 302 + `Location` 指向第二个；客户端 `PROVIDER_UNAVAILABLE`、`statusCode=302`、`attempts=1`；第一 server 1 次、**第二 server 0 次**（因此无凭据外发）。
- 源码证据：自有 HttpClient `followRedirects = false` + `install(HttpTimeout)`（专项用例读取源码断言）。
- 该用例在 `./scripts/check` 中通过。

## 6. 基线与边界证据

| 项 | 结果 |
| --- | --- |
| vfs 81 基线（`.local/validation/cm1/architect/vfs-baseline-sha256.json`） | `Checked 81 files: all OK` |
| `git status --porcelain vfs integration-tests/vfs` | 0 行 |
| Core build 文本 | `grep -rn ktor context/core/build.gradle.kts` 无命中 |
| Core 主源码 import | 无 `import io.ktor` |
| Core compileClasspath | `:context:core:dependencies --configuration compileClasspath` 中 ktor 命中 0 |
| 既有边界测试 | `CoreSkeletonTest` 2/2、`KtorBoundaryTest` 1/1，0 失败/错误/跳过 |
| `vfs`/`common` 改动 | 无 tracked 改动；`common/` 仅 CM1 遗留 4 个未跟踪文件 |

## 7. Lead 独立探针（3/3 PASS）

探针：`.local/validation/cm2/lead/ct08-b5/probe/LeadB5ProbeTest.kt`（结果 `lead-probe-results.xml`，`tests=3 failures=0 errors=0`）：

| # | 探针 | 结果 |
| --- | --- | --- |
| 1 | 部分 usage（仅 `prompt_tokens`）→ `inputTokens=7`、`outputTokens/totalTokens=null`、`usagePresent=1` | PASS |
| 2 | 20 并发成功 → 计数精确、token 求和正确、无丢失 | PASS |
| 3 | 发送前预算失败 → 统计 `failures=1 attempts=0`；WARN 日志字段 ⊆ 白名单且 `statusCode/estimatedInputTokens=null`、`throwable=null` | PASS |

## 8. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | 计数口径：项目约定为**行首** `@Test`；非锚定扫描会把 `integration-tests/context/.../B3Fixtures.kt` 注释中的 `@Test` 计入（16 vs 15）。按行首口径为 15/15，全模块 1296=1296，与 Worker 报告一致 | 非缺陷；Lead 初验采用行首口径 |
| L2 | 日志字段以空格分隔 `key=value`；若 profile/model 名本身含空格，人工解析会有歧义（生产日志仍完整） | 非阻断；白名单字段与值均由配置给出，不含敏感信息 |
| L3 | 失败诊断 `providerUsagePresent=false`，即使失败发生在 2xx 解析阶段（此时没有可用 usage） | 语义为「本次失败未获得 usage」，非阻断 |
| L4 | 统计仅进程内、`internal` 访问，不落库、不新增公开 `ContextStatus` 字段 | 符合 §m 5 |
| L5 | `./scripts/check` 不含真实 WebDAV 套件（独立 `webdav-check`）；CT08 未改 `vfs`，81 基线不变 | B5 范围未要求 WebDAV 重跑，非阻断 |
| L6 | token 求和为 `Long`，极端长期运行存在溢出可能（无上限要求） | 非本批要求，记录备查 |

## 9. 结论与下一步

- 统计、脱敏日志/诊断、302 不追随、全量回归（`./scripts/check` exit 0、130 tasks、1296=1296）、81 基线、`vfs` 零改动、Core 无 Ktor 双证据全部满足。
- **B5 Lead 初验通过，提交 Architect 独立复核**。
- **Architect 裁定（2026-10-29）**：**B5 APPROVED / DONE；CT08 DONE，可启动 CT09**。复核记录见 [架构与技术复核](架构与技术复核.md) §11（5 文件 SHA 一致、`./scripts/check` 130/130、全模块 1296=1296、81 基线 all OK、Core 无 Ktor、3 项探针 3/3）。

### 证据路径

- `.local/validation/cm2/lead/ct08-b5/spotlessApply.log`、`core-runtime-test.log`、`persistence-server-test.log`、`check.log`
- `.local/validation/cm2/lead/ct08-b5/test-counts.txt`、`vfs-baseline.txt`、`vfs-git-status.txt`、`core-dependencies.log`
- `.local/validation/cm2/lead/ct08-b5/lead-probe.log`、`lead-probe-results.xml`、`probe/`
