# CT29 Architect 技术裁定

## 本次修改（2026-11-11，D1～D4 批准，放行 B1）

- 批准 Lead 的只读方案与 B1/B2/B3 分批；本轮仅文档/技术裁定，无生产、测试、构建修改，无 Git 写操作。
- D1 公共纯同步 `ContextOfflineBackup`、D2 `init/upgrade/run` 与 0/2/3/4、D3 Application/installDist 和真实信号、D4 根 `deploy/` 样例均批准。当前 v1 无 `.sqm`，upgrade 仅先备份再 no-op，高低版拒绝。
- B1 仅 Persistence Offline Backup、Server Standalone 命令及各自新测试；不改 Application 构建、Managed、Schema、Runtime Factory、VFS 或业务算法。B2/B3 逐批复核，CT30～CT32 不放行。

## 1. D1 Offline Backup

公共签名 `snapshot(source: Path, target: Path): OfflineBackupResult` 纯同步、无 Runtime/VFS/协程。源只读打开，缺失/0字节/损坏/非当前版本失败且不写源；目标父目录预存在、NOFOLLOW 检查、CREATE_NEW 独占认领、不得覆盖；并发射击目标只允许一个成功。

复用同模块 `ContextSqliteBackup` 的 Xerial 底层快照，不复制第二套备份规则。若 Xerial 对已认领空目标有兼容问题，暂停报告最小内部实现调整，不得删认领后重开竞态窗口或改变在线 Backup 公共语义。

独立只读目标连接执行 `PRAGMA quick_check`，结果必须恰为一行 `ok`，再验 user_version、六表五索引和 committed turn 数。任何失败只清理本次成功认领的目标，不删源、源 WAL/SHM 或既有目标；公共异常只含安全固定分类，不含 SQL、秘密配置或任意 cause message。

优先新增 `ContextOfflineBackup.kt` 调用现有 internal；只有确需共享算法才定点改 `ContextSqliteBackup.kt`，并复跑现有 BackupRuntime/CT27 回归。

## 2. D2 CLI 冻结

```text
alcyone-context init --config <yaml>
alcyone-context upgrade --config <yaml> --backup-dir <existing-directory>
alcyone-context run --config <yaml>
```

- 无默认 run/init；未知命令/选项、重复/缺失参数、选项错用均用法失败。相对路径以进程 CWD 定位 YAML，YAML 内相对路径仍由 Loader 以 YAML 目录解析。
- init 先 Context+Server Loader 纯校验，再唯一调用既有 initializeDatabase；任何既有文件均拒绝，不能清空/覆盖。init/upgrade 不创建 VFS DB、不构造 Context/Managed/CIO、不启动 Worker/Cron。
- upgrade 先 Loader、源只读验版和完整离线备份，当前 v1 再 no-op；高/低版绝不 no-op 成功。无 `.sqm`，本批不实现/宣称真实迁移或迁移回滚。
- stdout 成功固定：init=`INITIALIZED`；upgrade=`NO_OP schema_version=1 backup_id=<仅文件名>`。不输出数据库/备份绝对路径、YAML、Key 或异常文本；backup_id 仅供在调用者给定目录定位。
- 退出码：0 成功/no-op；2 用法或配置无效；3 init/upgrade 的初始化、版本、备份、持久化失败；4 run 配置有效后的启动或关闭失败。stderr 仅固定安全 code；未知普通异常按所在命令归 3/4，CancellationException/VM fatal Error 不吞。`main` 统一映射 exitProcess，command runner 可直接测试。

## 3. D3/D4 与平台边界

B2 仅 Server build 增加 Gradle `application`、mainClass/applicationName 和真实进程测试；复用 Managed，不引入 shadow/第二套 Runtime。installDist 验证缺库/错版/端口冲突、Ready/权限、在途 drain、SIGTERM/SIGINT、关闭结果和 SQLite 重开；shutdown hook 只触发同一 close，主线程等待真实结果，重复 hook 不双重释放。

部署唯一目录为根 `deploy/`：`deploy/systemd/alcyone-context.service`、`deploy/caddy/Caddyfile`、`deploy/README.md`。B3 只样例/文档/静态检查与全量证据，不提前改根 README。macOS arm64 JVM/进程实跑不能替代 Linux systemd；Caddy 未安装或未实跑须标 not-run。

## 4. B1 验收

真实 SQLite：init 最新 v1/六表五索引、重复/0字节/损坏/高低版不覆盖、并发 init 单成功；upgrade v1 先备份后 no-op、独立 quick_check/结构/raw、目标存在/父目录缺失/源版本异常与源 SHA 不变；CLI 参数/退出码/固定输出、含空格路径、无 VFS/Ktor/Worker 副作用。完成 Spotless、Persistence+Server fresh、相关四模块与全量 check，再交 Lead/Architect 复核。
