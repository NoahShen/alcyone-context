# Alcyone Context System 核心数据流与接口设计

**版本**：v0.1  
**更新日期**：2026-10-20  
**状态**：持续讨论稿；Conversation / Extraction / Organization / Memory Map / Context Retrieval / Dreaming MVP 核心设计已确认；2026-10-19 移除 Deferred，将未确认认知纳入 Organized Memory；Dreaming 采用主题驱动增量反思与手动/Cron 触发；不建设独立 StartupRecovery；Extraction Count + Cron Idle Trigger、Organization Immediate + Cron Periodic Trigger 纳入 MVP，SQLite Online Backup API 完整快照备份纳入 MVP；复杂灾难恢复延后
**上位文档**：《Alcyone Context System 架构设计 v0.1》

## 本次修改（2026-10-25，CT01 设计差异同步）

- §6.1：SQLite 时间文本统一保存为上海时区 `+08:00` 固定毫秒精度 ISO-8601 格式，同步 Core 内部用 `Instant` 表示绝对时间的规则（见技术实现设计 §2.4 / §3.6）。
- §15.4：Memory Map 明确采用 T1 固定快照 + 最终一致性，删除生成后 T2 二次检查与短发布保护锁表述（见技术实现设计 §7.3）。
- §15.5：持续失败由 Map Cron 补偿机制处理（默认启用 `*/10 * * * *`），删除周期补偿暂列 TODO 表述（见技术实现设计 §7.5）。

---

## 1. 目的与讨论方式

本文件沿着 **Conversation → Memory → Context** 的数据流逐步记录可指导开发的设计。每个步骤优先确定：

1. 输入与输出的数据结构；
2. 模块职责与对外/内部接口；
3. 持久化、去重与状态边界；
4. 异常与恢复原则。

**状态约定**：
- **已确认**：用户与设计讨论已达成共识，可作为后续详细设计和开发的约束。
- **建议 / 待定**：目前的工程建议，仍需下一轮讨论，不作为已定稿契约。

本文件只记录 Alcyone Context System 的新架构；不沿用旧版 Pending MEM 及逐条审核流程。**Memory/Conversation 的结构化数据由本地 SQLite `context.db` 保存；VFS 作为通用文件基础设施原样复用**，负责 `map.md`、一致性数据库备份快照及 Resources 等文件，不理解 SQLite 表和记忆治理语义。VFS 自身设计文档无需因该分工修改。

### 1.1 MVP 默认串行执行原则（已确认）

- **准确性、确定性与设计简单优先于吞吐量**。在没有明确高并发需求的模块中，MVP 默认采用串行任务处理；需要并发时再基于实际瓶颈专项论证与升级，不预先设计并行 Worker 池。
- **Memory Organization 整条治理流水线按 Raw Memory 逐条串行**：同一时间仅治理一条 Pending Raw Memory；该条内部 Candidate Retriever 的各扫描批次**依次调用低成本 LLM**，完成全批次候选汇总后才进行独立 Governance 判断和持久化；提交完成后再处理下一条。
- **串行执行不等于免除可靠性要求**：仍需输出校验、SQLite 事务内的原子提交、失败保留 Pending，以及来源关联去重；数据库完整快照备份纳入 MVP，复杂灾难恢复后续专项；正常启动不建设 StartupRecovery。
- **异步不等于并行**：Ingestion 可及时返回，后续任务排队异步执行，但后台默认单 Worker；多来源请求仍需数据库约束和必要同步来保证正确性。


## 2. 核心数据流概览

```text
Agent Frameworks (Hermes / Pi / OpenClaw / ...)
  ↓ 框架原生会话事件
Runtime Adapter
  ↓ Conversation Turn v0.1
Conversation Ingestion
  ├─ 格式校验
  ├─ 幂等去重
  └─ SQLite 持久化
  ↓
Conversation Store
  ↓ 新 Turn 持久化成功后触发 Count 检查；Idle 独立由 Cron 定时扫描
Extraction Trigger（可扩展）
  ├─ Count Trigger          [MVP：按 Session 未处理 Turn 数量]
  ├─ Semantic Trigger       [预留：暂不实现]
  └─ Idle Trigger           [MVP：Cron 扫描空闲且有 pending Turn 的 Session]
  ↓ 任一 Trigger 满足
Extraction Scheduler（全局内存队列 + Session 触发计数）
  ↓ MVP 单 Worker 全局串行；未来按需扩展跨 Session 并行
Extraction Worker（正常流程的技术执行载体）
  ↓ SessionKey
PreExtractionContextBuilder  [构建 Snapshot + 连续 Turn 窗口，不预载 Raw Memory]
  ↓ ExtractionContext
  ↓
Memory Extraction            [六条提取原则、输出 DTO 与整批拒绝规则已确认]
  ↓
Raw Memory                 [context.db / 不可变记录 / 与 Turn 状态同事务提交]
  ├─ Recent Memory View     [后续可选：逻辑查询视图；MVP 暂不实现]
  └─ Memory Organization    [MVP：Immediate + Cron Periodic Trigger → Pending → 串行治理]
       ↓
     Memory Map             [VFS Markdown / Overview + Navigation；MAX(updatedAt) 刷新]
       ↓
Context Retrieval / Bootstrap [轻量 Bootstrap + Memory Search/ID / Resource URI]
       ↓
Main Agent / Dynamic Sub-agent

Dreaming：支持手动或可配置 Cron 触发；从新增 Conversation 发现主题，跨历史 Raw / Organized / Conversation 聚合证据，生成带来源的 Raw Memory 后交由 Organization 治理；详见 §17。
```

**已确认的总体边界**：
- Alcyone 是 Personal Agent Framework 的整体名称；Context System 是可独立部署、可被不同 Agent 框架复用的子系统。
- 用户拥有、管理并控制自己的会话、记忆与资料数据。
- Domain 用于长期知识分类，而不是固定 Agent 的身份或默认权限边界。
- VFS 继续提供 `alcyone://memory/`（仅 Map、备份等**文件**）和 `alcyone://resources/`（资源文件）；**单条 Raw / Organized Memory 不使用 VFS URI**，以 Memory ID 由 Context API 访问。
- 本地 `context.db` 由 SQLite Repository 原生访问，Conversation、Raw、Organized、来源关联、Pending 同库、同事务能力；数据库运行文件**不通过 VFS 读写**。数据库的一致性备份快照由 Context System 生成后交 VFS/Git 管理；VFS 不负责生成 SQLite 事务或快照。
- Recent Memory View 是计划中的逻辑查询能力（可包括未治理 Raw 和 Map 未覆盖的 Organized），不单独存储副本，**MVP 暂缓实现**。

---

## 3. Conversation Turn v0.1（已确认）

### 3.1 协议定位

Adapter 将各 Agent 框架的会话数据转换为标准的 **Conversation Turn**。一次提交一个逻辑轮次；不要求 Ingestion 理解 Hermes、Pi、OpenClaw 等框架的原生事件结构，也不要求每次上传完整 Session 历史。

### 3.2 标准 JSON

```json
{
  "runtime_id": "hermes-cloud",
  "agent_id": "quant",
  "session_id": "session-123",
  "session_turn_id": "turn-45",
  "timestamp": "2026-10-13T14:16:00+08:00",
  "messages": [
    {
      "role": "user",
      "content": "我决定暂时停止研究策略 A。"
    },
    {
      "role": "assistant",
      "content": "了解，后续将策略 A 视为暂停研究的方向。"
    }
  ]
}
```

**顶层字段（均必填）**

| 字段 | 类型 | 含义 | 产生方 |
| --- | --- | --- | --- |
| `runtime_id` | string | **稳定的 Runtime 部署实例 ID**，不是进程 PID；同一实例重启后不变 | Adapter 配置 / 注册 |
| `agent_id` | string | 该 Runtime 实例内的 Agent 身份，如 Hermes Profile；与 Memory Domain 无直接绑定 | Runtime / Adapter |
| `session_id` | string | 在上述 Runtime + Agent 命名空间下的会话标识 | Runtime / Adapter |
| `session_turn_id` | string | 该 Session 内稳定的轮次标识，用于去重与溯源 | Runtime / Adapter |
| `timestamp` | string | 本轮发生时间，ISO 8601 带时区；优先原始轮次时间，缺失时 Adapter 补充 | Runtime / Adapter |
| `messages` | array | 本轮消息，按时间顺序排列 | Runtime / Adapter |

**消息字段（均必填）**

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `role` | string | MVP 主要使用 `user`、`assistant`；可使用 `tool` 表示工具结果 |
| `content` | string | 标准化后的文本内容 |

**约定**：
- `messages` 不限于恰好两条，但普通完整轮次通常包含用户消息与最终助手回复。
- 第一版统一文本消息，不要求完整承载所有多模态附件、推理轨迹或工具调用结构。
- 工具消息是可选采集内容；未经独立证实的 Assistant 结论不自动视为已验证事实。
- `runtime_type`（如 `hermes`、`pi`）是 `runtime_id` 的注册元信息，不重复放在每条 Turn 中。
- `agent_id` 仅用于来源定位与运维，不决定记忆的 Domain 分类。
- Adapter 若无法从框架直接取得 `session_turn_id`，需生成**稳定、可重复定位**的 ID；不得在每次重试时随机生成不同 ID。
- `user_id` 不放在此基础协议中；身份应由可信的接入认证/配置确定，不能依赖请求自报。多用户支持时，服务端需增加对应的身份隔离。

### 3.3 ID 命名约定（已确认）

- **`id`**：`conversation_turn` 表的内部主键，全局唯一；设计讨论中也可以简称为 **`turn_id`**，默认表示这个内部 ID。
- **`session_turn_id`**：由 Runtime / Adapter 提供的会话内轮次 ID；外部 Conversation Turn JSON 字段和数据库列名统一使用 `session_turn_id`。
- **Session Snapshot**：由 `ExtractionContext.turns` 中构建时状态为 `pending` 的 Turn 表达；它们的内部 `id` 即本次完成后需要更新状态的 ID。不再单独维护 `target_turn_ids` 字段。
- 外部接入协议不提供 SQLite 内部 `id`。实际代码与文档中尽量直接写 `id` 或 `session_turn_id`，避免在接口上同时混用两种 `turn_id` 含义。

### 3.4 Session、Turn 的唯一性（已确认）

```text
Session: (runtime_id, agent_id, session_id)
Turn（业务唯一键）: (runtime_id, agent_id, session_id, session_turn_id)
```

不同 Agent Profile、不同 Runtime 实例可以出现相同 `session_id`，因此不得将 `session_id` 视为全局唯一。

---

## 4. Runtime Adapter（已确认职责）

**职责**：
1. 对接某一种 Agent 框架的原生 Hook、回调、事件或会话导出能力。
2. 将框架的原生轮次信息转为 Conversation Turn v0.1。
3. 根据框架特性规范化 Session / Turn ID、时间与消息文本。
4. 将标准 Turn 提交至 Ingestion；网络重试须复用同一业务标识。

**不负责**：Memory Extraction、Memory Domain 分类、长期记忆治理。

**框架示例**：
- Hermes：`agent_id` 可使用 Profile 名称；来自重复历史事件的轮次须映射到稳定的 `session_turn_id`。
- Pi、OpenClaw 等：由各自 Adapter 读取框架原生数据并转换，不要求 Core 支持其专属事件格式。

**待定**：Adapter 的配置/注册协议、如何从不完整或重复历史中切出标准轮次、掉线补偿与原始事件的诊断保留方式。

---

## 5. Conversation Ingestion（已确认职责与主流程）

Conversation Ingestion 仅执行：

```text
标准 Turn 接收
  → 格式校验
  → 业务唯一键重复校验
  → SQLite 持久化
  → 返回接收结果
```

- 不执行 Trigger 模型判断、Extraction 或 Organization；写入成功后通知后续异步触发检查。
- 后续学习流程由独立后台 Worker **异步查询 SQLite 中未处理的 Turn**。
- MVP 不要求 Kafka、RabbitMQ 等独立消息队列。

**建议对外接口（路径尚待详细 API 设计确认）**：

```http
POST /v1/conversations/turns
Content-Type: application/json
```

请求正文为 §3 的 Conversation Turn v0.1 JSON。接收成功的承诺边界是 **Turn 已持久化**，而非后续记忆学习已经完成。

### 5.1 幂等语义（已确认）

- 四元业务键不存在：创建新 Turn。
- 四元业务键已存在且内容相同：认定为重复提交，不创建新记录。
- 四元业务键已存在但内容不同：视为冲突；**不静默覆盖原记录**。

**待定**：重复内容是否按完全一致的规范化 JSON 判断；HTTP 状态码与冲突响应格式；客户端重试约定。

---

## 6. Conversation Store：SQLite（表结构已确认）

### 6.1 MVP 表结构

```sql
CREATE TABLE conversation_turn (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    runtime_id TEXT NOT NULL,
    agent_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    session_turn_id TEXT NOT NULL,

    timestamp TEXT NOT NULL,
    created_at TEXT NOT NULL,
    messages_json TEXT NOT NULL,

    extraction_status TEXT NOT NULL DEFAULT 'pending',

    UNIQUE (
        runtime_id,
        agent_id,
        session_id,
        session_turn_id
    )
);
```

**时间字段含义**：
- `timestamp` 为 Runtime/Adapter 归一化的原始会话发生时间，用于会话事实时序；**不用于判断 Session 是否近期有新数据入库**。
- `created_at` 为 Context System **首次成功插入 Turn** 时生成的入库时间，不属于外部 Conversation Turn v0.1 请求协议；重复提交同一业务 Turn 时不刷新它。时间统一保存为上海时区 `+08:00` 固定毫秒精度 ISO-8601 文本（`yyyy-MM-dd'T'HH:mm:ss.SSS+08:00`），Core 内部用 `Instant` 表示绝对时间，在 SQLite 写入边界统一编码；详见技术实现设计 §2.4 / §3.6。（已批准变更（2026-10-25））
- Idle Trigger 使用 `created_at`，避免历史会话补传因原始 `timestamp` 较旧而被立即认定为空闲。

**两个 ID 的含义**：
- `id`（讨论时可称 `turn_id`）：由 SQLite 产生的内部主键，服务于关联、运维、日志定位及后续 Worker 查询。
- `session_turn_id`：由 Runtime/Adapter 产生的外部业务轮次 ID，只保证在对应 `(runtime_id, agent_id, session_id)` 中唯一。

`id`（内部 `turn_id`）**不属于 Conversation Turn v0.1 外部请求协议**。外部 JSON 与表中的会话内轮次标识统一命名为 `session_turn_id`。外部业务唯一键使用 `UNIQUE` 约束，而不是直接作为数据库主键。

### 6.2 Extraction 处理状态（MVP 已确认）

`extraction_status` 仅记录一条 Turn 的 Extraction 处理**结果**，不记录运行中的状态；旧方案 `filter_status` 不再使用。

| 值 | 含义 |
| --- | --- |
| `pending` | 尚未成功完成 Extraction，**包括正在由 Worker 处理中的 Turn** |
| `complete` | Extraction 已成功完成，包括结果为空记忆的情况 |
| `failed` | 明确无法自动处理、需要特殊干预的问题；例如单个 Pending Turn 在当前模型配置下超过整体 Token Budget。普通临时失败不必设为 `failed` |

- **不使用 `processing` / `processing_started_at`** 等持久化运行状态。
- 模型超时、网络故障、进程中断等可重试异常，Turn 继续保持 `pending`；不要求 Worker 立即重试到成功。
- 本次 Extraction 输出通过整批校验后，在**同一个 `context.db` 事务**中插入本批 Raw Memory、对应 `organization_pending`，并将冻结 Snapshot 中的 Target Turn 更新为 `complete`；零记忆批次仅更新 Target。事务失败时全部回滚并保持 `pending`。**MVP 不另建批次级幂等键**；已提交但 Organization 通知未发出时，Pending 仍被持久化，由 Organization Periodic Trigger 按 Cron 补偿唤醒。
- Conversation、Raw Memory 与 Organization 分别维护各自处理结果；Memory Map 是面向 Agent 的派生概览，不是新的事实来源；Conversation 仍作为证据保留。

### 6.3 异步执行、异常与重启边界（已确认）

```text
新 Turn 持久化成功
  → Count Trigger 异步检查；Idle Trigger 由可配置 Cron 独立触发检查
  → Trigger 成立，向 Scheduler 提交 SessionKey
  → Scheduler 累加该 Session 的待消费触发数
  → 全局队列中该 Session 至多有一个排队项，且不得并发执行
  → Worker 调用 PreExtractionContextBuilder(sessionKey)
  → Builder 定位最新 Pending，向前读取 N 条连续 Turn，超预算则将 N 折半重读，直至窗口满足预算
  → 折半到 N=1 仍超预算：仅将该锚点标记 failed，重新定位下一条 Pending
  → 没有 pending Turn：返回空结果，本次正常结束
  → 窗口符合预算：冻结 Target，执行一次 Extraction
  → SQLite 同一事务提交 Raw Memory / organization_pending / Snapshot complete
  → Scheduler 消费本次触发数；如有剩余，再次把 Session 入队
```

- Ingestion 只承诺 Turn 已持久化，Trigger 与 Extraction 异步运行，不阻塞 Ingestion 响应。
- 本期采用**进程内队列与计数**，不增加任务数据库表、消息队列或持久化的处理中状态；同一进程内每次触发都会获得一次后续 Worker 执行机会，允许空执行。
- 若某次 Extraction 失败，本次 Trigger 计数仍消费，Turn 留在 `pending`；新的触发信号仍可使其再次处理。所有触发次数保证仅限**当前进程生命周期**。
- 进程重启后内存队列和计数会丢失，但 `pending` Turn 仍保存在 SQLite；**Worker 不做启动扫描或专属恢复**。MVP 的 Idle Trigger 在正常周期扫描中自然发现空闲且存在 Pending 的 Session，包括进程重启后遗留的 Turn。
- **不建设 StartupRecovery 模块**：正常启动或首次启动只需初始化 Trigger、Scheduler 和 Worker，启动不主动补跑 Extraction；Session 是否值得触发仍由 Count / Idle 等独立策略决定。

---

## 7. Extraction Trigger（Count + Idle MVP 已确认）

### 7.1 设计定位

**Trigger 只决定何时启动 Memory Extraction（When）**，不决定处理哪些 Turn（What），也不负责提取或判断最终 Memory 的长期价值（What to Remember）。

Trigger 是可扩展机制，不依赖某个特定小模型。多个 Trigger 采用 **OR** 逻辑：任意一个条件满足即向 Scheduler 申请启动。

**本期选择**：

| Trigger | 判断时机 | 规则 | MVP 状态 |
| --- | --- | --- | --- |
| Count Trigger | 新 Turn 成功入库后 | 同一 Session 中未处理 Turn 数量达到阈值 | **实现** |
| Semantic Trigger | 新 Turn 入库后（未来可选） | 基于语义判断是否需要立即 Extraction | **仅保留扩展能力；模型与实现暂不讨论** |
| Idle Trigger | 由可配置 Cron 定时扫描 | 存在 Pending Turn，且 Session 最近入库的 Turn 超过空闲阈值 | **实现** |

`false` / 不触发，只表示现在不需要执行；不应修改 Turn 的提取资格、删除 Turn 或将其永久跳过。

### 7.2 Count Trigger（MVP 已确认）

- **统计范围**：单个 Session，键为 `(runtime_id, agent_id, session_id)`；不同 Session 绝不合并计数。
- **统计对象**：`extraction_status = 'pending'` 的 Turn；不统计已经成功提取的 Turn。
- **触发时机**：Ingestion 成功插入一条新 Turn 后，异步检查该 Session；完全重复的 Turn 不应触发重复检查。
- **触发条件**：同 Session 未处理 Turn 数 `>= threshold`。
- **建议初始阈值**：`5`，**可配置**，不是永久固定参数。
- **输出**：向 Scheduler 发起一次“处理此 Session”的申请，不携带固定数量的 Turn，不直接调用 LLM。

示例：Session A 有 7 条 `pending` Turn、阈值为 5，则触发；PreExtractionContextBuilder 不应因此仅处理其中 5 条。

### 7.3 Idle Trigger（MVP 已确认）

**唯一职责**：周期性找出**有 `pending` Turn 且已经空闲**的 Session，并向现有 Extraction Scheduler 提交 `SessionKey`。Idle Trigger **不执行 Extraction、不决定 Target Turn、不维护重试状态，也不负责去重或防止无限重试**；重复触发和失败退避为 Scheduler / Worker 后续优化 TODO。

- **扫描时机**：使用可配置 **Cron 表达式**（含时区）定时检查 SQLite，支持自由安排执行时间与扫描间隔；不为每个 Session 设置独立定时器。Cron 只负责何时检查，不改变空闲判定规则。
- **空闲判定**：以该 Session **所有 Turn** 中最新的 `created_at`（首次入库时间）为活动时间，而非只看 Pending Turn、原始 `timestamp` 或 Agent 进程是否存活。最新入库时间不晚于 `now - idleTimeout` 即视为空闲。
- **待处理条件**：当前 Session 至少有一条 `extraction_status = 'pending'` 的 Turn；`complete` 不计入待处理数量，`failed` 不自动重试。
- **输出**：对每个满足条件的 Session 提交一次 `(runtime_id, agent_id, session_id)`，不携带 Turn ID，复用 Count Trigger 相同的 Scheduler / PreExtractionContextBuilder / Extraction Worker。
- **重启行为**：正常启动后按周期自然扫描即可发现此前遗留 Pending；不为系统重启设置特殊触发，不建设 StartupRecovery。

配置示意（初始建议，可修改）：

```yaml
extraction:
  triggers:
    count:
      threshold: 5
    idle:
      enabled: true
      idleTimeout: 10m
      cron: "*/5 * * * *"     # 示例：每 5 分钟检查一次
      timezone: "Asia/Shanghai"  # 示例，可配置
```

**性能原则：由 Pending Turn 驱动查询，不对全部历史 Turn 做 `GROUP BY + MAX` 聚合。** 先从只包含 Pending 的索引获取 Session，再通过 Session + 时间联合索引检查是否在空闲阈值之后有新 Turn。百万级历史 Turn 中 Pending 通常较少时，该方式避免每轮全表聚合。

```sql
-- 对 Pending 集合构建部分索引；complete 历史 Turn 不进入此索引
CREATE INDEX idx_turn_pending_session
ON conversation_turn (runtime_id, agent_id, session_id)
WHERE extraction_status = 'pending';

-- 判断指定 Session 最近是否有新 Turn 入库
CREATE INDEX idx_turn_session_created_at
ON conversation_turn (runtime_id, agent_id, session_id, created_at DESC);
```

扫描 SQL（`:idle_before` 为当前时间减去 `idleTimeout`；时间格式与 `created_at` 保持一致）：

```sql
WITH pending_sessions AS (
    SELECT DISTINCT runtime_id, agent_id, session_id
    FROM conversation_turn
    WHERE extraction_status = 'pending'
)
SELECT p.runtime_id, p.agent_id, p.session_id
FROM pending_sessions p
WHERE NOT EXISTS (
    SELECT 1
    FROM conversation_turn t
    WHERE t.runtime_id = p.runtime_id
      AND t.agent_id = p.agent_id
      AND t.session_id = p.session_id
      AND t.created_at > :idle_before
);
```

该 SQL 判断的是「Session 在空闲阈值之后没有新 Turn 入库」，与 `MAX(created_at) <= :idle_before` 等价；不要求加载整段 Session 历史。用 `EXPLAIN QUERY PLAN` 验证索引命中，未来依据实际 Pending 规模调整，无需 MVP 新增 Session 状态表、缓存或全局扫描水位。

**TODO（不属于 Idle Trigger 职责）**：重复触发去重、失败后重试频率、无限重试防护与退避策略，由 Scheduler / Worker 在后续专项优化；本阶段不为其增加持久化状态列或任务队列。

### 7.4 Trigger → Scheduler 的最小契约（已确认）

- Trigger 检查**是否值得现在启动**，满足条件时只提交 SessionKey：`(runtime_id, agent_id, session_id)`；不需要在触发时读取并固定 `turn_ids`。
- 判断与任务执行异步于 Ingestion；Trigger 不保存 Raw Memory、不修改 Conversation 的提取结果、不决定 Batch 内容。
- 任意 Trigger（Count、Idle、未来 Semantic）复用同一个 Scheduler；其内部判断逻辑独立、可插拔。
- 一个有效的触发信号意味着同进程内增加一次待消费执行机会；即便之前的 Extraction 已处理所有 Turn，后一次 Worker 也可发现没有 pending Turn 并立即结束。

### 7.5 Extraction Scheduler（MVP 已确认）

**调度结构**：单个全局内存任务队列 + 一个记录每 Session 待消费触发数的 Map；无需为每个 Session 单独维护物理队列。

```kotlin
val pendingTriggers = mutableMapOf<SessionKey, Int>()
val taskQueue = Channel<SessionKey>(Channel.UNLIMITED)
```

**逻辑约束**：

1. 每收到一次有效 Trigger：`pendingTriggers[session] += 1`。
2. 只有该 Session 的计数从 `0` 变成 `1` 时，才把它放入全局队列；后续触发仅累加计数。
3. 队列元素被 Worker 取走后，该 Session 仍保留在 Map 中，表示处于排队或运行状态，因此不会再次并发入队。
4. Worker 执行结束（含空执行与异常）后，Scheduler 将计数减 `1`；剩余大于零，则将 Session 放回队列尾部；归零则移除 Map 项。
5. **MVP 使用 `1` 个 Extraction Worker，全局串行消费触发任务**；同一 Session 严格串行的约束继续保留。未来确有吞吐量需求时，再允许多个 Worker 并行处理不同 Session；本轮不实现并行调度。
6. 即使只有一个 Worker，Trigger 仍可能由不同请求调用；Map 更新、首次入队、执行后减计数及重新入队必须保证状态更新正确，避免丢信号或重复入队。

示例：同一 Session 触发三次，Map 计数为 `3`；Worker 每完成一次（即使没有 pending Turn）消费一个计数，直到归零。在进程存活期间，三次触发对应三次 Worker 执行机会。

> `pendingTriggers` 和队列属于**易失的运行时状态**，不是可靠持久化任务系统。进程异常退出后，不承诺已触发信号的次数能恢复；只依靠 SQLite 的 `pending` Turn 在未来通过 Trigger 重新发现。当前不实现 Worker 启动扫描。

### 7.6 Trigger 取舍与扩展

- Count Trigger 按 Session 计数，阈值默认建议 `5` 且可配置；Trigger 阈值不等于每次 Extraction 只处理 5 条 Turn。
- Semantic Trigger 可在未来使用 Jev、Flash、规则或其他实现；目前仅预留接口，不讨论具体模型与判断协议。
- Idle Trigger **MVP 实现**：由可配置 Cron 定时扫描长期空闲且有 `pending` Turn 的 Session，自然覆盖进程重启后的遗留 Turn；不承担重试去重和失败退避。Cron 方言、配置校验与统一调度器实现细节在开发时确定。
- 触发判断为 false 只表示**本次不启动**，绝不意味着删除或永久放弃该 Turn。

---

## 8. Session Snapshot / PreExtractionContextBuilder（Snapshot 与 History 规则已确认）

### 8.1 模块定位与最小契约（已确认）

`PreExtractionContextBuilder` 负责从指定 Session 生成一次**受整体 Token Budget 限制的连续会话窗口**。不再以「该 Session 全部 Pending Turn」强制形成一个大 Snapshot；一次 Trigger 只要求提供一次处理机会，剩余 Pending 留给后续 Trigger。

- **Trigger**：判断何时启动，只提交 `SessionKey`，不决定具体 Turn。
- **Scheduler**：任务排队、同 Session 串行和触发次数消费，不自行扩大本轮处理范围。
- **PreExtractionContextBuilder**：以**最新的 Pending Turn** 为锚点向前读取连续 Turn；超过整体 Token Budget 就将本次读取条数折半并重试，直到选出可用窗口并固定 Target；不检索已有 Raw Memory。
- **Memory Extraction**：仅提取窗口内 Snapshot 时为 Pending 的 Target；Complete Turn 只提供背景。**仅使用 Builder 一次性构建的固定窗口**，不在 LLM 执行过程中请求追加历史。

```kotlin
interface PreExtractionContextBuilder {
    suspend fun build(sessionKey: SessionKey): ExtractionContext?
}

data class ExtractionContext(
    val sessionKey: SessionKey,
    val turns: List<ConversationTurn>
)
```

这里 `ConversationTurn` 是包含 SQLite 内部 `id` 和 `extractionStatus` 的内部记录。`turns` 为按会话发生顺序排列的**单个连续窗口**：其中冻结状态为 `pending` 的 Turn 才是本次 Target；其余 Turn 用作 History。不另外维护 `targetTurnIds`、`targetTurns`、`historyTurns` 或 `recentMemories` 字段。

### 8.2 Session Snapshot（已确认，2026-10-21 更新）

1. **生成位置**：Worker 被 Scheduler 唤醒后，由 `PreExtractionContextBuilder.build(sessionKey)` 查询 SQLite 并固定本轮窗口；Trigger 不携带 Turn ID。
2. **锚点**：查找该 Session 按真实会话顺序排列的**最新 `pending` Turn**；不是不论状态的最新 Turn。否则较新 `complete` Turn 可能导致重复空窗口，遗漏较旧 Pending。
3. **窗口与预算**：以最新 Pending 为终点，先按配置的初始读取数量／预算估算向前读取连续的 N 条 Turn，计算连同 Prompt、输出预留、安全余量及协议开销的整体 Token 消耗。若超预算，则令 `N = max(1, floor(N / 2))`，保持同一锚点重读该连续窗口，直至符合预算；首次符合即固定，不继续二分优化窗口大小。不能跳过中间 Complete Turn 只挑更早的 Pending。
4. **固定 Target**：选中窗口中，Snapshot 时 `extraction_status=pending` 的 Turn 全部作为本次 Target；`complete` 只作背景；`failed` 不自动作为 Target。**窗口外的 Pending 留在数据库，不属于本次任务。**
5. **事务范围**：成功时仅将本次已固定 Target 的 Turn 标记 `complete`，并与本轮 Raw Memory、Organization Pending 同库原子提交；快照后新增 Turn、窗口外 Turn 均不得顺带更新。
6. **没有 Pending**：Builder 返回 `null`，本次空执行。失败或模型输出不合法时本轮 Target 保持 Pending，等待后续正常 Trigger。
7. **单 Turn 过大**：只有当读取数折半至 `N=1`、锚点 Turn 加上必要 Prompt/输出预留仍超预算时，才认定为确定性不可处理；记录超限原因，在 SQLite 中将该锚点 `extraction_status` 更新为 `failed`（不标记 `complete`），重新定位下一条最新 `pending` Turn 再执行折半选择。原始 Conversation 保留；若已无其他 Pending，本次结束。临时网络/模型故障不能据此标记 `failed`。单 Turn 分段、修改模型配置后的人工重试作为 TODO。
8. **无需独立 Snapshot 表、持久化 processing 状态或额外 Batch Queue**；当前单 Worker 保证同 Session 串行。

### 8.3 连续会话窗口（已确认，2026-10-21 更新）

**从最新 Pending 向前读取固定条数的连续窗口，超预算则按 Turn 数量折半，整个窗口受同一个 Token Budget 约束**，不再由最早与最晚 Pending 强制覆盖中间全部历史。每次缩减只移除窗口前端（较早）的 Turn，不改变最新 Pending 锚点；交给模型时恢复真实会话顺序。排序优先使用归一化的会话 `timestamp`，并以内部 `id` 打破并列，不把数据库入库 ID 误当作事件发生顺序（Adapter 可能补录历史）。

示例（`P=pending`，`C=complete`；初始读取最后 8 条超预算，折半为 4 条后满足预算）：

```text
Turn 1  (P)      窗口外，保留 Pending
Turn 2  (C)      窗口外
Turn 3  (P)      窗口外，保留 Pending
Turn 4  (C)      窗口外
Turn 5  (C)      窗口外
Turn 6  (P)      本次 Target
Turn 7  (C)      History
Turn 8  (P)      本次 Target
Turn 9  (P)      最新 Pending / 锚点 / 本次 Target
```

本次 `ExtractionContext.turns = [Turn 6 ... Turn 9]`，成功后只把 6、8、9 标记 Complete。Turn 1、3 仍是 Pending；下一次由 Count / Cron Idle / 其他 Trigger 唤醒 Builder 时，较早 Pending 会成为新锚点，**不会因为窗口未覆盖它们就标为完成**。

- 窗口只取同一 Session 的真实连续对话，不能直接拼接跨 Session 的 Raw Memory；历史跨会话整合由 Organization 和 Dreaming 承担。
- 选择到的 Complete/Failed Turn 仅可作背景；提取结果必须至少引用本轮 Pending Target（§9.4）。
- 当存在长期连续不断的新 Pending 时，最旧 Pending 可能延后处理；MVP 接受此边界，Count / Idle Trigger 保持可重复发现的 Pending，必要的公平性策略列为后续 TODO。

### 8.4 Token Budget 与单次固定窗口（2026-10-21 更新）

- **唯一硬边界**：一次 Extraction 的输入应落在配置的整体 Token Budget 内，包含 Prompt、连续窗口中所有 Target / History Turn、协议开销及输出预留；不再沿用旧版「包含全部 Pending + 单独的 5/30 Turn 历史上限」。
- **窗口折半选择**：Builder 以最新 Pending 为锚点，从向前读取的 N 条连续 Turn 开始估算整体 Token；超出预算则 `N = max(1, floor(N / 2))` 后重读，直至首次满足预算即确定本轮窗口。折半只发生在 Builder 内，不额外调用 LLM，也不要求找到最大可容纳 Turn 数；窗口外 Pending 留待后续 Trigger。
- **取消按需追溯**：LLM 只读取本轮已固定的 `ExtractionContext.turns`，不提供追加历史 / `extendHistory()` 等二次读取协议，也不为此额外预留 Token。
- **上下文不足**：只提取能够被当前窗口确实支持的记忆；指代不明或证据不足时不猜测、不强行生成对应记忆。通过正常整批校验后，本轮 Target 仍按 Extraction 成功规则置为 `complete`，不因单项无可提取的记忆而一直挂起。
- **超长 Turn 处理**：**只有 `N=1` 时仍超过预算**，才将锚点标记 `failed` 并继续寻找下一条 Pending；其他 N 的超限只是缩减窗口，不是 Turn 失败。确定性超限和暂时性模型/网络错误必须区分，后者继续保持 Pending。记录错误日志以便后续人工恢复；MVP 不新增失败原因字段、批次表或额外 Builder 接口。初始 N、预算数值、估算余量与未来分段方式留给实现阶段。

### 8.5 不在提取前加载已有 Raw Memory（已确认）

**最终决定**：`ExtractionContext` 仅包含 `sessionKey` 和按会话顺序排列的 `turns`。PreExtractionContextBuilder 不负责检索相关 Raw Memory，Memory Extraction 也不以“已经存在类似记忆”为前置筛选条件。

**原因与边界**：

1. **提取优先**：从当前连续会话窗口识别具有长期价值的原始事实、决定及变化；不提前要求模型判断其是否与其他 Session 已提取的记忆重复。
2. **避免提取前检索的成本和偏差**：单次 Conversation 可能包含多个主题，在尚不明确候选记忆时检索大量已有 Raw Memory，容易引入无关上下文、Token 成本及遗漏新变化的风险。
3. **Raw Memory 定位为原始记忆记录 / 证据层**：允许不同 Turn 或不同时间重复表达同一事实并形成多条原始记忆，保留时间与来源，不能为追求语义唯一性而丢失重复确认或变化的证据。
4. **语义治理后置**：对 Raw Memory 的语义去重、合并、关联、更新和归档由 Memory Organization 负责，不属于 Extraction 或 Builder 的必经环节。
5. **重试与幂等**：本轮 Raw、Pending、Target Complete 同库事务提交，成功后不再重提取同一批 Target；来自不同来源的语义重复由后续 Organization 治理。MVP 不引入 Journal、额外批次幂等键或逐条 Raw 文件。
6. **Recent Memory View 不受影响**：在 Context Retrieval / Bootstrap 阶段，为 Agent 使用的 Recent Memory 逻辑查询窗口继续保留；它与 ExtractionContext 中是否加载 Raw Memory 是不同问题。

PreExtractionContextBuilder 只负责**一次性**选择并冻结预算内的连续会话窗口，不判断长期记忆价值，也不总结或生成 Raw Memory。

### 8.6 尚待明确的 Memory Extraction 细节

- Extraction 整体 Token Budget、初始读取条数 N 与 Token 估算安全余量的具体数值；**最新 Pending 锚定、读取超量按 Turn 数折半、仅 N=1 仍超量标记 `failed` 并继续其余 Pending** 已确定，单 Turn 分段/恢复留作 TODO。
- **不实现按需追溯接口**；旧的独立 5/30 Turn 历史默认值已取消。
- Extraction 输出 JSON、来源引用和整批拒绝规则已在 §9.4 确认；事实、观点与推测优先通过忠实的自然语言内容表达，不预设额外分类字段。
- Raw Memory 五字段模型、ID 规则和同库事务持久化方案见 §10；不再存在每条 Raw Memory 的 VFS JSON 文件，也不需要跨文件与 SQLite 的提交协议。

## 9. Memory Extraction 的语义规范、输出契约与 Raw Memory 核心模型（MVP 已确认）

### 9.1 Memory Extraction 六条核心原则（已确认）

Memory Extraction 从本次 `ExtractionContext.turns` 中，优先分析 **Snapshot 时处于 `pending` 状态的 Target Turn**；`complete` 历史只提供语境、补全指代与必要证据。模型**仅使用本轮固定窗口**，不能请求额外的历史 Turn，也不能把 History 当作新的 Target 重复提取。

1. **价值优先**：只提取对未来理解用户、完成任务、延续项目或复用经验有帮助的信息。
2. **忠实来源**：所有记忆必须有 Conversation 证据，不擅自推断，也不把 Agent 建议、假设或未经验证的推论冒充用户决定／客观事实。
3. **完整原子化**：单条记忆表达独立、可复用的**最小完整语义**，保留必要原因、条件、时间和背景；避免碎片化或堆积互不相关的事实。**对当前状态、持仓、计划、决策、偏好变化等时间敏感信息，应将有依据的发生／生效／确认时间直接写入 `content`**；例如「截至 2026-10-20，用户确认策略 A 当前仓位为 30%」，而不只记录「目前 30%」。
4. **关注重要变化**：优先识别决策、偏好、约束、目标变化、已验证经验、研究结论及失败／否定性结果，重要的决策理由也应保留。
5. **批次内去冗余**：本次 Pending Turn 中对同一信息的重复表述可合并并引用多个来源；**不检索已有 Raw Memory 做跨批次语义去重**，不同时间再次确认的事实允许再次提取。
6. **证据可追溯**：每条输出对应至少一个真实来源 Turn，保留原始陈述中的不确定性；若固定窗口内仍无法确认，不猜测、不强行提取。

这六条用于以后编写和验证 **Extraction LLM Prompt**；目前不制定具体 Prompt，也不要求为六条原则分别调用模型。模型可输出零条至多条原子记忆。未经用户采纳的 Agent 建议不得写成用户决策。

**时间表达规则**：Extraction 可以用来源 Conversation 的 `timestamp` 把「现在／目前／昨天／上个月」等相对时间锚定为有根据的具体日期；若用户明确描述了更早的实际生效时间，应优先保留该生效时间，必要时同时注明「何时确认」。原文没有提供的时间精度不能补造。普通长期稳定事实不必强制添加时间戳；不增加单独的 `eventTime` 字段。

### 9.2 Raw Memory 的定位（已确认）

**Raw Memory 是不可变的原始记忆记录／证据层，而不是去重后、代表当前最新状态的事实集合。**

- 保存一条能够独立理解的自然语言原子信息；必要的理由、**有依据的事实发生／生效／确认时间**、条件以及事实／观点／推测的区别直接保留在 `content` 中。与时间关联度高的状态不能只写「目前」而缺少可判断的新旧语境；无法确认的具体时间不得编造。
- 不要求同一事实在不同时间、不同来源只能生成一条记忆；允许保留重复确认与历史状态变化。
- 写入后原则上不直接改写原始内容；语义去重、合并、关联、状态更新、分类及归档由后续 **Memory Organization** 负责。
- Raw Memory 的生产者不只包括 **EXTRACTION**，还包括未来的 **DREAMING**。Dreaming 可以综合历史会话和已有 Raw Memory 生成有证据支持的归纳，但不能把推断冒充用户直接确认的事实。
- `createdAt` 是记忆生成／入库时间，**不是**所述事件发生时间；事件时间和确认时间应在有来源依据时以自然语言保留在 `content`。Governance 优先使用这一事实时间语义判断新旧，不要求新增独立时间字段。

### 9.3 Raw Memory 最小逻辑模型（已确认）

```kotlin
data class RawMemory(
    val id: String,
    val content: String,
    val generatedBy: MemoryProducer,
    val sources: List<MemorySourceRef>,
    val createdAt: Instant
)

enum class MemoryProducer {
    EXTRACTION,
    DREAMING
}

sealed interface MemorySourceRef {
    data class ConversationTurnRef(
        val runtimeId: String,
        val agentId: String,
        val sessionId: String,
        val sessionTurnId: String
    ) : MemorySourceRef

    data class RawMemoryRef(
        val memoryId: String
    ) : MemorySourceRef
}
```

**字段语义**：

| 字段 | 含义 | 生成方 |
| --- | --- | --- |
| `id` | 长期稳定的 Raw Memory 唯一 ID | 系统 |
| `content` | 忠实、完整、原子化的自然语言记忆 | Extraction / Dreaming |
| `generatedBy` | 记忆由哪一种机制生成（目前 `EXTRACTION` / `DREAMING`） | 系统 |
| `sources` | 可追溯的证据引用，可引用一个或多个 Conversation Turn 或已有 Raw Memory | 模型给出候选；系统验证／规范化 |
| `createdAt` | 本条 Raw Memory 的生成时间 | 系统 |

- **`generatedBy` 与 `sources` 不同**：前者说明由何种机制生产，后者说明事实或归纳依据了哪些原始记录。
- Conversation 证据使用完整稳定业务键 `(runtime_id, agent_id, session_id, session_turn_id)`；单独 `session_turn_id` 可能跨 Runtime / Agent / Session 重复，不能独立引用。
- SQLite 内部 Turn `id`（讨论时也称 `turn_id`）只在 Extraction 执行上下文内定位；**长期持久化引用应转换为稳定的复合业务标识**，以免数据库重建或迁移后自增 ID 变化。
- `RawMemoryRef` 可供 Dreaming 引用已存在的 Raw Memory；引用应可追溯且不可形成循环。MVP 可先关联到 Turn 粒度，不强制精确到 Message。
- 暂不在 Raw Memory 中增加 `type`、`domain`、`importance`、数值 `confidence` 或治理 `status` 等字段；不确定性应忠实表述，而不是依赖一个主观评分。
- 上述是**逻辑模型**；持久化采用 `context.db` 的 `raw_memory` 表，`sources` 初期可作为 JSON 列保留完整稳定引用；无需每条 Raw Memory 单独的 VFS 文件，详见 §10。

### 9.4 Memory Extraction 输出协议与校验（MVP 已确认）

**设计边界**：LLM 只输出原子记忆的 `content` 与证据 Turn 的内部 `id`；系统负责校验、生成技术字段，并转换为长期稳定的 `RawMemory`。Extraction 不加载已有 Raw Memory 进行跨批次语义去重；Dreaming 可另设模型输出协议，但写入统一的 Raw Memory 逻辑结构。

**LLM 结果 JSON**：

```json
{
  "memories": [
    {
      "content": "用户决定暂停策略 A 的研究，原因是近期回测表现不理想。",
      "source_turn_ids": [1001, 1003]
    }
  ]
}
```

**字段与语义**：

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `memories` | array | 必填；可为空数组，代表本批次无需产生 Raw Memory |
| `memories[].content` | string | 必填、非空；按 §9.1 的六条原则表达一条完整、忠实的原子信息 |
| `memories[].source_turn_ids` | array of integer | 必填、非空；为本次模型实际读取的 Conversation Turn **SQLite 内部 `id`**，可引用多个 Turn |

零条结果使用 `{ "memories": [] }`，**属于 Extraction 成功**；在相关成功流程完成后，本批 Target 仍应标记为 `complete`，而不是因为没有记忆继续保持 `pending`。

**证据来源约束（已确认）**：

1. 每条 Memory **至少引用一个在本次固定 Snapshot 中状态为 `pending` 的 Target Turn**，确认有本次新增证据；否则不得仅从 History 重新提取旧记忆。
2. 所有 `source_turn_ids` 必须来自 Builder **一次性固定**且提供给模型的连续 Turn 窗口；不能引用窗口外、不存在、其他 Session 或未提供给模型的 Turn。
3. 可以额外引用一个或多个 History Turn 解释指代、条件和原因；允许单条 Memory 关联多个 Turn。
4. 长期来源不能仅保存 `session_turn_id`，它只在 Session 内唯一；系统将模型返回的内部 Turn `id` 转换为完整 `(runtime_id, agent_id, session_id, session_turn_id)`，写入 `ConversationTurnRef`。

**模型与系统职责**：模型只负责 `content + source_turn_ids`；系统根据已验证的会话记录补全 `id`、`generatedBy = EXTRACTION`、`createdAt`、`sources`，不要求模型提供这些元数据，也不在模型输出中附加 `domain`、`type`、`confidence` 或状态字段。

**MVP 确定性校验**：解析合法 JSON、必需字段及类型正确、`content` 非空、每条 Memory 的来源 ID 列表非空且引用真实可见 Turn，并至少包含一个 Snapshot 的 Pending Target。校验仅针对结构和引用的可判定规则；内容是否忠实仍主要由提取 Prompt 中的六条原则约束。重复的来源 ID 等单纯格式规范化是否自动处理可在实现中确定，不改变整批成功或失败语义。

**整批拒绝（已确认）**：

```text
LLM 返回 0～N 条候选记忆
  → 校验整批 JSON 与每条候选的必需字段、来源
  ├─ 全部通过（包括空数组）
  │    → 转换为 Raw Memory
  │    → 按持久化协议完成保存（空数组则不写记忆）
  │    → 将本次 Snapshot 中的 Target Turn 标记 complete
  └─ 任一条不合法 / 解析失败 / 模型调用失败
       → 本次批次所有候选记忆均不保存
       → 本次 Target Turn 保持 pending
       → 等后续普通 Trigger 再执行
```

- **先校验全部，再写入任何候选**；不得因为部分候选合法而提前保存它们。
- 不允许因单条错误将已固定 Target 的部分记录提前标记 `complete`；也不依据部分合法 Memory 的来源推断某个 Turn 已完整提取。
- **第一版不实现**部分结果保存、自动 LLM 修复、逐条重试、持久化的部分完成状态或复杂失败恢复工作流。
- 此处的“整批”也是**数据库提交单元**：全部校验通过后，本批 Raw Memory、Pending 和 Target Turn 状态同事务写入；事务失败则全部回滚。具体见 §10。

### 9.5 重复与可靠性边界（MVP 已确认）

- **语义重复**：不同 Turn、不同时间表达同一事实，可以各自形成 Raw Memory；由 Organization 后续治理。
- **执行重复**：已提交批次的 Raw Memory 和 Turn 状态同事务落库，避免旧方案部分文件成功导致的重复；来自不同 Turn 或其他生产机制的语义重复仍可存在，交由 Organization 治理。MVP 不另加 Journal 或独立批次幂等键。
- Extraction 整批校验失败时不写入任何候选记忆，Target Turn 保持 `pending`；零记忆结果通过校验后正常完成。
- Extraction 输出整批校验通过后，由同一个 SQLite 事务提交 Raw Memory、Pending 与 Target Turn `complete`；事务失败全部回滚，Target 仍 `pending`。不再存在跨 Raw 文件分批提交产生的半成功状态。

---

## 10. Raw Memory：SQLite 权威持久化（MVP 已确认）

### 10.1 统一数据库与表设计

- **唯一权威数据**：Conversation、Raw Memory、Organized Memory、来源关联和 Pending 均存放于同一个本地 SQLite `context.db`；Memory SQLite **不可删除后依赖 VFS JSON 重建**。该数据库通过原生 SQLite / SQLDelight Repository 访问，不经 VFS 文件接口。
- **物理文件位置**：运行中的 `context.db` 位于普通本地目录，可配置路径；与 VFS 的 Node/ACL/Mount 元数据数据库保持独立。
- **业务不可变**：Raw Memory 插入后不允许通过业务 API 更新/删除；重复 ID 由 SQLite 主键阻止覆盖。五字段逻辑模型及长期稳定来源语义保持 §9.3 不变。
- `sources_json` 暂存原始 `sources` 的规范化 JSON（ConversationTurnRef / RawMemoryRef）；因为 MVP 主要按 Raw ID 做证据追溯，暂不增加第二套来源索引表。有实际结构化反查需求再评估。

```sql
-- 示意 Schema；实际 SQLDelight 迁移及外键策略在实现阶段明确
CREATE TABLE raw_memory (
    id           TEXT PRIMARY KEY,
    content      TEXT NOT NULL,
    generated_by TEXT NOT NULL,         -- EXTRACTION / DREAMING
    sources_json TEXT NOT NULL,        -- List<MemorySourceRef>
    created_at   TEXT NOT NULL
);

CREATE TABLE organization_pending (
    raw_memory_id TEXT PRIMARY KEY REFERENCES raw_memory(id)
);
```

### 10.2 Raw Memory ID（保留既定规则）

格式继续为 **`R-MEM-yyyyMMdd-{8位当天毫秒}-{2位序列号}`**，例如 `R-MEM-20261016-34205123-00`；日期、时区、同毫秒序列和单写入实例算法沿用原设计。

- ID 代表记录身份而非事实发生时间，不以 ID 排序取代来源时间语义。
- 进程内生成 ID 时处理时钟回拨；跨重启仍可能冲突，**数据库主键是最终唯一性约束**。冲突时须重新生成新的 ID 并重试整笔事务（不得覆盖已存在 Raw Memory）。
- ID 编码格式未来需要调整时可单独讨论，不影响本次从 JSON 文件迁移到 SQLite 的设计原则。

### 10.3 Extraction 原子提交

```text
Extraction LLM 返回 0～N 条 Raw 候选
  → 先完成整个输出的结构与证据校验
      ├─ 失败：不写数据库；本次 Target Turn 保持 pending
      └─ 通过：生成 RawMemory 的系统字段
                ↓
        BEGIN context.db TRANSACTION
          → INSERT 本批 raw_memory（允许 0 条）
          → INSERT 对应 organization_pending（默认 pending）
          → UPDATE 冻结 Snapshot 中的 Target Turn = complete
        COMMIT
                ↓
        提交成功后通知 Organization（仅当新增 Raw Memory）
```

- **整批原子性**：插入任一 Raw Memory、Pending 或 Turn 状态失败，事务整体回滚，不暴露部分已提交结果；不会出现旧版“多个 JSON 文件写入一半”问题。事务中只做确定性数据库写入，不在事务中调用 LLM。
- **Target 范围固定**：只能把本次预算内窗口中的 Snapshot Target 标记完成；同 Session 的更早 Pending、快照后新到的 Turn 均须保留原状，等待后续 Trigger。
- **空输出有效**：`memories: []` 时不插入 Raw/Pending，只完成 Target Turn。
- **提交与通知边界**：事务成功提交后执行 Immediate Trigger；若进程在 COMMIT 后、通知前崩溃，已提交 Pending 不丢失，后续由 Cron Periodic Trigger 发现并通知现有 Worker；不因重启引入独立 StartupRecovery 或补偿队列。
- Dreaming 新增 Raw Memory 时也使用相同的 Raw 表和 Pending 入队，但**不修改 Conversation 的 Extraction 状态**；Dreaming 独立记录自己的处理进度，详见 §17。

### 10.4 异常与边界

| 场景 | MVP 行为 |
| --- | --- |
| LLM / 输出校验失败 | 不开启写入事务；Target 保持 `pending` |
| 写入 Raw、Pending 或 Target 状态失败 | 同事务回滚；Target 保持 `pending`，无部分 Raw 成功记录 |
| 事务 COMMIT 成功后进程崩溃 | Raw、Pending、Turn 状态均已持久化；后续恢复工作另列专项 |
| 整批输出 `memories: []` | 在事务内标记 Target `complete`，不新增 Raw |
| Raw ID 主键冲突 | 重新生成 ID，并重试整批事务；不能覆盖已存在记录 |

MVP 不引入自定义 Journal、跨存储分布式事务、额外任务表或批次级复杂幂等协议。这里的原子性依赖**单个 SQLite 数据库的事务边界**，不对 VFS 文件和数据库备份快照承诺同一事务。

## 11. 后续模块：按数据流顺序讨论

| 顺序 | 模块 | 首先要确认的问题 | 状态 |
| --- | --- | --- | --- |
| 1 | Conversation Turn / Adapter / Ingestion | 标准消息、来源标识、校验去重、SQLite 存储 | **已确认** |
| 2 | Extraction Trigger / Scheduler | Count + Idle 规则、Pending 驱动的周期扫描与索引、全局队列、Session 串行 | **已确认（MVP）** |
| 3 | Session Snapshot / PreExtractionContextBuilder | 以最新 Pending 为锚点向前构建总 Token Budget 内的连续窗口；只完成窗口内 Target，`ExtractionContext` 两字段契约 | **已确认（2026-10-20 更新）** |
| 4 | Memory Extraction 原则 / Raw Memory 语义与最小逻辑模型 | 六条提取原则、不可变原始记忆、五字段、可扩展来源 | **已确认（原则与逻辑模型）** |
| 5 | Memory Extraction 输出契约 | `content + source_turn_ids`、目标证据、零结果、严格整批校验和拒绝 | **已确认（MVP）** |
| 6 | Raw Memory 持久化 | `context.db` 不可变 Raw、同库 Extraction 事务、原有稳定 ID | **已确认（MVP，已按新架构修订）** |
| 7 | Memory Organization | Organized Memory 可保存明确的不确定认知；Pending 只用于未完成治理；NEW/MERGE/UPDATE、LLM 候选检索、时间约束、SQLite 事务及单 Worker 串行消费 | **已定稿（MVP；2026-10-19 修订）** |
| 8 | Memory Map | 一份 Markdown 概览／导航、LLM 全量生成、MAX(updatedAt) 检测与合并刷新 | **已确认（MVP）** |
| 9 | Context Retrieval / Bootstrap | 轻量 Bootstrap、LLM Memory Scanner、ID 精确读取、Resource URI 访问；Recent View 延后 | **MVP 方向已确认，接口细节待实施** |
| 10 | Dreaming | 手动/Cron 触发、增量 Topic Discovery、全历史证据聚合、反思输出 Raw Memory、SQLite Checkpoint | **已定稿（MVP）** |
| 11 | 数据可靠性（专项） | Organization Periodic Trigger 已纳入 MVP；SQLite Online Backup API 生成完整快照并经 VFS 保存；不建设 StartupRecovery | **备份 MVP 已定稿；灾难恢复延后** |

## 12. 当前进度与后续专项

Conversation Ingestion → Trigger / Scheduler → ContextBuilder → Memory Extraction → Raw Memory 持久化 → Memory Organization → Memory Map 的 MVP 关键设计均已形成闭环。Raw Memory 提供证据，Organized Memory 维护当前有效认知，Memory Map 提供 Agent 概览与导航。

**Context Retrieval / Bootstrap 的基线见 §16；Dreaming MVP 见 §17**：Memory 通过 ID 和 `searchMemory` 访问，Resources 通过 VFS URI 精确访问；取消 Agent 外部 `query()`，Bootstrap 仅轻量加载 Map。Organization 不再维护 Deferred，遇到无法确定的当前事实时保存明确的不确定认知。Organization Pending 的 Cron Periodic Trigger 已确认，SQLite 完整快照备份 MVP 已定稿（§14）；后续重点进入具体 Prompt、接口和开发细节；Recent Memory View 仍延后实现。

---

## 13. Memory Organization：MVP 设计定稿

### 13.1 Organized Memory 的定位（已确认）

- 采用**独立、可更新、可持久化的 Organized Memory**，用于保存经过治理、可供当前 Agent 使用的长期认知；后续 Memory Map 基于它构建 Agent 概览与导航，不维护独立 Full Memory。
- **Organized Memory 只维护“当前有效认知”**：包括已经确认的事实，也包括**明确说明尚未确认的问题与证据矛盾**。当新证据改变结论或消除不确定性时，更新同一 Organized Memory，保持 ID 稳定；不在其中维护完整历史演变日志。不能把未确认取值写成确定事实。
- 不可变 Raw Memory 的**SQLite 记录**是原始证据，Organized Memory 的**SQLite 记录**是当前有效认知；二者都是权威数据，持久化、备份与恢复基于同一 `context.db`，不再依靠 Raw JSON 文件重建。
- Organized Memory 的**六字段最小持久化模型已确认**（`id/content/rawMemoryIds/domains/createdAt/updatedAt`），详见 §13.7；ID 的具体编码格式、Domain 取值规范以及 SQLite Schema 迁移细节留到工程实现时确定。
- `NEW / MERGE / UPDATE` 是**治理动作**，而不是 Organized Memory 持久状态；事实冲突或证据不足由 `content` 明确表达为不确定认知，不再设 `CONFLICT` 动作、`deferred` 状态或专门的冲突表。关联来源采用独立的 `organized_memory_source` 表（已选方案 B），`rawMemoryIds` 仅作为 Kotlin 逻辑模型字段，由 Repository 组装。

### 13.2 organization_pending：仅保留未完成治理的 Raw Memory（2026-10-19 已确认）

`organization_pending` 是尚未**成功提交 Organization** 的 Raw Memory ID 列表；Extraction 或 Dreaming 创建 Raw Memory 后同事务加入，治理事务成功后删除。**不再存在 `deferred`、`reason` 或其他业务状态列**；只有技术错误、非法 LLM 输出或无法安全验证的操作会导致记录继续留在 Pending 等待重试。

- **事实不确定 ≠ 技术失败**：若两份证据无法确认哪个是当前有效值，Governance 应在 Organized Memory 的 `content` 中明确说明分歧和未确认状态，通过 `NEW` 或 `UPDATE` 正常完成治理并出队；不把语义冲突隐藏在后台。
- **来源幂等**：已成功整合可由权威 `organized_memory_source` 判断。治理前先检查该关联；若 Raw ID 已存在关联，可清理意外残留的 Pending，不再次调用 LLM。
- **可靠提交**：Organized 内容变更、来源关联和 Pending 删除在同一 SQLite 事务中进行；事务失败时不出队。Pending 的补偿唤醒由 Cron Periodic Trigger 完成；数据库备份/灾难恢复见 §14。
- 表结构以 §10.1 为准，只有 `raw_memory_id` 主键与 Raw 外键，不再建立冲突待办或延期重评队列。

### 13.3 不确定认知与 Dreaming 的职责边界（2026-10-19 已确认）

```text
新 Raw Memory → organization_pending
    ↓
Candidate Retriever + Governance
    ├─ 事实明确：NEW / MERGE / UPDATE 正常整合
    ├─ 同一事项证据矛盾且无法判定：
    │    → NEW / UPDATE 明确的「尚待确认」Organized Memory
    │    → 关联全部相关 Raw 证据并从 Pending 出队
    └─ 技术错误 / 不能安全执行治理：保持 Pending，后续重试

未来新 Conversation 给出用户确认
    → Extraction → Raw Memory → Organization
    → UPDATE 原 Organized Memory，消除不确定性
```

- 例如同一策略仓位有 `20%` 与 `30%` 两种证据，无法证明先后或账户范围时，保存「当前仓位尚未确认；历史资料分别出现 20% 和 30%，需要核实」；Agent 可在具体任务中自行询问用户，Context System 不主动发问。
- 如果资料可以通过**事实的发生/生效时间**、账户或适用范围区分，就应如实整合，不能机械地把正常历史变化变成冲突。
- 如果证据不足以可靠判定多条记忆是否属于同一事项，**不得强行合并**；可分别保存有依据的认知，或在本次操作无法安全校验时保持 Pending。取消 Deferred 不意味着允许模型臆测关联。
- **Dreaming 不再承担 Deferred Review**。它回顾历史和跨会话证据，提出有根据的新 Raw Memory；Organization 唯一负责最终治理与消除已有不确定性，详见 §17。

### 13.4 Candidate Retrieval：低成本 LLM 分批全量扫描（MVP 已确认）

**目标**：在新 Raw Memory 进入 Governance 之前，检索描述同一事项、可能需要 `MERGE / UPDATE` 或明确表达不确定性 的现有 Organized Memory。Retriever 只负责候选召回，不执行治理动作，亦不直接写入 Organized Memory。数据从权威 `context.db` 批量读取，不再遍历 VFS JSON 文件。与外部 `searchMemory()` 复用底层 **LLM Memory Scanner** 分批扫描能力，两个业务阶段保持各自的 Prompt 和筛选准则。

**MVP 方案**：**低成本 LLM + 分批全量扫描 + 独立 Governance**，不以 FTS5、Embedding 或向量数据库作为候选召回的必需前置组件。选择这一方案是为了降低初始工程复杂度，直接利用 LLM 识别中文同义表达、上下文与同一事项；并不宣称全量 LLM 扫描绝无漏检。

```text
新 Pending Raw Memory
        ↓
Candidate Retriever
  → 读取当前 Organized Memory（至少 ID + Content）
  → 按 Token Budget 分批；规模较小时可单批
  → 每批调用低成本 LLM，仅选出可能相关的 Organized Memory ID
  → 校验 ID、跨批去重并合并候选
        ↓
Governance LLM（独立步骤）
  → 判断 NEW / MERGE / UPDATE（必要时在 content 表达不确定性）
        ↓
Core 校验与执行写入／Pending 状态变更
```

**接口与实现解耦**：对上层 Governance 暴露稳定的候选检索接口；第一版实现 `LlmCandidateRetriever`，未来可以替换成 FTS5、Embedding、混合召回或其他实现，**不改动治理动作与 Organized Memory 的数据契约**。以下为逻辑接口示意，非最终代码签名：

```kotlin
interface OrganizationCandidateRetriever {
    suspend fun search(rawMemory: RawMemory): List<OrganizedMemory>
}

// MVP 实现：低成本 LLM 扫描全部 Organized Memory，按预算分批选择候选
class LlmCandidateRetriever : OrganizationCandidateRetriever
```

**执行约束与边界**：

1. **扫描范围**：所有当前有效 Organized Memory 都有被纳入候选检查的机会；不得用来源 Agent 或 Domain 做硬过滤。具体批次大小由 Token Budget 控制，不固定每批条数。
2. **低成本筛选**：每批向 LLM 提供 Raw Memory 和该批 Organized Memory 的 ID／内容，要求返回**候选 ID**，不是直接修改记忆或判定最终治理动作；Core 校验返回 ID 必须属于该批输入。允许候选较宽松，最终由 Governance 判断。
3. **串行分批聚合（MVP 已确认）**：按顺序逐批调用低成本 LLM，所有批次均正常完成后，合并去重候选，再提交独立 Governance。默认不启动并发扫描；候选总量限制和重排序留待有实际需求后评估，不在 MVP 固化会造成漏检的截断规则。
4. **失败保护**：任一必需扫描批次失败或输出无法校验时，**不得把不完整扫描等同于“未发现相关记忆”并据此创建 NEW**；本次检索／治理失败，Raw Memory 保持 `pending`，后续重试。完整扫描返回空候选可以交由 Governance 按 NEW 规则判断，但仍接受模型漏检风险。
5. **治理前强制去重检查（已确认）**：从 `organization_pending` 取到 Raw Memory 后，**必须先**查询 `organized_memory_source` 中是否已有该 `raw_memory_id`。若已引用，则跳过 Candidate Retriever 与 Governance，在事务内清理残留 Pending；若未引用才开始候选检索。关联表本身是事务维护的权威关系，不需要额外可重建反向索引；数据库访问出错时必须中止该次治理，不能当作“没有引用”。
6. **可替换与升级**：在真实记忆样本上关注 MERGE／UPDATE 候选漏检、重复 NEW 的发生情况，以及每条 Raw Memory 的 Token 成本与处理耗时；当规模或效果要求不再适合全量扫描时，再考虑 FTS5、Embedding、混合检索等优化。

**与 Governance 的职责边界**：Candidate Retriever 负责“可能关联哪些 Organized Memory”，Governance 负责“这条 Raw Memory 应当 NEW／MERGE／UPDATE，以及如何如实表达未确认的信息”；双方使用不同的 LLM 调用阶段，可以分别选择模型。后续如优化成批量处理多条 Pending Raw Memory，必须重新确认快照与写入的先后顺序，避免使用过期候选导致重复 NEW；此优化非 MVP 必需。

### 13.5 Organization 串行处理策略（MVP 已确认）

```text
一次取出一条 Pending Raw Memory
    ↓
Core 强制检查：该 Raw Memory ID 是否已被 Organized Memory 引用？
    ├─ 是 → 不再调用 LLM；核实后从 Pending 出队 → 处理下一条
    └─ 否 ↓
Candidate Retriever：逐批串行扫描全部 Organized Memory
    ↓ 所有批次完成，候选合并去重
Governance：独立 LLM 判断治理动作
    ↓
Core 校验、在 SQLite 事务中写入 Organized Memory 与来源关联
    ↓ 成功后删除 Pending
再取下一条 Pending Raw Memory
```

- **同一时间只执行一条 Raw Memory 的 Organization 流程**，Candidate Retriever 批次也顺序执行，不因多个 LLM 请求技术上可并行就默认并行。
- 单条治理完成并落盘后才处理下一条，确保下一条检索可看到上一条最新的 Organized Memory，降低并发产生重复 `NEW` 或过期 `UPDATE` 的风险。
- 某次 Candidate Retriever 扫描或 Governance 技术失败时，本条保持 `pending`，不能把不完整候选当成 NEW；异常重试策略可后续细化，不能因持续失败永久阻塞所有后续 Raw Memory。
- 未来其他模块也默认串行；只有真实延迟、吞吐或成本数据表明必要时，才按模块局部引入并发。串行仅降低竞争复杂度，不替代持久化、幂等与恢复规则。

### 13.6 Governance 输入、输出与动作规则（MVP 已确认）

**输入**：当前一条 `pending` Raw Memory，以及 Candidate Retriever 完整扫描后选出的 Organized Memory 候选。至少向模型提供 Raw Memory 的 `id/content/createdAt` 和候选的 `id/content/domains/createdAt/updatedAt`；其中系统时间字段仅用于辅助理解数据来源和治理时序，**不可直接当成事实发生时间**。时间敏感认知的发生／生效／确认时间应由 Extraction / Dreaming 在有来源依据时写入 `content`，Governance **优先依据内容中的事实时间语义**判断新旧，而非默认回读来源 Conversation；内容不足以判断时保持不确定性，不猜测时间或顺序。**治理前必须先执行 §13.4／§13.5 的来源引用检查**，避免重复调用模型。

**输出**：一次结构化 Governance Decision，**MVP 一条 Raw Memory 只执行一个主要动作**；以后若确有跨多条 Organized Memory 的写入需求再扩展协议。字段示意：

```kotlin
data class GovernanceDecision(
    val action: GovernanceAction,
    val targetMemoryId: String? = null,
    val content: String? = null,
    val domains: List<String>? = null
)

enum class GovernanceAction { NEW, MERGE, UPDATE }
```

| 动作 | 模型必填 | Core 执行 | Pending 结果 |
| --- | --- | --- | --- |
| `NEW` | `content`、`domains` | 创建 Organized Memory 并插入来源关联 | 同库事务提交后出队 |
| `MERGE` | `targetMemoryId`；`content` 可选 | 关联新增来源；必要时完善兼容内容，保持目标 ID | 同库事务提交后出队 |
| `UPDATE` | `targetMemoryId`、`content`；`domains` 可按规则更新 | 用新内容表达当前认知（可明确尚待确认）；保持目标 ID 和历史来源关联 | 同库事务提交后出队 |

**语义约束**：`MERGE` 用于重复确认或兼容补充；`UPDATE` 用于已有认知内容的变化，**也包括把原先确定的内容修正为尚未确认，或在用户确认后消除不确定性**。无相关候选且信息可独立成立时可执行 `NEW`；新认知本身也可以是「尚待核实」的明确问题。发生证据冲突时，必须保留分歧及不确定的语义，不得猜测当前值，也不需要单独的 `CONFLICT` 动作。**判断事实先后时不能仅依赖 Raw Memory 的创建或入队顺序；被纠正的旧 Raw 是历史证据，不能仅因 Dreaming 重新提及就变成新的当前事实。**LLM 生成内容和分类建议，不直接控制 SQLite。

**Core 确定性校验与赋值**：

1. 校验结构、动作必填字段、非空内容／合理分类，以及 `targetMemoryId` 必须属于本次输入的合法候选集合；避免模型操作未授权的其他 Organized Memory。
2. 由 Core 生成 `NEW` 记录 ID、`createdAt/updatedAt`，维护当前 Raw Memory 的 `rawMemoryIds` 关联（去重）。模型不能伪造或覆盖这些字段；`UPDATE` 保持原 Organized Memory ID，保留旧来源以支持溯源。
3. 模型非法输出、超时、目标无效等均为**技术失败**，Raw Memory 保持 `pending`；事实中的不确定性则应如实写进 Organized Memory，不能与技术执行失败混为一谈。
4. 返回内容必须符合“当前认知”的语义（包括清楚标注未确认状态），不把 Organized Memory 变成完整历史变更日志；来源列表中的旧 Raw Memory 是**历史证据链**，不意味着每条旧证据都支持当前最新取值。

### 13.6.1 Governance 时间先后判断（已确认，须纳入 Prompt）

**核心要求：治理依据事实发生或生效的时间顺序，而不是 Raw Memory 被创建、补录、入队或执行 Organization 的先后顺序。** 旧对话、历史文件和 Dreaming 都可能在较晚时间产生一条描述更早事实的 Raw Memory；不能把“后来写入”误当成“更新的事实”。

- **区分三类时间**：① `content` 中明确表达的**事件／生效／确认时间**（由 Extraction / Dreaming 根据来源补足相对时间）；② `RawMemory.createdAt`（系统生成这条 Raw Memory 的时间）；③ `OrganizedMemory.createdAt/updatedAt`（系统建立或最近修改该 Organized Memory 的时间）。后两者是系统记录时间，**不自动证明哪个事实更晚发生**。不为此新增 `eventTime` 字段。
- **UPDATE 的前提**：必须有足够证据表明新信息是对当前有效认知的后续变更，或明确纠正了原有结论；即使新 Raw Memory 的 `createdAt` 更新，也不能单凭这一点执行 UPDATE。
- **补录旧信息**：若能明确判断它属于既有当前认知之前的历史阶段，且与现有演变逻辑兼容，优先把它作为历史来源证据关联，**不把当前 `content` 回退为旧状态**；适用时可用 `MERGE` 完成来源关联。
- **时间缺失或互相矛盾**：若来源没有可靠时间线、账户／范围或生效关系，无法确定哪个值代表当前状态，使用 `NEW` 或 `UPDATE` 保存**清楚陈述来源分歧及尚待确认的当前认知**；不得依赖入队先后猜测。待用户在后续 Conversation 中澄清，再由正常 Extraction → Organization 更新。
- **维持当前认知模型**：即使引用了历史来源，Organized Memory 的 `content` 仍只描述当前认知（可包含尚未确认的事实）；完整历史过程可通过 Raw Memory 证据引用溯源，不新增时间线或版本字段。

**示例**：现有 Organized Memory 为“用户已于 10 月 15 日恢复策略 A 研究”；10 月 17 日补录一条明确记载“用户在 10 月 1 日暂停策略 A”的 Raw Memory。该记录更晚进入系统，但描述的是**更早的状态**；不应将 Organized Memory 更新回“当前暂停”。如事实时间清晰且兼容，可以只追加历史证据来源。

**未来 Governance Prompt 必须显式强调**：先判定记忆涉及的是过去、现在、计划还是明确更正；审视事件／生效时间及适用范围；严禁以 `createdAt`、`updatedAt`、入队顺序或治理顺序代替事实时间；不足以判定时不要猜测或覆盖。尤其当新 Raw 来自 Dreaming 时，不得因为旧 Raw 被重新召回或概括，就回退已被用户明确纠正的 Organized 当前认知；若新增原始证据确实支持再次改变结论，则按实际时间与适用范围重新治理，不将 Organized 视为不可质疑。

### 13.7 Organized Memory 最小模型与 SQLite 存储（MVP 已确认）

```kotlin
data class OrganizedMemory(
    val id: String,
    val content: String,
    val rawMemoryIds: List<String>,
    val domains: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant
)
```

**六字段逻辑模型不变**：`id` 是稳定记忆身份，MERGE / UPDATE 继续保持同一 ID；`content` 表达当前认知，必要时明确标注尚未确认；`domains` 支持多领域；`rawMemoryIds` 包含全部历史来源（去重）；时间字段由 Core 维护。

**已确认方案 B：独立来源关联表**。不在 `organized_memory` 主表中重复持久化 `raw_memory_ids` JSON，Repository 查询关联表后组装 `rawMemoryIds`。同一 Raw Memory 暂按一条主要治理动作执行，但表结构不强制禁止未来多对多引用。

```sql
CREATE TABLE organized_memory (
    id         TEXT PRIMARY KEY,
    content    TEXT NOT NULL,
    domains_json TEXT NOT NULL,       -- List<String>，MVP 存储示意
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_organized_memory_updated_at
ON organized_memory(updated_at);

CREATE TABLE organized_memory_source (
    organized_memory_id TEXT NOT NULL REFERENCES organized_memory(id),
    raw_memory_id       TEXT NOT NULL REFERENCES raw_memory(id),
    PRIMARY KEY (organized_memory_id, raw_memory_id)
);

CREATE INDEX idx_organized_memory_source_raw
ON organized_memory_source(raw_memory_id);
```

- `organized_memory_source` 是**权威证据关联**，用于从 Organized 查 Raw、按 Raw 判断是否已治理；不是派生缓存表。相关外键与 SQLite `PRAGMA foreign_keys=ON` 在实现时启用。
- `domains` 可以暂存规范化 JSON 数组；MVP 对 Agent 不开放独立 `query()`，内部如需 Domain 过滤可先采用简单 SQL/Repository 逻辑，暂不新增 Domain 关联表。
- 对任意真正成功的 Organized Memory 变更（含仅增加来源证据的 MERGE），Core 更新 `updatedAt`；在 MVP 单实例串行提交下确保**全局严格递增**，以供 `MAX(updatedAt)` 驱动 Map 刷新。
- 单条 Organized Memory 不再对应文件或 VFS URI；外部通过 `getMemory(id)` 访问。数据库备份机制见 §14。

### 13.8 Organization 原子提交与失败边界（2026-10-19 已确认）

```text
取出 organization_pending 的一条 Raw Memory
  → 查询 organized_memory_source 是否已关联该 Raw ID
    ├─ 已关联：同库事务清除残留 Pending，不再调用 LLM
    └─ 未关联：LLM Memory Scanner → Governance → Core 校验
        ├─ NEW / MERGE / UPDATE：BEGIN TRANSACTION
        │     → INSERT/UPDATE organized_memory（含明确的不确定认知）
        │     → INSERT organized_memory_source（防重复）
        │     → DELETE organization_pending
        │   COMMIT
        └─ 技术/校验失败：事务不提交，Raw 保持 Pending
  → 处理下一条
```

- 内容、来源关联、Pending 删除在**同一个 `context.db` 事务**完成；失败时整体回滚，不会因为证据语义冲突而创建 `deferred` 记录。
- 保留治理前权威来源关联的强制检查，防止重复治理；来源检查失败不得冒险执行 NEW。
- 不确定认知是正常的治理结果，**不是**等待 Dreaming 后台修复的业务状态。Agent 可以按任务需要向用户求证，确认结论经新 Raw Memory 再次治理。
- LLM 在短事务之外运行；SQLite 事务不涵盖 VFS `map.md` 或备份快照，其派生刷新仍独立执行。

### 13.9 Immediate + Periodic Trigger、入队与 Trigger Counter 调度（MVP 已定稿）

**触发策略**：Raw Memory 由 Extraction 或 Dreaming 在 `context.db` 同一事务中与 `organization_pending` 共同提交；**事务成功提交后**执行 Immediate Trigger。一次写入多条 Raw Memory 可以先批量入队，再通知一次。为覆盖提交成功但通知丢失、进程重启遗留或上轮治理失败的 Pending，MVP 额外实现 Cron Periodic Trigger。**两种 Trigger 都只负责唤醒，不携带 Raw Memory ID，不承担去重、重试或治理职责**；待处理数据始终从 SQLite 读取。

| Trigger | 触发时机 | 检查与输出 | MVP |
| --- | --- | --- | --- |
| **Immediate Trigger** | 新 Raw Memory + Pending 事务提交成功后 | 向 Organization Scheduler 发送一次 Trigger | 实现 |
| **Periodic Trigger** | 由可配置 Cron 定时执行 | `organization_pending` 非空时发送一次 Trigger，否则不做事 | 实现 |

Periodic Trigger 的扫描 SQL 不需要额外索引（`organization_pending.raw_memory_id` 已是主键），只需判断是否存在数据：

```sql
SELECT EXISTS (SELECT 1 FROM organization_pending);
```

配置示意（均可调整；Cron、时区可配置；下例使用五字段 Cron，仅用于说明）：

```yaml
organization:
  triggers:
    immediate:
      enabled: true
    periodic:
      enabled: true
      cron: "*/10 * * * *"  # 示例：每 10 分钟检查一次
      timezone: "Asia/Shanghai"
```

- **触发器职责边界**：Periodic Trigger 只查 Pending 是否非空，结果为 true 就通知原有 Scheduler；不直接扫描并治理 Raw，不保存上次扫描水位，不维护任务状态；与 Idle Trigger 一样不处理重复触发、持续失败和退避策略，这些作为 Scheduler / Worker 的后续 TODO。
- **运行与重启**：系统正常初始化时注册 Cron，首次启动、正常重启、异常重启均走相同流程；不立即补跑停机时错过的每次定时事件，也不建设 StartupRecovery。丢失的内存通知可在后续 Cron 中由 SQLite Pending 重新发现。未启用 Periodic Trigger 时不再保证长期遗留 Pending 自动被唤醒，因此 MVP 推荐启用。
- **统一 Cron 约定**：Idle Trigger、Organization Periodic Trigger、Dreaming 均支持 Cron 和可配置时区；采用哪种 Cron 方言、表达式校验与调度器复用方式在实现阶段统一约定，不引入额外持久化 Cron 任务表。

**MVP 调度结构**：**内存 Trigger Counter + 单 Worker + SQLite `organization_pending`**。组织治理不维护第二套任务队列，也不为每条 Raw Memory 单独创建任务执行状态。

- `triggerCount`：进程内**尚未消费的 Trigger 次数**，初始为 `0`；每收到一次有效 Trigger，计数加 `1`。
- `workerRunning`：是否已经有 Organization Worker 在运行。若为 `false`，首次触发启动唯一 Worker；运行中收到新 Trigger 时只增加计数，**不启动第二个 Worker**。
- Worker **每一轮**从 SQLite 查询当前全部 `organization_pending` 记录，按入队顺序逐条串行处理。**完成一整轮后**将 `triggerCount` 减 `1`，而不是每处理一条 Raw Memory 减 `1`。
- 若本轮结束后 `triggerCount > 0`，继续下一轮，**重新查询 SQLite**；若计数为 `0`，则将 Worker 标记为空闲并退出。即使本轮已经处理了后来入队的 Raw Memory，后续计数对应的轮次可能扫描空集合，允许这种少量冗余。

```text
Raw Memory 与 organization_pending 已在同一事务提交（pending）
  → Trigger 到达：triggerCount += 1
       ├─ Worker 已运行：仅累加计数
       └─ Worker 未运行：启动唯一 Worker
  → Worker 开始一轮：查询本轮全部 Pending
       → 逐条治理，先执行来源引用强制检查
       → NEW / MERGE / UPDATE：保存成功后出队
       → 来源存在无法判定的矛盾：如实写入尚待确认的 Organized Memory，并出队
       → 技术失败：保持 pending，跳过该条
  → 本轮结束：triggerCount -= 1
       ├─ triggerCount > 0：重新查询 pending，执行下一轮
       └─ triggerCount = 0：Worker 退出，等待新 Trigger
```

**举例**：R1 入队并触发时 `triggerCount = 1`，Worker 开始第一轮；第一轮执行期间 R2、R3 分别入队并触发，计数变成 `3`；第一轮结束减至 `2`，继续下一轮；第三轮完成后减至 `0`，Worker 退出。新任务始终由 SQLite Pending Queue 提供，不依赖 Trigger 的参数或另存任务 ID。

**执行及正确性约束**：

1. **一次 Trigger 对应一次扫描轮次，不等于只处理一条 Raw Memory**。一轮可以处理多个 Pending；轮内每个已读取的 Raw Memory 最多尝试一次。技术失败后保持 `pending`，本轮跳过并继续其他任务；如后续确有新 Trigger 引发下一轮，允许重新尝试失败项，不因为 `pending` 残留而自行无限循环。
2. **并发触发与退出原子性**：`triggerCount` 的增减、`workerRunning` 状态变更和是否启动／退出 Worker 的判断必须在同一同步边界内协调（例如 Kotlin `Mutex` 或锁），保证 Worker 判断退出与新 Trigger 同时到达时不会漏唤醒。单实例默认串行治理，不引入多 Worker。
3. **入队顺序不代表事实时间**。新 Raw Memory 与补录的旧事实均可触发治理，Governance 仍遵守 §13.6.1 的事件／生效时间判断。
4. **没有 Deferred 状态**。所有真实语义上的未知、矛盾均由 Organized Memory 的内容表达；只有技术执行或校验失败的 Raw Memory 继续留在 Pending。
5. **内存计数不是持久工作来源**。进程崩溃或提交后通知丢失，不能仅靠 Trigger Counter 保证跨重启不漏处理；已提交 Raw 与 Pending 同库存续。Cron Periodic Trigger 按正常调度重新发现遗留 Pending，无须 StartupRecovery。

### 13.10 MVP 定稿范围与保留议题

**本轮已经确定**：六字段 Organized Memory（可准确表达当前尚未确认的认知，以 `context.db` 为权威）；Raw Memory 不可变证据；`organization_pending` 仅存未治理完成的 Raw ID；治理前来源关联幂等检查；低成本 LLM Memory Scanner 串行全量候选扫描；Governance 仅用 `NEW/MERGE/UPDATE` 且严格校验；按事实／生效时间而非入队时间更新；一条 Raw Memory 一次主要动作；同一 SQLite 事务提交 Organized/来源关系/出队；单 Worker、Immediate + Cron Periodic Trigger 与 Trigger Counter 调度；不建立 Deferred、冲突状态机或专门的 Deferred Review。

**不阻碍定稿的后续议题**：完整 Prompt 文案及具体模型配置、Organized Memory ID 编码与 Domain 枚举规则、SQLite Schema/迁移细节、可观测性及测评阈值、性能驱动的 Retriever 升级，以及备份文件留存／复杂灾难恢复后续专项（§14）。不在 MVP 中增设治理 Journal、历史版本状态机、Embedding 服务或额外调度队列。

## 14. SQLite 数据库备份：MVP 设计（2026-10-20 已定稿）

### 14.1 范围与职责

第一版**只实现 `context.db` 的完整一致性备份文件生成与保存**。运行中的 `context.db` 是 Conversation、Raw Memory、Organized Memory、来源关联、Pending、Dreaming Checkpoint 的单一权威结构化数据库，直接通过 SQLite 连接访问，**不通过 VFS 访问运行库**。VFS 的 Node/ACL/Mount 元数据 SQLite 属于 VFS 自身，备份机制由 VFS 独立负责，本节不改变 VFS 设计。

- **DatabaseBackupService**：调用 SQLite Online Backup API，从运行中的 `context.db` 生成一个一致性的、可独立打开的 `.db` 副本。
- **VFS**：负责接收和保存已经生成成功的普通备份文件；具体存储可由部署决定，例如 Local FS + Git 或 WebDAV。本节**不绑定 Git、不要求 Context 实现同步逻辑**。
- **触发**：支持调用备份服务手动执行；若需要自动执行，直接复用已有 Cron 调度能力，不建设独立任务系统。具体备份频率、留存数量和目标 Mount 在开发／部署时选择。

### 14.2 备份实现与最小流程

Kotlin/JVM 使用已有 Xerial SQLite JDBC 对 SQLite **Online Backup API** 的封装（例如 `org.sqlite.SQLiteConnection.getDatabase().backup(...)`），可与 SQLDelight 的常规数据访问并存；具体驱动方法签名和返回码按项目实际依赖版本核实。无需自己编写 JNI 或运行外部 sqlite3 命令。

```text
手动调用 / 已配置的 Cron
    → DatabaseBackupService
    → 打开独立的 SQLite 源连接
    → Online Backup API 生成本地临时 .db 快照
    → 确认 API 成功，关闭连接，必要的基本可读性检查
    → 将完成的备份文件发布到 VFS 的指定备份目录 / Mount
    → 清理临时文件
```

**约束**：

1. 备份以 SQLite API 创建一致性快照，允许源库继续正常读写；API 成功后生成的是可独立使用的完整数据库副本。
2. **不使用文件复制命令直接备份运行中的 `context.db`**，也不手动拷贝或拼装 `-wal`、`-shm` 文件。
3. 在临时路径生成备份，成功后才发布，不覆盖唯一一份既有有效备份；失败则报告错误并清理不完整临时文件，不影响已提交的正常业务事务。
4. 文件保存位置与传输方式是 VFS / 部署配置的选择，**Context 只生成备份文件并交付**；不把「快照已完成」混同于「远程同步已完成」。
5. MVP 可做简单的备份文件可读性检查（例如重新打开并执行 `PRAGMA quick_check`）；不引入备份记录表、复杂验证平台或自动恢复流程。

### 14.3 非 MVP 与启动边界

- **暂不实现**自动灾难恢复、增量备份、复杂留存与清理策略、备份任务队列、VFS 元数据恢复或专用备份管理系统。未来如需人工恢复旧快照，应停止数据库访问并保证恢复文件与旧 WAL/SHM 隔离，避免误覆盖权威数据。
- **不建设 StartupRecovery**：正常启动注册 Count + Cron Idle Trigger、Organization Immediate + Cron Periodic Trigger 和 Dreaming Cron；未完成任务依据 SQLite 中的 Pending / Checkpoint 在后续正常触发中继续处理，Memory Map 按当前更新检测机制维护。
- 后续再根据实际数据库规模、备份存储成本、风险要求评估保留份数、远程副本、恢复操作和恢复演练；不阻碍 MVP 开发。


## 15. Memory Map：MVP 设计（已定稿）

### 15.1 名称、目标与核心内容

**本模块正式命名为 `Memory Map`，不再称为 `Memory Projection`。** 其职责不是复制或维护另一套记忆文档，而是为 Agent 提供长期记忆的**概览与导航入口**，回答「系统了解用户哪些重要事项」「有哪些记忆领域和主题」「该如何按需检索详细信息」。

MVP 将两部分看成一个统一 Memory Map 的逻辑内容，而不是两套独立文件体系：

- **Memory Overview**：精炼的长期背景、目标、偏好、约束及重要当前认知，帮助 Agent 快速建立用户背景。
- **Memory Navigation**：现有记忆领域、主要主题和查询指引，让 Agent 知道有哪些内容可以进一步读取。

### 15.2 与其他模块的职责边界

| 模块 | 职责 |
| --- | --- |
| Raw Memory | 不可变原始记录和历史证据 |
| Organized Memory | 维护当前有效认知，作为 Memory Map 的主要事实来源 |
| **Memory Map** | 为 Agent 提供认知概览、知识发现和导航指引 |
| Memory Retrieval | 通过语义 Search / Memory ID 获取当前 Organized Memory，按 ID 追溯 Raw 证据；结构化 SQL 查询仅内部使用 |
| Context Bootstrap | 轻量返回 Memory Map 与检索能力说明；不按任务主动搜索详细 Memory / Resources；Recent View 预留后续 |
| Resources | 存放独立文档、报告、研究资料及大体量专题内容；Memory Map 可引导读取相关资源 |

**已确定的简化原则：**

1. **取消 Full Memory**：详细记忆由 Retrieval 对 Organized Memory 条件查询，不单独维护完整记忆 Markdown 文件。
2. **不强制保留独立 Summary / Category Index 体系**：其摘要与导航能力统一体现在 Memory Overview / Navigation 中；无需预设两套持久化及刷新流程。
3. **Memory Map 不是新事实来源**：不从旧 Map 推导或升级新事实，不因 Map 内容过时而覆盖 Organized Memory。涉及当前状态的具体任务应读取最新 Organized Memory。
4. **Domain 与 Agent 身份解耦**：Map 描述记忆内容及查询入口，不决定固定 Agent 或访问权限。
5. **个人记忆与大体量资料分工**：高体量的专项研究、资料或分析内容宜保存在 Resources，以少量必要的当前认知和导航帮助 Agent 找到它们；无须人为规定万条级规模或其他硬阈值，也不能仅因资料量大就丢弃 Organized Memory 中仍重要的用户事实。

### 15.3 MVP 输出与存储（已确认）

- **一份统一 Memory Map**，不按 Agent 或 Domain 独立维护多套文件；内容由 **Memory Overview + Memory Navigation** 两部分组成。
- **Markdown 持久化**，稳定路径：`alcyone://memory/map.md`；由 VFS 存储，便于直接查看内容与通过 Git Diff 检查生成质量。Map 从 SQLite 中的当前 Organized Memory 派生，不是新的事实来源。
- **Overview**：提炼重要背景、长期目标、稳定偏好、约束与当前重要认知；**Navigation**：真实存在的 Domain、领域内主要主题、可进一步查询的内容及自然语言检索提示。避免罗列所有 Organized Memory、冗长历史或把查询 API 协议写死在 Map 中。
- 推荐输出预算约 **1,000～2,000 Token**，作为可配置的初始目标而非固定截断上限；不因数据量增长而按比例扩张 Map。复杂分类树、独立 Shared/Domain Map、Full Memory、独立 Summary 和 Category Index 均不进入 MVP。
- 大体量专题资料、研究文档优先保存为 Resources；Map 可给出内容导航，不复制这些文档。Memory Retrieval 可以直接查询 Organized Memory 取得详细记忆。

建议的 Markdown 元数据（字段名称按 MVP 逻辑确定，具体序列化可在实现时统一）：

```yaml
---
generatedAt: 2026-10-17T22:00:00+08:00
sourceUpdatedAt: 2026-10-17T21:58:00+08:00
---
```

- `generatedAt`：本次 Map 实际生成／发布时间；**不能用来推断 Map 是否覆盖最新记忆**。
- `sourceUpdatedAt`：本次生成所读取 Organized Memory 快照中的 `MAX(updatedAt)`；用于检测是否过期。无 Organized Memory 时允许明确的空集合标记，不能误把缺少 Map 当成有效。

### 15.4 生成方式与数据约束（已确认）

```text
从 context.db 读取全部当前 Organized Memory（形成一次生成输入）
  → 按稳定方式组织输入，记录 sourceUpdatedAt = T1 = MAX(updatedAt)
  → LLM 提炼 Overview 与 Navigation
  → Core 校验事实边界、合法 Domain、必需部分及输出大小
  → VFS 安全替换 map.md，Front Matter.sourceUpdatedAt = T1
      （Map 采用 T1 固定快照 + 最终一致性；生成完成后直接安全替换，不二次查询 T2，不引入发布协调锁；
        stale 状态由 Map 模块与 Cron 补偿机制处理，详见技术实现设计 §7.3；已批准变更（2026-10-25））
```

- **输入只采用最新 Organized Memory**，不把旧 Map 作为新的事实证据反复总结；LLM 可以提炼主题，但不得捏造用户事实、Domain 或具体导航目的地。Domain 的实际名称／范围由 Core 提供并校验。
- **全部记忆用于生成**：MVP 假定个人记忆规模可由所选 LLM 上下文容纳；如果实际超过 Token Budget，不得悄悄截断并仍声称全量覆盖，后续再设计分批策略。
- Map 是导航而不是替代当前事实检索；涉及具体状态、最新约束、研究参数等，Agent 应通过 Retrieval 读取 Organized Memory，并在必要时核实 Raw Memory 证据。**无法确定的当前事实不得在 Map 中概括成确定值**；重要的未确认事项可以如实保留为导航提示。
- VFS 发布需保证文件完整替换（见技术实现设计 §7.3 与 G3 安全替换缺口记录），不引入 T2 检查或协调锁；允许新 Organized 在生成期间提交，本次 Map 可短暂落后，后续 Cron 追平；Agent 精确当前状态通过 `getMemory`/`searchMemory` 获取。（已批准变更（2026-10-25））

### 15.5 更新检测、触发与时效性（已确认）

- **不为 Organized Memory 建历史版本**：其 ID 继续代表稳定认知，NEW / MERGE / UPDATE 对同一记录的更新仍在原 ID 上完成。
- **不引入 Fingerprint 或独立全局 Revision**：使用 `MAX(OrganizedMemory.updatedAt)` 与 `map.md` 的 `sourceUpdatedAt` 比较来识别变化。`updatedAt` 表示 Organized Memory **任何成功持久化变更**，包含仅增补 `rawMemoryIds`、未改变 `content` 的 MERGE。
- **接受冗余刷新**：仅新增证据可能引起 Map 内容不变但仍触发生成；MVP 优先正确性和简单实现，不另加内容变化时间戳或全文比较。
- **严格递增前提**：单实例串行写入时应确保每次成功提交的 `updatedAt` 全局严格递增；相同时间精度、时钟回拨可能导致 `MAX(updatedAt)` 漏检，必须在实现中解决。**MVP 不进行 Organized Memory 的物理删除**；未来支持删除或多实例时重新评估水位方案。
- **合并刷新**：Organized Memory 成功变更后需要刷新检查，优先在 Organization 当前批次／轮次结束时聚合触发一次；不维护独立 Map Pending Queue 或复杂 Scheduler。未来直接修改 Organized Memory 的其他入口也必须触发相同检查。
- **Memory Map 模块初始化检查（已确认）**：Memory Map 服务每次正常初始化时读取 `map.md` 的 `sourceUpdatedAt` 并与 SQLite 的 `MAX(organized_memory.updated_at)` 比较；Map 缺失或水位不一致，则**异步安排一次 Map 生成/刷新**。首次启动、正常重启和异常重启采用同一初始化逻辑；无需 StartupRecovery。初始化检查不阻塞正常 Memory 查询。
- **失败不影响权威数据**：Map 生成／校验／保存失败时保留上一份可读取 Map，不回滚 Organized Memory；不能将旧 Map 误标为最新。首次 Map 缺失由模块初始化触发生成；日常变更由 Organization 触发。持续失败由 Map Cron 补偿机制处理（技术实现设计 §7.5），默认启用 `*/10 * * * *`（上海时区）；不承诺固定时间内追平。（已批准变更（2026-10-25））
- **读取最新性**：Memory Map 服务维护/暴露当前 Map 及其 stale 信息（必要时只读比较 `MAX(updatedAt)`），MVP 允许返回旧 Map 但必须标明 stale；Agent 要求准确当前事实时直接查询 Organized Memory。**Bootstrap 只读取已有 Map 状态并组装 Context，不启动 LLM、刷新任务或其他后台业务**。

### 15.6 给 Agent 的交付边界（已确认方向）

- 统一 Map 不按固定 Agent 角色分别生成；MVP Bootstrap 默认返回已有 Map（含 `stale` 状态）与工具使用说明，不根据任务自动搜集详细记忆，**不负责检查或触发 Map 刷新**。第一版使用可信 Agent 的基础授权，不建设细粒度 Agent / Domain ACL；敏感数据权限后续单独升级。
- Map 提供「有哪些记忆、该向哪里查询」；具体 Search / Read 的调用协议应由 Retrieval／Runtime 的工具规范提供，而不是与 Map 文本耦合。
- Recent Memory View 仍作为逻辑视图保留在设计中，但**不进入 MVP 开发范围**；MVP Bootstrap 不返回 Recent View，Agent 通过 Memory Search / Get 读取已治理记忆。未来确需补齐 Map 刷新延迟时再实现。

### 15.7 暂留实现细节

实际 LLM Prompt、Map Markdown 精确格式、输出校验、持续失败后补偿策略、稳定时间戳实现、超预算时的生成策略以及 `stale` API 契约留待开发细化；**首次初始化的 MAX(updatedAt) 检查和刷新唤醒已定稿**。


---

## 16. Context Retrieval / Bootstrap：MVP 接口与职责（2026-10-18 已确认）

### 16.1 统一入口、不同数据类型

**对 Agent 暴露统一的 Context API；不要求所有数据使用同一存储格式或标识符。**

- **Memory**：结构化知识与证据，来自 `context.db`；以 `O-MEM-...` / `R-MEM-...` 稳定 ID 访问，不为每条 Memory 分配 VFS URI。
- **Resources**：普通文档/附件及其他文件，由 VFS 提供精确路径 `alcyone://resources/...`；Memory 可以用自然语言记录某 Resource 的主题、用途和 URI，使 Agent 先搜索 Memory，再按 URI 访问文件。
- **Memory Map**：VFS 上的 `alcyone://memory/map.md`，通过 `bootstrap()` 交付，是派生导航，不需 Agent 直接以 VFS 读取它。
- **数据库备份**：`alcyone://memory/backups/...` 只是 Context System 生成的备份文件，不是 Agent 的语义检索对象。

Resources **不做独立的全文/模糊/Embedding 搜索**；VFS 自身的文件/目录精确操作不受此限制。Context Retrieval 不负责 Memory Organization 治理，也不负责 Agent 任务理解。

### 16.2 Agent 使用的接口

| 接口 | MVP 责任 | 实现/说明 |
| --- | --- | --- |
| `bootstrap()` | 读取 Memory Map（含生成水位与 `stale` 标记）、提供必要查询能力说明 | 不调用 LLM、不主动按任务检索；Recent View 暂不实现 |
| `searchMemory(query, domains?, limit?)` | 自然语言查找当前有效 Organized Memory | LLM Memory Scanner；可选 Domain 仅由 Agent 显式指定 |
| `getMemory(id)` | 按 Organized Memory ID 精确获取完整记忆 | SQLite Repository，连同来源 Raw ID 与 Domain 返回 |
| `getRawMemory(id)` | 按 Raw Memory ID 追溯历史证据 | SQLite Repository；面向需要证据追溯的 Agent，通常不用预载 |
| `loadMeta(uri)` | 获取 Resource 类型、大小、修改时间等元数据 | VFS；不解析文件，MIME 仅为提示 |
| `read(uri)` | 按 Resource URI 一次读取文件原始内容 | VFS；不自动理解 PDF/图片/视频 |
| `readStream(uri)` | 按 Resource URI 流式读取大文件 | VFS；具体流/分块协议开发时明确 |

- **取消对 Agent 的 `query()` API**：Domain/更新时间/分页等结构化条件查询仍可由内部 SQLite Repository 使用，也服务于 Map、Organization 和管理功能；不作为独立 Agent 入口。
- **Memory 返回真实数据**：`searchMemory` 中 LLM 只选择输入中存在的 Memory ID；Core 校验 ID、从权威 SQLite 获取 `id/content/domains/updatedAt/rawMemoryIds`；不允许模型直接编造返回内容、ID 或 Resource URI。
- **Read 仅面向 Resource 文件**：Agent 先由 Memory 得到 Resource URI，可选 `loadMeta(uri)` 判断是 Markdown、图片、PDF 等，再选择 `read` / `readStream` 交给自身解析工具；不强制先查询 Meta。
- **管理操作独立**：Resources 的 Create/Write/Move/Delete 等沿用已有 VFS 文件管理能力，不塞进只读 Retrieval；Context SDK/服务可在另一入口暴露这些能力。
- **权限 MVP**：由服务提供基本身份/可信 Agent 访问控制；暂不实现复杂的逐 Agent/Domain ACL。已有 VFS 文件访问控制保持自身行为，不能因上层接口而绕过。

### 16.3 LLM Memory Scanner（MVP）

Organization Candidate Retriever 与外部 Memory Search **共享底层的 Token Budget 分批、LLM 调用、ID 校验与候选合并能力**，但业务接口、Prompt 和相关性标准保持不同：Organization 偏重高召回和发现潜在冲突，Search 偏重用户查询相关性。**Scanner 不是新的事实存储层。**

```text
searchMemory(query, optional domains, limit)
  → 从 SQLite 读取有权访问的 Organized Memory（Domain 只做显式过滤）
  → 按 Token Budget 分批；MVP 默认串行执行
  → LLM 每批返回候选 Memory ID
  → Core 校验 ID 属于相应输入、合并去重
  → 候选多于 limit 时，必要时跨批统一重排序
  → Core 从 SQLite 取真实记忆并返回
```

- 默认建议 `limit=10`（可配置）；批次数按 Token 数而不是固定条数划分。候选数量过大无法一次重排序时，不静默截断并宣称完成。
- 某个必要扫描批次失败时，不能把不完整候选伪装成完整搜索结果；具体重试与错误类型实现阶段确定。
- **不额外做 Search 期间 `MAX(updatedAt)` 一致性检查、不因并发更新自动重试**；个人系统低更新频率可接受搜索期间偶发旧快照，查询具体当前状态时 Agent 可再调用 `getMemory(id)`。
- 初期不引入 FTS、Embedding、向量库或 Resource 语义索引；可替换 Scanner 实现，不改变 Agent 接口。

### 16.4 轻量 Bootstrap 与延后能力

MVP `bootstrap()` **只从 Memory Map 模块读取已有 Map（包括模块提供的 `stale` 状态），并构建轻量 Context/接口说明**；不根据任务主动推理或检索详细 Memory，不主动检查数据库水位、不发起 Map 生成/刷新、不启动其他异步任务，也不产生额外 LLM 消耗。Memory Map 模块自身负责初始化时的水位检查和 Organization 更新后的刷新（§15.5）；旧 Map 只能作为明确标记 `stale` 的导航参考。

**Recent Memory View** 保留为未来可选的 SQLite 逻辑查询视图，涵盖尚未治理或尚未被 Map 覆盖的近期变化；若启用，必须区分尚未治理的 Pending Raw 与已治理的 Organized Memory，不能将未经治理的信息或 Organized Memory 中尚待核实的内容伪装成确定事实。**MVP 暂不实现、不作为 Bootstrap 的依赖。**

### 16.5 尚待工程细化

具体 Kotlin 接口签名、Runtime 传输方式（HTTP/MCP/SDK）、异常码、二进制/流响应协议、Token Budget 数值、扫描大结果排序上限、基本授权机制和对外性能目标由实现阶段确定；不为此增加新存储或独立 Resource Retriever。


## 17. Dreaming：主题驱动增量反思（2026-10-19 MVP 已定稿）

### 17.1 定位、输入与职责边界

Dreaming 用于**主动回顾历史 Conversation / Raw Memory / 当前 Organized Memory**，发现普通 Extraction 遗漏的长期价值、跨会话规律和有证据支持的新认知；**不是**重新运行一遍 Extraction，也**不是**第二套 Organization。Dreaming 不直接修改 Organized Memory、Memory Map 或 Resource。

- **Extraction**：面向新的 Session Snapshot，及时提取明确的长期信息。
- **Dreaming**：围绕新 Conversation 中值得反思的 Topic，跨时间检索独立历史证据，归纳实质性新认知。
- **Organization**：对来自 Extraction / Dreaming 的 Raw Memory 进行统一治理；对证据冲突的当前事实也可以生成明确的未确认 Organized Memory。
- **Agent**：必要时在实际会话中向用户确认未决事实，后续进入普通 Conversation → Extraction → Organization 流程。

**取消 Deferred Review**：不再为冲突证据维护 Deferred 状态、周期复核队列或再入队逻辑；Dreaming 在正常反思中偶然发现澄清证据时，可以生成新的 Raw Memory，再交由 Organization 处理。

### 17.2 触发与串行执行（已确认）

- 支持**手动触发**与**可配置 Cron 表达式**两种入口，调用同一 Dreaming 执行逻辑；未配置 Cron 时只接受手动触发，**不强制默认每周执行**。
- Cron 表达式及其时区由配置提供，采用确定的调度实现；表达式语法和配置字段在实现阶段确定。配置的执行时间不代表 Conversation 的事实发生时间。
- MVP 只允许**一个 Dreaming Worker** 运行；手动和 Cron 同时触发时不新建并行任务。任务执行中的重复定时触发可以跳过本次，不建设持久化 Dreaming 任务队列。
- 失败或进程退出后通过 SQLite Checkpoint 确定下次从哪里继续；正常启动只需注册 Cron，无需专门的 StartupRecovery 或错过 Cron 的逐次补跑；数据库灾难恢复另见 §14。

### 17.3 Topic-Centric Incremental Dreaming：从新增 Conversation 发现主题

```text
Manual / Cron Trigger
    → 读取 Dreaming Checkpoint
    → 查询自上次成功处理水位以来新增的 Conversation Turn
    → 以 Token Budget 分批，LLM Topic Discovery
    → 选择有长期反思价值的临时 Topic
    → 针对每个 Topic 检索跨时间证据
    → 聚合 / 反思 / 产出候选新认知
    → 同库事务：Raw + Organization Pending + 本批 Checkpoint
    → 通知 Organization
```

- **Topic Discovery 采用增量 Conversation 范围**，不是每次全历史 Conversation 扫描；筛选值得反思的长期目标、反复出现的偏好、跨会话规律及遗漏，不对每条会话强制生成主题。**增量范围只以 Conversation 是否已持久化及 `id` 水位为依据，不检查 `extraction_status`；即使 Turn 仍是 pending/failed 也可参与 Dreaming。**
- Topic 是**一次 Dreaming 中的临时工作对象**（主题、说明、关键词、对应 Turn ID），不创建持久化 Topic、主题关系图或独立知识库。
- Topic 可以跨 Domain 和 Session；Domain 不是固定的 Agent 身份，也不应成为强制过滤条件。
- 可以返回空 Topic、空 Insight，这是正常的成功结果；不因定时触发而强制输出记忆。

### 17.4 Historical Evidence Retrieval：分层按需加载与证据聚合（已确认）

**原则：Organized Memory 是「系统当前认知」的基准（可被可靠新证据修正）；Raw Memory 用来发现「跨时间的历史证据」，其中可能包含已被纠正或过时的说法；Conversation 用来核实「用户当时究竟如何表达」。** 保留 Organized → Raw → Conversation 的分层检索，不把全部历史 Raw 当作当前事实，也不要求每个 Topic 无差别加载三层数据。

```text
Topic Discovery（本轮新增 Conversation，已包含可引用的原始 Turn）
    → Organized Memory 检索：现有认知是否已充分覆盖本轮新信息？
        ├─ 已充分覆盖、无实质增量 → 提前结束 Topic，不生成 Raw
        └─ 有新增信息／尚未充分覆盖 → Raw Memory 全历史候选检索
            → 通过 organized_memory_source 反查命中的 Raw 所关联的当前 Organized
            → 对照现行认知、用户明确纠正与时间顺序，汇总历史证据
            → 需要提出新结论或消除重要歧义时，按来源回读关键 Conversation
            → Reflection：产生有证据支持的 Insight，或返回空结果
            → Raw Memory（DREAMING）→ Organization
```

**第 1 层：Organized Memory（每个候选 Topic 先检索）。**

- 调用可复用 LLM Memory Scanner，从当前 Organized Memory 中选出与 Topic 相关的记录，加载这些记录的 `id/content/domains` 及必要来源引用；**不把全部 Organized Memory 作为 Reflection 输入**。
- 此层只负责对照已知认知、识别是否存在实质增量，**不作为独立原始证据**。若已有 Organized Memory 已充分覆盖 Topic，且本轮新增 Conversation 没有新增事实、适用范围或重要约束，则可以提前结束，省去 Raw 全历史扫描和后续 Reflection。
- **不能仅凭“搜到相关 Organized Memory”就提前结束**；必须对照本轮原始 Conversation 是否包含新内容。相关但不完整、未记录的新限定条件、可能值得重新考察的认知，继续下一层。

**第 2 层：Raw Memory（仅对值得深入的 Topic 检索）。**

- 为寻找长期规律、补充历史事实或核对不确定认知，围绕 Topic 使用 Scanner 在**全部历史 Raw Memory**中分批筛选相关 ID；「全历史检索」指所有 Raw 都有机会参与候选选择，**不代表把所有 Raw 加载到 Reflection**。优先复用当前相关 Organized Memory 的 `organized_memory_source` 取得已知 Raw 来源，但不可用它替代全历史候选检索，以免漏掉尚未关联的历史证据。
- Scanner 使用 Token Budget 分批、低成本 LLM 返回 ID、Core 校验 ID/合并去重；业务 Prompt 与 Organization Candidate Retriever、普通 Memory Search 不同。对**每条命中的 Raw ID**，Core 通过现有 `organized_memory_source` 反查所有关联的当前 Organized Memory，并加载其最新 `content` 参与对照，**即使它们未被第 1 层语义检索召回**；无需新表或第二次 LLM 搜索。无来源关联的 Raw 仍可进入证据分析，但不能据此推定它代表当前有效事实。
- 若历史证据不足以支持有价值的新结论，可以返回空结果、停止进一步回读；不为了定时任务必须产出而臆造长期规律。Raw 的历史覆盖并不保证能发现**从未被 Extraction 提取**的旧 Conversation，MVP 接受这一限制。

**第 3 层：Conversation（针对关键证据按需回读）。**

- **不默认回读每一条 Raw 的全部来源，更不默认重扫完整历史 Conversation**。在形成新的重要长期认知前，应回读足以支撑该结论的关键原始 Turn；发现来源冲突、时间先后不明、表达存在歧义、适用范围不清或需要判断证据独立性时，也应回读相关原文与必要的相邻 Turn。
- 重点核实：**适用范围**（某项目或普遍偏好）、**时序**（过去状态或当前决定）、**确定程度**（明确确认或只是倾向）、**独立性**（多个 Raw 是否实际来自同一 Turn）。不因 Raw 摘要省略了限制条件就放大结论。
- 通过 Raw `sources` 定位 Conversation；如来源指向其他 Raw，则继续追溯到底层原始证据，避免把 Dreaming 先前的总结当作独立证据。**本轮 Topic Discovery 已读取的新增 Conversation 本身就是原始证据**，可直接复用、无需再读一次。
- 对 Topic 的关键历史事实若完全没有 Raw 导航，未来可以按需补充历史 Conversation 检索；MVP 不默认启用全量 Conversation 语义扫描。

**第 4 层：证据聚合与 Reflection。**

- Core 对来源 Conversation Turn 去重、保留原文及事实相关时间语义，关注跨 Session／跨时间的独立证据；多条 Raw 指向同一 Turn 不能计算为多次确认。Reflection 输入须同时包含本 Topic 第 1 层召回的 Organized 与命中 Raw 经关联表反查得到的 Organized；它们是**当前认知对照**而非独立原始证据。旧 Raw 与这些当前认知冲突时，先识别是否已被用户纠正、是否只是历史阶段、是否存在新证据；不得用旧总结反复“证明”新总结。
- Reflection 只接收**当前 Topic 需要的**相关 Organized Memory、筛选后的 Raw、关键 Conversation 证据；来源不足、结论已有充分表达或无实质增量时允许空 `insights`。
- 长期稳定偏好通常应有多个独立场景支持，但不能将此机械化为每条 Insight 的固定来源数量；明确而重要的遗漏事实有时只需一条可信 Conversation。存在未解决的不确定性时必须写清楚「尚未确认」，不能宣称某个版本就是事实。
- 输入超过 Token Budget 时按预算组织证据，不能静默丢弃关键矛盾后仍把结论表述成完整可信；MVP 不引入置信度评分、独立证据库、向量数据库或新的持久化 Topic 状态。

### 17.5 Reflection 输出与统一治理

对每个 Topic，LLM 判断是否存在**相对于当前 Organized Memory 具有实质增量、且有可靠证据支持**的新认知；已充分表达的事实不重复创建，证据不足时宁可输出空结果。值得保留但仍存在不确定性的认知，须把未确认条件写清楚。

**Reflection Prompt 必须包含纠错保护硬约束**：历史 Raw 是当时的证据，不天然代表当前有效结论；对与当前 Organized 不一致的 Raw，必须检查用户是否明确纠正、事实适用时间/范围以及原始 Conversation。**不能仅凭重读或复述已被明确纠正的旧 Raw 生成相同的当前事实，也不能把多条同源 Raw 当作独立新证据。** 只有新增可靠原始证据（包括用户新的明确确认），或经原始证据核实的实质新发现，才能提出改变当前认知的 Insight；若仍无法判定，明确不确定性或返回空结果。Organized 是当前基准，但不是不能被证据纠正的永久真理。

**纠错示例**：若历史 Raw 记载“仓位 20%”，后续用户明确纠正为“当时实际 30%”，且当前 Organized 已保存 30%，Dreaming 重新检索到旧 Raw 不能再把 20% 输出为当前事实；如果之后用户明确确认重新调整为 20%，则属于新的有效证据，可以正常生成 Insight 并交由 Organization 判断。

LLM 输出逻辑 DTO 示意（具体 Prompt 与序列化规则在开发时确定）：

```json
{
  "insights": [
    {
      "content": "用户在个人软件项目中长期倾向采用简单、熟悉、易维护的技术方案。",
      "sourceTurnIds": [182, 435, 891, 1402]
    }
  ]
}
```

- LLM 只提交 `content` 与所引用的**本次允许引用的来源**；Core 校验来源存在、可追溯且内容相符，生成 `id / createdAt / generatedBy=DREAMING / sources`，保持 §9 已确认的五字段 Raw Memory 模型。若引用 Raw ID，须能追溯其已有证据；不能用重复模型输出构造虚假的独立来源。
- Dreaming 生成的 Raw Memory 与 `organization_pending` 在**一个 `context.db` 事务**中写入；成功提交后通知 Organization，**不修改**来源 Conversation 的 `extraction_status`，也不自行治理 Organized Memory。
- Deferred Review、独立 Governance、Topic 数据库、Embedding、专门的冲突修复任务均不进入 MVP。

### 17.6 Checkpoint、批次完成与失败边界

可在 `context.db` 维护一个简单的全局连续处理水位（逻辑示意）：

```sql
CREATE TABLE dreaming_checkpoint (
    id INTEGER PRIMARY KEY,
    last_processed_turn_id INTEGER NOT NULL
);
```

- 本轮先固定增量扫描上界，按 Turn ID 连续范围组织 Topic Discovery 和所有 Topic 的 Evidence Retrieval / Reflection；**只有覆盖当前批次的全部 Topic 并成功提交结果后才推进 Checkpoint**，不能在仅完成主题发现时提前推进。
- 本批新 Raw Memory、对应 Pending 与 Checkpoint 尽量在**同一 SQLite 事务**提交；事务失败则全部回滚、Checkpoint 不前进。空 Topic / 空 Insight 经完整处理也允许推进 Checkpoint。
- **Checkpoint 与 Extraction 状态完全解耦（2026-10-20 已确认）**：只要求本次按 SQLite `conversation_turn.id` 的连续扫描区间已全部完成 Topic Discovery、必要检索与 Reflection；**不要求 Turn 的 `extraction_status=complete`**，`pending/failed` 同样可作为原始 Conversation 参与 Dreaming。LLM 失败不能当作空结果；后续 Extraction 如提取出语义重复内容，由 Organization 正常治理。
- 失败不丢失待处理 Conversation；下次手动或 Cron 执行基于 Checkpoint 继续。补录旧会话按其**新入库的内部 Turn ID** 进入增量扫描，而不是用事件 `timestamp` 回退水位。已有 Conversation / Raw / Organized 使用现有数据库权威数据，不另建 Dreaming Job 队列。

### 17.7 MVP 不实现与后续评估

MVP 不实现永久 Topic 库、周期性历史全量重扫、Deferred 专项、向量检索或复杂调度。历史证据若从未进入 Raw Memory，可能无法经 Raw 语义导航找到；先接受这一局限，观察 Extraction 漏检、重复 Insight、Token 成本与新认知价值，再决定是否增强 Conversation 检索或补充人工指定主题反思。
