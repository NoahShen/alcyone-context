# CT25 B1 · Lead 初验

## 0. 本次修改（2026-11-09，B1 Lead PASS / Architect APPROVED / DONE）

- Lead 独立复算 14 个交付文件与 7 个关键基线 SHA，审查 Reflection 七类 Prompt 原则、严格 Parser/Validator、Stage、真实 SQLite Committer 测试与生产零接线边界；独立复跑三模块定向测试、Spotless 与 `git diff --check`，全部通过。
- Architect 复核结论 **APPROVED / DONE**，详见 [B1 Architect 复核](B1_Architect复核.md)。**CT25 仅 B1 DONE，B2/CT26 LOCKED**。

**日期**：2026-11-09
**状态**：**B1 Lead PASS / Architect APPROVED / DONE；CT25 未 DONE；B2/CT26 LOCKED**
**初验对象**：[B1 Worker 交付](B1_Worker交付.md)、[B1 委派单](B1_委派单.md)、Worker `06fe6ea7-4364-438b-a6be-645b604528db`
**依据**：[Architect 裁定](Architect_裁定.md)、[CT25 任务书](CT25_任务书.md)

## 1. 独立验收证据

| 命令/检查 | 独立结果 |
| --- | --- |
| `./scripts/dev gradle :context:core:test :context:runtime:test :context:persistence-sqldelight:test --rerun-tasks --console=plain` | exit 0，1m7s，`BUILD SUCCESSFUL`，29/29 tasks executed |
| 上述测试 XML | Core 179、Runtime 778、Persistence 183，合计 **1140=1140**；failures/errors/skipped=0/0/0 |
| `./scripts/dev gradle :context:core:spotlessCheck :context:runtime:spotlessCheck :context:persistence-sqldelight:spotlessCheck --rerun-tasks --console=plain` | exit 0，10s，`BUILD SUCCESSFUL`，13/13 tasks executed |
| `git diff --check` | exit 0，空输出 |
| 14 个交付代码/资源/测试 SHA | 与 Worker 交付 §2 逐项一致，14/14 |
| 7 个关键基线 SHA | Coordinator、BatchProcessor、TopicBatchProcessor、EvidenceStage、Evidence DTO、ExtractionCommitter、ContextSchema.sq 全部一致，7/7 |

沙箱内首次 Gradle 启动因 FileLock socket 限制失败；按既有正常环境方式重跑后获得上述有效结果。未修改脚本、依赖或生产配置规避环境问题。

Lead 未重复运行全量 `./scripts/check`；Worker 报告的 exit 0、131/131 tasks、14 模块 2108=2108 已审阅并保留为其交付证据，不冒充 Lead 独立执行结果。14 模块 source 与 XML 差额为 0；新增 B1 五类测试 XML 为 45/45。

## 2. 代码与测试有效性

- **Reflection 协议与来源**：Parser 严格要求唯一 `insights` 与 `content/sourceTurnIds`，拒绝错误类型、非整数、重复 key、围栏、解释及非法 JSON；Validator 将当前 Topic 内部 Turn ID 转为稳定四元 `ConversationTurnRef`，稳定去重并对歧义/越权 fail closed。
- **Prompt 七类原则**：实际资源逐项包含证据边界、Organized 基准、事实时间、明确纠正、同源去重、无增量空结果、新证据推动变化与严格 JSON；测试含逐片段内容断言，不是只测 Schema 或方法存在。
- **Stage/LLM**：Covered 跳过，Needs 每 Topic 恰一次且串行；Budget 等号/超限、Schema 有无、截断、后续 Topic 失败、CE 与 Binding 配置错误均有断言。任一失败不返回部分结果。
- **真实 SQLite Commit**：成功/回滚断言实际查询 Raw、Pending、Checkpoint 与 Conversation 状态；覆盖 0 Insight、多 Raw、ID 空洞、范围/CAS、insert/enqueue/CAS 行数与异常、碰撞重试/耗尽、非碰撞不重试及 CE。Extraction 状态保持 PENDING。
- **范围/接线**：B1 白名单外生产/测试/资源变更为 0；既有 CT22～CT24 tracked diff 保持，不回滚。未接 Coordinator、Batch Processor、wakeup、Factory/Impl/AlcyoneContext，无 COMMIT 后通知，无真实模型调用。

## 3. 计数勘误与结论

任务提示中的“51 新增测试”与实际源码/XML 不符。独立计数为 **45**：Core DTO/Validator 9 + Prompt 5，Runtime Parser 8 + Stage 10 + Commit 13。45 个测试完整覆盖委派单强制矩阵，数量勘误不构成实现阻断；Worker 交付本身已使用正确的 45 与 2108 总计。

未发现需要退回 Worker 的生产缺陷、原子性证据缺口或越权接线。**B1 Lead 初验通过，提交 Architect 复核；Architect 已批准 DONE。B2/CT26 不自动放行。**

