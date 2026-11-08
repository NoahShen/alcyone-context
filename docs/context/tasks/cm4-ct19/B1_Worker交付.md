# CT19 B1 · Map 状态、刷新协调与 Pipeline Worker 交付

## 本次修改（2026-11-08，B1 初始交付）

新增本交付报告，记录 CT19 B1 的 21 文件白名单、Map 三公共模型、Memory Map 配置解析、Tracker/Reader/Coordinator/Cron/Pipeline、43 个新增测试、三条验收命令、14 模块 **1799=1799**、Schema/CT17/CT18 SHA、81 VFS 基线及未接线边界。本文件不把 B1、CT19 或后续批次标记为 DONE。

**日期**：2026-11-08
**状态**：**提交 Lead 初验，CT19 未 DONE，B2/CT20 未启动**
**依据**：[B1 委派单](B1_委派单.md)、[CT19 任务书](CT19_任务书.md)、[CT19 方案核对](CT19_方案核对.md)、[Architect 裁定](Architect_裁定.md)、[CT18 收口报告](../cm4-ct18/CT18_收口报告.md)
**基线**：CT17/CT18 APPROVED / DONE；14 模块 **1756=1756**；`./scripts/check` 131 tasks；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；工作区既有 CM3/CT17/CT18 未提交交付保持不动。

## 1. 交付结论

严格完成 B1 委派单 §3 的 21 个文件：**20 个代码/测试文件 + 本交付报告**。其中新增 17 个代码/测试文件，增量修改既有 `ContextConfig.kt`、`ContextConfigLoader.kt`、`ContextConfigLoaderTest.kt` 3 个文件；加本报告共 21 个白名单文件。

- B1 新增 **43** 个测试：`context-api` +4、`context-runtime` +39。
- 14 模块源码 `@Test` 与 XML 均为 **1799**，fail/error/skip = **0/0/0**。
- API/Runtime 定向、两模块 Spotless、全量 `./scripts/check` 均通过。
- 未修改 `AlcyoneContext` 接口、Factory/Impl、Organization Pipeline、VFS/common/Schema/SQL/依赖/构建或 CT18 文件。
- 真实模型 **not-run**；无任何 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 API 公共模型（3）

| 文件 | SHA-256 |
| --- | --- |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/MapStatus.kt` | `b134c5869fb24d61f0bc7a03d50f80fe0f03a913daab79132c1f6fa298502c6e` |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/MemoryMapView.kt` | `553f7548fc255c13d0afbd1378e24f24e913b9738db3139bfb822acd92c7b035` |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/BootstrapContext.kt` | `c40883d75311a04e73faa2d50d7a5d069ad803fdd5d9cabb5765f51dc9fad901` |

### 2.2 Runtime 生产（8）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapConfig.kt` | `1b6f661c1ed3ac1f484df67435e47d5f6bcedda987c8b911efce86cfb6e508b2` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapStatusTracker.kt` | `ec8c333d33b0e719e285d0790e0d634206109aa3ad9f2f4cebd329f0171037d3` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapBootstrapReader.kt` | `1c59a03b691737b6f922137db7ccd8bcd42b6f590d2555e2e38a99f8327f8036` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapRefreshCoordinator.kt` | `503850cd88896ecbd68e4e0c35f209c40ba1902763a16afa1db9b6c8fa37ca8e` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapPeriodicCron.kt` | `100ad323643c195c158e8352084fb37a52b034b56537a87b9fdb7bc592986309` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapPipeline.kt` | `714b5a68cccb7b778ef40a040719a42789c43acab69e89ffa321beafe053b28a` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/ContextConfig.kt` | `499102808efc6a63b34b8de033d171a735828e1fe1d58fecc13c69e7592ad0bf` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/ContextConfigLoader.kt` | `24ddbb2cca37413f924abd47499dc2482e44eecba22d92a6f14c22575a3c79a5` |

### 2.3 测试（9）

| 文件 | SHA-256 |
| --- | --- |
| `context/api/src/test/kotlin/com/github/noahshen/alcyone/context/memory/api/BootstrapContextTest.kt` | `02068ce46ce4da9acf6885c90b665f93c080783d1cfaf82994d2568e3f263a6b` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapConfigTest.kt` | `0cf667e006447803c78aa375d93d70e24c7b4715ab621cd402ec6bbfdd4430d2` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapStatusTrackerTest.kt` | `a8e753c280bad680547b0d3a6a5c7d8a65e7c0ef15d5758c862f2bf9db4525a3` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapBootstrapReaderTest.kt` | `af16dcb8709c21f5243db23ddfbad6d7d0c6900e6ca7763ff74ea21e28e23b7f` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapRefreshCoordinatorTest.kt` | `9b85978be25cd3764a650f01967b328dae3eb9a6e1c76b79cc399ea526a00c7a` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapPeriodicCronTest.kt` | `55a4b7739290ff3012a77d70a24723ed8578f895f091f73243653778a82c3a32` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapPipelineTest.kt` | `e983a915d15caa320b8e3e11d643cfae08eea7d45a9653270b72793295bf18b3` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapTestSupport.kt` | `934f3b186837e5b0acce3e80a72258b27286e546b446e273bb1f9f0066750018` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/ContextConfigLoaderTest.kt` | `7fd50b2e20ca2b148b29bfccb2c985ea9b3b6c26874d9bca2501fedae974ac90` |

### 2.4 交付报告（1）

| 文件 | SHA-256 |
| --- | --- |
| `docs/context/tasks/cm4-ct19/B1_Worker交付.md` | 本文件自身不内嵌自引用 SHA |

白名单外 B1 代码/测试/构建变更：**0**。工作区既有 CM3/CT17/CT18 改动未被回滚、丢弃或改写。

## 3. 实现说明

### 3.1 公共模型

- `MapStatus` 固定四态：`CURRENT / STALE / MISSING / UNKNOWN`。
- `MemoryMapView(content, generatedAt, sourceUpdatedAt)` 保存严格解析后的完整 Markdown、生成时间与可空 T1；拒绝空白正文。
- `BootstrapContext(memoryMap, mapStatus, capabilities)` 固定返回结构，并提供静态 `DEFAULT_CAPABILITIES`；未增加 `AlcyoneContext.bootstrap()`，该接线归 B2。

### 3.2 配置

- `ContextConfig.memoryMap: MemoryMapConfig?`，缺省 `null` 表示禁用；非 null 即显式启用。
- `context.memoryMap` 出现空映射时使用 `periodic.enabled=true`、`cron="*/10 * * * *"`。
- 固定 `Asia/Shanghai`，无 `timezone` 字段；Loader 严格拒绝 unknown、错误类型与非法五字段 cron，cron 解析异常不挂原始 cause。
- `validateMemoryMapBinding()` 作为 B2 可复用的纯校验 helper：要求 `MEMORY_MAP` Task/Profile，且 Profile 为 `JSON_SCHEMA` 或 `JSON_OBJECT`。本批不调用 Factory。

### 3.3 Tracker / Reader

- Tracker 在单锁内原子保存 `MemoryMapState(view,status)`；快照永不返回混合字段。
- `CURRENT/STALE` 必须带有效 View；`UNKNOWN` 可保留上一份有效 View；`MISSING` 清空 View。
- Reader 只读取固定 `alcyone://memory/map.md`，严格调用 CT18 `MemoryMapRenderer.parse()`；不查 DB、不调 LLM、不触发刷新。
- `NOT_FOUND → MISSING`；破损/非法 UTF-8/严格解析失败/VFS 读取失败 → `UNKNOWN`，破损字节不进入 View。

### 3.4 RefreshCoordinator

- `Channel<Unit>(CONFLATED)` + 唯一 Worker；Worker 数不随请求增长。
- 每轮先读固定 Map，再在单次 `database.read` 中读取 Organized `MAX(updated_at)`。
- 可靠 Map 且 `sourceUpdatedAt == DB MAX` → `CURRENT` 并跳过生成；缺失/破损/陈旧 → CT18 `MemoryMapService.generateAndPublish()`。
- 发布成功后不查 T2，保守更新为 `STALE`；下一轮水位比较追平后才 `CURRENT`。
- 生成/VFS 发布失败恢复本轮开始前的 View/Status；DB 水位失败置 `UNKNOWN` 并保留旧 View；普通错误日志不挂异常、不输出正文。
- `CancellationException` 原样传播；`beginClosing()` 同步封准入且不创建新 Worker。

### 3.5 PeriodicCron / Pipeline

- Cron 为 UNIX 五字段、固定上海时区、启动不立即扫描、严格未来、不补跑；每 tick 只做 DB MAX 与缓存状态的轻量比较，不在 Cron 内等待 LLM。
- Cron 普通失败 WARN + 固定 1 秒可取消退避；close 立即取消并真实 join。
- Pipeline 组合 Tracker/Reader/Coordinator/Cron；`start()` 启动资源并异步请求一次启动检查，`activate()` 前 Worker 不读取/生成。
- `beginClosing()` 同步封准入；`closeAsync()` 顺序为停 Cron → 取消并 join Worker → 发布共享关闭结果。并发等待者共享同一失败。

## 4. 并发与失败矩阵实际断言

| 场景 | 实际断言 |
| --- | --- |
| 连续 request | 20 次生成中补信号经 CONFLATED 合并，最终仅再执行 1 轮 |
| 单 Worker | 所有 Coordinator 用例断言 `workerCreations=1` |
| DB 已 CURRENT | 0 生成，Tracker 置 CURRENT |
| Map NOT_FOUND | Reader 置 MISSING；Coordinator 请求后生成 |
| Map 破损 | Reader 置 UNKNOWN 且不返回破损正文；Coordinator 请求后生成完整替代 |
| stale Map | DB MAX 不同，进入生成 |
| 生成失败 | 恢复旧 View/Status；WARN 不含异常/测试标记 |
| safeReplace 失败 | 不伪造 CURRENT；恢复旧 View/Status，Worker 状态 FAILED |
| DB 水位失败 | UNKNOWN + 保留有效旧 View；0 生成 |
| Cron tick 重叠 | 受控阻塞下最大并发扫描数为 1 |
| Cron 查询失败 | WARN + 1 秒固定可取消退避；close 不等待满退避 |
| activation 前 | start 后 150ms 仍 0 生成；activate 后才生成 |
| beginClosing 后 | 拒绝新 request；不创建新 Worker；排队信号不生成 |
| Worker/Cron 关闭 | 在途生成/查询均取消并真实 join；最终 CLOSED/STOPPED |
| Bootstrap 并发刷新 | 生成阻塞时 snapshot 在 500ms 内立即返回最近缓存，不等待 LLM |
| CancellationException | Reader/Coordinator 均不包装；直接测试断言同一实例传播 |

## 5. 测试映射

### 5.1 A01～A08（B1 负责部分）

| 验收 | B1 正式测试 | 关键覆盖 |
| --- | --- | --- |
| A01 配置/启用门 | `MemoryMapConfigTest`、`ContextConfigLoaderTest` | 缺省/空映射/覆盖/ENV/unknown/timezone/type/cron；JSON 绑定 helper |
| A02 启动检查与四态 | `MemoryMapBootstrapReaderTest`、`MemoryMapRefreshCoordinatorTest` | NOT_FOUND/破损/VFS 失败/CURRENT/STALE/MISSING/UNKNOWN |
| A03 Conflated Worker | `MemoryMapRefreshCoordinatorTest` | 单 Worker、合并信号、执行中补信号、无并行 |
| A04 三类入口 | `MemoryMapPipelineTest`、`MemoryMapPeriodicCronTest` | 启动检查、Cron 补偿；Organization wakeup 留 B2 |
| A05 MapStatus | `MemoryMapStatusTrackerTest`、Coordinator | 四态、原子配对、不伪 CURRENT |
| A06 Bootstrap 只读 | `BootstrapContextTest`、`MemoryMapPipelineTest` | 公共字段、四态、缓存快照、不等待生成 |
| A07 生命周期 | `MemoryMapPipelineTest` | start/activate/beginClosing/close、顺序、共享失败、真实 join |
| A08 集成/边界 | 全部 map 测试、真实 SQLite + LocalFs fixture、全量回归 | 真实发布/读取；Schema/CT17/CT18/81 基线/依赖边界 |

### 5.2 用例数

| 测试类/增量 | 新增用例 |
| --- | ---: |
| `BootstrapContextTest` | 4 |
| `MemoryMapConfigTest` | 4 |
| `ContextConfigLoaderTest`（本批新增） | 3 |
| `MemoryMapStatusTrackerTest` | 4 |
| `MemoryMapBootstrapReaderTest` | 4 |
| `MemoryMapRefreshCoordinatorTest` | 9 |
| `MemoryMapPeriodicCronTest` | 6 |
| `MemoryMapPipelineTest` | 9 |
| **合计新增** | **43** |

## 6. 验收命令与结果

### 6.1 运行环境说明

首次在受限沙箱直接运行 Gradle 时，Gradle 在启动阶段报 `FileLockContentionHandler ... SocketException: Operation not permitted`。随后按项目入口在获批的非沙箱环境重跑同一命令；以下为最终有效实跑结果。代码写完及每次修正后均执行了 `./scripts/dev gradle spotlessApply`。

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle spotlessApply` | 0 | 1s | `BUILD SUCCESSFUL`；57 tasks，2 executed / 55 up-to-date |
| `./scripts/dev gradle :context:api:test :context:runtime:test --rerun-tasks --console=plain` | 0 | 45.49s | `BUILD SUCCESSFUL in 45s`；27 tasks executed |
| `./scripts/dev gradle :context:api:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | 6.94s | `BUILD SUCCESSFUL in 6s`；9 tasks executed |
| `./scripts/check` | 0 | 149.72s | `BUILD SUCCESSFUL in 2m 29s`；131/131 tasks executed |
| `git diff --check` | 0 | - | 空输出 |

## 7. 14 模块源码 `@Test` 与 XML

源码范围为各模块 `src/test/**/*.kt` 行首（允许缩进）`@Test`；XML 为最终 `build/test-results/test/TEST-*.xml`。

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 35 | 35 | 0 | 0 | 0 |
| context-core | 110 | 110 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 554 | 554 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 22 | 22 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **1799** | **1799** | **0** | **0** | **0** |

B1 前基线 **1756**，新增 **43**，最终 **1799=1799**。

## 8. Schema、CT17/CT18 与 81 基线

### 8.1 Context Schema

- `ContextSchema.sq` SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 未新增表、索引、`user_version`、SQL 或 MapStatus 持久化。

### 8.2 CT17 / CT18 SHA

- 按 `docs/context/tasks/cm4-ct18/B1_Worker交付.md` 的 CT17 23 文件 SHA 表逐项复算：**23/23 一致**。
- 按同文档 CT18 17 个代码/测试文件 SHA 表逐项复算：**17/17 一致**；CT18 Generator/Publisher/Renderer/Parser/资源均未改写。
- `AlcyoneContext.kt` SHA：`4437ca4800ab06a131f083bb88135ed5360405da55134e00b6c1e6823619364c`，与 CT17 终态一致；未增加 `bootstrap()`。
- `AlcyoneContextImpl.kt` SHA：`e95e6ae4cae23828a433fe712fc4a2d1abdf01cf20a8cf609ee4ff33a2c4352d`，与 CT17 终态一致。
- Factory/Organization Pipeline 不在 B1 写入集，未新增 Map 构造、启动、关闭或 wakeup 接线。

### 8.3 81 VFS 基线

按 `.local/validation/cm1/architect/vfs-baseline-sha256.json` 复算：

- **81 项中 75 项一致、6 项 CT17 B1 已批准差异、缺失 0、未批准差异 0**。
- 6 个差异仍仅限 CT17 已批准文件：`Vfs.kt`、`Storage.kt`、`DefaultVfs.kt`、`LocalFsStorage.kt`、`WebDavStorage.kt`、`AlcyoneVfs.kt`。
- CT19 B1 未修改 VFS/common/Schema/SQL/依赖/构建；构建文件与版本目录状态检查为空。
- Core 主源码禁止导入检查：无 Ktor、无 kotlinx.serialization。

## 9. 未运行项与残余风险

- 真实模型：**not-run**；Fake/注入服务只证明协调、水位、并发、关闭与协议，不证明语义质量。
- B2 生产接线未运行：`AlcyoneContext.bootstrap()`、Factory/Impl 构造、ContextStatus `memoryMap` Worker、Organization wakeup、Runtime E2E 均未实现。
- CT20 Search/Get、HTTP、真实 Resource 全景集成未启动。
- Cron 测试使用可注入 tick 与固定 Clock，覆盖严格未来/不补跑/退避/关闭；未做多日 wall-clock soak。
- safeReplace 报 `STATE_ERROR + PARTIAL` 时物理文件可能是完整新版，但 B1 不读取 T2、不伪造 CURRENT；缓存按失败保留旧 View/Status，交由下一轮读取与水位判断。
- 发布成功后按 D5 保守 STALE；必须再有一次可靠水位比较才进入 CURRENT，这是设计行为，不是即时强一致承诺。
- B1 的 Worker 状态为 internal `MemoryMapWorkerStatus`；public `ContextStatus.workers.memoryMap` 映射归 B2。

## 10. 最终状态

**提交 Lead 初验，CT19 未 DONE，B2/CT20 未启动。**

真实模型 not-run；无 `git add/commit/push/stash`、分支、回滚、发布或其他 Git 写操作。
