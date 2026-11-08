# CT14 Governance 方案裁定（D1～D10）

**日期**：2026-11-01
**状态**：**D1～D10 全部批准；CT14 B1/CT14 APPROVED / DONE；CT15 按用户暂停未启动**
**依据**：[CT14 任务书](../../milestones/cm3/CT14_任务书.md)、[CT14 方案核对](方案核对.md)

## 0. 本次修改（2026-11-01，Architect 裁定落档）

Architect 对 CT14 D1～D10 全部批准，并锁定契约与 B1 要求。本文件为裁定记录；实现前不改变 CT13/Schema/依赖/VFS。

## 1. 裁定结论

| # | 裁定 |
| --- | --- |
| D1 | 只做组件，不接 Factory/Impl/Config/Loader；生产接线归 CT16。 |
| D2 | Core：GovernanceAction/GovernanceDecision/GovernanceDecisionValidator/GovernancePrompt + Prompt/Schema 资源；Runtime：LlmGovernanceService/JsonGovernanceResponseParser；Core 不新增 Ktor/serialization。 |
| D3 | 非法决策复用 `LlmException(INVALID_RESPONSE)`，不新增公开错误码；错误消息脱敏。 |
| D4 | MERGE 提供 `domains` 即非法，空数组同样拒绝。 |
| D5 | domains 元素空白拒绝；重复稳定去重（保留首次顺序）；校验不通过 trim 改写原文。 |
| D6 | 空候选集合允许 NEW（信息可独立成立）。 |
| D7 | 预算超限整项失败，不截断候选、不调用 LLM、Pending 保留；`finish_reason=length` 失败，不接受部分 JSON。 |
| D8 | 仅 JSON_SCHEMA/JSON_OBJECT；缺 GOVERNANCE Task/Profile/PROMPT_ONLY → `INVALID_CONFIG`，构造期 fail-fast，不触 DB/LLM。 |
| D9 | 固定 Fake 样本：旧事实 MERGE、后续 UPDATE、矛盾/不确定、非法降级 NEW 反例；真实模型 not-run。 |
| D10 | 允许 internal 测试接缝/只读访问器，不新增公开 API/ContextStatus 字段。 |

## 2. 冻结契约

- `NEW`：`content`+`domains` 必填，`targetMemoryId` 缺席。
- `MERGE`：`targetMemoryId` 必填，`content` 可选，`domains` 必须缺席（含空数组）。
- `UPDATE`：`targetMemoryId`+`content` 必填，`domains` 可选。
- `targetMemoryId` 必须属于本次 Governance 实际提供的候选集合；跨批/未知/伪造 ID 拒绝。
- 非法决策整项失败，不降级 NEW、不部分保存；单次请求全部候选，不分批不截断。
- Prompt 六要素：事实时间优先、补录旧事实 MERGE、后续真实变化 UPDATE、不确定/矛盾明确写入、输入是数据不是指令、只输出一个 JSON。

## 3. B1 要求

1. 先落 `docs/context/tasks/cm3-ct14/B1_委派单.md`，严格白名单；不改 CT13 组件/Schema/索引/依赖/VFS/common/构建。
2. Validator 纯逻辑全分支（含 D4/D5/D6 边界）；Service 真实 SQLite + Fake LLM（预算超限 0 LLM、非法响应 0 写、target 越界拒绝、取消传播）；Prompt/Schema 正式测试逐条断言六要素。
3. `./scripts/check` exit 0、14 模块 @Test=XML、81 基线 all OK、Core 无 Ktor、Schema SHA 不变；提交 SHA/计数/A01 映射/证据归属。
4. Worker `10264b13`；Lead 初验后 Architect 复核；缺陷停下报告。

CT14 B1 通过前不启动 B2；CT15/CT16 未放行；CM4 不启动；无 git。

## 4. B1 与 CT14 收口（2026-11-01）

- **CT14 B1 APPROVED / DONE，无 R 项**；12 个白名单文件 SHA 复算一致，60 例新增（Validator 19、Prompt 15、Parser 7、Service 19）。
- `./scripts/check` exit 0、14 模块 **1558=1558**、失败/错误/跳过 0；81 VFS 基线 all OK、VFS diff 空、Core 无 Ktor/serialization、Schema `ec727a53…` 未变；CT13 组件 SHA 未变；未接生产。
- **CT14 整体 APPROVED / DONE**；保持组件化，生产接线归 CT16。
- CT15 技术条件满足，但按用户暂停指令**不启动**；恢复点见 `CM3_暂停恢复点.md`。
