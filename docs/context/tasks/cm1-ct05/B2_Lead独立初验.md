# CT05-B2（Raw / Organized / Pending / Checkpoint）Lead 独立初验

## 本次修改（2026-10-28，CT05-B2 初验，提交 Architect 逐批复核）

- B1 已 APPROVED（CT05 复核第 4 节）。B2 由同一 Paseo worker 配置 Worker `d7695b22` 实现（单一写入者）；Lead 未写生产代码，只做独立复跑、代码审查与初验。
- 范围：Raw Memory、Organized Memory（含来源关联）、Organization Pending、Dreaming Checkpoint；未做 B3（ContextDatabase 装配/集成）、CT06/CT07；未改 Schema/索引/`user_version`/API/Core 接口/CT04 保护层/VFS/common/AGENTS/Architect 文件；无 git 写操作。

## 1. 交付物（SHA 已核对）

| 文件 | SHA-256 |
| --- | --- |
| `ContextSqlQueries.kt` | `ab3ade1b77f7a272e5e4004805dbf4e3384c1f93440c66aac2442a860513b038` |
| `ContextJsonCodecs.kt` | `9a372082dac4dbfbbc168da7efa65a08d1112f8db10d0b5c8c0073601380507c` |
| `ContextOps.kt` | `5fd723efb6a360b456744972be7a4cf0a6059f1621259bd479668dc380a0bd5e` |
| `ContextRepositories.kt` | `b8f395eeea4f327228aba1503acb3bdaf09dabe3acb2205b16137b2119c16135` |
| `ConversationRepositoryImpl.kt` | `854efd77022f85bc7456e300cd0e0e5dd417843e8a85d8250d132a63622ed769`（未改，与 B1 一致） |

CT04 保护层 4 文件仍为 `8cd7a1a9…`/`185ecbf9…`/`79fc3ac1…`/`7949dfed…`；`build.gradle.kts` 未改。

## 2. Lead 独立实跑（非引用 Worker 日志）

| 检查 | 结果 |
| --- | --- |
| `:context:persistence-sqldelight:test :spotlessCheck --rerun-tasks` | exit 0；XML **155 例 0 失败/错误/跳过**；源码行首 `@Test`=155=XML 155，差额 0（B2 前 128 + 新增 27） |
| 新代码是否裸用连接/驱动 | 新增 Ops/Repository 均绑定调用方 scope、经 `queryPrepared`/`updatePrepared`，未直接触碰 `connection`/`sqlDriver` |
| EXPLAIN（测试断言 + 日志） | `maxUpdatedAt` → `idx_organized_updated_at`；`findOrganizedByRaw` → `idx_source_raw`；`findSourcesByOrganized` → `sqlite_autoindex_organized_memory_source_1`；未新增索引 |

新增测试：`ContextRawMemoryRealDbTest`(6)、`ContextOrganizedMemoryRealDbTest`(8)、`ContextOrganizationPendingRealDbTest`(3)、`ContextDreamingCheckpointRealDbTest`(3)、严格 sources/domains 类型 4、EXPLAIN 3（并入 `ContextQueryPlanTest`）。

## 3. 关键语义审查

- **Raw**：`insertBatch` 真实行数；两种 `MemorySourceRef` 变体往返；执行器关闭后独立只读连接核对 `sources_json`/`generated_by`/`+08:00` 时间；`findByIds` 空集合不执行 SQL；`scanPage` 按 `created_at,id` 排序与分页；重复 id 约束失败且原行不变；写事务抛错回滚、提交行重开可见。
- **Organized**：`insertNew`/`update`/`insertSourceLink`/`advanceUpdatedAt` 真实行数；重复关联冲突；正/反查集合一致；`findById` 从关联表补齐 `rawMemoryIds`；FK RESTRICT（有来源/pending 时删 raw、有来源时删 organized 均失败且行仍在，解除后删除成功）；同事务多条水位严格递增且可见。
- **Pending**：`enqueue` 幂等（1→0）、`dequeue` 行数；`nextByRawCreatedAt` 按 raw `created_at,id` 与入队顺序无关；入队缺失 raw 外键失败。
- **Checkpoint**：初始 0；CAS 匹配且 `next` 向前才更新（1 行），不匹配/相等/后退为 0 且值不变；回滚保持原值。
- **严格 JSON**：`sources_json` 按 `ConversationTurnRef`/`RawMemoryRef` 两变体校验（`type` 字符串且已知、必需字段存在且为字符串）；`domains_json` 仅字符串数组；数值/布尔/null/对象/数组/缺字段/未知 type 一律 `PERSISTENCE_UNAVAILABLE`，异常链不回显原文、无 cause；合法值（含 `"123"`/中文/emoji）顺序保持、空数组合法；注入经受控 `updateRaw` 且先断言行数=1 并回读原文。

## 4. 需 Architect 确认的一点（Lead 意见：可接受，但建议定稿）

- `sources_json` 采用持久化层内部 snake_case 编码：`{"type":"conversation_turn","runtime_id":…,"agent_id":…,"session_id":…,"session_turn_id":…}` 与 `{"type":"raw_memory","memory_id":…}`；`domains_json` 为字符串数组。公开契约仍是 `MemorySourceRef`，当前无迁移负担。Lead 意见：作为内部约定可接受，建议在进入 CM2 前确认定稿，避免后续迁移；本批不为此新增抽象或改 Schema。

另：B2 为新增功能，Worker 先落盘生产再补测试，故 `probe-before.log` 是首跑即绿记录，未伪造红；交付文档已如实说明（任务书对新增功能允许「若实现未写」不强制红阶段）。

## 5. 结论与边界

- **B2 初验通过**：155 例全绿、差额 0，严格类型、关联完整性、条件行数、全局水位、CAS/回滚与查询计划均有正式断言。
- 提交 **Architect 逐批复核**；通过后再启动 B3（ContextDatabase 装配与集成）。B2 通过不代表 CT05 完成；CT06/CT07 未启动。
