# CT30 B4 Lead 初验

> **Architect 终态（2026-11-12）**：已独立接受本初验与验收矩阵，裁定 **B4 APPROVED / DONE、CT30 APPROVED / DONE**。以下“建议”为当时 Lead 初验记录，最终依据见 [B4 Architect 复核](B4_Architect复核.md)。

## 本次修改（2026-11-12，只读初验提交 Architect）

- Lead 只读完成 [B4 验收矩阵](B4_验收矩阵.md)，逐项映射 B1/B2/B3 的 Architect 已批准证据、真实退出码、JUnit XML、Schema/生产 SHA 和 not-run 边界。
- 本轮**没有运行全量或新测试，没有修改生产/测试/Schema/构建/deploy，没有启动 CT31/CT32，没有 Git 写操作**。B4 的职责是冻结和核对既有证据，不以新增执行改变被验收字节。
- Lead 初验结论：**B4 PASS，建议 Architect 独立复核后判 B4 APPROVED / DONE，并据矩阵判 CT30 APPROVED / DONE。** 本结论不包含后续里程碑放行。

## 1. 逐批验收结论

| 批次 | Architect 已批准结论 | B4 初验 | 核心证据 |
|---|---|---|---|
| B1 | APPROVED / DONE | PASS | [B1 Architect 复核](B1_Architect复核.md) |
| B2 | R1 CLOSED；APPROVED / DONE | PASS | [B2 Architect 复核](B2_Architect复核.md) |
| B3 | R1 CLOSED；APPROVED / DONE | PASS | [B3 Architect 复核](B3_Architect复核.md) |

B4 将已批准能力拆为 A01～A10：恢复事务/阶段、Schema 迁移、四操作接缝、跨进程只读 reconcile、at-least-once cursor、poll/close、Local FS capability、原子发布身份保护、全量回归和声明纪律。十项均可在已签核证据中闭环，未发现批准范围内的正式证据缺口。

## 2. 关键真实结果

### 2.1 B1

- Architect fresh Core/Persistence/integration-vfs：exit 0，XML **339 + 58 + 121 = 518**，0 failure/error/skipped；日志 `/tmp/ct30-b1-architect-three.log`。
- 全量 `./scripts/check`：exit 0、**137/137**、14 模块 **2295=2295**；日志 `/tmp/ct30-b1-architect-check.log`。
- 真实 v1→v2→v3/v2→v3、失败迁移回滚、generation/phase CAS、CLOSED、Node/Event/journal 同事务回滚、四操作故障窗口、双 JVM 强杀和只读 reconcile 均获 Architect 接受。

### 2.2 B2

- R1 fresh 定向：Core 4、Persistence 4、integration 3 + 独立 JVM 1 全绿；五项生产 SHA 全部一致。
- 独立 JVM 首进程 handler 成功后 `halt(97)`，第二进程 exit 0 并重投同一 eventId，最终 cursor=2。
- 全量 `./scripts/check`：exit 0、**137/137**、14 模块 **2307=2307**、零失败/错误/跳过；日志 `/tmp/ct30-b2-r1-check.log`。
- R1 正式测试补齐无 hint poll、同毫秒/倒时钟 sequence、v3→v4、失败迁移回滚、慢 handler 不阻塞真实 append 和失败批 close。

### 2.3 B3

- 旧生产 SHA `916c87c8…` 的权威反例：exit 1，XML **10 tests / 2 failures / 0 errors / 0 skipped**；证明替换 staging 被误删的旧缺陷真实存在。
- 最小修复后 publishNew/safeReplace 定向：**28/28**；`:vfs:storage:check` exit 0、184 tests。
- 当前 macOS arm64 真实目录 probe：`actual=true capability=true`。
- 最终同窗全量：exit 0、**137/137**、14 模块 **312 XML / 2324 tests / 0 failures / 0 errors / 0 skipped**；源码声明 **2313 + 11 = 2324** 与 XML 一致，证据目录 `/private/tmp/ct30-b3-lead-final.LxlMeb/`。

## 3. 当前 Schema 与生产身份

Schema 当前为 **v4**，分版文件及当前 SHA-256：

| 文件 | 当前 SHA-256 |
|---|---|
| `1.sqm`（v2） | `1158632e1c7dcffdc19913b55ab0f8f6602659a74af73050359643d0d0be31bb` |
| `2.sqm`（v3） | `26ea0a363ee3948d2eb885210d2c2e1edf5fe705a94b99d8ff974799dd58c0d3` |
| `3.sqm`（v4） | `41291bb8423fa061c49d0d93165c53e3836f23f510df4433d03d6d9d726a0705` |

B2 五项生产当前 SHA 与 Architect 首次复核一致：Consumer `b18d5f59…e4e3`、Port `7266ad1f…860f`、SqliteRepositories `9f399dad…a245`、Cursor.sq `f7e9c678…ead5`、3.sqm `41291bb8…0705`。B3 生产 `LocalFsStorage.kt` 仍为 `b4b5e45d32ba724426e758fd39b7784b741a8680b6f72905236550bdb2e07215`，两个批准测试 SHA 也保持不变。

## 4. 已声明 not-run

以下均不是已承诺 CT30 能力的隐藏 GAP，而是 Architect 明示的生产/环境边界：

- 真实断电、内核崩溃、硬件 flush：**NOT-RUN**；进程强杀不等于断电。
- Linux dirfsync/Linux 硬件 flush：**NOT-RUN**；macOS arm64 的 true 不外推。
- 真实 WebDAV 服务和 WebDAV durable/atomic publishNew：**NOT-RUN**；现有 false capability 与显式拒绝不变。
- 默认 Runtime 自动 recover/reconcile、默认可靠订阅：**按设计未接线**；首版为显式只读/opt-in 组件。
- 多进程同名 consumer lease、exactly-once：**不承诺**；只承诺 at-least-once，消费者须按 eventId 幂等。
- 生产部署、CT31、CT32：**未运行/未放行**。

## 5. 流程事实与结论

- B3 曾发生一次已记录的重复全量 `LxlMeb`。B3 Architect 已直接核对同窗 XML 并作出 DONE 裁定；B4 不粉饰该流程问题、不启动第三次全量，也不以重复运行提高验收等级。
- B4 没有新增真实进程或测试证据，这是只读验收的预期分工，不是 not-run 缺陷；所有被引用结果均在 B1/B2/B3 签核前实际执行。
- 批准范围内的精确 GAP：**无**。若未来要求真实断电、Linux durable、WebDAV durable 或默认自动恢复，必须另立验收裁定和工作写集合，不能倒推本 B4 已通过。
- 建议 Architect 裁定：**B4 APPROVED / DONE；CT30 APPROVED / DONE**。CT31/CT32 保持 LOCKED，待后续独立只读方案。

提交对象：当前 Architect `0d2080d2-8a60-4b31-93b9-759b7ad2a723`。
