# P1（连接所有权与关闭轮次内有限重试）Lead 独立初验

## 本次修改（2026-10-29，全面复查 P1 初验，提交 Architect 复核）

- F1 已 APPROVED。P1 由同一 Paseo worker 配置 Worker `d7695b22` 按已批准设计（`P1_连接所有权与有限重试设计.md` §3/§4/§5/§9）实施，**未与其他修复合并**；Lead 未写生产代码。
- 范围：仅 `context/persistence-sqldelight` 生产与测试；未改 API/Core/Runtime/Server；无 git 写操作。

## 1. 交付物（SHA 已核对）

| 文件 | SHA-256（前缀） |
| --- | --- |
| `persistence-sqldelight/ContextSlot.kt` | `849e881115c1ec26` |
| `persistence-sqldelight/ContextDatabaseExecutor.kt` | `1890d74df3e63a90` |

## 2. Lead 独立实跑（非引用 Worker 日志）

| 模块 | 结果 |
| --- | --- |
| `context:persistence-sqldelight:test` | **163 例**，0 失败/错误/跳过，源码=XML=163（原 155 + `ReleaseOwnershipRetryTest` 8） |
| `context:core:test` | 11 例，0 失败（无回归） |
| `context:runtime:test` | 87 例，0 失败（无回归，F1 结果保持） |
| `context:server:test` | 19 例，0 失败（无回归） |
| `persistence spotlessCheck --rerun-tasks` | exit 0 |

日志 `.local/validation/cm1/lead/rereview-p1/check.log`。

## 3. 实现审查（逐条对照 §3/§4/§5/§9）

| 要求 | 实现核对 |
| --- | --- |
| 三语义分离；`disposed` 仅停止接单 | `ContextSlot.close()`：`disposed.compareAndSet(false,true)` 后**每次**执行 `releaseAttempt()`；重试经同一入口（`slot.close()`），不绕过 disposed、不直调私有 helper |
| 删除 `physicalConnection ?: return` 早退 | `releaseAttempt()`（`:432-475`）无早退：先线程退出判定（保存 `interrupted`）→ 物理 close → 聚合 → 抛主失败；`physical == null` 时仍执行线程判定与聚合 |
| 中断事实 `finally` 恢复 | `finally { if (interrupted) Thread.currentThread().interrupt() }` |
| 线程/连接失败分别跟踪 | `threadFailure`/`connectionFailure` 独立；两类同发时线程失败为主、连接失败 `addSuppressed`（不静默） |
| 有限重试（≤2 次，仅失败 Slot） | `releaseAll()`：`MAX_RELEASE_ATTEMPTS` 循环，`pending` 只保留失败者；每 Slot 经 `slot.close()` |
| `released` 仅全部成功后置位 | 循环后 `synchronized(protocolLock){ if (pending.isEmpty()) released = true }` |
| 首次失败保留、无自我 suppressed | `IdentityHashMap<ContextSlot, Throwable>` 保留首次；后续失败 `addSuppressed` 前检查 `first !== failure && suppressed.none { it === failure }` |
| 唯一共享终态、不重开轮次 | `beginClose` 后到者只 await 同一协议；轮次失败 → 共享失败实例；后续 `close()`/`closeAsync()` 返回同一实例（T2 断言 `assertSame`） |
| 不宣称全部释放、保留诊断 | 失败仅表示协议终结；`physicalConnection` 保留；`getFirstThreadFailure()`/`getFirstConnectionFailure()`/`getReleaseFailure()` 为 internal 诊断 |
| 无后台/定时/跨实例重试 | 未引入任何重试调度；仅轮次内 2 次 |

## 4. 测试核对（T1～T8，真实 SQLite + 仅包 `close` 的 Driver 代理，断言在兜底之前）

| 用例 | 关键断言（已核实） |
| --- | --- |
| T1 | 首失败→重试成功；连接全部 `isClosed=true`、所有权清空、共享成功（≥2 次尝试） |
| T2 | 持续失败 → `PERSISTENCE_UNAVAILABLE`；真实连接仍 `isClosed=false`（保留所有权）；`assertSame` 后续 `close()` 与 `closeAsync()` 返回**同一失败实例** |
| T3 | 仅写 Slot 持续失败 + 线程失败 → 读 Slot 真实关闭、写 Slot 保留、`cause` 含 suppressed |
| T4 | 清理期间 `interrupt()` → 清理完成、中断标志恢复、失败不被误判为成功 |
| T5 | 失败后再 close → 同一实例、close 调用计数不增加（不重开轮次） |
| T6 | 中毒连接 + 全局重试 → 持续失败保留引用并报告失败 |
| T7 | 先成功释放物理连接（`physicalConnectionForTesting()==null`）再注入线程终止失败 → `IllegalStateException("thread cleanup failure")` **不被吞**，证明删除早退生效 |
| T8 | 两轮失败且异常不同 → 主失败为**首次**、第二次 `suppressed`、无自我 suppressed、恰好 2 次尝试 |

既有 `F2ResourceOwnershipAndCloseProtocolTest` A03 已按新语义更新（失败 Slot 同轮各重试一次 `[1,0,1,0]`，suppressed=2），Lead 复跑全绿。

## 5. 结论与边界

- **P1 初验通过**：连接所有权不再在物理 close 前丢失；同一轮次内有限重试（≤2）；首次失败保留且无自我 suppressed；唯一共享终态不被改写；无物理连接时线程失败不被吞；四个受影响模块无回归。
- 提交 **Architect 独立复核**；通过后按既定顺序 **P4 → F2 → F3 → P2/P3 → F4**；全部开放项闭环并整体回归后直接启动 CT08。**CM2 实现仍未启动**；未 git 提交/发布。
