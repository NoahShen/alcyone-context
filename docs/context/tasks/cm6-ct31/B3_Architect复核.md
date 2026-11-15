# CT31 B3 Architect 独立复核

## 本次修改（2026-11-13/14，真实 72 条首轮复核）

- 独立核对 `.local/ct31/production-full-72/` 的报告、逐样本结果、Judge 和数据库产物；首轮 72/72 已执行，但 **B3 R1 未关闭，质量门槛未通过**。
- 区分真实生产/模型失败与评测器错误：Map 导航标记断言无生产契约依据，Dreaming checkpoint 使用旧断言且 backlink 未运行 Organization，协议失败缺调用证据；这些计数不得直接解释为最终生产质量。
- 原始报告保持只读，修复后必须在新目录重跑。CT31/CM6 未 DONE，CT32 LOCKED，无 Git 写操作。

## 1. 已独立核实的执行与完整性

- 真实 smoke：`.local/ct31/production-smoke-ext-001/`，`ext-001` 为 REVIEW_REQUIRED，1 次生产调用；它不是 PASS。
- 全量：`group/free`、`JSON_SCHEMA`、单次尝试、`PRODUCTION_COMPONENTS`，72 个唯一 ID、六类各 12 条，`complete=true`，`NOT_RUN=0`。
- 汇总重算：**PASS 19、FAIL 26、REVIEW_REQUIRED 13、PROTOCOL_FAILURE 14**；生产调用 77，input/output token **54,710 / 65,156**，无缺失 usage、无截断。任务分栏与 Lead 摘要一致。
- 磁盘有 72 个样本目录、72 `result.json`、72 `judge.json`、12 个 Dreaming `context.db`、77 个成功生产调用记录。源 manifest 实际 SHA 与报告一致：`13e2d0679f573d8361a03d290aa1c0ffe98f5ceadd55e50c77815cbefcbffd0a`。
- 固定首轮基线：`report.json` SHA `beb15247a78d6d5e8d9ccc1f6bd2ddda1c154e97ec5e926ae8f6b7ebdcf95302`，`summary.md` SHA `1b2c48617b5ae856665915570b723d081ded6526babddd26c54aed8995fc2432`。
- Judge 按实际字段 `safeError` 重算为 **48 次 INVALID_RESPONSE、7 次有效**；7 次有效中 6 个是 Governance 判决、1 个为空 Map。`semanticQualityEvaluated=true` 只表示存在部分判决，不代表 Judge 或人工复核完成。

## 2. 阻断项

### R1-A：协议失败和 Judge 失败缺少可审计原始证据

14 个生产协议失败样本均无 `production-call-*.json`，且 `calls=[]`。当前 `ProductionRecordingClient` 只在 `delegate.complete()` 成功返回后记录，无法证明零网络调用，也看不到 Provider 已拒绝的原始正文。48 次 Judge `INVALID_RESPONSE` 同样只有安全错误类别，没有原始响应。

不得通过把 `calls=0` 解释为“未发送”来关闭问题。须区分 attempted/succeeded/failed/cancelled 调用；若现有 `LlmException` 的安全边界有意不携带正文，原始抓取必须使用仅评测启用的 Provider 观察接缝，不得把正文塞进公共异常、日志或默认生产统计。

### R1-B：Dreaming 评测边界错误，首轮三条 FAIL 不能原样作为当前生产结论

1. 首轮报告 `dream-012` 为 `checkpoint=5013 expected=2`，把 checkpoint 与窗口条数比较；当前 `DreamingEvaluation.kt` 已改为与最大持久化 Turn ID 比较，说明首轮产物不是当前代码的最终复验。报告必须记录实际运行代码/入口 SHA，避免版本混用。
2. `DreamingCommitter` 的真实职责是原子写 Raw(DREAMING)+Pending+checkpoint；Raw→Organized 边由后续 `OrganizationCommitter` 建立。当前 Dreaming runner 没运行 Organization，却立即要求每条新 Raw 有 Organized backlink，属于装配与断言边界错误。
3. 修正上述评测错误后，`dream-008` 的零 Topic/Insight、`dream-009` 有 Topic 但零 Insight 仍是待复核的真实输出，不得一并改成 PASS。

### R1-C：Memory Map 11 条 FAIL 主要使用了未冻结的字面导航契约

生产 `memory-map-v1.md` 要求自然语言 navigation 和真实 Domain，不要求 `#MemoryId` 或 `@Domain`；Renderer 自行生成标题和 Domain。`map-001` 等真实输出已经含正确 ID/Domain，却因缺 `#`/`@` 被 FAIL。不得为迎合评测器而修改生产 Prompt。

R2 应按已冻结生产契约检查非空导航、真实 Domain、必要 ID 的自然语言引用和禁止虚构；大小写以 canonical Domain 为准。首轮 Map 计数只能标为“受错误断言污染”，不能计入最终生产失败率。

### R1-D：真实生产/模型问题仍需修复与复验

- Candidate Scanner 的 `sc-005/006/007/010` 有 forbidden ID，后两者还违反 expected-empty；Memory Search `ret-006/007` 有召回或 rerank 问题。这些是有效确定性反例，不能由 Judge 失败抵消。
- Governance 五个样本 Candidate Scanner 成功但只记录一次生产调用、Pending 保留且未提交。`DefaultOrganizationWorker` 会吞普通单条异常，当前 runner 丢失该阶段错误；必须暴露实际失败阶段/原因，再判断是 Governance 输出、候选/SourceGuard 还是 Committer 问题，禁止直接把 NONE 当语义 FAIL 后结束。
- Extraction `ext-012` 有 `sources_grounded` 确定性失败；其余语义结论需可靠 Judge/人工复核。
- 13 条 REVIEW_REQUIRED 及 PASS 校准抽样尚未完成 H1/H2/H3 人工复核。不能用同模型 Judge 替代人工验收。

## 3. R2 放行边界

1. Lead 组织一个 Worker，只修改 CT31 evaluation adapter/runner/test/文档，以及经单独技术确认的最小评测观察接缝；canonical 样本、oracle、旧报告、默认 Runtime 和业务算法冻结。需要改生产 Prompt/Parser/Committer 时先提交由具体样本支持的独立方案，不与评测器修复混合。
2. 补协议失败、调用取消、Governance 普通异常、Dreaming checkpoint/backlink、Map 契约和运行代码 SHA 的回归测试；真实失败产物不得覆盖或删除。
3. 先做离线定向和 `ext-001` 新目录 smoke；再针对 Judge 做最小协议诊断，获得可审计原始失败证据。禁止为追求 PASS 放宽 Parser、忽略非法 JSON、自动无限重试或改变样本/oracle。
4. 评测器可信后使用新目录重跑受影响样本；最终全量必须重新生成，不用旧、新报告拼接 72/72。语义修复与工具修复分开交付和复核。

**当前裁定：B3 IN_REVIEW / R1 OPEN，首轮真实运行完成但未通过。CT31/CM6 未 DONE，CT32 LOCKED。**
