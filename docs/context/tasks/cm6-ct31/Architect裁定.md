# CT31 B1 Architect 裁定

## 本次修改（2026-11-12，B1 DONE，B2 离线方案批准）

- 独立核对 [方案核对](方案核对.md)、[样本盘点](样本与证据盘点.md)、原始 Extraction/Search/Map/Dreaming 样本及技术设计 §10.5～§10.6；六类固定集 **4/0/0/10/10/4**、真实模型全部 not-run、两项元数据不一致均有直接证据。
- **CT31 B1 APPROVED / DONE；放行 B2 离线 canonical 数据集、严格加载/指标工具与报告。** 不调用真实模型，不修改生产/公共 API/Schema/SQL/依赖/默认 Runtime，不启动 CT32，无 Git 写操作。
- 72 条仅为覆盖审计下限，不是质量阈值；B2 DONE 不等于真实模型质量通过或 CT31 DONE。

## D1～D6 裁定

- **D1 批准**根 `evals/` 作为 `ct31-v1` canonical 版本快照；旧文件只读保留 provenance，不原地修改。新 canonical 与历史文件的角色必须在 README 明确，后续只能以新版本演进，不双头更新同一版本。
- **D2 批准**每类最低 12、总 72、净新增 44 的工程工作量下限；每项须绑定明确风险/边界，不适用的 H1/H2/H3 记录理由，禁止凑数或用数量声明语义充分。
- **D3 批准**canonical Extraction 来源 ID 与 Dreaming Profile/model 规范化；旧样本 SHA 保留。旧自由文本约束转结构化 oracle 必须由 Lead 逐条人工核对，尤其 Search qrels、K、Map requiredFacts，不能自动拆句后当人工真值。
- **D4 批准**方案 §5 的 16 个精确写路径及本任务交付/初验/复核文档。不得改生产、既有测试、Schema/SQL、Gradle 依赖、旧样本、默认 Runtime；需要额外接缝立即停止。
- **D5 批准**H1/H2/H3 逐样本人工裁决与独立 criticalErrors，不用聚合分抵消。语义匹配不使用字符串相似度冒充事实判断；B2 人工 oracle 审查与模型输出人工评分是不同事项。
- **D6 批准**B3 默认 LOCKED；未获用户 Provider/Profile、外发范围、费用上限和明确授权时不运行。真实模型 not-run 不阻塞 B2 技术验收，但阻塞真实模型质量通过及 CT31 整体 DONE；CT32 LOCKED。

## D7～D10 验收收紧

- **D7 Search 分阶段**：同一 query 的 SCANNER 与 RERANK 输出/判定分别记录。Scanner 报 Recall@K/CriticalRecall@K；Rerank 报 MRR@K、关键项首位/顺序与最终 Recall@K。qrels 为空时只评合法空结果，不进入 Recall/MRR 分母；K、候选数、分母固定入报告。
- **D8 离线工具真实性**：B2 harness 只读数据、已捕获输出和人工 judgment，不访问网络/密钥，不自动决定语义真值。用人工构造的测试输出验证公式、空分母、非法 ID、重复 ID、临界 K、缺失 judgment；这些自测输出不是模型结果，不计入 72 条样本。
- **D9 报告可复算**：`offline-v1.json` 可由 canonical 数据、manifest 与 judgment 重新生成；包含输入 SHA、公式分母、protocol/semantic 分栏、未评分原因，固定 `semanticQualityEvaluated=false`。不得写入时间戳、随机值或本机路径导致同输入不可复算。`real-model-not-run-v1.json` 不包含伪造指标。
- **D10 完整性**：严格 JSONL/manifest 解析、未知字段拒绝、稳定 ID 唯一、版本/SHA、expected/source/target/qrels 输入子集、Task/Profile 路由、合成/脱敏标记、每类数量与风险覆盖都有直接测试。`.schema.json` 与 Kotlin loader 的字段约束必须一致；不新增 JSON Schema 依赖。

## B2 实施顺序

由当前 Lead `b87b8183-b628-4276-80ef-286d4054470d` 委派唯一 Worker，分两个小交付、同一批 Architect 复核：

1. canonical 72 条数据、manifest/schema、loader/integrity 测试；先关闭 oracle 人工审查与两项元数据问题。
2. 离线指标 harness、公式反例/边界测试、可复算报告与 not-run 文件。

Worker 先跑目标测试与 Spotless，Lead 独立检查六类计数、provenance、报告复算及全部新增文件 SHA，再运行一次必要全量 `./scripts/check`；不得为同一冻结源码重复全量。测试增加量按实际 XML 记录，不预设通过。

## 未批准事项

真实模型调用、在线 runner、密钥读取、业务生产改动、公共 SDK/构建改动、WebDAV/恢复语义改动、CT32 与部署均不在 B2。B2 交付只证明数据集和离线工具可审查，不证明 Provider 兼容性或真实语义质量。
