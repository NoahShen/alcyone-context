# T34 进程执行/清理路径表

## 本次修改（2026-10-16，Architect 收口证据口径订正）

- 直接订正清理保证与历史计数残留，不改生产代码和测试；最终结论以《T34 架构与技术复核》第10节为准。
- 有界等待指进程等待、读取线程 join 与清理等待。流 close 为同步 JDK 调用，后续 join 截止时间并不自动约束 close 本身；本机 JDK21/macOS 已验证持管道、超时与中断代表，不外推任意平台/异常内核的硬实时保证。
- cleanupFailed 仅表示本次可追踪进程或读取任务未确认退出；不能检测任意已脱离且不可追踪的子进程，也不能承诺这类进程必然触发 poisoned。


## 本次修改（2026-10-16，第三轮复核 R3/R5 定向收口）

- 路径表与实现及真实方法名逐项对齐；补充“输出不完整 vs 资源未确认”的区分、关闭流的有界确认、父进程退出后 descendants 不可见的边界。

本文档描述 `JvmGitProcessRunner.run` 的进程/线程资源所有权与各路径的结果语义。
所有方法名均为 `vfs/git-backup/src/main/kotlin/.../JvmGitProcessRunner.kt` 中的真实名称。

## 资源归属总览

| 资源 | 创建方 | 回收责任 |
| --- | --- | --- |
| `Process`（git 进程） | `run` | `cleanUpProcess(process, state)`（超时/取消/读取未确认时）；正常退出依赖进程自身结束 |
| `descendants`（子进程） | git 进程派生 | `cleanUpProcess` 内 `parent.descendants().toList()` 后级联 destroy/destroyForcibly，并对每个句柄 `waitForExit` |
| stdout 读取线程 | `run` | `joinThread(thread, deadlineNanos, state)`；关闭流后仍无法确认 → `cleanupFailed = true` |
| stderr 读取线程 | `run` | 同上 |
| `process.inputStream` / `errorStream` / `outputStream` | JDK | `run` 的 `finally` 同步关闭；随后有界确认读取线程（不等价于 close 本身有超时参数） |
| 线程中断位 | 调用方 | `run` 在全部清理与确认结束后，于 `finally` 统一恢复（`if (state.interrupted) Thread.currentThread().interrupt()`） |

## 路径 1：正常路径

- 触发：进程在截止时间内退出；双流在截止时间内读到 EOF；无需关闭流收尾。
- 资源归属：进程自然退出；两个读取线程读到 EOF 后自行结束；主线程 `finally` 关闭流。
- 返回结果：`exitCode` 为真实退出码，`timedOut=false`，`cancelled=false`，`cleanupFailed=false`，`outputTruncated=false`。
- 直接测试断言：
  - `successful command reports complete output`：`printf hello` → `stdout=="hello"`、四个标志全 false。
  - `reader threads exit after successful command`：命令结束后 `git-stdout-drain`/`git-stderr-drain` 不再存活。

## 路径 2：输出截断 / 输出等待未完成路径

- 触发：保留字符数超过 `maxOutputChars`（`StreamCollector.append` 置 `truncated`）；读取发生 IOException（`StreamCollector.markIoFailed`）；或进程在截止时间内未读到 EOF，需要靠关闭流收尾（`requiredClose=true`）。
- 资源归属：读取线程由 `joinThread` 在有限预算内确认；关闭流在 `finally` 中进行，并再次用 `joinThread` 有界确认。
- 返回结果：
  - 输出不完整：`outputTruncated=true`（与资源是否确认解耦）。
  - 资源已确认：`cleanupFailed=false`；关闭流后仍无法确认读取线程退出：`cleanupFailed=true`。
- 直接测试断言：
  - `truncated output is flagged`：限额 8、输出 20 → `outputTruncated=true`。
  - `large stdout and stderr are both drained`：stdout 与 stderr 各写入 20 万行 → 不挂起、`outputTruncated=true`。
  - `parent exit with held pipe marks output incomplete`：父 shell 退出、后台进程持有管道 → `outputTruncated=true`；`cleanupFailed` 与读取线程是否确认一致（断言 `assertEquals(readerThreadsAlive(), result.cleanupFailed)`）。
  - GitRepository `execute`：`outputTruncated` → `Failed(..., OUTPUT_TRUNCATED)`。

## 路径 3：超时路径

- 触发：`process.waitFor(remaining, NANOSECONDS)` 在截止时间内未返回 → `timedOut=true`。
- 资源归属：`cleanUpProcess(process, state)` 先枚举 descendants，再对父/子分别 `waitForExit(handle, millis, state)`，未退出则 destroyForcibly 后再有界等待。
- 返回结果：`timedOut=true`；`cleanupFailed` 取决于父/子是否确认退出。
- 直接测试断言：
  - `timeout kills process and descendants with verified pids`：shell 写出父/子 PID，超时后两个 PID 均不存活；`cleanupFailed=false`。

## 路径 4：中断/取消路径

- 触发：`process.waitFor`、`joinThread` 或 `cleanUpProcess` 内的 `waitForExit` 抛 `InterruptedException`，或 runner 抛 `CancellationException`。
- 资源归属：全部中断经 `markInterrupted(state)` 统一记录；清理与读取确认完成后，在 `finally` 恢复中断位。
- 返回结果：`cancelled=true`，`timedOut=false`；中断后改用有限确认预算（`CONFIRM_TIMEOUT_MS`），不再等待到原截止时间。
- 直接测试断言：
  - `interrupt cancels running command and keeps cancellation semantics`：中断 `sleep 30` → `cancelled=true`、`cleanupFailed=false`、`worker.isInterrupted==true`。
  - `interrupt during output wait after parent exit is not lost`：父进程先退出、读线程仍等待时中断 → `cancelled=true`、耗时远小于原截止时间、中断位恢复。
  - GitRepository `cancellation exception during stage aborts with cancelled semantics`：注入 `CancellationException` → `Failed(STAGE, STAGE_ADD_CANCELLED, cancelled=true)`。

## 路径 5：清理无法确认路径

- 触发：descendants 枚举失败；`aliveOrUncertain(handle)` 查询失败；进程或 descendants 强制销毁并有界等待后仍存活；关闭流后读取线程仍无法在有限预算内确认退出。
- 资源归属：主线程尽最大努力销毁；无法确认即标记失败。
- 边界：父进程已退出后 `parent.descendants()` 可能为空，这只是“无当前可追踪子进程”，不代表此前派生的不可追踪进程已退出；此时以读取线程是否确认为准。
- 返回结果：`cleanupFailed=true`；GitRepository 在 `execute` 中标记 `poisoned`，本轮与后续 `syncOnce` 直接返回 `Failed(PROCESS_CLEANUP_FAILED)`。
- 直接测试断言：
  - `cleanup failure poisons repository and blocks subsequent syncs`：注入 `cleanupFailed` 的 runner → 首次失败、后续不再发起命令（调用计数不变）。
