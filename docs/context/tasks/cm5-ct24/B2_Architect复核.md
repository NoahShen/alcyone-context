# CT24 B2 · Architect 复核

**日期**：2026-11-09
**状态**：**CT24 B2 APPROVED / DONE；CT24 APPROVED / DONE**
**复核对象**：[B2 Worker 交付](B2_Worker交付.md)、[B2 委派单](B2_委派单.md)、[B2 委派单复核](B2_委派单复核.md)、Worker `059d6c59-ce9e-4e68-9055-b45b238506a1`
**依据**：[Architect 裁定](Architect_裁定.md) D1～D20 + Q1～Q8、[CT24 任务书](CT24_任务书.md)

## 0. 本次修改

Architect 独立复核 CT24 B2：复算 20/20 SHA、审查 24 文件白名单与 Conversation Need/Raw 来源链/邻居/证据包/Stage 组合，独立运行定向测试、格式检查及 `./scripts/check`，核对 14 模块、Schema、VFS、Core 与生产零接线边界。结论 **B2 APPROVED / DONE，CT24 整体 APPROVED / DONE**。

## 1. 独立证据

| 项 | 独立结果 |
| --- | --- |
| 24 文件白名单 | 20 个 SHA 基线文件全部一致；4 个扩展既有测试文件（ConversationRepositoryRealDb、ContextQueryPlan、PaginationArgumentValidation、EvidenceTestSupport）逐文件审查，无白名单外生产/构建变更 |
| 定向测试 | B2 Core/Runtime 49 例与相关 persistence 31 例全绿；三模块首次联合复跑出现环境异常，聚焦重跑及最终全量均通过，见 §5 |
| 格式 | Core/Runtime/persistence-sqldelight `spotlessCheck --rerun-tasks` exit 0（10s，13 tasks） |
| 全量 `./scripts/check` | **exit 0**，2m27s，131 actionable tasks（129 executed、2 up-to-date），全部 check/build/spotless 通过 |
| 14 模块 source/XML | **2063=2063**，各模块 failures/errors/skipped 均 0；相对 B1 的 2020 增加 43 例 |
| Schema | `ContextSchema.sq` SHA `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；`ContextSchemaInitializationTest` 通过，未改 DDL/迁移/索引 |
| VFS | `vfs/` 与 `integration-tests/vfs/` 全部 tracked 文件已纳入独立 SHA 清单（189 文件）；VFS 全量测试与构建通过，未见 B2 变更 |
| Core/生产边界 | Core 源码及依赖无 Ktor/serialization；Factory/Impl 无 CT24 Stage/Tracer 接线；无 Coordinator/Raw/Pending/Checkpoint 写入；真实模型 not-run；无 Git 写操作 |

## 2. 代码与测试有效性复核

| 裁定 | 复核结果 |
| --- | --- |
| D14/Q4 Conversation Need | 只接受 `memoryIds` 字符串数组；缺字段/额外字段/重复键/空 ID/错误类型/围栏/截断均失败。Validator 限制 B1 Raw 候选子集并稳定去重，空数组合法；错误归因 `DREAMING_EVIDENCE`、无敏感 cause。通过 |
| D15/Q7 Raw 链 | 只从 Need 选中根递归；整条链 Raw 纳入最终证据；缺失 Raw、缺失 ConversationTurnRef、自环与多节点环均 fail-closed；`activeRaw` 与 `completedRaw` 分离，菱形共享节点不误判。通过 |
| 委派单 rawMemories 口径 | 全部 B1 Raw hits 与链 Raw 合并，未选中的 B1 Raw 不被过滤；只追溯选中根。空 Need 仍保留全部 B1 Raw 并补反查 Organized、不扩来源/邻居。对应测试能捕获“按 Need 过滤最终 Raw”及“遍历所有 B1 Raw”的错误实现。通过 |
| D12/Q8 反查 | 每个 B1/链 Raw 调 `findOrganizedByRaw` 并回读全部 Organized，与 B1 Organized 按 id 合并；关系指向缺失 id 整批失败；无关联 Raw 保留。通过 |
| D16/D18 业务键 | ConversationTurnRef 经四元 `findByBusinessKey` 定位；Turn 按四元业务键去重，当前 Topic Turn 即使内部 id 不同也正确排除，不按全局自增 id 误去重。通过 |
| D17/D20/Q5 forward SQL | 7 参数参数化 SQL，严格同 Session、`(timestamp,id) > anchor`、升序、limit；同 timestamp tie-break、首/尾、limit、跨 runtime/agent/session、负/零 limit 均有断言；ReadOps/TxOps/Repository 语义一致，query plan 使用既有 `idx_turn_session_time`。通过 |
| 邻居与去重 | 每锚点固定前 1/后 1；当前 batch Turns 不作为扩邻锚点；同 Turn 多 Raw、多个根窗口均按业务键合并，不递归扩邻、不跨 Session。通过 |
| D19/失败语义 | Need 预算等号通过、超预算在 Need 请求前失败；截断/非法/越权/取消语义正确；LLM 均在只读作用域外；Stage 不写数据库，Topic/Batch 失败不返回部分 Evidence。通过 |
| Q6 DTO/CT23 组合 | `FULLY_COVERED` 不请求 Raw/Need；空 Raw 不请求 Need；Needs 保留 new/historical/organized/raw 分离证据。真实 SQLite + 真实 CT23 Stage + Fake LLM 全链验证顺序、来源链、反查、业务键与邻居，零写。通过 |
| Prompt/Schema | Conversation v1 覆盖数据非指令、必要性、范围/时间、冲突、纠正、独立性、宁缺勿滥及严格 JSON；Schema 严格且仅 `memoryIds`。资源缺失显式失败。通过 |

上述关键分支均核对了实际断言，而非仅依据用例名称计数；未发现需要退回 Worker 的生产缺陷或验收覆盖阻断。

## 3. CT24 A01～A08 总体验收

- **A01～A04**：沿用 B1（含 R1）独立复核结论，Novelty、全量 Organized/Raw 扫描、反查合并与缺失失败均 DONE；B2 未破坏 B1 行为。
- **A05**：Conversation Need 严格子集、空合法、单请求预算与错误归因通过。
- **A06**：ConversationTurnRef 业务键、RawMemoryRef 多层/菱形/循环/缺失及同 Turn 去重通过。
- **A07**：同 Session 前 1/后 1、timestamp/id tie-break、不跨 Session、当前 batch 不扩邻通过。
- **A08**：Covered/Needs 最终 DTO、CE/异常、真实 SQLite 组合、零写与零生产接线通过。

## 4. 边界与后续

- 本批复核证明协议、权限、结构、失败与事务边界；**不证明真实模型的证据相关性或来源选择语义质量**，后者仍归 CT26 评测。
- CT24 仍是组件化交付，未接公共生产链路；真实模型 not-run，无 Git 写操作/推送。
- **CT24 APPROVED / DONE；可以按既定串行授权进入 CT25，但本复核不自动启动 CT25；CT26 仍待 CT25 复核通过后放行。**

## 5. 构建环境观察（不阻断）

- 首次三模块联合 `--rerun-tasks`：Runtime 747 例中 56 例失败，主要是 `NoClassDefFoundError`，另有既有 `ExtractionPipelineLifecycleTest` 一次超时；聚焦 B2 测试随即全绿。
- Runtime 第二次全量：747 例仅上述既有 Pipeline 测试超时；另一次与全量 clean build 并行执行的隔离重跑出现编译类文件瞬时缺失（`ResourceMetadata.class`），判为并发 clean/重编译环境竞争，不以此替代有效验收结果。
- 最终串行 `./scripts/check` clean build 全绿，Runtime 747/747、14 模块 2063/2063。既有 Pipeline 时序敏感与 B1 复核 §4 的历史观察一致，B2 未修改该组件；继续保留专项跟踪建议，不作为本次阻断。
