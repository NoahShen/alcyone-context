# CT25 B1 · Reflection 与原子 Commit Worker 交付

## 0. 本次修改（2026-11-09，B1 Lead PASS / Architect APPROVED / DONE）

- [Lead 初验](B1_Lead初验.md)独立复跑定向测试与 Spotless 全绿，复算 14/14 交付 SHA、7/7 基线 SHA；三模块 XML 1140=1140，fail/error/skip=0/0/0。
- [Architect 复核](B1_Architect复核.md)结论 **B1 APPROVED / DONE**。本 Worker 报告原有实现、测试与全量检查证据不变；**CT25 未 DONE，B2/CT26 LOCKED**。

## 本次修改（2026-11-09，B1 初始交付）

严格完成 B1 委派单 §6 的 15 个文件：**14 个代码/资源/测试文件 + 本交付报告**。新增 Reflection DTO/Validator、四类证据 Prompt/严格 Schema、严格 JSON Parser、`LlmDreamingReflectionStage`、`DREAMING_REFLECTION` Binding，以及 Raw(DREAMING)+Pending+Checkpoint CAS 单事务 `DreamingCommitter` 和真实 SQLite 测试。新增 45 个测试，最终 14 模块源码 `@Test` 与 XML 均为 **2108=2108**，fail/error/skip=**0/0/0**；Core/Runtime/Persistence 定向、白名单 Spotless、全量 `./scripts/check` 均通过。

**日期**：2026-11-09
**状态**：**B1 Lead PASS / Architect APPROVED / DONE；CT25 未 DONE；B2/CT26 LOCKED**
**依据**：[B1 委派单](B1_委派单.md)、[Architect 裁定](Architect_裁定.md)、[CT25 任务书](CT25_任务书.md)、[方案核对](方案核对.md)、[CT24 B2 Worker 交付](../cm5-ct24/B2_Worker交付.md)
**基线**：CT24 APPROVED / DONE；14 模块 **2063=2063**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；Git `f391192b91da72878908ad7230ba1ef515cff43a`。工作区既有 CT22～CT24 交付、CM5 规划文档和开发计划保持不动。

## 1. 交付结论

- Reflection 只对 `NEEDS_REFLECTION` Topic 发起一次调用，`FULLY_COVERED` 直接跳过；多 Topic 串行，任一失败不返回部分 Insight。
- Prompt 明确覆盖证据边界、Organized 基准、事实时间、最新确认优先、同源去重、无增量返回空数组、严格 JSON 七类原则。
- `ValidatedDreamingInsight` 只保留正文与稳定 `ConversationTurnRef` 四元业务键；模型不能生成 Raw ID、origin、时间或 checkpoint。
- Reflection 请求使用 `DREAMING_REFLECTION`，预算复用 `LlmBudget`，严格复用既有 Profile/Task 配置；截断、非法 JSON、未知来源均 fail closed。
- `DreamingCommitter` 在一个写事务内完成全部 Raw、逐条 Pending 与 Checkpoint CAS；零 Insight 仍推进 checkpoint。
- 只有事务内确定的 Raw 主键预检碰撞触发整批 ID/Clock 重建，默认最多 3 次；其他数据库异常不重试，CE 原样传播。
- 未接 CT22 Processor/完整流水线，未接 Factory/Impl/AlcyoneContext，未实现 COMMIT 后通知，未运行真实模型，无任何 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 Core 生产与资源（6）

| 文件 | SHA-256 |
| --- | --- |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingReflection.kt` | `9f66042a60a2402102d57f20975b1e1b1a85aeb3894546381022024916947d3c` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingReflectionPrompt.kt` | `430056ca5eb085fd56c7eb5e55d1af0f19db9250972960299f689ef6b58c6e1e` |
| `context/core/src/main/resources/prompts/dreaming-reflection-v1.md` | `516f2b6b468bb691eccba6afa0124abc500b94e06a7151f9b5149ea3f8f12c04` |
| `context/core/src/main/resources/prompts/dreaming-reflection-schema-v1.json` | `120051b4ed851cb21cd325ae196112bad5671ac88a3a119ed7a7600b3203ea4e` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingCommit.kt` | `886f2b8d766862bd5281bd19d42cc934cf67d7a27c5ed762d8397ae67d14229f` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingCommitter.kt` | `bb8380a14694cc1f95554afa0fae810451c2f506bec2e5ade1743f01fe2bc8b9` |

### 2.2 Core 测试（2）

| 文件 | SHA-256 |
| --- | --- |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingReflectionTest.kt` | `a797b3a0c1ff15c5c9532bb6f4a5ff3d0f59733142b03b4c8a92552576b11346` |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingReflectionPromptTest.kt` | `39aae325e31fb807a0488a86a781b97f0125b4269558d85f3bda46a13a45daac` |

### 2.3 Runtime 生产（3）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/JsonDreamingReflectionParser.kt` | `fbfad6478672ba5cd586791628f30652899ca6e0cbba566423628679d35b3df8` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingReflectionBinding.kt` | `727a281ad2ece3d9071b27b81130b79dcf24b2dddadf651f75d03798773691e2` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingReflectionStage.kt` | `f582c0af6af213977d3bf8ed22a3ab3e191d75da8e32a15d71640fca6600a3c3` |

### 2.4 Runtime 测试（3）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/JsonDreamingReflectionParserTest.kt` | `4f0950586a3f1116cdf4ff4116c8684f69bcedcf65acd4fa7f7f9ccb9880527d` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingReflectionStageTest.kt` | `2756eebd042ea5adfa5d620f38633f63a276e3ff9c7ec009b00be028b7934cf0` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingCommitterRealDbTest.kt` | `95e48ca65941f3881e7e1f6d5f32be313cab26a8d638efeb2656216e8d5a3b15` |

### 2.5 交付报告（1）

| 文件 | SHA-256 |
| --- | --- |
| `docs/context/tasks/cm5-ct25/B1_Worker交付.md` | 本文件自身不内嵌自引用 SHA；由外部最终计算 |

白名单外 CT25 B1 代码/资源/测试/构建变更：**0**。既有 CT22～CT24 交付与规划文档未被回滚、丢弃或改写。

## 3. 实现说明

### 3.1 Reflection DTO / Validator

- `DreamingInsightCandidate` 与 `ValidatedDreamingInsight` 对来源列表做防御性不可修改拷贝；`toString` 只含正文长度/来源计数，不泄露正文、内部 ID 或业务键。
- Validator 对当前 Topic 的 new/historical Turns 建立内部 ID 与四元业务键映射；同 ID 异键、同键异 ID 均拒绝。
- blank 正文、空来源、非正数或越权来源使当前 Topic 整批失败；重复内部 ID 稳定去重，保留模型 Insight 与首次来源顺序。
- 输出仅包含正文与 `ConversationTurnRef(runtimeId, agentId, sessionId, sessionTurnId)`，不把 SQLite 内部 ID 写入 Raw 来源。

### 3.2 Prompt / Schema / Rendering

- Prompt 只使用当前 Topic 的 new Conversation、当前 Organized、候选 Raw、historical Conversation 四类证据；所有正文均为数据而非指令。
- 明确 Organized 是当前认知基准而非独立原始证据，旧 Raw 被重新召回不会自动成为当前事实，记录时间不等于事实发生时间。
- 明确用户纠正/后续变化/已核实更新优先，已纠正旧事实不得恢复；同一 Conversation 多 Raw 引用仍是一份同源证据。
- 无实质增量输出 `{"insights":[]}`；未确认信息保留不确定性，只有新可靠原始证据或经核实的新发现才输出。
- User 输入完整携带 Turn 内部 ID、四元业务键、messages、Organized/Raw 正文及关联字段；正文用 CDATA 安全分段，业务键做 XML 属性转义，稳定排序可复现。
- Schema 顶层仅 `insights`；每项严格 `content` + 非空整数 `sourceTurnIds`，`additionalProperties=false`；无 confidence/domain/Raw ID/Organized ID/checkpoint/generatedBy。
- Prompt 与 Schema 资源缺失或空白均 fail-fast。

### 3.3 Strict Parser / Binding / Stage

- Parser 要求唯一顶层字段 `insights`，Insight 字段恰好 `content/sourceTurnIds`；正文保留原始空白但不得 blank，来源必须是非空严格整数 Long 数组。
- unknown/missing/type、string/bool/float/exponent/null/超大整数、围栏、前后解释、截断 JSON、任意层级重复 key（含转义等价 key）全部拒绝，不修复 JSON。
- 所有 Parser/来源失败统一安全 `LlmException(INVALID_RESPONSE)`，任务归属 `DREAMING_REFLECTION`，不携带正文、SQL、cause 或来源键。
- Binding 解析现有 `DREAMING_REFLECTION` Task/Profile，支持 `JSON_SCHEMA` 与 `JSON_OBJECT`；缺 Task/Profile 或 mode 不支持时 fail-fast，错误仅含配置路径。
- JSON_SCHEMA 模式发送并计入完整 Schema Token；JSON_OBJECT 模式不发送 Schema，也不计 Schema Token。
- 预算复用 `LlmBudget.requireWithinBudget`：估算恰等于上限通过，超 Task 输入或 Profile context window 时在零 HTTP 调用下抛 `LOCAL_BUDGET_EXCEEDED`；安全余量使用既有配置值。
- `FULLY_COVERED` 零调用；每个 `NEEDS_REFLECTION` 恰调用一次并串行处理；`finish_reason=length` 映射 `OUTPUT_TRUNCATED`；后续 Topic 失败不返回先前部分结果；CE 原样传播并停止后续 Topic。
- Client 已分类异常不改写任务归属；Stage 自身生成的安全异常补全 `DREAMING_REFLECTION`、profile、model。

### 3.4 DreamingCommitter

- `DreamingCommitContext` 要求 `expectedCheckpoint >= 0`、`upperBound >= 0`、`expectedCheckpoint < lastTurnId <= upperBound`；不假设 Turn ID 连续。
- 每次尝试在事务外取一次 Clock 并生成整批 Raw ID；同次尝试所有 Raw 共享时间，origin 固定 `DREAMING`，来源来自已校验稳定四元业务键。
- 同一尝试内重复 ID、ID 生成异常或耗尽均在写事务前安全失败；普通异常不暴露 ID、正文或 cause。
- 写事务内先核对权威 checkpoint 等于 expected，再预检全部 Raw ID；随后 `insertBatch` 行数必须等于 Insight 数，每条 Pending enqueue 必须等于 1，CAS 到实际 `lastTurnId` 必须恰更新 1 行。
- 任一步失败由真实事务回滚 Raw、Pending 与 Checkpoint；Conversation `extraction_status` 不被修改。零 Insight 不生成 ID、不写 Raw/Pending，但仍在一次写事务内 CAS 推进。
- 只有私有 `RawIdCollision` 预检信号触发重试；重试重新生成整批 ID 和时间，不覆盖既有 Raw，默认最多 3 次。非碰撞数据库异常只写一次，CE 不包装。

## 4. A09 / A10 测试覆盖

| 验收项 | 正式测试覆盖 |
| --- | --- |
| A09 DTO/Validator | 合法转换、空正文/来源、0/负数/越权 ID、稳定去重、四元键精确性、同 ID 异键/同键异 ID、不可变与脱敏 |
| A09 Prompt | 七类原则、四类证据字段、CDATA 与 XML 属性边界、稳定渲染、严格 Schema、资源 fail-fast |
| A09 Parser | 空/多 Insight、严格字段/类型、原始正文、整数 Long 边界、围栏/解释/截断、嵌套与转义重复 key、安全异常 |
| A09 Stage | skip/每 Topic 一次/串行、部分失败、越权来源、Task/format/Schema/output budget、JSON_OBJECT 与 JSON_SCHEMA 差异、预算等号与超限、截断、CE、Binding 错误 |
| A10 Commit | 多 Raw+Pending+CAS、DREAMING/同批 Clock/稳定来源、0 Insight、ID 空洞、非法范围、CAS 冲突、insert/Pending/CAS 故障回滚、ID 失败/重复、碰撞恢复/3 次耗尽、非碰撞不重试、CE、Extraction 状态不变 |

B1 新增测试 **45**：Core 14（DTO/Validator 9 + Prompt 5），Runtime 31（Parser 8 + Stage 10 + 真实 SQLite Commit 13）。Persistence 增量为 0。

## 5. 验收命令

三条命令均在正常环境执行；沙箱内 Gradle FileLock socket 不可用，未修改脚本或依赖规避。

| 命令 | exit | 耗时 | 结果 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle :context:core:test :context:runtime:test :context:persistence-sqldelight:test --rerun-tasks --console=plain` | 0 | 1m13s | `BUILD SUCCESSFUL`；29/29 tasks executed |
| `./scripts/dev gradle :context:core:spotlessCheck :context:runtime:spotlessCheck :context:persistence-sqldelight:spotlessCheck --rerun-tasks --console=plain` | 0 | 10s | `BUILD SUCCESSFUL`；13/13 tasks executed |
| `./scripts/check` | 0 | 2m25s | `BUILD SUCCESSFUL`；131/131 tasks executed |
| `git diff --check` | 0 | - | 空输出 |

## 6. 14 模块 fresh source @Test / XML

源码范围为各模块 `src/test/**/*.kt` 行首（允许缩进）`@Test`；XML 为最终成功 `./scripts/check` 结果。

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 41 | 41 | 0 | 0 | 0 |
| context-core | 179 | 179 | 0 | 0 | 0 |
| context-persistence | 183 | 183 | 0 | 0 | 0 |
| context-runtime | 778 | 778 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 27 | 27 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| **合计** | **2108** | **2108** | **0** | **0** | **0** |

- CT24 B2 基线 2063 + Core 14 + Runtime 31 = **2108=2108**，差额 0。
- Persistence 183 保持不变；其余模块计数均与基线一致。

## 7. 边界与基线

- 按 CT22/CT23/CT24 B1/B2 交付报告逐项复算最新代码/资源/测试 SHA：**71/71 一致**；未修改既有 Coordinator、Topic/Evidence Stage、Config/Loader 或 CT24 Collector/Candidate/Binding/Parser。
- `ContextSchema.sq` SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变；零 Schema/DDL/DML/迁移/索引修改。
- `common`、`vfs`、`integration-tests/vfs`、`gradle`、根构建文件无 B1 diff；未新增依赖。
- Core 主源码无 Ktor、无 kotlinx.serialization；Core/build 零修改。
- 未修改 `AlcyoneContext`、Factory/Impl、ContextStatus/WorkerStatus、Extraction/Organization 主链、ContextConfig/Loader 或 VFS。

## 8. 未运行项与残余风险

- 真实模型：**not-run**；仅使用 Fake LLM/受控 TokenEstimator，不能证明 Reflection 的新增量、纠错、同源合并或语义质量。
- CT26 固定 fixture 评测：**LOCKED / not-run**；FULLY_COVERED 与 NEEDS_REFLECTION 的语义决策仍由 CT24/Prompt 和后续评测负责。
- B2 完整 Batch Processor、提交后 Organization 通知、partial failure 接缝：**LOCKED**；本 B1 不宣称可运行完整 Dreaming。
- 未接 Factory/Impl/AlcyoneContext，未创建生产 Dreaming Worker/Cron，无生产入口或 Server 接口变化。
- Committer 的原子性已由真实 SQLite 故障注入验证；多进程竞争下的更宽并发压力、数据库锁等待与吞吐性能未做专项压测。
- 无 Git 写操作；工作区既有 CM5 规划文档与开发计划修改未回滚、丢弃或改写。

## 9. 最终状态

**B1 Lead PASS / Architect APPROVED / DONE；CT25 未 DONE，B2/CT26 LOCKED。**

