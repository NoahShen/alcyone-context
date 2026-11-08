# CT19 B2 · Architect 复核

**日期**：2026-11-08
**状态**：**CT19 B2 APPROVED / DONE；CT19 整体 APPROVED / DONE**
**复核对象**：[B2 Worker 交付](B2_Worker交付.md)、[B2 Lead 初验](B2_Lead初验.md)、Worker `059d6c59-ce9e-4e68-9055-b45b238506a1`
**依据**：[Architect 裁定](Architect_裁定.md) D1～D12、[B2 委派单](B2_委派单.md)、[B1 Architect 复核](B1_Architect复核.md)

## 0. 本次修改

Architect 独立复核 CT19 B2：10/10 文件 SHA 一致、bootstrap/Factory/Impl/wakeup/关闭顺序实现逐项与 D1～D12 冻结裁定吻合、定向与全量实跑通过、B1 全部 20 文件与 CT17 21/CT18 17 SHA 未变。结论 **B2 APPROVED / DONE；CT19 整体 APPROVED / DONE**。N1（internal accessor 命名债务）裁定为记录性观察、不要求 R1。

## 1. 独立证据

| 项 | 结果 |
| --- | --- |
| 10 文件 SHA 复算 | API 2 + Runtime 生产 2 + Runtime 测试 5 + Integration E2E 1，与 Worker 交付 §2 逐项一致（10/10 OK） |
| 定向测试 | 三模块 `--rerun-tasks` exit 0（47s，32 tasks）；B2 新测试 6 类 = 1+4+5+5+4+1 = 20，全绿 |
| 全量 `./scripts/check` | exit 0（2m25s，131/131 tasks executed） |
| 14 模块计数（fresh XML） | source `@Test` = XML = **1819=1819**；fail 全 0；B2 前 1799 + 20 = 1819 |
| Schema | `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3` 未变 |
| B1 边界 | B1 组件 7 个关键文件 spot-check（Pipeline/Coordinator/Config/Loader/三模型/PipelineTest）SHA 全部与 B1 终态一致；Worker 交付声明 20/20 一致，与 Lead 独立核对吻合 |
| CT17/CT18 | CT17 21 个非白名单文件未变（含 ContextExceptions/ResourceMetadata/ContextResourceAccess/ResourceE2E spot-check 4/4）；`AlcyoneContext.kt`/`AlcyoneContextImpl.kt` 两个变化严格为 B2 白名单文件；CT18 17 文件未变（Lead 核对 17/17） |
| 其他边界 | `ContextStatus.kt`/`WorkerStatus.kt`/`ContextConfig`/`ContextConfigLoader` 未修改；common diff 空；VFS 仅 CT17 已批准差异 |

## 2. 实现逐项核对

| 裁定 | 复核结果 |
| --- | --- |
| D3/D8 bootstrap | `AlcyoneContext.bootstrap()` 接口默认方法返回 null+UNKNOWN+DEFAULT_CAPABILITIES（第三方无破坏）；Impl 在状态锁内仅读 `memoryMapPipeline?.snapshot()`，静态路径无 DB/VFS/LLM/refresh 调用；CREATED/STARTING→NOT_STARTED、STOPPING/CLOSED→CLOSED、未配置→null+MISSING。通过 |
| D2 绑定门 | `validateMemoryMapBinding(ownedMemoryMapConfig, ownedLlmConfig)` 在 `validateOrganizationBinding` 之后、任何资源打开前调用（Factory 代码顺序核实）；公共 `create()` 签名不变且传 null seam。通过 |
| D9 启动 | Pipeline 构造在 start 内（executor→VFS→LLM→Organization→MemoryMap→Extraction 取得顺序），RUNNING 发布前 `start()` 只排队启动检查；发布后 Organization→Extraction→MemoryMap 依次 `activate()`。通过 |
| D10 关闭 | RUNNING→STOPPING 同锁内对 Extraction/Organization/MemoryMap 三个 `beginClosing()`；`releaseResources` 顺序实测为 Extraction→Organization→MemoryMap→LLM→VFS（含 releaseProbe）→executor；失败 chain+suppressed。通过 |
| D11 Worker 状态 | `memoryMapWorkerStatus()`：`memoryMapConfig == null` 返回 emptyMap（未配置不出现条目）；CREATED/STARTING=IDLE；RUNNING 映射 internal 状态；STOPPING/CLOSED=STOPPED；通过既有 `workers: Map<String, WorkerStatus>`，公共类型零修改。通过 |
| D4 wakeup | 生产默认 `OrganizationMapWakeup { currentMemoryMapPipeline()?.requestRefresh() }`，仅 CONFLATED trySend；未配置/closing/no-op 安全；`DefaultOrganizationWorker` 仅 Committed 后调用。通过 |
| D7 生命周期 | NOT_STARTED/CLOSED 生命周期错误核实（Impl bootstrap when 分支）。通过 |

## 3. E2E 复核

`MemoryMapContextEndToEndTest`（真实 `AlcyoneContextFactory.create` + `initializeDatabase` + 真实 SQLite/LocalFs/Ktor client/JDK HTTP stub）：
- Front Matter/正文/`sourceUpdatedAt` 断言；`STALE|CURRENT`；capabilities 固定。
- 重复 bootstrap 20 次断言 LLM 请求数保持 1（bootstrap 不触发刷新）。
- `workers.memoryMap` 可观测；close 后 bootstrap=CLOSED 且无后台生成残留。

## 4. N1 裁定（internal accessor 命名债务）

**记录性观察，不要求 R1。** 理由：

- B2 生产路径通过 `MemoryMapPipeline.coordinatorForTesting().requestRefresh()` / `statusForTesting()` 复用 B1 internal Coordinator 的同一 CONFLATED 准入与状态读取。行为语义与 B1 完全一致，且 `Impl` 侧以私有扩展函数 `MemoryMapPipeline.requestRefresh()` 封装（KDoc 已注明复用原因），不扩散调用面。
- 要求 R1（B1 增加正式 internal accessor）只改善命名，不改变任何行为与测试结果，收益不足以触发 B1 SHA 变更与回归成本。
- 处置：登记为内部 API 债务，若 CT20/CM4 后续需重构 Map 入口时一并清理；届时必须保持 CONFLATED 合并语义与既有测试断言。

## 5. 结论

**CT19 B2 APPROVED / DONE；CT19 整体 APPROVED / DONE。**

- 11 文件白名单齐备；10 代码/测试 SHA 一致。
- 14 模块 **1819=1819**；`./scripts/check` exit 0；B1/CT17/CT18/Schema/VFS 边界全部通过。
- Bootstrap 只读缓存、Factory 绑定门、启动/激活/关闭顺序、Organization 真实 wakeup、workers.memoryMap 全部符合冻结裁定。
- N1 为记录性观察，后续重构时清理。
- **放行 CT20（Search/Get 只读方案核对）**；CT21 LOCKED；真实模型 not-run；无 Git 写操作。
