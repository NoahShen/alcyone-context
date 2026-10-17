# T33 插件模块配置与事件筛选开发及自测记录

## 本次修改（2026-10-16，落实复核修复 R1～R3 及初验收敛）

- 统一包名为设计规范的 `com.github.noahshen.alcyone.context.vfs.gitbackup` 并迁移目录。
- **R1（配置契约与防穿透）**:
  - `GitBackupConfig` 补齐 `localCommandTimeout`（默认 30s）、`pushTimeout`（默认 120s）、`shutdownGracePeriod`（默认 10s）及正时长校验。
  - `GitBackupConfig` 与 `GitBackupEventFilter` 实现 `eventRoots` 防御性拷贝（`Collections.unmodifiableSet(eventRoots.toHashSet())`），测试验证外部可变集合 `clear()` 后内部状态不受影响。
- **R2（Cron 星期与可达性契约）**:
  - `CronBackupSchedule` 星期契约适配：`cron-utils` 原生在 UNIX 模式下同时接受 0 和 7 作为周日。为了满足设计严谨性与确定性，本模块在 `CronBackupSchedule` 中进行校验适配，将星期基数严格收紧为 0-6（0=周日，6=周六），明确拒绝星期基数 7（如 `"7"`, `"1-7"`, `"0,7"`, `"7/2"`）。
  - 同时支持合法星期步长（1..7，如 `"*/7"`, `"1-5/2"`），避免将步长数字 7 误当作星期基数拒绝。
  - 严格拒绝英文字母（如 `"0 3 * JAN *"`, `"0 3 * * MON"`）。
  - 构造期引入有界未来可达性校验（`executionTime.nextExecution(now).isPresent`），构造即拒绝如 2月31日 `"0 3 31 2 *"` 等不可达表达式。
  - 远期闰日有界验证：cron-utils 支持针对合法远期闰日（如 2096-03 至 2104-02-29 跨越非闰世纪 2100）的正确搜索与终止，不会无限扫描，精确计算出 2104-02-29。
- **R3（测试断言补齐）**:
  - 单元测试增至 12 个，完整覆盖超时默认值/校验、可变别名防穿透、星期 7 基数拒绝与 `*/7` 步长支持、跨非闰世纪远期闰日（2104-02-29）、不可达日期构造拦截、DOM/DOW OR 契约触发判定、事件删除操作（`FILE_DELETED`, `DIRECTORY_DELETED`）、百分号编码路径匹配与子 Mount 保守触发。

## 1. 交付目标与选型

- **模块名称**: `:vfs:git-backup`
- **Cron 解析库选型**:
  - 依赖: `com.cronutils:cron-utils:9.2.1` (https://github.com/jmrozanec/cron-utils)
  - 许可: Apache License 2.0
  - 依据: 成熟主流的五字段 Cron 解析与调度库；原生支持 `CronType.UNIX` 标准方言，DOM/DOW 任一匹配（OR 逻辑）契约；由 `CronBackupSchedule` 适配收紧 0-6 星期与有界未来可达性。
- **修改范围**:
  - `gradle/libs.versions.toml`: 新增 `cron-utils = "9.2.1"` 及其库坐标。
  - `settings.gradle.kts`: 末尾 include 新增 `":vfs:git-backup"`。
  - `vfs/git-backup/build.gradle.kts`: 新模块构建脚本，仅引入 `:vfs:api`、coroutines、slf4j-api 和 cron-utils。
  - `vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitBackupConfig.kt`: 公开配置类。
  - `vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/CronBackupSchedule.kt`: 内部 5 字段 Cron 调度器。
  - `vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitBackupEventFilter.kt`: 内部事件筛选器。
  - `vfs/git-backup/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitBackupTest.kt`: 单元测试。

## 2. 交付文件列表

- `gradle/libs.versions.toml`
- `settings.gradle.kts`
- `vfs/git-backup/build.gradle.kts`
- `vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitBackupConfig.kt`
- `vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/CronBackupSchedule.kt`
- `vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitBackupEventFilter.kt`
- `vfs/git-backup/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitBackupTest.kt`
- `docs/tasks/e1-t33/DELIVERY_T33.md` (本文档)

## 3. 测试与验证记录

1. **Spotless 格式化**:
   - 命令: `./scripts/dev gradle spotlessApply`
   - 退出码: `0` (BUILD SUCCESSFUL)

2. **新模块单元测试**:
   - 命令: `./scripts/dev gradle :vfs:git-backup:cleanTest :vfs:git-backup:test`
   - 退出码: `0` (BUILD SUCCESSFUL)
   - 测试用例数: 12 / 12 全部通过（原 6 个 -> 复核 11 个 -> 初验修复收敛 12 个）
   - XML 报告路径: `vfs/git-backup/build/test-results/test/TEST-com.github.noahshen.alcyone.context.vfs.gitbackup.GitBackupTest.xml`
   - 测试用例明细:
     - `test CronBackupSchedule English letters rejected()`
     - `test CronBackupSchedule DOM and DOW OR contract()`
     - `test CronBackupSchedule range step list and day of week()`（含 `*/7` 与 `1-5/2`）
     - `test CronBackupSchedule unreachable expressions rejected at init()`
     - `test CronBackupSchedule leap day across non-leap century()`（2096-03 到 2104-02-29）
     - `test GitBackupEventFilter matching rules and edge cases()`
     - `test CronBackupSchedule rejects 6 fields and Quartz chars()`
     - `test GitBackupConfig defensive copy prevents mutable alias penetration()`
     - `test CronBackupSchedule nextExecution with controlled clock()`
     - `test valid GitBackupConfig creation and defaults()`
     - `test GitBackupConfig invalid arguments()`
     - `test CronBackupSchedule day of week 7 base rejected()`

3. **全工程回归**:
   - 命令: `./scripts/check`
   - 退出码: `0` (BUILD SUCCESSFUL in 58s, 75 actionable tasks executed)
   - 既有 6 个模块生产代码 Diff 为 0，零回归破坏。
