# CT31 B2 独立 Architect 复核

## 本次修改（2026-11-12，最终独立复核：R1 CLOSED，B2 APPROVED / DONE）

- 独立强制执行 evaluation **27/27，0 failures / errors / skipped**，BUILD SUCCESSFUL in 2s，28 tasks（1 executed / 27 up-to-date）；日志 `/tmp/ct31-b2-r1-final-independent.log`。
- 项目 JDK JShell 重放 §6.3 两个原反例，均已转绿：非空 expectedOrder 下真实空输出四项 0 分且分母为 1；合法无关项首位时 MRR=0.5、其余三项为 1；两者 orderStatus=FAIL、protocolFailureCount=0。排序语义不再清除指标；协议失败时顺序为 NOT_APPLICABLE。
- Governance 正式负例已使用完整 12 行并核对具体错误消息；数据/manifest/report 未漂移，72=28+44、semanticQualityEvaluated=false、modelRun NOT_RUN 保持。
- **R1-1 CLOSED，R1-2/R1-3 保持 CLOSED；R1 整体 CLOSED；CT31 B2 APPROVED / DONE（仅离线数据与工具）。** 详见 §7。B3 无真实模型授权，仍不放行 / NOT_RUN；CT31/CM6 未 DONE，CT32 LOCKED。
- 本轮仅更新本复核文档，没有修改生产/测试/数据、读取密钥、调用真实模型或执行 Git 写操作；未重跑全量 check，不把历史全量计数当本轮证据。

## 历史修改（2026-11-12，R1 修复后独立复核：仍 IN_REVIEW）

- 独立强制执行 evaluation：**25/25，0 failures / errors / skipped**，`BUILD SUCCESSFUL in 1s`，28 tasks（1 executed / 27 up-to-date）；日志 `/tmp/ct31-b2-r1-independent-fresh.log`。首次普通命令全部 UP-TO-DATE，故不将其当新实跑，随后仅对 test 使用 `--rerun`。
- **R1-2（NOOP 拒绝）与 R1-3（四样本 oracle）CLOSED；R1-1（Search）部分修复，但仍 OPEN。** 新独立内存探针确认：非空 expectedOrder 会把正 qrels 的真实空输出、合法但排序不佳的输出转为 PROTOCOL_FAILURE，清除原本应为 0 / 0.5 的指标。详见 §6。
- **R1 整体未 CLOSED，B2 不 APPROVED / DONE。B3 不放行、保持 NOT_RUN；CT31/CM6 未 DONE，CT32 LOCKED。** 未修改代码/测试/数据，未运行模型或读取密钥，无 Git 写操作；只追加本复核记录。

## 历史修改（2026-11-12，首次独立复核：IN_REVIEW / R1）

- 独立阅读任务书、Architect 裁定、B2 交付/Lead 初验、B3 任务书、evals README，并核对 16 路径、72 条数据、schema/loader、指标工具与测试。
- 实际运行指定 evaluation 定向命令：BUILD SUCCESSFUL，2 秒，28 actionable tasks（1 executed / 27 up-to-date）；新生成 XML 为 **6 + 11 = 17 tests，0 failures / errors / skipped**。日志 `/tmp/ct31-b2-independent-eval.log`，XML 时间 `2026-11-12T13:23:43Z`。未运行全量 check，Lead 的 137/137 与 2341 tests 不记为本轮独立实跑。
- 项目 JDK 21 JShell 直接调用当前编译后的测试级工具，内存构造反例，确认 Search 候选全集/分阶段边界错误及 Governance 仍接受 NOOP；未写入探针源码或改动数据。
- **B2 IN_REVIEW / R1，不建议 APPROVED / DONE。** 下述三组阻断项交回 Lead→Worker 修复；真实模型 **B3 NOT_RUN**，CT31 整体未 DONE，CT32 LOCKED，CM6 未 DONE。

## 1. 已核实证据

| 核对项 | 独立结果 |
| --- | --- |
| 精确写集合 | `evals/` 12 文件 + `evaluation/` 4 Kotlin 文件 = 16，与方案 §5 一致；当前文件的 16 项 SHA 全部匹配 B2 Lead 初验表 |
| 数据数量 | 六类各 12，总 72；导入 Extraction 4 / Search 10 / Map 10 / Dreaming 4 = 28，合成 44；稳定 ID、引用与风险覆盖现有完整性测试通过 |
| 来源与版本 | 四个 provenance 来源 SHA 全匹配，四个旧文件均与 `git show HEAD:<path>` 字节相同；manifest 26 项 Prompt/Schema 路径-SHA 引用全部匹配 |
| 报告确定性 | 正式测试两次内存重建相等，并与 checked-in JSON 相等；未运行会写报告的 main；`semanticQualityEvaluated=false`、`modelRun.status=NOT_RUN`，usage/cost null |
| Fake 边界 | Dreaming 4 场景为结构回放，`semanticEvidence=false`；不作为模型质量证据，`criticalErrors=[]` 不表示真实模型没有严重错误 |
| 最新修复 | gov-002 实际为 MERGE，gov-007 实际为 NEW；Search 所有非空 domains 样本为 STRICT；Map required ID/Domain 均具对应导航检查，loader 也检查其包含关系；dream-007 为 DATA_ONLY，loader 对 forbiddenInsightPatterns 执行 Regex 编译校验 |
| 旧测试/构建 | `git diff --name-only -- integration-tests/context` 为空，新 evaluation 文件为未跟踪新增；未发现该模块旧测试/构建修改 |

边界说明：整个工作区包含 CT27～CT30 的大量未提交生产改动，不能把当前 `git status` 当成 B2 越界证据，也不能据此宣称全仓生产相对 HEAD 无改动。本轮确认 16 文件交付与来源/Prompt SHA，未取得完整的 B2 开始时全生产文件快照，因此“所有生产文件在 B2 期间零漂移”不能仅靠本轮 status 独立证明。本复核过程未修改生产、测试、数据、构建或其他文档；只写本复核文件，不执行 Git 写操作，不读取密钥，不调用真实模型。

## 2. R1-1：Search 将相关集合当合法候选全集，阶段约束与指标反例不成立（阻断）

路径：

- `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/evaluation/OfflineEvaluationHarness.kt:47`（SearchObservation）及 `:81`（evaluateSearch）。
- `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/evaluation/OfflineEvaluationReportTest.kt:24`、`:66`。

`SearchObservation` 没有实际候选全集；evaluateSearch 以 `judgmentIds` 判定 out-of-set，同时所有 gain 必须大于零。因此合法但不相关的候选与伪造 ID 无法区分。对于合法候选 `{n1,r1}`、相关 qrels `{r1}`、K=2、排名 `[n1,r1]`，应得到 Recall=1、MRR=0.5，而真实工具探针输出四项均 `PROTOCOL_FAILURE`、`outOfSetIdCount=2`。当前任何被接受的非空 ranking 第一项都属于 relevant，故非空合法结果的 MRR 实际只能为 1；Lead 所述“第二位相关”没有对应有效测试。

另一个真实探针：qrels `{r1,r2}`，Scanner `[r1]`，Rerank `[r2,r1]`，工具返回 Scanner Recall=0.5、最终 Recall=1、protocolFailureCount=0。Rerank 把 Scanner 未选中的 r2 加回来仍获通过；现有测试反而将这一输入写成成功例。即使把两阶段解释为独立采集，也必须显式记录各自实际输入候选，当前结构无法验证 Rerank 输入边界。Rerank 的关键项首位/预期顺序也未由 SearchMetrics 表达或判定。

修复要求：区分候选全集、正相关 qrels、缺失人工 judgment 与已知不相关项；明确 Scanner→Rerank 实际输入集合及阶段 protocol 状态。补真正的第二位相关 MRR=0.5、相关项位于 K/K+1、合法无关项与伪造项、Rerank 越出其输入、缺失 judgment、关键项首位/顺序反例。不得通过过滤错误结果或把非相关结果改成协议失败来改善质量分数。空 qrels 仅检查合法空结果，不进入 Recall/MRR 分母。对应 D7/D8。

## 3. R1-2：Governance NOOP 只从数据删除，schema/loader 仍接受（阻断）

路径：

- `evals/schema/dataset-v1.schema.json` 的 `$defs.governanceOracle.properties.expectedAction` 仍枚举 `NEW/MERGE/UPDATE/NOOP`。
- `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/evaluation/EvalDatasetLoader.kt:705`、`:715`、`:723`～`:730` 显式允许和处理 NOOP。

内存探针从当前 gov-007 构造 `expectedAction=NOOP`、`expectedEmpty=true`、`requiredContentPoints=[]`，其他字段/12 条列表保持，调用真实 `parseSampleLines` 成功返回 12 条，无异常。数据里目前没有 NOOP 不等于严格契约已拒绝 NOOP；这与 README/交付的“合法动作只有三种”及生产 Governance Prompt 直接冲突。

修复要求：schema 与 loader 同时拒绝 NOOP，并约束 Governance 不期待空动作；增加直接负例，证明不能再次导入伪造动作。对应 D10。

## 4. R1-3：人工 oracle 仍有自相矛盾及错误排除（阻断）

### Governance

`evals/datasets/governance.jsonl:7` 的 gov-007 要求 NEW，requiredContentPoints 要求记录“用户请求重复当前缓存策略”，但 forbiddenRegressions 同时禁止“Store the ephemeral viewing request as durable memory.”。NEW 是新建 Organized 认知的治理决定，两项不能同时成立。review 关于上游过滤的解释不能使已经进入该样本的 NEW 与禁止持久化同时正确。

修复要求：将此用例改成真实可持久化、无相关候选且合法 NEW 的事实；或明确不适用此输入，并以同风险目标的有效样本替换，不能再引入 NOOP。

### Candidate Scanner

`evals/datasets/scanner.jsonl:2` 的 sc-002 把 `MEM-SC-005`（pnpm 不可用时使用 npm）列为 forbidden，理由是“不是重复事实”；但生产 `organization-candidate-v1.md` 要求高召回，兼容补充同样应纳入。该候选是同一包管理器偏好的回退条件，不是明显无关项。

`evals/datasets/scanner.jsonl:9` 的 sc-009 把 `MEM-SC-010`（queue name 是 batch-standard）列为 forbidden，而 Raw 陈述 queue name 是 ingest-priority；这是同一属性的潜在冲突，应提交后续 Governance 判断。正文注入应被忽略，但不能因此漏掉真实输入中的冲突候选。当前 oracle 会惩罚遵循高召回/冲突召回规则的输出，并奖励漏召回。

修复要求：逐条重新审查 Scanner relevant/critical/forbidden 集合，按“重复、补充、矛盾、纠错”而非“只找重复”裁定；至少修复上述明确反例，并重新签注人工审查理由。

### Map 导入约束的无依据残留

`evals/datasets/memory-map.jsonl:3` 的 map-003 将“丢失变化原因”列为 forbiddenFacts，但输入只提供每周同步改为每日异步及生效日期，没有提供变化原因。旧来源也存在这一约束；保留来源原文可用于追溯，但不能直接把无依据的旧约束作为 canonical 真值要求。应保留 provenance 原文，并在 canonical 判定中明确此项不适用/纠正理由，禁止要求模型补造原因。

本轮完成逐样本输入/oracle 阅读，以上为可直接定位的阻断反例；`APPROVED_FOR_OFFLINE` 字段及忠实复制旧文本本身不足以证明人工 oracle 正确。对应 D3/D5。

## 5. 复核出口

17/17 正式测试及 SHA/报告重建通过，证明当前已覆盖工程路径可重复，不覆盖上述真实反例。当前不需要重复全量来替代定点修复；Lead→Worker 在既定 B2 写集合内修正后，须重跑相应反例、evaluation 与必要回归，更新 manifest/报告/SHA 和人工审查说明，再交独立复核。

**结论：B2 IN_REVIEW / R1。B3 NOT_RUN；CT31 未 DONE；CT32 LOCKED；CM6 未 DONE。** 本复核不放行真实模型、CT32 或部署。

## 6. R1 修复后独立复核（2026-11-12）

### 6.1 实际执行与数据证据

阅读 [R1 修复交付](B2_R1_修复交付.md)、原复核、evals README、当前工具/测试及 B3 任务书。实际运行：

```text
./scripts/dev gradle :integration-tests:context:test --rerun \
  --tests 'com.github.noahshen.alcyone.context.memory.integration.evaluation.*' --console=plain
```

结果 BUILD SUCCESSFUL；完整性 7 例 + 报告/指标 18 例 = **25 例**，0 失败/错误/跳过。XML 时间为 `2026-11-12T14:59:18.950Z` / `14:59:19.243Z`。报告重建测试再次证明两次构建相等、与落盘 JSON 相等；未执行写报告 main，未重复全量 check。

六类数据仍为各 12、72=28+44；六份数据 SHA、manifest→报告 SHA、所有路由 Prompt/Schema SHA 均匹配。四份旧来源的 SHA 和 HEAD 字节对照均不变。离线报告 `semanticQualityEvaluated=false`、modelRun NOT_RUN、usage/cost null，Search aggregates 因没有捕获输出保持 N/A；这不等于真实模型评分。

本轮核对快照：

| 文件 | SHA-256 |
| --- | --- |
| `evals/manifest-v1.json` | `13e2d0679f573d8361a03d290aa1c0ffe98f5ceadd55e50c77815cbefcbffd0a` |
| `evals/results/offline-v1.json` | `7fcdaa44e0891f421e0447a8832bc3691559f15a647fd312cfbdfabdc03d7d92` |
| `evals/schema/dataset-v1.schema.json` | `e457aa748cdf0ccd95a953d98014429a6769e2ffe9c924fc50c455282b961f6a` |
| `evaluation/OfflineEvaluationHarness.kt`（原完整路径不变） | `f90adeb63fb3f163885a9b6e9271aeecaaeee0f62e0737834b838b30111bc8c6` |

### 6.2 已关闭及已通过的子项

- **R1-2 CLOSED**：schema 枚举已删除 NOOP，loader 仅接受 NEW/MERGE/UPDATE 并拒绝 Governance expectedEmpty=true。除正式测试外，独立 JShell 使用完整 12 行列表，仅内存修改第 7 行，分别得到 `expectedAction is invalid` 和 `governance decisions cannot be empty`，明确不是因为数量/ID 不符而拒绝。
- **R1-3 CLOSED**：gov-007 已改成批准的灾备区域 eu-west-2 这一可持久化 NEW 事实；sc-002 的 fallback 纳入 relevant；sc-009 的冲突队列纳入 relevant/critical；map-003 删除无来源的变化原因要求，transformNote/review 明确记录人工修正，旧来源文件未改。
- Search 已区分 candidateIds、正 qrels 与 judgedIds；不传 expectedOrder 时，合法无关项首位、相关项第二位确实得到 MRR=0.5。正式测试覆盖 unjudged、伪造/重复 ID、Scanner→Rerank 输入及输出边界、K/K+1、正 qrels 空 ranking=0、真空 qrels/空输出=N/A。上述局部修复通过。

补充测试建议：当前 Governance loader 负例传入只有一行、descriptor 仍要求 12 行，且只断言异常类型；将来移除动作校验也可能因样本数量错误而保持绿色。本轮用完整列表及具体错误消息独立补证，所以不据此重开 R1-2；正式回归宜改为完整合法列表并核对错误来源。

### 6.3 R1-1 残留阻断：排序语义失败清除正常指标

路径：`integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/evaluation/OfflineEvaluationHarness.kt`，`orderFailed` 判断及紧接的 `if (scannerFailed || rerankFailed || orderFailed)`；对应 `OfflineEvaluationReportTest.kt` 的 `critical first and expected order are explicit protocol dimensions`。

当前把 expectedOrder 不匹配与非法 ID/重复/阶段越界合并调用 protocolFailure，四项 MetricValue 均被重置为 PROTOCOL_FAILURE/null/0 分母。expectedOrder 是人工语义 oracle，实际排序不符合期望并不使合法候选输出失去协议合法性，也不应抹掉召回率/MRR。

直接用项目 JDK JShell 调用当前编译后的工具，未写任何探针源码或数据，得到：

| 输入 | 应保留的结果 | 实际结果 |
| --- | --- | --- |
| candidates/qrels=`[r1]`，critical r1，K=2，Scanner/Rerank 均空，expectedOrder=`[r1]` | 四项 CALCULATED 0，召回分母 1；顺序/关键项另报未满足 | Scanner/Rerank status PASS，但 orderStatus PROTOCOL_FAILURE；四项全部 null、分母 0，protocolFailureCount=1 |
| candidates/judged=`[n1,r1]`，qrels=`[r1]`，K=2，两阶段排名=`[n1,r1]`，expectedOrder=`[r1,n1]` | Recall/CriticalRecall/FinalRecall=1，MRR=0.5；另报排序未满足 | 四项全部 PROTOCOL_FAILURE/null，protocolFailureCount=1 |
| 与上一行完全相同，仅 expectedOrder 为空 | 同样的数值指标 | 实际为 1/1/0.5/1，protocolFailureCount=0 |

这证明“正 qrels 空输出计 0”只在遗漏 expectedOrder 时成立。canonical Search 多条已有非空 expectedOrder，因此不能以默认空顺序的单测证明组合路径正确；新增正式顺序测试还把这个错误分类固化成预期。

修复范围仍限当前 B2 工具及测试：将顺序/关键项语义判定从 protocol failure 分离，保留协议合法输出的数值指标与分母；明确 semantic order 的 PASS/FAIL/未评状态，不靠删除 expectedOrder 规避。补齐上表两个组合反例，并验证顺序错误不会污染合法 Scanner 指标；未知/重复/阶段越界仍按既定协议规则拒绝。

### 6.4 本轮出口与 B3

**R1-2 / R1-3 CLOSED；R1-1 OPEN；R1 整体未 CLOSED；B2 保持 IN_REVIEW，不 APPROVED / DONE。** 只需针对 §6.3 定点修复后再验，不重开已关闭的数据/NOOP 问题。

**B3 不放行，保持 NOT_RUN。** 除 B2 尚有残留外，B3 任务书要求明确的真实模型调用授权、可用 Provider/model/Profile、外发范围和费用上限；本轮未收到这些前置条件。即使 B2 后续完成，也不能自动将 B3 视为已授权。CT31/CM6 未 DONE，CT32 不放行。

## 7. 最终独立收口（2026-11-12）

本节取代 §6 的开放状态；历史失败证据保留。

### 7.1 独立执行

阅读更新后的 [R1 修复交付](B2_R1_修复交付.md)，核对当前 Harness、两个新增组合反例和 Governance 完整列表负例；独立执行：

```text
./scripts/dev gradle :integration-tests:context:test --rerun \
  --tests 'com.github.noahshen.alcyone.context.memory.integration.evaluation.*' --console=plain
```

命令成功，2 秒，28 actionable tasks（1 executed / 27 up-to-date）。日志 `/tmp/ct31-b2-r1-final-independent.log`；XML 为完整性 **7** + 指标/报告 **20** = **27 tests，0 failures / errors / skipped**，时间分别为 `2026-11-12T15:04:55.239Z` 和 `15:04:55.563Z`。此次 test 实际执行，未使用 UP-TO-DATE 作为测试通过证据。

另以项目 JDK JShell 直接调用当前编译工具，仅构造内存数据，原 §6.3 两反例结果：

| 原反例 | 本轮实际结果 |
| --- | --- |
| 正 qrels=`[r1]`、critical r1、两阶段空输出、expectedOrder=`[r1]` | Recall/CriticalRecall/MRR/FinalRecall 全部 CALCULATED 0.0，四项分母均 1；Scanner/Rerank PASS、order FAIL、protocolFailureCount=0 |
| candidates/judged=`[n1,r1]`、qrels=`[r1]`、实际顺序 `[n1,r1]`、expectedOrder=`[r1,n1]` | Recall/CriticalRecall/FinalRecall=1.0，MRR=0.5（1/2）；Scanner/Rerank PASS、order FAIL、protocolFailureCount=0 |

源码确认 `orderFailed` 不再进入 protocolFailure 分支；仅实际阶段协议失败进入该分支，orderStatus=NOT_APPLICABLE。正式测试仍覆盖非法/重复/unjudged、阶段输入/输出越界、K/K+1、真空 qrels 与确定性报告，均通过。

### 7.2 数据及关闭项保持

- Governance 负例现在替换完整 12 行中的 gov-007，分别核对 `expectedAction is invalid` 与 `decisions cannot be empty`；§6.2 的测试建议也已落实。
- 重新校验六份数据 SHA 与计数 **72=28+44**，manifest 和 offline 报告 SHA 与 §6.1 完全相同；本次最后修复没有改变已验收的四个数据 oracle。
- 报告确定性正式测试通过；`semanticQualityEvaluated=false`、modelRun NOT_RUN、usage/cost null 保持。Search aggregates 的 N/A 仍只表示未采集真实输出；Fake 不作为语义质量证据。
- 最后修复后的三项源码 SHA 如下（路径前缀均为 `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/evaluation/`）：

| 文件 | SHA-256 |
| --- | --- |
| `OfflineEvaluationHarness.kt` | `1d9d3f7463b16df37f43d036155dbbce50a47a8223e27a2a84a5a293119b56ff` |
| `OfflineEvaluationReportTest.kt` | `3d41a482b15c237a5232fd3ee7b4d8ac6269d139226f8140f5d68fec2432820b` |
| `EvaluationDatasetIntegrityTest.kt` | `6d70948c18f23265b54bd8b7a1e9679cf244667dd97028fe59b904fc5b3e6e92` |

### 7.3 最终裁定

**R1-1 CLOSED；R1-2/R1-3 保持 CLOSED；R1 整体 CLOSED。CT31 B2 APPROVED / DONE。** 收口范围是 canonical 数据、离线完整性/指标工具、反例与报告，不包含真实模型语义质量或 Provider 验证。当前冻结源码没有剩余阻断复核项。

**B3 不放行，保持 NOT_RUN。** 用户未授权真实模型调用，B2 DONE 不替代 B3 的 Provider/Profile、外发范围、费用上限与明确授权。CT31 整体未 DONE，CM6 未 DONE，CT32 LOCKED；不放行部署。本轮未修改其他文件、未读取密钥、未调用模型、无 Git 写操作。


