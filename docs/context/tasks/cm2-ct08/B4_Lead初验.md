# CT08 B4 · Lead 独立初验（取消/关闭协议 + Runtime 生命周期接线）

**日期**：2026-10-29
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**B4 Lead 初验通过，提交 Architect 独立复核**；B5 在复核通过前不启动。
**依据**：[B4_委派单](B4_委派单.md)、[CT08 方案](CT08_方案.md) §f v3/§g/§h A06/A01/A08/§j/§m、[架构与技术复核](架构与技术复核.md) §9（B3 DONE）

## 0. 本次修改（2026-10-29，B4 初验记录建立）

Worker 完成 B4 并交付；Lead 独立复算 SHA、跑三组命令、逐条核对 §f 关闭协议与 Runtime 接线，并用自建探针做 6 项并发/生命周期反例。**全部通过**；另记录 5 项非阻断观察（含一项探针自身判据修正）。

## 1. 交付面与边界核对

本批窗口（23:12–23:14）仅 8 个文件变化；`AlcyoneContextFactory.kt`、`LlmRetryPolicy.kt`、两个 stub、`KtorBoundaryTest.kt` 与 B4 派发基线一致：

| 类型 | 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- | --- |
| 改 | `context/runtime/.../llm/KtorLlmClient.kt` | `6bd57249…1823b4228` |
| 改 | `context/runtime/.../runtime/AlcyoneContextImpl.kt` | `b1da8a42…b0770e58e` |
| 新 | `.../llm/KtorLlmClientCloseTest.kt` | `ca5c7e69…ca413a83` |
| 新 | `.../runtime/LlmRuntimeLifecycleTest.kt` | `b86da064…68d7185c` |
| 定点改 | `.../llm/KtorLlmClientResponseTest.kt` | `188b2ba4…16ec2da75` |
| 机械改 | `KtorLlmClientRequestTest.kt` / `KtorLlmClientRoutingTest.kt` / `KtorLlmClientRetryTest.kt` | `bb22cfbf…` / `408dcae5…` / `cf86ed2e…` |

- `spotlessApply` 后 8 个 SHA 不变；`KtorLlmClient` 已无 `close()`（仅 `closeAsync()`）；测试中无 `client.close()` 残留。
- 3 个机械重命名文件仅 `close()` → `closeAsync()`；`KtorLlmClientResponseTest` 仅按 §5.2 调整 `close is idempotent and does not close an injected client`（close 后新调用 `CLOSED` + 0 请求；注入 HttpClient 仍可直接使用），其余断言未动。
- 未改 `LlmClient`/`LlmConfig`/`LlmBudget`/`LlmRetryPolicy`/Loader/Factory/`ContextConfigLoaderTest`/stub/构建/依赖/vfs/common/persistence/server；无新依赖、无新公开 API（新增访问器均为 `internal`）。

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
| runtime | 176 | 176 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

新增 10 例：`KtorLlmClientCloseTest` 6（A06 ①～⑤ + CLOSING 窗口独立用例）、`LlmRuntimeLifecycleTest` 4（A01/接线/清理失败/关闭后）；runtime 166 → 176。

## 3. 逐条核对 §f 关闭协议

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 状态/在途/共享结果同一把普通锁；持锁无挂起调用 | 源码（`lock`、`state`、`inFlight`、`closeOutcome`） | ✔ |
| `complete()`：`ensureActive` → `coroutineScope` + `async(LAZY)` → 锁内准入 + `invokeOnCompletion` 移除 → 未准入 cancel 后 `ContextLifecycleException(CLOSED)` → `start(); await()` | 源码；关闭后新调用（含未配置 task 的请求）返回 `CLOSED` 而非 `CONFIGURATION`（Lead 探针） | ✔ |
| 不使用游离 `Job(parent=...)`；移除只在 `invokeOnCompletion` | 源码 + A06 ③（close 返回时 inFlight=0 且调用方 finally 屏障已执行） | ✔ |
| `closeAsync()` 唯一入口；无同步 close | `grep` 无 `close()`；测试无 `client.close()` | ✔ |
| owner `NonCancellable`：取消快照 → 循环 join → 关自建 HttpClient → catch 发布失败 → finally CLOSED + complete | 源码；清理失败用例（CLOSED + `assertSame` 重抛） | ✔ |
| 共享结果含失败；CLOSED 后重复 close 重抛同一实例 | A06 ④ + Lead 探针（6 个并发 close 等待者拿到同一实例） | ✔ |
| 等待者取消只放弃自身等待，owner 仍完成 | A06 ⑤ | ✔ |
| 在途取消不转 CLOSED；调用方收 `CancellationException` | A06 ①/③ + Lead 探针（取消后 client 仍 RUNNING 且可继续成功调用） | ✔ |
| CLOSING/CLOSED 新调用 0 HTTP | A06 ② + Lead 探针（CLOSING 窗口、并发 close 后新调用全部 CLOSED） | ✔ |
| 注入 client 不由本类关闭 | 调整后的 B2 用例：注入 HttpClient 仍可直接请求 | ✔ |

## 4. Runtime 接线核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| `performStart` 顺序 `executor → vfs → llm`，三者成功后才 probe/发布 RUNNING | 源码 + 启动探针失败用例（llm 已创建时 probe 抛错） | ✔ |
| 失败逆序清理（llm → vfs → executor） | Lead 探针：probe 抛错后 `releaseProbe` 记录 `[VFS, EXECUTOR]`、llm 引用为 null、Context CLOSED | ✔ |
| `releaseResources` 顺序 `llm → vfs → executor`，独立 try/catch + `chain`，无 `ReleaseTarget.LLM` | 源码 + Lead 探针事件序列 `[LLM, VFS, EXECUTOR]` | ✔ |
| `shutdown` 两处捕获并置空 llm | 源码 | ✔ |
| LLM 清理失败仍释放其余资源，主失败为 LLM 异常 | `LlmRuntimeLifecycleTest`（`assertSame`、`[VFS, EXECUTOR]`、vfs/database 为 null） | ✔ |
| `create` 零副作用；HttpClient 只在 start 创建 | 源码 + 既有 create 用例；start 前 stub 0 请求 | ✔ |
| 未配置 llm → 不建 client、释放跳过 | A01 用例 | ✔ |
| 关闭后 client 拒绝新调用（A08 子项） | `LlmRuntimeLifecycleTest`（保存引用 → `CLOSED`、0 请求） | ✔ |

## 5. Lead 独立探针（6/6 PASS）

探针：`.local/validation/cm2/lead/ct08-b4/probe/LeadB4ProbeTest.kt`（结果 `lead-probe-results.xml`，`tests=6 failures=0 errors=0`）：

| # | 探针 | 结果 |
| --- | --- | --- |
| 1 | 关闭后新调用即使请求本身非法，也先返回 `CLOSED`（准入先于校验） | PASS |
| 2 | 6 个并发 `closeAsync()` 在清理失败时拿到**同一异常实例** | PASS |
| 3 | 在途取消后 client 仍 `RUNNING`，可继续成功调用（取消不关闭） | PASS |
| 4 | 20 并发调用 + 关闭：close 返回时 inFlight=0；关闭后 10 次新调用全 `CLOSED`，总请求数 ≤20（新调用 0 HTTP） | PASS |
| 5 | Runtime 释放顺序 `[LLM, VFS, EXECUTOR]` | PASS |
| 6 | llm 创建后 start 探针失败 → 逆序释放、Context CLOSED、llm 引用为 null | PASS |

**探针判据修正记录（如实归档）**：探针 4 首版断言「close 返回后 stub 计数不再增长」，在 20 并发 + 40ms 响应延迟下出现 13→19 的增长。独立诊断（`.local/validation/cm2/lead/ct08-b4/diag*.log`）确认：关闭前已准入的 20 次调用在取消时可能已交给引擎，stub 的 `requestCount` 在 handler 线程启动时自增，存在调度滞后；把关闭前延迟加大到 200ms 时 20 次请求全部在 close 前计数、close 后无增长。该现象属「已准入调用已被引擎派发」的固有网络竞态（§f 只要求新调用 0 HTTP、在途集合为空），不是新请求，也不是产品缺陷。探针 4 已改为按契约断言（新调用全 CLOSED、总数 ≤20），通过。

## 6. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | 关闭前已准入的调用若已被引擎派发，close 返回后 stub 仍可能计数（取消无法撤回已发出的请求） | 与 B3「超时不保证 Provider 未生成结果」一致；§f 只要求新调用 0 HTTP，非阻断 |
| L2 | `performStart` 发布块内（`createIngestionService`）抛错时，已赋值的 `executor/vfs/llm` 字段未显式置空（沿用 CM1 既有模式） | B4 未改变既有行为；`llm` 仅新增同样模式。建议后续统一收口，非本批阻断 |
| L3 | owner `closeAsync()` 若被调用方取消，清理仍完成并发布；owner 返回清理结果而非调用方取消 | §f 要求 owner 不可取消，未规定调用方取消后的返回类型；等待者取消语义已单独覆盖 |
| L4 | `closeActionForTesting` / `stateForTesting` / `inFlightCountForTesting` / `llmClientForTesting` 均为 `internal` | 非公开 API；与既有 `*ForTesting` 访问器同模式 |
| L5 | 注入的 HttpClient 不安装 `HttpTimeout` 时仍无逐请求超时（B3 已记录） | B4 未改变；生产由 start 创建的自建 client 安装插件 |

## 7. 结论与下一步

- §f v3 的准入、跟踪、owner/等待者、失败发布与重抛、取消语义全部落实；Runtime 构造/释放顺序与失败清理正确；A06 五类 + A01 完整 + 关闭后新调用均有正式用例与 Lead 独立探针证据。
- 四模块 19/176/176/30，源码与 XML 差额 0、失败/错误/跳过 0；B2/B3 行为未回退。
- **B4 Lead 初验通过，提交 Architect 独立复核**。
- **Architect 裁定（2026-10-29）**：**B4 APPROVED / DONE**，可启动 B5；复核记录见 [架构与技术复核](架构与技术复核.md) §10（8 文件 SHA 一致、独立实跑 19/176/176/30 全零、spotless 通过、6 项探针 6/6）。

### 证据路径

- `.local/validation/cm2/lead/ct08-b4/spotlessApply.log`
- `.local/validation/cm2/lead/ct08-b4/core-runtime-test.log`、`core-runtime-test-final.log`
- `.local/validation/cm2/lead/ct08-b4/persistence-server-test.log`
- `.local/validation/cm2/lead/ct08-b4/lead-probe.log`、`lead-probe-results.xml`、`probe/`
- `.local/validation/cm2/lead/ct08-b4/diag.log`、`diag2.log`（探针判据修正诊断）
