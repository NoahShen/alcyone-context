# CM1 全面复查 P2 + P3 Worker 交付（分页前置校验 / 水位调用方契约）

**任务**：CM1 全面复查 P2（分页参数前置校验）+ P3（`updated_at` 全局水位按裁定不新增 CAS）
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-29

## 本次修改（2026-10-29，P2 错误语义订正：IllegalArgumentException）

- 初验指出：把调用方参数错误报成 `PERSISTENCE_UNAVAILABLE` 会把调用方 bug 伪装成存储不可用，误导诊断并可能触发重试。
- `requirePagination` 改用 Kotlin `require(...)`（`IllegalArgumentException`），与本模块既有参数校验口径一致
  （`ContextDatabaseExecutor.kt:51`、`ContextDatabaseFactory.kt:78`、`ContextSqlQueries.kt:178` 均用 `require`）；
  **不新增 API 枚举值**。消息仍只含字段名与非法值、不回显 SQL。
- `PaginationArgumentValidationTest` 断言改为 `IllegalArgumentException`，并保留「负值在 SQL 执行前拒绝、
  失败后 `autoCommit` 已恢复、执行器仍可写、库内数据不受影响」的全部断言。
- **P3 未改动**：KDoc 与 5 例水位回归保持不变（不新增 CAS、明确调用方职责、回退值属契约违规）。
- 计数不变：persistence 176、core 11、runtime 89、server 30；证据 `.local/validation/cm1/worker/rereview-p2p3/`。

## 本次修改（2026-10-29，P2 分页前置校验 + P3 水位契约 KDoc）

### P2
- `ContextOps.kt` 新增 internal `requirePagination(limit, offset?)`：在执行任何 SQL 之前拒绝负 `limit` / 负 `offset`；`limit = 0` / `offset = 0` 合法（`limit=0` 返回空页）。
- 应用到全部四个分页入口：`ConversationTurnReader.sessionWindow`、`idRange`、`RawMemoryReader.scanPage`、`OrganizedMemoryReader.scanPage`。
- 错误用 Kotlin `require(...)` → `IllegalArgumentException`（与本模块既有参数校验口径一致），消息只含字段名 + 非法值，不回显 SQL 原文；这是调用方参数错误，不应伪装成存储不可用。
- 新增 `PaginationArgumentValidationTest` 4 例（真实文件 SQLite）。

### P3（按裁定不新增 CAS、不改 SQL/签名）
- 在 `OrganizedMemoryTxOpsImpl.advanceUpdatedAt` 与 `ADVANCE_UPDATED_AT` 的 KDoc 中明确**调用方职责**：同一写事务内 `maxUpdatedAt` → 分配 `next > max` → `advanceUpdatedAt`；传回退值属**契约违规**，本层不提供防护、也不视为可接受行为。
- 新增 `WatermarkContractTest` 5 例（真实事务 + SQLite 读回）：多行严格递增、MERGE 路径、单行推进隔离、缺失行影响行数 0、跨库隔离。

未改 API/Core/Runtime/Server；未与其他修复合并；未 `git add/commit`。

## 1. P2 实现

```kotlin
internal fun requirePagination(limit: Int, offset: Int? = null) {
    require(limit >= 0) { "pagination limit must be >= 0, got $limit" }
    if (offset != null) require(offset >= 0) { "pagination offset must be >= 0, got $offset" }
}
```

| 入口 | 校验 |
| --- | --- |
| `ConversationTurnReader.sessionWindow(...)` | `requirePagination(limit)`（有/无锚点两条 SQL 之前） |
| `ConversationTurnReader.idRange(...)` | `requirePagination(limit)` |
| `RawMemoryReader.scanPage(offset, limit)` | `requirePagination(limit, offset)` |
| `OrganizedMemoryReader.scanPage(offset, limit)` | `requirePagination(limit, offset)` |

- 为什么用 `IllegalArgumentException`：这是**调用方参数错误**，不是存储不可用。本模块既有参数校验（`ContextDatabaseExecutor.kt:51`、`ContextDatabaseFactory.kt:78`、`ContextSqlQueries.kt:178`）都用 Kotlin `require`；`ContextPersistenceErrorCode` 也没有 `INVALID_ARGUMENT`，因此不新增枚举值、不伪装成 `PERSISTENCE_UNAVAILABLE`。
- Core 侧分页 KDoc 已在契约中说明 `limit`/排序语义，本次**无需**修改 Core 注释；如需补一句「limit 不得为负」，可在下一批纯注释提交中同步，本批未改。

## 2. P2 测试（4 例）

| 用例 | 断言 |
| --- | --- |
| `sessionWindow` | `limit=-1` → `IllegalArgumentException`，消息含 `limit` 与 `-1`、不含 `LIMIT/OFFSET`；`limit=0` → 空页；正常 limit 仍返回数据 |
| `idRange` | `limit=-1` 拒绝；`limit=0` 空页；`limit=1` 返回首条 |
| Raw `scanPage` | `limit=-1`、`offset=-1` 分别拒绝并含字段名/值；`limit=0` 空页；正常分页 2 条 |
| Organized `scanPage` | 同上（`offset=-5` 也拒绝） |

每个用例在失败后断言：`writeSlotAutoCommit()==true`（无残留事务）、随后 `write {}` 成功且数据可见（执行器仍健康）。

## 3. P3 实现（仅 KDoc）与测试（5 例）

KDoc 关键句（`ContextOps.advanceUpdatedAt` 与 `ADVANCE_UPDATED_AT`）：

> 调用方必须在同一写事务内先 `maxUpdatedAt()` 读取全局最大值，再分配严格更大的毫秒值写回；
> 传入回退值（`next <= 当前全局 max`）属于**契约违规**，本层不提供防护，也不视为可接受行为。

| 用例 | 断言 |
| --- | --- |
| 多行分配 | 同一 `write{}` 内三次 `max → +1ms → advance`，分配值严格递增；提交后全局 MAX 等于最后值，三行 `updated_at` 各自对应 |
| MERGE 路径 | 同一事务内 `update(content/domains/updated_at)` + `advanceUpdatedAt` + `insertSourceLink`；读回正文/domains/水位/来源关联全部正确 |
| 单行推进隔离 | 推进 `org-1` 后 `org-2` 的 `updated_at` 不变，全局 MAX 跟随目标 |
| 缺失行 | `advanceUpdatedAt("missing", ...)` 返回 0，全局水位不变 |
| 跨库隔离 | 两个独立 SQLite 库，推进 A 不影响 B 的水位 |

> 说明：本批**没有**把「违反前置条件」写成已防护；也没有新增条件更新或改 SQL 语义。

## 4. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `:context:persistence-sqldelight:spotlessApply :context:persistence-sqldelight:compileKotlin` | 0 | 生产编译 |
| `:context:persistence-sqldelight:spotlessApply` | 0 | 格式化；`spotless-apply.log` |
| `:context:persistence-sqldelight:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks` | 0 | **176 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 176 = XML 176，**差额 0**；`targeted-test.log` |
| `:context:core:test :context:runtime:test :context:server:test --rerun-tasks` | 0 | 无回归 |

persistence 176 = P4 后的 167 + `PaginationArgumentValidationTest` 4 + `WatermarkContractTest` 5。

## 5. 改动文件与 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `.../sqldelight/ContextOps.kt` | `04c20e6857c6be51d671cfadfc0486409c419d1b37414ee9adee30e6de41f9e3` |
| `.../sqldelight/ContextSqlQueries.kt` | `5db9a8eba85a7981bb03f0db2197b6bc9a697838e74eb666ed6df58e52adcbec` |
| `.../test/PaginationArgumentValidationTest.kt`（新增） | `2b385d01d3163b2b84627d4688258a272111aa6935c96741a4d9d8dd71a20de4` |
| `.../test/WatermarkContractTest.kt`（新增） | `cbcd00732018f623f5f8b16775c84367bd7fc7622039371851a250ce48747467` |

## 6. 未做项与边界

- P3 不新增 CAS / 条件更新，不改签名与 SQL；调用方违约仍会写回回退值（这是裁定保留的边界，文档已写明，不宣称已防护）。
- P2 只覆盖本模块的分页入口；`findByIds` 无分页参数，不涉及。
- 未改 API/Core/Runtime/Server；未与其他修复合并；未 `git add/commit`。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 初验与 Architect 独立复核。

## 7. P2 错误语义订正记录（2026-10-29）

| 项 | 内容 |
| --- | --- |
| 原实现 | `throw persistenceUnavailable(...)` → `ContextPersistenceException(PERSISTENCE_UNAVAILABLE)` |
| 问题 | 调用方参数错误被伪装成存储不可用，误导诊断并可能触发重试 |
| 订正 | `require(limit >= 0)` / `require(offset >= 0)` → `IllegalArgumentException`，与本模块既有 `require` 口径一致，不新增 API 枚举 |
| 测试 | `PaginationArgumentValidationTest` 断言 `IllegalArgumentException`；仍验证负值在 SQL 前拒绝、失败后 `autoCommit` 恢复、执行器可写、库内数据不变 |
| P3 | 未改动（KDoc 与 5 例回归保持） |
| 复跑 | persistence 176 / core 11 / runtime 89 / server 30，源码 `@Test` = XML，差额 0 |
