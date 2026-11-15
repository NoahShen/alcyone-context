# CT28 B2a Architect 独立复核

## 本次修改（2026-11-11，R2 全部关闭，B2a APPROVED / DONE，放行 B2b）

- 独立指定四模块 fresh rerun exit 0、32/32、1m9s，XML **api45/core180/persistence186/runtime823=1234**，失败/错误/跳过全 0；日志 `/tmp/ct28-b2a-architect-four.log`。
- 独立全仓 `./scripts/check` exit 0、132/132、2m25s，正确 14 模块（含 `vfs/git-backup`）**2209=2209**、零失败/跳过；日志 `/tmp/ct28-b2a-architect-check.log`。四模块 Spotless、旧 Extraction 单测额外连续 3 次通过。
- R2 三项均具正式断言；关闭后 Worker capability、VFS custom AutoCloseable 清理及旧 Count 测试稳定性边界通过。Schema `ec727a53…aadd8ea3`、BackupRuntime SHA 不变，无临时诊断残留。B2a DONE，B2b 放行；B2c/CT29 不放行，无 Git 写操作。

## 历史修改（2026-11-11，B2a-R2 验收补测）

- 独立重算 R1 终态 **26 文件 SHA** 全部一致；生产十五文件范围、Schema、B1 Server 五文件及 Core/API 依赖边界通过，R1 Dreaming checkpoint 来源修复有效。
- 独立复跑四模块 `test --no-build-cache --rerun-tasks`：**32/32，BUILD SUCCESSFUL**；source/XML 为 API **45=45**、Core **180=180**、Persistence **186=186**、Runtime **817=817**，failure/error/skipped 均为 0；四模块 Spotless 通过。
- 静态复核未发现需要重写生产实现的新确定性错误；但委派单 §3.3 的生命周期、真实 DB 故障、Runtime 聚合/依赖隔离及真实 Post-publish 失败缺少正式断言。裁定 **B2a IN_REVIEW / B2a-R2**，只补测试/证据，**不放行 B2b/c**，CT28 未 DONE。无生产/测试修改，无 Git 写操作。

## 1. 独立证据

### 1.1 执行结果

Architect 命令：

```bash
./scripts/dev gradle \
  :context:api:test :context:core:test \
  :context:persistence-sqldelight:test :context:runtime:test \
  --no-build-cache --rerun-tasks --console=plain
```

结果：`/tmp/ct28-b2a-architect-four.log`，exit `0` 保存于 `/tmp/ct28-b2a-architect-four.exit`；**32 actionable tasks: 32 executed**，耗时 54s，无 UP-TO-DATE 充当测试通过。逐模块 XML 与源码核对：

| 模块 | source @Test | XML tests | failures / errors / skipped |
| --- | ---: | ---: | ---: |
| context:api | 45 | 45 | 0 / 0 / 0 |
| context:core | 180 | 180 | 0 / 0 / 0 |
| context:persistence-sqldelight | 186 | 186 | 0 / 0 / 0 |
| context:runtime | 817 | 817 | 0 / 0 / 0 |

另跑四模块 `spotlessCheck --no-build-cache`，BUILD SUCCESSFUL；日志 `/tmp/ct28-b2a-architect-spotless.log`。本批未运行全仓 `./scripts/check`、真实收费模型、WebDAV 或生产部署。

### 1.2 文件、边界与 R1

- `sha256sum -c /tmp/ct28-b2a-lead/final-26.sha256`：26/26 OK；清单恰为 26 文件，测试后再次核对无漂移。
- `AlcyoneContextImpl.kt` 保持 R1 终态 `908ec898…caa9e99f4`；Admin 的 Dreaming DTO 以锁内 `snapshot.dreaming != null` 决定是否读取 checkpoint，不再以 DB 是否有历史配置推断能力。R1 正式 4/4 通过。
- Schema 保持 `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；新增 SQL 只读 `SELECT COUNT(*) FROM organization_pending`，无 DML/迁移。
- B1 五个 Server 保护文件 SHA 与 Architect 基线一致；`context/server/**` 零改动，Admin/Health/managed HTTP 未实施。
- Core/API 生产源码无 Ktor/serialization import；无构建、VFS/common、Prompt 或生产依赖扩展。

## 2. 已通过实现项

- **D2**：第三方默认 Ready=false、Admin 仅已知 lifecycle；DTO 区分未配置/零记录，LLM List 构造边界复制，未引入 path/Throwable/秘密字段。
- **D3**：Core 默认 count 保留源码兼容；Read/Tx 都显式走共享 Reader 的真实 COUNT；恰好一行、非 null、非负及 Int 溢出受控失败有真实 SQLite + controlled query 证据。
- **D4**：代码按 RUNNING 预检→锁内受管 DB 快照→锁外 checkpoint 读→RUNNING 复检；不使用 `databaseVerified`、文件存在、SELECT 1 或新 JDBC；CE 原样传播。查询后关闭竞争测试有效。
- **D5/D6**：Pending 与 Dreaming checkpoint 在同一 `database.read`；worker/LLM/Map/Backup 使用稳定快照，锁外 DB；普通 Extraction Runner 失败与 worker 自行 CE 终止区分，Dreaming active run 与 LLM 分组/usage null 语义有测试。
- **D7**：Snapshot、Pre-check、Publish 的错误阶段和取消传播与原流程一致；`BackupStageFailure` 不新增公开自由文本到 DTO，发布/quick_check/清理算法未改。

以上仅说明实现静态路径及已有测试通过，不抵消下列正式验收缺口。

## 3. B2a-R2：必须补齐的验收项

### R2-1：STARTING / STOPPING 与真实 SQLite 故障没有按要求验证

**事实**：`AlcyoneContextAdminStatusTest.kt:24-70` 只覆盖 CREATED、RUNNING、CLOSED；`AlcyoneContextReadyTest.kt:23-41` 是已准入 RUNNING 查询与 close 的交错，不等于 STOPPING 入口预检。委派单 §3.3(3) 明确要求五种生命周期。

`AlcyoneContextAdminStatusTest.kt:73-85` 名为 `missing SQLite`，实际由 `adminReadHookForTesting` 在 `database.read` **之前**抛出合成的 `DATABASE_NOT_INITIALIZED`，因此没有证明真实受管 SQLite/executor 读失败会返回 false；Admin 也只断言“存在某个异常”，未断言原受控异常类型/代码。

**关闭要求**：仅在原允许的测试文件中使用现有 StartProbe/生命周期闸门和真实 Runtime/受管 executor，补 STARTING、STOPPING 的 `checkReady=false`，并证明未准入查询；制造真实 DB 读失败（不是在 DB 调用前抛 marker），Ready=false，Admin 精确抛出对应持久化异常而非伪 0。保留查询后关闭 false、CE 原样传播。不得为测试新增生产分支或改 Factory。

### R2-2：Runtime Worker 聚合及 Ready 的零 LLM/VFS 调用证据不完整

**事实**：`AlcyoneContextAdminStatusTest` 对 Extraction/Organization 只断言未配置为 null；非空 worker 仅覆盖 Dreaming IDLE 与 Map IDLE。Scheduler 的独立单测不能替代 `AlcyoneContextImpl` 对真实已配置 Extraction/Organization pipeline 的聚合验证，也未验证已配置组件在 Context CLOSED 时输出 STOPPED。

Ready 的 Pending 非空及 Map STALE 已有断言，但测试没有用调用计数证明探针没有调用 LLM/VFS；`memoryMap` 用例的 HTTP stub 虽存在，却未断言请求数，VFS 也没有零调用证据。委派单 §3.3(3)/(4) 的隔离要求因此未闭环。

**关闭要求**：在原 `AlcyoneContextAdminStatusTest.kt` / `AlcyoneContextReadyTest.kt` 内通过既有 Factory/真实 Runtime 接缝配置 Extraction、Organization，验证其非空 worker 来自实际 scheduler，并验证关闭后已配置槽位的 STOPPED 聚合；Dreaming/Map 继续沿用现有断言。Ready 在 Pending 非空、Map 非 CURRENT、LLM 历史失败或不可用配置下仍为 true，同时用受控计数证明 LLM 请求与 VFS 业务/网络调用均为 0。不得用 sleep 或“没有断言的 stub”充当证据，不要求真实收费模型。

### R2-3：Post-publish 只测了手工构造异常，没有执行真实失败路径

**事实**：`BackupRuntimeTest.kt:17-33` 直接构造 `BackupStageFailure(POST_CHECK, …)`，只能证明枚举透传；实际 `runNow()` 测试只覆盖 Snapshot（161-175）、Pre-check（177-193）和 Publish（73-98）。没有让已发布对象在 `openStream`/读回/quick_check/指纹比较中失败，因此未证明生产 catch 会映射 POST_CHECK，也未证明失败后已发布对象不回滚、workspace 仍清理。

**关闭要求**：只扩充原 `BackupRuntimeTest.kt` 的 controlled VFS/snapshotter，使 `publishNew` 成功后读回阶段确定性失败；断言对外错误码/受控异常、`runtime.status().lastFailureStage == POST_CHECK`、无取消吞没、临时 workspace 清理及已发布 URI 保留。保留既有 quick_check、publish 与成功测试全绿；不得改发布算法或为通过测试放宽错误码。

## 4. 修复边界与复验

- 本 R2 是**验收覆盖阻断**，不是要求重写已通过的 B2a 生产实现。默认只允许修改 §3 指名的现有测试文件：`AlcyoneContextReadyTest.kt`、`AlcyoneContextAdminStatusTest.kt`、`BackupRuntimeTest.kt`；测试 helper 若确需调整须先由 Lead 报告并保持原委派写集合。
- 26 个 B2a 生产/测试终态中，上述三个测试文件允许变化；其余生产文件、Schema、B1 Server、构建与 CT27 文件必须保持 SHA。若补测揭示真实生产缺陷，立即停止，由 Lead 提交精确生产差异与原因，Architect 另行裁定，不得借补测静默扩大范围。
- Worker 自测后 Lead 独立初验，再交 Architect；至少重跑四模块 32 tasks、四模块 Spotless，给 source/XML 新 totals 与三个 R 项的测试方法/行号。R2 全关闭前 B2a 不 DONE，B2b/c 不启动。
- 最终全仓 `./scripts/check`、14 模块 totals、Server/HTTP/managed 验证仍归 B2b/c，不以本批四模块全绿宣称 CT28 完成。

**裁定：B2a IN_REVIEW / R2；生产实现暂保留，补正式验收后再裁定。**
