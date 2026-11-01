# CM1 全面复查 P1 Worker 交付（连接所有权与关闭轮次内有限重试）

**任务**：CM1 全面复查 P1（设计 §3/§4/§5/§6/§9）：Slot 三语义分离、有限重试、唯一共享终态
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-29

## 本次修改（2026-10-29，P1 连接所有权与有限重试）

- `ContextSlot` 三语义分离：`disposed` 只表示「停止接单」，`close()` 每次执行 `releaseAttempt()`（重试也走同一入口，不绕过 `disposed`）。
- `releaseAttempt()` 顺序固定：线程退出判定（保存中断事实）→ 物理 close（成功才清所有权 + dispose 驱动）→ 失败聚合 → 抛主失败；删除 `physicalConnection ?: return` 早退，无连接时仍完成线程判定、聚合与中断恢复；线程/连接失败分别跟踪。
- `ContextDatabaseExecutor.releaseAll()`：同一轮次最多 `MAX_RELEASE_ATTEMPTS = 2`（次数上限），仅重试失败 Slot，经 `slot.close()`；首次失败保留在 `IdentityHashMap`，后续 `addSuppressed` 且跳过同一实例；`released` 只在全部成功后置位；轮次结束仍失败 → 共享失败（主失败为首次原因）。
- 唯一共享终态保持：`beginClose` 后到者只 await 同一协议，不重开新轮次、不改写结果；连接引用保留供 `getReleaseFailure()` 诊断。
- 新增 `ReleaseOwnershipRetryTest` T1～T8（真实文件 SQLite + 只包 `close` 的 Driver 代理，物理释放断言均在兜底清理之前）。
- 未改 API/Core/Runtime/Server；未与 F1 合并；未 `git add/commit`。

## 1. Slot 三语义（`ContextSlot`）

```kotlin
override fun close() {
    disposed.compareAndSet(false, true)   // 仅「停止接单」，不再兼作「已释放」
    releaseAttempt()                      // 每次清理尝试的唯一入口（重试也走这里）
}

internal fun releaseAttempt() {
    var interrupted = false
    var threadFailure: Throwable? = null
    try {
        executor.shutdown()
        interrupted = awaitTerminationInterruptibly()
        injectedThreadTerminationFailure.get()?.let { throw it }   // 仅测试钩子
    } catch (failure: Throwable) { threadFailure = failure }
    finally { if (interrupted) Thread.currentThread().interrupt() }

    val physical = physicalConnection          // 可为 null：不得提前 return
    var connectionFailure: Throwable? = null
    if (physical != null) {
        connectionFailure = runCatching { …physical.close()… }.exceptionOrNull()
        if (connectionFailure == null) { physicalConnection = null; physicalDriver?.dispose() }
    }

    recordAttemptFailure(threadFailure, connectionFailure)   // 各自保留首次失败
    if (threadFailure != null && connectionFailure != null && threadFailure !== connectionFailure) {
        threadFailure.addSuppressed(connectionFailure)       // 两类失败都聚合，不静默丢弃
    }
    (threadFailure ?: connectionFailure)?.let { throw it }
}
```

- 中断事实在 `finally` 恢复，失败路径也不丢。
- 线程退出失败与物理释放失败**分别跟踪**，不以一方成功代替整体成功。
- 物理 close 成功后才清 `physicalConnection` 并 dispose 驱动；失败则保留引用（可诊断、可重试）。

## 2. 关闭轮次内有限重试（`ContextDatabaseExecutor.releaseAll`）

```kotlin
private fun releaseAll() {
    synchronized(protocolLock) { if (released) return }
    val slots = readSlots.asReversed() + listOf(writeSlot)
    val pending = slots.toMutableList()
    val firstFailures = IdentityHashMap<ContextSlot, Throwable>()

    for (attempt in 1..MAX_RELEASE_ATTEMPTS) {      // 2 次：次数上限，不是耗时上限
        val stillFailing = mutableListOf<ContextSlot>()
        pending.forEach { slot ->
            val failure = runCatching { slot.close() }.exceptionOrNull()   // 经停止接单入口
            if (failure == null) firstFailures.remove(slot)
            else {
                val first = firstFailures[slot]
                if (first == null) firstFailures[slot] = failure
                else if (first !== failure && first.suppressed.none { it === failure }) first.addSuppressed(failure)
                stillFailing += slot
            }
        }
        pending.clear(); pending += stillFailing
        if (pending.isEmpty()) break
    }

    synchronized(protocolLock) { if (pending.isEmpty()) released = true }

    if (pending.isNotEmpty()) {
        val primary = firstFailures.values.first()
        firstFailures.values.drop(1).forEach { other ->
            if (other !== primary && primary.suppressed.none { it === other }) primary.addSuppressed(other)
        }
        throw ContextPersistenceException(PERSISTENCE_UNAVAILABLE,
            "releasing context database resources failed for ${pending.size} slot(s)", primary)
    }
}
```

- 重试**经 `slot.close()`**，不直接调私有 helper、不绕过 `disposed`。
- 首次失败保留；后续失败 `addSuppressed` 且跳过同一实例（无自我 suppressed）。
- `released` 仅全部成功才置位；轮次结束仍失败 → 抛共享失败（后续 close/closeAsync 返回**同一实例**）。
- 失败语义 = 「关闭协议已终结、仍有资源未释放」，不宣称全部释放；连接引用保留供诊断。

## 3. 测试 T1～T8（新增 8 例，真实文件 SQLite）

| 用例 | 注入 | 断言（均在测试兜底清理之前） |
| --- | --- | --- |
| T1 首次失败、重试成功 | 驱动对 Slot 的 `Connection.close()` 首次抛 `SQLException` | `close()` 成功；`physicalConnections` 全部 `isClosed=true`；至少 2 次 close 尝试 |
| T2 持续失败 | 每次都抛 | 失败为 `ContextPersistenceException(PERSISTENCE_UNAVAILABLE)`；真实连接仍 `isClosed=false`（保留所有权）；后续 `close()`/`closeAsync()` 返回**同一失败实例** |
| T3 仅写 Slot 持续失败 | 只对 Slot 0 注入失败 + 注入线程退出失败 | 读 Slot 全部真实关闭；写 Slot 保留引用；失败 cause 含 suppressed（线程失败 + 连接失败） |
| T4 清理期间中断 | 持续失败 + 主线程先 `interrupt()` | 清理仍完成；中断标志恢复；结果与真实终态一致（失败而非伪成功） |
| T5 失败后不重开轮次 | 持续失败，第二次 `close()` | 返回同一失败实例；close 调用计数**不增加**（只 await 同一协议） |
| T6 中毒连接后全局重试 | commit 注入失败 → poison → `releasePoisonedConnection` 失败 → 全局 close | 全局 close 仍失败时报告失败并保留引用 |
| T7 无物理连接但线程失败 | 先成功释放（`physicalConnection==null`），再注入 `awaitTermination` 失败 | 失败不被吞（`IllegalStateException("thread cleanup failure")`）；证明删除早退生效 |
| T8 首次原因保留 | 同一 Slot 两轮都失败 | 主失败为首次（cause），第二次作为 suppressed；无自我 suppressed；该 Slot 恰好 2 次尝试 |

既有 `F2ResourceOwnershipAndCloseProtocolTest` A03 期望按新语义更新：失败 Slot 在同轮内各重试一次（`[1, 0, 1, 0]`），suppressed 计数相应调整。

## 4. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `:context:persistence-sqldelight:spotlessApply :context:persistence-sqldelight:compileKotlin` | 0 | 生产编译 |
| `:context:persistence-sqldelight:spotlessApply` | 0 | 格式化；`spotless-apply.log` |
| `:context:persistence-sqldelight:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks` | 0 | **163 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 163 = XML 163，**差额 0**；`targeted-test.log` |
| `:context:core:test :context:runtime:test :context:server:test --rerun-tasks` | 0 | 无回归 |

persistence 163 = 原 155 + `ReleaseOwnershipRetryTest` 8。

## 5. 改动文件与最终 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `.../sqldelight/ContextSlot.kt` | `849e881115c1ec268271cf2131bc5350e924a5440b216339eb54d2d8b7c7d1e2` |
| `.../sqldelight/ContextDatabaseExecutor.kt` | `1890d74df3e63a90cab372f89ae67e71b78e4f13ebdde059fb347cd4de078005` |
| `.../test/ReleaseOwnershipRetryTest.kt`（新增） | `d05317d6240729799d3b6021b15d8205920da8dbfad07bcd6c8069f2988be174` |
| `.../test/F2ResourceOwnershipAndCloseProtocolTest.kt` | `94a0eb9bf1a400e0097ff8723c4d72f9f79657c874f355d4e20b475255495304` |

## 6. 未做项与边界

- 未改 `context/api` / `context/core` / `context/runtime` / `context/server`；未与 F1 合并提交。
- 重试是**次数上限**（2 次），不是 JDBC close 耗时上限；不做后台/定时/跨实例重试。
- 持续失败只表示「关闭协议已终结但仍有资源未释放」，不宣称全部释放；后续 close 返回同一失败实例。
- 新增测试钩子仅 `internal`：`injectThreadTerminationFailureForTesting`、`physicalConnectionForTesting`、`slotForTesting`、`firstThreadFailure/firstConnectionFailure` 诊断读取。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 初验与 Architect 独立复核。
