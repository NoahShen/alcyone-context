# CT23 · Architect 裁定（D1～D18 与细项）

**日期**：2026-11-09
**状态**：**APPROVED：放行 CT23 B1（Topic Discovery 与批内归并组件）**
**裁定对象**：[CT23 任务书](CT23_任务书.md)、[方案核对](方案核对.md)（D1～D18 + §4 六细项）
**基线**：CT22 APPROVED / DONE；14 模块 **1916=1916**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 0. 本次修改

Architect 独立核实 F1～F15（`LlmTask` 枚举含 DREAMING_TOPIC/EVIDENCE/REFLECTION、Core 无 dreaming 包、CT22 processor/budget seam 形态、Extraction/Organization 先例、设计 §8.3 原文）后裁定 D1～D18 与六个细项。结论：**方案 APPROVED，放行 CT23 B1**。

## 1. 事实核实（Architect 独立）

- F2 ✓：`LlmClient.kt` L9-L18 确认 `DREAMING_TOPIC`/`DREAMING_EVIDENCE`/`DREAMING_REFLECTION` 已预置，无需改 enum。
- F1/F11 ✓：Core 无 `dreaming` 包；Prompt 资源仅 Extraction/Organization/Retrieval/Map；Schema 仅 `dreaming_checkpoint`，无 Topic 表。
- F4/F5 ✓：CT22 `DreamingBatchProcessor` 仅返回计数；`DreamingBatchBudget.requiredTotalFor` 在 planner 路径。
- 设计 §8.3 原文 ✓：`DiscoveredTopic` 四字段 camelCase（`sourceTurnIds: List<Long>`）；`topics: []` 合法；来源必须属当前输入批；重复可去重；单/无 Topic 跳过归并；任一必需调用失败整批失败。

## 2. 逐项裁定（D1～D18）

| # | 裁定 |
| --- | --- |
| D1 | **APPROVED**：单 B1 = Core DTO/Validator/Prompt/Schema + Runtime Parser/Stage/Budget/Processor adapter + Fake LLM/真实 SQLite 测试 |
| D2 | **APPROVED**：`DiscoveredTopic` 放 `context/core`（public 非 SDK）；四字段不可变、防御性复制、`toString` 脱敏 |
| D3 | **APPROVED**：JSON 字段 `topics/title/description/keywords/sourceTurnIds` camelCase |
| D4 | **APPROVED**：title/description blank 拒绝保原文；keywords 必填数组允许空数组、非空元素非 blank、稳定去重；sourceTurnIds 必填非空、Long 整数、稳定去重 |
| D5 | **APPROVED**：来源必须 ∈ 当前 Discovery 输入批（程序级校验，不依赖 Prompt）；未知/跨批/负数/类型错误整批拒绝 |
| D6 | **APPROVED**：顶层/Topic unknown 字段一律拒绝；禁止 topicId/confidence/generatedBy/checkpoint/createdAt 等系统字段 |
| D7 | **APPROVED**：四份独立 v1 资源；Discovery 六原则按任务书 §4.2 |
| D8 | **APPROVED**：Schema `additionalProperties=false`、四字段 required、topics 可空数组 |
| D9 | **APPROVED**：0/1 Topic 跳过 Consolidation；≥2 恰调用一次；不递归 |
| D10 | **APPROVED**：输入 N≥2 时输出 1..N；禁止空数组/扩张；保留模型顺序 |
| D11 | **APPROVED**：输出每个来源 ∈ 输入来源并集；输出并集 = 输入并集（不增不减） |
| D12 | **APPROVED**：Discovery 与 Consolidation 共用既有 `LlmTask.DREAMING_TOPIC`；EVIDENCE/REFLECTION 留 CT24/CT25 |
| D13 | **APPROVED**：构造期要求 DREAMING_TOPIC Task + 存在 Profile + JSON_SCHEMA/JSON_OBJECT，否则 `ContextConfigException(INVALID_CONFIG)`；JSON_SCHEMA 才发送并计 Schema |
| D14 | **APPROVED**：Discovery 单请求整批、Consolidation 单请求全部 Topic；均 LlmBudget 前置；budget adapter 与 Discovery 共用 requestFor；超限 0 调用 |
| D15 | **APPROVED**：新增 `DreamingTopicDiscoveryStage` + 薄 `DreamingTopicBatchProcessor` adapter；**不改 CT22 outcome/seam 语义** |
| D16 | **APPROVED**：INVALID_RESPONSE / OUTPUT_TRUNCATED / LOCAL_BUDGET_EXCEEDED / ContextConfigException / CE 原样；Coordinator 映射 BATCH_PROCESSING 带 cause |
| D17 | **APPROVED**：Core 纯 Validator/Prompt 测试；Runtime Parser/Stage Fake LLM；真实 SQLite 仅 Coordinator adapter 接线 |
| D18 | **APPROVED**：不接 Factory/Impl/AlcyoneContext；不改 Schema/Config/Loader/CT17～CT22/VFS/common/依赖/构建 |

## 3. 六细项明确裁定

| # | 细项 | 裁定 |
| --- | --- | --- |
| Q1 | keywords 空数组 | **合法**（模型可无关键词）；所有非空元素必须非 blank；空元素（空串/空白）拒绝；稳定去重 |
| Q2 | Consolidation 1..N + 来源并集 | **严格**：输出 Topic 数必须 1..N（N=Discovery Topic 数）；输出 sourceTurnIds 并集必须恰好等于输入并集——新增来源或丢失来源均整批拒绝 |
| Q3 | Discovery 渲染会话元数据 | **携带** runtime/agent/session/sessionTurnId/timestamp（支持跨会话规律判断）；**不携带** extractionStatus（Dreaming 不按状态过滤，渲染它无意义且有暗示风险）；messages 原文完整保留不截断 |
| Q4 | 双 seam | **确认**：`DreamingTopicDiscoveryStage`（返回完整 Topic 列表供 CT24）+ CT22 薄 processor adapter（计数/批尾/0 memories）；不修改 `DreamingBatchOutcome` |
| Q5 | 共用 DREAMING_TOPIC | **确认**：不新增 Task enum、不改 Config/Loader；两个请求用同一 Task/Profile/预算校验路径 |
| Q6 | camelCase | **确认**：`sourceTurnIds` 与设计 §8.3 DTO 一致（区别于 Extraction 的 `source_turn_ids` 属不同 Task 协议，各自独立契约） |

## 4. 附加边界

- **Prompt 原文必须写入交付报告核对**：六原则内容逐条断言（参照 CT10 B1-R1 教训——"只数编号不核内容"不通过）。
- Consolidation 失败矩阵必须包含：来源并集不等于输入并集（新增/丢失各一例）、输出 0 Topic、输出 N+1 Topic。
- Discovery 请求内容（messages 原文 + 会话元数据）不得进异常消息/日志。
- B1 白名单方向按方案核对 §7（15～22 文件），Lead 冻结精确清单。
- 真实 SQLite + Fake LLM 的 CT22 Coordinator 接线测试必须验证：Topic 失败 → partialResult 保持最后成功批水位、`DreamingRunException(BATCH_PROCESSING)` cause 链完整。

## 5. 结论

- **CT23 方案 APPROVED；D1～D18 与六细项全部裁定；放行 CT23 B1**。
- Lead 冻结 B1 委派单与白名单后派发 Worker（复用 `059d6c59-ce9e-4e68-9055-b45b238506a1`）。
- CT23 B1 复核通过才进入 CT24；CT24～CT26 LOCKED；CM6 未启动。
- 真实模型 not-run；无 Git 写操作；基线 1916=1916。
