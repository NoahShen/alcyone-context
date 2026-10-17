# T33 Lead 初验与技术审查记录（复核修复后重新初验）

## 本次修改（2026-10-16，Architect 证据口径订正）

- Lead自测/初验原记录保留；Architect独立确认常规源码/XML为764/764，原808包含另组WebDAV43例及注释1处，不是常规源码数。本轮WebDAV未重跑。
- 实际依赖搜索边界经javap确认：100000次迭代及超过起算年份100年的退出分支；详细证据和T33 DONE结论见 [复核第6节](T33_架构与技术复核.md#6-修复后独立收口复核2026-10-16)。这些补证归属Architect，不改称Lead实跑。

## 本次修改（2026-10-16，落实复核修复 R1～R3 重新初验）

- 针对 Architect 首次独立复核指出的 R1～R3 及初验收敛项（星期步长、远期跨世纪闰日等）进行全面代码复审与独立跑测验证；
- 状态保持 **IN_REVIEW**，提供完备的新旧计数、XML/源码对照、官方选型与搜索终止依据及独立实跑原始日志；涉及全文。

## 1. 审查与验收结论

- **任务状态**: **IN_REVIEW**（复核修复初验通过，A01～A08 全部实质达成）
- **实现 Worker**: `c1d7c118-4bc9-41f5-93ab-e41b09259d73` (pi/Monk-olkey/monk-fast, high)
- **审查结论**:
  - **包名统一**: 全量统一为 `com.github.noahshen.alcyone.context.vfs.gitbackup`。
  - **R1（配置完整性与防穿透）**: 补齐 `localCommandTimeout` (30s)、`pushTimeout` (120s)、`shutdownGracePeriod` (10s) 及其正值校验；公开 `eventRoots` 与筛选器持有集合均经 `Collections.unmodifiableSet(eventRoots.toHashSet())` 防御性拷贝，测试断言外部 `clear()` 无法穿透破坏状态。
  - **R2（Cron 方言与可达性收敛）**: `cron-utils` 原生支持 UNIX 方言但宽容接受星期 7 为周日；本模块在 `CronBackupSchedule` 中适配收紧为严格 0-6 星期基数契约，明确拒绝基数 7（含 `7`, `1-7`, `0,7`, `7/2` 等），同时精确放行合法的步长语法（如 `*/7`, `1-5/2`，步长限制为 1..7 正整数）；严格禁止英文字母；构造期通过 `executionTime.nextExecution(now).isPresent` 实现有界拒识不可达日期（如 2月31日 `0 3 31 2 *`）；针对合法远期闰日（2096-03 起算跨非闰 2100 年至 2104-02-29）断言其正确命中，证实有界搜索算法稳定。
  - **R3（测试断言与证据闭环）**: 新模块测试用例由原 6 例扩充至 12 例实质断言，覆盖上述全部边界；交付与复核证据严格分离，全套源码与 XML 计数精确对齐，既有模块生产代码 Diff 严格为 0。

## 2. A01～A08 逐项实质核对

| 编号 | 验收标准 | 核对结果 | 详细依据 |
| --- | --- | --- | --- |
| A01 | 模块依赖边界 | PASS | `vfs/git-backup/build.gradle.kts` 仅引入 `:vfs:api`、`coroutines`、`slf4j-api` 与 `cron-utils:9.2.1`；既有模块及 Runtime 依赖 Diff 为 0。 |
| A02 | 配置默认值与非法校验 | PASS | `GitBackupConfig` 默认 60s 防抖、`0 3 * * *`、30s 本地超时、120s 推送超时、10s 关闭等待；无 dailyAt/zoneId/GitBackupStatus；非法参数/非正时长/空 roots 均抛 `IllegalArgumentException`；构造时不触发 I/O。 |
| A03 | 五字段 Cron 解析与契约 | PASS | 严格限制 5 字段；拒识 Quartz 字符（`?`, `L`, `W`, `#`）与英文字母；收紧星期基数为 0-6（拒识 7），支持 1..7 步长（支持 `*/7`）；构造期拦截无未来不可达日期；覆盖范围/步长/列表及 DOM/DOW OR 契约双分支判定。 |
| A04 | 事件筛选与段边界 | PASS | `GitBackupEventFilter` 忽略 `METADATA_UPDATED`；覆盖移动事件源/目标、删除事件（`FILE_DELETED`, `DIRECTORY_DELETED`）；已解码路径段严格段边界匹配（`/notes` 不匹配 `/notes-archive`）；支持百分号编码段比对与子 Mount 保守触发。 |
| A05 | 本机时间与受控 Clock | PASS | 不暴露外部时区配置，使用本地 Zone；支持传入受控 `Clock` 准确推导下次时刻。 |
| A06 | 零侵入与零越界 | PASS | 既有 6 生产模块源码及 Runtime build.gradle.kts 差异严格为 0；无 GitWorker、无空壳 Plugin。 |
| A07 | 格式化与跑测回归 | PASS | `spotlessApply` (exit 0)，`:vfs:git-backup:test` (exit 0, 12/12 passed)，`./scripts/check` (exit 0, 75 actionable tasks 全绿)。 |
| A08 | 规范交付与状态流转 | PASS | 交付物完备，选型官方依据及远期搜索终止说明齐全，自测与初验日志分离，状态置为 **IN_REVIEW**。 |

## 3. Lead 独立实跑记录与证据归档

- **Lead 独立实跑日志目录**: `.local/validation/t33-lead/`
- **命令 1**: `./scripts/dev gradle spotlessApply`
  - 退出码: `0`
  - 结果: 33 tasks up-to-date
- **命令 2**: `./scripts/dev gradle :vfs:git-backup:cleanTest :vfs:git-backup:test`
  - 退出码: `0`
  - 结果: 12 tests executed, 12 passed, 0 failed
  - 报告路径: `vfs/git-backup/build/test-results/test/TEST-com.github.noahshen.alcyone.context.vfs.gitbackup.GitBackupTest.xml`
- **命令 3**: `./scripts/check > .local/validation/t33-lead/check.log 2>&1`
  - 退出码: `0`
  - 结果: BUILD SUCCESSFUL in 1m (75 actionable tasks executed)
  - 原始日志: `.local/validation/t33-lead/check.log`

## 4. 全套源码与 XML 测试计数核对

- **模块 XML 测试用例数**: 12 例（全部 passed，0 failures，0 errors，0 skipped）
- **全工程 XML 测试用例总数**: **764** 例（原首版基线 752 + 首次复核 6 例 + 本轮扩充 6 例 = 764 例）
- **全工程 XML 状态统计**: `Failures=0, Errors=0, Skipped=0`
- **常规源码 `@Test` 用例数**: **764**（其中插件12），与常规XML764相符。原Lead文本搜索808包含WebDAV43及注释1处；此口径由Architect独立核实订正。

## 5. 选型与远期搜索有界性说明

1. **库选型**: `com.cronutils:cron-utils:9.2.1` (Apache License 2.0)。
2. **方言收敛**: 原生 UNIX 模式下容忍星期 7 为周日，本模块通过 `CronBackupSchedule` 前置校验适配收紧为严格 0-6 星期基数契约，并允许 1..7 的合法步长；严格过滤字母。
3. **有界终止与远期闰日**:
   - cron-utils 的 `ExecutionTime.nextExecution(ZonedDateTime)` 采用基于字段增量推演的有界算法：针对非法日期（如 2月31日）因在日历年中无法找到匹配项，在有限范围内推算无解后直接返回 `Optional.empty()`，因此在构造期可安全判定无未来匹配；
   - 针对跨非闰世纪的远期闰日（如从 2096-03 起算 `0 3 29 2 *`），经实跑验证可稳定跨越平年 2100 年准确推算至 2104-02-29 03:00 UTC，无死循环且不会误判为不可达。
