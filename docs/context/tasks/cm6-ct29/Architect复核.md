# CT29 Architect 最终复核

## 本次修改（2026-11-11，CT29 APPROVED / DONE）

- B3 R1 已仅修文档关闭：Context/VFS DB 与 storage 均为 `/var/lib/alcyone-context/...` 绝对路径，YAML 相对路径明确以 YAML 文件目录解析，不是 WorkingDirectory/CWD；未改 Loader/生产，静态断言与 `git diff --check` 通过。
- B1/B2 均已 Architect DONE。最终证据保持：clean 普通 Server 106/106 自动 installDist，全仓 `./scripts/check` 137/137，14 模块 **2259=2259**、零失败/错误/跳过；build/Command/Main/Managed/Offline/Schema SHA 与复核一致。
- 真实 init/upgrade、备份 quick_check、installDist、SIGTERM/SIGINT 0、close 失败 4、4 MiB drain、端口固定 RUN_FAILED、SQLite 重开通过；systemd/Caddy 静态样例符合边界。Linux systemd、Caddy、真实证书、生产部署、真实迁移/恢复回滚均 not-run，不宣称生产可用性。
- **CT29 APPROVED / DONE**；按 CM6 依赖放行 CT30 故障恢复、可靠消费与原子发布，先只读方案。CT31/CT32 不放行，CM6 未 DONE，无 Git 写操作。

## 1. 交付范围

- Persistence 公共 Offline Backup；Server init/upgrade/run、0/2/3/4、Application/installDist 与真实 shutdown hook；Managed 仅 CIO grace 1 秒定点追认。
- 根 `deploy/systemd/alcyone-context.service`、`deploy/caddy/Caddyfile`、`deploy/README.md` 与 CT29 使用/验收文档。
- 当前仅 Schema v1，无 `.sqm`；upgrade 是完整备份后 no-op，不是迁移成功。

## 2. 后续边界

CT30 不改 CT29 已验收 CLI/部署语义，故障恢复/可靠消费/Local FS 原子发布另立精确接缝。WebDAV publishNew 拒绝仍保留，真实收费模型和跨进程 cron/生产部署不属于本任务。
