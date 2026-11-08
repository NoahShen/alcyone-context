# CT18 B1 · Architect 复核

**日期**：2026-11-08
**状态**：**B1 APPROVED / DONE；R1 CLOSED**
**复核对象**：[B1 Worker 交付](B1_Worker交付.md)、[B1 Lead 初验](B1_Lead初验.md)、[B1-R1 Lead 复验](B1_R1_Lead复验.md)、实际工作区 Diff
**依据**：[B1 委派单](B1_委派单.md)、[CT18 任务书](CT18_任务书.md)、[CT18 方案核对](CT18_方案核对.md)

## 1. 最终结论

**CT18 B1 APPROVED / DONE。**

- R1 CLOSED：根对象重复 key 在 JSON tree 构建前扫描；重复 `overview/navigation/domains` 与转义等价 key 均抛 `LlmException(INVALID_RESPONSE)`。
- Architect 独立复跑 Parser 定向测试 exit 0。
- B1 完成范围符合 18 文件白名单；B1-R1 仅修改 Parser、Parser 测试、交付文档；白名单外变更 0。
- 证据通过：14 模块 **1756=1756**、fail/error/skip 0、全量 `./scripts/check` exit 0、Schema SHA 不变、CT17 23 SHA 一致、81 VFS 基线 75+6、`git diff --check` 空。
- CT18 因单 B1 已完成全部组件与测试而 APPROVED / DONE，见 [CT18 收口报告](CT18_收口报告.md)。
- CT19 未解锁，不启动、不规划、不派发。

## 2. 最终证据

| 项目 | 结论 |
| --- | --- |
| B1 文件 | 18 文件白名单；18 新增 / 0 修改既有文件 |
| B1-R1 文件 | 仅 Parser、Parser 测试、Worker 交付文档 |
| 白名单外 B1 变更 | 0 |
| 新增测试 | 46（45 初始 + 1 重复 key） |
| 14 模块计数 | source `@Test` = XML = **1756**；fail/error/skip 0 |
| Parser 定向 | Architect 独立 exit 0；Parser XML 8=8 |
| 全量回归 | `./scripts/check` exit 0，131/131 tasks |
| CT17 SHA | 23/23 一致 |
| 81 VFS 基线 | 75 一致、6 个 CT17 B1 批准差异、未批准差异 0 |
| Schema | `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3` 未变 |
| 依赖/构建/common/API/Factory 等 | CT18 白名单外零修改 |
| Git | `git diff --check` 空；无 Git 写操作 |

## 3. R1 关闭

- `RootObjectKeyScanner` 在 `Json.parseToJsonElement` 前扫描根对象成员名。
- 成员名先严格解码，`over\u0076iew` 与 `overview` 视为同一 key。
- 重复三个允许字段与转义等价 key 均返回 `INVALID_RESPONSE`。
- 嵌套对象只做字符串/括号平衡，不递归检查重复 key，符合 CT18 JSON Schema 范围。
- 新增重复 key 测试真实进入 XML，未出现假执行。

## 4. 生产边界复核

- Snapshot：单次 `database.read`、256 分页、全量、ID ASC、T1 与集合 MAX 一致；退出 ReadContext 后才 LLM。
- Prompt/LLM：`MEMORY_MAP`、JSON Schema/Object、全量一次请求、预算失败 0 LLM、length 失败。
- 空库：0 LLM，确定性空 Map，`sourceUpdatedAt:null`。
- Parser/Validator/Renderer：严格 JSON/Front Matter、真实 Domain 子集、确定性渲染。
- Publisher：固定 `alcyone://memory/map.md`，只调用 `Vfs.safeReplace`，无普通 write/move/delete 降级。
- Service：生成成功后才发布；生成失败 0 发布；`STATE_ERROR + PARTIAL` 原样传播。
- 未接 API/Factory/Impl/Config/Cron/Bootstrap/Search/Get/HTTP。

## 5. 当前状态

- B1：**APPROVED / DONE**。
- R1：**CLOSED**。
- CT18：**APPROVED / DONE**。
- CT19：**LOCKED / 未启动 / 不规划**。
- 真实模型：**not-run**。
- 无 Git 写操作。
