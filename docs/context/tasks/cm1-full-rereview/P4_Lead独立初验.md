# P4（单语句失败 vs 事务终结失败）Lead 独立初验

## 本次修改（2026-10-29，全面复查 P4 初验，提交 Architect 复核）

- P1 已 APPROVED。P4 由同一 Paseo worker 配置 Worker `d7695b22` 实施（单独批次，未与其他修复合并）；Lead 未写生产代码。
- 范围：**仅新增测试，无生产改动**（复核确认 `updateRaw`/`queryRaw` 本就不 poison）；未改 API/Core/Runtime/Server；无 git 写操作。

## 1. 交付物（SHA 已核对）

| 文件 | SHA-256（前缀） |
| --- | --- |
| `test/StatementFailureVsTransactionFailureTest.kt`（新增） | `2152a046ff885d2f` |
| `ContextDatabaseExecutor.kt`（未改） | `1890d74df3e63a90`（与 P1 一致） |
| `ContextSlot.kt`（未改） | `849e881115c1ec26`（与 P1 一致） |

## 2. Lead 独立实跑（非引用 Worker 日志）

| 模块 | 结果 |
| --- | --- |
| `context:persistence-sqldelight:test` | **167 例**，0 失败/错误/跳过，源码=XML=167（P1 后 163 + 4） |
| `context:core:test` | 11 例，0 失败（无回归） |
| `context:runtime:test` | 87 例，0 失败（F1 保持） |
| `context:server:test` | 19 例，0 失败（无回归） |
| `persistence spotlessCheck --rerun-tasks` | exit 0 |

日志 `.local/validation/cm1/lead/rereview-p4/check.log`。

## 3. 实现审查（确认无需生产微调）

| 路径 | poison？ | 依据 |
| --- | --- | --- |
| `updateRaw(sql)` | 否 | `ContextSqliteErrors.map` 只做安全映射；`finally` 仅 `scopeSession.invalidate()` + `clearCurrentScope()` |
| `queryRaw(sql)` | 否 | 同上 |
| `beginTransaction` / `commit` / `rollback` / 恢复 autoCommit 失败 | 是 | `ContextSlot` 内 `poison(failure)` |

结论：实现本就符合 P4 期望，无需生产微调；新增测试用于**锁死**两类行为，防止将来把「语句失败」误升级为 poison。

## 4. 测试核对（4 例，真实文件 SQLite）

| 用例 | 关键断言（已核实） |
| --- | --- |
| `updateRaw statement failure does not poison the write slot` | 违反 `raw_memory.content` CHECK → `PERSISTENCE_UNAVAILABLE`；消息**不含** `raw_memory`（SQL 原文）与 `R-P4-1`（参数值）；`isPoisoned()==false`；`writeSlotAutoCommit()==true`；随后 `write{}` 插入 `R-P4-2` 成功且 `countRaw==1`；提交后仍不 poison |
| `queryRaw statement failure does not poison the read slots` | 查不存在表 → 同一具名异常、消息不含表名；**所有读 Slot** 未 poison；读路径仍可用 |
| `transaction termination failure poisons while statement failure does not` | 同一执行器上「单语句失败 → 不 poison」与「事务内业务异常 → 成功 rollback → 不 poison」连续对照 |
| `transaction termination failure is still poison and later writes are refused` | 只包 `commit` 的 Driver 代理注入一次失败 → `isPoisoned()==true`；后续 `write{}` 被具名中毒错误拒绝（消息含 `poison`） |

## 5. 结论与边界

- **P4 初验通过**：单语句失败不 poison 且连接可继续写；事务终结失败仍 poison 且后续写被拒；两类行为被显式对比锁死；无生产改动、四模块无回归。
- 提交 **Architect 独立复核**；通过后继续 **F2 → F3 → P2/P3 → F4**；全部闭环并整体回归后直接启动 CT08。**CM2 实现仍未启动**；未 git 提交/发布。
- 备注：Architect 指出 P1 的 T6 可补「poisoned 首次失败后全局重试成功」分支作为增强（非阻断），后续择机纳入。
