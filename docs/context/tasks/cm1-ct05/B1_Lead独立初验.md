# CT05-B1（Conversation）Lead 独立初验

## 本次修改（2026-10-28，CT05-B1 初验，提交 Architect 逐批复核）

- 依据 [架构任务书](架构任务书.md) 与 [方案_SQL_Port_测试映射](方案_SQL_Port_测试映射.md)，CT04 已 APPROVED/DONE 后启动 CT05。B1 由按 fresh `list_profiles` 原样应用 `worker` 配置（pi / my_magpie/group/worker，thinking high）创建的 Worker `d7695b22` 实现（单一写入者）；Lead 未写生产代码，只做独立复跑、代码审查与初验。
- 范围：仅 Conversation 批次；未做 B2/B3、ContextDatabase 装配、integration-tests/context、CT06/CT07；未改 Schema/索引/`user_version`/API/Core 接口；未改 VFS/common/进度入口/AGENTS/Architect 文件；无 git 写操作。

## 1. 交付物（SHA 已核对）

| 文件 | SHA-256 |
| --- | --- |
| `ContextSqlQueries.kt` | `6cb83f64c59361b61d11382ee8d834696b34ff0f49efbbf467b632acb5305839` |
| `ContextJsonCodecs.kt` | `f7faaa5b61534e56291d2bbca5173fc12ccd94766358af90bc09219516ad561c` |
| `ContextOps.kt` | `de4e2e6999a9e4666f701c0688e0834103f72fa68ec87ce3983f075ec19704d7` |
| `ConversationRepositoryImpl.kt` | `854efd77022f85bc7456e300cd0e0e5dd417843e8a85d8250d132a63622ed769` |
| `context/persistence-sqldelight/build.gradle.kts` | `d5f61c66cbd9a96649b0df1cce57bdb7892505f9741828075fffdfe406124af9`（仅新增 `implementation(libs.serialization.json)`，版本来自既有 Catalog） |

CT04 受保护层 4 文件 SHA 与 F3 rev5 证据逐字一致（`8cd7a1a9…`/`185ecbf9…`/`79fc3ac1…`/`7949dfed…`），未改动。

## 2. Lead 独立实跑（非引用 Worker 日志）

| 检查 | 结果 |
| --- | --- |
| `:context:persistence-sqldelight:test :spotlessCheck --rerun-tasks` | exit 0；XML **120 例 0 失败/错误/跳过**；源码行首 `@Test`=120=XML 120，差额 0（基线 91 + 新增 29） |
| 新代码是否直接触碰连接/驱动 | `ContextOps`/`ConversationRepositoryImpl`/`ContextSqlQueries`/`ContextJsonCodecs` 均无 `connection`/`createStatement`/`sqlDriver`/`getConnection`；全部经 `queryPrepared`/`updatePrepared` 走 CT04 受控 scope |
| EXPLAIN（Worker 日志 + 测试断言） | 有/无锚点窗口均 `idx_turn_session_time`；latestPending/countPending 均 `idx_turn_pending_session_time`（covering）；idle 用 `idx_turn_session_created_at` |

新增测试文件：`ContextConversationRepositoryRealDbTest`(15)、`ContextConversationTxOpsRealDbTest`(4)、`ContextJsonTimeMappingTest`(6)、`ContextQueryPlanTest`(4)、`ConversationFixtures.kt`。

## 3. 关键语义审查

- **幂等/冲突**：同键同规范化时间与有序 messages → `DUPLICATE`，返回原 id 且不刷新 `created_at`；异消息/乱序/异时间 → `ContextIngestionException(TURN_CONFLICT)` 且原行不变（测试断言原行内容）。
- **窗口**：含锚点自身，`timestamp < anchor OR (timestamp = anchor AND id <= anchorId)`，DESC 取 limit 后反序返回；会话隔离。
- **空闲**：必须有 pending 且**全状态** `MAX(created_at) <= idleBefore`；`findIdleSessions` 用 GROUP BY + HAVING 同口径。
- **计数/边界**：pending 计数仅统计目标会话；`maxTurnId` 空表 null；`idRange` 下界排除、上界包含、升序 limit。
- **事务**：TxOps 与 ReadOps 共享调用方 scope；事务内读取可见未提交写入；`markComplete/markFailed` 返回真实 `executeUpdate` 行数（实测 1/0），非 pending 不命中。
- **JSON/时间**：顺序/转义/中文/emoji 往返；`+08:00` 毫秒文本；执行器关闭后由独立只读连接核对落库；非法 JSON/角色/空数组/非规范时间 → `PERSISTENCE_UNAVAILABLE` 且不回显原文、无 cause。
- **scope 绑定**：Repository 每方法一次 `executor.read {}` 短作用域，不缓存 scope/连接；Ops 不自开 `read/write`，不复用跨回调 scope。

## 4. 需 Architect 判定的两处偏差（Lead 意见）

1. **`sessionWindow` 拆成两条参数化 SQL**（有锚点/无锚点）。原因：CT04 `ScopedJdbc` 标量绑定白名单不支持 NULL（`setString(index, null)` 在转换处 NPE），而 CT04 保护层已冻结。Worker 未绕过 guard、未拼值、未改 CT04。Lead 意见：**可接受**，两条均参数化且 EXPLAIN 使用 `idx_turn_session_time`；如后续需要单一 SQL，可在 B2/B3 前单独评审是否给 `ScopedJdbc` 增加 NULL 绑定白名单（需复核）。
2. **文件名 `ConversationRepositoryImpl.kt`** 而非方案中的 `ContextRepositories.kt`：ktlint `standard:filename` 要求唯一类名与文件名一致。Lead 意见：**可接受**，B2 多类时可按需合并。

另：跨进程并发同键写入会以 UNIQUE 约束的 `PERSISTENCE_UNAVAILABLE` 暴露；执行器单写者契约内已串行化为 `DUPLICATE`（与 Port 契约一致，不作为本批缺陷）。

## 5. 结论与边界

- **B1 初验通过**：120 例全绿、差额 0，SQL 全参数化、scope 绑定与 CT04 保护一致，幂等/冲突/窗口/空闲/边界/JSON/时间/EXPLAIN 均有正式断言。
- 提交 **Architect 逐批复核**；通过后再启动 B2（Raw/Organized/Pending/Checkpoint）。B1 通过不代表 CT05 完成，CT06/CT07 仍未启动；CT05 整体通过后才放行 CT06。

## 6. R1 修复后 Lead 独立复核（2026-10-28）

- **修复内容**：`ContextJsonCodecs.decodeMessage` 的 `role`/`content` 统一经 `stringField`：`as? JsonPrimitive` + 显式 `isString`，任一不满足即 `PERSISTENCE_UNAVAILABLE`；不再使用会把 `123`/`true` 转成字符串的 `contentOrNull`。
- **Lead 独立实跑（非引用 Worker 日志）**：
  - `:context:persistence-sqldelight:test :spotlessCheck --rerun-tasks` → exit 0；XML **128 例 0 失败/错误/跳过**；源码行首 `@Test`=128=XML 128，差额 0（基线 91 + 37 = 15+4+14+4）。日志 `.local/validation/cm1/lead/ct05/b1-fix-check.log`。
  - Lead 自建逐条捕获探针 `B1StrictProbe`（受控 `updateRaw` 注入并断言行数=1，再经 Repository 读取）：
    - content 为 `123` / `true` / `null` / `{}` / `[1]` 全部 **REJECTED**，类型 `ContextPersistenceException`、码 `PERSISTENCE_UNAVAILABLE`、`chainLeaksRaw=false`；
    - 正常字符串 `"123"` 注入 rows=1 且可读为 `123`；
    - 汇总 `rejected=5 accepted=0`。日志 `.local/validation/cm1/lead/ct05/b1probe2/`。
  - 原 Architect `B1Probe` 首个非法用例被拒后未捕获即退出（期望拒绝）；Worker 适配版逐条捕获同样确认两条均拒绝（`ct05-b1-fix/b1probe-after.log`）。
- **SHA**：`ContextJsonCodecs.kt`=`2c56ec953fb8d258bb53a59dd17e27b2363f6a46524b8d9cedbe9b8cc00edab6`；`ContextJsonTimeMappingTest.kt`=`cc115309d765c19f4ef6e88d662abb9108a9b93f17b0b1331ea60543918b9d7f`；其余生产/测试与初版一致；CT04 保护层仍为 `8cd7a1a9…`/`185ecbf9…`/`79fc3ac1…`/`7949dfed…`。
- **口径更正已落实**：NULL 说明限定为「当前 `ScopedJdbc` 的 nullable string 绑定（`setString(index, null)`）不支持」，不泛称所有 NULL/SQLDelight；交付文档不再称「已生成业务查询」，明确为 Kotlin SQL 常量 + SQLDelight 同步绑定/游标经受控 scope 执行；非法 JSON 注入经受控 `updateRaw` 并断言行数=1，只读连接仅用于落库核对。
- **结论**：B1-R1 关闭，B1 初验维持通过，提交 Architect 复核；B2 待放行，CT06/CT07 未启动。
