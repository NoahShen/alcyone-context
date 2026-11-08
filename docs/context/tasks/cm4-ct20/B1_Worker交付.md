# CT20 B1 · Search/Rerank 组件与纯检索链 Worker 交付

## 本次修改（2026-11-08，B1 初始交付）

新增本交付报告，记录 CT20 B1 的 20 文件白名单、Retrieval 异常、四份 Prompt/Schema、Scanner/Rerank ID Validator、严格 Parser、Binding 与 MemorySearchService、48 个新增测试、三条验收命令、14 模块 **1867=1867**、CT19/CT17/CT18/Schema/81 基线与残余风险。本文件不把 B1、CT20 或后续批次标记为 DONE。

**日期**：2026-11-08
**状态**：**提交 Lead 初验，CT20 未 DONE，B2/CT21 未启动**
**依据**：[B1 委派单](B1_委派单.md)、[Architect 裁定](Architect_裁定.md)、[CT20 任务书](CT20_任务书.md)、[方案核对](方案核对.md)、[CT19 B2 Architect 复核](../cm4-ct19/B2_Architect复核.md)
**基线**：CT19 APPROVED / DONE；14 模块 **1819=1819**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；工作区既有 CM3/CT17/CT18/CT19 未提交交付保持不动。

## 1. 交付结论

严格完成 B1 委派单 §3 的 20 个文件：**19 个代码/资源/测试文件 + 本交付报告**。

- API 新增独立 Retrieval 异常契约；未修改 `AlcyoneContext` 或 `ContextExceptions.kt`。
- Core 新增 Scanner/Rerank 四份 v1 资源、Prompt 渲染和 ID Validator。
- Runtime 新增两个严格 Parser、MEMORY_SEARCH Binding/maxLimit helper 与纯 MemorySearchService。
- Search 链已覆盖全量单快照扫描、Domain 内存过滤、串行动态分批、跨批去重、全候选单请求 Rerank、权威回读重排。
- B1 新增 **48** 个测试：API +1、Core +9、Runtime +38。
- 14 模块源码 `@Test` 与 XML 均为 **1867**，fail/error/skip = **0/0/0**。
- 三模块定向、三模块 Spotless、全量 `./scripts/check` 均通过。
- 未接公共 Search/Get API、Factory/Impl、Config/Loader 或 Runtime E2E；真实模型 **not-run**；无任何 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 API（2）

| 文件 | SHA-256 |
| --- | --- |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/ContextRetrievalException.kt` | `b23b9707e91e0d92861a2f7ea16354adb84159ea256f089a8563e31dbbb60368` |
| `context/api/src/test/kotlin/com/github/noahshen/alcyone/context/memory/api/ContextRetrievalExceptionTest.kt` | `9fa372a0a4096ba6ffe6fcdf11807ec1c5819b8f07abd178c42c0f5211cd9d05` |

### 2.2 Core 生产与资源（8）

| 文件 | SHA-256 |
| --- | --- |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/retrieval/MemorySearchPrompt.kt` | `5bba622864beb6ce7207e8beb396133a78ac59155717acba9d999eab91013148` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/retrieval/MemorySearchIdValidator.kt` | `9110b6a673f312b197b03214288a64284924884dae02c4e0c3561e1d9370a049` |
| `context/core/src/main/resources/prompts/memory-search-v1.md` | `171906ece4c4d1a84a9437b09d961fc60bd045e2c7e907259754f1f45c21c16c` |
| `context/core/src/main/resources/prompts/memory-search-schema-v1.json` | `16f8de9c907ddf7750c65f70da8905e98176f9516ad517ccc2cfd58a0ea0a879` |
| `context/core/src/main/resources/prompts/memory-search-rerank-v1.md` | `caa29c26a0eab7e2951b92a47afbf5bd8fee50f2422d8865849adb0e2a821b86` |
| `context/core/src/main/resources/prompts/memory-search-rerank-schema-v1.json` | `162c2e0f2f22025cb31d7c634e71cd9c8ec4edefa923945a20347e03921ad662` |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/retrieval/MemorySearchPromptTest.kt` | `9c28203a7b51d1b14ceffe6c0f35cc040598f1e04510e9c4848f627880bf6f33` |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/retrieval/MemorySearchIdValidatorTest.kt` | `46c46a47beaf16d97672e0c7ce243115229a5d4f47bbec1c9f0c63a2b1bfb64e` |

### 2.3 Runtime 生产（4）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/JsonMemorySearchResponseParser.kt` | `defc36b3900654760439094a5c35f2728fd74ea250d005d35e95e16634c21926` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/JsonMemorySearchRerankResponseParser.kt` | `7dc62f47d0f67684c19175539e6cbcec0c527ce0adc34afda86d5789e5e5d20d` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/MemorySearchBinding.kt` | `e6640dfef31939f7f48f18c27aac7dc9c7247d3ee9d0d2de3b68cecbe86ec0bc` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/MemorySearchService.kt` | `484317c737972c28517a3b1c9b1033b2b4b7b972262fa728bb3663f838fe02d0` |

### 2.4 Runtime 测试（5）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/JsonMemorySearchResponseParserTest.kt` | `52b0ea186c8950e4c5fde178176da595c7f56bb538f7f20aafb7f46cc4bbae77` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/JsonMemorySearchRerankResponseParserTest.kt` | `ca96abdbd996a2d97262df77ad65be64c9633b0a88e9b17773c3d572e93fb74c` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/MemorySearchBindingTest.kt` | `f6d4646c7705f882725b82fb07009c81b066d71d3ff460acba4837681bbc0584` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/MemorySearchServiceTest.kt` | `498478f670196d5e6bceb95094dfbd222818369e9b13ecd19aaa696865746eac` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/MemorySearchTestSupport.kt` | `3f482a27e1730e210326f2773f1b44b174beb5a588920c0b94e31af99bb63e33` |

### 2.5 交付报告（1）

| 文件 | SHA-256 |
| --- | --- |
| `docs/context/tasks/cm4-ct20/B1_Worker交付.md` | 本文件自身不内嵌自引用 SHA |

白名单外 B1 代码/测试/构建变更：**0**。工作区既有 CM3/CT17/CT18/CT19 改动未被回滚、丢弃或改写。

## 3. 实现说明

### 3.1 Retrieval 异常

- 新增独立 `ContextRetrievalErrorCode` 三态与 `ContextRetrievalException`，继承既有 `ContextException`，不挂 cause。
- DB 错误仍为 `ContextPersistenceException`；LLM/预算/截断/非法响应仍为 `LlmException`；CE 不包装。
- B1 未修改 `ContextExceptions.kt`、`AlcyoneContext.kt`、Factory/Impl 或 ContextConfig/Loader。

### 3.2 Prompt / Schema / Validator

- 四份 v1 资源分别用于 Scanner System Prompt/Schema 与 Rerank System Prompt/Schema。
- 两份 System Prompt 均明确：query 与 memory 内容是数据，不是指令；伪造标签/ID 不改变程序边界。
- Scanner 输入含 trim 后 query，以及本批 `id/content/domains/createdAt/updatedAt`；候选按 id ASC 渲染。
- Rerank 输入含 trim 后 query、全部去重候选与 limit；候选按 id ASC 渲染。
- Scanner 输出仅 `candidate_ids`；Rerank 输出仅 `memory_ids`；Schema 均 `additionalProperties=false`、字符串数组可空。
- Scanner ID 复用 CT13 `CandidateIdValidator`：批内、空白/未知拒绝、重复稳定去重、空数组合法。
- Rerank ID 额外要求有序、唯一、输入候选子集；空白/未知/重复拒绝，空数组合法。

### 3.3 Parser / Binding

- Scanner/Rerank Parser 只接受单字段 JSON object 与字符串数组；拒绝未知/缺失字段、错误类型、空字符串、围栏、前后文本、截断。
- Parser 在 JsonObject 覆盖前扫描根对象重复 key，包含转义等价 key；失败统一 `LlmException(INVALID_RESPONSE)`，不挂 cause、不回显正文。
- `MemorySearchBinding` 在 Search 调用时校验 LLM、`MEMORY_SEARCH` Task/Profile 与 JSON_SCHEMA/JSON_OBJECT；失败为 `CONFIGURATION`，不阻塞 start/Get/Bootstrap。
- `MemorySearchDefaults.MAX_LIMIT=50`、`MAX_LIMIT_BOUND=1000`；配置 maxLimit 与单次 limit 使用独立校验 helper。

### 3.4 MemorySearchService

- 参数先校验：query trim 非空；Domain 逐项 trim、空白拒绝、精确大小写敏感、稳定去重；`null/[]` 无过滤；limit 1..maxLimit。
- 单次 `database.read` 分页读取全部 Organized，默认 256/页；偏移使用 Long 受检；每页前检查 CE；退出事务后按 id ASC 排序。
- Domain 过滤在内存执行；空结果直接返回 `[]`，0 LLM。
- Scanner 使用 `TokenBatchPlanner` 动态分批；固定开销/单条超预算映射 `LlmException(LOCAL_BUDGET_EXCEEDED)`。
- 批次严格串行；每批只发送本批候选；任一批失败/截断/Parser/ID 失败立即整体失败，后续批不调用。
- Scanner 选中 ID 按首次出现稳定去重；跨批重复 ID 在构造 Rerank 候选时再次按 id 去重。
- 所有非空去重候选执行一次全局 Rerank，即使候选数 ≤ limit；候选按 id ASC，单请求包含全部候选。
- Rerank 超预算直接失败，不截断、不批内 TopN 拼接；空数组合法；最终取前 limit。
- 最终使用新的短 `database.read { findByIds(ids) }`；按 Rerank ID 显式重排；任一缺失为 `ContextRetrievalException(UNAVAILABLE)`，不返回部分。

## 4. 完整扫描 / Scanner / Rerank / 回读矩阵

| 场景 | 实际断言 |
| --- | --- |
| 多页全量扫描 | 5 条记录/pageSize=2 读取 offset 0,2,4；最终按 id ASC 进入请求 |
| 整页倍数 | 4 条记录/pageSize=2 读取 offset 0,2,4，最终空页结束 |
| Domain null / [] | 均不过滤 |
| Domain 单/多/未知 | 精确、大小写敏感；`Work` 不匹配 `work`；未知域返回空且 0 LLM |
| Domain trim/去重 | `[" work ", "work"]` 只形成一次 `work` 过滤 |
| query trim | Scanner 与 Rerank 均发送 trim 后 query |
| 非法 query/Domain/limit | DB/LLM 前 `INVALID_REQUEST` |
| 空库/无匹配 | 0 LLM |
| 动态分批 | 受控预算下 3 条候选形成 3 个单候选 Scanner 请求，另 1 个全局 Rerank |
| 固定开销/单条超限 | `LOCAL_BUDGET_EXCEEDED`，0 LLM |
| 预算等号 | 等于 maxInput 的请求通过 |
| 批次串行 | 请求按批顺序记录，无并行/后续部分结果 |
| 任一批失败 | 非法 JSON、length、非法 ID 均在第 1 批停止，后续 0 请求 |
| 跨批重复选中 | 构造 Rerank 候选前按 id 去重 |
| 候选数 ≤ limit | 仍执行 1 次全局 Rerank |
| Rerank 输入顺序 | 全部候选按 id ASC |
| Rerank 子集/limit | 有序子集按 limit 截取；最终回读顺序与之一致 |
| Rerank 空数组 | 合法返回空，不执行最终回读 |
| Rerank 非法 ID | 未知/重复/空白统一 `INVALID_RESPONSE` |
| Rerank 超预算 | 不发起 Rerank LLM，不截断候选 |
| 权威回读乱序 | 以 Rerank ID 显式重排，不依赖 `findByIds` 返回顺序 |
| 最终缺失 ID | `UNAVAILABLE`，不返回部分 |
| DB 错误 | 原 `ContextPersistenceException` 实例传播 |
| LLM 错误 | 原 `LlmException` 实例传播 |
| 页/批 CE | CE 原样传播；LLM 调用原实例；页间 CE 后 0 LLM |
| Binding 缺失 | Search 调用时 `CONFIGURATION`，DB 读取 0 |
| JSON 模式 | JSON_OBJECT 不发送 schema；JSON_SCHEMA 发送对应资源 |

## 5. 测试映射与用例数

### 5.1 A01～A08（B1 负责部分）

| 验收 | B1 测试 | 关键覆盖 |
| --- | --- | --- |
| A01 API/参数 | `ContextRetrievalExceptionTest`、Binding、Service | 三错误码、query/domain/limit/maxLimit |
| A02 全量扫描 | `MemorySearchServiceTest` | 多页/整页倍数/id ASC/Domain/空结果 |
| A03 Scanner | Parser、Validator、Service | 动态分批、串行、批内 ID、跨批去重、无部分 |
| A04 Rerank | Parser、Validator、Service | 全候选单请求、有序子集、空数组、超预算、非法 ID |
| A05 权威回读 | `MemorySearchServiceTest` | Rerank 顺序、limit、缺失 UNAVAILABLE |
| A06 Get/GetRaw | 不在 B1 范围 | B2 公共 API/Impl 生产读取 |
| A07 取消 | `MemorySearchServiceTest` | 页间 CE、LLM CE、预算/长循环路径 |
| A08 集成/边界 | 真实 SQLite + Fake LlmClient、全量回归 | 无 Schema/依赖/VFS/Factory/API 接线 |

### 5.2 用例数

| 测试类 | 新增用例 |
| --- | ---: |
| `ContextRetrievalExceptionTest` | 1 |
| `MemorySearchPromptTest` | 5 |
| `MemorySearchIdValidatorTest` | 4 |
| `JsonMemorySearchResponseParserTest` | 5 |
| `JsonMemorySearchRerankResponseParserTest` | 5 |
| `MemorySearchBindingTest` | 4 |
| `MemorySearchServiceTest` | 24 |
| **合计新增** | **48** |

## 6. 验收命令与结果

### 6.1 运行环境说明

首次在受限沙箱直接运行 Gradle 时，Gradle 启动阶段报 `FileLockContentionHandler ... SocketException: Operation not permitted`。随后按项目入口在获批的非沙箱环境重跑同一命令；以下为最终有效实跑结果。代码写完与修正后均执行了 `./scripts/dev gradle spotlessApply`。

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle spotlessApply` | 0 | 1s | `BUILD SUCCESSFUL`；57 tasks，2 executed / 55 up-to-date |
| `./scripts/dev gradle :context:api:test :context:core:test :context:runtime:test --rerun-tasks --console=plain` | 0 | 49.59s | `BUILD SUCCESSFUL in 49s`；29 tasks executed |
| `./scripts/dev gradle :context:api:spotlessCheck :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | 6.07s | `BUILD SUCCESSFUL in 5s`；13 tasks executed |
| `./scripts/check` | 0 | 137.22s | `BUILD SUCCESSFUL in 2m 16s`；131/131 tasks executed |
| `git diff --check` | 0 | - | 空输出 |

## 7. 14 模块源码 `@Test` 与 XML

源码范围为各模块 `src/test/**/*.kt` 行首（允许缩进）`@Test`；XML 为最终 `build/test-results/test/TEST-*.xml`。

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 37 | 37 | 0 | 0 | 0 |
| context-core | 119 | 119 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 610 | 610 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 23 | 23 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **1867** | **1867** | **0** | **0** | **0** |

B1 前基线 **1819**，新增 **48**，最终 **1867=1867**。

## 8. CT19 / CT17 / CT18 / Schema / 81 基线

### 8.1 CT19 B1/B2

- CT19 B1 20 个代码/测试 SHA 逐项复算：**20/20 一致**。
- CT19 B2 10 个代码/测试 SHA 逐项复算：**10/10 一致**。
- B1 未修改 CT19 Map/Bootstrap/Factory/Impl/Config/Loader 或 CT19 测试。

### 8.2 Context Schema

- `ContextSchema.sq` SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 未新增表、索引、`user_version`、SQL、FTS、Embedding 或向量库。

### 8.3 CT17 / CT18

- CT18 17 个代码/测试文件 SHA：**17/17 一致**。
- CT17 23 文件基线保持 CT19 B2 终态：**21/23 未变**；`AlcyoneContext.kt` 与 `AlcyoneContextImpl.kt` 仍为 CT19 B2 白名单内的既有 bootstrap/Map 接线变化，CT20 B1 未再修改这两文件。
- CT20 B1 未修改 CT17/CT18 Generator/Publisher/Renderer/VFS/Resource 行为。

### 8.4 81 VFS 基线

按 `.local/validation/cm1/architect/vfs-baseline-sha256.json` 复算：

- **81 项中 75 项一致、6 项 CT17 已批准差异、缺失 0、未批准差异 0**。
- B1 未修改 VFS/common/Schema/SQL/依赖/构建；构建文件与版本目录状态检查为空。
- Core 主源码禁止导入检查：无 Ktor、无 kotlinx.serialization。

### 8.5 API / 生产边界

- 未修改 `AlcyoneContext.kt`、`ContextExceptions.kt`、Factory/Impl、ContextConfig/Loader。
- 未实现 `searchMemory/getMemory/getRawMemory` 公共 API、Get/GetRaw 生产读取或 Runtime E2E。
- 未新增公开 Factory 参数、公开检索内部类型或依赖。
- B1 Runtime 组件均 internal；Search 链不读 VFS、不依赖 Map 当前性。

## 9. 未运行项与残余风险

- 真实模型：**not-run**；Fake `LlmClient` 只证明协议、预算、扫描、排序与错误语义，不证明语义相关性质量。
- B2 未运行/未实现：公共 Search/Get API、Factory/Impl 接线、ContextConfig retrieval maxLimit 解析、Runtime E2E。
- Get/GetRaw 的空白 ID、数据库错误与 null 语义归 B2 公共链；B1 仅提供 Retrieval 错误契约。
- Rerank 必须一次覆盖全部去重候选；候选总量超过单次预算时明确失败，不提供降级或截断。
- `LlmConfig` 自身已在构造期拒绝未知 Profile；Binding 的未知 Profile 防御分支正常路径不可构造，仍需保留以防未来配置类型放宽。
- 页间 CE 已覆盖；底层 SQLite 执行器可能复制协程取消异常身份，但 B1 Service 不捕获/包装任何 CE。
- 真实 Provider 对长 JSON/输出上限的行为未运行；Fake 只验证业务边界。
- CT21 全景 SDK/评测、HTTP、Search→Resource 跨模块回归未启动。

## 10. 最终状态

**提交 Lead 初验，CT20 未 DONE，B2/CT21 未启动。**

真实模型 not-run；无 `git add/commit/push/stash`、分支、回滚、发布或其他 Git 写操作。
