# CT19 Architect 裁定

**日期**：2026-11-08
**状态**：**APPROVED；CT19 范围与 B1 边界冻结；B1 委派单已准备，待派发 Worker**
**依据**：[CT19 任务书](CT19_任务书.md)、[CT19 方案核对](CT19_方案核对.md)、[CM4 范围与依赖方案](../../milestones/cm4/CM4_范围与依赖方案.md)、技术实现设计 §7.4～§7.6/§7.10、[CT18 收口报告](../cm4-ct18/CT18_收口报告.md)

## 0. 本次修改

- Architect 独立基线复核：`./scripts/dev gradle build -x test` exit 0；三模块定向 test 全绿；14 模块（含 `vfs:git-backup`）精确 `@Test` = XML = **1756=1756**（fail/error/skip 0；vfs-core 316=316、context 13 模块 1683 与 CT17/CT18 记录口径一致）；Context Schema SHA `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3` 未变。
- 裁定 CT19 D1～D12（全部按方案推荐通过）；B1 委派单 READY，随后派发 Worker。

## 1. 裁定效力

本文件为 CT19 的 Architect 最终裁定，覆盖方案中的待裁定项。CT19 按以下边界推进：

- 先完成 **B1 组件/配置/状态**；B1 未 DONE 不派发 B2。
- B2 才接 `AlcyoneContext.bootstrap()` 公共方法、Factory/Impl、Organization wakeup、E2E。
- CT19 不实现 CT18 已冻结的生成/校验/Renderer/safeReplace 规则，不实现 CT20 Search/Get。
- 不新增表/索引/`user_version`、VFS/common/Schema/依赖/构建修改，不运行真实模型，无 Git 写操作。

## 2. 冻结裁定 D1～D12

### D1：`context.memoryMap` 语义

缺省 `null` 禁用（不建 Map pipeline）；出现即显式启用；空映射使用默认 periodic 配置。Loader 从"已知未消费子树"转为严格解析，`unknown`/类型错误必须拒绝。

### D2：LLM 绑定门

显式启用 Map 时必须存在 `MEMORY_MAP` Task/Profile 且为 `JSON_SCHEMA`/`JSON_OBJECT`，否则 `INVALID_CONFIG`。**B1 只做配置解析与纯校验 helper（如提供）；跨配置绑定校验归 B2 Factory。**

### D3：Bootstrap API 字段

冻结三模型（`MapStatus` 四态枚举、`MemoryMapView(content, generatedAt, sourceUpdatedAt)`、`BootstrapContext(memoryMap, mapStatus, capabilities)`）。`bootstrap()` 默认实现返回 `memoryMap=null, mapStatus=UNKNOWN` + 固定 capabilities（`MemoryMapView.sourceUpdatedAt` 可空，`Instant` 类型）。B1 不修改 `AlcyoneContext` 接口。

### D4：Cron 时区

固定 `Asia/Shanghai`，不提供 timezone 键（出现即未知键拒绝）；默认 cron `*/10 * * * *`；UNIX 五字段；严格未来、不补跑。

### D5：发布成功后的状态

不查 T2、无发布锁。发布成功后保守判 **STALE**（生成期间可能有新写入），下一轮水位检查追平后再 CURRENT。禁止伪造 CURRENT。

### D6：破损 Map 的 View

不返回破损内容；Tracker 可保留上一份有效 View 并标 UNKNOWN。解析失败与 VFS 读取失败均为 UNKNOWN；NOT_FOUND 为 MISSING。

### D7：未启动/关闭后的 Bootstrap

未启动 → `ContextLifecycleException(NOT_STARTED)`；关闭后 → `CLOSED`。不自造 MapStatus、不做 I/O。生命周期语义与其余 `AlcyoneContext` 方法一致（`ingestConversation` 同口径）。

### D8：Map 未配置的 Bootstrap

`memoryMap=null, mapStatus=MISSING` + 固定 capabilities；不建 pipeline、不触发任何刷新。

### D9：启动检查时机

`start()` 内异步发起一次启动检查（`requestRefresh()`），不阻塞 start 返回；RUNNING/activate 前不得执行生成/发布。

### D10：Cron/Worker 关闭次序

同步封刷新准入（beginClosing 非挂起）→ 停 Cron → 取消并真实 join Worker → 发布共享关闭结果。等待者取消只取消自己（同 CT09/CT16 口径）。

### D11：Worker 状态

`ContextStatus.workers` 增加 `memoryMap` 条目；单条生成失败记录状态但**不把整个 Context 标 FAILED**。B1 不接 getStatus（归 B2），但组件状态设计须支持该报告。

### D12：批次

B1 组件/配置/状态（不接生产）；B2 API/Factory/Impl/wakeup/E2E；**无需 B3**。若关闭竞态或 E2E 暴露结构性问题，另立 B2-R1/B3。

## 3. 派生要求

- B1 白名单 21 文件（委派单 §3）冻结；白名单外变更即停止报告。
- Coordinator 每轮水位检查：`database.read { maxUpdatedAt() }` 对比已发布 Map `sourceUpdatedAt`（CT18 Front Matter）；CURRENT → skip 生成。
- Cron 失败退避参照 CT16 `OrganizationPeriodicCron` 模式：WARN + 固定可取消退避，无紧凑循环。
- Coordinator 对 `MemoryMapService.generateAndPublish()` 的调用失败（非 CE）保留旧 View/Status，错误脱敏；CE 原样传播。
- 测试必须真实执行（XML 计数核对），不得虚报；真实 SQLite/LocalFs 组件测试复用 CT18 夹具模式。
- 验收命令与边界证据按委派单 §6；CT17 23 + CT18 17 文件 SHA 不变；81 VFS 基线保持 75+6；Schema SHA 不变。

## 4. 当前状态

- Architect 裁定：**APPROVED**。
- B1 委派单：**READY，待派发**。
- B2：**LOCKED，待 B1 DONE**。
- CT19：**IN_PROGRESS / 方案与裁定冻结**。
- 真实模型：**not-run**；无 Git 写操作。
