# CT06-B1（配置解析与映射）Lead 独立初验

## 本次修改（2026-10-28，CT06-B1 初验，提交 Architect 逐批复核）

- CT01～CT05 DONE；CT06 按 [架构任务书](架构任务书.md) 与 [方案_配置与生命周期](方案_配置与生命周期.md) 分 B1 配置、B2 生命周期两批。B1 由同一 Paseo worker 配置 Worker `d7695b22` 实现（单一写入者）；Lead 未写生产代码。
- B1 **不引入** `AlcyoneContext`/`start`/`close`/factory（避免假造生命周期），B2 再做；未改 VFS/Core/API/Persistence/CT04/VFS Loader；无 git 写操作。

## 1. 交付物（SHA 已核对）

| 文件 | SHA-256 |
| --- | --- |
| `ContextConfig.kt` | `cf9744bfdda427d94ee9afea4380c8a2630b8d301921c4985dcfc6a0a630dde6` |
| `ContextConfigLoader.kt` | `3ae47c62138176f55f35377bb720e7c57f8a6d84db3f275409feb7d1bcd02cd1` |
| `ContextConfigLoaderTest.kt` | `408480ea4cfa5ee73327340ef3eb91695a43bf0b8eb2629a209abfdc6a614d5d` |

## 2. Lead 独立实跑（非引用 Worker 日志）

| 检查 | 结果 |
| --- | --- |
| `:context:runtime:test :spotlessCheck --rerun-tasks` | exit 0；XML **18 例 0 失败/错误/跳过**；源码 `@Test`=18=XML 18，差额 0（新增 16 + 既有 skeleton 2） |
| 是否误引入生命周期 | `context/runtime/src/main` 无 `AlcyoneContext`/`start`/`close` 实现，仅注释说明 B2 |
| YAML 安全 | 使用 `Yaml(SafeConstructor(LoaderOptions().apply { setAllowDuplicateKeys(false) }))`，限制不安全类型且拒绝重复键 |

## 3. 关键实现与断言

- **阶段边界**：`server` 与其他顶层分区完全忽略；`context` 内蓝图已知键（database/lifecycle/llm/ingestion/organization/memoryMap/backup）中，llm/ingestion/organization/memoryMap/backup 暂不消费、不展开 ENV、不要求密钥；**真正未知字段仍 `UNKNOWN_FIELD`**（如 `context.database.unknown`）。测试覆盖 `context.ingestion` 为已知键且不误报未知。
- **路径**：相对 `context.database.path`、`vfs.database.path`、LocalFs `backend.root` 按 YAML 目录解析（非 CWD）；默认值 `./data/context.db`、`./data/vfs.db`。
- **ENV**：`${NAME}` 展开；缺失/空白 → `MISSING_ENV`，消息仅含字段路径与环境变量名、无 cause/值。
- **类型/范围**：14 组非法输入 → `INVALID_CONFIG`，消息仅含字段路径；重复 YAML 键安全拒绝。
- **映射**：LocalFs/WebDav 逐字段映射到 `MountConfig`/`MountBackend`；limits（默认 16 MiB）、`streamTotalLimit=null`、`eventBufferCapacity=256`、`closeGracePeriod=30s`、`shutdownTimeout=30s`。
- **脱敏**：WebDav 密码不出现在 `toString`、异常消息、cause/suppressed（独特标记断言）。
- **隔离**：`context.db == vfs.db`（同路径、父目录符号链接、已存在文件符号链接）拒绝；两库落在 LocalFs 挂载根真实路径内拒绝，同目录不同文件与 `storage-other` 兄弟前缀允许。
- **零副作用**：加载配置前后无新目录/文件创建；不要求父目录/挂载根存在（最近存在祖先 `toRealPath()` + 缺失后缀拼接，已有文件用 `Files.isSameFile` 兜底）。

## 4. 与 Architect 澄清的对应

- 澄清 1（ingestion 属 CT07、非永久忽略；阶段豁免 ≠ 未知字段豁免）已落实并测试。
- 澄清 2（不要求父目录存在、零创建、符号链接祖先、最近存在祖先规范化）已落实并测试。
- 澄清 3～5（CLOSED 后 close 共享结果、shutdownTimeout 仅观察上限、getStatus 用缓存事实）属 B2，已在方案 §4.1/4.3/4.5/4.6 定稿，B2 实现时按此。

## 5. 结论与边界

- **B1 初验通过**：18 例全绿、差额 0；配置字段/默认值/阶段边界/相对路径/ENV/未知字段/映射/脱敏/隔离/零创建均有真实 YAML 与文件系统断言。
- 提交 **Architect 逐批复核**；通过后启动 B2（factory/create、start/close、状态机、并发/竞争、失败清理、在途 JDBC 等待、显式初始化与拒启、getStatus、Runtime 集成）。CT07/CM2 未放行。

## 6. R1（悬空符号链接）修复后 Lead 独立复核（2026-10-28）

- **根因**：`canonicalizeForComparison` 以 `Files.exists`（跟随链接）找最近存在祖先，悬空链接被当作普通缺失后缀，未解析目标。
- **修复**：逐段用 `Files.isSymbolicLink`（不跟随）检测；命中链接时 `readSymbolicLink`（相对目标按链接父目录、绝对目标从根重解析）并继续剩余段；连续链接链维护已访问集合 + 40 次深度上限；循环/超深/读取失败 → `ContextConfigException(INVALID_CONFIG)`，消息含字段路径、无 cause、不回退词法路径。普通不存在多级父目录仍合法零创建；已有文件 `Files.isSameFile` 兜底。
- **Lead 独立实跑**：`:context:runtime:test :spotlessCheck --rerun-tasks` → exit 0；XML **21 例 0 失败/错误/跳过**；源码 `@Test`=21=XML 21，差额 0（原 18 + 新增 3）。日志 `.local/validation/cm1/lead/ct06/b1-fix-check.log`。
- **Lead 重跑 Architect `ConfigProbe`**（项目 JDK21）：`dangling file alias` 与 `dangling mount exposes planned context` 均被拒绝，`INVALID_CONFIG`，消息分别为「must not reference the same file」「must not be inside the local mount root」。日志 `.local/validation/cm1/lead/ct06/b1probe2/`。
- **KDoc 更正**：`ingestion` 改为属 CM1/CT07、CT06 暂不消费；`shutdownTimeout` 改为关闭等待在途的观察期限（非强杀上限）。
- **SHA**：`ContextConfig.kt`=`001579b1612c9d05…`、`ContextConfigLoader.kt`=`cd95c41835e9e72f…`、`ContextConfigLoaderTest.kt`=`2a465ac8d30ee8fb…`；未改 VFS/Core/API/Persistence/CT04；未扩 B2。
- **结论**：B1-R1 关闭，B1 初验维持通过，提交 Architect 复核；B2 待放行。

## 7. R2（大小写不敏感真实路径）修复后 Lead 独立复核（2026-10-28）

- **回归**：R1 版逐段拼接去掉了 `toRealPath()`，macOS 大小写不敏感 FS 上 `CaseRoot` 与 `caseroot` 同一目录被判为不同，导致挂载根别名暴露绕过（我独立复现 `existing case alias mount bypass=true`）。
- **修复**：规范化改为「先 `resolveSymlinkSegments` 逐段解析链接（悬空/相对目标/循环/40 次上限，不可解析 → `INVALID_CONFIG`，不回退词法）→ 再 `realPathForComparison`：存在用 `toRealPath()`，不存在用最近存在祖先 `toRealPath()` + 缺失后缀，失败 → `INVALID_CONFIG`」；不做全路径 lowercase；之后才做同文件/挂载根判断，已有文件 `isSameFile` 兜底。
- **Lead 独立实跑**：`:context:runtime:test :spotlessCheck --rerun-tasks` → exit 0；XML **22 例 0 失败/错误/跳过**；源码 `@Test`=22=XML 22，差额 0（R1 后 21 + 新增 1）。日志 `.local/validation/cm1/lead/ct06/b1-fix2-check.log`。
- **Lead 重跑 Architect `ConfigFixProbe`（JDK21）**：`dangling file alias`、`dangling mount`、`existing case alias` 三条均 `INVALID_CONFIG` 拒绝，消息可定位字段。日志 `.local/validation/cm1/lead/ct06/b1fixprobe2/`。
- **SHA**：`ContextConfigLoader.kt`=`83baa6845352d6af…`、`ContextConfigLoaderTest.kt`=`a6b6fd3d5f857a4f…`；`ContextConfig.kt`=`001579b1612c9d05…` 未变；未改 VFS、未扩 B2。
- **结论**：B1-R1/R2 关闭，B1 初验维持通过，提交 Architect 复核；B2 待放行。
