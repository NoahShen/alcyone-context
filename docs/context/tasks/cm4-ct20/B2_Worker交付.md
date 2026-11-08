# CT20 B2 · Search/Get 公共 API、配置与 Runtime 接线 Worker 交付

## 本次修改（2026-11-09，B2 初始交付）

新增本交付报告，记录 CT20 B2 的 13 文件白名单、公共 Search/Get/GetRaw 契约、Retrieval 配置/Loader、Factory/Impl 生命周期接线、15 个新增测试、三条验收命令、14 模块 **1882=1882**、CT20 B1/CT19/CT18/Schema/81 基线与残余风险。本文件不把 B2、CT20 或 CT21 标记为 DONE。

**日期**：2026-11-09
**状态**：**提交 Lead 初验，CT20 未 DONE，CT21 未启动**
**依据**：[B2 委派单](B2_委派单.md)、[B1 Architect 复核](B1_Architect复核.md)、[Architect 裁定](Architect_裁定.md)、[CT20 任务书](CT20_任务书.md)
**基线**：CT20 B1 APPROVED / DONE；14 模块 **1867=1867**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；工作区既有 CM3/CT17/CT18/CT19/CT20 B1 未提交交付保持不动。

## 1. 交付结论

严格完成 B2 委派单 §3 的 13 个文件：**12 个代码/测试文件 + 本交付报告**。

- `AlcyoneContext` 新增 Search/Get/GetRaw 默认契约，第三方默认明确 UNAVAILABLE。
- Real Impl 完成生命周期门控、Search 调用时 binding 校验、Get/GetRaw 直接 SQLite 精确读取。
- `context.retrieval.search.maxLimit` 配置、构造期校验与 Loader 严格解析完成。
- Factory 将 retrieval 配置传入 Impl；公共 create 签名不变且继续零副作用。
- B2 新增 **15** 个测试：API +1、Runtime +13、integration-context +1。
- 14 模块源码 `@Test` 与 XML 均为 **1882**，fail/error/skip = **0/0/0**。
- 三模块定向、三模块 Spotless、全量 `./scripts/check`、真实 SQLite+Ktor client+JDK HTTP stub E2E 均通过。
- 未修改 B1 检索链、ContextExceptions、CT17/CT18/CT19、VFS/common/Schema/SQL/依赖/构建；真实模型 **not-run**；无任何 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 API（2）

| 文件 | SHA-256 |
| --- | --- |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/AlcyoneContext.kt` | `3379ce95e70805199f2cf0d9b0c6f1fec27b5d2eb6a515b659b9e3aed81146db` |
| `context/api/src/test/kotlin/com/github/noahshen/alcyone/context/memory/api/AlcyoneContextRetrievalTest.kt` | `a123bfefca8b29017558a18b37ae4eb11be14d61eeaaced32543e6705feba345` |

### 2.2 Runtime 生产（5）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/RetrievalConfig.kt` | `22eb86d43f573f1353d464c5559aaaf323ec160a6c627bac26e76a2b572628c3` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/ContextConfig.kt` | `e13f6266536a41d66b0d7ea6d0d5fefd249574a690a1708e2453fee855dc2f7d` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/ContextConfigLoader.kt` | `750bcada432b1ee6afff9a3d938704ac38a19cea9d98ba7ebee3311e7e7edf29` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextFactory.kt` | `dc00b0d4079dc1bbd45b98df755ac81dfb2051d42f61bb8fe90cd7f3b7523df1` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextImpl.kt` | `1256adc79d2062933846a8e6a84489b62fa8cbd52035b913651ccd89cdee05b3` |

### 2.3 Runtime 测试（4）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/RetrievalConfigTest.kt` | `2e30503e710165da4164113fce0ed99ad34e7ef8f1b106ce20c4b99b91533055` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/ContextConfigLoaderTest.kt` | `2026a7798149343ba3f5c8cacae99603fe07e9325688136ee922889730d5ccbf` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/RetrievalRuntimeWiringTest.kt` | `c2db6bc6ef60e6e4254753edaedf2711bd108fc614a87421b77cb23ba0625b54` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/RetrievalLifecycleWiringTest.kt` | `9a3e6d14a11e6c6cca0f082c6d36cd79f1022804b4ee1afd65d73f9c45131188` |

### 2.4 Integration E2E（1）

| 文件 | SHA-256 |
| --- | --- |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/RetrievalContextEndToEndTest.kt` | `597eced73d092de3a972d0d70cf0ce016754d27286116066ca6e266d7e40f887` |

### 2.5 交付报告（1）

| 文件 | SHA-256 |
| --- | --- |
| `docs/context/tasks/cm4-ct20/B2_Worker交付.md` | 本文件自身不内嵌自引用 SHA |

白名单外 B2 代码/测试/构建变更：**0**。工作区既有 CM3/CT17/CT18/CT19/CT20 B1 改动未被回滚、丢弃或改写。

## 3. 实现说明

### 3.1 公共 API

- `searchMemory(query, domains=null, limit=10)`、`getMemory(id)`、`getRawMemory(id)` 新增默认方法。
- 第三方默认三方法均抛 `ContextRetrievalException(UNAVAILABLE)`；不返回假空列表、假 null 或伪成功。
- Real Impl 覆盖三方法；未启动/启动中 → `ContextLifecycleException(NOT_STARTED)`；停止中/关闭后 → `CLOSED`。

### 3.2 Retrieval 配置

- `RetrievalConfig(search=SearchConfig(maxLimit=50))`；`SearchConfig` 构造期校验 `1..1000`，失败为 `ContextConfigException(INVALID_CONFIG)`。
- `ContextConfig.retrieval` 追加为默认参数，旧 Kotlin 构造保持兼容。
- Loader 严格解析 `context.retrieval.search.maxLimit`：缺省/空 mapping 默认 50；未知键、错误类型、0/负/1001 越界均安全拒绝。
- Loader 不校验/构造 MEMORY_SEARCH Binding，不影响无 LLM 配置。

### 3.3 Factory / Impl

- 公共 Factory `create()` 签名不变；继续零资源副作用，只把 `ContextConfig.retrieval` 传入 Impl。
- Impl 在 RUNNING 状态锁内取得当前 `database`、`llm` client 与 `llmConfig` 引用，锁外执行 DB/LLM。
- Search 每次调用构造一个 B1 `MemorySearchService(database, llm, llmConfig, retrieval.search.maxLimit)`；不在 start 构造或校验 Search 服务。
- Get/GetRaw 在生命周期门控后直接 `database.read { findById }`；不调用 LLM、不读 VFS、不触发 Search/Map。
- 空白 ID trim 后拒绝为 `INVALID_REQUEST`；合法不存在返回 `null`；DB 异常原样传播。

### 3.4 Search / Get 隔离

- 缺 LLM、缺 MEMORY_SEARCH Task/Profile 或 PROMPT_ONLY 只在 Search 调用时由 B1 返回 `CONFIGURATION`。
- start、Bootstrap、Get/GetRaw 不依赖 Search Binding；无 LLM Runtime 仍可精确读取。
- Search 参数、Domain、动态分批、全候选 Rerank、权威回读和 CE/DB/LLM 错误语义完全复用 B1，不在 B2 重实现。
- API 默认 limit 保持 10；配置只控制 maxLimit。

## 4. 配置 / 生命周期 / DB / LLM / E2E 矩阵

| 场景 | 实际断言 |
| --- | --- |
| API 第三方默认 | 三方法均 UNAVAILABLE、无 cause、不回显 query/ID |
| Retrieval 默认 | maxLimit=50；旧 ContextConfig 构造自动获得默认 retrieval |
| maxLimit 边界 | 1/1000 通过；0/负/1001 拒绝 |
| Loader 缺省/空 mapping | 50 |
| Loader 覆盖 | 1/1000 正确映射 |
| Loader 非法 | unknown/type/越界均 ContextConfigException，敏感原值不回显 |
| Loader 无 LLM | retrieval 可单独解析，不因缺 MEMORY_SEARCH 失败 |
| CREATED/STARTING | Search/Get/GetRaw 均 NOT_STARTED |
| STOPPING/CLOSED | 三方法均 CLOSED；STOPPING 在 release probe 窗口实测 |
| RUNNING 无 LLM | Get/GetRaw 可用；Search CONFIGURATION；Bootstrap 不受影响 |
| 缺 Task / PROMPT_ONLY | Search CONFIGURATION；Get 仍可用；HTTP 0 请求 |
| Get/GetRaw 存在 | 返回完整 Organized/Raw 对象 |
| Get/GetRaw 缺失 | null |
| Get/GetRaw 空白 ID | INVALID_REQUEST |
| Get/GetRaw LLM | 0 请求 |
| DB 查询失败 | 原 ContextPersistenceException，不伪装 null |
| Search maxLimit | limit>maxLimit 在 DB/LLM 前 INVALID_REQUEST |
| Search HTTP | 真实 Ktor client 按序 Scanner + Rerank；返回 SQLite 权威对象 |
| Search 无绑定 | 不阻塞 start/Bootstrap/Get |
| E2E | 真实 Factory/initializeDatabase/SQLite/Ktor client + JDK HTTP stub；Search 2 请求，重复 Get/GetRaw 请求数不变 |
| E2E close | 三方法均 CLOSED |

## 5. 测试映射与用例数

### 5.1 A01～A08（B2 负责部分）

| 验收 | B2 测试 | 关键覆盖 |
| --- | --- | --- |
| A01 API/参数 | `AlcyoneContextRetrievalTest`、Runtime wiring | 默认 UNAVAILABLE、生命周期、空白 ID、maxLimit |
| A02 全量扫描 | B1 Service 保持；Runtime wiring | 公共调用复用 B1 扫描 |
| A03 Scanner | Runtime HTTP wiring、E2E | 真实 Ktor Scanner + Rerank |
| A04 Rerank | Runtime HTTP wiring、E2E | 两次 HTTP 请求与 SQLite 顺序 |
| A05 权威回读 | Runtime wiring、E2E | 返回真实 SQLite 对象 |
| A06 Get/GetRaw | Runtime wiring、Lifecycle、E2E | 存在/缺失/空白/DB 错误/0 LLM |
| A07 取消/生命周期 | `RetrievalLifecycleWiringTest` | CREATED/STARTING/STOPPING/CLOSED |
| A08 集成/边界 | `RetrievalContextEndToEndTest`、全量回归 | 真实 SQLite+Ktor client+Fake HTTP、无额外请求、close |

### 5.2 用例数

| 测试类/增量 | 新增用例 |
| --- | ---: |
| `AlcyoneContextRetrievalTest` | 1 |
| `RetrievalConfigTest` | 3 |
| `ContextConfigLoaderTest`（本批新增） | 2 |
| `RetrievalRuntimeWiringTest` | 6 |
| `RetrievalLifecycleWiringTest` | 2 |
| `RetrievalContextEndToEndTest` | 1 |
| **合计新增** | **15** |

## 6. 验收命令与结果

### 6.1 运行环境说明

首次在受限沙箱直接运行 Gradle 时，Gradle 启动阶段报 `FileLockContentionHandler ... SocketException: Operation not permitted`。随后按项目入口在获批的非沙箱环境重跑同一命令；以下为最终有效实跑结果。代码写完后执行了 `./scripts/dev gradle spotlessApply`。

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle spotlessApply` | 0 | 2s | `BUILD SUCCESSFUL`；57 tasks，6 executed / 51 up-to-date |
| `./scripts/dev gradle :context:api:test :context:runtime:test :integration-tests:context:test --rerun-tasks --console=plain` | 0 | 46.25s | `BUILD SUCCESSFUL in 45s`；32 tasks executed |
| `./scripts/dev gradle :context:api:spotlessCheck :context:runtime:spotlessCheck :integration-tests:context:spotlessCheck --rerun-tasks --console=plain` | 0 | 6.12s | `BUILD SUCCESSFUL in 5s`；13 tasks executed |
| `./scripts/check` | 0 | 137.40s | `BUILD SUCCESSFUL in 2m 16s`；131/131 tasks executed |
| `git diff --check` | 0 | - | 空输出 |

## 7. 14 模块源码 `@Test` 与 XML

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 38 | 38 | 0 | 0 | 0 |
| context-core | 119 | 119 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 623 | 623 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 24 | 24 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **1882** | **1882** | **0** | **0** | **0** |

B2 前基线 **1867**，新增 **15**，最终 **1882=1882**。

## 8. CT20 B1 / CT19 / CT18 / Schema / 81 基线

### 8.1 CT20 B1

- CT20 B1 19 个代码/资源/测试 SHA 逐项复算：**19/19 一致**。
- B1 Search 链、Prompt/Schema、Parser/Binding/Service 测试未修改。

### 8.2 CT19 B1/B2

- CT19 B1 20 个代码/测试中 **17/20 未变**；`ContextConfig.kt`、`ContextConfigLoader.kt`、`ContextConfigLoaderTest.kt` 为 B2 白名单要求的有意增量修改。
- CT19 B2 10 个代码/测试中 **7/10 未变**；`AlcyoneContext.kt`、`AlcyoneContextFactory.kt`、`AlcyoneContextImpl.kt` 为 B2 白名单要求的有意增量修改。
- CT19 Map/Bootstrap 组件、Map 测试与其余 CT19 文件未修改。

### 8.3 CT18 / CT17

- CT18 17 个代码/测试 SHA：**17/17 一致**。
- CT17 保持 CT19 B2 终态：21/23 未变；`AlcyoneContext.kt` 与 `AlcyoneContextImpl.kt` 的既有 CT19 B2 白名单变化继续存在，CT20 B2 仅按本批白名单追加 retrieval 契约/接线。
- CT20 B2 未修改 CT17/CT18 Generator/Publisher/Renderer/VFS/Resource 行为。

### 8.4 Schema / 81 VFS 基线

- `ContextSchema.sq` SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 81 VFS 基线：**75 一致 + 6 个 CT17 已批准差异，缺失 0，未批准差异 0**。
- B2 未修改 VFS/common/Schema/SQL/依赖/构建；Core 主源码无 Ktor/kotlinx.serialization。

### 8.5 生产边界

- 未修改 `ContextExceptions.kt`、`ContextStatus`/`WorkerStatus`、B1 检索链或 CT17/CT18/CT19 非白名单文件。
- 未实现 HTTP、FTS、Embedding、向量库或 CT21。
- Search 每次调用使用当前实例资源；start 不校验/构造 Search Service，不创建后台 Search Worker。
- Get/GetRaw 仅 SQLite；Search 不读 VFS、不依赖 Map 当前性。

## 9. 未运行项与残余风险

- 真实模型：**not-run**；真实 Ktor client 仅连接本机 JDK Fake HTTP stub，不证明真实语义相关性。
- Search 依赖 B1 全候选 Rerank；候选总量超单请求预算时明确失败，不降级/截断。
- Search 允许短暂旧快照；不执行 T1/T2 强一致检查或自动重试。
- Get/GetRaw 对合法但不存在返回 null；数据库故障原样抛 `ContextPersistenceException`。
- DB 错误测试通过运行中重命名 Organized 表制造真实 SQLite 查询失败；未模拟物理数据库文件损坏/连接中断全矩阵。
- CT21 SDK 全景评测、Search→Resource 跨模块取消回归、HTTP、部署/Backup/Dreaming 未启动。

## 10. 最终状态

**提交 Lead 初验，CT20 未 DONE，CT21 未启动。**

真实模型 not-run；无 `git add/commit/push/stash`、分支、回滚、发布或其他 Git 写操作。
