# CT08 B1 Lead 独立初验（配置/预算骨架）

**日期**：2026-10-29
**初验人**：CM2 Lead（只读初验，不写生产代码）
**提交对象**：Architect（B1 是否通过由 Architect 独立复核裁定；Lead 不自标 DONE）
**依据**：[CT08 方案](CT08_方案.md) §m（B1 边界）/§a/§b/§l、[CT08 任务书](../../milestones/cm2/CT08_任务书.md) v3、Worker B1 交付（message `9bc21a89`）

## 0. 本次修改（2026-10-29，B1 初验记录建立）

Worker `d7695b22` 完成 B1 并交付；Lead 独立执行只读初验（复算 SHA、独立跑测试与格式检查、逐条核对 §m 边界与 §a 规则），结论见 §6。

## 1. 交付面与边界核对

改动共 12 个文件（`find -newermt` 独立核实，近 40 分钟仅此 12 个 `.kt`，无其他源码漂移）：

| 文件 | 性质 | Lead 复算 SHA-256（与 Worker 一致） |
| --- | --- | --- |
| `context/core/.../core/llm/LlmErrors.kt` | 新增 | `59d411d2…41d3e835` |
| `context/core/.../core/llm/ConservativeTokenEstimator.kt` | 新增 | `7dcaabc9…52b61bfef` |
| `context/core/src/test/.../llm/ConservativeTokenEstimatorTest.kt` | 新增 | `c4c49bcc…5b5a7752fd` |
| `context/runtime/.../runtime/llm/LlmConfig.kt` | 新增 | `5f3674e5…636cc57dff` |
| `context/runtime/.../runtime/llm/LlmBudget.kt` | 新增 | `c6f033b2…0838e51e0c73` |
| `context/runtime/.../runtime/ContextConfig.kt` | 改（`+llm`） | `6d2eea66…125e1113c8` |
| `context/runtime/.../runtime/ContextConfigLoader.kt` | 改（`+parseLlm`） | `40d8eec4…2a8aa876ea96` |
| `context/runtime/.../runtime/AlcyoneContextFactory.kt` | 改（`ownedCopy`） | `d0f3a17d…7b7e725c81c9cc` |
| `context/runtime/.../runtime/AlcyoneContextImpl.kt` | 改（`internal val llmConfig`） | `85b6b6a3…54f896ccd1` |
| `context/runtime/src/test/.../llm/LlmConfigValidationTest.kt` | 新增 | `e258cb5c…6e2e63366` |
| `context/runtime/src/test/.../llm/LlmBudgetTest.kt` | 新增 | `38f91d90…d2169696ea` |
| `context/runtime/src/test/.../ContextConfigLoaderTest.kt` | 定点改 1 例 | `4fe3333c…2f8c41cf0` |

边界核对（逐项现场取证）：
- `context/core/build.gradle.kts` 无 ktor、无新依赖；`grep -rn ktor context/core/` 仅命中既有 `CoreSkeletonTest` 的「不得存在」断言。
- `LlmClient.kt` **未**新增 `schema` 字段（`grep schema` 空）→ 符合 §m「B1 不抢做请求协议」。
- 无 HTTP/Ktor 代码、无 `HttpClient`；`AlcyoneContextImpl.start()/close()` 未新增任何 LLM 调用或顺序变化（仅构造参数保存）。
- `git status --porcelain vfs common integration-tests/vfs`：`vfs/` 与 `integration-tests/vfs/` 干净；`common/` 仅 CM1 遗留的 4 个未跟踪文件（mtime 2026-10-26，本批未触碰）。
- 既有 `ContextConfigLoaderTest` 仅按 §m 允许范围定点更新 1 例（原「llm 接受且不消费」断言改为 `assertNull(config.llm)`），其余既有用例未改。

## 2. Lead 独立实跑（非沿用 Worker 结果）

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | `BUILD SUCCESSFUL`，26 tasks executed |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test :integration-tests:context:test --rerun-tasks --console=plain` | `BUILD SUCCESSFUL`，31 tasks executed |
| `./scripts/dev gradle :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | `BUILD SUCCESSFUL`，9 tasks executed |

计数（`@Test([^A-Za-z]|$)` 行首精确统计 vs XML `tests`，Lead 复算）：

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | --- | --- | --- | --- |
| context:core | 19 | 19 | 0 | 0/0/0 |
| context:runtime | 114 | 114 | 0 | 0/0/0 |
| context:persistence-sqldelight | — | 176 | — | 0/0/0 |
| context:server | — | 30 | — | 0/0/0 |
| integration-tests:context | — | 15 | — | 0/0/0 |

增量与基线一致：core 11 → 19（+8）、runtime 89 → 114（+25）。

## 3. 逐条核对 §a 校验规则（A02）

| 规则 | 实现位置 | 初验结论 |
| --- | --- | --- |
| 未知字段（llm / profile / task 三层） | `CONTEXT_LLM_KEYS`(63)、`CONTEXT_LLM_PROFILE_KEYS`(64)、`CONTEXT_LLM_TASK_KEYS`(76) + `rejectUnknown` | 路径完整（`context.llm.tasks.EXTRACTION.temperature` 等），码 `UNKNOWN_FIELD` ✔ |
| `profiles` 缺失/为空/非映射 | `parseLlm`(258-262) | `INVALID_CONFIG` ✔ |
| Profile 名空白 | `parseLlm`(263) + `validateLlm` | ✔ |
| 必填缺失/空白 | `parseLlmProfile` + `validateLlm` | ✔ |
| `baseUrl` 非 http(s)/含 userinfo/query/fragment | `requireHttpUrlWithoutCredentials` | 同一条不含值消息，URI 解析失败亦脱敏 ✔ |
| `apiKey` ENV 未定义/空白 | 复用 `expandEnv` | `MISSING_ENV`，消息含路径+ENV 名、无值 ✔ |
| `jsonMode` 非枚举 | `parseJsonMode` | 大小写不敏感，列出三值，不回显原值 ✔ |
| 数值范围（8 个字段 + margin） | `requireInRange` / `validateLlm` | 路径 + 区间，不回显原值 ✔ |
| 未知 Task / 重复 Task（大小写归一） | `parseLlm`(268-280) | ✔ |
| Task → 未知 Profile | `validateLlm` | ✔ |
| 静态预算自洽 | `validateLlm`（`Long` 相加） | ✔ |
| 未配置 `context.llm` | `parseLlm` 返回 null | `ContextConfig.llm == null`，且用例断言**不读取任何 ENV** ✔ |

## 4. 逐条核对 §b/§l（预算与估算）

- `estimateText`：按 **Unicode 码点**（代理对算 1）、ASCII `ceil/3`、非 ASCII ×3、空串 0、超长饱和 `Int.MAX_VALUE`；`estimatePrompt` = `Σ(role + content) + 8n + 16`（**按实际 role 字符串**，有专项用例 `prompt estimation uses the actual role string instead of a fixed role cost`）；`estimateSchema(null)=0` ✔。
- 预算：输入预算 + 容量两条件；`margin = ceil(input*percent/100)` 只在容量条件计一次；`Long` 饱和加法/乘法，专项用例 `int overflow cannot bypass the limits` 覆盖两个 `Int.MAX_VALUE`（`estimatedInput = 2×Int.MAX_VALUE` 不回绕）✔。
- `effectiveMaxOutputTokens` = `min(request ?: task, task)`，`<1` → `INVALID_REQUEST`；`margin` 只依赖输入 ✔（§l 2/3）。
- 错误码：`LOCAL_BUDGET_EXCEEDED` 与 `CONTEXT_LIMIT_EXCEEDED` 并列存在且语义分开；`LlmException` 继承 `api.ContextException`，只带 task/profile/model/statusCode/attempts，`toString` 重写 ✔（§l 1、§i 4）。
- `LlmConfig`：构造即取 `Collections.unmodifiableMap` 自有副本并 `init { validateLlm }`；`ownedCopy()` 再复制再校验；YAML 与直接 Kotlin 构造**同一 `validateLlm`**（用例 `direct kotlin llm config uses the same validation and owns its snapshot` 证明入参集合被清空后实例不变）✔（§i 1 / §m B1）。
- 脱敏：`LlmProfileConfig.toString` 恒输出 `apiKey=<redacted>`、`baseUrl=scheme://host/…`；用例以独特标记 `ct08-b1-unique-key-marker-51c7` 断言 `profile`/`llm`/`ContextConfig` 的 `toString`、失败消息、`stackTraceToString` 均无标记 ✔（A02 后半、A08 脱敏面）。

## 5. 复查中提出的观察项（不阻塞 B1 放行，交 Architect 裁定）

| # | 观察 | 影响 | Lead 建议 |
| --- | --- | --- | --- |
| N1 | `AlcyoneContextImpl` 新增 `internal val llmConfig`，B1 内**未被使用**（仅保存快照，B4 才用） | 1 行内部字段，无行为变化；严格按「B1 无生命周期接线」可视为提前占位 | 建议保留（B4 直接使用，减少二次改动）；若要求「本批零死代码」则移除、B4 再加 |
| N2 | 规范允许「`tasks` 为空但 `profiles` 非空」，当前无正向用例 | 覆盖缺口，无实现风险 | B2 的「无路由 → `CONFIGURATION`」用例顺带覆盖空 `tasks` 的正向解析 |
| N3 | `LlmBudget.estimate` 直接信任 `TokenEstimator` 返回值（自定义估算器返回负数时可能绕过下限） | 仅测试替身可能触发；生产固定使用 `ConservativeTokenEstimator` | 可选加固：`estimate* < 0 → 视为 0` 或 `INVALID_ARGUMENT`，留 B2 一并处理 |
| N4 | `parseJsonMode` 对 `jsonMode` 也走 `${ENV}` 展开 | 与「枚举字面量」语义略有出入，无安全影响 | 不必改；如改，B2 顺手收紧 |

## 6. Lead 初验结论

- **B1 交付完整、边界干净、可复现**：12 个文件、SHA 与 Worker 一致、无越界改动、无 `vfs/common` 漂移、无新增依赖、core 仍无 Ktor、未抢做 `schema`/HTTP/生命周期。
- **独立实跑全绿**：core 19 / runtime 114（源码=XML 差额 0）；persistence 176 / server 30 / integration 15 全绿；spotlessCheck 通过。
- **A02 全规则**与 **A07 的 Estimator/预算部分** 已具备可核对证据；A01 配置侧（`llm == null` 且不读 ENV）通过。
- 结论：**提交 Architect 独立复核**（Lead 不自标 DONE）。上述 N1～N4 为观察项，其中 N1 需 Architect 明确「保留/移除」；若裁定移除，Lead 立即委派 Worker 做单点回退并重跑三组定向测试。

## 7. 状态回传（2026-10-29 21:20 CST，Architect 询问期间核实）

- **Architect 裁定**：N1 **保留**（`llmConfig` 快照供 B4 使用，不回退死代码）；N3 **不得随手 clamp 负估算**（不得掩盖错误），待 Architect 复核裁定，Lead 收回原建议。
- **B1 状态不变**：实现完成、Lead 初验通过、**现进入 Architect 独立复核**；代码树自本记录 19:57:48 CST 落盘后**零改动**（`find` 20:00 CST 之后无任何 `.kt`/`.md`/`.kts` 变化，12 个文件 SHA 仍与本记录一致）。
- **Worker 归档事实（只读核实，不做任何恢复动作）**：`d7695b22` 于 `2026-10-29T13:14:01Z` 被归档，来源为 **Paseo 桌面端人工操作**（`~/.paseo/daemon.log:2615`：`origin=paseo://app`、`archive_agent_request`、loopback 直连；同批 8c00d30e 于 13:13:53Z 被归档），**不是**模型失效、不是被 Lead 归档、不是任务中断（B1 早在 11:55Z 已交付）。13:16:22Z 出现 `Agent resumed from persistence`，是守护进程为回答状态/活动查询而加载已持久化的 pi 会话；当前 `archivedAt` 仍非空、`status=idle`。
- **能否原会话恢复**：会话文件与服务端会话持久化都在（`~/.pi/agent/sessions/.../2026-10-28T09-55-15-529Z_01a181ef-…jsonl`），技术上可恢复；但**现有工具没有 unarchive**（只有软删除的 archive 与永久 kill），恢复需由用户在 Paseo 应用内取消归档，或在获授权后新建 Agent 复用同一 session —— 两者 Lead 均不自行执行。
- **是否有新写入者**：无。工作区内近 8 小时仅 Lead 自身为 `running`；其余为历史 `closed`/已归档 Agent；B1 之后无任何源码/文档改动。
