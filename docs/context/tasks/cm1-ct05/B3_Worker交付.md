# CT05-B3 Worker 交付（ContextDatabase 装配 + integration-tests 集成）

**任务**：CM1 CT05-B3：`ContextDatabase` / `ReadContext` / `TransactionContext` 装配与跨模块集成验证
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db（Paseo lead 配置，只做方案与独立初验）
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-28

## 本次修改（2026-10-28，B3 复核收尾：R1 测试清理与 R2 跨线程收尾）

- 按 Architect 复核第 6 节定点修复，只改 `integration-tests/context` 测试与本文件；未改生产代码、未加测试框架、未改 CT04 保护层。
- **R1**：`B3Fixtures.TestDatabase.close` 改为 `Files.walk(...).use { }` 关闭流；删除失败逐个收集（失败路径 + 异常，其余以 suppressed 附加）后抛出，不再 `runCatching` 全吞；目录不存在仍直接返回。
- **R2**：两个跨线程用例改用 `ExecutorService.submit` + `Future`：回调内 `assertTrue(release.await(10s))`、`finally` 无条件 `countDown()`、`Future.get(10s)` 有界等待并传播后台异常、`shutdownNow + awaitTermination(10s)` 断言线程退出、末尾断言后台失败为 null。相邻 snapshot 用例补 `finally` 放行两个 latch、有界 `join(10s)` 与 `!isAlive` 断言，保留「先建快照→外部写提交→回调内重读不可见→回调后可见」判据。
- 只重跑集成测试（未机械重跑 persistence 155 例）：`test :spotlessCheck --rerun-tasks` 退出 0，**15 例全绿**，源码 `@Test` 15 = XML 15，差额 0；日志 `cleanup-fix.log`。
- 证据 `.local/validation/cm1/worker/ct05-b3/` 更新 `final-source-hashes.json` 与 `verification.json`；未 `git add/commit`，未自标 DONE。

## 本次修改（2026-10-28，CT05-B3 ContextDatabase 装配与集成）

- 新增 `ContextDatabaseImpl.kt`：`read` 只开一个 `RawReadScope` 装配五个 ReadOps；`write` 只开一个 `RawTransactionScope` 装配五个 TxOps（TxOps 同时是 ReadOps），全部共享同一 scope / session / 物理连接。
- `integration-tests/context` 新增 13 例（另保留既有 skeleton 2 例）：多 Repository 原子提交/回滚、读快照、并发幂等与 CAS、取消后健康复用、重开一致、作用域逃逸/跨线程、装配不暴露 SQL/JDBC 且 CT04 保护仍生效；`build.gradle.kts` 增加 `testImplementation(projects.context.persistenceSqldelight)`（测试接线，不经 Runtime）。
- 未改 Schema/索引/`user_version`/API/Core 接口/CT04 保护层/B1/B2 已验收行为；未改 VFS/common/进度入口/AGENTS/Architect 文件；未做 Runtime 业务接线（CT06）/CT07；未 `git add/commit`。
- 过程中发现并修复一处测试发现缺陷：4 个集成测试最初把 `assertNotNull` / `assertFailsWith` 作为最后一个表达式，方法返回非 Unit，JUnit 5 静默跳过（源码 `@Test` 15 vs XML 11）。现已强制 Unit，最终 **源码 `@Test` 15 = XML `tests` 15，差额 0**。

## 1. 装配落点

| 文件 | 内容 | 关键落点 |
| --- | --- | --- |
| `context/persistence-sqldelight/.../ContextDatabaseImpl.kt` | `ContextDatabase` 实现与两个 Context 装配 | `ContextDatabaseImpl:32`、`read:35`、`write:37`、`ReadContextImpl:41`、`TransactionContextImpl:52` |

装配语义：

- `read(block)` → `executor.read { scope -> block(ReadContextImpl(scope)) }`；五个属性分别为 `ConversationReadOpsImpl` / `RawMemoryReadOpsImpl` / `OrganizedMemoryReadOpsImpl` / `OrganizationPendingReadOpsImpl` / `DreamingCheckpointReadOpsImpl`，全部绑定同一 scope。
- `write(block)` → `executor.write { scope -> block(TransactionContextImpl(scope)) }`；五个属性为对应 `*TxOpsImpl`，同一 scope 上读写共享事务与物理连接。
- 生命周期（打开/关闭）仍由调用方持有 `ContextDatabaseExecutor`；本类不新增连接管理、不缓存 scope、不裸透 `Connection`/`Driver`。
- Ops 内不允许再调 `database.read/write`（执行器会具名拒绝嵌套）；异常/取消沿用 CT04 契约。

## 2. 集成测试与断言（15 例）

`integration-tests/context`，真实文件 SQLite，直接 `ContextDatabaseFactory.open` + `ContextDatabaseImpl`，不接 Runtime。

| 用例（类:行） | 对应验收 | 关键断言 |
| --- | --- | --- |
| `write commits all five aggregates atomically`（Assembly:25） | A | 同一 write 内 conversation/raw/organized/link/pending/checkpoint 全部成功；关闭执行器后独立只读连接五表计数与 checkpoint=7 |
| `write rolls back every aggregate when the callback throws`（Assembly:56） | A | 回调抛错后执行器内五类读取均为空、checkpoint=0；独立只读连接五表计数 0、checkpoint=0 |
| `read context keeps one snapshot while an external write commits`（Assembly:94） | B | 读回调内先读（建立快照）→ 外部线程 write 提交 → 同一回调内重读仍为 null；回调返回后新 read 可见新行（受控 CountDownLatch，无 sleep） |
| `concurrent identical conversation inserts yield one CREATED and one DUPLICATE`（Assembly:141） | C | 两个并发 write 同四元键同内容 → 恰好一个 CREATED、一个 DUPLICATE；同一 id；`created_at` 等于 CREATED 调用的值（第二个调用用 +1h 也不刷新）；无 TURN_CONFLICT；表内 1 行 |
| `concurrent checkpoint CAS only advances once`（Assembly:184） | C | 两个并发 CAS(expected=0, next=1/2) → 恰好一个返回 1、另一个 0；最终水位为 1 或 2（只前进一次） |
| `write cancellation propagates rolls back and keeps the write slot reusable`（Assembly:212） | E | `CancellationException` 原样抛出；失败写不可见；随后 write 正常 |
| `read cancellation propagates and does not poison the read slots`（Assembly:227） | E | 读回调抛 `CancellationException` 原样传播；后续读正常（pending/checkpoint）；随后 write 正常 |
| `reopening the database preserves all five aggregates`（Assembly:240） | F | 关闭后重开：conversation 的 createdAt/messages、raw 的 sources、organized 的 domains/rawMemoryIds/updatedAt、pending、checkpoint=11 全部一致 |
| `read context cannot be used after its callback returns`（Scope:26） | D | 逃逸 ReadContext 调用 → `PERSISTENCE_UNAVAILABLE`，消息含 after its callback，无 cause |
| `transaction context cannot be used after its callback returns`（Scope:39） | D | 逃逸 TransactionContext 写调用 → 同上；事务已提交、无残留行 |
| `read context rejects use from a foreign thread while the callback is active`（Scope:53） | D | 回调仍活跃时另一线程调用 → `CROSS_THREAD`（消息含 thread），无 cause；回调正常结束 |
| `transaction context rejects use from a foreign thread while the callback is active`（Scope:83） | D | 写回调仍活跃时另一线程调用 → `CROSS_THREAD`；释放后事务正常提交 |
| `assembly exposes no SQL or JDBC surface and CT04 guards still reject`（Scope:113） | G | `ContextDatabase`/`ReadContext`/`TransactionContext`/`ContextDatabaseImpl` 的公开签名不含 `java.sql.*`、`app.cash.sqldelight.*`、`Connection`/`Driver`；同一执行器 `updateRaw("COMMIT")`、`SAVEPOINT`、`SELECT 1; COMMIT` 均被具名拒绝 |
| 既有 `ContextIntegrationSkeletonTest`（2 例） | — | 跨模块依赖与 Ktor testApplication 保持 |

测试基础设施：`B3Fixtures.kt` 提供临时库（初始化/重开/递归清理）、真实执行器、`mode=ro` 独立只读连接（仅核对落库，不写入）、固定时间与会话键。

## 3. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessApply :context:persistence-sqldelight:compileKotlin --console=plain` | 0 | 装配批次编译通过 |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessApply :integration-tests:context:spotlessApply --console=plain` | 0 | 两模块格式化；`spotless-apply.log` |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks --console=plain` | 0 | **155 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 155 = XML 155，差额 0；`targeted-test.log` |
| `./scripts/dev gradle :integration-tests:context:test :integration-tests:context:spotlessCheck --rerun-tasks --console=plain` | 0 | **15 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 15 = XML 15，差额 0；`integration-test.log` |

集成计数：15 = Assembly 8 + Scope 5 + Skeleton 2。证据目录 `.local/validation/cm1/worker/ct05-b3/`：`targeted-test.log`、`integration-test.log`、`spotless-apply.log`、`final-source-hashes.json`、`verification.json`。

## 4. 改动文件与最终 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `.../persistence/sqldelight/ContextDatabaseImpl.kt` | `600f551381c69fa57ca67d9ce9646b4d5497554b97522ebae1df4f35503bdf4f` |
| `integration-tests/context/.../B3Fixtures.kt` | `9f5f047429097bc2f7ae1ae6d14b7de19496a8c59898e28a1b76d4d2e14b4edc` |
| `.../ContextDatabaseAssemblyIntegrationTest.kt` | `4a6a3519490d99ee74bdff694345f2e84982260f8d1319312110ceaa72e80c20` |
| `.../ContextDatabaseScopeBoundaryIntegrationTest.kt` | `83d65decde3bf9595a90671233b1c15e87658eceaa1e1030cc0bcc24107e7834` |
| `integration-tests/context/build.gradle.kts` | `d4b243075a35419e10c2f6db5b87756059473032c2297c46da17da23ccdc12c0` |

CT04 四个保护层未改动（与 rev5 证据一致）：`ContextDatabaseExecutor.kt` `8cd7a1a9…`、`ContextSlot.kt` `185ecbf9…`、`SlotConnectionJdbcDriver.kt` `79fc3ac1…`、`RawScopes.kt` `7949dfed…`。完整清单见 `final-source-hashes.json`。

## 5. 未做项与边界

- 未做 CT06 Runtime 业务接线、CT07 Ingestion/HTTP；`context/runtime` 与 `context/server` 仍为 NO-SOURCE。
- 未改 Schema/索引/`user_version`/公开 API/Core 接口/CT04 保护层/VFS/common/进度入口/AGENTS/Architect 文件；未 `git add/commit`。
- 集成测试直接使用 `ContextDatabaseFactory` + `ContextDatabaseImpl`，因此 `integration-tests/context/build.gradle.kts` 增加了对 `context:persistence-sqldelight` 的 **testImplementation** 依赖（仅测试接线，不引入 Runtime 业务）。
- `ContextDatabaseImpl` 不管理生命周期：打开/关闭仍由调用方持有 `ContextDatabaseExecutor`；若 CT06 需要统一 `close`，应在 Runtime 层决定，不在 B3 扩大接口。
- 并发用例使用 `CountDownLatch` + 执行器串行语义，不使用 sleep 概率断言。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 独立初验与 Architect 独立复核。

## 6. R1 / R2 修复记录（2026-10-28）

| 项 | 落点 | 修复 |
| --- | --- | --- |
| R1 测试清理 | `B3Fixtures.kt` `TestDatabase.close` | `Files.walk(directory).use { ... }` 关闭流；逐个 `deleteIfExists`，失败收集为 `IllegalStateException("failed to delete test artifact <path>", cause)`，全部尝试后抛出主失败并把其余失败 `addSuppressed`；目录不存在直接返回 |
| R2 跨线程（读） | `ContextDatabaseScopeBoundaryIntegrationTest` `read context rejects use from a foreign thread...` | `Executors.newSingleThreadExecutor().submit { runBlocking { database.read { ... assertTrue(release.await(10s)) } } }`；`finally` 无条件 `release.countDown()`、`Future.get(10s)` 传播后台异常、`shutdownNow` + `awaitTermination(10s)` 断言退出；末尾 `assertNull(callbackFailure)` |
| R2 跨线程（写） | 同文件 `transaction context rejects use from a foreign thread...` | 同上，回调为 `database.write { ... }` |
| R2 snapshot 回收 | `ContextDatabaseAssemblyIntegrationTest` `read context keeps one snapshot...` | `finally` 无条件 `snapshotEstablished.countDown()` / `writeCommitted.countDown()`，`writer.join(10s)` 后 `assertFalse(writer.isAlive)`；判据不变：先建立快照 → 外部写提交 → 同一回调重读仍 null → 回调后新 read 可见 |

修复前 → 修复后 SHA：

| 文件 | 修复前 | 修复后 |
| --- | --- | --- |
| `B3Fixtures.kt` | `7993f9bc…` | `9f5f047429097bc2f7ae1ae6d14b7de19496a8c59898e28a1b76d4d2e14b4edc` |
| `ContextDatabaseAssemblyIntegrationTest.kt` | `dc208ee1…` | `4a6a3519490d99ee74bdff694345f2e84982260f8d1319312110ceaa72e80c20` |
| `ContextDatabaseScopeBoundaryIntegrationTest.kt` | `7aec8ddc…` | `83d65decde3bf9595a90671233b1c15e87658eceaa1e1030cc0bcc24107e7834` |

复跑：`:integration-tests:context:test :integration-tests:context:spotlessCheck --rerun-tasks` → 退出 0，15 例（8 + 5 + 2）0 失败/错误/跳过，源码 `@Test` 15 = XML 15；`spotlessApply` 已执行。生产装配 `ContextDatabaseImpl.kt` 与 CT04 保护层 SHA 未变。
