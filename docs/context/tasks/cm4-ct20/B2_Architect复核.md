# CT20 B2 · Architect 复核

**日期**：2026-11-09
**状态**：**CT20 B2 APPROVED / DONE；CT20 整体 APPROVED / DONE**
**复核对象**：[B2 Worker 交付](B2_Worker交付.md)、[B2 Lead 初验](B2_Lead初验.md)、Worker `059d6c59-ce9e-4e68-9055-b45b238506a1`
**依据**：[Architect 裁定](Architect_裁定.md) D1～D16、[B2 委派单](B2_委派单.md)、[B1 Architect 复核](B1_Architect复核.md)

## 0. 本次修改

Architect 独立复核 CT20 B2：12/12 文件 SHA 一致、公共 API/配置/Factory/Impl 接线逐项与 D1～D16 及委派单吻合、定向与全量实跑通过、B1 检索链与 Schema 边界未变。结论 **B2 APPROVED / DONE；CT20 整体 APPROVED / DONE**。N1/N2 均为记录性观察。

## 1. 独立证据

| 项 | 结果 |
| --- | --- |
| 12 文件 SHA 复算 | API 2 + Runtime 生产 5 + Runtime 测试 4 + Integration E2E 1，逐项一致（12/12 OK） |
| 定向测试 | 三模块 `--rerun-tasks` exit 0（47s，32 tasks）；B2 新测试 5 类 = 1+3+2+6+1（含 Loader 增量）= 13 新增用例 + Loader 2 增量 = 15，全绿 |
| 全量 `./scripts/check` | exit 0（2m15s，131/131 tasks executed） |
| 14 模块计数 | source `@Test` = XML = **1882=1882**；B2 前 1867 + 15 = 1882 |
| Schema | `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3` 未变 |
| B1 锁定 | `MemorySearchService`/`MemorySearchPrompt`/`MemorySearchBinding` SHA 与 B1 终态一致；`ContextExceptions.kt` 保持 CT17 终态 `942402ea…b50c9`；`MemoryMapPipeline` 未变 |
| 白名单变化 | CT19 6 个文件变化（Config/Loader/LoaderTest + AlcyoneContext/Factory/Impl）全部为 B2 委派单 §3 白名单文件，与功能需求一一对应 |
| 其他边界 | common diff 空；VFS 仅 CT17 已批准差异；构建/依赖无变更 |

## 2. 实现逐项核对

| 裁定/委派 | 复核结果 |
| --- | --- |
| D15 默认契约 | 三方法接口默认抛 `ContextRetrievalException(UNAVAILABLE)`，消息固定、无 query/ID 回显；不返回假空/假 null。通过 |
| 生命周期门控 | `currentRetrievalRuntime` 状态锁内统一门控：CREATED/STARTING→NOT_STARTED、STOPPING/CLOSED→CLOSED；RUNNING 快照 database/llmClient/llmConfig，锁外执行 DB/LLM。通过 |
| Get/GetRaw | `database.read { findById }` 纯 SQLite；`normalizeRetrievalId` trim 后空白 → INVALID_REQUEST；不存在 null；无 LLM/VFS 调用。通过 |
| D6/D2 Search | 每次调用构造 `MemorySearchService(database, llm, llmConfig, maxLimit)` 复用 B1 链；binding 在调用时校验（B1 CONFIGURATION）；start/Bootstrap/Get 零依赖。通过 |
| D1 默认 limit | API 签名 `limit: Int = 10` 保持；配置只控制 maxLimit。通过 |
| D2 配置 | `SearchConfig.init` 构造期 1..1000 → INVALID_CONFIG；Loader `parseRetrieval` 缺省/空 mapping 默认 50、`rejectUnknown` retrieval/search 两级严格、`intAt` 类型安全；Factory 只传 `retrievalConfig`，`create()` 签名不变零副作用。通过 |

## 3. E2E 复核

`RetrievalContextEndToEndTest`（真实 Factory + initializeDatabase + SQLite + Ktor client + JDK HTTP stub）：Scanner+Rerank 恰 2 请求、返回 SQLite 权威内容、重复 5 次 Get/GetRaw 请求数不变（89 行断言）、close 后三方法 CLOSED。

## 4. N1/N2 裁定

**均为记录性观察，不要求 R1。**

- N1（调用时构造轻量 Service + classpath 资源读取）：符合 D6 调用时 binding 语义；无后台状态；成本相对 LLM/全量扫描可忽略。登记为高 QPS 场景缓存优化候选。
- N2（Search 与 close 极端交错）：`currentRetrievalRuntime` 锁内取引用 + 既有 DB/LLM close 协议承担，无新增共享可变状态；CT21 全景取消/清理回归覆盖。

## 5. 结论

**CT20 B2 APPROVED / DONE；CT20 整体 APPROVED / DONE。**

- 13 文件白名单齐备；12 代码/测试 SHA 一致；白名单外变更 0。
- 14 模块 **1882=1882**；`./scripts/check` exit 0；B1 检索链/`ContextExceptions`/Schema/CT17/CT18/CT19 非白名单边界全部通过。
- A01～A08 全部落实（A01/A06/A07/A08 B2 侧、A02～A05 B1+B2 组合）。
- CT20 交付公共 Search/Get/GetRaw、Retrieval 错误契约、retrieval 配置、完整检索链与 E2E；真实模型语义质量仍未验证（not-run）。
- **放行 CT21（CM4 集成与评测方案核对）**；CM4 收口（CM4 整体复核）待 CT21 后。
- 无 Git 写操作。
