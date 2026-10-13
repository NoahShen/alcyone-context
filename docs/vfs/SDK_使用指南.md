# Alcyone VFS SDK 使用指南

> 本次修改（2026-10-13，T30 复核第 6 节修复收尾）：
> - §2.2 配置解析冲突错误码更正为 INVALID_ARGUMENT（与重开 Mount 身份 CONFLICT 区分）；
> - §3.1 移动语义更正：目录移动发射单条根级 DIRECTORY_MOVED，文件移动发射 FILE_MOVED；删除“原生原子 rename”表述，说明能力位仅表示后端支持原生操作，不承诺 Storage 与 SQLite 联合原子性，亦不对任意 WebDAV 服务提供原子承诺；同 Mount 无原生能力也走复制回退（已交付 Adapter 目录原生能力均为 false）；复制路径单文件受读写限额约束；更正“确认存在”为按既有实现核对可用长度/目录集合后删源；
> - §6、§7 补充说明：代码示例均位于 suspend 函数内、假定 vfs 已创建且 targetUri 尚不存在；补齐分发器不捕获 Error 说明；关闭时序明确到期先发协作取消再等清理完成。

本文档面向需要在源码工程中集成使用 Alcyone VFS 的开发者。当前版本仅支持在源码工程内直接调用，底层隐藏了 Local FS 与 WebDAV 的具体存储差异。

---

## 1. 架构定位与 Context System 分工

- **SDK 纯粹性**：VFS 仅负责文件与目录的统一寻址、I/O 读写、状态登记与事件发射。
- **无身份 / 无 ACL**：VFS 内部**不绑定 Agent 身份**，也**不做访问控制列表（ACL）**。
- **上层约定分工**：Context System 决定将哪些逻辑目录分配给哪个 Agent（例如规定 Agent A 仅访问 `alcyone://resources/agent-a/...`）。对于持有 `AlcyoneVfs` 实例的调用方，VFS 不会主动阻止其访问实例内的其它合法路径。安全性与租户隔离由 Context System 上层统筹控制。

---

## 2. 初始化与 Mount 配置

宿主调用者只需构造 `VfsRuntimeConfig`，并通过挂起函数 `AlcyoneVfs.create(config)` 创建 Runtime 实例。宿主无需手工实例化底层 Operator、Driver、Repository 或 Router。

### 2.1 配置结构与参数说明

```kotlin
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.VfsLimits
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountBackend
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

// 1. 挂载本地目录
val localMount = MountConfig(
    vfsPath = VfsPath.parse("/resources/docs"), // 逻辑挂载路径，必须属于已声明的命名空间，不能为 "/"
    storageKey = "docs-local",                  // 存储标识符：同 key 允许指向同一规范后端并自动归并；拒绝同 key 指向不同后端
    backend = MountBackend.LocalFs(Path.of("/var/data/docs")) // 本地物理根目录（必须已存在，会自动规范化真实绝对路径）
)

// 2. 挂载 WebDAV 远端存储
val webdavMount = MountConfig(
    vfsPath = VfsPath.parse("/resources/remote"),
    storageKey = "remote-dav",
    backend = MountBackend.WebDav(
        endpoint = "http://127.0.0.1:8080", // 仅支持 http:// 或 https://，禁止将账号密码拼入 URL（如 user:pass@host）
        root = "/dav/share",                 // 服务端共享根目录
        username = "davuser",                // 凭据仅在受管配置内传递，不进状态库、不进身份串、toString/异常受管脱敏
        password = "davpassword"
    )
)

// 3. 构造运行时配置
val config = VfsRuntimeConfig(
    stateDatabase = Path.of("/var/data/state.db"), // SQLite 状态库路径（本地物理根目录不能包含状态库文件）
    namespaces = setOf("resources", "memory"),     // 声明顶级命名空间
    mounts = listOf(localMount, webdavMount),      // 挂载点列表（不同后端物理根不能重叠嵌套）
    limits = VfsLimits(
        defaultReadMaxBytes = 16L * 1024 * 1024,   // read(ByteArray) 默认读取上限（16 MiB）
        defaultWriteMaxBytes = 16L * 1024 * 1024   // write(ByteArray) 默认写入上限（16 MiB）
    ),
    streamTotalLimit = null,                       // openStream 默认无总量上限（可设字节数），流仍按块读取不把整文件载入内存
    eventBufferCapacity = 256,                     // 进程内事件通知有界队列容量
    closeGracePeriod = 30.seconds                  // 关闭时等待在途操作清理的宽限期
)

// 4. 创建 VFS 实例（挂起函数）
// suspend fun initVfs(): AlcyoneVfs = AlcyoneVfs.create(config)
```

### 2.2 路径映射、约束与凭据保护边界
- **逻辑路径到挂载相对路径**：例如逻辑 URI `alcyone://resources/docs/reports/2026.pdf`，挂载点为 `/resources/docs` 对应本地根 `/var/data/docs`，则实际对应物理文件 `/var/data/docs/reports/2026.pdf`。
- **storageKey 归并与冲突**：配置中多个 MountConfig 允许使用相同的 `storageKey`，前提是它们指向完全相同的规范后端（归并为一个底层存储实例）；若同一 `storageKey` 指向了不同的本地物理目录或不同的 WebDAV endpoint/root，配置解析直接抛出 `INVALID_ARGUMENT`（`VfsRuntimeConfig.resolve` 校验 `invalidArgument`）拒绝启动。这与重新打开已有状态库时的 Mount 身份冲突（`CONFLICT`）属于不同阶段的不同错误码。
- **状态库位置约束**：本地挂载的物理根目录下**不能包含状态库文件**（避免扫描到底层自身状态文件）。两个目录可以相邻或位于同一父目录下。
- **重开身份比对**：重新打开已有状态库时，已记录的挂载身份（key、后端类型、规范物理根）必须与当前配置完全匹配；远端规范目标（endpoint + root）重叠或 key 漂移将拒绝启动（抛出 `CONFLICT`）。
- **受管凭据保护**：WebDAV 密码在 `MountBackend.WebDav.toString()` 中脱敏为 `***`，身份标识串和状态库持久表中不含密码，认证失败等异常在管道日志中脱敏；系统**没有全局日志脱敏器**，EventDispatcher 会直接记录 Consumer 抛出的异常，宿主与 Consumer 自行负责自定义异常中的文本安全。

---

## 3. 核心文件操作与写入模式

所有读写方法均通过挂起函数执行，路径统一使用 `VfsUri.parse("alcyone://<namespace>/<path>")`。

### 3.1 核心操作 API

| 操作 | 对应 API | 说明 |
| :--- | :--- | :--- |
| **全量读取** | `read(uri, options)` | 返回 `ByteArray`。只读操作，不登记 Node，不发射事件。`ReadOptions(maxBytes)` 只能收紧上限，超过限额抛 `LIMIT_EXCEEDED`。 |
| **状态查询** | `stat(uri, options)` | 查询属性。若底层物理存在但尚未在状态库登记，会触发**懒注册**（写入状态库并分配新 Node ID）。`includeStorage=false` 对已登记节点直接走逻辑库，未登记节点仍探查物理确认存在。 |
| **节点逻辑查询**| `getNode(id)` | 纯逻辑查询，通过 Node ID 查属性。ID 不存在返回 `NOT_FOUND`，绝不扫描底层。 |
| **目录列举** | `list(uri)` | 单层直接子项列举，不递归，不保证排序。合并物理直接子项与已配置的目录入口（同名配置入口优先）；不凭历史 Node 行列出物理已消失项，不批量登记未登记项（未登记项 `nodeId` 为 `null`）。 |
| **文件/目录移动**| `move(source, target)`| 移动文件或目录。**保持原 Node ID 与 Metadata 连续性**。同 Mount 按 Adapter 原生能力选择：若后端支持原生操作则调用原生移动，**无原生能力也走复制回退**（当前已交付 Adapter 的目录原生能力均为 `false`）。能力位仅表示后端支持原生操作，不承诺 Storage 与 SQLite 联合原子性，也不对任意 WebDAV 服务提供原子承诺。跨 Mount 或回退路径执行“目标端写入/复制 -> 确认存在（按实现核对可用长度/目录集合）-> 源端删除”编排。复制路径中单个文件受读/写限额约束。文件移动发射 `FILE_MOVED`；目录移动发射单条根级 `DIRECTORY_MOVED`。目标已存在直接拒绝。 |
| **文件/目录删除**| `delete(uri, options)`| 默认非递归，目录非空返回 `DIRECTORY_NOT_EMPTY`；设置 `DeleteOptions(recursive = true)` 级联删除物理文件及相关全部已登记 Node。删除后原 Node ID 废弃；在原路径新建将分配全新 Node ID。 |

### 3.2 三种写入模式（`WriteOptions`）

> **环境说明**：以下操作代码块均位于 `suspend` 函数体内，假定 `vfs` 实例已通过 `AlcyoneVfs.create(config)` 成功创建。

写入操作使用 `write(uri, bytes, options)`，支持三种模式：

```kotlin
import com.github.noahshen.alcyone.context.vfs.WriteMode
import com.github.noahshen.alcyone.context.vfs.WriteOptions

// 1. CREATE_NEW：仅当目标文件不存在时成功；目标已存在时抛 ALREADY_EXISTS（不触碰底层物理文件）
vfs.write(uri, content, WriteOptions(WriteMode.CREATE_NEW))

// 2. REPLACE_EXISTING：仅当目标文件已存在时覆盖；目标不存在时抛 NOT_FOUND
//    覆盖成功后：保持原有 Node ID、保留已有 Metadata，触发 FILE_WRITTEN 事件
vfs.write(uri, content, WriteOptions(WriteMode.REPLACE_EXISTING))

// 3. UPSERT（默认模式）：文件不存在则创建（发 FILE_CREATED），文件已存在则覆盖并保持 Node ID（发 FILE_WRITTEN）
vfs.write(uri, content, WriteOptions(WriteMode.UPSERT))
```

> **重要细节**：
> - 自动补全父目录：写文件时若物理父级目录不存在，VFS 会自动在底层补齐物理目录，但**不会为父级目录登记 Node，也不会触发目录创建事件**。
> - 首次新建与覆盖事件：首次创建产生 `FILE_CREATED`，覆盖写入产生 `FILE_WRITTEN`。

---

## 4. Node 身份与 Metadata 机制

> **环境说明**：以下代码块位于 `suspend` 函数体内，假定 `vfs` 已创建。

- **Node ID**：UUIDv7 格式的全局唯一标识。与 URI 相对解耦——移动文件或重命名时，URI 改变，但 Node ID 保持不变；同路径删除后重建，会生成全新的 Node ID。
- **Metadata 整体替换**：
  ```kotlin
  import com.github.noahshen.alcyone.context.vfs.NodeMetadata

  // 设置或更新元数据（完全覆盖替换，不提供隐式合并）
  vfs.setMetadata(nodeId, NodeMetadata(tags = setOf("report", "q4"), description = "财务报表"))

  // 读取元数据
  val meta = vfs.getMetadata(nodeId)
  println("tags=${meta.tags}, desc=${meta.description}")

  // 清空元数据：传入默认空实例 NodeMetadata()（系统无 NodeMetadata.EMPTY 静态常量）
  vfs.setMetadata(nodeId, NodeMetadata())
  ```
- **更新语义**：`setMetadata` 为整体替换语义，每次成功 set 都会刷新 `updatedAt` 并发射一次 `METADATA_UPDATED` 事件（即使内容相同也是一次显式更新）。

---

## 5. 大文件分块流式读取（`openStream`）

> **环境说明**：以下代码块位于 `suspend` 函数体内，假定 `vfs` 已创建。

对于大于内存缓冲限额（默认 16 MiB）的文件，必须使用 `openStream` 进行流式读取。流式读取不把完整文件载入内存，且不登记 Node、不发射事件。

- **限额机制**：`VfsRuntimeConfig.streamTotalLimit` 默认为 `null`（流默认无总量上限，分块流式读完为止）。调用方传入 `VfsStreamOptions(maxTotalBytes)` 时只能进一步收紧限额，有效上限为 `min(streamTotalLimit, maxTotalBytes)`。
- **资源释放**：调用方**必须显式关闭返回的 `VfsStreamResult`**，推荐使用 Kotlin 的 `.use { ... }` 语法块以确保底层 Storage Reader 可靠释放：

```kotlin
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
import com.github.noahshen.alcyone.context.vfs.VfsUri
import java.security.MessageDigest

val uri = VfsUri.parse("alcyone://resources/docs/large_model.bin")

// 分块读取示例（8 KiB 缓冲区）
val digest = MessageDigest.getInstance("SHA-256")
var totalBytes = 0L

vfs.openStream(uri, VfsStreamOptions(maxTotalBytes = 100L * 1024 * 1024)).use { result ->
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = result.stream.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        totalBytes += read
    }
}
println("Streamed total bytes: $totalBytes")
```

---

## 6. 事件通知与 Consumer 异常隔离边界

VFS 支持进程内异步事件订阅。`subscribe` 函数返回 `Unit`（无取消句柄），所有事件通知在底层事务提交后投递。

### 6.1 等待事件的标准范式

> **环境说明**：以下代码块位于 `suspend` 函数体内，假定 `vfs` 实例已创建，且前提为 `targetUri` 在底层与状态库中**尚不存在**（或传入 `WriteMode.CREATE_NEW` 确保首次新建）。

在测试或协调场景中等待事件时，必须**在操作之前建立订阅**，明确区分**首次新建**（`FILE_CREATED`）与**覆盖写**（`FILE_WRITTEN`），匹配目标 URI 与类型，并设置超时边界（因为缓冲区满时可能丢弃事件）：

```kotlin
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

val targetUri = VfsUri.parse("alcyone://resources/docs/new_doc.txt")
val eventDeferred = CompletableDeferred<VfsEvent>()

// 1. 订阅先于写入建立（subscribe 返回 Unit）
vfs.subscribe { event ->
    // 首次新建匹配 FILE_CREATED；若是覆盖已有文件则匹配 FILE_WRITTEN
    if (event.type == VfsEventType.FILE_CREATED && event.uri == targetUri) {
        eventDeferred.complete(event)
    }
}

// 2. 执行写入操作（首次新建场景）
vfs.write(targetUri, "content".toByteArray())

// 3. 有界等待通知送达（超时防死锁；慢 Consumer 或突发事件可能引发队列丢弃）
val event = withTimeout(10_000) { eventDeferred.await() }
println("Received event: ${event.type} for ${event.uri}")
```

### 6.2 Consumer 异常分层与隔离边界
1. **同事务后通知**：事件在 SQLite 事务持久化成功后投递；若事务回滚，绝不发射事件。
2. **普通异常隔离**：Consumer 内部抛出的普通 `Exception` 会被内部分发循环捕获并记录日志，**不影响后续 Consumer，也不影响发起写入的调用方**。慢 Consumer 会导致分发 worker 延迟后续通知处理。分发器**不捕获 `Error`**（如 `VirtualMachineError`、`AssertionError` 等），严重系统错误将直接向上中断。
3. **取消分层机制**：
   - 当 Consumer 抛出 `CancellationException` 时，底层的 `EventDispatcher` 会向外重抛；
   - 处于常驻运行状态的 `AsyncEventNotifier` worker 会记录该取消并继续消费处理后续队列事件（本次事件中排在后面的 Consumer 可能不再被调用）；
   - 仅当分发器自身在 VFS 关闭时被显式取消，worker 协程才会正式退出（不能推导 Error 或未受检致命错误也被隔离）。
4. **有界队列与背压**：事件分发器内置固定容量（默认 256）的有界缓冲区。当突发事件撑满队列时，新产生的通知可能被丢弃（记录 WARN 日志）。丢通知不代表删除已持久化的事件记录。系统首版**不承诺可靠重试、消息 ACK、持久化重放或死信队列**。

---

## 7. 实例生命周期与关闭超时边界

`AlcyoneVfs` 实例持有文件独占锁、单连接 SQLite 驱动以及后台事件分发协程。使用完毕后必须在 `finally` 块中调用挂起函数 `close()` 释放：

```kotlin
val vfs = AlcyoneVfs.create(config)
try {
    // 业务操作
} finally {
    vfs.close() // 优雅关闭挂起函数
}
```

### 关闭时序与边界说明
1. **停止接纳**：调用 `close()` 后，实例状态立即置为 closing，后续任何新操作均直接拒绝并抛出 `VfsErrorCode.CLOSED`。
2. **在途等待（inFlight.done）与超时取消**：
   - 宽限期 `closeGracePeriod` 等待的是**在途发起的操作任务（inFlight.done）**，**不等待**已经交出给调用方的遗留流自行读完；
   - 若当前没有任何在途操作，`close()` 会立即继续往下执行，**不会无故空等 30 秒**；
   - 若宽限期到期仍有在途操作未完，Runtime 会**先向未完成的在途任务发送协作取消信号**，**然后再等待清理收尾完成**（并非发出取消即刻释放资源）；
   - 在途操作全部完成（或超时协作取消清理完毕）后，Runtime 会**主动关闭所有尚未关闭的遗留流**；
   - 最后按序关闭后台事件分发器、各个 Storage Adapter、释放单连接 SQLite 驱动，并释放文件独占锁。
3. **方案④约束（重要）**：
   - `closeGracePeriod` 仅为在途协程协作取消的宽限期，**不是**底层原生网络/IO 请求的最长耗时上限。
   - 底层 native WebDAV 请求在执行阶段可能无法被协程取消强行打断；**严禁外部调用方直接强杀线程**，否则可能引发进程崩溃或 native 状态损坏。

