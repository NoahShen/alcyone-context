# CM1 F1-R1 Worker 交付（通知快照并发移除竞态）

**任务**：CM1 全面复查 F1-R1：`cancelNotifications` 用 `toList()` 取快照时与 `invokeOnCompletion` 并发移除元素冲突
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-29

## 本次修改（2026-10-29，F1-R1 快照竞态）

- 缺陷确认：`pending = notificationJobs.toList()` 对 `Collection` 先读 `size`，`size == 1` 时走
  `iterator().next()`；`invokeOnCompletion { notificationJobs.remove(job) }` 在其它线程并发移除唯一元素，
  迭代器已空 → `NoSuchElementException`（Lead 在 `:context:server:test` 偶发复现）。
- 修复：新增 `snapshotNotificationJobs()`，**显式迭代** `ConcurrentHashMap.newKeySet()` 构造快照
  （`ConcurrentHashMap` 迭代器弱一致，移除的元素自然缺席，不会抛 `NoSuchElementException`）。
- 语义保持不变：锁内先置 `notificationsClosed` 并快照 scope/Job → 锁外 `scope?.cancel()` →
  对未完成 Job 上报固定脱敏取消 → 逐个 `join()`；不以 `clear()`/计数 0 作退出判据。
- 其他并发集合用法核查：`ContextPathIsolation.kt:124` 的 `remaining.toList()` 是局部 `ArrayDeque`（单线程）；
  `AlcyoneContextFactory.kt:108` 的 `vfsConfig.mounts.toList()` 是普通 `List` 快照。**均无需修改**。
- 新增 `NotificationSnapshotRaceTest` 2 例；`:context:server:test` 与 `:context:runtime:test` 各重复 5 次全绿。
- 未改 API/Core/Persistence/Server；未与其他修复合并；未 `git add/commit`。

## 1. 修复实现

```kotlin
private suspend fun cancelNotifications() {
    val scope: CoroutineScope?
    val pending: List<Job>
    synchronized(notificationLock) {
        if (notificationsClosed) return
        notificationsClosed = true
        scope = notificationScope
        notificationScope = null
        pending = snapshotNotificationJobs()   // ← 不再 toList()
    }
    scope?.cancel()
    pending.filter { !it.isCompleted }.forEach { reportNotificationCancellation() }
    pending.forEach { it.join() }
}

/**
 * 显式迭代 ConcurrentHashMap.newKeySet()：迭代器弱一致，移除的元素自然缺席，
 * 不会像 Iterable.toList()（size==1 时用 iterator().next()）那样因并发移除抛
 * NoSuchElementException。
 */
private fun snapshotNotificationJobs(): List<Job> {
    val snapshot = ArrayList<Job>(notificationJobs.size)
    for (job in notificationJobs) snapshot += job
    return snapshot
}
```

## 2. 回归测试（新增 2 例）

| 用例 | 交错方式 | 断言 |
| --- | --- | --- |
| `snapshot tolerates notifications removing themselves while close is draining` | 32 个通知正常完成（gate 放行），测试钩子在 close 取快照前放行 gate 并等到至少一个 Job 已被 `invokeOnCompletion` 移除，随后 close 的显式迭代仍在与其余移除并发 | close 有界完成；无 `NoSuchElementException`/任何异常；Job 数归零；状态 CLOSED |
| `repeated close and notification completion does not throw NoSuchElementException` | 20 轮，奇偶轮分别「先放行再 close / close 开始后放行」，让完成移除与快照交错 | 每轮 close 有界退出、无失败；最终 Job 数归零 |

使用真实 `notificationJobs` 语义（Job 完成回调移除），没有用反射绕过路径。

## 3. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `:context:runtime:spotlessApply :context:runtime:compileKotlin` | 0 | 生产编译 |
| `:context:runtime:spotlessApply` | 0 | 格式化；`spotless-apply.log` |
| `:context:runtime:test :context:runtime:spotlessCheck :context:persistence-sqldelight:test :context:core:test :context:server:test --rerun-tasks` | 0 | **runtime 89 / server 30 / persistence 176 / core 11，0 失败/错误/跳过**；源码 `@Test` 与 XML 全一致（差额 0） |
| `:context:server:test :context:runtime:test --rerun-tasks` × 5 | 0 × 5 | 5 次全部 `BUILD SUCCESSFUL`（见 `repeat-runs.log`） |

runtime 89 = 87 + `NotificationSnapshotRaceTest` 2。

## 4. 改动文件与 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/.../AlcyoneContextImpl.kt` | `a88ba5614aa0ed3a577f5a716c86fd024cf181ab69fbb0ef1b7e95429b4d96e0` |
| `.../ingestion/NotificationSnapshotRaceTest.kt`（新增） | `9f010a0f05dd50db7b2d1c34aff4af36d8d5e07df83e80bd9ede3be9a44b06fa` |

## 5. 未做项与边界

- 只修 runtime 快照竞态；未改 API/Core/Persistence/Server；未与其他修复合并；未 `git add/commit`。
- 通知仍是尽力而为、不重试、不持久化；关闭等待的是「本次在途通知真正退出」。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 初验与 Architect 独立复核。
