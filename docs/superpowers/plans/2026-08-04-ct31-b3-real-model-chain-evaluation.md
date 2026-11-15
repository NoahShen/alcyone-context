# CT31-B3 真实模型六链路评测执行技术方案

日期：2026-08-04  
状态：技术接缝已核实；待实现，**真实模型尚未执行**  
基线：`22a1649`；canonical `evals/manifest-v1.json` 为 `ct31-v1`，72 条样本  
姊妹任务：`docs/superpowers/plans/2026-08-04-ct31-b3-evaluation-worker-task.md`

## 1. 目的与边界

B3 要验证的不是 HTTP 能否返回，而是当前真实 LLM 能否在冻结的
`ct31-v1` 数据上通过当前生产 Prompt、JSON Schema、预算、解析、ID/业务校验，
并据此得到六类链路的可复现结果。

本方案只新增评测与必要的 internal 执行接缝，不扩大 SDK/public API，不更改 B2
canonical 数据集，不改变生产默认行为、fallback 或评测 Oracle，不把测试改成读取
真实服务。72 条 canonical 样本中 28 imported/44 synthesized 是既有完整性字段，不是
真实模型已完成的样本数。

### 必须复用的生产阶段

| Topic | 真实输入/请求 | Runtime 阶段与协议验证 |
| --- | --- | --- |
| Extraction | `DefaultPreExtractionContextBuilder` 的同 session 连续窗口、target/history 状态 | `DefaultExtractionRunner`、`JsonExtractionResponseParser`、`ExtractionBatchValidator` |
| Candidate Scanner | 一条 Raw + 完整 Organized 快照，超预算由 `TokenBatchPlanner` 分批 | `LlmCandidateRetriever`、`JsonCandidateResponseParser`、`CandidateIdValidator` |
| Governance | 一条 Raw + 该 Raw 的全部候选 | `LlmGovernanceService`、`JsonGovernanceResponseParser`、`GovernanceDecisionValidator` |
| Memory Search | query/domain/全部 Organized 快照 | `MemorySearchService` 的 Scanner→Rerank、两套 parser、`MemorySearchIdValidator` |
| Memory Map | 非空固定 Organized 快照 | `MemoryMapGenerator`、`JsonMemoryMapResponseParser`、`MemoryMapValidator` |
| Dreaming | 一个连续 batch 的六个可独立记录子阶段 | Discovery、必要的 Consolidation、每 Topic Organized→Novelty→Raw、按需 Conversation Need、Reflection；各自 parser/validator |

现有 `LlmDreamingEvidenceCollector.scanOrganized` 复用
`MemorySearchPrompt.scannerMessages`，即 Dreaming 的 Organized 子阶段也使用搜索
Scanner Prompt，但其结果由 Dreaming Novelty 继续判定。Dreaming 完整运行不是一次
Reflection 调用。

### 非目标

- 不读取、回显或保存本机现有 API key、密码、tokens、配置秘密。
- 不默认向任何外部供应商发送 canonical 对话。
- 不将“模型回答看起来不错”等主观评价混入 H1/H2/H3；后者必须是代码确定计算。
- 不用同名但替代的 prompt、只测 Reflection、硬编码预期输出或重写响应补救协议失败。
- 不承诺阈值：72 条 pilot 的 H1 与 H2 采样没有冻结的全库通过线；先报告逐样本结果和
  可复现的失败，再决定阈值。

## 2. 已确认的仓库约束与选择

1. 六类 Prompt/Schema 是 `context-core` public objects，Runner 复用它们即可，禁止另写一份。
2. Runtime 的 parser 和 stages 为 `internal`，不能由 integration-tests 的普通 test
   source set 直接访问；候选补洞必须能构造需要引用的 Organized/Raw/Conversation。
3. Runtime 已经实现 `persistence-sqldelight`，无需新增生产依赖。
4. Extraction `requestFor` 和 Reflection/Evidence `requestFor` 已 internal；Discovery 和
   Memory Map 的请求构造仍 private，批量/排序在 public-core 数据结构中完成。
5. 现有 B2 报告 `evals/results/offline-v1.json` 是 deterministic fixture report，保持
   `semanticQualityEvaluated=false` 和 `realModelResultsPath=results/real-model-not-run-v1.json`。
6. 目前工作区是 clean HEAD；实现前重新检查并保留其他人的改动。

### 选择的接缝：Runtime test fixtures + integration report

在 `context-runtime` 开启 Gradle `java-test-fixtures`，新增
`src/testFixtures/kotlin/com/github/noahshen/alcyone/context/memory/realEvaluation/`。
该 source set 与 Runtime 同 module，能够直接调用 internal stages/parsers，但仅在
`integration-tests/context` 的 `testImplementation(testFixtures(projects.context.runtime))`
可见，不进入生产 jar 或 public SDK。

不要再创建重复的 production `RealEvaluation*` 实现；不能让测试 fixture 改生产
internal API 为 public。fixture 入口只接收已加载配置、冻结输入、唯一 run ID、输出目录，
返回与协议无关的 JSON-safe DTO/JSON。数据集中的 source/oracle 只在评测报告测试读取。

如果 Gradle test fixtures 因 Kotlin explicit API 或 transitive Core 类型而不可用，回退
为 Runtime 单独 `realEvaluation` source set + `JavaExec`，不扩大生产可见性；不能以此
为由另写协议。先验证最小 empty fixture 编译，再实现 runner。

## 3. 总体数据流与执行模型

```text
canonical manifest/data (readonly)
  + 外部、独立 eval YAML (secret reference only)
       |
       v
Runtime test-fixture real runner (opt-in)
  seed isolated SQLite snapshot -> production request builder/stage
  -> same LlmBudget/HttpOpenAiLlmClient/parser/ID validator
  -> structured observations, failure metadata, timing, attempt
       |
       v
integration-tests RealModelChainEvaluationTest
  offline H1/H2/H3 formulas + response protocol classification
  -> evals/results/real-model-<runId>/observations.json
  -> evals/results/real-model-<runId>/report.json
  -> non-secret acceptance record
```

- 六类 Topic 各自从 canonical 输入建立隔离 SQLite，测试之间不串 DB。Dreaming 按
  fixture `batch` 组织；不得把多个 Oracle 的 Conversation/Raw 拼成不同输入。
- 每个样本每条实际请求记录一个 `requestId`、父 `sampleId`、substage、attempt 和时序；
  生产 `RetryingLlmClient` 不在一次观测里吞掉多次 attempt。包装 `LlmClient` 或
  transport listener 在请求前后增加记录，记录前不能改变重试语义。
- Runner 不因非法 JSON/ID 而自动 repair、重抽 prompt 或选择另一个候选。生产已有
  retry 可以发生，但必须记录每次原始响应的分类，不得用最后成功覆盖失败历史。
- `finish_reason=length`、预算拒绝、网络失败、非法 JSON、非法引用均按协议失败处理；
  不让外层笼统 failure 变成语义 0 分，也不让它消失于 macro 平均。
- 批内失败策略固定：Scanner 生产要求任一批失败则整样本协议失败；Dreaming 子阶段失败
  后终止该 topic 的剩余请求，样本标为协议失败；其他独立 topic 是否继续由 `--continue-on-error`
  控制，默认停止、仍写出已完成记录。不得把半条链标为完整成功。
- 默认 max requests=1（只做 preflight）；完整运行须显式 `--all-samples` 和
  `--max-requests=<positive-int>`，没有上限不允许运行。默认 max attempts=1，避免真实
  provider 自动重试造成无法预测的发送量。真实运行成本由调用方负责。

## 4. 产物契约

运行目录只由调用者指定的、run ID 唯一的 `evals/results/real-model-<runId>/` 生成。
若目录已存在或非空，拒绝覆盖；run 成功后通过验收记录链接具体 run，不改 canonical
offline report 的语义字段。run ID 至少包含 UTC 时间、模型别名和 git SHA，不使用秘密。

### `observations.json`

稳定 schema（初版 `schemaVersion=1`）至少包含：

```json
{
  "schemaVersion": 1,
  "runId": "20260804T120000Z-model-sha",
  "status": "COMPLETED|PARTIAL|FAILED",
  "datasetVersion": "ct31-v1",
  "manifestSha256": "...",
  "gitCommit": "...",
  "configSha256": "...",
  "model": "non-secret-model-id",
  "endpointHost": "example.invalid",
  "startedAt": "...",
  "completedAt": "...",
  "samples": [
    {
      "sampleId": "extraction-001",
      "task": "EXTRACTION",
      "status": "COMPLETED|PROTOCOL_FAILURE|SEMANTIC_FAILURE|INFRASTRUCTURE_FAILURE",
      "attempts": 1,
      "elapsedMs": 1200,
      "requests": [
        {
          "substage": "EXTRACTION",
          "sequence": 0,
          "promptSha256": "...",
          "schemaSha256": "...",
          "promptTokens": 1200,
          "responseTokens": 250,
          "totalTokens": 1450,
          "finishReason": "stop",
          "status": "COMPLETED",
          "errorCode": null,
          "promptRef": null,
          "responseRef": null
        }
      ],
      "parsedOutput": {},
      "scannerRanking": [],
      "rerankRanking": []
    }
  ]
}
```

具体类型按 Topic 扩展；`parsedOutput` 只能是已由生产 parser/validator 接受的语义字段
及 ID，不含原始正文、Authorization、完整 URL。`promptRef/responseRef` 预留给人工批准的
另地安全审计，默认 null；canonical 数据的正文保留在 canonical，不复制进运行报告。
原文 exception message 不可直接落盘，因为可能带 body；统一 errorCode + 已过滤的类别。

### `report.json`

由 integration report 从 `observations.json` 生成，包含：

- run provenance、数据集/代码/配置 SHA、request totals、duration、attempts；
- 每 Topic 每样本 `protocolStatus` 与 `semanticStatus`，不能由 null/0 混淆；
- H1 的 recall/critical recall、动作与 target、source turn coverage；H2 的 Map 必需 ID
  coverage、wrong ID、内容点覆盖；H3 的 scanner recall/critical recall、MRR、final recall
  与 expected order；
- Dreaming 的 topic count、每阶段 status、evidence IDs、reflection count 和语义逐条对照；
- 全局 `semanticQualityEvaluated=true` 仅在至少一条真实请求成功解析且进入语义计算时成立；
  所有请求均未发出/仅 preflight 时仍为 false。它不等于质量 PASS。

沿用 `OfflineEvaluationHarness` 的 SearchObservation、macro、H1/H2/H3 formulas；将真实
ranking 转为同一 `SearchObservation`，scanner/rerank 之间严格检查输入边界。新逻辑应
拆为 `RealEvaluationMetrics`，不在 `buildOfflineReport` 偷换 real 状态。

### `acceptance.md`

固定区分以下三句：

1. 代码/fixture 测试是否通过；
2. 真实运行完成了多少样本、多少请求、多少协议失败；
3. 语义是否被评测、是否达到预先约定阈值（当前没有全库冻结阈值，故无自动质量 PASS）。

无密钥或无 `--execute` 只能生成 NOT_RUN 结果，不生成伪真实报告。

## 5. 六类冻结执行计划

### A. Extraction（6）

1. 把 `input.turns[]` 按 sessionKey 和 timestamp/id 原序写入隔离 DB，保持消息 role/body、
   PENDING/EXTRACTED，不能全部重置为 PENDING。
2. 用 `DefaultPreExtractionContextBuilder` 对每条 target 所属 session 取真实窗口。如果
   fixture 样本已经是完整窗口，则在评测 builder 中调用 production
   `requestFor(ExtractionContext)`，保留其中 status；DB builder 的 snapshot 测试另列。
3. 复用 `DefaultExtractionRunner` 的 parse/validate，不 commit canonical 数据。
4. H1 对照 sourceTurnIds、requiredFacts、forbiddenFacts、timeAnchors。当前 canonical
   H1 为字段存在性结构 oracle，不是任意文本模糊匹配；若需语义文本质量，另列 human review。
5. 特别记录 target/history 混序、多 target、合法 empty；不为 empty fixture 强行发请求。

### B. Candidate Scanner（18）

1. 由 `input.raw` 和全部 `input.candidates` 建立 Organized 快照；使用
   `OrganizationCandidatePrompt.messages(raw,batch)` 与 Runtime planner。
2. 不把所有候选自动截成 K；K 只属于报告指标。超预算按真实 TokenBatchPlanner 分批，
   观测各 batch，候选集 union 后维持生产 ID 顺序。
3. 将 `SearchObservation` 拆成 scanner 专属指标或用同一 harness 的 scanner 部分，不能把
   scanner 输出当作 rerank 输出。记录 qrels 中 expectedEmpty、irrelevant candidate。
4. 未召回、非法 ID、重复 ID、unknown ID 各自可辨；合法遗漏是语义 miss，不是协议失败。

### C. Governance（12）

1. 用 `input.raw` + 全部 `input.candidates` 调用 `LlmGovernanceService.decide`。
2. 对照 expectedAction、allowedTargets/forbiddenTargets、requiredContentPoints、
   forbiddenRegressions、uncertaintyRequired。
3. NEW 的 target/ID 语义按 production validator，不把评测者自己推断的 ID 塞入决策。
4. 对 MERGE/UPDATE，target 必须在 allowedTargets 且不在 forbidden；内容点与不确定性
   标为 `REVIEW_REQUIRED` 如果 canonical oracle 只有字段存在性。不可用字符串“含关键词”
   假装语义覆盖。

### D. Memory Search（6）

1. query/domains 与完整 candidates 交给 `MemorySearchService`；不自行先取 oracle expected
   IDs 作为 scanner 候选。
2. 分别记录 scannerRanking、rerankInputIds、rerankRanking。scanner 空输出按生产路径跳过
   rerank；qrels 非空时 Recall=0，qrels 为空时该指标 N/A。
3. 复用 SearchObservation 验证 duplicate/out-of-set/unjudged ID、rerank 增删越界。
4. H3 报告 recall@3、criticalRecall@3、MRR@3、finalRecall@3 及顺序状态，不用平均掩盖
   单条协议失败。

### E. Memory Map（6）

1. 全部 `input.memories` 构造合法 `MemoryMapSnapshot`；不凭 `input` 猜 generatedAt。
2. 用 `MemoryMapGenerator`（固定 Clock）或共享 `generateFromSnapshot` internal 接缝，
   禁止测试复制请求构造。
3. H2 对照 requiredDomains、requiredNavigationIds、forbiddenClaims、requiredContentPoints。
4. wrong ID/未列入 domain 为协议失败；缺 navigation ID 是语义失败。内容点不能由合法 JSON
   自动算满分，输出 machine result 与 review status。

### F. Dreaming（24）

一个 fixture `batch` 是一个样本；全部 substage 按以下顺序记录：

1. `DreamingTopicDiscoveryPrompt.messages(batch)`，解析并校验 sourceTurnIds。
2. 当 topics>=2，调用 consolidation；校验没有凭空新增/合并后 source 越界。
3. 每 topic 固定 topic turns + 全部 Organized 快照，调用 organized Scanner。
4. 有相关 Organized 时调用 Novelty；FULLY_COVERED 跳过 Raw/Conversation/Reflection。
5. NEEDS_EVIDENCE 时 Raw Scanner；Raw 反查 Organized 后保留 ID 集合。
6. 有 Raw 时 Conversation Need，trace historical turns；再按完整 Evidence 调 Reflection。
7. 分别输出每 topic 的 decision、organized/raw/historical IDs、reflection source IDs。

H1/H2 对照 expectedDecisions、expectedEvidenceIds、expectedReflectionCount、requiredPoints、
forbiddenClaims；决策次数与 Reflection 数量不同就是语义差异，source ID 越界是协议失败。
Fixture 没有可构造 evidence 时必须新建真实可构造的 Organized/Raw/Conversation fixture，
不能改已有样本 oracle 或仅发送空列表。建立阶段补洞/数据完整性是本实现的前置子任务。

## 6. 安全、配置与外部授权

1. 独立 YAML 仅放本地非 tracked 路径；复用 `ContextConfigLoader.load`，支持 `${ENV_NAME}`。
   日志与结果只保存 model、redacted endpoint host、config/prompt/schema SHA。
2. `--execute` 之外再要求 `ALCYONE_EVAL_EXTERNAL_LLM_APPROVED=YES`，两个都缺即 NOT_RUN。
   显式传 `--config`；不搜索 home、不读取其他项目、不自动发现凭据。
3. 默认 dry-run 构建请求、校验 prompt/schema/config 和 DB，但不调用 HTTP。dry-run 的
   结果只能是 `DRY_RUN`，token 为估算而非真实 usage。
4. 限定 endpoint allowlist、最大请求数、最多样本数、maxAttempts=1 和总 deadline；每个
   run 开始前打印非秘密配置摘要，外部发送由用户批准。调用工具沙箱需网络权限时正常
   请求审批，不绕过权限。
5. canonical 输入含个人/项目对话，运行前必须明确数据接收方与保留政策；必要时先另建
   脱敏/合成 pilot 数据集，不能修改 canonical 的哈希后仍称同一 ct31-v1。
6. 每个 Topic 先一条 preflight，再决定是否扩大；若认证、Schema、上下文、端点错误，
   停止整组并记 INFRASTRUCTURE_FAILURE，不重试耗尽预算。

## 7. 实施顺序与测试

### Task 0：确认/补齐可执行 fixture

- 编译最小 Runtime test fixture；确认 Gradle JUnit 不默认发现真实 runner。
- 为 Extraction/Organization/Search/Map/Dreaming 输入建立 DTO -> Core API 模型转换；验证
  domain、time、Raw/Organized source links、conversation FK。
- Dreaming 先验证 24 条 fixture 的 turns/candidates/decision 分支能构造，不足时在单独
  eval-fixtures 增补，保留原 manifest/oracle 不变。

### Task 1：复用 Runtime 请求与阶段

- 优先只读 `requestFor` 接缝：`LlmDreamingTopicDiscoveryStage` 暴露
  `internal discoveryRequestFor/consolidationRequestFor`；Memory Map 抽
  `generateFromSnapshot` 或同 module fixture request+parse，仍用同一 validator。
- Extraction 复用 runner 的 parse/validate 函数；避免运行 commit 和 checkpoint。
- 给 MemorySearchService 增加内部 observation hook，或让 fixture 复用拆分后的
  scanner/rerank execute 函数。不得返回 oracle 推断的 rerank 输入。
- 用真实 Ktor client + RecordingLlmClient 记录 usage/finish/error；不要 fake HTTP 通过
  real test。

### Task 2：真实 runner 与产物

- 实现 opt-in CLI/Gradle JavaExec：config、runId、output、topics、sample、execute、
  all-samples、max-requests、continue-on-error、deadline。
- 每 Topic 至少覆盖：合法 JSON、合法 empty、非法 JSON、unknown ID、截断、provider error。
  负例使用 fake HTTP，仅验证协议分类，不算真实语义样本。
- 保证取消原样传播、资源关闭、临时 DB 删除；不得网络发生在 DB read transaction 内。

### Task 3：H1/H2/H3 报告

- 新增 `RealModelChainEvaluationTest` 读取 runner 结构化 observations，实现真实 H1/H2/H3。
- 使用已有 SearchObservation 和宏平均；每个 fixture 都保持独立候选全集和 judged IDs。
- 加真实 observations 的 golden test：无输出、空输出、合法遗漏、乱序、非法 ID、重复 ID、
  provider failure 的状态区别。不得断言所有语义都为 PASS。
- B2 `EvaluationDatasetIntegrityTest`、OfflineEvaluationReportTest 保持通过；manifest hash
  不变时 offline report 必须 byte-for-byte 不变。

### Task 4：运行/验收

1. `./gradlew :context:runtime:compileTestFixturesKotlin :integration-tests:context:test --tests '*evaluation*'`。
2. `./gradlew test` 或全量受影响的 runtime/integration tests，确认无默认网络。
3. dry-run 输出 72 条（或明确选定子集）的 request plan、prompt/schema hashes 和预算。
4. 经用户另行批准配置/数据外发后，每 Topic 1 条 preflight，检查协议。
5. 有可用 endpoint 才跑全量，并记录实际 runId；不可用则交付 NOT_RUN，不伪造结果。
6. `git diff --check`、全量 fixture tests、manifest/oracle hashes 和 secret scan 后才验收。

## 8. 验收判据

- [ ] 六类均从 canonical 冻结输入执行当前生产 Prompt/Schema/Runtime validation，而非六个
      手写 HTTP 请求。
- [ ] Dreaming 覆盖六个实际子阶段及 FULLY_COVERED 跳过分支。
- [ ] 真实 observations 与 offline report 分离，H1/H2/H3 逐样本可重算。
- [ ] 原 72 条 fixture integrity、27 条旧 report tests、无网络默认测试全通过。
- [ ] 运行记录 request count/attempts/duration/config/model/prompt/schema hashes，协议失败
      与语义失败不混淆。
- [ ] 无 secret、原始鉴权头、完整 endpoint、真实 prompt/response 原文写入 git 或日志。
- [ ] 没有真实密钥/外发批准时报告明确 `NOT_RUN`，不声称 B3 的真实模型部分完成。

## 9. 当前结论

本次只完成技术核实和方案，不执行任何外部模型请求，也不提供现有凭据。当前仓库的
真实模型结论仍是 B2 的 NOT_RUN；本文件不是“真实六链路已通过”的验收记录。
