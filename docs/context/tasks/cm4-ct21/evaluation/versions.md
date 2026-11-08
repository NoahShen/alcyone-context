# CM4 版本与可复现记录（cm4-v1）

**日期**：2026-11-09  
**样本版本**：`cm4-v1`  
**real-model: not-run**

## Fake Profile / Task

- Profile：`cm4`（本地 JDK HTTP stub）
- Model：`fake-cm4`
- Response format：`JSON_OBJECT`
- Tasks：`EXTRACTION`、`CANDIDATE_SCANNER`、`GOVERNANCE`、`MEMORY_MAP`、`MEMORY_SEARCH`
- API Key：测试专用占位值；stub 只记录 Authorization 是否存在，不保存原文。

## 请求顺序

全景链实际序列：

```text
EXTRACTION
→ GOVERNANCE
→ MEMORY_MAP
→ MEMORY_SEARCH_SCANNER
→ MEMORY_SEARCH_RERANK
```

空候选 Organization Candidate Scanner 为 0 请求；Map 空库启动检查为 0 LLM。

## Prompt / Schema SHA-256

| 资源 | SHA-256 |
| --- | --- |
| `extraction-v1.md` | `56d3278f4613499170d0cde9a06fb757ae45637b9579040c521289715849b2c8` |
| `extraction-schema-v1.json` | `041e7916957c75f8128ca7f2e2d049322945a0051b68236d7b86ffb680d563b0` |
| `organization-candidate-v1.md` | `da45adb19451f3e5ecd2dbab1ab0710189b73c996ec6c35cc84b1be1aa63eda1` |
| `organization-candidate-schema-v1.json` | `2ef03476aaab3c7f752bb7b3cb0bfd6c217b53d803f97ed0a274fd601b3d672f` |
| `organization-governance-v1.md` | `bce0dc53b2ba86cb5ec6edee5ba4c5843bbae128d6ce09d4ec342d89280c7d92` |
| `organization-governance-schema-v1.json` | `5fe5baf06a6f9d0824a7ae0ac67f8eac3e1d55e3b1e78ccf017d366ecddad384` |
| `memory-map-v1.md` | `e333f259071bb68bec37f2cabac61f1448c8d183742a8a4bc1e873e4987ce56d` |
| `memory-map-schema-v1.json` | `1b6b576d7446332d590b8cee52a5c4654c107e99197cf44cd46373eaec9478a3` |
| `memory-search-v1.md` | `171906ece4c4d1a84a9437b09d961fc60bd045e2c7e907259754f1f45c21c16c` |
| `memory-search-schema-v1.json` | `16f8de9c907ddf7750c65f70da8905e98176f9516ad517ccc2cfd58a0ea0a879` |
| `memory-search-rerank-v1.md` | `caa29c26a0eab7e2951b92a47afbf5bd8fee50f2422d8865849adb0e2a821b86` |
| `memory-search-rerank-schema-v1.json` | `162c2e0f2f22025cb31d7c634e71cd9c8ec4edefa923945a20347e03921ad662` |

## 结果边界

- Fake：协议/预算/结构/确定性证据已归档。
- 真实模型：**not-run**。
- 不得用本文件宣称真实语义质量通过。
