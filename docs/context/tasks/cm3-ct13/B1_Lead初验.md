# CT13 B1 · Lead 独立初验（通用 Scanner/Candidate Retriever）

**日期**：2026-11-01
**初验人**：CM3 Lead（只读初验，不写生产代码）
**结论**：**B1 Lead 初验通过，提交 Architect 裁定；CT13 未 DONE，CT14 未放行。**
**依据**：[B1 委派单](B1_委派单.md)、[Architect 裁定](Architect_裁定.md)、[CT13 任务书](../../milestones/cm3/CT13_任务书.md)、[CT13 方案核对](方案核对.md)

## 0. 本次修改（2026-11-01，B1 初验记录）

Worker 完成 CT13 B1：13 个白名单文件、48 例新增测试；Lead 独立复算 SHA、检查源码/测试、实跑定向与全量、复核边界。**未发现阻断项**；B1 未接生产、无 git。

## 1. 交付面与 SHA

| 文件 | SHA-256（Lead 复算） |
| --- | --- |
| Core `OrganizationCandidateRetriever.kt` | `d23f0494…c4b5f3` |
| Core `CandidateIdValidator.kt` | `2135b3ec…26f2c` |
| Core `OrganizationCandidatePrompt.kt` | `ed03343b…246144` |
| `organization-candidate-v1.md` | `da45adb1…63eda1` |
| `organization-candidate-schema-v1.json` | `2ef03476…b3d672f` |
| Runtime `LlmCandidateRetriever.kt` | `60f12774…e9ce9b` |
| Runtime `JsonCandidateResponseParser.kt` | `cbe5abf7…8570e38` |
| Runtime `TokenBatchPlanner.kt` | `30bbbb72…f043fc` |
| Core `CandidateIdValidatorTest` | `6e919f6d…e73bd060` |
| Core `OrganizationCandidatePromptTest` | `b65fcb36…bc9f2ea` |
| Runtime `JsonCandidateResponseParserTest` | `b0e3b530…c2f86a4` |
| Runtime `TokenBatchPlannerTest` | `fe399bb1…8d0f690` |
| Runtime `LlmCandidateRetrieverTest` | `872ae0ff…2868f4` |

- `git status` 仅显示上述新增目录/文件与 CM3 文档；**既有生产文件 diff 为空**（Impl/Factory/Config/Loader/CM2 组件/SQL/Schema/VFS/common/构建均未改）。
- 未新增依赖；`LlmCandidateRetriever` 未接入 Factory/Impl；无公开 API/`ContextStatus` 变更。

## 2. 独立全量回归

| 命令 | 结果 |
| --- | --- |
| `./scripts/check` | **exit 0**，`BUILD SUCCESSFUL in 2m 7s`，**131 actionable tasks: 131 executed** |
| 14 模块行首 `@Test` vs XML | **1489 = 1489**，差额 0，失败/错误/跳过 0 |

| 模块 | 源码 | XML | 模块 | 源码 | XML |
| --- | ---: | ---: | --- | ---: | ---: |
| common | 23 | 23 | integration-tests/vfs | 107 | 107 |
| context/api | 26 | 26 | vfs/api | 66 | 66 |
| **context/core** | **59** | **59** | vfs/core | 305 | 305 |
| context/persistence-sqldelight | 176 | 176 | vfs/git-backup | 73 | 73 |
| **context/runtime** | **339** | **339** | vfs/persistence | 52 | 52 |
| context/server | 30 | 30 | vfs/runtime | 61 | 61 |
| integration-tests/context | 18 | 18 | vfs/storage | 154 | 154 |
| **合计** | **1489** | **1489** | | | |

新增 48（core +16、runtime +32），与 Worker 回执一致。

## 3. 定向重复与关键探针

- `LlmCandidateRetrieverTest` + `TokenBatchPlannerTest` 连续 3 次 `--rerun-tasks` 均 exit 0（9s/7s/7s），无竞态复现。
- 独立核对：
  - 空快照 0 LLM；单批/多批全覆盖；>1 页与整页倍数。
  - 分页间并发写不混入本次快照，下一次 search 可见；LLM 回调内独立 `database.write` 成功，证明 ReadContext 已释放。
  - 候选返回原快照对象（内容/更新时间不被 LLM 期间更新污染）。
  - 未知/空白/跨批/非字符串 ID 整条失败；重复稳定去重；中间批失败/截断/取消无部分返回、后续批不再调用。
  - 固定开销/首条/换批后超大项显式 `LOCAL_BUDGET_EXCEEDED`；非法估算 `INVALID_REQUEST`；等号边界通过；Schema 仅 `JSON_SCHEMA`；`maxOutputTokens` 等于 Task。
  - 构造期缺 Task/Profile/`PROMPT_ONLY` → `INVALID_CONFIG`，且不触 DB/LLM；错误消息无 Key/URL。
  - Prompt/Schema 正式测试覆盖高召回、补充/矛盾/纠错、数据非指令、批内 ID 权限、`additionalProperties=false`。

## 4. 边界

| 项 | 结果 |
| --- | --- |
| VFS 81 基线 | `Checked 81 files: all OK` |
| `vfs`/`integration-tests/vfs` status/diff | 空 |
| Core 无 Ktor | build/import 无命中 |
| `ContextSchema.sq` | `ec727a53…` 未变；schema 测试随全量通过 |
| 生产接线 | 未接 Factory/Impl；无公开 API/配置变更 |
| 依赖/Schema | 无新增依赖/表/索引/`user_version` |

## 5. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | `OrganizationCandidatePrompt` 仅向 Scanner 渲染 id/content，未带 domains/时间 | 设计 §6.4 最低要求为 id/content；候选授权由程序 ID 集合保证，不阻断 |
| L2 | `TokenBatchPlanner` 是 Runtime internal 通用组件，当前只被 Candidate Retriever 使用 | 符合 Architect “允许通用预算分批组件”授权；未提前实现 Retrieval/Governance |
| L3 | `readSnapshot` 用 `offset.toInt()` 前已在上一轮递增后做 `Int.MAX` 受检；实际分页不会越界 | 静态核对无实际缺陷；保留现有受检逻辑 |
| L4 | Prompt 正文中的伪造 `<candidate>` 标签原样进入 user 内容，但程序只接受批内真实 ID | 符合“正文含分隔符仍保留程序级权限边界”；Prompt 测试已覆盖 |

## 6. 结论与下一步

- B1 白名单、生产零改动、48 例新增、全量 1489=1489、81 基线/Core/Schema 边界均通过；无阻断项。
- **提交 Architect 复核 CT13 B1**；CT13 未 DONE，CT14 未放行。
- 证据：
  - `.local/validation/cm3/lead/ct13-b1/check.log`、`test-counts.txt`、`repeat-1..3.log`、`vfs-baseline.txt`、`vfs-git.txt`、`core-ktor.txt`、`schema-sha.txt`、`prod-diff.txt`
  - Worker：`.local/validation/cm3/worker/ct13-b1/`
