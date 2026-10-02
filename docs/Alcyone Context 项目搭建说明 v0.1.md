# Alcyone Context 项目搭建说明

## 本次修改（2026-10-02，模块更名同步当前目录）

- 当前有效目录树、模块清单、依赖图、测试路径与模块章节标题统一为 `vfs/storage` 与 `vfs/persistence`（原 `vfs/storage-opendal` / `vfs/persistence-sqldelight`），与 T11 交付的重命名结果一致。涉及第 2、4、7、8、9.1、10、11、12、13 节。

## 本次修改（2026-09-29，工具链版本单一来源）

- 声明 `gradle/toolchain.versions` 为 JDK 与 Gradle 版本、下载地址和 SHA-256 的单一来源，`scripts/dev` 与根构建守卫均从它读取。涉及第 2 节。

## 本次修改（2026-09-29，新增 common 模块）

- 目录结构新增与 `vfs/` 平级的 `common/` 公共工具库模块，并说明其职责与依赖方向。涉及第 2 节。

## 本次修改（2026-09-28，任务文档目录更新）

- 明确正式开发工具只在项目内生效，链接更新为独立目录 `docs/tasks/m1-t05/T05_工程初始化与验收.md`。涉及第 2 节。

**版本**：v0.1  
**更新时间**：2026-09-28  
**状态**：Draft  

---

# 1. 文档目的

本文档用于定义 `alcyone-context` 项目的初始工程目录结构，以及当前阶段各主要目录和模块的职责边界。

`alcyone-context` 是 Alcyone Personal Context System 的独立开源项目。

VFS 提供统一文件访问并隐藏后端差异；Agent 身份、目录分配和访问隔离由 Context System 处理，VFS 直接处理路径和文件操作。

当前第一阶段优先开发 Alcyone Virtual File System（VFS），因此本阶段项目结构只包含 VFS 相关模块，不提前创建 Memory、Retrieval、Context API 等后续模块。

核心原则：

> **先完成稳定、可独立使用的 VFS 基础能力，再在其之上建设 Context System。**

---

# 2. 项目目录结构

正式工程采用 Gradle Kotlin DSL 与 Wrapper；Gradle 8.14.3、Kotlin 2.2.21、JDK 21 已完成 [T04 最小验证](vfs/VFS_依赖与能力验证_v0.1.md)，已获用户确认，将在 T05 配置。`tools/validation/t04/` 是独立验证工程，不属于 SDK 模块或对外产物。

正式开发的 JDK、Gradle 发行包和依赖缓存只放在项目内忽略目录，由项目入口设置进程级环境，不改变系统默认 Java 或用户 shell 配置。具体环境准备、模块交付和验收要求见 [T05 工程初始化与验收](tasks/m1-t05/T05_工程初始化与验收.md)。

JDK 与 Gradle 的版本、下载地址和 SHA-256 集中在 `gradle/toolchain.versions`（每行 `KEY=VALUE`），是工具链版本的唯一来源；`scripts/dev` 与根 `build.gradle.kts` 的 JDK 守卫、`tasks.wrapper` 都从该文件读取，不在代码中重复硬编码。`gradle/wrapper/gradle-wrapper.properties` 是 wrapper 任务生成的产物，改版本后应同步重新生成。

初始项目目录如下：

```text
alcyone-context/
├── docs/
│
├── common/
│
├── vfs/
│   ├── api/
│   ├── core/
│   ├── storage/
│   ├── persistence/
│   └── runtime/
│
└── integration-tests/
    └── vfs/
```

整体职责可以概括为：

```text
docs
→ 项目与 VFS 设计文档

common
→ 与业务无关的公共工具库（UUIDv7 生成与校验），供 vfs、memory 等模块共用

vfs/api
→ VFS 对外契约

vfs/core
→ VFS 核心业务逻辑

vfs/storage
→ 底层 Storage 实现（原 vfs/storage-opendal）

vfs/persistence
→ VFS 关键状态持久化（原 vfs/persistence-sqldelight）

vfs/runtime
→ VFS SDK 入口与运行时组装

integration-tests/vfs
→ 完整 VFS 跨模块集成测试
```

---

# 3. `docs/`

`docs/` 用于保存项目设计和开发文档。

当前阶段主要保存 VFS 相关文档，例如：

```text
docs/
├── architecture/
└── vfs/
```

建议后续整理为：

```text
docs/
├── architecture/
│   └── overview.md
│
└── vfs/
    ├── design.md
    ├── use-cases.md
    └── storage.md
```

其中：

## 3.1 `architecture/`

保存 `alcyone-context` 项目的整体技术架构说明。

当前阶段只需要描述：

- 项目定位；
- VFS 在 Alcyone Context System 中的位置；
- Module 划分；
- Module 依赖关系；
- 关键架构原则。

不需要提前描述尚未实现的上层模块细节。

## 3.2 `vfs/`

保存 VFS 专项设计文档。

当前已有设计内容可以逐步整理到这里，包括：

```text
VFS 技术设计
VFS 核心用例
Storage 设计
Node / Mount / 存储能力设计
Event 设计
```

原则：

> **代码描述实现，docs 描述设计与决策。**

---

# 4. `vfs/`

`vfs/` 是当前阶段的核心开发目录。

VFS 本身采用模块化设计：

```text
vfs/
├── api/
├── core/
├── storage/
├── persistence/
└── runtime/
```

模块划分的主要目标是：

1. 将公共 API 与内部实现分离；
2. 将业务逻辑与基础设施实现分离；
3. 隐藏 OpenDAL、SQLDelight、SQLite 等具体技术细节；
4. 为 Context System 提供简单稳定的 VFS SDK；
5. 保持未来 Storage、Persistence 实现可替换。

---

# 5. `vfs/api`

`vfs/api` 是 VFS 的公共契约模块。

它回答：

> **VFS 能提供什么能力？**

主要包含 VFS 对调用方暴露的接口、类型和数据模型。

典型内容包括：

```text
Vfs
VfsUri
VfsPath

NodeId
NodeInfo
VfsEntry
NodeType

Metadata

VfsEvent

ReadOptions
WriteOptions
ListOptions
MoveOptions

VfsException
```

例如核心接口：

```kotlin
interface Vfs {
    suspend fun read(uri: VfsUri): ByteArray
    suspend fun write(uri: VfsUri, content: ByteArray): NodeInfo
    suspend fun stat(uri: VfsUri): NodeInfo
    suspend fun getNode(id: NodeId): NodeInfo
    suspend fun list(uri: VfsUri): List<VfsEntry>
    suspend fun move(source: VfsUri, target: VfsUri): NodeInfo
    suspend fun delete(uri: VfsUri)
    suspend fun getMetadata(id: NodeId): NodeMetadata
    suspend fun setMetadata(id: NodeId, metadata: NodeMetadata)
}
```

以上仅为接口示意，完整选项以 [公共契约草案](vfs/VFS_公共契约草案_v0.1.md) 为准。list 返回 Node ID 可空的 VfsEntry；NodeInfo 必有稳定 Node ID。文件和生命周期 API 均不传调用者身份。

`vfs-api` 不应该包含：

```text
SQLDelight
SQLite
OpenDAL
NodeRepository 实现
EventRepository 实现
Storage Provider 实现
具体依赖组装逻辑
```

原则：

> **API 定义能力，不暴露实现。**

该模块应保持尽可能稳定，因为未来 Context System 及其他调用方主要依赖这里定义的 VFS 契约。

---

# 6. `vfs/core`

`vfs/core` 是 VFS 的核心业务逻辑模块。

它回答：

> **VFS 的规则如何运行？**

主要负责：

```text
URI 解析
Node Identity
Node Registry
Metadata
Mount
Event
VFS 文件操作编排
```

典型内部结构可以为：

```text
vfs/core/
└── src/main/kotlin/
    └── .../vfs/
        ├── uri/
        ├── node/
        ├── metadata/
        ├── mount/
        ├── event/
        ├── storage/
        ├── repository/
        └── service/
```

其中可以包含：

```text
VfsUriResolver
NodeRegistry
MetadataManager
MountResolver
EventDispatcher
DefaultVfs
```

以及 VFS Core 所依赖的 Port：

```text
Storage
NodeRepository
MetadataRepository
MountRepository
EventRepository
```

这些 Port 只定义 Core 需要什么能力，而不指定具体实现技术。

例如：

```kotlin
interface NodeRepository {
    ...
}

interface Storage {
    ...
}
```

真正的实现分别由：

```text
storage
persistence
```

提供。

## 6.1 VFS Core 的核心访问链路

所有可能触发 Storage I/O 的 VFS 操作统一遵循：

```text
VfsUri
   ↓
VfsPath
   ↓
Mount / StoragePath
   ↓
路径边界 / 存储能力检查
   ↓
Storage
```

核心原则：

> **先校验路径、路由 Mount 并检查存储约束，再访问实际 Storage；Node Registry 按具体用例参与，用于资源身份与状态维护。**

因此以下操作：

```text
read
write
stat
list
move
delete
```

都由 `vfs-core` 统一编排，而不是由 Storage Adapter 自行决定访问流程。

---

# 7. `vfs/storage`

`vfs/storage`（原 `vfs/storage-opendal`）是 VFS 的 Storage 基础设施实现模块。

它回答：

> **实际文件如何读写？**

该模块使用 OpenDAL Java Binding 实现 `vfs-core` 定义的 `Storage` Port。

整体关系：

```text
VFS Core
    │
    │ Storage Port
    ▼
storage
    │
    ▼
OpenDAL
    │
    ├── Local FS
    ├── WebDAV
    └── Future Storage
```

主要职责包括：

```text
创建 OpenDAL Operator
执行 read / write / stat / list
执行 move / delete
处理不同 Storage Capability
将 OpenDAL Error 转换为 VFS Storage Error
```

VFS Core 不应该直接依赖：

```text
Local FS API
WebDAV API
S3 API
```

这些差异统一由 OpenDAL Adapter 屏蔽。

第一阶段主要支持：

```text
Local FS
WebDAV
```

未来可根据需要增加：

```text
S3
MinIO
OSS
其他 OpenDAL Backend
```

而无需修改 VFS Core。

原则：

> **VFS 管理逻辑资源，OpenDAL 管理物理 Storage。**

---

# 8. `vfs/persistence`

`vfs/persistence`（原 `vfs/persistence-sqldelight`）是 VFS 关键状态的持久化实现模块。

它回答：

> **VFS 自身的系统状态如何持久化？**

该模块使用：

```text
SQLite
+
SQLDelight
```

实现 `vfs-core` 定义的 Repository Port。

主要保存：

```text
Node Registry
Mount
Metadata
Event Log
```

典型实现包括：

```text
SqlDelightNodeRepository
SqlDelightMountRepository
SqlDelightMetadataRepository
SqlDelightEventRepository
```

SQLDelight Schema 可以放在：

```text
src/main/sqldelight/
```

例如：

```text
Node.sq
Mount.sq
Metadata.sq
Event.sq
```

## 8.1 SQLite 的定位

VFS SQLite 保存的是：

> **VFS Control Plane State**

例如：

```text
Node ID
Node 当前逻辑路径
Mount 配置
Metadata
Event
```

它与普通 Storage 中的文件内容不是同一类数据。

关系：

```text
Physical Storage
→ 实际 Context 文件

SQLite
→ VFS 管理这些文件所需要的关键状态
```

因此 VFS SQLite 不是缓存，也不是可以随意删除后重新生成的数据。

Node Identity、Metadata 等状态丢失可能直接影响 VFS 的正确性。

---

# 9. `vfs/runtime`

`vfs/runtime` 是 VFS 的 SDK 入口。

它的定位是：

> **VFS SDK Entry Point**

同时也是：

> **VFS Composition Root**

它回答：

> **如何获得一个真正可以直接使用的 VFS？**

## 9.1 Runtime 的职责

Runtime 负责组装文件 SDK；后续同步扩展也通过配置在此接入，Context System 不自行创建 Worker 或调用 Git CLI。同步实现按 E1 交付，不进入 Core 文件事务。

Runtime 负责组装：

```text
vfs-core

+

storage

+

persistence
```

并隐藏内部实现细节。

调用方不需要知道：

```text
NodeRepository
EventRepository
MountRepository

OpenDalStorage
StorageProvider

SQLDelight Driver

MountResolver
EventDispatcher
```

这些对象如何创建和连接。

## 9.2 Runtime 对外暴露

Runtime 主要提供：

```text
AlcyoneVfs
VfsConfig
MountConfig
StorageConfig
```

最终调用方式应尽可能简单。

例如：

```kotlin
val vfs = AlcyoneVfs.create {
    state {
        sqlite("./data/vfs.db")
    }

    mount("/memory") {
        local("./data/memory")
    }

    mount("/resources") {
        local("./data/resources")
    }

    mount("/resources/medical") {
        webdav {
            endpoint = "https://dav.example.com"
            root = "/medical"
        }
    }
}
```

然后调用方直接使用：

```kotlin
vfs.read(...)
vfs.write(...)
vfs.stat(...)
vfs.list(...)
vfs.move(...)
vfs.delete(...)
```

Context System 不需要理解 VFS 内部实现。

## 9.3 Runtime 与 API 的区别

两者职责明确区分：

```text
vfs-api
→ 定义 VFS 能做什么

vfs-runtime
→ 提供一个已经组装完成、可以直接使用的 VFS
```

典型关系：

```kotlin
val vfs: Vfs = AlcyoneVfs.create(config)
```

其中：

```text
Vfs
→ 来自 vfs-api

AlcyoneVfs
→ 来自 vfs-runtime
```

原则：

> **API 是契约，Runtime 是 SDK 入口。**

---

# 10. `integration-tests/vfs`

该目录用于 VFS 的跨模块集成测试。

普通单元测试仍然放在各模块自身：

```text
vfs/api/src/test/
vfs/core/src/test/
vfs/storage/src/test/
vfs/persistence/src/test/
vfs/runtime/src/test/
```

而：

```text
integration-tests/vfs/
```

用于测试真正组合后的完整 VFS。

例如：

```text
AlcyoneVfs
    ↓
DefaultVfs
    ↓
Mount Resolver
    ↓
存储能力检查
    ↓
OpenDAL
    ↓
Local FS
```

同时使用真实 SQLite：

```text
VFS
 ↓
SQLDelight
 ↓
SQLite
```

## 10.1 集成测试范围

第一阶段至少覆盖：

```text
读取文件
创建文件
写入文件
获取 Node 信息
Node 懒注册
目录 List
移动 / 重命名
跨 Mount Move
删除
Metadata
Event
Local FS
WebDAV
```

尤其应该验证完整访问链路：

```text
URI
 ↓
Mount / StoragePath
 ↓
存储约束
 ↓
Storage
```

避免单独组件测试通过，但实际组合后的行为不符合 VFS 设计。

---

# 11. 模块依赖关系

VFS 内部总体依赖关系：

```text
                 vfs-api
                    ▲
                    │
                 vfs-core
                ▲        ▲
                │        │
                │        │
 storage                persistence
                ▲        ▲
                └────┬───┘
                     │
                 vfs-runtime
```

从职责角度理解：

```text
vfs-api
→ 公共契约

vfs-core
→ 核心规则

storage
→ Storage 实现

persistence
→ Repository 实现

vfs-runtime
→ 运行时组装
```

调用方最终只需要通过 Runtime 获取完整实例：

```text
Context System
      │
      ▼
  AlcyoneVfs
      │
      ▼
     Vfs
```

Runtime 以下的内部组件原则上不应该泄露给 Context System。

---

# 12. 当前阶段项目边界

当前第一阶段只需要建立：

```text
alcyone-context/
├── docs/
├── vfs/
│   ├── api/
│   ├── core/
│   ├── storage/
│   ├── persistence/
│   └── runtime/
└── integration-tests/
    └── vfs/
```

暂时不创建：

```text
memory/
retrieval/
context/
http/
server/
```

等 VFS 核心能力稳定后，再根据 Alcyone Personal Context System 的后续开发逐步增加。

原则：

> **只创建当前已经有明确职责和开发需求的模块，不为未来假想需求提前建立空架构。**

---

# 13. 总结

当前 `alcyone-context` 的第一阶段本质上是：

```text
              AlcyoneVfs
                  │
             vfs-runtime
                  │
          ┌───────┴───────┐
          │               │
      vfs-core         vfs-api
          │
     ┌────┴─────┐
     │          │
     ▼          ▼
 OpenDAL     SQLDelight
     │          │
 Storage      SQLite
```

各模块一句话定位：

```text
docs
→ 保存设计

vfs-api
→ 定义 VFS 能做什么

vfs-core
→ 实现 VFS 的核心规则

vfs-storage
→ 访问实际文件

vfs-persistence
→ 保存 VFS 关键状态

vfs-runtime
→ 提供可直接使用的 AlcyoneVfs SDK

integration-tests/vfs
→ 验证完整 VFS 是否真正工作
```

最终目标是：

> **VFS 内部可以保持完整的模块化设计，但 Context System 只需要面对简单稳定的 `AlcyoneVfs` 与 `Vfs` 接口。**
