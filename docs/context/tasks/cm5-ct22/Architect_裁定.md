# CT22 · Architect 裁定（D1～D18）

**日期**：2026-11-09
**状态**：**APPROVED：放行 CT22 B1（Dreaming 调度与固定增量范围组件）**
**裁定对象**：[CT22 任务书](CT22_任务书.md)、[方案核对](方案核对.md)（D1～D18）
**基线**：CM4 APPROVED / DONE；14 模块 **1885=1885**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 0. 本次修改

Architect 独立核实方案核对 F1～F15 关键事实（checkpoint 表/CAS SQL、`maxTurnId`/`idRange`、`StoredConversationTurn` 字段、TokenBatchPlanner、既有 Cron 模式、ContextConfig/Loader 严格键、API 无 Dreaming 符号）后逐项裁定 D1～D18。结论：**方案 APPROVED，放行 CT22 B1**；D16 裁定为**批次允许跨页（streaming assembler）**并附带确定性要求。

## 1. 事实核实（Architect 独立）

- F1～F5：`dreaming_checkpoint` 固定 id=1、初始 0；`SELECT_CHECKPOINT`/`CAS_UPDATE_CHECKPOINT`（expected 匹配 + next 严格向前 + 行数返回）✓；`maxTurnId(): Long?` 与 `idRange(fromExclusive, toInclusive, limit)` 存在 ✓
- F6/F7：`StoredConversationTurn` 含内部 id/业务键/timestamp/messages/extractionStatus；Dreaming 不按状态过滤 ✓
- F8/F9：API 无 DreamingResult/ContextDreamingException；Loader 对 context 未知键严格拒绝（需新增 dreaming 键注册）✓
- F10/F11：TokenBatchPlanner 固定开销/动态分批/单条超限 fail-closed；Organization/Map Cron 有时区/严格未来/不补跑/close join 先例 ✓
- F15：14 模块 1885=1885；Schema 未变 ✓

## 2. 逐项裁定

| # | 裁定 | 说明 |
| --- | --- | --- |
| D1 | **DreamingResult 放 `context/api`** | 六字段公共数据模型；`require` 非负、`start <= end`；不暴露内部 Turn/存储对象 |
| D2 | **completed 语义**：正常/无工作 `completed=true`；partial failure 一律走具名异常（携带 partialResult），**不返回 `completed=false`** | 防止调用方误判成功 |
| D3 | **新增 `ContextDreamingException` + `ContextDreamingErrorCode`，至少 `ALREADY_RUNNING`** | 不复用 lifecycle/config 错误码；DB/LLM/预算/取消保留既有异常 |
| D4 | **中途失败具名异常（`DreamingRunException` 或并入 ContextDreaming 家族）携带 `partialResult`、stage、cause** | stage 枚举至少覆盖：固定范围读取/分页/预算/批处理；partialResult.endCheckpoint=最后成功批 lastTurnId |
| D5 | **flat 配置 `context.dreaming.cron: String?` + `timezone: String?`** | 与现有简单结构一致 |
| D6 | **`DreamingConfig()` 缺省始终存在，cron=null 即 manual-only** | ContextConfig 新增字段带默认值，旧构造兼容 |
| D7 | **timezone 缺省 Asia/Shanghai；出现时仅接受 IANA ZoneId（`ZoneId.of` 校验）** | 非法值构造期/解析期拒绝 |
| D8 | **timezone 单独出现（无 cron）拒绝** | 严格配置，防 orphan |
| D9 | **cron 缺省不创建 Cron**；manual 独立可用 | 符合 CM5 默认无 Cron |
| D10 | **手动重叠：guard 失败立即抛 `ALREADY_RUNNING`**，不等待/不排队 | 与设计冻结一致 |
| D11 | **Cron 重叠：直接 skip + 脱敏日志**，不累加 trigger、不抛给外部 | 不回压 Cron loop |
| D12 | **手动 caller 的 CE 原样传播（不包装）；组件 close 只取消自己启动的 run（owned run）**；外部 caller 持有的 run 不被 close 强杀，由其 caller 协程取消 | 保持既有 Extraction/Organization 口径 |
| D13 | **C0/U 同一次 `database.read`；校验 `0 <= C0 <= U`（违反即具名失败）** | 固定范围核心 |
| D14 | **无新增 Turn：start=end=C0、全 0 计数、completed=true** | 正常成功 |
| D15 | **固定 U：本轮不回读 MAX**；运行中新增 Turn 不进入本轮，留下一轮 | 快照一致性 |
| D16 | **批次允许跨页：page→streaming assembler**（推荐）；**不允许因 page 边界静默截断批次或漏行** | 附带要求：assembler 必须穷尽 `(C0,U]` 每一行——空页/不足页终止读取；页间衔接用确定性拼接（上一页末 ID），不依赖行数假设；若 Worker 判断跨页实现风险高，允许退回"批次不跨页"保守实现，但须在交付报告说明并在测试覆盖页边界批完整性 |
| D17 | **直接复用 TokenBatchPlanner；预算错误映射为 Dreaming 具名失败** | 不改 planner 语义/组件 |
| D18 | **CT22 只读聚合；真实 Raw+Pending+Checkpoint CAS 留 CT25**；batch processor 必须返回 `lastTurnId`，CT22 的 endCheckpoint 聚合逻辑须与 CT25 CAS 契约兼容（partialResult = 最后成功批） | processor 返回批外 ID/回退 ID 属非法 |

## 3. 附加裁定

- **B1 白名单方向**：`context/api` DreamingResult + DreamingException/ErrorCode；`context/runtime` DreamingConfig/Loader 解析 + DreamingRunCoordinator + 可选 DreamingPeriodicCron + Batch processor seam（internal）+ 预算 seam；各模块配套测试。由 Lead 冻结精确清单。
- **不改**：Schema/SQL、CT13/CT16/CT19/CT20 组件语义、Factory/Impl/AlcyoneContext、依赖/构建。
- **processor seam 可见性**：internal（测试用 @Suppress 或同模块测试直接访问）。
- **预算函数 seam**：注入式估算（完整请求 Token），真实 Topic Discovery 请求 CT23 接入；CT22 测试用受控 estimator。
- **单 Turn 超预算**：当前 run 具名失败保水位；不修改该 Turn Extraction 状态；不截断/跳过。
- **Cron 表达式**：UNIX 五字段；校验失败构造期拒绝。
- **验证命令**：按任务书 §9。

## 4. 结论

- **CT22 方案 APPROVED；D1～D18 全部裁定**（D16 主选跨页 streaming，附保守退路）。
- **放行 CT22 B1**：Lead 冻结 B1 委派单与白名单后派发 Worker。
- CT22 B1 复核通过才进入 CT23；CT23～CT26 LOCKED；CM6 未启动。
- 真实模型 not-run；无 Git 写操作；基线 1885=1885。
