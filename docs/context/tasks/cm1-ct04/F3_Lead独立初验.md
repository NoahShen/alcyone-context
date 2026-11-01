# CT04-F3 Lead 独立初验（rev5）

## 本次修改（2026-10-28，F3 rev5：第18节两项生产缺陷 + 测试清理后的 Lead 独立初验）

- Architect 第18节：rev4 方向认可，仅剩两项生产缺陷与测试清理。沿用已批准方案，不重写、不整体重实现。Lead 亲自复现两探针红后，交回同一 Paseo worker `8c00d30e`（单一写入者）定点修复；Lead 未写生产/测试代码。
- **红（Lead 独立复现）**：`F3BomProbe` → `failed business persisted through SQL commit=true`（U+FEFF 前缀 COMMIT 绕过）；`F3CloseProbe` → `physical connections still open after executor close=1`（首次 close 失败后引用被提前清空）。
- **生产修复（2 文件）**：
  - `ContextSlot.kt` → `185ecbf9a0c152cb8b5b19ed96725d3a73eb73744fadb9fdd65e348271565ea4`：`releasePoisonedConnection` 先 close，失败保留 `physicalConnection` 且不 dispose 驱动（供全局 `Slot.close` 重试），成功才清所有权；失败只 addSuppressed，持续失败由共享关闭结果报错。
  - `SlotConnectionJdbcDriver.kt` → `79fc3ac1943d9cbdc309ef1330d4c4c45806ef528ebdeff08472f8c97309a56c`：`contentStart` 只补跳 `U+FEFF`（不泛化零宽字符、不改写 SQL 正文），边界检查与 SQLite 实际词法一致。
  - 未改：`ContextDatabaseExecutor.kt`=`8cd7a1a9…`、`RawScopes.kt`=`7949dfed…`。
  - 测试 `F3Rev4CounterExampleTest.kt` → `37022da16a097c2fbf9d46ff80f990952a523568baf8e42c41d023bf98d70632`（基线 88 + 新增 3 = 91；含 R3 测试清理：子资源 `use{}`、撤外层吞异常 `runCatching`）。
- **Lead 独立验证**（非引用 Worker 日志）：`:test :spotlessCheck --rerun-tasks` → exit 0，**91 例 0 失败/错误/跳过**，源码 `@Test`=91=XML 91，差额 0。三探针逐场景无提前退出：`F3Probe` 全绿、`F3BomProbe` `SQL commit=false`、`F3CloseProbe` `still open=0`。
- **Lead 独立变异**：移除 `U+FEFF` 跳过 → BOM 用例 FAILED；恢复“close 前清所有权” → close 两条用例 FAILED（已还原，SHA 回到上述值）。
- **范围**：仅 2 生产文件 + 1 测试文件 + 交付文档；`vfs/`、`common/`、`integration-tests/vfs/` tracked diff 为空；无 git 写操作。

## 历史：本次修改（2026-10-28，F3 rev4 修复后的 Lead 独立初验，提交 Architect 复核）

- F1 已 APPROVED（§7），F2 已 APPROVED（§12），F3 rev3 独立复核未通过（§17 R1～R4）。按 §17 指示：先补正式反例确认红，再按已批准方案 §8 修复。写入权交给按 fresh `list_profiles` 原样应用 `worker` 配置（pi / my_magpie/group/worker，thinking high）创建的 Worker `8c00d30e`（单一写入者；前一代 `c89340d6` 因反复"只分析不落盘"已归档）。Lead 未写生产/测试代码，只做独立复现、审查与初验。未做 CT05；不改 VFS/common/进度入口/AGENTS/Architect 复核文件；不 git add/commit。

## 1. 第 1 步：正式反例先红（Lead 独立复跑确认）

新增 `F3Rev4CounterExampleTest.kt`（SHA `bd3a380156f70924f3340348d22e5758b5f9a3bd4eae744206109808a6ea549d`，5 用例）。Lead 独立执行 `:context:persistence-sqldelight:test --rerun-tasks` → exit 1，88 例 3 失败，与 §17 探针事实逐条对应：

| 用例 | 修前（红）事实 |
| --- | --- |
| R1 unwrap | 作用域内 `Connection.unwrap(Connection)` 返回物理连接，未拒绝 |
| R2 SQL 事务控制 | `COMMIT`/`/*c*/ COMMIT`/行注释 COMMIT/`ROLLBACK`/`BEGIN`/`END`/`SAVEPOINT`/`/*c*/ SAVEPOINT`/`RELEASE`/`...;COMMIT` 共 10 条无一被具名阻断 |
| R3b 取消 | 回调直接抛 `CancellationException` 后停在 `IN_TRANSACTION`，后续 write 报 `cannot begin transaction` |
| R3a/R6 锚点 | 业务失败 rollback 后可复用、异常链无原始 SQL 正文（基线正常） |

红日志：`.local/validation/cm1/worker/ct04-f3-rev4/probe-before.log`。

## 2. 第 2 步：生产修复（4 文件，SHA 已核对）

| 文件 | rev4 SHA-256 |
| --- | --- |
| `ContextDatabaseExecutor.kt` | `8cd7a1a9b388f18a7e52c9226de3f0186c5a7c226ff802d9e081b8e84a326564` |
| `ContextSlot.kt` | `48d7bbf98ef98e4ea2716337b8c9da2a2a30148e247239ae1f1b08a1714f2122` |
| `SlotConnectionJdbcDriver.kt` | `0966d6dd3c999694f402405ef23382f89353bdbdfbb1e152508575ddbc6d3220` |
| `RawScopes.kt` | `7949dfeddfc9598814f0e2d3745af7808b5857c8d62eb59a079b22c0739556fe` |

- **R1（统一默认拒绝）**：`ScopedJdbc` 以 `java.lang.reflect.Proxy` + 精确到参数类型的签名表替换全部 `by delegate`；只有 `Connection.createStatement()`/`prepareStatement(String)`、`Statement.executeQuery/executeUpdate/execute(String)`/`close`、`PreparedStatement.executeQuery/executeUpdate/execute/getUpdateCount/close` 与显式列出的 `setXxx` 标量绑定、`ResultSet.next/wasNull/close` 与索引/标签标量读取被放行；其余（`unwrap`/`isWrapperFor`/元数据/反向对象出口/未列明重载）一律抛 `ContextPersistenceException(PERSISTENCE_UNAVAILABLE)`。`ScopedSlotDriver` 拒绝 `begin/end/rollback/newTransaction/currentTransaction`。
- **R2（SQL 统一边界）**：`SqlBoundaryGuard` 按顶层分号切句（识别字符串、标识符、行/块注释内的分号），逐句跳过前导空白与注释后取首 token，命中 `BEGIN/COMMIT/END/ROLLBACK/SAVEPOINT/RELEASE` 即具名拒绝；多语句同样阻断。已接入 raw 读写四入口、statement/prepared 执行入口、driver `execute/executeQuery`、prepared 创建时。
- **R3（统一终结）**：`runBoundedTransaction` 供 write/read 共用——成功 commit 一次；业务异常/取消 rollback 一次，原异常/原取消对象为主，收尾失败仅 `addSuppressed`；`commitAttempted` 后绝不回滚；`begin` 失败 poison；`ContextSlot` 五态 + `checkScope` 每次操作校验中毒；`releasePoisonedConnection` 在作用域收尾边界关闭连接，不在 Slot 线程 await 自身。
- **R6**：委托调用 `SQLException` 在边界安全映射，`CancellationException` 原样传播。

## 3. 第 3 步：Lead 独立验证（非引用 Worker 日志）

| 验证 | 结果 |
| --- | --- |
| `:test :spotlessCheck --rerun-tasks` | exit 0，**88 例 0 失败/错误/跳过**，源码 `@Test`=88=XML 88，差额 0 |
| Architect 适配版 `F3Probe`（`.local/validation/cm1/architect/ct04/f3-rev3/F3Probe.java`，与 Worker 副本 diff 为空）逐场景 | 13 项全绿、无提前退出：null result=true；old driver / escaped default / escaped overloaded / **unwrap escape** 全部具名拒绝；read 原异常保留=true；**manual commit 落库=false**；**SQL commit 落库=false**；**comment savepoint 被点名 SAVEPOINT 阻断**；**取消后 healthy write admitted=true**；commit 失败后 write=false；restore 失败后 read=false |
| Lead 独立变异 | M1 关闭 `SqlBoundaryGuard` 事务控制判定 → R2 用例 FAILED；M2 关闭失败/取消 rollback → R3a/R3b/R6 用例 FAILED（还原后 SHA 回到 `8cd7a1a9…`/`0966d6dd…`） |
| 改动范围 | 仅 4 生产文件 + 2 测试文件 + 交付文档；`git diff --stat -- vfs/ common/ integration-tests/vfs/` 无改动；无 git 写操作 |

证据：`.local/validation/cm1/lead/ct04-f3/lead-f3-rev4-final.log`、`probe-arch/lead-probe.log`；Worker 侧 `ct04-f3-rev4/`（probe-before/after、targeted-test、spotless、final-source-hashes.json）。

## 判定

§17 的 R1/R2/R3/R4 已在正式测试与适配探针中逐条落实：红→绿可复现、变异可咬住、改动范围受控。**F3 rev4 初验通过，提交 Architect 独立复核**；是否收口 F3 由 Architect 判定。CT04 仍需整体集成验收，**CT05 继续禁止启动**。

## 未关闭项

- CT04 整体集成验收（F1/F2/F3 合一后整体回归）后方可放行 CT05；
- 多语句整体阻断严于最小要求，如 CT05 需要放开需另行评审。
