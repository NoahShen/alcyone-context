# CM4 Fake 结果（cm4-v1）

**日期**：2026-11-09  
**real-model: not-run**

## Fake 边界

- 使用本机 JDK HTTP stub 与真实 Ktor client。
- Fake 只证明请求路由、协议信封、预算/结构边界、ID 权限边界、确定性与生命周期。
- Fake **不**证明真实语义相关性、真实摘要质量或 Provider 兼容质量。

## 覆盖结果

| 类别 | 结果 | 证据 |
| --- | --- | --- |
| Map 空库 | 0 LLM；确定性空 Map | CT18/CT19 组件与 E2E |
| Map 单条/多条 | 结构合法；真实 Domain 子集；Front Matter 严格可解析 | `Cm4MapInterleavingEndToEndTest` |
| Map 10 样本 | 输入/约束已归档；Fake 未做语义通过判定 | `memory-map.jsonl` |
| Search 明确相关 | 动态真实 ID 选择 | `Cm4SdkJourneyEndToEndTest` |
| Search 多候选 | 全局 Rerank 顺序可确定 | CT20 B1 Service 测试 |
| Search 无匹配/空候选 | 空数组与 0 后续 LLM 边界 | CT20 B1/CT20 B2 测试 |
| Search 10 样本 | 输入/约束已归档；Fake 未做语义通过判定 | `retrieval.jsonl` |
| 请求序列 | Extraction → Governance → Map → Scanner → Rerank | `Cm4SdkJourneyEndToEndTest` |
| close 后请求 | 0 新增 | 三个 CT21 E2E |

## 结论

Fake 固定集与全景链通过的是**协议、确定性、结构、预算和生命周期**约束。真实模型语义质量：**not-run**。

