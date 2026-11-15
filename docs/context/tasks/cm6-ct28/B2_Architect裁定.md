# CT28 B2 Architect 技术裁定

## 本次修改（2026-11-11，B2 接缝裁定与必要修正）

- 独立核对 `AlcyoneContext`、真实 Runtime 启动路径、Pending Read/Tx 实现、Backup 错误类型与 Server 依赖，批准经本文件修正后的 [B2 方案核对](B2_方案核对.md)。
- 修正三项会误导实现的事实：`OrganizationPendingReadOps` 新方法的源码兼容、Backup 实际启用/错误阶段语义、Extraction worker 失败快照来源；补齐 managed 关闭与公开错误码边界。
- 本轮只形成技术裁定，未修改生产、测试、构建或 Schema，未派发 Worker；由主线程之后按 Architect → Lead → Worker 实施。

**裁定：B2 技术方案 APPROVED，按下列 D1～D16 与 §2 写集合实施；CT28 整体仍 IN_PROGRESS，不标 DONE。** 原方案与本文件冲突时以本文件为准。

## 1. D 编号裁定

### D1 — 批准范围与前置事实

批准最小 `checkReady()` / `getAdminStatus()`、Pending 只读计数、现有 worker/LLM 状态快照、Admin/Health HTTP 与 managed 组合。Ready 使用既有受管 executor/ReadContext，不另开 JDBC 连接，不做全库 `quick_check`，不调用 LLM/VFS/Map。

B1 保持其生产语义与既有测试；B2 不重写 Read/Ingestion 流水线。`context/server` 已 `implementation(projects.context.runtime)`，managed 入口可直接调用现有 `AlcyoneContextFactory`，无需新增生产依赖。

### D2 — 公共 SDK 接缝

`AlcyoneContext` 只增加以下默认方法，不改变已有 `getStatus()` 的无 I/O、存储路径与字段语义：

```kotlin
suspend fun checkReady(): Boolean = false
suspend fun getAdminStatus(): ContextAdminStatus = ContextAdminStatus(lifecycle = getStatus().lifecycle)
```

新增 `ContextAdminStatus.kt` 为纯安全 DTO。默认实现的未知字段使用 `null`，集合仅在确有“已配置但为空”的事实时为空集合；不得把未知写成 `0`、`IDLE`、`CURRENT` 或 `STOPPED`。DTO 不含 `databasePath`、Key、Throwable、原始异常文本、Prompt、请求正文、临时路径、目标配置或后端连接信息。

### D3 — Pending 计数必须保留源码兼容

`OrganizationPendingReadOps` 增加 `fun count(): Int`，但 **不得直接增加无默认实现的抽象方法**。批准接口默认保守实现：

```kotlin
fun count(): Int = listAllByRawCreatedAt().size
```

这只为第三方 Read/Tx 替身保留源码兼容；生产 `OrganizationPendingReadOpsImpl` 与 `OrganizationPendingTxOpsImpl` **都必须覆写**，经共享 `OrganizationPendingReader` 执行 `SELECT COUNT(*) FROM organization_pending`。Runtime 使用真实 Persistence 实现，禁止以 list 全表代替生产 SQL。

Persistence 要求恰好一行、非空、非负且 `Long → Int` 不溢出；坏值抛既有受控 persistence unavailable。无 Schema、DML、索引或迁移变化。Read/Tx 在同一真实 SQLite 快照中的一致性必须有正式断言。

### D4 — Ready 的权威读与竞态

顺序固定为：RUNNING 预检 → 当前受管 executor 的 `database.read { it.dreaming.readCheckpoint() }` → 再次确认 RUNNING。仅查询成功且两次状态检查通过才为 true；普通读失败、关闭竞争、未知状态均 false，外部 `CancellationException` 原样传播。

不得使用缓存 `databaseVerified`、文件存在或 `SELECT 1` 作为证据。Server 用 `readyTimeoutMillis` 包装一次探针，超时/false/受控读失败为 503；超时只取消本次等待，不声称 JDBC 已强杀。Ready 不因 Pending 非空、LLM 最近失败、Map STALE/MISSING/UNKNOWN 而失败。

### D5 — 管理快照的真实来源与失败语义

| 字段 | 唯一来源与不可用规则 |
| --- | --- |
| lifecycle | Runtime 状态锁内现有 lifecycle；锁外执行数据库 I/O |
| Pending | 与 Dreaming checkpoint 共用一次短 `database.read` 的真实 COUNT |
| Dreaming | `dreamingRuntime` 存在时表示已配置；checkpoint 来自同一 ReadContext |
| Map | `memoryMapPipeline?.snapshot()?.status`；未配置为 null，禁止由 Ready 推断 CURRENT |
| LLM | 受管 `KtorLlmClient` 中既有 `LlmCallStatistics` 的不可变全量快照；client 未创建为 null，零记录为空列表，缺 usage 的 token 为 null |
| Organization/Map worker | 现有 scheduler/coordinator 状态；新增语义明确的只读 `workerStatus()`，原测试访问器可保留 |
| Extraction worker | Scheduler 锁内 `state` + `executions > 0` + 非预期终止标志；Pipeline 只读转发 |
| Dreaming worker | Coordinator 锁内 `activeRun` 与 Runtime state；无历史失败事实时 `lastFailure=null` |
| Backup | 现有 `backupStatusReader`；未配置为 null，不代表已发布对象当前可访问或远端健康 |

管理快照的 Pending 查询失败整次失败并映 503，不返回伪 0；外部 CE 传播。纯进程内 worker/LLM 快照应短锁读取，不得持锁调用 SDK、DB、VFS 或用户回调。

### D6 — Extraction 状态不能用 `runningExecution` 虚构

Lead 方案所称 `runningExecution` 字段在当前 `ExtractionScheduler` 中不存在。批准以现有 `state` 与 `executions` 推导 `NEW/IDLE/RUNNING/STOPPING/CLOSED`，并增加一个仅由“非关闭状态下 worker 自行 CancellationException 终止”设置的真实失败位，映射 FAILED/`lastFailure=true`。

普通单会话 Runner 异常只被隔离，不能把整个 worker 写成 FAILED；正常 close 也不能写成 FAILED。不得新增第二套调度状态机或修改现有单飞、队列与关闭算法。

### D7 — Backup 实际配置与错误阶段必须修正

当前源码没有 `BackupConfig.enabled`：`AlcyoneContextImpl.start()` 在 `acquiredVfs != null` 时创建 `BackupRuntime`，随后由 `backupStatusReader != null` 表示已配置。方案中的“`enabled=false` 不创建 Runtime”不是当前事实，本任务不引入虚构开关或重写 CT27 配置。HTTP “未配置 Backup” 的测试应通过无可用 VFS/Backup Runtime 的真实入口或受控 API Fake 实现，不能删除字段后硬编码预期。

`BackupStageFailure` 当前是 private，且部分 Snapshot/Post-check 失败没有稳定公开 code。为完成 A05 的具名错误映射，允许最小修改 `BackupRuntime.kt`：新增 internal 只读 `failureStage(failure)`，并把当前已有的 Snapshot/Pre-check/Post-check/Publish 分类原样透出；必要时以 internal 受控异常补充 snapshot/post-check 阶段，**不得改变成功流程、发布时点、清理、部分失败或 quick_check 语义**。外部 CE 原样传播。

HTTP 白名单：UNAVAILABLE → 503 `BACKUP_UNAVAILABLE`；ALREADY_RUNNING → 409；QUICK_CHECK_FAILED → 500 `BACKUP_QUICK_CHECK_FAILED`；PUBLISH_FAILED → 500 `BACKUP_PUBLISH_FAILED`；Snapshot → 500 `BACKUP_SNAPSHOT_FAILED`；其他 Pre/Post-check → 500 `BACKUP_PRE_CHECK_FAILED` / `BACKUP_POST_CHECK_FAILED`。不得输出 cause/message，不得因 WebDAV `publishNew` 拒绝而降级普通写入。

### D8 — Admin 路由、正文与安全 DTO

三路由先认证再读正文/调用 SDK；缺/未知 Key 401，已识别错类 403，未配置 adminKey 不可访问。两个 POST 仅接受空 body 或严格 `{}`，复用有界读取、严格 UTF-8、重复键扫描与 `maxSearchBodyBytes`；其他内容 400，超限 413。

`GET /v1/admin/status` 调 `getAdminStatus()`；Dreaming/Backup 等待各自 SDK 真实结果，不另建业务后台 Job。显式安全 JSON DTO 使用稳定 snake_case；Dreaming 返回真实 bounded 结果，Backup 返回已发布 URI/大小/时间/quick_check。错误只使用任务书与本裁定的固定 code，未识别异常 500 `INTERNAL_ERROR`；SDK 自有 TimeoutCancellationException 与其他外部 CE 不映 504。

### D9 — Health 路由

Live 始终无 Key、固定 200，不访问 Runtime。Ready 无 Key，成功 200，false/读失败/自有 ready deadline 503 `NOT_READY`；不得返回数据库路径、异常文本或详细依赖状态。

Live/Ready 同样纳入 managed 请求注册与关闭门，但 Live 不因此依赖 Runtime 健康。旧外部所有权 Server 也注册 Health，非 RUNNING 时 Ready 503；既有“先监听后宿主 start”行为保持。

### D10 — managed 所有权、共享启动与逆序清理

保留 `AlcyoneHttpServer.start(config, context)`：只拥有 CIO，不启动/关闭外部 Runtime。新增 `ManagedAlcyoneHttpServer.kt`，提供直接配置与 YAML 两种清晰入口；YAML 入口分别经现有 `ContextConfigLoader` / `ServerConfigLoader.load`，不能绕过配置、Key 或 ENV 校验。

同一 handle 的重复/并发 start 共享一次真实启动：`AlcyoneContextFactory.create → context.start → CIO listen`，全成功才发布可用。任一阶段失败，已监听先停 HTTP，已启动 Runtime 再 `closeAsync()`；保留主失败并附加清理失败，不留 DB/executor/client。构造 handle 本身不取资源。

### D11 — managed 准入、在途与关闭

用共享锁与准入状态、在途计数/完成信号或等价结构化机制覆盖全部路由；业务路由不另建 Job。close 原子封闭新请求，已注册请求允许按现有取消/清理语义结束；停止 listener，等待 Ktor handler/response writer 与在途请求真实完成，最后才 `context.closeAsync()`。

重复/并发 close 等待并重放同一成功/失败。观察超时可具名失败，**不得当作 drain 成功继续释放 Runtime**；真实清理由不可取消的 cleanup owner 完成。调用方取消只放弃等待，不取消共享清理。事件顺序必须由确定性闸门证明，不能用 sleep 作为完成证据。

caller-owned `runDreaming()` / `backupNow()` 的真实 Job 必须在 Runtime close 前完成其契约要求的收尾。B1 流式首块预取、已提交后 abort、producer cancel/join/finally 保持，managed 外层等待完整链路，不伪造正常 EOF。

### D12 — Server 限额与敏感信息

`ServerLimits` 与 Loader 只新增 `readyTimeoutMillis: Long = 2000`，要求正值，非法/未展开值构造前拒绝。`toString`、配置错误、响应、日志和异常链不得含 Key、原始 body/Prompt、绝对数据库路径、后端 URL/临时路径或任意 Throwable.message。

不为 Admin 状态增加 SQL/JDBC/VFS 实现到 Server；Core/API 不增加 Ktor/serialization 注解。生产依赖不变；若确需测试级依赖必须另报，当前不批准。

### D13 — 实施分批

为避免一次混入所有生命周期改动，B2 分三个连续小批，仍属 CT28 B2：

1. **B2a 状态接缝**：API DTO/默认方法、Pending SQL/覆写、Runtime Ready/Admin、worker/LLM 快照、最小 Backup 错误阶段；对应 api/core/persistence/runtime 测试。此批不实现 Admin HTTP/managed。
2. **B2b Admin/Health**：Server DTO/路由/Ready 配置与真实 HTTP/SQLite/Local FS 测试；保留旧外部所有权入口。覆盖 A05/A06 与鉴权/正文/错误矩阵。
3. **B2c managed 与全量验收**：managed 启动/失败清理/关闭顺序、真实 CIO、在途流与手动 run；随后全量 A01～A08 回归与证据。

每批 Worker 自测后由 Lead 独立初验；B2a 的公共接缝通过后才进入 B2b，B2b 通过后才进入 B2c。技术缺陷交 Worker 定点修，Architect 最终统一裁定 CT28；不得因分小批省略独立验收。

### D14 — 必须新增的正式验收

- API 默认第三方实现：Ready=false，Admin 未知字段为 null，原 getStatus 无 I/O。
- Pending：空表 0、多条、Read/Tx 同事务一致、坏值/溢出、生产实现实际使用 COUNT；第三方默认兼容。
- Runtime Ready：各生命周期、真实 DB 故障、读前后关闭竞争、CE、零 LLM/VFS 调用。
- Runtime Admin：各 worker/Map/LLM/Backup 来源；零 LLM 记录与未配置区别；token 缺失 null；DB 失败不伪零；异常文本/path 不进 DTO。
- Admin：三路由鉴权与严格正文；真实 Dreaming 成功/未配置/并发/各阶段失败/取消；Local FS Backup 成功后独立打开快照并 quick_check/数据核对，未配置/并发/各发布校验失败/WebDAV 拒绝/取消清理。
- Health：Live 无 Runtime；Ready 真 200/503、关闭竞争、DB 故障、ready timeout；LLM 失败、Pending 存在、Map 非 CURRENT 仍 ready。
- managed：真实 CIO/SQLite；配置先校验；并发 start；listen/start 失败逆序清理；封闭准入→在途/CIO 完成→Runtime 释放；并发 close、超时不得抢跑、流与手动 run 清理；外部所有权回归。
- A08：响应/实际日志/异常链 marker 零泄漏；Server 无 SQL/VFS 业务实现、Core 无 Ktor；Schema 与 CT27 保护；14 模块源码 `@Test` 与 XML 一致，0 failure/error/skip。

所有关键成功路径使用真实 Runtime/SQLite/Local FS；Fake 只用于精确错误分支，不冒充真实模型语义质量。

### D15 — 保护边界与明确不交付

禁止修改 `vfs/**`、`common/**`、Prompt、数据库 Schema/索引/迁移、既有治理/检索/提交算法和构建生产依赖。禁止 Git 写操作。保留现有脏树，不 reset/checkout/clean 或格式化写集合外文件。

CT27 仍是 local-atomic-only。真实收费模型、长期跨进程 cron、Caddy/systemd 与生产部署均为 not-run；systemd/Caddy 样例和独立部署冒烟归 CT29。

### D16 — 放行与完成判据

主线程可将本裁定交 Lead，Lead 按 B2a/B2b/B2c 形成具体委派单并派唯一 Worker；不需要用户逐项再授权。Lead 不得兼任 Worker 或 Architect，最终 CT28 DONE 仍须 Architect 独立复核。

最终需 `spotlessApply`、各批定向实测（UP-TO-DATE 不算）、`./scripts/check`、14 模块源码/XML 对账、Schema SHA 保持 `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，以及保护文件 SHA/diff 与 A01～A08 断言位置。测试数量以实际源码为准，不预设伪精确数字。

## 2. 精确生产写集合

下列 SHA 是本次裁定时核对的基线；Worker 开工前 Lead 应复核，若被并发改动则停止并报告。路径中的 `...` 为模块对应 `src/main/kotlin/com/github/noahshen/alcyone/context/memory/` 包路径。

| 文件 | SHA-256 / 允许变更 |
| --- | --- |
| `context/api/.../api/AlcyoneContext.kt` | `6f326b0fee0300481a84879f5194b1069a30bcdd1fe693a6e5c8a9d055f25717`；D2 |
| `context/api/.../api/ContextAdminStatus.kt` | 新增；D2 安全 DTO |
| `context/core/.../core/repository/OrganizationPendingReadOps.kt` | `0c76c889e6a10eef3e7180ec41b1381ec4bbd17fa91e34c6dab698ebbb1e0e6c`；D3 默认 count |
| `context/persistence-sqldelight/.../sqldelight/ContextSqlQueries.kt` | `cb82d3e6245231e9f30dfec23db1190a48ed44f708186fefe123da081686c5df`；只加 COUNT_PENDING |
| `context/persistence-sqldelight/.../sqldelight/ContextOps.kt` | `6915d2817127dab1fcb757b9bcca8f40e5c239cb356d04c445cffa1ceac1c060`；共享 reader + Read/Tx 覆写 |
| `context/runtime/.../runtime/AlcyoneContextImpl.kt` | `7af81cb1d730070a369bb0b92e5b6495b7670765ead5005bc29a1d2ef5d6ca23`；Ready/Admin 组装 |
| `context/runtime/.../runtime/llm/LlmCallDiagnostics.kt` | `4c838195e6d115ded4cfd00757c00bb0307536b3ef88c1acaece3c1a18b4e84f`；不可变全量统计 |
| `context/runtime/.../runtime/llm/KtorLlmClient.kt` | `f56153372dfc5710963efccb3db9335f0b1d6dd848f45ceb01b77e90956aebf5`；只读转发 |
| `context/runtime/.../runtime/extraction/ExtractionScheduler.kt` | `4fe977006d562069fafbb3e489fc160bd62d0b465f3f15159872e093e7985c09`；D6 |
| `context/runtime/.../runtime/extraction/ExtractionPipeline.kt` | `9b898911712a4868658f84839ed4003c3e6d212dc457daf827499c6efa86757d`；只读转发 |
| `context/runtime/.../runtime/organization/OrganizationScheduler.kt` | `5c9379af36a853278b1e374b1f3279c4cd9b54a39e93592dc4d45af2b6aba1e6`；只读语义别名 |
| `context/runtime/.../runtime/organization/OrganizationPipeline.kt` | `48350dbf97b74d6dfb37de51daeacd2abb46244d2e921cedbc1e163626592031`；必要时只读转发 |
| `context/runtime/.../runtime/dreaming/DreamingRuntime.kt` | `6542de5185cf850c592d1d8dc3b71fa1180cdf9af9866e99130e45912d58bf1e`；worker 快照 |
| `context/runtime/.../runtime/dreaming/DreamingRunCoordinator.kt` | `cbd5d425f6b5700eff362ead4bdd4041c8a6f86e5ef622e7c167e6a31f8d9ab3`；active/state 快照 |
| `context/runtime/.../runtime/backup/BackupRuntime.kt` | `fc7a55637ee13eab44f1e8177bd2f850ba8b0dc8994129b0a0a88f2b258a6870`；仅 D7 最小错误阶段 |
| `context/server/.../server/AlcyoneHttpServer.kt` | `f56a07d1b8ecd8fbda0bb6c930315e97fb8907887a2e5ca575a63274d7ced3c6`；Admin/Health 与 managed 注册接缝 |
| `context/server/.../server/ServerConfig.kt` | `f73c839c01c7f05211c026793f485b73f05befcfedc20e226ea38f87df5959fc`；readyTimeoutMillis |
| `context/server/.../server/ServerConfigLoader.kt` | `a90dd57d2f4e2836015f107e04ef066d69abfd1fa25066acbfb28d94a836b6e6`；严格解析 |
| `context/server/.../server/ReadHttpApi.kt` | `4a6a4df9e6532dd42f1e0872ae6e3caa4afa9d4c4f8b0a8a8c313cea254c5c3a`；仅 managed 注册接缝 |
| `context/server/.../server/HttpApiSupport.kt` | `99776df10edb4b2771511ca82eac9e3c1bd0f14ee9a5f4d135dd253e11d725df`；仅共享请求辅助 |
| `context/server/.../server/IngestionHttpApi.kt` | `aaead10b7caa0c6bdc71714c855dba88b0e89d8f151dc14bf060eb6ea8628024`；仅 managed 注册接缝 |
| `context/server/.../server/AdminHttpApi.kt` | 新增；D8/D9 |
| `context/server/.../server/AdminHttpDto.kt` | 新增；显式安全 JSON |
| `context/server/.../server/ManagedAlcyoneHttpServer.kt` | 新增；D10/D11 |

`MemoryMapPipeline.kt`、`MemoryMapService.kt`、`ContextConfig.kt`、`BackupConfig.kt`、`AlcyoneContextFactory.kt`、`ServerAuth.kt`、`ReadHttpDto.kt`、`IngestionClientValidation.kt` 默认只读复用。若 Lead 证明必须修改其中任何文件，须先提交精确原因与 diff 范围再获技术确认；不得静默扩大集合。

## 3. 测试写集合

允许新增或定点扩充以下测试，复用 `ReadRuntimeFixture` 等现有辅助，不为 HTTP 重写业务流水线：

- `context/api/src/test/.../ContextAdminStatusTest.kt`（新增）及必要的 `AlcyoneContext` 默认契约测试。
- `context/persistence-sqldelight/src/test/.../ContextOrganizationPendingRealDbTest.kt`。
- `context/runtime/src/test/...`：`AlcyoneContextReadyTest.kt`、`AlcyoneContextAdminStatusTest.kt`（新增），以及 Extraction/Organization/Dreaming/LLM/Backup 对应既有测试类的最小只读快照断言。
- `context/server/src/test/...`：`AdminHttpApiTest.kt`、`HealthHttpApiTest.kt`、`ManagedAlcyoneHttpServerTest.kt`（新增）；必要时扩充 `ServerConfigLoaderTest`、`ReadRuntimeFixture` 与 Backup/生命周期测试辅助。
- `integration-tests/context/src/test/...` 仅在 Server 测试无法真实组合时新增一个明确命名的 managed/Admin 集成测试；不得复制流水线测试或改变构建依赖。

Worker 每批交付精确新增/修改文件、测试名与断言位置；Lead 独立复核后才允许进入下一批。本文件是技术放行，不是实现完成或验收通过声明。
