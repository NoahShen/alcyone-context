# CM1 全面复查：Lead 只读审计（CT04/CT05 Persistence）

**日期**：2026-10-29
**方式**：只读源码审计（未改生产代码、未运行构建，避免与 Architect 探针 XML 冲突）
**范围**：`context/persistence-sqldelight` 真实事务边界、异常恢复、SQL 约束、Repository 查询与 Port 契约一致性
**结论**：**发现 1 项真实缺陷（P1）+ 3 项契约/健壮性边界（P2/P3/P4）+ 未验证边界清单**。均不涉及 CT07 已验收的 HTTP/Core 语义。

## P1（真实缺陷）：全局 `Slot.close()` 在物理 close 前丢弃连接所有权

`ContextSlot.kt:396-401`

```kotlin
override fun close() {
    if (!disposed.compareAndSet(false, true)) return   // 396-397
    physicalDriver?.dispose()                          // 399
    val physical = physicalConnection                  // 400
    physicalConnection = null                          // 401  ← 先清空
    ...
        ContextSqliteErrors.map("closing ...") { physical.close() }   // 414  ← 后关闭
```

- **问题**：`physicalConnection` 在 `physical.close()` **之前**置 null。若 `close()` 抛异常（line 414 的 map 会包装后抛出），连接引用已丢失，且 `disposed=true` 使重试直接 `return`（397）——该物理连接**永久不可再释放**。
- **与已修复项的关系**：这正是 F2 rev5 修复 `releasePoisonedConnection`（`ContextSlot.kt:219-233`，close 成功才清所有权）时**未同步**的另一条路径。中毒连接经 `releasePoisonedConnection` 失败 → 保留引用 → 全局 `Slot.close()` 再次失败 → 引用丢失，仍会泄漏（只是把窗口推迟了一次尝试）。
- **影响面**：`ContextDatabaseExecutor.releaseAll()`（`ContextDatabaseExecutor.kt:441-459`）对每个 Slot `runCatching{close()}` 只尝试**一次**，失败后 `released=true`（442-445）阻断后续重试；因此该路径无二次机会。
- **可复现方式**：注入一个 `close()` 抛 SQLException 的包装 Connection（真实文件 SQLite + Driver 代理，仅包 `close`），走 `ContextDatabaseFactory.open(...).close()`；断言该真实 Connection 仍 `isClosed=false` 且第二次 `close()` 直接返回。属于确定性、非概率。
- **修复建议（不改语义）**：`Slot.close()` 改为与 `releasePoisonedConnection` 同构——先 close、成功才 `physicalConnection=null` + `disposed` 终态；失败保留引用并允许 `releaseAll` 二次尝试（或 `released` 标志改为「全部成功才置位」）。不新增公共 API。

## P2（契约边界）：`limit` / `offset` 未校验，负值改变语义

- `ContextOps.kt:343/355/445/565-566/748-749` 直接把 `limit.toLong()`、`offset.toLong()` 绑定进 SQL，**全模块无 `require`/范围校验**（`grep 'require|check('` 在 ContextOps 无命中）。
- SQLite 语义：`LIMIT` 为**负值表示无上界**；负 `OFFSET` 按 0 处理。因此 `scanPage(offset=0, limit=-1)` 会返回**整表**（无界读取），`sessionWindow(limit=-1)` 同样无界，与「分页」契约不一致。
- 与已记录边界的关系：CT05 文档只记录了「`findByIds` 顺序不保证、Organized 正查 N+1」，未记录 limit/offset 负值语义。
- **未验证**：现有正式测试未覆盖 `limit<=0` / `offset<0`（CT05 测试仅覆盖正常分页端点）。
- **建议**：在 Ops 层加 `require(limit >= 0)`、`require(offset >= 0)`（或统一映射 `INVALID_ARGUMENT`），并补正式反例；不新增公共契约。

## P3（契约边界）：`advanceUpdatedAt` 不校验严格递增

- `ContextSqlQueries.kt` 的 `ADVANCE_UPDATED_AT = "UPDATE organized_memory SET updated_at = ? WHERE id = ?"` 无「next > 当前」条件，`ContextOps.kt:686-694` 只返回行数。
- 契约（`OrganizedMemoryTxOps.advanceUpdatedAt` KDoc）要求调用方在同一写事务内先 `maxUpdatedAt()` 再分配严格更大值；**实现不强制**。若调用方传回退值，水位会**回退**且返回 1（伪成功）。
- 对比：`CAS_UPDATE_CHECKPOINT` 已带 `AND ? > last_processed_turn_id`，`Checkpoint` 有强制；`Organized` 水位没有。
- 这是 CT05 已复核接受的「不宣称自动修正任意 next」的口径，但**回退可成功写入**这一事实未被测试覆盖，属未验证边界。
- **建议**：要么 SQL 加 `AND ? > updated_at`（与 Checkpoint 对齐，返回 0 表示未推进），要么在正式测试中明确断言当前行为并记录为已知边界。需 Architect 裁定（涉及契约强度）。

## P4（异常恢复观察项）：读路径 poison 语义正确但 `queryRaw` 无显式事务

- `ContextDatabaseExecutor.kt:130-142` 的 `read` 走 `runBoundedTransaction`（begin/commit/rollback），poison 检查在 `acquireReadSlot` 与 `read` 入口均有；已核实正确。
- `queryRaw`（`~146-175`）/`updateRaw`（`~178-200`）各自构造临时 `ScopeSession`、直接执行单语句，**无显式事务**（依赖 autoCommit）。这符合 CT04 设计（单语句），但需注意：`updateRaw` 在 `writeSlot` 上执行时若语句自身失败，连接状态不变、不 poison；属预期，但**未被 CT05/CT07 正式测试显式覆盖**「updateRaw 失败不 poison、后续 write 可继续」。
- **未验证**：该边界（低风险，建议补一条断言即可）。

## 已核实无问题的部分（逐项）

| 项 | 位置 | 结论 |
| --- | --- | --- |
| 写事务成功/失败/取消终结互斥 | `ContextDatabaseExecutor.kt:212-245` | commitAttempted 后不回滚；取消 rollback 一次；主异常优先、收尾失败 suppressed —— 与 F3 验收一致 |
| begin/commit/rollback/恢复失败 → poison | `ContextSlot.kt:254-322` | 四处均 poison，恢复仅在 COMMITTED/ROLLED_BACK 后执行 —— 正确 |
| 唯一约束竞争重读 | `IngestionService`（Core） | SQLSTATE 23000 → 重读权威记录判 DUPLICATE/CONFLICT，非 23000 上抛 —— CT07 已验 |
| 部分初始化失败逆序释放 | `startSlots`（Executor） | 逐 Slot 登记、逆序 `runCatching{close}` + suppressed —— 正确 |
| 读 Slot 分配跳过中毒 Slot | `acquireReadSlot` | 轮转时 `isPoisoned` 跳过并释放许可；全忙回退路径 checkPoison —— 正确 |
| 参数化 SQL（无拼接） | `ContextSqlQueries.kt` | 全部 `?` 占位；`findByIds` 空集合提前返回不执行 SQL —— 正确 |
| JSON/时间严格类型 | `ContextJsonCodecs.kt` | `isString` 显式校验；时间 `TimeCodec` —— CT05 已验 |
| 外键/唯一/CHECK 约束 | `ContextSchema.sq` | 六表五索引、RESTRICT、JSON array CHECK —— CT04/CT05 已验 |
| 关闭共享结果 | `ContextDatabaseExecutor.kt` 关闭段 | 单一 protocol + latch，失败共享 —— CT04/F2 已验 |

## 未验证边界（明确列出，未发现缺陷但无测试证据）

1. `Slot.close()` 物理 close 失败后的重试路径（即 P1 的失败分支，无正式测试）。
2. `limit<=0` / `offset<0` 的分页行为（P2）。
3. `advanceUpdatedAt` 传入回退值时是否允许回退（P3）。
4. `updateRaw` 语句失败后不 poison、后续 write 可继续（P4）。
5. 进程崩溃/断电后 WAL 恢复（CT04 明确属 CM6，不在 CM1）。
6. 跨进程并发写同一业务键（CM1 单写者契约内不承诺）。

## F1（通知关闭虚假完成）设计建议

Architect 反例：`notifier` 在 `finally` 内 `withContext(NonCancellable){await gate}`，`close()` 返回且状态 CLOSED，但真实 `finally` 仍未退出；`notificationJobs.clear()` 使计数为 0 → 虚报资源退出。

**设计（不盲目 join）**：

1. **停止准入**（已有 `notificationsClosed`）后，`close` 需要的是「真实完成」而非「计数为 0」：
   - 维护**每个 Job 的 `CompletableDeferred` 完成信号**，`close` 等待 `allOf(completions)` 而非 `join` 单个 Job——避免通知体内 `withContext(NonCancellable)` 阻塞 `join` 时把 close 卡死。
   - 关键：**不把 `notificationJobs.clear()` 当作完成证据**；`clear` 只能在所有 completion 完成后执行，或改为仅在 `invokeOnCompletion` 中移除（现有实现已在 `invokeOnCompletion` 移除，问题在于 close 用计数判断而非等待）。
2. **避免通知内重入 close 自等待**：
   - 在通知体执行线程上标记 `insideNotification`（ThreadLocal），`close()`/`closeAsync()` 命中即具名拒绝（复用 F2 的「回调内 close 拒绝」模式），不等待自身。
   - 或 `close` 等待前检测「当前协程是否属于某个被等待的通知 Job」，命中则跳过等待该 Job 并记录（避免自死锁），但不宣称已退出——需明确该分支语义。
   - Lead 建议采用**具名拒绝**（更简单、可测）：通知内不允许调用 close，返回 `CLOSED`/`ILLEGAL_OPERATION`。
3. **关闭等待者取消不得丢清理**：等待用 `withContext(NonCancellable)`；等待者取消只取消自身 await，共享清理继续。
4. **可测断言**：
   - 注入阻塞型 notifier（`await` 有界 gate，`finally` 中 `NonCancellable` 等待），close 在 gate 未放行前**必须不返回**；放行后返回且状态 CLOSED。
   - close 返回后，通知体的 `finally` 已完成（用一个 `AtomicBoolean finished` 断言为 true），而不是看 Job 计数。
   - 通知内调用 close → 具名拒绝、不自死锁（超时护栏）。
5. **不改 API、不建 Worker/Cron**；仅 runtime 内部协议与测试。

## F2/F3/F4 转交（Architect 已复现，我确认收到并纳入待修）

- **F2** HTTP 非法 UTF-8（`0xff`）返回 201 而非 400：需在反序列化前做严格 UTF-8 解码校验，非法编码 → `INVALID_TURN`/400 且零行；合法 U+FFFD/中文不受影响。
- **F3** `Registry.of` 用 `associateBy` 静默覆盖重复 `runtimeId`：需拒绝重复 Key；并补 `ServerConfig` 直接构造时的 client 空白 / Map key 与 `runtimeId` 一致校验；统一 YAML/直接构造/Registry 三入口的具名配置验证（复制后验证、不打印 Key）。
- **F4** 文档入口过期（`docs/context/{README,开发进度,开发计划}.md`、`AGENTS.md` 当前段仍 CT06/5 或 6 of 7）：需更新为「CM1 既有验收通过，但本次全面复查存在开放项」；历史记录保留。

## 下一步（等 Architect 裁定后执行）

1. P1 由 Worker 定点修复（`Slot.close` 所有权 + `releaseAll` 重试语义），补真实反例。
2. P2 由 Architect 裁定是否加 `require`（建议加）；P3 需裁定「SQL 强制递增」还是「记录为已知边界」。
3. F1/F2/F3/F4 按逐任务机制交同一 Worker 修复，Lead 逐项初验。
4. **上述闭环前不启动 CM2 实现**；本轮不跑全量以免覆盖探针 XML。
