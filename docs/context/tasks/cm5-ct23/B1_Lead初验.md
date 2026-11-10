# CT23 B1 · Lead 初验

## 本次修改（2026-11-09，B1 Lead 初验）

完成 CT23 B1（含 B1-R1）独立初验：核对 22 文件白名单、21/21 SHA、Discovery/Consolidation Prompt/Schema/Validator/Parser/Stage/Budget/Processor 实现，审查来源权限、1..N 与来源并集、两阶段预算/JSON 模式、CT22 Coordinator partial failure，并独立复跑 Core/Runtime、Spotless、全量与边界检查。结论为 **B1 初验通过，提交 Architect 独立复核；CT23 未 DONE，CT24～CT26 LOCKED**。

**日期**：2026-11-09
**状态**：**B1 IN_REVIEW；提交 Architect 独立复核**
**Worker**：`059d6c59-ce9e-4e68-9055-b45b238506a1`（`codex-magpie/group/worker`、`auto-review`、high）
**依据**：[B1 委派单](B1_委派单.md)、[B1-R1 修复委派单](B1_R1修复委派单.md)、[B1 Worker 交付](B1_Worker交付.md)、[Architect 裁定](Architect_裁定.md)、[CT23 任务书](CT23_任务书.md)、[方案核对](方案核对.md)
**基线**：CT22 APPROVED / DONE；14 模块 **1916=1916**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 1. 初验结论

**CT23 B1 Lead 初验通过，提交 Architect 独立复核。**

- Worker 严格交付 B1 委派单 §6 的 22 个白名单文件：21 个代码/资源/测试文件 + 1 份交付报告；白名单外 CT23 变更 0。
- B1-R1 只修改授权的 3 个测试文件 + 交付报告，未修改任何生产 `src/main`、资源、CT22/Config/Loader、Schema/依赖/构建。
- Core 25 + Runtime 30 = **55** 个新增测试；14 模块 fresh 源码 `@Test` 与 XML 均为 **1971=1971**，fail/error/skip = **0/0/0**。
- Discovery 来源权限、单/无/多 Topic 归并触发、Consolidation 1..N 与来源并集不增不减、两阶段预算/截断/JSON 模式、CT22 partial failure 均符合裁定。
- Prompt 六原则逐条内容断言、Consolidation Schema 严格性、两条资源 fail-fast 与 R1 覆盖补强均通过。
- 未接 Factory/Impl/AlcyoneContext；未写 Topic/Raw/Pending/Checkpoint；未改 Schema/SQL、CT17～CT22、VFS/common/依赖/构建。

## 2. 白名单与 SHA

### 2.1 文件集

- 委派单 §6 的 22 个文件全部存在；21 个代码/资源/测试文件 SHA 与 [B1 Worker 交付](B1_Worker交付.md) §2 逐项一致。
- 交付报告不内嵌自引用 SHA；Lead 复算为 `4c59ecbd1a326ea8c82b6163e35bf1f510a020663cf106b0731d1a544fb43f28`。
- 组成为：Core 生产/资源 8 + Core 测试 3 + Runtime 生产 5 + Runtime 测试 5 + 交付报告 1。
- R1 发生变化的仅为 `DreamingTopicPromptTest.kt`、`LlmDreamingTopicDiscoveryStageTest.kt`、`LlmDreamingTopicBatchBudgetTest.kt` 与交付报告；其余 18 个代码/资源/测试 SHA 未变。

### 2.2 R1 文件 SHA

| 文件 | Lead 复算 SHA-256 |
| --- | --- |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingTopicPromptTest.kt` | `5c174418e8157b8a9a882022a67e1defbe5f12e421f3259081a991f3e4546dcb` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingTopicDiscoveryStageTest.kt` | `dac2024c79711a9d971eed22075a3f1381621f332e9fedb9294874f6e084c3af` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingTopicBatchBudgetTest.kt` | `4336c851fffd9fe58158ae7d973ccf487682aa0d715876ace13333fffdc725a1` |
| `docs/context/tasks/cm5-ct23/B1_Worker交付.md` | `4c59ecbd1a326ea8c82b6163e35bf1f510a020663cf106b0731d1a544fb43f28` |

## 3. 独立实跑证据

| 命令 | exit | 耗时/任务 | 关键结果 |
| --- | ---: | --- | --- |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | 0 | 47s / 27 tasks | Core 144/144、Runtime 681/681 |
| `./scripts/dev gradle :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | 7s / 9 tasks | Core/Runtime 格式通过 |
| `./scripts/check` | 0 | 2m18s / 131 tasks | clean build、Spotless 与 14 模块测试全绿 |
| `git diff --check` | 0 | - | 空输出 |

### 3.1 14 模块 source/XML

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

B1 前 **1916**，累计新增 **55**（Core +25、Runtime +30），最终 **1971=1971**，差额 0。

## 4. 行为核对

### 4.1 DTO / Validator / Prompt

- `DiscoveredTopic` 四字段、列表防御性复制/不可变、`toString` 脱敏；不暴露内部 Turn 或系统 ID。
- Discovery 校验 title/description 非 blank、keywords 可为空但元素非 blank、sourceTurnIds 非空/非负且属于当前 Batch；关键词/来源稳定去重。
- Consolidation 要求输入 >=2、输出严格 1..N；每项来源属于输入并集，输出并集与输入并集完全相等；顺序保留、不部分返回。
- Discovery 六原则按实际内容逐条断言：长期/跨会话、忠实/不确定、自包含/范围、高价值变化、批内边界、来源/数据非指令。
- Consolidation Prompt 断言只合并相关项、保留矛盾/范围/时间、来源不增不减、无关项保留、数据非指令。
- 两份 Schema 均严格 object、Topic 四字段 required、`additionalProperties=false`；Discovery/Consolidation 资源缺失均 fail-fast。

### 4.2 Parser / Stage / Budget

- Parser 顶层只允许 `topics`，Topic 只允许四字段；未知/缺失/类型错误、围栏、前后文本、截断、重复 key 均拒绝。
- sourceTurnIds 拒绝 string/bool/float/exponent/null/超大/负数；接受 Long 边界；失败统一 `INVALID_RESPONSE`，无 cause/原文。
- Discovery 0/1 Topic 跳过 Consolidation；2+ Topic 恰调用一次 Discovery→Consolidation；两请求均 `DREAMING_TOPIC`。
- Discovery 单请求覆盖 Batch，渲染 runtime/agent/session/sessionTurnId/timestamp 与完整 messages，不含 extractionStatus。
- Discovery 与 Consolidation 均前置 `LlmBudget`；R1 验证 Consolidation 预算恰等通过、超限不发送；`finish_reason=length` → `OUTPUT_TRUNCATED`。
- JSON_SCHEMA 两阶段分别发送对应 Schema；JSON_OBJECT 两阶段 schema 均为 null；Budget adapter 证明确有 schemaTokens 只在 JSON_SCHEMA 计入。
- CE 原样传播；配置缺失/非 JSON 模式构造期 `INVALID_CONFIG`；异常/日志不含请求原文或会话元数据。

### 4.3 Consolidation 失败矩阵 / CT22 接线

- 并集新增、并集丢失、输出 0 Topic、输出 N+1 Topic 均有正式失败断言；输出有效合并的来源并集完全相等。
- `DreamingTopicBatchProcessor` 只返回批尾、最终 Topic 数、`generatedMemories=0`，不修改 CT22 outcome。
- 真实临时 SQLite + Fake LLM + CT22 `DreamingRunCoordinator`：第一批成功、第二批 Topic 失败时抛 `DreamingRunException(BATCH_PROCESSING)`；`partialResult.startCheckpoint=0`、`endCheckpoint=1`、Topic 数 1、memories 0，cause 完整保留 `LlmException(INVALID_RESPONSE)`。
- CT23 仅读输入 Turn；不写 Topic/Raw/Pending/Checkpoint。

## 5. 边界核对

### 5.1 CT22 / CT20 / CT18 / Schema / 81

- CT22 B1 Worker 报告的 14 个代码/测试 SHA：**14/14 一致**；CT22 Coordinator/seam/Config/Loader 未由 CT23 修改。
- CT20 B1 的 19 个代码/资源/测试 SHA：**19/19 一致**。
- CT18 Worker 的 17 个代码/测试 SHA：**17/17 一致**。
- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 81 项 VFS 基线：**75 一致 + 6 个 CT17 已批准差异**，缺失 0，未批准差异 0。

### 5.2 生产边界

- 未修改 `AlcyoneContext`、`ContextExceptions.kt`、Factory/Impl、`LlmTask`、ContextConfig/Loader、ContextStatus/WorkerStatus。
- VFS/common/Schema/SQL/依赖/Gradle 构建无 CT23 变更；Core 主源码无 Ktor/serialization。
- 未创建 Topic 表/Repository/Job，不跨批归并，不接生产 Worker/Cron。
- 真实模型 **not-run**；Fake 只证明协议、预算、来源权限、失败原子性和 deterministic 结构。

## 6. 观察项与残余风险

- 既有非白名单 `ExtractionPipelineLifecycleTest` 在 Worker 一次全量中出现 10s 时序超时；该既有类单独复跑 15/15 通过，Worker 重跑全量与 Lead 独立全量均 exit 0。保留历史稳定性观察，不阻断 CT23。
- Consolidation 的语义相关性、矛盾/范围保留质量只能由 Prompt 和后续真实模型评测验证；程序只保证结构、来源、数量和顺序边界。
- CT24～CT26 未实现：Evidence Scan、Reflection、Raw/Pending/Checkpoint CAS、生产接线与固定集语义评测均保持 LOCKED。

## 7. 当前状态

- **CT23 B1（含 R1）：IN_REVIEW，Lead 初验通过，提交 Architect 独立复核。**
- **CT23：IN_PROGRESS；CT24～CT26 LOCKED。**
- CM5：IN_PROGRESS；真实模型 not-run；无 Git add/commit/push/stash、分支、回滚或发布操作。
