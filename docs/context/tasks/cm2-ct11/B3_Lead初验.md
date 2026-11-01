# CT11 B3 · Lead 独立初验（生产接线与全量验收）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**B3 APPROVED / DONE；CT11 APPROVED / DONE（Architect 已裁定）**。
**依据**：[B3_委派单](B3_委派单.md)、[CT11 任务书](../../milestones/cm2/CT11_任务书.md) §0.1、[B2_Lead初验](B2_Lead初验.md)

**收口（2026-10-30）**：Architect 已复核 B3/CT11，状态回填 DONE；原初验证据保留。

## 0. 本次修改（2026-10-30，B3 初验记录）

Worker 首轮因 CT08 PROMPT_ONLY 生命周期与 Builder fail-fast 冲突暂停回退；Lead 按 Architect 确认的“合法 extraction 绑定才建 pipeline”口径重新派发。B3 完成后 Lead 独立复算 SHA、跑 `./scripts/check`、14 模块计数、VFS/Core/schema 边界，并复核生产接线/唤醒/非法响应。**全部通过**。

## 1. 交付面与边界

| 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- |
| `AlcyoneContextImpl.kt` | `71ce61d0b53ecf7fe7641bc142c6fa64726de876c9e1a3324fbc3cdae894271c` |
| `AlcyoneContextFactory.kt` | `db6bb0a6d57c6d7a370e9d2a16c736005bdb44aed9c9aae1d65d2dfb5d05482e` |
| `ExtractionRuntimeWiringTest.kt` | `3da3e9ece59f40eb970afc40658e3c99b9d37360425d3151bee5ff5bb444149a` |

- B1/B2/CT10/CT09/SQL/Schema SHA 未变；**CT08 `LlmRuntimeLifecycleTest b86da064…` 未修改**；无新依赖/公开 API；无 git。

## 2. Lead 独立全量回归

| 命令 | 结果 |
| --- | --- |
| `./scripts/check` | **exit 0**，`BUILD SUCCESSFUL in 2m 4s`，**131 actionable tasks: 131 executed** |
| 14 模块行首 `@Test` vs XML | **1438 = 1438**，差额 0、失败/错误/跳过 0 |

| 模块 | 源码 | XML | 模块 | 源码 | XML |
| --- | ---: | ---: | --- | ---: | ---: |
| common | 23 | 23 | integration-tests/vfs | 107 | 107 |
| context/api | 26 | 26 | vfs/api | 66 | 66 |
| context/core | 43 | 43 | vfs/core | 305 | 305 |
| context/persistence-sqldelight | 176 | 176 | vfs/git-backup | 73 | 73 |
| context/runtime | 307 | 307 | vfs/persistence | 52 | 52 |
| context/server | 30 | 30 | vfs/runtime | 61 | 61 |
| integration-tests/context | 15 | 15 | vfs/storage | 154 | 154 |
| **合计** | **1438** | **1438** | | | |

runtime 300 → 307（+7）。

## 3. 生产接线核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 公共 `create` 签名不变；internal `create` 新增可选 `organizationWakeup`（默认 no-op） | Factory/Impl 源码 | ✔ |
| 合法性判定：EXTRACTION Task + Profile 存在 + `JSON_SCHEMA/JSON_OBJECT` | `hasLegalExtractionBinding`；wiring 1b 用例（PROMPT_ONLY/无 Task → pipeline null、start/close 正常） | ✔ |
| 合法绑定：Builder → JSON Validator → Committer → Runner（`maxWindowIterations=initialWindowTurns`、wakeup）→ CT09 Pipeline | 源码 + wiring 2/5 用例 | ✔ |
| 无 `context.llm`：不建 Runner/pipeline，行为同 CT09 | wiring 1/6 用例 | ✔ |
| 注入 Runner 优先 | 源码 `extractionRunner ?: default...` | ✔ |
| 合法绑定但 Builder 抛 `INVALID_CONFIG`：start 失败逆序清理/CLOSED | 源码既有失败路径；未吞异常 | ✔ |
| 无新公开 API/依赖；Factory 未暴露 Runner/LLM | 源码审查 | ✔ |
| CT08 PROMPT_ONLY 通用 LLM 生命周期保持 | `LlmRuntimeLifecycleTest` 4/4 PASS | ✔ |

## 4. 行为与证据核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 公共 Factory + 合法 LLM 配置：Count 触发 → 真实 LLM → 校验 → 原子提交 | wiring 2：1 Raw、稳定 ConversationTurnRef、pending、5 Target complete、RUNNING、stub 1 请求 | ✔ |
| 非法模型响应：0 Raw/Pending、Target pending、Context RUNNING、close 成功 | wiring 3 | ✔ |
| wakeup：raw>0 一次、空 memories 0 次；失败吞掉（B2 已验证） | wiring 4 + B2 用例 | ✔ |
| `maxWindowIterations = extractionConfig.initialWindowTurns` | wiring 5 源码断言 | ✔ |
| 无 LLM 时 stub 0 请求 | wiring 1/6 | ✔ |
| 日志脱敏（无 Key/正文/Raw） | wiring 2 捕获日志断言 | ✔ |
| Scheduler 消费 Execution/Commit 异常 | 非法响应用例 Context 保持 RUNNING、Target pending | ✔ |

## 5. 边界证据

| 项 | 结果 |
| --- | --- |
| `git status --porcelain vfs integration-tests/vfs` | 0 行 |
| `git diff --stat -- vfs integration-tests/vfs` | 0 行 |
| 81 VFS 基线 | `Checked 81 files: all OK` |
| Core 无 Ktor | build 文本/imports/compileClasspath 均无命中 |
| `ContextSchema.sq` | `ec727a53…` 未变 |
| Schema 临时库探针（Lead） | `user_version=1`、6 表、5 索引，1/1 PASS |
| 构建/依赖文件 | B3 窗口未改 |

## 6. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | 非法 extraction 绑定（无 EXTRACTION Task/Profile/PROMPT_ONLY）不建 pipeline、也不报错，保留通用 LLM 生命周期 | Architect 已确认该准入边界；Builder 自身 fail-fast 仅在合法绑定构造后生效 |
| L2 | `OrganizationWakeup` 生产默认 no-op；CT13 接入前只有 `organization_pending` 表可恢复 | 符合 D2/CT13 分期 |
| L3 | 公共 create 在合法 JSON extraction 配置下会启动 Count/Idle pipeline | CT11 D3 预期行为；无 LLM 行为不变 |
| L4 | 非法响应由 Scheduler 普通失败消费，日志事件为 `extraction.run.failed` | 符合 D10；不改变 Context 生命周期 |
| L5 | B3 为接线+验收批次，未新增 B1/B2 测试以外逻辑 | 符合 D14.3 |

## 7. 结论与下一步

- 合法 extraction 绑定才构造真实 Runner/Pipeline；无 LLM/非法绑定保持既有生命周期；真实 Count → LLM → 校验 → 原子提交、非法响应 pending、wakeup/上限/日志均通过。
- `./scripts/check` clean 全绿；14 模块 **1438=1438**；81 基线 all OK；vfs diff 空；Core 无 Ktor；schema 未变；CT08 4/4。
- **B3 Lead 初验通过，提交 Architect 裁定 CT11 是否 DONE**；CT12/CT13 未启动，仍无 git 操作与发布。

### 证据路径

- `.local/validation/cm2/lead/ct11-b3/check.log`、`test-counts.txt`、`vfs-git.txt`、`vfs-baseline.txt`、`core-dependencies.log`、`schema-probe-results.xml`
- Worker：`.local/validation/cm2/worker/ct11-b3/`
