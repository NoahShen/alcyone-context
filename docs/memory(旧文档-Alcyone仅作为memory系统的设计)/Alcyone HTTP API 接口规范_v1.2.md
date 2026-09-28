# Alcyone HTTP API 接口规范

版本：v1.2  
状态：设计稿  
范围：按功能与场景组织的 HTTP 接口定义  
基础：绑定 `127.0.0.1`，认证使用 `Authorization: Bearer <token>`
命名：**Alcyone** 即一个 Agent 长期记忆系统；核心组件为 **Alcyone Core**，管理角色为 **Alcyone Manager**（Agent 侧）。

---

## 1. 接口总览

按使用场景组织：

| 场景                 | 接口                          | 使用者          |
| -------------------- | ----------------------------- | --------------- |
| 搜索记忆             | `POST /v1/memory/search`      | Agent / Manager |
| 获取单条记忆         | `GET /v1/memory/{id}`         | Agent / Manager |
| 保存记忆             | `POST /v1/memory/remember`    | Agent           |
| 获取待治理记忆       | `GET /v1/manager/pending`     | Manager         |
| 治理单条记忆         | `POST /v1/manager/govern`     | Manager         |
| 获取到期 Review      | `GET /v1/manager/due-reviews` | Manager         |
| 批量提交 Review 结果 | `POST /v1/manager/review`     | Manager         |
| 健康检查             | `GET /health`                 | 运维            |
| 重建索引             | `POST /v1/ops/rebuild-index`  | Manager         |

---

## 2. 通用约定

### 2.1 请求

- 所有请求体使用 `application/json`。
- 所有时间字段使用北京时间 ISO8601，带 `+08:00`。
- 认证头：

```text
Authorization: Bearer <token>
```

### 2.2 响应

统一响应结构：

```json
{
  "ok": true,
  "data": { ... }
}
```

错误响应：

```json
{
  "ok": false,
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "Agent 无权访问该 Domain。"
  }
}
```

### 2.3 错误码

| code                | HTTP 状态 | 说明             |
| ------------------- | --------- | ---------------- |
| `UNAUTHORIZED`      | 401       | Token 缺失或无效 |
| `PERMISSION_DENIED` | 403       | 无权访问该资源   |
| `NOT_FOUND`         | 404       | Memory 不存在    |
| `INVALID_ARGUMENT`  | 400       | 参数不合法       |
| `LLM_FAILED`        | 502       | LLM 调用失败     |
| `INTERNAL_ERROR`    | 500       | 内部错误         |

### 2.4 权限摘要

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

### 2.5 并发写入

Core 为单实例服务，可并发接收 HTTP 请求。所有会修改持久化状态的接口（`remember`、`govern`、`review`、`rebuild-index`）由进程内 `WriteCoordinator` 按 FIFO 串行执行完整写入链路。

- `remember` 的 Memory ID 在互斥区内生成，因此并发调用返回的 ID 全局唯一且严格递增。
- 每个写请求在自身 Markdown、Git、SQLite 与审计步骤完成（或按既定 SQLite 降级规则处理）后才返回。
- 不支持多个 Core 进程同时操作同一个数据目录；客户端不得以多实例部署替代本接口的并发能力。

---

## 3. 场景一：搜索记忆

**场景**：Agent 需要用户长期背景时，先搜索 Memory。

### 3.1 请求

```http
POST /v1/memory/search
Authorization: Bearer <agent_token>
Content-Type: application/json

{
  "query": "投资偏好",
  "limit": 20
}
```

| 字段    | 类型    | 必填 | 说明                     |
| ------- | ------- | ---- | ------------------------ |
| `query` | string  | 否   | 关键词，空则返回最近记忆 |
| `limit` | integer | 否   | 默认 20，最大 100        |

### 3.2 处理

```text
AuthService.authenticate(token)
  ↓
SearchService.search(agent, query, limit)
  ↓
SqliteRepository.search(agent, query, limit)
  ↓
返回结果
```

权限条件直接写入 SQL：

```sql
WHERE status = 'active'
  AND (scope = 'shared' OR domain = :agent_domain)
```

### 3.3 响应

```json
{
  "ok": true,
  "data": {
    "items": [
      {
        "id": "MEM-000231",
        "type": "state",
        "scope": "domain",
        "domain": "quant",
        "status": "active",
        "content": "用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。",
        "source": {
          "type": "conversation",
          "ref": "session-2026-09-16"
        },
        "created_at": "2026-09-16T10:00:00+08:00",
        "updated_at": "2026-09-16T10:00:00+08:00",
        "review_at": "2027-09-16T10:00:00+08:00",
        "last_reviewed_at": null,
        "related": []
      }
    ],
    "total": 1
  }
}
```

### 3.4 错误

| 场景           | code               |
| -------------- | ------------------ |
| Token 无效     | `UNAUTHORIZED`     |
| limit 超出范围 | `INVALID_ARGUMENT` |

---

## 4. 场景二：获取单条记忆

**场景**：Agent 已知 Memory ID，需要读取完整内容。

### 4.1 请求

```http
GET /v1/memory/MEM-000231
Authorization: Bearer <agent_token>
```

### 4.2 处理

```text
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

### 4.3 响应

```json
{
  "ok": true,
  "data": {
    "id": "MEM-000231",
    "type": "state",
    "scope": "domain",
    "domain": "quant",
    "status": "active",
    "content": "用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。",
    "source": {
      "type": "conversation",
      "ref": "session-2026-09-16"
    },
    "created_at": "2026-09-16T10:00:00+08:00",
    "updated_at": "2026-09-16T10:00:00+08:00",
    "review_at": "2027-09-16T10:00:00+08:00",
    "last_reviewed_at": null,
    "related": []
  }
}
```

### 4.4 错误

| 场景                    | code                |
| ----------------------- | ------------------- |
| Memory 不存在           | `NOT_FOUND`         |
| Agent 无权访问该 domain | `PERMISSION_DENIED` |

---

## 5. 场景三：保存记忆

**场景**：Agent 发现值得长期保存的信息，调用 remember。

### 5.1 请求

```http
POST /v1/memory/remember
Authorization: Bearer <agent_token>
Content-Type: application/json

{
  "content": "用户偏好低维护成本的投资策略",
  "type": "state",
  "source": {
    "type": "conversation",
    "ref": "session-2026-09-16"
  }
}
```

| 字段          | 类型   | 必填 | 说明                                         |
| ------------- | ------ | ---- | -------------------------------------------- |
| `content`     | string | 是   | 记忆内容                                     |
| `type`        | enum   | 否   | `state` / `event` / `decision`，默认 `state` |
| `source.type` | string | 是   | 来源类型                                     |
| `source.ref`  | string | 否   | 来源引用                                     |

说明：

- `scope` 默认 `domain`。
- `domain` 由 Token 决定，Agent 不能声明。
- 写入即 `pending`，立即返回，不做关系判断。

### 5.2 处理

```text
AuthService.authenticate(token)
  ↓
WriteCoordinator.runExclusive(
  () => RememberService.remember(agent, content, type, source)
  ↓
IdGenerator.next_memory_id()
  ↓
构造 Memory，status = pending，domain = agent.domain
  ↓
MarkdownRepository.write(memory)
  ↓
GitRepository.commit
  ↓
SqliteRepository.insert(memory)
  ↓
AuditLogger.log(action="remember")
)
  ↓
返回
```

### 5.3 响应

```json
{
  "ok": true,
  "data": {
    "id": "MEM-000231",
    "status": "pending",
    "message": "Memory 已写入，等待治理。"
  }
}
```

### 5.4 错误

| 场景         | code               |
| ------------ | ------------------ |
| content 为空 | `INVALID_ARGUMENT` |
| type 非法    | `INVALID_ARGUMENT` |
| Token 无效   | `UNAUTHORIZED`     |

---

## 6. 场景四：获取待治理记忆

**场景**：Manager 定期拉取 pending Memory，准备治理。

### 6.1 请求

```http
GET /v1/manager/pending?limit=50
Authorization: Bearer <manager_token>
```

| 参数    | 类型    | 必填 | 说明              |
| ------- | ------- | ---- | ----------------- |
| `limit` | integer | 否   | 默认 50，最大 200 |

### 6.2 处理

```text
AuthService.authenticate(manager_token)
  ↓
QueryService.list_pending(manager, limit)
  ↓
SqliteRepository.query_pending(limit)
  ↓
返回
```

SQL：

```sql
SELECT *
FROM memory
WHERE status = 'pending'
ORDER BY created_at ASC
LIMIT :limit;
```

### 6.3 响应

```json
{
  "ok": true,
  "data": {
    "items": [
      {
        "id": "MEM-000231",
        "type": "state",
        "scope": "domain",
        "domain": "quant",
        "status": "pending",
        "content": "用户偏好低维护成本的投资策略",
        "created_at": "2026-09-16T10:00:00+08:00"
      }
    ],
    "total": 1
  }
}
```

### 6.4 错误

| 场景             | code                |
| ---------------- | ------------------- |
| Token 非 Manager | `PERMISSION_DENIED` |

---

## 7. 场景五：治理单条记忆

**场景**：Manager 对 pending Memory 调用 LLM 判断关系，并提交治理决策。

### 7.1 请求

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

| 字段                | 类型        | 必填 | 说明                                         |
| ------------------- | ----------- | ---- | -------------------------------------------- |
| `memory_id`         | string      | 是   | 待治理 Memory ID                             |
| `decision`          | enum        | 是   | `new` / `duplicate` / `update` / `uncertain` |
| `target_memory_ids` | string[]    | 否   | 相关已有 Memory ID                           |
| `promotion`         | enum        | 否   | `yes` / `no` / `uncertain`，默认 `no`        |
| `review_at`         | string/null | 否   | 建议的下次 Review 时间                       |
| `reason`            | string      | 是   | 判断理由，用于审计                           |

说明：

- Manager 负责调用 LLM 得到上述结构。
- Core 负责校验并执行。
- 不允许通过该接口修改 content。

### 7.2 处理

```text
AuthService.authenticate(manager_token)
  ↓
GovernanceValidator.validate(request)
  ↓
GovernanceService.govern_pending(manager, memory_id, decision)
  ↓
  ├── 执行 decision
  └── 若 promotion = yes，执行 Promotion
  ↓
MarkdownRepository.update / move
  ↓
GitRepository.commit
  ↓
SqliteRepository.update
  ↓
AuditLogger.log(action="govern")
  ↓
返回
```

### 7.3 decision 映射

| decision    | Core 执行                                              |
| ----------- | ------------------------------------------------------ |
| `new`       | `status = active`；`review_at = now + 默认周期`        |
| `duplicate` | `status = archived`；`related` 指向目标 Memory         |
| `update`    | pending → active；旧 Memory → archived；双向 `related` |
| `uncertain` | `status = active`；`review_at = now + 30d`             |

默认 review_at：

| type     | scope  | 默认 review_at |
| -------- | ------ | -------------- |
| state    | shared | now + 180d     |
| state    | domain | now + 365d     |
| decision | shared | now + 180d     |
| decision | domain | now + 365d     |
| event    | 任意   | null           |

Promotion 映射：

| promotion   | Core 执行                            |
| ----------- | ------------------------------------ |
| `yes`       | `scope = shared`，`domain = null`    |
| `no`        | 保持 domain                          |
| `uncertain` | 保持 domain，`review_at = now + 30d` |

### 7.4 响应

```json
{
  "ok": true,
  "data": {
    "memory_id": "MEM-000231",
    "decision": "new",
    "status": "active",
    "promotion": "no",
    "review_at": "2027-09-16T10:00:00+08:00",
    "related": []
  }
}
```

### 7.5 错误

| 场景                     | code                |
| ------------------------ | ------------------- |
| memory_id 不存在         | `NOT_FOUND`         |
| decision 非法            | `INVALID_ARGUMENT`  |
| target_memory_ids 不存在 | `INVALID_ARGUMENT`  |
| 试图修改 content         | `INVALID_ARGUMENT`  |
| 非 Manager Token         | `PERMISSION_DENIED` |

### 7.6 校验失败降级

Core 校验失败时：

```text
pending -> active
review_at = now + 30d
reason = "llm_governance_failed"
```

返回中标注：

```json
{
  "ok": true,
  "data": {
    "memory_id": "MEM-000231",
    "decision": "uncertain",
    "status": "active",
    "review_at": "2026-10-16T10:00:00+08:00",
    "reason": "llm_governance_failed"
  }
}
```

---

## 8. 场景六：获取到期 Review

**场景**：Manager 定期批量拉取到期需要 Review 的 Memory。

### 8.1 请求

```http
GET /v1/manager/due-reviews?limit=50
Authorization: Bearer <manager_token>
```

| 参数    | 类型    | 必填 | 说明              |
| ------- | ------- | ---- | ----------------- |
| `limit` | integer | 否   | 默认 50，最大 200 |

### 8.2 处理

```text
AuthService.authenticate(manager_token)
  ↓
QueryService.list_due_reviews(manager, today, limit)
  ↓
SqliteRepository.query_due_reviews(today, limit)
  ↓
返回
```

SQL：

```sql
SELECT *
FROM memory
WHERE status = 'active'
  AND review_at IS NOT NULL
  AND review_at <= :today
ORDER BY review_at ASC
LIMIT :limit;
```

`today` 为北京时间当天 00:00:00+08:00。

### 8.3 响应

```json
{
  "ok": true,
  "data": {
    "items": [
      {
        "id": "MEM-000231",
        "type": "state",
        "scope": "domain",
        "domain": "quant",
        "status": "active",
        "content": "用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。",
        "review_at": "2026-09-16T10:00:00+08:00",
        "last_reviewed_at": null
      }
    ],
    "total": 1
  }
}
```

### 8.4 错误

| 场景             | code                |
| ---------------- | ------------------- |
| 非 Manager Token | `PERMISSION_DENIED` |

---

## 9. 场景七：批量提交 Review 结果

**场景**：Manager 汇总到期 Memory，向用户确认后，由 LLM 结构化，批量提交 Review 结果。

### 9.1 请求

```http
POST /v1/manager/review
Authorization: Bearer <manager_token>
Content-Type: application/json

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

| 字段                       | 类型        | 必填 | 说明                                             |
| -------------------------- | ----------- | ---- | ------------------------------------------------ |
| `results[].memory_id`      | string      | 是   | 对应 Memory ID                                   |
| `results[].result`         | enum        | 是   | `VALID` / `CHANGED` / `DEPRECATED` / `UNCERTAIN` |
| `results[].new_content`    | string/null | 否   | 仅 `CHANGED` 时提供                              |
| `results[].next_review_at` | string/null | 否   | 下次 Review 时间                                 |
| `results[].reason`         | string      | 是   | 判断理由                                         |

### 9.2 处理

```text
AuthService.authenticate(manager_token)
  ↓
GovernanceValidator.validate_batch(results)
  ↓
GovernanceService.apply_review_batch(results)
  ↓
对每条：
  ├── VALID       -> status = active；review_at = next_review_at
  ├── CHANGED     -> RememberService.remember(manager, new_content)
  ├── DEPRECATED  -> status = deprecated；review_at = null
  └── UNCERTAIN   -> status = active；review_at = now + 30d
  ↓
MarkdownRepository.update
  ↓
GitRepository.commit
  ↓
SqliteRepository.update
  ↓
AuditLogger.log(action="review")
  ↓
返回
```

### 9.3 Review 结果映射

| result       | Core 执行                                                                                                          |
| ------------ | ------------------------------------------------------------------------------------------------------------------ |
| `VALID`      | `status = active`；`review_at = next_review_at`                                                                    |
| `CHANGED`    | 调用 `RememberService.remember(manager, new_content)` 写入新 pending；旧 Memory 保持 active，直到新 pending 被治理 |
| `DEPRECATED` | `status = deprecated`；`review_at = null`                                                                          |
| `UNCERTAIN`  | `status = active`；`review_at = now + 30d`                                                                         |

### 9.4 批量校验规则

- `results` 非空。
- 每个 `memory_id` 存在且状态为 `active`。
- `result` 在允许集合内。
- `CHANGED` 必须提供 `new_content`。
- 非 `CHANGED` 不应提供 `new_content`。
- `next_review_at` 合法或 null。

单条校验失败时：

```text
该条降级为 UNCERTAIN
review_at = now + 30d
reason = "llm_review_failed"
```

其余条目正常处理。

### 9.5 响应

```json
{
  "ok": true,
  "data": {
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
}
```

### 9.6 错误

| 场景                  | code                     |
| --------------------- | ------------------------ |
| results 为空          | `INVALID_ARGUMENT`       |
| 某条 memory_id 不存在 | 该条降级，整体仍返回 200 |
| 非 Manager Token      | `PERMISSION_DENIED`      |

---

## 10. 场景八：健康检查

**场景**：运维或外部探针检查 Alcyone Core 是否正常。

### 10.1 请求

```http
GET /health
```

无需认证。

### 10.2 处理

```text
OpsService.health()
  ↓
检查 SQLite 可读
检查 Markdown 目录存在
检查 Git 仓库可用
```

### 10.3 响应

```json
{
  "ok": true,
  "data": {
    "status": "ok",
    "sqlite": "ok",
    "markdown": "ok",
    "git": "ok",
    "timestamp": "2026-09-16T10:00:00+08:00"
  }
}
```

异常时：

```json
{
  "ok": false,
  "error": {
    "code": "INTERNAL_ERROR",
    "message": "SQLite 不可读。"
  }
}
```

---

## 11. 场景九：重建索引

**场景**：SQLite 损坏、丢失或 schema 变更后，从 Markdown 重建。

### 11.1 请求

```http
POST /v1/ops/rebuild-index
Authorization: Bearer <manager_token>
```

### 11.2 处理

```text
AuthService.authenticate(manager_token)
  ↓
OpsService.rebuild_index(manager)
  ↓
MarkdownRepository.scan_all()
  ↓
解析 frontmatter
  ↓
SqliteRepository.clear()
  ↓
SqliteRepository.bulk_insert(memories)
  ↓
AuditLogger.log(action="rebuild_index")
  ↓
返回
```

### 11.3 响应

```json
{
  "ok": true,
  "data": {
    "rebuilt": 231,
    "warnings": [
      {
        "file": "memory/quant/MEM-000100.md",
        "issue": "related 中的 MEM-000999 不存在"
      }
    ]
  }
}
```

### 11.4 错误

| 场景                | code                |
| ------------------- | ------------------- |
| 非 Manager Token    | `PERMISSION_DENIED` |
| Markdown 目录不可读 | `INTERNAL_ERROR`    |

---

## 12. 接口与场景对照

| 场景            | 接口                          | 使用者          | 是否写审计 |
| --------------- | ----------------------------- | --------------- | ---------- |
| 搜索记忆        | `POST /v1/memory/search`      | Agent / Manager | 否         |
| 获取单条记忆    | `GET /v1/memory/{id}`         | Agent / Manager | 否         |
| 保存记忆        | `POST /v1/memory/remember`    | Agent           | 是         |
| 获取待治理记忆  | `GET /v1/manager/pending`     | Manager         | 否         |
| 治理单条记忆    | `POST /v1/manager/govern`     | Manager         | 是         |
| 获取到期 Review | `GET /v1/manager/due-reviews` | Manager         | 否         |
| 批量提交 Review | `POST /v1/manager/review`     | Manager         | 是         |
| 健康检查        | `GET /health`                 | 运维            | 否         |
| 重建索引        | `POST /v1/ops/rebuild-index`  | Manager         | 是         |

---

## 13. 接口设计原则

1. 接口按场景组织，不按资源 CRUD 组织。
2. 不提供 `memory.update()`，内容变化通过 `remember` 新建。
3. 不提供 `conflict.resolve()`，冲突由 LLM 在治理时判断。
4. 治理接口由 Manager 调用，Core 只负责校验和执行。
5. LLM 调用发生在 Manager 侧，Core 不直接调用 LLM。
6. 所有写操作都写审计日志。
7. 权限条件直接写入 SQL，不在应用层二次过滤。
8. 时间字段统一北京时间 ISO8601，带 `+08:00`。
