# CT09 B4 · Lead 独立初验（全量验收与证据收口）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**B4 APPROVED / DONE；CT09 DONE（Architect 已于 2026-10-30 独立收口）**。见 [架构与技术复核 §7](架构与技术复核.md)。
**依据**：[B4_委派单](B4_委派单.md)、[架构与技术复核 §6.2](架构与技术复核.md)、[CT09 任务书](../../milestones/cm2/CT09_任务书.md) §5

## 0. 本次修改（2026-10-30，B4 初验记录）

Worker 完成 B4 只读验收；Lead 独立执行 `./scripts/check`、14 模块计数、VFS 基线/diff、Core 无 Ktor、schema 临时库探针，并复核 A01～A08 映射。**全部通过**；B4 未改任何源码/测试/构建/文档。

**2026-10-30 提交状态（历史）**：B4 复核请求已发出（message `611283bc`），Architect 侧因 429 Too Many Requests 报错中断；当时尚未获得 CT09 最终裁定，B4 保持待复核、CT09 未自标 DONE、CT10+ 未启动。Architect 恢复后已完成独立收口并裁定 CT09 DONE。

## 1. B4 交付面与非侵入核对

- B4 只新增 `.local/validation/cm2/worker/ct09-b4/` 证据；未改 `context/**`、`vfs/**`、`common/**`、`integration-tests/**` 源码/测试/构建，未改文档。
- 关键终态 SHA（Lead 复算，与 B3/Architect 一致，B4 未改）：
  `ExtractionPipeline 9b898911…`、`AlcyoneContextImpl 71bcefae…`、`AlcyoneContextFactory 1f996ce9…`、`ExtractionScheduler 4fe97700…`、`IdleCron a137f37c…`、`IdleTrigger cdd63513…`、`CountTrigger 4dc86afe…`、`ContextSchema.sq ec727a53…`。
- 未新增依赖/表/索引/`user_version`。

## 2. Lead 独立全量回归

| 命令 | 结果 |
| --- | --- |
| `./scripts/check` | **exit 0**，`BUILD SUCCESSFUL in 2m 7s`，**130 actionable tasks: 130 executed**（含 core/runtime spotlessCheck） |
| 14 模块行首 `@Test` vs XML | 见下表，**1365 = 1365**，差额 0、失败/错误/跳过 0 |

| 模块 | 源码 | XML | 差额 |
| --- | ---: | ---: | ---: |
| common | 23 | 23 | 0 |
| context/api | 26 | 26 | 0 |
| context/core | 19 | 19 | 0 |
| context/persistence-sqldelight | 176 | 176 | 0 |
| context/runtime | 258 | 258 | 0 |
| context/server | 30 | 30 | 0 |
| integration-tests/context | 15 | 15 | 0 |
| integration-tests/vfs | 107 | 107 | 0 |
| vfs/api | 66 | 66 | 0 |
| vfs/core | 305 | 305 | 0 |
| vfs/git-backup | 73 | 73 | 0 |
| vfs/persistence | 52 | 52 | 0 |
| vfs/runtime | 61 | 61 | 0 |
| vfs/storage | 154 | 154 | 0 |
| **合计** | **1365** | **1365** | **0** |

计数口径为行首 `@Test`；`integration-tests/context/.../B3Fixtures.kt` 注释中的 `@Test` 不计（宽松 grep 会得到 16，属既有注释）。

## 3. VFS 与 Core 边界

| 项 | 结果 |
| --- | --- |
| `git status --porcelain vfs integration-tests/vfs` | 0 行 |
| `git diff --stat -- vfs integration-tests/vfs` | 0 行 |
| 81 VFS 基线 | `Checked 81 files: all OK` |
| Core build 文本 ktor | 无命中 |
| Core main `import io.ktor` | 无命中 |
| `:context:core:dependencies --configuration compileClasspath` | ktor 命中 0 |
| `CoreSkeletonTest` / `KtorBoundaryTest` | 2/2、1/1 全绿 |
| 构建/依赖文件 | B4 窗口未修改（Worker+Lead mtime/SHA 一致） |

## 4. Schema 独立探针（Lead）

Lead 自建临时探针（`.local/validation/cm2/lead/ct09-b4/probe/LeadCt09B4SchemaProbeTest.kt`，`tests=1 failures=0 errors=0`）：用既有 `ContextDatabaseInitializer` 新建临时 DB，经 JDBC 读取：

- `PRAGMA user_version = 1`；
- 6 表：`conversation_turn`、`dreaming_checkpoint`、`organization_pending`、`organized_memory`、`organized_memory_source`、`raw_memory`；
- 5 索引：`idx_turn_session_time`、`idx_turn_pending_session_time`、`idx_turn_session_created_at`、`idx_organized_updated_at`、`idx_source_raw`。

与 `ContextSchema.sq` 中 6 个 `CREATE TABLE`、5 个 `CREATE INDEX` 一致；schema SHA `ec727a53…` 与 B3 终态一致。探针后已重跑正式 runtime 恢复 258/258。

## 5. A01～A08 映射（按任务书编号）

| # | 验收 | 正式测试证据 | 结果 |
| --- | --- | --- | --- |
| A01 | 配置与默认值 | `ExtractionConfigValidationTest`（9 例：默认/覆盖/直接构造/四层未知键/timezone/threshold/idleTimeout/cron/idle=false 不豁免） | 9/9 |
| A02 | Scheduler 计数与串行 | `ExtractionSchedulerTest`（7 例：单次/重复/全局串行/饱和/入队失败回滚/关闭后 no-op/幂等） | 7/7 |
| A03 | 异常隔离 | `ExtractionSchedulerTest` 失败/CE 用例 + `ExtractionSchedulerCloseTest` 共享失败/等待者用例；`extraction.run.failed`/`worker.stopped`/`enqueue.failed` 白名单日志 | 通过 |
| A04 | 关闭协议 | `ExtractionSchedulerCloseTest`（9 例：CLOSING 准入、在途取消+finally、共享失败、等待者取消、Runner 内拒绝、NEW 关闭、非关闭 CE、并发唯一清理等） | 9/9 |
| A05 | Count Trigger | `CountTriggerTest`（7 例：4/5 阈值、complete/failed 不计、跨 Session、reporter、CE、真实 IngestionService CREATED/DUPLICATE） | 7/7 |
| A06 | Idle Trigger/Cron | `IdleTriggerTest`（10）+ `IdleCronTest`（12）：created_at 等号、新旧状态、timestamp 无关、多维度、重叠 skip、失败/CE、极端 Duration/Instant、严格未来/不补跑、真实 skip、close join、持续失败退避/恢复 | 22/22 |
| A07 | Pipeline 与生命周期 | `ExtractionPipelineLifecycleTest`（15 例：公共零 pipeline、无 Runner、Count/Idle、idle=false、关闭取消+finally、释放顺序与失败继续、start/close 竞争、启动失败逆序清理、两类回调拒绝、关闭后 0 触发、并发 close、STOPPING 新 tick/排队项 0 Runner） | 15/15 |
| A08 | 全量回归与边界 | `./scripts/check` exit 0、1365=1365、81 基线、vfs diff 空、Core 无 Ktor、schema 探针 | 通过 |

说明：Worker B4 回执中的 A03/A04 标签按“Count/Idle”划分；本表按 CT09 任务书 A01～A08 原编号（A03=异常隔离、A04=关闭协议、A05=Count、A06=Idle/Cron），映射以任务书为准。

## 6. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | `./scripts/check` 约 2 分钟（130 tasks），clean build 成本 | 正常全量回归成本 |
| L2 | 14 模块总数为 1365（CT09 新增 69 例） | 与各批计数累加一致 |
| L3 | B4 仅证据，不改代码；CT09 最终 DONE 仍由 Architect 裁定 | 符合 §6.2 |
| L4 | 临时 schema 探针仅在 `.local`，不属于交付测试 | Lead 独立验证用，非仓库变更 |

## 7. 结论与下一步

- `./scripts/check` clean 全绿；14 模块 1365=1365；81 基线 all OK；vfs 无 diff；Core 无 Ktor；schema/表/索引/`user_version` 未变；A01～A08 均有正式测试证据。
- **Architect 独立复核通过：B4 APPROVED / DONE，CT09 DONE**；CT10+ 未启动，仍无 git 操作与发布。公共 Factory 不启用生产提取，待 CT11 提供 Runner。

### 证据路径

- `.local/validation/cm2/lead/ct09-b4/check.log`、`test-counts.txt`、`vfs-git.txt`、`vfs-baseline.txt`、`core-dependencies.log`、`lead-probe-results.xml`、`probe/`
- Worker 证据：`.local/validation/cm2/worker/ct09-b4/`
