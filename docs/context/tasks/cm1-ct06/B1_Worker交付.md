# CT06-B1 Worker 交付（配置模型 / 解析 / 映射 / 隔离 / 脱敏）

**任务**：CM1 CT06-B1：`ContextConfig` 模型与 YAML 配置加载（不引入 `AlcyoneContext` / `start` / `close`）
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db（Paseo lead 配置，只做方案与独立初验）
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-28

## 本次修改（2026-10-28，B1 复核修复 R2：大小写不敏感真实路径）

- 按 Architect 复核 §2 定点修复，只改 `ContextConfigLoader.kt` 规范化与正式测试；未改 VFS、未扩 B2。
- 根因：R1 的逐段链接解析完全去掉 `toRealPath`，macOS 大小写不敏感文件系统上 `CaseRoot` 与 `caseroot` 同一目录被当作不同目录。
- 修复：保留 R1 的悬空链接逐段解析（`isSymbolicLink`/`readSymbolicLink`/连续链集合/40 次深度上限，循环或不可解析 → `INVALID_CONFIG`、不回退词法）；在其结果上**恢复真实路径规范化**：存在 → `toRealPath()`，不存在 → 最近存在祖先 `toRealPath()` + 不存在后缀；解析失败 → `INVALID_CONFIG`，不回退词法；顺序为「链接解析 → 真实路径规范化 → 同文件/挂载根判断」，已有文件仍用 `Files.isSameFile` 兜底。不做全路径 lowercase，避免误伤大小写敏感文件系统。
- 先补真实文件回归并确认 **1 红**（macOS：`CaseRoot` 挂载 + `./caseroot/context.db` 被接受）；测试会先检测大小写敏感性，敏感文件系统则断言为不同目录且允许，避免误伤。修复后 **22 例全绿**，源码 `@Test` 22 = XML 22，差额 0。
- Architect `ConfigFixProbe` 重跑：dangling 两条均拒绝，existing case alias 拒绝（无 `bypass=true`）。
- 证据 `.local/validation/cm1/worker/ct06-b1-fix2/`；未 `git add/commit`，未自标 DONE。

## 本次修改（2026-10-28，B1 复核修复 R1：悬空符号链接规范化）

- 按 Architect 复核 §1 R1 定点修复，只改 `ContextConfigLoader.kt` 路径规范化 + 两个生产文件 KDoc + 正式测试；未改 VFS、未扩 B2、未改其他生产文件。
- 根因：`canonicalizeForComparison` 用跟随链接的 `Files.exists` 找祖先，悬空链接被当作普通缺失后缀；`toRealPath` 失败后回退词法路径并假定隔离成立。
- 修复：逐段解析绝对路径，用 `Files.isSymbolicLink`（对悬空链接同样为 true）检测链接，`readSymbolicLink` 取目标（相对目标按链接父目录、绝对目标从根重新解析）；连续链接链的已访问集合 + 40 次深度上限检测循环；无法可靠解析 → `INVALID_CONFIG`（消息含字段路径、无 cause），**不回退词法路径**。普通不存在的多级父目录仍合法且零创建。
- 先补 3 条真实文件反例并确认 **3 红**：悬空文件别名、悬空挂载链接暴露计划路径、悬空链接循环；修复后 21 例全绿，源码 `@Test` 21 = XML 21，差额 0。
- Architect ConfigProbe 重跑：两条反例均被拒绝（`dangling file alias rejected=…same file`、`dangling mount rejected=…inside local mount root`）。
- KDoc 更正：`context.ingestion` 明确为 CM1（CT07）范围、CT06 暂不消费、CT07 接线时纳入校验生效；`shutdownTimeout` 改为「关闭等待在途退出的观察期限（非强杀上限）」。
- 证据 `.local/validation/cm1/worker/ct06-b1-fix/`；未 `git add/commit`，未自标 DONE。

## 本次修改（2026-10-28，CT06-B1 配置）

- 新增 `ContextConfig.kt`：CM1 生效字段 `databasePath`、`lifecycleShutdownTimeout`，以及映射后的既有 `VfsRuntimeConfig`；不含生命周期/资源句柄。
- 新增 `ContextConfigLoader.kt`：真实 YAML（SnakeYAML `SafeConstructor` + 禁止重复键）、`${ENV}` 展开与脱敏报错、负责分区未知字段拒绝、`server` 与蓝图未实现子树忽略、相对路径按 YAML 目录解析、`VfsRuntimeConfig`/LocalFs/WebDav 映射、数据库同文件与 LocalFs 挂载根隔离。
- 新增 `ContextConfigLoaderTest.kt` 20 例（真实临时目录与 YAML）：最小配置/默认值、路径基准、ENV 缺失/空白、未知字段、`server` 忽略、`context.ingestion` 阶段豁免、类型/限额、LocalFs/WebDav 逐字段、凭据脱敏、同文件/符号链接别名/不存在多级父目录、挂载根暴露、零创建。
- 按 Architect 5 点澄清落实 B1 相关项：`context.ingestion` 视为 **CM1（CT07）已知键**，CT06 暂不消费但不得报未知、不得要求 ENV；真正未知字段仍 `UNKNOWN_FIELD`。路径规范化使用「最近存在祖先 `toRealPath()` + 不存在后缀」，**不要求父目录/挂载根存在**，配置阶段零创建；已有文件用 `Files.isSameFile` 兜底。
- 未改 VFS/Core/API/Persistence/CT04 保护层，未改 VFS Loader；未做 B2 生命周期、CT07、CM2；未 `git add/commit`。

## 1. 交付文件与落点

| 文件 | 内容 | 关键落点 |
| --- | --- | --- |
| `context/runtime/.../ContextConfig.kt` | 配置模型 | `databasePath`、`lifecycleShutdownTimeout`、`vfsConfig`、`vfsDatabasePath` |
| `context/runtime/.../ContextConfigLoader.kt` | YAML 加载/校验/映射/隔离 | `load(Path)` 生产入口；`load(Path, environment)` 为 internal 测试注入；`readYaml`、`parseMount`、`parseLimits`、`assertDistinctDatabases`、`assertOutsideLocalMounts`、`canonicalizeForComparison` |
| `context/runtime/src/test/.../ContextConfigLoaderTest.kt` | 16 例配置测试 | 见第 4 节 |

`toString()`：`ContextConfig`/`VfsRuntimeConfig`/`MountConfig` 的打印最终落到 `MountBackend.WebDav.toString`（VFS 已脱敏），密码不出现在任何字符串；Loader 自身不写日志。

## 2. CM1 字段与阶段边界

生效并校验：`context.database.path`（默认 `./data/context.db`）、`context.lifecycle.shutdownTimeout`（默认 `30s`）、`vfs.database.path`（默认 `./data/vfs.db`）、`vfs.namespaces`（默认空）、`vfs.mounts`（默认空；`vfsPath`/`storageKey`/`backend.type=local|webdav` 及各后端字段）、`vfs.limits.defaultReadMaxBytes`/`defaultWriteMaxBytes`（默认 16 MiB）、`vfs.streamTotalLimit`（默认 null）、`vfs.eventBufferCapacity`（默认 256）、`vfs.closeGracePeriod`（默认 30s）。

阶段豁免（接受、不校验、不生效、不展开 ENV）：`context.llm.*`、`context.organization.*`、`context.memoryMap.*`、`context.backup.*`；`context.ingestion.*` 属 **CM1（CT07）范围**，CT06 只是暂不消费，CT07 接线时必须纳入校验与生效。`server` 顶层完全忽略（由 Server 层负责）。**真正未知的 `context`/`vfs` 字段仍报 `UNKNOWN_FIELD`**，阶段豁免不等于全面忽略。

## 3. 错误契约与脱敏

- `MISSING_ENV`：消息只含字段路径与环境变量名（如 `context.database.path` / `CT06_B1_MISSING_VALUE`），不含值。
- `UNKNOWN_FIELD`：消息含具体路径（如 `context.database.unknown`、`vfs.mounts[0].backend.unknown`）。
- `INVALID_CONFIG`：类型/范围/路径/YAML 解析失败；消息只含字段路径与原因，不回显原始值；`cause` 为空。
- WebDAV 密码只存在于 `MountBackend.WebDav` 字段里，不进入异常消息、cause/suppressed 链或 `toString`（测试用独特标记断言）。

## 4. 测试与断言（20 例，真实临时目录 + YAML）

| 用例 | 断言 |
| --- | --- |
| 最小配置/默认值 | 两个数据库路径按 YAML 目录解析；shutdown=30s；namespaces/mounts 空；限额 16 MiB；streamTotalLimit null；eventBufferCapacity 256；closeGracePeriod 30s |
| 空 YAML | 全部走默认值（含两个默认数据库路径） |
| 相对路径基准 | 嵌套目录中的 `./data/context.db` 解析到 YAML 所在目录，而非 CWD |
| ENV 展开/缺失/空白 | 已定义值正确展开；缺失与 trim 空白均 `MISSING_ENV`，消息含路径与 ENV 名、无 cause |
| `context.ingestion` 阶段豁免 | 出现 `ingestion` 且含 `${...}` 不报未知、不读取 ENV；同文件 `context.database.unknown` 仍 `UNKNOWN_FIELD` |
| 蓝图未实现子树 | `llm/ingestion/organization/memoryMap/backup`（含非法值）整体接受，不触发 ENV |
| 未知字段 | `context.database.unknown`、`context.unknownTop`、`context.lifecycle.unknown`、`vfs.unknownTop`、`vfs.limits.unknown`、`vfs.mounts[0].backend.unknown` 均 `UNKNOWN_FIELD` 且消息含路径 |
| `server` 分区 | 内含缺失 ENV 也不读取、不报错 |
| 类型/范围 | 路径非字符串、负 duration、限额 ≤0、容量 <1、streamTotalLimit <0、namespaces 非字符串、mounts 非列表、`vfsPath=/`、空 storageKey、未知 backend.type、缺 root 等 14 组均为 `INVALID_CONFIG` 且含字段路径 |
| 重复 YAML 键 | `INVALID_CONFIG`（"not valid YAML"），不回显重复值 |
| LocalFs/WebDav 映射 | 命名空间集合、两块挂载的 `vfsPath`/`storageKey`、LocalFs 绝对根、WebDav endpoint/root/username/password 逐字段相等 |
| 凭据脱敏 | `ContextConfig`/`VfsRuntimeConfig`/mounts 的 `toString` 与失败异常完整链均不含密码标记 |
| 同文件隔离 | 直接同路径拒绝；父目录符号链接别名（文件均不存在）拒绝；已存在文件的符号链接别名经 `Files.isSameFile` 拒绝；均不创建文件 |
| 不存在多级父目录 | 符号链接祖先 + 多级不存在父目录可正常加载并零创建；同一计划路径经不同别名仍判等拒绝；不存在的挂载根不要求创建 |
| 挂载根隔离 | context.db / vfs.db 落在挂载根内拒绝（含符号链接解析后的真实根）；同目录不同文件与 `storage-other` 前缀兄弟目录允许 |
| 零创建 | 加载后 `data`/`state` 目录与数据库文件均不存在 |
| R1 悬空文件别名 | `alias.db -> target.db`（target 不存在），context 用 alias、vfs 用 target → `INVALID_CONFIG`，消息含 `database.path`、无 cause、两文件均未创建 |
| R1 悬空挂载链接 | `mount-link -> future-root`（root 不存在），LocalFs root=mount-link、context=future-root/context.db → `INVALID_CONFIG`，消息含 `context.database.path`、未创建 future-root |
| R1 悬空链接循环 | `a -> b`、`b -> a` → `INVALID_CONFIG`（字段路径 + 无 cause），零创建 |
| R1 回归 | 既有「多级不存在父目录 / 符号链接祖先」用例保持绿 |
| R2 大小写别名 | 先检测文件系统：`CaseRoot` 与 `caseroot` 为同一真实目录时，挂载 `./CaseRoot` + context `./caseroot/context.db` → `INVALID_CONFIG`（消息含字段路径、无 cause、零创建）；大小写敏感文件系统则断言为不同目录且允许 |

## 5. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `./scripts/dev gradle :context:runtime:spotlessApply :context:runtime:compileKotlin --console=plain` | 0 | 生产批次编译通过 |
| `./scripts/dev gradle :context:runtime:spotlessApply --console=plain` | 0 | 已格式化；`spotless-apply.log` |
| `./scripts/dev gradle :context:runtime:test :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | **22 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 22 = XML `tests` 22，**差额 0**；`targeted-test.log` |

计数：22 = `ContextConfigLoaderTest` 20 + 既有 `RuntimeSkeletonTest` 2。初版证据在 `.local/validation/cm1/worker/ct06-b1/`；R1 修复证据在 `.local/validation/cm1/worker/ct06-b1-fix/`；R2 修复证据在 `.local/validation/cm1/worker/ct06-b1-fix2/`（`probe-before.log`、`probe-after.log`、`targeted-test.log`、`spotless-apply.log`、`config-fix-probe-after.log`、`classpath.txt`、`final-source-hashes.json`、`verification.json`）。

## 6. 改动文件与最终 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `.../runtime/ContextConfig.kt` | `001579b1612c9d053b9be555f1b563ce5109753d5ac7185254230115fab71d6a` |
| `.../runtime/ContextConfigLoader.kt` | `83baa6845352d6af8dfd66e788ded882f22b03e3e5af95b34995ee93a25d6ff0` |
| `.../runtime/ContextConfigLoaderTest.kt` | `a6b6fd3d5f857a4fdf110c4a5fa07a656f919af9ebe00f69dcf2b249d61027b5` |

`RuntimeSkeletonTest.kt`（`94c78cc5…`）仅随模块格式化，无内容改动。完整清单见 `final-source-hashes.json`。

## 7. 未做项与边界

- **未引入** `AlcyoneContext` / `ContextStatus` / `start` / `close` / 工厂：B2 再实现生命周期、`create` 零副作用、显式初始化与拒启、状态机、并发/竞争、在途 JDBC 等待。
- **未做 CT07**（Ingestion/HTTP）与 CM2；未组装 LLM/Worker/Cron/HTTP；未改 Schema/API/Core/Persistence/CT04 保护层/VFS 源码/进度入口/AGENTS/Architect 文件；未 `git add/commit`。
- 配置阶段不要求 LocalFs 挂载根存在（VFS 在真正 `create/resolve` 时仍会检查），也不创建任何目录/文件；WebDAV endpoint/root 的格式校验仍由 VFS `Resolve` 负责，本批只做非空与类型校验。
- `load(Path, environment)` 为 `internal` 测试注入点，生产只用 `load(Path)`（`System.getenv`）。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 独立初验与 Architect 独立复核。

## 8. R1 修复记录（2026-10-28，悬空符号链接）

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 先红 | `:context:runtime:test --rerun-tasks`（未改 Loader） | 21 例 **3 失败**：悬空文件别名被接受、悬空挂载链接暴露计划路径被接受、悬空链接循环未拒绝 |
| 后绿 | 同上（修复后） | 21 例 0 失败 / 0 错误 / 0 跳过；源码 `@Test` 21 = XML 21 |

修复后的 `canonicalizeForComparison`：

- 逐段处理绝对路径；`Files.isSymbolicLink` 不跟随链接，悬空链接也能识别；
- 命中链接时 `readSymbolicLink` 取目标：相对目标按链接父目录、绝对目标从根重新解析，再继续剩余段；
- 连续链接链维护已访问集合（消费普通段后清空，避免 `/var -> private/var` 因绝对目标重新从根解析而被误判），并设 40 次解析深度上限；
- 重复链接 / 超过深度 / 读取失败 → `ContextConfigException(INVALID_CONFIG)`，消息含字段路径、无 cause、不回显额外原文；**不回退词法路径**；
- 普通不存在的多级父目录照常拼接（不用 `Files.exists` 判断存在性），配置阶段零创建；已有文件仍用 `Files.isSameFile` 兜底。

Architect ConfigProbe 重跑（`.local/validation/cm1/worker/ct06-b1-fix/config-probe-after.log`，javac/java 均退出 0）：

```text
dangling file alias rejected=...ContextConfigException: [INVALID_CONFIG] context.database.path and vfs.database.path must not reference the same file
dangling mount rejected=...ContextConfigException: [INVALID_CONFIG] context.database.path must not be inside the local mount root configured at /memory (vfs.mounts[0].backend.root)
```

修复前 → 修复后 SHA：

| 文件 | 修复前 | 修复后 |
| --- | --- | --- |
| `ContextConfig.kt` | `cf9744bf…` | `001579b1612c9d053b9be555f1b563ce5109753d5ac7185254230115fab71d6a` |
| `ContextConfigLoader.kt` | `3ae47c62…` | `cd95c41835e9e72f859dc0dd56e4ccd9a2f43ac97a588da7a417457c829860e1` |
| `ContextConfigLoaderTest.kt` | `408480ea…` | `2a465ac8d30ee8fb20e8108cf320d9a51e5b0de468bfb76c58aec78802695e87` |

## 9. R2 修复记录（2026-10-28，大小写不敏感真实路径）

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 先红 | `:context:runtime:test --rerun-tasks`（R1 版本、未做真实路径规范化） | 22 例 **1 失败**：macOS 上 `CaseRoot` 挂载 + `./caseroot/context.db` 被接受 |
| 后绿 | 同上（修复后） | 22 例 0 失败 / 0 错误 / 0 跳过；源码 `@Test` 22 = XML 22 |

修复后的顺序与语义：

1. `resolveSymlinkSegments`：`Files.isSymbolicLink`（悬空链接同样生效）逐段解析，相对目标按链接父目录、绝对目标从根重新解析；连续链接链已访问集合 + 40 次深度上限；循环/超深度/读取失败 → `INVALID_CONFIG`，不回退词法。
2. `realPathForComparison`：路径存在 → `toRealPath()`；不存在 → 最近存在祖先 `toRealPath()` + 不存在后缀；任一解析失败（IOException / SecurityException）→ `INVALID_CONFIG`，不回退词法。**不做全路径 lowercase**，大小写敏感文件系统不受影响。
3. 之后才执行同文件 / 挂载根暴露判断；已有文件仍用 `Files.isSameFile` 兜底；普通不存在多级父目录仍合法、零创建。

Architect `ConfigFixProbe` 重跑（`.local/validation/cm1/worker/ct06-b1-fix2/config-fix-probe-after.log`，javac/java 均退出 0）：

```text
dangling file alias rejected=... [INVALID_CONFIG] ... must not reference the same file
dangling mount rejected=... [INVALID_CONFIG] ... must not be inside the local mount root ...
case alias exists=true
case alias realpath=/private/var/.../CaseRoot
existing case alias rejected=... [INVALID_CONFIG] ... must not be inside the local mount root ...
```

修复前 → 修复后 SHA：

| 文件 | 修复前 | 修复后 |
| --- | --- | --- |
| `ContextConfigLoader.kt` | `cd95c418…` | `83baa6845352d6af8dfd66e788ded882f22b03e3e5af95b34995ee93a25d6ff0` |
| `ContextConfigLoaderTest.kt` | `2a465ac8…` | `a6b6fd3d5f857a4fdf110c4a5fa07a656f919af9ebe00f69dcf2b249d61027b5` |

`ContextConfig.kt` 本轮未改（`001579b1…`）。
