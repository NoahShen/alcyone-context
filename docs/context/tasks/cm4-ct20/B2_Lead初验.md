# CT20 B2 · Lead 初验

## 本次修改（2026-11-09，B2 Lead 初验）

完成 CT20 B2 独立初验：核对 13 文件白名单与 SHA，审查三方法默认契约、RetrievalConfig/Loader、Factory/Impl 生命周期与 Search/Get 隔离，复跑三模块定向、Spotless、B2 Runtime/Lifecycle、真实 SQLite/Ktor/Fake HTTP E2E 和全量 `./scripts/check`。结论为 **B2 初验通过、提交 Architect 复核；CT20 未 DONE，CT21 未启动**。

**日期**：2026-11-09
**状态**：**B2 IN_REVIEW；提交 Architect 独立复核**
**Worker**：`059d6c59-ce9e-4e68-9055-b45b238506a1`（`codex-magpie/group/worker`、`auto-review`、high）
**依据**：[B2 委派单](B2_委派单.md)、[B2 Worker 交付](B2_Worker交付.md)、[B1 Architect 复核](B1_Architect复核.md)、[Architect 裁定](Architect_裁定.md)、[CT20 任务书](CT20_任务书.md)
**基线**：CT20 B1 APPROVED / DONE；14 模块 **1867=1867**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 1. 初验结论

**CT20 B2 Lead 初验通过，提交 Architect 独立复核。**

- Worker 严格交付 B2 委派单 §3 的 13 个白名单文件：12 个代码/测试文件 + 1 份交付报告；白名单外 B2 变更 0。
- 新增 15 个测试，14 模块源码 `@Test` 与 XML 均为 **1882=1882**，fail/error/skip = **0/0/0**。
- `AlcyoneContext` 三方法默认 UNAVAILABLE；Real Impl 完成 NOT_STARTED/CLOSED 门控。
- Search 复用 B1 `MemorySearchService`，调用时校验 MEMORY_SEARCH binding，不在 start 创建后台 Search。
- Get/GetRaw 为纯 SQLite 精确读取，不存在返回 null、空白 ID INVALID_REQUEST、DB 错误不伪装。
- `context.retrieval.search.maxLimit` 默认 50、范围 1..1000，Loader 严格拒绝未知键/类型/越界。
- 真实 SQLite + Ktor client + Fake HTTP E2E 通过；B1 检索链、ContextExceptions、CT17/CT18/CT19 非白名单文件、VFS/common/Schema/SQL/依赖/构建未改。

## 2. 白名单与 SHA

### 2.1 文件集

- 委派单 §3 的 13 个文件全部存在；12 个代码/测试文件 SHA 与 [B2 Worker 交付](B2_Worker交付.md) §2 逐项一致。
- 交付报告为第 13 个白名单文件，不内嵌自引用 SHA。
- 实际组成为：API 2 + Runtime 生产 5 + Runtime 测试 4 + Integration E2E 1 + 交付报告 1。

### 2.2 关键文件 SHA

| 文件 | Lead 复算 SHA-256 |
| --- | --- |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/AlcyoneContext.kt` | `3379ce95e70805199f2cf0d9b0c6f1fec27b5d2eb6a515b659b9e3aed81146db` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/retrieval/RetrievalConfig.kt` | `22eb86d43f573f1353d464c5559aaaf323ec160a6c627bac26e76a2b572628c3` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/ContextConfigLoader.kt` | `750bcada432b1ee6afff9a3d938704ac38a19cea9d98ba7ebee3311e7e7edf29` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextImpl.kt` | `1256adc79d2062933846a8e6a84489b62fa8cbd52035b913651ccd89cdee05b3` |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/RetrievalContextEndToEndTest.kt` | `597eced73d092de3a972d0d70cf0ce016754d27286116066ca6e266d7e40f887` |

## 3. 独立实跑证据

| 命令 | exit | 耗时 | 关键结果 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle :context:api:test :context:runtime:test :integration-tests:context:test --rerun-tasks --console=plain` | 0 | 47s | 32 tasks；三模块全绿 |
| `./scripts/dev gradle :context:api:spotlessCheck :context:runtime:spotlessCheck :integration-tests:context:spotlessCheck --rerun-tasks --console=plain` | 0 | 6s | 13 tasks；格式通过 |
| `./scripts/check` | 0 | 2m17s | 131/131 tasks；全模块构建、Spotless、测试通过 |
| B2 Runtime Config/Wiring/Lifecycle 定向 | 0 | 10s | 11 例通过 |
| `RetrievalContextEndToEndTest` 定向 | 0 | 6s | 真实 SQLite/Ktor/Fake HTTP 通过 |
| 完整 Runtime + integration-context 恢复运行 | 0 | 45s | 623 + 24 例通过；恢复完整 XML |
| `git diff --check` | 0 | - | 空输出 |

### 3.1 14 模块 source/XML

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

B2 前 **1867**，新增 **15**，最终 **1882=1882**。

## 4. 行为核对

### 4.1 公共 API / Lifecycle

- 第三方三方法默认均抛 `ContextRetrievalException(UNAVAILABLE)`；不返回假空/假 null。
- Impl CREATED/STARTING → NOT_STARTED；STOPPING/CLOSED → CLOSED。
- Search/Get/GetRaw 共享统一 lifecycle 门控；STOPPING 通过真实 release probe 窗口验证。

### 4.2 Config / Loader

- `RetrievalConfig(search=SearchConfig(maxLimit=50))`；ContextConfig 旧构造默认兼容。
- SearchConfig 直接构造 1/1000 通过，0/负/1001 → ContextConfigException(INVALID_CONFIG)。
- Loader 缺省/空 mapping 为 50，覆盖 1/1000 正确；unknown/type/越界安全拒绝。
- Loader 只解析 maxLimit，不要求 LLM/MEMORY_SEARCH，不创建 Search 服务。

### 4.3 Factory / Impl

- 公共 Factory `create()` 签名不变；只将 retrieval 配置传入 Impl。
- Search 每次调用构造 B1 `MemorySearchService`，使用当前 DB、LLM client、LLM config 与 maxLimit。
- 缺 LLM/缺 MEMORY_SEARCH/PROMPT_ONLY 只在 Search 调用时 CONFIGURATION；start/Bootstrap/Get 不受影响。
- Search 不在 start 时校验/构造服务，不创建后台 Worker。
- Get/GetRaw 生命周期门控后直接 `database.read { findById }`；trim 后空白 ID INVALID_REQUEST。
- 不存在返回 null；DB 错误通过真实 SQLite 表损坏场景保留 ContextPersistenceException。
- Get/GetRaw 0 LLM；重复读取不增加 HTTP 请求。

### 4.4 E2E

- 真实 Factory/initializeDatabase/SQLite/Runtime/Ktor client + JDK HTTP stub。
- Scanner + Rerank 共 2 请求；Search 返回真实 SQLite Organized 内容。
- Get/GetRaw 返回真实 SQLite 对象；重复 5 次不增加 LLM 请求。
- close 后 Search/Get/GetRaw 均 CLOSED。

## 5. 边界核对

### 5.1 CT20 B1 / CT19 / CT18 / Schema / VFS

- CT20 B1 Worker 交付的 19 个代码/资源/测试 SHA：**19/19 一致**。
- CT19 B1 20 文件：**17 未变 + 3 个 B2 白名单要求变化**（ContextConfig、Loader、LoaderTest）。
- CT19 B2 10 文件：**7 未变 + 3 个 B2 白名单要求变化**（AlcyoneContext、Factory、Impl）。
- CT18 17 文件：**17/17 一致**。
- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 81 VFS 基线：**75 一致 + 6 个 CT17 已批准差异，缺失 0，未批准差异 0**。

### 5.2 生产边界

- `ContextExceptions.kt` SHA 保持 CT17 终态 `942402ea…b50c9`，B2 未修改。
- `ContextStatus.kt`、`WorkerStatus.kt` 未修改；未新增公共状态字段。
- B1 Search Prompt/Schema/Parser/Binding/Service 与测试未修改。
- 构建文件、Gradle 版本目录、common、Schema/SQL、依赖无 B2 变更。
- 未实现 HTTP/FTS/Embedding/向量库/CT21。

## 6. 非阻断观察

**N1：Search 在调用时创建轻量 Service。** 每次 `searchMemory` 构造一个 B1 `MemorySearchService` 并读取 v4 Prompt/Schema 资源。

- 不持有后台状态、不产生 worker，避免 start 时配置失败扩散；符合 D6。
- 资源从 classpath 读取，成本相对 LLM/全量扫描可忽略；若后续高 QPS，可另立缓存优化。
- 当前不阻断。

**N2：Search 与 close 的极端交错由既有 DB/LLM close 协议承担。** B2 在状态锁内取得资源引用，之后锁外执行；CT21 将补全景取消/清理回归。当前无额外共享可变状态，不阻断。

## 7. 未运行项与残余风险

- 真实模型：**not-run**；Fake HTTP 只证明协议、预算、排序、权威回读和生命周期，不证明语义相关性。
- Search 候选总量超单请求 Rerank 预算时明确失败，不降级/截断。
- Search 允许短暂旧快照，不做 T1/T2 强一致检查或自动重试。
- DB 故障使用真实 SQLite 表重命名场景，未模拟物理文件损坏/连接中断全矩阵。
- CT21 SDK 全景评测、Search→Resource 跨模块取消回归、HTTP、部署/Backup/Dreaming 未启动。

## 8. 当前状态

- **CT20 B2：IN_REVIEW，Lead 初验通过，提交 Architect 独立复核。**
- **CT20：IN_PROGRESS。**
- **CT21：LOCKED。**
- 真实模型 not-run；无 Git add/commit/push/stash、分支、回滚或发布操作。
