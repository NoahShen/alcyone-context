# CT28 B2c Architect 最终独立复核

## 本次修改（2026-11-11，B2c/CT28 APPROVED / DONE，放行 CT29）

- 独立 Server fresh 串行复跑 exit 0、27/27、16s、XML **87=87**，零失败/错误/跳过，日志 `/tmp/ct28-b2c-architect-server-serial.log`。首次与全仓并行编译发生 Kotlin daemon 失败，保留 `/tmp/ct28-b2c-architect-server.log`，未当作测试证据。
- 独立全仓 `./scripts/check` exit 0、132/132、2m32s；14 模块 **2234=2234**、零失败/错误/跳过，日志 `/tmp/ct28-b2c-architect-check.log`。Server Spotless 通过。
- Admin POST 鉴权/body/状态/取消矩阵、Local FS 独立 JDBC quick_check/raw、managed drain/single-flight/真实 BindException、外部所有权和依赖边界通过；B2a 四 SHA、Backup 分类批准 SHA、Managed R1 SHA、Schema 均一致。
- **B2c DONE；CT28 整体 DONE；放行 CT29 初始化、升级与部署入口。** CM6 仍 IN_PROGRESS，开发计划还有 CT30～CT32，不得以 CT29 单独宣称里程碑完成。真实模型、WebDAV 原子发布、Linux systemd/Caddy 与生产部署 not-run。无 Git 写操作。

## 1. A05～A07 关闭

- 手动 Dreaming/Backup 只经公共 SDK，严格空 body/`{}`，稳定 DTO；401/403/404/413/503/409/500/504 与 SDK CE 传播覆盖，无秘密文本。
- HTTP Local FS 备份产物独立只读打开，`PRAGMA quick_check=ok` 且已提交 raw 可读；无 WebDAV 普通写兜底。
- managed close 封准入→正常 drain→停止 CIO→closeAsync Runtime；真实 Dreaming 200 与流自然结束先于 Runtime close。并发/重复 start/close 共享真实结果，真实端口冲突清理一次。
- 外部 Runtime 入口不 start/close Context。vfs.runtime 仅一行 implementation 满足公共 Factory 签名，Server 生产无 VFS/JDBC import。

## 2. CT29 技术边界

CT29 只做显式初始化/独立升级、单 YAML Standalone 启动入口、systemd/Caddy 样例与受控部署验证。普通启动不得隐式创建/迁移；升级前备份、错版拒绝、失败停止；真实平台未运行时明确标注，不用 macOS 代替 Linux systemd 实跑。CT30 故障恢复、CT31 语义质量、CT32 打包文档与最终验收不在本批。
