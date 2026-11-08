# CM3 范围、CT13～CT16 依赖与待裁定问题（方案，不开发）

**日期**：2026-11-01
**状态**：**CM3 DONE（CT13/CT14/CT15/CT16 均已 Architect 复核通过）；CM4 不启动**
**依据**：`docs/context/开发计划.md` §CM3、技术实现设计 §6.1～§6.10/§10、核心数据流 §13、CM2 已验收 LLM/Extraction 能力
**基线**：`main` / `origin/main` `9723bb7`；CM1+CM2 已提交推送；当前全量 1441=1441（CT12 DONE 时）

## 0. 本次修改（2026-11-01，CM3 方案建立）

用户明确授权 CM3（仅 CT13～CT16，不启动 CM4）。本文件先做范围/依赖/API/配置/Prompt/Repository 缺口核对，并列出需 Architect 裁定的普通实现选型；CT13 只读核对另见 [CT13 方案核对](../../tasks/cm3-ct13/方案核对.md) 与 [CT13 任务书](CT13_任务书.md)。**本轮不派发 Worker（历史状态）。** 2026-11-01 Architect 已审阅并裁定：CT13 B1 放行，CT13 局部 Q1～Q8 批准；Q10/Q13 未冻结，留 CT16 前裁定；详见 [Architect 裁定](../../tasks/cm3-ct13/Architect_裁定.md)。

## 0.1 本次修改（2026-11-01，按 Architect 裁定同步）

- CT13 局部 Q1～Q8 全部批准，并追加：换批后必须重新检查单条预算；后续超大项不得返回部分候选；覆盖 >1 页/整页倍数/分页间并发快照；LLM 前释放 ReadContext，LLM 回调内可完成独立 DB 写；批间/长预算循环可响应取消；Prompt 内容/Schema 正式测试；允许通用预算分批小组件（文件须在委派单列明）。
- Q10 时区：**未冻结**；CT16 先核对现行定稿与 Extraction 固定上海约定，给出是否确需可配置的依据后再裁定；暂不增加 `timezone` 字段。
- Q11 关闭顺序：**先同步封闭准入，再停 Cron/取消并 join 在途工作，最后释放资源并发布共享关闭结果**；不能先停 Cron 才封闭准入。计数饱和须可观察、无溢出、不丢唯一 Worker 清理责任。
- Q13：**不批准**“任何不完整/非法绑定都静默 no-op”。CT16 必须区分“未配置 Organization”与“已配置但不完整/非法”，评估启动诊断/兼容/启用规则；不得为旧测试静默失效。
- 本次仅方案审批，不构成 CT13 实现验收；CT13 未 DONE。

## 0.2 本次修改（2026-11-07，CT15 DONE，CT16 只读方案）

- Architect 复核 **CT15 B1/CT15 APPROVED / DONE**：5 文件/29 例、定向 29/0/0/0、`./scripts/check` 131 tasks、14 模块 1587=1587、81 基线/Core/Schema/生产边界通过。
- 已落 [CT16 任务书](CT16_任务书.md) 与 [CT16 方案核对](../../tasks/cm3-ct16/方案核对.md)，锁定 Immediate/Periodic、单 Worker/Counter、固定 Pending、Source 预检、Map 接缝、生产生命周期与 A01～A08。
- CT16 配置启用门、时区、Pending 列表、Scheduler/Counter、失败退避、生命周期、状态及 B1/B2 白名单形成 D1～D14，待 Architect 裁定；本轮未派发 Worker、未改生产/测试代码、无 git。

## 1. CM3 范围（Memory Organization，CT13～CT16）

```text
Extraction / Dreaming COMMIT（Raw + organization_pending）
  → Immediate Trigger；Periodic Cron 补偿
  → 单 Worker / 全局 Trigger Counter
  → 每轮固定 Pending ID 列表（raw.created_at ASC, raw.id ASC）
  → 每条：来源幂等检查
       已关联 → 短事务清残留 Pending，跳过 LLM
       未关联 → Scanner 全量候选 → Governance → Core 校验
  → 单 WriteSlot 原子提交 Organized/Source/水位/Pending
  → 单条失败跳过，继续本轮；新入队留后轮
```

| 任务 | 范围 | 关键产出 |
| --- | --- | --- |
| CT13 通用 Scanner 与 Candidate Retriever | 全量 Organized 快照、Token 动态分批、串行 LLM、批内 ID 校验、稳定去重 | 空集合不调 LLM；全批覆盖；越批 ID 拒绝；任一批失败整条失败；不硬过滤 Domain/Agent；不静默截断 |
| CT14 Governance 与决策校验 | 独立 Prompt、NEW/MERGE/UPDATE、严格字段与候选权限 | 非法决策不降 NEW；目标只能来自本次候选；旧事实/后续变化/不确定样本 |
| CT15 治理事务、来源幂等与水位 | 来源幂等、版本冲突、Organized/Source/Pending 原子提交、全局毫秒水位 | 已关联免 LLM；过期目标拒绝；仅 MERGE 也推进水位；全有或全无 |
| CT16 Organization 调度、集成与评测 | Immediate/Periodic、单 Worker/全局 Counter、轮次固定 Pending、Map 通知接缝 | 单条失败跳过；轮内不无限重试；新入队留后轮；退出竞态；Conversation→Organized 闭环 |

**边界**：不实现 Map/Retrieval/Dreaming；不新增表/索引/`user_version`；不改 VFS；不新增第三方依赖；Core 不依赖 Ktor/serialization；不做 StartupRecovery、多 Worker、Deferred/冲突状态机、治理历史版本。

## 2. 已核实事实（当前代码）

| 事实 | 位置 |
| --- | --- |
| `LlmTask.CANDIDATE_SCANNER` / `GOVERNANCE` 已存在 | `context/core/.../core/llm/LlmClient.kt` |
| `LlmClient.complete`、`LlmBudget`、`ConservativeTokenEstimator` 可复用 | `core/llm/*`、`runtime/llm/LlmBudget.kt` |
| `ContextDatabase.read/write` 提供同步 ReadContext/TransactionContext；跨查询一致性需单次 `read` | `core/ContextDatabase.kt`、`ReadContext.kt` |
| `OrganizedMemoryReadOps.scanPage(offset,limit)` 已存在，按 `created_at ASC,id ASC`，并逐行补齐来源 | `ContextOps.kt`、`ContextSqlQueries.kt` |
| `RawMemoryReadOps` / `OrganizedMemoryTxOps` / `OrganizationPendingTxOps` 已有 insert/update/source/dequeue/maxUpdatedAt | `core/repository/*`、`ContextOps.kt` |
| `organization_pending` 只有 `nextByRawCreatedAt`，**没有整轮固定 ID 列表查询** | `OrganizationPendingReadOps.kt` |
| `organization` 分区目前被 Loader 接受但**不解析、不校验** | `ContextConfigLoader.CONTEXT_KEYS` / 文档注释 |
| `ContextConfig` 无 organization 字段；`ContextConfig.llm` 已有 Profile/Task | `runtime/ContextConfig.kt` |
| 现有 Prompt 只有 Extraction v1；无 Organization Prompt/Schema | `core/src/main/resources/prompts/` |
| `OrganizationWakeup` 是 Runtime internal no-op；CT11 提交 Raw 后调用 | `runtime/extraction/DefaultExtractionRunner.kt` |
| `MemoryIdGenerator.nextOrganizedMemoryId()` 已存在 | `core/id/MemoryIdGenerator.kt` |
| 现有 Schema 已有 `organization_pending`、`organized_memory`、`organized_memory_source`、`idx_source_raw`、`idx_organized_updated_at` | `ContextSchema.sq` |

## 3. 建议架构与归属

### 3.1 API / Port

| 层 | 建议 | 说明 |
| --- | --- | --- |
| Core | `OrganizationCandidateRetriever`（`suspend fun search(rawMemory): List<OrganizedMemory>`） | 设计 §6.4 的稳定 Port；Core 非 `context/api` 公共 SDK |
| Core | `CandidateIdValidator` / `GovernanceDecisionValidator` | 纯确定性校验，不依赖 JSON/Ktor |
| Core | `GovernanceAction`、`GovernanceDecision`、`ValidatedGovernanceDecision` | Core 内部 DTO，不新增公开 SDK 类型 |
| Core | `OrganizationCommitter` + `OrganizationCommitResult/Exception` | 单 WriteSlot 原子提交 |
| Core | `OrganizationCandidatePrompt`、`GovernancePrompt` | 构造 `LlmMessage` 与 Schema 文本；资源放 Core |
| Runtime | `LlmCandidateRetriever`、`JsonCandidateResponseParser` | 读快照、分批、调 `LlmClient`、严格解析 |
| Runtime | `LlmGovernanceService`、`JsonGovernanceResponseParser` | 独立 Governance Prompt/LLM/解析/校验 |
| Runtime | `OrganizationScheduler`、`OrganizationPeriodicCron`、`OrganizationPipeline` | 单 Worker/Counter、Immediate/Periodic、生命周期 |
| Runtime | `OrganizationConfig` + `ContextConfig.organization` + Loader 解析 | 只放 Trigger 配置；预算复用 `context.llm.tasks` |
| Runtime | `OrganizationMapWakeup`（internal fun interface，默认 no-op） | Map 通知接缝；不实现 Map、不新增公开 API |

### 3.2 Prompt / Schema 资源（Core）

| 资源 | 用途 |
| --- | --- |
| `prompts/organization-candidate-v1.md` | 低成本候选扫描；只输出候选 ID |
| `prompts/organization-candidate-schema-v1.json` | `{"candidate_ids":[string]}`，`additionalProperties=false` |
| `prompts/organization-governance-v1.md` | 单动作治理；严格时间语义、候选权限 |
| `prompts/organization-governance-schema-v1.json` | action/target/content/domains 严格字段 |

- 版本化命名 v1；实际 SHA 在实现批次记录。Prompt 不包含密钥/正文样例。
- 任务绑定使用既有 `LlmTask.CANDIDATE_SCANNER` / `GOVERNANCE`，不新增枚举。
- **推荐**：Organization 两个 Task 仅接受 `JSON_SCHEMA` / `JSON_OBJECT`；`PROMPT_ONLY` 不建立生产 Organization pipeline（与 CT11 extraction 的合法绑定口径一致）。候选/治理解析仍做严格 JSON 校验。

## 4. 关键算法（冻结建议）

### 4.1 CT13 快照与预算

1. **快照**：单次 `database.read { read -> ... }` 内用 `scanPage` 分页读取全部 `OrganizedMemory`（页大小如 256），按 `id ASC` 在内存稳定排序；读取结束立即释放 ReadContext。
2. **空集合**：直接返回空候选，**不调用 LLM**。
3. **固定开销**：用「System Prompt + Raw Memory + 输出契约 + Schema」估算空候选请求；若固定开销已超 `task.maxInputTokens` 或 `profile.contextWindowTokens`（含输出保留与安全余量），整条 Raw **显式预算失败**，不发起 LLM。
4. **单条/分批**：按 ID 顺序逐条加入候选；每条加入后重新估算：
   - 若当前批 + 下一条超限：当前批结束，下一条开新批；
   - 若当前批为空且单条仍超限：**显式单条预算失败**，不跳过该条、不宣称全量扫描；
   - 全部候选均被纳入某一批，不静默截断。
5. **输出预算**：每次请求的 `maxOutputTokens` 使用该 Task 的 `maxOutputTokens`（不超 Task 上限）；预算估算包含输出保留和安全余量。若 `finish_reason == "length"` 或响应截断，整条扫描失败。
6. **串行**：所有批次顺序调用 `LlmClient`；不并发；任一批失败/非法 ID/解析错误，整条 Raw 失败，保留 Pending。
7. **候选 ID**：每批只允许该批实际发送的 ID 集合；非数组/非字符串/空白/未知/跨批 ID 整批拒绝；重复 ID 可稳定去重；所有批次完成后按稳定顺序合并，映射回快照对象。
8. **取消/关闭**：`CancellationException` 原样传播；不在数据库事务内调用 LLM；关闭由 CT16 Scheduler 取消并 join，不产生部分写入。

### 4.2 CT14 Governance

- 输入：一条 Pending Raw（id/content/createdAt/generatedBy）+ Scanner 全部候选（id/content/domains/createdAt/updatedAt）。
- 输出严格单动作：
  - `NEW`：`content`、`domains` 必填；`targetMemoryId` 必须缺席。
  - `MERGE`：`targetMemoryId` 必填且属于候选；`content` 可选；`domains` 必须缺席（不修改分类）。
  - `UPDATE`：`targetMemoryId`、`content` 必填；`domains` 可选。
- 未知字段、错误类型、空内容、非法 domains、越权 target 一律整条失败；**不得把非法 MERGE/UPDATE 降级为 NEW**。
- Prompt 必须强调事实时间优先、补录旧事实、后续真实变化、不确定认知；系统时间不作为事实时间。
- 预算同 CT13：调用前 `LlmBudget.requireWithinBudget`；失败保持 Pending。

### 4.3 CT15 原子提交

单次 `database.write` 内：

1. 重读 `organization_pending` 与 `organized_memory_source`；已关联则仅清残留 Pending（若存在），不重复写。
2. 无 Pending 且无关联 → 拒绝过期决定。
3. `MERGE/UPDATE` 目标必须存在且 `updated_at` 等于候选快照中的值；不一致 → 过期决定，Pending 保留。
4. `NEW` 生成 Organized ID；主键碰撞仅有限次（≤3）整笔重试，绝不 `INSERT OR REPLACE`。
5. 写入/更新 Organized；插入 Source；删除 Pending；所有影响行数必须精确匹配。
6. 水位：`next = max(clock.instant().truncatedTo(MILLIS), previousGlobalMax.plusMillis(1))`；NEW 的 `created_at` 为真实新增时刻，`updated_at` 为水位；MERGE/UPDATE（含仅关联）均推进。
7. 任一步失败整笔回滚，Pending 保留；不在事务内调用 LLM/HTTP/VFS。

### 4.4 CT16 调度

- 配置建议：

```yaml
context:
  organization:
    triggers:
      immediate:
        enabled: true
      periodic:
        enabled: true
        cron: "*/10 * * * *"
        timezone: "Asia/Shanghai"   # 推荐可选，默认上海
```

- 一个进程内 `triggerCount: Int`（饱和）+ `workerRunning`；一次 Trigger = 一次全量扫描机会，不是单条。
- 每轮开始时读取**固定 Pending ID 列表**（`raw.created_at ASC, raw.id ASC`）；轮内每个 ID 最多尝试一次；单条普通失败记录后跳过并继续；新入队留后轮。
- 完成整轮后 `triggerCount -= 1`；仍 >0 则重新查询开启下一轮；归零退出 Worker。
- Immediate：Extraction 提交 Raw+Pending 后调用 `OrganizationWakeup`；一次事务多个 Raw 只唤醒一次。
- Periodic：Cron 到点仅 `hasAnyPending()`，非空才 request；不补跑、不持久化调度状态。
- 关闭：停 Periodic → 封闭准入 → 取消并 join Worker（含 LLM/DB 清理）→ 清计数；无 StartupRecovery。
- 成功提交后调用 `OrganizationMapWakeup.onOrganizedCommitted()`；失败只记录固定事件，不回滚治理；**不实现 Map**。

## 5. 现有 Repository 缺口

| # | 缺口 | 影响 | 建议 |
| --- | --- | --- | --- |
| R1 | `OrganizationPendingReadOps/Repository` 无整轮固定 Pending ID 列表 | CT16 无法一次取本轮固定集合；`nextByRawCreatedAt` 逐条取会让新入队混入本轮 | CT16 新增 `listAllByRawCreatedAt(): List<String>`（SQL join raw，按 `created_at ASC,id ASC`）；不新增表/索引 |
| R2 | `OrganizedMemoryReadOps.scanPage` 按 `created_at,id`，非纯 ID；且逐行 `attachSources` 有 N+1 | 快照排序与全量扫描性能 | CT13 在单次 `read` 内分页后按 `id` 内存排序；MVP 接受 N+1，性能优化留后续；不改既有查询契约 |
| R3 | 无 `scanAll`/候选轻量查询 | 需分页循环 | 复用 `scanPage`，不新增 Repository 方法；若 Architect 要求避免 N+1，可加轻量 `scanCandidatePage` |
| R4 | `OrganizationPending` 无 `created_at` 列 | 排序依赖 Raw 创建时间 | 用 join raw 的现有字段，不改 Schema |
| R5 | `OrganizedMemoryTxOps.update` 不提供目标 `updated_at` CAS | CT15 需自己读后比较 | CT15 在同一写事务内 `findById` 比较候选 `updatedAt`，不新增 CAS API |
| R6 | `OrganizationWakeup` 仅 Raw 提交后唤醒，无 Map 通知 | CT16 需要 Map 接缝 | 新增 Runtime internal `OrganizationMapWakeup` no-op；不新增 Core/公开 Port |

## 6. 分批、允许文件与正式回归

| 任务/批 | 允许新增/修改 | 明确不做 | 回归 |
| --- | --- | --- | --- |
| CT13 B1 | Core `organization/` Port/Validator/Prompt；Runtime `organization/LlmCandidateRetriever`/parser；Core Prompt/Schema 资源；对应 Core/Runtime 测试 | 不接 Factory/Impl、不做 Governance/Commit/Scheduler | 定向 core/runtime + `./scripts/check` 全量 |
| CT14 B1 | Core Governance DTO/Validator/Prompt；Runtime Governance service/parser；Prompt/Schema 资源；测试 | 不接生产、不做事务提交 | 定向 + 全量 |
| CT15 B1 | Core `OrganizationCommitter`/结果/异常；Repository 仅补必要查询（若裁定）；测试 | 不接 Scheduler、不改 Schema | 定向 + 全量 |
| CT16 B1 | Runtime `OrganizationScheduler`/Periodic/Pipeline/Config/Loader；`listAllByRawCreatedAt`；生命周期接缝；测试 | 不实现 Map/Retrieval | 定向 + 全量 |
| CT16 B2 | `AlcyoneContextImpl/Factory` 生产接线；真实 Runtime/SQLite + Fake LLM 集成测试；文档/证据 | 不跑真实模型、不发布 | `./scripts/check` + 14 模块 + 81 基线 |

- 每批必须：源码行首 `@Test` = XML、失败/错误/跳过 0；CT13～CT15 不接生产；CT16 B2 才宣称 Organization 生产启用。
- 不新增依赖；Core 无 Ktor/serialization；不改 VFS/common/SQL Schema/索引/`user_version`。
- 真实模型评测未运行；Fake 只证明协议/事务，不作为语义质量通过。

## 7. 需 Architect 裁定项（推荐）

| # | 问题 | 推荐 | 影响 |
| --- | --- | --- | --- |
| Q1 | CT13～CT15 是否只做组件、CT16 才接生产 | **是**；CT13 只读/组件，CT16 B2 接线 | 与 CT09/CT11 分批模式一致；避免半成品流水线 |
| Q2 | Candidate Port/Prompt 归属 | Core 定义 Port/Validator/Prompt 资源；Runtime 实现 LLM/JSON | Core 无 Ktor/serialization；与 Extraction 分层一致 |
| Q3 | 候选扫描预算 | 固定开销 + 逐条 + 输出保留/余量；单条放不下显式失败 | 不静默截断；不把不完整扫描当空候选 |
| Q4 | 快照排序/分页 | 单次 read 分页 + 内存 `id ASC`；空集合不调 LLM | 不新增 Repository；避免读锁跨 LLM |
| Q5 | 候选 ID 校验/去重 | 批内 ID 集合；未知/跨批/非字符串拒绝；重复稳定去重；`finish_reason=length` 失败 | 任一必需批失败整条 Raw 失败 |
| Q6 | Organization LLM 模式 | **已批准**：CT13 直接构造仅 `JSON_SCHEMA`/`JSON_OBJECT`；缺 Task/Profile/模式不支持 → `INVALID_CONFIG`；不外推为生产静默禁用 | 与 CT11 合法绑定一致；Q13 另定生产启用规则 |
| Q7 | Governance 严格字段 | NEW/MERGE/UPDATE 按设计；MERGE 不允许 domains；非法不降 NEW；target 必须候选 | 防止越权/静默降级 |
| Q8 | CT15 水位/幂等/冲突 | 单 WriteSlot；Source 已关联清 Pending；目标 updatedAt 不符拒绝；全局毫秒严格递增；仅 MERGE 也推进 | 不新增 CAS/Schema；调用方契约 |
| Q9 | Pending 固定列表 Repository | 新增 `listAllByRawCreatedAt()`；不建表/索引 | CT16 轮次固定；需确认新增内部 Port 方法 |
| Q10 | Organization Cron 时区 | **未冻结**：CT13 不涉及；CT16 前核对定稿/Extraction 固定上海依据后再裁定，暂不增加 `timezone` 字段 | 不阻塞 CT13；CT16 需单独裁定 |
| Q11 | Scheduler 关闭/计数 | **已批准**：Int 饱和且可观察；一轮固定列表；单 Worker；单条失败跳过；关闭先同步封闭准入→停 Cron/取消 join→释放资源/发布结果；无 StartupRecovery | 修正关闭顺序；不丢唯一 Worker 清理责任 |
| Q12 | Map 通知 | Runtime internal `OrganizationMapWakeup` 默认 no-op；提交后调用；失败吞掉 | 不新增 Core/公开 API；不实现 Map |
| Q13 | 生产接线门 | **未批准静默 no-op**：CT16 必须区分“未配置 Organization”与“已配置但不完整/非法”，评估启动诊断/兼容/启用规则；不得为旧测试静默失效 | 留 CT16 前裁定；不阻塞 CT13 |
| Q14 | CT16 集成 | 真实 Runtime/SQLite + Fake LLM；固定样本仅协议/事务 | 不承诺语义质量；真实模型 not-run |

## 8. 明确不做

- 不实现 CT17+ Map/Retrieval、Dreaming、备份/部署。
- 不新增表/索引/`user_version`、Deferred/冲突状态机、Organized 历史版本、治理 Journal、多 Worker、Embedding/FTS。
- 不修改 VFS 生产代码；不新增第三方依赖；不把 LLM 密钥/正文写入日志/异常。
- 不把 Fake LLM 结果写成真实模型语义质量通过。

## 9. 下一步

1. Architect 对 §7 Q1～Q14 给出裁定（可逐项简答）。
2. 裁定后 Lead 落 CT13 `B1_委派单.md`，派发同一 Worker；未裁定前不实现。
3. 逐任务 Worker → Lead 实跑初验 → Architect 独立复核；CT13 通过后才进入 CT14；CT16 通过后才宣称 CM3 完成。
4. 无 git 写操作/推送；当前授权只到 CM3，不自动进入 CM4。
