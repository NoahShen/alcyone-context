# CT22 B1 · Dreaming 调度与固定增量范围组件 Worker 交付

## 本次修改（2026-11-09，B1-R1）

R1 最小修复完成：`DreamingConfig` 直接 Kotlin 构造现在拒绝 `cron=null + 非默认 timezone`，并新增 orphan timezone 精确断言。禁止修改的 Loader、Coordinator、Cron、Factory/Impl、Schema/SQL、依赖/构建均未改；最终 `./scripts/check` 通过 131/131。CT22 仍未 DONE，CT23～CT26 LOCKED，真实模型 not-run，无 Git 写操作。

## 本次修改（2026-11-09，B1 初始交付）

新增本交付报告，记录 CT22 B1 的 15 文件白名单、DreamingResult/错误契约、context.dreaming 配置与严格 Loader、固定 C0/U 增量范围、跨页 streaming 预算分批、单 Worker Coordinator、可选 Cron、真实 SQLite 测试、三条验收命令、14 模块 **1916=1916**、CT20/CT18/Schema/81/common/VFS/Core 边界与残余风险。本文件不把 B1、CT22 或后续批次标记为 DONE。

**日期**：2026-11-09
**状态**：**提交 Lead 初验，CT22 未 DONE，CT23～CT26 LOCKED**
**依据**：[B1 委派单](B1_委派单.md)、[Architect 裁定](Architect_裁定.md)、[CT22 任务书](CT22_任务书.md)、[方案核对](方案核对.md)、[CM5 范围与依赖方案](../../milestones/cm5/CM5_范围与依赖方案.md)
**基线**：CM4 APPROVED / DONE；14 模块 **1885=1885**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；Git `f391192`。工作区既有 CM5 规划文档与 `docs/context/开发计划.md` Lead 修改保持不动。

## 1. 交付结论

严格完成 B1 委派单 §3 的 15 个文件：**14 个代码/测试文件 + 本交付报告**。

- 公共 API 新增六字段 `DreamingResult` 与独立 Dreaming 错误家族；未改 `AlcyoneContext`、`ContextExceptions.kt`。
- `ContextConfig.dreaming` 与 Loader 严格解析完成；缺省 manual-only，cron 可选，时区默认 `Asia/Shanghai`。
- Runtime 新增固定 C0/U 扫描、D16 主选跨页 streaming assembler、`TokenBatchPlanner` 动态预算、串行 processor seam。
- Coordinator 实现 manual/background 单 Worker、owned-only close、CE 原样传播和 partial failure 聚合。
- 可选 Cron 实现严格未来、不补跑、running skip、固定可取消退避和真实 join。
- B1 新增 **31** 个测试：API +3、Runtime Dreaming +26、既有 `ContextConfigLoaderTest` +2。
- 14 模块源码 `@Test` 与 XML 均为 **1916=1916**，fail/error/skip = **0/0/0**；相对 1885 基线新增 31，差额 0。
- R1 后 API/Runtime 定向测试、Spotless、全量 `./scripts/check` 均通过；14 模块测试任务全绿。
- 未接 Factory/Impl、未写 Checkpoint/Raw/Pending、未运行真实模型、无任何 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 API（3）

| 文件 | SHA-256 |
| --- | --- |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/DreamingResult.kt` | `7a945908d042f6cd091ff512042e62b2454335c52cf20cd8c071bcbc0a9b2e0c` |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/ContextDreamingException.kt` | `c3e23f202540eaba91c20e2635b03c883fa74fbcd30334589485d29586628a35` |
| `context/api/src/test/kotlin/com/github/noahshen/alcyone/context/memory/api/DreamingApiContractTest.kt` | `3687c5070ce95673ef9df315797bb7c154007dca2d973f4a3d19a327576b46c8` |

### 2.2 Runtime 生产（7）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingConfig.kt` | `c05aca1bcffe367a7e17abb2aaf5c3f3bf569ed57c4b42d6cc6ffc83c915ebaa` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingBatchProcessor.kt` | `433efc4d1db35725e602befa8b5205a9da9ca3a4ef0e7592ad73a7e80ce867c1` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingRunCoordinator.kt` | `98a704d0416dce6cbb0405d67d22be4023f17fb42ea9c70b4853c35759032468` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingPeriodicCron.kt` | `ef64d3579be2e14a4ab380f70e13c9c92cddf288578eb4d15b5be30efc62c311` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/ContextConfig.kt` | `27a949263205c69f7574d02be9a4ad0dd1ececa290735ed625509c8810d053fb` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/ContextConfigLoader.kt` | `710256e731ffbaafb883e918eaf829aa9167c063f920e9e2a9bc3fd2152e3a31` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingConfigTest.kt` | `6a40e20dcdde09355a0a03b8eb7c9282a1e6796a772e652f07cfd9e3fcd5a1fe` |

### 2.3 Runtime 测试（4）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingRunCoordinatorTest.kt` | `55f41eb0dd84160e42d6ab7143b359995ecd5d25a9e95b021a8750f5f6794a26` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingPeriodicCronTest.kt` | `2a68a56fe242818a863a14e4d728f97ceb6bf8032c473e3707a91b3259cb36a8` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingTestSupport.kt` | `3816cc8598d8905dcb25c526e31f0234368187eb33b0039d9dec5fced3daaea9` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/ContextConfigLoaderTest.kt` | `2ada46635d2f6466d0f471757f8f9e55228e84a2adbba608169ccff8c580ced4` |

### 2.4 交付报告（1）

| 文件 | SHA-256 |
| --- | --- |
| `docs/context/tasks/cm5-ct22/B1_Worker交付.md` | 本文件自身不内嵌自引用 SHA；由外部最终计算 |

白名单外 B1 代码/测试/生产/构建变更：**0**。`docs/context/milestones/cm5/`、`docs/context/tasks/cm5-ct22/` 规划文档与 `docs/context/开发计划.md` 为 Lead 既有修改，未被回滚或改写。

## 3. 实现说明

### 3.1 DreamingResult / Exception

- `DreamingResult` 六字段、不可变；构造期拒绝负 checkpoint/计数及 `start > end`。
- 正常与无工作 run 返回 `completed=true`；partial failure 只通过 `DreamingRunException.partialResult` 表达。
- `ContextDreamingException(ALREADY_RUNNING)` 无 cause；`DreamingRunException(stage, partialResult, message, cause?)` 保留 DB/LLM/processor 原 cause，并拒绝 `completed=true` 的 partial result。
- `DreamingRunStage` 覆盖 `FIXED_RANGE/PAGE_READ/BUDGET/BATCH_PROCESSING`；CE 不包装。

### 3.2 Config / Loader

- `DreamingConfig(cron: String? = null, timezone: String = "Asia/Shanghai")`；`cron=null` 为 manual-only，但仅允许默认 `Asia/Shanghai`。
- `DreamingConfig(cron = null, timezone != Asia/Shanghai)` 在对象构造期抛 `ContextConfigException(INVALID_CONFIG)`；消息只含字段路径，不挂 cause、不回显原值。
- cron 使用 UNIX 五字段 CronParser；timezone 使用 `ZoneId.of`，拒绝空值、offset-only 和非规范 IANA 文本。
- `ContextConfig.dreaming` 为带默认值的新字段，旧 Kotlin 构造保持兼容。
- Loader 注册 `context.dreaming`，仅接受 flat `cron/timezone`；拒绝 unknown、错误类型、非法 cron/timezone，以及无 cron 的显式 timezone。
- Loader 不校验 Dreaming LLM Task、不创建 Cron/Service、不产生资源副作用。

### 3.3 Fixed Range / Streaming Assembler

- 每轮一次 `database.read` 在同一 ReadContext 读取 checkpoint C0 与 `maxTurnId()` U。
- 校验 `C0 >= 0`、`C0 <= U`；U=null 或 U==C0 返回 start=end=C0、计数 0、completed=true。
- U 固定后只按 `idRange(lastReadId, U, pageSize)` 扫描 `(C0,U]`；默认页大小 256，页间使用上一页末 ID，不依赖行数猜测。
- pending/complete/failed 全部参与；ID 空洞合法；不按 timestamp/status 过滤。
- D16 主选 streaming assembler：保留 planner 最后一批与下一页拼接；不足页/空页后处理剩余批，不漏行、不因页边界静默截断。

### 3.4 Budget / Processor

- `DreamingBatchProcessor`/`DreamingBatchOutcome`/`DreamingBatchBudget` 均为 Runtime internal seam。
- 直接复用 `TokenBatchPlanner.plan`；预算返回 required tokens 时由 planner fail-closed，映射为 `DreamingRunException(BUDGET)`。
- 固定开销/单 Turn 超预算不截断、不跳过、不改 extraction status；partial result 保持最后成功批水位。
- processor 严格串行；outcome `lastTurnId` 必须等于本批最后内部 ID 且严格大于当前成功水位，否则 `BATCH_PROCESSING` 失败。
- 每批成功后才更新内存 endCheckpoint/counts；失败停止后续批，不返回部分成功 RunResult。

### 3.5 Coordinator / Cron

- manual `runOnce()` 在 caller coroutine 执行；同实例已有 run 时立即 `ALREADY_RUNNING`，不等待/排队。
- `requestBackground()` 只启动 owned Job；running/closing 时 skip 并记录固定脱敏 WARN，不累加 trigger。
- close 同步进入 CLOSING、取消并真实 join owned Job；外部 manual run 不被强杀，caller CE 原样传播。
- 并发 close 共享同一 completion 与失败实例；测试 hook 只用于确定性清理断言。
- `DreamingPeriodicCron` 仅由调用方在 cron 非空时构造；UNIX 五字段 + 配置时区，严格未来、不立即 tick、不补跑。
- tick 仅调用 `requestBackground()`；普通 tick/调度失败 WARN + 1 秒可取消退避；close 立即取消并 join，共享失败且 cleanup 只执行一次。

## 4. 固定范围 / 跨页 / 超长 Turn / 单 Worker / Cron / partial failure 矩阵

| 场景 | 实际断言 |
| --- | --- |
| 空表 / U==C0 | 返回 start=end=C0、scanned/topics/memories=0、completed=true；0 processor |
| C0 与 U 同 ReadContext | Tracking DB 断言 checkpoint/maxTurn 各读 1 次且同一 ReadContext 同时观察到两字段 |
| C0>U / C0 负值 | 均 `DreamingRunException(FIXED_RANGE)`，partial completed=false，0 processor |
| 直接构造 orphan timezone | `DreamingConfig(cron=null, timezone=Europe/London)` 当场拒绝；`DreamingConfig()` 仍合法 |
| `(C0,U]` / ID ASC | 多页记录按内部 ID 升序扫描；所有 range upper 固定为 U |
| U 后新 Turn | 当前轮 batch 阻塞期间插入新 Turn，仍只处理到 U；下一轮按 checkpoint 处理 |
| pending/complete/failed | 三种 extraction status 均进入 batch |
| ID 空洞 | 真实删除中间主键后仅处理 1、3；批与计数正确，无漏行/补洞假设 |
| 跨页 streaming | pageSize 1/2/3 均穷尽 7 行，单批完整、无页边界丢行 |
| 固定开销/单 Turn 超预算 | `BUDGET` 具名失败，0 processor；不截断/跳过 |
| 预算等号 | 等于上限通过并处理 |
| 串行 processor / lastTurnId | 批顺序确定；批外 ID 触发 BATCH_PROCESSING，后续 0 调用，partial=最后成功批 |
| processor 普通失败 | 原 cause 保留，partial endCheckpoint=最后成功批 |
| DB 页读取失败 | `PAGE_READ`，原 DB cause 保留 |
| CE | caller/processor/预算路径 CE 原实例传播 |
| manual 重叠 | 第二个 manual 立即 `ALREADY_RUNNING`；background 返回 false，0 新 owned run |
| beginClosing 窗口 | 同步封准入；新 background 请求立即拒绝 |
| owned run close | 取消并真实 join，状态 CLOSED；关闭后请求 0 接受 |
| 外部 manual close | close 不取消 caller run；caller 自行取消时 CE 原样传播 |
| Coordinator 并发 close | 两个调用共享同一失败实例，cleanup 一次 |
| Cron 严格未来 | 固定 Clock/上海时区计算下一 tick；now 处 tick 不补跑 |
| Cron start | 启动不立即 request；仅收到显式 tick 后请求一次 |
| Cron running skip | 已有 owned run 时第二 tick 不新增 owned run/round |
| Cron 失败退避 | WARN 脱敏、固定 1000ms、可取消 close，不紧凑循环 |
| Cron close | 取消并 join tick loop；并发 close 共享同一失败，cleanup 一次 |

## 5. 测试映射与用例数

### 5.1 A01～A08（B1 负责部分）

| 验收 | B1 测试 | 关键覆盖 |
| --- | --- | --- |
| A01 Result/Error | `DreamingApiContractTest` | 六字段、边界、ALREADY_RUNNING、partial/stage/cause |
| A02 C0/U | `DreamingRunCoordinatorTest` | 同 ReadContext、空/等值/越界/负值 |
| A03 固定范围 | Coordinator 测试 | id ASC、U 后新 Turn、三状态、真实 ID 空洞 |
| A04 分页/跨页 | Coordinator 测试 | pageSize 1/2/3、跨页 assembler、空/不足页终止 |
| A05 预算 | Coordinator 测试 | 固定开销、单 Turn、等号、BUDGET 映射 |
| A06 Processor/partial | Coordinator 测试 | 串行、lastTurnId、计数、失败停止、partial/cause/CE |
| A07 单 Worker/Cron | Coordinator、PeriodicCron 测试 | ALREADY_RUNNING、skip、owned-only close、严格未来、退避、并发 close |
| A08 配置/边界 | Config/Loader、全模块测试 | manual-only、cron/timezone/unknown/type、Schema/VFS/Core/依赖 |

### 5.2 用例数

| 测试类/增量 | 新增用例 |
| --- | ---: |
| `DreamingApiContractTest` | 3 |
| `DreamingConfigTest` | 4 |
| `DreamingRunCoordinatorTest` | 16 |
| `DreamingPeriodicCronTest` | 6 |
| `ContextConfigLoaderTest`（本批新增） | 2 |
| **合计新增** | **31** |

## 6. 验收命令与结果

### 6.1 运行环境与成功证据

首次在受限沙箱直接运行 Gradle 时，Gradle 启动阶段报 `FileLockContentionHandler ... SocketException: Operation not permitted`。随后按项目入口在获批的非沙箱环境重跑。代码写完及每次测试补强后均执行了 `./scripts/dev gradle spotlessApply`。

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle spotlessApply` | 0 | 1s | `BUILD SUCCESSFUL`；57 tasks，2 executed / 55 up-to-date |
| `./scripts/dev gradle :context:runtime:test --tests '*DreamingConfigTest' --tests '*ContextConfigLoaderTest' --rerun-tasks --console=plain` | 0 | 11s | `BUILD SUCCESSFUL`；25 tasks；DreamingConfig 4/4、ContextConfigLoader 30/30 |
| `./scripts/dev gradle :context:api:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | 5s | `BUILD SUCCESSFUL`；9 tasks executed |
| `./scripts/dev gradle :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | 6s | `BUILD SUCCESSFUL`；5 tasks executed |
| `./scripts/check` | 0 | 2m21s | `BUILD SUCCESSFUL`；131/131 tasks executed |
| `git diff --check` | 0 | - | 空输出 |

### 6.2 R1 全量验收

- R1 的最终 `./scripts/check` 为 exit 0、131/131，全部模块完成，当前无全量阻断。
- 既有 `ExtractionPipelineLifecycleTest` 时序敏感性在 B1 初始阶段曾出现 10s 超时，但 R1 最终全量未复现；该文件不在白名单且 R1 未修改，继续作为历史稳定性观察。
- R1 未修改 Loader；Loader 的 orphan timezone 拒绝测试仍为 30/30 通过。

## 7. 14 模块源码 `@Test` 与 XML

源码范围为各模块 `src/test/**/*.kt` 行首（允许缩进）`@Test`；XML 来自最终逐模块 `--rerun-tasks` 测试结果。

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 41 | 41 | 0 | 0 | 0 |
| context-core | 119 | 119 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 651 | 651 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 27 | 27 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **1916** | **1916** | **0** | **0** | **0** |

### 7.1 与 1885 基线差异

- CT22 新增 **31** 个测试（API +3、Runtime +28）。
- Runtime +28 构成为 Dreaming Config 4 + Coordinator 16 + Cron 6 + 既有 Loader 2；与 1885 基线合计 1916，差额 31。

## 8. CT20 / CT18 / Schema / 81 / common / VFS / Core 边界

- CT20 B1 按 [CT20 B1 Worker 交付](../cm4-ct20/B1_Worker交付.md) SHA 表复算：**19/19 一致**；Search/Rerank/Prompt/Schema/Service 未改。
- CT20 B2 的 `ContextConfig.kt`、`ContextConfigLoader.kt`、`ContextConfigLoaderTest.kt` 为 CT22 白名单要求的有意增量；其余 CT20 B2 文件未由 B1 修改。
- CT18 17 个代码/测试文件按 [CT18 Worker 交付](../cm4-ct18/B1_Worker交付.md) SHA 表复算：**17/17 一致**；Generator/Publisher/Renderer 未改。
- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 81 VFS 基线：**75 一致 + 6 个 CT17 已批准差异，缺失 0，未批准差异 0**。
- B1 未修改 VFS/common/Schema/SQL/依赖/构建；`git diff -- vfs common gradle build.gradle.kts settings.gradle.kts` 为空。
- Core 主源码无 Ktor、无 kotlinx.serialization；Core/build 零修改。
- 未修改 `AlcyoneContext`、Factory/Impl、CT13/CT16/CT19/CT20 组件、`ContextStatus`/`WorkerStatus` 或 `ContextExceptions.kt`。

## 9. 未运行项与残余风险

- 真实模型：**not-run**；CT22 仅使用 fake processor/受控预算，不证明 Topic/Reflection 语义质量。
- CT23～CT26：**LOCKED**；Topic/Evidence/Reflection/Commit、真实 Prompt/LLM、Checkpoint/Raw/Pending CAS 均未实现。
- 未接 Factory/Impl/AlcyoneContext，未创建生产 Dreaming Worker/Cron。
- `./scripts/check` 最终通过 131/131；既有 Extraction 时序用例的早期超时仅作为历史稳定性观察，R1 未修改该文件。
- 负 C0 通过测试 wrapper 覆盖，因正式 Schema CHECK 禁止负 checkpoint；验证 Coordinator 包装路径，不改变持久化契约。
- ID 空洞通过真实删除中间主键覆盖；未增加测试专用 Schema/业务表。
- Dreaming 分批只在页后重算 planner；跨页尾部保留一批，验证无漏行但未声明吞吐性能上限。
- 无 Git 写操作；工作区既有 CM5 规划文档与开发计划修改未回滚、丢弃或改写。

## 10. 最终状态

**提交 Lead 初验，CT22 未 DONE，CT23～CT26 LOCKED。**

真实模型 not-run；无 Git 写操作。
