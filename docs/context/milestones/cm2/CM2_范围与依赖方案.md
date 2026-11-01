# CM2 范围、CT08/CT09 依赖与待裁定问题（方案，不开发）

**日期**：2026-10-29
**状态**：CM1 全面复查已 CLOSED（2026-10-29），CM2 已放行；CT08 任务书见 [CT08 任务书](CT08_任务书.md)，先方案后实现
**依据**：`docs/context/开发计划.md` §3 CM2、技术实现设计专题 3/5、CT07 已验收的通知 Port 与 `IngestionService`

## 0. 本次修改（2026-10-29，CM2 方案整理）

- CM1 既有验收已通过；用户已条件授权 CM2。本次全面复查曾存在开放项（P1/F1～F4），**现已全部闭环**（整体回归 1188=1188），CM2 于 2026-10-29 放行，首任务 CT08。Q1～Q8 裁定已按本文件结论执行。

## 1. CM2 范围（Memory Extraction，CT08～CT12）

| 任务 | 范围 | 关键产出 |
| --- | --- | --- |
| CT08 LLM Adapter、预算与诊断 | Task→Profile 映射、Ktor Client+CIO、三类 JSON mode、有限重试、TokenEstimator、Usage | 受控 HTTP 验证多 Profile 不串密钥；总期限/Retry-After/取消；非法响应不修复；缺失 Usage 为未知；日志不含正文/密钥 |
| CT09 Count/Idle、Cron 与 Extraction Scheduler | 单 Worker、Session Counter、Cron 基础设施、Trigger 接线 | Idle 用**所有状态** Turn 的 `created_at`；并发触发、队尾再入队、失败消费计数、溢出/退出竞态；重启依正常 Cron，无 StartupRecovery |
| CT10 固定 Snapshot 与预算折半 | 最新 Pending 锚点、连续窗口、短 ReadContext、32→16→8 折半 | timestamp/id 排序、补录隔离、Target/History；仅 N=1 确定性超限标 failed |
| CT11 Extraction 与原子提交 | 版本化 Prompt、整批校验、稳定来源映射、Raw/Pending/Target 原子提交 | 零结果成功、非法来源整批拒绝、目标状态冲突回滚、ID 碰撞有限重试、提交后通知失败不重提取 |
| CT12 CM2 集成与评测 | 真实 Runtime/SQLite + Fake LLM、固定语义样本 | 异常后 Trigger 继续；窗口外 Turn 不变；Prompt/Profile/样本版本与真实模型结果分开归档 |

**边界**：不做 Organization/Dreaming/Map；不建持久化任务表、Snapshot 表、Journal；不引入独立 MQ；不改 VFS。

## 2. 与 CT08/CT09 的依赖关系

### 2.1 CT08 依赖（前置于 CT09/CT10/CT11）
- **CT07 已交付**：`context.llm.profiles/tasks` 配置目前 CT06 接受但**不消费**；CT08 首次消费，需在 Loader 增加校验（ENV 展开、未知字段、类型/范围、Profile 引用存在性）。这是**对 CT06 配置边界的扩展**，需 Architect 确认口径（保持 `context.llm.*` 严格校验后，未配置时不启动 LLM 能力）。
- **依赖新增**：`context/core` 需定义 LLM Port（`ExtractionLlm` 或等价），实现放 `context/runtime`（Ktor Client 已声明但当前未使用）；Core 不得依赖 Ktor（与 CM1 边界一致）。
- **TokenEstimator**：纯逻辑，放 Core；需与 LLM Provider 口径解耦，避免把某模型 tokenizer 硬编码进 Core。

### 2.2 CT09 依赖（前置于 CT10/CT11/CT12）
- **CT07 通知 Port**：`IngestionNotifier.onCreated(sessionKey)` 现为受控替身/no-op；CT09 接线为 Count Trigger。需确认**是否扩展 Port 签名**（当前只有 sessionKey，Count 需要统计 pending 数 → Port 内查库即可，不必改签名）。
- **Cron 基础设施**：`cron-utils` 已在 runtime 依赖但未使用；CT09 首次使用。需裁定「Cron 时区固定 Asia/Shanghai」与「上次扫描未结束则跳过重叠」的实现位置（runtime 内单一调度器 vs Core Port）。
- **单 Worker 全局串行**：需定义调度器生命周期与 `AlcyoneContext.start/close` 的集成点（start 启动、close 停止并等待在途）；不得复用通知协程 scope（那是 fire-and-forget）。
- **Idle 判定依赖 CT05**：`findIdleSessions(idleBefore)` 已验收（全状态 MAX created_at + 必须有 pending），CT09 直接复用。

### 2.3 后续依赖
- CT10 依赖 CT05 `sessionWindow`/`latestPending`/`countPending`/`idRange`（已验收）。
- CT11 依赖 CT05 TxOps（Raw insertBatch、Pending enqueue、Conversation markComplete/Failed 条件行数，已验收）。
- CT12 依赖 CT08～CT11 全部与 CT07 Runtime。

## 3. 需 Architect 裁定的问题

| # | 问题 | Lead 建议 |
| --- | --- | --- |
| Q1 | CT08 是否允许修改 CT06 的 `context.llm.*` 配置处理（从「接受但不校验」改为「严格校验并生效」） | 允许，但需保持「未配置 LLM 时 CM1 行为不变、不启动 LLM」；配置校验错误具名且不泄密 |
| Q2 | LLM Port 归属 | Core 定义接口（无 Ktor），runtime 提供 Ktor 实现；Core 单测用 Fake |
| Q3 | TokenEstimator 口径 | Core 纯逻辑，按字符/UTF-8 字节的保守估算 + 可替换策略；不承诺与具体模型 tokenizer 精确一致，评测阶段记录偏差 |
| Q4 | CT09 调度器归属与生命周期 | runtime 内单一调度器（start 启动/close 停止并等在途），Core 只定义 Trigger/Port；不建持久 Journal |
| Q5 | Count Trigger 是否需改 `IngestionNotifier` 签名 | 不改；Count 逻辑在实现内查库（复用 `countPending`），保持 CT07 已验收契约 |
| Q6 | Cron 时区与重叠策略实现位置 | runtime；固定 `Asia/Shanghai`；上次未结束跳过并记录，不排队堆积 |
| Q7 | 真实模型评测（G9）何时进入 | CT12 起按阶段积累固定脱敏样本；CM2 不承诺语义质量通过，只交付可评测基线与 Fake 测试 |
| Q8 | 本次复查 P1/P2/P3 与 F1～F4 的修复顺序 | P1（连接所有权泄漏）+ F1（close 虚报）优先；F2/F3（HTTP/配置）次之；P2/P3 需先裁定契约强度；F4 文档随修复同步 |

## 4. 明确不做（避免范围蔓延）
- 不实现 Organization（CM3）、Dreaming（CM5）、Map/Retrieval（CM4）、备份/部署（CM6）。
- 不新增表/索引/`user_version`；不建独立 MQ、持久化发送队列、Snapshot 表、StartupRecovery。
- 不修改 VFS 生产代码；不把 LLM 密钥写入日志/异常/`toString`。
- 不在 CM2 承诺模型语义质量（由评测与后续里程碑承担）。

## 5. 下一步

1. ~~等 Architect 对 Q1～Q8 的裁定~~ → 已裁定并执行。
2. ~~逐项修复复查开放项~~ → 8 项全部 APPROVED，F4 与整体回归完成，CM1 复查 CLOSED。
3. **已提交 CT08 任务书并放行**：Lead 先交方案，Architect 批准后实现；CT09 待 CT08 DONE。
