# CT05-B1 Worker 交付（Conversation）

**任务**：CM1 CT05-B1 Conversation 批次（SQL / JSON 编解码 / ReadOps+TxOps / Repository / 正式测试）
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db（Paseo lead 配置，只做方案与独立初验）
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-28

## 本次修改（2026-10-28，B1 复核修复 R1：严格 JSON 类型）

- 按 Architect B1 复核 R1 定点修复 `ContextJsonCodecs.decodeMessage`：`role` 与 `content` 都必须显式满足 `JsonPrimitive.isString`；数值、布尔、`null`、对象/数组、缺字段一律 `ContextPersistenceException(PERSISTENCE_UNAVAILABLE)`，完整异常链不回显库内原文、不带 cause，`CancellationException` 原样传播；正常字符串（含正文为 `"123"` / `"true"` 这类文本）、转义、中文、emoji、顺序保持。
- 先补 8 个正式反例/正向用例并确认 **2 红**（`content:123`、`content:true` 被接受），再改 codec 至绿。非法 JSON 注入统一走既有受控 `updateRaw`，并**断言注入行数 = 1 + 回读库内原文**，不以只读连接写入、也不把注入失败误当成 codec 拒绝；独立只读连接仍只用于核对正常落库内容。
- 重跑 B1Probe（逻辑与 Architect 版一致，仅临时目录指向本 Worker 证据目录并逐条捕获）：`123`、`true` 均被拒，字符串 `"123"` 仍可读。
- 更正两处措辞：(a) `sessionWindow` 拆两条 SQL 的原因限定为「当前 `ScopedJdbc` 的 nullable string 绑定（`setString(index, null)`）不支持」，不泛称 SQLDelight 或所有 NULL 绑定不支持；(b) B1 是 Kotlin SQL 常量 + SQLDelight 同步绑定/游标经受控 scope 执行，不称已验证「SQLDelight 生成业务查询」。
- 计数更新：模块 **128 例**（基线 91 + 37），源码 `@Test` 128 = XML `tests` 128，差额 0。证据 `.local/validation/cm1/worker/ct05-b1-fix/`。
- 只改 `ContextJsonCodecs.kt`、`ContextJsonTimeMappingTest.kt` 与本文件；未改 Schema/Core/API/CT04 保护层/其他生产文件，未 `git add/commit`，未自标 DONE。

## 本次修改（2026-10-28，CT05-B1 Conversation）

- 新增 `ContextSqlQueries.kt`（Conversation 参数化 SQL 常量）、`ContextJsonCodecs.kt`（`messages_json` 的 kotlinx.serialization 编解码与失败安全映射）、`ContextOps.kt`（Conversation ReadOps/TxOps + 行映射）、`ConversationRepositoryImpl.kt`（suspend Repository，每次 `executor.read {}` 短作用域）。
- `context/persistence-sqldelight/build.gradle.kts` 增加 `implementation(libs.serialization.json)`（版本来自既有 Version Catalog，未硬编码）。
- 新增 5 个测试文件共 37 例（真实文件 SQLite，复用 `TestDatabases` / `ContextDatabaseFactory`）：幂等/冲突/窗口锚点与排序/空闲判定/pending 计数/id 范围/maxTurnId、JSON 与 `+08:00` 毫秒文本落库往返、EXPLAIN QUERY PLAN、事务内条件更新行数。
- 未改 Schema 六表五索引与 `user_version`，未新增索引，未改公开 API / Core 接口 / CT04 保护层（四个受保护文件 SHA 与 CT04 rev5 证据完全一致），未做 B2/B3、CT06/CT07，未改 VFS/common/进度入口/AGENTS/Architect 复核文件，未 `git add/commit`。

## 1. 交付文件与落点

| 文件 | 内容 | 关键落点 |
| --- | --- | --- |
| `ContextSqlQueries.kt` | Conversation 全部 SQL 常量，参数化、单语句 | `SELECT_TURN_BY_ID`、`SELECT_TURN_BY_BUSINESS_KEY`、`SELECT_SESSION_WINDOW` / `SELECT_SESSION_WINDOW_LATEST`、`SELECT_LATEST_PENDING`、`COUNT_PENDING`、`EXISTS_PENDING`、`MAX_CREATED_AT`、`SELECT_IDLE_SESSIONS`、`SELECT_MAX_TURN_ID`、`SELECT_TURN_ID_RANGE`、`INSERT_TURN`、`SELECT_LAST_INSERT_ROW_ID`、`MARK_COMPLETE`、`MARK_FAILED` |
| `ContextJsonCodecs.kt` | `messages_json` 编解码 | `encodeMessages` / `decodeMessages`（数组、顺序、转义、中文；非法内容 → `PERSISTENCE_UNAVAILABLE`，不回显原文、不带 cause） |
| `ContextOps.kt` | Conversation ReadOps / TxOps 与行映射 | `ConversationTurnMapper`、`ConversationReadOpsImpl`、`ConversationTxOpsImpl.insertTurn/markComplete/markFailed`、共用 `ConversationTurnReader` |
| `ConversationRepositoryImpl.kt` | suspend Repository | 每个方法一次 `executor.read { scope -> ConversationReadOpsImpl(scope)... }`，不缓存 scope/Ops |
| `build.gradle.kts` | JSON 依赖 | `implementation(libs.serialization.json)`（仅实现依赖，不改变模块 API 面） |

文件名说明：方案原写 `ContextRepositories.kt`；B1 只有 1 个 Repository 类，ktlint `standard:filename` 要求文件名与唯一类名一致，因此本批为 `ConversationRepositoryImpl.kt`。B2 追加其余四个 Repository 时可放回多类的 `ContextRepositories.kt`。

## 2. SQL → Port 映射（全部 `?` 参数化，无值拼接）

| Port 方法 | SQL 常量 | 参数 |
| --- | --- | --- |
| `getById` | `SELECT_TURN_BY_ID` | id |
| `findByBusinessKey` | `SELECT_TURN_BY_BUSINESS_KEY` | runtime, agent, session, sessionTurnId |
| `sessionWindow`（有锚点） | `SELECT_SESSION_WINDOW` | runtime, agent, session, anchor(<), anchor(=), anchorId, limit |
| `sessionWindow`（无锚点） | `SELECT_SESSION_WINDOW_LATEST` | runtime, agent, session, limit |
| `latestPending` | `SELECT_LATEST_PENDING` | runtime, agent, session |
| `countPending` | `COUNT_PENDING` | runtime, agent, session |
| `idleCheck` 第一步 | `EXISTS_PENDING` | runtime, agent, session |
| `idleCheck` 第二步 | `MAX_CREATED_AT`（全状态） | runtime, agent, session |
| `findIdleSessions` | `SELECT_IDLE_SESSIONS`（`HAVING pending>0 AND MAX(created_at)<=?`，按业务键排序） | idleBefore |
| `maxTurnId` | `SELECT_MAX_TURN_ID` | — |
| `idRange` | `SELECT_TURN_ID_RANGE`（`id > ? AND id <= ?`，`ORDER BY id ASC`） | fromExclusive, toInclusive, limit |
| `insertTurn` | `SELECT_TURN_BY_BUSINESS_KEY` → `INSERT_TURN` → `SELECT_LAST_INSERT_ROW_ID` | 7 个绑定值 + 字面量 `'pending'` |
| `markComplete` / `markFailed` | `MARK_COMPLETE` / `MARK_FAILED`（`WHERE id=? AND extraction_status='pending'`） | id |

**与方案的唯一偏差（已实测原因）**：方案给 `sessionWindow` 单条 `(? IS NULL OR ...)`；当前 `ScopedJdbc` 的 **nullable string 绑定**（`setString(index, null)`）不支持——该受控白名单在把 null 转成 `String` 时抛 NPE（`SlotConnectionJdbcDriver.kt:262`），而 CT04 保护层在 B1 冻结。这是当前标量白名单对 nullable string 绑定的具体缺口，**不代表 SQLDelight 或 SQLite 不支持 NULL 参数，也不泛称所有 NULL 绑定不支持**。为不绕过 `ScopedJdbc`/`SqlBoundaryGuard`、也不把值拼进 SQL，改为「有锚点 / 无锚点」两条参数化 SQL，两条都使用 `idx_turn_session_time`（见第 5 节）。此偏差已由 Architect 在复核第 3 节接受。

## 3. 语义实现要点

- **幂等**：先按四元业务键查；不存在 → 插入 pending 并 `last_insert_rowid()` 取 id，返回 `CREATED`。
- **重放**：存在且「库内规范时间文本 + 有序消息列表」相同 → 返回原 id `DUPLICATE`，**不刷新 `created_at`**、不重置 `extraction_status`；时间比较用 `ContextDbTime.encode` 规范文本，因此亚毫秒 Instant 视为同一毫秒。
- **冲突**：存在但内容不同（含消息顺序不同、时间不同）→ `ContextIngestionException(TURN_CONFLICT)`，不被基础设施映射吞掉，事务整体回滚，原行不变。
- **空消息防御**：`insertTurn` 对空 `messages` 抛 `ContextIngestionException(INVALID_TURN)`，避免写入后 `StoredConversationTurn` 无法构造的坏行（公开 `ConversationTurn` 本就要求非空）。
- **行数契约**：`markComplete` / `markFailed` 返回真实 `executeUpdate` 行数（`1` 表示本次从 pending 迁移成功，非 pending / 不存在为 `0`）。
- **窗口**：底层 `timestamp DESC, id DESC LIMIT n`，Repository 反序返回真实会话顺序；锚点包含自身；`beforeId == null` 视为该时刻 id 不设上界。
- **空闲**：先要求存在 pending，再比较该会话**全状态** `MAX(created_at)` 与 `idleBefore`（解码为 Instant 比较，不用文本比较掩盖非规范时间）。
- **错误映射**：SQL 失败经 `ContextSqliteErrors.map`；JSON/时间/行映射失败 → `PERSISTENCE_UNAVAILABLE`，消息不含库内原文、`cause == null`；`CancellationException` 原样传播。
- **作用域**：Ops 只用传入的 `RawReadScope` / `RawTransactionScope`，事务内读写同一 scope/session/物理连接；Repository 不缓存 scope。

## 4. 正式测试与断言（37 例，真实文件 SQLite）

| 测试类 | 例数 | 关键断言 |
| --- | --- | --- |
| `ContextConversationRepositoryRealDbTest` | 15 | CREATED + 落库为 pending；空库全空结果/null；同键同内容 DUPLICATE 且 id 不变、`created_at` 不刷新；异消息/乱序/异时间 TURN_CONFLICT 且原行不变；窗口含锚点、`id <= anchorId` tie-break、limit 截断后反序、会话隔离；latestPending 忽略 complete 且同刻按 id；countPending 只数本会话 pending；idleCheck 需 pending 且全状态 MAX(created_at)；findIdleSessions 排除无 pending / 有更新 complete 的会话；maxTurnId 空表 null；idRange 下界排除/上界包含/limit；亚毫秒 Instant 归一化 |
| `ContextConversationTxOpsRealDbTest` | 4 | 事务内读到自己未提交的写入（同 scope）；`markComplete` 行数 `1,0,0,0` 且状态为 complete；`markFailed` 行数 `1,0,0`；DUPLICATE 重放不把 complete 重置回 pending |
| `ContextJsonTimeMappingTest` | 14 | 顺序/换行/引号/反斜杠/中文/emoji 编码后，**执行器关闭后用独立只读连接**核对 `messages_json` 原文与解析结果；`timestamp`/`created_at` 精确为 `2026-10-28T08:30:00.123+08:00` 与 `...08:31:00.456+08:00`；raw `\uXXXX` 可解码；缺字段 / 未知角色 / 空数组 / 非规范时间 → `PERSISTENCE_UNAVAILABLE`，消息不回显原文、无 cause；**R1 新增**：`content` 为数字/布尔/`null`/对象/数组、`role` 为数字/布尔/`null`、缺 `role` 均拒绝，异常链不含原文标记；正文为字符串 `"123"`/`"true"`/`"null"`/`"{}"`/`"[1]"` 仍正常读取 |
| `ContextQueryPlanTest` | 4 | 有/无锚点窗口均 `idx_turn_session_time`；latestPending 与 countPending 均 `idx_turn_pending_session_time`（只断言使用了期望索引，不硬编码完整计划文本） |

落库断言原则：JSON/时间用例在 `ContextDatabaseFactory.open(...).use {}` 结束后用 `TestDatabases.readOnly` 的独立只读连接读取磁盘真实文本，不以 Repository 返回值代替落库证据。

## 5. EXPLAIN QUERY PLAN 实测

`sqlite3` 3.51.0 + 与 `ContextSchema.sq` 完全一致的 DDL，SQL 文本与 `ContextSqlQueries` 常量逐字一致（日志 `.local/validation/cm1/worker/ct05-b1/query-plan.log`）：

| 查询 | 计划 |
| --- | --- |
| 有锚点窗口 | `SEARCH conversation_turn USING INDEX idx_turn_session_time (runtime_id=? AND agent_id=? AND session_id=?)` |
| 无锚点窗口 | `SEARCH conversation_turn USING INDEX idx_turn_session_time (...)` |
| latestPending | `SEARCH conversation_turn USING INDEX idx_turn_pending_session_time (...)` |
| countPending | `SEARCH conversation_turn USING COVERING INDEX idx_turn_pending_session_time (...)` |
| idle MAX(created_at) | `SEARCH conversation_turn USING COVERING INDEX idx_turn_session_created_at (...)` |
| findIdleSessions | `SCAN conversation_turn USING INDEX idx_turn_session_created_at` |

未新增索引；`ContextQueryPlanTest` 在真实库上对上述四条核心查询做了同样的「使用期望索引」断言并通过。

## 6. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `./scripts/dev gradle :context:persistence-sqldelight:compileKotlin --console=plain` | 0 | 生产批次（SQL/编解码/Ops/Repository）编译通过 |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks --console=plain` | 0 | **128 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 128 = XML `tests` 128，**差额 0**；日志 `.local/validation/cm1/worker/ct05-b1-fix/targeted-test.log` |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessApply --console=plain` | 0 | 已格式化，无残留；日志 `.local/validation/cm1/worker/ct05-b1-fix/spotless-apply.log` |
| `sqlite3` EXPLAIN QUERY PLAN | 0 | `.local/validation/cm1/worker/ct05-b1/query-plan.log`（初版证据，DDL 与 SQL 未变） |

计数来源：18 个 XML 报告合计 128；新增 37 例 = 15 + 4 + 14 + 4；此前模块基线 91 例（CT04 rev5），合计 128。R1 修复前同一套用例实跑为 128 例 / 2 失败（`probe-before.log`），修复后全绿。初版证据在 `.local/validation/cm1/worker/ct05-b1/`，R1 修复证据在 `.local/validation/cm1/worker/ct05-b1-fix/`（`probe-before.log`、`targeted-test.log`、`spotless-apply.log`、`b1probe-after.log`、`classpath.txt`、`final-source-hashes.json`、`verification.json`）。

## 7. 改动文件与最终 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `.../ContextSqlQueries.kt` | `6cb83f64c59361b61d11382ee8d834696b34ff0f49efbbf467b632acb5305839` |
| `.../ContextJsonCodecs.kt` | `2c56ec953fb8d258bb53a59dd17e27b2363f6a46524b8d9cedbe9b8cc00edab6` |
| `.../ContextOps.kt` | `de4e2e6999a9e4666f701c0688e0834103f72fa68ec87ce3983f075ec19704d7` |
| `.../ConversationRepositoryImpl.kt` | `854efd77022f85bc7456e300cd0e0e5dd417843e8a85d8250d132a63622ed769` |
| `.../ConversationFixtures.kt` | `9fe95194298e295ceae5681fd28977d6f72f20fbf1328bb13300b44d0e510cbf` |
| `.../ContextConversationRepositoryRealDbTest.kt` | `b7323d7b8e1e9b6266bb7878f6a1414f19c9436f03b7bc4c4e10a1b52a71b317` |
| `.../ContextConversationTxOpsRealDbTest.kt` | `0ccaa581b5b806df00dd8c4c776e2c009b442b7c0482c6c5e7fdfad7440b9930` |
| `.../ContextJsonTimeMappingTest.kt` | `cc115309d765c19f4ef6e88d662abb9108a9b93f17b0b1331ea60543918b9d7f` |
| `.../ContextQueryPlanTest.kt` | `05b55e131fc7c7aac553470c55148159a0c8cd9f6ee33a1d4bad28154030ee9c` |
| `context/persistence-sqldelight/build.gradle.kts` | `d5f61c66cbd9a96649b0df1cce57bdb7892505f9741828075fffdfe406124af9` |

CT04 受保护文件未改动（与 CT04 rev5 证据逐字一致）：`ContextDatabaseExecutor.kt` `8cd7a1a9…`、`ContextSlot.kt` `185ecbf9…`、`SlotConnectionJdbcDriver.kt` `79fc3ac1…`、`RawScopes.kt` `7949dfed…`。完整清单见 `final-source-hashes.json`。

## 8. 未做项与边界

- **未做 B2**（Raw / Organized / Pending / Checkpoint 的 SQL、Ops、Repository）与 **B3**（`ContextDatabaseImpl`、ReadContext/TransactionContext 装配、集成与全工程回归）。
- 未改 Schema 六表五索引与 `user_version`；未新增索引；未改公开 API / Core 接口 / CT04 `ScopedJdbc` / `SqlBoundaryGuard` / 关闭协议；未裸透 `Connection` / `Driver`。
- 未改 `integration-tests/context`，未做 CT06 Runtime / CT07 Ingestion/HTTP；未改 VFS / common / 进度入口 / AGENTS / Architect 复核文件；未 `git add/commit`。
- 第 2 节的 `sessionWindow` 双 SQL 偏差已由 Architect 复核第 3 节接受；措辞已按复核意见限定为「当前 nullable string 绑定不支持」。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 独立初验与 Architect 独立复核。

## 9. R1 修复记录（2026-10-28，严格 JSON 类型）

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 先红 | `:context:persistence-sqldelight:test --rerun-tasks`（未改 codec） | 128 例 **2 失败**：`non-string numeric content is rejected`、`non-string boolean content is rejected`，均为 `assertFailsWith` 未捕获异常（`123` / `true` 被当成字符串接受）；注入行数与回读断言先通过 |
| 后绿 | 同上（codec 修复后） | 128 例 0 失败 / 0 错误 / 0 跳过 |

修复点：`ContextJsonCodecs.stringField` 先判 `message[name] as? JsonPrimitive`，再判 `primitive.isString`，任一不满足即 `PERSISTENCE_UNAVAILABLE`；`contentOrNull` 不再参与解码（它会把数字/布尔转成字符串）。

B1Probe 重跑（`.local/validation/cm1/worker/ct05-b1-fix/b1probe-after.log`，javac/java 均退出 0）：

```text
inject content=123 update rows=1
REJECTED non-string JSON 123 -> ContextPersistenceException [PERSISTENCE_UNAVAILABLE] conversation messages_json is not a valid ChatMessage array
inject content=true update rows=1
REJECTED non-string JSON true -> ContextPersistenceException [PERSISTENCE_UNAVAILABLE] conversation messages_json is not a valid ChatMessage array
string content "123" accepted=123 update rows=1
```

注入策略：非法 JSON 用例一律经既有受控 `updateRaw` 写入，并先断言影响行数 = 1、再经受控读取回读库内原文，确保失败来自 codec 严格类型判定而不是注入失败；独立只读连接仅用于正常落库内容核对。未改 Schema / Core / API / CT04 保护层与其他生产文件，未 `git add/commit`，未自标 DONE。
