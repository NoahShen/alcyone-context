# CT29 B3 Architect 复核

## 本次修改（2026-11-11，IN_REVIEW / R1 文档路径基准）

- B3 仅根 deploy 三文件与 CT29 使用/验收文档，未改生产/构建/根 README；service 低权限/只读/状态目录/Restart/SIGTERM/60s/硬化/journal 与 Caddy 单一本机 TLS 反代静态检查通过。
- **R1：deploy/README.md 错写“相对路径以 service WorkingDirectory 为基准”。** 实际 ContextConfigLoader/VFS Loader 冻结为以 YAML 文件所在目录解析；样例 YAML 位于 /etc/alcyone-context，按现文会在 /etc 建库/存储并被 ProtectSystem=strict 拒绝。文档与真实 init/run 契约冲突，不能标 DONE。
- 退回 Lead 仅修文档：示例数据库和存储使用 `/var/lib/alcyone-context/...` 绝对路径；明确 YAML 内相对路径以 YAML 文件目录为基准，不是 systemd WorkingDirectory/CWD。不要为迁就文档修改 Loader/生产语义。
- 同步使用说明/验收中的路径说明与静态断言，复跑 `git diff --check`、B2 全量证据引用核对。Linux systemd/Caddy/真实证书仍 not-run；CT29 未 DONE，CT30～CT32 不放行，无 Git 写操作。
