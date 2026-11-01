# CT04-F2 Worker 交付：资源所有权与关闭协议

## 本次修改（2026-10-28，F2 rev5 A04 正常路径直接 get）

- 触发：Architect 第 11 节要求 A04 正常路径恢复直接 get（requireNotNull 确保已启动），让 TimeoutException/ExecutionException 传播；finally 保留 runCatching 尽力回收。
- 改动：仅测试文件 1 个（F2ResourceOwnershipAndCloseProtocolTest.kt，A04 正常路径 L412-L415）：
  1. writer?.let { requireNotNull(it).get(30, TimeUnit.SECONDS) }
  2. asyncCloser?.let { requireNotNull(it).get(30, TimeUnit.SECONDS) }
  3. syncCloser?.let { requireNotNull(it).get(30, TimeUnit.SECONDS) }
- finally 块保留三个 runCatching { ...?.get(...) } 不变（L421-L424）。未重写整段、未动生产、未新增抽象、未删现有断言。
- 范围：仅改动测试文件 1 个。生产代码完全不改。未重写 F1，未做 F3（R3/R5），未碰 CT05、VFS、common、进度入口、AGENTS、Architect 复核文件；未 git add/commit。
- 实跑：./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks -> exit 0，70 例，failures/errors/skipped 均 0，源码 @Test 与 XML tests 差额 0（F2 文件 tests="10"，A04 无 failure）；spotlessApply --rerun-tasks -> exit 0；spotlessCheck --rerun-tasks -> exit 0。
- 证据：.local/validation/cm1/worker/ct04-f2-rev5/（targeted-test.log、spotless-apply.log、spotless-check.log、final-source-hashes.json 含测试文件新 SHA；生产 SHA 不变）。
- 结论：F2 rev5 A04 正常路径直接 get 完成，待 Lead 初验、Architect 独立复核。F2 通过不等于 CT04 完成：F3（R3/R5）与 CT05 未放行。

---

## 本次修改（2026-10-28，F2 rev3 A04 定点修正）

- 触发：F2 rev2 独立复核通过后，Lead 指定 A04 测试存在两处缺陷需定点修正（不改生产代码）：
  (1) `syncReturned = CountDownLatch(1)` 初始计数为 1，`spinUntil { syncReturned.count == 1L || ... }` 立即退出，未确定性确认同步 close 已进入等待分支；
  (2) 断言失败后未放行 `releaseWrite`，导致 `executor.use` 在写回调处等待至 30 秒超时才收尾。
- 修正：改为等待 `syncThread.state == WAITING || syncReturned.count == 0L`，再断言 `state == WAITING` 且 `syncReturned.count == 1L`（仍未返回）；在 `try/finally` 中放行 `releaseWrite` 并 `runCatching` join 已启动的写/关闭任务，务必在 `executor.use` 退出前完成，失败分支不挂住。保持原断言语义：在途写未退出前，sync 与 async close 都不得返回（`asyncReturned.count == 1L`、`syncReturned.count == 1L`）。
- 范围：仅改动测试文件 1 个（`F2ResourceOwnershipAndCloseProtocolTest.kt`，A04 落点 L332）。**生产代码完全不改**。未重写 F1，未做 F3（R3/R5），未碰 CT05、VFS、common、进度入口、AGENTS、Architect 复核文件；未 `git add/commit`。
- 实跑：`./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks` → **exit 0，70 例，failures/errors/skipped 均 0，源码 @Test 与 XML tests 差额 0**（F2 文件 `tests="10"`，A04 0.009s 无 failure）；`spotlessApply --rerun-tasks` → exit 0；`spotlessCheck --rerun-tasks` → exit 0。
- 证据：`.local/validation/cm1/worker/ct04-f2-rev3/`（`targeted-test.log`、`spotless-apply.log`、`spotless-check.log`、`final-source-hashes.json`）。
- 结论：**F2 rev3 A04 定点修正完成，待 Lead 初验、Architect 独立复核**。F2 通过不等于 CT04 完成：F3（R3/R5）与 CT05 未放行。

### 一、rev3 最终源码哈希（SHA-256）

| 文件 | SHA-256 | 相对 rev2 |
| --- | --- | --- |
| `context/persistence-sqldelight/src/test/kotlin/com/github/noahshen/alcyone/context/memory/persistence/sqldelight/F2ResourceOwnershipAndCloseProtocolTest.kt` | `5ba2a3b5b61d5706bb5201235aaca7ffb99967b358380c6d112233f31ae1878b` | 由 `51da0135…` 变更（A04 定点修正）|

生产文件 `ContextDatabaseExecutor.kt`、`ContextSlot.kt`、`ContextSqliteErrors.kt` 未改动（与 rev2 相同哈希）。
完整清单见 `.local/validation/cm1/worker/ct04-f2-rev3/final-source-hashes.json`。

---

## 本次修改（2026-10-28，F2 rev2 修复 R1/R2/R3）

- 触发：Architect 复核第 8 节（`架构与技术复核.md`）F2 未通过，确认三处缺陷（R1 中断后放弃释放、R2 `queryRaw` mapper 绕过关闭守卫、R3 正式测试伪证）。本次仅修 F2 范围：资源所有权/关闭 + 用户回调守卫。**未**重写 F1，**未**做 F3（R3 driver 逃逸 / R5 事务），**未**碰 CT05、VFS、common、进度入口、AGENTS、Architect 复核文件；未 `git add/commit`。
- 生产改动 2 文件（`ContextDatabaseExecutor.kt`、`ContextSlot.kt`）+ 测试改动 1 文件（`F2ResourceOwnershipAndCloseProtocolTest.kt`，A01～A09）。
- 实跑：`:context:persistence-sqldelight:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks` → **exit 0，16 tasks executed**；源码 `@Test`=**70**，XML `tests`=**70**，**差额 0**，failures/errors/skipped 均 0。`:spotlessApply --rerun-tasks` → exit 0。
- 反例复跑：harness 修正版 `F2ProbeRev2` **多次连跑全 GREEN**（R1 `interrupted=false`、连接残留 `0`、中断标志恢复 `true`；R2 mapper `insideScopeBody=true`、`close` 具名拒绝、不死锁）。
- 结论：**F2 rev2 完成，待 Lead 初验、Architect 独立复核**。F2 通过不等于 CT04 完成：F3（R3/R5）与 CT05 未放行。

### 一、rev2 最终源码哈希（SHA-256）

| 文件 | SHA-256 | 相对 rev1 |
| --- | --- | --- |
| `ContextDatabaseExecutor.kt` | `52f72fed2387c15a321c7516cdd89faac09a97d5e4c3f77f48aa1e5c77b24113` | 由 `6a49a48c…` 变更 |
| `ContextSlot.kt` | `d37573190f6a6aa5efcf7502882eead7d54ccabe69ebdff77968e25a5d0d41c9` | 由 `8fbaf5dc…` 变更 |
| `F2ResourceOwnershipAndCloseProtocolTest.kt` | `51da01359cbec082063bde127285eb6a5ce870bd602cb473e92caf6970fa1b29` | 由 `e3713cfd…` 变更 |

完整清单（含探针源）见 `.local/validation/cm1/worker/ct04-f2-rev2/final-source-hashes.json`。证据目录 `.local/validation/cm1/worker/ct04-f2-rev2/`：`targeted-test.log`、`spotless-apply.log`、`spotless-check.log`、`F2Probe-after-rev2.log`、`probe-before-rev2.log`（修复前红）、`F2ProbeRev2.java`、`R2MapperProbe.java`。

### 二、F2-R1 落点（关闭的唯一清理责任不可被调用线程中断取消）

| 落点 | 改动 |
| --- | --- |
| `ContextDatabaseExecutor.kt` L220-241 `close()` | 发起者 `awaitDrainedBlocking()` 返回中断标志；`releaseAll()` 照常执行；成功后 `protocol.complete(Unit)`，最后按标志 `Thread.currentThread().interrupt()`。失败路径同样在发布共享失败后恢复中断再上抛。**绝不在仍有在途 JDBC 时抢关连接。** |
| `ContextDatabaseExecutor.kt` L390-407 `awaitDrainedBlocking(): Boolean` | 等待 `inFlight>0` 时捕获 `InterruptedException` → 置 `interrupted=true` → **继续等待**直到真实归零；返回是否被中断；不在方法内恢复标志（由调用者在资源释放完成后恢复）。 |
| `ContextDatabaseExecutor.kt` L489-505 `CloseProtocol.awaitBlocking()` | 非发起者等待同一完成结果时，中断同样「记录→继续等待→完成后恢复标志」，再如实抛出共享失败；同步/异步等待者观察统一实际完成结果。 |
| `ContextSlot.kt` L218-252 `close()` | `disposed` 置位后不再提前 return；线程等待失败/被中断都**不跳过物理 close**：先 `shutdown` + `awaitTerminationInterruptibly()`，无论线程结果如何都尝试 `physical.close()`，最后按标志恢复中断并如实抛出（清理失败以 suppressed 附加）。 |
| `ContextSlot.kt` L254-274 `awaitTerminationInterruptibly()` | 首次 `awaitTermination` 被中断 → 记标志 → `shutdownNow()` → **循环重试** `awaitTermination`，直到线程确实终止或终止判定失败（`require`）；必要时多次 `await`，结果纳入判定，不伪报释放成功。 |

对反例：正式测试 `A08 interrupted sync close initiator still completes unique cleanup and matches real final state()`（L565）；Architect `F2Probe` 的 R1 段（修后连接残留 2 → 0）。

### 三、F2-R2 落点（所有用户回调统一嵌套/关闭守卫）

| 落点 | 改动 |
| --- | --- |
| `ContextDatabaseExecutor.kt` L264-284 `withUserCallback`（新增） | 统一守卫：在同线程置位 `insideCallback` + `insideScopeBody`，`finally` 各自恢复。`write`/`read` 正文经 `withScope` 走它，`queryRaw` 的 `mapper` 也在 `withContext(slot.dispatcher)` 内走它。 |
| `ContextDatabaseExecutor.kt` L249 `withScope` | 回调正文包裹改用 `withUserCallback`，行为与原两处 ThreadLocal 置位/恢复一致（含 scope `invalidate`）。 |
| `ContextDatabaseExecutor.kt` L154-171 `queryRaw` | mapper 执行包进 `withUserCallback`，与 `write`/`read` 一致；`beginClose`（L345）对 `insideScopeBody` 抛具名 `closeInsideCallback()`（`CLOSED`），同步/异步关闭在 mapper 内均具名拒绝、不死锁。 |

对反例：正式测试 `A09 close inside queryRaw mapper is rejected by name without deadlock()`（L654）；worker 直接探针 `R2MapperProbe.java`（mapper `close()` 立即返回 `CLOSED`，查询完成 `true`）。

### 四、F2-R3 落点（正式测试排除提前清理与调度伪通过）

| 要求 | 落点 |
| --- | --- |
| A03 构造、故障关闭、真实连接断言都在兜底之前 | `A03`（L283）全部断言（close 失败可见、`closeAttempts==[1,0]` 每槽恰好一次、cause 的 suppressed==1、`recorded.size==3`、全部已关、同步/异步等待者看到同一 `failure` 实例）都在 `interceptDriver` 工作块内完成；兜底 `recorded.forEach close` 只作测试自身防线，不参与结论。 |
| A04 受控确认同步 close 已进入等待 | `A04`（L332）用 `syncThread` 记录线程 + `spinUntil { state==WAITING }` 受控确认后才做「未提前返回」断言，替代仅靠 `syncStarted.countDown` 启动计数。 |
| A02 配置 PRAGMA 执行阶段故障（与 version createStatement 区分） | 原 A02 收窄为「版本/结构校验的 createStatement 阶段」；新增 `A02b`（L221）在 `applyPragmas` 的 `execute("PRAGMA …=…")` 阶段注入故障，断言 `failure.message` 含 `applying PRAGMA` 且真实连接全关。 |
| 补 R1/R2 正式反例 | `A08`（L565，F2-R1 中断发起者仍完成唯一清理）、`A09`（L654，F2-R2 mapper 内 close 具名拒绝、不死锁）。 |
| finally 释放屏障/回收线程 | `A04`/`A08` 的 `pool.shutdownNow()+awaitTermination` 在 `finally`；`A08` 的 `releaseWrite.countDown()`/`writer.get` 在 `finally`；`A06`/`A09` 守护线程 + `join(15s)` 死锁护栏，失败分支不挂住。 |
| 连接断言紧贴真实连接 | 新增 `closedFlagOf`/`spinUntil`/`assertAllRecordedClosed` 辅助；`A01`/`A02`/`A02b`/`A06`/`A09` 的「全部真实连接已关」改为工作块内断言（不再落到兜底之外）。 |

### 五、命令与退码

| 命令 | 退码 |
| --- | --- |
| `./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks` | 0（70 例，0/0/0） |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessApply --rerun-tasks` | 0 |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessCheck --rerun-tasks` | 0 |
| `javac+java F2ProbeRev2`（修后） | 0，`VERDICT=GREEN` |
| `javac+java F2Probe`（Architect 原探针，修前） | 0 但红：`sync close interrupted=true`、`connections left open…=2`（见 `probe-before-rev2.log`） |

### 六、Architect 原 `F2Probe` 复跑说明（未改 Architect 文件）

原探针 R1 段存在**自身 harness 竞态**：`closer.interrupt(); closer.join(5000); release.countDown();` 先 `join` 后 `release`。修订后的正确语义要求「发起者被中断也不放弃清理」，close 必须等在途写真正退出（`release` 触发）才返回；二者互锁，原探针只能靠 5s 窗口的毫秒级竞态偶然读出 `interrupted=false / connections left open=0`，其余多为 `release` 之后的 writer `await` 超时。故在原文件之外新增harness 修正版 `F2ProbeRev2.java`：受控确认「中断后 close 仍在等待、连接未被抢关」→ 再 `release` → 断言 close 正常返回、中断标志恢复、连接全关；连跑全 GREEN。原探针文件及其目录未改动。

### 七、未关闭项与边界

- 未做 F3（R3 driver 逃逸 / R5 事务）与 CT05；未改 F1 既有实现；未动 VFS/common/进度入口/AGENTS/Architect 复核文件。
- F2 通过不等于 CT04 完成，F3/CT05 由 Lead/Architect 决定放行。
- 本文件下方「2026-10-27 …rev1」节保留为历史，其哈希与行号仅反映 rev1。

## 本次修改（2026-10-27，CT04-F2 资源所有权与关闭协议实现）

- 范围：仅 `context/persistence-sqldelight` 的 `ContextDatabaseExecutor` / `ContextSlot` 资源与关闭路径，对应架构复核 §4.2 第 2 批（R1 资源所有权/释放 + R2 关闭协调与准入）。**未**改 F3（R3/R5 事务/作用域）、**未**做 CT05 接线、**未**动 VFS/common/进度入口/AGENTS/Architect 复核文件。
- 交付物：生产改动 2 个文件（`ContextDatabaseExecutor.kt`、`ContextSlot.kt`；`ContextSqliteErrors.kt` 未改）+ 新增正式测试 `F2ResourceOwnershipAndCloseProtocolTest.kt`（A01～A07）；证据 `.local/validation/cm1/worker/ct04-f2/`。
- 实跑：`:context:persistence-sqldelight:test --rerun-tasks` → **67 例，0 failures / 0 errors / 0 skipped**（F2 新文件 7 例）；源码行首 `@Test`=67，XML `tests`=67，**差额 0**。`:spotlessApply` / `:spotlessCheck --rerun-tasks` → exit 0。
- 结论：**F2 完成，待 Lead 初验、Architect 独立复核**。F2 通过不等于 CT04 完成：R3/R5（F3）与 CT05 未放行。

### 一、最终源码哈希（SHA-256）

| 文件 | SHA-256 |
| --- | --- |
| `ContextDatabaseExecutor.kt` | `6a49a48c496b3375c91236fd7f59f5c57641dd7cc21044e1eaaeceb52a618868` |
| `ContextSlot.kt` | `8fbaf5dc2195f3564c83508fe1edf6bb4463fc7f4a31cac377c49c1c9be53335` |
| `ContextSqliteErrors.kt`（未改） | `6faaa81cd5a1865da194825e33598530db8c0ebfbf36ae9c70759dc7df266655` |
| `F2ResourceOwnershipAndCloseProtocolTest.kt` | `e3713cfdf5db86d46faf6be92796224af0c1c2979bdfe80e176a7a99051dd135` |

完整清单见 `.local/validation/cm1/worker/ct04-f2/final-source-hashes.json`。

## 二、R1 资源所有权与释放（改动点与落点行号）

| 落点 | 改动 |
| --- | --- |
| `ContextDatabaseExecutor.kt` L53-56 | `slots = startSlots(path, readSlotCount) { … }`，取代原来「字段初始化先建 writeSlot、再 openReadSlots」的流程；`writeSlot`/`readSlots` 改为持有者 getter（L58/L60）。 |
| `ContextDatabaseExecutor.kt` L496-524 `startSlots` | 新资源登记流程：先开写 Slot 立即 `acquired +=`，再逐个开读 Slot 登记；全量 `validateSchemaVersion`→`validateMissingObjects`→`applyPragmas`。任一步失败调 `releaseAll(primary)`：`acquired.asReversed()` 逐个 `runCatching{close}`，清理异常 `addSuppressed` 到主失败，**绝不中途跳过**，主失败原样上抛。 |
| `ContextDatabaseExecutor.kt` L334-352 `releaseAll`（关闭路径） | 逆序 `(readSlots.reversed() + writeSlot)` 逐个尽力 close、收集失败，`released` 标志防重复；有失败则以 `ContextPersistenceException("releasing…")` + suppressed 呈现，不伪报释放成功。 |
| `ContextSlot.kt` L74-88 构造 `init{}` | 「先开连接、再建驱动」；连接取得后任何构造失败 → 关闭该连接（失败 suppressed）→ `stopExecutorAfterConstructionFailure` → 上抛；连接未取得也停线程。 |
| `ContextSlot.kt` L232-243 | 构造期线程停止失败以 suppressed 附加，不阻断主失败。 |
| `ContextSlot.kt` L213-229 `close()` | 第二次 `awaitTermination` 结果纳入判定（`require`，未终止即清理失败）；`physicalConnection` 为空时跳过连接关闭。 |

对反例：F2-A01（读 Slot 打开失败→写连接已关）、F2-A02（连接取得后校验失败→该真实连接已关）、F2-A03（单 Slot close 失败→其余仍全部尝试、失败可见）。

## 三、R2 关闭协调与准入（改动点与落点行号）

| 落点 | 改动 |
| --- | --- |
| `ContextDatabaseExecutor.kt` L78 `protocolLock` | 单一同步边界（`Object`），同步 `close()` 与协程 `closeAsync()` 共用，取代原 `Mutex closeGate` + 独立 `synchronized(this)` 双通道。 |
| L81-93 | `closed: AtomicBoolean`、`inFlight: Int`、`drainWaiters: CopyOnWriteArrayList`、`closeProtocol: CloseProtocol?`、`released` 全在该锁内读写；`closed` 保留 `AtomicBoolean` 供独立探针读取。 |
| L274-284 `acquireRequest` | 在 `synchronized(protocolLock)` 内一次完成「`closed` 复查 + `inFlight++`」，消除「关闭见 0 释放 → 已准入请求再增计数」窗口。 |
| L286-293 `releaseRequest` | 在锁内 `inFlight--`，归零时 `notifyAll()` 唤醒阻塞等待者并完成全部 `drainWaiters`。 |
| L321-331 `beginClose` | 回调内（`insideScopeBody`）close 抛具名 `closeInsideCallback()`；同锁内封闭新请求、创建唯一 `CloseProtocol`。 |
| L184-206 `closeAsync` | 发起者在 `NonCancellable` 内 `awaitDrained + releaseAll`；**成功/失败结果均在 `NonCancellable` 内发布**；末尾 `ensureActive()` 让发起者自身被取消时原样传播、不改变共享结果。非发起者 `protocol.await()` 无功跳过、不隔离取消。 |
| L213-228 `close` | 非发起者 `protocol.awaitBlocking()` **阻塞等待同一结果，不提前 return**；失败 `completeExceptionally` 后上抛。 |
| L437-472 `CloseProtocol` | 唯一完成结果：协程 `await()` + 阻塞 `awaitBlocking()` 共享 `finished/failure`；`complete` 幂等（`runCatching`）。 |

对反例：F2-A04（sync/async 等待同一结果）、F2-A05（关闭后 CLOSED）、F2-A06（回调内 close 具名拒绝、免死锁）、F2-A07（等待者取消不影响真实清理、`CancellationException` 原样传播）。

### 本步相对 Lead 确认版（`8df6dbe2`）的最小生产修正

测试驱动下发现并修正 3 处（均属方案 §3「失败/取消对等待者可见」的直接要求，非架构决策）：

1. `closeAsync`：成功/失败结果发布全部移入 `NonCancellable`，末尾补 `currentCoroutineContext().ensureActive()`。原实现成功路径在 `withContext` 块外发布，发起者被取消时会把共享结果误标失败或漏发（影响 A07）。
2. `close()`：成功路径 `protocol.complete(Unit)` 移出 `try/catch`（失败路径仍 `completeExceptionally` 后上抛），保证同步清理失败对所有等待者一致可见。
3. `drainWaiters` 由 `mutableListOf` 改为 `CopyOnWriteArrayList`（双端迭代安全）。

`ContextSlot.kt` 与 Lead 确认版完全一致（`8fbaf5dc`）。

## 四、反例 F2-A01～A07 覆盖对照

正式测试：`F2ResourceOwnershipAndCloseProtocolTest.kt`（7 例，本文件 `tests=7 failures=0 errors=0`）。手段：真实文件型 SQLite + 包装 `java.sql.Driver` 记录每一条真实连接、断言 `Connection.isClosed`；并发用 `CountDownLatch`/内部 `closed` 标志受控同步，无 sleep 概率。详细对照见 `.local/validation/cm1/worker/ct04-f2/A01-A07-coverage.md`。

| 编号 | 用例（行号） | 决定性断言 |
| --- | --- | --- |
| A01 | L118 | 捕获的真实写连接 `isClosed==true`，全部已取得连接关闭 |
| A02 | L140 | `createStatement`（校验阶段）失败后读/写真实连接均 `isClosed`，构造抛 `ContextPersistenceException` |
| A03 | L191 | 两个读 Slot 都被尝试 close（计数含 0/1）；失败可见（`releasing…`）；其余连接不泄漏 |
| A04 | L246 | async 为发起者、sync 为等待者；在途写未退出前两者均未返回；收尾后连接全关 |
| A05 | L320 | `read`/`write`/`queryRaw` 均 `CLOSED`，重复 5 轮幂等 |
| A06 | L346 | 同步/异步回调内 close 均 `CLOSED` 且 message 含 `inside a read/write callback`；守护线程 join 15s 不死锁；事后正常关闭释放全部连接 |
| A07 | L414 | 被取消等待者 `CancellationException("waiter cancelled")` 原样传播；真实清理仍完成、连接全关 |

保留既有 `PartialInitCleanupTest` / `LifecycleCloseTest` 全部断言（未删除、未改名，均 `failures=0`）。

### 定向变异（证明断言非摆设，详见 `mutation-record.md`）

| 变异 | 旧缺陷 | 咬住 |
| --- | --- | --- |
| M1 | 构造失败时 `drop(1)` 跳过写 Slot 释放 | A01、A02 FAILED |
| M2 | `close()` 发现已关闭即 `return` | A04 FAILED |
| M3 | `beginClose()` 去掉回调内具名拒绝 | A06 FAILED（28s 内结束，非挂死） |
| M4 | `releaseAll()` 首个 close 失败即中止 | A03 FAILED |

## 五、实际命令与退码

| 命令 | 退码 | 日志 |
| --- | --- | --- |
| `./scripts/dev gradle :context:persistence-sqldelight:test --rerun-tasks` | 0（67 例 0 失败） | `.local/validation/cm1/worker/ct04-f2/targeted-test.log` |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessApply` | 0 | — |
| `./scripts/dev gradle :context:persistence-sqldelight:spotlessCheck --rerun-tasks` | 0 | `.local/validation/cm1/worker/ct04-f2/spotless-check.log` |

证据目录：`.local/validation/cm1/worker/ct04-f2/`（`targeted-test.log`、`spotless-check.log`、`A01-A07-coverage.md`、`mutation-record.md`、`final-source-hashes.json`）。

## 六、未做范围 / 未关闭项（如实）

- **未做 F3**：R3（作用域保护可被原始驱动/SQL 入口绕过）与 R5（事务收尾失败会提交失败事务或掩盖错误）未触碰；F2 的关闭协议不改变事务语义。
- **未做 CT05**：无 Runtime 接线、无生成查询执行入口、无公共 API/诊断接口新增。
- **未改**：`vfs/*`、`common/*`、`integration-tests/vfs/*`、`gradle/libs.versions.toml` 既有版本、进度入口、AGENTS、Architect 复核文件；未 `git add`/`commit`/推远端。
- **R6 剩余分支**：本批沿用 F1 的脱敏口径，未扩大；Factory 其余 catch 分支属 F3 范围。
- **F2 通过不等于 CT04 完成**：CT04 仍 IN_REVIEW，CT05 未放行。
