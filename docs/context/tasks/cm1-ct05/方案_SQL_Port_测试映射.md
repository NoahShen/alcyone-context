# CT05 方案：SQL / Port / 测试映射

**日期**：2026-10-28
**依据**：[架构任务书](架构任务书.md)、三份 Context 设计、CT01 实施契约、CT03 类型契约、CT04 已验收执行器（`ContextDatabaseExecutor` / `ContextSlot` / `ScopedSlotDriver` / `SqlBoundaryGuard`）
**状态**：Lead 方案，待按批委派唯一 Paseo Worker；生产仅 Worker 改，Lead 独立初验后逐批提交 Architect。

## 本次修改（2026-10-28，CT05 方案建立）

- CT04 已 APPROVED/DONE。本方案把五个 Repository Port、ReadOps/TxOps 与 `ContextDatabase` 适配映射到具体 SQL、JSON/时间映射与正式测试，分三批交付，每批 Lead 初验后立即提交 Architect。

## 1. 交付物与文件布局

| 位置 | 内容 |
| --- | --- |
| `context/persistence-sqldelight/src/main/.../` | 新增 `ContextSqlQueries.kt`（全部 SQL 常量，参数化）、`ContextJsonCodecs.kt`（messages/sources/domains 序列化）、`ContextRepositories.kt`（五个 Repository 的 suspend 实现，每次经 `executor.read {}` 短作用域）、`ContextOps.kt`（五组 ReadOps/TxOps，绑定传入 scope）、`ContextDatabaseImpl.kt`（`ContextDatabase`：`read`/`write` + ReadContext/TransactionContext 装配） |
| `context/persistence-sqldelight/src/test/.../` | CT05 单元与真实库测试（真实文件 SQLite，复用 `TestDatabases` / `ContextDatabaseFactory`） |
| `integration-tests/context/src/test/.../` | 跨模块集成：真实 Runtime 组装 + ContextDatabase 端到端（不新增 Runtime 业务功能，仅接线验证） |

不改 Schema（六表五索引与 `user_version=1` 不动）、不改公开 API/Core 接口、不新增索引、不改 VFS。

## 2. Port → SQL 映射（全部参数化，经受控 scope）

### 2.1 Conversation

| Port 方法 | SQL |
| --- | --- |
| `getById(id)` | `SELECT * FROM conversation_turn WHERE id = ?` |
| `findByBusinessKey(rt,ag,se,turn)` | `SELECT * FROM conversation_turn WHERE runtime_id=? AND agent_id=? AND session_id=? AND session_turn_id=?` |
| `sessionWindow(...)` | `SELECT * FROM conversation_turn WHERE runtime_id=? AND agent_id=? AND session_id=? AND (? IS NULL OR timestamp < ? OR (timestamp = ? AND id <= ?)) ORDER BY timestamp DESC, id DESC LIMIT ?`，结果反序返回 |
| `latestPending(...)` | `SELECT * FROM conversation_turn WHERE runtime_id=? AND agent_id=? AND session_id=? AND extraction_status='pending' ORDER BY timestamp DESC, id DESC LIMIT 1` |
| `countPending(...)` | `SELECT COUNT(*) FROM conversation_turn WHERE runtime_id=? AND agent_id=? AND session_id=? AND extraction_status='pending'` |
| `idleCheck(...)` | 存在性：`SELECT EXISTS(SELECT 1 FROM conversation_turn WHERE runtime_id=? AND agent_id=? AND session_id=? AND extraction_status='pending')`；且 `SELECT MAX(created_at) FROM conversation_turn WHERE runtime_id=? AND agent_id=? AND session_id=?`（全状态）`<= idleBefore` |
| `findIdleSessions(idleBefore)` | `SELECT runtime_id, agent_id, session_id, MAX(created_at) AS m FROM conversation_turn GROUP BY runtime_id, agent_id, session_id HAVING SUM(CASE WHEN extraction_status='pending' THEN 1 ELSE 0 END) > 0 AND m <= ?` |
| `maxTurnId()` | `SELECT MAX(id) FROM conversation_turn`（空表 null） |
| `idRange(fromEx, toInc, limit)` | `SELECT * FROM conversation_turn WHERE id > ? AND id <= ? ORDER BY id ASC LIMIT ?` |
| `insertTurn(...)` | 事务内：先按四元键查；不存在 → `INSERT INTO conversation_turn(runtime_id,agent_id,session_id,session_turn_id,timestamp,created_at,messages_json,extraction_status) VALUES(?,?,?,?,?,?,?, 'pending')`，`last_insert_rowid()` 取 id，返回 `CREATED`；存在且规范化内容相同 → 返回原 id `DUPLICATE`（不更新 created_at）；存在但内容不同 → 抛 `ContextIngestionException(TURN_CONFLICT)` |
| `markComplete(id)` | `UPDATE conversation_turn SET extraction_status='complete' WHERE id=? AND extraction_status='pending'` |
| `markFailed(id)` | `UPDATE conversation_turn SET extraction_status='failed' WHERE id=? AND extraction_status='pending'` |

### 2.2 Raw Memory（不可变）

| 方法 | SQL |
| --- | --- |
| `findById` | `SELECT * FROM raw_memory WHERE id=?` |
| `findByIds` | `SELECT * FROM raw_memory WHERE id IN (参数化占位符)` |
| `scanPage(offset,limit)` | `SELECT * FROM raw_memory ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?` |
| `insertBatch` | `INSERT INTO raw_memory(id,content,generated_by,sources_json,created_at) VALUES(?,?,?,?,?)` 逐条；返回累计影响行数 |

### 2.3 Organized Memory 与来源

| 方法 | SQL |
| --- | --- |
| `findById` / `findByIds` / `scanPage` | 同 Raw 形态（`organized_memory`，`ORDER BY created_at ASC, id ASC`） |
| `findSourcesByOrganized` | `SELECT raw_memory_id FROM organized_memory_source WHERE organized_memory_id=? ORDER BY raw_memory_id ASC` |
| `findOrganizedByRaw` | `SELECT organized_memory_id FROM organized_memory_source WHERE raw_memory_id=? ORDER BY organized_memory_id ASC` |
| `maxUpdatedAt` | `SELECT MAX(updated_at) FROM organized_memory` |
| `insertNew` | `INSERT INTO organized_memory(id,content,domains_json,created_at,updated_at) VALUES(?,?,?,?,?)` |
| `update` | `UPDATE organized_memory SET content=?, domains_json=?, updated_at=? WHERE id=?` |
| `insertSourceLink` | `INSERT INTO organized_memory_source(organized_memory_id,raw_memory_id) VALUES(?,?)` |
| `advanceUpdatedAt(organizedId,next)` | `UPDATE organized_memory SET updated_at=? WHERE id=?`，返回影响行数（调用方先 `maxUpdatedAt` 分配严格更大毫秒值） |

### 2.4 Organization Pending

| 方法 | SQL |
| --- | --- |
| `exists(rawId)` | `SELECT EXISTS(SELECT 1 FROM organization_pending WHERE raw_memory_id=?)` |
| `hasAnyPending()` | `SELECT EXISTS(SELECT 1 FROM organization_pending)` |
| `nextByRawCreatedAt()` | `SELECT p.raw_memory_id FROM organization_pending p JOIN raw_memory r ON r.id=p.raw_memory_id ORDER BY r.created_at ASC, r.id ASC LIMIT 1` |
| `enqueue` / `dequeue` | `INSERT OR IGNORE INTO organization_pending(raw_memory_id) VALUES(?)` / `DELETE FROM organization_pending WHERE raw_memory_id=?`，返回影响行数 |

### 2.5 Dreaming Checkpoint

| 方法 | SQL |
| --- | --- |
| `readCheckpoint` | `SELECT last_processed_turn_id FROM dreaming_checkpoint WHERE id=1` |
| `casUpdateCheckpoint(expected,next)` | `UPDATE dreaming_checkpoint SET last_processed_turn_id=? WHERE id=1 AND last_processed_turn_id=? AND ?>last_processed_turn_id`，返回影响行数（0 表示 CAS 失败） |

## 3. JSON / 时间 / 错误映射

- `messages_json` / `sources_json` / `domains_json` 用 kotlinx.serialization 编解码，写入前校验为 JSON array；读取时严格反序列化，失败映射为 `PERSISTENCE_UNAVAILABLE`（不泄露原文）。
- 所有 `Instant` 与库内 TEXT 之间统一用 `common` 的 `TimeCodec.format/parse`（固定 `+08:00` 毫秒），禁止自造格式或用 `CURRENT_TIMESTAMP`。
- 错误统一经 `ContextSqliteErrors.map` 安全映射；`TURN_CONFLICT` 等业务错误用具名 `ContextIngestionException`，不被基础设施映射吞掉；`CancellationException` 原样传播。

## 4. Ops / Repository 装配（复用 CT04 保护）

- `ContextDatabaseImpl.read(block)` → `executor.read { scope -> block(ReadContextImpl(ConversationReadOpsImpl(scope), ...)) }`；`write(block)` → `executor.write { scope -> block(TransactionContextImpl(...)) }`。
- TxOps 与 ReadOps 共享同一 `RawTransactionScope`/session/物理连接；禁止在 Ops 内调用 `database.read/write`（防嵌套），禁止裸 `Connection`/`Driver` 透出，禁止跨线程或 scope 失效后继续使用（CT04 `ScopedJdbc` 每次操作校验）。
- 每个 suspend Repository 方法 = 一次 `executor.read {}` 短作用域，不缓存 scope；事务内一律走传入 scope 的 Ops。
- 行数契约：`markComplete/markFailed/enqueue/dequeue/insertSourceLink/advanceUpdatedAt/casUpdateCheckpoint/insertNew/update` 返回真实 `executeUpdate` 行数，不用返回值伪造落库结果。

## 5. 测试映射（对应架构任务书验收 1～4）

| 验收 | 测试文件（建议） | 关键断言 |
| --- | --- | --- |
| 1 真实库全 Port 正常/空/边界/约束，JSON/时间往返，落库断言 | `ContextRepositoriesRealDbTest.kt`、`ContextJsonTimeMappingTest.kt` | 每个 Port 正常+空结果+边界（分页端点、id 边界、空表 MAX null）；JSON 顺序/转义/中文往返；时间文本 `+08:00` 精确往返；断言直接查询独立只读连接而非仅看返回值 |
| 2 真实 ContextDatabase 原子提交/回滚、读快照、同 scope 多 Repository、重复/并发业务键、CAS 竞争、影响行数、来源正反查、全局水位、取消与异常链 | `ContextDatabaseTransactionTest.kt`、`ContextConcurrencyRealDbTest.kt` | 提交后独立连接可见；抛错后不可见；写事务内多 Repository 同连接；同键同内容 DUPLICATE 且 createdAt 不变；同键异内容 TURN_CONFLICT；CAS 0 行且值不变；影响行数逐项核对；来源正/反查集合一致；`advanceUpdatedAt` 严格递增；取消原样传播且回滚 |
| 3 Ops/查询 scope 后与跨线程不可用、正常 prepared 连续调用、不破坏关闭/资源所有权 | `ContextOpsScopeBoundaryTest.kt` | scope 返回后调用 Ops 抛 `ESCAPED_SCOPE`；跨线程抛 `CROSS_THREAD`；同 scope 连续 prepared 查询/绑定正常；关闭后请求拒绝；真实连接在 close 后全部关闭 |
| 4 EXPLAIN QUERY PLAN 证据 | `ContextQueryPlanTest.kt` | 会话窗口用 `idx_turn_session_time`、pending 用 `idx_turn_pending_session_time`、`maxUpdatedAt` 用 `idx_organized_updated_at`、来源反查用 `idx_source_raw`；不硬编码完整计划文本，仅断言使用了期望索引 |

补充：`integration-tests/context` 增加端到端接线测试（初始化→ContextDatabase→写入→重开→读取一致），沿用 CT04 生命周期与关闭协议。

## 6. 分三批推进（每批 Lead 初验后立即提交 Architect）

1. **B1 Conversation**：`ContextSqlQueries`（会话部分）+ `ContextJsonCodecs`（messages）+ Conversation ReadOps/TxOps + Repository + 对应测试（幂等/冲突/窗口/空闲/计数/id 边界/EXPLAIN）。Architect 复核通过后再 B2。
2. **B2 Raw / Organized / Pending / Checkpoint**：其余 SQL、Ops/Repository、来源正反查、全局水位、CAS、入库幂等、约束与影响行数测试。
3. **B3 ContextDatabase 装配与集成**：`ContextDatabaseImpl`、ReadContext/TransactionContext、原子提交/回滚/快照/取消、scope 边界、`integration-tests/context` 端到端与 EXPLAIN 汇总；最终模块+全工程回归、计数与 SHA 自洽。

## 7. 约束与禁止

- 不改 Schema、公开 API、Core 接口；不新增索引（除非 EXPLAIN 证明必需，先报 Architect）；不新增连接池；不裸透 `Connection`/`Driver`；不绕 CT04 `ScopedJdbc`/`SqlBoundaryGuard`/关闭协议。
- 不做 CT06 Runtime 业务接线、CT07 Ingestion/HTTP；不改 VFS/common；不 git add/commit。
- 证据放 `.local/validation/cm1/worker/ct05-*/` 与任务目录文档；Lead 初验只读核验与实跑测试，不改生产。
