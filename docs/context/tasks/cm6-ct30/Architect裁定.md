# CT30 Architect 技术裁定

> **终态（2026-11-12）**：B1～B4 均已独立复核 DONE，CT30 已 APPROVED / DONE。最终验收依据见 [B4 Architect 复核](B4_Architect复核.md)；本文件 D1～D20 继续作为实施范围与边界裁定。

## 历史修改（2026-11-11，migration 版本更正）

- 委派前核实现有 `1.sqm`（SHA `1158632e…1bb`）已执行 v1→v2 mount 物理身份迁移，测试锁定 Schema.version=2；B1 不得覆盖。
- **B1 journal 更正为 v3/新增 2.sqm，B2 sequence/cursor 更正为 v4/新增 3.sqm**；其余 D1～D20、写集合和验收边界不变。不得修改既有 1.sqm 或把 v2 当空库版本。

## 历史修改（2026-11-11，D1～D20 裁定，放行 B1）

- 核对现有 Event 表、UnitOfWork、StorageCapabilities 与 VFS migration 后，批准恢复 journal、可靠消费和 Local FS dirfsync 的分批方案；全部先组件化，不接默认 Runtime 恢复/可靠消费。
- B1 只做 VFS Schema v2 journal、Core 选定文件操作接缝与显式只读 reconcile；B2 再做 Schema v3 event sequence/cursor；B3 再做 Local FS capability/dirfsync；B4 只验收。每批独立 Architect 复核，CT31/CT32 不放行，无 Git 写操作。
- 本裁定不允许自动重放/补偿、多进程恢复、exactly-once、WebDAV 原子发布或真实断电保证。

## D1～D6 恢复

- **D1 批准**先意图后外部写；journal 是事实记录，不是分布式事务。每个外部物理变更前都须持久化具体阶段，move 的目标写入与源删除不得共用一个模糊阶段；进度写失败禁止继续后续外部写。
- **D2 批准**仅文件 safeReplace、publishNew、native move、copy move；目录 move、普通 write/delete 不在本批恢复范围，保留既有 PARTIAL 语义。
- **D3 批准**重启不自动修复；显式 reconcile 首版只读 Storage/Node 并报告事实和 RECONCILE_REQUIRED，不修改 Node/Event、不补造事件。证据不足一律保持待核对。
- **D4 批准**单 Runtime/StateBoundary 单写者；不增加跨进程锁、lease 或自动恢复者。
- **D5 批准**journal close 与 Node/Event 同事务，扩展 TransactionScope/UnitOfWork 的精确接缝；Storage 与 SQLite 仍非原子。journal disabled 时既有事务/行为不变。
- **D6 批准**不新增公共 Vfs 恢复 API、不接 AlcyoneVfs.create 默认恢复。B1 可用 internal/显式 Core/Persistence 组装测试；普通生产默认 journal 不启用，不以 no-op 冒充已交付生产恢复。

B1 Schema 为 **v2**，只新增 operation journal 所需表/索引，不改 event。operation_id 唯一，状态迁移带 expected generation/CAS；诊断 code 白名单，不持久化任意异常文本/秘密。重启/独立进程只验证 journal 与真实物理事实，不自动推断成功。

## D7～D13 可靠消费

- **D7 批准**B2 Schema **v3** event sequence。SQLite 不能在原表直接 ADD AUTOINCREMENT PK，须事务内重建 event 表：sequence INTEGER PRIMARY KEY AUTOINCREMENT、event_id 唯一，旧行按 rowid 稳定编号；保留原字段/值并验证 sequence 连续唯一。
- **D8 批准**consumer_cursor 独立表；读批释放 DB 锁后处理，整批全部成功才短事务推进。处理成功而 cursor 提交前崩溃必须重投，明确 at-least-once，event_id 供消费者幂等。
- **D9 批准**EARLIEST 默认、LATEST 显式；首次游标创建必须短事务确定起点，LATEST 记录创建时最大 sequence，不随 backlog 倒退。
- **D10 批准**单 consumer 单协程、有界批默认100/1..1000、可取消轮询默认100ms；wakeup 仅 hint。普通处理失败不跳过/不死信，以有上限退避持续重试直到成功或关闭；CE/fatal Error 不吞。
- **D11 批准**close 停止领取新批并等待已领取处理结束；仅成功完成批提交游标，取消/失败不提交，重启重复。慢 callback 不持 SQLite 写事务、不阻塞 VFS 写。
- **D12 批准**consumerId 仅进程内单实例，不承诺多进程同名消费。
- **D13 批准**显式 ReliableEventConsumer 组件，不接默认 Factory、不改 subscribe/公共 Vfs API；实时 Flow 可丢语义保持，事件保留/清理不在本任务。

## D14～D20 Local FS 与分批

- **D14 批准**同目录 temp/file fsync/atomic rename 或 hard link 后，在平台支持时 fsync 目标父目录；发布与临时清理共享目录项持久边界。
- **D15 裁定**StorageCapabilities 新增默认 false 的 durableDirectorySync，与 atomicReplace/atomicPublishNew 独立。Local FS 构造实测能力；不支持时保留现有原子非 durable 行为，不能标 durable。支持而实际 fsync 失败则如实报错，不静默降级。本批不新增 strict mode/公共配置。
- **D16 批准**只清 fileKey 一致的自有 temp，orphan 不自动清；**D17 批准**rename 前保旧、rename 后失败保新，不反向补偿。
- **D18 批准**WebDAV capability 与 publishNew 拒绝不变；**D19 批准**仅实际平台能力/进程重开证据，真实断电、Linux/硬件 flush not-run。
- **D20 裁定**B1～B4 串行，v2/v3 两次独立 migration，不合并跳版。每批 fresh 测试、Spotless、81 VFS 基线/全量 check、真实 migration 与 SHA/边界对账。

## B1 放行范围

仅 VFS Core journal DTO/Port/选定文件操作与只读 reconcile、Persistence v2 Recovery.sq/1.sqm/Repository/UnitOfWork、对应测试及 integration-tests/vfs；不接 vfs/api、Runtime Factory、Context 或 deploy。若现有 EventPipeline/Node 写点无法同事务关闭 journal，先暂停给最小接缝，不复制第二套提交流程。
