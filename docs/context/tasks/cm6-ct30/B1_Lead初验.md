# CT30 B1 Lead 初验

## 本次修改（2026-11-12，完整初验提交 Architect）

- clean `./scripts/check`：**137/137 tasks，3m56s**；14 模块 **2295=2295，0 failures/errors/skipped**。Core 339、Persistence 58、integration-vfs 121，其余不变。
- 最后补证 fresh：`DefaultVfsRecoveryProgressTest` **12/12**（progress 失败禁止删源、Storage 主失败不被诊断覆盖）；`RecoveryJournalSqliteTest` **6/6**（真实 JDBC v1 经 1.sqm/2.sqm 到 v3，Mount/Node/Metadata/Event 保留）。仅改这两个测试文件，生产未改。
- 81 manifest：69 unchanged、12 cumulative expected changed、0 missing；包含此前 CT17～CT29 的 Vfs/Options/Storage/Runtime 变化，不声称全基线不变。`1.sqm` SHA `1158632e…1bb`、`2.sqm` SHA `26ea0a36…d3`；Core 无 Ktor/serialization/SQLDelight/OpenDAL 依赖。
- B1 仅 recovery Core/Persistence/integration-tests；默认 Runtime/API 不接 recovery/reconcile，无自动重放/补偿/补事件。B2 sequence/cursor、B3 dirfsync 未做；真实 WebDAV、多进程/断电、生产部署 not-run。
- 状态：**IN_REVIEW，提交 Architect `9e48d029-098a-49da-8f67-20f8cf8c9e9a`**；不启动 B2。

## 本次修改（2026-11-11，B1-R1 补证派发，未通过最终验收）

- 接手 Lead 独立核对实际代码并 fresh 重跑 Core/Persistence 定向：RecoveryJournal 2/2、OperationReconciler 2/2、SafeReplace 12/12、Move 14/14、CopyMove 25/25、Schema lifecycle 7/7，exit 0；日志 `/tmp/ct30-b1-lead-targeted.log`、`/tmp/ct30-b1-lead-core-extra.log`。这些通过不构成 B1 DONE。
- 独立审查确认 journal v3/`2.sqm` 与既有 v2 mount migration 分离；默认 Runtime 未接 recovery。尚缺真实 v2 库升级、CAS/闭态事务、完整四操作窗口、真实 Local FS 双 JVM 和真实栈只读 reconcile 证据。
- 已落 `B1_R1_验收修复清单.md` 并派发唯一 Worker `c23acac1-f0d1-48b3-9676-31d9bcbdda81`（message `e17e6a5c-30f3-4d3f-89b2-95004ad67e6e`）。仅允许 B1 接缝与测试补证，不扩范围、不启动 B2，无 Git 写操作。

## 历史修改（2026-11-11，真实实现存在但交付停滞，交接阻断）

- 取代早期“无实现产物”的历史结论：真实 Worker `5b891a3b-2b3b-4f5b-a26c-42815bb12146` 已写入 recovery 组件、v3 migration、四操作接缝与部分测试。
- 经精确状态追问和有限观察，Worker 声称已就位的真实栈/双 JVM/交付文档在磁盘上不存在，且活动记录退化为重复承担 Lead 审查；当前为 **BLOCKED / HANDOFF REQUIRED**，不是 APPROVED 或 IN_REVIEW。保留全部代码与证据，交由编排层替换 Worker，不继续无目标轮询。
- 本 Lead 未修改生产/测试/Schema，无 Git 写操作，未启动 B2/B3/B4。

**结论：BLOCKED — Worker 上下文退化，硬门禁未交付；需保存现场后替换 Worker。**

## 1. 已确认的真实产物与接缝

当前真实存在：

- Core：`recovery/RecoveryOperation.kt`、`RecoveryJournal.kt`、`OperationReconciler.kt`。
- Persistence：`SqliteRecoveryJournal.kt`、`Recovery.sq`、`2.sqm`。
- 既有接缝：`DefaultVfs.kt`、`transaction/UnitOfWork.kt`、`event/EventPipeline.kt`、`SqliteUnitOfWork.kt`。
- 测试：`core/recovery/OperationReconcilerTest.kt`、`RecoveryJournalSqliteTest.kt`，以及 `DefaultVfsSafeReplaceTest.kt` / `DefaultVfsCopyMoveTest.kt` 中部分 recovery 故障用例。

只读审查确认：

1. begin/advance/diagnose 使用 generation + phase CAS；CLOSED 不参与后续更新；close 通过既有 TransactionScope 与 Node/Event 同事务执行，没有第二套提交器。
2. 四操作只接 `safeReplace`、`publishNew`、native file move、copy file move；directory move 未接 journal。
3. 四操作起笔已移到父目录处理后；begin 失败接受 `createdDirectories`，已建目录时返回 PARTIAL。
4. `OperationReconciler` 的 list/find/Storage/Node 读取均进入显式共享 `StateBoundary`；UNKNOWN 不解释为 ABSENT，未见重放、补偿或自动成功事件。
5. default 构造路径 `recoveryJournal = null` 保持；本 Lead 未把 Runtime 默认接线写成已启用。
6. 既有 `1.sqm` SHA 保持 `1158632e1c7dcffdc19913b55ab0f8f6602659a74af73050359643d0d0be31bb`。
7. copy move 目标写后的 stat/type/length 失败路径是 Worker 自报修复点；必须在后续 fresh 验证中检查受限诊断和停止删源，而非仅看注释。

13 个核心新文件/接缝 SHA 快照保存于 `/tmp/ct30-b1-handoff.sha`。由于 CT27/CT28 同工作树已有改动，Git diff 不能直接等同 B1 写集合，后续 Worker 必须以此快照及任务边界继续。

## 2. 已执行与不可采信证据

- Lead 委派前旧基线构建日志 `/tmp/ct30-b1-baseline.log`，BUILD SUCCESSFUL 但 up-to-date；不是 B1 fresh 验收。
- Worker 精确状态回复：曾执行 `./scripts/dev gradle :vfs:core:test :vfs:persistence:test --console=plain`，BUILD SUCCESSFUL，但全部 UP-TO-DATE，明确不能冒充 fresh。
- 当时 XML 快照 Core 321、Persistence 54；integration 112。此处只是读取现有 XML，不是本 Lead fresh 重跑，不能据此通过。
- Worker 后续活动声称“三模块 fresh 通过（36 秒/79 tasks）、恢复日志/迁移/定向真实栈已就位”，但磁盘检查未发现其承诺的 `RecoveryJournalRealStackTest.kt`、任何 recovery 双 JVM helper/test、交付说明或对应新日志。故这些口头声明均 **未采信**。

本 Lead 未执行最终 fresh 测试，因为 Worker 仍在共享工作树修改且硬门禁/交付集合未稳定。替换 Worker 后必须先固定 SHA，再由新 Worker 完成实现，Lead 独立 fresh 复验；不得把以上缓存 XML 或 Worker 自述作为结论。

## 3. 未完成的硬门禁与交接清单

后续 Worker 仅继续 CT30 B1，不重做 CT27/CT28/CT29：

1. **四操作 Core 行为**：正常 close、begin 失败阻断 Storage、advance 失败停止后续外部写、Storage/取消诊断、Node+Event+close 原子回滚；核对 copy move 目标 stat/type/length 失败只读诊断。
2. **fresh Schema/migration**：fresh v3、真实 v1→v2→v3 与 v2→v3，保留 mount/node/event 数据；既有 `1.sqm` SHA 不变。
3. **真实 SQLite+Local FS**：四文件操作及跨 Mount 各故障点，reconcile 前后 journal/Node/Event/文件摘要不变，UNKNOWN 安全语义。
4. **两个真实 JVM**：实际子进程写入后 kill，再由独立 JVM 重开；证明 pending 可读、只读 reconcile、无自动重放/补偿/事件。
5. **回归与交付**：变更后 `spotlessApply`/`spotlessCheck`、VFS 81 基线、integration-tests、全量 `./scripts/check`，列出文件/SHA、fresh 命令与 XML、default-disabled、not-run。
6. **边界**：B1 不接 Runtime 默认 recovery；B2 Runtime opt-in、B3 操作指南、B4 最终验收均未开始。

## 4. 阻断原因与最小请求

Worker 最后精确回复称无技术阻断，下一份交付为 `integration-tests/vfs/.../RecoveryJournalRealStackTest.kt`；但随后该文件及双 JVM/文档始终未落盘。其活动记录又反复出现“把 Lead 的旧 BLOCKED 当当前事实”“准备承担独立审查/全量验收”等角色混乱，构成上下文退化，不再适合继续共享工作树写入。

请编排层：

- 停止当前 Worker 的后续写入，保留 `/tmp/ct30-b1-handoff.sha` 与本记录；
- 创建具有干净上下文的替换 VFS Worker，只读取本记录、任务书、委派单、Architect 裁定及当前文件，先确认 SHA 和实际缺失项，再从上述第 1～5 项继续；
- 新 Worker 提供真实 ID 与完整交付后，本 Lead 再独立验收。无需 Architect 重做技术方案，也不要求新 Worker 从零实现。
