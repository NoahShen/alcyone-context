# T35 交付与自测记录

## 本次修改（2026-10-16，第三轮独立复核 R3/R4 定向收口修复）

- **R3 已到期排队事件不再遗留无唤醒 pending 状态**：`schedulerLoop` 增加内层 `while (!closing)` 循环，每次信号唤醒后重新核对全部待执行状态（`nextDueReason` 重复调用），直到清空所有 pending 项；新增受控时间 + 闸门探针测试 `pending event is woken and cleared after immediate precedence`，验证 MANUAL 消费后 pendingEvent 不再悬空，最终清空。
- **R4 取消终止调度同时停止接纳和计时**：新增 `terminateScheduling()` 统一入口，取消/取消异常时设置 `closing=true`、取消 debounce/cron、清除 pending 状态并发送唤醒信号；`requestSync()` 增加 `worker.terminated` 检查，调度终止后抛 `IllegalStateException` 拒绝新请求；后续 `close()` 仍可靠完成且幂等。新增 `cancelled result stops timers and rejects requests` 与 `cancellation exception terminates scheduling and records duration` 两个测试，覆盖取消结果与取消异常两条路径；普通失败仍保持可再次触发。
- 保留 R1/R2 已关闭成果不重做；既有 VFS 零改动。

- **R1 订阅回调不再持有插件/Worker**：`eventConsumer` 改为 `consumerHolder::accept`，只捕获轻量 `EventConsumerHolder`；`onRelevantEvent` 用局部变量直接捕获 `GitBackupWorker`，不再通过 `this.worker` 字段。`javap` 确认 lambda 描述符为 `(GitBackupWorker) -> Function0`，不再接收插件。新增引用结构测试（遍历字段引用图，断言回调不可达插件，关闭后不可达 Worker）。
- **R2 close 取消与并发**：`close()` 用 `CompletableDeferred` 共享清理完成信号；`state` 置 CLOSED 只表示“停止接纳”，并发 close 等待同一次清理；清理在 `withContext(NonCancellable)` 中执行，调用方取消不跳过 `scope.cancel/join`，`finally` 完成共享信号。新增“调用方取消 close 后清理仍完成”“并发 close 不提前返回”测试。
- **R3 已到期排队事件被新事件重置**：Worker 用 `pendingEvent + eventGeneration` 表示事件待执行请求；新事件使旧请求失效，领取前核对代数；MANUAL/CRON/STARTUP 立即请求不受影响并取代未到期事件计时。新增“t=60 排队、t=60 新事件重置到 t=120 才执行”“立即请求取代到期事件”测试。
- **R4 唯一运行日志与取消语义**：`GitRepository` 不再写运行日志，`GitBackupWorker` 为唯一责任层（每轮一条开始 + 一条终结）；`RepositorySyncExecutor` 取消时抛 `SyncCancelledException(已得 SyncResult)`，Worker 记录取消阶段与耗时后停止循环；普通执行器异常记受控终结并继续等待下次触发。新增真实 `RepositorySyncExecutor`/`GitRepository` 接线日志与取消测试。
- **计数**：模块源码 `@Test` = 73，模块 XML = 73；全工程常规源码 = 825，常规 XML = 825（既有 752 + git-backup 73）。

### 本轮撤回

- 撤回此前 A01～A08 “全部达成”的表述；本轮仅就 R1～R4 修复给出证据，最终达成由 Architect 独立复核判定。

## 1. 目标与范围

实现 `GitBackupPlugin` 独立公开入口与内部 `GitBackupWorker` / `GitBackupEventConsumer`，把 T34 的受控单次同步接上事件防抖、Cron、启动与手动触发。不修改 Runtime 或既有 6 个模块，不引入公开状态模型，不启动 T36。

## 2. 交付文件

生产代码（`vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/`）：

- `GitBackupPlugin.kt`：公开入口，`eventConsumer` / `start` / `requestSync` / `close`；构造只建轻量对象。
- `GitBackupWorker.kt`：每仓库一个的串行 Worker，防抖、Cron、请求合并、日志。
- `GitBackupEventConsumer.kt`：轻量事件桥接，只筛选与转发。
- `SyncExecutor.kt`：执行边界 + 生产 `RepositorySyncExecutor`（`runInterruptible(Dispatchers.IO)`）。
- `GitBackupRuntime.kt`：内部 Scope / Clock / Executor / Logger 集合，生产与测试注入点。
- `TriggerReason.kt`：`EVENT / CRON / STARTUP / MANUAL`。

测试代码（`vfs/git-backup/src/test/kotlin/...`）：

- `GitBackupPluginTest.kt`：A01～A07 的受控时间与受控交错测试（21 例，含 R3/R4 新增受控闸门测试）。
- `GitBackupWorkerTest.kt`：Worker 未启动/关闭后拒绝工作、普通异常继续调度、取消结果停止循环（4 例，含 PendingProbe 受控交错与 cancelled 结果终止）。
- `GitBackupLogCaptureTest.kt`：生产链（`RepositorySyncExecutor` → `GitRepository`）唯一日志与真实取消接线（2 例）。
- `GitBackupTestSupport.kt`：内存 Logger、虚拟/偏移 Clock、事件构造与引用结构遍历。

构建接线（模块测试依赖，必要变更）：

- `gradle/libs.versions.toml`：新增 `coroutines-test` 库坐标（复用 `coroutines` 版本）。
- `vfs/git-backup/build.gradle.kts`：新增 `testImplementation(libs.coroutines.test)`。

文档：`docs/tasks/e1-t35/DELIVERY_T35.md`（本文档）。

## 3. 设计与边界

- 构造只校验参数并建立轻量对象；`start()` 幂等，异步启动一次 STARTUP 检查；`requestSync()` 返回 Unit 且未启动/已关闭抛 `IllegalStateException`；`close()` 幂等，断开 Consumer、拒新请求、停计时、宽限期后在途取消，不追加 push。
- 内存只保留变更代数、待立即执行标记与触发原因，不保存逐条事件；Git I/O 在临界区之外。
- 防抖用协程 `delay`（单调推进）并在临界区核对代数，旧计时器不会越过重置提前执行；Cron 用本机 `Clock`，醒来只补一次、不积压。
- 单仓库最多一个流程并发；运行中新通知保留倒计时，不被结束逻辑抹掉。
- 不提供 `status()` / `GitBackupStatus` / 请求结果枚举。

## 4. 验证命令与结果

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| `./scripts/dev gradle spotlessApply` | 0 | BUILD SUCCESSFUL |
| `./scripts/dev gradle :vfs:git-backup:cleanTest :vfs:git-backup:test` | 0 | BUILD SUCCESSFUL |
| `./scripts/check` | 0 | BUILD SUCCESSFUL in 1m 11s, 75 actionable tasks |

### 4.1 计数（归档实际统计命令）

```bash
# 常规 XML 用例总数（仅 src/test 常规测试，不含 WebDAV）
find . -path '*/build/test-results/test/*.xml' -print0 \
  | xargs -0 grep -ho 'tests="[0-9]*"' | grep -o '[0-9]*' | paste -sd+ - | bc

# 常规源码 @Test 行数（排除 webdavTest；@Test\b 排除 @TestMethodOrder）
find . -path '*/src/test/*' -name '*.kt' -not -path '*/webdavTest/*' -print0 \
  | xargs -0 grep -hoE '@Test\b' | wc -l
```

- 常规源码 `@Test` 行数：**825**
- 常规 XML 用例总数：**825**（差额为零）
- 既有模块：**752**；git-backup：**73**（T34 收口 47 + T35 新增 26）
- WebDAV 源码 `@Test`：43（单独 `webdavTest`，不计入常规）
- git-backup 模块 XML：`GitBackupPluginTest(21)`、`GitBackupWorkerTest(4)`、`GitBackupLogCaptureTest(2)`、`GitBackupTest(12)`、`GitRepositoryTest(23)`、`GitSecurityTest(3)`、`JvmGitProcessRunnerTest(8)`。

## 5. A01～A08 测试映射

| 编号 | 用例 | 断言要点 |
| --- | --- | --- |
| A01 | `requestSync before start throws` / `start is idempotent and runs a single startup check` / `requestSync and start after close are rejected and close is idempotent` | 生命周期规则清晰；重复 start 仅一次 STARTUP；无公开状态模型 |
| A02 | `debounce waits full quiet period and resets on new events` | 0s/30s 通知：89s 不执行、90s 执行；持续通知重置 |
| A02 | `a due but not yet run debounce timer does not fire after a reset` | 到点未运行的旧计时器在重置后不提前执行 |
| A02 | `unrelated and metadata events do not reset the debounce` | 范围外与 METADATA 事件不重置 |
| A03 | `single run at a time and notifications during a run are preserved` | 最大并发 1；运行中手动+事件合并并保留后续轮 |
| A03/R3 | `a queued due event request is invalidated by a newer event` | t=60 排队事件被 t=60 新事件失效，t=120 才执行 |
| A03/R3 | `immediate request supersedes a due event request` | 立即请求取代到期事件，不产生重复 EVENT 轮 |
| A03/R3 | `pending event is woken and cleared after immediate precedence` | STARTUP 阻塞期间 MANUAL + 事件，静默期到期后事件被唤醒执行，pending 最终清空 |
| A06/R4 | `cancelled result stops timers and rejects requests` | 取消结果后 requestSync 抛 IllegalStateException，受控时间推进无新增工作/残留，幂等 close 后自身退出且宿主活跃 |
| A06/R4 | `cancellation exception terminates scheduling and records duration` | 取消异常记录含 durationMs 的终结日志后停止循环，普通失败保持可再次触发，宿主 Scope 不受影响 |
| A04 | `startup cron and manual run without events` | 无事件也执行 STARTUP / CRON / MANUAL |
| A04 | `cron is not delayed by the debounce quiet period` | Cron 30s 到点早于 60s 静默期即执行 |
| A04 | `missed cron times do not accumulate` | 阻塞跨过 5 个 Cron 时刻只合并一次 |
| A05 | `event consumer captured before close cannot restart work` / `worker rejects work before start and after shutdown` | 关闭后旧引用与未启动均不触发；回调无 IO |
| A05/R1 | `event consumer does not retain the plugin and releases the worker on close` | 回调引用结构不可达插件；关闭后不可达 Worker |
| A06 | `close cancels in-flight run without cancelling host scope` | 在途取消、插件 Scope 取消、宿主 Scope 仍活跃、close 不追加 push |
| A06/R2 | `close completes cleanup even when the caller is cancelled` / `concurrent close waits for the same cleanup` | 取消不跳过清理；并发 close 等待同一次完成 |
| A07 | `each run logs start and result with runId and trigger` / `skipped failed and cancelled runs log their outcome` | 每轮开始+结束、runId、触发原因、成功/跳过/失败/取消格式 |
| A07/R4 | `production chain logs exactly one start and one terminal without credentials` | 真实 RepositorySyncExecutor 链每轮唯一开始/终结，无凭据泄露 |
| A06/R4 | `real repository executor cancellation stops the loop and logs stage and duration` | 真实 runInterruptible 取消链记录阶段与耗时 |
| A07/R4 | `generic executor exception logs a controlled failure and keeps scheduling` / `cancelled result logs stage and duration and stops the loop` | 普通异常受控记录并继续；取消结果记录阶段/耗时并停止 |
| A08 | 本文件 §4 | 格式、模块测试、`./scripts/check` 通过 |

## 6. 局限

- 测试使用注入的 `SyncExecutor` 与受控 `Clock`/调度器，不真实等待 60 秒或凌晨；真实 Git 流程由 T34 的 `GitRepositoryTest` 覆盖。
- 本 Worker 只保证对自身 Scope/任务/在途进程的清理；已脱离且不可追踪的子进程由 T34 的 `cleanupFailed → poisoned` 阻断。
- `close()` 不强制补 push；失败后由下一次正常触发处理。
