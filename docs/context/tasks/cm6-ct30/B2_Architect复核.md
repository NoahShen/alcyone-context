# CT30 B2 Architect 复核

## 本次修改（2026-11-12，R1 CLOSED；B2 APPROVED / DONE）

- 独立核对五项生产 SHA 全部与首次复核一致；R1 只新增/修改正式测试，不改生产、不接 Runtime。
- fresh 定向 Core 4、Persistence 4、integration SQLite 3、独立 JVM 1 全绿；旧 RecoveryJournal/VfsStateDatabase/Core Event 回归通过。
- 四项 R1 均有真实 SQLite 正式测试；Lead 全量 137/137、14 模块 **2307=2307**。旧 1.sqm/2.sqm SHA 保持。
- **R1 CLOSED；B2 APPROVED / DONE。** CT30/CM6 尚未完成；按任务计划进入 B3，完成 B3/B4 与最终验收后才可收口。

## 历史修改（2026-11-12，首次复核：R1 验收补证，不批准 DONE）

- 独立审阅 v4 migration、Core Consumer、SQLite Repository、测试和独立 JVM 强杀路径；未修改生产代码，临时探针已删除。
- 定向 fresh 与三个真实 SQLite Architect 探针通过，未发现已证实生产正确性缺陷；但任务书明确要求的 poll/顺序/v3→v4/迁移回滚缺正式测试，**B2 IN_REVIEW / R1，未 APPROVED / DONE**。

## 1. 独立证据

- `./scripts/dev gradle :vfs:core:test --tests '*ReliableEventConsumerTest' :vfs:persistence:test --tests '*ReliableEventSqliteTest' :integration-tests:vfs:test --tests '*ReliableEventConsumerProcessTest' --rerun-tasks --console=plain`：BUILD SUCCESSFUL，日志 `/tmp/ct30-b2-architect-directed.log`。
- Core 4 例、Persistence 2 例通过；integration 独立 JVM 子进程 exit 97 后第二进程 exit 0，日志由正式 Gradle 任务确认。
- 真实 SQLite 临时探针 2/2：慢 handler 时 `writer.append` 在 2 秒内完成；close 等 handler，成功后 next=2。永久失败 handler 调一次后 close，cursor=1、无重投提交。日志 `/tmp/ct30-b2-architect-probes.log`。
- 真实 SQLite poll 探针 1/1：无 wakeup hint，启动 80ms 后追加同毫秒两条和倒时钟一条，Consumer 按 sequence 1/2/3 收到。日志 `/tmp/ct30-b2-architect-poll-probe.log`。
- 最终 Lead 全量 `./scripts/check` 137/137、14 模块 2302=2302 已核对计数记录；本复核未重复耗时全量。

## 2. 通过的生产语义

1. **迁移结构**：`3.sqm` 在既有 `VfsStateDatabase.upgrade` 的 `BEGIN IMMEDIATE` 内重建 event；`rowid ORDER BY` 回填 sequence，字段完整保留，event_id 唯一，后续 AUTOINCREMENT；cursor 表 CHECK 正数。
2. **事务边界**：起点和 cursor CAS 使用独立短事务；读批自动提交 SELECT 返回后 handler 才运行，真实探针证明 handler 中追加不阻塞。
3. **at-least-once**：handler 整批成功才 CAS；handler 失败从原 cursor 整批重读，不跳过；独立 JVM 在 handler 成功后 halt(97)，重启同 eventId 重投，最终 cursor=2。
4. **cursor 失败**：advance 不在 handler 重试 catch 内，DB/CAS 失败终止 Job，不无限重复已成功副作用。
5. **CE/close**：CE 原样传播且不提交；close 停止新批并等待已领取 handler，成功才提交；失败批 close 不提交。
6. **边界**：Core 组件未改 `Vfs`/`subscribe`，Runtime/外围 Consumer 未默认接线；无多进程 lease/exactly-once 声称。

复核时生产 SHA：Consumer `b18d5f59…e4e3`、Port `7266ad1f…860f`、SqliteRepositories `9f399dad…a245`、3.sqm `41291bb8…0705`、Cursor.sq `f7e9c678…ead5`。

## 3. R1 阻断：正式验收覆盖不足

以下均为 B2 委派单第 6～7 项明确门禁，不得以 Architect 临时探针或全量测试数替代：

- **R1-1 poll 保底**：正式 Consumer 测试须启动后不发 wakeup，再追加事件，证明仅靠 poll 读取；同测试覆盖同毫秒/倒时钟仍按 sequence，而非 UUID/occurredAt。
- **R1-2 v3→v4**：用真实 v3 库（含 journal、旧 event 全字段）直接打开迁移，验证 v4、稳定连续 sequence、字段保留及后续 append 接续编号。当前只有 v1→v4 和 fresh v4。
- **R1-3 迁移失败回滚**：在 v3 event 中制造合法旧库但会使 v4 重建失败的约束冲突（例如 event_id 重复），打开失败后确认 `user_version=3`、旧 event/journal 完整可读、无 event_v4/consumer_cursor 残留。
- **R1-4 测试真实性**：将 Architect 已通过的“慢 handler 不阻塞真实 SQLite append”和“失败批 close 不提交/不无限重试”纳入正式真实 SQLite 测试；现有 close/失败测试主要使用 Fake，不能把 Fake 当真实锁证据。

R1 原则上只补 Persistence/integration 正式测试与证据；生产实现若需改动须先说明具体失败及最小修复。既有 v1→v4、独立 JVM、Core 四例保持通过；R1 后 fresh 定向与最终 `./scripts/check` 重跑。

## 4. 裁定

- **B2：IN_REVIEW / R1，不批准 DONE。**
- CT30 / CM6 未收口；B3 不放行；无 Git 写操作。
- 本记录没有宣称生产缺陷；阻断是明确验收缺口。补齐后复核生产 SHA 与新增证据，再决定是否 APPROVED / DONE。
