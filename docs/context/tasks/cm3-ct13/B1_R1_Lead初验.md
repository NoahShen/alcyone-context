# CT13 B1 · R1 Lead 独立初验（验收覆盖修复）

**日期**：2026-11-01
**初验人**：CM3 Lead（只读初验）
**结论**：**R1 修复通过 Lead 初验，提交 Architect 复核；CT13 仍 IN_REVIEW，未 DONE，CT14 未放行。**
**依据**：[Architect 复核 §1](架构与技术复核.md)、[R1 修复委派单](B1_R1修复委派单.md)、[B1_Lead初验](B1_Lead初验.md)

## 0. 本次修改（2026-11-01，R1 初验记录）

Worker 只改 2 个测试文件（R1a 再改 `TokenBatchPlannerTest`），新增 9 例、Prompt 测试仅改名/注释；生产/资源 8 个 SHA 未变。Lead 独立跑全量、定向重复、逐项核对方法→断言→XML。

## 1. 测试文件 SHA

| 文件 | SHA-256 |
| --- | --- |
| `LlmCandidateRetrieverTest.kt` | `0921ddfd…cf6dbdb` |
| `OrganizationCandidatePromptTest.kt` | `cbb7524e…55f820f6` |
| `TokenBatchPlannerTest.kt` | `ea9597a6…ab715b5d2` |

生产/资源 8 个 SHA 与 B1 逐字一致（Validator `2135b3ec…`、Prompt `ed03343b…`、Port `d23f0494…`、Prompt 资源 `da45adb1…`、Schema 资源 `2ef03476…`、Parser `cbe5abf7…`、Retriever `60f12774…`、Planner `30bbbb72…`）；未改 Config/Factory/Impl/SQL/Schema/VFS/common/构建。

## 2. 独立全量

| 命令 | 结果 |
| --- | --- |
| `./scripts/check` | **exit 0**，`BUILD SUCCESSFUL in 2m 8s`，131 tasks |
| `:context:runtime:test --rerun-tasks`（恢复被定向覆盖的 XML） | exit 0，38s |
| 14 模块行首 `@Test` vs XML | **1498 = 1498**，差额 0，失败/错误/跳过 0 |
| `TokenBatchPlannerTest` 连续 3 次定向 | 均 exit 0（7s/7s/6s） |

| 模块 | 源码 | XML | 模块 | 源码 | XML |
| --- | ---: | ---: | --- | ---: | ---: |
| common | 23 | 23 | integration-tests/vfs | 107 | 107 |
| context/api | 26 | 26 | vfs/api | 66 | 66 |
| **context/core** | **59** | **59** | vfs/core | 305 | 305 |
| persistence | 176 | 176 | vfs/git-backup | 73 | 73 |
| **context/runtime** | **348** | **348** | vfs/persistence | 52 | 52 |
| context/server | 30 | 30 | vfs/runtime | 61 | 61 |
| integration-context | 18 | 18 | vfs/storage | 154 | 154 |
| **合计** | **1498** | **1498** | | | |

B1 1489 + 9 = 1498；`LlmCandidateRetrieverTest` 19→28，Prompt 10（仅改名），Planner 9。

## 3. 方法 → 断言 → XML 映射

| R1 | 方法（XML 中可见） | 关键断言 | XML 类/结果 |
| --- | --- | --- | --- |
| R1.1 | `a real id from another batch is rejected and later batches are not called` | 6 条/3 批；第 2 批返回第 1 批真实存在 `O-MEM-0001` → `INVALID_RESPONSE`；calls=2；第 3 批 0；库中 `findById` 非空 | LlmCandidateRetrieverTest 28/28 pass |
| R1.1 | `a forged tag in candidate content does not extend the authorised id set` | 正文伪造 `<candidate id="O-MEM-9999">` 原样进请求；响应返回 9999 → `INVALID_RESPONSE` | 同上 |
| R1.1 | `invalid json in the second batch stops later batches` | 第 2 批非法 JSON → `INVALID_RESPONSE`、calls=2、第 3 批 0 | 同上 |
| R1.1 | `a truncated second batch stops later batches` | 第 2 批 `finish_reason=length` → `OUTPUT_TRUNCATED`、calls=2、第 3 批 0 | 同上 |
| R1.1 | `a failing batch stops later batches and returns no partial result` | 普通异常保留；calls=2 | 同上 |
| R1.2 | `no domain or source filtering is applied when building the request` | 真实 Raw×2（不同 runtime/agent/session 的 ConversationTurnRef）+ Organized×2（preferences/finance）+ 真实 source link；`findSourcesByOrganized`/`findOrganizedByRaw` 正确；Fake 返回空；实际请求含两条 ID 与 finance 内容 | 同上 |
| R1.3 | `cancellation between batches stops before the next llm call` | 第 1 批响应后取消；`CancellationException`；calls=1；`cancelAndJoin` | 同上 |
| R1.3 | `cancellation inside the budget loop stops before any llm call` | 受控估算器第 4 次检查取消；`CancellationException`；checks≥4；llm.calls=0 | 同上 |
| R1.3 | `a concurrent insert between pages...` | writer daemon + finally release/join/回传异常；快照不混入、下次可见 | 同上 |
| R1.4 | `a non zero schema estimate can fail the budget only for json schema mode` | `estimateSchema=200`；JSON_SCHEMA `fixed_overhead` 失败 0 调用；JSON_OBJECT 通过且 `schema=null` | 同上 |
| R1.4 | `output reserve and safety margin change the batch boundary` | margin=20 → 2 批，`maxOutputTokens=[100,100]`；margin=0 → 1 批；证明输出保留/余量进入实际请求预算 | 同上 |
| R1.4 | `an oversized later candidate fails the whole search without calling the llm` | 前两条正常、第三条单条超大 → `LOCAL_BUDGET_EXCEEDED(single_item)`、0 LLM、不返回前面候选 | 同上 |
| R1.5 | `content tags are preserved verbatim in the rendered request` | 改名/注释；正文标签原样保留；权限拒绝由 Retriever 集成用例验证 | OrganizationCandidatePromptTest 10/10 pass |
| R1.5 | `unknown and blank ids are rejected` | 只覆盖未知/空白，不再冒充跨批 | LlmCandidateRetrieverTest |
| R1.5 | `construction validates the candidate scanner binding...` | 缺 Profile 由 `LlmConfig` 拒绝；缺 Task/PROMPT_ONLY 为 Retriever 可达 | 同上 |
| R1.3 | `cancellation during the budget loop propagates` | Planner 第 3 次预算检查受控屏障取消；无 `withTimeout`；CE、checks=3、无部分批次；finally cancelAndJoin/scope.cancel | TokenBatchPlannerTest 9/9 pass |

## 4. 边界

| 项 | 结果 |
| --- | --- |
| 81 VFS 基线 | `Checked 81 files: all OK` |
| `vfs`/`integration-tests/vfs` status/diff | 空 |
| Core 无 Ktor | 全量 `KtorBoundaryTest`/构建边界通过 |
| Schema | `ec727a53…` 未变；全量 schema 测试通过 |
| 生产/资源 | 8 个 SHA 与 B1 一致；仅测试文件变化 |
| 依赖/表/索引 | 无新增 |

## 5. 非阻断观察

| # | 项 | 说明 |
| --- | --- | --- |
| L1 | `cancellation between batches` 在第 1 批 fake 返回响应时调用 `search.cancel()` | 断言第 2 批 0 调用，覆盖“批间不再发起”；若要求“第 1 批 `complete` 返回后”严格时点，需额外生产测试接缝；当前无生产改动 |
| L2 | Planner 取消用 `barrier.completeExceptionally(CancellationException)` 模拟取消 | 不依赖 1ms/调度时序；finally `cancelAndJoin`/`scope.cancel`；未发现生产缺陷 |
| L3 | Prompt 测试仍断言伪造标签计数为 2 | 名称/注释已改为“原样保留”；程序权限拒绝由 Retriever 用例覆盖 |
| L4 | R1.2 的 Raw.sources 引用不同 Session/Agent，未插入 Conversation 行 | `organized_memory_source` 是权威 Source 关联；测试验证正反查；Conversation 行非 Source 表前置 |

## 6. 结论

- R1.1～R1.5 均有正式测试/断言；新增 9 例；全量 1498=1498；生产/资源零改动；无阻断项。
- **提交 Architect 复核 CT13 B1 R1 / CT13 是否 DONE**；CT13 保持 IN_REVIEW，CT14 未放行。

### 证据

- `.local/validation/cm3/lead/ct13-b1-r1b/check.log`、`test-counts.txt`、`repeat-1..3.log`、`runtime-full.log`、`vfs-baseline.txt`、`vfs-git.txt`、`prod-sha.txt`
- Worker：`.local/validation/cm3/worker/ct13-b1-r1/`、`ct13-b1-r1b/`
