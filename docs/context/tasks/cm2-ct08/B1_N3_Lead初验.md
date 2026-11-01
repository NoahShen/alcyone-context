# CT08 B1 · N3 修复 Lead 独立初验

**日期**：2026-10-29
**初验人**：CM2 Lead（只读初验，不写生产代码）
**提交对象**：Architect（B1 是否通过由 Architect 独立复核裁定；Lead 不自标 DONE）
**依据**：[B1_N3修复委派单](B1_N3修复委派单.md) §4、[架构与技术复核](架构与技术复核.md) §3（N3）、[CT08 方案](CT08_方案.md) §m
**Worker**：`10264b13-4fd6-4e6d-b699-b8095efd6ab6`（Paseo 配置 `worker`，`pi/my_magpie/group/worker`，thinking `high`）

## 0. 本次修改（2026-10-29，B1 N3 修复初验记录建立）

原 Worker `d7695b22` 归档后，按 Architect 指示改用新 Worker 执行 N3 修复；Lead 独立复算 SHA、独立实跑三组命令、逐条核对修复要求 1～5，并复现 Architect 反例（修复前 2/2 失败 → 修复后 2/2 通过）。结论见 §6：**初验通过，提交 Architect 复核；B2 不启动。**

## 1. 交付面与边界核对

`find -newermt '-60 minutes'`（排除 `build/`）在 `context/ common/ vfs/ integration-tests/` 下仅命中 2 个 `.kt`，无构建文件、非源码与依赖漂移：

| 文件 | 性质 | Worker SHA-256 | Lead 复算 SHA-256 |
| --- | --- | --- | --- |
| `context/runtime/.../runtime/llm/LlmBudget.kt` | 修改 | `f1887e2a…982e5f83f` | `f1887e2affc7dec3eaa2eceb222535c2028b12244cf73375420639c982e5f83f` ✔ |
| `context/runtime/.../runtime/llm/LlmBudgetTest.kt` | 修改 | `924f1064…45700596a` | `924f10640ea894f3a103006d17307bb248949b8ea52b338697c6bc245700596a` ✔ |

- 执行 `./scripts/dev gradle spotlessApply --console=plain` 后**两个 SHA 仍不变** → 格式合规且无隐式改写。
- 未改 `LlmConfig` / Loader / Factory / Impl / `ContextConfigLoaderTest`；`vfs/**`、`common/**`、`context/persistence-sqldelight/**`、`context/server/**` 无源码改动；`context/**/build.gradle.kts` 未变（无新依赖、无 Ktor）。
- `git status --porcelain context common vfs integration-tests` 与 B1 派发前一致（`context/`、`integration-tests/context/` 整体仍是未跟踪目录，`common/` 仅 CM1 遗留 4 个未跟踪文件），无 git 操作迹象。

## 2. Lead 独立实跑（不复用 Worker 结果）

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | `BUILD SUCCESSFUL`，57 tasks（全部 up-to-date），SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | `BUILD SUCCESSFUL`，26 tasks executed |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | `BUILD SUCCESSFUL`，28 tasks executed |

计数（源码 `@Test([^A-Za-z]|$)` vs `build/test-results/test/*.xml` 的 `tests`，Lead 复算）：

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | --- | --- | --- | --- |
| context:core | 19 | 19 | 0 | 0/0/0 |
| context:runtime | 119 | 119 | 0 | 0/0/0 |
| context:persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| context:server | 30 | 30 | 0 | 0/0/0 |

增量与派发基线一致：`LlmBudgetTest` 9 → 14（+5），runtime 114 → 119（+5），core 19 不变。

## 3. 逐条核对修复要求（Architect 1～5）

| # | 要求 | 实现/证据 | 结论 |
| --- | --- | --- | --- |
| 1 | 在 `estimate` 内、任何加法/乘法**之前**校验两个估算值非负 | `estimate` 中 `nonNegativeTokenEstimate("estimator.estimatePrompt", estimator.estimatePrompt(...))` 与 `…estimateSchema` 紧接在取回原始 `Int` 后、`saturatedAdd` 之前；`marginTokens`/`required` 均为其后 | ✔ |
| 2 | 违规抛 `INVALID_REQUEST`，消息含来源名与 `non-negative`，不回显请求/Schema/Key/URL | 消息固定为 `"$source must return a non-negative token count (actual=$tokens)"`，`source` 仅两种白名单字面量；单测断言 `!contains("hi")`、`!contains(CREDENTIAL_MARKER)`、`!contains("llm.example.com")`、`!contains("object")` | ✔ |
| 3 | **不得 clamp 为 0**（fail closed）；`0` 合法 | 代码无 `coerce*`/`clamp`/`maxOf(0, …)`（仅注释说明不 clamp）；负值一律抛异常；`zero estimates stay legal` 断言 `estimatedInputTokens=0`、`safetyMarginTokens=0`、`requiredTotalTokens=3000` 且 `requireWithinBudget` 返回同一判定 | ✔ |
| 4 | 正式回归五类 | `LlmBudgetTest` 新增 5 例（见 §4 表），均为 Kotlin 断言正式用例，非临时探针；XML `LlmBudgetTest tests="14"` | ✔ |
| 5 | 只修 B1，不抢做 B2/B3/B4/B5 | 改动面仅 2 文件；无 `schema` 字段、无 HTTP/Ktor、无重试/期限、无 `start`/`close` 接线、无统计 | ✔ |

补充核对：

- **双入口**：`requireWithinBudget` 复用 `estimate`，故负值在两条入口均先抛 `INVALID_REQUEST`，不再进入预算比较；`a negative prompt estimate cannot bypass require within budget` 专门锁定修复前的「`estimatedInput=-1`、`requiredTotal=2999` 静默通过」路径。
- **错误分类**：`schema=Int.MIN_VALUE` 现在为 `INVALID_REQUEST`，用例显式断言 `errorCode != LOCAL_BUDGET_EXCEEDED`，不再被饱和算术误报为本地预算超限。
- **饱和未回退**：`Int.MAX_VALUE × 2` 仍得 `2L*Int.MAX_VALUE > 0`、余量 > 0、总量 > 输入，`requireWithinBudget` 抛 `LOCAL_BUDGET_EXCEEDED`；原 `int overflow cannot bypass the limits` 用例保持通过。
- **判定对象语义未变**：`LlmBudgetDecision` 仍为 4 个字段（`estimatedInputTokens: Long`、`effectiveMaxOutputTokens: Int`、`safetyMarginTokens: Long`、`requiredTotalTokens: Long`），构造顺序与文档注释未变；`effectiveMaxOutputTokens`、`marginTokens` 逻辑未改。

## 4. 反例复现（独立于 Worker 用例）

| 复现方式 | 修复前 | 修复后（Lead 实跑） |
| --- | --- | --- |
| Architect 探针 `.local/validation/cm2/architect/ct08-b1/runtime/NegativeEstimatorProbeTest.kt`（经 `probe.init.gradle` 重跑） | 2/2 失败（`expected exception but completed successfully`） | **2/2 PASS**（`tests=2 failures=0 errors=0`） |
| Lead 自建探针 `.local/validation/cm2/lead/ct08-b1-n3/probe/LeadN3ProbeTest.kt` | — | **2/2 PASS**：① `requireWithinBudget(prompt=-1)` 抛 `INVALID_REQUEST` 且消息含 `estimator.estimatePrompt`/`non-negative`；② `0/0` 合法（`requiredTotal=3000`）、`Int.MAX_VALUE×2` 抛 `LOCAL_BUDGET_EXCEEDED` |

探针运行后已重新执行 `:context:core:test :context:runtime:test --rerun-tasks`，正式 XML 恢复 core 19 / runtime 119（差额 0，0 失败/错误/跳过），探针类不在正式源码目录内。

## 5. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | 消息含实际估算值（`actual=-1` / `actual=-2147483648`） | 属数值元数据，不含请求正文/Schema/Key/URL，符合要求 2；无需修改 |
| L2 | 校验位置在 `safetyMarginPercent` 与 `effectiveMaxOutputTokens` 两个入参检查之后 | 两者均不参与估算算术，且先校验自身入参更符合「先参数、后估算」顺序；不影响「算术前拒绝」的实质要求 |
| L3 | Worker 首轮曾因断言 `requiredTotalTokens == Long.MAX_VALUE` 失败后改为「为正且大于输入」 | 变更后的断言与 `Int` 级输入的真实饱和语义一致（不会触达 `Long.MAX_VALUE`），已在 Worker 证据中如实记录；Lead 复跑全绿 |
| L4 | 原 Worker `d7695b22` 归档原因未在 Paseo 侧保留可查记录 | 不影响本次交付身份；新 Worker 的改动面、SHA 与实跑已独立核实 |

## 6. 结论

- N3 修复满足 Architect 要求 1～5：双入口对负值 fail closed 为 `INVALID_REQUEST`（不 clamp），`0` 合法，`Int.MAX_VALUE×2` 仍饱和为正并 `LOCAL_BUDGET_EXCEEDED`，消息不泄漏请求内容，判定字段语义未变。
- 改动面严格限定 2 个文件；core 19 / runtime 119 / persistence 176 / server 30，源码与 XML 差额为 0，0 失败/错误/跳过；Architect 原反例 2/2 转绿，Lead 独立探针 2/2 通过。
- **Lead 初验通过，提交 Architect 独立复核**；B1 是否 DONE 由 Architect 裁定，**B2 在复核通过前不启动**。
- **Architect 裁定（2026-10-29）**：**B1 APPROVED / DONE**，N3 关闭，可启动 B2；复核记录见 [架构与技术复核](架构与技术复核.md) §7。

### 证据路径

- `.local/validation/cm2/lead/ct08-b1-n3/spotlessApply.log`
- `.local/validation/cm2/lead/ct08-b1-n3/core-runtime-test.log`、`core-runtime-test-final.log`
- `.local/validation/cm2/lead/ct08-b1-n3/persistence-server-test.log`
- `.local/validation/cm2/lead/ct08-b1-n3/probe-rerun.log`（Architect 探针重跑）
- `.local/validation/cm2/lead/ct08-b1-n3/lead-probe.log`、`probe/`（Lead 探针与其 init 脚本）
