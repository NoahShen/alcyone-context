# CT23 B1 · Topic Discovery 与批内 Consolidation Worker 交付

## 本次修改（2026-11-09，B1-R1）

R1 仅补验收覆盖，不修改任何生产 src/main、资源、CT22/Config/Loader、Schema/SQL、依赖或构建：新增 Consolidation Schema 严格性与两条资源缺失 fail-fast、Consolidation 恰等预算、两阶段 JSON_SCHEMA/JSON_OBJECT Schema/responseFormat，以及 Budget adapter 的 Schema Token 计费差异。新增 3 个测试，最终 14 模块 **1971=1971**、fail/error/skip 0/0/0。CT23 未 DONE，CT24～CT26 LOCKED，真实模型 not-run，无 Git 写操作。

## 本次修改（2026-11-09，B1 初始交付）

新增本交付报告，记录 CT23 B1 的 22 文件白名单、DiscoveredTopic/Validator、Discovery/Consolidation Prompt 与严格 Schema、Runtime Parser、LLM Topic Stage、DREAMING_TOPIC Budget 与 CT22 Processor adapter、真实 SQLite + Fake LLM 接线测试、最终 14 模块 **1971=1971** 及边界证据。本文件不把 B1、CT23 或后续批次标记为 DONE。

**日期**：2026-11-09
**状态**：**提交 Lead 初验，CT23 未 DONE，CT24～CT26 LOCKED**
**依据**：[B1 委派单](B1_委派单.md)、[Architect 裁定](Architect_裁定.md)、[CT23 任务书](CT23_任务书.md)、[方案核对](方案核对.md)、[CT22 B1 Worker 交付](../cm5-ct22/B1_Worker交付.md)
**基线**：CT22 APPROVED / DONE；14 模块 **1916=1916**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；Git 基线 `f391192`。工作区既有 CT22 交付与 CM5 规划文档保持不动。

## 1. 交付结论

严格完成 B1 委派单 §6 的 22 个文件：**21 个代码/资源/测试文件 + 本交付报告**。

- Core 新增四字段临时 Topic DTO、Discovery/Consolidation Validator、两份 Prompt 与两份严格 Schema。
- Runtime 新增严格 JSON Parser、Discovery Stage、DREAMING_TOPIC 绑定/预算路径、CT22 Budget adapter 与 Processor 薄 adapter。
- Discovery 与 Consolidation 均复用 `LlmTask.DREAMING_TOPIC`；未改 `LlmTask`、Config/Loader、Factory/Impl 或 CT22 seam。
- Discovery 来源权限、Consolidation 1..N、来源并集不增不减、0/N+1 Topic、预算等号/超限、截断、CE 和 Coordinator partial failure 均有正式断言。
- B1 新增 **55** 个测试：Core +25、Runtime +30（其中 R1 补 3 个）。
- 14 模块源码 `@Test` 与 XML 均为 **1971=1971**，fail/error/skip = **0/0/0**；相对 1916 基线新增 55。
- Core/Runtime 定向、两模块 Spotless、全量 `./scripts/check` 均通过。
- 未接 Factory/Impl、未写 Raw/Pending/Checkpoint、未运行真实模型、无任何 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 Core 生产与资源（8）

| 文件 | SHA-256 |
| --- | --- |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DiscoveredTopic.kt` | `2150476397bef4cfdef62afd11c7f578f640774920345514de58392c78e34e2e` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DiscoveredTopicValidator.kt` | `30ad9e00cca39c42a0a050174a4399894ef6136d156373bc5b71f037969d8303` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingTopicDiscoveryPrompt.kt` | `28b8e375a8732a7de2805c982f88b84b7aec6a0a9f4c34fc667f7228a78abc6a` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingTopicConsolidationPrompt.kt` | `c3afa95d53972a8c2cc234d84d7c3ad8b2dff0d48460f22407cad01a41958265` |
| `context/core/src/main/resources/prompts/dreaming-topic-discovery-v1.md` | `c6eaf65b9c4d0a8e911a247367db76e33fed2a39243eab055df0e4537303ebef` |
| `context/core/src/main/resources/prompts/dreaming-topic-discovery-schema-v1.json` | `7a081d1dfb242e64a90266c3c7867052bd198fa51240e8620308d346f876a084` |
| `context/core/src/main/resources/prompts/dreaming-topic-consolidation-v1.md` | `dfea2bbadf29163b67c54252abc4ff3e14245471f15f1154dbc017e832eb2ca5` |
| `context/core/src/main/resources/prompts/dreaming-topic-consolidation-schema-v1.json` | `cf6ce4b8e6a191fb6b5fa6ce586b89f238026ca50ad8edabde483f4369c7a486` |

### 2.2 Core 测试（3）

| 文件 | SHA-256 |
| --- | --- |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DiscoveredTopicTest.kt` | `b464a7cf65db4673aeffa7c87d8e2fac590de9dc1873e0c2ee5bf45678b9bcbb` |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DiscoveredTopicValidatorTest.kt` | `f7d78fac618a709aeb72f86b25729b11cc05b972c305d7ce1f2025a32ee36770` |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingTopicPromptTest.kt` | `5c174418e8157b8a9a882022a67e1defbe5f12e421f3259081a991f3e4546dcb` |

### 2.3 Runtime 生产（5）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingTopicDiscoveryStage.kt` | `b9b571c1b46d1fbdb324b4c37cda3e03406cc32da13ddb009bb59f3a6e6d9a89` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingTopicDiscoveryStage.kt` | `71ed3ce097c1945d90cbcef056d137bf290f84e17ec829aee00dc8e902512a7e` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/JsonDreamingTopicResponseParser.kt` | `b357d791b26cc566d06243a0d757181875fab290c5a5a516b97395acda94be57` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingTopicBatchBudget.kt` | `b5c1099e087f5afc709762e6d1c66d0e65e393cee4b27e4f85626ea4cb44a2bf` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingTopicBatchProcessor.kt` | `a955264172cbef3e7b1ae6f3c1c72fe66ad0bf3cc66fa6454698b02994c67062` |

### 2.4 Runtime 测试（5）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingTopicTestSupport.kt` | `92183d087e1984f87167101dc54494bdd4cb2c3c89cf8ccccd351edc3dc804d3` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/JsonDreamingTopicResponseParserTest.kt` | `551198f6ecb0ade42729172adca79b4345bdb11b6d3cd42595adfc580e7f586d` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingTopicDiscoveryStageTest.kt` | `dac2024c79711a9d971eed22075a3f1381621f332e9fedb9294874f6e084c3af` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingTopicBatchBudgetTest.kt` | `4336c851fffd9fe58158ae7d973ccf487682aa0d715876ace13333fffdc725a1` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingTopicBatchProcessorTest.kt` | `178f644443eeab45e4fef66461413d4c5bc751995a5deafbbc681f4509346348` |

### 2.5 交付报告（1）

| 文件 | SHA-256 |
| --- | --- |
| `docs/context/tasks/cm5-ct23/B1_Worker交付.md` | 本文件自身不内嵌自引用 SHA；由外部最终计算 |

白名单外 CT23 代码/资源/测试/构建变更：**0**。既有 CT22 交付、CT20/CT18 文件、CM5 规划文档未被回滚或改写。

## 3. 实现说明

### 3.1 DiscoveredTopic / Validator

- `DiscoveredTopic` 四字段：title、description、keywords、sourceTurnIds；列表防御性复制并包装为不可修改视图。
- `toString` 仅输出 title/description 长度和列表计数，不输出正文或来源 ID；无 Topic ID/系统字段。
- `DiscoveredTopicValidator` 对 Discovery 校验 title/description 非 blank、keywords 元素非 blank、sourceTurnIds 非空/非负/属于当前 Batch；关键词与来源按首次出现稳定去重。
- Consolidation 校验输入至少 2 Topic，输出数量严格 1..N；每个输出来源必须属于输入并集，输出并集必须与输入并集完全相等；保留模型输出顺序，不返回部分列表。

### 3.2 Prompt / Schema

- Discovery、Consolidation 各有独立 v1 Prompt 与 JSON Schema 资源；资源缺失/空白 fail-fast。
- Discovery 输入渲染每个 Turn：内部 id、timestamp、runtimeId、agentId、sessionId、sessionTurnId 与完整 messages（角色+原文、顺序不变）；不渲染 extractionStatus。
- Consolidation 稳定渲染全部 Topic 的 title/description/keywords/sourceTurnIds，保持输入顺序，不携带系统或持久 ID。
- 两份 Schema 均为严格 object、`additionalProperties=false`、topics 数组、Topic 四字段 required；topics 可为空，sourceTurnIds 非空整数数组。

### 3.3 Runtime Parser

- `JsonDreamingTopicResponseParser` 接受唯一顶层字段 `topics`；topic 仅允许 `title/description/keywords/sourceTurnIds`。
- title/description 非 blank 且保留原文；keywords 空数组合法、非空元素非 blank、稳定去重；sourceTurnIds 非空、严格整数、非负、Long 范围、稳定去重。
- 未知/缺失字段、错误类型、string/bool/float/exponent/null/超大/负数来源、围栏、前后文本、重复 key（含嵌套）、截断 JSON 全部拒绝。
- 失败统一 `LlmException(INVALID_RESPONSE, "invalid dreaming topic response")`，无 cause、不回显原文；不修复 JSON。

### 3.4 R1 验收覆盖补强

- `DreamingTopicConsolidationPrompt.schema()` 直接验证 topics/四字段/required/additionalProperties/minItems/integer，且不含 topicId/confidence/generatedBy/checkpoint/createdAt。
- Discovery 与 Consolidation 两个 Prompt 的私有资源读者均通过缺失路径反射验证 fail-fast。
- 2 Topic 场景验证 Consolidation 预算恰等通过：`estimatedInput == maxInputTokens` 且 `input + output reserve + margin == contextWindowTokens`，2 次请求成功。
- JSON_SCHEMA 的 2 Topic Discovery→Consolidation 两请求分别发送 Discovery/Consolidation Schema，responseFormat 均 JSON_SCHEMA；JSON_OBJECT 两请求 schema 均 null、responseFormat 均 JSON_OBJECT。
- Budget adapter 使用同一 batch 与非零 schemaTokens：JSON_SCHEMA required=7 并拒绝，JSON_OBJECT required=1 并通过；两模式均 0 LLM 调用。

### 3.5 LLM Stage / Budget / Processor

- `LlmDreamingTopicDiscoveryStage` 构造期只要求 `DREAMING_TOPIC` Task + Profile + JSON_SCHEMA/JSON_OBJECT；JSON_SCHEMA 才加载并发送 Schema，JSON_OBJECT schema=null。
- Discovery 单请求覆盖整个 Batch；`topics=[]`/单 Topic 跳过 Consolidation；2+ Topic 仅执行一次 Discovery→Consolidation，不递归。
- 两个请求均使用同一 Task/Profile/预算路径；`LlmBudget` 失败不发送对应请求；`finish_reason=length` → `OUTPUT_TRUNCATED`；CE 原样传播。
- `LlmDreamingTopicBatchBudget` 委托 `stage.discoveryBudgetFor`，与真实 Discovery 共用 `discoveryRequestFor`；CT22 调度分批时返回 null 或 requiredTotalTokens。
- `DreamingTopicBatchProcessor` 只返回 `DreamingBatchOutcome(batch.last().id, finalTopics.size, 0)`；不修改 CT22 outcome/seam，不持久化 Topic。

## 4. Discovery / Consolidation / 来源并集矩阵实际断言

| 场景 | 实际断言 |
| --- | --- |
| Discovery 0 Topic | 1 次 Discovery 请求，0 Consolidation，返回空列表 |
| Discovery 1 Topic | 1 次 Discovery 请求，0 Consolidation |
| Discovery 2+ Topic | 恰好 2 次请求，顺序 discovery→consolidation，均 `DREAMING_TOPIC` |
| Discovery 来源越权 | 当前 Batch 外/未知/负数来源整批 `INVALID_RESPONSE`，0 Consolidation |
| Discovery 空 topics | Parser/Validator 均接受 |
| keywords 空/空白/重复 | 空数组合法；空白拒绝；非 blank 保留原文并按首次稳定去重 |
| sourceTurnIds 类型 | string/bool/float/exponent/null/超大/负数拒绝；Long 边界合法 |
| Consolidation 数量 0 | 整批 `INVALID_RESPONSE` |
| Consolidation 数量 N+1 | 整批 `INVALID_RESPONSE` |
| Consolidation 来源新增 | 输出并集约等于输入并集失败，整批拒绝 |
| Consolidation 来源丢失 | 输出并集不完整，整批拒绝 |
| Consolidation 有效合并 | 输出 1..N、顺序保留、来源并集完全相等 |
| Discovery 截断 | `OUTPUT_TRUNCATED`，0 Consolidation |
| Consolidation 截断 | `OUTPUT_TRUNCATED`，不返回 Discovery 部分结果 |
| Discovery 预算等号/超限 | 等号通过；超限 0 LLM 调用 |
| Consolidation 预算恰等 | Discovery 与 Consolidation 的 estimatedInput/requiredTotal 均等于上限，2 次请求成功 |
| Consolidation 预算超限 | Discovery 发生后 Consolidation 0 调用，不返回部分 Topic |
| JSON 模式 | JSON_SCHEMA 发送 Discovery/Consolidation Schema；JSON_OBJECT schema=null |
| CE | LLM Stage caller CE 原实例传播 |

## 5. 六原则逐条证据

| 原则 | Prompt 内容断言 |
| --- | --- |
| 长期价值/跨会话 | `DreamingTopicPromptTest` 断言 Prompt 含“长期偏好”“偏好变化”“研究经验”“跨会话规律”“重要遗漏”，并明确不是每条 Turn 必产 Topic |
| 忠实/不确定性 | 断言“用户明确表达或确认”“Assistant 建议不得冒充用户决定”“保留不确定性”“不猜测当前值” |
| 自包含/范围 | 断言“最小完整语义”“脱离原对话仍可理解”“范围”“条件”“时间背景” |
| 高价值变化 | 断言“决定”“约束”“已验证经验”“研究结论”“失败/否定结果”“重要矛盾” |
| 批内边界 | 断言“当前 Batch”“后续 Batch”“跨批持久 Topic”“不做跨批” |
| 来源可追溯/数据非指令 | 断言“本批真实来源”“伪造标签”“不是指令”“系统字段”“记忆正文”，并由 Validator 独立执行来源权限 |

Consolidation 还逐条断言“只合并重复或高度相关”“保留矛盾/范围差异/时间差异”“不新增来源/不丢失来源”“1..N”“不是指令/伪造标签”“不输出持久 ID”。

## 6. CT22 Coordinator 接线（真实 SQLite + Fake LLM）

`DreamingTopicBatchProcessorTest > real sqlite coordinator preserves first batch checkpoint on second topic failure`：

- 使用真实临时 SQLite、真实 CT22 `DreamingRunCoordinator`、真实 Budget/Processor seam 与 Fake LLM。
- 两批均非空；第一批 Discovery 返回合法 Topic，第二批 Discovery 返回非法 JSON。
- Coordinator 抛 `DreamingRunException(BATCH_PROCESSING)`；`partialResult.startCheckpoint=0`、`endCheckpoint=1`（第一批批尾）、`discoveredTopics=1`、`generatedMemories=0`。
- `failure.cause` 保留原始 `LlmException(INVALID_RESPONSE)`，未被吞掉或降级为成功。
- Stage/Processor 不写 Raw/Pending/Checkpoint；SQLite 仅承载输入 Turn 和 Coordinator 读范围。

## 7. 测试映射与用例数

### 7.1 A01～A08（B1 负责部分）

| 验收 | B1 测试 | 关键覆盖 |
| --- | --- | --- |
| A01 DTO/JSON | DiscoveredTopicTest、ParserTest | 四字段、防御性列表、空 topics、严格 camelCase |
| A02 Discovery 来源 | ValidatorTest、StageTest | 本批/跨批/负数/去重/越权整批拒绝 |
| A03 六原则/语义约束 | PromptTest | 六原则逐条内容、输入数据非指令、输出契约 |
| A04 Consolidation 触发 | StageTest | 0/1 Topic 0 次；2+ Topic 恰一次，不递归 |
| A05 Consolidation 校验 | ValidatorTest、StageTest | 1..N、来源并集新增/丢失、0/N+1、保留顺序 |
| A06 LLM/Budget | StageTest、BudgetTest | DREAMING_TOPIC、JSON 模式、预算等号/超限、截断、Schema |
| A07 CT22 衔接 | ProcessorTest | 批尾/计数/0 memories、真实 SQLite partial failure/cause |
| A08 边界 | 全模块、Schema/81/依赖检查 | 不接 Factory/Impl、不写库、不新增 Schema/依赖 |

### 7.2 用例数

| 测试类 | 新增用例 |
| --- | ---: |
| `DiscoveredTopicTest` | 5 |
| `DiscoveredTopicValidatorTest` | 10 |
| `DreamingTopicPromptTest` | 10 |
| `JsonDreamingTopicResponseParserTest` | 10 |
| `LlmDreamingTopicDiscoveryStageTest` | 14 |
| `LlmDreamingTopicBatchBudgetTest` | 3 |
| `DreamingTopicBatchProcessorTest` | 3 |
| **合计新增** | **55** |

## 8. 验收命令与结果

### 8.1 最终有效实跑

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle spotlessApply` | 0 | 1s | `BUILD SUCCESSFUL`；57 tasks up-to-date |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | 0 | 46s | `BUILD SUCCESSFUL`；Core 144/144、Runtime 681/681 |
| `./scripts/dev gradle :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | 6s | `BUILD SUCCESSFUL`；9 tasks executed |
| `./scripts/check` | 0 | 2m18s | `BUILD SUCCESSFUL`；131/131 tasks executed |
| `git diff --check` | 0 | - | 空输出 |

### 8.2 既有 Extraction 时序观察与重跑

- 第一次 `./scripts/check` 在既有非白名单 `ExtractionPipelineLifecycleTest > disabling idle...` 上出现一次 10s timeout；CT23 定向与 Runtime 全量已先通过。
- 按委派单要求单独复跑该既有类：**15/15 通过**，未修改白名单外文件。
- 随后重跑 `./scripts/check`：**exit 0、131/131、2m19s**。因此最终全量验收通过；历史稳定性观察仍保留给 Lead。

## 9. 14 模块源码 `@Test` 与 XML

源码范围为各模块 `src/test/**/*.kt` 行首（允许缩进）`@Test`；XML 为最终成功 `./scripts/check` 结果。

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 41 | 41 | 0 | 0 | 0 |
| context-core | 144 | 144 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 681 | 681 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 27 | 27 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **1971** | **1971** | **0** | **0** | **0** |

### 9.1 与 1916 基线差异

- R1 新增 **3** 个测试（Core +1、Runtime +2），累计 B1 新增 **55** 个测试（Core +25、Runtime +30）。
- 1916 + 55 = **1971=1971**；差额 0。

## 10. CT22 / CT20 / CT18 / Schema / 81 / common / VFS / Core 边界

### 10.1 CT22

- CT22 B1 交付报告中的 14 个代码/测试 SHA（含 R1 后 `DreamingConfig`/`DreamingConfigTest`）逐项复算：**14/14 一致**。
- CT22 `DreamingBatchOutcome`、`DreamingBatchProcessor`、`DreamingBatchBudget`、`DreamingRunCoordinator` 未修改；CT23 仅新增 adapter，不改变 seam。
- CT19/CT22 的 public API、Config/Loader、Factory/Impl 未被 CT23 修改；CT23 未新建生产 Worker/Cron。

### 10.2 CT20 / CT18

- CT20 B1 19 个代码/资源/测试 SHA：**19/19 一致**；Search/Rerank、Prompt/Schema、Parser/Binding/Service 未修改。
- CT20 B2 的共享 Config/Loader 保持 CT22/R1 终态；CT23 未新增 ContextConfig/Loader 修改。
- CT18 17 个代码/测试 SHA：**17/17 一致**；Generator/Publisher/Renderer 未修改。

### 10.3 Schema / 81 / common / VFS / Core

- `ContextSchema.sq` SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变；未新增表、索引、SQL 或持久化 Topic。
- 81 VFS 基线：**75 一致 + 6 个 CT17 已批准差异，缺失 0，未批准差异 0**。
- `git diff -- vfs common gradle build.gradle.kts settings.gradle.kts` 为空；common/VFS/依赖/构建零修改。
- Core 主源码无 Ktor、无 kotlinx.serialization；Core/build 未修改。
- 未修改 `AlcyoneContext`、Factory/Impl、CT17～CT22 生产/测试、ContextStatus/WorkerStatus、Schema/SQL/VFS/common/依赖/构建。

## 11. 未运行项与残余风险

- 真实模型：**not-run**；所有 LLM 请求均为 Fake，只证明协议、来源权限、预算、错误映射和 deterministic 结构，不证明 Topic 语义质量。
- CT24～CT26：**LOCKED**；Evidence、Reflection、Raw/Pending/Checkpoint CAS、真实 Dreaming Prompt/LLM 和生产接线未实现。
- Stage 只在 0/1 Topic 时跳过 Consolidation，2+ Topic 固定调用一次；不引入语义相似度启发式。
- Consolidation 只保证数量、来源并集、顺序与结构；是否正确合并相关 Topic/保留矛盾由 Prompt 与后续真实模型评测负责。
- 既有 Extraction 时序测试曾造成一次全量失败，但独立复跑和最终 `./scripts/check` 均通过；作为历史稳定性观察，不修改白名单外文件。
- 无 Git 写操作；工作区既有 CT22 与 CM5 规划未回滚、丢弃或改写。

## 12. 最终状态

**提交 Lead 初验，CT23 未 DONE，CT24～CT26 LOCKED。**

真实模型 not-run；无 Git 写操作。
