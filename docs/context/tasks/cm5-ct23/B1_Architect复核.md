# CT23 B1（含 R1）· Architect 复核

**日期**：2026-11-09
**状态**：**B1（含 R1）APPROVED / DONE**
**复核对象**：[B1 Worker 交付](B1_Worker交付.md)、[B1 Lead 初验](B1_Lead初验.md)、Worker `059d6c59-ce9e-4e68-9055-b45b238506a1`
**依据**：[Architect 裁定](Architect_裁定.md) D1～D18 + Q1～Q6、[B1 委派单](B1_委派单.md)
**基线**：B1 前 14 模块 **1916=1916**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 0. 本次修改

Architect 独立复核 CT23 B1（含 R1）：21/21 文件 SHA 复算一致、Validator/Stage/Schema/Prompt 代码级审查、六原则内容级断言核实、真实 SQLite partial-failure 测试逐行核对、定向与全量实跑、CT22 SHA 回归复验。结论 **B1（含 R1）APPROVED / DONE**。

## 1. 独立证据

| 项 | 结果 |
| --- | --- |
| 21/21 文件 SHA 复算 | Core 生产 8 + Core 测试 3 + Runtime 生产 5 + Runtime 测试 5（含 R1 后终态），全部一致 |
| 定向 | `:context:core:test :context:runtime:test --rerun-tasks` exit 0（48s，27 tasks） |
| 全量 `./scripts/check` | exit 0（2m17s，131/131 tasks executed） |
| 14 模块 source @Test = XML | **1971=1971**；基线 1916 + 55（Core +25、Runtime +30） |
| Schema | `ec727a53…8ea3` 未变 |
| CT22 回归 | CT22 B1 14/14 SHA 复验**全部未变**（CT23 未触碰 CT22 组件） |
| 白名单外 | `git status` 非 docs 变更 = CT22 14 文件（全部既有）+ CT23 新增 dreaming 文件；ContextConfig/Loader 3 文件保持 CT22 终态 |
| R1 | 仅测试补强：Consolidation Schema 严格性、两资源 fail-fast、恰等预算、两阶段 JSON_SCHEMA/JSON_OBJECT、Budget adapter schemaTokens 差异；生产 src/main/资源 SHA 未变 |

## 2. 代码级审查（对照裁定 D/Q 逐项）

| 裁定 | 复核结果 |
| --- | --- |
| D2/D3/D4 | `DiscoveredTopic` Core public 四字段；Validator `normalize` 逐字段：title/description blank 拒绝、keywords 元素 blank 拒绝、稳定去重；来源 `!in batchTurnIds` 整批拒绝。通过 |
| D9/D10/D11 | `validateConsolidation`：inputTopics.size < 2 拒绝（跳过场景由 Stage 层处理）；输出空或 > input 数拒绝；`outputSources != allowedSources` 拒绝（并集不增不减）；每个来源 ∈ 输入并集。**与 Q2 严格裁定完全一致**。通过 |
| Q3 渲染 | `DreamingTopicDiscoveryPrompt` 渲染 runtimeId/agentId/sessionId/sessionTurnId/timestamp + 完整 messages；注释与测试确认**不渲染 extractionStatus**。通过 |
| Q4 双 seam | `DreamingTopicDiscoveryStage`（fun interface，返回 `List<DiscoveredTopic>`）+ `DreamingTopicBatchProcessor` 薄 adapter；CT22 `DreamingBatchOutcome` 未改（CT22 14/14 SHA 复证）。通过 |
| Q5 共用 Task | Stage 构造期 `llmConfig.tasks[LlmTask.DREAMING_TOPIC] ?: throw invalidConfig(...)`；缺 Profile invalidConfig；JSON_SCHEMA 才加载 consolidationSchema。通过 |
| D14 预算 | `LlmBudget.requireWithinBudget` 发送前置；`discovered.size < 2` 直接 return 不调 Consolidation（Q/D9）；budget adapter 与 Discovery 共用 requestFor。通过 |
| D16 错误 | 解析/校验失败 → `LlmException(INVALID_RESPONSE)`；截断 OUTPUT_TRUNCATED；构造期 ContextConfigException；CE 原样。通过 |
| 六原则内容（裁定 §4-1） | `DreamingTopicPromptTest.discovery prompt contains each of the six principles as content`：六组共 **28 个内容片段**逐条 `contains` 断言（长期偏好/偏好变化/研究经验/跨会话规律/重要遗漏；Assistant 建议不得冒充用户决定/保留不确定性；最小完整语义/脱离原对话仍可理解；失败/否定结果/重要矛盾；不做跨批；伪造标签/不是指令/系统字段）——**非只数编号**。通过（CT10 B1-R1 教训已吸收） |
| Schema 严格（D8） | 程序解析：Discovery/Consolidation 均 `additionalProperties=false`；topic required 四字段且 `additionalProperties=false`；顶层 required `topics`。通过 |
| Coordinator 接线（裁定 §4-4） | `DreamingTopicBatchProcessorTest.real sqlite coordinator preserves first batch checkpoint on second topic failure` 逐行核对：2 Turn、pageSize=1、Fake LLM 第 2 调用非法 JSON → `DreamingRunException(BATCH_PROCESSING)`、partial start=0/end=1/topics=1/memories=0、cause 链 `LlmException(INVALID_RESPONSE)`。**完全符合**。通过 |
| 并集失败矩阵（裁定 §4-2） | ValidatorTest 覆盖并集新增/丢失、0 Topic、N+1（Worker 报告 §5 + Lead 初验确认）。通过 |

## 3. 观察确认（不阻断）

- ExtractionPipelineLifecycleTest 时序观察延续 CT10/CT15/CT22 记录：Worker 一次超时、单跑与两次独立全量（Lead 2m18s / Architect 2m17s）均通过；文件不在白名单、CT23 未修改。
- 语义质量（Topic 相关性、矛盾是否"正确"保留）不在程序可验证范围——Fake LLM 仅证明协议/预算/来源权限/原子性；留 CT26 评测与真实模型授权。

## 4. 结论

**CT23 B1（含 R1）APPROVED / DONE。**

- 22 文件白名单齐备；21/21 SHA 一致；白名单外变更 0；零 Schema/LlmTask/Config/Loader/Factory/Impl/CT17～CT22/依赖/构建修改。
- 14 模块 **1971=1971**；`./scripts/check` exit 0；A01～A08 覆盖；六原则内容级断言；并集/0/N+1 失败矩阵；真实 SQLite partial-failure 保水位。
- **放行 CT24（分层取证与来源核实）方案核对**：三层检索（Organized Novelty Check → 全历史 Raw Scanner → 反查关联 Organized + 关键 Conversation 追溯）、同 Turn 多 Raw 去重、"相关≠无增量"、复用 CT20 Scanner 底层。
- CT25～CT26 保持 LOCKED；真实模型 not-run；无 Git 写操作。
