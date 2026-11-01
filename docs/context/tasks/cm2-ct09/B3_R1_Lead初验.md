# CT09 B3 · R1 修复 Lead 独立初验（STOPPING 窗口准入同步封闭）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**2026-10-30 Architect 独立收口：B3-R1 CLOSED、B3 DONE、B4 放行**；见 [复核 §6](架构与技术复核.md)。两个 STOPPING 探针 2/2、五模块 498/498 与格式通过；下文保留 Lead 提交记录。
**依据**：[B3_R1修复委派单](B3_R1修复委派单.md)、[架构与技术复核 §5](架构与技术复核.md)、[Architect 裁定](Architect_裁定.md) D3/D4

## 0. 本次修改（2026-10-30，B3-R1 初验记录）

Worker 修复 B3-R1（仅 `ExtractionPipeline.kt`、`AlcyoneContextImpl.kt`、`ExtractionPipelineLifecycleTest.kt`）；Lead 独立复算 SHA、跑五模块命令、重跑两个 Architect 探针。**探针 2/2 转绿**。

## 1. 交付面与边界

本批仅 3 文件变化；Factory 与 B1/B2 组件/测试 SHA 未变：

| 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- |
| `ExtractionPipeline.kt` | `9b898911712a4868658f84839ed4003c3e6d212dc457daf827499c6efa86757d` |
| `AlcyoneContextImpl.kt` | `71bcefaea00cb70840d3a370e0ffb6fa37ec22d671de886d15c5ee135ddfe09a` |
| `ExtractionPipelineLifecycleTest.kt` | `1e83b91a341cce9891d7284f7aee2f7d22cf108ffeda9dfc26ee1d374544a383` |

- 未变：`AlcyoneContextFactory` `1f996ce9…`、`ExtractionScheduler` `4fe97700…`、`IdleCron` `a137f37c…`、`IdleTrigger` `cdd63513…`、`CountTrigger` `4dc86afe…`、`ExtractionConfig` `050b01e8…`、`ContextConfigLoader` `939895f0…` 与 B1/B2 全部测试/fixture。
- `spotlessApply` 后 SHA 不变；未改 Runner/IngestionNotifier 签名/SQL/索引/构建/依赖；无 git 操作。

## 2. Lead 独立实跑与计数

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :integration-tests:context:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| 探针后恢复正式 runtime | BUILD SUCCESSFUL |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 19 | 19 | 0 | 0/0/0 |
| runtime | 258 | 258 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |
| integration-tests/context | 15 | 15 | 0 | 0/0/0 |
| **合计** | **498** | **498** | **0** | **0/0/0** |

新增 2 例（Lifecycle 13→15）：STOPPING 窗口新 Idle tick、已排队项均不得启动新 Runner。

## 3. 修复协议核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| Pipeline 关闭门：普通短同步、非挂起、不调 Context | `admissionLock` + `closing` + `beginClosing()`（synchronized 一个布尔） | ✔ |
| `beginClosing()` 幂等；`closeAsync()` 开头也调用 | 源码 | ✔ |
| Idle/Count 请求门关闭后 no-op | `requestFromIdle` / `requestFromCount` 检查 admission；`idleRequestHook` 前后双重检查 | ✔ |
| 包装 Runner 在 `activation.await()` 后、真实 Runner 前检查门，关闭则直接返回 | 源码；**未**只检查 Context 状态 | ✔ |
| `shutdown()` 首次状态锁内 `RUNNING→STOPPING` 同步 `beginClosing()`，释放锁前完成 | 源码（同一 `synchronized(lock)` 块内） | ✔ |
| 不改变 `activate` 语义、不使用 activate 代替 closing | 源码 | ✔ |
| 未改 Scheduler/IdleCron/IdleTrigger/CountTrigger/Factory | SHA 未变 | ✔ |
| 关闭顺序/通知收尾/真实 join/释放顺序未回退 | 既有实现未变；正式用例全绿 | ✔ |
| 已准入且在 STOPPING 前启动的 Runner 作为在途处理 | 探针二：第一项允许进入，第二项被门挡住；执行计数 1 | ✔ |

## 4. Architect 探针（2/2 PASS）

命令：

```bash
./scripts/dev gradle :context:runtime:test --tests '*ArchitectPipelineAdmissionProbeTest' \
  -I .local/validation/cm2/architect/ct09-b3/probe.init.gradle --console=plain
```

结果：`tests=2 failures=0 errors=0`：

| 探针 | 修复前 | 修复后 |
| --- | --- | --- |
| `newIdleTickMustNotStartRunnerWhileContextIsStopping` | FAIL | **PASS** |
| `queuedRunnerMustNotStartWhileContextIsStopping` | FAIL | **PASS** |

正式 runtime XML 已恢复 258/258。

## 5. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | 请求门检查与 `scheduler.request` 之间存在微小窗口，可能入队一个计数；Runner 门保证不执行，关闭时 Scheduler 取消/清理 | 不影响「STOPPING 后 0 新 Runner」判据；非阻断 |
| L2 | STOPPING 窗口内 IdleCron 仍可能完成/发起 DB 扫描，直到 pipeline close 停止 | 要求仅禁止新 Runner；DB 在 executor 释放前关闭，非阻断 |
| L3 | `beginClosing()` 在 Context 锁内获取 pipeline admissionLock；Runner/请求只短暂持 admissionLock，无反向 Context 锁路径 | 无死锁路径；非阻断 |
| L4 | 已排队项的 Trigger 计数被消费但未执行 | 关闭即丢弃进程内触发，符合不持久化 Trigger 语义 |
| L5 | Lifecycle 测试新增 2 例依赖 `beforeNotificationSnapshotForTesting` 屏障 | 既有测试接缝，确定性有界；非阻断 |

## 6. 结论与下一步

- STOPPING 后新 Idle tick 与已排队项均在 Runner 前被关闭门挡住；`beginClosing` 与 `RUNNING→STOPPING` 同锁；两个 Architect 探针 2/2 转绿；五模块 498/498。
- **B3-R1 Lead 初验通过，提交 Architect 独立复核**；B4 在复核通过前不启动，CT10+ 不启动。

### 证据路径

- `.local/validation/cm2/lead/ct09-b3-r1/spotlessApply.log`、`core-runtime-test.log`、`core-runtime-test-restored.log`、`persistence-server-test.log`、`integration-context-test.log`
- `.local/validation/cm2/lead/ct09-b3-r1/architect-probe.log`（2/2）
- `.local/validation/cm2/architect/ct09-b3/probe-results/`（Architect 探针 XML）
