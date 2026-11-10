# CT24 B2 · Conversation 追溯与证据包 Worker 交付

**日期**：2026-11-09
**状态**：**提交 Lead 初验，CT24 未 DONE，CT25～CT26 LOCKED**
**依据**：[B2 委派单](B2_委派单.md)、[B2 委派单 Architect 复核](B2_委派单复核.md)、[Architect 裁定](Architect_裁定.md)、[CT24 任务书](CT24_任务书.md)、[B1 Worker 交付](B1_Worker交付.md)、[B1 Architect 复核](B1_Architect复核.md)
**基线**：CT24 B1（含 R1）APPROVED / DONE；14 模块 **2020=2020**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；Git `f391192`

## 1. 交付结论

严格完成 B2 委派单 §5 的 **24 文件白名单**：23 个代码/资源/测试文件 + 本交付报告。

- Conversation Need DTO/Validator、Prompt/Schema、严格 JSON Parser 已实现；Need 只决定 Conversation trace roots，不缩小 final Raw 证据集合。
- `sessionWindowAfter` 以 7 参数参数化只读 SQL 接入 `ConversationReadOps`、`ConversationTxOps`、`ConversationRepository` 与实现；严格同 Session、`(timestamp,id) > anchor`、升序、复用 `idx_turn_session_time`。
- RawMemoryRef 使用 completed-visited + on-stack cycle 检测递归解析；菱形去重、自循环/多节点循环、missing Raw/Turn 均整批失败。
- final `rawMemories` 固定为全部 B1 hits ∪ chain Raw；全部 evidence Raw 均执行 Raw→Organized 反查和全量回读；ConversationTurnRef 使用四元业务键定位。
- 每个关键 Turn 仅扩同 Session 前 1/后 1；当前 batch turns 不扩邻，historical 按业务键排除 new turns；邻居不递归扩邻。
- `LlmDreamingEvidenceStage` 仅组合 CT23 Discovery/Consolidation、B1 Collector、B2 Need/trace；未修改 CT22/CT23 seam 或 Processor outcome。
- 新增 **43** 个测试：Core +8、Persistence +5、Runtime +30。
- 14 模块 fresh source `@Test` = XML = **2063=2063**，fail/error/skip = **0/0/0**；相对 2020 基线新增 43。
- 真实模型 not-run；无权威表写入；无 Git 写操作。

## 2. 24 文件白名单与 SHA-256

### 2.1 Core（8）

| # | 文件 | SHA-256 |
| ---: | --- | --- |
| 1 | `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingConversationNeed.kt` | `86f9c2b7fa9be5ee850c4de62bcded8023188f515e8dbc61d07c9e46829a473c` |
| 2 | `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingEvidencePrompt.kt` | `4f484ba9619512b9d82283dd291e8a60ee147e37f923f6f83307172ae649a655` |
| 3 | `context/core/src/main/resources/prompts/dreaming-evidence-conversation-v1.md` | `fc766aa0487253f7c5f8c660e345c79ae556d5d5f27339c270b8b9af4b55a65c` |
| 4 | `context/core/src/main/resources/prompts/dreaming-evidence-conversation-schema-v1.json` | `306b26ec4e74e7b605418e3b70ec7a688285f4288de25633d8be0f3d3e2904d7` |
| 5 | `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingConversationNeedTest.kt` | `1564e78cfc2d914f5c58bab3bd9970bb900545f1ee774a78f30b72ae90698ddb` |
| 6 | `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingEvidencePromptTest.kt` | `fa67c5925c8e118b638f2cc1ff5c4deaead8bd2dc35ad6a61f1d5f3c8739b3e8` |
| 7 | `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/repository/ConversationReadOps.kt` | `f80e43e7554524f437a17944d7a20ab104f13ef78dfcc4868c4434af229cbbad` |
| 8 | `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/repository/ConversationRepository.kt` | `c1fd0a29be10c8762819f4e8b89f3cc312fb9af3a667a71bebee6c31ead90a96` |

### 2.2 Persistence（6）

| # | 文件 | SHA-256 |
| ---: | --- | --- |
| 9 | `context/persistence-sqldelight/src/main/kotlin/com/github/noahshen/alcyone/context/memory/persistence/sqldelight/ContextSqlQueries.kt` | `cb82d3e6245231e9f30dfec23db1190a48ed44f708186fefe123da081686c5df` |
| 10 | `context/persistence-sqldelight/src/main/kotlin/com/github/noahshen/alcyone/context/memory/persistence/sqldelight/ContextOps.kt` | `6915d2817127dab1fcb757b9bcca8f40e5c239cb356d04c445cffa1ceac1c060` |
| 11 | `context/persistence-sqldelight/src/main/kotlin/com/github/noahshen/alcyone/context/memory/persistence/sqldelight/ConversationRepositoryImpl.kt` | `267056dd516013b5cd286b9a65199e84bf9858104e52f4ad1f96255aa1d832fc` |
| 12 | `context/persistence-sqldelight/src/test/kotlin/com/github/noahshen/alcyone/context/memory/persistence/sqldelight/ContextConversationRepositoryRealDbTest.kt` | `80410994e5289a3682ec76adfac77615e6dc10e3d67514695a2d82f191b8e958` |
| 13 | `context/persistence-sqldelight/src/test/kotlin/com/github/noahshen/alcyone/context/memory/persistence/sqldelight/ContextQueryPlanTest.kt` | `3531053c829c19809ff57798071d8c79d50d0cff6078a5013d1a7a70ca885a40` |
| 14 | `context/persistence-sqldelight/src/test/kotlin/com/github/noahshen/alcyone/context/memory/persistence/sqldelight/PaginationArgumentValidationTest.kt` | `14ee0cb7d9ac586d49aca6141c7ec07ad76adb8f7c14106e19068c095271fef2` |

### 2.3 Runtime（9）

| # | 文件 | SHA-256 |
| ---: | --- | --- |
| 15 | `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/JsonDreamingConversationNeedParser.kt` | `870db45a8f0af0820bfdd9104ed4a899ae5f36c9cce10730ae99b01fc4deb8d1` |
| 16 | `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingEvidence.kt` | `62a47e378dd1d15da3811df27e83186a87ff727626ce58443448130aa44c01e2` |
| 17 | `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingConversationEvidenceTracer.kt` | `06e896bb4672d2bb7573e9ef2fb784a256b896e49e273d514fe363c0cc43c2cf` |
| 18 | `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingEvidenceStage.kt` | `0bfc55cfe465263473fb58c86aab8129bc023ecc336090d7394dc890febab5ba` |
| 19 | `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingEvidenceTestSupport.kt` | `b908567dafd949eb37502cf896472322c34ad382c3bc3ac1e9cb4514465f613c` |
| 20 | `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/JsonDreamingConversationNeedParserTest.kt` | `64671521f79ea8e57cffbf83ca1de1bfa328b644d1da97cec2bd5be87099cab1` |
| 21 | `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingConversationEvidenceTracerTest.kt` | `e8dd2ef6e2ced7890951ad3df683a9b691073e88b62490f99b0cd0f858429919` |
| 22 | `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingEvidenceStageTest.kt` | `ba5a39f3962152fdf91deaf9c2f50e3817cde7dcfe7d4dc277aa1126e82a99c3` |
| 23 | `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingEvidenceRealDbTest.kt` | `4cb7eb5da572840f3bfda563ff2e749a1f6a7a198628a9ba134460dd5729948f` |

### 2.4 交付报告（1）

| # | 文件 | SHA-256 |
| ---: | --- | --- |
| 24 | `docs/context/tasks/cm5-ct24/B2_Worker交付.md` | 本文件自身不内嵌自引用 SHA；由外部最终计算 |

CT24 B2 白名单外代码/资源/测试/构建变更：**0**。入口前既有 CT22/CT23/CT24 B1 脏文件与规划文档保持不动，未提交或回滚。

## 3. 4 个 B1 声明文件增量与其余 SHA 不变

| 文件 | B1 终态 SHA | B2 终态 SHA | 增量说明 |
| --- | --- | --- | --- |
| `DreamingEvidencePrompt.kt` | `9369769386bb70538813963cfeee4e1a3a5fe6a5086a29fae5f7ff1be4d53999` | `4f484ba9619512b9d82283dd291e8a60ee147e37f923f6f83307172ae649a655` | 新增 Conversation v1 资源访问器、messages 与确定性 rendering；Novelty/Raw 既有契约不变 |
| `DreamingEvidencePromptTest.kt` | `1e771c466a9d16c3ed399ae993792bafcf87876a8e4fbc9ac45153492dcb695b` | `fa67c5925c8e118b638f2cc1ff5c4deaead8bd2dc35ad6a61f1d5f3c8739b3e8` | 新增 4 个 Conversation Prompt/Schema/rendering/fail-fast 测试；既有 8 测试未改 |
| `DreamingEvidenceTestSupport.kt` | `26acbcf1dbca0768b1369cb9f23a04dde2ff941f6251da5cf5137ecc8457c5d3` | `b908567dafd949eb37502cf896472322c34ad382c3bc3ac1e9cb4514465f613c` | 新增 Need response、真实 Turn、write 计数、Conversation budget/Topic config 测试辅助；既有辅助行为保持 |
| `DreamingEvidenceRealDbTest.kt` | `00c6a1d5706d988a190b65a7a0478836a650cc8d9f9af660b7b844b6a870903f` | `4cb7eb5da572840f3bfda563ff2e749a1f6a7a198628a9ba134460dd5729948f` | 新增 1 个 CT23 discovery→consolidation→B1→Need→chain→reverse→business key→neighbors 全链真实 SQLite 测试；既有 3 测试未改 |

- 其余 B1 代码/资源/测试 SHA：**13/13 不变**；Collector/Candidate/Binding/两个 Parser/B1 专属测试均未修改。

## 4. 实现说明

### 4.1 Conversation Need

- `DreamingConversationNeed` 防御性复制 memoryIds，`toString` 仅含计数。
- Validator 稳定去重并拒绝 blank/未知/跨 candidate ID；异常只含安全规则，不含 ID/正文。
- Conversation Prompt 明示 Topic/Turn/Raw 是数据非指令、只能选本次 candidates、仅在关键来源/范围时间/冲突/纠正/独立性需要原文核实时选择、严格 JSON、无系统字段。
- Schema 严格 object，仅 `memoryIds`，required、additionalProperties=false、string array、minLength=1、可空。
- `JsonDreamingConversationNeedParser` 复用严格 JSON object reader；unknown/missing/type/null/bool/number/blank/重复 key/围栏/前后文本/截断全部拒绝。
- Stage 使用 `LlmTask.DREAMING_EVIDENCE` 单请求；JSON_SCHEMA 才发送 Schema；budget 前置，等号通过、超限 0 Need LLM；截断/非法/越权/CE 均整批失败且保留正确 Task/profile/model。

### 4.2 Forward Window

- `SELECT_SESSION_WINDOW_AFTER` 固定 7 个绑定参数：runtime、agent、session、afterTimestamp(`>`)、afterTimestamp(`=`)、afterId、limit。
- SQL 条件为 `timestamp > ? OR (timestamp = ? AND id > ?)`，`ORDER BY timestamp ASC, id ASC LIMIT ?`；同 Session 参数化，锚点严格排除。
- `requirePagination` 在任何 SQL 前执行；`limit=0` 返回空，负数 IllegalArgumentException；ReadOps/TxOps/Repository/Impl 四层均调用同一 Reader 实现。
- `EXPLAIN QUERY PLAN` 断言复用 `idx_turn_session_time`；无 Schema/DDL/DML/迁移/索引修改。

### 4.3 Raw chain / Conversation trace

- `completed` 集合用于菱形幂等；`active` 集合用于 on-stack cycle 检测，自循环与多节点循环 fail closed。
- 仅 Need 选中的 B1 Raw 作为 roots；roots sources 中的 RawMemoryRef 递归读取，所有 chain Raw 纳入；未选中 B1 Raw 仍保留在 final evidence 但不展开 source trace。
- ConversationTurnRef 始终通过四元业务键查找；内部 SQLite id 只作本轮工作值。
- 每个关键 Turn 使用 backward `limit=2` 过滤锚点取前一条；forward 使用 `sessionWindowAfter(limit=1)`；邻居不再递归扩邻。
- 当前 batch turns 不作为额外邻居 anchor；historical 最终按四元业务键去重并排除 new turns，按 `(timestamp,id)` 升序。

### 4.4 最终证据与 Stage

- final Raw = 全部 B1 hits ∪ chain Raw，按 `(createdAt,id)` 去重排序。
- **每一个** final Raw 均执行 `findOrganizedByRaw` + `findByIds` 全量回读，与 B1 Organized 合并按 id 去重排序；无关联 Raw 保留，关联 ID 缺失整批失败。
- FULLY_COVERED → final FULLY，raw/historical 空、organized 保留；NEEDS_EVIDENCE → NEEDS_REFLECTION，保留完整 Raw/Organized/Conversation 证据。
- DTO 列表防御性复制且不可修改；`toString` 只含决策与计数，不暴露 Topic/Turn/Raw/Organized 正文或业务 ID。
- Stage 顺序固定为 CT23 Discovery/Consolidation → B1 Collector → Conversation Need → trace → final batch；空 batch 仍由 CT23 Discovery 原语义失败。
- 所有 DB read 均为同步短 scope；Conversation Need LLM 在 DB read 外；测试断言 LLM 执行时 `readActive=false` 且追踪包装器写入计数为 0。

## 5. 测试矩阵与 A05～A08 映射

| 验收 | 实际断言 | 证据 |
| --- | --- | --- |
| A05 Need DTO/Validator | 空合法、防御复制、首次去重、blank/unknown/跨候选拒绝、安全异常/toString | `DreamingConversationNeedTest` 4 |
| A05 Prompt/Schema/Parser | 六类内容原则逐片段、strict schema、确定性渲染；unknown/missing/type/null/bool/number/blank/duplicate key/fence/prefix/suffix/truncate 拒绝 | `DreamingEvidencePromptTest` +4；`JsonDreamingConversationNeedParserTest` 4 |
| A05 Need LLM | FULLY/empty Raw 0 Need；单请求 task/schema/budget；exact/overrun；invalid/truncate/CE 归属与安全消息 | `LlmDreamingEvidenceStageTest` 12 |
| A06 Forward window | 空表、首/中/尾、tie-break、跨 runtime/agent/session、limit 0/负、三入口一致、7 参数、query plan | Persistence 3 类共 +5 |
| A06 Raw chain | 单层/多层/菱形、自循环/两节点循环、missing Raw/Turn、同 Turn 多 Raw/重复 ref/多 root 去重 | `DreamingConversationEvidenceTracerTest` 13 |
| A06 Reverse/By-key | 四元业务键定位、全部 B1+chain Raw 反查、全部 Organized 回读、反查缺失失败、未关联保留 | tracer + real DB |
| A07 Neighbors | 前/后各 1、首尾、tie-break、不跨 Session、batch turns 不扩邻、邻居不递归、多 root union 去重 | tracer 定向 + real DB |
| A08 Final/原子性 | FULLY/NEEDS 映射、final Raw 全集、列表防御复制、安全 toString、无写、LLM 事务外、失败无部分 Evidence | `LlmDreamingEvidenceStageTest` + real DB |
| A08 CT23 组合 | 真实 discovery→consolidation→B1→Need→chain→reverse→business-key→neighbors；请求顺序与任务类型固定 | `DreamingEvidenceRealDbTest` 全链 1 例 |

## 6. 新增测试与计数

| 测试类 | B2 新增 | 当前类总数 |
| --- | ---: | ---: |
| `DreamingConversationNeedTest` | 4 | 4 |
| `DreamingEvidencePromptTest` | 4 | 12 |
| `ContextConversationRepositoryRealDbTest` | 3 | 18 |
| `ContextQueryPlanTest` | 1 | 8 |
| `PaginationArgumentValidationTest` | 1 | 5 |
| `JsonDreamingConversationNeedParserTest` | 4 | 4 |
| `DreamingConversationEvidenceTracerTest` | 13 | 13 |
| `LlmDreamingEvidenceStageTest` | 12 | 12 |
| `DreamingEvidenceRealDbTest` | 1 | 4 |
| **合计** | **43** | |

## 7. 验收命令与结果

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle spotlessApply` | 0 | 1s | `BUILD SUCCESSFUL`；57 tasks，1 executed |
| `./scripts/dev gradle :context:core:test :context:runtime:test :context:persistence-sqldelight:test --rerun-tasks --console=plain` | 0 | 51s | `BUILD SUCCESSFUL`；29 tasks executed |
| `./scripts/dev gradle :context:core:spotlessCheck :context:runtime:spotlessCheck :context:persistence-sqldelight:spotlessCheck --rerun-tasks --console=plain` | 0 | 7s | 初次检查发现 1 处测试换行格式，执行 `spotlessApply` 后重跑通过；13 tasks executed |
| `./scripts/check` | 0 | 2m19s | `BUILD SUCCESSFUL`；131/131 tasks executed |
| `git diff --check` | 0 | - | 空输出 |

## 8. 14 模块 fresh source @Test / XML

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 41 | 41 | 0 | 0 | 0 |
| context-core | 165 | 165 | 0 | 0 | 0 |
| context-persistence | 183 | 183 | 0 | 0 | 0 |
| context-runtime | 747 | 747 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 27 | 27 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| **合计** | **2063** | **2063** | **0** | **0** | **0** |

- 2020 + 43 = **2063=2063**，差额 0。
- 增量：Core +8、Persistence +5、Runtime +30。

## 9. 边界与基线

- CT23 B1 交付报告 21 个代码/资源/测试 SHA：**21/21 一致**；未修改 CT23 Topic DTO/Validator/Prompt/Parser/Stage/Processor。
- CT22 B1 交付报告 14 个代码/测试 SHA：**14/14 一致**；未修改 Coordinator/Cron/Batch seam/outcome。
- CT24 B1：4 个声明文件按白名单增量扩展；其余 13/13 SHA 不变；Collector/Candidate/Binding/两个 Parser 未改。
- `ContextSchema.sq` SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变；零 Schema/DDL/DML/迁移/索引。
- 81 VFS 基线：**75 一致 + 6 个 CT17 已批准差异，缺失 0，未批准差异 0**。
- `common`、`vfs`、`integration-tests/vfs`、`gradle`、根构建文件无 B2 diff；未新增依赖。
- Core 主源码无 Ktor、无 kotlinx.serialization；Core/build 未改。
- 未修改 `AlcyoneContext`、Factory/Impl、ContextConfig/Loader、CT22/CT23、ContextStatus/WorkerStatus、VFS/common/Schema/SQL 文件（SQL 仅新增只读常量）、依赖/构建。

## 10. 未运行项与残余风险

- 真实模型：**not-run**。Fake LLM 只证明协议、权限、预算、错误映射、阶段顺序和原子性，不证明 Conversation Need 的语义判断质量。
- `Novelty FULLY_COVERED` 仅验证结构、非空性及 Organized 候选子集；“确实已充分覆盖”的语义责任仍在 Prompt 与 CT26 评测。
- Raw chain 深度未设额外配置上限；按来源链递归解析并以 cycle fail-closed 防御，极深合法链的栈深度风险留 Architect 评估。
- `sessionWindowAfter` 为接口新增能力的默认实现提供安全拒绝，所有生产 ReadOps/TxOps/Repository 实现均覆盖；未修改白名单外第三方测试 fake。
- CT25（Reflection/Commit/Raw/Pending/Checkpoint CAS）与 CT26（固定评测）仍 **LOCKED**。
- 无 Git 写操作；工作区既有 CT22/CT23/CT24 B1 与规划文档未回滚、丢弃或改写。

## 11. 最终状态

**提交 Lead 初验，CT24 未 DONE，CT25～CT26 LOCKED。**

真实模型 not-run；无 Git 写操作。
