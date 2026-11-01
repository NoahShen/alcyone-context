# CT04-F1 Worker 交付

**任务**：CM1 CT04-F1 文件所有权与打开保护（R4 + Factory R6 分支）
**Worker**：720e6ddc-e9f8-414c-a8ae-ffe7414f0bab（主实现，已停止）→ 本 Worker（尾项收口与最终证据重跑）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db（Paseo lead 配置；前序交接自 989b31a6-dfdb-43a1-9e4b-489f6e7b588a）
**状态**：F1-T3 rev3 清理后交付（Worker 自测口径），待 Lead 初验与 Architect 独立复核
**日期**：2026-10-27

## 本次修改（2026-10-27，F1-T3 rev3 清理）

按 Architect《架构与技术复核》第 6 节仅退回 T3 三个测试清理点，只改测试文件与本文档，**生产 9 主源码 + `ContextSchema.sq` + 探针 SHA 完全不变**（与 rev2 快照逐字节一致，见 `final-rev3/final-rev3-source-hashes.json`）。

(a) **关闭失败可见**：两处吞掉关闭失败的 `runCatching { alt.close() }`（T2 读阶段注入的 rw 短连接、`CloseSetVersionHandler` 的 rw 短连接）改为直接 `try { alt.close() } catch (e) { actionFailure.set(e) }`，关闭失败记入已有 `actionFailure`，由用例 `assertNull` 断言；同时保持 handler 结构「注入完成后继续委托真实 `connection.close()`」，**原连接必关的事实不变**。

(b) **意外成功必须释放并失败**：T1 三个 Factory 异常用例（连接阶段 / user_version / sqlite_master）原直接 `ContextDatabaseFactory.open(source, 1)` 捕获异常，改为复用新增的 `openAndCapture(...)`（内部 `try/finally` + `executor?.close()`，意外成功返回 null）；末尾「缺库打开不创建任何文件」「错版文件字节不变」两例由 `assertFailsWith` 改为 `openExpectingFailure(...)`（同样 `try/finally` + 意外成功立即 `close()` 并回退 `fail()`）。两者语义不变：open 仍必须拒绝且拒绝类型为 `ContextPersistenceException`，仅包装方式改掉。

(c) **文案订正**：`TempDir.cleanup()` 实现是 `try { stream.sorted(...).forEach {...} } finally { stream.close() }`（显式 `close()`），**不是** `Files.walk(...).use { }`；效果等同（流都被关闭，清理失败逐条收集），仅按实现如实描述，未重写。

**本轮实跑结果**：模块测试仍为 **60 例**，0 failures/errors/skipped，源码 `@Test`=60 与 XML `tests`=60 差额 0；`spotlessCheck --rerun-tasks` exit 0。日志与 SHA 快照在 `.local/validation/cm1/worker/ct04-f1/final-rev3/`。历史计数口径保留：**历史 47 例**（主 Worker 修后初版）、**历史 59 例**（上一轮收口）、**本轮 60 例**，均为各轮实际数字，不得混用旧计数当作新用例通过证据。

## 本次修改（2026-10-27，F1-T1/T2/T3 定点修正）

按 Architect《架构与技术复核》第 5 节修正 CT04-F1 的**正式回归测试**，只改测试文件与本文档，**生产代码零改动**（9 个主源码 + `ContextSchema.sq` 哈希与上轮一致，见 `final-rev2/final-rev2-source-hashes.json`）。

**F1-T1 查询阶段精确注入**（替换原两个未命中分支的用例）：
- 原「Factory 版本查询异常」只在 `connect` 阶段抛错，「Factory 结构查询异常」在第一次 `createStatement` 就抛错，二者都未真正命中查询语句；本轮改为包装真实连接 + 代理 `Statement`，仅在 `executeQuery` 的 SQL 文本命中指定阶段时抛带敏感标记的 `SQLException`（并带 `suppressed`）。
- 三个独立场景与新增断言：
  1. 建立连接阶段（`Factory 建立只读预检连接失败时异常链不携带敏感标记`）：断言 `ContextPersistenceException`、只读预检连接仅尝试建立 1 次、完整异常链（cause/suppressed/printStackTrace/message/toString）无敏感标记。
  2. `PRAGMA user_version` 阶段（`Factory 预检 user_version 查询失败时异常链不携带敏感标记`）：断言真实只读连接已成功建立（`roConnected`）、注入精确命中 version 查询（`firedQuery`）、失败后不再执行其它查询、异常链无敏感标记；按 Architect 更正，**不断言同一次 version 校验成功**。
  3. `sqlite_master` 阶段（`Factory 预检 sqlite_master 查询失败时异常链不携带敏感标记`）：断言注入前 user_version 查询已成功（`versionSuccesses >= 1`）、注入精确命中 sqlite_master、异常链无敏感标记。

**F1-T2 读 Slot 结构校验阶段定点注入**（`实际读连接结构校验阶段命中缺索引并被拒`）：
- 旧用例在只读预检连接 close 时用完整 `broken.db` 覆盖目标，且吞掉复制异常，拒绝实际发生在写 Slot 阶段，证明不了读 Slot 检查；本轮按澄清口径收窄。
- 新实现：按 mode=rw 连接建立次序识别**第 2 条连接（读 Slot）**，写 Slot 只统计结构查询成功次数；读 Slot 的 `Statement.executeQuery` 命中 `sqlite_master` 时，先经**独立 rw 短连接**执行真实 `DROP INDEX idx_source_raw`（try/finally 真实关闭），再放行原查询；不再用 broken.db 覆盖目标。
- 新增断言：写 Slot 结构查询成功次数 ≥ 1（写阶段已完成）、读阶段命中次数 = 1、注入确实发生、缺失索引名出现在异常 message 的 missing 列表、异常 message 指明 `read` Slot、读 Slot 的 `query_only` 配置 PRAGMA 执行次数 = 0（证明拒绝早于该读连接配置 PRAGMA 生效）、注入动作无失败。

**F1-T3 测试资源清理**：
- `CloseDeleteHandler` / `CloseSetVersionHandler` 首次 `close` 拦截后不再直接 return，改为执行注入动作后**继续委托真实 `connection.close()`**；注入动作异常记录到 `AtomicReference` 并在用例中断言（`assertNull`），不再静默吞掉。
- 删除旧 `CloseDropIndexHandler` 与 `ThrowCreateStatementHandler`（分支已按 T1/T2 重新定点，且旧实现会覆盖目标文件并吞异常）。
- 预期失败的 `ContextDatabaseFactory.open` 若意外成功，通过 `openExpectingFailure` 立即 `close()` 释放连接/线程，并让断言失败（不伪通过）。
- 临时目录清理改为 `TempDir` + `Files.walk(...).use { }`（显式关流），清理失败逐条收集，测试末尾 `assertCleanupSucceeded` 断言可见。

**本轮实跑结果**（详见下文「本轮实跑命令与退码」）：模块测试 **60 例**（上一轮 59 例，本轮净 +1：T1 由 2 例拆为 3 例、T2 重写为 1 例、其余用例保留），0 failures/errors/skipped，源码 `@Test`=60 与 XML `tests`=60 **差额 0**；LeadF1Probe **11/11 PASS，failures=0**。

## 本次修改（收口 Worker，2026-10-27 最终版）

- 参数校验顺序收口：`ContextDatabaseExecutor` 中 `init {}` 里冗余的第二处 `require(readSlotCount >= 1)` 删除，保留「先于任何资源创建」的第一处为唯一来源，行为不变；核对 `ContextDatabaseFactory.open` 的 `require` 位于资源创建之前（只读校验与 Slot 打开前），无需修改。
- 最终产物重跑：以上最新 classes 重跑模块测试 59 例 0 失败、LeadF1Probe 11/11 通过，日志与源码 SHA-256 归档在 `.local/validation/cm1/worker/ct04-f1/final/`；47 例 first-* 中间态与旧 after 证据仅作前序引用。
- 未展开 F2 关闭/资源协议、F3 事务/作用域，不做 CT05，未改 VFS/common/进度入口/AGENTS/Architect 复核文件，未执行 git add/commit/远端。

## 主实现 Worker 交付记录（720e6ddc，前序证据引用）

- 修正 5 个生产源码文件，实现 F1 范围内的 R4 数据库路径编码与建库/打开竞争保护，以及 Factory R6 敏感异常链映射。
- 新增正式反例测试 `R4FileOwnershipAndOpenProtectionTest.kt`（12 个用例），固化 Lead 探针的 11 个失败场景为可复现的 Kotlin 测试。

## 生产源码 SHA-256（最终收口版，本 Worker 实测）

收口改动仅 1 个文件（`ContextDatabaseExecutor.kt` 删除冗余 require），其余 8 个主源码与探针哈希与 `.local/validation/cm1/worker/ct04-f1/after-hashes.txt` 及探针哈希一致；完整清单见 `final/final-source-hashes.json`。

| 文件 | SHA-256 |
| --- | --- |
| `.../ContextDatabaseExecutor.kt` | `9a1b85970da05849be95d24b30c7b323de26230ab962161219b6d3eeed7c08e1`（本 Worker 修改） |
| `.../ContextDatabaseFactory.kt` | `bcb49abafe4be8826ceedc25d17f968120beaefc5cd0ce84d9d1681072c0ceff` |
| `.../ContextDatabaseInitializer.kt` | `a0eb787e5a883bff1e7ac54629a07583257303ca39e20c8a45e618341c13ef87` |
| `.../ContextSlot.kt` | `d5dc2e18077b7ec5eebc9c1af183a00dbfffb5a487d01e95b61a637357516058` |
| `.../SqliteUrls.kt` | `50c39576daa80644fb380d73bc95270071d9b96e1e5aa6e4ad6c9f8e9f50e578` |
| `.local/validation/cm1/lead/ct04-f1/probe/LeadF1Probe.java` | `4ddbf43a87555856d6fabdcf5ef577462f56d4fb7ee2a23772844090bc776ce8` |

## 正式测试用例清单（F1-T1/T2/T3 修正后，13 例；本文件历史曾为 12 例）

文件：`context/persistence-sqldelight/src/test/kotlin/com/github/noahshen/alcyone/context/memory/persistence/sqldelight/R4FileOwnershipAndOpenProtectionTest.kt`（rev2 SHA-256 `57c6ccc9…f96797`；rev3 清理后为 `230c0567…f6432`）

| 用例 | 覆盖 R4/R6 判据 | 本轮变化 |
| --- | --- | --- |
| 特殊路径初始化后打开 user_version 为 1 | 空格、%、?、#、中文路径 URI 编码正确，打开后 schema 有效 |
| 竞争初始化胜者唯一且库有效 | CREATE_NEW 原子认领，仅一方成功，胜者文件保留、user_version=1 |
| 初始化失败时不删除他方替换文件 | 建库失败后文件被外部替换，不删除他方文件 |
| 认领后文件被删除不重建 | CREATE_NEW 认领后文件丢失，不重建 |
| Factory 验证后文件被删除时拒绝且不重建 | 只读校验通过后文件消失，open 拒绝且不重建 |
| 每个 Slot 物理连接使用 mode=rw 且不自动建库 | 写 1 + 读 2 = 3 条 mode=rw 连接，无自动建库 |
| 实际写连接 PRAGMA 前重验 user_version，错版被拒 | 实际连接 PRAGMA 前再读 user_version，错版拒绝 |
| 实际读连接 PRAGMA 前重验结构，缺失索引被拒 | 旧实现只证明写阶段失败 | **已替换**（见下） |
| Factory 版本查询异常时完整异常链不携带敏感标记 | 旧实现只在 connect 阶段抛错 | **已替换**（见下） |
| Factory 结构查询异常时完整异常链不携带敏感标记 | 旧实现首次 createStatement 即抛错 | **已替换**（见下） |
| 缺库打开不创建任何文件 | 缺库 open 不建文件 | 清理改为可见失败 |
| 错版文件字节不变 | 错版 open 不改动文件字节 | 清理改为可见失败 |

### F1-T1/T2 修正后新增/替换的用例（共 4 例）

| 用例 | 注入点 | 关键断言 |
| --- | --- | --- |
| Factory 建立只读预检连接失败时异常链不携带敏感标记 | `connect(url, props)` 对 mode=ro 直接抛带标记 SQLException | 异常类型、只读预检连接仅建立 1 次、完整异常链无标记 |
| Factory 预检 user_version 查询失败时异常链不携带敏感标记 | `Statement.executeQuery` 命中 `PRAGMA user_version` | 真实 ro 连接已建立、注入精确命中、失败后无其它查询、完整异常链无标记 |
| Factory 预检 sqlite_master 查询失败时异常链不携带敏感标记 | `Statement.executeQuery` 命中 `sqlite_master` | 注入前 version 查询成功 ≥ 1、注入精确命中、完整异常链无标记 |
| 实际读连接结构校验阶段命中缺索引并被拒 | 第 2 条 mode=rw 连接（读 Slot）的 `executeQuery` 命中 `sqlite_master` 时经独立 rw 短连接 `DROP INDEX idx_source_raw` | 写 Slot 结构查询已成功 ≥ 1、读阶段命中 = 1、message 含缺失索引名与 `read`、读 Slot `query_only` PRAGMA 执行数 = 0（拒绝早于配置 PRAGMA）、注入动作无失败 |

所有测试使用真实文件型 SQLite，Driver 包装制造受控交错，finally 中恢复 Driver 注册并清理临时资源，不使用概率 sleep。

## 本轮实跑命令与退码（F1-T3 rev3 清理后，2026-10-27）

```
./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks --console=plain
  -> BUILD SUCCESSFUL, exit 0
  -> 模块 60 例，0 failures/errors/skipped；源码 @Test=60，XML tests=60，差额 0
  （日志：.local/validation/cm1/worker/ct04-f1/final-rev3/final-rev3-targeted.log）
./scripts/dev gradle :context:persistence-sqldelight:spotlessCheck --rerun-tasks --console=plain
  -> BUILD SUCCESSFUL, exit 0
  （日志：.local/validation/cm1/worker/ct04-f1/final-rev3/final-rev3-spotless.log）
```

测试文件 SHA-256：`230c0567768c4ef1edfdfd592adae2d6c977493de58507a787eb3b011b1f6432`。按 Lead 指示生产不变，本轮未重跑 LeadF1Probe（rev2 的 11/11 PASS 仍有效）。

## 本轮实跑命令与退码（F1-T1/T2/T3 修正后，2026-10-27）

```
./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks --console=plain
  -> BUILD SUCCESSFUL, exit 0
  -> 模块 60 例，0 failures/errors/skipped；源码 @Test=60，XML tests=60，差额 0
  （日志：.local/validation/cm1/worker/ct04-f1/final-rev2/final-rev2-targeted.log）
./scripts/dev gradle :context:persistence-sqldelight:spotlessApply --console=plain
  -> BUILD SUCCESSFUL, exit 0
  （日志：.local/validation/cm1/worker/ct04-f1/final-rev2/final-rev2-spotless-apply.log）
./scripts/dev gradle :context:persistence-sqldelight:spotlessCheck --rerun-tasks --console=plain
  -> BUILD SUCCESSFUL, exit 0
  （日志：.local/validation/cm1/worker/ct04-f1/final-rev2/final-rev2-spotless-check.log）
LeadF1Probe 重编译重运行（javac + java，classpath 同 .local/validation/cm1/architect/ct04/classpath.txt，JDK .local/jdk/jdk-21.0.12.1+1）
  -> 11/11 PASS，failures=0，exit 0
  （日志：.local/validation/cm1/worker/ct04-f1/final-rev2/final-rev2-probe.log）
```

用例数变化说明：上一轮为 59 例，本轮为 **60 例**（T1 由 2 个旧用例拆为 3 个精确阶段用例、T2 重写为 1 个精确读阶段用例，其余 9 个用例保留）。“历史 47 例”（主 Worker 修后初版）与“历史 59 例”（上一轮收口）均仅为历史引用，不作为本轮通过证据。

## 真实命令与退码（上一轮收口 Worker 实跑，2026-10-27）

改动前已核对当前 9 个主源码 SHA 与 `after-hashes.txt` 一致。改动后：

```
./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks --console=plain
  -> BUILD SUCCESSFUL, exit 0
  -> 模块 59 例，0 failures/errors/skipped；源码 @Test=59，XML tests=59，差额 0
  （日志：.local/validation/cm1/worker/ct04-f1/final/final-targeted.log）
探针重编译重运行（javac + java，classpath 同 .local/validation/cm1/architect/ct04/classpath.txt，JDK .local/jdk/jdk-21.0.12.1+1）：
  -> 11/11 PASS，failures=0，exit 0
  （日志：.local/validation/cm1/worker/ct04-f1/final/final-probe.log）
./scripts/dev gradle :context:persistence-sqldelight:spotlessApply --console=plain
  -> BUILD SUCCESSFUL, exit 0（Executor.kt 哈希不变，格式无差异）
```

主实现 Worker（720e6ddc）交付时的自测证据（引用，非本次实跑）：`./scripts/dev gradle :context:persistence-sqldelight:spotlessApply` 通过；`:test --rerun-tasks` 退出 0，共 59 例，0 failures；LeadF1Probe 修后 11/11 通过，见 `.local/validation/cm1/worker/ct04-f1/lead-probe-after.log` 与 `build/test-results/test/*.xml`。前序 first-* 中间态（47 例）仅作历史引用，不作为最终证据。

## 证据目录

`.local/validation/cm1/worker/ct04-f1/`：
- `before-hashes.txt`、`after-hashes.txt`：主实现 Worker 修前/修后生产源码 SHA-256（收口前基线核对一致）
- `lead-probe-after.log`：主实现 Worker 的 LeadF1Probe 修后运行日志（11 PASS，failures=0）
- `TEST-*.xml`：主实现 Worker 时点 59 例结果 XML（前序引用）
- `final/`（上一轮收口实测归档）：`final-targeted.log`（59 例命令完整输出与 BUILD SUCCESSFUL）、`final-probe.log`（11/11 PASS，failures=0，exit=0）、`final-source-hashes.json`（9 个主源码 + 探针 SHA-256）、`TEST-*.xml`（该轮 59 例 XML 副本，tests/failures/errors/skipped = 59/0/0/0）
- `final-rev2/`（F1-T1/T2/T3 修正后实测归档）：`final-rev2-targeted.log`（60 例，exit 0）、`final-rev2-spotless-apply.log`、`final-rev2-spotless-check.log`（均 exit 0）、`final-rev2-probe.log`（11/11 PASS，failures=0，exit=0）、`final-rev2-source-hashes.json`（9 个主源码 + `ContextSchema.sq` + 探针 + 测试文件 SHA-256）
- `final-rev3/`（F1-T3 rev3 清理后实测归档，2026-10-27）：`final-rev3-targeted.log`（`:test --rerun-tasks`，exit 0，60 例，差额 0）、`final-rev3-spotless.log`（`spotlessCheck --rerun-tasks`，exit 0）、`final-rev3-source-hashes.json`（9 个主源码 + `ContextSchema.sq` + 探针 + 测试文件新 SHA-256 `230c0567768c4ef1edfdfd592adae2d6c977493de58507a787eb3b011b1f6432`）、`TEST-*.xml` 副本（60/0/0/0）。生产源码与探针与 rev2 快照逐字节一致（仅测试文件 SHA 变化）；按 Lead 指示生产不变，本轮未重跑 LeadF1Probe。

## 未关闭项（F2/F3，不在本批，不得外推）

- R1：写连接启动失败释放、关闭协议统一锁、全部 Slot 尽力释放；
- R2：同步/异步关闭互斥、单一完成结果、回调内关闭拒绝；
- R3：原始驱动逃逸缩小、逐次操作作用域/线程校验；
- R5：rollback/autoCommit 失败不隐式提交失败事务；
- R6 剩余 Factory/Executor 分支异常链安全映射。

**F2/F3 未开展，CT05 未放行。** 请按 Architect → Lead → Worker 三级复核流程逐批推进。

## 本轮改动文件清单（SHA-256）

| 文件 | SHA-256 | 说明 |
| --- | --- | --- |
| `context/persistence-sqldelight/src/test/kotlin/.../R4FileOwnershipAndOpenProtectionTest.kt` | rev2 `57c6ccc9…f96797` → rev3 `230c0567…f6432` | 本轮唯一代码改动（F1-T1/T2/T3 与 T3 rev3 清理） |
| `docs/context/tasks/cm1-ct04/F1_Worker交付.md` | 本轮更新 | 交付记录 |
| `context/persistence-sqldelight/src/main/kotlin/.../*.kt`（9 个） | 与上一轮完全一致 | **未改动**，含 `ContextDatabaseExecutor.kt` = `9a1b85970da05849be95d24b30c7b323de26230ab962161219b6d3eeed7c08e1` |
| `context/persistence-sqldelight/src/main/sqldelight/.../ContextSchema.sq` | `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3` | **未改动** |
| `.local/validation/cm1/lead/ct04-f1/probe/LeadF1Probe.java` | `4ddbf43a87555856d6fabdcf5ef577462f56d4fb7ee2a23772844090bc776ce8` | **未改动**（只重编译重跑） |
