# CT19 B2 · Lead 初验

## 本次修改（2026-11-08，B2 Lead 初验）

完成 CT19 B2 独立初验：核对 11 文件白名单与 SHA，审查 bootstrap、Factory/Impl、Worker 状态、Organization wakeup 与关闭顺序，复跑三模块定向、Spotless、B2 定向 Runtime、真实 SQLite/LocalFs/HTTP E2E 和全量 `./scripts/check`。结论为 **B2 初验通过、提交 Architect 复核；CT19 未 DONE，CT20/CT21 未启动**。保留一项非阻断观察：B2 生产接线复用了 B1 internal Coordinator test accessor，未改变 B1 语义。

**日期**：2026-11-08
**状态**：**B2 IN_REVIEW；提交 Architect 独立复核**
**Worker**：`059d6c59-ce9e-4e68-9055-b45b238506a1`（`codex-magpie/group/worker`、`auto-review`、high）
**依据**：[B2 委派单](B2_委派单.md)、[B2 Worker 交付](B2_Worker交付.md)、[Architect 裁定](Architect_裁定.md)、[B1 Architect 复核](B1_Architect复核.md)、[CT18 收口报告](../cm4-ct18/CT18_收口报告.md)
**基线**：CT19 B1 APPROVED / DONE；14 模块 **1799=1799**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 1. 初验结论

**CT19 B2 Lead 初验通过，提交 Architect 独立复核。**

- Worker 严格交付 B2 委派单 §3 的 11 个白名单文件：10 个代码/测试文件 + 1 份交付报告；白名单外 B2 变更 0。
- 新增 20 个测试，14 模块源码 `@Test` 与 XML 均为 **1819=1819**，fail/error/skip = **0/0/0**。
- `bootstrap()` 默认契约、真实只读缓存实现、生命周期错误与未配置 MISSING 行为符合 D3/D7/D8。
- Factory 在资源打开前完成 Memory Map LLM 绑定校验；公共 `create()` 签名不变、零副作用。
- Runtime 构造/启动/激活/关闭唯一 MemoryMapPipeline，Organization 提交后的生产 wakeup 已接真实刷新入口。
- `ContextStatus.workers.memoryMap` 映射通过既有 Map 类型完成，未修改 public `ContextStatus`/`WorkerStatus`。
- 真实 SQLite + LocalFs + Ktor client + JDK HTTP stub E2E 通过；真实模型 not-run。

## 2. 白名单与 SHA

### 2.1 文件集

- 委派单 §3 的 11 个文件全部存在；10 个代码/测试文件 SHA 与 [B2 Worker 交付](B2_Worker交付.md) §2 逐项一致。
- 交付报告为第 11 个白名单文件，不内嵌自引用 SHA。
- 实际组成为：API 生产 1 + API 测试 1 + Runtime 生产 2 + Runtime 测试/支持 5 + Integration E2E 1 + 交付报告 1。

### 2.2 关键文件 SHA

| 文件 | Lead 复算 SHA-256 |
| --- | --- |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/AlcyoneContext.kt` | `9f2cde1bd792836c05b561d4235ac5f271a07c4481de214c78538c9b9345d37c` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextFactory.kt` | `e23aa8bfe498b08ac6d02222b7f18774d756bbbded9ca9ef1b980e2944e4f3e0` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextImpl.kt` | `3d99e6ca4024066996b25d4cac6027538393e2debd8c0c20d104d156e84b809f` |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/MemoryMapContextEndToEndTest.kt` | `0f01eca178a4f2f697a6320e913021132f4ef0f140fc66198ff462dd4262e1ff` |

## 3. 独立实跑证据

| 命令 | exit | 耗时 | 关键结果 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle :context:api:test :context:runtime:test :integration-tests:context:test --rerun-tasks --console=plain` | 0 | 50s | 32 tasks；三模块全绿 |
| `./scripts/dev gradle :context:api:spotlessCheck :context:runtime:spotlessCheck :integration-tests:context:spotlessCheck --rerun-tasks --console=plain` | 0 | 6s | 13 tasks；格式通过 |
| `./scripts/check` | 0 | 2m18s | 131/131 tasks；全模块构建、Spotless、测试通过 |
| B2 Runtime 四测试类定向 | 0 | 13s | Factory/Runtime/Lifecycle/Organization wakeup 18 例通过 |
| `MemoryMapContextEndToEndTest` 定向 | 0 | 7s | 真实 SQLite + LocalFs + Ktor client + HTTP stub 通过 |
| 完整 Runtime + integration-context 恢复运行 | 0 | 44s | 572 + 23 例通过；恢复完整 XML |
| `git diff --check` | 0 | - | 空输出 |

### 3.1 14 模块 source/XML

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

B2 前 **1799**，新增 **20**，最终 **1819=1819**。

## 4. 行为核对

### 4.1 Bootstrap

- API 默认实现返回 `null + UNKNOWN + DEFAULT_CAPABILITIES`。
- Impl 在状态锁内只读取 `MemoryMapPipeline.snapshot()`；无 SQLite、VFS、LLM 或刷新调用。
- CREATED/STARTING → NOT_STARTED；STOPPING/CLOSED → CLOSED。
- RUNNING 未配置 → `null + MISSING + fixed capabilities`。
- RUNNING 已配置 → 原子 View/Status；异常缺失 Pipeline 时保守返回 `UNKNOWN/null`。
- E2E 重复 bootstrap 20 次，LLM 请求数保持 1；close 后 bootstrap 为 CLOSED。

### 4.2 Factory / Start / Activate

- `validateMemoryMapBinding` 在数据库/VFS/LLM 创建前执行；缺 LLM、缺 Task、PROMPT_ONLY 均在 create 阶段拒绝且零文件副作用。
- 公共 `create()` 签名不变；B2 注入 seam 均为 internal 且公共入口传 null。
- start 构造唯一 Map Pipeline；RUNNING 发布前 0 生成，发布后 Organization → Extraction → MemoryMap 激活。
- 受控阻塞生成时 start 仍在 2 秒超时内返回；启动检查只生成一次。
- Map 构造/start 失败进入既有 NonCancellable 清理；Context CLOSED，VFS/executor 已释放。

### 4.3 Organization Wakeup / Worker 状态

- 公共生产路径使用 `OrganizationMapWakeup { currentMemoryMapPipeline()?.requestRefresh() }`，不再永久 no-op。
- wakeup 只合并 Coordinator 信号，不等待 LLM/发布；Map 未配置或已关闭时 no-op。
- 直接运行 `DefaultOrganizationWorker` 的 Committed 路径可触发第二次 Map 生成请求。
- `workers.memoryMap`：CREATED/STARTING IDLE；RUNNING 映射 IDLE/RUNNING/FAILED；STOPPING/CLOSED STOPPED。
- 单轮 Map 失败只显示 FAILED，Context 保持 RUNNING。

### 4.4 Close

- RUNNING → STOPPING 同锁封闭 Extraction/Organization/MemoryMap 三条准入。
- 独立顺序钩子实测：`Extraction → Organization → MemoryMap → LLM → VFS → executor`。
- 在途 Map 生成取消并真实进入 finally；Context 最终 CLOSED，Pipeline 引用清空。
- 并发/重复 close 共享主失败；Organization/Map close 失败作为 suppressed 保留。

## 5. 边界核对

### 5.1 B1 / CT17 / CT18 / Schema / VFS

- B1 Worker 交付的 20 个代码/测试 SHA：**20/20 一致**。
- CT18 Worker 交付的 17 个代码/测试 SHA：**17/17 一致**。
- CT17 23 文件：**21 个未变 + 2 个 B2 白名单要求变化**：
  - `AlcyoneContext.kt`：新增默认 `bootstrap()`；
  - `AlcyoneContextImpl.kt`：Map/Bootstrap 生产接线。
- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 81 VFS 基线：**75 一致 + 6 个 CT17 已批准差异，缺失 0，未批准差异 0**。

### 5.2 生产边界

- `ContextStatus.kt`、`WorkerStatus.kt` 未修改；仅通过既有 `workers: Map<String, WorkerStatus>` 增加条目。
- `ContextConfig.kt`、`ContextConfigLoader.kt` 保持 B1 SHA，未由 B2 修改。
- 构建文件、Gradle 版本目录、common、Schema/SQL、依赖无 B2 变更。
- API 未新增 Search/Get/HTTP；Factory 公共 `create()` 签名不变。
- Map 生产路径继续只使用 CT18 `MemoryMapService`/`MemoryMapPublisher.safeReplace`，无普通 write/move/delete 或物理写。
- Core 主源码无 Ktor/kotlinx.serialization；真实模型 not-run。

## 6. 非阻断观察

**N1：B1 未暴露 production request/status accessor。** B2 在不修改 B1 组件白名单的前提下，生产接线通过 `MemoryMapPipeline.coordinatorForTesting().requestRefresh()` 与 `statusForTesting()` 复用同一 internal Coordinator 准入/状态。

- 未改变 B1 行为，CONFLATED、单 Worker、beginClosing 与状态语义均由 B1 测试继续覆盖。
- 命名中的 `ForTesting` 进入 B2 生产调用属于后续可清理的内部 API 债务；若 Architect 要求，可另立最小 R1 在 B1 增加正式 internal accessor，但会改变 B1 SHA。
- 当前不构成行为失败，作为非阻断观察提交 Architect 判断。

## 7. 未运行项与残余风险

- 真实模型：**not-run**；Fake HTTP 只证明协议、预算、发布、缓存和生命周期，不证明语义质量。
- E2E 使用 `periodic.enabled=false` 的启动检查路径；Cron wall-clock soak 未运行。
- 发布成功仍按 D5 保守 STALE，下一次可靠水位比较才 CURRENT。
- safeReplace `STATE_ERROR + PARTIAL` 时缓存可能短暂保留旧 View，后续读取/水位收敛，不伪造 CURRENT。
- CT20 Search/Get、HTTP 路由、真实模型评测、Dreaming、Backup、部署均未启动。

## 8. 当前状态

- **CT19 B2：IN_REVIEW，Lead 初验通过，提交 Architect 独立复核。**
- **CT19：IN_PROGRESS。**
- **CT20/CT21：LOCKED。**
- 真实模型 not-run；无 Git add/commit/push/stash、分支、回滚或发布操作。
