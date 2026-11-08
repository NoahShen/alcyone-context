# CM3 当前进度与恢复点（2026-11-07；2026-11-08 已整体收口）

**状态：CM3 DONE（CT13/CT14/CT15/CT16 全部通过 Architect 整体复核）；CT17/CM4 不启动。**

## 1. 当前完成

| 任务 | 状态 | 关键证据 |
| --- | --- | --- |
| CT13 Scanner/Candidate Retriever | **DONE** | 1498=1498；B1/R1/B2 复核；生产/资源 8 SHA 未变 |
| CT14 Governance | **DONE** | 1558=1558；B1 12 文件/60 例；81 基线/Core/Schema 通过；未接生产 |
| CT15 原子提交/幂等/水位 | **DONE** | B1 5 文件/29 例；定向 29/0/0/0；1587=1587；Architect 复核通过 |
| CT16 调度/生产接线 | **DONE** | B1 21 文件/50 例、B2 6 文件/16 例；Lead/Architect 复核均通过；整体 1653=1653 |
| CM4 | **未启动** | 不在当前授权 |

## 1.1 2026-11-08 CM3 最终收口

- Architect 独立重跑 `./scripts/check`：exit 0，2m18s，131/131 tasks。
- 14 模块源码/XML **1653=1653**，失败/错误/跳过全 0；81 VFS 基线 all OK；VFS/common/Schema/依赖边界通过。
- 详细结论见 [CM3 里程碑复核](milestone-review.md)：**CM3 APPROVED / DONE**。

## 2. 当前代码/证据边界

- CT13 组件：`core/organization` + `runtime/organization` 的 Scanner/Candidate Retriever；未接 Factory/Impl/Scheduler。
- CT14 组件：`GovernanceAction/Decision/Validator/Prompt` + `LlmGovernanceService/JsonGovernanceResponseParser`；未接 Factory/Impl/Config/Loader。
- CT15 组件：`OrganizationCommitter/Result/Exception`；Source 幂等、目标版本、Organized/Source/Pending 原子提交和全局毫秒水位已验收；仍不接生产。
- CT16 B1/B2 组件、Config、Factory/Impl、public Worker 状态和真实 Runtime/SQLite + Fake HTTP 已通过 Lead 与 Architect 验证；Map 仍 no-op。
- 全量基线：**1653 = 1653**；`./scripts/check` exit 0（131 tasks）；81 VFS 基线 all OK；VFS diff 空；Core 无 Ktor/serialization；Schema `ec727a53…` 未变。
- CT13/CT14 20 个组件/资源 SHA 未变；CT15 5 个交付 SHA 与 Architect 复核一致。
- 无 git add/commit/push；无发布。

## 3. 证据目录

- CT13：`.local/validation/cm3/worker/ct13-b2/`、`.local/validation/cm3/lead/ct13-b2/`、`.local/validation/cm3/architect/ct13-b1-r1/`
- CT14：`.local/validation/cm3/worker/ct14-b1/`、`.local/validation/cm3/lead/ct14-b1/`
- CT15：`.local/validation/cm3/worker/ct15-b1/`、`.local/validation/cm3/lead/ct15-b1/`
- 文档：`docs/context/tasks/cm3-ct13/`、`cm3-ct14/`、`cm3-ct15/`、`cm3-ct16/`、`docs/context/milestones/cm3/`

## 4. 恢复时入口

1. 读取本文件、`CT15_任务书.md`、`B1_Architect复核.md`、`CT16_任务书.md`、`tasks/cm3-ct16/方案核对.md`。
2. CM3 已整体收口 DONE；本文件仅作历史恢复点，不再作为待办入口。
3. 未获用户明确授权前不进入 CT17/CM4。
4. 收口前仍不改 VFS/common/Schema/索引/依赖，不运行真实模型、不发布。
5. 本恢复点不得把计划文档当作实现或验收。

## 5. 未决事项

- CT16 已收口；后续若发现需修正 B1/B2 已验收语义，必须停止并提交 Architect 裁定。
- CT14 非阻断观察（既有 CT09 偶发 timeout、取消测试等待方式、Prompt domains 拼接、Service 不持有 DB）保留，不阻断 CT14；恢复后按需单独评估。
- 真实模型语义评测仍未运行，Fake 结果不得冒充语义质量。
