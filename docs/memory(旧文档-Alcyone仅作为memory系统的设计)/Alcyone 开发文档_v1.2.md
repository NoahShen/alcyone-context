# Alcyone 开发文档

**版本**：v1.2　**状态**：开发基线　**输入**：技术架构 v1.3、数据模型与存储规范 v1.4、HTTP API 规范 v1.2、LLM 治理契约与 Prompt 规范 v1.3
命名：**Alcyone** 即一个 Agent 长期记忆系统；核心组件为 **Alcyone Core**，管理角色为 **Alcyone Manager**（Agent 侧）。

本版实现语言改为 TypeScript，并澄清并发语义：Core 仍为**单实例**，但可并发接收 HTTP 请求；所有写操作经进程内 `WriteCoordinator` 的 FIFO 互斥锁串行执行。该锁覆盖 ID 分配至审计落盘的完整写入链路，以防止 ID、Markdown 和 Git 写入竞争。
---

## 1. 设计基线与差异澄清

四份设计文档之间存在少量演进性差异，开发以下表中的**基线裁决**为准：

| #   | 议题                   | 架构历史版本                                 | 契约 / 存储规范                            | **开发基线**                                                             |
| --- | ---------------------- | -------------------------------------------- | ------------------------------------------ | ------------------------------------------------------------------------ |
| 1   | LLM 调用位置           | Core 治理层含 `LLMGovernanceService`         | LLM 由 Manager Agent 自带，Core 只校验执行 | **契约 v1.3**：Core 不实现任何 LLM 调用模块                              |
| 2   | SQLite 附加字段        | 含 `git_commit` / `content_hash` / `version` | 不存放 Git、哈希、版本字段                 | **存储 v1.4**：仅 13 个查询字段                                          |
| 3   | SQLite 索引            | 建 3 个索引                                  | 不建索引，全表扫描                         | **存储 v1.4**（个人规模足够）                                            |
| 4   | Review 周期 / 降级天数 | 硬编码 180/365/30d                           | `config/governance.yaml` 可配置            | **契约 v1.3**：全部走配置                                                |
| 5   | 时间格式示例           | 出现 `Z` 后缀                                | 统一 `+08:00` 北京时间                     | **存储 v1.4**：禁止 `Z` 后缀                                             |
| 6   | `last_reviewed_at`     | 未出现                                       | frontmatter / SQLite 均有                  | **存储 v1.4**：纳入实现                                                  |
| 7   | 并发写入与递增 ID      | 单实例单写者，未定义请求并发行为             | Memory ID 必须递增且不可重复               | **本版**：单实例内使用 `WriteCoordinator` 串行化全部写操作；不支持多实例 |

---

## 2. 系统概述

> **Fat Memory Core, Thin Agents** —— Alcyone，跨 Agent、跨 Harness 的统一长期记忆系统。
> **角色边界**：

```
Agent（读写业务方） ──┐
                      ├── HTTP 127.0.0.1 ──> Alcyone Core（本项目开发主体）
Manager Agent ────────┘        ▲
        │                      │
        └── LLM 判断 + 用户确认（LLM 由 Harness 提供，Core 不感知）
```

**继承的契约冻结项**（开发中不可违反）：Core 唯一写入口；写入即 pending 立即返回；LLM 输出无 `confidence`、不可改 content；decision/result/promotion 枚举冻结；Markdown+Git 为 Source of Truth；SQLite 可随时重建；所有写操作记审计。
**明确不实现清单**（防范围蔓延）：

| 不实现项                                      | 原因                                                                           |
| --------------------------------------------- | ------------------------------------------------------------------------------ |
| cron 调度 / Prompt 注入                       | Harness（Hermes）负责                                                          |
| LLM 客户端与配置                              | Manager Agent 侧自带                                                           |
| 备份服务                                      | Git commit + push 即备份                                                       |
| FTS 全文索引、队列表、conflicts 表、agents 表 | 逻辑查询 / 配置文件替代                                                        |
| `memory.update()` / `conflict.resolve()`      | 变化通过 remember 新建，冲突由治理判断                                         |
| 多实例 / 跨进程写入                           | 本期只支持一个 Core 进程；进程内并发请求由 `WriteCoordinator` 串行化全部写操作 |

---

## 3. 技术选型

### 3.1 选型总表

| 维度          | 选型                                       | 版本                   | 理由                                                                                                                 |
| ------------- | ------------------------------------------ | ---------------------- | -------------------------------------------------------------------------------------------------------------------- |
| 语言 / 运行时 | **TypeScript** + **Node.js**               | TS 5.9+ / Node 24 LTS+ | `strict` 编译期类型检查；Node 生态适合后续 AI 集成与性能优化                                                         |
| Web 框架      | **Fastify**                                | 5.x                    | JSON Schema 路由校验、响应序列化和 OpenAPI 生态；单进程绑定 `127.0.0.1`                                              |
| 数据校验      | **TypeBox** + Fastify/Ajv                  | latest                 | 同一份 JSON Schema 生成静态类型与运行时校验；`additionalProperties: false` 拒绝 `content`、`confidence` 等未定义字段 |
| SQLite        | **better-sqlite3**（不用 ORM）             | latest                 | 同步预编译 SQL 适合单实例串行写入；与规范 SQL 一一对应                                                               |
| YAML          | **yaml**                                   | latest                 | agents.yaml / governance.yaml                                                                                        |
| Frontmatter   | **gray-matter**                            | latest                 | 解析 `---` 包裹的 YAML，减少手写边界问题                                                                             |
| Git 操作      | **node:child_process** 封装 GitRunner      | 内置                   | 只需 add/commit/push/rev-parse；显式参数调用，错误信息清晰                                                           |
| 时区          | **Intl（Asia/Shanghai）** + Clock          | 内置                   | `Clock.now()` 统一输出 `+08:00` ISO8601，禁止业务代码取系统时间                                                      |
| 并发写入      | **WriteCoordinator**（FIFO Promise mutex） | 项目内实现             | 单实例内串行化 ID 分配、文件、Git、SQLite 和审计写入；避免 ID 与 Git 竞争                                            |
| 测试          | **Vitest** + `@vitest/coverage-v8`         | latest                 | 单元 + 集成                                                                                                          |
| 质量          | **tsc --noEmit** + ESLint + Prettier       | latest                 | 严格类型检查、lint 与格式化；运行时边界仍由 JSON Schema 校验                                                         |
| 运行形态      | 单进程 Node.js，绑定 `127.0.0.1`           | —                      | 与架构约束一致；可并发接收请求，写路径保持串行                                                                       |

### 3.2 备选方案说明

| 备选                       | 未选理由                                                                                      |
| -------------------------- | --------------------------------------------------------------------------------------------- |
| Python（FastAPI/Pydantic） | TypeScript 的 `strict` 类型检查与 TypeBox/Ajv 的运行时 JSON Schema 已覆盖本项目的契约校验需求 |
| Prisma / Drizzle 等 ORM    | 单表、无迁移需求，ORM 反而引入复杂度；保留显式 SQL Repository                                 |
| node-cron（Core 内置定时） | 架构明确调度归 Harness，Core 不感知 cron                                                      |
| simple-git 等 Git SDK      | Git 操作面极窄，`node:child_process` 显式封装更可控                                           |

---

## 4. 工程结构

```
memory-core/
├── package.json
├── package-lock.json
├── tsconfig.json
├── eslint.config.js
├── prettier.config.js
├── README.md
├── config/
│   ├── agents.yaml                 # Agent/Manager 与 token（架构 §6.1）
│   └── governance.yaml             # 治理周期配置（契约 §2）
├── src/
│   ├── main.ts                     # Fastify 入口
│   ├── clock.ts                    # Clock.now() → +08:00 ISO8601
│   ├── config/
│   │   ├── loader.ts               # 加载 agents.yaml / governance.yaml
│   │   └── models.ts               # GovernanceConfig / AgentConfig
│   ├── auth/
│   │   └── service.ts              # token → AgentContext{agent, role, domain}
│   ├── api/
│   │   ├── deps.ts                 # Bearer 依赖、requireAgent/requireManager
│   │   ├── errors.ts               # 统一错误码与响应包装 {ok, data|error}
│   │   ├── agent-routes.ts         # search / get / remember
│   │   ├── manager-routes.ts       # pending / govern / due-reviews / review
│   │   └── ops-routes.ts           # health / rebuild-index
│   ├── schemas/
│   │   ├── memory.ts               # MemoryOut / RememberIn / SearchIn TypeBox Schema
│   │   ├── governance.ts           # GovernIn / LLMGovernanceOutput Schema
│   │   └── review.ts               # ReviewItem / ReviewResult / ReviewBatchIn Schema
│   ├── services/
│   │   ├── search-service.ts
│   │   ├── query-service.ts        # get / listPending / listDueReviews
│   │   ├── remember-service.ts
│   │   ├── governance-service.ts   # applyDecision / applyReviewBatch / promote
│   │   └── ops-service.ts          # health / rebuildIndex
│   ├── governance/
│   │   └── validator.ts            # GovernanceValidator.validate / validateBatch
│   └── storage/
│       ├── markdown-repo.ts        # write / update / move / scanAll
│       ├── git-repo.ts             # commit / push / revParse
│       ├── sqlite-repo.ts          # insert / update / get / search / query* / clear / bulkInsert
│       ├── audit-logger.ts         # audit.log.jsonl（AUD-xxxxxx）
│       ├── id-generator.ts         # sequences/memory.seq
│       └── write-coordinator.ts    # runExclusive：完整写入链路的 FIFO 互斥锁
├── tests/
│   ├── unit/                       # validator / id / clock / repos
│   └── integration/                # 端到端场景（对应 API 规范场景一~九）
└── data/                           # 运行时 alcyone/（见 §11 部署）
memory-manager/                     # Manager 侧独立交付物
├── prompts/
│   ├── govern/system.v1.txt        # 契约 §3.6
│   ├── govern/user.v1.txt          # 契约 §3.7
│   ├── review/system.v1.txt        # 契约 §4.5
│   ├── review/user.v1.txt          # 契约 §4.6
│   └── tasks/
│       ├── pending_task.v1.txt     # 契约 §6.2
│       └── review_task.v1.txt      # 契约 §6.3
└── hermes.yaml                     # Harness cron 示例（契约 §6.4）
```

---

## 5. 模块划分与职责

### 5.1 分层职责表

| 层     | 模块                                      | 核心方法                                                         | 对应设计                     |
| ------ | ----------------------------------------- | ---------------------------------------------------------------- | ---------------------------- |
| 接口层 | HTTP Gateway（Fastify）                   | 路由、token 解析、JSON Schema 参数校验、错误映射                 | API 规范 §2、架构 §3.1       |
| 接口层 | AuthService                               | `authenticate(token) → AgentContext`                             | 架构 §3.1，agents.yaml       |
| 应用层 | SearchService                             | `search(agent, query, limit)`                                    | API 规范 §3                  |
| 应用层 | QueryService                              | `get / list_pending / list_due_reviews / get_candidates`         | API 规范 §4/6/8              |
| 应用层 | RememberService                           | `remember(agent, content, type, source)`                         | API 规范 §5                  |
| 应用层 | GovernanceService                         | `govern_pending / apply_decision / apply_review_batch / promote` | API 规范 §7/9，架构 §7.4/7.5 |
| 应用层 | OpsService                                | `health / rebuild_index`                                         | API 规范 §10/11              |
| 治理层 | **GovernanceValidator**（唯一治理层模块） | `validate(output)` / `validate_batch(results)`                   | 契约 §3.9/§4.8               |
| 存储层 | MarkdownRepository                        | write/update/move/scan_all                                       | 存储 §4                      |
| 存储层 | GitRepository                             | commit/push/rev-parse                                            | 存储 §7                      |
| 存储层 | SqliteRepository                          | insert/update/get/search/query_*/clear/bulk_insert               | 存储 §5                      |
| 存储层 | AuditLogger                               | log（remember/govern/review/promote/rebuild_index）              | 存储 §8                      |
| 存储层 | IdGenerator                               | `next_memory_id()`（读 seq → +1 → 写回）                         | 存储 §3.2                    |
| 存储层 | WriteCoordinator                          | `runExclusive()`（串行化完整写操作）                             | 存储 §3.2、§11               |
| 存储层 | Clock                                     | `now()`（唯一时间来源）                                          | 存储 §6.3                    |

### 5.2 治理层说明（与架构历史版本的差异落地）

- **不实现** `LLMGovernanceService.judge_pending / judge_review_batch` —— LLM 调用发生在 Manager Agent 侧（契约冻结项 17）。
- Core 侧治理层只保留 **GovernanceValidator**，职责：枚举合法性、`target_memory_ids` 存在性与候选匹配、`duplicate/update` 非空 target、`CHANGED ↔ new_content` 绑定、`review_at` 格式（ISO8601 + `+08:00`）、TypeBox Schema 的 `additionalProperties: false` 拒绝未定义字段。
- 校验失败的降级动作在 `GovernanceService` 中执行（见 §8.3）。

---

## 6. 数据模型实现要点

### 6.1 SQLite DDL（按存储规范 v1.4，无索引、无 Git 字段）

```sql
CREATE TABLE memory (
    id               TEXT PRIMARY KEY,        -- MEM-xxxxxx
    type             TEXT NOT NULL,           -- state / event / decision
    scope            TEXT NOT NULL,           -- shared / domain
    domain           TEXT,                    -- shared 时 NULL
    status           TEXT NOT NULL,           -- pending / active / deprecated / archived
    content          TEXT NOT NULL,
    source_type      TEXT,
    source_ref       TEXT,
    created_at       TEXT NOT NULL,           -- 2026-09-16T10:00:00+08:00
    updated_at       TEXT NOT NULL,
    review_at        TEXT,
    last_reviewed_at TEXT,
    related_json     TEXT,                    -- JSON 数组字符串
    file_path        TEXT NOT NULL
);
-- 不建索引。pending/review 队列为逻辑查询，不建表。
```

### 6.2 关键实现规则

| 规则            | 实现要点                                                                                                                                                                               |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 写入顺序        | **Markdown → Git commit → SQLite → AuditLogger**；SQLite 失败仅标记待重建，不阻塞                                                                                                      |
| 审计与 Git 对应 | 先 commit 拿到 hash，再写审计 `git_commit` 字段，一一对应（存储 §11.9）                                                                                                                |
| ID 生成         | 读 `sequences/memory.seq` → +1 → `MEM-%06d` → 写回；序列文件随本次 commit 一并提交；重建 SQLite 不影响 ID                                                                              |
| 并发写入        | 每个写操作从 ID 分配至审计写入均放在 `WriteCoordinator.runExclusive()` 中；同一进程按 FIFO 串行执行。序列文件采用临时文件写入后原子 rename；中途失败允许留下 ID 间隙，但绝不复用或重复 |
| Promotion       | 同一 ID，`scope=shared, domain=null`，文件 `move` 到 `memory/shared/`，`updated_at` 更新，commit message `promote: MEM-xxxxxx domain=quant -> shared`                                  |
| 内容变更        | 永不原地修改 content；变化走 `remember()` 新 pending + 旧条 archived + 双向 related                                                                                                    |
| 时间            | 全部经 `Clock.now()`；SQL 范围比较直接用字符串（`+08:00` 统一后字符串序 = 时间序）                                                                                                     |
| 配置            | `governance.yaml` 中 `default_days / uncertain_retry_days / llm_failure_retry_days / *_batch_size` 全部热读配置，Core 内不写死天数                                                     |

---

## 7. 接口实现清单

| #                                                                                                                                                          | 接口                          | 模块           | 认证          | 审计 | 关键校验 / 降级                                                            |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------- | -------------- | ------------- | ---- | -------------------------------------------------------------------------- |
| 1                                                                                                                                                          | `POST /v1/memory/search`      | agent_routes   | agent/manager | 否   | limit 1–100；权限条件写进 SQL WHERE                                        |
| 2                                                                                                                                                          | `GET /v1/memory/{id}`         | agent_routes   | agent/manager | 否   | shared 或 own domain，否则 403                                             |
| 3                                                                                                                                                          | `POST /v1/memory/remember`    | agent_routes   | agent         | 是   | content 非空、type 枚举；domain 取自 token；返回 `{id, status: "pending"}` |
| 4                                                                                                                                                          | `GET /v1/manager/pending`     | manager_routes | manager       | 否   | limit 默认 50 最大 200                                                     |
| 5                                                                                                                                                          | `POST /v1/manager/govern`     | manager_routes | manager       | 是   | `GovernanceValidator.validate`；失败降级（§8.3）                           |
| 6                                                                                                                                                          | `GET /v1/manager/due-reviews` | manager_routes | manager       | 否   | `review_at <= today(北京时间 00:00)`                                       |
| 7                                                                                                                                                          | `POST /v1/manager/review`     | manager_routes | manager       | 是   | `validate_batch`；单条失败降级为 UNCERTAIN，整体仍 200                     |
| 8                                                                                                                                                          | `GET /health`                 | ops_routes     | 无            | 否   | 检查 SQLite / Markdown 目录 / Git                                          |
| 9                                                                                                                                                          | `POST /v1/ops/rebuild-index`  | ops_routes     | manager       | 是   | 单条异常不中断，写入 warnings                                              |
| **错误码**：`UNAUTHORIZED(401) / PERMISSION_DENIED(403) / NOT_FOUND(404) / INVALID_ARGUMENT(400) / LLM_FAILED(502) / INTERNAL_ERROR(500)`，统一 `{ok, data | error}` 包装。                |

---

## 8. 核心流程实现要点

### 8.1 remember（API 规范 §5）

```
authenticate
→ WriteCoordinator.runExclusive(() =>
    IdGenerator.next → 构造 Memory(status=pending, domain=token.domain)
    → MarkdownRepo.write → GitRepo.commit("remember: MEM-xxxxxx")
    → SqliteRepo.insert → AuditLogger.log
  )
→ 返回
```

### 8.2 govern（API 规范 §7，LLM 已由 Manager 完成）

```
authenticate → Validator.validate(request)
├─ 通过 → WriteCoordinator.runExclusive(() => GovernanceService.apply_decision)
│         new       → active, review_at = now + default_days[type][scope]
│         duplicate → archived, related → 目标
│         update    → pending→active；旧→archived；双向 related
│         uncertain → active, review_at = now + uncertain_retry_days
│         promotion=yes（先 decision 后 promote）→ scope=shared, domain=null, move 文件
│       → Markdown → Git → SQLite → Audit
└─ 失败 → 降级：pending→active，review_at = now + llm_failure_retry_days，
          reason = llm_governance_failed，仍返回 200 并在 data 中标注
```

### 8.3 review 批量（API 规范 §9）

```
authenticate → validate_batch
  覆盖性 / result 枚举 / CHANGED 必有 new_content / 非 CHANGED 必无
→ WriteCoordinator.runExclusive(() =>
  VALID       → active, review_at = next_review_at 或 default_days
  CHANGED     → RememberService.remember(manager, new_content, type 原值,
                source={type:"review", ref: 原 id}) → 新 pending，旧条保持 active
  DEPRECATED  → deprecated, review_at = null
  UNCERTAIN   → active, review_at = now + uncertain_retry_days
  )
单条校验失败：该条置 UNCERTAIN，review_at = now + llm_failure_retry_days，
reason = llm_review_failed，其余正常，整体 200
```

### 8.4 rebuild-index（存储 §9）

`scan_all` → 解析 frontmatter → 一致性检查（scope/domain 匹配、文件位置、related 存在性、ID 唯一、时间格式）→ 单条异常进 warnings 不中断 → `clear + bulk_insert` → 审计。
---

## 9. Manager Agent 侧开发内容

| 交付物                                                                                                                                          | 说明                                                                                                                     |
| ----------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| 6 个 Prompt 文件                                                                                                                                | 内容按契约 §3.6/§3.7/§4.5/§4.6/§6.2/§6.3 原文落盘，头部记录 `# Prompt / # Version / # Date / # Description`（契约 §5.2） |
| hermes.yaml                                                                                                                                     | 两个 cron job：`memory-govern-pending`（*/15）、`memory-review-due`（0 10 * * *），注入 prompt、manager_token、base_url  |
| 联调核对单                                                                                                                                      | Agent 调用 Core 的 4 个 Manager 接口的请求/响应样例（契约 §8）                                                           |
| **边界提醒**：Manager 侧不直接操作 Markdown/Git 文件，仅走 HTTP + manager_token；LLM 模型信息与 Agent 执行日志由 Harness 记录，不进 Core 审计。 |

---

## 10. 开发计划

### 10.1 里程碑总览（单人全职，约 6.5 周；两人可并行 M2/M4，压缩至 5 周）

| 里程碑                        | 内容                                                                      | 交付物与验收标准（DoD）                                                                                       | 工期   | 依赖  |
| ----------------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | ------ | ----- |
| **M0** 工程初始化             | npm 骨架、TypeScript strict、ESLint/Prettier/Vitest CI、空服务            | `npm run dev` 启动，`GET /health` 返回 ok；`tsc --noEmit` 与 CI 绿                                            | 0.5 周 | —     |
| **M1** 存储层                 | Clock、IdGenerator、WriteCoordinator、Markdown/Git/SQLite/Audit 五个 Repo | 单元测试覆盖：并发 ID 唯一且递增、ID 序列持久与 Git 提交、frontmatter 往返、commit message 规范、审计字段完整 | 1 周   | M0    |
| **M2** 配置与 Agent 接口      | agents.yaml/governance.yaml 加载、AuthService、search/get/remember        | API 规范场景一~三联调通过；跨 domain 403、shared 可读；写入即 pending                                         | 1 周   | M1    |
| **M3** Manager 接口与治理校验 | pending/govern/due-reviews/review + GovernanceValidator + 降级链路        | API 规范场景四~七联调通过；全部降级路径有测试（§12 清单）；promotion 文件移动正确                             | 1.5 周 | M2    |
| **M4** 运维能力               | health 完善、rebuild-index                                                | Markdown→SQLite 重建映射测试全绿；异常条目进 warnings                                                         | 0.5 周 | M1    |
| **M5** Manager 侧             | 6 个 Prompt、hermes.yaml、端到端 cron 演练                                | 契约 §8.1/§8.2 两条完整链路（含用户确认、LLM 结构化、批量提交）演练通过                                       | 1 周   | M3    |
| **M6** 集成与发布             | E2E 回归、README/部署手册、打包                                           | 全部 API 规范场景回归通过；打 v1.0 tag                                                                        | 1 周   | M1–M5 |

### 10.2 任务分解（M3 细化示例，其余里程碑同法）

1. `governance.yaml` 加载与默认值（0.5d）
2. `GET /v1/manager/pending`、`GET /v1/manager/due-reviews`（0.5d）
3. `GovernanceValidator.validate`（TypeBox/Ajv Schema + 存在性校验）（1d）
4. `GovernanceService.apply_decision`：四种 decision + promotion（含 move）（1.5d）
5. `validate_batch` + `apply_review_batch`（含 CHANGED → remember 回调）（1.5d）
6. 降级路径（govern 失败 / review 单条失败）+ 审计 `prompt_version` 扩展字段（1d）
7. 接口集成测试（1d）

---

## 11. 部署与运维

**运行时数据目录初始化**：

```
alcyone/
├── config/agents.yaml
├── memory/{shared,quant,finance,knowledge}/
├── sequences/memory.seq        # 初始 000000
├── memory.db
└── audit.log.jsonl
```

- 启动：开发期 `npm run dev`；生产期 `npm run build && npm run start`，进程绑定 `127.0.0.1:8080`
- Git：`git init` + 配置 remote；**push 失败不阻塞写入**（commit 本地成功即算完成，push 由下次操作或后台重试兜底）——这是对"commit+push 即备份"的工程化补充。
- 运维动作：`GET /health` 巡检；SQLite 异常时 `POST /v1/ops/rebuild-index`，以 Markdown 为准修复。
- 建议以 systemd / launchd 托管进程；Hermes 与 Core 同机部署（均走 127.0.0.1）。

---

## 12. 测试计划

**单元测试重点**：Validator 全枚举分支与非法输入（含 JSON Schema 额外字段拒绝、`review_at` 无 `+08:00` 拒绝）；ID 序列与重建无关性；Clock 格式；权限 SQL（跨 domain / shared / manager 全量）；`Promise.all` 并发调用下 ID 唯一、严格递增且每条写入完整。
**降级场景测试清单**（契约 §7.2 全覆盖）：

| 场景                                                                                                                | 期望                                                                                       |
| ------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| LLM 超时 / 非 JSON / 字段缺失                                                                                       | pending→active，`llm_failure_retry_days`，reason 分别为 `llm_timeout / llm_invalid_output` |
| govern 校验失败                                                                                                     | 同上，reason=`llm_governance_failed`                                                       |
| review 单条失败                                                                                                     | 该条 UNCERTAIN + `llm_review_failed`，其余条目正常，整体 200                               |
| review UNCERTAIN                                                                                                    | active + `uncertain_retry_days`                                                            |
| promotion=uncertain                                                                                                 | 保持 domain + `uncertain_retry_days`                                                       |
| **集成测试**：对应 API 规范场景一~九逐条编写 E2E；另加"CHANGED → 新 pending → 下轮治理 → 旧条 archived"全链路用例。 |

---

## 13. 风险与注意事项

| 风险                                                                                                                     | 应对                                                                                 |
| ------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------ |
| Git push 失败影响可用性                                                                                                  | commit 本地成功即返回，push 失败记录待重试，不阻塞主流程                             |
| LLM 输出质量差导致 uncertain 堆积                                                                                        | 审计统计 decision 分布；观察期迭代 Prompt（版本化），必要时临时调大 batch 或人工确认 |
| 同进程并发写入导致 ID / Git 竞争                                                                                         | `WriteCoordinator` 串行化完整写入链路；并发 ID、Markdown 与 Git 集成测试覆盖         |
| 误启多实例导致跨进程竞争                                                                                                 | 部署文档明确单实例；后续如需多实例再评估文件锁或外部协调                             |
| `+08:00` 与 `Z` 混用                                                                                                     | Validator 与 rebuild 一致性检查强制拒绝 `Z` 后缀                                     |
| 设计文档间不一致引发实现漂移                                                                                             | 以 §1 基线裁决表为准，代码评审对照冻结项 17 条                                       |
| **后续扩展点**（本期不做，预留）：FTS5 全文索引、语义搜索、多实例并发、Conflicts 独立建模、Memory 数量增长后的索引策略。 |

---

## 附：契约冻结项 → 模块对照（速查）

| 冻结项                                     | 落点                                               |
| ------------------------------------------ | -------------------------------------------------- |
| 两类 LLM 任务 + Schema                     | `schemas/governance.ts` / `schemas/review.ts`      |
| decision / promotion / result 枚举         | TypeBox enum + 推导的 TypeScript 类型              |
| 无 confidence、不改 content                | `additionalProperties: false` + `INVALID_ARGUMENT` |
| 降级天数可配置                             | `config/governance.yaml`                           |
| Prompt 版本化                              | `prompts/**` + 审计 `prompt_version` 字段          |
| 审计记录 Prompt 版本 / 失败原因 / 降级天数 | `AuditLogger`                                      |
| Core 不调 LLM、不调度                      | §1 差异裁决 + 不实现清单                           |
