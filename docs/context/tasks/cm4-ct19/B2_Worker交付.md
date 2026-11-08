# CT19 B2 · Bootstrap、Runtime 接线与 Organization Wakeup Worker 交付

## 本次修改（2026-11-08，B2 初始交付）

新增本交付报告，记录 CT19 B2 的 11 文件白名单、公共 `bootstrap()`、Factory/Impl 生产接线、Memory Map Worker 状态、Organization 提交后真实 wakeup、20 个新增测试、三条验收命令、14 模块 **1819=1819**、B1/CT17/CT18/Schema/81 基线与残余风险。本文件不把 B2、CT19 或后续任务标记为 DONE。

**日期**：2026-11-08
**状态**：**提交 Lead 初验，CT19 未 DONE，CT20/CT21 未启动**
**依据**：[B2 委派单](B2_委派单.md)、[CT19 任务书](CT19_任务书.md)、[Architect 裁定](Architect_裁定.md)、[B1 Architect 复核](B1_Architect复核.md)、[CT18 收口报告](../cm4-ct18/CT18_收口报告.md)
**基线**：CT19 B1 APPROVED / DONE；14 模块 **1799=1799**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；工作区既有 CM3/CT17/CT18/CT19 B1 未提交交付保持不动。

## 1. 交付结论

严格完成 B2 委派单 §3 的 11 个文件：**10 个代码/测试文件 + 本交付报告**。

- API `AlcyoneContext.bootstrap()` 默认契约与 `AlcyoneContextImpl` 真实只读缓存实现完成。
- Factory 在任何资源打开前执行 Memory Map LLM 绑定校验；公共 `create()` 签名不变且仍零副作用。
- Runtime start 构造/启动唯一 Memory Map Pipeline，RUNNING 发布后按 Organization → Extraction → MemoryMap 激活。
- Organization 提交后的生产默认 Map wakeup 已接到当前 Pipeline 的 CONFLATED 刷新入口，不再是永久 no-op。
- `ContextStatus.workers.memoryMap` 已映射 CREATED/STARTING/RUNNING/FAILED/STOPPED，不修改 public `ContextStatus`/`WorkerStatus` 类型。
- B2 新增 **20** 个测试：API +1、Runtime +18、integration-context +1。
- 14 模块源码 `@Test` 与 XML 均为 **1819**，fail/error/skip = **0/0/0**。
- 三模块定向、三模块 Spotless、全量 `./scripts/check`、真实 SQLite+LocalFs+Fake HTTP E2E 均通过。
- 真实模型 **not-run**；无任何 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 API（2）

| 文件 | SHA-256 |
| --- | --- |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/AlcyoneContext.kt` | `9f2cde1bd792836c05b561d4235ac5f271a07c4481de214c78538c9b9345d37c` |
| `context/api/src/test/kotlin/com/github/noahshen/alcyone/context/memory/api/AlcyoneContextBootstrapTest.kt` | `19dee29b4f4d741f9d65d6a5a7a416d065a5b3f19b70dffe6c5c04826e4c4b5b` |

### 2.2 Runtime 生产（2）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextFactory.kt` | `e23aa8bfe498b08ac6d02222b7f18774d756bbbded9ca9ef1b980e2944e4f3e0` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextImpl.kt` | `3d99e6ca4024066996b25d4cac6027538393e2debd8c0c20d104d156e84b809f` |

### 2.3 Runtime 测试（5）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/MemoryMapFactoryBindingTest.kt` | `18451a01be3d8217be74a6dd41e52e58f1dcb7f88adc31a903d759873015448e` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/MemoryMapRuntimeWiringTest.kt` | `eea1289bd110aabf8030a0ae33c9df856da9223ff21f1fcdfbcc265e1afe135c` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/MemoryMapLifecycleWiringTest.kt` | `9d4850d6b3d4d164b48035d6dcec7875ebfbca00a52b0f4f8c40f3abfcdb0833` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/MemoryMapOrganizationWakeupTest.kt` | `62a94fcb8f74764867ae53580a2185d556b68f2814c9edf26e811a001699aee9` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/MemoryMapRuntimeTestSupport.kt` | `864e6a3e08ab756866fba71c87096437895c5db6613c9a000a128e2e8c03798d` |

### 2.4 Integration E2E（1）

| 文件 | SHA-256 |
| --- | --- |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/MemoryMapContextEndToEndTest.kt` | `0f01eca178a4f2f697a6320e913021132f4ef0f140fc66198ff462dd4262e1ff` |

### 2.5 交付报告（1）

| 文件 | SHA-256 |
| --- | --- |
| `docs/context/tasks/cm4-ct19/B2_Worker交付.md` | 本文件自身不内嵌自引用 SHA |

白名单外 B2 代码/测试/构建变更：**0**。工作区既有 CM3/CT17/CT18/CT19 B1 改动未被回滚、丢弃或改写。

## 3. 实现说明

### 3.1 公共 Bootstrap 契约

- `AlcyoneContext.bootstrap()` 默认返回 `memoryMap=null`、`mapStatus=UNKNOWN`、`BootstrapContext.DEFAULT_CAPABILITIES`，第三方实现无需新增状态即可安全编译。
- `AlcyoneContextImpl.bootstrap()` 在状态锁内只读取 `MemoryMapPipeline.snapshot()`；不查 SQLite、不读 VFS、不调 LLM、不发刷新请求。
- CREATED/STARTING → `ContextLifecycleException(NOT_STARTED)`；STOPPING/CLOSED → `CLOSED`。
- RUNNING 且 Map 未配置 → `null + MISSING + fixed capabilities`；已配置 → Pipeline 原子 View/Status；异常缺失 pipeline 时保守返回 `UNKNOWN/null`。

### 3.2 Factory / 配置门

- `validateMemoryMapBinding(contextConfig.memoryMap, ownedLlmConfig)` 在数据库、VFS、LLM client 创建之前执行。
- Map 未配置时不要求 LLM、不创建 pipeline；显式配置时必须有 `MEMORY_MAP` Task/Profile 且为 `JSON_SCHEMA`/`JSON_OBJECT`。
- 公共 `create()` 签名不变，继续传 null 测试注入项；内部重载新增 `memoryMapGenerator`、`memoryMapTickSource`、`memoryMapPipelineConfigurator` 默认 null seam。
- 合法配置在 start 内使用生产 `MemoryMapGenerator + MemoryMapPublisher + MemoryMapService`；测试可替换 Generator，Publisher/VFS 仍走真实安全发布路径。

### 3.3 Start / Activate

- create 仍不打开数据库、VFS、LLM 或后台协程。
- start 的资源取得顺序为 executor → VFS → LLM → Organization → MemoryMap → Extraction；Pipeline 构造/启动失败进入既有 NonCancellable 逆序清理。
- RUNNING 发布前 MemoryMapPipeline 只持有启动检查信号，不读取/生成/发布。
- RUNNING 发布后依次激活 Organization → Extraction → MemoryMap；`start()` 不等待 Map LLM 完成。

### 3.4 Organization Wakeup

- 生产默认 `OrganizationMapWakeup` 不再注入永久 no-op，而是捕获当前实例的 MemoryMapPipeline 并合并一次 Coordinator 刷新信号。
- 该通知仅同步 `Channel.CONFLATED.trySend`，不等待 LLM/发布；Map 未配置、Pipeline closing/closed 或请求拒绝时安全 no-op。
- `DefaultOrganizationWorker` 只在 `OrganizationCommitResult.Committed` 后调用 effective wakeup；单次 wakeup 普通失败仍由既有 worker 隔离。
- 测试注入能力保留；仅显式注入时覆盖生产闭包。

### 3.5 Close / Worker 状态

- RUNNING → STOPPING 的同一状态锁内调用 Extraction/Organization/MemoryMap 三个 `beginClosing()`，Map 新请求立即拒绝。
- 固定释放顺序：**Extraction → Organization → MemoryMap → LLM → VFS → executor**。
- MemoryMapPipeline 自己先停 Cron，再取消并真实 join Coordinator；并发 close 共享同一主失败与 suppressed。
- `workers.memoryMap` 仅在 `context.memoryMap != null` 时出现：CREATED/STARTING=IDLE；RUNNING 映射 IDLE/RUNNING/FAILED；STOPPING/CLOSED=STOPPED。
- 单轮 Map 失败只记录 `FAILED`，Context 保持 RUNNING；后续信号可重新进入 RUNNING/IDLE。

## 4. 测试矩阵实际断言

### 4.1 A01～A08（B2 负责部分）

| 验收 | B2 测试 | 实际断言 |
| --- | --- | --- |
| A01 配置/启用门 | `MemoryMapFactoryBindingTest` | 未配置零 pipeline/零 LLM；缺 LLM、缺 Task、PROMPT_ONLY 均 create 前 INVALID_CONFIG；JSON_SCHEMA/OBJECT 通过；create 零文件副作用 |
| A02 启动与 Bootstrap | `MemoryMapRuntimeWiringTest` | NOT_STARTED/CLOSED；未配置 MISSING；STARTING Map 0 生成；RUNNING 初始 UNKNOWN/MISSING 不等待；成功 STALE → CURRENT |
| A03 单 Worker | B1 测试保持；B2 status/wakeup | 启动检查只生成一次；重复 wakeup 只合并信号；bootstrap 不触发 Worker |
| A04 三类入口 | `MemoryMapOrganizationWakeupTest`、E2E | Runtime start 检查、Organization committed effective wakeup；Cron 由 B1 保持 |
| A05 MapStatus/Worker | `MemoryMapRuntimeWiringTest` | CREATED/STARTING IDLE、RUNNING RUNNING/IDLE/FAILED、STOPPED；单轮失败不升级 Context 失败 |
| A06 Bootstrap | API 测试、RuntimeWiring、E2E | 默认契约；只读缓存；重复 bootstrap 0 LLM 请求；capabilities 固定；关闭后 CLOSED |
| A07 生命周期 | `MemoryMapLifecycleWiringTest` | 关闭顺序、STOPPING 窗口、在途生成真实 join、Map start 失败清理、并发 close 共享失败 |
| A08 集成/边界 | `MemoryMapContextEndToEndTest`、全量回归 | 真实 SQLite+LocalFs+Ktor client+JDK HTTP stub；文件/正文/Front Matter/状态/close 无后台残留 |

### 4.2 用例数

| 测试类 | 新增用例 |
| --- | ---: |
| `AlcyoneContextBootstrapTest` | 1 |
| `MemoryMapFactoryBindingTest` | 4 |
| `MemoryMapRuntimeWiringTest` | 5 |
| `MemoryMapLifecycleWiringTest` | 5 |
| `MemoryMapOrganizationWakeupTest` | 4 |
| `MemoryMapContextEndToEndTest` | 1 |
| **合计新增** | **20** |

## 5. 验收命令与结果

### 5.1 运行环境说明

首次在受限沙箱直接运行 Gradle 时，Gradle 启动阶段报 `FileLockContentionHandler ... SocketException: Operation not permitted`。随后按项目入口在获批的非沙箱环境重跑同一命令；以下为最终有效实跑结果。代码写完与最终 KDoc 修正后均执行了 `./scripts/dev gradle spotlessApply`。

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle spotlessApply` | 0 | 2s | `BUILD SUCCESSFUL`；57 tasks，2 executed / 55 up-to-date |
| `./scripts/dev gradle :context:api:test :context:runtime:test :integration-tests:context:test --rerun-tasks --console=plain` | 0 | 50.04s | `BUILD SUCCESSFUL in 49s`；32 tasks executed |
| `./scripts/dev gradle :context:api:spotlessCheck :context:runtime:spotlessCheck :integration-tests:context:spotlessCheck --rerun-tasks --console=plain` | 0 | 6.13s | `BUILD SUCCESSFUL in 5s`；13 tasks executed |
| `./scripts/check` | 0 | 140.64s | `BUILD SUCCESSFUL in 2m 20s`；131/131 tasks executed |
| `git diff --check` | 0 | - | 空输出 |

补充定向证据：`MemoryMapContextEndToEndTest` 单独实跑 exit 0、10.86s、28 tasks，真实 LocalFs 发布与 HTTP stub 请求闭环通过。

## 6. 14 模块源码 `@Test` 与 XML

源码范围为各模块 `src/test/**/*.kt` 行首（允许缩进）`@Test`；XML 为最终 `build/test-results/test/TEST-*.xml`。

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 36 | 36 | 0 | 0 | 0 |
| context-core | 110 | 110 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 572 | 572 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 23 | 23 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **1819** | **1819** | **0** | **0** | **0** |

B2 前基线 **1799**，新增 **20**，最终 **1819=1819**。

## 7. B1 / CT17 / CT18 / Schema / 81 基线

### 7.1 B1 组件

- 按 [B1 Worker 交付](B1_Worker交付.md) §2 的 20 个代码/测试 SHA 逐项复算：**20/20 一致**。
- B1 Map Config/Tracker/Reader/Coordinator/Cron/Pipeline、Bootstrap 三模型与 B1 测试均未修改语义。

### 7.2 Context Schema

- `ContextSchema.sq` SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 未新增表、索引、`user_version`、SQL 或 MapStatus 持久化。

### 7.3 CT17 / CT18

- CT18 17 个代码/测试文件按 [CT18 Worker 交付](../cm4-ct18/B1_Worker交付.md) SHA 表复算：**17/17 一致**。
- CT17 23 文件基线中 **21/23 一致**；两处有意变化严格落在 B2 白名单：
  - `AlcyoneContext.kt`：新增默认 `bootstrap()`；
  - `AlcyoneContextImpl.kt`：Runtime Map/Bootstrap 接线。
- 其余 CT17 21 个文件未变；无 CT17/CT18 Generator/Publisher/Renderer/VFS/Resource 行为改写。

### 7.4 81 VFS 基线

按 `.local/validation/cm1/architect/vfs-baseline-sha256.json` 复算：

- **81 项中 75 项一致、6 项 CT17 已批准差异、缺失 0、未批准差异 0**。
- B2 未修改 VFS/common/Schema/SQL/依赖/构建；构建文件与版本目录状态检查为空。
- Core 主源码禁止导入检查：无 Ktor、无 kotlinx.serialization。

### 7.5 API / 生产边界

- 公共 API 仅新增 `bootstrap()` 默认方法；未新增 Search/Get/HTTP、公开 Factory 参数或公开 Map 内部类型。
- `ContextStatus`/`WorkerStatus` 文件未修改；`memoryMap` 通过既有 `workers: Map<String, WorkerStatus>` 增加条目。
- Factory 公共 `create()` 签名未变；Map 绑定校验在资源打开前完成。
- Bootstrap 实现静态路径只有 `MemoryMapPipeline.snapshot()`，无 DB/VFS/LLM/requestRefresh 调用。
- OrganizationMapWakeup 生产路径不再为永久 no-op；测试注入仍可覆盖。

## 8. 未运行项与残余风险

- 真实模型：**not-run**；Fake HTTP 只证明请求/响应协议、预算、发布、缓存与生命周期，不证明语义质量。
- E2E 使用 `periodic.enabled=false` 的启动检查路径；B1 已覆盖注入 tick 的 Cron 严格未来、退避与关闭，但未做多日 wall-clock soak。
- B1 `MemoryMapPipeline` 未直接暴露 `requestRefresh()`；B2 生产 wakeup 复用其 internal Coordinator CONFLATED 准入，不改变 public API。若后续 B1/B2 重构需保留同一合并语义。
- Bootstrap 在 Worker 已读取 Map 但生成尚未完成时可能返回最近 `UNKNOWN/MISSING/旧 View` 快照；这是不等待 LLM 的既定语义。
- 发布成功后仍按 B1 D5 保守 STALE，下一次可靠水位检查才 CURRENT。
- safeReplace `STATE_ERROR + PARTIAL` 的物理新版可能与缓存旧 View 暂时不一致；后续读取与水位收敛，不伪造 CURRENT。
- CT20 Search/Get、HTTP 路由、真实模型评测、部署/Backup/Dreaming 均未启动。

## 9. 最终状态

**提交 Lead 初验，CT19 未 DONE，CT20/CT21 未启动。**

真实模型 not-run；无 `git add/commit/push/stash`、分支、回滚、发布或其他 Git 写操作。
