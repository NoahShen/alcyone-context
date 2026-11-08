# CT17 B2 · Context Resource 只读访问 Worker 交付

## 本次修改（2026-11-08，B2-R1）

按 Architect P1～P3 裁定执行最小 B2-R1：P1/P2 接受为残余风险且行为不变；P3 将 Resource 映射与关流相关 catch 从 `Throwable` 收窄为 `Exception`。新增公共 Resource API/Flow 的 `AssertionError` 原样传播测试，并更新 SHA、计数、命令和风险证据。

## 本次修改（2026-11-08，B2 初始交付）

新增本交付报告，记录 Context Resource 只读 API、Runtime 适配、生命周期接线、测试映射、三条验收命令、14 模块计数、Schema/B1 SHA、81 VFS 基线与白名单边界。本文件不把 B2 或 CT17 标记为 DONE。

**日期**：2026-11-08
**状态**：**B2-R1 提交 Lead 初验；CT17 未 DONE，B2 未自标 DONE，CT18 未启动**
**依据**：[B2 委派单](B2_委派单.md)、[B1 Architect 复核](B1_Architect复核.md)、[CT17 任务书](CT17_任务书.md)、[Architect 裁定](Architect_裁定.md)
**基线**：B1 APPROVED / DONE；14 模块 **1682=1682**；`./scripts/check` 131 tasks；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；既有 CM3 与 B1 未提交交付保持不动。

## 1. 交付结论

严格修改/新增 B2 委派单 §5 的 11 个文件：**4 修改 / 7 新增**。已实现 `AlcyoneContext.loadMeta/read/readStream`、`ResourceMetadata`、`ContextResourceException` 与 internal `ContextResourceAccess`；未修改 VFS、B1 文件、Schema/SQL、common、依赖、构建、Prompt、Factory、Config/Loader、Persistence、Server。

B2-R1 后新增 28 个测试；最终 14 模块源码 `@Test` = XML = **1710**，fail/error/skip = 0/0/0。定向测试、Spotless 与全量 `./scripts/check` 均通过。未运行真实模型，无 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 修改（4）

| 文件 | SHA-256 |
| --- | --- |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/AlcyoneContext.kt` | `4437ca4800ab06a131f083bb88135ed5360405da55134e00b6c1e6823619364c` |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/ContextExceptions.kt` | `942402ea5bf9bd0343fb630d03f7cf356b07fa586591d00031029d93d78b50c9` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextImpl.kt` | `e95e6ae4cae23828a433fe712fc4a2d1abdf01cf20a8cf609ee4ff33a2c4352d` |
| `context/api/src/test/kotlin/com/github/noahshen/alcyone/context/memory/api/ContextExceptionsTest.kt` | `0ba4922f2caac8a4bc97e2ed77cb14739ade8553fb80ab331e1f59c835983cc5` |

### 2.2 新增（7）

| 文件 | SHA-256 |
| --- | --- |
| `context/api/src/main/kotlin/com/github/noahshen/alcyone/context/memory/api/ResourceMetadata.kt` | `67ceadbbe6f3e7050dd09cf9610136bc857e18698650e85e38532900611668ce` |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/resource/ContextResourceAccess.kt` | `466c5594017d92a7c2cc16ab6a2dae219f2fe7f3207f6fd23d32f20f7f19d33f` |
| `context/api/src/test/kotlin/com/github/noahshen/alcyone/context/memory/api/ResourceApiContractTest.kt` | `bad2b04d5f8954c50917c1850f46668564206b8511c61ea58edb87bf25863d6e` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/resource/ContextResourceAccessTest.kt` | `af77827d52e920bf61fd120d13ae3bb9e4e5909085abbfad07ece66c3a3a053f` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextResourceIntegrationTest.kt` | `5726190beef05f04d947a4dbd4119718f2edf3cac02df798c74ff24a69536d62` |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/ResourceEndToEndTest.kt` | `3eff0b6f3b21d50dd8aefad5dcd9cd253e895e4f1476bb73c1c464f455677a70` |
| `docs/context/tasks/cm4-ct17/B2_Worker交付.md` | 本文件自身不内嵌自引用 SHA |

白名单外 B2 新增变更：**0**。工作区原有 CM3/B1 改动未被回滚或改写。

## 3. 公共契约

### 3.1 API

```kotlin
suspend fun loadMeta(uri: String): ResourceMetadata
suspend fun read(uri: String): ByteArray
fun readStream(uri: String): Flow<ByteArray>

data class ResourceMetadata(
    val uri: String,
    val sizeBytes: Long?,
    val modifiedAt: Instant?,
)
```

接口为三个 Resource 方法提供安全默认拒绝：第三方实现无需立即破坏源码兼容；默认 `loadMeta/read` 抛出 `ContextResourceException(UNAVAILABLE)`，默认 `readStream` 返回冷 Flow，在 collect 时抛出同一安全错误。生产 `AlcyoneContextImpl` 全部覆盖，不依赖默认路径。

### 3.2 异常

```kotlin
enum class ContextResourceErrorCode {
    INVALID_URI,
    OUT_OF_SCOPE,
    NOT_FOUND,
    TYPE_MISMATCH,
    LIMIT_EXCEEDED,
    UNAVAILABLE,
}

class ContextResourceException(
    val errorCode: ContextResourceErrorCode,
    message: String,
) : ContextException("[$errorCode] $message")
```

`ContextResourceException` 不接收或复制 cause；错误消息只包含操作类别和安全逻辑 URI，不包含物理路径、凭据、后端原文或 Resource 正文。

### 3.3 错误映射

| VFS 结果 | Context 结果 |
| --- | --- |
| `VfsUri.parse` 失败 | `INVALID_URI` |
| 非 `/resources/<non-empty>` | `OUT_OF_SCOPE` |
| `NOT_FOUND` | `NOT_FOUND` |
| `TYPE_MISMATCH` | `TYPE_MISMATCH` |
| `LIMIT_EXCEEDED` | `LIMIT_EXCEEDED` |
| `MOUNT_NOT_FOUND` / `CLOSED` / `STORAGE_*` / `STATE_ERROR` / 其他依赖失败 | `UNAVAILABLE` |
| `CancellationException` | 原实例原样传播 |

## 4. 实现说明

### 4.1 URI 范围

- 三个方法统一先调用 `VfsUri.parse`，不使用字符串前缀判断、不自行解码。
- `VfsPath.segments.first()` 必须严格等于 `resources`，且 `segments.size >= 2`。
- `alcyone://`、`alcyone://resources`、`alcyone://resources/`、`memory`、`resources-other` 均 `OUT_OF_SCOPE`。
- query、fragment、反斜杠、空段、双重编码、`.`/`..` 等解析失败均 `INVALID_URI`，且零 VFS I/O。
- 目录只由 VFS 的类型结果拒绝：`loadMeta` 检查 `NodeType.FILE`，`read/openStream` 映射 VFS `TYPE_MISMATCH`。

### 4.2 loadMeta / read

- `loadMeta` 调用 `vfs.stat(uri, StatOptions())`，返回 `NodeInfo.uri.toString()`；`storage` 缺失时两个统计字段保持 `null`。
- 允许 VFS 的 `stat` 懒注册 Node；实际集成断言事件数不变、Resource 内容逐字节不变。
- `read` 固定调用 `vfs.read(uri, ReadOptions())`，复用 VFS 默认 16 MiB 可配置上限，不复制常量，不调用 `readAllBytes()`。
- 缺 `resources` mount 时 Context 仍可 start；Resource 调用映射 `UNAVAILABLE`。

### 4.3 readStream

- 方法调用只创建冷 Flow；首次 collect 才取得 RUNNING access、执行 URI gate 并调用 `vfs.openStream(uri, VfsStreamOptions())`。
- 每次 collect 独立 open/close；固定 64 KiB buffer，零长度文件不 emit。
- 每轮读取及 emit 前检查协程取消；不承诺打断正在阻塞的 native read，只保证取消请求后最终 close。
- 完成、open/read 异常、collector 异常、collector 取消、`take(n)` 提前结束、Context/VFS 关闭都走 `finally` 关流。
- 有普通主异常时，`Exception` close 失败转换为安全 `UNAVAILABLE` 后挂 suppressed；无主异常时普通 close 失败直接映射安全 `UNAVAILABLE`。

### 4.4 Context 生命周期

- RUNNING 发布时在同一状态锁内绑定 `ContextResourceAccess`。
- CREATED/STARTING 的 Resource 调用抛 `ContextLifecycleException(NOT_STARTED)`。
- STOPPING/CLOSED 的 Resource 调用抛 `ContextLifecycleException(CLOSED)`。
- 关闭进入 STOPPING 时立即清空 access；VFS 关闭负责回收已经打开的流，后续 collect 重新检查生命周期并得到 `CLOSED`。

### 4.5 Fatal throwable（B2-R1）

- `loadMeta/read/openStream/stream read/close` 相关 catch 只接收 `Exception`；`CancellationException` 仍先判断并原样传播。
- `Error` 及其他非 `Exception` throwable 不进入安全映射、不包装为 `ContextResourceException`，原实例直接抛出。
- 普通 read 主异常 + 普通 close 失败的优先级保持：主异常保留，安全 close 异常 suppressed。
- P1 阻塞 `readStream` dispatcher 与 P2 access/close 精确竞态仍按 Architect 裁定接受为残余风险，本 R1 未改变行为。

## 5. 测试映射

### 5.1 A07 Resource

| 测试类 | 新增用例 | 覆盖 |
| --- | ---: | --- |
| `ResourceApiContractTest` | 4 | 模型字段、三方法、无基础设施 classpath、第三方安全冷默认 |
| `ContextExceptionsTest` | 1 | Resource 异常继承、错误码、消息、无 cause |
| `ContextResourceAccessTest` | 14 | URI gate、错误映射、Meta/read、冷 Flow、64 KiB、多次 collect、关流/取消/异常优先级、fatal throwable |
| `AlcyoneContextResourceIntegrationTest` | 8 | 真实 SQLite + LocalFs、Meta/read/stream、16 MiB 限额、零/等号/超限、缺 mount、生命周期、已开流回收 |
| `ResourceEndToEndTest` | 1 | 公共 Factory + 真实 SQLite + LocalFs + SDK Resource 调用 |
| **合计** | **28** | A07；A08 回归证据见下文 |

### 5.2 关键实际断言

- 非法/越界 URI：三个方法全部具名失败，`stat/read/openStream` 调用均为 0。
- 合法百分号编码：只解析一次，`stat` 收到规范化 `VfsUri`。
- VFS 七类错误：`loadMeta/read/readStream` 三条路径均按统一表映射；消息与 cause 不含注入的物理路径/凭据。
- `CancellationException`：同一实例原样传播。
- `loadMeta`：文件统计、null 统计、目录拒绝；真实文件无内容写入且事件数不变。
- `read`：原始二进制、默认 `ReadOptions()`、零字节、等于 4-byte 测试限额、超 1 byte `LIMIT_EXCEEDED`、真实 16 MiB+1 超限。
- `readStream`：方法调用 open=0；首次 collect 才 open；64 KiB + 尾块；同一 Flow 两次 collect 独立 open/close；空文件不 emit。
- `take(1)`、collector 抛错、collector 取消均关闭一次；读取异常终止并关闭。
- close 失败无主异常时映射 `UNAVAILABLE`；有主异常时主异常保留、安全 close 异常 suppressed，原始后端文本不泄漏。
- B2-R1 注入同一 `AssertionError` 到 stat/read/open/stream-read/close：公共 `loadMeta/read/readStream` 均收到原实例，未包装为 `ContextResourceException`；普通主异常 + close 失败优先级用例继续通过。
- Context 关闭时已打开 VFS 流计数从 1 回到 0；后续 collect 得到 `CLOSED`。
- 缺 `resources` mount：start 为 RUNNING，`loadMeta` 才返回 `UNAVAILABLE`。

## 6. 验收命令

沙箱内 Gradle 首次因 FileLock socket `Operation not permitted` 无法启动；以下成功结果均在审批后的沙箱外真实执行，不把环境拒绝写成通过。

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle :context:api:test :context:runtime:test :integration-tests:context:test --rerun-tasks --console=plain` | 0 | 45.79s | `BUILD SUCCESSFUL in 45s`；32 actionable tasks executed |
| `./scripts/dev gradle :context:api:spotlessCheck :context:runtime:spotlessCheck :integration-tests:context:spotlessCheck --rerun-tasks --console=plain` | 0 | 5.33s | `BUILD SUCCESSFUL in 4s`；13 actionable tasks executed |
| `./scripts/check` | 0 | 138.21s | `BUILD SUCCESSFUL in 2m 17s`；131/131 tasks executed |

## 7. 14 模块源码 `@Test` 与 XML

源码范围为各模块 `src/test/**/*.kt` 行首（允许缩进）`@Test`；XML 为最终 `build/test-results/test/TEST-*.xml`。

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 31 | 31 | 0 | 0 | 0 |
| context-core | 93 | 93 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 487 | 487 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 21 | 21 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **1710** | **1710** | **0** | **0** | **0** |

B1 基线 1682，B2 初始新增 27，B2-R1 新增 1，最终新增 **28**、总计 **1710=1710**。B2 初查曾修正一个 `runBlocking` 测试表达式返回异常对象导致的源码/XML 差 1；R1 后最终计数再次按源码与 XML 独立复算一致。

## 8. Schema、B1 与 VFS 基线

- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- B1 的 13 个代码/测试文件 SHA 与 B1 Worker 交付逐项一致：**13/13 一致**。
- 81 VFS 基线：**75 一致、6 个 B1 已批准差异、缺失 0、未批准差异 0**。
- 6 个差异仅为 B1 批准的 `Vfs.kt`、`Storage.kt`、`DefaultVfs.kt`、`LocalFsStorage.kt`、`WebDavStorage.kt`、`AlcyoneVfs.kt`；B2 未新增 VFS diff。
- `common/`、`gradle/`、`settings.gradle.kts`、`gradle.properties`、各 `build.gradle.kts`：B2 零修改。
- Schema/SQL/Prompt：B2 零修改；工作区原有 CM3 SQL/Prompt 未提交内容保持原状。
- `ContextConfig`/Loader/Factory/Persistence/Server、Map/Bootstrap/Search/Get、Resource 写入/删除/移动：B2 零修改。
- Context Resource 生产路径静态检查无 `Files.move`、`FileOutputStream`、OpenDAL、`readAllBytes()` 或 `startsWith("alcyone://resources")`。
- `git diff --check`：exit 0，空输出。

## 9. 未运行项与残余风险

- 真实模型：**not-run**；本任务不涉及 LLM。
- P1 接受风险：`readStream` 未使用 IO dispatcher；不承诺打断正在阻塞的 native read，只保证取消请求发出、最终关流且不泄漏。
- P2 接受风险：access 已取得后 Context close 的精确竞态仍可能把 VFS `CLOSED` 映射为 `UNAVAILABLE`；按冻结映射保留，不做跨 Context/VFS 锁协调。
- P3 已修复：普通映射与关流 catch 仅处理 `Exception`；`Error` 等 fatal throwable 原样传播，不包装、不吞掉。
- `loadMeta` 可能触发 VFS Node 懒注册和 VFS 状态库写入；按裁定允许，已验证不发文件变更事件、不写 Resource 内容。
- 第三方 `AlcyoneContext` 实现若未覆盖 Resource 方法，使用 API 安全默认拒绝 `UNAVAILABLE`；生产 Runtime 不使用该默认路径。
- 未实现 Map、Bootstrap、Search/Get、Resource 写入/删除/移动或 HTTP 路由。

## 10. 最终状态

提交 Lead 初验（B2-R1）；**CT17 未 DONE，B2 未自标 DONE，CT18 未启动**。真实模型 not-run，无 git add/commit/push/stash、分支或回滚操作。
