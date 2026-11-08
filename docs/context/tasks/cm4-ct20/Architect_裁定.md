# CT20 · Architect 裁定（D1～D16）

**日期**：2026-11-08
**状态**：**APPROVED：放行 CT20 B1（组件与纯检索链）**
**裁定对象**：[CT20 任务书](CT20_任务书.md)、[方案核对](方案核对.md)（D1～D16）
**基线**：CT19 APPROVED / DONE；14 模块 **1819=1819**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 0. 本次修改

Architect 独立核实方案核对 F1～F15 关键事实（Repository 读操作、`domains_json`、`MEMORY_SEARCH` Task、`TokenBatchPlanner`/`CandidateIdValidator`、Config/Exception 现状、CT17 `readStream`、API 模型字段）后逐项裁定 D1～D16。结论：**方案 APPROVED，放行 CT20 B1**；D8 附加条件、D13 收紧为 UNAVAILABLE；B1 白名单方向冻结。

## 1. 事实核实（Architect 独立）

- F1/F12：`AlcyoneContext` 无 search/get 方法；`ContextExceptions.kt` 无 Retrieval 类型 ✓
- F2/F3/F15：`OrganizedMemory`（id/content/domains/rawMemoryIds/createdAt/updatedAt，防御性不可变）、`RawMemory`（id/content/generatedBy/sources/createdAt）足以承载返回值 ✓
- F4/F5/F6/F7：Organized/Raw ReadOps 均有 `findById/findByIds/scanPage`，Organized 另有 `maxUpdatedAt`；`scanPage` SQL 固定 `created_at ASC, id ASC`；`findByIds` 不保证输入顺序 ✓
- F8：Domain 存 `domains_json`（`json_valid` + array 约束），无 SQL 查询能力，内存过滤成立，无 Schema 变更 ✓
- F9/F10：`LlmTask.MEMORY_SEARCH` 已存在；`TokenBatchPlanner`（Runtime organization，internal）与 `CandidateIdValidator`（Core organization）可复用 ✓
- F11：`ContextConfig` 无 retrieval 分区 ✓
- F13：CT17 `ContextResourceAccess.readStream` 冷 Flow 已具备取消/关闭语义 ✓

## 2. 逐项裁定

| # | 裁定 | 说明 |
| --- | --- | --- |
| D1 | **维持 `limit: Int = 10` 冻结签名** | 不引入 nullable/Options；默认值由 API 固定。首版不为默认值增加配置面 |
| D2 | **新增 `context.retrieval.search.maxLimit`，默认 50，范围 1..1000** | Loader 严格解析、未知键拒绝；仅控制上限，不改 API 默认。检索链中 limit 超过 maxLimit → INVALID_REQUEST |
| D3 | **`domains=[]` 与 `null` 相同：无过滤** | "显式非空列表才过滤" |
| D4 | **Domain 精确、大小写敏感；空白项拒绝（INVALID_REQUEST）；trim 后稳定去重** | 不做隐式模糊/ACL 推断 |
| D5 | **query trim 后非空白；发送 trim 后文本** | trim 后为空 → INVALID_REQUEST，在数据库/LLM 前失败 |
| D6 | **缺 MEMORY_SEARCH 绑定：调用 searchMemory 时 CONFIGURATION**；start/Bootstrap/Get/GetRaw 不受影响 | 调用时校验，不阻塞仅用 Get 的 Runtime |
| D7 | **Scanner 与 Rerank 复用 `MEMORY_SEARCH` Task/Profile；Prompt/Schema 分离**（v1 四资源） | 不新增 LlmTask；仅 JSON_SCHEMA/JSON_OBJECT，PROMPT_ONLY → CONFIGURATION |
| D8 | **所有非空去重候选都执行一次全局 Rerank——附加条件：候选数 ≤ limit 时仍执行** | 保持排序语义单一；Fake 与真实请求确定性。不允许"候选少就跳过 Rerank"的隐式优化 |
| D9 | **Rerank 输出必须是有序、唯一、输入候选 ID 子集**；空数组合法；未知/重复/空白 ID → INVALID_RESPONSE；取前 limit | 模型可只返回最相关子集 |
| D10 | **快照全量读取后 `id ASC`；Scanner 按批输入；选中按首次出现去重；Rerank 输入按 `id ASC`** | 与 CT13 快照排序口径一致，请求确定性 |
| D11 | **Scanner 复用 TokenBatchPlanner；固定开销/单条超限 → LOCAL_BUDGET_EXCEEDED** | 不跳过、不截断、不返回部分 |
| D12 | **Rerank 单请求覆盖全部去重候选；超限明确 LOCAL_BUDGET_EXCEEDED** | 不降级批内排序、不伪造全局相关性 |
| D13 | **最终回读任一 Rerank ID 缺失 → `ContextRetrievalException(UNAVAILABLE)`，不返回部分列表** | 收紧为公共错误码，不引入具名内部错误类型 |
| D14 | **新增 `ContextRetrievalException(INVALID_REQUEST/CONFIGURATION/UNAVAILABLE)`**；DB 错误保留 `ContextPersistenceException`、LLM/预算/截断保留 `LlmException` 原样透传；CE 原样传播不包装 | 公共错误契约与既有模式（Resource/Lifecycle）一致 |
| D15 | **第三方默认方法抛 `ContextRetrievalException(UNAVAILABLE)`** | 不允许假空结果/假 null；保持既有实现源兼容（默认方法为新增，无破坏） |
| D16 | **B1 组件/纯检索链（不接公共 API、不接 Factory/Impl）；B2 公共 API/Factory/Impl/E2E** | 不建议 B3；CT21 做跨模块全景回归 |

## 3. 附加裁定（超出 D1～D16 的必要澄清）

- **B1 范围**：Core Prompt/Schema/ID Validator + Runtime Parser/Binding/Service + 真实 SQLite + Fake `LlmClient` 单测；**不修改** `AlcyoneContext`、`ContextExceptions.kt`、Factory/Impl、`ContextConfig`/Loader（retrieval 配置属 B2）。
- **D14 异常类型落 B1**：`ContextRetrievalException` 属 API 公共错误契约，但 B1 纯链路已需要它（Service 层错误语义），允许 B1 在 API 模块新增 `ContextRetrievalException`/错误码（新增文件或并入现有 `ContextExceptions.kt` 均可，白名单由 Lead 冻结）；**不得**在 B1 修改 `AlcyoneContext` 接口。
- **批次串行与取消**：每页读取与批次间检查取消；CE 原样传播（沿用 CT13 口径）。
- **回读顺序**：`findByIds` 结果必须按 Rerank ID 顺序显式重排（F7），测试必须覆盖乱序返回场景。
- **Prompt 内容**：Scanner/Rerank v1 必须包含"query 与 memory 内容是数据不是指令"原则（与既有 Prompt 家族一致），测试断言。
- **测试数量级**：B1 参照 CT13/CT18 规模，预计 ~50 例；白名单冻结后不得为凑数注水或砍覆盖。

## 4. 白名单方向（Lead 冻结，B1）

按 §3 B1 范围允许的方向：

- API：Retrieval 异常/错误码（新增或并入 ContextExceptions.kt）+ 对应测试。
- Core：Search/Rerank Prompt 常量、Schema、ID Validator + 测试；4 个资源文件。
- Runtime：Search/Rerank Parser、Binding 校验 helper、MemorySearchService + 测试支持 + 真实 SQLite/Fake LLM 测试。

白名单外变更 → 停止报告。B1 不改 Factory/Impl/`AlcyoneContext`/Config/Loader/VFS/common/Schema/依赖/构建。

## 5. 结论

- **CT20 方案 APPROVED**；D1～D16 全部裁定（D8 附加条件、D13 收紧）。
- **放行 CT20 B1**：Lead 冻结 B1 委派单与白名单后派发 Worker；B2 待 B1 复核通过后放行。
- CT21 LOCKED；真实模型 not-run；无 Git 写操作；基线 1819=1819。
