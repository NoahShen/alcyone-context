# VFS 公共契约草案 v0.1

## 本次修改（2026-10-04，T17 实现回写 Metadata 事件与更新时间）

- 第 6.2 节补上 T17 落实的两个选择：**每次 `setMetadata` 都发一条 `METADATA_UPDATED`（含传相同值、含重复清空）**，以及**同一事务内 touch Node 的逻辑更新时间**；用一个「替换清空」例子解释。涉及第 6.2 节；接口签名与字段未变。

## 本次修改（2026-10-04，T16 复核回写删除事件粒度）

- 第 8 节补一句删除事件的最小口径：一次成功删除只发**目标级**事件，目录事件按完整段子树失效，不为后代逐个造事件（依据与示例见 [T16 任务文档 §2.4](../tasks/m3-t16/T16_删除操作开发及验收.md)）。事件类型与字段本身未变。

## 本次修改（2026-10-03，T13 独立复核）

- 接受 T13 增补 `VfsUri.of(VfsPath)`：从已校验的不可变逻辑路径构造 URI，编码仍统一在 toString，parse / 路径校验规则不变。涉及第 4 节类型与构造入口说明；不改变 stat、身份或权限语义。

## 本次修改（2026-09-29，错误码分类澄清）

- 在第 7 节补充 `INVALID_URI` 与 `INVALID_ARGUMENT` 的划分口径：路径类输入（含 URI 与其解码结果 `VfsPath`）用 `INVALID_URI`，ID、选项、字节内容等非路径参数用 `INVALID_ARGUMENT`。涉及第 7 节。

## 本次修改（2026-09-29，取消命名空间白名单）

- 明确 URI / Path 不校验命名空间成员资格，目录示例不构成白名单。涉及第 4.1 节。

日期：2026-09-27  
对应任务：T01  
状态：T01 审阅通过（2026-09-28），作为后续设计基线；未开始实现

## 本次修改（2026-09-29，补充流式读取方法）

- 确认保留 `read()` 的 ByteArray 签名，同时新增大文件流式读取方法；原交给 T04 的“流式 API 是否首版必需”结论改为“必需”。涉及第 5 节补充约束、第 10 节 T04 行。

## 本次修改（2026-09-29，明确 INVALID_URI 与 INVALID_ARGUMENT 的分类）

- 在第 7 节错误码表中区分 `INVALID_URI`（路径格式）与 `INVALID_ARGUMENT`（ID、选项等非路径参数），作为 T06 的实现决定。涉及第 7 节。

## 本次修改（2026-09-28，T03 审阅通过）

- 衔接已确认的事件规则：懒注册不公开事件，状态与事件同事务提交后分发。涉及第 9 节。

## 1. 本轮审阅内容

本草案定义调用方能看见的 VFS 接口、类型、存储约束、错误及生命周期。本稿已由用户审阅通过，下文接口与设计选择作为 T01 基线；明确交给后续任务的细节仍需收敛，不代表代码已存在。文件名保留“草案”以保持既有链接稳定。

本轮已确认：VFS 是面向个人 Context System 的统一文件访问库，直接接收文件操作参数；Agent 的目录分配、共享范围和访问隔离由 Context System 处理。VfsEntry 与 NodeInfo 继续保留。

| 编号 | 选择 | 当前状态 / 取舍 |
| --- | --- | --- |
| P1 | 文件操作直接传 URI / Node ID | 已确认；Context System 在调用 VFS 前处理 Agent 数据范围 |
| P2 | list 返回 VfsEntry，stat / getNode 返回 NodeInfo | 已确认；列目录不批量注册，NodeInfo 必有稳定 Node ID |
| P3 | Metadata 按 Node ID 管理，与 stat 返回值分开 | 已确认；提供独立查询 / 替换方法 |
| P4 | 可能访问存储或状态库的方法使用 suspend 与领域异常 | 已确认；取消信号单独传播 |
| P5 | Runtime 创建、组装和关闭资源，外围同步通过配置接入 | 已确认；宿主无需直接操作各后端或 Worker |
| P6 | ByteArray API 有大小限制，大文件流式能力在 T04 验证 | 已确认该推进方式；具体大小和流式能力仍由 T04 验证 |

设计依据：[项目搭建说明](<../Alcyone Context 项目搭建说明 v0.1.md>)、[VFS 技术设计](Alcyone_Virtual_File_System_VFS_技术设计_v0.2.md)、[核心用例](Alcyone_VFS_核心用例文档_v0.2.md)、[开发计划](../开发计划与进度.md)。

## 2. 公共边界

`vfs/api` 提供 `Vfs`、路径和 Node ID 类型、操作选项、结果模型、事件数据和领域异常。`vfs/runtime` 提供 `AlcyoneVfs`、启动配置、生命周期以及供 Context System 使用的事件消费入口。

OpenDAL Operator、SQLDelight Driver、Repository、Mount Resolver、Storage Path 和物理凭据均不进入 `Vfs` 的公共签名。Runtime 配置可描述 Local FS / WebDAV，但不要求调用方自行构造基础设施对象。

本轮不设计 HTTP、Memory Governance 或动态 Mount 管理接口。Mount 首版通过 Runtime 启动配置提供；持久化配置冲突由 T03 明确。

## 3. 职责边界与调用方式

| 层次 | 职责 |
| --- | --- |
| Context System | Agent 注册和身份管理；为 Agent 分配目录；决定共享范围；将 Agent 请求限定到可访问目录后调用 VFS |
| VFS | URI / Path、目录和文件操作、Node 资源身份、Metadata、Mount 路由、后端能力适配与变更事件；恢复为后续优化 |
| Storage Adapter | 使用 OpenDAL 访问实际 Local FS / WebDAV，转换后端能力与错误 |
| 同步扩展 | 根据配置消费事件、执行 Git 等同步、记录消费进度和重试；由 Runtime 接入，避免调用方手动组织同步流程 |

例如 Context System 可为两个 Agent 分配 `/resources/agents/quant/` 和 `/resources/agents/family/`，由上层构造和约束各自的 URI。VFS 将它们当作普通逻辑目录，不识别目录名所代表的 Agent，也不根据调用者筛选列表。共享目录同样由上层决定。

Context System 应在工具入口限制传给 VFS 的路径；目录名称本身不构成 VFS 内的访问隔离。VFS 只保证路径规范化和物理挂载根边界，避免逻辑路径被错误映射到挂载外。

```kotlin
val runtime = AlcyoneVfs.create(config)
try {
    val vfs: Vfs = runtime
    val content = vfs.read(
        VfsUri.parse("alcyone://resources/agents/quant/report.md")
    )
} finally {
    runtime.close()
}
```

AlcyoneVfs 实现 Vfs。所有文件、Node、Metadata、事件及生命周期 API 均无需调用身份，不提供匿名 / 管理员模式之分。Node Identity 仍保留，它描述资源身份，不描述调用者。

Context System 配置逻辑 Mount 和所需同步策略；普通 read / write / move / delete 不需要知道实际使用 WebDAV 还是 Local FS，也不需要直接操作 Git CLI。首个同步扩展仍按开发计划 E1 单独交付；统一接入不表示首版已实现任意后端的双向同步、冲突合并或外部 Watch。

## 4. 路径、Node 与查询结果

### 4.1 路径和 ID

| 类型 | 含义 | 示例 / 约束 |
| --- | --- | --- |
| `VfsUri` | 调用方使用的逻辑地址 | `alcyone://resources/medical/ct/a.dcm` |
| `VfsPath` | VFS 全局逻辑路径 | `/resources/medical/ct/a.dcm` |
| `NodeId` | 稳定资源身份 | UUIDv7，移动后不变；不是路径的哈希 |
| `StoragePath` | Adapter 内部物理相对路径 | 挂载根内的 `ct/a.dcm`，不作为公共文件操作参数 |

`VfsUri.parse(text)` 校验并构造 URI；`VfsUri.of(path: VfsPath)` 从已校验的逻辑路径构造 URI，`uri.path` 返回逻辑路径。`of` 不重新解码，序列化时仍逐段编码，例如 `/resources/my notes.txt` 输出 `alcyone://resources/my%20notes.txt`；`VfsPath` 和 `NodeId` 也只能通过校验后的构造入口生成。非法输入在本地被拒绝，不触发 Mount 或 Storage。规范化、编码、大小写、尾斜线和路径穿越规则由 T02 细化。

`memory` / `resources` 仅是逻辑命名空间示例，第一段必须作为路径保留，不能因通用 URI 库把它识别为 authority 而丢弃。API 不限制第一段的名称，也不查询配置；例如 `alcyone://notes/a.txt` 可解析，但不代表资源存在或已有匹配 Mount。

### 4.2 列表项与稳定 Node 分开

```kotlin
enum class NodeType { FILE, DIRECTORY }

data class StorageStat(
    val sizeBytes: Long?,
    val modifiedAt: Instant?,
)

data class VfsEntry(
    val uri: VfsUri,
    val type: NodeType,
    val nodeId: NodeId?,
    val storage: StorageStat?,
)

data class NodeInfo(
    val id: NodeId,
    val uri: VfsUri,
    val type: NodeType,
    val registeredAt: Instant,
    val updatedAt: Instant,
    val storage: StorageStat?,
)
```

这里 `Instant` 建议采用 JVM 的 `java.time.Instant`；序列化适配不在本轮展开。

- `VfsEntry.nodeId == null` 表示该条目未注册，不表示资源不存在。`list` 不为返回完整类型而批量注册 Node。
- `NodeInfo.id` 必须存在。`stat(uri)` 在底层资源确认存在后可以完成懒注册，因此它可能写入状态库。
- `registeredAt` 是 Node 注册时间，`updatedAt` 是 VFS 逻辑记录更新时间，均不是文件系统创建 / 修改时间。
- `storage == null` 表示本次没有获取底层属性；`sizeBytes` / `modifiedAt` 为空表示后端不提供该属性。目录大小不伪装成准确的递归文件总量。
- 返回值是一次操作的观察结果，不保证文件在返回后未被外部修改。第一阶段不跟踪全部外部变更。
- 普通 `NodeInfo` 不内嵌自定义 Metadata，调用方可按 Node ID 单独获取。

### 4.3 VfsEntry 的作用和使用场景

`VfsEntry` 就是 `list()` 返回的一个目录条目，供 Context System 浏览资源、展示目录或选择下一步要读取的文件。它是查询结果，不是额外的持久化实体，也没有单独的 Entry ID。

例如，WebDAV 上原本已有以下文件，VFS 尚未为其注册 Node：

```text
alcyone://resources/research/
├── report.md
└── images/
```

调用 `list(researchUri)`（researchUri 指向上述目录）返回两个 `VfsEntry`：一个 FILE、一个 DIRECTORY，二者的 `nodeId` 都可以为空。Context System 可直接按返回的 URI 读取 `report.md`，也可以继续列出 `images/`。

如果后续需要给 `report.md` 设置 Metadata，则先调用 `stat(reportUri)` 获取有稳定 ID 的 `NodeInfo`，再按 Node ID 更新 Metadata。

| 返回类型 | 用在哪里 | 是否要求已注册 |
| --- | --- | --- |
| `VfsEntry` | `list()`，描述目录中发现的条目 | 否，Node ID 可空 |
| `NodeInfo` | `stat()` / `getNode()` 及写入、移动回执 | 是，Node ID 必须存在 |

“两个查询结果类型”具体指 `VfsEntry` 和 `NodeInfo`，不是两套查询服务，也不是两个文件实体。保留两个类型是为了避免列目录产生大量 Node 注册。如果希望只保留 `NodeInfo`，就必须允许其 ID 为空，或让 `list` 自动注册；前者削弱稳定身份契约，后者改变当前懒注册设计。**本轮已确认保留 `VfsEntry` 和 `NodeInfo`。**

## 5. Vfs 接口草案

以下代码为已审阅的公共接口形状，不是已经编译发布的 SDK。集合分页、大文件流式接口以及可靠事件订阅签名尚未确定。

```kotlin
interface Vfs {
    suspend fun read(
        uri: VfsUri,
        options: ReadOptions = ReadOptions(),
    ): ByteArray

    suspend fun write(
        uri: VfsUri,
        content: ByteArray,
        options: WriteOptions = WriteOptions(),
    ): NodeInfo

    suspend fun stat(
        uri: VfsUri,
        options: StatOptions = StatOptions(),
    ): NodeInfo

    suspend fun getNode(id: NodeId): NodeInfo

    suspend fun list(uri: VfsUri): List<VfsEntry>

    suspend fun move(source: VfsUri, target: VfsUri): NodeInfo

    suspend fun delete(
        uri: VfsUri,
        options: DeleteOptions = DeleteOptions(),
    )

    suspend fun getMetadata(id: NodeId): NodeMetadata
    suspend fun setMetadata(id: NodeId, metadata: NodeMetadata)

}

data class ReadOptions(val maxBytes: Long? = null)
enum class WriteMode { CREATE_NEW, REPLACE_EXISTING, UPSERT }
data class WriteOptions(val mode: WriteMode = WriteMode.UPSERT)
data class StatOptions(val includeStorage: Boolean = true)
data class DeleteOptions(val recursive: Boolean = false)
```

### 5.1 每个方法的行为

| 操作 | 输入与返回 | 建议语义 |
| --- | --- | --- |
| `read` | URI → bytes | 仅读取文件，不懒注册，不产生变更事件；目录输入报类型错误 |
| `write` | URI、bytes、模式 → NodeInfo | 创建或更新文件，并持久化 Node；返回信息不额外调用 Storage stat，`storage` 可空 |
| `stat` | URI、查询选项 → NodeInfo | 默认取得底层属性；Node 不存在时可懒注册。`includeStorage=false` 仅允许已有 Node 走逻辑查询，未注册时仍须调用 Storage stat 以确认资源 |
| `getNode` | Node ID → NodeInfo | 查询已注册 Node 的当前位置等逻辑信息，`storage=null`；未知 ID 报 Not Found，不隐式寻找外部文件 |
| `list` | 目录 URI → 直接子项列表 | 首版单层列举、不递归、不注册 Node；无序返回，不承诺跨后端统一排序；嵌套 Mount 合并规则在 T02 确定，不按 Agent 筛选 |
| `move` | 源 URI、目标 URI → 移动后的 NodeInfo | 支持文件和目录，默认目标已存在则拒绝；保持 Node ID，返回信息不追加 Storage 查询 |
| `delete` | URI、递归选项 → Unit | 默认不递归，非空目录报错；不存在报 Not Found；成功后处理关联逻辑状态 |
| `getMetadata` | Node ID → Metadata | 只查询已注册 Node 的逻辑状态；未设置时返回空 Metadata |
| `setMetadata` | Node ID、完整 Metadata → Unit | 整体替换，空对象清空自定义 Metadata；不提供隐式字段合并 |

补充约束：

- `ReadOptions.maxBytes` 是调用方进一步收紧的上限；为空使用 Runtime 限额，不能解释为无限。流读取途中也需检查限制，不能先将任意大小文件完整读入内存再检查。具体限额在 T04 确定。
- `write` 的 CREATE_NEW / REPLACE_EXISTING / UPSERT 分别表示仅创建、仅覆盖已存在文件、创建或覆盖。建议覆盖已注册文件保持 Node ID。后端无法满足所承诺的并发条件时须报告不支持，不用不可靠的先查后写冒充原子操作；保证范围由 T02 / T04 明确。
- `move` 首版不开放覆盖选项，以避免在 T01 引入“源身份与目标身份谁保留”的隐含决定。T02 如决定支持覆盖，再增加明确的 `MoveOptions`。
- `write` 的缺失父目录、空目录创建、目录移动与嵌套 Mount 的组合支持由 T02 明确；本稿暂不增加 `mkdir`，若空目录是必需场景则补充接口。
- `list` 返回完整单层列表，适合首版有限规模目录；不能静默截断。分页及容量上限是否需要纳入首版由 T02 / T04 明确。
- 对 Node ID 输入直接查询逻辑记录，不需要 Storage；仅有 URI 的 Metadata 调用可先使用 `stat(uri, StatOptions(includeStorage=false))` 获取 ID。
- **流式读取（2026-09-29 T07 B1 评审确认）**：`read()` 保持 `ByteArray` 签名与 T04 确认的 16 MiB 默认限额（可配置），**新增大文件流式读取方法**。原“流式 API 是否首版必需”问题的结论改为“必需”。流式方法的具体签名在 T07 B3 给出 Interface 契约建议，由后续实现任务落地；`read()` 内部仍须在内容进入内存前中止超限读取。

## 6. 存储约束与 Metadata

### 6.1 文件系统约束

Context System 管理各 Agent 的目录和共享范围；VFS 接收已确定的文件操作参数。

VFS 仍处理文件系统自身的约束：URI 合法性、挂载根边界、目标类型、覆盖规则、后端操作能力，以及实际后端返回的拒绝访问 / 凭据错误。这些规则对同一个 VFS 实例的所有调用一致，不依赖 Agent。

```text
URI / 参数校验 → VfsPath → Mount / StoragePath → 存储约束检查 → Storage I/O
```

Node Registry 按 VfsPath 维护资源身份。普通 read / list 不强制注册；Node 查询、写入、移动、删除和 Metadata 按操作需要读取或更新 Registry。

首版不增加可配置只读挂载。操作检查后端的实际能力：移动要求源支持读取 / 移除、目标支持写入；能力不足时明确失败。物理后端只读时拒绝内容变更，但 Metadata 存储于独立状态库，其修改不依赖物理文件可写性。

### 6.2 Metadata 形状建议

```kotlin
data class NodeMetadata(
    val tags: Set<String> = emptySet(),
    val description: String? = null,
    val extensions: JsonObject = JsonObject(emptyMap()),
)
```

JsonObject 来自 Kotlin Serialization。移除固定 owner 字段以避免与 Agent 归属混淆；若上层需要业务标记，可自行维护或放入 extensions，VFS 不解释这些字段的业务含义。

Metadata 绑定 Node ID，移动后保留。查询 / 替换已注册 Node 的 Metadata 不访问 Storage；其值不会因切换物理后端自动丢失。

**替换与事件（T17 落实）**：`setMetadata` 整体替换，传空对象就是清空；每次有效调用都产生**一条** `METADATA_UPDATED`，包括传入与当前完全相同的值、包括重复清空——不提供相等比较或去重，想知道「变没变」由调用方自己比较前后两次 `getMetadata`。事件里的 `nodeId` 是目标 ID，`uri` 是这次受保护读取里看到的 Node 当时所在路径。

例：一个 Node 原本是 `tags = {ct, 影像}`、`description = 胸部 CT 报告`，传一个只有 `description = 报告` 的对象进去，结果是 `tags` 变空、`extensions` 也变空——整体替换，不与旧值合并；接着再传一次完全相同的对象，仍然发一条事件。

**更新时间（T17 落实）**：替换成功时在同一事务内把 Node 的逻辑更新时间 `updatedAt` 推到本次调用的时刻，ID、路径、类型和登记时间不变，物理文件时间不动；事件与 Metadata 同批提交，失败一起回滚。注入时钟精度内连续设置可能取得相同时间，不要求严格递增。

## 7. 错误契约

建议使用 `VfsException` 加稳定 `VfsErrorCode`；调用方按 code 分支，不解析错误文本，不依赖 OpenDAL / SQLDelight 异常类型。

| 错误码建议 | 主要触发条件 |
| --- | --- |
| INVALID_URI / INVALID_ARGUMENT | URI、ID、选项或数据格式非法；本地校验失败。分类：路径格式错误（`VfsUri`、`VfsPath`）用 `INVALID_URI`；ID、选项、字节内容等非路径参数用 `INVALID_ARGUMENT` |
| READ_ONLY | 底层存储为只读，拒绝物理变更 |
| STORAGE_ACCESS_DENIED | 后端拒绝访问或凭据无效 |
| NOT_FOUND | 底层资源不存在，或查询的 Node ID 无记录 |
| ALREADY_EXISTS | CREATE_NEW 或 move 遇到已有目标 |
| TYPE_MISMATCH / DIRECTORY_NOT_EMPTY | 文件 / 目录类型不符合操作，或非递归删除非空目录 |
| MOUNT_NOT_FOUND | 没有可用 Mount |
| UNSUPPORTED_OPERATION | 后端能力或当前 SDK 不支持所请求语义 |
| LIMIT_EXCEEDED | 内容大小等明确限制被超过 |
| STORAGE_ERROR | 底层 I/O 或网络失败；后端明确拒绝访问时归为 STORAGE_ACCESS_DENIED |
| STATE_ERROR | Node / Metadata / Event 等关键状态持久化失败 |
| CONFLICT | 已确定的并发控制规则检测到冲突 |
| RECOVERY_REQUIRED | 预留给后续恢复能力；首版使用 STORAGE_ERROR / STATE_ERROR 等现有错误及 effect 报告结果 |
| CLOSED | Runtime 已关闭或正在关闭，不接受新操作 |

路径类输入与非路径参数的划分口径：`VfsUri` 与其解码结果 `VfsPath` 是同一份路径表示（`VfsPath` 只接受已解码的绝对逻辑路径和路径段列表，不接受其它对象），两者格式非法时统一返回 `INVALID_URI`，调用方不需要先判断输入来自哪个入口。`INVALID_ARGUMENT` 用于非路径参数：Node ID、事件 ID、读写选项（如负数 `maxBytes`）以及内容数据格式。例：`VfsPath.parse("/a//b")` 与 `VfsUri.parse("alcyone://a//b")` 同为 `INVALID_URI`；`NodeId.parse("非 UUIDv7")` 与 `ReadOptions(-1)` 为 `INVALID_ARGUMENT`。

### 7.1 异常需要携带的信息

除 code、可读 message 外，建议异常包含可空的逻辑 URI、operation ID 和 effect。不公开物理 Storage Path、连接串、凭据或原始后端响应。

| effect 建议值 | 含义 |
| --- | --- |
| NONE | 确认没有提交此次操作造成的逻辑或物理变更 |
| PARTIAL | 已知存在部分变更，首版不自动修复 |
| UNKNOWN | 无法确定后端是否完成操作，例如响应前连接中断 |

effect 不能仅由错误码猜测：写入时的 STORAGE_ERROR 可能已经发生部分写入；懒注册也可能产生状态变更。变更边界与 effect 归类在实现测试中确定，不要求恢复日志。

部分成功与结果不明不得伪装成“操作未发生”。operation ID 仅用于关联诊断，不要求持久化操作日志；首版不增加客户端幂等键。没有可靠保证时，不在异常中提供简单的 `retryable=true` 鼓励盲目重试。

### 7.2 每类操作的失败范围

所有方法均可能产生参数、状态库和 CLOSED 错误。涉及 Storage 的方法还可能产生 MOUNT_NOT_FOUND、STORAGE_ACCESS_DENIED、STORAGE_ERROR 或 UNSUPPORTED_OPERATION；物理变更还可能产生 READ_ONLY；其他主要错误如下：

| 操作 | 重点错误 / 边界 |
| --- | --- |
| read | NOT_FOUND、TYPE_MISMATCH、LIMIT_EXCEEDED |
| write | ALREADY_EXISTS、NOT_FOUND（仅覆盖模式）、TYPE_MISMATCH、LIMIT_EXCEEDED、CONFLICT、RECOVERY_REQUIRED |
| stat | NOT_FOUND、CONFLICT；懒注册失败需区分状态是否已提交 |
| getNode、Metadata 管理 | 未知 Node ID 为 NOT_FOUND；更新失败按数据库事务回滚；不产生 Storage 错误 |
| list | NOT_FOUND、TYPE_MISMATCH、LIMIT_EXCEEDED（若确定列表限额） |
| move | NOT_FOUND、ALREADY_EXISTS、TYPE_MISMATCH、CONFLICT、RECOVERY_REQUIRED |
| delete | NOT_FOUND、DIRECTORY_NOT_EMPTY、CONFLICT、RECOVERY_REQUIRED |

Kotlin Coroutine 的 `CancellationException` 原样传播，不包装为普通业务异常。取消发生在外部 I/O 或提交附近时，调用方不能据此断言操作未发生；首版不记录恢复进度或在重启后续做，资源清理在实现时明确。未知 Node ID 直接返回 NOT_FOUND；Agent 对资源的可见性由 Context System 处理。

## 8. 事件：公共数据与宿主消费分开

事件领域模型放在 API，事件日志 / 消费接口由 Context System 或配置的外围 Worker 使用，不携带 Agent 身份、不在 VFS 内按 Agent 过滤。Context System 决定是否向其 Agent 暴露事件。

本轮建议的事件公共字段：事件 UUIDv7 ID、事件类型、可空 Node ID、发生时间、逻辑 URI 和关联 operation ID。移动事件额外包含源 / 目标 URI；消费进度及处理状态属于 Event Log / Consumer 状态，不让订阅者直接修改原事件。

事件类型覆盖 FILE_CREATED、FILE_WRITTEN、FILE_MOVED、DIRECTORY_MOVED、FILE_DELETED、DIRECTORY_DELETED、METADATA_UPDATED；按已确认的 T03，首版不公开 NODE_REGISTERED。Payload 不包含文件内容、凭据。

删除事件只发目标级：一次成功删除产生**一条** `FILE_DELETED` 或 `DIRECTORY_DELETED`，URI 为请求目标，Node ID 为目标已有身份（未登记则为空）。目录事件表示该 URI 之下**按完整路径段边界**的整棵子树失效，不为每个后代另造事件；Consumer 需要逐文件信号时应在删除前自行展开。口径与示例见 [T16 §2.4](../tasks/m3-t16/T16_删除操作开发及验收.md)。

UC-07 的外部可见契约建议：事件代表已完成的核心变更；Consumer 失败不撤销该变更。进程内 Flow 是通知机制，不能单独代表可靠消费。首版仅持久化事件和进程内通知，消费接口在实现时明确；日志游标、确认和重放列为后续 E05。

调用被取消或响应丢失时，核心变更可能已经完成并有事件，不能仅凭调用方观察到异常就判定事件不应存在。核心状态未完成的操作不能产生表示全操作成功的事件；部分完成通过异常 effect 报告，不保存恢复进度。

## 9. Runtime 与资源生命周期

Runtime 接口形状建议如下，`VfsConfig` 的完整字段与验证规则留待 T02～T04：

```kotlin
// vfs/runtime，声明示意。
class AlcyoneVfs : Vfs {
    companion object {
        suspend fun create(config: VfsConfig): AlcyoneVfs
    }

    // Vfs 方法由组装后的 Core 实现；此处省略。
    suspend fun close()
}
```

- `create` 可能访问数据库、加载 native 依赖和初始化资源，因此采用挂起函数。初始化失败时清理已经创建的资源。
- 初始配置至少描述 VFS 状态库位置、Mount 列表、I/O 限额和关闭策略；初始配置和持久化状态的优先关系由 T03 定义。
- `close` 幂等，并发关闭只执行一次资源释放。进入关闭状态后拒绝新操作，等待在途操作至配置期限，超时后取消剩余工作并释放资源，不保存续做进度。
- 关闭应终止 Runtime 自己的事件分发任务并释放其 Operator、DB 等资源，不关闭宿主提供且仍由宿主持有的共享资源。
- 关闭不等待 Git push 等外围 Consumer 业务完成；首版不保证关闭后补发，可靠消费由后续 E05 提供。
- 采用 `suspend close()`，首版不同时承诺 Java `AutoCloseable.close()` 的阻塞语义；示例使用协程内 `try/finally`。实现需使必要清理不被宿主协程取消直接中断，精确超时行为在 T03 明确。
- 不强绑定宿主的 Web Framework 或日志实现。同步 Java 包装不是本轮范围，不引入身份 Session。

### 9.1 同步扩展的接入边界

SDK / Runtime 负责接入已实现的同步扩展，Context System 只声明需要同步的目录和策略，不创建 Git Worker 或手动推进消费游标。具体同步配置类型随 E1 定义，不为了未来后端建立空泛的通用同步框架。

文件操作成功与异步同步完成分开：write 返回表示核心文件与逻辑状态按契约提交，不表示远端 Git push 已完成。同步状态和失败重试由扩展记录并通过 SDK 的独立查询 / 诊断入口提供，入口形状在 E1 确定；同步失败不回滚已成功的文件操作。

第一阶段不承诺发现全部外部文件变化、双向同步或自动冲突合并。Node / Metadata / Event 所在 SQLite 的备份也不等同于同步普通文件目录。

## 10. 交给后续任务的问题

| 任务 | 本轮提供的输入 | 后续必须收敛的内容 |
| --- | --- | --- |
| T02 | 无身份文件 API、路径类型、列表项模型、操作选项 | 路径规范化、后端能力、空目录、递归、覆盖、嵌套 Mount 列表和 Metadata 修改边界 |
| T03 | NodeInfo、Metadata、错误 effect、事件与关闭边界 | 状态职责、事务、失败报告、配置冲突、进程内事件与关闭；操作恢复和可靠消费延后 |
| T04 | ByteArray 首版建议、挂起 API、Runtime 资源模型 | native 支持平台、Storage 原子能力、大小限制、流式 API 是否成为首版必需项及测试依据。**流式 API 必需性已于 2026-09-29 T07 B1 确认**，见第 5 节 |
| E1 | 文件访问与同步扩展分离、SDK 隐藏 Worker 细节 | Git 同步配置、目录范围、状态查询、重试和资源生命周期 |

T01 审阅通过表示公共接口方向得到确认；依赖后续决策的字段和接口仍需在实现前收敛。如果后续任务需要调整本稿，应直接更新对应章节并记录原因，不维持两套矛盾的契约。

## 11. T01 审阅与完成标准

- 文件与 Metadata 操作都有输入、返回、存储约束和错误说明。
- VfsEntry / NodeInfo 均保留，位置与资源身份分离，list 不批量注册。
- Context System 负责 Agent 目录和访问范围，VFS 负责文件模型与存储适配；同步由配置接入的外围 Worker 承担。
- 公共 API 不暴露 OpenDAL、SQLDelight 或 Repository 类型，存储 / 同步差异不进入普通文件操作参数。
- T01 已于 2026-09-28 获用户确认，任务标记 DONE；不将后续任务的待定项视为已实现能力。

本稿仅完成文档层面的交叉检查，未编译 Kotlin 片段，也未运行 SDK 测试。代码片段是契约示意，包含将在实现阶段定义的类型和省略的方法体。

## 12. 当前审阅结论

- 第三轮已确认：VFS 为简单的统一文件系统；Agent 身份绑定、数据归属、目录分配及访问隔离由 Context System 处理。VFS 接口直接接收文件操作参数。
- 继续保留：VfsEntry / NodeInfo、稳定 Node 资源身份、Metadata、Mount 与事件；恢复按本次反馈延后。
- VFS 通过 Adapter 隐藏存储渠道差异，通过 Runtime 接入同步扩展；Git Sync 仍按 E1 单独交付，不自动扩大为通用同步产品。
- 文档每次更新必须在前部说明主要变更及涉及章节。
- 用户于 2026-09-28 确认本稿目前无问题，T01 已完成。路径与目录操作进入 [T02 语义草案](VFS_路径与文件操作语义_v0.1.md)，流式 API 和同步配置继续按计划收敛；操作恢复延后至 E04；尚未开始实现。
