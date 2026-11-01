# CT09 B2 · R1 修复 Lead 独立初验（IdleCron 持续失败可取消退避）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**2026-10-30 Architect 独立收口：B2-R1 CLOSED、B2 DONE、B3 放行**；见 [复核 §4](架构与技术复核.md)。独立探针 1/1、四模块 468/468 与格式通过；下文保留 Lead 提交记录。
**依据**：[B2_R1修复委派单](B2_R1修复委派单.md)、[架构与技术复核 §3](架构与技术复核.md)、[Architect 裁定](Architect_裁定.md) D2/D6

## 0. 本次修改（2026-10-30，B2-R1 初验记录）

Worker 修复 B2-R1（仅 `IdleCron.kt` + `IdleCronTest.kt`）；Lead 独立复算 SHA、跑正式回归、重跑 Architect 探针，并自建 2 项探针复核快速关闭与恢复不扫描。**Architect 探针 1/1 转绿**。

## 1. 交付面与边界

本批仅 2 文件变化；其余 B2/B1 文件 SHA 未变：

| 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- |
| `IdleCron.kt` | `a137f37c2be75c990f784317d946ac979843d1acc9f326ff03eec1b15c4a8afc` |
| `IdleCronTest.kt` | `beb395e858c884a3ddb357b6d9b7c021049bc39b54267afa100a9a56754446b5` |

- 未变：`CountTrigger` `4dc86afe…`、`IdleTrigger` `cdd63513…`、`ExtractionScheduler` `4fe97700…`、`CountTriggerTest` `5d82def1…`、`IdleTriggerTest` `e04ef1c6…`、`ExtractionTriggerFixture` `9f04f6b4…`；未改配置/Loader/Context/Factory/SQL/索引/构建/依赖。
- `spotlessApply` 后 SHA 不变；无 git 操作。

## 2. Lead 独立正式回归

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| 探针后恢复正式 runtime | BUILD SUCCESSFUL |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 19 | 19 | 0 | 0/0/0 |
| runtime | 243 | 243 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

Cron 测试 8 → 12（+4），runtime 239 → 243。

## 3. 修复协议核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 每轮等待 tick 前 `ensureActive` | `runTicks` 首行 `currentCoroutineContext().ensureActive()` | ✔ |
| 普通调度失败：WARN + 固定 1s 可取消退避 | `delay(SCHEDULING_FAILURE_BACKOFF_MILLIS)`（1000ms 内部常量，无配置）；日志固定事件、无 Throwable/SessionKey/文本 | ✔ |
| 退避不是 tick：不直接扫描/不补跑 | 退避后 `continue` 回到循环，重新 `awaitTick` 计算严格未来；恢复用例仅自然 tick 扫描一次 | ✔ |
| CE 原样传播 | `catch (cancellation)` 重抛；`delay` 可取消 | ✔ |
| 关闭不等满退避 | 退避中 close ≤900ms 完成；owner `NonCancellable + join` 未改 | ✔ |
| 持续失败有受控间隔、无紧凑循环 | 连续 WARN 间隔 ≥900ms；默认 tick + `Instant.MAX` 时 clock 读取有界；close 后 CLOSED/在途 0 | ✔ |
| 既有语义未回退 | 严格未来/上海/启动不扫描/不补跑/真实重叠 skip/close join 用例保留并通过 | ✔ |

## 4. Architect 探针（1/1 PASS）

命令：

```bash
./scripts/dev gradle :context:runtime:test --tests '*ArchitectIdleCronProbeTest' \
  -I .local/validation/cm2/architect/ct09-b2/probe.init.gradle --console=plain
```

结果：`tests=1 failures=0 errors=0`，`repeatedDefaultClockFailureMustRemainCancellable PASS`。修复前为 1/1 FAIL（500ms 内 clockReads 243→504578、state=CLOSING）；修复后快速关闭并完成清理。正式 runtime XML 已恢复 243/243。

## 5. Lead 独立探针（2/2 PASS）

探针：`.local/validation/cm2/lead/ct09-b2-r1/probe/LeadB2R1ProbeTest.kt`（`lead-probe-results.xml`，`tests=2 failures=0 errors=0`）：

| # | 探针 | 结果 |
| --- | --- | --- |
| 1 | 默认 tick + `Instant.MAX` 持续失败：close 快速完成、CLOSED、零扫描、clock 读取受控 | PASS |
| 2 | 恢复至 `00:04:59Z` 后不立即扫描；`00:05:00Z` 恰好一次；推进到 `00:05:30Z` 不补跑 | PASS |

## 6. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | 固定 1s 退避使连续失败用例耗时约 2s；仅测试成本 | 设计按 Architect 要求固定内部常量；非阻断 |
| L2 | `awaitNextCronTick` 返回 null（无未来 tick）仍直接结束循环，不进入退避 | 既有受控失败路径，语义务“无下一 tick 结束”；非阻断 |
| L3 | 退避时长无配置/无 jitter | Architect 明确固定 1s；符合裁定 |
| L4 | 默认 tick 源 `Instant.MAX` 仍会依赖注入 Clock 才可达 | 测试边界；正常系统时钟不受影响 |

## 7. 结论与下一步

- B2-R1 的持续失败紧凑循环与关闭阻塞已修复：每轮取消检查 + 1s 可取消退避；退避不扫描/不补跑；close 立即响应；Architect 探针 1/1 转绿；正式 243/243。
- **B2-R1 Lead 初验通过，提交 Architect 独立复核**；B3 在复核通过前不启动，CT10+ 不启动。

### 证据路径

- `.local/validation/cm2/lead/ct09-b2-r1/spotlessApply.log`、`core-runtime-test.log`、`core-runtime-test-restored.log`、`persistence-server-test.log`
- `.local/validation/cm2/lead/ct09-b2-r1/architect-probe.log`（1/1）
- `.local/validation/cm2/lead/ct09-b2-r1/lead-probe.log`、`lead-probe-results.xml`、`probe/`
- `.local/validation/cm2/lead/ct09-b2-r1/counts.txt`
