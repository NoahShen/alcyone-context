# CT26：Dreaming 生产接线、公共入口与集成验收计划

- 里程碑：CM5 Dreaming。
- 状态：IN_REVIEW / 追溯性方案；CT26 未 DONE，CM5 未收口。
- 依据：[CT26 任务书](../../milestones/cm5/CT26_任务书.md)（现行 B1/B2/B3 定义与 D1～D8）、[CM5 范围](../../milestones/cm5/CM5_范围与依赖方案.md)、[方案核对](方案核对.md)、[Architect 追溯复核](Architect_追溯复核.md)、[B1 Lead 委派单](B1_委派单.md)。
- 本文件只规划后续定点修正与验收，不把方案前落盘的代码倒签为已批准实现，也不把已有空库/单元测试当成真实集成通过。

## 1. 现状基线与范围

已落盘：公共 `runDreaming()`/结果/异常、Factory/Impl、`DreamingRuntime`/`DreamingBinding`、YAML 入口及其单元测试；CT22～CT25 的 Coordinator、四 Stage、Committer 和 SQLite ID 范围读取。追溯复核仅批准既有公共接线作为临时 B1 状态，明确 CT26 未 DONE。

已确认的 R1 缺口：默认不支持错误码误用 `ALREADY_RUNNING`；无 Cron 的部分 Dreaming 绑定延迟失败；公共手动 run 未纳入关闭等待；非空真实 HTTP/SQLite、多批恢复与固定集证据不足。start 在发布 RUNNING 前启动立即 tick 的 Cron，也须用回归证明不会提前读 DB/HTTP。

本轮允许：`context/api` Dreaming API/异常及测试，`context/runtime` Factory/Impl/Dreaming Runtime/Coordinator/Binding 及必要测试，`integration-tests/context` 真实公共入口测试，CT26 文档。不得为隐藏缺陷删除测试或放宽断言。

不包含：Backup、HTTP Dreaming 端点、StartupRecovery、持久化任务队列、Dreaming 直写 Organized/Map、Agent SDK/CM6、SQLite 索引或迁移、prompt/JSON 协议改写、Git 写操作、默认真实收费模型调用。

## 2. 批次、并行性与交付

| 批次 | 工作 | 输入依赖 | 交付/退出条件 |
|---|---|---|---|
| B1：接线 R1 与确定性集成 | D1～D6 最小修正；真实 SQLite/VFS/受控 HTTP 非空链、多批、失败、重启、manual/Cron/close | CT22～CT25 已批准组件；CT26 任务书 R1 | [B1 实施清单](B1_实施清单.md) 全完成；配置矩阵、非空链、失败原子性、恢复和生命周期测试通过；实现交接与独立验证记录 |
| B2：独立验收与固定集 | 不改生产；全量/边界核验、独立 JVM COMMIT 后恢复、固定脱敏 JSONL 与 Fake 结果归档 | B1 全部通过 | [B2 实施清单](B2_实施清单.md) 全完成；独立进程证据、固定集、全量 XML 计数、Schema/依赖边界、交接齐全 |
| 可选 B3：真实模型评测，LOCKED | 单独脱敏样本、Profile、数据/费用边界与结果 | 用户对模型、外发数据和费用的明确授权 | 未获授权不派发，记录 `not-run`；不影响 B1/B2 确定性验收，不声称真实语义质量通过 |

B1 由一个 Worker 定点实现，具体文件白名单与测试矩阵以 [B1 Lead 委派单](B1_委派单.md) 为准，Lead 初验后再独立复核；B2 独立验收者不改生产。可在不改源码的前提下并行设计 B2 固定集和子进程探针，但不得以并行准备替代 B1 通过。任何同文件修改串行；若必须改变 Core 语义或扩大公共 API，先交接 Architect 裁定。

## 3. B1 实施约束

### 3.1 公共入口与配置

1. `ContextDreamingErrorCode` 增加 `UNAVAILABLE`；第三方默认 `runDreaming()` 抛该码，`ALREADY_RUNNING` 仅表示真实重叠。保留六字段 `DreamingResult`、`DreamingRunException(stage, partialResult(completed=false), cause)` 和 CE 原样传播。
2. 四 Task `DREAMING_TOPIC/MEMORY_SEARCH/DREAMING_EVIDENCE/DREAMING_REFLECTION` 均存在、Profile 均存在且为 JSON_SCHEMA/JSON_OBJECT 才完整。完整绑定无 Cron 也可手动；Cron 只增加调度入口。
3. 有 Cron、或有任一 Dreaming Task 而绑定不完整/模式非法，都在资源获取前抛 `ContextConfigException(INVALID_CONFIG)`；完全无 Dreaming Task 才保持 ingestion-only。不能用“合法才静默不建 Runtime”掩盖部分配置。
4. `create()` 不开 DB/VFS/协程/HTTP；完整绑定也仅在 `start()` 取得资源后组装；Cron 启动失败走既有逆序清理。公共 Factory 不暴露测试参数，tick/configurator 只走 internal 重载。保留既有 YAML 严格字段、五字段 cron、IANA timezone 和默认 Asia/Shanghai 行为。

### 3.2 单 Worker、生命周期与固定增量

- 手动/Cron 共用 Coordinator 单 Worker；手动重叠 `ALREADY_RUNNING`，Cron 重叠跳过；不添加手动重试。固定 `C0 < id <= U`，按 ID 全状态重扫；Raw+Pending+CAS 提交后才推进 checkpoint；新 turn 下一轮处理。
- 手动准入登记、状态封闭与关闭共用同步锁，持锁不挂起。close 先封闭手动/Cron 新工作；取消并 join owned run，等待已登记手动 run 的实际收尾，再释放 LLM/SQLite。不能让 close 返回时 `activeRun=true`，也不能只等待调用方协程句柄而漏掉后台执行体。
- 手动调用方取消仍归调用方，close 不取消手动调用方协程，不把调用方取消转为 CLOSED；取消不重试、不包装成普通 Dreaming 失败。重复 close 共享同一真实清理结果，保留同步 `close()` 不可中断等待与 `closeAsync()` 等待者取消语义。启动/关闭竞争和立即 tick 必须有零提前 DB/HTTP 回归。
- 关闭等待/资源释放失败遵守既有共享失败语义；不在本任务增加未经裁定的超时、资源泄漏或关闭状态机契约。

### 3.3 真实确定性集成

每场景独立临时目录、真实 `initializeDatabase()`/ContextFactory/SQLite/VFS/LocalFs、现有 KtorLlmClient 和四 Stage；HTTP 仅 `127.0.0.1:0` JDK stub，不创建平行 LLM Adapter。多 Profile 各自 key/model 不串用，日志与归档不记录 Key、原始敏感正文或完整响应。

| 场景 | 必须证明 |
|---|---|
| 默认/非空手动链 | 无 Cron 无自动工作；Conversation → Topic → Evidence/Memory Search → Reflection → Raw+Pending+checkpoint；六字段结果正确；Organization 通知可观测，Dreaming 不直写 Organized/Map |
| 多批与固定范围 | 多 session/状态；按 ID 重扫；运行中新增 turn 下一轮；每轮 Raw+Pending+CAS 原子；checkpoint 为最后已提交 ID；已有 Organized/Raw 与双向引用正确 |
| 空轮/重跑 | 该轮 Dreaming 请求为零，无重复 Raw/Pending，无多余 Organization/Map 唤醒；completed=true |
| 多批失败 | 第二批 HTTP/解析失败时准确 stage/partial/cause；前批保留，失败批无 Raw/Pending/checkpoint；尝试数精确，CE 不进入普通失败 |
| 正常重启续跑 | 真实 close/open SQLite 后续跑，前批不重复、后续继续；不是只重建内存 Coordinator |
| 自动与互斥 | tick 只入队；手动/Cron 重叠语义；D2 成功合并后一次 Organization 唤醒，D1/D3 边界不变；公开读取不见半成品 |
| 生命周期 | create/start 零意外请求；启动失败清理；手动/Cron 与 close 竞态、调用方取消、close 后无新 HTTP/DB 工作 |

按实际请求 body 核对 Task、Profile/model、response format 和 Key 隔离，不能只凭总请求数推断阶段。正常单 Topic 新记忆基线为 `TOPIC_DISCOVERY → MEMORY_SEARCH → DREAMING_EVIDENCE → DREAMING_REFLECTION`；有 Organized novelty、D2 Organization/Map 时按实际链路分别追加并验证。每个用例 finally 停止 tick、关闭 Context、关闭 stub、清理临时目录。

## 4. B2 独立验收与固定集

1. 执行 `./scripts/check`，核对全绿、Spotless、14 模块 `@Test`=JUnit XML、81 VFS 基线、Schema/VFS/common/Core 依赖边界；记录真实数字和命令，不沿用 CT25 的 2118/2124 作为本轮结果。
2. 独立 JVM 子进程在可控处理器“真实提交已返回、结果发布前”停止，模拟 COMMIT 后崩溃；下一进程验证前批不重复、后续可继续。使用临时 HOME/DB 和明确子进程 PID，不 kill 正常 Gradle daemon，不污染正式数据。同进程重建不得替代此证据。
3. 归档固定脱敏 JSONL、Prompt/Schema/Profile 版本、输入输出期望与 Fake 结果；真实模型结果独立文件，未授权时明确 `not-run`。固定集只评价确定性语义映射，不把 Fake 结果当真实模型质量。
4. 填写 [实现交接](handoff.md)：目标、范围、任务状态、测试状态、实际文件、调用边界、配置兼容、事务/幂等/关闭证据、命令与 XML、风险、下游建议；如适用再填实际 commit/GPG 证据。未执行与失败必须显式列出。

## 5. 契约一致性检查

- 保持 CT22～CT25 的固定增量、严格解析、四类语义、幂等与唤醒边界；本任务只修接线和验证真实组合。
- 无新配置开关、备份、SDK 路由、迁移或 prompt 协议；真实模型默认 LOCKED，本机凭证存在不代表使用授权。
- `UNAVAILABLE` 与 `ALREADY_RUNNING` 不混用；无配置与部分配置在启动前区分；手动 run 与 owned run 的关闭责任分别实现。
- 事务可见性、CE、关闭等待与失败共享结果优先于旧测试中与之冲突的描述；调整测试须记录原因，不削弱断言。
- 本计划不代表实现完成；CT26 只有 B1/B2 实际验收齐全后才可提请 CM5 收口，不能自行宣布 CM5 DONE。

