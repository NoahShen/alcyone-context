# CT16 B1 · Lead 独立初验（Organization Config/Scheduler/Pipeline）

**日期**：2026-11-08
**初验人**：CM3 Lead（独立复核）
**结论**：**B1 LEAD 初验通过，无 R 项；Architect 复核 APPROVED；CT16 B1 DONE（2026-11-08）。**
**依据**：[B1 委派单](B1_委派单.md)、[CT16 任务书](../../milestones/cm3/CT16_任务书.md)、[方案核对](方案核对.md)、Architect D1～D14 终稿

## 0. 本次修改（2026-11-08，B1 Lead 独立初验）

Worker 严格修改/新增 21 个白名单文件。Lead 独立复算全部 SHA、审查 Config/绑定门、固定 Pending 列表、SourceGuard、Scheduler/Counter、PeriodicCron、Worker 与 Pipeline；自行重跑 Core/Persistence/Runtime 定向、Organization 组件 3 次和完整 `./scripts/check`，并核对 14 模块、81 基线、Core/Schema/CT13-15/生产零接线。未发现阻断缺陷，未修改生产/测试代码，无 git 操作。

## 1. 交付面与 SHA

| 文件 | SHA-256 |
| --- | --- |
| Core `OrganizationPendingReadOps.kt` | `0c76c889e6a10eef3e7180ec41b1381ec4bbd17fa91e34c6dab698ebbb1e0e6c` |
| Core `OrganizationPendingRepository.kt` | `1de0f260b191f5140c574db56bffaf4158025d49a64f3609f988a21e040d7509` |
| Persistence `ContextSqlQueries.kt` | `2b20820bb4d95ffceccbbff92f7b7393f763589d62382308ed9b1166d651ba2a` |
| Persistence `ContextOps.kt` | `4618f370aef2031882ced45106c5a225c52e9d928807eef6fea50df7a6007cee` |
| Persistence `ContextRepositories.kt` | `167d8a80b14572788c83f6112d7f9fbe9ccd099e34d47676eb421d203dfddddf` |
| Persistence test `ContextOrganizationPendingRealDbTest.kt` | `ecab70244dbf7f92696258baa76410c0bc0dd07a715b777bef023ffa38c9086e` |
| Runtime `ContextConfig.kt` | `bcd8fe817cc3d170d5ac1b663b473af6afd1f2b50a6884779534bc7b33757d74` |
| Runtime `ContextConfigLoader.kt` | `34c4a70807a1092315f24dd71f0c476a6fb2c439d1229b76378f56adabab01c5` |
| Runtime `OrganizationConfig.kt` | `5d1881ed84a162ed6df62609025b639a598b457b81f2e690c34614812869f72e` |
| Runtime `OrganizationSourceGuard.kt` | `23ad00a07fe7a00cf7f7e0bf2c1f0ceca6faa1f80abb5af46c4e6b3033b89346` |
| Runtime `OrganizationScheduler.kt` | `5c9379af36a853278b1e374b1f3279c4cd9b54a39e93592dc4d45af2b6aba1e6` |
| Runtime `OrganizationPeriodicCron.kt` | `82bccc868202d6ca5a3510960cee25aec88c625087e676c01a4edfa366122a7a` |
| Runtime `DefaultOrganizationWorker.kt` | `592b61f673172e9d095a0df9a3f64289920aef6803b2ccc309fba724b77af6b5` |
| Runtime `OrganizationPipeline.kt` | `48350dbf97b74d6dfb37de51daeacd2abb46244d2e921cedbc1e163626592031` |
| Test `ContextConfigLoaderTest.kt` | `0139cdb1f2dea26cf5f69eb676e8bdd1688524a7af304411d09ce4438cb2c19c` |
| Test `OrganizationConfigTest.kt` | `33d47745a4e1a7763946be7f545732eec5f9bff466f2940decbaa79e0f9681ba` |
| Test `OrganizationSchedulerTest.kt` | `e55f23761d4329017d95b9d25f058384adf2e2a7ef561194d80061702b1dd976` |
| Test `OrganizationPeriodicCronTest.kt` | `bd7cc007fcbb32a989960e8f1151b5f2cfb285100052cb5be8f825854cafd45e` |
| Test `OrganizationWorkerTest.kt` | `e4922eddbf4f30be427bf53cc83bf308ed5b8345a9520c25860e06b6cf0f7ff9` |
| Test `OrganizationPipelineTest.kt` | `09c7c1116254236364ef697fdc2449c10305a2c27c45b9d35cef525dde84257e` |
| Test support `OrganizationTestSupport.kt` | `0e09b7936d7c8d890f42dee52fe33d341a98a3adcd7a2d360490a3de792061eb` |

- 21/21 SHA 与 Worker 回执一致；无第 22 个 CT16 源码/测试文件。
- 9 个 tracked 修改与 12 个新增路径均在白名单；白名单外 tracked/untracked CT16 变更 0。

## 2. 静态审查

| 契约 | 结果 |
| --- | --- |
| D2/D4 配置 | `organization=null` 禁用；空映射默认双 Trigger；两个 disabled、非法 cron、unknown/timezone 均拒绝；固定上海 |
| D3 binding | 纯函数要求 CANDIDATE_SCANNER/GOVERNANCE Task/Profile 与 JSON 模式；不触 DB/LLM，消息不含 Key/URL |
| D5 Pending | `listAllByRawCreatedAt()` JOIN Raw，按 `created_at,id ASC`，无 limit；单读快照 |
| D6 Scheduler | Int 饱和、单 Worker、轮末消费、锁内退出/唤醒；一次 Trigger 一整轮 |
| D7 SourceGuard | 短读 + 短写二次核验；已关联清 Pending，状态反转不误删；不改 CT15 API |
| D8 Worker | 固定列表；SourceGuard→Raw→Scanner→Governance→Commit；单条普通失败继续，CE 传播 |
| D9/D11 失败退避 | 轮级 Persistence failure 与 Periodic 普通失败均固定 1s 可取消退避；无单条无限重试 |
| D10 Pipeline | activation gate；beginClosing 同步封准入；Cron→Scheduler 关闭；真实 join、共享失败 |
| D12 Map/状态 | Committed 后 Map；AlreadyAssociated 不调用；Map 普通失败吞掉、CE 传播；单条失败不 FAILED |
| D13 生产边界 | Factory/Impl/ContextStatus/ExtractionScheduler/构建依赖均无 B1 接线 |

## 3. Lead 独立实跑

| 命令 | 结果 |
| --- | --- |
| `:context:core:test :context:persistence-sqldelight:test :context:runtime:test --rerun-tasks` | **exit 0**，BUILD SUCCESSFUL in 50s |
| `:context:runtime:test --tests '*Organization*Test' --rerun-tasks` × 3 | **3/3 exit 0**，各 BUILD SUCCESSFUL in 10s |
| `./scripts/check` | **exit 0**，BUILD SUCCESSFUL in 2m20s，131 tasks |

- CT16 新增：**50 例**；1587 → **1637**。
- 14 模块源码行首 `@Test` 与 XML：**1637 = 1637**，fail/error/skip 全 0。
- Organization 组件 XML：Config 9、Scheduler 9、PeriodicCron 7、Worker 11、Pipeline 9，共 45；另有 ConfigLoader 3、Pending SQLite 2，共 50。

| 模块 | 源码 | XML | 模块 | 源码 | XML |
| --- | ---: | ---: | --- | ---: | ---: |
| common | 23 | 23 | integration-tests/vfs | 107 | 107 |
| context/api | 26 | 26 | vfs/api | 66 | 66 |
| context/core | 93 | 93 | vfs/core | 305 | 305 |
| persistence-sqldelight | 178 | 178 | vfs/git-backup | 73 | 73 |
| **context/runtime** | **451** | **451** | vfs/persistence | 52 | 52 |
| context/server | 30 | 30 | vfs/runtime | 61 | 61 |
| integration-context | 18 | 18 | vfs/storage | 154 | 154 |
| **合计** | **1637** | **1637** | | | |

## 4. A01～A08 映射

| # | Lead 证据 | 结果 |
| --- | --- | --- |
| A01 | ConfigLoader：缺省/默认/覆盖/ENV/unknown/timezone/类型/双 disabled/非法 cron；ConfigTest：双 Task/Profile/JSON 模式纯 binding | 通过 |
| A02 | Pending SQLite：空集合、34 条稳定顺序/无截断；Worker：轮中新增仅下一轮可见 | 通过 |
| A03 | Scheduler：单/多 Trigger、轮末消费、Int 饱和、单 Worker、退出竞态、轮级退避、关闭 join、CE FAILED | 通过 |
| A04 | Worker：Source+Pending 清理、Source-only no-op、读后反转、完整 Scanner→Governance→Commit、单条失败继续、CE、Map 接缝 | 通过 |
| A05 | Periodic：严格未来上海、不补跑、启动不扫描、hasAnyPending 门、失败退避、关闭 join | 通过 |
| A06 | Pipeline：activation gate、beginClosing、Cron→Scheduler、在途取消/join、关闭前置/重复、共享失败 | 通过 |
| A07 | IDLE/RUNNING/STOPPED；单条/普通轮失败不 FAILED；不可恢复 CE 为 FAILED；public status 映射留 B2 | 通过 |
| A08 | 定向、3 次组件重复、全量、14 模块、81/Core/Schema/CT13-15/生产边界 | 通过 |

## 5. 边界独立核对

| 项 | 结果 |
| --- | --- |
| 81 VFS 基线 | 按 manifest 独立复算：**Checked 81 files: all OK** |
| `vfs` / `integration-tests/vfs` | status 与 tracked diff 均为空 |
| Core 依赖 | main 无 Ktor/serialization import；compileClasspath 仅 context/api、common、coroutines/stdlib |
| Schema | `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3` 未变 |
| CT13/CT14/CT15 | 25/25 SHA 未变，缺失/歧义/不一致 0 |
| 生产接线 | Factory/Impl/ContextStatus/ExtractionScheduler 无 B1 类型引用；Factory/Impl diff 0 |
| 真实模型 | **not-run**；Fake/manual/injected seam 不宣称语义质量 |

## 6. 非阻断观察

| # | 项 | 说明 |
| --- | --- | --- |
| L1 | 既有 CT09 `ExtractionPipelineLifecycleTest` Worker 首轮一次 10s timeout | 白名单外既有测试，未修改；Lead 定向、3 次组件重复与全量均通过；保留时序敏感性观察 |
| L2 | B1 的 Worker 链路通过 seam 验证调用/失败边界 | CT15 实际 Committer 已独立验收，B2 将用真实 Factory/LLM HTTP 完成纵向集成 |
| L3 | public `workers["organization"]` 尚未接入 | D12 明确归 B2；B1 只提供内部状态，不修改 public ContextStatus |

## 7. 结论

- 21 文件白名单、D1～D14 B1 范围、A01～A08、定向/重复/全量与边界证据均通过 Lead 独立核对。
- **B1 LEAD 初验通过，无 R 项**；提交 Architect 复核并裁定 B1 是否 DONE。
- B2/CT17/CM4 未启动；未接生产；无 git 写操作/推送。

## 8. 证据

- Worker：`.local/validation/cm3/worker/ct16-b1/`
- Lead：`.local/validation/cm3/lead/ct16-b1/`

## 9. Architect 收口（2026-11-08）

- Architect 独立复核 21/21 SHA、D1～D14 B1 语义、50 例与全量边界，结论 **CT16 B1 APPROVED / DONE**。
- Architect Gradle 沙箱 FileLock 限制由 Worker/Lead 双重实跑、XML、静态审查与 SHA 补足，不构成缺陷；既有 CT09 一次 timeout 维持观察。
- B2 已放行，详见 [B1 Architect 复核](B1_Architect复核.md)。
