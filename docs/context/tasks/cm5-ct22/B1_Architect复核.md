# CT22 B1（含 R1）· Architect 复核

**日期**：2026-11-09
**状态**：**B1（含 R1）APPROVED / DONE**
**复核对象**：[B1 Worker 交付](B1_Worker交付.md)、[B1 Lead 初验](B1_Lead初验.md)、Worker `059d6c59-ce9e-4e68-9055-b45b238506a1`
**依据**：[Architect 裁定](Architect_裁定.md) D1～D18、[B1 委派单](B1_委派单.md)
**基线**：B1 前 14 模块 **1885=1885**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 0. 本次修改

Architect 独立复核 CT22 B1（含 R1）：14/14 文件 SHA 复算一致、R1 修复逐行核对、Coordinator 流式 assembler 代码级审查、定向与全量实跑、SHA 链条闭环验证、白名单外变更 0 确认。结论 **B1（含 R1）APPROVED / DONE**。

## 1. 独立证据

| 项 | 结果 |
| --- | --- |
| 14 文件 SHA 复算 | API 3 + Runtime 生产 6 + Runtime 测试 5，逐项一致（14/14 OK） |
| 定向 | `:context:api:test :context:runtime:test --rerun-tasks` exit 0（46s，27 tasks） |
| 全量 `./scripts/check` | exit 0（2m16s，131/131 tasks executed） |
| 14 模块 source @Test = XML | **1916=1916**；基线 1885 + 31（API +3、Runtime +28） |
| Schema | `ec727a53…8ea3` 未变 |
| SHA 链条 | CT20 B1 19/19；**当前文件与 CT20 B2 终态 12/12 一致**（3 个 CT18/CT19 时代 SHA 差异经链式核验均为 CT19 B2/CT20 B2 白名单已批准增量，CT22 无额外越界）；CT22 白名单外变更 0 |
| 白名单外 | `git status` 非 docs 变更恰为 3 修改 + 11 新增（全部在 15 文件白名单内）；common/vfs diff 空 |

## 2. R1 修复复核（B1-R1）

- 缺口：`DreamingConfig(cron=null, timezone="Europe/London")` 直接 Kotlin 构造未拒绝（D8 只覆盖 Loader 路径）。
- 修复逐行核对：`DreamingConfig` init 块 `cron == null && timezone != DEFAULT_TIMEZONE` → `ContextConfigException(INVALID_CONFIG)`；消息仅字段路径、无 cause、无原值回显；`DreamingConfig()` 仍合法（§3.2 与实现一致）。
- 附加 timezone 严格性：拒绝空值、`ZoneOffset`、`zone.id != timezone` 非规范文本——超出裁定要求的防御，接受。
- R1 仅改白名单内 `DreamingConfig.kt`/`DreamingConfigTest.kt`（+Loader 既有用例），Loader/Coordinator/Cron 未动。

## 3. 实现逐项核对（D1～D18 代码级）

| 裁定 | 复核结果 |
| --- | --- |
| D1/D2/D3/D4 | `DreamingResult` 六字段 require 非负/start<=end；`DreamingRunException` 拒绝 completed=true 的 partialResult；stage 四枚举齐；CE 每条路径先 rethrow 不包装。通过 |
| D5～D9 | flat cron/timezone；缺省 manual-only；`ContextConfig.dreaming` 带默认值；Loader 注册 dreaming 键（`ContextConfigLoader` L77/L475）。通过 |
| D13/D14/D15 | `readFixedRange` 单次 `database.read` 同 ReadContext 读 checkpoint+maxTurnId；C0<0 / C0>U → FIXED_RANGE 具名失败；max==null||max==C0 返回 start=end=C0 全 0 true；U 固定后所有 `idRange` 上界恒为 max。通过 |
| D16（主选跨页） | 流式 assembler 逐行审查：`pending + page` 拼接 → `TokenBatchPlanner.plan` → 除末批外逐批 process、末批留 pending 跨页；`page.size < pageSize` 终止读取后处理尾部 pending；`validatePage` 断言严格升序 + 首行 > fromExclusive + 末行 <= toInclusive（不依赖行数假设）；pageSize 1/2/3 × 7 行穷尽测试。**无漏行、无页边界截断**。通过 |
| D17 | 复用 `TokenBatchPlanner.plan`；`CandidateBudgetExceededException` 映射 `DreamingRunException(BUDGET)`（含 reason/requiredTokens，无敏感内容）；planner 语义未改。通过 |
| D18 | `outcome.lastTurnId != expectedLast || <= partial.endCheckpoint` → BATCH_PROCESSING 具名失败（批外/回退/重复均拦截）；CT22 零 Checkpoint/Raw/Pending 写。通过 |
| D10/D11/D12 | manual 重叠同步抛 ALREADY_RUNNING（L359）；`requestBackground` CLOSING/running skip；close 同步 CLOSING → 取消 ownedJob 真实 join；caller CE 原样。通过 |
| Cron | 仅显式构造；严格未来/不立即 tick/不补跑；tick 只 requestBackground；1s 可取消退避；close join。通过 |

## 4. 观察确认（不阻断）

- **ExtractionPipelineLifecycleTest 时序观察**：Worker 初始阶段一次 10s 超时、R1 最终全量与 Lead/Architect 独立全量（2m21s/2m16s）均通过；该文件不在白名单、B1/R1 未修改——与 CT10/CT15 以来的历史观察一致，继续记录。
- 负 C0 经测试 wrapper 覆盖（正式 Schema CHECK 禁止负值）——合理。
- `AlcyoneContextFactory.kt` `dc00b0d4…` 与 CT20 B2 记录一致，CT22 未触碰 Factory。

## 5. 结论

**CT22 B1（含 R1）APPROVED / DONE。**

- 15 文件白名单齐备；14 代码/测试 SHA 一致；白名单外变更 0；零 Schema/Factory/Impl/依赖/构建修改。
- 14 模块 **1916=1916**；`./scripts/check` exit 0；A01～A08（B1 部分）全部覆盖。
- **放行 CT23（Topic Discovery 与批内归并）**：Lead 只读方案核对 → Architect 裁定 → B1 派发。
- CT23～CT26 解锁为"CT23 进入方案核对"；CT24～CT26 保持 LOCKED；真实模型 not-run；无 Git 写操作。
