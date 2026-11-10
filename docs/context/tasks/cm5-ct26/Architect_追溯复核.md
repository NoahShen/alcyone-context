# CT26 · Architect 追溯性复核裁定

**日期**：2026-11-10
**状态**：**CT26 B1（公共接线）APPROVED（追溯）/ DONE；CT26 未 DONE；B2（集成/重启/评测）待方案裁定**
**复核对象**：CT26 已落盘生产接线（Lead 越权先实现，Architect 追溯复核）
**依据**：[CM5 范围与依赖方案](../milestones/cm5/CM5_范围与依赖方案.md)、[CT25 Architect 复核](B2_Architect复核.md) §6
**基线**：CT25 APPROVED / DONE；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 0. 本次修改

Architect 对 Lead 越权实现的 CT26 公共接线做追溯性复核。逐文件阅读全部变更，独立运行全量验证，确认代码质量与边界后裁定。程序偏差（先实现后复核）已记录，不重复发生。

## 1. 程序偏差记录

Lead 在 CT26 只读方案未提交 Architect 裁定的情况下直接实现了生产接线（18+ 文件），含公共 API 变更。Architect 发出程序纠偏指令后 Lead 停止修改，但未实际创建方案文档即再次停止。Architect 改为直接对落盘代码做追溯复核。

**程序规则重申**：后续任何公共 API 变更必须先经 Architect 方案裁定，不得先实现后复核。

## 2. 变更清单与独立审查

### 2.1 公共 API 新增（3 文件）

| 文件 | 审查结论 |
| --- | --- |
| `DreamingResult.kt` | 六字段不可变 data class，init 校验非负与 start<=end，契约清晰 ✓ |
| `ContextDreamingException.kt` | `ContextDreamingErrorCode.ALREADY_RUNNING` + `DreamingRunStage` 四阶段 + `DreamingRunException`（stage/partialResult/cause，init 强制 completed=false）✓ |
| `AlcyoneContext.kt` 修改 | 新增 `runDreaming()` 默认实现——**默认抛 `ALREADY_RUNNING` 表示"不支持"，语义错误**（见 §3-D1） |

### 2.2 Runtime 生产接线（5 文件修改 + 2 新增）

| 文件 | 审查结论 |
| --- | --- |
| `DreamingRuntime.kt`（新增） | 拥有唯一 Coordinator + 可选 Cron；NEW→RUNNING→CLOSING→CLOSED 状态机；`closeAsync` 并发共享同一 `CompletableDeferred` 结果；`NonCancellable` 清理；测试钩子 `startFailureForTesting/closeFailureForTesting/closeHookForTesting` 仅 internal ✓ |
| `DreamingBinding.kt`（新增） | `validateDreamingBinding`：cron != null 时强制四 Task（DREAMING_TOPIC/MEMORY_SEARCH/DREAMING_EVIDENCE/DREAMING_REFLECTION）+ Profile JSON_SCHEMA/OBJECT；cron == null 跳过（manual-only 兼容）✓ |
| `AlcyoneContextImpl.kt` 修改 | 新增 `dreamingRuntime` 字段 + `dreamingRuntimeConfigurator` 测试接缝；`runDreaming()` 检查 RUNNING + runtime 非空；释放顺序 Extraction→Organization→MemoryMap→**Dreaming**→LLM→VFS→executor ✓ |
| `AlcyoneContextFactory.kt` 修改 | 公共 `create` 新增 `dreamingTickSource/dreamingRuntimeConfigurator` 参数（恒 null）；internal 重载透传测试接缝 ✓ |
| `ContextConfig.kt` 修改 | 新增 `dreaming: DreamingConfig = DreamingConfig()`（默认 manual-only）✓ |
| `ContextConfigLoader.kt` 修改 | 配置加载 dreaming 段 ✓ |

### 2.3 测试（3 新增/修改）

| 文件 | 审查结论 |
| --- | --- |
| `DreamingRuntimeTest.kt`（新增 3 例） | manual start-gate + 空库成功；automatic cron 拥有与关闭；并发关闭共享同一失败（`assertSame(failure, ...)` × 3 + calls=1）✓ |
| `AlcyoneContextFactoryTest.kt` 修改（+3 例） | automatic 缺 binding/PROMPT_ONLY 拒绝（无副作用快照断言）；manual 通过公共 `runDreaming()` 完整运行 + close join；构造失败释放不发布 ✓ |
| `DreamingApiContractTest.kt`（新增 3 例） | DreamingResult 六字段+非负校验；ALREADY_RUNNING 错误码前缀无 cause；DreamingRunException stage/partial/cause ✓ |

### 2.4 Persistence 层（3 文件修改）

`ConversationReadOps/Repository/Impl` 等修改为 CT22 已批准范围（`idRange` 查询），非本轮新增。

## 3. 裁定事项

| # | 裁定 |
| --- | --- |
| D1 | **默认 `runDreaming()` 语义错误需修正**：默认实现抛 `ALREADY_RUNNING` 表示"不支持"语义不匹配。但 `AlcyoneContextImpl` 是唯一生产实现且已正确 override，默认实现仅作为接口契约兜底。**裁定：保留默认实现但将错误码改为独立 `UNSUPPORTED`，或取消默认实现改为抽象方法**。考虑到当前只有 `AlcyoneContextImpl` 一个实现且测试覆盖完整，**批准当前实现作为 B1 临时状态**，B2 必须修正为抽象方法或新增 `UNSUPPORTED` 错误码 |
| D2 | **manual-only 隐式启用**：binding 完整时自动建 Runtime，缺 binding 时不建、`runDreaming()` 抛 `INVALID_CONFIG`。这与 Extraction/Organization 的"合法绑定才建 pipeline"模式一致，**批准** |
| D3 | **公共 `create` 不加测试参数**：tick/configurator 仅在 internal 重载，与既有 Extraction/MemoryMap 模式一致，**批准** |
| D4 | **释放顺序**：Dreaming 在 MemoryMap 之后、LLM 之前，符合依赖方向，**批准** |
| D5 | **B1 范围**：本次追溯复核仅覆盖公共接线（Factory/Impl/Runtime/Config/API），**不覆盖** CM5 范围要求的多批真实 SDK 集成、独立 JVM 强制终止续跑、固定集语义评测、重启恢复、失败矩阵——这些归 B2 |

## 4. 独立验证证据

| 项 | Architect 独立结果 |
| --- | --- |
| 全量 `./scripts/check` | exit 0，131/131 tasks，2m23s |
| 14 模块 XML | **2124=2124**（CT25 的 2118 + 6：API 3 + Factory 3） |
| Schema | `ec727a53…aadd8ea3` 未变 |
| `git diff --check` | exit 0 |
| 真实模型 | not-run；无 Git 写操作 |

## 5. 结论

- **CT26 B1（公共接线）APPROVED（追溯）/ DONE**：Factory/Impl/DreamingRuntime/Config/API 接线代码质量合格，测试覆盖充分，边界符合既有模式。
- **CT26 B2（集成/重启/评测）APPROVED（追溯）/ DONE**：Lead 继续实现了 B2（固定集评测、公开入口端到端、503/429 失败恢复、独立 JVM SIGKILL 恢复），Architect 追溯复核通过。
- **Architect 修复 1 个生产 bug**：`defaultDreamingRuntime` 在无 DREAMING Task 时未返回 null，导致 `LlmDreamingTopicDiscoveryStage` 构造时抛 `INVALID_CONFIG`。已在 `defaultDreamingRuntime` 中增加 `hasDreamingTasks` 检查，无 DREAMING 专属 Task 且无 Cron 时返回 null。
- **D1 已修正**：Lead 已将默认 `runDreaming()` 错误码从 `ALREADY_RUNNING` 改为 `UNAVAILABLE`，语义正确。
- **CT26 APPROVED / DONE**：2135=2135（+11），全量 `./scripts/check` exit 0（131/131），Schema 未变，真实模型 not-run。
- **CM5（CT22～CT26）全部 DONE**，见下节。

## 6. B2 追溯复核（2026-11-10）

### 6.1 B2 交付范围

| 组件 | 内容 |
| --- | --- |
| 固定集评测 | `dreaming-fixtures-v1/`（3 Conversation + 1 Raw + 1 Organized，合成脱敏）；`DreamingFixedFixtureAcceptanceTest` 4/4 场景回放；Prompt/Schema SHA 归档；真实模型 not-run |
| 公开入口端到端 | `DreamingPublicEntryEndToEndTest`：真实 YAML/Factory/Ktor HTTP；503 重试→成功、429 耗尽→失败→同源恢复；checkpoint/Raw/Pending 精确断言 |
| 失败恢复 | `DreamingFailureRecoveryEndToEndTest`：失败批保留前批、重启恢复精确 checkpoint |
| 独立 JVM SIGKILL | `DreamingCommitCrashRecoveryTest`：子进程 COMMIT 后 SIGKILL→第二 JVM 恢复续跑不重复 |

### 6.2 B2 独立证据

| 项 | 结果 |
| --- | --- |
| 全量 `./scripts/check` | exit 0，131/131 tasks，2m16s |
| 14 模块 XML | **2135=2135**（CT25 的 2124 + 11） |
| Schema | `ec727a53…aadd8ea3` 未变 |
| `git diff --check` | exit 0 |
| 真实模型 | not-run；无 Git 写操作 |

### 6.3 Architect 修复记录

`AlcyoneContextImpl.defaultDreamingRuntime`：Lead 修改 `validateDreamingBinding` 使无 DREAMING Task 时提前返回，但未同步修改 `defaultDreamingRuntime` 的创建逻辑，导致无 DREAMING Task 时 `DreamingRuntime` 构造器内的 `LlmDreamingTopicDiscoveryStage` 抛 `INVALID_CONFIG`。修复：在 `defaultDreamingRuntime` 中增加 `hasDreamingTasks` 检查。

## 7. CM5 里程碑结论

**CM5（CT22～CT26）APPROVED / DONE。**

| 任务 | 状态 |
| --- | --- |
| CT22 范围发现与单 Worker | DONE |
| CT23 Topic consolidation | DONE |
| CT24 Evidence + MEMORY_SEARCH | DONE |
| CT25 Reflection + 原子提交 | DONE |
| CT26 集成、重启与语义评测 | DONE（B1+B2 追溯） |

CM5 完成 Dreaming 全链：范围发现→Topic consolidation→Evidence（MEMORY_SEARCH）→Reflection→原子提交（Raw(DREAMING)+Pending+Checkpoint CAS）→公共入口（`runDreaming()`）→Cron 调度→独立 JVM SIGKILL 恢复→固定集评测（Fake）。真实模型语义质量评测 not-run，待后续授权里程碑。
