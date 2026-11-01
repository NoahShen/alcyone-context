# CT09 B1 · Lead 独立初验（配置 / Runner Port / Scheduler）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：Lead 初验历史结论为通过；**2026-10-30 Architect 独立复核未通过，R1 待修**，见 [复核 §1](架构与技术复核.md)。L1 已由真实子任务屏障反例升级为阻断，不再延后 B3；下文保留初验记录。B2 不启动。
**依据**：[B1_委派单](B1_委派单.md)、[Architect 裁定](Architect_裁定.md) D1～D8、[CT09 任务书](../../milestones/cm2/CT09_任务书.md) §1/§2/§5

## 0. 本次修改（2026-10-30，B1 初验记录建立）

Worker 完成 B1 并交付；Lead 独立复算 SHA、跑定向命令、逐条核对配置/Runner/Scheduler/关闭契约，并用自建探针复核 cron cause、timezone 未知键、非关闭 CE 终止。**全部通过**；记录 7 项非阻断观察。

**2026-10-30 复核更新**：Architect 首次独立复核判定 **R1 阻断**——L1（非关闭 CE 由 Worker 提前发布成功、绕过真实 join/清理）被两个真实探针复现，本文件原 L1 的“非阻断”结论已失效；修复与回归见 [架构与技术复核 §1](架构与技术复核.md) 与 [B1_R1修复委派单](B1_R1修复委派单.md)。

## 1. 交付面与边界核对

本批窗口（Oct30 07:44–07:48）仅 9 个文件；`ContextConfigLoaderTest`、`AlcyoneContextImpl`、`AlcyoneContextFactory`、两个 `build.gradle.kts` 与基线一致：

| 类型 | 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- | --- |
| 新 | `core/.../core/extraction/ExtractionRunner.kt` | `6b65795f…13ad98` |
| 新 | `runtime/.../runtime/extraction/ExtractionConfig.kt` | `050b01e8…3376a5d` |
| 新 | `runtime/.../runtime/extraction/ExtractionScheduler.kt` | `bc8fb9a2…586b189` |
| 改 | `runtime/.../runtime/ContextConfig.kt` | `5c233ea1…0c733a6` |
| 改 | `runtime/.../runtime/ContextConfigLoader.kt` | `939895f0…dc447111` |
| 新 | `.../extraction/ExtractionConfigValidationTest.kt` | `7be5db46…4043ee9c` |
| 新 | `.../extraction/ExtractionSchedulerTest.kt` | `e4aab355…f50ac0b4` |
| 新 | `.../extraction/ExtractionSchedulerCloseTest.kt` | `54a88805…71ff257` |
| 可选订正 | `core/.../ingestion/IngestionNotifier.kt`（仅 KDoc） | `f9616feb…6fbdec1d` |

- `spotlessApply` 后 9 个 SHA 不变；`ContextConfigLoaderTest` 未改（无追加断言）；未改 `AlcyoneContextImpl`/Factory/`Llm*`/Repository/SQL/`ContextSchema.sq`/vfs/common/persistence/server/构建/依赖。
- `context/api` 无新增；`ExtractionRunner` 在 `core.extraction`（Kotlin public，非 api）；`ExtractionConfig`/`ExtractionDefaults` public（`ContextConfig` 公共字段需要），Scheduler/校验/接缝均 `internal`。
- `git status --porcelain vfs integration-tests/vfs` 为 0 行；无新表/索引/`user_version`。

## 2. Lead 独立实跑与计数

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :integration-tests:context:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL（公共 `ContextConfig` 变更的跨模块编译/行为回归） |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 19 | 19 | 0 | 0/0/0 |
| runtime | 211 | 211 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |
| integration-tests/context | 15 | 15 | 0 | 0/0/0 |

增量一致：runtime 189 → 211（+22：配置 9 / 调度 7 / 关闭 6）。

## 3. 配置契约核对（D1/D2）

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 层级 `context.extraction.triggers.count.threshold` / `idle.enabled` / `idleTimeout` / `cron` | `ContextConfigLoader.parseExtraction`；四层 `rejectUnknown` | ✔ |
| 默认 5 / true / 10m / `*/5 * * * *`；全部可省略 | 默认值测试 + 空 YAML 测试 | ✔ |
| threshold `Int >= 1`、类型/越界拒绝 | `intAt` + `validateExtraction` + 表驱动用例 | ✔ |
| idleTimeout 有限正 Duration；0/负/无限拒绝 | `isFinite`/`isNegative`/`ZERO` + 用例 | ✔ |
| UNIX 五字段 Cron；4/6 字段/非法拒绝；固定上海、无 timezone 键 | `CronParser(UNIX).validate()`；`timezone` → `UNKNOWN_FIELD`（用例 + Lead 探针） | ✔ |
| 非法值 `INVALID_CONFIG` 含路径、不回显原值、cron 不挂 cause | 用例断言消息路径与 `cause == null`；Lead 探针复算 | ✔ |
| 直接 Kotlin 构造与 YAML 同一校验 | `ExtractionConfig.init` + Loader 构造同一类型；直接构造用例 | ✔ |
| `idle.enabled=false` 不豁免其他字段 | 专用用例 | ✔ |
| `ContextConfig.extraction` 默认值、未配置行为不变 | `ContextConfig` 末尾默认参数；既有 loader 测试未改且全绿 | ✔ |

## 4. Runner 与 Scheduler 契约核对（D3/D4/D5）

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| Core Runner public、非 api、仅 `execute(SessionKey)` | `ExtractionRunner.kt`；`context/api` 未改 | ✔ |
| 状态 `NEW/RUNNING/CLOSING/CLOSED`；`start` 幂等、关闭后拒绝 | 源码 + `request after close … start is idempotent` 用例 | ✔ |
| `request` 仅 RUNNING；关闭后 false/no-op 且不 WARN | 用例（捕获日志为空） | ✔ |
| `Int` 饱和 MAX、不 wrap、单排队项 | 受控接缝 `setPendingForTesting` + 用例 | ✔ |
| `trySend` 失败回滚计数、`enqueue.failed` WARN、无幽灵计数 | `closeChannelForTesting` + 用例 | ✔ |
| 单 Worker 全局串行；执行中同 Session 不重复入队；剩余队尾重入；归零移除 | Fake Runner 屏障 + 用例 | ✔ |
| 普通异常消费一次、隔离继续、`run.failed` WARN 无 SessionKey/异常文本/Throwable | 用例捕获日志断言 | ✔ |
| CE 不按普通失败消费；非关闭 CE → 封闭准入、CLOSED、`worker.stopped` WARN、不重启、closeAsync 不悬挂 | 用例 + Lead 探针（状态/请求/执行次数/日志/无标记） | ✔ |
| `closeAsync` 唯一入口；先封闭准入 → 取消 Worker → close Channel（不 drain）→ 真实 join → 清计数 → 发布 | 源码 + 关闭用例（finally 屏障、排队项不执行） | ✔ |
| owner `NonCancellable`、共享失败同实例、等待者取消只退自身、NEW 直接关闭 | 三个关闭用例 | ✔ |
| Runner 内关闭自身具名拒绝、无自等待 | 用例（Runner 内调用 → `CLOSED` 消息；外部 close 完成） | ✔ |
| 锁内无 I/O/回调/挂起；状态/计数/入队/准入/封闭同一短锁 | 源码审查 | ✔ |
| 公共 Factory 未接线、未启用生产 pipeline | `AlcyoneContextImpl`/Factory SHA 不变 | ✔ |

## 5. Lead 独立探针（3/3 PASS）

探针：`.local/validation/cm2/lead/ct09-b1/probe/LeadCt09B1ProbeTest.kt`（`lead-probe-results.xml`，`tests=3 failures=0 errors=0`）：

| # | 探针 | 结果 |
| --- | --- | --- |
| 1 | 直接构造 6 字段 cron → `INVALID_CONFIG`、消息含 `context.extraction.triggers.idle.cron`、`cause == null` | PASS |
| 2 | YAML `idle.timezone` → `UNKNOWN_FIELD` 且路径正确 | PASS |
| 3 | Runner 非关闭 CE → `CLOSED`、request false、`start()` 拒绝、`closeAsync` 5s 内返回、执行次数 1（不重放）、`worker.stopped` 无 Throwable/CE 标记 | PASS |

## 6. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | 非关闭 CE 终止时由 Worker 自行发布终态；之后 `closeAsync()` 走共享结果路径，不再 close Channel/cancel scope | **已被 Architect R1 判定为阻断**（真实子任务 finally 未结束时 close 返回、清理失败被跳过）；本文件保留原记录，修复见 [B1_R1修复委派单](B1_R1修复委派单.md) |
| L2 | `runWorker` 对 `runner.execute` 的 `Throwable`（含 `Error`）按普通失败隔离并继续 | 与既有 Worker 容错模式一致；如需只捕 `Exception` 可在 B2/B3 评审，非本批阻断 |
| L3 | `executionCount` 在**执行准入**时累加（诊断语义「已启动执行次数」），非完成时 | Worker 已记录调试过程；语义已写入 KDoc，测试按此断言 |
| L4 | `ExtractionConfig`/`ExtractionDefaults` 为 public（`ContextConfig` 公共字段需要）；Runner public 属 D3 明确要求 | 非 api SDK；Scheduler/校验/接缝 internal |
| L5 | `setPendingForTesting`/`closeChannelForTesting`/`closeActionForTesting` 为 internal 测试接缝 | 与既有 `*ForTesting` 同模式；生产无行为 |
| L6 | 极大但有限的 `idleTimeout` 通过 B1 校验；B2 计算 `now - idleTimeout` 时必须防溢出 | 已列入 B2 契约（D1） |
| L7 | `ContextConfig.toString` 现包含 extraction 字段（threshold/timeout/cron） | 非敏感信息；无 Key/正文 |

## 7. 结论与下一步

- D1/D2 配置、D3 Runner Port、D4/D5 Scheduler 计数/串行/异常隔离/关闭协议全部按裁定落实；22 例新增测试与 3 项 Lead 探针全绿；四模块 + integration context 计数差额 0。
- **B1 Lead 初验通过，提交 Architect 独立复核**；B2 在复核通过前不启动，CT10+ 不启动。

### 证据路径

- `.local/validation/cm2/lead/ct09-b1/spotlessApply.log`、`core-runtime-test.log`、`core-runtime-test-final.log`、`persistence-server-test.log`、`integration-context-test.log`
- `.local/validation/cm2/lead/ct09-b1/counts.txt`
- `.local/validation/cm2/lead/ct09-b1/lead-probe.log`、`lead-probe-results.xml`、`probe/`
