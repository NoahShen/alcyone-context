# Alcyone Context System 文档入口

## 本次修改（2026-10-29，CM1 全面复查通过，放行 CM2）

- **全面复查 CLOSED**：8 项开放项（F1 通知关闭、P1 连接所有权与有限重试、P4 单语句失败语义、F2 非法 UTF-8、F3 注册三入口校验、P2 分页校验、P3 水位契约、F1-R1 快照竞态）全部 APPROVED；F4 入口文档归位完成。
- **整体回归通过**（Architect 独立执行）：`./scripts/check` exit 0（130 tasks，常规 **1188=1188**、14 个模块源码 `@Test` 与 XML 差额全 0）；`vfs/`、`common`、`integration-tests/vfs` tracked diff 为空（81 VFS 基线 mismatch=0）；`./scripts/t31-consumer-check` exit 0。
- **CM1 = 复查通过**，历史验收记录全部保留；原「复查收尾中」表述以此条为准。放行 **CM2**，按用户条件授权启动首个任务 **CT08 Memory Extraction 纵向骨架**。
- 仍未授权：`git add/commit`、远端推送、公开发布。详见 [CM1 全面复查](milestones/cm1/CM1_全面复查.md) 与 [开发进度](开发进度.md)。

## 本次修改（2026-10-29，CM1 全面复查 F4：入口状态归位）

- 当前真实状态：**CM1 历史验收已通过**（CT01～CT07 全部 DONE；整体初验常规 1142=1142、138 项源码清单、81 VFS 基线、`t31-consumer-check` 通过，历史证据保留），但**用户要求的全面复查仍有开放项**，不能表述为“复查通过”。
- 复查已关闭项（均经 Lead 初验 + Architect 独立复核 APPROVED）：**F1** 通知关闭真实等待；**P1** 连接所有权与关闭轮次内有限重试；**P4** 单语句失败不 poison（与事务终结失败区分）；**F2** 非法 UTF-8 固定 400 且零落库；**F3** 配置/注册三入口统一校验；**P2** 分页参数 SQL 前校验；**P3** `updated_at` 全局水位仍为调用方契约（不新增 CAS）；**F1-R1** Architect 实跑发现的通知快照 `ConcurrentHashMap` 竞态。
- 未关闭项：**F4 文档入口**（本次修订）与**整体回归**（`./scripts/check` + `t31-consumer-check`）。全部闭环并整体回归后，才可把状态改为“复查通过”并启动 CM2。
- CM2：用户已**条件授权**——上述闭环与整体回归通过后直接启动 `CT08`，无需再次请示；当前 **CM2 未启动**，未 `git add/commit`、未公开发布。
- 本轮各模块实测（Architect 独立跑）：persistence 176、runtime 89、server 30、core 11，0 失败/错误/跳过，源码 `@Test` 与 XML 差额 0。历史记录一律保留，见 [CM1 全面复查](milestones/cm1/CM1_全面复查.md)、[开发进度](开发进度.md)。

## 本次修改（2026-10-29，CT06整体收口与CT07启动）

- CT06独立常规1097/1097及原VFS Runtime消费验证通过，81基线/118清单匹配，B1/B2及整体集成均通过；CT06 DONE。见CT06复核第7节。
- 授权原Paseo Lead按Lead→Paseo worker推进CT07 Ingestion与最小HTTP，先方案后实现、逐批初验和独立复核；CM1完成6/7，CM2不启动。

## 本次修改（2026-10-28，CT05整体收口与CT06启动）

- CT05独立常规1050/1050及格式通过，81基线/104项清单匹配，B1～B3均通过；CT05 DONE。见CT05复核第8节。
- 授权原Paseo Lead按Lead→Paseo worker推进CT06配置与Runtime，先方案后实现、逐批初验和独立复核；CM1完成5/7，CT07待前置，CM2不启动。


## 本次修改（2026-10-28，CT04整体收口与CT05启动）

- CT04独立常规973/973、格式及原VFS Runtime消费验证通过，81基线/87项清单匹配，F1/F2/F3均关闭；CT04 DONE。见CT04复核第20节。
- 授权原Paseo Lead按Lead→Paseo worker推进CT05 Repository，先方案后实现、逐任务初验和独立复核；CT06/CT07待前置，CM2不启动。CM1完成4/7。


## 本次修改（2026-10-26，CT04 独立复核）

- CT04 现有929例测试通过，但独立故障与竞争探针发现R1～R6，状态IN_REVIEW；原Lead组织修正，CT05未放行，详见CT04架构与技术复核。

## 本次修改（2026-10-26，公共能力迁移独立验收通过）

- CT03 公共能力迁移通过独立代码复核、884 例回归和 common 独立消费验证；CT01～CT03 DONE，CT04 IN_PROGRESS。详细结论见 `docs/context/tasks/cm1-ct03/架构与技术复核.md` 第 5 节。


## 本次修改（2026-10-26）

- 用户要求时间和通用ID能力迁移到common，作为CT03增量逐级开发及复核，CT04等待。

- CM1 逐任务推进：CT01～CT05 DONE，CT06 IN_PROGRESS；最新状态与证据见 [开发进度](开发进度.md)。

## 本次修改（2026-10-25）

- 建立 Context System 文档入口，新增开发计划和进度台账；设计、任务、复核及交付说明统一归档在 `docs/context/`。
- 用户授权 CM1 开工时启动 CT01；见 [CM1 任务书](milestones/cm1/CM1_任务与验收.md)。逐任务复核，CM1 完整验收后汇报，CM2 尚未启动。

## 阅读顺序

| 文档 | 用途 |
| --- | --- |
| [架构设计 v0.1](<Alcyone Context System 架构设计 v0.1.md>) | 业务定位、权威数据与 MVP 范围 |
| [核心数据流与接口设计 v0.1](<Alcyone Context System 核心数据流与接口设计 v0.1.md>) | Conversation、Extraction、Organization、Map、Retrieval、Dreaming 的业务语义 |
| [技术实现设计 v0.1](<Alcyone Context System 技术实现设计 v0.1.md>) | 已定稿的十个工程专题、六个里程碑、测试与协作要求 |
| [开发计划](开发计划.md) | 里程碑、任务拆分、依赖、验收标准及设计衔接问题 |
| [开发进度](开发进度.md) | 当前实现状态、证据、待解决事项与下一步 |

技术实现设计已明确修订的条款按最新确认执行：Map 使用 T1 快照、最终一致性与 Cron 补偿，不再执行发布前 T2 检查或共享发布锁；SQLite 时间统一为上海 `+08:00` 固定毫秒文本。其余未明确批准的设计冲突由 Architect 记录并处理，不能让实现方自行选择。

## 目录约定

Context 里程碑使用 `CM1～CM6`，对应技术实现设计 §10.7 的 M1～M6；任务使用 `CT01～CT32`，避免与既有 VFS 的 M0～M6、T01～T36 混淆。

后续文档在实际启动任务时创建，不预填验收结论：

```text
docs/context/
├── README.md
├── 三份现有设计文档
├── 开发计划.md
├── 开发进度.md
├── milestones/cm1/milestone-review.md  # 各里程碑集成复核，cm2～cm6 同理
├── tasks/cm1-ct01/                    # 任务说明、交付/初验、Architect 复核
├── evals/                            # 评测方法、样本说明、脱敏结果与问题清单
└── delivery/                         # SDK/HTTP 使用、部署、备份与最终交付说明
```

以上子目录按任务推进创建，当前已建立 CM1 任务书；未完成项目不预填验收结论。可执行测试、Prompt 资源及评测程序放对应工程位置，文档通过路径引用；私密原文、密钥和原始运行日志不作为普通文档提交。

既有 VFS/E1 文档保留原位置，Context 文档引用其证据，不迁移历史记录。用户本轮要求优先于根 AGENTS 中通用的 `docs/tasks/` 约定，**新增 Context 任务文档统一放 `docs/context/tasks/`**。
