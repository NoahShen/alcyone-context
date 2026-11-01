# CM1 整体集成初验（Lead）

**日期**：2026-10-29
**里程碑**：CM1 工程基础与 Ingestion（CT01～CT07）
**依据**：[CM1 任务与验收](CM1_任务与验收.md)、CT01 实施契约、各任务复核记录（CT04 §1～§20、CT05 §1～§8、CT06 §1～§7、CT07 §1～§6）
**状态**：Lead 整体初验完成，提交 Architect 做 CM1 整体独立复核；**CM1 整体通过前不启动 CM2、不公开发布**。

## 1. 执行结果（Lead 实跑）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 全工程常规检查 | `./scripts/check` | exit 0，**130 tasks executed**，BUILD SUCCESSFUL（1m43s），含编译 + 全部常规测试 + Spotless。日志 `.local/validation/cm1/lead/cm1-integration/check.log` |
| 原 VFS Runtime 独立消费兼容 | `./scripts/t31-consumer-check` | exit 0，两阶段 Runtime 创建/重开断言通过（项目既有受控入口）。日志 `t31-consumer-check.log` |

**WebDAV 口径**：CM1 全程未修改 `vfs/*` 或 `integration-tests/vfs/*`（`git diff --stat` 为空，81 基线 mismatch=0）；Context 的 HTTP/存储测试使用真实文件 SQLite + LocalFs，不触及 WebDAV 代码路径。沿用历史真实 WebDAV 43/43 独立证据，不重复执行（CT06/CT07 复核已认可该口径）。

## 2. 任务状态与逐项复核

| 任务 | 状态 | 独立复核记录 |
| --- | --- | --- |
| CT01 实施契约与兼容性核对 | DONE | 复核（探针 P1/P2/P3 通过、81 基线一致） |
| CT02 工程骨架 | DONE | 复核（依赖图/定向测试） |
| CT03 公共模型、Port、时间/ID | DONE（含公共能力迁移） | 复核（880/880 + Java copy 探针） |
| CT04 Schema、连接和生命周期 | DONE | 复核 §1～§20（F1/F2/F3 逐批收口；整体 973/973 + 消费验证） |
| CT05 Repository | DONE | 复核 §1～§8（B1～B3；整体 1050/1050 + 104 清单） |
| CT06 Config 与 Runtime | DONE | 复核 §1～§7（B1/B2；整体 1097/1097 + 118 清单） |
| CT07 Ingestion 与 CM1 集成 | DONE | 复核 §1～§6（B1/B2；整体 1142/1142 + 138 清单） |

- **CM1 7/7 任务全部 DONE**，逐项均有 Worker 自测 + Lead 初验 + Architect 独立复核，未以最后一次总测替代逐项审查。

## 3. 实际计数与源码身份

| 模块 | 源码 @Test | XML tests | 差额 |
| --- | --- | --- | --- |
| common | 23 | 23 | 0 |
| context/api | 26 | 26 | 0 |
| context/core | 11 | 11 | 0 |
| context/persistence-sqldelight | 155 | 155 | 0 |
| context/runtime | 75 | 75 | 0 |
| context/server | 19 | 19 | 0 |
| integration-tests/context | 15 | 15 | 0 |
| integration-tests/vfs（常规） | 107 | 107 | 0 |
| vfs | 711 | 711 | 0 |
| **常规合计** | **1142** | **1142** | **0** |

- `integration-tests/vfs` 另有 43 例真实 WebDAV（独立 `webdavTest`，歷史 43/43）。
- 最终源码身份：`context/` + `common/` 共 **138** 个源文件 SHA-256 清单 `.local/validation/cm1/lead/cm1-integration/source-manifest.json`。
- 81 个 VFS 基线文件逐项 SHA-256 **mismatch=0**；`vfs/`、`common/`、`integration-tests/vfs/` tracked diff 为空。

## 4. 验收门槛对照（CM1 任务书 §4）

| 门槛 | 结果 |
| --- | --- |
| CT01～CT07 逐项复核、不以总测替代 | 通过（见 §2） |
| 完整构建/格式/常规套件 + 必要消费回归；区分历史与本轮计数 | 通过（130 tasks；1142=1142；消费验证 exit 0；WebDAV 沿用历史 43/43） |
| 真实 SQLite 幂等/约束/事务/快照/拒启；HTTP 限额/Key 绑定/重试/通知失败/关闭直接断言 | 通过（CT04 155 例 + CT05/CT06/CT07 真实库与 HTTP 测试） |
| SDK 与 HTTP 共用业务；Core 无 Ktor/JDBC/YAML 实现依赖；VFS 生产源码不变；取消不吞、错误/日志不含 Key/正文/连接串 | 通过（`grep` 校验 Core 无 Ktor/YAML/JDBC；Server 不直连 Persistence；脱敏测试；VFS 零改动） |
| 不虚报 LLM/后续流水线能力 | 通过（未实现 LLM/Extraction/Organization/Dreaming/Map、Cron/Worker、完整权限、部署；如实记录） |

## 5. 接口契约、依赖边界与功能

- **公开 API（`context/api`）**：`AlcyoneContext : AutoCloseable`（`start`/`close`/`closeAsync`/`getStatus`/`ingestConversation`）、`ConversationTurn`/`ChatMessage`/`ChatRole`、`IngestResult`/`IngestStatus`、`RawMemory`/`OrganizedMemory`/`MemorySourceRef`、`ContextStatus`/`ContextLifecycleState`/`StorageStatus`/`WorkerStatus`、错误码与异常。未新增 CT07 以外的公共契约；业务入口仅冻结的 `ingestConversation`。
- **依赖方向**：`api → coroutines`；`core → api + common`；`persistence-sqldelight → api + core + common`（限定 JDBC 实现）；`runtime → api + core + persistence + vfs.runtime`；`server → runtime + core`（+ Ktor Server/JSON/YAML）。Core 无 Ktor Server/YAML/JDBC；Server 不直接依赖 Persistence/JDBC；`runtime` 生产依赖不含 Ktor Server。
- **实际可用功能**：配置加载/校验/映射（context/vfs/server 分区）、VFS 组装、显式数据库初始化、普通启动拒启保护、生命周期/并发/关闭、五类 Repository 与条件写、会话入库（幂等/冲突/限额/offset 契约）、最小 HTTP Ingestion 与静态 Bearer 认证、提交后通知 Port（受控替身）。
- **明确未实现**：LLM、Extraction、Organization、Dreaming、Memory Map/检索、Count-Idle/Cron/Worker 调度、持久化 Trigger Journal、动态 Runtime 注册/热更新、多用户权限、HTTPS 终止、部署与公开发布；`WorkerStatus` 仅类型定义、Runtime `workers` 返回空 Map，不虚报运行态。

## 6. 交付物与文档

- 任务文档归档 `docs/context/tasks/cm1-ct01`～`cm1-ct07`（任务说明、方案、交付、Lead 初验、架构与技术复核）；本文件与 `CM1_任务与验收.md` 记录里程碑状态。
- 证据统一在 `.local/validation/cm1/`（各任务 worker/lead/architect 子目录）。
- 未执行 `git add/commit`；未公开发布。

## 7. 结论

- **CM1 整体初验通过**：全工程 `check` 130 tasks 全绿、常规 **1142=1142** 差额 0、81 VFS 基线 mismatch=0、原 VFS Runtime 消费兼容、7/7 任务逐项复核、依赖与接口边界正确、无范围外能力虚报。
- 提交 **Architect 做 CM1 整体独立复核**；是否完成 CM1 由 Architect 判定（结论写入 `docs/context/milestones/cm1/milestone-review.md`）。**CM1 整体通过前不启动 CM2、不公开发布。**
