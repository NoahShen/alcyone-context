# F3（Server 注册配置三入口统一校验）Lead 独立初验

## 本次修改（2026-10-29，全面复查 F3 初验，提交 Architect 复核）

- F2 已 APPROVED。F3 由同一 Paseo worker 配置 Worker `d7695b22` 单独批次实施（未合并其他修复）；Lead 未写生产代码。
- 范围：仅 `context/server` 与测试；未改 API/Core/Runtime/Persistence；无 git 写操作。

## 1. 交付物（SHA 已核对）

| 文件 | SHA-256（前缀） |
| --- | --- |
| `server/IngestionClientValidation.kt`（新增） | `3b5babc205c5da31` |
| `server/ServerConfig.kt` | `3886bf53cb6e3be4` |
| `server/ServerConfigLoader.kt` | `0448b95dfd5fc43d` |
| `test/ServerClientValidationTest.kt`（新增） | `a0454b1e28ffa6eb` |

## 2. Lead 独立实跑（非引用 Worker 日志）

| 模块 | 结果 |
| --- | --- |
| `context:server:test` | **30 例**（原 24 + 新增 6），0 失败/错误/跳过，源码=XML=30 |
| `context:core:test` | 11 例，0 失败 |
| `context:runtime:test` | 87 例，0 失败 |
| `context:persistence-sqldelight:test` | 167 例，0 失败 |
| `server spotlessCheck --rerun-tasks` | exit 0 |

日志 `.local/validation/cm1/lead/rereview-f3/check.log`。

**Lead 独立复跑 Architect 探针**（`-I .local/validation/cm1/architect/full-rereview/probe.init.gradle`，正式 XML 已备份并还原）：`BUILD SUCCESSFUL`，`ServerBoundaryProbeTest` 全部通过（含此前失败的 `duplicateRuntimeRegistrationMustNotSilentlyDiscardCredentials`）。日志 `probe.log`。

## 3. 实现审查

新增 internal `IngestionClientValidation`（`validateMap` / `validateCollection`），**三入口共用**：

| 入口 | 调用点 |
| --- | --- |
| `ServerConfig(clients=Map)` 直接构造 | `init` 内 `validateMap(clients)`，**校验通过后才** `LinkedHashMap(clients)` |
| YAML Loader | `ServerConfigLoader` 内 `validateMap(clients)` |
| `IngestionClientRegistry.from(config)` | `validateCollection(config.clients().values)` 二次校验（防未来绕过） |
| `IngestionClientRegistry.of(Collection)` | 先 `toList()` 快照 → `validateCollection` → 建 Map → 复用 `ServerConfig` 校验 |

规则：`runtimeId` 非空白；**Map key 必须等于 `runtimeId`**；`runtimeType`/`apiKey` 非空白；**重复 `runtimeId` 具名拒绝**（`is registered more than once`）；**同一 `apiKey` 绑定不同 Runtime 具名拒绝**。错误统一 `ContextConfigException(INVALID_CONFIG)`，消息只含字段路径 + runtimeId，**不回显 apiKey**。

`of` 不再使用 `associateBy { it.runtimeId }`；重复在校验阶段即抛出，**不会到达 Map 写入**，故无静默覆盖路径。`from` 内 `byKey[client.apiKey] = client` 亦以重复 Key 已拒绝为前提。

## 4. 测试核对（6 例）

| 用例 | 关键断言（已核实） |
| --- | --- |
| `collection entry rejects duplicate runtime ids instead of silently overwriting` | `of(listOf(dup, dup))` 具名拒绝，**不静默丢弃任一注册** |
| `same api key bound to different runtimes is rejected on every entry` | `of` 与 `ServerConfig(Map)` 均拒绝；消息含字段名 `apiKey` 但**不含 Key 值标记**（`assertFalse(contains(keyMarker))`） |
| `map key must equal the configured runtime id on direct construction` | 直接构造 Map key ≠ runtimeId → 拒绝（不能绕过） |
| `blank runtime id runtime type and api key are rejected` | 三者空白各入口拒绝 |
| `registry and config keep their own snapshot when the caller mutates the original map` | 改原 Map 后：原 Key 仍可认证、后加/被替换 Key 无效、`config.clients().keys` 不变 |
| `collection entry accepts a valid multi runtime snapshot` | 合法多 Runtime 正常认证 |

既有 server 认证（401/403）、strict JSON、非法 UTF-8、幂等 201/200、409、503 全部保持通过。

## 5. 结论与边界

- **F3 初验通过**：三入口统一校验且不可绕过；重复 runtimeId / 同 Key 不同 Runtime / Map key 不一致 / 空白均具名拒绝；快照自有；错误不回显 Key；Architect 原失败探针已转绿。
- 提交 **Architect 独立复核**；通过后继续 **P2/P3 → F4**；全部闭环并整体回归后直接启动 CT08。**CM2 实现仍未启动**；未 git 提交/发布。
