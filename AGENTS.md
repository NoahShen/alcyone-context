# AGENTS.md

## 本次修改（2026-09-29，common 模块与格式化工具）

- 新增 `common/` 公共工具库模块（UUIDv7），更新模块职责表、依赖方向与基础设施边界。涉及“模块职责与依赖”。
- 增加代码格式与版本集中管理的强制要求。涉及“开发与验证”。
- 更新 T06 状态为独立验收通过后的修订轮。涉及“设计文档与阅读顺序”。

本文为在本仓库工作的编码 Agent 提供项目背景、设计依据与开发约束，适用于整个仓库。文档默认使用中文，代码标识符使用英文。

## 项目定位与当前阶段

Alcyone Context 是 Personal Agent Framework 的长期 Context 基础设施，负责跨 Round、Agent、Harness 保存和提供长期上下文。整体方向是 **Agent-native Virtual File System + Memory Governance + Retrieval Optimization**。

- Resources 是普通长期文件，通过 VFS 管理。
- Memory 是需要语义治理和生命周期管理的长期认知，未来建立在 VFS 之上。
- Agent Framework 负责执行任务；Context System 负责 Agent 身份、目录分配、共享范围与访问隔离；VFS 提供统一文件访问，不理解 Agent 归属。

当前第一阶段只建设可独立使用的 VFS Kotlin Library。不要提前创建 `memory/`、`retrieval/`、`context/`、`http/`、`server/` 等后续模块。

仓库已有 T05 的六模块根构建骨架和项目内工具链，以及 T06 已实现的公共 API 类型（`VfsUri`、`VfsPath`、`Vfs` 接口等）；`tools/validation/t04/` 仍仅用于独立依赖验证。模块构建可用不代表对应业务能力已经实现；开始任务前检查实际文件。

## 设计文档与阅读顺序

1. [项目搭建说明 v0.1](<docs/Alcyone Context 项目搭建说明 v0.1.md>)：当前工程目录、模块职责、依赖方向与阶段边界。
2. [Personal Context System 设计说明 v0.3](<docs/Alcyone Personal Context System 设计说明 v0.3.md>)：整体定位及 Memory、Resources、Retrieval 的关系。
3. [VFS 技术设计 v0.2](docs/vfs/Alcyone_Virtual_File_System_VFS_技术设计_v0.2.md)：技术选型、核心模型与架构约束。
4. [VFS 核心用例 v0.2](docs/vfs/Alcyone_VFS_核心用例文档_v0.2.md)：操作流程、异常行为、状态变化与事件。
5. [T01 公共契约基线](docs/vfs/VFS_公共契约草案_v0.1.md)：2026-09-28 用户已审阅通过，文件名保留以兼容既有链接。
6. [T02 路径与文件操作语义](docs/vfs/VFS_路径与文件操作语义_v0.1.md)：2026-09-28 用户已审阅通过，包含路径、Mount、列表、覆盖及递归操作规则与验收样例。
7. [T03 状态与事务设计](docs/vfs/VFS_状态与恢复设计_v0.1.md)：2026-09-28 用户已审阅通过，明确存储职责、提交边界与失败报告。
8. [T04 依赖与能力验证](docs/vfs/VFS_依赖与能力验证_v0.1.md)：2026-09-28 用户已确认；版本组合在 macOS arm64 最小验证通过，首版默认文件限额 16 MiB（可配置）；记录后端差异与后续验收范围。

9. [T05 工程初始化与验收](docs/tasks/m1-t05/T05_工程初始化与验收.md)：开发 Agent 的阶段任务及 A01～A08 验收标准；同目录含使用说明与验收记录。

10. [T06 公共契约实现与验收](docs/tasks/m1-t06/T06_公共契约实现与验收.md)：API 类型、路径校验、事件与异常的实现范围及 A01～A08 验收标准；状态 DONE。

开发任务与进度统一记录在 [开发计划与进度](docs/开发计划与进度.md)。开始开发前核实任务依赖；完成后更新状态、负责人和验收证据。计划中的待定决策与建议不代表已冻结契约。

处理文档差异时：

- 工程结构采用搭建说明中的 `vfs/api`、`vfs/core` 等划分，技术设计已同步；独立发布策略尚未确定。
- VFS 公共接口以已通过的 T01 为设计基线；其明确交给后续任务的细节仍未冻结。T02 具体操作规则也已通过审阅，优先于早期总体文档中未展开的示意；T03 简化后的事务与失败报告规则也已确认，操作恢复和可靠消费延后。
- T01 第三轮已确认：VFS API 仅接收文件操作参数；Context System 负责 Agent 目录分配与访问范围。保留 VfsEntry / NodeInfo；详细接口见已审阅通过的 [公共契约基线](docs/vfs/VFS_公共契约草案_v0.1.md)。
- `docs/memory(旧文档-Alcyone仅作为memory系统的设计)/` 是旧 Memory-only 系统的历史资料。其 TypeScript 技术栈、HTTP API、Memory ID、写入顺序及 SQLite 可重建等规则，不适用于当前 VFS。
- Git Sync 在技术设计中列为第一阶段能力，但最新搭建说明未安排对应模块。它始终属于外围 Consumer；按具体任务开展，不因早期目录示意就将其加入 Core。
- 当前架构与主要流程见 [架构与工作流程图](docs/vfs/架构与工作流程图.md)，与公共契约同步维护。
- 对尚未确定的行为，在相关设计文档中记录决策或待定项，避免把推测写成既定规范。

## 技术基线

| 领域 | 设计选型 |
| --- | --- |
| 语言与运行时 | Kotlin / JVM，JDK 21+ |
| 运行形态 | 纯 Kotlin Library，Library First |
| 异步 | Kotlin Coroutines；事件使用 Flow / Channel |
| 文件存储 | Apache OpenDAL Java Binding；首阶段 Local FS、WebDAV |
| VFS 状态持久化 | 独立 SQLite 数据库 + SQLDelight |
| Node / Event ID | UUIDv7 |
| 序列化 | 优先 Kotlin Serialization |
| 日志 | SLF4J API，具体日志实现由宿主决定 |

Core 不依赖 Micronaut、Spring Boot 或 Ktor Server。不引入独立 MQ、通用 Storage Watch、完整 Secret System 或面向海量 Mount 的复杂索引。

OpenDAL 以 JVM 依赖嵌入运行，无需独立服务；构建发布时需处理目标 OS / CPU 对应的 native classifier，不将 JNI 细节放入领域模型。已确认的版本组合和最小验证命令见 T04；正式工程在 T05 配置 Wrapper 和依赖。

正式开发使用项目内独立工具链：JDK、Gradle 发行包及依赖缓存保存在仓库内忽略目录，由开发入口对子进程设置环境；启动 JVM、Daemon、编译和测试都使用项目 JDK。不得改系统默认 JDK、全局 Gradle、用户 shell 启动文件或全局环境配置；不依赖 T04 的临时目录，具体交付要求见 T05。

## 模块职责与依赖

| 计划目录 | 职责 |
| --- | --- |
| `docs/` | 设计与决策 |
| `common/` | 与 `vfs/` 平级的公共工具库，供 `vfs`、`memory` 等业务模块共用；当前提供 UUIDv7 生成与校验 |
| `vfs/api/` | 公共接口和领域类型，如 `Vfs`、`VfsUri`、`VfsPath`、`NodeInfo`、选项、事件与异常 |
| `vfs/core/` | URI、Node、Metadata、Mount、Event 规则及文件操作编排；定义 Storage / Repository Port |
| `vfs/storage-opendal/` | 实现 Core 的 Storage Port，处理 OpenDAL 调用、后端能力与错误转换 |
| `vfs/persistence-sqldelight/` | 实现 Core 的 Repository Port，保存 Node、Mount、Metadata、Event Log |
| `vfs/runtime/` | SDK 入口与依赖组装，提供 `AlcyoneVfs`、配置及完整 VFS 实例 |
| `integration-tests/vfs/` | 组合 Runtime、Core、真实 SQLite 与 Storage 的跨模块测试 |

依赖方向：`common` 不依赖任何业务模块；`core → api → common`；两个基础设施模块依赖 Core 定义的 Port；Runtime 组装 API、Core 和基础设施实现。Core 不反向依赖具体 Adapter。

- API 不暴露 OpenDAL、SQLDelight、SQLite 或 Repository 实现。
- `common` 只放与业务无关的工具；它不知道 VFS 错误契约，抛自身异常，由 `vfs/api` 在边界转换。业务规则、协议常量和领域校验不放在 `common`。
- Core 中的 Storage Port 仅表达业务所需能力，不另建一套通用存储后端框架；后端差异交给 OpenDAL。
- Runtime 隐藏 Repository、Driver、Operator 和各 Manager 的创建过程。
- SQLDelight Schema 放在持久化模块的 `src/main/sqldelight/`；单元测试放在所属模块的 `src/test/`。

## VFS 必须保持的语义

### 路径、身份与目录

- 顶级命名空间为 `alcyone://memory/` 和 `alcyone://resources/`。
- 区分 `VfsUri`、`VfsPath` 和 `StoragePath`。例如 `alcyone://resources/medical/ct/a.dcm` 对应逻辑路径 `/resources/medical/ct/a.dcm`；挂载 `/resources/medical/` 后，Storage Path 为 `ct/a.dcm`。
- URI 表示位置，Node ID 表示身份。移动、重命名和跨 Mount 移动均保持同一个 Node ID。
- Metadata 等逻辑信息绑定 Node ID；Node Registry 优先按全局 `VfsPath` 定位 Node，调用方无需预先知道 Mount。
- 已确认保留 `VfsEntry` 和 `NodeInfo`：`list` 返回 Node ID 可空的 `VfsEntry`，不批量注册；`NodeInfo` 必须有稳定 Node ID。
- 文件和目录都可成为 Node。目录内容由 Storage `list` 推导，不在数据库维护完整目录树，不强制注册所有目录。
- 普通读取不要求 Node 已注册，默认不修改 Node、Metadata，也不产生变更事件。
- `stat(uri)` 可确认底层资源存在后懒注册 UUIDv7 Node；T02 定义的纯虚拟目录可据配置注册且无物理映射；`getNode(id)` 只查已注册的逻辑记录。当前有效 Node 的 `vfs_path` 建议保持唯一。

### 文件访问与存储约束

```text
VfsUri / 参数校验 → VfsPath → Mount / StoragePath → 存储约束检查 → Storage I/O
```

- 文件、Metadata、事件和 Runtime 生命周期接口统一面向 Context System，直接接收操作参数。
- Context System 将 Agent 请求限定到分配的目录后再调用 VFS；VFS 不按调用者过滤 list，也不把目录名解释为 Agent。Agent 目录范围由上层落实。
- VFS 校验 URI、路径段边界、物理挂载根、后端能力和覆盖规则；这类规则不依赖调用者。
- Node Registry 按 VfsPath 维护资源身份，按需参与查询 / 变更。
- 已注册 Node 的 Metadata 管理只操作逻辑状态，不需要 Mount 或 Storage。
- 首版不增加可配置只读挂载；后端拒绝访问或凭据错误须明确返回。Metadata 存储在独立状态库中。

### 写入、移动与失败处理

- 创建 / 写入先完成 Storage 操作，再创建或更新 Node 状态；Storage 失败时不更新成功状态、不发布成功事件。
- 同 Mount 移动优先使用原生 rename / move，并检查后端能力。
- 跨 Mount 移动遵循读取源 → 写入目标 → 确认成功 → 删除源，再更新同一个 Node 的逻辑路径与物理映射。
- 未注册源资源若允许移动，应在确认资源存在后建立 Node Identity，保持移动前后的身份连续性。
- 删除成功后按确定的删除策略处理 Node、Metadata。
- Storage 成功而逻辑状态更新失败、跨 Mount 目标写入成功而源删除失败等情况，首版返回错误和实际 effect，不自动补偿或修复；中断与重启恢复列为后续 E04，不能假定 SQLite 事务可以回滚外部 Storage。

### 状态与事件

- VFS SQLite 保存 Control Plane 关键状态，包含 Node Identity、路径映射、Mount、Metadata、Event Log。它不是缓存，不能随意删除重建，也不与未来 Memory 查询库共享数据库或 Repository。
- 成功的状态变更产生标准 VFS Event，包括创建、写入、移动、删除、Metadata 更新；首版不对外发布 `NODE_REGISTERED`。
- 实时分发使用 Coroutine Flow / Channel；首版保留 Event Log，不实现持久化消费进度或重启重放，可靠消费列为后续 E05。
- 状态与事件在同一事务中提交，提交后通知；首版不承诺可靠投递，不从示意图推断未实现的保证。
- Git Sync、Index、Audit 属于外围 Event Consumer，其失败不改变已成功的 VFS 操作结果。
- Git 不是 Storage Backend；需要 Git Sync 时优先使用 Git CLI。同步扩展由 SDK / Runtime 通过配置接入，普通文件调用不需要操作 Worker；同步失败可重试，不回滚已成功文件操作。Git Sync 仍按 E1 单独交付，不承诺通用双向同步。
- 第一阶段仅覆盖经由 VFS 发起的变更事件，不保证发现绕过 VFS 的外部文件变化。

## 开发与验证

**代码格式：写完代码必须跑 `./scripts/dev gradle spotlessApply` 再交付。`spotlessCheck` 已挂进 `check`，格式不过 `./scripts/dev gradle build` 会直接失败。依赖与插件版本在 `gradle/libs.versions.toml` 声明；JDK 与 Gradle 发行包的版本、下载地址和 SHA-256 在 `gradle/toolchain.versions` 声明。构建文件与脚本不得再硬编码版本号。**

**任务文档目录：每个开发任务在 `docs/tasks/<里程碑>-<任务ID>/` 下建立独立目录，存放该任务的任务说明、使用说明和验收记录，例如 `docs/tasks/m1-t05/`。不要把不同任务的文档平铺在 `docs/tasks/` 根目录。**

**文档更新规则：每次修改文档，必须在被修改文档的前面部分增加或更新“本次修改”说明，列出本次主要修改内容及涉及的章节；仅在文末记录或仅在交付消息中说明不满足要求。适用于设计文档、进度文档和本文件。**

**后续设计文档保持简洁：每项以 2～3 句话说明规则，配一个简单例子。沿用已确定的架构和核心用例，不重复展开；字段、SQL 和锁可在开发中明确，只回写影响契约的重要结论。**

**协作分工：用户将实现任务交给专门的开发 Agent，当前设计 / 验收对话仅维护文档、审查结果并运行必要验收，不添加或修改实现代码。开发 Agent 完成自测后标为 IN_REVIEW，独立验收通过后才标为 DONE；验收问题交回开发 Agent 修复，不自动继续下一任务。**

开始实现前，先阅读对应设计和用例，检查已有代码、构建配置及工作区改动。按任务需要建立模块，不提前搭建后续空架构。变更公共契约、模块边界或持久化语义时，同步更新相关设计文档。

路径、覆盖、目录递归和嵌套 Mount 按已通过的 T02 实现。后端实际能力由 T04 验证，状态与事务按已确认的 T03 推进；操作中断与重启恢复不纳入首版；细节可在对应开发任务中明确。不要将设计示例中的省略视为允许跳过这些行为。

实现后的测试按变更范围选择，重点验证：

- URI / Path 转换、Mount 最长前缀匹配与路径边界。
- 路径逃逸、后端能力和错误转换；已知无效的变更在副作用之前拒绝。
- 创建、写入、读取、`stat` 懒注册、目录列表、移动、删除与 Metadata 管理。
- 同 Mount 和跨 Mount 移动的 Node ID 连续性，以及目标写入失败、源删除失败和 Registry 更新失败。
- 正常关闭后重启可读取已提交的 SQLite 状态，不要求修复中断操作，事件对应成功变更，Consumer 失败不影响已完成操作。
- 通过 Runtime 组合真实 SQLite 与 Local FS / WebDAV 的集成行为。

T05 构建骨架已复核通过：使用 `./scripts/dev bootstrap` 准备项目工具链，`./scripts/dev gradle clean build --console=plain` 构建；完整命令及证据见 T05 使用说明与验收记录。当前测试均为 NO-SOURCE，不得声称 SDK 业务测试已通过。仅修改文档时检查路径引用与设计一致性即可；交付时说明修改内容、验证结果及未验证事项。
