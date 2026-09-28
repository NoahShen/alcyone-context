已升级为 **v1.3**：Core 实现语言确定为 TypeScript/Node.js，并补充单实例内并发写入与递增 ID 的协调规则。以下为全文：
---

# Alcyone 技术架构设计

**版本**：v1.3　**状态**：设计稿（已对齐开发基线）　**范围**：架构、模块、数据模型、接口、核心流程
**基础**：数据模型与存储规范 v1.4、HTTP API 接口规范 v1.2、LLM 治理契约与 Prompt 规范 v1.3
命名：**Alcyone** 即一个 Agent 长期记忆系统；核心组件为 **Alcyone Core**，管理角色为 **Alcyone Manager**（Agent 侧）。

## 修订记录

| 版本 | 变更                                                                                                                                                                                                                                                                                                                                                                                    |
| ---- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| v1.0 | 初稿                                                                                                                                                                                                                                                                                                                                                                                    |
| v1.1 | 对齐开发基线：① LLM 调用移出 Core，治理层不再含 LLMGovernanceService，候选收集改由 Manager 侧完成；② SQLite 移除 `git_commit` / `content_hash` / `version`，补充 `last_reviewed_at`；③ 取消 SQLite 索引；④ 治理周期与降级天数改由 `config/governance.yaml` 配置，不再硬编码；⑤ 全部时间统一北京时间 ISO8601 带 `+08:00`，禁止 `Z` 后缀；⑥ frontmatter 与返回示例补充 `last_reviewed_at` |
| v1.2 | 系统正式更名为 Alcyone：组件命名对齐为 Alcyone Core 与 Alcyone Manager，跨文档引用更新为对应新版本号                                                                                                                                                                                                                                                                                    |
| v1.3 | Core 实现语言改为 TypeScript/Node.js（Fastify + TypeBox/Ajv + better-sqlite3）；单实例可并发接收请求，所有写入由 `WriteCoordinator` 串行化，以保证递增 ID 与 Git/文件一致性                                                                                                                                                                                                             |

---

## 1. 目标与原则

Alcyone 是一个跨 Agent、跨 Harness 的统一长期记忆系统。
核心原则：

> **Fat Memory Core, Thin Agents.**
> 三层职责：

- **Agent**：使用 Memory，发现值得保存的信息。
- **Alcyone Core**：统一存储、搜索、权限、写入治理、Review Queue。
- **Alcyone Manager**：Alcyone 记忆系统运维，以及需要高级语义判断的治理（LLM 调用发生在 Manager 侧）。
  Memory 与 Documents 分离：
- **Memory**：脱离具体项目后仍然值得长期记住的信息。
- **Documents**：知识、研究资料、项目过程和详细内容。
  核心设计约束：

1. Alcyone Core 是唯一写入口。
2. 只提供 HTTP 接口，绑定 `127.0.0.1`。
3. SQLite 只保留 `memory` 表，日期字段用 `TEXT`（ISO8601，北京时间 `+08:00`）。
4. Agent / Token 使用配置文件。
5. `pending_queue` 和 `review_queue` 是逻辑查询，不建表。
6. Conflict 不建表、不建模块，由 LLM 在治理时判断。
7. 写入即 `pending`，立即返回，关系判断交给 Manager。
8. LLM 治理判断由 Manager Agent 侧调用（LLM 由其 Harness 管理），Core 只校验与执行。
9. `update` 合并原 `supersede` 语义：旧 Memory 统一 `archived`。
10. Promotion 合并在治理流程内，不确定时保持 domain，`review_at = now + uncertain_retry_days`。
11. Review 采用批量模式：批量抓取 → 批量用户确认 → 批量 LLM 结构化 → 批量更新。
12. Git commit + push 即备份，不单独实现备份服务。
13. 治理周期、降级天数、批量大小由 `config/governance.yaml` 配置，Core 不硬编码。
14. 时间统一北京时间 ISO8601 带 `+08:00`，禁止使用 `Z`（UTC）后缀。
15. Core 只部署一个进程；可并发接收请求，但所有持久化写入在进程内由 FIFO `WriteCoordinator` 串行化。该互斥区覆盖 ID 分配、Markdown、Git、SQLite 和审计日志。

---

## 2. 总体架构

```text
Agent（业务读写）
Alcyone Manager Agent（Harness cron 调度；自带 LLM 与治理 Prompt）
        │
        │ HTTP 127.0.0.1
        ▼
Alcyone Core（TypeScript / Node.js；不含 LLM 依赖）
├── HTTP Gateway
├── Config & Auth（agents.yaml / governance.yaml）
├── Application Services
│   ├── SearchService      # 语义/关键词搜索
│   ├── QueryService       # 按条件查询记录
│   ├── RememberService    # 写入 pending
│   ├── GovernanceService  # 校验后执行治理结果
│   └── OpsService         # 健康检查、重建索引
├── WriteCoordinator        # 单实例内 FIFO 串行化全部写操作
└── Storage
    ├── Markdown + Git     # Source of Truth
    ├── SQLite             # 仅 memory 表
    └── Audit JSONL
LLM 调用发生在 Manager Agent 侧（Prompt 版本化，见治理契约），
Core 只对 Manager 提交的结构化结果做校验与执行。
```

Alcyone Core 是唯一写入口。Agent 和 Alcyone Manager 都不能直接修改 Memory 文件。
---

## 3. 模块设计

### 3.1 接口层

| 模块                                                         | 职责                                                            |
| ------------------------------------------------------------ | --------------------------------------------------------------- |
| HTTP Gateway（Fastify）                                      | 暴露 REST 接口，解析 token，执行 JSON Schema 参数校验，映射错误 |
| ConfigLoader                                                 | 读取 `agents.yaml` / `governance.yaml`                          |
| AuthService                                                  | token → agent identity → role / domain                          |
| 不实现 AgentRegistry 服务，Agent 和 Token 直接写在配置文件。 |

### 3.2 应用服务层

| 模块                                                                               | 职责                                           |
| ---------------------------------------------------------------------------------- | ---------------------------------------------- |
| SearchService                                                                      | 搜索 Memory，权限条件直接写入 SQL              |
| QueryService                                                                       | 按 ID 或条件查询 Memory                        |
| RememberService                                                                    | 统一写入入口，写入 `status = pending`          |
| GovernanceService                                                                  | 校验通过后执行治理决策、Promotion、Review 结果 |
| OpsService                                                                         | 健康检查、从 Markdown 重建 SQLite              |
| 不提供 `memory.update()`。所有新增、修改、取代都通过 `remember()` 和治理流程完成。 |

### 3.3 治理层

| 模块                                                                                                                           | 职责                                                                |
| ------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------- |
| GovernanceValidator                                                                                                            | 校验 Manager 提交的治理 / Review 结果，防止非法状态、越权、错误覆盖 |
| 说明：**LLM 调用不在 Core**。治理判断由 Manager Agent 按治理契约的 Prompt 调用其自带 LLM 完成；Core 收到提交后只做校验与执行。 |
| 治理逻辑：                                                                                                                     |

```text
Manager 调用 LLM（治理契约 Prompt）得到结构化决策
        ↓
Manager 提交 POST /v1/manager/govern | /v1/manager/review
        ↓
Core 校验（GovernanceValidator）
        ↓
Core 执行状态更新
```

### 3.4 存储层

| 模块                                                                                                                                                           | 职责                                                                               |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| MarkdownRepository                                                                                                                                             | 读写 Memory Markdown                                                               |
| GitRepository                                                                                                                                                  | commit、push、历史与审计                                                           |
| SqliteRepository                                                                                                                                               | 只维护 `memory` 表                                                                 |
| AuditLogger                                                                                                                                                    | 写 `audit.log.jsonl`                                                               |
| IdGenerator                                                                                                                                                    | 生成 `MEM-xxxxxx`                                                                  |
| WriteCoordinator                                                                                                                                               | 在单实例内以 FIFO 互斥锁串行化完整写入链路，防止 ID、文件、Git 和审计竞争          |
| Clock                                                                                                                                                          | 统一时间来源，`now()` 返回北京时间 ISO8601（`+08:00`）；禁止业务代码直接取系统时间 |
| RebuildService                                                                                                                                                 | 从 Markdown 重建 SQLite                                                            |
| 不建 FTS、不建索引、不建队列表、不建 conflicts 表、不建 agents / tokens 表。不支持多实例；单实例内可以并发接收请求，但写入必须通过 `WriteCoordinator` 串行化。 |

### 3.5 Manager 侧

| 模块                                                                | 职责                                                                                |
| ------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| ReviewWorker                                                        | 拉取 pending 和到期 Review                                                          |
| LLMJudge                                                            | 按治理契约 Prompt（govern / review）调用 LLM，完成 pending 关系判断与 Review 结构化 |
| UserConfirmationAdapter                                             | 批量向用户确认 Review                                                               |
| OpsRunner                                                           | 健康检查、重建索引                                                                  |
| Manager 通过 HTTP + manager token 调 Alcyone Core，不直接操作文件。 |

---

## 4. 数据模型

### 4.1 Memory 状态

```text
pending
active
deprecated
archived
```

| 状态         | 含义                                     |
| ------------ | ---------------------------------------- |
| `pending`    | 新写入，等待 Manager 治理                |
| `active`     | 已生效，普通 Agent 可搜索到              |
| `deprecated` | 已废弃，不再适合作为当前记忆             |
| `archived`   | 被新事实取代，保留历史，不再作为当前事实 |
| 逻辑队列：   |

```sql
-- pending queue
SELECT * FROM memory WHERE status = 'pending' ORDER BY created_at ASC;
-- review queue
SELECT * FROM memory
WHERE status = 'active'
  AND review_at IS NOT NULL
  AND review_at <= :today
ORDER BY review_at ASC;
```

### 4.2 SQLite：memory 表

```sql
CREATE TABLE memory (
    id               TEXT PRIMARY KEY,
    type             TEXT NOT NULL,      -- state / event / decision
    scope            TEXT NOT NULL,      -- shared / domain
    domain           TEXT,               -- shared 为 null
    status           TEXT NOT NULL,      -- pending / active / deprecated / archived
    content          TEXT NOT NULL,
    source_type      TEXT,
    source_ref       TEXT,
    created_at       TEXT NOT NULL,      -- 北京时间 ISO8601，+08:00
    updated_at       TEXT NOT NULL,      -- 北京时间 ISO8601，+08:00
    review_at        TEXT,               -- 北京时间 ISO8601 或 null
    last_reviewed_at TEXT,               -- 北京时间 ISO8601 或 null
    related_json     TEXT,               -- JSON 数组字符串
    file_path        TEXT NOT NULL
);
-- 不建索引：个人 Memory 数量不大，SQLite 全表扫描足够；
-- 数据量增长后，再根据实际查询情况添加索引。
-- 不建队列表、conflicts 表、agents 表，队列通过 status / review_at 逻辑查询。
```

设计说明：

- 只保留查询与展示需要的字段，**不存放 `git_commit`、`content_hash`、`version` 等非查询字段**（Git 历史由 Git 仓库本身承载；内容不原地修改，因此无版本字段）。
- 日期字段使用 `TEXT`，北京时间 ISO8601，例如 `2026-09-16T10:00:00+08:00`，**禁止 `Z` 后缀**。

### 4.3 Markdown frontmatter

```yaml
---
id: MEM-000231
type: state
scope: domain
domain: quant
status: pending
source:
  type: conversation
  ref: xxx
created_at: 2026-09-16T10:00:00+08:00
updated_at: 2026-09-16T10:00:00+08:00
review_at: null
last_reviewed_at: null
related: []
---
```

约束：

- `scope = shared` 时，`domain` 必须为 `null`。
- `type = event` 时，`review_at` 默认为 `null`。
- 时间字段统一北京时间 ISO8601 带 `+08:00`。
- 不引入 `version`：Memory 内容不直接修改，变化时新建一条 Memory，旧 Memory 通过 `status = archived` 表达。

---

## 5. HTTP 接口

绑定 `127.0.0.1`。认证使用：

```text
Authorization: Bearer <token>
```

### 5.1 Agent 接口

```text
POST /v1/memory/search
GET  /v1/memory/{id}
POST /v1/memory/remember
```

### 5.2 Manager 接口

```text
GET  /v1/manager/pending
GET  /v1/manager/due-reviews?limit=50
POST /v1/manager/govern      # 请求体为完整治理决策（decision / target_memory_ids / promotion / review_at / reason）
POST /v1/manager/review
```

### 5.3 运维接口

```text
GET  /health
POST /v1/ops/rebuild-index
```

---

## 6. 配置文件与权限

### 6.1 agents.yaml

```yaml
agents:
  - name: quant
    token: tok_quant_xxx
    role: agent
    domain: quant
  - name: knowledge
    token: tok_knowledge_xxx
    role: agent
    domain: knowledge
  - name: finance
    token: tok_finance_xxx
    role: agent
    domain: finance
manager:
  name: memory-manager
  token: tok_manager_xxx
  role: manager
```

### 6.2 权限规则

```text
Agent:
  shared       -> READ
  own domain   -> READ + WRITE
  other domain -> NO ACCESS
Manager:
  READ 所有 domain + shared
  WRITE status / scope / domain / review_at / related
  CONTENT 不直接修改
```

Agent 的 domain 由 token 决定，写入时直接使用，无需额外权限判断。

### 6.3 治理配置（governance.yaml）

治理周期与降级天数不在代码中硬编码，统一由配置提供：

```yaml
# config/governance.yaml
review:
  default_days:
    state: { shared: 180, domain: 365 }
    decision: { shared: 180, domain: 365 }
    event: null # event 默认不设置 review_at
  uncertain_retry_days: 30 # UNCERTAIN 后重新 Review 的天数
  llm_failure_retry_days: 30 # LLM 失败降级后重新 Review 的天数
  due_reviews_batch_size: 50
  pending_batch_size: 50
```

使用点：`decision = new` 的默认周期、`uncertain` 重试、Promotion uncertain 重试、Review UNCERTAIN 重试、LLM 超时 / 输出非法 / 校验失败降级、批量拉取条数。详见《LLM 治理契约与 Prompt 规范》§2。
---

## 7. 核心流程

### 7.1 搜索 Memory

#### 入口

```http
POST /v1/memory/search
Authorization: Bearer <token>
Content-Type: application/json
{ "query": "投资偏好", "limit": 20 }
```

#### 调用链

```text
HTTP Gateway
   ↓
AuthService.authenticate(token)          # token -> agent
   ↓
SearchService.search(agent, query, limit)
   ↓
SqliteRepository.search(agent, query, limit)
   ↓
返回结果
```

#### SQL 查询

```sql
SELECT * FROM memory
WHERE status = 'active'
  AND ( scope = 'shared' OR domain = :agent_domain )
  AND ( content LIKE '%' || :query || '%' OR :query IS NULL )
ORDER BY updated_at DESC
LIMIT :limit;
```

Manager 搜索时不做 domain 限制，直接查全量 active。

#### 返回

```json
{
  "items": [
    {
      "id": "MEM-000231",
      "type": "state",
      "scope": "domain",
      "domain": "quant",
      "status": "active",
      "content": "用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。",
      "created_at": "2026-09-16T10:00:00+08:00",
      "updated_at": "2026-09-16T10:00:00+08:00",
      "review_at": "2027-09-16T10:00:00+08:00",
      "last_reviewed_at": null,
      "related": []
    }
  ],
  "total": 1
}
```

---

### 7.2 获取单条 Memory

#### 入口

```http
GET /v1/memory/MEM-000231
Authorization: Bearer <agent_token>
```

#### 调用链

```text
HTTP Gateway
   ↓
AuthService.authenticate(token)
   ↓
QueryService.get(agent, memory_id)
   ↓
SqliteRepository.get(memory_id)
   ↓
权限检查（shared 或 own domain）
   ↓
返回结果
```

#### 权限规则

```text
Agent:
  memory.scope = shared      -> 可读
  memory.domain = agent.domain -> 可读
  其他                        -> 拒绝
Manager: 全部可读
```

#### 返回

```json
{
  "id": "MEM-000231",
  "type": "state",
  "scope": "domain",
  "domain": "quant",
  "status": "active",
  "content": "用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。",
  "source": { "type": "conversation", "ref": "xxx" },
  "created_at": "2026-09-16T10:00:00+08:00",
  "updated_at": "2026-09-16T10:00:00+08:00",
  "review_at": "2027-09-16T10:00:00+08:00",
  "last_reviewed_at": null,
  "related": []
}
```

---

### 7.3 写入 Memory（remember）

#### 入口

```http
POST /v1/memory/remember
Authorization: Bearer <token>
Content-Type: application/json
{
  "content": "用户偏好低维护成本的投资策略",
  "type": "state",
  "source": { "type": "conversation", "ref": "xxx" }
}
```

说明：

- `type` 可选，默认 `state`。
- `scope` 默认 `domain`。
- `domain` 由 Token 决定，Agent 不能声明。

#### 调用链

```text
HTTP Gateway
   ↓
AuthService.authenticate(token)
   ↓
WriteCoordinator.runExclusive(() => RememberService.remember(agent, content, type, source))
   ↓
IdGenerator.next_memory_id()
   ↓
构造 Memory 对象，status = pending，domain = agent.domain
   ↓
MarkdownRepository.write(memory)
   ↓
GitRepository.commit(file_path, message)
   ↓
SqliteRepository.insert(memory)
   ↓
AuditLogger.log(action="remember", memory_id)
   ↓
返回 { id, status: "pending" }
```

#### Memory 对象构造

```text
id: MEM-000231
type: state              # 默认 state
scope: domain            # 默认 domain
domain: quant            # 来自 token
status: pending
content: "..."
source_type: "conversation"
source_ref: "xxx"
created_at: now          # 北京时间 ISO8601，+08:00
updated_at: now
review_at: null
last_reviewed_at: null
related: []
file_path: memory/quant/MEM-000231.md
```

不包含 `git_commit` / `content_hash` / `version`：Git 信息由 Git 仓库承载；内容不原地修改，因此无版本字段。

#### 返回

```json
{
  "id": "MEM-000231",
  "status": "pending",
  "message": "Memory 已写入，等待治理。"
}
```

---

### 7.4 Manager 治理 pending Memory

治理流程涵盖：

- 关系判断（**Manager 侧 LLM** 完成）
- decision 执行（Core 完成）
- Promotion 处理（Domain → Shared，Core 在治理流程内执行）
  Promotion 不是独立入口，而是治理流程中的一步。

#### 入口

Manager 定期轮询：

```http
GET /v1/manager/pending
Authorization: Bearer <manager_token>
```

对每条 pending 完成治理判断后，提交完整决策：

```http
POST /v1/manager/govern
Authorization: Bearer <manager_token>
Content-Type: application/json
{
  "memory_id": "MEM-000231",
  "decision": "new",
  "target_memory_ids": [],
  "promotion": "no",
  "review_at": "2027-09-16T10:00:00+08:00",
  "reason": "新信息与已有记忆相关，但描述的是不同维度，因此新建。"
}
```

#### Manager 侧流程（LLM 判断）

```text
Harness cron 注入 pending_task Prompt
        ↓
GET /v1/manager/pending?limit={pending_batch_size}
        ↓
对每条 pending：
  GET /v1/memory/{id}                # 获取完整字段
  收集候选 active Memory             # 同 domain 或 shared，status = active
  构造 LLM 输入（治理契约 §3.2）
  调用 LLM（govern/system + govern/user Prompt）
  得到治理决策 JSON（decision / target_memory_ids / promotion / review_at / reason）
  本地预校验（失败则按降级结构提交）
        ↓
POST /v1/manager/govern 提交完整决策
```

候选筛选条件（Manager 通过 `POST /v1/memory/search` 获取 active Memory 后按此过滤，Manager 身份无 domain 限制）：

```sql
status = 'active'
  AND ( domain = :pending_domain OR scope = 'shared' )
ORDER BY updated_at DESC;
```

LLM 的 System / User Prompt 与输入输出 Schema 见《LLM 治理契约与 Prompt 规范》§3。LLM 输出即 govern 请求体结构；不包含 `confidence`，不允许修改 content。

#### Core 侧调用链

```text
POST /v1/manager/govern
   ↓
AuthService.authenticate(manager_token)
   ↓
GovernanceValidator.validate(request)
   ↓
WriteCoordinator.runExclusive(() => GovernanceService.apply_decision(request))
   ├── 执行 decision（new / duplicate / update / uncertain）
   └── 若 promotion = yes，执行 Promotion
   ↓
MarkdownRepository.update / move
   ↓
GitRepository.commit
   ↓
SqliteRepository.update
   ↓
AuditLogger.log
   ↓
返回治理结果
```

#### 校验

`GovernanceValidator.validate(request)` 检查：

- `decision` 在 `new / duplicate / update / uncertain` 内。
- `promotion` 在 `yes / no / uncertain` 内。
- `target_memory_ids` 为字符串数组；`decision = duplicate / update` 时非空。
- `target_memory_ids` 中每个 ID 存在、状态为 active、且在候选范围内。
- `review_at` 为合法 ISO8601 且带 `+08:00`，或 null。
- `reason` 非空。
- 不允许出现 `content`、`confidence` 等未定义字段。
- 不允许通过该接口修改 content。
  校验失败时：

```text
pending -> active
review_at = now + llm_failure_retry_days
reason = "llm_governance_failed"
```

其中 `llm_failure_retry_days` 来自 `config/governance.yaml`，默认 30 天。

#### decision 映射

| decision                                                       | Core 执行                                                          |
| -------------------------------------------------------------- | ------------------------------------------------------------------ |
| `new`                                                          | `status = active`；`review_at = now + default_days.{type}.{scope}` |
| `duplicate`                                                    | `status = archived`；`related` 指向目标 Memory                     |
| `update`                                                       | pending → active；旧 Memory → archived；双向 `related`             |
| `uncertain`                                                    | `status = active`；`review_at = now + uncertain_retry_days`        |
| 默认 review_at 周期（由 governance.yaml 配置，下表为默认值）： |
| type                                                           | scope                                                              | 默认 review_at |
| ---                                                            | ---                                                                | ---            |
| state                                                          | shared                                                             | now + 180d     |
| state                                                          | domain                                                             | now + 365d     |
| decision                                                       | shared                                                             | now + 180d     |
| decision                                                       | domain                                                             | now + 365d     |
| event                                                          | 任意                                                               | null           |

#### Promotion 处理

Promotion 在同一治理流程内执行：

| promotion                   | Core 执行                                             |
| --------------------------- | ----------------------------------------------------- |
| `yes`                       | `scope = shared`，`domain = null`                     |
| `no`                        | 保持 domain                                           |
| `uncertain`                 | 保持 domain，`review_at = now + uncertain_retry_days` |
| 调用链（promotion = yes）： |

```text
GovernanceService.apply_decision(pending_memory, request)
   ↓ 先执行 decision
   ↓ 若 promotion = yes：
GovernanceService.promote(memory)
   ↓
memory.scope = 'shared'，memory.domain = null
   ↓
MarkdownRepository.move(file_path, memory/shared/)
   ↓
GitRepository.commit
   ↓
SqliteRepository.update
   ↓
AuditLogger.log(action="promote")
```

规则：

- 同一个 Memory，ID 不变。
- 不复制内容。
- `scope = shared`，`domain = null`。
- 文件从 `memory/<domain>/` 移动到 `memory/shared/`。

#### 返回

```json
{
  "memory_id": "MEM-000231",
  "decision": "new",
  "status": "active",
  "promotion": "no",
  "review_at": "2027-09-16T10:00:00+08:00",
  "related": []
}
```

---

### 7.5 Review 到期 Memory（批量）

Review 采用**批量抓取 → 批量用户确认 → 批量 LLM 结构化（Manager 侧）→ 批量更新**的流程。

#### 入口

Manager 定期批量拉取：

```http
GET /v1/manager/due-reviews?limit=50
Authorization: Bearer <manager_token>
```

#### 整体调用链

```text
Manager 定时任务（Harness 注入 review_task Prompt）
   ↓
HTTP GET /v1/manager/due-reviews
   ↓
AuthService.authenticate(manager_token)
   ↓
QueryService.list_due_reviews(manager, today, limit)
   ↓
SqliteRepository.query_due_reviews(today, limit)
   ↓
返回到期 Memory 列表
   ↓
Manager 汇总成用户可读的问题列表
   ↓
用户逐条或整体确认
   ↓
Manager 将用户回答交给 LLM（review/system + review/user Prompt，见治理契约 §4）
   ↓
LLM 返回批量结构化 Review 结果
   ↓
GovernanceValidator.validate_batch(review_output)
   ↓
WriteCoordinator.runExclusive(() => GovernanceService.apply_review_batch(results))
   ↓
逐条 MarkdownRepository.update
   ↓
GitRepository.commit（可合并为一次提交）
   ↓
SqliteRepository.update（批量）
   ↓
AuditLogger.log
   ↓
返回批量结果
```

#### SQL

```sql
SELECT * FROM memory
WHERE status = 'active'
  AND review_at IS NOT NULL
  AND review_at <= :today
ORDER BY review_at ASC
LIMIT :limit;
```

`today` 为北京时间当天 `00:00:00+08:00`。

#### 用户确认阶段

Manager 把到期 Memory 整理为可读的问题，例如：

```text
以下 Memory 需要确认：
[MEM-000231] 用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。
这个偏好目前仍然成立吗？
[MEM-000487] 用户偏好低维护成本的投资策略。
这个偏好目前仍然成立吗？
```

用户回答示例：

```text
MEM-000231：仍然成立
MEM-000487：已经变了，现在更关注收益稳定性
```

#### LLM 批量结构化（Manager 侧）

输入：

```json
{
  "review_items": [
    {
      "memory_id": "MEM-000231",
      "content": "用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。",
      "user_response": "仍然成立"
    },
    {
      "memory_id": "MEM-000487",
      "content": "用户偏好低维护成本的投资策略。",
      "user_response": "已经变了，现在更关注收益稳定性"
    }
  ]
}
```

输出：

```json
{
  "results": [
    {
      "memory_id": "MEM-000231",
      "result": "VALID",
      "new_content": null,
      "next_review_at": "2027-09-16T10:00:00+08:00",
      "reason": "用户确认该偏好仍然成立。"
    },
    {
      "memory_id": "MEM-000487",
      "result": "CHANGED",
      "new_content": "用户当前更关注收益稳定性，而非低维护成本。",
      "next_review_at": "2027-09-16T10:00:00+08:00",
      "reason": "用户确认偏好已变化。"
    }
  ]
}
```

#### 批量校验

`GovernanceValidator.validate_batch(results)` 检查：

- `results` 是否覆盖全部输入 `memory_id`。
- `result` 是否在允许集合内。
- `CHANGED` 是否提供 `new_content`。
- 非 `CHANGED` 是否没有 `new_content`。
- `next_review_at` 是否合法（ISO8601 带 `+08:00`）或 null。
- 不允许出现 `confidence` 等未定义字段。
  任一条校验失败时：

```text
该条 result = UNCERTAIN
review_at = now + llm_failure_retry_days
reason = "llm_review_failed"
```

其余条目正常处理，整体仍返回 200。

#### Review 结果映射

| result       | Core 执行                                                                                                          |
| ------------ | ------------------------------------------------------------------------------------------------------------------ |
| `VALID`      | `status = active`；`review_at = next_review_at 或 default_days.{type}.{scope}`                                     |
| `CHANGED`    | 调用 `RememberService.remember(manager, new_content)` 写入新 pending；旧 Memory 保持 active，直到新 pending 被治理 |
| `DEPRECATED` | `status = deprecated`；`review_at = null`                                                                          |
| `UNCERTAIN`  | `status = active`；`review_at = now + uncertain_retry_days`                                                        |

#### CHANGED 批量处理

```text
GovernanceService.apply_review_batch(results)
   ↓
对 result = CHANGED 的条目：
RememberService.remember(
    agent = manager,
    content = new_content,
    type = 原 memory.type,
    source = { type: "review", ref: memory.id }
)
   ↓
新 Memory 以 pending 写入
   ↓
等待下一轮治理
```

#### 返回

```json
{
  "processed": 2,
  "results": [
    {
      "memory_id": "MEM-000231",
      "result": "VALID",
      "status": "active",
      "next_review_at": "2027-09-16T10:00:00+08:00"
    },
    {
      "memory_id": "MEM-000487",
      "result": "CHANGED",
      "status": "active",
      "new_memory_id": "MEM-000512"
    }
  ]
}
```

---

### 7.6 运维流程

#### 健康检查

```http
GET /health
```

调用：

```text
OpsService.health()
   ↓
检查 SQLite 可读
检查 Markdown 目录存在
检查 Git 仓库可用
   ↓
返回 { status: "ok" }
```

#### 从 Markdown 重建 SQLite

```http
POST /v1/ops/rebuild-index
Authorization: Bearer <manager_token>
```

调用链：

```text
WriteCoordinator.runExclusive(() => OpsService.rebuild_index(manager))
   ↓
（以下步骤均在写锁内）MarkdownRepository.scan_all()
   ↓
解析 frontmatter
   ↓
SqliteRepository.clear()
   ↓
SqliteRepository.bulk_insert(memories)
   ↓
AuditLogger.log(action="rebuild_index")
   ↓
返回 { rebuilt: N }
```

#### 备份

不单独实现备份接口。备份策略：

```text
每次写入 / 治理操作
   ↓
GitRepository.commit()
   ↓
GitRepository.push()      # 推送到远程
   ↓
远程 Git 仓库即为备份
```

Markdown 是 Source of Truth，SQLite 可随时从 Markdown 重建。
---

## 8. 内部方法总览

| 服务                                                                                                                         | 方法                                                             | 用途                                       |
| ---------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------ |
| AuthService                                                                                                                  | `authenticate(token)`                                            | token → agent                              |
| SearchService                                                                                                                | `search(agent, query, limit)`                                    | 搜索 active Memory，权限条件写入 SQL       |
| QueryService                                                                                                                 | `get(agent, memory_id)`                                          | 按 ID 查询并做权限判断                     |
| QueryService                                                                                                                 | `list_pending(manager)`                                          | 查询 pending                               |
| QueryService                                                                                                                 | `list_due_reviews(manager, before, limit)`                       | 批量查询到期 Review                        |
| RememberService                                                                                                              | `remember(agent, content, type, source)`                         | 写入 pending                               |
| GovernanceService                                                                                                            | `govern_pending(manager, request)`                               | 校验并执行治理决策                         |
| GovernanceService                                                                                                            | `apply_decision(memory, request)`                                | 执行 decision                              |
| GovernanceService                                                                                                            | `apply_review_batch(results)`                                    | 批量执行 Review 结果                       |
| GovernanceService                                                                                                            | `promote(memory)`                                                | Domain → Shared（治理流程内调用）          |
| GovernanceValidator                                                                                                          | `validate(request)`                                              | 校验单条治理请求                           |
| GovernanceValidator                                                                                                          | `validate_batch(results)`                                        | 校验批量 Review 结果                       |
| MarkdownRepository                                                                                                           | `write / update / move / scan_all`                               | Markdown 操作                              |
| GitRepository                                                                                                                | `commit / push / ensure_clean`                                   | Git 操作                                   |
| SqliteRepository                                                                                                             | `insert / update / get / search / query_* / clear / bulk_insert` | SQLite 操作                                |
| AuditLogger                                                                                                                  | `log(...)`                                                       | 审计日志                                   |
| IdGenerator                                                                                                                  | `next_memory_id()`                                               | 生成 MEM ID                                |
| WriteCoordinator                                                                                                             | `runExclusive(fn)`                                               | 单实例内 FIFO 串行化 ID 分配及完整写操作   |
| Clock                                                                                                                        | `now()`                                                          | 北京时间 ISO8601（`+08:00`），统一时间来源 |
| OpsService                                                                                                                   | `health / rebuild_index`                                         | 运维                                       |
| 说明：候选 active Memory 由 Manager 侧通过 `POST /v1/memory/search` 收集（见 §7.4），Core 不提供独立的 candidates 查询方法。 |

---

## 9. 核心原则

1. Alcyone Core 是唯一写入口。
2. 只提供 HTTP，绑定 `127.0.0.1`。
3. SQLite 只保留 `memory` 表，日期用 `TEXT`（北京时间 ISO8601 带 `+08:00`），不建索引。
4. Agent / Token 使用配置文件；治理周期与降级天数使用 `config/governance.yaml`。
5. `pending_queue` 和 `review_queue` 是逻辑查询，不建表。
6. Conflict 不建表、不建模块，由 LLM 在治理时判断。
7. 写入即 `pending`，立即返回，关系判断交给 Manager。
8. LLM 治理判断由 Manager Agent 侧完成（LLM 由其 Harness 管理），Core 只校验与执行。
9. LLM 输出不引入 `confidence`，不作为状态变更依据。
10. `decision` 只允许 `new | duplicate | update | uncertain`。
11. `update` 合并原 `supersede` 语义：旧 Memory 统一 `archived`。
12. Promotion 合并在治理流程内，不确定时保持 domain，`review_at = now + uncertain_retry_days`。
13. Review 采用批量模式：批量抓取 → 批量用户确认 → 批量 LLM 结构化 → 批量更新。
14. Review 结果只允许 `VALID | CHANGED | DEPRECATED | UNCERTAIN`。
15. `CHANGED` 通过 `remember` 写新 pending，不直接改旧 content。
16. Markdown + Git 是 Source of Truth，SQLite 可重建。
17. Git commit + push 即备份，不单独实现备份服务。
18. 所有治理操作写审计日志。
19. 时间统一北京时间 ISO8601 带 `+08:00`，禁止 `Z` 后缀；业务代码统一经 `Clock.now()` 获取时间。
20. LLM 输出禁止出现未定义字段（如 `content`、`confidence`）；校验失败统一降级为 `llm_failure_retry_days` 天后再 Review。
21. Core 为单实例；并发请求可以进入服务，但全部写操作必须在 `WriteCoordinator` 互斥区内从 ID 分配串行执行至审计。ID 严格递增且不可重复；因故障可出现间隙，不得复用。

---

## 契约冻结项对照（v1.3 对齐确认）

| 冻结项（契约 v1.3 §9）                                                   | 本文档落点                                             |
| ------------------------------------------------------------------------ | ------------------------------------------------------ |
| LLM 调用发生在 Manager Agent 侧，Core 不直接调用 LLM（第 17 条）         | §1 约束 8、§2 架构图、§3.3 治理层、§7.4 Manager 侧流程 |
| 降级天数、默认 Review 周期通过 `config/governance.yaml` 配置（第 11 条） | §1 约束 13、§6.3、§7.4 / §7.5 映射表                   |
| 时间统一北京时间 ISO8601 带 `+08:00`（存储规范 §6）                      | §1 约束 14、§4.2、§4.3、§7 各示例、§9 原则 19          |
| SQLite 不存放 Git / 哈希 / 版本字段，不建索引（存储规范 §5）             | §4.2                                                   |
| `last_reviewed_at` 纳入数据模型（存储规范 §4.2 / §5.1）                  | §4.2、§4.3、§7.1 / §7.2 返回示例                       |
