# CT26 · Architect 首轮复核（历史，已关闭）

## 本次修改（2026-11-10，R1 关闭；不得用于现行 CM5/CM6 状态）

- 本文件记录提交前工作区快照的首轮 R1，不表示当前 CT26/CM5 仍失败。后续生产修复、全量验证、追溯复核及 `main/origin/main` 提交 `3c7d5c3` 已关闭 R1。
- 当前有效裁定为 [Architect 追溯复核](Architect_追溯复核.md)：**CT26 APPROVED / DONE；CM5 APPROVED / DONE**。不得据此旧文件退回 CT26 或阻断已授权的 CM6。
- 历史失败清单保留用于审计；不删除、不将工具超时写成通过。此次协调未执行 Git 写操作。

## 1. 最终状态核对（2026-11-10 14:40 CST）

| 项目 | 结果 |
| --- | --- |
| 当前基线 | `main`、`origin/main` 均为 `3c7d5c3 feat(context): CM5 Dreaming 全链（CT22～CT26）` |
| 生产工作树 | `git status --short -- context vfs common integration-tests gradle` 无输出 |
| 后续独立全量日志 | `/tmp/final-check2.log`：`BUILD SUCCESSFUL in 2m 23s`，`132 actionable tasks: 130 executed, 2 up-to-date` |
| 14 模块 XML | 现场读取 **2135/2135**，277 个 XML；与追溯复核一致 |
| 关键修复 | `AlcyoneContextImpl.defaultDreamingRuntime` 在完全无 Dreaming Task 且无 Cron 时返回 null，不再构造需要完整绑定的 Stage |
| Schema / 真实模型 | Schema 未变；真实模型 `not-run` |

上述证据覆盖本文件 §2 的 R1-1 与 R1-2；旧日志不是当前基线缺陷。当前继续按已授权 CM6 推进，真实模型仍需另行授权。

## 2. 历史 R1（提交前快照）

### R1-1 · 非 Dreaming 生命周期测试被新增配置校验阻断（已修复）

历史日志：`.local/tmp/ct26-architect-check.log`、`.local/tmp/ct26-architect-isolated-check.log`。隔离目录 `/tmp/alcyone-ct26-architect.4cxAYx` 复用项目 `.local` 工具链，复现 24 例；该次运行达工具 1200 秒上限并由 Gradle stop 收尾，没有 exit 0 或完整 XML 证据。

受影响历史测试：`ExtractionPipelineLifecycleTest` 1、`ExtractionRuntimeWiringTest` 4、`LlmRuntimeLifecycleTest` 3、`MemoryMapFactoryBindingTest` 1、`MemoryMapLifecycleWiringTest` 5、`MemoryMapOrganizationWakeupTest` 3、`MemoryMapRuntimeWiringTest` 4、`OrganizationLifecycleWiringTest` 5，合计 **24 例**。

当时仅部分 Organization/Extraction/Memory Map Task 的配置在 Factory 创建阶段抛 `ContextConfigException(INVALID_CONFIG)`。后续已通过无 Dreaming Task 时跳过 Runtime 创建修复，完整/部分 Dreaming 配置校验保持。

### R1-2 · 旧快照编译错误（已由后续基线消除）

首轮工作区曾出现 VFS `DefaultVfs.kt` 引用旧 `vfs.api.*` 包，以及 `WatermarkContractTest.kt:165` 的 `OrganizedMemoryTxOpsImpl` 不可解析。当前提交已使用正确 VFS 包路径，后续全量构建成功；不要求额外修改 VFS 业务或 Schema。

## 3. 局部静态证据（历史）

- 默认 `AlcyoneContext.runDreaming()` 返回 `UNAVAILABLE`。
- 手动/Cron 单 worker、关闭等待、真实 SQLite 多批与独立 JVM Commit 后 SIGKILL 恢复均有正式测试。
- 固定脱敏集明确真实模型 `not-run`；Schema SHA-256 `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`。
- 这些局部检查从未等同于全量通过；最终全量以 §1 与追溯复核为准。
