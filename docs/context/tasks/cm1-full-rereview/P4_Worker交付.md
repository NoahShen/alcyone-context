# CM1 全面复查 P4 Worker 交付（单语句失败 vs 事务终结失败）

**任务**：CM1 全面复查 P4：普通 `updateRaw` 语句失败不 poison、健康连接可继续 write，并与「事务终结失败必须 poison」显式区分
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-29

## 本次修改（2026-10-29，P4 单语句失败不 poison）

- **仅新增测试**，无生产改动（`updateRaw` / `queryRaw` 本就不调用 `slot.poison()`；poison 只发生在 begin/commit/rollback/恢复失败路径）。
- 新增 `StatementFailureVsTransactionFailureTest`（4 例，真实文件 SQLite）：单语句失败不 poison 且可继续写入、读路径失败不 poison、两类失败显式对比、事务终结失败仍 poison 且后续写入被拒。
- 未改 API/Core/Runtime/Server；未与其他修复合并；未 `git add/commit`。

## 1. 结论：无需生产微调

复核了实现路径：

| 路径 | 是否 poison |
| --- | --- |
| `updateRaw(sql)` | **否**。`ContextSqliteErrors.map` 把 `SQLException` 收敛为 `PERSISTENCE_UNAVAILABLE`，`finally` 只失效作用域并清 `currentScope`，从不调用 `slot.poison()` |
| `queryRaw(sql)` | **否**。同样只做安全映射与作用域清理 |
| `write {}` 的 begin 失败 | 是（`beginTransaction` 内 `poison(failure)`） |
| `write {}` 的 commit 失败 | 是（`commitTransaction` 内 `poison(failure)`，且不回滚） |
| `write {}` 的 rollback 失败 | 是（`rollbackTransaction` 内 `poison(failure)`） |
| 恢复 autoCommit 失败 | 是（`restoreAutoCommit` 内 `poison(failure)`） |

因此 P4 只需补正式回归，把这两类行为显式锁死，防止将来把「语句失败」误升级为 poison。

## 2. 测试与断言（新增 4 例）

| 用例 | 断言 |
| --- | --- |
| `updateRaw statement failure does not poison the write slot` | 违反 `raw_memory.content` 的 `CHECK (length(trim(content)) > 0)`：抛 `ContextPersistenceException(PERSISTENCE_UNAVAILABLE)`；消息不含 `raw_memory` 也不含参数值 `R-P4-1`；`writeSlot.isPoisoned()==false`；`writeSlotAutoCommit()==true`（失败语句没留下未结束事务）；随后 `write{}` 插入 `R-P4-2` 并可见（count=1）；仍不 poison |
| `queryRaw statement failure does not poison the read slots` | 查不存在的表：同一具名异常、消息不含表名；**所有读 Slot** `isPoisoned()==false`；随后 `queryRaw` 计数仍可用 |
| `transaction termination failure poisons while statement failure does not` | 同一执行器上先做单语句失败（不 poison）→ 事务内业务异常（成功 rollback，仍不 poison）→ 再次事务内业务异常（rollback 成功，不 poison）。用连续对比说明「只有终结失败才 poison」 |
| `transaction termination failure is still poison and later writes are refused` | 用只包 `commit` 的 Driver 代理注入一次 commit 失败：单语句失败后 `isPoisoned()==false`；commit 失败后 `isPoisoned()==true`；后续 `write{}` 抛具名中毒错误（消息含 `poison`） |

关键区分点：**语句失败 = 单条 SQL 报错，连接与事务状态仍然可信；终结失败 = 事务边界（begin/commit/rollback/恢复）不可信，连接必须停用**。

## 3. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `:context:persistence-sqldelight:spotlessApply :context:persistence-sqldelight:compileKotlin` | 0 | 无生产改动，编译确认 |
| `:context:persistence-sqldelight:spotlessApply` | 0 | 格式化；`spotless-apply.log` |
| `:context:persistence-sqldelight:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks` | 0 | **167 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 167 = XML 167，**差额 0**；`targeted-test.log` |
| `:context:core:test :context:runtime:test :context:server:test --rerun-tasks` | 0 | 无回归 |

persistence 167 = P1 后的 163 + `StatementFailureVsTransactionFailureTest` 4。

## 4. 改动文件与 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `.../test/StatementFailureVsTransactionFailureTest.kt`（新增） | `2152a046ff885d2fb50ee2fefa2e81359fdf521f59b08db6d9b68b2f170caf34` |
| `.../sqldelight/ContextDatabaseExecutor.kt`（未改，供核对） | `1890d74df3e63a90cab372f89ae67e71b78e4f13ebdde059fb347cd4de078005` |
| `.../sqldelight/ContextSlot.kt`（未改，供核对） | `849e881115c1ec268271cf2131bc5350e924a5440b216339eb54d2d8b7c7d1e2` |

## 5. 未做项与边界

- 不改 CT04 既有语义：commit/rollback/恢复失败仍然 poison，且 commit 失败后不回滚。
- 不改 `queryRaw` 行为（已验证不 poison）。
- 未改 API/Core/Runtime/Server；未与其他修复合并；未 `git add/commit`。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 初验与 Architect 独立复核。
