# CM1 工程基础与 Ingestion：任务与验收

## 本次修改（2026-10-29，全面复查 F4：里程碑状态归位）

- **历史验收保持**：CT01～CT07 全部 DONE；整体初验常规 1142=1142、138 项源码清单、81 VFS 基线、独立 VFS Runtime 消费与 `t31-consumer-check` 通过，历史证据不作废。
- **本轮全面复查已 CLOSED**：F1、P1、P4、F2、F3、P2、P3、F1-R1 共 8 项逐项 APPROVED（Lead 初验 + Architect 独立复核）；F4 入口文档归位完成；整体回归通过（`./scripts/check` exit 0、常规 **1188=1188**、源码与 XML 差额全 0、VFS tracked diff 为空、`t31-consumer-check` exit 0）。
- **里程碑状态**：CM1 **DONE（含全面复查）**；按用户条件授权放行 **CM2**，首任务 `CT08`。详见 [CM1 全面复查](CM1_全面复查.md)。


## 本次修改（2026-10-29，CT07 收口与 CM1 整体验收启动）

- Architect 独立复核 CT07 整体（复核 §6）通过：`./scripts/check` exit 0、130 tasks、常规 1142=1142 差额 0、138 项源码清单与 81 VFS 基线 mismatch=0。授权 Lead 将 **CT07 标 DONE** 并组织 CM1 整体集成初验。CM1 完成 **7/7**；CM1 整体复核通过前不启动 CM2、不公开发布。

## 本次修改（2026-10-29，CT06-B2 收口）

- B2经Architect独立49例、格式、真实配置所有权探针与81基线验证通过；B1/B2均APPROVED，原Lead组织CT06整体集成初验后提交Architect。CT06尚未DONE，CM1仍5/7，CT07待前置，CM2不启动。

## 本次修改（2026-10-29，CT06-B1 收口与 B2 放行）

- Architect 独立 22 例、格式与三条路径探针通过，81 基线匹配；CT06-B1 APPROVED，R1/R2 关闭。原 Lead 复用现有 Worker 推进 B2 生命周期与集成，完成后独立复核。CM1 仍 5/7，CT06 IN_PROGRESS，CT07 待前置，CM2 不启动。

## 本次修改（2026-10-28，CT05整体收口与CT06启动）

- CT05独立常规1050/1050及格式通过，81基线/104项清单匹配，B1～B3均通过；CT05 DONE。见CT05复核第8节。
- 授权原Paseo Lead按Lead→Paseo worker推进CT06配置与Runtime，先方案后实现、逐批初验和独立复核；CM1完成5/7，CT07待前置，CM2不启动。


## 本次修改（2026-10-28，CT04整体收口与CT05启动）

- CT04独立常规973/973、格式及原VFS Runtime消费验证通过，81基线/87项清单匹配，F1/F2/F3均关闭；CT04 DONE。见CT04复核第20节。
- 授权原Paseo Lead按Lead→Paseo worker推进CT05 Repository，先方案后实现、逐任务初验和独立复核；CT06/CT07待前置，CM2不启动。CM1完成4/7。


## 本次修改（2026-10-28，CT04-F2 独立收口与F3放行）

- F2 APPROVED：生产中断清理/回调守卫反例通过，A04测试尾项关闭；本轮定向A04及格式通过，沿用此前独立70例，生产哈希/81基线不变。见CT04复核第12节。
- 授权原Paseo Lead按Lead→Paseo Worker推进F3事务失败与作用域边界，先方案后实现。F1/F2已通过；CT04仍IN_REVIEW，CT05未放行。

## 本次修改（2026-10-27，CT04-F1 独立收口与F2放行）

- F1文件保护及测试尾项通过Architect独立复核：强制60例与格式检查通过，最终12项交付哈希/81基线一致。详见CT04架构与技术复核第7节。
- 授权当前Paseo Lead `6f547b7e-4ced-4f1b-92a7-e665b2d008db` 按Lead→Worker推进F2资源所有权与关闭协议，先方案后实现；只能使用Paseo worker配置委派。CT04仍IN_REVIEW，F3/CT05未放行，CT01～CT03 DONE保持。

## 本次修改（2026-10-26，CT04 首次独立复核）

- 独立常规 929/929 通过，但故障/竞争探针确认连接泄漏、关闭与作用域绕过、建库保护失效、失败事务提交及异常泄漏；R1～R6 待修。CT04 IN_REVIEW，CT05 未放行，详见 `docs/context/tasks/cm1-ct04/架构与技术复核.md`。CT01～CT03 DONE 保持。

## 本次修改（2026-10-26，公共能力迁移独立验收通过）

- CT03 公共能力迁移通过独立代码复核、884 例回归和 common 独立消费验证；CT01～CT03 DONE，CT04 IN_PROGRESS。详细结论见 `docs/context/tasks/cm1-ct03/架构与技术复核.md` 第 5 节。


## 本次修改（2026-10-26，用户授权公共能力迁移）

- CT03 增量迁移时间/通用ID算法到 common，Context 保留业务前缀适配；CT03 IN_PROGRESS，CT04 WAITING（待迁移独立复核），历史通过结论保留。

## 本次修改（2026-10-26，CT03 收口）

- CT03 R1～R3 关闭，独立880例及copy探针通过；CT03 DONE，CT04 IN_PROGRESS。

## 本次修改（2026-10-26，CT03 首次复核）

- 独立常规 864/864 通过，copy 不可变保护与错误/Port 契约 R1～R3 待修，CT03 IN_REVIEW，CT04 未放行。

## 本次修改（2026-10-25，CT02 收口）

- CT02 R1 关闭，独立验证通过，CT02 DONE、CT03 IN_PROGRESS。

## 本次修改（2026-10-25，CT02 首次复核）

- CT02 独立常规 840/840 通过，依赖封装 R1 待修，保持 IN_REVIEW，不放行 CT03。

## 本次修改（2026-10-25，CT01 收口与 CT02 启动）

- CT01 R1～R4 全部关闭，独立复核通过；CT01 DONE，CT02 IN_PROGRESS，原 Lead 继续逐项交付。

## 本次修改（2026-10-25，CT01 正式复核）

- CT01 独立探针全通过，契约与文档 R1～R4 待修，状态 IN_REVIEW；未放行 CT02。见 [任务复核](../../tasks/cm1-ct01/架构与技术复核.md)。

## 本次修改（2026-10-25，用户授权开工）

- CM1 转为 IN_PROGRESS，CT01 启动，CT02～CT07 待前置复核；用户授权常规连续推进，每项独立复核，CM1 集成验收后汇报。
- 本文件是 Architect 对 CM1 Lead 的任务书；主线与验收依据见 [开发计划](../../开发计划.md) 和 [技术实现设计](<../../Alcyone Context System 技术实现设计 v0.1.md>)。

## 1. 目标与范围

交付可独立初始化、启动、关闭的 Context SDK 和最小 Ingestion HTTP 接入。标准 Turn 经规范化、校验、四元键幂等保存到独立文件型 SQLite；成功只承诺 COMMIT，不承诺记忆提取。

包括五模块骨架、模型/Port、时间/ID、六表五索引、WAL+FULL、1 写+少量固定读 Slot、Repository、Runtime 配置/资源所有权/生命周期、Ingestion 服务与 Runtime 绑定认证。仅按任务需要实现，不提前开发 Extraction、Organization、Map、Dreaming、备份、完整管理 HTTP 或正式部署。

代码基线 cdcf3a2。现有 docs/context 原始设计为用户提供的未跟踪文件，保留其内容与来源，不删除或整体重写。后续修改文档前部必须更新本次修改说明。

## 2. 逐任务执行

| 任务 | 状态 | 输出与关键验收 |
| --- | --- | --- |
| CT01 实施契约与兼容性核对 | DONE | 落实模块/API/配置边界和依赖版本方案；核实 SQLDelight 物理连接、JSON1、Online Backup；同步已批准的旧设计差异；列清后续发布缺口 |
| CT02 工程骨架 | DONE | 五模块及集成测试入口、版本单一来源、依赖方向、编译/格式验证与 VFS 兼容 |
| CT03 公共模型、Port、时间/ID | DONE（含公共能力迁移） | 稳定键与内部 ID 分离，读写上下文、不可变 Raw/来源模型、上海时间及 ID 边界验证；只定义后续必要 Port，不实现业务 |
| CT04 Schema、连接和生命周期 | DONE | 六表五索引；真实连接隔离、WAL/FULL/外键、固定线程、事务/取消/关闭，显式初始化和错版本保护 |
| CT05 Repository | DONE | 查询与约束、条件写行数、来源正反查、全局水位、Checkpoint CAS，真实数据库状态和查询计划证据 |
| CT06 Config 与 Runtime | DONE | 解析 context/vfs、现有 VFS 配置组装；create 无资源启动，start/close 竞态和失败清理，基础运行状态，不伪造未来 Worker。独立常规1097/1097、原Runtime消费、81/118清单通过 |
| CT07 Ingestion 与 CM1 集成 | DONE（含复查） | SDK 与最小 HTTP 共用 Core IngestionService；首次/重复/冲突/complete 重放、offset 契约、限额先于解析、认证绑定、并发/重开、唯一约束竞争重读、通知失败/取消可见、503/500 脱敏、关闭不死锁均有正式测试证据。B1/B2 均通过 Architect 复核，整体初验 1142=1142、138/81 清单通过 |

Lead 每次只启动一个任务。Worker 交付后 Lead 阅读 Diff/代码并独立执行适当验证，提交 IN_REVIEW；只有 Architect 明确通过，才允许标 DONE 并启动下一任务。任务文档归档于 docs/context/tasks/cm1-ctNN/，至少包含任务说明、交付/初验与独立复核记录。

## 3. CT01 具体要求

允许修改 Context 文档，编写范围明确的兼容性探针至 tools/validation 或忽略的 .local/validation；不修改生产代码/现有构建、不提前创建正式模块。必要的独立临时验证工程必须使用项目私有 JDK/Gradle、受控缓存和输出目录，不改变宿主工具配置。

1. 检查实际 VFS 接口和既有版本，锁定可复现依赖组合；新库版本以真实资料/解析结果核实，不能猜测。
2. 明确 Runtime 不依赖 Ktor Server，但未来 LLM Adapter 可使用 Ktor Client；YAML vfs 分区由 Context Runtime 解析并映射现有配置；VFS 在 Context start 时创建。
3. 同步上游已批准差异：Map T1 最终一致、取消 T2/发布锁、Cron 纳入 MVP、SQLite 上海固定毫秒文本；澄清实际 VFS 没有调用者 ACL，Context 承担接入权限，不扩大权限系统。
4. 为 CT04 提供 SQLDelight/JDBC Driver 与物理连接绑定、WAL/JSON1 和 Online Backup API 可用性证据。验证程度与未验证项写清，不能将 API 存在等同故障/恢复验收。
5. 固定 YAML 相对路径、环境变量缺失、未知字段、初始化入口、生命周期并发规则及 CM1 基础错误契约。普通实现选择自主做出，不引入额外框架。
6. Map 安全替换/备份大小留为 CM4/CM6 前置；仅提出差异方案，未经批准不修改 VFS、不降低已定安全保证。不因后续问题阻塞独立 CM1 基础交付。

验收：方案可用于 CT02～CT07，引用/依赖均可定位；兼容性探针可复现、实际运行结果有归属；现有生产/构建无改动；用户明确的 Runtime 配置方案无反复讨论。

## 4. CM1 集成验收门槛

- CT01～CT07 均通过 Worker 自测、Lead 初验、Architect 独立复核；不得以最后一次总测代替逐项审查。
- 实际跑完整构建/格式/常规套件，必要 VFS WebDAV 与独立原 Runtime 消费回归；区分历史 828/43 和本轮实际计数。
- 文件型真实 SQLite 验证幂等/约束/事务/读写快照、缺库/错版/损坏拒启；HTTP 输入限额、Runtime Key 绑定、响应丢失重试、提交后通知失败、关闭资源释放直接断言。
- SDK 与最小 HTTP 路径共用业务，Core 无 Ktor/JDBC/YAML 实现依赖；VFS 生产源码不变。取消不得吞掉，错误/日志不含 Key、会话正文或敏感连接信息。
- CM1 不要求实际 LLM、Linux 部署或后续记忆流水线验收；明确未实现/未验证，不能扩充数据结构或虚报能力。
- 复核结论写同目录 milestone-review.md，用户仅在 CM1 完整通过后收到交付通知。若遇到必须由用户决定的重大变更/不可解除外部阻碍，先完成可审查方案再报告。
