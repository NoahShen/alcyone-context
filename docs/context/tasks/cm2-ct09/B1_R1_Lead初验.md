# CT09 B1 · R1 修复 Lead 独立初验（真实 join / 唯一清理）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**2026-10-30 Architect 独立收口：R1 CLOSED、B1 DONE、B2 放行**；见 [复核 §2](架构与技术复核.md)。探针改为 timeout 内捕获与比较，同实例要求保留；独立探针 2/2、对照 3/3、五模块正式 454/454。下文保留 Lead 提交时记录。
**依据**：[B1_R1修复委派单](B1_R1修复委派单.md)、[架构与技术复核 §1](架构与技术复核.md)、[Architect 裁定](Architect_裁定.md) D4/D5

## 0. 本次修改（2026-10-30，R1 修复初验记录）

Worker 修复 R1（仅 `ExtractionScheduler.kt` 与 `ExtractionSchedulerCloseTest.kt` 两文件）；Lead 独立复算 SHA、跑正式回归、重跑两个 Architect 探针，并自建 3 项探针验证共享实例与协程边界复制。**探针一由 FAIL 转 PASS；探针二仅身份断言失败，根因为 `withTimeout` 边界复制，非实现缺陷**。提交 Architect 裁定探针。

## 1. 交付面与边界

本批窗口仅 2 个文件；`ExtractionSchedulerTest.kt` 未改（SHA 与 B1 基线一致）；配置/Loader/Context/Runner 等零改动：

| 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- |
| `ExtractionScheduler.kt` | `4fe977006d562069fafbb3e489fc160bd62d0b465f3f15159872e093e7985c09` |
| `ExtractionSchedulerCloseTest.kt` | `58b671ac087c8c4439be713c323346edbcb58772501b3e14522438e3e4c15e9a` |
| `ExtractionSchedulerTest.kt` | `e4aab355…f50ac0b4`（未改） |

- `spotlessApply` 后 SHA 不变；无白名单外改动；无 git 操作。

## 2. Lead 独立正式回归

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| 探针后恢复正式 runtime：`:context:core:test :context:runtime:test --rerun-tasks` | BUILD SUCCESSFUL |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 19 | 19 | 0 | 0/0/0 |
| runtime | 214 | 214 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

新增 3 例关闭测试（6→9），runtime 211→214。

## 3. R1 修复协议核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 非关闭 CE 只封闭准入，不自行 CLOSED/清计数/发布结果 | Worker catch 仅 `beginCleanupLocked()` + `worker.stopped` + 原样抛 CE | ✔ |
| 唯一 owner 非失败 Worker、非调用方协程 | `beginCleanupLocked` 创建独立 `cleanupScope`（`Job()` parent + IO，无 Runner 标记）；仅第一个创建 `closeOutcome`，其余 await | ✔ |
| owner 顺序：取消 Worker → `queue.cancel`（不 drain）→ 真实 `worker.join()` → hook → 清计数 → 释放 workerParent/scope → CLOSED → 最后发布 | `runCleanup` 源码 | ✔ |
| 子任务 finally/结构化子 Job 被真实等待 | 探针一 PASS（真实子任务 `NonCancellable` 屏障未放行时 close 不返回） | ✔ |
| 清理失败也发布 CLOSED + 同一实例 | 无协程边界时 `assertSame`；正式测试 `first.cause === unique`（边界复制） | ✔（边界语义见 §4） |
| 并发 close 唯一清理 | 3 个并发 close，hook 只执行 1 次；取消一个等待者不影响 owner | ✔ |
| 等待者取消只退自身、owner 不受影响 | 关闭等待者取消用例 | ✔ |
| Runner 内 close 仍具名拒绝、cleanup 不继承标记 | 既有用例 + 源码 | ✔ |
| 普通失败继续/队尾重入/饱和/回滚/no-op/幂等/日志脱敏未回退 | 既有 7 个调度用例 + 6 个关闭用例全绿 | ✔ |

## 4. Architect 探针结果与根因

命令（按 Architect 原文）：

```bash
./scripts/dev gradle :context:runtime:test --tests '*ArchitectSchedulerProbeTest' \
  -I .local/validation/cm2/architect/ct09-b1/probe.init.gradle --console=plain
```

结果：**2 tests completed, 1 failed**（exit 1）：

| 探针 | 修复前 | 修复后 |
| --- | --- | --- |
| `unexpectedCancellationMustStillJoinWorkerChildren` | FAIL | **PASS** |
| `unexpectedCancellationMustNotBypassSharedCleanupFailure` | FAIL | FAIL（仅 `assertSame` 身份） |

失败信息：`expected: IllegalStateException@53812a9b but was: IllegalStateException@14b030a0`（同消息、不同实例）。

**根因（Lead 独立复现）**：探针二用 `withTimeout(5_000) { scheduler.closeAsync() }`；kotlinx.coroutines 的 stack-trace recovery 在异常逃出协程边界时复制异常（对带 `(String, Throwable)` 构造器的异常），复制体以原实例为 `cause`。因此任何在 `withTimeout` 内抛出的异常都**不可能**与外部 `unique` 保持 `assertSame`，与本实现无关。

Lead 独立探针（`.local/validation/cm2/lead/ct09-b1-r1/probe/LeadB1R1ProbeTest.kt`，3/3 PASS）：

| 探针 | 结果 |
| --- | --- |
| 无协程边界：`closeAsync()` 抛出 `assertSame(unique, first)`；第二次 close 也 `assertSame(unique, second)` | PASS |
| `withTimeout` 包裹：`first !== unique` 且 `first.cause === unique` | PASS |
| 对照：`withTimeout { throw unique }` 同样发生复制（`first !== unique`、`first.cause === unique`） | PASS |

结论：Scheduler 共享同一清理失败实例的语义成立；探针二的身份断言跨协程边界不成立。正式测试已按“无边界 `assertSame` + 有边界 `cause` 身份”覆盖。

## 5. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | cleanup 协程在 `outcome.complete` 后返回；`closeAsync` 不 join cleanupJob（资源已在发布前释放） | 无活动任务/资源；如需更严格可后续 join，非本批阻断 |
| L2 | `closeActionForTesting` 若在 hook 内调用 `closeAsync()` 会自等待（仅测试钩子；生产 null） | 测试契约注明；非生产路径 |
| L3 | 非关闭 CE 的 `worker.stopped` 在 cleanup 启动前记录；cleanup 失败时 close 另抛同一失败 | 可观察性满足；日志无敏感字段 |
| L4 | 探针二若改为 `assertSame(unique, first.cause)` 或去掉 `withTimeout` 包裹即可通过；Lead 未修改 Architect 探针文件 | 待 Architect 裁定 |

## 6. 结论与请求裁定

- R1 的真实缺陷（提前发布、绕过真实 join/清理）已修复：探针一由 FAIL 转 PASS；子任务 finally、唯一清理、并发 close、清理失败共享均有正式回归证据。
- **探针二仅因 `withTimeout` 协程边界的异常复制导致 `assertSame` 失败**；Lead 独立证明同一实例在无边界时成立、边界复制保留 `cause`。
- 请求 Architect 裁定：将探针二改为 `assertSame(unique, first.cause)`（或移除 `withTimeout` 包裹改用测试超时）；Lead 不擅自修改 Architect 探针。
- B1 暂不判 DONE，B2 不启动；无 git 操作。

### 证据路径

- `.local/validation/cm2/lead/ct09-b1-r1/spotlessApply.log`、`core-runtime-test.log`、`core-runtime-test-final.log`、`core-runtime-test-restored.log`、`persistence-server-test.log`
- `.local/validation/cm2/lead/ct09-b1-r1/architect-probe.log`（1/2）
- `.local/validation/cm2/lead/ct09-b1-r1/lead-probe.log`、`lead-probe-results.xml`、`probe/`
- `.local/validation/cm2/architect/ct09-b1/probe-results/`（Architect 探针 XML，未被正式 XML 覆盖）
