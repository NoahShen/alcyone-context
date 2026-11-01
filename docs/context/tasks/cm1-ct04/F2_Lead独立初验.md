# CT04-F2 Lead 独立初验（rev5，A04 正常路径直接 get 后的 Lead 独立初验，提交 Architect 收口）

## rev5 最终证据索引（Architect 收口整理）

- Lead rev5实跑日志：`.local/validation/cm1/lead/ct04-f2/lead-rev5-final-check.log`，构建成功；本消息提交的全模块计数为70例。测试最终SHA：`7b0326c45732d9b8b43e41fb3a15408008de08400d590f59b955ec26744dd109`。
- Architect本轮只定向运行A04及格式，1例通过；沿用此前独立70例与生产反例结果，不把本轮定向测试写成重跑70例。生产三文件哈希与rev2一致。
- 正常路径三个Future均在直线控制流中已成功赋值，get异常向外传播；safe-call内requireNotNull为冗余写法，不承担判据。finally保留尽力回收，关键断言全部受保护。
- 最终批准见《架构与技术复核》第12节；下方混合历史叙述以此索引与Architect最终结论为准。

## 历史：本次修改（2026-10-28，rev2：F2 rev2 复核整改后的 Lead 独立初验；A04 try/finally 边界前移后由上方 rev4 节取代，再由 rev5 正常路径直接 get 取代）

- F2 首次独立复核未通过（架构与技术复核第 8 节）。按第 8 节修订 F2 方案（清理责任不可被中断取消、所有用户回调统一嵌套/关闭守卫、正式测试排除提前清理与调度伪证），按 fresh `list_profiles` 原样应用 `worker` 配置（pi / my_magpie/group/worker，thinking high）创建唯一 F2-rev2 Worker `3c4b8a98`，单一写入者。F1 保持 APPROVED，F3/CT05 禁止启动。
- **生产改动（2 文件）**：
  - `ContextDatabaseExecutor.kt` → `52f72fed2387c15a321c7516cdd89faac09a97d5e4c3f77f48aa1e5c77b24113`
  - `ContextSlot.kt` → `d37573190f6a6aa5efcf7502882eead7d54ccabe69ebdff77968e25a5d0d41c9`
  - 测试 `F2ResourceOwnershipAndCloseProtocolTest.kt` → `51da01359cbec082063bde127285eb6a5ce870bd602cb473e92caf6970fa1b29`
  - 未改 `vfs/*`、`common/*`、`integration-tests/vfs/*`（`git diff --stat` 为空）、进度入口、AGENTS、Architect 复核文件；无 git 写操作。
- **R1 落地**（执行器 + Slot）：
  - `close()` 发起者在 `awaitDrainedBlocking()` 被中断时：记录中断标志 → 继续等待 inFlight 归零 → `releaseAll()` → 完成后恢复中断标志，**绝不在有在途 JDBC 时抢关连接**，最终结果与真实资源终态一致。被中断调用者语义写清并测试（`A08`）。
  - `ContextSlot.close()`：线程等待失败/被中断都不跳过物理 `connection.close()`；必要时 `shutdownNow`+重试 `await`；结果纳入判定；`disposed` 不先置真而阻断物理 close。
  - `CloseProtocol.awaitBlocking()`、`awaitDrainedBlocking(): Boolean` 中断继续等待，不放弃清理。
- **R2 落地**：`withUserCallback`（`insideCallback`/`insideScopeBody` 统一置位 + finally 恢复），`write`/`read`/`queryRaw` 都走它；`beginClose` 回调内 close 具名 `CLOSED`；同步/异步关闭具名拒绝、不死锁（`A09`、`R2MapperProbe`）。
- **R3 测试**：`A03` 构造/故障关闭/全部真实连接断言置于兜底之前、`closeAttempts==[1,0]`、多等待者同失败；`A04` 受控确认 sync close 已进入等待或已返回（`spinUntil{state==WAITING}`）；`A02` 收窄为版本校验 `createStatement`，新增 `A02b` 覆盖 `applyPragmas` 阶段故障与真实连接关闭；屏障/线程 `finally` 释放，失败分支不挂住。
- **Lead 独立实践（非引用 Worker 日志）**：`./scripts/dev gradle :context:persistence-sqldelight:test :context:persistence-sqldelight:spotlessCheck --rerun-tasks` → exit 0；源码行首 `@Test`=**70**、XML `tests=70`，0 failures/errors/skipped，差额 0（基线 67 + rev2 新增 3：`A02b`/`A08`/`A09`；rev4 仅前移 `try/finally` 边界，rev5 仅恢复正常路径直接 `get`，测试数均不变）。日志 `.local/validation/cm1/lead/ct04-f2/lead-rev5-final-check.log`。
- **Architect 原 `F2Probe.java` 独立重跑（修后产物）**：超时。经独立分析确认是**探针自身 harness 竞态**，非产品缺陷——`closer.interrupt(); closer.join(5000); release.countDown()` 是「先 join 后 release」，与修订后「中断不放弃清理、必须等在途写退出」的语义构成循环等待（close 阻塞等待 inFlight 归零，而归零需要在 join 之后才执行的 release.countDown()）。可独立复现，日志 `.local/validation/cm1/lead/ct04-f2/probe-after/lead-f2probe-after.log`。F2Probe 源码未改动。
- **harness-修正版 `F2ProbeRev2.java`（同等断言，修正释放顺序）**：5/5 GREEN（R1 `interrupted=false`、`interrupt flag restored=true`、`connections left open=0`；R2 `mapper insideScopeBody=true`、`waits for own request=false`、`named-rejected=true`）。日志 `.local/validation/cm1/worker/ct04-f2-rev2/probe-after/lead-rev2-probes.log`。
- **`R2MapperProbe.java`**：GREEN（`query completed=true`，mapper close 具名 `CLOSED`，mapper 线程 WAITING 非死锁）。
- **Lead 独立变异**：先前已对 rev1 的 M1（跳过写 Slot 释放）→ A01/A02 失败、M2（close 提前 return）→ A04 失败、M3（去掉回调内拒绝）→ A06 超时挂起，三条均咬住；rev2 在此基础上追加 A02b/A08/A09 并保持绿；rev4 `try/finally` 前移后 A04 保持 70 例全绿；rev5 正常路径直接 `get`，超时/执行失败会传播，`finally` 仍保留 `runCatching` 尽力回收。

## 判定

F2-R1/R2/R3 在正式测试中定点落实并可被变异/探针咬住；**F2 rev5 初验通过，提交 Architect 收口**。R3（F3 driver逃逸）/R5（事务）与 CT05 **未触碰、未放行**；F2 通过不等于 CT04 完成。

## 未关闭项（F3，不在本批）

- R3：原始驱动逃逸缩小、逐次操作作用域/线程校验；
- R5：rollback/autoCommit 失败不隐式提交、事务失败协议闭环；
- R6 剩余 Factory/Executor 分支异常链映射（如仍有）。
