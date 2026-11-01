# CT05-B3（ContextDatabase 装配与集成）Lead 独立初验

## 本次修改（2026-10-28，CT05-B3 初验，提交 Architect 逐批复核）

- B2 已 APPROVED（CT05 复核第 5 节）。B3 由同一 Paseo worker 配置 Worker `d7695b22` 实现（单一写入者）；Lead 未写生产代码，只做独立复跑、代码审查与初验。
- 范围：`ContextDatabase` / `ReadContext` / `TransactionContext` 装配 + `integration-tests/context` 集成；未做 Runtime 业务接线（CT06）、未做 CT07；未改 Schema/索引/`user_version`/API/Core 接口/CT04 保护层/VFS/common/AGENTS/Architect 文件；无 git 写操作。

## 1. 交付物（SHA 已核对）

| 文件 | SHA-256 |
| --- | --- |
| `ContextDatabaseImpl.kt` | `600f551381c69fa57ca67d9ce9646b4d5497554b97522ebae1df4f35503bdf4f` |

- 装配：`read` 只开一个 `RawReadScope` → 五个 ReadOps 共享同一 scope/session/只读事务；`write` 只开一个 `RawTransactionScope` → 五个 TxOps（同时继承 ReadOps）共享同一写事务与物理连接；不缓存 scope、不裸透连接、不新增生命周期（仍由调用方持有 executor）。
- CT04 保护层 4 文件 SHA 未变（`8cd7a1a9…`/`185ecbf9…`/`79fc3ac1…`/`7949dfed…`）。
- `integration-tests/context/build.gradle.kts` 仅新增 `testImplementation(projects.context.persistenceSqldelight)`（测试接线，不经 Runtime）。

## 2. Lead 独立实跑（非引用 Worker 日志）

| 检查 | 结果 |
| --- | --- |
| `:context:persistence-sqldelight:test :spotlessCheck --rerun-tasks` | exit 0；XML **155 例 0 失败/错误/跳过**；源码 `@Test`=155=XML 155，差额 0 |
| `:integration-tests:context:test :spotlessCheck --rerun-tasks` | exit 0；XML **15 例 0 失败/错误/跳过**；源码 `@Test`=15=XML 15，差额 0 |
| 装配是否暴露 SQL/JDBC | 集成测试反射检查 `ContextDatabase`/`ReadContext`/`TransactionContext`/`ContextDatabaseImpl` 公开方法签名，不含 `java.sql.*`/`app.cash.sqldelight.*`/`Connection`/`Driver` |
| 真实库与确定性 | 集成测试使用真实文件 SQLite（`ContextDatabaseFactory.open`），并发/快照用 `CountDownLatch` 控制，无 `Thread.sleep` |

## 3. 集成断言覆盖（A–G）

| 项 | 证据 |
| --- | --- |
| A 多 Repository 原子性 | 同一 write 内五类聚合全部提交后独立只读连接五表可见；任一步抛错整笔回滚，执行器内与独立连接全部不可见（checkpoint 保持 0） |
| B 读快照 | 读回调内建立快照 → 外部线程 write 提交 → 同一回调内重读仍为 null；回调返回后新 read 可见（latch 控制） |
| C 并发幂等与 CAS | 两个并发同键同内容插入 → 恰好一个 `CREATED`、一个 `DUPLICATE`（同一 id，`created_at` 不被刷新），无 `TURN_CONFLICT`；两个并发 CAS(0→1/2) → 恰好一个 1、一个 0，水位只前进一次 |
| D 作用域逃逸/跨线程 | 逃逸的 ReadContext/TransactionContext 调用 → `PERSISTENCE_UNAVAILABLE`；回调活跃时跨线程 → `CROSS_THREAD`；异常链无 cause/底层链 |
| E 取消与健康复用 | write/read 回调抛 `CancellationException` → 原样传播、写回滚、读不 poison，后续读写正常 |
| F 重开 | 关闭重开后 conversation/raw/organized（含 `rawMemoryIds`）/pending/checkpoint 数据与 createdAt 一致 |
| G 未绕过 CT04 | 同一执行器上 `COMMIT`、`SAVEPOINT`、`SELECT 1; COMMIT` 仍被具名拒绝 |

## 4. 过程缺陷与修复（已核实）

- 首轮集成测试源码 `@Test`=15 但 XML=11：4 个用例以 `assertNotNull`/`assertFailsWith` 作为最后表达式导致方法返回非 Unit，JUnit 5 静默跳过。Worker 已把 `withDatabase` 回调与相关方法强制为 Unit；Lead 独立复跑确认 **15 = 15**，无静默跳过。该过程记录已写入交付文档与 `verification.json`，供 Architect 对照。

## 5. 结论与边界

- **B3 初验通过**：155 + 15 例全绿、差额 0；装配共享单一 scope、无 SQL/JDBC 暴露、未绕过 CT04；原子性/快照/并发幂等与 CAS/逃逸与跨线程/取消回滚与复用/重开均有真实库断言。
- 提交 **Architect 逐批复核**；B3 通过后进行 **CT05 整体收口**（按任务书验收 1～4 汇总、全工程回归与计数/SHA 自洽）。**CT06/CT07 未授权、未启动**。

## 6. R1/R2 测试收尾后 Lead 独立复核（2026-10-28）

- **R1**：`B3Fixtures.TestDatabase.close` 改为 `Files.walk(directory).use {}` 关流；逐个删除失败收集为带路径的 `IllegalStateException`，全部尝试后抛主失败并 `addSuppressed`，不再 `runCatching` 全吞。
- **R2**：两个跨线程用例改为 `ExecutorService.submit` + `Future`；回调内 `assertTrue(release.await(10s))`；`finally` 无条件 `release.countDown()` → `Future.get(10s)`（传播后台异常/超时）→ `shutdownNow()` → `assertTrue(awaitTermination(10s))`；末尾断言 `callbackFailure == null`。snapshot 用例补 `finally` 双放行、`join(10s)` 后 `assertFalse(writer.isAlive)`，并保留「先建立快照 → 确认外部提交 → 回调内重读不可见 → 回调后可见」判据。
- **Lead 独立实跑**：`:integration-tests:context:test :spotlessCheck --rerun-tasks` → exit 0；XML **15 例 0 失败/错误/跳过**；源码 `@Test`=15=XML 15，差额 0。日志 `.local/validation/cm1/lead/ct05/b3-cleanup-check.log`。未机械重跑 persistence 155 例（本轮未改持久化生产）。
- **SHA**：`B3Fixtures.kt`=`9f5f0474…`、`ContextDatabaseAssemblyIntegrationTest.kt`=`4a6a3519…`、`ContextDatabaseScopeBoundaryIntegrationTest.kt`=`83d65dec…`；生产 `ContextDatabaseImpl.kt`=`600f5513…` 未变；CT04 四保护层仍为最终验收值。
- **结论**：B3-R1/R2 关闭，B3 初验维持通过，提交 Architect 收口 B3；随后再做 CT05 整体验收（不颠倒顺序），CT06/CT07 未放行。
