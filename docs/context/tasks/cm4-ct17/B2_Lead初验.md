# CT17 B2 · Context Resource 访问 Lead 独立初验

## 本次修改（2026-11-08）

Lead 独立核对 11 文件白名单、API/Runtime 实现、27 个新增用例、3 模块定向测试、B1 SHA、81 VFS 基线及 Schema/common/依赖/构建边界；未修改生产/测试代码，未派发 Worker，未启动 CT18，无 Git 写操作。

**日期**：2026-11-08
**状态**：**Lead 初验完成；未发现 R 阻断；存在 3 项 P 建议；建议提交 Architect 复核**
**依据**：[B2 委派单](B2_委派单.md)、[B2 Worker 交付](B2_Worker交付.md)、[B1 Architect 复核](B1_Architect复核.md)、[CT17 任务书](CT17_任务书.md)
**基线**：B1 APPROVED / DONE；B1 后 14 模块 **1682=1682**；Schema `ec727a53…8ea3`

## 1. 结论

- **R 阻断：0**
- **P 建议：3**
- **N 备注：4**
- 11 文件范围、公共契约、URI gate、错误映射、Meta/read/Flow、真实 LocalFs 和生命周期主路径均通过。
- 建议提交 Architect 复核；**Lead 不自标 B2/CT17 DONE**，CT18 不启动。

## 2. 文件白名单与 SHA

实际 B2 变更严格为：

- **4 修改**：3 个 API/Runtime 生产文件、1 个既有 API 测试；
- **7 新增**：2 个 API/Runtime 生产文件、4 个测试、1 个 Worker 交付报告；
- 合计 **11 文件**，与 [B2 委派单 §5](B2_委派单.md) 一致。

Lead 独立复算 10 个代码/测试文件 SHA，全部与 Worker 交付一致；Worker 报告自身不内嵌自引用 SHA。白名单外 B2 变更 0。

| 类别 | 数量 | 结果 |
| --- | ---: | --- |
| API 生产 | 3 | AlcyoneContext/ContextExceptions/ResourceMetadata |
| Runtime 生产 | 2 | AlcyoneContextImpl/ContextResourceAccess |
| API 测试 | 2 | 1 修改 / 1 新增 |
| Runtime 测试 | 2 | 新增 |
| Integration 测试 | 1 | 新增 |
| 交付报告 | 1 | 新增 |
| 白名单外 B2 变更 | 0 | 通过 |

## 3. 生产实现审查

### 3.1 公共 API

通过：

- `AlcyoneContext` 新增 `loadMeta/read/readStream`，返回类型与委派单一致。
- 第三方实现获得安全冷默认：`loadMeta/read` 抛 `UNAVAILABLE`；`readStream` 在 collect 时抛同一安全错误。
- `ResourceMetadata` 仅含 `uri/sizeBytes/modifiedAt`，统计字段可空。
- `ContextResourceException` 继承 `ContextException`，只含安全 error code/message，不复制 VFS cause。
- `context/api` 仍只使用既有 coroutines 依赖，无基础设施类型泄漏。

### 3.2 URI gate

通过：

- 三个入口统一 `VfsUri.parse`，无字符串前缀判断。
- 首段严格 `resources`，且至少一个资源路径段。
- root、resources root、memory、其他 namespace 均 `OUT_OF_SCOPE`。
- query/fragment/反斜杠/空段/双重编码/`.`/`..` 等解析失败均 `INVALID_URI`。
- 非法/越界 URI 在 VFS stat/read/openStream 之前失败。
- 目录由 VFS 类型结果拒绝：Meta 检查 FILE；read/stream 映射 TYPE_MISMATCH。

### 3.3 loadMeta / read

通过：

- `loadMeta` 使用 `StatOptions()`，返回规范化 `NodeInfo.uri` 与可空 storage 统计。
- 允许 VFS 懒注册，但不写 Resource 内容、不发文件变更事件。
- `read` 使用 `ReadOptions()`，复用 VFS 可配置限额，不复制 16 MiB 常量。
- VFS NOT_FOUND/TYPE_MISMATCH/LIMIT_EXCEEDED 映射具名错误；MOUNT_NOT_FOUND/STORAGE/STATE/CLOSED 映射 UNAVAILABLE。
- 缺 resources mount 时 start 成功，调用时失败。

### 3.4 readStream

功能通过：

- 冷 Flow：构造不 open，首次 collect 才执行 URI gate/open。
- 每次 collect 独立 open/close；64 KiB 分块；零文件不 emit。
- 正常完成、open/read 异常、collector 异常、collector 取消、`take(n)` 均进入 finally。
- close 失败无主异常时映射 UNAVAILABLE；有主异常时安全 close 错误 suppressed。
- 不把后端文本、物理路径或正文写入公共异常。

### 3.5 生命周期与关闭竞态

主路径通过：

- RUNNING 发布时在同一状态锁绑定 `ContextResourceAccess`。
- CREATED/STARTING 返回 `NOT_STARTED`。
- STOPPING/CLOSED 返回 `CLOSED`。
- close 进入 STOPPING 时清空 access；已打开 VFS 流由 VFS close 回收。
- `readStream` 每次 collect 重新取得当前 RUNNING access。

## 4. 测试审查

### 4.1 新增用例

| 测试类 | 新增 `@Test` |
| --- | ---: |
| `ResourceApiContractTest` | 4 |
| `ContextExceptionsTest` | 1 |
| `ContextResourceAccessTest` | 13 |
| `AlcyoneContextResourceIntegrationTest` | 8 |
| `ResourceEndToEndTest` | 1 |
| **合计** | **27** |

与 Worker 交付一致。

### 4.2 A07/A08 覆盖

| 验收 | Lead 结论 | 关键证据 |
| --- | --- | --- |
| URI 范围 | 通过 | 非法/越界三入口零 VFS I/O；百分号编码规范化 |
| 错误映射 | 通过 | NOT_FOUND/TYPE_MISMATCH/LIMIT_EXCEEDED/MOUNT_NOT_FOUND/CLOSED/STORAGE/STATE |
| loadMeta | 通过 | 文件统计、null 统计、目录拒绝、懒注册无文件事件/内容写 |
| read | 通过 | 原始字节、默认 ReadOptions、零/等号/超限、真实 16 MiB+1 |
| Flow | 通过 | 冷启动、64 KiB、多 collect、零文件、take、异常、取消、close 失败优先级 |
| 生命周期 | 通过 | CREATED/STARTING/STOPPING/CLOSED；已开流关闭回收 |
| 缺 mount | 通过 | start RUNNING；调用时 UNAVAILABLE |
| 公共 E2E | 通过 | 真实 Factory/Runtime/SQLite/LocalFs Resource 闭环 |
| 敏感信息 | 通过 | cause 为空；消息不含注入的物理路径/凭据/正文 |
| A08 边界 | 通过 | 3 模块定向、1709=1709、B1 SHA、81 基线、Schema/common/依赖/构建 |

## 5. 独立执行证据

### 5.1 3 模块定向

命令：

```text
./scripts/dev gradle :context:api:test :context:runtime:test :integration-tests:context:test --rerun-tasks --console=plain
```

结果：

- 首次沙箱执行因 Gradle FileLock `SocketException: Operation not permitted` 未启动测试；
- 经沙箱外批准重跑：**exit 0**，`BUILD SUCCESSFUL in 46s`，32 tasks executed；
- 未把首次环境拒绝写成测试通过。

### 5.2 源码/XML 计数

Lead 使用精确 `@Test` 注解匹配独立统计：

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| context-api | 31 | 31 | 0 | 0 | 0 |
| context-runtime | 486 | 486 | 0 | 0 | 0 |
| integration-context | 21 | 21 | 0 | 0 | 0 |

结合 Worker 已生成的其他模块 XML，当前 14 模块合计 **1709=1709**，全部 fail/error/skip 0。

### 5.3 B1、VFS 与边界

- B1 Worker 交付的 13 个代码/测试 SHA：**13/13 一致**。
- 81 VFS 基线：**75 一致、6 个 B1 已批准差异、缺失 0、未批准差异 0**。
- B2 未新增 VFS diff；6 项差异仍严格为 B1 批准的 6 个生产文件。
- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- `common/`、`gradle/`、`settings.gradle.kts`、`gradle.properties`、各 `build.gradle.kts`：无 B2 修改。
- Schema/SQL/Prompt、Factory/Config/Loader/Persistence/Server：无 B2 白名单变更。
- Context 工作区既有 CM3/B1 交付保持原状，与 B2 11 文件白名单无冲突。
- 生产 Resource 路径静态检查无 Files/FileOutputStream/OpenDAL/readAllBytes/字符串前缀判断。
- `git diff --check`：exit 0，空输出。

## 6. 发现项

### P1（建议，不阻断）：阻塞流读取未切换到 IO dispatcher

`ContextResourceAccess.readStream` 在 Flow 当前 collector 上下文直接调用阻塞 `InputStream.read(buffer)`，没有 `withContext(Dispatchers.IO)` 或 `flowOn(Dispatchers.IO)`。若 collector 使用单线程/UI dispatcher，Resource 读取会阻塞该 dispatcher；取消也只能在阻塞 read 返回后完成。

当前委派单未明确要求 dispatcher，且功能、关流和取消后最终清理测试均通过，因此 Lead 不判 R。建议 Architect 决定：

- B2 接受并明确文档化“collector 应自行切 IO”；或
- 在 `ContextResourceAccess` 内将单次 read 包到 `Dispatchers.IO`，并补一条线程/阻塞证据。

### P2（建议，不阻断）：close 竞态可能把 VFS CLOSED 映射为 UNAVAILABLE

`ContextResourceAccess` 将 VFS `CLOSED` 统一映射 `ContextResourceException(UNAVAILABLE)`。若在 `currentResourceAccess` 已返回 access 后、VFS 调用前 Context 进入 STOPPING/CLOSED，调用可能得到 UNAVAILABLE，而不是 `ContextLifecycleException(CLOSED)`。

当前测试覆盖“调用前已 CREATED/STARTING/STOPPING/CLOSED”，未覆盖“已取得 access 后关闭”的精确竞态；已打开流的 close 回收通过。建议 Architect 判断是否需要 B2-R1，或接受该竞态并补文档。

### P3（建议，不阻断）：`Throwable` 映射范围过宽

`loadMeta/read/readStream` 对 VFS 调用和流读取使用 `catch (Throwable)`；除 CancellationException 外，`Error`（OOM、ThreadDeath 等）也会被包装成安全 `UNAVAILABLE`。现有 Runtime 操作路径通常只包装 `Exception`。

建议收窄为 `Exception`/已知 VFS 异常；`CancellationException` 原样传播，JVM `Error` 原样抛出。当前没有触发该路径的测试。

### N1：第三方默认方法行为已覆盖

`ResourceApiContractTest` 验证未覆盖 Resource 方法的第三方 `AlcyoneContext` 得到冷、安全的 UNAVAILABLE，生产 Runtime 全部覆盖默认路径。

### N2：Worker 修复过一次源码/XML 差异

Worker 报告初查发现 `runBlocking` 返回异常对象导致 486/485；改为 Unit 后 Lead 独立复核为 486=486，未发现漏测。

### N3：未独立复跑全量 `./scripts/check`

3 模块定向、XML/源码计数、B1 SHA、81 基线与边界均通过；未发现需要扩大全量回归的 R 可疑点，因此沿用 Worker 的 `./scripts/check` exit 0 证据。

### N4：loadMeta 懒注册副作用已按裁定接受

真实集成确认 `loadMeta` 不发文件变更事件、不写 Resource 内容；VFS 状态库懒注册属于 Architect 已批准的内部副作用。

## 7. 是否提交 Architect

**是，建议提交 Architect 复核。**

- 未发现 R 阻断，无需立即退回 Worker；
- P1～P3 为非阻断建议，交 Architect 判断是否接受或要求 B2-R1；
- B2 仍为 **IN_REVIEW / 未 DONE**；
- CT17 仍为 **IN_PROGRESS**；
- CT18 不启动；真实模型 not-run；无 Git 写操作。
