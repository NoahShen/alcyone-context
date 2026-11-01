# F1（通知关闭虚假完成）Lead 独立初验

## 本次修改（2026-10-29，全面复查 F1 初验，提交 Architect 复核）

- 依据 `docs/context/milestones/cm1/CM1_全面复查.md`「修复裁定」第 1 条与 Architect 紧急纠正，由同一 Worker `d7695b22` 实施（单一写入者）；Lead 未写生产代码。
- 范围：仅 `context/runtime` 的通知关闭协议与测试；**未合并 P1**，未改 API/Core/Persistence/Server；无 git 写操作。

## 1. 交付物（SHA 已核对）

| 文件 | SHA-256（前缀） |
| --- | --- |
| `runtime/NotificationExecutionMarker.kt`（新增） | `28fd3d78f648303f` |
| `runtime/AlcyoneContextImpl.kt` | `746b3d7556f1b1fc` |

## 2. Lead 独立实跑（非引用 Worker 日志）

`:context:runtime:test :context:runtime:spotlessCheck --rerun-tasks` → exit 0；XML **84 例 0 失败/错误/跳过**；源码 `@Test`=84=XML 84，差额 0（原 75 + 新增 9）。日志 `.local/validation/cm1/lead/rereview-f1/check.log`。

## 3. 逐条对照裁定与紧急纠正

| 要求 | 实现核对 |
| --- | --- |
| 等待 Job 真实完成（含 finally/结构化子协程），不得以 clear/计数证明 | `cancelNotifications()` 改为 suspend：锁内先置 `notificationsClosed`、快照 scope/Job，锁外 `scope.cancel()` 后**逐个 `job.join()`**；`notificationJobs.clear()` 不再作为退出判据 |
| 完成信号仅由 `invokeOnCompletion` 产生 | 仍由 `job.invokeOnCompletion { notificationJobs.remove(job) }` 维护集合；close 不依赖该集合判定完成 |
| 通知内 close 在**加入共享等待之前**拒绝 | `close()` 第 229 行、`closeAsync()` 第 255 行均在 `beginClose()` 之前调用 `rejectIfInsideOwnNotification*` |
| 标记随协程上下文传播、携带实例身份 | 新增 `NotificationExecutionMarker`：`ThreadContextElement` + `CoroutineContext.Element`，`updateThreadContext`/`restoreThreadContext` 安装还原线程值；由通知 scope 的 `launch` 携带（`launchNotification` 中 `... + NotificationExecutionMarker(this)`）；身份用 `=== this` 对象引用 |
| 不用普通 AbstractCoroutineContextElement / 不用手工 ThreadLocal 跨 suspend | 已按纠正：元素本身是 `ThreadContextElement`（框架托管安装/还原），非挂起 `close()` 直接读线程值；无手工 set/clear |
| 共享清理不可被等待者取消 | 保持 `NonCancellable`；等待者取消只取消自身 await |
| create/start 零通知副作用 | 懒创建 scope，`notificationScopeCreatedForTesting()` 在 start 后仍为 false |

## 4. 测试核对（新增 9 例）

- **门控（sync/async 各一）**：`gateScenario` 用阻塞 notifier（`finally` 内 `withContext(NonCancellable){gate.await()}`）；断言 **gate 未放行 300ms 内 close 不返回**、`finished=false`；放行后 close 返回、`finished=true`（finally 置位）、状态 CLOSED、Job 数 0、reporter 收到 `CancellationException`。**不看计数判完成**。
- **通知内关闭**：同实例 `close()`/`closeAsync()` 均 `ContextLifecycleException(CLOSED)` 且返回（证明不死锁）。
- **跨线程**：通知内 `withContext(Dispatchers.IO){yield()}`、`withContext(Dispatchers.Default){yield()}` 之后仍拒绝。
- **结构化子协程**：`coroutineScope { async(IO){...}; launch(Default){...} }` 内仍拒绝。
- **外部关闭已发起**：close 在途未返回时通知内 closeAsync 仍拒绝；gate 放行后 close 才返回。
- **不同 Context 不误拒**；**通知退出后标记不残留**（`insideNotificationForTesting()=false`），普通 close 正常。
- R1/R2/R3 既有断言（含 `IngestionNotificationRaceTest`）全绿。

## 5. 结论与边界

- **F1 初验通过**：关闭不再虚报资源退出（真实 join），重入拒绝在共享等待前、经框架托管线程上下文桥接并覆盖跨线程/子协程/外部关闭/不同实例/退出不污染。
- 提交 **Architect 独立复核**；通过后再按 §9 修订设计实施 P1（不合并两批）。后续按 Q8：P4 → F2 → F3 → P2/P3，F4 随进度更新；开放项闭环并整体回归后直接启动 CT08，CM2 实现仍未启动。

## 6. F1-R1（关闭顺序）修复后 Lead 独立复核（2026-10-29）

- **R1 修复**：`shutdown()` 现为「同一锁内原子完成状态转 STOPPING（CREATED 直接 CLOSED）+ 捕获资源引用」→ **之后**才 `cancelNotifications()`（取消 + `join` 真实等待）→ 等 start 竞态 → 逆序释放 → CLOSED。此前 `cancelNotifications()` 排在状态转换之前，drain 期间仍 RUNNING 并接受 SDK。保持 CREATED/STARTING 竞态、资源捕获与逆序清理、共享失败；已准入在途仍真实 drain、不抢关。
- **R2 判据加强**：新增 `NotificationDrainBarrierTest`(3)：finally 内先 `countDown` 显式「已进入清理」屏障再等 gate，断言此刻 `lifecycle == STOPPING`、重复键与新键 SDK ingest 均 `CLOSED` 拒绝、库内无新行、close 未返回；含结构化子协程 finally 阻塞时 close 仍等待。门控测试改为等 draining 屏障而非计时猜。marker 不残留改为在**受控单线程 dispatcher 复用同一线程**验证（测试结束关闭 dispatcher）。`shutdown()` 注释更正为「通知取消与等待参与关闭完成判据，观察窗起点包含该等待」。
- **Lead 独立实跑**：`:context:runtime:test :context:runtime:spotlessCheck --rerun-tasks` → exit 0；XML **87 例 0 失败/错误/跳过**；源码 `@Test`=87=XML 87，差额 0（原 84 + 新增 3）。日志 `.local/validation/cm1/lead/rereview-f1-r1/check.log`。
- **Lead 独立复跑 Architect 探针**（`probe.init.gradle` + `NotificationAdmissionProbeTest`，不覆盖正式 XML）：`DRAIN_STATE=STOPPING; INGEST_ACCEPTED=false; RESULT=null`（修复前为 `RUNNING/true/DUPLICATE`），探针通过。日志 `.local/validation/cm1/lead/rereview-f1-r1/probe.log`。
- **SHA**：`AlcyoneContextImpl.kt`=`3dc754a5444195dce90d56c6e3d897553a2fd0f570168df150d08585a413f1be`；`NotificationExecutionMarker.kt` 未变；未合并 P1、未改 API/Core/其他模块。
- **结论**：F1-R1 关闭，F1 初验通过，提交当前 Architect 复核；通过后再按 §9 实施 P1，CM2 未启动。
