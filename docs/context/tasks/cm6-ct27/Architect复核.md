# CT27 · Architect 方案裁定

## 本次修改（2026-11-11，R2 独立复核与最终裁定）

- 本轮按 `PROGRESS_SAVE.md` 交接由 Architect 独立复核，不依赖不存在的 `Lead初验.md`、历史 Agent 口头结论或 `Backup_完整设计.md`；后者仍不是实现契约。
- 工作区原样保留；本轮只修改本复核文档。未 reset/checkout/clean，未执行 Git 写操作，未启动 CT28/CT29。
- **裁定：PASS / R2；CT27 完成，CM6 保持 ACTIVE。** 该结论只关闭 CT27。CT28/CT29 仍 LOCKED，必须分别取得实施授权。

### 2.1 R1 逐项关闭

| R1 | 裁定依据 | 结果 |
|---|---|---|
| R1-1 Xerial/目标连接 | [实现](../../../../context/persistence-sqldelight/src/main/kotlin/com/github/noahshen/alcyone/context/memory/persistence/sqldelight/ContextSqliteBackup.kt) 使用真实 `SQLiteConnection.getDatabase().backup(...)`；目录内文档记录的 3.49.1.0 签名一致。目标由 backup 创建，完成/异常/取消在 `NonCancellable` 中释放 native backup 与目标 handle；独立 post-check 连接只在 backup 返回后打开。取消通过 `SQLiteConfig.setProgressHandler` + driver cancellation 触发，不把 `nStep` 冒充逐页回调。真实适配测试见 `ContextBackupSnapshotTest`。 | PASS |
| R1-2 写 Slot/发布边界 | `ContextDatabaseExecutor.backupTo` 复用 `enter`、唯一写 permit、`inFlight` 与 `withUserCallback`；读可并行，业务写串行。真实并发测试同时验证业务写最终成功及备份快照的跨表已提交状态。Local FS 以同目录 `CREATE_NEW` 临时 inode、`force(true)`、`Files.createLink(target,temp)` 完成不覆盖发布；目标已存在不会替换。发布后 Node/Event 失败映射 `STATE_ERROR + PARTIAL` 并保留正式文件。 | PASS |
| R1-3 唯一 URI/WebDAV | 每个 run 生成由 Clock 时间、UUID 路径和短随机后缀组成的唯一 URI，固定 Clock 的连续 run 也不覆盖。CT27 的 Backup 配置在启动期只接受 `atomicPublishNew=true` 且不存在备份目标前缀冲突；真实 WsgiDAV 100 轮并发 MOVE 的反证（63 双 201、37 个 201+500、0 个条件失败）和不完整 PUT 的正式 URI 暴露风险，使本专题明确降级为 **local-atomic only**，不以普通 PUT/MOVE 冒充安全发布。该降级已写进当前任务书、B1/B2 与设计 §9，不再属于静默偏离。WebDAV 自身仍明确返回 `UNSUPPORTED_OPERATION`，真实套件通过。 | PASS（显式降级） |
| R1-4 流/限额/锁边界 | Core 在 `StateBoundary` 内只做路由、冲突检查、父目录补建、Storage 发布与状态提交；Storage 的分块复制、取消检查、fsync、发布不占 VFS 状态锁。`InputStream` 所有权唯一归 VFS/Storage，Core 仅对“尚未交接给 Storage”的早期失败做防御性幂等关闭；本地实现与 Runtime 测试均验证 short/long/失败后关闭。备份 `maxBytes` 有界，pre-check 对快照做大小校验，post-check 用独立 JDBC 验证完整 `quick_check`、字节数及 SQLite 页数/页头一致性，不构造整库 ByteArray。 | PASS（见 2.3） |
| R1-5 写集合/模块 | 当前真实写集合已收敛到 `vfs/{api,core,storage,runtime}`、`context/{api,persistence-sqldelight,runtime}`、对应 `integration-tests/{vfs,context}`；实现位于真实 `vfs/storage`，不存在不存在的 `storage-local-fs/storage-webdav` 模块。CM6 文档由 Lead/Architect 共同收口，无 Worker 并发写冲突。 | PASS |
| R1-6 API/状态/关闭 | `cron != null` 即自动调度，无 cron 为 manual-only，`enabled` 已移除。普通 run 失败记录实际阶段（snapshot/pre-check/publish/post-check）和 code 后回 `IDLE`；`FAILED` 不用于普通外部失败。Runtime 在 DB/VFS 释放前停止 Backup，等待已登记 run drain；发布后的正式文件在 post-check/close/失败时保留。取消与 Runtime close 均有真实测试。 | PASS |

### 2.2 独立验证

以下均在当前工作区、当前源码上执行，不引用旧 Agent 汇报：

- 常规 14 个受管模块：行首 `@Test` **2156**，XML `tests` 合计 **2156**、failures/errors/skipped 均为 0；最近一次全量 `./scripts/check` 为 **BUILD SUCCESSFUL in 2m 25s，132 actionable tasks: 132 executed**（`.local/ct27-lead-rerun-check.log`）。
- 真实 WebDAV：`.local/validation/t36-lead-r10/webdav.log` 为 **BUILD SUCCESSFUL in 7s，17 actionable tasks: 17 executed**；WebDAV XML 额外 **44** 条，failures/errors/skipped 均为 0；fixture server 与临时 root 已清理。
- Schema：`shasum -a 256 ContextSchema.sq` 当前与 CM5/CT26 基线均为 **`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`**，未漂移。
- Source/XML、Schema 与日志时间戳均已现场核对；WebDAV 两个新增测试文件的源时间早于本轮实跑，XML/日志晚于源文件，不是仅凭旧文件计数。
- 当前工作区仍有 29 个已跟踪修改与未跟踪新增生产/测试/文档文件，属用户既有工作；未清理、未提交、未改写。`AGENTS.md`、CM5/CM6 范围文档的既有修改不由本轮回退。

### 2.3 明确非阻断残余风险

- Core 对 `Storage` 正常路径会在 finally 再调用一次 `InputStream.close()`。API 契约是“幂等防御”，真实 `Files.newInputStream` 与现有测试流可安全重复关闭；Storage 仍是资源释放的第一 owner。此重复调用不构成数据或资源阻断，但将来若允许任意非幂等第三方流，宜在独立 API 清理任务中收窄为显式交接标志。
- CT27 Backup 后端范围是 local-atomic only，并非 WebDAV 原子备份能力已实现。WebDAV 的真实发布能力仍有协议级不确定性；不得把这一降级扩大成“WebDAV 通用能力已证明”。
- 本裁定没有验证 CT28/CT29、未做 Git 提交、未宣称生产部署、未验证长期 cron 的跨进程持久调度。

### 2.4 后续授权边界

- CT27 可标记 DONE；CM6 保持 ACTIVE，不能因本专题通过自动开始下一专题。
- CT28/CT29 继续 LOCKED；开始任一专题都需要新的明确授权、独立任务书、写集合与复核门禁。
- 不允许用本 R2 的测试结果替代未来的真实模型、部署或端到端用户验收。

## 本次修改（2026-11-10，补充驳回越界“完整设计”）

- `Backup_完整设计.md` 不是本 R1 的修订响应：CT27 任务书及 B1/B2 委派单未修订，本文件 R1-1～R1-6 仍全部有效。
- 文档声称“用户已批准”私有任务库、durable outbox、StartupRecovery、Rust Host、TUI、Web、16 消息等，但当前对话与 CM6 授权均无此记录；不得伪造批准来源或借此改变 CM6 范围。这些扩展与专题 9 的明确非 MVP 冲突，**不准纳入 CT27**。
- 文档建议新建独立源连接且“不占业务 write slot”，未经批准改变已核对的执行器并发边界，不予采用。不得把持久化复杂化或 Host/UI 方案当成 Xerial/发布/写集合修正。
- `Backup_完整设计.md` 仅作为未授权草稿保留，**不能作为实现契约、委派依据或验收证据**。Lead 必须回到原 CT27 文档逐项回应 R1；CT27/CT28/CT29 继续 LOCKED，无生产/测试/Git 修改。

## 本次修改（2026-11-10，首轮方案 R1）

- 核对 CT27 任务书、B1/B2 委派单、CM6 范围及技术实现设计 §9；**不通过，CT27 尚不得实施**。以下 R1 均为可定点实施的修正，不沿用 CT26 已关闭历史问题。
- 已用项目缓存的 `org.xerial:sqlite-jdbc:3.49.1.0` 执行 `javap`，确认真实 API；现有执行器的准入/关闭协议可以复用，但 native Backup 目标生命周期、VFS 发布、URI 与写集合必须改正。
- 本轮只读生产代码并新增本复核文档；无生产/测试/构建修改，无 Git 写操作，真实模型 `not-run`。

**裁定：IN_REVIEW / R1；CM5 保持 DONE，CT28/CT29 继续 LOCKED。**

## 1. R1-1 · Xerial API 与目标连接生命周期不符（阻断）

### 实测

当前 `gradle/libs.versions.toml` 已锁定 `sqlite-jdbc=3.49.1.0`，且 `context/persistence-sqldelight` 已通过 `implementation(libs.sqlite.jdbc)` 使用同一驱动。方案“新依赖由 runtime 使用”是错误前提，无需也不能建立第二套驱动版本。

缓存 JAR 的公开签名为：

```text
org.sqlite.SQLiteConnection.getDatabase(): org.sqlite.core.DB
org.sqlite.core.DB.backup(String dbName, String destFileName,
                          DB.ProgressObserver observer): int
org.sqlite.core.DB.backup(String dbName, String destFileName,
                          DB.ProgressObserver observer,
                          int pagesPerStep, int sleepMillis, int nStep): int
```

没有任务书所称 `org.sqlite.Database` / `org.sqlite.Backup`，也没有公开 `SQLiteConnection.database` 原生只读 backup handle 或 Java 侧 `step/finish/dispose` 生命周期。目标库由 Xerial backup 按 `destFileName` 打开；不能先用普通 JDBC 打开同一目标再要求 backup 复用那个连接。

### 必须修正

- 以实际 `SQLiteConnection` → `getDatabase()` → `DB.backup(...)` 为适配边界，Core/API 仍不得暴露 JDBC/Xerial 类型。不要伪造 handle API 或在 runtime 新增 Xerial 依赖。
- 目标文件由 backup API 创建；独立校验连接必须在 `backup` 返回、源回调退出并关闭对应资源后打开。不能在 backup 运行期间用另一个普通 JDBC Connection 打开/占用目标。
- 默认进度回调每 `pagesPerStep` 触发；显式 `nStep` 是分段调用控制，不等于每页回调。须使用可控的小页批、进度回调中的取消检查及异常传播实现可取消执行，不能把 `nStep` 当“每页通知”。业务异常须穿过 callback 原样保留，JDBC 错误映射既有 persistence 错误。
- 完成、失败、取消都关闭本次专用资源并尽力删除自己创建的临时目标；不得删除其他文件。进度回调若偶发不触发，必须有明确的停止/等待策略，不能声称 instant cancellation。
- 当前调用是同步 native 方法且占固定写 Slot 线程；不得以 `withContext` 或 `runBlocking` 造成回调重入/关闭自等待。保留 `CancellationException`，业务失败映射 `UNAVAILABLE`。
- 增加真实 API 的最小契约测试，禁止 mock `Backup` 冒充 Xerial 适配验收。

## 2. R1-2 · 写 Slot 可复用，但并发承诺和发布/关闭边界需写准确（阻断）

`ContextDatabaseExecutor` 的 `enter → protocolLock 内 closed 检查 + inFlight++`、`writeSemaphore.withPermit`、关闭停止准入并 drain 的结构充分保护专用 backup 调用；`withUserCallback` 也可复用嵌套/回调内 close 拒绝。无需第二套 Semaphore 或生命周期协议。

但必须明确：

1. backup 持有唯一写 Slot 时**与业务写串行**；读 Slot 可继续提供一致 WAL 快照，业务写会等待或排队，不能承诺完全读写都不暂停。测试同时断言双方成功与写被 backup 占用时的串行边界。
2. `inFlight` 覆盖排队阶段，关闭会等待整个 backup 进入/退出，不能增加绕过 `enter` 的原生调用。
3. SQLite backup 在线一致快照不等于某个业务跨表事务的业务快照边界；测试要断言“同一次已提交事务的跨表状态同时出现”，不能只比较文件大小。
4. 本地临时目录必须来自受控临时根，真实路径校验普通目录/文件且不在任何 Local Mount 内。方案“用配置父目录外”没有现成 `backupMount` 配置可引用，应改为 `databasePath.parent` 之外且路径隔离已验证的独立临时根。
5. 唯一目标创建后，不得跟随符号链接或复用调用者预置目标；随机名称、父目录身份/目标类型检查与清理边界须覆盖外部替换，不能仅靠 UUID 名称。
6. `publishNew` 返回后才是“已发布物理文件”；随后 Node/Event 状态失败是 `STATE_ERROR + PARTIAL`，文件保留、Node/Event 不假装成功。Runtime 应保留实际 VFS effect，映射 `PUBLISH_FAILED`，不宣称发布可回滚。
7. post-publish quick_check 失败时当前 run 失败、**正式文件保留**并返回可定位 URI/失败码，不删除可能已完整发布的文件。状态 lastResult 仅在 pre/post 校验均成功时更新；lastFailureCode 记录实际失败，普通失败不把 worker 永久置 FAILED。

## 3. R1-3 · 固定 URI 与唯一不覆盖备份冲突；WebDAV 被无依据禁用（阻断）

技术设计 §9.1～9.2 要求唯一时间标识、必要时序号、不覆盖旧备份，并明确 Local FS/WebDAV 可选。任务书却固定 `alcyone://backups/context-backup.db` 且 WebDAV `publishNew=false`，导致第一次成功后永远不能再备份，并违反既定存储范围。

必须改为：

- 每次生成唯一目标，例如 `alcyone://memory/backups/context-YYYYMMDD-HHmmss.SSS-<短随机>.db`；目录、命名空间与时间格式按现有 VFS 配置及 Asia/Shanghai 规则落实。测试固定 Clock 下多个 run 仍得到不同 URI，不覆盖已有目标。
- `backupMount="/backups"` 与示例 `alcyone://memory/backups/` 不可同时含糊存在。建议沿用设计：`context.backup.directory` 为完整逻辑目录 URI，默认 `alcyone://memory/backups/`；Manual 只有显式配置或默认目录确实可路由时执行，缺失映射明确 `INVALID_CONFIG`，不隐式新增 mount。
- `enabled` 若不需要请移除；`cron != null` 即自动启用，无 cron 为 manual-only，避免 enabled/cron 语义冲突。
- WebDAV 要么实现满足完整创建边界的 `publishNew`，要么由架构明确将 CT27 自动/手动 Backup 限为 `atomicPublishNew=true` 后端并同步修订 CM6/技术设计，不能静默偏离。当前没有用户授权缩小 WebDAV 范围，**本 R1 要求实现 WebDAV 安全 publishNew 或提交独立后端降级裁定，不得直接放行 false**。
- 若 WebDAV 无法证明原子 rename/完整提交，不得以普通 PUT 直接写正式 URI。可验证的唯一新对象 + `ifNotExists` 仅在完整写入成功后才可被视为已发布；中途失败留下可识别临时对象并清理，不得留下正式半文件。需真实 WebDAV 集成证明，不得仅靠 fake capabilities。

## 4. R1-4 · `publishNew` 契约、限额及 InputStream 所有权不完整（阻断）

当前 `Storage` 已是 `vfs/core` 的 Port；`LocalFsStorage`、`WebDavStorage` 都在 `vfs/storage`，不是 `storage-local-fs` / `storage-webdav`。必须采用真实模块路径。

- `InputStream` 的所有权需唯一：建议 **VFS/Storage 实现负责在成功、失败、取消时 close 传入流**，调用方不得再次关闭；方案原文“不归调用方关闭”若意指谁都不关是资源泄漏。`openStream` 的 result 由 Runtime 在另一阶段负责 close，二者不能混淆。
- Local FS 使用 `CREATE_NEW` 临时文件、分块复制、文件 `force`、同目录 `ATOMIC_MOVE` 且不带 `REPLACE_EXISTING`。目录持久化与 move 后故障按 UNKNOWN/PARTIAL，不伪造已回滚。`ATOMIC_MOVE` 不支持必须 UNSUPPORTED，不得降级普通 move。
- Core 应先走既有 route/capability/guard，再创建父目录和 storage 临时文件；Node 预检查不能代替物理 `CREATE_NEW`/不覆盖 move 的竞态兜底。
- 默认写限额 16 MiB 会成为合法大库的硬失败，不能“不得截断”就忽略。`publishNew` 必须增加显式流上限契约（默认沿用配置安全上限；Backup 根据已生成快照 `Files.size` 先做校验，读取仍累计防守），并在 Backup 配置中给出**有界** `maxBytes`；超限在发布前具名失败，不得提供无限流绕过全局限额。post-read 同样配置有界 streamTotalLimit 或在启动时拒绝无法读取已允许快照的配置。
- Backup 应使用 `Files.size(snapshot)` 作预期字节数，完整复制后校验相等，覆盖短读/额外字节；post quick_check 还要确认目标数据库页数/大小与发布前一致。哈希可作为诊断，不替代 SQLite 校验。
- 备份期间 VFS `StateBoundary` 会序列化状态操作，但 Storage I/O 不包 SQLite 事务；不得在 publishNew 内把整次流复制放进 VFS 状态事务。
- WebDAV 关闭/临时 stream 资源要沿用 `StorageAdapter` 活动流追踪与 Runtime close 语义。

## 5. R1-5 · B1/B2 写集合缺失并含不存在的模块（阻断）

当前写集合虽大体按 Context/VFS 分层互斥，但缺少真实集成所需文件，且命令引用不存在模块。修订后必须逐文件/路径列清并再次核对互斥。

建议：

- **B1**：`vfs/api/**`、`vfs/core/**`、`vfs/storage/**`、`vfs/runtime/**` 中能力快照接线及测试；必要时 `integration-tests/vfs/**` 的真实 Local FS/WebDAV 集成测试。禁止把 Storage 拆成不存在的 `storage-local-fs` / `storage-webdav`。
- **B2**：`context/api/**`、`context/persistence-sqldelight/**`、`context/runtime/**`；真实公共入口测试放 `integration-tests/context/**`。若公共 API 的 Backup 配置不依赖 runtime 类型，配置归属需一致。
- 两份委派单只能由一方拥有 `integration-tests/vfs` / `integration-tests/context` 对应路径；Lead 自己负责 CM6 文档，不交给两个 Worker 并发写。
- persistence 已有 Xerial 依赖，无需修改 version catalog；若确需 test 依赖，明确唯一 owner。
- B2 不得在 B1 API 未落地时长期维持“编译契约”假接口。顺序应为 B1 落地最小 `Vfs.publishNew` 契约并交 Lead 核对 → B2 可并行 persistence/API 独立部分 → B1 合入工作区后 B2 完成 Runtime 集成；禁止伪造发布成功。

## 6. R1-6 · API/状态/关闭顺序需与现有生命周期一致（必须修正）

- `BackupWorkerState.FAILED` 应定义：普通一次 backup 失败仍回 `IDLE`，只记录 lastFailureCode；`FAILED` 仅用于 service 自身不可继续的结构故障，避免一次 503/IO 错误永久禁用后续 Manual/Cron。
- `BackupStatus` 需包含当前/最近 URI、最近完成时间、最近失败阶段（snapshot/pre-check/publish/post-check），不得只一个模糊 code。返回不可变快照，不做 I/O。
- `ContextBackupErrorCode` 需保留实际持久化/lifecycle 语义：NOT_RUNNING 与 CLOSED/NOT_STARTED 不应互相吞掉；超时/取消继续 CE。默认值只对第三方实现 `UNAVAILABLE`，生产无备份配置时同码；Cron 配置而 service 缺失为启动失败。
- 现有 `AlcyoneContextImpl.shutdownTimeout` **只是观察期，不是强杀期限**，`runShutdown` 在 NonCancellable 中等待真实 drain。任务书“shutdownTimeout 内收尾”须改成：观察超时只记录，关闭真实等待 backup 收尾；或先另案改变整个 Runtime 关闭契约，不能在 Backup 一处宣称硬超时。
- 关闭时先在同一状态锁内 STOPPING + Backup beginClosing，再停止 Cron/封闭 Manual，等待已登记任务。backup 若使用 VFS 和 DB，必须在二者释放前完成；停止其调度不应无谓等待 Dreaming/Map 等后续 worker。所有半构造 service/pipeline 必须登记并逆序清理。
- 发布成功、post-check 进行中收到 close：等待其完成后 close，或取消并返回失败但保留已发布文件；不能删除/回滚正式文件，不能让 close 提前释放 VFS/DB。
- `runBackup` 调用方取消与 Runtime 拥有任务的取消要分开：前者不擅自取消另一个共享调用；service 若有 owner，需要 NonCancellable 清理临时文件并登记真实结束。

## 7. 修正后建议分批

1. **方案修订（仅文档）**：落实 R1-1～R1-6，附真实 Xerial 签名、URI/目录配置、WebDAV 完成边界、明确写集合。
2. **B1 VFS publishNew**：真实 Local FS + WebDAV 集成、能力快照、流上限/所有权、Node+Event/effect；交付真实模块测试与 XML 计数。
3. **B2 Backup**：persistence 真实 Online Backup → service quick_check → Runtime/config/cron/lifecycle → 公共集成；至少真实 SQLite 并发写/读、失败/取消/关闭、pre/post-check、连续多个唯一 URI、临时清理。
4. **Lead 集成 / Architect 独立复核**：`./scripts/dev gradle ...` 或 `./scripts/check` 使用仓库工具链；14 模块行首 `@Test` 与 XML 相等、Schema SHA 不变、VFS 既有基线全绿、无敏感日志、真实 WebDAV 集成不降级。

**在这些 R1 修订并获 Architect 裁定前，不批准 Worker 修改生产/测试代码。**
