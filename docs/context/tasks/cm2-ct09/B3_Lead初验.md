# CT09 B3 · Lead 独立初验（Pipeline 组装与 Runtime 生命周期接线）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：Lead 初验历史结论通过；**2026-10-30 Architect 首次复核未通过，B3-R1 待修**，见 [复核 §5](架构与技术复核.md)。B4 不启动，下文保留初验历史。
**依据**：[B3_委派单](B3_委派单.md)、[架构与技术复核 §4.2](架构与技术复核.md)、[Architect 裁定](Architect_裁定.md) D3/D4/D8

## 0. 本次修改（2026-10-30，B3 初验记录建立）

Worker 完成 B3 并交付；Lead 独立复算 SHA、跑定向命令、逐条核对 Pipeline/Factory/Impl 生命周期，并自建探针验证「RUNNING 前不执行 Runner」与公共零 pipeline。**全部通过**；记录 6 项非阻断观察。

## 1. 交付面与边界

本批窗口（14:52–14:54）仅 4 个文件；B1/B2 十二个组件/测试 SHA 全部未变：

| 类型 | 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- | --- |
| 新 | `runtime/extraction/ExtractionPipeline.kt` | `bdd1959f…01b70051` |
| 改 | `runtime/AlcyoneContextImpl.kt` | `cb060039…dc46f5bc9` |
| 改 | `runtime/AlcyoneContextFactory.kt` | `1f996ce9…87aced884` |
| 新 | `runtime/ExtractionPipelineLifecycleTest.kt` | `791e390a…086ab0d5b9` |

- B1/B2 基线：`ExtractionScheduler` `4fe97700…`、`IdleCron` `a137f37c…`、`IdleTrigger` `cdd63513…`、`CountTrigger` `4dc86afe…`、`ExtractionConfig` `050b01e8…`、`ContextConfigLoader` `939895f0…` 及全部 B1/B2 测试未变。
- `spotlessApply` 后 4 文件 SHA 不变；未改 Runner/IngestionNotifier 签名/SQL/索引/其他测试/构建/依赖；无 git 操作。

## 2. Lead 独立实跑与计数

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| 探针后恢复正式 runtime | BUILD SUCCESSFUL |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 19 | 19 | 0 | 0/0/0 |
| runtime | 256 | 256 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

新增 13 例：runtime 243 → 256。

## 3. Pipeline 契约核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| CountTrigger/Scheduler/IdleTrigger/IdleCron 组装；`idle.enabled=false` 不建/不启 Cron | `ExtractionPipeline` 构造；禁用 idle 用例 | ✔ |
| 激活门：包装 Runner 先 `activation.await()`；`start()` 只启组件；RUNNING 后 `activate()` | 源码 + Lead 探针（STARTING 期间请求已入队但 Runner 零执行；激活后执行一次） | ✔ |
| Runner 不得在 RUNNING 前执行 | Lead 探针 + 关闭竞争用例（close 获胜时 Runner 未执行） | ✔ |
| `closeAsync`：先 IdleCron（停 tick/join 扫描）再 Scheduler（join Worker/Runner）；失败 chain 抛主失败 | 源码 + 释放顺序用例 | ✔ |
| CountTrigger 作为 `IngestionNotifier`；Count 阈值/DUPLICATE/失败 reporter 语义不变 | Count 用例、真实 SQLite | ✔ |
| 回调标记：Runner 与 Idle 请求回调安装 owner；Context 入口具名拒绝 | 源码 + 两类关闭用例 | ✔ |
| `startFailureForTesting`/`closeFailureForTesting`/hook/访问器均 internal | 源码审查 | ✔ |

## 4. Runtime 接线核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 公共 `create` 签名/行为不变；不接 Runner、不建 pipeline、零后台副作用 | `Factory` 公共 create 传 null；Lead 探针公共 create 后 `pipelineForTesting()==null` | ✔ |
| 内部重载注入 Runner/Idle tick/configurator；无 Runner 不建 pipeline/Cron/Worker | `Factory` 内部重载；无 Runner 用例 | ✔ |
| `performStart` 顺序 `executor→vfs→llm→pipeline`；pipeline 用已取得 DB；`start()`→probe→锁内 RUNNING→`activate()` | 源码 | ✔ |
| close 获胜不误报 start 失败；pipeline 不执行 | 关闭竞争用例（start 得到 CLOSED、close 正常、Runner 零执行） | ✔ |
| 非竞争 pipeline 启动失败：抛出原始失败、逆序清理、CLOSED | 启动失败用例（`startFailureForTesting`，VFS/EXECUTOR 释放） | ✔ |
| `shutdown`：锁内 STOPPING → 等待通知 → 置空 → `releaseResources(pipeline→llm→vfs→executor)` | 源码 + 顺序用例 `[PIPELINE, LLM, VFS, EXECUTOR]` | ✔ |
| pipeline 清理失败：发布终态且继续释放其余资源 | 顺序用例：pipeline 抛唯一失败，LLM/VFS/executor 仍释放，Context 抛同一失败、CLOSED | ✔ |
| 不在通知/扫描/Runner 未结束时释放 DB/LLM/VFS；真实 join | 关闭用例：Runner finally 已执行、在途 0；通知先 drain | ✔ |
| 无 Runner 时沿用既有 ingestionNotifier；有 Runner 用 CountTrigger | 源码 `createIngestionService(database, pipeline?.ingestionNotifier ?: ingestionNotifier)` | ✔ |
| 关闭后 0 触发、已释放 DB 不访问 | 关闭后 tick/通知用例（仅 reporter 路径） | ✔ |
| 并发 close 共享失败、pipeline 只关一次 | 并发 close 用例 | ✔ |

## 5. Lead 独立探针（2/2 PASS）

探针：`.local/validation/cm2/lead/ct09-b3/probe/LeadCt09B3ProbeTest.kt`（`lead-probe-results.xml`，`tests=2 failures=0 errors=0`）：

| # | 探针 | 结果 |
| --- | --- | --- |
| 1 | startProbe（RUNNING 发布前）向 Scheduler 提交请求：`status=STARTING`、150ms 内 Runner 零执行；start 返回后 Runner 恰好执行一次、status=RUNNING | PASS |
| 2 | 公共 `create` start 后 `pipelineForTesting()==null`、RUNNING、close 成功 | PASS |

## 6. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | IdleCron 可能在 start 期间先进行 DB 扫描（Runner 仍被激活门挡住） | 满足「Runner 不得在 RUNNING 前执行」；DB 读取属 Idle 组件既有行为，非阻断 |
| L2 | `ExtractionPipeline.closeAsync` 重复直接调用会重复执行 `closeHookForTesting`（Context 路径只关一次） | 测试接缝；生产 Context 共享关闭，非阻断 |
| L3 | Count 请求回调未安装 Extraction 标记；其内部关闭由既有 ingestion notification 拒绝覆盖 | 符合要求范围（Runner/Idle 回调）；非阻断 |
| L4 | configurator/测试接缝为 internal，可修改 pipeline 行为 | 仅同模块测试；非公开 API |
| L5 | `activate()` 在关闭后调用为幂等 no-op | 竞争安全；非阻断 |
| L6 | 无 Runner 时 pipeline 不存在，`context.extraction` 配置仍解析但不产生后台任务 | 符合 D3/CT11 阶段边界 |

## 7. 结论与下一步

- Pipeline 组装、激活门、公共零 pipeline、内部 Runner 注入、关闭顺序/真实 join、竞争与失败清理、回调拒绝、Count/Idle 触发与关闭后 0 触发均按 §4.2 落实；13 例新增 + 2 项 Lead 探针全绿；四模块 19/256/176/30 差额 0。
- **B3 Lead 初验通过，提交 Architect 独立复核**；B4 在复核通过前不启动，CT10+ 不启动。

### 证据路径

- `.local/validation/cm2/lead/ct09-b3/spotlessApply.log`、`core-runtime-test.log`、`core-runtime-test-restored.log`、`persistence-server-test.log`
- `.local/validation/cm2/lead/ct09-b3/counts.txt`
- `.local/validation/cm2/lead/ct09-b3/lead-probe.log`、`lead-probe-results.xml`、`probe/`
