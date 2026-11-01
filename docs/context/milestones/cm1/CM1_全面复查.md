# CM1 全面复查（Architect，2026-10-29）

状态：**复查通过（CLOSED）**。用户要求重新全面 review CM1，确认无问题后开始 CM2；此前验收历史保留。

截至 2026-10-29，全部开放项 **F1、P1、P4、F2、F3、P2、P3、F1-R1** 已逐项由 Lead 初验并经 Architect 独立复核 **APPROVED**（各项独立批次、未合并），**F4 入口文档归位**完成，**整体回归通过**：`./scripts/check` exit 0（130 tasks，常规 **1188=1188**、源码 `@Test` 与 XML 差额全 0）、`vfs/`+`common`+`integration-tests/vfs` tracked diff 为空（81 基线 mismatch=0）、`./scripts/t31-consumer-check` exit 0。CM1 至此**复查通过**，放行 CM2。

## 审查分工与边界

- Architect 独立审查 API/common、配置、Runtime 生命周期、Ingestion、HTTP、验收证据及文档入口。
- 原 Paseo Lead `6f547b7e` 组织原 Worker `d7695b22` 对 CT04/CT05 事务、作用域、Repository/约束执行只读专项审计，结果须回传 Architect。
- 生产修复继续 Lead → Worker，逐项初验和独立复核；CM2 仅整理 CT08～CT12 方案，待开放问题关闭后实现。
- 原常规1142例成功、消费验证成功是历史证据；本次独立探针发现的边界不在原套件覆盖中。

## 实跑反例

探针均在 `.local/validation/cm1/architect/full-rereview/`，通过临时 Gradle init 添加测试源码；未修改生产代码或正式测试。

命令：`./scripts/dev gradle -I .local/validation/cm1/architect/full-rereview/probe.init.gradle :context:runtime:test :context:server:test --tests '*ProbeTest' --continue`。

实跑 exit 1：Runtime 76例（原75 + 探针1）中1失败，Server两项探针均失败。该次运行不是常规全量验证；不得把临时测试的XML与正式源码计数混比。

### F1：通知未退出，但关闭返回且跟踪清零（高）

- 位置：`AlcyoneContextImpl.cancelNotifications()`，`notificationJobs.clear()`。
- 可复现：notifier 已进入执行；取消后在 `finally { withContext(NonCancellable) { release.await() } }` 等待明确放行。`close()`返回时清理仍未退出，而计数为0。
- 实际输出：`CLOSED_WITH_LIVE_NOTIFICATION=true; TRACKED=0`。
- 影响：此前“close后无残留Job”测试依赖被直接清空的集合，不能证明真实完成。CM2将接线实际通知，不能继续基于此判据管理资源。
- 要求：停止准入后真实追踪取消/完成，不用清空集合伪造退出；明确关闭完成与通知收尾关系，并处理通知内部重入close时的自等待，避免简单join造成死锁。Lead先提交具体设计，Worker实现。

### F2：非法UTF-8正文被静默替换并接受（高）

- 位置：`IngestionHttpApi` 的 `body.toString(Charsets.UTF_8)`。
- 可复现：合法JSON的content字节中置入0xff；真实HTTP返回201。
- 实际输出：`MALFORMED_UTF8_STATUS=201`，期望400。
- 影响：接收的正文已经被解码器替换，破坏严格输入与原样保留契约。
- 要求：字节限额先于严格UTF-8解码；非法编码固定400/INVALID_TURN且无行，合法中文和合法U+FFFD不误拒。

### F3：直接注册入口静默覆盖重复Runtime（中）

- 位置：`IngestionClientRegistry.of`，`clients.associateBy { it.runtimeId }`。
- 可复现：同runtimeId配两个不同Key，不报错、后者覆盖前者；独立拒绝断言失败。
- 要求：在归并丢失信息前校验重复runtimeId；同时统一直接配置与YAML的空白字段、Map键/runtimeId一致性、重复Key规则，失败为具名配置错误且不回显Key。保持原集合快照保护。

### F4：当前文档入口仍停留在CT06（中）

- `docs/context/README.md`当前进度、`开发计划.md`状态段、`开发进度.md`末段及表格、AGENTS最新段与已完成7/7不一致。
- 要求：保留历史修改记录，当前入口统一到CM1历史验收完成/本次增量复查开放；记录用户条件授权CM2。全部修复通过后再更新CM2实际启动状态。

## 证据口径纠正

- Server确实通过`ServerConfigLoader`读取server YAML；“Server不读取YAML”只能限定为HTTP路由取得Context限额的路径。
- `IngestionLimitAware`是Runtime模块的public辅助接口，非api模块冻结接口；不能把“非api模块”说成“Kotlin internal”。
- `t31-consumer-check`证明既有VFS消费兼容，不代表独立Context产物消费已验证；当前Context发布缺口依既有CT01阶段边界记录。
- Core无基础设施实现依赖、模型集合防御性复制等已有边界仍成立；不因本次发现推翻无关已通过行为。

## 修复裁定与 CM2 方案约束（2026-10-29）

以下为设计裁定，尚非修复验收。继续复用原 Lead/Worker，逐项初验后提交 Architect，禁止以最终总测代替逐项复核。

1. **F1 立即修复**：关闭停止通知准入、取消在途通知，并等待其真实完成（包括 finally 和结构化子协程），不得通过清空集合证明完成。可直接等待 Job 完成；若使用完成信号，必须由 invokeOnCompletion 完成。通知内对同一 Context 调用同步/异步 close 均具名拒绝，且须在加入共享关闭等待之前检查。标记使用携带实例身份的协程上下文传播机制，不用普通 ThreadLocal 跨 suspend 设置/清除；覆盖 yield、切换 dispatcher、外部关闭已发起以及不同 Context 不误拒。共享清理不可被等待者取消，既有取消/失败传播契约保持。门控测试必须证明 gate 未放行时 close 未返回，释放后 finally 真正退出且 CLOSED。
2. **P1 确认所有权缺陷，先细化协议**：Slot 在物理 close 成功前不得丢失连接引用；逻辑停止接单、线程退出、资源释放成功必须分开表达。Executor 不得提前以 released 阻断失败资源的内部清理。Lead 先提交与唯一共享关闭结果兼容的有限重试设计：同一清理轮次内重试失败资源，其他 Slot 均尽力清理；持续失败保留所有权与具名失败，禁止无限重试和伪报已释放。不得令后续 public close 改写既有共享终态结果。测试须注入首次 close 失败后成功及持续失败，直接断言物理资源、其他 Slot、中断与共享失败结果。设计确认后再实现。
3. **P2 批准边界校验**：各分页入口 limit >= 0、offset >= 0；零 limit 合法并返回空，负数在执行 SQL 前拒绝。同步 Core KDoc 和真实数据库测试，不加任意业务最大页长。
4. **P3 不按无条件缺陷处理**：实际冻结契约 OrganizedMemoryTxOps 明确由调用方在同一写事务读取全局 maxUpdatedAt 并分配严格更大的毫秒值。传回退值已违反前置条件，单加目标行比较也不能证明全局单调。当前保留签名及职责，不擅自改成新 CAS；补真实事务内多行/来源 MERGE 的全局分配回归，文档明确底层不替调用方分配或验证全局水位。CM3 业务实现必须承担并验证此不变量；若正确使用契约仍可回退，重新列为阻断缺陷。
5. **P4 增补针对性回归**：普通 updateRaw 语句失败后健康连接仍可执行 write；与事务终结失败必须 poison 的既有测试明确区分。
6. **F2/F3 按反例修复，F4 同步文档**：先保留反例证据，再安排正式回归；没有修复验收之前当前状态保持复查 IN_REVIEW。

### CM2 Q1～Q8

- Q1 同意首次消费并严格校验 context.llm；未配置时保持 CM1 ingestion-only 行为。
- Q2 复用已有 Core LlmClient/LlmRequest/LlmResponse/TokenEstimator，禁止另建重叠 ExtractionLlm；Ktor 实现在 Runtime。
- Q3 同意保守估算与可配置余量，区分估算和真实 Usage，不宣称模型 tokenizer 精确一致。
- Q4 同意 Runtime 管理调度生命周期，Core 保持业务边界；关闭须等待真实在途退出，与通知作用域分开。
- Q5 保持通知签名，Count 按同一 Session 的 pending 数量判阈值；不把 pending 数量等同于 Scheduler trigger counter。
- Q6 纠正为 Cron 默认 Asia/Shanghai，保留任务显式时区覆盖；跳过重叠扫描，不擅自吞业务触发计数。
- Q7 不接受用 Fake 替代 CT12/G9 实际模型评测。使用获授权 Profile 与固定脱敏样本，分别记录 Prompt/Profile/样本版本和实际结果；缺配置或授权时如实保留评测未完成，不能宣称 CM2 全体验收通过。
- Q8 F1 优先实施，Lead 同时细化 P1 设计；随后逐批处理 P1/P4、F2、F3、P2/P3，F4 随进度更新。各批独立复核通过后做整体回归，再放行 CT08。用户已经条件授权 CM2，无需重复请求开工授权。

## F1 首轮修复独立复核（2026-10-29）：未通过

- 两项生产 SHA 与 Lead 交付一致：NotificationExecutionMarker `28fd3d78…`、AlcyoneContextImpl `746b3d75…`。实际阅读实现及新增两个测试文件；ThreadContextElement 的实例身份传播、两关闭入口在共享等待前拒绝自身通知重入，以及取消后 join 真实 Job 的方向正确。
- Architect 独立运行 `./scripts/dev gradle :context:runtime:test :context:runtime:spotlessCheck --rerun-tasks` 成功（29 tasks）。当次正式 XML 84/84，失败/错误/跳过均零；源码 @Test 84，差额零。
- **阻断 F1-R1：关闭排空通知期间仍接受请求。** shutdown 先调用挂起 cancelNotifications，之后才设置 STOPPING。独立探针 NotificationAdmissionProbeTest 等到通知因关闭取消进入 finally（明确屏障），将 finally 阻塞后发起新的 SDK 调用：实际 `DRAIN_STATE=RUNNING; INGEST_ACCEPTED=true; RESULT=DUPLICATE`。探针期望 STOPPING，实际失败；即使是重复请求，关闭已开始后仍接受并查询数据库也违反准入契约。
- 要求在唯一关闭协议开始时原子关闭业务准入（RUNNING/STARTING 转 STOPPING），然后才取消并等待通知；已准入工作仍按既有协议真实排空，不能抢关。处理 CREATED/start/close 竞争与失败共享，勿仅移动一个状态赋值而丢失待释放资源。修复后覆盖新业务键拒绝/无新行、重复请求拒绝、getStatus STOPPING、HTTP 503，以及 gate 放行后真实退出/CLOSED。
- **测试判据须补强**：门控用例现在仅 start 线程后等待 200/300ms，并未先证明关闭线程已到取消/等待点；增加明确 finally-entered/取消屏障。退出不污染测试在测试主线程读 ThreadLocal，而通知运行在 IO，不能证明同一执行线程的 restore；使用受控单线程 dispatcher 在标记上下文退出后复用该线程断言，并关闭测试 dispatcher。补结构化子协程 finally 阻塞时关闭仍等待真实完成的断言。
- 同步修正 shutdown 注释“通知不参与关闭完成判据”，观察窗起点须涵盖通知等待，避免长时间 drain 被排除。
- 独立探针命令：`./scripts/dev gradle -I .local/validation/cm1/architect/full-rereview/probe.init.gradle :context:runtime:test --tests '*NotificationAdmissionProbeTest'`，exit 1，1例失败。探针运行后的当前 XML 仅此1例，不能与正式84例源码混算；前述84例为此前独立正式运行结果。探针位于 `.local/validation/cm1/architect/full-rereview/runtime/NotificationAdmissionProbeTest.kt`。
- F1 保持 IN_REVIEW，交原 Lead → Worker 定点修复。P1 已批准设计但实现仍待 F1 通过；CM2 不启动。

## F1-R1 修复后独立收口（2026-10-29）：APPROVED

- 生产 SHA：`AlcyoneContextImpl.kt=3dc754a5444195dce90d56c6e3d897553a2fd0f570168df150d08585a413f1be`；`NotificationExecutionMarker.kt=28fd3d78f648303f7484080f8b0de49e0e8382262bd2b89340b40edd28855422`。
- 实际审查：`shutdown()` 在同一 `lock` 内将 RUNNING/STARTING 转 STOPPING（CREATED 直接 CLOSED），并捕获/清空业务资源引用；随后才 `cancelNotifications()` 取消并逐个 `join()` 真实 Job。业务准入封闭先于通知等待，符合 F1-R1 要求。close 与 start 竞态、逆序释放、共享失败语义保持。
- 新增 `NotificationDrainBarrierTest` 使用显式 finally/cancelled 屏障，验证 drain 期间状态为 STOPPING、同键/新键 SDK ingest 均 CLOSED、库内无新行、close 不提前返回、结构化子协程 finally 真退出后 close 完成。
- Architect 独立实跑 `:context:runtime:test :context:runtime:spotlessCheck --rerun-tasks` exit 0；XML 87/87，失败/错误/跳过均零；源码 @Test 87，差额零。
- Architect 独立复跑 `NotificationAdmissionProbeTest`（临时 init 注入）exit 0，实际 `DRAIN_STATE=STOPPING; INGEST_ACCEPTED=false; RESULT=null`，首轮反例关闭。该探针 XML 与正式 87 例分属两次运行，不混算。
- Marker 不残留测试已使用受控单线程 dispatcher 复用同一实际线程并关闭 dispatcher；门控测试已等待 draining 屏障，不再依赖 sleep/固定时序猜测。通知重入拒绝仍在共享等待之前。

**结论：F1 APPROVED，可实施 P1。** 仍需后续 P1/P4、F2、F3、P2/P3、F4 逐批闭环后做整体回归；CM2 实现继续等待开放项全部关闭。

## P1 修复后独立复核（2026-10-29）：APPROVED

- 生产 SHA：`ContextSlot.kt=849e881115c1ec268271cf2131bc5350e924a5440b216339eb54d2d8b7c7d1e2`；`ContextDatabaseExecutor.kt=1890d74df3e63a90cab372f89ae67e71b78e4f13ebdde059fb347cd4de078005`。
- 实际审查：`ContextSlot.close()` 只把 `disposed` 用作停止接单，每次尝试均经该入口执行 `releaseAttempt()`；线程退出判定、物理连接关闭、失败聚合和中断恢复分离处理。物理连接仅在 `close()` 成功后清空并 dispose 驱动；无连接时线程清理失败仍被抛出。中断事实在 `finally` 恢复。
- `ContextDatabaseExecutor.releaseAll()` 在同一轮次内最多两次尝试，仅重试失败 Slot；`released` 仅全部成功后置位。`IdentityHashMap` 保留每个 Slot 首次失败，后续失败按实例去重后追加 suppressed，聚合跨 Slot 失败时也避免自我 suppressed。后续 close/closeAsync 等待唯一 CloseProtocol，失败后不再开启新轮次，不改写共享失败。
- 测试审查：`ReleaseOwnershipRetryTest` T1～T8 覆盖首失败重试成功、持续失败保留真实连接、其它 Slot 尽力关闭、中断恢复、失败后不再尝试、中毒连接全局重试、无连接线程失败不吞、首次失败保留且恰好两次。物理断言均发生在测试兜底清理之前。
- Architect 独立实跑 `:context:persistence-sqldelight:test :context:core:test :context:runtime:test :context:server:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks` exit 0。XML：persistence 163、core 11、runtime 87、server 19，均失败/错误/跳过零；源码 @Test 与 XML 差额均零。
- T6 首轮为持续失败分支；结合既有 `releasePoisonedConnection` 语义与代码审查确认失败保留所有权、全局 close 尝试。若后续增加“首次失败后全局重试成功”的 poisoned 分支，可作为增强而非本轮阻断。

**结论：P1 APPROVED，可继续 P4。** P4 仍需独立提交初验与 Architect 复核；随后继续 F2、F3、P2/P3、F4。CM2 实现继续等待开放项全部关闭。

## P4 独立复核（2026-10-29）：APPROVED

- 无生产改动。`StatementFailureVsTransactionFailureTest.kt=2152a046ff885d2fb50ee2fefa2e81359fdf521f59b08db6d9b68b2f170caf34`；P1 两项生产 SHA 保持 `1890d74d…` / `849e8811…`。
- 实际审查：`updateRaw/queryRaw` 仅经安全错误映射并释放请求作用域，不调用 poison；事务终结失败仍按既有路径 poison。新增测试真实覆盖 CHECK 约束失败后同一执行器继续 write、queryRaw 失败后读路径继续可用、commit 注入失败后 Slot poison 且后续 write 被拒绝。
- 错误映射断言不回显 SQL 表名/语句或参数值；物理与行为断言均在测试兜底清理之前执行。
- Architect 独立实跑 `:context:persistence-sqldelight:test :context:core:test :context:runtime:test :context:server:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks` exit 0。XML：persistence 167、core 11、runtime 87、server 19，均失败/错误/跳过零；源码 @Test 与 XML 差额均零。

**结论：P4 APPROVED，可继续 F2。** F2 仍需独立提交初验与 Architect 复核；随后继续 F3、P2/P3、F4。CM2 实现继续等待开放项全部关闭。

## F2 修复后独立复核（2026-10-29）：APPROVED

- 生产 SHA：`IngestionHttpApi.kt=7952a207729defd0910fe1ee9b12ec6fd1868230b0488e1e5655531d12b81efc`；新增测试 `InvalidUtf8BodyTest.kt=e0c4fd7623ec5f23d2cec539085effed173a18633d65dc4280f418131c5ffd2c`。
- 实际审查：HTTP 顺序为 Content-Length 预检、流式 `readBodyUpTo` 限额、`CharsetDecoder` 且 malformed/unmappable 均 REPORT 的严格 UTF-8 解码、最后 JSON 反序列化。非法或截断 UTF-8 返回固定 400 `INVALID_TURN`，不再使用会静默替换的字符串解码。
- 字节级测试覆盖 `0xFF`、孤立 `0x80`、截断三字节、孤立代理编码、超限先 413、自定义 512 字节限额内的 400/超限 413、合法中文/emoji/真实 `U+FFFD` 不误拒，以及零落库与响应脱敏。
- Architect 独立实跑 `:context:server:test :context:core:test :context:runtime:test :context:persistence-sqldelight:test :context:server:spotlessCheck --rerun-tasks` exit 0。XML：server 24、core 11、runtime 87、persistence 167，均失败/错误/跳过零；源码 @Test 与 XML 差额均零。

**结论：F2 APPROVED，可继续 F3。** F3 须统一直接构造、YAML Loader 与 Registry 三入口校验，禁止重复 runtimeId 静默覆盖，并保持配置快照与 Key 脱敏。CM2 实现继续等待开放项全部关闭。

## F3 修复后独立复核（2026-10-29）：APPROVED

- SHA：`IngestionClientValidation.kt=3b5babc205c5da31c3f39b2e583e79601be4627d835455fef6110651997fc707`；`ServerConfig.kt=3886bf53cb6e3be4a3b016b01e6474a6cb7e1f3184f40d492114c9ffc6a2972b`；`ServerConfigLoader.kt=0448b95dfd5fc43d9babd612e76208281c835cf52f56725e4d81b9181873ca4b`；`ServerClientValidationTest.kt=a0454b1e28ffa6ebd39d0e52dc71533d31291d642b6c1f35e1e75bd711143b5c`。
- 实际审查：`ServerConfig` 直接构造、YAML Loader、`IngestionClientRegistry.from/of` 共用 `IngestionClientValidation`。规则覆盖 Map key 与 runtimeId 一致、runtimeId/runtimeType/apiKey 非空白、重复 runtimeId、同 apiKey 绑不同 Runtime。`of` 先做 Collection 快照并校验，再构造 Map；不再先 `associateBy` 丢失重复信息。
- `ServerConfig` 在校验后复制 `LinkedHashMap` 并暴露只读视图；Registry 再从快照构建 Key 表。调用者修改原 Map 不影响认证。错误消息只含字段路径/runtimeId，不回显 apiKey；`toString` 也脱敏。
- 新增测试覆盖三入口重复 runtimeId、同 Key 不同 Runtime、Map key 不一致、空白字段、调用者修改原 Map 后认证不受影响、合法多 Runtime 认证。
- Architect 独立实跑 `:context:server:test :context:core:test :context:runtime:test :context:persistence-sqldelight:test :context:server:spotlessCheck --rerun-tasks` exit 0。XML：server 30、core 11、runtime 87、persistence 167，均失败/错误/跳过零；源码 @Test 与 XML 差额均零。
- 原 Architect `ServerBoundaryProbeTest` 中重复 runtimeId 探针已由 Lead 复跑转绿；该探针为临时注入，不与正式 XML 混算。

**结论：F3 APPROVED，可继续 P2/P3。** P2 需在 SQL 执行前校验所有分页入口的 limit/offset 非负并保留零 limit 空结果；P3 按调用方全局水位契约补分配回归与契约说明。CM2 实现继续等待开放项全部关闭。

## A 批次：F1 通知快照竞态复核（2026-10-29）：APPROVED

- SHA：`AlcyoneContextImpl.kt=a88ba5614aa0ed3a577f5a716c86fd024cf181ab69fbb0ef1b7e95429b4d96e0`；`NotificationSnapshotRaceTest.kt=9f010a0f05dd50db7b2d1c34aff4af36d8d5e07df83e80bd9ede3be9a44b06fa`。
- 实际审查：`cancelNotifications()` 在锁内快照 scope/Job；快照不再使用 `Iterable.toList()`，改为显式遍历 `ConcurrentHashMap.newKeySet()` 弱一致迭代器并构建 ArrayList，避免 `size==1` 时与 `invokeOnCompletion` 并发移除产生 `NoSuchElementException`。锁内封闭准入、锁外取消、脱敏取消上报、逐个 join 真实退出的语义未变。
- 新增测试真实使用 Job 完成回调移除：32 个通知与 close 并发移除、20 轮先/后放行交错，不使用反射。
- Architect 独立实跑 runtime/server/persistence/core 测试及 runtime/server/persistence 格式检查 exit 0；XML 89/30/176/11，均零失败/错误/跳过，源码差额均零。随后独立重复运行 runtime/server 全量 5 次，全部 exit 0；XML 中仅出现测试用例名称文本，无失败记录或异常堆栈。

**A 结论：APPROVED。**

## B 批次：P2/P3 复核（2026-10-29）：P2 APPROVED、P3 APPROVED

- SHA：`ContextOps.kt=04c20e6857c6be51d671cfadfc0486409c419d1b37414ee9adee30e6de41f9e3`；`PaginationArgumentValidationTest.kt=2b385d01d3163b2b84627d4688258a272111aa6935c96741a4d9d8dd71a20de4`；`WatermarkContractTest.kt=cbcd00732018f623f5f8b16775c84367bd7fc7622039371851a250ce48747467`。
- P2 实际审查：`sessionWindow`、`idRange`、Raw `scanPage`、Organized `scanPage` 四个入口均在构造/执行 SQL 前调用 `requirePagination`。负 limit/offset 抛 `IllegalArgumentException`，消息含字段与非法值，不回显 SQL；limit=0/offset=0 合法，limit=0 返回空页。该错误语义与本模块既有参数校验一致，不伪装为 `PERSISTENCE_UNAVAILABLE`，未新增公共枚举。
- P2 测试覆盖四入口负值、零值、正常分页、失败后 autoCommit 恢复、后续 write 成功和数据库不受影响。
- P3 实际审查：未新增条件更新/CAS，未改 SQL 与签名；Core KDoc 已明确同一写事务内读取全局 max、分配严格更大值、验证影响行数的调用方职责。真实 SQLite 回归覆盖同一事务多行严格递增、MERGE 更新与来源关联、目标行推进、缺失行返回 0、双库隔离，不把契约违规写成已防护行为。
- Architect 独立实跑：runtime 89、server 30、persistence 176、core 11，均零失败/错误/跳过，源码差额均零；三个模块 Spotless 均通过。

**B 结论：P2 APPROVED、P3 APPROVED。**

## Architect 最终独立收口（2026-10-29）

Lead 完成 F4 与整体初验后，Architect 未直接采信下级报告，另行执行最终复核：

- 独立实跑 `./scripts/check` exit 0：130/130 tasks executed，含编译、全部常规测试与 Spotless。
- XML 汇总：common 23、context/api 26、context/core 11、context/persistence 176、context/runtime 89、context/server 30、integration-tests/context 15、integration-tests/vfs 107、vfs/api 66、vfs/core 305、vfs/git-backup 73、vfs/persistence 52、vfs/runtime 61、vfs/storage 154，合计 **1188**，失败/错误/跳过均为 0。
- 精确按行首 `@Test`（排除 `@TestMethodOrder`）核对源码：integration-tests/context 15、vfs/core 305；14 个模块源码计数与 XML 差额均为 0。
- `git diff --stat -- vfs common integration-tests/vfs` 为空；81 项 VFS SHA 基线 mismatch=0。
- 独立实跑 `./scripts/t31-consumer-check` exit 0：本地发布与独立 JVM 消费两阶段创建/重开断言通过。该验证证明既有 VFS SDK 消费，不代表 Context 产物已公开发布。

**最终结论：CM1 全面复查 APPROVED / CLOSED；按用户条件授权放行 CM2。** CT08 保持 IN_PROGRESS，先方案后实现；CT09～CT12 未启动，未 git add/commit、未远端推送、未公开发布。

## 修复收口记录（2026-10-29）

| 项 | 结论 | 关键证据 |
| --- | --- | --- |
| F1 通知关闭 | APPROVED | 真实 `join` 等待、重入具名拒绝、`shutdown()` 同锁封闭准入→STOPPING→取消→等待→逆序清理；runtime 89 例 |
| P1 连接所有权与有限重试 | APPROVED | `releaseAttempt()` 无 `physicalConnection ?: return` 早退；三语义分离；`MAX_RELEASE_ATTEMPTS=2` 仅重试失败 Slot；首次失败保留、无自我 suppressed；后续 `close/closeAsync` 同一失败实例 |
| P4 单语句失败语义 | APPROVED | `updateRaw`/`queryRaw` 失败不 poison 且连接可继续写；commit 失败仍 poison 且后续写被拒；错误不回显 SQL/参数（无生产改动，仅补回归） |
| F2 非法 UTF-8 | APPROVED | 限额 → 严格 `CharsetDecoder(REPORT)` → JSON；非法字节固定 400 且零落库；合法中文/emoji/真实 U+FFFD 不误拒；超限仍 413 |
| F3 注册校验 | APPROVED | 三入口共用 `IngestionClientValidation`；重复 runtimeId/同 Key 不同 Runtime/Map key 不一致/空白均具名拒绝；快照自有；不回显 Key；原失败探针转绿 |
| P2 分页参数 | APPROVED | 四入口 SQL 前 `require`；`IllegalArgumentException`（不新增 API 枚举）；`limit=0` 空页；失败后 `autoCommit` 恢复 |
| P3 水位契约 | APPROVED | 未新增 CAS、SQL/签名未动；KDoc 明确调用方同事务内 `max → next > max → advance`，回退值属契约违规；多行与 MERGE 真实回归 |
| F1-R1 快照竞态 | APPROVED | Architect 实跑发现的 `notificationJobs.toList()` `size==1` 竞态；改显式弱一致迭代快照；连续 5 次重复跑无复现 |

剩余：F4 文档入口（本次已修订 `README`、`开发计划`、`开发进度`、`AGENTS.md`、`CM1_任务与验收`）与整体回归。

## 整体回归（2026-10-29，Architect 独立执行）

| 项 | 结果 |
| --- | --- |
| `./scripts/check` | **exit 0**，130 actionable tasks |
| 常规测试合计 | **1188 = 1188**（原 1142 + 复查新增 46） |
| 源码 `@Test` 与 XML 差额 | 14 个模块/套件**全部为 0**（含 `vfs/git-backup` 73） |
| 模块计数 | common 23、vfs/api 66、vfs/core 305、vfs/storage 154、vfs/persistence 52、vfs/runtime 61、vfs/git-backup 73、context/api 26、context/core 11、context/persistence-sqldelight 176、context/runtime 89、context/server 30、integration-tests/vfs 107、integration-tests/context 15 |
| VFS 零侵入 | `git status --porcelain vfs common integration-tests/vfs` 仅 4 个 CT03 用户授权的 common 新文件，**VFS 生产/测试 tracked diff 为空** |
| `./scripts/t31-consumer-check` | **exit 0**（`=== T31 SDK packaging and consumer validation SUCCESS ===`） |
| 日志 | `.local/validation/cm1/architect/final-regression/{check.log,t31-consumer-check.log}` |

说明：静态计数曾把注解前缀 `@TestMethodOrder` 误配为 `@Test` 而出现 vfs/core 306 vs 305 的假差额；按 `@Test([^A-Za-z]|$)` 精确统计后差额为 0。

## F4 入口文档归位（2026-10-29）

已更新并保留历史记录：`docs/context/README.md`、`docs/context/开发计划.md`、`docs/context/开发进度.md`（含状态表与计数行）、根 `AGENTS.md`、`docs/context/milestones/cm1/CM1_任务与验收.md`；本轮把「CM1 历史验收通过 + 复查收尾中」推进到「复查通过」，并在整体回归后放行 CM2。
