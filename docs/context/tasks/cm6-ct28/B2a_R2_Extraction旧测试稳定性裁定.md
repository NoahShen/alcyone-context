# CT28 B2a-R2 Extraction 旧测试稳定性裁定

## 本次修改（2026-11-11，更正 Scheduler 稳定态名称）

- 新增条件首次停在状态名：`ExtractionScheduler` 枚举只有 NEW/RUNNING/CLOSING/CLOSED，健康运行稳定态为 RUNNING，不是 Worker 的 IDLE。仅将第 3 条更正为 notifications drained + queueDepth 0 + state RUNNING，其余执行快照 1..5、同 Session 和 tick 无副作用不变。
- 不新增生产 IDLE、不放宽次数/归属边界；Worker 未错误放宽断言。B2a 未 DONE，B2b/c 不放行，无 Git 写操作。

## 历史修改（2026-11-11，仅修瞬时次数假设，不改生产）

- Lead 诊断证明第 4 次通知已执行 Runner、第 5 次通知再次合法执行；旧条件 `executions.size == 1` 会在快速两次执行后永久失败。该用例契约是 idle 禁用时 Count/Scheduler 工作，不是“五次异步通知恰好一次 Runner”。
- 仅允许修改既有 `ExtractionPipelineLifecycleTest.kt` 的 `disabling idle keeps the count and scheduler working without a cron` 方法；不得修改 CountTrigger、Scheduler、Pipeline、Factory、Impl 或其他测试。
- 用通知 drain、Scheduler 稳定、执行归属/上界及 idle tick 不再增加组成正式断言，不用无界 `>=1` 掩盖异常重复调度。B2a 未 DONE，B2b/c 不放行。

## 精确断言口径

保留原 idle tick 在 ingest 前不执行的断言和 `idleCron == null`。五次 ingest 后：

1. `awaitUntil { runner.executions.isNotEmpty() }`：至少一次 Count 执行。
2. `awaitNotificationsDrained(impl)`：五次通知全部完成，不再通过瞬时窗口猜测。
3. 在通知已 drain 后等待真实 Scheduler `queueDepthForTesting()==0` 且 `stateForTesting()=="RUNNING"`，确保所有已接纳调度完成且 Scheduler 仍健康运行。
4. 取执行快照，断言 `size in 1..5`，且 `distinct()` 仅包含 `sessionKey`；五次通知不得产生超过五次或无归属执行。
5. 再调用一次 `ticks.tick()`，沿用有界等待后断言执行快照不增加，证明 idle 禁用持续有效。

这是显式接受 Count Trigger 的合法多次执行，不是将原恰好一次改为简单 `>=1` 后立即通过。不得新增 sleep 作为主要同步；现有 tick 无副作用观察可使用原有界 delay。也不要求第二次 Runner 阻塞/取消，避免把关闭语义混入本用例。

## 复验与停止

该单测连续至少 5 次通过（含 `--no-daemon` 一次），再跑整个 `ExtractionPipelineLifecycleTest` 和四模块全量。交付记录旧文件 SHA `4debb92db77afbe1bc2c0f6eeab0e5e0487f6cd559d1b30dfe190470ce9ebbff`、新 SHA、诊断日志及正式断言行号。

若出现超过五次、其他 Session、通知不 drain、Scheduler 不在 RUNNING 且 queue 0 的稳定状态，或 tick 后新增执行，则作为真实生产问题重新暂停上报，不得放宽上述边界。无 Git 写操作。
