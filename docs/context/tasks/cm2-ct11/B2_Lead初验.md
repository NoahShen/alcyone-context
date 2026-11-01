# CT11 B2 · Lead 独立初验（Runtime ExtractionRunner）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**B2 Lead 初验通过，提交 Architect 独立复核**；B3 在复核通过前不启动。
**依据**：[B2_委派单](B2_委派单.md)、[CT11 任务书](../../milestones/cm2/CT11_任务书.md) §0.1、[B1_Lead初验](B1_Lead初验.md)

## 0. 本次修改（2026-10-30，B2 初验记录）

Worker 完成 B2（Builder 仅新增 internal `requestFor`、Runner、Runner 测试）；Lead 独立复算 SHA、跑三组命令、重复 Runner 测试 3 次，并核对窗口一致性/单 Turn/错误分类/唤醒/防御上限。**全部通过**。

## 1. 交付面与边界

| 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- |
| `DefaultPreExtractionContextBuilder.kt`（仅 `requestFor` + 共享 request） | `6c16e5fb16cd10ae6fad32780ae1a5372a1cc9a17a3607fefd0d443051acac0f` |
| `DefaultExtractionRunner.kt`（internal） | `0ba9552b881572989510760a58beb438838b54d7dcf240405fb738dcc05e6766` |
| `DefaultExtractionRunnerTest.kt` | `42b3f6f1ce6543161eff6592797aa89433baaf33378fff9a750d154dc8791a71` |

- `DefaultPreExtractionContextBuilderTest.kt` `f79e36fb…` **未改**；B1 Validator/Committer/JSON、CT10 Config/Prompt/BudgetPolicy、CT09 Scheduler/Pipeline/Impl/Factory、Schema `ec727a53…` 未变。
- `build()` 仅将内部请求构造抽为共享 `request(...)`，既有 Builder 测试与全量运行通过，语义未变；`spotlessApply` 后 3 SHA 不变；无 git。

## 2. Lead 独立实跑与计数

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `DefaultExtractionRunnerTest` 单独重复 3 次 | 3/3 全绿（每次 12/12） |
| 过滤测试后恢复正式 runtime | BUILD SUCCESSFUL |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 43 | 43 | 0 | 0/0/0 |
| runtime | 300 | 300 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

runtime 288→300（+12）。

## 3. Runner 契约核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| `requestFor(context)` 复用同一 task/profile/schema/Prompt；`build()` 语义未改 | 源码 + BuilderTest 未改且全绿 + 窗口一致性用例 | ✔ |
| Built：LLM（事务外）→ Validator → Committer；窗口与发送请求一致 | Runner 用例（system/user/schema/format/顺序/target） | ✔ |
| 空 memories：Target complete、0 Raw/Pending、不唤醒 | 用例 | ✔ |
| 非空：Raw/Pending/Target/来源正确、唤醒一次 | 用例 | ✔ |
| 非法 JSON/结构/来源 → `INVALID_RESPONSE`、0 写、pending | 用例 | ✔ |
| LLM `LlmException` 原样、0 写、pending | 用例（`assertSame`） | ✔ |
| SingleTurn：markFailed 行数 1 后继续下一条；行数 0 → `ExtractionCommitException`、0 Raw | 用例 | ✔ |
| FixedOverhead → `ContextConfigException(INVALID_CONFIG)`、0 写 | 用例 | ✔ |
| 唤醒失败：提交成功、Raw/Pending 落库、不重试、脱敏 WARN | 用例 | ✔ |
| 超 `maxWindowIterations` 抛技术错误、无无限循环 | 用例（3 次即停） | ✔ |
| CE 传播、NoPending 0 LLM/写/唤醒 | 用例 | ✔ |
| 未新增 Core OrganizationNotifier Port | 源码审查；`OrganizationWakeup` internal | ✔ |

## 4. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | Builder 文件因抽出 `request(...)` 而 SHA 变化；`build()` 行为由既有测试与全量回归覆盖 | 非语义变更 |
| L2 | `ExtractionExecutionException`/`ExtractionCommitException` 由 Scheduler 按普通失败消费；B3 接线时确认日志/错误分类 | 符合 D10 |
| L3 | `OrganizationWakeup` 默认 no-op；B3/CT13 接真实消费 | 符合 D2 |
| L4 | `maxWindowIterations` 由 B3 以 `initialWindowTurns` 或等价有界值注入 | B3 接线项 |
| L5 | SingleTurn 的 markFailed 为独立短事务；若进程在标记后崩溃，下轮 Trigger 按 DB 状态继续 | 设计允许；非补偿 |

## 5. 结论与下一步

- B2 的 Runner 循环、窗口一致性、单 Turn、错误分类、原子提交、唤醒失败/上限/CE 均按 D4/D5/D9～D13 落实；12 例 Runner 测试重复 3 次全绿；四模块 43/300/176/30 差额 0。
- **B2 Lead 初验通过，提交 Architect 独立复核**；B3 在复核通过前不启动，CT12/CT13 不启动。

### 证据路径

- `.local/validation/cm2/lead/ct11-b2/spotlessApply.log`、`core-runtime-test.log`、`core-runtime-test-restored.log`、`persistence-server-test.log`
- `.local/validation/cm2/lead/ct11-b2/runner-run-1..3.log`、`counts.txt`
- Worker：`.local/validation/cm2/worker/ct11-b2/`
