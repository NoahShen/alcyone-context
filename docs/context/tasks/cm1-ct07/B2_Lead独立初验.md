# CT07-B2（Server 最小 HTTP）Lead 独立初验

## 本次修改（2026-10-29，CT07-B2 初验，提交 Architect 逐批复核）

- B1 已 APPROVED（复核 §3）。B2 由同一 Paseo worker 配置 Worker `d7695b22` 实现（单一写入者）；Lead 未写生产代码。
- 范围：Server 配置/静态 Bearer 认证/runtime_id 绑定/最小 POST/strict JSON/时间 offset/流式限额/错误映射/生命周期与关闭；未改 VFS/CT04/CT05/CT06、未改 B1 Core/API 语义；未发布/部署；无 git 写操作。

## 1. 交付物（SHA 已核对）

| 文件 | SHA-256（前缀） |
| --- | --- |
| `context/server/build.gradle.kts` | `499ea80c841f87ef` |
| `server/ServerConfig.kt` | `fbe37fe87f8301ef` |
| `server/ServerConfigLoader.kt` | `4ec30802f09c1498` |
| `server/IngestionHttpApi.kt` | `bd83630535ff7b16` |
| `server/AlcyoneHttpServer.kt` | `cda3dc7fa487faa1` |

## 2. Lead 独立实跑（非引用 Worker 日志）

| 模块 | 结果 |
| --- | --- |
| `context:server:test` | **15 例**（HTTP 7 + 配置 6 + skeleton 2），0 失败/错误/跳过，源码=XML=15 |
| `context:core:test` | 11 例，源码=XML |
| `context:runtime:test` | 75 例，源码=XML |
| `context:persistence-sqldelight:test` | 155 例，源码=XML |
| `server spotlessCheck` | exit 0 |

日志 `.local/validation/cm1/lead/ct07/b2-check.log`。

## 3. 关键实现与断言审查

- **依赖边界**：Server 仅依赖 `context.runtime`、`context.core`（复用 `IngestionTimestampText`）、serialization、snakeyaml、Ktor Server；**未直接访问 Persistence/JDBC**（grep `persistence|java.sql|Connection` 为空）。
- **配置**：`server.host/port/ingestion.clients`；SafeConstructor + 禁重复键；`${ENV}` 展开；三层未知字段 `UNKNOWN_FIELD`；类型/范围/Key 冲突 `INVALID_CONFIG`；ENV 缺失/空白 `MISSING_ENV`；错误不回显 Key。`ServerConfig` 持有自有 Map，`clients()` 只读视图，`IngestionClientRegistry` 再拷贝快照——调用者修改原 Map 不能绕过认证（有专门测试）。
- **认证**：Bearer → runtimeId；缺失/格式错/未知 Key 401；`runtime_id` 不匹配 403；同 Runtime 新 agent 允许；多 Runtime 隔离。
- **HTTP**：strict JSON `ignoreUnknownKeys=false`（未知顶层/消息字段 400，不回显正文）；`timestamp` 经 Core `IngestionTimestampText`（无 offset/纯日期/非法 400；`+08:00` 与 `Z` 等价重复 200）；**限额先于完整反序列化**（`Content-Length` + 流式 `readBodyUpTo`，超 2 MiB → 413 且库内 0 行）；messages/ID 超限 413；全部校验/幂等复用同一 `AlcyoneContext.ingestConversation`。
- **映射**：CREATED→201、DUPLICATE→200（同 id）、INVALID_TURN→400、TURN_CONFLICT→409、PAYLOAD_TOO_LARGE→413、PERSISTENCE_UNAVAILABLE→503、其他→500；未启动/关闭中→503；响应体固定、不含 Key/正文/连接串。
- **生命周期/关闭**：未启动与关闭后 503；已接纳请求（COMMIT 完成、返回前受控阻塞）+ close 不死锁且请求返回 201；CIO 封装实测未启动 503 → 启动后 201。
- **并发**：同键两个 HTTP 请求 `{201, 200}`、同一 id、库内 1 行。
- **通知语义**：沿用 B1（CREATED-only、DUPLICATE 不通知、失败不影响结果），HTTP 复用同一 `AlcyoneContext`，未另建路径。

## 4. 结论与边界

- **B2 初验通过**：配置/认证/绑定/strict JSON/offset/流式限额/错误映射/生命周期/并发/脱敏均有 Ktor test host + 真实 SQLite 断言，计数与差额 0。
- 提交 **Architect 逐批复核**；B2 未通过不启动 CT07 整体验收。CT07 整体通过后才做 CM1 整体验收；**CM2 不启动**，不发布/不部署。

## 5. R4/R5 修复后 Lead 独立复核（2026-10-29）

- **R4 错误映射**：新增 `IngestionHttpErrorMappingTest`（受控假 `AlcyoneContext` + `testApplication` 真实 HTTP）：`PERSISTENCE_UNAVAILABLE` → 503、其他持久化错误码 → 500、未预期 `RuntimeException` → 500；响应体固定为 `{"error":"SERVICE_UNAVAILABLE"}` / `{"error":"INTERNAL_ERROR"}`，断言不含异常 message、类名、Key、正文标记、`jdbc:sqlite` 连接串。
- **R5 限额一致性**：runtime 新增非 api 的 `IngestionLimitAware { val ingestionMaxTurnBytes }`，由 `AlcyoneContextImpl` 返回实例配置快照；`Application.ingestion`/`AlcyoneHttpServer.start` 删除显式/常量限额，只读该访问器（不是 `IngestionLimitAware` 则拒绝组装）。测试：`maxTurnBytes=256` 时，>256 但远小于 2 MiB 的合法 JSON 与超限非法 JSON 均在**完整反序列化前 413**、库内 0 行；正常请求 201；默认 2 MiB 路径保留。
- **Lead 独立实跑**：`:context:server:test :server:spotlessCheck :core:test :runtime:test :persistence:test :runtime:spotlessCheck :core:spotlessCheck --rerun-tasks` → exit 0；server **19**（HTTP 8 + 错误映射 3 + 配置 6 + skeleton 2）、core 11、runtime 75、persistence 155，均 0 失败/错误/跳过，源码=XML 差额 0。日志 `.local/validation/cm1/lead/ct07/b2-fix-check.log`。
- **SHA**：`IngestionLimitAware.kt`=`935968254ea16539`；`AlcyoneContextImpl.kt`=`097a563b862baac9`；`IngestionHttpApi.kt`=`9efbeb36f418070d`；`AlcyoneHttpServer.kt`=`2ed984607541c29b`；`IngestionHttpErrorMappingTest.kt`=`469289aff3479f87`；api `AlcyoneContext.kt`=`cbf598f793027850`（未变）。
- **结论**：B2-R4/R5 关闭，B2 初验通过，提交当前 Architect 复核；B2 通过前不启动 CT07 整体收口，CM1 整体验收与 CM2 不启动。
