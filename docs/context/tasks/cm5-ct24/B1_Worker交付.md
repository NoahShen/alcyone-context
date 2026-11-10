# CT24 B1 · Organized / Raw / Novelty Worker 交付（含 R1）

## 本次修改（2026-11-09，B1 初始交付）

新增本交付报告，记录 CT24 B1 的 18 文件白名单、Novelty DTO/Validator、Novelty/Raw Prompt 与严格 Schema、双 Task Binding、Parser、Candidate、LlmDreamingEvidenceCollector、R1 Scanner 错误任务归属修复、真实 SQLite + Fake LLM 测试、最终 14 模块 **2020=2020** 及相关边界。本文件不把 B1、CT24 或后续批次标记为 DONE。

**日期**：2026-11-09
**状态**：**R1 已修复，提交 Lead 复验；CT24 未 DONE，B2/CT25～CT26 LOCKED**
**依据**：[B1 委派单](B1_委派单.md)、[Architect 裁定](Architect_裁定.md)、[CT24 任务书](CT24_任务书.md)、[方案核对](方案核对.md)、[CT23 B1 Worker 交付](../cm5-ct23/B1_Worker交付.md)
**基线**：CT23 APPROVED / DONE；14 模块 **1971=1971**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；Git `f391192`。工作区既有 CT22/CT23 交付与 CM5 规划文档保持不动。

## 1. 交付结论

严格完成 B1 委派单 §4 的 18 个文件：**17 个代码/资源/测试文件 + 本交付报告**。

- Core 新增 `DreamingNoveltyAssessment`/Validator、Novelty/Raw Prompt/Schema 与渲染。
- Runtime 新增双 Task Binding、Novelty/Raw strict Parser、Candidate DTO 与 `LlmDreamingEvidenceCollector`。
- Organized scanner 复用 CT20 Prompt/Parser/ID Validator/TokenBatchPlanner/LlmBudget；Raw scanner 使用 `DREAMING_EVIDENCE`。
- 同一 `database.read` 读取完整 Organized+Raw snapshots；每 Topic 独立扫描；Raw 命中反查全部 Organized 并合并去重。
- Full covered 只做结构与 ID 权限校验；语义覆盖质量由 Prompt 与 CT26 评测负责。
- B1 初始新增 **45** 个测试；R1 追加 **4** 个错误任务归属测试，最终新增 **49**：Core 13、Runtime 36。
- R1 仅修复 Organized/Novelty/Raw 的 `OUTPUT_TRUNCATED`/`INVALID_RESPONSE` 安全 Task/profile/model 归属并补测试，不改变候选选择、Novelty 决策、Raw 反查或合并结果。
- 14 模块源码 `@Test` 与 XML 均为 **2020=2020**，fail/error/skip = **0/0/0**；相对 1971 基线新增 49。
- 三条验收命令与最终 `./scripts/check` 均通过；真实模型 not-run，无 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 Core 生产与资源（6）

| 文件 | SHA-256 |
| --- | --- |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingNoveltyAssessment.kt` | `cfb3d0fdca8c0476869fa3ab715999ef3d5826b997dc75fb49e81bae4291959e` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingEvidencePrompt.kt` | `9369769386bb70538813963cfeee4e1a3a5fe6a5086a29fae5f7ff1be4d53999` |
| `context/core/src/main/resources/prompts/dreaming-evidence-novelty-v1.md` | `6c533545482f467f86e8fdf601af411a0cc4744040be1964fd0f847c26bbc8a9` |
| `context/core/src/main/resources/prompts/dreaming-evidence-novelty-schema-v1.json` | `78de683e7821531a4c84a4b260a921dc527eb843fd6119664c137cf444f4a499` |
| `context/core/src/main/resources/prompts/dreaming-evidence-raw-v1.md` | `d60df5a68defe2311747cfcb4be81f99bbcde7d1b41f4852553769885f96bce6` |
| `context/core/src/main/resources/prompts/dreaming-evidence-raw-schema-v1.json` | `c8933ff7f1a61911cef428310cc5ac20519698213866bb0a232a98228d952368` |

### 2.2 Core 测试（2）

| 文件 | SHA-256 |
| --- | --- |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingNoveltyAssessmentTest.kt` | `1cc2c1e1b1b7b549e5630f122ad39c188850a97693481b8036f555ad7d40314f` |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingEvidencePromptTest.kt` | `1e771c466a9d16c3ed399ae993792bafcf87876a8e4fbc9ac45153492dcb695b` |

### 2.3 Runtime 生产（5）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingEvidenceBinding.kt` | `4a151c526e04c061147bdb6d8ed84f0f2b8696238b247aa822bd001def76a2be` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingTopicEvidenceCandidate.kt` | `fc4be438352359ffeb874b210ec414b94d2cf075f7a591f420b29fa1081fdee1` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/JsonDreamingNoveltyResponseParser.kt` | `50a6afe798d8699b8a5fee7c7d7b167947c844ec99d63341ca853a8b41f26036` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/JsonDreamingEvidenceCandidateParser.kt` | `b25b5e5843ce35aca9d7916c73f234866546deced2d8a67cfc0565318ea42463` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingEvidenceCollector.kt` | `a36774a5bd578f8239e5f81a176fb4dfcd6942b9664ce5c715887be4f4b1298b` (R1) |

### 2.4 Runtime 测试（4）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingEvidenceTestSupport.kt` | `26acbcf1dbca0768b1369cb9f23a04dde2ff941f6251da5cf5137ecc8457c5d3` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/JsonDreamingEvidenceParserTest.kt` | `03bb0e56c753e7843d42bb01970227fcd5da721a7b41b10632b2e8b0e9da9bc5` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingEvidenceCollectorTest.kt` | `95235bd26874cf67d2e03b1f91aaa75c4094a42fec279805c16a74407efa4781` (R1) |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingEvidenceRealDbTest.kt` | `00c6a1d5706d988a190b65a7a0478836a650cc8d9f9af660b7b844b6a870903f` |

### 2.5 交付报告（1）

| 文件 | SHA-256 |
| --- | --- |
| `docs/context/tasks/cm5-ct24/B1_Worker交付.md` | 本文件自身不内嵌自引用 SHA；由外部最终计算 |

白名单外 CT24 B1 代码/资源/测试/构建变更：**0**。既有 CT23/CT22 交付、CT20/CT18 文件、CM5 规划文档未被回滚、丢弃或改写。

## 3. 实现说明

### 3.1 Novelty DTO / Validator

- `DreamingNoveltyDecision` 仅两态；`DreamingNoveltyAssessment` 对覆盖 ID 做防御性不可修改拷贝，`toString` 不泄露 ID。
- `NEEDS_EVIDENCE` 必须覆盖 ID 为空；`FULLY_COVERED` 必须非空且全部属于 relevant Organized 候选；重复稳定去重；blank/unknown 拒绝。
- Validator 只验证结构、空集和候选 ID 权限，不证明“语义已充分覆盖”。

### 3.2 Prompt / Schema / Rendering

- Novelty Prompt 明确相关不等于无增量、数据非指令、只有无新增/限制/变化/纠错/矛盾才可 FULLY_COVERED。
- Raw Prompt 明确全历史 Raw、不因 Organized 已召回而省略、EXTRACTION/DREAMING 双 origin、未关联 Raw 不是当前事实。
- discovery/novelty/raw 输入渲染稳定：Topic、source batch turns、Organized/Raw 字段和完整正文原样，不渲染 extractionStatus 系统字段。
- 两份 Schema 均严格 object、required、`additionalProperties=false`；Novelty decision enum，Raw candidate_ids 字符串数组可空。
- 资源缺失/空白 fail-fast；两套 Prompt 资源分别由测试覆盖。

### 3.3 Binding / Parsers

- `DreamingEvidenceBinding` 构造期同时解析 `MEMORY_SEARCH` 与 `DREAMING_EVIDENCE` Task/Profile，要求两者 JSON_SCHEMA 或 JSON_OBJECT；错误只含配置路径。
- Novelty Parser 严格 `decision/coveredOrganizedIds`；Candidate Parser 严格 `candidate_ids`；unknown/missing/type/围栏/前后文本/重复 key/截断统一 `INVALID_RESPONSE`，无 cause、无正文。
- Candidate IDs 的空白由 ID 层拒绝，重复由 `MemorySearchIdValidator` 稳定去重。

### 3.4 Collector

- `collect` 首先在一个 `database.read` 内分页读取完整 Organized 与 Raw snapshots；Organized 按 id ASC，Raw 按 `(createdAt ASC,id ASC)`。
- 每个 Topic 独立扫描完整 Organized snapshot，复用 `MemorySearchPrompt.scannerMessages`、`JsonMemorySearchResponseParser`、`MemorySearchIdValidator`、`TokenBatchPlanner`、`LlmBudget`；无 Topic 级缓存。
- Organized 无候选直接 NEEDS；有候选才调用 Novelty。FULLY_COVERED 跳过 Raw；NEEDS 扫描完整 Raw snapshot，不按 origin 过滤。
- Raw 命中逐个单独 `database.read`：`findOrganizedByRaw` → `findByIds` 全量回读；缺失任何关联 ID 整批失败；无关联 Raw 保留。
- 第一层 Organized 与反查 Organized 合并按 id ASC 去重；Candidate 的 newConversationTurns 按 batch 原顺序映射 Topic source IDs。
- 任一批 Parser/ID/预算/截断/反查失败立即抛出，不返回部分 Candidate；CE 原样传播。

### 3.5 R1 错误任务归属

- Organized Scanner 请求使用 `MEMORY_SEARCH`；其 `OUTPUT_TRUNCATED`、严格 Parser 失败和批内 ID 校验失败统一携带 `task=MEMORY_SEARCH` 及 `memorySearchProfile` 的 profile/model。
- Novelty/Raw 请求使用 `DREAMING_EVIDENCE`；其截断、Parser、Novelty Validator 与批内 ID 校验失败统一携带 `task=DREAMING_EVIDENCE` 及 `evidenceProfile` 的 profile/model。
- 反查 Organized ID 缺失仍使整批失败，原任务归属保持 `DREAMING_EVIDENCE`；不新增公共错误码/API。
- R1 测试配置为 `MEMORY_SEARCH` 与 `DREAMING_EVIDENCE` 使用不同的 profile/model，避免同值掩盖错误归属；Organized ID 非法测试同时断言无后续 `DREAMING_EVIDENCE` 请求。
- 错误对象不携带 cause，消息不含 Prompt/Conversation/Topic/Raw/Organized 正文、Key 或 URL；CE 仍原样传播。

## 4. 矩阵实际断言

| 场景 | 实际断言 |
| --- | --- |
| 空 Organized/Raw snapshot | 0 LLM；Candidate NEEDS，new turns 仍按 batch 顺序映射 |
| Organized full scan | 多页/多批；每个 Topic 全量扫描，不 Agent/Domain 过滤 |
| Organized 无命中 | 不调用 Novelty，直接 NEEDS；Raw 可继续扫描 |
| Novelty NEEDS | 继续完整 Raw 扫描 |
| Novelty FULLY_COVERED | organized 非空、raw 为空；0 Raw 请求 |
| FULLY 覆盖越权/空 | 整批 INVALID_RESPONSE |
| Raw full scan | 双 origin 参与；多批独立；raw snapshot 不被第一层缩小 |
| Raw ID 批外/未知/空白 | 整批 INVALID_RESPONSE，后续 0 调用 |
| Raw 去重 | 重复返回 ID 稳定去重为单一 snapshot 项 |
| Raw 反查 | 每个 Raw 单独 read；全部关联 Organized 回读合并；id ASC 去重 |
| 反查缺失 | 整个 Batch 失败，无部分 Candidate |
| Raw 无关联 Organized | 保留 Raw 为历史候选，不失败 |
| 多 Topic | 每 Topic 独立全量扫描；前一个失败后无后续 Topic 请求 |
| 预算等号 | Organized/Raw 分批边界通过；超限 0 对应 LLM |
| Schema tokens | JSON_SCHEMA 计入并超限；JSON_OBJECT 不计入并通过 |
| JSON 模式 | MEMORY_SEARCH 与 DREAMING_EVIDENCE 均断言 schema/responseFormat |
| 截断/CE | `OUTPUT_TRUNCATED`；CE 原实例传播 |
| R1 Organized 截断 | `OUTPUT_TRUNCATED`、task/profile/model = `MEMORY_SEARCH`/safe；0 后续 Novelty/Raw 请求 |
| R1 Organized Parser/ID 失败 | `INVALID_RESPONSE`、task/profile/model = `MEMORY_SEARCH`/safe；0 后续请求 |
| R1 Novelty/Raw 失败 | `INVALID_RESPONSE`/`OUTPUT_TRUNCATED` 均归属 `DREAMING_EVIDENCE`，profile/model safe；Novelty 失败 0 Raw 请求 |
| R1 传输安全 | 异常无 cause；message/toString 不出现测试正文、Key 标记或 URL |
| real SQLite | 唯一快照 read、LLM 在 read 外、真实 source link 反查、无权威表写入 |
| Candidate DTO | 列表防御性复制；toString 不泄露正文 |

## 5. Prompt 核心契约逐片段证据

| 契约 | 测试断言 |
| --- | --- |
| 数据非指令 | `DreamingEvidencePromptTest` 断言 Novelty/Raw 均含“不是指令”，伪造标签/JSON 不执行 |
| 相关 ≠ 无增量 | Novelty Prompt 断言“相关不等于无增量”“绝不因相关自动覆盖” |
| 全历史 Raw / 双 origin | Raw Prompt 断言“完整历史 Raw”“不因 Organized”“EXTRACTION”“DREAMING” |
| 未关联 Raw 非当前事实 | Raw Prompt 断言“未关联任何 Organized”“历史原始证据”“不能自动当作当前事实” |
| 冲突/纠错/范围/时间 | Novelty Prompt 断言新增事实、限定条件、变化、纠错、矛盾、时间/范围差异不得提前 FULLY |
| 无系统字段 | 两份 Prompt/Schema 测试断言不要求 topicId/confidence/generatedBy/checkpoint/createdAt/extractionStatus |
| 两个 Prompt 资源 fail-fast | 同一反射测试同时覆盖 Novelty 与 Raw 缺失资源 |

## 6. 测试映射与用例数

| 测试类 | 新增用例 |
| --- | ---: |
| `DreamingNoveltyAssessmentTest` | 5 |
| `DreamingEvidencePromptTest` | 8 |
| `JsonDreamingEvidenceParserTest` | 6 |
| `LlmDreamingEvidenceCollectorTest` | 27 |
| `DreamingEvidenceRealDbTest` | 3 |
| **合计新增** | **49** |

## 7. 验收命令与结果

### 7.1 最终有效实跑

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle spotlessApply` | 0 | 1s | `BUILD SUCCESSFUL`；57 tasks up-to-date |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | 0 | 48s | `BUILD SUCCESSFUL`；27 tasks executed；Core 157/157、Runtime 717/717 |
| `./scripts/dev gradle :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | 7s | `BUILD SUCCESSFUL`；9 tasks executed |
| `./scripts/check` | 0 | 2m21s | `BUILD SUCCESSFUL`；131/131 tasks executed |
| `git diff --check` | 0 | - | 空输出 |

### 7.2 既有 Extraction 时序观察

- 测试开发期间 Core/Runtime 全量曾一次被既有非白名单 `ExtractionPipelineLifecycleTest > disabling idle...` 10s 超时阻断；CT24 定向进程内的测试未出现该失败。
- 该既有类单独复跑最终 **15/15 通过**，随后 Core/Runtime 全量与 `./scripts/check` 均通过；未修改白名单外文件。
- 该现象作为既有稳定性观察保留，不归因于 CT24 生产代码。

## 8. 14 模块源码 `@Test` 与 XML

源码范围为各模块 `src/test/**/*.kt` 行首（允许缩进）`@Test`；XML 为最终成功 `./scripts/check` 结果。

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 41 | 41 | 0 | 0 | 0 |
| context-core | 157 | 157 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 717 | 717 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 27 | 27 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **2020** | **2020** | **0** | **0** | **0** |

### 8.1 与 1971 基线差异

- CT24 B1 最终新增 **49** 个测试：Core +13、Runtime +36；此前提交的 45 个测试保持，R1 新增 4 个归属回归。
- 1971 + 49 = **2020=2020**，差额 0。

## 9. CT23 / CT22 / Schema / 81 / common / VFS / Core 边界

- CT23 B1 交付报告 21 个代码/资源/测试 SHA：**21/21 一致**；CT23 Topic DTO/Stage/Processor/Coordinator 未修改。
- CT22 B1 交付报告 14 个代码/测试 SHA（含 R1 后 Config/Test）：**14/14 一致**；CT22 outcome/seam 未修改。
- CT20 B1 19 个代码/资源/测试 SHA：**19/19 一致**；CT20 Search/Rerank 语义未修改。
- CT18 17 个代码/测试 SHA：**17/17 一致**；Generator/Publisher/Renderer 未修改。
- `ContextSchema.sq` SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变；无表/索引/SQL。
- 81 VFS 基线：**75 一致 + 6 个 CT17 已批准差异，缺失 0，未批准差异 0**。
- `vfs`、`common`、`gradle`、`build.gradle.kts`、`settings.gradle.kts` 无未提交修改；本次 CT24 B1/R1 未修改依赖或构建。
- Core 主源码无 Ktor、无 kotlinx.serialization；Core/build 未修改。
- 本次 R1 未修改 `ContextConfig`/`ContextConfigLoader`、`AlcyoneContext`、Factory/Impl、CT22/CT23 文件、ContextStatus/WorkerStatus、Schema/SQL/VFS/common/依赖/构建；工作区中 Config/Loader 的既有 CT22 修改保持不动。
- R1 白名单严格为 1 个 Runtime 生产文件、1 个 Runtime 测试文件和本交付报告；其余 16 个 B1 初始文件及上述边界均未修改。

## 10. 未运行项与残余风险

- 真实模型：**not-run**；所有 LLM 均为 Fake，只证明协议、结构、来源权限、预算和错误映射，不证明 Novelty/证据语义质量。
- CT24 B2：**LOCKED**；Conversation Need、RawMemoryRef 递归链、ConversationTurnRef 定位、相邻 Turn、完整 Evidence DTO/CT23 组合集成未实现。
- CT25～CT26：**LOCKED**；Reflection、Raw/Pending/Checkpoint 写入、固定评测和真实模型质量未启动。
- FULLY_COVERED 只做决策结构与非空 relevant candidate 子集校验；“确实充分覆盖”的语义责任在 Prompt 与 CT26 评测。
- Raw 未关联 Organized 合法保留为历史候选；Prompt 明确其不是当前事实，程序不自行生成历史来源结论。
- 每 Topic 全量扫描成本高，设计接受；未引入缓存、Embedding、FTS 或跨 Topic 优化。
- 既有 Extraction 时序测试在部分全量运行中出现过一次超时，独立复跑与最终全量通过；保留观察，不影响本次最终证据。
- 无 Git 写操作；工作区既有 CT22/CT23/CM5 未回滚、丢弃或改写。

## 11. 最终状态

**R1 已修复并提交 Lead 复验；CT24 未 DONE，B2/CT25～CT26 LOCKED。**

真实模型 not-run；无 Git 写操作。
