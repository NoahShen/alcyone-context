# CM4 范围、CT17～CT21 依赖与待裁定问题（方案，不开发）

**日期**：2026-11-08
**状态**：**CM4 只读方案完成；CT17 待 Architect 裁定，未派发 Worker；CT18～CT21 不启动实现**
**依据**：技术实现设计 §7.1～§7.10、§9.1、§10；核心数据流 §15～§16；架构设计 §4.4、§4.6、§5.4、§6；[开发计划](../../开发计划.md) §CM4/§5；[CM3 整体复核](../cm3/milestone-review.md)
**基线**：CM1、CM2、CM3 均 DONE；`./scripts/check` 131 tasks、14 模块 **1653=1653**、81 VFS 基线 all OK；Schema SHA `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；Map 仍为 no-op。当前工作区含预期 CM3 未提交交付，不得回滚、丢弃或执行 Git 写操作。

## 0. 本次修改（2026-11-08，CM4 只读方案）

用户已明确授权启动 CM4（仅 CT17～CT21，不启动 CM5/CM6）。本文件只核对范围、依赖、API/配置/Prompt/Schema/VFS 缺口并形成待裁定问题；CT17 细节另见 [CT17 方案核对](../../tasks/cm4-ct17/方案核对.md)。

- 本轮不实现 CT17～CT21，不新增生产/测试代码，不改 VFS/common/Schema/依赖，不运行真实模型。
- CT17 方案未获 Architect 批准前不派发 Worker；CT17 未 DONE 前不进入 CT18。
- CT18～CT21 只能按逐任务方案、逐批复核推进；不得因本文件存在而自动开工。
- 无 git add/commit/push/stash/分支/发布操作。

## 1. CM4 目标与边界

CM4 完成 Memory Map 与 Context Retrieval 的首版闭环：

```text
Organized Memory（SQLite 权威事实）
  → Map 固定快照 / T1 / LLM Overview+Navigation / Core Front Matter
  → VFS 安全发布 alcyone://memory/map.md
  → Organization 通知 / Runtime 初始化 / Cron 补偿
  → Bootstrap 返回 Map 与四态

Agent
  → Bootstrap（概览与导航，不查水位、不调 LLM）
  → Search（Organized 全量扫描、显式 Domain、全局排序）
  → Get / GetRaw（SQLite 精确读取，不调 LLM）
  → Resource Meta / Read / ReadStream（只读 alcyone://resources/...）
```

**CM4 范围**：SDK 逻辑能力、Map 生成与刷新、Bootstrap、Search/Get、Resource 只读访问、Fake/真实 Runtime 集成和固定集评测归档。

**明确不在 CM4**：

- Dreaming、Online Backup、完整 HTTP 权限/路由、部署、系统级故障恢复属于 CM5/CM6。
- 不新增 FTS/Embedding/向量库、Resource 语义索引、Recent Memory View、多 Map 文件、Full Memory、独立 Map Job/Revision、SQLite↔VFS 强事务。
- 不新增逐 Agent/Domain ACL；MVP 只做可信调用者基础边界与 Resource URI 范围检查。
- 不物理删除 Organized Memory，不建立 Organized 历史版本。
- 不运行真实模型；固定样本和 Fake 只能证明协议、预算、事务与确定性行为，不能冒充语义质量通过。

## 2. 任务依赖与放行门

| 任务 | 依赖 | 主要能力 | 放行门 |
| --- | --- | --- | --- |
| CT17 | CM1 VFS、CM3 DONE、CT01 G3/G4 记录 | VFS 安全替换；Resource Meta/ByteArray/Flow 读取 | Architect 先批准 VFS 公共能力与失败语义；Resource 不依赖 Map |
| CT18 | CT17 DONE | Organized 全量快照、T1、Map Prompt/校验、Core Front Matter、安全发布 | CT17 的 LocalFs 安全替换通过真实后端与失败注入 |
| CT19 | CT16 DONE、CT17 DONE、CT18 DONE | Conflated 单 Worker、Organization/初始化/Cron、四态、Bootstrap | CT18 能稳定生成/发布且失败保留可判定状态 |
| CT20 | CT13 Scanner 经验、CT17 DONE | Search 全量扫描、显式 Domain、跨批全局排序、精确 Get/GetRaw | 全局排序契约冻结；Search 不返回部分结果 |
| CT21 | CT17～CT20 DONE | SDK 闭环、发布/读取交错、取消清理、stale 补偿、固定集评测 | 不运行真实模型时明确 `not-run`，不得宣称语义通过 |

建议保持串行 `CT17 → CT18 → CT19 → CT20 → CT21`。CT20 虽可在代码上部分并行，但会同时修改 `AlcyoneContext` 公共契约、Runtime 配置与 Factory/Impl，串行可降低公共 API 和生命周期冲突。

## 3. 已核实事实（当前源码）

| # | 事实 | 证据 |
| --- | --- | --- |
| F1 | `AlcyoneContext` 目前只有 ingestion 与生命周期方法；CM4 的 Bootstrap/Search/Get/Resource 方法均未声明 | `context/api/.../AlcyoneContext.kt` |
| F2 | `BootstrapContext`、`MemoryMapView`、`MapStatus`、`ResourceMetadata` 均不存在；公开模型必须落在 `context/api` | `context/api/src/main/kotlin`、CT01 实施契约 |
| F3 | `OrganizedMemory` 已有稳定六字段；`RawMemory` 已有 id/content/generatedBy/sources/createdAt | `context/api/.../OrganizedMemory.kt`、`RawMemory.kt` |
| F4 | `OrganizedMemoryReadOps/Repository` 已有 `findById(s)`、`maxUpdatedAt()`、`scanPage(offset,limit)`；`RawMemoryReadOps/Repository` 已有 `findById(s)` | `context/core/.../repository` |
| F5 | `scanPage` 当前按 `created_at ASC,id ASC` 分页；单次 `database.read` 可形成一致快照，读回调不可挂起 | `OrganizedMemoryReadOps.kt`、`ContextDatabase.kt`、CT13 已验收实现 |
| F6 | `LlmTask.MEMORY_MAP`、`MEMORY_SEARCH` 已存在；`LlmBudget`、`ConservativeTokenEstimator`、CT13 `TokenBatchPlanner` 可复用 | `core/llm/LlmClient.kt`、`runtime/llm/LlmBudget.kt`、`runtime/organization/TokenBatchPlanner.kt` |
| F7 | Core Prompt 资源目前只有 Extraction 与 Organization；没有 Map/Search Prompt/Schema | `context/core/src/main/resources/prompts/` |
| F8 | `ContextConfig` 无 `memoryMap` / retrieval 字段；Loader 把 `context.memoryMap` 当作已知但未消费子树，不校验、不展开 ENV | `runtime/ContextConfig.kt`、`ContextConfigLoader.kt` |
| F9 | Organization 提交后已有 `OrganizationMapWakeup` internal 接缝，生产默认 no-op | `runtime/organization/OrganizationMapWakeup.kt`、`AlcyoneContextImpl.kt` |
| F10 | `ContextStatus.workers` 可报告 Organization；尚无 map worker 状态或 MapStatus 缓存 | `ContextStatus.kt`、`AlcyoneContextImpl.getStatus()` |
| F11 | VFS `write(REPLACE_EXISTING)` 写入阶段失败可为 `UNKNOWN`；`move` 明确拒绝已有目标；没有 `safeReplace`/`moveOrReplace` | `vfs/api/Vfs.kt`、`DefaultVfs.kt`、`LocalFsStorage.kt`、`WebDavStorage.kt` |
| F12 | VFS `openStream` 返回必须关闭的 `VfsStreamResult`；`read` 默认受 16 MiB 可配置限额，流式总量默认不设限但可配置 | `Vfs.kt`、`VfsStream.kt`、`VfsRuntimeConfig.kt` |
| F13 | VFS 无 MIME 检测；`StorageStat` 只有 size/modifiedAt，`NodeInfo` 提供 type 与逻辑时间 | `NodeInfo.kt`、`StorageStat.kt` |
| F14 | Context 目前允许空 namespaces/mounts 以保持 CM1 最小配置兼容；CM4 所需的 `memory`/`resources` 挂载尚未做功能级门禁 | `MountRouter.of`、`ContextConfigLoaderTest`、CT01 R1 |
| F15 | `cron-utils`、coroutines、SnakeYAML 已在 Runtime 依赖中；Core 无 Ktor/serialization；无需为 CM4 新增第三方依赖 | 各 `build.gradle.kts` |
| F16 | `Vfs.stat` 默认可能懒注册 Node 并写 VFS 状态库；`read/openStream` 不注册 Node | `Vfs.kt`、`NodeRegistry`、`DefaultVfs.stat` |

## 4. CT17～CT21 范围与缺口总表

| 任务 | API / 配置 / Prompt / Schema / VFS 缺口 | 建议产出 | 明确不做 |
| --- | --- | --- | --- |
| CT17 | **VFS**：无安全替换；WebDAV 无一致原子替换保证。**API**：无 `ResourceMetadata`、Resource 异常、`loadMeta/read/readStream`。**配置**：复用 VFS 限额，不新增业务配置。**Prompt/Schema**：无 | VFS 能力受控的 `safeReplace`；Resource URI 范围与错误映射；冷 Flow 读取和关流测试 | Map 正文生成、Front Matter、Bootstrap、Search/Get |
| CT18 | **Core/Runtime**：无 Map 快照生成器、Prompt、JSON/正文校验、Front Matter 渲染/解析、发布器。**配置**：`memoryMap` 未解析。**VFS**：依赖 CT17 | Map 固定快照、T1、Prompt v1/Schema v1（若选 JSON 信封）、Core Markdown 渲染、Validator、`MemoryMapPublisher` | 刷新协调、Cron、Bootstrap、Search |
| CT19 | **Runtime**：无 Conflated Worker、初始化检查、Cron、Map 状态缓存。**API**：无 Bootstrap 类型/方法。**配置**：refresh 子树未消费。**Prompt/Schema**：无新增 | 单 Worker、三类唤醒、四态、Bootstrap 只读缓存、生命周期接线、workers 状态 | 生成规则本身、Search/Get |
| CT20 | **API**：无 Search/Get/GetRaw 方法；无 retrieval 异常。**Prompt/Schema**：无 Search 扫描/全局重排资源。**配置**：默认 limit/最大 limit 未定义。**Repository**：可复用现有查询，不需要新表 | 全量扫描、显式 Domain、两阶段全局排序、SQLite 权威回读、精确 Get | FTS/Embedding、Resource 语义搜索、HTTP 路由 |
| CT21 | **集成**：无 CM4 SDK E2E；无 Map/Search 固定样本和版本归档。**证据**：真实模型未运行 | SDK 学习→导航→Search/Get→Resource 集成；发布/读取交错、取消、stale 补偿；Fake 评测与 `real-model: not-run` | 真实模型质量结论、部署/备份/Dreaming |

## 5. CT17 关键前置：G3 与 G4

### 5.1 G3：Map 安全发布

**结论**：现有公开 VFS API 不能完成安全替换。

- `move(temp,map.md)` 在目标存在时返回 `ALREADY_EXISTS`，因此“先删旧 Map 再 move”明确禁止。
- `write(map.md, REPLACE_EXISTING)` 是原地覆盖；后端写入阶段失败只能报 `UNKNOWN`，不保证旧文件仍可读。
- 跨 Mount 复制移动无法降级为安全发布。
- Context 直接使用 `java.nio.file`、OpenDAL、物理路径或临时文件改名会绕过 VFS 所有权，明确禁止。

**推荐方案**：在 VFS 增加显式 `safeReplace(uri, content)` 能力；Local FS 使用同挂载临时文件 + 原子替换，WebDAV 在未取得一致性证明前返回 `UNSUPPORTED_OPERATION`，不得退化为删除后改名或普通覆盖。

**必须裁定的失败语义**：SQLite/VFS 无跨存储事务。若物理原子替换已经成功、随后 VFS Node/Event 状态提交失败，旧 Map 已无法保证恢复；此时只能报告 `STATE_ERROR + PARTIAL`，但文件内容仍是完整的新版本。开发计划中的“失败保旧”需要明确是否仅指原子提交前失败。该问题未裁定前，CT17 不能宣称 G3 关闭。

### 5.2 G4：Resource 字节与流式读取

**结论**：现有 VFS 已具备所需读取原语，缺口在 Context 适配层。

- `read(uri)` 可通过 VFS 的 ByteArray 读取落实默认 16 MiB 可配置上限。
- `readStream(uri): Flow<ByteArray>` 必须冷启动、分块读取、完成/异常/取消均关闭 `VfsStreamResult`。
- URI 必须用 `VfsUri.parse` 严格解析，再校验首段恰为 `resources` 且有实际资源路径；禁止字符串前缀判断。
- 当前底层 OpenDAL/native 读可能阻塞；VFS `close` 会等待当前读取返回后再释放。可承诺“取消后请求关闭且不泄漏句柄”，不能承诺立即中断正在阻塞的 native 调用。若要求硬实时取消，必须另立 VFS 能力变更。

## 6. 建议的公共 API / 配置 / 资源布局

### 6.1 公共 API（逐任务增量）

```kotlin
// CT17
suspend fun loadMeta(uri: String): ResourceMetadata
suspend fun read(uri: String): ByteArray
fun readStream(uri: String): Flow<ByteArray>

// CT19
suspend fun bootstrap(): BootstrapContext

// CT20
suspend fun searchMemory(
    query: String,
    domains: List<String>? = null,
    limit: Int = 10,
): List<OrganizedMemory>

suspend fun getMemory(id: String): OrganizedMemory?
suspend fun getRawMemory(id: String): RawMemory?
```

- 所有公开模型直接归属 `context/api`；Core/Runtime 不得把 SQLDelight、Ktor、OpenDAL、JDBC、物理路径类型放入签名。
- `readStream` 位于 API 接口，`context/api` 已 `api(coroutines)`，无需新增依赖。
- Map 的 `BootstrapContext/MemoryMapView/MapStatus` 在 CT19 接线前加入；不要提前放空实现或假成功。

### 6.2 建议配置

```yaml
context:
  memoryMap:
    refresh:
      periodic:
        enabled: true
        cron: "*/10 * * * *"
```

- `context.memoryMap` 缺失时建议不建 Map pipeline，保持当前无 LLM、空 VFS 配置兼容；出现即显式启用，空映射使用默认 refresh 参数。
- Cron 建议沿用固定 `Asia/Shanghai`；若继续拒绝 `timezone` 键，需 Architect 明确这是对技术设计示例的收敛。
- Resource 读取不新增业务配置，复用 `vfs.limits.defaultReadMaxBytes` 与 `vfs.streamTotalLimit`。
- Search 的默认 `limit=10` 与最大值、Domain 空列表语义、`context.retrieval.search` 是否需要配置，均在 CT20 前裁定。

### 6.3 Prompt / Schema 资源

| 任务 | 建议资源 | 说明 |
| --- | --- | --- |
| CT18 | `memory-map-v1.md`、`memory-map-schema-v1.json`（若采用 JSON 信封） | LLM 只返回 overview/navigation；Core 渲染标题与 Front Matter |
| CT20 | `memory-search-v1.md`、`memory-search-schema-v1.json` | 批内候选筛选，只允许输入 ID |
| CT20 | `memory-search-rerank-v1.md`、`memory-search-rerank-schema-v1.json` | 对去重后的全部候选项做一次全局相关性排序 |

- Prompt 资源版本化放 Core classpath；资源缺失/空白明确失败，不静默空串。
- 输入正文一律视为数据而非指令；程序级 ID 权限边界不能只靠 Prompt。
- 若 Architect 决定 Map 直接输出 Markdown，则 Map 可不使用 JSON Schema，但仍需确定性 Core Validator；该选项增加围栏/标题解析风险。

## 7. CT18：Map 生成建议

1. 单次短 `database.read` 分页读取全部 Organized，按 `id ASC` 排序，并在同一 ReadContext 读取/核对 `MAX(updated_at)=T1`；退出 ReadContext 后才调用 LLM。
2. 空库行为须裁定；推荐不调 LLM，发布确定性空 Map，并显式写入 `sourceUpdatedAt: null`。
3. 全部 Organized 都必须进入请求；固定开销或全量输入超预算则 `LOCAL_BUDGET_EXCEEDED`，不截断、不分批、不发布。
4. LLM 只产出 Overview/Navigation；Core 严格校验非空、长度、Domain 真实性、不伪造事实/Resource URI，并生成 Front Matter。
5. 发布时间使用注入 Clock 的上海毫秒格式；生成期间新 Organized 写入不改 T1，不二次查询 T2，不引入发布锁。
6. 任何生成/校验/安全发布失败保留可读取的上一版；发布成功后才更新 Map 缓存与水位状态。

**Front Matter 建议**：仅允许 `generatedAt`、`sourceUpdatedAt` 两键；时间格式固定上海 `+08:00` 毫秒；空库写显式 `sourceUpdatedAt: null`；缺字段、重复键、非法时间、未知字段视为破损，不把缺失当有效水位。解析实现应保持在 Core，不引入 SnakeYAML 到 Core。

## 8. CT19：刷新与 Bootstrap 建议

- `Channel<Unit>(CONFLATED)` + 唯一 Consumer；生成期间新信号最多保留一个，下一轮先比较水位。
- Organization 轮次结束合并一次通知；Runtime `start()` 做一次轻量水位检查并异步唤醒；Periodic Cron 默认开启，只做水位比较，不在 Cron 内等待 LLM。
- 启动时 Map Worker 与业务 activation 分离：发布 RUNNING 前不得生成/发布；关闭时先封闭准入，再停 Cron、取消并 join Worker。
- Bootstrap 只读 Map 模块缓存，不查 SQLite 水位、不调 LLM、不发任务；返回已有 Map 时状态必须是 `CURRENT/STALE/UNKNOWN` 之一。
- 建议状态：无文件 `MISSING`；合法 Map 且最近可靠比较相等 `CURRENT`；不等 `STALE`；Map 破损或数据库/水位不可得 `UNKNOWN`。破损 Map 不得返回为可用内容。
- `capabilities` 为固定静态说明，不包含动态 Memory 内容。

## 9. CT20：Search/Get 建议

### 9.1 Search

1. 单次 `database.read` 分页读取全部 Organized，按 `id ASC`；`domains=null` 表示不过滤，显式列表按调用方值过滤，不根据 Agent/Profile 推断。
2. 空库或无 Domain 匹配直接返回空，不调 LLM。
3. 使用 `MEMORY_SEARCH` Task/Profile、独立 Prompt/Schema，复用动态预算分批、批内 ID 校验、串行调用与截断失败规则。
4. 所有扫描批次成功后去重候选，再做**一次全局重排**，不能按批各取 TopN 后拼接。全局重排输入超预算时明确失败，不返回部分或静默截断。
5. 按重排 ID 从 SQLite 权威库短读真实 Organized；不接受模型返回的正文/URI。输出按全局相关性顺序取 `limit`。
6. 不额外做 T1/T2 强一致检查；并发更新允许返回短暂旧快照，需要最新值时调用 `getMemory`。

**必须裁定**：全局重排采用独立第二阶段 LLM，还是让批内 Scanner 输出可跨批比较的分数。推荐独立重排，因为分批模型的分数不天然可比。

### 9.2 Get / GetRaw

- 直接调用现有 Repository，无 LLM；找不到返回 `null`，数据库故障抛具名错误，不把故障伪装为不存在。
- 输出复用现有 `OrganizedMemory` / `RawMemory`，不新增存储模型、不改 Schema。

## 10. CT21：集成与评测建议

- 真实 Runtime + 真实 Context SQLite + 真实 VFS LocalFs + Fake LLM HTTP stub；覆盖 Conversation→Extraction→Organization→Map→Bootstrap→Search/Get→Resource。
- Map 发布与读取交错：读取只能看到完整旧版或完整新版，不出现半写 Markdown；生成期间补信号由下一轮追平。
- Flow 在正常完成、读取异常、采集端取消、Context 关闭时均关流；测试 VFS 真实 `trackedStreamCount` 或受控 Fake 计数。
- 固定脱敏样本分为 Map 覆盖/不确定性保留与 Search 相关性；记录模型/Profile、Prompt/Schema SHA、样本版本。
- 本轮及无授权环境真实模型标记 `not-run`；Fake 结果单独归档，不填“真实语义通过”。

## 11. 验收命令与边界

每批至少执行：

```text
./gradlew :vfs:api:test :vfs:core:test :vfs:storage:test :vfs:runtime:test
./gradlew :integration-tests:vfs:test
./gradlew :context:api:test :context:runtime:test
./gradlew :integration-tests:context:test
./scripts/check
```

证据要求：

- 14 模块源码行首 `@Test` 与 XML 数量一致，失败/错误/跳过全 0。
- `./scripts/check` exit 0；任务数相对当前 131 的变化必须解释。
- 81 项 VFS 基线 all OK。CT17 起 VFS diff **不再要求为空**，但只允许 Architect 批准的 G3 文件；common/Schema/`user_version` 仍必须未变。
- Context Schema SHA 保持 `ec727a53…8ea3`；Core compileClasspath 仍无 Ktor/serialization。
- 静态审查：Map 发布路径只能调用 VFS 安全替换，不得出现 Context 直写物理文件、先删后 move、普通 `REPLACE_EXISTING` 冒充安全发布。
- `git diff --check` 为空；不得把 Fake、资源缺失或 WebDAV 未验证写成生产能力已通过。

## 12. 待 Architect 裁定问题

| # | 问题 | 推荐 | 风险/影响 |
| --- | --- | --- | --- |
| Q1 | G3 采用显式 `Vfs.safeReplace`，还是扩展现有 `WriteMode` | **显式 API**，避免普通写误用安全语义 | 修改 VFS 公共接口与 core/storage/runtime |
| Q2 | LocalFs 安全替换如何实现 | 同挂载临时文件 + 真实原子 replace；不得删目标或走 VFS move | 需后端探针、失败注入、临时文件清理 |
| Q3 | WebDAV 是否必须支持 Map 安全替换 | **首版不承诺**；无逐服务证明时 `UNSUPPORTED_OPERATION` | 若用户要求 WebDAV Map，需要新增远端协议兼容/验证范围 |
| Q4 | “失败保旧”是否包含物理替换后 VFS 状态提交失败 | 明确为“原子提交前失败保旧；提交后状态失败报 PARTIAL，但内容完整” | 当前定义不一致会阻断 CT17 验收 |
| Q5 | `safeReplace` 是否允许首建、缺失父目录、目标目录、未注册文件 | 允许首建/补父目录；目录拒绝；未注册文件可替换并登记 | 影响 Node/Event 与 Map 首次发布 |
| Q6 | Resource 是否只允许 `/resources/<non-empty>` 文件 | **是**；`loadMeta/read/readStream` 统一文件边界 | 目录/Meta 能力若需要须显式扩展 |
| Q7 | `ResourceMetadata` 字段 | 最小 `uri/sizeBytes/modifiedAt`；不伪造 MIME | 设计提到 MIME 提示，但 VFS 无 MIME |
| Q8 | Resource 错误是否新增 `ContextResourceException` | **是**，具名 INVALID_URI/OUT_OF_SCOPE/NOT_FOUND/LIMIT_EXCEEDED/UNAVAILABLE/CLOSED | 不让上层依赖 VFS/OpenDAL 异常 |
| Q9 | `readStream` 取消的硬实时性 | 承诺取消后关闭且不泄漏；不承诺打断阻塞 native read | 硬实时取消需另立 VFS 能力 |
| Q10 | `context.memoryMap` 缺失是否禁用 Map | **是**，保持最小配置兼容；出现即启用 | 与技术设计“默认启用”字面存在差异 |
| Q11 | Memory Map Cron 时区 | 固定 `Asia/Shanghai`，拒绝 timezone 键 | 与 CT16 一致；偏离示例中的可配置字段 |
| Q12 | Map LLM 输出 JSON 信封还是直接 Markdown | **JSON `{overview,navigation}`**，Core 渲染 Markdown/Front Matter | 若必须 PROMPT_ONLY/Markdown，Validator 复杂度上升 |
| Q13 | 空库 Map 行为 | 发布确定性空 Map，`sourceUpdatedAt: null`，零 LLM | 若不发布，Bootstrap 长期 MISSING 语义需另定 |
| Q14 | Map 破损状态 | 返回 `UNKNOWN` 且不返回破损内容；无文件为 `MISSING` | 防止把不可解析文件当 CURRENT |
| Q15 | Search 全局排序方式 | 扫描后对去重候选做一次独立全局重排；超预算明确失败 | 增加一次 LLM；批内分数方案可比性不足 |
| Q16 | Domain 空列表、limit 上限、默认值 | `null` 不过滤；空列表按无过滤；limit >0，上限待定 | 公共契约一旦发布难改 |
| Q17 | Search 缺少 MEMORY_SEARCH 绑定时何时失败 | 建议调用时具名配置错误，不阻塞 Get/启动；若配置显式 search 则 create 时失败 | 需与“不完整绑定不静默”原则统一 |
| Q18 | `loadMeta` 是否接受 VFS 懒注册副作用 | 建议接受内部 Node 懒注册，但不发文件变更事件、不改 Resource 内容；若要求严格零状态写，需另定非注册 stat 能力 | 影响公共“只读”语义与 VFS API 范围 |

## 13. 建议的 CT17 拆分

### CT17 B1：VFS 安全发布能力（阻塞项）

- 白名单：`vfs/api`、`vfs/core`、`vfs/storage`、`vfs/runtime` 及对应测试；仅 Architect 批准文件。
- 新增受能力约束的 `safeReplace`；LocalFs 同挂载临时文件 + 原子 replace；WebDAV 明确不支持，不降级。
- 覆盖首建/替换、旧文件保旧（提交前失败）、无删除后 move、状态失败 PARTIAL、目标目录、只读/跨 Mount、限额、取消、临时文件清理、Node ID/Event、真实 LocalFs 重开。
- 不改 Context、Schema、依赖、common。

### CT17 B2：Context Resource 访问适配

- 白名单：`context/api`、`context/runtime`、`integration-tests/context` 及对应测试。
- 新增 `ResourceMetadata`、Resource 错误、`loadMeta/read/readStream`；严格 `/resources/...` URI 范围；复用 VFS 限额。
- Flow 覆盖冷启动、多次采集、64 KiB 分块、正常完成、异常、采集取消、Context 关闭、零长度文件、超限、NOT_FOUND、目录、越界 URI、关流失败优先级。
- 不实现 Map/Search/Get，不改 Prompt/Schema/数据库。

### CT17 收口

- Lead 独立定向、真实 LocalFs 集成、全量 `./scripts/check`、14 模块计数、81 VFS 基线、Schema/Core/依赖/物理写边界。
- Architect 复核 G3/G4 与文件白名单；通过后才允许进入 CT18 只读方案/实现。

## 14. 结论与下一步

- CM4 的主要新增面是 VFS 安全发布、Map 生成/刷新、Bootstrap、Search/Get 与 Resource Flow 适配；现有 SQLite Repository、LLM 预算与 Scanner 组件可大量复用。
- CT17 的 Resource 适配可独立推进，但 G3 的安全替换属于 VFS 公共能力变更，且失败保旧语义必须由 Architect 明确，因此 **CT17 当前存在必须裁定的阻塞项**。
- 下一步仅提交本文件与 [CT17 方案核对](../../tasks/cm4-ct17/方案核对.md) 给 Architect；未获批准前不派发 Worker、不创建 CT17 任务书之外的实现、不启动 CT18～CT21。
- 无 git 写操作/推送；真实模型 not-run。




