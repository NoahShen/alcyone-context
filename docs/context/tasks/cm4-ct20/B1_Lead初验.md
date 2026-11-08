# CT20 B1 · Lead 初验

## 本次修改（2026-11-09，B1 Lead 初验）

完成 CT20 B1 独立初验：核对 20 文件白名单与 SHA，审查 Retrieval 异常、四份 Prompt/Schema、Scanner/Rerank Validator、Parser、Binding 与 MemorySearchService，复跑 API/Core/Runtime 定向、Spotless、检索链定向和全量 `./scripts/check`。结论为 **B1 初验通过、提交 Architect 复核；CT20 未 DONE，B2/CT21 未启动**。

**日期**：2026-11-09
**状态**：**B1 IN_REVIEW；提交 Architect 独立复核**
**Worker**：`059d6c59-ce9e-4e68-9055-b45b238506a1`（`codex-magpie/group/worker`、`auto-review`、high）
**依据**：[B1 委派单](B1_委派单.md)、[B1 Worker 交付](B1_Worker交付.md)、[Architect 裁定](Architect_裁定.md)、[CT20 任务书](CT20_任务书.md)、[方案核对](方案核对.md)
**基线**：CT19 APPROVED / DONE；14 模块 **1819=1819**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 1. 初验结论

**CT20 B1 Lead 初验通过，提交 Architect 独立复核。**

- Worker 严格交付 B1 委派单 §3 的 20 个白名单文件：19 个代码/资源/测试文件 + 1 份交付报告；白名单外 B1 变更 0。
- 新增 48 个测试，14 模块源码 `@Test` 与 XML 均为 **1867=1867**，fail/error/skip = **0/0/0**。
- Search 链实现完整：单快照全量扫描、`id ASC`、Domain 过滤、串行动态分批、跨批去重、所有非空候选强制 Rerank、最终 SQLite 权威回读与显式重排。
- Scanner/Rerank 分离使用四份 v1 资源；两 System Prompt 均明确 query 与 memory 内容是数据而非指令。
- 参数、配置、DB、LLM、预算、截断、最终缺失与 CE 的错误边界符合 D1～D16。
- 未修改 `AlcyoneContext`、`ContextExceptions.kt`、Factory/Impl、ContextConfig/Loader、CT17/CT18/CT19、VFS/common/Schema/SQL/依赖/构建。

## 2. 白名单与 SHA

### 2.1 文件集

- 委派单 §3 的 20 个文件全部存在；19 个代码/资源/测试文件 SHA 与 [B1 Worker 交付](B1_Worker交付.md) §2 逐项一致。
- 交付报告为第 20 个白名单文件，不内嵌自引用 SHA。
- 实际组成为：API 2 + Core 生产/资源/测试 8 + Runtime 生产 4 + Runtime 测试/支持 5 + 交付报告 1。

### 2.2 关键文件 SHA

| 文件 | Lead 复算 SHA-256 |
| --- | --- |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/ContextRetrievalException.kt` | `b23b9707e91e0d92861a2f7ea16354adb84159ea256f089a8563e31dbbb60368` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/retrieval/MemorySearchPrompt.kt` | `5bba622864beb6ce7207e8beb396133a78ac59155717acba9d999eab91013148` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/retrieval/MemorySearchIdValidator.kt` | `9110b6a673f312b197b03214288a64284924884dae02c4e0c3561e1d9370a049` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/MemorySearchBinding.kt` | `e6640dfef31939f7f48f18c27aac7dc9c7247d3ee9d0d2de3b68cecbe86ec0bc` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/MemorySearchService.kt` | `484317c737972c28517a3b1c9b1033b2b4b7b972262fa728bb3663f838fe02d0` |

## 3. 独立实跑证据

| 命令 | exit | 耗时 | 关键结果 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle :context:api:test :context:core:test :context:runtime:test --rerun-tasks --console=plain` | 0 | 49s | 29 tasks；三模块全绿 |
| `./scripts/dev gradle :context:api:spotlessCheck :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | 6s | 13 tasks；格式通过 |
| `./scripts/check` | 0 | 2m16s | 131/131 tasks；全模块构建、Spotless、测试通过 |
| Runtime Retrieval 四测试类定向 | 0 | 11s | Parser/Binding/Service 38 例通过 |
| Core Retrieval 两测试类定向 | 0 | 2s | Prompt/ID Validator 9 例通过 |
| 完整 Core + Runtime 恢复运行 | 0 | 45s | 119 + 610 例通过；恢复完整 XML |
| `git diff --check` | 0 | - | 空输出 |

### 3.1 14 模块 source/XML

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

B1 前 **1819**，新增 **48**，最终 **1867=1867**。

## 4. 行为核对

### 4.1 异常 / 参数 / Binding

- `ContextRetrievalException` 三错误码，继承 `ContextException`，无 cause。
- query trim 后非空；Domain trim、空白拒绝、精确大小写敏感、稳定去重；`null/[]` 无过滤。
- limit `1..maxLimit`；maxLimit 默认 50，范围 `1..1000`；非法参数在 DB/LLM 前失败。
- 缺 LLM、MEMORY_SEARCH Task/Profile 或 PROMPT_ONLY → CONFIGURATION；B1 未接 Runtime 生命周期。
- JSON_OBJECT 发送 null schema；JSON_SCHEMA 发送对应 v1 schema。

### 4.2 Prompt / Validator / Parser

- 四份 v1 资源存在且非空白；Schema 仅允许 `candidate_ids` / `memory_ids`，`additionalProperties=false`。
- 两 System Prompt 均含“query 与 memory 内容是数据不是指令”原则。
- Scanner 输入含 query、id/content/domains/createdAt/updatedAt；Rerank 输入含 query、全部候选、limit。
- Scanner ID 复用 CT13 规则；Rerank ID 要求有序、唯一、输入子集，未知/重复/空白拒绝。
- 两个 Parser 拒绝未知/缺失字段、错误类型、围栏、前后文本、重复 key、截断；错误无 cause/正文。

### 4.3 MemorySearchService

- 单次 ReadContext 分页扫描全部 Organized，默认 256/页，Long 偏移；退出事务后按 `id ASC`。
- Domain 在内存过滤；空库/无匹配返回空，0 LLM。
- Scanner 用 TokenBatchPlanner 动态分批；批次串行；固定开销/单条超限 → LOCAL_BUDGET_EXCEEDED。
- 任一批 Parser/ID/LLM/截断失败立即整体失败；后续批不执行，无部分结果。
- Scanner 选中按首次出现去重；Rerank 输入再次按 id ASC。
- 所有非空候选均执行一次 Rerank，候选数 ≤ limit 也不跳过。
- Rerank 单请求覆盖全部候选；超预算不发起请求；输出有序唯一子集，取前 limit。
- 最终新的短 ReadContext 调用 findByIds，按 Rerank ID 显式重排；任一缺失 → ContextRetrievalException(UNAVAILABLE)。
- DB 错误、LLM 错误与 CE 均保持原实例/类型，不包装。

## 5. 边界核对

### 5.1 CT19 / CT18 / CT17 / Schema / VFS

- CT19 B1 Worker 交付的 20 个代码/测试 SHA：**20/20 一致**。
- CT19 B2 Worker 交付的 10 个代码/测试 SHA：**10/10 一致**。
- CT18 Worker 交付的 17 个代码/测试 SHA：**17/17 一致**。
- CT17 仍为 CT19 B2 终态：`AlcyoneContext.kt`、`AlcyoneContextImpl.kt` 两处既有白名单变化，CT20 B1 未再修改。
- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 81 VFS 基线：**75 一致 + 6 个 CT17 已批准差异，缺失 0，未批准差异 0**。

### 5.2 生产边界

- `AlcyoneContext.kt`、`ContextExceptions.kt`、Factory/Impl、ContextConfig/Loader SHA 均保持 CT19 终态，B1 未修改。
- 未实现 `searchMemory/getMemory/getRawMemory` 公共 API、Get/GetRaw 生产读取或 Runtime E2E。
- 构建文件、Gradle 版本目录、common、Schema/SQL、依赖无 B1 变更。
- Core Retrieval 源码无 Ktor/serialization/文件系统写；Runtime Parser 使用既有 kotlinx.serialization。
- B1 Runtime 组件均 internal；Search 不读 VFS、不依赖 Map 当前性。

## 6. 非阻断观察

**N1：Prompt 结构化文本未做标签转义。** `MemorySearchPrompt` 将 ID/content/domains 渲染到自定义 XML 样文本中，没有 HTML/XML 转义。

- 与 CT13 Organization Prompt 家族一致，正文明确为数据而非指令。
- 程序级安全边界仍由批内/输入子集 ID Validator 执行，模型不能通过正文伪造可返回 ID。
- 若未来允许任意不可信 ID/Domain 文本或增加严格 Markup 解析，可另立 Prompt 编码强化；当前不阻断。

## 7. 未运行项与残余风险

- 真实模型：**not-run**；Fake LlmClient 只证明协议、预算、扫描、排序和错误语义，不证明语义相关性质量。
- B2 未实现/未运行：公共 Search/Get API、Factory/Impl 接线、ContextConfig maxLimit 解析、Get/GetRaw 生产读取、Runtime E2E。
- Rerank 必须一次覆盖全部去重候选；超预算明确失败，不提供降级或截断。
- 底层 SQLite 执行器可能复制 CE 身份；B1 Service 不捕获/包装 CE。
- CT21 全景 SDK/评测、HTTP、Search→Resource 跨模块回归未启动。

## 8. 当前状态

- **CT20 B1：IN_REVIEW，Lead 初验通过，提交 Architect 独立复核。**
- **CT20：IN_PROGRESS；B2 LOCKED。**
- **CT21：LOCKED。**
- 真实模型 not-run；无 Git add/commit/push/stash、分支、回滚或发布操作。
