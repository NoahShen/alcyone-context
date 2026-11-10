# CT22 B1 · Lead 初验

## 本次修改（2026-11-09，B1 Lead 初验）

完成 CT22 B1（含 B1-R1）独立初验：核对 15 文件白名单、14/14 代码/测试 SHA、直接 DreamingConfig orphan timezone 校验，审查 Result/Exception、Config/Loader、固定 C0/U 范围、跨页 streaming 分批、单 Worker/owned close、预算与 processor seam、可选 Cron，并独立复跑 R1 定向、API/Runtime 全测、Spotless 与全量 `./scripts/check`。结论为 **B1 初验通过，提交 Architect 独立复核；CT22 未 DONE，CT23～CT26 LOCKED**。

**日期**：2026-11-09
**状态**：**B1 IN_REVIEW；提交 Architect 独立复核**
**Worker**：`059d6c59-ce9e-4e68-9055-b45b238506a1`（`codex-magpie/group/worker`、`auto-review`、high）
**依据**：[B1 委派单](B1_委派单.md)、[B1-R1 修复委派单](B1_R1修复委派单.md)、[B1 Worker 交付](B1_Worker交付.md)、[Architect 裁定](Architect_裁定.md)、[CT22 任务书](CT22_任务书.md)、[方案核对](方案核对.md)
**基线**：CM4 APPROVED / DONE；14 模块 **1885=1885**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；Git `f391192`（未执行 Git 写操作）

## 1. 初验结论

**CT22 B1 Lead 初验通过，提交 Architect 独立复核。**

- Worker 严格交付 B1 委派单 §3 的 15 个白名单文件：14 个代码/测试文件 + 1 份交付报告；白名单外 B1 变更 0。
- B1-R1 只修改授权的 3 个文件：`DreamingConfig.kt`、`DreamingConfigTest.kt`、`B1_Worker交付.md`。
- `DreamingConfig(cron = null, timezone = "Europe/London")` 现在于直接构造期抛出 `ContextConfigException(INVALID_CONFIG)`；无 cause、消息不回显原始 timezone。
- 新增 31 个测试，14 模块 fresh 源码 `@Test` 与 XML 均为 **1916=1916**，fail/error/skip = **0/0/0**。
- 固定范围、跨页分批、超长 Turn 失败保水位、单 Worker、Cron skip/owned close、partial failure 与 CE 传播均符合 D1～D18。
- 未修改 `AlcyoneContext`、`ContextExceptions.kt`、Factory/Impl、Schema/SQL、CT13/CT16/CT19/CT20 语义、VFS/common/依赖/构建。

## 2. 白名单与 SHA

### 2.1 文件集

- 委派单 §3 的 15 个文件全部存在；14 个代码/测试文件 SHA 与 [B1 Worker 交付](B1_Worker交付.md) §2 逐项一致。
- 交付报告为第 15 个白名单文件，不内嵌自引用 SHA；Lead 复算为 `e6f3c147b2f0422c2bf7ec4c1d856ab0dbfb935fe6c55af26874513ad1539ed5`。
- 实际组成为：API 3 + Runtime 生产/配置 6 + Runtime 测试/支持 5 + 交付报告 1。
- 计划文档 `docs/context/milestones/cm5/`、`docs/context/tasks/cm5-ct22/` 与 `docs/context/开发计划.md` 为 Lead 既有修改，不计入 B1 白名单外变更。

### 2.2 R1 相关 SHA

| 文件 | Lead 复算 SHA-256 |
| --- | --- |
| `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingConfig.kt` | `c05aca1bcffe367a7e17abb2aaf5c3f3bf569ed57c4b42d6cc6ffc83c915ebaa` |
| `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/dreaming/DreamingConfigTest.kt` | `6a40e20dcdde09355a0a03b8eb7c9282a1e6796a772e652f07cfd9e3fcd5a1fe` |
| `docs/context/tasks/cm5-ct22/B1_Worker交付.md` | `e6f3c147b2f0422c2bf7ec4c1d856ab0dbfb935fe6c55af26874513ad1539ed5` |

其余 B1 白名单代码/测试文件 SHA 与 Worker 报告一致；R1 未修改 Loader、Coordinator、Cron、API 或其他测试。

## 3. 独立实跑证据

| 命令 | exit | 耗时/任务 | 关键结果 |
| --- | ---: | --- | --- |
| `./scripts/dev gradle :context:runtime:test --tests '*DreamingConfigTest' --tests '*ContextConfigLoaderTest' --rerun-tasks --console=plain` | 0 | 11s / 25 tasks | DreamingConfig 4/4、ContextConfigLoader 30/30 |
| `./scripts/dev gradle :context:api:test :context:runtime:test --rerun-tasks --console=plain` | 0 | 46s / 27 tasks | API 41/41、Runtime 651/651 |
| `./scripts/dev gradle :context:api:spotlessCheck :context:runtime:spotlessCheck --rerun-tasks --console=plain` | 0 | 6s / 9 tasks | API/Runtime 格式通过 |
| `./scripts/check` | 0 | 2m17s / 131 tasks | clean build、Spotless 与 14 模块测试全绿 |
| `git diff --check` | 0 | - | 空输出 |

### 3.1 14 模块 source/XML

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 41 | 41 | 0 | 0 | 0 |
| context-core | 119 | 119 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 651 | 651 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 27 | 27 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **1916** | **1916** | **0** | **0** | **0** |

B1 前 **1885**，新增 **31**（API +3、Runtime +28），最终 **1916=1916**，差额 0。

## 4. 行为核对

### 4.1 Result / Exception / Config

- `DreamingResult` 六字段公共模型校验 checkpoint/计数非负与 `startCheckpoint <= endCheckpoint`，不暴露 Turn/SQLite 类型。
- 正常/无工作 run 返回 `completed=true`；partial failure 只通过 `DreamingRunException.partialResult` 表达，不返回 `completed=false` 的伪成功结果。
- `ContextDreamingException(ALREADY_RUNNING)` 无 cause；`DreamingRunException` 保留 stage、partial result 与底层 cause；CE 不包装。
- `DreamingConfig()` 保持 manual-only + `Asia/Shanghai`；cron 非空时校验 UNIX 五字段与 IANA timezone。
- D8 的直接构造与 Loader 两条入口均拒绝无 cron 的显式 timezone；R1 错误消息固定、无 cause、无原始值回显。
- `ContextConfig.dreaming` 默认字段保持旧 Kotlin 构造兼容；Loader 严格注册 `context.dreaming.cron/timezone`，拒绝 unknown/type/非法值。

### 4.2 Fixed Range / Budget / Processor

- 每轮同一 `database.read` 读取 checkpoint C0 与 `maxTurnId()` U；校验 `0 <= C0 <= U`，之后固定 U，不回读 MAX。
- `(C0,U]` 按 id ASC 分页，ID 空洞合法；pending/complete/failed 均参与，不按 timestamp/status 过滤。
- page size 1/2/3 的跨页测试均穷尽全部 Turn；页间以末 ID 衔接，不依赖“满页”假设。
- 直接复用 `TokenBatchPlanner`；固定开销、单 Turn 超预算具名失败，不截断/跳过/改 Extraction 状态；预算等号通过。
- processor 串行，outcome `lastTurnId` 必须等于批尾且严格前进；任一批失败停止后续批次，partial endCheckpoint 保持最后成功批水位。
- DB/processor 普通异常保留 cause；caller/processor/budget 路径的 CE 不包装。

### 4.3 Single Worker / Cron / Lifecycle

- manual overlap 立即 `ALREADY_RUNNING`，不等待/排队；background overlap 返回 false、只记录脱敏 WARN，不增加 owned run/round。
- `beginClosing` 在状态锁内同步封准入；close 只取消并 join owned Job，外部 manual run 不被强杀，caller CE 原样传播。
- 并发 close 共享 completion/失败实例；关闭后 background 不再接受，状态进入 CLOSED。
- Cron start 不立即 tick；严格未来、不补跑，tick 只请求 background；running skip 不回压 loop。
- tick/调度失败使用固定 1000ms 可取消退避和脱敏 WARN；close 立即取消并真实 join，并发 close 共享失败且 cleanup 仅一次。

## 5. 边界核对

### 5.1 白名单 / 生产边界

- B1 白名单外代码/测试/生产/构建变更：**0**；R1 白名单外变更：**0**。
- 未修改 `AlcyoneContext`、`ContextExceptions.kt`、Factory/Impl、ContextConfig/Loader 之外的生产接线。
- 未实现 Topic/Evidence/Reflection/Commit、真实 Prompt/LLM、Checkpoint/Raw/Pending 写入或 Factory/Impl 接线。
- `context.dreaming` 缺省不构造 Cron；B1 未运行真实模型，Fake processor/受控 estimator 仅证明协议与边界语义。

### 5.2 历史基线与 VFS/Schema

- CT18 B1 Worker 报告中的 17 个代码/测试 SHA：**17/17 一致**。
- CT20 B1 Worker 报告的 19 个代码/测试 SHA：**19/19 一致**；CT20 B2 的 Config/Loader/LoaderTest 仅因 CT22 白名单要求出现有意增量。
- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 81 项 VFS 基线：**75 一致 + 6 个 CT17 已批准差异**，缺失 0，未批准差异 0。
- common、VFS、Schema/SQL、Gradle 构建/依赖版本目录无 B1 变更；Core 主源码无 Ktor/serialization。

## 6. 观察项与残余风险

- 既有 `ExtractionPipelineLifecycleTest` 的 10s 时序超时在 Worker 初始阶段曾出现，R1 最终全量与 Lead 独立全量均未复现；该文件不在白名单且未被本轮修改，继续作为既有稳定性观察，不阻断 B1。
- 真实模型：**not-run**；当前结果不证明 Topic Discovery/Reflection 的语义质量。
- 负 C0 用测试 wrapper 覆盖（正式 Schema CHECK 禁止负 checkpoint）；ID 空洞用真实删除中间主键覆盖。
- 跨页 stream assembler 已验证无漏行；当前不声明吞吐性能上限。

## 7. 当前状态

- **CT22 B1：IN_REVIEW，Lead 初验通过，提交 Architect 独立复核。**
- **CT22：IN_PROGRESS；CT23～CT26 LOCKED。**
- CM5：IN_PROGRESS；真实模型 not-run；无 Git add/commit/push/stash、分支、回滚或发布操作。
