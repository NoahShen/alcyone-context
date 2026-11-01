# Alcyone Context System 架构设计

**版本**：v0.1  
**日期**：2026-10-20  
**状态**：Context System MVP 架构基线；Memory 统一本地 SQLite，VFS 负责 Map、备份与 Resources；Organization 允许未确认认知、不再维护 Deferred；Dreaming MVP 已定稿，详细事务与协议以《核心数据流与接口设计》为准  
**上层体系**：Alcyone — Personal Agent Framework

## 本次修改（2026-10-25，CT01 设计差异同步）

- §4.4、§5.4：同步 Memory Map 采用 T1 固定快照与最终一致性规则，取消生成前后 T2 水位二次检查与发布锁；Map stale 状态由 Map 模块自身与 Cron 补偿机制处理（见技术实现设计 §7.3）。

---

## 1. 背景与定位

**Alcyone** 是面向个人长期使用的 Personal Agent Framework：由用户统一表达目标，系统按需组织 Agent 完成任务。构建完整、成熟的 Personal Agent Framework 涉及交互、Agent 执行与编排、外部系统集成等大量能力，短期不作为本项目的交付前提。

**Alcyone Context System** 是 Alcyone 的重要子系统，同时也是一个可独立部署、可供其他个人 Agent 框架复用的通用项目。它不承担 Agent 任务规划、编排和执行，而是负责持续收集上下文、学习长期记忆、整理个人资料，并向 Agent 提供可用的 Context。

项目的最初动机与首要价值是：

> **用户应当真正拥有、管理并控制自己的长期记忆数据，而不是让重要的个人记忆被锁定在某一个 Agent、模型平台或聊天产品中。**

系统既要实现 **Data Ownership（数据自主）**，也要实现 **Memory Intelligence（持续形成高质量记忆）**。记忆是用户的长期资产，Agent 与模型只是临时访问和处理这些资产的工具。

### 1.1 目标

- 让不同 Agent / Runtime 的会话进入统一的数据沉淀流程。
- 持续识别有价值的用户事实、目标、偏好、决策及跨会话经验。
- 高质量地组织、更新、归档和反思记忆，而不是依赖各 Agent 主动提交 Pending MEM。
- 让不同 Agent 自行使用统一 Context API 获取记忆和 Resource 位置；Context System 不根据任务主动决定使用哪些记忆，摆脱固定的 Agent—Domain 绑定。
- 支持自托管、自选存储、自选模型和迁移导出，避免数据与厂商绑定。

### 1.2 非目标

- 不实现完整 Personal Agent Framework、Agent 编排器或通用 Harness。
- 不负责替 Agent 做最终业务决策，也不强制要求固定的 Specialist Agent。
- 不将所有历史对话直接等同于长期 Memory。
- 不要求第一版提供复杂的逐 Agent / 逐 Domain ACL 配置、多租户协作或分布式基础设施。

---

## 2. 核心设计原则

1. **User-Owned Context**：个人 Memory 与 Resources 属于用户；本地 `context.db` 是 Conversation/Memory 的不可丢失权威存储，支持一致性备份与可读导出；Resources 和派生文件由 VFS 管理。
2. **Agent & Model Independent**：Context 与 Hermes、Pi、OpenCode 或具体模型解耦；通过适配器与统一接口接入。
3. **Conversation-Driven Learning**：会话是主要学习来源；Agent 主动提交信息可以保留为补充，但不再驱动主流程。
4. **Authoritative Memory Database**：Raw Memory 作为不可变原始证据，Organized Memory 作为当前有效认知，两者均为同一 SQLite 权威数据；Memory Map 等概览可重新生成，数据库本身不能当缓存删除。
5. **Domain-Oriented, Agent-Independent**：领域是知识组织维度，不是固定 Agent 的身份或默认权限边界。
6. **Agent-Directed Context Loading**：Bootstrap 只提供 Memory Map 与接口说明；Agent 自主按需搜索 Memory、通过 ID 读取记忆，再使用 URI 读取 Resources。Recent View 保留但非 MVP。
7. **Continuous Learning & Dreaming**：持续提取和治理，手动或可配置 Cron 触发跨会话主题反思；模型的语义判断和 Core 的确定性写入分离。
8. **VFS as File Infrastructure**：复用现有 VFS 管理 Markdown Map、数据库一致性备份和 Resources；运行中 SQLite 直接访问本地目录，不经过 VFS；VFS 不理解 SQLite 或记忆业务。

---

## 3. 总体架构

```text
Hermes / Pi / OpenCode / Other Agents
                │
        统一 Context API
         ├─ bootstrap / searchMemory / getMemory / getRawMemory
         └─ loadMeta / read / readStream (Resource URI)
                │
       ┌────────┴───────────┐
       ▼                    ▼
  Memory Service      Resource Access
       │                    │
       ▼                    ▼
  本地 context.db       Alcyone VFS
       │                    ├── memory/map.md
       │                    ├── memory/backups/（一致性快照）
       │                    └── resources/（文档和附件）
       │
       ├── Conversation Turn（Adapter → Ingestion）
       │         ↓
       │   Count + Cron Idle Trigger / Token Budget 折半窗口
       │         ↓
       ├── Extraction → Raw Memory + Pending（同库事务）
       │         ↓
       └── Organization → Organized + 来源关联（同库事务）
                        ↓
                 Memory Map Generator
                  → VFS Markdown Map

Dreaming：手动 / Cron → 增量 Conversation 发现 Topic
         → 检索历史 Raw / Organized / Conversation 证据
         → 生成有依据的 Raw Memory → 统一 Organization 治理。
Database Backup：Context System 创建 SQLite 一致性完整快照
                  → VFS 管理备份文件（Local FS + Git 或 WebDAV，可部署时选择）。
```

**边界说明**：单实例 Kotlin Context Service 可容纳这些逻辑模块，不要求拆分微服务。Adapter 属于 Agent Runtime 接入侧。**`context.db` 是本地运行数据库**，由 SQLDelight / SQLite Repository 原生访问；VFS 不介入其事务，也不提供逐条 Memory URI。VFS 对任何上层文件一视同仁，无需改动 VFS 技术或核心用例文档。

## 4. 核心数据与事实来源

### 4.1 Conversation

各 Runtime 适配器推送标准化会话事件，至少保留来源、可信用户身份、会话 ID、轮次 ID、时间、参与者角色及消息正文；工具结果等可以根据配置择要采集。接收后持久化、幂等去重、记录处理进度。

Conversation 是**记忆的证据来源**，不是默认直接暴露给 Agent 的长期 Memory。标准 Conversation Turn 与 Raw/Organized Memory **同存于本地 `context.db`**，用于支持 Extraction 的原子事务；来源仍使用可迁移的稳定业务键。

### 4.2 Raw Memory 与 Organized Memory

- **Raw Memory**：保留提取/回顾得到的原子记忆、生产方式、稳定来源引用和创建时间；业务上不可变，可以包含历史认知和重复证据，不能自然等同于当前事实。**时间敏感状态的发生／生效／确认时间（有来源依据时）直接写入 `content`**，不新增独立事件时间字段，也不把入库时间当作事实时间。
- **Organized Memory**：从 Raw 治理得到的**当前认知（包含明确标注的尚未确认事项）**，维持稳定 Memory ID；MERGE / UPDATE 修改同一条记录，不创建完整历史版本。
- **物理存储**：Conversation、Raw、Organized、`organization_pending`、`organized_memory_source` 均位于同一个本地 SQLite `context.db`，作为权威数据。Raw 来源可追溯到 Conversation/Raw；Organized 来源使用独立 `organized_memory_source` 关联表，`rawMemoryIds` 在 Kotlin 逻辑模型中由 Repository 组装。
- **原子提交**：Extraction 在同库事务中同时提交 Raw、Pending、Turn complete；Organization 在同库事务中提交 Organized、来源关系、Pending 状态。LLM 执行不占用数据库写事务。
- **标识区分**：Memory 由稳定 ID 访问；Resource 由 VFS URI 访问。不为每条 Raw/Organized Memory 创建 JSON 文件或逻辑 VFS URI。

### 4.3 Organization 与 Domain

Organization 通过低成本 **LLM Memory Scanner** 逐批检索相关 Organized 候选，再由独立 Governance 判断 `NEW / MERGE / UPDATE`。如果两个来源不能确认同一事项的当前取值，就把**明确标注未确认的认知**写入 Organized Memory，并与两边来源关联；Agent 可在需要时向用户核实。完成后在同一 SQLite 事务中出队。**完全取消 `CONFLICT / deferred` 业务分支**，仅技术失败保留 Pending。默认串行，不引入额外冲突队列。

Domain 只是 Organized Memory 的知识主题分类（例如 Quant、Family、Education），不是固定 Agent 身份或默认访问权限。MVP 不建立逐 Agent/Domain ACL。

### 4.4 Memory Map

**Memory Map 是面向 Agent 的长期记忆概览与导航入口**，不再使用 Memory Projection 及 Full / Summary / Category Index 三分法。

- **Memory Overview**：重点呈现用户长期背景、偏好、目标、约束和当前重要认知，使 Agent 快速建立对用户的基本了解。
- **Memory Navigation**：展示系统已覆盖的知识领域、主要主题以及进一步搜索、读取的导航指引。

Memory Map 从 **Organized Memory 当前认知（包含尚未确认事项）**中派生，不是第二套事实存储。**Full Memory 不再单独存在**，详细记忆由 Retrieval 按条件直接查询 Organized Memory。高体量的专项资料更适合放在 Resources 文档中，Map 只需提供概览和导航；不设置硬性数量阈值。MVP 统一持久化为 `alcyone://memory/map.md`；基于全部当前 Organized Memory 由 LLM 生成 Overview / Navigation，记录 `generatedAt` 与 `sourceUpdatedAt = MAX(OrganizedMemory.updatedAt)`；由 Organization 轮次结束后合并触发刷新（Map 采用 T1 固定快照 + 最终一致性，生成后不再做 T2 二次检查；详见技术实现设计 §7.3；已批准变更（2026-10-25））。若仅证据引用变化也可冗余刷新；过期 Map 不得当作最新事实依据，未确认的事实也不得在 Map 中被总结成确定结论。Memory Map 模块初始化时比较 `MAX(updatedAt)`，缺失或 stale 时安排一次异步刷新；Bootstrap 只读取已有 Map 状态，不负责触发刷新。具体 API 与 Prompt 以详细设计为准。

### 4.5 Recent Memory View（延后实现）

Recent Memory View 保留为未来可能需要的**SQLite 逻辑查询视图**，不存一份独立副本，可用于提示尚未完成 Organization 或已治理但尚未反映到 Map 的近期变化。它并非 MVP 的 Bootstrap 依赖；第一版**不实现**，根据真实使用中 Map 延迟问题再决定优先级。若将来返回 Pending，必须明确其尚未完成治理；Organized Memory 内的未确认认知同样不能被当作确定事实。

### 4.6 Resources、VFS 与数据库备份

VFS 按原有两大命名空间管理**普通文件**，无需知道文件内容代表什么业务数据：

```text
alcyone://memory/
  ├── map.md                 # Memory Map，可阅读/查看 Git Diff
  └── backups/              # context.db 的一致性备份快照
alcyone://resources/         # 文档、研究资料、图片及附件
```

**运行中 `context.db` 存放在普通本地目录，不通过 VFS 打开或写入**；Context System 使用 SQLite Online Backup API（可由 Xerial SQLite JDBC 连接调用）生成完整一致性快照，成功后交 VFS 作为普通文件存储；使用 Local FS + Git 或 WebDAV 由部署/开发选择。不得直接复制正在运行的 db/WAL/SHM 文件。复杂保留策略和灾难恢复不属于 MVP。

Resources 不建设独立模糊搜索或语义索引；**重要 Resource 的主题/用途/精确 URI 通过 Organized Memory 记录**。Agent 先 `searchMemory()` 找到 URI，随后 `loadMeta()` 检查格式并通过 `read()` 或 `readStream()` 获取原始内容，是否解析 PDF/图片由 Agent 自行决定。

## 5. 持续学习流水线

### 5.1 Conversation Ingestion / Extraction Trigger

Adapter 推送标准 Conversation Turn，经校验和业务唯一键幂等去重后写入 `context.db`。**不再使用旧版 Fast Filter**：MVP 同时包含按 Session Pending 数量触发的 Count Trigger 和 Cron 扫描空闲且有 Pending 的 Idle Trigger。二者向同一 Scheduler 提交 SessionKey；单 Worker 串行执行。Builder 以最新 Pending Turn 为锚点，向前读取 N 条连续 Turn；**超出整体 Token Budget 时将 N 折半后重读，直至首次满足预算**，只处理固定窗口内 Target，**不支持 LLM 执行期间二次按需追溯历史**。只有折半至 `N=1` 仍超限才标记锚点 `failed`，再选择下一条最新 Pending 重新构建；原始 Turn 保留，分段与人工恢复后续再实现。

### 5.2 Memory Extraction

LLM 只使用本轮固定窗口，对冻结的 Pending Target 提取 0～N 条具来源的 Raw Memory，严格整批校验；**时间敏感信息应根据 Conversation 时间和用户原话把有依据的生效／确认日期纳入 `content`**，不臆造精度。校验成功后一次 SQLite 事务提交 Raw、Organization Pending、本轮 Target Complete。**窗口外仍有 Pending 时不提前标记 Complete**，由后续 Count/Idle 等 Trigger 再发现。Extraction 不预先装载已有 Raw 做跨批次去重；零记忆输出也可正常完成本轮 Target。

### 5.3 Memory Organization

从 `organization_pending` 逐条串行消费；先以权威 `organized_memory_source` 检查该 Raw 是否已整合。LLM Memory Scanner 全量分批筛出候选，Governance 独立作出 NEW / MERGE / UPDATE 决策；Core 校验并通过单一 SQLite 事务提交关联和状态。

### 5.4 Memory Map（概览与导航）

基于 SQLite Organized Memory 当前有效认知，用 LLM 生成一份 Markdown Overview + Navigation，经 VFS 发布 `alcyone://memory/map.md`。记录来源水位 `sourceUpdatedAt = MAX(organized_memory.updatedAt)`；Organization 批次后合并刷新，**Memory Map 模块每次初始化时另外检查一次 `MAX(updatedAt)`，不一致则异步安排刷新**，记录快照 T1 水位（不再做生成后 T2 二次校验；详见技术实现设计 §7.3；已批准变更（2026-10-25）），允许仅追加 Raw 证据时冗余刷新。Map 是派生视图，失败保留旧版并标记 `stale`；Bootstrap 不负责刷新。

### 5.5 Dreaming（MVP 已定稿）

采用 **Topic-Centric Incremental Dreaming**：支持**手动触发与可配置 Cron 表达式**（未配置则不自动执行），由单 Worker 串行处理；执行中再次触发不并行启动，Cron 触发可跳过。Topic Discovery 只读取上次成功 Checkpoint 后的增量 Conversation，**不等待 Extraction Complete**，从中选择值得反思的临时 Topic。历史检索仍为 **Organized → Raw → 必要时回读 Conversation**：Organized 代表当前认知基准，Raw 代表可能已过时或已纠正的历史证据；命中 Raw 后通过现有 `organized_memory_source` 反查其关联的当前 Organized（即使首次语义搜索漏召回），与原始时间和用户纠错对照。

Reflection 只在证据充分、相较当前 Organized Memory 有实质增量时生成 `generatedBy=DREAMING` 的 Raw Memory；**允许空结果**。Prompt 明确禁止仅靠重新召回已被用户纠正的旧 Raw 就恢复旧的当前结论；改变当前认知需要新的可信证据或核实后的实质新发现，冲突无法判定时保留不确定性。Governance 遵循同样的纠错保护原则，Organized 也并非不可质疑。新 Raw、Organization Pending 与成功处理范围的 SQLite Checkpoint 由同库事务提交，成功后交给 Organization。Dreaming 不直接修改 Organized Memory/Map、不持久化 Topic，也**不做 Deferred Review**。失败不推进 Checkpoint；处理范围、证据校验和细节见核心数据流文档 §17。

## 6. Agent Context 交付

**轻量 Bootstrap，Agent 自主查询。** Context System 不分析当前业务任务、规划查找策略或替 Agent 自动装配完整的任务相关材料。

1. Agent 初始化时调用 `bootstrap()`，获取 Memory Map、`stale` 标记及必要的检索接口说明；MVP 不注入 Recent View 或完整 Resources。
2. Agent 根据自己的任务调用 `searchMemory(query, domains?, limit?)`；底层 LLM Memory Scanner 只筛选 Organized Memory ID，结果从 SQLite 读取真实内容。**Search 不增加 `MAX(updatedAt)` 的额外快照一致性校验**。
3. 已知 Organized Memory ID 时直接 `getMemory(id)`；追溯证据时按需 `getRawMemory(id)`。内部 Repository 可以继续执行 Domain、更新时间等 SQL 条件查询，**不单独对 Agent 开放 `query()`**。
4. 如记忆包含资源文件位置，Agent 取出 `alcyone://resources/...` URI，可选 `loadMeta(uri)` 判断格式、大小，再调用 `read(uri)` 或 `readStream(uri)` 获取原始文件，自行解析使用。
5. Agent 产生新的会话，经 Runtime Adapter 返回 Conversation Ingestion，进入持续学习流程。

MVP 只对可信 Agent 做必要的基础授权，保留 VFS 既有机制，不建设细粒度 Agent/Domain ACL。Resources 创建、写入、移动等仍通过独立的 VFS 文件管理能力，避免把只读 Retrieval 变成业务文件管理接口。

## 7. 数据自主、隐私与安全

**数据主权是产品能力，而不是部署口号。**

- **可控制**：用户配置会话采集范围、Memory 存储位置及备份目标；第一版通过 Agent 自然会话纠正、更新偏好，不实现独立的人工 Memory 编辑或物理删除接口。
- **可迁移**：第一版支持 Context SQLite 一致性完整快照备份；独立可读 JSON 导出与迁移属于后续能力。Map 为可重建 Markdown，Resources 以普通文件由 VFS 保管。不能把 SQLite 视为可删除重建的缓存。
- **可自托管**：不依赖某一个云端 Agent 平台；支持用户自行选择本地/私有存储，模型可按隐私级别配置。
- **可审计**：Raw 的原始证据与 Organized 的来源关联可追溯；更完整的操作审计保留为后续增强，不作为当前 SQLite 表结构的强制新增模块。
- **最小披露**：不默认向第三方模型发送完整会话或敏感资料；用户可决定仅使用本地模型处理特定类别。
- **授权独立于 Domain**：MVP 面向单用户可信 Agent，保持基础服务身份验证与 VFS 既有文件访问限制，不构建严格的逐 Agent/Domain ACL；更细的授权与敏感资料隔离以后按实际使用需求评估。

**注意**：加密、密钥管理与授权凭据按部署环境落实；MVP 不提供物理删除或彻底遗忘能力，也不把普通认知更新宣称为数据已清除。不将“本地优先”误写为“任何模型调用都不会泄露数据”的保证。

---

## 8. 逻辑模块与继承关系

```text
alcyone-context/（逻辑模块，非物理 Gradle 目录强制约束）
├── ingestion/           # Conversation Turn 标准化、去重
├── conversation/        # SQLite Conversation Repository
├── memory/
│   ├── extraction/      # Session Snapshot、Raw 提取
│   ├── organization/    # 候选扫描、治理、Pending
│   ├── scanner/         # Organization / Search 复用的 LLM 扫描能力
│   ├── map/             # Memory Map 生成与 VFS 发布
│   └── dreaming/        # 主题驱动增量反思（手动/Cron）
├── retrieval/           # Memory ID / 语义 Search / Resource 精确访问
├── context/             # 轻量 Bootstrap、统一 API
└── vfs/                 # 复用现有文件基础设施，不改其设计
```

这里只是职责分区，不要求实现为多个独立服务。Memory/Conversation 使用同一 `context.db` 和事务能力；VFS 的 Kotlin/OpenDAL/SQLDelight 选型及既有项目目录设计不受本次调整影响。

## 9. 分阶段目标

### P0：最小可用闭环

- Runtime Adapter → Conversation Ingestion → 本地 `context.db` 幂等保存。
- Count + Cron Idle Trigger / 最新 Pending 向前读取 N 条，超预算折半至合规窗口（N=1 仍超限标记 `failed`，无按需二次追溯）→ LLM Extraction（时间敏感事实保留有依据的日期语义）→ Raw + Pending + 本轮 Target Turn 原子提交。
- LLM Memory Scanner / Governance（NEW/MERGE/UPDATE）→ Organized Memory（含明确的不确定认知）、来源关联及 Pending 出队原子提交。
- Memory Map（VFS Markdown）生成、Organization 变更触发及 Map 模块初始化 `MAX(updatedAt)` 检查，Bootstrap 仅返回已有 Map/`stale` 信息。
- Dreaming：手动/Cron 增量 Conversation（不受 Extraction 状态阻塞）→ Topic Discovery → Organized 对照、Raw 检索及来源反查当前认知、必要的 Conversation 核实 → 纠错保护 Reflection → Raw + Pending + Checkpoint 同库事务。
- Agent 可调用轻量 `bootstrap()`、`searchMemory()`、`getMemory()`/`getRawMemory()`，以及面向 Resource URI 的 `loadMeta/read/readStream`。
- 由 Context System 通过 SQLite Online Backup API 生成完整一致性快照并交 VFS 保存（Local FS + Git 或 WebDAV，开发时选择）。

### P1：记忆质量与成熟度

- 治理质量指标、不确定认知的用户确认效果，以及 Dreaming 的产出价值/成本优化（**Dreaming 手动/Cron 增量反思已纳入 P0**）。
- 视真实使用体验决定是否实现 Recent Memory View；根据性能考虑 Scanner 的可替换检索方案；持续失败退避和较旧 Pending 的公平性优化。
- 对 SQLite 备份保留、恢复演练、迁移导出、操作审计和更细粒度授权专项加强；直接编辑、数据擦除/隐私删除需未来单独定义语义。

### P2：通用化与生态接入

- Pi、OpenCode 等更多 Agent Runtime 适配器。
- 自托管部署、模型与存储选择、迁移导入导出。
- 按需要增加更丰富的 Agent 调用授权或管理工具接口。

## 10. 后续待明确的设计问题

1. `context.db` 的实际 SQLDelight Schema、迁移、完整快照备份调度与未来保留/恢复演练；**权威 SQLite 不得作为可重建缓存处理**。
2. 完整的 Extraction / Governance Prompt、模型选型以及对真实记忆的漏检/重复治理质量评估。
3. `LLM Memory Scanner` 批次 Token Budget、候选过多时的排序与错误响应；Agent 接口协议可选择 HTTP/SDK/MCP，不影响语义。
4. Memory Map 生成质量审查、过期发布和模块初始化水位检查；Recent Memory View 保留但先观察必要性。
5. 隐私模型使用策略以及未来可信 Agent 之外的访问控制。

> 已确认的核心数据流、SQLite 事务边界与 Context API 以《Alcyone Context System 核心数据流与接口设计 v0.1》为准；VFS 技术设计及用例文档**不因本次上层存储变更而修改**。

## 11. 一句话总结

> **Alcyone 是个人 Agent 框架；Alcyone Context System 是其可独立使用的长期 Context 基础设施，让用户掌握自己的记忆数据，让记忆从会话中持续学习、被高质量地组织，并随时服务于不同的 Agent。**
