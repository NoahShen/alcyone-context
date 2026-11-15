# CT28 B2a R2 补测委派单

## 本次修改（2026-11-11，Scheduler 状态名裁定已更正）

- Architect 确认 IDLE 为裁定状态名错误，已更正稳定性裁定：仅将旧测试稳定等待改为 notifications drained + queueDepth==0 + state==RUNNING。
- 执行快照1..5、全为sessionKey、再次tick不增加及其余断言不变；不得新增生产IDLE或改Scheduler。继续5次/整类/四模块复验。

## 历史修改（2026-11-11，Scheduler IDLE 诊断枚举需更正）

- 按裁定修改后 executions 非空与通知 drain 已通过，但新增 await `queueDepth==0 && state==IDLE` 在 test line180 超时；日志 `/tmp/ct28-b2a-r2/extraction-stable-1.log`。
- `ExtractionScheduler` 实际状态枚举仅 NEW/RUNNING/CLOSING/CLOSED，无 IDLE；start 后健康运行态是 RUNNING。裁定中的 IDLE 条件不可达，Worker 未修改生产或放宽断言。
- Lead 建议窄更正为 `queueDepth==0 && state==RUNNING`；notifications drained + queue0 + RUNNING 表示本批已接纳调度稳定，随后快照次数/Session/tick无副作用断言保持。不得新增生产 IDLE 状态。

## 历史修改（2026-11-11，Extraction 旧测试稳定性修复获批）

- Architect 批准仅修改 `ExtractionPipelineLifecycleTest.kt` 的 `disabling idle...` 方法，完整口径见 [稳定性裁定](B2a_R2_Extraction旧测试稳定性裁定.md)。生产及其他测试不改。
- 保留 idleCron null 与 ingest 前 tick 零执行；五次 ingest 后等 executions 非空、通知 drain、Scheduler queue=0/state=IDLE；快照断言 size in 1..5 且全部同一 session，再 tick 有界观察不增加。
- 不阻塞第二次、不用简单 >=1 即结束、不改 Count/Scheduler。单测连续至少5次含 no-daemon、整类和四模块复验；超过5次/异Session/不drain/不IDLE/tick新增均暂停报生产问题。
- 记录旧 SHA `4debb92db77afbe1bc2c0f6eeab0e5e0487f6cd559d1b30dfe190470ce9ebbff`、新 SHA 和断言行号。

## 历史修改（2026-11-11，Extraction 持续失败根因定位）

- Worker 窄修订定向通过，但 `ExtractionPipelineLifecycleTest > disabling idle...` 连续5次/no-daemon超时。Lead 独立复现并用临时探针定位，诊断文件及既有测试临时修改均已恢复，未进入交付。
- 真实原因不是 Runner 零执行：pending 从2到6，第4次通知 pending=5 已触发 Runner，第5次 pending=6 又触发一次；原断言 `awaitUntil { runner.executions.size == 1 }` 在两次快速执行后永远为 false，10s超时。
- 原方法诊断 XML：`context/runtime/build/test-results/test/TEST-...ExtractionPipelineLifecycleTest.xml`（后续运行可能覆盖）；持久日志 `/tmp/ct28-b2a-r2/lead-original-diag.log`，输出显示 `runs=[key,key]`、Scheduler RUNNING、queue depth 0、通知最终 drain。探针相同业务场景通过且同样两次执行。
- 这是该既有测试已知的“恰好一次瞬时窗口”时序敏感性；当前 VFS/Admin 改动未触及 Count/Scheduler，不能记为零执行生产回归，也不能用延长等待修复。
- 建议仅在该既有测试增加确定性的 Runner 一次性 gate：记录 executions，第二次执行阻塞/受控取消，或更精确地以第一次完成后立即封闭 count 通知并验证 idle disabled；具体改法交 Architect 裁定。不得改 CountTrigger/Scheduler 生产语义，不得简单改 `>=1` 掩盖重复调度。
- R2三文件/Factory/Impl当前交付保持，Extraction测试 SHA 已恢复 `4debb92db77afbe1bc2c0f6eeab0e5e0487f6cd559d1b30dfe190470ce9ebbff`。

## 历史修改（2026-11-11，VFS 接缝窄修订获批并续派）

- Architect 不批准 delegate 解包/双诊断方案，批准更小修订，见 [VFS接缝窄修订](B2a_R2_VFS接缝窄修订.md)。
- Factory 删除 ManagedVfs/default wrapper，internal 工厂改 `suspend (VfsRuntimeConfig) -> Vfs = AlcyoneVfs::create`；公共 create 不变。Impl 业务字段/局部/Map/既有 vfsForTesting 恢复 `AlcyoneVfs?`，另增 internal `managedVfsForTesting(): Vfs?` 供清理/关闭检查。
- start 工厂结果先 Vfs 临时值；生产默认赋 AlcyoneVfs。custom VFS 业务可使用，但不可关闭实现必须 `ContextConfigException(INVALID_CONFIG)` 且走既有失败清理，不静默泄漏。
- releaseResources 业务参数保留 AlcyoneVfs?，另收同一实例 managedVfs:Vfs? 仅 close；ReleaseProbe(VFS)后、executor前分派真实 close 或 AutoCloseable.close，非法实现明确配置异常。
- ReadyTest Counting VFS 实现 AutoCloseable 并幂等委托真实 close；无生产反射/wrapper delegate/公共API/既有Resource测试修改。已改的其他既有测试必须恢复。
- 重跑失败Resource、Ownership/Lifecycle/CloseSharing/VfsIntegration、R2三项，再四模块/Spotless；Lead继续独立全量复验。

## 历史修改（2026-11-11，ManagedVfs 既有反射回归提交 Architect）

- Admin 6/6、Ready isolation、R2-3 两项定向通过；四模块 823 中仅既有 `AlcyoneContextResourceIntegrationTest > Context close reclaims an already opened Resource stream` 失败。
- 原因：既有测试反射 `vfsForTesting()` 返回对象的 `getTrackedStreamCount`；默认 ManagedVfs 匿名委托不再暴露真实 AlcyoneVfs 方法。日志 `/tmp/ct28-b2a-r2/four-modules.log`。Worker 未改既有测试。
- Lead 建议不增加 ManagedVfs.delegate/Factory 解冻，也不让生产反射：恢复 Impl `vfsForTesting(): AlcyoneVfs?`，仅返回 default wrapper 的真实 delegate；新增独立 `managedVfsForTesting(): ManagedVfs?` 给关闭释放 null 检查。
- ReadyTest 已通过 CountingManagedVfs.Factory.current 持有计数包装器，不依赖 vfsForTesting；注入自定义 wrapper 时旧 vfsForTesting 可返回 null（仅 internal 兼容诊断），Factory.current 提供真实 VFS 证据。
- 此方案不改生产业务/关闭顺序，不修改既有 Resource 测试，但需 Architect 窄修订原“vfsForTesting 统一改 ManagedVfs”条款后执行。

## 历史修改（2026-11-11，关闭 Admin 最小修复获批准并续派）

- Architect 确认真实缺陷并批准仅继续修改既有 `AlcyoneContextImpl.kt`，完整边界见 [关闭Admin修复裁定](B2a_R2_关闭Admin修复裁定.md)；Factory ManagedVfs 已完成，无需再改。
- capability：Extraction=注入 runner 或 llm 满足 `hasLegalExtractionBinding`；Organization/Map=config 非 null；Dreaming=llm 非 null 且（cron 非 null 或三个专属 Task 任一），抽 Impl 私有纯函数供 Admin capability 与 defaultDreamingRuntime 共用。
- RUNNING 读真实 worker；STOPPING/CLOSED 已配置槽位 STOPPED、未配置 null；CREATED/STARTING 已配置 IDLE。workers 容器非 null，不保留已关闭 Pipeline/LLM。
- 关闭后 pending/checkpoint/mapStatus/llm 均 null；Backup 沿用已发布 reader，保留真实 STOPPED/lastUri/lastCompletedAt/lastFailureStage，不重建 Runtime。
- 原 AdminTest 补 Dreaming/Map 关闭 STOPPED、未配置 null、无合法 Extraction、MEMORY_SEARCH-only 无 Cron 不报 Dreaming，以及关闭后其他字段/Backup/无资源引用边界。R2-3 恢复，完成全部测试后 Lead 独立复验。

## 历史修改（2026-11-11，真实关闭 Admin 缺陷提交 Architect）

- Worker 补测证明运行态真实 Extraction/Organization Admin=IDLE，但 close 后 `getAdminStatus().workers == null`，无法表达已配置槽位 STOPPED；失败日志 `/tmp/ct28-b2a-r2/r2-2-workers.log`，未放宽断言。
- 根因：shutdown 锁内清空 pipeline/Dreaming/LLM 等引用，Admin 仅以这些引用判断能力存在。既有 `LlmRuntimeLifecycleTest` 等要求关闭后不再持有 client，不能为诊断保留已关闭资源引用。
- Lead 建议最小修复仅在 `AlcyoneContextImpl.getAdminStatus()`：用不可变配置 `extractionConfig/organizationConfig/memoryMapConfig/dreamingConfig` 决定槽位存在；STOPPING/CLOSED 由固定 workerState 输出 STOPPED，RUNNING 仍读实际 Runtime。DB 已释放时 persisted/map 内容为 null，不重新访问资源。
- Extraction 配置需区分实际合法 binding 与仅配置但不建 pipeline 的情况；可沿用 Factory 的合法 binding 判定或运行/关闭时记录不可变 capability 布尔值，不能将 PROMPT_ONLY/缺 Task 误报已配置 worker。
- ManagedVfs 与 R2-1 两项定向已通过；R2-3 暂停，等待 Architect 精确裁定。不自行扩大生产范围。

## 历史修改（2026-11-11，ManagedVfs 接缝获批准并续派）

- Architect 已批准最小 internal `ManagedVfs` 接缝，精确边界见 [VFS测试接缝裁定](B2a_R2_VFS测试接缝裁定.md)。原建议裸 `Vfs` 字段不获批准，因为公共接口没有 close。
- Factory 定义 `internal interface ManagedVfs : Vfs { suspend fun close() }`，私有默认工厂委托真实 AlcyoneVfs；existing internal create 末尾追加 `vfsFactory: suspend (VfsRuntimeConfig) -> ManagedVfs = ::defaultVfs`，private build 透传。公共 create 签名不变。
- Impl 仅将具体 VFS 持有/局部/清理/Map 参数及 `vfsForTesting()` 统一改为 `ManagedVfs`，同一实例原位置创建、原顺序 close。不得改 Ready/Admin 逻辑、ReleaseProbe、NonCancellable、资源顺序或另开 VFS。
- 生产写集合只扩至 Factory/Impl，原三测试文件继续，计数包装器只能放 ReadyTest；其余生产/测试/Schema/B1 Server/构建/VFS/common 冻结。
- 测试必须覆盖 Vfs 所有业务入口（含默认 publishNew/safeReplace）计数；Map 启动稳定清零，仅 Ready 区间断言 VFS=0、LLM=0。
- Lead 除四模块/Spotless/XML 外，独立重跑 Lifecycle、CloseSharing、ResourceIntegration、VfsIntegration 与 Map/Backup wiring。仍需额外接缝或改变关闭顺序则停止回报。

## 历史修改（2026-11-11，VFS 计数接缝提交 Architect 裁定）

- Worker 开工前只读核对后暂停，未修改文件：现有 `vfsForTesting()` 只返回具体 `AlcyoneVfs`，无业务调用计数；Factory 测试重载无 VFS 注入，三测试文件无法替换/包装真实 Factory VFS。
- 核实 VFS `AssemblyProbe` 仅覆盖 state/notifier/storages 且对 Context Runtime 不可见，不能证明 `checkReady()` 的 VFS 业务调用为零。
- 建议最小接缝：仅在 `AlcyoneContextFactory.create` 的 existing internal 测试重载增加默认 `suspend (VfsRuntimeConfig) -> Vfs` 参数，默认 `AlcyoneVfs::create`；`AlcyoneContextImpl` 的 VFS 持有/局部变量改为公共 `Vfs` 接口，`vfsForTesting(): Vfs?` 只放宽测试返回类型。公共 `create` 不传该参数，生产路径仍真实 AlcyoneVfs.create。
- 测试包装只委托真实 VFS 并计数，等待 Map 启动生成稳定后清零，再断言 Ready 区间 VFS=0；不另造 Runtime、不伪造 SQLite Ready。该生产接缝扩展尚待 Architect 批准，Worker 保持暂停。

## 历史修改（2026-11-11，Architect R2 定点补测）

- Architect 独立四模块 32/32、source/XML 45/180/186/817、零失败/错误/跳过、26 SHA/Schema/B1 Server/依赖边界均通过；但正式验收覆盖有三项缺口，裁定 **B2a IN_REVIEW / R2**，见 [Architect复核](B2a_Architect复核.md)。
- 本单只补 `AlcyoneContextReadyTest.kt`、`AlcyoneContextAdminStatusTest.kt`、`BackupRuntimeTest.kt`；生产、Schema、B1 Server、构建、CT27 与其他测试保持 SHA。
- 若补测发现真实生产缺陷，立即停止并回 Lead 精确原因，不静默修改生产。B2b/c 不启动，无 Git 写操作。

**执行**：既有 Worker `c2816850-92fa-4bca-986b-8535ffa369d3`；Lead 独立初验后再交 Architect `9e48d029-098a-49da-8f67-20f8cf8c9e9a`。

## 1. R2-1 生命周期与真实受管 DB 失败

在原 `AlcyoneContextReadyTest.kt` 与 `AlcyoneContextAdminStatusTest.kt` 内：

1. 用现有 `StartProbe.afterResourcesAcquired` 的确定性闸门停住 STARTING；断言 `checkReady() == false`，并证明未准入 DB 查询（例如 `adminReadHookForTesting` 计数为 0），释放后正常启动。
2. 用现有 `ReleaseProbe.beforeRelease(EXECUTOR)` 闸门制造 STOPPING，断言 Ready=false 且未准入查询；释放并完成真实关闭。不能用 sleep/猜测生命周期。
3. 在 context.start 后，通过 `databaseForTesting()` 取得同一个真实受管 `ContextDatabaseImpl`，将其 executor 在 Runtime 外层**确定性关闭**，然后直接调用 Runtime 的 Ready/Admin；不要再调用 start，也不要在 `database.read` 前由 hook 抛 marker。
4. 真实 read 失败时 Ready=false；Admin 精确断言受控异常类型/错误 code（以实际 executor closed 契约为准），不是 `assertNotNull(exception)`，Pending 不可伪 0。
5. 保留已有查询后关闭 false 与外部 CE 原样传播；CE hook 仍仅用于取消契约，不冒充 DB 故障。

## 2. R2-2 真实 Worker 聚合与 Ready 隔离

在原 Admin/Ready 测试内：

- 通过 Factory/现有测试重载配置真实 Extraction pipeline（合法 EXTRACTION binding，`idleEnabled=false`）与 Organization pipeline（`immediateEnabled=false`、`periodicEnabled=false` 或既有无触发配置，合法 Scanner/Governance binding）。使用受控 HTTP stub 或无触发的受控 LLM，不要求真实模型。
- 从 Impl 既有 `pipelineForTesting()` / `organizationPipelineForTesting()` 断言两个真实 pipeline 非空；`getAdminStatus().workers.extraction/organization` 非空，状态与真实 scheduler 对应（通常 IDLE），不是只测子 Scheduler 单测。
- Context close 后再读 Admin，已配置的 Extraction/Organization/Dreaming/Map 槽位按实际配置输出 STOPPED；至少明确断言 Extraction/Organization 关闭聚合。
- Ready 在 Pending 非空、Map 非 CURRENT、LLM 历史失败或不可用配置下仍 true；在 Ready 前后用 `LlmHttpStub` 请求计数为 0 证明零 LLM 请求。
- VFS 业务/网络零调用必须计数：可复用 Impl `vfsForTesting()` 暴露的实际 Runtime/Operator/业务资源访问计数，或在允许测试文件内通过既有接缝设置可计数 VFS 探针；不得另造不经 Factory 的 Ready。Map 启动生成若发生 VFS 调用，应先等待生成稳定并清零计数，再只测 Ready 区间。

若现有接缝不足以证明 VFS 零业务调用，先报告 Lead，不修改生产或额外测试 helper 文件。

## 3. R2-3 真实 Post-publish 失败

只扩充 `BackupRuntimeTest.kt` 的 controlled `RecordingVfs`：

- `publishNew` 必须实际保存对象并记录 URI、成功返回；随后 `openStream` 或实际读回 InputStream 确定性失败。不得直接构造 `BackupStageFailure(POST_CHECK, ...)` 冒充流程。
- 对外精确断言 `ContextBackupException` 及实际批准的错误 code，`runtime.status().lastFailureStage == POST_CHECK`；已发布 URI 仍保留在 `vfs.published`，无删除/回滚声明。
- 用真实 `BackupConfig(tempDirectory=root)` 验证失败后 workspace 为空/清理完成；不能仍用空 cleanup stub。
- 另验证此阶段的外部 CE 原样传播（保留取消语义）且不会记录普通失败；若拆为不同受控失败实例，保持确定性。原有 stage mapping 手工构造测试可保留，但不能替代真实路径。

## 4. 自测与交付

1. `./scripts/dev gradle :context:runtime:spotlessApply`（或四模块 spotlessApply，不能改其他文件）。
2. 三个测试类定向运行，必要时重复生命周期用例；最终四模块：
   `./scripts/dev gradle :context:api:test :context:core:test :context:persistence-sqldelight:test :context:runtime:test --no-build-cache --rerun-tasks --console=plain`
3. 四模块 `spotlessCheck --no-build-cache`。
4. 给新 source/XML totals、零失败/错误/跳过、32 tasks、三项测试方法与行号、三文件新 SHA；生产/Schema/B1 Server/构建保护清单未变。
5. 如实报告任何既有 flaky 失败及独立重跑；全仓 check 与 HTTP 本批不做已验证声明。
