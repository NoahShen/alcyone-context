# CT31 / CM6 新 Architect 交接（2026-11-13）

## 用户暂停指令

用户要求停止所有 Agent、保存进度，并换一个全新的 Architect Agent。此文件是恢复入口；不要延续旧对话的长上下文。保存时无其他活动 Agent 或 schedule；旧 Architect `fd678078-0628-49de-8128-601fef659b17` 在保存后归档。没有 Git 写操作、模型调用或生产部署。

## 1. 当前准确状态

| 项目 | 状态 | 边界 |
|---|---|---|
| CT30 及以前 | 见既有 Architect 复核；不要重开 | 保留其历史验收范围，不扩大为生产部署完成 |
| CT31 B2 | APPROVED / DONE | 72 条 canonical（28 imported + 44 synthesized）、严格 loader、离线报告；历史正式证据 27/27 |
| CT31 B3 | 实现已落盘，IN_REVIEW / NOT_RUN | runner 未经过独立 Lead/Architect 验收；没有真实模型结果 |
| CT31 / CM6 | 未 DONE | 不能用离线/Fake/Judge 自动结果宣称模型质量或里程碑完成 |
| CT32 | LOCKED | 未放行 |

仓库在 `main`，基线 HEAD `3c7d5c3`。工作区有大量用户及多个历史批次的未提交修改与 untracked 文件。**必须原样保留：禁止 reset、checkout、clean、stash、rebase、commit 或 push；不得只保留 B3 文件而丢失其他工作。** 本文件以当前磁盘为准，不被 AGENTS.md 或任务书中的旧 Lead/旧状态记录覆盖。

## 2. B3 已实现的范围

- `integration-tests/context/build.gradle.kts`：独立 `contextRealModelEval` JavaExec 任务；默认 dry-run，真实调用要求 `contextEvalLive=true`、`contextEvalProduction=true`、`contextEvalExternalApproved=true`。普通 check/test 不触发收费调用。
- `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/`：12 个新文件，包括配置、fixture 转换、生产装配、生产 Dreaming/SQLite、Semantic Judge、协议检查、逐样本及汇总 artifact。
- 六类生产 Prompt/Schema/解析路径复用：Extraction、Candidate Scanner、Governance、Search Scanner/Rerank、Memory Map、Dreaming。生产 Dreaming 使用真实 SQLite 和持久化断言。
- 结果区分 `PASS`、`FAIL`、`REVIEW_REQUIRED`、`PROTOCOL_FAILURE`、`NOT_RUN`；保留 usage、延迟、缺失 usage、截断、Prompt/Schema SHA 等。Judge 是同一 Provider 的附加受控复核，不是独立模型、盲审或真人研究。
- 运行说明在 `evals/README.md`；B3 任务边界在 `docs/context/tasks/cm6-ct31/B3_真实模型任务书.md`。

## 3. 已验证与未验证

最终离线命令：

```text
./scripts/dev gradle :integration-tests:context:test \
  --tests 'com.github.noahshen.alcyone.context.integration.evaluation.*' \
  --tests 'com.github.noahshen.alcyone.context.memory.integration.evaluation.*' \
  -PcontextDreamingTestDir=build/ct31-b3-final
```

结果 BUILD SUCCESSFUL；`integration-tests/context/build/ct31-b3-final/xml/` 中 20+3+7+7 = **37/37**，零失败、错误、跳过。仅离线/协议/fixture 转换证据，不是生产组件真实运行或模型质量证据。

无网络 dry-run：全量选择 **72/72**，production `ext-001` 选择 **1/1**，均 BUILD SUCCESSFUL；没有网络或模型结果。

真实 `ext-001` smoke 的执行安全审批被拒绝，原因为内部评测 Prompt、样本及派生输出发往配置的外部网关需要明确批准。**没有真实请求、模型输出、费用或 Provider 兼容性证据。** 不得绕过审批或把 dry-run 写成真实 PASS。

## 4. 新 Architect 首批工作

1. 先读本文件、`B3_真实模型任务书.md`、`evals/README.md` 和 B3 的 12 个实现文件；只读盘点磁盘 diff 与测试 XML，不重读全部历史长对话。
2. 核对 B3 runner 是否真的满足六类生产语义与 artifact 边界，重点审查 fixture 到生产对象的映射、Scanner→Rerank 候选/顺序、Governance 字段、Dreaming stage/SQLite 证据、Judge 的失败隔离、报告 schema/选择完整性/成本与隐私。
3. 不直接实现生产代码。发现具体缺口时形成精确 R1（文件/行为/复现/建议），通过 Paseo 配置的 Lead → Worker 修复；无缺口也须独立初验与 Architect 复核后才可判 B3 runner 可用。
4. 恢复真实模型前，向用户明确确认：允许向已配置的 **my_magpie 网关**发送 CT31 脱敏样本、内部评测 Prompt 及派生模型输出；API key 不外发、不输出、不进入日志。此前调用/测试数据外发/费用不限授权仍存在，但安全审批对该具体外发风险有额外阻断。
5. 真实运行先 `ext-001` smoke，再检查 artifact、protocol/usage/truncation 与人工复核；smoke 成功不等于 72 条或语义质量通过。未运行样本必须保留，不以子集冒充全量。
6. B3 完成后仍需独立验收才能决定 CT31 状态；不得自动放行 CT32 或宣称 CM6 完成。

## 5. 已知审查重点

- 当前 `RealModelEvalCli.kt` 的 `selectedSamples` 为筛选后的数量、`sampleCount` 为已完成数量；`complete` 表示所选集合是否全部完成。不要把 smoke 与全量混淆。
- 无 `live` 时只打印样本 ID/Task/输入 SHA，不读取模型配置；dry-run 不是生产链路执行。
- 原始 prompt/response 仅允许进入明确隔离、未提交的输出目录；安全汇总不含 API key。不要提交 secrets、raw payload 或 `.local` 内容。
- Semantic Judge 与人类复核边界必须保留；模型自评不能替代 H1/H2/H3 人工审查。
- 本轮仅做过聚焦 37 条测试，没有做全仓 check；不能沿用历史全量数字证明当前工作区全绿。

## 6. 新 Architect 提示词（可直接复制）

```text
你是 /Users/noahshen/workspace/alcyone-context 的新任 Architect，接管 CT31 B3 与后续 CM6 工作。不要依赖旧对话上下文；先完整阅读 docs/context/tasks/cm6-ct31/NEW_ARCHITECT_HANDOFF.md，并遵守其中当前磁盘事实、边界和恢复步骤。

当前 CT31 B2 已 DONE；B3 的真实模型评测 runner 已写入 integration-tests，但尚未独立验收，真实模型仍 NOT_RUN。最新聚焦离线测试 37/37 通过，dry-run 72/72 与 ext-001 1/1 成功；这些不证明生产组件真实执行、Provider 兼容性或语义质量。CT31/CM6 未 DONE，CT32 LOCKED。

你的第一阶段只做有界、只读的 Architect 核对：
1. 阅读交接文件、B3_真实模型任务书.md、evals/README.md 和交接列出的 B3 实现文件。
2. 审查六类生产请求/解析复用、Scanner→Rerank 边界、Dreaming SQLite/stage 证据、Semantic Judge 失败隔离、artifact/报告完整性与隐私边界。
3. 形成简洁的 B3_新Architect方案核对.md：磁盘事实、验收矩阵、具体缺口（若有）及精确文件/复现/建议、真实模型 smoke 前置条件。不要复述长历史，不要将旧 Lead 状态当当前事实。

工作区是脏的，包含用户和多个历史批次的未提交改动。严禁 reset/checkout/clean/stash/rebase、覆盖或删除无关改动；禁止 Git commit/push。不得读取、打印或归档密钥，不得调用真实模型或扩大外发范围，除非先取得本阶段明确授权。

遵守 Architect → Paseo 配置 Lead → Worker → 独立复核流程；Architect 不直接编写生产代码，不直接管理 Worker。先提交只读核对供我审阅，不要自动派 Worker、不要启动 CT32、不要自批 DONE。
```
