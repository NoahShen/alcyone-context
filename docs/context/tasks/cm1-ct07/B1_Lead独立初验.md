# CT07-B1（Ingestion Service + Runtime SDK）Lead 独立初验

## 本次修改（2026-10-29，CT07-B1 初验，提交 Architect 逐批复核）

- CT07 方案与 Q1～Q5 已批准（`CT07_任务书与方案.md` §8）。B1 由同一 Paseo worker 配置 Worker `d7695b22` 实现（单一写入者）；Lead 未写生产代码。
- 范围：Core Ingestion Service、`context.ingestion` 限额、通知 Port + Runtime SDK 接线；未做 B2 Server/HTTP；未建 Worker/Cron/队列；未改 VFS/CT04/CT05/CT06 已验收语义；无 git 写操作。

## 1. 交付物（SHA 已核对）

| 文件 | SHA-256（前缀） |
| --- | --- |
| `api/AlcyoneContext.kt`（仅增加冻结方法） | `cbf598f793027850` |
| `core/ingestion/IngestionLimits.kt` | `032a76db699397f3` |
| `core/ingestion/IngestionNotifier.kt` | `a478d60370784b4e` |
| `core/ingestion/IngestionService.kt` | `a201d286cb80fc07` |
| `runtime/ContextConfig.kt` | `99b0a2213d97cfff` |
| `runtime/ContextConfigLoader.kt` | `02482d79d9032bea` |
| `runtime/AlcyoneContextImpl.kt` | `527af750dd7b0a0a` |
| `runtime/AlcyoneContextFactory.kt` | `8ce27579f9ad368c` |

## 2. Lead 独立实跑（非引用 Worker 日志）

| 模块 | 结果 |
| --- | --- |
| `context:api:compileKotlin` | exit 0 |
| `context:core:test` | **9 例**，0 失败/错误/跳过，源码=XML=9 |
| `context:runtime:test` | **69 例**，0 失败/错误/跳过，源码=XML=69 |
| `context:persistence-sqldelight:test` | **155 例**，0 失败/错误/跳过，源码=XML=155 |
| `core/runtime spotlessCheck --rerun-tasks` | exit 0 |

日志 `.local/validation/cm1/lead/ct07/b1-check.log`。runtime 增量 20：`IngestionServiceRealDbTest` 9 + `IngestionConstraintRaceTest` 6 + `IngestionRuntimeSdkTest` 5。

## 3. 关键实现与断言审查

- **配置限额**：`context.ingestion.{maxTurnBytes,maxMessages,maxIdLength}` 默认 2 MiB/256/256；覆盖生效；0/负数/非整数 → `INVALID_CONFIG`（只带字段路径）；ingestion 内未知字段 → `UNKNOWN_FIELD`；值对象不可变，实例自有。保留 CT06 对 llm 等蓝图键暂不消费。
- **校验/限额**：四 ID 非空白且 ≤ maxIdLength（超长 `PAYLOAD_TOO_LARGE`）；messages 非空且 ≤ maxMessages（空 `INVALID_TURN`、超限 `PAYLOAD_TOO_LARGE`）；规范化 UTF-8 ≤ maxTurnBytes；超限时库内无行。
- **幂等/事务**：单 `database.write` 内查找/比较/INSERT；CREATED 使用注入 Clock + `TimeCodec`；DUPLICATE 返回原 id，不刷新 createdAt/状态；比较仅用规范化 timestamp + 有序 `(role,content)`；complete 后重放仍 DUPLICATE 且状态不变；重开后同键仍 DUPLICATE。
- **唯一约束竞争**：替身注入 SQLSTATE 23000 → 重读权威记录返回 DUPLICATE/TURN_CONFLICT（不 500）；非 23000 原样上抛；COMMIT 失败不返回 CREATED。
- **通知**：Core `IngestionNotifier` 为内部 Port；仅 CREATED 且 `write` 成功返回后在事务外 `notificationScope.launch` 异步调用；DUPLICATE 不通知；通知抛错不回滚、不影响结果；scope 在 close 时取消；未创建 Worker/Cron/队列。
- **生命周期**：CREATED/STARTING → `NOT_STARTED`；STOPPING/CLOSED → `CLOSED`；RUNNING 才允许入库。
- **依赖边界**：Core 未引入 Ktor/YAML/JDBC（`grep io.ktor|java.sql|org.yaml` 为空）；API 仅增加冻结 `ingestConversation`，无其他新契约；Server 未参与 B1。

## 4. 结论与边界

- **B1 初验通过**：Service/SDK/限额/通知/生命周期在真实 SQLite 下验证，计数与差额 0，依赖边界与契约范围正确。
- 提交 **Architect 逐批复核**；B1 通过前不放行 B2。B2（Server/HTTP/Bearer/413/strict JSON/无 offset 时间）待放行。CT07 整体通过后才做 CM1 整体验收；CM2 不启动。

## 5. R1/R2 修复后 Lead 独立复核（2026-10-29）

- **R1 时间 offset**：新增 Core 内部 `IngestionTimestampText.parseRequiringOffset`（`OffsetDateTime.parse`），只接受带明确 offset 的 ISO-8601；`+08:00` 与 `Z` 得同一 Instant；无 offset/纯日期/空白/非法 → `INVALID_TURN` 且不回显原文。SDK 仍用 `Instant`，KDoc 如实记录「SDK 可信路径无法校验原始文本 offset，只保证按 Instant 幂等与 TimeCodec 存储」；未引入原始文本载体、未改 API。
- **R2 通知可见性**：Core 新增内部 `IngestionNotificationReporter`（只传异常）；`notifyCreated` 非取消失败上报，`CancellationException` 先上报再抛出。Runtime 通知 scope **懒创建**（首次 CREATED 才建 `Dispatchers.IO.limitedParallelism(1)`），跟踪在途 Job；默认 reporter 用 SLF4J 只记录异常类名；close 取消剩余 Job 并逐个上报取消，不阻塞既有清理。
- **Lead 独立实跑**：`:context:api:compileKotlin :context:core:test :context:runtime:test :context:persistence-sqldelight:test :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks` → exit 0；core **11 例**、runtime **74 例**、persistence **155 例**，均 0 失败/错误/跳过，源码=XML 差额 0。日志 `.local/validation/cm1/lead/ct07/b1-fix-check.log`。
- **关键新增断言**：无 offset/纯日期/非法拒绝且不回显；`+08:00` 与 `Z` 等价并 DUPLICATE；存储 `2026-10-29T08:00:00.000+08:00`；reporter 收到同一异常且 CREATED 不回滚；DUPLICATE 不通知、reporter 为空；create/start 后无通知 Job（懒创建）；阻塞 notifier 下 close 记录取消且 Job 归零；上报不含 SessionKey/正文。
- **SHA**：api `AlcyoneContext.kt`=`cbf598f793027850`（未变）；`IngestionTimestampText.kt`=`8cc579daff212ac5`；`IngestionNotificationReporter.kt`=`42d30f834967cbcc`；`IngestionService.kt`=`e1255b19c99e716d`；`AlcyoneContextImpl.kt`=`cfd99ab3e0fcf9d6`；`AlcyoneContextFactory.kt`=`e977b89f9b29d220`。
- **结论**：B1-R1/R2 关闭，B1 初验维持通过，提交当前 Architect 复核；B1 未通过不放行 B2，CT07 未完成前不启动 CM1 整体验收，CM2 不启动。

## 6. R3（close 与通知准入竞态）修复后 Lead 独立复核（2026-10-29）

- **修复**：`notificationLock` 下新增 `notificationsClosed`；`cancelNotifications` 锁内先置关闭、快照 scope/在途 Job 并置空，锁外取消并对每个挂起 Job 用固定脱敏 `CancellationException` 上报；`launchNotification` 锁内检查关闭（已关闭返回 false，不建 scope/Job），否则 LAZY Job 启动前加入跟踪集合，释放锁后 `start()`；COMMIT 成功但未准入时上报固定取消，不静默。
- **Lead 独立实跑**：`:context:core:test :context:runtime:test :context:persistence-sqldelight:test :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks` → exit 0；core **11**、runtime **75**、persistence **155**，均 0 失败/错误/跳过，源码=XML 差额 0。日志 `.local/validation/cm1/lead/ct07/b1-fix2-check.log`。
- **确定性竞态测试已核实**：ingest 线程在 internal gate 挂起（COMMIT 已成功）→ close → 放行 gate；断言 close 后 scope 未重建、Job 数 0、reporter 收到 `CancellationException`、上报不含 SessionKey 值/正文、Turn 仍返回 CREATED 且独立只读连接可见 1 行。
- **SHA**：`AlcyoneContextImpl.kt`=`59e1948d3298f140`；`IngestionNotificationRaceTest.kt`=`3bc60e92fae685fa`；API `AlcyoneContext.kt`=`cbf598f793027850`（未变）；Core 语义与 CT04/CT05/CT06 未改。
- **结论**：B1-R3 关闭，B1 初验通过，提交当前 Architect 复核；B1 通过前不放行 B2，CT07 未完成前不启动 CM1 整体验收，CM2 不启动。
