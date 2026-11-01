# CT05-B2 Worker 交付（Raw / Organized / Pending / Checkpoint）

**任务**：CM1 CT05-B2：Raw Memory / Organized Memory（含来源关联）/ Organization Pending / Dreaming Checkpoint
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db（Paseo lead 配置，只做方案与独立初验）
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-28

## 本次修改（2026-10-28，CT05-B2 Raw/Organized/Pending/Checkpoint）

- 扩展 `ContextSqlQueries.kt`（§2.2～§2.5 全部参数化单语句）、`ContextJsonCodecs.kt`（`sources_json` / `domains_json` 严格编解码）、`ContextOps.kt`（四组 ReadOps/TxOps + 行映射）、新增 `ContextRepositories.kt`（四个 suspend Repository，每个方法一次 `executor.read {}`）。
- 新增 6 个测试文件共 27 例（真实文件 SQLite）：Raw 6、Organized 8、Pending 3、Checkpoint 3、sources/domains 严格类型 4、EXPLAIN 3（并入既有 `ContextQueryPlanTest`）。
- 未改 Schema/索引/`user_version`/API/Core 接口/CT04 保护层/B1 已验收行为；未改 VFS/common/进度入口/AGENTS/Architect 文件；未做 B3/CT06/CT07；未 `git add/commit`。
- 说明：B2 生产代码先落盘、测试后补，因此**没有红阶段**（Lead 任务书中「若实现未写」才要求先红）；`probe-before.log` 是测试首跑即全绿的记录，最终验收见 `targeted-test.log`。

## 1. 交付文件与落点

| 文件 | 内容 | 关键落点（行号按最终源码） |
| --- | --- | --- |
| `ContextSqlQueries.kt` | B2 全部 SQL 常量（参数化、单语句） | `SELECT_RAW_BY_ID:110`、`selectRawByIds:113`、`SELECT_RAW_PAGE:116`、`INSERT_RAW:119`、`SELECT_ORGANIZED_BY_ID:124`、`selectOrganizedByIds:126`、`SELECT_ORGANIZED_PAGE:128`、`SELECT_SOURCES_BY_ORGANIZED:132`、`SELECT_ORGANIZED_BY_RAW:136`、`SELECT_MAX_UPDATED_AT:140`、`INSERT_ORGANIZED:142`、`UPDATE_ORGANIZED:145`、`INSERT_SOURCE_LINK:147`、`ADVANCE_UPDATED_AT:150`、`EXISTS_PENDING_RAW:152`、`HAS_ANY_PENDING:154`、`SELECT_NEXT_PENDING_BY_RAW_CREATED_AT:157`、`ENQUEUE_PENDING:162`、`DEQUEUE_PENDING:164`、`SELECT_CHECKPOINT:166`、`CAS_UPDATE_CHECKPOINT:169` |
| `ContextJsonCodecs.kt` | sources / domains 严格编解码 | `encodeDomains:82`、`decodeDomains:85`、`encodeSources:93`、`decodeSources:122`、统一 `decodeArray` 与 `stringField`（`isString` 严格判定） |
| `ContextOps.kt` | 四组 Ops 与映射 | `RawMemoryMapper:465`、`RawMemoryReadOpsImpl:491`、`RawMemoryTxOpsImpl:507`、`RawMemoryReader:539`、`OrganizedMemoryMapper:584`、`OrganizedMemoryReadOpsImpl:610`、`OrganizedMemoryTxOpsImpl:632`、`OrganizedMemoryReader:697`、`OrganizationPendingReadOpsImpl:774`、`OrganizationPendingTxOpsImpl:787`、`DreamingCheckpointReadOpsImpl:828`、`DreamingCheckpointTxOpsImpl:837`、`decodeStoredInstant:873` |
| `ContextRepositories.kt` | 四个 suspend Repository | `RawMemoryRepositoryImpl:20`、`OrganizedMemoryRepositoryImpl:35`、`OrganizationPendingRepositoryImpl:58`、`DreamingCheckpointRepositoryImpl:70` |

`ContextOps.kt` 中 B1 的 `ConversationQuery` 仅补充 KDoc 说明其为 Ops 层共享查询入口，**B1 行为与测试未改**；`ConversationRepositoryImpl.kt` 未改动（SHA 与 B1 交付一致）。

## 2. JSON 存储编码（持久化层内部契约）

B1 已固定 `messages_json` 为 `[{"role","content"}]`。B2 的 `sources_json` / `domains_json` 采用同一风格（snake_case，与库列命名一致）：

```json
domains_json: ["domain-a","医疗","emoji 🚀"]
sources_json: [
  {"type":"conversation_turn","runtime_id":"r","agent_id":"a","session_id":"s","session_turn_id":"t"},
  {"type":"raw_memory","memory_id":"m-1"}
]
```

严格规则：必须是 JSON 数组；`domains` 元素必须是 JSON 字符串；`sources` 元素必须是对象，`type` 必须是 `conversation_turn` / `raw_memory` 之一且为字符串，该变体的所有必需字段必须存在且为 JSON 字符串。数值/布尔/`null`/对象/数组/缺字段/未知 `type` 一律 `PERSISTENCE_UNAVAILABLE`，异常链不含原文、无 cause。

> 该编码是持久化层内部约定，公开契约仍是 `MemorySourceRef` sealed 类型；字段名（snake_case）与 `type` 取值如需调整，请在进入 CM2 前定稿，避免已落库数据迁移。

## 3. SQL → Port 映射

| Port | SQL | 说明 |
| --- | --- | --- |
| `RawMemory.findById` | `SELECT_RAW_BY_ID` | id 绑定 |
| `RawMemory.findByIds` | `selectRawByIds(n)` | `IN (?, …)` 按集合大小生成占位符；**空集合直接返回空列表，不执行 SQL** |
| `RawMemory.scanPage` | `SELECT_RAW_PAGE` | `ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?`（先 limit 后 offset 绑定） |
| `RawMemory.insertBatch` | `INSERT_RAW` | 逐条插入，累计真实行数 |
| `OrganizedMemory.findById(s)` | `SELECT_ORGANIZED_BY_ID` / `selectOrganizedByIds(n)` | 行映射后用来源正查补齐 `rawMemoryIds` |
| `OrganizedMemory.scanPage` | `SELECT_ORGANIZED_PAGE` | 同上补齐 `rawMemoryIds` |
| `findSourcesByOrganized` | `SELECT_SOURCES_BY_ORGANIZED` | `ORDER BY raw_memory_id ASC` |
| `findOrganizedByRaw` | `SELECT_ORGANIZED_BY_RAW` | `ORDER BY organized_memory_id ASC` |
| `maxUpdatedAt` | `SELECT_MAX_UPDATED_AT` | 空表 null，非空严格 `+08:00` 解析 |
| `insertNew` | `INSERT_ORGANIZED` | 只写行；`rawMemoryIds` 由 `insertSourceLink` 单独写入 |
| `update` | `UPDATE_ORGANIZED` | 只更新 content / domains / updated_at |
| `insertSourceLink` | `INSERT_SOURCE_LINK` | 重复关联由主键冲突安全失败 |
| `advanceUpdatedAt` | `ADVANCE_UPDATED_AT` | 返回真实行数；递增分配由调用方在同一写事务内完成 |
| `Pending.exists` / `hasAnyPending` | `EXISTS_PENDING_RAW` / `HAS_ANY_PENDING` | `SELECT EXISTS(...)` |
| `Pending.nextByRawCreatedAt` | `SELECT_NEXT_PENDING_BY_RAW_CREATED_AT` | `raw_memory.created_at ASC, id ASC LIMIT 1` |
| `enqueue` / `dequeue` | `ENQUEUE_PENDING` / `DEQUEUE_PENDING` | `INSERT OR IGNORE` / `DELETE`，返回真实行数 |
| `readCheckpoint` | `SELECT_CHECKPOINT` | 固定 `id = 1`；行缺失或非数字 → `PERSISTENCE_UNAVAILABLE` |
| `casUpdateCheckpoint` | `CAS_UPDATE_CHECKPOINT` | `expected` 匹配且 `next > 当前` 才更新，返回真实行数 |

## 4. 语义实现要点

- **Raw 不可变**：只有 `insertBatch`；没有 update/delete 业务方法。`generated_by` 严格按 `MemoryOrigin` 名字编码/解析。
- **来源关联**：`OrganizedMemory` 读取时 `rawMemoryIds` 一律来自 `organized_memory_source`（正查补齐），不信任调用方输入；`insertNew`/`update` 不写关联（与 Port 与方案一致）。
- **行数契约**：`insertBatch` 累计每条 `executeUpdate`；`insertNew`/`update`/`insertSourceLink`/`advanceUpdatedAt`/`enqueue`/`dequeue`/`casUpdateCheckpoint` 返回真实行数，不伪造成功。
- **水位**：`advanceUpdatedAt` 只写值；严格递增分配由调用方在同一写事务内 `maxUpdatedAt → next = max(now, max+1ms)` 完成（测试覆盖同事务两条严格递增）。
- **错误**：SQL 失败经 CT04 `ContextSqliteErrors.map`；JSON/时间/行映射失败 → `PERSISTENCE_UNAVAILABLE`，消息不含库内原文、无 cause；`CancellationException` 原样传播。
- **作用域**：所有 Ops 只用传入 scope，不自开 `read/write`、不裸连接；Repository 每次调用一个短读作用域，不缓存。

## 5. 正式测试与断言（27 例）

| 测试类 | 例数 | 关键断言 |
| --- | --- | --- |
| `ContextRawMemoryRealDbTest` | 6 | `insertBatch` 返回 2 且两种来源变体往返相等；关闭执行器后用**独立只读连接**核对 `sources_json` 规范文本、`generated_by`、`+08:00` 毫秒；`findByIds` 空集合/不存在/重复；`scanPage` 按 `created_at,id` 排序与 offset/limit 边界；重复 id 约束失败且原行不变；写事务抛错回滚、提交行重开后可见（只读连接只看到 `raw-commit`） |
| `ContextOrganizedMemoryRealDbTest` | 8 | `insertNew`/`update` 行数与字段落库；`findByIds`/`scanPage` 分页；`maxUpdatedAt` 空表 null 与最大值；`insertSourceLink` 行数、重复关联冲突、正反查集合一致、`findById` 补齐 `rawMemoryIds`；FK RESTRICT：有来源关联/ pending 时删 raw、有来源时删 organized 均失败且行仍在，解除后同样 DELETE 成功；`advanceUpdatedAt` 行数与水位可见；同一写事务两条严格递增（+1ms、+2ms）并均可见；`domains_json` 规范文本 |
| `ContextOrganizationPendingRealDbTest` | 3 | enqueue 幂等（1→0）、dequeue 行数（1→0）、exists/hasAnyPending；`nextByRawCreatedAt` 不受入队顺序影响，按 raw `created_at,id`；enqueue 不存在的 raw 外键失败且不入队 |
| `ContextDreamingCheckpointRealDbTest` | 3 | 初始 0；CAS expected 匹配且向前 = 1、expected 不匹配 = 0、`next == 当前`/`next < 当前` = 0 且值不变；事务回滚后仍为 0，提交后为 3 |
| `ContextSourcesDomainsJsonTest` | 4 | sources 元素为数字/布尔/`null`/字符串/未知 type/缺字段/非字符串字段/缺 type/缺 `session_turn_id` 共 16 组 → 拒绝；domains 非字符串共 8 组 → 拒绝；失败均为 `PERSISTENCE_UNAVAILABLE`、无 cause、异常链不含原文标记；合法 sources/domains（含 `"123"`/`"true"`/中文/emoji）顺序保持；空数组合法 |
| `ContextQueryPlanTest`（新增 3） | 7 | `maxUpdatedAt` 用 `idx_organized_updated_at`；`findOrganizedByRaw` 用 `idx_source_raw`；`findSourcesByOrganized` 用 `sqlite_autoindex_organized_memory_source_1`（仅断言使用期望索引） |

非法 JSON 注入策略：全部经受控 `updateRaw` 写入，先断言影响行数 = 1、再回读库内原文，才断言读取失败；独立只读连接只用于正常落库内容核对。

## 6. EXPLAIN QUERY PLAN 实测

`sqlite3` 3.51.0 + 与 `ContextSchema.sq` 一致 DDL，SQL 与常量逐字一致（`query-plan.log`）：

| 查询 | 计划 |
| --- | --- |
| maxUpdatedAt | `SEARCH organized_memory USING COVERING INDEX idx_organized_updated_at` |
| findOrganizedByRaw | `SEARCH organized_memory_source USING INDEX idx_source_raw (raw_memory_id=?)` + TEMP B-TREE（排序） |
| findSourcesByOrganized | `SEARCH organized_memory_source USING COVERING INDEX sqlite_autoindex_organized_memory_source_1 (organized_memory_id=?)` |
| nextByRawCreatedAt | `SCAN r USING INDEX sqlite_autoindex_raw_memory_1` + `SEARCH p USING COVERING INDEX sqlite_autoindex_organization_pending_1` + TEMP B-TREE |
| raw / organized scanPage | 表扫描 + TEMP B-TREE（`created_at,id` 无现成索引；未新增索引） |
| checkpoint read | `SEARCH dreaming_checkpoint USING INTEGER PRIMARY KEY (rowid=?)` |

## 7. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessApply :context:persistence-sqldelight:compileKotlin --console=plain` | 0 | 生产批次编译通过 |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessApply :context:persistence-sqldelight:test --rerun-tasks --console=plain` | 0 | 测试首跑即全绿（无红阶段）；`probe-before.log` |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks --console=plain` | 0 | **155 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 155 = XML `tests` 155，**差额 0**；`targeted-test.log` |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessApply --console=plain` | 0 | 已格式化；`spotless-apply.log` |
| `sqlite3` EXPLAIN QUERY PLAN | 0 | `query-plan.log` |

计数来源：23 个 XML 报告合计 155；B2 新增 27 例 = 6 + 8 + 3 + 3 + 4 + 3（EXPLAIN 并入既有类）；B2 前模块 128 例，合计 155。证据目录 `.local/validation/cm1/worker/ct05-b2/`：`probe-before.log`、`targeted-test.log`、`spotless-apply.log`、`query-plan.log`、`final-source-hashes.json`、`verification.json`。

## 8. 改动文件与最终 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `.../ContextSqlQueries.kt` | `ab3ade1b77f7a272e5e4004805dbf4e3384c1f93440c66aac2442a860513b038` |
| `.../ContextJsonCodecs.kt` | `9a372082dac4dbfbbc168da7efa65a08d1112f8db10d0b5c8c0073601380507c` |
| `.../ContextOps.kt` | `5fd723efb6a360b456744972be7a4cf0a6059f1621259bd479668dc380a0bd5e` |
| `.../ContextRepositories.kt` | `b8f395eeea4f327228aba1503acb3bdaf09dabe3acb2205b16137b2119c16135` |
| `.../B2Fixtures.kt` | `b9246932fa9746300fad807b261800fbf6ede9e02526ebdcaa1ef786952986eb` |
| `.../ContextRawMemoryRealDbTest.kt` | `156db02577849bba88025c7911209080aa92182198952093fa401166cd435c64` |
| `.../ContextOrganizedMemoryRealDbTest.kt` | `72bf0aaa022a592570d15f788b77fb028fe2e24e7fe444e5f7b803f3a5a3103c` |
| `.../ContextOrganizationPendingRealDbTest.kt` | `12502949e6a81109199261822b50d269e451c6c21c481d822dbb33a77313ef47` |
| `.../ContextDreamingCheckpointRealDbTest.kt` | `58c45b71ec627925bf1b68fe478461df37c2380ed0a08c3e24e28ad87bf90285` |
| `.../ContextSourcesDomainsJsonTest.kt` | `00d2244336c112081a3038af119c154b6440a6c312df63b406fcc0cfacabcb2f` |
| `.../ContextQueryPlanTest.kt` | `9acad80e598334af49acee6bf559a11ab6802977cac288194764f12e696cc3bc` |

未改动：`ConversationRepositoryImpl.kt`（`854efd77…`，与 B1 交付一致）、CT04 四个保护层（`8cd7a1a9…` / `185ecbf9…` / `79fc3ac1…` / `7949dfed…`）、`build.gradle.kts`（`d5f61c66…`）。完整清单见 `final-source-hashes.json`。

## 9. 未做项与边界

- **未做 B3**：`ContextDatabaseImpl`、ReadContext/TransactionContext 装配、真实 ContextDatabase 的原子提交/快照/多 Repository 共享事务综合验收；`integration-tests/context` 未改。
- 未做 CT06 Runtime / CT07 Ingestion/HTTP；未改 Schema/索引/`user_version`/API/Core 接口/CT04 保护层/VFS/common/进度入口/AGENTS/Architect 文件；未 `git add/commit`。
- `insertNew`/`update` 不写来源关联（按 Port 与方案）；调用方必须显式 `insertSourceLink`，否则读取的 `rawMemoryIds` 为空。若后续希望聚合写入，需要新契约。
- `findByIds` 返回顺序不保证与入参一致（SQL 无 ORDER BY，沿用方案）；按集合断言。
- Organized 读取为「每行一次来源正查」（N+1，`findByIds`/`scanPage` 同样）；当前按方案只提供单条正查 SQL。若 B3 集成时成为热点，可另加批量来源查询，不改变现有语义。
- `sources_json` 的 snake_case 字段名与 `type` 取值是 B2 定的持久化内部编码，需 Lead/Architect 确认后再进入 CM2，避免迁移成本。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 独立初验与 Architect 独立复核。
