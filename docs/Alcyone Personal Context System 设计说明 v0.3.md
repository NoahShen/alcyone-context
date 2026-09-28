# Alcyone Personal Context System 设计说明

## 本次修改（2026-09-28，文件系统文档清理）

- 清理底层权限模型相关描述，使用 Agent 目录分配和数据范围说明上层职责。涉及第 2～3、5、10、12～13 节。

**版本**：v0.3  
**更新时间**：2026-09-28  
**状态**：概念设计稿  
**上层框架**：Personal Agent Framework

---

# 1. 系统定义

Alcyone 是 Personal Agent Framework 的长期 Context 基础设施。

它负责保存、组织和提供跨 Round、跨 Agent、跨 Harness 持续存在的 Personal Context。

Alcyone 的核心定义是：

> **Alcyone 的底层是一个面向 Agent 的虚拟文件系统，通过统一 URI 和层级目录组织所有长期 Context；Context System 在其上管理各 Agent 的目录范围和共享关系。Resources 直接表现为文件；Memory 则是在虚拟文件系统之上增加语义治理和生命周期管理的特殊长期 Context。搜索、Overview、语义索引和渐进加载等能力，都是建立在该文件系统之上的访问与检索优化。**

整体可以概括为：

```text
Agent-native Virtual File System
        +
Memory Governance
        +
Retrieval Optimization
```

---

# 2. 系统边界

Personal Agent Framework 负责当前 Round 的执行：

```text
理解用户目标
规划任务
路由 Agent
Agent 协作
调用工具
执行任务
生成结果
```

Alcyone 负责长期 Context：

```text
长期 Context 的组织
长期 Context 的持久化
长期 Context 的访问控制
Memory 治理
Context 的访问与检索
长期连续性
```

面向个人服务场景，不建立多用户 VFS。Context System 管理 Agent 注册、身份绑定与数据归属；底层 VFS 提供统一文件操作。

因此：

> **Framework executes.**

> **Alcyone preserves context.**

> **Agents reason and act.**

---

# 3. 核心原则

## 3.1 Virtual Filesystem First

Alcyone 的基础是一个面向 Agent 的虚拟文件系统。

Memory、Resources 以及长期 Context 的访问能力都建立在统一的目录、文件和 URI 模型之上。

---

## 3.2 Stable URI

每个长期 Context 都拥有稳定、可引用的逻辑 URI，例如：

```text
alcyone://memory/domains/quant/
alcyone://resources/alcyone/design/
```

URI 与底层物理存储解耦，使 Agent 可以跨 Round 稳定引用同一份 Context。

---

## 3.3 Context System 管理 Agent 目录

Context System 为各 Agent 分配独立目录，并决定哪些资料可共享。例如 `/resources/agents/quant/` 与 `/resources/agents/family/` 分别保存各自文件。

Context System 在工具入口和检索流程中限定 Agent 的访问范围，再调用 VFS。VFS 将这些位置视为普通目录，不按 Agent 身份过滤文件，身份管理与目录分配归上层。

---

## 3.4 Memory Is Governed Context

Memory 不是普通文件，而是具有语义治理和生命周期的长期 Context。

Memory 的新增、更新、合并、冲突、Review 等变化都通过专门的 Memory Governance 完成。

---

## 3.5 Fat Context Core, Thin Agents

Alcyone 负责长期 Context 的组织、目录分配、治理和检索。

Agent 只需要通过统一接口寻找和使用 Context，而不需要理解底层存储、索引和治理实现。

---

# 4. 总体架构

Alcyone 从下到上的核心结构是：

```text
                    ┌───────────────────────┐
                    │        Agent          │
                    └───────────┬───────────┘
                                │
                 ┌──────────────┴──────────────┐
                 │                             │
          ┌──────▼──────┐               ┌──────▼──────┐
          │   Memory    │               │  Resources  │
          └──────┬──────┘               └──────┬──────┘
                 │                             │
┌────────────────▼───────┐                    │
│   Memory Governance    │                    │
│ NEW / UPDATE / MERGE…  │                    │
└────────────────────────┘                    │
                 │                             │
                 └──────────────┬──────────────┘
                                │
                     ┌──────────▼──────────┐
                     │ Virtual File System │
                     │     alcyone://      │
                     └──────────┬──────────┘
                                │
                     ┌──────────▼──────────┐
                     │  Physical Storage   │
                     └─────────────────────┘
```

其中：

```text
Physical Storage
→ 实际数据存储

Virtual File System
→ 统一 URI、目录、文件、Mount 和基本 IO

Memory
→ 长期认知

Resources
→ 普通长期文件和资料

Memory Governance
→ Memory 特有的语义治理和生命周期管理
```

Memory 与 Resources 在 Virtual File System 之上平级。

Memory Governance 是围绕 Memory 的治理机制，而不是独立的 Context 层。

搜索、Overview、语义索引和渐进加载等能力属于访问与检索优化，不进入核心架构主干。

---

# 5. Virtual File System

Virtual File System 是 Alcyone 的底层核心。

所有长期 Context 都组织在统一命名空间：

```text
alcyone://
```

第一阶段包含两个主要目录：

```text
alcyone://memory/
alcyone://resources/
```

Virtual File System 负责统一 URI、层级目录、文件、Node 资源身份、Metadata、基本文件操作以及底层存储映射。Agent 的目录管理由 Context System 完成。

首阶段底层物理数据来自 Local FS、WebDAV，未来按需要增加其他渠道。VFS 通过 Storage Adapter 隐藏后端差异；Git 是特定本地目录的同步 / 版本管理扩展，不作为 Storage Backend。同步扩展通过 Runtime 配置接入，文件访问与异步同步完成分开。

具体设计在 Virtual File System 专项设计文档中定义。

---

# 6. Resources

Resources 是 Alcyone Virtual File System 中的普通长期文件和资料。

例如：

```text
alcyone://resources/alcyone/

├── README.md
├── design/
├── decisions/
└── research/
```

也可以包含 Markdown、PDF、图片、视频、音频、代码、数据集以及其他文件。

Resources 不需要额外的复杂领域模型或治理流程。

对 Alcyone 来说：

> **Resources are files.**

它们主要通过 Virtual File System 提供的文件能力进行组织和管理。

---

# 7. Memory

Memory 存在于：

```text
alcyone://memory/
```

Memory 是经过提炼、未来长期有价值，并能够影响 Agent 后续判断的长期认知。

例如：

```text
用户长期事实
长期偏好
长期目标
重要决策
长期约束
长期经验
Agent 服务经验
```

Memory 与 Resources 的主要区别不在于底层存储方式，而在于：

> **Memory 需要被理解、治理并维护生命周期。**

---

# 8. Memory Structure

Memory 分为两个主要 Scope：

```text
memory/
│
├── shared/
│
└── domains/
```

## 8.1 Shared Memory

Shared Memory 保存整个 Agent 系统都可能需要知道的用户长期认知。

例如：

```text
alcyone://memory/shared/profile/
alcyone://memory/shared/preferences/
alcyone://memory/shared/goals/
alcyone://memory/shared/decisions/
```

定义：

> **Shared Memory = Who the user is.**

---

## 8.2 Domain Memory

Domain Memory 保存某个领域长期形成的服务经验。

例如：

```text
alcyone://memory/domains/quant/
alcyone://memory/domains/family/
alcyone://memory/domains/personal/
```

定义：

> **Domain Memory = What this domain has learned about serving the user.**

原则：

> **关于用户的事实，属于所有人。**

> **服务用户的手艺，属于对应 Domain。**

---

# 9. Memory Governance

Memory 的核心特殊性是治理。

Agent 或系统发现值得长期保存的信息后，不直接作为普通文件写入 Memory，而是进入 Memory Governance。

典型治理动作包括：

```text
NEW
UPDATE
DUPLICATE
MERGE
CONFLICT
SUPERSEDE
PROMOTION
REVIEW
```

Memory Governance 负责：

```text
判断是否值得保存
识别已有 Memory
处理新增和更新
处理重复和合并
处理冲突
维护 Fact Identity
处理 Shared / Domain
执行 Review
维护生命周期
```

具体 Memory 数据模型、治理规则和状态转换由 Memory 专项设计文档定义。

---

# 10. Alcyone Memory Manager

Alcyone Memory Manager 是 Memory Governance 中负责语义判断的 Agent。

它负责：

```text
是否值得形成长期 Memory
是否存在对应 Memory
应该新增还是更新
是否应该合并
是否存在冲突
应该进入 Shared 还是 Domain
是否需要 Review
是否应该失效或被替代
```

Alcyone Memory Manager 不负责普通 Resources 的管理。

上层 Context System Core 负责执行确定性的：

```text
Validation
Storage
State Transition
Directory Scope Management
Audit
```

原则：

> **Memory Manager decides. Core validates and executes.**

---

# 11. Retrieval Layer

Retrieval Layer 建立在 Virtual File System 之上，用于提高 Agent 获取长期 Context 的效率。

主要包括 Search、Navigation、Overview、Index、Progressive Loading 和 Ranking 等能力。

基本原则是：不知道位置时使用 Search；知道位置时直接 Navigate；需要快速判断时读取 Overview；需要完整信息时读取 Detail。

Retrieval Layer 是技术优化层，不改变 Virtual File System、Memory 和 Resources 的核心模型。具体方案在后续 Retrieval 专项设计中确定。

---

# 12. 核心模块

Alcyone 的核心模块保持简单：

```text
Alcyone
│
├── Virtual File System
├── Memory
│   └── Memory Governance
├── Resources
├── Retrieval
└── Audit
```

## Virtual File System

提供 URI、Directory、File、Node、Metadata、Mount、基本 IO 和事件；通过 Adapter / 同步扩展隐藏存储和同步细节。Agent 访问范围由 Context System 管理。

## Memory

负责长期认知的组织和存储。

## Memory Governance

负责 Memory 的语义治理和生命周期。

## Resources

表示 Virtual File System 中的普通长期文件与资料。

## Retrieval

负责提高 Context 查找和读取效率。

## Audit

记录重要的 Context Mutation、Directory Assignment Change、Memory Governance 和其他重要 Context 操作。

---

# 13. 核心场景

## 13.1 获取 Agent 下的记忆

Agent 在处理当前任务时，可以访问：

```text
alcyone://memory/shared/
```

以及分配给自己的 Domain Memory：

```text
alcyone://memory/domains/{domain}/
```

Context System 根据 Agent 身份及其分配 / 共享目录，只查询和返回该 Agent 范围内的 Memory。VFS 仅接收已确定的 URI，不接收身份参数。

---

## 13.2 产生新的记忆

Agent 在当前 Round 中发现值得长期保存的信息时，将候选 Memory 提交给 Memory Governance。

```text
Agent
  ↓
Memory Candidate
  ↓
Alcyone Memory Manager
  ↓
NEW / UPDATE / MERGE / DUPLICATE / CONFLICT ...
  ↓
alcyone://memory/...
```

Memory Manager 完成语义判断，系统 Core 负责实际状态更新和持久化。

---

## 13.3 查找 Resources

当 Agent 需要长期资料时，可以通过 Search 或 URI Navigation 查找：

```text
alcyone://resources/
```

中的文件。

例如：

```text
search("可转债下修研究")
```

可能定位到：

```text
alcyone://resources/quant/convertible-bonds/downward-revision/
```

Agent 再读取其中所需文件。

---

## 13.4 添加新的 Resources

Agent 产生新的长期资料时，例如设计文档、研究报告、实验结果、图片、视频、数据或代码，可以直接写入：

```text
alcyone://resources/...
```

Resources 的添加本质上是普通文件操作，不经过 Memory Governance。

---

# 14. 总结

Alcyone 的核心架构建立在一个面向 Agent 的 Virtual File System 之上。

```text
Physical Storage
        ↓
Virtual File System
        ↓
┌───────────────┐
│               │
Memory       Resources
│
└─ Memory Governance
        ↓
       Agent
```

其中：

```text
Resources
→ 普通长期文件

Memory
→ 具有语义治理和生命周期的长期认知
```

搜索、Overview、语义索引和渐进加载等能力，则作为 Retrieval Optimization 建立在这套基础架构之上。

Context System 决定 Agent 使用哪些文件；VFS 提供统一文件访问；同步扩展按配置处理后端之外的同步工作。

因此 Alcyone 可以概括为：

```text
Agent-native Virtual File System
        +
Memory Governance
        +
Retrieval Optimization
```
