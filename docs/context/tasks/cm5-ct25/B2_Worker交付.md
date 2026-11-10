# CT25 B2 · 完整 Dreaming Batch Processor Worker 交付

**日期**：2026-11-10
**状态**：**提交 Lead 初验；CT25 B1 APPROVED / DONE；B2 与 CT25 未 DONE；CT26 LOCKED**
**依据**：[B2 委派单](B2_委派单.md)、[B2 Architect 复核](B2_Architect复核.md)、[B1 Worker 交付](B1_Worker交付.md)、[CT25 任务书](CT25_任务书.md)、[方案核对](方案核对.md)
**基线**：CT25 B1 APPROVED / DONE；14 模块 **2113=2113**；Git `f391192b91da72878908ad7230ba1ef515cff43a`；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`。

## 1. 交付结论

严格完成 B2 委派单 §3 的 **10 个文件**：9 个代码/测试文件加本报告。5 个既有文件按白名单有意修改，4 个扩展既有文件 SHA 不变；白名单外代码、测试、构建修改 **0**。

- `LlmDreamingBatchProcessor` 完成 Topic/Evidence → Reflection → B1 原子 Commit → Organization 通知链；无空 Evidence 短路，空 Topic 交由 CT24 上游具名失败。
- 批次入口要求 `DreamingBatchContext`，LLM 调用位于事务外；提交复用固定 `expectedCheckpoint/upperBound`，不读取或自行推进 Checkpoint。
- `OrganizationWakeup` 仅在原子提交且 `rawCount > 0` 后调用；普通异常脱敏 WARN 并继续，CE 原样传播且不回滚已提交结果。
- `DreamingBatchOutcome` 使用真实 Topic 数、Raw 数和已提交 `lastTurnId`；CT22 partial failure 语义保持。
- `DreamingTopicBatchProcessor` 仅保留验证适配器语义：无 Write Tx、0 Raw/0 Organization，后续由完整 Processor 替代。
- 新增 **5** 个 Runtime 测试；B1 35 个已有测试未删除或放宽。最终 14 模块 **2118=2118**，fail/error/skip = **0/0/0**，相对基线恰增 5。
- 未接 `ContextServiceFactory`、`AlcyoneContextServiceImpl` 或生产调用链；未改 Schema/SQL/迁移/依赖；真实模型 **not-run**；无 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 有意修改的既有文件（5）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingBatchProcessor.kt` | `a597499036072f9d036c3867a4a12d032d28c8058cc44b2f14cff064a6074893` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingBatchProcessor.kt` | `2e93621bc2d9a3ab8c76017fe2cc6a4f30cb8e143130c2a3a414e09caf0426b7` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingRunCoordinator.kt` | `0abe58a76faf80353143704c794c649c422fcc325b584301aaf19cc74a5f495d` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingTopicBatchProcessor.kt` | `97a9a3923ad2165cec2347301c1fd0339dc7e4735ef8a1169bd965207d5505f7` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingTopicBatchProcessorTest.kt` | `cb8d622d16a4865b1afd7db121456d746ab480cb7e83ce2fa249607766e32173` |

### 2.2 扩展既有文件（4）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingTestSupport.kt` | `ea60cdf7a2fb3f1cccd94604adab16f704cba0655f4a9b8be083896d0e94318b` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingEvidenceTestSupport.kt` | `b908567dafd949eb37502cf896472322c34ad382c3bc3ac1e9cb4514465f613c` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingEvidenceRealDbTest.kt` | `4cb7eb5da572840f3bfda563ff2e749a1f6a7a198628a9ba134460dd5729948f` |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/dreaming/DreamingEvidencePromptTest.kt` | `fa67c5925c8e118b638f2cc1ff5c4deaead8bd2dc35ad6a61f1d5f3c8739b3e8` |

### 2.3 新增测试与报告（2）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingBatchProcessorTest.kt` | `bb1884ba127387070e7007de41f4652e9169be4b4298ada480f1902956de0470` |
| `docs/context/tasks/cm5-ct25/B2_Worker交付.md` | 本文件自身不内嵌自引用 SHA；由外部最终计算 |

## 3. 完整链与事务/通知语义

### 3.1 Processor

`LlmDreamingBatchProcessor` 提供三个构造入口：独立 Stage seam、现成 Evidence/Reflection Stage，以及 `LlmClient + LlmConfig` 的便捷构造。便捷构造显式组装既有 `LlmDreamingTopicDiscoveryStage`、`LlmDreamingEvidenceStage`、`LlmDreamingReflectionStage`，不是生产 Factory 接线。

每批严格依次执行：范围校验 → Evidence 收集 → Reflection → `DreamingCommitter.commit` → 条件通知。Evidence/Reflection 未返回或失败时不启动 Commit；Commit 未成功时不发送 Organization。

### 3.2 固定范围、partial 与 Commit

- `DreamingBatchContext` 包含本批 `expectedCheckpoint`、固定 `upperBound` 和非空 `turns`。
- 校验 Checkpoint/上界非负、`expected < upper`、首 Turn 严格大于 expected、Turn ID 严格递增、末 Turn 恰为 upper；ID 空洞合法，不要求连续。
- 每批将 `lastTurnId = batch.last().id` 传给 B1 Committer；B1 在单个 Write Tx 中完成 Raw+Pending+CAS。
- Processor 不先写 Raw、不读 Checkpoint 代替 Coordinator、不缓存后续状态；每批独立提交。
- Coordinator 固定本轮 U，将当前成功水位传入下一批。前批成功、后批失败保留 `partialResult`，不重复提交前批；DB commit 可重试部分以新 expected CAS，重试部分完全不写 Raw/Pending。

### 3.3 OrganizationWakeup

- 通知读取的是 Commit 返回值，`rawCount == 0` 不发；`rawCount > 0` 仅在 Commit 返回后调用。
- 普通异常只输出 `dreaming.organization.wakeup.failed batchLastTurnId=<id>`，不挂 Throwable，不回显正文或异常消息。
- 通知失败后的批次继续提交，最终 RunResult 成功；CE 原样抛出，已提交 Raw/Pending/Checkpoint 保留，下一轮从已提交水位继续。

### 3.4 Topic 验证适配器

`DreamingTopicBatchProcessor.process(List<StoredConversationTurn>)` 继续用于 CT23 组件验证，只返回 Topic 数、批尾和 0 Raw。它不创建 Write Tx，不调用 Organization，不宣称替代完整 Processor。

## 4. 验收矩阵与实际断言

| 场景 | 实际证据 |
| --- | --- |
| 真实 Discovery budget 三 Turn | `LlmDreamingBatchProcessorTest` 使用真实 Stage/Tokenizer/Fake LLM；超预算由真实 planner 拆分，Coordinator 逐批推进；每批 Raw+Pending 可读 |
| 合法 ID 空洞与范围拒绝 | 删除中间 ID 后仍可提交；空 turns、负 expected/U、expected==首 ID、末 ID!=U、重复/倒序 ID 均在 Evidence 前 `IllegalArgumentException` |
| 三批固定 U 与逐批 expected | 记录 `0→1、1→2、2→3` CAS；每批 upper=3；通知发生在各批 Commit 后 |
| 前批成功、后批 Reflection 失败 | 前批 Raw/Pending/checkpoint 保留；`DreamingRunException(BATCH_PROCESSING)` 的 partialResult 保留前批 topics/raw/endCheckpoint；原 cause 保留 |
| Commit DB 可重试部分 | CAS 失败后 Coordinator 终止；重试阶段 Raw/Pending=0，CAS attempts 逐批前进，不出现重复写入 |
| Reflection 0 Insight | Commit 成功但 rawCount=0；不通知，Coordinator 正常推进 |
| Reflection 非空 | Raw+Pending 原子落库，DREAMING 来源及 ConversationTurnRef 正确 |
| Organization 普通失败 | 脱敏单条 WARN；下一批继续；Raw/Pending/checkpoint 最终为 2/2/2；敏感消息及 Throwable 不出现在日志 |
| Organization CE | 原实例传播；当批提交保留；下一次 run 从 checkpoint 前进，不重做已提交批次 |
| Evidence/Reflection/Commit 失败 | 失败点之后阶段及通知均不执行；CE 不包装 |

CT22 原有 `DreamingRunCoordinatorTest` 16 例、CT23 `DreamingTopicBatchProcessorTest` 3 例继续通过；B1 `DreamingReflectionTest` 9、Prompt 5、Parser 8、Stage 10、Committer 13 未删改，全部通过。

## 5. 新增测试与计数

B2 仅新增 `LlmDreamingBatchProcessorTest` 中 **5** 个验收用例：真实 Discovery budget 分批、范围/空洞、三批提交链、后批 DB 重试失败、通知普通失败；其余既有完整链、零 Raw、通知 CE 等用例继续作为补充回归。源码普通 `src/test` 的 `@Test` 行与 XML testcase 使用同一范围；`webdavTest` 未计入。

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 41 | 41 | 0 | 0 | 0 |
| context-core | 179 | 179 | 0 | 0 | 0 |
| context-persistence | 183 | 183 | 0 | 0 | 0 |
| context-runtime | 788 | 788 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 27 | 27 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **2118** | **2118** | **0** | **0** | **0** |

增量核对：B1 2113 + B2 新增 5 = **2118**，差额 0；Core 179、Persistence 183 保持 B1 数值；Runtime 783 + 5 = 788。`LlmDreamingBatchProcessorTest` 最终 XML **10/10**，其中 B2 新增 5。

## 6. 验收命令与结果

| 命令 | exit | 结果 |
| --- | ---: | --- |
| `./scripts/dev gradle spotlessApply` | 0 | `BUILD SUCCESSFUL`；57 tasks，2 executed / 55 up-to-date |
| `./scripts/dev gradle :context:core:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | `BUILD SUCCESSFUL`；9 tasks executed |
| `./scripts/check` | 0 | `BUILD SUCCESSFUL in 2m 23s`；131 actionable tasks executed |
| `git diff --check` | 0 | 空输出 |

初次受限沙箱运行出现 Gradle `FileLockContentionHandler ... SocketException: Operation not permitted`，随后按项目入口在获批环境执行。最终全量统计在完整 `./scripts/check` 结束后立即完成，14 模块 XML 全部存在；随后未再启动 Gradle，避免与其他验收任务的 clean/定向测试覆盖共享报告。

## 7. 基线、边界与残余风险

- CT23 21 项 SHA 复算 **19/21**；仅 `DreamingTopicBatchProcessor.kt` 与其测试为本批有意修改。
- CT22 14 项 SHA 复算 **11/14**；仅 `DreamingBatchProcessor.kt`、`DreamingRunCoordinator.kt`、`DreamingTestSupport.kt` 为本批有意修改。
- CT24 扩展点：EvidenceTestSupport、EvidenceRealDbTest、Core EvidencePromptTest 及 `DreamingEvidencePrompt.kt` 均保持 B1 交付 SHA。
- CT25 B1 Reflection 生产、资源、Parser、Stage、Committer 及其测试未被 B2 修改。
- Schema SHA `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变；Persistence 测试 183/183。
- 未改 common/VFS/gradle/构建/依赖；未接生产 Factory/Service，真实模型 not-run，未运行跨窗口组织质量评测。
- CT26 的生产接线、真实模型 A01～A08、端到端 Dreaming 与质量验收仍 **LOCKED**。

**本报告提交 Lead 初验，不宣称 CT25 整体 DONE，也不放行 CT26。**
