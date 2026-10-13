# Alcyone VFS SDK 使用指南

> 本次修改（2026-10-13，T30 README 与 SDK 使用及运维文档开发及验收）：新增 SDK 使用指南，系统讲解初始化与 Mount 配置、Context System 职责分工、核心文件操作与三种写入模式、Node 身份与 Metadata 机制、流式分块读取与完整资源释放、事件订阅与 Consumer 异常隔离边界，以及 Runtime 实例的关闭与超时边界。

本文档面向需要在源码工程中集成使用 Alcyone VFS 的开发者。当前版本仅支持在源码工程内直接调用，底层隐藏了 Local FS 与 WebDAV 的具体存储差异。

---

## 1. 架构定位与 Context System 分工

- **SDK 纯粹性**：VFS 仅负责文件与目录的统一寻址、I/O 读写、状态登记与事件发射。
- **无身份 / 无 ACL**：VFS 内部**不绑定 Agent 身份**，也**不做访问控制列表（ACL）**。
- **上层约定分工**：Context System 决定将哪些逻辑目录分配给哪个 Agent（例如规定 Agent A 仅访问 `alcyone://resources/agent-a/...`）。对于持有 `AlcyoneVfs` 实例的调用方，VFS 不会主动阻止其访问实例内的其它合法路径。安全性与租户隔离由 Context System 上层统筹控制。

---

## 2. 初始化与 Mount 配置

宿主调用者只需构造 `VfsRuntimeConfig`，并通过 `AlcyoneVfs.create(config)` 创建 Runtime 实例。宿主**无需**手工实例化底层 Operator、Driver、Repository 或 Router。

### 2.1 配置结构与参数说明

```kotlin
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountBackend
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

// 1. 挂载本地目录
val localMount = MountConfig(
    vfsPath = VfsPath.parse("/resources/docs"), // 逻辑挂载路径，必须属于已声明的命名空间，不能为 "/"
    storageKey = "docs-local",                  // 盘标识符，同配置内唯一，不能有空白字符
    backend = MountBackend.LocalFs(Path.of("/var/data/docs")) // 本地物理目录（必须已存在，会自动规范化真实绝对路径）
)

// 2. 挂载 WebDAV 远端存储
val webdavMount = MountConfig(
    vfsPath = VfsPath.parse("/resources/remote"),
    storageKey = "remote-dav",
    backend = MountBackend.WebDav(
        endpoint = "http://127.0.0.1:8080", // 仅支持 http:// 或 https://，禁止将账号密码拼入 URL（如 user:pass@host）
        root = "/dav/share",                 // 服务端共享根目录
        username = "davuser",                // 凭据仅在配置内传递，不进数据库、不进身份串、不打入日志
        password = "davpassword"
    )
)

// 3. 构造运行时配置
val config = VfsRuntimeConfig(
    stateDatabase = Path.of("/var/data/state.db"), // SQLite 状态库路径（不能放在挂载目录内部）
    namespaces = setOf("resources", "memory"),     // 声明顶级命名空间
    mounts = listOf(localMount, webdavMount),      // 挂载点列表（物理目录不能相互重叠包含）
    closeGracePeriod = 30.seconds                  // 关闭时等待在途操作的宽限期
)

// 4. 创建 VFS 实例
val vfs = AlcyoneVfs.create(config)
```

### 2.2 挂载与约束边界
- **物理目录独立**：状态库文件绝对不能位于任何挂载的本地物理根目录下；多个本地挂载物理目录之间禁止重叠嵌套。
- **凭据保护**：WebDAV 密码在 `toString()`、身份串和任何日志中均被脱敏或屏蔽，严禁把密码写在 URI 的 userinfo 字段中。

---

## 3. 核心文件操作与写入模式

所有读写方法均通过挂起函数执行，路径统一使用 `VfsUri.parse("alcyone://<namespace>/<path>")`。

### 3.1 核心操作 API

| 操作 | 对应 API | 说明 |
| :--- | :--- | :--- |
| **小文件读取** | `read(uri, options)` | 返回 `ByteArray`。只读操作，不登记 Node，不发射事件。超过限额抛 `LIMIT_EXCEEDED`。 |
| **状态查询** | `stat(uri, options)` | 查询底层属性。如果物理存在但尚未在状态库登记，会触发**懒注册**（写入状态库并分配 Node ID）。`includeStorage=false` 对已登记节点直接走逻辑库，对未登记节点仍会查询底层确认存在。 |
| **节点逻辑查询**| `getNode(id)` | 纯逻辑查询，通过 Node ID 查属性。ID 不存在返回 `NOT_FOUND`，绝不扫描底层。 |
| **目录列举** | `list(uri)` | 单层列举直接子节点，不递归，不保证排序。未登记的物理项其 `nodeId` 为 `null`。 |
| **文件/目录移动**| `move(source, target)`| 移动文件或目录。**保持原 Node ID 与 Metadata 连续性**。目标已存在则直接拒绝报错。 |
| **文件/目录删除**| `delete(uri, options)`| 默认非递归，目录非空返回 `DIRECTORY_NOT_EMPTY`；设置 `DeleteOptions(recursive = true)` 级联删除子树及全部子节点状态。删除后原 Node ID 废弃；在原路径新建将分配全新 Node ID。 |

### 3.2 三种写入模式（`WriteOptions`）

写入操作使用 `write(uri, bytes, options)`，支持三种模式：

```kotlin
import com.github.noahshen.alcyone.context.vfs.WriteMode
import com.github.noahshen.alcyone.context.vfs.WriteOptions

// 1. CREATE_NEW：仅当目标文件不存在时成功；目标已存在时抛 ALREADY_EXISTS（不触碰底层物理文件）
vfs.write(uri, content, WriteOptions(WriteMode.CREATE_NEW))

// 2. REPLACE_EXISTING：仅当目标文件已存在时覆盖；目标不存在时抛 NOT_FOUND
//    覆盖成功后：保持原有 Node ID、保留已有 Metadata，触发 FILE_WRITTEN 事件
vfs.write(uri, content, WriteOptions(WriteMode.REPLACE_EXISTING))

// 3. UPSERT（默认模式）：文件不存在则创建，文件已存在则覆盖并保持 Node ID
vfs.write(uri, content, WriteOptions(WriteMode.UPSERT))
```

> **重要细节**：
> - 自动补全父目录：写文件时如果父级目录不存在，VFS 会自动在底层补齐物理目录，但**不会为父级目录登记 Node ID，也不会触发目录创建事件**。
> - 覆盖写事件：文件覆盖写产生的是 `FILE_WRITTEN` 事件类型。

---

## 4. Node 身份与 Metadata 机制

- **Node ID**：UUIDv7 格式的全局唯一标识。与 URI 相对解耦——移动文件或重命名时，URI 改变，但 Node ID 保持不变；同路径删除后重建，会生成全新的 Node ID。
- **Metadata 整体替换**：
  ```kotlin
  import com.github.noahshen.alcyone.context.vfs.NodeMetadata

  // 设置或更新元数据（完全覆盖替换，不提供隐式合并）
  vfs.setMetadata(nodeId, NodeMetadata(tags = setOf("report", "q4"), description = "财务报表"))

  // 读取元数据
  val meta = vfs.getMetadata(nodeId)
  println("tags=${meta.tags}, desc=${meta.description}")

  // 清空元数据
  vfs.setMetadata(nodeId, NodeMetadata.EMPTY)
  ```
- **更新语义**：`setMetadata` 为整体替换语义，每次成功 set 都会刷新 `updatedAt` 并发射一次 `METADATA_UPDATED` 事件（即使内容相同也是一次显式更新）。

---

## 5. 大文件分块流式读取（`openStream`）

对于大于内存缓冲限额（默认 16 MiB）的文件，必须使用 `openStream` 进行流式读取。流式读取不把完整文件载入内存，且不登记 Node、不发射事件。

调用方**必须显式关闭返回的 `VfsStreamResult`**，推荐使用 Kotlin 的 `.use { ... }` 语法块以确保底层 Storage Reader 与临时资源可靠释放：

```kotlin
import com.github.noahshen.alcyone.context.vfs.VfsStreamOptions
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

VFS 支持进程内异步事件订阅。事件通知具有以下重要契约：

```kotlin
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import kotlinx.coroutines.CompletableDeferred

val fileCreated = CompletableDeferred<VfsEvent>()

// 1. 订阅必须在目标操作发生之前建立
val subscription = vfs.subscribe { event ->
    // 建议按目标 Node ID 或事件类型过滤，切勿在测试中使用无保障的固定 sleep 等待
    if (event.type == VfsEventType.FILE_WRITTEN && event.uri == targetUri) {
        fileCreated.complete(event)
    }
}

// 2. 执行操作，事件将在事务提交后由后台分发器投递
vfs.write(targetUri, "content".toByteArray())
val event = fileCreated.await()
```

### Consumer 异常与隔离边界
1. **同事务后通知**：事件在 SQLite 事务持久化成功后投递；若事务回滚，绝不会发射事件。
2. **普通异常隔离**：普通 Consumer 抛出的 `Exception` 会被分发器捕获并记录 WARN 日志，**不会影响其它 Consumer，也不会回滚或反向中断发起写入的调用方**。
3. **取消与 Error**：`CancellationException` 将按协程语义正常向上传播；严重 `Error` 不被捕获。
4. **有界队列与背压**：事件分发器内置固定容量（默认 256）的有界缓冲区。慢 Consumer 会拖慢当前分发循环；当队列溢出时可能发生事件丢弃。首版**不承诺可靠重试、消息 ACK、持久化重放或死信队列**。

---

## 7. 实例生命周期与关闭超时边界

`AlcyoneVfs` 实例持有文件锁、SQLite 连接池以及后台分发协程，使用完毕后必须在 `finally` 块中调用挂起函数 `close()` 优雅关闭：

```kotlin
val vfs = AlcyoneVfs.create(config)
try {
    // 业务操作
} finally {
    vfs.close() // 优雅关闭挂起函数
}
```

### 关闭时序与边界说明
1. **停止接纳**：调用 `close()` 后，实例状态置为 closing，后续任何新操作均直接拒绝并抛出 `VfsErrorCode.CLOSED`。
2. **在途等待**：实例在 `closeGracePeriod`（默认 30 秒）期限内等待当前已接纳的操作与遗留流完成。到期后对剩余在途任务发起协程取消请求，并等待清理释放。
3. **方案④约束（重要）**：
   - `closeGracePeriod` 仅为协作取消宽限期，**不是**底层原生网络/IO 请求的最长耗时上限。
   - 底层原生 WebDAV 请求在执行阶段可能无法被强行中断；**严禁外部调用方直接强杀线程**来试图加速释放 native 资源，否则可能引发进程崩溃或底层句柄泄漏。
