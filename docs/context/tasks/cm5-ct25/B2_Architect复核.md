# CT25 B2 · Architect 首轮复核（R1 阻断）

**日期**：2026-11-10
**状态**：**CT25 B2 IN_REVIEW / R1；CT25 未 DONE；CT26 LOCKED**
**复核对象**：[B2 Worker 交付](B2_Worker交付.md)、[B2 Lead 初验](B2_Lead初验.md)、[B2 委派单](B2_委派单.md)、[B2 白名单复核](B2_白名单复核.md)
**依据**：[CT25 任务书](CT25_任务书.md)、[Architect 裁定](Architect_裁定.md)、[B1 Architect 复核](B1_Architect复核.md)

## 0. 本次修改

Architect 独立核对 B2 生产实现、7 个新增用例的实际断言、B1 组件 SHA、三模块定向测试/Spotless 与全量 `./scripts/check`。构建与主路径实现通过，但白名单强制矩阵中的通知失败、真实 Commit 失败、失败零副作用和非空 Topic 零 Insight 未被有效验证；因此 **B2 不批准 DONE，退回 Lead 安排 Worker 定点补测试**。

## 1. 独立验证证据

| 项 | Architect 独立结果 |
| --- | --- |
| 三模块测试 | `:context:core:test :context:runtime:test :context:persistence-sqldelight:test --rerun-tasks` exit 0，1m1s，29/29 tasks；Core 179、Runtime 785、Persistence 183，fail/error/skip=0/0/0 |
| 三模块 Spotless | `--rerun-tasks` exit 0，6s，13/13 tasks |
| 全量 | `./scripts/check` exit 0，2m18s，131/131 tasks executed |
| 14 模块 XML | **2115=2115**（相对 CT24 的 2063 增加 Runtime 52）；全部 failures/errors/skipped=0 |
| B2 增量 | `LlmDreamingBatchProcessorTest` 7/7；`DreamingRunCoordinatorTest` 23/23；`DreamingTopicBatchProcessorTest` 13/13 |
| B1 SHA | Reflection DTO/Prompt/资源/Parser/Binding/Stage/Committer 与 B1 交付逐项一致；`DreamingCommitter` SHA `bb8380a1…2bc8b9` 未变 |
| 范围/边界 | 白名单外生产/构建 diff 未见；`vfs/`、`integration-tests/vfs/`、common、Gradle 无 tracked diff；Schema `ec727a53…aadd8ea3` 未变；Core 无 Ktor/serialization；Factory/Impl 无 Dreaming 接线 |
| 格式/工作区 | `git diff --check` exit 0；真实模型 not-run；无 Git 写操作 |

源码 `@Test` 行首计数在 `vfs-core` 比 XML 多 1，原因是 `NotifierTeardownTest` 的 `@TestMethodOrder` 被简单行首规则误计；按 XML 精确聚合及去除该注解前缀后为 **2115=2115**，不是测试缺失。

## 2. 已通过的实现审查

- 唯一 `DreamingBatchContext(expectedCheckpoint, upperBound, turns)` 已接入 Coordinator，范围读取、ID 空洞、实际批尾与空范围行为保持 CT22 契约。
- 多批 expected 使用已提交批尾，所有批共享固定 U；普通失败保留前批内存 partial，通知 CE 在已提交后原样传播且不伪记内存 partial。
- `LlmDreamingBatchProcessor` 顺序为 Evidence → Reflection → Commit → `rawCount>0` 条件通知；普通通知异常由固定事件 `dreaming.organization.wakeup.failed` 脱敏记录，CE 原样传播。
- 完整链保留 CT24 Evidence/CT25 Reflection/B1 Committer 接缝，不改 Organized、Extraction 状态、Schema 或公共生产入口。
- 现有成功多批、预算拆批、Reflection 失败前批保留、通知 CE 恢复、空 Evidence 推进测试有效；B1 的真实 SQLite 原子提交故障矩阵仍有效。

上述是通过的实现检查，**不能替代下述缺失的正式验收断言**。

## 3. R1：强制验收覆盖不完整

### 3.1 普通通知失败没有测试

白名单 §4.7 明确要求注入含敏感正文的普通异常，断言固定脱敏 warning、日志无敏感正文/堆栈内容、本批成功且数据库保留、后续批继续前进。

当前 7 个 `LlmDreamingBatchProcessorTest` 中只有通知 CE 用例，没有普通通知异常用例；其他允许的 B2 测试文件也未覆盖。静态代码看似正确不等于该分支已验收。

### 3.2 三个失败用例缺少零副作用及后续不执行断言

白名单 §4.9 要求 Evidence、Reflection、真实 Commit 各至少一例，并断言当前批 **0 通知、0 新 Raw/Pending、checkpoint 保持前批尾、前批 partial 保留、后续批不执行**。

当前：

- `reflection failure preserves prior partial` 只检查 outcome、partial 和 checkpoint；没有检查 Raw/Pending、通知次数、后续批是否执行。
- `evidence reflection and real commit failures preserve prior partial` 的名称与交付声明包含三类失败，实际循环仅注入 Evidence 异常和 Reflection 异常，**没有 Committer 故障分支**。
- 该用例同样没有查询 Raw/Pending，没有统计通知或第三批调用；无法捕获“失败后仍通知/写入/继续后续批”的错误实现。

必须补真实 SQLite Commit 故障（可复用 B1 `DreamingCommitterRealDbTest` 的 FaultInjectingDatabase 模式），而不是只 fake committer 抛出。

### 3.3 非空 Topic 的 0 Insight 合法推进未覆盖

现有 `zero result advances checkpoint...` 使用 `DreamingBatchEvidence(emptyList())` 与 `reflectEvidence={emptyList()}`，覆盖空 Evidence，不覆盖白名单 §4.6 的 **0 Insight**。需要让 Reflection 对实际非空 Topic/Evidence 返回合法空列表，并证明真实 Commit 0 Raw/Pending、推进 CAS、0 通知、后续范围正确。B1 Committer 的零 Insight 单元测试不能替代完整 Processor 接缝验证。

## 4. 定点修复范围

仅允许修改：

1. `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/LlmDreamingBatchProcessorTest.kt`
2. `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingTestSupport.kt`（确有必要复用真实 DB 故障注入时）
3. 本批交付/复核/进度文档。

不得修改 B2 生产文件或 B1/CT22～CT24 组件，除非新测试实际证明生产缺陷并另行回报裁定；不得为通过测试放宽断言或删除既有用例。普通通知 warning 可用仓库现有 SLF4J 测试捕获机制验证，不新增依赖。

完成后必须独立重跑三模块测试/Spotless、`./scripts/check`，记录准确测试增量、2115 基线增长、B1/B2 生产 SHA、Schema/VFS/common/Core/生产零接线边界。真实模型 not-run，无 Git 写操作。

## 5. 首轮裁定（已被 §6 R1 复核关闭）

**CT25 B2 IN_REVIEW / R1；不批准 CT25 DONE。** 退回 Lead 组织定点补测试及重新初验；R1 关闭前 CT26、真实模型、公共 Factory/Impl 接线继续 LOCKED。

## 6. R1 复核关闭（2026-11-10）

**状态：CT25 B2 APPROVED / DONE；CT25 APPROVED / DONE；R1 CLOSED**

### 6.1 独立证据

| 项 | Architect 独立结果 |
| --- | --- |
| Runtime 定向测试 | `--rerun-tasks` exit 0，55s，25/25 tasks；`LlmDreamingBatchProcessorTest` 10/10 |
| 全量 `./scripts/check` | **exit 0**，2m14s，131/131 tasks（期间环境偶发 vfs:git-backup 失败，重跑即过，非本次变更引入） |
| 14 模块 XML | **2118=2118**（B2 R1 +3：7→10），fail/error/skip=0/0/0 |
| B2 生产 SHA | 10/10 与交付一致（4 有意修改 + 4 扩展 + 2 测试） |
| B1 Committer SHA | `bb8380a1…2bc8b9` 未变 |
| Schema | `ec727a53…aadd8ea3` 未变 |
| `git diff --check` | exit 0；真实模型 not-run；无 Git 写操作 |

### 6.2 R1 四项强制工作项实际断言核验

1. **普通通知异常脱敏**：`ordinary notification failure is sanitized...` 注入含敏感正文异常；`Slf4jCapture` 断言固定 `dreaming.organization.wakeup.failed` WARN、无 Throwable、敏感正文不出现在任何日志；首批提交保留（Raw/Pending/checkpoint=2/2/2），第二批继续；通知调用 2 次。✓
2. **Evidence/Reflection 失败零副作用**：`evidence failure preserves prior state...` 与 `reflection failure preserves prior partial` 分别注入第二批失败；前批 Raw/Pending/checkpoint/partial 完整保留（checkpoint=1、Raw=1、Pending=1、通知=1），当前批 0 新写，第三批不执行（evidenceCalls/reflectionCalls 列表精确断言）。✓
3. **真实 SQLite Commit 故障**：`real sqlite commit failure rolls back current batch...` 使用 `DreamingCommitFaultDatabase`（delegate 真实 SQLite，仅 CAS 拦截抛错）驱动真实 `DreamingCommitter`；CAS 失败经 Coordinator 映射 BATCH_PROCESSING；前批完整保留，当前批 0 新写/0 通知，后续不执行；casAttempts 精确断言 `(0,1),(1,2)`。✓
4. **非空 Topic/Evidence 0 Insight**：`nonempty topic with zero insights advances real cas...` Reflection 收到非空 Topic/Turn 后返回合法 emptyList；真实 CAS 逐批 `(0,1),(1,2),(2,3)`；0 Raw/Pending/0 通知；固定 U=3 与后续范围正确（rangeCalls 精确断言）。✓
5. **通知 CE**：`notification cancellation preserves commit and next run does not redo batch` 断言 CE 同实例传播且已提交 DB 保留、下轮不重复。✓

### 6.3 结论

- **CT25 B2 R1 全部关闭，B2 APPROVED / DONE；CT25 APPROVED / DONE。**
- CT25 完成 Reflection 与逐批原子提交：DreamingBatchContext 显式 expected/U、单事务 Raw(DREAMING)+Pending+Checkpoint CAS、ID 碰撞有限重试、通知脱敏/CE 传播、真实 SQLite 全链验证。
- **放行 CT26**（公共 Factory/Impl 接线 + 真实模型评测规划），按既定流程先只读方案提交 Architect 裁定；CT26 通过前不宣称 CM5 完成。
- 真实模型 not-run；无 Git 写操作。
