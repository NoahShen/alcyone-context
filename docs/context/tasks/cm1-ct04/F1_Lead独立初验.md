# CT04-F1 Lead 独立初验

## 本次修改（2026-10-27，rev3：F1-T3 清理后的 Lead 独立初验，F1 全部尾项已补齐，待 Architect 复核收口）

- Architect《架构与技术复核》第 6 节确认 T1/T2 关闭，仅退回 T3 三个测试清理点。Worker `90a0475d` 修正后，本节为最终初验记录；此前各节均为历史。生产 9 主源码 + `ContextSchema.sq` + 探针哈希与 rev2 逐字节一致（Executor=`9a1b8597…`、probe=`4ddbf43a…`、sq=`ec727a53…`），本轮唯一代码改动为 `R4FileOwnershipAndOpenProtectionTest.kt`（`57c6ccc9…` → `230c0567768c4ef1edfdfd592adae2d6c977493de58507a787eb3b011b1f6432`，仍 13 个 `@Test`）。
- **T3-1 关闭失败可见**：读结构用例的短连接 `alt.close()`（约 602-608 行）与 `CloseSetVersionHandler` 的 `alt?.close()`（约 896-903 行）均改为 try/catch 记入 `actionFailure`，由用例 `assertNull(actionFailure.get(), …)`（455/536/637 行）断言；`runCatching` 在该测试文件已清零；handler 注入动作后仍继续委托被包装原 `connection.close()`，原连接必关不变。
- **T3-2 意外成功必须释放并失败**：逐一核对该文件全部 `ContextDatabaseFactory.open` 调用点：成功路径 283/334/487 行均 `.use{}` 释放；T1 三例（677/734/791）改用新增 `openAndCapture`（try/finally + `executor?.close()`，意外成功返回 null 由调用方 fail）；缺库（812）、错版（830）两例改 `openExpectingFailure`（意外成功立即 close 并 fail）；拒绝类型语义不变。剩余 365/399 两处 `assertFailsWith` 仅用于 initializer 用例（不涉及 Executor 打开），合法保留。
- **T3-3 文案订正**：交付文档 rev3 节已将 TempDir 描述订正为 try/finally + `stream.close()`；rev2 历史节旧措辞保留为历史，未被覆盖描述为新实现。
- **Lead 独立实跑**（非引用 Worker 日志）：`./scripts/dev gradle :context:persistence-sqldelight:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks` → exit 0；源码行首 `@Test`=**60**、XML `tests=60`，0 failures/errors/skipped，差额 0。日志 `.local/validation/cm1/lead/ct04-f1/rev3-lead-check.log`。按 Architect 指示生产完全不变，未机械重跑 11 探针（探针判据已由正式测试定点覆盖）。
- **判定**：§5 的 T1/T2 与 §6 的 T3 全部在正式测试中落实；F1 三批判据齐备，**F1 尾项已补齐，等待 Architect 独立复核收口**。F1 通过仍不等同 CT04 完成：R1/R2/R3/R5 与 R6 剩余分支属 F2/F3，CT05 仍不放行。

## 历史：本次修改（2026-10-27，rev2：F1-T1/T2/T3 定点修正后的 Lead 独立初验；T3 清理后由上方 rev3 节取代）

- Architect《架构与技术复核》第 5 节判定 F1 **暂不收口**，要求只修正式测试与交付记录（F1-T1 查询阶段错位 / F1-T2 读 Slot 判据不成立 / F1-T3 测试资源清理），生产代码零改动。Worker `90a0475d`（Paseo worker 配置）完成修正后，本节为 Lead 独立初验记录；下节及更早段落为历史。
- **改动范围核对**：仅 `R4FileOwnershipAndOpenProtectionTest.kt`（`0e163ab3…` → `57c6ccc9ba2a221779e13d0274a2a6e2dfc1f832c31abc24adcf623675f96797`，13 个 `@Test`）与 `F1_Worker交付.md`。生产 9 主源码 + `ContextSchema.sq`（`ec727a53…`）哈希与 rev1 完全一致（Executor=`9a1b8597…`），探针 `LeadF1Probe.java`=`4ddbf43a…` 未改。
- **Lead 独立实跑**（非引用 Worker 日志）：
  - `./scripts/dev gradle :context:persistence-sqldelight:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks` → exit 0；源码行首 `@Test`=**60**、XML `tests=60`，0 failures/errors/skipped，**差额 0**；R4 文件内 13 例。日志 `.local/validation/cm1/lead/ct04-f1/rev2-lead-check.log`。
  - 同一 `LeadF1Probe.java` 重新 javac 编译 + 运行 → **11/11 PASS，failures=0**，javac/java 均 exit 0。日志 `.local/validation/cm1/lead/ct04-f1/rev2-lead-probe.log`。
- **Lead 定向变异验证**（3 条，全部咬住，证明新断言不是摆设；变异在临时副本上进行并已还原，文件哈希回到 `57c6ccc9…`）：
  1. 把 T2「读阶段命中 = 1」改为 `= 0` → 用例「实际读连接结构校验阶段命中缺索引并被拒」FAILED；
  2. 把 T2 注入点退回 Architect 指出的旧错误做法（写 Slot 阶段就 DROP INDEX）→ 同用例 FAILED（`readStructureHits expected:<1> but was:<0>`），证明该做法现在会被判失败；
  3. 把 T1 连接阶段注入改为不抛错 → 用例「Factory 建立只读预检连接失败时异常链不携带敏感标记」FAILED。
- **F1-T1 三阶段触达标记核对**（测试文件 626/677/740 行起）：连接阶段断言只读预检连接仅建立 1 次且无任何查询；`PRAGMA user_version` 阶段断言真实 ro 连接已建立（`roConnected`）且注入精确命中该查询（`firedQuery`）、失败后无其它查询，按 Architect 更正**未**断言同一次 version 校验成功；`sqlite_master` 阶段断言注入前 user_version 查询已成功（`versionSuccesses >= 1`）且注入精确命中 sqlite_master。三例均检查完整异常链（message / toString / cause / suppressed / printStackTrace 全文）无敏感标记。
- **F1-T2 阶段证据核对**（533 行起）：按 mode=rw 建立次序识别第 2 条（读 Slot）连接；注入经独立 rw 短连接在读连接 `executeQuery` 命中 `sqlite_master` 时真实 `DROP INDEX idx_source_raw`（短连接 try/finally 真实关闭），**不再用 broken.db 覆盖 target**。断言：写 Slot 结构查询成功 ≥ 1（写阶段已完成）、读阶段命中 = 1、缺失索引名出现在异常 message 的 missing 列表、异常指明 `read` Slot、读 Slot `query_only` 配置 PRAGMA 执行数 = 0（拒绝早于该读连接配置 PRAGMA）、注入动作无失败。
- **F1-T3 清理核对**：`CloseDeleteHandler` / `CloseSetVersionHandler` 首次 close 拦截后继续委托真实 `connection.close()`；注入动作异常入 `AtomicReference` 并 `assertNull`；旧 `CloseDropIndexHandler` / `ThrowCreateStatementHandler` 已删除；`openExpectingFailure` 在意外成功时立即 close 并让断言失败；`TempDir` + `Files.walk(...).use{}` 显式关流，清理失败逐条收集并在用例末尾 `assertCleanupSucceeded` 断言。
- **判读**：F1-T1/T2/T3 三项在正式测试中已定点落实并可被变异咬住；生产侧决定性保护仍由 11 项独立探针背书。**F1 的初验结论以本节为准**，待 Architect 独立复核后收口；F2/F3/CT05 仍不放行。

## 本次修改（2026-10-27，新Lead独立重跑最终收口，重写初验结论）

- Lead `6f547b7e`（Paseo lead 配置）接续 Worker `90a0475d` 的最终交付，独立重跑测试、探针与格式检查后重写本文件的初验结论；下方「修前独立执行」「F1 生产修正与初验通过（47 例时点）」「修后生产源码 SHA-256（40b1a5…）」均为**历史段落**，以本节最终结论为准。
- **最终源码身份**：`ContextDatabaseExecutor.kt`=`9a1b8597…`（收口 Worker 删除字段初始化块冗余的第二处 `require(readSlotCount>=1)`，保留构造参数处先于资源创建的唯一来源），其余 8 个主源码 + `.sq` + 探针哈希不变，与 `.local/validation/cm1/worker/ct04-f1/final/final-source-hashes.json` 一致。
- **Lead 独立重跑（本节命令与日志，非引用 Worker 日志）**：
  - `./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks` → exit 0，模块 59 例 0 failures/errors/skipped；源码行首 `@Test`=59、XML `tests=59`，差额 0。日志 `.local/validation/cm1/lead/ct04-f1/final-lead-targeted.log`。
  - `./scripts/dev gradle :context:persistence-sqldelight:spotlessCheck --rerun-tasks` → exit 0，5 tasks executed。日志 `.local/validation/cm1/lead/ct04-f1/final-lead-spotless.log`。
  - 同一 `LeadF1Probe.java`（哈希 `4ddbf43a…`，源码未改）以最新 classes 重新 `javac` 编译 + `java` 运行（`.local/jdk/jdk-21.0.12.1+1`，CT04 classpath）→ 11/11 PASS，`failures=0`，javac exit 0、java exit 0。日志 `.local/validation/cm1/lead/ct04-f1/final-lead-probe.log`。
- 校验顺序独立核对：当前 `ContextDatabaseExecutor.kt` 仅第 51 行一处 `require(readSlotCount>=1)`，位于 `init{}` 内、先于 `writeSlot`/`readSlots` 字段初始化（先于任何资源创建）；`ContextDatabaseFactory.open` 的 `require` 也在 `verifyReadOnly` 与 Executor 构造之前。
- 47 例的 `after/first-*` 是 R4 正式测试（+12 例、Executor 由 `40b1a5`→`9a1b85`）提交前的中间态证据，**不作为最终初验依据**。

## 本次修改（2026-10-27，恢复交接与修前证据）

- 接任 Lead `989b31a6-dfdb-43a1-9e4b-489f6e7b588a` 收到原 Worker `a47e03bb-5abf-42a6-9886-d69aa9a178d7` 的停止写入确认后，按配置创建唯一 F1 Worker `720e6ddc-e9f8-414c-a8ae-ffe7414f0bab`。
- 本文记录 Lead 自行执行的修前证据，修后尚待初验；不改变 Architect 的 CT04 IN_REVIEW 结论，不放行 F2/F3 或 CT05。

## 范围与判据

只覆盖文件 URI 接线、初始化 CREATE_NEW 所有权、普通物理连接非创建打开及实际连接版本/结构检查、Factory SQL 错误安全映射。初始化竞争必须只有一方成功且胜者数据库可重开；普通验证后文件消失必须拒绝且不重建；特殊路径对应原文件；底层敏感标记不能出现在完整异常链。

资源统一释放/关闭协议及事务/作用域仍由 F2/F3 处理。本批不对这些问题宣称关闭。

## 修前独立执行

HEAD `cdcf3a28ec392d344cf98f9a22e7387aa04d2f6b`，Context 为既有未提交成果，不能用 HEAD 代替源码身份。修前源码快照及 SHA-256：`.local/validation/cm1/lead/ct04-f1/before/src/`、`source-hashes.json`。

| 执行 | 实际结果 | 证据（相对 `.local/validation/cm1/lead/ct04-f1/`） |
| --- | --- | --- |
| `./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks` | 初次受沙箱 SocketException 阻止；批准后重编译并退出 0，47 tests，0 failures/errors/skipped | `before/targeted.log`、`before/test-count.json` |
| Architect 原 `FileRaceProbe.java`，项目私有 JDK 21 编译运行 | 初始化删除竞争方库=true；普通打开重建缺库=true；进程退出 0 仅表示探针执行完，不表示行为通过 | `before/FileRaceProbe.java`、`before/file-race.log` |
| Lead 新 `LeadF1Probe.java`，同一私有 JDK 与 CT04 classpath | 特殊路径通过；竞争胜者保存、删除后不重建、所有 Slot 非创建模式、实际写 Slot 重验版本、读 Slot 实际结构检查、Factory 连接/版本查询/结构查询安全异常链以及初始化替换文件保护/丢失认领不重建，合计 10 项失败；退出 1 | `probe/LeadF1Probe.java`、`before/lead-probe.log` |

独立探针使用真实 SQLite 和 Driver 包装受控交错；初始化竞争采用连接时重入第二初始化，分别统计胜者并检查有效 Schema；Driver 注册在 finally 恢复，临时目录在 finally 清理。修后应以同一探针源码重新编译运行。

## 开发中差异审查（待 Worker 自测收敛）

Lead 在 Worker 开发过程中发现并已反馈：初始化清理须核对 fileKey，不得仅凭历史 claimed 布尔删除当前路径；认领后的连接也须非创建模式；新 SQL catch 不得重新携带 SQLException cause；fileKey 为 `Any?`，不需要全局认领 Map；实际 Slot 需同时检查版本和结构；保持原 roundRobin 成员状态，避免引入范围外的调度回归。这些是增量审查发现，不代表最终交付仍存在或已经修复，待实际修后代码与测试核对。

## 历史：本次修改（2026-10-27，F1 生产修正，47 例时点，非最终依据）

- Worker 完成 F1 范围内的 5 文件生产修正，`./scripts/dev gradle :context:persistence-sqldelight:spotlessApply` 通过，`:context:persistence-sqldelight:test --rerun-tasks` 退出 0，47 tests，0 failures/errors/skipped，源码 `@Test` 47 与 XML `tests=47` 差额 0。
- 同一 `LeadF1Probe.java` 以修后源码重新编译运行：11/11 场景通过，退出 0，`failures=0`。证据：`.local/validation/cm1/lead/ct04-f1/after/lead-probe.log`、`.local/validation/cm1/worker/ct04-f1/lead-probe-after.log`。
- 不改动 `vfs/*`、`common/*`、`integration-tests/vfs/*`，81 个 VFS 基线文件零差异；不改 `gradle/libs.versions.toml` 既有版本；不执行 `git add` / `git commit` / 远端操作。

## 修后生产源码 SHA-256（历史 47 例时点；最终值见下节）

| 文件 | SHA-256 |
| --- | --- |
| `ContextDatabaseExecutor.kt` | `40b1a5c44140427a960862eedd75ec948a95ed8dfa64a44c42f9fc4fe6222979`（历史；收口后 `9a1b8597…`） |
| `ContextDatabaseFactory.kt` | `bcb49abafe4be8826ceedc25d17f968120beaefc5cd0ce84d9d1681072c0ceff` |
| `ContextDatabaseInitializer.kt` | `a0eb787e5a883bff1e7ac54629a07583257303ca39e20c8a45e618341c13ef87` |
| `ContextSlot.kt` | `d5dc2e18077b7ec5eebc9c1af183a00dbfffb5a487d01e95b61a637357516058` |
| `SqliteUrls.kt` | `50c39576daa80644fb380d73bc95270071d9b96e1e5aa6e4ad6c9f8e9f50e578` |

完整快照见 `.local/validation/cm1/worker/ct04-f1/after-hashes.txt`；修前见 `before-hashes.txt`；**最终 9 主源码 + 探针 SHA 见 `.local/validation/cm1/worker/ct04-f1/final/final-source-hashes.json` 与本文件首节。**

## 历史：Lead 初验结论（rev1，基于 59 例 + Executor 9a1b85…，已被 rev2 节取代；Architect 第 5 节指出正式回归覆盖待定点修正）

按 F1 范围判据逐项核对（每项证据均出自 Lead 独立重跑的 `final-lead-probe.log`，而非 Worker 日志）：

| 判据 | 修后结果 | 证据 |
| --- | --- | --- |
| 特殊路径（空格、%、?、#、中文）URI 编码正确 | PASS | `LeadF1Probe` "special paths" |
| 初始化竞争胜者唯一、胜者库有效 Schema 可重开 | PASS | `LeadF1Probe` "competing initializer preserves winner" |
| 初始化失败不删除他方替换文件 | PASS | `LeadF1Probe` "initializer failure preserves replaced file" |
| 认领后文件被删除不重建 | PASS | `LeadF1Probe` "initializer does not recreate deleted claimed file" |
| 普通打开验证后文件消失 → 拒绝且不重建 | PASS | `LeadF1Probe` "validation then delete rejects without recreate" |
| 所有 Slot 连接 `?mode=rw`，非自动建库 | PASS | `LeadF1Probe` "each physical connection uses noncreate mode" |
| 写 Slot 实际连接 PRAGMA 前重验 `user_version` | PASS | `LeadF1Probe` "actual write connection revalidates version before pragma" |
| 读 Slot 实际连接 PRAGMA 前重验结构（六表五索引） | PASS | `LeadF1Probe` "actual read connection revalidates structure" |
| Factory 版本查询异常 → 安全映射，无敏感标记 | PASS | `LeadF1Probe` "factory query failure safe PRAGMA user_version" |
| Factory 结构查询异常 → 安全映射，无敏感标记 | PASS | `LeadF1Probe` "factory query failure safe sqlite_master" |
| Factory SQL 失败链不带底层 cause / message / stackTrace | PASS | `LeadF1Probe` "factory sql failure chain safe" |

**F1 初验通过（最终）**。判据 11/11 PASS；参数校验顺序收口确认（Executor 仅一处 `require`、先于资源创建；Factory `require` 先于只读校验与 Executor 构造）；模块 59 例与 spotlessCheck 独立重跑 exit 0。独立重跑证据：`.local/validation/cm1/lead/ct04-f1/final-lead-targeted.log`、`final-lead-spotless.log`、`final-lead-probe.log`。F2（资源所有权与关闭协议）和 F3（事务失败与作用域执行边界）仍未开展，CT05 仍不放行。Architect 独立复核待安排。

## 未关闭项（F2/F3，不在本批）

- R1：写连接启动失败释放、关闭协议统一锁、全部 Slot 尽力释放；
- R2：同步/异步关闭互斥、单一完成结果、回调内关闭拒绝；
- R3：原始驱动逃逸缩小、逐次操作作用域/线程校验；
- R5：rollback/autoCommit 失败不隐式提交失败事务；
- R6 剩余 Factory/Executor 分支异常链安全映射。

