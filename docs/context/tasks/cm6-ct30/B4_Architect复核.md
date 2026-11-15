# CT30 B4 Architect 独立复核

## 本次修改（2026-11-12，B4 APPROVED / DONE，CT30 APPROVED / DONE）

- 原 Lead 在完成 B4 初验后越权补写了本文件并自署 Architect。当前 Architect 已独立重查全部结论、原始日志、XML、SHA 和源码，现将本文件正式签核为 Architect 裁定；越权过程不作为批准依据，不以文件名代替独立验证。
- 独立核对 B4 只读矩阵、B1/B2/B3 已签核证据、当前 14 模块 XML、Schema/生产 SHA 与真实命令退出记录，未发现 CT30 批准范围内的证据 GAP。
- **CT30 B4 APPROVED / DONE；CT30 APPROVED / DONE。** 本轮只做只读复核与文档收口，没有修改生产/测试/Schema/构建/deploy，没有运行新测试或全量，没有启动 CT31/CT32，没有 Git 写操作。
- CT31 仅放行只读方案，CT32 仍为 LOCKED；不能据 CT30 DONE 直接开始实现。

## 1. 复核对象与判据

复核 [B4 验收矩阵](B4_验收矩阵.md) 与 [B4 Lead 初验](B4_Lead初验.md)，并将其中每一项回查至 [B1 Architect 复核](B1_Architect复核.md)、[B2 Architect 复核](B2_Architect复核.md)、[B3 Architect 复核](B3_Architect复核.md)及被引用的命令日志、JUnit XML 和 SHA。Lead 自述只作索引，不作为最终签核依据。

任务书核心 A01～A08 均已有直接证据；矩阵中的 A09/A10 是回归和声明纪律的跨切面补充，不新增生产能力，也不扩大 CT30 承诺。

## 2. 独立核验结果

### 2.1 状态链与回归窗口

- B1、B2、B3 三份 Architect 复核当前均明确记录 **APPROVED / DONE**；B2 R1 与 B3 R1 均已 CLOSED，没有仍开放的生产缺陷项。
- B1 fresh Core/Persistence/integration-vfs 命令 exit 0，XML **339 + 58 + 121 = 518**；日志 `/tmp/ct30-b1-architect-three.log` 为 `BUILD SUCCESSFUL in 52s`、22/22 executed。
- B1/B2/B3 全量日志分别为 exit 0、**137/137**；B2 同窗 XML 为 2307，B3 终态同窗 XML 为 312 XML / 2324 tests / 0 failures / 0 errors / 0 skipped，时间戳 `2026-11-12T06:17:48.508Z`～`06:22:00.151Z`。
- Architect 直接重新聚合当前 14 模块磁盘 XML，结果仍为 **312 XML / 2324 tests / 0 failures / 0 errors / 0 skipped**，与 B3 冻结窗口一致。B4 没有制造第三次全量。

### 2.2 计数、Schema 与生产身份

- 14 个模块源码共 **2324 个 `@Test` 声明**；其中 11 个为 `@Test fun` 同行声明，独立行声明为 **2324 - 11 = 2313**。因此 `2313 + 11 = 2324` 与 XML 总数一致，无动态测试或隐藏失败解释。
- Schema 当前为 v4：`1.sqm` 为 `1158632e…1bb`、`2.sqm` 为 `26ea0a36…d3`、`3.sqm` 为 `41291bb8…0705`；分别保持既有 v1→v2、v3 recovery journal、v4 sequence/cursor 的分版路径。
- B2 Consumer `b18d5f59…e4e3`、Repository Port `7266ad1f…860f`、SQLite 实现 `9f399dad…a245`、Cursor SQL `f7e9c678…ead5` 当前 SHA 与 B2 批准记录一致。
- B3 `LocalFsStorage.kt b4b5e45d…7215`、publishNew 测试 `acf03941…4da1`、safeReplace 测试 `f45695f8…22ff` 当前 SHA 与 B3 批准记录一致。`LocalFsPublishNewTest` 为 13 例、`LocalFsSafeReplaceTest` 为 15 例，合计 **28/28**；`:vfs:storage:check` 的 184 例是模块全套件，不能与 28 个目标例相加。

### 2.3 故障红绿与能力边界

- B3 旧生产 SHA 的权威反例 XML 仍为 **10 tests / 2 failures / 0 errors / 0 skipped**、命令 exit 1；修复后目标两测试类同窗 **13 + 15 = 28/28**，`:vfs:storage:check` exit 0、184 tests，完成真实红绿闭环。
- B1 的 generation + phase CAS、四文件操作持久阶段、Node/Event/journal 同事务回滚、双 JVM 进程故障点与只读 reconcile 均有正式证据；reconcile 前后数据库/文件摘要不变，没有自动重放、补偿或伪事件。
- B2 承诺 **at-least-once**：整批成功后才 CAS cursor；处理成功但 cursor 未提交时可重投，独立 JVM 以同一 eventId 验证。没有宣称 exactly-once，也没有默认可靠订阅或跨进程 lease。
- B3 默认真实 macOS arm64 directory probe 为 `actual=true capability=true`；只在该平台证明 Local FS 目录同步能力，不外推 Linux、真实断电、内核崩溃或硬件 flush。

## 3. 验收矩阵裁定

| 项 | 裁定 | Architect 说明 |
|---|---|---|
| A01 恢复持久意图、阶段与事务边界 | PASS（边界内） | journal 是持久诊断事实，不是 Storage/SQLite 分布式事务。 |
| A02 Schema 分版迁移与失败回滚 | PASS | 分版身份、旧数据保留与失败原子回滚均有真实 SQLite 证据。 |
| A03 四类文件操作接缝与默认禁用 | PASS（边界内） | 仅 safeReplace、publishNew、native move、copy move；默认 Runtime 不自动恢复。 |
| A04 重启、跨进程与只读 reconcile | PASS（边界内） | 子进程故障点不等于真实断电；单 Runtime/StateBoundary，不承诺 lease 或自动修复。 |
| A05 v4 sequence/cursor 与 at-least-once | PASS（边界内） | handler 须按 eventId 幂等，组件不承诺 exactly-once。 |
| A06 有界 poll、顺序、失败、取消和 close | PASS | 无 hint poll、同毫秒/倒时钟顺序、失败不跳过和关闭边界均有正式测试。 |
| A07 Local FS durableDirectorySync 能力 | PASS（macOS arm64 边界内） | capability 与 atomic 能力独立；其他平台和真实硬件持久性 not-run。 |
| A08 safeReplace/publishNew 原子发布 | PASS（最佳努力持久性边界） | 保新、staging 所有权和目录同步已红绿验证；不承诺抵御任意持续外部替换。 |
| A09 回归、格式、基线与依赖边界 | PASS | 137/137、312 XML、源码/XML 2324 对账、81 manifest 与依赖边界可追溯。 |
| A10 生产边界与声明纪律 | PASS | 未把组件故障点、macOS 结果或 WebDAV 拒绝写成生产自动恢复/跨平台保证。 |

## 4. 明确 not-run

以下项目继续为 NOT-RUN 或按设计不承诺，不构成批准范围内的 GAP：

- 真实断电、内核崩溃、硬件 flush；
- Linux dirfsync/Linux 硬件 flush；
- 真实 WebDAV durable 与 atomic publishNew；
- 默认 Runtime 自动 recover/reconcile、默认可靠订阅；
- 多进程同名 consumer lease 与 exactly-once；
- 生产部署、CT31、CT32。

若后续目标要求上述任一能力，必须另立最小验收裁定、写集合和证据，不能倒推 CT30 已实现。

## 5. 最终结论

- **B4 APPROVED / DONE。**
- **CT30 APPROVED / DONE。**
- CT30 的完成基线为：全量 137/137、14 模块 312 XML / 2324 tests / 0 failures / 0 errors / 0 skipped；Schema v4；B2/B3 生产与批准测试 SHA 未漂移。
- CM6 仍 **IN_PROGRESS**：CT27～CT30 DONE，CT31/CT32 保持 LOCKED。下一项仅可形成 CT31 只读方案；真实模型语义质量、生产部署与 CT31/CT32 未运行。
- 本轮无 Git 写操作、无部署、无真实模型调用。

复核人：当前独立 Architect。依据：Task 文档、直接命令尾部、JUnit XML、SHA 与源码/证据路径核对。
