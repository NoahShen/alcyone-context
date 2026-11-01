# CT07-B1 Worker 交付（Core Ingestion Service + Runtime SDK + 限额 + 通知 Port）

**任务**：CM1 CT07-B1：`context.ingestion` 限额、Core 入库 Service、Runtime `ingestConversation`、提交后通知 Port
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db（Paseo lead 配置，只做方案与独立初验）
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-29

## 本次修改（2026-10-29，B1 复核修复 R3 通知准入竞态）

- 在 `notificationLock` 下新增 `notificationsClosed`：close 先置关闭标记再快照/取消通知，之后到达的通知**不得重建 scope/Job**。
- `launchNotification` 改为锁内检查准入、懒创建 scope，并以 `CoroutineStart.LAZY` 建 Job、**start 前加入跟踪集合**；解锁后才 `start()`，锁内无 suspend。
- COMMIT 成功但通知未准入（close 已获胜）时，用同一固定脱敏 `CancellationException` 上报 reporter，不静默丢弃；close 仍对关闭时挂起的 Job 上报固定取消。
- 新增 internal `notificationLaunchGateForTesting`（仅测试）：在 CREATED 已提交、通知准入前执行。确定性竞态测试：ingest 线程在 gate 挂起（证明已提交）→ 主线程 close→ 放行 gate；断言 scope 未重建、Job 归零、reporter 收到固定取消且不含 SessionKey/正文、ingest 仍返回 CREATED、独立只读连接可见该行。
- 计数更新：core **11**、runtime **75**（+1 R3 竞态测试）、persistence 155；源码 `@Test` = XML，差额 0。证据 `.local/validation/cm1/worker/ct07-b1-fix2/`。

## 本次修改（2026-10-29，B1 复核修复 R1 时间 offset / R2 通知可见性）

- **R1**：新增 Core 内部 `IngestionTimestampText.parseRequiringOffset`（`java.time`，不依赖 HTTP），只接受带明确 offset 的 ISO-8601；`+08:00` 与 `Z` 得同一 Instant；无 offset/纯日期/非法 → `INVALID_TURN`，消息不回显原文。`IngestionService` KDoc 如实记录 SDK 入参为 Instant、无法校验原始文本 offset 的限制（不引入文本载体、不改 API）。
- **R2**：新增 Core 内部 `IngestionNotificationReporter.reportFailure(Throwable)`（只传异常）；`IngestionService` 通知失败上报 reporter，`CancellationException` 先上报再原样抛出。Runtime 通知作用域改为**懒创建**（首次 CREATED 才建 `Dispatchers.IO.limitedParallelism(1)`），跟踪在途 Job；默认 reporter 用 SLF4J 只记录异常类名；close 取消剩余 Job 并逐个上报取消，不阻塞清理协议。
- 测试补强：无 offset/非法/纯日期 → `INVALID_TURN`；`+08:00` 与 `Z` 等价并判 DUPLICATE；落库时间文本断言 `+08:00` 毫秒；reporter 收到同一失败实例、CREATED 仍成功；DUPLICATE 不通知不报告；create/start 无通知作用域；close 上报取消且无残留 Job；上报内容不含 SessionKey/正文。
- 计数更新：core **11**、runtime **74**（新增 core 2 例 + runtime 5 例），persistence 155 不变；三模块源码 `@Test` = XML，差额 0。证据 `.local/validation/cm1/worker/ct07-b1-fix/`。

## 本次修改（2026-10-29，CT07-B1 入库 Service / SDK / 限额 / 通知）

- `context.ingestion` 配置进入 ContextConfig/Loader：默认 `maxTurnBytes=2 MiB`、`maxMessages=256`、`maxIdLength=256`；出现时严格正整数校验（非法 `INVALID_CONFIG`），ingestion 内未知字段 `UNKNOWN_FIELD`；解析结果以不可变值对象归实例所有。
- `context/core` 新增 `IngestionLimits`、`IngestionNotifier`（Core 内部 Port）与 `IngestionService`：规范化、限额、单写事务幂等（CREATED/DUPLICATE/TURN_CONFLICT）、SQLSTATE 23000 竞争重读、事务外通知。
- `context/runtime` 实现冻结方法 `ingestConversation`：生命周期门（CREATED/STARTING → `NOT_STARTED`，STOPPING/CLOSED → `CLOSED`）、复用已打开数据库与可注入 `Clock`、CREATED 后异步通知（受控 scope，close 取消）；默认通知 no-op。
- 新增 20 个 B1 测试（真实文件 SQLite）：`IngestionServiceRealDbTest` 9、`IngestionConstraintRaceTest` 6（测试替身）、`IngestionRuntimeSdkTest` 5；`ContextConfigLoaderTest` 同步覆盖限额默认/覆盖/非法/未知与值快照独立性。
- 未改 CT04/CT05/CT06 已验收语义、未改 VFS、未做 B2 HTTP/Server、未建 Worker/Cron/持久队列，未 `git add/commit`。

## 1. 配置（`context.ingestion`）

| 字段 | 默认 | 校验 |
| --- | --- | --- |
| `maxTurnBytes` | 2 MiB（2097152） | 正整数，否则 `INVALID_CONFIG` |
| `maxMessages` | 256 | 正整数 |
| `maxIdLength` | 256 | 正整数 |

- `context.ingestion` 下未知字段 → `UNKNOWN_FIELD`（含路径）。
- 缺省时使用 `IngestionLimits.DEFAULTS`；Loader 构造不可变值对象，`ContextConfig.ingestionLimits` 归实例所有（例如两份配置互不影响）。
- `context.llm/organization/memoryMap/backup` 仍按 CT06 阶段边界暂不消费；`server` 仍由 Server 层负责。

## 2. Core IngestionService

| 能力 | 实现 |
| --- | --- |
| 校验 | 四个 ID 非空白且长度 ≤ maxIdLength（超长 `PAYLOAD_TOO_LARGE`）；messages 非空（空 `INVALID_TURN`）且 ≤ maxMessages；规范化时间 + ID + role/content 的 UTF-8 字节 ≤ maxTurnBytes |
| 规范化 | `timestamp` 经 `TimeCodec.format` 统一 `+08:00` 毫秒；messages 顺序与 content 原样保留（不 trim/截断/去重） |
| 幂等 | 一次 `database.write { }` 内按四元键查找/比较/INSERT；不存在 → `CREATED`（pending，`created_at=clock.instant()`）；相同规范化时间 + 有序 `(role,content)` → `DUPLICATE`（原 id，不刷新任何字段）；不同 → `TURN_CONFLICT` |
| 唯一约束竞争 | 插入路径捕获 `ContextPersistenceException` 消息中的 `SQLSTATE 23000`（CT04 安全映射保留的唯一信息），重新读取权威记录 → `DUPLICATE`/`TURN_CONFLICT`；其他持久化异常原样上抛；COMMIT 失败由 `database.write` 抛出，不返回 CREATED |
| 通知 | `IngestionNotifier.onCreated(sessionKey)` 仅在 CREATED 且事务提交成功后、事务外调用；异常脱敏捕获，不影响结果 |

`IngestionService.create(...)` 返回 `DelegatingIngestionService`：把「服务调用」绑定到 `database.write { tx -> ... }`，事务边界清晰。

## 3. Runtime 接线

- `AlcyoneContextImpl.ingestConversation`：`CREATED/STARTING → ContextLifecycleException(NOT_STARTED)`；`STOPPING/CLOSED → CLOSED`；`RUNNING` 委托 Service。
- `created_at` 使用注入的 `Clock`（生产 `Clock.systemUTC()`，测试固定时钟）。
- CREATED 返回前 `notificationScope.launch { notifyCreated(...) }`（`SupervisorJob`，不等待）；DUPLICATE 不启动通知；close 时取消 scope。不是 Worker/Cron/持久队列。
- 默认通知实现 no-op；内测可注入记录/抛错替身（不进入公开 API）。

## 4. 测试与断言

| 测试类 | 例数 | 关键断言 |
| --- | --- | --- |
| `IngestionServiceRealDbTest` | 12 | 首次 CREATED；重复 DUPLICATE 同 id、createdAt/status 不刷新；等价时间 DUPLICATE、消息顺序/内容变化 TURN_CONFLICT；complete 后重放仍 DUPLICATE 且状态 COMPLETE；超长 ID / 超 messages / 超 2 MiB → PAYLOAD_TOO_LARGE 且库内无行；冻结 API 模型拒绝空白 ID 与空消息；并发同键 → 一个 CREATED 一个 DUPLICATE 同 id；重开仍 DUPLICATE；`notifyCreated` 吞掉替身异常 |
| `IngestionConstraintRaceTest` | 6 | 23000 后重读赢家：相同内容 → DUPLICATE、不同内容 → TURN_CONFLICT、读不到 → TURN_CONFLICT；非 23000 持久化异常原样上抛；insert 返回非 CREATED 不伪报；COMMIT 失败向上传播 |
| `IngestionRuntimeSdkTest` | 7 | 未启动 `NOT_STARTED`、关闭后 `CLOSED`；STARTING 期间 `NOT_STARTED`；CREATED 异步通知一次、DUPLICATE 不再通知；通知抛错不影响 CREATED；注入 Clock 决定 createdAt |
| `ContextConfigLoaderTest`（增量） | 20（+1 改造） | 限额默认/覆盖/非法/未知字段；值对象快照独立 |

错误消息只含字段类别（如 `context.ingestion.maxTurnBytes`、`runtimeId`），不含 ID 值、正文或连接信息。

## 5. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `:context:core:spotlessApply :context:core:compileKotlin`、`:context:runtime:spotlessApply :context:runtime:compileKotlin` | 0 | 生产批次编译 |
| `:context:core:spotlessApply :context:runtime:spotlessApply :context:api:spotlessApply` | 0 | 格式化；`spotless-apply.log` |
| `:context:api:compileKotlin :context:core:test :context:runtime:test :context:persistence-sqldelight:test :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks` | 0 | **core 9 / runtime 69 / persistence 155 全绿**；三模块源码 `@Test` 与 XML 均一致，差额 0；`targeted-test.log` |

runtime 75 = 原有 49 + `IngestionServiceRealDbTest` 12 + `IngestionConstraintRaceTest` 6 + `IngestionRuntimeSdkTest` 7 + `IngestionNotificationRaceTest` 1（`ContextConfigLoaderTest` 仍 20）；core 11 = 原 9 + `IngestionTimestampTextTest` 2。证据 `.local/validation/cm1/worker/ct07-b1/`：`targeted-test.log`、`spotless-apply.log`、`final-source-hashes.json`、`verification.json`。

## 6. 改动文件与最终 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `context/api/.../AlcyoneContext.kt`（新增冻结方法） | `cbf598f793027850a4a99456a391f07305ae5f7e567e796a39bc8e6937c10207` |
| `context/core/.../ingestion/IngestionLimits.kt` | `032a76db699397f360539448fe5f10f71f830f0ba6988c70e8e0a898dd25c488` |
| `context/core/.../ingestion/IngestionNotifier.kt` | `a478d60370784b4e7e1ef6e80d2bcfea866db34c5fbd5684ae062c1d3a3dbcd0` |
| `context/core/.../ingestion/IngestionService.kt` | `e1255b19c99e716de1d8d66e2df1bbea7ae561098ce18e1e246a658262809076` |
| `context/core/.../ingestion/IngestionNotificationReporter.kt` | `42d30f834967cbccd43ed340c4f4e33977c03fbff7e0c42f74ad9d0262a05634` |
| `context/core/.../ingestion/IngestionTimestampText.kt` | `8cc579daff212ac5d50c0649ed0d8345afbd6d55b299328122093cb9db63b99b` |
| `context/core/src/test/.../ingestion/IngestionTimestampTextTest.kt` | `48f9ed0a26cae4d1bbe530da571321867698efdc9a561f5c74deb941c9048a3d` |
| `context/runtime/.../ContextConfig.kt` | `99b0a2213d97cfff2d069941d13433abb2646ab3fd14831e5170f44d1b0012c3` |
| `context/runtime/.../ContextConfigLoader.kt` | `02482d79d9032beac2e620322a2ba051352ed6ddd291d961c9bbeea643e36bae` |
| `context/runtime/.../AlcyoneContextImpl.kt` | `59e1948d3298f1408ddeef7b5258acb1ee4257ca058d4de60ce756c7f2702f93` |
| `context/runtime/.../AlcyoneContextFactory.kt` | `e977b89f9b29d22013294ff27c898030f9871164a8e13aeb68709b96c6c543fc` |
| `context/runtime/src/test/.../ContextFixture.kt` | `f99971c52948e5174cc51475363c990e42ae2a2f1bf615c27ed43077e5ca42f7` |
| `.../ContextConfigLoaderTest.kt` | `c30698440240c795900512c97b21582fe15cfc9941e053dc323e3ebb0ca969e8` |
| `.../ingestion/IngestionTestSupport.kt` | `e2c0367c7e72b60a01808544ed70593597d22f6d57797fa9af61587da9e5dd04` |
| `.../ingestion/IngestionServiceRealDbTest.kt` | `f9f45beb021fa57a6947479c9ad944483c4a691685cbb7b16de0ae9493fb548f` |
| `.../ingestion/IngestionConstraintRaceTest.kt` | `45b6b6f9465c618a14371f7edf9d2731b25b10d9a12678df5d37d2b8ca320a3d` |
| `.../ingestion/IngestionRuntimeSdkTest.kt` | `3795609fa49a9992b46330a42a845c1fbd3132ccfeb429b389005cc861678b58` |
| `.../ingestion/IngestionNotificationRaceTest.kt` | `3bc60e92fae685fa502f7419f1a45a857960de4c42a9b886a05347e7f6d9ee02` |

完整清单见 `final-source-hashes.json`。

## 7. 未做项与边界

- **未做 B2**：Server HTTP、Bearer 认证/绑定、413 前置限额、Server 配置解析与 Ktor 测试；未启动 CT07-B2。
- 未建 LLM/Extraction/Organization/Dreaming/Worker/Cron/持久队列；未改 VFS、CT04 保护层、CT05/CT06 已验收语义；未 `git add/commit`。
- 空白 ID / 空消息由冻结 API 模型 `ConversationTurn` 直接拒绝（`IllegalArgumentException`），Service 内保留防御性校验；测试覆盖 API 层拒绝且库内无行。
- 唯一约束竞争识别依赖 CT04 `ContextSqliteErrors` 在安全消息中保留的 `SQLSTATE 23000` 文本（不带 SQL 原文/正文）；不修改持久化层。
- 通知是尽力而为的 fire-and-forget：不保证送达、不重试、不持久化；close 会取消未完成通知。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 独立初验与 Architect 独立复核。

## 8. R1 时间 offset / R2 通知可见性修复记录（2026-10-29）

| 项 | 落点 |
| --- | --- |
| R1 工具 | `context/core/.../ingestion/IngestionTimestampText.parseRequiringOffset`：`OffsetDateTime.parse`，`+08:00`/`Z` 等价；无 offset/纯日期/非法 → `INVALID_TURN`，消息只给字段类别 |
| R1 落库 | 真实库断言原始 `timestamp` 文本为 `2026-10-29T08:00:00.000+08:00`；`+08:00` 与 `Z` 解析后同一 Instant 且判 DUPLICATE |
| R1 文档 | `IngestionService` KDoc 如实记录 SDK 入参为 Instant、无法拒绝无 offset 文本；B2 用该工具解析 HTTP 文本并映射 400 |
| R2 Port | `IngestionNotificationReporter.reportFailure(Throwable)` 只传异常；非取消失败上报，`CancellationException` 先上报再原样抛出 |
| R2 Runtime | 懒创建 `Dispatchers.IO.limitedParallelism(1)` 通知 scope（create/start 零任务）；在途 Job 跟踪；默认 reporter 用 SLF4J 仅记录异常类名；close 取消剩余 Job 并上报取消 |
| R2 测试 | reporter 收到同一失败实例且 CREATED 成功；DUPLICATE 不通知不报告；create/start 无通知作用域；close 上报取消且无残留 Job；上报字符串不含 SessionKey/正文 |

复跑：`:context:api:compileKotlin :context:core:test :context:runtime:test :context:persistence-sqldelight:test :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks` → 退出 0；core 11 / runtime 74 / persistence 155，源码 `@Test` 与 XML 一致，差额 0。

## 9. R3 通知准入竞态修复记录（2026-10-29）

| 项 | 落点 |
| --- | --- |
| 准入标记 | `notificationLock` 下 `notificationsClosed`；close 锁内先置 true 再快照 scope/Job |
| 准入实现 | `launchNotification` 锁内检查标记；关闭后返回 false 且不创建 scope/Job；否则 LAZY 建 Job、start 前加入跟踪集合，解锁后 start |
| 取消上报 | `cancelNotifications` 锁外取消旧 scope/Job，对关闭时挂起 Job 上报固定 `CancellationException`；未准入的 CREATED 同样上报，不静默丢弃 |
| 测试钩子 | `internal notificationLaunchGateForTesting`：CREATED 提交后、准入前执行；生产为 null |
| 竞态测试 | `IngestionNotificationRaceTest`：gate 挂起证明已提交 → close → 放行；断言 scope 未重建、Job=0、reporter 收到固定取消且不含 SessionKey/正文、ingest 返回 CREATED、独立只读连接可见 Turn |
| 复跑 | core 11 / runtime 75 / persistence 155，源码 `@Test` = XML，差额 0；spotlessCheck 通过 |
