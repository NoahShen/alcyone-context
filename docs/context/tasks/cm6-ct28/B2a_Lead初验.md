# CT28 B2a Lead 初验

## 本次修改（2026-11-11，R2 关闭，提交 Architect）

- R2 三项关闭：STARTING/STOPPING Ready=false 且无 DB read 准入；真实 closed executor 时 Ready=false/Admin 保留 CLOSED 失败；真实 Extraction/Organization worker 与关闭 STOPPED、MemorySearch-only 无 Dreaming；Counting Vfs+pending1+Map STALE+LLM 历史下 Ready 两次且 VFS/LLM 零新增；发布成功后 openStream 失败精确 POST_CHECK/UNAVAILABLE、对象保留/temp 清空，CE 传播。
- Factory 公共签名不变，internal vfsFactory 默认 `AlcyoneVfs::create`；业务 vfs/Backup 保持真实 AlcyoneVfs，managedVfs 负责 custom AutoCloseable/真实 VFS 的确定性关闭，无反射生产接缝。旧 Extraction 测试仅按裁定改为 drain+queue0+RUNNING、1..5 同 Session、tick 无副作用；旧/新 SHA `4debb92d…ebbff`/`290b4866…ad578f`。
- Lead 指定四模块 fresh rerun：`/tmp/ct28-b2a-r2/lead-four-required.log`，exit0、32/32、55s；XML **api45/core180/persistence186/runtime823=1234**，failure/error/skipped 全0；四模块 Spotless exit0。首次 api/core/runtime/server 仅为附加证据，未替代 Persistence。
- 全仓 `./scripts/check` `/tmp/ct28-b2a-r2/lead-full-check.log` exit0、132/132、2m27s；14模块 **2209=2209**，明细 `/tmp/ct28-b2a-r2/lead-full-xml.txt`。Schema `ec727a53…aadd8ea3`、BackupRuntime `25cfd1d0…bb626f` 未变，无 Ct28LeadDiagnostic/Ct28InheritedDiagnostic 或 DIAG/ORIG/EXACT 残留。
- 生产/测试 SHA：AlcyoneContext API `583d1ee1…b7754`、ContextStatus `078d4172…d7`、Factory `5396e96e…8271`、Impl `b75ea0a0…2607`、ReadyTest `033d9d45…7f84`、AdminTest `09e66e56…5cd`、BackupTest `2ef6fa37…ba6`。提交 Architect 独立复核，B2a 未自标 DONE；B2b/c 未启动，无 Git 写操作。

## 历史修改（2026-11-11，R1 关闭，提交 Architect）

- R1 已关闭：Dreaming checkpoint 仅由 `snapshot.dreaming != null` 决定，旧 helper 无引用；`AlcyoneContextImpl` 终态 SHA 为 `908ec89826cd905d1712cd226a5a09af8d38ab7ea509e895decb309caa9e99f4`，其余交付文件 SHA 保持。
- Lead 独立复跑四模块 `test --no-build-cache --rerun-tasks`，`/tmp/ct28-b2a-lead/final-four.log`、exit 0、52s、32/32；修正汇总脚本后 **api 45/45、core 180/180、persistence 186/186、runtime 817/817，四模块差额 0，失败/错误/跳过均 0**。
- Admin Runtime XML 明确 tests=4/failures=0，Dreaming Runtime XML tests=4/failures=0；Schema SHA 复核未变。26 文件终态清单见 `/tmp/ct28-b2a-lead/final-26.sha256`。
- B2a 提交 Architect 独立复核，未自标 DONE；B2b/c 未启动，无 Git 写操作。

## 历史修改（2026-11-11，IN_REVIEW / R1）

- Worker 交付 26 个文件的终态 SHA 全部与 Lead 实算一致；Schema SHA 保持 `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，B2a 未改 Server/构建/VFS。
- 初步确认 API 默认、真实 COUNT、Ready 重查、四类 worker、LLM 不可变统计、Backup 阶段、真实 SQLite/Map/Backup 组合及 `@Test`/XML 对账证据。
- R1：`getAdminStatus()` 的 Dreaming checkpoint 存在判断必须直接使用同一锁内快照 `snapshot.dreaming != null`，不得在锁外另读 `dreamingConfigured()`；与 D5 的 Runtime 唯一来源对齐。
- Lead 曾误报 `profile` 必须删除，回读任务书与 Architect 裁定后已撤回：`task/profile` 是批准维度，DTO 不含 model/baseUrl/Key，非缺陷。
- 保持 B2a IN_REVIEW，B2b/c 未启动；无 Git 写操作。

## 1. 交付与边界

最终 Worker 命令为四模块 `test --no-build-cache --rerun-tasks`，日志 `/tmp/ct28-b2a/delivery-evidence.log`：53s、32/32、45/180/186/817、四模块差额 0。Admin 非 Unit 测试发现缺口已修正，Admin XML 4/0；Dreaming Runtime XML 4/0。

26 文件终态 SHA 与 Worker 清单逐项一致。历史时序事实保留：Worker 两次完整 Runtime 出现既有 `ExtractionPipelineLifecycleTest > disabling idle...` 10s 超时；Worker 单类 3 次通过；Lead 独立单类 15/15 与 Runtime 815/815 通过；最终 Worker Runtime 817/817 通过。日志分别为 `tests.log`、`tests-final.log`、`/tmp/ct28-b2a-lead/lifecycle-1.log`、`/tmp/ct28-b2a-lead/runtime-1.log`、`delivery-evidence.log`。

## 2. 静态初验

- `checkReady()` 通过当前受管 executor 的 checkpoint read，返回后重查 RUNNING；外部 CE 传播，其他读失败 false。无文件存在、SELECT 1、新 JDBC、LLM/VFS/Map 调用。
- `getAdminStatus()` 锁内复制稳定引用，锁外同一 `database.read` 取 Pending/checkpoint；Pending/count 失败整体抛出，不伪零。`getStatus()` 原实现未改。
- COUNT 恰好一行、非空、非负、Int 上限；Read/Tx 覆写共享 Reader，第三方默认保留 list 兼容。
- Extraction 非关闭 CE 设置真实 FAILED，普通 Runner 失败隔离，close STOPPED；Organization 使用原状态算法；Dreaming activeRun/Runtime state，无历史失败事实为 null；Map/Backup 使用现有 reader。
- LLM 快照在各 Counters 锁内复制，未配置 null、零记录 empty list、usage 缺失 token null；不暴露 model/baseUrl/Key。
- API DTO 无路径、Throwable、自由异常文本或序列化依赖；公开 list 在构造边界复制。

## 3. R1 定点修正

`AlcyoneContextImpl.getAdminStatus()` 当前在锁外使用 `dreamingConfigured()` 推断 checkpoint 是否存在。即使它与 Factory 创建条件当前一致，D5 明确要求由同一 Runtime 快照决定，故只允许把该条件改为 `snapshot.dreaming != null` 并删除不再使用的 private helper；不改配置矩阵、Dreaming 状态机或 DB 读取。

修正后 Worker 运行 Spotless 与四模块测试，Lead 再独立复跑四模块及 XML 对账。未验证项仍为全仓 `./scripts/check`、真实模型、WebDAV 原子发布、长期跨进程及生产部署。
