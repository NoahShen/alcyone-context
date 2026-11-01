# F1-R1（通知快照竞态）Lead 独立初验

## 本次修改（2026-10-29，Lead 实跑发现并退回修复，提交 Architect 复核）

- **来源**：Lead 在 P2/P3 独立实跑 `:context:server:test` 时出现 1 例偶发失败（单跑 5 次不复现 → 竞态），**并非 Worker 自测发现**。属 F1 已批准代码中的真实缺陷。
- F1-R1 由同一 Paseo worker 配置 Worker `d7695b22` 独立批次修复（未与 P2/P3 合并）；Lead 未写生产代码。
- 范围：`context/runtime` 与测试；未改 API/Core/Persistence/Server；无 git 写操作。

## 1. 缺陷与根因

```text
java.util.NoSuchElementException
  at java.util.concurrent.ConcurrentHashMap$KeyIterator.next(ConcurrentHashMap.java:3460)
  at kotlin.collections.CollectionsKt___CollectionsKt.toList(_Collections.kt:1329)
  at ...AlcyoneContextImpl.cancelNotifications(AlcyoneContextImpl.kt:585)
  at ...AlcyoneContextImpl.shutdown(AlcyoneContextImpl.kt:397)
  at IngestionHttpApiTest.closing while a request is accepted does not deadlock
```

`pending = notificationJobs.toList()`：Kotlin `Iterable.toList()` 对 `Collection` 先读 `size`，`size == 1` 时走 `iterator().next()`；与 `job.invokeOnCompletion { notificationJobs.remove(job) }`（:566）并发时，唯一元素已被移除 → 迭代器空 → `NoSuchElementException`。**这是生产代码竞态，不是测试不稳定**。

## 2. 交付物（SHA 已核对）

| 文件 | SHA-256（前缀） |
| --- | --- |
| `runtime/AlcyoneContextImpl.kt` | `a88ba5614aa0ed3a` |
| `test/ingestion/NotificationSnapshotRaceTest.kt`（新增） | `9f010a0f05dd50db` |

## 3. 实现审查

```kotlin
private fun snapshotNotificationJobs(): List<Job> {
    val snapshot = ArrayList<Job>(notificationJobs.size)
    for (job in notificationJobs) { snapshot += job }   // 弱一致迭代器，并发移除不抛异常
    return snapshot
}
```

- 不再依赖 `size == 1` 分支；`ConcurrentHashMap` 迭代器弱一致，并发移除只导致该元素缺席，**不抛 `NoSuchElementException`**。
- 关闭语义未变：锁内 `notificationsClosed = true` + 快照 scope/Job → 锁外 `scope?.cancel()` → 未完成 Job 上报固定脱敏取消 → 逐个 `join()`；仍不以 `clear()`/计数 0 作退出判据。
- 其他并发集合核查：`ContextPathIsolation.kt:124` 为局部 `ArrayDeque`（单线程）、`AlcyoneContextFactory.kt:108` 为普通 `List` 快照，**均无需修改**（Lead 复核认可）。
- 新增 `internal var beforeNotificationSnapshotForTesting`（测试钩子，非公开 API），仅用于确定性构造「快照期间并发移除」交错。

## 4. Lead 独立实跑

| 项 | 结果 |
| --- | --- |
| `runtime:test` | **89 例**（87+2），0 失败/错误/跳过，源码=XML=89 |
| `server:test` | 30 例，0 失败 |
| `persistence:test` | 176 例，0 失败 |
| `core:test` | 11 例，0 失败 |
| 三个模块 `spotlessCheck` | exit 0 |

**竞态重复验证**：`server:test + runtime:test --rerun-tasks` **连续 5 次全部 exit 0，`NoSuchElementException` 出现 0 次**（日志 `repeat-1..5.log`）。

## 5. 回归测试核对（2 例，真实 `invokeOnCompletion` 移除，无反射）

| 用例 | 关键断言 |
| --- | --- |
| `snapshot tolerates notifications removing themselves while close is draining` | 32 个通知真实完成并移除，`beforeNotificationSnapshotForTesting` 放行后等 `notificationJobCountForTesting() < 32` 再取快照；close 有界完成、无 `NoSuchElementException`、Job 归零、状态 CLOSED |
| `repeated close and notification completion does not throw NoSuchElementException` | 20 轮「先放行/后放行」交错，每轮有界退出且无失败 |

## 6. 结论与边界

- **F1-R1 初验通过**：竞态根因消除、关闭语义保持、有确定性交错回归、重复 5 次不复现。F1 原 APPROVED 结论在本修复后继续成立（本修复为 F1 的补强，不改变其已验收语义）。
- 提交 **Architect 独立复核**（与 P2 订正分开审）；通过后进入 **F4 + 整体回归**，全部闭环后启动 CT08。**CM2 实现仍未启动**；未 git 提交/发布。
