# CT10 B3 · Lead 独立初验（集成证据与全量验收）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**B3 APPROVED / DONE；CT10 APPROVED / DONE（Architect 已裁定）**。
**依据**：[B3_委派单](B3_委派单.md)、[CT10 任务书](../../milestones/cm2/CT10_任务书.md) §0.1、[B2_Lead初验](B2_Lead初验.md)

**收口（2026-10-30）**：Architect 已复核 B3/CT10，状态回填 DONE；原初验证据保留。

## 0. 本次修改（2026-10-30，B3 初验记录）

Worker 完成 B3 只读验收；Lead 独立执行 `./scripts/check`、14 模块计数、VFS 基线/diff、Core 无 Ktor、schema 探针，并复核 A01～A08 映射。**全部通过**；B3 未改任何源码/测试/构建/文档。

## 1. B3 非侵入与终态 SHA

B3 仅新增 `.local/validation/cm2/worker/ct10-b3/` 证据；未改仓库代码/测试/构建/文档。关键终态 SHA（Lead 复算，与 B1/B2/Worker 一致）：

- `DefaultPreExtractionContextBuilder.kt` `a8fc94b9…c10853`
- `DefaultPreExtractionContextBuilderTest.kt` `f79e36fb…8a77f0`
- `ExtractionPrompt.kt` `efcf5158…d215e156`
- `extraction-v1.md` `56d3278f…1849b2c8`
- `ExtractionConfig.kt` `a0a3e01b…94b5f6ec`
- `ContextSchema.sq` `ec727a53…dd8ea3`
- CT09 Scheduler/Pipeline/IdleCron/IdleTrigger/CountTrigger/Impl/Factory SHA 未变。

## 2. Lead 独立全量回归

| 命令 | 结果 |
| --- | --- |
| `./scripts/check` | **exit 0**，`BUILD SUCCESSFUL in 2m`，**131 actionable tasks: 131 executed**（含 spotlessCheck） |
| 14 模块行首 `@Test` vs XML | **1397 = 1397**，差额 0、失败/错误/跳过 0 |

| 模块 | 源码 | XML | 模块 | 源码 | XML |
| --- | ---: | ---: | --- | ---: | ---: |
| common | 23 | 23 | integration-tests/vfs | 107 | 107 |
| context/api | 26 | 26 | vfs/api | 66 | 66 |
| context/core | 34 | 34 | vfs/core | 305 | 305 |
| context/persistence-sqldelight | 176 | 176 | vfs/git-backup | 73 | 73 |
| context/runtime | 275 | 275 | vfs/persistence | 52 | 52 |
| context/server | 30 | 30 | vfs/runtime | 61 | 61 |
| integration-tests/context | 15 | 15 | vfs/storage | 154 | 154 |
| **合计** | **1397** | **1397** | | | |

## 3. VFS、Core 与 Schema 边界

| 项 | 结果 |
| --- | --- |
| `git status --porcelain vfs integration-tests/vfs` | 0 行 |
| `git diff --stat -- vfs integration-tests/vfs` | 0 行 |
| 81 VFS 基线 | `Checked 81 files: all OK` |
| Core build 文本 ktor / main import / compileClasspath | 均无命中（ktor hits=0） |
| `CoreSkeletonTest` / `KtorBoundaryTest` | 含在 core 34 / runtime 275 全绿 |
| `ContextSchema.sq` | SHA `ec727a53…` 未变 |
| Schema 临时库探针（Lead） | `PRAGMA user_version=1`、6 表、5 索引，1/1 PASS |
| 构建/依赖文件 | B3 窗口未改 |

## 4. A01～A08 映射（全部 PASS）

| # | 验收 | 正式测试证据 | 结果 |
| --- | --- | --- | --- |
| A01 | 配置默认 32/1..1024/未知键/直接构造与 YAML 同校验 | `ExtractionConfigValidationTest`（10） | 10/10 |
| A02 | 锚点 latest pending（timestamp DESC/id DESC/同 timestamp/跨 Session/较新 complete） | `DefaultPreExtractionContextBuilderTest` 锚点用例 | PASS |
| A03 | 单 ReadContext 快照、并发补录不混入 | Builder 快照用例（`betweenReadsForTesting` + 独立写槽） | PASS |
| A04 | 连续窗口含 complete/failed History、不跳过中间、真实顺序、窗口外 Pending | Builder 连续窗口用例 | PASS |
| A05 | 完整请求估算（Prompt/ID/时间/Target + Schema + 输出 + 余量 + 等号） | Builder 3 例 + `ExtractionPromptTest` 9 + `ExtractionBudgetPolicyTest` 6 | 18/18 |
| A06 | 折半按实际 K、首次满足即停 | Builder `halving uses the actual read size …` | PASS |
| A07 | N=1 → SingleTurnOverBudget（不写 failed）；固定开销 → FixedOverheadOverBudget；无 Pending（0 估算）；无 LLM | Builder 4 例 + `ExtractionContextResultTest` 6 | 10/10 |
| A08 | 全量回归/边界 | check、1397=1397、81 基线、vfs diff、Core 无 Ktor、schema | PASS |

## 5. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | B3 为证据批次，未新增测试/代码 | 符合 D13/Task 6.2 |
| L2 | 14 模块总数 1397（CT10 B1+B2 新增 25 例） | 与各批计数累加一致（34/275） |
| L3 | CT10 未接 CT09 pipeline；真实 Runner 待 CT11 | 符合 D13；生产提取未启用 |
| L4 | Lead schema 探针位于 `.local`，不属于交付测试 | 独立验证用，非仓库变更 |

## 6. 结论与下一步

- `./scripts/check` clean 全绿；14 模块 1397=1397；81 基线 all OK；vfs 无 diff；Core 无 Ktor；schema/表/索引/`user_version` 未变；A01～A08 均有正式测试证据。
- **B3 Lead 初验通过，提交 Architect 裁定 CT10 是否 DONE**；CT11/CT12 未启动，仍无 git 操作与发布。

### 证据路径

- `.local/validation/cm2/lead/ct10-b3/check.log`、`test-counts.txt`、`vfs-git.txt`、`vfs-baseline.txt`、`core-dependencies.log`、`schema-probe-results.xml`、`schema-probe.log`
- Worker：`.local/validation/cm2/worker/ct10-b3/`
