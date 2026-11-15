# CT31 B3 新 Architect 方案核对

## 本次修改（2026-11-13，调度层故障后的受限接管实现）

- Pi Worker 也在实现中发生角色串扰，已归档且 R1 文件零改动。继续创建 Agent 不是有效推进；由 Architect 直接修改**评测工具专属** R1，生产业务算法保持不动，Lead 仅独立复验与真实运行。
- 这是调度故障后的例外技术路径，不改变验收独立性：Architect 不自行签 DONE，真实 smoke/72、Lead 初验和人工语义复核仍为必须证据。

## 本次修改（2026-11-13，Worker 投递故障的技术裁定）

- 两个 `codex-magpie/group/worker` 会话均出现 `lastUserMessageAt=null`、prompt 未投递、零改动，已由 Lead 归档。不是技术方案拒绝或用户撤销授权，不再重建同类型 Worker。
- 保持 Lead `cb727471…` 负责集成、验收和真实运行；由 Architect 创建唯一 `pi/my_magpie/group/worker` 实现 Worker，角色仍是 Worker，禁止管理 Agent。R1 写集合、离线测试与独立验收门禁不变。

## 本次修改（2026-11-13，并行 Worker 清理与 R1 证据更正）

- 四个并行 Worker 已归档且零改动，唯一 Lead 保留。现有 report `modelCalls` 只统计 `SampleResult.calls`，不包含 Judge；接受去重 task 统计和独立 Judge usage，但不接受虚构的“Judge 混入生产调用”修复。
- 只读审查确认 Search 的 Scanner 列表捕获过早、Dreaming canonical raw/source 被重写及输入 Evidence 链接不可靠、生产异常时 recorder 丢失。上述与取消/异常报告落盘进入唯一 Worker R1，修复后再真实 smoke。

## 本次修改（2026-11-13，按实际磁盘接管 B3，放行实现接管与真实评测）

- 旧 Lead 已归档，不复用其历史状态；B3 runner 已有未提交实现，但尚无有效 Lead/Architect 独立验收。新建 Lead 接管现有文件，Worker 只修明确缺口，不重写工作区。
- 用户已明确授权真实模型调用、CT31 脱敏样本/内部评测 Prompt/派生输出外发，费用不设上限；只限 canonical 测试数据，不扩大到仓库其他内容、个人数据或凭据。API key 不读取、不打印、不进入报告。
- 采用先 smoke、后六类 72 条生产组件评测、再全量工程回归及人工 H1/H2/H3 复核的顺序。Provider 失败、协议失败、语义失败分开记录；Judge 不是独立模型或真人复核。

## 1. 当前事实与基线

- B2 APPROVED / DONE，canonical 六类各 12 条，共 **72=28 IMPORTED+44 SYNTHESIZED**，见 `B2_Architect复核.md`。B3/CT31 未 DONE，CT32 LOCKED，CM6 未 DONE。
- 当前实现是 `NEW_ARCHITECT_HANDOFF.md` 所述的 B3 runner：独立 `contextRealModelEval`、真实生产组件装配、SQLite 持久化、逐样本 artifact、受控 Judge 和离线测试。该实现没有经独立复核，不能直接宣称可用。
- 工作区含用户及多个历史任务的未提交修改。严禁 reset/checkout/clean/stash/rebase/commit/push；不得覆盖或删除无关改动。
- 本地 `.local/ct31/model.yaml` 存在，密钥由 `${ALCYONE_EVAL_API_KEY}` 注入，对应环境文件存在且非空。本次不读取或输出其值。

## 2. 验收矩阵

| 层次 | 必须证明 | 不接受的替代 |
|---|---|---|
| 离线 runner | canonical loader SHA、六类生产对象转换、协议失败隔离、无密钥泄漏、默认无网络 | 仅 dry-run、Fake、模型 Judge 自评 |
| smoke | `ext-001` 真正到达生产 Extraction→SQLite，原始请求/响应、usage、耗时、Prompt/Schema SHA、脱敏错误落盘 | HTTP 成功或离线协议通过 |
| 六类真实评测 | Extraction、Scanner、Governance、Search Scanner→Rerank、Memory Map、Dreaming 全部 72 条；真实生产组件、真实 SQLite、逐样本结果 | 只覆盖六条、离线 Fake、跳过失败样本 |
| Search | Scanner 与 Rerank 候选集合、ID 顺序、qrels/分母、空候选不调用分别保留 | 把旧离线指标当作真实模型分数 |
| Governance | NEW/MERGE/UPDATE、候选 ID 权限、内容/时序/来源、Pending/数据库实际变化 | 只验证 JSON 可解析 |
| Dreaming | Topic→Evidence→Reflection、真实 commit/checkpoint/Pending/backlink、空场景与失败 | 宣称已做 scheduler E2E；组件装配不冒充调度器 |
| 汇总 | 72 selected/completed 分开、PASS/FAIL/REVIEW_REQUIRED/PROTOCOL_FAILURE/NOT_RUN、成本/usage/截断/重试、完整 SHA | 把子集报告写成全量 |
| 工程 | 当前工作区定向评测测试、Spotless、`./scripts/check` 全量与 XML 零失败/跳过 | 沿用旧 137/137 而不重跑 |
| 语义 | 人工核对 oracle 与原始输出，重点 H1/H2/H3、空结果、冲突、纠正、来源与时序 | 同 Provider Judge 或结构通过自动放行质量 |

## 3. 接管与定点修复边界

由新 `codex-lead` 先盘点并保留现有 B3，委派一个 `codex-worker` 完成以下有界修复/补强：

1. 审查现有生产装配，不用 `SemanticEvaluationRunner` 的自制解析器替代真实生产解析器；Search 必须使用生产 Scanner→Rerank 的实际边界与候选顺序。
2. 修正 fixture/持久化与 Dreaming raw/source 映射、Evidence 链接、空场景及 artifact 完整性；保持 canonical 数据、生产算法及 B2 oracle 不变。
3. 配置中模板 context window/JSON mode 只能如实标为本地配置，不宣称 Provider 已确认。若 smoke 暴露服务端不支持，记录协议/Provider 失败后再作最小配置兼容，不放宽生产解析器。
4. 补齐失败保留、取消传播、artifact 原子写、report schema 一致性、脱敏与隐私；密钥绝不写入日志/报告。原始 prompt/response 只在 `.local/ct31/` 隔离输出，不进入 Git 跟踪文档。
5. 更新 `evals/README.md` 的“当前执行状态”：此前 NOT_RUN 是历史记录；真实运行后按实际结果更新，不伪造成功。
6. 先跑离线定向测试及 dry-run，再由 Lead 执行 `ext-001` smoke；smoke 产物审查通过后才跑全量 72。真实执行发生明确 Provider 失败时保留证据、定位修复，不删除输出或换样本掩盖。

允许修改仅：现有 `context/runtime/src/main/.../evaluation/` 评测适配器、`integration-tests/.../integration/evaluation/` 文件、相关 evaluation Gradle 接缝、`evals/README.md`、本任务 B3 文档和必要 `.local/ct31/` 输出/本地非密钥配置。不得修改 canonical 数据/manifest/offline report、生产业务算法、B2 测试、VFS/Backup/旧 Runtime 变更；确需扩界先交 Architect。

## 4. 执行与停止条件

- smoke 与全量输出使用新目录，绝不覆盖已有运行。任何调用均需 `Live+Production+ExternalApproved=true`；普通 test/check 不触发真实模型。
- 每次中断保留已完成样本及 report；取消不伪装 PASS。网络、认证、限流、结构化输出不兼容归入 Provider/协议，不自动算语义失败。
- 全量后 Lead 提交实际产物、成本/usage、失败清单与独立初验；Architect 复跑必要测试并人工审查 H1/H2/H3。未获得完整真实证据前，B3 仍为 IN_REVIEW，不签 DONE，不启动 CT32。
- 本阶段没有额外外部授权需求，不因费用/模型外发暂停；遇到技术问题按证据修复继续，避免无目标轮询或角色重建。
