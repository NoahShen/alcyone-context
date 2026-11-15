# CT28 B2b Architect 独立复核

## 本次修改（2026-11-11，B2b APPROVED / DONE，放行 B2c）

- 独立 Server fresh 串行复跑 exit 0、27/27、14s，XML **73=73**、零失败/错误/跳过，日志 `/tmp/ct28-b2b-architect-server-serial.log`。首次与全仓并行编译触发 Kotlin daemon 失败，不是测试通过证据；串行重跑通过，完整日志 `/tmp/ct28-b2b-architect-server.log`。
- 独立全仓 `./scripts/check` exit 0、132/132、2m30s；14 模块 **2220=2220**、零失败/错误/跳过，日志 `/tmp/ct28-b2b-architect-check.log`。
- Admin 完整安全投影、Read 六路由统一权限、Ready 单依赖与独立 deadline、真实 SQLite 失败、无 client 与外部所有权均通过。B2a 四文件 SHA 与 Schema `ec727a53…aadd8ea3` 不变，Backup/Dreaming HTTP 未提前混入。
- **B2b DONE，B2c 放行**：手动 Dreaming/Backup HTTP + managed 生命周期与最终 CT28 验收；CT28 尚未 DONE，CT29 不放行。无 Git 写操作。

## 1. 验收覆盖

- Admin 显式 DTO 包含 lifecycle、四类 Worker、pending、dreaming checkpoint、map_status、llm 与 backup；显式 null/空数组区分，无 Throwable/message/model/baseUrl/path。
- ADMIN-only 与 Read/Ingestion 互不继承；未知 401、错类 403、未配置 Admin 路由 404；Persistence/Lifecycle 固定 503，其他 500。
- live 不触 Context；ready 仅 `checkReady()`，false/DB 失败/关闭/自有 deadline 均 503，SDK CancellationException 传播。
- Read 六路由在 body/query/SDK 前统一 `requireOperation`，原 stream、404、限额和取消语义未改。
- 未配置 Ingestion client 仍可 Admin/Health；既有外部 Runtime 入口不 start/close Context。

## 2. B2c 放行边界

只补任务书既有 `POST /v1/admin/dreaming`、`POST /v1/admin/backup` 与 managed Server 生命周期，复用 B2a/B2b API，不改公共 SDK/Runtime 算法/Schema/VFS。systemd/Caddy 与独立部署冒烟仍归 CT29；真实模型与生产部署 not-run。
