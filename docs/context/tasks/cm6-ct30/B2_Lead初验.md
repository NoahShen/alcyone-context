# CT30 B2 Lead 初验

## 本次修改（2026-11-12，B2 实现与独立 JVM 验证）

- 交付 v4 event sequence/cursor Schema、Core 显式可靠消费组件、SQLite Repository 与测试；不改 `subscribe`/Flow/公共 VFS 签名，不接 Git Backup/Index/Audit。
- 记录真实迁移、失败重投、close/CE、独立 JVM 强杀和全量回归；B2 尚待 Architect 复核，CT30 未 DONE。

## 交付文件

生产：`ReliableEventRepository.kt`、`ReliableEventConsumer.kt`、`SqliteRepositories.kt`、`EventQueries.kt`、`Event.sq`、`Cursor.sq`、`3.sqm`。

测试：`ReliableEventConsumerTest.kt`、`ReliableEventSqliteTest.kt`、`ReliableEventConsumerProcessTest.kt`；既有 `RecoveryJournalSqliteTest.kt`、`MountIdentitySqliteTest.kt`、`VfsStateDatabaseLifecycleTest.kt`、`MountConfigurationTest.kt` 只更新当前版本与旧库降级夹具。

## 行为与验证

| 场景 | 证据 |
| --- | --- |
| 批中第二事件失败 | Core 4/4：attempts=[1,2,1,2]，仅一次 cursor=3 提交；不跳过失败事件 |
| cursor 写入失败 | handler 只执行一次，主 Job 终止，不把 CAS/DB 错误当业务失败无限重试 |
| close | 已领取 handler 未释放时不提交、关闭线程等待；释放后 cursor=2 |
| CE | handler 原始 CE 实例传播，Job cancelled，cursor 不提交 |
| v1→v4 | 真实连续迁移，rowid→sequence 1/2，完整 URI/operation/source/target 保留 |
| cursor | EARLIEST/LATEST、重复 start 初始化、CAS 拒绝、重开续读均真实 SQLite |
| 独立 JVM | 首进程 handler 写 eventId 后 `Runtime.halt(97)`，cursor 未提交；第二进程 exit 0，同 eventId 再投一次，最终 cursor=2 |
| 最终全量 | `./scripts/check` exit 0，137/137 tasks，14 模块 2302=2302、0 failure/error/skipped；Core 343、Persistence 60、integration 122；日志 `/tmp/ct30-b2-final-check.log` |

## 边界

- 提供显式 opt-in 的 at-least-once；处理函数必须按 `eventId` 幂等。批处理成功与 cursor 提交之间崩溃可能重投整批。
- 单 consumerId 仅允许一个进程内实例，无多进程 lease、无 exactly-once、无自动 partition。
- 实时通知仅提示；SQLite backlog 由有界 poll 保底。默认重试 100ms/500ms/1s/5s。
- `close` 停止领取新批并等待当前批；调用方应在 close 前等待必要的 backlog 完成信号。
- 未接入生产 Runtime 默认生命周期；多进程 lease、持续外部写时 close 吞吐、真实 Git Backup 重放 not-run。

## 待 Architect 裁定

B2 是否 APPROVED / DONE；未裁定前 CT30/CM6 不宣称完成，无 Git 写操作。
