# CT28 B2a Worker 委派单：状态接缝

## 本次修改（2026-11-11，B2a 唯一 Worker 实施边界）

- 依据 [B2 Architect D1～D16 裁定](B2_Architect裁定.md)，本单只实施 **B2a**：API DTO/default、Pending count 默认与真实 SQL Read/Tx、Runtime Ready/Admin、worker/LLM 快照、最小 Backup 错误阶段及对应测试。
- **不做 Admin/Health HTTP，不做 managed，不改 `context/server/**`，不启动 B2b/c**；不改 Schema、Prompt、构建、VFS/common，不做 Git 写操作。
- 原 Worker `c2816850` 由主线程派发；Lead 不兼任 Worker/Architect。完成后 Worker 自测，Lead 独立初验，B2b 不得提前开始。

**任务状态**：B2a 可实施；CT28 / B2 仍 IN_PROGRESS。优先执行裁定 §2/§3 与 D2～D7、D13～D16；本单不放宽裁定。

## 1. 实施契约

### 1.1 API 默认方法与安全 DTO（D2）

在 `AlcyoneContext` 只新增：

```kotlin
suspend fun checkReady(): Boolean = false
suspend fun getAdminStatus(): ContextAdminStatus =
    ContextAdminStatus(lifecycle = getStatus().lifecycle)
```

新增 `ContextAdminStatus.kt` 纯 Kotlin data DTO，至少承载 lifecycle、四类 worker、Pending、Dreaming checkpoint、Map 四态、LLM usage、Backup。建议命名：`ContextAdminStatus`、`AdminWorkerStatus`、`AdminPendingStatus`、`AdminDreamingStatus`、`AdminLlmUsage`、`AdminLlmTaskUsage`。

- `AdminWorkerStatus(state: WorkerState? = null, lastFailure: Boolean? = null)`；无事实不得填 IDLE/STOPPED/false。第三方默认只含已知 lifecycle，workers/llm/backup 为 null，其余可空字段为 null。
- Pending 为真实 `count: Int`；Dreaming 为真实 `lastProcessedTurnId: Long`。未配置 Dreaming 时 dreaming=null，不伪造 0。
- Map 使用现有 `MapStatus`，未配置为 null，禁止由 Ready 推导 CURRENT。
- LLM 每条按 task/profile 带 calls/successes/failures/attempts、usagePresent/usageMissing、nullable input/output/totalTokens；client 未创建为 null，已创建零记录为空列表，无 usage 样本 token=null。
- Backup 复用 `ContextBackupStatus` 或等价安全 DTO，未配置为 null；状态仅来自 `backupStatusReader`，不表示远端健康。
- DTO 集合在构造边界防御性复制；不得包含 `databasePath`、Throwable、自由异常文本、Key、Prompt/body、临时路径或后端连接信息。API 不加 Ktor/serialization 注解。

### 1.2 Pending count（D3）

Core 接口必须源码兼容：

```kotlin
fun count(): Int = listAllByRawCreatedAt().size
```

生产 `OrganizationPendingReadOpsImpl` 和 `OrganizationPendingTxOpsImpl` **均显式 override**，共享 `OrganizationPendingReader.count()`。新增唯一 SQL 常量 `COUNT_ORGANIZATION_PENDING = "SELECT COUNT(*) FROM organization_pending"`，通过既有 `ConversationQuery.queryPrepared` 执行。

必须恰好一行、非 null、非负且 `Long → Int` 不溢出；坏值抛既有 `persistenceUnavailable(...)` 受控异常，不回退 list 全表、不默认 0。不得修改同名现有会话 `COUNT_PENDING`。无 Schema/DML/迁移。

### 1.3 Runtime Ready（D4）

`AlcyoneContextImpl.checkReady()` 固定顺序：

1. RUNNING 预检，否则 false。
2. 在同一个状态短锁快照中取得当前受管 `database` / `executor`（ContextDatabaseImpl 的 read 已使用当前 executor）；锁外执行 `database.read { it.dreaming.readCheckpoint() }`。
3. 返回后再次确认 RUNNING 才 true。
4. 普通读失败、关闭竞争、未知状态均 false；外部 `CancellationException` 原样传播。

不得使用 `databaseVerified`、文件存在、SELECT 1 或新 JDBC 连接。不得因 Pending 非空、LLM 失败、Map STALE/MISSING/UNKNOWN 失败；不得调用 LLM/VFS/Map。

### 1.4 Runtime Admin 与快照来源（D5/D6）

`getAdminStatus()`：锁内只短读稳定引用/lifecycle，锁外一次 `database.read` 同取 `read.pending.count()` 与 `read.dreaming.readCheckpoint()`；Pending 查询失败整次抛受控异常，不伪 0，外部 CE 传播。worker/LLM/Map/Backup 快照只短锁读取，不持锁做 DB/VFS/SDK/用户回调。

| 来源 | 实现要求 |
| --- | --- |
| Extraction | Scheduler `workerStatus()` 使用现有 state、executions>0 与新增真实非预期终止失败位；NEW/零 execution=IDLE，RUNNING+executions>0=RUNNING，CLOSING/CLOSED=STOPPED，非关闭状态 worker 自行 CE 终止=FAILED/lastFailure=true。普通 Runner 异常仍隔离，不得 FAILED；正常 close 不 FAILED。Pipeline 只读转发 |
| Organization | Scheduler 增语义明确 `workerStatus()`，复用现有 status 算法；Pipeline `workerStatus()` 转发；原测试访问器保留 |
| Memory Map | worker 来自现有 coordinator `statusForTesting()`；Map 四态来自 `memoryMapPipeline.snapshot().status`；不改 refresh 算法 |
| Dreaming | Coordinator 增 active/state 只读快照；Runtime 组合自身 RUNNING/CLOSING/CLOSED 为 RUNNING/STOPPED，NEW 为 IDLE；无历史失败事实 lastFailure=null |
| LLM | `LlmCallStatistics` 增全量不可变快照，每条在各自 counters 锁内复制；`KtorLlmClient` 增只读快照转发，关闭不重建记录。无记录列表为空，不创建假条目 |
| Backup | 直接读取现有 `backupStatusReader`；不得把原 lastError 自由文本复制到 Admin DTO |

第三方未知字段、未配置能力、零真实记录必须可区分；getStatus() 原有无 I/O/路径/兼容行为不变。

### 1.5 Backup 最小错误阶段（D7）

仅在 `BackupRuntime.kt`：

- 新增 internal 只读 `failureStage(failure)` 函数/映射，透出当前真实 `BackupStageFailure.stage` 及 `ContextBackupException` 的 PUBLISH/POST_CHECK/PRE_CHECK 分类；取消阶段为 null。
- 必要时把 private `BackupStageFailure` 调整为 internal 只读受控类型，使 B2b 可识别阶段；不得把 cause/message 输出到 HTTP 或新日志。
- 保留现有 Snapshot/Pre-check/Post-check/Publish 分类与成功流程、发布时点、清理、partial failure、quick_check 语义；外部 CE 原样传播，WebDAV 拒绝不降级普通写入。
- 不引入虚构 `BackupConfig.enabled`，不改 BackupConfig/ContextConfig/Factory。

## 2. 精确生产写集合与开工 SHA

以下 SHA 已由 Lead 在委派前复核，均与 Architect §2 一致。路径前缀省略为各模块的 `src/main/kotlin/com/github/noahshen/alcyone/context/memory/`。

| 文件 | 开工 SHA-256 |
| --- | --- |
| `context/api/.../api/AlcyoneContext.kt` | `6f326b0fee0300481a84879f5194b1069a30bcdd1fe693a6e5c8a9d055f25717` |
| `context/api/.../api/ContextAdminStatus.kt` | 新增 |
| `context/core/.../core/repository/OrganizationPendingReadOps.kt` | `0c76c889e6a10eef3e7180ec41b1381ec4bbd17fa91e34c6dab698ebbb1e0e6c` |
| `context/persistence-sqldelight/.../sqldelight/ContextSqlQueries.kt` | `cb82d3e6245231e9f30dfec23db1190a48ed44f708186fefe123da081686c5df` |
| `context/persistence-sqldelight/.../sqldelight/ContextOps.kt` | `6915d2817127dab1fcb757b9bcca8f40e5c239cb356d04c445cffa1ceac1c060` |
| `context/runtime/.../runtime/AlcyoneContextImpl.kt` | `7af81cb1d730070a369bb0b92e5b6495b7670765ead5005bc29a1d2ef5d6ca23` |
| `context/runtime/.../runtime/llm/LlmCallDiagnostics.kt` | `4c838195e6d115ded4cfd00757c00bb0307536b3ef88c1acaece3c1a18b4e84f` |
| `context/runtime/.../runtime/llm/KtorLlmClient.kt` | `f56153372dfc5710963efccb3db9335f0b1d6dd848f45ceb01b77e90956aebf5` |
| `context/runtime/.../runtime/extraction/ExtractionScheduler.kt` | `4fe977006d562069fafbb3e489fc160bd62d0b465f3f15159872e093e7985c09` |
| `context/runtime/.../runtime/extraction/ExtractionPipeline.kt` | `9b898911712a4868658f84839ed4003c3e6d212dc457daf827499c6efa86757d` |
| `context/runtime/.../runtime/organization/OrganizationScheduler.kt` | `5c9379af36a853278b1e374b1f3279c4cd9b54a39e93592dc4d45af2b6aba1e6` |
| `context/runtime/.../runtime/organization/OrganizationPipeline.kt` | `48350dbf97b74d6dfb37de51daeacd2abb46244d2e921cedbc1e163626592031` |
| `context/runtime/.../runtime/dreaming/DreamingRuntime.kt` | `6542de5185cf850c592d1d8dc3b71fa1180cdf9af9866e99130e45912d58bf1e` |
| `context/runtime/.../runtime/dreaming/DreamingRunCoordinator.kt` | `cbd5d425f6b5700eff362ead4bdd4041c8a6f86e5ef622e7c167e6a31f8d9ab3` |
| `context/runtime/.../runtime/backup/BackupRuntime.kt` | `fc7a55637ee13eab44f1e8177bd2f850ba8b0dc8994129b0a0a88f2b258a6870` |

开工先重算 SHA；不一致立即停止报告，不覆盖并发修改。**B2a 禁止修改其余生产文件**，特别是 `context/server/**`、`AlcyoneContextFactory.kt`、`MemoryMapPipeline.kt`、`MemoryMapService.kt`、`ContextConfig.kt`、`BackupConfig.kt`、所有构建文件。

Schema 保护：`context/persistence-sqldelight/src/main/sqldelight/com/github/noahshen/alcyone/context/memory/persistence/sqldelight/ContextSchema.sq` 必须保持 `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`。

## 3. 测试写集合与正式断言

### 3.1 允许新增

- `context/api/src/test/kotlin/com/github/noahshen/alcyone/context/memory/api/ContextAdminStatusTest.kt`
- `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/repository/OrganizationPendingReadOpsDefaultTest.kt`（第三方默认兼容；Core 不加实现依赖）
- `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextReadyTest.kt`
- `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextAdminStatusTest.kt`

### 3.2 允许定点扩充（开工 SHA）

| 测试文件 | 开工 SHA-256 / 新断言 |
| --- | --- |
| `context/persistence-sqldelight/src/test/.../ContextOrganizationPendingRealDbTest.kt` | `ecab70244dbf7f92696258baa76410c0bc0dd07a715b777bef023ffa38c9086e` |
| `context/runtime/src/test/.../extraction/ExtractionSchedulerTest.kt` | `e4aab35505868842e66221996a7a97ae9fde0631cd773660bfb3dbeef50ac0b4` |
| `context/runtime/src/test/.../extraction/ExtractionSchedulerCloseTest.kt` | `58b671ac087c8c4439be713c323346edbcb58772501b3e14522438e3e4c15e9a` |
| `context/runtime/src/test/.../organization/OrganizationSchedulerTest.kt` | `e55f23761d4329017d95b9d25f058384adf2e2a7ef561194d80061702b1dd976` |
| `context/runtime/src/test/.../dreaming/DreamingRunCoordinatorTest.kt` | `e3d0a0c766c5536f7c13e58f8742e40d9899de35563970db955d114882875fdb` |
| `context/runtime/src/test/.../dreaming/DreamingRuntimeTest.kt` | `d493ae3ddbc57fd412a909be67f78c9a7cee0e3c4df7f20b3ceee181424f1d4c` |
| `context/runtime/src/test/.../llm/KtorLlmClientDiagnosticsTest.kt` | `ece777b81bc59aa9816c76d7c7b468478dc6cf5366ae8a9938bd5a140e886cac` |
| `context/runtime/src/test/.../backup/BackupRuntimeTest.kt` | `2e7b4aa79c748b211ee0d1b97e0e77a6b71e8c4258d653cec3ce19b443b6a5b6` |

Memory Map 组合断言放入新 `AlcyoneContextAdminStatusTest.kt`；不必修改既有 Map 测试。若确需新测试 helper，仅限上述测试包内新增且先报告，不改生产/构建。

### 3.3 必测矩阵

1. **API/default**：第三方 `checkReady=false`；Admin 只有已知 lifecycle，未知字段 null；原 `getStatus` 仍无 I/O；DTO 集合防御性复制，字符串不含秘密/path/Throwable 文本。
2. **Pending**：空表 0、多条、Read/Tx 同事务一致（可用 Tx 内写后读及外部不可见证明同一快照）；第三方默认 count 调 list；生产 Read/Tx 实际 COUNT SQL；恰好一行、null/负值/Long 溢出均受控失败。可用 package-private reader + controlled `ConversationQuery` 精确覆盖 SQLite 正常 COUNT 不易产生的坏行，但生产成功路径必须真实 SQLite。
3. **Ready**：CREATED/STARTING/RUNNING/STOPPING/CLOSED；RUNNING+真实 SQLite 读成功 true；真实 DB 故障 false；查询前后关闭竞争 false；外部 CE 传播；Pending 非空、Map 非 CURRENT、LLM 失败仍 ready；受控 fake client/VFS 证明零 LLM/VFS 调用。
4. **Admin**：真实 SQLite Pending/checkpoint；Extraction/Organization/Map/Dreaming worker 各状态来源；Map 四态不由 ready 推断；LLM 未配置 null/零记录空列表/usage 缺失 token null/多条不可变快照；Backup 未配置 null/已配置真实 reader；DB 失败整次失败不伪零；自由异常文本/path 不进 DTO。
5. **Worker**：Extraction 普通 Runner 失败不 FAILED、真正执行 RUNNING、非关闭 CE FAILED、正常 close STOPPED；Organization 原状态算法不变；Dreaming active run RUNNING、closing STOPPED、无历史失败 lastFailure=null。
6. **Backup**：受控 Snapshot/Pre/Publish/Post-check 失败通过 internal failureStage 精确映射；quick_check 与 publish 原错误码不变；取消 null；成功、清理与已发布对象保护既有测试全回归。

所有关键成功路径真实 Runtime/SQLite；Fake 只用于精确故障/计数分支，不冒充真实模型。不得用 sleep 作为并发完成证据，使用 Deferred/latch/状态闸门，有界等待。

## 4. 验证、日志与交付证据

Worker 完成后必须执行，不接受 UP-TO-DATE 作为通过证据；使用项目工具链，禁止改全局 JDK/Gradle：

```bash
mkdir -p /tmp/ct28-b2a
./scripts/dev gradle spotlessApply --console=plain --no-build-cache \
  > /tmp/ct28-b2a/spotless.log 2>&1
./scripts/dev gradle \
  :context:api:test :context:core:test \
  :context:persistence-sqldelight:test :context:runtime:test \
  --console=plain --no-build-cache --rerun-tasks \
  > /tmp/ct28-b2a/tests.log 2>&1
```

- **本批不跑 `./scripts/check` 全仓**，留 B2c 最终验收；不跑真实收费模型、WebDAV 或生产部署。
- 保存上述日志；交付四模块 `build/test-results/test/TEST-*.xml` 实际 totals，逐模块核对 source `@Test` = XML tests，failures/errors/skipped=0。Spotless 后不得遗留未格式化改动。
- 交付新增/修改文件精确清单与终态 SHA；逐条给 D2～D7/D14 测试类名、方法名和行号；说明 SQL/Schema、Core 无 Ktor/serialization、Server 零改动、生产依赖零改动。
- 特别保存 Pending SQL、Ready/Admin 关闭竞态、worker 失败来源、LLM null-vs-empty、Backup stage 的断言位置与 XML 证据。
- 发现接缝缺口/基线 SHA 变化立即停止报告；不得静默扩大写集合或自行批准 B2b/c。

## 5. 明确不做

- 不实现 `/v1/admin/**`、`/health/**`、`readyTimeoutMillis`、managed Server；不改任何 Server 文件。
- 不修改调度、检索、提交、Memory Map 刷新、Backup 发布/quick_check/清理算法。
- 不修改 Schema、索引、迁移、Prompt、构建依赖、vfs/common；不做 Git 写操作，不 reset/checkout/clean，不覆盖既有脏树。
- 不宣称 CT28/B2 DONE；B2a 须 Lead 独立初验后才可考虑 B2b，最终 DONE 由 Architect 裁定。
