# CM1 全面复查 F1 Worker 交付（通知关闭虚假完成）

**任务**：CM1 全面复查裁定第 1 条 F1：关闭时等待在途通知真实完成 + 通知内关闭具名拒绝
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-29

## 本次修改（2026-10-29，F1-R1 关闭顺序修复与判据加强）

- **R1 顺序修复**：`shutdown()` 现在**先**在同一把锁内原子完成「状态转 STOPPING（CREATED 直接 CLOSED）+ 捕获 vfs/executor/database/ingestion」，**然后**才 `cancelNotifications()`（取消 + `join` 等待真实退出）。此前 `cancelNotifications()` 排在状态转换之前，导致 drain 期间仍为 `RUNNING` 且继续接受 SDK ingest。
- **R2 判据加强**：
  - 新增 `NotificationDrainBarrierTest`（3 例）：用「取消已进入 finally」的显式屏障（`finally` 内先 `countDown` 再 `gate.await()`），断言此时 `lifecycle == STOPPING`、重复键与新键 SDK ingest 均 `CLOSED` 拒绝、库内无新增行、close 未返回；gate 放行后 close 才返回、finally 真正退出、状态 CLOSED；另覆盖结构化子协程 `finally` 阻塞时 close 仍等待。
  - `NotificationCloseGateTest` 门控改为先等 draining 屏障，再断言 close 未返回，不再用固定毫秒猜测。
  - `NotificationReentrancyContextTest` 的标记还原改为在**受控单线程 dispatcher**（通知实际用过的那条线程）上验证，不再只看测试主线程；测试结束关闭 dispatcher/executor。
  - `shutdown()` 注释更正：通知取消与等待**参与**关闭完成判据，观察窗计时起点包含这段等待。
- Architect 探针 `NotificationAdmissionProbeTest` 修后：`DRAIN_STATE=RUNNING; INGEST_ACCEPTED=true` → **`DRAIN_STATE=STOPPING; INGEST_ACCEPTED=false; RESULT=null`**。
- 计数更新：runtime **87**（原 84 + `NotificationDrainBarrierTest` 3），源码 `@Test` = XML，差额 0。
- 未合并 P1、未改 API/Core/其他模块、未 `git add/commit`。

## 本次修改（2026-10-29，F1 通知关闭虚假完成）

- `cancelNotifications()` 改为 **suspend**：锁内先置 `notificationsClosed` 并快照 scope/Job，锁外 `scope.cancel()` 后对每个 Job `join()`——**等待真实退出**（`join` 覆盖 `finally` 与结构化子协程），不再用 `notificationJobs.clear()` 或计数 0 证明完成；对关闭时仍在跑的 Job 上报固定脱敏取消。
- 新增 `NotificationExecutionMarker`（`ThreadContextElement` + `CoroutineContext.Element`）：由通知 scope 的 `launch` 携带，协程框架在挂起/恢复时自动安装与还原线程值；身份用所属 `AlcyoneContextImpl` 对象引用。
- `close()` / `closeAsync()` 在进入 `beginClose()`（共享关闭等待）**之前**检查自身通知标记，命中即抛 `ContextLifecycleException(CLOSED)`（具名拒绝、不等待、不死锁）；不同 Context 实例不误拒。
- 新增 9 个测试：门控（sync/async 各一，用 `finally` 置位的 `AtomicBoolean` 证明真实退出，而不是计数）、通知内 sync/async 关闭拒绝、结构化子协程内关闭拒绝、`yield`+dispatcher 切换后仍拒绝、外部关闭已发起时仍拒绝、不同 Context 不误拒、通知退出后标记不残留。
- 未改 API/Core/其他模块；未合并 P1；未 `git add/commit`。

## 1. 关闭等待真实完成

```kotlin
private suspend fun cancelNotifications() {
    val scope: CoroutineScope?
    val pending: List<Job>
    synchronized(notificationLock) {
        if (notificationsClosed) return
        notificationsClosed = true
        scope = notificationScope
        notificationScope = null
        pending = notificationJobs.toList()
    }
    scope?.cancel()
    pending.filter { !it.isCompleted }.forEach { reportNotificationCancellation() }
    // join() 覆盖 finally 与结构化子协程：gate 未放行时这里不会返回。
    pending.forEach { it.join() }
}
```

- `shutdown()` 本身已是 suspend，在 `NonCancellable` 清理路径中调用；不会与既有清理协议冲突。
- `scope.cancel()` 只发“停止”请求，**完成判据是 `join()`**；取消路径不会手动完成任何完成信号。
- 既有语义保持：`notificationJobs` 由 `invokeOnCompletion` 移除，但移除**不是**退出证明。

## 2. 通知内关闭拒绝（重入标记）

```kotlin
internal class NotificationExecutionMarker(
    private val owner: AlcyoneContextImpl,
) : ThreadContextElement<Any?>, CoroutineContext.Element {
    override fun updateThreadContext(context: CoroutineContext): Any? { /* 当前线程值 = owner */ }
    override fun restoreThreadContext(context: CoroutineContext, oldState: Any?) { /* 还原 */ }
    companion object Key : CoroutineContext.Key<NotificationExecutionMarker> {
        fun currentOwner(): Any? // 读当前线程值
    }
}
```

- 通知 scope = `SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + NotificationExecutionMarker(this)`，所以通知体、`yield`、`withContext(Dispatchers.IO)`、结构化子协程都继承同一标记。
- **为什么不是普通 ThreadLocal**：跨 suspend 换线程会失效，且非挂起 `close()` 无法读取 `currentCoroutineContext`。`ThreadContextElement` 由协程框架负责安装/还原，正好补上这一环。
- 两个入口都先检查再 `beginClose()`：

```kotlin
override fun close() {
    rejectIfInsideOwnNotification("close()")   // 在 beginClose 之前
    val (mine, state) = beginClose()
    ...
}
override suspend fun closeAsync() {
    rejectIfInsideOwnNotification("closeAsync()")
    val (mine, state) = beginClose()
    ...
}
```

因此“外部关闭已发起（STOPPING/CLOSED）”时，通知内调用同样立即具名拒绝，不会加入等待也不会死锁。

## 3. 测试与断言（新增 9 例）

| 测试 | 关键断言 |
| --- | --- |
| `NotificationCloseGateTest.sync close waits...` | 注入阻塞 notifier（finally 内 `NonCancellable` 等 gate）：gate 未放行时 sync `close()` 300ms 内不返回且 `finished=false`；放行后 close 返回、`finished=true`、状态 CLOSED、Job 数 0、reporter 收到取消 |
| `NotificationCloseGateTest.async close waits...` | 同上，入口为 `closeAsync()` |
| `NotificationCloseGateTest.closing the same context...` | 通知内 sync + async 关闭均 `CLOSED` 具名拒绝，且都能返回（不死锁） |
| `NotificationCloseGateTest.closing a different context...` | 关闭另一个 Context 不被拒绝，对方正常 CLOSED |
| `NotificationCloseGateTest.external close started...` | close 在途（未返回）时，通知内 `closeAsync()` 仍具名拒绝；gate 放行后 close 才返回，finally 真正退出 |
| `NotificationReentrancyContextTest.yield + dispatcher` | 通知内先 `withContext(IO){yield()}` 再 `withContext(Default){yield()}`，两次关闭仍被拒绝 |
| `NotificationReentrancyContextTest.structured child` | `coroutineScope { async(IO){...}; launch(Default){...} }` 子任务内关闭被拒绝 |
| `NotificationReentrancyContextTest.marker not polluted` | 通知内标记可见；通知退出后 `insideNotificationForTesting()=false`，普通 `close()` 正常 |
| `NotificationReentrancyContextTest.different contexts` | 两个独立 Context（各自库/挂载）互不误拒 |

## 4. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `:context:runtime:spotlessApply :context:runtime:compileKotlin` | 0 | F1 生产编译 |
| `:context:runtime:spotlessApply` | 0 | 格式化；`spotless-apply.log` |
| `:context:runtime:test :context:runtime:spotlessCheck --rerun-tasks` | 0 | **87 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 87 = XML 87，**差额 0**；`targeted-test.log` |

runtime 84 = 原 75 + `NotificationCloseGateTest` 5 + `NotificationReentrancyContextTest` 4。R1/R2/R3 既有断言（`IngestionNotificationRaceTest`、`IngestionRuntimeSdkTest`、`IngestionServiceRealDbTest` 等）全绿。

证据 `.local/validation/cm1/worker/rereview-f1/`：`targeted-test.log`、`spotless-apply.log`、`final-source-hashes.json`、`verification.json`。

## 5. 改动文件与最终 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/.../NotificationExecutionMarker.kt`（新增） | `28fd3d78f648303f7484080f8b0de49e0e8382262bd2b89340b40edd28855422` |
| `context/runtime/.../AlcyoneContextImpl.kt` | `3dc754a5444195dce90d56c6e3d897553a2fd0f570168df150d08585a413f1be` |
| `.../ingestion/NotificationCloseGateTest.kt`（新增） | `b06d531c9dd6292a7de7527f9d1068f194ffe0479f3aedc342ec11b2d2a38bf7` |
| `.../ingestion/NotificationDrainBarrierTest.kt`（新增） | `6ac5c4ce6e4e05bbd5cd6ca2b2b425b06573a3149b38ab37e95d25c3c5c2e7e2` |
| `.../ingestion/NotificationReentrancyContextTest.kt`（新增） | `deddc4af7081ba2021f813a587589dadfc1c25e21aa9ca5120fd79e505a2ed5b` |

## 6. 未做项与边界

- 未改 `context/api`、`context/core`、`context/persistence-sqldelight`、`context/server`；未合并 P1（Slot 所有权/重试设计）。
- `create`/`start` 仍零通知副作用（不建 scope、不建 Job、不起线程）；懒创建判据测试保持绿。
- 通知仍是尽力而为：不重试、不持久化；关闭等待的是「本次在途通知真正退出」，不承诺投递成功。
- 重入检查只针对**同一实例**；不同 Context 实例的通知互不影响（有测试）。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 初验与 Architect 独立复核。

## 7. F1-R1 修复记录（2026-10-29，关闭顺序）

| 阶段 | 说明 |
| --- | --- |
| 缺陷 | `shutdown()` 先 `cancelNotifications()`（可能长时间 `join`）再转 STOPPING → drain 期间仍 `RUNNING` 并接受 SDK ingest |
| 修复 | 先（同一锁内）转 STOPPING/CLOSED 并捕获资源引用，再 `cancelNotifications()`；已准入请求仍真实 drain，不抢关 |
| 探针 | `NotificationAdmissionProbeTest`：修复前 `DRAIN_STATE=RUNNING; INGEST_ACCEPTED=true; RESULT=DUPLICATE`；修复后 `DRAIN_STATE=STOPPING; INGEST_ACCEPTED=false; RESULT=null` |
| 判据 | draining 屏障代替计时猜测；STOPPING 期间重复键/新键均 `CLOSED`、库内无新行；单线程 dispatcher 验证 marker 还原；结构化子协程 finally 阻塞时 close 仍等待 |
| 复跑 | `:context:runtime:test :context:runtime:spotlessCheck --rerun-tasks` 退出 0；87 例，源码 `@Test` = XML，差额 0 |

证据 `.local/validation/cm1/worker/rereview-f1-r1/`。
