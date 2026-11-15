# CT30 B1 Architect 独立复核

## 本次修改（2026-11-12，B1 APPROVED / DONE，放行 B2）

- 独立 Core/Persistence/integration-vfs fresh exit 0、22/22、52s，XML **339+58+121=518**、零失败/错误/跳过，日志 `/tmp/ct30-b1-architect-three.log`。全仓 `./scripts/check` exit 0、**137/137**、3m51s，14 模块 **2295=2295**、零失败/错误/跳过，日志 `/tmp/ct30-b1-architect-check.log`。
- `1.sqm` SHA `1158632e…1bb` 未变，`2.sqm` SHA `26ea0a36…d3`；真实 v1→v2→v3/v2→v3、失败迁移原子回滚、Mount/Node/Metadata/Event 保留通过。generation+phase CAS、CLOSED 拒绝、Node/Event/journal close 原子回滚通过。
- 四操作请求前/外部写阶段/成功 close/默认 disabled 兼容通过；SOURCE_DELETE_REQUESTED 失败不删源、Storage 主失败不被诊断覆盖、CE 原样传播。双 JVM 强杀后 pending/真实文件事实保留，reconcile 前后 DB/文件摘要不变，UNKNOWN/CLOSED 与缺失 Node 安全处理。
- 默认 Runtime/API 未接 recovery/reconcile，无自动重放/补偿/补事件；目录 move/普通 write/delete 未扩。Core 无 Ktor/serialization/SQLDelight/OpenDAL。81 manifest 69 unchanged、12 cumulative expected changed、0 missing，按已批准历史接缝接受。
- **B1 DONE，放行 B2 VFS Schema v4/3.sqm event sequence/cursor 与显式 ReliableEventConsumer。** B3/B4、CT31/CT32 不放行；真实断电、多进程、生产默认恢复 not-run，无 Git 写操作。

## B2 验收重点

event 表事务重建、旧 rowid 顺序编号与 event_id/字段保留；cursor 处理后整批提交、崩溃重复、失败不跳过、EARLIEST/LATEST、有界批/轮询/关闭和实时 Flow 回归。不得用实时 Channel 或 UUID/time 当可靠顺序，不接默认 Runtime。
