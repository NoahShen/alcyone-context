# CT31 Offline Evaluation Dataset

## 本次修改（2026-11-12，B2 R1 定点修复）

- Search 区分未采集、真实空输出、合法已知无关项、顺序语义失败与协议失败；qrels 非空的空输出计算 0 分。Scanner/Rerank 候选、judged/unjudged、顺序和阶段边界有正反例。
- `gov-007` 改为可持久化灾备区域事实；Scanner 两处补回高召回相关候选；`map-003` 删除输入未提供的变化原因，并在 provenance/测试中记录人工 oracle 修正。
- 定向 evaluation **27/27**，零失败/错误/跳过，manifest 与报告已重算；真实模型 NOT_RUN，语义质量 NOT_EVALUATED。

## 历史修改（2026-11-12，B2 数据与校验修复）

- 六类 `ct31-v1` canonical 数据集固定为 72 条（28 imported/44 synthesized），报告可确定性复算，真实模型保持 NOT_RUN。
- 修复 Governance 合法动作、Search Domain 模式、Map required ID/Domain 导航约束与 Dreaming DATA_ONLY/正则完整性；离线 evaluation 17/17 通过。
- 本目录不包含密钥、真实模型结果或个人数据；Fake 与结构报告不构成模型质量证据。

This directory is the versioned, synthetic/desensitized offline evaluation package for the six
Context LLM tasks. It is deliberately separate from production resources and from any real-model
claim.

## Frozen boundaries

- `manifest-v1.json` binds every dataset line, Prompt, Schema, Task/Profile route, output budget and
  SHA-256. The tracked source files used by imported samples are hashed independently.
- `datasets/<task>.jsonl` contains exactly 12 samples per task (72 total). Every line has a stable ID,
  provenance, task-specific input and manually reviewed oracle, criticality and risk tags. Extraction,
  Scanner, Search, Map and Dreaming include legal empty results. Governance only emits the production
  actions NEW/MERGE/UPDATE; upstream Extraction/Scanner owns no-candidate filtering, so Governance does
  not fabricate a NOOP/expectedEmpty action.
- `results/offline-v1.json` is reproducible from the manifest, datasets and checked-in fixture
  resources. It reports protocol and
  semantic evidence separately. No Fake output is treated as semantic model quality.
- `inputCoverage.retrieval` records all 12 canonical K, candidate-count and qrel-size observations.
  It is input coverage, not a score. Search aggregates remain `NOT_APPLICABLE` because no retrieval
  outputs were captured.
- `fakeReplay` is rebuilt by verifying the canonical Dreaming imported provenance SHA, locating the
  fixture manifest from that source, checking `scenariosFile`, unique scenario IDs, `expected` and
  stage-output JSON structure. The checked-in canonical report must be
  `COMPLETED_FIXTURE_REPLAY` with 4 scenarios. Missing resources, hash drift, malformed manifests or
  malformed successful stage outputs produce `NOT_RUN`, never semantic evidence.
- `results/real-model-not-run-v1.json` is the explicit B3 boundary. CT31 B2 does not call a model,
  network endpoint, provider or secret store.

## Real-model runner (B3)

The runner defaults to a network-free dry run. The following three flags must be explicitly
enabled together for an external request; approval covers the synthetic/desensitized fixture
content, not arbitrary repository files. Raw prompts/responses and API keys are not committed.
The local ignored configuration is `.local/ct31/model.yaml` and uses `${ALCYONE_EVAL_API_KEY}`.

```text
scripts/dev gradle :integration-tests:context:contextRealModelEval \
  -PcontextEvalLive=true -PcontextEvalExternalApproved=true \
  -PcontextEvalProduction=true -PcontextEvalSample=ext-001 \
  -PcontextEvalOutput=.local/ct31/production-smoke-ext-001
```

For a full run, replace the sample selector with no task/sample/limit selector. The task, sample,
and positive limit properties can be combined. Omitting all selectors selects exactly 72 samples;
`complete` and `selectedSamples/sampleCount` distinguish that full run from a smoke run. Use a new
output directory for each run; existing reports are never overwritten. `contextEvalProduction=false`
retains the offline protocol baseline and is not production end-to-end evidence.

The actual evaluation profile is `real-eval`, model `group/free`, with JSON Schema requested from
the supplied gateway. Its 32,768-token window is a local template until the provider confirms it;
it is not a provider capability claim. The current Ktor client does not set temperature, so reports
record `temperature: null` (server default), not a fabricated fixed value. There is one production
attempt, including both stage outputs and judge calls. Missing usage is counted separately.

Production extraction, organization, scanner, search and map use production executors and a real
SQLite database. Dreaming uses the production DreamingRuntime/Pipeline, its real SQLite driver and
commit assertions. Evaluation-only SYSTEM turns are represented as non-target USER turns with
their original content, order and ID; the public production ChatTurn API has no SYSTEM role.

The semantic judge is an additional controlled review, not an independent model, human blind
review, or real-user study. It uses the same production-configured provider, isolated judge prompt
and strict `{"pass": boolean, "reason": nonblank}` response. `JUDGE_UNAVAILABLE` produces
REVIEW_REQUIRED. No execution, production protocol failure, missing required output, and missing
required Dreaming insight cannot become semantic PASS. Safe reports include prompt/schema hashes,
request hashes, sanitized errors, usage and timing; raw observations remain in the ignored output
directory. Reports conform to `schema/real-model-report-v1.schema.json` (not the fixed offline
`NOT_RUN` schema).

### Current execution status (2026-11-13)

Offline protocol/fixture tests: **37/37 passed**. Dry-run selection: **72/72 samples**; the
production `ext-001` smoke selection is also valid without network access. The live smoke remains
**NOT_RUN**: the execution safety reviewer rejected transmission of internal prompts and derived
outputs to the configured external gateway. No live result, provider compatibility, semantic
quality, or production readiness is claimed. CT31 remains incomplete and CT32 remains locked.

## Validation

Run the focused suite through the repository bootstrap:

```text
scripts/dev gradle :integration-tests:context:test \
  --tests 'com.github.noahshen.alcyone.context.memory.integration.evaluation.*'
```

The loader rejects unknown fields, duplicate/missing IDs, dangling source/target/qrels references,
private data flags, route/task drift and manifest hash drift. Search metrics keep Scanner and
Rerank separate; zero denominators are reported as `NOT_APPLICABLE`, never zero quality. Fixture
replay is structural protocol evidence only and cannot be promoted to model-quality evidence.

## Repairing or adding a sample

1. Add a synthesized line or copy a tracked source line without changing its evidence meaning.
2. For an imported line, retain `provenance.sourcePath` and `provenance.sourceSha256`; record the
   canonicalization in `transformNote`.
3. Update the manual oracle, not a model answer. Model outputs belong in a captured-result file and
   must carry a protocol status and human judgment outside the canonical samples.
4. Regenerate the manifest hashes and offline report, then run the integrity tests. Never update a
   hash merely to silence a semantically changed source.
5. For Dreaming fixture changes, keep the canonical imported `provenance.sourceSha256` synchronized
   with `scenarios.jsonl`. A changed fixture manifest path, scenario ID, expected block or malformed
   success-stage JSON must fail replay rather than be silently skipped.

