# CT10 B1 · Lead 独立初验（配置 / Core 结果 / Prompt v1 / 纯预算分类）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**B1（含 R1）Lead 初验通过，提交 Architect 独立复核**；B2 在复核通过前不启动。
**依据**：[B1_委派单](B1_委派单.md)、[B1_R1修复委派单](B1_R1修复委派单.md)、[CT10 任务书](../../milestones/cm2/CT10_任务书.md) §0.1、[CT10 方案核对](CT10_方案核对与待裁定.md) §1.1

## 0. 本次修改（2026-10-30，B1 + R1 初验记录）

Worker 完成 B1（13 文件）后，Lead 初验发现 **B1-R1：Prompt v1 六原则覆盖不足**，退回 Worker 仅改 Prompt 资源与测试；修复后 Lead 重新独立验证。**B1-R1 已关闭，B1 初验通过**。

## 1. 交付面与边界

B1 新增/修改 13 文件；R1 仅改 2 文件。CT09 组件/测试 SHA 全部未变。

| 文件 | SHA-256（Lead 复算） |
| --- | --- |
| `core/.../extraction/ExtractionContext.kt` | `24d309e1…c4a18dba` |
| `core/.../extraction/ExtractionBuildResult.kt` | `5f15637c…01748159` |
| `core/.../extraction/PreExtractionContextBuilder.kt` | `6eb66e44…7e84f9` |
| `core/.../extraction/ExtractionPrompt.kt` | `efcf5158…d215e156` |
| `core/src/main/resources/prompts/extraction-v1.md` | `56d3278f…1849b2c8`（R1 后） |
| `core/src/main/resources/prompts/extraction-schema-v1.json` | `041e7916…b680d563b0` |
| `core/src/test/.../ExtractionPromptTest.kt` | `b96db43c…8f629d5`（R1 后） |
| `core/src/test/.../ExtractionContextResultTest.kt` | `824ab1dc…d99d7b44` |
| `runtime/.../extraction/ExtractionConfig.kt` | `a0a3e01b…94b5f6ec` |
| `runtime/.../ContextConfigLoader.kt` | `961ca51f…84a75f69` |
| `runtime/.../extraction/ExtractionBudgetPolicy.kt` | `5253be34…fa74ef24` |
| `runtime/src/test/.../ExtractionBudgetPolicyTest.kt` | `b622cc68…93030f652` |
| `runtime/src/test/.../ExtractionConfigValidationTest.kt` | `c4e9a572…b6d15eadb` |

- 未变：Scheduler `4fe97700…`、IdleCron `a137f37c…`、IdleTrigger `cdd63513…`、CountTrigger `4dc86afe…`、Pipeline `9b898911…`、Impl `71bcefae…`、Factory `1f996ce9…`、StoredConversationTurn `791167a7…`、LlmClient `77b1f7aa…`、LlmBudget `f1887e2a…`、Schema `ec727a53…`。
- `spotlessApply` 后 SHA 不变；未改 SQL/索引/构建/依赖/vfs/common/persistence/server；无 git 操作。

## 2. Lead 独立实跑与计数

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| 探针后恢复正式 runtime | BUILD SUCCESSFUL |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 34 | 34 | 0 | 0/0/0 |
| runtime | 265 | 265 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

增量：core 19→34（+15）、runtime 258→265（+7）；R1 仅强化内容/断言，用例数不变。

## 3. 契约核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| `ExtractionContext` 防御性复制、不可修改、无 target/history 字段、toString 不展开正文 | 源码 + Core 用例 + Lead 探针 | ✔ |
| `ExtractionBuildResult` 四态、字段名固定、不携带 Prompt/正文 | 源码 + Core 用例 | ✔ |
| `PreExtractionContextBuilder.build(): ExtractionBuildResult` public 非 api；B1 无实现 | 源码 | ✔ |
| Prompt 资源 v1、版本常量；缺失/空白明确失败 | 源码 + 用例 | ✔ |
| `messages` = system + user；空列表 user 空串；Turn 顺序/id/`+08:00` 时间/target 三态/角色正文原样 | 用例 + Lead 探针 | ✔ |
| 六原则内容（价值/忠实/完整原子化/重要变化/批内去冗余/证据） | R1 后 Prompt + 强化断言（逐条内容，不只编号） | ✔ |
| Target/History、时间表达、JSON 契约、无 Key/URL | Prompt 内容 + 用例 | ✔ |
| Schema v1 `memories/content/source_turn_ids`、`additionalProperties:false`、无系统字段 | 资源 + 用例 | ✔ |
| `initialWindowTurns` 默认 32、1..1024、直接构造/YAML 同校验、未知键拒绝 | Config + Loader + 用例 | ✔ |
| 纯预算分类：等于边界 Fits、输入/容量 Over(requiredTotal)、`nextWindowSize=max(1,current/2)`、不调 LLM/DB | `ExtractionBudgetPolicy` + 用例 + Lead 探针 | ✔ |
| 无 Builder/DB/折半循环/pipeline/CT11+ | 白名单审查 | ✔ |

## 4. Lead 独立探针（3/3 PASS）

`.local/validation/cm2/lead/ct10-b1/probe/LeadCt10B1ProbeTest.kt`（`lead-probe-results.xml`，`tests=3 failures=0 errors=0`）：

1. Prompt 六原则标题齐全；Turn target true/false 与 `+08:00` 毫秒时间戳正确。
2. `ExtractionContext` 构造后修改原列表不影响快照；`turns` 不可修改。
3. 预算分类等号边界 Fits、`Over(requiredTotal)` 透传、`nextWindowSize(1/10)` 正确。

## 5. B1-R1 关闭

- 原缺陷：Prompt 仅编号覆盖部分原则；测试只数编号。
- 修复：`extraction-v1.md` 增加六原则子节与必要原因/条件/时间、重要变化、批内去冗余；`ExtractionPromptTest` 逐条断言内容；用例数不变。
- 复核：R1 后 SHA 与 Worker 一致；三条命令全绿；六原则内容与断言逐项核对通过。

## 6. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | Worker 一次全量运行中 CT09 既有 `ExtractionPipelineLifecycleTest > disabling idle...` 10s 超时失败一次；单独重跑、连续 3 次全量均绿；Lead 单独重跑该类 5/5 通过 | 时序敏感性观察，非 B1 改动；记录供 Architect 评估，B1 不修改 CT09 测试 |
| L2 | Prompt 渲染不做 `</turn>` 转义（D12 固定格式） | 按裁定格式；如后续安全评审需要再统一 |
| L3 | `ExtractionBudgetPolicy.Over.requiredTotalTokens` 在仅输入超限时也返回 requiredTotal（含输出/余量） | 符合 D5 字段语义 |
| L4 | `ExtractionPrompt.systemPrompt()` 每次读取资源 | 测试/配置期调用，成本可忽略 |

## 7. 结论与下一步

- B1 配置、Core 结果/Context/Prompt v1/Schema、纯预算分类均按 D1～D13 落实；B1-R1 已关闭；core 34/34、runtime 265/265、persistence 176/176、server 30/30。
- **B1 Lead 初验通过，提交 Architect 独立复核**；B2 在复核通过前不启动，CT11/CT12 不启动。

### 证据路径

- `.local/validation/cm2/lead/ct10-b1/spotlessApply.log`、`core-runtime-test.log`、`core-runtime-test-restored.log`、`persistence-server-test.log`
- `.local/validation/cm2/lead/ct10-b1/lead-probe.log`、`lead-probe-results.xml`、`probe/`
- Worker：`.local/validation/cm2/worker/ct10-b1/`、`ct10-b1-r1/`
