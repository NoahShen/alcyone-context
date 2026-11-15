# CM6 范围与依赖方案

## 本次修改（2026-11-12，CT30 DONE，CT31 待只读方案）

- Architect 接受 CT30 B4 只读验收矩阵，确认恢复 journal/显式 reconcile、v4 reliable consumer、Local FS directory sync/原子发布及 **312 XML / 2324 tests / 0/0/0** 全量证据闭环，见 [CT30 B4 复核](../../tasks/cm6-ct30/B4_Architect复核.md)。
- **CT30 APPROVED / DONE；CM6 仍 IN_PROGRESS。** CT31/CT32 保持 LOCKED，下一项只能先形成 CT31 独立只读方案。真实模型语义评测、Linux/WebDAV durable、生产部署和真实断电继续 not-run；无新增测试、代码改动、部署或 Git 写操作。

## 历史修改（2026-11-11，CT28 DONE，CT29 放行）

- CT28 B2c 与整体经 Architect 独立复核 DONE：Server 87/87、全量 14 模块 **2234=2234**、check 132/132；HTTP/Backup/managed/Schema/依赖边界通过，见 [最终复核](../../tasks/cm6-ct28/B2c_Architect复核.md)。
- 放行 CT29 初始化、升级与部署入口；CM6 仍 IN_PROGRESS，CT30～CT32 按开发计划继续，不得以 CT29 单独宣称里程碑完成。无 Git 写操作。

## 历史修改（2026-11-11，CT28 B2a/B2b DONE，B2c 放行）

- CT28 B2a/B2b 均经 Architect 独立复核 DONE；全量 14 模块 **2220=2220**、check 132/132，Schema 与既有 Runtime 边界通过。B2c 补齐手动 Dreaming/Backup HTTP、managed 生命周期及 CT28 验收。
- CM6 保持 IN_PROGRESS，CT28 未整体 DONE，CT29/systemd/Caddy 独立部署不提前开始；无 Git 写操作。

## 历史修改（2026-11-11，CT28 B1 完成，B2 接缝核对放行）

- CT28 B1 经 Architect 独立复核 **APPROVED / DONE**：四套件 32/32、Server 62/62、全量 `./scripts/check` 132/132、14 模块 2188=2188，Schema 与生产 SHA 未漂移。
- **CM6 保持 IN_PROGRESS**：CT27 DONE（local-atomic only），CT28 B1 DONE、B2 接缝核对放行，CT29 排队。B2 仍须精确 API/Runtime/Persistence 写集合与 Architect 技术确认，不提前宣称 CT28 完成。
- 用户连续授权与三级验收不变；真实模型、生产部署 not-run，无 Git 写操作。

## 历史修改（2026-11-11，CM6 连续授权；CT28 方案中）

- 用户明确授权继续 CM6 至全部完成，按 Architect → Lead → Worker 推进；CT28、CT29 不再需要逐项人工授权。技术任务书、实现边界、独立复核和测试验收仍是必要门禁。
- 当前顺序为 **CT27 DONE → CT28 方案/实施 → CT29 最终验收**。CT28 Architect 正在形成 `docs/context/tasks/cm6-ct28/{CT28_任务书,Architect裁定}.md`；尚未修改 CT28 生产代码。

## 历史修改（2026-11-11，CT27 R2 收口）

- Architect 独立复核裁定 **CT27 PASS / DONE**，CM6 保持 **IN_PROGRESS**；证据与限制见 [CT27 Architect 复核](../../tasks/cm6-ct27/Architect复核.md)。
- CT27 为 **local-atomic only**：Local FS 已验证；WebDAV 安全原子发布未实现，并明确拒绝 Backup。真实模型、跨进程长期 cron、生产部署未验证。
- 当时状态：CT28/CT29 LOCKED，需独立授权与任务书；本轮无 Git 写操作。

## 历史修改（2026-11-10，CM6 授权启动）

用户明确授权 CM6（备份、部署与最终验收）。Git 基线 `3c7d5c3`（CM1～CM5 已提交推送）。真实模型评测待用户配置后另行测试。

**日期**：2026-11-10
**状态**：**CM6 IN_PROGRESS（CT27～CT30 DONE；CT31/CT32 LOCKED）**
**依据**：用户 2026-11-10 授权；技术实现设计 §9、§10.7（M6）
**基线**：14 模块 **2135=2135**；`./scripts/check` 131/131；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；git `3c7d5c3`

## 1. 里程碑目标

M6 完成 Context System 的备份、HTTP 部署与最终验收，使系统可独立部署、访问、备份。

| 任务 | 范围 | 依赖 |
| --- | --- | --- |
| CT27 SQLite Online Backup | `DatabaseBackupService`、Online Backup API 适配、VFS 安全发布、quick_check、Manual/Cron 单任务 | CM5 全部 |
| CT28 HTTP Read/Admin 端点 | Search/Get/GetRaw/Resource/Bootstrap HTTP API、三类 Key 鉴权、健康探针 | CT27 |
| CT29 最终验收 | 跨流水线故障矩阵、端到端全链验证、独立部署冒烟 | CT28 |

## 2. 跨任务固定边界

- **备份**：仅 `context.db` SQLite Online Backup；不含 VFS 状态库；VFS 发布安全完成；不覆盖旧备份；Manual/可选 Cron 单任务 ALREADY_RUNNING。
- **HTTP**：Ktor 本机监听；三类 Key（Ingestion/Read/Admin）；不实现 OAuth/ACL/多租户；健康探针轻量。
- **部署**：单 JVM 单 Runtime；systemd 管理；不引入 K8s/MQ/服务发现。
- **非 MVP**：自动恢复、增量备份、备份清理、统一面板、动态 Key 热更新。
- 真实模型 not-run（用户后续配置后测试）。

## 3. 任务依赖

```
CM5 DONE → CT27（Backup）→ CT28（HTTP Read/Admin）→ CT29（最终验收）
```

## 4. CM6 收口标准

1. SQLite Online Backup 快照可独立打开且 quick_check 通过。
2. HTTP 三类 Key 权限隔离、Ingestion Runtime ID 绑定。
3. 健康探针 Live/Ready 正确反映核心依赖状态。
4. 跨流水线故障测试通过（备份与业务并发、HTTP 取消传播）。
5. 全部核心不变量与端到端测试通过。

## 5. 当前状态

- **CM6 IN_PROGRESS**；CT27～CT30 APPROVED / DONE，CT31/CT32 LOCKED。CT30 只证明当前 macOS arm64 与故障组件边界，不证明真实断电、Linux/WebDAV durable、默认自动恢复或生产部署。
- 后续仍按 Architect → Lead → Worker 与逐任务独立复核推进；CT31 先做只读方案，技术复核与验收不放宽。真实模型 not-run。
- 无 Git 写操作（收口后由用户决定提交）。
