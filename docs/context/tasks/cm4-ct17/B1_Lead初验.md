# CT17 B1 · VFS safeReplace Lead 独立初验

## 本次修改（2026-11-08）

Lead 独立核对 14 文件白名单、生产实现、29 个新增用例、5 模块定向测试、81 基线、Schema/common/Context/依赖边界；未修改生产/测试代码，未派发 Worker，未启动 B2/CT18，无 Git 写操作。

**日期**：2026-11-08
**状态**：**Lead 初验完成；未发现 R 阻断；存在 1 项 P 建议；建议提交 Architect 复核**
**依据**：[B1 委派单](B1_委派单.md)、[B1 Worker 交付](B1_Worker交付.md)、[Architect 裁定](Architect_裁定.md)、[CT17 任务书](CT17_任务书.md)
**基线**：CM1/CM2/CM3 DONE；B1 前 14 模块 **1653=1653**；Schema `ec727a53…8ea3`

## 1. 结论

- **R 阻断：0**
- **P 建议：1**（清理失败后的残留临时文件可见性）
- **N 备注：4**
- Worker 的 14 文件范围、生产契约、失败矩阵、真实 LocalFs 证据与定向测试均通过 Lead 独立检查。
- 建议提交 Architect 复核；**Lead 不自标 B1 DONE**，B2/CT18 不启动。

## 2. 文件白名单与 SHA

独立读取 `git status` 与文件 SHA，实际 B1 变更严格为：

- **9 修改**：6 个 VFS 生产文件、3 个既有测试文件；
- **5 新增**：4 个新测试文件、1 个 Worker 交付报告；
- 合计 **14 文件**，与 [B1 委派单 §3](B1_委派单.md) 一致。

Lead 独立复算 13 个代码/测试文件 SHA，全部与 Worker 交付一致。Worker 报告自身不内嵌自引用 SHA，符合交付说明。

| 类别 | 数量 | 结果 |
| --- | ---: | --- |
| VFS 生产文件 | 6 | 全部在白名单 |
| 既有测试修改 | 3 | 全部在白名单 |
| 新测试文件 | 4 | 全部在白名单 |
| 交付报告 | 1 | 全部在白名单 |
| 白名单外 B1 变更 | 0 | 通过 |

## 3. 生产实现审查

### 3.1 `Vfs.safeReplace` 公共 API

通过：

- 签名严格为 `(VfsUri, ByteArray) -> NodeInfo`。
- 默认实现抛 `UNSUPPORTED_OPERATION`、`effect=NONE`、携带逻辑 URI。
- 未扩展 `WriteMode`。
- `PublicContractTest` 验证方法集合、签名与默认拒绝；公共签名未泄漏 OpenDAL/JDBC/SQLDelight/NIO 物理路径。
- 不承诺 JVM ABI 不变，符合 Architect D2。

### 3.2 Storage 能力与 WebDAV

通过：

- `StorageCapabilities.atomicReplace` 默认 `false`。
- `Storage.safeReplace` 默认拒绝；`DefaultVfs` 在调用 Storage 前先检查能力位。
- LocalFs 通过真实挂载根临时文件探针决定能力；WebDAV 能力位恒 `false`。
- WebDAV Adapter `safeReplace` 在 `call/lifetime` 内直接抛 `UNSUPPORTED_OPERATION`，没有 OpenDAL 网络读写分支。
- 既有 WebDAV write/move 代码未改变。

### 3.3 `DefaultVfs` 单锁编排

通过：

- 内容超限在 Storage 调用前拒绝。
- 路由、只读、结构、目录、父路径与 Node 类型冲突复用既有 `OperationGuard`/写路径规则。
- 目标事实确认、父目录补建、Storage 原子替换、Node/Event 提交位于同一 `StateBoundary.withLock` 调用链内。
- 目标不存在允许首建；父目录可补；目录和已注册非文件 Node 拒绝。
- Storage 成功后才提交 Node/Event；失败不提前发布状态。
- 物理替换成功、状态提交失败转换为 `STATE_ERROR + PARTIAL`，不尝试伪回滚。
- 已注册 Node 通过既有 `commitWrite` 保留 ID、Metadata、`registeredAt`，只推进 `updatedAt`。
- 事件按替换前实际物理存在性产生 `FILE_CREATED` / `FILE_WRITTEN`。
- 未创建临时 Node，未发临时事件。

### 3.4 LocalFs 原子替换

通过：

- 目标同目录创建唯一临时文件，不使用 OpenDAL rename、delete+move 或普通覆盖。
- 临时内容通过 `FileChannel` 完整写入并 `force(true)`，随后以 `BasicFileAttributes` 验证普通文件类型和完整长度。
- 提交使用真实 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`；`AtomicMoveNotSupportedException` 映射 `UNSUPPORTED_OPERATION`，无降级分支。
- LocalFs 打开时在真实挂载根执行原子能力探针，只有探针成功才声明 `atomicReplace=true`。
- 成功、失败、取消均进入临时文件清理路径；清理失败不覆盖主异常，`VfsException` 保留 code/cause/suppressed 并提升 effect 至至少 `PARTIAL`。
- 活动临时文件被 `list` 过滤；关闭后 `safeReplace` 返回 `CLOSED`。

### 3.5 `AlcyoneVfs` 生命周期

通过：

- Runtime 通过 `operation("safeReplace")` 转发，未绕过在途登记。
- OPEN 接受；CLOSING/CLOSED 拒绝。
- close 等待在途操作；宽限期到点请求取消。
- 既有 `InFlightOp` 的取消/等待/清理协议未被改写。

## 4. 测试审查

### 4.1 新增用例计数

| 测试类 | 新增 `@Test` |
| --- | ---: |
| `PublicContractTest` | 1 |
| `DefaultVfsSafeReplaceTest` | 11 |
| `LocalFsSafeReplaceTest` | 11 |
| `WebDavStorageTest` | 1 |
| `RuntimeSafeReplaceTest` | 3 |
| `DefaultVfsSafeReplaceRealStackTest` | 2 |
| **合计** | **29** |

与 Worker 交付一致。

### 4.2 A01～A06/A08 覆盖

| 验收 | Lead 结论 | 关键证据 |
| --- | --- | --- |
| A01 API/能力 | 通过 | 默认 unsupported；方法集合/签名；LocalFs/WebDAV 能力位；能力 false 时 Storage 0 调用 |
| A02 LocalFs 物理语义 | 通过 | 真实首建/替换、完整字节、临时清理、关闭后 CLOSED；真实 `ATOMIC_MOVE` 探针 |
| A03 Node/Event | 通过 | ID/Metadata/registeredAt 保留；`FILE_CREATED/FILE_WRITTEN`；真实 SQLite 重开 |
| A04 失败注入 | 通过 | 临时写/确认/move/清理失败、Core Storage 失败、状态失败、取消边界 |
| A05 禁止降级 | 通过 | Core 调用记录无 write/move/delete 替代；LocalFs 无原子能力明确拒绝；WebDAV 零网络拒绝 |
| A06 Runtime 生命周期 | 通过 | 在途登记、CLOSING/CLOSED 拒绝、宽限期等待、到点取消、真实栈重开 |
| A08 回归/边界 | 通过 | 5 模块定向、XML/源码计数、Schema/common/Context/依赖/构建与 81 基线 |

重点断言均存在：

- 原子提交前旧文件逐字节不变。
- 物理替换成功但 Node/Event 提交失败时，真实文件保留完整新版，返回 `STATE_ERROR + PARTIAL`，Node/Event 事务回滚。
- Core 调用记录证明没有 delete+move、普通 write 或跨 Mount copy 降级。
- Node ID/Metadata/registeredAt 与两类事件有真实 SQLite 与 Core 替身双证据。
- Runtime 关闭竞争与取消在提交前发生，旧文件不变。
- LocalFs 测试包含真实文件探针，不是只 mock `Files.move`。

## 5. 独立执行证据

### 5.1 5 模块定向

命令：

```text
./scripts/dev gradle :vfs:api:test :vfs:core:test :vfs:storage:test :vfs:runtime:test :integration-tests:vfs:test --rerun-tasks --console=plain
```

结果：

- 首次沙箱执行因 Gradle FileLock `SocketException: Operation not permitted` 未启动测试；
- 经沙箱外批准重跑：**exit 0**，`BUILD SUCCESSFUL in 59s`，26 tasks executed；
- 未把首次环境拒绝写成测试通过。

### 5.2 源码/XML 计数

Lead 使用精确 `@Test` 注解匹配（排除 `@TestMethodOrder`）独立统计：

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |

结合 Worker 已生成的其余模块 XML，当前 14 模块合计 **1682=1682**，全部 fail/error/skip 0。

### 5.3 Git 与基线

- `git diff --check`：exit 0，空输出。
- 独立复算 81 基线：**75 项一致、6 项预期差异、缺失 0、未批准差异 0**。
- 6 项差异严格为：`Vfs.kt`、`Storage.kt`、`DefaultVfs.kt`、`LocalFsStorage.kt`、`WebDavStorage.kt`、`AlcyoneVfs.kt`。
- `common/`、`gradle/`、`settings.gradle.kts`、`gradle.properties`、各 `build.gradle.kts`、VFS Schema：无 B1 修改。
- Context Schema SHA 保持 `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`。
- Context 工作区差异为既有 CM3 交付，与 B1 14 文件白名单无交集。
- VFS Core 主源码无 Ktor/serialization import。

## 6. 发现项

### P1（建议，不阻断）：清理失败后的残留临时文件可见性

`LocalFsStorage` 用 `activeTempFiles` 隐藏正在执行的临时文件，但 `safeReplace` 的 `finally` 无论清理是否成功都会把路径移出该集合。若注入清理失败，物理临时文件会残留；后续 `list` 可能重新列出它，若名称已知也可通过 VFS 路径访问。

影响：

- 仅发生在清理失败这一极端路径；
- 不影响目标文件内容、旧版保旧、Node/Event 或返回 effect；
- 当前 Worker 已如实记录“可能残留内部前缀文件”的风险。

建议：

- 保留清理失败路径在隐藏集合直至进程结束，或在 Adapter 启动时清理/保留内部前缀的不可见状态；
- 若要跨重启严格隐藏，需要明确保留内部前缀，并在 list/read/stat 边界统一处理，不能仅依赖随机名称。

该问题是否作为 B1 修复项由 Architect 裁定；Lead 不据此判 R。

### N1：临时计数误报已排除

初次粗统 `vfs-core` source=317 是因为把 `@TestMethodOrder` 误当 `@Test`；改用精确注解匹配后为 **316=316**，无漏测。

### N2：真实 WebDAV 套件未由 Lead 重复执行

Lead 的 5 模块定向已执行 `WebDavStorageTest`，验证能力 false、零网络 `UNSUPPORTED_OPERATION` 与既有能力声明。Worker 已记录 `./scripts/webdav-check` 43=43；Lead 本轮未重复真实 WebDAV fixture，作为证据归属差异记录。

### N3：未复跑全量 `./scripts/check`

5 模块定向、XML/源码计数和边界均通过，未发现需要扩大全量的 R 可疑点；因此本轮沿用 Worker 的 `./scripts/check` exit 0 证据，Lead 未独立重复 132.81s 全量。

### N4：物理文件权限未做契约断言

原子替换以临时文件 inode 替换目标，可能改变 POSIX mode/owner。当前 B1 契约未要求保留物理权限，Map 由同一服务进程消费；本轮不作为缺陷，但若后续外部工具依赖固定权限，应另立验收。

## 7. 是否提交 Architect

**是，建议提交 Architect 复核。**

- 未发现 R 阻断，无需退回 Worker 做强制修复；
- P1 建议随初验一并交 Architect 判断是否接受当前残余风险或要求 B1-R1；
- B1 仍为 **IN_REVIEW / 未 DONE**；
- B2、CT18 不启动；真实模型 not-run；无 Git 写操作。
