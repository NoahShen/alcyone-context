# CT07-B2 Worker 交付（Server 最小 HTTP + 静态注册认证 + 限额/错误映射）

**任务**：CM1 CT07-B2：`POST /v1/conversations/turns`、Bearer 静态注册认证、限额前置、错误映射、生命周期与关闭、CIO 启动封装
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db（Paseo lead 配置，只做方案与独立初验）
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-29

## 本次修改（2026-10-29，B2 复核修复 R4 错误映射 / R5 实例限额）

- **R4**：新增真实 HTTP 错误映射测试（受控假 `AlcyoneContext` + `Application.ingestion` + `testApplication`）：`PERSISTENCE_UNAVAILABLE` → 503、其他持久化错误码 → 500、未预期 `RuntimeException` → 500；响应体断言为固定 JSON，且不含异常 message/类名、Key、请求正文或 jdbc/连接串。
- **R5**：`context/runtime` 新增非 API 的 `IngestionLimitAware { val ingestionMaxTurnBytes }`，由 `AlcyoneContextImpl` 返回实例真实限额；`Application.ingestion` 与 `AlcyoneHttpServer.start` 删除显式/常量限额，只读该访问器，杜绝传不同值。
- 新增测试：真实 Context 配置 `maxTurnBytes=256`，超过 256 但远小于 2 MiB 的**合法 JSON** 与**非法 JSON** 都在完整反序列化前返回 413、库内 0 行；正常请求 201；默认 2 MiB 路径保持。
- 计数更新：server **19**（原 15 + 4），core 11 / runtime 75 / persistence 155 不变；四模块源码 `@Test` = XML，差额 0。证据 `.local/validation/cm1/worker/ct07-b2-fix/`。

## 本次修改（2026-10-29，CT07-B2 Server HTTP）

- `context/server` 新增 `ServerConfig` / `IngestionClientConfig` / `IngestionClientRegistry`（静态注册快照 + Bearer 解析）、`ServerConfigLoader`（`server` 分区严格解析）、`IngestionHttpApi`（严格 JSON、限额前置、错误映射与 Core 复用）、`AlcyoneHttpServer`（最小 CIO 封装）。
- `context/server/build.gradle.kts` 增加 kotlinx.serialization 插件/依赖、SnakeYAML、Core（仅为使用 `IngestionTimestampText`，不访问 Persistence/JDBC）；测试增加 VFS runtime 以组装真实 LocalFs 配置。
- 新增 13 个正式测试（HTTP 7 + 配置 6，另保留 skeleton 2），真实文件 SQLite + VFS LocalFs + Ktor `testApplication`；覆盖认证矩阵、201/200/400/403/409/413/503、strict JSON、时间 offset、限额前置、配置快照、关闭竞态、并发同键与 CIO 封装。
- 未改 VFS/CT04/CT05/CT06、未改 B1 Core/API 语义；不做 CT08+；未 `git add/commit`。

## 1. 文件与职责

| 文件 | 内容 |
| --- | --- |
| `ServerConfig.kt` | `IngestionClientConfig`（`toString` 遮住 Key）、`ServerConfig`（host/port + 注册集合快照，`clients()` 只读）、`IngestionClientRegistry`（Bearer 解析 + Key→runtimeId 快照） |
| `ServerConfigLoader.kt` | 读取 YAML `server` 分区：`SafeConstructor` + 禁重复键、`${ENV}` 展开、严格未知字段、类型/范围、Key 冲突、结果快照 |
| `IngestionHttpApi.kt` | `Application.ingestion(context, registry, maxTurnBytes)`：`POST /v1/conversations/turns`、认证/绑定、Content-Length + 流式双限额、strict JSON、时间解析、错误映射 |
| `AlcyoneHttpServer.kt` | `AlcyoneHttpServer.start(config, context, maxTurnBytes)` 最小 CIO 封装与 `close()` |

## 2. HTTP 契约

请求（六字段均必填，snake_case）：

```json
{"runtime_id":"...","agent_id":"...","session_id":"...","session_turn_id":"...",
 "timestamp":"2026-10-29T20:15:00+08:00",
 "messages":[{"role":"user","content":"..."}]}
```

- **strict JSON**：未知顶层/消息字段 → 400 `INVALID_TURN`（kotlinx.serialization strict，不忽略未知键）；解析错误不回显正文。
- **时间**：`IngestionTimestampText.parseRequiringOffset`；无 offset/纯日期/非法 → 400；`+08:00` 与 `Z` 等价重复返回 200。
- **限额先于完整反序列化**：先查 `Content-Length`，再用 8 KiB 缓冲流式读取，累计超 `maxTurnBytes` 立即 413（`maxTurnBytes` 默认 2 MiB，可注入）；超限请求不进入 Core，不产生数据库行。消息条数/ID 长度超限由同一 Core Service 返回 `PAYLOAD_TOO_LARGE` → 413。
- **认证**：`Authorization: Bearer <key>`（scheme 大小写不敏感）；缺失/格式错/未知 Key → 401；`runtime_id` 与 Key 绑定不一致 → 403；同 Runtime 新 agent 允许。
- **响应映射**：CREATED→201、DUPLICATE→200（同一 id）、INVALID_TURN→400、TURN_CONFLICT→409、PAYLOAD_TOO_LARGE→413、PERSISTENCE_UNAVAILABLE→503、Context 未 RUNNING（`ContextLifecycleException`）→503、其他→500；响应体仅 `{"id":...}` 或 `{"error":"CODE"}`，不含 Key/正文/连接串。
- **复用**：校验/幂等/事务/通知全部走 `AlcyoneContext.ingestConversation`，Server 不建第二套逻辑。

## 3. 配置（`server` 分区）

```yaml
server:
  host: 127.0.0.1        # 可选，默认 127.0.0.1
  port: 8080              # 可选，默认 8080，1..65535
  ingestion:
    clients:
      hermes-cloud:
        runtimeType: hermes
        apiKey: ${HERMES_INGEST_KEY}
```

- `server` / `server.ingestion` / 每个 client 内未知字段 → `UNKNOWN_FIELD`（含路径）。
- 类型/范围错误、运行时 ID 空白、`runtimeType`/`apiKey` 空白、两个 Runtime 使用同一 Key → `INVALID_CONFIG`；消息只含字段路径，不回显 Key。
- ENV 缺失或 trim 空白 → `MISSING_ENV`（只含路径与 ENV 名）。
- 注册集合复制进 `ServerConfig`，`clients()` 只读；`IngestionClientRegistry` 再拷贝一份 Key→client 快照，调用者修改原 Map 不能绕过认证。

## 4. 测试与断言（Server 15 例）

| 测试类 | 例数 | 关键断言 |
| --- | --- | --- |
| `ServerConfigLoaderTest` | 6 | ENV 展开与 Key 脱敏；ENV 缺失/空白 `MISSING_ENV`；三层未知字段 `UNKNOWN_FIELD`；类型/范围/重复 Key `INVALID_CONFIG` 且不回显 Key；重复 YAML 键拒绝；缺失 `server` 用默认值；`ServerConfig`/registry 快照不被调用者后续修改影响 |
| `IngestionHttpApiTest` | 8 | 认证矩阵（401/403、同 Runtime 新 agent 201、双 Runtime 隔离）；201→200 同 id、409 冲突、未知字段/无 offset/纯日期/非法时间/空白 ID/空消息/未知 role → 400、`+08:00` 与 `Z` 重复 200；超大 Content-Length 与 chunked 流式均 413 且库内 0 行、超 messages/ID → 413；未启动与关闭后 503；已接纳请求 + close 不死锁且返回 201；并发同键 {201,200} 同 id 单行；`AlcyoneHttpServer` CIO 封装未启动 503 → 启动后 201 |
| `IngestionHttpErrorMappingTest` | 3 | `PERSISTENCE_UNAVAILABLE`→503、其他持久化错误码→500、未预期异常→500；固定脱敏响应体，不含异常 message/类名/Key/正文/jdbc |
| `ServerSkeletonTest` | 2 | 既有 Ktor/包名骨架不回归 |

## 5. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `:context:server:spotlessApply :context:server:compileKotlin` | 0 | 生产编译 |
| `:context:server:spotlessApply :context:core:spotlessApply :context:runtime:spotlessApply` | 0 | 格式化；`spotless-apply.log` |
| `:context:server:test :context:server:spotlessCheck :context:core:test :context:runtime:test :context:persistence-sqldelight:test --rerun-tasks` | 0 | **server 15 / core 11 / runtime 75 / persistence 155 全绿**；四模块源码 `@Test` 与 XML 一致，差额 0；`targeted-test.log` |

证据 `.local/validation/cm1/worker/ct07-b2/`：`targeted-test.log`、`spotless-apply.log`、`final-source-hashes.json`、`verification.json`。

## 6. 改动文件与最终 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `context/server/build.gradle.kts` | `499ea80c841f87ef70c81ddcbb17f8bd1910dfbafae1b8a11eeee1d576bc2f03` |
| `.../server/ServerConfig.kt` | `fbe37fe87f8301efa0178828ba1e6ae8f1470b7039c1d8e265bbf1775b70b2b8` |
| `.../server/ServerConfigLoader.kt` | `4ec30802f09c1498e110eda6fe34b6d1ff393a84f9b66239452b3f30a0562921` |
| `.../server/IngestionHttpApi.kt` | `9efbeb36f418070da0a3ca3dd9ebfdb42e01b212b0d19b8a97b99a0bebde1a4b` |
| `.../server/AlcyoneHttpServer.kt` | `2ed984607541c29bccd9f6b31768965c0055c85660f690377efcd7c3c7bd90e3` |
| `context/runtime/.../IngestionLimitAware.kt` | `935968254ea16539594b76a118a112ae42972ab7eed3cfc485467676a80e7613` |
| `context/runtime/.../AlcyoneContextImpl.kt` | `097a563b862baac91640ef3f993905df6217601f97ca3c306b121897c89b0e10` |
| `.../test/IngestionHttpErrorMappingTest.kt` | `469289aff3479f87fa39f422c1c54d563b9e784d5d0dca39047bb91269259325` |
| `.../test/ServerFixture.kt` | `0737b74ce0576969fc980a9d34ed68fc1afc8fc683a1ff0d22b0177ca63b6dc5` |
| `.../test/ServerConfigLoaderTest.kt` | `859cbe7a3c7f04de08cfb6c5caf367b779260587ce962c0387599ad879000f84` |
| `.../test/IngestionHttpApiTest.kt` | `1cc7e3d1a7f391056cebfa2d50081b02cb23d1c652de20fc08e948b429c56f3c` |
| `.../test/ServerSkeletonTest.kt` | `9b70328796bcbee1e5fd0a9a0a9544c77ad076ffb272b93fbcbc9a0a30be00bc` |

## 7. 未做项与边界

- 未做 CT08+；不发布/不部署/不做 HTTPS 终止；未实现动态注册、热更新、多用户权限、Worker/Cron/持久队列；未改 VFS/CT04/CT05/CT06/B1 Core/API 语义；未 `git add/commit`。
- 通知语义（CREATED-only、DUPLICATE 不通知、通知失败不影响结果）已在 B1 由可注入 notifier/reporter 验证；B2 HTTP 复用同一 `AlcyoneContext`，不建第二套通知路径。
- `maxTurnBytes` 由 Server 以参数接收（默认与 `context.ingestion.maxTurnBytes` 默认值一致）；宿主可比对该配置后显式传入。
- `Application.ingestion` 与 `AlcyoneHttpServer.start` 为最小封装，不承诺生产级超时/限流/连接数治理。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 独立初验与 Architect 独立复核。

## 8. R4 错误映射 / R5 实例限额修复记录（2026-10-29）

| 项 | 落点 |
| --- | --- |
| R4 真实映射 | `IngestionHttpErrorMappingTest`：假 Context 触发异常，经真实 `testApplication` 请求断言 503/500 固定体；响应不含异常 message/类名、Key、正文或 jdbc 串 |
| R5 访问器 | `context/runtime` `IngestionLimitAware`（非 api 冻结契约）；`AlcyoneContextImpl` 返回实例 `context.ingestion.maxTurnBytes` |
| R5 组装 | `Application.ingestion` / `AlcyoneHttpServer.start` 只读访问器；不再接受可传错的显式限额或常量 |
| R5 测试 | 真实 Context `maxTurnBytes=256`：>256 的合法/非法 JSON 均在解析前 413、0 行；正常请求 201；默认 2 MiB 路径保持 |
| 复跑 | server 19 / core 11 / runtime 75 / persistence 155，源码 `@Test` = XML，差额 0；spotlessCheck 通过 |
