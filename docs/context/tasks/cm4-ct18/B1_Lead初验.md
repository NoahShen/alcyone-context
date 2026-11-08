# CT18 B1 · Memory Map 生成与安全发布 Lead 初验

## 本次修改（2026-11-08）

Lead 独立核对 18 文件白名单、CT17 23 文件 SHA、Snapshot/T1、Prompt/LLM、Parser/Validator/Renderer、Publisher/Service、45 个新增用例、3 模块定向和 1755=1755；未修改生产/测试代码，未派发 Worker，未启动 CT19，无 Git 写操作。

**日期**：2026-11-08
**状态**：**B1 Lead 初验发现 R1；B1 IN_REVIEW，不建议提交 Architect；CT18 未 DONE**
**依据**：[B1 委派单](B1_委派单.md)、[B1 Worker 交付](B1_Worker交付.md)、[CT18 任务书](CT18_任务书.md)、[CT18 方案核对](CT18_方案核对.md)
**基线**：CT17 APPROVED / DONE；14 模块 **1710=1710**；Schema `ec727a53…8ea3`

## 1. 结论

- **R 阻断：1**
- **P 建议：0**
- **N 备注：2**
- R1：JSON Parser 未拒绝重复 JSON 字段；`Json.parseToJsonElement` 底层对象构建使用 map put，重复 key 会被覆盖而不是报错。
- 其余白名单、Snapshot/T1、预算、空库、Renderer、Publisher/Service 与测试真实性主路径通过。
- **不建议提交 Architect**；退回 Worker 做最小 B1-R1，修复 Parser 与测试。

## 2. 文件白名单与 SHA

实际 B1 变更严格为委派单 §3 的 **18 文件**：18 新增 / 0 修改既有文件；白名单外 B1 代码/测试变更 0。

- Core 生产/资源：6 文件。
- Runtime 生产：4 文件。
- 测试：7 文件。
- Worker 交付报告：1 文件。

Worker 报告列出的 17 个代码/测试 SHA 与 Lead 独立复算一致；报告自身不内嵌自引用 SHA。工作区中 `docs/context/tasks/cm4-ct18/CT18_任务书.md`、`CT18_方案核对.md`、`B1_委派单.md` 为本轮方案文档，不属于 B1 Worker 18 文件。

## 3. 生产实现审查

### 3.1 Snapshot / T1

通过：

- 单次 `ContextDatabase.read`，默认 256 条/页，偏移受检。
- 同一 ReadContext 读取 `maxUpdatedAt()`。
- 退出 ReadContext 后排序，Snapshot 校验严格 ID ASC 与 T1/集合 MAX 一致。
- 空表 T1=null。
- LLM 期间新增 Organized 不影响已捕获 Snapshot/T1；测试证明数据库最终可见新行。
- 未查询 T2、未使用发布锁。

### 3.2 Prompt / LLM / 预算

通过：

- 使用 `LlmTask.MEMORY_MAP`。
- Prompt/Schema 资源存在且版本化；全量 Memory 渲染。
- 缺 Task/Profile 或 PROMPT_ONLY → `INVALID_CONFIG`。
- 一次全量请求；`LlmBudget.requireWithinBudget`；超预算 `LOCAL_BUDGET_EXCEEDED` 且 0 LLM。
- `finish_reason=length` → `OUTPUT_TRUNCATED`。
- 空库不调用 LLM，生成确定性空 Map 与 `sourceUpdatedAt=null`。

### 3.3 Validator / Renderer

通过：

- Overview/Navigation 非空、LF、无保留边界冲突。
- Domain 空白/未知拒绝、重复稳定去重。
- Front Matter 仅两键，使用 `TimeCodec`；空库 null。
- Renderer 输出固定标题、Domain 列表、上海毫秒时间。
- Front Matter Parser 拒绝缺失/重复/未知键、非规范时间、CRLF、非法 UTF-8、错误标题和未排序 Domain。

### 3.4 Publisher / Service

通过：

- 固定 `alcyone://memory/map.md`。
- 唯一生产写调用为 `Vfs.safeReplace`；静态和测试均无 write/move/delete 降级。
- 生成/校验失败 Service 不调用 Publisher。
- `STATE_ERROR + PARTIAL` 原样传播；提交前失败旧 Map 保留。
- 未读取旧 Map、未查 T2、未接 Runtime。

## 4. R1：重复 JSON 字段未拒绝

**严重性：R（阻断 B1）。**

位置：

- `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/JsonMemoryMapResponseParser.kt`
- `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/JsonMemoryMapResponseParserTest.kt`

事实：

- Parser 直接调用 `Json.parseToJsonElement(content)`。
- Kotlinx serialization JSON tree reader 构建对象时使用 map `put`；重复 key 不会抛错，后值覆盖前值。
- 当前 Parser 只检查解析后的 `JsonObject.keys == {overview,navigation,domains}`，因此重复 `overview`/`navigation`/`domains` 会被静默覆盖。
- 当前 Parser 测试没有重复 JSON key 反例。

这违反本次初验要求的“严格 JSON、重复字段拒绝”，可能让模型输出两个不同 overview/navigation 时被静默接受。

**修复要求（B1-R1，最小范围）：**

1. 在解析前增加严格 JSON 重复 key 检测，至少覆盖根对象三个允许字段；重复 key 统一抛 `LlmException(INVALID_RESPONSE)`。
2. 不改变合法 JSON、未知字段、类型、围栏、截断的现有行为。
3. 在 `JsonMemoryMapResponseParserTest` 增加至少三例：重复 `overview`、重复 `navigation`、重复 `domains`；若实现为通用重复 key 扫描，再补嵌套/转义边界。
4. 复跑 Parser、Generator、E2E 定向；保持 45 个旧用例不退化，新增后更新 source/XML 总数。
5. 只允许修改 `JsonMemoryMapResponseParser.kt`、`JsonMemoryMapResponseParserTest.kt` 和 `B1_Worker交付.md`；若需其他文件，停止报告。

## 5. 测试与执行证据

### 5.1 定向结果

```text
./scripts/dev gradle :context:core:test :context:runtime:test :integration-tests:context:test --rerun-tasks --console=plain
```

- 首次沙箱执行因 Gradle FileLock `SocketException: Operation not permitted` 未启动测试。
- 经沙箱外批准重跑：**exit 0**，`BUILD SUCCESSFUL in 46s`，32 tasks executed。

### 5.2 计数

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| context-core | 110 | 110 | 0 | 0 | 0 |
| context-runtime | 514 | 514 | 0 | 0 | 0 |
| integration-context | 22 | 22 | 0 | 0 | 0 |

14 模块当前合计 **1755=1755**，fail/error/skip 0。

### 5.3 测试真实性

- 7 个 CT18 测试类源码/XML 均为：Prompt 4、Validator 7、Renderer 6、Parser 7、Generator 14、Publisher 6、E2E 1；合计 45。
- E2E 使用真实 Context SQLite、真实 LocalFs `AlcyoneVfs`、Fake LlmClient；不是纯 mock。
- 测试覆盖 Snapshot 分页、生成期写入、T1、预算、length、空库、Renderer 严格解析、safeReplace-only、LocalFs 首建/替换、提交前保旧和 `STATE_ERROR + PARTIAL`。
- 未发现测试假执行或绕过 safeReplace 的生产路径。

## 6. 边界证据

- CT17 23 个代码/测试文件 SHA：**23/23 一致**。
- 81 VFS 基线：**75 一致、6 个 CT17 B1 批准差异、缺失 0、未批准差异 0**。
- Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- CT18 未修改 VFS、common、Schema/SQL、依赖、构建、API、Factory/Impl、Config/Loader、Persistence/Server。
- Context 既有 CM3/CT17 交付保持原状，与 CT18 18 文件白名单无冲突。
- `git diff --check`：exit 0，空输出。

## 7. 备注

### N1：E2E 的 internal 可见性抑制

`MemoryMapGenerationEndToEndTest` 使用文件级 `INVISIBLE_REFERENCE` suppression 访问 internal Runtime Map 组件；编译和实跑通过，但这是测试模块可见性风险，不改变生产可见性。当前不作为 R。

### N2：未独立复跑全量 check

3 模块定向、1755=1755、CT17 SHA、81 基线与边界均通过；R1 为 Parser 逻辑缺陷，不能由现有全量测试发现，因此本轮未重复 Worker 的全量 `./scripts/check`。

## 8. 是否提交 Architect

**否。**

- R1 未关闭，B1 保持 **IN_REVIEW**。
- 退回原 Worker 做最小 B1-R1；修复后重新提交 Lead 初验。
- CT18 未 DONE，CT19 未启动；真实模型 not-run；无 Git 写操作。
