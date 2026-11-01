# CT09 Architect 裁定

**日期**：2026-10-30（Asia/Shanghai）  
**状态**：D1～D8 已裁定；批准 B1，后续批次待逐批独立复核。

本裁定优先于 CT09 草案中的候选建议。CT08 DONE 不重开；本轮仅核对设计、现有源码与制定实现边界，没有新增测试实跑。已有 CT08 独立 1296/1296 记录仍归 CT08。

## 1. D1 / D2：配置与 Cron

- 配置采用 `context.extraction.triggers.count.threshold=5`、`context.extraction.triggers.idle.enabled=true`、`idleTimeout=10m`、`cron="*/5 * * * *"`。全部可省略，逐层严格拒绝未知键；不增加 `count.enabled`。
- threshold 为 Int 且 >=1；idleTimeout 为有限正 Duration，转换及截止时间计算必须避免溢出。沿用现有 Loader 类型/错误口径，直接构造与 YAML 加载共同校验，不能只靠 Loader。关闭 Idle 不豁免配置有效性校验。
- UNIX 五字段 Cron，固定 `Asia/Shanghai`，不提供 timezone 键。非法值为 `ContextConfigException(INVALID_CONFIG)`，含字段路径，不附带可泄漏原值的解析 cause。
- `idle.enabled=false` 仅关闭 Idle；有 Runner 时 Count 仍启用。无 Runner 的阶段边界见 D3。
- 每次从当前时刻选择严格未来的 tick；不启动即扫、不补跑停机或扫描期间错过的 tick。时间推进用注入 Clock/tick 验证，不通过真实等待五分钟验收。
- 技术设计中可配置时区的泛化说明在 CT09 按 CM2 Q6 收窄；配置示例以本裁定层级为准。Lead 应核准实际章节标题再修正文档引用，不能继续把技术设计当前 §7.3（Memory Map 最终一致性）称为 extraction 配置示例。

## 2. D3：Runner 与阶段性接线

选择方案 A。Core 新增 `core.extraction.ExtractionRunner`，`suspend fun execute(SessionKey)`；Runtime 的 pipeline 必须持有真实注入的 Runner。Core Port 因跨模块使用须具备 Kotlin public 可见性；它不属于 `context/api` SDK 门面，不能写成“没有任何新 public 类型”。Runtime 实现、诊断及测试接缝保持 internal。

CT09 公共 Factory 不提供 Runner 参数、不创建生产 pipeline；仅内部组装入口接受 Runner，验证 start/close 与真实 SQLite 通知链。CT11 接入真实服务后才能宣称生产提取启用。配置仍正常解析校验，不以 no-op Runner 假装完成生产接线；文档必须明示此阶段限制，不承诺 CT11 只需改一行。

Scheduler 保留 Runtime internal 实现即可，本批不额外增加没有跨模块调用方的 Core Scheduler Port。Count 使用既有 `IngestionNotifier`，签名不改。Runner 只负责一次执行，CT09 不实现 Snapshot、LLM 调用、Turn 状态变更或 CT10+。

## 3. D4：关闭与生命周期

批准 CT09 **立即取消并真实等待**，不先等待 Runner 自然完成，不新增 grace 配置；这是对早期设计 §1.8“先等超时再取消”的 CT09 明确收窄，沿用当前 Context `shutdownTimeout` 只观察、不强杀资源的已验收语义。不得以“CT11 已保证原子性”作为本阶段证据。

顺序为：先在状态锁下进入 CLOSING 并封闭所有 Trigger/Scheduler 准入，再停止 Cron 发起新扫描，取消并等待扫描、Worker 及其结构化子任务，最后清理队列/计数并发布关闭结果。Channel 可在封闭准入后关闭，但不 drain 排队任务，不把 close(Channel) 等同任务终止。关闭与 dequeue 竞争必须有一致的执行准入判定。

- owner 清理在 NonCancellable 内完成；并发 close 共享同一成功/失败结果，等待者取消只退出自己的等待。失败也必须发布终态，不能永久悬挂。
- 清空集合或计数为零不能证明任务结束；必须 join 真实 Job，包括 finally 和结构化子协程。不响应取消的任务未结束时，不得先释放其数据库/LLM/VFS。
- Runtime 顺序为封闭准入、收尾通知与 pipeline，再 `llm → vfs → executor`。Count 通知与扫描都可能访问 DB，必须全部结束后释放。封闭动作不能包含锁内挂起操作。
- create 零后台副作用；重复 start 只有一个消费者/时钟任务；启动与关闭竞争、部分初始化失败均由唯一所有者逆序清理。不能在未成功发布运行状态时执行业务 Runner。
- Runner/扫描回调内同步或挂起关闭自身会形成自等待，须在更改关闭状态前具名拒绝，复用既有生命周期错误口径；普通外部关闭不受影响。

## 4. D5：计数与执行一致性

批准 Int 饱和到 MAX_VALUE，不新增 Long 计数器。计数包含执行中的一次机会；每个 Session 至多一个排队项或一个执行位置。正常返回/普通异常消费一次，剩余次数排到队尾，保证全局单 Worker 且不饿死其他已排队 Session。

状态、计数、入队、执行完成及关闭封闭使用同一一致性边界。允许普通短锁替代示意 Mutex；锁内无 I/O、用户回调或挂起。`trySend` 失败不能留下无人消费的正计数；运行态异常发送失败应可观察，不能冒充接受成功。关闭后 request 返回 false/no-op，不打 WARN。

CancellationException 不当作普通失败消费并继续运行。正常关闭取消按 D4 收尾；若 Runner 在非关闭状态自行抛出取消导致唯一 Worker 结束，必须封闭准入并使终止可观察，不能留下 RUNNING 外观继续接收任务，也不自行重启/重放。用确定性测试覆盖该分支。

## 5. D6 / D7：Idle 与 Count

- Idle single-flight：重叠 tick 跳过、不排队。事件 `extraction.idle.skipped` 为 DEBUG，并有 internal 跳过计数；查询失败记录 `extraction.idle.failed` WARN，下次自然 tick 再查。CancellationException 原样传播，不记普通扫描失败。
- Worker 普通失败事件 `extraction.run.failed` WARN，隔离后继续下一 Session；非预期消费者终止使用 `extraction.worker.stopped` WARN。日志只含固定事件名、计数、耗时、受控分类，不输出 SessionKey/消息/SQL/原始异常文本，不把 Throwable 直接传 logger。
- Idle 保持已验收条件：至少一个 pending，且该 Session 所有状态最新 created_at <= now-idleTimeout。必须验证等号边界、较新的 complete/failed 阻止旧 pending 触发、timestamp 无关、多 Runtime/Agent/Session 隔离。复用现有查询，不新增索引或改 SQL。
- Count 每次 CREATED+COMMIT 后查 `countPending >= threshold`；不新增独立 Turn Counter。查询失败交既有通知 reporter，不发 Trigger、不改变已提交 Ingestion；取消沿既有链传播。DUPLICATE 不通知。允许订正 IngestionNotifier 历史 KDoc，不改签名。

## 6. D8：接缝与验收

允许 internal 只读计数/队列/执行状态、scanOnce、Clock/tick 与受控屏障。不要公开 ContextStatus 新字段或引入持久化诊断。测试应验证真实执行/退出，不只验证计数；溢出用受控测试接缝，不循环 MAX_VALUE 次；并发断言用屏障与有界等待，不以 sleep 猜测成功。

## 7. 分批授权与交付

Lead 先同步任务书、方案与本裁定的冲突、更新当前状态和验收映射，落首批委派单后，直接派发现有 Worker `10264b13-4fd6-4e6d-b699-b8095efd6ab6`。无需再等待 D1～D8 确认。

| 批次 | 范围 | 放行状态 |
| --- | --- | --- |
| B1 | Extraction 配置/Loader/统一校验；Core Runner Port；Runtime Scheduler 计数、串行、异常隔离与独立关闭协议及测试 | 已批准；不做 Cron/Count 查询与 Context 生命周期接线 |
| B2 | Count/Idle Trigger、Cron 计算与重叠/失败语义、真实 SQLite 验收 | B1 独立通过后放行 |
| B3 | Pipeline 组装、内部 Runner 注入、Runtime start/close 接线及竞争/释放顺序 | B2 独立通过后放行 |
| B4 | 全量验收、文档与回归、81 基线、源码/XML 计数 | B3 独立通过后放行 |

每批 Worker 自测 → Lead 独立初验（SHA、变更边界、实际命令、精确源码/XML 计数及反例）→ Architect 独立复核。B1 可定点新增 Loader 配置断言，既有断言不得弱化；Lead 在委派单锁定可改文件与基线。CT09 总体仍 IN_PROGRESS，CT10+ 不启动；无 git 写操作与发布。
