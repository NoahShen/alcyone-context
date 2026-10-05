# T17 Metadata 查询与更新开发及验收

## 本次修改（2026-10-05，6862ef7 收口通过）

- `6862ef7` 独立收口通过，R1 与 S1～S3 全部关闭，T17 DONE；独立完整检查 542 例通过。涉及当前状态与测试计数，见 [复核第 7 节](T17_架构与技术复核.md)；T18 可以开始。

## 本次修改（2026-10-04，T17 实现完成，状态 IN_REVIEW）

- 实现 `DefaultVfs.getMetadata` / `setMetadata`：纯状态库查询、整体替换、有效 Node 校验、共享锁、同事务 touch 与事件、失败与取消语义；§2.1～§2.4 与验收标准 A01～A08 对应实现与证据。使用说明、验收记录与交付文档见同目录；§2.2 的同值事件 / 更新时间选择已回写 [T01 §6.2](../../vfs/VFS_公共契约草案_v0.1.md)。本轮未实现 `move`、Runtime 与字段级 patch。

## 本次修改（2026-10-04，任务初稿）

- 明确 Metadata 查询 / 替换、有效 Node 校验、共享锁、事务与事件，给出 S1～S3 和 A01～A08；涉及全文。本轮仅安排文档，不修改实现。

状态：**DONE，6862ef7 独立收口通过**。当前正式测试 **542** 例（common 10、api 65、core 237、persistence 47、storage 136、integration 47）。

依据：[T01 §5～8](../../vfs/VFS_公共契约草案_v0.1.md)、[T02 §10](../../vfs/VFS_路径与文件操作语义_v0.1.md)、[T03 §4、6](../../vfs/VFS_状态与恢复设计_v0.1.md)、[UC-06](../../vfs/Alcyone_VFS_核心用例文档_v0.2.md)、[T14](../m3-t14/T14_架构与技术复核.md)、[T16 收口](../m3-t16/T16_架构与技术复核.md)。

## 1. 目标与范围

实现现有 `DefaultVfs.getMetadata(id)` 与 `setMetadata(id, metadata)`，复用 MetadataRepository、NodeRepository、StateBoundary 与 EventPipeline。只做 Core 编排和必要依赖接线，真实 SQLite 组合测试放 integration-tests；不新增公共 API、Schema、字段级 patch、身份管理或通用 Metadata 框架。

例：先 stat 得到文件 ID，再设置标签与说明；再次查询返回保存的完整对象。文件移动后的 ID 连续性由 T20～T22 验证，本轮只确保 Metadata 始终按 ID 保存，不提前实现 move。

## 2. 开发内容

### 2.1 纯逻辑查询

先确认 Node ID 有效，再读取 Metadata；有效 Node 尚未设置时返回 `NodeMetadata()`，未知或已删除 ID 返回 NOT_FOUND，不能把无效 ID 当成空 Metadata。文件、目录和已登记虚拟目录采用同一规则，不查 Mount、不 stat 物理文件、不懒注册、不产生事件。

例：外部直接移除物理文件但 Node 仍有效时，仍可按 ID 读写 Metadata；经 VFS.delete 成功标记删除后，该旧 ID 的两种 Metadata 操作都报 NOT_FOUND。

### 2.2 整体替换与逻辑更新时间

setMetadata 整体替换 tags / description / extensions，空对象清空；物理后端只读不影响状态库更新，复用已有 NodeMetadata 与 Repository 语义，不增加业务字段校验或标签规范化。**本任务采用的最小规则**：每次有效 set 调用都执行替换并产生一条 METADATA_UPDATED，包括相同值和重复清空，不增加相等比较或去重机制。

同一事务内以现有时钟 touch Node.updatedAt，保留 ID、路径、类型和 registeredAt；这是 T01「逻辑记录更新时间」的落实，不改物理文件时间。例：原对象有 tags 和 description，替换为只有 description 的对象后 tags 为空；时钟精度内连续设置可取得相同时间，不要求时间严格递增。

### 2.3 唯一持锁与同事务事件

查询的 Node 校验与 Metadata 读取共享一次 StateBoundary；更新的 Node 校验、Metadata 替换、touch 与事件追加在同一次 UnitOfWork 内，成功返回后才通知。复用 EventPipeline.commit，或已持锁时用 commitInsideBoundary，二选一避免二次取锁；事务内写入使用 TransactionScope，不使用自动提交仓库替代。

事件 nodeId 为目标 ID，uri 为同一受保护状态下读到的当前 Node 路径，类型 METADATA_UPDATED；查询没有事件。例：更新与删除竞争时按共享锁顺序执行，删除先成功则更新报 NOT_FOUND，不能给已删除 Node 写回 Metadata。

### 2.4 失败与取消

保留 NOT_FOUND 等领域错误，状态库普通失败按现有 Port 契约报告 STATE_ERROR 并保留 cause；确认事务回滚的测试情形为 NONE，没有 Storage 副作用，不套用 T16 删除后的 PARTIAL。不要把已有 UNKNOWN 等失败事实无条件降为 NONE，或因为捕获异常就宣称一定未提交。

CancellationException 原样传播；COMMIT 前取消应回滚，COMMIT 后取消不证明提交撤销，通知队列丢弃或 Consumer 失败也不撤回已提交更新。例：真实事件追加冲突时，Metadata 和 Node 时间一起回滚，没有成功通知。

## 3. 实施计划

| 步骤 | 内容 | 交付证据 |
| --- | --- | --- |
| S1 查询与替换 | 解锁两种入口、注入所需 Port、有效 Node 校验、整体替换与清空 | 小型替身：无 Storage 调用、无效 ID、相同值与空对象 |
| S2 事务与事件 | 共享边界、逻辑时间、事件字段、失败与取消 | 可控挂起验证、真实 SQLite 事务回滚 |
| S3 组合与交付 | 与已有 stat / write / delete 最小接线、正式检查、使用说明 | 真库持久化、删除后旧 ID、分层验收记录 |

沿用已有夹具；DefaultVfs 构造依赖可按职责补 MetadataRepository 等 Core Port，并同步调用点，不要求兼容尚未发布的内部构造签名。不新增生产测试开关，也不要求每个既有 Repository / Dispatcher 分支在 T17 重测。

## 4. 验收标准

| 编号 | 必须满足 | 核心证据 |
| --- | --- | --- |
| A01 查询与有效性 | 有效 Node 无 Metadata 返回空对象；未知 / 已删除 ID 的 get 与 set 均 NOT_FOUND | 替身边界 + 真库至少覆盖有效空值、已删除 ID；无效更新不写 Metadata / 时间 / 事件 |
| A02 替换与清空 | tags / description / extensions 完整往返，替换不隐式合并，空对象清空 | 真 SQLite 验证各字段和再次打开数据库后的结果；复用现有生命周期夹具，不扩展 Runtime |
| A03 纯逻辑与身份 | 两种操作不调用 Storage 或依赖物理可写性，文件 / 目录 / 已登记虚拟目录均可用 | 替身用一碰 Storage 即失败或精确调用记录；只读后端、物理已缺失但 Node 有效；真栈经 delete 后旧 ID 拒绝 |
| A04 时间与事件 | 每次成功 set 含相同值 / 清空各一条 METADATA_UPDATED，字段正确；get 零事件 | 可控时钟检查 updatedAt、registeredAt / ID / 路径不变；提交后通知，先订阅并按事件 ID 等待，勿依赖次数或 sleep |
| A05 原子失败 | Metadata、Node 时间、事件同事务；失败 cause 保留，确认回滚时 STATE_ERROR / NONE | 在已有非空 Metadata 上更新并制造真实 SQLite 事件主键冲突，验证旧值与时间恢复、事件不增加；按 ID 末尾标记验证无失败通知 |
| A06 串行与取消 | 查询 / 更新遵守同一边界，无脏读或自锁；取消原样传播、锁释放 | 一个受控更新占锁时让后续 Metadata 查询实际启动，证明尚未读仓库，放行后读取已提交值；一个事务内写入后、COMMIT 前取消用例，从调用协程内部捕获原异常并核对回滚，后续同锁操作可完成 |
| A07 范围与回归 | Core 仅依赖 Port，公共 API / Schema 无不必要变化 | 原阶段拒绝只剩 move；已有文件操作、删除测试无回归；移动保留 Metadata 的端到端验证留后续 |
| A08 工具链与文档 | 使用项目内 Java / Gradle，原 528 例无回归，新增测试真实执行 | scripts/check、分模块 XML、提交号与平台；开发自测和独立结论分开，真实栈 / 替身 / 源码审查分开，不要求变异或十连跑 |

例：A06 的取消若只发生在进入事务前，就不能宣称证明更新回滚；选择一个确定的事务内挂起点即可，不穷举 COMMIT 后取消窗口或 T27 生命周期场景。

## 5. 交付与后续

同目录补 `T17_交付文档.md`、`T17_验收记录.md`、`T17_使用说明.md`，更新文档须前置本次修改与涉及章节。交付回写 §2.2 的同值事件 / 更新时间选择到 T01 对应 Metadata 说明，用一个替换清空例子解释；不把未验证项写成保证。

开发完成标 IN_REVIEW，由复核方验收后标 DONE。本轮不自动实现 T18；T18 承接 Runtime 组装，T19 承接 SDK 入口，恢复与可靠消费继续延后。
