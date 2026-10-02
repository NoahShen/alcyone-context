# T11 SQLDelight Schema、Repository、事务与数据库生命周期开发及验收

## 本次修改（2026-10-02，T11 独立验收收口）

- 修复提交 `6fe4878` / 补充 `d5b33f8` 已独立复核通过，状态改为 **DONE**，纠正原状态段“未提交”；227 个正式测试通过，T11 共新增 47 个。涉及状态说明，详见 [架构与技术复核](T11_架构与技术复核.md) 第 7 节。

## 本次修改（2026-10-02，实现完成，状态 IN_REVIEW）

- 交付 `vfs/persistence` 状态库：四表 SQLDelight Schema、数据库级路径唯一约束、T07 四个 Repository 实现、`UnitOfWork` 事务、数据库打开 / 关闭 / 迁移生命周期，全部用真实 SQLite 验证。新增 43 个测试（A01～A06 全覆盖），`./scripts/check` 全绿，测试总数 180 → **223**。同目录补齐 [使用说明](T11_使用说明.md) 与[验收记录](T11_验收记录.md)。涉及全文。
- **模块更名**：正文与命令中出现的 `vfs/persistence-sqldelight` 均为旧名，模块已更名为 `vfs/persistence`（Kotlin 包名 `com.github.noahshen.alcyone.context.vfs.persistence.sqldelight` 不变），本任务文档文件名保持不变以兼容既有链接。涉及第 1、4、6 节。
- 本阶段确定的实现要点：当前有效路径唯一由部分唯一索引 `node_active_path ... WHERE deleted_at IS NULL` 保证、`register` 竞争复用；时间戳统一 epoch 毫秒；子树查询用 `instr` 精确前缀规避 `LIKE` 通配符与大小写不敏感；事务用整库单连接 + 显式 `BEGIN IMMEDIATE`，不套 `runBlocking`。涉及第 2.1～2.5 节与验收记录第 3 节。
- 保留边界：并发 `inTransaction` 不支持（调用方串行）、跨进程独占检测不做、Mount 写入属 T18、事件分发属 T14。涉及第 2.3、3 节。

## 本次修改（2026-10-01，任务计划初稿）

- 定义 `vfs/persistence-sqldelight` 的开发内容、边界和三个实施步骤：Schema 与唯一约束、Repository 实现、事务与数据库生命周期。涉及第 1～4 节。
- 给出 A01～A07 验收标准，全部用真实 SQLite 验证（内存 + 临时文件），不引入 mock driver。涉及第 5～6 节。

状态：**DONE。** `6fe4878` / `d5b33f8` 第二轮独立复核通过，R1～R4 关闭；前置 T03、T07 已完成，不自动开始 T12。

## 1. 目标与依据

在 `vfs/persistence` 实现状态库基础设施：SQLDelight Schema（Node / Metadata / Event / Mount 四张表）、T07 四个 Repository 接口的实现、`UnitOfWork` 事务实现与数据库打开 / 关闭 / 迁移生命周期。本轮是纯基础设施：不编排文件操作、不分发事件、不读取配置。

依据：[T03 状态与恢复设计](../../vfs/VFS_状态与恢复设计_v0.1.md) 第 1～4、7～9 节，T07 的 `Repositories` / `RepositoryModels` / `UnitOfWork` 接口，T04 已验证的 SQLDelight 2.1.0 + JDBC `sqlite-driver` 组合（版本已在 `gradle/libs.versions.toml` 声明）。状态库只服务 VFS，不与未来 Memory 查询库共享（AGENTS 约束）。

例：`register` 在同一路径上的两次并发调用只有一个记录生效，另一个复用既有记录——这由数据库唯一约束保证，不靠调用方先查后插。

## 2. 开发内容

### 2.1 Schema 与唯一约束

在模块 `src/main/sqldelight/` 定义四张表：Node（含删除标记）、Metadata（Node ID 主键 + 序列化载荷）、Event（按 `EventRecord` 字段）、Mount（路径 + storageKey）。当前有效路径的唯一性必须由**数据库级约束**保证（部分唯一索引或等价方案由实现决定）；`markDeleted` 标记后路径占用立即释放，可被新 Node 复用，旧 ID 查询不再返回。表名与字段类型由开发 Agent 决定，ID 存 TEXT、时间戳的编码在实现中明确。

例：删除 `/notes/a` 的 Node 后，`/notes/a` 上注册新文件成功且获得新 ID；旧 ID 的 `findById` 返回 null。

### 2.2 Repository 实现

实现 T07 的四个接口（R1～R11），语义以接口 Kdoc 为准。两个已知的 SQLite 陷阱必须处理：**大小写敏感**（SQLite `LIKE` 对 ASCII 字母不区分大小写，而 VfsPath 区分大小写，需 `PRAGMA case_sensitive_like` 或等值 + 前缀比较）；**子树查询的通配符**（`_` 是合法路径字符且是 `LIKE` 通配符，`%` 已被 T09 禁止，转义或改用前缀长度方案由实现决定）。`findSubtree` 严格按完整段边界（R7 / G10）。

例：`findSubtree(/notes/a)` 返回 `/notes/a` 与 `/notes/a/b`，不返回 `/notes/abc`；`/A` 与 `/a` 是两个不同路径。

### 2.3 事务与 UnitOfWork

实现 `UnitOfWork`：回调正常返回即提交，抛出异常整体回滚（Node / Metadata / Event 同一事务）；`TransactionScope` 聚合三个事务内视图，逃逸到回调外使用抛 `IllegalStateException`，不退化为自动提交；`CancellationException` 原样传播不包装。接口是 `suspend`，JDBC 阻塞调用用 IO 调度器包裹；首版单连接单 Runtime（T03 §7），跨进程独占检测不属本轮。

例：事务内先 `register` 再 `append` 事件，回调抛异常后两者都不存在；重开数据库确认无残留。

### 2.4 数据库生命周期与迁移

提供数据库工厂 / 管理入口：打开（内存或文件）、按版本执行 Schema 创建与迁移、关闭释放资源。迁移使用版本化机制（如 SQLDelight 生成的 `Schema.migrate`），**不删除重建**（T03 §8）；首版为基线 schema，不强制附带历史迁移文件，但迁移入口与版本号管理方式必须明确并写入使用说明。重启只加载已提交状态，不扫描或续做中断操作（T03 §9）。

例：文件数据库正常写入并关闭后，重新打开能查到原 Node 与事件记录。

### 2.5 序列化与错误映射

`NodeMetadata` 直接以 `JsonObject` 文本序列化存 Metadata 表，不需要给 API 类型加注解。数据库操作失败统一映射 `STATE_ERROR`，不吞异常、不猜测原因；Repository 不产生 Storage / Mount / 路由类错误。模块对外只暴露 Core 已有类型，SQLDelight / JDBC 类型不泄漏到 `vfs/core` 与 `vfs/api`（依赖方向 persistence → core）。

例：唯一约束冲突在 `register` 语义内按 R3 复用处理，其余数据库故障对外是 `STATE_ERROR`。

## 3. 交付范围与后续归属

| 本轮交付 | 后续处理 |
| --- | --- |
| 四张表的 Schema、唯一约束、四个 Repository 实现、`UnitOfWork`、数据库生命周期与迁移入口 | T13 Node Registry 懒注册与并发复用的编排；T14 事件产生与分发 |
| Mount 表的 Schema 与读取（`MountRepository.list`） | T18 首次启动写入 Mount 映射与"已有映射被修改时拒绝"的校验（T03 §8）、多 Runtime 独占检测 |
| 模块内真实 SQLite 测试（内存 + 临时文件） | T19 通过 Runtime 组合真实 SQLite 与 Local FS 的集成验证 |

不修改 T07 接口与 API 类型（2.5 说明无需兼容性补充）；不实现事件分发、消费进度、操作恢复日志；不实现文件操作编排。测试放在 `vfs/persistence/src/test/`，用真实 SQLite 驱动，不为验收额外构建内存假驱动。同目录补齐 `T11_使用说明.md` 与 `T11_验收记录.md`。

## 4. 实施顺序

| 步骤 | 工作 | 完成标志 |
| --- | --- | --- |
| S1 Schema 与约束 | 建表、唯一约束、删除标记与路径复用、数据库工厂 | 唯一约束、注册竞争复用、路径复用测试通过 |
| S2 Repository 与事务 | R1～R11 实现、`UnitOfWork` 提交 / 回滚、大小写与段边界 | 查询语义、事务原子性、事务逃逸与取消传播测试通过 |
| S3 生命周期与验证 | 打开 / 关闭 / 迁移入口、重启持久化、统一检查与文档 | 重启持久化测试通过，A01～A07 自测完成，提交后 IN_REVIEW |

三个步骤属于同一任务，不设额外逐步确认门。查询先直查不过度优化；无性能证据不引入连接池或缓存层。

## 5. 验收标准

| 编号 | 必须满足的结果 | 核心场景 / 证据 |
| --- | --- | --- |
| A01 Schema 与唯一约束 | 有效路径唯一由数据库级约束保证；标记删除释放路径占用；注册竞争复用既有记录 | 同路径二次注册复用；删除后新注册成功且新 ID；并发竞争不产生双记录 |
| A02 查询与变更语义 | R1～R11 按 T07 Kdoc 行为正确；大小写敏感；子树按完整段边界 | `markDeleted` 后三类查询不返回；`findSubtree(/notes/a)` 不含 `/notes/abc`；`/A` ≠ `/a`；`findByPaths` 批量正确 |
| A03 事务原子性 | 正常返回提交、异常整体回滚；事务视图逃逸抛 `IllegalStateException`；`CancellationException` 原样传播 | 同事务 Node + Metadata + Event 全有或全无；回滚后重开库无残留 |
| A04 生命周期与持久化 | 打开→操作→关闭→重开，已提交状态完整可读；回滚状态不存在；关闭释放资源 | 真实 SQLite 临时文件验证；close 后无连接 / 句柄泄漏（测试与代码审查） |
| A05 迁移与版本管理 | 版本化迁移入口明确、行为可说明，不删除重建；首版为基线 schema | 使用说明写清迁移入口与版本号管理；打开既有库不丢数据 |
| A06 模块边界与错误映射 | 依赖方向 persistence → core；SQLDelight / JDBC 类型不泄漏；数据库失败映射 `STATE_ERROR` 不吞异常；不依赖 Storage / Router / Guard | 代码审查；构造数据库故障验证错误码与消息 |
| A07 构建与交付 | 新增测试真实执行（真实 SQLite 驱动）、原有测试继续通过、格式检查通过；文档和状态准确 | `./scripts/check` 输出、JUnit 数量与失败 / 错误 / 跳过统计、变更清单；远端结果如实记录 |

本轮验证的是状态库自身的完整性：事务原子性只覆盖 SQLite 内部，不承诺回滚外部 Storage 操作（T03 §3）；事件只保证持久化，分发与可靠投递属 T14 / E05。

## 6. 验证与开发指令

```sh
./scripts/dev gradle spotlessApply
./scripts/dev gradle :vfs:persistence:test --console=plain
./scripts/check
```

沿用项目内 Java / Gradle，依赖齐全且离线时可加 `--offline`。开始时标为 IN_PROGRESS，完成实现、自测和提交后标为 IN_REVIEW（状态由复核方标 DONE）；独立验收通过后才收口，不自动继续 T12。

> 按 AGENTS.md、开发计划与本文件执行 T11。在 `vfs/persistence` 实现四张表的 Schema 与唯一约束、T07 四个 Repository、`UnitOfWork` 事务与数据库生命周期，全部用真实 SQLite 验证。只实现本任务基础设施，不实现事件分发、Mount 写入编排或文件操作；完成 A01～A07，补充本目录使用说明和验收记录，提交后等待独立复核。
