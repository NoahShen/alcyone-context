# Alcyone LLM 治理契约与 Prompt 规范

版本：v1.3  
状态：设计稿  
范围：Manager 侧 LLM 调用契约、输入输出 Schema、Prompt 模板、Manager 定时任务 Prompt、校验与降级规则  
基础：HTTP API 接口规范、数据模型与存储规范
命名：**Alcyone** 即一个 Agent 长期记忆系统；核心组件为 **Alcyone Core**，管理角色为 **Alcyone Manager**（Agent 侧）。

---

## 0. 关于用户确认的执行位置

用户确认 **不在 Alcyone Core 执行**，也不通过 Alcyone Core 的 HTTP 接口完成。

```text
用户
  ↕ 自然对话（由 Manager Agent 发起）
Alcyone Manager Agent（在 Hermes 中执行 cron）
  ↓ HTTP + manager token
Alcyone Core
```

职责划分：

- **用户**：只和 Manager Agent 交互，不接触 Alcyone Core。
- **Alcyone Manager Agent**：由 Harness（如 Hermes）按 cron 调度执行；负责拉取待处理 Memory、调用 LLM、在需要时通过对话渠道向用户确认、再提交 Core 执行。
- **Alcyone Core**：只接收结构化后的治理结果，不处理用户原文。

用户确认渠道由 Manager Agent 根据上下文自行决定，例如：

- 与用户直接对话
- 日报 / 通知
- 其他 Agent 的用户交互渠道

本规范只定义 LLM 契约与 Agent 定时任务 Prompt，不约束具体 UI。

---

## 1. 总览

### 1.1 角色分工

```text
Alcyone Core：
  确定性执行
  - 写入 pending
  - 校验 Manager 提交的结构化结果
  - 执行状态变更
  - 写 Markdown / Git / SQLite

Alcyone Manager Agent：
  高级语义判断 + 用户交互
  - 由 Harness 定时调度（如 Hermes cron）
  - 拉取 pending / due-reviews
  - 调用 LLM
  - 需要时向用户确认
  - 提交给 Core 执行
```

### 1.2 LLM 任务清单

| 任务               | 触发场景                                              | 对应 HTTP 接口            |
| ------------------ | ----------------------------------------------------- | ------------------------- |
| Pending 治理判断   | 新 pending Memory 需要判断与已有 active Memory 的关系 | `POST /v1/manager/govern` |
| 批量 Review 结构化 | 到期 Memory 经用户确认后，结构化 Review 结果          | `POST /v1/manager/review` |

Promotion 不单独作为 LLM 任务，它是 Pending 治理判断的输出字段之一。

### 1.3 通用原则

1. LLM 输出必须是结构化 JSON，可被 Core 校验。
2. LLM 不能直接修改 content，只能建议新建或关联。
3. LLM 输出不包含 `confidence`，置信度不作为决策依据。
4. LLM 调用失败或输出非法时，Core 统一降级处理。
5. 所有 LLM 输出都记录到审计日志。
6. Prompt 版本化管理，变更需评审。
7. 降级时间、默认 Review 周期均可配置。
8. LLM 由 Manager Agent 自带，Alcyone 记忆系统不单独配置 LLM。

---

## 2. 治理配置

Alcyone 记忆系统不配置 LLM。Manager Agent 自带的 LLM 由其 Harness 管理。

Alcyone 记忆系统只需配置治理相关的周期参数：

```yaml
# config/governance.yaml
review:
  # 默认 Review 周期（天）
  default_days:
    state:
      shared: 180
      domain: 365
    decision:
      shared: 180
      domain: 365
    event: null

  # UNCERTAIN 后重新 Review 的天数
  uncertain_retry_days: 30

  # LLM 失败降级后重新 Review 的天数
  llm_failure_retry_days: 30

  # 到期 Review 批量大小
  due_reviews_batch_size: 50

  # pending 治理批量大小
  pending_batch_size: 50
```

### 2.1 配置项说明

| 配置项                         | 默认值 | 说明                               |
| ------------------------------ | ------ | ---------------------------------- |
| `default_days.state.shared`    | 180    | state + shared 默认 Review 周期    |
| `default_days.state.domain`    | 365    | state + domain 默认 Review 周期    |
| `default_days.decision.shared` | 180    | decision + shared 默认 Review 周期 |
| `default_days.decision.domain` | 365    | decision + domain 默认 Review 周期 |
| `default_days.event`           | null   | event 默认不设置 review_at         |
| `uncertain_retry_days`         | 30     | UNCERTAIN 后重新 Review 的天数     |
| `llm_failure_retry_days`       | 30     | LLM 失败降级后重新 Review 的天数   |
| `due_reviews_batch_size`       | 50     | 批量 Review 拉取条数               |
| `pending_batch_size`           | 50     | 批量 pending 治理拉取条数          |

### 2.2 配置使用点

| 使用点                         | 配置项                        |
| ------------------------------ | ----------------------------- |
| decision = new                 | `default_days.{type}.{scope}` |
| decision = uncertain           | `uncertain_retry_days`        |
| promotion = uncertain          | `uncertain_retry_days`        |
| review result = UNCERTAIN      | `uncertain_retry_days`        |
| LLM 超时 / 输出非法 / 校验失败 | `llm_failure_retry_days`      |
| Review 单条校验失败            | `llm_failure_retry_days`      |
| Manager 拉取到期 Review        | `due_reviews_batch_size`      |
| Manager 拉取 pending           | `pending_batch_size`          |

---

## 3. 任务一：Pending 治理判断

### 3.1 场景

Manager Agent 拉取到 pending Memory 后，需要判断它与已有 active Memory 的关系，输出治理决策。

```text
Manager Agent（cron 触发）
  ↓
GET /v1/manager/pending
  ↓
对每条 pending：
  收集候选 active Memory
    ↓
  调用 LLM
    ↓
  得到治理决策
    ↓
  POST /v1/manager/govern
```

### 3.2 输入 Schema

```json
{
  "actor": "memory-manager",
  "pending_memory": {
    "id": "MEM-000231",
    "type": "state",
    "scope": "domain",
    "domain": "quant",
    "content": "用户偏好低维护成本的投资策略"
  },
  "candidate_memories": [
    {
      "id": "MEM-000100",
      "type": "state",
      "scope": "domain",
      "domain": "quant",
      "status": "active",
      "content": "用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。"
    }
  ]
}
```

字段说明：

| 字段                           | 类型          | 说明                                |
| ------------------------------ | ------------- | ----------------------------------- |
| `actor`                        | string        | 固定 `memory-manager`               |
| `pending_memory`               | object        | 待治理 Memory                       |
| `pending_memory.id`            | string        | Memory ID                           |
| `pending_memory.type`          | enum          | `state` / `event` / `decision`      |
| `pending_memory.scope`         | enum          | `domain`（pending 阶段默认 domain） |
| `pending_memory.domain`        | string        | 所属 domain                         |
| `pending_memory.content`       | string        | 记忆内容                            |
| `candidate_memories`           | array         | 候选 active Memory 列表             |
| `candidate_memories[].id`      | string        | 已有 Memory ID                      |
| `candidate_memories[].type`    | enum          | 类型                                |
| `candidate_memories[].scope`   | enum          | `shared` / `domain`                 |
| `candidate_memories[].domain`  | string / null | `shared` 时为 null                  |
| `candidate_memories[].status`  | enum          | 通常为 `active`                     |
| `candidate_memories[].content` | string        | 记忆内容                            |

候选筛选条件：

```sql
SELECT *
FROM memory
WHERE status = 'active'
  AND (
    domain = :pending_domain
    OR scope = 'shared'
  )
ORDER BY updated_at DESC;
```

### 3.3 输出 Schema

```json
{
  "decision": "new | duplicate | update | uncertain",
  "target_memory_ids": ["MEM-000100"],
  "promotion": "yes | no | uncertain",
  "review_at": "2027-09-16T10:00:00+08:00",
  "reason": "新信息与已有记忆相关，但描述的是不同维度，因此新建。"
}
```

字段说明：

| 字段                | 类型        | 必填 | 说明                                               |
| ------------------- | ----------- | ---- | -------------------------------------------------- |
| `decision`          | enum        | 是   | 治理决策                                           |
| `target_memory_ids` | string[]    | 是   | 相关已有 Memory ID，无则为空数组                   |
| `promotion`         | enum        | 是   | 是否建议升级为 Shared                              |
| `review_at`         | string/null | 否   | 建议的下次 Review 时间，不提供则由 Core 按配置计算 |
| `reason`            | string      | 是   | 判断理由，用于审计                                 |

### 3.4 decision 语义

| decision    | 语义                             | Core 执行                                                          |
| ----------- | -------------------------------- | ------------------------------------------------------------------ |
| `new`       | 新事实，与已有记忆无关或维度不同 | `status = active`；`review_at = now + default_days.{type}.{scope}` |
| `duplicate` | 与已有记忆表达同一事实           | `status = archived`；`related` 指向目标 Memory                     |
| `update`    | 新事实取代已有事实               | pending → active；旧 Memory → archived；双向 `related`             |
| `uncertain` | 无法可靠判断                     | `status = active`；`review_at = now + uncertain_retry_days`        |

约束：

- `decision = duplicate` 时，`target_memory_ids` 必须非空。
- `decision = update` 时，`target_memory_ids` 必须非空。
- `decision = new` 时，`target_memory_ids` 可以为空。
- `decision = uncertain` 时，`target_memory_ids` 可以为空。

### 3.5 promotion 语义

| promotion   | 语义                      | Core 执行                                             |
| ----------- | ------------------------- | ----------------------------------------------------- |
| `yes`       | 具有跨 Agent 长期复用价值 | `scope = shared`，`domain = null`                     |
| `no`        | 仅属于当前 domain         | 保持 domain                                           |
| `uncertain` | 无法可靠判断              | 保持 domain，`review_at = now + uncertain_retry_days` |

约束：

- `decision = duplicate` 时，promotion 通常为 `no`。
- `promotion = yes` 时，先执行 decision，再执行 Promotion。
- 如果 decision 结果为 `archived`，不执行 Promotion。

### 3.6 LLM System Prompt

```text
你是 Alcyone 记忆系统的治理助手（Alcyone 是一个 Agent 长期记忆系统的名称）。你的任务是判断一条新写入的 Memory（pending）
与已有 Memory（active）之间的关系。

规则：
1. 只输出 JSON，不要输出任何解释性文字。
2. 判断标准：两条 Memory 是否表达同一事实、同一维度、同一主体。
3. 如果新 Memory 与已有 Memory 表达相同事实，返回 duplicate。
4. 如果新 Memory 取代了已有 Memory 的事实，返回 update。
5. 如果新 Memory 是全新的事实，返回 new。
6. 如果无法可靠判断，返回 uncertain。宁可 uncertain，也不要错误覆盖。
7. 判断是否具有跨 Agent 长期复用价值，输出 promotion 字段。
8. 不要修改 content。
9. 不要输出 confidence。

输出格式：
{
  "decision": "new | duplicate | update | uncertain",
  "target_memory_ids": ["MEM-xxxxxx"],
  "promotion": "yes | no | uncertain",
  "review_at": "ISO8601 或 null",
  "reason": "判断理由"
}
```

### 3.7 LLM User Prompt 模板

```text
待治理 Memory：
- ID: {pending_memory.id}
- 类型: {pending_memory.type}
- Scope: {pending_memory.scope}
- Domain: {pending_memory.domain}
- 内容: {pending_memory.content}

已有 Memory 候选：
{candidate_memories}

请输出治理决策 JSON。
```

候选列表格式化示例：

```text
1. ID: MEM-000100
   类型: state
   Scope: domain
   Domain: quant
   内容: 用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。
```

### 3.8 输出示例

#### 示例一：new

```json
{
  "decision": "new",
  "target_memory_ids": [],
  "promotion": "no",
  "review_at": "2027-09-16T10:00:00+08:00",
  "reason": "该偏好与已有记忆描述的是不同维度，属于新事实。"
}
```

#### 示例二：duplicate

```json
{
  "decision": "duplicate",
  "target_memory_ids": ["MEM-000100"],
  "promotion": "no",
  "review_at": null,
  "reason": "与 MEM-000100 表达同一事实，仅措辞不同。"
}
```

#### 示例三：update

```json
{
  "decision": "update",
  "target_memory_ids": ["MEM-000100"],
  "promotion": "no",
  "review_at": "2027-09-16T10:00:00+08:00",
  "reason": "新偏好取代了 MEM-000100 描述的旧偏好。"
}
```

#### 示例四：uncertain

```json
{
  "decision": "uncertain",
  "target_memory_ids": ["MEM-000100"],
  "promotion": "uncertain",
  "review_at": "2026-10-16T10:00:00+08:00",
  "reason": "新信息与 MEM-000100 部分相关，但无法确定是否构成取代。"
}
```

#### 示例五：promotion = yes

```json
{
  "decision": "new",
  "target_memory_ids": [],
  "promotion": "yes",
  "review_at": "2027-09-16T10:00:00+08:00",
  "reason": "该事实具有跨 Agent 复用价值，建议升级为 Shared。"
}
```

### 3.9 校验规则

`GovernanceValidator.validate(output)` 检查：

| 检查项                     | 规则                                             |
| -------------------------- | ------------------------------------------------ |
| `decision`                 | 必须在 `new / duplicate / update / uncertain` 中 |
| `target_memory_ids`        | 必须为数组，元素为字符串                         |
| `decision = duplicate`     | `target_memory_ids` 非空                         |
| `decision = update`        | `target_memory_ids` 非空                         |
| `target_memory_ids` 存在性 | 每个 ID 必须在候选列表中                         |
| `promotion`                | 必须在 `yes / no / uncertain` 中                 |
| `review_at`                | 合法 ISO8601 带 `+08:00`，或 null                |
| `reason`                   | 非空字符串                                       |
| 禁止字段                   | 不允许出现 `content`、`confidence` 等未定义字段  |
| content 修改               | 不允许修改 content                               |

校验失败时：

```text
pending -> active
review_at = now + llm_failure_retry_days
reason = "llm_governance_failed"
```

---

## 4. 任务二：批量 Review 结构化

### 4.1 场景

Manager Agent 拉取到期 Memory，向用户确认后，将用户回答交给 LLM 结构化，批量提交给 Core。

```text
Manager Agent（cron 触发）
  ↓
GET /v1/manager/due-reviews
  ↓
汇总为可读问题
  ↓
通过对话渠道向用户确认（Agent 自然发起）
  ↓
用户回答
  ↓
Agent 收集回答
  ↓
调用 LLM 结构化
  ↓
POST /v1/manager/review
```

### 4.2 输入 Schema

```json
{
  "review_items": [
    {
      "memory_id": "MEM-000231",
      "type": "state",
      "scope": "domain",
      "domain": "quant",
      "content": "用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。",
      "user_response": "仍然成立"
    },
    {
      "memory_id": "MEM-000487",
      "type": "state",
      "scope": "domain",
      "domain": "quant",
      "content": "用户偏好低维护成本的投资策略。",
      "user_response": "已经变了，现在更关注收益稳定性"
    }
  ]
}
```

字段说明：

| 字段                           | 类型          | 说明                |
| ------------------------------ | ------------- | ------------------- |
| `review_items`                 | array         | 待结构化条目        |
| `review_items[].memory_id`     | string        | Memory ID           |
| `review_items[].type`          | enum          | 类型                |
| `review_items[].scope`         | enum          | `shared` / `domain` |
| `review_items[].domain`        | string / null | 所属 domain         |
| `review_items[].content`       | string        | 原 Memory 内容      |
| `review_items[].user_response` | string        | 用户确认回答        |

### 4.3 输出 Schema

```json
{
  "results": [
    {
      "memory_id": "MEM-000231",
      "result": "VALID",
      "new_content": null,
      "next_review_at": "2027-09-16T10:00:00+08:00",
      "reason": "用户确认该偏好仍然成立。"
    }
  ]
}
```

字段说明：

| 字段                       | 类型        | 必填 | 说明                                         |
| -------------------------- | ----------- | ---- | -------------------------------------------- |
| `results`                  | array       | 是   | 结构化结果                                   |
| `results[].memory_id`      | string      | 是   | 对应 Memory ID                               |
| `results[].result`         | enum        | 是   | `VALID / CHANGED / DEPRECATED / UNCERTAIN`   |
| `results[].new_content`    | string/null | 否   | 仅 `CHANGED` 时提供                          |
| `results[].next_review_at` | string/null | 否   | 下次 Review 时间，不提供则由 Core 按配置计算 |
| `results[].reason`         | string      | 是   | 判断理由                                     |

### 4.4 result 语义

| result       | 语义                 | Core 执行                                                                                   |
| ------------ | -------------------- | ------------------------------------------------------------------------------------------- |
| `VALID`      | 信息仍然有效         | `status = active`；`review_at = next_review_at 或 default_days.{type}.{scope}`              |
| `CHANGED`    | 信息已变化           | 调用 `RememberService.remember(manager, new_content)` 写入新 pending；旧 Memory 保持 active |
| `DEPRECATED` | 不再适合作为当前记忆 | `status = deprecated`；`review_at = null`                                                   |
| `UNCERTAIN`  | 无法可靠确认         | `status = active`；`review_at = now + uncertain_retry_days`                                 |

约束：

- `CHANGED` 必须提供 `new_content`。
- 非 `CHANGED` 不应提供 `new_content`。
- `next_review_at` 可以为空，由 Core 按配置计算。

### 4.5 LLM System Prompt

```text
你是 Alcyone 记忆系统的 Review 助手（Alcyone 是一个 Agent 长期记忆系统的名称）。用户对一批长期 Memory 进行了确认，
你需要根据用户的回答，为每条 Memory 输出结构化的 Review 结果。

规则：
1. 只输出 JSON，不要输出任何解释性文字。
2. 用户回答表示仍然成立 -> VALID。
3. 用户回答表示已经变化 -> CHANGED，并提取新的事实作为 new_content。
4. 用户回答表示不再适用 -> DEPRECATED。
5. 无法可靠判断 -> UNCERTAIN。
6. 每条 Memory 必须输出一个结果，不能遗漏。
7. 不要输出 confidence。
8. 不要修改 memory_id。

输出格式：
{
  "results": [
    {
      "memory_id": "MEM-xxxxxx",
      "result": "VALID | CHANGED | DEPRECATED | UNCERTAIN",
      "new_content": "string 或 null",
      "next_review_at": "ISO8601 或 null",
      "reason": "判断理由"
    }
  ]
}
```

### 4.6 LLM User Prompt 模板

```text
以下是需要 Review 的 Memory 及用户回答：

{review_items}

请输出结构化 Review 结果 JSON。
```

输入格式化示例：

```text
1. ID: MEM-000231
   内容: 用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。
   用户回答: 仍然成立

2. ID: MEM-000487
   内容: 用户偏好低维护成本的投资策略。
   用户回答: 已经变了，现在更关注收益稳定性
```

### 4.7 输出示例

#### 示例一：VALID

```json
{
  "memory_id": "MEM-000231",
  "result": "VALID",
  "new_content": null,
  "next_review_at": "2027-09-16T10:00:00+08:00",
  "reason": "用户确认该偏好仍然成立。"
}
```

#### 示例二：CHANGED

```json
{
  "memory_id": "MEM-000487",
  "result": "CHANGED",
  "new_content": "用户当前更关注收益稳定性，而非低维护成本。",
  "next_review_at": "2027-09-16T10:00:00+08:00",
  "reason": "用户确认偏好已变化。"
}
```

#### 示例三：DEPRECATED

```json
{
  "memory_id": "MEM-000512",
  "result": "DEPRECATED",
  "new_content": null,
  "next_review_at": null,
  "reason": "用户表示该偏好已不再适用。"
}
```

#### 示例四：UNCERTAIN

```json
{
  "memory_id": "MEM-000600",
  "result": "UNCERTAIN",
  "new_content": null,
  "next_review_at": "2026-10-16T10:00:00+08:00",
  "reason": "用户回答模糊，无法确认是否仍然成立。"
}
```

### 4.8 校验规则

`GovernanceValidator.validate_batch(results)` 检查：

| 检查项                | 规则                                                 |
| --------------------- | ---------------------------------------------------- |
| `results`             | 非空数组                                             |
| `results[].memory_id` | 必须存在于输入 `review_items`                        |
| 覆盖性                | 每条输入 `memory_id` 必须有且仅有一个结果            |
| `result`              | 必须在 `VALID / CHANGED / DEPRECATED / UNCERTAIN` 中 |
| `CHANGED`             | 必须提供非空 `new_content`                           |
| 非 `CHANGED`          | `new_content` 必须为 null                            |
| `next_review_at`      | 合法 ISO8601 带 `+08:00`，或 null                    |
| `reason`              | 非空字符串                                           |
| 禁止字段              | 不允许出现 `confidence` 等未定义字段                 |

单条校验失败时：

```text
该条 result = UNCERTAIN
review_at = now + llm_failure_retry_days
reason = "llm_review_failed"
```

其余条目正常处理。整体仍返回 200。

---

## 5. Prompt 版本管理

### 5.1 版本命名

```text
prompts/
├── govern/
│   ├── system.v1.txt
│   └── user.v1.txt
├── review/
│   ├── system.v1.txt
│   └── user.v1.txt
└── tasks/
    ├── pending_task.v1.txt
    └── review_task.v1.txt
```

其中 `tasks/` 存放 Manager Agent 的定时任务提示词，见第 6 章。

### 5.2 版本记录

每个 Prompt 文件头部记录：

```text
# Prompt: govern/system
# Version: v1
# Date: 2026-09-18
# Description: Pending 治理判断系统提示词
```

### 5.3 变更流程

- Prompt 变更需评审。
- 变更后在审计日志中记录 Prompt 版本。
- 治理结果记录使用的 Prompt 版本，便于回溯。

审计日志扩展字段：

```json
{
  "action": "govern",
  "target": "MEM-000231",
  "prompt_version": "govern/system.v1",
  "timestamp": "2026-09-18T10:00:00+08:00"
}
```

---

## 6. Manager 定时任务 Prompt

Manager Agent 由 Harness（如 Hermes）按 cron 调度执行。  
每轮执行时，Harness 将对应任务的 Prompt 注入 Agent，Agent 按 Prompt 完成工作。

Alcyone 记忆系统不实现调度，只提供 Prompt 规范与 HTTP 接口。

### 6.1 定时任务清单

| 任务         | 建议频率      | 对应 Prompt                 |
| ------------ | ------------- | --------------------------- |
| Pending 治理 | 每 10–30 分钟 | `tasks/pending_task.v1.txt` |
| 到期 Review  | 每天一次      | `tasks/review_task.v1.txt`  |

频率由 Harness 的 cron 配置决定，Alcyone 记忆系统不感知。

### 6.2 Pending 治理任务 Prompt

```text
你是 Alcyone Manager Agent。Alcyone 是一个 Agent 长期记忆系统的名称。本次任务是对待治理的 Memory 进行治理。

【任务目标】
拉取所有 pending 状态的 Memory，判断其与已有 active Memory 的关系，
并提交治理决策。

【执行步骤】
1. 调用 HTTP 接口获取待治理 Memory：
   GET /v1/manager/pending?limit={pending_batch_size}
   Header: Authorization: Bearer {manager_token}

2. 对每条 pending Memory：
   a. 调用 HTTP 接口获取该 Memory：
      GET /v1/memory/{id}
   b. 收集候选 active Memory：
      - 同 domain
      - shared
      - status = active
   c. 按 govern/system.v1 的 System Prompt 与 govern/user.v1 的 User Prompt
      调用 LLM，得到治理决策 JSON
   d. 校验 LLM 输出：
      - decision 必须在 new / duplicate / update / uncertain
      - target_memory_ids 必须在候选列表内
      - promotion 必须在 yes / no / uncertain
      - 不允许出现 content、confidence 等字段
   e. 校验通过后，调用：
      POST /v1/manager/govern
      Body: {
        "memory_id": "...",
        "decision": "...",
        "target_memory_ids": [...],
        "promotion": "...",
        "review_at": "...",
        "reason": "..."
      }
   f. 校验失败时：
      POST /v1/manager/govern
      Body: {
        "memory_id": "...",
        "decision": "uncertain",
        "target_memory_ids": [],
        "promotion": "uncertain",
        "review_at": null,
        "reason": "llm_governance_failed"
      }

3. 如果 LLM 判断 decision = uncertain，且候选中有多条 Memory 疑似相关，
   可以主动通过对话渠道向用户确认：
   - 向用户描述 pending Memory 与候选 Memory 的差异
   - 询问用户这是新事实、重复事实、还是取代关系
   - 根据用户回答重新调用 LLM 或直接给出 decision

4. 处理完成后，汇总本轮结果：
   - 处理条数
   - 各 decision 分布
   - 失败条数与原因
   - 向用户确认的条数与结果

【约束】
- 不直接修改 Memory content。
- 不绕过 HTTP 接口直接操作文件。
- 不确定时宁可 uncertain，也不要错误覆盖。
- 所有 HTTP 调用使用 manager_token。

【输出】
向用户或日志汇报本轮治理结果。
```

### 6.3 到期 Review 任务 Prompt

```text
你是 Alcyone Manager Agent。Alcyone 是一个 Agent 长期记忆系统的名称。本次任务是对到期的 Memory 进行 Review。

【任务目标】
拉取到期需要 Review 的 Memory，向用户确认后，结构化结果并提交。

【执行步骤】
1. 调用 HTTP 接口获取到期 Memory：
   GET /v1/manager/due-reviews?limit={due_reviews_batch_size}
   Header: Authorization: Bearer {manager_token}

2. 如果返回为空，结束本轮任务。

3. 将到期 Memory 汇总为用户可读的问题列表，例如：
   - [MEM-000231] 用户倾向于研究简单、可解释、具有长期结构性基础的投资策略。
     这个偏好目前仍然成立吗？
   - [MEM-000487] 用户偏好低维护成本的投资策略。
     这个偏好目前仍然成立吗？

4. 通过对话渠道向用户发送问题。
   渠道由你自行决定，例如：
   - 直接与用户对话
   - 通过日报 / 通知
   - 通过其他 Agent 的用户交互渠道

5. 等待用户回答。
   如果用户未回复：
   - 保留到期状态，不修改任何 Memory
   - 本轮结束，下次 cron 触发时重新提问

6. 收集用户回答后，构造 review_items：
   [
     {
       "memory_id": "MEM-000231",
       "type": "state",
       "scope": "domain",
       "domain": "quant",
       "content": "...",
       "user_response": "仍然成立"
     },
     ...
   ]

7. 按 review/system.v1 与 review/user.v1 调用 LLM，
   得到结构化 results JSON。

8. 校验 LLM 输出：
   - results 覆盖所有 review_items
   - result 必须在 VALID / CHANGED / DEPRECATED / UNCERTAIN
   - CHANGED 必须提供 new_content
   - 非 CHANGED 不应提供 new_content

9. 校验通过后，调用：
   POST /v1/manager/review
   Body: { "results": [...] }

10. 单条校验失败时：
    该条 result = UNCERTAIN
    next_review_at = null
    reason = "llm_review_failed"
    其余条目正常提交。

11. 处理完成后，汇总本轮结果：
    - 到期条数
    - 用户确认条数
    - 各 result 分布
    - 失败的条数与原因

【约束】
- 不直接修改 Memory content。
- 不绕过 HTTP 接口直接操作文件。
- 用户未回复时不修改任何 Memory。
- 所有 HTTP 调用使用 manager_token。

【输出】
向用户或日志汇报本轮 Review 结果。
```

### 6.4 Harness 调度建议

Manager Agent 的调度由 Harness 负责，例如 Hermes cron：

```yaml
# hermes.yaml 示例
jobs:
  - name: memory-govern-pending
    schedule: '*/15 * * * *'
    agent: memory-manager
    prompt: prompts/tasks/pending_task.v1.txt
    env:
      manager_token: tok_manager_xxx
      base_url: http://127.0.0.1:8080

  - name: memory-review-due
    schedule: '0 10 * * *'
    agent: memory-manager
    prompt: prompts/tasks/review_task.v1.txt
    env:
      manager_token: tok_manager_xxx
      base_url: http://127.0.0.1:8080
```

Alcyone 记忆系统不实现调度，只定义 Prompt。  
Harness 负责注入 Prompt、提供环境变量、记录 Agent 执行日志。

### 6.5 与 Alcyone Core 的边界

- Alcyone Core 不感知 Harness，不感知 cron。
- Alcyone Core 只提供 HTTP 接口。
- Manager Agent 的执行日志由 Harness 负责。
- 治理相关的审计由 Alcyone Core 记录到 `audit.log.jsonl`。
- Prompt 版本、Agent 执行上下文由 Harness 记录。

---

## 7. LLM 调用与降级策略

### 7.1 调用流程

```text
Manager Agent
  ↓
构造输入
  ↓
调用 LLM（由 Agent 自带）
  ↓
解析 JSON
  ↓
GovernanceValidator.validate
  ↓
通过 -> 提交 Core
失败 -> 降级处理
```

### 7.2 失败类型与降级

| 失败类型        | 降级处理                                                                                         |
| --------------- | ------------------------------------------------------------------------------------------------ |
| LLM 超时        | pending → active；`review_at = now + llm_failure_retry_days`；`reason = "llm_timeout"`           |
| LLM 返回非 JSON | pending → active；`review_at = now + llm_failure_retry_days`；`reason = "llm_invalid_output"`    |
| JSON 字段缺失   | pending → active；`review_at = now + llm_failure_retry_days`；`reason = "llm_invalid_output"`    |
| 校验失败        | pending → active；`review_at = now + llm_failure_retry_days`；`reason = "llm_governance_failed"` |
| Review 单条失败 | 该条 `UNCERTAIN`；`review_at = now + llm_failure_retry_days`；其余正常                           |

其中 `llm_failure_retry_days` 来自 `config/governance.yaml`，默认 30 天。  
`uncertain_retry_days` 同样可配置，默认 30 天。

统一原则：

> LLM 不可靠时，宁可稍后再 Review，也不错误覆盖。

### 7.3 重试策略

- LLM 超时、重试策略由 Manager Agent 的 Harness 决定。
- Alcyone 记忆系统只定义降级后的状态。
- 下一轮 cron 触发时会重新处理。

### 7.4 审计记录

每次 LLM 调用记录：

| 字段             | 说明               |
| ---------------- | ------------------ |
| `prompt_version` | Prompt 版本        |
| `input_hash`     | 输入哈希           |
| `output_raw`     | 原始输出，可选     |
| `validated`      | 是否通过校验       |
| `failure_reason` | 失败原因           |
| `retry_days`     | 实际使用的降级天数 |

LLM 模型信息由 Harness 记录，不进入 Alcyone Core 审计。

---

## 8. 完整调用示例

### 8.1 Pending 治理

```text
Harness cron 触发
  ↓
注入 pending_task.v1.txt
  ↓
Manager Agent 执行：
  GET /v1/manager/pending?limit=50
    ↓
  对 MEM-000231：
    收集候选 active Memory
      ↓
    调用 LLM（govern/system + govern/user）
      ↓
    LLM 返回：
      {
        "decision": "new",
        "target_memory_ids": [],
        "promotion": "no",
        "review_at": "2027-09-16T10:00:00+08:00",
        "reason": "新事实，与已有记忆维度不同。"
      }
      ↓
    校验通过
      ↓
    POST /v1/manager/govern
      ↓
    Core 执行：
      pending -> active
      review_at = 2027-09-16T10:00:00+08:00
      ↓
    写 Markdown / Git / SQLite
      ↓
    写审计日志
  ↓
Agent 汇总本轮结果
```

### 8.2 批量 Review

```text
Harness cron 触发
  ↓
注入 review_task.v1.txt
  ↓
Manager Agent 执行：
  GET /v1/manager/due-reviews?limit=50
    ↓
  汇总为用户可读问题
    ↓
  通过对话渠道向用户发送
    ↓
  用户回答
    ↓
  构造 review_items
    ↓
  调用 LLM（review/system + review/user）
    ↓
  LLM 返回结构化 results
    ↓
  校验通过
    ↓
  POST /v1/manager/review
    ↓
  Core 执行：
    VALID       -> active，review_at 更新
    CHANGED     -> 写入新 pending
    DEPRECATED  -> deprecated
    UNCERTAIN   -> active，review_at = now + uncertain_retry_days
    ↓
  写 Markdown / Git / SQLite
    ↓
  写审计日志
  ↓
Agent 汇总本轮结果
```

---

## 9. 契约冻结项

以下内容在本版本冻结，后续变更需走设计评审：

1. 两类 LLM 任务：Pending 治理判断、批量 Review 结构化。
2. Pending 治理输入输出 Schema。
3. 批量 Review 输入输出 Schema。
4. decision 集合：`new / duplicate / update / uncertain`。
5. promotion 集合：`yes / no / uncertain`。
6. result 集合：`VALID / CHANGED / DEPRECATED / UNCERTAIN`。
7. LLM 输出不包含 `confidence`。
8. LLM 不允许修改 content。
9. 校验失败统一降级为 `llm_failure_retry_days` 天后再 Review。
10. UNCERTAIN 统一降级为 `uncertain_retry_days` 天后再 Review。
11. 降级天数、默认 Review 周期通过 `config/governance.yaml` 配置。
12. Alcyone 记忆系统不配置 LLM，LLM 由 Manager Agent 的 Harness 管理。
13. Prompt 版本化管理，分为治理 Prompt 与定时任务 Prompt。
14. Manager 定时任务由 Harness 调度，Alcyone 记忆系统不实现调度。
15. 用户确认由 Manager Agent 通过对话渠道自然发起。
16. 审计日志记录 Prompt 版本、失败原因、降级天数。
17. LLM 调用发生在 Manager Agent 侧，Core 不直接调用 LLM。
