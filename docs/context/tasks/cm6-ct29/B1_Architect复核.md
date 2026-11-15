# CT29 B1 Architect 独立复核

## 本次修改（2026-11-11，B1 APPROVED / DONE，放行 B2）

- 独立 Persistence+Server fresh 串行复跑 exit 0、29/29、37s，XML **192+97=289**、零失败/错误/跳过，日志 `/tmp/ct29-b1-architect-two.log`。全仓首次并行编译冲突失败，未当作测试证据；串行 fresh `./scripts/check` exit 0、132/132、2m27s，日志 `/tmp/ct29-b1-architect-check-serial.log`。
- 独立 14 模块 **2250=2250**、零失败/错误/跳过；Persistence/Server Spotless 通过。Offline `a180dba5…42c86`、Command `07b3ac5d…63b78`、build `976eaeb5…89186a` 与 Lead 一致。
- R1 sidecar/fileKey/统一六表五索引/raw/quick_check、run 等待与零副作用均关闭；Managed `0bad2059…44f0`、ContextSqliteBackup `23a79675…`、Schema `ec727a53…aadd8ea3` 不变。Server 仅批准 Persistence 一行，无 JDBC/Repository/Executor。
- **B1 DONE，放行 B2 Application/installDist 与真实进程/SIGTERM/SIGINT。** B3、CT30～CT32 不放行，无 Git 写操作。

## 1. 验收结论

Offline 源只读验版/结构，目标与 sidecar 预检、CREATE_NEW 并发单成功；Xerial 复用底层、目标 checkpoint/独立只读 quick_check 恰一行 ok，committed raw/turn/version 一致。失败只清自有目标，fileKey 替换保护他方文件，预存目标/sidecar 字节不变。

CLI 严格参数与固定输出/退出码；init 只建 Context v1、不覆盖 zero/corrupt/low/high/有效库，并发单成功；upgrade 先完整备份再 v1 no-op，失败无 NO_OP/backup_id；init/upgrade 不建 VFS、不开端口/Worker。run 等待 termination 后 close，不再启动即退出。

## 2. B2 边界

仅 Server build 增加 `application`、mainClass/applicationName 和真实 installDist 子进程测试，复用现有 Managed/Command，不引入 shadow 或第二套 Runtime。验证缺库/非法配置/端口冲突、Ready/权限、在途 drain、SIGTERM/SIGINT、退出码与 SQLite 重开；macOS arm64 实跑不能代替 Linux systemd。B3 根 deploy 样例待 B2 复核。
