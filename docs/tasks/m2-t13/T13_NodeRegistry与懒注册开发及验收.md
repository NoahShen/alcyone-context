# T13 Node Registry 与懒注册开发及验收

## 本次修改（2026-10-03，复核 R1 / R2 / R3 修复，仍 IN_REVIEW）

- R1：虚拟回退多认「祖先在磁盘上是文件」这一类遮蔽（`TYPE_MISMATCH`）；挂载根 stat 返回非目录时 `TYPE_MISMATCH` 拒绝，且拒绝先于写状态。R2：Kdoc / 使用说明 / 交付文档三处示例统一为同一段并写清约定边界。R3：证据口径分层、提交号 `a83c375` 回填、`VfsUri.of` 措辞订正。新增 6 个用例（含采纳建议的 `StateBoundaryTest` 2 例），全仓 **413 → 419**。涉及第 2.3～2.5 节与状态行。

## 本次修改（2026-10-03，Lead 验收后修复两处，仍 IN_REVIEW）

- A04 关键用例的竞态弱断言改成**正向检测违规**（事务未提交却拿到返回值 = 边界失效），并用 `withLock` 空操作变异验证咬住；补齐「状态库失败 → `STATE_ERROR` 且 cause 保留」用例。全仓 **412 → 413**，`./scripts/check` 全绿。涉及第 2.5、5 节与状态行。

## 本次修改（2026-10-03，实现交付：状态改 IN_REVIEW）

- 实现完成并自测通过：新增 `core.registry`（Node Registry）与 `core.state`（共享串行边界 `StateBoundary`），Core 单测 +38、api +1、真实组合测试 10，全仓 **363 → 413**，`./scripts/check` 全绿。状态由 TODO 改为 **IN_REVIEW**，待独立复核。涉及第 2、4、5 节与状态行。
- 同目录补 [使用说明](T13_使用说明.md)、[验收记录](T13_验收记录.md)、[交付文档](T13_交付文档.md)。

## 本次修改（2026-10-03，任务初稿）

- 定义 Node Registry 的查询、确认资源与懒注册、虚拟目录、竞争复用及串行边界，列出实施顺序和 A01～A08 验收标准。涉及第 1～5 节；沿用 T01～T03，不实现完整 DefaultVfs。

状态：**IN_REVIEW，实现已交付，待独立复核**。前置 T09 / T10 / T11 / T12 均 DONE；正式测试基线 **363 → 419**，其中 core 149（原 106）、api 65、integration-tests/vfs 12。

## 1. 目标与依据

在 `vfs/core` 实现 Node Registry，供 T15 的 stat、T17 的 Metadata 和后续移动编排复用：把逻辑位置关联到稳定 Node ID，按需确认资源并注册。复用 MountRouter、Storage Port、NodeRepository 与已有 UUIDv7 工具，Core 不引用 SQLite、SQLDelight 或 OpenDAL 实现。

依据：[T01 公共契约](../../vfs/VFS_公共契约草案_v0.1.md) 第 5～7 节、[T02 路径与操作语义](../../vfs/VFS_路径与文件操作语义_v0.1.md) 第 3 / 5 节、[T03 状态与事务](../../vfs/VFS_状态与恢复设计_v0.1.md)，以及 [T11 复核](../m2-t11/T11_架构与技术复核.md) 的单连接与取消边界。开发前核对当前 `Repositories.kt`、`RepositoryModels.kt`、`MountRouter.kt` 与 Storage Port，不再另造一套 Node 模型。

例：磁盘上已有 `a.txt`，普通 read 不创建逻辑记录；首次 stat 确认它存在后生成 ID，再次查询返回同一个 ID。

## 2. 开发内容

### 2.1 查询入口与依赖

提供按 Node ID 查询、按路径确认并取得 Node 的入口，支持 T01 的 includeStorage 行为；类名及内部返回组合由开发确定，T15 直接复用，不复制注册算法。Storage 实例通过已配置的 storageKey 查找，使用简单映射或函数即可，不建设插件容器；无匹配 Mount 的普通路径报 MOUNT_NOT_FOUND，命中 Mount 却缺 Storage 实例属于装配错误，需明确报告而非伪装资源不存在。

例：`/resources/docs/a.txt` 命中 `/resources/docs` 时，只向对应 Storage 查询 `a.txt`，子 Mount 失败不得退回父后端。

### 2.2 注册、ID 与逻辑查询

新 Node 由 Core 调用已有 UUIDv7 工具生成 ID，并设置逻辑注册 / 更新时间；Repository.register 返回的记录才是最终身份，不能返回本地候选 ID。已有 Node 查询不重建 ID、不更新逻辑时间或 Metadata；getNode(id) 只查有效逻辑记录，未知或已删除 ID 报 NOT_FOUND，storage 为 null。

例：两个请求为同一路径准备不同 ID，Repository 复用先注册记录时，两者必须返回同一个生效 ID；旧记录已标删除后重新注册应使用新 ID。

### 2.3 stat 与底层事实

默认 stat 确认资源并取得属性：普通路径即使已有 Registry 记录，底层缺失也报 NOT_FOUND；实际类型与已有记录冲突时报 CONFLICT，不在查询中换 ID、删除 Metadata 或自动修复记录。includeStorage=false 对已有 Node 完全走逻辑查询，对未注册物理资源仍须先 Storage.stat 确认存在，但返回的 storage 保持 null；物理属性不覆盖逻辑时间字段。

例：已注册文件被外部删除，默认 stat 失败，getNode 与 includeStorage=false 仍可查询旧逻辑记录；同路径外部删除再重建且类型相同，不承诺识别新一代文件。

### 2.4 配置目录与虚拟目录

按 T02 与 MountRouter 的配置结构判断目录，不能把任意无 Mount 路径当成虚拟目录；纯虚拟目录可注册 DIRECTORY、physical=false，storage=null，不能为此创建物理目录。配置祖先同时对应真实目录时可取得实际属性，同名物理文件按 T02 被逻辑目录遮蔽；恰好是 Mount 根则必须确认后端目录，后端失败不能降级为虚拟成功（已有 Node 的逻辑查询例外）。

例：仅挂载 `/resources/medical/ct`，`/resources/medical` 可注册为虚拟目录；访问 ct 根时仍要确认对应 Storage 根，不能仅凭配置宣称后端正常。具体区分“缺失 / 被文件遮蔽”和真实访问故障时，只处理可识别的前两者，不能吞掉权限、关闭或一般 I/O 错误。

### 2.5 串行边界与竞争复用

T11 使用单连接且不支持并发 / 嵌套事务，本轮让 Registry 注册与必要逻辑查询使用可由后续编排共用的串行边界（简单 Mutex 或等价方式即可）；只给 Registry 私有锁、后续写入另用一把锁，不能满足要求。注册过程在串行范围内复查有效记录，仍以数据库唯一约束与 register 返回值兜底；注册只提交 Node，不写事件，单条注册不必再套事务。

例：并发 stat 同一路径最终只有一个有效 Node；T15 的 write、懒注册与事务需要复用同一边界，调用组合不能重复进入不可重入 Mutex。具体锁的传递和持有方式由开发选最小方案并写一个调用例子，不建立通用调度器；等待锁时取消应正常传播。

### 2.6 失败与后续调用

Storage 确认失败时不得新增成功 Node；状态库失败保留 STATE_ERROR 及原因，取消继续原样传播，不把“收到取消”解释成数据库必定没有提交。Registry 不写成功事件、不改 Metadata，也不实现 read / list / 文件变更；T15 普通 read 直接访问 Storage，list 仅用现有批量查询补 ID，不调用注册入口。

例：底层 stat 被拒绝后 Registry 仍无该路径；注册提交完成后发生返回取消，后续查询可能已经看到 Node，应复用它而非补偿删除。取消时机测试按实际控制位置说明，不要求新增恢复日志。

## 3. 本轮边界

| 本轮交付 | 后续承接 |
| --- | --- |
| Registry 查询、资源确认、懒注册、虚拟目录判断 | T15 将其接入 DefaultVfs.stat；T18 Runtime 组装与配置 |
| 可共用的串行边界及最小调用示例 | T15 / T16 / T17 / T20～T23 的状态读写使用同一边界，避免单连接事务交错 |
| UUIDv7 生成、竞争返回记录复用、NodeInfo 所需信息 | 写入 / 移动 / 删除的 Node 更新、跨 Mount 身份保持由对应任务交付 |
| 注册不写事件、逻辑查询不查后端 | T14 事件分发；T15 read / list；T17 Metadata API |

不增加身份 / ACL、缓存与后台扫描、外部文件世代识别、跨进程锁、动态重挂载迁移或自动修复。配置变化、physical 标记与外部状态变化的细节若影响现有契约，先记录最小决定；不要在本轮扩展 Registry 为完整文件系统编排层。

## 4. 实施顺序

| 步骤 | 内容 | 完成证据 |
| --- | --- | --- |
| S1 查询与边界 | 整理 Registry 入口、依赖与共享串行方式，完成逻辑查询 | 不访问 Storage 的调用计数；明确供 T15 复用的最小例子 |
| S2 确认与注册 | 接入路由和 Storage.stat，处理 UUIDv7、竞争复用、虚拟目录和错误 | 路由 / 查询矩阵、并发同路径、失败不注册 |
| S3 真实组合与交付 | Core 单测及真实 SQLite + Local FS 组合验证，整理交付文档 | A01～A08、完整检查、提交号与未验证范围 |

真实组合用例放 `integration-tests/vfs`，可直接组装已有组件，不要求先实现 Runtime；Core 单测使用小型计数 / 故障替身即可。不要为了固定测试数量或覆盖未来方法而扩建测试框架。

## 5. 验收标准

| 编号 | 必须满足 | 关键证据 |
| --- | --- | --- |
| A01 身份与持久性 | 首次注册 UUIDv7、重复查询稳定；使用 register 返回记录；已删除路径复用新身份 | 真实 SQLite + Local FS：确认 → 注册 → 重建组件 / 重开库 → 同 ID；旧记录删除后再注册新 ID，删除动作可用既有 Repository 作准备 |
| A02 查询矩阵 | 默认 stat 查后端；已有 Node 的 getNode / includeStorage=false 不查后端；未注册物理路径须确认存在 | 计数或拒绝调用替身区分各分支；返回 storage 是否为空、逻辑时间不被物理时间覆盖；未知 / 已删除 ID 为 NOT_FOUND |
| A03 路由与虚拟目录 | 最长挂载、相对路径、纯虚拟目录、祖先文件遮蔽与 Mount 根检查符合 T02 | 无 Mount 普通路径 MOUNT_NOT_FOUND；纯虚拟目录无物理创建；配置祖先真实目录 / 同名文件；子 Mount 失败不回退、根失败不伪装成功 |
| A04 并发与串行 | 同路径并发得到同一有效 ID；不同路径不因嵌套 / 并发事务而失败；共享边界可复用 | 真实 SQLite 并发调用；与持有同一边界的状态操作受控交错，Registry 不读到其未提交状态；取消等待不遗留锁 |
| A05 冲突与失败 | 物理缺失 / 拒绝访问不注册；已有类型冲突 CONFLICT；状态库错误 / 取消不被错误包装 | 物理与库状态断言，ID / Metadata 不被查询修复；数据库失败与取消路径说明实际 effect，不承诺取消一定无提交 |
| A06 无额外副作用 | 注册不写 Event、不改 Metadata；已有逻辑查询不注册、不更新时间 | 用真实库核对 Event / Metadata / Node 前后；read / list 留 T15，不为本项提前实现它们 |
| A07 模块边界 | 实现属于 Core，仅依赖 Port / API / 通用 UUID 工具；不引用具体后端或数据库 | 构建依赖及源码检查；真实组合测试在 integration-tests，公开 API 不泄漏基础设施类型 |
| A08 构建与交付 | 现有 363 个测试无回归，新测试实际执行，交付范围清楚 | 项目工具链执行 spotlessApply、scripts/check；分模块计数、提交号、自测证据、平台与未验证事项 |

同目录补使用说明、验收记录及交付文档；使用说明以“磁盘已有文件首次拿到 Node ID”为例，说明何时访问后端、何时写状态。开发完成标 IN_REVIEW，独立复核通过才标 DONE；具体类名、锁和查询细节开发时确定，只回写影响契约的决定，不自动开始 T14。
