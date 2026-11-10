# CT24 B1（含 R1）· Architect 复核

**日期**：2026-11-09
**状态**：**B1（含 R1）APPROVED / DONE**
**复核对象**：[B1 Worker 交付](B1_Worker交付.md)、Lead 验收复核、Worker `059d6c59-ce9e-4e68-9055-b45b238506a1`
**依据**：[Architect 裁定](Architect_裁定.md) D1～D20 + Q1～Q8、[B1 委派单](B1_委派单.md)
**基线**：B1 前 14 模块 **1971=1971**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 0. 本次修改

Architect 独立复核 CT24 B1（含 R1）：17/17 文件 SHA 复算一致（含 2 个 R1 标注文件）、Novelty Validator/Collector 代码级审查、R1 任务归属逐行核对、定向与多次全量实跑、CT23/CT22/CT20/CT18 SHA 回归复验。结论 **B1（含 R1）APPROVED / DONE**。

## 1. 独立证据

| 项 | 结果 |
| --- | --- |
| 17/17 文件 SHA | Core 生产/资源 6 + Core 测试 2 + Runtime 生产 5（含 R1 的 Collector）+ Runtime 测试 4（含 R1 测试），全部一致 |
| 定向 | `:context:core:test :context:runtime:test --rerun-tasks` exit 0（47s，27 tasks） |
| 全量 `./scripts/check` | Architect 共 4 次实跑：**3 次通过**（2m14s、2m16s、131/131 ×2）+ **1 次中途失败**（1m38s、113 tasks，重跑即绿，无持久失败 XML）——详见 §4 观察 |
| 14 模块 source @Test = XML | **2020=2020**；基线 1971 + 49（Core +13、Runtime +36，R1 +4） |
| Schema | `ec727a53…8ea3` 未变 |
| CT23 回归 | 21/21 SHA 未变 |
| CT22 回归 | 14/14 SHA 未变 |
| 白名单外 | 非 docs 变更 = CT22 3 文件（既有）+ CT22/CT23/CT24 dreaming 新增；`sessionWindowAfter` 未出现在 B1（正确归 B2） |

## 2. 代码级审查（对照裁定逐项）

| 裁定 | 复核结果 |
| --- | --- |
| D8 Novelty | `DreamingNoveltyDecision` 两态；NEEDS 空 coveredOrganizedIds、FULLY 非空且属 relevant 候选、稳定去重（Validator 测试 5 例覆盖）。通过 |
| D6/D10/D12 快照 | `collect` 首个 `database.read` 分页读全量 Organized+Raw（L96-L122）；每个命中 Raw 单独 read `findOrganizedByRaw` → `findByIds` 全量回读（L265-L268）；反查缺失整批失败；无关联 Raw 保留。通过 |
| D7 复用 | Organized scanner 用 `binding.memorySearchTask`；复用 CT20 Prompt/Parser/IDValidator/Planner/Budget。通过 |
| R1 任务归属 | 逐行核对：Organized 截断/解析/ID 失败携带 `LlmTask.MEMORY_SEARCH` + memorySearchProfile（L158/L165/L173）；Novelty/Raw 失败携带 `DREAMING_EVIDENCE` + evidenceProfile（L194-L246）；无 cause、无正文泄漏。通过 |
| Q8 | Raw 无关联 Organized 保留为历史候选，Raw Prompt 明示"未关联任何 Organized…不能自动当作当前事实"（Prompt 契约测试断言）。通过 |
| §4 附加边界 | Novelty/Raw Prompt 核心契约逐片段断言（数据非指令/相关≠无增量/全历史双 origin/冲突纠错/无系统字段——§5 表 7 项）；FULLY_COVERED 语义责任边界已写入交付报告 §10。通过 |
| 失败原子性 | 任一批 Parser/ID/预算/截断/反查失败立即抛出、无部分 Candidate；多 Topic 前失败后 0 后续请求。通过 |

## 3. 测试矩阵抽查

- 空快照 0 LLM、FULLY 跳过 Raw、Raw 双 origin、ID 批外拒绝、预算等号/Schema tokens 差异、JSON 模式双 Task 断言、真实 SQLite source link 反查——报告 §4 矩阵与测试类对应（49 例：Novelty 5/Prompt 8/Parser 6/Collector 27/RealDb 3）。

## 4. 观察（不阻断，但升级跟踪）

- **ExtractionPipelineLifecycleTest 时序抖动频率上升**：该观察自 CT10 起持续记录（CT15/CT22/CT23/CT24 各出现一次），本次 Architect 独立复核 4 次全量中 1 次中途失败（113 tasks，重跑绿、无持久失败 XML，与历史模式一致）。该测试属 CT09 产出、不在任何 CM5 白名单。
- **裁定：继续不阻断 CT24 B1**（模式与历史一致、复跑稳定、非 CT24 代码引入）；但**建议 Lead 在 CM5 收口后在下一个授权窗口安排专项调查/修复任务**（提高超时阈值或确定性化 trigger），避免长期回归成本累积。
- 语义质量（Novelty"确实充分覆盖"、证据相关性）：Fake LLM 仅证明协议/权限/原子性，责任边界在 Prompt+CT26 评测（交付报告 §10 已明确）。

## 5. 结论

**CT24 B1（含 R1）APPROVED / DONE。**

- 18 文件白名单齐备；17/17 SHA 一致；白名单外变更 0；零 Schema/Config/Factory/Impl/CT17～CT23 修改。
- 14 模块 **2020=2020**；A01～A08（B1 部分）覆盖；Prompt 契约内容级断言；CT23 21/21、CT22 14/14 回归未变。
- **放行 CT24 B2（Conversation 层取证与证据包）**：Conversation Need（memoryIds）、RawMemoryRef 链（cycle fail-closed）、ConversationTurnRef 四元定位、只读 `sessionWindowAfter`（D17 已批准，含 Repository/测试）、同 Session 前 1/后 1 邻居、同 Turn 多 Raw 去重、`DreamingTopicEvidence`/`DreamingBatchEvidence` DTO、CT23 组合集成。
- CT24 B2 复核通过才进入 CT25；CT25～CT26 LOCKED；真实模型 not-run；无 Git 写操作。
