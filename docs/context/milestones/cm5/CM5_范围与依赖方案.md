# CM5 · Dreaming 范围与依赖方案

## 本次修改（2026-11-10，CT26 追溯性方案 R1）

- CT25 保持 DONE；CT26 预实现不等于批准。已补 [CT26 任务书](CT26_任务书.md) 与 [方案核对](../../tasks/cm5-ct26/方案核对.md)，退回 Lead→Worker 修默认 UNAVAILABLE、manual 部分绑定启动前失败、关闭等待手动 run 与真实集成。
- CT26 确定性 B1/B2 获放行；真实收费模型可选 B3 仍需用户另行明确授权。CT26/CM5 未 DONE，无 Git 写操作。

## 本次修改（2026-11-10，CT25 B2 Architect R1）

- CT25 B2 独立构建/边界通过，但普通通知失败、真实 Commit 故障、失败零副作用与非空 Topic 0 Insight 的强制验收覆盖不足，判定 **IN_REVIEW / R1**，见 [B2 Architect 复核](../../tasks/cm5-ct25/B2_Architect复核.md)。
- 已退回 Lead 定点补测试，生产组件与 Schema 未改；**CM5 保持 IN_PROGRESS，CT25 未 DONE，CT26 LOCKED**，无 Git 写操作。

## 0. 本次修改（2026-11-09，CM5 授权启动）

用户明确授权 CM5（CT22～CT26）并要求无阻断问题时不停止直到完成。Git 基线 `f391192`（CM1～CM4 已提交推送）。本方案锁定 CM5 范围、依赖与执行边界；按 Architect → Lead → Worker 流程推进，CT22 起逐任务裁定与复核。

**日期**：2026-11-09
**状态**：**CM5 IN_PROGRESS（CT22～CT24 DONE；CT25 B2 R1；CT26 LOCKED）**
**依据**：用户 2026-11-09 授权；[开发计划](../../开发计划.md) §CM5；技术实现设计 §8.1～8.8、§10；[CM4 Milestone Review](../cm4/milestone-review.md)
**基线**：14 模块 **1885=1885**；`./scripts/check` 131/131；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；git `f391192`

## 1. 里程碑目标

实现 Dreaming（跨会话增量反思）完整生产链：

```text
Manual runOnce（默认无 Cron；Cron 可配置仅请求启动）
  → 读取 Checkpoint C0 与固定上界 U = MAX(conversation_turn.id)（同一 ReadContext）
  → (C0, U] 按 ID 升序、Token Budget 动态分批（pending/complete/failed 均参与）
  → 每批：Topic Discovery → 批内归并（可选）→ 三层历史取证 → Reflection
  → 单批全 Topic 校验通过 → 单短写事务原子提交 Raw(DREAMING) + Pending + Checkpoint CAS
  → 提交后通知 Organization（失败不回滚，由 Org Periodic 补偿）
```

## 2. 任务拆分与依赖

| 任务 | 范围 | 依赖 |
| --- | --- | --- |
| CT22 调度与固定增量范围 | §8.1～8.2：`DreamingService.runOnce()`、单 Worker/ALREADY_RUNNING、可选 Cron 跳过、Checkpoint + 固定 U、预算分批、超长 Turn 不跳过 | Schema `dreaming_checkpoint`（已有）、ConversationReadOps MAX（已有）、LLM 基础设施（CT03/CT08） |
| CT23 Topic Discovery 与批内归并 | §8.3：Prompt/Schema v1、临时 Topic DTO、严格来源校验（本批输入）、轻量 Consolidation、空 Topic 合法 | CT22 |
| CT24 分层取证与来源核实 | §8.4：Organized 层 Novelty Check → 全历史 Raw 分批扫描 → 反查关联 Organized → 关键 Conversation 追溯；同 Turn 多 Raw 去重 | CT23、Memory Scanner 复用（CT20 B1）、`organized_memory_source` 反查（已有） |
| CT25 Reflection 与逐批原子提交 | §8.5～8.6：Reflection Prompt/Schema、`content+sourceTurnIds`、Core 转 `ConversationTurnRef`、`generatedBy=DREAMING`、Raw/Pending/Checkpoint 单事务 CAS、ID 碰撞重试、Commit 后通知 | CT24、RawMemoryTxOps（已有）、OrganizationPendingTxOps（已有） |
| CT26 CM5 集成、重启与语义评测 | §8.7～8.8、§10：多批执行、关闭取消、**真实子进程重启续跑**、失败矩阵、固定集评测归档 | CT25 全部 |

## 3. 跨任务固定边界（全部任务共用）

- **单 Worker**：运行中手动触发返回 `ALREADY_RUNNING`；Cron 触发跳过；无持久队列。
- **默认无 Cron**：未配置 `context.dreaming.cron` 时仅手动；配置后 Cron 只请求启动不等 LLM。
- **不改 Schema**：`dreaming_checkpoint`（已有表 id=1 行）与全部现有表不动；Schema SHA 保持 `ec727a53…`。
- **原子提交**：每批一次短 `database.write`；LLM/VFS 调用一律在事务外；CAS 行数恰为 1。
- **不改 Extraction 状态**：pending/failed Turn 照常参与 Dreaming。
- **超预算**：单 Turn 超 Topic Discovery 预算 → 本批失败保水位，不截断/跳过/建跳过表。
- **不建**：Topic 表、跨批 Topic 持久化、Deferred Review、Embedding/向量、递归 Reflection、Dreaming Journal、StartupRecovery。
- **真实模型 not-run**：所有 LLM 由 Fake/stub 驱动；语义质量留给后续授权。
- **组件化推进**：各任务先 Core DTO/Validator/Prompt + Runtime 组件与测试，生产接线集中后置批次（与 CM2～CM4 同模式）。

## 4. 验收基线

- 每任务：定向测试 + 全量 `./scripts/check` + 14 模块 source`@Test`=XML 差额 0 + 81 VFS 基线（75+6 CT17 已批准）+ Schema 未变 + Core 无 Ktor/serialization。
- CM5 收口：CT26 提供集成/重启/评测证据 + 收口报告 + Architect milestone review。
- 无 Git 写操作（收口后由用户决定提交）。

## 5. 当前状态

- **CM5 IN_PROGRESS**；CT22 待 Lead 只读方案核对与 Architect 裁定。
- CT23～CT26 LOCKED；CM6 未启动。
- 真实模型 not-run。
