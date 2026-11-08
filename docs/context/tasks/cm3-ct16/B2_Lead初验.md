# CT16 B2 · Lead 独立初验（Organization 生产接线与集成）

**日期**：2026-11-08
**初验人**：CM3 Lead（独立复核）
**结论**：**B2 LEAD 初验通过，无 R 项；已由 Architect 复核通过。**
**依据**：[B2 委派单](B2_委派单.md)、[CT16 任务书](../../milestones/cm3/CT16_任务书.md)、[方案核对](方案核对.md)、[B1 Architect 复核](B1_Architect复核.md)

## 0. 本次修改（2026-11-08，B2 Lead 独立初验）

Worker 严格修改/新增 6 个白名单文件。Lead 独立复算 SHA、审查 Factory 配置门、Impl 双 pipeline 接线、workers 映射、启动失败/关闭顺序和公共 E2E；自行重跑 Runtime+Integration 定向、wiring 3 次、E2E 2 次和完整 `./scripts/check`。未发现阻断缺陷，未修改生产/测试代码，无 git 操作。

## 1. 交付面与 SHA

| 文件 | SHA-256 |
| --- | --- |
| Runtime `AlcyoneContextFactory.kt` | `d453e3e8334c9faad2e275ae0e6203bcf596d8920da3609cdc771ae7de0dbdb5` |
| Runtime `AlcyoneContextImpl.kt` | `e5680698871756daded025fa874cf6e0144b92664ec4ec819a51af1833ac416c` |
| Test `AlcyoneContextFactoryTest.kt` | `0a11468acf08bd008424c58ac984a62c86c7582111ab268e158bc0fbefbc60ef` |
| Test `OrganizationRuntimeWiringTest.kt` | `6ff1d3dc806d37e0f6017a8969e7a70e7f481d634c4c2799fa9c0d5a164ca5af` |
| Test `OrganizationLifecycleWiringTest.kt` | `7766c93ede9d6ad5c84d3753b8598377aca1c5e57a93256d5f7ee6bd4a435f2f` |
| Integration `OrganizationEndToEndTest.kt` | `dc370988283956e68da678a837d0b7a32a5825990bdc2cf89e766f28ef101860` |

- 6/6 SHA 与 Worker 回执一致；3 tracked + 3 new 全在白名单；无第 7 个文件、白名单外变化 0。
- B1 21 文件、ContextStatus/WorkerStatus/公开 API、DefaultExtractionRunner/OrganizationWakeup 类型、Schema/依赖/VFS/common 未改。

## 2. 静态审查

| 契约 | 结果 |
| --- | --- |
| Factory 配置门 | `validateOrganizationBinding` 在资源打开前执行；null 保持禁用；非法绑定 `INVALID_CONFIG` 零副作用 |
| Impl 构建顺序 | DB→VFS→LLM→OrganizationPipeline→ExtractionPipeline；RUNNING 前不执行业务；RUNNING 后先 activate Organization 再 Extraction |
| Immediate wakeup | Extraction 默认 `OrganizationWakeup` 指向真实 OrganizationPipeline；测试注入仍可覆盖 |
| 真实组件 | CandidateRetriever/Governance/Committer/SourceGuard/Worker 真实组合；Map wakeup 保持 no-op |
| workers 映射 | 仅显式 organization 配置时暴露 `workers["organization"]`；IDLE/RUNNING/STOPPED/FAILED 全映射，单条失败不 FAILED |
| 启动失败 | Organization/Extraction 任一启动失败均清理已取得的双 pipeline 与后续资源 |
| 关闭顺序 | 同锁双 beginClosing；Extraction→Organization→LLM→VFS→executor；失败继续释放并共享主失败 |
| 回调关闭 | Organization Runner/Map 回调内 Context close 被具名拒绝，不破坏 RUNNING 状态 |
| 公共 E2E | 真实 Factory/SQLite/Fake HTTP 完成 Raw→Pending 清除→Organized/Source/watermark；Map 未接 |

## 3. Lead 独立实跑

| 命令 | 结果 |
| --- | --- |
| `:context:runtime:test :integration-tests:context:test --rerun-tasks` | **exit 0**，BUILD SUCCESSFUL in 42s |
| Factory/Runtime wiring 重复 | **3/3 exit 0**（10s / 11s / 9s） |
| 公共 Factory E2E 重复 | **2/2 exit 0**（7s / 6s） |
| `./scripts/check` | **exit 0**，BUILD SUCCESSFUL in 2m36s，131 tasks |

- CT16 B2 新增：**16 例**；1637 → **1653**。
- 14 模块源码行首 `@Test` 与 XML：**1653 = 1653**，fail/error/skip 全 0。
- B2 XML：Factory 12（含新增 2）、RuntimeWiring 6、LifecycleWiring 6、E2E 2；新增 16。

| 模块 | 源码 | XML | 模块 | 源码 | XML |
| --- | ---: | ---: | --- | ---: | ---: |
| common | 23 | 23 | integration-tests/vfs | 107 | 107 |
| context/api | 26 | 26 | vfs/api | 66 | 66 |
| context/core | 93 | 93 | vfs/core | 305 | 305 |
| persistence-sqldelight | 178 | 178 | vfs/git-backup | 73 | 73 |
| **context/runtime** | **465** | **465** | vfs/persistence | 52 | 52 |
| context/server | 30 | 30 | vfs/runtime | 61 | 61 |
| **integration-context** | **20** | **20** | vfs/storage | 154 | 154 |
| **合计** | **1653** | **1653** | | | |

## 4. A01～A08 映射

| # | Lead 证据 | 结果 |
| --- | --- | --- |
| A01 | FactoryTest：非法绑定 5 组零资源；合法显式配置 create 零副作用并 IDLE；null 空 workers | 通过 |
| A02 | E2E：Extraction COMMIT 真实唤醒 Organization；空 memories 不唤醒 | 通过 |
| A03 | RuntimeWiring：Periodic tick seam 有 Pending 才处理；periodic disabled 无 Cron | 通过 |
| A04 | workers：IDLE/RUNNING/STOPPED/FAILED；Governance 单条失败保持 IDLE/Pending | 通过 |
| A05 | Lifecycle：Organization/Extraction start failure 双清理；close 赢 start；Organization 回调 close 拒绝 | 通过 |
| A06 | Lifecycle：Extraction→Organization→LLM→VFS→executor；pipeline 失败继续释放、共享主失败 | 通过 |
| A07 | E2E：1 Raw、0 Pending、1 Organized、1 Source；content/domains/上海毫秒/watermark | 通过 |
| A08 | 定向、3 次 wiring、2 次 E2E、全量、14 模块与全部边界 | 通过 |

## 5. 边界独立核对

| 项 | 结果 |
| --- | --- |
| 81 VFS 基线 | 按 manifest 独立复算：**Checked 81 files: all OK** |
| `vfs` / `integration-tests/vfs` | status 与 tracked diff 均为空 |
| Core 依赖 | main 无 Ktor/serialization import；compileClasspath 仅 context/api、common、coroutines/stdlib |
| Schema | `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3` 未变 |
| B1/CT13/14/15 | 46/46 SHA 未变，缺失/歧义/不一致 0 |
| 公共 API/类型 | ContextStatus、WorkerStatus、DefaultExtractionRunner/OrganizationWakeup 类型 diff 0 |
| 构建/依赖 | 相关 build 文件 diff 0；无新增依赖 |
| Map/CT17 | OrganizationMapWakeup 生产实例 no-op；无 Map 生成 |
| 真实模型 | **not-run**；Fake HTTP 仅验证协议、事务、调度和接线 |

## 6. 非阻断观察

| # | 项 | 说明 |
| --- | --- | --- |
| L1 | 既有 CT09 一次 10s timeout 的历史观察仍保留 | B2 定向、重复与全量均通过；未修改该文件 |
| L2 | public WorkerStatus.lastError 未填 | D12 未要求新增字段/错误文本；单条失败不标 FAILED，保持 IDLE |
| L3 | 公共 E2E 首次 Organize 无 Candidate Scanner 调用 | 空 Organized 快照按 CT13 契约零 LLM；Governance 与事务闭环真实执行 |

## 7. 结论

- 6 文件白名单、Factory/Impl/workers/E2E、定向/重复/全量与边界证据均通过 Lead 独立核对。
- **B2 LEAD 初验通过，无 R 项**；提交 Architect 复核并裁定 B2/CT16 是否 DONE。
- CT17/CM4 未启动；Map 未实现；无 git 写操作/推送。

## 8. 证据

- Worker：`.local/validation/cm3/worker/ct16-b2/`
- Lead：`.local/validation/cm3/lead/ct16-b2/`
