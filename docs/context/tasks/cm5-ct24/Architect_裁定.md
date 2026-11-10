# CT24 · Architect 裁定（D1～D20 与细项）

**日期**：2026-11-09
**状态**：**APPROVED：放行 CT24 B1（拆分后第一批）/ B2**
**裁定对象**：[CT24 任务书](CT24_任务书.md)、[方案核对](方案核对.md)（D1～D20 + §4 八细项）
**基线**：CT23 APPROVED / DONE；14 模块 **1971=1971**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 0. 本次修改

Architect 独立核实 F1～F15（`findOrganizedByRaw`、`sessionWindow` 仅 backward、`RawMemory.sources` 双 Ref 类型、`scanPage` 双 origin 全量、`idx_turn_session_time` 索引存在、CT20 Scanner 可复用性、设计 §8.4 原文）后裁定 D1～D20 与八细项。结论：**方案 APPROVED；D2 拆分 B1/B2 获批；D17 批准新增只读 `sessionWindowAfter`（零 Schema/DML/迁移）**。

## 1. 事实核实（Architect 独立）

- F5 ✓：`findOrganizedByRaw(rawId): List<String>` 存在，按 organized id ASC。
- F7/F8 ✓：`sessionWindow` 仅支持锚点向前（`timestamp < ? OR (timestamp = ? AND id <= ?)` 降序）与最新窗口；**无 forward 查询**；全局 `idRange` 相邻会跨 Session。
- F4 ✓：`MemorySourceRef` 含 `ConversationTurnRef`（四元业务键）与 `RawMemoryRef`；`RawMemory.sources` 防御性复制。
- F9/F10 ✓：`scanPage` 按 `(created_at, id)` 全量分页，无 origin 过滤；`organized_memory_source` 为既有权威关联表。
- F3 ✓：`MemorySearchService` 硬编码 Organized 快照+Rerank，不能直接复用为 Raw scanner——复用其底层 Prompt/Parser/IDValidator/Planner 是正确拆法。
- 索引 ✓：`idx_turn_session_time` 已覆盖 (runtime, agent, session, timestamp, id)；forward 窗口查询无需新索引。
- 设计 §8.4 ✓：三层定义、反查全部关联 Organized、同 Turn 多 Raw 非独立证据、"相关不等于无增量"原文一致。

## 2. 逐项裁定（D1～D20）

| # | 裁定 |
| --- | --- |
| D1 | **APPROVED**：Core Novelty DTO/Validator + 3 Prompt/Schema；Runtime 两 scanner、Novelty、Conversation trace、Evidence collector；不接生产 |
| D2 | **APPROVED**：**B1/B2 拆分**——B1 = Organized+Raw 全量扫描+Novelty+反查合并；B2 = Conversation Need/Ref 链追溯/邻居/证据包+CT23 组合集成。B1 复核通过才派发 B2 |
| D3 | **APPROVED**：Organized scanner 用 `MEMORY_SEARCH`；Novelty/Raw/Conversation 三 Prompt 共用 `DREAMING_EVIDENCE` |
| D4 | **APPROVED**：两 Task/Profile/JSON 模式构造期校验，缺失 INVALID_CONFIG |
| D5 | **APPROVED**：三个独立 DREAMING_EVIDENCE v1 资源；Organized scanner 复用 memory-search v1 |
| D6 | **APPROVED**：每批一次 read 全量 Organized 快照；不 Agent/Domain 过滤 |
| D7 | **APPROVED**：复用 CT20 Prompt/Parser/`MemorySearchIdValidator`/Planner/Budget；批内/跨批 ID 整批失败 |
| D8 | **APPROVED**：Novelty 输出 `decision + coveredOrganizedIds`；NEEDS 时空、FULLY 时非空候选子集；无候选直接 NEEDS |
| D9 | **APPROVED**：相关 Organized 仅进候选**绝不自动 skip**；FULLY_COVERED 必须模型显式判定；Prompt 明定"确认无新增/限定/变化/纠错/矛盾"才可 FULLY |
| D10 | **APPROVED**：NEEDS 时读取完整 Raw 快照（不过滤 origin）；每 Topic 全量分批扫描 |
| D11 | **APPROVED**：Raw 输出 `candidate_ids`；批内权限、稳定去重、`(createdAt,id)` 快照顺序；任一批失败整体失败 |
| D12 | **APPROVED**：每个命中 Raw 反查全部关联 Organized 并回读，与第一层命中合并去重；反查 ID 缺失 fail |
| D13 | **APPROVED**：证据 DTO 分离 organized/raw/conversation；Prompt 明示未关联 Raw 的 Organized 不是当前事实 |
| D14 | **APPROVED**：Conversation Need 单请求严格 `memoryIds`（Raw candidate 子集）；可选空 |
| D15 | **APPROVED**：RawMemoryRef 递归解析；重复稳定去重、菱形可接受；链中 Raw 全纳入 |
| D16 | **APPROVED**：ConversationTurnRef 四元 business key 定位（`findByBusinessKey`）；不可定位 fail；内部 id 仅本轮工作值不持久化 |
| D17 | **APPROVED**：**批准新增只读 `sessionWindowAfter`**（参数化 SELECT + ReadOps/Repository + 测试）；零 Schema/DML/迁移/索引（复用 `idx_turn_session_time`）；按 `(timestamp,id)` 升序 forward，锚点排除自身 |
| D18 | **APPROVED**：Raw/Organized by id 去重，Conversation by business key；同 Turn 多 Raw 仅一份 Turn；DTO 分离 new/historical turns |
| D19 | **APPROVED**：全部 LlmBudget 前置；超预算/截断/非法/来源不可定位整批失败保水位；CE 原样 |
| D20 | **APPROVED**：如 D17 所述，只读能力补口；测试须含 SQL 边界（首尾 Turn、同 timestamp id tie-break、不跨 Session） |

## 3. 八细项明确裁定

| # | 细项 | 裁定 |
| --- | --- | --- |
| Q1 | B1/B2 拆分 | **批准**。B1（Organized+Raw+Novelty+反查）复核通过才派发 B2（Conversation+证据包+组合）；两批各自白名单/SHA/初验/复核 |
| Q2 | `sessionWindowAfter` | **批准新增**（只读、参数化、零 DDL）。替代方案（放大 backward window / 全 Session 读入内存）均拒绝——前者读取过量，后者违反最小化读取 |
| Q3 | Novelty JSON | **确认** `decision + coveredOrganizedIds`；`FULLY_COVERED` 必须非空且为候选子集；NEEDS 必须空 |
| Q4 | Conversation Need 输出 | **确认 `memoryIds`**（选择待追溯 Raw）——模型不直接选 Turn，Turn 定位由程序经 source 链完成，保证权限边界 |
| Q5 | 相邻半径 | **固定前 1/后 1，不新增配置**；锚点 Turn 本身已含；当前 batch turns 不扩邻 |
| Q6 | Evidence DTO | **确认** `DreamingTopicEvidence`（含 skipReflection/new/historical conversation/organized/raw/决策）+ `DreamingBatchEvidence(topics)`；Core 或 Runtime internal 由 Lead 按 CT23 模式定（非 SDK public） |
| Q7 | RawMemoryRef cycle | **fail closed**：检测到循环（含自循环）即整批失败——不静默截断链 |
| Q8 | Raw 无关联 Organized | **保留为历史证据候选**（不失败）；Prompt/Reflection 明示其非当前事实（对齐设计"历史 Raw 可能已过时"） |

## 4. 附加边界

- **每 Topic 扫描成本**：每 Topic 独立全量 Organized+Raw 扫描是设计接受的成本；禁止引入缓存/索引优化（留后续里程碑）。
- **快照一致性**：全量 Organized/Raw 快照必须同一 ReadContext 内读取；扫描期间 LLM 调用在事务外。
- **Novelty FULLY_COVERED 语义责任**：程序只能验证结构（非空候选子集），"确实充分覆盖"的语义责任在 Prompt+CT26 评测——交付报告必须明确此边界。
- Prompt 六原则式内容级断言（CT23 模式）：三种 v1 Prompt 的核心契约（数据非指令、相关≠无增量、全历史、冲突/纠错保留、无系统字段）逐片段断言。
- **失败原子性**：一个 Topic 失败 → 整个 Batch 失败，无部分 Evidence 返回（对齐设计 §8.4 末段）。
- B1/B2 白名单由 Lead 分别冻结；`sessionWindowAfter` 归 B2 白名单（含 Repository 实现与测试）。
- 真实 SQLite + Fake LLM；不运行真实模型；无 Git 写操作。

## 5. 结论

- **CT24 方案 APPROVED；D1～D20 与八细项全部裁定；放行 CT24 B1**。
- Lead 冻结 B1 委派单（Organized+Raw scanner、Novelty、反查合并）与白名单后派发 Worker（`059d6c59-ce9e-4e68-9055-b45b238506a1`）。
- B1 复核通过才派发 B2；CT24 DONE 后放行 CT25；CT25～CT26 LOCKED。
- 真实模型 not-run；基线 1971=1971。
