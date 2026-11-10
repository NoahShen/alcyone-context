# AGENTS.md

## 本次修改（2026-11-10，CT26 追溯性 R1，生产接线定点修复）

- CT25 保持 DONE；CT26 预实现未获倒签批准。已补 [CT26 任务书](docs/context/milestones/cm5/CT26_任务书.md) 与 [方案核对](docs/context/tasks/cm5-ct26/方案核对.md)。
- B1 已派发 Lead→Worker：修默认 `UNAVAILABLE`、manual 部分绑定启动前失败、关闭等待手动 run 后再释放 LLM/SQLite，以及真实 SQLite/受控 HTTP 非空多批集成。
- B2 独立进程恢复/全量验收待 B1 复核；真实收费模型 B3 默认 LOCKED。CT26/CM5 未 DONE，无 Git 写操作。

## 本次修改（2026-11-10，CT25 B2 Architect R1，定点补验收）

- Architect 独立复跑三模块定向测试、Spotless 与 `./scripts/check` 全绿（131/131，14 模块 XML **2115=2115**）；B1/B2 生产 SHA、Schema、VFS/common/Core 与零生产接线边界通过。
- B2 判定 **IN_REVIEW / R1**：普通通知异常、真实 SQLite Commit 故障、失败零副作用/后续不执行及非空 Topic 0 Insight 缺有效正式断言；退回 Lead→Worker 仅补测试/证据，见 [B2 Architect 复核](docs/context/tasks/cm5-ct25/B2_Architect复核.md)。
- **CT25 B1 保持 DONE；CT25 整体未 DONE；CT26/真实模型/公共 Factory/Impl 继续 LOCKED**；无 Git 写操作。

## 本次修改（2026-11-08，CM3 整体收口 DONE）

- Architect 复核 **CT15 B1/CT15 APPROVED / DONE**：5 文件/29 例、定向 29/0/0/0、`./scripts/check` 131 tasks、14 模块 **1587=1587**、81 基线 all OK、VFS diff 空、Core 无 Ktor/serialization、Schema 未变、生产零接线。
- CT15 保持组件化；Source 幂等、目标 `updatedAt` 冲突、Organized/Source/Pending 原子提交及全局毫秒水位已验收。Architect 沙箱 Gradle 的 FileLock socket 限制由双重实跑/XML/SHA/静态复核补足。
- Architect 复核 **CT16 B1 APPROVED / DONE**：21 文件/50 例、1637=1637、81/Core/Schema/生产边界通过，见 [B1 Architect 复核](docs/context/tasks/cm3-ct16/B1_Architect复核.md)。
- CT16 B2 Worker 交付 6 文件/16 例；Lead 独立定向 42s、wiring 3/3、E2E 2/2、全量 `./scripts/check` 2m36s 均通过；14 模块 **1653=1653**、81/Core/Schema/公共 API/依赖边界通过。
- Architect 复核 **CT16 B2 APPROVED / DONE**：6 文件/16 例、B1/CT13/14/15 SHA 与边界通过，见 [B2 Architect 复核](docs/context/tasks/cm3-ct16/B2_Architect复核.md)。
- Architect 最终整体复核：`./scripts/check` exit 0（2m18s、131/131 tasks）、14 模块 **1653=1653**、81 VFS 基线 all OK、VFS/common/Schema/依赖边界通过，见 [CM3 里程碑复核](docs/context/milestones/cm3/milestone-review.md)。
- **CM3（CT13～CT16）APPROVED / DONE**；Map no-op，CT17/CM4 未启动；无 git 写操作/推送；真实模型 not-run。

## 本次修改（2026-11-01，CT14 DONE；按用户暂停，CT15 不启动）

- Architect 复核 **CT14 B1 APPROVED / DONE；CT14 整体 APPROVED / DONE**：12 文件/60 例、`./scripts/check` exit 0、14 模块 **1558=1558**、81 基线 all OK、VFS diff 空、Core 无 Ktor/serialization、Schema 未变；CT13 组件 SHA 未变；未接生产。
- 用户暂停指令：**保存进度，不启动 CT15**。恢复点：CM3 IN_PROGRESS（用户暂停），CT13 DONE、CT14 DONE，CT15/CT16 未启动，CM4 不启动；CT14 保持组件化，生产接线归 CT16。
- 已落 [CM3 暂停恢复点](docs/context/milestones/cm3/CM3_暂停恢复点.md)；停止派发新任务，等待用户明确恢复。无 git 写操作/推送。

## 本次修改（2026-11-01，CM3 授权，CT13 只读方案）

- 用户明确授权 CM3（仅 CT13～CT16，不启动 CM4）；`main`/`origin/main` 基线 `9723bb7`，CM1+CM2 已提交推送。
- 已落 [CM3 范围与依赖方案](docs/context/milestones/cm3/CM3_范围与依赖方案.md)、[CT13 任务书](docs/context/milestones/cm3/CT13_任务书.md)、[CT13 方案核对](docs/context/tasks/cm3-ct13/方案核对.md)；本轮只做只读方案，不派发 Worker、不改生产/测试/构建。
- 方案锁定：动态预算串行全量快照扫描、无 Domain/Agent 硬过滤、批内 ID 校验、任一批失败整条失败、无截断、单条放不下显式预算失败；CT14～CT16 的 Governance/幂等/水位/调度边界见 CM3 方案。
- CT13 通过 Architect 裁定前不实现；CT13 DONE 前不进入 CT14；CT16 通过前不宣称 CM3 完成。无 git 写操作/推送；真实模型未运行，Fake 不得冒充语义质量。
- 2026-11-01 Architect 已批准 CT13 B1（局部 Q1～Q8）；B1 已派发 Worker `7bc568e0`，只新增 Core/Runtime organization 组件、Prompt/Schema、通用预算分批与测试；不接 Factory/Impl，CT14 未放行。
- 2026-11-01 CT13 B1 完成并通过 Lead 初验：13 文件/48 例、全量 1489=1489、81 基线/Core/Schema 通过；未接生产。已提交 Architect 复核，CT13 未 DONE，CT14 未放行。
- 2026-11-01 Architect 首轮复核：13 SHA/398 测试通过，未确认生产缺陷；R1 验收覆盖阻断（真实跨批 ID、不同域/来源、批间取消、非零 schema/输出/余量、第二批非法 JSON/截断）。已派发 Worker `6920f01b` 仅修测试/辅助，生产/资源 SHA 保持。
- 2026-11-01 R1 完成并通过 Lead 初验：只改 2 个测试文件（+9 例，Planner 取消改确定性），全量 1498=1498、81 基线/Core/Schema 通过，生产/资源 8 SHA 未变；已提交 Architect 复核，CT13 未 DONE，CT14 未放行。
- 2026-11-01 Architect 复核 R1：R1 CLOSED、B1 APPROVED / DONE，放行 CT13 B2；B2 只做验收/证据/A01-A08/全量边界，不改代码。已派发 Worker `44c6578b`，CT13 未 DONE，CT14 未放行。
- 2026-11-01 Architect 复核 B2：**CT13 B2 APPROVED / DONE；CT13 APPROVED / DONE**；1498=1498、81 基线/Core/Schema、8 SHA 通过。CT14 Governance 已放行（仅组件，不接生产）；CT15/CT16 逐批；CM4 不启动。
- 2026-11-01 CT14 只读方案已落：`CT14_任务书.md`、`tasks/cm3-ct14/方案核对.md`；范围锁定 Governance NEW/MERGE/UPDATE 严格字段、候选权限、非法不降 NEW、事实时间/补录/不确定 Prompt；未派发实现，待 Architect 裁定。
- 2026-11-01 Architect 批准 CT14 D1～D10 并放行 B1：只做 Core DTO/Validator/Prompt + Runtime LLM/JSON 组件，不接生产；D4 MERGE domains 空数组也拒绝，D5 空白拒绝/重复去重，D6 空候选允许 NEW，D8 禁止 PROMPT_ONLY。待落委派单派发。
- 2026-11-01 CT14 B1 已派发 Worker `b7a7cbfe`：Core Governance DTO/Validator/Prompt + Runtime Service/Parser + 测试；不接生产，不改 CT13/Schema/依赖（历史记录，后经复核 DONE）。
- 2026-11-01 CT14 B1 完成并通过 Lead 初验：12 文件/60 例、全量 1558=1558、81/Core/Schema 通过；未接生产（历史记录，后经 Architect 复核 DONE）。
- 2026-11-01 Architect 最终裁定：**CT13 B2 APPROVED / DONE；CT13 APPROVED / DONE；放行 CT14（先只读方案核对）**。证据保持 1498=1498、81 基线 all OK、VFS diff 空、Core 无 Ktor、Schema `ec727a53…` 未变；8 个生产/资源 SHA 与 B1/R1 终态一致。CT13 仍组件化，不接 Factory/Impl/Scheduler；CT15/CT16 未放行，CM4 不启动，无 git 写操作/推送。

## 本次修改（2026-10-30，CT12 DONE，CM2 全部完成）

- Architect 复核 CT12 B1+B2：**CT12 APPROVED / DONE；CM2（CT08～CT12）全部 DONE；CT13 不启动**。方案 A 测试级 `vfs.runtime` 依赖获批准（生产 compileClasspath vfs 0）。
- 证据保持：core 43 / runtime 307 / persistence 176 / server 30 / integration-context 18 / 其他 VFS 模块合计 **1441=1441**，`./scripts/check` exit 0（131 tasks）、81 基线 all OK、vfs diff 空、Core 无 Ktor、Schema 未变；B1 三例端到端与 B2 合成样本/版本归档、Fake 与真实模型结果分离（真实模型 not-run）。
- CT13（Organization，CM3）不启动；B2 评测基线为 Fake，真实模型语义质量待后续授权里程碑；仍无 git 写操作与发布。

## 本次修改（2026-10-30，CT12 B1/B2 初验通过，提交 Architect 裁定）

- CT12 B1 Worker 新增 3 文件：`integration-tests/context/build.gradle.kts` 仅加测试级 `vfs.runtime`（方案 A，生产 compileClasspath vfs 0）、`ExtractionHttpStub.kt`、`ExtractionEndToEndTest.kt`；3 例真实 Runtime/SQLite + Fake LLM 端到端（失败后 Trigger 继续、窗口外 Pending 不变、零记忆）。
- Lead 独立全量：`./scripts/check` exit 0（131 tasks，2m5s）、14 模块 1441=1441、81 基线 all OK、vfs diff 空、Core 无 Ktor、Schema 未变；B2 完成 4 条合成脱敏样本、Prompt/Schema/Profile/样本版本与 Fake/真实模型结果分离归档（真实模型明确 not-run）。
- 已提交 Architect 裁定 CT12 DONE；CT13 未启动；无 git 操作与发布。详见 `docs/context/tasks/cm2-ct12/B1_Lead初验.md`、`B2_评估基线与版本归档.md`。

## 本次修改（2026-10-30，CT11 DONE，CT12 B1 集成测试派发）

- Architect 复核 CT11 B3/生产接线：APPROVED / DONE；`./scripts/check` 131 tasks、14 模块 1438=1438、81 基线、Core/schema 通过；CT12 放行。
- 落 `docs/context/milestones/cm2/CT12_任务书.md` 与 `docs/context/tasks/cm2-ct12/B1_委派单.md`，派发 Worker `10264b13`（message `66c51976`）：CT12 B1 只新增 `integration-tests/context` 的 Fake LLM HTTP stub + 端到端测试（失败后 Trigger 继续、窗口外 Pending 不变、零记忆），真实 Runtime/SQLite；不改生产/现有测试/SQL/依赖；不启动 CT13。
- CT12 未标 DONE；无 git 操作与发布。样本/版本归档（B2）待 B1 通过后由 Lead 完成。
- CT12 B1 首次因 `integration-tests/context` testCompileClasspath 无 VFS 类型、公共 Factory 无法编译而停下；Lead 授权方案 A：仅在该模块增加一行 `testImplementation(projects.vfs.runtime)`（测试级、仓库内模块、生产依赖图不变），已重新派发（message `67ae9693`）。待 Architect 复核确认该测试级依赖。

## 本次修改（2026-10-30，CT11 B3 初验通过，提交 Architect 裁定）

- B3 按 Architect 确认的“合法 extraction 绑定才建 pipeline”口径完成：有 EXTRACTION Task + Profile + JSON_SCHEMA/JSON_OBJECT 时构造真实 Runner/Pipeline；无 LLM/非法绑定保留 LLM client 生命周期（CT08 PROMPT_ONLY 4/4 通过）。
- Lead 独立复跑 `./scripts/check` exit 0（131 tasks）、14 模块 1438=1438、81 基线 all OK、vfs diff 空、Core 无 Ktor、schema 探针 1/1；wiring 7 例（合法绑定真实提交、非法响应 pending/RUNNING、wakeup、无 LLM 零 pipeline、PROMPT_ONLY/无 Task 兼容、maxWindowIterations）。
- 已提交 Architect 裁定 CT11 是否 DONE；CT12/CT13 未启动；无 git 操作与发布。详见 `docs/context/tasks/cm2-ct11/B3_Lead初验.md`。

## 本次修改（2026-10-30，CT11 B2 DONE，B3 生产接线派发）

- Architect 复核 B2：APPROVED / DONE（core 43、runtime 300、persistence 176、server 30；窗口一致性/单Turn/错误分类/唤醒/l上限通过）。
- B3 按 D3/D14.3 只做公共 Factory/Impl 生产接线与全量验收：有合法 `context.llm` 时构造真实 Runner（Builder/LLM/Validator/Committer、maxWindowIterations=initialWindowTurns、OrganizationWakeup 注入）接 CT09 Pipeline；无 LLM 不建 pipeline；公共 create 签名不变。全量 `./scripts/check`、14 模块、81 基线、Core 无 Ktor、schema、日志/错误消费确认；不改 B1/B2/CT10/CT09 语义。
- CT11 未标 DONE；CT12/CT13 未启动；无 git 操作与发布。
- B3 首次接线因 CT08 `LlmRuntimeLifecycleTest`（PROMPT_ONLY + start 成功）与 Builder PROMPT_ONLY fail-fast 冲突而暂停回退；Architect 未授权改 CT08 测试。Lead 采用“合法 extraction 绑定（EXTRACTION Task + Profile + JSON_SCHEMA/JSON_OBJECT）才建 pipeline，否则保留通用 LLM 生命周期”口径，已重新派发 B3（message `59d06ecf`），并要求 wiring 测试覆盖 PROMPT_ONLY/无 Task 零 pipeline 与 CT08 全绿；待 Architect 复核确认该边界。
- B3 Worker 首次派发（message `30cd71d5`）：公共 Factory/Impl 生产接线（有 llm 建真实 Runner/Pipeline，无 llm 不建；maxWindowIterations=initialWindowTurns；OrganizationWakeup 注入）+ 真实 SQLite/HTTP stub wiring 测试 + `./scripts/check`/14 模块/81 基线/Core/schema 全量验收。

## 本次修改（2026-10-30，CT11 B2 初验通过，提交 Architect）

- B2 Worker 交付 3 文件：Builder 仅新增 internal `requestFor`、internal `DefaultExtractionRunner`、12 例真实 SQLite+Fake LLM 测试；BuilderTest 未改；B1/CT10/CT09/Schema 未变。
- Lead 独立初验：core 43/43、runtime 300/300（288+12）、persistence 176、server 30，差额 0；Runner 测试单独重复 3 次 12/12；窗口一致性、单 Turn 行数、FixedOverhead、非法响应/LLM 错误 0 写、wakeup 失败吞掉、防御上限、CE、NoPending 均通过。
- 已提交 Architect 复核；B3 未启动；CT12/CT13 未启动；无 git 操作与发布。详见 `docs/context/tasks/cm2-ct11/B2_Lead初验.md`。

## 本次修改（2026-10-30，CT11 B1 DONE，B2 Runner 派发）

- Architect 复核 B1：APPROVED / DONE（core 43/43、runtime 288/288、persistence 176、server 30；Validator/Committer/JSON 规则通过）。
- 落 `docs/context/tasks/cm2-ct11/B2_委派单.md` 并派发 Worker `10264b13`（message `7cde7149`）：B2 新增 internal `DefaultExtractionRunner`，串联 Builder/LLM/B1 Validator/Committer；Builder 仅新增 internal `requestFor`；单 Turn 循环+防御上限、FixedOverhead 配置错误、LLM/非法响应 0 写、wakeup raw>0/失败吞掉、CE；真实 SQLite+Fake LLM 测试；不改 CT10 build/CT09/Factory/依赖；不启动 B3。
- CT12/CT13 未启动；无 git 操作与发布。

## 本次修改（2026-10-30，CT11 B1 初验通过，提交 Architect）

- B1 Worker 新增 7 文件（Core DTO/Validator/Committer + Runtime JSON Validator + 3 测试）；CT10/CT09/SQL/Schema SHA 未变；Core 无 serialization。
- Lead 独立初验：core 43/43（34+9）、runtime 288/288（275+13）、persistence 176、server 30，差额 0；Validator 严格性/来源映射/原子提交/ID 碰撞/空 memories/Target 变化回滚/JSON 19 类非法载荷均通过。
- 已提交 Architect 复核；B2 未启动；CT12/CT13 未启动；无 git 操作与发布。详见 `docs/context/tasks/cm2-ct11/B1_Lead初验.md`。

## 本次修改（2026-10-30，CT11 D1～D14 裁定，B1 派发）

- CT10 收口后 Architect 授权 CT11；完成只读核对并获 D1～D14 裁定（Core DTO/规则 + Runtime JSON、无 OrganizationNotifier Port、CT11 生产接线、Builder internal requestFor、单 Turn 循环上限、Validator 严格、原子事务逐个 TxOps、ID 碰撞预检重试≤3、通知失败吞掉、错误分类、Raw 字段、空 memories、CE、B1～B3 分批）。
- 已同步 `docs/context/milestones/cm2/CT11_任务书.md`、`docs/context/tasks/cm2-ct11/CT11_方案核对与待裁定.md`，落 `B1_委派单.md` 并派发 Worker `10264b13`（message `47adc158`）：B1 只做 Core DTO/Validator/Committer + Runtime JSON Validator 与真实 SQLite 提交测试；不改 CT10/CT09/SQL/依赖；不启动 B2。
- CT12/CT13 未启动；无 git 操作与发布。

## 本次修改（2026-10-30，CT10 B3 初验通过，提交 Architect 裁定）

- B3 Worker 只读验收：`./scripts/check` exit 0（131 tasks，2m1s）、14 模块 1397=1397、81 基线 all OK、vfs/integration-tests/vfs 无 diff、Core 无 Ktor、schema 未变；A01～A08 映射齐全；未改任何代码/测试/构建/文档。
- Lead 独立复跑：check exit 0（131 tasks，2m）、14 模块 1397=1397 差额 0、81 基线 all OK、vfs diff 空、Core 无 Ktor；Lead schema 临时库探针 1/1（user_version=1、6 表、5 索引）；已同步任务书 §5 证据/状态、方案核对、B3 委派单；提交 Architect 裁定 CT10 是否 DONE。
- CT10 未自标 DONE；CT11/CT12 未启动；无 git 操作与发布。

## 本次修改（2026-10-30，CT10 B2 收口，B3 全量验收派发）

- Architect 放行 B3；B2 收口：Builder + 10 例真实 SQLite 测试，runtime 275/275、core 34/34、persistence 176、server 30，差额 0。
- B3 按 D13 只做集成证据/全量回归/文档：`./scripts/check` clean、14 模块 @Test=XML、81 基线、vfs diff、Core 无 Ktor、schema 未变、A01～A08 映射；不新增生产/测试代码，不接 pipeline。
- CT10 未标 DONE；CT11/CT12 未启动；无 git 操作与发布。

## 本次修改（2026-10-30，CT10 B2 初验通过，提交 Architect）

- B2 Worker 新增 `DefaultPreExtractionContextBuilder` 与 10 例真实 SQLite 测试；B1/CT09 SHA 未变。
- Lead 独立初验：core 34/34、runtime 275/275（265+10）、persistence 176/176、server 30/30，差额 0；Builder 测试单独重复 3 次 10/10；锚点/连续窗口/History/实际 K 折半/单 Turn 不写状态/固定开销/快照并发补录/预算等号/JSON 模式/构造 fail-fast 均通过。
- 已提交 Architect 复核；B3 未启动；CT11/CT12 未启动；无 git 操作与发布。详见 `docs/context/tasks/cm2-ct10/B2_Lead初验.md`。

## 本次修改（2026-10-30，CT10 B1 收口，B2 Builder 派发）

- Architect 放行 B2；B1（含 R1）收口：core 34/34、runtime 265/265、persistence 176、server 30，差额 0；Prompt 六原则 R1 关闭。
- 落 `docs/context/tasks/cm2-ct10/B2_委派单.md` 并派发 Worker `10264b13`（message `8537934d`）：新增 internal `DefaultPreExtractionContextBuilder`，同一真实 SQLite ReadContext 内锚点/连续窗口/完整请求估算/按实际 K 折半；构造期缺 LLM/EXTRACTION Task/Profile/PROMPT_ONLY fail-fast；单 Turn/固定开销/无 Pending 结果；不写 failed、不调 LLM、不接 pipeline；真实 SQLite + 受控 estimator + 快照并发补录测试。
- B3 未放行；CT11/CT12 未启动；无 git 操作与发布。

## 本次修改（2026-10-30，CT10 B1（含 R1）初验通过，提交 Architect）

- B1 Worker 交付 13 文件；Lead 初验发现 Prompt v1 六原则覆盖不足（B1-R1），退回 Worker 仅改 Prompt 资源与测试；修复后 Lead 重新独立验证。
- 初验结果：core 34/34、runtime 265/265、persistence 176/176、server 30/30，差额 0；3 项 Lead 探针全绿（六原则/时间/target、Context 防御性快照、预算边界/折半）；B1-R1 关闭。
- 记录：一次全量运行中 CT09 既有 `ExtractionPipelineLifecycleTest > disabling idle...` 10s 超时一次；单独重跑及连续全量均绿、Lead 5/5 通过，暂列时序敏感性观察，B1 未改 CT09。
- 已提交 Architect 复核；B2 未启动；CT11/CT12 未启动；无 git 操作与发布。

## 本次修改（2026-10-30，CT10 B1 初验发现 R1，定点修复派发）

- CT10 B1 交付 13 文件；Lead 独立初验：core 34/34、runtime 265/265、persistence 176、server 30，差额 0；但发现 **B1-R1**：`extraction-v1.md` 六原则覆盖不足（缺完整原子化的原因/条件/时间、关注重要变化、批内去冗余），现有测试只数编号。
- 已落 `docs/context/tasks/cm2-ct10/B1_R1修复委派单.md` 并派发 Worker `10264b13`（message `8995cbe0`）：仅改 Prompt 资源与 `ExtractionPromptTest`，补齐六原则内容并逐条断言；不新增用例数、不改其他文件、不启动 B2。
- 另记录：Worker 一次全量运行中 CT09 既有 `ExtractionPipelineLifecycleTest > disabling idle...` 10s 超时失败一次，单独重跑与连续 3 次全量均绿；Lead 单独重跑该类 5/5 通过，暂记为时序敏感性观察，待后续复核。
- CT11/CT12 未启动；无 git 操作与发布。

## 本次修改（2026-10-30，CT10 D1～D13 裁定，B1 派发）

- CT09 DONE 后 Architect 授权 CT10；完成只读方案核对并获 D1～D13 裁定（sealed `ExtractionBuildResult`、`StoredConversationTurn`、CT10 拥有 Prompt v1/Schema/Renderer、`context.extraction.initialWindowTurns` 默认 32/1..1024、纯 `LlmBudget.estimate` 分类、按实际 K 折半、单 ReadContext、Builder 不写 failed、Core public/Runtime internal、构造期配置 fail-fast、仅 JSON_SCHEMA/JSON_OBJECT、v1 渲染格式、B1～B3 分批）。
- 已同步 `docs/context/milestones/cm2/CT10_任务书.md` 与 `docs/context/tasks/cm2-ct10/CT10_方案核对与待裁定.md`，落 `B1_委派单.md` 并派发 Worker `10264b13`（message `42965c28`）：B1 只做配置/Core 结果/Context/Prompt Renderer/资源/纯预算分类；不实现 Builder/DB、不接 pipeline、不启动 B2。
- CT11/CT12 未启动；无 git 操作与发布。

## 本次修改（2026-10-30，CT09 收口）

- CT09 独立全量复核通过：`./scripts/check` exit 0（130/130）、14 模块 1365=1365、81 基线 all OK、VFS tracked diff 为空、Core 无 Ktor、schema `user_version=1`/6 表/5 索引。B1～B4 与 B1-R1/B2-R1/B3-R1 全部关闭，CT09 **DONE**，见 `docs/context/tasks/cm2-ct09/架构与技术复核.md` §7。
- 公共 Factory 仍不接 Runner、不启用生产提取；真实 Runner 待 CT11 接入。CT10+ 未启动；无 git 写操作/发布。

## 本次修改（2026-10-30，CT09 B3 收口与 B4 放行）

- B3-R1 独立通过：Pipeline 准入门在 Context 状态锁内同步封闭，STOPPING 窗口新 Idle tick 与排队项均不再启动 Runner；两个 Architect 探针 2/2、五模块 498/498 与格式通过。B3 DONE，见 `docs/context/tasks/cm2-ct09/架构与技术复核.md` §6。
- 授权现有 Lead→Worker 推进 B4 全量验收与证据收口；仅文档/证据，不在 B4 修复生产代码。CT09 最终 DONE 待 Architect 复核；无 git 写操作/发布。

## 本次修改（2026-10-30，CT09 B3 首次独立复核）

- 4 文件 SHA 一致，独立五模块 496/496 与格式通过；但两个 STOPPING 窗口探针 2/2 失败，确认 Context 停止已开始后新 Idle tick/排队任务仍能启动 Runner。B3-R1 阻断，见 `docs/context/tasks/cm2-ct09/架构与技术复核.md` §5。
- 授权现有 Lead→Worker 定点修复状态感知准入/关闭门，补新 tick 与已排队项的中间窗口回归。B1/B2 DONE 保持，B4 不放行；无 git 写操作/发布。

## 本次修改（2026-10-30，CT09 B2 收口与 B3 放行）

- B2-R1 独立通过：原失败探针 1/1、四模块 468/468、Runtime 格式全绿；持续调度失败具备 1 秒可取消退避，关闭不再卡在 CLOSING。B2 DONE，见 `docs/context/tasks/cm2-ct09/架构与技术复核.md` §4。
- 授权现有 Lead→Worker 推进 B3 Pipeline 与 Runtime 生命周期接线；公共 create 仍不启用生产提取，内部 Runner 仅测试/后续 CT11 接入。B4 全量验收待 B3 通过；无 git 写操作/发布。

## 本次修改（2026-10-30，CT09 B2 首次独立复核）

- 7 新文件 SHA 与 B1 五项基线一致；独立四模块 464/464、Runtime 格式通过。默认 tick 极端 Clock 探针确认持续调度异常形成紧凑循环且不响应取消，close 卡在 CLOSING；B2-R1 阻断，见 `docs/context/tasks/cm2-ct09/架构与技术复核.md` §3。
- 授权现有 Lead→Worker 仅修 IdleCron 与其测试，增加取消检查及固定 1 秒可取消错误退避。B1 DONE 保持，B3 不放行；无 git 写操作/发布。

## 本次修改（2026-10-30，CT09 B1 收口与 B2 放行）

- R1 独立通过：真实子任务 join、唯一清理与共享失败；Architect 探针 2/2、协程边界对照 3/3、五模块正式 454/454 与格式通过。探针身份比较移入 timeout 内，保留同实例要求。B1 DONE，见 `docs/context/tasks/cm2-ct09/架构与技术复核.md` §2。
- 授权现有 Lead→Worker 推进 B2 Count/Idle/Cron，先委派单与基线后实现；B3 Runtime 接线待 B2 独立通过。CT10+、git 写操作、发布未授权。

## 本次修改（2026-10-30，CT09 B1 首次独立复核）

- 9 文件 SHA 一致，独立五模块 451/451 与 Core/Runtime 格式通过；两个独立探针失败，确认非关闭 CE 提前发布成功 closeOutcome，导致 closeAsync 不等真实子 Job 且跳过清理失败。B1 IN_REVIEW / R1 待修，B2 不放行。
- 见 `docs/context/tasks/cm2-ct09/架构与技术复核.md` §1；授权现有 Lead→Paseo Worker 定点修 Scheduler 与正式回归，生产修改仍仅 Worker。CT08 DONE、无 git 写操作/发布保持。

## 本次修改（2026-10-30，B4 初验通过，提交 Architect 裁定 CT09）

- B4 Worker 只读验收：`./scripts/check` exit 0（130 tasks，2m4s）、14 模块 1365=1365、81 基线 all OK、vfs/integration-tests/vfs 无 diff、Core 无 Ktor、schema 未变；A01～A08 映射齐全；未改任何代码/测试/构建/文档。
- Lead 独立复跑：`./scripts/check` exit 0（2m7s，130 tasks）、14 模块 1365=1365 差额 0、81 基线 all OK、vfs diff 空、Core 无 Ktor 三类证据；Lead schema 临时库探针独立验证 `user_version=1`、6 表、5 索引。已同步任务书 §5 证据/状态、方案核对状态、B4 委派单；提交 Architect 裁定 CT09 是否 DONE。
- CT09 未自标 DONE；CT10+ 未启动；无 git 操作与发布。
- **2026-10-30 B4 提交受阻**：B4 复核请求（message `611283bc`）在 Architect 侧因 429 Too Many Requests 中断；未获 CT09 最终裁定，B4 待复核，等待配额恢复后重提。

## 本次修改（2026-10-30，B3 DONE，B4 全量验收派发）

- Architect 复核 B3-R1 收口：B3 APPROVED / DONE；两 STOPPING 探针 2/2、五模块 498/498、Runtime spotless 通过。
- 按 §6.2 落 `docs/context/tasks/cm2-ct09/B4_委派单.md` 并派发 Worker `10264b13`（message `ccf67184`）：B4 只做全量验收/证据/映射，不改生产/测试/文档；要求 `./scripts/check` clean exit0、14 模块行首 @Test=XML、vfs/integration-tests/vfs 无 tracked diff、81 基线 all OK、Core 无 Ktor/无新依赖、无新表/索引/user_version，并整理 A01～A08 映射。
- 若回归暴露缺陷立即停止单独报告，不得隐蔽修复；CT09 DONE 由 Architect 最终裁定。B4 未完成前不启动 CT10+；无 git 操作与发布。

## 本次修改（2026-10-30，B3-R1 修复完成，待复核）

- Worker 修复 B3-R1（仅 `ExtractionPipeline.kt`、`AlcyoneContextImpl.kt`、`ExtractionPipelineLifecycleTest.kt`）：Pipeline 增加 admissionLock/closing 关闭门（beginClosing 短同步；Idle/Count 请求门；包装 Runner 在 activation 后检查；closeAsync 开头封闭）；shutdown 首次状态锁内 RUNNING→STOPPING 同步 beginClosing；Factory/B1/B2 组件未变。
- Lead 独立初验：五模块 core 19/runtime 258（256+2）/persistence 176/server 30/integration 15，合计 498/498 差额 0；**Architect 两探针 2/2 转绿**（STOPPING 新 Idle tick、已排队项均 0 新 Runner）。已提交 Architect 复核；B4 未启动。详见 `docs/context/tasks/cm2-ct09/B3_R1_Lead初验.md`。
- 无 git 操作与发布；CT10+ 未授权。

## 本次修改（2026-10-30，B3-R1 定点修复派发）

- Architect 首次复核 B3 未通过：shutdown 在 RUNNING→STOPPING 释放锁后、releaseResources(pipeline) 之前，IdleCron/Scheduler 仍运行，新 Idle tick 或已排队项可启动 Runner；两个真实探针 2/2 FAIL（Runner 进入时 status=STOPPING）。B4 不放行。
- Lead 已建立 `docs/context/tasks/cm2-ct09/B3_R1修复委派单.md` 并派发 Worker `10264b13`（message `d7f39a95`）：仅改 `ExtractionPipeline.kt`/`AlcyoneContextImpl.kt`/`ExtractionPipelineLifecycleTest.kt`；Pipeline 增加状态感知关闭门（beginClosing 非挂起；Idle/Count 请求与包装 Runner 在 activation 后检查），shutdown 首次状态锁内 RUNNING→STOPPING 同步调用 beginClosing；补 STOPPING 窗口新 tick/已排队项 0 新 Runner 回归；Architect 探针必须 2/2。
- 不改 Factory/B1/B2 组件/协议；B4 未启动；无 git 操作与发布。

## 本次修改（2026-10-30，B3 初验通过，提交 Architect）

- B3 Worker 交付 4 文件（`ExtractionPipeline.kt`、`AlcyoneContextImpl.kt`、`AlcyoneContextFactory.kt`、`ExtractionPipelineLifecycleTest.kt`）；B1/B2 十二个组件/测试 SHA 未变。
- Lead 独立初验通过：runtime 243→256、core 19/persistence 176/server 30 差额 0；2 项 Lead 探针全绿（RUNNING 前 Runner 零执行/激活后一次；公共 create 零 pipeline）。公共零 pipeline、无 Runner 不建、Count/Idle 触发、idle=false 只关 Cron、关闭取消在途并等 finally、释放顺序 pipeline→llm→vfs→executor、pipeline 失败继续释放、start/close 竞争、启动失败逆序清理、Runner/Idle 回调具名拒绝、关闭后 0 触发、并发 close 共享失败均有用例。
- 已提交 Architect 复核；B4 未启动；无 git 操作与发布；CT10+ 未授权。详见 `docs/context/tasks/cm2-ct09/B3_Lead初验.md`。

## 本次修改（2026-10-30，B2 DONE，B3 Pipeline/Runtime 接线派发）

- Architect 复核 B2-R1 收口：B2 APPROVED / DONE；独立四模块 468/468、Runtime spotless 通过；原失败探针 1/1 PASS。
- 按 §4.2 落 `docs/context/tasks/cm2-ct09/B3_委派单.md` 并派发 Worker `10264b13`（message `d0fabb31`）：新增 internal `ExtractionPipeline` 与 `ExtractionPipelineLifecycleTest`，修改 `AlcyoneContextImpl`/`AlcyoneContextFactory`；公共 create 零 pipeline/零后台副作用，内部注入 Runner/可控 Idle tick；performStart executor→vfs→llm→pipeline，RUNNING 发布后才激活 Runner；关闭顺序通知收尾→pipeline→llm→vfs→executor；Runner/扫描回调内关闭具名拒绝；真实 SQLite+Fake Runner 覆盖竞争/失败/顺序/并发 close。
- 不改 B1/B2 组件；若集成暴露组件缺陷先单独报告。B4 未启动；无 git 操作与发布；CT10+ 未授权。

## 本次修改（2026-10-30，B2-R1 修复完成，待复核）

- Worker 修复 B2-R1（仅 `IdleCron.kt` + `IdleCronTest.kt`）：`runTicks` 每轮 `ensureActive`；普通调度失败 WARN + 内部固定 1s 可取消退避后重算严格未来 tick；退避非 tick、不扫描/不补跑；CE 传播、close 立即响应；新增 4 例（持续失败可关闭、失败间隔、退避中关闭、恢复后自然 tick 不补跑）。
- Lead 独立初验：正式 core 19/runtime 243（239+4）/persistence 176/server 30 差额 0；**Architect 探针 1/1 转绿**（原 1/1 FAIL）；Lead 2 项探针通过（快速关闭零扫描、恢复不提前扫描/不补跑）。已提交 Architect 复核；B3 未启动。详见 `docs/context/tasks/cm2-ct09/B2_R1_Lead初验.md`。
- 无 git 操作与发布；CT10+ 未授权。

## 本次修改（2026-10-30，B2-R1 定点修复派发）

- Architect 首次独立复核 B2 未通过：`IdleCron.runTicks` 在默认 tick 源持续异常时只 WARN+continue，无可取消等待/取消检查；close 取消 loop 后卡在 NonCancellable join。探针（默认 tick + 极端 Clock）1/1 失败：500ms 内 clockReads 243→504578、state=CLOSING。B3 不放行。
- Lead 已建立 `docs/context/tasks/cm2-ct09/B2_R1修复委派单.md` 并派发 Worker `10264b13`（message `02d16ab5`）：仅改 `IdleCron.kt` + `IdleCronTest.kt`；每轮 tick 前 ensureActive；普通调度失败固定 1s 可取消退避（不加配置）后重算严格未来 tick；退避非 tick、不扫描/不补跑；CE 传播、close 不等满退避；补持续失败及时关闭、失败间隔、退避中取消、恢复后自然 tick 不补跑测试；Architect 探针必须转绿。
- B3 未启动；无 git 操作与发布。

## 本次修改（2026-10-30，B2 初验通过，提交 Architect）

- B2 Worker 交付 7 个新增文件（CountTrigger/IdleTrigger/IdleCron + 测试辅助 + 3 个测试）；B1 五文件 SHA 不变；未接线 Context/Factory、未改 SQL/索引/依赖。
- Lead 独立初验通过：runtime 214→239、core 19/persistence 176/server 30 差额 0；4 项 Lead 探针全绿（Count 4/5 阈值、极长 Duration 受控失败后恢复、Idle 真实重叠 skip、Cron 扫描中 tick 真实 skip）；Count CREATED/DUPLICATE 契约、Idle created_at 等号边界、Cron 严格未来/不补跑/关闭 join 均有真实 SQLite/注入 tick 用例。
- 非阻断观察：IdleCron 持续调度异常可能紧凑重试（正常系统时钟不可达），建议 B3/B4 加固；B3 未启动。详见 `docs/context/tasks/cm2-ct09/B2_Lead初验.md`。
- 无 git 操作与发布；CT10+ 未授权。
- **2026-10-30 提交受阻**：B2 复核请求（message `05c96dab`）在 Architect 侧因 429 Too Many Requests 中断，未获裁定；B2 保持 IN_REVIEW、B3 未启动，等待配额恢复后重提。

## 本次修改（2026-10-30，B1 DONE，B2 Count/Idle/Cron 派发）

- Architect 复核 B1 R1 收口：**B1 APPROVED / DONE**（探针边界裁定见 `架构与技术复核.md` §2）；独立五模块 454/454、格式通过；正式 XML 已恢复；两文件 SHA 一致。
- 按 §2.4 落 `docs/context/tasks/cm2-ct09/B2_委派单.md` 并派发 Worker `10264b13`（message `c4c3873d`）：仅 Count Trigger、Idle Trigger、IdleCron、真实 SQLite/注入 tick 测试；不接线 Context/Factory、不改 B1/配置/SQL/索引/依赖。要求真实重叠 skip、极大 Duration/Instant 受控失败不短窗口、Count 查 pending、CREATED/DUPLICATE 契约不变。
- B3/B4 未放行；CT10+、git 写操作、发布未授权。

## 本次修改（2026-10-30，CT09 B1 R1 修复完成，探针二待裁定）

- Worker 修复 R1（仅 `ExtractionScheduler.kt` 与 `ExtractionSchedulerCloseTest.kt`）：非关闭 CE 不再提前发布，改由独立 cleanup scope 的唯一 owner 取消 Worker、`queue.cancel`、真实 `join`（含结构化子任务）、执行清理钩子、释放 workerParent/scope 后再 CLOSED/发布共享结果；新增真实子任务屏障、清理失败共享、并发唯一清理 3 例。
- Lead 独立初验：正式 runtime 211→214，core 19/persistence 176/server 30 差额 0；Architect 探针一 FAIL→**PASS**；探针二仍 FAIL，但根因为探针 `withTimeout` 协程边界复制异常导致 `assertSame` 不可能成立（Lead 独立 3/3：无边界同一实例、边界 `cause === unique`、通用 `withTimeout` 复制对照）。已提交 Architect 裁定探针写法；B1 暂不 DONE，B2 未启动。详见 `docs/context/tasks/cm2-ct09/B1_R1_Lead初验.md`。
- 无 git 操作与发布。

## 本次修改（2026-10-30，CT09 B1 R1 定点修复派发）

- Architect 首次独立复核 CT09 B1 未通过：R1 为非关闭 CE 时 Worker 自行置 CLOSED 并提前发布 `closeOutcome`，外部 `closeAsync()` 绕过真实 join/清理；两个真实探针（子任务 finally 屏障未结束即 close 返回；`closeActionForTesting` 清理失败被跳过）2/2 失败。B2 不放行。
- Lead 已建立 `docs/context/tasks/cm2-ct09/B1_R1修复委派单.md` 并派发 Worker `10264b13`（message `2b643c3b`）：仅改 `ExtractionScheduler.kt` 与两个 Scheduler 正式测试；唯一清理 owner（非失败 Worker）真实 join Worker/子任务、释放 scope parent Job 与队列后发布共享结果；补子任务屏障、清理失败、并发 close 唯一清理回归；必须重跑 Architect 两个探针 2/2。
- 无 git 操作与发布；B2 未启动。

## 本次修改（2026-10-30，CT09 方案裁定与 B1 放行）

- CT08 DONE 保持；CT09 D1～D8 已裁定，见 `docs/context/tasks/cm2-ct09/Architect_裁定.md`。采用 context.extraction、固定上海五字段 Cron、查库 Count、单 Worker 触发计数；CT09 仅内部注入 Runner 验证接线，公共生产提取待 CT11。
- 关闭先封闭准入、取消并 join 真实后台任务，再释放 llm/vfs/executor；shutdownTimeout 仍只观察。CT09 分 B1 配置与 Scheduler、B2 Trigger/Cron、B3 生命周期、B4 全量验收；仅 B1 已授权，仍走现有 Paseo Lead→Worker→独立复核。
- Lead 已同步 `CT09_任务书.md` 正文/验收映射、修正配置示例引用（核心数据流 §7.3；技术实现设计 §7.3 是 Memory Map，不是 extraction 示例），并落 `docs/context/tasks/cm2-ct09/B1_委派单.md` 派发 Worker `10264b13`（message `bf469bbd`）。B1 仅配置/Loader/Runner Port/Scheduler 与关闭测试，不做 Trigger DB/Cron/Context 接线。
- B1 Worker 已交付 9 文件（Runner Port、ExtractionConfig/Loader、internal Scheduler、3 个测试、KDoc 订正）；Lead 独立初验通过：runtime 189→211、core 19/persistence 176/server 30/integration-context 15 差额 0，3 项 Lead 探针（cron cause、timezone 未知键、非关闭 CE）全绿；已提交 Architect 复核。详见 `docs/context/tasks/cm2-ct09/B1_Lead初验.md`。B2 未启动。
- 本轮无 git 写操作与发布；CT10+ 未授权。

## 本次修改（2026-10-29，CT08 DONE，放行 CT09）

- Architect 独立复核 B5（记录见 `docs/context/tasks/cm2-ct08/架构与技术复核.md` §11）：5 个交付文件 SHA 一致；统计 `calls=successes+failures`、attempts 实际求和、无 usage 样本 token 为 null、取消/CLOSED 不计不记、并发不丢计数；日志仅白名单字段、不传 Throwable、Key/Prompt/schema/body/URL 标记零泄漏；302 不追随且第二 host 0 请求；独立 `./scripts/check` 130/130、全模块 1296=1296、VFS 81 基线 all OK、Core 无 Ktor、3 项探针 3/3。**B5 APPROVED / DONE，CT08 DONE**。
- CT08 最终状态：B1～B5 全部通过 Architect 独立复核，A01～A08 闭环。**CT09 可按任务书流程启动**，仍须 Lead 先出方案/委派单并由 Worker 实施，未经 Architect 逐批复核不得继续后续任务。
- 仍未授权 `git add/commit`、远端推送或公开发布；CM2 后续任务按里程碑顺序推进。

## 本次修改（2026-10-29，CT08 DONE，CT09 只读方案核对完成待裁定）

- Architect 复核 B5 APPROVED / DONE，并裁定 **CT08 DONE、CT09 放行**（记录见 `docs/context/tasks/cm2-ct08/架构与技术复核.md` §11）；B5 探针 3/3、`./scripts/check` 130/130、全模块 1296=1296、VFS 81 基线 all OK。
- 按指令完成 CT09 **只读**方案核对：建立 `docs/context/milestones/cm2/CT09_任务书.md`（草案）与 `docs/context/tasks/cm2-ct09/CT09_方案核对与待裁定.md`。已核实 Count 通知 Port、`findIdleSessions`、pending 索引、cron-utils、Runtime 生命周期与 `context.extraction` 尚未消费等事实。
- 待 Architect 裁定 C1（配置分区 `context.extraction` vs 顶层 `extraction`）、C2（时区固定 vs 可配置）、C3/D3（无 CT11 Runner 时的生产接线策略）及 D1～D8 细节；**未派发 Worker、未实现代码**，CT10+ 不启动。

## 本次修改（2026-10-29，B5 初验通过，提交 Architect 裁定 CT08）

- B5（最终证据与回归）Worker 交付 5 个文件：`LlmCallDiagnostics.kt`（internal 诊断 + 按 task/profile 最小统计）、`KtorLlmClient.kt`（成功 INFO / 失败 WARN 各一条、统计接线）、`KtorLlmClientDiagnosticsTest`、`KtorLlmClientRedirectTest`、测试辅助 `Slf4jCapture`；未新增公开 API/`ContextStatus` 字段/落库记录。
- Lead 独立初验通过：`./scripts/check` **exit 0**（130 tasks，1m48s）；全模块源码 `@Test` 与 XML **1296=1296** 差额 0、0 失败/错误/跳过（runtime 176→189）；`vfs` 81 基线 **all OK**、`git status --porcelain vfs integration-tests/vfs` 为空；Core 无 Ktor 的 build 文本 + 主源码 import + compileClasspath + `CoreSkeletonTest`/`KtorBoundaryTest` 四重证据；302 不追随（第二 host 0 请求）；统计/日志脱敏与字段白名单由正式用例 + 3 项 Lead 独立探针覆盖。
- 已提交 Architect 独立复核；**CT08 是否 DONE 由 Architect 裁定**，未自标 DONE，CT09 未启动；仍无 `git add/commit` 与远端发布。详见 `docs/context/tasks/cm2-ct08/B5_Lead初验.md`。

## 本次修改（2026-10-29，B4 APPROVED / DONE，放行 B5）

- Architect 独立复核 B4（记录见 `docs/context/tasks/cm2-ct08/架构与技术复核.md` §10）：8 个交付文件 SHA 一致；准入先于校验/HTTP、`invokeOnCompletion` 移除、owner `NonCancellable`、共享失败发布/重抛、等待者取消、在途取消不转 `CLOSED`、Runtime 构造/释放顺序全部通过；独立实跑 core 19 / runtime 176 / persistence 176 / server 30 全零、spotless 通过、6 项 Lead 探针 6/6。**B4 APPROVED / DONE**。
- **B5 已获放行**：范围锁定为 CT08 方案 §j B5——最小内部统计（调用/成功/失败/Attempts/Usage）、脱敏日志捕获、重定向证据、`vfs` diff/81 基线、源码 `@Test`=XML、全量 `./scripts/check`；不新增公开 `ContextStatus` 字段，不启动 CT09。
- Lead 须先落 `B5_委派单.md` 再派发现有 Worker `10264b13-4fd6-4e6d-b699-b8095efd6ab6`；B5 未通过前 CT08 不 DONE。仍无 `git add/commit` 与远端发布。

## 本次修改（2026-10-29，B3 APPROVED / DONE，启动 B4）

- Architect 独立复核 B3（记录见 `docs/context/tasks/cm2-ct08/架构与技术复核.md` §9）：6 个交付文件 SHA 一致；总期限/remaining 重算、HttpTimeout 逐 Profile、429 三支与额度优先、Retry-After 五条规则、退避 ±20% 且 ≤10s、取消不包装全部通过；独立实跑 core 19 / runtime 166 / persistence 176 / server 30 全零、spotless 通过、8 项探针 8/8。**B3 APPROVED / DONE**。
- 按 Architect 指令启动 **B4（取消与关闭协议 + Runtime 生命周期接线）**，范围与验收锁定在 `docs/context/tasks/cm2-ct08/B4_委派单.md`：`KtorLlmClient` 的 `RUNNING/CLOSING/CLOSED` + `inFlight` + 共享 `closeOutcome`（结构化 LAZY 子任务、`invokeOnCompletion` 移除、owner `NonCancellable`、等待者可取消、失败发布/重抛、`close()`→`closeAsync()` 重命名），`AlcyoneContextImpl` 的 `executor→vfs→llm` 构造与 `llm→vfs→executor` 释放；A06 五类 + A01 完整 + A08 关闭后新调用。
- B4 不做 B5 日志/统计/重定向/全量 A08；B4 未通过前不启动 B5。仍无 `git add/commit` 与远端发布。
- **B4 Lead 初验通过（2026-10-29）**：8 文件（`KtorLlmClient.kt` 关闭协议、`AlcyoneContextImpl.kt` 接线、2 个新测试、`KtorLlmClientResponseTest` 定点调整、3 个测试机械重命名）；四模块 core 19 / runtime 176 / persistence 176 / server 30 差额 0；6 项 Lead 独立探针全绿（准入优先、并发关闭共享失败、在途取消后仍可用、并发关闭后新调用 0 HTTP、释放顺序 LLM→VFS→EXECUTOR、启动失败逆序清理）。已提交 Architect 复核，B5 未启动。详见 `docs/context/tasks/cm2-ct08/B4_Lead初验.md`。

## 本次修改（2026-10-29，B2 APPROVED / DONE，启动 B3）

- Architect 独立复核 B2（记录见 `docs/context/tasks/cm2-ct08/架构与技术复核.md` §8）：7 个交付文件 SHA 一致、core 只追加 `LlmRequest.schema`、协议/路由/预算/响应分类/异常链不泄漏全部通过；独立实跑 core 19 / runtime 147 / persistence 176 / server 30 全零、spotless 通过、Lead 6 项探针 6/6、core 编译类路径无 Ktor。**B2 APPROVED / DONE**（第 1 轮 R1 安全缺陷已在第 2 轮关闭）。
- 按 Architect 指令启动 **B3（总期限、有限重试、Retry-After、退避与取消传播）**，范围与验收锁定在 `docs/context/tasks/cm2-ct08/B3_委派单.md`：新增 `LlmRetryPolicy`、`KtorLlmClient` 期限/重试循环、`HttpTimeout` 逐 Profile、A05 脚本化测试；允许对 `KtorLlmClientResponseTest` 做 2 处最小定点修改（分类/传输用例设 `maxAttempts=1`）。
- B3 不做 B4 关闭协议/生命周期、B5 日志统计；B3 未通过前不启动 B4。仍无 `git add/commit` 与远端发布。
- **B3 Lead 初验通过（2026-10-29）**：仅 6 文件变化（`LlmRetryPolicy.kt`、`KtorLlmClient.kt`、3 个新测试/stub、`KtorLlmClientResponseTest` 两例仅加 `maxAttempts=1`）；四模块 core 19 / runtime 166 / persistence 176 / server 30 差额 0，8 项 Lead 独立探针全绿（重试、attempts、Retry-After 超预算、额度优先、精确等待、退避中取消、总期限非取消、注入 client 无 HttpTimeout）。已提交 Architect 复核，B4 未启动。详见 `docs/context/tasks/cm2-ct08/B3_Lead初验.md`。

## 本次修改（2026-10-29，B1 APPROVED / DONE，启动 B2）

- Architect 独立复核 N3 修复（记录见 `docs/context/tasks/cm2-ct08/架构与技术复核.md` §7）：两文件 SHA 一致，core/runtime 负值双入口均 `INVALID_REQUEST`、不 clamp、`0` 合法、饱和与分类正确；独立实跑 core 19 / runtime 119 / persistence 176 / server 30 全零、spotless 通过、原反例探针 2/2 转绿。**B1 APPROVED / DONE**。
- 按 Architect 指令启动 **B2（LLM Adapter 与路由）**，范围与验收锁定在 `docs/context/tasks/cm2-ct08/B2_委派单.md`：`LlmRequest.schema`、`KtorLlmClient`（单次调用，无期限/重试）、content 本身 JSON 校验与围栏拒绝、最小释放路径，验收 A03/A04/A07 响应侧/A08 core 无 Ktor。
- B2 不做 B3 期限重试、B4 关闭协议与生命周期接线、B5 日志与统计；B2 未通过前不启动 B3。仍无 `git add/commit` 与远端发布。
- **B2 两轮初验**：第 1 轮功能主路径与 26 例新增测试通过，但 R1 阻断——schema/content 解析失败的原始 `SerializationException` 作为 `cause`，其消息含输入原文，经 `stackTraceToString()` 泄漏请求 schema 与响应 content。Worker 修复后第 2 轮通过：仅 3 文件变化（`KtorLlmClient.kt` + 两个测试文件），解析/传输异常不再链接原始异常链，新增 2 例泄漏回归；四模块 core 19 / runtime 147 / persistence 176 / server 30 差额 0，6 项独立探针全绿。详见 `docs/context/tasks/cm2-ct08/B2_Lead初验.md` §5、§8；已提交 Architect 复核，B3 未启动。

## 本次修改（2026-10-29，CT08 B1 N3 修复完成 Lead 初验，提交 Architect）

- B1 因阻断缺陷 N3（`LlmBudget` 未校验 Estimator 返回非负，负数可绕过预算）被 Architect 判未通过；原 Worker `d7695b22` 归档后，按 Architect 指示由新 Paseo Worker `10264b13-4fd6-4e6d-b699-b8095efd6ab6`（配置 `worker`）执行修复，范围仅 `LlmBudget.kt` 与 `LlmBudgetTest.kt`。
- 修复：`estimate()` 在算术前校验 `estimatePrompt`/`estimateSchema` 非负，负值 fail closed 抛 `INVALID_REQUEST`（不 clamp、不回显请求/Schema/Key/URL），`0` 合法，`Int.MAX_VALUE×2` 仍饱和为正并 `LOCAL_BUDGET_EXCEEDED`；新增 5 例正式回归（`LlmBudgetTest` 9→14）。
- Lead 独立初验通过：两文件 SHA 与 Worker 一致（`f1887e2a…`、`924f1064…`），core 19 / runtime 119 / persistence 176 / server 30，源码与 XML 差额全 0、0 失败/错误/跳过；Architect 反例探针由 2/2 失败转为 2/2 通过，Lead 自建双入口探针 2/2 通过。详见 `docs/context/tasks/cm2-ct08/B1_N3_Lead初验.md`。
- Lead 独立初验通过并已提交 Architect；**Architect 复核裁定 B1 APPROVED / DONE**（`架构与技术复核.md` §7），N3 关闭。
- B2 已按指令启动，范围见 `docs/context/tasks/cm2-ct08/B2_委派单.md`；B2 未通过前 B3 不启动；仍未授权 `git add/commit` 与远端推送。

## 本次修改（2026-10-29，CM1 全面复查通过，放行 CM2）

- **CM1 全面复查 CLOSED**：F1、P1、P4、F2、F3、P2、P3、F1-R1 八项逐项 APPROVED（Lead 初验 + Architect 独立复核，独立批次未合并），F4 入口文档归位完成；历史验收记录保留。
- **整体回归通过**（Architect 独立执行）：`./scripts/check` exit 0（130 tasks，常规 **1188=1188**，14 个模块源码 `@Test` 与 XML 差额全 0）；`vfs/`、`common`、`integration-tests/vfs` tracked diff 为空（81 VFS 基线 mismatch=0，VFS 生产零侵入）；`./scripts/t31-consumer-check` exit 0。
- **CM1 DONE（含全面复查）**；按用户条件授权启动 **CM2**，首个任务 **CT08 Memory Extraction 纵向骨架**（范围与 Q1～Q8 裁定见 `docs/context/milestones/cm2/CM2_范围与依赖方案.md`）。CM3～CM6 未启动。
- 仍未授权：`git add/commit`、远端推送、公开发布。详见 `docs/context/milestones/cm1/CM1_全面复查.md`。

## 本次修改（2026-10-29，CM1 全面复查 F4：入口状态归位）

- 当前真实状态：**CM1 历史验收已通过**（CT01～CT07 全部 DONE；整体初验常规 1142=1142、138 项源码清单、81 VFS 基线、独立 VFS Runtime 消费与 `t31-consumer-check` 通过，历史证据保留），但用户要求的**全面复查仍有开放项**（F4 文档入口与整体回归），不得表述为“复查通过”。
- 复查已关闭项（逐项 Lead 初验 + Architect 独立复核 APPROVED，未合并）：F1 通知关闭真实等待与重入拒绝；P1 连接所有权与关闭轮次内有限重试；P4 单语句失败不 poison（与事务终结失败明确区分）；F2 非法 UTF-8 固定 400 且零落库、限额先于解码；F3 配置/注册三入口统一校验且重复 runtimeId/Key 不静默覆盖；P2 分页参数在 SQL 前校验（`IllegalArgumentException`，不新增 API 枚举）；P3 `updated_at` 全局水位仍为调用方契约、不新增 CAS；F1-R1 由 Architect 实跑发现的 `notificationJobs.toList()` 快照竞态。
- 本轮 Architect 独立实跑：persistence 176、runtime 89、server 30、core 11，0 失败/错误/跳过，源码 `@Test` 与 XML 差额 0；`runtime`/`server` 连续 5 次重复跑无竞态复现。整体回归（`./scripts/check` + `t31-consumer-check`）待执行。
- **CM2 条件授权**：上述闭环与整体回归通过后**直接启动 `CT08`**，无需再次请示；当前 CM2 未启动。未 `git add/commit`、未公开发布。详见 `docs/context/milestones/cm1/CM1_全面复查.md` 与 `docs/context/tasks/cm1-full-rereview/`。

## 本次修改（2026-10-29，CT07-B1首次复核）

- B1独立9/69/155及格式通过，8项SHA与81基线匹配；但静态审查确认Instant无法校验冻结的输入offset、fire-and-forget通知失败被空吞且close直接取消。B1 IN_REVIEW，B2不放行。见CT07复核第1节。

## 本次修改（2026-10-29，CT06整体收口与CT07启动）

- CT06独立完整常规1097/1097、格式、原VFS Runtime消费、81基线/118清单及契约复核通过，B1/B2及整体集成均关闭；CT06 DONE。见CT06复核第7节。
- 授权原Paseo Lead按Lead→Paseo worker推进CT07 Ingestion与最小HTTP，先方案后实现、逐批初验和独立复核；CM1完成6/7，CM2不启动。

## 本次修改（2026-10-29，CT06-B2 收口与整体验收）

- B2配置快照修复与并发测试判据经Architect独立49例、格式、两组真实探针验证通过，81基线匹配；B1/B2均APPROVED。见CT06复核第6节。
- 授权原Paseo Lead组织CT06整体集成初验并提交Architect；CT06尚未DONE，CM1仍5/7，CT07待前置，CM2不启动。

## 本次修改（2026-10-29，CT06-B1 收口与 B2 放行）

- B1 R1/R2 经 Architect 独立 22 例、格式及三条真实路径探针验证关闭，81 基线匹配，B1 APPROVED。详见 CT06 架构与技术复核第3节。
- 原 Paseo Lead 复用现有 Worker 推进 B2 Runtime 生命周期与集成，保持逐批初验和独立复核；CT06 IN_PROGRESS，CM1 5/7，CT07 待前置，CM2 不启动。

## 本次修改（2026-10-28，CT05整体收口与CT06启动）

- CT05独立常规1050/1050及格式通过，81基线/104项清单匹配，B1～B3均通过；CT05 DONE。见CT05复核第8节。
- 授权原Paseo Lead按Lead→Paseo worker推进CT06配置与Runtime，先方案后实现、逐批初验和独立复核；CM1完成5/7，CT07待前置，CM2不启动。


## 本次修改（2026-10-28，CT04整体收口与CT05启动）

- CT04独立常规973/973、格式及原VFS Runtime消费验证通过，81基线/87项清单匹配，F1/F2/F3均关闭；CT04 DONE。见CT04复核第20节。
- 授权原Paseo Lead按Lead→Paseo worker推进CT05 Repository，先方案后实现、逐任务初验和独立复核；CT06/CT07待前置，CM2不启动。CM1完成4/7。


## 本次修改（2026-10-28，F3独立收口）

- F3 rev5独立91例、格式与三组探针通过，第18节问题关闭；F1/F2/F3均APPROVED。授权原Paseo Lead组织CT04整体集成验收并提交Architect；CT04仍IN_REVIEW，CT05未放行。详见CT04架构与技术复核第19节。生产修复仍仅由Paseo worker承担。

## 本次修改（2026-10-28，F3 rev2复核与方案重审）

- 独立83例通过，但真实反例仍确认JDBC重载绕过、手动提交使失败业务落库、普通read异常被覆盖、注释Savepoint绕过；正式测试未增加。F3 rev2未通过，详见CT04复核第14节。
- 原Lead先修订接口边界/事务状态与回归映射方案，Architect审阅后继续Worker编码。F1/F2保持通过；CT04 IN_REVIEW，CT05未放行。

## 本次修改（2026-10-28，CT04-F3 首次独立复核）

- 独立83例/格式通过，但真实探针确认旧驱动复活、物理连接逃逸、prepared跨次回归、事务失败后仍复用、读原异常覆盖与nullable写返回回归。F3未通过，详见CT04复核第13节，交原Paseo Lead修复。
- F1/F2保持已通过；CT04 IN_REVIEW，CT05未放行。

## 本次修改（2026-10-28，CT04-F2 独立收口与F3放行）

- F2 APPROVED：生产中断清理/回调守卫反例通过，A04测试尾项关闭；本轮定向A04及格式通过，沿用此前独立70例，生产哈希/81基线不变。见CT04复核第12节。
- 授权原Paseo Lead按Lead→Paseo Worker推进F3事务失败与作用域边界，先方案后实现。F1/F2已通过；CT04仍IN_REVIEW，CT05未放行。

## 本次修改（2026-10-28，CT04-F2 首次独立复核）

- 独立67例与格式检查通过，但真实反例确认同步关闭中断后连接未释放、queryRaw回调内关闭死锁；新增测试A03提前兜底关闭、A04等待判据不足需修正。详见CT04架构与技术复核第8节。
- F2退回原Paseo Lead组织Worker修复，F1保持APPROVED；CT04 IN_REVIEW，F3/CT05未放行。

## 本次修改（2026-10-27，CT04-F1 独立收口与F2放行）

- F1文件保护及测试尾项通过Architect独立复核：强制60例与格式检查通过，最终12项交付哈希/81基线一致。详见CT04架构与技术复核第7节。
- 授权当前Paseo Lead `6f547b7e-4ced-4f1b-92a7-e665b2d008db` 按Lead→Worker推进F2资源所有权与关闭协议，先方案后实现；只能使用Paseo worker配置委派。CT04仍IN_REVIEW，F3/CT05未放行，CT01～CT03 DONE保持。

## 本次修改（2026-10-27，按用户要求重建Paseo Lead）

- 用户明确Lead和Worker必须使用Paseo中对应的`lead`/`worker`配置，不得用其他角色配置或自行选模型替代。本次重新读取配置后，创建Lead `6f547b7e-4ced-4f1b-92a7-e665b2d008db`（server `srv_rWwNLOnyU5IK`；lead配置：pi / my_magpie/group/worker，high）；旧Codex Lead `989b31a6-dfdb-43a1-9e4b-489f6e7b588a`已归档，历史保留。
- 新Lead只能通过Paseo的worker配置委派实现，先与现F1 Worker `720e6ddc-e9f8-414c-a8ae-ffe7414f0bab`交接写入权，再继续正式回归覆盖、文档与参数校验顺序收尾；最终源码需重新独立初验并交Architect。首轮核心11探针通过不是F1最终验收，F2/F3/CT05仍未放行。

## 本次修改（2026-10-27，CM1 Lead 故障恢复交接）

- 原Lead `7af1005e-bca3-4ed1-bb34-6ea8f288740c` 连续模型错误/上游超时，重试与同Agent模型切换均未恢复。已通过Paseo创建接任Lead `989b31a6-dfdb-43a1-9e4b-489f6e7b588a`（Codex，依据已配置architect模型参数承担Lead职责），同一CM1、同一工作区，保留历史，不重置代码。
- 接任Lead先完成Worker写入权交接，再只推进CT04-F1；仍按Architect→Lead→Worker及F1/F2/F3逐批复核，CT05不放行。原Lead不再继续委派，用户无需重新授权开发范围。

## 本次修改（2026-10-27，CT04二次复核与公共改名收口）

- 公共类型`SequenceIdGenerator`/`TimeCodec.format/parse`改名独立验证通过（受影响79例与common-only消费）。CT04二次复核仍未通过，原五项核心反例仍成立；按F1文件保护、F2资源关闭、F3事务/作用域三批修复并逐批复核，CT05未放行。详见CT04架构与技术复核第4节。

## 本次修改（2026-10-26，用户授权公共类型命名调整）

- 用户要求公共类型去掉 Shanghai 前缀：采用 `SequenceIdGenerator` 与 `TimeCodec`，时间方法采用 `format` / `parse`；保持现有时区、格式和算法行为，不新增时区配置或兼容别名。已交原 Lead 协调 Worker 同步引用并提交独立复核，当前待完成。
- CT03 既有行为验收保持；此次命名调整单独复核。CT04 R1～R6 修复继续，CT05 未放行。历史证据中的旧名保留，当前接入说明随实现同步。

## 本次修改（2026-10-26，CT04 首次独立复核）

- 独立常规 929/929 通过，但故障/竞争探针确认连接泄漏、关闭与作用域绕过、建库保护失效、失败事务提交及异常泄漏；R1～R6 待修。CT04 IN_REVIEW，CT05 未放行，详见 `docs/context/tasks/cm1-ct04/架构与技术复核.md`。CT01～CT03 DONE 保持。

## 本次修改（2026-10-26，公共能力迁移独立验收通过）

- CT03 含 common 公共能力迁移独立复核通过，884/884 常规回归及 common-only Java 消费成功；CT03 DONE，授权原 Lead 恢复 CT04，仍须逐任务复核。81 个既有基线文件不变，common 新增文件为用户授权范围。详见 `docs/context/tasks/cm1-ct03/架构与技术复核.md` 第 5 节。


## 本次修改（2026-10-26，用户授权公共能力迁移）

- 用户要求将 Context Core 时间编解码和 ID 生成算法迁入 common，抽象可复用公共能力。授权新增 common 工具/测试及 Core 依赖和薄适配；本次覆盖此前 common 零修改限制，既有 VFS 不变。
- CT03 重新进入增量开发，历史880例验收结论保留；迁移须 Lead 初验与 Architect 独立复核，CT04 等待迁移通过后继续，保留其已产生工作。

## 本次修改（2026-10-26，Context CT03 收口）

- CT03 R1～R3 关闭，独立常规880/880与Java copy反例修复验证通过，CT03 DONE；原Lead获授权启动CT04 Schema/连接/生命周期。VFS/common基线81文件不变；见 `docs/context/tasks/cm1-ct03/架构与技术复核.md` 第4节。

## 本次修改（2026-10-25，Context CT02 收口）

- CT02 独立常规 840/840、依赖封装修复定向验证通过，CT02 DONE；原 Lead 获授权启动 CT03 模型、Port、时间/ID，仍须逐任务复核。VFS/common 基线 81 文件不变；见 `docs/context/tasks/cm1-ct02/架构与技术复核.md` 第 4 节。

## 本次修改（2026-10-25，Context CT01 收口）

- CT01 独立兼容性探针全通过、契约 R1～R4 关闭，CT01 DONE；原 CM1 Lead 获授权启动 CT02 工程骨架，CT03～CT07 仍须逐任务复核。见 `docs/context/tasks/cm1-ct01/架构与技术复核.md` 第 5 节；VFS 生产代码不变。

## 本次修改（2026-10-25，用户授权启动 Context CM1）

- 用户授权 CM1（CT01～CT07）连续开发，Architect 经 Paseo 按 Lead → Worker 委派，每项 Lead 初验后立即由 Architect 独立复核，通过后继续；CM1 全部完成再向用户汇报，不启动 CM2。
- Context 当前设计、任务、复核与进度统一放 `docs/context/`，入口见 `docs/context/README.md`、`docs/context/milestones/cm1/CM1_任务与验收.md`；原“不要创建 Context 模块”等旧阶段限制不适用于已授权 CM1。
- 用户已确认 Context Runtime 解析 YAML 的 vfs 分区并构造既有 VfsRuntimeConfig，不增加 VFS Loader、不修改 VFS。CM1 保持既有 VFS 生产代码与行为不变；Map 安全替换/大备份发布仅记录后续方案，不阻塞 CM1、不提前实施扩展。

## 本次修改（2026-10-17，T36与E1独立验收通过）

- Architect独立常规828/828、WebDAV43/43、原Runtime消费、插件消费全部通过；文档示例原样提取编译通过，原Runtime依赖图不含插件，既有六模块Diff为零。T33～T36及E1 **DONE**，仅本地交付，不公开发布或自动启动新扩展。详见 `docs/tasks/e1-t36/T36_架构与技术复核.md` 第12节；历史状态以本条为准。


## 本次修改（2026-10-17，T36首次独立复核）

- T36首次复核未通过：真实订阅/内容断言、消费成功判据、辅助进程超时清理、文档证据R1～R4待修。T36 IN_REVIEW，E1 IN_PROGRESS；详见T36架构与技术复核。T33～T35 DONE保持。


## 本次修改（2026-10-17，T35收口与T36启动）

- T35独立825/825与计时任务退出探针通过，R1～R4关闭，T35 DONE。原Lead获授权启动T36（IN_PROGRESS），E1待最终集成验收；既有VFS零侵入、仅本地验证发布保持。


## 本次修改（2026-10-16，T35首次独立复核）

- T35独立模块62例通过；引用释放、关闭取消、排队事件防抖及生产日志R1～R4待修，保持IN_REVIEW。见T35架构与技术复核。T34 DONE，T36不启动，E1 IN_PROGRESS。


## 本次修改（2026-10-16，T34收口与T35启动）

- T34独立799/799通过，R1～R5关闭并标DONE，见T34复核第10节。授权原Lead启动T35，当前T35 IN_PROGRESS，T36 TODO，E1 IN_PROGRESS；既有VFS零侵入与逐任务复核保持。


## 本次修改（2026-10-16，T34 首次独立复核）

- T34 独立常规 777/777 全绿，但探针确认错误脱敏、提交失败误判、多目标推送及清理失败后继续执行；R1～R5 待修。当前 T34 **IN_REVIEW**，T33 DONE，T35/T36 不启动；详细意见见 `docs/tasks/e1-t34/T34_架构与技术复核.md`。


## 本次修改（2026-10-16，T33 收口与 T34 启动）

- T33独立764/764通过，R1～R3关闭并标DONE，见 [复核第6节](docs/tasks/e1-t33/T33_架构与技术复核.md#6-修复后独立收口复核2026-10-16)。已授权原Lead启动T34，T35/T36待前置；E1仍IN_PROGRESS，既有VFS零侵入。

## 本次修改（2026-10-16，T33 首次独立复核）

- 独立常规758/758通过、零失败/错误/跳过；T33配置、Cron契约和验收证据R1～R3待修，当前IN_REVIEW。见 [复核记录](docs/tasks/e1-t33/T33_架构与技术复核.md)。已交Lead组织修复，T34不启动；涉及当前安排和任务状态。

## 本次修改（2026-10-16，用户授权启动 E1）

- 用户已授权开工，建立 [E1 里程碑与 T33～T36 任务](docs/tasks/e1-git-backup/E1_Git本地备份里程碑.md)，当前 T33 IN_PROGRESS，T34～T36 TODO；此前“仅设计/未启动”表述为历史状态。
- Architect 通过 Paseo 协调 Lead → Worker，逐任务初验和独立复核后继续；用户已授权常规推进，无需重复确认。不扩大范围、不发布真实远端。涉及当前安排、任务入口与协作规则。

## 本次修改（2026-10-16，Git 备份 Cron 与日志简化）

- 按用户要求更新 [Git 备份设计](docs/vfs/Git_本地目录备份设计_v0.1.md)：五字段 Cron（默认 `0 3 * * *`）替代每日固定时间，使用本机时间，不提供 zoneId。
- 删除公开状态查询和请求结果模型，每次同步记录开始与成功/失败日志；仅保留内部串行调度与资源释放。涉及 Git 当前设计与 E1 规划，仍为独立插件、仅文档设计，未启动开发。

## 本次修改（2026-10-15，Git 备份独立插件化）

- 按用户要求修订 [Git 备份设计](docs/vfs/Git_本地目录备份设计_v0.1.md)：宿主通过既有 `AlcyoneVfs.subscribe` 连接独立插件，配置、60 秒防抖、每日/启动/手动触发与生命周期全部由插件管理。
- 取消 Runtime 新增配置、方法、组装及关闭逻辑；现有 VFS 生产代码与 Runtime 依赖保持不变，插件作为可选产物。涉及 Git 设计入口及当前 E1 规划，仍仅设计讨论、未启动开发。

## 本次修改（2026-10-15，Git 本地备份设计草案）

- 新增 [Git 本地目录备份设计 v0.1](docs/vfs/Git_本地目录备份设计_v0.1.md)：模块、配置、主要用例、调用流程、类与目录布局及验收方向。
- 用户确认本地 Mount 根作为专用工作区，60 秒防抖、每日/启动/手动触发；本版不做消费位点、重放、自动冲突处理或 `.git` 保护，E05 非前置。涉及后续 Git 设计入口；旧规划中的可靠消费要求不适用于本版。
- 当前仅设计讨论，未建立模块或启动开发，M0～M6 DONE、E01～E03 BACKLOG；不要依据本文自动启动实现或发布。

## 本次修改（2026-10-14，caef0b8 独立收口）

- R1～R3 全部关闭，T32 / M6 **DONE**，M0～M6 首版本地交付完成，不自动启动扩展或公开发布。涉及当前状态与验收结论；见 T32 架构与技术复核第 7 节。本轮仅文档复核，沿用 dfd867d 独立 752 / 43 与消费通过证据，重新核对源码/XML 差额零，未重复执行三项命令。

## 本次修改（2026-10-14，T32 复核第 6 节收尾）

- 落实 T32 架构与技术复核第 6 节：R1 语义收尾（流与 ByteArray 限额明确解耦、物理删除在同一事务内标记删除清理元数据并追加事件后提交再异步通知、worker 退出条件收窄、移动限额限定于复制回退/跨 Mount 路径、事件队列默认容量 256 可配置）与 R2 证据归属收尾（明确区分开发方自测实跑与复核方独立实跑，回填 dfd867d 为文档收尾提交）。
- 保持 T32 为 IN_REVIEW，M6 为 IN_PROGRESS，生产业务代码与正式测试零改动。

## 历史修改（2026-10-14，dfd867d 修复独立复核）

- R3 当前指引关闭；R1 尚需流/移动限额、删除事务顺序与 worker 退出条件收尾，R2 尚需执行记录归属及提交号回填。见 T32 架构与技术复核第 6 节；T32 IN_REVIEW，M6 IN_PROGRESS，生产与正式测试零改动。涉及当前验收结论与任务入口。

## 本次修改（2026-10-14，T32 复核修复 R1～R3）

- 落实 T32 独立复核意见 R1～R3 及建议优化：
  - R1：删除不存在的 `VfsMountConfig`，纠正为 `VfsRuntimeConfig.namespaces` 与 `mounts: List<MountConfig>`，挂载路由为最长完整路径段匹配；
  - R3：更新“开发与验证”末段过期指引，彻底删除 227/461 例及“Runtime 编排与 SDK 入口尚未实现，不得声称 SDK 文件操作已验收”历史残留，更新为 M0～M6 全量开发已完成、常规 752 / WebDAV 43 全绿、六模块本地打包发布与独立 JVM 消费已闭环验证，提供 check、webdav-check、t31-consumer-check 三个有效验证入口；
- 涉及当前工程指引与语义说明，生产代码与正式测试零改动。

## 历史修改（2026-10-14，0ee2b37 独立复核）

- 本轮独立常规 752 / WebDAV 43 与本地消费验证通过，源码/XML 差额零；R1 接口与保证范围、R2 环境/证据归档、R3 当前工程指引待修。详见 T32 架构与技术复核第 2 节；T32 IN_REVIEW，M6 IN_PROGRESS，不启动扩展或公开发布。涉及当前结论与验收入口，生产代码未改动。

## 本次修改（2026-10-14，T32 开发完成，状态 IN_REVIEW）

- 完成 [T32 首版交付记录与后续需求整理及验收](docs/tasks/m6-t32/T32_首版交付记录与后续需求整理及验收.md)：交付面向接入者的《首版交付记录》（`docs/vfs/首版交付记录.md`）与面向演进规划的《后续需求清单》（`docs/vfs/后续需求清单.md`）；更新 AGENTS 当前工程指引与状态；归档 A01～A08 及 M0～M6 完成证据核对表。
- 依次实跑并通过常规 752 例（`./scripts/check`）、真实 WebDAV 43 例（`./scripts/webdav-check`）及独立最小 JVM 消费工程（`./scripts/t31-consumer-check`），源码 @Test 与 XML 差额严格为零；生产代码与测试代码零变更。
- T32 转为 **IN_REVIEW**，M6 保持 **IN_PROGRESS**，等待独立复核；不自动启动扩展任务，不进行公开发布。

## 历史修改（2026-10-14，安排 T32）

- 新增 T32 首版交付记录与后续需求整理任务，明确交付范围、证据归档、AGENTS 当前指引更新及 A01～A08。T31 DONE，T32 TODO（已安排），M6 IN_PROGRESS，基线 752 / WebDAV 43；本轮仅准备文档。涉及任务入口与当前安排，不自动启动扩展或公开发布。

## 本次修改（2026-10-14，203b903 独立收口）

- R2 插件版本单一来源确认关闭，R1 保持关闭；本轮独立一键消费、clean 编译运行及 752 / 43 回归全部通过，源码/XML 差额零。T31 **DONE**，M6 IN_PROGRESS，不开始 T32。涉及当前状态与验收结论；详细证据见 T31 架构与技术复核第 7 节，版本一致不等同未来 ABI 兼容保证。

## 本次修改（2026-10-14，T31 修复独立复核）

- e9c54ff / d0bbf0a：R1 工具链隔离关闭，R2 仓库排他与库版本来源已落实，仅消费工程 Kotlin 插件仍硬编码 2.2.21 待修。独立一键消费与 clean 编译/运行通过；详见 T31 架构与技术复核第 6 节。T31 IN_REVIEW，不开始 T32；涉及当前结论与任务入口。

## 本次修改（2026-10-13，T31 复核修复 R1/R2）

- 落实 R1 工具链隔离：为独立消费工程配置 `gradle.properties`（禁用 auto-detect / auto-download，受控 JVM 策略）、限定 Kotlin daemon runFilesPath 在 `.local/tmp/kotlin-daemon`，`run-consumer.sh` 引入 `install.sha256` 完整校验；
- 落实 R2 SDK 仓库排他与依赖版本单一来源：消费工程通过 Version Catalog 读取第三方版本（协程、SLF4J），`mavenCentral` 排除 SDK group；
- 落实建议优化：`ConsumerMain.kt` 补充流 SHA-256 哈希校验、移动事件目标 URI 断言与临时目录清理返回值检查；收窄发布目标表述为“本任务命令仅发布至项目目录”；明确 Mac 发布产物 native classifier 固定说明；
- 状态保持 **IN_REVIEW**，双套套件 752 / 43 及消费工程端到端全绿，不开始 T32。

## 历史修改（2026-10-13，T31 独立复核）

- 4374785 发布与独立消费实跑通过，六模块元数据/产物来源核对正确；R1 消费工具链隔离、R2 SDK 仓库排他与第三方版本来源待修，详见 T31 架构与技术复核。T31 保持 IN_REVIEW，不开始 T32；涉及当前任务状态与验收结论。

## 本次修改（2026-10-13，T31 开发完成，状态 IN_REVIEW）

- 完成 [T31 SDK 打包与本地消费开发及验收](docs/tasks/m6-t31/T31_SDK打包与本地消费开发及验收.md)：为 6 个必要模块配置 Maven 本地发布（`.local/sdk-repository/`，版本 `0.1.0-SNAPSHOT`），排除测试与根聚合；建立独立最小 JVM 消费工程（`tools/validation/t31-consumer`），仅通过坐标依赖闭环验证公开 Runtime 完整生命周期及重开一致性；新增 `./scripts/t31-consumer-check`；更新 README.md 与 SDK 使用指南；主工程常规 752 例、WebDAV 43 例全绿，源码与 XML 差额为零。T31 转为 **IN_REVIEW**，M6 保持 IN_PROGRESS，不开始 T32。

## 历史修改（2026-10-13，安排 T31）

- 新增 T31 SDK 打包与本地消费开发及验收入口，覆盖六模块本地产物、独立 JVM 消费、native/传递依赖、私有工具链及 A01～A08。T30 DONE，T31 TODO（已安排），M6 IN_PROGRESS，基线 752 / WebDAV 43；本轮仅准备文档，不修改代码/构建、不发布、不开始 T32。涉及任务入口与当前安排。

## 本次修改（2026-10-13，T30 独立收口）

- 484a49d 收口复核：R1～R5 关闭，复核方补完整冷备探针归档并独立实跑通过，直接订正文案残留；独立常规 752 / WebDAV 43 通过，源码/XML 差额零。T30 **DONE**，M6 IN_PROGRESS，不开始 T31 / T32；涉及当前状态与验收结论，详见 T30 架构与技术复核第 7 节。生产代码与正式测试未改动。

## 本次修改（2026-10-13，fcdba48 独立复核）

- R1 主要示例订正与 R5 凭据/环境边界已落实；R2 语义收尾、R3 底层补删源建议、R4 冷备份证据仍待修。RuntimeReopenIdentityTest 仅验证原库重开，不执行备份恢复；不能据此标 A06 达成。详见 T30 架构与技术复核第 6 节；T30 保持 IN_REVIEW，M6 IN_PROGRESS，不开始 T31 / T32。涉及当前验收结论与任务入口。

## 本次修改（2026-10-13，T30 复核修复 R1～R5）

- 完成独立复核意见 R1～R5 的全部修正（修改 README.md、SDK 使用指南、SDK 运维说明及 T30 任务文档；零生产代码与零正式测试代码变动；双套回归检查持续 752/43 全绿，无源码/XML 差额）：
  - R1：修正不存在的 API/事件名（`FILE_DELETED` / `DIRECTORY_DELETED`、`NodeMetadata()`）；修正 SDK 使用指南事件等待范例（明确新建/覆盖匹配、加超时边界、删除 `val subscription`）；澄清默认终端输出为 Gradle 任务结果，详细观测输出在 XML 报告 `system-out`，补齐 `vfs/api` 相对路径；
  - R2：对齐配置与运行时机制（`storageKey` 归并与冲突、路径映射简例、状态库目录约束、`list` 物理与配置合并、`move` 原生与跨 Mount 编排、`defaultReadMaxBytes` / `defaultWriteMaxBytes` 限额命名与 `ReadOptions` 收紧语义、Consumer 异常分层机制、SQLite 单连接机制与 `inFlight.done` 宽限期等待语义）；
  - R3：纠正失败模型与处置建议（错误码与 effect 映射仅限预检为 NONE，运行时支持 PARTIAL/UNKNOWN；跨后端移动失败停止写入只读核对，禁止盲目 stat 后 delete 源端；覆盖六类典型运维诊断；保留 CancellationException 独立语义）；
  - R4：撤回无依据的 WAL/checkpoint 保证，对齐单连接默认 DELETE 日志模式及单文件布局；验收记录补充独立可复现冷备探针实测数据（`RuntimeReopenIdentityTest` 2 例全绿，单文件 `state.db` 备份恢复断言全通过）；
  - R5：收窄凭据脱敏范围至受管配置打印、挂载身份串及真实 WebDAV 认证异常，说明系统无全局脱敏器；澄清 Python 环境依赖；撤回交付与验收过强表述。
- T30 保持 **IN_REVIEW**，M6 保持 IN_PROGRESS，不开始 T31 / T32。

## 本次修改（2026-10-13，T30 开发完成，状态 IN_REVIEW）

- 第 34 条由 TODO（已安排）改为 **IN_REVIEW**：完成面向使用者的三份产品文档（根目录 `README.md`、`docs/vfs/SDK_使用指南.md`、`docs/vfs/SDK_运行维护说明.md`）及任务目录交付记录；用例使用真实公共入口验证，端到端实机验证离线备份隔离恢复流程。常规回归 752 例、真实 WebDAV 43 例全部通过，源码与 XML 差额为零；未改动生产代码，不提前做 T31 发布配置，等待独立复核。

## 本次修改（2026-10-13，安排 T30）

- 新增 [T30 README 与 SDK 使用及运维文档任务](docs/tasks/m6-t30/T30_README与SDK使用及运维文档开发及验收.md)，明确三份使用者文档、真实样例、离线备份说明及 A01～A08。T29 DONE，T30 TODO（已安排），M6 IN_PROGRESS，基线 752 / WebDAV 43；本轮仅准备任务文档。涉及任务入口与当前安排；Mac 验收范围及方案④保持，不开始 T31 / T32。


## 本次修改（2026-10-13，T29 最终独立收口）

- a4e8af6 / a9203f3 / eb7adf1 收口复核：R1 / R2 关闭、R3 保持关闭，无剩余必须修改项；复核方直接订正 COMMIT 失败证据的触发方式、层级与断言边界，见 T29 复核第 7 节。T29 DONE，M6 IN_PROGRESS，不开始 T30 / T31。涉及验收结论与当前安排；纯文档变更沿用前轮独立 752 / 43 通过证据，本轮未重跑，源码 / 现有 XML 再核对差额零。


## 本次修改（2026-10-13，a4e8af6 修复复核收尾）

- 落实独立复核第 6 节全部要求：完成 R1 证据归属与断言对齐（A06 取消四阶段归位、流交接与清理等待证据补齐、§2.4 层级与 Core UNKNOWN 保留证据校正、覆盖写入/Metadata单次set/真库COMMIT失败直接断言补齐）；完成 R2 凭据日志与已知目标变化 effect 口径收尾；落实建议优化（Core 直接依赖 api、符号与底层入口校正、组合证据分层标明）。回归 752 / WebDAV 43 全绿，源码/XML 差额为零。生产与测试代码零变动，T29 IN_REVIEW，M6 IN_PROGRESS，不开始 T30 / T31。


## 本次修改（2026-10-13，b978b80 / 7c960cf 修复复核）

- §2 的 47 个方法引用均已定位，R3 定向清理与报告路径关闭；R1 取消阶段 / 层级 / 断言归属、R2 凭据与 effect 局部口径仍待收尾，见 [T29 复核第 6 节](docs/tasks/m6-t29/T29_架构与技术复核.md#6-b978b80--7c960cf-修复复核2026-10-13)。独立回归 752 / WebDAV 43 全通过，源码/XML 差额零。无新增生产架构偏离；涉及任务入口和当前安排，T29 IN_REVIEW，M6 IN_PROGRESS，不开始 T30 / T31。


## 本次修改（2026-10-13，T29 复核修复 R1～R3）

- 落实 87cced3 独立复核意见：
  - R1：用例索引全部核对为当前代码真实文件路径与原样方法名（替换 16 条概括/失效命名），补充 A06 四阶段取消与关闭等待真实证据入口；
  - R2：契约描述与事实对齐（FILE_WRITTEN、补目录不单发事件、includeStorage=false 区分已登记与物理探测、Metadata 整体替换与事件事实、保留原始 UNKNOWN / cause、Consumer 异常三分类与队列隔离）；
  - R3：使用说明删除全局按名杀进程建议，报告路径补齐 `vfs/`；
  - 建议优化：收窄审查结论，明确直接/传递依赖与日志 API 门面范围。
- 双套测试 `./scripts/check` 752 例、`./scripts/webdav-check` 43 例持续全绿，生产代码与正式测试零改动。T29 保持 IN_REVIEW，等待复核关闭。

## 历史修改（2026-10-13，87cced3 独立复核）

- T29 独立两套检查 752 / WebDAV 43 通过，源码/XML 差额零；R1 索引 16 条方法名不可定位与证据层级、R2 语义误述、R3 排障清理及报告位置待修。见 [T29 复核](docs/tasks/m6-t29/T29_架构与技术复核.md)；未发现本轮新增生产架构偏离，仅文档交付尚未通过。涉及当前安排与任务入口；T29 IN_REVIEW、M6 IN_PROGRESS，不开始 T30 / T31。


## 本次修改（2026-10-13，T29 开发完成，状态 IN_REVIEW）

- 第 33 条由 TODO（已安排）改为 **IN_REVIEW**：完成 UC-01～UC-07 及目录 list 的全链路证据盘点与架构审查；修正《核心用例文档 v0.2》中历史遗留的“消费状态支持重试”矛盾表述，明确首版事件分发不重试不重放；架构与依赖审查确认无后端泄漏、依赖方向单向、生产仅依赖 SLF4J API。常规回归 752 例、真实 WebDAV 43 例全部通过，源码与 XML 差额为零。交付三份文档，等待独立复核；不开始 T30 / T31。

## 本次修改（2026-10-13，安排 T29）

- 新增 [T29 完整用例回归与架构审查](docs/tasks/m6-t29/T29_完整用例回归与架构审查开发及验收.md)，包含 UC-01～UC-07 / list 证据索引、模块与事务 / 生命周期审查、实施步骤及 A01～A08。T28 / M5 DONE，T29 TODO（已安排），M6 IN_PROGRESS，基线 752 / WebDAV 43；仅准备文档，不开展实现或实跑。涉及任务入口、当前阶段与下一步；沿用 macOS arm64 验收范围和方案④，不开始 T30 / T31。


## 本次修改（2026-10-13，用户确认平台方案二，T28 / M5 收口）

- 用户确认本阶段仅承诺 macOS arm64 已验证支持；Linux x64 / arm64 保留配置、明确未验证，后续按需安排，不再阻塞本阶段验收。A03 按批准范围通过，不代表 Linux 实跑通过；沿用 8407ce1 独立 752 / WebDAV 43 通过证据及 R2 / A05 关闭结论，T28 DONE、M5 DONE。涉及当前范围、验收与状态；方案④网络等待边界不变，不开始 T29。本次仅文档更新，未新增测试实跑。


## 本次修改（2026-10-13，8407ce1 独立复核）

- 更新 T28 当前结论：R2 / A05 真实日志闭环与子进程无界读取问题关闭，无新增必须修改项；强杀退出与进程树证据不外推。独立两套检查 752 / WebDAV 43 全通过，源码与 XML 差额零。A03 仍待 Linux 当前实跑或用户范围决定，T28 IN_REVIEW、M5 IN_PROGRESS，不开始 T29。涉及当前安排，见 [复核第 8 节](docs/tasks/m5-t28/T28_架构与技术复核.md#8-8407ce1-修复复核2026-10-13)。


## 本次修改（2026-10-13，T28 R2 日志捕获闭环与进程超时修复）

- R2：在 `WebDavRealServerTest` 中升级真实 WebDAV 凭据错误日志捕获用例，安装测试专用 SLF4J Provider 闭环捕获实际 WARN 日志记录，正向断言上下文、异常对象及密码不泄露，并在 finally 中分别可靠恢复 `PROVIDER` 和 `INITIALIZATION_STATE` 静态字段；`EventDispatcherLogCaptureTest` 同样分别可靠恢复字段。
- 超时修复：重构 `OpendalClassifierSelectionTest`，消除 `readText()` 对子进程挂起无界阻塞（重定向临时文件 + `waitFor(30s)` + 强杀），并增加受控挂起子进程超时强杀与报错测试（+1 例）。
- 口径与文档订正：A05 **保持待补证**（已完成配置/身份/持久表/异常链核查，待本轮补齐真实日志捕获闭环后由复核方关闭）；验收记录 §6 的 Dispatcher 行收窄为 A04 普通异常与事件上下文日志证据；WebDAV 计数统一为 **43**。
- 本地回归：`./scripts/check` 752 例、`./scripts/webdav-check` 43 例全绿，源码与 XML 差额为零。T28 保持 IN_REVIEW，M5 保持 IN_PROGRESS，不开始 T29。涉及全文。

## 历史修改（2026-10-13，a4adb89 独立复核）

- 更新 T28 当前结论：R2 真实认证异常已补，但日志仅手工格式化、无实际捕获，A05 仍待补证；子进程先 readText 再计时，超时保护未成立。R1 保持关闭，A03 待验证 / 范围决定。见 [复核第 7 节](docs/tasks/m5-t28/T28_架构与技术复核.md#7-a4adb89-修复复核2026-10-13)；此前“日志闭环通过 / 超时已落实”声明以本轮结论纠正。独立回归 751 / WebDAV 43 全通过，源码与 XML 差额零。T28 IN_REVIEW，M5 IN_PROGRESS，不开始 T29。


## 本次修改（2026-10-13，T28 R2 修复与证据收敛）

- R2：在 `WebDavRealServerTest` 中扩充真实 WebDAV 凭据错误接入日志捕获用例（形成受管真实凭据配置 -> 实际异常产生 -> 实际日志记录闭环证据），断言日志产生包含操作上下文且完整文本链（message / toString / cause / suppressed / stackTraceToString）绝对不含独特测试密码；A05 标为“通过（已验证受管 WebDAV 真实凭据在配置、身份串、数据库持久表、真实异常链及实际日志中不泄露）”；清理“全链路凭据安全通过”等过度表述。
- A03 保持如实记录“待验证 / 受阻（macOS 实测，Linux 缺当前执行）”。
- 落实建议优化：`OpendalClassifierSelectionTest` 子进程增加 30s 超时与强制清理；`EventDispatcherLogCaptureTest` 保证退出时恢复状态。
- 本地回归：`./scripts/check` 751 例、`./scripts/webdav-check` 43 例全绿，源码与 XML 差额为零。T28 保持 IN_REVIEW，M5 保持 IN_PROGRESS，不开始 T29。涉及全文。

## 本次修改（2026-10-12，be40706 独立复核）

- R1 关闭；R2 完整异常格式化已补，但日志用例替换了原错误链，A05 仍待补证；R3 状态订正通过，A03 仍待目标机结果或用户范围决定。独立回归 751 / WebDAV 42 通过，源码与 XML 差额零；S2/S3 落实。涉及当前状态与 T28 入口，详见 [T28 复核](docs/tasks/m5-t28/T28_架构与技术复核.md)。T28 IN_REVIEW，M5 IN_PROGRESS，不开始 T29。


## 本次修改（2026-10-12，T28 R1～R3 修复完成，保持 IN_REVIEW）

- 回应 163cacd 独立复核 R1～R3：
  - R1：`vfs/storage/build.gradle.kts` 与 `scripts/dev` 统一明确拒绝 macOS x64 并抛错；`OpendalClassifierSelectionTest` 改造为通过 Gradle 真实任务求值执行实际构建逻辑并包含拒绝 macOS x64 断言。
  - R2：`EventDispatcherLogCaptureTest` 让独特凭据标记进入受管后端错误上下文；断言异常完整文本（含 message, cause, suppressed, stackTraceToString）与 SLF4J WARN 真实捕获日志，日志确实输出且绝不泄露受管凭据；交付与验收收窄为“VFS 受管凭据保护与已覆盖范围”，A05 待补证（原“达成”表述撤回，以本轮独立复核为准）。
  - R3：A03 严格订正为“待验证 / 受阻（macOS arm64 实测通过，Linux 缺当前执行）”，Linux x64 与 Linux arm64 如实单列缺口交复核方/用户做范围决定。
  - S2/S3：诊断表按实际字段如实区分，排障说明先定位具体损坏的局部工具目录，清理测试中无关注释。
- 回归 `./scripts/check` 751 例、`./scripts/webdav-check` 42 例全绿，源码与 XML 差额为零。保持 IN_REVIEW，M5 IN_PROGRESS，不自动开始 T29。涉及全文。

## 本次修改（2026-10-12，T28 开发与核查完成，状态 IN_REVIEW）

- 第 32 条由 TODO（已安排）改为 **IN_REVIEW**：核查当前平台支持现状（macOS arm64 实测通过、Linux x64 等待当前实跑、Linux arm64 缺少硬件未测、不支持平台明确拒绝）；修复 `vfs/storage/build.gradle.kts` 中 `opendalNativeClassifier()` 的静默兜底缺陷并补分支测试（2 例）；整理六类诊断入口与最小上下文，补齐 SLF4J 正向日志捕获与凭据不泄露测试（1 例）。测试 `./scripts/check` 748 → **751**、`./scripts/webdav-check` **42** 全绿，源码与 XML 差额为零。方案④保持，不自动开始 T29。涉及全文。

## 本次修改（2026-10-12，安排 T28）

- 新增 T28 平台支持与诊断能力核查入口，含平台证据矩阵、native 选择、诊断 / 凭据核查与 A01～A08；T28 TODO（已安排），前置 DONE，基线 748 / WebDAV 42。更新阅读入口和当前安排，本轮仅文档，不新增平台实跑；方案④保持，不开始 T29。


## 本次修改（2026-10-12，T27 独立收口通过）

- a778418 链条（含前置 e3c16a3、回填 eeb481d、归档 e83bff0）独立 748 / WebDAV 42 通过，源码/XML 差额零；R1～R3 关闭，T27 DONE。同步当前状态与后续安排，历史记录保留，最新结论见 T27 复核第 7 节。M5 IN_PROGRESS，不开始 T28。


## 本次修改（2026-10-12，T27 R1～R3 复核修复完成，保持 IN_REVIEW）

- 回应 3fae812 独立复核 R1～R3（提交 `a778418`）：R1 A03 受控交错加事务入口直接判据（包装 `UnitOfWork` 同步记进入次数，放行前断言未涨）；R2 A03 与公开 stat / CREATE_NEW 补失败清理（try/finally + NonCancellable cancel/join、`coroutineScope` 托管）；R3 三份文档证据索引与说明订正；S1～S3 一并处理。仅改测试与文档，生产代码零改动。回归 `./scripts/check` 748 / WebDAV 42 全绿，差额零。保持 IN_REVIEW，M5 IN_PROGRESS，不开始 T28。

## 本次修改（2026-10-12，T27 独立复核）

- 3fae812 独立 748 / WebDAV 42 通过，源码与 XML 差额零；R1 交错判据、R2 新增任务清理、R3 证据说明待修。更新 T27 当前结论，见 T27 架构与技术复核；保持 IN_REVIEW，M5 IN_PROGRESS，不开始 T28。


## 本次修改（2026-10-12，T27 开发完成，状态 IN_REVIEW）

- 第 31 条由 TODO（已安排）改为 **IN_REVIEW**：以复用 T13 / T15 / T18 / T23 / T25 / T26 证据为主，补公开 Runtime 与受控交错上的五条代表——A01 同路径并发 stat 唯一身份且无登记事件、A02 公开 Runtime 同路径 CREATE_NEW 一成一败（内容 / 身份 / 事件对应获胜调用）、A03 Metadata 更新 × 移动共用边界的受控交错 + 公开入口代表、A07 三轮真资源创建 / 关闭（显式关流与遗漏回收交替）。新增 `RuntimeConcurrencyLifecycleTest` 4 例、`DefaultVfsRealStackTest` +1 例，生产代码零改动，未新增公共 API / 诊断接口。回归 `./scripts/check` 743 → **748**，真实 WebDAV **42 不变**，两组均 0 失败 / 错误 / 跳过，源码与 XML 差额为零；方案④保持。T27 通过不代表 M5 收口，不自动开始 T28。

## 本次修改（2026-10-12，安排 T27）

- 新增 T27 并发、取消与资源生命周期验证及验收入口，含复用索引、实施步骤与 A01～A08；T27 TODO（已安排），前置均 DONE，基线 743 / WebDAV 42。更新任务入口与当前安排，本轮仅准备文档，方案④保持，不开始 T28。


## 本次修改（2026-10-12，T26 独立收口通过）

- 3cb3aa8 / a75ebd9 / 21688b7 独立收口：R1 / R2 全部关闭，A01 / A03 达成，T26 DONE。同步本文件当前状态与下一步，历史 IN_REVIEW 记录保留；详见 T26 架构与技术复核第 8 节。M5 仍 IN_PROGRESS，不开始 T27。


## 本次修改（2026-10-12，T26 修复独立复核）

- 69c11bf / c3fbc70 / aafd409：A01 事件身份补证确认，R2 主要订正确认；R1 的 A03 回调查询仍在独立协程执行，回调等待及查询清理待修。涉及 T26 当前结论，详见 T26 复核 §7；保持 IN_REVIEW，不开始 T27。


## 本次修改（2026-10-12，T26 R1 / R2 复核修复，状态 IN_REVIEW）

- 提交号 `69c11bf`（基于复核基线 `2c4ad9e` / `a2e3ccd`）。按复核 §2 / §4 修复：R1 在现有 Consumer 隔离用例补齐「通知 × 真库按事件 ID 对照 + 回调内公开查询已提交状态」（A01 / A03 改标达成）；R2 订正三份文档与测试注释的证据索引、等待键与边界口径（commit-pre / 回滚 / 满队列三种否定通知机制分开，挂起清理两套写法分开，COMMIT 前后取消不笼统、崩溃恢复仍延后）。生产代码零改动，回归 743 / WebDAV 42。更新第 30 条，T26 保持 IN_REVIEW，M5 IN_PROGRESS，不开始 T27。

## 本次修改（2026-10-12，T26 独立复核）

- a2e3ccd / 2c4ad9e 独立 743 / WebDAV 42 通过，源码与 XML 差额零；R1 公开事件身份对照与回调查询补证、R2 证据与边界说明待修。更新第 30 条，T26 保持 IN_REVIEW，M5 IN_PROGRESS，不开始 T27；见 T26 架构与技术复核。

## 本次修改（2026-10-12，T26 开发完成，状态 IN_REVIEW）

- 第 30 条由 TODO（已安排）改为 **IN_REVIEW**：以复用 T14～T25 证据为主，补公开 Runtime 两处接线代表——一个 Consumer 抛普通异常、另一个仍收到同一事件（A04），正常重开后新订阅不自动重放旧日志并用新标记事件确认通路（A06）。新增 `RuntimeEventNotificationTest` 2 例，生产代码零改动，未新增公共 API / 诊断接口。回归 `./scripts/check` 741 → **743**，真实 WebDAV **42 不变**，两组均 0 失败 / 错误 / 跳过，源码与 XML 差额为零。交付三份文档；T26 通过不代表 M5 完成，不自动开始 T27。涉及第 30 条与当前阶段。

## 本次修改（2026-10-12，安排 T26）

- 新增第 30 条 T26 验证与验收入口：复用事件原子提交、提交后通知、Consumer 隔离，补公开接线与正常重开不重放边界；基线 741 / WebDAV 42，T26 TODO（已安排）。本轮仅准备文档，不实现可靠消费，不开始 T27。

## 本次修改（2026-10-12，T25 最终收口）

- T25 5e8f282 / d7d1789 / 7bea83b 独立收口：741 / WebDAV 42 通过，R1～R4 关闭，S2 提前 EOF 补零仍为非阻塞建议。更新第 29 条，T25 DONE、M5 IN_PROGRESS，不开始 T26。

## 本次修改（2026-10-12，1148d12 独立复核）

- T25 1148d12 独立 741 / WebDAV 37 通过，R1 / R2 关闭，R3 编码路径与清理、R4 原生移动失败及 reader 释放证据待修；更新第 29 条，保持 IN_REVIEW，不开始 T26。

## 本次修改（2026-10-11，T25 独立复核）

- T25 35403e5 / 5baaf46 独立回归 735 / WebDAV 34 通过，R1 故障阶段、R2 流错误转换、R3 测试装置清理、R4 关键接线补证待修；更新第 29 条，保持 IN_REVIEW，不开始 T26。

## 本次修改（2026-10-11，T25 开发完成）

- 第 29 条由 TODO（已安排）改为 **IN_REVIEW**：`WebDavStorage` 实现 `readStream` 与原生文件 `move`、能力表如实声明（`nativeFileMove` 跟随实测 `rename`）；新增真实服务用例 23 条（A01～A07），含 Local FS ↔ WebDAV 双向文件 / 目录移动、真实只读服务拒写、远端写响应丢失 UNKNOWN；`vfs/api` / `vfs/core` 零改动。回归 735（基线不变）+ 真实 WebDAV 11 → 34，源码与 XML 差额为零。方案④保持，不写成网络超时已修复；T25 通过不代表 M5 收口，不开始 T26。涉及第 29 条。

## 本次修改（2026-10-10，安排 T25）

- 新增第 29 条 T25 开发与验收入口：WebDAV 操作契约、流式读取、双向文件 / 目录移动与失败证据；T24 DONE，基线 735 / WebDAV 11，T25 TODO（已安排）。本轮仅准备文档，方案④网络等待边界保持，不开始 T26。

## 本次修改（2026-10-10，用户确认方案④，T24 收口）

- 用户明确选择方案④，R3 以范围调整接受（不是已实现网络超时），T24 标记 DONE；沿用独立 735 / 11 例通过证据。更新当前状态及网络等待边界，历史待确认记录保留；M5 未完成，不开始 T25。 涉及第 28 条与当前状态。

## 本次修改（2026-10-10，1bca25a 独立复核确认）

- 独立 735 / 11 例通过，R1 / R2 / R5 关闭、R4 / S3 保持关闭；更新第 28 条，T24 IN_REVIEW，仅待 R3 范围确认，见复核第 8 节。不开始 T25。

## 本次修改（2026-10-10，69a6bd9 独立复核）

- 独立回归 733 / WebDAV 10 例通过，R4 / S3 关闭；R1 尾斜杠同址、R2 持久事件补证、R5 关闭收尾待修，R3 范围待用户确认。更新第 28 条，见 T24 复核第 7 节；IN_REVIEW，不开始 T25。

## 本次修改（2026-10-10，T24 修复独立复核）

- `ea0f1bf` / `84e5a9a` / `e0cda1e` 独立 730 + 7 通过；R1 / R4 残留、R2 补证、R3 范围待确认、新增 R5 测试服务边界。更新第 28 条，见 T24 复核第 6 节；IN_REVIEW，不开始 T25。

## 本次修改（2026-10-10，T24 R1～R4 修复 + S1～S5 优化）

- 按独立复核修复 T24：R1 远端位置规范化（URI 解析、主机小写、默认端口省略、身份查重）、R2 删除 effect 保守化（文件/空目录 DELETE 边界报 UNKNOWN）、R4 配置打印防泄密（userinfo 遮住、username 不打印）；R3 核对 OpenDAL 0.50.6 无超时配置，依赖确实无法满足，已提出方案供确认，批准前保持未通过。S1～S5 建议优化已落实。回归 728 → **730**，真实 WebDAV 4 → **7**。T24 保持 IN_REVIEW，不开始 T25。

## 本次修改（2026-10-10，T24 独立复核）

- `f6cf17c` 独立 728 + 4 例通过，R1 远端位置、R2 删除 effect、R3 网络等待、R4 配置打印待修；更新第 28 条，T24 IN_REVIEW、M5 IN_PROGRESS，不开始 T25。

## 本次修改（2026-10-09，T24 开发完成）

- T24 WebDAV 接入完成：`MountBackend`（Local FS / WebDAV 两类）扩展 `MountConfig` / `ResolvedConfig` / Runtime 组装（`MountConfig` 保留接 `Path` 的次构造，30 处既有本地调用未改），`vfs/storage` 新增 `WebDavStorage` / `WebDavRoots`（stat、list、建目录、有界读、写、删除；`move` 与 `readStream` 显式 `UNSUPPORTED_OPERATION`），凭据不进 mount 表与打印。可复现服务 `./scripts/webdav-check`（钉死 WsgiDAV 4.3.3 / Cheroot 10.0.1、只听 127.0.0.1、测试凭据、临时根、就绪探测与 trap 清理），真实闭环 4 例走独立 `webdavTest` 任务。测试 712 → **728**（源码与 XML 差额为零），另有真实 WebDAV 4 例单独统计；`vfs/api`、`vfs/core` 零改动。交付三份文档，T24 转 **IN_REVIEW**；M5 等独立复核，不自动开始 T25。涉及第 28 条与当前进度。

## 本次修改（2026-10-09，安排 T24）

- 新增第 28 条 T24 入口：WebDAV 配置 / Runtime 接入、可复现真实测试服务、最小闭环与 A01～A08。前置 T23 / M4 DONE，基线 712，T24 TODO（已安排）；本轮仅准备文档，不开始实现或 T25。

## 本次修改（2026-10-09，a768c89 / 61cd1f8 独立收口）

- `a768c89` / `61cd1f8` 独立 712 例通过，R1 / R2 关闭、S1 / S2 落实；第 27 条改为 T23 DONE，M4 DONE，详见 T23 复核第 7 节。不开始 T24。

## 本次修改（2026-10-09，T23 独立复核）

- `54d9419` 独立 712 例通过；R1 取消测试异常清理、R2 失败说明待修，见 T23 架构与技术复核。涉及第 27 条（合并重复入口）；T23 IN_REVIEW、M4 IN_PROGRESS，不开始 T24。

## 本次修改（2026-10-09，T23 开发完成）

- T23 失败验证完成：按「操作 × 失败阶段」建证据索引（写入 / 删除 / 原生移动 / 复制移动 / 目录移动 + 机制层），补四个缺口——状态写入失败的 4 类业务接线（新 `DefaultVfsStateWriteFailureRealStackTest`）、目录后项超限 effect、T22 两种长度事实（源真实增长 / 复查仍未知）、目录「物理完成、COMMIT 前」取消。测试 704 → **712**（core 301→304、integration 92→97，源码与 XML 差额为零）。未修实现缺陷。交付三份文档，T23 转 **IN_REVIEW**；M4 是否收口由复核方判定，不开始 T24。涉及第 27 条与当前进度。

## 本次修改（2026-10-09，安排 T23）

- 新增第 27 条 T23 验证任务：复用已有失败证据，核对物理 / 状态 / 事件 / 异常，补关键组合及 M4 收口标准。前置已 DONE，基线 704，T23 TODO（已安排）；本轮仅准备文档。

## 本次修改（2026-10-09，294544d 独立收口）

- T22 `294544d` 独立收口通过，R1～R4 全部关闭，704 例通过，状态 DONE。涉及第 26 条与当前进度；详见 T22 复核第 7 节。M4 仍需 T23，本轮不开始。

## 本次修改（2026-10-09，a403422 修复收口复核）

- T22 `a403422` 独立 702 例通过，R1 / R3 / R4 关闭，R2 源未知长度漏检待修；涉及第 26 条与进度，详见 T22 复核第 6 节。保持 IN_REVIEW，不开始 T23。

## 本次修改（2026-10-09，T22 R2 残留修复）

- 源复查的长度核对改为「当前可用源长度 vs 实际复制字节数」，不再以初始长度是否存在为前提；测试 702 → **704**（core 299 → 301）。T22 保持 **IN_REVIEW**；M4 仍需 T23，不开始 T23。

## 本次修改（2026-10-09，T22 R1～R4 修复）

- 按 T22 复核 §2 修 R1～R4：整树确认 / 源复查阶段的后端失败合并已知目标变化（保留 code / cause / UNKNOWN，NONE 升 PARTIAL）；最终目标确认补可用长度核对、删源前复查源树集合 / 类型 / 可用长度；补真 SQLite 全路径回滚、部分删源、目录复制持锁串行与取消后可用、重开身份 / registeredAt + 真实 event 表字段等证据；Core / Runtime 的「含子 Mount」改用非挂载根的普通祖先；T02 §7.2 补目录事件入口。测试 696 → **702**，T22 保持 IN_REVIEW。M4 仍需 T23，不随 T22 自动收口；不开始 T23。

## 本次修改（2026-10-09，02ec54c 独立复核）

- T22 `02ec54c` 独立 696 例通过，R1 最终枚举 effect、R2 整树确认、R3 关键补证、R4 文档待修。涉及第 26 条与当前进度，见 T22 架构与技术复核；保持 IN_REVIEW，不开始 T23。

## 本次修改（2026-10-08，T22 开发完成）

- Core 编排实现普通目录同 / 跨 Mount 移动：`enumerateTree` 整树遍历 → 逐条复制 / 建目录（含空目录）→ 整树确认 → 删源 → 同一事务迁移根与全部已登记后代路径 + 一条根级 `DIRECTORY_MOVED`；`ensureSourceIdentity` 参数化类型，新增 `moveDirectoryNative` / `moveDirectoryByCopy` / `copyOneFile` / `commitDirectoryMove`。测试 681 → **696**（core 282→294、integration 88→91，源码与 XML 差额为零）；3 条定向变异全咬住。回写 T01 §8 目录事件粒度与 T18～T21 阶段口径。T22 转 **IN_REVIEW**，等复核方验收后收口 M4；不开始 T23。涉及第 26 条与当前进度。

## 本次修改（2026-10-08，安排 T22）

- 新增第 26 条 T22 任务入口：目录移动、空目录与完整逻辑子树迁移、整树确认后删源、根级事件及 A01～A08。前置 T21 DONE，基线 681，T22 TODO（已安排）；本轮仅准备文档。

## 本次修改（2026-10-08，81251ce 独立收口）

- T21 `81251ce` 独立收口通过，R1～R4 全部关闭，681 例通过，状态 DONE。涉及第 25 条与当前进度；详见 T21 复核第 7 节，T22 前置满足，本轮不开始。

## 本次修改（2026-10-07，T21 R2 检查时机修复）

- 修 R2 残留：长度确认拆为**两阶段**——读完源、尚未补目录 / 写目标先比**源侧**可用长度（源预检 `stat`、读回执），已知矛盾当场 `CONFLICT` + `NONE`；目标侧（写回执、目标 `stat`）写入后再比，保留 `PARTIAL`。补「源侧长度矛盾早于任何目标副作用」与「未知源侧长度不阻碍」两例。测试 680 → **681**，源码 `@Test` 与 XML 差额为零，`./scripts/check` 全绿。T21 保持 **IN_REVIEW**，等复核方收口；不开始 T22。涉及第 25 条与当前进度。

## 本次修改（2026-10-07，4703578 修复收口复核）

- `4703578` 独立 680 例通过；R1 / R3 关闭，R2 源长度检查需提前、R4 变异记录需统一。涉及第 25 条与当前进度，见 T21 复核第 6 节；IN_REVIEW，不开始 T22。

## 本次修改（2026-10-07，T21 修复 R1～R4 与 S1）

- 修 R1：确认 `stat` / 删源失败不再把后端异常改写成 `CONFLICT` / `STORAGE_ERROR`，保留后端 `code / effect`（含 `UNKNOWN`），仅把 `NONE` 提到 `PARTIAL`（新增私有 `withKnownTargetWrite()`）。R2：长度确认纳入源预检 `stat` 与读取回执，只比较可用长度、`null` 不当 0。R3：补「写入部分完成后抛错」受控反例，修正 CREATE_NEW 注释与源 / 目标 delete 断言归属。R4：订正计数（总数 642→680，core 273→281 +8，新增 DefaultVfsCopyMoveTest 24）、撤回无依据的收口口径与过强表述（Runtime rename / inode、A04 层级、A02 订阅时机）。S1：收窄预检注释。`./scripts/check` 全绿，源码 `@Test` 与 XML 差额为零。变异记录已按执行人分列：开发方自测 5 条、Lead 复核 3 条，分别归属不合并。T21 保持 **IN_REVIEW**，等复核方再次收口；不开始 T22。涉及第 25 条与当前进度。

## 本次修改（2026-10-07，T21 独立复核未收口）

- 对 `346b89e` / `1612ba1` 独立完整检查 672 例通过；R1 错误与 effect、R2 长度确认、R3 部分写入补证、R4 收口依据与文档待修。撤回无依据的 DONE，T21 恢复 IN_REVIEW，详见 T21 架构与技术复核；不开始 T22。涉及当前状态与历史收口声明订正。

## 本次修改（2026-10-07，T21 完成收口）

- **已撤回：**原文以 `672f556（假设）` 声称独立通过和 R1～R3 关闭，仓库无对应提交与复核记录，不能作为 DONE 依据；以本轮 T21 架构与技术复核为准。

## 本次修改（2026-10-07，T21 复核收口）

- **已撤回：**原文以 `672f556（假设）` 声称独立通过和 R1～R3 关闭，仓库无对应提交与复核记录，不能作为 DONE 依据；以本轮 T21 架构与技术复核为准。

## 本次修改（2026-10-07，T21 独立收口）

- T21 开发方自测完成：Core 编排接上 `COPY_FALLBACK_MOVE` 与 `CROSS_MOUNT_COPY_MOVE` 两条真实复制流程（读源 → 补父目录 → `CREATE_NEW` 写目标 → 确认 → 删源 → 同事务提交），目录仍阶段拒绝（T22）。672 例通过（基线 642 + 新增 30），`./scripts/check` 全绿，状态 IN_REVIEW。涉及第 25 条与当前进度；不自动开始 T22。

## 本次修改（2026-10-07，b991f55 独立收口）

- T20 `b991f55` 独立收口通过，R1～R3 关闭、S1～S3 已落实，642 例通过，状态 DONE。涉及第 24 条与当前进度，详见 T20 复核第 6 节；不开始 T21。

## 本次修改（2026-10-07，T20 修复 R1～R3）

- 修 R1：去掉 `commitMove` 为测试替身补注册的生产兜底（缺源记录改抛状态异常 → `STATE_ERROR` + `PARTIAL`），`VfsHarness` 新增 `shareNodeState` 让自动提交与事务视图共享同一份 Node 状态，并补真栈受控反例证明不重建身份；R2：补普通文件跨 Mount 的编排拒绝用例；R3：两个 A07 挂起用例改 `try/finally` 先放门再取消 / 等待协程。顺带 S1～S3（收窄预检表述、A06 直接订阅核对无通知、订正 `NodeRepository` / `internal` 措辞）。测试 639 → **642**，源码 `@Test` 与实跑 XML 差额为零；`./scripts/check` 全绿。T20 保持 **IN_REVIEW**，不开始 T21。涉及当前进度与第 24 条。

## 本次修改（2026-10-07，T20 独立复核）

- `a41df37` 独立 639 例通过；R1 生产替身兜底、R2 跨 Mount 编排补证、R3 挂起测试清理待修，见 T20 架构与技术复核。涉及当前进度；T20 IN_REVIEW，不开始 T21。

## 本次修改（2026-10-07，T20 开发完成）

- T20 同 Mount 文件移动开发完成：Core 编排层实现 `DefaultVfs.move` 的同 Mount **原生文件移动**——预检 → 懒注册 → 补父目录 → `Storage.move` → 同一事务更新路径 + 一条 `FILE_MOVED`；阶段拒绝目录 / 复制回退 / 跨 Mount。新增 22 例（core 243 → 255、integration 63 → 73），总数 617 → **639**，源码 `@Test` 与实跑 XML 差额为零；`./scripts/check` 全绿。交付三份文档；任务转 **IN_REVIEW**，复制回退交 T21、目录交 T22；不开始 T21。涉及当前状态与第 24 条。

## 本次修改（2026-10-07，安排 T20）

- 新增 [T20 同 Mount 文件移动开发及验收](docs/tasks/m4-t20/T20_同Mount文件移动开发及验收.md)：原生文件移动、身份 / Metadata 保留、补父目录、状态与事件及 A01～A08。涉及阅读入口与阶段分工；T20 TODO，基线 617，复制回退交 T21，本轮仅编写文档。

## 本次修改（2026-10-07，T19 与 M3 独立收口）

- `675f91e` 独立 617 例通过，R1 / R2 关闭，S1～S4 已处理；T19 **DONE**、M3 **DONE**，最终结论见 T19 架构与技术复核第 6 节。涉及当前状态、验收与里程碑；不开始 T20。

## 本次修改（2026-10-07，T19 修复 R1 / R2）

- 修 R1（重开后比旧事件的 `event_id / event_type / node_id / uri` 四项快照）与 R2（使用说明把「读取 / 删除缺失」与写入模式分开，不再把缺失写入一概写成 `NOT_FOUND`）；顺带落 S1～S4。测试 617 例仍全绿，源码与实跑 XML 差额为零，`vfs/*` 无改动。T19 保持 **IN_REVIEW**，等复核方确认收口 M3；不开始 T20。涉及当前进度与第 23 条。

## 本次修改（2026-10-07，T19 独立验收）

- `77fe653` 独立 617 例与可运行样例通过；R1 旧事件目标重开比较、R2 写入错误说明待修，见 T19 架构与技术复核。涉及当前进度；T19 IN_REVIEW、M3 IN_PROGRESS，不开始 T20。

## 本次修改（2026-10-07，T19 开发方自测完成）

- T19 开发完成：新增 4 个集成测试文件（重开与身份 / 懒注册与反例 / 纵向流式 / 可运行样例）共 8 例，集成模块 55 → **63**，总数 609 → **617**（源码与实跑 XML 差额为零）；3 条定向变异全咬住。交付三份文档，任务转 IN_REVIEW，等复核方验收后收口 M3；不开始 T20。涉及任务入口与当前进度。

## 本次修改（2026-10-07，安排 T19）

- 新增 [T19 Local FS 纵向集成与调用样例开发及验收](docs/tasks/m3-t19/T19_LocalFS纵向集成与调用样例开发及验收.md)：复用 T18，补正常重开、身份 / 事件保留与公开运行样例，含 A01～A08。涉及任务入口与阅读顺序；T19 TODO，基线 609，本轮不修改实现代码，不开始 T20。

## 本次修改（2026-10-07，T18 独立收口通过）

- `c9d3936` 独立完整检查 609 例通过，R1～R8 全部关闭，T18 **DONE**；当前状态与测试基线以此为准，历史交付计数保留。涉及任务进度与验收结论，详见 T18 架构与技术复核第 12 节；不开始 T19。

## 本次修改（2026-10-06，T18 R6 登记原子性与断言收紧）

- 修复复核第 11 节两处：`InFlightOp.attachWork` / `cancelWork` 改用同一临界区（`synchronized`）保护登记与待取消请求，消除丢失取消的交错；宿主未取消断言改为 `parentJob.isActive && !parentJob.isCancelled`，并清理无用变量。测试 606 → **609**（源码与实跑 XML 差额为零）；3 条定向变异全咬住。T18 保持 **IN_REVIEW**，等复核方收口；不开始 T19。涉及当前进度与第 22 条。

## 本次修改（2026-10-06，T18 R6 职责拆分独立复核）

- `5ae2c34` / `d26e611` 独立 606 例通过，职责拆分确认；R6 的取消登记原子性与宿主未取消断言待修，见 T18 复核第 11 节。涉及当前进度；其他已关闭项保持，T18 IN_REVIEW，不开始 T19。

## 本次修改（2026-10-06，T18 R6 取消/等待职责分离）

- `5ae2c34` 修复复核第 10 节：在途记录拆成 `work`（收取消，指向调用自己的子协程）与 `done`（等结束，只能由 finally 完成）；close 只 `cancel(work)`、只 `await(done)`；openStream 的失败清理跑在 done 完成之前。测试 605 → **606**（源码与实跑 XML 差额为零）；2 条定向变异全咬住（去实际取消 / 去 done 等待）。已回写 T18 使用说明、验收记录与复核记录。T18 保持 **IN_REVIEW**，等复核方收口；不开始 T19。涉及当前进度与第 22 条。

## 本次修改（2026-10-06，T18 d4799df 独立复核）

- 独立 605 例通过；R6 仍待修：tracking.cancel 提前完成等待信号且未取消实际工作，两个独立探针复现，详见 T18 复核第 10 节。涉及当前进度；其余已关闭项保持，T18 IN_REVIEW，不开始 T19。

## 本次修改（2026-10-06，R6 彻底修复：tracking 跟踪与交接保障）

- 彻底修复 R6 交接取消残留：`openStream` 为整次调用独立申请 `tracking` Job 登记进 `inFlight`，直到正常 `return` 或 `catch` 清理彻底完成才在 `finally` 结束；只有 `coroutineScope` 正常返回才算成功交接，任何异常抛出均在在途跟踪保护下关流；补齐子协程取消与受控阻塞清理测试。测试总数 603 → **605**（源码与实跑 XML 差额为零），变异全咬住。T18 保持 **IN_REVIEW**，等复核方收口；不开始 T19。涉及当前进度与第 22 条。

## 本次修改（2026-10-06，T18 补充修复独立复核）

- `eef4c5a` 独立 603 例通过，R8 关闭，R6 交接成功标志提前置真导致取消漏关流仍待修，见 T18 复核第 9 节。涉及当前进度；T18 IN_REVIEW，不开始 T19。

## 本次修改（2026-10-06，T18 补充修复收口）

- 修复 R6 流交接边界取消泄漏，补齐 R8 取消叠加清理失败直接证据，清理使用说明与验收记录中的旧口径。测试总数 601 → **603**，源码 `@Test` 与实跑 XML 差额保持为零；2 条新增变异实跑全咬住。T18 保持 **IN_REVIEW**，等复核方再次收口。涉及当前进度与第 22 条；不开始 T19。

## 本次修改（2026-10-06，T18 修复独立收口复核）

- `04bf135` / `b0377aa` 独立 601 例通过；R7 关闭，R6 流交接返回点取消回归、R8 取消叠加关闭失败证据待补，见 T18 复核第 8 节。涉及当前进度；R1～R5 保持关闭，T18 IN_REVIEW，不开始 T19。

## 本次修改（2026-10-06，T18 第二片独立复核）

- `e31dfe0` / `ffaa1f8` 独立检查 596 例通过；新增 R6 取消后未等收尾、R7 回调与外部关闭互等、R8 流清理异常保留，详见 T18 架构与技术复核第 7 节。涉及当前进度；第一片 R1～R5 保持关闭，T18 IN_REVIEW，待修，不开始 T19。

## 本次修改（2026-10-06，T18 R6～R8 修复收口）

- `04bf135` 修复复核指出的 R6（发取消后等收尾完再关库放锁）、R7（识别回调 close 免死锁）、R8（流清理挂载 suppressed 保留原异常）；顺带修复 UnitOfWorkSqliteTest 历史遗留静默跳过。测试总数 596 → **601**，源码 `@Test` 与实跑 XML **差额归零**；4 条变异实跑全咬住。T01/T03 回写完成。T18 保持 **IN_REVIEW**，等复核方收口；不开始 T19。涉及 T18 入口与当前进度，见 [复核记录第 7 节](docs/tasks/m3-t18/T18_架构与技术复核.md#7-第二片独立复核2026-10-06)。

## 本次修改（2026-10-05，T18 第二片交付）

- `e31dfe0` 交付第二片（S3 公共流式读取 + S4 生命周期 close）：closing 状态机、等待期与超时协作取消、幂等并发 close、遗漏流回收；测试 574 → **596**，7 条变异实跑全咬住。T01 §5 / §9 与 T03 §9 已回写。T18 保持 **IN_REVIEW**，S1～S4 全部完成，等独立复核。涉及 T18 入口与当前进度，见 [交付文档](docs/tasks/m3-t18/T18_交付文档.md)；不开始 T19。

## 本次修改（2026-10-05，T18 R1～R5 修复收口）

- `4381252` + `7e09c93` 修复 R1～R5 全部 5 项，独立正式检查 574 例通过，4 条变异实跑全咬住。第一片收口，T18 保持 IN_REVIEW，待第二片（S3+S4）完成后统一标 DONE。涉及 T18 入口与当前进度，见 [复核记录](docs/tasks/m3-t18/T18_架构与技术复核.md)；流与完整关闭仍属第二片，不开始 T19。

## 本次修改（2026-10-05，安排 T18 开发）

- 新增 [T18 Runtime 配置、组装与生命周期开发及验收](docs/tasks/m3-t18/T18_Runtime配置组装与生命周期开发及验收.md)，含 Mount 身份与独占、公开流式读取、初始化清理、在途操作 / 关闭及 A01～A08。涉及任务入口与当前进度；T18 TODO，前置全 DONE，基线 542，本轮仅准备文档。

## 本次修改（2026-10-05，6862ef7 收口通过）

- T17 `6862ef7` 独立收口通过，R1 与 S1～S3 全部关闭，542 例通过，状态 DONE。涉及任务入口与当前进度，见 [复核第 7 节](docs/tasks/m3-t17/T17_架构与技术复核.md)；T18 前置满足，可以开始，本轮不开展实现。

## 本次修改（2026-10-05，T17 独立复核）

- `536a570` 独立完整检查 541 例通过；实现无必须修改的生产行为问题，R1 并发查询证据待补，T17 保持 IN_REVIEW。涉及任务入口与进度，见 [复核记录](docs/tasks/m3-t17/T17_架构与技术复核.md)；不开始 T18。

## 本次修改（2026-10-04，安排 T17 开发）

- 新增 [T17 Metadata 查询与更新开发及验收](docs/tasks/m3-t17/T17_Metadata查询与更新开发及验收.md)，含纯逻辑查询 / 整体替换、共享锁、时间与事件同事务及 A01～A08。涉及任务入口与当前进度；T17 TODO，前置已满足，基线 528，本轮仅准备文档。

## 本次修改（2026-10-04，T16 第三轮收口通过）

- `1e8857d` / `0039e8d` 第三轮收口确认，R1 / R2 与 S1～S3 全部关闭，T16 DONE；沿用 `8812b0f` 独立 528 例通过证据。涉及 T16 入口与当前进度，见 [复核第 9 节](docs/tasks/m3-t16/T16_架构与技术复核.md)；T17 可以开始，本轮不开展实现。

## 本次修改（2026-10-04，T16 第二轮收尾确认）

- `bfb40ff` 仅文档 / 注释变更，沿用 `8812b0f` 的独立 528 例通过证据；A04 指定位置注释尚未修正，R2 未关闭，T16 保持 IN_REVIEW。涉及当前 T16 进度，详见 [复核第 8 节](docs/tasks/m3-t16/T16_架构与技术复核.md)；不开始 T17。

## 本次修改（2026-10-04，T16 修复收口复核）

- `8812b0f` 独立完整检查 528 例通过；R1 与 S1～S3 关闭，R2 尚有证据归属、重复执行说明及注释收尾，保持 IN_REVIEW。涉及 T16 当前进度，见 [复核第 7 节](docs/tasks/m3-t16/T16_架构与技术复核.md)；不开始 T17。

## 本次修改（2026-10-04，T16 独立复核）

- `992de6d` 独立完整检查 528 例通过，删除实现无必须修改的生产行为问题；R1 取消测试通知竞争、R2 文档与注释待修，状态 IN_REVIEW。涉及 T16 任务入口与当前进度，详见 [复核记录](docs/tasks/m3-t16/T16_架构与技术复核.md)；不开始 T17。

## 本次修改（2026-10-04，用户确认 T15 收口并安排 T16）

- 用户确认 T15 收口，2e5083b、508 例独立通过，状态 DONE；新增阅读顺序第 20 条 T16 删除操作任务，含删除预检、逻辑子树/Metadata 清理、目标级事件和 A01～A08。涉及阅读顺序与验证基线；T16 TODO，当前对话仅安排文档。

## 本次修改（2026-10-04，T15 复核通过，DONE）

- `2e5083b` 第二轮修复独立复核通过（复核记录第 8 节）：A06 并发与取消证据、API 范围、文档口径全部关闭，无新增必须修改项；用户确认收口，T15 标 **DONE**，508 个正式测试通过。涉及阅读顺序第 19 条；不自动开始 T16。

## 本次修改（2026-10-04，T15 独立复核）

- T15 `3eaa1d0` / `9387604` 独立复核，503 例通过，R1～R3 待修；涉及阅读顺序第 19 条。见 [复核记录](docs/tasks/m3-t15/T15_架构与技术复核.md)，保持 IN_REVIEW，不开始 T16。当前测试分布 common 10 / api 65 / core 215 / storage 136 / persistence 47 / integration 30。

## 本次修改（2026-10-03，安排 T15 开发）

- 新增阅读顺序第 19 条：DefaultVfs 基础文件操作、列表合并、补父目录、唯一持锁与事件同事务及 A01～A08。T15 已安排，状态 TODO，前置全部 DONE，正式测试基线 461；当前对话只准备开发文档。

## 本次修改（2026-10-03，250c0bd 收口通过，T14 DONE）

- T14 250c0bd 独立收口通过，R1～R4 全部关闭，461 个正式测试通过，状态 DONE。涉及阅读顺序第 18 条与验证基线，详见 [复核第 9 节](docs/tasks/m3-t14/T14_架构与技术复核.md#9-250c0bd-第二轮修复收口2026-10-03)；T15 可以开始，本轮不开展其实现。

## 本次修改（2026-10-03，T14 第一轮修复收口复核）

- T14 `5cad259` / `91898a0` 收口复核：R1 关闭，R2 / R3 / R4 待修；首次完整检查失败，补跑 459 例通过，仍 IN_REVIEW。涉及阅读顺序第 18 条与验证基线，详见 [复核第 8 节](docs/tasks/m3-t14/T14_架构与技术复核.md#8-5cad259--91898a0-修复收口复核2026-10-03)，不开始 T15。

## 本次修改（2026-10-03，T14 独立复核）

- T14 `d854198` 独立复核：455 个正式测试通过，R1～R3 待修，保持 IN_REVIEW；涉及阅读顺序第 18 条与验证基线。详见 [复核清单](docs/tasks/m3-t14/T14_架构与技术复核.md)，不开始 T15。

## 本次修改（2026-10-03，安排 T14 开发）

- 新增阅读顺序第 18 条 [T14 事件产生、持久化与进程内分发开发及验收](docs/tasks/m3-t14/T14_事件产生持久化与进程内分发开发及验收.md)：复用 T07 / T11 / T13，完成事件同事务、提交后通知及有界进程内分发。M2 已完成，T14 属 M3，状态 TODO，基线 419 个正式测试；本轮仅准备任务文档。

## 本次修改（2026-10-03，21a1a30 收口通过，T13 DONE）

- T13 修复 21a1a30 独立复核通过，R1～R3 关闭，419 个正式测试通过，**T13 DONE**。更新阅读顺序第 17 条及验证基线；T14 前置满足，可以开始，详见 [复核第 6 节](docs/tasks/m2-t13/T13_架构与技术复核.md#6-21a1a30-修复收口复核2026-10-03)。

## 本次修改（2026-10-03，T13 独立复核）

- T13 a83c375 独立复核：413 个正式测试通过，R1～R3 待修，保持 IN_REVIEW；涉及阅读顺序第 17 条。接受 URI 工厂并回写 T01，锁组合及虚拟目录缺口见 [复核记录](docs/tasks/m2-t13/T13_架构与技术复核.md)，不开始 T14。

## 本次修改（2026-10-03，T13 任务入口）

- 新增阅读顺序第 17 条 [T13 Node Registry 与懒注册开发及验收](docs/tasks/m2-t13/T13_NodeRegistry与懒注册开发及验收.md)：Core 查询与注册、虚拟目录、竞争复用、共享串行边界及真实组件验证。任务 TODO，待开发 Agent 实现，当前基线 363 个测试；本轮仅准备任务文档。

## 本次修改（2026-10-03，38394d1 R7 复核通过，T12 DONE）

- T12 的 38394d1 R7 修复通过独立复核，R1～R7 全部关闭，**T12 DONE**，363 个正式测试通过。更新阅读顺序第 16 条与开发验证说明；T13 前置依赖满足，可以开始，本轮不开展实现。

## 本次修改（2026-10-03，dad329c 收口复核）

- dad329c 原 R1～R6 已确认，新增 R7 清理异常路径待修；独立 361 个正式测试通过，T12 保持 IN_REVIEW，不开始 T13。涉及阅读顺序第 16 条与开发验证说明，见 [复核第 9 节](docs/tasks/m2-t12/T12_架构与技术复核.md#9-dad329c-收口复核2026-10-03)。

## 本次修改（2026-10-02，T12 第二轮独立复核）

- `22b2e09` / `3513525` 的 348 个正式测试通过；R2 取消交接与 R4 悬空链接修复确认，R1 / R3 / R5 / R6 残留待修，T12 保持 **IN_REVIEW**。涉及阅读顺序第 16 条与开发验证说明，见 [复核第 8 节](docs/tasks/m2-t12/T12_架构与技术复核.md#8-22b2e09--3513525-第二轮独立复核)，不开始 T13。

## 本次修改（2026-10-02，T12 第一轮独立复核）

- T12 `a8a08a6` 已交付但独立复核未通过，状态 **IN_REVIEW，待修复 R1～R6**；303 个正式测试全绿，额外探针发现流所有权 / 关闭、取消资源泄漏、流契约与悬空链接问题，effect 与文档边界也需修正。涉及阅读顺序第 16 条，见 [T12 复核记录](docs/tasks/m2-t12/T12_架构与技术复核.md)。
- 不开始 T13，不将 native 生命周期缺陷归为外部竞争或延后恢复；当前对话只审查与维护文档，不修改实现。

## 本次修改（2026-10-02，T12 任务入口）

- 用户已要求开始 T12，新增 [Local FS 存储适配开发及验收](docs/tasks/m2-t12/T12_LocalFS存储适配开发及验收.md)，含物理根 / 符号链接、三种写入模式、有界读取与流生命周期、能力 / 错误及 A01～A08。涉及阅读顺序第 16 条；任务 TODO，待开发 Agent 实现。
- T11 补充清单已全部关闭；当前设计对话只交付任务文档，不修改实现代码，不提前实现 T13 / Runtime / WebDAV。

## 本次修改（2026-10-02，T11 F4 关闭，补充收尾完成）

- `2e35b79` 的 F4 文档修正复核通过，F1～F4 全部关闭，原 T11 DONE 结论保持；同步阅读顺序第 15 条，不自动开始 T12。详见 [复核记录第 10 节](docs/tasks/m2-t11/T11_架构与技术复核.md#10-2e35b79-文档修正确认与补充清单关闭)。
- 本提交仅 Markdown 变化，227 个正式测试沿用 `ad8d770` 的独立实跑证据；后续 T13 / T15 串行化及 T14 提交后事件分发约束不变。

## 本次修改（2026-10-02，ad8d770 补充交付复核）

- F1～F3 已独立复核关闭；F4 使用说明的三种注册情形共用同一库状态，输出与实现不符，待修正文档后复核；原 T11 DONE 与 R1～R4 关闭结论保留，不开始 T12。涉及阅读顺序第 15 条，详见 [复核记录第 9 节](docs/tasks/m2-t11/T11_架构与技术复核.md#9-ad8d770-补充交付独立复核)。
- 独立统一检查 227 个测试全绿，无生产行为变化；F4 情形 3 无正式用例不阻塞，源码审查与直接测试覆盖需区分。

## 本次修改（2026-10-02，T11 用户审阅后的补充收尾）

- 用户要求先完成剩余问题再进入下一步；[T11 复核记录第 8 节](docs/tasks/m2-t11/T11_架构与技术复核.md#8-用户审阅后的补充修复清单) 列出 F1～F4（测试时序、迁移编号、listener 与 Node ID 职责说明），当前补充项 TODO。涉及阅读顺序第 15 条的后续安排。
- T11 原交付 DONE、R1～R4 关闭结论保留；开发团队完成补充修复后交回复核，通过前不开始 T12。不要求实现查询订阅、缓存、T13 编排或其他已延后的能力。

## 本次修改（2026-10-02，T11 第二轮独立复核通过，DONE）

- `6fe4878` / `d5b33f8` 独立复核通过，R1～R4 关闭，S1～S3 处理可接受；227 个正式测试全绿，另独立验证 BEGIN 完成后取消不会遗留事务，T11 由复核方标为 **DONE**。涉及阅读顺序第 15 条，详见 [T11 复核记录](docs/tasks/m2-t11/T11_架构与技术复核.md) 第 7 节；不开始 T12。
- 后续 T13 / T15 需落实普通写入的串行化与必要读取的同锁约束，并避免将取消等同于无副作用；历史迁移实测及跨进程独占等边界不变。涉及状态与事务语义的后续承接。

## 本次修改（2026-10-02，T11 第一轮复核 R1～R4 已修复，待复核）

- 已复核 `ff7ed1f` / `041c95f` / `7b41734`；R1～R4 已落实：BEGIN 纳入事务生命周期且仅在本次确实取得事务时回滚、库版本高于代码时拒绝打开、迁移文件按升级前版本号命名、两份当前架构文档模块名同步并订正交付口径；`./scripts/check` 全绿，测试总数 223 → **227**（`vfs/persistence` 47 个）。T11 仍为 **IN_REVIEW**，DONE 由复核方标记。涉及“设计文档与阅读顺序”第 15 条与“开发与验证”，详见 [T11 架构与技术复核](docs/tasks/m2-t11/T11_架构与技术复核.md)。
- 单连接允许普通查询读取尚未提交的状态，后续 T13 / T15 需明确读取和自动提交写入的串行边界；本轮未修改实现，不开始 T12。涉及“VFS 必须保持的语义”的后续承接说明。

## 本次修改（2026-10-02，T11 实现完成，状态 IN_REVIEW）

- T11 状态库已交付：模块 `vfs/persistence`（由 `vfs/persistence-sqldelight` 更名，Kotlin 包名不变）实现四表 SQLDelight Schema、数据库级路径唯一约束、T07 四个 Repository、`UnitOfWork` 事务与数据库打开 / 关闭 / 迁移生命周期，全部用真实 SQLite 验证。新增 43 个测试（A01～A06 全覆盖），`./scripts/check` 全绿，测试总数 180 → **223**。同目录补齐 [使用说明](docs/tasks/m2-t11/T11_使用说明.md) 与[验收记录](docs/tasks/m2-t11/T11_验收记录.md)。涉及“设计文档与阅读顺序”第 15 条与“开发与验证”。
- 本阶段确定的实现要点：有效路径唯一由部分唯一索引保证、`register` 竞争复用；时间戳 epoch 毫秒；子树查询用 `instr` 精确前缀规避 `LIKE` 通配符与大小写不敏感；事务为整库单连接 + 显式 `BEGIN IMMEDIATE`（不使用 `runBlocking` 驱动挂起回调）。涉及“VFS 必须保持的语义”。
- 保留边界：并发 `inTransaction` 不支持（第二个 `BEGIN` 被 SQLite 拒绝报 `STATE_ERROR`），状态变更需由编排层串行；跨进程独占检测不做；Mount 写入属 T18、事件分发属 T14。涉及“VFS 必须保持的语义”与“开发与验证”。

## 本次修改（2026-10-01，模块解耦重命名）

- 模块名由按技术实现命名调整为按领域职责命名：
  - `vfs/storage-opendal` → `vfs/storage`：定位为文件物理读写与存储适配层。首发基于 Apache OpenDAL，但模块设计需具备多引擎扩展能力，预留接入其他数据访问层或自研存储引擎的空间。
  - `vfs/persistence-sqldelight` → `vfs/persistence`：负责 VFS 状态、Node、元数据与事件的持久化。
- 涉及工程构建配置 `settings.gradle.kts`、`vfs/runtime/build.gradle.kts` 及“模块职责与依赖”表。

## 本次修改（2026-10-01，T11 任务入口）

- 在“设计文档与阅读顺序”新增 T11 任务文档：`vfs/persistence-sqldelight` 的 Schema 与唯一约束、四个 Repository 实现、`UnitOfWork` 事务与数据库生命周期，全部用真实 SQLite 验证；含 SQLite 大小写敏感与子树查询两个已知陷阱的处理要求。任务尚未开始。涉及“设计文档与阅读顺序”。

## 本次修改（2026-10-01，T10 复核方收口确认，状态 DONE）

- T10 收口确认：R1 / R2 / R3 均验证落实，状态由复核方标为 **DONE**；交付链 `79d2f04`（实现）→ `8d39fc4`（交付文档）→ `2de60b7`（注释直白化，零行为变化）→ `956e1c4`（R1/R2 修正）→ `ef272ef`（越权状态回退），180 个测试全绿，未推送。下一任务 T11，不自动开始。涉及“设计文档与阅读顺序”第 14 条。

## 本次修改（2026-10-01，T10 落实复核修正，状态待收口确认）

- T10 第一轮独立复核结论：代码与测试无必须修改项；R1 / R2 文档修正已落实，状态保持 **IN_REVIEW**，待复核 Agent 收口确认后由其标 DONE；详见 [T10 架构与技术复核](docs/tasks/m2-t10/T10_架构与技术复核.md)。
- R3 传承：T15 / T20 编排层需先 stat 再构造 `OperationIntent`（`entryType` 是调用方声明，预检不校验类型事实）。涉及后续任务说明。
- 流程约定：任务状态（IN_REVIEW → DONE）由负责架构和复核验收的 Agent 修改，Lead / 开发 Agent 只负责落实修正与交接文档。

## 本次修改（2026-10-01，T10 实现完成，状态 IN_REVIEW）

- T10 变更操作预检已交付：Core 新增包 `core.operation`（`OperationIntent` / `CapabilitySnapshot` / `PreconditionResult` / `ExecutionStrategy` / `OperationGuard`），按参数冲突 → 结构保护 → 路由判定 → 只读 → 能力组合的固定顺序在存储副作用前拒绝，并输出执行策略。结构保护归并为一次 `MountRouter.isConfiguredDirectory` 查询，未修改 T09 路由。涉及“模块职责与依赖”“VFS 必须保持的语义”“开发与验证”。
- 既有类型只做一处兼容性补充：`StorageCapabilities` 增补 `readOnly: Boolean = false`（T07 的 StorageFakeImpl 与既有测试无回归）。本阶段确定的决策：能力快照缺 `storageKey` → `INVALID_ARGUMENT`；跨挂载点一律复制语义，不因 `storageKey` 相同改用原生 move（留 T12 / T18 重估）。涉及“VFS 必须保持的语义”。
- 新增 33 个单元测试（A01～A06 全覆盖，含检查顺序确定性），`./scripts/check` 全绿，测试总数 147 → **180**。同目录补 [使用说明](docs/tasks/m2-t10/T10_使用说明.md) 与 [验收记录](docs/tasks/m2-t10/T10_验收记录.md)；状态 IN_REVIEW，待独立复核。涉及“设计文档与阅读顺序”“开发与验证”。

## 本次修改（2026-10-01，T10 任务入口）

- 在“设计文档与阅读顺序”新增 T10 存储约束与操作能力检查的开发与验收文档：定位为纯逻辑的变更操作预检（结构保护、参数 / 路由判定、只读与能力检查、执行策略输出），含 `StorageCapabilities` 增补 `readOnly` 的兼容性说明；任务尚未开始。

## 本次修改（2026-09-30，T09 独立复核通过，状态 DONE）

- T09 架构与技术复核通过：代码与测试无必须修改项，R1～R3 文档修正已落实，状态改为 **DONE**；交付 Commit `9e28b62`，`./scripts/check` 全绿（147 个测试）。详见 [T09 架构与技术复核](docs/tasks/m2-t09/T09_架构与技术复核.md)。
- 复核确认的决策：命名空间名禁原始空格（超出路径段规则的收紧，已补记入任务文档 2.1）；挂 `/` 拒绝、挂命名空间根允许。

## 本次修改（2026-09-30，T09 使用说明重写）

- `T09_使用说明.md` 改为直白版：开头用大白话说明 MountRouter 解决什么问题，全文用「笔记本场景」贯穿，每个方法固定写「做什么 / 什么时候用 / 代码示例 / 边界情况」；新增「挂载位置的选择」，明确挂命名空间根（`/memory`）允许、挂逻辑根（`/`）拒绝。错误码表每行补了具体触发输入。涉及“设计文档与阅读顺序”第 13 条对应的配套文档。
- 补用例 `A01 mounting a namespace root itself is allowed`，路由用例 23 → 24，`./scripts/check` 全绿（147 个测试）；实现未改动，T09 仍为 IN_REVIEW。

## 本次修改（2026-09-30，T09 B / C 阶段完成，状态 IN_REVIEW）

- T09 路径与挂载路由已实现并自测通过：Core 新增 `MountRouter` / `RouteMatch`（包 `core.router`），提供挂载配置校验、最长完整段匹配、相对 StoragePath 与配置目录查询；新增 24 个单元测试，`./scripts/check` 全绿（147 个测试）。T09 状态改为 IN_REVIEW，等待独立验收。涉及“设计文档与阅读顺序”“开发与验证”。
- 路由是纯逻辑：不含 Storage / Repository，无匹配返回 `null`，`MOUNT_NOT_FOUND` 与目录合并由 T10 / T15 决定；物理根重叠与符号链接仍属 T12。涉及“模块职责与依赖”“VFS 必须保持的语义”。

## 本次修改（2026-09-30，T09 任务入口）

- 在“设计文档与阅读顺序”新增 T09 路径与挂载路由开发文档，明确纯逻辑范围，任务尚未开始。

## 本次修改（2026-09-30，远端 CI 通过，M1 收口）

- GitHub Actions 首次实跑通过：运行 [36725260393](https://github.com/NoahShen/alcyone-context/actions/runs/36725260393)，提交 `227f526`，job `build (ubuntu-latest, Linux x64)` `success`，约 2 分钟。Linux x64 的工具链与统一检查已完整验证，R1～R3 修复经此次运行确认生效。T08 状态改为 DONE。
- M1 四个任务（T05～T08）全部完成，阶段收口，下一步进入 M2（从 T09 Mount 路由开始）。涉及“项目定位与当前阶段”与“开发与验证”。
- 仍未验证的边界已写入 T08 验收记录：Linux arm64 需 ARM runner；OpenDAL native 实际加载属 T12；`pull_request` / `workflow_dispatch` 触发未实跑。

## 本次修改（2026-09-30，T08 第二轮复核 R4 文档收尾）

- 统一 T08 四份文档的当前有效说明：区分“已配置支持的平台”（macOS arm64 / Linux x64 / Linux arm64）与“已实跑验证的平台”（仅 macOS arm64），状态保持 IN_PROGRESS。涉及“设计文档与阅读顺序”。详见 T08 交付文档与验收记录。

## 本次修改（2026-09-30，工具链支持 Linux，CI 改为 ubuntu-latest）
## 本次修改（2026-09-30，工具链支持 Linux，CI 改为 ubuntu-latest）

- 项目工具链扩展到 macOS arm64、Linux x64、Linux arm64：`gradle/toolchain.versions` 按平台分列 JDK 的 URL 与 SHA-256，`scripts/dev` 按 `uname` 选平台并归一化 JDK 目录；Gradle 发行包与插件版本仍是单一来源。CI 运行器由 `macos-14` 改为 `ubuntu-latest`。涉及“开发与验证”。
- 修复两处会在 Linux 上触发的实现缺陷：`find -exec` 不能调用 shell 函数（导致安装清单被静默写成空文件），以及 GNU `sha256sum -c` 对空清单返回 0（会把未校验的安装判为完好）。安装清单现在要求非空。
- **验证边界**：macOS arm64 上 `./scripts/check` 通过（120 个测试）；Linux x64 验证到“下载校验 + 解压归一化 + 清单校验”（用真实 Adoptium 归档），**完整构建未在真实 Linux 上跑过**；Linux arm64 仅静态验证。详见 T08 验收记录第 5.1 节。T08 状态回到 IN_PROGRESS。
- 未改动任何 VFS 业务代码与测试。

## 本次修改（2026-09-30，T08 本地检查与 CI 配置）

- 新增统一检查入口 `./scripts/check`（等价 `clean build`，含编译、测试与 `spotlessCheck`，失败返回非零且不改写源码）与 GitHub Actions 工作流（`macos-14`，走 `scripts/dev bootstrap`）。开发前跑 `./scripts/dev bootstrap` 准备项目内工具链，日常用 `./scripts/check`；格式修正仍需显式运行 `spotlessApply`。T08 状态 IN_PROGRESS。涉及“设计文档与阅读顺序”“开发与验证”。

## 本次修改（2026-09-30，T08 任务入口）

- 在“设计文档与阅读顺序”新增 T08 开发与验收文档，复用现有项目工具链，任务尚未开始。

## 本次修改（2026-09-30，914615a 独立验收通过）

- 更新阅读顺序中的 T07 为 DONE，记录独立验收与 120 个测试结果；接口阶段审阅原则保持不变。

## 本次修改（2026-09-30，收敛接口阶段验收范围）

- 补充“开发与验证”的接口阶段审阅原则；T07 的 R2 / R3 不再阻塞，当前待处理范围以最新复核清单为准。

## 本次修改（2026-09-30，T07 独立复核）

- 更新阅读入口中的 T07 状态为待修复；取消时间 / ID 注入按交付记录中的用户决定执行，详细问题见 T07 修复清单。涉及“设计文档与阅读顺序”。

## 本次修改（2026-09-29，T07 任务入口）

- 在“设计文档与阅读顺序”增加 T07 Core 边界开发文档，任务尚未开始实现。

## 本次修改（2026-09-29，提交 4d3c872 最终验收）

- T06 最终验收通过，更新“设计文档与阅读顺序”“开发与验证”的状态及 72 个测试结果。

## 本次修改（2026-09-29，取消命名空间白名单）

- 移除 API 顶层目录白名单；命名空间由后续配置提供。涉及“VFS 必须保持的语义”。

## 本次修改（2026-09-29，T06 提交复核待修复）

- 记录 T06 提交复核发现路径不可变性、UUID / Unicode 与参数校验缺口，任务重新打开；更新当前测试状态。涉及“设计文档与阅读顺序”“开发与验证”。

本文为在本仓库工作的编码 Agent 提供项目背景、设计依据与开发约束，适用于整个仓库。文档默认使用中文，代码标识符使用英文。

## 项目定位与当前阶段

Alcyone Context 是 Personal Agent Framework 的长期 Context 基础设施，负责跨 Round、Agent、Harness 保存和提供长期上下文。整体方向是 **Agent-native Virtual File System + Memory Governance + Retrieval Optimization**。

- Resources 是普通长期文件，通过 VFS 管理。
- Memory 是需要语义治理和生命周期管理的长期认知，未来建立在 VFS 之上。
- Agent Framework 负责执行任务；Context System 负责 Agent 身份、目录分配、共享范围与访问隔离；VFS 提供统一文件访问，不理解 Agent 归属。

当前第一阶段只建设可独立使用的 VFS Kotlin Library。不要提前创建 `memory/`、`retrieval/`、`context/`、`http/`、`server/` 等后续模块。

仓库已有 T05 的六模块根构建骨架和项目内工具链，以及 T06 已实现的公共 API 类型（`VfsUri`、`VfsPath`、`Vfs` 接口等）；`tools/validation/t04/` 仍仅用于独立依赖验证。模块构建可用不代表对应业务能力已经实现；开始任务前检查实际文件。

## 设计文档与阅读顺序

1. [项目搭建说明 v0.1](<docs/Alcyone Context 项目搭建说明 v0.1.md>)：当前工程目录、模块职责、依赖方向与阶段边界。
2. [Personal Context System 设计说明 v0.3](<docs/Alcyone Personal Context System 设计说明 v0.3.md>)：整体定位及 Memory、Resources、Retrieval 的关系。
3. [VFS 技术设计 v0.2](docs/vfs/Alcyone_Virtual_File_System_VFS_技术设计_v0.2.md)：技术选型、核心模型与架构约束。
4. [VFS 核心用例 v0.2](docs/vfs/Alcyone_VFS_核心用例文档_v0.2.md)：操作流程、异常行为、状态变化与事件。
5. [T01 公共契约基线](docs/vfs/VFS_公共契约草案_v0.1.md)：2026-09-28 用户已审阅通过，文件名保留以兼容既有链接。
6. [T02 路径与文件操作语义](docs/vfs/VFS_路径与文件操作语义_v0.1.md)：2026-09-28 用户已审阅通过，包含路径、Mount、列表、覆盖及递归操作规则与验收样例。
7. [T03 状态与事务设计](docs/vfs/VFS_状态与恢复设计_v0.1.md)：2026-09-28 用户已审阅通过，明确存储职责、提交边界与失败报告。
8. [T04 依赖与能力验证](docs/vfs/VFS_依赖与能力验证_v0.1.md)：2026-09-28 用户已确认；版本组合在 macOS arm64 最小验证通过，首版默认文件限额 16 MiB（可配置）；记录后端差异与后续验收范围。

9. [T05 工程初始化与验收](docs/tasks/m1-t05/T05_工程初始化与验收.md)：开发 Agent 的阶段任务及 A01～A08 验收标准；同目录含使用说明与验收记录。

10. [T06 公共契约实现与验收](docs/tasks/m1-t06/T06_公共契约实现与验收.md)：API 类型、路径校验、事件与异常的实现范围及 A01～A08 验收标准；提交 `4d3c872` 最终验收通过，R1～R5 已关闭，T06 为 DONE。

11. [T07 Core 边界定义与验收](docs/tasks/m1-t07/T07_Core边界定义与验收.md)：Storage / Repository 接口、事务与事件边界及修订后的 A01～A08 验收标准；当前 DONE，代码 `914615a` 已独立验收通过，完整构建及 120 个测试通过；真实存储和事务实现留后续任务，见同目录验收记录第 9 节。

12. [T08 本地检查与 CI 配置及验收](docs/tasks/m1-t08/T08_本地检查与CI配置及验收.md)：本地检查入口、GitHub Actions、运行说明与 A01～A07 验收标准；**DONE**，提交 `a993576` 独立验收，Linux 扩展与 R1～R3 修复经远端 CI 运行 `36725260393`（`ubuntu-latest`，`success`）实跑通过。

13. [T09 路径与挂载路由开发及验收](docs/tasks/m2-t09/T09_路径与挂载路由开发及验收.md)：逻辑挂载校验、最长段匹配、StoragePath 与配置目录推导及 A01～A07 验收；**DONE**，交付 Commit `9e28b62` 独立复核通过，147 个测试全绿；详见同目录 [架构与技术复核](docs/tasks/m2-t09/T09_架构与技术复核.md) 与 [交付文档](docs/tasks/m2-t09/T09_交付文档.md)。

14. [T10 存储约束与操作能力检查开发及验收](docs/tasks/m2-t10/T10_存储约束与操作能力检查开发及验收.md)：变更操作预检、结构保护、只读与能力检查、执行策略输出及 A01～A07 验收；**DONE**，交付链 `79d2f04` / `8d39fc4` / `2de60b7`（含修正 `956e1c4` / `ef272ef`），独立复核通过并由复核方收口，180 个测试全绿；详见同目录 [架构与技术复核](docs/tasks/m2-t10/T10_架构与技术复核.md) 与 [交付文档](docs/tasks/m2-t10/T10_交付文档.md)。

15. [T11 SQLDelight Schema、Repository、事务与数据库生命周期开发及验收](docs/tasks/m2-t11/T11_SQLDelight存储事务与生命周期开发及验收.md)：状态库四表 Schema 与唯一约束、Repository 与事务实现、生命周期与迁移及 A01～A07 验收；当前 **DONE**（修复 `6fe4878` / `d5b33f8` 第二轮独立复核通过，R1～R4 关闭），227 个测试全绿，模块为 `vfs/persistence`；详见同目录 [使用说明](docs/tasks/m2-t11/T11_使用说明.md) 与[验收记录](docs/tasks/m2-t11/T11_验收记录.md)。 **补充 F1～F4 已全部复核关闭，F4 修正提交 `2e35b79`；不自动开始 T12。**

16. [T12 Local FS 存储适配开发及验收](docs/tasks/m2-t12/T12_LocalFS存储适配开发及验收.md)：实现 `vfs/storage` 的 OpenDAL Local FS Adapter，含真实文件操作、物理路径检查、流与限额、能力和错误映射；当前 **DONE**，`38394d1` R7 独立复核通过，R1～R7 全部关闭，363 个正式测试通过，详见同目录架构与技术复核第 10 节；可以开始 T13。

17. [T13 Node Registry 与懒注册开发及验收](docs/tasks/m2-t13/T13_NodeRegistry与懒注册开发及验收.md)：前置 T09～T12 已完成，当前 **DONE，21a1a30 修复独立复核通过，R1～R3 关闭，419 个正式测试通过**；实现属于 Core，T15 复用查询 / 注册与共享串行边界，真实 SQLite + Local FS 验证放 integration-tests。

18. [T14 事件产生、持久化与进程内分发开发及验收](docs/tasks/m3-t14/T14_事件产生持久化与进程内分发开发及验收.md)：当前 **DONE，250c0bd 独立复核通过，R1～R4 全部关闭**，461 个正式测试通过，见 [复核清单](docs/tasks/m3-t14/T14_架构与技术复核.md)；含 S1～S3 与 A01～A08；不实现可靠消费、Git Consumer 或 Runtime 完整停机，T15 复用提交与分发接线。

19. [T15 基础文件操作开发及验收](docs/tasks/m3-t15/T15_基础文件操作开发及验收.md)：当前 **DONE，`2e5083b` 复核通过，R1～R3 与 S1 全部关闭，508 个正式测试通过**，见 [复核记录第 8 节](docs/tasks/m3-t15/T15_架构与技术复核.md#8-2e5083b-第二轮修复收口复核2026-10-04)；实现 read / write / stat / list 与 getNode 接线，写入从预检到状态提交共享唯一持锁边界，SQLite 不包 Storage I/O。Runtime 与公共流式入口接线留 T18 / T19，不提前实现删除、Metadata 或移动。

20. [T16 删除操作开发及验收](docs/tasks/m3-t16/T16_删除操作开发及验收.md)：当前 **DONE，1e8857d / 0039e8d 第三轮收口通过，R1 / R2 与 S1～S3 全部关闭**；沿用 8812b0f 独立 528 例通过证据，见同目录复核第 9 节。实现删除、已有 Node / Metadata 清理及目标级事件；T17 可以开始。

21. [T17 Metadata 查询与更新开发及验收](docs/tasks/m3-t17/T17_Metadata查询与更新开发及验收.md)：当前 **DONE，6862ef7 独立收口通过，R1 与 S1～S3 全部关闭，542 例通过**；实现有效 Node ID 的纯逻辑查询 / 整体替换，时间与事件同事务。见同目录复核第 7 节；T18 可以开始。

22. [T18 Runtime 配置、组装与生命周期开发及验收](docs/tasks/m3-t18/T18_Runtime配置组装与生命周期开发及验收.md)：**DONE**，c9d3936 独立 609 例通过，R1～R8 全部关闭；最终结论见复核第 12 节。

23. [T19 Local FS 纵向集成与调用样例](docs/tasks/m3-t19/T19_LocalFS纵向集成与调用样例开发及验收.md)：**DONE**，675f91e 独立 617 例通过，R1 / R2 关闭、S1～S4 已处理；M3 DONE，见 [复核第 6 节](docs/tasks/m3-t19/T19_架构与技术复核.md)。不开始 T20。

24. [T20 同 Mount 文件移动开发及验收](docs/tasks/m4-t20/T20_同Mount文件移动开发及验收.md)：**DONE**，`b991f55` 独立复核 R1～R3 关闭、642 例通过；实现同 Mount 原生文件移动与失败报告（A01～A08），复制回退 / 跨 Mount 留 T21，目录留 T22。

25. [T21 复制回退与跨 Mount 文件移动开发及验收](docs/tasks/m4-t21/T21_复制回退与跨Mount文件移动开发及验收.md)：**DONE**，`81251ce` 独立 681 例通过，R1～R4 全部关闭；实现复制回退与跨 Mount 文件移动，详见同目录复核第 7 节；目录移动留 T22，本轮不开始。

26. [T22 目录移动与子 Node 路径更新开发及验收](docs/tasks/m4-t22/T22_目录移动与子Node路径更新开发及验收.md)：**DONE**，`294544d` 独立 704 例通过，R1～R4 全部关闭；同 / 跨 Mount 普通目录、空目录和子 Node 身份迁移已验收，详见同目录复核第 7 节；M4 仍需 T23，本轮不开始。


27. [T23 变更操作事务与失败报告验证及验收](docs/tasks/m4-t23/T23_变更操作事务与失败报告验证及验收.md)：**DONE**，`a768c89` / `61cd1f8` 独立 712 例通过，R1 / R2 关闭；见 [复核第 7 节](docs/tasks/m4-t23/T23_架构与技术复核.md#7-a768c89--61cd1f8-修复收口复核)。M4 DONE，不自动开始 T24。

28. [T24 WebDAV 配置与测试服务开发及验收](docs/tasks/m5-t24/T24_WebDAV配置与测试服务开发及验收.md)：**DONE**，独立 735 / 11 例通过，R1 / R2 / R4 / R5 关闭；用户已确认方案④，R3 以范围调整接受，请求 / close 无最长耗时保证。见 [复核第 9 节](docs/tasks/m5-t24/T24_架构与技术复核.md#9-用户确认方案④与最终收口2026-10-10)。后续按 T25 任务执行。

29. [T25 WebDAV 契约与跨后端集成开发及验收](docs/tasks/m5-t25/T25_WebDAV契约与跨后端集成开发及验收.md)：**DONE**，5e8f282 链条独立 741 / WebDAV 42 通过，R1～R4 关闭；S2 提前 EOF 补零仍为非阻塞建议，见 [复核第 8 节](docs/tasks/m5-t25/T25_架构与技术复核.md#8-5e8f282--d7d1789--7bea83b-最终收口复核2026-10-12)。不开始 T26。

30. [T26 事件持久化与进程内分发验证及验收](docs/tasks/m5-t26/T26_事件持久化与进程内分发验证及验收.md)：**DONE**，3cb3aa8 链条独立复核通过，R1 / R2 关闭，743 / WebDAV 42 通过；见 [复核第 8 节](docs/tasks/m5-t26/T26_架构与技术复核.md)。生产零改动，方案④保持，M5 未收口，T27 已按用户要求安排。

31. [T27 并发、取消与资源生命周期验证及验收](docs/tasks/m5-t27/T27_并发取消与资源生命周期验证及验收.md)：**DONE**，a778418 链条独立 748 / WebDAV 42 通过，R1～R3 关闭，见 [复核第 7 节](docs/tasks/m5-t27/T27_架构与技术复核.md)。生产零改动，方案④保持；M5 未收口，T28 已按用户要求安排。

32. [T28 平台支持与诊断能力核查及验收](docs/tasks/m5-t28/T28_平台支持与诊断能力核查及验收.md)：**DONE**，8407ce1 独立 752 / WebDAV 43 通过，R2 / A05 关闭；用户选择平台方案二，A03 按 macOS arm64 范围通过，Linux x64 / arm64 保留配置但未验证。M5 DONE，见 T28 复核第 9 节；不开始 T29。

33. [T29 完整用例回归与架构审查](docs/tasks/m6-t29/T29_完整用例回归与架构审查开发及验收.md)：**DONE**，a4e8af6 链条独立复核 R1～R3 关闭，沿用 752 / 43 通过证据，见 T29 复核第 7 节；不开始 T30 / T31。

34. [T30 README 与 SDK 使用及运维文档开发及验收](docs/tasks/m6-t30/T30_README与SDK使用及运维文档开发及验收.md)：**DONE**，交付根 README、SDK 使用指南、运行维护说明及任务验收记录；样例与备份步骤以真实公共入口验证，不提前做 T31 发布配置，独立复核通过，见 T30 复核第 7 节。
35. [T31 SDK 打包与本地消费开发及验收](docs/tasks/m6-t31/T31_SDK打包与本地消费开发及验收.md)：**DONE**，项目内本地仓库与独立 JVM 产物消费，核对 native、传递依赖及公开流程；不远端发布，T32 已按用户要求安排。

36. [T32 首版交付记录与后续需求整理及验收](docs/tasks/m6-t32/T32_首版交付记录与后续需求整理及验收.md)：**DONE**，交付首版交付记录与后续需求清单，更新工程指引并归档 M0～M6 里程碑核对表；双套测试 752/43 及独立消费全绿，caef0b8 独立收口通过，M6 DONE，见 T32 复核第 7 节；不自动启动扩展或公开发布。

开发任务与进度统一记录在 [开发计划与进度](docs/开发计划与进度.md)。开始开发前核实任务依赖；完成后更新状态、负责人和验收证据。计划中的待定决策与建议不代表已冻结契约。


处理文档差异时：

- 工程结构采用 `common`、`vfs/api`、`vfs/core`、`vfs/storage`、`vfs/persistence`、`vfs/runtime` 六模块划分，已落实本地 Maven 发布（`.local/sdk-repository/`）与独立 JVM 消费验证闭环；正式远端发布（Maven Central）策略作为后续工程候选 E09，首版尚未公开发布。
- VFS 公共接口以已通过的 T01 为设计基线；其明确交给后续任务的细节仍未冻结。T02 具体操作规则也已通过审阅，优先于早期总体文档中未展开的示意；T03 简化后的事务与失败报告规则也已确认，操作恢复和可靠消费延后。
- T01 第三轮已确认：VFS API 仅接收文件操作参数；Context System 负责 Agent 目录分配与访问范围。保留 VfsEntry / NodeInfo；详细接口见已审阅通过的 [公共契约基线](docs/vfs/VFS_公共契约草案_v0.1.md)。
- `docs/memory(旧文档-Alcyone仅作为memory系统的设计)/` 是旧 Memory-only 系统的历史资料。其 TypeScript 技术栈、HTTP API、Memory ID、写入顺序及 SQLite 可重建等规则，不适用于当前 VFS。
- Git Sync 在技术设计中列为第一阶段能力，但最新搭建说明未安排对应模块。它始终属于外围 Consumer；按具体任务开展，不因早期目录示意就将其加入 Core。
- 当前架构与主要流程见 [架构与工作流程图](docs/vfs/架构与工作流程图.md)，与公共契约同步维护。
- 对尚未确定的行为，在相关设计文档中记录决策或待定项，避免把推测写成既定规范。

## 技术基线

| 领域 | 设计选型 |
| --- | --- |
| 语言与运行时 | Kotlin / JVM，JDK 21+ |
| 运行形态 | 纯 Kotlin Library，Library First |
| 异步 | Kotlin Coroutines；事件使用 Flow / Channel |
| 文件存储 | Apache OpenDAL Java Binding；首阶段 Local FS、WebDAV |
| VFS 状态持久化 | 独立 SQLite 数据库 + SQLDelight |
| Node / Event ID | UUIDv7 |
| 序列化 | 优先 Kotlin Serialization |
| 日志 | SLF4J API，具体日志实现由宿主决定 |

Core 不依赖 Micronaut、Spring Boot 或 Ktor Server。不引入独立 MQ、通用 Storage Watch、完整 Secret System 或面向海量 Mount 的复杂索引。

OpenDAL 以 JVM 依赖嵌入运行，无需独立服务；构建发布时需处理目标 OS / CPU 对应的 native classifier，不将 JNI 细节放入领域模型。已确认的版本组合和最小验证命令见 T04；正式工程在 T05 配置 Wrapper 和依赖。

正式开发使用项目内独立工具链：JDK、Gradle 发行包及依赖缓存保存在仓库内忽略目录，由开发入口对子进程设置环境；启动 JVM、Daemon、编译和测试都使用项目 JDK。不得改系统默认 JDK、全局 Gradle、用户 shell 启动文件或全局环境配置；不依赖 T04 的临时目录，具体交付要求见 T05。



## 模块职责与依赖

| 计划目录 | 职责 |
| --- | --- |
| `docs/` | 设计与决策 |
| `common/` | 与 `vfs/` 平级的公共工具库，供 `vfs`、`memory` 等业务模块共用；当前提供 UUIDv7 生成与校验 |
| `vfs/api/` | 公共接口和领域类型，如 `Vfs`、`VfsUri`、`VfsPath`、`NodeInfo`、选项、事件与异常 |
| `vfs/core/` | URI、Node、Metadata、Mount、Event 规则及文件操作编排；定义 Storage / Repository Port；含挂载路由（`core.router`）与逻辑配置校验 |
| `vfs/storage/` | 实现 Core 的 Storage Port，负责文件内容的物理存储访问。首发基于 OpenDAL，设计上需支持未来扩展/替换其他存储引擎或数据访问层 |
| `vfs/persistence/` | 实现 Core 的 Repository Port，保存 Node、Mount、Metadata、Event Log（当前基于 SQLite + SQLDelight） |
| `vfs/runtime/` | SDK 入口与依赖组装，提供 `AlcyoneVfs`、配置及完整 VFS 实例 |
| `integration-tests/vfs/` | 组合 Runtime、Core、真实 SQLite 与 Storage 的跨模块测试 |

依赖方向：`common` 不依赖任何业务模块；`core → api → common`；两个基础设施模块依赖 Core 定义的 Port；Runtime 组装 API、Core 和基础设施实现。Core 不反向依赖具体 Adapter。

- API 不暴露 OpenDAL、SQLDelight、SQLite 或 Repository 实现。
- `common` 只放与业务无关的工具；它不知道 VFS 错误契约，抛自身异常，由 `vfs/api` 在边界转换。业务规则、协议常量和领域校验不放在 `common`。
- Core 中的 Storage Port 仅表达业务所需能力，不另建一套通用存储后端框架；后端差异交给 OpenDAL。
- Runtime 隐藏 Repository、Driver、Operator 和各 Manager 的创建过程。
- SQLDelight Schema 放在持久化模块的 `src/main/sqldelight/`；单元测试放在所属模块的 `src/test/`。

## VFS 必须保持的语义

### 路径、身份与目录

- `memory` / `resources` 是顶级命名空间示例，不是 API 白名单。VfsUri / VfsPath 仅校验路径语法，保留所有路径段的大小写；命名空间由 Runtime 配置（`VfsRuntimeConfig.namespaces`）定义，各挂载项由 `mounts: List<MountConfig>` 提供。
- 区分 `VfsUri`、`VfsPath` 和 `StoragePath`。例如 `alcyone://resources/medical/ct/a.dcm` 对应逻辑路径 `/resources/medical/ct/a.dcm`；挂载 `/resources/medical/` 后，Storage Path 为 `ct/a.dcm`。
- URI 表示位置，Node ID 表示身份。移动、重命名和跨 Mount 移动均保持同一个 Node ID。
- 挂载路由按已解码的完整路径段做最长前缀匹配（T09）：逻辑路径无法经路由逃出挂载相对路径，但这不代表已抵御真实文件系统的符号链接或外部并发替换。
- Metadata 等逻辑信息绑定 Node ID；Node Registry 优先按全局 `VfsPath` 定位 Node，调用方无需预先知道 Mount。
- 已确认保留 `VfsEntry` 和 `NodeInfo`：`list` 返回 Node ID 可空的 `VfsEntry`，不批量注册；`NodeInfo` 必须有稳定 Node ID。
- 文件和目录都可成为 Node。目录内容由 Storage `list` 推导，不在数据库维护完整目录树，不强制注册所有目录。
- 普通读取不要求 Node 已注册，默认不修改 Node、Metadata，也不产生变更事件。
- `stat(uri)` 可确认底层资源存在后懒注册 UUIDv7 Node；T02 定义的纯虚拟目录可据配置注册且无物理映射；`getNode(id)` 只查已注册的逻辑记录。当前有效 Node 的 `vfs_path` 建议保持唯一。

### 文件访问与存储约束

```text
VfsUri / 参数校验 → VfsPath → Mount / StoragePath → 存储约束检查 → Storage I/O
```

- 文件、Metadata、事件和 Runtime 生命周期接口统一面向 Context System，直接接收操作参数。
- Context System 将 Agent 请求限定到分配的目录后再调用 VFS；VFS 不按调用者过滤 list，也不把目录名解释为 Agent。Agent 目录范围由上层落实。
- VFS 校验 URI、路径段边界、物理挂载根、后端能力和覆盖规则；这类规则不依赖调用者。
- Node Registry 按 VfsPath 维护资源身份，按需参与查询 / 变更。
- 已注册 Node 的 Metadata 管理只操作逻辑状态，不需要 Mount 或 Storage。
- 首版不增加可配置只读挂载；后端拒绝访问或凭据错误须明确返回。Metadata 存储在独立状态库中。

### 写入、移动与失败处理

- 创建 / 写入先完成 Storage 操作，再创建或更新 Node 状态；Storage 失败时不更新成功状态、不发布成功事件。
- 同 Mount 移动优先使用原生 rename / move，并检查后端能力。
- 跨 Mount 移动遵循读取源 → 写入目标 → 确认成功 → 删除源，再更新同一个 Node 的逻辑路径与物理映射。
- 未注册源资源若允许移动，应在确认资源存在后建立 Node Identity，保持移动前后的身份连续性。
- 删除成功后按确定的删除策略处理 Node、Metadata。
- Storage 成功而逻辑状态更新失败、跨 Mount 目标写入成功而源删除失败等情况，首版返回错误和实际 effect，不自动补偿或修复；中断与重启恢复列为后续 E04，不能假定 SQLite 事务可以回滚外部 Storage。

### 状态与事件

- VFS SQLite 保存 Control Plane 关键状态，包含 Node Identity、路径映射、Mount、Metadata、Event Log。它不是缓存，不能随意删除重建，也不与未来 Memory 查询库共享数据库或 Repository。
- 成功的状态变更产生标准 VFS Event，包括创建、写入、移动、删除、Metadata 更新；首版不对外发布 `NODE_REGISTERED`。
- 实时分发使用 Coroutine Flow / Channel；首版保留 Event Log，不实现持久化消费进度或重启重放，可靠消费列为后续 E05。
- 状态与事件在同一事务中提交，提交后通知；首版不承诺可靠投递，不从示意图推断未实现的保证。
- 状态库实现（T11）：当前有效路径唯一由数据库部分唯一索引保证，`markDeleted` 释放路径后新 Node 可立即复用；时间戳统一 epoch 毫秒；子树查询按完整段边界且大小写敏感（不用 `LIKE`）；事务为整库单连接 + 显式 `BEGIN IMMEDIATE`，**并发 `inTransaction` 不支持**，状态变更需由编排层串行。
- Git Sync、Index、Audit 属于外围 Event Consumer，其失败不改变已成功的 VFS 操作结果。Git 本地备份按 2026-10-15 用户要求作为独立可选插件，宿主通过既有 subscribe 接线，不修改 Runtime 配置或生命周期。
- Git 不是 Storage Backend；需要 Git Sync 时优先使用 Git CLI。同步扩展由宿主独立配置并订阅既有事件，普通文件调用不需要操作 Worker；Git 本地备份的定时任务和失败后再触发全部在插件内，不回滚已成功文件操作。Git Sync 仍按 E1 单独交付，不承诺通用双向同步。
- 第一阶段仅覆盖经由 VFS 发起的变更事件，不保证发现绕过 VFS 的外部文件变化。

## 开发与验证

接口设计阶段重点检查职责、参数 / 返回值、依赖方向与必要的调用语义。未实现的流程、后端机制及完整故障测试留到对应开发任务，不为验收接口扩建测试替身或提前设计实现框架；已实际交付的实现代码仍检查其正确性。

**代码格式：写完代码必须跑 `./scripts/dev gradle spotlessApply` 再交付。`spotlessCheck` 已挂进 `check`，格式不过 `./scripts/dev gradle build` 会直接失败。依赖与插件版本在 `gradle/libs.versions.toml` 声明；JDK 与 Gradle 发行包的版本、下载地址和 SHA-256 在 `gradle/toolchain.versions` 声明。构建文件与脚本不得再硬编码版本号。**

**任务文档目录：每个开发任务在 `docs/tasks/<里程碑>-<任务ID>/` 下建立独立目录，存放该任务的任务说明、使用说明和验收记录，例如 `docs/tasks/m1-t05/`。不要把不同任务的文档平铺在 `docs/tasks/` 根目录。**

**文档更新规则：每次修改文档，必须在被修改文档的前面部分增加或更新“本次修改”说明，列出本次主要修改内容及涉及的章节；仅在文末记录或仅在交付消息中说明不满足要求。适用于设计文档、进度文档和本文件。**

**后续设计文档保持简洁：每项以 2～3 句话说明规则，配一个简单例子。沿用已确定的架构和核心用例，不重复展开；字段、SQL 和锁可在开发中明确，只回写影响契约的重要结论。**

**协作分工：当前 Architect 只维护文档、审查与独立验收，不直接修改生产代码。E1 已由用户授权通过 Paseo 按 Architect → Lead → Worker 推进；Worker 实现自测、Lead 初验后标 IN_REVIEW，Architect 独立复核通过才标 DONE 并通知 Lead 启动下一项，无需用户逐项重复授权。验收问题交回 Lead 安排 Worker 修复，未复核通过不能提前继续。其他未授权扩展不得自动启动。**

开始实现前，先阅读对应设计和用例，检查已有代码、构建配置及工作区改动。按任务需要建立模块，不提前搭建后续空架构。变更公共契约、模块边界或持久化语义时，同步更新相关设计文档。

路径、覆盖、目录递归和嵌套 Mount 按已通过的 T02 实现。后端实际能力由 T04 验证，状态与事务按已确认的 T03 推进；操作中断与重启恢复不纳入首版；细节可在对应开发任务中明确。不要将设计示例中的省略视为允许跳过这些行为。

实现后的测试按变更范围选择，重点验证：

- URI / Path 转换、Mount 最长前缀匹配与路径边界。
- 路径逃逸、后端能力和错误转换；已知无效的变更在副作用之前拒绝。
- 创建、写入、读取、`stat` 懒注册、目录列表、移动、删除与 Metadata 管理。
- 同 Mount 和跨 Mount 移动的 Node ID 连续性，以及目标写入失败、源删除失败和 Registry 更新失败。
- 正常关闭后重启可读取已提交的 SQLite 状态，不要求修复中断操作，事件对应成功变更，Consumer 失败不影响已完成操作。
- 通过 Runtime 组合真实 SQLite 与 Local FS / WebDAV 的集成行为。

T05 构建骨架已复核通过：使用 `./scripts/dev bootstrap` 准备项目工具链，`./scripts/dev gradle clean build --console=plain` 构建；完整命令及证据见 T05 使用说明与验收记录。历史阶段基线（T11: 227 例，T12: 363 例，T13: 419 例，T14: 461 例）均已在各任务复核记录中归档。当前 M0～M6 全量核心开发已完成，常规回归套件 `./scripts/check`（752 例）与真实 WebDAV 集成套件 `./scripts/webdav-check`（43 例）持续全绿，六模块本地打包发布（`0.1.0-SNAPSHOT` 至 `.local/sdk-repository/`）与独立最小 JVM 消费工程（`./scripts/t31-consumer-check`，退出码 0）已闭环端到端验证。仅修改文档时检查路径引用与设计一致性即可；交付时说明修改内容、验证结果及未验证事项。
