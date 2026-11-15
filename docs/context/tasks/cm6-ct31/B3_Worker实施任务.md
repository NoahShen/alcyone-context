# CT31 B3 唯一 Worker 实施任务

## 本次修改（2026-11-13，失效 Worker 的技术替换）

- 本任务是自包含的直接实现指令，不是审计或方案任务。新 Worker 直接修改下列文件并运行离线测试，不创建或管理任何 Agent，不调用真实模型，不读取密钥。
- 保留既有未提交实现和其他工作区改动；禁止 Git 写操作、reset/checkout/clean/stash，禁止修改 canonical 数据、manifest、离线报告或生产业务算法。

## 写集合

仅可改：

- `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/evaluation/ProductionEvaluationAdapters.kt`
- `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/ProductionEvalRunner.kt`
- `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/ProductionDreamingEvalRunner.kt`
- `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/integration/evaluation/RealModelEvalCli.kt`
- 同目录 `RealModelEvalSupportTest.kt`、`EvaluationProtocolTest.kt`，或新增一个 B3 专属测试文件。
- 确有必要时 `evals/schema/real-model-report-v1.schema.json`、`evals/README.md`。

## 必须完成

1. `ProductionSearchExecutor.search()` 先完成 `service.search()`，再读取 observer 捕获的非空 Scanner IDs 并构造 observation。加真实 SQLite + scripted client 测试，证明 Scanner/Rerank 两阶段及顺序没有被观测器改变。
2. Dreaming 导入保留 `sample.input.toRawMemories()` 的 canonical raw ID、ConversationTurnRef/sessionTurnId，不再添加 session 前缀。按实际导入 raw 数写 `organization_pending`；按 `organized.rawMemoryIds` 插入输入 Evidence links，不通过 Prompt 文本反推。空 turns 返回保留实际 turns/pending/DB 事实，不硬编码全零。加真实 SQLite 正反例。
3. 普通 Provider/解析/DB 异常时保留已获得的 production calls 和 raw artifacts，写逐样本 `result.json` 与不完整 report。取消时尽力写 NOT_RUN/未完成记录后继续抛出，不吞取消、不变成语义 PASS。先成功响应再 parse 失败也须留证。
4. `summary.tasks` 改为去重、排序的 task 列表或明确 `taskCounts`；可新增独立 Judge 调用/usage 统计。当前 `modelCalls` 不含 Judge，不得修改成“修复 Judge 混入”的错误叙述。report schema 与实际输出必须有正反例测试，保留原子写。
5. 完成后运行 `./scripts/dev gradle spotlessApply` 和 evaluation 定向测试，报告实际修改文件、命令与 XML 计数。真实模型、72 全量和最终质量结论由 Lead/Architect 负责。
