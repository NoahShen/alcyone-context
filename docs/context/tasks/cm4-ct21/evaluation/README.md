# CM4 固定集评测说明（cm4-v1）

**日期**：2026-11-09  
**样本版本**：`cm4-v1`  
**规模**：Memory Map 10 条、Retrieval 10 条  
**real-model: not-run**

## 目的

本目录只保存 CM4 的高价值合成样本、人工语义约束与 Fake 运行证据，不建设通用评测平台，也不把 Fake 结果写成真实模型语义质量结论。

## 文件

- `memory-map.jsonl`：10 条 Map 生成输入与语义约束。
- `retrieval.jsonl`：10 条 Search Scanner/Rerank 输入与语义约束。
- `fake-results.md`：Fake Profile/Task 下的协议、预算、结构与确定性结果。
- `versions.md`：Prompt/Schema SHA、请求顺序、Fake 配置与样本版本。

## JSONL 字段

- `id`：样本稳定 ID。
- `version`：样本版本，固定 `cm4-v1`。
- `task`：`memory-map` 或 `retrieval`。
- `input`：自包含的合成脱敏输入。
- `semantic_constraints`：人工定义的语义约束。
- `expected_behavior`：预期结构/选择/排序行为。
- `forbidden_errors`：明确禁止的错误。
- `notes`：人工参考说明。

## 边界

- 所有内容均为合成、脱敏数据，不使用真实用户数据。
- Fake 只验证协议、预算、ID 权限边界、确定性和结构；真实语义质量未运行。
- 所有评测文档统一标注 `real-model: not-run`。

