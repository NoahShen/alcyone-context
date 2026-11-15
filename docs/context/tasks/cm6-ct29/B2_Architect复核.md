# CT29 B2 Architect 独立复核

## 本次修改（2026-11-11，B2 APPROVED / DONE，放行 B3）

- 独立 clean 后普通 `:context:server:test` exit 0、31/31、1m13s，自动执行 startScripts→installDist→test，XML **106=106**、零失败/错误/跳过，无手工预建产物；日志 `/tmp/ct29-b2-architect-clean-server.log`。
- 独立全仓 `./scripts/check` exit 0、**137/137**、3m29s；14 模块 **2259=2259**、零失败/错误/跳过，日志 `/tmp/ct29-b2-architect-check.log`；Server Spotless 通过。
- build `72cb1f57…6529f`、Command `97b4b323…90364`、Main `4c4cd44a…288717`、Managed `9679ba81…e5616` 与 Lead 一致；Offline `a180dba5…`、Schema `ec727a53…` 不变。R1 Managed grace 已单独追认，R2 test dependsOn installDist 关闭。
- **B2 DONE；放行 B3 根 deploy/ systemd/Caddy/README 与 CT29 文档。** 不改生产/构建/根 README，B3 后仍须 Architect 复核，CT30～CT32 不放行，无 Git 写操作。

## 1. 真实进程结论

installDist public 入口真实 SIGTERM/SIGINT 成功 0；受控 close 失败 TERM/INT 4，无 143。hook/main 对同一 Managed 共享 closeJob，4 MiB 在途响应完整 drain，Context/VFS SQLite 可重开。端口冲突 4、stderr 固定 RUN_FAILED、无 BindException/stacktrace/秘密；fatal Error 委托原 handler，Library run 不改全局。

Application mainClass=StandaloneMainKt，test 依赖 installDist；普通 clean/check 可复现，不需手工预跑。Managed 1 秒仅 CIO flush/idle 收口，registry awaitDrained 与 CIO timeout/Runtime 顺序不变。

## 2. B3 边界

仅新增 `deploy/systemd/alcyone-context.service`、`deploy/caddy/Caddyfile`、`deploy/README.md` 及 `docs/context/tasks/cm6-ct29/{使用说明,验收记录}.md`；不改根 README。样例 ExecStart 与 installDist 一致、专用低权限用户/只读配置/持久化数据/Restart=on-failure/TimeoutStopSec=60；Caddy 仅 TLS 本机反代，不绕过 Key/Ready 鉴权边界。

macOS arm64 JVM/真实进程已通过；Linux systemd、Caddy 实际代理/证书未实跑必须标 not-run，不得仅静态样例宣称部署通过。交付附静态配置检查、真实已跑范围、全量 check 与 CT29 是否可收口证据。
