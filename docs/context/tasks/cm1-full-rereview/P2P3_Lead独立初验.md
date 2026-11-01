# P2/P3（分页参数校验 + updated_at 水位契约）Lead 独立初验

## 本次修改（2026-10-29，全面复查 P2/P3 初验，提交 Architect 复核）

- F3 已 APPROVED。P2/P3 由同一 Paseo worker 配置 Worker `d7695b22` 同批实施（P3 按 Q8 裁定不改实现）；P2 错误语义经 Lead 退回订正一次（见 §3）。
- 范围：`context/persistence-sqldelight` 生产与测试；未改 API/Core/Runtime/Server；无 git 写操作。

## 1. 交付物（SHA 已核对，含订正后）

| 文件 | SHA-256（前缀） |
| --- | --- |
| `sqldelight/ContextOps.kt`（订正后） | `04c20e6857c6be51` |
| `sqldelight/ContextSqlQueries.kt`（P2 契约注释） | `5db9a8eba85a7981` |
| `test/PaginationArgumentValidationTest.kt`（订正后） | `2b385d01d3163b2b` |
| `test/WatermarkContractTest.kt` | `cbcd00732018f623` |

## 2. Lead 独立实跑

| 模块 | 结果 |
| --- | --- |
| `context:persistence-sqldelight:test` | **176 例**（P4 后 167 + P2 4 + P3 5），0 失败/错误/跳过，源码=XML=176 |
| `context:core:test` | 11 例，0 失败 |
| `context:runtime:test` | 89 例，0 失败 |
| `context:server:test` | 30 例，0 失败 |
| `persistence spotlessCheck --rerun-tasks` | exit 0 |

日志 `.local/validation/cm1/lead/rereview-p2p3/check.log`、`.local/validation/cm1/lead/rereview-f1r1/check.log`。

## 3. P2（分页参数前置校验）

`requirePagination(limit, offset?)` 在**执行任何 SQL 之前**拒绝负值，四个分页入口全部接入：

| 入口 | 校验位置 |
| --- | --- |
| `sessionWindow`（ConversationTurnReader） | `:355`，早于两条 `queryPrepared` |
| `idRange` | `:460` |
| Raw `scanPage` | `:584`，早于 `SELECT_RAW_PAGE` |
| Organized `scanPage` | `:778` |

- **Lead 退回订正**：初版把调用方参数错误报成 `ContextPersistenceException(PERSISTENCE_UNAVAILABLE)`（本模块枚举无 `INVALID_ARGUMENT`），会把调用方 bug 伪装成存储不可用、误导诊断并可能触发重试。已改为 Kotlin `require(...)`（`IllegalArgumentException`），与本模块既有口径一致（`ContextDatabaseExecutor.kt:51`、`ContextDatabaseFactory.kt:78`、`ContextSqlQueries.kt:178`），**未新增 API 枚举值**。
- 消息只含字段名 + 非法值（`pagination limit must be >= 0, got -1`），不回显 SQL 原文。
- `limit = 0` **合法返回空页**；`offset = 0` 正常。失败后 `autoCommit` 已恢复、执行器仍可写、库内数据不受影响。

## 4. P3（updated_at 全局水位，按裁定不新增 CAS）

- `advanceUpdatedAt` 与 `ADVANCE_UPDATED_AT` KDoc 明确**调用方职责**：必须在同一写事务内 `maxUpdatedAt` → 分配 `next > max` → `advanceUpdatedAt`；**传回退值属契约违规**，本层不提供防护，也**不视为可接受行为**。
- **SQL 与签名未动**，未新增条件更新（符合 Q8 裁定）。
- 5 例回归（真实 SQLite 读回）：同一 `write{}` 内三行 `max → +1ms → advance` 严格递增且提交后全局 MAX 与各行一致；MERGE 路径（`update` + `advance` + `insertSourceLink`）；推进单行不影响其他行；缺失行返回 0 且水位不变；两个独立库水位互不影响。

## 5. 结论与边界

- **P2/P3 初验通过**：四入口 SQL 前拒绝负值、0 合法、错误语义诚实且不泄露 SQL；P3 未新增 CAS、契约与调用方职责在 KDoc 明确、多行与 MERGE 路径有真实回归。
- 提交 **Architect 独立复核**（与 F1-R1 分开审）；通过后 **F4 + 整体回归**，全部闭环后启动 CT08。**CM2 实现仍未启动**；未 git 提交/发布。
