# CT13 B2 · Lead 独立初验（整体验收与 A01～A08 映射）

**日期**：2026-11-01
**初验人**：CM3 Lead（只读初验）
**结论**：**B2 APPROVED / DONE；CT13 APPROVED / DONE（2026-11-01）；CT14 已放行，CM4 不启动。**
**依据**：[架构与技术复核 §2](架构与技术复核.md)、[B2 委派单](B2_委派单.md)、[B1_R1_Lead初验](B1_R1_Lead初验.md)

## 0. 本次修改（2026-11-01，B2 初验记录）

B2 为只读验收；Worker 未改仓库文件，仅写 `.local/validation/cm3/worker/ct13-b2/`。Lead 独立重跑全量、14 模块计数、81 基线、VFS/Core/Schema 边界，并核对 A01～A08 方法→断言→XML。

## 1. 独立全量

| 命令 | 结果 |
| --- | --- |
| `./scripts/check` | **exit 0**，`BUILD SUCCESSFUL in 2m 7s`，131 tasks |
| 14 模块行首 `@Test` vs XML | **1498 = 1498**，差额 0，失败/错误/跳过 0 |

| 模块 | 源码 | XML | 模块 | 源码 | XML |
| --- | ---: | ---: | --- | ---: | ---: |
| common | 23 | 23 | integration-tests/vfs | 107 | 107 |
| context/api | 26 | 26 | vfs/api | 66 | 66 |
| context/core | 59 | 59 | vfs/core | 305 | 305 |
| persistence | 176 | 176 | vfs/git-backup | 73 | 73 |
| **context/runtime** | **348** | **348** | vfs/persistence | 52 | 52 |
| context/server | 30 | 30 | vfs/runtime | 61 | 61 |
| integration-context | 18 | 18 | vfs/storage | 154 | 154 |
| **合计** | **1498** | **1498** | | | |

B2 前后均为 1498；无新增/删除测试。

## 2. 边界

| 项 | Lead 独立结果 |
| --- | --- |
| 81 VFS 基线 | `Checked 81 files: all OK` |
| `vfs`/`integration-tests/vfs` status/diff | 空 |
| Core 无 Ktor | build/import 无命中；全量 `KtorBoundaryTest` 通过 |
| `ContextSchema.sq` | `ec727a53…` 未变 |
| Schema 探针 | Worker 本轮 `user_version=1`、6 表、5 索引（`.local/.../worker/ct13-b2/schema-evidence.txt`，归属 Worker）；Lead 以 Schema SHA + 全量 Schema 测试复核 |
| 8 个生产/资源 SHA | 与 B1 终态逐字一致（Lead 复算） |
| 仓库改动 | B2 未改任何仓库文件；仅有 B1/CM3 既有新增与文档修改 |

## 3. A01～A08 映射（Lead 核对）

| # | 关键测试方法 | 断言 | XML |
| --- | --- | --- | --- |
| A01 | `an empty snapshot returns no candidates without calling the llm` | 空快照返回空；0 LLM | LlmCandidateRetrieverTest 28/28 |
| A02 | `multiple batches scan every candidate exactly once`；`more than one page is read completely`；`an exact page multiple is read completely` | 6 条/3 批全扫描；>1 页；整页倍数；无遗漏/重复 | 同上 |
| A03 | `a real id from another batch...`；`a forged tag...`；`unknown and blank ids...`；`duplicate ids...` | 跨批真实 ID → INVALID_RESPONSE、第 3 批 0；伪造标签不授权；空白/未知拒绝；重复去重 | 同上；Parser 4/4、Validator 6/6 |
| A04 | `invalid json in the second batch...`；`a truncated second batch...`；`a failing batch...`；`cancellation between batches...`；`cancellation inside the budget loop...` | 第二批失败/截断/普通异常/取消 → 无部分、后续批 0；预算循环取消 0 LLM | 同上 |
| A05 | `no domain or source filtering...`；`a concurrent insert between pages...`；`candidates keep the snapshot version...` | 不同 Domain/真实 Source 均进入请求；分页并发不混入；快照版本不重查 | 同上 |
| A06 | `fixed overhead...`；`a single candidate...`；`an oversized later candidate...`；`the exact budget boundary...` | 固定/首条/后续超大显式失败；等号通过；不返回部分 | 同上；Planner 9/9 |
| A07 | `a non zero schema estimate...`；`output reserve and safety margin...`；`schema is only sent...`；`the llm callback can write...` | Schema 仅 JSON_SCHEMA；输出保留+margin 改变边界；ReadContext 已释放 | 同上 |
| A08 | 全量、81、Core/Schema、Prompt 10/10、未接生产 | 1498=1498、all OK、无 Ktor、Schema 未变、Prompt 正式测试 | `./scripts/check` + Prompt XML |

- 关键 A01～A07 均指向真实 SQLite + Fake LLM 的 `LlmCandidateRetrieverTest`；`TokenBatchPlannerTest` 仅作纯逻辑补充，不替代集成覆盖。
- Worker 的完整逐条映射见 `.local/validation/cm3/worker/ct13-b2/A01-A08-mapping.md`；Lead 已抽查方法与 XML 结果一致。

## 4. 证据归属

| 归属 | 证据 |
| --- | --- |
| **Lead 本轮** | `.local/validation/cm3/lead/ct13-b2/check.log`、`test-counts.txt`、`vfs-baseline.txt`、`vfs-git.txt`、`core-ktor.txt`、`schema-sha.txt`、`prod-sha.txt` |
| **Worker 本轮** | `.local/validation/cm3/worker/ct13-b2/`（SUMMARY、A01-A08-mapping、命令日志、计数、Schema 探针、SHA） |
| **Architect 既有** | `.local/validation/cm3/architect/ct13-b1-r1/core-runtime.log`（仅引用，不冒充本轮） |
| **Lead 既有** | `.local/validation/cm3/lead/ct13-b1-r1b/`（仅引用） |

## 5. 非阻断观察

| # | 项 | 说明 |
| --- | --- | --- |
| L1 | Schema `user_version/6 表/5 索引` 的临时库探针由 Worker 本轮执行 | Lead 以 Schema SHA + 全量 Schema 测试复核；归属已标注，不冒充 Lead 重跑 |
| L2 | B2 无仓库改动 | `git status` 仅显示 B1/CM3 既有新增与文档修改；B2 证据全在 `.local` |
| L3 | 未接生产 | 8 个生产/资源 SHA 与 B1 一致；Factory/Impl/Config/Loader 未引用 Organization 组件 |

## 6. 结论

- B2 全量/边界/A01～A08 映射通过；1498=1498；81 基线 all OK；Core/Schema 未变；生产/资源零改动；无阻断项。
- **提交 Architect 裁定 CT13 是否 DONE**；CT13 仍未 DONE，CT14 未放行，CM4 未启动。
