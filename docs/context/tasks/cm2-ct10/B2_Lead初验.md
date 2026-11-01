# CT10 B2 · Lead 独立初验（Runtime Builder / 真实 SQLite 快照与折半）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**B2 Lead 初验通过，提交 Architect 独立复核；Architect 已放行 B3（B2 收口）**。
**依据**：[B2_委派单](B2_委派单.md)、[CT10 任务书](../../milestones/cm2/CT10_任务书.md) §0.1、[B1_Lead初验](B1_Lead初验.md)

## 0. 本次修改（2026-10-30，B2 初验记录）

Worker 完成 B2（仅新增 Builder 与其真实 SQLite 测试）；Lead 独立复算 SHA、跑三组命令、复核锚点/连续窗口/折半/单 Turn/固定开销/快照/配置 fail-fast，并重复运行 Builder 测试 3 次。**全部通过**。

## 1. 交付面与边界

本批仅新增 2 文件；B1/CT09 文件 SHA 全部未变：

| 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- |
| `runtime/.../extraction/DefaultPreExtractionContextBuilder.kt` | `a8fc94b965b33ed0d280e632562a3cb106bf7ef3d0b45f63bb14888e52c10853` |
| `runtime/src/test/.../DefaultPreExtractionContextBuilderTest.kt` | `f79e36fb96a99ed54a74a3e49748ec2390cc365d13ccfc7bccb62b2fa58a77f0` |

- 未变：Core 四文件/两资源/两测试、`ExtractionBudgetPolicy 5253be34…`、`ExtractionConfig a0a3e01b…`、`ContextConfigLoader 961ca51f…`、CT09 Scheduler/Pipeline/Impl/Factory 等。
- `spotlessApply` 后 2 SHA 不变；未改 SQL/索引/`user_version`/构建/依赖/vfs/common/persistence/server；无 git。

## 2. Lead 独立实跑与计数

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `DefaultPreExtractionContextBuilderTest` 单独重复 3 次 | 3/3 全绿（每次 10/10） |
| 过滤测试后恢复正式 runtime | BUILD SUCCESSFUL |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 34 | 34 | 0 | 0/0/0 |
| runtime | 275 | 275 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

runtime 265 → 275（+10）。

## 3. Builder 契约核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 构造期 fail-fast：缺 EXTRACTION Task / Profile / PROMPT_ONLY | `init` 校验；测试 3 例（路径、无 Key/URL） | ✔ |
| 构造期不访问 DB/LLM；schema 资源只读一次 | 源码；fake DB 未触发 | ✔ |
| 整个 build 在一个 `database.read` 回调内 | 源码；`betweenReadsForTesting` 在回调内 | ✔ |
| `latestPending` 空 → `NoPending`，不估算 | 测试 estimator 0 调用 | ✔ |
| 锚点按 `timestamp DESC,id DESC`；同 timestamp 大 id；跨 Session；较新 complete 不抢 | 测试 | ✔ |
| 连续窗口含中间 complete/failed、真实顺序；窗口外旧 Pending 不处理 | 测试 | ✔ |
| 基础请求（空 Turn + System/Schema/输出/余量）超限 → `FixedOverheadOverBudget`，不读窗口 | 测试 | ✔ |
| 每个 N 完整请求估算；Fits → `Built`；N=1 超 → `SingleTurnOverBudget(anchorId, requiredTotal)` | 测试；DB 仍 PENDING、不写 failed | ✔ |
| 折半按实际读取 K（`max(1,K/2)`），首次满足即停 | 测试观察序列 `[0,8,4]` | ✔ |
| 预算等号视为 Fits | 测试精确等号 | ✔ |
| JSON_SCHEMA 携带 v1 schema；JSON_OBJECT 不发送 | 测试 | ✔ |
| 同一快照：并发补录不混入（折半也不混入） | `betweenReadsForTesting` + 独立写槽测试；重复 3 次稳定 | ✔ |
| 不写库/不改 status/不调 LLM | 源码 + 测试 | ✔ |

## 4. 测试覆盖（10 例）

无 Pending（0 估算）、锚点/同 timestamp/跨 Session/较新 complete、连续窗口/History/窗口外 Pending、实际 K 折半首次满足、单 Turn 超预算不写状态、固定开销不读窗口、并发补录快照隔离、预算等号、JSON_SCHEMA/JSON_OBJECT、构造 fail-fast 三例。

## 5. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | 构造期“Task 引用缺失 Profile”的 Builder 分支在经校验的 `LlmConfig` 下不可达；测试改为断言配置层同路径失败 | 防御性代码；LlmConfig 已先行校验，非阻断 |
| L2 | 该防御分支消息含 Profile 名（`...: <name>`）；D10 要求消息只含路径 | 不可达路径；建议后续统一为仅路径，非本批阻断 |
| L3 | `betweenReadsForTesting` 为 internal 测试接缝，生产 null | 与既有 `*ForTesting` 同模式 |
| L4 | 基础请求使用空 user 信封参与协议开销估算 | 符合 D5“空 Turn 信封/协议开销” |
| L5 | Builder 每次 build 重新读取 Prompt 资源（通过 `ExtractionPrompt.messages`） | 调用期成本可忽略；非阻断 |

## 6. 结论与下一步

- B2 的构造 fail-fast、单 ReadContext 快照、锚点/连续窗口/History、实际 K 折半、单 Turn/固定开销/无 Pending、预算等号、JSON 模式均按 D1～D13 落实；10 例真实 SQLite 测试 + 3 次重复全绿；四模块 34/275/176/30 差额 0。
- **B2 Lead 初验通过，提交 Architect 独立复核**；B3 在复核通过前不启动，CT11/CT12 不启动。

### 证据路径

- `.local/validation/cm2/lead/ct10-b2/spotlessApply.log`、`core-runtime-test.log`、`core-runtime-test-restored.log`、`persistence-server-test.log`
- `.local/validation/cm2/lead/ct10-b2/builder-run-1..3.log`、`counts.txt`
- Worker：`.local/validation/cm2/worker/ct10-b2/`
