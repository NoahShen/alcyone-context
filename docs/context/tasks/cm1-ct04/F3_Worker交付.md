# CT04-F3 Worker 交付（rev5）

**任务**：CM1 CT04-F3 事务失败处理与作用域边界（R3 作用域/driver逃逸、R5 事务失败、R6 剩余异常链），rev5 版本
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db（Paseo lead 配置）
**状态**：Worker 自测通过（rev5），待 Lead 初验与 Architect 独立复核；不得自标 DONE
**日期**：2026-10-28

## 本次修改（2026-10-28，CT04-F3 rev5：第18节定点修复 R1 BOM 词法 + R2 连接所有权 + R3 测试清理）

按 Architect 复核第 18 节与 Lead 口径纠正（只补齐已实测 U+FEFF、不泛化零宽字符、不改写 SQL 正文）定点修复；只改 4 个生产文件与正式测试，保留 rev4 已通过行为，不做 CT05。

**先红后绿**：先补 3 条正式回归并实跑确认 3 红（`.local/validation/cm1/worker/ct04-f3-rev5/probe-before.log`，91 例 3 失败），再改生产；修后 91 例全绿。

### 改动文件清单 + SHA（rev5 最终产物）

| 文件 | rev4 | rev5 SHA-256 |
| --- | --- | --- |
| `ContextDatabaseExecutor.kt`（未改） | `8cd7a1a9…` | `8cd7a1a9b388f18a7e52c9226de3f0186c5a7c226ff802d9e081b8e84a326564` |
| `ContextSlot.kt` | `48d7bbf9…` | `185ecbf9a0c152cb8b5b19ed96725d3a73eb73744fadb9fdd65e348271565ea4` |
| `SlotConnectionJdbcDriver.kt` | `0966d6dd…` | `79fc3ac1943d9cbdc309ef1330d4c4c45806ef528ebdeff08472f8c97309a56c` |
| `RawScopes.kt`（未改） | `7949dfed…` | `7949dfeddfc9598814f0e2d3745af7808b5857c8d62eb59a079b22c0739556fe` |
| `F3Rev4CounterExampleTest.kt` | `bd3a3801…` | `37022da16a097c2fbf9d46ff80f990952a523568baf8e42c41d023bf98d70632` |
| `F3TransactionFailureAndScopeBoundaryTest.kt`（未改） | `4d679a87…` | `4d679a8796370b023ff97ff616586c6fe53488de871920b7bc1442d21aca4872` |

全部哈希：`.local/validation/cm1/worker/ct04-f3-rev5/final-source-hashes.json`。

### R1～R3 落点（行号按 rev5 最终源码）

- **R1 前导 BOM 词法边界**：`SlotConnectionJdbcDriver.kt:505` `SqlBoundaryGuard.contentStart` 在跳过空白与行/块注释之外只补跳过 `U+FEFF`（`char.isWhitespace() || char == '\uFEFF'`），SQL 正文原样传给 SQLite；不做通用零宽字符/未知前缀处理，避免误伤正常 DML/查询/绑定。既有 `SqlBoundaryGuard.check` 的「逐条首 token 命中 BEGIN/COMMIT/END/ROLLBACK/SAVEPOINT/RELEASE 即拒绝 + 多语句阻断」与字符串/标识符内分号规则不变，raw/prepared/driver/statement 全部入口自动继承。
- **R2 中毒连接所有权**：`ContextSlot.kt:220` `releasePoisonedConnection` 先尝试 `physical.close()`，**失败时保留 `physicalConnection` 且不 dispose 驱动**（失败只 `addSuppressed` 到中毒原因/记录），成功后才置 null 并 dispose；全局 `Slot.close` 因此可再次尝试，持续失败时 `releaseAll` 汇总失败并由同步/异步 close 的共享结果抛出（不静默成功）。不恢复 autoCommit、不复用 poisoned、不在 Slot 线程 await 自身，可重复安全调用。
- **R3 测试资源清理**：`F3Rev4CounterExampleTest.kt:53` unwrap 用例改为 scope 内 `use{}` 嵌套关闭 `Statement`/`PreparedStatement`/`ResultSet`，删除吞掉执行器意外失败的外层 `runCatching`，只对预期 unwrap 拒绝做局部捕获；正常路径断言不变、未新增抽象。

### 红 → 绿（实跑，基线 88 + 新增 3 = 91）

| 新增用例 | rev5 前（红） | rev5 后（绿） |
| --- | --- | --- |
| `rev5 bom prefixed transaction control is rejected on every sql entry` | `update(<BOM>COMMIT)` 被 SQLite 接受，失败业务落库 watermark=88；后续入口因 Slot 中毒继续红 | 3 个 BOM 前缀 × update/updatePrepared/driver.execute/driver.executeQuery 全部具名拒绝；多语句仍拒；字符串内分号、普通 DML/查询/prepared 绑定照常（水位 7 提交成功） |
| `rev5 poisoned connection close failure keeps ownership for the global close` | `executor.close()` 后仍有 1 条物理连接未关闭（首次 close 失败即丢引用） | `executor.close()` 重试成功，`still open=0` |
| `rev5 persistent close failure is reported by the shared close result` | 持续 close 失败时 `executor.close()` 静默成功（null） | 首次与重复 close 都抛共享失败，连接确实未释放（证明非静默） |
| rev4 既有 5 例（含 R3 测试清理后的 unwrap 用例） | 绿 | 绿（清理后仍绿） |

### 三探针逐场景（`.local/validation/cm1/worker/ct04-f3-rev5/probe-after.log`，javac/java 均退出 0）

- **F3Probe**：与 rev4 一致全部为绿（null 返回、旧 driver/逃逸/unwrap 拒绝、读原异常保留、manual/SQL 提交落库=false、取消后复用=true、commit/restore 失败后请求拒绝）。
- **F3BomProbe**：`failed business persisted through SQL commit=false`（BOM 前缀 COMMIT 已被阻断），其余场景同 F3Probe 绿。
- **F3CloseProbe**：`physical connections still open after executor close=0`（首次 close 失败后全局重试成功释放），其余场景绿。

### 命令退码 / 日志

| 命令 | 退码 | 日志 |
| --- | --- | --- |
| `:context:persistence-sqldelight:test --rerun-tasks`（修前红） | 1（91 例 3 失败） | `probe-before.log` |
| `:context:persistence-sqldelight:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks`（修后） | 0（91 例 0 失败/错误/跳过；源码行首 `@Test` 91 = XML 91，差额 0） | `targeted-test.log` |
| `spotlessApply` | 0 | `spotless-apply.log` |
| `javac` / `java`（F3Probe/F3BomProbe/F3CloseProbe，项目 JDK 21） | 0 / 0 | `probe-after.log` |

### 未关闭项 / 边界

- **未做 CT05**；**未自标 DONE**：F3 rev5 仅完成 Worker 自测，须 Lead 独立初验与 Architect 独立复核；CT04 仍 IN_REVIEW，CT05 未放行。
- 本轮只跑持久化模块与三个 F3 探针，未重跑全工程回归/本地发布/消费工程；F1/F2 结论沿用既有独立证据。
- R1 只补已实测的 U+FEFF；其它未知前缀不额外泛化（避免误伤正常 SQL 消费）。
- 持续 close 失败时连接无法释放属注入故障的必然结果，本轮只保证失败可见（共享关闭结果抛出），不做资源回收兜底。


## 本次修改（2026-10-28，CT04-F3 rev4：R1 统一默认拒绝 + R2 SQL 事务控制 + R3 取消终结）

按第 17 节未通过项与《F3_事务失败与作用域边界方案》第 8 节修复；只改 `context/persistence-sqldelight/src/main/.../` 4 个生产文件与正式测试，不新增抽象、不重写 SQLDelight、不做 CT05、不改 VFS/common/进度入口/AGENTS/Architect 复核文件。

**先红后绿**：先落 `F3Rev4CounterExampleTest.kt`（5 例）并实跑确认 3 红，再改生产代码；红证据 `.local/validation/cm1/worker/ct04-f3-rev4/probe-before.log`，绿证据同目录 `targeted-test.log` / `probe-after.log`。

### 改动文件清单 + SHA（rev4 最终产物）

| 文件 | baseline（rev3） | rev4 SHA-256 |
| --- | --- | --- |
| `ContextDatabaseExecutor.kt` | `2e0fac1e…` | `8cd7a1a9b388f18a7e52c9226de3f0186c5a7c226ff802d9e081b8e84a326564` |
| `ContextSlot.kt` | `375b9522…` | `48d7bbf98ef98e4ea2716337b8c9da2a2a30148e247239ae1f1b08a1714f2122` |
| `SlotConnectionJdbcDriver.kt` | `fea1521a…` | `0966d6dd3c999694f402405ef23382f89353bdbdfbb1e152508575ddbc6d3220` |
| `RawScopes.kt` | `bee5121f…` | `7949dfeddfc9598814f0e2d3745af7808b5857c8d62eb59a079b22c0739556fe` |
| `F3Rev4CounterExampleTest.kt`（新增） | — | `bd3a380156f70924f3340348d22e5758b5f9a3bd4eae744206109808a6ea549d` |
| `F3TransactionFailureAndScopeBoundaryTest.kt`（未改） | `4d679a87…` | `4d679a8796370b023ff97ff616586c6fe53488de871920b7bc1442d21aca4872` |

全部哈希：`.local/validation/cm1/worker/ct04-f3-rev4/final-source-hashes.json`。

### R1～R4 落点（行号按 rev4 最终源码）

- **R1 统一默认拒绝（未列明一律阻断）**：`SlotConnectionJdbcDriver.kt:192` `ScopedJdbc` 用 `java.lang.reflect.Proxy` + 精确到参数类型的签名表替换 `Connection/Statement/PreparedStatement/ResultSet by delegate`；`connection()`（:194）只放行 `createStatement()` / `prepareStatement(String)`；`statement()`（:212）只放行 `executeQuery(String)` / `executeUpdate(String)` / `execute(String)` / `close()`；`preparedStatement()`（:240）只放行 `executeQuery()/executeUpdate()/execute()/getUpdateCount()/close()` 与显式列出的 `setNull(int,int)`、`setXxx(int, 标量)`、`setObject(int, Object, int)`（仅标量）；`resultSet()`（:285）只放行 `next()/wasNull()/close()` 与索引/标签版标量读取；`proxy()`（:312）对未登记方法（含 `unwrap`/`isWrapperFor`/`getMetaData`/`Statement.getConnection`/`ResultSet.getStatement`/全部带参重载）抛具名 `PERSISTENCE_UNAVAILABLE`，`Object` 方法只回安全身份；`ScopedSlotDriver`（:96）显式拒绝 `Connection.beginTransaction/endTransaction/rollbackTransaction` 与 `newTransaction/currentTransaction`，`getConnection()` 返回受控代理，`close()` 不触碰共享 Slot 驱动/连接。
- **R2 SQL 事务控制统一阻断**：`SlotConnectionJdbcDriver.kt:411` `SqlBoundaryGuard.check` 按顶层分号拆语句（识别字符串、双引号/方括号标识符、行/块注释内的分号），逐条跳过前导空白/注释取首 token，命中 `BEGIN/COMMIT/END/ROLLBACK/SAVEPOINT/RELEASE` 即拒绝并在消息中点名关键字；多语句一律阻断。调用点：`RawScopes.kt:47/61/126/139/155/168`（读/写 scope 的 `query/queryPrepared/update/updatePrepared`）、`SlotConnectionJdbcDriver.kt:221/226/231`（`ScopedStatement` 三个执行入口）、`prepareStatement(String)`（:204，prepared 在 prepare 时判定固定 SQL）、`ScopedSlotDriver.execute/executeQuery`（:137/:149 起，guard 在 :144/:156，driver 路径）、`ContextDatabaseExecutor.kt:159/184`（`queryRaw`/`updateRaw`）。
- **R3 取消与失败统一终结**：`ContextDatabaseExecutor.kt:212` `runBoundedTransaction`——成功 commit 一次；业务异常/取消（含回调直接抛 `CancellationException`）rollback 一次，原异常或原取消对象为主，收尾失败只 `addSuppressed`（不改 cause）；commit 已尝试（`:226`）或已 poison 时不再回滚；`write()`（:111）/`read()`（:128）共用该终结路径。`ContextSlot.kt:249/270/291/308` begin/commit/rollback/恢复失败统一 `poison()`，`rollbackTransaction` 成功恢复 autoCommit 后转 IDLE，故取消后可复用。
- **R4 独占权后 poison 与失败连接释放**：`ContextDatabaseExecutor.kt:116/131`（write/read 取得独占权后 `checkPoison`）、`:158/183`（queryRaw/updateRaw）、`:391` `acquireReadSlot` 跳过中毒读 Slot（健康 Slot 不受影响）；`ContextSlot.kt:120` `checkScope` 每次句柄操作都校验作用域身份与中毒；`:218` `releasePoisonedConnection` 在作用域失效、同步 JDBC 已退出的收尾边界尽力关闭物理连接（不恢复 autoCommit、不重试未知事务、不在 Slot 线程 shutdown 自身），失败仅记录；`ContextDatabaseExecutor.kt:319` 在 `withScope` finally 调用，F2 关闭协议不变。

### 红 → 绿（实跑）

| 用例（`F3Rev4CounterExampleTest`） | rev4 前 | rev4 后 |
| --- | --- | --- |
| R1 `unwrap escape`（Connection/Statement/PreparedStatement/ResultSet 的 `unwrap`） | 红：`Connection.unwrap` 返回真实连接 | 绿：全部具名拒绝，数据库未被修改 |
| R2 十条事务控制（`COMMIT`、`/*c*/ COMMIT`、行注释+COMMIT、`ROLLBACK`、`BEGIN`、`END`、`SAVEPOINT`、`/*c*/ SAVEPOINT`、`RELEASE`、`…;COMMIT`） | 红：10 条全部未按事务控制阻断 | 绿：10 条全部具名拒绝并点名关键字，独立连接不可见业务水位 |
| R3 取消后复用 | 红：回调直接抛 `CancellationException` 后 `IN_TRANSACTION`，后续 write 被拒 | 绿：只 rollback 一次、取消原样传播、失败写不可见、后续 write 成功 |
| R3' 业务失败后复用（锚点） | 绿 | 绿 |
| R6 异常链不含原始 SQL（锚点） | 绿 | 绿 |

Architect 原探针 `F3Probe.java`（逐场景，无提前退出；`.local/validation/cm1/worker/ct04-f3-rev4/probe-after.log`，javac/java 均退出 0）：

`null result returned=true`；`old driver reuse` 拒绝；`escaped default createStatement` 拒绝（session 失效）；`escaped overloaded` 拒绝；`unwrap escape` 拒绝；`ordinary read callback failure retained=true`；`failed business persisted through manual commit=false`；`failed business persisted through SQL commit=false`；`comment savepoint` 拒绝（点名 SAVEPOINT）；`healthy write after cancellation admitted=true`；`write callback admitted after commit failure=false`；`read original failure retained=true`；`read callback admitted after restore failure=false`。撤回 rev3 文档中「环境无 JDK 21、无法运行探针」的表述——本轮用项目内 JDK 21 正常编译并逐场景执行。

### 命令退码 / 日志

| 命令 | 退码 | 日志 |
| --- | --- | --- |
| `./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks` | 0（88 例 0 失败/错误/跳过） | `targeted-test.log` |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessApply` | 0 | `spotless-apply.log` |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessCheck --rerun-tasks` | 0 | `spotless-check.log` |
| `javac` / `java`（F3Probe，项目 JDK 21） | 0 / 0 | `probe-after.log` |
| 红基线（rev4 生产修复前） | 1（3 失败） | `probe-before.log` |

源码行首 `@Test` = 88，模块 XML tests = 88，差额 0。

### 未关闭项 / 边界

- **未做 CT05**：`ReadOps/TxOps`、`ContextDatabase` 接线属 CT05。
- **未自标 DONE**：F3 rev4 仅完成 Worker 自测，须 Lead 独立初验与 Architect 独立复核；CT04 仍 IN_REVIEW，CT05 未放行。
- 本轮只跑持久化模块定向测试与 F3 探针，未重复全工程回归/本地发布/消费工程；F1/F2 结论沿用既有独立证据（本轮未改其相关路径语义）。
- `ResultSet.getObject`、LOB/Array/流、`Statement.getConnection` 等出口按第 8 节明确拒绝，未扩大允许面；SQLDelight 生成查询所需最小面（含 `PreparedStatement.execute()/getUpdateCount()`）保持可用。
- 多语句一律阻断属于比「仅阻断事务控制」更严的选择，若 CT05 需要多语句需另行评审。

## 本次修改（2026-10-28，CT04-F3 rev3 事务失败与作用域边界修复）

按 Lead rev3 方案与第 8 节 Architect 裁定实现 F3，生产改动限定 `context/persistence-sqldelight/src/main/.../` 下的 4 个文件；不新增抽象、不重写 SQLDelight、复用成熟 `JdbcDriver`；不做 CT05 业务接线；不改 VFS/common/进度入口/AGENTS/Architect 复核文件。

## 改动文件清单 + SHA

| 文件 | baseline SHA | final SHA | 落点 |
| --- | --- | --- | --- |
| `ContextDatabaseExecutor.kt` | `82b158e1…` | `2e0fac1e…` | R5 写/读事务状态转移：写取消 rollback 一次失败则 poison，原 CancellationException 为主；业务异常 rollback 一次失败则 poison，原异常为主；catch 不再重复 rollback；读回调失败 rollback 一次，不再无条件 commit；write 恢复可空返回契约，允许合法 null 返回；withScope 保持 scope 生命周期设置兼容 |
| `ContextSlot.kt` | `11449503…` | `375b9522…` | txState 五态 IDLE/IN_TRANSACTION/COMMITTED/ROLLED_BACK/POISONED；poisonCause 字段；beginTransaction 先 checkPoison 再检查 IDLE；commit/rollback 失败置 POISONED（不尝试 rollback/commit，不恢复 autoCommit），恢复 autoCommit 失败亦置 POISONED；restoreAutoCommitIfActive 仅在 COMMITTED/ROLLED_BACK 时恢复；checkScope 新增 currentScope 身份校验；poison() 设置 txState=POISONED 和 poisonCause |
| `SlotConnectionJdbcDriver.kt` | `f344eca3…` | `fea1521a…` | ScopedSlotDriver(slot, session)：getConnection() 先 checkScope 返回 ScopedConnection；close() 不置共享 slot.driver.disposed；addListener/removeListener/notifyListeners/getMajorVersion/getMinorVersion/jdbcCompliant/getParentLogger 委托父类；不暴露 newTransaction/currentTransaction 等事务控制 API；ScopedConnection/ScopedStatement/ScopedPreparedStatement/ScopedResultSet 全部抛具名 PERSISTENCE_UNAVAILABLE 对未列明方法（commit/rollback/setAutoCommit/close/getConnection/unwrap/getMetaData/getStatement/getObject/LOB/Array/流等）；Statement/PreparedStatement/ResultSet close 委托 delegate 正常释放语句/结果集资源，不关连接；setObject 仅允许经验证的标量类型与 SQL 类型三参签名 |
| `RawScopes.kt` | `c4a18695…` | `bee5121f…` | rejectSavepoint 处理前导空白与行/块注释，匹配 SAVEPOINT/RELEASE；sqlDriver() 返回 slot.scopedDriver(session)（受作用域保护的驱动） |

全部哈希见 `.local/validation/cm1/worker/ct04-f3-rev3/final-source-hashes.json`。

## 测试

模块 `./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks`：83 例全绿，0 失败/错误/跳过（源码行首 @Test 83 = XML tests 83，差额 0）。
`spotlessApply` + `spotlessCheck --rerun-tasks`：通过。

## 探针红绿记录（F3Probe，Architect 原探针，`.local/validation/cm1/architect/ct04/f3/F3Probe.java`）

原探针基线（rev2 已验证为红）：

| 反例 | 红（修前） | 状态 |
| --- | --- | --- |
| null result | `write result missing`（应允许 null） | 已修复：write 恢复可空返回契约 |
| old driver revived in later scope | `true`（DEFECT） | 已修复：旧句柄在后续 scope 被拒绝 |
| prepared next scope | `FAILED`（应成功） | 已修复：新 scope 内 prepared 正常成功 |
| escaped physical Connection updated outside scope | `true`（DEFECT） | 已修复：ScopedConnection 禁止事务控制与 close |
| write callback admitted after commit failure | `true`（DEFECT） | 已修复：commit 失败后新 write 被拒 |
| read original failure retained | `false`（DEFECT） | 已修复：原异常作为主异常保留 |
| read callback admitted after restore failure | `true`（DEFECT） | 已修复：restore 失败后新 read 被拒 |

注：原 F3Probe.java 需按当前 API 适配重新编译（当前环境无 JDK 21，原探针编译遇到类文件版本不匹配），上述行为以 83 例正式测试验证为准。

## 命令退码 / 日志路径

| 命令 | 退码 | 日志 |
| --- | --- | --- |
| `./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks` | 0 | 模块 test 任务 |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessApply` | 0 | spotlessApply 日志 |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessCheck --rerun-tasks` | 0 | spotlessCheck 日志 |

证据目录：`.local/validation/cm1/worker/ct04-f3-rev3/`（探针前后日志、targeted-test.log、spotless 日志、final-source-hashes.json）。

## R3/R5/R6 落点行号（按最终源码）

- **R3**：`ContextSlot.kt` `checkScope()` 新增 `currentScope !== session` 身份校验；`SlotConnectionJdbcDriver.kt` `ScopedSlotDriver`、`ScopedConnection`、`ScopedStatement`、`ScopedPreparedStatement`、`ScopedResultSet` 全部抛具名 `PERSISTENCE_UNAVAILABLE` 对未列明方法；`RawScopes.kt` `rejectSavepoint` 剥离前导注释/空白后匹配 `SAVEPOINT`/`RELEASE`；`sqlDriver()` 返回 `slot.scopedDriver(session)`。
- **R5**：`ContextSlot.kt` `TxState` 五态、`commitTransaction()`/`rollbackTransaction()`（失败置 POISONED、不尝试逆向操作、恢复 autoCommit 失败亦置 POISONED）、`restoreAutoCommitIfActive()`（仅 COMMITTED/ROLLED_BACK 时恢复）、`poison()`/`isPoisoned()`/`checkPoison()`；`ContextDatabaseExecutor.kt` `write()`（取消 rollback 一次，失败则 poison，原 CancellationException 为主；业务异常 rollback 一次，失败则 poison，原异常为主，catch 不再重复 rollback）、`read()`（回调失败 rollback 一次，不再无条件 commit；原业务异常为主）。
- **R6**：`ContextSqliteErrors.map`（F1/F2 已落实，SQLException 只保留操作名/SQLSTATE/驱动类型名，不裸漏 cause/message/stackTrace）；`ContextSlot.checkPoison()`/`poison()` 不传递底层异常详情；`CancellationException` 原样向外传播，不包装。

## 未做 / 未关闭项

- **未做 CT05**：ReadOps/TxOps、`ContextDatabase` 接线属 CT05，本轮不碰。
- **未运行 F3Probe.java**：环境无 JDK 21（系统 Java 11），原探针编译遇到类文件版本不匹配，无法直接重跑。以 83 例正式测试全绿作为行为验证。
- F3 rev3 通过不等于 CT04 完成，CT04 仍需整体集成验收才能放行 CT05。

## 下一步

由 Lead 独立初验，通过后提交 Architect 独立复核。复核通过后 F3 rev3 收口，CT04 仍需整体回归后才能放行 CT05。
