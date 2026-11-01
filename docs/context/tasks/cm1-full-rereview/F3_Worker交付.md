# CM1 全面复查 F3 Worker 交付（Server 注册配置三入口统一校验）

**任务**：CM1 全面复查 F3：`ServerConfig` 直接构造、YAML Loader、`IngestionClientRegistry.of` 共用同一套注册校验
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-29

## 本次修改（2026-10-29，F3 注册配置统一校验）

- 新增 `IngestionClientValidation`（internal）：`validateMap` / `validateCollection` 一套规则，供 `ServerConfig` 构造、`ServerConfigLoader`、`IngestionClientRegistry.from/of` 共用。
- `ServerConfig` 直接构造也走校验（之前只复制 Map），校验通过后才复制为自有快照。
- `IngestionClientRegistry.of` 不再用 `associateBy` 静默覆盖：先对 Collection 检测重复 runtimeId，再按 runtimeId 建 Map 并复用 `ServerConfig` 校验。
- 错误统一为 `ContextConfigException(INVALID_CONFIG)`，消息只含字段路径与 runtimeId，**不回显 apiKey**。
- 新增 `ServerClientValidationTest` 6 例。
- 未改 API/Core/Runtime/Persistence；未与其他修复合并；未 `git add/commit`。

## 1. 统一校验规则

| 规则 | 结果 |
| --- | --- |
| `runtimeId` 空白 | `INVALID_CONFIG`（字段路径） |
| Map key ≠ 配置对象 `runtimeId` | `INVALID_CONFIG`（说明必须等于 Map key） |
| `runtimeType` 空白 | `INVALID_CONFIG` |
| `apiKey` 空白 | `INVALID_CONFIG` |
| 重复 `runtimeId`（Collection 入口） | `INVALID_CONFIG`（`is registered more than once`，**不静默覆盖**） |
| 同一 `apiKey` 绑定不同 Runtime | `INVALID_CONFIG`（`apiKey conflicts with another registered runtime`） |

所有消息只引用 `server.ingestion.clients[.<runtimeId>].<field>` 与 runtimeId；不打印 Key。

## 2. 三入口实现

```kotlin
internal object IngestionClientValidation {
    fun validateMap(clients: Map<String, IngestionClientConfig>, path: String = BASE_PATH) {
        // key ↔ runtimeId 一致性，然后走 Collection 规则
    }
    fun validateCollection(clients: Collection<IngestionClientConfig>, path: String = BASE_PATH) {
        // blank 字段 / 重复 runtimeId / 重复 apiKey
    }
}
```

- `ServerConfig.init`：`validateMap(clients)` → 再 `LinkedHashMap(clients)` 快照。
- `ServerConfigLoader`：解析后 `validateMap(clients)`（去掉本地重复 Key 检查，规则单点）。
- `IngestionClientRegistry.of(clients)`：`clients.toList()` → `validateCollection` → 按 runtimeId 建 Map → `from(ServerConfig(clients = map))`；`from` 再校验一次快照。

认证语义不变：Bearer → runtimeId；401/403；runtimeType 不参与 SQL；同 Runtime 新 agent 允许。

## 3. 测试（新增 6 例）

| 用例 | 断言 |
| --- | --- |
| Collection 入口重复 runtimeId | `of(listOf(c1, c1dup))` → `INVALID_CONFIG`，消息含 runtimeId 与 `more than once`，不含两个 Key |
| 同一 apiKey 绑定不同 runtime | `of` 与 `ServerConfig(Map)` 两个入口都拒绝，消息含 `apiKey`，**不含 Key 值标记** |
| Map key 与 runtimeId 不一致 | 直接构造 `ServerConfig(mapOf("map-key" to client("actual-runtime", ...)))` → 拒绝，消息含 `map-key` |
| 空白 runtimeId / runtimeType / apiKey | 三个字段分别空白都被拒绝（Collection 与 Map 入口） |
| 快照独立 | 构造后修改原 Map（新增/替换），`clients()` 与 registry 认证不受影响 |
| 合法多 Runtime | 两个不同 runtime + 不同 Key 正常认证，未知 Key 返回 null |

既有 `ServerConfigLoaderTest`（6）、`IngestionHttpApiTest`（8）、`IngestionHttpErrorMappingTest`（3）、`InvalidUtf8BodyTest`（5）、`ServerSkeletonTest`（2）全部保持绿。

## 4. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `:context:server:spotlessApply :context:server:compileKotlin` | 0 | 生产编译 |
| `:context:server:spotlessApply` | 0 | 格式化；`spotless-apply.log` |
| `:context:server:test :context:server:spotlessCheck --rerun-tasks` | 0 | **30 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 30 = XML 30，**差额 0**；`targeted-test.log` |
| `:context:server:test :context:server:spotlessCheck :context:core:test :context:runtime:test :context:persistence-sqldelight:test --rerun-tasks` | 0 | 全绿：server 30 / core 11 / runtime 87 / persistence 167 |

server 30 = 原 24 + `ServerClientValidationTest` 6。

## 5. Architect 探针

`ServerBoundaryProbeTest`（用其 `probe.init.gradle` 复跑，不覆盖正式 XML）：

- `malformedUtf8IsNotSilentlyStored` → `MALFORMED_UTF8_STATUS=400`
- `duplicateRuntimeRegistrationMustNotSilentlyDiscardCredentials` → **由红转绿**（`BUILD SUCCESSFUL`）

## 6. 改动文件与 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `.../server/IngestionClientValidation.kt`（新增） | `3b5babc205c5da31c3f39b2e583e79601be4627d835455fef6110651997fc707` |
| `.../server/ServerConfig.kt` | `3886bf53cb6e3be4a3b016b01e6474a6cb7e1f3184f40d492114c9ffc6a2972b` |
| `.../server/ServerConfigLoader.kt` | `0448b95dfd5fc43d9babd612e76208281c835cf52f56725e4d81b9181873ca4b` |
| `.../test/ServerClientValidationTest.kt`（新增） | `a0454b1e28ffa6ebd39d0e52dc71533d31291d642b6c1f35e1e75bd711143b5c` |

## 7. 未做项与边界

- 未改 API/Core/Runtime/Persistence；未与 F1/P1/P4/F2 合并；未 `git add/commit`。
- 校验只约束注册集合本身；运行时认证语义（401/403、同 Runtime 新 agent）不变。
- `runtimeType` 仍只作注册元信息，不进入 SQL。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 初验与 Architect 独立复核。
