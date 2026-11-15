# CT29 B1 Lead 初验

## 本次修改（2026-11-11，R1 关闭，独立初验通过，提交 Architect）

- R1仅新增Offline/Standalone及测试和批准的一行Persistence依赖；Offline SHA `a180dba54e2be3398cd6b5782612514c172b8f1b453257cc3413424d24f42c86`、Command `07b3ac5d6e0ee69388cb069586777498d182bc0ab78461262e035e181df63b78`、Main `a7d506f58d84119269cbd614e9a22386543f3d37c0d0e97bbe23698d791a5461`、build `976eaeb5…89186a`。
- 预存主目标/三种sidecar均拒绝且字节不变；fileKey替换保留他方文件；只清认领后本次sidecar。删除重复Schema清单，统一Factory六表五索引，11对象逐项缺失拒绝。独立quick_check恰一行ok、版本/turn/committed raw一致。
- RUN安装termination shutdownHook，start后await，不自动close；B1可注入termination，真实signal归B2。run缺库4且Context/VFS零创建；init既有zero/corrupt/low/high字节保护；备份失败无NO_OP/backup_id。
- Lead两模块fresh `/tmp/ct29-b1-lead-r1-two.log` exit0、29/29、29s，**192+97=289**、0bad；四模块 `/tmp/ct29-b1-lead-r1-four.log` exit0、32/32、1m1s，**45+180+192+823=1240**、0bad。
- Lead全仓 `/tmp/ct29-b1-lead-r1-check.log` exit0、132/132、2m29s；14模块 **2250=2250**、0 failure/error/skipped，明细 `/tmp/ct29-b1-lead-r1-xml.txt`。Spotless/compileClasspath `/tmp/ct29-b1-lead-r1-boundary.log` exit0，Persistence仅批准一行，无Application/mainClass。
- Managed `0bad2059…44f0`、ContextSqliteBackup `23a79675…`、Schema `ec727a53…aadd8ea3`不变；Server生产无JDBC/Repository/Executor import。提交Architect裁定B1；真实SIGTERM/SIGINT/进程和Linux部署not-run，B2/B3未启动，无Git写。

## 历史修改（2026-11-11，IN_REVIEW / R1，定点修复）

- 首轮Lead两模块fresh190+94通过，但sidecar无条件删除、重复错误Schema清单、RUN启动即退出及关键零副作用断言不足，R1退回。现以上述复验关闭。

## 1. 关键实现与证据

- `ContextOfflineBackup.kt:25-65` 只读源/目标及sidecar预检/CREATE_NEW/Xerial/校验；`:73-101` 统一missingObjects；`:135-180` checkpoint/sidecar/fileKey清理。
- `StandaloneCommand.kt:19-35` termination hook；`:38-86` 双Loader、固定退出码、init/upgrade/run；`:89-122` 严格参数。
- `ContextOfflineBackupTest.kt` 7例含raw/quick_check/错版/预存sidecar/11对象/并发/fileKey；`StandaloneCommandTest.kt` 10例含CLI矩阵/init保护/upgrade raw/run缺库。

## 2. 未验证

真实installDist、SIGTERM/SIGINT、端口/Ready/in-flight进程、Linux systemd/Caddy归B2/B3，未宣称。真实模型/WebDAV/迁移回滚not-run。无Git写。
