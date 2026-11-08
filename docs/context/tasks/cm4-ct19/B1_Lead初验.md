# CT19 B1 · Lead 初验

## 本次修改（2026-11-08，B1 Lead 初验）

完成 CT19 B1 独立初验：核对 21 文件白名单与 SHA，复跑 API/Runtime 定向、Spotless、并发/失败矩阵和全量 `./scripts/check`，核对 14 模块 source/XML、Schema、CT17/CT18 SHA、81 VFS 基线及生产边界。结论为 **B1 初验通过、提交 Architect 复核；CT19 未 DONE，B2/CT20 未启动**。另记录一次既有 CT09 `ExtractionPipelineLifecycleTest` 单例时序敏感性，未发现与 CT19 调用面或资源生命周期相关。

**日期**：2026-11-08
**状态**：**B1 IN_REVIEW；提交 Architect 独立复核**
**Worker**：`059d6c59-ce9e-4e68-9055-b45b238506a1`（`codex-magpie/group/worker`、`auto-review`、high）
**依据**：[B1 委派单](B1_委派单.md)、[B1 Worker 交付](B1_Worker交付.md)、[Architect 裁定](Architect_裁定.md)、[CT18 收口报告](../cm4-ct18/CT18_收口报告.md)
**基线**：CT17/CT18 APPROVED / DONE；14 模块源码 `@Test` 实测 **1756**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 1. 初验结论

**CT19 B1 Lead 初验通过，提交 Architect 独立复核。**

- Worker 严格交付委派单 §3 的 21 个白名单文件：20 个代码/测试文件 + 1 份交付报告；白名单外 B1 代码/测试/构建变更 0。
- 新增 43 个测试，14 模块源码 `@Test` 与 XML 均为 **1799=1799**，fail/error/skip = **0/0/0**。
- API/Runtime 定向、两模块 Spotless、并发/失败矩阵定向及全量 `./scripts/check` 均由 Lead 独立实跑通过。
- 公共模型、Config/Loader、Tracker/Reader、Coordinator、Cron、Pipeline 的实现与 B1 冻结边界一致。
- 未修改 `AlcyoneContext` 接口、Factory/Impl、Organization Pipeline、VFS/common/Schema/SQL/依赖/构建或 CT18 文件；真实模型 not-run。

## 2. 白名单与 SHA 核对

### 2.1 文件集

- 委派单 §3 的 21 个文件全部存在；20 个代码/测试文件 SHA 与 [B1 Worker 交付](B1_Worker交付.md) §2 逐项一致。
- 交付报告为第 21 个白名单文件，不内嵌自引用 SHA。
- 新增/修改分类：17 个新增代码/测试、3 个既有文件增量修改、1 份交付报告。
- 既有 `ContextConfig.kt`、`ContextConfigLoader.kt`、`ContextConfigLoaderTest.kt` 的 B1 增量仅涉及 `memoryMap` 配置；未出现白名单外 B1 变更。

### 2.2 关键文件 SHA

| 文件 | Lead 复算 SHA-256 |
| --- | --- |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/MapStatus.kt` | `b134c5869fb24d61f0bc7a03d50f80fe0f03a913daab79132c1f6fa298502c6e` |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/MemoryMapView.kt` | `553f7548fc255c13d0afbd1378e24f24e913b9738db3139bfb822acd92c7b035` |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/BootstrapContext.kt` | `c40883d75311a04e73faa2d50d7a5d069ad803fdd5d9cabb5765f51dc9fad901` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapRefreshCoordinator.kt` | `503850cd88896ecbd68e4e0c35f209c40ba1902763a16afa1db9b6c8fa37ca8e` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapPeriodicCron.kt` | `100ad323643c195c158e8352084fb37a52b034b56537a87b9fdb7bc592986309` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapPipeline.kt` | `714b5a68cccb7b778ef40a040719a42789c43acab69e89ffa321beafe053b28a` |

## 3. 独立实跑证据

| 命令 | exit | 耗时 | 关键结果 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle :context:api:test :context:runtime:test --rerun-tasks --console=plain` | 0 | 46s | 27 tasks；API/Runtime 全绿 |
| `./scripts/dev gradle :context:api:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | 4s | 9 tasks；格式通过 |
| `./scripts/dev gradle :context:runtime:test --tests '*MemoryMapRefreshCoordinatorTest' --tests '*MemoryMapPeriodicCronTest' --tests '*MemoryMapPipelineTest' --rerun-tasks --console=plain` | 0 | 15s | 24 个并发/失败/关闭用例通过 |
| `./scripts/check` | 0 | 2m14s | 131/131 tasks；全模块构建、Spotless、测试通过 |
| 第二次 `:context:runtime:test --rerun-tasks` | 0 | 43s | 恢复完整 Runtime XML；554/554 通过 |
| `git diff --check` | 0 | - | 空输出 |

### 3.1 14 模块 source/XML

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

B1 前 **1756**，新增 **43**，最终 **1799=1799**。

## 4. 并发与失败矩阵抽验

Lead 独立审查实现与正式断言，并实跑三组定向用例：

| 场景 | 抽验结果 |
| --- | --- |
| CONFLATED 单 Worker | 20 次执行中补信号只形成一个待处理信号；最多再执行 1 轮；`workerCreations=1` |
| CURRENT / MISSING / STALE / UNKNOWN | CURRENT 0 生成；MISSING/STALE/UNKNOWN 请求生成；发布后保守 STALE，下一轮水位相等才 CURRENT |
| 破损 Map | 破损正文不进入 View；旧有效 View 可保留并标 UNKNOWN |
| 生成/发布失败 | 恢复请求前 View/Status；safeReplace `STATE_ERROR + PARTIAL` 不伪 CURRENT；日志无 Throwable/正文标记 |
| DB 水位失败 | UNKNOWN 保留旧有效 View，0 生成 |
| activation 前 | 启动检查只排队，不读取/生成；activate 后才执行 |
| beginClosing | 同步拒绝新请求，不创建新 Worker；排队信号不生成 |
| Cron | 严格未来、不启动即扫、不补跑；tick 扫描不重叠；失败固定可取消退避；close 真实 join |
| Pipeline close | 顺序 Cron → Coordinator；在途生成取消并真实 join；并发/重复 close 共享主失败与 suppressed |
| Bootstrap 快照 | 生成阻塞期间返回最近缓存快照，不等待 LLM/DB |
| CancellationException | Reader/Coordinator 对 CE 原样传播，不包装 |

## 5. 边界核对

### 5.1 Schema / CT17 / CT18 / VFS

- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- [CT18 B1 Worker 交付](../cm4-ct18/B1_Worker交付.md) 的 40 个 SHA 条目（CT17 23 + CT18 17）逐项复算：**40/40 一致**。
- 81 项 VFS 基线：**75 一致 + 6 个 CT17 已批准差异，缺失 0，未批准差异 0**。
- 构建文件、Gradle 版本目录及 common/VFS/Schema/SQL 均无 B1 新变更。

### 5.2 生产接线与 API

- `AlcyoneContext.kt` SHA 保持 `4437ca4800ab06a131f083bb88135ed5360405da55134e00b6c1e6823619364c`；没有新增 `bootstrap()`。
- `AlcyoneContextImpl.kt` SHA 保持 `e95e6ae4cae23828a433fe712fc4a2d1abdf01cf20a8cf609ee4ff33a2c4352d`；未接 Map Pipeline。
- Factory/Impl/Organization Pipeline 中无 `MemoryMapPipeline`、Coordinator 或 `memoryMap` 接线引用。
- B1 新 Runtime 组件均为 internal；未新增 public `ContextStatus` 字段或 Worker 映射。
- Map 生产路径仍只复用 CT18 `MemoryMapService`/`MemoryMapPublisher`，未引入普通 write/move/delete、物理文件写或 T2 二次检查。
- Core 主源码无 Ktor/kotlinx.serialization；Runtime 新增源码无第三方依赖或构建变更。

## 6. 非阻断观察：既有 CT09 时序敏感用例

独立恢复完整 Runtime XML 期间，一次完整 Runtime 运行出现既有
`ExtractionPipelineLifecycleTest > disabling idle keeps the count and scheduler working without a cron()`
10 秒超时；随后两次单例复跑仍超时，而完整 `ExtractionPipelineLifecycleTest`（15/15）和第二次完整 Runtime（554/554）均通过。

- 该用例属于 CT09 既有 extraction/notification 路径，不在 CT19 B1 修改面；Factory/Impl/Extraction Pipeline 的 SHA 未变。
- CT19 定向 24 例、API/Runtime 46s 定向及全量 `./scripts/check` 131/131 均通过。
- AGENTS 已记录同类 `disabling idle...` 10s 时序敏感性；本轮未发现 CT19 Map 组件创建、调用或资源泄漏与该用例的关联证据。
- 结论：不阻断 CT19 B1，但保留为既有测试时序敏感性观察，交由 Architect 判断是否另立测试稳定性修复。

## 7. 未运行项与残余风险

- 真实模型：**not-run**；Fake/受控服务只证明协调、水位、并发、关闭与协议，不证明语义质量。
- B2 尚未实现/验证：`AlcyoneContext.bootstrap()`、Factory/Impl、ContextStatus `memoryMap`、Organization wakeup、Runtime E2E。
- CT20 Search/Get、HTTP、Dreaming、Backup、部署及真实模型评测均未启动。
- Cron 使用注入 tick/固定 Clock；未做多日 wall-clock soak。
- safeReplace 返回 `STATE_ERROR + PARTIAL` 时物理内容可能是完整新版，但 B1 不读 T2、不伪 CURRENT；下一轮由读取与水位收敛。

## 8. 当前状态

- **CT19 B1：IN_REVIEW，Lead 初验通过，提交 Architect 独立复核。**
- **CT19：IN_PROGRESS；B2 LOCKED。**
- **CT20/CT21：未启动。**
- 真实模型 not-run；无 Git add/commit/push/stash、分支、回滚或发布操作。
