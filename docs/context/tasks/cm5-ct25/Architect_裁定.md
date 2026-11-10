# CT25 · Architect 裁定（D1～D21）

## 0. 本次修改（2026-11-09，B2 白名单与 expected 链澄清）

Architect 复核 B2 精确白名单并确认：原 §2“每批 expected 均保持 C0”与 B1 已批准的逐批 Checkpoint CAS 冲突，属于 D2/D12/D18 的必要勘误。现明确同一 run 固定 U，首批 expected=C0，随后每批 expected=前一批已提交且校验通过的 `outcome.lastTurnId`；成功批之间 Coordinator 不另读 DB checkpoint，每批 Committer 仍事务内核对并 CAS。COMMIT 后通知 CE 原样中止 run，下一 run 从 DB 权威 checkpoint 恢复，不以 partial 重做旧批。详见 [B2 白名单复核](B2_白名单复核.md)。

本次仅做上述文档澄清与 B2 白名单放行；不改生产/测试/构建，不启动 CT26 或真实模型，无 Git 写操作。

## 本次修改（2026-11-09，D1～D21 初裁）

Architect 独立阅读技术设计 §8.5～8.8、CT22 Coordinator/Processor、CT23 Stage、CT24 Evidence DTO、Extraction Committer/Wakeup、Core TransactionContext 与 Raw/Pending/Checkpoint TxOps 后，对 CT25 方案 D1～D21 逐项终裁，并明确四个重点问题。

**日期**：2026-11-09
**状态**：**APPROVED（含本裁定明确化）／放行 CT25 B1**
**裁定对象**：[CT25 任务书](CT25_任务书.md)、[方案核对](方案核对.md)
**基线**：CT24 APPROVED / DONE；14 模块 **2057=2057**；Schema 保持 `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`
**边界**：本次只写裁定文档；不改生产/测试/构建，不派发 Worker，无 Git 写操作。

## 1. 独立事实核实

| 事实 | 核实结论与裁定影响 |
| --- | --- |
| CT22 固定范围与 seam | `DreamingRunCoordinator` 在同一 `ReadContext` 读取 `checkpoint/maxTurnId`，并验证实际批尾；`DreamingBatchProcessor.process` 当前只有 Turn 列表。CAS 所需 `expectedCheckpoint/U` 必须显式传递，不能由含空洞的 ID 列表推导，也不能在 Processor 中另起普通读取代替 run 固定范围。 |
| Coordinator 部分结果 | 普通 Processor 失败包装为 `BATCH_PROCESSING`，保留此前成功批；CE 原样传播。CT25 可复用，不需新错误协议。 |
| Evidence 决策 | CT24 `DreamingEvidenceDecision` 已有 `FULLY_COVERED/NEEDS_REFLECTION`；Evidence 分离 new/historical Conversation、Organized、Raw。FULLY_COVERED 应跳过 Reflection，NEEDS 即使没有 Raw/历史也可依据本批原文。 |
| 唤醒接口 | 实际是 Runtime internal `OrganizationWakeup.onRawCommitted(): Unit`，不是方案中的 `requestWakeup()` 或 Boolean 返回。CT25 直接复用该接口，不新建重复 Core Port。 |
| Pending 端口 | 实际只有 `OrganizationPendingTxOps.enqueue(rawId): Int`，没有 `enqueueBatch`。CT25 在同一事务逐条 enqueue 并逐条校验返回 1。 |
| Checkpoint CAS | `DreamingCheckpointTxOps.casUpdateCheckpoint(expected,next)` 要求严格前进并返回行数；事务上下文同时提供 `readCheckpoint()`，CAS 前必须显式核对 expected。 |
| Extraction 重试 | 现有 `ExtractionCommitter` 只对事务内预检命中 Raw ID 的私有碰撞信号重试；其他 DB 异常原样传播。CT25 沿用确定碰撞模式，不依据任意 SQL 文本猜测重试。 |
| LLM 资源 | `DREAMING_REFLECTION` 已存在；CT23/24 已有构造期配置、严格 JSON、预算、截断模式，可复用，不新增 enum/config。 |
| 生产接线 | CT22～CT24 均保持组件化，公共 Factory/Impl 尚无 Dreaming 组装。CT25 先完成真实组件链；公共生命周期与 CM5 集成归 CT26。 |

## 2. 四个重点终裁

| 重点 | 终裁 |
| --- | --- |
| CT22 `DreamingBatchContext` 扩展 | **批准且必需；B2 白名单复核已勘误 expected 链**。B2 将 Processor 唯一入口改为 `process(context: DreamingBatchContext)`；上下文含 `expectedCheckpoint/upperBound/turns`。Coordinator 在同一固定 run 内传递起始 `C0` 与固定 `U`：**首批 expected=C0，随后 expected=前一批已提交且校验通过的 `outcome.lastTurnId`，upper 全 run 固定**；成功批之间不另读 checkpoint，每批 Committer 事务内核对并 CAS。删除原生产签名，**不保留任何仅收 turns 的便捷重载**；同步调整 internal 测试 fake/旧 CT23 零写入 adapter。 |
| B1/B2 拆分 | **批准**。B1 只做 Reflection DTO/Validator/Prompt/Parser/Stage + DreamingCommitter 与定向真实 SQLite 测试，不接 Coordinator、不通知。B2 才扩展 CT22 上下文、实现完整 Processor、提交后唤醒与全量验收。B1 经 Lead 初验及 Architect 复核通过才放行 B2。 |
| 通知普通失败与 CE | **普通非 CE 异常捕获，只记录固定脱敏 warning，不回滚、不重试、不重复保存**；`CancellationException` **原样传播**。COMMIT 后 CE 可能使本次 `DreamingRunException`/partial 不宣称该批成功，但数据库 Checkpoint 已推进；下一次 run 必须从权威 checkpoint 开始，禁止以内存 partial 重做旧批。通知不置于 NonCancellable，不用 CE 触发回滚。与现有 `DefaultExtractionRunner.notifyRawCommitted` 语义一致。 |
| Factory/Impl 是否留 CT26 | **留 CT26**。CT25 不改公共 `AlcyoneContextFactory/AlcyoneContextImpl`、公共 create 签名或生产 Cron 生命周期；B2 用真实 Coordinator+Processor+SQLite+Fake LLM 验证完整组件链。CT26 再接生产组装、关闭取消、独立子进程重启与固定集评测。 |

## 3. D1～D21 逐项裁定

| # | 裁定 |
| --- | --- |
| D1 | **APPROVED**：按 B1 Reflection+Committer、B2 完整 Processor+通知+验收拆分；两批独立白名单、初验、Architect 复核。 |
| D2 | **APPROVED WITH CLARIFICATION**：新增 Runtime internal `DreamingBatchContext(expectedCheckpoint, upperBound, turns)`；唯一 Processor 入口接收该对象。构造要求 turns 非空、严格 ID 升序、首 ID>expected、尾 ID<=upper；由 Coordinator 保证同一固定范围。 |
| D3 | **APPROVED**：保留 CT22 调度/分页/预算/结果聚合；旧 CT23 零写入 adapter 只保留明确的历史组件测试。不得用于 CT25 完整链或生产；Processor 只有实际提交成功才返回 outcome。 |
| D4 | **APPROVED**：Core `DreamingInsightCandidate(content,sourceTurnIds)` 与 validated DTO（完整 `ConversationTurnRef`）；Runtime Evidence 输入按稳定业务键建立唯一 ID 映射。 |
| D5 | **APPROVED**：空 insights 合法；content 非空白且保存原文；sourceTurnIds 非空、Long 整数、输入 Turn 子集、按首次稳定去重；任一非法整批拒绝。 |
| D6 | **APPROVED**：同业务键不同内部 ID、同 ID 不同业务键、重复业务键正文/身份不一致均 fail closed。CT25 只消费 CT24 已核验 Evidence，不重新发现 Topic。 |
| D7 | **APPROVED**：每个 NEEDS Topic 一次 Reflection；FULLY_COVERED 0 调用；任一 Topic 失败全批失败；无递归。 |
| D8 | **APPROVED**：仅渲染该 Topic 四类证据及必要来源关系、内部 ID/业务键/正文/时间；数据非指令。不得把其他 Topic 的证据纳入来源白名单。 |
| D9 | **APPROVED**：Prompt 必须逐条内容级断言 Organized 非独立原始证据、旧 Raw 非当前事实、事实时间优先、用户纠正/后续变化优先、同 Turn 多 Raw 非独立证据、无增量为空、不确定性保留。 |
| D10 | **APPROVED**：顶层与 Insight 字段严格限于设计；camelCase；禁止 Raw/Organized ID 和系统字段；严格 JSON、无围栏/解释/重复 key。 |
| D11 | **APPROVED**：复用 `DREAMING_REFLECTION` 与既有预算/配置/截断模式；全证据超预算即失败，不删证据降级。 |
| D12 | **APPROVED**：`DreamingCommitContext` 显式携带 expected/U/last；事务前与事务内均检查 `0<=expected<last<=upper`。B2 中 last 必须等于 context.turns 尾 ID。 |
| D13 | **APPROVED WITH FACT CORRECTION**：一次短 `database.write`：显式 `readCheckpoint()==expected`；逐 Raw 预检 ID；`insertBatch` 行数精确；对每条 Raw 调 `pending.enqueue(raw.id)==1`；CAS 返回恰 1。没有 `enqueueBatch`，不得虚设端口。 |
| D14 | **APPROVED**：0 Topic/0 Insight 也执行短写事务和 CAS；不生成 ID、不写 Raw/Pending。 |
| D15 | **APPROVED**：同批统一 Clock，每 Raw 新 ID、`DREAMING`、稳定 ConversationTurnRef；模型不生成系统字段。重试可重新取同批 Clock，但单次尝试内必须一致。 |
| D16 | **APPROVED**：只对事务内确定的主键预检碰撞重新生成整批 ID，默认最多 3 次；普通 DB/SQL/约束错误不猜测重试；耗尽无新部分写入。 |
| D17 | **APPROVED**：Core `DreamingCommitException` 仅含安全原因、不挂原始 SQL cause；CE 原样传播。范围/CAS/事务失败均无本批新 Raw/Pending。 |
| D18 | **APPROVED WITH FACT CORRECTION**：复用 `OrganizationWakeup.onRawCommitted()`，不再建平行 `DreamingOrganizationNotifier` 抽象，除非仅作为私有包装且不增加契约。rawCount>0 且 COMMIT 成功后调用一次；普通异常固定 warning，CE 原样。 |
| D19 | **APPROVED**：`LlmDreamingBatchProcessor` 顺序执行 Evidence→Reflection→全量校验→Commit→Wakeup→真实 outcome；事务外无权威写，提交后不重复保存。 |
| D20 | **APPROVED**：公共 Factory/Impl/Cron 留 CT26；CT25 B2 完成真实组件集成即可，不以公共生产接线作为本批验收项。 |
| D21 | **APPROVED**：不改 Schema/SQL/VFS/common/依赖/公开 SDK/Extraction 状态；不改 CT23/24 语义；不新增持久 Topic/Journal/Deferred/恢复机制。B2 允许必要修改 CT22/23 internal seam 与其测试，但不允许借此改变已验收的调度、Topic/Evidence 业务语义。 |

## 4. B1 白名单方向

B1 预计只允许新增/修改以下方向，精确路径由 Lead 委派单逐项冻结：

### Core 生产与资源

- `context/core/src/main/kotlin/.../core/dreaming/`：Reflection candidate/validated DTO、严格来源 Validator、Reflection Prompt、`DreamingCommitContext/Result/Exception/Committer`。
- `context/core/src/main/resources/prompts/dreaming-reflection-v1.md`
- `context/core/src/main/resources/prompts/dreaming-reflection-schema-v1.json`

### Runtime 生产

- `context/runtime/src/main/kotlin/.../runtime/dreaming/`：Reflection binding、strict JSON Parser、`LlmDreamingReflectionStage` 及必要 internal 接口。
- **B1 不改** `DreamingRunCoordinator.kt`、`DreamingBatchProcessor.kt`、CT23/24 生产组件。

### 测试与文档

- `context/core/src/test/kotlin/.../core/dreaming/`：Prompt 内容级断言、Validator、真实 SQLite Committer 故障矩阵。
- `context/runtime/src/test/kotlin/.../runtime/dreaming/`：Reflection Parser/Stage/binding/预算/截断/CE 测试及必要测试支持。
- `docs/context/tasks/cm5-ct25/`：委派单、Worker 交付、Lead 初验、Architect 复核。

### B1 明确禁止

- 不接 Coordinator/Factory/Impl，不调用 Organization wakeup。
- 不改 Persistence 生产代码、Schema/SQL、CT22～CT24 生产代码、公开 API/server/integration-tests/VFS/common/Gradle。
- 不实现完整 `LlmDreamingBatchProcessor`，不启动 B2，不运行真实模型。

## 5. B1 必须通过的验收重点

1. Reflection Prompt 不是只数标题：逐条断言七类纠错/证据原则与严格输出协议。
2. JSON/来源严格矩阵：空结果、非空 content、Long ID、输入子集、稳定去重、整批拒绝、稳定四元 Ref。
3. covered skip、needs 一次、Topic 失败整批失败；DREAMING_REFLECTION binding、JSON_SCHEMA/OBJECT、预算等号/超限、length、CE。
4. 真实 SQLite：Raw(DREAMING)+逐条 Pending+CAS 全成或全败；0 结果推进；expected/U/last 边界；CAS 冲突；Raw/Pending/Checkpoint 故障注入；确定 ID 碰撞重试与耗尽；Conversation extraction_status 不变。
5. B1 基线不允许减少既有 CT22～CT24 测试；运行 core/runtime/persistence 定向测试与 spotless。B1 不接完整链，不伪称 CT25 DONE。

## 6. 结论

- **CT25 方案 APPROVED（以上述明确化为准），可冻结委派单并派发 B1。**
- 四处事实/边界纠正为强制要求：唯一 BatchContext 入口、复用 `onRawCommitted()`、逐条 `enqueue()`、Factory/Impl 留 CT26。
- B1 通过 Architect 复核后才放行 B2；CT25 未 DONE，CT26 LOCKED。
- 真实模型 not-run；无 Git 写操作。
