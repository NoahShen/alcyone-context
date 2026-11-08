# CT17 B1 · VFS safeReplace Worker 交付

## 本次修改（2026-11-08）

新增本交付报告，记录 VFS safeReplace 的 14 文件白名单、实现契约、测试映射、失败矩阵、命令结果、14 模块计数、81 基线预期差异与残余风险；不修改任务状态为 DONE。

**日期**：2026-11-08
**状态**：**B1 提交 Lead 初验，CT17 未 DONE，B2/CT18 未启动**
**依据**：[B1 委派单](B1_委派单.md)、[Architect 裁定](Architect_裁定.md)、[CT17 任务书](CT17_任务书.md)
**基线**：CM1/CM2/CM3 DONE；14 模块 **1653=1653**；`./scripts/check` 131 tasks；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；工作区原有 CM3 未提交交付保持不动。

## 1. 交付结论

严格修改/新增委派单 §3 的 14 个文件：**9 修改 / 5 新增**。VFS `safeReplace` 已贯通 API、Core、LocalFs、WebDAV 与 Runtime；LocalFs 使用同目录临时文件、完整写入/长度确认和真实 `ATOMIC_MOVE + REPLACE_EXISTING`，WebDAV 明确不支持且零网络副作用。Context/common/Schema/依赖/构建文件零修改，无 git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 修改（9）

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

### 2.2 新增（5）

| 文件 | SHA-256 |
| --- | --- |
| `vfs/core/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/core/DefaultVfsSafeReplaceTest.kt` | `b76ade05be70a5d8f1457f31bb4acdd1050ded731a2bda9f6b21329435ec892f` |
| `vfs/storage/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/storage/opendal/LocalFsSafeReplaceTest.kt` | `e900b4b1b3d9feb4a534df8524acd8e2b4bb944bbb5a759b15c0a82574668ba1` |
| `vfs/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/runtime/RuntimeSafeReplaceTest.kt` | `c6477d7c4ece3edfbee7d8e4d6b2bb4ef43218da6b7ffefc5cea37ef07dd06f2` |
| `integration-tests/vfs/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/integration/vfs/DefaultVfsSafeReplaceRealStackTest.kt` | `363f8e97661e5bdb04253f1e3920ddaa088d0b33b5c908ed3b13abd85b22bf77` |
| `docs/context/tasks/cm4-ct17/B1_Worker交付.md` | 本文件自身不内嵌自引用 SHA；其余 13 个交付文件 SHA 如上。 |

## 3. 冻结契约实现

### 3.1 公共 API

```kotlin
suspend fun safeReplace(
    uri: VfsUri,
    content: ByteArray,
): NodeInfo
```

- `Vfs.safeReplace` 默认抛 `VfsException(UNSUPPORTED_OPERATION, effect=NONE)`，未修改 `WriteMode`。
- `DefaultVfs` 与 `AlcyoneVfs` 覆盖真实实现；Runtime 通过 `operation("safeReplace")` 登记在途。
- API 方法集合、签名和默认拒绝由 `PublicContractTest` 验证，公共签名仍不暴露 OpenDAL/JDBC/SQLDelight/NIO 物理路径。

### 3.2 Storage 能力

- `StorageCapabilities.atomicReplace` 默认 `false`。
- `Storage.safeReplace` 默认明确拒绝；调用方必须先检查能力位。
- LocalFs 在打开时用真实挂载根上的临时文件执行 `ATOMIC_MOVE + REPLACE_EXISTING` 探针，并验证目标内容；只有探针成功才声明 `atomicReplace=true`。
- WebDAV `atomicReplace=false`；直接调用 `safeReplace` 返回 `UNSUPPORTED_OPERATION`，不调用 OpenDAL 网络操作。

### 3.3 Core 编排

- 超限在任何 Storage 调用前拒绝；能力位在目标 `stat`/`safeReplace` 前检查。
- 路由、只读、结构保护、父路径冲突、目标目录和 Node 类型冲突沿用既有预检/写路径契约。
- 预检、目标事实确认、父目录补建、Storage 原子替换、Node/Event 提交位于同一个 `StateBoundary` 锁内。
- 替换前物理不存在 → `FILE_CREATED`；物理已存在 → `FILE_WRITTEN`。
- 已登记 Node 保留 ID、Metadata、`registeredAt`，只推进 `updatedAt`；未登记物理文件替换后登记。
- 不创建临时 Node、不发临时事件。
- 物理替换成功、Node/Event 提交失败 → `STATE_ERROR + PARTIAL`，完整新文件保留，不伪回滚。

### 3.4 LocalFs

- 目标同目录创建唯一临时文件，`Files.createTempFile` 使用不覆盖既有文件的随机名称。
- 写入循环处理完整 `ByteBuffer`，`FileChannel.force(true)` 刷盘，再按 `BasicFileAttributes` 确认为普通文件且长度等于内容长度。
- 提交使用 `Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)`；不支持原子替换时映射 `UNSUPPORTED_OPERATION`。
- 未使用普通覆盖、delete+move、OpenDAL rename 或跨文件系统回退。
- 活动临时文件不进入 `list` 结果；成功、失败、取消均尽力清理。
- 临时文件清理失败不覆盖主异常；`VfsException` 保留 code/message/cause，effect 至少提升到 `PARTIAL`；取消原样传播，清理异常挂 suppressed。
- 关闭后调用返回 `CLOSED`。

## 4. 测试映射

### 4.1 A01～A06 / A08

| 验收 | 正式证据 |
| --- | --- |
| A01 API/能力 | `PublicContractTest` 默认拒绝与签名；`DefaultVfsSafeReplaceTest` 能力 false；`WebDavStorageTest` 能力 false/直接拒绝 |
| A02 LocalFs 物理语义 | `LocalFsSafeReplaceTest` 真实首建、替换、完整字节、旧版保旧、临时清理、关闭后 CLOSED |
| A03 Node/Event | `DefaultVfsSafeReplaceTest` 首建/替换/未注册文件；`DefaultVfsSafeReplaceRealStackTest` 真 SQLite Node/Event/重开 |
| A04 失败注入 | Core 父目录失败、Storage 失败、状态提交失败、取消；LocalFs 写入/确认/原子移动/清理失败；Runtime 宽限期取消 |
| A05 禁止降级 | Core 调用记录断言无 `write/move/delete` 替代；LocalFs 注入无原子能力仍保旧并拒绝；WebDAV 零网络拒绝 |
| A06 Runtime 生命周期 | `RuntimeSafeReplaceTest` 转发、CLOSING/CLOSED、宽限期等待、到点取消；真实栈重开读取 |
| A08 回归/边界 | 五模块定向、真实 WebDAV 43、`./scripts/check`、14 模块计数、81 基线、Schema/common/Context/依赖边界 |

### 4.2 新增用例

| 测试类 | 用例数 |
| --- | ---: |
| `PublicContractTest` 新增 safeReplace 默认/签名 | 1 |
| `DefaultVfsSafeReplaceTest` | 11 |
| `LocalFsSafeReplaceTest` | 11 |
| `WebDavStorageTest` 新增 safeReplace 拒绝 | 1 |
| `RuntimeSafeReplaceTest` | 3 |
| `DefaultVfsSafeReplaceRealStackTest` | 2 |
| **合计新增** | **29** |

## 5. 失败矩阵实际断言

- 超限、路由、只读、结构、目录、父路径冲突、能力 false：原错误码 + `NONE`；能力 false 时 Storage 调用 0。
- 父目录补建中途失败：保留后端错误码 + `PARTIAL`，目标不存在、无 Node/Event。
- Storage 预提交失败：旧文件逐字节不变；Core 替身与真实 LocalFs 均断言。
- LocalFs 临时写入/确认失败：旧目标逐字节不变，临时文件清理成功。
- LocalFs 原子移动失败：旧目标不变，返回原错误 + `UNKNOWN`，无普通 move/覆盖降级。
- LocalFs 清理失败：主错误保留，suppressed 保留清理失败，effect=`PARTIAL`，临时文件可能残留。
- 原子移动前取消：`CancellationException` 原样传播；旧目标不变，活动临时文件不可见且被清理。
- 无原子能力：`UNSUPPORTED_OPERATION + NONE`；旧目标不变，临时文件清理，无降级调用。
- 原子替换成功、状态提交失败：真实 SQLite 触发事件写入失败；物理新版完整保留，`STATE_ERROR + PARTIAL`，Node/Event 事务回滚，重开仍读新版。

## 6. 验收命令与结果

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle :vfs:api:test :vfs:core:test :vfs:storage:test :vfs:runtime:test :integration-tests:vfs:test --rerun-tasks --console=plain` | 0 | 52.78s | `BUILD SUCCESSFUL`，26 tasks |
| `./scripts/dev gradle :vfs:api:spotlessCheck :vfs:core:spotlessCheck :vfs:storage:spotlessCheck :vfs:runtime:spotlessCheck :integration-tests:vfs:spotlessCheck --rerun-tasks --console=plain` | 0 | 6.69s | `BUILD SUCCESSFUL`，21 tasks |
| `./scripts/webdav-check` | 0 | 8.62s | `BUILD SUCCESSFUL`；WebDAV 43=43，fail/error/skip=0/0/0；fixture 清理完成 |
| `./scripts/check` | 0 | 132.81s | `BUILD SUCCESSFUL`，131/131 tasks |

沙箱内首次执行 Gradle / WebDAV 脚本分别遇到本地 socket / fixture 权限限制；经审批在沙箱外执行后均取得上述真实通过结果。未把首次环境拒绝写成测试通过。

## 7. 14 模块源码 `@Test` 与 XML

源码计数范围为各模块 `src/test/**/*.kt` 的行首（允许缩进）`@Test`；XML 为 `build/test-results/test/TEST-*.xml`。

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 26 | 26 | 0 | 0 | 0 |
| context-core | 93 | 93 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 465 | 465 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 20 | 20 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **1682** | **1682** | **0** | **0** | **0** |

B1 前基线 1653，新增 29，最终 **1682=1682**。真实 WebDAV `webdavTest` 另行 43=43，0/0/0。

## 8. 81 基线差异

按 `.local/validation/cm1/architect/vfs-baseline-sha256.json` 复算：**81 项中 75 项一致，6 项预期差异，缺失 0，未批准差异 0**。

预期差异均属于本委派单生产白名单：

- `Vfs.kt`
- `Storage.kt`
- `DefaultVfs.kt`
- `LocalFsStorage.kt`
- `WebDavStorage.kt`
- `AlcyoneVfs.kt`

## 9. 边界证据

- Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- `common/`、Schema、`gradle/`、`settings.gradle.kts`、`gradle.properties`、各 `build.gradle.kts`：CT17 零修改。
- `context/`：工作区原有 CM3 未提交改动保持原状；CT17 未修改 Context API/Core/Runtime/Persistence/Server。
- 依赖：未新增第三方依赖；`vfs/core/src/main` 无 Ktor/serialization import。
- `git diff --check`：exit 0，空输出。
- 无 `git add/commit/push/stash`、分支或回滚操作。

## 10. 未运行项与残余风险

- 真实模型：**not-run**；本任务不涉及模型。
- WebDAV `safeReplace`：按裁定 **unsupported by design**；既有 WebDAV 回归实跑通过。
- 跨 SQLite / 文件系统联合原子性：不提供；物理替换成功而状态失败时保留新版并报 `PARTIAL`。
- 原子能力当前在本次 macOS/JVM 真实探针通过；其他平台会在打开 LocalFs 时重新探针，不支持则能力 false 并拒绝，不降级。
- 临时清理失败可能留下以内部前缀命名的文件；接口如实报 `PARTIAL`，不声称清理成功。
- B2 Context Resource、Map、Bootstrap、Search/Get 未实现；B2/CT18 未启动。

## 11. 最终状态

提交 Lead 初验，CT17 未 DONE，B2/CT18 未启动。真实模型 not-run，无 git 写操作。
