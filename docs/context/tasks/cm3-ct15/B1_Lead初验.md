# CT15 B1 · Lead 独立初验（Organization Committer 原子提交）

**日期**：2026-11-07
**初验人**：CM3 Lead（独立复核）
**结论**：**B1 LEAD 初验通过，无 R 项；Architect 复核 APPROVED；CT15 DONE（2026-11-07）。**
**依据**：[B1 委派单](B1_委派单.md)、[CT15 任务书](../../milestones/cm3/CT15_任务书.md)、[方案核对](方案核对.md)、Architect D1～D12 终稿

## 0. 本次修改（2026-11-07，B1 Lead 独立初验）

Worker 仅新增 5 个白名单源码文件。Lead 复算 5 个 SHA、逐文件审查事务实现与 29 例测试，并独立重跑 CT15 定向测试和完整 `./scripts/check`；随后独立核对 14 模块计数、81 基线、VFS diff、Core 依赖、Schema、CT13/CT14 组件 SHA 与生产零接线。未发现阻断缺陷，未修改生产/测试代码，无 git 操作。

## 1. 交付面与 SHA

| 文件 | SHA-256 |
| --- | --- |
| Core `OrganizationCommitter.kt` | `f8bdc5ae0bf16c3424cadd15ed0d2bed45649debcba29a8e3293aa07035d03c8` |
| Core `OrganizationCommitResult.kt` | `5b782c9da3cee2ab891a017fbcda6fa6a52de7003f22a843af676bf029802e31` |
| Core `OrganizationCommitException.kt` | `b7c490839a66b99442735f8eae6b6e6d6e9db23b4e1c810cb94f28a691fcb105` |
| Runtime `OrganizationCommitterTest.kt` | `39825131a89478857bbee62b2c2f731b278dedef92b697772dbfdddba42ad71e` |
| Runtime `OrganizationCommitterTestSupport.kt` | `0a088b838b6105a0fd5ca56d2f4ced7162794853b9adadebecd25c579ee8e25e` |

- 5 个 SHA 与 Worker 回执一致；源码树中不存在第 6 个 CT15 源码文件。
- 未修改 Repository/Port、SQL/Schema/索引、依赖、VFS/common、Factory/Impl、Config/Loader、Scheduler、CT13/CT14 或构建。

## 2. 静态审查

| 契约 | 结果 |
| --- | --- |
| D2 API | `commit(rawId, candidates, decision)`；未引入 RawMemory 全对象或 request DTO |
| D3 幂等优先 | 事务内先查 Source/Pending；Source 已关联时不触 Validator/Clock/水位，仅出队或 no-op |
| D3 Validator 复验 | 仅 Source 未关联且 Pending 存在时调用 CT14 Validator；异常映射 `INVALID_DECISION` |
| D5 异常边界 | `CancellationException` / `ContextPersistenceException` 不捕获、不包装；提交异常仅携带安全 reason |
| D6 分支 | 已关联+Pending 出队；已关联无 Pending no-op；未关联无 Pending `STALE_DECISION`；未关联+Pending 继续 |
| D7 版本 | 事务内重读 target；缺失或 `stored.updatedAt != candidate.updatedAt` 为 `STALE_TARGET` |
| D8 写入 | MERGE 无 content 走一次 `advanceUpdatedAt`；有 content 与 UPDATE 各走一次 `update`；无二次 advance |
| D9 水位 | `nowMs` 截断毫秒；NEW createdAt=nowMs；`Instant.MAX` 显式 `WATERMARK_EXHAUSTED` |
| D10 行数/ID | Organized/Source/Pending 均精确 1；仅显式 ID 预检命中整笔重试，最多 3 次；无 OR REPLACE |
| D12 白名单 | 5 个文件严格命中；test-only wrapper/trigger 未进入生产 ContextDatabase 契约 |

## 3. Lead 独立实跑

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle :context:runtime:test --tests '*OrganizationCommitterTest' --rerun-tasks --console=plain` | **exit 0**，`BUILD SUCCESSFUL in 11s`，25 tasks executed |
| `./scripts/check` | **exit 0**，`BUILD SUCCESSFUL in 2m 24s`，131 tasks executed |

- CT15 XML：**29 tests / 0 failure / 0 error / 0 skipped**。
- 14 模块源码行首 `@Test` 与 XML：**1587 = 1587**，各模块差额 0、失败/错误/跳过 0；基线 1558 + 29。

| 模块 | 源码 | XML | 模块 | 源码 | XML |
| --- | ---: | ---: | --- | ---: | ---: |
| common | 23 | 23 | integration-tests/vfs | 107 | 107 |
| context/api | 26 | 26 | vfs/api | 66 | 66 |
| context/core | 93 | 93 | vfs/core | 305 | 305 |
| persistence-sqldelight | 176 | 176 | vfs/git-backup | 73 | 73 |
| **context/runtime** | **403** | **403** | vfs/persistence | 52 | 52 |
| context/server | 30 | 30 | vfs/runtime | 61 | 61 |
| integration-context | 18 | 18 | vfs/storage | 154 | 154 |
| **合计** | **1587** | **1587** | | | |

## 4. A01～A08 映射

| # | Lead 证据 | 结果 |
| --- | --- | --- |
| A01 | `a01 associated source with pending...` 使用取消 Clock + 非法 decision 证明先幂等；Source-only no-op；未关联无 Pending stale | 通过 |
| A02 | NEW 原子写；ID 冲突后换新 ID；3 次耗尽无 Organized/Source 新增且 Pending 保留 | 通过 |
| A03 | MERGE 有无 content；UPDATE domains 提供/缺席；旧 Source 保留；MERGE 水位推进 | 通过 |
| A04 | target updatedAt 变化、target 缺失、非法字段、越权 target 均 0 半写，Pending 保留 | 通过 |
| A05 | 空表毫秒、已有更高 MAX、同毫秒连续提交、上海 `+08:00` 原文、`Instant.MAX` 显式失败 | 通过 |
| A06 | insert/update/advance/source/dequeue 行数故障；真实 SQLite trigger 失败；全部整笔回滚 | 通过 |
| A07 | 真实 COMMIT 后抛 `ContextPersistenceException`；再次调用 Source 幂等收敛；取消/持久化异常原样传播 | 通过 |
| A08 | 定向 + 全量 + 14 模块 + 81 基线 + VFS/Core/Schema/生产边界 | 通过 |

## 5. 边界独立核对

| 项 | 结果 |
| --- | --- |
| 81 VFS 基线 | 按 `.local/validation/cm1/architect/vfs-baseline-sha256.json` 独立复算：**Checked 81 files: all OK** |
| `vfs` / `integration-tests/vfs` | `git status --porcelain` 与 tracked diff 均为空 |
| Core 依赖 | 主源码无 Ktor/serialization import；`:context:core:compileClasspath` 仅 context/api、common、coroutines/stdlib |
| Schema | `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3` 未变 |
| CT13/CT14 组件 | 20/20 SHA 未变，缺失/歧义/不一致 0 |
| 生产接线 | 除 3 个 CT15 Core 自身文件外无引用；Factory/Impl/Config/Loader/Scheduler 无 CT15 类型 |
| 真实模型 | **not-run**；测试仅使用真实 SQLite + test-only Fake ID/Clock/故障 seam |

## 6. 非阻断观察

| # | 项 | 说明 |
| --- | --- | --- |
| L1 | 测试辅助使用临时 trigger/wrapper 注入故障 | 仅位于 Runtime test source，符合 D12；生产 ContextDatabase 契约未改 |
| L2 | `Instant.MAX` 通过 test-only `MaxWatermarkDatabase` 注入 | 真实 SQLite 无法表示该年份；生产溢出分支仍由 Committer 显式处理并无写回滚 |
| L3 | 全量构建保留若干既有编译 warning | 与本批 5 个文件无关；`spotlessCheck` 与全部测试通过 |

## 7. 结论

- 5 文件白名单、D1～D12 契约、A01～A08、定向/全量与边界证据均通过 Lead 独立核对。
- **B1 LEAD 初验通过，无 R 项**；提交 Architect 复核并裁定 CT15 是否 DONE。
- CT15 仍保持组件化，不接生产；CT16/CM4 未启动；无 git 写操作/推送。

## 8. 证据

- Worker：`.local/validation/cm3/worker/ct15-b1/`
- Lead：`.local/validation/cm3/lead/ct15-b1/`

## 9. Architect 收口（2026-11-07）

- Architect 独立复核通过：5 文件 SHA 一致、D1～D12 静态审查通过、29 例与 XML 一致、A01～A08 覆盖、Schema/81/Core/生产边界通过。
- Architect 会话内 Gradle 因 FileLock socket 权限无法启动，属复核环境限制；由 Worker/Lead 双重实跑、XML、静态审查与独立 SHA 补足，不构成交付缺陷。
- **CT15 B1 APPROVED / DONE；CT15 APPROVED / DONE**，详见 [B1 Architect 复核](B1_Architect复核.md)。
