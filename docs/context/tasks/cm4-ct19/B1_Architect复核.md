# CT19 B1 · Architect 复核

**日期**：2026-11-08
**状态**：**CT19 B1 APPROVED / DONE**
**复核对象**：[B1 Worker 交付](B1_Worker交付.md)、[B1 Lead 初验](B1_Lead初验.md)、Worker `059d6c59-ce9e-4e68-9055-b45b238506a1`
**依据**：[Architect 裁定](Architect_裁定.md) D1～D12、[B1 委派单](B1_委派单.md)

## 0. 本次修改

Architect 独立复核 CT19 B1：20/20 文件 SHA 一致、实现与 D1～D12 冻结边界逐项吻合、定向与全量实跑通过、CT17 23/CT18 17 SHA 全部未变、Schema/81 基线/依赖边界通过。结论 **B1 APPROVED / DONE**，放行 B2。

## 1. 独立证据

| 项 | 结果 |
| --- | --- |
| 20 文件 SHA 复算 | API 3 + Runtime 生产 8 + 测试 9，与 Worker 交付 §2 逐项一致（20/20 OK） |
| 定向测试 | `:context:runtime:test :context:api:test`（MemoryMap/BootstrapContext/ContextConfigLoader --rerun-tasks）exit 0；11 个相关类 XML 全绿（含既有 CT18 Parser/Generator/Publisher 8+14+6） |
| 既有 CT09 时序敏感类 | `ExtractionPipelineLifecycleTest` 单独复跑 15/15 全绿（Lead 报告的一次性超时不复现） |
| 全量 `./scripts/check` | exit 0（2m17s，131/131 tasks executed） |
| 14 模块计数（fresh XML） | source `@Test` = XML = **1799=1799**；fail/error/skip 全 0；B1 前 1756 + 43 = 1799 |
| Schema | `ContextSchema.sq` SHA `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3` 未变 |
| CT17 23 文件 | 23/23 SHA 一致（含 `AlcyoneContext.kt` 4437ca…、`AlcyoneContextImpl.kt` e95e6a…） |
| CT18 17 文件 | 生产 10 + 测试 7 逐项复算一致（10/10 + 7/7） |
| VFS 边界 | `git status -- vfs` 仅 CT17 已批准 12 修改 + 1 新增（与 CT17 交付白名单一致）；`common` diff 为空 |
| 依赖边界 | Runtime 新增 import 仅既有 cron-utils（organization 同源）、SLF4J、coroutines、项目内模块；API 三模型仅 `java.time.Instant`；无新依赖/构建变更 |
| 生产接线 | Factory/Impl/Organization Pipeline 无 `MemoryMapPipeline`/`memoryMap`/`bootstrap` 引用（grep 0 命中）；`AlcyoneContext` 无 `bootstrap()`；public `ContextStatus` 无 memoryMap 字段 |

## 2. D1～D12 逐项核对

| 裁定 | 复核结果 |
| --- | --- |
| D1 配置 | `ContextConfig.memoryMap: MemoryMapConfig?` 缺省 null；空映射默认 periodic `*/10 * * * *`；Loader 三层 `rejectUnknown`（refresh/periodic 未知键、timezone 拒绝）；类型错误与非法 cron 均 `INVALID_CONFIG`/`UNKNOWN_FIELD`，消息不回显表达式、不挂 cause。通过 |
| D2 绑定门 | `validateMemoryMapBinding` 纯校验（MEMORY_MAP Task/Profile + JSON_SCHEMA/JSON_OBJECT，缺 LLM/tasks/profile 均拒绝）；B1 未接 Factory，符合裁定。通过 |
| D3 三模型 | `MapStatus` 恰四态；`MemoryMapView(content, generatedAt, sourceUpdatedAt: Instant?)` 空白拒绝；`BootstrapContext` + `DEFAULT_CAPABILITIES`；`AlcyoneContext` 未改。通过 |
| D4 Cron | UNIX 五字段 `CronType.UNIX`、`ZONE = Asia/Shanghai` 固定、`nextAfter` 严格未来、tick 循环 `ensureActive`、失败 WARN + 固定 1000ms 可取消退避、close 真实 join。通过 |
| D5 发布后 STALE | `processOne` 发布成功只 `tracker.update(publishedView, MapStatus.STALE)`；不查 T2；下一信号水位相等才 CURRENT（测试断言两次信号 1 次生成）。通过 |
| D6 破损 | Reader `NOT_FOUND → MISSING`（清 View）；破损/VFS 失败 → `UNKNOWN`，`MemoryMapRenderer.parse` 严格解析，破损字节不进入 View。通过 |
| D7/D8 生命周期与未配置 | `State.CLOSING/CLOSED` 抛 `ContextLifecycleException(CLOSED)`；未配置归 B2（组件状态支持）；close-after-start 拒绝、close-before-start 安全（PipelineTest 覆盖）。通过（生命周期接线归 B2 为既定批次边界） |
| D9 启动检查 | `start()` 启动资源 + `requestRefresh()`，Worker 在 `activation.await()` 前不读取/生成（150ms 0 生成断言 + beginClosing 排队信号不生成）。通过 |
| D10 关闭次序 | Pipeline `closeAsync`：beginClosing（同步封准入）→ `periodicCron.closeAsync()` → `coordinator.closeAsync()`（取消并真实 join Worker）→ 共享 `closeCompletion`；并发等待者共享同一 primary + suppressed。通过 |
| D11 Worker 状态 | internal `MemoryMapWorkerStatus`（IDLE/RUNNING/STOPPED/FAILED），单条失败 `FAILED` 不外抛不阻断 Context；public 映射留 B2。通过 |
| D12 批次 | 21 文件白名单（17 新增 + 3 增量 + 1 报告）逐项核实；无白名单外变更。通过 |

## 3. 并发与失败矩阵抽验

- **CONFLATED 单 Worker**：执行中 20 次补信号 → `workerCreations=1`、仅 1 轮 follow-up（`calls=2`）——实现为 `Channel<Unit>(CONFLATED)` + 唯一 LAZY worker，与 D5/裁定 §3 一致。
- **CURRENT 跳过**：可靠 Map 且 `sourceUpdatedAt == DB MAX` → 0 生成；DB 水位读取失败 → `markUnknown()` 保留旧有效 View、0 生成。
- **生成/发布失败**：`tracker.restore(previous)` 恢复请求前快照；`STATE_ERROR + PARTIAL` 不伪造 CURRENT；日志 `memoryMap.refresh.failed` 无 throwable、无正文/标记泄漏（Slf4jCapture 断言）。
- **CE 传播**：`processOnceForTesting` 断言 `assertSame(cancellation)`；Reader/Coordinator/Cron 均显式 rethrow CE。
- **自身关闭保护**：`closeAsync` 检测调用者即 Worker 本身时抛 CLOSED（防自 join 死锁）。
- **Cron tick 不重叠**：串行 `runTicks` 循环天然无重入，测试受控阻塞断言最大并发 1。

## 4. 边界与残留观察

- **观察 N1（不阻断，沿用既有记录）**：CT09 `ExtractionPipelineLifecycleTest > disabling idle...` 存在偶发时序敏感性（Lead 报告一次 10s 超时，完整类与完整套件均绿，Architect 单独复跑 15/15）。该类不在 CT19 修改面、相关 SHA 未变，继续按 AGENTS 既有记录跟踪，不在 B1 处置。
- **观察 N2（B1 既定边界）**：`requestRefresh()` 在 state 非 RUNNING 时 `trySend` 失败返回 false；CONFLATED 满语义正确。
- B2 待办（既定）：`bootstrap()` 公共方法、Factory/Impl 构造与接线、ContextStatus `memoryMap` 映射、Organization wakeup、Runtime E2E。
- 真实模型 not-run；无 Git 写操作。

## 5. 结论

**CT19 B1 APPROVED / DONE。**

- 21 文件白名单齐备，20 代码/测试 SHA 与交付一致。
- 14 模块 1799=1799，`./scripts/check` exit 0，fail/error/skip 0。
- Schema/CT17/CT18/81 VFS 基线/依赖/生产未接线边界全部通过。
- **放行 CT19 B2**（按委派单 §B2：bootstrap API、Factory/Impl、ContextStatus 映射、Organization wakeup、E2E）；CT20/CT21 保持 LOCKED。
