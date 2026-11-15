# CT28 B2a-R2 关闭后 Admin Worker 状态修复裁定

## 本次修改（2026-11-11，确认真实缺陷并批准 Impl 定点修复）

- R2 正式用例证明：真实 Extraction/Organization 在 RUNNING 为 IDLE，但 close 后 `workers` 的对应槽位消失，违反稳定槽位表达 STOPPED 的验收契约；Worker 未放宽断言，暂停原因有效。
- 根因是 Admin 只从关闭时已清空的资源引用判断 capability。批准只在既有 `AlcyoneContextImpl.kt` 用不可变配置和生命周期计算槽位，不保留已关闭 Pipeline/LLM，不重新打开 DB/VFS。
- 同步明确 Dreaming/Extraction 合法 binding 条件及关闭后 pending/map/LLM/Backup 边界；B2a 仍 IN_REVIEW，R2-3 继续，B2b/c 不放行，无 Git 写操作。

## 1. 允许写集合

生产仅允许继续修改：

- `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextImpl.kt`

Factory 的 ManagedVfs 接缝已完成，不需要再改；API/Server、Schema、VFS、构建、CT27 与其他生产文件冻结。测试仍限原 R2 三个文件。

## 2. Worker 槽位存在条件

`workers` 容器本身保持非 null，四个槽位按下列“实际会组装的 capability”决定；不能仅凭默认配置对象非空误报：

| 槽位 | 条件 |
| --- | --- |
| extraction | `extractionRunner != null`，或 `llmConfig` 满足现有 `hasLegalExtractionBinding`（EXTRACTION Task、Profile 存在、JSON_SCHEMA/JSON_OBJECT） |
| organization | `organizationConfig != null`（Factory 已校验其双 LLM binding） |
| memoryMap | `memoryMapConfig != null`（Factory 已校验其 binding） |
| dreaming | `llmConfig != null` 且 `dreamingConfig.cron != null` 或存在任一 DREAMING_TOPIC/DREAMING_EVIDENCE/DREAMING_REFLECTION Task；与 `defaultDreamingRuntime` 的实际创建条件完全一致 |

Dreaming 判定抽成 Impl 私有纯函数，由 capability 与 `defaultDreamingRuntime` 共用，避免两处漂移。不得把仅有 MEMORY_SEARCH、无专属 Task 且无 Cron 的普通 LLM 配置误报为 Dreaming Worker。

## 3. 生命周期状态

- RUNNING：继续读取对应真实 Pipeline/Runtime 的 Worker 状态，不用配置覆盖实际 FAILED/RUNNING/IDLE。
- STOPPING/CLOSED：每个已配置槽位固定 `AdminWorkerStatus(WorkerState.STOPPED)`；不得调用已释放 Worker，也不保留 lastFailure 历史。
- CREATED/STARTING：已配置槽位沿用现有 IDLE 表达，未配置槽位 null。
- 不为诊断保留关闭后的 Pipeline、DreamingRuntime、KtorLlmClient 或其引用；现有资源释放测试不得修改。

## 4. 其他 Admin 字段终态

- `pending`：仅在快照中 DB 非 null 时真实读取；CLOSED/DB 已释放为 null。读取失败仍按原异常/CE语义传播，不伪 0。
- `dreaming.lastProcessedTurnId`：仅在 DB 可读且 dreaming capability 存在时读取；CLOSED 为 null，不伪造 checkpoint。
- `mapStatus`：仅从仍持有的真实 Map 快照读取；CLOSED 为 null，不用 UNKNOWN 冒充已持久化 Map 内容。
- `llm`：仅从仍持有的 client 读取；CLOSED 为 null，不复制/保留已释放 client。
- `backup`：保留现有 `backupStatusReader` 语义；曾经成功发布该 reader 时，关闭后仍可输出其真实 STOPPED 和进程内 lastUri/lastCompletedAt/lastFailureStage，CREATED 或从未发布时为 null。不得重新构造 BackupRuntime，也不得把 lastFailure 清空。
- 外部 CancellationException 原样传播，其余 Admin 查询/资源异常不因本修复改成业务默认值。

## 5. 必须补强的回归

在原 `AlcyoneContextAdminStatusTest.kt` 内同时证明正负条件：

1. 已配置 Extraction/Organization 的 RUNNING 真实 IDLE 与 CLOSED STOPPED 原用例通过。
2. 已配置 Dreaming/Map 的 CLOSED 槽位 STOPPED；未配置槽位 null。
3. 无 LLM、PROMPT_ONLY/缺 EXTRACTION Task 或未知 Profile 不产生 Extraction 槽位；普通 MEMORY_SEARCH-only 且无 Cron 不产生 Dreaming 槽位。
4. CLOSED 后 pending/dreaming checkpoint/mapStatus/llm 为 null；曾运行 Backup 的状态与最后发布事实仍可读取，且没有保留 LLM/Pipeline 引用。

不得改变公共 DTO/Server 来绕过 Runtime 缺陷。完成 R2-1～R2-3 后，由 Lead 独立复跑原要求的四模块与生命周期/Map/Backup 回归，再交 Architect；任何新的越界需求继续暂停上报。
