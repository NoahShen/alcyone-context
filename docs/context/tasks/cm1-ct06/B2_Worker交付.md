# CT06-B2 Worker 交付（生命周期 / 并发 / 失败与关闭屏障）

**任务**：CM1 CT06-B2：`AlcyoneContext` / `AlcyoneContextFactory`、start/close/closeAsync、状态机、失败与取消、在途 JDBC 等待、真实 SQLite + VFS LocalFs 集成
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db（Paseo lead 配置，只做方案与独立初验）
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-28

## 本次修改（2026-10-29，B2 §5 R1 配置集合所有权与测试判据加强）

- 按 Architect 复核 §5 定点修复：`Factory.build` 在**校验前**取得归实例所有的快照（`namespaces.toSet()`、`mounts.toList()`），用 `copy(...)` 构造新的 `VfsRuntimeConfig`，只对该快照校验并交给 `AlcyoneContextImpl`；调用者事后修改原集合不再影响 start。
- 新增真实回归（直接 config + Loader config 两条路径）：safe/private 目录各预置独特 marker，Context 库放在 private；create 后修改调用者原 mounts/namespaces；start 必须成功且内部 VFS 真实 list 仅显示 `safe-marker.txt`，看不到 `private-marker.txt` 与 `context.db`。
- 加强测试判据：`close` 等待者改为在 `internal closeWaiterEnteredForTesting` 计数上升（已进入共享等待）后再取消；并发 start 成功例在放行探针前等待 `internal startWaiterEnteredForTesting`，失败例新增 internal `StartGate` 证明第二个 start 已在 STARTING 等待后再触发失败，并断言两者得到同一失败实例。
- 不新增生产公共接口（仅 internal 测试钩子）；未改 VFS/CT04/Persistence；未启动 CT07/CM2。
- 计数：**49 例**（原 47 + 2 所有权回归），源码 `@Test` 49 = XML 49，差额 0。证据 `.local/validation/cm1/worker/ct06-b2-fix2/`。

## 本次修改（2026-10-29，B2 接口裁定修正，Architect §4）

- 按裁定 A 恢复 CT01 冻结契约：在 `context/api` 新增 `AlcyoneContext : AutoCloseable`（`suspend start()`、`override fun close()`、`suspend fun closeAsync()`、`suspend fun getStatus()`），删除 runtime 中的重复接口并同步工厂/测试；api 不依赖 Runtime/JDBC/YAML。
- 同步/异步关闭共享**真实**清理结果：唯一 `CloseState`（协程用 `CompletableDeferred`、同步宿主用 `CountDownLatch`）；发起者在独立、中断状态干净的清理线程（sync）或 `NonCancellable + Dispatchers.IO`（async）执行 `vfs.close()` + `executor.closeAsync()`；等待在锁外进行；同步线程被 interrupt 时不可中断地等待真实结果并在结束后恢复中断标志；挂起路径不占用调用者 dispatcher。
- 修复失败传播：fetch-start 的 close 获胜分支若释放失败会写入 `terminalFailure` 并由 start/close 共享；`shutdown` 等待 startAttempt 不再吞异常（在 await 后读 `startAbortedByClose` 区分正常竞态与真实失败）；CLOSED 后 close/closeAsync 抛出同一存储失败实例。
- 新增 internal `ReleaseProbe`（VFS/EXECUTOR，仅测试注入，生产空实现）与 5 个关闭契约测试：sync/async 混合共享、释放失败共享、竞态中释放失败共享、同步线程中断不丢清理、单线程 dispatcher 无死锁。
- 计数：**47 例**（原 42 + 5），源码 `@Test` 47 = XML 47，差额 0。证据 `.local/validation/cm1/worker/ct06-b2-fix/`。
- 未改 VFS/CT04/Persistence；未启动 CT07/CM2；未 `git add/commit`，未自标 DONE。

## 本次修改（2026-10-28，CT06-B2 生命周期与集成）

- 新增 `AlcyoneContext`（仅 `start` / `close` / `closeAsync` / `getStatus`）、`AlcyoneContextFactory`（`create` 零副作用 + `initializeDatabase`）、`AlcyoneContextImpl`（状态机、共享结果、逆序释放、在途等待）。
- B1 的路径隔离逻辑抽到 `ContextPathIsolation.kt`，由 Loader 与 `create` 共用；`ContextConfigLoader` 改为委托，B1 的 20 个配置测试继续全绿（无回归）。
- 新增 20 个测试（工厂 10 / 生命周期 7 / 关闭屏障 2 / LocalFs 集成 1），真实临时目录、真实文件 SQLite 与 VFS LocalFs；并发与屏障全部用 `CountDownLatch`/`Future`/`AtomicReference`，无 sleep 概率断言。
- 未改 VFS/Core/API/Persistence/CT04；未扩 CT07/CM2；未 `git add/commit`。

## 1. 交付文件与落点

| 文件 | 内容 | 关键落点 |
| --- | --- | --- |
| `context/api/.../AlcyoneContext.kt` | SDK 生命周期接口（CT01 冻结位置） | `start` / `close` / `closeAsync` / `getStatus`；CT07 才加业务入口 |
| `.../AlcyoneContextFactory.kt` | 工厂 | `create`（纯校验+组装，零副作用）、`internal create(..., StartProbe)`（同模块测试探针）、`initializeDatabase`、vfs 结构校验 |
| `.../AlcyoneContextImpl.kt` | 实现 | `start`/`performStart`、`close`/`closeShared`/`shutdown`、`releaseResources`、`getStatus`、`lastShutdownExceededObservationWindow`、`databaseForTesting` |
| `.../ContextPathIsolation.kt` | 共用路径隔离 | 悬空链接逐段解析 + 真实路径规范化；同文件 / LocalFs 挂载根判断 |
| `.../ContextConfigLoader.kt` | 委托隔离 helper | 行为不变，B1 测试全绿 |

## 2. 语义实现

- **create 零副作用**：只做结构校验与路径隔离校验（读文件系统、不创建），返回 `CREATED`；`databaseInitialized=false`，`getStatus` 只读缓存事实，不探测文件/不建连接。
- **直接配置也隔离**：`create(contextConfig, vfsConfig)` 对直接构造的对象同样执行 `ContextPathIsolation`（同文件、LocalFs 挂载根、悬空链接、大小写别名），复用 B1 已验收算法。
- **start**：`CREATED → STARTING`；`ContextDatabaseFactory.open`（缺库/0 字节/错版/损坏拒绝，不建库/不迁移）→ `ContextDatabaseImpl` → `AlcyoneVfs.create(vfsConfig)` → 复查状态后发布 `RUNNING`。并发 start 共享一个 `CompletableDeferred`，只初始化一次；`RUNNING` 重复 start 幂等（不重建资源）。
- **失败/取消**：启动异常或取消 → `NonCancellable` 中逆序释放已取得资源、`terminalFailure` 记录主异常（清理失败 `addSuppressed`）、状态 `CLOSED`、共享结果携带同一失败；不会停留 `STARTING`。
- **close/closeAsync**：共享唯一 `CompletableDeferred`；`CREATED` 直接 `CLOSED`；`RUNNING` 时 `STOPPING → 逆序释放（vfs.close → executor.close）→ CLOSED`；失败不中断后续释放，主异常保留、其余 suppressed，共享结果抛同一失败且状态为 `CLOSED`；`CLOSED` 后 close 仍共享此前结果（含启动失败）。
- **start/close 竞争**：close 在 `STARTING` 期间把状态改为 `STOPPING` 并等待 start；start 在发布前复查状态，失败路径自行释放资源并转 `CLOSED`，**从不发布 RUNNING**；close 正常完成。
- **在途与观察期限**：`shutdownTimeout` 只是观察上限，不是强杀；close 始终等待 CT04 执行器 drain 与 VFS 自身等待完成，到点不提前发布 `CLOSED`、不抢关连接；仅在 `lastShutdownExceededObservationWindow` 记录是否超窗。
- **getStatus**：`lifecycle` 取真实状态；`workers` 空 Map；`storage.databasePath` 为规范化绝对路径；`databaseInitialized` 只来自 start 的 `Factory.open` 校验成功（缓存事实）。
- **无后台资源**：不创建 LLM/Worker/Cron/HTTP，runtime 不引入 Ktor Server。

## 3. 测试与断言（27 例新增；修正后总数 49）

| 测试类 | 例数 | 关键断言 |
| --- | --- | --- |
| `AlcyoneContextFactoryTest` | 10 | create 前后目录快照完全一致、CREATED、`databaseInitialized=false`、再次 getStatus 仍零创建；同文件 / 挂载根暴露 / 大小写别名（按文件系统判定）拒绝；5 组非法 vfs 配置 `INVALID_CONFIG`（含字段路径）；initialize 建库、二次初始化拒绝；未初始化 start → `DATABASE_NOT_INITIALIZED` 且不建 context/vfs 库；空文件/错版/损坏 start 拒绝且字节不变；显式初始化后 start → RUNNING、`databaseInitialized=true`、VFS 库由 VFS 创建、close → CLOSED；close 后 start → CLOSED |
| `AlcyoneContextLifecycleTest` | 7 | 并发 start 只调用一次初始化探针、两个等待者都成功；RUNNING 重复 start 返回同一数据库入口；CREATED 直接 close（零创建、重复 close 共享成功）；启动失败后 close 抛**同一异常实例**、重复 closeAsync 同失败、start 返回 CLOSED；并发 start 失败共享同一异常、无 VFS 残留、状态 CLOSED；close 赢得竞争（探针固定交错）：start 得 CLOSED、close 成功、状态 CLOSED、VFS 锁可重取且 Context 库可重开；取消 start 发起者：清理已取得资源、状态 CLOSED、可重开 |
| `AlcyoneContextInFlightCloseTest` | 2 | 受控阻塞写在途时 close 300ms 内不返回；写退出后 close 完成、连接/锁释放；1ms 观察窗被超过但只记录、不提前关闭；取消一个 close 等待者（`waiter.isCancelled`）不影响共享清理，主 close 仍成功、状态 CLOSED |
| `AlcyoneContextConfigOwnershipTest` | 2 | 直接 config / Loader config：create 后修改原 mounts（指向 private）与 namespaces，start 仍挂载安全目录；真实 list 仅见 safe marker，private marker 与 context.db* 均不可见 |
| `AlcyoneContextCloseSharingTest` | 5 | sync/async 混合 close 只清理一次并共享结果；释放探针失败由 close 与所有后续 close/closeAsync 共享同一实例且状态 CLOSED、资源仍释放；start/close 竞态中释放失败由 start 与 close 共享；同步线程 interrupt 不中止清理且恢复中断标志；单线程 dispatcher 上 closeAsync 不与在途协程死锁 |
| `AlcyoneContextVfsIntegrationTest` | 1 | 真实 LocalFs 挂载（命名空间 `memory`）start → RUNNING、Context/VFS 库就位、状态路径正确；close → CLOSED；同配置重开 → RUNNING → CLOSED（VFS 独占锁已释放） |

既有 `ContextConfigLoaderTest` 20 例与 `RuntimeSkeletonTest` 2 例保持全绿（路径隔离抽取无回归）。

## 4. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `./scripts/dev gradle :context:runtime:spotlessApply :context:runtime:compileKotlin --console=plain` | 0 | 生产批次编译通过 |
| `./scripts/dev gradle :context:runtime:spotlessApply --console=plain` | 0 | 已格式化；`spotless-apply.log` |
| `./scripts/dev gradle :context:api:compileKotlin :context:runtime:test :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | **49 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 49 = XML `tests` 49，**差额 0**；`targeted-test.log` |

计数：49 = 工厂 10 + 生命周期 7 + 关闭屏障 2 + 关闭契约 5 + 配置所有权 2 + LocalFs 集成 1 + B1 配置 20 + 既有 skeleton 2。初版证据在 `.local/validation/cm1/worker/ct06-b2/`；裁定修正证据在 `.local/validation/cm1/worker/ct06-b2-fix/` 与 `.local/validation/cm1/worker/ct06-b2-fix2/`（`probe-before.log`、`targeted-test.log`、`mutable-probe-after.log`、`mutable-probe-fix-after.log`、`spotless-apply.log`、`final-source-hashes.json`、`verification.json`）。

## 5. 改动文件与最终 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `.../api/AlcyoneContext.kt` | `4ba048c48bff76719f3ae31a9c82ffaaf39062657079c6e796a486cd6b8b185e` |
| `.../runtime/AlcyoneContextFactory.kt` | `0e87697abd793bb3601604664a8f95d92545401688102ff5b8043cae92ef11c3` |
| `.../runtime/AlcyoneContextImpl.kt` | `18a9e64dfa4d592131039e51c82bd13e40d154c9b43da1d3a9f593a0015bb04e` |
| `.../runtime/ContextPathIsolation.kt` | `7629e4f3d4a58baf986c17d84c75546f75309c4fbf9da1e4fc6903c2f14ff325` |
| `.../runtime/ContextConfigLoader.kt` | `c0643f31dcad225a675b0a7ea277a9f31aa2d1a968e9e88a9fce80837261d111` |
| `.../test/ContextFixture.kt` | `d6db1d612e2da03c02f8267c78fe9b5774ccb7343e4d60537cb50d04c58ebb4c` |
| `.../test/AlcyoneContextFactoryTest.kt` | `b0483b35e0408ec0ddb53dde1cdd1d27ed5010ca3c82dfa39a36dec36086c1ec` |
| `.../test/AlcyoneContextLifecycleTest.kt` | `22673113fd03851902492742feacc12f39943a4ee558de88b3e94119f9e2b4f2` |
| `.../test/AlcyoneContextInFlightCloseTest.kt` | `4aba349e5a6e30276f9325bc2074786961130e1e5664c87557b1e542aa3c84cd` |
| `.../test/AlcyoneContextConfigOwnershipTest.kt` | `8c4dbfe8ffe5c291326eb0fea8cd9db5b8cd232efa4aa5696a778e00bd39c173` |
| `.../test/AlcyoneContextVfsIntegrationTest.kt` | `fa0bf965d2be3bbf757750905273ac475165014386ae74dface86054e6e73ff2` |
| `.../test/AlcyoneContextCloseSharingTest.kt` | `93ae48cf6884d4aa67d1db14f714b8989991b32b070c0ad1511bc7b60ba41241` |

完整清单见 `final-source-hashes.json`。

## 6. 未做项与边界

- **未做 CT07**（`ingestConversation` / Ingestion / HTTP）与 CM2；`AlcyoneContext` 未放 TODO/假实现；无 LLM/Worker/Cron/HTTP，runtime 不引入 Ktor Server。
- 未改 Schema/API/Core/Persistence/CT04/VFS 源码/进度入口/AGENTS/Architect 文件；未 `git add/commit`。
- `getStatus` 的 `databaseInitialized` 是「本实例已验证可用」的缓存事实：显式 `initializeDatabase` 本身不作用于既有实例，实例在 start 成功 open 后才为 true。
- 未启动时没有业务入口，`NOT_STARTED` 暂无适用对象（CT07 接线业务入口时使用）。
- `AlcyoneContextImpl.databaseForTesting()` 与 `StartProbe` 为同模块测试/诊断注入点，不进入公开 SDK 契约。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 独立初验与 Architect 独立复核。

## 7. 接口裁定修正记录（2026-10-29）

| 项 | 修正 |
| --- | --- |
| 接口位置 | `context/api/.../AlcyoneContext.kt`（`AutoCloseable`，`override fun close()`、`suspend fun closeAsync()`、`suspend fun getStatus()`）；runtime 重复接口删除 |
| 共享关闭 | 唯一 `CloseState`（`CompletableDeferred` + `CountDownLatch`），lock 外等待；同步发起者在独立清理线程执行 `runBlocking` 桥接，挂起发起者在 `NonCancellable + Dispatchers.IO` 执行 `vfs.close()` + `executor.closeAsync()` |
| 中断语义 | 同步线程被 `Thread.interrupt` 时 `latch.await`/`join` 捕获后继续等待真实结果，结束后恢复中断标志；清理线程中断状态干净，不会被 `InterruptedException` 打断 |
| 失败传播 | close 获胜分支释放失败写入 `terminalFailure` 并共享给 start/close；`shutdown` 在 `startAttempt.await()` 之后读 `startAbortedByClose`，真实失败不被吞；CLOSED 后 close/closeAsync 抛同一存储失败 |
| 测试钩子 | `internal ReleaseProbe`（VFS/EXECUTOR），生产空实现；`StartProbe` 保留 |
| 计数 | 47 例，0 失败/错误/跳过；源码 `@Test`=XML=47 |

## 8. §5 R1 与测试判据加强记录（2026-10-29）

| 项 | 落点 |
| --- | --- |
| 配置快照 | `Factory.build`：先 `namespaces.toSet()` / `mounts.toList()` 并 `copy` 新 `VfsRuntimeConfig`，校验与启动只用这份快照 |
| 真实回归 | `AlcyoneContextConfigOwnershipTest`：safe/private marker + private 内 Context 库；直接 config 与 Loader config 两路径，create 后改原集合，start 后 list 仅见 `safe-marker.txt` |
| 判据加强 | `closeWaiterEnteredForTesting`（beginClose 非 owner 分支）、`startWaiterEnteredForTesting`（STARTING 非 owner 分支）、`StartGate`（打开前闸门）；失败例证明第二个 start 已等待后收到同一失败实例 |
| 探针 | 原 Architect `MutableConfigProbe` 保留并重跑：`START_ACCEPTED_MUTATED_MOUNT=true`、`ACTUAL_VFS_LIST=[]`（未使用被改 mount）；另有 `MutableConfigProbeFix`：`SAFE_MARKER_VISIBLE=true`、`PRIVATE_MARKER_VISIBLE=false`、`CONTEXT_DB_VISIBLE=false`、`MUTATED_MOUNT_LEAK=false` |
| 红→绿 | 未修复快照时 49 例 2 失败（两条所有权回归）；修复后 49 例全绿 |
