# CT27 自动备份：R1 修正实施契约

> 本文件此前夹带未经 CM6 授权的 Host、前端、TUI、outbox 等扩展，不能作为实现依据。
> CT27 只实现既有任务书的 SQLite Online Backup、VFS 发布、WebDAV、Cron 与关闭生命周期。

1. 使用真实 `SQLiteConnection.getDatabase().backup(...)`，API/Core 不暴露 JDBC；目标由 backup 创建，校验连接在 backup 返回后打开。
2. 默认 `alcyone://memory/backups/`，每次以 Asia/Shanghai 时间戳和短随机生成唯一 URI；无路由 Mount 报 `INVALID_CONFIG`，不隐式新增 Mount。
3. Local FS 与 WebDAV 都先完整写临时对象，再执行目标全新的 rename。WebDAV 要求条件创建及 rename 能力，不以普通 PUT 冒充完整发布。
4. `publishNew` 分块复制，执行 `maxBytes` 与预期字节数校验；Storage 负责关闭输入流。发布后的状态失败为 `STATE_ERROR + PARTIAL`，不回删正式文件。
5. 临时 SQLite 根默认 `<databasePath.parent>/backups-tmp`，须为已存在普通目录、通过路径隔离检查且不在任何 Local Mount 内；快照以 `CREATE_NEW` 认领。
6. `BackupStatus` 记录 URI、完成时间与失败阶段；普通失败回 IDLE。关闭先封闭 Backup，等待 snapshot/publish/post-check 结束，再释放 VFS 与 DB。

以下为未授权历史草稿，仅保留供审阅，不得作为 CT27 实施或验收依据。

**日期**：2026-11-10  
**基线**：`3c7d5c319aaaacb77bf34effe280fbda84900ba3`（CT26 / CM5 DONE）  
**状态**：用户已批准方向；本文为实现契约，不表示实现或验收已完成  
**范围**：自动备份、本地快照、LocalFS 快照发布、可选 WebDAV 同步、本地 HTTP Host、静态前端、TUI、崩溃对账及可复现验收  
**非目标**：自动导入 / 恢复覆盖运行库、多设备数据库合并、对象级增量同步、加密、数据库内部加密、业务 Schema 变更

### 0.1 与现有正式契约的偏差（实施前必须同步修订）

本设计依据用户本次对持久化任务、16 消息、Host / TUI / 前端的明确要求，**扩展**而非复述现有 CM6 MVP。当前仓库尚无 DatabaseBackupService、Rust Host、TUI、Web backup 页面或 BridgeDispatcher；这些均标为新增，不得在实现计划中写成“修改现有文件”。本次批准尚未自动修改 [CM6 范围](../../../milestones/cm6/CM6_范围与依赖方案.md) 或专题 9 的冻结文字。

| 现有契约 | 本次批准的扩展 | 实施约束 |
| --- | --- | --- |
| 专题 9 不建设备份记录数据库、持久重试状态、StartupRecovery | 独立私有 `backup-state` SQLite 与 durable outbox | 只存任务元数据；不碰业务六表 / user_version；先同步修订 CM6 / 专题 9，记录批准来源 |
| Manual + 可选 Cron，单进程不并行 | 同一约束，增加事件触发与可重试 pending | 不引入第二 Worker；默认 Cron `0 3 * * *`、Asia/Shanghai、默认关闭 |
| `DatabaseBackupService.backup()` 返回 VFS 已发布快照，不承诺云同步 | 本地快照与 WEB_DAV 远端校验分为两个事实 | 原有 API 语义保持；新增 task / sync 状态表示 pending 与远端完成 |
| VFS 发布完成即返回 BackupResult | 物理快照发布、VFS 元数据提交、远端校验分别有恢复记录 | 无跨 SQLite / Storage 伪回滚；发布成功但 Node 提交失败返回 PARTIAL 并可重查 |
| 不含统一面板 / 自动恢复 | 新增操作 UI，只操作任务，不导入数据库 | `task.import` / `sync.applyRemote` 是固定拒绝的预留协议 |
| 单 JVM / 单 Runtime | 私有状态库文件锁只作为防误开护栏 | 不把文件锁当多实例协调；第二 Runtime 明确拒绝，仍不保证跨实例锁分配 |

不启用的事件不引入新机制：无自动 sync 时不需要 REMOTE_CHANGED；VFS 挂载支持热插拔时才启用 MOUNTED_CHANGED；没有“发布前重新生成快照”需求时 REAPPLY 仅保留枚举与拒绝路径，不制造假阶段。未实现 capability 必须明确 false。

## 1. 结论与不可协商边界

采用“**同一持久化任务状态机 + SQLite durable outbox + 不可变内容寻址快照 + 单 Worker + 可重放上传**”。本地快照成功与远端发布成功是两个独立事实。一次远端失败不能使本地备份消失，也不能令任务成为不可恢复的终态。

1. 用户唯一业务操作是 `BackupRun`。开始、取消、重新运行、改配置、刷新状态、更新检查都必须得到同一个 `TaskSnapshot`。
2. **WEB_DAV 模式下本地备份不是终态**。已上传并校验后才结束本轮；后端不可用时为 `DEGRADED_PENDING`，不是 FAILED，也不是 SUCCESS。LOCAL 模式的完整终态是本地快照发布成功，绝不显示“已同步”。
3. 只有无本地改动且有已同步快照的定时 tick 可产生 `SKIPPED`；手动请求、`MOUNTED_CHANGED`、`REMOTE_CHANGED` 或真正的本地变更不可被 `SKIPPED` 静默吞掉。MVP 已 ingest 即产生新快照，不承诺按 Turn ID 推导“无本地改动”，因此初始 tick 不猜测 SKIPPED。
4. 远端只用不可变对象 `snapshots/<sha256>.sqlite3`。禁止覆盖 `context.db`、`latest.db` 或已有 digest 对象。全局 latest 只是可选索引，不是备份成功条件。
5. 普通 VFS `rename`、`Storage.safeReplace`、`CREATE_NEW` 的“检查后写”和“先删后传”均不自动满足原子发布。条件不支持就保留 pending；绝不静默降级覆盖。
6. SQLite 数据文件、WAL、VFS 状态数据库必须保持本地。任何时刻都不得把 JDBC、WAL、事务或 SQLite checkpoint 指向 WebDAV。
7. 任务不持锁休眠，不占 SQLite / VFS 边界等待网络，不阻塞 ingest 或 close。每个阻塞步骤都可取消；物理步骤不可中断时先断绝逻辑所有权，再在受控 IO 中收尾。
8. 不自动改业务表。`PRAGMA user_version` 与 CT01 六表五索引校验原样冻结。
9. 不删除新代码来规避测试，不用 `task.status` 推断远端是否成功，不把 `CANCELLED` 当成执行完毕。
10. TUI 不实现另一个同步状态机。Host 与 TUI 共用 runtime Port；Web 不直接操作 JDBC 或 OpenDAL。

## 2. 已核实的仓库接缝

| 接缝 | 当前事实 | CT27 用法 |
| --- | --- | --- |
| Context 持久化 | `ContextDatabaseExecutor` 的 write / read 准入受关闭协议保护；`ContextSlot` 连接代理禁止泄露原生连接 | 新增受控 `onlineBackup` Port，实现只在持久化模块接触 xerial；不新建旁路业务连接 |
| 在线备份 | xerial 3.49.1.0 的 `SQLiteConnection.database.backup(...)` 使用 SQLite online backup，支持剩余页 / 总页回调 | 持久化模块新建受控只读源连接，走同一 schema 校验、关闭准入与路径约束；不占业务 write slot，不 checkpoint 源库、不拷贝 WAL |
| 快照前刷 WAL | 当前 `PASSIVE` checkpoint 不是发布屏障 | 不将它当作远端提交；本地完整 SQLite 文件验证通过后才记录 digest |
| VFS | `Vfs` 有完整读写 / 挂载 / 流接口；LocalFS 与 WebDAV 都经 OpenDAL Storage | 复用 VFS 认证、URI、路径、流和错误边界，只新增内部不可变发布原语 |
| LocalFS | 现有 safeReplace 是业务文件替换语义 | 新增同挂载点排他 staging + 校验 + `link` 的不可变发布；不借助 `REPLACE_EXISTING` |
| WebDAV | `writeWithIfNotExists` 已用于条件创建；普通 rename 声明 `atomicReplace=false` | staging 条件创建 + `MOVE Overwrite:F`；若服务器不支持不可变条件发布，返回 `PENDING` |
| Host | 当前仓库无此模块 | 新增 `tools/backup-host` Rust Host，以 `/context` 为唯一可写挂载；拒绝任意本机目录穿越 |
| 前端 | 当前仓库无此模块 | 新增 `frontend/web-backup-demo`；以 `/context` 为默认 VFS 备份根，启动时下发根 URI |
| TUI | 当前仓库无此模块 | 新增 `tools/backup-tui`；使用同一 runtime Port，不拼 HTTP，不远程执行命令 |
| 关闭 | 现有工厂与执行器逆序关闭资源 | Backup owner 先封闭任务、登记退出，再关 VFS / SQLite；等待未完成 IO 的受控结果 |

## 3. 配置、挂载与路径

```kotlin
data class BackupConfig(
    val autoEnabled: Boolean = false,
    val cron: String = "0 3 * * *",         // 现有 UNIX 五字段 Cron
    val timezone: String = "Asia/Shanghai",
    val syncMode: SyncMode = SyncMode.LOCAL, // LOCAL 也生成快照，但不远端发布
    val webDav: WebDavConfig? = null,
    val remoteRoot: String = "alcyone/backups",
    val checkForUpdates: Boolean = false,
)

enum class SyncMode { LOCAL, WEB_DAV }
enum class BackupEvent {
    START, INTERVAL, MANUAL, RETRY, STARTUP_RECOVERY,
    MOUNTED_CHANGED, REMOTE_CHANGED, CANCEL, CONFIG_RELOAD,
}
```

`INTERVAL` 是调度层唤醒事件，默认由 `CronSchedule` 产生；若部署选择固定间隔，则实现独立的 `intervalSeconds` 可选项，不与 cron 同时启用。唤醒原因保留 INTERVAL，即使底层触发器为 Cron。

Cron 直接复用已验证的 `CronBackupSchedule` 五字段解析器，不新增 third-party Cron。若该解析器使用固定时区，则 `timezone` 必须等于其实际时区；实现前先为显式 Asia/Shanghai 补齐解析 / 测试，不允许只在配置中写一个未生效字段。

- `LOCAL` 是本地快照模式，不声称有远端副本。`WEB_DAV` 才需要 endpoint、root、凭据。
- WebDAV root 与相对对象路径由 Storage 校验，不接受 `..`、反斜杠歧义、控制字符或非 UTF-8 不安全输入。endpoint / username / password 不出现在事件、配置回显、日志或异常中。
- 本地 VFS 备份根必须是挂载内目录，Host 固定为 `vfs://context/backup/`。初始化创建 `staging/`、`snapshots/`、`manifests/`。
- Host 私有 journal 目录为 `<host-data>/backup-journal/v1/`，state 为 `<host-data>/backup-state/v1/state.sqlite3`。二者不属于 WebDAV 挂载。Embedded SDK 由宿主通过 `backupStateDirectory` 显式提供二者，不猜用户目录。
- 远端对象路径固定：
  - `snapshots/<lowercase-sha256>.sqlite3`：不可变完整数据库；
  - `manifests/<taskId>.json`：每个任务最多发布一次；必须包含任务身份、远端 snapshot digest / size、远端 URI 和完成状态；自身不可被更新；不含敏感内容；
  - 不实现 latest 覆盖指针。恢复 / 检查由任务状态、manifest 列举和对象 digest 决定。

`remoteRoot` 更新后创建新 sync generation；旧 generation 的 pending 不得自动上传到新后端。默认保留并显式提示“有旧目标待传”。本期只提供重试旧目标；若需放弃旧目标，须下载已验证快照并由受控运维操作清除，不提供无确认的 UI 删除。

## 4. 数据边界与 durable outbox

### 4.1 私有状态库

状态库启用 WAL、`foreign_keys=ON`、`busy_timeout`，所有迁移显式版本化。其 Schema 独立于业务数据库，不写入业务 `user_version`。

```sql
CREATE TABLE backup_task (
    task_id TEXT PRIMARY KEY,
    generation INTEGER NOT NULL,
    origin TEXT NOT NULL,
    status TEXT NOT NULL,
    phase TEXT NOT NULL,
    trigger_reason TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    started_at TEXT,
    finished_at TEXT,
    generation_id TEXT,
    snapshot_id TEXT,
    local_digest TEXT,
    local_size INTEGER,
    remote_generation TEXT,
    remote_uri TEXT,
    remote_digest TEXT,
    remote_effect TEXT NOT NULL DEFAULT 'NONE',
    attempt INTEGER NOT NULL DEFAULT 0,
    next_retry_at TEXT,
    error_code TEXT,
    cancel_requested INTEGER NOT NULL DEFAULT 0,
    check_not_before TEXT NOT NULL
);

CREATE TABLE backup_event (
    event_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES backup_task(task_id),
    generation INTEGER NOT NULL,
    sequence INTEGER NOT NULL,
    event TEXT NOT NULL,
    phase TEXT NOT NULL,
    attempt INTEGER NOT NULL,
    at TEXT NOT NULL,
    details_json TEXT NOT NULL,
    UNIQUE(task_id, sequence)
);

CREATE TABLE backup_outbox (
    snapshot_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES backup_task(task_id),
    generation INTEGER NOT NULL,
    journal_path TEXT NOT NULL UNIQUE,
    expected_digest TEXT,
    expected_size INTEGER,
    remote_generation TEXT NOT NULL,
    remote_uri TEXT,
    remote_effect TEXT NOT NULL DEFAULT 'NONE',
    state TEXT NOT NULL, -- CAPTURING / READY / UPLOADING / UPLOADED / ABORTED
    acknowledged INTEGER NOT NULL DEFAULT 0,
    lease_owner TEXT,
    lease_epoch INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_retry_at TEXT,
    last_error_code TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE backup_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
```

`backup_meta` 保存 `schema_version`、`next_task_generation`、`next_worker_epoch`、`sync_generation`、`clock_observation`、`last_mount_signature`。不需要的字段以 `null` 保存，不用空字符串代替事实。

### 4.2 持久化与展示分离

- `backup_event` 是审计日志，可按已确认的策略清理，不能承担恢复状态。
- `backup_task` / `backup_outbox` / `backup_meta` 是恢复事实，不受普通日志保留策略影响。
- `TaskSnapshot.recentEvents` 只是日志的 UI DTO，不由前端反向重建任务。每任务 `sequence` 自 1 严格递增，事件产生与状态更新同事务提交；提交后才通知进程内 subscribers。`eventId` 为独立 UUID，不由客户端生成。
- 任务每个持久化迁移使用事务及条件 `WHERE generation=? AND status/phase=?`。只有返回受影响行数为 1 才推进内存状态。

### 4.3 Journal 的精确保留顺序

1. 单 Worker 先写任务和 `outbox(CAPTURING, journal_path=<host>/.../<snapshotId>.sqlite3)` 并 COMMIT。
2. 在同一固定路径执行 SQLite online backup。snapshotId 为 UUID，内容 digest 之后计算；不得每轮随机换路径留下孤儿。
3. 目标完成、来源快照句柄关闭后，以独立只读 SQLite 连接执行 `PRAGMA integrity_check`、`PRAGMA user_version`、六表五索引校验。所有结果与基线一致才继续。
4. 流式计算 SHA-256 和字节数。同事务更新 outbox 为 `READY`、写入 digest / size，并更新任务本地快照 identity。
5. 上传以该 digest 为唯一目标。上传完成必须回读并重新计算 digest / size，再标记 outbox `UPLOADED`。
6. 远端回读通过后，先在事务里保存 `remote_effect=PUBLISHED`、任务 SUCCEEDED，并将 outbox 标记 `UPLOADED / acknowledged=1`。此 COMMIT 后才可删除 journal。acknowledged 行不再是待传项，但删除它与删除 journal 的任意崩溃顺序都可恢复：已完成且无 journal 时只清元数据；已有 journal 时先重读远端确认再清，绝不重新备份。不可出现“待传记录已删但任务仍等待远端”的状态。
7. 崩溃恢复只清理“无 outbox、无运行中 lease、超过宽限期”的本服务 journal。绝不用“目录为空”或“任务显示失败”删除待传文件。

远端已确认后才允许 pending 出队；如果完成确认已返回但进程在删 outbox 前崩溃，重传同一 digest 是幂等读校验，不生成第二份远端内容。

## 5. 状态、阶段与事件

```text
TaskStatus: CREATED | RUNNING | CANCELLING | CANCELLED |
            SUCCEEDED | FAILED | SKIPPED | DEGRADED_PENDING

TaskPhase: IDLE | PREFLIGHT | CAPTURE | VERIFY_LOCAL | PUBLISH |
           VERIFY_REMOTE | REAPPLY | RECONCILE | CANCELLED |
           SUCCEEDED | FAILED | SKIPPED | DEGRADED_PENDING
```

| 状态 | 语义 | 允许离开 |
| --- | --- | --- |
| CREATED | 已持久化，尚无快照 | RUNNING / CANCELLED |
| RUNNING | 任一活动 phase；进度仅在 RUNNING 时有意义 | CANCELLING / SUCCEEDED / FAILED / DEGRADED_PENDING |
| CANCELLING | 已接受取消，物理步骤可能仍在退出 | CANCELLED / FAILED（仅清理失败） |
| CANCELLED | 当前 generation 的逻辑任务停止，本地 pending 仍可恢复；不是远端完成 | 新任务 / RETRY（保留 pending 时） |
| SUCCEEDED | 请求范围已完成；WEB_DAV 必须远端校验通过 | 新任务 |
| FAILED | 配置、权限、完整性等当前目标无法继续；可重试，保留 pending | RETRY / 新任务 / CANCELLED |
| SKIPPED | 仅明确无工作可做 | 新任务 |
| DEGRADED_PENDING | 本地快照有效，远端暂不可用或待传 | RETRY / 新任务 / CANCELLED |

`REAPPLY` 不是把旧 SQLite 数据库覆盖到业务库，而是“在 publish 前又发现源数据 / 挂载变化，废弃本轮候选并创建新快照”。它只保留协议枚举；默认实现不根据每次 ingest 强行重拍正在验证的快照。后续若开启 latest-only 策略，才允许在本地快照发布后排队新任务；不能回滚远端已完成的其他任务。

`RECONCILE` 只用于启动时读取持久化状态、校验 journal / outbox、查询已发布 digest 和处理未知上传结果，不导入远端数据库。恢复中的任务保持 `RUNNING + RECONCILE`，临时未知结果不新建人工任务；不可恢复时进入 DEGRADED_PENDING 并保留原 outbox。

标准事件：`created, request, started, phase_changed, progress, retry_scheduled, waiting_mount, degraded_pending, reapply_requested, cancel_requested, cancelled, completed, failed, skipped, config_changed, mount_changed, remote_changed, recovery_started, recovery_finished`。`request` 在接受 start / retry / sync.retry 时落库，保存规范化 trigger 与参数摘要；并发拒绝不产生第二个工作请求。

每个事件至少含 `taskId / generation / workerEpoch / sequence / phase / status / attempt / at`。取消事件不能被之后旧 generation 的 completed 覆盖。

## 6. 单 Worker、执行权与关闭

### 6.1 调度

`BackupTask`（API DTO）、`BackupTaskService`（用例入口）、`BackupCoordinator`（owner）与 `BackupWorker`（执行）职责分离。

- 一个 runtime 最多一个活动 worker；并发触发合并为一个 pending reason 集合，**不是**简单丢弃所有重复信号。
- 手动运行时若已有活动 run，返回带 `ALREADY_RUNNING` 和当前 snapshot 的冲突；不排队悄悄生成第二份手动快照。
- 定时 tick 在活动任务期间只设 `timerPending=true`，当前 run 结束后重新判断，不使用固定间隔强行并发。
- `MANUAL / MOUNTED_CHANGED / REMOTE_CHANGED / STARTUP_RECOVERY` 标记 dirty，不能因本轮成功被无条件清除。只有已成功覆盖其目标后，才逐原因清除；不能保存一个笼统 dirty 位后一次成功全清。
- `config` 更新不可变；活动 run 持旧配置快照。新配置使 generation 失效并请求合作取消，之后重新 preflight。

### 6.2 Epoch 与物理 IO

内存 `workerId` 为 UUID，持久化 `generation` 与 `workerEpoch` 均在同一状态库事务中单调分配，重启继续递增。每次运行绑定 `(taskId, generation, workerEpoch)`；事件、DB 迁移、远端结果都校验三元组。启动时把旧的 RUNNING / CANCELLING / UPLOADING lease 标为 UNKNOWN_PENDING_RECONCILE，不把进程死亡自动等同于远端失败。`UNKNOWN_PENDING_RECONCILE` 是内部恢复标记，对外映射为 `RUNNING + RECONCILE`。

取消分两段：

1. 逻辑取消：在协议锁内置 cancelRequested，封闭后续阶段，发 cancelRequested。
2. 物理退出：取消协程 / 关闭本次流 / 等待 JDBC 在线备份返回。旧 worker 即使晚返回，也不能推进 DB、删除 pending 或发 completed。

取消只取消当前 generation 的继续推进；CANCELLED 是任务控制终态，关联的 READY / 上传中 outbox 保持未确认状态，恢复事实与 UI 状态分开。`task.retry` / `sync.retry` 创建新 generation 并唤醒它，普通 `task.delete` 不允许绕过保留规则。启动恢复按 snapshotId 确定性选取最早 pending，逐个处理；一个 runtime 仍只有一个 Worker，不存在并行重试。

`closeAsync`：封闭新 run 与 retry → 请求 worker 取消 → 在配置的 shutdownGracePeriod 内等待已登记 worker / leased outbox → 关闭 Backup resources → 交还 Context / VFS / SQLite 的既有逆序关闭。清理主体由独立 close job 所有，调用方取消只停止该调用方等待，不取消真实清理。

存储 Port 必须有确定性可中断上传、整体 deadline 和 socket timeout，不能以“底层可能不响应取消”为由无限等待。默认 grace period 为 10 秒；到期将 worker 标为 `CLOSING_TIMEOUT`，保留 outbox / journal，停止接纳，记录资源未安全关闭并向上返回错误。不得仍报 clean close，也不得在后台不受控地访问已关闭 VFS。`CLOSING_TIMEOUT` 的进程退出必须由 Host 决策，不能用 finally 无条件释放 JDBC / 文件描述符后继续假装正常。测试必须覆盖这条非干净路径。

### 6.3 时间、重试与测试

所有延迟、超时和间隔走可取消的 `delay`。重试持久化 `attempt / nextRetryAt`，不在内存里保存唯一计时器。

生产默认 `1s, 2s, 4s, 8s, 16s, 30s`，capped exponential backoff；同轮最多 6 次，加入 0～20% 可注入 jitter。达到上限进入 DEGRADED_PENDING，不丢 journal；`retry` 开始新 attempt。`retry_scheduled` 与等待挂起都在同一个 cancellable delay 的上下游；close / 新 retry 会取消旧 Job 并 join。认证失败、无效配置、完整性错误不做无意义网络重试。

测试注入 `virtualTime` scheduler，必须能验证提前推进零工作、到期恰好一次、close / retry 取消旧定时器；不得依赖任意 `Thread.sleep` 碰巧成功。

## 7. 完整执行流程

### 7.1 Preflight

1. 校验配置、Host 权限、本地备份根、磁盘限额、WebDAV 参数（仅 WEB_DAV）。
2. 固定 generation、配置快照与触发原因，计算源库 identity / 挂载 signature。
3. 恢复已有 outbox；优先重试 digest 已确定的快照，不无意义重备份。未初始化挂载签名时先登记一次；后续仅当签名变化才触发 MOUNTED_CHANGED，不能每次启动无条件生成新备份。
4. 本地可用磁盘至少满足 `max(64MiB, 2 * 源库主文件大小)`，另加可配置余量。空间不足具名 `INSUFFICIENT_SPACE`，不启动写满磁盘的备份。

### 7.2 Capture 与本地验收

SQLite 在线备份使用持久化模块掌管的独立只读源连接，准入登记纳入 `ContextDatabaseExecutor` 的关闭协议；连接只能来自已校验的原数据库路径，绝不接受 VFS URI。目标只能是本机新文件。备份 API 的 busy 参数有上限，支持进度回调检查取消令牌。

不复制源 `-wal` / `-shm`，不对业务库做 `VACUUM INTO`，不在活动事务中创建快照，不把 checkpoint 当备份。备份结束关闭目标连接，然后独立打开做完整校验。

本地验收至少包含：

- `integrity_check` 仅返回 `ok`；
- `user_version == 2`，六表五索引完整；
- 行数与关键 checkpoint 可作诊断，不宣称字节相同；
- digest 与 size 从最终关闭后的文件计算；
- 校验失败标 FAILED，保留现场 journal 供受控诊断，不上传。

### 7.3 不可变发布与未知结果

内部接口示意：

```kotlin
interface ImmutableSnapshotPublisher {
    suspend fun publish(
        source: Path,
        digest: String,
        expectedSize: Long,
        idempotencyKey: String,
        onProgress: (Long) -> Unit,
    ): PublishedSnapshot
    suspend fun verify(remote: PublishedSnapshot): VerifiedSnapshot
}
```

发布分 `ABSENT / PUBLISHED_BY_US / ALREADY_PRESENT_VERIFIED / CONFLICT_UNKNOWN`。digest 相同且校验成功是幂等成功，不是覆盖；digest 不同的同名路径是服务错误，禁止修改远端。

LocalFS：

1. 在备份根的 staging 下创建随机临时文件，使用排他创建。
2. 分块复制、检查取消、计算 digest，close；在支持时 fsync 文件与父目录。
3. 通过同一目录的 `link(temp, target)` 原子创建硬链接发布；文件系统无硬链接能力则明确不支持，不使用会覆盖的 rename。已存在时读取目标验证 digest，不删目标。
4. 同 digest 视为先前尝试已成功；不同 digest / 读取失败进入 CONFLICT_UNKNOWN，绝不覆盖。

WebDAV：

1. 用条件创建将完整内容写入唯一 staging URI，校验大小 / digest。staging 名含 taskId / generation / attempt，可被恢复流程识别，不覆盖旧 staging。
2. 新增 `immutableMove` 能力，要求 WebDAV `MOVE` 到最终 digest URI 且 `Overwrite: F`，服务端遵守 RFC 4918 的 412 冲突语义。当前 OpenDAL 普通 `rename` 不暴露此保证，不能当作 immutableMove。
3. 若当前驱动无法发送 / 约束 Overwrite:F，可在 vfs storage 增加受控 WebDAV HTTP 实现，但复用现有 endpoint、认证、错误脱敏、超时和 lifetime；不新建平行公开后端。必须由真实服务器证明 412 与目标不变。
4. 412 后读取最终对象，同 digest 是先前发布成功，不同 digest 为冲突。MOVE 超时先 reconcile，不能假设成功或失败。
5. 仅当服务端已证明不支持不可变 MOVE 时，才可选 `If-None-Match:*` 直传最终 URI；其条件也不能被忽略。不支持条件 / 身份无法确定时进入 DEGRADED_PENDING / FAILED，**不执行普通 write 或 rename**。

两种后端最终都必须以独立流读取完整对象计算 SHA-256；`stat` 的 existence、Content-Length、ETag 均不是完整性证据。VFS Node 提交置于物理发布之后：成功则提交 `FILE_CREATED`；失败保留物理对象与 outbox `remote_effect=UNKNOWN/PUBLISHED`，重启通过 digest 对账补登记，绝不倒删远端对象。

`sync.checkUpdates` 先列 manifest，再按 snapshot digest 对账对象；manifest 缺失 / 损坏 / 指向错误 digest 是具名远端一致性错误，不能据此推断“远端没有更新”。不能列目录的服务器返回 capability=false，不声称已完成更新检查。

#### VFS 流式发布与锁边界

不把整个数据库读成 ByteArray，也不把网络 IO 放进现有 `DefaultVfs.StateBoundary` / SQLite 单写连接里。新增窄 `ImmutableVfsPublisher`，由 VFS Core 拥有，Runtime 只负责组装：

1. 短暂进入状态边界，解析 `/context` 路由、授权、目标路径与父目录，领取 `(storageKey, mountIdentity, leaseId)`；慢速 mount / 状态写等待可取消。
2. 离开边界后使用 backend lease 分块写 staging、读取校验与不可变提交。lease 注册到 Runtime 资源清单，带读写超时和整体 deadline；close 先取消 lease 再等待退出，不直接关闭底层 Storage。
3. 物理发布完成后，短暂重入状态边界，验证 storageKey / mountIdentity 未变，再在 `UnitOfWork` 中登记 FILE Node 与 FILE_CREATED event。此处只做 SQLite / Node 操作，绝不等待网络。
4. Node 提交失败返回 `PUBLISH_PARTIAL` 并保存远端 digest；恢复时依据 URI + digest 补登记，不把同内容上传两遍。挂载已改变时不把旧对象登记到新挂载。

LocalFS adapter 新增同文件系统的流式 staging + fsync + hard-link，WebDAV adapter 新增受控 staging + `MOVE Overwrite:F`。普通 `Vfs.write`、`safeReplace` 和 `Storage.move` 的既有替换语义不变。Core / Storage 的 capability 默认值必须是 `false` 或默认实现抛 `UNSUPPORTED_OPERATION`，防止第三方后端静默获得危险的假能力。

请求超时但服务器可能已接收时，状态为 `RECONCILE`：先按 digest 回读。确认前不能换远端路径制造重复，也不能删除 journal。

### 7.4 LOCAL 模式

本地 VFS 快照使用同一个不可变发布 Port。成功后状态为 `SUCCEEDED`，`remoteUri=null`，`syncMode=LOCAL`，事件明确 `localOnly=true`。UI 文案是“本地快照已保存”，不能显示“已同步”。

### 7.5 Reapply

发布前发现源业务代次变化，不丢弃已验证快照：先完成该不可变快照的本地登记；若策略为 latest-only，则创建新任务引用并保留前一个为可恢复历史。默认 CT27 不启用 latest-only 重拍；远端只追加，不做“替换回滚”。单 Worker 下变更通知只设置 dirty 位，不直接重入 Capture。

## 8. 公共 API 与十六个消息

Kotlin API 命名建议：`BackupTaskService.start(trigger)`、`getTask(taskId?)`、`listTasks(limit,before)`、`cancel(taskId)`、`retry(taskId)`、`updates: Flow<TaskSnapshot>`、`closeAsync()`。

所有 Bridge / TUI JSON 使用同一 DTO schema。请求必须带 `requestId`；所有响应携带 `task`（适用时）或具名 `error`；事件 `eventId` 供去重，`sequence` 供断线检测。断线后 `task.get` / `task.list` 是事实来源，不要求 SSE 保证重放全部历史。

原需求术语对应：`BackupRun = task.run`，`run.start = task.run`，`run.cancel = task.cancel`，`run.retry = task.retry`，`run.updates = task.updates`，`snapshot.download = task.download`，`snapshot.import = task.import`，`sync.apply = sync.applyRemote`，`host.closeAsync = host.shutdown`。

| # | message | 请求 | 成功结果 / 错误 |
| --- | --- | --- | --- |
| 01 | `task.run` | `{requestId,trigger:"MANUAL"}` | 完整 `TaskSnapshot`；活动手动任务时 `ALREADY_RUNNING` |
| 02 | `task.get` | `{requestId,taskId?}` | 指定或当前任务；无任务返回 `task:null` |
| 03 | `task.list` | `{requestId,limit,before?}` | `{items,nextCursor}`；按持久化时间 / ID 稳定排序 |
| 04 | `task.cancel` | `{requestId,taskId}` | CANCELLING snapshot；终态幂等返回，不虚报刚取消 |
| 05 | `task.retry` | `{requestId,taskId}` | 新 generation snapshot；本地不存在时明确 `SNAPSHOT_NOT_FOUND` |
| 06 | `task.updates` | `{requestId,subscribe:true,taskId?}` | SSE / JSON-RPC notification；不是一次性命令结果 |
| 07 | `task.delete` | `{requestId,taskId}` | 只删无 pending 的已完成历史元数据；活动 / 待传任务拒绝 `TASK_RETAINED`；本期不物理删快照 |
| 08 | `task.download` | `{requestId,taskId}` | 返回已验证本地 snapshot 的授权只读流；不存在时可从已校验远端重新物化到 Host 私有缓存；不接受客户端路径 |
| 09 | `task.import` | 预留：`{requestId,confirmed:false}` | 本迭代固定 `UNSUPPORTED_OPERATION`，不得实际覆盖数据库 |
| 10 | `config.get` | `{requestId}` | 脱敏配置 + capabilities + 当前 pending 数 |
| 11 | `config.update` | `{requestId,expectedRevision,patch}` | 新 revision 与 task；revision 冲突 `CONFIG_CONFLICT` |
| 12 | `storage.status` | `{requestId}` | mount、backend、能力、pending、最后成功、当前降级，不含凭据 |
| 13 | `sync.retry` | `{requestId,taskId}` | 仅重试远端 outbox，不强制重新备份 |
| 14 | `sync.checkUpdates` | `{requestId}` | 远端可用 / 较新不可变快照数，不自动下载或导入 |
| 15 | `sync.applyRemote` | `{requestId,taskId,confirmed}` | 默认拒绝；本期不开放远端应用 / 恢复写入 |
| 16 | `host.shutdown` | `{requestId}` | `accepted` 后完成 close，连接再关闭；等待实际资源收尾 |

`task.run` 对应用户原“run.start”；采用统一 `task.*` 命名避免 run / task 双模型。`task.delete` 默认只清历史元数据，任何本地快照删除都要求无 pending / 引用；本期 UI 不开放破坏性物理删除。`task.retry` 优先沿用当前 task 的本地快照创建新 generation；`sync.retry` 仅处理远端 outbox，两者都不在已有 pending 时重新 Capture。

### 8.1 TaskSnapshot DTO

```json
{
  "task": {
    "id": "uuid", "generation": 7, "workerEpoch": 3,
    "status": "RUNNING", "phase": "PUBLISH", "trigger": "MANUAL",
    "createdAt": "...", "startedAt": "...", "finishedAt": null,
    "snapshot": {"id":"uuid","digest":"...","size":123,"generationId":"..."},
    "local": {"status":"VERIFIED","journalRetained":true},
    "remote": {"status":"UPLOADING","uri":"alcyone/backups/snapshots/...","digest":null},
    "attempt": 1, "nextRetryAt": null,
    "progress": {"bytesDone": 100, "bytesTotal": 123},
    "error": null, "recentEvents": []
  },
  "capabilities": {"cancel":true,"retry":false,"download":false,"import":false},
  "serverTime": "..."
}
```

进度 monotonic 仅在同一 `(generation,phase,attempt)` 内保证；换阶段必须携带新 phase。错误 `{code,retryable,message}`，message 为受控文案，不含 SQL、URL 凭据或路径秘密。

## 9. Host、Web 与 TUI

### 9.1 Rust Host

- 启动解析 `--backup-root=vfs://context/backup/`、auto / interval / sync 配置，创建 `/context/backup`，再把规范 URI 交给 Kotlin runtime。
- `BridgeDispatcher` 增加白名单方法及参数 schema；未识别方法返回 `METHOD_NOT_FOUND`，不 shell 执行。
- 一个 runtime owner 管理更新订阅、请求应答和 shutdown；取消订阅 / 页面断开不得关闭整个 Backup runtime。
- 默认绑定 loopback，WebDAV 密码仅从授权配置 / 环境读取，不写 page config、stdout、事件或持久化明文日志。
- HTTP / SSE 走本机受鉴权 bridge。下载由 Host 先校验任务 / snapshot 权限，再以流式 response 返回，设置 `Content-Disposition: attachment`；不把 journal 的绝对路径交给浏览器。远端物化只能进入 Host 私有缓存，先校验 digest 才允许下载。
- `requestId` 在任务状态库保存 `(method, requestId, requestDigest, response)`；重复且摘要相同返回同一结果或 `REQUEST_IN_PROGRESS`，摘要不同时返回 `REQUEST_ID_REUSED`，不能重复启动任务。未知请求体、过大请求体、非法 UTF-8 均具名拒绝。
- `host.shutdown` 幂等，等待 runtime、SSE、备份 worker、VFS、SQLite 逆序退出。

### 9.2 静态 Web

1. 连接前禁用操作，显示连接状态；连接后 `config.get + task.get + storage.status`，再订阅 updates。
2. 只由服务端 snapshot 渲染；`task.run` 点击后仍以返回 task 为准，不本地伪造 RUNNING。
3. 所有异步回调核对 `requestId / taskId / generation`；旧响应不能覆盖新任务。
4. CANCELLING 禁用重复取消；FAILED / DEGRADED_PENDING 显示“重试上传”或“重试任务”，而不是只显示失败。
5. 配置弹窗不返回密码；留空表示保留，明示“清除密码”才删除。
6. 事件监听、定时器、object URL、fetch AbortController 均在 dispose 清理。

### 9.3 TUI

复用 `BackupTaskService`，由 TUI adapter 将 `TaskSnapshot` 渲染为文本。按键触发 intent，不在 UI 线程运行 JDBC / OpenDAL；`q` 触发同一个 close 协议。JSON-RPC 输出与 Bridge 共用 golden DTO。

## 10. 资产指纹与离线 Demo

新增工具从 bridge / demo / e2e 共享源构建 HTML：将 JS 转为合法 JSON string 后注入，不能只把双引号换成 `\"`。必须同时处理换行、反斜杠、U+2028 / U+2029、`</script>` 等边界。

默认 `asset-manifest.json` 内嵌于指纹 HTML；可显式传路径生成另一份。不能依赖 `file://` 下的 `fetch()` 才能打开。

指纹脚本解析 JSON、校验版本、遍历全部资产，浏览器支持时用 SubtleCrypto 计算 SHA-256；不支持时明确报“无法验证”，不能显示已通过。输出逐文件 pass / fail、总字节和 manifest digest。

离线 HTML 可以模拟任务状态机、重试、取消和指纹，但页面显著标识“SIMULATED / 未连接真实 Host”。它不是网络、VFS、下载、EventSource 或浏览器安全策略的验收证据。

## 11. 错误、安全与隐私

| code | 处理 |
| --- | --- |
| `INVALID_CONFIG` | 启动 / config.update 拒绝，不触发上传 |
| `AUTH_FAILED` | 脱敏提示重新授权，不自动反复请求 |
| `MOUNT_UNAVAILABLE` | 本地 / 远端保留 pending，可重试 |
| `UNSUPPORTED_ATOMIC_PUBLISH` | 禁用危险回退，保留 pending |
| `REMOTE_VERIFY_FAILED` | 不标记成功，不覆盖既有对象 |
| `SNAPSHOT_INTEGRITY_FAILED` | 不上传，保留受控诊断文件 |
| `INSUFFICIENT_SPACE` | preflight 失败，不创建完整备份承诺 |
| `ALREADY_RUNNING` | 返回真实活动任务 |
| `GENERATION_STALE` | 丢弃旧回调，不写新状态 |
| `CLOSED` / `CANCELLED` | 生命周期具名结果，不包装为存储错误 |

日志只允许 taskId、digest、size、状态、脱敏后端和错误码。敏感原文、数据库内容、完整查询、密码、Authorization、endpoint userinfo 禁止进入事件。digest 不是加密；共享远端访问控制由 WebDAV 服务负责。

## 12. 验收设计

### A. SQLite / outbox

- 在持续 ingest 下完成 online backup；快照 integrity_check、user_version、六表五索引通过。
- 业务 Schema 与 factory 原有校验不变；不向业务库增加 backup 表。
- kill 点：outbox 提交前、journal 创建后、READY 提交前、上传成功后、UPLOADED 提交前、journal 删除前。每种重启都恰好恢复一个逻辑快照，不丢待传、不重复远端对象。
- 取消、关闭与备份回调竞态；没有 close 返回后仍写 journal / SQLite 的 worker。

### B. LocalFS 与 WebDAV

- LocalFS：排他 staging、hard-link 发布、同 digest 重试、不同 digest 冲突、崩溃遗留 staging、目标预存在均通过。
- WebDAV stub：条件创建、401、超时、断流、服务端已收但响应丢失、不支持条件、回读 digest 错误；不得出现普通覆盖。
- 至少一个真实受控 WebDAV 容器 / 服务做条件创建与 MOVE 能力测试；实测不支持即记录不支持，不把 mock 当服务器证明。
- 任意发布失败后源 journal 与 pending 均在；已成功对象永不修改。

### C. 调度与并发

- 手动并发、interval + manual、mount change + run、config reload、cancel + complete、close + retry、startup recovery + timer。
- 每个 phase 设可控屏障；旧 generation 回调不得修改新 task。
- fake clock 验证间隔、退避、重试和关闭；断言 await 次数而非只断言 delay 被调用。
- 上传慢 / 卡住时 ingest 与 close 的行为符合协议；无持锁 delay。

### D. 协议与 UI

- 十六消息 golden JSON、非法参数、重复 requestId、断线重连、乱序事件、重复事件、终态幂等。
- Web 模拟快照仅用于渲染；真实浏览器另跑 Host + 静态页面，验证点击、下载、配置、取消和错误文案。
- `tools/e2e/run-kmp-smoke.mjs` 增加 CT27，不禁用原 VFS 协议测试。Node 测试与浏览器测试分别报告。
- TUI 与 Web 对同一 runtime 序列产生相同 task / error DTO。

### E. 回归与证据

实现验收时执行 `./gradlew --no-daemon spotlessCheck test`，要求 BUILD SUCCESSFUL；报告实际 XML tests / failures / skipped，不预写数字。原 CT26 的 2135 仅基线，新增测试后总数必须重新统计。

证据包含：测试命令、真实 WebDAV 配置（无凭据）、kill 点、恢复结果、协议 golden、浏览器报告、未运行项。未经实测不得声明 Safari / file:// / WebDAV `MOVE Overwrite:F` / 浏览器下载已通过。

## 13. 实现拆分与审查门

| 批次 | 内容 | 通过门 |
| --- | --- | --- |
| B1 | 私有状态库、DTO、任务状态机、虚拟时钟、恢复 | 状态 / 并发 / kill-point 测试 |
| B2 | 持久化 onlineBackup、本地 journal、完整性 / digest、outbox | 连续写入与关闭安全测试 |
| B3 | VFS 不可变发布、LocalFS、WebDAV、重试 | 两类存储故障注入与真实服务契约 |
| B4 | Coordinator 接线、配置、生命周期、同步检查 | 真实 runtime 集成 |
| B5 | Host 十六消息、TUI、Web、资产工具 | 协议 golden、Node、真实浏览器 |
| B6 | 全量回归、故障矩阵、文档与证据 | 独立复核所有 P0 / P1 关闭 |

B1～B4 可分批 Review，但在远端语义、恢复与生命周期验证完成前，不允许以“UI 已能显示成功”判定 CT27 DONE。

### 13.1 逐批实现清单

**B0：范围与协议基线**

1. 依据 0.1 修订 CM6 范围与专题 9，附用户批准链接；只改任务库和发布能力，不改业务六表。
2. 在 `context/api` 新增 Backup DTO / Service；在 `context/core` 定义 BackupTaskService、SnapshotStore、Clock、Coordinator Port。
3. 新增 `context/backup` 模块，负责私有 state、journal、outbox、Worker 与恢复；Core 不依赖 JDBC / OpenDAL。
4. 为十六消息建立共享 JSON golden 与默认拒绝 capability，先冻结 DTO，再写 Host / UI。

**B1：可重启任务内核**

1. SQLDelight 私有 schema / migration、序列、generation、epoch、config revision；独立 DatabaseHolder。
2. 状态迁移 Guard、事件顺序、task query / pagination、pending query；所有更新断言 affectedRows=1。
3. 单 Worker scheduler、reason 集、cancellable retry、close job、startup recovery；纯 fake storage / fake snapshot。
4. 测试每个非法迁移、重复 request、旧 epoch、重启 lease、日志保留与 acknowledged 清理。

**B2：SQLite 快照**

1. 在 persistence 增加受控 onlineBackup，读源注册到关闭协议，目标路径只能位于 Host 私有 journal。
2. runInterruptible 隔离 JDBC，回调只读 generation / cancellation；连接与目标文件句柄按逆序关闭。
3. 完整性、schema、digest、size 验证，READY 原子提交；失败保留具名诊断记录。
4. 用真实数据库持续写入、取消、kill-point 验证，不把 mock snapshot 当 SQLite 验收。

**B3：VFS 不可变流式发布**

1. `ImmutableVfsPublisher` 与 backend lease，拆分短 StateBoundary 和慢 IO。
2. LocalFS 排他 staging、流式 copy、fsync、hard-link、EEXIST 对账与 VFS Node 补登记。
3. WebDAV 条件 staging、超时 / 中断、回读、`MOVE Overwrite:F`、412 / UNKNOWN 对账。
4. WsgiDAV 真实服务先测能力；另加拒绝覆盖的服务 stub，证明服务端不满足时不降级。

**B4：Runtime / 生命周期**

1. 在 Runtime 组装 Backup owner，在关闭链最前封闭任务并等待 lease，再关闭 VFS / SQLite。
2. 配置 revision / 目标 generation / mount identity、自动 Cron、pending recovery 与更新检查。
3. `CLOSING_TIMEOUT` 作为具名非干净关闭结果，Host 必须显式处理，测试不能吞掉。

**B5：交付界面与工具**

1. 新 Rust Host 启动 JVM runtime、严格 loopback HTTP / SSE / JSON 白名单与鉴权。
2. 静态 Web 任务面板、配置、重试、取消、下载；新 TUI adapter，共享同一 DTO。
3. 资产清单与指纹、离线模拟明确 SIMULATED；真实浏览器单独跑 Host。
4. 文档给出启动参数、无凭据配置、截图、TUI 文本 golden 和全部未运行项。

## 14. 明确排除的伪实现

- `Thread.sleep` 后把任务设 SUCCEEDED；内存任务列表重启即丢；把FAILED当永久终态。
- `stat` 后普通 write；`rename` 覆盖；上传前删 journal；忽略未知上传结果。
- 直接复制活动 DB / WAL 到远端；在 WebDAV 上打开 JDBC；另开绕约束的业务写连接。
- 用 `buildJsonObject { put("event", event) }` 替代具名 DTO；后端字段缺失由前端默认成 SUCCESS。
- 只跑 Node 模拟 UI 就声称浏览器 / 下载 / Host 已验收。

本设计完成后，自动备份的可信语义是：**每一份成功快照都可独立校验与重试；每次不确定都保留恢复材料；每个旧任务都不能改写新任务；远端能力不够时宁可待传，也不覆盖。**
