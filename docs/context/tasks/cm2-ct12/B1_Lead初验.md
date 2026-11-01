# CT12 · Lead 独立初验（集成测试 + 评测基线/版本归档）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验 + B2 文档归档）
**结论**：**B1 集成验收通过，B2 样本/版本归档完成；Architect 已裁定 CT12 APPROVED / DONE，CM2（CT08～CT12）全部完成**；CT13 不启动。
**依据**：[CT12 任务书](../../milestones/cm2/CT12_任务书.md)、[B1_委派单](B1_委派单.md)、技术实现设计 §10.1～§10.6、CT11 B3 接线

## 0. 本次修改（2026-10-30，CT12 初验与 B2 归档）

Worker 完成 B1 真实 Runtime/SQLite + Fake LLM 三例端到端测试（含一次“测试级 vfs.runtime 依赖”冲突上报与方案 A 授权）；Lead 独立跑全量、复核窗口/失败继续/零记忆，并完成 B2 固定脱敏样本与版本/Fake/真实结果分离归档。

## 1. 交付面与依赖冲突

| 文件 | SHA-256（Lead 复算） |
| --- | --- |
| `integration-tests/context/build.gradle.kts`（仅测试依赖 1 行） | `073e8cc5…01c769a96` |
| `integration-tests/context/.../ExtractionHttpStub.kt` | `eaa437cf…a76a1c1df2` |
| `integration-tests/context/.../ExtractionEndToEndTest.kt` | `d7da8515…b7e7e9c0c3` |

- 构建改动仅 `testImplementation(projects.vfs.runtime)`；`:integration-tests:context` 的 **compileClasspath vfs 命中 0**，testCompileClasspath 4 命中；生产依赖图未变。
- CT11 Impl/Factory/Builder/Runner/Validator/Committer/Scheduler/Pipeline 等 SHA 未变；无第三方新依赖/公开 API；无 git。
- 冲突处理：首次因该模块 testCompileClasspath 无 VFS 类型无法调用公共 Factory，Worker 停止上报；Lead 授权方案 A（仅测试级仓库内模块），未改生产构建。

## 2. Lead 独立全量回归

| 命令 | 结果 |
| --- | --- |
| `./scripts/check` | **exit 0**，`BUILD SUCCESSFUL in 2m 5s`，**131 actionable tasks: 131 executed** |
| 14 模块行首 `@Test` vs XML | **1441 = 1441**，差额 0、失败/错误/跳过 0 |

| 模块 | 源码 | XML | 模块 | 源码 | XML |
| --- | ---: | ---: | --- | ---: | ---: |
| common | 23 | 23 | integration-tests/vfs | 107 | 107 |
| context/api | 26 | 26 | vfs/api | 66 | 66 |
| context/core | 43 | 43 | vfs/core | 305 | 305 |
| context/persistence-sqldelight | 176 | 176 | vfs/git-backup | 73 | 73 |
| context/runtime | 307 | 307 | vfs/persistence | 52 | 52 |
| context/server | 30 | 30 | vfs/runtime | 61 | 61 |
| integration-tests/context | **18** | **18** | vfs/storage | 154 | 154 |
| **合计** | **1441** | **1441** | | | |

integration-tests/context 15→18（+3）。

## 3. 端到端用例核对

| 用例 | 断言 | 结论 |
| --- | --- | --- |
| `a failed extraction does not stop later triggers` | 第 1 次非法 content → 0 Raw/Pending、5 pending、RUNNING；第 6 条再触发成功（1 Raw+Pending、6 Target complete）；stub 请求 2；凭据只在 Header、落库无 Key | ✔ |
| `pending turns outside the initial window stay pending` | `initialWindowTurns=4`、6 条；窗口内 turn-2..5 complete、turn-0/1 仍 pending；来源仅窗口内 | ✔ |
| `an empty memory batch completes every target without raw rows` | `{"memories":[]}` → 5 Target complete、0 Raw/Pending、RUNNING、1 请求 | ✔ |

- 等待使用真实 SQLite 只读查询 + `withTimeout/delay` 有界轮询（无固定 sleep）；`finally` 关闭 Context/stub/临时目录；日志/落库无 Key/正文。

## 4. B2 评测基线与版本归档

| 文件 | 内容 |
| --- | --- |
| `evals/extraction.jsonl` | 4 条合成脱敏样本（决定/原因、约束范围、相对时间锚点、空结果），含 must/forbidden/expectedSourceTurns |
| `evals/metadata.json` | Prompt v1 `56d3278f…`、Schema v1 `041e7916…`、Profile（`apiKey=<redacted>`、model `<offline-fake>`）、Task 预算、样本版本 `samples-v1` |
| `evals/results-fake-v1.json` | B1 三例离线 Fake 结果，`semanticQualityEvaluated=false` |
| `evals/results-real-model.md` | `status: not-run`，明确不得冒充真实模型通过 |

## 5. 边界与 A01～A08

| 项 | 结果 |
| --- | --- |
| `vfs` status/diff | 0 行 |
| 81 VFS 基线 | `Checked 81 files: all OK` |
| Core 无 Ktor | build/import/compileClasspath 均无命中 |
| `ContextSchema.sq` | `ec727a53…` 未变；CT11 B3 探针 `user_version=1`、6 表、5 索引 |
| 生产/CT11 文件 | SHA 未变；无 SQL/索引/依赖（除测试级一行） |

| # | CT12 验收 | 证据 |
| --- | --- | --- |
| A01 | 失败后 Trigger 继续 | B1 用例 1 |
| A02 | 窗口外 Pending 不变 | B1 用例 2 |
| A03 | 零记忆完成 Target、无 Raw | B1 用例 3 |
| A04 | 固定脱敏样本/版本归档 | `evals/` 4 文件 |
| A05 | Fake 与真实模型分开归档 | `results-fake-v1.json` / `results-real-model.md`（not-run） |
| A06 | 全量回归 | check exit 0、1441=1441 |
| A07 | VFS/Core/Schema 边界 | 81 基线、vfs diff、Core、Schema |
| A08 | 不新增生产代码/依赖、不启动 CT13 | 交付面审查 |

## 6. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | 为调用公共 Factory，`integration-tests/context` 仅新增测试级 `vfs.runtime` 依赖（方案 A）；生产 compileClasspath vfs 为 0 | 测试专用；需 Architect 复核确认 |
| L2 | 样本为合成脱敏且仅 4 条，未运行真实模型；不对语义质量下结论 | 符合 Q7/CT12 范围 |
| L3 | 首次 E2E stub 信封多一层引号导致 INVALID_RESPONSE；修正后全绿 | 测试辅助问题，非产品缺陷 |
| L4 | CT12 B2 由 Lead 完成文档，不新增测试/代码 | 符合 CT12 文档/证据范围 |

## 7. 结论与下一步

- B1 三例真实 Runtime/SQLite + Fake LLM 端到端全绿；全量 check/14 模块/81 基线/Core/Schema 通过；B2 样本与版本归档完成；Fake 与真实模型结果分离，真实模型明确未运行。
- **提交 Architect 裁定 CT12 是否 DONE**；CT13 未启动，仍无 git 操作与发布。

### 证据路径

- `.local/validation/cm2/lead/ct12-b1/check.log`、`test-counts.txt`、`prod-classpath.log`、`test-classpath.log`、`vfs-git.txt`、`vfs-baseline.txt`
- Worker：`.local/validation/cm2/worker/ct12-b1/`
- 评测归档：`docs/context/tasks/cm2-ct12/evals/`、`B2_评估基线与版本归档.md`
