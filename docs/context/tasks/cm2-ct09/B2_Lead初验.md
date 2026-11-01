# CT09 B2 · Lead 独立初验（Count / Idle / Cron）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：Lead 初验历史结论通过；**2026-10-30 Architect 首次复核未通过，B2-R1 待修**。L1 已升级阻断：持续调度失败既紧凑重试又无法响应关闭取消，见 [复核 §3](架构与技术复核.md)。B3 不启动，下文保留初验历史。
**依据**：[B2_委派单](B2_委派单.md)、[架构与技术复核 §2.4](架构与技术复核.md)、[Architect 裁定](Architect_裁定.md) D1/D2/D6/D7/D8

## 0. 本次修改（2026-10-30，B2 初验记录建立）

Worker 完成 B2 并交付；Lead 独立复算 SHA、跑定向命令、逐条核对 Count/Idle/Cron 契约，并用自建探针复核阈值、极端 Duration、真实重叠 skip、Cron 重叠 skip。**全部通过**；记录 5 项非阻断观察。

**2026-10-30 复核更新**：Architect 首次独立复核判定 **B2-R1 阻断**——L1（IdleCron 持续调度失败紧凑循环）被默认 tick 源 + 极端 Clock 探针复现，本文件原 L1“非阻断”结论已失效；修复见 [B2_R1修复委派单](B2_R1修复委派单.md)。

**2026-10-30 提交状态**：复核请求已发出（message `05c96dab`），Architect 侧因 429 Too Many Requests 报错中断；**尚未获得 B2 裁定**，B2 保持 IN_REVIEW、B3 未启动；等待 Architect 配额恢复后再提交。

## 1. 交付面与边界

本批窗口（08:19–08:23）仅 7 个新增文件；B1 五文件 SHA 与基线完全一致；未改 Context/Factory/SQL/索引/构建/依赖：

| 类型 | 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- | --- |
| 新 | `CountTrigger.kt` | `4dc86afe…6028c78` |
| 新 | `IdleTrigger.kt` | `cdd63513…ecd5d26b` |
| 新 | `IdleCron.kt` | `81293224…72dbcaf` |
| 新 | `ExtractionTriggerFixture.kt`（测试辅助） | `9f04f6b4…2760de9e` |
| 新 | `CountTriggerTest.kt` | `5d82def1…fbb0be9` |
| 新 | `IdleTriggerTest.kt` | `e04ef1c6…30c2c0866` |
| 新 | `IdleCronTest.kt` | `3db15775…901bc5e` |

- B1 基线不变：`ExtractionScheduler.kt` `4fe97700…`、`ExtractionConfig.kt` `050b01e8…`、`ContextConfigLoader.kt` `939895f0…`、`ExtractionSchedulerTest.kt` `e4aab355…`、`ExtractionSchedulerCloseTest.kt` `58b671ac…`。
- `spotlessApply` 后 SHA 不变；未改 `AlcyoneContextImpl`/Factory/`IngestionNotifier` 签名/SQL/`ContextSchema.sq`/vfs/common/persistence/server/构建/依赖；无 git 操作。

## 2. Lead 独立实跑与计数

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| 探针后恢复正式 runtime 测试 | BUILD SUCCESSFUL |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 19 | 19 | 0 | 0/0/0 |
| runtime | 239 | 239 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

新增 25 例：Count 7 / Idle 10 / Cron 8；runtime 214 → 239。

## 3. Count Trigger 契约核对（D7）

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 实现既有 `IngestionNotifier`，签名未改 | `CountTrigger : IngestionNotifier`；`IngestionNotifier.kt` SHA 未变 | ✔ |
| `countPending >= threshold` 才 `request`；只统计 pending | 阈值 4/5 用例；complete/failed 不计 | ✔ |
| 不新增独立 Turn Counter | 每次通知查 DB，无计数器状态 | ✔ |
| 查询失败交既有 reporter、0 触发、不影响 Ingestion | `FlakyDatabase` + reporter 用例 | ✔ |
| `CancellationException` 原样传播 | 专用用例 | ✔ |
| DUPLICATE 不通知、CREATED 通知不变 | 真实 `IngestionService` 用例（第 5 条 CREATED 触发、DUPLICATE 不二次通知） | ✔ |
| 日志/错误无 SessionKey/消息/SQL/异常文本 | CountTrigger 不写日志；reporter 只收异常对象；用例断言 | ✔ |

## 4. Idle Trigger 契约核对（D6 + §2.4）

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 所有状态最新 `created_at` ≤ now-idleTimeout；等号边界 | 真实 SQLite 等号用例 | ✔ |
| 较新 complete/failed 阻止旧 pending；timestamp 无关 | 专用用例 | ✔ |
| 无 pending/仅 failed 不触发；多 Runtime/Agent/Session 隔离 | 专用用例 | ✔ |
| `idleBefore` 直接用 Instant 运算，不转毫秒/秒饱和 | 源码 `clock.instant().minus(idleTimeout.toJavaDuration())` | ✔ |
| 极大有限 Duration / Instant.MIN 受控失败、0 触发、不缩窗；下轮可继续 | 两个专用用例 + Lead 探针（极长 Duration 后正常扫描触发） | ✔ |
| single-flight 真实重叠 skip、计数 + `extraction.idle.skipped` DEBUG | 屏障用例 + Lead 探针 | ✔ |
| 查询失败 `idle.failed` WARN、0 触发、下次成功；无 Throwable/SessionKey | `FlakyDatabase` 用例 + Lead 探针 | ✔ |
| CE 原样传播、不记普通失败、single-flight 释放 | 专用用例 | ✔ |
| 复用既有 `findIdleSessions`，无 SQL/索引改动 | 源码；`ContextSchema.sq` 未变 | ✔ |

## 5. Cron 契约核对（D2/D6）

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| UNIX5 + 固定 `Asia/Shanghai`；严格未来 tick | `nextAfter` 用例（09:00 上海=01:00Z；整点不返回同一时刻） | ✔ |
| 启动不扫描；每 tick 一次扫描 | 用例 | ✔ |
| 不补跑错过 tick | `*/5` 从 08:23 → 08:25 用例 | ✔ |
| 无下一 tick 受控失败、不触发 | “2 月 30 日”用例 | ✔ |
| tick 不 await 扫描；扫描中到达真实 skip | 用例 + Lead 探针（ManualTicks + 屏障） | ✔ |
| close 停循环、取消并 join 在途扫描（finally 已执行、在途 0、关闭后不再触发） | 用例 | ✔ |
| 调度失败 WARN 不触发、下一 tick 继续 | 用例 | ✔ |
| 测试注入 tick/clock，不真实等 5 分钟 | 源码/用例 | ✔ |

## 6. Lead 独立探针（4/4 PASS）

探针：`.local/validation/cm2/lead/ct09-b2/probe/LeadCt09B2ProbeTest.kt`（`lead-probe-results.xml`，`tests=4 failures=0 errors=0`）：

| # | 探针 | 结果 |
| --- | --- | --- |
| 1 | 真实 SQLite：4 pending 不触发、5 pending 触发 | PASS |
| 2 | 极长有限 Duration（约 1e18 天）→ FAILED、0 触发；随后 10m 正常扫描触发 | PASS |
| 3 | 同实例真实重叠 → SKIPPED、skip 计数 1、DEBUG 日志；放行后首次 TRIGGERED | PASS |
| 4 | Cron 扫描中到达下一 tick → 真实 skip（不串行 await），放行后扫描触发 | PASS |

## 7. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | `IdleCron.runTicks` 对默认 tick 源的**持续**调度异常会 `continue` 立即重试，形成紧凑失败循环并阻塞 close | **已被 Architect 判定为 B2-R1 阻断**（默认 tick 源 + 极端 Clock 探针 500ms 内 clockReads 243→504578、close 卡在 join）；修复见 [B2_R1修复委派单](B2_R1修复委派单.md) |
| L2 | `IdleCron.closeAsync` 无清理失败注入路径（run/scan join 不抛业务失败）；等待者取消/共享失败测试不适用 | B2 未要求；B3 接线时统一生命周期 |
| L3 | `CountTrigger.request` 返回值被忽略；Scheduler 关闭时静默不触发 | 符合 D7/Count 语义；B3 接线后由 Context 生命周期保证 |
| L4 | `IdleTrigger`/`CountTrigger` 捕获 `Throwable`（含 `Error`）按普通失败/隔离；与 B1 既有模式一致 | 后续统一审阅关注，非本批阻断 |
| L5 | `IdleCron.triggerScan` 每个 tick 都创建一个短命 LAZY Job（skip 时立即结束）；数量受 tick 频率约束 | 设计使然（真实单飞），非阻断 |

## 8. 结论与下一步

- Count/Idle/Cron 按 D1/D2/D6/D7 与 §2.4 落实；25 例新增测试 + 4 项 Lead 独立探针全绿；四模块计数差额 0；B1 五文件未被修改。
- **B2 Lead 初验通过，提交 Architect 独立复核**；B3 在复核通过前不启动，CT10+ 不启动。

### 证据路径

- `.local/validation/cm2/lead/ct09-b2/spotlessApply.log`、`core-runtime-test.log`、`core-runtime-test-restored.log`、`persistence-server-test.log`
- `.local/validation/cm2/lead/ct09-b2/counts.txt`
- `.local/validation/cm2/lead/ct09-b2/lead-probe.log`、`lead-probe-results.xml`、`probe/`
