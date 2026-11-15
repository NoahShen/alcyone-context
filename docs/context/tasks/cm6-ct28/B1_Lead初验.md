# CT28 B1 Lead 独立初验

## 本次修改（2026-11-11，Architect R1 补测独立复验）

- 按主线程定点指令，只读检查 R1 委派、补测实现与断言，未重开实现、未修改任何生产或测试代码，未使用 Paseo/Agent 工具，未运行全仓或启动 B2。
- 独立实跑四套件 `--rerun-tasks`：**32/32**；独立实跑 `:context:server:check --rerun-tasks`：**62/62**，两项均 0 failures / 0 errors / 0 skipped，日志、退出码与最终 XML 分别保存于 `.local/ct28-b1-architect-r1-lead-four-suites*` 与 `.local/ct28-b1-architect-r1-lead-server-check*`。
- R1 技术补测覆盖有效：真实 Runtime 无匹配精确 `[]` 且模型调用数证明短路；UNAVAILABLE/CONFIGURATION 均为固定 503 且秘密零回显；缺 query、null、截断 UTF-8、孤立 0xFF 均为原始输入且 SDK 调用数不增；五类 Resource 错误均分别覆盖 meta 与 content 首块前 Flow 异常。
- **唯一待 Architect 澄清项（文档字面量，不是测试覆盖或已确认生产缺陷）**：`Architect复核.md` 与 `B1_ArchitectR1补测委派.md` 写 `TYPE_MISMATCH → INVALID_RESOURCE_TYPE`，而原任务书/B1 委派只要求固定具名代码，实际生产、补测及另一份 R1 委派使用 `RESOURCE_TYPE_MISMATCH`。本轮禁止改代码，故未擅自统一；请 Architect 明确最终契约文字或更正 R1 文档笔误。
- 结论：**R1 技术覆盖无缺口，B1 保持 IN_REVIEW 并提交 Architect 最终复核**；上述 code 字面量差异单独待裁定，不宣称 CT28/CM6 DONE。

## 历史修改（2026-11-11，B1 R1 首轮收口并提交 Architect）

- 独立复跑四个 R1 定向套件与 `:context:server:check`，分别 **29/29**、**59/59**，均为 0 failures / 0 errors / 0 skipped；最终 XML 已单独保存，未被后续定向任务覆盖。
- 采用既有全仓 `./scripts/check` 证据：**BUILD SUCCESSFUL，132/132 actionable tasks**；14 模块有效 XML 合计 **2185=2185**，另核对 `B3Fixtures.kt` 的辅助 `@Test` 不产生测试用例，故源码原始计数 2186、XML 2185，差额 1 已解释。
- 核对 A01～A04/A08 的真实 Runtime、SQLite、Local FS、严格 JSON、认证、流式释放及脱敏证据；R1 技术缺口在 Lead 初验层面关闭。
- **结论：CT28 B1 R1 CLOSED，B1 IN_REVIEW，提交 Architect 最终复核。** Lead 不自标 APPROVED/DONE；B2 未实施，CT28/CM6 未 DONE。

**状态**：IN_REVIEW / R1 CLOSED（待 Architect 裁定）  
**范围**：CT28 B1，三类静态凭据、六条 Read 路由、共享 HTTP 基础设施及对应测试  
**基线**：`3c7d5c319aaaacb77bf34effe280fbda84900ba3`，保留 CT27 既有工作区修改  
**生产改动边界**：仅 `context/server`；无 Git 写操作

## 1. 交付与写集合核对

### 1.1 生产文件

允许修改的 5 个文件：

- `AlcyoneHttpServer.kt`：同时安装 Ingestion 与启用时的 Read 路由，外部 Runtime 所有权不变。
- `IngestionClientValidation.kt`：同类 Key 冲突改走固定长度摘要比较。
- `IngestionHttpApi.kt`：复用共享鉴权、严格 UTF-8、有界 body 与固定错误响应；保留 Runtime 绑定与旧状态语义。
- `ServerConfig.kt`：增加 Read/Admin Key、ServerLimits、统一校验、防御性快照及脱敏输出。
- `ServerConfigLoader.kt`：严格解析 `server.auth`、`server.limits` 与 ENV/数值规则。

允许新增的 4 个文件：

- `ServerAuth.kt`：三类凭据、摘要注册表、Bearer 兼容解析及恒定时间比较。
- `HttpApiSupport.kt`：共享有界读取、严格 UTF-8、重复 JSON 键扫描、自有 deadline 与固定错误体。
- `ReadHttpDto.kt`：显式安全 DTO；不直接序列化 Domain 对象。
- `ReadHttpApi.kt`：六条 Read 路由、严格 Search、错误映射及 Resource 流式传输。

R1 的必要最小生产修复只涉及 `ReadHttpApi.kt`：补 Resource 首块预取/Channel 转交、提交后异常终止、真实底层释放及首块前错误映射。最终 SHA-256：

```text
4a6a4df9e6532dd42f1e0872ae6e3caa4afa9d4c4f8b0a8a8c313cea254c5c3a  ReadHttpApi.kt
```

### 1.2 测试文件

- 原有 `ServerConfigLoaderTest.kt`、`ServerClientValidationTest.kt`、`IngestionHttpApiTest.kt`、`IngestionHttpErrorMappingTest.kt`、`InvalidUtf8BodyTest.kt`、`ServerFixture.kt` 保持原内容；完整回归通过。
- 新增 `ServerAuthTest.kt`、`ReadHttpApiTest.kt`、`ReadHttpSerializationTest.kt`、`ReadHttpResourceTest.kt`。
- 新增测试辅助 `ReadRuntimeFixture.kt`、`ServerLogCapture.kt`，没有生产 Server JDBC/VFS 实现接线。

`ReadRuntimeFixture.kt` 最终 SHA-256：

```text
1c2aecb76f4c83fbb887d41b2a22dcc57ac8c98db5fa37b6bf6f8ebe0e05e1e4  ReadRuntimeFixture.kt
```

未修改任何 Gradle/依赖、`scripts/**`、Context/VFS Schema 或 Migration；未修改 `vfs/**`、`common/**`。其他 Context 模块中的已有 CT27 改动完整保留，不属于 B1 新增改动。`git diff --check -- context/server` 无输出，格式检查随定向 `check` 通过。

## 2. 逐项验收证据

路径均为 `context/server/src/test/kotlin/com/github/noahshen/alcyone/context/memory/server/`。

### A01：凭据、权限、配置与零副作用

- `ReadHttpApiTest.kt:118` `every protected route rejects missing and wrong credentials before SDK work and accepts its own class`：覆盖 Ingestion + 六条 Read 的 missing/unknown/错类/合法 Key 矩阵；Ingestion/Read/Admin 不互继承，并逐路由核对 SDK 调用计数。
- `ReadHttpApiTest.kt:232` `auth classes are checked before body query or SDK work`：认证先于 body/query 与 SDK；Read 仅访问 Read，错误类型为 401/403。
- `ReadHttpApiTest.kt:55` `real Runtime serves every read route and unauthorized ingestion leaves SQLite empty`：独立真实文件 SQLite 夹具断言未认证 Ingestion 后 `conversation_turn=0`，合法请求随后正常落库。
- `IngestionHttpApiTest.kt:42` `bearer authentication matrix and runtime binding`：既有 Bearer 大小写、outer trim、多空格及 runtime_id 绑定兼容回归，未被共享鉴权抽取收紧。
- `ServerAuthTest.kt:16`：三类 Principal 区分、未知/畸形 token；`:37`：三类 Key 交叉冲突且异常不泄露 Key。
- `ServerAuthTest.kt:64`、`:103`：auth/limits ENV、未知字段、非正数、脱敏；`ServerClientValidationTest.kt:20`～`:111` 与 `ServerConfigLoaderTest.kt:19`～`:157`：Loader/直接构造/Collection Registry、重复 runtimeId/Key、空白、快照不可变及错误脱敏。
- 静态实现核对：认证表保存 SHA-256 摘要而非原 Key HashMap 查找；比较使用固定长度摘要与 `MessageDigest.isEqual`。

### A02：六条 Read 的真实 Runtime 数据面

- `ReadHttpApiTest.kt:55` 使用 `ReadRuntimeFixture` 组装真实 `AlcyoneContextFactory`、文件 SQLite 与 Local FS，不是 Fake 数据面证据：
  - bootstrap 返回 200 与 Map 字段；
  - search 非空，返回真实 organized content；
  - getMemory/getRawMemory 命中 200、不存在 404；
  - resources/meta 返回真实大小；
  - resources/content 返回真实小文件正文；
  - 同测试另核对未认证写入零 SQLite 行。
- `ReadRuntimeFixture.kt` 提供真实 seed、受控模型响应与只读 SQLite 计数；测试模型只承担固定 Runtime 依赖，不替代真实 SQLite/检索/资源访问，不用于宣称真实模型质量。
- `ReadHttpSerializationTest.kt:30`：bootstrap Map CURRENT/STALE/UNKNOWN/UNAVAILABLE 四态、内容及水位；`:56`：Memory DTO 全字段、数组顺序、时间值和两种 source discriminator；`:107`：Resource 不可用统计保持 JSON null。
- `ReadHttpApiTest.kt:443`：snake_case 与 raw source 精确 DTO、null 404；`:462`：URI 范围等错误固定映射且不泄露后端消息。
- Search 失败与空匹配分别由 `ReadHttpApiTest.kt` 的检索错误映射及真实/Spy 路由用例覆盖；失败整体映射 503，不伪 200 空列表。

### Architect R1 补测独立断言核对（本轮）

- `ReadHttpApiTest.kt:57`：真实 Runtime 的 `no-match` 请求断言 HTTP 200、正文精确 `"[]"`，并由 `modelCalls == 3` 证明第一阶段空 `candidate_ids` 未进入第二阶段；不是 Fake 空列表。
- `ReadHttpApiTest.kt:502`：UNAVAILABLE 与 CONFIGURATION 逐一断言 503、固定 `RETRIEVAL_UNAVAILABLE`、无 memory 结果字段、异常秘密不回显。
- `ReadHttpApiTest.kt:519`：缺 query/query:null 与两组原始非法 UTF-8 字节逐一断言 400/`INVALID_REQUEST`，逐项比较 SDK 总访问数且 `searchCalls == 0`。
- `ReadHttpResourceTest.kt:260`：五类错误 × meta/content 共十条组合；meta 通过 `loadMeta` 异常，content 在首块前通过 Flow 异常，分别断言 status、固定 code 与每例独立秘密零回显。`RESOURCE_TYPE_MISMATCH` 为当前生产与测试一致使用的固定 code。
- `ReadRuntimeFixture.kt:28`：受控模型读取实际请求体并按 no-match 分流，空第一阶段候选不再触发第二阶段；该辅助不替代 Runtime/SQLite/检索实现。

### A03：严格 JSON、实际字节计数与旧 Ingestion

- `ReadHttpApiTest.kt:265`：body 恰好 max 接受、max+1 拒绝；`domains` 省略/null/空数组/多值及默认 limit=10、显式 limit 的实际 SDK 入参。
- `ReadHttpApiTest.kt:307`：重复键、未知键、缺 query、null/错误类型、尾随垃圾、非法 UTF-8 等统一 400 且 SDK 0 调用。
- `ReadHttpApiTest.kt:328`：chunked/无 Content-Length 的实际字节计数、合法 filters 原样委派；结合 `:265` 的声明长度预检覆盖精确边界与 +1。
- `ReadHttpApiTest.kt:351`、`:374`、`:412`：body 与 SDK 共用请求期限，自有 deadline 映射 504；SDK 自有 timeout/外部 CancellationException 不被吞掉或误分类。
- `IngestionHttpApiTest.kt` 8 例、`IngestionHttpErrorMappingTest.kt` 3 例、`InvalidUtf8BodyTest.kt` 5 例全部通过，覆盖 Runtime 绑定、201/200/409/413、严格 UTF-8、关闭竞态与既有外部 Runtime 所有权启动顺序。

### A04：Resource 流式、超限、超时与释放

- `ReadHttpResourceTest.kt:106`：真实 Runtime + SQLite + Local FS 下载 **16 MiB + 4096 bytes** 文件，默认不套用 ByteArray 16 MiB 上限，实际字节数与 SHA-256 完全一致。
- `ReadHttpResourceTest.kt:151`：真实 Local FS 已知大小超过配置限额，413；`:265`：空文件 200/0 bytes、已知精确限额通过。
- `ReadHttpResourceTest.kt:285`：已知 oversized stat 在打开流前拒绝；`:298`：stat 未揭示的实际超限在提交后中断，不伪装完整下载，release=1。
- `ReadHttpApiTest.kt:479`：首块预取及首块超限在响应提交前 413；`ReadHttpResourceTest.kt:322` 与 `ReadHttpApiTest.kt:496`：首块失败固定 JSON，后续失败截断已提交流且不追加 JSON/异常秘密。
- `ReadHttpResourceTest.kt:169`：真实 CIO 端口 + Socket 中途断连，有限时间内底层 Flow release=1、读取提前停止，后续请求仍可服务；不是仅 `runCatching`。
- `ReadHttpResourceTest.kt:356`：请求 deadline 中断已提交流并仅释放一次；底层取消/finally 证据齐全。

### A08：脱敏、依赖边界、Schema 与回归

- `ReadHttpResourceTest.kt:34`：Key/query/正文/URI/User-Agent/后端异常等 marker 不进入固定 HTTP 错误、实际 abort exception/cause 链或平台捕获日志。
- `IngestionHttpErrorMappingTest.kt`、`InvalidUtf8BodyTest.kt`、`ServerAuthTest.kt`、`ReadHttpApiTest.kt` 的错误映射与配置用例补充 Key、正文、JDBC/path、Throwable.message 零回显断言。
- 静态扫描 `/tmp/ct28-b1-r1/server-static-scan.txt`：仅扫描 `context/server/src/main/**/*.kt` 共 9 文件，无直接 `println`、`printStackTrace` 或 logger emission；不会把 `Bearer ` 字面量误报为秘密/日志。
- `context/server` 生产代码无 JDBC/SQL、OpenDAL 或 VFS implementation import；`context/api`、`context/core` 无 Ktor import。Server 只通过 `AlcyoneContext` 委派。
- Context Schema SHA-256 保持 `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；Context/VFS Schema 与 Migration 的 tracked/untracked diff 均为空。
- B1 未改 `vfs/**`、`common/**`、构建与依赖；CT27 local-atomic-only 边界未改变。

## 3. Lead 独立命令与结果

### 3.1 四个 R1 套件（实际 `--rerun-tasks`，不是 UP-TO-DATE）

```bash
./scripts/dev gradle :context:server:test --rerun-tasks \
  --tests 'com.github.noahshen.alcyone.context.memory.server.ServerAuthTest' \
  --tests 'com.github.noahshen.alcyone.context.memory.server.ReadHttpApiTest' \
  --tests 'com.github.noahshen.alcyone.context.memory.server.ReadHttpSerializationTest' \
  --tests 'com.github.noahshen.alcyone.context.memory.server.ReadHttpResourceTest' \
  --console=plain
```

- 退出码：`0`；`BUILD SUCCESSFUL in 23s`，27/27 actionable tasks。
- 日志：`.local/ct28-b1-lead-r1-four-suites.log`；退出码：同名 `.exit`。
- XML 快照：`.local/ct28-b1-lead-r1-four-suites-xml/`。

| Suite | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| ReadHttpApiTest | 13 | 0 | 0 | 0 |
| ReadHttpResourceTest | 9 | 0 | 0 | 0 |
| ReadHttpSerializationTest | 3 | 0 | 0 | 0 |
| ServerAuthTest | 4 | 0 | 0 | 0 |
| **合计** | **29** | **0** | **0** | **0** |

### 3.2 Server 完整测试与格式（实际 `--rerun-tasks`）

```bash
./scripts/dev gradle :context:server:check --rerun-tasks --console=plain
```

- 退出码：`0`；`BUILD SUCCESSFUL in 17s`，32/32 actionable tasks。
- 日志：`.local/ct28-b1-lead-r1-server-check.log`；退出码：同名 `.exit`。
- XML 快照：`.local/ct28-b1-lead-r1-server-check-xml/`。
- 10 suites，源码 `@Test=59`，XML `tests=59 / failures=0 / errors=0 / skipped=0`，差额 0。

### 3.3 全仓既有独立证据（本轮不重复运行）

```bash
./scripts/check
```

- 日志：`.local/ct28-b1-lead-check.log`。
- `BUILD SUCCESSFUL in 2m 33s`，**132 actionable tasks: 132 executed**。
- 该日志时间晚于 R1 最终生产文件修改时间，覆盖当前实现。

| 模块 | XML tests/failures/errors/skipped | 有效差额 |
| --- | --- | ---: |
| common | 23/0/0/0 | 0 |
| context/api | 42/0/0/0 | 0 |
| context/core | 179/0/0/0 | 0 |
| context/persistence-sqldelight | 184/0/0/0 | 0 |
| context/runtime | 808/0/0/0 | 0 |
| context/server | 59/0/0/0 | 0 |
| integration-tests/context | 32/0/0/0 | 0（原始计数差额 1 为辅助函数注解） |
| integration-tests/vfs | 112/0/0/0 | 0 |
| vfs/api | 67/0/0/0 | 0 |
| vfs/core | 318/0/0/0 | 0 |
| vfs/git-backup | 73/0/0/0 | 0 |
| vfs/persistence | 52/0/0/0 | 0 |
| vfs/runtime | 67/0/0/0 | 0 |
| vfs/storage | 169/0/0/0 | 0 |
| **合计** | **2185/0/0/0** | **0** |

原始源码 `@Test=2186` 与 XML `2185` 的唯一差额位于 `integration-tests/context/.../B3Fixtures.kt` 的 `withDatabase` 辅助函数。该函数不是测试类用例，其注释明确用于避免 JUnit 5 静默跳过；无真实测试缺失。Server 当前 59 例在后续独立 `check` 再次验证，结果不变。

## 4. 结论与未验证边界

- 本轮 Architect R1 补测经独立断言核对与实跑，四套件 32/32、Server 62/62 通过；**技术覆盖无缺口，B1 IN_REVIEW，提交 Architect**。
- R1-3 文档的 `INVALID_RESOURCE_TYPE` 与生产/测试的 `RESOURCE_TYPE_MISMATCH` 存在字面量差异，见本次修改说明；本轮未修改代码，待 Architect 对契约文字作最终澄清。
- 历史 R1 要求的真实 Runtime 六路由、>16 MiB 真流下载、真实 Socket 断连释放、Search 边界、认证零副作用和日志/异常脱敏均已有正式断言与独立通过证据。
- 本报告是 Lead 初验，不替代 Architect 最终独立复核；**B1 当前 IN_REVIEW，不标 DONE**。
- B2 的 Admin/Health/Ready/managed 生命周期不在本批交付，未宣称实现；CT28 整体与 CM6 均未 DONE。
- 本轮未运行真实收费模型、真实 WebDAV、生产部署或长期跨进程 cron；Fake/受控模型不用于外推语义质量。CT27 仍保持 local-atomic-only。
- 首次 Extraction 时序失败的原始日志已缺失，本报告不补造该证据、不宣称修复无关问题。
- 无 Git add/commit/branch/reset/checkout/stash/clean，无发布或部署。
