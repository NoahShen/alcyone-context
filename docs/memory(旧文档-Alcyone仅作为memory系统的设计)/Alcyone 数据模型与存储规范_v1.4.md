# Alcyone 数据模型与存储规范

版本：v1.4  
状态：设计稿  
范围：SQLite 表结构、Markdown 文件规范、目录结构、ID 规则、Git 提交规范、重建映射
命名：**Alcyone** 即一个 Agent 长期记忆系统；核心组件为 **Alcyone Core**，管理角色为 **Alcyone Manager**（Agent 侧）。

---

## 1. 存储总览

```text
alcyone/
├── config/
│   └── agents.yaml
├── memory/
│   ├── shared/
│   │   └── MEM-000231.md
│   ├── quant/
│   │   └── MEM-000232.md
│   ├── finance/
│   └── knowledge/
├── sequences/
│   └── memory.seq
├── memory.db
├── audit.log.jsonl
└── .git/
```

职责划分：

| 存储            | 角色            | 说明                                 |
| --------------- | --------------- | ------------------------------------ |
| Markdown        | Source of Truth | 每条 Memory 一个文件，权威数据       |
| Git             | 历史与审计      | 每次写入/治理提交，push 到远程即备份 |
| SQLite          | 查询层          | 只保留 `memory` 表，专注查询与搜索   |
| sequences       | ID 序列         | 独立文件，避免 ID 生成依赖数据库     |
| audit.log.jsonl | 审计日志        | 结构化记录治理操作，不建表           |

原则：

- Markdown 是权威，SQLite 是投影。
- SQLite 损坏或丢失时，可从 Markdown 完整重建。
- 所有写操作必须同时更新 Markdown、Git、SQLite。
- Agent 和 Manager 都不直接操作文件，只通过 Alcyone Core。
- Core 是单实例部署，但可以并发接收 HTTP 请求；所有写操作由进程内 `WriteCoordinator` 串行化，避免序列文件、Memory 文件、Git 和审计日志竞争。

---

## 2. 目录与文件命名

### 2.1 目录结构

```text
alcyone/
├── config/
│   └── agents.yaml
├── memory/
│   ├── shared/
│   ├── quant/
│   ├── finance/
│   └── knowledge/
├── sequences/
│   └── memory.seq
├── memory.db
├── audit.log.jsonl
└── .git/
```

- `memory/shared/`：所有 `scope = shared` 的 Memory。
- `memory/<domain>/`：所有 `scope = domain` 的 Memory。
- Domain 目录名与 `agents.yaml` 中的 `domain` 一致。
- 新 Domain 出现时，Core 自动创建对应目录。
- `sequences/memory.seq`：独立 ID 序列文件。

### 2.2 文件命名

```text
MEM-000231.md
```

规则：

- 文件名 = Memory ID + `.md`。
- ID 全局唯一，永不复用。
- 不使用 content、日期、domain 作为文件名。
- 文件路径由 Core 生成，外部请求不能指定路径。

### 2.3 文件路径字段

每条 Memory 的 `file_path` 记录相对路径：

```text
memory/quant/MEM-000231.md
memory/shared/MEM-000232.md
```

Promotion 后路径从 `memory/<domain>/` 变为 `memory/shared/`。

---

## 3. Memory ID 规范

### 3.1 格式

```text
MEM-000001
MEM-000002
MEM-000003
...
```

- 前缀固定 `MEM-`。
- 数字 6 位，左侧补零。
- 达到 `MEM-999999` 后扩展为 7 位，不重置。
- ID 永不复用，即使 Memory 被 `archived` 或 `deprecated`。

### 3.2 生成方式

由 Alcyone Core 的 `IdGenerator` 统一生成。

使用独立序列文件，不依赖数据库业务表：

```text
sequences/memory.seq
```

文件内容：

```text
000231
```

生成与写入协调流程：

```text
WriteCoordinator.runExclusive() 获取 FIFO 写锁
  ↓
读取 sequences/memory.seq
  ↓
解析为整数 +1，格式化为 MEM-000232
  ↓
临时文件写入新序列值，再原子 rename 到 sequences/memory.seq
  ↓
在同一写锁内完成 Markdown → Git commit → SQLite → Audit
  ↓
释放写锁
```

规则：

- 序列文件是 ID 的唯一权威来源。
- 不读取 `memory` 表生成 ID。
- SQLite 丢失或重建不影响 ID 连续性。
- 序列文件必须纳入 Git，push 后即备份。
- 单个 Core 进程可同时处理多个 HTTP 请求；所有**写操作**必须通过同一个 `WriteCoordinator` 的 FIFO 互斥区执行。锁覆盖 ID 分配至审计日志写入，不能只锁序列文件。
- 因此，在单实例内并发调用 `remember`、`govern`、`review` 或 `rebuild-index` 时，Memory ID 全局唯一且严格递增，Git 与 Markdown 写入不会交错。
- 若进程在序列文件原子更新后失败，允许出现未使用的 ID 间隙；ID 不回收、不复用，以优先保证不重复。
- 不支持多个 Core 进程或多个宿主机同时访问同一数据目录。该场景需要额外的跨进程锁或外部序列服务，超出本期范围。

### 3.3 其他 ID

| 类型      | 格式         | 用途                  |
| --------- | ------------ | --------------------- |
| Memory ID | `MEM-000001` | Memory 唯一标识       |
| 审计 ID   | `AUD-000001` | 审计日志条目 ID，可选 |

当前版本不引入 Conflict ID、Review ID，队列通过状态查询。

---

## 4. Markdown 文件规范

### 4.1 文件整体结构

```markdown
---
<frontmatter>
---

<content>
```

- 第一部分是 YAML frontmatter，用 `---` 包裹。
- 第二部分是 Memory 内容，纯文本或 Markdown。
- 文件末尾保留一个换行符。

### 4.2 Frontmatter 字段

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

字段说明：

| 字段               | 类型          | 必填 | 说明                                             |
| ------------------ | ------------- | ---- | ------------------------------------------------ |
| `id`               | string        | 是   | Memory ID                                        |
| `type`             | enum          | 是   | `state` / `event` / `decision`                   |
| `scope`            | enum          | 是   | `shared` / `domain`                              |
| `domain`           | string / null | 是   | `shared` 时为 `null`                             |
| `status`           | enum          | 是   | `pending` / `active` / `deprecated` / `archived` |
| `source`           | object        | 是   | 来源信息                                         |
| `source.type`      | string        | 是   | `conversation` / `review` / `import` 等          |
| `source.ref`       | string / null | 否   | 来源引用                                         |
| `created_at`       | string        | 是   | 北京时间，ISO8601 带时区                         |
| `updated_at`       | string        | 是   | 北京时间，ISO8601 带时区                         |
| `review_at`        | string / null | 是   | 下次 Review 时间，可为 null                      |
| `last_reviewed_at` | string / null | 否   | 上次 Review 时间                                 |
| `related`          | string[]      | 是   | 相关 Memory ID 列表                              |

字段约束：

- `scope = shared` 时，`domain` 必须为 `null`。
- `scope = domain` 时，`domain` 必填。
- `type = event` 时，`review_at` 默认为 `null`。
- `related` 不允许包含自身 ID。
- 时间字段统一使用北京时间，ISO8601 带 `+08:00` 偏移。

不引入 `version`：Memory 内容不直接修改，变化时新建一条 Memory，旧 Memory 通过 `status = archived` 表达。

### 4.3 Content 部分

```markdown
用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。
```

规则：

- content 只保存事实本身，不重复 frontmatter 中的元数据。
- 允许 Markdown 语法。
- 不允许嵌套 frontmatter。
- 内容变更必须经过 Alcyone Core，不直接编辑。
- 内容变化时新建 Memory，不原地修改。

### 4.4 完整示例

```markdown
---
id: MEM-000231
type: state
scope: domain
domain: quant
status: active
source:
  type: conversation
  ref: session-2026-09-16
created_at: 2026-09-16T10:00:00+08:00
updated_at: 2026-09-16T10:00:00+08:00
review_at: 2027-09-16T10:00:00+08:00
last_reviewed_at: null
related: []
---

用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。
```

### 4.5 Promotion 后的变化

Promotion 前后对比：

```yaml
# Promotion 前
scope: domain
domain: quant
```

```yaml
# Promotion 后
scope: shared
domain: null
```

同时：

- 文件从 `memory/quant/MEM-000231.md` 移动到 `memory/shared/MEM-000231.md`。
- `updated_at` 更新。
- Git 提交记录 `promote` 动作。
- ID、content、created_at 不变。

---

## 5. SQLite 数据模型

### 5.1 表结构

```sql
CREATE TABLE memory (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL,              -- state / event / decision
  scope TEXT NOT NULL,             -- shared / domain
  domain TEXT,                     -- shared 为 null
  status TEXT NOT NULL,            -- pending / active / deprecated / archived
  content TEXT NOT NULL,
  source_type TEXT,
  source_ref TEXT,
  created_at TEXT NOT NULL,        -- 北京时间 ISO8601
  updated_at TEXT NOT NULL,        -- 北京时间 ISO8601
  review_at TEXT,                  -- 北京时间 ISO8601 或 null
  last_reviewed_at TEXT,           -- 北京时间 ISO8601 或 null
  related_json TEXT,               -- JSON 数组字符串
  file_path TEXT NOT NULL
);
```

设计原则：

- SQLite 只承担查询与搜索职责。
- 只保留查询和展示需要的字段。
- 不存放 Git、哈希、版本等非查询字段。
- 表结构简单，可从 Markdown 随时重建。

### 5.2 字段说明

| 字段               | 类型 | 说明                                             |
| ------------------ | ---- | ------------------------------------------------ |
| `id`               | TEXT | 主键，`MEM-xxxxxx`                               |
| `type`             | TEXT | `state` / `event` / `decision`                   |
| `scope`            | TEXT | `shared` / `domain`                              |
| `domain`           | TEXT | `shared` 时为 NULL                               |
| `status`           | TEXT | `pending` / `active` / `deprecated` / `archived` |
| `content`          | TEXT | Memory 正文                                      |
| `source_type`      | TEXT | 来源类型                                         |
| `source_ref`       | TEXT | 来源引用                                         |
| `created_at`       | TEXT | 创建时间                                         |
| `updated_at`       | TEXT | 更新时间                                         |
| `review_at`        | TEXT | 下次 Review 时间                                 |
| `last_reviewed_at` | TEXT | 上次 Review 时间                                 |
| `related_json`     | TEXT | `related` 的 JSON 序列化                         |
| `file_path`        | TEXT | 相对路径                                         |

### 5.3 索引

不建索引。

个人 Memory 数量不大，SQLite 全表扫描足够。  
随着数据量增长，再根据实际查询情况添加索引。

### 5.4 逻辑队列查询

`pending_queue`：

```sql
SELECT *
FROM memory
WHERE status = 'pending'
ORDER BY created_at ASC;
```

`review_queue`：

```sql
SELECT *
FROM memory
WHERE status = 'active'
  AND review_at IS NOT NULL
  AND review_at <= :today
ORDER BY review_at ASC;
```

不建队列表。

### 5.5 搜索查询

Agent 搜索：

```sql
SELECT *
FROM memory
WHERE status = 'active'
  AND (
    scope = 'shared'
    OR domain = :agent_domain
  )
  AND (
    content LIKE '%' || :query || '%'
    OR :query IS NULL
  )
ORDER BY updated_at DESC
LIMIT :limit;
```

Manager 搜索：

```sql
SELECT *
FROM memory
WHERE status = 'active'
  AND (
    content LIKE '%' || :query || '%'
    OR :query IS NULL
  )
ORDER BY updated_at DESC
LIMIT :limit;
```

---

## 6. 日期与时间规范

### 6.1 时区选择

统一使用 **北京时间（UTC+8）**，格式为 ISO8601 带时区偏移：

```text
2026-09-16T10:00:00+08:00
```

理由：

- 个人系统，用户主要在北京时间使用，直接展示即可读懂。
- ISO8601 带 `+08:00` 偏移，无歧义。
- 跨时区使用时仍可正确转换为本地时间。
- 字符串排序与范围比较仍然正常。

### 6.2 格式约定

- 秒级精度，不需要毫秒。
- 统一带 `+08:00` 后缀。
- 允许 `null` 的字段：`review_at`、`last_reviewed_at`。
- 不使用 `Z`（UTC）后缀，避免与北京时间混淆。

### 6.3 时间来源

- Core 统一通过 `Clock.now()` 获取当前时间。
- `Clock` 返回北京时间 ISO8601 字符串，带 `+08:00`。
- 禁止业务代码直接调用系统时间，便于测试与审计。

### 6.4 展示建议

前端或 Agent 展示时，可以直接显示：

```text
2026-09-16 10:00
```

或保留完整格式：

```text
2026-09-16 10:00:00 +08:00
```

数据库和 Markdown 中始终保存完整格式。

### 6.5 SQLite 比较

字符串比较即可：

```sql
WHERE review_at <= '2026-09-16T00:00:00+08:00'
```

由于所有时间统一使用 `+08:00`，字符串排序等价于时间排序。

---

## 7. Git 提交规范

### 7.1 提交时机

每次写操作完成后提交一次：

| 操作          | 提交内容                    |
| ------------- | --------------------------- |
| remember      | 新增 Markdown 文件          |
| govern        | 修改 Markdown 文件          |
| review        | 修改 Markdown 文件          |
| promote       | 移动 + 修改 Markdown 文件   |
| rebuild-index | 不提交，仅更新 SQLite       |
| id-sequence   | 更新 `sequences/memory.seq` |

ID 序列文件与 Memory 文件可在同一次 commit 中提交。

所有会修改 Git 工作区的操作必须在 `WriteCoordinator` 的互斥区内执行；不允许两个请求并发执行 `git add`、`commit`、`push` 或文件移动。

### 7.2 Commit Message 格式

```text
<action>: <memory_id> [detail]
```

示例：

```text
remember: MEM-000231
govern: MEM-000231 decision=new
govern: MEM-000231 decision=update target=MEM-000100
review: MEM-000487 result=CHANGED
review: MEM-000487 result=VALID
promote: MEM-000231 domain=quant -> shared
rebuild-index
```

### 7.3 远程备份

- Git 配置 remote。
- 每次 commit 后 push。
- 远程仓库即为备份。
- 不需要额外备份接口。

### 7.4 Git 与 SQLite 的关系

- Git 记录 Markdown 历史。
- SQLite 只是查询层，不记录 Git 信息。
- 出现不一致时，以 Markdown 为准，重建 SQLite。

---

## 8. 审计日志规范

### 8.1 文件

```text
audit.log.jsonl
```

每行一个 JSON 对象。

### 8.2 字段

```json
{
  "id": "AUD-000123",
  "actor": "memory-manager",
  "actor_role": "manager",
  "action": "promote",
  "target": "MEM-000231",
  "before": { "scope": "domain", "domain": "quant" },
  "after": { "scope": "shared", "domain": null },
  "reason": "cross-agent long-term value",
  "timestamp": "2026-09-16T10:00:00+08:00",
  "git_commit": "abc123"
}
```

| 字段         | 说明                                                           |
| ------------ | -------------------------------------------------------------- |
| `id`         | 审计 ID                                                        |
| `actor`      | 操作者名称                                                     |
| `actor_role` | `agent` / `manager`                                            |
| `action`     | `remember` / `govern` / `review` / `promote` / `rebuild_index` |
| `target`     | Memory ID                                                      |
| `before`     | 操作前关键字段快照                                             |
| `after`      | 操作后关键字段快照                                             |
| `reason`     | 理由，来自 LLM 或 Manager                                      |
| `timestamp`  | 时间，北京时间                                                 |
| `git_commit` | 对应 Git commit                                                |

### 8.3 记录范围

- 所有写入（remember）。
- 所有治理（govern）。
- 所有 Review。
- 所有 Promotion。
- 索引重建。

搜索、读取不写审计。

---

## 9. 从 Markdown 重建 SQLite

### 9.1 触发场景

- SQLite 损坏或丢失。
- schema 变更。
- 手动触发 `POST /v1/ops/rebuild-index`。

### 9.2 流程

```text
OpsService.rebuild_index()
  ↓
MarkdownRepository.scan_all()
  ↓
对每个文件：
    解析 frontmatter
    读取 content
    构造 memory 行
  ↓
SqliteRepository.clear()
  ↓
SqliteRepository.bulk_insert(memories)
  ↓
AuditLogger.log(action="rebuild_index")
  ↓
返回 { rebuilt: N }
```

### 9.3 字段映射

| Markdown                     | SQLite           |
| ---------------------------- | ---------------- |
| frontmatter.id               | id               |
| frontmatter.type             | type             |
| frontmatter.scope            | scope            |
| frontmatter.domain           | domain           |
| frontmatter.status           | status           |
| content 正文                 | content          |
| frontmatter.source.type      | source_type      |
| frontmatter.source.ref       | source_ref       |
| frontmatter.created_at       | created_at       |
| frontmatter.updated_at       | updated_at       |
| frontmatter.review_at        | review_at        |
| frontmatter.last_reviewed_at | last_reviewed_at |
| frontmatter.related          | related_json     |
| 文件相对路径                 | file_path        |

### 9.4 一致性检查

重建时：

- 校验 `scope` 与 `domain` 是否匹配。
- 校验 `scope = shared` 的文件是否在 `memory/shared/`。
- 校验 `scope = domain` 的文件是否在对应 domain 目录。
- 校验 `related` 中的 ID 是否存在。
- 校验 ID 是否唯一。
- 校验文件名与 `id` 是否一致。
- 校验时间字段是否为合法 ISO8601 带 `+08:00`。

异常处理：

- 单条异常不中断整体重建。
- 异常条目写入重建报告，人工后续处理。

---

## 10. 数据生命周期

### 10.1 状态流转

```text
pending
  │
  ├── new       → active
  ├── duplicate → archived
  ├── update    → active（旧 Memory → archived）
  └── uncertain → active（review_at = now + 30d）

active
  │
  ├── review VALID       → active（更新 review_at）
  ├── review CHANGED     → 写入新 pending
  ├── review DEPRECATED  → deprecated
  └── review UNCERTAIN   → active（review_at = now + 30d）

deprecated
  │
  └── 长期不再需要 → archived

archived
  └── 保留，不删除
```

### 10.2 内容变化处理

Memory 内容不原地修改。

内容变化时：

```text
旧 Memory：status = archived
新 Memory：新建，status = pending → active
related：双向关联
```

这样：

- 每条 Memory 代表一个时间点的确定事实。
- 历史可追溯。
- 不需要 version 字段。

### 10.3 删除策略

- 不物理删除 Memory。
- 通过 `status = archived` 表达不再作为当前事实。
- Markdown 文件保留，Git 保留历史。
- 搜索默认只返回 `active`。

---

## 11. 数据一致性规则

1. Markdown 是权威，SQLite 可重建。
2. 每次写操作：先写 Markdown，再 Git commit，再更新 SQLite。
3. SQLite 更新失败时，标记为待重建，不阻塞主流程。
4. `updated_at` 每次状态变更时更新。
5. `related` 双向记录：新 Memory 和旧 Memory 互相引用。
6. ID 永不复用。
7. ID 由独立序列文件生成，不依赖数据库。
8. 内容不原地修改，变化时新建 Memory。
9. 审计日志与 Git 提交一一对应。
10. 时间统一使用北京时间 ISO8601 带 `+08:00`。
11. 单实例内的所有写操作由 `WriteCoordinator` 从 ID 分配到审计记录全程串行化；ID 可以有间隙，但不可重复、不可回退或复用。

---

## 12. 数据模型总览

```text
Memory
├── id: MEM-xxxxxx
├── type: state / event / decision
├── scope: shared / domain
├── domain: string / null
├── status: pending / active / deprecated / archived
├── content: string
├── source: { type, ref }
├── created_at: ISO8601 北京时间
├── updated_at: ISO8601 北京时间
├── review_at: ISO8601 北京时间 / null
├── last_reviewed_at: ISO8601 北京时间 / null
├── related: string[]
└── file_path: string
```
