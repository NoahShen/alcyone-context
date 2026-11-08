# CT18 B1 · Memory Map Generation Worker 交付

## 本次修改（2026-11-08，B1-R1）

按 Lead 初验阻断执行最小 B1-R1：Parser 在 `Json.parseToJsonElement` 前扫描根对象成员名，拒绝重复 `overview/navigation/domains`（含转义等价 key），统一返回 `LlmException(INVALID_RESPONSE)`。新增 1 个重复 key 测试并更新 SHA、计数、命令和 R1 证据；其余 15 个 CT18 白名单文件不变。

## 本次修改（2026-11-08，B1 初始交付）

新增本交付报告，记录 Memory Map 固定快照/T1、Prompt/Schema、严格 Parser/Validator/Renderer、safeReplace Publisher、生成后发布 Service、45 个新增测试、三条验收命令、14 模块计数、Schema/CT17 SHA、81 VFS 基线与白名单边界。本文件不把 B1 或 CT18 标记为 DONE。

**日期**：2026-11-08
**状态**：**B1-R1 提交 Lead 初验；CT18 未 DONE，CT19 未启动**
**依据**：[B1 委派单](B1_委派单.md)、[CT18 任务书](CT18_任务书.md)、[CT18 方案核对](CT18_方案核对.md)、[CT17 收口报告](../cm4-ct17/CT17_收口报告.md)
**基线**：CT17 APPROVED / DONE；14 模块 **1710=1710**；`./scripts/check` 131 tasks；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；既有 CM3/CT17 未提交交付保持不动。

## 1. 交付结论

严格新增 B1 委派单 §3 的 18 个文件：**18 新增 / 0 修改既有文件**。Memory Map 组件已形成单次固定快照生成与安全发布闭环；未接 Factory/Impl/Config/Loader，未新增 `context/api` 公共方法、Schema/SQL、依赖、构建或 VFS 变更。

CT18 B1-R1 后新增 46 个测试。最终 14 模块源码 `@Test` = XML = **1756**，fail/error/skip = 0/0/0。定向、Runtime Spotless、全量 `./scripts/check` 均通过；真实模型 not-run，无 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 Core 生产与资源（6）

| 文件 | SHA-256 |
| --- | --- |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/map/MemoryMapDocument.kt` | `c0fc7deab14285a1d53b3c76906839e87beee98f808932219f81e49d2b0ba288` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/map/MemoryMapPrompt.kt` | `ef7dad1352f11557399f8cc08b0fbda0a9c4e9ab6a43e0f0722d17144eecc4e1` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/map/MemoryMapValidator.kt` | `ffd21f22338f5feb9d92db99fea8cc05da40e189da2079ea54b878f02623f713` |
| `context/core/src/main/kotlin/com/github/noahshen/alcyone/context/memory/core/map/MemoryMapRenderer.kt` | `1bd857009ec59268de61a74504b062cf9097e54ee1ff34026f540ab256266953` |
| `context/core/src/main/resources/prompts/memory-map-v1.md` | `e333f259071bb68bec37f2cabac61f1448c8d183742a8a4bc1e873e4987ce56d` |
| `context/core/src/main/resources/prompts/memory-map-schema-v1.json` | `1b6b576d7446332d590b8cee52a5c4654c107e99197cf44cd46373eaec9478a3` |

### 2.2 Runtime 生产（4）

| 文件 | SHA-256 |
| --- | --- |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/JsonMemoryMapResponseParser.kt` | `d39d00486b0a09f291bf63bbd616260603b65bd4de0afd7dca26b7c1f3770258` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapGenerator.kt` | `6ef51440150050f6ea143393b29884f67f90f88e46429308cab98f124c8fb1a1` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapPublisher.kt` | `8558adfa9bfc0746bc386a47533396cc8540eb325f94a32a269c04a3c46293d0` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapService.kt` | `9a0750d6928b55530f89b05ab949cc105ef9c55af40b51b43b87106b2b39ae83` |

### 2.3 测试（7）

| 文件 | SHA-256 |
| --- | --- |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/map/MemoryMapPromptTest.kt` | `6671fc14bbfbe26a695330f593afe58a5959a75e79c25c0644f785ce4e99ac1e` |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/map/MemoryMapValidatorTest.kt` | `349c119e8ae8233ac22368a4c9db77d2dc2a6bd539d9da2b4140bc8e82ccbc85` |
| `context/core/src/test/kotlin/com/github/noahshen/alcyone/context/memory/core/map/MemoryMapRendererTest.kt` | `f87322d7a5ef362f9dc89b3f7d83f5f6e0b6b1419932893dda7242e75ef4d363` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/JsonMemoryMapResponseParserTest.kt` | `2fdddb725057c2d11e0bacdf7157ead1870bc3119b6fc90513eab5ff515f4634` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapGeneratorTest.kt` | `f46a3c1ce0abf8da4b305bf8b43555e16ed528ecde0fe180c4228d6b44a747ad` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/MemoryMapPublisherTest.kt` | `1b7ab417575a76fe7a39c73299dbbfa53760584600b32354a32f735949057f7e` |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/MemoryMapGenerationEndToEndTest.kt` | `724f795bd586405138fa594885a8fe440fa066e16ee2eac7d019db1bd1acd5db` |

### 2.4 交付报告（1）

| 文件 | SHA-256 |
| --- | --- |
| `docs/context/tasks/cm4-ct18/B1_Worker交付.md` | 本文件自身不内嵌自引用 SHA |

白名单外 B1 新增变更：**0**。工作区既有 CM3/CT17 改动未被回滚或改写。

## 3. 实现说明

### 3.1 Snapshot / T1

- `MemoryMapGenerator` 在**单次** `ContextDatabase.read` 内按默认 256 条/页扫描全部 Organized；测试可注入更小页大小。
- 偏移使用 `Long` 累加并检查 `Int.MAX_VALUE`；每页前、读取 T1 前、退出事务后均检查协程取消。
- 同一 ReadContext 读取 `maxUpdatedAt()`；退出事务后才按 `id ASC` 排序并构造 `MemoryMapSnapshot`。
- Snapshot 构造校验：ID 严格升序、T1 必须等于集合 `maxOfOrNull(updatedAt)`、空集合时两者均 null。
- LLM 期间的新写入不改变快照或 T1，不进行 T2 二次查询。

### 3.2 Prompt / LLM / 预算

- 使用既有 `LlmTask.MEMORY_MAP`；缺 Task / Profile 或 Profile 非 `JSON_SCHEMA`/`JSON_OBJECT` → `ContextConfigException(INVALID_CONFIG)`。
- Prompt v1 包含全量 Memory 内容、真实 Domain、createdAt/updatedAt 与 T1；正文明确作为数据而非指令。
- 一次全量请求，不分批、不截断；`maxOutputTokens = task.maxOutputTokens`，Schema 仅 `JSON_SCHEMA` 发送与计入预算。
- 发送前调用 `LlmBudget.requireWithinBudget`；超限 `LOCAL_BUDGET_EXCEEDED`，0 LLM。
- `finish_reason=length` → `LlmException(OUTPUT_TRUNCATED)`。
- 空库不调用 LLM，生成固定 Overview/Navigation、空 Domain、`sourceUpdatedAt=null` 的确定性空 Map。

### 3.3 Parser / Validator

- Parser 只接受严格 JSON object，且键集合必须恰好为 `overview/navigation/domains`；未知字段、类型错误、围栏、前后文本、截断统一 `LlmException(INVALID_RESPONSE)`。
- B1-R1 在 `Json.parseToJsonElement` 前扫描根对象成员名，识别重复 key（含 `\u` 转义后的等价 key），避免后值覆盖绕过严格字段集合检查。
- Validator 要求 Overview/Navigation 非空；Domain 非空白、单行、稳定去重且必须是本次 Snapshot 真实 Domain 子集。
- 不设置第二套静默截断长度；大文本原样保留，最终字节限额由 VFS write limit 执行。
- Validator 非法统一映射 `LlmException(INVALID_RESPONSE)`；数据库错误不包装。

### 3.4 Front Matter / Renderer

- 固定两键 Front Matter：`generatedAt`、`sourceUpdatedAt`；使用 `TimeCodec` 固定 `+08:00` 毫秒格式。
- 固定标题：`# Memory Overview`、`# Memory Navigation`、`## Domains`。
- Domain 列表由 Core 对 Snapshot 真实 Domain 去重排序后确定性渲染；空列表输出 `_None._`。
- 严格 parser 拒绝缺失/重复/未知键、非规范时间、CRLF、非法 UTF-8、缺失标题、未排序 Domain 或尾部多余内容；不使用 SnakeYAML。

### 3.5 Publisher / Service

- 固定 URI：`alcyone://memory/map.md`。
- Publisher 唯一生产写调用为 `Vfs.safeReplace`；不读取旧 Map，不调用 write/move/delete，不查 T2，不加发布锁。
- 生成或校验失败不会进入 Publisher；发布前失败旧 Map 保留。
- `STATE_ERROR + PARTIAL` 原样传播；完整新版可能已存在，状态归属 CT19。
- `MemoryMapService.generateAndPublish()` 在生成成功并再次检查取消后才发布，供 CT19 复用；当前未接 Runtime。

## 4. 测试映射

### 4.1 A01～A08

| 验收 | 正式测试 | 关键覆盖 |
| --- | --- | --- |
| A01 Snapshot/T1 | `MemoryMapGeneratorTest` | 多页、整页倍数、ID 排序、T1、空表、并发写入隔离、偏移/取消 |
| A02 Prompt/预算 | `MemoryMapPromptTest`、`MemoryMapGeneratorTest` | 资源版本、全量输入、数据边界、固定输入超限、余量超限、等于边界 |
| A03 LLM/Parser | `JsonMemoryMapResponseParserTest`、`MemoryMapGeneratorTest` | MEMORY_MAP、JSON 模式、Schema、length、网络失败、严格 JSON、重复 key |
| A04 Validator | `MemoryMapValidatorTest`、`MemoryMapGeneratorTest` | 非空、真实 Domain、空白/重复/未知、结构边界、大文本不截断 |
| A05 Renderer/Front Matter | `MemoryMapRendererTest` | 精确格式、上海毫秒、空库 null、严格反解析、标题/Domain |
| A06 Publisher/Service | `MemoryMapPublisherTest` | LocalFs 首建/替换、固定 URI、无降级、提交前保旧、write limit、PARTIAL、生成后才发布 |
| A07 并发快照 | `MemoryMapGeneratorTest`、E2E | LLM 期间写入不改 T1、ReadContext 已释放、单次 read |
| A08 集成/边界 | `MemoryMapGenerationEndToEndTest`、全量回归 | 真 SQLite + LocalFs + Fake LLM、发布后 VFS 读回与严格解析 |

### 4.2 用例数

| 测试类 | 用例数 |
| --- | ---: |
| `MemoryMapPromptTest` | 4 |
| `MemoryMapValidatorTest` | 7 |
| `MemoryMapRendererTest` | 6 |
| `JsonMemoryMapResponseParserTest` | 8 |
| `MemoryMapGeneratorTest` | 14 |
| `MemoryMapPublisherTest` | 6 |
| `MemoryMapGenerationEndToEndTest` | 1 |
| **合计新增** | **46** |

## 5. 失败矩阵实际断言

| 场景 | 实际结果 |
| --- | --- |
| Snapshot DB 失败 | 原 `ContextPersistenceException` 实例传播；0 LLM |
| T1 与集合 MAX 不一致 | `MemoryMapSnapshotException`；0 LLM |
| 空库 | 0 LLM；固定空 Map；`sourceUpdatedAt=null` |
| 固定输入超预算 | `LOCAL_BUDGET_EXCEEDED`；0 LLM |
| 输入+输出+余量超 Profile | `LOCAL_BUDGET_EXCEEDED`；0 LLM；精确边界成功 |
| LLM 网络失败 | 原 `LlmException` 实例传播 |
| `finish_reason=length` | `OUTPUT_TRUNCATED`；不解析部分输出 |
| Parser/Validator 非法 | `INVALID_RESPONSE`；不生成发布字节 |
| 根对象重复 key | 重复 `overview/navigation/domains` 与转义等价 key 均在 JsonObject 覆盖前 `INVALID_RESPONSE` |
| Renderer 结构失败 | 原 `MemoryMapValidationException` 传播，不产生字节 |
| VFS write limit | `LIMIT_EXCEEDED`；不截断，旧 Map 逐字节保留 |
| safeReplace 提交前失败 | 原错误传播；旧 Map 逐字节保留；write/move/delete 0 |
| `STATE_ERROR + PARTIAL` | 原异常、code、effect 原样传播 |
| 生成失败 | Service 不调用 Publisher |
| LLM 期间新 Organized | 请求与 Map 仅含旧 Snapshot；T1 保持旧值；数据库可见新行 |
| 取消 | Snapshot 分页和 LLM 的 `CancellationException` 原样传播 |

## 6. 验收命令与结果

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle :context:core:test :context:runtime:test :integration-tests:context:test --rerun-tasks --console=plain` | 0 | 44.17s | `BUILD SUCCESSFUL in 43s`；32 actionable tasks executed |
| `./scripts/dev gradle :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | 5.90s | `BUILD SUCCESSFUL in 5s`；5 actionable tasks executed |
| `./scripts/check` | 0 | 136.82s | `BUILD SUCCESSFUL in 2m 16s`；131/131 tasks executed |

## 7. 14 模块源码 `@Test` 与 XML

源码范围为各模块 `src/test/**/*.kt` 行首（允许缩进）`@Test`；XML 为最终 `build/test-results/test/TEST-*.xml`。

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 31 | 31 | 0 | 0 | 0 |
| context-core | 110 | 110 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 515 | 515 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 22 | 22 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **1756** | **1756** | **0** | **0** | **0** |

B1 前基线 1710，新增 46，最终 **1756=1756**。

## 8. Schema、CT17 与 VFS 基线

### 8.1 Schema / VFS

- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 81 VFS 基线：**75 一致、6 个 CT17 B1 已批准差异、缺失 0、未批准差异 0**。
- 6 个差异仅限 `Vfs.kt`、`Storage.kt`、`DefaultVfs.kt`、`LocalFsStorage.kt`、`WebDavStorage.kt`、`AlcyoneVfs.kt`；CT18 未修改 VFS。

### 8.2 CT17 23 个代码/测试文件 SHA

下表 23 个文件与 CT17 终态一致。

| 文件 | SHA-256 |
| --- | --- |
| `vfs/api/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/Vfs.kt` | `5f8b95f2859455e33af897c92b1eeb194e78541d7a181960b95fcfff18c8131c` |
| `vfs/core/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/core/storage/Storage.kt` | `758c6832e84247793e06e99662effc805e21acd2784f9f442f8de852b6f71a4d` |
| `vfs/core/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/core/DefaultVfs.kt` | `a7d52a7296fdcb6649036b16686e8d5b67c6de78ca8e465a03602b99435d29f0` |
| `vfs/storage/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/storage/opendal/LocalFsStorage.kt` | `8d7f15d7fc7324eddb38f7cf1e4d725782c44f0b723e9a7709ca96ed1de1aed7` |
| `vfs/storage/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/storage/opendal/WebDavStorage.kt` | `f2fd7bd7397bfe8420faaa73bdf5d21e292681a24d3e2360b11b6aa84705dd16` |
| `vfs/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/runtime/AlcyoneVfs.kt` | `e8af492944ce6e44d30d60404df613c35b84e8bdbefc533da2eedac7603648ca` |
| `vfs/api/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/PublicContractTest.kt` | `36ee128bf700010c2a5c7408abe2fe088817461973c5d316d8f376d4897a8846` |
| `vfs/core/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/core/storage/StorageFakeImpl.kt` | `1eb822ae0cb55aee5e75dc6e91d25efc6e1a27d3ab4a10557714100549867400` |
| `vfs/storage/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/storage/opendal/WebDavStorageTest.kt` | `ad4c0978ac43c209f1ebaf2c8363d87c8f3e34517832a6d56d39e62513ad4230` |
| `vfs/core/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/core/DefaultVfsSafeReplaceTest.kt` | `b76ade05be70a5d8f1457f31bb4acdd1050ded731a2bda9f6b21329435ec892f` |
| `vfs/storage/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/storage/opendal/LocalFsSafeReplaceTest.kt` | `e900b4b1b3d9feb4a534df8524acd8e2b4bb944bbb5a759b15c0a82574668ba1` |
| `vfs/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/runtime/RuntimeSafeReplaceTest.kt` | `c6477d7c4ece3edfbee7d8e4d6b2bb4ef43218da6b7ffefc5cea37ef07dd06f2` |
| `integration-tests/vfs/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/integration/vfs/DefaultVfsSafeReplaceRealStackTest.kt` | `363f8e97661e5bdb04253f1e3920ddaa088d0b33b5c908ed3b13abd85b22bf77` |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/AlcyoneContext.kt` | `4437ca4800ab06a131f083bb88135ed5360405da55134e00b6c1e6823619364c` |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/ContextExceptions.kt` | `942402ea5bf9bd0343fb630d03f7cf356b07fa586591d00031029d93d78b50c9` |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/ResourceMetadata.kt` | `67ceadbbe6f3e7050dd09cf9610136bc857e18698650e85e38532900611668ce` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextImpl.kt` | `e95e6ae4cae23828a433fe712fc4a2d1abdf01cf20a8cf609ee4ff33a2c4352d` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/resource/ContextResourceAccess.kt` | `466c5594017d92a7c2cc16ab6a2dae219f2fe7f3207f6fd23d32f20f7f19d33f` |
| `context/api/src/test/kotlin/com/github/noahshen/alcyone/context/memory/api/ResourceApiContractTest.kt` | `bad2b04d5f8954c50917c1850f46668564206b8511c61ea58edb87bf25863d6e` |
| `context/api/src/test/kotlin/com/github/noahshen/alcyone/context/memory/api/ContextExceptionsTest.kt` | `0ba4922f2caac8a4bc97e2ed77cb14739ade8553fb80ab331e1f59c835983cc5` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/resource/ContextResourceAccessTest.kt` | `af77827d52e920bf61fd120d13ae3bb9e4e5909085abbfad07ece66c3a3a053f` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextResourceIntegrationTest.kt` | `5726190beef05f04d947a4dbd4119718f2edf3cac02df798c74ff24a69536d62` |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/ResourceEndToEndTest.kt` | `3eff0b6f3b21d50dd8aefad5dcd9cd253e895e4f1476bb73c1c464f455677a70` |

## 9. 边界证据

- `context/api`：CT18 零修改；无 Bootstrap/MapStatus/Map 读取公共方法。
- Factory/Impl/Config/Loader/Persistence/Server：CT18 零修改；未接生产启动/刷新。
- VFS/common/Schema/SQL/依赖/构建：CT18 零修改。
- Runtime map 组件为 Kotlin `internal`，不进入 Context SDK 公共契约；Core map DTO/Prompt/Validator/Renderer 仅作为 Core→Runtime 实现面。
- Core map 生产源码无 SnakeYAML、Ktor、kotlinx.serialization、OpenDAL、NIO 物理写或普通 VFS write/move/delete。
- Publisher 静态路径只有 `Vfs.safeReplace`。
- `git diff --check`：exit 0，空输出。

## 10. 未运行项与残余风险

- 真实模型：**not-run**；Fake LLM 只证明协议、预算、快照、事务与确定性行为，不证明真实语义质量。
- `generatedAt` 使用注入 Clock；生产接线与时钟来源由 CT19 负责。
- Publisher 对 `STATE_ERROR + PARTIAL` 只原样传播；Map 状态缓存/四态归 CT19。
- 未实现 Worker/Cron/Bootstrap/MapStatus/Search/Get/HTTP，也未读取旧 Map 或查询 T2。
- integration E2E 为验证 Kotlin `internal` Runtime 组件使用文件级 `INVISIBLE_REFERENCE` suppression；当前 Kotlin 编译器成功编译并实跑，若未来编译器移除该兼容行为需调整测试模块可见性方案。该 suppression 仅存在于测试文件，不改变生产可见性。

## 11. 最终状态

提交 Lead 初验（B1-R1）；**CT18 未 DONE，CT19 未启动**。真实模型 not-run，无 git add/commit/push/stash、分支或回滚操作。
