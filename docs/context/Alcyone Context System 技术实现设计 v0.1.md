# Alcyone Context System 技术实现设计

**版本**：v0.1  
**更新日期**：2026-10-25  
**状态**：**专题 1～10 全部定稿**；可作为 Coding Agent 实施与验收基线  
**项目**：Alcyone Context System  
**位置**：Alcyone / Context System

## 本次修改（2026-11-10，CT26 SDK 接线边界补充）

- 在 §8.2/§8.7 补充已存在 SDK 手动入口的关闭与错误语义：第三方未实现为 `UNAVAILABLE`，真实重叠才为 `ALREADY_RUNNING`；关闭须先封闭手动/Cron 准入并等待已登记手动及后台运行收尾，再释放 LLM/SQLite。
- 不改变 Manual+可选 Cron、单 Worker、Checkpoint、短事务与无 StartupRecovery 契约；真实收费模型评测仍按 §10.6 独立授权。

## 本次修改（2026-10-25，CT01 设计差异同步与 VFS 配置归属）

- 按用户意见调整 §1.1、§1.2、§1.5～1.6：Context Runtime 读取 YAML 的 `context` 与 `vfs` 分区，将 `vfs` 配置转换为现有 `VfsRuntimeConfig`；不新增 VFS Loader、不修改 VFS。
- 配置文件解析和映射属于 Runtime 接入职责，不进入 Context Core；VFS 继续执行既有配置对象校验。VFS 实例在 Context `start()` 时创建，保持 `create()` 不初始化运行资源的约定。
- CT01 实施契约核对同步：§1.3 模块表格定点纠正为「不依赖 Ktor Server（未来 LLM Adapter 可使用 Ktor Client）」，与 §1.2、§3.1 保持严格一致。

## 0. 文档定位与约定

本文档记录 Alcyone Context System 在正式交由 Coding Agent 实现之前，需要统一确认的**工程技术方案与实现契约**。先完成各专题设计，再启动实际编码。

**上游依据**：
- 《Alcyone Context System 架构设计 v0.1》——业务定位、架构及 MVP 范围。
- 《Alcyone Context System 核心数据流与接口设计 v0.1》——Conversation → Memory → Context 的数据流、处理状态及事务边界。
- 《Alcyone Context 项目搭建说明 v0.1》——既有 VFS 工程组织方式。
- 《Alcyone Virtual File System (VFS) 技术设计 v0.2》——独立 VFS 的能力与架构边界。

**约束**：本文件细化工程实现，不擅自更改前两份 Context System 文档已经确认的业务语义。若出现冲突，需单独识别并讨论，不能由 Coding Agent 默默做出架构决策。

**状态表示**：
- **已确认**：经过专题讨论认可，作为后续实施基线。
- **待细化**：已明确归属后续专题，但具体接口、参数或实现规则尚未批准。
- **示意**：用于表达设计意图的伪代码或配置，不能直接视为精确 API 或正式默认值。

---

# 专题 1：工程架构与技术选型（已定稿）

## 1.1 总体架构及原则

Alcyone Context System 使用 **Kotlin/JVM 模块化单体架构**，同时支持纯 Kotlin SDK 与独立 HTTP 服务两种接入形态。

1. **SDK First**：Context Runtime 是纯 Kotlin SDK；HTTP Server 仅为外部接入层，不承载核心业务。
2. **清晰职责**：API 定义对外契约；Core 实现业务规则并定义内部依赖 Port；Persistence 负责数据持久化；Runtime 负责依赖组装与后台运行；Server 负责 HTTP。
3. **单实例 MVP**：不为多实例一致性、分布式调度、租户协调设计基础设施。
4. **数据可靠性优先**：Conversation / Raw / Organized Memory 等权威状态由同一个本地 `context.db` 管理；内存触发和执行状态不是权威存储。
5. **简单可维护**：不用微服务、通用 DI 容器、外部队列或通用业务重试框架。
6. **VFS 保持独立**：VFS 保持已有配置对象、数据库与 SDK；Context Runtime 负责配置文件解析并通过公开接口集成，不要求 VFS 提供 YAML Loader。

## 1.2 技术栈

| 领域 | 确认方案 | 备注 |
|---|---|---|
| 开发语言及 JVM | **Kotlin + JDK 21** | Kotlin/Gradle 补丁版本待工程搭建时统一锁定 |
| 构建 | **Gradle 多模块、Kotlin DSL** | 依赖版本统一管理；具体版本待定 |
| HTTP | **Ktor + CIO** | 不引入 Micronaut；Server 只承担网络职责 |
| 后台执行 | **Kotlin Coroutines** | `CoroutineScope + SupervisorJob` |
| Cron | **cron-utils + Coroutines** | 使用 UNIX 五字段表达式，支持独立时区 |
| 数据存储 | **SQLite + SQLDelight** | `context.db` 为不可丢失的权威数据库 |
| SQLite 驱动 | **Xerial SQLite JDBC** | Online Backup API 细节在数据库/备份专题核实 |
| 依赖组装 | **手动依赖注入** | Runtime 作为 Composition Root，不使用 Koin/Dagger |
| LLM 接口 | **Core 的统一 `LlmClient` 抽象** | Runtime 初期实现 OpenAI-compatible Adapter |
| 配置文件 | **单 YAML 文件，Runtime 解析 Context/VFS，Server 解析服务配置** | 详见 §1.6；VFS 使用现有配置对象 |
| 测试 | **JUnit 5、真实 SQLite、Ktor TestApplication** | Core 通过 Fake 依赖测试 |

`kotlinx.serialization` 与 **Ktor Client + CIO** 已在专题 3 确认用于 LLM Adapter；具体 YAML 实现库及各依赖兼容版本仍待工程搭建时锁定。

## 1.3 工程目录及模块职责

```text
alcyone-context/
├── docs/
├── vfs/                             # 现有独立 VFS 组件，保持既有模块设计
│   ├── api/
│   ├── core/
│   ├── storage-opendal/
│   ├── persistence-sqldelight/
│   └── runtime/
├── context/
│   ├── api/
│   ├── core/
│   ├── persistence-sqldelight/
│   ├── runtime/
│   └── server/                     # Ktor + CIO
└── integration-tests/
    ├── vfs/
    └── context/
```

Context 采用 **五模块**：

| 模块 | 职责及限制 |
|---|---|
| `context/api` | 对外 Kotlin API、稳定数据契约；不暴露 SQLite、Ktor、模型厂商实现 |
| `context/core` | Conversation、Extraction、Organization、Memory Map、Retrieval、Dreaming 业务逻辑；定义 Repository、LLM 等内部 Port；不直接依赖具体基础设施 |
| `context/persistence-sqldelight` | Context SQLite Schema、SQLDelight 查询/Repository、事务实现、迁移；不管理 VFS SQLite |
| `context/runtime` | SDK 入口、手动依赖注入、Context/VFS 配置加载衔接、LLM Adapter、Worker/Cron 运行与生命周期；不依赖 Ktor Server（未来 LLM Adapter 可使用 Ktor Client） |
| `context/server` | HTTP Route、鉴权、请求响应转换、Server 配置与启动/关闭；不得绕开 Runtime 直接执行数据库或业务逻辑 |

Core 内部按 `conversation`、`extraction`、`organization`、`scanner`、`memory/map`、`retrieval`、`dreaming` 等 Kotlin package 分区；**不**为每个流水线独立建立 Gradle 子模块，也不预先增加 `common` / `infrastructure` 等泛化模块。

**依赖方向**：`api` 定义公开契约；`core` 实现规则及所需 Port；`persistence-sqldelight` 实现持久化 Port；`runtime` 统一组装 `core + persistence + vfs` 和 LLM Adapter；`server` 只依赖 Runtime 公开能力。具体 Gradle 依赖清单在工程建立时据此落实。

## 1.4 Runtime、SDK 与 HTTP Server

**已确认：实例创建与后台服务启动分离。**

```kotlin
// 示意：签名尚非最终公开 API
val context = AlcyoneContext.create(contextConfig, vfsConfig)
context.start()
// 调用 Context API
context.close()
```

- `create()`：完成配置对象接收与依赖组装；**不自动启动 Worker/Cron**。涉及数据库连接或其他有副作用的初始化，按照明确的生命周期规则在 `start()` 处理。
- `start()`：初始化必需运行资源、注册 Worker/Cron，执行 Memory Map 模块约定的初始化水位检查；不额外实施 Startup Recovery。
- `close()`：按 §1.8 的规则停止运行并释放自身拥有的资源。
- HTTP Server 为可选接入方式，使用 **Ktor + CIO**；不作为 Core、Runtime 配置或业务生命周期的上级依赖。

Core 的 Trigger / Scheduler / Worker 负责**业务规则和处理契约**；Runtime 负责实际协程、队列执行、Cron 和生命周期；Server 负责 HTTP 请求生命周期与 Runtime 的启动、停止衔接。

## 1.5 VFS 集成与所有权

- **Context Runtime 自己创建一个供该 Context 实例使用的 VFS**，并在关闭时负责释放它。
- VFS 保持现有 `VfsRuntimeConfig` 与 `AlcyoneVfs.create(config)` 公开契约；不新增 VFS Loader，也不为配置文件接入修改 VFS。
- Context Runtime 读取 YAML 的 `vfs` 分区并构造 `VfsRuntimeConfig`，在 Context `start()` 时调用 `AlcyoneVfs.create(config)`。配置解析/映射留在 Runtime，Context Core 不依赖 YAML 或 VFS 配置结构；VFS 继续执行已有的配置对象与挂载校验。
- Context Memory/Conversation SQLite 与 VFS 管理状态 SQLite **相互独立**，数据库不共享业务事务。
- 运行中的 `context.db` 由 Context 直接通过 SQLite 操作，**不**经 VFS 打开或写入；VFS 管理 `map.md`、备份快照与 Resources。

## 1.6 单 YAML 配置与 Runtime 加载职责

部署使用**一份 YAML 配置文件**（名称/路径示例 `config/alcyone.yaml`），以 `context`、`vfs`、`server` 顶层分区区分模块。

```yaml
# 仅示意顶层分区；LLM Profile/Task 细节及默认值见专题 3
context:
  database:
    path: ./data/context.db
  llm:
    profiles:
      fast:
        baseUrl: https://api.example-fast.com/v1
        apiKey: ${FAST_LLM_API_KEY}
        model: fast-model
        contextWindowTokens: 64000
        jsonMode: json_object
    tasks:
      extraction:
        profile: fast
        maxInputTokens: 12000
        maxOutputTokens: 3000

vfs:
  database:
    path: ./data/vfs.db

server:
  host: 127.0.0.1
  port: 8080
```

**规则（已确认）**：
1. Context Runtime 负责解析/校验 `context` 与 `vfs` 分区，Server 负责 `server` 分区；负责的分区内未知字段要报错，不能将其他模块负责的顶层分区视为错误。VFS 不新增配置文件 Loader。
2. Runtime 将 `context` 分区转换为 Context 配置，将 `vfs` 分区转换为现有 `VfsRuntimeConfig`；文件格式、字段映射、环境变量展开和文件路径解析由 Runtime 处理，VFS 保留已有配置对象校验。Server 不代替 Runtime 读取业务配置，Core 不承担配置解析。
3. SDK 可直接接受 Kotlin 配置对象；不强制绑定 YAML 文件。
4. 文件位置由启动方显式指定；支持 `${ENV_NAME}` 等环境变量引用，敏感字段不应出现在日志和异常文本中。
5. **MVP 不支持热更新**，配置变化通过重新启动实例生效。
6. 纯 SDK 模式下可以省略 `server` 分区。

**待细化参数**：相对路径以配置文件目录还是指定工作目录为基准、缺失环境变量如何报告、顶层分区必填规则、配置文件精确加载 API 和 YAML 库选型。这些属于工程参数落地，不改变本节架构。

## 1.7 后台协程与 Cron

**执行模型（已确认）**：
- Extraction：独立单 Worker，内部串行，按 Session 的 Trigger/Scheduler 规则处理。
- Organization：独立单 Worker，逐条治理 Raw Memory；Candidate Scanner 各批次顺序调用模型。
- Dreaming：同时最多一个 Worker；手动/Cron 触发按 Dreaming 的既定运行语义处理。
- **不同流水线之间允许并行运行**，但每一条流水线内部遵守自己的串行规则。
- Runtime 持有统一 `CoroutineScope + SupervisorJob`，避免某项后台任务的普通故障自动取消其他独立任务。

**Cron（已确认）**：
- 使用 `cron-utils` 解析 UNIX 五字段 Cron、计算下一次触发时间；由 Kotlin Coroutines 等待并发出触发信号。
- 各任务可配置自己的时区。
- Cron 只负责唤醒对应业务 Trigger；不在 Cron 回调内等待耗时 LLM 工作结束。
- 支持 Extraction Idle、Organization Periodic、Dreaming，以及可配置的数据库备份调度。
- 不使用 Quartz；不持久化 Cron 调度历史；不补跑停机期间错过的每次触发；不支持热更新。
- Trigger Counter、Pending 和 Checkpoint 的业务语义，严格遵循《核心数据流与接口设计》，不另设计第二套任务队列。

多流水线可以并发调用 SQLite；**数据库原子性由 SQLDelight/SQLite 事务与持久化访问策略保证**。如何管理连接、`WAL`、写入串行化与事务范围在专题 2 定稿；不以一把贯穿全部 LLM 执行的全局业务锁破坏流水线并行性。

## 1.8 Runtime 生命周期与关闭

**状态机（已确认）**：

```text
CREATED → STARTING → RUNNING → STOPPING → CLOSED
               └─ 启动失败后清理资源 → CLOSED
```

- 重复 `start()` 不重复启动 Worker 或 Cron；`close()` 重复调用安全处理。
- 启动期间关键数据库、Schema 或资源初始化失败，报告启动错误并清理部分取得的资源；关闭后的实例**不可重新启动**。
- 不强制在启动时连接全部 LLM Provider 做在线测试。
- Memory Map 模块初始化时执行其已有的水位检查，必要时异步安排生成；**不**额外全库扫描 Pending，不引入 StartupRecovery。
- 工作结束之前，不关闭仍被 Worker 使用的 SQLite/VFS/LLM 资源。

**关闭步骤（已确认）**：
1. 对外服务部署时，由 HTTP Server 停止接收新请求，并排空/终止请求。
2. Runtime 进入 `STOPPING`，停止接受新业务 Trigger 或后台任务。
3. 停止 Cron 调度。
4. 等待正在执行的业务任务至可配置超时；**不必清空持久化 Pending**。
5. 超时后取消剩余协程，并确认资源使用任务终止；不能在仍有数据库操作时盲目关闭数据库。
6. 释放 LLM Client、Context SQLite、VFS 等 Runtime 拥有的资源。
7. 进入 `CLOSED`。

**后续应定下的实现参数**：并发 `start()/close()` 的竞争规则、等待超时默认值、无法及时响应取消的外部调用如何报告故障及隔离。这些不更改状态机或优雅关闭原则。

## 1.9 异常边界

已确认按影响范围处理，而非建设一套复杂的通用恢复框架：

| 类别 | 处理原则 |
|---|---|
| 请求错误 | 明确返回失败，不影响其他请求或 Runtime 健康 |
| 可重试业务失败 | LLM 超时、网络错误、模型非法输出等；不进行部分业务提交；保留相应 Pending / Checkpoint，等待已有触发机制重试 |
| 确定性任务失败 | 例如单 Turn 经 N 折半至 1 仍超过预算；按已有约定标记该 Turn 为 `failed` |
| 致命基础设施错误 | 如数据库损坏、必需 Schema 不兼容；启动失败或暴露运行中故障并停止受影响服务 |
| 正常取消 | 按取消语义传播 `CancellationException`，执行清理，不吞掉正常取消 |

补充规则：
- Worker 在**业务任务边界**隔离和记录异常；`SupervisorJob` 不能代替 Worker 循环自身的容错处理。
- 不引入通用 Retry Framework；LLM Client 的有限调用级重试、整体超时与错误分类见专题 3；不得扩散成整个业务流水线自动重试。
- Extraction、Organization、Dreaming 原子提交及失败保留规则以数据流设计为准；LLM 网络调用不放在 SQLite 写事务内。
- Memory Map 刷新失败保留旧 Map 并标记 `stale`；备份失败不破坏已有有效备份。
- 发生致命问题时须有可观测的错误状态，不能把永久终止的 Worker 误当作正常空闲。

## 1.10 测试边界

| 测试层级 | 重点 | 依赖方式 |
|---|---|---|
| Core 单元测试 | Trigger/Scheduler 业务规则、Snapshot、Memory 校验、Governance/Dreaming 边界等 | Fake Repository、Fake LLM、可控时钟 |
| Persistence 集成测试 | SQLDelight、SQLite 唯一约束、原子提交、迁移、关联和并发写入 | 真实 SQLite；涉及 WAL/备份/重启场景用临时文件 |
| Runtime 集成测试 | 手动组装、创建/启动/关闭、重复调用、Cron、Worker 隔离、部分初始化失败 | 真实 Runtime + Fake 外部系统 |
| Server 测试 | Ktor Route、JSON/鉴权、输入校验、错误映射 | Ktor TestApplication |
| 端到端测试 | Conversation → Raw → Organized → Retrieval 及异常后重触发 | 真实 SQLite + 可控 Fake LLM |

常规自动化测试不依赖远程真实 LLM；模型语义质量、Prompt 漏检率、记忆重复/纠错质量在独立评测中开展。重点覆盖提交成功但通知丢失、数据库事务回滚、Cron 补偿、关闭时未完成任务和重复触发等可靠性用例。

## 1.11 明确不建设的基础设施

MVP **不建设**：
- 微服务拆分、多实例并发写入协调；
- Kafka、RabbitMQ、Redis 等额外消息系统；
- Quartz 或持久化 Cron 任务系统；
- 通用 DI 框架、通用 Retry Framework；
- 通用持久化 Worker 运行状态机和 StartupRecovery；
- 配置热更新；
- 独立的 Memory 文件存储或将 `context.db` 当作缓存重建。

## 1.12 专题 1 定稿结论与遗留细化项

**专题 1 架构决策已冻结，可以作为后续专题的统一前提。**

后续依实施需要补齐，但不再重复讨论的具体工程参数：
1. Kotlin/Gradle/Ktor/SQLDelight 等兼容版本及 JSON、YAML 实现库；
2. YAML 配置的相对路径、环境变量缺失、分区缺失等具体语义；
3. Runtime 并发 start/close、默认 shutdownTimeout、无法及时取消的任务收尾行为；
4. 日志框架、基本运行状态可观测性（已在专题 3 定稿）；
5. HTTP 鉴权、错误响应与资源流协议。

这些参数分别在基础设施、API、部署及验收专题里进一步定稿；不要求 Coding Agent 自主做出可能影响系统契约的重要决定。

---

# 专题 2：数据库与持久化（已定稿）

> **定稿日期**：2026-10-22。以下是开发契约，业务含义以《核心数据流与接口设计 v0.1》已确认规则为准。示例 Kotlin API 展示职责与类型边界，允许实现时在不改变语义前提下调整命名。

## 2.1 数据库定位与技术选型

- 唯一权威的 Context 关系型数据库：本地文件 `context.db`；Conversation、Raw、Organized、来源关联、Pending、Dreaming Checkpoint 由其持久化，**不可当作缓存删库重建**。
- 使用 **SQLite + SQLDelight + Xerial SQLite JDBC**，Kotlin Core 对 SQLDelight、JDBC 和物理连接不可见。
- 与 VFS 自己的 SQLite 数据库严格隔离；运行中的 `context.db` **不通过 VFS 文件 API** 读写。VFS 用于 Memory Map、数据库一致性备份快照及 Resources。
- MVP **单实例**、**1 个写连接 + 固定少量读连接**（初始建议 2 个读连接、可配置），不引入 HikariCP 等通用连接池。
- SQLite 使用本地文件系统和 WAL；写连接设 `PRAGMA synchronous=FULL`，数据持久性优先；`PRAGMA foreign_keys=ON` 与 `PRAGMA busy_timeout=5000` 在**每个连接**设置（5000ms 为建议初值）；读连接建议 `PRAGMA query_only=ON`。`journal_mode=WAL` 在数据库初始化时设置并核验；WAL Checkpoint 先保留 SQLite 默认机制。
- 允许 Extraction、Organization、Dreaming 的 LLM 流程并行；数据库写入由单写连接串行短事务提交。不可为避免锁竞争而将多个流水线的整个 LLM 处理用全局业务锁串行化。

## 2.2 数据模型：六张业务表

MVP 不另加 Session、Domain、通用 Job、Processing、Deferred、全局 Revision、来源索引或 Map 状态表。

| 表 | 权威含义 | 主键或关键约束 |
|---|---|---|
| `conversation_turn` | 标准化 Turn 及 Extraction 结果 | SQLite 内部自增 `id`；四元业务键唯一；状态仅 `pending/complete/failed` |
| `raw_memory` | 不可变记忆证据 | 业务生成字符串 ID；五字段逻辑模型；来源 JSON |
| `organization_pending` | 尚未治理完成的 Raw ID 集合 | 只有 `raw_memory_id`，外键指向 Raw |
| `organized_memory` | 当前有效认知（可明确表示不确定） | 稳定 Organized ID；Domain JSON；严格递增 `updated_at` |
| `organized_memory_source` | Organized 与 Raw 的权威证据关联 | `(organized_memory_id,raw_memory_id)` 联合主键、双向外键 |
| `dreaming_checkpoint` | Dreaming 已完整处理的内部 Turn ID 连续水位 | 固定 `id=1` 的单行 |

最终逻辑 DDL（由 SQLDelight `.sq` 实际落地，开发时校验生成代码与 JSON1 函数支持）：

```sql
CREATE TABLE conversation_turn (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    runtime_id TEXT NOT NULL,
    agent_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    session_turn_id TEXT NOT NULL,
    timestamp TEXT NOT NULL,
    created_at TEXT NOT NULL,
    messages_json TEXT NOT NULL CHECK (json_valid(messages_json) AND json_type(messages_json) = 'array'),
    extraction_status TEXT NOT NULL DEFAULT 'pending'
        CHECK (extraction_status IN ('pending', 'complete', 'failed')),
    UNIQUE (runtime_id, agent_id, session_id, session_turn_id)
);

CREATE TABLE raw_memory (
    id TEXT NOT NULL PRIMARY KEY,
    content TEXT NOT NULL CHECK (length(trim(content)) > 0),
    generated_by TEXT NOT NULL CHECK (generated_by IN ('EXTRACTION', 'DREAMING')),
    sources_json TEXT NOT NULL CHECK (json_valid(sources_json) AND json_type(sources_json) = 'array'),
    created_at TEXT NOT NULL
);

CREATE TABLE organization_pending (
    raw_memory_id TEXT NOT NULL PRIMARY KEY
        REFERENCES raw_memory(id) ON DELETE RESTRICT
);

CREATE TABLE organized_memory (
    id TEXT NOT NULL PRIMARY KEY,
    content TEXT NOT NULL CHECK (length(trim(content)) > 0),
    domains_json TEXT NOT NULL CHECK (json_valid(domains_json) AND json_type(domains_json) = 'array'),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE organized_memory_source (
    organized_memory_id TEXT NOT NULL
        REFERENCES organized_memory(id) ON DELETE RESTRICT,
    raw_memory_id TEXT NOT NULL
        REFERENCES raw_memory(id) ON DELETE RESTRICT,
    PRIMARY KEY (organized_memory_id, raw_memory_id)
);

CREATE TABLE dreaming_checkpoint (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    last_processed_turn_id INTEGER NOT NULL DEFAULT 0
        CHECK (last_processed_turn_id >= 0)
);
INSERT INTO dreaming_checkpoint (id, last_processed_turn_id) VALUES (1, 0);
```

- `conversation_turn.id` 为 SQLite 内部递增主键，不混同于外部 `session_turn_id`；前者承担 Dreaming 水位，使用 `AUTOINCREMENT` 避免已经使用过的已提交 ROWID 被重用。
- 外部业务键是 `(runtime_id, agent_id, session_id, session_turn_id)`，重复报文幂等校验由 Core 结合唯一索引完成；`created_at` 仅在首次插入时生成。
- `RawMemory.sources_json` 的 ConversationTurnRef / RawMemoryRef 可能涉及跨表及多级引用，**不**添加 JSON 内隐式外键；由 Core 校验来源有效、存在、可追溯及引用环。
- `raw_memory` 无更新与删除业务 API；`organized_memory_source` 权威且不可作为可重建缓存；MVP 不开放物理删除 Organized Memory。
- `organization_pending` 不新增入队序号，不保证严格 FIFO；按照 `raw_memory.created_at ASC, id ASC` 确定性选取。
- `domains_json` 仍为 JSON 字符串数组，不建立独立 Domain 表；字段的业务语义、可用来源、实际数据有效性由 Core 验证。
- 数据库 CHECK 只做结构性校验，不能代替会话、记忆来源及模型结果的业务校验。

## 2.3 索引与查询路径

除自动生成的主键与 UNIQUE 索引外，MVP 使用如下 **5 个显式业务索引**：

```sql
CREATE INDEX idx_turn_session_time
  ON conversation_turn (runtime_id, agent_id, session_id, timestamp DESC, id DESC);

CREATE INDEX idx_turn_pending_session_time
  ON conversation_turn (runtime_id, agent_id, session_id, timestamp DESC, id DESC)
  WHERE extraction_status = 'pending';

CREATE INDEX idx_turn_session_created_at
  ON conversation_turn (runtime_id, agent_id, session_id, created_at DESC);

CREATE INDEX idx_organized_updated_at
  ON organized_memory (updated_at);

CREATE INDEX idx_source_raw
  ON organized_memory_source (raw_memory_id);
```

关键查询路径与必备语义：

**A. 最新 Pending 锚点：按会话发生时间，不按入库 ID。**

```sql
SELECT id, timestamp
FROM conversation_turn
WHERE runtime_id = ? AND agent_id = ? AND session_id = ?
  AND extraction_status = 'pending'
ORDER BY timestamp DESC, id DESC LIMIT 1;
```

**B. 锚点之前连续 N 条会话（包含 Complete / Failed 背景）：**

```sql
SELECT * FROM conversation_turn
WHERE runtime_id = ? AND agent_id = ? AND session_id = ?
  AND (timestamp < ? OR (timestamp = ? AND id <= ?))
ORDER BY timestamp DESC, id DESC LIMIT ?;
```

由 Repository 将结果反序为真实会话顺序。Builder 固定锚点，以 N 为候选窗口长度，整体 Token Budget 超限则只向前端缩小窗口、N 折半重读；选中窗口里 Snapshot 时 `pending` 的 Turn 是 Target。锚点和多次窗口读取必须在一个短 `ReadContext` 事务中形成一致性快照；LLM 调用在 ReadContext 结束之后进行。

**C. Count / Idle：** Count 只统计同一 Session 的 Pending。Idle 必须先从 Pending 部分索引得到候选 Session，再利用 `idx_turn_session_created_at` 检查该 Session 自 `idleBefore` 后**所有状态 Turn**中是否有新入库 Turn；使用 `created_at`，而不是 `timestamp` 或 Pending 最新时间。查询的 `EXPLAIN QUERY PLAN` 应验证索引利用。

**D. Pending 取下一条：**

```sql
SELECT p.raw_memory_id
FROM organization_pending p
JOIN raw_memory r ON r.id = p.raw_memory_id
ORDER BY r.created_at ASC, r.id ASC LIMIT 1;
```

允许 SQLite 为这条查询使用临时排序结构，不为此新增 Raw 时间索引或 Pending 序号。Periodic Trigger 判断存在性仅需 `SELECT EXISTS(SELECT 1 FROM organization_pending)`。

**E. Organized 来源与 Map：** `(organized_memory_id,raw_memory_id)` 主键支持 Organized→Raw；`idx_source_raw` 支持 Raw→Organized。Map 水位取 `SELECT MAX(updated_at) FROM organized_memory`（空表时返回 null）。Domain 过滤使用简单 JSON 查询，不强制 FTS/Embedding/向量索引。

**F. Dreaming 增量：**

```sql
SELECT * FROM conversation_turn
WHERE id > ? AND id <= ?
ORDER BY id ASC LIMIT ?;
```

用 SQLite INTEGER PRIMARY KEY 即可，不额外建索引。扫描由固定上界和 Checkpoint 确定；即使 Turn 的 Extraction 为 pending/failed，也有资格参与 Dreaming。

## 2.4 时间、单调水位与业务 ID

- **数据库所有时间文本字段统一保存上海本地时间**，采用固定毫秒精度、固定 `+08:00` 偏移的 ISO-8601 格式：`yyyy-MM-dd'T'HH:mm:ss.SSS+08:00`，例如 `2026-10-23T18:30:25.123+08:00`。适用于 `conversation_turn.timestamp` / `created_at`、`raw_memory.created_at`、`organized_memory.created_at` / `updated_at`，以及后续新增的同类 SQLite 时间字段。直接打开 SQLite 即可读出上海本地时间，**不能仍存 `...Z` 而仅在展示层转换**。
- Kotlin Core 内部继续用 `Instant` 表示绝对时间、进行时间比较和计算；在 SQLite 写入边界统一格式化为 `Asia/Shanghai` 的 `+08:00` 字符串，读取时解析回 `Instant`。有时区/偏移的外部时间先按其真实时刻归一化，再以 `+08:00` 写入；无时区输入仅在协议允许时按上海时区解释。日志和人类可见时间同样使用 `Asia/Shanghai`；Cron 默认采用上海时区，已有的各任务显式时区覆盖能力保留。
- **格式统一是 SQL 语义前提**：所有时间列及时间范围查询参数（例如 Idle Trigger 的 `idleBefore`）必须使用相同的固定毫秒精度、`+08:00` 偏移和零填充格式。这样 SQLite TEXT 字典序、`ORDER BY timestamp`、`MAX(updated_at)` 以及时间字符串比较才与时间顺序一致。禁止混存 `Z` / 其他偏移 / 不同小数精度；禁止使用 SQLite `CURRENT_TIMESTAMP` 直接写入 UTC 文本。格式化与解析由单一 Kotlin 时间编解码组件执行，并在写入边界校验。
- `RawMemory` 的字符串 ID 继续沿用已定格式 `R-MEM-yyyyMMdd-{8位当天毫秒}-{2位序列号}`；日期、当天毫秒与时钟回拨时的逻辑时刻一律使用 `Asia/Shanghai`。最终唯一性仍由 SQLite 主键兜底，冲突不得覆盖原记录。Organized Memory ID 同样以上海本地日历日/当天毫秒编码；生成器接口及逻辑时间算法见 §3.6。
- `organized_memory.updated_at` 仍在**同一个 WriteSlot 写事务内部**读取当前全局最大值，并在 `Instant` 层分配严格更大的毫秒值：`next = max(clock.instant().truncatedTo(MILLIS), maxExistingInstant.plusMillis(1))`。一个事务更新多条 Organized 时依次推进；提交前统一编码为上海 `+08:00` 文本。`created_at` 是实际插入时间；`updated_at` 兼作逻辑水位，极端时钟回拨时可领先当前墙上时间。
- 包括仅新增 `organized_memory_source` 的 MERGE 在内，所有成功的 Organized 变更都必须推进相应 `updated_at`。Memory Map `sourceUpdatedAt` 使用这一水位，不得因改变序列化时区导致误判 stale。
- Dreaming Checkpoint 仍存 `conversation_turn.id`（非时间），条件推进时校验原水位及严格向前；时区存储规范不改变其 ID 语义。

## 2.5 Core Repository 和显式访问上下文

按业务数据聚合提供 **五个 Repository Port**，不建立无业务意义的 BaseRepository，也不为 `organized_memory_source` 另立 Repository：

| Port | 普通读取/ReadOps | TransactionContext 中的 TxOps |
|---|---|---|
| Conversation | `getById`、业务键查找、Session 窗口、最新 Pending、Count、Idle、ID 范围 | 幂等插入、条件标记 Complete/Failed |
| Raw Memory | ID / 批量精确查询、历史分页扫描 | 批量插入（不可更新删除） |
| Organized Memory | ID / 批量查询、来源正反查、分页扫描、MAX 更新时间 | NEW / UPDATE、插入来源关联、推进水位 |
| Organization Pending | exists、按 Raw 创建时间取下一条 | enqueue、remove |
| Dreaming Checkpoint | 读取单行水位 | 条件更新水位 |

- **普通 Repository** 对上层只暴露独立 `suspend` 读取：Executor 选择一个 ReadSlot，执行同步查询后释放 Slot。多次普通查询之间不保证是同一快照。
- **`ReadContext`**：`database.read { ctx -> ... }` 显式绑定一个 ReadSlot / 一次 SQLite 只读事务；仅提供 `ReadOps`，回调同步且不可挂起，适用于 Map 输入快照、需要跨多次读取的数据一致性场景。
- **`TransactionContext`**：`database.write { tx -> ... }` 显式绑定唯一 WriteSlot / SQLite 写事务；提供 `TxOps`，并继承对应的 `ReadOps`，写事务内部所有读取都使用当前写连接。回调同步且不可挂起。
- Core 仅能看到业务 Port 与 Context 类型，不接触 SQLDelight Driver、JDBC Connection 或 Driver 线程细节。
- **MVP 禁止事务嵌套与 Savepoint**；事务内部直接调用 `tx` 读取，禁止调用会自行调度新的普通 Repository 或 `database.read/write` 的方法；不可将 `ReadContext/TransactionContext` 延伸到回调结束后。

示例（逻辑签名，最终 API 名称可在不改变语义前提下调整）：

```kotlin
interface ContextDatabase {
    suspend fun <T> read(block: (ReadContext) -> T): T
    suspend fun <T> write(block: (TransactionContext) -> T): T
}

interface ReadContext {
    val conversations: ConversationReadOps
    val rawMemories: RawMemoryReadOps
    val organizedMemories: OrganizedMemoryReadOps
    val pending: PendingReadOps
    val dreaming: DreamingReadOps
}

interface TransactionContext : ReadContext {
    override val conversations: ConversationTxOps
    override val rawMemories: RawMemoryTxOps
    override val organizedMemories: OrganizedMemoryTxOps
    override val pending: PendingTxOps
    override val dreaming: DreamingTxOps
}
```

事务执行必须检查 SQL 操作影响行数；不能以忽略结果的 UPDATE 模拟业务状态已经成功变更。

## 2.6 SQLDelight Driver 与固定线程执行器

Persistence 内部维护明确的 Slot：

```text
ContextDatabaseExecutor
  ├─ WriteSlot × 1 ─ 固定执行线程 ─ SQLDelight JDBC Driver/Connection ─ SQLite
  └─ ReadSlot × N  ─ 每 Slot 独立固定线程与 Driver/Connection ─ SQLite
```

- 使用 SQLDelight **同步** JDBC API，不启用 `generateAsync=true`；同一次事务/读快照回调必须在一个固定执行线程同步完成，不能挂起后跨线程恢复。
- 每个 Slot 内顺序执行，不能把同一个 JDBC Connection 分配给多个并发协程。`suspend` 只用来在回调**之外**等待可用 Slot/线程；阻塞 JDBC 不在通用事件循环线程直接运行。
- 每个 Slot 必须验证所选 SQLDelight/Xerial 版本实际的 Driver↔物理 Connection 绑定语义，不能只根据对象名称假定 Driver 恒等于单连接。
- 一笔事务在绑定的写连接中 `BEGIN`、同步执行所有 TxOps，然后 COMMIT；异常或取消导致提交前中断时回滚，不能残留锁与未清理事务。事务不能跨 LLM、HTTP 或 VFS 网络操作。
- WAL 允许读取已提交快照与写入并行，但 ReadContext 必须短时完成，以免长读事务阻碍 WAL Checkpoint。
- 关闭时先停止接受任务、等待/取消正在使用连接的任务并确认结束，再按资源所有权关闭 ReadSlots、WriteSlot 与驱动；不能在阻塞 JDBC 仍访问连接时强行关闭数据库。

## 2.7 写入事务与条件提交

**Conversation Ingestion**：同一个业务键首次 INSERT；完全一致的重复提交返回幂等结果且不更新 `created_at`，同键不同内容按业务冲突报错。成功 COMMIT 后才发送 Count Trigger。

**Extraction**：LLM 结束且整批验证通过后，单写事务原子插入 Raw、enqueue Pending、将本轮 Snapshot 固定的 Target Turn 条件更新为 `complete`；必须验证所有 Target 当前仍为 `pending` 且实际更新数量符合预期。批次为空 Raw 时也正常标记 Target。`N=1` 仍超预算时条件标记锚点为 `failed`。失败回滚，不修改窗口外 Pending。

**Organization**：事务外先检查已有权威来源关联并进行 LLM；提交事务内再次核对来源与 Pending，执行合法 NEW/MERGE/UPDATE、写入来源关联、推进 Organized `updated_at`、删除 Pending。来源已存在时仅清理残留 Pending；不得因校验失败而执行 NEW 或删除 Pending。

**Dreaming**：每批 Topic 发现、取证和反思在事务外；完成后将本批 Raw、Pending、Checkpoint 条件推进一起提交；即使结果为空也允许对真实完成的非空扫描区间推进水位。Checkpoint 原值变化或更新数量异常即整笔回滚。

**跨存储界限**：任何 SQLite 事务不覆盖 VFS `map.md` 发布、资源读写或数据库备份文件写入；Map 从已提交 Organized 派生。事务提交后通知的失败由既有 Cron/Pending/Checkpoint 机制补偿，**不**引入新的持久化业务任务系统。

## 2.8 首次初始化、版本检查与独立升级

**正式定稿：禁止把“数据库文件不存在”视为普通启动时可以自动创建。**

| 场景 | 唯一允许行为 |
|---|---|
| 日常启动，文件不存在 | **拒绝启动**，报告数据库路径和初始化要求，防止错误路径悄悄创建空记忆库 |
| 明确授权首次初始化，文件不存在 | 创建最新 Schema、索引、Checkpoint 与版本标记，然后正常启用数据库 |
| 数据库存在，Schema 版本匹配 | 不执行迁移，正常初始化连接与 Runtime |
| 数据库存在，Schema 版本低于或高于程序版本 | **直接拒绝启动**，不运行自动迁移 |
| 文件损坏、无法识别或版本异常 | 拒绝启动，不覆盖、不删库重建 |
| 需要升级 | 通过显式授权的独立升级流程，先产生有效备份，运行经过验证的 SQLDelight `.sqm` 迁移并核验；失败回滚/停止，不由普通启动隐式执行 |

- 数据库存在性检查必须避免 SQLite 在检查阶段自行创建空文件；可通过只读方式打开并检查文件/Schema 版本。
- 显式授权可由**单独初始化命令或明确的一次性初始化模式**提供；MVP 不要求同时建设两套入口，具体 CLI 命名与调用参数在部署专题定稿。原则是不让日常启动默认启用该授权。
- Schema 版本使用 `PRAGMA user_version` 等 SQLite 原生版本机制。初始化阶段完成必要检查，再开放读写连接、启动 Worker 和 Cron；普通启动不得将 SQLDelight Schema 自动迁移工具当作隐含升级步骤。
- 需要区分 Schema 初始化/迁移与 SQLite 自身的 WAL/Checkpoint 管理。Schema 自动迁移默认关闭不影响 SQLite 正常 WAL 恢复机制。
- `context.db` 备份、恢复、升级过程的完整运维协议在专题 9 定稿，既定前提是权威数据不可直接清空或替换。

## 2.9 测试与验收边界

- **DDL/索引验证**：在所选 SQLDelight、Xerial SQLite 和 SQLite JSON1 版本上执行 Schema 建立、列约束、外键拒绝；通过 `EXPLAIN QUERY PLAN` 验证最新 Pending、连续窗口、Count/Idle、来源反查、MAX 更新时间、Dreaming ID 范围的实际访问路径。微量测试命中索引不替代数据规模测试。
- **上海时区持久化验证**：检查 `timestamp` / `created_at` / `updated_at` 数据库原文均为 `yyyy-MM-dd'T'HH:mm:ss.SSS+08:00`；外部 `Z`、其他偏移输入能够归一化；UTC↔上海转换的绝对时刻不变；SQL TEXT 排序、MAX 水位、Idle 截止时间和跨天 ID 生成在固定格式下正确；拒绝混入 `Z` 或其他精度文本。
- **快照一致性**：两个 ReadSlots 并发读取；WriteSlot 事务未提交时读端不可见；读上下文跨多次查询必须看到稳定快照；不得出现跨 Driver/Connection 的事务泄露。
- **Extraction/Organization/Dreaming 原子性**：插入中途失败、条件 UPDATE 影响行数异常、LLM 后提交失败都不得留存半提交的 Raw、来源、Pending 或 Checkpoint 状态。
- **幂等与约束**：Turn 四元键重复提交、Raw 主键冲突、来源关联重复、外键 RESTRICT、非法 enum/JSON 结构、过期 Checkpoint、严格单调 `updated_at` 和时钟回拨。
- **数据库首次初始化保护**：无授权且文件缺失必须拒绝启动、不新建数据库；显式授权首次创建正确；Schema 版本高/低均拒绝启动且文件内容不变；数据库损坏不得覆盖；升级流程仅通过显式入口测试。
- **Runtime 故障**：读/写回调抛异常释放 Slot；写事务回滚；嵌套事务明确拒绝；关闭中有阻塞 JDBC/LLM 时不得抢先释放被占用资源；COMMIT 成功但触发丢失可由 Cron 扫描发现 Pending。

## 2.10 专题 2 定稿结论与后续边界

**本专题已冻结**：六表结构、五个显式索引、单写与少量独立读连接、WAL+FULL、固定线程 SQLDelight 同步执行、显式 ReadContext/TransactionContext、禁止嵌套事务、SQLite 时间统一以固定毫秒精度 `+08:00` 保存（Kotlin 内部为 `Instant`）、Organization Pending 简单确定性排序、严格递增的 Organized 更新时间水位，以及**显式授权首次创建 / 普通启动不自动迁移**。

留至对应后续专题的**参数和实现载体**：具体 Kotlin/SQLDelight/Xerial 版本、可配置读 Slot 数与超时默认值、初始化命令名称、升级脚本运维方式、完整备份/恢复、HTTP 错误码、LLM Prompt 及 Token Budget。Coding Agent 不可据此自行改变上述数据和事务契约。

---

# 专题 3：基础设施（已定稿）

本专题是 Context Core、Runtime 与各记忆流水线共用的基础设施契约；不新增 Gradle Module、通用任务框架、独立监控数据库或模型网关。具体第三方依赖**版本**在工程搭建时锁定；业务 Prompt、评测阈值及 HTTP 对外协议由后续专题讨论。

## 3.1 LlmClient 统一契约与模块边界

- Core 定义统一 `LlmClient` Port；业务按 `LlmTask` 发起调用，不直接使用 Provider URL、密钥或厂商 SDK。Runtime 实现 **OpenAI-compatible Chat Completions** Adapter，初期使用 **Ktor Client + CIO + kotlinx.serialization**。
- MVP 只有非流式 `complete()`：支持文本/JSON，暂不支持 Streaming、Tool Calling、多模态；不实现 Alcyone 内部多供应商自动容灾、动态负载均衡或自动模型切换。
- Runtime 创建并复用长生命周期 `HttpClient`；请求按选定 Profile 设置 URL、认证及 model。**不得在共享 HttpClient 的全局默认 Header 中放某个 Profile 的 API Key**；关闭由 Runtime 生命周期负责。
- LlmClient 只提供统一的 HTTP/生成结果与协议层错误，不理解 Conversation/Memory 业务语义。Core 构造 Prompt、Schema，并执行来源约束、业务字段和整批校验。

接口示意（最终字段和枚举命名可保持语义后微调）：

```kotlin
interface LlmClient {
    suspend fun complete(request: LlmRequest): LlmResponse
}

data class LlmRequest(
    val task: LlmTask,
    val messages: List<LlmMessage>,
    val responseFormat: LlmResponseFormat,
    val maxOutputTokens: Int? = null
)

data class LlmResponse(
    val content: String,
    val usage: TokenUsage?,
    val finishReason: String?
)
```

`LlmResponse.usage` 可能缺失；不可将缺失 Usage 解释为已确认零 Token。Adapter 应保留 Provider 请求 ID、实际 model、调用耗时和 Attempts 等诊断元数据，供统一日志/统计使用；这不意味着要在 Core 持久化调用记录。

## 3.2 Profile 独立 API 与 Task 路由

**配置层级：`LlmTask → Profile → (baseUrl, apiKey, model, capabilities)`。** 每个 Profile 独立配置地址、密钥、模型和结构化输出能力，可指向 DeepSeek、OpenAI API 或自建/第三方兼容网关。MVP 不要求所有 Profile 共用一个 Gateway。业务只引用任务类型，修改 Profile 或外部网关路由不需修改 Core 代码。

示意 YAML（仅列主要字段，超时和重试有内置默认值，无须反复书写）：

```yaml
context:
  llm:
    profiles:
      fast:
        baseUrl: https://api.example-fast.com/v1
        apiKey: ${FAST_LLM_API_KEY}
        model: fast-model
        contextWindowTokens: 64000
        jsonMode: json_object
      quality:
        baseUrl: https://api.example-quality.com/v1
        apiKey: ${QUALITY_LLM_API_KEY}
        model: quality-model
        contextWindowTokens: 128000
        jsonMode: json_schema
    tasks:
      extraction:
        profile: fast
        maxInputTokens: 12000
        maxOutputTokens: 3000
      governance:
        profile: quality
        maxInputTokens: 16000
        maxOutputTokens: 4000
```

上面的地址、名称、容量和预算均是**配置结构示例**，不是现实模型规格或已决定的模型选择。任务覆盖 Extraction、Candidate Scanner、Governance、Memory Search、Memory Map、Dreaming Topic Discovery / Evidence Scan / Reflection。各任务映射可独立调整：候选扫描/检索通常使用性价比模型，治理/复杂反思可使用质量更高的模型。

- 运行时严格校验任务引用的 Profile 存在、必需字段齐全、上下文容量/预算合法；对支持的协议能力显式配置，不通过供应商名称猜测。SDK 可使用 Kotlin Config，不强制 YAML。
- **约定优于配置**：无差异的超时、重试和日志参数使用内置默认值；仅对特殊模型/任务覆盖。Profile 级设置 HTTP 超时/重试，Task 级设置预算/整体期限；重合参数按更具体的显式覆盖优先，否则用默认值。
- 配置热更新仍不进入 MVP；模型配置调整后重启。外部网关修改别名指向属于其自身管理行为。

## 3.3 TokenEstimator 与 Token Budget

- Core 统一提供可注入 `TokenEstimator`，估算最终渲染 Prompt/messages、消息角色及协议开销、结构化输出 Schema 等；**MVP 使用统一保守估算和可配置安全余量（初始建议约 20%）**，不接入各厂商 Tokenizer。此值不是准确上限保证；根据真实 API Usage 校准估算误差。
- Profile 配置 `contextWindowTokens`；Task 配置 `maxInputTokens`、`maxOutputTokens`。在发送前验证两条限制：业务估算输入不超过任务输入预算；估算输入 + 输出保留（包含可能的内部推理消耗）+ 安全余量不超过 Profile 总上下文容量。安全余量只计一次，避免估算器与预算检查重复加成。
- Core 负责数据选择和超限业务行为，TokenEstimator 只做估算/检查，不决定丢弃什么：Extraction 从最新 Pending 锚点按 Turn 数折半，N=1 本地确定性超限才按既定规则标记 failed；Scanner 分批且不得漏批；Dreaming 证据不静默舍弃重要矛盾；Map 全量输入超限则不发布不完整 Map。
- Runtime 发送前再次确认实际完整请求符合配置边界。Provider 返回 context-length 错误不等于已满足本地 `N=1` 的确定性超预算条件；不能因此擅自把 Turn 标为 failed。
- 具体每个 Task 的预算数、初始窗口 N、估算安全系数可配置，并在业务专题决定实际默认值；不得从上面 YAML 示例直接视为最终模型限制。

## 3.4 JSON 输出兼容与严格校验

Profile 显式声明 `jsonMode`，取值：

| 模式 | Adapter 行为 | 正确性边界 |
|---|---|---|
| `json_schema` | 在兼容的响应参数中提供业务 Schema | 仍须 Core 校验结构与证据 |
| `json_object` | 请求 JSON Object | 仍须 Core 校验完整业务 Schema |
| `prompt_only` | 不发送 response_format，仅在 Prompt 要求 JSON | Core 完全负责解析与校验 |

- Schema 来源于业务模块，Adapter 仅负责协议映射；不同 Provider 的 `max_tokens` / `max_completion_tokens` 等差异可在 Profile 能力配置/Adapter 中适配，不侵入 Core。
- `finishReason=length`、明确拒答、空响应、非法 JSON 或业务结构不合法，必须明确失败；不能把部分结果当成成功。
- **禁止自动降级** JSON mode，禁止自动修复、从 Markdown 代码围栏“猜测性提取” JSON 或部分结果落库。可验证的正常零结果（如 `{"memories": []}`）属于业务成功。
- LLM 负责候选事实和字段，Core 严格验证：Extraction 源 Turn 属于本次固定 Snapshot 且至少有一个 Target；Scanner 返回 ID 必须属于对应批次；Governance 目标属于合法候选集；Dreaming 证据可追溯。任一非法输出遵守各业务既定原子性与失败保留规则。

## 3.5 超时、有限重试和错误分类

分别限制单次 HTTP 请求和完整 `complete()` 生命周期；**请求超时不等于业务流水线重试**。`connectTimeout`、`requestTimeout`、`socketTimeout` 在 Profile 层按需覆盖；`totalTimeout` 在 Task 层按需覆盖且覆盖全部尝试、退避等待。

推荐内置默认值（允许差异覆盖）：

| 参数 | 默认值 |
|---|---|
| 连接超时 | `10s` |
| 单次请求超时 | `180s` |
| Socket 无数据超时 | `120s` |
| complete 总期限 | `360s` |
| `maxAttempts` | `2`（含首次请求，最多一次重试） |
| 初始重试等待 | `1s` |
| 最大自动退避等待 | `10s` |
| Jitter | 开启 |

- 仅对暂时性连接/超时问题、408、临时 429、500/502/503/504 等**可判定**错误进行有限重试；退避采用指数增长与轻量 Jitter；服务端提供有效 `Retry-After` 时优先尊重。若要求等待超过允许总期限/配置上限，则不提前重试，直接失败。
- 对配置、401/403 认证权限、余额不足或确定性配额耗尽、400/404/422 参数错误、JSON 业务校验失败、模型拒答/输出截断等不自动重试。429 应区分暂时限流与确定性额度耗尽，无法安全判断时不做无界重复请求。
- HTTP 超时后服务端可能已生成结果，再提交可能重复计费，所以默认尝试数保持较低；不在 Worker 外叠加另一层无界重试，不自动换 Profile/Provider。
- 统一错误分类建议：`CONFIGURATION / AUTHENTICATION / QUOTA_EXCEEDED / RATE_LIMITED / NETWORK / TIMEOUT / PROVIDER_UNAVAILABLE / INVALID_REQUEST / CONTEXT_LIMIT_EXCEEDED / INVALID_RESPONSE / OUTPUT_TRUNCATED / MODEL_REFUSAL`；异常元数据包括 Task、Profile、HTTP 状态、Provider Code、Request ID、Attempts、Retryable（脱敏）。协议层由 Adapter 分类，业务输出由 Core 校验。
- 主动取消/Runtime 关闭应传播 `CancellationException`，不吞并为超时或追加重试。普通失败保留相应 Extraction Target、Organization Pending、Dreaming Checkpoint；Memory Map 刷新失败保留旧 Map 并标记 stale；Search 报明确失败，不伪造完整搜索结果。

## 3.6 Memory ID、Clock 与上海时间序列化

**ID 设计（单实例 MVP）：** Core 暴露 `MemoryIdGenerator` 接口，Runtime 提供共用一个生成器实例的实现；不依赖外部分布式 ID 服务、不持久化生成器状态、不修改 Memory 数据模型。

```kotlin
interface MemoryIdGenerator {
    fun nextRawMemoryId(): String
    fun nextOrganizedMemoryId(): String
}
```

- Raw ID：`R-MEM-yyyyMMdd-{8位当天毫秒}-{2位序列}`；Organized ID 采用**相同日期/序列规则**但前缀 `O-MEM`；两个表/前缀各自独立主键，Organization NEW 生成新 ID，MERGE/UPDATE 保留既有 Organized ID。Conversation 内部 ID 继续由 SQLite `AUTOINCREMENT` 生成，外部 `session_turn_id` 由 Adapter/Runtime 提供。
- 一个线程安全生成器维护 `lastLogicalMillis + sequence`；本地当前毫秒未超过逻辑毫秒时在当前逻辑毫秒递增两位序号；序号超过 99 则逻辑毫秒推进 1ms 并归零。时钟回拨不能导致进程内重复；按逻辑时刻的**上海日期与当天毫秒**编码。ID 的时间部分仅承担标识功能，不代表事实发生时间或真实保存时间。
- 跨重启不承诺仅凭生成器绝对无碰撞，SQLite 主键负责最终去重；仅在**确认为新生成 Memory ID 主键冲突**时重新取 ID、有限次重试整笔未提交事务，绝不覆盖既有 Memory，也不将普通业务失败当作 ID 冲突。

**时间源与序列化：**

- 注入 JDK `Clock`，生产默认 `Clock.systemUTC()`，测试可用固定/可控 Clock；Core 内部使用 `Instant` 表示绝对时间，不引入复杂 TimeService。`Clock.systemUTC()` **不代表**数据库保存 UTC 文本。
- **SQLite 所有时间 TEXT 一律上海时区 `+08:00` 固定毫秒格式**：`yyyy-MM-dd'T'HH:mm:ss.SSS+08:00`，例如 `2026-10-23T18:30:25.123+08:00`。由 Repository 统一编解码，不把 `Z` 或其他偏移原样存入数据库，外部不同偏移输入先转换成真实 `Instant`。可读日志及管理界面也使用 `Asia/Shanghai`；Cron 默认同一时区，仍允许各任务显式覆盖。
- 固定偏移、毫秒精度和零填充是 SQLite TEXT 的 `ORDER BY` / `MAX(updated_at)` / 截止时间比较正确的前提；数据库查询参数也必须按同一规则编码。禁止 SQLite `CURRENT_TIMESTAMP` 直接写 UTC 字符串。可采用以下 Kotlin 组件：

```kotlin
private val SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai")
private val DB_TIME_FORMAT = DateTimeFormatter
    .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
    .withZone(SHANGHAI_ZONE)

fun toDbTime(value: Instant): String = DB_TIME_FORMAT.format(value)
fun fromDbTime(value: String): Instant {
    val parsed = OffsetDateTime.parse(value, DB_TIME_FORMAT).toInstant()
    require(toDbTime(parsed) == value) { "Non-canonical Shanghai DB time" }
    return parsed
}
```

- `organized_memory.updated_at` 是**严格递增的逻辑水位**：WriteSlot 同一事务内查询当前全局最大值，先将 `Clock.instant()` **截断到毫秒**，再算 `next=max(nowMs, previousMax.plusMillis(1))`；一笔事务多条修改依次推进；最终编码为上海 `+08:00`。这避免纳秒到毫秒序列化后意外产生重复水位。仅增补来源关系的 MERGE 也要推进水位；`created_at` 则记录实际生成时刻。
- LLM/HTTP 耗时与重试剩余时间使用单调计时，不用墙上时间差；不因本机操作系统时区变化修改数据库格式。
- 如果历史数据库已有 `Z` 字符串，必须经过专题 2 的显式授权、备份后的版本迁移，**不能普通启动时静默混写两种表示**。

## 3.7 日志与隐私

- Core 使用 **SLF4J API**；Standalone Runtime/Server 推荐 **Logback** 作为具体日志实现，默认向 stdout 输出结构化 JSON（日志显示上海 `+08:00` 时间）。作为嵌入式 SDK 时不强制宿主应用换用 Logback，不自动安装第二个日志后端；JSON Encoder 的具体实现依赖版本留工程阶段校验。
- `INFO` 默认级别。`DEBUG` 记录安全的预算估算与批次元数据；`INFO` 记录启动与重要业务批次完成；`WARN` 记录可恢复技术失败；`ERROR` 记录 Worker 异常退出、关键数据库/运行资源不可用。合法空批次/无新增记忆不算错误。
- 推荐统一事件名，例如 `extraction.completed`、`organization.failed`、`dreaming.completed`、`llm.call.failed`，并用轻量 `operationId` 串联一轮执行和多个 LLM 请求。记录 component、Task、Profile、模型、Duration、批次量、分类后的错误及 Provider Request ID。Operation ID 只是关联日志的临时标识，**不是新的持久化任务 ID**。
- 默认严禁记录 API Key、Authorization、完整 Prompt、模型原始响应、Conversation 原文和 Memory 内容；不因开启 DEBUG 自动写出私密文本。详细调试留给后续受控诊断模式，不进入 MVP 默认流程。
- 日志收集、轮转与留存由 systemd/Docker/部署环境管理；Context 不引入独立日志持久化表或默认远程采集服务。

## 3.8 LLM 调用统计与运行状态

**进程内累计指标**（不新建统计表、重启即清零）：按 `LlmTask + Profile` 分组（必要时附 model），记录逻辑 `complete()` 调用数、实际 HTTP Attempts、成功/失败次数、已返回 Usage 的输入/输出 Token、估算输入 Token 和累计耗时。Usage 缺失表示**未知**而非实际零消耗；超时的 Attempts 可能已经产生不可见账单，不从估算得出“实际计费”结论。MVP 不实现自动货币成本核算。

**状态入口**：`ContextRuntime.getStatus(): ContextStatus`（可为 `suspend`），由 Runtime 汇总生命周期、流水线进程内状态、LLM 指标和**按需查询**的 SQLite 权威业务数据。可供 SDK 直接调用；HTTP 只做映射，具体鉴权/路由放专题 9/Server 实现。

- Runtime 生命周期复用 §1.8 的 `CREATED/STARTING/RUNNING/STOPPING/CLOSED`，不新建第二套生命周期。
- Worker 简单执行态 `IDLE/RUNNING/STOPPED/FAILED`：单次业务任务失败但 Worker 继续运行时记录 `lastError`，**不**标为 FAILED；仅 Worker 意外永久退出、无法消费后续任务时标为 FAILED 并发 ERROR 日志。
- Worker 内存记录 `lastStartedAt`、`lastCompletedAt`、`lastSuccessAt`、脱敏 `lastError`；不额外持久化 Worker 状态或实施无条件自动重启。
- 数据库权威指标按需读取：Conversation pending/failed 数、Organization pending 数、Organized 总量、Dreaming Checkpoint、Memory Map 是否 stale（VFS map.md 的水位与 `MAX(updated_at)` 比较）。内存调度与 SQLite 无须组成跨存储原子快照，状态附采样时间即可；若数据库查询失败，应明确标注该部分不可用，不能返回伪造的零待处理数。
- 健康接口与详细管理状态区分；管理状态需鉴权，不允许公网匿名暴露。Runtime 不要求启动时逐一在线探测所有 LLM Provider，也不部署 Prometheus、Grafana 或独立监控代理。
- 在 Worker 业务边界隔离异常，保持其他流水线正常；不能因使用 `SupervisorJob` 就默认不可恢复的 Worker 会自动重建。当前不增加独立 Worker 自动重启系统。

**默认配置**：日志 `INFO`、独立运行模式 JSON stdout、上海时区、LLM 统计及 Worker 状态开启、完整 Prompt 日志关闭；普通 YAML 可全部省略这些项，仅在有特别需求时覆盖。

## 3.9 验收测试要求

1. **多 Profile 路由**：同一进程下不同 Task 调用独立 baseUrl/API Key/model；共享 HttpClient 不串用密钥；未知 Profile、密钥缺失启动校验失败并脱敏。
2. **预算/JSON**：保守估算覆盖 Schema 与安全余量；Scanner 分批不漏检、Extraction N 折半；三种 JSON 模式按 Profile 发送，不支持时明确失败不降级；非法内容不部分保存；空合法结果正常处理。
3. **重试/取消**：可控 Fake HTTP 模拟临时 503、429+Retry-After、认证失败、超时、服务端延迟及取消；验证 Attempts 上限、totalTimeout 与关闭取消；不把取消吞为普通失败、不重试非临时错误。
4. **ID/时间**：同一毫秒多次生成、超过 100 ID、并发、时钟回拨、上海跨日；SQLite 全部时间 TEXT 严格符合 `+08:00` 毫秒格式，UTC↔上海代表相同绝对时刻；`updated_at` 在纳秒输入、时钟回拨及同事务多记录下仍全局严格递增。
5. **日志/状态**：校验日志不出现 Key/Prompt/原始 Conversation；`complete` 与 Attempts 统计区分，缺失 Usage 不填零；任务失败不误标 Worker FAILED，Worker 异常退出可观测；数据库不可用显示未知/故障而不是零 Pending。
6. **集成边界**：真实 SQLite 验证错误后的 pending/checkpoint/Map 不变量，Worker 并行时 SQLDelight 写事务与短快照规则不受 LLM 重试影响。

## 3.10 专题 3 定稿结论与后续边界

**本专题已冻结**：统一 LlmClient、Ktor Client+CIO+kotlinx.serialization、每 Profile 独立 API、Task→Profile 路由、可省略默认参数的配置风格、保守 TokenEstimator、Profile 容量与 Task 预算、三类 JSON 输出、调用级有限重试与分类错误、MemoryIdGenerator/Clock、SQLite 上海 `+08:00` 时间序列化、SLF4J+Logback（SDK 只依赖 API）、进程内统计及 `ContextRuntime.getStatus()`。

**不在 MVP 中实现**：各厂商专属 Tokenizer、内建大模型网关/自动切换、Streaming/Tool Calling、多模态、JSON 自动修复/自动降级、通用重试框架、全量 Prompt 日志、持久化 LLM 统计数据库、独立监控平台、复杂 Worker 自愈。模型 Prompt/具体 Task 参数、管理 HTTP 路由、费用报表与真正的 Provider 兼容性测试，可在后续业务、部署与验收专题落实，但不得改变上述已定稿边界。

---

# 专题 4：Conversation Ingestion（已定稿）

本专题复用《核心数据流与接口设计》§3～§6 的 Conversation Turn v0.1 和专题 2 的 SQLite 事务契约。**一条完整逻辑 Turn 一次提交；接收成功仅表示数据库持久化，不代表记忆提取完成。** 以 Kotlin SDK 为业务入口，HTTP 仅为有认证的薄适配层；不新增数据库表、独立消息队列、持久化发送队列或框架专属 Core 逻辑。

## 4.1 统一输入协议与来源标识

```json
{
  "runtime_id": "hermes-cloud",
  "agent_id": "quant",
  "session_id": "session-123",
  "session_turn_id": "turn-45",
  "timestamp": "2026-10-23T20:15:00+08:00",
  "messages": [
    {"role": "user", "content": "暂停策略 A 的研究。"},
    {"role": "assistant", "content": "已了解。"}
  ]
}
```

- 顶层六字段均必填。`runtime_id` 是跨进程重启稳定的**部署实例 ID**，不是 PID；`agent_id` 是 Runtime 内部 Agent/Profile 标识，**不是** Memory Domain；`session_turn_id` 必须在相同 Session 发生历史重放、网络重试时稳定不变。
- SessionKey = `(runtime_id, agent_id, session_id)`；Turn 唯一业务键 = `(runtime_id, agent_id, session_id, session_turn_id)`；SQLite `conversation_turn.id` 为内部自增 PK，绝不允许外部客户端提供。
- `runtime_type`（如 hermes/pi）是 Runtime 注册元信息，不重复存入每条 Turn；不向 Turn 协议增加 `user_id`、部分执行状态、批次标识或框架原生字段。
- `messages` 是按真实顺序排列的文本消息数组，支持 `user` / `assistant` / `tool`；不要求必须恰好一问一答，Tool 为可选采集项。MVP 不承载多模态、工具结构化调用与推理轨迹。

## 4.2 Adapter 与完成边界

- Hermes、Pi 等 Adapter 运行于各自框架或独立接入项目中，负责监听原生事件、切分**已经结束的完整逻辑轮次**、生成稳定的 ID 与时间、选择 Tool 消息、标准化文本，并通过 SDK/HTTP 提交；Context Core 不解析框架原生事件。
- **不提交流式 Token、临时中间片段或未确认完成的 Turn**。执行中断时优先通过源框架已有的持久化 Session/历史记录重新组装完整 Turn；无法恢复时不伪造已完成内容。
- 不支持同键“先上传部分内容、随后覆盖为完整内容”的更新协议。Adapter 若首次从源事件补充了缺失时间，重试、重放时必须复用稳定时间与内容；不能每次重试重新使用当前时间或随机 Turn ID。
- Adapter 可以选择不采集过长的 Tool 消息，但不得要求 Ingestion 对已经提交的消息正文静默删改、截断或摘要。
- 由源框架负责会话事件的完整性与可重放性；Context 只保证已成功 COMMIT 的 Turn 不因后续学习失败而丢失。Adapter 没有可靠历史来源时，MVP 不承诺断电后自动补发。

## 4.3 输入校验、大小上限及时间规范

- 四个业务标识必填、非空白，建议默认各最长 **256 字符**；`timestamp` 必须是有效且**明确携带时区偏移**的 ISO-8601 时间；`messages` 必须非空，`role` 属于受支持枚举，`content` 必须为字符串（允许合法空字符串，不自动 trim）。
- 内置可覆盖默认：单 Turn 最大 **2 MiB（UTF-8）**、最多 **256 条消息**、单个业务 ID 最长 **256 字符**。HTTP 在读取/反序列化完整请求体前限制字节数，SDK 同样在业务入口检查规范化后有效载荷；超限明确拒绝，不能自动截断、拆分或丢失消息。
- **Ingestion 上限与 Extraction Token Budget 完全分离**：只要接收协议允许，即使后续模型无法处理，仍应完整保存；仅 Extraction Builder 的 `N=1` 预算判定才可依已定规则标记对应 Turn `failed`。
- 外部 `timestamp` 先按给定 Offset 解析真实时间，然后统一格式化为 SQLite 规定的 `yyyy-MM-dd'T'HH:mm:ss.SSS+08:00`。`created_at` 仅由服务端首次 INSERT 时用注入的 Clock 生成、同样以上海偏移存储；重复提交不刷新它。文本消息按原样保留、消息顺序不得改变。
- 比较和保存时使用确定性、规范化的业务字段序列化；JSON 文本的空白、对象字段顺序及等价时间偏移不构成业务内容差异。Ingestion 不预先筛选事实价值、不调用 LLM、不查询历史 Raw Memory。

## 4.4 幂等语义与单写事务

- 四元业务键不存在：写入新行，`extraction_status=pending`，返回 `CREATED` 和内部 `id`。
- 同键已存在，且**规范化的 `timestamp` 与按顺序的 `messages(role,content)` 全部相同**：返回 `DUPLICATE` 与原 `id`；不更新任何已存字段、不刷新 `created_at`、不重置 Extraction 状态，也不产生第二次 Count 通知。
- 同键已存在但上述内容有差异：返回 `TURN_CONFLICT`，不静默覆盖；比较**不包含**内部 `id`、`created_at`、`extraction_status`。
- 使用专题 2 的 `database.write { tx -> ... }` 在一个短事务内执行按唯一业务键查找、比较及 INSERT；数据库唯一约束负责最终防并发。若出现唯一冲突的竞争，重新读取权威记录并按内容判断 Duplicate/Conflict，不生成第二条。不能跨该事务执行网络调用或 Trigger。
- **SQLite COMMIT 是接收成功承诺边界**。COMMIT 失败不返回 CREATED；若服务端无法确定结果是否已提交，调用方使用同一业务键和内容重试查询。HTTP 断开但已经 COMMIT 的场景会安全返回 DUPLICATE。

## 4.5 COMMIT 后 Count Trigger 通知

- 仅首次 `CREATED` 且已成功 COMMIT 才向后续 Count Trigger 发送异步处理通知，携带 SessionKey；不让 Ingestion 同步执行 Count 判断、Extraction 或 Organization。
- Count 通知失败**不回滚**已提交 Turn，也不改变已完成的接收语义；记录脱敏异常。已存 `pending` 可由正常 Cron Idle Trigger 后续扫描重新发现，不建设 StartupRecovery 或持久化 Trigger Journal。
- 完全重复请求不刷新触发状态，不新发通知；Semantic Trigger 仅为未来保留的扩展点。

## 4.6 SDK、HTTP 接口与错误映射

```kotlin
interface AlcyoneContext {
    suspend fun ingestConversation(turn: ConversationTurn): IngestResult
}

data class IngestResult(val id: Long, val status: IngestStatus)
enum class IngestStatus { CREATED, DUPLICATE }
```

- Kotlin SDK 面向可信调用方直接使用该能力；Core 定义唯一 Ingestion 业务 Service，`context/runtime` 对外暴露 SDK，`context/server` 复用同一服务，不建立两套校验与幂等逻辑。
- HTTP MVP 使用 `POST /v1/conversations/turns`，Bearer API Key + JSON 请求；`201 CREATED`、`200 DUPLICATE`（两者返回同一原始 `id`）、`400 INVALID_TURN`、`401` 未认证、`403` Runtime 身份不匹配、`409 TURN_CONFLICT`、`413 PAYLOAD_TOO_LARGE`、`503 PERSISTENCE_UNAVAILABLE`、`500 INTERNAL_ERROR`。
- HTTP 解析、鉴权和错误码转换仅由 Server 负责；Core 只报告具名业务/持久化异常，不依赖 Ktor/HTTP。所有返回均不泄露原文、API Key、内部连接信息。

## 4.7 静态 Runtime 注册和来源认证

- **按 `runtime_id` 粒度静态注册**，配置保存 `runtimeType` 和独立 API Key；同一 Runtime 内新增 Agent/Profile 不需要增加注册记录，也不在 SQLite 创建 Runtime/Agent 注册表。配置使用环境变量与启动时加载，MVP 不支持 HTTP 动态注册或热更新。
- 身份注册归 `server.ingestion.clients`；例如：

```yaml
server:
  ingestion:
    clients:
      hermes-cloud:
        runtimeType: hermes
        apiKey: ${HERMES_INGEST_KEY}
      pi-mac:
        runtimeType: pi
        apiKey: ${PI_INGEST_KEY}
```

- Server 通过可信配置校验 Bearer Key，取得其绑定的 `runtime_id`，再核对请求体中的 `runtime_id`；未经认证不能仅凭请求自报 ID 写入其他 Runtime 的会话。`agent_id` 由已认证 Runtime/Adapter 自主管理，不作为 Domain/用户授权边界。
- SDK 在同进程可信调用下不经过 HTTP API Key，但由宿主保证传入来源标识可靠；身份隔离、多用户权限/动态密钥管理均非 MVP。服务端 API Key 应配合 HTTPS 使用，禁止写入日志。
- 删除 Runtime 静态注册不删除历史 Turn；`runtime_type` 不作为现有 SQLite 中的持久化字段，故无法仅凭历史库恢复删除前的类型注册内容，此限制为 MVP 接受。

## 4.8 Adapter 请求重试与异常恢复

- Adapter HTTP 仅对短暂网络故障、请求超时及临时服务失败执行**有上限的重试**；始终复用相同四元业务键、时间与 messages；`201 CREATED` 和 `200 DUPLICATE` 都视为成功。
- `400/409/413/401/403` 等确定性拒绝停止自动重试，要求修正输入、冲突、权限或体积；暂时性 `503` 等可有限重试。Adapter 失败后如源框架能重放历史，可在后续同步重试，不额外为 Adapter 建立持久化发送队列。
- API 成功 COMMIT、响应丢失后重试得到 DUPLICATE 为预期行为；未知结果不能伪造已接收或抛弃稳定标识。严重数据库故障由 Runtime 运行状态与日志显式报告，不把持久化失败当成业务重复成功。

## 4.9 测试与验收

1. 标准 Turn 入库/重复/冲突、等价偏移时间幂等、JSON 字段顺序与空白不影响比对、消息顺序变化形成冲突；已 complete 的 Turn 重放不会重置状态。
2. SDK 和 HTTP 对相同业务输入采用同一校验规则；超 2 MiB/256 条消息/ID 长度上限均拒绝，SQLite 无部分记录；只超过 Extraction Token Budget 的 Turn 仍可正常入库。
3. 并发同键首次提交及网络超时重试不重复建行；成功 COMMIT 后 Trigger 故障仍返回成功；COMMIT 不确定时通过原业务键重试。
4. API Key 与 runtime_id 绑定：未认证、身份不匹配、配置中多 Runtime、增加 Agent Profile 均有对应测试；SDK 可信入口不强制 HTTP 认证。
5. Adapter 中断、工具大输出取舍与来源重放测试，不上传未完结 Turn、不截断已提交 messages，也不产生新的 session_turn_id。

## 4.10 专题 4 定稿边界

**本专题已冻结**：Conversation Turn v0.1 保持六字段和文本消息；Adapter 仅提交完整逻辑轮次并保留稳定 ID；静态 Runtime 注册和逐 Runtime API Key；SDK/HTTP 共用 Service；校验与可省略的默认大小限制；按规范化时间与有序消息精确幂等；单写短事务和 SQLite 唯一兜底；COMMIT 后首次创建才异步通知 Count Trigger；明确 HTTP 响应和 Adapter 有限重试。

**非 MVP**：部分 Turn 覆盖写入、原生框架数据的通用恢复框架、动态 Runtime 注册/Agent 注册表、多用户管理、持久化发送队列、跨进程 Trigger Journal、自动截断/摘要 Tool 结果，以及异步入库前的 Extraction 或智能过滤。各框架具体 Hook/稳定轮次派生算法、生产 API Key 生命周期以及复杂公网部署安全参数在对应 Adapter/部署专题落实。

---

# 专题 5：Memory Extraction（已定稿）

本专题复用《核心数据流与接口设计》§6～§10 已确认的业务语义，以及专题 2 的 SQLite 读写事务、专题 3 的 LLM / Token Budget / 时间服务。**Trigger 决定何时执行，Builder 决定本次窗口，Extractor 判断提取结果，Writer 负责原子提交。** 不建设新的任务表、状态表、Snapshot 表或独立 Journal。

## 5.1 总体流程与模块边界

```text
Conversation Ingestion COMMIT
    ├─ 新增 Turn → Count Trigger：按 Session 统计 pending
    └─ Cron → Idle Trigger：扫描空闲且有 pending 的 Session
                  ↓
           Extraction Scheduler
     [进程内 Trigger Counter + SessionKey Channel]
                  ↓
         全局 Extraction Worker × 1
                  ↓
        PreExtractionContextBuilder
    [固定最新 Pending 锚点 / 连续窗口 / Token 预算]
                  ↓
       Extraction LLM（事务外）
                  ↓
       整批 JSON / 来源 / Target 校验
                  ↓
       context.db Write Transaction
 [Raw + organization_pending + Target complete]
                  ↓
       COMMIT 后通知 Organization
```

MVP 的 Worker 全局串行，不把网络调用、Prompt 构造或 LLM 处理放进 SQLite Write Transaction；SDK/HTTP Ingestion 只承诺 Turn 的接收与 COMMIT，不等待记忆提取。

## 5.2 Count 与 Idle Trigger

- **Count Trigger**：仅对 Ingestion 首次 `CREATED` 的 Session 进行异步检查，按 `(runtime_id,agent_id,session_id)` 统计 `extraction_status='pending'` 的数量，达到阈值才提交 `SessionKey`。默认阈值建议 `5`，可配置；它不决定本次处理的 Turn 数量，不包括 complete/failed。
- **Idle Trigger**：`cron-utils + Coroutines` 定期读取至少有一条 pending 的 Session；只有该 Session **所有状态** Turn 中最新的 `created_at <= now - idleTimeout` 才触发。不得使用会话事实发生时间 `timestamp`，也不得只检查最新 Pending。查询由 Pending 部分索引驱动，避免全历史表聚合；SQL 截止时间参数使用数据库固定毫秒、`+08:00` 规范。
- 默认建议 `idle.enabled=true`、`idleTimeout=10m`、Cron 为 `*/5 * * * *`、时区 `Asia/Shanghai`；参数均有内置默认值，普通 YAML 无需写出。Cron 上一次扫描尚未结束时跳过重叠扫描，不启动平行的同类扫描。
- Trigger 只调用 `scheduler.request(SessionKey)`，不携带 Turn ID、Snapshot 或 Raw 数据；Semantic Trigger 仅预留扩展能力，本期不实现。
- 新 Turn COMMIT 后的 Count 通知失败不回滚 Ingestion；Cron Idle 可以重新发现遗留 Pending。普通启动不做专门 StartupRecovery。

## 5.3 Scheduler：单 Worker、按 Session 触发计数

```kotlin
interface ExtractionScheduler {
    suspend fun request(sessionKey: SessionKey)
}

// 实现关键状态（示意）
val pendingTriggers = mutableMapOf<SessionKey, Int>()
val taskQueue = Channel<SessionKey>(Channel.UNLIMITED)
val mutex = Mutex()
```

- 每次有效 Trigger 对该 Session 计数 `+1`；仅从 `0→1` 时入队一次，同 Session 在排队、执行期间均不能重复入队或并发执行。`Int` 计数必须防溢出。
- 一个全局 Worker 读取队列，一次取到 `SessionKey` 执行一次 `MemoryExtractionService.execute(sessionKey)`；执行完成或非取消异常时均消费一次计数。若还有计数，则在锁保护下重新排到队尾；计数为零则移除该 Session。
- `Mutex` 保证计数更新、入队/再次入队与结束判断的一致性；在锁内只做短同步状态操作与非阻塞 Channel `trySend`，并处理已关闭 Channel，不在锁内执行 SQLite/LLM 或其他挂起调用。
- Worker 在任务边界记录并隔离普通异常，继续处理其他 Session；**失败仍消费该次触发机会**，相关 Turn 保持 pending，等待以后自然 Trigger。CancellationException 继续传播，取消清理的计数维护须处于短而安全的同步边界。
- Worker 不自行扫描数据库和重复执行失败任务；Trigger Counter 和队列仅属于进程内状态，进程重启不要求恢复历史触发次数。当前不增加持久化 processing/lease 状态。

## 5.4 PreExtractionContextBuilder：锚点、连续窗口和快照

```kotlin
interface PreExtractionContextBuilder {
    suspend fun build(sessionKey: SessionKey): ExtractionContext?
}

data class ExtractionContext(
    val sessionKey: SessionKey,
    val turns: List<ConversationTurn>
)
```

- `ConversationTurn` 是包含 SQLite `id`、归一化 `timestamp`、messages、读取时 `extractionStatus` 的内部记录；`turns` **按真实会话顺序排列**。仅 Snapshot 时 `pending` 的 Turn 是本轮 Target，`complete/failed` 只能提供 History；不额外维护 `targetTurnIds`、`historyTurns`、`recentMemories` 字段。
- 使用一个**短 `database.read { ReadContext }`** 完成所有读取与预算折半：先以 `timestamp DESC,id DESC` 寻找当前 Session **最新 pending** 锚点；再用 `(timestamp < anchorTimestamp OR (timestamp=anchorTimestamp AND id<=anchorId))` 限制连续窗口的右端，以 `timestamp DESC,id DESC LIMIT N` 向前读取并反序。**不能直接按内部自增 ID 当成会话顺序，也不能跳过中间 Complete Turn 只选 Pending。**
- Builder 一次构建过程的锚点和多次折半重读必须共享同一个 SQLite ReadContext 快照；Adapter 在折半过程补录历史时不会混入本轮。ReadContext 回调同步、不可挂起，不调用 LLM 或等待网络；返回不可变 `ExtractionContext` 后释放读事务。
- 窗口只含一个 Session，不加载已有 Raw Memory，不进行提取前语义去重或跨 Session 追溯；模型不提供二次扩展历史接口。

## 5.5 Token Budget 与窗口折半

- 初始窗口上限 `initialWindowTurns` 默认建议 `32`，可配置。读取本轮 N 条后，使用**真实渲染的完整 LLM 请求**估算：System Prompt、会话角色和文本、内部 Turn ID/时间与 Target 标记、Schema/协议开销、输出预留、安全余量都应计算。复用专题 3 的 `TokenEstimator`、Task/Model Budget，不单独实现 Tokenizer。
- 若超预算：`N = max(1, floor(N/2))`，保持同一锚点和同一只读 Snapshot 重新读取连续窗口；**首次满足即固定**，不追求最大可用 N，也不静默截断任何 Turn 或消息。
- 若 `N=1` 且仅包含一个 Turn 后仍确定性超过允许预算：Service 在短写事务中仅将该锚点 `pending→failed`（条件更新并验证影响行数），随后重新定位下一条最新 Pending 继续尝试；原始 Conversation 保留。只要有其他 Pending，不能因为一个超长 Turn 阻断后续处理。
- **固定 Prompt/Schema/输出预留在空窗口时就超过模型容量**属于模型/配置错误，不得误把用户 Turn 标为 failed。Provider 实际返回 Context Limit 错误或网络/估算不确定故障，也不等价于本地已确认的单 Turn 确定性超限，Target 保持 pending。
- 若没有 Pending，Builder 返回 `null`、本轮不调用 LLM。长期持续新增更晚 Pending 可能造成旧 Pending 延迟处理，MVP 接受，公平性策略及超长单 Turn 分段仅列后续 TODO。

## 5.6 Extraction Prompt 与输出契约

- Core 维护单一、版本化的 Extraction Prompt 资源（如 `prompts/extraction-v1.md`），MVP 不建 Prompt 数据库、在线模板编辑和多轮模型修复流程。System Prompt 覆盖已经确认的**价值优先、忠实来源、完整原子化、关注重要变化、批内去冗余、证据可追溯**六原则；显式区分 Target/History，禁止把未经用户认可的 Assistant 建议冒充用户决定。
- 按 Snapshot 实际发生顺序渲染 Turn，向模型提供 SQLite 内部 `id`、原始发生时间、`target` 标记及消息角色/正文。会话原文只作为**待分析数据**，不能覆盖 System Prompt。时间敏感事实应在有依据时写出事件/生效/确认时间；`RawMemory.createdAt` 只代表系统写入时刻，不得冒充事实发生时间。无依据的具体时间不能补造。
- LLM 只能输出下述 JSON 业务内容（任务的 `jsonMode` 由 Profile 决定，Core 负责严格解析、校验）：

```json
{
  "memories": [
    {
      "content": "用户决定暂时停止策略 A 的研究。",
      "source_turn_ids": [1001, 1003]
    }
  ]
}
```

- `memories` 必填数组，可为 `[]`（本批正常成功、并完成全部 Target）。每条 `content` 必填且非空白；`source_turn_ids` 必填、非空整数数组。不得由模型生成 Raw ID、`generatedBy`、`createdAt`、`domain`、`confidence`、`type` 等系统字段。

## 5.7 Core 严格校验与来源映射

- 由 `ExtractionResultValidator` 一次校验全部 JSON：必要字段与类型、结构合法、记忆内容非空、每个来源 ID 都属于**本轮固定 Snapshot**，每条记忆**至少引用一条本轮 Snapshot 时 pending 的 Target**；可额外引用同窗 Complete/Failed History。窗口外、不存在、其他 Session 的 ID 全部拒绝。
- 对重复的 `source_turn_ids` 可按首次出现顺序确定性去重；**不自动修复 JSON、猜测缺失 ID、按内容重写或自动合并多条语义相近记忆**。任一候选不合法则**整批拒绝**；`memories:[]` 是合法成功，不能误认为执行失败。
- 每个合法来源通过 Snapshot 记录转换为完整、稳定的 `ConversationTurnRef(runtimeId,agentId,sessionId,sessionTurnId)`；SQLite 内部 Turn ID 仅用于本次 LLM 引用，不长期存入 `sources_json` 作为唯一来源。Core 统一填充新 Raw 的 `id`、`generatedBy=EXTRACTION`、`createdAt=clock.instant()` 与规范化 `sources`；不新增 Raw 字段。
- 校验确保**结构与引用确定性**，无法机械证明全部语义忠实；事实/观点/推测与重要变化由 Prompt 和后续评测承担。LLM 返回非法 JSON、截断、拒答、无效来源或网络错误时都保持 Target pending，不允许部分 Raw 落地。

## 5.8 SQLite 原子事务与通知

```text
LLM 返回并整批校验成功
    → Core 生成所有 RawMemory 的系统字段
    → database.write { tx ->
          插入本批 raw_memory（允许 0 条）
          插入各条 organization_pending
          仅将本轮冻结 Target 条件更新 pending → complete
          检查所有影响行数和前置状态
      }  // COMMIT
    → 新增 Raw > 0 时通知 Organization Immediate Trigger
```

- 全部 LLM 调用和校验均在写事务外完成，SQLite 同一事务保证 Raw、Pending、Target `complete` **全有或全无**；零候选只提交 Target 状态更新。
- 最终提交时依 Snapshot 固定的 Target ID 集合做条件更新，并确认更新行数完全匹配；快照后新增的 Turn 与窗口外 Pending 不得顺带完成。如果任一 Target 在 LLM 期间被其他调用改为非 pending，则整笔回滚，不保存任何 Raw。
- 新生成 Raw ID 的 SQLite 主键冲突，且能明确识别为**本次 ID 冲突**时才重新生成并有限次重试整笔未提交事务；不能 `INSERT OR REPLACE` 覆盖证据，也不能把一般数据库错误一律重试。
- COMMIT 后仅当新增 Raw/Pending 才唤醒 Organization。若通知丢失/失败，记忆和 Pending 已权威入库，后续 Periodic Cron 检查可恢复；不得因为通知失败重新 Extract 或将 Target 改回 pending。
- 不创建 Extraction Journal、批次幂等键、Snapshot 表、VFS Raw JSON、部分完成状态或独立分布式事务。

## 5.9 失败规则与验收测试

| 事件 | 预期行为 |
|---|---|
| 无 Pending | 不调用 LLM，正常空执行 |
| 本轮 `memories: []` | 无 Raw/Pending，全部固定 Target 原子标记 complete |
| JSON、引用、Target 来源校验失败 | 全批不保存，Target 全部维持 pending |
| 模型调用超时、拒答、输出截断 | 不落地候选，Target 仍 pending |
| 单 Turn 本地确定性超预算 | 仅该锚点条件更新 failed，继续寻找其他 Pending |
| 配置/固定 Prompt 超预算或 API Context Limit | 不误标记 failed，报告技术故障 |
| COMMIT 前数据库出错 | 全部回滚，无部分 Raw/Pending/complete |
| COMMIT 后通知异常 | 已提交结果仍成功，Periodic Cron 补偿 |

验收至少覆盖：Count 单 Session 统计、Idle `created_at` 空闲规则、触发并发/去重与关闭竞态；同时间戳 `(timestamp,id)` 排序、历史补录不改变固定快照、32→16→8→4 折半、没有 Pending 与超限继续处理；不同 Target/History 来源映射、零数组、非法 JSON/外部来源/空 content、批次拒绝；LLM 期间新 Turn 到达及 Target 状态变化、Raw ID 主键碰撞、事务任一步失败回滚、COMMIT 后通知失败。测试使用 Fake LlmClient + 真实 SQLite，Prompt 语义评测与指标阈值在专题 10 实施。

## 5.10 专题 5 定稿边界

**本专题已冻结**：Count + Cron Idle Trigger；Channel/Mutex/每 Session Counter 与单 Extraction Worker；最新 Pending 锚定的短 ReadContext 连续快照；完整 Prompt/Token 预算、首次满足为止的 N 折半；仅 `N=1` 的确定性超限标记 failed；单一版本化 Extraction Prompt；`content + source_turn_ids` 结果和严格整批校验；稳定 ConversationTurnRef 来源；Raw/Pending/Target 的 SQLite 同事务提交以及成功后 Organization 唤醒。

**非 MVP**：Semantic Trigger 模型、提取前 Raw 语义检索、复杂 Prompt 注册/修复、按需扩展历史、分段超长 Turn、跨批次语义去重、通用任务重试框架、持久化处理队列/Journal、启动 Recovery、并行 Extraction Worker、自动部分成功恢复；语义质量评测参数在专题 10 定稿。

---

# 专题 6：Memory Organization（已定稿）

本专题细化《核心数据流与接口设计》§13 的 MVP。**唯一权威数据是 Context SQLite**：`raw_memory` 不可变；`organization_pending` 仅保存未治理的 Raw ID；`organized_memory` 表达当前有效认知（含有依据的明确不确定状态），`organized_memory_source` 保存完整证据关联。治理每条 Raw 只有一个主要 `NEW / MERGE / UPDATE` 动作。不增加 `deferred`、`processing`、冲突状态表、治理历史版本或持久化任务队列。

## 6.1 处理流水线与模块职责

```text
Extraction / Dreaming COMMIT (Raw + organization_pending)
  -> Immediate Trigger；Cron Periodic 为遗留 Pending 提供补偿唤醒
  -> Organization Scheduler（一个进程内 Trigger Counter、一个 Worker）
  -> 每轮固定按 raw.created_at ASC, raw.id ASC 的 Pending Raw ID 列表
  -> 每条：来源关联幂等检查
     -> 已关联：短事务删除残留 Pending，跳过 LLM
     -> 未关联：Candidate Retriever 全量顺序批次 LLM -> Governance LLM
       -> Core 校验 -> 同一个 SQLite 写事务保存 Organized/Source/出队
  -> 当前 Raw 成功/失败后处理下一条；一轮结束消费一次 Trigger
```

- **组织过程逐条串行**：全局仅一个 Organization Worker；每次处理完一条 Raw 并成功提交后，再读取下一条的现有 Organized 集合，避免对尚未落地的变更做候选检索。
- `OrganizationService` 管理处理流程和异常边界；`OrganizationCandidateRetriever` 只负责候选召回；`GovernanceService` 构造独立 Prompt 并调用 LLM；`GovernanceDecisionValidator` 仅做结构及确定性权限验证；Repository 负责短事务写入。
- 初始 Pending 列表是**本轮待尝试集合**，不是数据库锁，也不是事实时间顺序；轮内每个列出 ID 至多尝试一次。新入队的 Raw 交由后续轮次或 Periodic Trigger；不得在当前轮失败后反复从库中重新取它而无限循环。

## 6.2 Trigger 与 Scheduler

- **Immediate Trigger**：Extraction / Dreaming 的 Raw + Pending 事务成功 COMMIT 后通知一次；一次事务可写多个 Raw，只发送一次唤醒。COMMIT 后通知失败不回滚任何权威状态。
- **Periodic Trigger**：默认启用，使用 cron-utils UNIX 五字段 Cron（默认 `"*/10 * * * *"`、`Asia/Shanghai`）检查 `SELECT EXISTS(SELECT 1 FROM organization_pending)`，仅非空时通知；不管理重试状态，也不负责治理。
- Scheduler 是**单个全局内存 `triggerCount: Int` + `workerRunning`**，受 `Mutex` 等同一临界区保护。Trigger 到来计数加一；Worker 未运行时启动唯一 Worker，运行中只累加；**处理完整一轮之后**计数减一，若仍大于零重新查询 Pending 列表开启下一轮，归零则原子设置空闲。一个 Trigger 是一次**全量待处理扫描机会**，不对应单条 Raw。
- 处理失败的 Raw 仍在 Pending，跳过后继续本轮其余记录；当前轮不直接无限重试。后续 Trigger 可再次尝试。计数溢出及关闭过程的计数/Worker 退出竞态必须保护，普通任务错误隔离而不终止永久消费能力。
- 不建设 StartupRecovery；进程重启只注册正常 Cron/Worker，内存计数丢失；SQLite Pending 在之后的周期性检查中被自然发现。持久化数据仍是可靠性依据。

配置示意（字段与默认值可在 Loader 集中实现，用户无需逐项写出）：

```yaml
context:
  organization:
    triggers:
      immediate:
        enabled: true
      periodic:
        enabled: true
        cron: "*/10 * * * *"
        timezone: Asia/Shanghai
```

## 6.3 来源关联的前置幂等检查

每条 Raw 开始治理前先查询权威 `organized_memory_source` 是否存在该 `raw_memory_id`。**已关联**说明该 Raw 已成功治理，跳过 Scanner/Governance；若 Pending 残留，使用短写事务再次核验后删除。**未关联**才执行候选检索。查询错误不得视作未关联；外键/数据库异常必须报告并保留 Pending。

```sql
SELECT EXISTS (
  SELECT 1 FROM organized_memory_source WHERE raw_memory_id = ?
);
```

该关联是权威业务数据而非可删重建的缓存；索引 `idx_source_raw` 已在专题 2 定稿。MVP 不维护单独的组织任务状态或独立源去重索引。

## 6.4 Candidate Retriever 与通用 Scanner

```kotlin
interface OrganizationCandidateRetriever {
    suspend fun search(rawMemory: RawMemory): List<OrganizedMemory>
}
```

- MVP 采用可替换的 `LlmCandidateRetriever`：从 SQLite 读取**全部当前 Organized Memory** 的一次一致性列表快照（至少 `id/content`，按 ID 稳定排序），释放短 `ReadContext`，再按 Token Budget 逐批串行调用低成本 LLM，返回该批可能有关联的 Organized ID。未按 Runtime、Agent 或 Domain 做硬过滤。
- Batch 构造使用 `TokenEstimator`、Profile 容量、Task 输入/输出预算、Prompt 和安全余量，逐条累加直至下一条超预算，再生成下一批；不是固定每批 20/50 条。空 Organized 列表直接返回空候选，不要求 LLM 调用。
- 每批 LLM 最小结构为 `{"candidate_ids":["O-MEM-..."]}`；Core 校验类型和每个 ID **属于该批模型实际可见的 ID 集合**。所有批次完整结束才做全局稳定去重并交给 Governance，不预先静默截断候选。
- **任一必需扫描批次失败、非法 ID、响应截断或 JSON 错误**：整条 Raw 治理失败、保持 Pending；不能把局部结果当作全量空候选并执行 NEW。单条 Organized 连同固定 Prompt、Raw 本身无法容纳时，报告预算错误，不能跳过它还宣称全量扫描。
- Scanner 只做高召回候选查找，不执行治理，也不能直接写数据库。未来可改成 FTS/Embedding/混合召回而不改 Governance/Organized 数据协议。与 Retrieval `searchMemory()` 复用通用 Token 分批、ID 校验基础能力，但使用**独立的 Prompt 和业务相关性准则**。

## 6.5 Governance 输入、单动作输出与 Prompt

Governance 接受一条 `pending` Raw Memory（至少 `id/content/createdAt/generatedBy`）及 Scanner 全部候选（至少 `id/content/domains/createdAt/updatedAt`）。系统时间仅是生成/存储时间，**不得被当作事实发生或生效时间**；Prompt 应优先评估 `content` 提供的事实时间、账户/适用范围、过去与现在、确定结论与待确认事项。MVP 不为一次治理默认检索全部历史 Raw/Conversation；真实来源追溯由引用关系保留，必要的内容缺失不得靠猜测填补。

```kotlin
data class GovernanceDecision(
    val action: GovernanceAction,
    val targetMemoryId: String? = null,
    val content: String? = null,
    val domains: List<String>? = null
)
enum class GovernanceAction { NEW, MERGE, UPDATE }
```

| 动作 | LLM 字段要求 | 语义与 Core 执行 |
|---|---|---|
| `NEW` | `content`、`domains` 必填；**不允许** `targetMemoryId` | 新的独立当前认知（也可明确尚未核实）；Core 创建新 Organized ID、时间和当前 Raw 来源 |
| `MERGE` | `targetMemoryId` 必填；`content` 可选；**不修改** `domains` | 重复确认、兼容补充或明确早于现有状态的历史证据；保留 ID，可兼容完善 `content`，追加当前 Raw |
| `UPDATE` | `targetMemoryId`、`content` 必填；`domains` 可选 | 当前认知改变、纠正或应明确未确认状态；保留 ID，更新当前 `content`，`domains` 未提供则保留原分类，追加当前 Raw |

- 一条 Raw **一次只有一个主要动作**，MVP 不支持模型同时操作多个 Organized，不增加 DELETE/SPLIT/CONFLICT/DEFERRED 动作。`MERGE` 不得借补全文案隐式修改事实当前状态；需改变状态时用 `UPDATE`。
- **事实时间优先**：晚录入旧历史不会覆盖更晚生效的当前认知。例如 10 月 24 日补录「10 月 1 日暂停」但当前 Organized 有「10 月 15 日恢复」时，优先将旧历史作为 MERGE 来源而非 UPDATE 回旧状态。Raw `createdAt`、Organized `updatedAt` 和入队顺序不能证明事实新旧。
- **不确定认知是成功结果**：同一事项出现两种持仓比例但缺乏可信时间/账户范围时，可使用 UPDATE 将内容明确写为「当前值未确认，已有相互矛盾来源」，追加所有相应证据并出队；不用 `deferred` 或额外状态。不能将没有证据的新值写成确定事实；若安全关联都无法判定，也不能强行合并。
- Organized 只维护**当前有效认知**，而非完整历史版本；旧来源保留作追溯，并不代表所有旧 Raw 都支持当前值。

## 6.6 决策验证与模型权限约束

独立 `GovernanceDecisionValidator` 对模型结果做确定性校验：合法 JSON/字段类型；动作特定的必填/禁止字段组合；非空 `content` 与合法 `domains`；`MERGE/UPDATE` 的目标 ID **必须来自本轮 Scanner 返回且实际提供给 Governance 的候选集合**。模型不能自己指定 Organized ID、`createdAt/updatedAt`、`rawMemoryIds` 或来源表操作，不能引用未授权候选。

非法结果整条失败，**不得自动将不合法的 UPDATE/MERGE 改成 NEW**，不能部分保存。模型是否忠实表达当前事实属于 Prompt 与专项评测的语义质量范围；程序级校验不应声称已证明文本事实正确。不增加第二个 LLM 审核流水线。

## 6.7 单 WriteSlot 原子提交

所有 LLM 调用、候选检索、Core 输出校验都在数据库事务外完成。提交只使用已有的 `database.write { tx -> ... }`，在唯一 WriteSlot 的**同步、短事务**内执行：

1. 重读 `organization_pending` 是否仍包含当前 Raw ID、`organized_memory_source` 是否已引用该 Raw；数据库读取失败即取消提交。
2. 若仍 Pending 且未关联，MERGE/UPDATE 再次确认目标存在且 `updated_at` 与 Governance 获取该候选时一致，避免执行过期决定；否则回滚或放弃本次决定，保留 Pending，留待下轮重新检索。
3. `NEW`：生成 Organized ID；INSERT `organized_memory`；INSERT 当前 Raw 来源。
4. `MERGE`：保留原 ID，必要时更新兼容内容；INSERT 当前 Raw 来源。
5. `UPDATE`：保留原 ID，UPDATE 当前内容与按需分类；INSERT 当前 Raw 来源，旧关联全部保留。
6. 所有成功的 Organized 变更（**包括仅新增关联的 MERGE**）均在该 WriteSlot 事务内以 `max(clock.instant().truncatedTo(MILLIS), previousGlobalMaxUpdatedAt.plusMillis(1))` 分配**全局严格递增**的 `updated_at`，按固定上海 `+08:00` 毫秒时间文本写入。`created_at` 是真实新增时刻，不等同事实时间。
7. DELETE 当前 `organization_pending`，检查来源插入、目标更新、Pending 删除等受影响行数，然后 COMMIT。任一步失败即整笔回滚，保留 Pending。不会出现成功治理但证据关联未保存的半成品。

**事务内幂等分支**：若已关联但仍 Pending，则仅删除残留 Pending，不重复修改 Organized 或调用 LLM；若已关联且已出队视为已完成；若既未关联又无 Pending，拒绝本次旧决策，不静默创建记忆。若 `NEW` 的系统生成 ID 与主键冲突，仅对明确的**新 ID 主键冲突**有限次重新生成并重试整笔未提交事务，绝不 `INSERT OR REPLACE`；其他异常不伪装成 ID 冲突。

SQLite 独立于 VFS；**不在治理事务内读写 `alcyone://memory/map.md`**，也不在事务里执行 HTTP/LLM。整个 Raw 处理成功的标志是上述 SQLite COMMIT，而不是获得一个合法 LLM 响应。

## 6.8 Map 水位与发布解耦

`organized_memory.updated_at` 既是本次记录更新时刻，也承担 Memory Map 变化检查的**逻辑水位**。MVP 不物理删除 Organized；NEW/MERGE/UPDATE 的成功事务均推进 `MAX(updated_at)`。Organization 成功后由 Memory Map 模块按既有水位策略检测并合并安排刷新；Map 是 VFS 派生文件，即便生成/发布失败，也**不得回滚治理提交**。Map 采用专题 7 确认的最终一致性、合并刷新及 Cron 补偿机制。

## 6.9 失败处理、进程中断与限制

| 场景 | 结果 |
|---|---|
| Scanner 批次失败、非法 ID、超预算 | 保留当前 Raw Pending；本轮继续其他 Raw |
| Governance 超时、拒答、非法决策或越权目标 | 不写入；Pending 保留 |
| SQLite Raw/Organized/Source/Pending 任一步写入失败 | 事务整体回滚，Pending 保留 |
| 目标 `updated_at` 在 LLM 期间变化 | 拒绝过期决策，保留 Pending 后续重试 |
| COMMIT 成功但 Worker 未收到结果、进程崩溃 | 权威 Organized/Source/出队已落盘；下次以 Source+Pending 检查判断是否处理 |
| 仅部分 Raw 失败 | 其余 Raw 正常治理；失败 Raw 待后续 Trigger |
| Map 刷新失败 | 治理仍成功；Map 标记 stale，保留此前有效 Map |

异常恢复依赖 SQLite 权威状态、Immediate+Periodic Trigger，不设 Governance Journal、Deferred Queue、额外批次幂等键、自动恢复 Worker 或无界重试。若持续技术故障使某 Raw 每个 Cron 周期重复失败，MVP 依靠日志和运行状态发现，退避/黑名单/人工重放入口留待实测评估。

## 6.10 验收测试与非 MVP

测试至少覆盖：Candidate 全量分批覆盖/ID 验证/单批失败整条拒绝；来源已关联直接出队且不调用 LLM；NEW/MERGE/UPDATE 的字段组合和候选授权；补录旧事实、真实后续状态变化、不确定事实表达；原有来源关系不丢失、无内容变更的 MERGE 也推进 `updated_at`；同毫秒连续写入的全局严格递增上海水位；目标更新冲突、来源唯一冲突、Pending 删除故障、未确认 COMMIT 结果、单条失败不阻断其他 Raw、Cron/Trigger 并发退出竞态以及 Map stale。使用 Fake LlmClient 和真实 SQLite，模型语义质量的评测集合交由专题 10。

**本专题已冻结**：单 Worker/全局 Trigger Counter，每轮固定 Pending ID 集合逐条串行；先检查来源；Scanner 低成本 LLM Token 分批全量扫描、整体校验；Governance 一次一个 `NEW/MERGE/UPDATE`；事实时间优先、明确不确定认知；校验严格限定模型可操作候选；SQLite 同事务维护 Organized、Source、水位与 Pending；单条失败保留并跳过、Cron 后续唤醒。

**不属于 MVP**：复杂冲突状态机/Deferred、Organized 版本历史、治理 Journal、分布式/多 Worker Organization、批次并发 Scanner、Embedding/FTS 必选检索、Prompt 在线编辑、自动 LLM 修复或二次审判、长期失败自动退避队列、物理删除/拆分 Organized 等。

---

# 专题 7：Memory Map 与 Context Retrieval（已定稿）

本专题以《核心数据流与接口设计》§15～16 为基线，进一步落实用户确认的 MVP 方案。**本专题关于 Memory Map 最终一致性的最新决策优先于早期文档中“必须再检查 T2、发布协调锁、发布时不得落后”的强一致性建议**：Map 为可再生派生数据；权威事实来自 SQLite Organized Memory。Map 通过 Organization 轮次通知、Runtime 初始化和 Cron 定期水位检查最终追上最新水位，不阻塞 Organized 写入。

## 7.1 Memory Map 角色、单文件与内容格式

- 一份统一 Memory Map，逻辑内容由 **Memory Overview + Memory Navigation** 组成；不按 Agent/Domain 分文件，不再额外维护 Full Memory、独立 Summary 或 Category Index。
- 从 **当前 Organized Memory** 全量生成，不读取旧 Map 作为生成新事实的来源，不直接使用旧 Raw Memory 覆盖现行认知。
- Map 唯一持久化位置：`alcyone://memory/map.md`，由 VFS 管理。Overview 保留重要的长期背景、目标、偏好、约束和当前认知；Navigation 概括真实存在的 Domain、主题及查询指引。不得罗列全部 Organized 或抄写大体量 Resources。
- Map 正文建议约 1,000～2,000 Token（配置目标，非允许静默截断的绝对上限）。生成失败或输出超限不得截断后发布。
- LLM 仅生成正文的 Overview/Navigation，**Core 生成 YAML Front Matter**。`generatedAt` 表示本次生成/发布时间，`sourceUpdatedAt` 表示本次输入 Organized 快照的 `MAX(updated_at)`；无 Organized 时用确定的空集合标记（例如 JSON/Kotlin `null`，序列化统一后不把缺失当成有效水位）。时间文本沿用统一上海 `+08:00` 毫秒格式。

```markdown
---
generatedAt: 2026-10-25T08:30:00.000+08:00
sourceUpdatedAt: 2026-10-25T08:25:00.000+08:00
---

# Memory Overview

…

# Memory Navigation

…
```

## 7.2 一次生成的固定 Snapshot 与预算

- 用一个短 `database.read {}` 读取全部当前 Organized Memory，保证 `id/content/domains/updatedAt` 与全局 `sourceUpdatedAt = T1` 来自同一 SQLite 只读快照；`ReadContext` 结束后才生成 Prompt 和请求 LLM。`T1` 是快照的明确标签，**即使生成期间有新变更，也不能擅自用更新后的水位改写 T1**。
- 对输入按稳定 ID 排序，复用专题 3 `TokenEstimator` 和 Task Profile 预算。全部 Organized 必须被纳入生成；若输入超过预算，报告 `BUDGET_EXCEEDED`，保留旧 Map；不静默丢弃数据并谎称已覆盖全量。分批/分层摘要不进入 MVP。
- 单个版本化 Map Prompt 强调概览和导航；必须保留 Organized 中“尚待确认”的不确定性，不编造领域、用户事实、资源链接或当前值。Validator 检查必需标题、非空结构、合法 Domain、长度和 Front Matter 可解析性；语义忠实性通过模型评测而不是伪装成完全可由规则校验。

## 7.3 最终一致性：取消二次水位检查与发布协调锁

**最新确认决定**：不使用与 Organization 共享的 Map 发布锁，不要求在 LLM 生成后再次查询 `T2`，不建立 SQLite↔VFS 跨存储事务、全局 Revision 或 Map 持久化 Job。

```text
读取全量 Organized Snapshot，记录 T1
    → LLM 生成 Map 正文 → Core 严格校验
    → VFS 安全替换 map.md，Front Matter.sourceUpdatedAt = T1
    → 正常结束（本轮不再检查 T2）

后续 Organization 通知 / Runtime 初始化 / Cron 定期检查：
    MAX(organized_memory.updated_at) != map.sourceUpdatedAt
      → 安排下一次刷新
```

- 允许新 Organized 在生成期间提交，本次 Map 可以短暂落后，后续合并刷新/Cron 最终追赶；Agent 的精确当前状态应通过 `getMemory`/`searchMemory` 获取，而非把 Map 当成权威事实。
- `MAX(updated_at)` 全局严格递增是**监测变化的基础**：NEW/UPDATE，以及仅新增来源关系的 MERGE 都推进水位；MVP 不物理删除 Organized，不引入独立 Fingerprint/Revision。
- 单个 Map Worker 保证本实例不会有多个不同快照的生成任务同时发布；VFS 发布需保证**文件完整替换**，避免读取到半写内容。若底层 Storage 缺乏可靠原子替换语义，应适配安全发布能力或报告失败，不将分段覆盖写误当作安全发布。
- 发布失败保留旧 Map，不回滚已 COMMIT 的 Organized。跨进程直接修改、VFS/SQLite 独立故障和 Crash 不受进程内强协调保障，由下次水位检查与状态反馈处理。

## 7.4 刷新协调：`Channel.CONFLATED` + 单 Worker

与 Extraction 的“每 Trigger 一次执行机会”不同，Map 刷新**只关心最新状态**。Runtime 维护一个 `Channel<Unit>(Channel.CONFLATED)` 和一个唯一消费者；同一时刻最多一个 Map Generator 执行，尚未消费的多次信号合并为一个。

```kotlin
private val requests = Channel<Unit>(Channel.CONFLATED)

fun requestRefresh() {
    check(requests.trySend(Unit).isSuccess) { "Map refresh coordinator not accepting requests" }
}

suspend fun runRefreshWorker() {
    for (ignored in requests) {
        try {
            mapService.refreshIfStale()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Memory Map refresh failed; retain previous map", e)
        }
    }
}
```

- `requestRefresh` 是进程内唤醒，非持久任务确认；已发布但暂时 stale 时由后续检查负责重新发送信号。示例中异常需脱敏，不记录记忆正文。
- 同时到达的多次请求合并为一个待处理 Unit；**Worker 执行期间收到新请求，会再形成最多一个待处理信号**，下一轮先检查水位，若已最新则跳过实际生成。因此“合并”不等于整个时间段最多生成一次，也不保证一定只执行一次检查。
- 仅 Runtime `start()` 启动唯一消费者，关闭时停止接受请求、停止 Cron 并按生命周期等待/取消正在生成的任务；消费者故障退出必须被运行状态识别。
- `refreshIfStale` 先比较已发布 Map 水位与 SQLite 当前快照水位，相同则跳过；不同时生成及安全发布。必须校验当前可读取 Map 的 Front Matter，文件破损不可当成 CURRENT。

## 7.5 三类刷新入口与 Cron 补偿

1. **Organization**：当前完整治理轮次结束时如 Organized 有变化，合并发出一次 `requestRefresh()`，不要每提交一条 Raw 就要求生成一次；以后若有其他合法 Organized 修改入口，同样通知。
2. **Runtime 初始化**：Memory Map 模块每次正常 `start()` 检查现有 Map 与 SQLite `MAX(updated_at)`，缺失/水位不一致则异步唤醒 Worker。初始化检查不阻塞普通 Context 检索，不叫 StartupRecovery。
3. **Cron 定期补偿（MVP 新确认）**：默认开启，建议 UNIX 五字段 `*/10 * * * *`、`Asia/Shanghai`，可配置。每次只做轻量水位比较；Map 缺失、破损或 stale 才通知 Worker，不在 Cron 回调内等待 LLM。避免并发重复 Cron 扫描。

```yaml
context:
  memoryMap:
    refresh:
      periodic:
        enabled: true
        cron: "*/10 * * * *"
        timezone: Asia/Shanghai
```

- 上述参数有内置默认值，用户不必逐项配置。Cron 作为补偿，覆盖更新通知失败、上轮模型失败和启动后长期无新增 Organized 的情况；若模型/存储持续不可用只能反复报告失败，不承诺固定时间内追平。
- `bootstrap()` 只读取 Map 模块现有状态，**不主动触发**水位检查、LLM 生成或补偿任务。

## 7.6 Map 状态契约与 Bootstrap

```kotlin
data class BootstrapContext(
    val memoryMap: MemoryMapView?,
    val mapStatus: MapStatus,
    val capabilities: String
)

data class MemoryMapView(
    val content: String,
    val generatedAt: Instant,
    val sourceUpdatedAt: Instant?
)

enum class MapStatus { CURRENT, STALE, MISSING, UNKNOWN }
```

- `CURRENT`：**最近一次成功的** Map 模块水位检查发现当前数据库水位与已发布 Map 相同，非瞬时强一致保证。`STALE`：已知 Map 低于权威水位；`MISSING`：尚无可用 Map；`UNKNOWN`：数据库/水位检查不可用、无法证明最新性，不能谎报 CURRENT。
- Map 模块维护/报告最近的可靠水位状态；Bootstrap 不重新触发主动数据库水位检查。若存在旧 Map 但 `STALE` 或 `UNKNOWN`，可以返回内容及明确状态；首次无 Map 仍返回 `capabilities`。
- `capabilities` 是固定的 Agent 使用说明，不调用 LLM 生成；Bootstrap 不检索详细 Memory、Raw/Resources，也不返回 Recent Memory View（延期）。当前状态/最新参数应让 Agent 再执行 Search/Get，不依赖 Map 的当前性推断。

## 7.7 Context Retrieval SDK 契约

与专题 4 的 `ingestConversation` 和 Runtime 生命周期并存，MVP 逻辑 API 示意：

```kotlin
interface AlcyoneContext {
    suspend fun bootstrap(): BootstrapContext
    suspend fun searchMemory(query: String, domains: List<String>? = null, limit: Int = 10): List<OrganizedMemory>
    suspend fun getMemory(id: String): OrganizedMemory?
    suspend fun getRawMemory(id: String): RawMemory?
    suspend fun loadMeta(uri: String): ResourceMetadata
    suspend fun read(uri: String): ByteArray
    fun readStream(uri: String): Flow<ByteArray>
}
```

- `searchMemory` 搜索当前 Organized Memory；可选 Domain 仅在调用方**显式指定**时过滤，不以 Agent/Profile/Runtime 默认推断 Domain；默认 `limit=10`（可配置并校验）。`getMemory/getRawMemory` 直接精确查询 SQLite，不调用 LLM；找不到返回 `null`，读取故障返回错误而非 `null`。
- `loadMeta/read/readStream` 仅访问允许的 `alcyone://resources/...`，交由 VFS 校验路径与已有权限；`read` 返回原始字节、配置合理的整体读取上限，`readStream` 为逐块 `Flow<ByteArray>` 并在取消/完成时释放底层资源。Context 不自动解析 PDF/图片/视频、不建立 Resource 全文检索；Resources 写入/删除操作仍在 VFS 管理入口，不属于只读 Retrieval。
- `getMemory` 包含稳定 `id/content/domains/rawMemoryIds/createdAt/updatedAt`；`getRawMemory` 返回 `id/content/generatedBy/sources/createdAt`。两者不向 Agent 暴露 SQLite 事务、内部 Turn ID 或 VFS Memory 文件路径。
- 对 Agent 不暴露泛化结构化 `query()`；内部 Repository 可保留 Domain、更新水位、时间及分页查询。

## 7.8 Memory Search 复用 LLM Scanner 与结果规范

- Search 与 Organization Candidate Retrieval 复用底层 **Token Budget 预算动态分批、LLM 结构化选择、ID 归属校验及跨批合并**，但使用各自独立 Prompt/模型 Task：Organization 偏高召回及冲突发现，Search 偏相关性。
- Search 读取有权访问的 Organized Memory（显式 Domains 可过滤），每批返回该批已提供给模型的候选 ID；Core 拒绝非法/窗口外 ID，所有必需批次均完整成功后汇总去重，不把部分成功伪装成完整结果。
- 若候选多于 `limit`，使用跨批全局相关性排序后取前 `limit`，**不可按每批各取 TopN 后简单拼接**。若统一重排序超出 Token Budget，明确报超限而非静默截断后宣称全局最相关；分层排序后续优化。
- Core 最后从权威 SQLite 按 ID 读取真实 Organized 内容；不接受模型编造的文本、Memory ID 或 Resource URI。MVP 不为了 Search 的并发 Organization 更新做额外 T1/T2 检查或自动重试，容许短暂旧快照；需要最新值时通过 `getMemory(id)` 再读。
- 无匹配时返回空列表（成功），任意必要 Scanner 批次/结构/权限校验失败时整体失败。MVP 不引入 FTS5/Embedding/向量库作为 Search 前置组件。

## 7.9 HTTP 薄适配层与权限边界

| HTTP | Path | SDK |
|---|---|---|
| `GET` | `/v1/context/bootstrap` | `bootstrap` |
| `POST` | `/v1/memories/search` | `searchMemory` |
| `GET` | `/v1/memories/{id}` | `getMemory` |
| `GET` | `/v1/raw-memories/{id}` | `getRawMemory` |
| `GET` | `/v1/resources/meta?uri=...` | `loadMeta` |
| `GET` | `/v1/resources/content?uri=...` | `read/readStream` |

- Server 不实现检索业务、SQL 或 VFS 存储逻辑；只负责认证、HTTP 参数/结果/错误映射以及内容流式传输。Resource Content 在 HTTP 层复用同一个下载接口，不因 SDK 提供 `read/readStream` 而强制设置两个 HTTP 路由。
- **Ingestion Key 与 Context Read Key 分离**：专题 4 的按 Runtime 绑定 Key 只授权提交该 Runtime 的 Turn，绝不自动赋予读取全部长期记忆的权限。MVP Read Key 为可信调用者的基础鉴权，不提前实施逐 Agent/Domain 多用户 ACL；VFS 自有授权必须继续生效。API Key 由环境变量注入，HTTPS 传输，禁止写入日志；具体配置/安全设置在专题 9 落地。
- 建议 HTTP 映射：非法参数 400；未认证 401；无权 403；精确 Memory/Resource 不存在 404；依赖数据库不可用 503；LLM/Scanner 错误返回具名依赖故障，不伪造 200 空数据。二进制 Resource 应遵循流式响应与取消释放约定。

## 7.10 异常、刷新状态及测试边界

| 场景 | 结果 |
|---|---|
| Map 丢失、破损、发布失败 | 缺失则 MISSING；发布失败保留旧文件，标为 STALE/UNKNOWN；定期 Cron 后续补偿 |
| Map 旧水位，当前数据库水位可得 | `STALE`，可以返回旧 Overview/Navigation 但不宣称最新 |
| Map 水位查询不可用 | `UNKNOWN`，不把未知当 CURRENT |
| Search 完整扫描无匹配 | 空列表，正常成功 |
| Search 某个必需扫描批次失败或结果非法 | 整体失败，不返回部分结果 |
| Search 输入/重排序超预算 | 明确失败，不静默截断 |
| Memory/Raw ID 不存在 | SDK `null` / HTTP 404 |
| Resource URI 非法、越界或无权限 | 明确参数/权限错误，不绕过 VFS |
| 资源读取中途失败/取消 | 终止流并清理资源，不把部分字节当完整文件 |

验收至少覆盖：`MAX(updated_at)` 严格单调下 NEW/MERGE/UPDATE 的 Map stale 检测；同一快照加载及 `sourceUpdatedAt=T1`、生成期间新 Organized 写入后正常发布旧 T1、下一次 Cron 补偿追平；Conflated 多 Trigger 只保留一个待消费唤醒、生成期间补信号与 Shutdown 竞态；Map 破损/LLM 失败/VFS 替换失败仍保留旧文件；初始化、Cron、Organization 通知均可唤醒；Bootstrap 在 STALE/MISSING/UNKNOWN 均不调用 LLM；Scanner 全量覆盖/全局排序/非法 ID/某批失败；getMemory/getRawMemory 无 LLM、HTTP 404；VFS URI 范围、权限、流式中断释放；Read/Ingestion 两类 Key 相互隔离。

**本专题已冻结**：Map 全量快照+单 Markdown+Core Front Matter；最终一致性、**不进行发布 T2 二次检查、不采用协调锁**；Channel.CONFLATED 单 Worker 合并刷新；Organization、启动与默认启用的 Cron 三类触发；MapStatus 四态；轻量 Bootstrap；复用 Scanner 的全量 Search、统一排序及直接 Get；SDK 优先/HTTP 薄适配、Read Key 与 Ingestion Key 分离；Resource 原始读取与流式读取。

**非 MVP**：复杂 Agent/Domain ACL、Recent Memory View、Full Memory 或多 Map 文件、FTS/Embedding/独立 Resource 搜索、Map 增量摘要或全量超预算静默降级、独立 Revision/Map Job/发布锁、跨 SQLite/VFS 强事务、Search 并发快照强一致保证及通用失败恢复调度器。

---

# 专题 8：Dreaming（已定稿）

本专题工程化落实《核心数据流与接口设计》§17，并以此前用户确认的三轮方案为准：**Manual + 可选 Cron、增量 Conversation ID 扫描、每批独立完整处理、Organized → Raw → Conversation 分层取证、Reflection 输出可追溯 Insight、Raw/Pending/Checkpoint 同库原子提交**。Dreaming 不是另一套 Extraction/Organization，不直接更新 Organized Memory、Memory Map 或 VFS Resources；不增加 Topic 表、Dreaming Job、Journal、Deferred 队列或向量数据库。

## 8.1 调度、生命周期与运行结果

- **两种入口**：手动请求和配置的 `cron-utils` 五字段 Cron，时区可配置；MVP **默认不启用 Cron**，没有配置时仅运行手动调用。Cron 只请求启动，不在回调里等待 LLM。
- 单实例内**最多一个 Dreaming Worker**。运行中再次手动请求返回 `ALREADY_RUNNING`，运行中 Cron 触发直接跳过并记录脱敏状态；不积累 Trigger 次数、不维护持久化 Dreaming Queue。Dreaming 与 Extraction、Organization 可以并行，但同一 Dreaming 内串行处理批次。
- Runtime `start()` 仅注册手动入口与可选 Cron，不进行 Dreaming 专项启动扫描，也不补跑停机时错过的 Cron；Shutdown 遵守专题 1 协程取消、资源释放规则。中途失败后由 Checkpoint 保留已提交进度，下一次正常触发继续。
- 建议逻辑服务 `suspend fun runOnce(): DreamingResult`，结果包括 `startCheckpoint`、`endCheckpoint`、`scannedTurns`、`discoveredTopics`、`generatedMemories`、`completed`。业务异常和中途失败原因通过具名异常/运行状态报告；`completed=false` 不能代替错误详情。管理调用的异步执行反馈不等同于永久 Job ID 或历史执行表。

```kotlin
interface DreamingService {
    suspend fun runOnce(): DreamingResult
}

data class DreamingResult(
    val startCheckpoint: Long,
    val endCheckpoint: Long,
    val scannedTurns: Int,
    val discoveredTopics: Int,
    val generatedMemories: Int,
    val completed: Boolean
)
```

## 8.2 固定增量上界与 Checkpoint

- `dreaming_checkpoint` 保持专题 2 的唯一全局进度记录（逻辑 `id=1, last_processed_turn_id=0` 起始）。使用 `conversation_turn.id` SQLite 内部自增 ID，不使用 `session_turn_id`、`timestamp`，也不按 `extraction_status` 过滤。
- 每次执行在**同一个短 SQLite ReadContext** 读取旧 Checkpoint `C0` 与当时已提交 `MAX(conversation_turn.id)=U`，固定本次扫描 `(C0, U]`；没有新增 ID 时正常返回。之后释放事务，新的 Turn 留给下次 Dreaming。
- 按 ID 升序读取增量 Turn，分批由实际渲染后的 Token Budget 决定；可容纳 `pending`、`complete`、`failed` 状态的 Turn。历史补录使用新自增 ID 自然进入后续增量批次，原始 `timestamp` 只用于理解实际事实先后。
- 一批成功的结束水位为**实际覆盖并完成处理的最后一条 Turn ID**，不是根据条数或时间猜测；ID 间存在合法空洞并不表示漏扫。批次必须连续覆盖尚未处理的查询结果，不能略过超预算、错误或不方便处理的 Turn 后推进水位。
- 单条 Turn 确定性超过 Topic Discovery 可用预算时，当前批失败并保持 Checkpoint；不修改该 Turn 的 Extraction 状态，不截断、丢弃或建立特殊跳过表。之后通过调整模型预算或未来分段能力解决。

```sql
-- 当前本轮固定上界 U 内，按已成功水位分页
SELECT * FROM conversation_turn
WHERE id > :last_committed_id AND id <= :upper_bound
ORDER BY id ASC
LIMIT :page_limit;
```

分页条数只是数据库读取控制；真实 Topic Discovery 输入边界由完整 Prompt + Turn 内容的 Token Budget 动态决定，不能仅凭固定 `LIMIT` 宣称批次满足模型预算。

## 8.3 Topic Discovery 与本批归并

每个**可独立提交的 Conversation Batch**执行 Topic Discovery → 本批 Topic 合并 → 必要的历史证据检索 → Reflection → 整批校验 → SQLite COMMIT。不得在先提交 Batch 1 后依赖尚未处理的 Batch 3 对 Batch 1 的 Topic 做跨批统一归并；跨批长期关联交给每个 Topic 的历史证据检索。

```kotlin
data class DiscoveredTopic(
    val title: String,
    val description: String,
    val keywords: List<String>,
    val sourceTurnIds: List<Long>
)
```

- 模型从本批实际提供的 Conversation 中发现长期偏好、变化、研究经验、跨会话规律和遗漏；不是每条 Turn 都必须产生 Topic。`topics: []` 是合法结果。
- Core 严格校验 JSON、非空标题/描述、数组类型和 `sourceTurnIds`；每个来源必须属于**当前 Topic Discovery 输入批次**，重复 Turn ID 可规范化去重。LLM 不得虚构来源或输出系统进度字段。
- 同批多个重复/相关 Topic 可使用一次轻量 Topic Consolidation 合并，输出 Topic 引用的来源仅可从已有 Topic 来源中组合；必须保留影响结论的不同范围和矛盾，不得把不一致事实强行合并。单 Topic 或无 Topic 可跳过归并调用。
- Topic 为**临时工作 DTO**；不分配持久 Topic ID，不保存 Topic Repository，也不将 Topic 直接当成 Raw Memory。
- Topic Discovery、Consolidation 任一必需调用失败或输出不可校验，即认定**当前整个 Batch 未完成**，不能以部分 Topic 代替完整处理，也不能推进 Checkpoint。

## 8.4 Historical Evidence Retrieval：三层按需检索

按 Topic 分层执行，以最小的相关证据输入进行 Reflection。复用专题 7 / 专题 6 的 LLM Memory Scanner 底层分批、Token Budget、ID 校验和候选聚合能力，三种业务 Prompt 分离。

1. **Organized 层**：从全部当前 Organized 中检索 Topic 相关的当前认知（不以 Agent/Domain 为硬过滤），对照本批新增 Conversation 做轻量 Novelty Check。只有确认“现有 Organized 已充分覆盖且无新的事实、限定条件、变化或值得验证的矛盾”时可提前结束 Topic；**检索到相关 Organized 本身不能成为跳过理由**。
2. **Raw 层**：有实质增量或不确定之处时，从**全部历史 Raw Memory** 按 Token Budget 分批筛选候选 ID，覆盖 `EXTRACTION` 和 `DREAMING` 生产记录；全部必需扫描批次成功后才认定候选完整。可以先用已召回 Organized 的来源确定已知 Raw，但不能代替全量候选扫描。每个命中 Raw 还应经 `organized_memory_source` 反查**全部关联的当前 Organized**，即使这些 Organized 未在第一层命中，也必须参加当前认知对照；未关联 Raw 不能因此当作当前事实。
3. **Conversation 层**：对关键 Insight 来源、范围/时间不清、证据冲突、用户明确纠正或来源独立性问题，按 `RawMemory.sources` 读取关键 Conversation Turn 和必要相邻 Turn；若来源是 `RawMemoryRef`，沿链追溯到底层 Conversation 并防循环/重复。本批增量 Conversation 已是原始证据，可以直接复用。**相同 Turn 的多个 Raw 不是独立多份证据**。

- Organized 是当前认知基准，但不是独立原始证据；历史 Raw 可能已过时、被纠正或只适用于旧阶段；原始 Conversation 用于核实用户当时实际表述。
- 无实质增量/证据不足时允许停止 Topic 或返回空 Insight；不默认遍历所有历史 Conversation。历史原文若从未形成 Raw 导航，MVP 接受召回盲区，不新增通用 Conversation 语义检索引擎。
- 当任一必需 Scanner 批次失败、ID 非法、原始来源不可定位或无法保留关键反面证据时，不能继续生成冒称完整的结论；当前 Batch 失败并保持 Checkpoint。

## 8.5 Reflection：输出契约与纠错保护

Reflection 只接收本 Topic 相关的：新 Conversation、召回及反查的当前 Organized、候选历史 Raw、已核验的关键 Conversation。LLM 要先比较当前认知和独立原始证据，再判断是否有**相对 Organized 的实质新信息**。旧 Raw 不能仅因为重新进入模型窗口就变成新的当前事实；应优先遵循事实发生/生效/确认时间、范围及用户明确纠正，不能用 Raw/Organized `createdAt/updatedAt` 代替事实时间。

- 已有充分表达、没有实质增量时返回 `{ "insights": [] }`；重要但尚未确认的信息必须在 `content` 中保留不确定性，不猜测当前值。
- 长期偏好通常需要跨场景独立证据，但明确的重要遗漏事实可以仅有一条可靠 Conversation；重复引用同一 Turn 的多条 Raw 不可冒充多个独立来源。
- 如当前 Organized 已确认用户纠正旧事实，Reflection 不得因重新召回旧 Raw 再输出该旧值为当前事实；只有新的可靠原始证据、明确后续变更或经核实的实质新发现才能推动后续 Organization 修正。

```json
{
  "insights": [
    {
      "content": "用户在个人软件项目中长期倾向选择熟悉、简单且便于维护的技术方案。",
      "sourceTurnIds": [182, 435, 891]
    }
  ]
}
```

- MVP Reflection 输出**只要求** `content` 与 `sourceTurnIds`（已提供给 Reflection 的 SQLite 内部 Turn ID）；持久化时由 Core 转换成完整稳定 `ConversationTurnRef(runtimeId,agentId,sessionId,sessionTurnId)`。Raw 来源仍参与检索和追溯，五字段 `RawMemory` 逻辑模型继续支持未来 Dreaming 的 `RawMemoryRef`，但当前输出协议不要求模型直接输出它。
- Core 检查 JSON、每条非空 `content`、非空且**属于本次实际向 Reflection 提供并核验的 Turn** 的来源、来源可追溯性及批次完整性；单条错误则本批 Insight 整批拒绝。Core 只能执行确定性校验，模型语义忠实性需要专题 10 的评测。
- LLM 不生成记忆 ID、`generatedBy`、`createdAt` 或 Checkpoint。Core 填写 `generatedBy=DREAMING`、MemoryIdGenerator 产生 Raw ID、Clock 提供创建时间；不为 Insight 加 `confidence/domain/type` 等未经确认字段。
- Reflection 全部必需证据超预算时，不能静默删除关键矛盾后给出确定结论；若无法在预算内构成可靠上下文，则当前 Topic/Batch 失败，Checkpoint 不推进。暂不建立多级递归反思框架。

## 8.6 Checkpoint 与 Raw Memory 原子提交

`dreaming_checkpoint` 保持唯一一条全局记录，初始化值 0。本批全部 Topic 的检索、Reflection 和来源校验完成后，预先构造 0～N 条 `RawMemory(DREAMING)`，仅使用一次**短** `database.write { tx -> ... }` 原子保存；LLM、VFS、额外普通 Repository/挂起函数不能运行在写事务中。

```text
已完成批次 [fromExclusive, lastTurnId]，预期 checkpoint = C
    → 全部 Insight / 来源检查成功，系统字段已准备
    → BEGIN context.db WRITE TRANSACTION
         核对 checkpoint == C
         INSERT 本批 raw_memory（允许 0 条）
         INSERT 对应 organization_pending（允许 0 条）
         UPDATE dreaming_checkpoint
           SET last_processed_turn_id = lastTurnId
           WHERE id = 1 AND last_processed_turn_id = C
         校验 checkpoint 更新行数恰好为 1，且 C < lastTurnId <= U
      COMMIT
    → 若本批新增 Raw，则提交后通知 Organization 一次
```

- **只推进当前批全部实际扫描/处理完成的最大 Turn ID**；不能在只完成 Topic Discovery 时提交。批次没有 Topic 或没有 Insight 时仍需正常推进 Checkpoint。
- Checkpoint `expected == actual` 校验及 `new > expected`/`new <= upperBound` 前置检查均必需；CAS 失败表示过期执行，整笔事务回滚，不能将旧结果套到新的 Checkpoint 上。
- `raw_memory`、`organization_pending`、`dreaming_checkpoint` 任何插入/更新失败均整笔回滚；任意一条 Insight 不合法也不得先写其中合法部分。Raw ID 主键冲突时仅对确定的碰撞重新生成 ID，并有限次重试**尚未提交**的整笔事务，不能覆盖现有 Raw。
- COMMIT 后通知 Organization 失败不回滚权威数据，已有 Organization Periodic Cron 通过 Pending 补偿。COMMIT 成功但响应/进程异常使执行者无法确认时，下次读取 Checkpoint 判定已提交进度，**不重复保存旧批次**。
- Dreaming 不改 Conversation 的 `extraction_status`，即使输入为 Pending/Failed Turn。Dreaming 与 Extraction 可能生成语义相近的 Raw，允许重复进入权威 Raw 证据层，由唯一 Organization 流程整合。

## 8.7 失败、重启与可观测性

| 场景 | 本批 Raw / Pending | Checkpoint / 后续行为 |
|---|---|---|
| Topic Discovery / Consolidation 模型失败、非法来源 | 均不写入 | 保持旧水位，下次正常触发重试 |
| Organized / Raw Scanner 必需批次失败、原文追溯失败 | 均不写入 | 不推进，不把不完整召回当成功 |
| Reflection 返回非法 Insight 或关键证据不足 | 整批拒绝 | 保持旧水位；不部分提交 |
| 全部 Topic 成功且 0 Insight | 0 条新 Raw | 正常推进当前非空批次水位 |
| 一条 Conversation 超 Token Budget | 不截断/跳过 | 报确定性错误，保留水位，不修改 Extraction 状态 |
| SQLite 事务错误或 Checkpoint CAS 失败 | 全部回滚 | 保持旧水位，稍后重新读取权威进度 |
| COMMIT 成功但通知 Organization 失败 | 全部已持久化 | 水位已推进，Org Periodic 补偿 |
| 第 N 批失败而前 N−1 批已提交 | 前批保留，当前批无部分保存 | 下次从最后成功 Checkpoint 继续 |
| Manual / Cron 重叠触发 | 不创建第二 Worker | 手动返回 ALREADY_RUNNING / Cron 跳过 |
| 进程重启 | 已提交 Raw、Pending 和 Checkpoint 保留 | 不做 StartupRecovery，等待下次 Manual/Cron |

SDK `AlcyoneContext.runDreaming()` 与 Cron 共用上述协调器；未提供该能力的第三方默认实现抛出 `UNAVAILABLE`，不得复用 `ALREADY_RUNNING`。Runtime 关闭先封闭手动/Cron 准入，等待已登记手动运行与后台运行真实收尾（后台运行可取消），调用方取消仅取消自身，随后才释放 LLM 与 SQLite；关闭中/后新请求返回 `CLOSED`。

运行状态至少记录已提交/当前 Checkpoint、最近执行时间、已处理 Turn/Topic/Insight 数、失败阶段与脱敏错误；敏感 Conversation/Raw 原文及模型 Prompt 不写日志。普通业务失败不将 Worker 标为永久 FAILED，任务取消按既有 Coroutine 语义传播。

## 8.8 验收与范围边界

验收覆盖：空库及无新增 Turn；固定上界内处理同时有新 Turn 入库；Pending/Complete/Failed 三种 Extraction 状态；历史补录按新 ID 而非旧 `timestamp` 入批；批次 Token 动态划分、Topic 来源校验与同批归并；Novelty Check 已覆盖/新增限定条件；全历史 Raw 扫描、反查当前 Organized、相同 Turn 多 Raw 的来源去重和旧事实纠错；Reflection 零结果与非法来源；超长单 Turn 不跳过；Raw/Pending/Checkpoint 故障注入事务回滚；条件水位更新失败；Raw 主键冲突；COMMIT 后崩溃/通知失败；多个手动/Cron 请求；关闭取消及成功批次的重启续跑。

**已冻结**：Manual + 可选 Cron、单 Worker/重叠触发跳过；按 SQLite Conversation 自增 ID 的固定扫描上界与独立批次；Topic Discovery 临时 DTO、批内合并；Organized → Raw → Conversation 按需历史检索及纠错保护；Reflection 输出 `content + sourceTurnIds`、系统生成 `DREAMING` Raw；所有 Topic 完成后 Raw/Pending/Checkpoint 同事务；失败保留进度并由下次触发继续。

**非 MVP**：常驻 Topic 表、跨批持久 Topic 归并、Deferred Review、重扫全部历史 Conversation、分段超长 Turn、多级递归 Reflection、Embedding/向量证据库、独立批次幂等键、Dreaming Journal、持久化任务队列、自动 StartupRecovery 或手动绕过未完成批次的跳过清单。

---

# 专题 9：SQLite 数据库备份与部署（已定稿）

本专题落实《核心数据流与接口设计》§14 以及专题 1、3、4、7 的基础设施约定：运行中的 `context.db` 是 Conversation/Memory/Checkpoint 的**唯一权威数据库**；VFS 负责独立的 Map、备份文件及 Resources；SDK 优先、单实例运行、Ktor 为可选 HTTP 薄适配层。备份与部署不改变既有业务事务、Trigger 或 Worker 协议。

## 9.1 备份范围、接口与技术选型

- MVP **只对 Context 自身 `context.db` 生成完整一致性快照**，覆盖 Conversation Turn、Raw/Organized Memory、`organized_memory_source`、`organization_pending`、`dreaming_checkpoint` 等所有业务表、Schema 及必要数据库元数据；不将运行库当作可删除重建的缓存。
- Memory Map 是由 Organized 派生的 VFS Markdown，不在本次 SQLite 快照范围内；Resources 及 VFS Node/ACL/Mount 元数据数据库由 VFS 的独立机制管理，Context 不承担跨两套数据库的统一备份事务。
- `DatabaseBackupService` 负责备份编排；底层 Xerial SQLite JDBC 的 **Online Backup API** 适配放在现有 `context/persistence-sqldelight`，并与 SQLDelight 的正常 Repository 访问并存。具体 API 签名、进度/超时与数据库连接方式按工程锁定的 JDBC 驱动版本验证。Core 不依赖 JDBC 类型。
- `suspend fun backup(): BackupResult`（示意）的成功承诺是**快照已生成并完整发布到 VFS**，不是远程 Git/云盘异步同步成功；结果可包含备份 URI、大小、完成时间。手动与 Cron 调用同一逻辑。

```text
Manual / 已配置 Cron
  → 单备份任务占用执行位
  → 用独立 SQLite 源连接调用 Online Backup API
  → 在本地临时路径生成完整独立 .db
  → API 成功、关闭连接、只读重新打开并执行 PRAGMA quick_check
  → 通过 VFS 发布到唯一目标 URI（保证文件完成边界）
  → 清理临时文件并返回 BackupResult
```

- 必须使用 SQLite Online Backup 生成运行期快照；**禁止**直接复制正在使用的 `context.db`，也不能自己拼装 `-wal/-shm` 文件。源库可保持 WAL 模式并继续处理业务读写。
- `PRAGMA quick_check` 是轻量可读性/基础完整性检查，**不是恢复演练或业务完整性证明**；如果备份 API、校验、VFS 发布任一步失败，当前备份报错，不损害运行中的权威库和已经完成的旧备份。
- VFS 发布不得让半写入文件占用正式 `.db` URI；采用底层支持的安全完成/替换方式，若无法满足则明确失败。备份文件使用唯一时间标识，必要时附序号避免同秒冲突，不覆盖唯一有效旧备份。推荐格式 `context-20261025-030000.db`，时间依照系统统一 `Asia/Shanghai` 规则。
- 备份目标目录建议 `alcyone://memory/backups/`，底层可选 Local FS 或 WebDAV Mount；Context 不感知存储实现及其异步同步细节。生产部署建议至少一份备份位于**与主数据库不同的故障域**，不能把主库与所有备份都放在同一块易同时损坏的磁盘。

## 9.2 Manual/Cron、并发与失败范围

- 手动入口和可选 Cron 复用 Runtime 已定稿 `cron-utils` UNIX 五字段与 Kotlin Coroutines，按配置时区执行；**自动备份默认不启用，生产推荐每日执行**。参考配置：

```yaml
context:
  backup:
    enabled: true
    cron: "0 3 * * *"
    timezone: Asia/Shanghai
    directory: alcyone://memory/backups/
```

- 进程中最多同时有一个备份任务；重复的手动请求返回 `ALREADY_RUNNING`，Cron 同时到达则跳过并记录；不排队、不增加备份 Job 表、Trigger Counter 或持久重试状态。Cron 不在回调中直接长时间阻塞，实际备份由后台任务执行。
- 中途失败/取消应清理不完整的本地临时文件及未完成发布物（在底层能够识别时），保留已完成的旧备份；若 VFS 保存成功但返回/响应丢失，依据目标 URI 及可读性核实，**不盲目覆盖**。
- 备份过程不持有常规 Context SQLite 的写事务进行 VFS I/O，也不暂停全部业务 Worker；数据库一致性由 Backup API 保证。
- MVP **不实现**自动数据库恢复、增量备份、自动旧备份删除、备份记录数据库、复杂留存策略或 VFS 自身数据库的恢复工具。人工恢复须先停止 Runtime，隔离目标数据库和旧 WAL/SHM 后谨慎恢复经过验证的快照，不能在运行中直接替换权威 DB。

## 9.3 单实例部署与两种运行模式

- **Embedded SDK**：Kotlin 宿主调用 `AlcyoneContext.create(...) / start() / close()`，由宿主管理生命周期；不启动 HTTP。可信 SDK 调用不依赖 HTTP API Key，但仍执行所有 Core/VFS 业务校验。
- **Standalone**：一个 JDK 21 JVM 进程包含 `context/server` Ktor+CIO 与唯一 `context/runtime`，组合现有 Core、SQLite、VFS、LLM Adapter、Cron/Workers；所有 HTTP 路由只委派 Runtime 公开能力，不能直接操作业务表。
- 同一 `context.db` 只允许本次 MVP 的**一个后台业务 Runtime**负责 Extraction、Organization、Dreaming 与 Map；即使 SQLite 支持多连接，也不能启动两份各自持有独立内存调度器的 Runtime 共同治理同一个数据库。不引入分布式协调、多实例水平扩缩容或通用服务发现。
- Standalone 优先使用 **systemd** 负责开机启动、SIGTERM 优雅停止、失败重启及 stdout 日志。Docker 单容器可作为替代，但必须持久化 SQLite 与 VFS 状态、确保只有一个活跃 Runtime；不为 MVP 建立 Kubernetes、复杂编排平台或额外消息队列。

## 9.4 Ktor、Caddy HTTPS 与网络边界

- Ktor 默认只监听 `127.0.0.1:8080`（可配置，须限制暴露范围），云主机防火墙不直接开放后端端口；由现有 Caddy 实例提供 HTTPS/TLS 与本机反向代理。域名证书由 Caddy 按满足条件的域名/CA 机制管理；公网 IP 证书只有 CA 与验证协议确实支持时才采用，不把自签名证书误认作公网受信任证书。
- 不增加第二套 Caddy、独立 API Gateway 或 OAuth 服务。Caddy 只负责网络层 TLS/代理，**HTTP API Key 认证仍由 Ktor 执行**；Forwarded Headers 只能从实际可信的反向代理接受，禁止将用户自报头部视作鉴权依据。
- Server 对 Ingestion Body、搜索参数、资源读取大小/流、超时和取消执行与已确认接口相符的限制，不默认把全部二进制资源加载进内存；对外不返回内部绝对路径、API Key、原始敏感日志和完整 Prompt。

## 9.5 三类静态凭据与权限

| 凭据 | 适用操作 | 边界 |
|---|---|---|
| `Ingestion Key` | 按已注册 `runtime_id` 提交完整 Conversation Turn | Key 与 `runtime_id` 绑定，**不能**因能够上传 Turn 就获得全部记忆读取权限 |
| `Context Read Key` | Bootstrap、Search/Get Organized/Raw、Resource Meta/Read/Stream | 不授予写 Conversation、运行备份/Dreaming、查看详细管理状态权限；仍须遵守 VFS 访问检查 |
| `Admin Key` | 详细运行状态、手动 Dreaming/Backup 等管理操作 | 不因管理身份而绕过各业务操作的输入和权威存储校验 |

示例（与专题 4 注册结构一致，配置分区内部由各自 ConfigLoader 负责）：

```yaml
server:
  host: 127.0.0.1
  port: 8080
  ingestion:
    clients:
      hermes-cloud:
        runtimeType: hermes
        apiKey: ${HERMES_INGEST_KEY}
  auth:
    readKey: ${CONTEXT_READ_KEY}
    adminKey: ${CONTEXT_ADMIN_KEY}
```

- 静态 Key 从环境变量/受控密钥文件注入，启动时检查缺失、空值和冲突；按合法 Bearer 认证后再检查权限和 `runtime_id` 所属身份。比较实现采用安全摘要或恒定时间比较，敏感内容禁止进入日志。未经认证返回 401、凭据有效但权限不足返回 403。
- MVP 不实现逐 Agent/Domain 的复杂 ACL、OAuth/SSO、多租户/动态注册/Key 热更新。配置变化须重启；已存在的 VFS 访问控制必须继续生效。
- 所有管理接口与详细状态必须认证；若 HTTP 对外暴露健康检查，优先限制只供本机或可信网段访问，并确保无敏感指标泄露。

## 9.6 Runtime 启动、退出与运行状态

- 复用专题 1 已冻结的 `CREATED → STARTING → RUNNING → STOPPING → CLOSED` 状态机：验证 Config/凭据 → 初始化 Context SQLite/WAL/Schema → 初始化 VFS/LLM Client → 注册/启动 Worker 与 Cron → 检查 Memory Map 初始化水位、必要时异步刷新 → Ktor 最后对外接收请求。初始化不可用的权威数据库/版本错误应让启动失败并清理已有资源；**不要求启动时逐个在线检测 LLM Provider，也不等待 Map LLM 生成完成**。
- 关闭时 Ktor 先停止接受新请求并排空/取消在途请求；Runtime 进入 STOPPING 并拒绝新 Trigger，停止 Cron；等待可配置超时，随后取消余下 Worker，确认数据库操作停止后再释放 LLM Client、Context SQLite、VFS。建议 Runtime `shutdownTimeout=30s`（可覆盖），systemd 的 `TimeoutStopSec` 配置应留出额外收尾裕量。不得为退出强制完成整个 Dreaming 大批次，未提交批次由既有 Checkpoint 恢复。
- `systemd` 使用专用低权限用户、受限的配置/密钥文件、持久化数据目录、`Restart=on-failure`；不在容器临时层存权威 `context.db`。服务意外重启只恢复进程与正常 Cron 注册，不额外补跑错过的 Trigger、不启动独立 StartupRecovery。
- `GET /health/live`：仅确认进程与 HTTP 基本响应；`GET /health/ready`：确认 Runtime 正常运行及关键 SQLite 基础能力可用；`GET /v1/admin/status`：鉴权返回完整 `ContextRuntime.getStatus()`（Work 状态、Pending、Dreaming Checkpoint、Map 四态、LLM Usage、最近失败及 Backup 状态）。健康接口优先限定内部网络。
- Provider 临时不可用、Map `STALE`、存在 Pending 或单条 Dreaming 失败，**不直接代表整个 Context HTTP 不 Ready**；数据库损坏或关键权威存储不可用则 Ready 失败。不可判定/数据库查询失败不能伪装成零 Pending、`CURRENT` 或业务正常。保持专题 3 的 SLF4J+Logback JSON stdout、脱敏统计，运行状态仅进程内累计，不建监控表或独立 Prometheus/Grafana 依赖。

## 9.7 故障、验收与非 MVP 范围

- 集成测试使用文件型真实 SQLite：源库在 WAL 下持续读写同时备份，验证快照可单独打开且 `quick_check` 通过，快照包含跨表一致状态；强制 Backup API/校验/VFS 发布失败、临时清理、旧备份保留、Cron/Manual 重叠运行。
- Runtime/HTTP 测试：Standalone 与 Embedded 路径一致；重复 start/close、启动部分失败和安全释放、系统关闭中禁止接受新 Trigger；HTTP Bearer 三类权限隔离、Ingestion Runtime ID 绑定、拒绝泄漏敏感日志、Resource 流取消、反向代理真实请求路径、健康探针仅按核心依赖判 Ready。
- 验证进程崩溃/重启后 Organization Pending、Extraction Pending、Dreaming Checkpoint、Map 水位仍可由**已确认的正常触发/读取机制**继续处理；systemd 重启不能凭空修复损坏的数据库，也不等于立即重放所有历史 Cron。
- **已冻结**：SQLite Online Backup + `quick_check` + VFS 安全发布；唯一文件名、Local FS/WebDAV 可选；Manual/可选 Cron 单任务、默认开发不开自动备份/生产推荐日备；不覆盖旧有效备份；单 JVM/单 Runtime；SDK 优先、Ktor 本机监听+Caddy HTTPS；Ingestion/Read/Admin 三类 Key；systemd 管理、轻量 Live/Ready、受鉴权详细状态及优雅关闭。
- **非 MVP**：备份自动清理/自动灾难恢复、增量备份、跨 VFS 元数据库联合快照、统一管理面板、动态 OAuth/复杂 ACL、多实例业务协调、外部任务调度/消息队列、Kubernetes 集群、独立监控服务、自动修复受损权威数据库。

---

# 专题 10：测试、质量与交付验收（已定稿）

本专题定义正式交付前的工程验证、语义评测及 Coding Agent 协作验收契约。**确定性规则由自动化测试证明，数据一致性由真实 SQLite 证明，LLM 语义质量由固定评测样本与回归评估证明**；不以单一覆盖率或模型准确率代替正确性。沿用专题 1 的五模块、JDK 21、JUnit 5、Ktor TestApplication、Fake 外部依赖及 Runtime/Server 分层，不建设新的通用测试/审批平台。

## 10.1 测试分层与可重复性

| 层级 | 运行对象与依赖 | 主要验证 |
|---|---|---|
| Core 单元测试 | Fake LlmClient / Repository、Fake Clock、可控 Coroutine Dispatcher | Trigger、快照边界、来源与 JSON 校验、预算、单调时间、业务状态机 |
| Persistence 集成测试 | **文件型真实 SQLite + SQLDelight**；内存库仅限无 WAL/重启要求的小测试 | DDL/索引/外键、WAL、事务回滚、读写 Slot、持久化水位、并发、幂等 |
| Runtime 集成测试 | 真实 Runtime/Coroutines + Fake LLM/VFS 或受控替身 | Scheduler/Cron、通知丢失、Worker 隔离、单实例、启动/停止、资源清理 |
| Server 测试 | Ktor TestApplication + 测试 Runtime | Route、JSON、请求大小、三类 Key/Runtime ID、状态码、流式取消 |
| 跨模块端到端测试 | 真实 SQLite + Fake LLM + 测试 VFS | Conversation → Raw → Organized → Bootstrap/Search、Dreaming、Map、备份与恢复边界 |
| LLM 语义评测 | 固定输入/期望约束 + 实际配置模型（独立于离线 CI） | Extraction、Scanner、Governance、Search、Map、Dreaming 的事实质量、误差与回归 |

- 常规自动化测试可**离线、确定性**执行，不依赖真实外部 LLM 账号、服务可用性或网络；通过预置 Fake 输出覆盖成功、失败、非法模型结果和调用统计。
- 控制随机数/Clock、Coroutine 时序和文件临时目录；并发测试用 `CompletableDeferred`、屏障或可控 Dispatcher，避免用固定 `delay(100)` 碰运气。
- 测试跟随既有 `context/{core,persistence-sqldelight,runtime,server}/src/test`；跨模块、故障注入和进程级测试放 `integration-tests/context`；仅共享少量 Fixture/Fake，不提前新增泛化测试框架。
- 进程级崩溃、WAL 与重启可靠性必须用**独立 JVM 进程或受控子进程**检验；在同一 JVM 中抛异常不能等同于真实进程崩溃。

## 10.2 八项跨模块不变量

| 编号 | 不变量 | 必须证明的性质 |
|---|---|---|
| **I1 原子提交** | Extraction、Organization、Dreaming | Raw/Pending/Target 状态、Organized/Source/Pending、Dreaming Raw/Pending/Checkpoint 各组更新全成或全败 |
| **I2 幂等** | Conversation 入库与 Raw 治理 | 相同 Turn 重放不重复插入；同一 Raw 已有来源关联后不重复 Governance |
| **I3 来源可追溯** | Raw、Dreaming Insight | 原始证据引用转换为稳定 `(runtime_id,agent_id,session_id,session_turn_id)`；不能将数据库自增 Turn ID 当长期外部来源保存 |
| **I4 进度不丢** | Pending/Checkpoint | 异常或重启后仍能由 SQLite 权威状态识别未完成工作，正常触发可补偿，不依赖内存计数持久化 |
| **I5 当前认知正确** | Organized 治理与纠错 | 稳定 Organized ID；补录旧事件或重现已纠正的 Raw 不能因入库时间较晚而覆盖当前有效认知 |
| **I6 水位严格单调** | Organized `updated_at` | NEW/UPDATE 和仅增加来源的 MERGE 均在 WriteSlot 内推进全局毫秒级逻辑水位；同毫秒与时钟回拨不漏检 |
| **I7 派生隔离** | Map、LLM、Backup | Map 生成/发布、LLM 调用、备份发布失败不能破坏已提交的权威 SQLite 记录；Map stale 能经正常检查恢复 |
| **I8 权限隔离** | Ktor/Runtime/VFS | Ingestion Key 只上传所属 Runtime，Read Key 不能管理或写入，Admin Key 不能绕过业务校验；VFS ACL 仍生效 |

以上是**关键验收清单**而非另建运行时校验数据库。每个涉及这些规则的开发任务必须有直接测试证据；Fake Repository 只能证明业务分支，不能单独证明 SQLite 原子性或唯一约束。

## 10.3 按流水线的核心测试矩阵

| 功能 | 必须覆盖的确定性/集成场景 |
|---|---|
| Conversation Ingestion | 初次插入、精确重放、相同键不同正文冲突、并发重放、Runtime 绑定、大小/时间格式、COMMIT 后通知失败 |
| Extraction | 单 Session Count/Idle、固定且连续的 Snapshot、`N=32` 折半及单 Turn 超限、Target/History 关系、来源非法整批拒绝、零结果、事务失败原样 Pending |
| Organization | Immediate/Periodic/Trigger Counter 竞态、Scanner 全量不漏批、NEW/MERGE/UPDATE、旧事实与不确定性、候选版本变化、来源关联/出队幂等、更新时间递增 |
| Memory Map | 同一 SQLite Snapshot/T1、Conflated 合并、生成中新增 Organized 后发布旧 T1、后续 Cron 补偿、文件安全替换、CURRENT/STALE/MISSING/UNKNOWN、旧 Map 保留 |
| Retrieval / Resources | Scanner 全批成功和结果 ID 校验、跨批全局排序、无结果与部分失败区分、Get 精确查找、Resource URI 限定、VFS 鉴权、分块读取消资源释放 |
| Dreaming | 固定 Turn ID 上界、Extraction 状态无关、历史补录、动态分批和 Topic 归并、三层取证/同源去重、旧事实纠正、空 Insight、水位 CAS、已提交批次续跑 |
| Backup | WAL 下正常写入同时 Online Backup、一致性与 `quick_check`、Manual/Cron 冲突、临时失败/VFS 发布失败、旧快照保留、必要时从快照只读打开 |
| Runtime / HTTP | SDK/Standalone 对齐、重复 start/close、启动部分失败、关键存储不可用时 Ready、凭据隔离、停止新 Trigger、关闭中任务取消与资源归还 |

特别要求验证**失败后的真实状态**。例如 Extraction 第二条 Raw 插入时故障，必须断言 SQLite 中 Raw/Pending 均无新增且目标 Turn 仍为 `pending`；不能只断言代码抛出异常。Organization/Dreaming 的对应事务同理。

## 10.4 故障注入与并发边界

固定的故障注入点：

1. **外部调用前后**：LLM 网络失败、模型返回非法 JSON/非法来源；SQLite 不允许提前标记完成。
2. **事务中途**：写入任意一个表、违反主键/外键、更新时间或 Checkpoint CAS 校验失败；验证整笔回滚。
3. **COMMIT 成功但通知前失败**：模拟响应遗失、触发器失败或进程退出；数据库结果权威，后续 Count/Idle/Organization Cron/Map Cron 的**既有规则**能发现可恢复工作；Dreaming 按已提交 Checkpoint 续跑，不重做旧批。
4. **Worker 取消及 Runtime 关闭**：在 LLM 调用、SQLite 提交和 VFS 写入各边界主动取消，验证 `CancellationException` 不吞掉、短事务完整、资源先停止使用后再关闭。
5. **实际进程重启**：用独立子进程测试 SQLite WAL、未完成 Pending、Dreaming 已提交/未提交批次、Map 水位与备份文件；不凭内存触发计数做持久恢复。

并发重点：Extraction/Organization/Dreaming 的网络阶段可并行而 SQLite WriteSlot 串行；同 Session 不并发提取、Org/Dreaming 各自单 Worker；Map 生成期间 Org 可继续更新，仅最终一致并由 Cron 追赶；HTTP 关闭时不得新建业务处理。测试不能误加跨全部 LLM 工作的全局互斥锁。

## 10.5 CI 档位与质量门槛

| 档位 | 时机 | 最低要求 |
|---|---|---|
| **快速验证** | Worker 每次开发任务提交前 | 编译、目标模块的单元测试、相关 SQLite 小型集成测试、格式/静态检查（项目已有时） |
| **完整验证** | Lead 阶段验收、Architect 里程碑复核、合并主分支前 | 全模块测试、跨模块端到端、对应的故障注入、并发和必要的进程重启测试 |
| **语义评测** | Prompt、任务模型或语义流水线发生实质修改时；关键里程碑复核时 | 固定样本回归，记录 Prompt/Profile/模型及错误清单；人工复核严重退化 |

- 所有任务必须能提供**真实执行命令和结果**，未运行不得写“已通过”。命令/Gradle task 名称由工程创建时固定，最终验收需能在干净环境复现。
- 不设置全项目统一覆盖率百分比或全 LLM Task 统一准确率硬门槛；可用 JaCoCo 等工具观察变化，但不能替代关键不变量验证。
- 不能以“大部分测试通过”交付未处理的关键事务、幂等、事实纠错或权限错误。非阻塞遗留问题需明确记录风险及处理计划。

## 10.6 LLM Prompt 评测数据与回归

建议在现有测试/开发资料下维护一套独立 Evaluation Dataset，例如：

```text
evals/
├── datasets/
│   ├── extraction.jsonl
│   ├── scanner.jsonl
│   ├── governance.jsonl
│   ├── retrieval.jsonl
│   ├── memory-map.jsonl
│   └── dreaming.jsonl
├── prompts/
└── results/
```

- 每个样本包含输入（Conversation/Raw/Organized 与来源关系）、预期的语义约束或关键判断、不能出现的错误，以及必要的人工参考说明。**不要求与参考答案逐字一致**。
- Extraction：重要事实召回、误提取、范围和时间、来源忠实性；Scanner：重复/冲突候选高召回及漏检；Governance：动作判断、时间顺序和不确定认知；Search：Recall@K/相关性；Map：重要认知覆盖、真实 Domain、导航可用性；Dreaming：新 Insight 价值、重复率、纠错保护及独立证据忠实性。
- 优先识别**虚构或扩大用户事实、漏掉关键冲突候选、已纠正事实回退**三类高风险错误。建议初始每种 LLM Task 准备约 **20～50 条**高价值样本，覆盖正常、矛盾、历史补录、空结果及非法引用，数量不是不可调整的门槛。
- 记录模型/Profile、Prompt 版本或内容哈希、评测样本版本、指标与典型失误。修改模型、Prompt 或与模型输入有关的扫描逻辑后跑固定集，对关键失误人工复核；**重大质量退化不得未经分析直接验收**。每个 Task 的取舍独立：Candidate Scanner 偏召回、Governance 偏防错误覆盖、Dreaming 偏证据保守。
- **确定性协议校验与语义质量区分**：JSON/ID/来源/目标权限必须由 Core 严格拒绝，不能用模型评分替代。语义忠实性不宣称能完全机械证明。
- 默认使用脱敏或合成样本；个人 Conversation/Memory 及模型完整响应不默认提交公开测试仓库或发送未获授权的远程评测平台。离线 CI 不运行收费 LLM 评测。

## 10.7 六个实施里程碑与独立验收

按模块依赖而非简单逐专题顺序开发，六个里程碑交付均须能**单独自动验收**：

| 里程碑 | 实施范围 | 最小可验收交付 |
|---|---|---|
| **M1 工程基础与 Ingestion** | Gradle 五模块、Runtime 配置/生命周期、SQLite/SQLDelight、LLM 抽象、Conversation 协议入库 | 可信调用方可幂等写入 Turn；Schema/WAL/来源鉴权与基础测试通过 |
| **M2 Memory Extraction** | Count/Idle、Scheduler、Snapshot/预算、LLM Extraction、Raw/Pending/Target 提交 | Conversation 能完整转化为 Raw + Pending；允许零结果、失败回滚 |
| **M3 Memory Organization** | Periodic/Immediate、Candidate Scanner、Governance、来源/出队/updatedAt | Raw 可治理成当前 Organized，纠错、幂等、严格事务与水位可验证 |
| **M4 Memory Map 与 Retrieval** | Map 生成/三种刷新、Bootstrap/Search/Get、VFS Resources | Agent 能读取长期背景、检索权威内容；Map stale 与 Cron 补偿可验证 |
| **M5 Dreaming** | Topic Discovery、三层取证、Reflection、Raw/Pending/Checkpoint | 能在跨会话证据中发现有效增量；已完成批次重启后不会重复提交 |
| **M6 备份、部署与最终验收** | Online Backup、Ktor/Caddy/systemd、三类鉴权、跨流水线故障测试 | 可独立部署、访问、备份；全部核心不变量与端到端测试通过 |

- **每个里程碑独立验收、不强迫提前开发后续模块**：M2 可直接断言 Raw/Pending，无须等待 Organization；M3 可用预置 Raw Fixture 验收，无须每次完整跑 Conversation 流程。
- 早期里程碑按需提供 Fake/测试适配接口，以保证目标功能可验证；M6 才完成正式 HTTP/部署完整路径，不以未实现的后续能力阻塞此前里程碑。
- 不准因赶进度绕过已冻结分层：M2 不直接 JDBC 写库、M3 不擅改 Turn 状态、M4 不把 Map 当权威、M5 不直接更新 Organized、M6 不建立另一个恢复/任务框架。

## 10.8 Architect → Lead → Worker 协作与复核

已确定协作层级：**Architect 负责架构、里程碑与任务方案；Lead 负责开发任务分解、Code Review/测试；Worker 负责生产代码、测试与提交**。每个开发任务必须完成两级复核，而不是只在里程碑末审查：

```text
Architect 制定里程碑和任务约束
    → Lead 落地设计并下发 Worker 任务
    → Worker 实现、提交 Commit、自测
    → Lead 检查真实 git diff / 代码，执行测试和验收
        ├─ 不通过：列出问题、测试和修复建议 → Worker 修改
        └─ 通过：带 Commit/交付文档 → Architect 复核
              ├─ 不通过：Lead 拆修复任务 → Worker 修改
              └─ 通过：任务完成；里程碑末 Architect 再做集成复核
```

**任务下发最小字段**：目标、设计依据章节、允许修改范围、关键约束、可验证验收标准、所需测试。Lead 不得仅依赖 Worker 的文字完成报告；要检查实际 `git diff`、受影响代码与测试结果，必要时重新运行测试。Worker 必须报告实际命令/通过失败、Commit ID、修改范围、已知问题。

**交付模板（示意，可由 Paseo 自动传递）**：

```markdown
## Worker → Lead
Commit: <commit-id>
实现摘要: ...
修改文件: ...
测试: <命令 + 实际结果>
已知问题: ...

## Lead → Architect
任务目标: ...
Commit: <commit-id>
交付文档: <relative-path>
Lead Review: <设计符合性 + 测试结果 + 关键风险 + 遗留事项>
复核请求: 请按技术实现设计及验收条件检查。
```

- 对已有设计中定义的**权威数据结构、事务语义、外部协议、跨模块职责和业务状态**的重大变更，Architect 必须提出设计偏差与影响分析，交由用户确认后调整定稿文件；Coding Agent 不得自行静默修改。命名、局部实现布局等不影响契约的调整由 Architect 自行裁定，避免频繁打断。
- Architect 复核失败，Lead 负责组织 Worker 修正；不能跳过 Lead 的再次审查。每个里程碑维护一份简短 `milestone-review.md`（Commit 范围、完成项、测试、风险、验收结论），不建立复杂审批台账、日报或专门的任务数据库。

## 10.9 里程碑与最终交付门槛

每个里程碑必须同时满足：

1. 所有任务的 Worker→Lead→Architect 复核均通过，提交 Commit 可追踪，关键变更对应设计章节。
2. 对应范围的快速/完整自动测试实际通过；关键数据不变量和事务故障场景有可复现证据。
3. 所修改模块与已有模块的集成行为正确，没有绕开 `api/core/persistence/runtime/server` 已确认边界。
4. 文档与代码一致，Prompt/Profile 变更有语义评测回归记录（如适用）。
5. 没有未决关键设计偏差；非阻塞遗留问题已记录影响和后续行动。

M6 最终验收再执行全部模块、进程重启、备份/恢复边界、授权隔离、端到端和必要 LLM 语义质量评测。未实际执行的项目需明确标注“未验证”，不得填充虚假的成功结果。

## 10.10 已定稿一致性核对与非 MVP 边界

- **权威数据统一**：`context.db` 的 Turn、Raw、Organized、Source、Pending、Checkpoint 共同遵循短事务；Memory Map/Backup/Resources 是 VFS 派生或独立文件，不建立 SQLite↔VFS 分布式事务。
- **调度规则各司其职**：Extraction 按 Session Count + Idle，Organization 依 Trigger Counter + Immediate/Periodic，Map 只关心最新状态用 Conflated + 初始化/Org/Cron，Dreaming Manual/Cron 重叠跳过，Backup Manual/Cron 重叠跳过。不得为了代码复用而修改各自的触发语义。
- **Snapshot/水位边界一致**：Extraction 锚定本 Session 窗口、Dreaming 按入库 Turn ID 固定上界和逐批 Checkpoint；Organized `updated_at` 严格全局递增供 Map 使用。Map **最终一致**、只记录快照 T1、无需生成后 T2 二次检测或发布锁；不将过期 Map 当权威事实。
- **权限和生命周期**：唯一 Context Runtime、SDK 优先/Server 薄适配、Ingestion/Read/Admin Key 相互隔离；关闭以实际数据事务和工作任务收尾为准，不依赖 StartupRecovery 或持久化 Task Queue。
- **评测/验收与语义契约分开**：确定性规则必须自动证明，模型语义有评测基线；Prompt 不是数据库写入权限或来源 ID 合法性的替代。

**与上游文档版本差异的明确说明**：此前《核心数据流与接口设计》§15 中的 Memory Map 生成后二次 `T2` 检查/短发布保护以及 Cron 补偿暂列 TODO，已由用户在专题 7 明确确认修订为 **T1 固定快照 + 最终一致性 + Map Cron 正式进入 MVP**。本技术实现文档 §7 为最新获确认的实施契约；后续同步修订上游设计时不得再恢复旧的发布锁/T2 条款。该差异已被正式批准，不能让 Coding Agent 自行选择两套方案。

**非 MVP**：统一 90% 代码覆盖率硬阈值、全部 LLM Task 统一准确率指标、全自动 Prompt/模型优化、在线外部 LLM 作为 CI 硬依赖、大型 Evals 平台、独立 QA 服务、复杂审批流、通用故障注入服务、多实例/分布式调度、重建权威 DB 的 StartupRecovery/Journal，以及未经本文件确认的新持久化任务/记忆结构。

**十大专题已全部定稿**：文档作为 Architect 制定实现计划和逐级验收的工程基线；后续变更需说明原因、受影响章节及验证方案。

---

## 版本记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v0.1 | 2026-10-21 | 建立技术实现设计文档；专题 1 已定稿，专题 2～10 建立待讨论提纲 |
| v0.1（更新） | 2026-10-22 | 专题 2「数据库与持久化」定稿：六表五索引、读写 Slot、显式事务上下文、SQLite 初始化及版本保护、故障验收 |
| v0.1（时间规范修正） | 2026-10-23 | 按用户确认修正专题 2 SQLite 时间为统一上海 `+08:00` ISO-8601 文本；专题 3 新增 Clock、时间编码器、Memory ID 时区、水位与测试边界 |
| v0.1（专题 3 定稿） | 2026-10-23 | 专题 3「基础设施」定稿：LLM Client/多 Profile、Token Budget、JSON、重试、ID/Clock、上海时间、日志、指标和运行状态；同步修正专题 1 过时 YAML 示例及单调水位毫秒精度 |
| v0.1（专题 4 定稿） | 2026-10-24 | 专题 4「Conversation Ingestion」定稿：Turn 校验、稳定 ID、规范化幂等、Payload 限制、SDK/HTTP、静态 Runtime 注册与 API Key、COMMIT 后 Trigger、Adapter 重试与验收 |
| v0.1（专题 5 定稿） | 2026-10-24 | 专题 5「Memory Extraction」定稿：Count/Idle、单 Worker 与 Session Trigger Counter、短 ReadContext 连续 Snapshot、Token 折半、严格来源校验、Raw/Pending/Target SQLite 原子提交与测试边界 |
| v0.1（专题 6 定稿） | 2026-10-24 | 专题 6「Memory Organization」定稿：Immediate/Periodic 与单 Worker、Scanner Token 分批全量召回、NEW/MERGE/UPDATE、事实时间与不确定认知、Source 幂等及版本检查、SQLite 原子出队和 Map 严格水位 |
| v0.1（专题 7 定稿） | 2026-10-25 | 专题 7「Memory Map 与 Context Retrieval」定稿：最终一致性、Conflated 单 Worker、初始化/Organization/Cron 补偿、Bootstrap 四态、Search 全量扫描及全局排序、SDK/HTTP 接口、Read Key 与 Ingestion Key 隔离 |
| v0.1（专题 8 定稿） | 2026-10-25 | 专题 8「Dreaming」定稿：Manual/可选 Cron 与单 Worker、固定 ID 上界与逐批 Checkpoint、Topic Discovery 及批内合并、三层历史取证、纠错保护 Reflection、Raw/Pending/Checkpoint SQLite 同事务与失败恢复 |
| v0.1（专题 9 定稿） | 2026-10-25 | 专题 9「数据库备份与部署」定稿：SQLite Online Backup/quick_check/VFS 安全发布、Manual/可选 Cron、单实例 Ktor+Caddy+systemd、Ingestion/Read/Admin 三类静态 Key、健康状态与优雅关闭 |
| v0.1（专题 10 定稿） | 2026-10-25 | 专题 10「测试、质量与交付验收」定稿：六层测试/八项核心不变量、事务/重启故障注入、固定 LLM Evals、六里程碑、Architect→Lead→Worker 每任务双重复核与最终交付门槛；专题 1～10 全部定稿 |
